# SDDP — Spec-Driven Development Platform

> **Design integrations as code. Generate production-ready clients automatically.**

SDDP is an open-source platform for **spec-driven integration development**. Define your services, APIs, and connections once — get validated topology, intermediate representation (IR), and generated client code for any environment.

---

## Why SDDP?

| Problem | SDDP Solution |
|---------|---------------|
| **Drift between docs and code** | Single source of truth: JSON Schema + mapping rules → generated clients |
| **Environment-specific config hell** | Per-environment connections with protected promotion (DEV → STAGE → PROD) |
| **Manual client boilerplate** | One-click Java REST client with timeouts, retries, circuit breaker |
| **Invisible integration topology** | Graphical editor with real-time schema validation |
| **Fragile version upgrades** | Backward-compatible API versions; existing connections stay on old versions |

---

## Core Features

### 🎯 **Spec-First Design**
- Services with typed APIs (REQUEST_RESPONSE / PUB_SUB)
- JSON Schema for request/response with field tree editor
- Field-level mapping between source and target APIs (DIRECT_COPY, HARDCODED_VALUE, CONCATENATE)

### 🌍 **Multi-Environment Topology**
- Isolated connection sets per environment (DEV, STAGE, PROD, custom)
- Protected environments (PROD by default) require explicit confirmation
- Visual comparison & promotion: `MERGE` (add missing) / `REPLACE` (full sync)

### 📊 **Graphical Connection Editor**
- Drag-and-drop between services — direction inferred from gesture
- Real-time schema validation: green ✓ / yellow ⚠ / red ✗
- Clickable issue panel with links to fix mappings
- Dagre auto-layout (LR, RL, TB, BT) + MiniMap + persisted node positions

### 🏗 **Intermediate Representation (IR)**
- Single `POST /ir` builds validated manifest for any environment
- Services, outbound connections, methods, mapping rules — ready for codegen or external tools

### 📦 **Topology Snapshots (v1)**
- Export/import services, API schemas, connections, environment settings, and graph positions
- Versioned, portable JSON — perfect for GitOps or environment seeding

### ⚡ **Code Generation**
- **Built-in**: Java 21 + Spring `RestClient` + Resilience4j circuit breaker + retries
- **Extensible**: Docker / Kubernetes plugin executors (isolated profiles)
- Generated code includes: timeouts, base URLs, embedded mapping logic, circuit breaker config

### 🔒 **Safety & Governance**
- Delete guards: HTTP 409 if service/API/environment is referenced
- No PII in database — only integration metadata
- Protected environment mutations require `X-SDDP-Confirm-Protected` header

---

## Quick Start (local development)

```bash
# 1. Start database
docker compose up -d postgres

# 2. Run application
mvn spring-boot:run
# → http://localhost:8080
```

**Default profiles:** `docker` (local), `k8s` (Kubernetes executor).

The application has no built-in authentication. Keep it on a trusted host or network; for remote access, put it behind an authenticated HTTPS gateway and restrict direct access. See the security guidance in [Technical Reference](TECHNICAL.md#security-model).

---

## Install

Both options run the same image: Java 21 runtime, non-root user, Flyway migrations applied on startup, HTTP on port 8080. They need a PostgreSQL 16 database with a user that may create tables in the target schema.

### Docker (single host)

Requires Docker Engine with the Compose plugin.

```bash
# Build the image from this checkout and start PostgreSQL + the application
export POSTGRES_PASSWORD="$(openssl rand -base64 24)"
docker compose -f docker-compose.prod.yml up -d --build

# Verify
curl -fsS http://127.0.0.1:8080/actuator/health
docker compose -f docker-compose.prod.yml logs -f app
```

`docker-compose.prod.yml` publishes the application only on `127.0.0.1:8080` and keeps PostgreSQL on an internal network with data in the `sddp-postgres` volume. `POSTGRES_PASSWORD` is required — do not reuse a password from an example.

To run an image published by CI instead of building from source (images are pushed when `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` are configured, tagged `<version>`, `<version>-<sha>` and `latest`):

```bash
export POSTGRES_PASSWORD='<strong password>'
export IMAGE_NAME=docker.io/<owner>/sddp
export IMAGE_TAG=0.1.0   # any tag pushed by CI, or the full version

docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d --no-build
```

Upgrades rebuild (`--build`) or pull (`--no-build`) the image and let Flyway migrate on startup; `docker compose down` keeps the database volume, `docker compose down -v` deletes it. Use `SDDP_MAX_REQUEST_BYTES` to change the API body limit, and put [Caddy or another authenticated gateway](TECHNICAL.md#https-gateway-optional) in front of the app before exposing it. Back up the database with the commands in [Technical Reference](TECHNICAL.md#database-backup-and-recovery) before upgrading.

### Kubernetes

[`deploy/kubernetes.yaml`](deploy/kubernetes.yaml) contains a Deployment, Service, ServiceAccount and the namespace-scoped Role the k8s plugin executor needs to create its Jobs.

1. Build and push the image to a registry the cluster can pull from:

   ```bash
   docker build -t registry.example.com/sddp:0.1.0 .
   docker push registry.example.com/sddp:0.1.0
   ```

2. Create the namespace and the database password secret:

   ```bash
   kubectl create namespace sddp
   kubectl -n sddp create secret generic sddp-db --from-literal=password='<strong password>'
   ```

3. Point the manifest at your image and database — `image`, `SddP_DB_URL` and `SddP_DB_USERNAME` in `deploy/kubernetes.yaml`. PostgreSQL is expected to be external (managed service or an operator); the app only needs a reachable JDBC URL and a user allowed to create tables.

4. Apply and wait for the rollout:

   ```bash
   kubectl -n sddp apply -f deploy/kubernetes.yaml
   kubectl -n sddp rollout status deployment/sddp
   ```

5. Reach the API locally, then expose it through an authenticated ingress or gateway:

   ```bash
   kubectl -n sddp port-forward svc/sddp 8080:80
   curl -fsS http://127.0.0.1:8080/actuator/health
   ```

The pod runs as UID 1000 with a read-only root filesystem, no Linux capabilities and `/tmp` on an `emptyDir`. `SDDP_K8S_NAMESPACE` is taken from the pod's namespace, so plugin Jobs and ConfigMaps are created next to the application. The container mounts a service account token because the executor calls the Kubernetes API; plugin Job pods explicitly do not.

Plugin images are started with `imagePullPolicy: IfNotPresent` and no `imagePullSecrets` of their own, so either pre-pull them on the nodes or attach a pull secret to the namespace's `default` service account. The executor polls Job status every `SDDP_K8S_POLL_SECONDS` (default `2`, minimum `0.5`); raise it if many builds run concurrently and the API server rate-limits the application. The application keeps no local state, so additional replicas can be added behind the Service; only the database needs to be shared.

Each plugin Job has limits of 1 CPU, 512 MiB memory and 32 MiB ephemeral storage, with a 16 MiB output volume and a five-minute execution deadline. The cluster administrator must configure pod PID limits at the node level. Large plugin logs can prevent the output archive from being recovered because log reads are capped at 7 MB.

---

## Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Project   │────▶│ Environment │────▶│ Connections │
│  (workspace)│     │  (DEV/STAGE)│     │  (edges)    │
└─────────────┘     └─────────────┘     └─────────────┘
       │                   │                   │
       ▼                   ▼                   ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Services   │     │    APIs     │     │   Mapping   │
│ (nodes)     │     │ (schemas)   │     │  (rules)    │
└─────────────┘     └─────────────┘     └─────────────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           ▼
              ┌─────────────────────────┐
              │     IR / Snapshot       │
              │   (portable manifest)   │
              └───────────┬─────────────┘
                          ▼
              ┌─────────────────────────┐
              │      Codegen            │
              │  (Java / Plugin / ...)  │
              └─────────────────────────┘
```

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| **Runtime** | Java 21, Spring Boot 4, Spring Data JPA |
| **Database** | PostgreSQL 16 + Flyway migrations |
| **Frontend** | Vanilla JS + React Flow (graph) + dagre (layout) |
| **Build** | Maven, FreeMarker templates |
| **Resilience** | Resilience4j CircuitBreaker |
| **Container** | Multi-stage Dockerfile (temurin:21-jre-alpine, non-root) |
| **Orchestration** | Docker Compose / Kubernetes (Job-based plugin executor) |

---

## Documentation

| Document | Description |
|----------|-------------|
| [Technical Reference](TECHNICAL.md) | API reference, data model, configuration, deployment |

---

## Status

**Version 0.1.0** — Core vertical complete:
- ✅ Full CRUD for projects, services, APIs, connections, environments
- ✅ Graph editor with validation & persistence
- ✅ Environment comparison & promotion
- ✅ IR build + Snapshot v1 export/import
- ✅ Java codegen (built-in + plugin framework)
- ✅ Automated unit and integration tests
- ✅ CI/CD: GitHub Actions → Docker Hub when credentials are configured

---

## License

MIT — free for commercial use.

---

## Contributing

Issues and PRs welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

**Made for integration engineers who believe specs should be executable.**
