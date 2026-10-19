#!/bin/sh
set -eu
: "${IMAGE_REF:?Set IMAGE_REF}"
run_id="identity-smoke-${BUILD_NUMBER:-local}-$$"
db="$run_id-db"
app="$run_id-app"
password=$(cat /proc/sys/kernel/random/uuid)
mkdir -p target/ci-reports
cleanup() {
    docker logs "$app" >target/ci-reports/smoke-application.log 2>&1 || true
    docker rm -fv "$app" "$db" >/dev/null 2>&1 || true
    docker network rm "$run_id" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM
docker network create "$run_id" >/dev/null
docker run -d --name "$db" --network "$run_id" --network-alias postgres \
    -e POSTGRES_DB=identity -e POSTGRES_USER=identity -e POSTGRES_PASSWORD="$password" \
    postgres:17-alpine >/dev/null
attempt=0
until docker exec "$db" pg_isready -U identity -d identity >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 60 ] || { echo 'PostgreSQL did not become ready' >&2; exit 1; }
    sleep 2
done
docker run -d --name "$app" --network "$run_id" --network-alias app \
    --read-only --tmpfs /tmp:rw,nosuid,size=128m --cap-drop ALL \
    --security-opt no-new-privileges:true \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/identity \
    -e SPRING_DATASOURCE_USERNAME=identity -e SPRING_DATASOURCE_PASSWORD="$password" \
    -e SPRING_SECURITY_USER_NAME=smoke -e SPRING_SECURITY_USER_PASSWORD="$password" \
    "$IMAGE_REF" >/dev/null
docker run --rm --network "$run_id" curlimages/curl:8.17.0 \
    --fail --silent --show-error --retry 60 --retry-all-errors --retry-delay 2 \
    --retry-max-time 180 http://app:8080/actuator/health \
    >target/ci-reports/smoke-health.json
grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' target/ci-reports/smoke-health.json
# Confirm the packaged service denies unauthenticated requests as well.
status=$(docker run --rm --network "$run_id" curlimages/curl:8.17.0 \
    --silent --output /dev/null --write-out '%{http_code}' \
    -H 'Accept: application/json' http://app:8080/private)
[ "$status" = 401 ] || { echo "Expected HTTP 401, received $status" >&2; exit 1; }
