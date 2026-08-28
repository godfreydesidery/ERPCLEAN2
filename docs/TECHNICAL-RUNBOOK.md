# OrbixERP — Technical Runbook

**How the system is built, how to build and test it, and how every technical subsystem behaves.**

Companion document: [OPERATIONAL-RUNBOOK.md](OPERATIONAL-RUNBOOK.md) — deploying, running and
recovering the estate. This document is for engineers; that one is for operators. Together they are
meant to be sufficient: you should be able to work from these two files without opening the design
documents, which go deeper but are not required to do the job.

*Written 2026-08-28 against the shipped code. Where a version or count is stated, verify it before
relying on it — §14 gives the command.*

---

## Contents

1. [System overview](#1--system-overview)
2. [The load-bearing invariants](#2--the-load-bearing-invariants)
3. [Repository layout](#3--repository-layout)
4. [Local development](#4--local-development)
5. [Build and packaging](#5--build-and-packaging)
6. [Tests and CI gates](#6--tests-and-ci-gates)
7. [Database and migrations](#7--database-and-migrations)
8. [Configuration reference](#8--configuration-reference)
9. [Security architecture](#9--security-architecture)
10. [API conventions](#10--api-conventions)
11. [Observability and health](#11--observability-and-health)
12. [Client applications — POS and HQ](#12--client-applications--pos-and-hq)
13. [Technical troubleshooting](#13--technical-troubleshooting)
14. [Command index](#14--command-index)
15. [Where to go deeper](#15--where-to-go-deeper)

---

## 1 · System overview

OrbixERP (repository `ERPCLEAN2`) is a **modular monolith**.

| Layer | Technology |
|---|---|
| Backend | Spring Boot **3.3.5**, Java **21**, Maven (no wrapper — install Maven) |
| Database | PostgreSQL **15**, schema owned by **Flyway** |
| Web client | Angular **21**, standalone components, **no NgModules** |
| Packaging | The Angular bundle is compiled into `src/main/resources/static/` and served by the same Spring Boot process |
| Desktop till | OrbixPOS — Flutter (Windows) |
| Mobile | OrbixHQ — Flutter (Android) |

**The SPA and the API share one origin and one port. This is not a preference.**
`web/src/environments/environment.prod.ts` sets a **relative** `apiBaseUrl: '/api/v1'`, and the
backend has **no CORS configuration at all**. Serving the SPA from a separate nginx container on a
different origin would break every API call.

### The 25 business modules

`ap` · `approvals` · `ar` · `bi` · `budgeting` · `cashbank` · `costing` · `crm` · `documents` ·
`fixedassets` · `fx` · `gl` · `hr` · `iam` · `manufacturing` · `notifications` · `parties` ·
`products` · `projects` · `purchases` · `reporting` · `routes` · `sales` · `stock` · `tax`

Cross-cutting platform packages: `audit` · `bootstrap` · `bulk` · `common` · `events` · `fiscal` ·
`security`.

Roughly **140 controllers**, **105 migrations** (latest `V104`), **69 ADRs** in
[decisions/](decisions/).

### The three deployment shapes

| Shape | Used by | Database | Composed from |
|---|---|---|---|
| **Host processes** | local development | Postgres in Docker on host port 5434 | `docker-compose.yml` (db service only) |
| **Single container** | QA | Postgres *inside* the same container, supervised | `infra/qa/Dockerfile` + `entrypoint.sh` + `supervisord.conf` |
| **Compose stack** | production and customers | Postgres native on the host, or a sibling container | `infra/prod/docker-compose.hostdb.yml`, or the client bundle's overlays |

---

## 2 · The load-bearing invariants

These are enforced by code and tests and cut across every module. Violating one is a bug, not a
style nit. The enforcing test is named in each row.

| # | Invariant | Enforced by |
|---|---|---|
| 1 | **Layering**: `controller → service → repository → domain`. Controllers never touch repositories | `ModuleBoundaryTest` |
| 2 | **Modules talk only via `..domain.dto..` / `..domain.enums..` and the outbox.** Never import another module's entity or service | `ModuleBoundaryTest` |
| 3 | **Every REST response is wrapped in `ApiResponse<T>`** by a `ResponseBodyAdvice`; controllers return raw `T` | Interceptor tests, web unit suite |
| 4 | **`id` + `uid` duality**: numeric `id` internally, ULID `uid` externally. **URLs address by uid** — `/api/v1/<resource>/uid/{uid}` | Convention + review |
| 5 | **Multi-company / multi-branch tenancy**: transactional tables carry `company_id` + `branch_id`; `RequestContext` is built per request from the JWT plus an optional `X-Branch-Uid` header | `TenantScopingRulesTest`, `TwoOrganisationIsolationIT` |
| 6 | **RBAC by permission code, never role name.** `@PreAuthorize("hasPermission('SALES_INVOICE.POST')")` | `EndpointAuthorizationTest`, `PermissionCodesSeededTest` |
| 7 | **Audit is written by an aspect**, not by calling code, and `audit_log` is append-only | `AuditService` shape; see §9.5 |
| 8 | **Cross-module side effects go through the transactional outbox** — a `domain_event` row in the same transaction, dispatched by a poller. Never Spring's in-memory `ApplicationEventPublisher` for cross-module events | `ModuleBoundaryTest` |
| 9 | **Schema owned by Flyway** (`ddl-auto: validate`); optimistic locking (`@Version`) on transactional aggregates; append-only posting tables | §7 |

If a needed dependency breaks rule 1 or 2, **the design is wrong** — fix it or write an ADR. Do not
relax the rule.

### Package conventions

- Controllers are **flat** under `com.erp.api` — no per-module subpackage. One per resource:
  `api/SalesInvoiceController.java`.
- Each module: `com.erp.modules.<name>/{domain/{entity,dto,enums,event},service,repository}`.
- Services are `interface Xxx` + `class XxxImpl`, `@Transactional` on public methods.
- Cross-cutting infrastructure: `com.erp.platform/{common,security,audit,events,bootstrap,bulk,fiscal}`.

### Coding standards

**Java** — Google Java Style; `final` where reasonable; **records for DTOs** (`*Dto` suffix).
**Lombok `@Getter @Setter` on entities only** — never `@Data` / `@EqualsAndHashCode` / `@ToString`,
which break JPA identity and lazy loading. Hand-write constructors and behaviour methods (invariants,
state transitions); Lombok generates plain accessors only.

**TypeScript** — strict; no `any` without a justification comment. Standalone components only.
Feature screens under `web/src/app/features/admin/<module>/`; shared primitives under
`web/src/app/shared/`; core HTTP/auth wiring under `web/src/app/core/`.

**Accessibility** — WCAG 2.1 AA. axe-core runs in the web unit suite and gates the build on new
serious/critical violations.

**Commits** — Conventional Commits, one logical change per PR.

---

## 3 · Repository layout

```
backend/                Spring Boot API (com.erp) — the modular monolith
  src/main/java/com/erp/
    api/                ~140 controllers, flat
    modules/<name>/     25 business modules
    platform/           audit, bootstrap, bulk, common, events, fiscal, security
  src/main/resources/
    application.yml     base config (all profiles)
    application-dev.yml    hot reload, SQL logging, dev bootstrap
    application-prod.yml   prod profile — JSON-ish logging, probes, scheduler pool
    db/migration/       V1..V104 + R__seed_permissions.sql
  src/test/
    java/com/erp/architecture/   the ArchUnit gates
    resources/archunit_freeze/   the frozen tenant-scoping baseline
    resources/testcontainers.properties

web/                    Angular 21 SPA
  src/app/core/         HTTP interceptors, auth, guards
  src/app/shared/       shared primitives (uid-picker, etc.)
  src/app/features/admin/<module>/
  e2e/                  Playwright specs
  proxy.conf.json       /api -> http://localhost:8081

infra/
  qa/                   single-container QA image + deploy scripts
  prod/                 production compose, Caddyfile, backup/restore, JWT keygen

dist/                   client distribution package (maintainer tooling + the shipped bundle)
  bundle/               what a customer receives
  build-release.sh|ps1  the release builder
  release/              built bundles, one directory + archive per version/arch

e2e/                    Node operator scripts (seeding, smoke) — NOT the Playwright suite
scripts/
  check-migrations.sh          the migration hygiene gate
  rehearse-fresh-install.sh    the empty-database install rehearsal

docs/
  TECHNICAL-RUNBOOK.md         this file
  OPERATIONAL-RUNBOOK.md       the operator's companion
  decisions/                   69 ADRs
  ops/                         deep operational references
  requirements/ · data-model/ · user-manual/ · testing/
```

---

## 4 · Local development

### 4.1 Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 21 (Temurin) | pinned in `backend/pom.xml` |
| Maven | 3.9+ | no wrapper in this repo |
| Node | 22+ | `npm@10.9.4` is the pinned package manager |
| Docker | Desktop 4.x or Engine 23+ with Compose v2 | needed for Postgres, ITs and release builds |
| WSL (Windows) | any bash distro | needed for the fresh-install rehearsal only |

```bash
java -version && mvn -v && node -v && npm -v && docker version --format '{{.Server.Version}}'
```

### 4.2 Start the stack

```bash
# 1. Postgres — host port 5434, db/user/password all "erp" (dev only)
docker compose up -d db
docker exec erp-db pg_isready -U erp -d erp

# 2. API on :8081, management on :9090
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 3. SPA on :4200, /api proxied to :8081
cd web
npm install      # first run only
npm start
```

On an **empty** database the `dev` profile bootstraps organisation + company `C1` + branch `BR-01` +
`rootadmin` / `RootPass12345`, and is idempotent afterwards.

```bash
curl http://localhost:8081/api/v1/health
# {"data":{"status":"UP",...},"errors":[]}
```

> **Never `docker compose down -v`.** The local volume `erp-db-data` is meant to persist across
> restarts exactly like QA does. Stop with `docker compose stop db`.

Optional fully-containerised API (no hot reload):

```bash
docker compose --profile api up --build
```

### 4.3 Hot reload

**Backend — Spring Boot DevTools** watches `target/classes` and restarts the context in 1–2 s.
**New endpoints 404 until a recompile lands there.** Either enable IDE auto-build (IntelliJ:
*Build project automatically* + registry `compiler.automake.allow.when.app.running`; VS Code Java
auto-builds on save), or run a watcher in a second terminal:

```powershell
cd backend
$fsw = New-Object IO.FileSystemWatcher "src\main\java", "*.java"
$fsw.IncludeSubdirectories = $true
while ($true) {
  $fsw.WaitForChanged([IO.WatcherChangeTypes]::All) | Out-Null
  Start-Sleep -Milliseconds 300   # debounce a burst of saves
  mvn -o -q compile
}
```

A `pom.xml` or dependency change needs a real restart — DevTools cannot hot-swap those.

**Frontend** hot-reloads on save. One exception: `angular.json` changes (e.g. a new global
stylesheet) need a dev-server restart.

### 4.4 Local URLs

| URL | What |
|---|---|
| `http://localhost:4200` | the SPA (dev server) |
| `http://localhost:8081/api/v1/health` | API health, enveloped |
| `http://localhost:9090/actuator/health` | actuator health (liveness + readiness) |
| `http://localhost:9090/actuator/prometheus` | Micrometer metrics |
| `http://localhost:8081/swagger-ui/index.html` | Swagger UI |
| `http://localhost:8081/v3/api-docs` | OpenAPI JSON |

### 4.5 Seeding

```bash
# Catalogue, parties and a till — enough to drive POS
API_BASE=http://localhost:8081/api/v1 ROOT_USER=rootadmin ROOT_PASS=RootPass12345 \
  node e2e/full-coverage-drive.js

# Large scale + business-flow assertions (never aborts; writes issues.json)
node e2e/seed-and-flow.js
```

`seed-and-flow.js` bootstraps as root, creates an operator role and 100 branch-assigned users, then
as a **non-root operator** creates 1000 customers / 50 suppliers / 50 products / 20 agents / 10
routes, runs purchase-order → goods-receipt (stock in) and finalised sales (stock out), and asserts
counts, stock maths and invoice-number uniqueness.

**Provisioning a cashier** (required before any POS sale — `BR-SALES-06` requires the logged-in user
to have an INTERNAL agent record, and `BR-PARTY-10` forbids the super-admin being an agent):

1. `POST /users` → `pos_cashier` / `Cashier12345`
2. `POST /user-branches` → assign `BR-01`, `makeDefault: true`
3. `POST /user-roles` → grant the seeded `ORG_ADMIN` role, scoped to the company
4. `POST /agents` → `{ "agentKind": "INTERNAL", "appUserId": <cashier id> }`

### 4.6 Throwaway stacks

**Plain throwaway API + DB** (never touches dev or QA):

```bash
docker run -d --name erp-verify-db \
  -e POSTGRES_USER=erp -e POSTGRES_PASSWORD=erp -e POSTGRES_DB=erp \
  -p 5435:5432 postgres:15

cd web && npm run build && cd ..
rm -rf backend/src/main/resources/static && mkdir -p backend/src/main/resources/static
cp -r web/dist/web/browser/* backend/src/main/resources/static/
cd backend && mvn -q -DskipTests -Dmaven.test.skip=true clean package spring-boot:repackage && cd ..

SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8088 \
  SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5435/erp" \
  SPRING_DATASOURCE_USERNAME=erp SPRING_DATASOURCE_PASSWORD=erp \
  ERP_BOOTSTRAP_ENABLED=true ERP_BOOTSTRAP_ADMIN_PASSWORD=RootPass12345 \
  java -jar backend/target/erp-api-0.0.1-SNAPSHOT.jar

docker rm -f erp-verify-db      # teardown
```

**Two-organisation stack** (tenancy work) — throwaway DB on 5445, API on 8099, tenant B seeded by
SQL because no organisation-create endpoint exists yet. Procedure:
[ops/two-tenant-local-stack.md](ops/two-tenant-local-stack.md). Teardown:
`docker rm -f erp-twoorg-db`.

**Fresh-install rehearsal** — runs the *shipped* installer against a genuinely empty database:

```bash
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh up dist/release/orbixerp-<version>-amd64'
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh verify'
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh reboot'
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh down'
```

Two non-negotiable constraints: **run it in WSL or real Linux, not Git Bash**, and **the install
directory must be native ext4 (`/opt/...`), never `/mnt/c` or `/mnt/d`** — `install.sh` chowns the
JWT private key to uid 10001 and `chown` is a no-op on DrvFs, so the application cannot read its own
signing key for a reason unrelated to the release.

The script hard-codes its namespace (`orbix-b1r`, port 18080, `/opt/orbix-b1r`), refuses names that
collide with real stacks, and contains no `down -v`, `volume prune` or `system prune`.

### 4.7 Browser smoke, locally

```bash
node e2e/static-proxy-server.js     # serves the built SPA and proxies /api (one origin, no CORS)
npm i playwright-core               # in a scratch directory
node e2e/ui-smoke.js                # login, screenshot key screens, report console errors and 5xx
node e2e/qa-ui-drive.js             # 100% typed data entry through the real forms
```

---

## 5 · Build and packaging

### 5.1 Backend artefacts

```bash
cd backend
mvn -q -DskipTests clean package spring-boot:repackage   # target/erp-api-0.0.1-SNAPSHOT.jar
```

The pom version stays `0.0.1-SNAPSHOT` forever — the **release version is a build argument**,
decoupled from it.

### 5.2 SPA into the jar

```bash
cd web && npm run build && cd ..
rm -rf backend/src/main/resources/static && mkdir -p backend/src/main/resources/static
cp -r web/dist/web/browser/* backend/src/main/resources/static/
```

The Angular bundle ends up **inside** the jar at `BOOT-INF/classes/static/`. This matters when
verifying a deployment: grepping a container filesystem for a component finds nothing either way.

### 5.3 Images

| Dockerfile | Builds |
|---|---|
| `backend/Dockerfile` | the API image used by the root compose `api` profile |
| `infra/qa/Dockerfile` | the single-container QA image (API + Postgres + supervisord) |
| `infra/prod/Dockerfile` | the production API image |
| `dist/Dockerfile.build` | compiles the Angular bundle and the Spring Boot jar → `dist/build/app.jar`. Runs **once, natively** |
| `dist/Dockerfile.runtime` | lands that prebuilt jar on a JRE base. Built **once per architecture** |

**Why the client build is split in two.** `app.jar` is JVM bytecode plus static assets — byte-for-byte
identical on amd64 and arm64; only the JRE base differs. The naive alternative
(`buildx --platform linux/arm64` over the whole Dockerfile) runs npm and Maven under QEMU: 30–60
minutes per release, with emulated JVM build failures that are miserable to diagnose. Splitting the
stages leaves only `apk add openssl tzdata` and a `COPY` to emulate — about two minutes.

> If a native dependency is ever added to the backend (anything shipping a `.so`), this assumption
> breaks and the arm64 image must be built properly. Nothing in the current dependency tree does.

On plain Linux Docker Engine (not Docker Desktop), cross-architecture builds need QEMU registered
once per boot:

```bash
docker run --privileged --rm tonistiigi/binfmt --install all
```

### 5.4 Client release bundle

```powershell
.\dist\build-release.ps1 -Version 1.9.4                 # amd64 + arm64
.\dist\build-release.ps1 -Version 1.9.4 -Arch amd64
.\dist\build-release.ps1 -RefreshDocs                   # after editing a guide
```

```bash
bash dist/build-release.sh --version 1.9.4
bash dist/build-release.sh --version 1.9.4 --arch amd64
bash dist/build-release.sh --refresh-docs
```

Output lands in `dist/release/orbixerp-<version>-<arch>/` plus a matching archive.
**`latest` is rejected by both scripts** — a moving tag makes it impossible to know what a customer
is running when they call for support.

The guides in `dist/bundle/docs/` are committed as **both** `.md` and generated `.txt`.
**Never hand-edit a `.txt`** — edit the Markdown, run `--refresh-docs`, commit both. A release
**refuses to build** if a committed `.txt` no longer matches its `.md`. The converter
(`dist/md2txt.js`) runs inside `node:20-alpine` so both build scripts share one implementation and
cutting a release needs no Node installation. The `.txt` files are CRLF and ASCII-only on purpose:
Notepad on older Windows renders an LF-only file as one endless line, and the build fails if any
non-ASCII survives transliteration.

Full handover procedure: [OPERATIONAL-RUNBOOK.md](OPERATIONAL-RUNBOOK.md) §6.

---

## 6 · Tests and CI gates

### 6.1 Command reference

| Command | Runs | Docker | Time |
|---|---|---|---|
| `mvn -B clean test` | **surefire**: unit + ArchUnit gates — **the PR gate** | no | 2–3 min |
| `mvn -B clean verify` | + failsafe `*IT` (Testcontainers Postgres) | yes | 15–30 min |
| `mvn -Dtest=PermissionResolverTest test` | one unit class | no | seconds |
| `mvn -Dit.test=BranchOverrideIT verify` | one IT class | yes | 1–2 min |
| `mvn -o -B spring-boot:run -Dspring-boot.run.profiles=prod` | **release gate** — boot under the prod profile | Postgres | < 1 min |
| `npm ci` | lockfile-exact install (CI) | — | — |
| `npm run build` | production bundle — a CI gate | — | — |
| `npm test -- --watch=false` | Vitest unit + **axe a11y** — the CI gate | — | — |
| `npm test -- --watch=false --include=src/app/features/admin/sales` | narrow by path | — | — |
| `npm test -- --watch=false -t "invoice totals"` | narrow by test name | — | — |
| `npm run e2e` / `npm run e2e:install` | Playwright + axe (opt-in) | — | — |
| `npm audit --omit=dev --audit-level=high` | production-dependency CVE gate | — | — |
| `bash scripts/check-migrations.sh` | migration rule 1 | no | seconds |
| `BASE=origin/develop bash scripts/check-migrations.sh` | rules 1 + 2 | no | seconds |

Inventory: **192 `*Test`** files (surefire), **144 `*IT`** files (failsafe), **181 `*.spec.ts`**
under `web/src`.

> **`--include=` is mandatory when narrowing the web suite by path.**
> `npm test -- --watch=false <path>` parses the bare path as the *project name*, the builder never
> loads, and the run dies on `Unknown argument: watch` having executed **zero tests** — while
> looking like a pass.

### 6.2 The ArchUnit gates

All in `backend/src/test/java/com/erp/architecture/`, all in the **fast** suite, so they gate every
PR.

| Test | Enforces |
|---|---|
| `ModuleBoundaryTest` | layering and module boundaries (invariants 1, 2, 8) |
| `EndpointAuthorizationTest` | every endpoint carries an authorization annotation |
| `PermissionCodesSeededTest` | every code referenced by `@PreAuthorize` exists in the seed — **this is what stops a phantom permission code shipping** |
| `RolePermissionClosureTest` | seeded role bundles resolve to real permissions |
| `DefaultRoleBundlesSeededTest` | the shipped role bundles exist |
| `PlatformPermissionBoundaryTest` | `platform` codes are withheld from tenant admins — **and asserts the platform module is non-empty**, because an exclusion over an empty set passes while proving nothing |
| `TenantScopingRulesTest` | by-id lookups against a frozen baseline of audited exceptions (§6.5) |
| `TenantOnlyProvisionersTest` | provisioning paths stay tenant-scoped |
| `OrganisationWriteMappingsAreAllowlistedTest` | organisation writes go through an allowlist |
| `ApplicationYamlParsesTest` | **every** `application*.yml` parses — added after a duplicate top-level `spring:` key took a live customer down |
| `CodeSequenceSeederCoversAllKindsTest` | the code-sequence seeder covers every kind |
| `MessageHygieneTest` | user-facing error strings leak no internal detail |

### 6.3 The tenancy harnesses (integration)

| Test | Covers |
|---|---|
| `TwoOrganisationIsolationIT` | **the only test that puts two organisations in one database.** Eight probes, proven to fail when the isolation rule is removed |
| `TenancyParityHarnessIT` | parity between tenant-scoped and legacy paths |
| `MigrationKeepDataIT` | migrations apply onto a **populated, pre-existing** database |

> Eight ITs named `*TenantIsolation*` create **one** organisation with two companies. They are
> cross-**company** tests. Their names imply coverage that does not exist.

### 6.4 Testcontainers on Windows

ITs fail with `Connection to localhost:<port> refused` unless Ryuk is disabled and a singleton
container is used per JVM.

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
mvn -B clean verify
docker container prune          # orphans from an interrupted run
```

`backend/src/test/resources/testcontainers.properties` pins `ryuk.disabled=true` and deliberately
leaves `testcontainers.reuse.enable=false` — the pre-stable baseline is edited between runs, so each
run wants a fresh container; `PostgresIntegrationTest`'s singleton-per-JVM pattern already gives
speed *within* a run.

### 6.5 The ArchUnit freeze store

`TenantScopingRulesTest` uses a `FreezingArchRule` with its baseline in
`backend/src/test/resources/archunit_freeze/` and `allowStoreUpdate=false`, so **new** violations
fail the build rather than being grandfathered.

The store records each violation **with its file and line**, so a refactor that merely *moves* an
already-frozen call makes the entry obsolete and CI fails with `StoreUpdateFailedException`.
To regenerate deliberately:

```bash
rm -rf backend/src/test/resources/archunit_freeze/*
mvn -Dfreeze.store.default.allowStoreUpdate=true -Dtest=TenantScopingRulesTest test
# confirm the count is unchanged unless new exceptions were intended, then re-lock and commit
```

> **Local freeze failures are usually lambda-attribution artefacts that do not reproduce in CI.
> Regenerating the store locally to "fix" them BREAKS CI.** Confirm the failure exists in CI first.

### 6.6 CI

`.github/workflows/backend-ci.yml` — triggers on push to `main`, `develop`, `feat/**` and on PRs
into `main`/`develop`:

| Job | Command | Required |
|---|---|---|
| Compile + unit + architecture gates | `mvn -B -ntp clean test` | yes |
| Integration tests (Testcontainers) | `mvn -B -ntp clean verify`, `TESTCONTAINERS_RYUK_DISABLED=true`; uploads `failsafe-reports/` on failure | yes |
| Migration hygiene | `BASE=origin/<base> bash ../scripts/check-migrations.sh` with `fetch-depth: 0` | yes |

`clean` in the verify job is deliberate — an incremental compile once produced a false green.

`.github/workflows/web-ci.yml`:

| Job | Steps | Required |
|---|---|---|
| Build & unit test (incl. a11y) | `npm ci` → `npm run build` → `npm test -- --watch=false` → `npm audit --omit=dev --audit-level=high` | yes |
| Playwright e2e | `npm run e2e` | **no** — `if: false` until a backend test environment exists in CI |

**The axe specs skip when `process.env.CI` is set** — under CI CPU starvation they time out rather
than fail honestly. They still run locally, which is where they are meaningful.

### 6.7 Playwright

```bash
cd web && npm run e2e:install && npm run e2e
```

Starts `ng serve` itself; needs the backend on `:8081` with a `rootadmin` user.
`e2e/auth.setup.ts` logs in once and reuses the session.

- Port 4200 may host a different project locally — use `PW_BASE_URL=http://localhost:4300 npm run e2e`.
- Disable DevTools restart during e2e; a mid-run context restart looks like flake.
- `web/e2e/` is the Playwright suite; **`e2e/` at the repo root is different** — Node operator
  scripts. See [../e2e/README.md](../e2e/README.md).

### 6.8 Pre-merge checklist

```bash
cd backend && mvn -B clean test
cd ../web  && npm test -- --watch=false && npm run build
cd ..      && BASE=origin/develop bash scripts/check-migrations.sh
```

The fast suite is **necessary but not sufficient**. On 2026-08-15 two releases passed every check
above and neither would start. If the change touches the schema, `application-prod.yml`, bootstrap
or the reconciler, the release gates in [OPERATIONAL-RUNBOOK.md](OPERATIONAL-RUNBOOK.md) §4 apply.

---

## 7 · Database and migrations

**The schema is frozen and additive-only (since 2026-06-20) and the database is durable in every
environment.** Nothing is ever wiped or recreated.

### 7.1 The rules

1. **Never edit, rename or delete an applied migration.** Its checksum drifts and the application
   refuses to boot (`validate-on-migrate`) on any populated database — while every fresh dev/CI
   database stays green, so you find out on the customer's box.
2. **Any schema or seed change is a new `V<n>`** at the next free version — or, for convergent
   reference data (permission codes and grants), an edit to `R__seed_permissions.sql`, which upserts
   and self-heals.
3. **A migration cannot be unapplied.** "Rollback" means "restore a dump taken before the deploy".
4. **Owner approval is required before authoring any migration**, new `V<n>` or `R__` seed edit.
   Present the DDL and version, wait for an explicit yes. Standing rule.
5. **Never `flyway clean`**, never `docker compose down -v`, never `docker volume rm` a data volume.
6. **`*.sql` is pinned to LF in `.gitattributes`** — a CRLF/LF flip silently changes a checksum.

### 7.2 Flyway configuration, and why each setting is pinned

| Setting | Value | Why |
|---|---|---|
| `clean-disabled` | `true` | a full data drop; never allowed against a persistent database |
| `validate-on-migrate` | `true` | checksum drift must fail fast at boot, never silently re-run |
| `out-of-order` | `false` | a lower version arriving after a higher one is rejected, not interleaved |
| `baseline-on-migrate` | `false` | a restored `pg_dump` carries `flyway_schema_history`, so a real restore needs no baseline. Auto-baselining a history-less dump would mark V1 applied then re-run V2..HEAD onto an existing schema — mass failure |

### 7.3 Authoring against populated tables

**Expand → backfill → constrain, in separate migrations.** Never single-shot `NOT NULL` / `UNIQUE` /
`CHECK` / `FOREIGN KEY` on a populated table.

```sql
-- V105__add_thing_expand.sql
ALTER TABLE invoices ADD COLUMN thing_id BIGINT;

-- V106__add_thing_backfill.sql   (batched, safe to re-run)
UPDATE invoices SET thing_id = ... WHERE thing_id IS NULL;

-- V107__add_thing_constrain.sql
ALTER TABLE invoices ALTER COLUMN thing_id SET NOT NULL;
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_thing FOREIGN KEY (thing_id) REFERENCES things (id);
```

The multitenancy work is the worked example: `V99` expand, `V101` backfill, `V104` constrain.

**`CREATE INDEX CONCURRENTLY` must be alone in a non-transactional migration** — Flyway wraps a
migration in a transaction and `CONCURRENTLY` cannot run inside one. A plain `CREATE INDEX` takes a
write lock, which is downtime on `gl_entries` and `stock_*`.

**Widen CHECK constraints additively** with DROP-IF-EXISTS / ADD, keeping each new constraint a
superset of the previous one so existing rows stay valid.

**One version per merge.** Two branches both adding `V105__` is a boot failure everywhere.

### 7.4 Two traps that have taken a live customer down

**A `BEFORE UPDATE` trigger can match the row it is validating.** `V102` added a trigger enforcing
that a tenant role may not reuse a global role's code. In a `BEFORE UPDATE` trigger the table still
holds the old row, so a role moving from global to tenant-scoped matched *itself* and was refused —
and `TenancyReconciler` performs exactly that update on every boot. The application crash-looped
twelve times on the customer's installation. **Any trigger that queries its own table must exclude
the row under change**, and must be rehearsed against a restored copy of a real customer database,
not a fresh one. `V102` was also the first trigger in a schema of 101 migrations — triggers are
invisible to the ORM and to every ArchUnit gate.

**Changing a unique index breaks every `ON CONFLICT` that infers it.** `V102` replaced the global
`uq_role_code`, which broke `R__seed_permissions.sql`'s `ON CONFLICT (code)` — a clause that needs a
unique index on `(code)` alone. It fails **late**: `V102` does not change the seed's checksum, so the
deploy looks clean and it detonates on the *next* seed edit, as a boot failure everywhere. Fixed
with `ON CONFLICT (code) WHERE organisation_id IS NULL`.

### 7.5 The permission seed

Permissions and grants are convergent reference data, hence repeatable.

- **A new permission-gated endpoint must add its code to the seed** — `PermissionCodesSeededTest`
  fails otherwise. Gating on a never-seeded code breaks every non-root user and is otherwise
  invisible.
- The `ORG_ADMIN` grant is `INSERT ... ON CONFLICT DO NOTHING`, so it **grants but never revokes** —
  a code that flows in once stays in. Add an exclusion *before* the code exists, not after.
- `module` is a **security discriminator, not a label**: anything marked `platform` is withheld from
  every tenant admin.
- Chart-of-accounts and `gl_configs` seeds are **not** repeatable — they are per-company data a
  customer edits, and re-running would overwrite their work.

### 7.6 The hygiene gate

```bash
bash scripts/check-migrations.sh                     # rule 1
BASE=origin/develop bash scripts/check-migrations.sh # rules 1 + 2
```

**Rule 1** — no two versioned migrations share a version (`1_1` and `1.1` normalise equal, as Flyway
treats them). **Rule 2** — a versioned migration present on the base branch must not be modified,
renamed (detected via `--find-renames`) or deleted. `R__*` files are exempt.

### 7.7 Failed-migration recovery

A migration that fails midway leaves a `success = false` row in `flyway_schema_history` and the
application will not start until it is resolved.

```sql
SELECT installed_rank, version, description, type, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 20;
```

```bash
# TAKE A BACKUP FIRST
pg_dump -Fc -Z9 -h 127.0.0.1 -U erp -d erp -f ~/backups/pre-repair-$(date +%Y%m%d-%H%M%S).dump
```

Then fix **the data or the migration** — a *new* `V<n>` if it has been applied anywhere else — run
`flyway repair` to clear the failed row and realign checksums, and restart to migrate again.

> **`max(version)` on `flyway_schema_history` is a LEXICAL string maximum** — `"9" > "78"`. It lies
> about the schema version. Read *"Current version of schema"* from the boot log.

### 7.8 Adopting a legacy database

`baseline-on-migrate` is `false` on purpose, so this is always deliberate: back up, establish which
migrations the schema already reflects, insert the corresponding `flyway_schema_history` rows (or run
a one-off `flyway baseline` at that version with the application stopped), then start and let it
apply the remainder. A restored `pg_dump` of one of our own databases needs none of this.

### 7.9 Inspecting a database

```bash
docker exec -it erp-db psql -U erp -d erp            # local dev
docker exec -it erpclean2 psql -U erp -d erp         # QA (Postgres is inside the container)
psql -h 127.0.0.1 -U erp -d erp                      # production (native)
docker exec -it <stack>-db psql -U erp -d erp        # customer, docker DB mode
```

```sql
\dt
\d+ sales_invoices
SELECT count(*) FROM flyway_schema_history WHERE success = false;   -- must be 0
SELECT version, description FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
```

**Naming reality check:** tables are plural (`app_users`, `audit_logs`); the audit timestamp column
is `at`. `DATA-MODEL.md`'s prose on naming is stale — trust the shipped SQL.

### 7.10 Append-only tables

`stock_move`, `audit_log` and the GL posting tables are append-only. Corrections are new postings.
Soft-deletable masters use a `status` enum (`ACTIVE` / `INACTIVE` / `ARCHIVED`).

Audit append-only-ness is enforced **by the application**, not by database grants: `AuditService`
declares no update or delete, `AuditLog` exposes getters only, and the implementation persists a
transient entity once — which is what the requirement actually asks ("cannot be edited or deleted
*through the application*"). A database `REVOKE` would change nothing, because the runtime role is
superuser or schema owner in every shipped topology.

### 7.11 Migration checklist

- [ ] Owner approved the DDL **and** the version number
- [ ] Next free `V<n>`, no collision with another open branch
- [ ] Expand → backfill → constrain if it constrains a populated table
- [ ] `CREATE INDEX CONCURRENTLY` alone in a non-transactional migration
- [ ] Any unique-index change checked against every `ON CONFLICT` that infers it
- [ ] Any trigger excludes the row under change, rehearsed on a restored customer database
- [ ] `BASE=origin/develop bash scripts/check-migrations.sh` passes
- [ ] `mvn -B clean verify` passes, including `MigrationKeepDataIT`
- [ ] Release notes state whether the release contains a migration, and whether it is one-way

---

## 8 · Configuration reference

Everything is environment-driven. Nothing below has a secret default.

### 8.1 Core application (`application.yml`)

| Variable | Default | Meaning |
|---|---|---|
| `ERP_DB_URL` | `jdbc:postgresql://localhost:5434/erp` | JDBC URL |
| `ERP_DB_USER` | `erp` | database user |
| `ERP_DB_PASSWORD` | `erp` | database password |
| `ERP_API_PORT` | `8081` | HTTP port for API **and** the SPA |
| `ERP_MANAGEMENT_PORT` | `9090` | actuator/health/Prometheus port — never public |
| `ERP_SWAGGER_ENABLED` | `true` | set `false` in production to remove the docs surface |
| `SPRING_PROFILES_ACTIVE` | *(none)* | `dev`, `qa` or `prod` |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | *(unset)* | pool sizing is deliberately **not** committed — wire it at deploy time to match the database's connection limit |
| `SPRING_TASK_SCHEDULING_POOL_SIZE` | `4` (prod profile) | scheduler threads. Spring's default is **one** thread shared by the outbox dispatcher, the midnight standing-order run, the hourly notification scan and the metrics sweep |

Fixed, non-overridable behaviour worth knowing: multipart upload limits are 8 MB (bulk-import
spreadsheets cap at 2000 rows); `server.error.*` is locked down so no stack trace, exception class or
raw message ever reaches a caller; `spring.mvc.throw-exception-if-no-handler-found` is `true` so
unmatched `/api/**` requests return the standard envelope 404 rather than the Whitelabel page.

### 8.2 JWT

| Variable | Default | Meaning |
|---|---|---|
| `ERP_JWT_SIGNING_MODE` | `dev-in-memory` | `file` in production — loads a stable RS256 key |
| `ERP_JWT_PRIVATE_KEY` | *(empty)* | path **inside the container**, e.g. `/run/secrets/jwt/private.pem` |
| `ERP_JWT_PUBLIC_KEY` | *(empty)* | path inside the container |

Fixed: access-token TTL 15 minutes, refresh-token TTL 7 days, issuer `erp-api`.

### 8.3 Bootstrap (first run on an empty database only)

| Variable | Default | Meaning |
|---|---|---|
| `ERP_BOOTSTRAP_ENABLED` | `false` (`true` under `dev`) | create org + company + branch + root admin when no organisation exists |
| `ERP_BOOTSTRAP_ORG_NAME` | `Default Organisation` | |
| `ERP_BOOTSTRAP_COMPANY_CODE` / `_NAME` | `C1` / `Default Company` | |
| `ERP_BOOTSTRAP_BRANCH_CODE` / `_NAME` | `BR-01` / `Head Office` | |
| `ERP_BOOTSTRAP_ADMIN_USERNAME` | `rootadmin` | |
| `ERP_BOOTSTRAP_ADMIN_DISPLAY_NAME` | `Root Administrator` | |
| `ERP_BOOTSTRAP_ADMIN_PASSWORD` | **none** | **≥ 12 chars, not a placeholder, or the app refuses to start** |
| `ERP_BOOTSTRAP_TIME_ZONE` | `Africa/Dar_es_Salaam` | |
| `ERP_BOOTSTRAP_CURRENCY_BASE` / `_DEFAULT` | `TZS` / `TZS` | |
| `ERP_BOOTSTRAP_CURRENCY_ENABLED` | `TZS,KES,UGX,RWF,BIF,SSP,USD,EUR` | |

Bootstrap runs **only** when `organisations.count() == 0`. No test environment with data ever
executes it — which is precisely why it has broken in production before.

### 8.4 Security

| Setting | Value |
|---|---|
| Lockout | 5 failed attempts, 15-minute lock |
| Password minimum length | 8 |

### 8.5 Production compose (`infra/prod/.env`)

| Variable | Meaning |
|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | database credentials. In host-DB mode `POSTGRES_PASSWORD` **must equal** the password of the native `erp` role |
| `ERP_API_HOST_PORT` | host-side port mapping (container is always 8081) |
| `ERP_PUBLIC_HOST` | hostname or IP Caddy binds its site to. **Required for HTTPS** — a bare `:443` has no name to issue a certificate for and produces `ERR_SSL_PROTOCOL_ERROR` |
| `ERP_JWT_PRIVATE_KEY` / `ERP_JWT_PUBLIC_KEY` | `/run/secrets/jwt/private.pem` and `public.pem` |
| `JAVA_OPTS` | optional JVM tuning |

Passwords ≥ 16 chars, randomly generated (`openssl rand -base64 24`).
**Do not `source` this file** — values contain spaces. Grep the keys you need.

### 8.6 QA container (`infra/qa/qa.env`, on the box)

`ERP_BOOTSTRAP_*` as above, plus `DB_NAME` / `DB_USER` / `DB_PASSWORD`, which the entrypoint uses to
create the in-container role and database on first run.

### 8.7 Client bundle (`.env` in the install directory)

| Variable | Default | Meaning |
|---|---|---|
| `ERP_DB_MODE` | `docker` | `docker` (we provide Postgres) or `host` (the customer's own) — selects the compose overlay |
| `ERP_DB_NAME` / `ERP_DB_USER` / `ERP_DB_PASSWORD` | `erp` / `erp` / — | database credentials |
| `ERP_DB_HOST` / `ERP_DB_PORT` | `host.docker.internal` / `5432` | host-mode target |
| `ERP_DB_URL_PARAMS` | — | extra JDBC parameters |
| `ERP_HTTP_PORT` / `ERP_BIND_ADDR` | `8080` / `0.0.0.0` | where the app listens on the LAN |
| `ERP_TLS_ENABLED` / `ERP_PUBLIC_HOST` / `ERP_HTTPS_PORT` / `ERP_HTTP_REDIRECT_PORT` | `false` / — / `443` / `80` | the optional Caddy TLS overlay |
| `ERP_VERSION` / `ERP_IMAGE` | `<version>` / `orbixerp-api` | the image tag actually run |
| `ERP_STACK_NAME` | `orbixerp` | namespaces the compose project, containers, network and volume |
| `ERP_SWAGGER_ENABLED` | `false` | off for customers |
| `ERP_TIME_ZONE` | `Africa/Dar_es_Salaam` | |
| `ERP_BACKUP_RETAIN_DAYS` | `14` | nightly backup retention |
| `ERP_BACKUP_PREUPDATE_RETAIN_DAYS` | `90` | pre-update safety backups are kept far longer |
| `ERP_BACKUP_SAFETY_RETAIN_DAYS` | `30` | |
| `ERP_BACKUP_KEEP_MIN` / `_KEEP_MAX` | `7` / `90` | floor and ceiling on file count |
| `ERP_BACKUP_DIR_MAX_MB` | `2048` | size ceiling for the backup directory |

**`ERP_DB_PASSWORD` is the dangerous one.** Changing it in `.env` does **not** change the password
inside an existing database volume — it only breaks the application's ability to connect.

**Why overlays, not compose profiles:** a profiled service referenced by another service's
`depends_on` has had changing behaviour across Compose v2 minor releases. Overlays behave identically
on every Compose v2 ever shipped. The overlay's `environment:` block always wins over `.env`
(compose precedence), so switching modes cannot leave a stale datasource behind. The
`extra_hosts: host.docker.internal:host-gateway` line in the host overlay is **load-bearing** — that
hostname exists automatically on Docker Desktop but not on Linux.

### 8.8 Profiles

| Profile | File | Effect |
|---|---|---|
| `dev` | `application-dev.yml` | localhost:5434 datasource, DevTools restart, `com.erp` and `org.hibernate.SQL` at DEBUG, bootstrap enabled with `RootPass12345` |
| `qa` | `infra/qa/application-qa.yml` | Hikari pool 10/2, INFO logging, datasource supplied by the entrypoint |
| `prod` | `application-prod.yml` | probes enabled, `show-details: when-authorized`, actuator exposure `health,info`, root/`com.erp` at INFO and Hibernate at WARN, scheduler pool 4 |

> **Spring profiles do not inherit.** `application-prod.yml` is an explicit, version-controlled
> statement of prod settings — and it is loaded by the `prod` profile **only**. Local runs use `dev`,
> QA uses `qa`. **The customer is the first environment ever to parse it.** That is exactly how a
> duplicate top-level `spring:` key shipped and killed a live installation before reading a single
> property. `ApplicationYamlParsesTest` now guards the parse; booting under `prod` guards the rest.

---

## 9 · Security architecture

### 9.1 Authentication

Login issues an **access JWT** (15 min, RS256) plus a **single-use refresh token** (7 days, stored
SHA-256-hashed, rotated on use with reuse detection). Login lands the user in their **default branch**
(`user_branch.is_default`); a user may be assigned to many branches with exactly one default.

**Dev signs with an ephemeral in-memory RSA key** that rotates on each restart — everyone is logged
out on restart, which is fine for dev. **Production must load a stable RS256 key**
(`ERP_JWT_SIGNING_MODE=file`).

### 9.2 Generating and rotating JWT keys

```bash
bash infra/prod/generate-jwt-keys.sh
# -> infra/prod/jwt-keys/private.pem (chmod 600) + public.pem (644)
```

The script warns and waits 5 seconds if keys already exist. Add to `infra/prod/.env`:

```
ERP_JWT_PRIVATE_KEY=/run/secrets/jwt/private.pem
ERP_JWT_PUBLIC_KEY=/run/secrets/jwt/public.pem
```

`jwt-keys/` is bind-mounted read-only into the API container at `/run/secrets/jwt/`.

**Rotation invalidates every existing token** — all users are logged out at the moment of deploy.
Do it deliberately, not during business hours. **Back the private key up outside the repo**: it can
forge a token for any user on that deployment. Losing it is a full re-issue; leaking it is a
compromise of the whole installation.

### 9.3 Authorization — RBAC by permission

The atomic unit is a **`Permission` with a dot-separated code** (`SALES_INVOICE.POST`,
`USER.MANAGE`). `@PreAuthorize("hasPermission('CODE')")` references **permission codes, never role
names**. Roles are permission bundles. Permissions are seeded via `R__seed_permissions.sql`.

`app_user.is_root` **short-circuits to allowed**, always audited as `ROOT.BYPASS`.

Three consequences that repeatedly cost time:

1. **Gating on a code that was never seeded** breaks every non-root user and is invisible to CI
   unless `PermissionCodesSeededTest` covers it. **Always test as a non-root role.**
2. **An Angular route guard must reference the same code as the backend endpoint** *and* a
   `ScopeGuard` key — otherwise a 403 reads to the user as "I can't open this screen".
3. **Root-only testing proves nothing.** Root bypasses every check, so a missing grant on a real role
   is invisible until a customer hits it.

A grant ceiling (`AuthorityCeiling`, ADR-0059) stops a holder of `ROLE.MANAGE` self-elevating: an
admin cannot confer a permission they do not themselves hold.

### 9.4 Multi-tenancy

Two nested scopes:

- **Organisation** is the tenant. `companies.organisation_id` is `NOT NULL`.
- **Company / branch** is the operating scope inside a tenant.

`RequestContext` (request-scoped) is built by a servlet filter from the JWT plus an optional
**`X-Branch-Uid`** header. The filter verifies the branch is in the caller's `user_branch`
assignments and that the assignment is live, else 403. **Switching branch is context-only** — no DB
write, no re-login. The web `authHeaderInterceptor` attaches `Authorization: Bearer` and
`X-Branch-Uid` on every API call.

IAM admin tables are exempt from the blanket tenant predicate — administration is cross-branch.

**The rule that shipped is `isForeignTenant`, not `!isSameTenant`.** It fires only on a *positive*
mismatch (both organisations known and different). An unknown organisation is a data gap, not
evidence. Applying the equality unconditionally is a **total lockout**: any account whose
`organisation_id` is NULL loses the entire product, denied by a constraint rather than an
authorisation decision. `TenancyScopeEnforcerTest` is proven to fail on the naive form — if those
assertions ever "need fixing", the lockout is being reintroduced.

**Scope from the LOADED entity, never from a caller-supplied parameter.** A confused-deputy probe
once found 28 cross-company leaks, all of that shape.

Organisation equality is checked **inside `ScopeGuard.canActIn`, ahead of the `root ||` disjunct**,
which is what lets ~698 `assertCanActIn` call sites and 89 `@RequestParam Long companyId` controllers
close at one method. It is backed by `CompanyTenantIndex`, a write-once `companyId → organisationId`
cache (the column is `NOT NULL` and has no setter, so it cannot go stale).

### 9.5 Audit

Audit rows are written **by an aspect**, not by calling code, so they cannot be forgotten.
`audit_log` is append-only, enforced by the application (see §7.10).

Two facts worth remembering: `recordIndependent` sources the actor from `RequestContext`, so auditing
before the principal is established writes rows with no actor — audit **after**
`RequestContext.set`. And `LOGIN.SUCCESS` / `LOGIN.FAIL` rows deliberately carry no
`organisation_id`, because the unauthenticated path has no established tenant (owner decision,
2026-08-15; accepted consequence is that login history cannot be filtered per customer).

### 9.6 Error hygiene

`errors[]` in the envelope carries **user-safe strings only** — never exception text. `server.error.*`
is configured to include no stack trace, exception class, message or binding errors.
`GlobalExceptionHandler` and `SecurityErrorResponder` short-circuit before the Whitelabel path for
`/api/**`. `MessageHygieneTest` gates this.

**Do not make an error message a credential oracle.** `NO_AUTHORITY` / `UNKNOWN_PERMISSION` advance
the caller's throttle, but their *message* stays distinct so a manager lacking a permission is not
told their password is wrong.

### 9.7 Secrets

`.env`, `*.key` and `*.pem` are gitignored. No secret ever goes on a command line — it is visible in
`ps` to any other user on the box. Pass secrets via stdin or a mode-600 file that is removed
afterwards.

Periodic review: [ops/security-sweep.md](ops/security-sweep.md).

### 9.8 The honest note on protecting the compiled code

A Docker image is a tar of filesystem layers. Anyone holding one can `docker save`, untar it, extract
`app.jar` and decompile it with CFR or Procyon into near-original Java — Spring Boot compiles with
`-parameters`, so field, method and parameter names survive.

What is actually in place: **the licence and the commercial contract** (the real protection);
**no source maps** (`web/angular.json` does not enable `sourceMap` in the production configuration —
**do not add it**); and **no source in the bundle**, verified by construction from an explicit file
list.

Considered and rejected: **obfuscation** (Spring, JPA and Jackson are reflection- and name-driven;
obfuscating entity, DTO and bean names breaks JSON contracts, `@PreAuthorize` SpEL and Hibernate
mappings in ways that surface only in production) and, still open as an option, a
**licence-key/activation gate** — which is an application change, not a packaging one.

> ⚠ `dist/bundle/LICENSE.txt` is a clearly-marked **template with no legal review**. It must be
> replaced with a lawyer-reviewed document before a first handover.

---

## 10 · API conventions

### 10.1 The envelope

Every REST response is wrapped by a `ResponseBodyAdvice`:

```json
{ "data": { }, "errors": [], "meta": { } }
```

Controllers return raw `T`. On the web side `apiResponseInterceptor`
([../web/src/app/core/api/http.interceptors.ts](../web/src/app/core/api/http.interceptors.ts))
unwraps it, so feature services see `T` directly. **Callers needing the full envelope** — paging
`meta`, for instance — set the `SKIP_UNWRAP` HTTP-context token.

### 10.2 Identity and URLs

Externally exposed entities carry a numeric `id` (BIGINT, the internal FK target) **and** a `uid`
(ULID, `VARCHAR(26)`, external). **URLs address entities by uid**:
`/api/v1/<resource>/uid/{uid}`.

### 10.3 Wire serialization — the trap

| Java type | JSON | TypeScript |
|---|---|---|
| `Long` (ids) | **string** (global Jackson config, so 64-bit ids survive JavaScript) | `string` |
| `BigDecimal` (money, quantities) | **number** | `number` |

Typing a `BigDecimal` field as `string` in TypeScript compiles fine and crashes at runtime. This has
happened. Check the Java type before declaring the TS one.

### 10.4 Headers every call carries

| Header | Set by | Purpose |
|---|---|---|
| `Authorization: Bearer <jwt>` | `authHeaderInterceptor` | authentication |
| `X-Branch-Uid` | `authHeaderInterceptor` | branch context override, verified against assignments |

### 10.5 Shared web primitives

`web/src/app/shared/uid-picker` forwards `id` and `aria-labelledby` to its inner `<select>` — bind
`[id]` and `[ariaLabelledby]`, not the raw attributes. Note that plain `tsc` does **not** type-check
Angular templates, so a wrong binding here compiles and fails only in the browser or in an axe spec.

**Pickers must search server-side.** Preloading N options and filtering in memory hides everything
past N — with a real product catalogue this silently makes most items unreachable. Products need
`[search]`.

---

## 11 · Observability and health

### 11.1 Ports and endpoints

Actuator is on the **management port `9090`**, never on the public `8081` edge.

| Endpoint | Purpose |
|---|---|
| `GET :9090/actuator/health` | overall health, `show-details: when-authorized` |
| `GET :9090/actuator/health/liveness` | is the process alive? |
| `GET :9090/actuator/health/readiness` | ready to serve? includes the DB indicator |
| `GET :9090/actuator/info` | build info |
| `GET :9090/actuator/prometheus` | Micrometer scrape target |
| `GET :8081/api/v1/health` | the application's own enveloped health route |

Exposure is `health,info,prometheus` at base, narrowed to `health,info` under the `prod` profile.

### 11.2 The `(unhealthy)` badge that is not a problem

`infra/prod/Dockerfile`'s `HEALTHCHECK` historically probed
`localhost:8081/actuator/health/readiness`, but actuator listens on **9090**; on 8081 that path falls
through to the SPA resource handler and fails. The container then reports `(unhealthy)` while serving
traffic perfectly — the long-standing false alarm on the production box.

Both `infra/prod` compose files and `dist/Dockerfile.runtime` now probe **9090**, with
`start_period: 180s` to cover Flyway migrating on first boot. **Verify health on 9090, never by the
container badge.**

```bash
docker exec erpclean2 wget -qO- http://127.0.0.1:9090/actuator/health   # QA
wget -qO- http://127.0.0.1:9090/actuator/health                          # prod (host networking)
```

### 11.3 Logs

| Environment | Command |
|---|---|
| Local | the `mvn spring-boot:run` console; `com.erp` and `org.hibernate.SQL` at DEBUG |
| QA | `docker logs -f erpclean2` |
| Production | `docker logs -f erp-prod-api`, `docker logs -f erp-prod-caddy` |
| Customer | `./orbixerp.sh logs` (add `-f` to follow) |

Boot lines worth grepping for after any deploy:

```bash
docker logs <container> 2>&1 | grep -i "Current version of schema"
docker logs <container> 2>&1 | grep -i "Successfully applied\|No migration necessary"
docker logs <container> 2>&1 | grep -i "Started ErpApplication"
```

**MDC is half-wired:** `organisationId` is put into the MDC but `logback-spring.xml` does not render
it. Known follow-up.

---

## 12 · Client applications — POS and HQ

### 12.1 OrbixPOS (Flutter, Windows till)

Located in `pos_app/`. Riverpod `Notifier` state; the token store uses `shared_preferences`
(`flutter_secure_storage` was dropped — its Windows plugin needs the Visual Studio C++ ATL component
and blocks `flutter build windows`). An OS keystore is the production hardening path.

```bash
# Run
flutter run -d windows            # or -d chrome, or an Android device

# Tests
flutter test test/widget_test.dart

# Live end-to-end against a running, seeded backend (skipped without the env var)
POS_LIVE_HOST=http://localhost:8081 POS_LIVE_USER=pos_cashier POS_LIVE_PASS=Cashier12345 \
  flutter test test/live_pos_smoke_test.dart

# Release build -> pos_app/build/windows/x64/runner/Release/
flutter build windows --release
```

The live test drives the real service layer end to end: login → numeric context resolution →
catalogue/units/prices → walk-in customer + agent → till → open session → ring (server-priced,
FINALISED) → idempotent replay (same invoice) → receipt → x-read → close → reconcile.

Ship as `dist/OrbixPOS-<version>-windows.zip`, zipped from the `Release` directory.

**Host configuration** is done in the app: login screen → *Server setup* → set the ERP host and
*Test connection*. `/api/v1` is appended automatically.

**TLS against a self-signed production certificate** (ADR-0061). Production serves a leaf +
intermediate chaining to `CN=Caddy Local Authority`; the root is not sent on the wire. That root is
compiled into the app (`lib/core/api/erp_root_ca.dart`) and added to a shared
`SecurityContext(withTrustedRoots: true)`, so public CAs still work and a future Let's Encrypt
certificate needs no change. `applyErpTls(Dio)` in `lib/core/api/erp_tls.dart` is the **one** place
TLS policy is applied.

- **Multi-server:** drop `certs/*.pem` (or `erp-ca.pem`, or set `POS_ERP_CA_FILE`) next to
  `pos_app.exe` — one file per server, no rebuild. Every client install mints its own Caddy root.
- **If the `erp-prod-caddy-data` volume is ever lost**, Caddy mints a new root and every till breaks.
  Fetch the new root and drop it into `certs/`:
  ```bash
  docker exec erp-prod-caddy cat /data/caddy/pki/authorities/local/root.crt
  ```
- `--dart-define=POS_ALLOW_INSECURE_TLS=true` disables validation entirely. **Bench use only —
  never ship a till with it on.**
- **Diagnostic trap:** Windows `curl` uses Schannel and silently **ignores `--cacert`**. Verify a
  private-CA chain with `openssl s_client -CAfile` instead.

### 12.2 OrbixHQ (Flutter, Android)

```bash
flutter build apk --release --dart-define=HQ_HOST=http://16.170.11.41    # QA
flutter build apk --release --dart-define=HQ_HOST=http://51.21.23.170    # a client
flutter build apk --release --dart-define=HQ_HOST=https://erp.example.com
```

**Pass scheme and host only.** `HqConfig` appends `/api/v1` itself — a host ending in `/api/v1`
yields `/api/v1/api/v1` and every call 401s. Without `HQ_HOST` the build defaults to
`http://localhost:8081`, which is useful only on an emulator.

**Verify the host actually landed.** `String.fromEnvironment` is const-folded into the AOT snapshot,
so a forgotten `--dart-define` produces a perfectly good APK pointed at localhost:

```bash
python -c "import zipfile; b=zipfile.ZipFile('build/app/outputs/flutter-apk/app-release.apk').read('lib/arm64-v8a/libapp.so'); print(b'51.21.23.170' in b)"
```

On a client build, check the *other* hosts are **absent** too — a build pointed at one customer that
still carries another's address is worse than one pointed at localhost.

**Two things that will bite:**

- **INTERNET permission.** Flutter puts `android.permission.INTERNET` in the *debug* manifest only.
  It is declared in `android/app/src/main/AndroidManifest.xml`; if that line is ever lost, the
  release build installs and runs but reaches nothing, and every screen says "Cannot reach the
  server". Verify with `aapt2 dump badging <apk> | grep uses-permission`.
- **Cleartext HTTP.** QA and the default install serve plain HTTP, which Android 9+ blocks;
  `android:usesCleartextTraffic="true"` is set for that reason. Once clients are on HTTPS with a real
  certificate, drop the flag or narrow it with a network-security-config.

**Signing: current builds are debug-signed.** Android warns on install and they must not be treated
as distribution builds. A real release keystore is required before the app goes to a client or a
store.

**Support gesture:** the server address is reached by tapping the footer line *"Protected by your
company's server"* **seven times** (a countdown appears from the fourth tap). The read-only address
is always visible under **Settings → About** — that is where you ask someone to look when you need to
know what a phone is pointed at.

**Check the server before shipping a client build.** The app is only as new as the API behind it: a
screen calling an endpoint the customer's server does not have fails in their hands, not in testing.
This cannot be probed — the API answers 401 for a nonexistent route exactly as it does for one
needing a login — so the evidence is the git dates of the endpoints the build depends on.

---

## 13 · Technical troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| New endpoint 404s after an edit | the app is on a stale `target/classes` | `mvn -o compile`, or enable IDE auto-build |
| Everyone logged out after a restart (dev) | ephemeral in-memory RSA key rotates per JVM start | expected in dev; production uses `ERP_JWT_SIGNING_MODE=file` |
| ITs fail "connection refused" | Testcontainers Ryuk on Windows | `TESTCONTAINERS_RYUK_DISABLED=true`; `docker container prune` for orphans |
| `npm test -- --watch=false <path>` runs 0 tests but looks green | a bare path is parsed as the project name | use `--include=<path>` |
| ArchUnit freeze fails locally but not in CI | lambda-attribution artefact | do **not** regenerate the store — that breaks CI |
| `StoreUpdateFailedException` after a refactor | a frozen violation moved file/line | regenerate deliberately (§6.5) |
| App refuses to boot: checksum mismatch | an applied migration was edited | restore the original file; ship the correction as a new `V<n>` |
| App refuses to boot: duplicate Flyway version | two branches used the same `V<n>` | renumber; `check-migrations.sh` catches it at PR time |
| App won't start, `flyway_schema_history` has `success=false` | a migration failed midway | §7.7 — back up, fix, `flyway repair`, restart |
| Crash-loop right after a release with no migration | a `prod`-profile-only config fault | boot locally under `-Dspring-boot.run.profiles=prod`; check for duplicate YAML keys |
| Reported schema version looks wrong | `max(version)` is a lexical string max | read "Current version of schema" from the boot log |
| Container badge `(unhealthy)` but the app works | healthcheck probing 8081 instead of 9090 | verify on 9090; §11.2 |
| Non-root users get 403 on a new screen | permission code gated but never seeded, or a guard/endpoint mismatch | seed the code; make the Angular guard, the endpoint and the `ScopeGuard` key agree |
| A picker shows nothing past the first N items | preloaded options filtered in memory | give it server-side `[search]` |
| Runtime crash on a numeric field | `BigDecimal` typed as `string` in TypeScript | §10.3 |
| POS: "request was rejected" with no detail | a real HTTP 400 whose `errors` the client parses as objects while the backend sends `List<String>` | read the actual response body; fix the client parse |
| POS cannot reach production over HTTPS | self-signed Caddy root not trusted, or the bare IP was used | §12.1; Caddy binds to `ERP_PUBLIC_HOST`, so use the hostname |
| OrbixHQ APK reaches nothing | missing `INTERNET` permission, or `HQ_HOST` never landed | §12.2 |
| axe specs time out in CI only | CPU starvation | they are skipped when `process.env.CI` is set — run them locally |

---

## 14 · Command index

```bash
# --- verify the facts in this document ---
ls backend/src/main/resources/db/migration | wc -l          # migration count
ls backend/src/main/resources/db/migration | sort -V | tail -1
find backend/src/test -name '*Test.java' | wc -l            # surefire classes
find backend/src/test -name '*IT.java'   | wc -l            # failsafe classes
find web/src -name '*.spec.ts' | wc -l                      # web specs
ls backend/src/main/java/com/erp/api | wc -l                # controllers
ls docs/decisions | wc -l                                   # ADRs

# --- local stack ---
docker compose up -d db
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd web && npm start

# --- tests ---
cd backend && mvn -B clean test
cd backend && TESTCONTAINERS_RYUK_DISABLED=true mvn -B clean verify
cd web && npm test -- --watch=false
cd web && npm run build
BASE=origin/develop bash scripts/check-migrations.sh

# --- release gates (see OPERATIONAL-RUNBOOK.md §4) ---
cd backend && mvn -o -B spring-boot:run -Dspring-boot.run.profiles=prod
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh up dist/release/orbixerp-<v>-amd64'

# --- packaging ---
cd web && npm run build && cd .. && cp -r web/dist/web/browser/* backend/src/main/resources/static/
cd backend && mvn -q -DskipTests clean package spring-boot:repackage
bash dist/build-release.sh --version <x.y.z>

# --- keys ---
bash infra/prod/generate-jwt-keys.sh
```

---

## 15 · Where to go deeper

| Document | Adds |
|---|---|
| [OPERATIONAL-RUNBOOK.md](OPERATIONAL-RUNBOOK.md) | deploying, running, backing up and recovering the estate |
| [../PROJECT-CONVENTIONS.md](../PROJECT-CONVENTIONS.md) | the fixed stack and engineering invariants |
| [../ARCHITECTURE.md](../ARCHITECTURE.md) · [../DATA-MODEL.md](../DATA-MODEL.md) | IAM-detailed foundations; predate most modules — trust shipped code and its ADR for a business module |
| [decisions/](decisions/) | 69 ADRs — the *why* behind most non-obvious choices |
| [ops/migrations-and-seeding.md](ops/migrations-and-seeding.md) | the full migration authoring reference |
| [ops/two-tenant-local-stack.md](ops/two-tenant-local-stack.md) | the two-organisation local stack |
| [ops/fresh-install-rehearsal.md](ops/fresh-install-rehearsal.md) | the empty-database rehearsal and what it found |
| [ops/security-sweep.md](ops/security-sweep.md) · [ops/jwt-keys.md](ops/jwt-keys.md) | security review and key material |
| [../dist/README.md](../dist/README.md) | maintainer rationale for the client distribution package |
| [../e2e/README.md](../e2e/README.md) | the operator e2e harness |
