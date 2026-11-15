# SDDP Core Backend

## Quick Start

Requires Java 21, Maven, and Docker. Start the database:

```text
docker compose up -d postgres
mvn spring-boot:run
```

By default the app connects to `jdbc:postgresql://localhost:5432/sddp`. Override via `SddP_DB_URL`, `SddP_DB_USERNAME`, `SddP_DB_PASSWORD`.

## Data Model

- **Project** — integration workspace.
- **Environment** — project profile (`DEV`, `STAGE`, `PROD` by default): its own set of connections and connection settings. Environments are stored in DB with code, name, order, and a protected flag.
- **Service** — project node of type `CORE`, `EXTERNAL`, or `ADAPTER`.
- **API Operation** — belongs to a service: name, version, pattern (`REQUEST_RESPONSE` / `PUB_SUB`), request/response JSON Schema.
- **Connection** — directed link between two services and specific API operations within one environment. One connection describes one target API operation; multiple operations require multiple connections.

## Implemented API

- `GET /api/v1/projects` — list projects for active system selection
- `POST /api/v1/projects` — create project (`{"name":"billing"}`)
- `GET /api/v1/projects/{id}` — project card
- `DELETE /api/v1/projects/{id}` — delete project with all services, APIs, environments, and connections (cascade)
- `POST /api/v1/projects/import` — import Topology Snapshot (v2 or legacy v1)
- `GET/POST /api/v1/projects/{id}/services` — list and create services
- `GET/PUT/DELETE /api/v1/projects/{id}/services/{serviceId}` — service card, settings, and delete (HTTP 409 if used in connections; cascades API deletions)
- `GET /api/v1/projects/{id}/connections/{connectionId}` — single connection card (editor)
- `GET/POST /api/v1/projects/{id}/services/{serviceId}/apis` — list and create service API operations
- `GET/PUT/DELETE /api/v1/projects/{id}/services/{serviceId}/apis/{apiId}` — API operation card, edit, and delete (HTTP 409 if used in connections)
- `POST /api/v1/projects/{id}/services/{serviceId}/apis/{apiId}/versions` — create backward-compatible new API version; existing connections stay on the previous version
- `GET /api/v1/projects/{id}/apis` — all project APIs (for connection editor)
- `GET/POST /api/v1/projects/{id}/environments` — list and create project environments
- `PUT/DELETE /api/v1/projects/{id}/environments/{environmentId}` — rename/protect and delete environment (HTTP 409 if connections exist)
- `GET /api/v1/projects/{id}/environments/compare?sourceEnvironment=DEV&targetEnvironment=STAGE` — compare connections and settings of two environments
- `POST /api/v1/projects/{id}/connections/copy` — copy connections between environments (`MERGE` — add missing, `REPLACE` — replace target set)
- `POST /api/v1/projects/{id}/connections` — create connection between API operations
- `PUT /api/v1/projects/{id}/connections/{connectionId}` — update connection, transport, settings, and mapping
- `PUT /api/v1/projects/{id}/connections/{connectionId}/mapping` — update only mapping rules
- `DELETE /api/v1/projects/{id}/connections/{connectionId}` — delete connection
- `POST /api/v1/topology/connections` — same contract in `{projectId, connection}` format
- `GET /api/v1/projects/{id}/topology?environment=DEV` — get services and connections of environment for UI
- `POST /api/v1/projects/{id}/ir?environment=DEV` — build and validate IR manifest
- `POST /api/v1/projects/{id}/snapshot?environment=DEV` — download JSON Topology Snapshot v2 (all environments without parameter)
- `POST /api/v1/codegen/generate` — generate Java proxy via FreeMarker; optional `plugin` field runs Docker/Kubernetes executor
- `GET /api/v1/codegen/plugins` — active generation plugins for UI
- `/actuator/health` — health check

## UI

Selected project and environment are stored in `localStorage` and synced with query params (`?projectId=…&environment=…`), so a direct link to any screen can be shared.

- `/` (`index.html`) — projects: create, import snapshot, select active project, and delete project
- `/project.html?projectId=…` — project workspace with tabs "Overview", "Services", "Connections", "Environments", "Build"; IR, codegen, and snapshot live on the "Build" tab
- `/service.html?projectId=…&serviceId=…` — service page: settings, API operation list, inbound/outbound connections
- `/api.html?projectId=…&serviceId=…` — API operation create form
- `/api.html?projectId=…&serviceId=…&apiId=…&mode=view|edit|version|delete` — view, edit, new version, and delete API operation
- `/service-form.html?projectId=…&mode=create` and `/service-form.html?projectId=…&serviceId=…&mode=edit|delete` — separate forms for create, edit, and delete service
- `/connection.html?projectId=…&mode=create` and `/connection.html?projectId=…&connectionId=…&mode=edit|delete` — separate forms for create, edit, and delete connection
- `/environment.html?projectId=…&mode=create` and `/environment.html?projectId=…&environmentId=…&mode=edit|delete` — separate forms for create, edit, and delete environment
- `/graph.html?projectId=…` — graphical connection editor for current environment: drag from source service handle to target service to create a connection; problematic connections are highlighted; node positions are persisted in the project

API schemas are edited as a field tree (name, type, required, description, nested objects and arrays). JSON Schema is generated automatically and shown read-only. Connection mapping is configured by picking fields from source and target request schemas; JSON manifest is also preview-only. All CRUD operations open on dedicated form pages: project and service lists keep only links; create/edit/delete of services, connections, and environments happen on `service-form.html`, `connection.html`, and `environment.html`.

Only the latest version of each API operation is shown in the UI; older versions remain in DB for existing connections but don't clutter lists and dropdowns. Deleting an API operation used in connections is blocked — change or delete those connections first.

Environments are created, edited, and deleted on dedicated `environment.html` pages (from "Environments" tab and project page): code, name, order, and "protected" flag. A protected environment (default `PROD`) requires confirmation for creating, editing, and deleting connections, copying into it, and its own deletion; confirmation is a checkbox in the form sent via `X-SDDP-Confirm-Protected: true` header. The tab also provides environment comparison (differences `CHANGED`, `MISSING_IN_TARGET`, `MISSING_IN_SOURCE`) and connection copy in `MERGE` and `REPLACE` modes. Connection counters on overview are scoped to the current environment; snapshot can be exported for all environments or a single one.

The graphical editor `/graph.html` shows project services as nodes and current environment connections as edges. To create a connection, drag the handle on the right side of a source service onto a target service: a dialog opens for API operation, transport, and endpoint selection; direction is inferred from the gesture. Edge color reflects schema validation: green — no issues, yellow — warnings (e.g., no field mapping configured), red dashed — errors (e.g., mapping misses required target request fields or references non-existent fields). Issue list on the right is clickable: selecting an issue highlights the edge and shows its details with edit/delete form links. "Auto-layout" (dagre, four directions) and "Fit view" buttons arrange the graph; dragging nodes persists coordinates to the service (`position_x`/`position_y`) via `PUT /services/{serviceId}`.

Shared styles and client API live in `static/app.css` and `static/common.js`; schema editor — in `static/schema-editor.js`.

Migrations are in `src/main/resources/db/migration`. Schema stores project, service, API, and connection metadata; no PII or user token fields.

## Verification

```text
mvn test
```

The graph editor uses locally built React Flow and `dagre` assets. After changing `frontend/` files, run `npm ci && npm run build` from `frontend/`, then rebuild the backend. No runtime network fetches. Dragged node positions are stored in `services` table (`position_x`/`position_y`), so layout survives page reload and browser change; positions are not included in snapshots.

Docker plugin is active in the `docker` profile (default); for Kubernetes use `SPRING_PROFILES_ACTIVE=k8s`, `SDDP_K8S_NAMESPACE`, and `SDDP_K8S_POLL_SECONDS`. The process needs Docker socket access for Docker plugins. Full runtime acceptance with real PostgreSQL/Docker/Kubernetes requires corresponding infrastructure; locally only compilation and unit tests are verified.

The Kubernetes executor passes IR via a temporary ConfigMap and reads the result archive from the pod log. Plugin image must contain `/bin/sh`, `plugin`, `tar`, `gzip`, `base64`, and `tr`. Backend ServiceAccount needs permissions to create/delete ConfigMap and Job, read Job status, list pods and their logs. To forbid plugin network access in the cluster, a separate NetworkPolicy is required; without it, air-gapped isolation of the Job is not guaranteed. Real Kubernetes cluster execution not yet verified.

The built-in REST generator uses `timeout_ms` for connect/read and `retries` for network error retries (defaults: 5000 ms and 3). A retried POST may duplicate the operation if the server processed the request before the connection dropped; target API must ensure idempotency. MTLS and JWT are not supported by the built-in generator and require a suitable plugin.

The generated REST client uses `io.github.resilience4j:resilience4j-circuitbreaker:2.3.0`. Circuit breaker opens after five network-access errors and permits probe calls after 30 seconds; errors are propagated to the caller, not masked as success. This dependency must be included in the project consuming the generated code.

Docker integration test runs after building the fixture image locally: `docker build --network=none -t sddp-plugin-fixture:local src/test/resources/docker-plugin` with `SDDP_DOCKER_IT=true`. On Windows with Docker Desktop, set `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`; both integration scenarios verified via this named pipe. On Linux, standard Unix socket is used. Never expose Docker API to the network unprotected: access equals host admin.

## Acceptance Checklist

Before delivery, the system is verified against:

1. **Compile-time immutability** — generated Java code contains no dynamic reconfiguration or external server polling; everything runs on static Spring properties.
2. **No PII** — no DB table contains columns for personal names, emails, phones, or user tokens; only integration metadata schemas.
3. **Clean Code & Error Specification** — if a Docker plugin container exits non-zero, the backend captures its `stderr` and throws `PluginGenerationException` with the log.
4. **Kubernetes-native readiness** — K8s Job management code is isolated in `@Profile("k8s")`, so the project runs locally without Kubernetes via the standard `@Profile("docker")`.