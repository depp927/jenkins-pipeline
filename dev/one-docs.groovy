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
    buildEnv = ""
    jarName = "ewallet-notify.jar"
    buildResult = "../notify-bootstrap/target/${jarName}"
    mavenSet = "/data/m2root/prod"
    baseImage = "harbor.onelabs.cc/base-images/onechain-java-baseimage:v1"
    appServerPort = "8080"
    pullImageCredentials = "harbor-img"
    appTag =  createTag()
    gitCredentials = "jenkins_user_wallet_gitlab"
    harborHost = "harbor.onelabs.cc"
    harborProtocol = "https"
    harborURL = "${harborProtocol}://${harborHost}"
    harborNS = "prod"
    harborCredentials = "jenkins_user"
    k8sURL = "https://B2BF5B36DAD868ADD2B306AF59FF3A08.gr7.ap-southeast-1.eks.amazonaws.com"
    k8sNamespace = "prod"
    k8sCredentials = "jenkins_to_wallet_eks"
    appImageURL = "${harborHost}/${harborNS}/${appName}:${appTag}"
    javaOpts="-XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m -XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError -XX:+CrashOnOutOfMemoryError -XX:+UseContainerSupport -XX:ErrorFile=/hostdir/\${HOSTNAME}_\$(date +%Y%m%d%H%M%S)_hs_err.log"
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
          docker run --rm -v $mavenSet:/root/.m2 \\
          -v $realWK:/build \\
          $buildEnv bash -c "cd /build && mvn -Dmaven.test.skip=true clean package deploy -DaltSnapshotDeploymentRepository=nexus-snapshots-mainnet::default::http://10.81.79.48:8081/repository/maven-snapshots/ -Pmainnet"
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
ENV TZ=UTC LANG=C.UTF-8 LC_ALL=C.UTF-8
ENV GIT_HASH=$gitHash
COPY $jarName /
ENTRYPOINT exec java ${javaOpts} \
  -jar -Dspring.profiles.active=prod \
  -Dreactor.netty.pool.leasingStrategy=lifo \
  -Dserver.port=${appServerPort}  \
  /$jarName

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

    stage("k8s Deploy") {
      steps {
        script {
          
          def mDeployment = '''
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: $appName
  namespace: $k8sNamespace
spec:
  minReplicas: 1
  maxReplicas: 1
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: $appName
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 80
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
---
apiVersion: v1
kind: Service
metadata:
  name: ${appName}-svc
  namespace: $k8sNamespace
spec:
  type: ClusterIP
  selector:
    app: $appName
  ports:
  - name: http
    targetPort: $appServerPort
    port: $appServerPort
---
apiVersion: v1
kind: Service
metadata:
  name: ${appName}-bolt-svc
  namespace: $k8sNamespace
spec:
  type: ClusterIP
  selector:
    app: $appName
  ports:
  - name: http
    targetPort: 8081
    port: 8081
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $appName
  namespace: $k8sNamespace
  labels:
    app: $appName
  annotations:
    kubernetes.io/change-cause: $appImageURL
spec:
  selector:
    matchLabels:
      app: $appName
  strategy:
    rollingUpdate:
      maxUnavailable: 0
    type: RollingUpdate
  template:
    metadata:
      labels:
        app: $appName
    spec:
      topologySpreadConstraints:
      - maxSkew: 1
        topologyKey: topology.kubernetes.io/zone
        whenUnsatisfiable: ScheduleAnyway
        labelSelector:
          matchLabels:
            app: $appName
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              topologyKey: kubernetes.io/hostname
              labelSelector:
                matchExpressions:
                 - key: app
                   operator: In
                   values:
                   - $appName
          - weight: 100
            podAffinityTerm:
              topologyKey: topology.kubernetes.io/zone
              labelSelector:
                matchExpressions:
                 - key: app
                   operator: In
                   values:
                   - $appName
      containers:
        - name: $appName
          image: $appImageURL
          ports:
            - containerPort: $appServerPort
          envFrom:
          - configMapRef:
              name: kmsenv
          resources:
              requests:
                  cpu: 1
                  memory: 4Gi
              limits:
                  cpu: 1
                  memory: 4Gi
          lifecycle:
            preStop:
              exec:
                command: [\\"/bin/sh\\", \\"-c\\", \\"curl 127.0.0.1:${appServerPort}/offline ; sleep 90\\"]
                command: [\\"/bin/sh\\", \\"-c\\", \\"sleep 90\\"]
          startupProbe:
            httpGet:
              path: /api/health
              port: $appServerPort
            failureThreshold: 30
            periodSeconds: 10
          livenessProbe:
            tcpSocket:
              port: $appServerPort
            periodSeconds: 10
            failureThreshold: 180
          readinessProbe:
            httpGet:
              path: /api/health
              port: $appServerPort
            periodSeconds: 10
            failureThreshold: 3
          volumeMounts:
          - name: hostdir
            mountPath: /hostdir
      volumes:
      - name: hostdir
        hostPath:
          path: /hostdir
          type: DirectoryOrCreate
      terminationGracePeriodSeconds: 120
      imagePullSecrets:
        - name: $pullImageCredentials
        '''

          withKubeConfig([serverUrl: k8sURL, credentialsId: k8sCredentials]) {
            sh """
              echo "$mDeployment" > deploy.yaml
              kubectl apply -f deploy.yaml
            """
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
        message "Rollback"
        parameters {
          booleanParam(name: 'ROLLBACK', defaultValue: false, description: "If you want rollback, please select.")
        }
      }
      steps {
        script {
          if (ROLLBACK == "true") {
            withKubeConfig([serverUrl: k8sURL, credentialsId: k8sCredentials]) {
              sh """
                kubectl rollout undo deployment $appName -n $k8sNamespace
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
