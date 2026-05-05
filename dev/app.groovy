def createTag() {
    return new Date().format('yyyyMMddHHmmss') + "_${env.BUILD_ID}"
}

pipeline {
    agent any

    parameters {
        // 构建时手动选择应用名，或通过 Webhook 自动传入
        string(name: 'SELECT_APP_NAME', defaultValue: 'one-docs', description: '请输入应用名称')
    }

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    environment {
        // 全局基础配置
        harborURL         = "harbor.onepoker.cc"
        harborNS          = "dev"
        harborCredentials = "jenkins_user"
        gitBranch         = "main"
        gitCredentials    = "depp927-githubtoken"
        gitopsCredentials = "depp927_github"
        gitopsOverlay     = "overlays/dev"
        appTag            = createTag()
    }

    stages {
        stage("Initialize Metadata") {
            steps {
                script {
                    // 1. 读取运维维护的配置清单 (请确保该文件在执行前已存在于 workspace)
                    def catalog = readYaml file: 'appmeta/apps.yaml'
                    def appConfig = catalog.apps[params.SELECT_APP_NAME]

                    if (!appConfig) {
                        error "应用 [${params.SELECT_APP_NAME}] 未在 apps.yaml 中定义！"
                    }

                    // 2. 注入动态环境变量
                    env.appName     = params.SELECT_APP_NAME
                    env.gitURL      = appConfig.repo_url
                    env.gitopsURL   = appConfig.gitops_repo
                    env.appImageURL = "${env.harborURL}/${env.harborNS}/${env.appName}:${env.appTag}"

                    echo "--- Metadata Loaded ---"
                    echo "App Name: ${env.appName}"
                    echo "Git URL:  ${env.gitURL}"
                }
            }
        }

        stage("Pull Code") {
            steps {
                // 将代码拉取到 source_code 目录，避免与运维配置目录冲突
                dir('source_code') {
                    git url: env.gitURL,
                        credentialsId: env.gitCredentials,
                        branch: env.gitBranch
                }
            }
        }

        stage("Build & Push Image") {
            steps {
                script {
                    // 进入业务代码目录执行构建，此时会读取该目录下的 Dockerfile
                    dir('source_code') {
                        env.gitHash = sh(script: "git rev-parse HEAD", returnStdout: true).trim()

                        withCredentials([usernamePassword(
                            credentialsId: env.harborCredentials,
                            usernameVariable: 'HARBOR_USER',
                            passwordVariable: 'HARBOR_PASS'
                        )]) {
                            sh """
                                # 检查 Dockerfile 是否存在
                                if [ ! -f Dockerfile ]; then
                                    echo "Error: Dockerfile not found in repository root!"
                                    exit 1
                                fi

                                docker login ${env.harborURL} -u \${HARBOR_USER} -p \${HARBOR_PASS}
                                docker build -t ${env.appImageURL} .
                                docker push ${env.appImageURL}
                                docker rmi ${env.appImageURL}
                                docker logout ${env.harborURL}
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
                            
                            # 确保已安装 kustomize
                            kustomize edit set image ${env.harborURL}/${env.harborNS}/${env.appName}:${env.appTag}
                            
                            git config user.email "jenkins@ci.local"
                            git config user.name "Jenkins CI"
                            git add kustomization.yaml
                            git commit -m "ci: update ${env.appName} image to ${env.appTag} [skip ci]"
                            git push origin main
                            
                            cd ../..
                            rm -rf gitops-repo
                        """
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                def displayTag = env.appTag ?: "unknown"
                currentBuild.displayName = "#${BUILD_NUMBER} [${params.SELECT_APP_NAME}:${displayTag}]"
            }
        }
    }
}
