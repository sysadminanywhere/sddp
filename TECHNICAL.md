# SDDP — Technical Reference

> Complete technical documentation for operators and integrators.

---

## Table of Contents

1. [Data Model](#data-model)
2. [REST API](#rest-api)
3. [UI Pages](#ui-pages)
4. [Graph Editor Protocol](#graph-editor-protocol)
5. [Environment Management](#environment-management)
6. [Intermediate Representation (IR)](#intermediate-representation-ir)
7. [Topology Snapshot v1](#topology-snapshot-v1)
8. [Code Generation](#code-generation)
7. [Plugin Executors](#plugin-executors)
8. [Security Model](#security-model)
9. [Database Schema](#database-schema)
10. [Configuration](#configuration)
11. [Deployment](#deployment)
12. [Development](#development)

---

## Data Model

### Entities

| Entity | Key Fields | Description |
|--------|------------|-------------|
| **Project** | `id` (UUID), `name` (unique), `created_at` | Integration workspace |
| **Environment** | `id`, `project_id`, `code`, `name`, `position`, `is_protected`, `created_at` | Profile within project (DEV/STAGE/PROD) |
| **Service** | `id`, `project_id`, `name`, `service_type` (CORE/EXTERNAL/ADAPTER), `description`, `position_x`, `position_y`, `created_at` | Node in topology |
| **API Operation** | `id`, `service_id`, `name`, `version`, `description`, `pattern` (REQUEST_RESPONSE/PUB_SUB), `request_schema` (JSONB), `response_schema` (JSONB), `created_at` | Belongs to service; multiple versions supported |
| **Connection** | `id`, `project_id`, `environment`, `source_service_id`, `target_service_id`, `source_api_id`, `target_api_id`, `transport_type` (REST), `connection_settings` (JSONB), `mapping_rules` (JSONB), `updated_at` | Directed edge in one environment |

### Connection Settings (JSONB)

```json
{
  "host": "target-host",
  "port": 8080,
  "target_endpoint": "/api/v1/action",
  "timeout_ms": 5000,
  "retries": 3,
  "security_type": "NONE|MTLS|JWT"
}
```

### Mapping Rules (JSONB)

```json
{
  "target_api": "targetApiName v1",
  "transformations": [
    { "type": "DIRECT_COPY", "from": "$.field", "to": "$.field" },
    { "type": "HARDCODED_VALUE", "value": "static-value", "to": "$.field" },
    { "type": "CONCATENATE", "from": ["$.a", "$.b"], "to": "$.c", "separator": "-" }
  ]
}
```

---

## REST API

Base path: `/api/v1`

### Projects

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/projects` | List all projects |
| `POST` | `/projects` | Create project `{name}` |
| `GET` | `/projects/{id}` | Get project |
| `DELETE` | `/projects/{id}` | Delete project (cascade) |
| `POST` | `/projects/import` | Import Topology Snapshot v1 |
| `GET` | `/projects/{id}/apis` | All APIs in project (for connection editor) |

### Services

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/projects/{id}/services` | List services |
| `POST` | `/projects/{id}/services` | Create service `{name, serviceType, description}` |
| `GET` | `/projects/{id}/services/{serviceId}` | Get service |
| `PUT` | `/projects/{id}/services/{serviceId}` | Update service (incl. `position_x`, `position_y`) |
| `DELETE` | `/projects/{id}/services/{serviceId}` | Delete (409 if used in connections) |

### API Operations

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/projects/{id}/services/{serviceId}/apis` | List APIs |
| `POST` | `/projects/{id}/services/{serviceId}/apis` | Create API |
| `GET` | `/projects/{id}/services/{serviceId}/apis/{apiId}` | Get API |
| `PUT` | `/projects/{id}/services/{serviceId}/apis/{apiId}` | Update API |
| `DELETE` | `/projects/{id}/services/{serviceId}/apis/{apiId}` | Delete (409 if used in connections) |
| `POST` | `/projects/{id}/services/{serviceId}/apis/{apiId}/versions` | Create new version (backward-compatible) |

### Connections

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/projects/{id}/connections/{connectionId}` | Get connection detail |
| `POST` | `/projects/{id}/connections` | Create connection |
| `PUT` | `/projects/{id}/connections/{connectionId}` | Update connection |
| `PUT` | `/projects/{id}/connections/{connectionId}/mapping` | Update mapping rules only |
| `DELETE` | `/projects/{id}/connections/{connectionId}` | Delete connection |

### Environments

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/projects/{id}/environments` | List environments |
| `POST` | `/projects/{id}/environments` | Create environment |
| `PUT` | `/projects/{id}/environments/{environmentId}` | Update (rename, protect, position) |
| `DELETE` | `/projects/{id}/environments/{environmentId}` | Delete (409 if connections exist) |
| `GET` | `/projects/{id}/environments/compare?sourceEnvironment=X&targetEnvironment=Y` | Compare connections |
| `POST` | `/projects/{id}/connections/copy` | Copy connections `{sourceEnvironment, targetEnvironment, mode: MERGE|REPLACE}` |

### Topology & IR

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/projects/{id}/topology?environment=ENV` | Services + connections for UI |
| `POST` | `/projects/{id}/ir?environment=ENV` | Build IR manifest |
| `POST` | `/projects/{id}/snapshot?environment=ENV` | Export Snapshot v1 (all envs if omitted) |

### Code Generation

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/codegen/generate` | Generate code `{projectId, environment, packageName, clientName, plugin?}` |
| `GET` | `/codegen/plugins` | List active plugins |

### Health

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/actuator/health` | Health check |

---

## UI Pages

All pages are server-rendered static HTML + vanilla JS modules. State (projectId, environment) synced via `localStorage` + query params.

| Route | Purpose |
|-------|---------|
| `/` (`index.html`) | Project list: create, import, select, delete |
| `/project.html?projectId=...` | Workspace tabs: Overview, Services, Connections, Environments, Build |
| `/service.html?projectId=...&serviceId=...` | Service card + API list + inbound/outbound connections |
| `/service-form.html?projectId=...&mode=create\|edit\|delete` | Service CRUD form |
| `/api.html?projectId=...&serviceId=...&apiId=...&mode=view\|edit\|version\|delete` | API operation CRUD + versioning |
| `/connection.html?projectId=...&connectionId=...&mode=create\|edit\|delete` | Connection CRUD form |
| `/environment.html?projectId=...&environmentId=...&mode=create\|edit\|delete` | Environment CRUD form |
| `/graph.html?projectId=...` | Graphical editor for current environment |

### i18n

- `EN` (default) / `RU` — switched via header combobox, persisted in `localStorage`
- All UI strings in `static/i18n.js` (keyed by page.section.key)

---

## Graph Editor Protocol

Host page: `static/graph.html` + `static/graph-page.js`  
React Flow bundle: `static/flow/graph.js` + `static/flow/graph.css` (built from `frontend/`)

### Events (window → bundle)

| Event | Payload | Description |
|-------|---------|-------------|
| `sddp:graph-data` | `{nodes, edges, environment, services, apis}` | Initial render |
| `sddp:graph-edges` | `edges[]` | Update edges only |
| `sddp:graph-connect` | `{source, target}` | User dragged handle → open connection dialog |
| `sddp:graph-edge-click` | `edgeId` | User clicked edge → show details panel |
| `sddp:graph-auto-layout` | `{direction: LR|RL|TB|BT}` | Run dagre layout |
| `sddp:graph-fit-view` | — | Fit all nodes in viewport |

### Events (bundle → window)

| Event | Payload | Description |
|-------|---------|-------------|
| `sddp:graph-positions` | `{nodeId: {x, y}, ...}` | Node drag ended → host persists via PUT /services/{id} |
| `sddp:graph-connect` | `{source, target}` | Request connection dialog |
| `sddp:graph-edge-click` | `edgeId` | Request edge details panel |

### Validation (host-side)

- Runs after each data/edge change
- Checks: required target fields present, mapping references exist, mapping not empty
- Edge class: `sddp-ok` / `sddp-warning` / `sddp-error`
- Issues shown in right panel, clickable → highlights edge

---

## Environment Management

### Protected Environments

- Default: `PROD` (configurable via `is_protected` flag)
- Mutations (create/update/delete connections, copy into, delete environment) require:
  - Form checkbox **or**
  - Header `X-SDDP-Confirm-Protected: true`
- Without confirmation → HTTP 409 with message

### Comparison

`GET /environments/compare?sourceEnvironment=DEV&targetEnvironment=STAGE`

Returns diff array with `state`: `CHANGED`, `MISSING_IN_TARGET`, `MISSING_IN_SOURCE`, `SAME`

### Copy

`POST /connections/copy` with `{sourceEnvironment, targetEnvironment, mode}`

- `MERGE`: add missing, keep existing
- `REPLACE`: target becomes exact copy of source

---

## Intermediate Representation (IR)

`POST /projects/{id}/ir?environment=ENV` → `IrManifest`

```json
{
  "system_name": "project-name",
  "environment": "DEV",
  "generated_at": "2026-...",
  "services": [
    {
      "service_name": "orders",
      "is_external": false,
      "outbound_connections": [
        {
          "client_name": "orders",
          "target_service_name": "payments",
          "transport_type": "REST",
          "connection_settings": { ... },
          "methods": [{ "method_name": "charge", "pattern": "REQUEST_RESPONSE", ... }],
          "mapping_rules": { "target_api": "...", "transformations": [...] }
        }
      ]
    }
  ]
}
```

Used by codegen and external consumers.

---

## Topology Snapshot v1

`POST /projects/{id}/snapshot?environment=ENV` (all envs if omitted)

```json
{
  "formatVersion": 1,
  "projectName": "billing",
  "environments": [{"code": "DEV", "name": "Development", "position": 10, "isProtected": false}],
  "services": [...],
  "apis": [...],
  "connections": [...]
}
```

Import: `POST /projects/import` with snapshot JSON → creates new project with restored topology.

Each environment includes its code, display name, order and protection flag. Services include optional `positionX` and `positionY` graph coordinates. A snapshot exported for one environment contains only that environment and its connections; all services and APIs are included. Import restores precisely the environments in the snapshot and rejects connections to undeclared environments.

---

## Code Generation

### Built-in Generator (FreeMarker)

Template: `src/main/resources/templates/proxy-adapter.java.ftl`

Produces per-connection Java class with:
- Package + class name from request
- `RestClient` with connect/read timeouts from `connection_settings`
- Retry loop (`retries` times on `ResourceAccessException`)
- Resilience4j `CircuitBreaker` (sliding window 5, 100% failure threshold, 30s open)
- Methods matching IR `methods[]` → `uri(target_endpoint)`
- Embedded mapping logic (Jackson `ObjectMapper` + transformation rules)

**Limitations**: REST only, REQUEST_RESPONSE only, `security_type=NONE` only. MTLS/JWT → use plugin.

### Plugin Framework

Two executors, mutually exclusive via Spring profiles:

| Profile | Class | Description |
|---------|-------|-------------|
| `docker` (default) | `DockerPluginExecutor` | Runs plugin container with bind-mounted `/input` (IR JSON) + `/output`; captures stdout/stderr; exit≠0 → `PluginGenerationException` |
| `k8s` | `KubernetesJobPluginExecutor` | Creates ConfigMap + Job; reads base64-gzipped tar from pod logs between `SDDP_OUTPUT_BEGIN`/`END` markers |

Plugin image must contain: `/bin/sh`, `plugin` (executable), `tar`, `gzip`, `base64`, `tr`.

### PluginGenerationException

Thrown when executor fails:
- `exitCode`: container/Job exit code
- `message`: includes captured stderr/logs
- HTTP 502 from controller

---

## Plugin Executors

### DockerPluginExecutor (`@Profile("docker")`)

- Uses `docker-java` (zerodep HTTP client)
- Network mode: `none` (air-gapped)
- Limits: 512MB RAM, 1 CPU, 128 processes, 5 min timeout; Docker logs rotate at 1 MB and returned stderr is capped at 64 KiB
- Read-only root filesystem; mounts: `/input` (ro), `/output` (16 MiB tmpfs), `/tmp` (8 MiB tmpfs), `/control` (64 KiB tmpfs), with inode limits
- Output: at most 500 archive entries, 5 MB per file and 10 MB total; symlinks and unsafe paths are rejected
- Plugin image requires `/bin/sh`, `plugin`, `tar`, `head`, `mv` and `sleep`; the container stays alive briefly while its output is collected
- Cleans up container + temp dir on completion

### KubernetesJobPluginExecutor (`@Profile("k8s")`)

- Requires: `SDDP_K8S_NAMESPACE` (default `default`), `SDDP_K8S_POLL_SECONDS` (default 2)
- Creates: ConfigMap (IR), Job (plugin pod)
- Pod: `restartPolicy: Never`, `readOnlyRootFilesystem`, `dropAllCapabilities`, no service account token
- Polls Job status → on success extracts output archive from logs
- Cleans up Job + ConfigMap on completion/failure

### PluginExecutionFactory

Resolves active executor via `ObjectProvider<PluginExecutor>` — exactly one must be active.

---

## Security Model

- **No authentication/authorization built-in** — designed to run behind reverse proxy (OAuth2/OIDC, mTLS, VPN)
- **No PII in database** — only integration metadata
- **Protected environment mutations** require explicit confirmation (checkbox or header)
- **Delete guards** prevent orphaned references (HTTP 409)
- **Plugin isolation**: Docker network=none / K8s readOnlyRootFilesystem + dropped capabilities
- **Docker socket access** required for Docker executor — treat as root equivalent

The API can create, modify and delete projects. Production Compose binds the application to `127.0.0.1` and requires `POSTGRES_PASSWORD`; the PostgreSQL port is not published. For remote access, use an HTTPS gateway on the same host or a private network, authenticate and authorize every request including `/api` and `/actuator`, and block direct access to the application and database. Browser-facing gateways must also mitigate CSRF for state-changing requests. Restrict access to the Docker daemon to trusted administrators; the production Compose example does not mount its socket automatically.

The optional Caddy gateway below applies HTTP Basic authentication to every route and rejects state-changing requests unless `Origin` exactly matches the configured HTTPS origin. API clients must supply that header for POST, PUT, PATCH and DELETE. It uses one shared account, not per-user roles, MFA, or audit identity. Allow port 8080 only on the host's loopback interface and never expose the application directly; anyone with local host access can bypass the gateway.

For a team, prefer an identity-aware gateway backed by an organization's OIDC provider with MFA. The gateway must authenticate every route, remove user-supplied identity headers, authorize requests based on trusted identity and groups, protect state-changing requests against CSRF, and record actor, request, outcome and timestamp without logging credentials or payloads. A gateway alone cannot enforce project-level permissions unless it has project-aware policy; add application-level authorization and audit events before offering per-project roles. The included Basic Auth configuration does not provide these capabilities.

---

## Database Schema

Tables (Flyway migrations V1–V5):

| Table | Purpose |
|-------|---------|
| `projects` | Project metadata |
| `services` | Service nodes + positions |
| `service_apis` | API operations + JSON schemas |
| `project_environments` | Environment definitions |
| `topology_connections` | Connections per environment + settings + mapping |
| `codegen_plugins` | Registered plugin descriptors |

All FKs `ON DELETE CASCADE` from `projects`.

---

## Configuration

### Application Properties (`application.yml`)

```yaml
spring:
  profiles:
    default: docker          # or k8s
  datasource:
    url: jdbc:postgresql://localhost:5432/sddp
    username: sddp
    password: sddp
  flyway:
    enabled: true
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SddP_DB_URL` | `jdbc:postgresql://localhost:5432/sddp` | PostgreSQL JDBC URL |
| `SddP_DB_USERNAME` | `sddp` | DB user |
| `SddP_DB_PASSWORD` | `sddp` | DB password |
| `SPRING_PROFILES_ACTIVE` | `docker` | `docker` or `k8s` |
| `SDDP_K8S_NAMESPACE` | `default` | K8s namespace for Jobs |
| `SDDP_K8S_POLL_SECONDS` | `2` | Job status poll interval in seconds (minimum `0.5`) |
| `SDDP_MAX_REQUEST_BYTES` | `10000000` | Maximum API request body in bytes, including chunked requests |

API write requests exceeding the configured limit receive HTTP 413. The limit applies to snapshots, JSON schemas and other API request bodies; increase it deliberately for larger projects. It bounds each request, not the aggregate memory consumed by concurrent requests.

---

## Deployment

### Docker (Production)

```bash
# Build the image from this checkout and start the stack
export POSTGRES_PASSWORD='<strong unique password>'
docker compose -f docker-compose.prod.yml up -d --build
```

To run a prebuilt image instead, set `IMAGE_NAME` (repository) and `IMAGE_TAG` (defaults to `latest`) and skip the build: `IMAGE_NAME=docker.io/<owner>/sddp IMAGE_TAG=0.1.0 docker compose -f docker-compose.prod.yml up -d --no-build`. Step-by-step instructions for both paths are in the [README](README.md#docker-single-host).

`docker-compose.prod.yml`:
- `app` (built image) + `postgres:16-alpine`
- Healthcheck on postgres → `depends_on: condition: service_healthy`
- Non-root user `sddp:1000` in container

### HTTPS gateway (optional)

Point a DNS name to the server and allow inbound TCP ports 80 and 443 (and optionally UDP 443) for Caddy's automatic certificates. Choose a strong database password and gateway password. In a Bash shell, generate the bcrypt hash interactively without putting the plaintext password in command history:

```bash
export POSTGRES_PASSWORD='<strong unique database password>'
export SDDP_DOMAIN='sddp.example.com'
export SDDP_USER='sddp-admin'
read -rs -p 'Gateway password: ' SDDP_GATEWAY_PASSWORD; echo
export SDDP_PASSWORD_HASH="$(printf '%s' "$SDDP_GATEWAY_PASSWORD" | docker run --rm -i caddy:2-alpine caddy hash-password --algorithm bcrypt)"
unset SDDP_GATEWAY_PASSWORD
docker compose -f docker-compose.prod.yml -f docker-compose.gateway.yml up -d --build
```

Keep the generated hash and database password in a secret manager for restarts; do not commit them or put them into a shared `.env` file. Verify that `https://sddp.example.com/` prompts for credentials, that unauthenticated access is denied, and that port 8080 is not reachable remotely. For API calls with Basic authentication, include `Origin: https://sddp.example.com` on state-changing requests. Use an organizational identity-aware gateway instead when individual accounts, MFA or detailed audit trails are required.

### Database backup and recovery

Snapshots export a project; they are not a full database backup. The following commands create a consistent PostgreSQL custom-format backup and rehearse restoring it into a separate database without changing the live database. Run them from the project directory with the production Compose environment configured:

```bash
umask 077
docker compose -f docker-compose.prod.yml exec -T postgres pg_dump -U sddp -Fc sddp > sddp-backup.dump
docker compose -f docker-compose.prod.yml exec -T postgres createdb -U sddp sddp_restore_check
docker compose -f docker-compose.prod.yml exec -T postgres pg_restore -U sddp --exit-on-error -d sddp_restore_check < sddp-backup.dump
docker compose -f docker-compose.prod.yml exec -T postgres psql -U sddp -d sddp_restore_check -c 'SELECT count(*) FROM projects;'
```

Use a new restore database name for each rehearsal; `createdb` intentionally fails if it already exists. Check application data and Flyway history before treating a backup as usable. A real recovery should stop writes, restore into a new database, validate it with the matching application version, and switch the connection only after validation; retain the original database until recovery is accepted. Backups may contain sensitive connection configuration: encrypt them, restrict access, retain copies outside the server, and rehearse restoration regularly. Database backups do not include Caddy certificates, gateway credentials or deployment configuration; back those up separately.

`bash scripts/test-compose-recovery.sh` rehearses export/import, IR generation, backup/restore and app restart in an isolated Compose project, and removes only its own temporary containers, volumes and backup afterward. It requires Docker Compose with `!reset`/`!override`, Bash, curl and Node.js. Set `SDDP_TEST_BROWSER=1` to also create a project through the HTTPS UI using Playwright CLI and installed Chrome; CI enables this check. The browser check uses port 18443 on localhost and needs npm access to install the pinned Playwright CLI.

`SDDP_UPGRADE_BASE_REF=HEAD^ bash scripts/test-upgrade-rollback.sh` builds the selected Git revision's source and the current source into separate images, creates data with the baseline, upgrades while retaining the database, writes more data, and verifies both records after rolling back. CI uses the pull request base or preceding commit as the baseline. This verifies only the selected revisions and migrations; before a real release, repeat it against the actual deployed version and keep a restorable backup, because a future schema migration may prevent rollback.

### Docker (Development)

```bash
docker compose -f docker-compose.dev.yml up -d postgres
docker compose -f docker-compose.dev.yml up app
```

- Uses `target: build` stage (Maven image)
- Mounts `./src`, `./pom.xml`, `~/.m2` → hot reload via `mvn spring-boot:run`

### Kubernetes

- Manifests: [`deploy/kubernetes.yaml`](deploy/kubernetes.yaml) — Deployment, Service, ServiceAccount, namespace-scoped Role/RoleBinding; step-by-step instructions in the [README](README.md#kubernetes)
- Image: the same Dockerfile image, started with `SPRING_PROFILES_ACTIVE=k8s` and a service account token mounted; PostgreSQL stays external and Flyway migrates on startup
- Requires: `SDDP_K8S_NAMESPACE` (default `default`; the manifest takes it from `metadata.namespace`) and `SDDP_K8S_POLL_SECONDS` (default `2`, minimum `0.5`)
- ServiceAccount permissions: `create/get/delete ConfigMap`, `create/get/delete Job`, `get/list Pod`, `get Pod/log`
- Plugin Jobs: `backoffLimit: 0`, `activeDeadlineSeconds: 300`, read-only root filesystem, all capabilities dropped, no service account token; the executor waits up to 6 minutes
- Limits: IR under 800 KB (ConfigMap data), plugin output under 5 MB compressed and 10 MB extracted
- Images are started with `imagePullPolicy: IfNotPresent` and no `imagePullSecrets`; pre-pull them or attach a pull secret to the namespace's `default` service account
- Apply a NetworkPolicy to restrict plugin pod egress

---

## Development

### Prerequisites

- Java 21, Maven 3.9+, Node 20+ (for frontend), Docker

### Frontend Build

```bash
cd frontend
npm ci
npm run build
# Outputs to ../src/main/resources/static/flow/graph.js + graph.css
```

### Backend

The project uses the Maven Central mirror on Google Cloud Storage because direct access to Central may be unreliable on some networks. This is a third-party mirror and newly published artifacts can appear with a delay. To use the original Central endpoint instead, run `mvn -Dmaven.central.url=https://repo.maven.apache.org/maven2/ verify`, or build the image with `docker build --build-arg MAVEN_CENTRAL_URL=https://repo.maven.apache.org/maven2/ .`. Project global settings preserve the user's Maven settings, including proxy configuration; a user-defined mirror can override the project mirror.

```bash
mvn spring-boot:run
# or
mvn verify  # unit tests and H2 integration; external integration tests are opt-in
```

### Dependency Security

`pom.xml` pins `jackson-bom.version` (Jackson 3) and `jackson-2-bom.version` (Jackson 2) because the versions managed by Spring Boot 4.0.9 — 3.1.7 and 2.21.7 — are affected by CVE-2026-110879; 3.1.8 and 2.21.8 are the patched releases. The same applies to the explicit Bouncy Castle, `commons-io` and resilience4j versions, which override older transitives from `docker-java` and `kubernetes-client`. When a Spring Boot patch release manages patched versions, the Jackson overrides can be dropped. Keep `mvn dependency:tree` and an OSV/GitHub advisory scan in the upgrade checklist.

### Test Categories

| Test | Command | Requirements |
|------|---------|--------------|
| Unit and H2 integration | `mvn test` | None |
| PostgreSQL snapshot integration | `mvn -Dtest=SnapshotPostgresIntegrationTest test` | PostgreSQL, `SDDP_POSTGRES_IT=true`; database user must be able to create schemas and install pgcrypto |
| Docker Plugin IT | `mvn -Dtest=DockerPluginIntegrationTest test` | Docker daemon + `SDDP_DOCKER_IT=true` |
| Profile Isolation | `mvn -Dtest=PluginProfileTest test` | None |

The PostgreSQL test creates a uniquely named schema, runs the Flyway migrations, exercises import/export and HTTP JSON, and drops that schema afterward. CI runs this test against its PostgreSQL service. Docker plugin integration additionally requires the `sddp-plugin-fixture:local` image (build it from `src/test/resources/docker-plugin`).

Application JSON models and schema validation use Jackson 3. Hibernate's JSON/JSONB format mapper is explicitly configured for Jackson 3 because the Hibernate release in use otherwise selects Jackson 2 when it is present. Jackson 2 databind remains a runtime dependency for Spring's detection of transitive Jackson 2 components; generated Java clients also retain their existing Jackson 2 contract. The deprecated Spring Boot Jackson 2 compatibility module is no longer used.

### Project Structure

```
src/
├── main/
│   ├── java/ru/sddp/platform/
│   │   ├── api/           # DTOs (ApiModels, CodegenModels, TopologyRequests)
│   │   ├── domain/        # JPA entities
│   │   ├── repository/    # Spring Data repositories
│   │   ├── service/       # Business logic
│   │   └── web/           # REST controllers
│   └── resources/
│       ├── db/migration/  # Flyway V1–V5
│       ├── static/        # HTML/JS/CSS (served as-is)
│       └── templates/     # FreeMarker codegen templates
└── test/                  # Unit + integration tests
frontend/                  # React Flow + dagre source (npm build → static/flow/)
```

---

## Versioning & Release

- Version in `pom.xml` → propagated to Docker image labels + tags via CI
- CI tags:
  - `main` branch: `{version}-{sha}`, `{version}`, `latest`
  - `vX.Y.Z` tag: `X.Y.Z`, `X.Y`, `latest`
- Release: push tag `v0.1.0` → CI builds and pushes to Docker Hub when both `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` secrets are configured; otherwise publication is skipped.
- A release tag must exactly match the non-SNAPSHOT version in `pom.xml`; CI rejects mismatches before publishing. Verify the previous deployed version's backup and upgrade/rollback test before tagging.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `409 Service is used by N connection(s)` | Delete service with active connections | Delete connections first |
| `409 Environment PROD is protected` | Mutation without confirmation | Add checkbox or `X-SDDP-Confirm-Protected: true` |
| Codegen `400 MTLS not supported` | Built-in generator with MTLS connection | Switch to `NONE` or register plugin |
| Graph editor blank | React Flow bundle not built | `cd frontend && npm run build` |
| Plugin `502` | Container exit≠0 | Check `PluginGenerationException.message` for stderr |
| Flyway `validate` fails | DB schema mismatch | `mvn flyway:migrate` or clean DB |

---

## References

- [README.md](README.md) — Project overview & quick start
