# 11 — Accounts Receivable (AR) Test Cases

Exhaustive UI/API test cases for the Accounts Receivable domain: AR open-item (invoice) view,
record receipt (tender + allocation + WHT leg), credit notes, write-offs, opening balances, and
customer statement / ageing / balance. Cases are Playwright-friendly (navigate by `/admin/...`
route, pick resources by NAME via typeahead, assert visible state) and cite only verified
controllers, permission codes, enum values, and frontend routes.

## Modules / submodules covered

| Submodule | Frontend route(s) | Backend controller · base path |
| --- | --- | --- |
| AR open items (invoices) list + filter + write-off/credit-note actions | `/admin/ar/invoices` | `ArInvoiceController` · `/api/v1/ar/invoices` |
| Record receipt (tender, allocate, optional WHT) | `/admin/ar/receipts/record` | `ArReceiptController` · `/api/v1/ar/receipts` (POST) |
| Receipts list | `/admin/ar/receipts` | `ArReceiptController` · `/api/v1/ar/receipts` (GET list) |
| Receipt detail | `/admin/ar/receipts/uid/:uid` | `ArReceiptController` · `/api/v1/ar/receipts/uid/{uid}` |
| Credit note (raised from invoice row modal) | `/admin/ar/invoices` (modal) | `ArCreditNoteController` · `/api/v1/ar/credit-notes` (POST) |
| Write-off (raised from invoice row modal) | `/admin/ar/invoices` (modal) | `ArWriteOffController` · `/api/v1/ar/write-offs` (POST) |
| Opening balance | `/admin/ar/opening-balance` | `ArOpeningBalanceController` · `/api/v1/ar/opening-balances` (POST) |
| Customer statement | `/admin/ar/statement` | `ArStatementController` · `/api/v1/ar/statement` |
| AR ageing + per-customer balance | `/admin/ar/ageing` | `ArStatementController` · `/api/v1/ar/ageing`, `/api/v1/ar/balance` |

Notes on coverage boundaries (verified by reading the code):
- **No AR invoice creation endpoint exists.** Open items are created by the outbox handler
  `ArSalePostedHandler` (from a posted sale) or by the opening-balance path. `ArInvoiceController`
  exposes only GET (`/uid/{uid}` and the paged list). There is no POST on `/api/v1/ar/invoices`.
- **No AR invoice detail route exists in the frontend.** `admin.routes.ts` has `ar/invoices`
  (list), `ar/receipts`, `ar/receipts/uid/:uid`, `ar/receipts/record`, `ar/statement`,
  `ar/ageing`, and `ar/opening-balance`. There is **no** `ar/invoices/uid/:uid` route, **no**
  standalone credit-note route, and **no** standalone write-off route. Credit notes and write-offs
  are raised via modals on the invoices-list screen. The backend GETs
  `/api/v1/ar/credit-notes`, `/api/v1/ar/write-offs` (list + `uid/{uid}`) have **no UI surface** —
  test them at the API level only.
- **Receipt re-allocation** (`ArReceiptService.reallocate`, permission `AR.RECEIPT.ALLOCATE`)
  exists in the service layer but is **not exposed by any controller endpoint and has no UI**.
  It is covered here as a documented gap (see TC-AR-061).
- **Known FE↔BE contract mismatches** were found while reading the code and are written up as
  defect-hunting cases (TC-AR-041, TC-AR-042, TC-AR-043, TC-AR-026). Steps assert the *intended*
  behaviour so the defect surfaces as a failure.

## Permission codes in scope (exact, from `V11__accounts_receivable.sql`)

| Code | Used by |
| --- | --- |
| `AR.VIEW` | Invoices list, receipts list, receipt detail, credit-note/write-off GET, balance |
| `AR.INVOICE.VIEW` | `ArInvoiceController.getByUid` (scoped) |
| `AR.RECEIPT.RECORD` | `ArReceiptController.record` (scoped on `req.companyUid`) |
| `AR.RECEIPT.ALLOCATE` | Re-allocate (service only; seeded perm, no endpoint/UI) |
| `AR.WRITEOFF` | `ArWriteOffController.writeOff` (scoped on `req.arInvoiceUid`) |
| `AR.CREDITNOTE` | `ArCreditNoteController.raise` (scoped on `req.companyUid`) |
| `AR.STATEMENT.VIEW` | `ArStatementController.statement`, `.ageing` |
| `AR.OPENING.SET` | `ArOpeningBalanceController.setOpeningBalance` (scoped on `req.companyUid`) |

`getByUid` for credit notes (`AR.VIEW`) and write-offs (`AR.VIEW`) and the credit-note/write-off
list (`AR.VIEW`) use `@perm.has('AR.VIEW')` — confirmed in the controllers.

## Enum values in scope (exact, from the enum files)

- `ArInvoiceStatus` = `OPEN`, `PARTIAL`, `PAID`, `WRITTEN_OFF`
- `ArReceiptStatus` = `UNALLOCATED`, `PARTIAL`, `ALLOCATED`
- `ArCreditNoteOrigin` = `STANDALONE`, `SALE_VOID`, `RETURN`
- `ArInvoiceSource` = `SALE`, `OPENING_BALANCE`
- `AgeingBucket` = `CURRENT`, `D1_30`, `D31_60`, `D61_90`, `D90_PLUS`
  (classification boundaries from `ArAgeingQuery.classify`: daysOverdue ≤ 0 → CURRENT; ≤ 30 →
  D1_30; ≤ 60 → D31_60; ≤ 90 → D61_90; else D90_PLUS — overdue measured against `dueDate`.)
- `TenderType` (frontend `ar.model.ts`) = `CASH`, `CHEQUE`, `BANK_TRANSFER`, `MOBILE_MONEY`,
  `OTHER` (the request field `tenderType` is a free `String` on the backend
  `RecordReceiptRequest`; the system convention enum is `{CASH, MOBILE_MONEY}`).

## Type / role variations exercised

| Dimension | Variations covered |
| --- | --- |
| User role | `rootadmin` (superuser bypass, positive), `ACCOUNTANT` (full AR), `SALES_MANAGER`/`SALES_REP` (read AR), CUSTOM role (subset, e.g. `AR.VIEW` only), NO-PERMISSION user (forbidden/empty-nav), a user with `AR.RECEIPT.RECORD` but **not** `AR.WRITEOFF`/`AR.CREDITNOTE` |
| Customer kind | `CASH_WALK_IN` vs `CREDIT_ACCOUNT`; `PartyType` INDIVIDUAL vs BUSINESS |
| Invoice source | `SALE` (from posted sale) vs `OPENING_BALANCE` |
| Invoice status | OPEN, PARTIAL, PAID, WRITTEN_OFF (and illegal transitions) |
| Receipt status | UNALLOCATED, PARTIAL, ALLOCATED (derived) |
| Allocation mode | auto oldest-first (empty allocations) vs manual override |
| WHT | receipt with no WHT vs receipt with WHT_ON_RECEIPT leg |
| Credit note | applied to invoice vs standalone/unapplied; origin STANDALONE |
| Tender | CASH vs MOBILE_MONEY (and the broader FE list) |
| Branch/company | default vs non-default branch; single vs multi-branch user; cross-company/cross-branch denial |
| Currency | base TZS vs foreign (FX realized gain/loss legs) |

---

## TEST CASES

### TC-AR-001 — Receivables (open items) list renders with envelope + pagination
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** AR invoices list (`/admin/ar/invoices` · `GET /api/v1/ar/invoices`)
- **Permission / Role:** `AR.VIEW` — runs as `ACCOUNTANT`; also as NO-PERMISSION user → expect forbidden/hidden nav
- **Variation:** company = default; mix of OPEN/PARTIAL/PAID invoices seeded
- **Preconditions / Seed:** At least 21 AR open items for the active company (≥ 2 pages at size 20). Seed by posting sales (ArSalePostedHandler) and/or via TC-AR-030 opening balances.
- **Steps:**
  1. Log in as ACCOUNTANT, navigate to `/admin/ar/invoices`.
  2. Wait for the list to settle to the idle state.
  3. Read the first-page rows; confirm the `<app-paginator>` controls show.
  4. Click NEXT, then LAST, then FIRST.
- **Test Data:** size = 20; totalElements ≥ 21.
- **Expected Result:** Table shows document no, customer, original + outstanding amount, currency, dates, and a status badge. Response is `ApiResponse<List<ArInvoiceDto>>` with `meta {page,size,totalElements,totalPages,hasNext}`. Paging changes the page param and reloads.
- **Convention Assertions:** C2 envelope + meta; C4 four states (assert idle here); C5 paginator FIRST/PREV/numbers/NEXT/LAST; C8 money formatted, dates ISO; C1 no raw uid shown in any cell; C6 axe-clean.
- **Negative / Edge:** As NO-PERMISSION user, nav item "Receivables" is hidden and direct `/admin/ar/invoices` is redirected (route guard `requirePermission('AR.VIEW')`).

### TC-AR-002 — Invoices list: loading state
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** AR invoices list (`/admin/ar/invoices`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Preconditions / Seed:** Throttle/delay the `GET /api/v1/ar/invoices` response (Playwright route delay).
- **Steps:** 1. Navigate to `/admin/ar/invoices`. 2. Observe the screen before data resolves.
- **Expected Result:** A loading indicator is shown while `state()==='loading'`; replaced by the table when idle.
- **Convention Assertions:** C4 (loading distinct from empty/error/forbidden).
- **Negative / Edge:** None.

### TC-AR-003 — Invoices list: empty state
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** AR invoices list (`/admin/ar/invoices`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Variation:** a freshly-created company with zero AR open items
- **Preconditions / Seed:** Company with no invoices (or filter to a customer with none).
- **Steps:** 1. Switch the company selector to the empty company. 2. Observe.
- **Expected Result:** Distinct empty-state message (`isEmpty() === true`), no table rows, paginator hidden.
- **Convention Assertions:** C4 empty distinct; C5 paginator self-hidden when ≤ 1 page.
- **Negative / Edge:** None.

### TC-AR-004 — Invoices list: error state on server 500
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** AR invoices list (`/admin/ar/invoices`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Preconditions / Seed:** Playwright route-intercept `GET /api/v1/ar/invoices` → 500.
- **Steps:** 1. Navigate to `/admin/ar/invoices`. 2. Observe.
- **Expected Result:** Error state shown (not empty). `state()` is `error`.
- **Convention Assertions:** C4 error distinct.
- **Negative / Edge:** Intercept → 403 yields the forbidden state (`state()==='forbidden'`) — separate assertion.

### TC-AR-005 — Invoices list: filter by customer via picker (by name, not uid)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** AR invoices list (`/admin/ar/invoices`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Variation:** customer = BUSINESS + CREDIT_ACCOUNT
- **Preconditions / Seed:** ≥ 2 customers each with invoices.
- **Steps:**
  1. Navigate to `/admin/ar/invoices`.
  2. Type part of a customer name/code into the customer filter typeahead; wait for the debounced (300 ms) results.
  3. Click the matching customer (chosen by `code — displayName`).
  4. Confirm the list reloads filtered to that customer.
  5. Clear the filter; confirm the full list returns.
- **Test Data:** customer code e.g. `CUST-001`.
- **Expected Result:** List shows only that customer's open items. The customer was chosen from the picker by name; the stored value is the customer uid under the hood.
- **Convention Assertions:** C1 picker-by-name used, no uid typed, no uid shown; C4 idle/empty; C7 only own-company customers appear.
- **Negative / Edge:** **Defect watch** — the FE service sends `customerUid` but the backend list endpoint only reads `customerId` (Long). If the filter does not actually narrow results, this is the latent contract defect (see TC-AR-041). Assert the filtered count is strictly less than the unfiltered count.

### TC-AR-006 — Invoices list: filter by status
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** AR invoices list (`/admin/ar/invoices`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Variation:** status filter ∈ {OPEN, PARTIAL, PAID, WRITTEN_OFF}
- **Preconditions / Seed:** Invoices in each status.
- **Steps:** 1. Navigate to the list. 2. Select status = OPEN. 3. Observe. 4. Repeat for PARTIAL, PAID, WRITTEN_OFF.
- **Expected Result:** Only rows in the chosen status display; status badge colour matches (`OPEN`→warning, `PARTIAL`→info, `PAID`→success, `WRITTEN_OFF`→secondary).
- **Convention Assertions:** C4; C8.
- **Negative / Edge:** **Defect watch** — `status` is sent by the FE but not read by the backend list endpoint (only `companyId`/`customerId`). If filtering is client-illusory, log against TC-AR-041.

### TC-AR-007 — View single AR open item by uid (API, scoped)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** AR invoice get (`GET /api/v1/ar/invoices/uid/{uid}`)
- **Permission / Role:** `AR.INVOICE.VIEW` (`@perm.scoped(#uid,'arinvoice','AR.INVOICE.VIEW')`) — runs as ACCOUNTANT; also as a user lacking it → 403
- **Preconditions / Seed:** One AR invoice uid known.
- **Steps:** 1. `GET /api/v1/ar/invoices/uid/{uid}` with `X-Branch-Uid` for the owning company. 2. Inspect.
- **Test Data:** uid from TC-AR-001.
- **Expected Result:** `ArInvoiceDto` with `source`, `status`, `originalAmount`, `outstandingAmount`, `invoiceDate`, `dueDate`. Scope-checked against caller's active company.
- **Convention Assertions:** C2 envelope; C3 RBAC (`AR.INVOICE.VIEW` distinct from `AR.VIEW`); C7 scope.
- **Negative / Edge:** Caller scoped to a different company → 403 (scope guard). Unknown uid → 404. No frontend route consumes this endpoint (documented gap).

### TC-AR-008 — Record receipt: CASH, auto oldest-first allocation, fully allocated
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Record receipt (`/admin/ar/receipts/record` · `POST /api/v1/ar/receipts`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT; also as SALES_REP lacking it → forbidden nav/route
- **Variation:** customer = CREDIT_ACCOUNT; tender = CASH; allocation = auto oldest-first
- **Preconditions / Seed:** Customer with ≥ 2 OPEN invoices whose total outstanding ≤ receipt amount.
- **Steps:**
  1. Navigate to `/admin/ar/receipts/record`.
  2. Pick the customer via the typeahead (by `code — displayName`).
  3. Enter amount equal to the sum of the two invoices' outstanding; currency TZS; today's date; tender CASH.
  4. Wait for the open invoices to load into the allocation editor.
  5. Click **Auto oldest-first**.
  6. Confirm allocated total == receipt amount and unallocated == 0; submit.
- **Test Data:** inv A outstanding 600.00, inv B outstanding 400.00; amount 1,000.00.
- **Expected Result:** HTTP 201; `ArReceiptDto` with `status = ALLOCATED`, `unallocatedAmount = 0`, two allocation lines. Each invoice's outstanding drops to 0 and status → `PAID`. Success alert shows the receipt number.
- **Convention Assertions:** C1 customer picked by name, allocation rows reference invoices by document no (no uid shown/typed); C2 envelope; C8 money formatting; C6 axe; C3 RBAC.
- **Negative / Edge:** SALES_REP (no `AR.RECEIPT.RECORD`) → route guard redirect + nav item hidden + API 403.

### TC-AR-009 — Record receipt: MOBILE_MONEY, partial allocation (status PARTIAL on both receipt + invoice)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Record receipt (`/admin/ar/receipts/record`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Variation:** tender = MOBILE_MONEY; allocation = manual partial
- **Preconditions / Seed:** Customer with one OPEN invoice outstanding 1,000.00.
- **Steps:**
  1. Navigate, pick customer, amount 1,000.00, tender MOBILE_MONEY, enter a bank/mobile reference.
  2. In the allocation editor enter 600.00 against the single invoice (leave 400 on-account).
  3. Submit.
- **Test Data:** amount 1,000.00; allocate 600.00.
- **Expected Result:** 201; receipt `status = PARTIAL`, `unallocatedAmount = 400.00`; invoice outstanding 400.00, status `PARTIAL` (`deriveInvoiceStatus`/`deriveReceiptStatus`).
- **Convention Assertions:** C1; C8; the bankReference round-trips to the receipt; C6 axe.
- **Negative / Edge:** Allocate exactly the invoice's outstanding while receipt > total → receipt status `PARTIAL` (allocated>0 and unallocated>0).

### TC-AR-010 — Record receipt: fully on-account (no allocation → status UNALLOCATED)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Record receipt (`/admin/ar/receipts/record`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Variation:** customer = CASH_WALK_IN with no open invoices
- **Preconditions / Seed:** Customer with zero open items.
- **Steps:** 1. Pick the customer (no invoices load). 2. Enter amount 500.00, CASH. 3. Submit with no allocations.
- **Expected Result:** 201; receipt `status = UNALLOCATED`, `unallocatedAmount = 500.00`, empty allocation list (BR-AR-05 on-account).
- **Convention Assertions:** C1; C8; C2.
- **Negative / Edge:** None.

### TC-AR-011 — Record receipt: client guard blocks over-allocation
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Record receipt (`/admin/ar/receipts/record`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Steps:**
  1. Pick a customer with one invoice outstanding 1,000.00; receipt amount 800.00.
  2. Enter an allocation of 900.00 against the invoice.
  3. Observe the row highlight and the submit button.
- **Expected Result:** Row flagged `table-danger` (`allocationRowClass`); `overAllocated()`/`anyAllocationExceedsOutstanding()` true; **Submit disabled** (`submitDisabled()`). No POST is sent.
- **Convention Assertions:** C8; client validation distinct from server.
- **Negative / Edge:** Server guard parity — if a forged request bypasses the UI, backend throws `IllegalStateException` ("exceeds outstanding … BR-AR-04") → mapped error response (see TC-AR-012).

### TC-AR-012 — Record receipt: server rejects allocation exceeding outstanding (API)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Record receipt (`POST /api/v1/ar/receipts`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Steps:** 1. POST a receipt whose allocation line `allocatedAmount` > the invoice's outstanding. 2. Inspect.
- **Test Data:** invoice outstanding 100.00; allocation 150.00.
- **Expected Result:** Error (4xx) with message containing "exceeds outstanding … (BR-AR-04)". The receipt is NOT created (whole TX rolls back).
- **Convention Assertions:** C2 errors array populated; C9 append-only (no partial write).
- **Negative / Edge:** Total allocated > receipt amount → "Total allocated … exceeds receipt amount (BR-AR-04)".

### TC-AR-013 — Record receipt with WHT_ON_RECEIPT leg
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Record receipt (`/admin/ar/receipts/record` · `POST /api/v1/ar/receipts`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Variation:** WHT type present (kind = WHT_ON_RECEIPT)
- **Preconditions / Seed:** An active WHT type of kind `WHT_ON_RECEIPT` for the company; customer with an OPEN invoice.
- **Steps:**
  1. Navigate to record receipt; pick customer; amount 1,000.00; allocate fully.
  2. In the WHT section select the WHT type and enter WHT amount 50.00.
  3. Submit.
- **Test Data:** amount 1,000.00; WHT 50.00.
- **Expected Result:** 201; receipt recorded. GL: Cash DR = base(amount − WHT) = 950.00, WHT receivable DR = 50.00, AR control CR = 1,000.00 (`ArReceiptServiceImpl`). A WHT certificate/transaction is captured and linked to the journal entry uid.
- **Convention Assertions:** C1 WHT type chosen by name; C8 money; C2.
- **Negative / Edge:** WHT type present but `whtAmount` ≤ 0 → no WHT leg (treated as no-WHT). WHT type omitted but amount entered → no WHT leg (both required, `hasWht` guard).

### TC-AR-014 — Record receipt: foreign-currency receipt posts realized FX leg
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Record receipt (`POST /api/v1/ar/receipts`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Variation:** receipt currency = USD (foreign); base = TZS; an FX rate exists for the date
- **Preconditions / Seed:** A USD-denominated AR invoice (stamped fxRate at invoice date); a different USD→TZS rate on the receipt date; `REALIZED_FX_GAIN`/`REALIZED_FX_LOSS` GL config set.
- **Steps:** 1. POST a USD receipt allocated to the USD invoice at the receipt-date rate. 2. Fetch the linked GL entry.
- **Expected Result:** 201; GL posts Cash DR (base@settlement), AR CR (base@invoice rate), and a balancing `REALIZED_FX_GAIN` or `REALIZED_FX_LOSS` plug equal to `Σbase_relieved − Σbase_settled`. All legs in base currency; Σ balances.
- **Convention Assertions:** C8 base-currency legs; C2.
- **Negative / Edge:** Same currency == base → FX plug == 0, no FX leg emitted (byte-identical). Unknown FX rate → `FxRateNotFoundException` and the whole TX rolls back.

### TC-AR-015 — Record receipt: GL-posting failure rolls back the whole receipt (API)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Record receipt (`POST /api/v1/ar/receipts`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Preconditions / Seed:** A company missing the `ACCOUNTS_RECEIVABLE` / cash GL config, or a closed period for the receipt date.
- **Steps:** 1. POST a receipt that will fail GL config resolution / period check. 2. Re-query receipts.
- **Expected Result:** Error response; **no** receipt row, **no** allocation, invoices unchanged (atomic, D-4).
- **Convention Assertions:** C9 append-only/atomic; C2 errors.
- **Negative / Edge:** None.

### TC-AR-016 — Record receipt: required-field validation (client)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Record receipt (`/admin/ar/receipts/record`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Steps:** 1. Navigate. 2. Leave customer unselected and amount blank. 3. Try to submit.
- **Expected Result:** Submit disabled (`submitDisabled()` true while no customer / amount ≤ 0 / no date / no company). Inline errors on attempt ("Customer is required", "Enter a valid receipt amount", "Receipt date is required").
- **Convention Assertions:** C4; required-field validation.
- **Negative / Edge:** amount = 0 or negative → blocked; currency blank → "Currency is required".

### TC-AR-017 — Receipts list renders + paginates + customer filter
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Receipts list (`/admin/ar/receipts` · `GET /api/v1/ar/receipts`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT; also NO-PERMISSION → forbidden
- **Preconditions / Seed:** ≥ 21 receipts for the company.
- **Steps:** 1. Navigate to `/admin/ar/receipts`. 2. Verify rows show receipt number, date, amount, unallocated, currency, tender. 3. Page NEXT/LAST/FIRST. 4. Filter by a customer via the picker.
- **Expected Result:** Paged list; each row links to `/admin/ar/receipts/uid/:uid`. `meta` drives the paginator.
- **Convention Assertions:** C1 row link uses uid only in the URL, not shown as text; C2 + meta; C5 paginator; C8; C6 axe.
- **Negative / Edge:** Customer filter (`customerUid`) — backend list only reads `customerId` (Long); assert the filter actually narrows or log against TC-AR-041.

### TC-AR-018 — Receipt detail by uid shows allocations
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Receipt detail (`/admin/ar/receipts/uid/:uid` · `GET /api/v1/ar/receipts/uid/{uid}`)
- **Permission / Role:** `AR.VIEW` (`@perm.scoped(#uid,'arreceipt','AR.VIEW')`) — runs as ACCOUNTANT
- **Preconditions / Seed:** A receipt with ≥ 1 allocation (from TC-AR-008).
- **Steps:** 1. From the receipts list click a row. 2. On the detail page read header + allocation table.
- **Expected Result:** Header shows receipt number, date, amount, unallocated, currency, tender, status; allocation lines list each allocated invoice (by document no) and amount.
- **Convention Assertions:** C1 invoice referenced by document no, uid only in any deep-link URL; C4 loading/error/forbidden; C8; C6 axe.
- **Negative / Edge:** uid from another company → 403 (scoped). Unknown uid → 404/error state.

### TC-AR-019 — Receipt status derivation matrix
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Record receipt (`POST /api/v1/ar/receipts`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Steps:** POST three receipts: (a) no allocation, (b) partial allocation, (c) full allocation; read each `status`.
- **Expected Result:** (a) `UNALLOCATED` (unallocated == amount); (b) `PARTIAL` (allocated > 0 and unallocated > 0); (c) `ALLOCATED` (unallocated == 0) — matches `deriveReceiptStatus`.
- **Convention Assertions:** C2.
- **Negative / Edge:** None.

### TC-AR-020 — Write-off an OPEN invoice (modal on invoices list)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Write-off (`/admin/ar/invoices` modal · `POST /api/v1/ar/write-offs`)
- **Permission / Role:** `AR.WRITEOFF` (`@perm.scoped(#req.arInvoiceUid,'arinvoice','AR.WRITEOFF')`) — runs as ACCOUNTANT; also as a user with `AR.VIEW` but **not** `AR.WRITEOFF` → action hidden + API 403
- **Variation:** invoice status = OPEN → WRITTEN_OFF
- **Preconditions / Seed:** An OPEN invoice with outstanding > 0.
- **Steps:**
  1. Navigate to `/admin/ar/invoices`.
  2. On the target invoice row click **Write off** (visible only when `canWriteOff()`).
  3. In the modal enter a reason and accept today's date; submit.
- **Test Data:** reason "Customer insolvent".
- **Expected Result:** 201; invoice outstanding → 0, status → `WRITTEN_OFF`; GL DR Bad-Debt Expense / CR AR control at carrying base. Success alert; list refreshes; badge now secondary.
- **Convention Assertions:** C1 invoice chosen by row (document no), uid under the hood; C3 RBAC (write-off action hidden without `AR.WRITEOFF`); C8; C9 reversal/append-only (no edit of the original); C6 axe.
- **Negative / Edge:** Reason blank → modal blocks ("Reason is required"); date blank → blocked.

### TC-AR-021 — Write-off illegal: already PAID invoice rejected
- **Type:** Manual (API)
- **Priority:** P1
- **Module / Submodule:** Write-off (`POST /api/v1/ar/write-offs`)
- **Permission / Role:** `AR.WRITEOFF` — runs as ACCOUNTANT
- **Variation:** illegal transition PAID → WRITTEN_OFF
- **Preconditions / Seed:** A fully-paid invoice (status PAID).
- **Steps:** 1. POST a write-off for the PAID invoice. 2. Inspect.
- **Expected Result:** `IllegalStateException` "is already PAID — cannot write off." No GL post, no write-off row.
- **Convention Assertions:** C2 errors; status lifecycle enforced.
- **Negative / Edge:** Already WRITTEN_OFF → same rejection ("is already WRITTEN_OFF"). Invoice with 0 outstanding (but not PAID) → "has no outstanding balance to write off."

### TC-AR-022 — Write-off a PARTIAL invoice writes off the remaining outstanding
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Write-off (`POST /api/v1/ar/write-offs`)
- **Permission / Role:** `AR.WRITEOFF` — runs as ACCOUNTANT
- **Variation:** invoice status = PARTIAL
- **Preconditions / Seed:** An invoice partially paid (outstanding 400 of 1,000).
- **Steps:** 1. POST a write-off for the PARTIAL invoice. 2. Read the write-off DTO + invoice.
- **Expected Result:** Write-off `amount` == remaining face outstanding (400.00); invoice → WRITTEN_OFF, outstanding 0; GL relieves carrying base.
- **Convention Assertions:** C8; C9.
- **Negative / Edge:** None.

### TC-AR-023 — Write-off GET endpoints have no UI (API-only)
- **Type:** Manual (API)
- **Priority:** P3
- **Module / Submodule:** Write-off read (`GET /api/v1/ar/write-offs`, `GET /api/v1/ar/write-offs/uid/{uid}`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Steps:** 1. `GET /api/v1/ar/write-offs?companyId=…`. 2. `GET /api/v1/ar/write-offs/uid/{uid}`.
- **Expected Result:** Paged list and single DTO (uid, customerId, arInvoiceId, writeOffDate, amount, currency, reason, glEntryUid). Both gated `AR.VIEW`.
- **Convention Assertions:** C2 envelope + meta; documented gap: no frontend route consumes these.
- **Negative / Edge:** Caller scoped to another company → 403.

### TC-AR-024 — Raise credit note applied to an invoice (modal on invoices list)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Credit note (`/admin/ar/invoices` modal · `POST /api/v1/ar/credit-notes`)
- **Permission / Role:** `AR.CREDITNOTE` (`@perm.scoped(#req.companyUid,'company','AR.CREDITNOTE')`) — runs as ACCOUNTANT; also as a user with `AR.VIEW` but not `AR.CREDITNOTE` → action hidden + API 403
- **Variation:** origin = STANDALONE; applied to an OPEN/PARTIAL invoice; currency matches invoice
- **Preconditions / Seed:** An OPEN invoice outstanding 1,000.00 in TZS.
- **Steps:**
  1. Navigate to `/admin/ar/invoices`; on the row click **Credit note** (visible only when `canCreditNote()`).
  2. In the modal enter net 200.00, VAT 36.00, reason; currency defaults to the invoice currency; submit.
- **Test Data:** net 200.00, VAT 36.00 (total 236.00).
- **Expected Result:** 201; invoice outstanding → 764.00, status `PARTIAL`; GL DR Revenue + DR VAT / CR AR control (origin STANDALONE posts full legs). Success alert; list refreshes.
- **Convention Assertions:** C1 invoice referenced by row/document no; C3 RBAC; C8; C9 contra posting (not an edit of the invoice); C6 axe.
- **Negative / Edge:** Net ≤ 0 → modal blocks ("Enter a valid net amount"); reason blank → blocked.

### TC-AR-025 — Credit note: amount exceeding outstanding rejected (API)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Credit note (`POST /api/v1/ar/credit-notes`)
- **Permission / Role:** `AR.CREDITNOTE` — runs as ACCOUNTANT
- **Steps:** 1. POST a credit note (net+VAT) > the target invoice's outstanding. 2. Inspect.
- **Test Data:** invoice outstanding 100.00; credit total 150.00.
- **Expected Result:** `IllegalStateException` "Credit note amount … exceeds outstanding …". Nothing posted.
- **Convention Assertions:** C2 errors; C9.
- **Negative / Edge:** Credit note currency ≠ invoice currency → "Credit note currency … does not match invoice currency …" (BR-CUR-06).

### TC-AR-026 — Credit note from invoice row: customer uid contract bug (defect-hunt)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Credit note (`/admin/ar/invoices` modal · `POST /api/v1/ar/credit-notes`)
- **Permission / Role:** `AR.CREDITNOTE` — runs as ACCOUNTANT
- **Preconditions / Seed:** An OPEN invoice on the list.
- **Steps:** 1. Open the credit-note modal from a row. 2. Enter valid net/VAT/reason. 3. Submit. 4. Inspect the request body and response.
- **Expected Result (intended):** Backend resolves the customer by uid and posts the credit note.
- **Convention Assertions:** C1 (the customer should be referenced by the invoice's customer uid, never a numeric id).
- **Negative / Edge:** **Defect** — `ar-invoices-list.component.ts` builds `customerUid: String(inv.customerId)`, i.e. it sends the numeric `customerId`, not the customer uid. The backend `customers.findByCompanyIdAndUid(companyId, req.customerUid())` will fail with "Customer not found". Assert the credit note succeeds; if it 404s on customer lookup, this is the bug.

### TC-AR-027 — Standalone (unapplied) credit note (API)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Credit note (`POST /api/v1/ar/credit-notes`)
- **Permission / Role:** `AR.CREDITNOTE` — runs as ACCOUNTANT
- **Variation:** `arInvoiceUid` = null (unapplied credit); origin STANDALONE
- **Steps:** 1. POST a credit note with `arInvoiceUid` omitted, net 100.00 VAT 18.00. 2. Inspect.
- **Expected Result:** 201; credit note created with `arInvoiceId = null`; GL CR AR at base total (no FX delta, no invoice reduced).
- **Convention Assertions:** C2; C8.
- **Negative / Edge:** No UI surface raises an unapplied credit note (the modal always targets a row) — documented gap.

### TC-AR-028 — Credit note GET endpoints have no UI (API-only)
- **Type:** Manual (API)
- **Priority:** P3
- **Module / Submodule:** Credit note read (`GET /api/v1/ar/credit-notes`, `/uid/{uid}`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Steps:** 1. `GET /api/v1/ar/credit-notes?companyId=…`. 2. `GET …/uid/{uid}`.
- **Expected Result:** Paged list + single `ArCreditNoteDto` (creditNoteNumber, arInvoiceId, noteDate, amount, netAmount, vatAmount, currency, reason, origin, glEntryUid). Gated `AR.VIEW`.
- **Convention Assertions:** C2 + meta; documented gap (no FE route).
- **Negative / Edge:** Cross-company → 403.

### TC-AR-029 — Credit note origin SALE_VOID / RETURN are system-raised only
- **Type:** Manual (verification)
- **Priority:** P3
- **Module / Submodule:** Credit note origin (`ArCreditNoteOrigin`)
- **Permission / Role:** N/A (internal handlers)
- **Steps:** 1. Confirm `RaiseCreditNoteRequest.origin` defaults to STANDALONE for the controller path. 2. Confirm SALE_VOID is raised by the SALE.VOIDED handler and RETURN by SalesReturnService.
- **Expected Result:** The AR credit-note controller path always produces STANDALONE; SALE_VOID/RETURN originate from cross-module handlers (not from this UI/endpoint). SALE_VOID skips the GL post (already reversed by the void path); RETURN posts full legs like STANDALONE.
- **Convention Assertions:** Cross-module documentation only.
- **Negative / Edge:** N/A.

### TC-AR-030 — Set AR opening balance (creates OPENING_BALANCE open item)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Opening balance (`/admin/ar/opening-balance` · `POST /api/v1/ar/opening-balances`)
- **Permission / Role:** `AR.OPENING.SET` (`@perm.scoped(#req.companyUid,'company','AR.OPENING.SET')`) — runs as ACCOUNTANT/ORG_ADMIN; also as a user lacking it → forbidden route + API 403
- **Variation:** customer = CREDIT_ACCOUNT; currency = TZS (base)
- **Preconditions / Seed:** A customer exists.
- **Steps:**
  1. Navigate to `/admin/ar/opening-balance`.
  2. Pick the customer via typeahead; enter amount 5,000.00, currency TZS, invoice date, optional due date, optional document no.
  3. Submit.
- **Test Data:** amount 5,000.00; documentNo "OB-2026-001".
- **Expected Result:** 201; an `ArInvoiceDto` with `source = OPENING_BALANCE`, status OPEN, outstanding 5,000.00; GL DR AR control / CR Opening Balance Equity. Success alert; form resets.
- **Convention Assertions:** C1 customer picked by name; C8 money + ISO dates; C2; C6 axe; C3.
- **Negative / Edge:** Amount ≤ 0 → submit disabled; invoice date blank → disabled; currency blank → "Currency is required".

### TC-AR-031 — Opening balance: foreign currency stamps FX triple
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Opening balance (`POST /api/v1/ar/opening-balances`)
- **Permission / Role:** `AR.OPENING.SET` — runs as ACCOUNTANT
- **Variation:** currency = USD; base = TZS; a USD→TZS rate exists for the invoice date
- **Steps:** 1. POST an opening balance in USD. 2. Read the resulting invoice.
- **Expected Result:** Invoice stamped `fxRate`, `baseOriginalAmount`, `baseOutstandingAmount`, `rateAt`. GL legs in the document currency for AR/equity contra.
- **Convention Assertions:** C8.
- **Negative / Edge:** Unknown FX rate for the date → conversion error, no invoice created.

### TC-AR-032 — Opening balance: GL failure rolls back (API)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Opening balance (`POST /api/v1/ar/opening-balances`)
- **Permission / Role:** `AR.OPENING.SET` — runs as ACCOUNTANT
- **Preconditions / Seed:** Company missing `OPENING_BALANCE_EQUITY` or `ACCOUNTS_RECEIVABLE` GL config.
- **Steps:** 1. POST an opening balance. 2. Verify no open item is created.
- **Expected Result:** Error; no `ar_invoices` row (synchronous post, atomic).
- **Convention Assertions:** C9; C2.
- **Negative / Edge:** None.

### TC-AR-033 — Customer statement renders ageing + open items + recent receipts
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer statement (`/admin/ar/statement` · `GET /api/v1/ar/statement`)
- **Permission / Role:** `AR.STATEMENT.VIEW` — runs as ACCOUNTANT; also as a user lacking it → forbidden route + API 403
- **Variation:** customer with a mix of CURRENT and overdue invoices
- **Preconditions / Seed:** Customer with several OPEN/PARTIAL invoices across due-date ranges and ≥ 1 receipt.
- **Steps:**
  1. Navigate to `/admin/ar/statement`.
  2. Pick the customer via typeahead.
  3. Read the total-outstanding headline, the ageing-bucket table, open-items table, recent-receipts table.
- **Expected Result:** `ArStatementDto` shows `totalOutstanding`, ageing rows per bucket, open items, last-10 receipts. Total == Σ outstanding of OPEN/PARTIAL items.
- **Convention Assertions:** C1 customer by name (no uid shown); C4 loading/empty/error/forbidden; C8 money + dates; C6 axe.
- **Negative / Edge:** **Defect watch** — FE `getStatement` sends `customerUid`, but `ArStatementController.statement` requires `customerId` (Long). If the statement never loads / 400s, this is the contract defect (see TC-AR-042).

### TC-AR-034 — Statement: empty / loading / error / forbidden states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customer statement (`/admin/ar/statement`)
- **Permission / Role:** `AR.STATEMENT.VIEW` — runs as ACCOUNTANT
- **Steps:** 1. Before selecting a customer → empty/idle. 2. Delay the response → loading. 3. Intercept 500 → error. 4. Intercept 403 → forbidden.
- **Expected Result:** Each of the four states is visually distinct (`state()` ∈ idle/loading/error/forbidden; `isEmpty()` when no customer chosen).
- **Convention Assertions:** C4.
- **Negative / Edge:** None.

### TC-AR-035 — Ageing bucket classification boundaries (API)
- **Type:** Manual (API)
- **Priority:** P1
- **Module / Submodule:** Ageing (`GET /api/v1/ar/ageing`)
- **Permission / Role:** `AR.STATEMENT.VIEW` — runs as ACCOUNTANT
- **Variation:** invoices with dueDate offsets from `asAt`: 0, +1, +30, +31, +60, +61, +90, +91 days overdue
- **Preconditions / Seed:** One OPEN invoice per boundary for the customer.
- **Steps:** 1. `GET /api/v1/ar/ageing?companyId=…&customerId=…&asAt=…`. 2. Read the bucketed amounts.
- **Expected Result:** daysOverdue ≤ 0 → `CURRENT`; 1–30 → `D1_30`; 31–60 → `D31_60`; 61–90 → `D61_90`; ≥ 91 → `D90_PLUS` (per `ArAgeingQuery.classify`). Each `ArAgeingRowDto` has `bucket`, `amount`, `currency` (base currency).
- **Convention Assertions:** C2; C8 (currency = company base).
- **Negative / Edge:** Invoice due exactly on `asAt` (daysOverdue 0) → CURRENT. Note the BE enum is `D1_30/D31_60/D61_90/D90_PLUS`, **not** the FE labels `DAYS_1_30/…` (see TC-AR-043).

### TC-AR-036 — Ageing screen + per-customer balance lookup
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Ageing screen (`/admin/ar/ageing` · `GET /api/v1/ar/ageing`, `GET /api/v1/ar/balance`)
- **Permission / Role:** `AR.STATEMENT.VIEW` (ageing) + `AR.VIEW` (balance) — runs as ACCOUNTANT
- **Preconditions / Seed:** Company with aged invoices; a customer with a known balance.
- **Steps:**
  1. Navigate to `/admin/ar/ageing`; the ageing report loads on company select.
  2. In the balance-lookup section pick a customer via typeahead.
  3. Read the returned balance.
- **Expected Result:** Ageing table renders; balance = Σ outstanding (OPEN/PARTIAL) − Σ unallocated receipts (`ArBalanceServiceImpl`), shown with the base currency.
- **Convention Assertions:** C1 customer by name; C4; C8; C6 axe.
- **Negative / Edge:** **Defect watch (high)** — the screen's `getAgeing(companyId)` calls `GET /ar/ageing` with **only** `companyId`, but the backend requires `customerId` (`@RequestParam Long customerId`, not optional). The call will 400. Also the FE `ArAgeingRowDto` model exposes per-customer columns (current/days1to30/…/total) that the backend bucket-row DTO does not return. This is the known FE ageing defect (see TC-AR-043).

### TC-AR-037 — Customer balance endpoint correctness (API)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Balance (`GET /api/v1/ar/balance`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Preconditions / Seed:** Customer with outstanding 1,000.00 and an UNALLOCATED receipt of 300.00.
- **Steps:** 1. `GET /api/v1/ar/balance?companyId=…&customerId=…`. 2. Read `balance`.
- **Expected Result:** `ArBalanceDto.balance == 700.00` (outstanding − unallocated), currency = company base. Used by Sales at finalise for the credit-limit check (FR-AR-19).
- **Convention Assertions:** C2; C8.
- **Negative / Edge:** Customer with no items → balance 0.00. Cross-company customerId → scope guard 403.

### TC-AR-038 — Statement `asAt` defaulting and override (API)
- **Type:** Manual (API)
- **Priority:** P3
- **Module / Submodule:** Statement/ageing (`GET /api/v1/ar/statement`, `/ageing`)
- **Permission / Role:** `AR.STATEMENT.VIEW` — runs as ACCOUNTANT
- **Steps:** 1. Call without `asAt`. 2. Call with `asAt` set to a past date.
- **Expected Result:** Omitted `asAt` defaults to today (`LocalDate.now()`); a past `asAt` re-classifies buckets relative to that date.
- **Convention Assertions:** C8 ISO date.
- **Negative / Edge:** Future `asAt` → everything CURRENT (no overdue).

### TC-AR-039 — RBAC: NO-PERMISSION user sees no AR nav and is blocked on all AR routes
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All AR routes
- **Permission / Role:** none — runs as NO-PERMISSION user
- **Steps:** 1. Log in. 2. Inspect the nav. 3. Attempt to navigate directly to `/admin/ar/invoices`, `/ar/receipts`, `/ar/receipts/record`, `/ar/statement`, `/ar/ageing`, `/ar/opening-balance`.
- **Expected Result:** No AR nav items shown (each item is permission-gated). Each direct route hit is blocked by `requirePermission(...)` and redirected to the neutral admin home. API calls return 403.
- **Convention Assertions:** C3 RBAC across the whole module; C4 forbidden.
- **Negative / Edge:** None.

### TC-AR-040 — RBAC: granular split (view but not act)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Invoices list actions (`/admin/ar/invoices`)
- **Permission / Role:** CUSTOM role with `AR.VIEW` only (no `AR.WRITEOFF`, no `AR.CREDITNOTE`, no `AR.RECEIPT.RECORD`)
- **Steps:** 1. Log in as the CUSTOM user. 2. Open `/admin/ar/invoices`. 3. Inspect for Write-off / Credit-note actions. 4. Try the API directly for each action.
- **Expected Result:** The list renders (read works). Write-off and Credit-note actions are hidden (`canWriteOff()`/`canCreditNote()` false). The "Record Receipt" nav item is hidden. Direct API POSTs to `/ar/write-offs`, `/ar/credit-notes`, `/ar/receipts` → 403.
- **Convention Assertions:** C3 per-action gating; C4.
- **Negative / Edge:** A user with `AR.RECEIPT.RECORD` but not `AR.WRITEOFF` can record receipts but the write-off action stays hidden.

### TC-AR-041 — DEFECT: invoice list customer/status filters not honoured server-side
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Invoices list (`/admin/ar/invoices` · `GET /api/v1/ar/invoices`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Preconditions / Seed:** ≥ 2 customers with invoices.
- **Steps:**
  1. Note the unfiltered row count for the company.
  2. Apply the customer filter (picker) and the status filter.
  3. Compare counts and contents.
- **Expected Result (intended):** Server returns only the selected customer's rows / selected status.
- **Convention Assertions:** C7 scoping; C1 picker.
- **Negative / Edge:** **Defect** — `ArService.listInvoices` sends `customerUid` and `status` query params, but `ArInvoiceController.list` only binds `companyId` and `customerId` (Long). The `customerUid`/`status` params are ignored; the list returns all company rows regardless of filter. Assert the filtered result is actually narrowed; failure confirms the contract gap (FE should send `customerId`, or BE should accept `customerUid`/`status`).

### TC-AR-042 — DEFECT: statement/balance FE sends customerUid, BE expects customerId
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Statement + balance (`/admin/ar/statement`, `/admin/ar/ageing` · `GET /api/v1/ar/statement`, `/balance`)
- **Permission / Role:** `AR.STATEMENT.VIEW` / `AR.VIEW` — runs as ACCOUNTANT
- **Steps:**
  1. On `/admin/ar/statement` pick a customer and observe the network call params.
  2. On `/admin/ar/ageing` pick a customer in the balance lookup and observe.
- **Expected Result (intended):** Statement and balance load for the chosen customer.
- **Convention Assertions:** C2; C8.
- **Negative / Edge:** **Defect** — `ArService.getStatement` and `getBalance` send `customerUid`, but `ArStatementController.statement`/`balance` declare `@RequestParam Long customerId` (mandatory). The missing `customerId` yields a 400 (missing required param) or a wrong-type bind error. Assert the screen loads data; if it errors on a missing/typed param, confirm the defect.

### TC-AR-043 — DEFECT: ageing FE↔BE shape + param mismatch
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Ageing screen (`/admin/ar/ageing` · `GET /api/v1/ar/ageing`)
- **Permission / Role:** `AR.STATEMENT.VIEW` — runs as ACCOUNTANT
- **Steps:** 1. Open `/admin/ar/ageing`. 2. Observe the `GET /ar/ageing` request and response handling.
- **Expected Result (intended):** A company-wide ageing report renders.
- **Convention Assertions:** C2; C8.
- **Negative / Edge:** **Defect (3 parts):** (a) FE `getAgeing(companyId)` omits `customerId`, but BE requires it (`@RequestParam Long customerId`) → 400. (b) BE returns `List<ArAgeingRowDto>` of `{bucket, amount, currency}` per **bucket**, but the FE `ArAgeingRowDto` model expects per-**customer** rows `{customerCode, customerName, current, days1to30, …, total}` → render mismatch. (c) FE bucket labels (`DAYS_1_30`, `DAYS_91_PLUS`) differ from the BE enum (`D1_30`, `D90_PLUS`). Assert the report renders correctly; any of these failing confirms the defect. There is no company-wide (all-customers) ageing endpoint server-side.

### TC-AR-044 — Multi-tenancy: AR data is company-scoped (cross-company denied)
- **Type:** Manual (API)
- **Priority:** P1
- **Module / Submodule:** All AR list/get endpoints
- **Permission / Role:** `AR.VIEW` etc. — runs as a tenant-A user
- **Preconditions / Seed:** Tenant A and tenant B each with AR data.
- **Steps:**
  1. As tenant-A user, list `/api/v1/ar/invoices?companyId=<A>` → only A's rows.
  2. List `/api/v1/ar/invoices?companyId=<B>` → 403 (scope guard `assertCanActIn`).
  3. `GET /api/v1/ar/receipts/uid/{B-receipt}` → 403 (scoped get).
- **Expected Result:** Caller can never read another company's AR rows; scope guard denies.
- **Convention Assertions:** C7 multi-tenancy; C3.
- **Negative / Edge:** rootadmin sees across tenants (superuser bypass) — positive control only, never used for negative auth.

### TC-AR-045 — Branch scoping: user acting in an unassigned branch is denied
- **Type:** Manual (API)
- **Priority:** P1
- **Module / Submodule:** Record receipt / write-off / credit note (scoped POSTs)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as a user assigned to branch X only
- **Variation:** branch = non-default; user assigned to ONE branch
- **Preconditions / Seed:** Company with branches X (assigned) and Y (not assigned).
- **Steps:** 1. POST a receipt with `X-Branch-Uid` = Y. 2. Inspect.
- **Expected Result:** Denied (the active branch must be one the user is assigned to; scope guard rejects). Receipt header branch stamps the active branch from `RequestContext`.
- **Convention Assertions:** C7 branch scope; C3.
- **Negative / Edge:** A user assigned to ALL branches can act in any; switching the active branch (X-Branch-Uid) changes the stamped branch on new receipts.

### TC-AR-046 — Receipt header stamps the active branch
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Record receipt (`POST /api/v1/ar/receipts`)
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT (multi-branch)
- **Variation:** default vs non-default branch
- **Steps:** 1. Record a receipt with `X-Branch-Uid` = default branch. 2. Record another with a non-default branch. 3. Read each `branchId`.
- **Expected Result:** Each receipt's `branchId` matches the active branch in the request context.
- **Convention Assertions:** C7.
- **Negative / Edge:** Opening balances carry `branchId = null` (no branch tag, by design).

### TC-AR-047 — Money + date conventions across AR screens
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Invoices list, receipts, statement, ageing
- **Permission / Role:** `AR.VIEW` / `AR.STATEMENT.VIEW` — runs as ACCOUNTANT
- **Steps:** 1. Visit each screen with seeded data. 2. Inspect money + date rendering.
- **Expected Result:** Money values display with two decimals (coerced via `+v`), currency shown; dates ISO `yyyy-MM-dd`. No `.startsWith`/`.trim` called on raw money (no NaN/`undefined` rendered).
- **Convention Assertions:** C8 money/date; C2 (wire values may be number or string).
- **Negative / Edge:** A money value arriving as a string still renders correctly (no crash).

### TC-AR-048 — Accessibility (axe) sweep of AR screens
- **Type:** Automated (Playwright + axe)
- **Priority:** P2
- **Module / Submodule:** `/admin/ar/invoices`, `/ar/receipts`, `/ar/receipts/record`, `/ar/receipts/uid/:uid`, `/ar/statement`, `/ar/ageing`, `/ar/opening-balance`
- **Permission / Role:** appropriate AR perms — runs as ACCOUNTANT
- **Steps:** 1. For each route, load with data. 2. Run an axe scan. 3. Tab through interactive elements; open the write-off + credit-note modals via keyboard.
- **Expected Result:** Axe-clean (WCAG 2.1 AA); tables have captions/scope; inputs have labels; modals are keyboard-operable and focus-trapped; the typeahead pickers are reachable and announce results.
- **Convention Assertions:** C6 a11y.
- **Negative / Edge:** None.

### TC-AR-049 — Invoice status transition: OPEN → PARTIAL → PAID via receipts
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Record receipt + invoices list
- **Permission / Role:** `AR.RECEIPT.RECORD` + `AR.VIEW` — runs as ACCOUNTANT
- **Variation:** lifecycle OPEN → PARTIAL → PAID
- **Preconditions / Seed:** One OPEN invoice outstanding 1,000.00.
- **Steps:** 1. Record a 400.00 receipt allocated to it → status PARTIAL. 2. Record a 600.00 receipt allocated to it → status PAID. 3. Verify on the list each time.
- **Expected Result:** Statuses progress OPEN→PARTIAL→PAID (`deriveInvoiceStatus`); outstanding 1,000→600→0.
- **Convention Assertions:** C8; status badges update.
- **Negative / Edge:** Attempting to allocate to a PAID invoice (outstanding 0) → allocation 0/blocked; an allocation > 0 against it → "exceeds outstanding".

### TC-AR-050 — Invoice status transition: OPEN → WRITTEN_OFF (terminal)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Write-off + invoices list
- **Permission / Role:** `AR.WRITEOFF` + `AR.VIEW` — runs as ACCOUNTANT
- **Variation:** lifecycle OPEN → WRITTEN_OFF; then illegal further transitions
- **Steps:** 1. Write off an OPEN invoice. 2. Attempt a receipt allocation to it. 3. Attempt another write-off.
- **Expected Result:** Status WRITTEN_OFF (terminal). It no longer appears as OPEN/PARTIAL in the allocation editor (filtered to OPEN/PARTIAL). A second write-off → "already WRITTEN_OFF" rejection.
- **Convention Assertions:** C9 terminal/append-only; status lifecycle enforced.
- **Negative / Edge:** Receipt auto-allocate skips WRITTEN_OFF items (only OPEN/PARTIAL are fetched in `findOpenForUpdateByCompanyAndCustomer`).

### TC-AR-051 — Credit note drives invoice to PAID
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Credit note + invoices list
- **Permission / Role:** `AR.CREDITNOTE` + `AR.VIEW` — runs as ACCOUNTANT
- **Steps:** 1. On an invoice outstanding 236.00 (net 200 + VAT 36), raise a credit note net 200 + VAT 36. 2. Inspect.
- **Expected Result:** Outstanding → 0; status `PAID` (per `ArCreditNoteServiceImpl`, full-relief sets PAID; partial sets PARTIAL).
- **Convention Assertions:** C8; C9.
- **Negative / Edge:** Credit note < outstanding → status PARTIAL.

### TC-AR-052 — Receipt allocation reduces base_outstanding for FX invoices (API)
- **Type:** Manual (API)
- **Priority:** P3
- **Module / Submodule:** Record receipt FX accounting
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Variation:** foreign-currency invoice + receipt
- **Steps:** 1. Allocate a foreign receipt against a foreign invoice. 2. Read the invoice's `outstandingAmount` and `baseOutstandingAmount`.
- **Expected Result:** Face outstanding reduced by the allocated face; base outstanding reduced by face × invoice rate (clamped ≥ 0).
- **Convention Assertions:** C8.
- **Negative / Edge:** Legacy rate=1 row → base == face, no FX leg.

### TC-AR-053 — Customer kind variations don't block AR receipts
- **Type:** Both
- **Priority:** P3
- **Module / Submodule:** Record receipt
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Variation:** CASH_WALK_IN vs CREDIT_ACCOUNT; INDIVIDUAL vs BUSINESS
- **Steps:** 1. Record an on-account receipt for a CASH_WALK_IN customer. 2. Record an allocated receipt for a CREDIT_ACCOUNT customer.
- **Expected Result:** Both succeed; CASH_WALK_IN typically has no open items so the receipt is on-account (UNALLOCATED); CREDIT_ACCOUNT allocates against open items.
- **Convention Assertions:** C1; C2.
- **Negative / Edge:** Only ACTIVE customers appear in the picker (FE filters `status === 'ACTIVE'`).

### TC-AR-054 — Inactive/archived customer not selectable in pickers
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** All AR customer pickers
- **Permission / Role:** appropriate AR perms — runs as ACCOUNTANT
- **Preconditions / Seed:** One INACTIVE/ARCHIVED customer.
- **Steps:** 1. In any AR picker, search for the inactive customer by name.
- **Expected Result:** It does not appear (FE filters `c.status === 'ACTIVE'`).
- **Convention Assertions:** C1; C9 soft-delete (masters deactivated, not deleted).
- **Negative / Edge:** None.

### TC-AR-055 — Auto oldest-first ordering correctness
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Record receipt allocation editor
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Preconditions / Seed:** Customer with 3 OPEN invoices of different invoice dates.
- **Steps:** 1. Enter a receipt amount covering 1.5 invoices. 2. Click Auto oldest-first.
- **Expected Result:** Oldest invoice filled to full outstanding first, then the next, until the budget runs out; remainder left on-account. (FE sorts by `invoiceDate` asc; BE auto-allocate uses `findOpenForUpdateByCompanyAndCustomer` oldest-first.)
- **Convention Assertions:** C8.
- **Negative / Edge:** Clear-allocations resets all rows.

### TC-AR-056 — Receipt number is system-generated and shown
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Record receipt
- **Permission / Role:** `AR.RECEIPT.RECORD` — runs as ACCOUNTANT
- **Steps:** 1. Record a receipt. 2. Read the success alert and the saved receipt.
- **Expected Result:** A `receiptNumber` (RCT-####) is generated server-side (`ArReceiptNumberGenerator`) and surfaced; it is never hand-typed.
- **Convention Assertions:** C1 (system id, not user-entered); C8.
- **Negative / Edge:** Two rapid receipts get distinct sequential numbers.

### TC-AR-057 — Statement total equals Σ open-item outstanding
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Statement (`GET /api/v1/ar/statement`)
- **Permission / Role:** `AR.STATEMENT.VIEW` — runs as ACCOUNTANT
- **Steps:** 1. Fetch the statement for a customer. 2. Sum the open-items outstanding and the ageing buckets.
- **Expected Result:** `totalOutstanding` == Σ open-items outstanding == Σ ageing buckets; recentReceipts limited to 10.
- **Convention Assertions:** C8.
- **Negative / Edge:** WRITTEN_OFF / PAID items excluded (statement uses `findOpenForStatement` = OPEN/PARTIAL only).

### TC-AR-058 — Receipt detail forbidden/loading/error states
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Receipt detail (`/admin/ar/receipts/uid/:uid`)
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Steps:** 1. Delay the get → loading. 2. Intercept 403 → forbidden. 3. Intercept 404/500 → error.
- **Expected Result:** Each state is distinct on the detail screen.
- **Convention Assertions:** C4.
- **Negative / Edge:** None.

### TC-AR-059 — Opening balance with optional due date and document no
- **Type:** Both
- **Priority:** P3
- **Module / Submodule:** Opening balance (`/admin/ar/opening-balance`)
- **Permission / Role:** `AR.OPENING.SET` — runs as ACCOUNTANT
- **Steps:** 1. Submit an opening balance omitting due date + document no. 2. Submit another with both.
- **Expected Result:** Both succeed; omitted optional fields are sent as `undefined`/null; the resulting invoice's `dueDate`/`documentNo` reflect the input (null when omitted). Ageing of a no-due-date opening balance follows the classify logic (null dueDate edge — verify behaviour).
- **Convention Assertions:** C8.
- **Negative / Edge:** An opening balance with no due date — confirm it does not crash ageing classification (`ChronoUnit.DAYS.between(dueDate, asAt)` requires a non-null dueDate; if null, expect either CURRENT handling or a documented constraint).

### TC-AR-060 — Receipt list row deep-link integrity (uid only in URL)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Receipts list → detail
- **Permission / Role:** `AR.VIEW` — runs as ACCOUNTANT
- **Steps:** 1. On `/admin/ar/receipts`, inspect a row link target. 2. Click it.
- **Expected Result:** The link points to `/admin/ar/receipts/uid/<uid>`; the uid appears only in the URL, never as visible text in the list or detail header. No numeric database id appears in any URL.
- **Convention Assertions:** C1 uid-in-URL-only; no DB id in URL.
- **Negative / Edge:** None.

### TC-AR-061 — GAP: receipt re-allocation has a permission but no endpoint/UI
- **Type:** Manual (verification)
- **Priority:** P3
- **Module / Submodule:** Re-allocate (`ArReceiptService.reallocate`, perm `AR.RECEIPT.ALLOCATE`)
- **Permission / Role:** `AR.RECEIPT.ALLOCATE` — N/A (no caller)
- **Steps:** 1. Search the controllers for any mapping invoking `reallocate`. 2. Search the frontend for any re-allocate UI/route.
- **Expected Result:** `AR.RECEIPT.ALLOCATE` is seeded and `reallocate(...)` exists in the service, but no controller endpoint and no UI expose it. Documented gap — re-allocation cannot be triggered by a user today.
- **Convention Assertions:** Documentation only.
- **Negative / Edge:** If a future endpoint is added, add transition cases: re-allocate restores prior outstanding then re-applies; over-allocation rejected (BR-AR-04).

### TC-AR-062 — Scoped permission failure messages and codes (API)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** All scoped AR POSTs
- **Permission / Role:** various — runs as users lacking the specific code
- **Steps:** 1. As a user with `AR.VIEW` only, POST to `/ar/receipts`, `/ar/write-offs`, `/ar/credit-notes`, `/ar/opening-balances`. 2. Inspect HTTP + envelope.
- **Expected Result:** Each returns 403; the `@PreAuthorize` codes are exactly `AR.RECEIPT.RECORD`, `AR.WRITEOFF`, `AR.CREDITNOTE`, `AR.OPENING.SET` respectively. Scope is checked against the body's company/invoice uid.
- **Convention Assertions:** C2 errors; C3 RBAC; C7 scope.
- **Negative / Edge:** Has the permission but wrong company scope → still 403 (scope guard).
