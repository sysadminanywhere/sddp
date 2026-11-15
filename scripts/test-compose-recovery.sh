#!/usr/bin/env bash
set -euo pipefail
case "$(uname -s)" in
  MINGW*|MSYS*) export MSYS_NO_PATHCONV=1; null_output=NUL ;;
  *) null_output=/dev/null ;;
esac

suffix="$$${RANDOM}"
project="sddptest${suffix}"
export IMAGE_NAME="sddp-test-${suffix}"
export POSTGRES_PASSWORD="sddp-test-${suffix}"
export SDDP_DOMAIN=localhost:18443 SDDP_USER=Bob
export SDDP_PASSWORD_HASH='$2a$14$Zkx19XLiW6VYouLHR5NmfOFU0z2GTNmpkT/5qqR7hx4IjWJPDhjvG'
if [[ "${SDDP_TEST_BROWSER:-0}" == 1 ]]; then export SDDP_TEST_PORT=18443; fi
tmp=$(mktemp -d)
compose=(docker compose -p "$project" -f docker-compose.prod.yml -f docker-compose.gateway.yml -f scripts/compose-test.yml)
if [[ -n "${SDDP_PLAYWRIGHT_CLI_JS:-}" ]]; then
  browser=(node "$SDDP_PLAYWRIGHT_CLI_JS" --session "$project")
else
  browser=(npx --yes --package @playwright/cli@0.1.24 playwright-cli --session "$project")
fi
browser_started=0
cleanup() {
  if [[ "$browser_started" == 1 ]]; then "${browser[@]}" close >/dev/null 2>&1 || true; fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  docker image rm "${IMAGE_NAME}:latest" >/dev/null 2>&1 || true
  rm -f "$tmp/snapshot.json" "$tmp/backup.dump"
  rmdir "$tmp" 2>/dev/null || true
}
trap cleanup EXIT

"${compose[@]}" up -d --build
port=$("${compose[@]}" port gateway 18443)
port=${port##*:}
request() {
  curl -qsk --connect-to "localhost:18443:127.0.0.1:${port}" -u Bob:hiccup \
    -H 'Origin: https://localhost:18443' "$@"
}
ready=000
for _ in {1..120}; do
  ready=$(request -o "$null_output" -w '%{http_code}' https://localhost:18443/api/v1/projects || true)
  [[ "$ready" == 200 ]] && break
  sleep 1
done
if [[ "$ready" != 200 ]]; then
  "${compose[@]}" logs --tail=80 app gateway
  exit 1
fi
if [[ "${SDDP_TEST_BROWSER_PAUSE:-0}" == 1 ]]; then
  echo "Gateway is ready on port ${port}; press Enter to continue."
  read -r
fi
if [[ "${SDDP_TEST_BROWSER:-0}" == 1 ]]; then
  browser_started=1
  "${browser[@]}" --config scripts/playwright-gateway.json open --browser="${SDDP_TEST_BROWSER_CHANNEL:-chrome}" https://localhost:18443/
  "${browser[@]}" run-code 'async page => { await page.getByRole("textbox", {name:"System name"}).fill("browser-project"); await page.getByRole("button", {name:"Create project"}).click(); await page.getByRole("heading", {name:"browser-project"}).waitFor(); }'
  "${browser[@]}" close
  browser_started=0
fi

original=$(request -fsS -H 'Content-Type: application/json' -d '{"name":"recovery-original"}' https://localhost:18443/api/v1/projects)
original_id=$(printf '%s' "$original" | node -p 'JSON.parse(require("fs").readFileSync(0,"utf8")).id')
[[ -n "$original_id" && "$original_id" != undefined ]]
service=$(request -fsS -H 'Content-Type: application/json' -d '{"name":"orders","serviceType":"CORE"}' "https://localhost:18443/api/v1/projects/${original_id}/services")
service_id=$(printf '%s' "$service" | node -p 'JSON.parse(require("fs").readFileSync(0,"utf8")).id')
request -fsS -o "$null_output" -H 'Content-Type: application/json' -d '{"name":"create","version":"1","pattern":"REQUEST_RESPONSE","requestSchema":{"type":"object","properties":{}}}' "https://localhost:18443/api/v1/projects/${original_id}/services/${service_id}/apis"
manifest=$(request -fsS -X POST "https://localhost:18443/api/v1/projects/${original_id}/ir?environment=DEV")
printf '%s' "$manifest" | node -e 'const m=JSON.parse(require("fs").readFileSync(0,"utf8")); if (m.environment !== "DEV" || typeof m.generated_at !== "string" || !m.services.some(s => s.service_name === "orders")) process.exit(1)'
request -fsS -X POST "https://localhost:18443/api/v1/projects/${original_id}/snapshot" > "$tmp/snapshot.json"
snapshot_payload=$(<"$tmp/snapshot.json")
imported=$(request -fsS -H 'Content-Type: application/json' --data-binary "$snapshot_payload" https://localhost:18443/api/v1/projects/import)
imported_id=$(printf '%s' "$imported" | node -p 'JSON.parse(require("fs").readFileSync(0,"utf8")).id')
[[ -n "$imported_id" && "$imported_id" != "$original_id" ]]
[[ "$original_id" =~ ^[0-9a-f-]{36}$ && "$imported_id" =~ ^[0-9a-f-]{36}$ ]]
apis=$(request -fsS "https://localhost:18443/api/v1/projects/${imported_id}/apis")
printf '%s' "$apis" | node -e 'const a=JSON.parse(require("fs").readFileSync(0,"utf8")); if (!a.some(x => x.name === "create" && x.requestSchema.type === "object")) process.exit(1)'

"${compose[@]}" exec -T postgres pg_dump -U sddp -Fc sddp > "$tmp/backup.dump"
"${compose[@]}" exec -T postgres createdb -U sddp sddp_restore
"${compose[@]}" exec -T postgres pg_restore -U sddp --exit-on-error -d sddp_restore < "$tmp/backup.dump"
count=$("${compose[@]}" exec -T postgres psql -U sddp -d sddp_restore -Atc "SELECT count(*) FROM projects WHERE id IN ('$original_id', '$imported_id')")
[[ "$count" == 2 ]]
api_count=$("${compose[@]}" exec -T postgres psql -U sddp -d sddp_restore -Atc "SELECT count(*) FROM service_apis a JOIN services s ON s.id = a.service_id WHERE s.project_id IN ('$original_id', '$imported_id') AND a.name = 'create'")
[[ "$api_count" == 2 ]]

"${compose[@]}" restart app >/dev/null
ready=000
for _ in {1..120}; do
  ready=$(request -o "$null_output" -w '%{http_code}' "https://localhost:18443/api/v1/projects/${imported_id}" || true)
  [[ "$ready" == 200 ]] && break
  sleep 1
done
[[ "$ready" == 200 ]]
echo 'Compose snapshot import, database backup/restore and restart passed'
