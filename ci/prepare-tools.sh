#!/bin/sh
set -eu
mkdir -p .ci-tools
extract() {
    image=$1 source=$2 destination=$3
    container=$(docker create "$image")
    trap 'docker rm -f "$container" >/dev/null 2>&1 || true' EXIT HUP INT TERM
    docker cp "$container:$source" "$destination"
    docker rm "$container" >/dev/null
    trap - EXIT HUP INT TERM
}
if [ ! -x .ci-tools/jdk/bin/java ]; then
    extract eclipse-temurin:25-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e /opt/java/openjdk .ci-tools/jdk
fi
if [ ! -x .ci-tools/trivy ]; then
    extract aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e /usr/local/bin/trivy .ci-tools/trivy
fi
.ci-tools/jdk/bin/java -version
.ci-tools/trivy --version
