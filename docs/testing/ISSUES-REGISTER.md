# Issues Register — surfaced during verification & E2E

Running log of issues found while verifying the system (browser-verify, security review, and the
large-scale E2E seed/flow run). Captured here to be **worked on later** — not all are fixed.
Newest first. Severity: BLOCKER > HIGH > MEDIUM > LOW. Status: OPEN / FIXED / WONTFIX.

| # | Sev | Status | Area | Issue | Evidence / where |
|---|-----|--------|------|-------|------------------|
| 1 | HIGH | **FIXED** | Outbox / Stock | `DomainEventDispatcher.poll()` called `this.dispatchOne()` as a self-invocation, bypassing the Spring proxy → `@Transactional(REQUIRES_NEW)` never engaged → `MANDATORY` stock handlers threw "no existing transaction" → **stock never moved at runtime**. All 371 unit tests passed (they called `dispatchOne` directly, masking it). | Found by browser/API verify; fix `fix(outbox)` commit `4f61037`, regression test `poll_dispatchesMandatoryHandler_throughProxyTransaction`. |
| 2 | MEDIUM | **FIXED** | API / error handling | Malformed requests (missing required `@RequestParam`, path-var type mismatch, unreadable body) fell through to the catch-all → generic **500** with no logged stack, instead of **400**. Surfaced via `GET /companies` with no `organisationUid`. | Fix `fix(api)` commit `75336e1`; handler added to `GlobalExceptionHandler`; regression `rootToken_listCompanies_missingOrganisationUid_returns400`. |
| 3 | LOW | OPEN | API consistency | **DTO company-reference inconsistency.** Parties create DTOs (`CreateCustomerRequest`, `CreateSupplierRequest`, `CreateAgentRequest`) take **`companyId` (Long)**, while newer masters (`products`, `price-lists`, `routes`, `units`) take **`companyUid` (String)**. Wire convention is uid-in-body; parties are un-retrofitted. Confusing for API consumers; worth harmonising to `companyUid`. | Observed building the E2E seeder (had to handle both shapes). Pre-existing (Parties module, ADR-0006). |
| 4 | LOW | OPEN | Observability | The catch-all `@ExceptionHandler(Exception.class)` in `GlobalExceptionHandler` returns a generic 500 **without logging the exception/stack** (there's a `TODO(logging)`). Any unexpected 500 is invisible in logs — made diagnosing #2 harder. Should log at ERROR with the exception before returning the safe envelope. | `GlobalExceptionHandler` catch-all; noticed during #2 diagnosis. |
| 5 | INFO | OPEN | Test integrity | Outbox/stock unit tests drive `dispatchOne()` directly (inside a test TX), which **masked #1**. The scheduled `poll()` path was untested until the new regression test. Audit other "drive the internal method directly" tests for the same blind spot (anything relying on an ambient TX the real entrypoint must open). | Root cause analysis of #1. |

## E2E run summary (2026-06-08, throwaway stack, main @ db46205 + fixes)

**Result: PASS — 0 BLOCKER / 0 HIGH / 0 MEDIUM / 0 LOW.**

- **Scale seeded:** 100 users (branch-assigned + role-granted), 1000 customers, 50 suppliers,
  50 products (priced), 20 EXTERNAL agents, 10 routes (agent+customer assigned), 6 branches,
  1 operator role (35 perms).
- **RBAC / multi-actor:** rootadmin bootstrapped, then a **non-root operator** created the entire
  catalogue + parties and ran the full purchase→stock→sale loop on its branch (root stepped back).
- **Correctness asserted:** customer count = 1000 ✓; stock math `received 1000 − sold 40 = 960`
  on-hand exact ✓; invoice numbers unique ✓.
- **Performance (informal):** create latency flat under load — customer avg **12 ms** / max 41 ms
  across 1000; product 19.8 ms; supplier 12.5 ms. Outbox kept pace.

### Coverage gaps / things this run did NOT exercise (candidates for a future pass)

- **Cross-tenant isolation at scale** — the run used a single company; a 2nd company + 2nd operator
  would assert no cross-company leakage on list/search (the F12–F16 class) under volume. The
  automated ITs cover this functionally; an at-scale check would be additive.
- **Concurrency** — actors ran sequentially. Parallel non-root operators on the same branch/product
  would exercise stock-on-hand row contention + the `@Version` optimistic-lock paths.
- **Negative/RBAC-denial paths at scale** — verifying a user WITHOUT a permission is blocked
  (covered by ITs; not re-checked here).
- **List/search pagination & filtering** with 1000+ rows — only count was asserted; response-shape
  and deep-page latency not profiled.
- **Goods-receipt partial / over-receipt, sale void → stock restore** at scale — single-shot only.

## How to reproduce

See [`e2e/README.md`](../../e2e/README.md). Scripts: `e2e/seed-and-flow.js` (this run),
`e2e/ui-smoke.js` (browser smoke), `e2e/static-proxy-server.js` (SPA+API origin).
