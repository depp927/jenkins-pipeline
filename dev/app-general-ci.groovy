pipeline {
    agent any

    parameters {
        choice(name: 'SELECT_APP_NAME', choices: ['placeholder'], description: '请选择应用名称（首次运行请直接点构建以初始化列表）')
    }

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    environment {
        AWS_REGION          = "ap-east-1"
        ECR_BASE_DOMAIN     = "390402568976.dkr.ecr.ap-east-1.amazonaws.com"
        ECR_REGISTRY        = "390402568976.dkr.ecr.ap-east-1.amazonaws.com/dev"
        AWS_CRED_ID         = "aws-credentials-id" // 统一维护你的 AWS 凭据 ID（如果是 EC2 IAM 角色模式，此变量留空字符串即可）

        // 🛠️ 已经成功从 GitHub 迁移至 ECR OCI 的公共模板配置
        COMMON_CHART_NAME   = "dev/node-common-chart"
        COMMON_CHART_VERSION= "0.1.0" // 填入你在本地笔记本上传的那个基础版本号

        gitBranch           = "main"
        gitCredentials      = "depp927-githubtoken"
    }

    stages {
        // ──────────────────────────────────────────
        // Stage 1: 拉取应用源码
        // ──────────────────────────────────────────
        stage("Pull App Source") {
            steps {
                script {
                    def catalog = readYaml file: 'appmeta/apps.yaml'
                    def appNames = catalog.apps.keySet().collect { it.toString() }
                    
                    def selectedApp = (params.SELECT_APP_NAME == 'placeholder' || !params.SELECT_APP_NAME) ? appNames[0] : params.SELECT_APP_NAME
                    def appConfig   = catalog.apps[selectedApp]
                    
                    if (!appConfig) { error "应用 [${selectedApp}] 未在 apps.yaml 中定义！" }
                    env.gitURL = appConfig.repo_url
                    env.appName = selectedApp
                }
                
                dir('source_code') {
                    git url: env.gitURL,
                        credentialsId: env.gitCredentials,
                        branch: env.gitBranch
                }
            }
        }

        // ──────────────────────────────────────────
        // Stage 2: 解析元数据 & 动态生成复杂的 appTag
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

                    def appConfig = catalog.apps[env.appName]
                    
                    def gitCommit = ""
                    dir('source_code') {
                        gitCommit = sh(returnStdout: true, script: "git rev-parse --short=7 HEAD").trim()
                    }
                    
                    def todayDate = new Date().format('yyyyMMdd')
                    env.appTag    = "${todayDate}_${gitCommit}_${env.BUILD_ID}"

                    env.appImageRepo  = "${env.ECR_REGISTRY}/${env.appName}"
                    env.appImageURL   = "${env.appImageRepo}:${env.appTag}"
                    env.chartRepoName = "dev/${env.appName}-chart"

                    env.appPort       = appConfig.port        ? appConfig.port.toString()        : "3000"
                    env.appReplicas   = appConfig.replicas    ? appConfig.replicas.toString()    : "1"
                    env.appIngress    = appConfig.ingress_host ?: ""

                    echo "=== 🚀 规范化元数据已生成 ==="
                    echo "生成的新 Tag : ${env.appTag}"
                    echo "目标容器镜像 : ${env.appImageURL}"
                    echo "目标 Helm 包 : oci://${env.ECR_BASE_DOMAIN}/${env.chartRepoName} Tag: ${env.appTag}"
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
                        withAWS(credentials: "${env.AWS_CRED_ID}", region: "${env.AWS_REGION}") {
                            sh """
                                if [ ! -f Dockerfile ]; then
                                    echo "ERROR: Dockerfile not found in source_code directory!"
                                    exit 1
                                fi

                                aws ecr get-login-password --region ${env.AWS_REGION} \
                                    | docker login --username AWS --password-stdin ${env.ECR_BASE_DOMAIN}

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
        // Stage 4: 渲染、打包并推送应用专属 Helm Chart（已重构为 ECR 互通模式）
        // ──────────────────────────────────────────
        stage("Package & Push Helm Chart") {
            steps {
                script {
                    // 统一在一个 AWS 权限块内处理拉取、修改与推送
                    withAWS(credentials: "${env.AWS_CRED_ID}", region: "${env.AWS_REGION}") {
                        sh """
                            # 1. 登录 Helm OCI 注册表 (使用根域名)
                            aws ecr get-login-password --region ${env.AWS_REGION} \
                                | helm registry login --username AWS --password-stdin ${env.ECR_BASE_DOMAIN}

                            # 2. 从 ECR OCI 拉取由你本地上传的公共基础模板，并直接就地解压成文件夹 (--untar)
                            rm -rf common_chart_download && mkdir common_chart_download
                            cd common_chart_download
                            helm pull oci://${env.ECR_BASE_DOMAIN}/${env.COMMON_CHART_NAME} --version "${env.COMMON_CHART_VERSION}" --untar
                            
                            # 3. 将解压出来的通用基础目录，复制到外层并改名成专属应用 Chart 目录
                            cd ..
                            rm -rf ${env.appName}-chart
                            cp -r common_chart_download/node-common-chart ${env.appName}-chart
                            rm -rf common_chart_download
                        """

                        // 4-3. 覆写应用专属的 Chart.yaml 描述文件
                        def chartYaml = """apiVersion: v2
name: ${env.appName}-chart
description: Helm chart for ${env.appName} (dynamically generated by Jenkins from ECR common template)
type: application
version: "${env.appTag}"
appVersion: "${env.appTag}"
"""
                        writeFile file: "${env.appName}-chart/Chart.yaml", text: chartYaml

                        // 4-4. 覆写应用专属的 values.yaml 变量配置
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

                        // 4-5. 维护专属应用的 ECR 仓库并执行最终推送
                        sh """
                            # 1. 检查并确保 ECR 对应应用的独立 chart 仓库存在（如 dev/my-app-chart）
                            aws ecr describe-repositories --repository-names ${env.chartRepoName} --region ${env.AWS_REGION} 2>/dev/null \
                            || aws ecr create-repository --repository-name ${env.chartRepoName} --region ${env.AWS_REGION}

                            # 2. 语法校验与本地构建打包
                            helm lint ${env.appName}-chart
                            helm package ${env.appName}-chart --destination .

                            # 3. 推送到该应用在 ECR 的专属私有 Chart 仓库中
                            helm push ${env.appName}-chart-${env.appTag}.tgz oci://${env.ECR_BASE_DOMAIN}/dev
                        """
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                def displayApp = env.appName ?: "init"
                def displayTag = env.appTag ?: "none"
                currentBuild.displayName = "#${BUILD_NUMBER} [${displayApp}:${displayTag}]"
            }
            cleanWs()
        }
        success {
            echo """
======================================================================
  🎉 Pipeline 成功完成！公共模板已完全切为 ECR OCI 互通。
  应用名称 : ${env.appName}
  统一 Tag : ${env.appTag}
  容器镜像 : ${env.appImageURL}
  Helm Chart: oci://${env.ECR_BASE_DOMAIN}/${env.chartRepoName} Tag: ${env.appTag}
======================================================================
"""
        }
    }
}