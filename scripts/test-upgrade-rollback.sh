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
post_json() {
  curl -qfsS -H 'Content-Type: application/json' --data-binary @- "http://127.0.0.1:${port}/api/v1$1"
}
json_id() {
  node -p 'JSON.parse(require("fs").readFileSync(0, "utf8")).id'
}
create_project() {
  printf '{"name":"%s"}' "$1" | post_json /projects | json_id
}
check_project() {
  [[ $(curl -qs -o "$null_output" -w '%{http_code}' "http://127.0.0.1:${port}/api/v1/projects/$1") == 200 ]]
}
seed_openapi_connection() {
  local source_id target_id
  source_id=$(printf '{"name":"store","serviceType":"CORE"}' | post_json "$project_api/services" | json_id)
  target_id=$(printf '{"name":"orders","serviceType":"EXTERNAL"}' | post_json "$project_api/services" | json_id)
  import_path="$project_api/services/$target_id/apis/openapi/import"
  import_payload=$(node -e '
    const document = JSON.parse(require("fs").readFileSync("docs/examples/orders.openapi.json", "utf8"));
    process.stdout.write(JSON.stringify({ document, operations: ["GET /orders/{id}"] }));
  ')
  operation_id=$(printf '%s' "$import_payload" | post_json "$import_path" | node -p '
    const { created, existing } = JSON.parse(require("fs").readFileSync(0, "utf8"));
    if (created.length !== 1 || existing.length) throw new Error("Expected one new OpenAPI operation");
    created[0];
  ')
  [[ "$operation_id" =~ ^[0-9a-f-]{36}$ ]]
  connection_id=$(printf '{"environment":"DEV","sourceServiceId":"%s","targetServiceId":"%s","targetApiId":"%s","transportType":"REST","connectionSettings":{"host":"orders","port":8080,"target_endpoint":"/"}}' \
    "$source_id" "$target_id" "$operation_id" | post_json "$project_api/connections" | json_id)
  [[ "$connection_id" =~ ^[0-9a-f-]{36}$ ]]
}
check_openapi_connection() {
  curl -qfsS "http://127.0.0.1:${port}/api/v1$project_api/connections/$connection_id" | node -e '
    const { environment, connection } = JSON.parse(require("fs").readFileSync(0, "utf8"));
    if (environment !== "DEV" || connection.id !== process.argv[1] || connection.targetApiId !== process.argv[2]) {
      throw new Error("The original OpenAPI connection or target operation did not survive");
    }
  ' "$connection_id" "$operation_id"
  printf '{"projectId":"%s","environment":"DEV","packageName":"upgrade","clientName":"OrdersClient"}' "$old_id" |
    post_json /codegen/generate | node -e '
      const java = JSON.parse(require("fs").readFileSync(0, "utf8")).files["OrdersClient.java"];
      if (!java?.includes("public Object getOrder(") || !java.includes("HttpMethod.GET") || !java.includes("/orders/{id}")) {
        throw new Error("Generated client did not retain the original OpenAPI method and path");
      }
    '
  printf '%s' "$import_payload" | post_json "$import_path" | node -e '
    const { created, existing } = JSON.parse(require("fs").readFileSync(0, "utf8"));
    if (created.length || existing.length !== 1 || existing[0] !== process.argv[1]) {
      throw new Error("Re-import did not preserve the original OpenAPI operation ID");
    }
  ' "$operation_id"
}
old_id=$(create_project before-upgrade)
[[ "$old_id" =~ ^[0-9a-f-]{36}$ ]]
project_api="/projects/$old_id"
seed_openapi_connection
export IMAGE_NAME="$new_image"
start_app
check_project "$old_id"
check_openapi_connection
new_id=$(create_project after-upgrade)
[[ "$new_id" =~ ^[0-9a-f-]{36}$ ]]
export IMAGE_NAME="$old_image"
start_app
check_project "$old_id"
check_project "$new_id"
check_openapi_connection
echo "Upgrade and rollback preserved projects, the OpenAPI connection, and generated client (baseline: $base)"
