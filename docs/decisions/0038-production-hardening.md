# 0038 — Production Hardening: Observability, CI, and Prod-Shape Infra

- **Status:** Proposed
- **Date:** 2026-06-13
- **Deciders:** devops-engineer (author), solutions-architect (owner ratification required before any item merges to main)
- **Context source:** Wave-3 production-hardening recon (`docs/decisions/_hardening-recon/recon.md`); `docs/PATH-TO-FULL-ERP.md` Theme 16 (production-hardening 30% done); `ISSUES-REGISTER` #4; prior ADRs 0001–0037 (frozen schema V1–V81, stateless JWT resource server, transactional outbox).

---

## Executive Summary (20 lines)

The codebase is security-mature: no hardcoded secrets, fail-closed bootstrap, BCrypt-12, SHA-256 refresh-token rotation with reuse detection, constant-time unknown-user path, account lockout, 401/403 enveloped without enumeration, and a build-time RBAC gate (`EndpointAuthorizationTest` fails `mvn verify` if any handler under `com.erp.api` lacks `@PreAuthorize`; allowlist = exactly 4 public endpoints). The gaps are operational plumbing in four areas: (1) a literal `// TODO(logging)` in `GlobalExceptionHandler.handleUnexpected` swallows every unexpected stack trace; (2) `JwtRequestContextFilter` resolves user/company/branch per request but pushes nothing into the SLF4J MDC, making no log line traceable to a request or tenant; (3) the entire Java backend — including `EndpointAuthorizationTest`, `ModuleBoundaryTest`, and ~100 Testcontainers ITs — has never run in CI; (4) there is no production-shaped Dockerfile (Angular + API, no in-container Postgres), no prod compose, no `pg_dump` backup script, no Prometheus endpoint, no actuator probe split, and no stable JWT signing-mode story. This ADR delivers 11 hardening items in four parallelizable tranches. All changes are additive; no security gate is weakened; all Java changes gate on `mvn clean verify` (the full 844-test suite + failsafe ITs — the Wave-2 false-green lesson); dev/test behaviour is unchanged via profile-gating. No Flyway migration (V82) is needed. Fenced items requiring owner ops decisions (host, managed Postgres, registry, secrets backend, TLS edge, log/metrics collector) are documented as a runbook checklist only.

**One concrete defect corrected vs the draft:** `application.yml` line 23 sets `server.port: ${ERP_API_PORT:8081}` and the dev compose confirms `ERP_API_PORT=8081`; `backend/Dockerfile` incorrectly `EXPOSE 8080`. Every healthcheck and port mapping must target 8081 (or an explicit `ERP_API_PORT=8080` override in the prod env — one value, applied consistently). This ADR pins the prod container to port 8081 via `ERP_API_PORT=8081` in the prod env and aligns `EXPOSE`, compose port maps, and healthcheck URLs accordingly. This is a build-blocker and is resolved in D-4 and D-6 before any smoke-test.

---

## Context

### Security posture (already hardened — no changes needed)

Confirmed against the live code:

- 12-factor env config throughout `application.yml` — no hardcoded secrets anywhere in `backend/src/main`; every secret is `${ENV_VAR:default}`.
- `BootstrapRunner` is fail-closed: `ERP_BOOTSTRAP_ADMIN_PASSWORD` has no default, refuses to start if blank or a known placeholder, enforces minimum length. `ERP_BOOTSTRAP_ENABLED` defaults to `false`.
- Stateless JWT OAuth2 resource server: BCrypt-12 password hashing, SHA-256 refresh-token rotation with reuse detection, per-user active-check on every request (`JwtRequestContextFilter` line 77), account lockout (5 failed attempts, 15-minute lock), constant-time unknown-user path, CSRF-off (correct for a token API), 401/403 enveloped without enumeration.
- Deny-by-default enforced **at build time**: `EndpointAuthorizationTest` scans every `@RestController` under `com.erp.api` and fails `mvn verify` if any handler lacks `@PreAuthorize`. Allowlist = exactly 4 public endpoints. This gate must not be weakened.
- ScopeGuard tenant isolation enforced at the service layer; root cross-company bypass is audited.

### Operational gaps (what this ADR fixes)

**Observability:** `GlobalExceptionHandler.handleUnexpected` at line 114 (`backend/src/main/java/com/erp/platform/common/api/GlobalExceptionHandler.java`) has a literal `// TODO(logging)` and no SLF4J logger anywhere in the class. Every unexpected 500 — constraint violations, NPEs, the AP bill-match #15 and CRM create #20a failures from Wave-2 — is invisible in logs. `JwtRequestContextFilter.doFilterInternal` resolves `userId`, `username`, root flag, `companyId`, `branchId`, and `ip` into a `RequestContext.Principal` at line 85 but pushes nothing into the SLF4J MDC; no log line is correlated to a request, user, or company. There is no `logback-spring.xml` in the repo; the app uses Spring Boot's plain-text default. No Micrometer or Prometheus dependency exists in `backend/pom.xml`. The actuator exposes only `health,info` (`application.yml` line 29) with no readiness/liveness split. `backend/Dockerfile` has no `HEALTHCHECK` directive.

**CI (the single biggest gap):** `.github/workflows/web-ci.yml` is the only GitHub Actions workflow. It builds and tests the Angular frontend only. The entire Java backend — `EndpointAuthorizationTest`, `ModuleBoundaryTest`, and ~100 Testcontainers integration tests — has never run automatically. Backend regressions, including silent RBAC gate regressions, can silently merge to `develop` and `main`.

**Prod-shape infra:** `backend/Dockerfile` is API-only, no Angular build stage, `EXPOSE 8080` (wrong — the app binds `${ERP_API_PORT:8081}`), no `HEALTHCHECK`, no memory tuning. `infra/qa/Dockerfile` is intentionally wrong-for-prod (coupled Postgres + supervisord). The dev `docker-compose.yml` is close to correct topology but uses hardcoded `erp/erp` credentials and sets `ERP_API_PORT=8081` — confirming the real port. No `infra/prod/` directory exists. No `pg_dump` backup script exists.

**JWT signing mode:** `application.yml` lines 38–41 document the ephemeral in-memory RSA key as a hard prod gating item. `infra/qa/Dockerfile` line 71 sets `JWT_SIGNING_MODE=dev-in-memory` — but the app reads `ERP_JWT_SIGNING_MODE`. The env var name is silently wrong; the QA container defaults to in-memory regardless (the app's own default), but an operator trying to flip prod mode via this var would find it dead. No automated guard exists against future secret commits beyond `.gitignore`.

**OpenAPI and dependency hygiene:** No `springdoc-openapi` dependency. No `org.owasp:dependency-check-maven` plugin. No `.github/dependabot.yml`. Spring Boot `3.3.5` is a few patch releases behind the current 3.3.x line; no headline-critical CVE is confirmed, but a sweep is overdue for a financial ERP.

**HTTP security headers (deferred — explicit disposition):** The recon flags the absence of explicit `HSTS`/`X-Content-Type-Options`/`X-Frame-Options`/`Referrer-Policy`/`Content-Security-Policy` headers as an `[M]` hardening item. Spring Security servlet defaults supply `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY` automatically, but there is no explicit `headers()` hardening block and no CSP for the served SPA. This item is **deliberately deferred** to a follow-up ADR: a correct CSP for the baked-in Angular bundle requires a nonce or hash strategy that must be designed with the frontend team, and HSTS is only meaningful once TLS termination at the edge (a fenced ops decision, D-Fenced) is resolved. The deferral is noted here so it is not lost.

**Constraint:** V1–V81 Flyway migrations are frozen. Production hardening requires no schema change — no V82 is created. All Java changes must pass `mvn clean verify` (the full gate; incremental compile is not sufficient — Wave-2 lesson).

---

## Decision

Build 11 hardening items in four tranches. All changes are additive. No existing security gate is weakened. Profile-gating ensures dev/test behaviour is unchanged.

---

## D-1 — Exception Logging in GlobalExceptionHandler [S, PROD-CRITICAL]

**Files touched:** `backend/src/main/java/com/erp/platform/common/api/GlobalExceptionHandler.java`

**Change:** Add `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);` to the class. In `handleUnexpected(Exception ex)`, replace the `// TODO(logging)` comment with:

```java
log.error("Unhandled exception [{}]", ex.getClass().getSimpleName(), ex);
```

The `ex` as the last argument causes SLF4J to append the full stack trace. Do NOT pass `ex.getMessage()` to the client — the envelope already returns `"An unexpected error occurred."` and must stay that way. Do not add log calls to `SecurityErrorResponder` — filter-level 401/403 denials are normal flow, not errors.

This is the fix for ISSUES-REGISTER #4. Without it, any constraint-violation 500, any NPE, and any future unexpected exception is entirely invisible in operational logs.

**Gate:** `mvn clean verify` green.

---

## D-2 — Correlation ID + MDC Enrichment in JwtRequestContextFilter [M, PROD-CRITICAL]

**Files touched:** `backend/src/main/java/com/erp/platform/security/JwtRequestContextFilter.java`

**Change:** In `doFilterInternal`, immediately after `RequestContext.set(principal)` at line 85, add:

```java
String requestId = Optional.ofNullable(request.getHeader("X-Request-Id"))
        .filter(s -> !s.isBlank())
        .orElseGet(() -> java.util.UUID.randomUUID().toString());
MDC.put("requestId", requestId);
MDC.put("userId",    String.valueOf(principal.userId()));
MDC.put("username",  principal.username());
MDC.put("companyId", String.valueOf(principal.companyId()));
MDC.put("branchId",  String.valueOf(principal.branchId()));
response.setHeader("X-Request-Id", requestId);
```

For the pre-auth path (unauthenticated requests where `auth.getPrincipal()` is not a `Jwt`), generate and set only `requestId` before `chain.doFilter`. In the `finally` block, add `MDC.clear()` alongside `RequestContext.clear()` at line 89. The `finally` already exists — MDC cleanup goes there to guarantee no cross-request leakage.

**Accept/echo pattern:** A reverse proxy or client can supply `X-Request-Id`; it appears in every log line for that request and is echoed in the response header, enabling incident triage across a distributed log stream.

**Known limitation:** `@Async` outbox dispatcher and `@Scheduled` poller run on different threads from the request thread. MDC context (requestId/companyId) does NOT propagate to async event handlers — log lines from GL/stock handlers triggered by an outbox event will have empty MDC. This is acceptable at current scale; the MDC `requestId` is the pragmatic substitute for distributed tracing (deferred, see D-Fenced). This limitation is by design, not a regression.

**Gate:** `mvn clean verify` green. Verify no IT asserts on MDC-empty log output.

---

## D-3 — Structured Logging: logback-spring.xml + application-prod.yml [M]

**Files (new):** `backend/src/main/resources/logback-spring.xml`, `backend/src/main/resources/application-prod.yml`

**pom.xml addition:**

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
    <optional>true</optional>
</dependency>
```

`<optional>true</optional>` makes the dependency non-transitive. It is NOT excluded from this module's runtime classpath — the fat jar includes it and the `prod` profile `LogstashEncoder` will resolve. The actual risk is that the `prod` logback block is never exercised by `mvn verify` (tests run without the `prod` profile). The acceptance gate for this item therefore requires a real `SPRING_PROFILES_ACTIVE=prod` boot of the assembled jar — not just `mvn verify`.

**logback-spring.xml** uses `<springProfile>` blocks:

- Default / dev / test (no profile or `!prod`): standard `PatternLayoutEncoder` to stdout. Pattern includes MDC fields: `[%X{requestId:-}] [uid:%X{userId:-}] [co:%X{companyId:-}] [br:%X{branchId:-}]`. This preserves current dev behaviour while adding MDC context to log lines.
- `prod` profile: `LogstashEncoder` writing JSON to stdout. Each log event becomes a JSON object with `timestamp`, `level`, `logger`, `message`, and all MDC fields as first-class keys. JSON stdout is 12-factor compliant and ingested natively by every log aggregation backend (ELK, Datadog, CloudWatch Logs). The aggregation backend itself is a live-ops decision, fenced in D-Fenced.

The default/test branch MUST be explicit (`<springProfile name="!prod">` or a root appender that is NOT inside a prod-only block) so that the ~100 Testcontainers ITs (which boot a full Spring context with no active profile) do not encounter a logback config with no matching appender and fail context load. Verify under `mvn clean verify` that the IT suite still passes.

**application-prod.yml** (Spring profile file, `spring.config.activate.on-profile: prod`):

```yaml
spring:
  config:
    activate:
      on-profile: prod

logging:
  level:
    root: INFO
    com.erp: INFO
    org.hibernate.SQL: WARN
    org.hibernate.type.descriptor.sql: WARN
```

This pins log levels for prod regardless of what the dev profile sets. The Hikari pool sizing block is intentionally **omitted** from this file — a placeholder pool size in a committed prod profile is a footgun (a `maximumPoolSize: 10` committed here becomes the default for every prod deploy regardless of the managed-Postgres connection limit). Pool sizing is documented in `docs/ops/deploy-prod.md` as a formula (`maximumPoolSize = floor(available_connections / instance_count) - 2`) and is wired via `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` at deploy time.

The "prevents dev from leaking into prod" rationale sometimes cited for profile files is technically imprecise — Spring profiles do not inherit, so dev settings do not carry over to prod. The correct rationale is: this file explicitly pins INFO/WARN levels so prod log verbosity is version-controlled and auditable, and so that an operator who activates the `prod` profile without setting any log-level env vars gets the correct production defaults.

**Gate:** `mvn clean verify` green (tests, no active profile → default logback branch). Additionally: `mvn clean package -DskipTests && java -jar -DSPRING_PROFILES_ACTIVE=prod target/erp-api-*.jar` starts and logs JSON to stdout before the test harness is satisfied.

---

## D-4 — Actuator Readiness/Liveness Split + Container HEALTHCHECK [S]

**Files touched:** `backend/src/main/resources/application.yml` (management block), `backend/Dockerfile` (EXPOSE + HEALTHCHECK)

**Port reconciliation (build-blocker, resolved here):** `application.yml` line 23: `server.port: ${ERP_API_PORT:8081}`. The dev compose confirms `ERP_API_PORT=8081` and maps `8081:8081`. `backend/Dockerfile` currently `EXPOSE 8080` — this is wrong. The fix:

- Change `EXPOSE 8080` → `EXPOSE 8081` in `backend/Dockerfile`.
- All healthcheck URLs target `localhost:8081`.
- The prod compose (D-6) explicitly sets `ERP_API_PORT=8081` in the `api` service env and maps `ports: "${ERP_API_HOST_PORT:-8081}:8081"`.

One consistent port: **8081 everywhere**. Any future change to the default port requires updating `application.yml`, both Dockerfiles, both compose files, and all healthcheck URLs in the same commit.

**application.yml management block** (replaces the existing `management:` block):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true
      group:
        readiness:
          include: db, diskSpace
        liveness:
          include: ping
```

Note: `prometheus` is **NOT** added to the main-port exposure list here. The prometheus endpoint is wired in D-5 via a separate management port to avoid leaving it unauthenticated behind `SecurityConfig`'s existing `permitAll("/actuator/**")`. See D-5 for the exact wiring.

**backend/Dockerfile** after EXPOSE:

```dockerfile
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1
```

`start-period=90s` accounts for Flyway migration time across V1–V81. Use liveness (not readiness) for the container-level healthcheck: a DB-unreachable event should not cause Docker to mark the container unhealthy and restart it — the `service_healthy` gate in the compose `depends_on` block is the orchestrator's concern, not the container-restart policy.

`wget` is present in `eclipse-temurin:21-jre-alpine`.

**Gate:** `mvn clean verify` green. `docker build -f backend/Dockerfile -t erpclean2:api-only . && docker run --rm -e ERP_API_PORT=8081 erpclean2:api-only` — observe the HEALTHCHECK status with `docker inspect` after `start-period` elapses.

---

## D-5 — Micrometer + Prometheus Metrics [M]

**Files touched:** `backend/pom.xml`, `backend/src/main/resources/application.yml` (management block addition), `backend/src/main/java/com/erp/platform/events/DomainEventDispatcher.java`

**pom.xml additions:**

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Spring Boot auto-configures `PrometheusMeterRegistry` when this dependency is present.

**Prometheus endpoint on a separate management port.** The existing `SecurityConfig` does `requestMatchers("/actuator/**").permitAll()`. Adding `prometheus` to the main-port exposure list would expose memory layout, bean names, SQL-ish metric tags, and tenant counts unauthenticated over the public port. The correct fix — without touching the security filter chain — is to move the prometheus endpoint to a separate management port that is only reachable inside the container network:

Add to `application.yml`:

```yaml
management:
  server:
    port: 9090
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

Spring Boot starts a separate Tomcat connector on port 9090 for management endpoints when `management.server.port` is set. The main port (8081) exposure stays at `health,info` only (D-4). The compose file (D-6) exposes port 9090 on the internal Docker network only — not bound to the host — so Prometheus scraping requires a sidecar or an internal network route, which is a live-ops topology decision fenced in D-Fenced. The container `HEALTHCHECK` in D-4 targets port 8081 (the main port), not 9090, which is correct — liveness is a main-port concern.

**Custom metrics (bounded scope):** The two most operationally critical signals that would have been invisible during Wave-2 incidents:

1. **Outbox dispatch counter and FAILED-event counter.** `DomainEventDispatcher` should record a `Counter` for each dispatched event and a `Counter` for FAILED events (poison-event park-and-move path from ADR-0009). Tag both with `event_type` — event types are a bounded enum (`DomainEventType`), guaranteeing bounded metric cardinality. A poison-event loop (#20d in the Wave-2 incident set) would be immediately visible as a sustained FAILED counter spike. The dispatch Timer is also included (measures the latency of a single dispatch cycle).

2. **Hikari pool saturation.** Hikari exposes pool metrics via Micrometer automatically when `spring.datasource.hikari.metrics-registry` is set; with `micrometer-registry-prometheus` on the classpath this is auto-wired. Key metric: `hikaricp.connections.active` vs `hikaricp.connections.max`.

Standard Spring Boot auto-configured metrics (JVM memory, GC, HTTP request timing with status and URI tags, Tomcat threads) are included automatically at no additional code cost.

**Out of scope this wave:** The `InventoryValuationServiceImpl.java` valuation-recompute timer. That file is currently modified on `develop` (confirmed via `git status`) and the inventory valuation build is in flight. Adding a metric there risks a merge conflict with the in-flight build. Deferred until the inventory valuation module is merged.

**Suite risk:** Adding `micrometer-registry-prometheus` auto-configures a `PrometheusMeterRegistry` in the test Spring context too. Confirm no IT asserts on the absence of `/actuator/prometheus` or on a specific actuator exposure list before merging.

**Gate:** `mvn clean verify` green. Verify `/actuator/prometheus` (on port 9090) returns text in Prometheus exposition format when the prod image is started with `SPRING_PROFILES_ACTIVE=prod`.

---

## D-6 — Prod Dockerfile + Prod docker-compose [M, PROD-CRITICAL]

**Files (new/changed):** `infra/prod/Dockerfile`, `infra/prod/docker-compose.yml` (reference single-node topology — see scope note), `infra/prod/.env.example`, `infra/prod/scripts/generate-jwt-keys.sh` (see D-10), `infra/qa/Dockerfile` (dead-env-var fix)

**Scope clarification on the prod compose:** The host/orchestrator choice (single-EC2 + compose, ECS/Fargate, K8s, PaaS) is an explicit owner ops decision fenced in D-Fenced. The `infra/prod/docker-compose.yml` committed here is a **reference single-node topology** — it is clearly labelled as such in its header comment and in `docs/ops/deploy-prod.md`. If the owner chooses K8s or ECS, this file is not the deploy artifact; the Dockerfile (below) is topology-agnostic and is needed by every target. The compose file and its env-wiring conventions do NOT pre-decide the secrets backend — the `/run/secrets` mount convention of Docker Swarm is explicitly avoided; instead the compose uses a gitignored `.env` file and a documented bind-mount override (`docker-compose.prod-keys.yml`, gitignored) for the JWT key files.

**infra/prod/Dockerfile** — three-stage multi-stage build:

- **Stage 1 (web-build):** `node:20-alpine`. Copies `web/package.json` and `web/package-lock.json`. Uses `npm ci --no-audit --no-fund` (not `npm install` — requires the lockfile to be present and committed; the QA Dockerfile uses `npm install` because the lockfile was absent; prod enforces the lockfile). Copies `web/`, runs `npm run build`. Output: `/web/dist/web/browser/`.
- **Stage 2 (api-build):** `maven:3.9-eclipse-temurin-21`. Caches `pom.xml` → `dependency:go-offline`. Copies `backend/src/`. Copies Angular bundle from Stage 1 into `src/main/resources/static/` (same pattern as `infra/qa/Dockerfile` Stage 2 — `SpaWebConfig.java` serves it from `classpath:/static/`). Runs `mvn -B clean package -DskipTests`.
- **Stage 3 (runtime):** `eclipse-temurin:21-jre-alpine` (matches `backend/Dockerfile`). Sets memory flags via `JAVA_OPTS`: `-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom`. Sets `ERP_API_PORT=8081`. `EXPOSE 8081`. `HEALTHCHECK` from D-4 targeting `localhost:8081`. `ENTRYPOINT ["java", "-jar", "app.jar"]` — no supervisord, no in-container Postgres (QA-only shape). No `SPRING_BOOT_DEVTOOLS`, no `ERP_BOOTSTRAP_ENABLED=true`, no `JWT_SIGNING_MODE` in any form.

**infra/prod/docker-compose.yml** (reference single-node topology, clearly labelled):

```yaml
# Reference single-node topology. If the owner chooses K8s / ECS / PaaS, this file
# is documentation only — use the prod Dockerfile with the appropriate orchestrator manifests.
# All secrets come from a gitignored .env file (see .env.example) or a bind-mount override.

services:
  db:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: ${ERP_DB_NAME}
      POSTGRES_USER: ${ERP_DB_USER}
      POSTGRES_PASSWORD: ${ERP_DB_PASSWORD}
    volumes:
      - erp-prod-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${ERP_DB_USER} -d ${ERP_DB_NAME}"]
      interval: 10s
      timeout: 5s
      retries: 10
    restart: unless-stopped

  api:
    image: ${ERP_API_IMAGE:-erpclean2:prod}
    depends_on:
      db:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: prod
      ERP_API_PORT: "8081"
      ERP_DB_URL: jdbc:postgresql://db:5432/${ERP_DB_NAME}
      ERP_DB_USER: ${ERP_DB_USER}
      ERP_DB_PASSWORD: ${ERP_DB_PASSWORD}
      ERP_JWT_SIGNING_MODE: file
      ERP_JWT_PRIVATE_KEY: ${ERP_JWT_PRIVATE_KEY_PATH}
      ERP_JWT_PUBLIC_KEY: ${ERP_JWT_PUBLIC_KEY_PATH}
      ERP_BOOTSTRAP_ENABLED: ${ERP_BOOTSTRAP_ENABLED:-false}
      ERP_BOOTSTRAP_ADMIN_PASSWORD: ${ERP_BOOTSTRAP_ADMIN_PASSWORD:-}
    ports:
      - "${ERP_API_HOST_PORT:-8081}:8081"
    volumes:
      - ${ERP_JWT_KEYS_DIR:-./secrets}:/run/keys:ro
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8081/actuator/health/readiness || exit 1"]
      interval: 30s
      timeout: 10s
      start_period: 120s
      retries: 3

volumes:
  erp-prod-data:
```

The JWT key paths are set via `ERP_JWT_PRIVATE_KEY_PATH` and `ERP_JWT_PUBLIC_KEY_PATH` in `.env` (pointing to files under `ERP_JWT_KEYS_DIR` which is bind-mounted into the container). The key files never enter the image. The `ERP_API_IMAGE` variable allows a CI-built, registry-tagged image to be specified without editing the compose file.

**One-shot bootstrap procedure (documented here and in `docs/ops/deploy-prod.md`):** The `ERP_BOOTSTRAP_ENABLED: false` default is correct for normal restarts — the app starts but performs no bootstrap check. For the **first deploy on a fresh DB only**, the operator sets `ERP_BOOTSTRAP_ENABLED=true` and `ERP_BOOTSTRAP_ADMIN_PASSWORD=<strong-password>` in `.env`, starts the stack, waits for the API healthcheck to pass (confirming bootstrap ran), then **immediately removes or unsets** `ERP_BOOTSTRAP_ENABLED` and `ERP_BOOTSTRAP_ADMIN_PASSWORD` from `.env` and restarts the API container (`docker compose -f infra/prod/docker-compose.yml restart api`). The bootstrap is idempotent (fails closed if org already exists), so a subsequent restart with `ERP_BOOTSTRAP_ENABLED=false` is safe. **Never leave `ERP_BOOTSTRAP_ENABLED=true` as a standing env var** — it would re-attempt bootstrap on every restart and expose the admin password to `docker inspect`. The `.env.example` comments this procedure.

**infra/qa/Dockerfile dead-env-var fix:** Line 71 sets `JWT_SIGNING_MODE=dev-in-memory`. The app reads `ERP_JWT_SIGNING_MODE`. Change to `ERP_JWT_SIGNING_MODE=dev-in-memory`. Non-breaking: the app's own default is in-memory when the var is absent, so QA behaviour is unchanged. This makes the Dockerfile's intent match reality and prevents an operator from believing they flipped the signing mode via the old name.

**Smoke-test gate:** `docker build -f infra/prod/Dockerfile -t erpclean2:prod .` completes without error. `docker run --rm -e SPRING_PROFILES_ACTIVE=prod -e ERP_API_PORT=8081 -e ERP_DB_URL=... erpclean2:prod` starts, logs JSON to stdout, and `/actuator/health/liveness` returns 200. The SPA index loads at `http://localhost:8081/`. This must be verified before declaring D-6 done.

---

## D-7 — pg_dump Backup/Restore Script [M, PROD-CRITICAL]

**Files (new):** `infra/prod/scripts/backup.sh`, `infra/prod/scripts/restore.sh`, `docs/ops/backup-restore.md`

**backup.sh:** Wraps `pg_dump` with connection parameters sourced from environment variables (`ERP_DB_HOST`, `ERP_DB_PORT`, `ERP_DB_NAME`, `ERP_DB_USER`, `PGPASSWORD`). Writes a timestamped compressed dump (`erp_$(date +%Y%m%d_%H%M%S).dump` in `pg_custom` format) to `BACKUP_DIR`. Prunes dumps older than `BACKUP_RETAIN_DAYS` (default 30). Sets `set -euo pipefail`. Exits non-zero on failure so a cron monitoring tool can detect backup failures.

Example cron entry (documented in `docs/ops/backup-restore.md`):
```
0 2 * * * BACKUP_DIR=/opt/erpclean2/backups /opt/erpclean2/scripts/backup.sh >> /var/log/erpclean2-backup.log 2>&1
```

**restore.sh:** Wraps `pg_restore` for a named dump file. Requires explicit `--confirm yes` flag to guard against accidental overwrites. Uses `--clean --if-exists` for idempotent restore. Documents the `pg_restore -Fc` format requirement.

**Retention note:** At QA scale a daily cron on the EC2 box with 30-day retention is sufficient. If the owner chooses a managed Postgres (RDS, Cloud SQL, Neon), rely on the managed backup facility instead and treat this script as the fallback for self-hosted Postgres only.

---

## D-8 — Backend CI GitHub Actions Workflow [M, PROD-CRITICAL]

**Files (new):** `.github/workflows/backend-ci.yml`

This is the single highest-ROI item in the wave. The 844-test suite has never run in CI.

**Trigger:**

```yaml
on:
  push:
    branches: [main, develop, 'feat/**']
  pull_request:
    branches: [main, develop]
```

**Job 1 — `fast-check` (every push, every PR, BLOCKING):**

`mvn -B clean test` — compiles + surefire unit tests + ArchUnit tests (`EndpointAuthorizationTest`, `ModuleBoundaryTest`). No Docker, no Testcontainers. Fast. Runs on every push so RBAC gate regressions are caught immediately, not at the end of a slow IT job.

**Job 2 — `integration-test` (PRs to develop/main + pushes to develop/main only, INITIALLY NON-REQUIRED):**

`mvn -B clean verify` — the full gate including failsafe ITs. Runs on `ubuntu-latest`. Docker is available on GitHub-hosted Linux runners without any special setup. Testcontainers pulls `postgres:15-alpine`. The Ryuk-disabled singleton-container pattern in `PostgresIntegrationTest.java` / `testcontainers.properties` works correctly on Linux CI runners — Ryuk disablement is harmless on Linux (no Docker Desktop Ryuk port-mapping issue). The `erp.outbox.scheduling-enabled=false` override in `PostgresIntegrationTest.datasourceProps` prevents the `@Scheduled` outbox poller from racing IT assertions.

**Critical:** `integration-test` MUST be initially added as a **non-required status check** (observe-only) for the first 5+ runs. Do not make it a required PR gate until it has demonstrated stability in the CI environment. A CI-environment-specific flake on day one would wall off `develop`. Promote it to a required check after observing stability.

`needs: fast-check` — the IT job only starts if compilation and unit tests passed; no wasted Docker pulls on a compile failure.

**Workflow sketch:**

```yaml
name: Backend CI

on:
  push:
    branches: [main, develop, 'feat/**']
  pull_request:
    branches: [main, develop]

defaults:
  run:
    working-directory: backend

jobs:
  fast-check:
    name: Compile + unit tests + ArchUnit
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: maven
      - run: mvn -B clean test

  integration-test:
    name: Full verify (Testcontainers ITs)
    runs-on: ubuntu-latest
    # Only on PR or push to integration branches; not every feat push
    if: github.event_name == 'pull_request' || contains(fromJson('["develop","main"]'), github.ref_name)
    needs: fast-check
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: maven
      - run: mvn -B clean verify
```

No CI-specific environment variables are needed for the test phase — `@DynamicPropertySource` in `PostgresIntegrationTest` overrides the datasource, `MailStubConfig` stubs `JavaMailSender`, and bootstrap is disabled by the test properties.

**Gate:** The `fast-check` job self-validates on merge (it is a new file; the first push to `develop` that includes it is the proof). The `integration-test` job is promoted to required only after 5+ stable runs.

---

## D-9 — springdoc-openapi / Swagger UI [S/M]

**Files touched:** `backend/pom.xml`, `backend/src/main/resources/application.yml`, `backend/src/main/java/com/erp/platform/security/config/SecurityConfig.java`

**pom.xml:**

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>
```

Spring Boot 3.3.5 is compatible with springdoc-openapi 2.x.

**application.yml** gains a `springdoc` block:

```yaml
springdoc:
  swagger-ui:
    enabled: ${ERP_SWAGGER_ENABLED:true}
  api-docs:
    enabled: ${ERP_SWAGGER_ENABLED:true}
```

`infra/prod/.env.example` sets `ERP_SWAGGER_ENABLED=false` so prod deployments have the docs surface disabled by default — a prod deployment must not expose the full API contract unauthenticated via the existing `GET /** permitAll`.

**SecurityConfig.java:** Add `requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()` **after** the `/api/**` matchers and **before** the `GET /**` catch-all. Cosmetic but clarifying; the `GET /**` SPA permitAll already covers these paths. `authorizeHttpRequests` is first-match-wins; placement after `/api/**` ensures no interference.

**EndpointAuthorizationTest** scans `com.erp.api` only. Springdoc's `OpenApiWebMvcResource` lives in `org.springdoc` — outside the scan boundary. No risk to the build-time RBAC gate.

**SpaWebConfig interaction:** `SpaWebConfig`'s `PathResourceResolver` handles `/**` but excludes `api/` and `actuator/`. Springdoc's `@RequestMapping`-based handlers are registered in the dispatcher and evaluate before the resource handler, so `/v3/api-docs` returns JSON and not `index.html`. Verify this under `mvn clean verify` + a local `docker run` smoke-test: GET `/v3/api-docs` must return a JSON spec, not the SPA index.

**No OpenAPI annotations** are added to controllers in this wave — that is backend-engineer territory. The generated spec from existing handler signatures and `@Valid` DTOs is sufficient for DX tooling.

**Gate:** `mvn clean verify` green. `EndpointAuthorizationTest` still passes. GET `/v3/api-docs` returns a JSON spec. GET `/v3/api-docs` with `ERP_SWAGGER_ENABLED=false` returns 404.

---

## D-10 — JWT Signing Mode: File-Mode Wiring + Key Generation [S, PROD-CRITICAL]

**Files (new):** `infra/prod/scripts/generate-jwt-keys.sh`, `docs/ops/jwt-keys.md`

**Why this is PROD-CRITICAL:** Every restart of the API container in `dev-in-memory` mode rotates the RSA keypair, invalidating all active refresh tokens and logging out every user. For a rolling deploy even on a single instance this is a session-disrupting event. For multi-instance scaling it is a hard blocker — instances generate different keys and cannot verify each other's tokens.

**generate-jwt-keys.sh:**

```bash
#!/usr/bin/env bash
set -euo pipefail
# Generates an RS256 2048-bit keypair in PKCS8/X.509 PEM format.
# Output: jwt_private_key.pem (PKCS8 private), jwt_public_key.pem (X.509 public).
# These files are gitignored (*.pem rule). Never commit them.
openssl genrsa -out _jwt_private.pem 2048
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in _jwt_private.pem -out jwt_private_key.pem
openssl rsa -in _jwt_private.pem -pubout -out jwt_public_key.pem
rm _jwt_private.pem
echo "Keys written: jwt_private_key.pem (keep secret), jwt_public_key.pem"
```

The PKCS8 format for the private key and X.509 format for the public key match what `RsaKeyProvider.loadPrivateKey` / `loadPublicKey` expect. The output files are gitignored by the existing `*.pem` rule.

**Wiring in prod:** The prod compose (D-6) sets `ERP_JWT_SIGNING_MODE: file`, `ERP_JWT_PRIVATE_KEY: ${ERP_JWT_PRIVATE_KEY_PATH}`, and `ERP_JWT_PUBLIC_KEY: ${ERP_JWT_PUBLIC_KEY_PATH}`. The operator runs `generate-jwt-keys.sh`, stores the output files in a directory on the host (e.g. `/opt/erpclean2/secrets/`, `chmod 600`), and sets `ERP_JWT_KEYS_DIR=/opt/erpclean2/secrets` and `ERP_JWT_PRIVATE_KEY_PATH=/run/keys/jwt_private_key.pem` / `ERP_JWT_PUBLIC_KEY_PATH=/run/keys/jwt_public_key.pem` in `.env`. The `volumes:` in the compose bind-mounts `ERP_JWT_KEYS_DIR` to `/run/keys` read-only.

**docs/ops/jwt-keys.md** documents: key generation procedure, file permissions, prod wiring, rotation procedure (generate new pair, update `.env`, rolling restart), and the known limitation below.

**Known hardening note (documented, not built this wave):** `JwtBeans.java` builds a `NimbusJwtDecoder` with no `JwtIssuerValidator` — only signature and expiry are checked. Low risk while there is a single in-house RS256 signer. Becomes a real risk if the app ever accepts tokens from external identity providers or operates as a multi-tenant IdP. Flag to security-engineer before any such expansion. Adding `JwtIssuerValidator` is a one-line change in `JwtBeans` but requires a coordinated token format change; deferred to a future ADR.

---

## D-11 — Security Audit Pass + Dependency CVE Sweep [M]

**Files touched/new:** `backend/pom.xml` (OWASP plugin), `.github/dependabot.yml` (new), `.github/workflows/web-ci.yml` (`npm audit` addition), `dependency-check-suppressions.xml` (new, initially empty), `docs/ops/security-sweep.md` (new)

**OWASP dependency-check Maven plugin** in `backend/pom.xml` as a `<reporting>` plugin (run on demand, not on every build):

```xml
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>10.0.3</version>
    <configuration>
        <failBuildOnCVSS>7</failBuildOnCVSS>
        <suppressionFile>dependency-check-suppressions.xml</suppressionFile>
    </configuration>
</plugin>
```

The `failBuildOnCVSS=7` threshold fails on High/Critical CVEs. `dependency-check-suppressions.xml` (initially empty, committed) allows false positives on non-exploitable findings to be documented and suppressed with a rationale.

**NVD API key caveat:** The NVD rate-limits unauthenticated pulls heavily (as of 2024+). A CI-automated weekly sweep job is likely to fail on NVD throttling without an `NVD_API_KEY` secret provisioned in GitHub. Therefore: the plugin configuration is committed and `docs/ops/security-sweep.md` documents how to run `mvn dependency-check:aggregate` locally; the **CI-automated weekly job is fenced** pending the NVD API key ops decision. The job skeleton is documented in `security-sweep.md` as a `workflow_dispatch` trigger ready to enable once the key is available.

**`.github/dependabot.yml`:**

```yaml
version: 2
updates:
  - package-ecosystem: maven
    directory: /backend
    schedule: { interval: weekly }
    open-pull-requests-limit: 5
  - package-ecosystem: npm
    directory: /web
    schedule: { interval: weekly }
    open-pull-requests-limit: 5
  - package-ecosystem: github-actions
    directory: /
    schedule: { interval: weekly }
```

Dependabot PRs target `develop`. Owner reviews and merges.

**npm audit** step added to the existing `web-ci.yml` `build-and-test` job: `npm audit --audit-level=high` after `npm ci`. Consistent with the Maven CVSS-7 threshold.

**Spring Boot version note (runbook, not built):** `backend/pom.xml` parent version is `3.3.5`. `docs/ops/security-sweep.md` documents that bumping to the latest 3.3.x patch release is the first recommended action after this ADR merges (one-line parent version change, then `mvn clean verify`). The OWASP scan will surface any known CVEs in the current Spring/Tomcat/Nimbus transitive deps.

**Image version/SHA stamping (rollback capability):** The prod compose references `${ERP_API_IMAGE:-erpclean2:prod}` — a mutable `:prod` tag means "what is deployed is unknowable." Until the registry decision is made (D-Fenced), the ops runbook (`docs/ops/deploy-prod.md`) documents tagging the image with the git SHA before deploy: `docker build ... -t erpclean2:$(git rev-parse --short HEAD)` and updating `ERP_API_IMAGE` in `.env` before `docker compose up -d`. This gives a rollback target (re-point `ERP_API_IMAGE` to the previous SHA tag, `docker compose up -d`) without requiring a registry. Once a registry is chosen, the CI job stamps and pushes the image automatically.

---

## D-Fenced — Owner Ops Decisions (NOT built this wave)

These items require a topology/vendor decision by the owner. No repo artifacts are built for them. Each is documented as a numbered checklist in `docs/ops/deploy-prod.md`.

**[ ] 1. Production host and orchestrator.** Single-EC2 + compose (the `infra/prod/docker-compose.yml` from D-6 is the deploy artifact), ECS/Fargate, K8s (no manifests in repo; zero Kubernetes YAML files anywhere), or a PaaS. This decision determines whether D-6's compose file is the live deploy unit or documentation only.

**[ ] 2. Managed Postgres vs self-hosted.** Managed DB (RDS, Cloud SQL, Neon, Supabase) removes the `db` service from the prod compose and the D-7 backup script is supplemented by the managed snapshot facility. Self-hosted keeps both. The `ERP_DB_URL` env var is the only wiring change.

**[ ] 3. Container registry and credentials.** GHCR is the zero-additional-infra option. Requires a PAT with `write:packages` or a `GITHUB_TOKEN` with packages write. The `ERP_API_IMAGE` variable accepts the registry-tagged image. Until decided, images are built on-box.

**[ ] 4. Secrets backend.** Options: gitignored `.env` on the host (current shape, acceptable for single-operator), Docker Swarm secrets, HashiCorp Vault, AWS SSM/Secrets Manager, K8s Secrets. The JWT key bind-mount in D-6 is the minimal working solution for a file-based secret. Multi-instance or PaaS deployments need a real secrets store.

**[ ] 5. TLS termination and reverse proxy.** The API image serves plain HTTP on 8081. Production needs TLS termination — Caddy (automatic Let's Encrypt), nginx with certbot, or a cloud load balancer (ALB, Cloud Run ingress). No nginx or Caddy config exists in the repo.

**[ ] 6. Live prod deploy pipeline.** Current QA deploy: manual SSH git-pull-and-rebuild. CI-triggered prod deploy requires the registry decision. Once the image is built in CI and pushed to a registry, `deploy.sh` changes from `docker build` to `docker pull` + `docker compose up -d --no-build`. The readiness healthcheck (`wget -qO- http://localhost:8081/actuator/health/readiness`) is already wired in the prod compose and can be polled post-deploy to gate the "deploy succeeded" signal.

**[ ] 7. Log aggregation backend.** D-3 produces JSON-to-stdout in the `prod` profile. The aggregation platform (ELK stack, Datadog, CloudWatch Logs, Loki/Grafana) is a cost and topology decision.

**[ ] 8. Metrics scraping and dashboards.** D-5 exposes `/actuator/prometheus` on port 9090. Scraping requires a Prometheus server or a managed equivalent. Dashboard templates (JVM + Hikari + outbox metrics) can be authored as Grafana JSON exports in `infra/grafana/` in a future wave once scraping is confirmed.

**[ ] 9. Distributed tracing.** The MDC `requestId` from D-2 is the pragmatic substitute at current scale. `micrometer-tracing` with a Brave/OTel bridge is a pom.xml addition; the collector (Zipkin, Jaeger, Tempo) and sampling rate are live-ops decisions. Deferred.

**[ ] 10. HTTP security headers and CSP.** Deferred to a follow-up ADR (see Context section for the full disposition). HSTS + CSP require the TLS edge decision and a nonce/hash strategy for the baked-in Angular bundle.

**[ ] 11. CI-automated OWASP weekly scan.** Deferred pending an `NVD_API_KEY` GitHub secret. Run `mvn dependency-check:aggregate` locally in the meantime.

**[ ] 12. GitHub PAT scope and EC2 credentials rotation.** Live-ops hygiene. Confirm the PAT in `infra/qa/deploy.env.local` is scoped to `contents:read` only; rotate if broader. Gitignored correctly, never in history.

**[ ] 13. Pre-commit secret-scan hook.** `gitleaks` or `trufflehog` as a pre-commit hook via `.pre-commit-config.yaml` or a CI step. Requires team agreement on toolchain and a baseline suppression for the correctly-gitignored dev credential files.

---

## Migration

No Flyway migration (V82) is required by any of the 11 items. The hardening is entirely at the infrastructure, configuration, and platform code layers. The V1–V81 freeze is intact.

---

## Build Tranches (Parallelizable Units)

### Tranche A — Zero-risk, highest-leverage, no Java behaviour change (parallelize freely)

Ship Tranche A before any Java changes in B land so that subsequent changes in B/C are gated by a real CI run.

- **A1: D-8 (backend CI workflow, `backend-ci.yml`)** — new file only, no code changes. Ship FIRST. The `fast-check` job self-validates on merge. Add `integration-test` as non-required (observe-only) initially.
- **A2: D-1 (exception logging in `GlobalExceptionHandler`)** — 3-line Java change, zero behaviour risk. Ship immediately; it illuminates everything else.
- **A3: D-11 partial (`.github/dependabot.yml` + `npm audit` step in `web-ci.yml`)** — new file + one line. Zero risk.

These three items touch different files and have no overlap; they can be raised as separate PRs or a single PR.

### Tranche B — Observability core (serialize application.yml edits to avoid conflicts)

B is internally serial because D-4, D-2/D-3, and D-5 all touch `application.yml` or are causally chained. Do not parallelize within B.

- **B1: D-4 (actuator probes + port reconciliation + HEALTHCHECK in `backend/Dockerfile`)** — ship first within B. **This is also where the port bug is fixed**: `EXPOSE 8080` → `EXPOSE 8081`, all healthcheck URLs updated to `localhost:8081`. The management block in `application.yml` gets the readiness/liveness split. Gate: `mvn clean verify` green.
- **B2: D-2 + D-3 (MDC enrichment in `JwtRequestContextFilter` + `logback-spring.xml` + `application-prod.yml` + logstash-logback-encoder in `pom.xml`)** — implement together; the MDC fields are only useful if the log pattern includes them. Gate: `mvn clean verify` green (tests run with the default logback branch — verify no logback context-load failure in the IT suite). ADDITIONALLY: `SPRING_PROFILES_ACTIVE=prod` boot of the assembled jar to confirm the JSON encoder resolves.
- **B3: D-5 (Micrometer + Prometheus + custom outbox metrics + management-server.port in `application.yml`)** — after B1 and B2. The management port configuration goes here. Gate: `mvn clean verify` green; confirm no IT asserts on actuator exposure. Verify `/actuator/prometheus` on port 9090 returns Prometheus exposition text with `SPRING_PROFILES_ACTIVE=prod`.

### Tranche C — Infra artifacts (parallel with B after A merges; no Java changes)

C1/C2/C3 are fully independent of each other (all new files, no suite impact) and can be raised as separate PRs or one.

- **C1: D-6 (infra/prod/Dockerfile + prod compose + .env.example + infra/qa/Dockerfile dead-env fix)** — smoke-test required before declaring done: `docker build -f infra/prod/Dockerfile -t erpclean2:prod . && docker run --rm -e ERP_API_PORT=8081 -e SPRING_PROFILES_ACTIVE=prod ... erpclean2:prod`. Verify HEALTHCHECK passes, SPA index loads, `/actuator/health/readiness` returns 200.
- **C2: D-7 (backup/restore scripts + docs/ops/backup-restore.md)** — manual smoke-test: bring up the prod compose `db` service, seed a row, run `backup.sh`, drop the row, run `restore.sh`, verify row is restored.
- **C3: D-10 (generate-jwt-keys.sh + docs/ops/jwt-keys.md)** — smoke-test: run `generate-jwt-keys.sh`, mount the keys into the prod image via the compose bind-mount, start with `ERP_JWT_SIGNING_MODE=file`, verify login works and survives a container restart without token invalidation.

### Tranche D — DX and dependency hygiene (lowest urgency; land any time after A)

- **D1: D-9 (springdoc-openapi)** — `mvn clean verify` green; `EndpointAuthorizationTest` still passes; GET `/v3/api-docs` returns JSON spec; `ERP_SWAGGER_ENABLED=false` returns 404.
- **D2: D-11 remainder (OWASP plugin in pom.xml + `dependency-check-suppressions.xml` + `docs/ops/security-sweep.md`)** — run `mvn dependency-check:aggregate` locally first; triage any CVSS-7+ findings before enabling any CI gate. CI-automated weekly job fenced pending NVD API key.

---

## Risks

**Risk 1: mvn clean verify is the gate, not incremental compile.** Every Tranche B item touching Java code must be validated with `mvn clean verify` (full 844-test suite + failsafe ITs). Estimated runtime with the singleton Postgres container: 8–12 minutes locally. Do not merge a Java change that has not passed `mvn clean verify`.

**Risk 2: Port mismatch (mitigated in D-4).** `backend/Dockerfile EXPOSE 8080` is wrong today. D-4 fixes this as its first act. Until D-4 merges, any Docker-based smoke test of the existing `backend/Dockerfile` will find the HEALTHCHECK pointing at the wrong port. Do not build on top of D-6's prod compose until D-4's port fix is in `develop`.

**Risk 3: logback-spring.xml default-profile branch.** The test profile is neither dev nor prod. The `<springProfile>` blocks MUST have a sane default branch (the non-prod / `!prod` branch = plain PatternLayoutEncoder to stdout) so the ~100 Testcontainers ITs (booting a full Spring context with no active profile) do not encounter a logback config with no matching appender. A malformed `logback-spring.xml` fails fast at context init and would red the entire IT suite. The acceptance for D-3 requires `mvn clean verify` green — this is the check.

**Risk 4: logstash-logback-encoder on the test classpath.** `<optional>true</optional>` makes the dep non-transitive; it is still on this module's runtime and test classpath. The encoder is never instantiated in tests (no `prod` profile active), so this is harmless. The prod fat jar includes it (Spring Boot Maven plugin bundles runtime-scope deps including optional ones) — the prod-profile boot smoke-test (required in D-3 gate) confirms this.

**Risk 5: micrometer-registry-prometheus in test context.** Adding `micrometer-registry-prometheus` auto-configures a `PrometheusMeterRegistry` in all Spring test contexts. Confirm no IT asserts on the absence of `/actuator/prometheus` or on a specific exposure list. Low risk but check before merging D-5.

**Risk 6: integration-test CI flakiness as a merge blocker.** The singleton-container pattern with `ryuk.disabled=true` is correct on Linux CI runners. The risk is test isolation — if an IT leaves dirty Postgres state that causes a subsequent IT to fail, the failure appears flaky rather than as a real regression. The mitigation is: add `integration-test` as observe-only initially and promote to required only after 5+ stable runs.

**Risk 7: SecurityConfig matcher ordering (D-9).** The new springdoc `permitAll` matcher must be positioned after the `/api/**` matchers and before `GET /**`. The paths are not under `/api/**` so mispositioning does not break anything, but placing it at the top above `/api/**` should be avoided as a hygiene matter.

**Risk 8: JWT issuer/audience validation gap (documented, not mitigated this wave).** `JwtBeans.java` builds a `NimbusJwtDecoder` with no `JwtIssuerValidator`. Low risk with a single in-house signer. Flag to security-engineer before any external IdP integration. Documented in `docs/ops/jwt-keys.md`.

---

## Consequences

**What becomes easier:** Every unexpected 500 is logged with a full stack trace (D-1), ending the invisible-failure problem from Wave-2. Every log line carries request-id, user-id, company-id, and branch-id (D-2), making tenant-scoped incident triage possible. Backend regressions — including silent RBAC gate regressions — are caught on PR (D-8). The prod image is a single `docker build` + `docker run --env-file` away from a correct deployment (D-6). DB backup is a one-command operation (D-7). JWT signing mode is stable across restarts and instances (D-10). Dependency CVEs surface weekly rather than at the point of a breach (D-11).

**What becomes harder or constrained:** The CI suite adds ~8–12 minutes per PR for the IT job; any Java change that breaks `mvn clean verify` blocks the PR (after `integration-test` is promoted to required). The `logstash-logback-encoder` dependency must be maintained (Dependabot handles it). The `infra/prod/` directory and `docs/ops/` directory establish conventions that future ops work must follow.

**What is not changed:** The existing security filter chain. All `@PreAuthorize` / ScopeGuard logic. All Flyway migrations (V1–V81). All module boundaries enforced by `ModuleBoundaryTest`. `EndpointAuthorizationTest` and its allowlist of exactly 4 public endpoints. The `infra/qa/` directory topology (QA deploys continue to work; only the dead-env-var is fixed). The dev `docker-compose.yml` (untouched).

---

## Alternatives Considered

**Combine D-2 + D-3 with Spring Boot 3.4+ native structured logging.** Spring Boot 3.4 ships `LOGGING_STRUCTURED_LOGGING_FORMAT=ecs|logfmt` built in. ERPCLEAN2 is on 3.3.5 — this feature is not available in 3.3.x without a major version bump. Upgrading Boot to 3.4+ is its own regression surface and belongs in D-11's runbook as a follow-up action. `logstash-logback-encoder` is the stable, well-understood choice for 3.3.x. Not chosen: waiting on Boot upgrade before adding JSON logging.

**Run the OWASP CVE scan on every PR.** The OWASP dependency-check plugin downloads the NVD CVE database on each invocation and takes 3–5 minutes. The NVD now rate-limits unauthenticated pulls. Running it on every PR significantly increases CI cost and is likely to be flaky without an NVD API key provisioned. The weekly schedule catches CVEs promptly enough. Not chosen: on-every-PR for the dependency scan.

**nginx sidecar for Angular static serving instead of baking into the jar.** A separate nginx container decouples frontend and backend deployments and is the conventional approach at scale. It requires CORS configuration (`CorsConfigurationSource`, a `WebMvcConfigurer` — backend-engineer territory), a second image in the compose stack, and a network proxy configuration. `SpaWebConfig.java` already serves the SPA from `classpath:/static/` and explicitly guards against shadow-routing `/api/**` and `/actuator/**`. For a first prod deployment the baked-in approach is simpler and already proven by the QA image. Not chosen for this wave: requires a CORS + security ADR.

**Committed infra/prod/docker-compose.yml as the canonical prod deploy unit.** The host/orchestrator choice is an explicit owner ops decision fenced in D-Fenced. A committed compose file that pre-decides a single-EC2/compose topology before that decision would be dead if the owner chooses ECS/K8s. The compose is committed as a clearly-labelled reference single-node topology — the Dockerfile is the canonical, topology-agnostic artifact; the compose is a runbook aid.

**management.server.port split vs leaving actuator on main port.** An alternative is to leave all actuator endpoints on port 8081 and address the `permitAll("/actuator/**")` footgun by restricting the SecurityConfig matcher to `health` and `info` only (not an open `/**`). This is also correct but requires touching `SecurityConfig.java` (a security-sensitive file) and re-testing the auth gate. The separate management port approach avoids touching the security filter chain entirely and provides a cleaner separation. Chosen: separate management port on 9090, not exposed at the host edge.

---

## Files Touched by This ADR (Complete List)

**Modified (existing files):**
- `backend/src/main/java/com/erp/platform/common/api/GlobalExceptionHandler.java` — D-1
- `backend/src/main/java/com/erp/platform/security/JwtRequestContextFilter.java` — D-2
- `backend/src/main/java/com/erp/platform/security/config/SecurityConfig.java` — D-9 (permitAll addition for springdoc paths)
- `backend/src/main/resources/application.yml` — D-4 (management block: readiness/liveness + management.server.port), D-5 (management server port), D-9 (springdoc block)
- `backend/pom.xml` — D-3 (logstash-logback-encoder), D-5 (micrometer-registry-prometheus), D-9 (springdoc), D-11 (OWASP plugin)
- `backend/Dockerfile` — D-4 (EXPOSE 8081, HEALTHCHECK)
- `infra/qa/Dockerfile` — D-6 (dead-env-var fix: `JWT_SIGNING_MODE` → `ERP_JWT_SIGNING_MODE`)
- `.github/workflows/web-ci.yml` — D-11 (npm audit step)

**New files:**
- `backend/src/main/resources/logback-spring.xml` — D-3
- `backend/src/main/resources/application-prod.yml` — D-3
- `infra/prod/Dockerfile` — D-6
- `infra/prod/docker-compose.yml` — D-6 (reference single-node topology)
- `infra/prod/.env.example` — D-6
- `infra/prod/scripts/backup.sh` — D-7
- `infra/prod/scripts/restore.sh` — D-7
- `infra/prod/scripts/generate-jwt-keys.sh` — D-10
- `.github/workflows/backend-ci.yml` — D-8
- `.github/dependabot.yml` — D-11
- `dependency-check-suppressions.xml` — D-11
- `docs/ops/README.md` — ops index
- `docs/ops/backup-restore.md` — D-7
- `docs/ops/jwt-keys.md` — D-10
- `docs/ops/deploy-prod.md` — D-6 + D-Fenced runbook/checklist
- `docs/ops/security-sweep.md` — D-11
