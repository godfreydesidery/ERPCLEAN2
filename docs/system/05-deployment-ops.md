# Deployment and Operations

ERPCLEAN2 ships as a Spring Boot 3 / Java 21 backend (with the Angular bundle baked into the
jar's `static/` path), a PostgreSQL 15 database, and — for local development — a separate
`ng serve`. This document covers the three environments, how to run locally, migration
discipline, the QA and production deploys, backup/restore, observability, and CI.

The operational design is recorded in [ADR-0038](../decisions/0038-production-hardening.md);
the runbooks live under [docs/ops/](../ops/) and the deploy artifacts under `infra/`.

## 1. Environments

| Environment | Topology | Database | Use |
|---|---|---|---|
| **Local dev** | `docker compose up -d db` (Postgres) + backend on host (`mvn spring-boot:run`, dev profile) + `ng serve` | Containerised Postgres, host port 5434 | Day-to-day development; the canonical e2e environment. |
| **QA** | Single container (`infra/qa`): API + in-container Postgres + Angular bundle, run by supervisord | In-container Postgres, persistent volume | Release smoke / UAT. |
| **Production** | Split topology (`infra/prod`): separate API and Postgres containers (reference single-node compose; topology-agnostic Dockerfile) | Separate `db` container with a persistent volume, or managed Postgres | Live (fenced ops decisions still open). |

The QA single-container shape (coupled API + DB lifecycle, no scaling) is deliberately
**wrong for production** but right for QA: one image, one `docker run`, one volume. Production
splits the lifecycles.

## 2. Running locally

Three processes: Postgres in Docker, the backend on the host (for hot reload), and `ng serve`.

### 2.1 Database

```bash
docker compose up -d db
```

This starts `postgres:15-alpine` as container `erp-db`, database `erp`, user/password
`erp`/`erp` (dev defaults only — not production secrets), mapped to **host port 5434**
(5432/5433 are taken by other local projects; the container stays on 5432 internally).

### 2.2 Backend

```bash
cd backend
SPRING_PROFILES_ACTIVE=dev ERP_API_PORT=8081 mvn spring-boot:run
```

The dev profile uses `dev-in-memory` JWT signing (fresh key each restart) and bootstraps the
super-user **`rootadmin` / `RootPass12345`** (dev only — never a production credential). The
API binds **port 8081** (`server.port: ${ERP_API_PORT:8081}`). Flyway runs on startup and
applies all migrations.

A containerised API is available opt-in via `docker compose --profile api up --build`, but
day-to-day dev runs the API on the host for hot reload.

### 2.3 Frontend

```bash
cd web
npm install      # first time
npm run start    # ng serve on :4200
```

`ng serve` runs on **port 4200**; its dev proxy forwards `/api` to the backend on
**`http://localhost:8081`** (`web/proxy.conf.json`). Open `http://localhost:4200`.

### 2.4 Ports and bootstrap credentials (summary)

| Component | Port | Notes |
|---|---|---|
| Postgres (dev) | host 5434 → container 5432 | container `erp-db`, db `erp` |
| Backend API | 8081 | `ERP_API_PORT`, dev bootstraps `rootadmin`/`RootPass12345` |
| `ng serve` | 4200 | proxy `/api` → `:8081` |
| Management (prod) | 9090 | Prometheus, internal network only |

## 3. Flyway migration discipline

- **Flyway owns all schema.** Hibernate runs `ddl-auto=validate` (never `update`) — the
  schema is defined by V-prefixed migrations only (PROJECT-CONVENTIONS §3.6).
- **Additive after freeze.** The IAM baseline (V1) was edited in place while the schema was
  pre-stable; after that freeze, all changes are **additive** new migrations. The migration
  head is currently around **V83**.
- **Never edit an applied migration.** A V-prefixed file that has run in any environment must
  not be changed — Flyway validates checksums on startup and a restored DB confirms the schema
  matches. Editing applied migrations is a backend-engineer call, never an ops call
  ([backup-restore.md](../ops/backup-restore.md)).
- **No schema change without a migration.** A new permission-gated endpoint needs its
  permission in a seed migration; a new transactional table needs `company_id` + `branch_id`
  and the tenant predicate.
- **Failed migration rollback:** stop the API, restore the backup taken **before** the deploy,
  fix the migration SQL, redeploy. Flyway `validate` on the next startup confirms the restored
  schema (see [backup-restore.md](../ops/backup-restore.md) "Rollback story").

## 4. QA deployment

QA is a single container on the target box (see `infra/qa/README.md`). After a one-time
bootstrap (install Docker, clone the repo with a fine-grained read-only PAT, place
`infra/qa/qa.env` with the DB and bootstrap secrets), each release is one command from a
developer machine:

```bash
# macOS / Linux / git-bash
export ERP_SSH_KEY=~/keys/qa.pem
infra/qa/deploy.sh                 # deploys the branch named in infra/qa/deploy.env
```

```powershell
# Windows / PowerShell
$env:ERP_SSH_KEY = "C:\path\to\qa.pem"
infra\qa\deploy.ps1
infra\qa\deploy.ps1 -Branch main   # or another branch
```

The deploy script SSHes in, `git pull`s the branch, rebuilds the image, and restarts the
container. The QA image runs the API, an in-container Postgres, and the Angular bundle under
supervisord; it activates `SPRING_PROFILES_ACTIVE=qa` (`infra/qa/application-qa.yml` pins a
Hikari pool of 10 and INFO logging). The container reads the JWT signing mode from
`ERP_JWT_SIGNING_MODE` (defaults to `dev-in-memory`).

- **Data-preserving deploy** is the default: the persistent volume survives a redeploy, so QA
  data carries across releases.
- **Recreate / wipe:** stop and remove the container **and** drop the `erpclean2-data` volume,
  then redeploy — the next start re-bootstraps from `qa.env` on a fresh DB.

The non-secret target host/user/branch live in committed `infra/qa/deploy.env`; the SSH key
path, the GitHub PAT, and the bootstrap secrets stay out of git (`deploy.env.local`,
`qa.env`, both gitignored).

## 5. Production deployment

Production uses the split topology in `infra/prod` (ADR-0038 D-6). The `infra/prod/Dockerfile`
is a three-stage build (Angular build → Maven package with the bundle copied into
`static/` → `eclipse-temurin:21-jre-alpine` runtime) and is **topology-agnostic** — it is the
canonical artifact for any orchestrator. The `infra/prod/docker-compose.yml` is a clearly
labelled **reference single-node topology**: a `db` (Postgres 15) service and an `api` service
on port 8081, both `restart: unless-stopped`, with healthchecks and `depends_on:
service_healthy`. If the owner chooses K8s / ECS / a PaaS, the compose file is documentation
and the Dockerfile is the deploy unit.

Key production settings (from the prod compose / `.env.example`):

- `SPRING_PROFILES_ACTIVE=prod` → JSON logging, pinned INFO/WARN log levels.
- `ERP_JWT_SIGNING_MODE=file` (hard-wired) → stable RS256 keys, bind-mounted read-only; tokens
  survive restarts and can be shared across replicas (see [jwt-keys.md](../ops/jwt-keys.md)).
- `ERP_API_PORT=8081` everywhere (the one consistent port); the management port is 9090.
- DB connection via `ERP_DB_URL` / `ERP_DB_USER` / `ERP_DB_PASSWORD`.
- `ERP_BOOTSTRAP_ENABLED=false` by default; set `true` **only** for the first deploy on a
  fresh DB with a strong `ERP_BOOTSTRAP_ADMIN_PASSWORD`, then immediately unset and restart.
- `ERP_API_IMAGE` lets a CI-built, SHA-tagged image be specified for rollback without editing
  the compose file.

All secrets come from a gitignored `.env` (see `infra/prod/.env.example`) plus the bind-mounted
JWT key files — the key files never enter the image. Several production decisions are explicitly
**fenced** pending an owner choice (host/orchestrator, managed vs self-hosted Postgres,
container registry, secrets backend, TLS edge / reverse proxy, log aggregation, metrics
scraping) — see ADR-0038 D-Fenced.

## 6. Backup and restore

PostgreSQL is the only datastore; backups use `pg_dump` custom format (`-Fc`) and `pg_restore`
(scripts in `infra/prod/`, runbook in [backup-restore.md](../ops/backup-restore.md)).

- **Backup:** `infra/prod/backup.sh` writes a timestamped compressed `.dump` to `BACKUP_DIR`
  and prunes archives older than `BACKUP_RETAIN_DAYS` (default 14–30). It reads connection
  details from `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD`. Exits non-zero on failure
  so a cron monitor can detect it.

  ```cron
  0 2 * * * BACKUP_DIR=/backups BACKUP_RETAIN_DAYS=14 sh infra/prod/backup.sh >> /var/log/erpclean2-backup.log 2>&1
  ```

- **Restore:** stop the API (so Flyway/Hibernate cannot write mid-restore), take a fresh
  backup of the current state for forensics, then `infra/prod/restore.sh` (`pg_restore --clean
  --if-exists`, with a confirmation guard). Restart the API and watch the log for the Flyway
  validate result.
- **Off-host copy:** the `/backups` volume is on the same host as the database — keep at least
  one backup off-host (S3, rsync to a second server). If the owner adopts managed Postgres,
  rely on the managed snapshot facility and treat these scripts as the self-hosted fallback.

## 7. Observability

- **Health probes** (ADR-0038 D-4). The actuator exposes `health,info` on the main port with a
  readiness/liveness split: readiness includes `db` + `diskSpace`; liveness is `ping`. The
  container `HEALTHCHECK` targets `/actuator/health/liveness` on 8081 (a DB outage should not
  trigger a container restart — that is the orchestrator's concern via `depends_on`); the
  compose `api` healthcheck polls `/actuator/health/readiness`. `start-period` allows for
  Flyway migration time at startup.
- **Metrics** (ADR-0038 D-5). Micrometer + Prometheus exposes `/actuator/prometheus` on a
  **separate management port 9090**, reachable only on the internal container network — never
  bound to the host edge — so metrics (memory, bean names, tenant counts) are not exposed
  unauthenticated over the public port. Custom metrics include outbox dispatch + FAILED-event
  counters (tagged by bounded `event_type`, so a poison-event loop is immediately visible) and
  Hikari pool saturation; JVM, GC, and HTTP timing metrics come free.
- **Structured logging** (ADR-0038 D-3). The `prod` profile emits **JSON to stdout**
  (`logstash-logback-encoder`), 12-factor-compliant and ingestible by any aggregator; dev/test
  use a plain-text pattern. Both include the MDC fields.
- **Correlation id** (ADR-0038 D-2). `JwtRequestContextFilter` puts `requestId`, `userId`,
  `username`, `companyId`, and `branchId` into the SLF4J MDC, so every log line is traceable to
  a request and tenant. The `requestId` is taken from an inbound `X-Request-Id` (or generated)
  and echoed in the response header. MDC is cleared per request. Known limitation: async outbox
  / scheduled handlers run on different threads and do not inherit the MDC.
- **Exception logging** (ADR-0038 D-1). Unexpected 500s are logged with the full stack trace
  server-side; the client still receives only `"An unexpected error occurred."`. Filter-level
  401/403 denials are normal flow and are not logged as errors.

## 8. Continuous integration

Two GitHub Actions workflows under `.github/workflows`, both triggered on push to
`main`/`develop`/`feat/**` and on PRs to `main`/`develop`.

### 8.1 Backend CI — `backend-ci.yml` (ADR-0038 D-8)

- **`fast-check` (REQUIRED):** `mvn -B -ntp clean test` — compile + surefire unit tests +
  ArchUnit gates (`ModuleBoundaryTest`, `EndpointAuthorizationTest`). No Docker; fast (~2–3
  min). This is the PR gate, so RBAC-gate and module-boundary regressions are caught
  immediately.
- **`integration-test` (observe-only):** `mvn -B -ntp clean verify` — the full ~98 Testcontainers
  Postgres integration tests on a Linux runner (`TESTCONTAINERS_RYUK_DISABLED=true`, singleton
  container). Marked `continue-on-error: true` until proven stable across 5+ runs, then promoted
  to required. `clean` is deliberate — an incremental compile gave a false green in a prior wave.
  Failsafe reports are uploaded on failure.

### 8.2 Web CI — `web-ci.yml`

- **`build-and-test`:** `npm ci` → `npm run build` (production, must be zero errors) →
  `npm test` (Vitest unit specs including the axe a11y gate) → `npm audit --omit=dev
  --audit-level=high` (CVSS-7+ gate, mirroring the Maven threshold).
- **`e2e` (manual):** Playwright is wired but not auto-triggered (`if: false`) — it needs a
  running backend; run it via `workflow_dispatch` or locally.

The identity-discipline static gate (`npm run c1`) and the full testing pyramid are described
in [07-testing.md](07-testing.md). The dependency-CVE runbook (OWASP dependency-check,
Dependabot, the fenced weekly scan) is in [docs/ops/security-sweep.md](../ops/security-sweep.md).
