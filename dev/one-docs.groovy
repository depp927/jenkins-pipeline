def createTag() {
    return new Date().format('yyyyMMddHHmmss') + "_${env.BUILD_ID}"
}

pipeline {
  agent any

  options {
    disableConcurrentBuilds()
    timestamps()
  }

  environment {
    appName           = "one-docs"
    gitURL            = "https://github.com/depp927/one-docs.git"
    gitBranch         = "main"
    baseImage         = "harbor.onepoker.cc/dev/node:20-alpine"
    appServerPort     = "8090"
    gitCredentials    = "depp927_github"
    harborURL         = "harbor.onepoker.cc"
    harborNS          = "dev"
    harborCredentials = "jenkins_user"
    appTag            = createTag()
    appImageURL       = "${harborURL}/${harborNS}/${appName}:${appTag}"
    gitopsURL         = "https://github.com/depp927/one-docs-gitops.git"
    gitopsCredentials = "depp927_github"
    gitopsOverlay     = "overlays/dev"
  }

  stages {

    stage("Pull Code") {
      steps {
        git url: gitURL,
            credentialsId: gitCredentials,
            branch: gitBranch
      }
    }

    stage("Build & Push Image") {
      steps {
        script {
          env.gitHash = sh(script: "git rev-parse HEAD", returnStdout: true).trim()

          def mDockerfile = """FROM ${baseImage} AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --only=production

FROM ${baseImage}
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=builder /app/node_modules ./node_modules
COPY server.js ./
COPY docs/ ./docs/
COPY public/ ./public/
COPY package.json ./
USER appuser
EXPOSE ${appServerPort}
ENTRYPOINT ["node", "server.js"]
"""

          writeFile file: 'Dockerfile', text: mDockerfile

          withCredentials([usernamePassword(
            credentialsId: harborCredentials,
            usernameVariable: 'HARBOR_USER',
            passwordVariable: 'HARBOR_PASS'
          )]) {
            sh """
              docker login ${harborURL} -u \${HARBOR_USER} -p \${HARBOR_PASS}
              docker build -t ${appImageURL} .
              docker push ${appImageURL}
              docker rmi ${appImageURL}
              docker logout ${harborURL}
            """
          }
        }
      }
    }

    stage("Update GitOps Repo") {
      steps {
        script {
          withCredentials([usernamePassword(
            credentialsId: gitopsCredentials,
            usernameVariable: 'GIT_USER',
            passwordVariable: 'GIT_PASS'
          )]) {
            sh """
              # 清理上次残留
              rm -rf gitops-repo

              # 克隆 GitOps 仓库
              git clone https://\${GIT_USER}:\${GIT_PASS}@${gitopsURL.replace('https://', '')} gitops-repo

              cd gitops-repo/${gitopsOverlay}

              # 用 kustomize 更新镜像 tag（只修改 kustomization.yaml）
              kustomize edit set image ${harborURL}/${harborNS}/${appName}:${appTag}

              # 提交并推送
              git config user.email "jenkins@ci.local"
              git config user.name "Jenkins CI"
              git add kustomization.yaml
              git commit -m "ci: update ${appName} image to ${appTag} [skip ci]"
              git push origin main

              # 清理
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
        currentBuild.displayName = "#${BUILD_NUMBER} [${appName}:${appTag}]"
      }
    }
  }

}
