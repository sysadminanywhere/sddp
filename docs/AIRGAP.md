# Isolated Docker installation

## Prepare the kit

On a host with access to the approved registries, build or pull the application image. Build the Maven builder from the same SDDP revision so its populated repository matches the versions used in generated artifact POMs.

```bash
docker build -t sddp:pilot .
docker build -f deploy/Dockerfile.artifact-builder -t sddp-maven-builder:pilot .
docker pull postgres:16-alpine
bash scripts/package-airgap.sh sddp-airgap sddp pilot sddp-maven-builder:pilot
```

Transfer the kit and the topology snapshot through the approved channel. Build the images for the operating system and architecture of the isolated Docker host.

For the Kafka pilot, include the generator and the broker image if the isolated environment needs a broker. The kit records all transferred image tags in `images.txt`.

```bash
docker build -t sddp-kafka-plugin:pilot plugins/kafka
docker pull apache/kafka:4.1.2
bash scripts/package-airgap.sh sddp-airgap-kafka sddp pilot sddp-maven-builder:pilot \
  postgres:16-alpine sddp-kafka-plugin:pilot apache/kafka:4.1.2
```

## Install in the isolated environment

```bash
cd sddp-airgap
sha256sum -c SHA256SUMS
docker image load --input images.tar
export POSTGRES_PASSWORD="$(openssl rand -base64 24)"
docker compose --env-file images.env -f compose.yml -f compose.airgap.yml up -d --no-build --pull never
```

The Compose overlay creates an internal network. Reach the application from the Docker host or an approved service attached to that network. For browser access outside the host, use an authenticated HTTPS gateway in the same isolated environment. Keep OIDC issuers and other service dependencies inside the allowed network when enabled.

## Build the Java library

Import the topology snapshot and download the Java project ZIP from `POST /api/v1/codegen/artifact`. Use the intended environment, then verify the extracted project and build the JAR.

```bash
cd /path/to/extracted-project
sha256sum -c SHA256SUMS
docker run --rm --pull never --network none --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -v "$PWD:/build" sddp-maven-builder:pilot
```

Use the Maven builder tag recorded in the kit's `images.env`. Deploy the JAR with the application's pinned Java dependencies. The generated client connects to the target configured for the selected environment. Supply runtime credentials to the consuming application. Topology fields and mapping constants belong to the project snapshot and may appear in generated source, so keep credentials and working payloads in the consuming system.

## Build and run the Kafka clients

Import the Orders events snapshot and export DEV IR with `POST /api/v1/projects/{projectId}/ir?environment=DEV`. Save the response as `ir.json` in the current directory. The Kafka connections in the snapshot must contain the address reachable from the consuming application and the topics from the AsyncAPI contract.

```bash
mkdir -p kafka-client/classes
docker run --rm --pull never --network none --user "$(id -u):$(id -g)" \
  -v "$PWD/ir.json:/input/ir.json:ro" -v "$PWD/kafka-client:/output" \
  sddp-kafka-plugin:pilot plugin /input/ir.json /output
docker run --rm --pull never --network none --user "$(id -u):$(id -g)" --entrypoint javac \
  -v "$PWD/kafka-client:/build" sddp-maven-builder:pilot -cp '/opt/sddp/runtime/*' \
  -d /build/classes /build/Kafka_publishOrderCreated.java /build/Kafka_receiveOrderCreated.java
```

Create the `orders.created` topic on the isolated broker. Add the compiled classes and pinned Kafka and Jackson runtime dependencies to the consumer application's classpath. Use `publishOrderCreated` and `receiveOrderCreated` to send and confirm the Orders event. The pilot uses plaintext on the test network. The isolated acceptance script includes the broker configuration and a runnable Java example in `scripts/test-airgap-install.sh`.

## Update and restore

Keep the previous verified kit until the new version passes acceptance. Back up PostgreSQL before loading a new image and start it with the same Compose project and database volume. Test restore to a separate database before removing your backup.

```bash
docker compose --env-file images.env -f compose.yml -f compose.airgap.yml exec -T postgres \
  pg_dump -U sddp -Fc sddp > sddp-backup.dump
docker compose --env-file images.env -f compose.yml -f compose.airgap.yml exec -T postgres \
  createdb -U sddp sddp_restore
docker compose --env-file images.env -f compose.yml -f compose.airgap.yml exec -T postgres \
  pg_restore -U sddp --exit-on-error -d sddp_restore < sddp-backup.dump
```

On the recovery test stand, add a Compose override named `compose.restore.yml` so SDDP connects to the restored database.

```yaml
services:
  app:
    environment:
      SddP_DB_URL: jdbc:postgresql://postgres:5432/sddp_restore
```

Restart only the app with the update kit's image and the recovery override.

```bash
docker compose --env-file images.env -f compose.yml -f compose.airgap.yml -f compose.restore.yml \
  up -d --no-build --pull never --no-deps app
```

Generate the REST artifact and Kafka clients again from the projects in the restored database. Build them offline, then run the Orders GET and POST requests and publish and receive an Orders event. Keep the pre-upgrade backup for rollback if the older app cannot read the upgraded schema.

The automated acceptance scenario in `scripts/test-airgap-install.sh` performs those round trips after installation, after an offline image update, and after restoring the pre-upgrade backup.
