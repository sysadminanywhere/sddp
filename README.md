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

## Quick Start

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
