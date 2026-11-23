#!/usr/bin/env bash
set -euo pipefail

suffix="$$${RANDOM}"
project="sddp-offline-${suffix}"
image="sddp-airgap-test:${suffix}"
# Use the published main revision that first passed isolated REST and Kafka acceptance.
baseline_source=docker.io/sysadminanywhere/sddp:0.5.0-a9b1a0e@sha256:9a25ad47b271ad854a0264f9d629e46a8b51135c50b44c6e9423b751a1f097ae
baseline="sddp-airgap-baseline:${suffix}"
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
restore_compose=("${compose[@]}" -f "$work/compose.restore.yml")
orders_fixture="${project}-orders"
kafka_fixture="${project}-kafka"
test_user="$(id -u):$(id -g)"
cleanup() {
  if [[ -f "$kit/images.env" ]]; then "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true; fi
  if [[ -f "$kit/images.env" ]]; then "${source_compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true; fi
  docker rm -fv "$orders_fixture" "$kafka_fixture" >/dev/null 2>&1 || true
  docker image rm "$image" "$baseline" "$builder" "$kafka_plugin" >/dev/null 2>&1 || true
  rm -rf -- "$work"
}
trap cleanup EXIT

# Stage the transferable kit on the connected side.
docker pull "$baseline_source"
docker tag "$baseline_source" "$baseline"
docker build -t "$image" .
docker build -f deploy/Dockerfile.artifact-builder -t "$builder" .
docker build -t "$kafka_plugin" plugins/kafka
docker pull "$postgres"
docker pull "$kafka"
docker pull "$test_runner"
bash scripts/package-airgap.sh "$kit" sddp-airgap-test "$suffix" "$builder" "$postgres" "$kafka" "$kafka_plugin" "$baseline"
(cd -- "$kit" && sha256sum -c SHA256SUMS)

mkdir -- "$work/qa"
cp -- docs/examples/orders.openapi.json "$work/qa/orders.json"
cp -- docs/examples/orders-events.asyncapi.json "$work/qa/orders-events.json"
cp -- scripts/seed-airgap-orders.mjs "$work/qa/seed.mjs"
IMAGE_NAME="${baseline%:*}" "${source_compose[@]}" up -d --no-build --pull never
seed() {
  docker run --rm --pull never --network "$1" --user "$test_user" -e SDDP_TEST_ORIGIN=http://app:8080 \
    -v "$work/qa:/qa" "$test_runner" node /qa/seed.mjs "$2"
}
seed "${project}-source_sddp-net" export
"${source_compose[@]}" down --volumes --remove-orphans
docker image rm "$image" "$baseline" "$builder" "$kafka" "$kafka_plugin"

# Load the local archive and prohibit image pulls and outbound access.
docker image load --input "$kit/images.tar"
IMAGE_NAME="${baseline%:*}" "${compose[@]}" up -d --no-build --pull never
network="${project}_sddp-net"
[[ "$(docker network inspect -f '{{.Internal}}' "$network")" == true ]]
seed "$network" import
cat > "$work/Consumer.java" <<'JAVA'
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

cat > "$work/KafkaRoundTrip.java" <<'JAVA'
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

verify_clients() {
  local phase="$work/$1"
  echo "Build and exercise REST and Kafka clients: $1"
  mkdir -p -- "$phase/artifact" "$phase/kafka"
  cp -- "$work/Consumer.java" "$phase/artifact/"
  cp -- "$work/KafkaRoundTrip.java" "$phase/kafka/"
  seed "$network" generate
  unzip -q "$work/qa/orders.zip" -d "$phase/artifact"
  (cd -- "$phase/artifact" && sha256sum -c SHA256SUMS)
  docker run --rm --pull never --network none --user "$test_user" -e HOME=/tmp \
    -v "$phase/artifact:/build" "$builder"
  [[ -s "$phase/artifact/target/orders-1.0.0.jar" ]]
  unzip -p "$phase/artifact/target/orders-1.0.0.jar" META-INF/sddp/sddp-artifact.json |
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
  docker run --rm --pull never --network "$network" --user "$test_user" --entrypoint java \
    -v "$phase/artifact:/build" "$builder" \
    -cp '/build/target/orders-1.0.0.jar:/opt/sddp/runtime/*' /build/Consumer.java
  docker rm -f "$orders_fixture" >/dev/null

  # Generate both clients from the exported IR and compile them with no network.
  docker run --rm --pull never --network none --user "$test_user" \
    -v "$work/qa:/input:ro" -v "$phase/kafka:/build" \
    "$kafka_plugin" plugin /input/kafka-ir.json /build
  mkdir -- "$phase/kafka/classes"
  docker run --rm --pull never --network none --user "$test_user" --entrypoint javac \
    -v "$phase/kafka:/build" "$builder" -cp '/opt/sddp/runtime/*' -d /build/classes \
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
  if ! docker run --rm --pull never --network "$network" --user "$test_user" --entrypoint java \
    -v "$phase/kafka:/build" "$builder" -cp '/build/classes:/opt/sddp/runtime/*' /build/KafkaRoundTrip.java; then
    docker logs "$kafka_fixture" >&2
    return 1
  fi
  docker rm -fv "$kafka_fixture" >/dev/null
  if grep -F -r -q -- "$POSTGRES_PASSWORD" "$work/qa" "$phase/artifact" "$phase/kafka"; then
    echo 'Database credential was present in a generated file' >&2
    return 1
  fi
}

verify_app_logs() {
  if "${compose[@]}" logs app | grep -F -- "$POSTGRES_PASSWORD" >/dev/null; then
    echo 'Database credential was present in an application log' >&2
    return 1
  fi
}

verify_clients installed
verify_app_logs

# Update the isolated installation using the transferred image and the existing database.
"${compose[@]}" exec -T postgres pg_dump -U sddp -Fc sddp > "$work/backup.dump"
"${compose[@]}" up -d --no-build --pull never --no-deps app
verify_clients upgraded
verify_app_logs

# Recover the pre-upgrade backup into a separate database and start the updated app on it.
"${compose[@]}" exec -T postgres createdb -U sddp sddp_restore
"${compose[@]}" exec -T postgres pg_restore -U sddp --exit-on-error -d sddp_restore < "$work/backup.dump"
cat > "$work/compose.restore.yml" <<'YAML'
services:
  app:
    environment:
      SddP_DB_URL: jdbc:postgresql://postgres:5432/sddp_restore
YAML
"${restore_compose[@]}" up -d --no-build --pull never --no-deps app
verify_clients recovered
verify_app_logs
echo 'Isolated installation, REST and Kafka snapshot transfer, offline builds, exchanges, upgrade and recovery passed'
