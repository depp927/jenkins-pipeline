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
    appName = "one-docs"
    gitURL = "https://github.com/depp927/one-docs.git"
    gitBranch = "main"
    buildImage = "arbor.onepoker.cc/dev/node:20-alpine"
    baseImage = "harbor.onepoker.cc/dev/node:20-alpine"
    appServerPort = "8090"
    pullImageCredentials = "harbor-img"
    appTag =  createTag()
    gitCredentials = "depp927_github"
    harborURL = "https://harbor.onepoker.cc"
    harborNS = "dev"
    harborCredentials = "jenkins_user"
    appImageURL = "${harborHost}/${harborNS}/${appName}:${appTag}"
  }
  
  stages {

    stage("gitlabe PullCode") {
      steps {
        git url: gitURL,
        credentialsId: gitCredentials,
        branch: gitBranch
      }
    }

    stage("maven Build") {
      steps {
        script {
          realWK = WORKSPACE.replaceAll("^/var", "/data")
          sh """
          docker run --rm -v $realWK:/build \\
          $buildImage bash -c "cd /build && npm install"
          """
        }
      }
    }

    stage("docker Build") {
      steps {
        sh "rm -rf dockerfiles"
        dir("dockerfiles") {
          script {
            
            env.gitHash = sh (script: "git rev-parse HEAD", returnStdout: true).trim()
            def mDockerfile = '''
FROM $baseImage
WORKSPACE /app
COPY . /app
ENTRYPOINT npm start
            '''

            docker.withRegistry(harborURL, harborCredentials){
              sh """
                echo "$mDockerfile" > Dockerfile
                mv ${buildResult} .
                docker build -t ${appImageURL} .
                docker push ${appImageURL}
                docker rmi ${appImageURL}
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
        wrap([$class: 'BuildUser']) {
          currentBuild.displayName = "#${BUILD_NUMBER} ${BUILD_USER}"
        }
      }
    }
  }

}
