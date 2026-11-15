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
7. [Topology Snapshot v2](#topology-snapshot-v2)
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
| `POST` | `/projects/import` | Import Topology Snapshot v2/v1 |
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
| `POST` | `/projects/{id}/snapshot?environment=ENV` | Export Snapshot v2 (all envs if omitted) |

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

## Topology Snapshot v2

`POST /projects/{id}/snapshot?environment=ENV` (all envs if omitted)

```json
{
  "formatVersion": 2,
  "projectName": "billing",
  "environments": ["DEV", "STAGE"],
  "services": [...],
  "apis": [...],
  "connections": [...]
}
```

Import: `POST /projects/import` with snapshot JSON → creates new project with restored topology.

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
- Limits: 512MB RAM, 1 CPU, 5 min timeout
- Mounts: `/input` (ro), `/output` (rw)
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
| `SDDP_K8S_POLL_SECONDS` | `2` | Job status poll interval |

---

## Deployment

### Docker (Production)

```bash
# Build
docker build -t ghcr.io/owner/sddp:0.1.0 .

# Run with compose
docker compose -f docker-compose.prod.yml up -d
```

`docker-compose.prod.yml`:
- `app` (built image) + `postgres:16-alpine`
- Healthcheck on postgres → `depends_on: condition: service_healthy`
- Non-root user `sddp:1000` in container

### Docker (Development)

```bash
docker compose -f docker-compose.dev.yml up -d postgres
docker compose -f docker-compose.dev.yml up app
```

- Uses `target: build` stage (Maven image)
- Mounts `./src`, `./pom.xml`, `~/.m2` → hot reload via `mvn spring-boot:run`

### Kubernetes

- Build image via CI → push to registry
- Deploy with `SPRING_PROFILES_ACTIVE=k8s`
- Grant ServiceAccount: `create/delete ConfigMap`, `create/delete Job`, `get/list/watch Pod/Job`, `get Pod/log`
- Apply NetworkPolicy to restrict plugin pod egress

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

```bash
mvn spring-boot:run
# or
mvn verify  # runs all tests including Docker integration (needs SDDP_DOCKER_IT=true)
```

### Test Categories

| Test | Command | Requirements |
|------|---------|--------------|
| Unit | `mvn test` | PostgreSQL (testcontainers or local) |
| Docker Plugin IT | `mvn -Dtest=DockerPluginIntegrationTest test` | Docker daemon + `SDDP_DOCKER_IT=true` |
| Profile Isolation | `mvn -Dtest=PluginProfileTest test` | None |

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
- Release: push tag `v0.1.0` → CI builds + pushes to GHCR + Docker Hub

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
- [API Endpoint Catalog](docs/api.md) — Detailed request/response examples
- [Migration Guide](docs/migration.md) — v1 → v2 snapshot upgrade