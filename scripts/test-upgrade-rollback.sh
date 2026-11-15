#!/usr/bin/env bash
set -euo pipefail
case "$(uname -s)" in
  MINGW*|MSYS*) export MSYS_NO_PATHCONV=1; null_output=NUL; windows=1 ;;
  *) null_output=/dev/null; windows=0 ;;
esac

suffix="$$${RANDOM}"
project="sddupgrade${suffix}"
old_image="sddp-upgrade-${suffix}-old"
new_image="sddp-upgrade-${suffix}-new"
export POSTGRES_PASSWORD="sddp-upgrade-${suffix}"
export IMAGE_NAME="$old_image"
compose=(docker compose -p "$project" -f docker-compose.prod.yml -f scripts/compose-upgrade-test.yml)
tmp=$(mktemp -d -t sddp-upgrade.XXXXXXXX)
case "$tmp" in /tmp/sddp-upgrade.*) ;; *) echo "Unexpected temporary directory" >&2; exit 1 ;; esac
cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  docker image rm "${old_image}:latest" "${new_image}:latest" >/dev/null 2>&1 || true
  rm -rf -- "$tmp"
}
trap cleanup EXIT

base=${SDDP_UPGRADE_BASE_REF:-HEAD^}
if [[ "$base" =~ ^0+$ ]]; then base='HEAD^'; fi
if ! git rev-parse --verify --quiet "${base}^{commit}" >/dev/null; then
  echo "Upgrade baseline is not a commit: $base" >&2
  exit 1
fi
mkdir -p "$tmp/baseline/.mvn"
git archive "$base" | tar -x -C "$tmp/baseline"
# Use the current build configuration and mirror while compiling the baseline's unchanged source.
cp Dockerfile "$tmp/baseline/Dockerfile"
cp .dockerignore "$tmp/baseline/.dockerignore"
mkdir -p "$tmp/baseline/.mvn"
cp .mvn/maven.config .mvn/settings.xml "$tmp/baseline/.mvn/"
context="$tmp/baseline"
if [[ "$windows" == 1 ]]; then context=$(cygpath -m "$context"); fi
docker build -t "${old_image}:latest" "$context"
docker build -t "${new_image}:latest" .

start_app() {
  "${compose[@]}" up -d --no-build --no-deps --force-recreate app
  port=$("${compose[@]}" port app 8080); port=${port##*:}
  ready=000
  for _ in {1..120}; do
    ready=$(curl -qs -o "$null_output" -w '%{http_code}' "http://127.0.0.1:${port}/api/v1/projects" || true)
    [[ "$ready" == 200 ]] && return
    sleep 1
  done
  "${compose[@]}" logs --tail=80 app
  return 1
}
"${compose[@]}" up -d --no-build postgres
for _ in {1..60}; do
  if "${compose[@]}" exec -T postgres pg_isready -U sddp -d sddp >/dev/null 2>&1; then break; fi
  sleep 1
done
start_app
create_project() {
  curl -qfsS -H 'Content-Type: application/json' -d "{\"name\":\"$1\"}" "http://127.0.0.1:${port}/api/v1/projects" |
    node -p 'JSON.parse(require("fs").readFileSync(0,"utf8")).id'
}
check_project() {
  [[ $(curl -qs -o "$null_output" -w '%{http_code}' "http://127.0.0.1:${port}/api/v1/projects/$1") == 200 ]]
}
old_id=$(create_project before-upgrade)
[[ "$old_id" =~ ^[0-9a-f-]{36}$ ]]
export IMAGE_NAME="$new_image"
start_app
check_project "$old_id"
new_id=$(create_project after-upgrade)
[[ "$new_id" =~ ^[0-9a-f-]{36}$ ]]
export IMAGE_NAME="$old_image"
start_app
check_project "$old_id"
check_project "$new_id"
echo "Upgrade and rollback preserved projects (baseline: $base)"
