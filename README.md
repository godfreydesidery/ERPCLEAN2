# ERP

Clean-build ERP — **Spring Boot 3 / Java 21 · Angular 21 · PostgreSQL 15**, modular monolith.
Built by a team of specialised agents (see [.claude/agents/README.md](.claude/agents/README.md)).

- **Conventions:** [PROJECT-CONVENTIONS.md](PROJECT-CONVENTIONS.md) — fixed stack + engineering invariants.
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md) · **Data model:** [DATA-MODEL.md](DATA-MODEL.md)
- **Decisions:** [docs/decisions/](docs/decisions/)
- **Requirements:** [docs/requirements/](docs/requirements/) · **Stories:** [USER-STORIES.md](USER-STORIES.md)
- **First module:** IAM — plan in [docs/iam-build-plan.md](docs/iam-build-plan.md).

## Layout
```
backend/   Spring Boot API (com.erp) — modular monolith
web/        Angular 21 standalone-components web client
docs/       requirements, decisions (ADRs), plans
docker-compose.yml   local Postgres (+ optional containerised API)
```

## Prerequisites
Java 21 · Maven 3.9+ · Node 22+ · Angular CLI · Docker + Compose.

## Running (Slice 0)

### 1. Start Postgres
```bash
docker compose up -d db
```
Postgres listens on `localhost:5434` (db `erp`, user `erp`, password `erp` — dev only;
host port 5434 avoids clashing with other local Postgres instances).

### 2. Backend API (host, hot reload)
```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
Health check: <http://localhost:8081/api/v1/health> → `{"data":{"status":"UP",...},"errors":[]}`.

### 3. Web client (host, hot reload)
```bash
cd web
npm install        # first run only
npm start          # ng serve on http://localhost:4200, /api proxied to :8080
```
The shell shows a live **API: UP** badge — proof the web↔API path (interceptors + envelope unwrap)
works end to end.

### Fully containerised API (optional)
```bash
docker compose --profile api up --build
```

## Tests
```bash
cd backend && mvn test          # JUnit + ArchUnit (boundary rules); Testcontainers Postgres in S1+
cd web && npm test              # unit (includes interceptor envelope/header tests)
cd web && npm run build         # production bundle
```

## Conventions that bite if ignored
- Schema is owned by **Flyway** (`ddl-auto=validate`). Pre-stable: **edit the baseline + recreate the
  DB**, don't stack migrations (PROJECT-CONVENTIONS §3.6).
- Every API response is wrapped in `ApiResponse<T>`; the web interceptor unwraps it — services see
  raw `T`.
- Externally exposed entities carry `id` (numeric) + `uid` (ULID); URLs address by `uid`.
- No secrets in git (`.env`, `*.key`, `*.pem` are ignored). Production needs a stable RS256 JWT key
  from a secret store before deploy.
