# Contributing to SDDP

Thank you for your interest in contributing! This guide covers the workflow, standards, and requirements.

---

## Getting Started

### Prerequisites

- **Java 21** (Temurin/Eclipse/OpenJDK)
- **Maven 3.9+**
- **Node 20+** (for frontend build)
- **Docker** (for integration tests and plugin executor)
- **PostgreSQL 16** (local or via `docker compose up -d postgres`)

### Local Setup

```bash
# 1. Clone
git clone https://github.com/your-org/sddp.git
cd sddp

# 2. Start database
docker compose up -d postgres

# 3. Build frontend (required for graph editor)
cd frontend
npm ci
npm run build
cd ..

# 4. Run application
mvn spring-boot:run
# → http://localhost:8080
```

### Run Tests

```bash
# Unit tests only
mvn test

# Docker plugin integration test (requires Docker daemon)
SDDP_DOCKER_IT=true mvn -Dtest=DockerPluginIntegrationTest test

# Specific test class
mvn -Dtest=PluginProfileTest test
```

---

## Development Workflow

### Branch Strategy

| Branch | Purpose |
|--------|---------|
| `main` | Protected, always deployable. CI runs on every push. |
| `feature/*` | New features, bug fixes. Open PR to `main`. |
| `fix/*` | Hotfixes for production. |
| `docs/*` | Documentation updates. |

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Types:** `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `perf`, `build`, `ci`

**Examples:**
```
feat(graph): add auto-layout direction selector
fix(api): return 409 when deleting referenced service
docs(readme): add quick start section
test(service): add delete guard test for environment
```

### Pull Requests

1. **Title** — conventional commit format
2. **Description** — what changed, why, testing done
3. **Checks** — CI must pass (tests, build, docker)
4. **Review** — at least 1 approval required
5. **Squash** — merge with squash, delete branch

---

## Code Standards

### Java (Backend)

- **Formatter:** `mvn formatter:format` (uses `spring-boot-starter-parent` defaults)
- **Static analysis:** `mvn spotless:check` (if configured)
- **Imports:** Organized, no wildcards
- **Nullability:** Annotate `@Nullable` / `@NonNull` where meaningful
- **Exceptions:** Custom exceptions in `service/` package, mapped in `ApiExceptionHandler`
- **Transactions:** `@Transactional` on service methods, read-only where possible

### JavaScript (Frontend)

- **ES2022+**, modules (`type="module"`)
- **No frameworks** — vanilla JS, small modules
- **Naming:** `camelCase` for vars/functions, `PascalCase` for classes
- **Events:** CustomEvent via `window.dispatchEvent` / `window.addEventListener`
- **i18n:** All strings in `static/i18n.js` — never hardcode UI text

### Database

- **Migrations only** — Flyway `V{number}__{description}.sql`
- **Never edit applied migrations** — new version for changes
- **Naming:** `snake_case` tables/columns, `UPPER_CASE` SQL keywords
- **Constraints:** FK with `ON DELETE CASCADE` from `projects`

### Documentation

- **README.md** — marketing overview only
- **TECHNICAL.md** — complete technical reference
- **API changes** → update TECHNICAL.md + OpenAPI spec (if exists)
- **New env vars** → document in TECHNICAL.md Configuration section

---

## Testing Requirements

### Unit Tests

- Location: `src/test/java/...`
- Framework: JUnit 5 + Mockito
- Coverage: Aim for >80% on business logic (`service/`)
- Naming: `*Test.java`, methods `void should<ExpectedBehavior>When<Condition>()`

### Integration Tests

- Docker plugin: `DockerPluginIntegrationTest` (requires `SDDP_DOCKER_IT=true`)
- Profile isolation: `PluginProfileTest`
- Use `@SpringBootTest` sparingly — prefer service-layer tests with mocks

### Frontend Tests

- Not yet configured — manual verification via browser for now
- Graph editor: test in Chrome/Firefox with `npm run build` + `mvn spring-boot:run`

---

## Adding Features

### New REST Endpoint

1. DTO in `api/` (request/response records)
2. Service method in `service/`
3. Controller in `web/` with validation
4. Exception handling in `ApiExceptionHandler`
5. Unit test for service + controller
6. Update `TECHNICAL.md` API table

### New Frontend Page

1. HTML in `static/` with `data-i18n` keys
2. JS module in `static/` (IIFE, registers on `window.SDDP.onReady`)
3. i18n keys in `static/i18n.js` (EN + RU)
4. Link from existing page (breadcrumb, button, tab)
5. Add to `TECHNICAL.md` UI Pages table

### New Graph Feature

1. Modify `frontend/src/graph.jsx` (React Flow component)
2. Extend event protocol if needed (document in TECHNICAL.md)
3. Build: `cd frontend && npm run build`
4. Update host page `static/graph-page.js` for new events

### New Plugin Type

1. Implement `PluginExecutor` interface
2. Annotate with `@Profile("<name>")`
3. Register in `PluginExecutionFactory` (auto via `ObjectProvider`)
4. Add integration test
5. Document in TECHNICAL.md Plugin Executors

---

## Release Process

1. **Version bump** in `pom.xml` (remove `-SNAPSHOT` for release)
2. **Changelog** — update `CHANGELOG.md` (if exists) or release notes
3. **Tag** — `git tag v0.1.0 && git push origin v0.1.0`
4. **CI** — builds and pushes to GHCR + Docker Hub with tags:
   - `0.1.0`, `0.1`, `latest`
5. **GitHub Release** — generate from tag, attach artifacts if any

---

## Code of Conduct

- Be respectful and inclusive
- No harassment, discrimination, or offensive language
- Focus on technical merit
- Report violations to maintainers

---

## Questions?

- **Issues:** Bug reports, feature requests
- **Discussions:** Design questions, RFCs
- **Security:** Email maintainers directly (see `SECURITY.md` if exists)

---

*Thank you for contributing to SDDP!*