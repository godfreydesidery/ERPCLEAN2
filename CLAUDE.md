# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A clean-build **ERP** — a modular monolith: **Spring Boot 3.3 / Java 21 / PostgreSQL 15** backend
(`backend/`) + **Angular 21** standalone-components web client (`web/`). It started as IAM only;
it now spans 25 business modules (sales, purchases, stock, GL, AR, AP, cash/bank, tax, fixed
assets, HR/payroll, manufacturing, projects, CRM, budgeting, FX, costing, BI, approvals, parties,
products, reporting, routes, notifications, documents) and 78 Flyway migrations (latest V78).

Authoritative design docs (read the relevant one before non-trivial work — they go deeper than this file):
- [PROJECT-CONVENTIONS.md](PROJECT-CONVENTIONS.md) — fixed stack + the engineering invariants below.
- [ARCHITECTURE.md](ARCHITECTURE.md) · [DATA-MODEL.md](DATA-MODEL.md) (IAM-detailed; later modules in `docs/`).
- [docs/decisions/](docs/decisions/) — 47 ADRs (0001–0047); the *why* behind most non-obvious choices.
- [docs/requirements/](docs/requirements/) · [USER-STORIES.md](USER-STORIES.md) · [docs/data-model/](docs/data-model/).

Note: the foundational docs (ARCHITECTURE.md, DATA-MODEL.md) describe the IAM spine in detail and
predate most modules. For a business module, trust the shipped code and its ADR over the prose.

## Commands

All backend commands run from `backend/`, web from `web/`. Postgres must be up first for the app
and integration tests.

```bash
# Local stack
docker compose up -d db                                   # Postgres on localhost:5434 (db/user/pass = erp)
mvn spring-boot:run -Dspring-boot.run.profiles=dev        # API on :8081, mgmt/actuator on :9090
                                                          # dev profile bootstraps rootadmin / RootPass12345 on an empty DB
npm install && npm start                                  # web on :4200, /api proxied to :8081 (web/proxy.conf.json)

# Backend tests
mvn -B clean test                                         # surefire: unit + ArchUnit gates (fast, no Docker) — the PR gate
mvn -B clean verify                                       # adds failsafe *IT integration tests (needs Docker)
mvn -Dtest=PermissionResolverTest test                   # one unit test class
mvn -Dit.test=BranchOverrideIT verify                    # one integration test (Testcontainers Postgres)

# Web tests / build
npm test -- --watch=false                                 # vitest unit + axe a11y specs (the CI gate)
npm test -- --watch=false --include=src/app/features/admin/sales   # narrow to a path; or `-t "<name pattern>"`
                                                          # --include= is required: a BARE path is parsed as the
                                                          # project name, the builder never loads, and the run
                                                          # dies on "Unknown argument: watch" having run 0 tests
npm run build                                             # production bundle (also a CI gate)
npm run e2e                                               # Playwright + axe (opt-in; needs API on :8081 — see below)

# Migration hygiene gate (run before merging a migration)
bash scripts/check-migrations.sh                          # rule 1: no duplicate Flyway versions
BASE=origin/develop bash scripts/check-migrations.sh      # + rule 2: applied migrations not edited/renamed/deleted
```

### Integration tests on Windows
Testcontainers ITs fail with "connection refused" unless **Ryuk is disabled** and a **singleton
container** is reused (`TESTCONTAINERS_RYUK_DISABLED=true`; see `PostgresIntegrationTest` /
`testcontainers.properties`). CI sets this env var; set it locally too. There are ~111 `*IT` files
(failsafe, `verify`) vs ~84 `*Test` files (surefire, `test`).

### Backend hot reload
Spring Boot **DevTools** restarts the app context when `target/classes` changes. New endpoints
404 until a recompile lands there: enable IDE auto-build, or run `mvn -o compile` in a second
terminal after edits (full README has a PowerShell file-watcher loop). A `pom`/dependency change
needs a real restart.

### Running Playwright e2e
`npm run e2e` starts `ng serve` itself and needs the backend on :8081 with a `rootadmin` user.
`auth.setup.ts` logs in once and reuses the session. **Gotcha:** port 4200 may host a different
project locally — point Playwright elsewhere with `PW_BASE_URL`. `e2e/` (repo root, not `web/e2e/`)
holds separate Node operator scripts for large-scale seeding/smoke against a throwaway stack — see
[e2e/README.md](e2e/README.md).

## Architecture — the load-bearing invariants

These are enforced by code/tests and cut across every module. Violating one is a bug, not a style nit.

1. **Modular monolith, layered, ArchUnit-enforced.** Base package `com.erp`. Layering is
   `controller → service → repository → domain`; controllers never touch repositories; **modules
   talk to each other only via `..domain.dto..` / `..domain.enums..` and the outbox — never by
   importing another module's entity or service.** `ModuleBoundaryTest` (and
   `EndpointAuthorizationTest`) fail the build on violation. If a needed dependency breaks the rule,
   the design is wrong — fix it or write an ADR; don't relax the rule.
   - Controllers: flat under `com.erp.api` — no per-module subpackage (e.g. `api/SalesInvoiceController.java`), one per resource.
   - Each module: `com.erp.modules.<name>/{domain/{entity,dto,enums,event},service,repository}`.
   - Services are `interface Xxx` + `class XxxImpl`, `@Transactional` at public methods.
   - Cross-cutting infra lives under `com.erp.platform/{common,security,audit,events,bootstrap}`.

2. **`ApiResponse<T>` envelope.** Every REST response is wrapped (`data`, `errors[]`, meta) by a
   `ResponseBodyAdvice` — controllers return raw `T`. On the web side `apiResponseInterceptor`
   ([web/src/app/core/api/http.interceptors.ts](web/src/app/core/api/http.interceptors.ts)) unwraps
   it, so feature services see `T` directly. Callers needing the full envelope (e.g. paging `meta`)
   set the `SKIP_UNWRAP` http-context token. `errors[]` carries **user-safe strings only** — never
   leak exception text.

3. **`id` + `uid` identity duality.** Externally exposed entities carry a numeric `id` (BIGINT,
   internal FK target) **and** a `uid` (ULID `VARCHAR(26)`, external). **URLs address entities by
   uid**: `/api/v1/<resource>/uid/{uid}`. Long ids serialise as JSON **strings** (global Jackson
   config) so 64-bit ids survive JavaScript — the web side types every id field as `string`.

4. **Multi-company / multi-branch tenancy.** Transactional tables carry `company_id` + `branch_id`.
   `RequestContext` (request-scoped) is built by a servlet filter from the JWT plus an optional
   **`X-Branch-Uid`** header; the filter verifies the branch is in the caller's `user_branch`
   assignments (else 403). Switching branch is context-only — no DB write, no re-login. The web
   `authHeaderInterceptor` attaches `Authorization: Bearer` + `X-Branch-Uid` on every API call.
   IAM admin tables are exempt from the blanket tenant predicate (administration is cross-branch).

5. **RBAC by permission, not role.** The atomic unit is a `Permission` with a dot-separated code
   (`SALES_INVOICE.POST`, `USER.MANAGE`). `@PreAuthorize("hasPermission('CODE')")` references
   **permission codes, never role names**. Roles are permission bundles. Permissions are seeded via
   Flyway (`R__seed_permissions.sql`) — **a new permission-gated endpoint must add its code to the
   seed.** `app_user.is_root` short-circuits to allowed (always audited).

6. **Auth flow (IAM).** Login issues an access JWT (15 min, RS256) + a single-use refresh token
   (7 d, stored SHA-256-hashed, rotated on use with reuse-detection). Login lands the user in their
   **default branch** (`user_branch.is_default`); a user may be assigned to many branches, exactly
   one default. Dev signs with an ephemeral in-memory RSA key (rotates each restart → logs everyone
   out; fine for dev). **Production must load a stable RS256 key from a secret store**
   (`ERP_JWT_SIGNING_MODE=file`) — a release-gating item.

7. **Audit is written by an aspect**, not calling code (so it can't be forgotten). `audit_log` is
   **append-only** — the deploy grants the app DB role no UPDATE/DELETE on it.

8. **Cross-module side effects = transactional outbox.** Write a `domain_event` row in the same TX
   as the business write; a poller dispatches it. Never call into another module's service for a
   side effect, and never use Spring's in-memory `ApplicationEventPublisher` for cross-module events.

9. **Persistence discipline.** Schema is owned by **Flyway** (`ddl-auto=validate`, never `update`).
   Optimistic locking (`@Version`) on transactional aggregates. Append-only posting tables
   (`stock_move`, `audit_log`, GL postings) — corrections are new postings, never updates.
   Soft-deletable masters use a `status` enum (`ACTIVE`/`INACTIVE`/`ARCHIVED`).

## Migrations — frozen / additive-only (since 2026-06-20)

Migrations live in `backend/src/main/resources/db/migration/` (`V<n>__*.sql` versioned,
`R__*.sql` repeatable). **The schema is frozen and the database is durable in every environment
(local, QA, prod) — never wiped or recreated.**

- **Never edit, rename, or delete an applied migration.** Its checksum drifts and the app refuses
  to boot (`validate-on-migrate`) on a populated DB. Any schema/seed change is a **new `V<n>`
  migration** (next free version) — or, for convergent reference data (permission codes + grants),
  an edit to the repeatable `R__seed_permissions.sql` (it upserts and self-heals).
- **Author against populated tables**, not an empty DB: expand→backfill→constrain across separate
  migrations (never single-shot `NOT NULL`/`UNIQUE`/`FK` on a populated table); `CREATE INDEX
  CONCURRENTLY` in its own non-transactional migration.
- CI enforces both **rule 1** (no duplicate versions) and **rule 2** (immutability vs the base
  branch) via `scripts/check-migrations.sh` (job `migration-hygiene`). `application.yml` pins
  `clean-disabled`, `validate-on-migrate`, `out-of-order: false`, `baseline-on-migrate: false`.
- Full runbook (authoring, recovery, the durable-DB patterns): [docs/ops/migrations-and-seeding.md](docs/ops/migrations-and-seeding.md).

Don't wipe local data either (no `docker compose down -v`); the `erp-db-data` volume is meant to
persist across restarts like QA.

## Coding standards

- **Java:** Google Java Style, `final` where reasonable, **records for DTOs** (`*Dto` suffix).
  **Lombok** `@Getter @Setter` on entities only — NOT `@Data`/`@EqualsAndHashCode`/`@ToString`
  (they break JPA identity/lazy loading). Hand-write constructors and behaviour methods (invariants,
  state transitions); Lombok generates only plain accessors.
- **TypeScript:** strict, no `any` without a justification comment. Angular **standalone components,
  no NgModules**. Web feature screens live under `web/src/app/features/admin/<module>/`; shared
  primitives under `web/src/app/shared/` (note `uid-picker` — forwards `id`/`aria-labelledby` to its
  inner `<select>`; use `[id]`/`[ariaLabelledby]`, see the a11y memory). Core/HTTP/auth wiring under
  `web/src/app/core/`.
- **Accessibility:** ships WCAG 2.1 AA; axe-core runs in the web unit suite and gates the build on
  new serious/critical violations.
- **Commits/PRs:** Conventional Commits, one logical change per PR.

## Branch workflow

`develop` is the integration branch; `main` is release. **Never commit to or push `main`.** Branch
off `develop` (or `feat/**`), commit, and open a PR — the owner merges to `main`. CI (backend +
web) runs on `main`, `develop`, and `feat/**`.

## Deployment

Deployment lives under `infra/`, not in the app modules:
- `infra/prod/` — production stack: `docker-compose.yml` (+ `docker-compose.hostdb.yml` for a
  host/native Postgres), a `Caddyfile` (self-signed TLS reverse proxy — bind it to
  `ERP_PUBLIC_HOST`), `generate-jwt-keys.sh` (stable RS256 keypair for `ERP_JWT_SIGNING_MODE=file`),
  and `backup.sh`/`restore.sh`.
- `infra/qa/` — QA stack: `deploy.sh`/`deploy.ps1`, `entrypoint.sh`, `supervisord.conf`,
  `application-qa.yml`, and `qa.env.example` (copy to `qa.env`; `*.local.*` files stay out of git).

Prod/QA never wipe the DB (see the migration rules). `backend/Dockerfile` builds the API image.

## Config quick reference

- Ports: API `8081` (`ERP_API_PORT`), actuator/Prometheus `9090` (`ERP_MANAGEMENT_PORT`, separate
  from the public edge), Postgres `5434` (host), web dev `4200`.
- Profiles: `dev` (`application-dev.yml`, hot reload + auto-bootstrap + SQL logging),
  `prod` (`application-prod.yml`, JSON logging, stable JWT key required).
- Swagger UI at `/swagger-ui/index.html`, OpenAPI JSON at `/v3/api-docs` (gate off with
  `ERP_SWAGGER_ENABLED=false` in prod).
- Bootstrap (`ERP_BOOTSTRAP_*`): on an empty DB creates org + company + default branch + root admin.
  `ERP_BOOTSTRAP_ADMIN_PASSWORD` must be ≥12 chars (the app refuses to start otherwise).
- Secrets stay out of git (`.env`, `*.key`, `*.pem` are ignored).
