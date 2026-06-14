# ERPCLEAN2 — Testing Strategy

Operational strategy for verifying the whole ERP. It complements the test-case suite in
[`test-cases/`](test-cases/) (1,150 cases, 25 docs) and the test-case-level strategy in
[`test-cases/00-test-strategy-and-environment.md`](test-cases/00-test-strategy-and-environment.md).
This document is the **how we execute** layer: environments, tooling, the automated Playwright
pipeline, what we assert, and how findings flow into the issues register.

## 1. Objectives
1. Prove every module/submodule works end-to-end from the UI, for every relevant user/branch/entity type.
2. Enforce the system conventions (C1–C9) as automated gates — especially **C1: a uid is never shown to
   or typed by a user; it appears only in URL paths; resources are chosen by name via a picker.**
3. Surface defects with enough evidence (route, role, step, expected vs actual, screenshot/trace) to fix
   without re-investigation, and record them in [`ISSUES.md`](ISSUES.md).

## 2. Test levels (the pyramid already in place)
| Level | Tool | Where | Runs |
|---|---|---|---|
| Unit | JUnit (surefire) | `backend/` | `mvn test` |
| Integration | Spring + Testcontainers Postgres (failsafe) | `backend/` | `mvn verify` (~98 ITs) |
| Web unit | Vitest | `web/` | `npm test` (677 specs) |
| Web a11y | jest-axe | `web/` | within `npm test` |
| **System e2e** | **Playwright + axe** | **`web/e2e/`** | **`npm run e2e`** ← this strategy |
| Manual | the test-case docs as scripts | — | per release/UAT |

This document focuses on the **system e2e (Playwright)** level, which is the one that drives the real,
deployed UI against a real backend + database and is the primary issue-finding vehicle.

## 3. Environments
| Env | Stack | URL | Data | Use |
|---|---|---|---|---|
| **Local** (this run) | `docker compose up -d db` (Postgres :5434) + backend `mvn spring-boot:run` (dev profile, :8081, bootstraps `rootadmin`/`RootPass12345`) + `ng serve` :4200 (proxy `/api`→:8081) | http://localhost:4200 | seeded fresh via the API seeder | **default for automated e2e + dev triage** |
| QA | single container (infra/qa) | http://16.170.11.41 | persistent | release smoke / UAT |
| Prod | split topology (infra/prod) | — | live | — (no test runs) |

**Local is the canonical e2e environment** — it is fresh, reproducible, isolated, and free to reset.

## 4. Automated Playwright pipeline
### 4.1 Bring-up (one-time per run)
```bash
docker compose up -d db                                   # Postgres :5434
cd backend && SPRING_PROFILES_ACTIVE=dev ERP_API_PORT=8081 mvn spring-boot:run   # API :8081 + bootstrap
cd web && npm run e2e:install                             # Chromium (first time only)
node e2e/full-coverage-drive.js                           # seed volume data (API_BASE=localhost:8081)
```
### 4.2 Run
```bash
cd web && ROOT_PASS=RootPass12345 npm run e2e            # Playwright auto-starts ng serve :4200
```
Playwright config (`web/playwright.config.ts`): baseURL `:4200`, auto webServer (`ng serve`), Chromium,
trace on first retry, screenshot on failure.

### 4.3 Suite layout (`web/e2e/`)
The suite is **layered** so coverage is broad *and* failures are unambiguous:

| Layer | Spec(s) | What it proves | Style |
|---|---|---|---|
| **L1 Auth & RBAC** | `auth.spec.ts`, `rbac.spec.ts` | login per role; nav visibility per permission; forbidden-route handling; cross-tenant denial | per-role |
| **L2 Route smoke** | `routes.smoke.spec.ts` | EVERY admin route loads (no error state, heading visible, no console error / API 5xx); axe scan | **data-driven** from the route list |
| **L3 Conventions** | `conventions.spec.ts` | C1 uid-never-shown + picker-used; C4 four-state; C5 pagination controls; C6 axe; C8 money/date | data-driven across representative screens |
| **L4 Lifecycle flows** | `flows/<domain>.spec.ts` | the create→action→state journeys per module (POS sale, stock transfer, requisition→PO, journal post, AR receipt, …) | per-domain, grounded in `test-cases/<domain>.md` |

Each Playwright test maps back to a `TC-<DOMAIN>-NNN` id (in the test title) so coverage is traceable.

### 4.4 Assertion conventions (Playwright)
- Navigate by **route** (`/admin/...`); interact by **accessible role/label/placeholder**
  (`getByRole`, `getByLabel`) — **never** by a uid.
- **C1 gate:** assert no element text matches a uid pattern (`/^[0-9A-HJKMNP-TV-Z]{26}$/` ULID or a bare
  numeric id) in any visible table cell/label; assert resource selectors are `<select>`/picker, not a
  free-text uid input.
- Assert the **four states** where forced (loading spinner, empty message, error alert, forbidden notice).
- Assert **pagination** controls (first/prev/numbers/next/last) on lists with > 1 page.
- Run an **axe** scan on representative screens (C6); fail on serious/critical violations.
- Money formatted `CUR 1,234.56`; dates ISO `yyyy-MM-dd` (C8).

## 5. Test data
Seeded via `e2e/full-coverage-drive.js` (rootadmin → API), giving volume across every module and the
type variations: customers (INDIVIDUAL/BUSINESS × CASH_WALK_IN/CREDIT_ACCOUNT), suppliers (GOODS/SERVICE),
agents (INTERNAL/EXTERNAL), products (GOODS/SERVICE), locations (all `LocationType`), plus lifecycle docs.
Role users (ORG_ADMIN, SALES_MANAGER, SALES_REP, ACCOUNTANT, STOREKEEPER, PURCHASE_OFFICER) are created +
assigned for RBAC tests. The DB is reset (drop volume → re-bootstrap) for a clean baseline when needed.

## 6. Priorities & exit criteria
- **P1** blocking (cannot complete a core flow; 500; auth broken) → must fix before release.
- **P2** major (feature broken in a variation; convention violation users notice) → fix this cycle.
- **P3** minor (cosmetic, edge) → backlog.

**Release gate:** L1+L2 fully green; L4 P1 flows green; no open P1 in [`ISSUES.md`](ISSUES.md).

## 7. Reporting → issues
Every Playwright failure is triaged into **real app defect** vs **spec defect** (a flaw in the test).
Real defects are logged in [`ISSUES.md`](ISSUES.md) with: id, severity, module, the `TC-`/spec that found
it, route, role, steps, expected vs actual, and evidence (screenshot/trace path). Spec defects are fixed in
the spec, not logged as product issues. The Playwright HTML report + traces are the raw evidence.

## 8. Cadence
- On every change to `web/` or `backend/` API contract: run L1+L2 locally.
- Before each `develop→main` merge: full L1–L4 run + a QA smoke.
- The suite lives in `web/e2e/` and is wired into `web-ci.yml` (extend to gate PRs once stable).
