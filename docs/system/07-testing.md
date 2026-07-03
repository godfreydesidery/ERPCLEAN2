# Testing

ERPCLEAN2 is verified by a layered test pyramid — JUnit unit tests, Testcontainers
integration tests, Vitest web unit tests (including an axe a11y gate), Playwright e2e
(with axe), and a static identity-discipline gate — backed by a 1,150-case manual/automated
test-case suite. This document describes each level, how to run it, and how findings flow into
the issues register.

The execution strategy is in
[docs/testing/TESTING-STRATEGY.md](../testing/TESTING-STRATEGY.md); the per-module cases are in
[docs/testing/test-cases/](../testing/test-cases/).

## 1. The test pyramid

| Level | Tool | Where | Count | Run |
|---|---|---|---|---|
| Unit | JUnit (surefire) | `backend/` | — | `mvn test` |
| Architecture gates | ArchUnit (surefire) | `backend/` | — | `mvn test` |
| Integration | Spring + Testcontainers Postgres (failsafe) | `backend/` | ~98 ITs | `mvn verify` |
| Web unit | Vitest | `web/` | 678 specs | `npm test` |
| Web a11y | jest-axe (inside Vitest) | `web/` | (within the 678) | `npm test` |
| C1 static gate | Node script | `web/` | — | `npm run c1` |
| System e2e | Playwright + axe | `web/e2e/` | data-driven | `npm run e2e` |

The pyramid widens upward in coverage and downward in speed: the surefire layer is the fast PR
gate; the Testcontainers ITs and Playwright e2e are the slower, higher-fidelity layers.

## 2. Backend — unit and architecture gates

```bash
cd backend
mvn test          # compile + surefire: JUnit unit tests + ArchUnit gates
```

Two ArchUnit gates run in this fast phase and **fail the build** on violation:

- **`ModuleBoundaryTest`** — enforces the modular-monolith layering: controllers may not touch
  repositories; a module does not import another module's entity/service; cross-module
  communication is via DTOs/enums and the outbox only.
- **`EndpointAuthorizationTest`** — scans every `@RestController` under `com.erp.api` and fails
  if any handler lacks a `@PreAuthorize` gate. The public-endpoint allowlist is exactly 4
  endpoints (see [04-security.md](04-security.md) §5). This makes deny-by-default a build
  guarantee.

Two further surefire permission-parity guards run in the same fast phase and complete the parity
chain (reachable → guard parity → seeded → read-closure):

- **`PermissionCodesSeededTest`** — fails the build if any `@perm` gate references a permission
  code the repeatable seed (`R__seed_permissions.sql`) does not define (a **phantom** code,
  invisible to root but broken for everyone else).
- **`RolePermissionClosureTest`** (ADR-0047) — asserts the screen-read-closure manifest
  (`backend/src/main/resources/security/screen-read-closure.json`) stays honest against the live
  controller gates: each declared read's gate-form and code equal the real `@PreAuthorize`, every
  strict required read is seeded (so a role *can* be composed to satisfy it), and no manifest code
  is a phantom. No Docker; it reuses the seeded-codes scanner.

This is the **required** gate in `backend-ci.yml`'s `fast-check` job — fast, no Docker.

## 3. Backend — integration tests (Testcontainers)

```bash
cd backend
mvn verify        # surefire + failsafe: ~98 Spring + Testcontainers Postgres ITs
```

The ITs boot a full Spring context against a **real PostgreSQL 15** container, so they exercise
Flyway migrations, the tenant predicate, RBAC gates, the outbox, and HTTP-level filter behaviour
(e.g. the branch-override 403 path that service-level tests cannot see — ADR-0003 D-2).

- A **singleton container per JVM** with **Ryuk disabled** (`PostgresIntegrationTest` /
  `testcontainers.properties`) — required on Windows/Docker-Desktop, harmless on Linux CI.
- The `@Scheduled` outbox poller is disabled in ITs (`erp.outbox.scheduling-enabled=false`) so
  it does not race assertions; `@DynamicPropertySource` overrides the datasource; `MailStubConfig`
  stubs the mail sender; bootstrap is disabled by the test properties.
- `clean verify` is the source of truth — an incremental compile gave a false green in a prior
  wave; always run the clean build before trusting a green.

In CI this is `backend-ci.yml`'s `integration-test` job (observe-only until proven stable, then
promoted to required).

## 4. Web — unit and a11y

```bash
cd web
npm test                          # Vitest, 678 specs, includes the jest-axe a11y gate
npx vitest run <path-to-spec>     # a single spec
npm run build                     # type-check + bundle, must be zero errors
```

Each feature ships a `*-list.component.spec.ts` covering the standard cases: load-once,
`isEmpty`, the validation guard, the success payload, and `403 → 'forbidden'`. The axe a11y
checks run inside Vitest, so accessibility regressions fail the same command.

## 5. The C1 static gate

```bash
cd web
npm run c1        # web/scripts/c1-check.mjs
```

This statically enforces identity discipline (convention C1, [06-conventions.md](06-conventions.md)
§4): uids must not leak into the UI, resources must be chosen via `<app-uid-picker>` (no
free-text uid/id inputs), and no numeric id may appear in a URL path. It is a fast, dependency-free
check run alongside `npm run build` and `npm test`.

## 6. System e2e — Playwright + axe

```bash
# Bring-up (local is the canonical e2e environment)
docker compose up -d db
cd backend && SPRING_PROFILES_ACTIVE=dev ERP_API_PORT=8081 mvn spring-boot:run
cd web && npm run e2e:install                # Chromium, first time only
node e2e/full-coverage-drive.js             # seed volume data via the API

# Run (Playwright auto-starts ng serve on :4200)
cd web && ROOT_PASS=RootPass12345 npm run e2e
```

The suite (`web/e2e/`) is layered so coverage is broad and failures are unambiguous:

| Layer | Proves |
|---|---|
| **L1 Auth & RBAC** | Login per role; nav visibility per permission; forbidden-route handling; cross-tenant denial. |
| **L2 Route smoke** | Every admin route loads (no error state, heading visible, no console error / API 5xx) + axe scan. Data-driven from the route list. |
| **L3 Conventions** | C1 (uid never shown, picker used), C4 (four-state), C5 (pagination), C6 (axe), C8 (money/date). |
| **L4 Lifecycle flows** | The create → action → state journeys per module, grounded in the per-module test-case docs. |

Assertions navigate by **route** and interact by **accessible role/label/placeholder** — never
by uid. The C1 gate asserts no ULID/numeric-id text appears in a visible cell and that resource
selectors are pickers, not free-text uid inputs. axe runs on representative screens; serious /
critical violations fail. Each Playwright test maps back to a `TC-<DOMAIN>-NNN` id so coverage is
traceable. Playwright e2e is wired into `web-ci.yml` but not auto-triggered (it needs a running
backend) — run it locally or via `workflow_dispatch`.

## 7. The test-case suite (1,150 cases)

[docs/testing/test-cases/](../testing/test-cases/) holds **1,150 cases across 25 documents** —
one per module plus a strategy doc, an RBAC matrix, and a cross-cutting conventions doc. They
were authored from the real code (controllers, routes, DTOs, enums, migrations) and
**adversarially verified**: a second pass grep-confirmed every cited endpoint, permission code,
enum value, and route. Each is the best per-module reference for screens, routes, permissions,
fields, status lifecycles, and flows.

The cases are **UI-first**: written to drive automated Playwright e2e (navigate by route, pick by
name, assert four-state + pagination + axe + RBAC) and to double as manual UAT scripts. Start with
[00 — Test Strategy & Environment](../testing/test-cases/00-test-strategy-and-environment.md) for
the environment matrix (all user/branch/entity types), the ID scheme, and the C1–C9 convention
charter; the [README](../testing/test-cases/README.md) has the full catalogue and case counts.

## 8. Issues register

Findings flow into [docs/testing/ISSUES-REGISTER.md](../testing/ISSUES-REGISTER.md) (and
[ISSUES.md](../testing/ISSUES.md)). Every e2e failure is triaged into **real app defect** vs
**spec defect** (a flaw in the test): real defects are logged with id, severity, module, the
`TC-`/spec that found it, route, role, steps, expected vs actual, and evidence
(screenshot/trace); spec defects are fixed in the spec, not logged as product issues. Severity is
P1 (blocking — cannot complete a core flow / 500 / auth broken), P2 (major), or P3 (minor).

**Release gate:** e2e L1+L2 fully green, L4 P1 flows green, and no open P1 in the issues register.

Authoring the test-case suite already surfaced concrete defects to confirm when running — for
example the POS permission prefix mismatch (controllers check `POS.*` while the migration seeds
`SALES.POS.*`, so the exact-match resolver denies every non-root user), hardcoded POS tender, and
several create-path 500s. These are catalogued in the
[test-cases README](../testing/test-cases/README.md).
