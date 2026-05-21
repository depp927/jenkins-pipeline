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
        AWS_CRED_ID         = "aws-aksk" // 统一维护的AWS 凭据 ID

        // 已经成功从 GitHub 迁移至 ECR OCI 的公共模板配置
        COMMON_CHART_NAME   = "dev/node-common-chart"
        COMMON_CHART_VERSION= "0.1.0" 

        gitBranch           = "main"
        gitCredentials      = "depp927"
    }

    stages {
        // ──────────────────────────────────────────
        // Stage 1: 拉取应用源码（支持 Workspace 隔离留痕）
        // ──────────────────────────────────────────
        stage("Pull App Source") {
            steps {
                script {
                    env.BUILD_DIR = "builds/build_${env.BUILD_ID}"
                    def catalog = readYaml file: 'appmeta/apps.yaml'
                    def appNames = catalog.apps.keySet().collect { it.toString() }
                    
                    def selectedApp = (params.SELECT_APP_NAME == 'placeholder' || !params.SELECT_APP_NAME) ? appNames[0] : params.SELECT_APP_NAME
                    def appConfig   = catalog.apps[selectedApp]
                    
                    if (!appConfig) { error "应用 [${selectedApp}] 未在 apps.yaml 中定义！" }
                    env.gitURL = appConfig.repo_url
                    env.appName = selectedApp
                    
                    dir("${env.BUILD_DIR}/source_code") {
                        git url: env.gitURL,
                            credentialsId: env.gitCredentials,
                            branch: env.gitBranch
                    }
                }
            }
        }

        // ──────────────────────────────────────────
        // Stage 2: 解析元数据 & 动态生成符合 SemVer 规范的 appTag
        // ──────────────────────────────────────────
        stage("Initialize & Dynamic Params") {
            steps {
                script {
                    def catalog = readYaml file: 'appmeta/apps.yaml'
                    def appConfig = catalog.apps[env.appName]
                    
                    def gitCommit = ""
                    dir("${env.BUILD_DIR}/source_code") {
                        gitCommit = sh(returnStdout: true, script: "git rev-parse --short=7 HEAD").trim()
                    }
                    
                    def todayDate = new Date().format('yyyyMMdd')
                    // 完美兼容 Docker 和 Helm 规范的语义化版本号
                    env.appTag    = "0.0.${env.BUILD_ID}-${todayDate}.${gitCommit}"
                    env.appImageRepo  = "${env.ECR_REGISTRY}/${env.appName}"
                    env.appImageURL   = "${env.appImageRepo}:${env.appTag}"
                    env.chartRepoName = "dev/${env.appName}-chart"

                    env.appPort       = appConfig.port        ? appConfig.port.toString()        : "8080"
                    env.appReplicas   = appConfig.replicas    ? appConfig.replicas.toString()    : "1"
                    env.appIngress    = appConfig.ingress_host ?: ""

                    echo "=== 🚀 规范化元数据已生成 ==="
                    echo "生成的新 Tag : ${env.appTag}"
                    echo "目标容器镜像 : ${env.appImageURL}"
                }
            }
        }

        // ──────────────────────────────────────────
        // Stage 3: 构建业务镜像并推送到 ECR
        // ──────────────────────────────────────────
        stage("Build & Push Image") {
            steps {
                script {
                    dir("${env.BUILD_DIR}/source_code") {
                        withAWS(credentials: "${env.AWS_CRED_ID}", region: "${env.AWS_REGION}") {
                            sh """
                                if [ ! -f Dockerfile ]; then
                                    echo "ERROR: Dockerfile not found!"
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
        // Stage 4: 渲染、打包并推送应用专属 Helm Chart（解决空指针问题）
        // ──────────────────────────────────────────
        stage("Package & Push Helm Chart") {
            steps {
                script {
                    withAWS(credentials: "${env.AWS_CRED_ID}", region: "${env.AWS_REGION}") {
                        sh """
                            aws ecr get-login-password --region ${env.AWS_REGION} \
                                | helm registry login --username AWS --password-stdin ${env.ECR_BASE_DOMAIN}

                            rm -rf common_chart_download && mkdir common_chart_download
                            cd common_chart_download
                            helm pull oci://${env.ECR_BASE_DOMAIN}/${env.COMMON_CHART_NAME} --version "${env.COMMON_CHART_VERSION}" --untar
                            
                            cd ..
                            rm -rf ${env.appName}-chart
                            cp -r common_chart_download/node-common-chart ${env.appName}-chart
                            rm -rf common_chart_download
                        """

                        def chartYaml = """apiVersion: v2
name: ${env.appName}-chart
description: Helm chart for ${env.appName} (dynamically generated by Jenkins from ECR common template)
type: application
version: "${env.appTag}"
appVersion: "${env.appTag}"
"""
                        writeFile file: "${env.appName}-chart/Chart.yaml", text: chartYaml

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

serviceAccount:
  create: false
  annotations: {}
  name: ""

autoscaling:
  enabled: true
  minReplicas: 1
  maxReplicas: 3
  targetCPUUtilizationPercentage: 80
  targetMemoryUtilizationPercentage: 80
"""
                        writeFile file: "${env.appName}-chart/values.yaml", text: valuesYaml

                        sh """
                            aws ecr describe-repositories --repository-names ${env.chartRepoName} --region ${env.AWS_REGION} 2>/dev/null \
                            || aws ecr create-repository --repository-name ${env.chartRepoName} --region ${env.AWS_REGION}

                            helm lint ${env.appName}-chart
                            helm package ${env.appName}-chart --destination .

                            helm push ${env.appName}-chart-${env.appTag}.tgz oci://${env.ECR_BASE_DOMAIN}/dev
                        """
                    }
                }
            }
        }

        // ──────────────────────────────────────────
        // Stage 5: 自动改写 GitOps 仓库文件，触发 ArgoCD 部署（修复版）
        // ──────────────────────────────────────────
        stage("Trigger GitOps Deployment") {
            steps {
                script {
                    dir('gitops_repo') {
                        // 1. 克隆 GitOps 控制中心仓库
                        git url: 'https://github.com/depp927/eks-gitops-manifests.git',
                            credentialsId: env.gitCredentials,
                            branch: 'main'
                        
                        def manifestPath = "apps/base/${env.appName}.yaml"
                        
                        if (!fileExists(manifestPath)) {
                            error "GitOps 仓库中未找到应用的 Application 声明文件: ${manifestPath}"
                        }

                        // 2. 借助 withCredentials 临时把你的 Git 凭据解密为环境变量，供 sh 块内的 git push/pull 授权使用
                        // 这里假设你的 'depp927' 凭据在 Jenkins 里是 "Username with password" 或 "Secret text" 类型
                        // 如果是 Token，usernameVariable 可以随便起名，passwordVariable 存放真正的 Token
                        withCredentials([usernamePassword(
                            credentialsId: "${env.gitCredentials}", 
                            usernameVariable: 'GIT_USER', 
                            passwordVariable: 'GIT_TOKEN'
                        )]) {
                            
                            sh """
                                # 🛠️ 修复点 1：加双引号，确保 sed 命令在 Shell 中被正确精准执行
                                sed -i "/chart: ${env.appName}-chart/{n;s/targetRevision:.*/targetRevision: ${env.appTag}/}" ${manifestPath}
                                
                                # 配置本地 Git 审计身份
                                git config user.name "Jenkins CI"
                                git config user.email "jenkins@yourcompany.com"
                                
                                # 临时重写远程仓库的 URL，把令牌（Token）动态塞进去，确保免密 Push/Pull 成功
                                # 这样写可以完美打通 github.com 的认证
                                git remote set-url origin https://${GIT_USER}:${GIT_TOKEN}@github.com/你的组织/k8s-gitops-manifests.git
                                
                                # 提交修改
                                git add ${manifestPath}
                                git commit -m "image: auto update ${env.appName} to ${env.appTag} [skip ci]" || echo "No changes to commit"
                                
                                # 🛠️ 修复点 2：此时有了 Token 注入，pull --rebase 和 push 就能畅通无阻了
                                git pull --rebase origin main
                                git push origin main
                            """
                        }
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
            // 保持注释掉 cleanWs()，让本地 builds/ 目录持续留痕记录
        }
        success {
            echo """
======================================================================
  🎉 GitOps 全链路自动化交付成功完成！
  应用名称 : ${env.appName}
  统一 Tag : ${env.appTag}
  容器镜像 : ${env.appImageURL}
  ArgoCD 状态: 变动已推送至 GitOps 仓库，正在集群内进行滚动更新。
======================================================================
"""
        }
    }
}