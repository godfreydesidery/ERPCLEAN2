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

Hot reload is on via **Spring Boot DevTools** (dev profile): it watches `target/classes` and restarts
the app context (~1–2 s) whenever classes recompile — new endpoints/beans appear **without** a manual
stop/start. The catch: a recompile must land in `target/classes`. Make that happen one of two ways:
- **IDE auto-build** — IntelliJ: enable *Build project automatically* (and registry
  `compiler.automake.allow.when.app.running`); VS Code Java auto-builds on save. Save a file → restart.
- **Watch command** — if you don't rely on IDE auto-build, recompile in a *second* terminal
  alongside `spring-boot:run`. Either re-run `mvn -o compile` after each change, or run this
  no-extra-plugin PowerShell loop that recompiles whenever a `.java` file changes (each `mvn compile`
  refreshes `target/classes`, which makes DevTools restart):
  ```powershell
  cd backend
  $fsw = New-Object IO.FileSystemWatcher "src\main\java", "*.java"
  $fsw.IncludeSubdirectories = $true
  Write-Host "Watching src\main\java — save a .java file to recompile + hot-reload (Ctrl-C to stop)…"
  while ($true) {
    $fsw.WaitForChanged([IO.WatcherChangeTypes]::All) | Out-Null
    Start-Sleep -Milliseconds 300   # debounce a burst of saves
    mvn -o -q compile
  }
  ```

> If a new endpoint 404s after editing, the running app is on a **stale** `target/classes` — the
> recompile didn't fire. Trigger a build (save with auto-build on, or `mvn compile`) and DevTools
> restarts. A full restart is only needed for changes DevTools can't hot-swap (e.g. dependency/`pom`
> changes).

### 3. Web client (host, hot reload)
```bash
cd web
npm install        # first run only
npm start          # ng serve on http://localhost:4200, /api proxied to :8081
```
Angular's dev server hot-reloads on save out of the box. One exception: changes to **`angular.json`**
(e.g. adding a global stylesheet) need a dev-server **restart** to take effect.

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
