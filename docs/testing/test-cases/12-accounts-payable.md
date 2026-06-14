# Accounts Payable (AP) — Test Cases

**Scope.** End-to-end UI test cases for the Accounts Payable domain: supplier-bill entry + 3-way match, single-bill payment + payment run (incl. WHT-on-payment), debit notes, AP opening balances, and the supplier statement (balance / ageing / reconciliation). Cases are written against the deployed QA app and are Playwright-friendly (navigate by route, pick by name, assert visible state).

**Note on coverage boundaries.** Two of the four AP "controllers" in the brief — `SupplierBillController` and `BillMatchController` — live in the AP module package but represent the procurement→bill bridge (bill entry + 3-way match). They are included here because the AP UI surfaces them (Enter Bill screen runs the match inline). Where behaviour is backend-only or embedded in another screen, it is called out explicitly.

## Modules / submodules covered

| Controller | Base path | Frontend route(s) | Component(s) |
|---|---|---|---|
| `SupplierBillController` | `/api/v1/ap/supplier-bills` | `/admin/ap/supplier-bills`, `/admin/ap/supplier-bills/enter`, `/admin/ap/supplier-bills/uid/:uid` | `SupplierBillsListComponent`, `EnterBillComponent`, `BillDetailComponent` |
| `BillMatchController` | `/api/v1/ap/supplier-bills/uid/{billUid}/match` (`/run`, `/accept-variance`) | embedded in `/admin/ap/supplier-bills/enter` (no dedicated route) | `EnterBillComponent` (`runMatch`, `acceptVariance`) |
| `ApPaymentController` | `/api/v1/ap/payments` (`/single`, `/payment-run`, `GET /uid/{uid}`, `GET`) | `/admin/ap/payments/record`, `/admin/ap/payments`, `/admin/ap/payments/uid/:uid` | `RecordPaymentComponent` (payment run), `ApPaymentsListComponent` (list + inline pay-single), `ApPaymentDetailComponent` |
| `ApDebitNoteController` | `/api/v1/ap/debit-notes` (`POST`, `GET /uid/{uid}`, `GET`) | **no dedicated route** — raised via modal on `/admin/ap/supplier-bills`; list endpoint has a service method (`listDebitNotes`) but **no UI list screen** | `SupplierBillsListComponent` (debit-note modal) |
| `ApOpeningBalanceController` | `/api/v1/ap/opening-balance` (`POST`) | `/admin/ap/opening-balance` | `ApOpeningBalanceComponent` |
| `ApStatementController` | `/api/v1/ap/statement` (`/balance`, `/ageing`, `/reconciliation`) | `/admin/ap/statement` | `SupplierStatementComponent` |

Nav items (shell): **Payables** (`/admin/ap/supplier-bills`, `AP.VIEW`), **Enter Bill** (`AP.BILL.ENTER`), **Record Payment** (`/admin/ap/payments/record`, `AP.PAYMENT.RUN`), **Payments** (`/admin/ap/payments`, `AP.VIEW`), **Supplier Statement** (`/admin/ap/statement`, `AP.VIEW`), **AP Opening Balance** (`/admin/ap/opening-balance`, `AP.OPENING.SET`).

## Permission codes in scope (EXACT `@PreAuthorize`)

| Code | Where enforced | Description (from V12 seed) |
|---|---|---|
| `AP.VIEW` | list/get on all 4 controllers; statement balance/ageing/reconciliation; `@perm.scoped(#uid,'appayment'/'apdebitnote'/'supplierbill','AP.VIEW')` on detail gets | View the AP sub-ledger, balances, ageing, and the reconciliation read |
| `AP.BILL.ENTER` | `POST /supplier-bills` | Enter a supplier bill (BILL-####) and edit its draft lines |
| `AP.BILL.MATCH` | `POST .../match/run`, `POST .../match/accept-variance` (`@perm.scoped(#billUid,'supplierbill','AP.BILL.MATCH')`) | Run / accept the 3-way match; accept an over-tolerance variance |
| `AP.PAYMENT.RUN` | `POST /payments/single`, `POST /payments/payment-run` | Pay a single bill and run a payment run (PAYRUN-####) |
| `AP.DEBITNOTE` | `POST /debit-notes` | Raise a debit note / adjustment against an open payable |
| `AP.OPENING.SET` | `POST /opening-balance` | Enter AP opening balances at go-live |

**RBAC seeding fact (verified V12__accounts_payable.sql):** only **ORG_ADMIN** is seeded with the six AP.* permissions. **ACCOUNTANT** and **PURCHASE_OFFICER** are NOT granted AP perms by the seed migrations — they are valid *denied* roles for AP negative-auth tests. `rootadmin` bypasses all checks (use only for positive setup, never for negative-auth).

## Enum values (verified from source)

- `SupplierBillStatus` (backend): `DRAFT, MATCHED, HELD, APPROVED, PARTIALLY_PAID, PAID`
- `SupplierBillSource` (backend): `BILL, OPENING_BALANCE` — **FE model declares `PURCHASE_ORDER | OPENING_BALANCE | MANUAL` (mismatch, see DEF-AP-02)**
- `BillMatchStatus`: `MATCHED, HELD_PRICE_VARIANCE, HELD_QTY_VARIANCE, VARIANCE_ACCEPTED`
- `ApPaymentKind`: `SINGLE, PAYMENT_RUN`
- `AgeingBucket` (backend, shared with AR): `CURRENT, D1_30, D31_60, D61_90, D90_PLUS` — **FE model + statement screen declare `CURRENT, DAYS_1_30, DAYS_31_60, DAYS_61_90, DAYS_91_PLUS` (mismatch, see DEF-AP-01)**
- `tenderType` is a free string on the AP request DTOs (`@NotBlank`); FE `TenderType` union is `CASH | CHEQUE | BANK_TRANSFER | MOBILE_MONEY | OTHER`. (Ground-truth POS `TenderType {CASH, MOBILE_MONEY}` is a different, narrower POS enum — do not conflate.)
- 3-way match default tolerance = 2% of PO unit cost (V12 seeds an `ap_settings` row per company with `price_tolerance_pct = 2.0000`, `price_tolerance_abs = 0.0000`).

## Suspected defects surfaced while reading source (verify during execution; log in ISSUES-REGISTER)

- **DEF-AP-01 (FE/BE ageing-bucket mismatch).** Backend `AgeingBucket` serialises `D1_30/D31_60/D61_90/D90_PLUS`; the supplier-statement screen maps only `DAYS_1_30…DAYS_91_PLUS`. Non-CURRENT buckets will fail the `BUCKET_LABEL`/`BUCKET_ORDER` lookup and render the raw enum / unsorted. Exercised by TC-AP-038.
- **DEF-AP-02 (FE/BE bill-source mismatch).** Backend `SupplierBillSource` is `BILL|OPENING_BALANCE`; FE declares `PURCHASE_ORDER|OPENING_BALANCE|MANUAL`. The Source column/badge shows the raw backend value (`BILL`), not a friendly label. Exercised by TC-AP-006, TC-AP-031.
- **DEF-AP-03 (debit-note supplierUid sends numeric id).** `SupplierBillsListComponent.submitDebitNote()` sends `supplierUid: String(bill.supplierId)` (the numeric DB id), not the supplier's `uid`. The backend `RaiseDebitNoteRequest.supplierUid` is `@NotBlank` and resolved as a uid → likely 400/404. Exercised by TC-AP-024.
- **DEF-AP-04 (supplier-bills list shows numeric supplierId).** Both the list and the debit-note column render `bill.supplierId` (a numeric DB id) in the Supplier column — violates C1 (no raw id on screen; show name). Exercised by TC-AP-005.
- **DEF-AP-05 (list-filter param FE/BE mismatch).** The FE `ApService.listBills/listPayments/listDebitNotes` and the statement balance/ageing calls send `supplierUid` (and `status` for bills) as query params, and pass `companyId` as a string; but the backend list/statement endpoints declare `@RequestParam Long companyId, @RequestParam(required=false) Long supplierId` (and **no `status` param** on the bills list). The backend therefore ignores `supplierUid`/`status`, so the Supplier and Status filters do not actually narrow the server-side result. Exercised by TC-AP-004.

## Type / role variations exercised

| Dimension | Variations covered |
|---|---|
| User role (allowed) | `rootadmin` (bypass, setup); `ORG_ADMIN` (full AP) |
| User role (denied) | `ACCOUNTANT`, `PURCHASE_OFFICER`, NO-PERMISSION user (no AP grants → 403 / hidden nav); a CUSTOM role granted only `AP.VIEW` (read-only: cannot enter/match/pay/debit/opening) |
| Supplier kind | `GOODS` (3-way match path with PO/GR), `SERVICE` (no PO, non-stockable lines) |
| Bill source | `BILL` (entered/matched), `OPENING_BALANCE` (opening-balance screen) |
| Bill status lifecycle | DRAFT→MATCHED, DRAFT→HELD→(accept variance)→MATCHED, MATCHED→PARTIALLY_PAID→PAID; illegal: re-match a PAID bill, pay a DRAFT/HELD bill |
| Payment kind | SINGLE (inline pay form), PAYMENT_RUN (Record Payment screen); with/without WHT_ON_PAYMENT |
| Branch / company | default vs non-default branch; single- vs multi-company org; cross-tenant isolation (tenant A cannot see tenant B's bills/payments); user acting in an unassigned branch denied |
| Currency | base TZS; foreign-currency bill (currency on bill ≠ TZS) |
| Screen states | loading / empty / error / forbidden on every list + detail + statement |

---

## Test Cases

### TC-AP-001 — Payables nav + list visible to AP.VIEW holder; hidden for no-permission user
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Supplier Bills list (`/admin/ap/supplier-bills` · `GET /api/v1/ap/supplier-bills`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN; also as NO-PERMISSION user → expect nav hidden + route forbidden
- **Variation:** single-company org
- **Preconditions / Seed:** At least one supplier bill exists for the company (seed via TC-AP-010 or API `POST /supplier-bills`).
- **Steps:**
  1. Log in as ORG_ADMIN; assert the left nav shows **Payables** (`/admin/ap/supplier-bills`).
  2. Navigate to `/admin/ap/supplier-bills`.
  3. Observe the company selector resolves and the bills table renders.
  4. Log out; log in as the NO-PERMISSION user; assert **Payables** nav item is absent.
  5. Directly navigate to `/admin/ap/supplier-bills`.
- **Test Data:** company = "Acme TZ Ltd".
- **Expected Result:** ORG_ADMIN sees a table with caption "Supplier bills" and columns Bill No., Supplier Inv. No., Supplier, Bill Date, Due Date, Gross, Outstanding, Currency, Status, Source. NO-PERMISSION user: nav item hidden; on direct navigation the route guard (`requirePermission('AP.VIEW')`) blocks and the in-page "You don't have permission to view supplier bills." alert (role="alert") shows.
- **Convention Assertions:** C3 (RBAC, gated by `AP.VIEW`); C4 (forbidden state distinct); C2 (list arrives as `ApiResponse` with `meta`); C6 (axe scan clean on the list).
- **Negative / Edge:** Confirm the 403 page does not leak any bill data.

### TC-AP-002 — Supplier bills list: four states (loading / empty / error / forbidden)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Supplier Bills list (`/admin/ap/supplier-bills` · `GET /api/v1/ap/supplier-bills`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** A company with zero bills (for empty); a company with bills (for loaded).
- **Steps:**
  1. Navigate to the list with throttled network → assert loading spinner with `aria-live="polite"` and text "Loading bills…".
  2. Select a company that has no bills → assert empty state "No supplier bills found.".
  3. Force a backend error (stop API / 500) → assert error state "Could not load supplier bills. Please try again." (role="alert").
  4. As a CUSTOM role lacking `AP.VIEW`, hit the route → assert forbidden copy.
- **Test Data:** empty company = "NewCo Ltd".
- **Expected Result:** Each of the four states renders distinctly and only one at a time.
- **Convention Assertions:** C4 (four-state); C6 (axe on loaded + empty); C2 (envelope).
- **Negative / Edge:** Empty state must NOT render the paginator (C5 self-hide when ≤1 page).

### TC-AP-003 — Supplier bills list pagination (FIRST/PREV/pages/NEXT/LAST)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Supplier Bills list (`/admin/ap/supplier-bills` · `GET /api/v1/ap/supplier-bills?page&size`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** ≥ 21 bills for the company (page size = 20) → 2+ pages.
- **Steps:**
  1. Navigate to the list; assert `<app-paginator>` is present with FIRST, PREVIOUS, page numbers, NEXT, LAST.
  2. Click NEXT → page 2 loads (request `page=1`); assert rows change and `meta.page=1`.
  3. Click LAST then FIRST; click a numbered page.
- **Test Data:** seed 25 bills.
- **Expected Result:** `meta {page,size,totalElements,totalPages,hasNext}` drives the control; row set updates per page.
- **Convention Assertions:** C5 (full paginator); C2 (meta); C6 (axe on a paginated view).
- **Negative / Edge:** With exactly 20 bills (1 page) the paginator is self-hidden.

### TC-AP-004 — Supplier bills list: filter by supplier (picker, by name) + by status (DEF-AP-05)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Supplier Bills list (`/admin/ap/supplier-bills` · `GET /api/v1/ap/supplier-bills` — backend params are `companyId` + `supplierId` (Long); the **backend has NO `supplierUid` or `status` query param**. The FE service nonetheless sends `supplierUid` + `status` (see DEF-AP-05), which the backend silently ignores, so status filtering and supplier filtering are effectively client-side / non-functional against the real contract.)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Variation:** filter status = MATCHED
- **Preconditions / Seed:** Two suppliers each with bills in varied statuses.
- **Steps:**
  1. In the Supplier filter, type part of a supplier name → typeahead list appears (role="listbox", options role="option").
  2. Choose the supplier **by name/code** from the suggestions.
  3. Select Status = "Matched" from the status dropdown.
  4. Observe the list filters to that supplier + status.
  5. Click the clear (×) button to reset the supplier filter.
- **Test Data:** supplier = "SUP-001 — Dar Wholesalers".
- **Expected Result (desired):** Only the chosen supplier's MATCHED bills show; clearing resets to all suppliers. The supplier is chosen by name; the stored uid is never typed or visible.
- **Convention Assertions:** C1 (picker by name; uid under the hood, never typed); C5 (paginator reflects filtered count); C7 (only this company's data).
- **Negative / Edge:** **DEF-AP-05** — the FE sends `supplierUid`/`status` but the backend list endpoint only honours `supplierId` (Long) and has no `status` param, so the server-side result may NOT actually narrow. Capture whether the rows truly filter (server) or only appear filtered (client) and log the FE/BE contract mismatch. Status filter "All" + no supplier returns the full company list.

### TC-AP-005 — Supplier column should show supplier name, not numeric id (DEF-AP-04)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Supplier Bills list (`/admin/ap/supplier-bills`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** A bill for a named supplier.
- **Steps:**
  1. Open the list and inspect the "Supplier" column for any row.
- **Test Data:** supplier "Dar Wholesalers".
- **Expected Result (desired):** the human-readable supplier name/code is shown.
- **Convention Assertions:** C1 (no raw machine id on screen — names/codes only).
- **Negative / Edge:** **Current behaviour renders `bill.supplierId` (numeric DB id)** — record as DEF-AP-04 if confirmed.

### TC-AP-006 — Source column renders backend value `BILL` / `OPENING_BALANCE` (DEF-AP-02)
- **Type:** Both
- **Priority:** P3
- **Module / Submodule:** Supplier Bills list + Bill Detail
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** one entered bill (source BILL) + one opening-balance bill (source OPENING_BALANCE).
- **Steps:**
  1. Open the list; read the Source badge on each row.
  2. Open each bill detail; read the Source field.
- **Expected Result:** entered bill shows `BILL`; opening-balance bill shows `OPENING_BALANCE`.
- **Convention Assertions:** C8 (consistent display).
- **Negative / Edge:** FE model lists `PURCHASE_ORDER/MANUAL` which the backend never emits — confirm no UI path expects those (DEF-AP-02).

### TC-AP-007 — Bill detail screen: header, lines, four states; uid only in URL
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Bill Detail (`/admin/ap/supplier-bills/uid/:uid` · `GET /api/v1/ap/supplier-bills/uid/{uid}`)
- **Permission / Role:** `@perm.scoped(#uid,'supplierbill','AP.VIEW')` — runs as ORG_ADMIN; also as CUSTOM role without `AP.VIEW` → forbidden
- **Preconditions / Seed:** one MATCHED bill with ≥2 lines.
- **Steps:**
  1. From the list, click the bill number link → routes to `/admin/ap/supplier-bills/uid/{uid}`.
  2. Assert header card shows Bill No., status badge, Supplier Invoice No., Bill/Due dates, Net/VAT/Gross/Outstanding, Source, GL Entry (if posted).
  3. Assert the Bill Lines table (caption/aria-label "Supplier bill lines") lists each line (#, Description, Billed Qty, Unit Cost, Line Net, Currency).
  4. As a role without `AP.VIEW`, navigate directly → forbidden copy.
- **Test Data:** bill BILL-0007.
- **Expected Result:** All header + line fields render; money formatted (e.g. "1,234.56" with currency). The uid appears only in the URL; never as a label on screen.
- **Convention Assertions:** C1 (uid only in URL; not shown in body — note `purchaseOrderUid`/`postedGlEntryUid`/`glEntryUid` ARE shown as monospace technical fields — flag if these count as exposed machine ids); C4 (loading/error/forbidden); C8 (money/date); C6 (axe).
- **Negative / Edge:** Unknown uid → error state, not a crash.

### TC-AP-008 — Bill detail: Record Payment button visible only for payable status + AP.PAYMENT.RUN
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Bill Detail (`/admin/ap/supplier-bills/uid/:uid`)
- **Permission / Role:** `AP.PAYMENT.RUN` — runs as ORG_ADMIN; also as a CUSTOM role with only `AP.VIEW` → button hidden
- **Variation:** status MATCHED (payable) vs DRAFT (not payable)
- **Preconditions / Seed:** one MATCHED bill, one DRAFT bill.
- **Steps:**
  1. Open the MATCHED bill as ORG_ADMIN → assert "Record Payment" footer button present.
  2. Open the DRAFT bill → assert no payment button.
  3. As AP.VIEW-only role, open the MATCHED bill → assert button hidden.
- **Expected Result:** Button shows only when `canPayBill` (MATCHED/APPROVED/PARTIALLY_PAID) AND `AP.PAYMENT.RUN`.
- **Convention Assertions:** C3 (action gated by permission AND status).

### TC-AP-010 — Enter a GOODS supplier bill against a PO → auto 3-way match → MATCHED + GL posted
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Enter Bill (`/admin/ap/supplier-bills/enter` · `POST /api/v1/ap/supplier-bills`) + match (`POST .../match/run`)
- **Permission / Role:** `AP.BILL.ENTER` (+ `AP.BILL.MATCH` for the inline run) — runs as ORG_ADMIN; also as ACCOUNTANT → forbidden (no AP grant)
- **Variation:** supplier kind = GOODS; currency = TZS; line cost within 2% tolerance of PO
- **Preconditions / Seed:** an approved PO + goods receipt exist for the supplier (so the bill line's `poLineUid`/`grLineUid` can be matched). Seed via procurement TCs / API.
- **Steps:**
  1. Navigate to `/admin/ap/supplier-bills/enter`.
  2. Pick the supplier by name (typeahead) — confirm picker, not a typed uid.
  3. Pick the Purchase Order via `<app-uid-picker>` (by PO number); pick the PO line per bill line.
  4. Enter Supplier Invoice No., Bill Date, Due Date (ISO yyyy-MM-dd), VAT amount, currency TZS.
  5. Add a line (description, billed qty, unit cost = PO cost) and submit.
  6. Observe success toast "Bill entered <billNumber>" and the inline match result.
- **Test Data:** supplierInvoiceNo "INV-9001"; line qty 10 @ 1,000.00; PO cost 1,000.00.
- **Expected Result:** `POST /supplier-bills` returns 201 with a DRAFT bill (BILL-####); the inline match runs and returns `BillMatchResultDto` with each line `MATCHED`; bill status becomes `MATCHED`; DR Purchases / CR AP-control posted (a `postedGlEntryUid` appears on the bill detail).
- **Convention Assertions:** C1 (supplier + PO chosen via picker by name/number; uids hidden); C2 (201 + envelope); C3 (gated `AP.BILL.ENTER`/`AP.BILL.MATCH`); C8 (ISO dates, money strings); C9 (no edit-in-place of postings); C6 (axe on form).
- **Negative / Edge:** ACCOUNTANT hitting the route → forbidden; submit disabled when supplier/invoice/dates/lines missing.

### TC-AP-011 — Enter bill required-field validation (client-side submit guard)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Enter Bill (`/admin/ap/supplier-bills/enter`)
- **Permission / Role:** `AP.BILL.ENTER` — runs as ORG_ADMIN
- **Steps:**
  1. Open the Enter Bill form.
  2. Without selecting a supplier, assert Submit is disabled.
  3. Select supplier but leave Supplier Invoice No. blank → Submit stays disabled.
  4. Fill invoice no. but clear Bill Date / Due Date → Submit disabled.
  5. Remove all line rows → Submit disabled.
- **Expected Result:** `submitDisabled` is true whenever supplier, invoiceNo, billDate, dueDate, company, or any line is missing. No request is sent.
- **Convention Assertions:** C3; required-field UX; C6 (axe).
- **Negative / Edge:** A line with empty description / non-numeric qty → backend `@NotBlank`/`@Valid` rejects (400) if it slips through; assert error surfaced from `errors[]`.

### TC-AP-012 — Enter bill triggers HELD on price variance > tolerance; accept variance → MATCHED + posts
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Enter Bill + match run + accept-variance (`POST .../match/run`, `POST .../match/accept-variance` · `AP.BILL.MATCH`)
- **Permission / Role:** `AP.BILL.MATCH` — runs as ORG_ADMIN; also as a CUSTOM role with `AP.BILL.ENTER` but NOT `AP.BILL.MATCH` → match/accept 403
- **Variation:** price variance line > 2% tolerance
- **Preconditions / Seed:** PO line cost 1,000.00; bill the same line at 1,200.00 (20% over → HELD_PRICE_VARIANCE).
- **Steps:**
  1. Enter the bill with the over-tolerance unit cost.
  2. After submit, the inline match runs → the held line shows badge `HELD_PRICE_VARIANCE` with priceVarianceAmount / priceVariancePct and an "Accept Variance" button.
  3. Assert the bill status is `HELD` and NO GL entry posted yet.
  4. Click "Accept Variance" for the line.
  5. Assert the line flips to `VARIANCE_ACCEPTED`, the bill becomes `MATCHED`, and GL posts.
- **Test Data:** PO cost 1,000.00; billed 1,200.00.
- **Expected Result:** match result reflects HELD then, after the last variance accepted, `billStatus = MATCHED`; DR Purchases / CR AP posted only at that point.
- **Convention Assertions:** C3 (accept gated by `AP.BILL.MATCH`, scoped to the bill uid); C9 (posting only on full match — append-only); C8 (variance amounts/pct formatted).
- **Negative / Edge:** Qty over-billed (billedQty > GR received) → `HELD_QTY_VARIANCE`; accepting it likewise required; a user without `AP.BILL.MATCH` cannot accept (403).

### TC-AP-013 — Enter a SERVICE supplier bill (no PO, non-stockable lines)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Enter Bill (`/admin/ap/supplier-bills/enter`)
- **Permission / Role:** `AP.BILL.ENTER` — runs as ORG_ADMIN
- **Variation:** supplier kind = SERVICE; no purchase order; manual lines
- **Preconditions / Seed:** a SERVICE supplier.
- **Steps:**
  1. Pick the SERVICE supplier by name.
  2. Leave PO blank; add a free-text line (e.g. "Monthly cleaning", qty 1, cost 250,000.00).
  3. Submit.
- **Test Data:** supplier "CleanPro Services" (SERVICE); line "Consultancy" 1 @ 250,000.00.
- **Expected Result:** Bill is created DRAFT; the inline match has no PO/GR to compare → behaviour per backend (lines without `poLineUid`/`grLineUid` are not held). Assert the bill is created and the match result returns.
- **Convention Assertions:** C1 (supplier by picker); C8 (money/date).
- **Negative / Edge:** A SERVICE line referencing a stockable product should still be allowed as a free description (the bill line has no stockable constraint at AP level).

### TC-AP-014 — Enter a foreign-currency bill (currency ≠ TZS)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Enter Bill (`/admin/ap/supplier-bills/enter`)
- **Permission / Role:** `AP.BILL.ENTER` — runs as ORG_ADMIN
- **Variation:** currency = USD with an active FX rate to TZS
- **Preconditions / Seed:** an FX rate USD→TZS exists for the bill date.
- **Steps:**
  1. Enter a bill, set currency = USD, line cost in USD.
  2. Submit and run the match.
- **Expected Result:** Bill stores USD amounts; GL posting converts to base TZS via the document converter. The detail shows the bill currency (USD).
- **Convention Assertions:** C8 (currency shown with amounts); base = TZS for GL.
- **Negative / Edge:** No FX rate for the date → posting/conversion error surfaced (not a silent zero).

### TC-AP-015 — Re-running match on a PAID bill is rejected (illegal transition)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Bill match (`POST .../match/run`)
- **Permission / Role:** `AP.BILL.MATCH` — runs as ORG_ADMIN
- **Variation:** bill status = PAID
- **Preconditions / Seed:** a fully-paid bill (status PAID).
- **Steps:**
  1. Via API (no UI button for re-match exists post-entry) `POST /supplier-bills/uid/{paidBillUid}/match/run`.
- **Expected Result:** Request rejected (illegal transition — only DRAFT/HELD are matchable); HTTP 400/409 with an `errors[]` message; bill remains PAID with its posting unchanged.
- **Convention Assertions:** C9 (no re-posting / no double DR-CR); illegal-transition guard.
- **Negative / Edge:** Also attempt match on an already-MATCHED bill → rejected/no-op.

### TC-AP-020 — Record Payment (payment run) over selected MATCHED/APPROVED/PARTIALLY_PAID bills
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Record Payment (`/admin/ap/payments/record` · `POST /api/v1/ap/payments/payment-run`)
- **Permission / Role:** `AP.PAYMENT.RUN` — runs as ORG_ADMIN; also as PURCHASE_OFFICER → forbidden (no AP grant)
- **Variation:** payment kind = PAYMENT_RUN; tender = BANK_TRANSFER
- **Preconditions / Seed:** a supplier with ≥2 payable bills (MATCHED/APPROVED/PARTIALLY_PAID).
- **Steps:**
  1. Navigate to `/admin/ap/payments/record`.
  2. Pick the supplier by name (typeahead) → their payable bills load as a checkbox list (only MATCHED/APPROVED/PARTIALLY_PAID shown).
  3. Use "Select all"; observe Selected count + Selected total update.
  4. Set Payment Date (defaults to today), Tender Type, optional Bank Reference.
  5. Submit (disabled when zero bills selected or no date).
  6. Observe "Payment run complete — N payment(s) recorded".
- **Test Data:** supplier "Dar Wholesalers"; 2 bills outstanding 1,000.00 + 500.00; tender BANK_TRANSFER; bankReference "TT-55".
- **Expected Result:** `POST /payment-run` returns 201 with the created `ApPaymentDto` (one payment-run payment; kind = PAYMENT_RUN, PAYRUN-#### number, one allocation per bill). NOTE: the backend `ApPaymentController.paymentRun` returns a single `ApPaymentDto`, but the FE `ApService.paymentRun` types the response as `ApPaymentDto[]` — assert the actual single-object body and flag the FE typing mismatch if it causes a render issue. Each paid bill moves to PARTIALLY_PAID/PAID; DR AP / CR Cash posted atomically (GL failure rolls back).
- **Convention Assertions:** C1 (supplier by picker; bills chosen by name/number not uid); C2 (201 + envelope); C3 (gated `AP.PAYMENT.RUN`); C8 (money totals formatted, ISO date); C9 (posting append-only).
- **Negative / Edge:** PURCHASE_OFFICER on the route → forbidden; submit disabled with no bills selected; only payable-status bills appear (DRAFT/HELD/PAID excluded).

### TC-AP-021 — Payment run with WHT-on-payment (cash CR reduced by WHT, WHT leg posted)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Record Payment (`/admin/ap/payments/record` · `POST /payment-run` with `whtTypeUid`+`whtAmount`)
- **Permission / Role:** `AP.PAYMENT.RUN` — runs as ORG_ADMIN
- **Variation:** WHT type kind = WHT_ON_PAYMENT
- **Preconditions / Seed:** an active WHT type with `kind = WHT_ON_PAYMENT` for the company; a payable bill.
- **Steps:**
  1. On Record Payment, pick supplier + select a bill.
  2. In the WHT section choose the WHT type (only active WHT_ON_PAYMENT types are listed) and enter a WHT amount.
  3. Submit.
- **Test Data:** bill outstanding 1,000.00; WHT type "WHT 5% services"; WHT amount 50.00.
- **Expected Result:** Request includes `whtTypeUid` + `whtAmount`; backend reduces the cash CR by 50.00 and captures the WHT payable leg; payment recorded for the gross with WHT withheld.
- **Convention Assertions:** C1 (WHT type chosen from a name list, uid stored under the hood); C8 (amounts formatted).
- **Negative / Edge:** WHT amount left blank / 0 → no WHT leg sent (fields omitted); WHT amount > bill amount → backend validation error surfaced.

### TC-AP-022 — Pay a single bill (inline pay form on Payments list)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** AP Payments list inline pay (`/admin/ap/payments` · `POST /api/v1/ap/payments/single`)
- **Permission / Role:** `AP.PAYMENT.RUN` — runs as ORG_ADMIN; also as AP.VIEW-only CUSTOM role → pay form hidden
- **Variation:** payment kind = SINGLE; partial payment
- **Preconditions / Seed:** one MATCHED bill outstanding 1,000.00.
- **Steps:**
  1. Navigate to `/admin/ap/payments`.
  2. Open the "Pay Single Bill" form; pick the open bill (by bill number / supplier search).
  3. Enter amount = 400.00 (partial), payment date, tender type.
  4. Submit.
- **Test Data:** bill BILL-0010; amount 400.00; tender CASH.
- **Expected Result:** `POST /payments/single` returns 201 with `ApPaymentDto` (kind = SINGLE, allocation 400.00 to the bill); bill becomes PARTIALLY_PAID (outstanding 600.00); DR AP / CR Cash posted.
- **Convention Assertions:** C1 (bill chosen by name/number; uid hidden); C2 (201); C3 (gated `AP.PAYMENT.RUN`; form hidden for AP.VIEW-only); C8 (money/date).
- **Negative / Edge:** amount = 0 or negative → blocked (`@Positive`); amount > outstanding → backend rejects (over-payment) or partial-only rule; AP.VIEW-only role cannot see the form.

### TC-AP-023 — Single payment fully settles a bill → status PAID, outstanding 0
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** AP Payments single (`POST /payments/single`)
- **Permission / Role:** `AP.PAYMENT.RUN` — runs as ORG_ADMIN
- **Variation:** full settlement (MATCHED → PAID)
- **Preconditions / Seed:** a PARTIALLY_PAID bill with 600.00 outstanding.
- **Steps:**
  1. Pay the remaining 600.00 via the single-pay form.
- **Expected Result:** Bill status → PAID; outstanding 0.00 (shown green); a second `ApPaymentDto` (SINGLE) created.
- **Convention Assertions:** C8 (outstanding 0.00 styled success); C9 (separate append-only payment, not an edit of the prior one).
- **Negative / Edge:** Attempt to pay an already-PAID bill → rejected (no outstanding).

### TC-AP-024 — Raise a debit note against an open bill (modal on Payables list) (DEF-AP-03)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Debit note modal (`/admin/ap/supplier-bills` → `POST /api/v1/ap/debit-notes`)
- **Permission / Role:** `AP.DEBITNOTE` — runs as ORG_ADMIN; also as a role lacking `AP.DEBITNOTE` → "Debit Note" action hidden
- **Variation:** bill status MATCHED/APPROVED/PARTIALLY_PAID (action only shows for these)
- **Preconditions / Seed:** a MATCHED bill outstanding 1,000.00.
- **Steps:**
  1. On the Payables list, on a MATCHED row click "Debit Note" → modal "Raise Debit Note — <billNumber>".
  2. Enter Note Date, Net Amount (e.g. 200.00), VAT (default 0), Reason.
  3. Submit.
- **Test Data:** net 200.00; vat 0; reason "Damaged goods returned".
- **Expected Result (desired):** `POST /debit-notes` returns 201 `ApDebitNoteDto` (DN number, amount, reason); the linked bill outstanding reduces by 200.00; DR AP / CR Purchases [+CR VAT] posted; success toast with the DN number; list refreshes.
- **Convention Assertions:** C1 (no typed uid; bill chosen by clicking its row); C2 (201); C3 (gated `AP.DEBITNOTE`); C8 (money/date); C9 (posting append-only).
- **Negative / Edge:** **DEF-AP-03** — the modal sends `supplierUid = String(bill.supplierId)` (numeric id, not the supplier uid). The `@NotBlank` uid-resolution likely returns 400/404 "supplier not found". Capture the actual response; log the defect. Also: net ≤ 0 or blank reason → client-side validation blocks ("Enter a valid net amount." / "Reason is required.").

### TC-AP-025 — Debit-note action hidden for non-payable statuses + for non-AP.DEBITNOTE roles
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Payables list debit-note button visibility
- **Permission / Role:** `AP.DEBITNOTE` — runs as ORG_ADMIN; also CUSTOM role with `AP.VIEW` only
- **Variation:** status DRAFT / HELD / PAID (button absent) vs MATCHED/APPROVED/PARTIALLY_PAID (button present)
- **Steps:**
  1. As ORG_ADMIN, confirm "Debit Note" appears only on MATCHED/APPROVED/PARTIALLY_PAID rows.
  2. As AP.VIEW-only role, confirm no "Debit Note" button on any row.
- **Expected Result:** Visibility = `canDebitNote()` AND payable status.
- **Convention Assertions:** C3 (action gated by permission AND status).

### TC-AP-026 — Debit note GET endpoints (detail + list) have no UI screen (backend-only note)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Debit notes (`GET /api/v1/ap/debit-notes`, `GET /uid/{uid}` · `AP.VIEW`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Steps:**
  1. Confirm via the app there is NO debit-note list route/nav (only the raise modal exists).
  2. Via API, call `GET /ap/debit-notes?companyId=...` and `GET /ap/debit-notes/uid/{uid}` to validate the read path + scoping.
- **Expected Result:** API returns the company's debit notes (paged, `ApiResponse` + meta) and a single DN by uid; `@perm.scoped(...,'apdebitnote','AP.VIEW')` enforced on the detail. UI list is **not implemented** — record as a known gap.
- **Convention Assertions:** C2 (envelope + meta); C3 (scoped view); C7 (company scoping).
- **Negative / Edge:** Cross-company uid → 403/404 via scope guard.

### TC-AP-030 — Set an AP opening balance for a supplier
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** AP Opening Balance (`/admin/ap/opening-balance` · `POST /api/v1/ap/opening-balance`)
- **Permission / Role:** `AP.OPENING.SET` — runs as ORG_ADMIN; also as a role lacking it → nav hidden + route forbidden
- **Variation:** source = OPENING_BALANCE
- **Preconditions / Seed:** an ACTIVE supplier.
- **Steps:**
  1. Navigate to `/admin/ap/opening-balance`.
  2. Pick the supplier by name.
  3. Enter Gross Amount, Bill Date, Due Date, optional currency + supplier invoice no.
  4. Submit.
- **Test Data:** supplier "Dar Wholesalers"; gross 5,000.00; billDate 2026-01-01; dueDate 2026-01-31; supplierInvoiceNo "OB-2025".
- **Expected Result:** `POST /opening-balance` returns 201 `SupplierBillDto` with `source = OPENING_BALANCE`, status reflecting an open payable; DR Opening Balance Equity / CR AP posted. The new opening-balance bill appears in the supplier's statement + Payables list.
- **Convention Assertions:** C1 (supplier by picker); C2 (201); C3 (gated `AP.OPENING.SET`); C8 (gross formatted, ISO dates).
- **Negative / Edge:** Role without `AP.OPENING.SET` → forbidden; gross ≤ 0 → `@Positive` rejects; missing billDate/dueDate → 400.

### TC-AP-031 — Opening-balance bill surfaces with source OPENING_BALANCE on list + statement
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Payables list + Supplier Statement
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** TC-AP-030 executed.
- **Steps:**
  1. On the Payables list, filter to the supplier; confirm the opening-balance bill row shows Source = OPENING_BALANCE.
  2. Open the Supplier Statement for the supplier; confirm the opening balance is included in the outstanding balance + ageing.
- **Expected Result:** Opening balance counts toward outstanding + ageing; source label correct.
- **Convention Assertions:** C7 (company-scoped); C8.
- **Negative / Edge:** DEF-AP-02 — confirm the FE renders `OPENING_BALANCE` correctly (it is shared by both FE/BE so this one should match).

### TC-AP-035 — Supplier Statement: balance + ageing + open bills + reconciliation render
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Supplier Statement (`/admin/ap/statement` · `GET /statement/balance`, `/ageing`, `/reconciliation`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN; also as role without `AP.VIEW` → forbidden
- **Variation:** supplier with outstanding across multiple ageing buckets
- **Preconditions / Seed:** a supplier with bills aged into CURRENT and at least one older bucket.
- **Steps:**
  1. Navigate to `/admin/ap/statement`.
  2. Pick the supplier by name.
  3. Assert outstanding balance, the ageing breakdown rows, the open-bills list (status ≠ PAID), and the reconciliation panel all load (parallel calls).
- **Test Data:** supplier "Dar Wholesalers".
- **Expected Result:** Balance = sum of outstanding; ageing rows show bucket + amount + currency; open bills exclude PAID; reconciliation shows sub-ledger total, GL control balance, difference.
- **Convention Assertions:** C1 (supplier by picker); C2 (envelope on each call); C3 (gated `AP.VIEW`); C4 (loading/error/forbidden — note 403 on any sub-call sets forbidden, except reconciliation which is non-fatal); C8 (money/currency); C6 (axe).
- **Negative / Edge:** Reconciliation failure does NOT block balance/ageing/bills (it is caught and ignored) — verify the rest still renders.

### TC-AP-036 — Supplier Statement four states incl. empty (no supplier selected)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Supplier Statement (`/admin/ap/statement`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Steps:**
  1. Open the statement with no supplier chosen → empty/idle prompt to pick a supplier.
  2. Pick a supplier with throttled network → loading state.
  3. Force a 403 on a sub-call → forbidden state.
  4. Force a 500 → error state.
- **Expected Result:** `isEmpty` (idle + null balance) prompts selection; loading/forbidden/error states distinct (per `LoadState`).
- **Convention Assertions:** C4 (four states incl. forbidden via 403 detection); C6 (axe on empty + loaded).

### TC-AP-037 — Reconciliation: zero difference is the pass condition; non-zero is a defect signal
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Supplier Statement reconciliation (`GET /api/v1/ap/statement/reconciliation`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Variation:** after a full O2C/P2P cycle (enter→match→pay→debit-note)
- **Preconditions / Seed:** run TC-AP-010, TC-AP-020/022, TC-AP-024 for one company.
- **Steps:**
  1. Open the statement for any supplier in the company (reconciliation is company-scoped).
  2. Read sub-ledger total, GL control balance, difference.
- **Test Data:** company "Acme TZ Ltd".
- **Expected Result:** `difference = subLedgerTotal − glControlBalance = 0.00` (sub-ledger ties to GL 2100). A non-zero difference is a finance-grade defect (BR-AP-02 / NFR-AP-01) — log it.
- **Convention Assertions:** C8 (amounts formatted); C9 (postings append-only keep the tie).
- **Negative / Edge:** Intentionally skipping a GL post (simulate) must show a non-zero difference, proving the check is live.

### TC-AP-038 — Ageing buckets beyond CURRENT mislabelled/unsorted (DEF-AP-01)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Supplier Statement ageing (`GET /api/v1/ap/statement/ageing`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Variation:** supplier with outstanding aged 1–30, 31–60, 61–90, 90+
- **Preconditions / Seed:** bills with due dates spanning all buckets, asAt = today.
- **Steps:**
  1. Open the statement for the supplier and inspect the ageing rows.
- **Expected Result (desired):** rows labelled Current / 1–30 / 31–60 / 61–90 / 90+ in order, amounts per bucket.
- **Convention Assertions:** C8 (bucket label + amount + currency).
- **Negative / Edge:** **DEF-AP-01** — backend emits `D1_30/D31_60/D61_90/D90_PLUS`; the screen's `BUCKET_LABEL`/`BUCKET_ORDER` keys are `DAYS_1_30…DAYS_91_PLUS`. Expect non-CURRENT buckets to render the raw enum and/or sort incorrectly (only `CURRENT` matches). Capture the rendered labels; log the FE/BE enum mismatch.

### TC-AP-040 — AP Payments list: rows, detail navigation, four states, pagination
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** AP Payments list (`/admin/ap/payments` · `GET /api/v1/ap/payments`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** ≥ 21 payments for the company.
- **Steps:**
  1. Navigate to `/admin/ap/payments`; assert the table + `<app-paginator>`.
  2. Filter by supplier (picker, by name).
  3. Click a payment row → `/admin/ap/payments/uid/:uid` detail.
  4. Exercise loading/empty/error/forbidden.
- **Expected Result:** Paged list (PAYRUN/single payments) with kind, payment number, date, amount, tender; detail shows allocations per bill.
- **Convention Assertions:** C1 (supplier by picker; row→detail by uid in URL only); C2 (meta); C4; C5; C6.
- **Negative / Edge:** Empty company → empty state, paginator hidden.

### TC-AP-041 — AP Payment detail: header + allocations; uid only in URL
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** AP Payment detail (`/admin/ap/payments/uid/:uid` · `GET /payments/uid/{uid}` with `@perm.scoped(...,'appayment','AP.VIEW')`)
- **Permission / Role:** `AP.VIEW` — runs as ORG_ADMIN; also as role without `AP.VIEW` → forbidden
- **Preconditions / Seed:** one payment-run payment with ≥2 allocations.
- **Steps:**
  1. From the payments list, open a payment.
  2. Assert payment number, kind (PAYMENT_RUN/SINGLE), date, amount, tender, GL entry, and the allocations table (each linked bill uid + allocated amount).
- **Expected Result:** All fields render; allocations sum to the payment amount; the uid is only in the URL.
- **Convention Assertions:** C1 (uid in URL only); C4 (loading/error/forbidden); C8.
- **Negative / Edge:** Cross-tenant payment uid → 403 via scope guard.

### TC-AP-050 — Multi-tenancy: tenant A cannot see tenant B's bills / payments / statement
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** All AP list + scoped detail endpoints
- **Permission / Role:** `AP.VIEW` (+ others) — runs as an ORG_ADMIN of Tenant A
- **Variation:** two organisations/companies A and B, each with AP data
- **Preconditions / Seed:** bills + payments + debit notes in both companies.
- **Steps:**
  1. As Tenant A ORG_ADMIN, open Payables / Payments / Statement → only A's data.
  2. Attempt to open a Tenant B bill detail by its uid URL (`/admin/ap/supplier-bills/uid/{B-uid}`).
  3. Attempt the same for a B payment uid.
- **Expected Result:** Lists show only A's records; B's uids return 403/404 via `@perm.scoped` + company scoping; no B data leaks.
- **Convention Assertions:** C7 (company/tenant isolation); C3 (scope guard); C1.
- **Negative / Edge:** Also vary by branch — see TC-AP-051.

### TC-AP-051 — Branch scoping: user assigned to one branch cannot act in an unassigned branch
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** AP entry/payment with `X-Branch-Uid` context
- **Permission / Role:** `AP.BILL.ENTER` / `AP.PAYMENT.RUN` — runs as an ORG_ADMIN assigned to Branch-1 only
- **Variation:** default vs non-default branch; user assigned to ONE branch; multi-branch company
- **Preconditions / Seed:** company with Branch-1 (default) + Branch-2; user assigned to Branch-1 only.
- **Steps:**
  1. Acting in Branch-1, enter a bill / record a payment → succeeds; record is branch-scoped to Branch-1.
  2. Switch the active branch to Branch-2 (or send `X-Branch-Uid` = Branch-2) and retry.
- **Expected Result:** Branch-1 actions succeed and are tagged with Branch-1 (`branchId` on the DTO). Acting in Branch-2 (unassigned) is denied (403). Branch-2 data is not visible in Branch-1 lists.
- **Convention Assertions:** C7 (branch scoping); C3 (denied in unassigned branch).
- **Negative / Edge:** Switching to an assigned branch (multi-branch user) should succeed; verify the new records carry the active branch.

### TC-AP-060 — Read-only CUSTOM role (AP.VIEW only): can view, cannot act
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All AP screens
- **Permission / Role:** CUSTOM role granted ONLY `AP.VIEW`
- **Steps:**
  1. Log in as the AP.VIEW-only user.
  2. Assert nav shows Payables, Payments, Supplier Statement; hides Enter Bill, Record Payment, AP Opening Balance.
  3. On the Payables list, assert no Enter Bill / Record Payment / Pay / Match / Debit Note actions render.
  4. Directly navigate to `/admin/ap/supplier-bills/enter`, `/admin/ap/payments/record`, `/admin/ap/opening-balance` → each blocked by its route guard.
  5. Attempt the write APIs directly (`POST /supplier-bills`, `/payments/single`, `/debit-notes`, `/opening-balance`) → 403.
- **Expected Result:** Full read access; every write action hidden in UI AND rejected (403) at the API.
- **Convention Assertions:** C3 (per-action permission gating, UI + API); C4 (forbidden distinct).
- **Negative / Edge:** Confirm the guard uses the exact codes (`AP.BILL.ENTER`, `AP.PAYMENT.RUN`, `AP.OPENING.SET`).

### TC-AP-061 — No-permission user: empty AP nav + all AP routes forbidden
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** AP nav + all routes
- **Permission / Role:** NO-PERMISSION user
- **Steps:**
  1. Log in; assert no AP nav items appear.
  2. Directly navigate to each AP route (`/admin/ap/supplier-bills`, `.../enter`, `.../uid/:uid`, `/admin/ap/payments`, `.../record`, `.../uid/:uid`, `/admin/ap/statement`, `/admin/ap/opening-balance`).
- **Expected Result:** Every route is blocked by its `requirePermission(...)` guard; no AP data renders.
- **Convention Assertions:** C3; C4.
- **Negative / Edge:** Confirm direct API calls also return 403 (defence in depth).

### TC-AP-062 — ACCOUNTANT / PURCHASE_OFFICER seeded roles are NOT granted AP perms (RBAC ground truth)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** AP RBAC seeding
- **Permission / Role:** seeded ACCOUNTANT and PURCHASE_OFFICER (no AP grants per V12)
- **Steps:**
  1. Log in as ACCOUNTANT → assert AP nav items absent; AP routes forbidden; AP write APIs 403.
  2. Repeat as PURCHASE_OFFICER.
- **Expected Result:** Both roles are denied AP access by default (only ORG_ADMIN is seeded with AP.*). If the QA seed differs, reconcile against V12__accounts_payable.sql.
- **Convention Assertions:** C3 (RBAC by permission code, not role name — these roles simply lack the codes).
- **Negative / Edge:** If an admin grants `AP.VIEW` to a CUSTOM role, that user CAN view (proves code-based, not role-based, gating) — covered by TC-AP-060.

### TC-AP-070 — Money + date formatting conventions across AP screens
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Enter Bill, Bill Detail, Payments, Statement
- **Permission / Role:** `AP.VIEW` (+ others) — runs as ORG_ADMIN
- **Steps:**
  1. Across bill detail, payment detail, and statement, assert money values display with currency and thousands separators (e.g. "TZS 1,234.56" / monospace "1,234.56" + currency column) and never throw on string-vs-number wire values.
  2. Assert dates render ISO yyyy-MM-dd.
- **Expected Result:** Consistent money/date formatting; coercion (`+v`) handles wire numbers/strings without NaN.
- **Convention Assertions:** C8 (money string + currency; ISO dates).
- **Negative / Edge:** A null/blank money field renders "0.00", not "NaN"/"undefined".

### TC-AP-071 — Accessibility sweep (axe) on all AP screens
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** All AP UI
- **Permission / Role:** ORG_ADMIN
- **Steps:**
  1. Run an axe scan on: Payables list, Enter Bill, Bill Detail, Record Payment, AP Payments list, AP Payment detail, Supplier Statement, AP Opening Balance, Debit Note modal.
- **Expected Result:** No critical/serious axe violations; tables have captions + `scope`; the debit-note modal has `aria-modal`, labelled title, and focus management; typeahead suggestion lists use `role="listbox"`/`role="option"`; required fields are marked.
- **Convention Assertions:** C6 (WCAG 2.1 AA); keyboard-operable typeaheads (Enter selects an option).
- **Negative / Edge:** Modal must trap focus and be closable via the close button / backdrop.
