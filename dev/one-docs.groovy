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
              docker login ${harborURL} -u ${HARBOR_USER} -p ${HARBOR_PASS}
              docker build -t ${appImageURL} .
              docker push ${appImageURL}
              docker rmi ${appImageURL}
              docker logout ${harborURL}
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
