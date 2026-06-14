# TAX (VAT + WHT) — Test Cases

Exhaustive UI-driven test cases for the Tax domain: VAT return lifecycle (open → recompute → file),
VAT adjustments (add/remove), WHT type (rate master) CRUD, and the WHT register / certificate view.
All cases are written against the deployed QA Angular app (route-driven, role/label interaction, picker-by-name,
four screen states, pagination, axe), and assert the system conventions C1–C9.

## Modules/submodules covered

| Submodule | Frontend route | API base path | Controller |
|---|---|---|---|
| VAT Returns list + open (new) | `/admin/tax/vat-returns` | `POST/GET /api/v1/vat/returns` | `VatReturnController` |
| VAT Return detail (recompute / file / face) | `/admin/tax/vat-returns/uid/:uid` | `POST /api/v1/vat/returns/uid/{uid}/recompute`, `POST .../file`, `GET .../uid/{uid}` | `VatReturnController` |
| VAT Adjustments (add/remove/list) | embedded in VAT Return detail screen | `POST/GET /api/v1/vat/returns/uid/{returnUid}/adjustments`, `DELETE .../uid/{uid}` | `VatAdjustmentController` |
| WHT Types (rate master CRUD) | `/admin/tax/wht-types` | `POST/GET/PUT /api/v1/wht/types`, `POST .../uid/{uid}/deactivate` | `WhtTypeController` |
| WHT Register / certificates (period query) | `/admin/tax/wht-register` | `GET /api/v1/wht/register` | `WhtRegisterController` |

Navigation: the "Accounting" shell group exposes three Tax items — **VAT Returns** (`/admin/tax/vat-returns`, perm `VAT.VIEW`),
**WHT Types** (`/admin/tax/wht-types`, perm `WHT.VIEW`), **WHT Register** (`/admin/tax/wht-register`, perm `WHT.VIEW`)
(verified in `web/src/app/layout/shell/shell.component.ts`).

### Backend-only-with-no-UI note (verified — do NOT write UI cases for these)
- **WHT capture / certificate issuance** is an internal service (`WhtCaptureService.captureOnPayment` / `captureOnReceipt`).
  It has **NO controller and NO standalone UI** — WHT certificates are created as a side-effect of an AP payment / AR receipt
  in the AR/AP modules, and surface read-only in the WHT Register. There is no screen to "capture WHT" directly.
  Register rows are therefore seeded via AP-payment / AR-receipt flows (or via DB/API in those modules), not via a tax screen.
- **VAT adjustments** are not a top-level route; they live **inside** the VAT Return detail screen.
- The VAT computation source (output/input figures from sales/purchases) is read by `VatReturnComputationReader`;
  there is no UI to edit the computed band figures — only **Recompute** re-reads them.

## Permission codes in scope (exact, seeded in `V14__vat_return.sql`)

| Code | Module | Grants |
|---|---|---|
| `VAT.VIEW` | tax | View VAT returns (face + band breakdown) and the WHT register |
| `VAT.RETURN.PREPARE` | tax | Open / compute / recompute a DRAFT VAT return |
| `VAT.RETURN.FILE` | tax | File a VAT return — the lock + the synchronous GL settlement post |
| `VAT.ADJUST` | tax | Add / remove adjustment lines on a DRAFT return |
| `WHT.VIEW` | tax | Read WHT transactions / the WHT register |
| `WHT.MANAGE` | tax | Manage WHT rates/types (capture WHT / issue certificates is server-side) |

> RBAC is by permission CODE, never role name. `@PreAuthorize` uses `@perm.has('CODE')` for company-list reads
> and `@perm.scoped(<uid>,'<resourceType>','CODE')` for scoped actions (company / vatreturn / whttype).

## Enums in scope (exact values, read from source)

- `VatReturnStatus` = **{ DRAFT, FILED }** (only two states; no CANCELLED/SUBMITTED).
- `VatAdjustmentReason` = **{ BAD_DEBT_RELIEF, PRIOR_PERIOD_CORRECTION, CREDIT_NOTE_VAT, DEBIT_NOTE_VAT, OTHER }**.
- `VatAdjustmentSign` = **{ INCREASE, DECREASE }** (INCREASE adds to net; DECREASE subtracts; `signedAmount()` applies the sign).
- `WhtKind` = **{ WHT_ON_PAYMENT, WHT_ON_RECEIPT }** (we withhold on supplier payments / customer withholds on our receipts).
- `VatStatus` (product VAT band) = **{ STANDARD, ZERO_RATED, EXEMPT }** — drives which output band a line lands in; STANDARD bears 18% TZ VAT, ZERO_RATED is taxable at 0%, EXEMPT is out of the net. (Bands are computed, not directly entered on the return.)

## Core formulas / business rules (asserted by cases)

- **Net VAT** = `outputVat − inputVat + adjustmentsTotal − openingCredit` (`VatReturnServiceImpl.compute`).
- **Closing credit** = `max(−netVat, 0)`.
- **Opening credit (carry-forward)** = `closingCredit` of the most recent **FILED** prior return (else 0).
- **Due date** = the 20th of the month after the period.
- **Adjustments total** = signed sum of adjustment lines (INCREASE positive, DECREASE negative).
- **BR-VAT-01** one return per company-period (duplicate → 409 ConflictException).
- **BR-VAT-02** cannot recompute a FILED return.
- **BR-VAT-09** cannot add/remove adjustments on a FILED return.
- **BR-VAT-11** cannot re-file an already-FILED return.
- **D-4 prior-period gate** a period cannot be filed while an earlier-period return is still DRAFT.
- **GL settlement on file (D-8):** DR `VAT_PAYABLE` output, CR `VAT_INPUT` input, balancing leg to `VAT_DUE`
  (`O−I`>0 → CR, `O−I`<0 → DR, `O==I` → omitted). A **nil-activity** return (output=0 AND input=0) files and locks with
  **no journal** (`postedJournalUid` null). Adjustments/opening_credit affect the net figure but not the control-account clearing legs (v1).
- **WHT type:** code unique per company (duplicate → 409); `ratePct >= 0`; `kind` immutable after create; update changes name/rate/active only.

## Type/role variations exercised

| Dimension | Variations covered |
|---|---|
| User role / permission | `rootadmin` (bypass); ORG_ADMIN (full tax set — V14 grants the six tax permission codes to role ORG_ADMIN only); a CUSTOM role with `VAT.VIEW`+`WHT.VIEW` only (read-only); a CUSTOM role with `VAT.VIEW`+`VAT.RETURN.PREPARE`+`VAT.ADJUST` but NOT `VAT.RETURN.FILE`; NO-PERMISSION user (forbidden / hidden nav). NOTE: the seeded ACCOUNTANT role is NOT granted any tax permission in V14 — to run tax cases as an accountant, assign the tax codes to a CUSTOM role. |
| VatReturnStatus | DRAFT (editable) vs FILED (locked); legal DRAFT→FILED; illegal FILED→recompute / FILED→adjust / FILED→re-file |
| Net outcome | net payable (output>input, "Payable to TRA"); net credit (input>output, "Credit carried forward"); nil (output==input) |
| VAT adjustment | each `VatAdjustmentReason`; INCREASE vs DECREASE sign; positive-amount validation; remove |
| WhtKind | WHT_ON_PAYMENT (payable group) vs WHT_ON_RECEIPT (receivable group) |
| Period selection (WHT register) | year+month mode vs explicit periodStart/periodEnd range mode; year+month precedence; neither → error |
| Carry-forward | opening credit from a prior FILED return; first-ever return (opening 0) |
| Company/branch scope | own company vs cross-tenant (denied); company picker switches scope; VAT returns post company-level (no branch tag) |
| Screen states | loading / empty / error / forbidden on every list & detail |

---

## VAT Return — list & open

### TC-TAX-001 — VAT Returns list renders for an authorised user (idle state)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** VAT Returns (`/admin/tax/vat-returns` · `GET /api/v1/vat/returns`)
- **Permission / Role:** `VAT.VIEW` — runs as ORG_ADMIN (and rootadmin); also as NO-PERMISSION user → expect forbidden/hidden nav
- **Variation:** company = default company (single-company tenant)
- **Preconditions / Seed:** at least one VAT return exists for the company (seed via TC-TAX-010 or API `POST /api/v1/vat/returns`)
- **Steps:**
  1. Log in as ORG_ADMIN; navigate to `/admin/tax/vat-returns`.
  2. Wait for the company selector to populate; confirm the first company is auto-selected and the list loads.
  3. Read the returns table.
- **Test Data:** company "Acme Trading Ltd"
- **Expected Result:** table lists each return showing return number, period (YYYY-MM), status badge (DRAFT=warning / FILED=success) and money columns formatted to 2 dp; clicking a row navigates to `/admin/tax/vat-returns/uid/:uid`.
- **Convention Assertions:** C1 (no raw uid shown in any cell/label; navigation uses the uid only in the URL); C2 (list reads `ApiResponse` envelope + `meta`); C3 (`VAT.VIEW` gate); C4 (idle state distinct); C6 (axe clean); C8 (money "n,nnn.nn", period ISO-ish YYYY-MM).
- **Negative / Edge:** as NO-PERMISSION user the "Tax → VAT Returns" nav item is hidden and direct navigation to the route is blocked by `requirePermission('VAT.VIEW')`.

### TC-TAX-002 — VAT Returns list empty state
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** VAT Returns (`/admin/tax/vat-returns` · `GET /api/v1/vat/returns`)
- **Permission / Role:** `VAT.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** a company that has **no** VAT returns yet
- **Steps:**
  1. Navigate to `/admin/tax/vat-returns`; select the empty company.
- **Expected Result:** a distinct empty message (no rows; `isEmpty` true) — not a spinner and not an error.
- **Convention Assertions:** C4 (empty ≠ loading ≠ error); C6 axe.
- **Negative / Edge:** switching company back to a populated one repopulates the table.

### TC-TAX-003 — VAT Returns list error state with retry
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** VAT Returns (`/admin/tax/vat-returns`)
- **Permission / Role:** `VAT.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** simulate a 5xx on `GET /api/v1/vat/returns` (network throttle / server down)
- **Steps:**
  1. Navigate to the list; force the list call to fail.
- **Expected Result:** error state shown distinctly; user can recover by re-selecting the company (`onCompanyChange` → `load`).
- **Convention Assertions:** C4 (error state distinct).
- **Negative / Edge:** a **403** from the list is mapped to the **forbidden** state, not the generic error state (`status === 403 ? 'forbidden' : 'error'`).

### TC-TAX-004 — VAT Returns list forbidden state (has nav, lacks read at API)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** VAT Returns (`/admin/tax/vat-returns`)
- **Permission / Role:** simulate a user reaching the route without `VAT.VIEW` scope → API 403
- **Preconditions / Seed:** a user whose company scope yields 403 on the list
- **Steps:**
  1. Navigate to the list; the list API returns 403.
- **Expected Result:** the dedicated **forbidden** state renders (distinct from error).
- **Convention Assertions:** C3 (403 → forbidden); C4.

### TC-TAX-005 — Company picker switches scope and reloads returns
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** VAT Returns (`/admin/tax/vat-returns`)
- **Permission / Role:** `VAT.VIEW` — ORG_ADMIN
- **Variation:** multi-company organisation
- **Preconditions / Seed:** org with ≥2 companies, each with distinct returns
- **Steps:**
  1. Navigate to the list; note the auto-selected company's returns.
  2. Choose a second company in the company selector (by NAME).
- **Expected Result:** the table reloads with only the second company's returns.
- **Convention Assertions:** C1 (company chosen by name; companyId sent under the hood, never typed by user); C7 (data scoped per company).
- **Negative / Edge:** returns from company A never appear while company B is selected.

### TC-TAX-006 — Multi-tenant isolation: tenant A cannot see tenant B's VAT returns
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** VAT Returns (`GET /api/v1/vat/returns`, `GET .../uid/{uid}`)
- **Permission / Role:** `VAT.VIEW` — ORG_ADMIN of tenant A
- **Preconditions / Seed:** tenant B has a VAT return with a known uid
- **Steps:**
  1. As tenant A, attempt to open `/admin/tax/vat-returns/uid/<tenantB-uid>`.
- **Expected Result:** denied — `scopeGuard.assertCanActIn` rejects; detail screen shows error (no tenant-B data leaks).
- **Convention Assertions:** C7 (tenant isolation); C3.
- **Negative / Edge:** tenant B's returns are absent from tenant A's list regardless of company selection.

### TC-TAX-007 — Pagination behaviour on the returns list
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** VAT Returns (`GET /api/v1/vat/returns`, paged)
- **Permission / Role:** `VAT.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** note: the list component requests `size=100`; to exercise paging meta seed >100 returns OR assert the meta contract via API
- **Steps:**
  1. Inspect the `ApiResponse.meta` returned (`page,size,totalElements,totalPages,hasNext`).
- **Expected Result:** the envelope carries `meta`; when totalPages ≤ 1 the shared paginator is self-hidden.
- **Convention Assertions:** C2 (meta preserved); C5 (paginator self-hides at 1 page). NOTE: the current list UI fetches a single page of 100 and does not render `<app-paginator>` page-number controls — flag as a UI gap if >100 returns must be navigable.
- **Negative / Edge:** with >100 returns the UI would not show pages 2+ today (document as known limitation).

### TC-TAX-008 — "New VAT Return" action visible only with VAT.RETURN.PREPARE
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** VAT Returns (`/admin/tax/vat-returns`)
- **Permission / Role:** `VAT.RETURN.PREPARE` — runs as ORG_ADMIN (visible); also as read-only CUSTOM role with only `VAT.VIEW` → button hidden
- **Steps:**
  1. As the read-only role, load the list — confirm no "New VAT Return" control (`canPrepare` false).
  2. As ORG_ADMIN, confirm the control is present.
- **Expected Result:** create affordance gated on `VAT.RETURN.PREPARE`.
- **Convention Assertions:** C3 (action gated by permission code, not role name).
- **Negative / Edge:** read-only user calling `POST /api/v1/vat/returns` directly → 403.

### TC-TAX-009 — Open a new DRAFT VAT return (happy path, first-ever period)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** VAT Returns (`POST /api/v1/vat/returns`)
- **Permission / Role:** `VAT.RETURN.PREPARE` (`@perm.scoped(#req.companyUid,'company',...)`) — ORG_ADMIN
- **Variation:** first return for the company → opening credit = 0
- **Preconditions / Seed:** company with no prior return for the chosen period
- **Steps:**
  1. On the list, click "New VAT Return".
  2. Select year and month (month chosen by label, e.g. "March").
  3. Submit.
- **Test Data:** year = 2026, month = 3 (March)
- **Expected Result:** HTTP 201; a new DRAFT return is created with `returnNumber` generated, `periodStart`=2026-03-01, `periodEnd`=2026-03-31, `dueDate`=2026-04-20, `openingCredit`=0; a first compute runs; UI navigates to the detail screen and shows a success toast with the return number.
- **Convention Assertions:** C1 (company selected by name; no uid typed; resulting uid only in the new URL); C2 (201, envelope); C8 (dates ISO yyyy-MM-dd); C9 (append-only — opening a return creates a record, no edits to prior).
- **Negative / Edge:** see TC-TAX-011 (duplicate), TC-TAX-012 (validation).

### TC-TAX-010 — Open return carries forward opening credit from prior FILED return
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** VAT Returns (`POST /api/v1/vat/returns`)
- **Permission / Role:** `VAT.RETURN.PREPARE` — ORG_ADMIN
- **Variation:** prior period FILED with a closing credit (input > output)
- **Preconditions / Seed:** period 2026-02 is FILED with `closingCredit` = 50,000 (net credit)
- **Steps:**
  1. Open a new return for 2026-03.
  2. Open its detail; read "Opening Credit b/f".
- **Test Data:** prior closingCredit 50,000.00
- **Expected Result:** new return's `openingCredit` = 50,000.00 and `priorReturnId` points at the FILED Feb return; net VAT reflects the subtraction of opening credit.
- **Convention Assertions:** C8 (money formatting); C9 (carry-forward sourced from the prior FILED record, not re-keyed).
- **Negative / Edge:** if the prior period is still DRAFT it is NOT used for carry-forward (only the latest **FILED** before the period is used); opening credit stays 0.

### TC-TAX-011 — Duplicate period rejected (BR-VAT-01)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** VAT Returns (`POST /api/v1/vat/returns`)
- **Permission / Role:** `VAT.RETURN.PREPARE` — ORG_ADMIN
- **Preconditions / Seed:** a return already exists for company + 2026-03
- **Steps:**
  1. Attempt to open another return for the same company + 2026-03.
- **Expected Result:** 409 ConflictException — message "A VAT return already exists for company … period 2026-03 (BR-VAT-01)."; UI shows the inline create error from the envelope's `errors[0]`.
- **Convention Assertions:** C2 (error surfaced from envelope `errors`); C3.
- **Negative / Edge:** different month for the same company succeeds; same month for a different company succeeds (scope is per company).

### TC-TAX-012 — Open-return field validation (year/month bounds)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** VAT Returns (`POST /api/v1/vat/returns`)
- **Permission / Role:** `VAT.RETURN.PREPARE` — ORG_ADMIN
- **Steps:**
  1. Open the new-return form; try year 1999, then 2101, then a blank/zero month.
- **Test Data:** year 1999 (< 2000), year 2101 (> 2100), month 0 / 13
- **Expected Result:** client validation blocks ("Enter a valid year (2000–2100)." / "Select a valid month."); if forced to the API, server bean-validation rejects (`@Min(2000)/@Max(2100)`, `@Min(1)/@Max(12)`) with 400.
- **Convention Assertions:** C2 (400 envelope errors); required-field handling.
- **Negative / Edge:** boundary values 2000 and 2100, month 1 and 12 are accepted.

---

## VAT Return — detail, recompute, file (lifecycle)

### TC-TAX-020 — VAT Return detail "face" renders bands + summary + net label
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** VAT Return detail (`/admin/tax/vat-returns/uid/:uid` · `GET /api/v1/vat/returns/uid/{uid}`)
- **Permission / Role:** `VAT.VIEW` — ORG_ADMIN
- **Variation:** net payable (output > input)
- **Preconditions / Seed:** a DRAFT return with output 180,000 / input 80,000
- **Steps:**
  1. Open the detail screen by navigating from the list (row click).
  2. Read the "Return Face" card: output-VAT-by-band table, total output, total input, adjustments, opening credit, net.
- **Test Data:** outputVat 180,000.00; inputVat 80,000.00; adjustments 0; opening 0 → net 100,000.00
- **Expected Result:** band table shows one row per tax band (e.g. STANDARD) with taxable base + output VAT; summary computes net = output − input + adj − opening = 100,000.00; net label = **"Payable to TRA"** with danger styling.
- **Convention Assertions:** C1 (uid only in URL; not shown in face text); C4 (loading/error states on detail); C6 axe; C8 money formatting; table has `aria-label`/`scope` headers.
- **Negative / Edge:** see TC-TAX-021 (credit) and TC-TAX-022 (nil).

### TC-TAX-021 — Net credit shows "Credit carried forward"
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** VAT Return detail
- **Permission / Role:** `VAT.VIEW` — ORG_ADMIN
- **Variation:** input > output → net < 0
- **Preconditions / Seed:** DRAFT return with output 40,000 / input 90,000
- **Steps:**
  1. Open detail; read the net row and the closing credit row.
- **Test Data:** net = 40,000 − 90,000 = −50,000.00 → closingCredit = 50,000.00
- **Expected Result:** net label = **"Credit carried forward"** (success styling); a "Closing Credit c/f" row appears showing 50,000.00.
- **Convention Assertions:** C8; net = `max(−net,0)` closing credit shown only when > 0.
- **Negative / Edge:** the closing credit becomes the next period's opening credit only after this return is FILED (TC-TAX-010).

### TC-TAX-022 — Nil-activity return (output == input, net 0)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** VAT Return detail + file
- **Permission / Role:** `VAT.VIEW` / `VAT.RETURN.FILE` — ORG_ADMIN
- **Variation:** nil return (output 0 AND input 0)
- **Preconditions / Seed:** a period with no sales/purchases → compute yields output 0, input 0
- **Steps:**
  1. Open the return; confirm net label = **"Nil"** and no closing-credit row.
  2. File the return (TC-TAX-031 flow).
- **Test Data:** output 0.00, input 0.00 → net 0.00
- **Expected Result:** files and locks to FILED; `postedJournalUid` is **null** (no GL journal — nil-activity per D-8); the "Posted Journal" link is therefore absent.
- **Convention Assertions:** C2; C9 (FILED with no journal is a legal append-only outcome).
- **Negative / Edge:** a return with output==input but BOTH non-zero (e.g. 50,000/50,000) DOES post a journal (legs 1 & 2 present, VAT_DUE leg omitted because O−I=0).

### TC-TAX-023 — Recompute a DRAFT return (VAT.RETURN.PREPARE)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** VAT Return detail (`POST /api/v1/vat/returns/uid/{uid}/recompute`)
- **Permission / Role:** `VAT.RETURN.PREPARE` (`@perm.scoped(#uid,'vatreturn',...)`) — ORG_ADMIN; also as user with only `VAT.VIEW` → Recompute button hidden + API 403
- **Variation:** DRAFT status
- **Preconditions / Seed:** a DRAFT return; new sales/purchase activity recorded after the last compute
- **Steps:**
  1. Open the DRAFT detail; click "Recompute".
- **Expected Result:** bands rebuilt (delete + reinsert), totals refreshed, net & closing credit recomputed; success toast; figures update in place.
- **Convention Assertions:** C3 (button + API both gated); C8.
- **Negative / Edge:** see TC-TAX-024 (FILED recompute blocked).

### TC-TAX-024 — Illegal transition: recompute a FILED return is rejected (BR-VAT-02)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** VAT Return detail (`POST .../recompute`)
- **Permission / Role:** `VAT.RETURN.PREPARE` — ORG_ADMIN
- **Variation:** FILED status (illegal source state)
- **Preconditions / Seed:** a FILED return
- **Steps:**
  1. Open a FILED return; confirm the "Recompute" button is **not rendered** (`isDraft()` false).
  2. Attempt `POST .../recompute` directly via API on the FILED uid.
- **Expected Result:** UI never offers recompute on FILED; API throws IllegalStateException "Cannot recompute a FILED VAT return (BR-VAT-02)".
- **Convention Assertions:** C3; illegal status transition rejected.
- **Negative / Edge:** all DRAFT-only actions (recompute, file form, add/remove adjustment) are hidden on FILED.

### TC-TAX-025 — File button gated on VAT.RETURN.FILE (PREPARE alone is insufficient)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** VAT Return detail (`POST /api/v1/vat/returns/uid/{uid}/file`)
- **Permission / Role:** `VAT.RETURN.FILE` — runs as ORG_ADMIN (visible); also as CUSTOM role with `VAT.VIEW`+`VAT.RETURN.PREPARE`+`VAT.ADJUST` but NOT `VAT.RETURN.FILE` → File control hidden
- **Variation:** DRAFT status
- **Steps:**
  1. As the prepare-but-not-file role, open a DRAFT return — confirm Recompute/Add-Adjustment present but "File Return" absent (`canFile` false).
  2. Attempt `POST .../file` directly → 403.
- **Expected Result:** filing strictly requires `VAT.RETURN.FILE`.
- **Convention Assertions:** C3 (distinct codes for prepare vs file).
- **Negative / Edge:** none.

### TC-TAX-026 — File a DRAFT return — happy path (lock + GL settlement, net payable)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** VAT Return detail (`POST .../file`)
- **Permission / Role:** `VAT.RETURN.FILE` — ORG_ADMIN
- **Variation:** net payable (output > input)
- **Preconditions / Seed:** a DRAFT return, output 180,000 / input 80,000; GL config has `VAT_PAYABLE`, `VAT_INPUT`, `VAT_DUE` mapped
- **Steps:**
  1. Open detail; click "File Return".
  2. Enter TRA Filing Reference and Filing Date; click "Confirm File".
- **Test Data:** filingReference "TRA/VAT/2026/001234"; filingDate 2026-04-15
- **Expected Result:** final recompute runs, figures freeze; status → **FILED**; `filingReference`, `filingDate`, `filedAt`, `filedBy` recorded; a balanced settlement journal posts (DR VAT_PAYABLE 180,000; CR VAT_INPUT 80,000; CR VAT_DUE 100,000); `postedJournalUid` set and shown as a link to `/admin/gl/journals/uid/:uid`; DRAFT actions disappear; success toast.
- **Convention Assertions:** C1 (uid only in the journal link URL); C2 (envelope); C8 (money + ISO date); C9 (FILED is locked, append-only — GL posting is a new journal, not an edit).
- **Negative / Edge:** see TC-TAX-027/028/029.

### TC-TAX-027 — File requires filing reference and filing date
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** VAT Return detail (`POST .../file`)
- **Permission / Role:** `VAT.RETURN.FILE` — ORG_ADMIN
- **Steps:**
  1. Open the File form; clear the reference; click Confirm File.
  2. Then provide reference but clear the date; Confirm File.
- **Expected Result:** client validation blocks with "Filing reference is required." / "Filing date is required."; server `@NotBlank filingReference` + `@NotNull filingDate` reject if bypassed (400).
- **Convention Assertions:** C2 (400 envelope); required-field handling.
- **Negative / Edge:** whitespace-only reference is trimmed and treated as empty.

### TC-TAX-028 — Illegal transition: re-file an already-FILED return rejected (BR-VAT-11)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** VAT Return detail (`POST .../file`)
- **Permission / Role:** `VAT.RETURN.FILE` — ORG_ADMIN
- **Variation:** FILED (illegal source state)
- **Preconditions / Seed:** a FILED return
- **Steps:**
  1. Open the FILED return — confirm no "File Return" control.
  2. Attempt `POST .../file` directly on the FILED uid.
- **Expected Result:** IllegalStateException "VAT return already FILED (BR-VAT-11)".
- **Convention Assertions:** C3; illegal transition rejected.
- **Negative / Edge:** none.

### TC-TAX-029 — File blocked while a prior-period return is still DRAFT (D-4 ordering gate)
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** VAT Return detail (`POST .../file`)
- **Permission / Role:** `VAT.RETURN.FILE` — ORG_ADMIN
- **Variation:** prior period (e.g. 2026-02) still DRAFT, current (2026-03) being filed
- **Preconditions / Seed:** 2026-02 DRAFT and 2026-03 DRAFT both exist
- **Steps:**
  1. Attempt to file 2026-03.
- **Expected Result:** IllegalStateException "Prior period return <number> must be FILED before this period can be filed (D-4)."; UI surfaces the message in the File form error.
- **Convention Assertions:** C2 (error from envelope).
- **Negative / Edge:** after filing 2026-02 first, filing 2026-03 succeeds; if there is NO prior return at all the gate does not apply.

### TC-TAX-030 — GL settlement leg shape: net credit posts DR VAT_DUE
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** VAT Return detail (`POST .../file`) + GL
- **Permission / Role:** `VAT.RETURN.FILE` — ORG_ADMIN
- **Variation:** net credit (input > output)
- **Preconditions / Seed:** DRAFT with output 40,000 / input 90,000
- **Steps:**
  1. File the return; open the posted journal link.
- **Expected Result:** journal = DR VAT_PAYABLE 40,000; CR VAT_INPUT 90,000; DR VAT_DUE 50,000 (the `O−I` negative branch posts a debit to VAT_DUE); entry balanced (Σdebit == Σcredit).
- **Convention Assertions:** C8; C9 (append-only journal).
- **Negative / Edge:** if VAT_DUE / VAT_PAYABLE / VAT_INPUT GL config is missing for the company, filing fails and the whole transaction rolls back (return stays DRAFT, no partial post).

### TC-TAX-031 — Filed return is fully read-only
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** VAT Return detail
- **Permission / Role:** `VAT.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** a FILED return with adjustments and a posted journal
- **Steps:**
  1. Open the FILED return.
- **Expected Result:** header shows FILED (success badge); "Filed <date> (ref: …)" and posted-journal link shown; no Recompute / File / Add-Adjustment / Remove controls; adjustment rows have no delete button.
- **Convention Assertions:** C9 (locked); C1 (uid in URL only); C6 axe.
- **Negative / Edge:** none.

### TC-TAX-032 — VAT Return detail loading & error states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** VAT Return detail (`GET .../uid/{uid}`)
- **Permission / Role:** `VAT.VIEW` — ORG_ADMIN
- **Steps:**
  1. Open a valid uid (loading → idle).
  2. Open a non-existent uid (`GET` 404) → error state with Retry.
- **Expected Result:** spinner during load; "Failed to load VAT return." with a Retry button on failure.
- **Convention Assertions:** C4 (loading/error distinct on detail). NOTE: the detail screen maps all load failures (including 403) to the single `error` state — there is no separate `forbidden` state on detail (record as known gap vs C4 strictness).
- **Negative / Edge:** Retry re-issues `getByUid`.

---

## VAT Adjustments (embedded in detail)

### TC-TAX-040 — Add adjustment list visible only with VAT.ADJUST (DRAFT only)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** VAT Adjustments (`POST /api/v1/vat/returns/uid/{returnUid}/adjustments`)
- **Permission / Role:** `VAT.ADJUST` (`@perm.scoped(#returnUid,'vatreturn',...)`) — ORG_ADMIN; also as `VAT.VIEW`-only role → no Add/Remove controls
- **Variation:** DRAFT
- **Steps:**
  1. As the view-only role, open a DRAFT return → "Add Adjustment" absent and rows have no Remove button.
  2. As ORG_ADMIN, confirm "Add Adjustment" present.
- **Expected Result:** adjustment editing gated by `VAT.ADJUST` AND draft status.
- **Convention Assertions:** C3.
- **Negative / Edge:** on a FILED return Add/Remove are hidden even for `VAT.ADJUST` holders.

### TC-TAX-041 — Add an INCREASE adjustment and see net increase
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** VAT Adjustments (`POST .../adjustments`)
- **Permission / Role:** `VAT.ADJUST` — ORG_ADMIN
- **Variation:** reason = PRIOR_PERIOD_CORRECTION, sign = INCREASE
- **Preconditions / Seed:** a DRAFT return with known net (e.g. 100,000)
- **Steps:**
  1. Open detail; "Add Adjustment".
  2. Choose Reason "Prior Period Correction", Effect "Increase VAT", Amount 5,000, Narrative "Q1 under-declaration".
  3. Submit.
- **Test Data:** reason PRIOR_PERIOD_CORRECTION, sign INCREASE, amount 5000.00
- **Expected Result:** HTTP 201; row appears (reason label, INCREASE badge=danger, amount, narrative); the return is re-fetched so `adjustmentsTotal` += 5,000 and net = 105,000.00.
- **Convention Assertions:** C2 (201); C8; signed-sum recompute reflected.
- **Negative / Edge:** see TC-TAX-044 (positive-amount validation).

### TC-TAX-042 — Add a DECREASE adjustment and see net decrease
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** VAT Adjustments (`POST .../adjustments`)
- **Permission / Role:** `VAT.ADJUST` — ORG_ADMIN
- **Variation:** reason = BAD_DEBT_RELIEF, sign = DECREASE
- **Preconditions / Seed:** DRAFT return net 100,000
- **Steps:**
  1. Add adjustment Reason "Bad Debt Relief", Effect "Decrease VAT", Amount 8,000.
- **Test Data:** reason BAD_DEBT_RELIEF, sign DECREASE, amount 8000.00
- **Expected Result:** row badge=success (DECREASE); `adjustmentsTotal` decreases by 8,000 (signed −8,000); net = 92,000.00.
- **Convention Assertions:** C8.
- **Negative / Edge:** mixing INCREASE + DECREASE lines nets correctly (signed sum).

### TC-TAX-043 — Each VatAdjustmentReason value is selectable
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** VAT Adjustments
- **Permission / Role:** `VAT.ADJUST` — ORG_ADMIN
- **Steps:**
  1. Open the Add Adjustment form; enumerate the Reason dropdown.
- **Expected Result:** exactly five options — Bad Debt Relief, Prior Period Correction, Credit Note VAT, Debit Note VAT, Other (mapping to enum values BAD_DEBT_RELIEF / PRIOR_PERIOD_CORRECTION / CREDIT_NOTE_VAT / DEBIT_NOTE_VAT / OTHER).
- **Convention Assertions:** enum coverage; C6 axe (labelled select).
- **Negative / Edge:** Effect dropdown offers exactly INCREASE / DECREASE.

### TC-TAX-044 — Adjustment amount must be positive
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** VAT Adjustments (`POST .../adjustments`)
- **Permission / Role:** `VAT.ADJUST` — ORG_ADMIN
- **Steps:**
  1. In the Add form, enter amount 0 then a negative; submit.
- **Test Data:** amount 0; amount −10
- **Expected Result:** client blocks "Enter a positive amount."; server `@Positive BigDecimal amount` rejects with 400 if bypassed. Sign is carried separately via `VatAdjustmentSign`, so the magnitude must always be positive.
- **Convention Assertions:** C2 (400 envelope); validation.
- **Negative / Edge:** narrative is optional (sent as undefined when blank).

### TC-TAX-045 — Remove an adjustment and see net revert
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** VAT Adjustments (`DELETE .../adjustments/uid/{uid}`)
- **Permission / Role:** `VAT.ADJUST` — ORG_ADMIN
- **Preconditions / Seed:** a DRAFT return with one adjustment (e.g. +5,000)
- **Steps:**
  1. Open detail; click the Remove (trash) button on the adjustment row.
- **Expected Result:** HTTP 204; row disappears; return re-fetched so `adjustmentsTotal` and net revert.
- **Convention Assertions:** C2 (204 no content); the Remove button has an aria-label "Remove adjustment <reason>".
- **Negative / Edge:** removing an adjustment uid that is not on this return → 404 "VatAdjustment … not on return …".

### TC-TAX-046 — Illegal: add/remove adjustment on a FILED return rejected (BR-VAT-09)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** VAT Adjustments (`POST` / `DELETE .../adjustments`)
- **Permission / Role:** `VAT.ADJUST` — ORG_ADMIN
- **Variation:** FILED (illegal)
- **Preconditions / Seed:** a FILED return that has an adjustment
- **Steps:**
  1. Open the FILED return — confirm no Add/Remove controls.
  2. Attempt `POST` and `DELETE` on adjustments directly via API.
- **Expected Result:** IllegalStateException "Cannot add adjustment to a FILED VAT return (BR-VAT-09)" / "Cannot remove adjustment from a FILED VAT return (BR-VAT-09)".
- **Convention Assertions:** C3; C9 (locked).
- **Negative / Edge:** list adjustments (`GET`) still works read-only on FILED with `VAT.VIEW`.

### TC-TAX-047 — List adjustments empty state + scope guard
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** VAT Adjustments (`GET .../adjustments`)
- **Permission / Role:** `VAT.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** a return with no adjustments
- **Steps:**
  1. Open the detail; read the Manual Adjustments card.
- **Expected Result:** "No adjustments on this return." (distinct empty state, not error/spinner).
- **Convention Assertions:** C4; C7 (`scopeGuard` blocks cross-company adjustment reads → forbidden/error).
- **Negative / Edge:** cross-tenant `GET .../adjustments` denied.

---

## WHT Types (rate master CRUD)

### TC-TAX-060 — WHT Types list renders (idle) gated WHT.VIEW
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** WHT Types (`/admin/tax/wht-types` · `GET /api/v1/wht/types`)
- **Permission / Role:** `WHT.VIEW` — ORG_ADMIN; also NO-PERMISSION user → nav hidden / route blocked
- **Preconditions / Seed:** ≥1 WHT type for the company
- **Steps:**
  1. Navigate to `/admin/tax/wht-types`; first company auto-selected.
- **Expected Result:** table lists code, name, kind badge ("On Payment"=primary / "On Receipt"=info), rate % (2 dp), active flag.
- **Convention Assertions:** C1 (no uid shown; kind shown by friendly label); C3; C4; C6 axe; C8 (rate formatting).
- **Negative / Edge:** empty / error / 403→forbidden states (analogous to TC-TAX-002/003/004).

### TC-TAX-061 — Create WHT type — happy path (WHT.MANAGE)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** WHT Types (`POST /api/v1/wht/types`, `@perm.scoped(#req.companyUid,'company','WHT.MANAGE')`)
- **Permission / Role:** `WHT.MANAGE` — ORG_ADMIN; also as `WHT.VIEW`-only role → no Create control
- **Variation:** kind = WHT_ON_PAYMENT
- **Preconditions / Seed:** company selected; code not already used
- **Steps:**
  1. Click "New WHT Type"; enter Code, Name, Kind "On Payment", Rate.
  2. Submit.
- **Test Data:** code "WHT-SERV", name "Service fees (resident)", kind WHT_ON_PAYMENT, ratePct 5
- **Expected Result:** HTTP 201; row added; success toast; companyUid resolved from the selected company under the hood.
- **Convention Assertions:** C1 (company chosen by name, companyUid not typed); C2 (201); C8.
- **Negative / Edge:** see TC-TAX-062/063.

### TC-TAX-062 — Create WHT type with WHT_ON_RECEIPT kind
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** WHT Types (`POST /api/v1/wht/types`)
- **Permission / Role:** `WHT.MANAGE` — ORG_ADMIN
- **Variation:** kind = WHT_ON_RECEIPT
- **Steps:**
  1. Create a type with Kind "On Receipt".
- **Test Data:** code "WHT-RCPT", name "Customer-withheld", kind WHT_ON_RECEIPT, ratePct 2
- **Expected Result:** created; kind badge shows "On Receipt" (info). Both `WhtKind` values are exercised across TC-061/062.
- **Convention Assertions:** enum coverage; C8.
- **Negative / Edge:** kind is fixed at create and cannot be changed by update (the Update request has no `kind` field).

### TC-TAX-063 — Duplicate WHT type code rejected (per company)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** WHT Types (`POST /api/v1/wht/types`)
- **Permission / Role:** `WHT.MANAGE` — ORG_ADMIN
- **Preconditions / Seed:** code "WHT-SERV" already exists for the company
- **Steps:**
  1. Attempt to create another "WHT-SERV" for the same company.
- **Expected Result:** 409 ConflictException "WHT type code 'WHT-SERV' already exists for this company."; inline create error from envelope.
- **Convention Assertions:** C2 (envelope errors); C7 (uniqueness is per company — same code allowed in a different company).
- **Negative / Edge:** different code for same company succeeds.

### TC-TAX-064 — Create validation: code, name, rate
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** WHT Types (`POST /api/v1/wht/types`)
- **Permission / Role:** `WHT.MANAGE` — ORG_ADMIN
- **Steps:**
  1. Submit with blank code, then blank name, then negative rate.
- **Test Data:** code "" / name "" / ratePct −1
- **Expected Result:** client blocks ("Code is required." / "Name is required." / "Enter a valid rate (0 or greater)."); server `@NotBlank code`, `@NotBlank name`, `@Min(0) ratePct` reject (400) if bypassed.
- **Convention Assertions:** C2 (400); required-field handling.
- **Negative / Edge:** rate 0 is valid (e.g. a zero-rate type); large rate (e.g. 30) accepted.

### TC-TAX-065 — Edit WHT type (name / rate / active) — WHT.MANAGE
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** WHT Types (`PUT /api/v1/wht/types/uid/{uid}`, `@perm.scoped(#uid,'whttype','WHT.MANAGE')`)
- **Permission / Role:** `WHT.MANAGE` — ORG_ADMIN
- **Preconditions / Seed:** an existing WHT type
- **Steps:**
  1. Click edit on the row; change Name and Rate; toggle Active; Save.
- **Test Data:** name "Service fees (revised)", ratePct 7.5, active true
- **Expected Result:** row updates in place; success toast; only name/rate/active change (code + kind immutable).
- **Convention Assertions:** C1 (uid in URL path only); C2; C8.
- **Negative / Edge:** edit validation: blank name / negative rate blocked (client + `@NotBlank`/`@Min(0)`); `@NotNull active` required.

### TC-TAX-066 — Deactivate WHT type (soft, not delete)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** WHT Types (`POST /api/v1/wht/types/uid/{uid}/deactivate`)
- **Permission / Role:** `WHT.MANAGE` — ORG_ADMIN
- **Preconditions / Seed:** an ACTIVE WHT type
- **Steps:**
  1. Click "Deactivate" on an active row.
- **Expected Result:** `active` flips to false; the row remains (no hard delete); the Deactivate control is suppressed once inactive.
- **Convention Assertions:** C9 (soft-deactivate, not destructive); C3.
- **Negative / Edge:** the UI suppresses the Deactivate control once a type is inactive (the server `deactivate` is idempotent — it unconditionally sets `active=false`, there is no early-return guard, so re-deactivating an already-inactive type is a harmless no-op rather than an error); deactivation can be reversed via Edit (set active = true). An inactive type is rejected by the server-side WHT capture (`resolveType` throws "is inactive") — covered conceptually only (no UI).

### TC-TAX-067 — WHT Types read-only for VIEW-only role
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** WHT Types
- **Permission / Role:** `WHT.VIEW` (without `WHT.MANAGE`) — CUSTOM read-only role
- **Steps:**
  1. Open the list as the read-only role.
- **Expected Result:** table visible; no New / Edit / Deactivate controls (`canManage` false); direct `POST/PUT/deactivate` API calls → 403.
- **Convention Assertions:** C3 (manage gated separately from view).
- **Negative / Edge:** none.

### TC-TAX-068 — WHT Types company scoping & cross-tenant denial
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** WHT Types (`GET`, `PUT`, deactivate)
- **Permission / Role:** `WHT.VIEW` / `WHT.MANAGE` — ORG_ADMIN of tenant A
- **Preconditions / Seed:** tenant B has a WHT type with a known uid
- **Steps:**
  1. As tenant A, switch companies and confirm only tenant-A types show.
  2. Attempt `PUT /api/v1/wht/types/uid/<tenantB-uid>` → denied.
- **Expected Result:** `scopeGuard.assertCanActIn` rejects cross-tenant; list shows only the selected company's types.
- **Convention Assertions:** C7 (tenant + company scope).
- **Negative / Edge:** same code can coexist across tenants.

---

## WHT Register / certificates

### TC-TAX-080 — WHT Register by year+month (idle, two groups + totals)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** WHT Register (`/admin/tax/wht-register` · `GET /api/v1/wht/register?companyId&year&month`)
- **Permission / Role:** `WHT.VIEW` — ORG_ADMIN; also NO-PERMISSION user → nav hidden / route blocked
- **Variation:** period mode = month
- **Preconditions / Seed:** WHT certificate rows exist for the month, in BOTH kinds — seeded via AP-payment (WHT_ON_PAYMENT) and AR-receipt (WHT_ON_RECEIPT) flows in the AP/AR modules (no tax-screen capture exists)
- **Steps:**
  1. Navigate to `/admin/tax/wht-register`; select company; period mode "Month"; pick year + month (month by label); click Load.
- **Test Data:** year 2026, month 3
- **Expected Result:** two sections — "WHT Payable to TRA" (WHT_ON_PAYMENT rows) and "WHT Receivable" (WHT_ON_RECEIPT rows); each row shows certificate number (`whtNumber`), party kind, party name, source ref, taxable base, WHT amount, certificate date; each section shows a group total (`totalPayable` / `totalReceivable`).
- **Convention Assertions:** C1 (certificate number / party NAME shown, no raw uid); C4; C6 axe; C8 (money + ISO dates); table aria-labels/scope.
- **Negative / Edge:** see TC-TAX-082/083.

### TC-TAX-081 — WHT Register by explicit date range
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** WHT Register (`GET /api/v1/wht/register?companyId&periodStart&periodEnd`)
- **Permission / Role:** `WHT.VIEW` — ORG_ADMIN
- **Variation:** period mode = range
- **Steps:**
  1. Switch period mode to "Range"; set periodStart and periodEnd; Load.
- **Test Data:** periodStart 2026-01-01, periodEnd 2026-03-31 (a quarter)
- **Expected Result:** register covers the inclusive date range (filtered on `certificateDate BETWEEN start AND end`).
- **Convention Assertions:** C8 (ISO dates on the wire).
- **Negative / Edge:** year+month takes precedence — if both year+month AND a range are present, the controller resolves year+month and ignores the range.

### TC-TAX-082 — WHT Register empty period
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** WHT Register
- **Permission / Role:** `WHT.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** a month with no WHT transactions
- **Steps:**
  1. Load a period with no rows.
- **Expected Result:** both sections render with no rows and totals 0.00 (distinct empty presentation, not an error).
- **Convention Assertions:** C4; C8 (0.00).
- **Negative / Edge:** loading state shows a spinner before results.

### TC-TAX-083 — WHT Register requires a valid period selection
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** WHT Register (`GET /api/v1/wht/register`)
- **Permission / Role:** `WHT.VIEW` — ORG_ADMIN
- **Steps:**
  1. In range mode, leave one of periodStart/periodEnd blank; Load.
- **Expected Result:** server raises IllegalArgumentException "Provide either year+month or periodStart+periodEnd." (neither complete pair supplied) → mapped to an error/400; UI shows the error state.
- **Convention Assertions:** C2 (error envelope); C4.
- **Negative / Edge:** providing only periodStart (no end) is insufficient; providing only year (no month) is insufficient.

### TC-TAX-084 — WHT Register forbidden / scope
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** WHT Register
- **Permission / Role:** user reaching the route without scope → API 403; and cross-tenant company
- **Steps:**
  1. Trigger a 403 on the register call.
- **Expected Result:** dedicated forbidden state (`status === 403 ? 'forbidden' : 'error'`).
- **Convention Assertions:** C3; C7 (`scopeGuard.assertCanActIn` on the resolved company).
- **Negative / Edge:** tenant A cannot pull tenant B's register.

### TC-TAX-085 — WHT Register groups split strictly by WhtKind
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** WHT Register
- **Permission / Role:** `WHT.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** mixed WHT_ON_PAYMENT and WHT_ON_RECEIPT rows in the period
- **Steps:**
  1. Load the register; cross-check each row's section against its kind.
- **Expected Result:** every WHT_ON_PAYMENT row appears only in the Payable section; every WHT_ON_RECEIPT only in Receivable; `totalPayable` = Σ payable WHT amounts; `totalReceivable` = Σ receivable WHT amounts.
- **Convention Assertions:** C8 (totals); enum-driven grouping coverage.
- **Negative / Edge:** a certificate with `certificateDate` on the boundary day (period start or end) is included (BETWEEN is inclusive).

---

## Cross-cutting / convention sweeps

### TC-TAX-100 — A11y axe sweep across all Tax screens
- **Type:** Automated (Playwright + axe)
- **Priority:** P2
- **Module / Submodule:** all Tax routes
- **Permission / Role:** `VAT.VIEW` + `WHT.VIEW` — ORG_ADMIN
- **Steps:**
  1. Visit `/admin/tax/vat-returns`, a `/admin/tax/vat-returns/uid/:uid` (DRAFT and FILED), `/admin/tax/wht-types`, `/admin/tax/wht-register`.
  2. Run axe on each, including with forms open (new return, file form, add adjustment, new WHT type).
- **Expected Result:** zero critical/serious axe violations; all inputs labelled (`for`/`id`), tables have `aria-label` + `scope` headers, action buttons keyboard-operable, status/loading regions have roles.
- **Convention Assertions:** C6 (WCAG 2.1 AA).
- **Negative / Edge:** forms must trap focus and expose validation errors via `role="alert"`.

### TC-TAX-101 — uid-never-shown / no-typed-uid sweep
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** all Tax screens
- **Permission / Role:** ORG_ADMIN
- **Steps:**
  1. Across every Tax table/detail/form, scan visible text for any raw uid string.
  2. Confirm every cross-resource reference (company, posted journal) is selected by NAME / shown as a human number, and uid appears only inside URLs.
- **Expected Result:** no uid in any table cell, label, badge, or detail field; company chosen via selector by name; posted-journal link displays the journal uid only as the link target/text within the GL deep-link (machine reference) and is reached by clicking, never typed.
- **Convention Assertions:** C1.
- **Negative / Edge:** the VAT return number, WHT certificate number, and filing reference are human identifiers (allowed to display); uids are not.

### TC-TAX-102 — Money & date formatting sweep
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** all Tax screens
- **Permission / Role:** ORG_ADMIN
- **Steps:**
  1. Inspect money fields (output/input/adjustments/net/closing on returns; rate % on WHT types; taxable base/WHT amount/totals on register).
  2. Inspect date fields (periodStart/End, dueDate, filingDate, certificateDate).
- **Expected Result:** money rendered to 2 dp (component `fmtMoney`); dates ISO yyyy-MM-dd; amounts on the API wire are strings (C8) and the UI accepts string amounts in adjustment/rate inputs.
- **Convention Assertions:** C8.
- **Negative / Edge:** very large amounts and zero render without NaN ("0.00").

### TC-TAX-103 — Empty-nav for the no-permission user
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** shell nav (Tax items in the "Accounting" group)
- **Permission / Role:** NO-PERMISSION user
- **Steps:**
  1. Log in as the no-permission user; inspect the sidebar.
  2. Attempt direct navigation to each Tax route.
- **Expected Result:** the Tax nav items (VAT Returns / WHT Types / WHT Register) are hidden (each carries a `permission` of `VAT.VIEW`/`WHT.VIEW`); direct route access is blocked by the `requirePermission` guard.
- **Convention Assertions:** C3 (nav hidden when permission absent); C4 (forbidden).
- **Negative / Edge:** a user with `VAT.VIEW` but not `WHT.VIEW` sees only "VAT Returns" (and vice-versa).

### TC-TAX-104 — Audit trail recorded for tax actions (verification)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Tax (all write actions) + Audit log (`/admin/audit`)
- **Permission / Role:** `AUDIT.VIEW` reviewer (after acting as ORG_ADMIN)
- **Preconditions / Seed:** perform an open, recompute, add/remove adjustment, file, WHT create/update/deactivate
- **Steps:**
  1. As an `AUDIT.VIEW` user, open the audit log and filter to the tax actions.
- **Expected Result:** audit events recorded — VAT_RETURN_PREPARE (open/recompute), VAT_ADJUST (add/remove), VAT_RETURN_FILE (with returnNumber, filingReference, glEntryUid, netVat), WHT_TYPE_MANAGE (create/update/deactivate), WHT_CAPTURE (when WHT captured via AR/AP).
- **Convention Assertions:** C9 (immutable audit; financial events append-only).
- **Negative / Edge:** filing a nil return logs glEntryUid as "(nil return — no journal)".
