def createTag() {
    return new Date().format('yyyyMMddHHmmss') + "_${env.BUILD_ID}"
}

pipeline {
    agent any

    // 自动读取 apps.yaml 生成下拉菜单（第一次运行后生效）
    parameters {
        choice(name: 'SELECT_APP_NAME', choices: [], description: '请选择应用名称')
    }

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    environment {
        // AWS ECR 基础配置
        AWS_REGION        = "ap-east-1"
        ECR_REGISTRY      = "390402568976.dkr.ecr.ap-east-1.amazonaws.com"
        ECR_CREDENTIALS   = "awsecr" // 你指定的 AK/SK 凭据 ID
        
        // Git 配置
        gitBranch         = "main"
        gitCredentials    = "depp927-githubtoken"
        gitopsCredentials = "depp927-githubtoken"
        gitopsOverlay     = "overlays/dev"
        appTag            = createTag()
    }

    stages {
        stage("Initialize & Dynamic Params") {
            steps {
                script {
                    // 1. 读取 YAML 元数据以更新参数列表和获取应用配置
                    def catalog = readYaml file: 'appmeta/apps.yaml'
                    def appNames = catalog.apps.keySet().collect { it.toString() }
                    
                    // 动态更新 Jenkins 参数（下一次构建时将出现下拉列表）
                    properties([
                        parameters([
                            choice(name: 'SELECT_APP_NAME', choices: appNames, description: '请选择应用名称')
                        ])
                    ])

                    // 如果是第一次运行或手动触发，确保有默认选值
                    def selectedApp = params.SELECT_APP_NAME ?: appNames[0]
                    def appConfig = catalog.apps[selectedApp]

                    if (!appConfig) {
                        error "应用 [${selectedApp}] 未在 apps.yaml 中定义！"
                    }

                    // 2. 注入动态环境变量
                    env.appName     = selectedApp
                    env.gitURL      = appConfig.repo_url
                    env.gitopsURL   = appConfig.gitops_repo
                    // ECR 标准镜像格式：Registry/Repository:Tag
                    env.appImageURL = "${env.ECR_REGISTRY}/${selectedApp}:${env.appTag}"

                    echo "--- Metadata Loaded ---"
                    echo "App Name: ${env.appName}"
                    echo "Image URL: ${env.appImageURL}"
                }
            }
        }

        stage("Pull Code") {
            steps {
                dir('source_code') {
                    git url: env.gitURL,
                        credentialsId: env.gitCredentials,
                        branch: env.gitBranch
                }
            }
        }

        stage("Build & Push to ECR") {
            steps {
                script {
                    dir('source_code') {
                        // 使用你定义的 awsecr 凭据映射为 AWS CLI 识别的环境变量
                        withCredentials([usernamePassword(
                            credentialsId: env.ECR_CREDENTIALS,
                            usernameVariable: 'AWS_ACCESS_KEY_ID',
                            passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                        )]) {
                            sh """
                                # 检查 Dockerfile
                                if [ ! -f Dockerfile ]; then
                                    echo "Error: Dockerfile not found!"
                                    exit 1
                                fi

                                # ECR 登录：使用 AWS CLI 获取临时 Token 并传给 Docker
                                aws ecr get-login-password --region ${env.AWS_REGION} | docker login --username AWS --password-stdin ${env.ECR_REGISTRY}
                                
                                # 构建与推送
                                docker build -t ${env.appImageURL} .
                                docker push ${env.appImageURL}
                                
                                # 清理本地镜像
                                docker rmi ${env.appImageURL}
                            """
                        }
                    }
                }
            }
        }

        stage("Update GitOps Repo") {
            steps {
                script {
                    withCredentials([usernamePassword(
                        credentialsId: env.gitopsCredentials,
                        usernameVariable: 'GIT_USER',
                        passwordVariable: 'GIT_PASS'
                    )]) {
                        sh """
                            rm -rf gitops-repo
                            git clone https://\${GIT_USER}:\${GIT_PASS}@${env.gitopsURL.replace('https://', '')} gitops-repo
                            
                            cd gitops-repo/${env.gitopsOverlay}
                            
                            # 更新 Kustomize 镜像配置
                            kustomize edit set image ${env.appImageURL}
                            
                            git config user.email "jenkins@ci.local"
                            git config user.name "Jenkins CI"
                            git add kustomization.yaml
                            git commit -m "ci: update ${env.appName} image to ${env.appTag} [skip ci]"
                            git push origin main
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
        }
    }
}
