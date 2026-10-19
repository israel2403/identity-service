pipeline {
    agent { label 'nahuatl-maven' }
    options {
        skipDefaultCheckout(true)
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
    }
    parameters {
        string(name: 'GIT_COMMIT', defaultValue: '', description: 'Exact GitHub commit SHA; empty builds main')
    }
    environment {
        JAVA_HOME = "${WORKSPACE}/.ci-tools/jdk"
        PATH = "${WORKSPACE}/.ci-tools/jdk/bin:${env.PATH}"
        IMAGE_REPOSITORY = 'israelhf24/identity-service'
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                script {
                    def revision = params.GIT_COMMIT?.trim()
                    if (revision && !(revision ==~ /[0-9a-f]{40}/)) {
                        error('GIT_COMMIT must be a full lowercase Git SHA')
                    }
                    checkout scmGit(
                        branches: [[name: revision ?: '*/main']],
                        userRemoteConfigs: scm.userRemoteConfigs.collect {
                            [url: it.url, credentialsId: it.credentialsId, refspec: '+refs/heads/*:refs/remotes/origin/*']
                        }
                    )
                    def actual = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    if (revision && actual != revision) {
                        error('Checked-out commit differs from requested commit')
                    }
                    env.BUILD_SHA = actual
                    env.IMAGE_REF = "${env.IMAGE_REPOSITORY}:sha-${actual}-build-${env.BUILD_NUMBER}"
                }
                sh 'sh ci/prepare-tools.sh'
                sh 'java -version && mvn -version && docker info'
            }
        }
        stage('Code checks') {
            steps { sh 'mvn -B -ntp clean validate' }
        }
        stage('Unit tests') {
            steps { sh 'mvn -B -ntp test' }
        }
        stage('Integration tests') {
            steps {
                sh 'mvn -B -ntp jacoco:prepare-agent failsafe:integration-test failsafe:verify'
            }
        }
        stage('Coverage/security gates') {
            steps {
                sh 'mvn -B -ntp jacoco:report jacoco:check cyclonedx:makeAggregateBom'
                sh 'sh ci/security.sh source'
            }
        }
        stage('Package') {
            steps { sh 'mvn -B -ntp -DskipTests package' }
        }
        stage('Build image') {
            steps {
                sh '''docker build --pull --label "org.opencontainers.image.revision=$BUILD_SHA" \
                    --label "org.opencontainers.image.source=https://github.com/israel2403/identity-service" \
                    --tag "$IMAGE_REF" .'''
            }
        }
        stage('Image scan') {
            steps { sh 'sh ci/security.sh image' }
        }
        stage('Container smoke test') {
            steps { sh 'sh ci/smoke-test.sh' }
        }
        stage('Publish image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-identity-service',
                        usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_TOKEN')]) {
                    sh '''#!/bin/sh
set -eu
set +x
DOCKER_CONFIG=$(mktemp -d)
export DOCKER_CONFIG
trap 'rm -rf "$DOCKER_CONFIG"' EXIT HUP INT TERM
printf '%s' "$DOCKERHUB_TOKEN" | docker login --username "$DOCKERHUB_USER" --password-stdin
docker push "$IMAGE_REF"
docker inspect --format '{{json .RepoDigests}}' "$IMAGE_REF" >target/ci-reports/published-digests.json
printf '%s\\n' "$IMAGE_REF" >target/ci-reports/published-image.txt
'''
                }
            }
        }
    }
    post {
        always {
            junit allowEmptyResults: true,
                testResults: 'target/surefire-reports/TEST-*.xml,target/failsafe-reports/TEST-*.xml'
            archiveArtifacts allowEmptyArchive: true, fingerprint: true,
                artifacts: 'target/identity-service.jar,target/bom.json,target/site/jacoco/**,target/*-reports/**,target/ci-reports/**,target/checkstyle-result.xml'
            script {
                if (env.IMAGE_REF) {
                    sh 'docker image rm "$IMAGE_REF" >/dev/null 2>&1 || true'
                }
            }
        }
    }
}
