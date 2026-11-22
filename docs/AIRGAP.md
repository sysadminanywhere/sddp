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
docker run --rm --pull never --network none -v "$PWD:/build" sddp-maven-builder:pilot
```

Use the Maven builder tag recorded in the kit's `images.env`. Deploy the JAR with the application's pinned Java dependencies. The generated client connects to the target configured for the selected environment. Supply runtime credentials to the consuming application. Topology fields and mapping constants belong to the project snapshot and may appear in generated source, so keep credentials and working payloads in the consuming system.

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

Verify the restored project and artifact against the restored database. Preserve the backup or restore it into a fresh database when an older application cannot read the upgraded schema.
