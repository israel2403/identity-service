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
      steps { checkout scm }
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
