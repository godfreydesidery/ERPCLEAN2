# Wave 3 (production hardening) — recon (drives ADR-0038)


## observability-logging-health

WHAT EXISTS (cited):

ACTUATOR / HEALTH: pom.xml (D:/My_Works/ERP/ERPCLEAN2/backend/pom.xml lines 38-41) includes spring-boot-starter-actuator. application.yml (lines 25-32) exposes only `health,info` over web, with `endpoint.health.show-details: when-authorized`. No metrics/prometheus/loggers endpoints exposed. SecurityConfig (D:/.../platform/security/config/SecurityConfig.java line 49) does `requestMatchers("/actuator/**").permitAll()`, and `/api/v1/health` is also public (line 46). So a liveness/health probe IS reachable unauthenticated at /actuator/health. There is NO separate readiness vs liveness probe configured (no `management.endpoint.health.probes.enabled`, no liveness/readiness group), and DB-down behavior of /actuator/health is default (the JPA/Datasource health indicator is auto-included and will report DOWN).

EXCEPTION HANDLING / THE #4 GAP (confirmed): GlobalExceptionHandler.java (D:/.../platform/common/api/GlobalExceptionHandler.java) is an @RestControllerAdvice(basePackages="com.erp.api"). The base-package scoping is CORRECT â€” controllers genuinely live in com.erp.api (e.g. backend/src/main/java/com/erp/api/StockController.java). It maps domain/validation exceptions to clean envelopes. The catch-all `handleUnexpected(Exception)` (lines 113-119) returns a generic 500 and has NO logging: line 116 is literally `// TODO(logging): wire a logger in Slice 0 follow-up; do not echo ex.getMessage() to client.` This class imports/uses NO logger at all. This confirms ISSUES-REGISTER #4: any unexpected 500 (e.g. the constraint-violation 500s in findings #15 AP bill-match and #20a CRM) is INVISIBLE in logs â€” the stack is swallowed. This is the known S-effort win. Fix is ~3 lines: add a SLF4J logger and `log.error("Unhandled exception processing request", ex)` before returning the safe envelope. SecurityErrorResponder.java (filter-level 401/403 envelopes) ALSO does not log the denial.

LOGGING (what's present): NO logback-spring.xml / logback.xml / log4j2 config anywhere (Glob over backend/** and resources/** = none) â€” the app uses Spring Boot's DEFAULT logback (plain-text pattern to stdout, which satisfies 12-factor stdout but is NOT structured/JSON). Log levels: application-dev.yml (lines 28-31) sets org.hibernate.SQL=debug and com.erp=debug for dev; base application.yml sets NO logging levels (defaults to INFO). SLF4J loggers are used widely in the service/event layer â€” 63 files declare a logger (e.g. GLPostingSafeInvoker.java line 43 `LoggerFactory.getLogger(...)`, plus stock/gl/notifications/manufacturing handlers) â€” so ad-hoc business logging exists, but it is inconsistent and there is no central policy.

WHAT IS MISSING (verified absent): NO correlation-id / request-id / MDC anywhere (Grep for MDC|CorrelationId|X-Request-Id across backend/src = 0 hits). The one OncePerRequestFilter (JwtRequestContextFilter.java) populates RequestContext (user/company/branch/ip) for audit/scope but puts NOTHING in the SLF4J MDC, so log lines cannot be correlated to a request/user/tenant. NO request/access logging filter (no CommonsRequestLoggingFilter, no custom access log). NO Micrometer/Prometheus dependency, NO metrics endpoint (Grep for micrometer|prometheus|tracing|otel|zipkin|sleuth in pom.xml = 0). NO distributed tracing (no micrometer-tracing / OTel / Zipkin). NO structured (JSON) logging encoder (no logstash-logback-encoder).

The repo's OWN roadmap corroborates all of this: docs/PATH-TO-FULL-ERP.md line 386 lists "Observability / structured error logging â€” S â€” GlobalExceptionHandler catch-all has a TODO and does not log before 5xx (ISSUES-REGISTER #4, ~1-line fix); outbox metrics absent"; line 420 "Metrics (Micrometer/Prometheus) â€” M"; line 421 "Distributed tracing â€” L"; line 473 explicitly says the 1-line exception-logging fix and a readiness probe "should land in Phase A (cheap, high value)". Theme 16 (line 40) marks production-hardening 30% done with logging/CI/CD/K8s remaining.

### Gaps
- [S · PROD-CRITICAL] Catch-all @ExceptionHandler(Exception.class) in GlobalExceptionHandler does NOT log the stack at ERROR before returning the safe 500 (the TODO(logging) on line 116). Unexpected 500s are invisible in logs â€” this directly hampered diagnosing real prod-blocker bugs #15 (AP bill-match) and #20a (CRM create). Fix is a SLF4J logger + log.error(msg, ex). Repo artifact, no live-ops needed.
- [M · PROD-CRITICAL] No correlation/request-id and no MDC enrichment. JwtRequestContextFilter already resolves user/company/branch/ip per request but pushes none of it into the SLF4J MDC, and no request-id is generated/propagated. In a multi-tenant ERP you cannot trace a log line back to a request, user, or company â€” severe for production incident triage. Achievable as a repo artifact (generate/accept X-Request-Id, MDC.put in the existing filter, add to log pattern).
- [M] No structured (JSON) logging. App uses Spring Boot default plain-text logback to stdout. stdout is 12-factor-OK, but without JSON encoding (e.g. logstash-logback-encoder) and the MDC fields above, log aggregation (ELK/Datadog/CloudWatch) is painful. Repo artifact = add logback-spring.xml with a JSON encoder + an env-toggle; the aggregation backend choice itself is a live-ops decision.
- [S] No explicit liveness/readiness probe split. /actuator/health is exposed and permitAll, but there is no readiness group (e.g. excluding warmup) nor liveness/readiness probe config â€” needed for clean K8s/orchestrator rollouts (the roadmap flags the readiness probe as a Phase-A item). The default health does include DB/JPA indicators. Repo artifact (management.endpoint.health.probes.enabled + groups).
- [M] No application/business metrics (Micrometer + Prometheus). No metrics endpoint exposed and no micrometer-registry-prometheus dep. Operationally important signals are unmeasured: outbox/DomainEventDispatcher latency & poison-event retries (the #20d mandatory-dimension poison loop would be invisible), GL-handler error rates, HTTP timing, DB pool saturation. Repo artifact = add dep + expose /actuator/prometheus + a few custom counters/timers; scraping/dashboards are live-ops.
- [L] No distributed tracing (trace-id propagation, OTel/Zipkin/Jaeger). For a modular monolith with async outbox event dispatch, no trace links the originating request to the async GL/stock handlers that post later. Nice-to-have at current scale; becomes important if/when extracted to services. Partly repo artifact (micrometer-tracing bridge) + a collector backend (live-ops).
- [S] No request/access logging. No filter logs inbound method/path/status/latency. Not a strict blocker (a reverse proxy/ingress usually provides access logs in prod), but with no app-level request log and no correlation-id, in-app diagnosis is blind. Repo artifact if desired (a lightweight OncePerRequestFilter or CommonsRequestLoggingFilter).
- [S] No central logging-level / observability config in the base profile and no documented policy. Only application-dev.yml sets levels (com.erp=debug, hibernate.SQL=debug â€” must NOT be inherited by prod). Prod profile should pin INFO, ensure SQL logging off, and define the pattern with MDC fields. Repo artifact (an application-prod.yml or logback-spring.xml profile block); confirms a real config decision rather than live-ops.

### Files
- D:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/common/api/GlobalExceptionHandler.java
- D:/My_Works/ERP/ERPCLEAN2/backend/src/main/resources/application.yml
- D:/My_Works/ERP/ERPCLEAN2/backend/src/main/resources/application-dev.yml
- D:/My_Works/ERP/ERPCLEAN2/backend/pom.xml
- D:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/security/JwtRequestContextFilter.java
- D:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/security/config/SecurityErrorResponder.java
- D:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/security/config/SecurityConfig.java
- D:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/common/api/ApiResponseAdvice.java
- D:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/service/GLPostingSafeInvoker.java
- D:/My_Works/ERP/ERPCLEAN2/docs/testing/ISSUES-REGISTER.md
- D:/My_Works/ERP/ERPCLEAN2/docs/PATH-TO-FULL-ERP.md

## secrets-config

CONFIG IS GENUINELY 12-FACTOR / ENV-DRIVEN. backend/src/main/resources/application.yml externalizes every secret as ${ENV:default}: DB url/user/password (ERP_DB_URL/USER/PASSWORD, defaulting to localhost:5434 erp/erp), JWT signing-mode + key locations (ERP_JWT_SIGNING_MODE / ERP_JWT_PRIVATE_KEY / ERP_JWT_PUBLIC_KEY), API port, and the full bootstrap block (org/company/branch + admin username/password). No spring.security default user/password is configured. Password hashing is BCrypt cost 12 (SecurityConfig.java).

JWT KEY SOURCING (RsaKeyProvider.java, JwtBeans.java, JwtProperties.java): two modes. (1) dev-in-memory (DEFAULT): generates a fresh 2048-bit RSA keypair at startup â€” rotates every restart, invalidating all tokens. (2) file: loads a stable PEM keypair from ERP_JWT_PRIVATE_KEY/ERP_JWT_PUBLIC_KEY paths. There is NO hardcoded JWT secret and NO hardcoded fallback key â€” the key is either generated or read from disk. The code + application.yml comments explicitly flag file-mode as a required pre-prod gating item.

BOOTSTRAP ADMIN PASSWORD: never defaulted in prod (application.yml leaves admin-password empty). BootstrapRunner.validateAdminPassword() is fail-closed: app refuses to start if the password is missing/blank, <12 chars, a known placeholder (changeme/password/admin/rootadmin/secret/...), or fails the general policy. Idempotent (skips once an organisation exists).

SECRET INJECTION TODAY: only the single-container QA path exists. infra/qa/Dockerfile bakes the jar + sets SPRING_PROFILES_ACTIVE=qa and reads runtime secrets from `docker run --env-file infra/qa/qa.env`. infra/qa/entrypoint.sh exports ERP_DB_URL/USER/PASSWORD into the JVM and creates the in-container Postgres role from DB_USER/DB_PASSWORD. deploy.sh/deploy.ps1 SSH to EC2 16.170.11.41, git-pull, rebuild, and `docker run --env-file qa.env`. docker-compose.yml is dev-only (erp/erp). README + comments repeatedly state QA shape is wrong for prod (coupled lifecycle, no scaling) and prod must use a secret store + stable RS256 key.

COMMITTED-SECRET RISK = LOW. Confirmed via `git ls-files` + `git check-ignore` + `git log --all`: the live secret files qa.env (real DB pwd orbixlocal + real admin pwd), deploy.env.local (real GitHub PAT ghp_...), and CREDENTIALS.local.md are all gitignored, NOT tracked, and NEVER appeared in git history (no .env or PAT ever committed). .gitignore is thorough (.env, *.key/*.pem/*.pfx/*.p12, **/qa.env, **/deploy.env.local, **/*.local.md, static bundle, worktrees). Tracked files contain only placeholders (qa.env.example/.env.example use CHANGE_ME) or clearly-labeled dev defaults (application-dev.yml admin-password RootPass12345, docker-compose erp/erp, e2e UserPass12345). No hardcoded secret found anywhere in backend/src/main.

REAL BUG: infra/qa/Dockerfile line 71 sets ENV JWT_SIGNING_MODE=dev-in-memory, but the app reads ERP_JWT_SIGNING_MODE (application.yml line 39). The Dockerfile env is dead/ignored â€” harmless today (both default to dev-in-memory) but misleading and a footgun if someone tries to flip prod mode via that var. Also SecurityConfig permits /actuator/** unauthenticated, but management exposure is limited to health,info so impact is minimal.

### Gaps
- [L · PROD-CRITICAL] No production secret-injection mechanism is committed (no k8s Secret/External Secrets, no Vault, no Docker secrets, no SSM/Secrets Manager wiring). Only the QA --env-file path exists, which writes plaintext secrets to a file on the EC2 box. Prod needs a real secret store + manifests. This is partly a repo artifact (add k8s/compose-prod manifests + docs) and partly a live-ops decision (which store).
- [M · PROD-CRITICAL] JWT runs in dev-in-memory mode everywhere including QA, so every app restart rotates the RSA key and logs out all users. No file-mode key is provisioned and no infra generates/mounts a stable RS256 PEM pair. Must set ERP_JWT_SIGNING_MODE=file + mount keys (or move to a JWKS/KMS-backed key) before prod. Code already supports file mode; the gap is provisioning + ops.
- [S] Dockerfile sets JWT_SIGNING_MODE but the code reads ERP_JWT_SIGNING_MODE â€” the env var is silently ignored. One-line fix (rename to ERP_JWT_SIGNING_MODE) to avoid an operator believing they switched modes when they did not.
- [S] A live, valid-looking GitHub PAT (ghp_...) and real DB/admin passwords sit in the developer working tree (infra/qa/deploy.env.local, qa.env, CREDENTIALS.local.md). Correctly gitignored so not a repo leak, but the PAT is also persisted in the EC2 box's git remote and these tokens should be rotated/scoped (fine-grained, read-only) and ideally moved to a secret manager. Pure live-ops hygiene, not a repo change.
- [S] No automated guard against future secret commits (no pre-commit gitleaks/trufflehog hook, no CI secret scan). .gitignore is the only line of defense. Adding a scan is a cheap repo artifact that prevents regressions.
- [M] QA bootstrap admin password is loaded via plaintext --env-file (qa.env) which lands on disk in the docker inspect env and the box filesystem. Acceptable for QA; for prod the bootstrap admin secret should come from the secret store and bootstrap should be a one-shot, not a standing env on every restart.

### Files
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/resources/application.yml
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/resources/application-dev.yml
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/application-qa.yml
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/qa.env.example
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/qa.env
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/deploy.env
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/deploy.env.local
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/CREDENTIALS.local.md
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/Dockerfile
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/entrypoint.sh
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/deploy.sh
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/deploy.ps1
- d:/My_Works/ERP/ERPCLEAN2/infra/qa/README.md
- d:/My_Works/ERP/ERPCLEAN2/.env.example
- d:/My_Works/ERP/ERPCLEAN2/.gitignore
- d:/My_Works/ERP/ERPCLEAN2/docker-compose.yml
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/security/jwt/RsaKeyProvider.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/security/jwt/JwtBeans.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/security/config/JwtProperties.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/security/config/SecurityConfig.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/bootstrap/BootstrapRunner.java

## cicd

## What EXISTS today

### 1. Automated CI (GitHub Actions) â€” FRONTEND ONLY
`.github/workflows/web-ci.yml` is the ONLY project workflow (everything else under `.github` is inside `web/node_modules`). Triggers: push to `main`, `develop`, `feat/**`; PR to `main`/`develop`. `defaults.run.working-directory: web`.
- Job `build-and-test` (auto, ubuntu-latest, Node 22, npm cache): `npm ci` â†’ `npm run build` (prod Angular build) â†’ `npm test -- --watch=false` (vitest + axe a11y gate). This is the only thing gating PRs automatically.
- Job `e2e` (Playwright) is hard-disabled via `if: false` â€” never runs automatically. Comments say it needs a running backend wired into CI. Would upload `web/playwright-report/` artifact and uses `secrets.ROOT_PASS`.

### 2. Backend CI â€” DOES NOT EXIST
No workflow builds or tests the Java backend on PR/push. There is a large backend test suite that is NOT exercised by CI:
- ~100+ Testcontainers Postgres integration tests (`*IT.java`) run via maven-failsafe in `verify` phase (`backend/pom.xml` lines 160-173) plus surefire unit tests + ArchUnit boundary/endpoint-auth tests (`backend/src/test/java/com/erp/architecture/*`).
- Build commands (from `backend/pom.xml`, Spring Boot 3.3.5, Java 21): `mvn -B clean package` (unit tests), `mvn verify` (adds Testcontainers ITs), `mvn -DskipTests package` (jar only).
- CI feasibility note: ITs use a singleton-per-JVM Postgres container with Ryuk disabled (`backend/src/test/java/com/erp/support/PostgresIntegrationTest.java`, `backend/src/test/resources/testcontainers.properties`). The Ryuk-disable + singleton pattern is documented as a Docker-Desktop/Windows workaround; on Linux GitHub runners (which have Docker) these would generally work, but the no-reuse + edit-V1-baseline assumption means each run re-applies the full Flyway baseline.

### 3. Docker images â€” BUILT LOCALLY/ON-BOX, NEVER PUBLISHED
Two Dockerfiles exist, neither pushed to any registry:
- `backend/Dockerfile`: multi-stage Mavenâ†’JRE-alpine, builds `erp-api-*.jar`, `-DskipTests`, EXPOSE 8080. Used by `docker-compose.yml` `api` profile.
- `infra/qa/Dockerfile`: single-container QA image (Angular bundle baked into Spring static/ + Spring Boot fat jar + Postgres 15 + supervisord). Wrong shape for prod by its own header comment ("coupled lifecycle, no scaling"). Built ON the EC2 box during deploy, not in CI.
- `docker-compose.yml` (root): local dev only â€” Postgres on host 5434, opt-in `--profile api` containerised API on 8081. Dev secrets only.

### 4. QA deploy â€” MANUAL SSH PULL-AND-REBUILD (no CI involvement)
`infra/qa/deploy.sh` + `infra/qa/deploy.ps1` (identical logic): operator runs from their machine; SSH into EC2 `16.170.11.41` (`ubuntu@`, host pinned in `infra/qa/deploy.env`), then on the box: `git fetch/checkout/pull --ff-only` the branch (default `main`) â†’ `docker build -f infra/qa/Dockerfile` â†’ stop/rm old container â†’ `docker run -d -p 80:8081` with `--env-file infra/qa/qa.env` and named volume `erpclean2-data`. The image is built FRESH on the box every deploy from a git pull (no artifact handoff, no CI trigger). One-time bootstrap (Docker install, repo clone via PAT baked into git remote, copy `qa.env.example`â†’`qa.env` with admin+DB passwords) is documented in `infra/qa/README.md`. Bootstrap config in `infra/qa/qa.env.example`; runtime Spring overrides in `infra/qa/application-qa.yml`; process map in `infra/qa/supervisord.conf`; first-run DB init in `infra/qa/entrypoint.sh`.

### 5. Release tagging / versioning â€” NONE
No release workflow, no `softprops/action-gh-release`/tag automation, no semver bump. App version is static `0.0.1-SNAPSHOT` (pom) / `0.0.0` (web package.json). Deploys track a moving git branch (`main`), not a tagged immutable artifact.

### 6. Production deploy path â€” DOES NOT EXIST
No prod/staging infra of any kind: `infra/` contains only `qa/`. No prod compose, no orchestration (k8s/ECS/etc.), no IaC, no registry. ARCHITECTURE.md references prod conceptually (RS256 key, security review before prod deploy) but there is no prod deploy artifact.

## Automatic vs manual summary
- AUTOMATIC on PR/push: web build + web unit/a11y tests only.
- MANUAL: backend build/test (local mvn only), QA deploy (operator SSH), all image builds, everything prod.

### Gaps
- [M · PROD-CRITICAL] No backend CI on PR/push. The entire Java backend (compile, unit tests, ArchUnit boundary/auth rules, ~100+ Testcontainers ITs) is never run automatically â€” backend regressions can merge to develop/main undetected. Achievable as a repo artifact: add a backend job to web-ci.yml (or a new backend-ci.yml) running `mvn -B verify` on a Linux runner with Docker (Testcontainers works there; verify the Ryuk-disabled singleton pattern still passes in CI, possibly split fast `mvn test` on every push from full `mvn verify` ITs on PR for runtime).
- [M · PROD-CRITICAL] No published, immutable Docker image / registry. Images are rebuilt from a git pull on the box every deploy, so 'what is deployed' is not reproducible and there is no rollback target. Needs a live-ops decision on which registry (GHCR is the zero-infra default given GitHub), but the workflow (docker/build-push-action + login + metadata tags) is a buildable repo artifact.
- [XL · PROD-CRITICAL] No production deploy path at all (infra/ has only qa/). Prod is greenfield: requires live-ops decisions (host/orchestrator/managed Postgres vs in-container, TLS, RS256 key in a real secret store per ARCHITECTURE.md Â§, separate DB from app lifecycle since the QA single-container shape is explicitly not prod-grade). Repo artifacts (compose/k8s manifests, prod env templates) can be drafted but the topology choice is an ops decision.
- [L] Manual SSH deploy with no automation/audit. deploy.sh/.ps1 require an operator with the .pem and run git-pull-and-rebuild on a single hardcoded EC2 host. No deploy-from-CI, no health-check gate, no rollback. Converting to a CI deploy job (build image in CI, ssh/pull-image instead of rebuild) is achievable but partly depends on the registry decision above.
- [S] No release tagging / version pinning. Deploys follow a moving branch (default main) rather than an immutable tag; version stuck at 0.0.1-SNAPSHOT / 0.0.0. A tag-on-release workflow that stamps the version and pins the deployed image is a clean repo artifact.
- [M] Playwright e2e job is permanently disabled (if: false) pending a backend-in-CI environment. Once backend CI + an ephemeral stack exist, enabling it gives real end-to-end coverage; today there is zero automated e2e/integration coverage across the web+API boundary in CI.
- [S] QA Dockerfile uses `npm install` (not `npm ci`) because package-lock.json may be gitignored on the box â€” non-reproducible web dependency resolution in the deployed image vs the lockfile-pinned CI build. Pinning to npm ci / shipping the lockfile is a small repo fix.

### Files
- D:\My_Works\ERP\ERPCLEAN2\.github\workflows\web-ci.yml
- D:\My_Works\ERP\ERPCLEAN2\backend\pom.xml
- D:\My_Works\ERP\ERPCLEAN2\backend\Dockerfile
- D:\My_Works\ERP\ERPCLEAN2\backend\src\test\java\com\erp\support\PostgresIntegrationTest.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\test\resources\testcontainers.properties
- D:\My_Works\ERP\ERPCLEAN2\docker-compose.yml
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\deploy.sh
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\deploy.ps1
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\Dockerfile
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\entrypoint.sh
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\supervisord.conf
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\application-qa.yml
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\deploy.env
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\qa.env.example
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\README.md
- D:\My_Works\ERP\ERPCLEAN2\web\package.json

## topology-prod-vs-qa

CONFIRMED single-container QA shape, plus more prod-ready primitives already exist than the prompt assumed.

QA single-container (infra/qa/Dockerfile): 3 stages â€” (1) node:20-alpine builds the Angular bundle; (2) maven:3.9-temurin-21 builds the Spring Boot fat jar AND copies the Angular bundle into src/main/resources/static/ so the SPA ships inside the jar; (3) eclipse-temurin:21-jre-jammy runtime that ALSO installs postgresql-15 + supervisor + tini. So API + Postgres + Angular static are one image, one container, one lifecycle. The file header itself states "Wrong shape for production (coupled lifecycle, no scaling) â€” right shape for QA". EXPOSE 8081, VOLUME /var/lib/postgresql/data, ENTRYPOINT tini -> entrypoint.sh.

infra/qa/supervisord.conf: nodaemon supervisor runs two programs in the same container â€” postgres (priority 10, listen 127.0.0.1, autorestart) and api (priority 20, /opt/erpclean2/start-api.sh, startretries 20). Process-level coupling, no per-service container health.

infra/qa/entrypoint.sh: first-run bootstrap â€” initdb (auth-local trust, host scram-sha-256), pins listen_addresses=127.0.0.1, creates DB_USER/DB_NAME (default erp/erp), uses a marker file (.erpclean2-initialized). Generates start-api.sh which busy-waits on /dev/tcp 127.0.0.1:5432 (up to 60s) then execs java with -XX:MaxRAMPercentage=70. DB reachable only on localhost inside the container.

infra/qa/application-qa.yml: thin qa overrides (Hikari pool 10/2, logging). Datasource URL/user/pass injected by entrypoint as ERP_DB_URL/USER/PASSWORD.

Deploy automation (QA only): infra/qa/deploy.sh + deploy.ps1 SSH to a hardcoded EC2 box (deploy.env EC2_HOST=16.170.11.41), git pull a branch, docker build the QA image, stop/rm/run the container on -p 80:8081 with --env-file infra/qa/qa.env and --restart unless-stopped, single named volume erpclean2-data. qa.env.example carries bootstrap + DB secrets (gitignored on the box). This is build-on-the-box, not a registry pipeline.

ALREADY EXISTS toward prod (the prompt's 'what prod needs' is partly built):
1. Split API container â€” backend/Dockerfile is API-ONLY: 2-stage (maven build -> eclipse-temurin:21-jre-alpine), EXPOSE 8080, datasource purely from env, ENTRYPOINT java -jar. It does NOT install Postgres and does NOT bundle Angular. This is the seed of a prod API image.
2. Multi-container compose â€” root docker-compose.yml runs postgres:15-alpine (with a real pg_isready healthcheck, named volume erp-db-data, host 5434) plus an opt-in `api` profile that builds backend/Dockerfile, depends_on db service_healthy, env-driven. It is labeled dev-only and dev-secret, but it IS a working separate-DB, separate-API topology with DB health gating.
3. Health endpoints â€” spring-boot-starter-actuator is a dependency (backend/pom.xml) and application.yml exposes management endpoints health,info (health show-details when-authorized). So orchestration health probes have a target (/actuator/health) already.
4. Static-serving strategy â€” SpaWebConfig.java serves the Angular SPA from classpath:/static/ with HTML5 fallback and explicitly excludes /api/** and /actuator/**. So 'API serves the static' is implemented; a CDN/nginx split is optional, not required.
5. Secret/JWT awareness â€” application.yml jwt.signing-mode defaults to dev-in-memory (ephemeral RSA, rotates each restart, invalidates all tokens) with file-mode hooks (private/public-key-location env vars) already wired; comments + README explicitly flag a stable RS256 key from a secret store as a prod gating item. Bootstrap admin password refuses placeholder/short values.

DOES NOT EXIST: no prod Dockerfile that bundles Angular into the API-only image (backend/Dockerfile has no web-build stage and no HEALTHCHECK); no prod/prod-like docker-compose (the only multi-container compose is dev-defaults with hardcoded erp/erp creds and no API healthcheck/static); no nginx or CDN config anywhere (only supervisord.conf matched *.conf); no Kubernetes/Helm/manifests (zero matches); no DB backup/restore script (no pg_dump/restore/cron anywhere in infra); no HEALTHCHECK directive in either Dockerfile; no registry/image-tagging or CI deploy (deploy builds on the box via git pull); QA Postgres data lives in a single docker volume with no backup/retention.

### Gaps
- [S · PROD-CRITICAL] No prod Dockerfile for the API that bundles the Angular build (backend/Dockerfile is API-only with no web stage). For an API-serves-SPA prod topology you need either a 2-image build that adds the Angular static stage to the slim API image, or accept nginx serving static separately. Achievable as a repo artifact (add a web-build stage to backend/Dockerfile or a new infra/prod/Dockerfile). Note backend/Dockerfile uses jre-alpine; QA uses jre-jammy â€” pick one for prod.
- [M · PROD-CRITICAL] No prod/prod-like docker-compose splitting a managed/separate Postgres from the API container. The dev docker-compose.yml is close (separate db+api, db healthcheck, depends_on healthy) but ships hardcoded erp/erp creds, host port 5434, no API healthcheck, and the api profile image doesn't serve the SPA. A repo artifact (infra/prod/docker-compose.yml) parameterised by env, with secrets externalised and an actuator-based API healthcheck, is achievable. The actual DB endpoint/creds are a live-ops decision (managed RDS vs self-hosted).
- [S · PROD-CRITICAL] JWT signing-mode defaults to dev-in-memory (ephemeral RSA key, rotates every restart -> all tokens invalidated, breaks multi-instance/scaling). Prod must run signing-mode=file with a stable RS256 keypair from a secret store. The file-mode plumbing exists (ERP_JWT_PRIVATE_KEY/PUBLIC_KEY env), so this is config + key provisioning. Key generation can be a repo helper script; secret storage is a live-ops decision.
- [M · PROD-CRITICAL] No DB backup/restore strategy â€” QA keeps Postgres in a single docker volume with no pg_dump, no scheduled backup, no retention, no documented restore. For prod a pg_dump backup script + schedule (or relying on a managed DB's automated snapshots) is required. A backup/restore shell script is achievable as a repo artifact; choosing managed-DB snapshots vs self-managed cron is a live-ops decision.
- [S · PROD-CRITICAL] No container HEALTHCHECK and no orchestration health-gating for the API in any image (only the dev compose db has pg_isready). Actuator /actuator/health exists as a target, so adding a HEALTHCHECK to the prod API image and an API service healthcheck/readiness probe in compose/orchestrator is straightforward. Achievable as a repo artifact.
- [M · PROD-CRITICAL] Secrets management for prod â€” QA passes DB password + bootstrap admin password via a gitignored qa.env on the box. Prod needs a real secret store (env injection from a vault/SSM/parameter store/K8s secrets) rather than a file on the host. The app already reads everything from env vars so wiring is easy; the secret backend is a live-ops decision.
- [L] Deploy is build-on-the-box-from-git (deploy.sh/ps1 SSH + docker build) against a single hardcoded EC2 IP with no image registry, tagging, or rollback. Prod-grade delivery wants a CI build pushing tagged images to a registry and a pull-based deploy. A CI workflow + Dockerfile are achievable as repo artifacts; the registry, registry creds, and target environment are live-ops decisions.
- [M · PROD-CRITICAL] No reverse proxy / TLS termination story. QA exposes the API directly on port 80 (-p 80:8081); there is no nginx/traefik config and no HTTPS. Prod typically wants TLS termination + a proxy (or a cloud LB). A repo nginx/compose service is achievable; certs, DNS, and LB choice are live-ops decisions.
- [XL] No Kubernetes/Helm manifests. If the target is K8s, deployments/services/ingress/HPA/probes/PVCs are all absent (zero matches). Manifests are achievable as repo artifacts, but whether to use K8s at all, plus the cluster/registry/ingress controller, are live-ops decisions. Likely a nice-to-have unless K8s is the chosen target.
- [M] No static asset CDN configuration. SpaWebConfig serves the SPA from the jar today, which is acceptable for prod at modest scale; a CDN (CloudFront/etc.) for the Angular bundle is a scaling/perf optimisation, not a blocker. Entirely a live-ops decision.
- [M] No horizontal-scaling readiness verification. The single-container shape cannot scale (coupled Postgres). Even the split API has the ephemeral-JWT blocker above and should be checked for other in-memory state (sessions, locks, schedulers) before running multiple replicas. Investigation/repo changes possible; scaling target is a live-ops decision.

### Files
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\Dockerfile
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\supervisord.conf
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\entrypoint.sh
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\application-qa.yml
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\README.md
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\deploy.sh
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\deploy.ps1
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\deploy.env
- D:\My_Works\ERP\ERPCLEAN2\infra\qa\qa.env.example
- D:\My_Works\ERP\ERPCLEAN2\backend\Dockerfile
- D:\My_Works\ERP\ERPCLEAN2\docker-compose.yml
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\resources\application.yml
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\api\SpaWebConfig.java
- D:\My_Works\ERP\ERPCLEAN2\backend\pom.xml
- D:\My_Works\ERP\ERPCLEAN2\README.md

## security-openapi-deps

SECURITY POSTURE (mature, well-engineered for a JWT API):
- Filter chain: backend/src/main/java/com/erp/platform/security/config/SecurityConfig.java is a stateless OAuth2 resource server. SessionCreationPolicy.STATELESS; CSRF disabled (correct for a token-only, cookie-less API). Only 4 public API endpoints: /api/v1/auth/login, /refresh, /logout, /api/v1/health. Everything under /api/** is .authenticated() AND method-gated. EnableMethodSecurity is on; @EnableConfigurationProperties for JWT/security props. 401/403 from the filter chain are rendered as the ApiResponse envelope by SecurityErrorResponder (generic messages, no permission/token enumeration).
- RBAC/scope: @PreAuthorize("@perm.has(...)")/@perm.scoped(...) via PermissionChecks.java; tenant isolation centralised in ScopeGuard.java (canActIn/canActOn/assertCanActIn) which fails closed (unknown/unresolvable target -> deny) and audits root cross-company bypass. ~621 @PreAuthorize/permitAll/authenticated occurrences across 129 files. Deny-by-default is ENFORCED AT BUILD TIME: backend/src/test/java/com/erp/architecture/EndpointAuthorizationTest.java scans every @RestController under com.erp.api and fails the build if any handler lacks @PreAuthorize (allowlist = the 4 public handlers). JwtRequestContextFilter re-checks the user is still ACTIVE on every request (disabled user -> 401 immediately, not after TTL) and validates the X-Branch-Uid scope-override fail-closed.
- Auth hardening: BCrypt cost 12 (FR-IAM-08). Refresh tokens are SHA-256 hashed at rest, single-use/rotated with reuse detection and client-scope binding (RefreshToken.java, AuthServiceImpl.java). Constant-time unknown-user path (decoy bcrypt) to prevent username enumeration; account lockout (5 attempts / 15 min) recorded in a separate tx so it survives rollback.
- Actuator: management.endpoints.web.exposure.include = health,info only; health show-details=when-authorized. /actuator/** is permitAll in SecurityConfig but only health+info are exposed, so this is low-risk (no env/heapdump/loggers exposed).
- SPA serving: GET /** is permitAll for the co-located Angular shell; SpaWebConfig.java resource resolver explicitly refuses to fall back to index.html for api/ or actuator/ paths, and runs after dispatcher mappings so it never shadows REST routes. The /api/** matcher is evaluated first so the API stays gated.

OPENAPI: NOT present. No springdoc/swagger dependency in backend/pom.xml; no /v3/api-docs; no OpenAPI bean. The only mention is the roadmap docs/PATH-TO-FULL-ERP.md which lists "OpenAPI / Swagger API docs (springdoc) - M" as NOT_STARTED.

DEPS/CVE HYGIENE:
- Backend (backend/pom.xml): Spring Boot 3.3.5 parent (Nov 2024; current 3.3.x patch line has since advanced and 3.4/3.5 exist - it is a few patch releases behind, not dramatically stale). Pinned non-managed deps: OpenPDF 1.3.35, Apache POI 5.3.0. No OWASP dependency-check / org.owasp:dependency-check-maven, no spotbugs, no snyk/trivy/grype plugin.
- Web (web/package.json): Angular 21.2, bootstrap 5.3.8, rxjs 7.8, typescript 5.9 - all current/recent; no obviously-CVE'd runtime dep.
- No repo-level Dependabot config (.github has only web-ci.yml; the only dependabot.yml found is inside web/node_modules/fast-uri, i.e. a vendored dep, not ours).
- CI: .github/workflows/web-ci.yml builds+unit-tests the web app (+axe a11y) on push/PR. There is NO backend CI workflow at all - backend build/test (incl. the EndpointAuthorizationTest gate and Testcontainers IT) is not run in CI, and there is no automated dependency/CVE scan on either side.

### Gaps
- [S · PROD-CRITICAL] JWT signing key defaults to dev-in-memory (ephemeral RSA, rotates every restart, logs everyone out). Production MUST set ERP_JWT_SIGNING_MODE=file with a stable RS256 PEM keypair from a secret store. RsaKeyProvider supports it; this is a live-ops/deploy config decision, not a code gap, but it is a hard prod blocker if not set.
- [S] JwtDecoder (JwtBeans.java: NimbusJwtDecoder.withPublicKey(...).build()) sets NO issuer/audience validator - only signature + exp/nbf are checked by default. JwtService mints an 'issuer' claim (erp-api) but nothing validates it on the way in. Low real risk while there is a single in-house signer, but hardening: add JwtIssuerValidator (and an audience claim+validator) so a token from any other RSA key/realm cannot be replayed. Repo artifact, easy to add.
- [M] No HTTP security response headers configured (no HSTS, X-Content-Type-Options, X-Frame-Options/frame-options, Referrer-Policy, CSP). Spring Security's servlet defaults add some headers, but HSTS only applies over HTTPS and there is no explicit headers() hardening or CSP for the served SPA. Roadmap (OWASP/CSP) confirms it is outstanding. Hardening, not a functional blocker.
- [S] No CORS configuration anywhere (no CorsConfigurationSource, no .cors(), no WebMvcConfigurer CORS, no @CrossOrigin). Fine for the single-container co-located SPA (same origin). Becomes a real blocker only if/when the web app is served from a different origin than the API - a deployment-topology decision. Repo artifact when needed.
- [M] No OpenAPI/Swagger. Adding springdoc-openapi (springdoc-openapi-starter-webmvc-ui) is a clean S/M win: generates /v3/api-docs + Swagger UI. Must remember to permit /v3/api-docs/** and /swagger-ui/** in SecurityConfig (currently everything non-public under /api would be gated; these paths are outside /api so the GET /** permitAll would actually expose them - intended for docs). Documentation/DX, not a prod blocker.
- [M] No automated dependency/CVE scanning: no OWASP dependency-check Maven plugin, no Dependabot config for the repo, no npm-audit gate. For a financial ERP this is a real supply-chain hardening gap. Adding org.owasp:dependency-check-maven and a .github/dependabot.yml (maven + npm + github-actions ecosystems) are pure repo artifacts.
- [M · PROD-CRITICAL] No backend CI workflow. The build-time security gate (EndpointAuthorizationTest enforcing @PreAuthorize on every endpoint) and the Testcontainers integration tests are NOT run automatically on push/PR - only web-ci.yml exists. This means the strongest RBAC safety net can silently regress. Add a backend GitHub Actions job (mvn verify) + wire the dependency-check. Repo artifact.
- [S] Spring Boot 3.3.5 is a few patch releases behind the latest 3.3.x (and behind 3.4/3.5). No known headline-critical CVE blocking, but bumping to the latest 3.3.x patch (and reviewing transitive Tomcat/Jackson/Nimbus CVEs, which a dependency-check run would surface) is routine hygiene before prod. Repo artifact.
- [S] /actuator/** is permitAll. Currently safe because only health+info are exposed (when-authorized details). It is a latent footgun: if a future change adds exposure (env, loggers, heapdump, prometheus) without revisiting SecurityConfig, it would be unauthenticated. Recommend gating /actuator/** behind auth (or a management port) rather than relying on the exposure list. Hardening.

### Files
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\config\SecurityConfig.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\config\SecurityErrorResponder.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\config\JwtProperties.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\config\SecurityProperties.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\jwt\JwtBeans.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\jwt\JwtService.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\jwt\RsaKeyProvider.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\PermissionChecks.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\ScopeGuard.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\JwtRequestContextFilter.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\api\AuthController.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\api\SpaWebConfig.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\iam\service\AuthServiceImpl.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\iam\domain\entity\RefreshToken.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\test\java\com\erp\architecture\EndpointAuthorizationTest.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\resources\application.yml
- D:\My_Works\ERP\ERPCLEAN2\backend\pom.xml
- D:\My_Works\ERP\ERPCLEAN2\web\package.json
- D:\My_Works\ERP\ERPCLEAN2\.github\workflows\web-ci.yml
- D:\My_Works\ERP\ERPCLEAN2\docs\PATH-TO-FULL-ERP.md
