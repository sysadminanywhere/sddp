#!/usr/bin/env bash
set -euo pipefail

suffix="$$${RANDOM}"
project="sddp-offline-${suffix}"
image="sddp-airgap-test:${suffix}"
builder="sddp-airgap-builder:${suffix}"
postgres=postgres:16-alpine
kafka=apache/kafka:4.1.2
kafka_plugin="sddp-airgap-kafka-plugin:${suffix}"
test_runner=node:22-alpine
export POSTGRES_PASSWORD="sddp-test-db-secret-${suffix}"
# CI also defines IMAGE_NAME, which otherwise takes precedence over the bundle environment file.
export IMAGE_NAME="${image%:*}" IMAGE_TAG="${image##*:}" SDDP_POSTGRES_IMAGE="$postgres"
work=$(mktemp -d -t sddp-airgap.XXXXXXXX)
[[ "$work" == /tmp/sddp-airgap.* ]]
kit="$work/kit"
source_compose=(docker compose -p "${project}-source" --env-file "$kit/images.env" -f "$kit/compose.yml")
compose=(docker compose -p "$project" --env-file "$kit/images.env" -f "$kit/compose.yml" -f "$kit/compose.airgap.yml")
orders_fixture="${project}-orders"
kafka_fixture="${project}-kafka"
test_user="$(id -u):$(id -g)"
cleanup() {
  if [[ -f "$kit/images.env" ]]; then "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true; fi
  if [[ -f "$kit/images.env" ]]; then "${source_compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true; fi
  docker rm -fv "$orders_fixture" "$kafka_fixture" >/dev/null 2>&1 || true
  docker image rm "$image" "$builder" "$kafka_plugin" >/dev/null 2>&1 || true
  rm -rf -- "$work"
}
trap cleanup EXIT

# Stage the transferable kit on the connected side.
docker build -t "$image" .
docker build -f deploy/Dockerfile.artifact-builder -t "$builder" .
docker build -t "$kafka_plugin" plugins/kafka
docker pull "$postgres"
docker pull "$kafka"
docker pull "$test_runner"
bash scripts/package-airgap.sh "$kit" sddp-airgap-test "$suffix" "$builder" "$postgres" "$kafka" "$kafka_plugin"
(cd -- "$kit" && sha256sum -c SHA256SUMS)

mkdir -- "$work/qa" "$work/artifact" "$work/kafka"
cp -- docs/examples/orders.openapi.json "$work/qa/orders.json"
cp -- docs/examples/orders-events.asyncapi.json "$work/qa/orders-events.json"
cp -- scripts/seed-airgap-orders.mjs "$work/qa/seed.mjs"
"${source_compose[@]}" up -d --no-build --pull never
seed() {
  docker run --rm --pull never --network "$1" --user "$test_user" -e SDDP_TEST_ORIGIN=http://app:8080 \
    -v "$work/qa:/qa" "$test_runner" node /qa/seed.mjs "$2"
}
seed "${project}-source_sddp-net" export
"${source_compose[@]}" down --volumes --remove-orphans
docker image rm "$image" "$builder" "$kafka" "$kafka_plugin"

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

# Generate both clients from the restored IR and compile them with no network.
docker run --rm --pull never --network none --user "$test_user" \
  -v "$work/qa:/input:ro" -v "$work/kafka:/build" \
  "$kafka_plugin" plugin /input/kafka-ir.json /build
mkdir -- "$work/kafka/classes"
docker run --rm --pull never --network none --user "$test_user" --entrypoint javac \
  -v "$work/kafka:/build" "$builder" -cp '/opt/sddp/runtime/*' -d /build/classes \
  /build/Kafka_publishOrderCreated.java /build/Kafka_receiveOrderCreated.java

docker run -d --pull never --name "$kafka_fixture" --network "$network" --network-alias kafka \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 "$kafka" >/dev/null
cat > "$work/kafka/KafkaRoundTrip.java" <<'JAVA'
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import sddp.kafka.Kafka_publishOrderCreated;
import sddp.kafka.Kafka_receiveOrderCreated;

class KafkaRoundTrip {
    public static void main(String[] args) throws Exception {
        try (var admin = Admin.create(Map.of(
                "bootstrap.servers", "kafka:9092", "default.api.timeout.ms", 60_000))) {
            admin.createTopics(List.of(new NewTopic("orders.created", 1, (short) 1)))
                .all().get(65, TimeUnit.SECONDS);
        }
        var sender = new Kafka_publishOrderCreated();
        sender.publishOrderCreated(new Kafka_publishOrderCreated.Payload("order-42", "SKU-1"));
        var receiver = new Kafka_receiveOrderCreated();
        var received = receiver.receiveOrderCreated("sddp-offline-orders", Duration.ofSeconds(25)).orElseThrow();
        if (!"order-42".equals(received.orderId()) || !"SKU-1".equals(received.productCode())) {
            throw new AssertionError("The isolated Kafka client returned unexpected data");
        }
    }
}
JAVA
if ! docker run --rm --pull never --network "$network" --user "$test_user" --entrypoint java \
  -v "$work/kafka:/build" "$builder" -cp '/build/classes:/opt/sddp/runtime/*' /build/KafkaRoundTrip.java; then
  docker logs "$kafka_fixture" >&2
  exit 1
fi
docker rm -fv "$kafka_fixture" >/dev/null

restored=$(<"$work/qa/project-id")
restored_events=$(<"$work/qa/events-project-id")
[[ "$restored" =~ ^[0-9a-f-]{36}$ ]]
[[ "$restored_events" =~ ^[0-9a-f-]{36}$ ]]
"${compose[@]}" exec -T postgres pg_dump -U sddp -Fc sddp > "$work/backup.dump"
"${compose[@]}" exec -T postgres createdb -U sddp sddp_restore
"${compose[@]}" exec -T postgres pg_restore -U sddp --exit-on-error -d sddp_restore < "$work/backup.dump"
count=$("${compose[@]}" exec -T postgres psql -U sddp -d sddp_restore -Atc \
  "SELECT count(*) FROM topology_connections WHERE project_id = '$restored'")
[[ "$count" == 2 ]]
directions=$("${compose[@]}" exec -T postgres psql -U sddp -d sddp_restore -Atc \
  "SELECT string_agg(a.event_operation->>'direction', ',' ORDER BY a.event_operation->>'direction')
     FROM topology_connections c JOIN service_apis a ON a.id = c.target_api_id
    WHERE c.project_id = '$restored_events' AND a.event_operation->>'topic' = 'orders.created'")
[[ "$directions" == "RECEIVE,SEND" ]]
if grep -F -r -q -- "$POSTGRES_PASSWORD" "$work/qa" "$work/artifact" "$work/kafka" ||
   "${compose[@]}" logs app | grep -F -- "$POSTGRES_PASSWORD" >/dev/null; then
  echo 'Database credential was present in a generated file or build log' >&2
  exit 1
fi
echo 'Isolated installation, REST and Kafka snapshot transfer, offline builds, exchanges and PostgreSQL restore passed'
