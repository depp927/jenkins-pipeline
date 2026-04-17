def createTag() {
    return new Date().format('yyyyMMddHHmmss') + "_${env.BUILD_ID}"
}

pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    parameters {
        choice(name: 'DEPLOY_ENV', choices: ['dev', 'test', 'prod'], description: '选择部署环境 (Select Deployment Environment)')
        string(name: 'BRANCH_NAME', defaultValue: 'main', description: '代码分支 (Git Branch)')
    }

    environment {
        appName = "oneplay-onebox"
        gitURL = "https://github.com/one-chain-labs/oneplay-ONEBOX.git"
        startCMD = "pnpm run dev"
        baseImage = "harbor.onelabs.cc/base-images/node:22-pnpm"
        buildEnv = "harbor.onelabs.cc/base-images/node:22-pnpm"
        appServerPort = "8080"
        pullImageCredentials = "harbor-img"
        appTag = createTag()
        gitCredentials = "jenkins_github_token"
        harborHost = "harbor.onelabs.cc"
        harborProtocol = "https"
        harborURL = "${harborProtocol}://${harborHost}"
        harborCredentials = "jenkins_user"
    }

    stages {
        stage('Initialize Environment') {
            steps {
                script {
                    switch(params.DEPLOY_ENV) {
                        case 'dev':
                            env.k8sNamespace = "dev"
                            env.harborNS = "dev"
                            env.k8sURL = "https://DEV_EKS_URL.gr7.ap-southeast-1.eks.amazonaws.com"
                            env.k8sCredentials = "jenkins_to_dev_eks"
                            env.replicasMin = "1"
                            env.replicasMax = "2"
                            env.cpuRequest = "500m"
                            env.memRequest = "1Gi"
                            env.cpuLimit = "1"
                            env.memLimit = "2Gi"
                            break
                        case 'test':
                            env.k8sNamespace = "test"
                            env.harborNS = "test"
                            env.k8sURL = "https://TEST_EKS_URL.gr7.ap-southeast-1.eks.amazonaws.com"
                            env.k8sCredentials = "jenkins_to_test_eks"
                            env.replicasMin = "1"
                            env.replicasMax = "2"
                            env.cpuRequest = "500m"
                            env.memRequest = "1Gi"
                            env.cpuLimit = "1"
                            env.memLimit = "2Gi"
                            break
                        case 'prod':
                            env.k8sNamespace = "prod"
                            env.harborNS = "prod"
                            env.k8sURL = "https://F0F01017F747118C6284BED24A9CE38D.gr7.ap-southeast-1.eks.amazonaws.com"
                            env.k8sCredentials = "jenkins_to_game_eks"
                            env.replicasMin = "1"
                            env.replicasMax = "4"
                            env.cpuRequest = "1"
                            env.memRequest = "2Gi"
                            env.cpuLimit = "1"
                            env.memLimit = "4Gi"
                            break
                        default:
                            error "Unknown environment: ${params.DEPLOY_ENV}"
                    }

                    env.appImageURL = "${env.harborHost}/${env.harborNS}/${env.appName}:${env.appTag}"
                    echo "Deploying to Environment : ${params.DEPLOY_ENV}"
                    echo "Namespace                : ${env.k8sNamespace}"
                    echo "Image URL                : ${env.appImageURL}"
                }
            }
        }

        stage("Git PullCode") {
            steps {
                git url: gitURL,
                    credentialsId: gitCredentials,
                    branch: params.BRANCH_NAME
            }
        }

        stage("npm Build") {
            steps {
                script {
                    realWK = WORKSPACE.replaceAll("^/var", "/data")
                    sh """
                    docker run --rm -v $realWK:/build \\
                     $buildEnv bash -c "cd /build && pnpm install && pnpm run build"
                    """
                }
            }
        }

        stage("docker Build") {
            steps {
                script {
                    def mDockerfile = '''
FROM $baseImage
ENV PORT=8080
WORKDIR /app
COPY . /app/
EXPOSE 8080
ENTRYPOINT exec $startCMD
                    '''
                    docker.withRegistry(harborURL, harborCredentials) {
                        sh """
                            echo "$mDockerfile" > Dockerfile
                            docker build -t ${appImageURL} .
                            docker push ${appImageURL}
                            docker rmi ${appImageURL}
                        """
                    }
                }
            }
        }

        stage("k8s Deploy") {
            steps {
                script {
                    // 使用 sed 将模板文件中的占位符替换为实际变量值，生成最终 deploy.yaml
                    sh """
                        sed \
                          -e 's|__APP_NAME__|${appName}|g' \
                          -e 's|__K8S_NAMESPACE__|${k8sNamespace}|g' \
                          -e 's|__APP_IMAGE_URL__|${appImageURL}|g' \
                          -e 's|__APP_SERVER_PORT__|${appServerPort}|g' \
                          -e 's|__REPLICAS_MIN__|${replicasMin}|g' \
                          -e 's|__REPLICAS_MAX__|${replicasMax}|g' \
                          -e 's|__CPU_REQUEST__|${cpuRequest}|g' \
                          -e 's|__MEM_REQUEST__|${memRequest}|g' \
                          -e 's|__CPU_LIMIT__|${cpuLimit}|g' \
                          -e 's|__MEM_LIMIT__|${memLimit}|g' \
                          -e 's|__PULL_IMAGE_CREDENTIALS__|${pullImageCredentials}|g' \
                          deploy.yaml.tpl > deploy.yaml
                    """

                    withKubeConfig([serverUrl: k8sURL, credentialsId: k8sCredentials]) {
                        sh "kubectl apply -f deploy.yaml"
                    }
                }
            }
        }

        stage("k8s Rollback") {
            options {
                timeout(time: 12, unit: "HOURS")
            }
            input {
                ok "submit"
                message "Rollback Deployment in ${params.DEPLOY_ENV}?"
                parameters {
                    booleanParam(name: 'ROLLBACK', defaultValue: false, description: "Select to rollback the current deployment.")
                }
            }
            steps {
                script {
                    if (ROLLBACK == true || ROLLBACK == "true") {
                        withKubeConfig([serverUrl: k8sURL, credentialsId: k8sCredentials]) {
                            sh "kubectl rollout undo deployment ${appName} -n ${k8sNamespace}"
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                wrap([$class: 'BuildUser']) {
                    currentBuild.displayName = "#${BUILD_NUMBER} [${params.DEPLOY_ENV}] ${BUILD_USER ?: 'auto'}"
                    currentBuild.description = "Branch: ${params.BRANCH_NAME} | Env: ${params.DEPLOY_ENV}"
                }
            }
        }
    }
}

