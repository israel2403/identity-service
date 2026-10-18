pipeline {
  agent { label 'nahuatl-maven' }
  options {
    skipDefaultCheckout(true)
    timestamps()
    disableConcurrentBuilds()
    timeout(time: 30, unit: 'MINUTES')
  }
  stages {
    stage('Checkout') {
      steps {
        script {
          if (params.GIT_COMMIT) {
            if (!(params.GIT_COMMIT ==~ /[0-9a-f]{40}/)) {
              error('GIT_COMMIT must be a full Git commit SHA')
            }
            checkout scmGit(
              branches: [[name: params.GIT_COMMIT]],
              userRemoteConfigs: scm.userRemoteConfigs.collect {
                [url: it.url, credentialsId: it.credentialsId]
              }
            )
            def actual = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            if (actual != params.GIT_COMMIT) {
              error('Checked-out commit differs from the GitHub workflow commit')
            }
            echo "Verified GitHub commit: ${actual}"
          } else {
            checkout scm
          }
        }
      }
    }
    stage('Environment') {
      steps { sh 'java -version && mvn -version && docker info' }
    }
    stage('Verify') {
      steps { sh 'mvn -B -ntp clean verify' }
    }
  }
  post {
    always {
      junit allowEmptyResults: false,
        testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
    }
  }
}
