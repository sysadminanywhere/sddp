#!/usr/bin/env bash
set -euo pipefail

suffix="$$${RANDOM}"
project="sddp-offline-${suffix}"
image="sddp-airgap-test:${suffix}"
builder="sddp-airgap-builder:${suffix}"
postgres=postgres:16-alpine
test_runner=node:22-alpine
export POSTGRES_PASSWORD="sddp-test-db-secret-${suffix}"
work=$(mktemp -d -t sddp-airgap.XXXXXXXX)
[[ "$work" == /tmp/sddp-airgap.* ]]
kit="$work/kit"
source_compose=(docker compose -p "${project}-source" --env-file "$kit/images.env" -f "$kit/compose.yml")
compose=(docker compose -p "$project" --env-file "$kit/images.env" -f "$kit/compose.yml" -f "$kit/compose.airgap.yml")
orders_fixture="${project}-orders"
test_user="$(id -u):$(id -g)"
cleanup() {
  if [[ -f "$kit/images.env" ]]; then "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true; fi
  if [[ -f "$kit/images.env" ]]; then "${source_compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true; fi
  docker rm -f "$orders_fixture" >/dev/null 2>&1 || true
  docker image rm "$image" "$builder" >/dev/null 2>&1 || true
  rm -rf -- "$work"
}
trap cleanup EXIT

# Stage the transferable kit on the connected side.
docker build -t "$image" .
docker build -f deploy/Dockerfile.artifact-builder -t "$builder" .
docker pull "$postgres"
docker pull "$test_runner"
bash scripts/package-airgap.sh "$kit" sddp-airgap-test "$suffix" "$builder" "$postgres"
(cd -- "$kit" && sha256sum -c SHA256SUMS)

mkdir -- "$work/qa" "$work/artifact"
cp -- docs/examples/orders.openapi.json "$work/qa/orders.json"
cp -- scripts/seed-airgap-orders.mjs "$work/qa/seed.mjs"
"${source_compose[@]}" up -d --no-build --pull never
seed() {
  docker run --rm --pull never --network "$1" --user "$test_user" -e SDDP_TEST_ORIGIN=http://app:8080 \
    -v "$work/qa:/qa" "$test_runner" node /qa/seed.mjs "$2"
}
seed "${project}-source_sddp-net" export
"${source_compose[@]}" down --volumes --remove-orphans
docker image rm "$image" "$builder"

# Load the local archive and prohibit image pulls and outbound access.
docker image load --input "$kit/images.tar"
"${compose[@]}" up -d --no-build --pull never
network="${project}_sddp-net"
[[ "$(docker network inspect -f '{{.Internal}}' "$network")" == true ]]
seed "$network" import
unzip -q "$work/qa/orders.zip" -d "$work/artifact"
(cd -- "$work/artifact" && sha256sum -c SHA256SUMS)
docker run --rm --pull never --network none --user "$test_user" -e HOME=/tmp \
  -v "$work/artifact:/build" "$builder"
[[ -s "$work/artifact/target/orders-1.0.0.jar" ]]
unzip -p "$work/artifact/target/orders-1.0.0.jar" META-INF/sddp/sddp-artifact.json |
  node -e 'const m=JSON.parse(require("fs").readFileSync(0,"utf8"));
    if (m.contracts.length !== 2 || m.environment !== "DEV") throw new Error("Invalid artifact provenance")'

# Exercise the transferred library against an Orders service on the isolated network.
docker run -d --pull never --name "$orders_fixture" --network "$network" --network-alias orders "$test_runner" node -e '
  require("node:http").createServer(async (req,res) => {
    const data=[]; for await (const chunk of req) data.push(chunk);
    const valid=(req.method==="GET" && req.url==="/orders/A%2F42") ||
                (req.method==="POST" && req.url==="/orders" && JSON.parse(Buffer.concat(data)).productCode==="SKU-1");
    res.writeHead(valid ? 200 : 400, {"Content-Type":"application/json"});
    res.end(JSON.stringify({id:"order-42",productCode:"SKU-1"}));
  }).listen(8080);' >/dev/null
cat > "$work/artifact/Consumer.java" <<'JAVA'
import java.util.Map;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
class Consumer {
    public static void main(String[] args) {
        var reader = new pilot.Orders2(RestClient.builder());
        var writer = new pilot.Orders1(RestClient.builder(), new ObjectMapper());
        if (!"order-42".equals(reader.getOrder(Map.of("id", "A/42"), Map.of()).id())
                || !"SKU-1".equals(writer.createOrder(Map.of("sku", "SKU-1")).productCode())) {
            throw new AssertionError("The isolated Orders client returned unexpected data");
        }
    }
}
JAVA
docker run --rm --pull never --network "$network" --user "$test_user" --entrypoint java \
  -v "$work/artifact:/build" "$builder" \
  -cp '/build/target/orders-1.0.0.jar:/opt/sddp/runtime/*' /build/Consumer.java
docker rm -f "$orders_fixture" >/dev/null

restored=$(<"$work/qa/project-id")
[[ "$restored" =~ ^[0-9a-f-]{36}$ ]]
"${compose[@]}" exec -T postgres pg_dump -U sddp -Fc sddp > "$work/backup.dump"
"${compose[@]}" exec -T postgres createdb -U sddp sddp_restore
"${compose[@]}" exec -T postgres pg_restore -U sddp --exit-on-error -d sddp_restore < "$work/backup.dump"
count=$("${compose[@]}" exec -T postgres psql -U sddp -d sddp_restore -Atc \
  "SELECT count(*) FROM topology_connections WHERE project_id = '$restored'")
[[ "$count" == 2 ]]
if grep -F -r -q -- "$POSTGRES_PASSWORD" "$work/qa" "$work/artifact" ||
   "${compose[@]}" logs app | grep -F -- "$POSTGRES_PASSWORD" >/dev/null; then
  echo 'Database credential was present in a generated file or build log' >&2
  exit 1
fi
echo 'Isolated load, snapshot import, offline Java build and PostgreSQL restore passed'
