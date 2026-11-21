#!/usr/bin/env bash
set -euo pipefail

json=(jq)
case "$(uname -s)" in
  MINGW*|MSYS*) export MSYS_NO_PATHCONV=1; json+=(-b) ;;
esac

namespace="sddp-release-${RANDOM}-$$"
app_image="${SDDP_TEST_APP_IMAGE:-sddp-kubernetes-test:local}"
context=$(kubectl config current-context)
kubectl_for_test=(kubectl --context "$context")
kube=("${kubectl_for_test[@]}" --namespace "$namespace")
tmp=$(mktemp -d)
forward_pid=
namespace_created=0
cleanup() {
  if [[ -n "$forward_pid" ]]; then kill "$forward_pid" 2>/dev/null || true; fi
  if [[ "$namespace_created" == 1 ]]; then "${kubectl_for_test[@]}" delete namespace "$namespace" --wait=false >/dev/null || true; fi
  rm -f "$tmp/forward.log"
  rmdir "$tmp"
}
trap cleanup EXIT

"${kubectl_for_test[@]}" create namespace "$namespace"
namespace_created=1
"${kube[@]}" create secret generic sddp-db --from-literal=password=sddp-test
"${kube[@]}" run postgres --image=postgres:16-alpine --port=5432 \
  --env=POSTGRES_DB=sddp --env=POSTGRES_USER=sddp --env=POSTGRES_PASSWORD=sddp-test
"${kube[@]}" expose pod postgres --port=5432
"${kube[@]}" wait --for=condition=Ready pod/postgres --timeout=120s

# Keep the shipped Deployment, ServiceAccount and RBAC intact.
sed -e "s|docker.io/CHANGE_ME/sddp:0.1.0|$app_image|" \
  -e 's|postgres.example.internal|postgres|' deploy/kubernetes.yaml | "${kube[@]}" apply -f -
if ! "${kube[@]}" rollout status deployment/sddp --timeout=240s; then
  "${kube[@]}" describe pods -l app.kubernetes.io/name=sddp
  "${kube[@]}" logs deployment/sddp --tail=100
  exit 1
fi

# Plugin descriptors are configured by the operator in PostgreSQL.
"${kube[@]}" exec postgres -- psql -U sddp -d sddp -v ON_ERROR_STOP=1 -c \
  "INSERT INTO codegen_plugins (language, delivery_type, executable_target) VALUES ('TEST', 'DOCKER', 'sddp-plugin-fixture:local');"

"${kube[@]}" port-forward --address 127.0.0.1 service/sddp :80 >"$tmp/forward.log" 2>&1 &
forward_pid=$!
port=
for _ in {1..30}; do
  port=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) -> .*/\1/p' "$tmp/forward.log")
  [[ -n "$port" ]] && break
  sleep 1
done
if [[ -z "$port" ]]; then
  cat "$tmp/forward.log"
  exit 1
fi
base="http://127.0.0.1:$port"
curl -fsS "$base/actuator/health" | "${json[@]}" -e '.status == "UP"'
project_id=$(curl -fsS -H 'Content-Type: application/json' -d '{"name":"kubernetes-release"}' \
  "$base/api/v1/projects" | "${json[@]}" -er '.id')
body=$("${json[@]}" -nc --arg id "$project_id" '{projectId:$id, environment:"DEV", packageName:"test",
  clientName:"Fixture", plugin:{language:"TEST", deliveryType:"DOCKER", executableTarget:"sddp-plugin-fixture:local"}}')
if ! response=$(curl --fail-with-body -sS -H 'Content-Type: application/json' -d "$body" "$base/api/v1/codegen/generate"); then
  printf '%s\n' "$response" >&2
  "${kube[@]}" logs deployment/sddp --tail=60 >&2
  exit 1
fi
"${json[@]}" -e '.files["result.json"] | contains("kubernetes-release")' <<< "$response"
echo 'Kubernetes Deployment and plugin Job passed using the shipped ServiceAccount'
