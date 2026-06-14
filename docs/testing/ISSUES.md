# ERPCLEAN2 — Test Issues Register

Issues observed when executing the test-case suite ([`test-cases/`](test-cases/)) via the automated
Playwright e2e run (see [`TESTING-STRATEGY.md`](TESTING-STRATEGY.md)). Each entry is **ready for
resolution**: it names the failing test, the exact location, the expected vs observed behaviour, and
evidence. Real product defects only — Playwright spec flaws are fixed in the spec, not logged here.

**Status legend:** OPEN · IN-PROGRESS · FIXED · WONTFIX · DUPLICATE
**Severity:** P1 (blocker) · P2 (major) · P3 (minor)
**Layer:** L1 auth/RBAC · L2 route-smoke · L3 conventions · L4 lifecycle flow

---

## Summary

| Run | Date | Layer | Tests | Passed | Failed | Real defects |
|---|---|---|---:|---:|---:|---:|
| Local Playwright | 2026-06-14 | L2 route-smoke (rootadmin) | 128 | 121 | 7 | 6 (1 flake) |
| Local API seeder | 2026-06-14 | API lifecycle | 32 modules | 25 | 7 | 7 |
| Local Playwright | 2026-06-14 | L3 conventions (C1+axe) | 256 | 246 | 10 | 10 (2 issues) |
| **Local Playwright (after fixes)** | 2026-06-14 | L2+L3 re-run | 384 | **380** | 4 | **2 P3** (+2 flake) |
| **Backend mvn clean verify (after fixes)** | 2026-06-14 | unit + IT | 748 | **748** | 0 | 0 |
| **Frontend ng test (after fixes)** | 2026-06-14 | vitest | 677 | **677** | 0 | 0 |
| **Final (after P3 follow-ups)** | 2026-06-14 | Playwright workers=4 (storageState) | 385 | **385** | 0 | **0** |
| **Final (after P3 follow-ups)** | 2026-06-14 | backend mvn clean verify | 748 | **748** | 0 | 0 |

**Totals: 15 documented defects** (5×P1, 7×P2, 3×P3) + 1 test-flake. P1s block POS, requisition-create,
CRM activity, supplier-quote, purchase-return. See entries below.

---

## RESOLUTION (2026-06-14) — all 15 fixed + verified (ISSUE-014 C1 resolved via a dedicated sweep)

> 14 functional defects (001–012, 015 + cascades) fixed + verified. **ISSUE-014 (C1 uid/id visibility)** was
> re-opened as systemic (the old gate gave a false "none") and is now **RESOLVED** by a codebase-wide sweep
> (~93 renders) + a real static gate (`npm run c1`, 0 offenders) + a runtime detail-route scan. Final
> Playwright vs a fresh backend: **513 / 0**. See the ISSUE-014 entry for details.

Fixed across branch `feat/e2e-playwright` (`f2684e2`) in 4 passes. **Verification gates green:**
- Backend `mvn clean verify` — **748 tests, 0 failures** (incl. new regression tests).
- Frontend `ng build` clean + `ng test` — **677 tests, 0 failures**.
- API re-seed (full lifecycle) — all modules green (POS till/session, stock-transfer, stock-count,
  crm.activity, supplier-quote, purchase-return, stock-location, ar/ageing). *(pos.sale returns 400
  only because the seeder runs as `rootadmin` with no agent — BR-SALES-06, correct behaviour, not a defect.)*
- Final Playwright re-run — **380 pass / 4 fail**, where 2 are login-flake (test-harness) and axe = **0**.

| Issue | Status | Fix |
|---|---|---|
| 001 requisition-create NG0203 | ✅ FIXED | `takeUntilDestroyed(inject(DestroyRef))` |
| 002 stock-valuation 403 | ✅ FIXED | be-services (perm/scope) — route now loads |
| 003 fx/rates iterator crash | ✅ FIXED | map the paged envelope to an array |
| 004 pos/sessions branchId 400 | ✅ FIXED | send active branchId |
| 005 ar/ageing customerId 400 | ✅ FIXED | `customerId` now optional (company-wide ageing) |
| 006 landed-cost ngModel name | ✅ FIXED | `name=` added |
| 007 crm.activity 500 (NOTE/TASK) | ✅ FIXED | assign activity_number before save |
| 008 pos.till 500 | ✅ FIXED | nullable `code` + wire `cashBankAccountId` (default cash acct) |
| 009 POS perm-code mismatch | ✅ FIXED | V83 seeds the checked `POS.*` codes + grants ORG_ADMIN |
| 010 supplier-quote 400 | ✅ FIXED | `companyUid` accessor on the DTO |
| 011 purchase-return confirm 500 | ✅ FIXED | AP debit-note GL post |
| 012 stock-location create | ✅ FIXED | default-location flush ordering |
| 014 C1 uid-visible | 🔶 **PARTIAL — RE-OPENED** | Only the targeted *list-screen ULID* cases fixed (pos/sessions, notification-deliveries, pos-tills, POS-variance memo, gl/journals, ap-supplier-bills, boms list). The C1 gate gave a false "none" — it skips detail routes + can't see numeric ids. **~32 templates still render raw uids + ~33 raw numeric ids. See re-opened ISSUE-014 below.** |
| 015 axe a11y (7 screens) | ✅ FIXED | removed prohibited aria / added labels — axe now 0 serious/critical |
| + pos.session 500 (cascade) | ✅ FIXED | generate `session_number` (new field + generator) |
| + stock-transfer in-transit 409 (cascade) | ✅ FIXED | seed in-transit location per company/branch (`StockLocationSeeder`) |

### Follow-ups — ✅ ALL RESOLVED (2026-06-14, branch `feat/test-followups`)
Confirmed by a clean final Playwright run: **385 pass / 0 fail** (axe serious: none · login-flake: none) +
backend `mvn clean verify` **748 / 0 failures** + API re-seed green.

> ⚠️ **Caveat on "C1 uid-visible: none" (corrected 2026-06-14):** that result means *none detected by the
> automated C1 gate* — but the gate is **blind** to most C1 violations (it skips all `/uid/:uid` detail
> routes and only matches 26-char ULIDs, not numeric ids). FOLLOW-001/002 (below) genuinely fixed their
> specific list screens, but the broad C1 convention is **NOT** resolved — see the re-opened **ISSUE-014**.
- **FOLLOW-001 (P3, C1) — GL journal memos embed source uids.** ✅ FIXED. Added document-number fields to
  all 8 outbox payloads (with backward-compat constructors for in-flight events), populated them in the 6
  publishers, and threaded a `docNumber` through every `InventoryGlPoster` method — memos now read e.g.
  "Goods receipt GRN-0001" (no ULID). The machine `sourceRef`/source-uid linkage is unchanged.
- **FOLLOW-002 (P3, C1) — AP enter-bill PO option shows uid.** ✅ FIXED. Option label = `orderNumber ??
  '(draft PO)'` (never the uid); also dropped the raw "Line UID" column from the 3-way-match table.
- **FOLLOW-003 (P3) — outbox redelivery idempotency.** ✅ FIXED. The stock-movement posting now checks for
  an existing movement by `source_event_uid` and treats a redelivery as an already-applied no-op success
  (no exception, no double-apply) so the event marks dispatched.
- **TEST-FLAKE (test-only) — login race.** ✅ FIXED. Added a Playwright `auth.setup.ts` + `storageState`
  project dependency (one login, reused by all specs); routes-smoke/conventions no longer log in per test.
  Result: 128/129-failing-under-workers=4 → **129/129 passing**, zero `/login` redirects.

Environment: backend dev profile :8081 (bootstrapped `rootadmin`), Postgres :5434, `ng serve` :4200,
seeded via `e2e/full-coverage-drive.js`. Evidence: `web/test-results/`, `web/pw-routes.json`,
`/tmp/local-seed-issues.json`.

---

## Open issues

### ISSUE-001 — Purchase Requisition create screen is broken (NG0203 takeUntilDestroyed)
- **Severity:** P1 · **Status:** OPEN · **Layer:** L2
- **Module / Route:** Procurement · `/admin/purchase-requisitions/create` · API `/api/v1/purchase-requisitions`
- **Found by:** `routes-smoke.spec.ts::route /admin/purchase-requisitions/create`
- **Observed:** console `ERROR RuntimeError: NG0203: takeUntilDestroyed() can only be used within an injection context`. Init throws → the create form does not function.
- **Expected:** screen loads and lets the user raise a requisition.
- **Suspected cause:** a `takeUntilDestroyed()` call outside the constructor/injection context in the requisition-create component (move to constructor or pass a `DestroyRef`).

### ISSUE-002 — Stock Valuation report returns 403 on load (even as rootadmin)
- **Severity:** P2 · **Status:** OPEN · **Layer:** L2
- **Module / Route:** Inventory · `/admin/stock/valuation` and `/admin/stock/valuation/opening` · API `GET /api/v1/stock/valuation/report`
- **Found by:** `routes-smoke.spec.ts::route /admin/stock/valuation*`
- **Role:** rootadmin (which normally bypasses RBAC)
- **Observed:** on load `GET /api/v1/stock/valuation/report` → **403** `"You do not have permission to perform this action."` → the report never renders for anyone.
- **Expected:** rootadmin (and `INVENTORY.VALUATION.VIEW` holders) see the valuation report.
- **Suspected cause:** permission-code mismatch (checked code not granted / not in root bypass) or a ScopeGuard denial when no active branch is set. Compare `StockValuationController` @PreAuthorize vs seeded codes (cf. ISSUE-009 POS pattern).

### ISSUE-003 — FX Rates screen crashes on render (TypeError: newCollection[Symbol.iterator])
- **Severity:** P2 · **Status:** OPEN · **Layer:** L2
- **Module / Route:** FX · `/admin/fx/rates` · API `/api/v1/fx` / `/api/v1/currencies`
- **Found by:** `routes-smoke.spec.ts::route /admin/fx/rates`
- **Observed:** console `ERROR TypeError: newCollection[Symbol.iterator] is not a function or its return value is not iterable` — an `@for` iterates a non-array (rates/currencies response shape ≠ expected iterable).
- **Expected:** rates list renders.
- **Suspected cause:** the fx-rates component binds `@for` to an object/paged envelope instead of an array.

### ISSUE-004 — POS Sessions list calls /pos/tills without branchId → 400
- **Severity:** P2 · **Status:** OPEN · **Layer:** L2
- **Module / Route:** POS · `/admin/pos/sessions` · API `GET /api/v1/pos/tills`
- **Found by:** `routes-smoke.spec.ts::route /admin/pos/sessions`
- **Observed:** on load `GET /api/v1/pos/tills?companyId=1` → **400** `Missing required request parameter: branchId`.
- **Expected:** tills load for the active branch.
- **Suspected cause:** the sessions component / `pos.service.listTills` omits `branchId`; send the active branch (or make it optional server-side).

### ISSUE-005 — AR Ageing screen calls /ar/ageing without customerId → 400
- **Severity:** P2 · **Status:** OPEN · **Layer:** L2
- **Module / Route:** AR · `/admin/ar/ageing` · API `GET /api/v1/ar/ageing`
- **Found by:** `routes-smoke.spec.ts::route /admin/ar/ageing`
- **Observed:** on load `GET /api/v1/ar/ageing?companyId=1` → **400** `Missing required request parameter: customerId`.
- **Expected:** company-wide ageing renders, or the call defers until a customer is picked.
- **Suspected cause:** `ArStatementController.getAgeing` requires `customerId`; make it optional (company-wide) or defer the call.

### ISSUE-006 — Landed Cost create: ngModel without name inside form (NG01352)
- **Severity:** P2 · **Status:** OPEN · **Layer:** L2
- **Module / Route:** Procurement · `/admin/landed-costs/create` · API `/api/v1/landed-costs`
- **Found by:** `routes-smoke.spec.ts::route /admin/landed-costs/create`
- **Observed:** console `ERROR NG01352: If ngModel is used within a form tag, either the name attribute must be set or the form control must be defined as 'standalone'`.
- **Expected:** form renders without console errors and binds correctly.
- **Suspected cause:** an `[(ngModel)]` input inside a `<form>` missing `name=` (or `[ngModelOptions]="{standalone:true}"`).

### ISSUE-007 — crm.activity create → 500 for NOTE & TASK (activity_number NOT NULL)
- **Severity:** P1 · **Status:** OPEN · **Layer:** API/L4
- **Module / Route:** CRM · `/admin/crm/activities` · API `POST /api/v1/crm/activities`
- **Found by:** `full-coverage-drive.js::crm.activity`
- **Observed:** create → **500**. NOTE/TASK types don't get an `activity_number`, violating the NOT NULL column.
- **Expected:** all activity types (CALL/EMAIL/MEETING/NOTE/TASK) create successfully.
- **Suspected cause:** number generator only runs for CALL/EMAIL/MEETING; extend to NOTE/TASK (or relax the column).

### ISSUE-008 — POS till create → 500 (schema gap, V43)
- **Severity:** P1 · **Status:** OPEN · **Layer:** API/L4
- **Module / Route:** POS · `/admin/pos/tills` · API `POST /api/v1/pos/tills`
- **Found by:** `full-coverage-drive.js::pos.till`
- **Observed:** create → **500**; cascades (sessions/sales unreachable). Schema gap in `pos_tills` (V43).
- **Expected:** a till is created.
- **Suspected cause:** missing/incorrect column in `pos_tills` vs `PosTill` entity; inspect V43.

### ISSUE-009 — POS permission codes don't match the seeded codes (all non-root POS denied)
- **Severity:** P1 · **Status:** OPEN · **Layer:** L1/RBAC
- **Module:** POS · API `/api/v1/pos/*`
- **Found by:** test-case authoring (`test-cases/07-pos.md` DEFECT-POS-PERM)
- **Observed:** controllers + FE check `POS.TILL.MANAGE` / `POS.SESSION.*` / `POS.SALE.CREATE`; V43 seeds `SALES.POS.*`; `PermissionResolver` exact-matches (`PermissionResolver.java:78`) → all non-root POS denied.
- **Expected:** a role granted POS permissions can use POS.
- **Suspected cause:** align seeded permission codes with the checked codes.

### ISSUE-010 — supplier-quote capture → 400 every call (DTO lacks companyUid())
- **Severity:** P1 · **Status:** OPEN · **Layer:** API/L4
- **Module / Route:** Procurement · `/admin/rfqs` (capture quote) · API `POST /api/v1/supplier-quotes`
- **Found by:** `full-coverage-drive.js::supplier-quote`
- **Observed:** capture → **400** every call. `@PreAuthorize("@perm.scoped(#req.companyUid(),...)")` calls `companyUid()` on `CaptureSupplierQuoteRequest`, which has no such accessor.
- **Expected:** a supplier quote is captured against an RFQ.
- **Suspected cause:** add `companyUid` to `CaptureSupplierQuoteRequest` (populate from the RFQ), or change the gate.

### ISSUE-011 — purchase-return confirm → 500 (AP debit-note GL post fails)
- **Severity:** P1 · **Status:** OPEN · **Layer:** API/L4
- **Module / Route:** Procurement · `/admin/purchase-returns` · API `POST /api/v1/purchase-returns/uid/{uid}/confirm`
- **Found by:** `full-coverage-drive.js::purchase-return`
- **Observed:** confirm → **500**; the AP debit-note GL posting fails.
- **Expected:** confirming a return posts the debit-note + stock-out cleanly.
- **Suspected cause:** debit-note GL poster (missing config account / unbalanced leg).

### ISSUE-012 — stock-location create fails (1 of 3) → cascades to transfer/count
- **Severity:** P2 · **Status:** OPEN · **Layer:** API/L4
- **Module / Route:** Inventory · `/admin/stock/locations` · API `POST /api/v1/stock-locations`
- **Found by:** `full-coverage-drive.js::stock-location` (2 pass, 1 fail)
- **Observed:** one location create failed; the missing 2nd location skipped in-transit transfer + stock-count. Re-run to capture the exact failing-create body.
- **Expected:** all location creates succeed (distinct codes/types).
- **Suspected cause:** likely duplicate-code/default-location or LocationType constraint on the 2nd/3rd create.

### ISSUE-013 — (flake, not a defect) smoke login test timed out under parallel load
- **Severity:** P3 · **Status:** WONTFIX (test-only) · **Layer:** L1
- **Found by:** `smoke.spec.ts::login and reach the admin home page`
- **Observed:** the single login assertion timed out once while 128 route tests logged in concurrently. **Not a product defect** — 121 route tests + the API seeder all authenticated fine. Mitigation: raise the login timeout / reduce workers for that spec.

### ISSUE-014 — C1: raw machine identifiers (uid + numeric id) shown in the UI — ✅ **RESOLVED**
- **Severity:** P2 (was filed P3) · **Status:** ✅ **RESOLVED (2026-06-14, branch `feat/c1-sweep`)** · **Layer:** L3 (convention C1)
- **Resolution:** swept ~93 raw machine-id renders across 22 areas (60 `…Uid` + 33 raw numeric `…Id`) to human
  name/number/code, name-resolvers (mirroring `branchDisplay()`/`glAccountLabel()`), routerLinks (uid only in
  the path), or dropped where no user value. Also fixed 4 `…Ref`/`journalEntryRef` *data*-fields that held a
  uid as their VALUE (journal-detail/account-ledger/wht-register `sourceRef`, cash-transfer `journalEntryRef`).
  **Gates added + green:** (1) a STATIC gate `web/scripts/c1-check.mjs` (`npm run c1`) scans every admin
  template for raw `…Uid`/`…Id` interpolation — **clean: 181 templates, 0 offenders** (was 78); (2) the
  runtime `conventions.spec.ts` now also visits each list's first-row DETAIL route (this caught the `sourceRef`
  data-value residual the static gate cannot see). Final Playwright vs a fresh backend: **513 pass / 0 fail**;
  `ng build` clean; `ng test` 678 green.
- **Backend follow-ups (optional, P3 — flagged by the sweep agents, NOT blocking C1):** a few DTOs could expose
  a human number/name so the FE shows it instead of dropping/linking — e.g. `FixedAssetDto.costCentreName`,
  `SupplierBillDto.supplierName`/`supplierCode`, `GeneratedDocumentDto` source name/number. Tracked separately.
- *(original report retained below for history)*

- **Severity (orig):** P2 (was filed P3) · **Layer:** L3 (convention C1)
- **Originally filed** as 3 list screens; **investigation 2026-06-14 found C1 is a codebase-wide pattern that the
  automated gate could not see** — so it was prematurely marked resolved. The list-screen ULID cases that were
  targeted ARE fixed; the broad convention is not.

**Why the C1 gate gave a false "none" (3 blind spots in `web/e2e/conventions.spec.ts`):**
1. **Skips every detail screen.** Line 19 `.filter(([p]) => p && !p.includes(':'))` drops all `/uid/:uid`
   routes — so no detail page is ever scanned, and detail pages are where raw uids are dumped into fields.
2. **ULID-only regex.** `ULID_RE = /\b[0-9A-HJKMNP-TV-Z]{26}\b/` (`_helpers.ts`) matches 26-char ULIDs only,
   so raw **numeric** FK ids shown as text are invisible to it (the rule covers ids too).
3. **First-paint, seed-dependent, top-level only.** Scans `body.innerText()` once after `networkidle+500ms`;
   an empty list, content behind a tab/modal, or a late async load passes vacuously.

**Actual scope (grep over `web/src/app/features/admin`, 2026-06-14):**
- **~32 templates render a raw `…Uid` as visible text** (22 of them detail screens). Examples:
  `ap-payment-detail` ({{p.glEntryUid}}, {{a.supplierBillUid}}), `bill-detail` (purchaseOrderUid, postedGlEntryUid),
  `ar-receipt-detail` (arInvoiceUid), `cash-transfer-detail` (source/destinationAccountUid),
  `approval-request-detail` (documentUid, sourcePolicyUid), `document-detail` (uid, sourceUid),
  `dimension-value-detail` (uid, dimensionUid, parentUid), `budget-detail`/`budget-version-detail`
  (fiscalYearUid, budgetUid), `depreciation-run-detail`/`payroll-run-detail`/`fixed-asset-detail` (glEntryUid,
  sourceBillUid), `bom-detail` (parentProductUid ×2, uid).
- **~33 raw numeric `…Id` renders** (not name-resolved). Examples: `supplier-bills-list` (supplierId),
  `ar-invoices-list` (customerId), `fixed-asset-detail` (Category/Branch/Cost-Centre ID), `loan-detail` &
  `pay-component-detail` (GL Account ID), `journal-entry-detail` (reversalOfId), `depreciation-run`
  (fiscalPeriodId, fixedAssetId), `document-detail`/`document-template-list` (brandingId).
  *(Renders that go through a name resolver — `branchDisplay()`, `glAccountLabel()`, `stageName()` — are
  CORRECT and excluded from this count.)*
- **Genuinely fixed (keep):** the list-screen ULID cases — gl/journals (memo→doc number), notification-deliveries,
  pos/sessions (sessionNumber), pos-tills, ap-supplier-bills (FOLLOW-002), boms list. These are real and stay fixed.

**Expected:** no raw uid OR raw numeric id in any visible cell/label/field — show a human name/number/code; keep
the machine identifier only in the row's `routerLink`/URL path.

**Fix scope (not yet done):** (a) broaden the C1 gate — include `/uid/:uid` detail routes (seed a known entity,
visit its detail), and flag raw numeric ids, not just ULIDs; (b) sweep the ~32 uid + ~33 id renders to use a
name/number/resolver (mirror the existing `branchDisplay()`/`glAccountLabel()` pattern); (c) re-run to confirm.
Estimated: a focused multi-agent sweep similar to the original rules-compliance pass.

### ISSUE-015 — Accessibility (C6): axe serious/critical violations on 7 screens
- **Severity:** P2 · **Status:** OPEN · **Layer:** L3 (convention C6 / WCAG 2.1 AA)
- **Found by:** `conventions.spec.ts::C6 axe a11y on ...`
- **Observed (rule → screens → node count):**
  - `aria-prohibited-attr` — `/admin/gl/accounts` (47), `/admin/products` (40), `/admin/crm/settings/pipeline-stages` (5), `/admin/cost-centre/dimensions` (2), `/admin/cash/accounts` (1)
  - `aria-allowed-attr` — `/admin/blanket-orders/create` (2), `/admin/goods-receipts/create` (1)
- **Expected:** zero serious/critical axe violations (WCAG 2.1 AA).
- **Suspected cause:** `aria-prohibited-attr` = an `aria-label`/`aria-*` placed on an element/role that doesn't permit it (commonly an `aria-label` on a non-interactive `<td>`/`<span>` or a div without a role). `aria-allowed-attr` = an aria attribute not allowed for the element's role. Fix by removing the prohibited aria or adding the correct role. The high counts on `gl/accounts`/`products` suggest one repeated template construct (likely an icon/badge pattern) — fixing the shared construct clears most nodes.

---

## Notes / next increments
- **L4 lifecycle flows (per-domain Playwright create→action journeys)** are the next layer to add — the
  data-driven L2/L3 + the API seeder already surfaced the issues above; L4 will deepen coverage of
  multi-step business flows (POS shift, O2C, P2P, journal post→reverse) once the P1s here are fixed
  (several flows are currently blocked by ISSUE-008/009/010/011).
- **RBAC per-role Playwright** (login as each seeded role; assert nav + forbidden routes) is the other
  pending layer; ISSUE-009 (POS perm-code mismatch) and ISSUE-002 (valuation 403) will be confirmed there.

---

## Pre-known issues to confirm in this run
Carried from the earlier QA e2e run + the test-case authoring pass — confirm they reproduce locally and
fold them into the numbered list above with evidence:

- **POS permission-code mismatch (P1)** — controllers/FE check `POS.*`; migration V43 seeds `SALES.POS.*`;
  `PermissionResolver` exact-matches → all non-root POS denied. (`test-cases/07-pos.md` DEFECT-POS-PERM.)
- **pos.till create → 500** (schema gap, V43).
- **stock-count create → 500** (schema gap).
- **crm.activity create → 500** for NOTE & TASK (`activity_number` NOT NULL not generated).
- **supplier-quote capture → 400** every call (DTO lacks `companyUid()` used by `@PreAuthorize`).
- **purchase-return confirm → 500** (AP debit-note GL post fails).
- **stock-transfer in-transit dispatch → 409** (no in-transit location seeded, V37).
- **ar/ageing screen broken on load** — calls `getAgeing` with no `customerId`, which the backend requires.
- **POS tender hardcoded CASH** (MOBILE_MONEY unreachable) and **POS agent @NotNull vs optional UI** →
  400 when omitted (DEFECT-POS-TENDER / DEFECT-POS-AGENT).
- **Convention (C1):** POS sessions list shows raw session uid; POS tills list shows raw branchId.

---

## Resolved (this cycle)
_(none yet)_
