#!/bin/sh
set -eu
mkdir -p target/ci-reports
case "${1:-}" in
    source)
        .ci-tools/trivy fs --no-progress --scanners secret --exit-code 1 \
            --skip-dirs .git --skip-dirs .ci-tools --skip-dirs target \
            --format json --output target/ci-reports/secrets.json .
        .ci-tools/trivy sbom --no-progress --scanners vuln --severity HIGH,CRITICAL --exit-code 1 \
            --format json --output target/ci-reports/dependencies.json target/bom.json
        ;;
    image)
        : "${IMAGE_REF:?Set IMAGE_REF}"
        trap 'rm -f target/ci-image.tar' EXIT HUP INT TERM
        docker save --output target/ci-image.tar "$IMAGE_REF"
        .ci-tools/trivy image --no-progress --input target/ci-image.tar --scanners vuln \
            --severity HIGH,CRITICAL --exit-code 1 \
            --format json --output target/ci-reports/image.json
        ;;
    *) echo 'Usage: ci/security.sh source|image' >&2; exit 2 ;;
esac
