def createTag() {
    return new Date().format('yyyyMMddHHmmss') + "_${env.BUILD_ID}"
}

pipeline {
    agent any

    parameters {
        choice(name: 'SELECT_APP_NAME', choices: [], description: '请选择应用名称')
    }

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    environment {
        AWS_REGION          = "ap-east-1"
        ECR_REGISTRY        = "390402568976.dkr.ecr.ap-east-1.amazonaws.com/dev"
        ECR_CREDENTIALS     = "awsecr"

        // 通用 Helm Chart 模板配置
        COMMON_CHART_REPO   = "https://github.com/depp927/node-common-chart.git"
        COMMON_CHART_BRANCH = "main"
        gitChartCredentials = "depp927-githubtoken"

        gitBranch           = "main"
        gitCredentials      = "depp927-githubtoken"
        appTag              = createTag()
    }

    stages {
        // ──────────────────────────────────────────
        // Stage 1: 解析元数据 & 动态注入
        // ──────────────────────────────────────────
        stage("Initialize & Dynamic Params") {
            steps {
                script {
                    def catalog = readYaml file: 'appmeta/apps.yaml'
                    def appNames = catalog.apps.keySet().collect { it.toString() }

                    properties([
                        parameters([
                            choice(name: 'SELECT_APP_NAME', choices: appNames, description: '请选择应用名称')
                        ])
                    ])

                    def selectedApp = params.SELECT_APP_NAME ?: appNames[0]
                    def appConfig   = catalog.apps[selectedApp]

                    if (!appConfig) {
                        error "应用 [${selectedApp}] 未在 apps.yaml 中定义！"
                    }

                    env.appName       = selectedApp
                    env.gitURL        = appConfig.repo_url
                    
                    // 统一规范：Docker 镜像与 Helm Chart 在 ECR 中分别独立仓库存放
                    env.appImageRepo  = "${env.ECR_REGISTRY}/${selectedApp}"
                    env.appImageURL   = "${env.appImageRepo}:${env.appTag}"
                    env.chartRepoName = "dev/${selectedApp}-chart" // 供 AWS CLI 创建仓库使用

                    // 动态从 apps.yaml 获取高级参数，提供生产合理的缺省值
                    env.appPort       = appConfig.port        ? appConfig.port.toString()        : "3000"
                    env.appReplicas   = appConfig.replicas    ? appConfig.replicas.toString()    : "1"
                    env.appIngress    = appConfig.ingress_host ?: ""

                    echo "=== 🚀 Metadata Loaded ==="
                    echo "Deploying App : ${env.appName}"
                    echo "Target Image  : ${env.appImageURL}"
                    echo "Target Chart  : oci://${env.ECR_REGISTRY}/${selectedApp}-chart:${env.appTag}"
                }
            }
        }

        // ──────────────────────────────────────────
        // Stage 2: 拉取应用源码
        // ──────────────────────────────────────────
        stage("Pull App Source") {
            steps {
                dir('source_code') {
                    git url: env.gitURL,
                        credentialsId: env.gitCredentials,
                        branch: env.gitBranch
                }
            }
        }

        // ──────────────────────────────────────────
        // Stage 3: 构建业务镜像并推送到 ECR
        // ──────────────────────────────────────────
        stage("Build & Push Image") {
            steps {
                script {
                    dir('source_code') {
                        withCredentials([usernamePassword(
                            credentialsId: env.ECR_CREDENTIALS,
                            usernameVariable: 'AWS_ACCESS_KEY_ID',
                            passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                        )]) {
                            sh """
                                if [ ! -f Dockerfile ]; then
                                    echo "ERROR: Dockerfile not found!"
                                    exit 1
                               fi

                                aws ecr get-login-password --region ${env.AWS_REGION} \
                                    | docker login --username AWS --password-stdin ${env.ECR_REGISTRY}

                                docker build -t ${env.appImageURL} .
                                docker push ${env.appImageURL}
                                docker rmi ${env.appImageURL} || true
                            """
                        }
                    }
                }
            }
        }

        // ──────────────────────────────────────────
        // Stage 4: 渲染、打包并推送应用专属 Helm Chart
        // ──────────────────────────────────────────
        stage("Package & Push Helm Chart") {
            steps {
                script {
                    // 4-1. 克隆公共模板
                    dir('common_chart') {
                        git url: env.COMMON_CHART_REPO,
                            credentialsId: env.gitChartCredentials,
                            branch: env.COMMON_CHART_BRANCH
                    }

                    // 4-2. 基于公共模板创建隔离的构建工作目录
                    // 修复细节：为了让 Chart 顺利推送到私有仓库 ${env.appName}-chart，Chart 文件夹命名及里面 Chart.yaml 的 name 必须叫 ${env.appName}-chart
                    sh "cp -r common_chart ${env.appName}-chart"

                    // 4-3. 覆写专属 Chart.yaml
                    def chartYaml = """apiVersion: v2
name: ${env.appName}-chart
description: Helm chart for ${env.appName} (dynamically generated by Jenkins)
type: application
version: "${env.appTag}"
appVersion: "${env.appTag}"
"""
                    writeFile file: "${env.appName}-chart/Chart.yaml", text: chartYaml

                    // 4-4. 覆写专属 values.yaml (确保字段名称契合你的公共 Chart 模板)
                    def valuesYaml = """# Auto-generated by Jenkins CI — DO NOT EDIT MANUALLY
# Target App: ${env.appName}

replicaCount: ${env.appReplicas}

image:
  repository: ${env.appImageRepo}
  tag: "${env.appTag}"
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: ${env.appPort}

ingress:
  enabled: ${env.appIngress ? 'true' : 'false'}
  host: "${env.appIngress}"
"""
                    writeFile file: "${env.appName}-chart/values.yaml", text: valuesYaml

                    // 4-5. 自动维护 ECR 并推送 Chart 制品
                    withCredentials([usernamePassword(
                        credentialsId: env.ECR_CREDENTIALS,
                        usernameVariable: 'AWS_ACCESS_KEY_ID',
                        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )]) {
                        sh """
                            # 1. 登录 Helm OCI 注册表
                            aws ecr get-login-password --region ${env.AWS_REGION} \
                                | helm registry login --username AWS --password-stdin ${env.ECR_REGISTRY}

                            # 2. 检查并确保 ECR 中对应的专属 -chart 仓库存在，无则自动创建
                            aws ecr describe-repositories --repository-names ${env.chartRepoName} --region ${env.AWS_REGION} 2>/dev/null \
                            || aws ecr create-repository --repository-name ${env.chartRepoName} --region ${env.AWS_REGION}

                            # 3. 校验并打包
                            helm lint ${env.appName}-chart
                            helm package ${env.appName}-chart --destination .

                            # 4. 推送到 OCI 托管仓库
                            helm push ${env.appName}-chart-${env.appTag}.tgz oci://${env.ECR_REGISTRY}
                        """
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                def displayApp = params.SELECT_APP_NAME ?: "init"
                currentBuild.displayName = "#${BUILD_NUMBER} [${displayApp}:${env.appTag}]"
            }
            // 生产洁癖：彻底清理构建痕迹，防止单机存储穿孔
            cleanWs()
        }
        success {
            echo """
======================================================================
  🎉 Pipeline 成功完成！制品已全部上架 AWS ECR OCI 仓库
  应用名称 : ${env.appName}
  容器镜像 : ${env.appImageURL}
  Helm Chart: oci://${env.ECR_REGISTRY}/${env.appName}-chart Tag: ${env.appTag}
======================================================================
"""
        }
    }
}