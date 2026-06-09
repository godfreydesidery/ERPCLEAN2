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
| 6 | HARNESS | **FIXED** | E2E driver (`e2e/qa-ui-drive`) | **Loop re-created records (2× per item).** The customers/suppliers loop navigates to the list ONCE then loops toggle-fill-save without re-navigating; the form state carried over so the create sequence effectively ran ~twice → 100 customer rows for 50 intended names (timestamps 28s apart, non-adjacent codes → NOT a double-submit). **App was correct** (100 unique, contiguous codes CUST-0001..0100). Fix the driver: re-open a fresh form per record / assert the row landed before the next. | QA Playwright run 2026-06-08; createdAt analysis. |
| 7 | HARNESS | **FIXED** | E2E driver | **Unit create step wrong + unnecessary.** Driver clicked `New Unit of Measure` (actual button is `New Unit`) AND the fresh bootstrap **pre-seeds 17 units** — so unit creation should be skipped entirely. 3 false HIGHs. | qa-drive.js units phase. |
| 8 | HARNESS | **FIXED** | E2E driver | **User create reported failure but 4/5 persisted.** The user form's submit button doesn't match the generic `button:has-text("Save")` Save locator → 30s timeout, logged HIGH — but qauser1–4 were actually created. Driver needs the user form's real submit selector + verify-by-list instead of button-wait. | qa-drive.js users phase vs API count. |

## Application verdict from the QA UI run (2026-06-08, fresh DB, real typed entry)

**The application behaved correctly for every record the UI actually submitted.** All "failures" in
the Playwright run were **test-harness defects** (#6–#8 above), not product bugs:
- Customer codes `CUST-0001..0100`: **100 unique, fully contiguous** — per-company `code_sequence`
  held under rapid UI creates. Products 20, suppliers 19, routes 5, price-list 1, users 4 persisted.
- **Zero console errors, zero API 5xx** across the entire browser session.
- Login, navigation, and every create form that the driver targeted correctly **rendered and saved**.

Net: real browser data entry against the deployed stack is **functionally sound**. The harness needs
the three fixes above before the next UI run (per-record fresh form + correct unit/user selectors).
Data left on QA for tester inspection (fresh bootstrap + this run's typed data).

## Corrected UI run (2026-06-08, fresh DB, `e2e/qa-ui-drive.js`) — CLEAN

Harness issues #6–#8 fixed (+ a 4th found & fixed: the route Save is a `type="button"`
`(click)="create()"`, not a form submit, so Enter doesn't submit it — must click Save). Re-deployed
QA fresh and re-ran 100% typed UI entry. **Exact counts, no doubling, no app issues:**

| Entity | Intended | Persisted on QA | |
|---|---|---|---|
| Customers | 50 | **50** (codes CUST-0001..0050, unique, contiguous) | ✓ |
| Products | 10 | **10** | ✓ |
| Suppliers | 10 | **10** | ✓ |
| Users | 5 | **5** (+rootadmin = 6) | ✓ |
| Routes | 3 | **3** (ROUTE-0001..0003) | ✓ |
| Price lists | 1 | **1** | ✓ |

**0 console errors, 0 API 5xx.** The doubling (#6) is gone (per-record fresh-form + wait-for-close),
units skipped (#7), users create cleanly (#8), routes fixed (click Save). Driver issues #6–#9 are
now **FIXED in `e2e/qa-ui-drive.js`**. Application verdict stands: real browser data entry is sound.

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

## GL online E2E (2026-06-09, fresh QA deploy of feat/gl-module)

Deployed GL (V10) fresh to QA, then ran the master-data UI E2E (`qa-ui-drive.js` — 0 issues,
50 customers/10 suppliers/10 products/5 users/3 routes all typed via UI) and a new GL UI E2E
(`gl-ui-drive.js`). **GL acceptance bar PASSED live:** Chart of Accounts shows the 13 seeded TZ
accounts; a balanced manual journal (DR 50,000 / CR 50,000) posted through the post-journal editor
(balance indicator + Post-enable worked); the **trial balance then showed Balanced with the 50,000**.

| # | Sev | Status | Area | Issue | Evidence |
|---|-----|--------|------|-------|----------|
| 10 | MEDIUM | **FIXED** | web / GL trial balance | `trial-balance.component.html` assumed money fields are **strings** (`row.net.startsWith('-')`, `row.totalDebit !== '0.00'`), but BigDecimal serializes as a JSON **number** on the wire → `TypeError: net.startsWith is not a function` threw mid-row, **blanking the per-row DEBIT/CREDIT/NET cells** (footer Totals + Balanced banner still rendered, computed separately). The unit spec mocked `net` as a string so it never caught it. | Found by `gl-ui-drive.js` (console error + screenshot showing empty NET column). Fixed: number-safe `+row.net < 0` / `+row.totalDebit !== 0`; added a render-with-numeric-money regression test to trial-balance.component.spec. |

Note (latent, LOW): the GL DTO money fields are typed `string` in the Angular models but arrive as
numbers — other GL screens coerce with `parseFloat`/`Number.parseFloat(String(..))` so they're safe;
only the trial-balance template assumed string. Consider normalising money to a single wire type
(string everywhere, per the Long-as-string convention) — recorded for later, not blocking.

## How to reproduce

See [`e2e/README.md`](../../e2e/README.md). Scripts: `e2e/seed-and-flow.js`, `e2e/qa-ui-drive.js`
(typed master-data UI entry), `e2e/gl-ui-drive.js` (GL: post a balanced journal + verify trial
balance), `e2e/ui-smoke.js` (browser smoke), `e2e/static-proxy-server.js` (SPA+API origin).
