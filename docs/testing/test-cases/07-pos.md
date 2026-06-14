# 07 — Point of Sale (POS) Test Cases

Scope: the full POS workflow — till setup; session lifecycle (open float → sell → payout → X-read → close[count cash] → reconcile[Z-read, variance, GL]); and the quick-sale/checkout (session + customer + agent, line items, tender, change). Verified against the real controllers, services, DTOs, enums, the V43 migration, and the Angular POS feature.
This domain has BOTH a backend (`PosTillController`, `PosSessionController`, `PosSaleController`) AND a frontend (`web/src/app/features/admin/pos/*`). Cases below are UI-driven (Playwright) where a screen exists and API-level where a behaviour is backend-only.

## Modules / submodules covered

| Submodule | Frontend route | API base path | Controller |
|---|---|---|---|
| POS Tills (list/create/deactivate) | `/admin/pos/tills` | `/api/v1/pos/tills` | `PosTillController` |
| POS Sessions (list/open) | `/admin/pos/sessions` | `/api/v1/pos/sessions` | `PosSessionController` |
| POS Session detail (X-read, payout, close, reconcile→Z-read) | `/admin/pos/sessions/uid/:uid` | `/api/v1/pos/sessions/uid/{uid}/...` | `PosSessionController` |
| POS Checkout / Sell | `/admin/pos/sell` | `/api/v1/pos/sales` | `PosSaleController` |

Frontend files: `web/src/app/features/admin/pos/pos-till-list.component.ts(.html)`, `pos-session-list.component.ts(.html)`, `pos-session-detail.component.ts(.html)`, `pos-sale.component.ts(.html)`, `pos.service.ts`, `models/pos.model.ts`. Nav group "Point of Sale" in `shell.component.ts` (lines 346–351). Routes in `admin.routes.ts` (lines 860–884), each `canActivate: [requirePermission(...)]`. The route guard (`permission.guard.ts`) REDIRECTS a user lacking the permission to `/admin/home` (it does not show a 403 page); the nav also hides the item.

## Permission codes in scope

### Codes the CODE checks (controllers `@PreAuthorize` + frontend `requirePermission`/`hasPermission`):

| Endpoint / UI action | Code checked in code |
|---|---|
| `POST /pos/tills` (create till) | `POS.TILL.MANAGE` |
| `GET /pos/tills/uid/{uid}` | `POS.TILL.VIEW` (scoped, target `postill`) |
| `GET /pos/tills` (list) | `POS.TILL.VIEW` |
| `DELETE /pos/tills/uid/{uid}` (deactivate) | `POS.TILL.MANAGE` (scoped) |
| `POST /pos/sessions` (open) | `POS.SESSION.OPEN` |
| `GET /pos/sessions/uid/{uid}` | `POS.SESSION.VIEW` (scoped, target `possession`) |
| `GET /pos/sessions` (list) | `POS.SESSION.VIEW` |
| `POST /pos/sessions/uid/{uid}/payouts` | `POS.SESSION.OPEN` (scoped) |
| `POST /pos/sessions/uid/{uid}/close` | `POS.SESSION.CLOSE` (scoped) |
| `GET /pos/sessions/uid/{uid}/x-read` | `POS.SESSION.VIEW` (scoped) |
| `POST /pos/sessions/uid/{uid}/reconcile` | `POS.SESSION.RECONCILE` (scoped) |
| `POST /pos/sales` (process sale) | `POS.SALE.CREATE` |

### Codes the DATABASE actually seeds (V43__pos.sql, granted to `ORG_ADMIN`):
`SALES.POS.TILL.MANAGE`, `SALES.POS.SESSION.OPEN`, `SALES.POS.SESSION.CLOSE`, `SALES.POS.SESSION.RECONCILE`, `SALES.POS.SELL`, `SALES.POS.REFUND`, `SALES.POS.VIEW`.

> **DEFECT-POS-PERM (P1, blocks ALL non-root POS use).** The permission codes the controllers and the frontend check (`POS.TILL.MANAGE`, `POS.TILL.VIEW`, `POS.SESSION.OPEN/VIEW/CLOSE/RECONCILE`, `POS.SALE.CREATE`) are NOT the codes the migration seeds (`SALES.POS.*` / `SALES.POS.SELL` / `SALES.POS.REFUND` / `SALES.POS.VIEW`). `PermissionResolver.hasPermission` does an exact `Set.contains(code)` with NO prefix normalisation (verified `PermissionResolver.java:78`). Net effect: with the SEEDED roles, every POS endpoint returns **403** and every POS nav item is **hidden / route redirects to /admin/home**, for ALL users EXCEPT `rootadmin` (root bypasses all checks). There is also no seeded code matching `POS.TILL.VIEW`, `POS.SESSION.VIEW`, `POS.SALE.CREATE` even after stripping the prefix (seed has `SALES.POS.VIEW` and `SALES.POS.SELL`, not granular VIEW/CREATE). Tests are written against the CODE-checked codes (what an enforced grant must contain); where seeded roles are exercised this defect is asserted as the expected (broken) outcome and called out.

## Type / role variations exercised

| Dimension | Values varied across cases |
|---|---|
| User role | `rootadmin` (superuser, only user that currently works end-to-end — see DEFECT-POS-PERM); a CUSTOM role granted the exact CODE-checked `POS.*` codes (manager-capable); a cashier-style CUSTOM role granted only `POS.SESSION.OPEN` + `POS.SALE.CREATE` + `POS.SESSION.VIEW`; `ACCOUNTANT`/`SALES_REP`/seeded roles (lack the granted POS codes → denied); NO-PERMISSION user (empty nav) |
| `PosSessionStatus` | OPEN → CLOSED → RECONCILED, plus every ILLEGAL transition |
| `PosPayoutType` | `PAID_OUT`, `REFUND` (both subtract from expected cash) |
| `TenderType` | POS sale path hardcodes `CASH` only (see DEFECT-POS-TENDER); `MOBILE_MONEY` is in the enum but unreachable via `/pos/sales` |
| Customer | `CASH_WALK_IN` vs `CREDIT_ACCOUNT`; `INDIVIDUAL` vs `BUSINESS` (POS accepts any active customer) |
| Variance | over (counted > expected → DR Cash / CR 4900 income), short (counted < expected → DR 5170 expense / CR Cash), exact (zero → no journal) |
| Branch / company | default vs non-default branch; single- vs multi-branch company; user assigned to the till's branch vs not (scope-denied); cross-tenant (company A cannot see company B sessions/tills) |

> **DEFECT-POS-TENDER (P2).** `PosSaleServiceImpl.processSale` hardcodes `new AddPaymentRequest(TenderType.CASH, grossTotal, ...)` (line 126) for the FULL gross. `PosSaleRequest` carries no tender type and the checkout UI has no tender selector, so the "tender CASH vs MOBILE_MONEY" requirement is **CASH-only** in POS today. `MOBILE_MONEY` (in `TenderType`) cannot be exercised through the POS sale endpoint. Cases assert CASH behaviour and flag MOBILE_MONEY as not-yet-supported.

> **DEFECT-POS-AGENT (P2).** `PosSaleRequest.agentId` is `@NotNull` (backend), but the checkout UI labels Agent "(optional)" and sends `agentId: undefined` when none is chosen (`pos-sale.component.ts:322`, `pos.model.ts: agentId?`). A POS sale with no agent will be rejected by bean-validation (`400`) even though the UI allows submitting it. Cases cover both an agent-present (happy) and agent-absent (expected-400) path.

> **CONVENTION-POS-UID (P3, C1 violation).** The POS Sessions list renders the raw session **uid** in a visible table column ("Session UID", `pos-session-list.component.html:136`), and the POS Tills list renders the raw numeric **branchId** in a visible column (`pos-till-list.component.html:152`). Both contradict C1 ("uid is a machine id, never shown; numeric id never shown"). Sale-session picker labels also embed `Till ${posTillId}` (raw id). Flagged in the relevant cases.

---

## TEST CASES

### TC-POS-001 — POS nav group renders for a user with POS view rights
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Shell nav "Point of Sale" (`shell.component.ts:346`)
- **Permission / Role:** items gated by `POS.SALE.CREATE` / `POS.SESSION.VIEW` / `POS.TILL.VIEW` — runs as `rootadmin` (or a CUSTOM role granted those exact codes); also as NO-PERMISSION user → group/items absent
- **Preconditions / Seed:** Logged-in session; active company + branch (X-Branch-Uid set).
- **Steps:**
  1. Log in as `rootadmin`; navigate to `/admin/home`.
  2. Open the sidebar; expand "Point of Sale".
  3. Assert three items: "Point of Sale" → `/admin/pos/sell`, "POS Sessions" → `/admin/pos/sessions`, "POS Tills" → `/admin/pos/tills` (`getByRole('link', { name: ... })`).
  4. Log out; log in as the NO-PERMISSION user; assert the "Point of Sale" group and all three items are NOT present.
- **Test Data:** rootadmin; a user with zero roles.
- **Expected Result:** Root sees all three POS nav links; no-perm user sees none.
- **Convention Assertions:** C3 (nav hidden without permission); C6 axe scan on the shell.
- **Negative / Edge:** A user with the SEEDED `ORG_ADMIN` role sees the items hidden too, because `ORG_ADMIN` holds `SALES.POS.*` not `POS.*` (DEFECT-POS-PERM) — assert this.

### TC-POS-002 — Direct navigation to a POS route without permission redirects to /admin/home
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** route guard `requirePermission` on `/admin/pos/*`
- **Permission / Role:** `POS.TILL.VIEW` / `POS.SESSION.VIEW` / `POS.SALE.CREATE` — runs as a user LACKING them
- **Preconditions / Seed:** Logged-in NO-PERMISSION (or seeded-only) user.
- **Steps:**
  1. As the no-perm user, navigate by URL to `/admin/pos/tills`.
  2. Assert the app lands on `/admin/home` (not a 403 page) — per `permission.guard.ts`.
  3. Repeat for `/admin/pos/sessions`, `/admin/pos/sessions/uid/SOMEUID`, `/admin/pos/sell`.
- **Expected Result:** Each guarded POS route redirects to `/admin/home`.
- **Convention Assertions:** C3 (guard backstops direct nav); C4 (no broken/forbidden screen leak).
- **Negative / Edge:** Confirm the redirect is silent (no error toast).

### TC-POS-003 — Create a POS till (manager)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** POS Tills (`/admin/pos/tills` · `POST /api/v1/pos/tills`)
- **Permission / Role:** `POS.TILL.MANAGE` — runs as `rootadmin` / CUSTOM-manager; also as cashier role lacking it → "New Till" button hidden and `POST` → 403
- **Variation:** branch = default
- **Preconditions / Seed:** A company with ≥1 active branch.
- **Steps:**
  1. Navigate `/admin/pos/tills`.
  2. If multi-company, select the company; observe Branch filter.
  3. Click "New Till"; in the create form type Till Name "Counter 1"; pick Branch via `<app-uid-picker>` (choose by branch NAME, e.g. "Head Office").
  4. Submit "Create Till".
  5. Assert success alert "Till created" and the new till appears in the table with Status badge ACTIVE.
- **Test Data:** name="Counter 1", branch = default branch by name.
- **Expected Result:** `201`, `PosTillDto {uid, name:"Counter 1", status:ACTIVE}`. Backend allocates a `code` server-side (UI only collects name + branch). Row shows in list.
- **Convention Assertions:** C1 (branch chosen via picker by NAME, not a typed uid; no till uid typed); C2 envelope; C3 RBAC (button hidden for non-manager); C6 axe; C9 (till is a master, ACTIVE on create).
- **Negative / Edge:** Empty name → inline "Till name is required."; no branch → "Branch is required."; `name` > 60 chars → `@Size(max=60)` 400; cashier role → button absent and forced `POST` returns 403.

### TC-POS-004 — Till list: company + branch scoping and "All branches"
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** POS Tills (`GET /api/v1/pos/tills?companyId&branchId`)
- **Permission / Role:** `POS.TILL.VIEW` — runs as a user assigned to the company
- **Variation:** multi-branch company
- **Preconditions / Seed:** Two branches each with ≥1 till.
- **Steps:**
  1. Navigate `/admin/pos/tills`.
  2. Select branch A in the Branch filter; assert only branch-A tills listed.
  3. Select branch B; assert only branch-B tills.
  4. Select "All branches" (empty value) → request omits `branchId`; assert tills from both branches.
- **Expected Result:** List filtered by company+branch; "All branches" lists all company tills.
- **Convention Assertions:** C7 (company+branch scoping); C4 (loading→idle); C1 — NOTE the Branch column shows the raw numeric branchId (CONVENTION-POS-UID, flag it).
- **Negative / Edge:** Company with no tills → empty state "No tills configured for this branch yet."

### TC-POS-005 — Till list four-state: loading / empty / error / forbidden
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** POS Tills (`/admin/pos/tills`)
- **Permission / Role:** `POS.TILL.VIEW`
- **Preconditions / Seed:** Network-stub each state.
- **Steps:**
  1. Loading: assert spinner output "Loading tills…" (role/aria-live) before the list resolves.
  2. Empty: company/branch with no tills → "No tills configured for this branch yet."
  3. Error: stub `GET /pos/tills` → 500; assert role=alert "Could not load tills. Please try again."
  4. Forbidden: stub → 403; assert "You don't have permission to view POS tills."
- **Expected Result:** Each of the four states renders its distinct treatment.
- **Convention Assertions:** C4 (four states distinct); C6 axe per state.
- **Negative / Edge:** companies fetch error shows the company-level error, not the list error.

### TC-POS-006 — Deactivate a till (soft-delete, not hard delete)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** POS Tills (`DELETE /api/v1/pos/tills/uid/{uid}`)
- **Permission / Role:** `POS.TILL.MANAGE` (scoped) — runs as manager; viewer role → action column hidden
- **Preconditions / Seed:** An ACTIVE till.
- **Steps:**
  1. Navigate `/admin/pos/tills`; locate the ACTIVE till row by its NAME.
  2. Click "Deactivate" (aria-label "Deactivate till Counter 1").
  3. Assert success alert "Till deactivated"; the row Status becomes INACTIVE and the Deactivate button no longer shows.
- **Expected Result:** Endpoint sets `status=INACTIVE` (verified `PosTillServiceImpl.deactivateTill` → `MasterStatus.INACTIVE`); record retained (soft-delete).
- **Convention Assertions:** C9 (soft-delete via MasterStatus, never hard delete); C1 (row found by name; deactivate targets the underlying uid, never shown/typed); C3 (button only for MANAGE).
- **Negative / Edge:** Deactivating a non-existent uid → 404 NotFound; an already-INACTIVE till has no Deactivate button (UI guards on `status==='ACTIVE'`).

### TC-POS-007 — Scoped till access: cannot view a till outside the active company (cross-tenant)
- **Type:** API (Manual/automated via REST)
- **Priority:** P1
- **Module / Submodule:** POS Tills (`GET /api/v1/pos/tills/uid/{uid}`)
- **Permission / Role:** `POS.TILL.VIEW` scoped (`@perm.scoped(#uid,'postill','POS.TILL.VIEW')`)
- **Variation:** cross-tenant
- **Preconditions / Seed:** Till T-A in company A; user U-B active in company B (holds POS.TILL.VIEW in B).
- **Steps:**
  1. As U-B (active company B), `GET /api/v1/pos/tills/uid/{T-A.uid}`.
- **Expected Result:** 403 (ScopeGuard denies — target not in active company). Root would succeed and trigger a ROOT.BYPASS audit row.
- **Convention Assertions:** C7 (tenant isolation); C3 (scoped permission).
- **Negative / Edge:** Same user in company A succeeds; deactivate (`POS.TILL.MANAGE` scoped) is likewise tenant-bound.

### TC-POS-008 — Open a POS session with an opening float (cashier)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** POS Sessions (`/admin/pos/sessions` · `POST /api/v1/pos/sessions`)
- **Permission / Role:** `POS.SESSION.OPEN` — runs as cashier/manager with the code; user lacking it → "Open Session" button hidden, `POST` → 403
- **Variation:** till on default branch; float = 100,000.00
- **Preconditions / Seed:** An ACTIVE till (TC-POS-003).
- **Steps:**
  1. Navigate `/admin/pos/sessions`; select company.
  2. Click "Open Session"; pick the Till via `<app-uid-picker>` (by till NAME; only ACTIVE tills are options); enter Opening Float "100000.00".
  3. Submit "Open Session".
  4. Assert success alert "Session opened" and a new row with Status OPEN.
- **Test Data:** till by name; openingFloatAmount=100000.00.
- **Expected Result:** `201`, session `status=OPEN`, `cashierId` = acting user, `openedAt` set. Cash sales total starts at 0; expected cash = opening float.
- **Convention Assertions:** C1 (till picked by name; session uid not typed — though it IS later shown in list, CONVENTION-POS-UID); C2 envelope; C3 RBAC; C8 (float formatted via decimal pipe 1.2-2); C6 axe.
- **Negative / Edge:** Float blank/negative → inline "Opening float must be a valid non-negative number." (UI) and `@DecimalMin("0.00")` 400 (API); no till selected → "Till is required."

### TC-POS-009 — One-open-session-per-till invariant (BR-SD-02)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** POS Sessions (`POST /api/v1/pos/sessions`)
- **Permission / Role:** `POS.SESSION.OPEN`
- **Preconditions / Seed:** Till T1 already has an OPEN session.
- **Steps:**
  1. Open a session on T1 (succeeds).
  2. Attempt to open a SECOND session on T1 while the first is OPEN.
- **Expected Result:** `409 Conflict` — "Till {uid} already has an OPEN session." (service check mirrors the DB partial unique index `ux_pos_session_one_open`). UI surfaces the message in the open-form error.
- **Convention Assertions:** C2 (errors[] envelope drives the message); C3.
- **Negative / Edge:** After the first session is CLOSED/RECONCILED, opening a new session on T1 succeeds.

### TC-POS-010 — Session list pagination + company scoping
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** POS Sessions (`GET /api/v1/pos/sessions?companyId&page&size`, returns ApiResponse with PageMeta)
- **Permission / Role:** `POS.SESSION.VIEW`
- **Preconditions / Seed:** ≥ 21 sessions in the company (size default 20 → 2 pages).
- **Steps:**
  1. Navigate `/admin/pos/sessions`.
  2. Assert the `<app-paginator>` shows First / Previous / page numbers / Next / Last.
  3. Click Next → page 2 loads (rows change), Previous returns to page 1, Last → final page.
  4. With ≤ 20 sessions, assert the paginator self-hides (1 page).
- **Expected Result:** Paged list; meta {page,size,totalElements,totalPages,hasNext} honoured; service uses `SKIP_UNWRAP` to read both data + meta.
- **Convention Assertions:** C5 (full paginator control set, self-hide on 1 page); C2 (meta preserved); C4.
- **Negative / Edge:** Company with no sessions → "No sessions yet. Open one above."; switching company resets to page 0.

### TC-POS-011 — Session list scoping: cross-tenant isolation
- **Type:** API / Playwright
- **Priority:** P1
- **Module / Submodule:** POS Sessions (`GET /api/v1/pos/sessions`)
- **Permission / Role:** `POS.SESSION.VIEW`
- **Variation:** cross-tenant
- **Preconditions / Seed:** Sessions exist in company A and company B; user holds POS.SESSION.VIEW in A only.
- **Steps:**
  1. As the company-A user, list sessions for `companyId=A` → only A's sessions.
  2. Request `companyId=B` → `assertCanActIn` denies → 403.
- **Expected Result:** A user only sees their active company's sessions; B's are 403.
- **Convention Assertions:** C7 tenant isolation; C3.

### TC-POS-012 — Session detail: header + live X-read on open session
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Session detail (`/admin/pos/sessions/uid/:uid` · `GET .../x-read`)
- **Permission / Role:** `POS.SESSION.VIEW` (scoped)
- **Preconditions / Seed:** An OPEN session with 0+ sales.
- **Steps:**
  1. From the session list click "View" on an OPEN row.
  2. Assert header shows Status OPEN, Opened At, Opening Float (monospace, 1.2-2).
  3. Assert the "X-Read (Live Totals)" card shows Sales Total, Payouts, Expected Cash, Invoice Count.
  4. Click the X-read refresh button; assert it reloads.
- **Expected Result:** `XReadDto {totalSalesAmount, totalPayoutsNetAmount, expectedCashAmount, invoiceCount}`. Expected = openingFloat + cashSales − payouts (verified `xRead`).
- **Convention Assertions:** C8 money formatting; C4 (X-read has its own loading/error sub-state); C6 axe; C1 (navigated by route uid path; on-screen the uid header isn't displayed but list column does — flag).
- **Negative / Edge:** X-read on a non-OPEN session → 409 "Session is not OPEN." (`requireOpen` in `xRead`); X-read fetch 500 → "Could not load X-read." sub-state.

### TC-POS-013 — Record a misc cash PAID_OUT payout on an open session
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Session detail payout (`POST .../uid/{uid}/payouts`)
- **Permission / Role:** `POS.SESSION.OPEN` (scoped) — payout reuses the OPEN permission, NOT a separate one
- **Variation:** payoutType = PAID_OUT
- **Preconditions / Seed:** OPEN session with known expected cash.
- **Steps:**
  1. Open session detail; click "Record Payout".
  2. Select Type "PAID_OUT"; Amount "5000"; Reason "Drawer-to-safe drop".
  3. Submit "Record".
  4. Assert success "Payout recorded — PAID_OUT — 5000"; the X-read auto-reloads and Payouts increases by 5000, Expected Cash decreases by 5000.
- **Expected Result:** `PosSessionPayout` persisted (type PAID_OUT, amount 5000); expected cash drops (all payouts subtract).
- **Convention Assertions:** C3 (gated by SESSION.OPEN); C8; C2 (no body returned — void; UI re-reads X-read).
- **Negative / Edge:** Amount ≤ 0 → "Enter a valid positive amount." (UI) / `@DecimalMin("0.01")` 400; blank reason → "Reason is required." (UI; reason is optional on the API but UI-required); payout on a CLOSED session → 409 "is not OPEN."

### TC-POS-014 — Record a REFUND payout
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Session detail payout (`POST .../payouts`)
- **Permission / Role:** `POS.SESSION.OPEN` (scoped)
- **Variation:** payoutType = REFUND
- **Preconditions / Seed:** OPEN session.
- **Steps:** As TC-POS-013 but Type "REFUND", Amount "2500", Reason "Customer cash refund".
- **Expected Result:** Payout stored type REFUND; like PAID_OUT it SUBTRACTS from expected cash (both are outflows — verified enum doc + close math).
- **Convention Assertions:** C8; C3.
- **Negative / Edge:** Both payout types reduce expected cash identically; the only difference is the type label/audit.

### TC-POS-015 — Close session: declare counted cash, compute variance (exact match)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Session detail close (`POST .../uid/{uid}/close`)
- **Permission / Role:** `POS.SESSION.CLOSE` (scoped) — "Close Session" button only when `canClose()`; cashier without CLOSE cannot close
- **Variation:** variance = 0 (counted == expected)
- **Preconditions / Seed:** OPEN session; read its X-read Expected Cash, e.g. 100000.00.
- **Steps:**
  1. Session detail; click "Close Session".
  2. Enter Counted Cash exactly equal to Expected (100000.00); optional Notes.
  3. Submit.
  4. Assert success "Session closed"; header Status → CLOSED; Counted Cash, Expected Cash shown; Variance badge shows 0.00 (success colour).
- **Expected Result:** `status=CLOSED`, `closedAt` set, `expectedCashAmount` = opening + cashSales − payouts, `varianceAmount` = counted − expected = 0.
- **Convention Assertions:** C8 money; C3 (CLOSE gating); C4; C9 (close is a state transition; no edit of postings).
- **Negative / Edge:** Counted blank/negative → "Enter a valid counted cash amount." (UI) / `@NotNull` 400 (API; note API has no min, negative is blocked by DB CHECK `counted_cash_amount >= 0`); closing an already-CLOSED session → 409 "is not OPEN."

### TC-POS-016 — Close session with cash OVER (counted > expected)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Session close (`POST .../close`)
- **Permission / Role:** `POS.SESSION.CLOSE`
- **Variation:** variance > 0 (over)
- **Preconditions / Seed:** OPEN session, expected = 100000.00.
- **Steps:** Close with Counted Cash 100500.00.
- **Expected Result:** `varianceAmount = +500.00`; Variance badge positive (info colour). No GL posted yet (posting happens at reconcile).
- **Convention Assertions:** C8; C9 (variance recorded, not yet posted).
- **Negative / Edge:** Compare to short case (TC-POS-017); badge colour differs.

### TC-POS-017 — Close session with cash SHORT (counted < expected)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Session close (`POST .../close`)
- **Permission / Role:** `POS.SESSION.CLOSE`
- **Variation:** variance < 0 (short)
- **Preconditions / Seed:** OPEN session, expected = 100000.00.
- **Steps:** Close with Counted Cash 99500.00.
- **Expected Result:** `varianceAmount = −500.00`; Variance badge negative (danger colour).
- **Convention Assertions:** C8.
- **Negative / Edge:** Sets up the SHORT reconcile GL case (TC-POS-020).

### TC-POS-018 — Reconcile a CLOSED session with zero variance → Z-read, NO journal
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Session reconcile (`POST .../uid/{uid}/reconcile`)
- **Permission / Role:** `POS.SESSION.RECONCILE` (scoped) — "Reconcile" button only when status==CLOSED && canReconcile()
- **Variation:** variance = 0
- **Preconditions / Seed:** A CLOSED session with variance 0 (TC-POS-015).
- **Steps:**
  1. Session detail (status CLOSED); click "Reconcile"; optional Notes; submit.
  2. Assert success "Session reconciled"; the Z-Read card appears (Opening Float, Sales Total, Payouts, Expected, Counted, Variance 0.00, Invoice Count).
  3. Assert header Status → RECONCILED.
- **Expected Result:** `status=RECONCILED`, `reconciledAt` set; variance 0 → NO journal posted (`varianceJournalId` null, no "Journal" row in Z-read). `ZReadDto` returned.
- **Convention Assertions:** C8; C3 (RECONCILE gating); C9 (append-only — reconcile posts a NEW journal only when needed, never edits).
- **Negative / Edge:** Reconcile button is absent on an OPEN session; reconciling an OPEN session via API → 409 "Session must be CLOSED before reconciliation."

### TC-POS-019 — Reconcile with cash OVER → posts variance journal (DR Cash / CR 4900)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Session reconcile (`POST .../reconcile`) + GL
- **Permission / Role:** `POS.SESSION.RECONCILE`
- **Variation:** variance = +500.00 (over)
- **Preconditions / Seed:** A CLOSED session with +500 variance (TC-POS-016); company has GL configs `CASH`, `POS_CASH_OVER`→4900 seeded (V43).
- **Steps:**
  1. Reconcile the CLOSED over-session.
  2. Assert Z-Read shows Variance +500.00 and a "Journal" row with a journal id.
  3. (GL verification) Open the posted journal: DR CASH 500 / CR "Cash Over (Till Surplus)" 4900 500, source type POS_VARIANCE, posting date = session close date, currency = company base (TZS).
- **Expected Result:** `varianceJournalId` set; balanced journal posted to GL (verified `postVarianceGl` over branch).
- **Convention Assertions:** C8 (currency = company base, not hardcoded); C9 (append-only GL); C2.
- **Negative / Edge:** If `POS_CASH_OVER` GL config is MISSING, reconcile FAILS FAST (exception propagates) and the session stays CLOSED — assert the error surfaces and status is NOT advanced.

### TC-POS-020 — Reconcile with cash SHORT → posts variance journal (DR 5170 / CR Cash)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Session reconcile (`POST .../reconcile`) + GL
- **Permission / Role:** `POS.SESSION.RECONCILE`
- **Variation:** variance = −500.00 (short)
- **Preconditions / Seed:** A CLOSED session with −500 variance (TC-POS-017); GL configs `CASH`, `POS_CASH_SHORT`→5170 seeded.
- **Steps:**
  1. Reconcile the short session.
  2. Assert Z-Read Variance −500.00 and a Journal id.
  3. (GL) Journal: DR "Cash Short / Till Shortage" 5170 500 / CR CASH 500, source POS_VARIANCE.
- **Expected Result:** Balanced short journal posted; `varianceJournalId` set; status RECONCILED.
- **Convention Assertions:** C8; C9.
- **Negative / Edge:** Missing `POS_CASH_SHORT` config → fail-fast, status stays CLOSED.

### TC-POS-021 — Illegal session transitions are rejected
- **Type:** API (Manual/automated)
- **Priority:** P1
- **Module / Submodule:** POS Sessions lifecycle (OPEN→CLOSED→RECONCILED)
- **Permission / Role:** appropriate codes per call (OPEN/CLOSE/RECONCILE)
- **Preconditions / Seed:** Sessions in each state.
- **Steps & Expected (one assertion per illegal edge):**
  1. RECONCILE an OPEN session → 409 "Session must be CLOSED before reconciliation."
  2. CLOSE a CLOSED session → 409 "is not OPEN."
  3. CLOSE a RECONCILED session → 409 "is not OPEN."
  4. RECONCILE a RECONCILED session → 409 "Session must be CLOSED..." (status != CLOSED).
  5. PAYOUT on a CLOSED or RECONCILED session → 409 "is not OPEN."
  6. X-READ on a CLOSED or RECONCILED session → 409 "is not OPEN."
  7. SELL (`/pos/sales`) on a CLOSED/RECONCILED session → 409 "POS session ... is not OPEN."
  8. Re-OPEN: there is NO endpoint to move a session backwards (no reopen) — confirm none exists.
- **Expected Result:** Each illegal transition returns 409 with the stated message; no state change.
- **Convention Assertions:** C2 (envelope errors); C9 (lifecycle is forward-only).
- **Negative / Edge:** The legal chain OPEN→CLOSE→RECONCILE always succeeds in order.

### TC-POS-022 — Quick sale (checkout): walk-in customer, single CASH line, change shown
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** POS Checkout (`/admin/pos/sell` · `POST /api/v1/pos/sales`)
- **Permission / Role:** `POS.SALE.CREATE` — runs as cashier with the code; user lacking it → "You don't have permission to create POS sales." panel and route redirect
- **Variation:** customer = CASH_WALK_IN (INDIVIDUAL); product = GOODS; tender = CASH; agent present
- **Preconditions / Seed:** An OPEN session; an active CASH_WALK_IN customer; an active sellable GOODS product with a sales price; an active agent.
- **Steps:**
  1. Navigate `/admin/pos/sell`; (multi-company) select company.
  2. Pick Session via `<app-uid-picker>` (only OPEN sessions listed; label "Session …(Till …)").
  3. Search + pick Customer by NAME (walk-in); search + pick Agent by NAME.
  4. Currency "TZS".
  5. "Add Line"; pick Product by name (base unit auto-fills); set Qty 2, Unit Price 1500, Discount 0. Assert line Subtotal 3,000.00 and footer Total "TZS 3,000.00".
  6. Tendered Amount 5000 → assert Change "TZS 2,000.00" (success colour).
  7. "Complete Sale".
  8. Assert success receipt: "Sale recorded!", Invoice number, "Total: TZS 3,000.00", a "View Invoice" link → `/admin/sales-invoices/uid/{uid}`, and a "New Sale" button.
- **Test Data:** qty 2 × 1500 = 3000; tendered 5000; change 2000.
- **Expected Result:** `201 SalesInvoiceDto` — origin POS, tagged `posSessionId`, FINALISED (status), CASH payment for full gross 3000, agentName/customerName enriched. Session cash-sales total increases (reflected in next X-read).
- **Convention Assertions:** C1 (session/customer/agent/product/unit all chosen via picker by NAME; no uid typed; resolved id sent under the hood — note: customer/agent are sent as numeric `customerId`/`agentId` resolved from the picked uid, not visible); C2 envelope; C3 RBAC; C6 axe; C8 money "TZS 3,000.00".
- **Negative / Edge:** see TC-POS-024..028.

### TC-POS-023 — Quick sale with a CREDIT_ACCOUNT (BUSINESS) customer
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** POS Checkout (`POST /pos/sales`)
- **Permission / Role:** `POS.SALE.CREATE`
- **Variation:** customer = CREDIT_ACCOUNT + BUSINESS; tender CASH (POS always takes full CASH)
- **Preconditions / Seed:** OPEN session; an active CREDIT_ACCOUNT BUSINESS customer.
- **Steps:** As TC-POS-022 but pick the credit/business customer.
- **Expected Result:** Sale succeeds; POS still records a full CASH payment for the gross (POS sale is cash-tendered regardless of the customer's credit terms — verified `processSale` adds CASH for grossTotal).
- **Convention Assertions:** C1 (picker by name); C8.
- **Negative / Edge:** Confirm POS does NOT create an open AR balance (full cash settled) for the credit customer in this flow.

### TC-POS-024 — Checkout validation: required fields and line validation
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** POS Checkout (`/admin/pos/sell`)
- **Permission / Role:** `POS.SALE.CREATE`
- **Preconditions / Seed:** OPEN session present.
- **Steps & Expected (each is an inline `role=alert` form error, no API call):**
  1. Submit with no session → "Session is required."
  2. Session but no customer → "Customer is required."
  3. Clear currency → "Currency is required."
  4. No line items → "Add at least one line item."
  5. A line with no product → "Select a product for every line."
  6. A line with no unit → "Select a unit for every line."
  7. Qty 0 or negative → "Quantity must be positive for every line."
  8. Unit price negative → "Unit price must be non-negative for every line."
- **Expected Result:** Each missing/invalid field blocks submit with the exact message; no POST fired.
- **Convention Assertions:** C4 (validation surfaced inline); C6 axe; C1 (pickers used for resource fields).
- **Negative / Edge:** Fixing each in turn eventually allows submit.

### TC-POS-025 — Checkout tender must cover total (change cannot be negative)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** POS Checkout
- **Permission / Role:** `POS.SALE.CREATE`
- **Preconditions / Seed:** Total = 3,000.00.
- **Steps:**
  1. Build a sale totalling 3000; enter Tendered 2000.
  2. Assert Change shows "TZS -1,000.00" in danger colour.
  3. Submit.
- **Expected Result:** Blocked client-side: "Tendered amount is less than the total." No POST.
- **Convention Assertions:** C8 (change formatting incl. negative); C4.
- **Negative / Edge:** Tendered blank/non-numeric/negative → "Tendered amount must be a valid non-negative number."; tendered == total → change 0.00, allowed.

### TC-POS-026 — Sale only allowed on an OPEN session
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** POS Checkout (`POST /pos/sales`)
- **Permission / Role:** `POS.SALE.CREATE`
- **Variation:** session = CLOSED
- **Preconditions / Seed:** A CLOSED session.
- **Steps:**
  1. In the session picker, CLOSED sessions are NOT listed (UI filters `status==='OPEN'`) — assert the closed session is absent.
  2. (API) Force `POST /pos/sales` with a CLOSED `sessionUid`.
- **Expected Result:** API → 409 "POS session {uid} is not OPEN." UI never offers it.
- **Convention Assertions:** C2 envelope; C3.
- **Negative / Edge:** Non-existent sessionUid → 404 "PosSession {uid}".

### TC-POS-027 — POS sale tender is CASH-only; MOBILE_MONEY not selectable (DEFECT-POS-TENDER)
- **Type:** Manual (gap verification)
- **Priority:** P2
- **Module / Submodule:** POS Checkout (`POST /pos/sales`) + `TenderType`
- **Permission / Role:** `POS.SALE.CREATE`
- **Preconditions / Seed:** OPEN session.
- **Steps:**
  1. Inspect the checkout UI — confirm there is NO tender-type selector (only "Tendered Amount").
  2. Inspect `PosSaleRequest` — confirm no tender field.
  3. Process a sale and inspect the created invoice's payment.
- **Expected Result:** Exactly one payment of `TenderType.CASH` for the full gross is recorded (`processSale` hardcodes CASH). `MOBILE_MONEY` is unreachable via POS. Record this as the known limitation, not a bug-of-the-test.
- **Convention Assertions:** none beyond documenting the gap.
- **Negative / Edge:** If MOBILE_MONEY POS is later required, both the DTO and UI need a tender selector.

### TC-POS-028 — POS sale with no agent is rejected by the backend (DEFECT-POS-AGENT)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** POS Checkout (`POST /pos/sales`)
- **Permission / Role:** `POS.SALE.CREATE`
- **Preconditions / Seed:** OPEN session; valid customer + line; NO agent selected.
- **Steps:**
  1. Build a valid sale but leave Agent (optional) empty.
  2. Submit "Complete Sale".
- **Expected Result:** UI passes client validation (agent is "optional") and POSTs `agentId: undefined`; backend rejects with `400` because `PosSaleRequest.agentId` is `@NotNull`. The error surfaces as "Could not process sale." (or the bean-validation message). Assert the mismatch.
- **Convention Assertions:** C2 (400 validation envelope); C4 (error surfaced).
- **Negative / Edge:** Selecting any active agent makes the same sale succeed (TC-POS-022).

### TC-POS-029 — Price/line behaviour: discount and multi-line totals
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** POS Checkout line items
- **Permission / Role:** `POS.SALE.CREATE`
- **Preconditions / Seed:** OPEN session; 2 sellable products.
- **Steps:**
  1. Add line A: Qty 3 × 1000, Discount 200 → line subtotal 2,800.00.
  2. Add line B: Qty 1 × 500, Discount 0 → 500.00.
  3. Assert footer Total "TZS 3,300.00"; Tendered 4000 → Change "TZS 700.00".
  4. Remove line B → Total recomputes to 2,800.00.
- **Expected Result:** Client subtotal = Σ(qty×price − discount); server validates the client-submitted unitPrice against list price (per DTO comment) and computes authoritative gross on the invoice.
- **Convention Assertions:** C8; C6 axe (table caption "Sale line items", scoped headers).
- **Negative / Edge:** Per-line discount exceeding line value yields a negative line subtotal in the UI preview — confirm the server's invoice math/validation governs the final figure.

### TC-POS-030 — Cashier vs Manager capability split
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** all POS screens
- **Permission / Role:** mixed POS.* codes
- **Preconditions / Seed:** Two CUSTOM roles — (a) Cashier = `POS.SESSION.OPEN` + `POS.SALE.CREATE` + `POS.SESSION.VIEW`; (b) Manager = all POS.* including `POS.TILL.MANAGE`, `POS.SESSION.CLOSE`, `POS.SESSION.RECONCILE`.
- **Steps:**
  1. As Cashier: can open a session, ring sales, view sessions/X-read; on session detail the "Close Session" and "Reconcile" buttons are HIDDEN; the Tills "New Till"/"Deactivate" controls are hidden (and `/admin/pos/tills` accessible only if granted POS.TILL.VIEW — Cashier lacks it → redirect).
  2. As Manager: can create/deactivate tills, close and reconcile sessions.
- **Expected Result:** UI controls and routes reflect the held permission codes; server enforces independently (forced calls without the code → 403).
- **Convention Assertions:** C3 (per-action gating, button-level + route-level + API-level); C6 axe.
- **Negative / Edge:** Cashier forcing `POST .../close` → 403; Manager-only actions blocked for Cashier at the API even if a button were reachable.

### TC-POS-031 — Branch-scope: acting in a branch the user is not assigned to is denied
- **Type:** API (Manual/automated)
- **Priority:** P1
- **Module / Submodule:** POS session/till operations (ScopeGuard via X-Branch-Uid)
- **Permission / Role:** holds POS codes but assigned to branch X only
- **Variation:** till on branch Y; user assigned branch X
- **Preconditions / Seed:** Multi-branch company; till T-Y on branch Y; user U holds POS.SESSION.OPEN but is assigned only to branch X (acts with X-Branch-Uid = X).
- **Steps:**
  1. As U (active branch X), attempt to open a session on till T-Y (branch Y).
- **Expected Result:** Permission resolution is per active (user, company, branch); acting outside the assigned/active branch is denied — `assertCanActIn`/effective-permission set returns no grant → 403. (Note: `assertCanActIn` is company-level in the service; branch enforcement is via the resolved permission set for the active branch — assert the user cannot operate the off-branch till.)
- **Convention Assertions:** C7 (branch scoping); C3.
- **Negative / Edge:** Switching the active branch to Y (if assigned) enables the operation; a user assigned to ALL branches can operate any till in the company.

### TC-POS-032 — Session detail four-state (loading / error / forbidden / loaded)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Session detail (`/admin/pos/sessions/uid/:uid`)
- **Permission / Role:** `POS.SESSION.VIEW`
- **Preconditions / Seed:** stub responses.
- **Steps:**
  1. Loading: assert "Loading session…" output (aria-live).
  2. Error: stub session GET 500 → role=alert "Could not load session. Please try again."
  3. Forbidden: stub 403 → "You don't have permission to view this session."
  4. Loaded: normal render with X-read sub-card.
- **Expected Result:** Four distinct states (note: there is no "empty" for a single-entity detail).
- **Convention Assertions:** C4; C6 axe each state.
- **Negative / Edge:** A reconciled session shows Closed At + Reconciled At + Variance but no action buttons.

### TC-POS-033 — End-to-end POS day: open → sell → payout → X-read → close → reconcile
- **Type:** Automated (Playwright) — flagship happy path
- **Priority:** P1
- **Module / Submodule:** full POS workflow
- **Permission / Role:** runs as `rootadmin` (the only currently-working principal given DEFECT-POS-PERM) OR a CUSTOM role granted all CODE-checked `POS.*` codes
- **Variation:** product GOODS; customer CASH_WALK_IN; one PAID_OUT payout; resulting cash OVER variance
- **Preconditions / Seed:** Active till; active GOODS product (price 1500); active walk-in customer; active agent; GL configs CASH/POS_CASH_OVER seeded.
- **Steps:**
  1. `/admin/pos/sessions` → Open Session on the till, float 50,000.
  2. `/admin/pos/sell` → ring a sale: 2 × 1500 = 3,000, tendered 3,000, change 0; complete. (cash sales 3,000)
  3. Back to session detail → Record Payout PAID_OUT 1,000 reason "petty". Expected cash now 50,000 + 3,000 − 1,000 = 52,000.
  4. X-read: assert Sales Total 3,000, Payouts 1,000, Expected Cash 52,000, Invoice Count 1.
  5. Close Session with Counted Cash 52,100 → Variance +100.00 (OVER).
  6. Reconcile → Z-Read shows Variance +100.00 + Journal id; Status RECONCILED.
  7. (GL) Verify DR Cash 100 / CR 4900 100 POS_VARIANCE journal.
- **Expected Result:** Each step advances state correctly; figures tie out; lifecycle ends RECONCILED with a balanced variance journal.
- **Convention Assertions:** C1 (all resources via pickers/route by name/uid-path, none typed); C2; C5 (session list paginator present); C6 axe at each screen; C8 money throughout; C9 (forward-only lifecycle, append-only GL).
- **Negative / Edge:** Re-running step 5/6 (close/reconcile again) → 409 (covered TC-POS-021).

### TC-POS-034 — Audit trail is written for each POS action
- **Type:** API / Manual
- **Priority:** P3
- **Module / Submodule:** POS audit (`AuditService` events)
- **Permission / Role:** acting user with the relevant POS codes
- **Preconditions / Seed:** Perform till create, session open, payout, close, reconcile, sale.
- **Steps:**
  1. After each operation, inspect the audit log for the matching action.
- **Expected Result:** Actions recorded — `POS.TILL.CREATE`, `POS.TILL.DEACTIVATE`, `POS.SESSION.OPEN`, `POS.SESSION.PAYOUT`, `POS.SESSION.CLOSE`, `POS.SESSION.RECONCILE`, `POS.SALE.FINALISE` (verified `AuditActions`), each with entity table + uid + detail (e.g. variance, gross, payout type/amount).
- **Convention Assertions:** C9 (audit append-only).
- **Negative / Edge:** Root actions acting cross-company also emit a ROOT.BYPASS row.

### TC-POS-035 — Reconcile is idempotent-safe against missing notes / double reconcile
- **Type:** API
- **Priority:** P3
- **Module / Submodule:** Session reconcile (`POST .../reconcile`)
- **Permission / Role:** `POS.SESSION.RECONCILE`
- **Preconditions / Seed:** A CLOSED session.
- **Steps:**
  1. Reconcile with an EMPTY body (`ReconcileSessionRequest` notes is the only field, optional) → succeeds; notes unchanged.
  2. Reconcile the SAME (now RECONCILED) session again → 409 "Session must be CLOSED before reconciliation."
- **Expected Result:** First reconcile OK with no notes; second blocked (already RECONCILED). Variance computed server-side; no client variance input.
- **Convention Assertions:** C2; C9 (no re-post of variance).
- **Negative / Edge:** Notes provided only on first reconcile persist; the variance journal is posted at most once.

---

> Coverage map (controller endpoint → cases):
> - `POST /pos/tills` → 003 (and 030 RBAC, 031 branch); `GET /pos/tills` → 004,005,030; `GET /pos/tills/uid` → 007; `DELETE /pos/tills/uid` → 006,007.
> - `POST /pos/sessions` → 008,009,031,033; `GET /pos/sessions` → 010,011; `GET /pos/sessions/uid` → 012,032,033; `POST .../payouts` → 013,014,021; `POST .../close` → 015,016,017,021; `GET .../x-read` → 012,021,033; `POST .../reconcile` → 018,019,020,021,033,035.
> - `POST /pos/sales` → 022,023,024,025,026,027,028,029,033.
> - Lifecycle transitions (legal + illegal) → 008/015/018/033 (legal) and 021 (all illegal).
> - Permission/RBAC (allowed + denied, button/route/API) → 001,002,003,006,008,030,031; cross-tenant 007,011; DEFECT-POS-PERM called out in 001.
> - Four states → 005 (till list), 010/032 (sessions), plus per-screen axe.
> - Pagination/filter/search → 010 (paginator), 004 (branch filter), 022/029 (picker search).
> - Enum behaviour → PosPayoutType 013/014; PosSessionStatus 021/033; TenderType 027; customer kinds 022/023.
