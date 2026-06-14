# Finance — General Ledger & Cost Dimensions — Test Cases

End-to-end test specification for the General Ledger (Chart of Accounts, manual journals, fiscal
calendar, trial balance, GL posting-account config, year-end close) and the Cost-Centre / Dimension
module (dimension types, dimension values, mandatory-dimension enforcement, dimension-sliced trial
balance), plus the GL Account-Ledger drill-down report. All endpoints, permission codes, enum values
and routes below were read directly from the shipped controllers, DTOs, enums, services and Angular
components — nothing is invented.

## Modules / submodules covered

| Submodule | Controller (API base path) | Frontend route(s) / component |
|---|---|---|
| Chart of Accounts | `ChartOfAccountController` (`/api/v1/gl/accounts`) | `/admin/gl/accounts` (`ChartOfAccountsListComponent`) |
| Manual Journals | `JournalController` (`/api/v1/gl/journals`) | `/admin/gl/journals` (list), `/admin/gl/journals/post` (`PostJournalComponent`), `/admin/gl/journals/uid/:uid` (`JournalEntryDetailComponent`) |
| Fiscal Calendar (years + periods) | `FiscalPeriodController` (`/api/v1/gl/periods`) | `/admin/gl/periods` (`FiscalPeriodsComponent`) |
| Trial Balance | `TrialBalanceController` (`/api/v1/gl/trial-balance`) | `/admin/gl/trial-balance` (`TrialBalanceComponent`) |
| GL Posting-Account Config | `GlConfigController` (`/api/v1/gl/configs`) | `/admin/gl/config` (`GlConfigComponent`) |
| Year-End Close | `YearEndCloseController` (`/api/v1/gl/periods/fiscal-years`) | `/admin/gl/year-end` (`YearEndCloseComponent`) |
| Dimension Types | `DimensionController` (`/api/v1/dimensions`) | `/admin/cost-centre/dimensions` (`DimensionListComponent`) |
| Dimension Values | `DimensionValueController` (`/api/v1/dimension-values`) | `/admin/cost-centre/values` (list), `/admin/cost-centre/values/uid/:uid` (`DimensionValueDetailComponent`) |
| Dimension-Sliced Trial Balance | `DimensionReportController` (`/api/v1/costing/reports`) | `/admin/cost-centre/report` (`CostingReportComponent`) |
| GL Account-Ledger report | `ReportingController` (`/api/v1/reports/account-ledger`) | `/admin/reporting/account-ledger` (`AccountLedgerComponent`) |

## Permission codes in scope (exact `@PreAuthorize` codes)

- `GL.VIEW` — list/view CoA, journals, fiscal periods/years, trial balance, gl_configs (seeded V10).
- `GL.MANAGE` — create/update/deactivate accounts; set gl_configs; (FE also gates "Open fiscal year") (V10).
- `GL.POST` — post manual journal entries + post reversals (V10).
- `GL.PERIOD.CLOSE` — open a fiscal year; close/reopen a fiscal **period** (V10).
- `GL.YEAR.CLOSE` — close/reopen a fiscal **year** (the closing journal → Retained Earnings) (V16).
- `COSTING.VIEW` — list/view dimension types + values; read the sliced TB (V27).
- `COSTING.MANAGE` — toggle mandatory; create/update/(de)activate/delete dimension values (V27).
- `REPORT.LEDGER.VIEW` — GL account-ledger drill-down (V15).

Note: the sliced trial balance requires **both** `COSTING.VIEW` **and** `GL.VIEW`
(`@perm.has('COSTING.VIEW') and @perm.has('GL.VIEW')`).

## Scope-guard semantics

Most write endpoints use `@perm.scoped(#uid|#req.companyUid, '<type>', '<PERM>')` — the permission
**and** the company/branch scope are both enforced. List endpoints use `@perm.has('<PERM>')` and the
service re-asserts `scopeGuard.assertCanActIn(...)` against the resolved company id. Cross-tenant /
cross-branch access is therefore denied at the service layer even when the permission is held.

## Verified enum values (use these exact tokens)

- `AccountType` = `ASSET, LIABILITY, EQUITY, INCOME, EXPENSE`.
- `NormalBalance` = `DEBIT, CREDIT`. Derived: `ASSET/EXPENSE → DEBIT`; `LIABILITY/EQUITY/INCOME → CREDIT` (BR-GL-12; not user-editable).
- `PeriodStatus` = `OPEN, CLOSED` (used for BOTH fiscal periods and fiscal years).
- `JournalSourceType` (manual path only ever creates `MANUAL`; reversal of a manual is also `MANUAL`). Other tokens (`SALES`, `SALES_REVERSAL`, `OPENING_BALANCE`, `YEAR_END_CLOSE`, etc.) appear on system-posted entries and are read-only in the UI.
- `GlConfigKey` (44 keys) — the v1-active sales keys: `SALES_REVENUE, VAT_PAYABLE, ACCOUNTS_RECEIVABLE, CASH`; `RETAINED_EARNINGS` is required for year-end close. The full enum is seeded; the config screen lets you map any key to an account.
- `DimensionSlot` = `COST_CENTRE, DEPARTMENT, DIMENSION_3, DIMENSION_4`. v1 seeds `COST_CENTRE` + `DEPARTMENT` as built-in; `DIMENSION_3/4` reserved. Dimension **types are seeded per company — there is no create/delete endpoint** (only list + mandatory-toggle).
- `MasterStatus` = `ACTIVE, INACTIVE, ARCHIVED` (dimension types/values carry this).

## Verified business rules (assertion targets)

- BR-GL-01: a journal needs ≥2 lines and Σ debits == Σ credits (exact `BigDecimal` compare).
- BR-GL-08: each line is one-sided — a positive debit **xor** a positive credit, non-negative.
- BR-GL-04: cannot post to an **inactive** account (sole exception: the `YEAR_END_CLOSE` closing journal, which may zero a deactivated P&L account).
- BR-GL-05: every line's account must belong to the journal's company.
- BR-GL-06: every line currency must equal the company base currency (TZS).
- BR-GL-03: the posting date must fall in an **OPEN** fiscal period; resolver rejects a closed/absent period.
- BR-GL-02 / C9: the ledger is append-only — entries are never edited or deleted; corrections are **reversals** (swap debit/credit, new entry, `reversalOfId` set, source `MANUAL`).
- BR-CC-04: a dimension tag present on a line must be active, correct-slot, same-company.
- BR-CC-03 / FR-CC-08: mandatory-slot enforcement applies **only to MANUAL journals** (system posters are exempt).
- BR-CC-05: a dimension value with any posting cannot be hard-deleted (DELETE 4xx) — deactivate instead.
- BR-CLOSE-04: the immediately-prior fiscal year must be CLOSED before closing this year.
- BR-CLOSE-05: a year with no periods, or already CLOSED, cannot be closed.
- BR-CLOSE-10: only the most-recently-closed year may be reopened.

## Known FE/BE contract gaps to verify (file as defects if reproduced)

- **G1 (period trial balance):** backend `GET /trial-balance/period` expects `periodId` (numeric Long), but `GlService.getTrialBalanceForPeriod` sends `periodUid` (the period's string uid). The period-filtered trial balance is therefore expected to fail or ignore the filter. TC-GL-049 targets this.
- **G2 (sliced TB period filter — verified CORRECT, no defect):** backend `DimensionReportController` expects `periodId` (Long). The costing-report screen's period `<select>` binds `[value]="p.id"` (the numeric `FiscalPeriodDto.id`, NOT the uid) and `getSlicedTrialBalance` sends the param as `periodId`, so the period filter matches the controller contract and works. (This is unlike the trial-balance screen's G1, which is a genuine defect.) TC-GL-072 verifies the filter functions correctly.
- **G3 (manual-journal dimensions vs mandatory enforcement):** `PostJournalLineRequest` has **no** dimension fields (only `accountUid, debitAmount, creditAmount, lineMemo`), and the post-journal UI has no dimension picker. Yet the GL engine enforces mandatory dimension slots on `MANUAL` journals. So if any dimension is set mandatory, **every** UI-posted manual journal is rejected (no way to supply the slot). TC-GL-065 targets this.
- **G4 (dimension-value parent picker):** the create + detail screens take the parent via a raw `parentUid` **text input**, not an `<app-uid-picker>` (deviates from convention C1). TC-GL-058/060 assert the actual behaviour and flag the deviation.

---

## Type / role variations exercised

| Dimension of variation | Values exercised |
|---|---|
| User role (allowed) | `rootadmin` (superuser bypass), `ACCOUNTANT` (GL.VIEW/POST/MANAGE/PERIOD.CLOSE per seed), `ORG_ADMIN` |
| User role (denied / partial) | NO-PERMISSION user (forbidden + empty nav), `SALES_REP`/`STOREKEEPER` (no GL perms → 403), a CUSTOM role holding `GL.VIEW` only (read-only — no create/post/close), a CUSTOM role holding `COSTING.VIEW` but **not** `GL.VIEW` (sliced TB forbidden) |
| `AccountType` | ASSET, LIABILITY, EQUITY, INCOME, EXPENSE (normal-balance derivation per type) |
| `PeriodStatus` transitions | OPEN→CLOSED (period), CLOSED→OPEN (period), OPEN→CLOSED (year), CLOSED→OPEN (year); illegal repeats |
| `JournalSourceType` | MANUAL (created via UI); SALES / OPENING_BALANCE / YEAR_END_CLOSE (read-only system entries) |
| `DimensionSlot` | COST_CENTRE, DEPARTMENT (built-in); DIMENSION_3/4 (reserved — present in slot picker) |
| `MasterStatus` (dimension value) | ACTIVE ↔ INACTIVE (deactivate/activate), delete-blocked when postings exist |
| Company / branch context | single-company; multi-company isolation (tenant A cannot read tenant B); default vs non-default branch; user acting in an unassigned branch (denied) |
| Screen states | loading / empty / error / forbidden on every list + report; pagination on CoA + dimension-value lists |

---

# TEST CASES

## A. Chart of Accounts (`/admin/gl/accounts` · `/api/v1/gl/accounts`)

### TC-GL-001 — List Chart of Accounts (happy path, paginated, scoped)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Chart of Accounts (`/admin/gl/accounts` · `GET /api/v1/gl/accounts`)
- **Permission / Role:** `GL.VIEW` — runs as `ACCOUNTANT`; also as NO-PERMISSION user → expect forbidden
- **Preconditions / Seed:** Company with ≥1 seeded account (CoA is seeded at company bootstrap).
- **Steps:**
  1. Log in as `ACCOUNTANT`; navigate to `/admin/gl/accounts`.
  2. Observe the company `<select>` defaults to the first company; the table loads.
  3. Read a row: account code, name, type badge, normal-balance, status.
- **Test Data:** default seeded CoA.
- **Expected Result:** table renders account rows; each shows code + name + an `AccountType` badge + DEBIT/CREDIT + ACTIVE; envelope is `ApiResponse<AccountDto[]>` with `meta {page,size,totalElements,totalPages,hasNext}`.
- **Convention Assertions:** C1 (no raw uid in any cell — code/name only; uid lives only in actions/URL); C2 envelope+meta; C3 RBAC; C4 four-state; C5 paginator present (`<app-paginator>`); C6 axe clean; C7 only this company's accounts.
- **Negative / Edge:** NO-PERMISSION user → screen shows forbidden, nav item hidden; API 403.

### TC-GL-002 — CoA list: empty, loading, error, forbidden states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Chart of Accounts (`/admin/gl/accounts`)
- **Permission / Role:** `GL.VIEW`
- **Preconditions / Seed:** A company with zero accounts (or filter to a no-match search) for empty; throttle/network-fail for error.
- **Steps:**
  1. Empty: select a company with no accounts → empty-state message shown, paginator hidden.
  2. Loading: observe the loading indicator during fetch.
  3. Error: simulate a 500 → error state shown (not empty).
  4. Forbidden: as NO-PERMISSION user → forbidden state.
- **Expected Result:** all four states are distinct; the paginator self-hides when totalPages ≤ 1.
- **Convention Assertions:** C4 four distinct states; C5 paginator self-hide; C6 axe.
- **Negative / Edge:** 403 yields `forbidden`, not `error` (component branches on `status === 403`).

### TC-GL-003 — CoA debounced search
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Chart of Accounts (`/admin/gl/accounts` · `GET /api/v1/gl/accounts?q=`)
- **Permission / Role:** `GL.VIEW`
- **Preconditions / Seed:** Accounts whose name/code contains "Cash".
- **Steps:**
  1. Type "Cash" in the search box; wait for the 300ms debounce.
  2. Observe results filter; page resets to 0.
  3. Click "Clear" → full list returns.
- **Expected Result:** list reflects the `q` filter; pagination resets to page 0 on a new query.
- **Convention Assertions:** C5 pagination reset; C4 empty state on no-match; C6 axe.
- **Negative / Edge:** whitespace-only query is treated as no filter.

### TC-GL-004 — Create account: ASSET (normal balance DEBIT)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Chart of Accounts (`POST /api/v1/gl/accounts`)
- **Permission / Role:** `GL.MANAGE` — runs as `ACCOUNTANT`; also as a `GL.VIEW`-only CUSTOM role → create button hidden / 403
- **Variation:** `accountType = ASSET`
- **Preconditions / Seed:** A company; the active code is unique.
- **Steps:**
  1. Click "Add account"; fill code, name, choose type = ASSET.
  2. Submit.
- **Test Data:** code `1100`, name `Bank — Operating`, type `ASSET`.
- **Expected Result:** HTTP 201; row appears; `normalBalance = DEBIT` is derived server-side and shown; `status = ACTIVE`, `active = true`. Request body = `CreateAccountRequest{companyUid, accountCode, name, accountType}`.
- **Convention Assertions:** C1 company chosen by name (not typed uid; `companyUid` resolved under the hood); C2 envelope; C3 RBAC; C6 axe; C8 no money on this form.
- **Negative / Edge:** missing code or name → client blocks ("Account code and name are required."); duplicate code → server 4xx surfaced inline.

### TC-GL-005 — Create account: LIABILITY (normal balance CREDIT)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Chart of Accounts (`POST /api/v1/gl/accounts`)
- **Permission / Role:** `GL.MANAGE` — runs as `ACCOUNTANT`
- **Variation:** `accountType = LIABILITY`
- **Steps:** create code `2100` "VAT Payable" type LIABILITY.
- **Expected Result:** `normalBalance = CREDIT` derived; badge `text-bg-warning`.
- **Convention Assertions:** C1; C2; C6.
- **Negative / Edge:** none beyond TC-GL-004.

### TC-GL-006 — Create account: EQUITY (CREDIT)
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Chart of Accounts (`POST /api/v1/gl/accounts`)
- **Permission / Role:** `GL.MANAGE`
- **Variation:** `accountType = EQUITY`
- **Steps:** create code `3900` "Retained Earnings" type EQUITY.
- **Expected Result:** `normalBalance = CREDIT`; badge `text-bg-info`. (This account is needed for year-end close mapping — see TC-GL-040.)
- **Convention Assertions:** C1; C2; C6.

### TC-GL-007 — Create account: INCOME (CREDIT)
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Chart of Accounts (`POST /api/v1/gl/accounts`)
- **Permission / Role:** `GL.MANAGE`
- **Variation:** `accountType = INCOME`
- **Steps:** create code `4000` "Sales Revenue" type INCOME.
- **Expected Result:** `normalBalance = CREDIT`; badge `text-bg-success`.
- **Convention Assertions:** C1; C2; C6.

### TC-GL-008 — Create account: EXPENSE (DEBIT)
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Chart of Accounts (`POST /api/v1/gl/accounts`)
- **Permission / Role:** `GL.MANAGE`
- **Variation:** `accountType = EXPENSE`
- **Steps:** create code `6000` "Rent Expense" type EXPENSE.
- **Expected Result:** `normalBalance = DEBIT`; badge `text-bg-danger`.
- **Convention Assertions:** C1; C2; C6.

### TC-GL-009 — Update account: rename + change type
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Chart of Accounts (`PUT /api/v1/gl/accounts/uid/{uid}`)
- **Permission / Role:** `GL.MANAGE` (`@perm.scoped(#uid,'account','GL.MANAGE')`)
- **Preconditions / Seed:** an existing account (TC-GL-004).
- **Steps:** edit name; submit `UpdateAccountRequest{name, accountType, active}`.
- **Expected Result:** updated values returned; normal balance re-derived if type changed.
- **Convention Assertions:** C1 uid only in URL path, not on screen; C2; C3.
- **Negative / Edge:** edit as `GL.VIEW`-only role → 403.

### TC-GL-010 — Deactivate an account (soft, C9)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Chart of Accounts (FE deactivate uses `PUT .../uid/{uid}` with `active:false`; a `DELETE .../uid/{uid}` also exists → 204)
- **Permission / Role:** `GL.MANAGE`
- **Preconditions / Seed:** an active account with no postings.
- **Steps:** click "Deactivate" on a row.
- **Expected Result:** account becomes inactive (status reflects deactivation); it is excluded from the active-accounts picker used by the journal form and gl-config. Soft, never hard-deleted from the UI.
- **Convention Assertions:** C9 soft-delete; C1; C3.
- **Negative / Edge:** deactivated account no longer selectable when posting a journal (TC-GL-024).

### TC-GL-011 — Reactivate an account
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Chart of Accounts (`PUT .../uid/{uid}` with `active:true`)
- **Permission / Role:** `GL.MANAGE`
- **Preconditions / Seed:** a deactivated account (TC-GL-010).
- **Steps:** click "Reactivate".
- **Expected Result:** account ACTIVE again; reappears in pickers.
- **Convention Assertions:** C9; C3.

### TC-GL-012 — Get account by uid (detail fetch)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Chart of Accounts (`GET /api/v1/gl/accounts/uid/{uid}` · `@perm.scoped(#uid,'account','GL.VIEW')`)
- **Permission / Role:** `GL.VIEW`
- **Preconditions / Seed:** an account uid.
- **Steps:** call the endpoint with a valid uid; then with a uid from another company.
- **Expected Result:** valid → `AccountDto`; cross-company uid → 403/404 (scope guard).
- **Convention Assertions:** C7 cross-tenant denied; C1.
- **Negative / Edge:** unknown uid → 404.

### TC-GL-013 — CoA create RBAC denial (GL.VIEW only)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Chart of Accounts (`POST /api/v1/gl/accounts`)
- **Permission / Role:** CUSTOM role with `GL.VIEW` only — expect no "Add account" button; direct POST → 403
- **Steps:** log in as the read-only custom role; confirm create control is hidden; attempt the API directly.
- **Expected Result:** UI hides the create form (`canManage()` false); API returns 403.
- **Convention Assertions:** C3 RBAC at UI + API; C6.

### TC-GL-014 — CoA multi-tenant isolation
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Chart of Accounts (`GET /api/v1/gl/accounts?companyId=`)
- **Permission / Role:** `GL.VIEW` — user assigned to company A only
- **Preconditions / Seed:** two companies A, B each with distinct accounts.
- **Steps:** as a company-A user, list accounts; attempt to request company B's `companyId`.
- **Expected Result:** only A's accounts visible; requesting B's companyId is denied by `assertCanActIn`.
- **Convention Assertions:** C7 tenant isolation; C3.

---

## B. Manual Journals (`/admin/gl/journals*` · `/api/v1/gl/journals`)

### TC-GL-015 — Journal list (paged, scoped)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Journals (`/admin/gl/journals` · `GET /api/v1/gl/journals`)
- **Permission / Role:** `GL.VIEW` — also NO-PERMISSION user → forbidden
- **Preconditions / Seed:** ≥1 posted journal in the company.
- **Steps:** navigate to `/admin/gl/journals`; read rows (batch number, date, description, source-type badge).
- **Expected Result:** rows show batchNumber + postingDate + description + sourceType; envelope with meta; default page size 20.
- **Convention Assertions:** C1 (batch number shown, not uid; uid only in detail link); C2 meta; C4; C5; C6; C7.
- **Negative / Edge:** empty company → empty state.

### TC-GL-016 — Post a balanced 2-line manual journal (happy path)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Journals (`/admin/gl/journals/post` · `POST /api/v1/gl/journals`)
- **Permission / Role:** `GL.POST` (`@perm.scoped(#req.companyUid,'company','GL.POST')`) — runs as `ACCOUNTANT`; also as `GL.VIEW`-only → post nav/route forbidden
- **Variation:** `sourceType = MANUAL` (form hard-codes it)
- **Preconditions / Seed:** two active accounts in an OPEN period; posting date in that period.
- **Steps:**
  1. Navigate to `/admin/gl/journals/post`.
  2. Posting date defaults to today; enter a description.
  3. Line 1: pick account "Bank — Operating" (by name in the per-line dropdown), debit 1,000.00.
  4. Line 2: pick account "Sales Revenue", credit 1,000.00.
  5. Watch the running totals: Debits 1,000.00, Credits 1,000.00, Difference 0.00; "Post" enables.
  6. Click Post.
- **Test Data:** desc `June accrual`; line1 DR 1000.00; line2 CR 1000.00.
- **Expected Result:** HTTP 201; navigates to `/admin/gl/journals/uid/{uid}`; batchNumber `JB-####` shown in success toast; entry `sourceType = MANUAL`, `reversalOfId = null`, two balanced lines.
- **Convention Assertions:** C1 accounts picked by name per line (no uid typed; `accountUid` stored under the hood); C2 envelope; C3 RBAC; C8 money as decimal strings on the wire ("0"/amount); C9 append-only (a new entry, not a mutation); C6 axe.
- **Negative / Edge:** "Post" stays disabled while unbalanced / no date / no description / posting in progress.

### TC-GL-017 — Reject unbalanced journal (client guard + server BR-GL-01)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Journals (`POST /api/v1/gl/journals`)
- **Permission / Role:** `GL.POST`
- **Steps:**
  1. Enter line1 DR 1000.00, line2 CR 900.00.
  2. Observe Difference = 100.00; "Post" disabled (client guard).
  3. (API-level) bypass the UI and POST the unbalanced body directly.
- **Expected Result:** UI never enables Post; direct API call returns 4xx with message "Journal entry is unbalanced (BR-GL-01)…".
- **Convention Assertions:** C2 errors array surfaced; C3.
- **Negative / Edge:** Σ debits == Σ credits exact compare — 0.01 difference must reject.

### TC-GL-018 — Reject single-line journal (≥2 lines, BR-GL-01)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Journals (`POST /api/v1/gl/journals`)
- **Permission / Role:** `GL.POST`
- **Steps:** The UI starts with 2 lines and won't drop below 2 (`removeLine` no-ops at length 2). API-post a 1-line body.
- **Expected Result:** API returns "A journal entry requires at least 2 lines (BR-GL-01)…".
- **Convention Assertions:** C2.
- **Negative / Edge:** UI cannot reach this state — server backstop only.

### TC-GL-019 — Reject a line with both debit and credit (one-sided, BR-GL-08)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Journals (`POST /api/v1/gl/journals`)
- **Permission / Role:** `GL.POST`
- **Steps:** enter a line with DR 500 and CR 500.
- **Expected Result:** UI blocks with "A line cannot have both a debit and a credit amount."; API rejects with "must have a positive debit OR a positive credit, not both or neither (BR-GL-08)".
- **Convention Assertions:** C2; C3.
- **Negative / Edge:** a line with zero on both sides → "Each line must have a debit or credit amount."

### TC-GL-020 — Reject a line with a negative amount
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Journals (`POST /api/v1/gl/journals`)
- **Permission / Role:** `GL.POST`
- **Steps:** API-post a line with debit `-100`.
- **Expected Result:** 4xx "Journal line amounts must be non-negative (BR-GL-08)".
- **Convention Assertions:** C2.
- **Negative / Edge:** boundary value 0 also rejected (must be > 0 on one side).

### TC-GL-021 — Reject posting into a CLOSED period (BR-GL-03)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Journals (`POST /api/v1/gl/journals`)
- **Permission / Role:** `GL.POST`
- **Preconditions / Seed:** a fiscal period that is CLOSED (close one via TC-GL-033).
- **Steps:** set the posting date inside the closed period; post a balanced entry.
- **Expected Result:** server rejects — no OPEN period resolved for the date (period resolver throws); error surfaced inline.
- **Convention Assertions:** C2; C9 (no partial write — transactional).
- **Negative / Edge:** a date with no fiscal period at all → also rejected.

### TC-GL-022 — Reject posting to an account from another company (BR-GL-05)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Journals (`POST /api/v1/gl/journals`)
- **Permission / Role:** `GL.POST`
- **Steps:** API-post a journal for company A but supply an `accountUid` belonging to company B.
- **Expected Result:** account lookup is `findByCompanyIdAndUid` → NotFound for B's account in A; or BR-GL-05 mismatch. 4xx.
- **Convention Assertions:** C7; C2.
- **Negative / Edge:** UI cannot do this (picker only lists the selected company's accounts).

### TC-GL-023 — Currency must equal company base currency (BR-GL-06)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Journals (`POST /api/v1/gl/journals`)
- **Permission / Role:** `GL.POST`
- **Preconditions / Seed:** company base = TZS.
- **Steps:** the manual path resolves currency to the company base; confirm posted lines carry currency TZS.
- **Expected Result:** lines stored with `currency = TZS`; a non-base currency on a line would be rejected by the engine ("does not match company base currency … (BR-GL-06)").
- **Convention Assertions:** C8 money/currency; C2.
- **Negative / Edge:** N/A via UI (currency not user-entered on this form).

### TC-GL-024 — Cannot select an inactive account when posting (BR-GL-04)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Journals (`POST /api/v1/gl/journals`)
- **Permission / Role:** `GL.POST`
- **Preconditions / Seed:** a deactivated account (TC-GL-010).
- **Steps:** open the post form; inspect a line's account dropdown.
- **Expected Result:** the deactivated account is absent from the picker (the form loads `listAllActiveAccounts`); the engine independently rejects a post to an inactive account.
- **Convention Assertions:** C1 picker; C2; C9.
- **Negative / Edge:** API-post to the inactive account uid → "is inactive; cannot post to it (BR-GL-04)".

### TC-GL-025 — Add / remove journal lines dynamically
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Journals (`/admin/gl/journals/post`)
- **Permission / Role:** `GL.POST`
- **Steps:** click "Add line" to reach 4 lines; remove one; verify cannot go below 2.
- **Expected Result:** lines add/remove; minimum 2 enforced; running totals recompute live.
- **Convention Assertions:** C6 keyboard-operable add/remove; running totals reflect each line.
- **Negative / Edge:** removing down to 2 then attempting a 3rd remove no-ops.

### TC-GL-026 — Journal entry detail: balanced lines + totals
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Journals (`/admin/gl/journals/uid/:uid` · `GET .../journals/uid/{uid}`)
- **Permission / Role:** `GL.VIEW` (`@perm.scoped(#uid,'journalentry','GL.VIEW')`)
- **Preconditions / Seed:** a posted journal (TC-GL-016).
- **Steps:** open the detail page.
- **Expected Result:** header shows batchNumber, postingDate, sourceType badge, description; lines table shows account code+name, debit, credit, memo; column totals equal; "Balanced" indicator true.
- **Convention Assertions:** C1 account shown by code/name, uid only in the URL; C8 money formatted; C6.
- **Negative / Edge:** cross-company uid → scope-denied.

### TC-GL-027 — Reverse a MANUAL journal (append-only correction, BR-GL-11 / C9)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Journals (`POST /api/v1/gl/journals/uid/{uid}/reverse` · `@perm.scoped(#uid,'journalentry','GL.POST')`)
- **Permission / Role:** `GL.POST`
- **Preconditions / Seed:** a posted MANUAL journal, not itself a reversal, in an OPEN period.
- **Steps:**
  1. Open the entry detail; the "Reverse" button is visible (`canReverse` = GL.POST AND isManual AND not already a reversal).
  2. Click Reverse.
- **Expected Result:** HTTP 201; navigates to the new reversing entry; new entry has swapped debit/credit per line, `sourceType = MANUAL`, `reversalOfId` = the original id, its own `JB-####`. Original entry is unchanged (append-only).
- **Convention Assertions:** C9 append-only (original untouched; correction is a new entry); C1; C2; C3.
- **Negative / Edge:** Reverse button hidden for `SALES` (and other system-sourced) entries; hidden when entry is itself already a reversal; hidden for `GL.VIEW`-only users.

### TC-GL-028 — Reverse with a chosen reversal date
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Journals (`POST .../uid/{uid}/reverse?reversalDate=`)
- **Permission / Role:** `GL.POST`
- **Steps:** the FE button calls reverse with no date (server defaults to today). Call the API directly with `reversalDate=2026-06-30` inside an OPEN period.
- **Expected Result:** reversal posted with the supplied date; rejected if that date is in a CLOSED period.
- **Convention Assertions:** C8 ISO date; C2.
- **Negative / Edge:** reversalDate in a closed period → 4xx (no OPEN period).

### TC-GL-029 — Reverse RBAC + cross-tenant denial
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Journals (`POST .../uid/{uid}/reverse`)
- **Permission / Role:** `GL.VIEW`-only role → button hidden, API 403; company-A user reversing company-B entry → denied
- **Steps:** attempt reverse as read-only role and as cross-tenant user.
- **Expected Result:** 403 in both; UI hides the control for the read-only role.
- **Convention Assertions:** C3; C7.

### TC-GL-030 — Read-only system journal (SALES) shows no Reverse
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Journals (`/admin/gl/journals/uid/:uid`)
- **Permission / Role:** `GL.POST`
- **Preconditions / Seed:** a journal with `sourceType = SALES` (auto-posted from a sales invoice).
- **Steps:** open its detail.
- **Expected Result:** the entry renders with a SALES badge; no "Reverse" button (only MANUAL entries are reversible via this screen).
- **Convention Assertions:** C9 (system postings corrected by their own module's reversal, not here); C1.
- **Negative / Edge:** confirm `OPENING_BALANCE` / `YEAR_END_CLOSE` likewise read-only here.

---

## C. Fiscal Calendar — years & periods (`/admin/gl/periods` · `/api/v1/gl/periods`)

### TC-GL-031 — List fiscal periods + years (scoped)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Fiscal Periods (`/admin/gl/periods` · `GET /periods`, `GET /periods/fiscal-years`)
- **Permission / Role:** `GL.VIEW`
- **Preconditions / Seed:** a company with a seeded current fiscal year (12 periods auto-created).
- **Steps:** navigate; observe the periods list (periodNo, start/end dates, status badge) and the fiscal-years list.
- **Expected Result:** 12 periods listed for the year; each `OPEN` (green badge) initially; years list shows yearCode + status.
- **Convention Assertions:** C1 (no uid shown; period referenced by periodNo/dates); C4; C6; C7; C8 ISO dates.
- **Negative / Edge:** company with no year → empty periods list.

### TC-GL-032 — Open a new fiscal year (creates 12 periods)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Fiscal Periods (`POST /periods/fiscal-years` · `@perm.scoped(#req.companyUid,'company','GL.PERIOD.CLOSE')`)
- **Permission / Role:** server requires `GL.PERIOD.CLOSE`; **FE gates the "Open fiscal year" form on `GL.MANAGE`** — note this mismatch: a user with PERIOD.CLOSE but not MANAGE won't see the FE button though the API would allow it.
- **Variation:** `startMonth = 1`, `calendarYear = 2027`
- **Steps:**
  1. Click "Open fiscal year".
  2. Enter yearCode `FY2027`, choose start month January, calendar year 2027.
  3. Submit `OpenFiscalYearRequest{companyUid, yearCode, startMonth, calendarYear}`.
- **Expected Result:** HTTP 201; a new `FiscalYearDto` status `OPEN`; 12 monthly periods created (Jan–Dec 2027), each OPEN; year appears in the years list.
- **Convention Assertions:** C1 company chosen by name; C2; C3; C8 dates.
- **Negative / Edge:** duplicate yearCode for the company → 409 "Fiscal year … already exists"; calendarYear outside 2000–2100 → client blocks; missing yearCode → client blocks.

### TC-GL-033 — Close a fiscal period (OPEN → CLOSED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Fiscal Periods (`POST /periods/uid/{uid}/close` · `@perm.scoped(#uid,'fiscalperiod','GL.PERIOD.CLOSE')`)
- **Permission / Role:** `GL.PERIOD.CLOSE` — runs as `ACCOUNTANT`; also as `GL.VIEW`-only → close action hidden, API 403
- **Preconditions / Seed:** an OPEN period.
- **Steps:** click "Close" on a period row.
- **Expected Result:** period status → CLOSED (grey badge); `closedAt` stamped; success toast "Period N closed". Subsequent journal posts into this period are rejected (TC-GL-021).
- **Convention Assertions:** C1; C2; C3; C9 (status lifecycle, not delete).
- **Negative / Edge:** closing an already-CLOSED period → 409 "already CLOSED".

### TC-GL-034 — Reopen a fiscal period (CLOSED → OPEN)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Fiscal Periods (`POST /periods/uid/{uid}/reopen` · `@perm.scoped(#uid,'fiscalperiod','GL.PERIOD.CLOSE')`)
- **Permission / Role:** `GL.PERIOD.CLOSE`
- **Preconditions / Seed:** a CLOSED period (TC-GL-033).
- **Steps:** click "Reopen".
- **Expected Result:** status → OPEN; `closedAt` cleared; posting into the period works again.
- **Convention Assertions:** C2; C3; C9.
- **Negative / Edge:** reopening an already-OPEN period → 409 "already OPEN".

### TC-GL-035 — Period close/reopen RBAC denial
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Fiscal Periods (`/admin/gl/periods`)
- **Permission / Role:** CUSTOM role with `GL.VIEW` only → no close/reopen controls; API 403
- **Steps:** view as read-only role; confirm `canClose()` false hides the action; attempt API directly.
- **Expected Result:** UI hides actions; API 403.
- **Convention Assertions:** C3.

### TC-GL-036 — Fiscal-year/period multi-tenant isolation
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Fiscal Periods (`GET /periods?companyId=`, close by uid)
- **Permission / Role:** `GL.PERIOD.CLOSE` (company A only)
- **Steps:** company-A user lists periods; attempts to close a company-B period uid.
- **Expected Result:** A sees only its periods; closing B's period uid → scope-denied.
- **Convention Assertions:** C7; C3.

---

## D. Trial Balance (`/admin/gl/trial-balance` · `/api/v1/gl/trial-balance`)

### TC-GL-037 — Full trial balance (nets to zero)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Trial Balance (`/admin/gl/trial-balance` · `GET /trial-balance?companyId=`)
- **Permission / Role:** `GL.VIEW`
- **Preconditions / Seed:** ≥1 balanced posted journal.
- **Steps:** navigate; observe rows grouped by account type in canonical order (ASSET, LIABILITY, EQUITY, INCOME, EXPENSE); read the footer totals.
- **Expected Result:** rows show account code/name + totalDebit/totalCredit/net; footer `totalDebits == totalCredits`; "Balanced" indicator true.
- **Convention Assertions:** C1 accounts by code/name (no uid); C8 money formatted; C4; C6; C7.
- **Negative / Edge:** empty books → empty/zero state; `isEmpty` true when no data.

### TC-GL-038 — Trial balance grouping + canonical type order
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Trial Balance (`/admin/gl/trial-balance`)
- **Permission / Role:** `GL.VIEW`
- **Steps:** confirm the rows are grouped/sorted by `AccountType` order then by accountCode.
- **Expected Result:** ASSET group first, EXPENSE last; within a group ascending by code.
- **Convention Assertions:** C6 table caption/scope; C1.
- **Negative / Edge:** a type with no rows is omitted from the grouping.

### TC-GL-039 — Trial balance forbidden / error states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Trial Balance (`/admin/gl/trial-balance`)
- **Permission / Role:** NO-PERMISSION user → forbidden; simulated 500 → error
- **Steps:** load as a user lacking GL.VIEW; then simulate server error.
- **Expected Result:** forbidden state (on 403) distinct from error state.
- **Convention Assertions:** C4; C3; C6.

---

## E. GL Posting-Account Config (`/admin/gl/config` · `/api/v1/gl/configs`)

### TC-GL-040 — List gl_configs (posting-role → account map)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** GL Config (`/admin/gl/config` · `GET /configs?companyId=`)
- **Permission / Role:** `GL.MANAGE` (route guard) / `GL.VIEW` (list endpoint)
- **Preconditions / Seed:** seeded gl_configs (sales keys mapped at bootstrap).
- **Steps:** navigate; observe each `GlConfigKey` row with its mapped account code+name.
- **Expected Result:** rows show configKey + accountCode + accountName; keys with no mapping show as unset.
- **Convention Assertions:** C1 account shown by code/name; C4; C6; C7.
- **Negative / Edge:** NO-PERMISSION user → route forbidden.

### TC-GL-041 — Set / change a posting account for a key
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** GL Config (`POST /configs` · `@perm.scoped(#req.companyUid,'company','GL.MANAGE')`)
- **Permission / Role:** `GL.MANAGE`
- **Variation:** `configKey = RETAINED_EARNINGS` (needed by year-end close)
- **Preconditions / Seed:** a `3900 Retained Earnings` EQUITY account exists (TC-GL-006).
- **Steps:**
  1. Click the key's "Set" action.
  2. Pick the account "3900 — Retained Earnings" from the account picker (by name).
  3. Save `SetGlConfigRequest{companyUid, configKey, accountUid}`.
- **Expected Result:** mapping persisted; row shows the new account; success toast labels the key.
- **Convention Assertions:** C1 account chosen by name (uid stored under the hood, not typed); C2; C3.
- **Negative / Edge:** missing account → "Config key and account are required."; only active accounts appear in the picker.

### TC-GL-042 — Set posting account: SALES_REVENUE / VAT_PAYABLE / ACCOUNTS_RECEIVABLE / CASH
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** GL Config (`POST /configs`)
- **Permission / Role:** `GL.MANAGE`
- **Variation:** each of the four v1-active keys
- **Steps:** map each sales key to an appropriately-typed account.
- **Expected Result:** all four mappings saved; these drive sales auto-posting.
- **Convention Assertions:** C1; C2.
- **Negative / Edge:** re-setting an existing key overwrites the prior mapping (idempotent set).

### TC-GL-043 — GL Config RBAC + tenant isolation
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** GL Config (`/admin/gl/config`)
- **Permission / Role:** `GL.VIEW`-only / cross-tenant
- **Steps:** read-only role → route forbidden (guard `GL.MANAGE`); company-A user cannot set company-B config.
- **Expected Result:** 403 for write; tenant B's configs not visible to A.
- **Convention Assertions:** C3; C7.

---

## F. Year-End Close (`/admin/gl/year-end` · `/api/v1/gl/periods/fiscal-years`)

### TC-GL-044 — Year-End screen lists fiscal years with close/reopen
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Year-End Close (`/admin/gl/year-end` · `GET /periods/fiscal-years`)
- **Permission / Role:** `GL.YEAR.CLOSE` (route guard); list uses `GL.VIEW`
- **Preconditions / Seed:** ≥1 OPEN fiscal year.
- **Steps:** navigate; observe years with status badges; OPEN years show "Close"; CLOSED years show "Reopen".
- **Expected Result:** list renders; controls gated by `canYearClose()`.
- **Convention Assertions:** C1 year shown by yearCode (closing-journal uid sliced/truncated, not a full hand-typed id); C4 four-state incl. forbidden on 403; C6; C7.
- **Negative / Edge:** empty → empty state; NO-PERMISSION user → forbidden.

### TC-GL-045 — Close a fiscal year — net profit → CR Retained Earnings
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Year-End Close (`POST /periods/fiscal-years/uid/{uid}/close` · `@perm.scoped(#uid,'fiscalyear','GL.YEAR.CLOSE')`)
- **Permission / Role:** `GL.YEAR.CLOSE`
- **Preconditions / Seed:** an OPEN year with INCOME > EXPENSE movement; `RETAINED_EARNINGS` gl_config mapped (TC-GL-041); the prior year CLOSED or absent.
- **Steps:**
  1. Click "Close" on the OPEN year.
  2. Read the confirmation panel warning about the retained-earnings posting; confirm.
- **Expected Result:** a `YEAR_END_CLOSE` closing journal is posted zeroing each P&L account and crediting 3900 with the net profit; all year periods auto-close; year status → CLOSED with `closedAt`, `closedBy`, `closingJournalUid` set; toast "Closing journal posted to Retained Earnings."
- **Convention Assertions:** C9 append-only (close = posting, not edit); C1; C2; C3.
- **Negative / Edge:** missing/inactive `RETAINED_EARNINGS` mapping → close rejected (BR-CLOSE-11).

### TC-GL-046 — Close a fiscal year — net loss → DR Retained Earnings
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Year-End Close (`POST .../uid/{uid}/close`)
- **Permission / Role:** `GL.YEAR.CLOSE`
- **Preconditions / Seed:** an OPEN year with EXPENSE > INCOME.
- **Steps:** close the year.
- **Expected Result:** closing journal debits 3900 with the net loss; P&L accounts zeroed; year CLOSED.
- **Convention Assertions:** C9; C2.
- **Negative / Edge:** break-even / no-trading year (all P&L net zero) → no journal posted; `closingJournalUid = null`; year still marked CLOSED.

### TC-GL-047 — Year-end close guards (illegal transitions)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Year-End Close (`POST .../uid/{uid}/close`)
- **Permission / Role:** `GL.YEAR.CLOSE`
- **Steps / Expected (each a sub-assertion):**
  1. Close an already-CLOSED year → 409 "already CLOSED … Reopen it first" (BR-CLOSE-05).
  2. Close a year with no periods → 409 "has no periods" (BR-CLOSE-05).
  3. Close a year whose immediately-prior year is still OPEN → 409 "prior fiscal year … must be CLOSED" (BR-CLOSE-04).
- **Convention Assertions:** C2 conflict errors; C9 (no partial posting on guard failure).
- **Negative / Edge:** these are the canonical illegal-transition cases.

### TC-GL-048 — Reopen a fiscal year (reverses the closing journal)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Year-End Close (`POST .../uid/{uid}/reopen` · `@perm.scoped(#uid,'fiscalyear','GL.YEAR.CLOSE')`)
- **Permission / Role:** `GL.YEAR.CLOSE`
- **Preconditions / Seed:** a CLOSED year (TC-GL-045) that is the most-recently-closed.
- **Steps:** click "Reopen".
- **Expected Result:** the year's periods reopen first; the closing journal is reversed (append-only `YEAR_END_CLOSE` reversal with `reversalOfId`); year → OPEN; `closedAt/closedBy/closingJournalUid` cleared; toast "Closing journal reversed."
- **Convention Assertions:** C9 reversal not deletion; C2; C3.
- **Negative / Edge (each a sub-assertion):**
  1. Reopen an OPEN year → 409 "not CLOSED".
  2. Reopen a year that is **not** the most-recently-closed → 409 "Only the most-recently-closed … may be reopened" (BR-CLOSE-10).
  3. Reopen when the closing journal was already reversed → 409 "already been reversed".
  4. Reopen as a non-`GL.YEAR.CLOSE` user → 403; cross-tenant year → scope-denied.

---

## G. Trial Balance — period filter (FE/BE gap) 

### TC-GL-049 — Trial balance filtered by fiscal period (verify FE/BE contract — gap G1)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Trial Balance (`/admin/gl/trial-balance` · `GET /trial-balance/period?companyId=&periodId=`)
- **Permission / Role:** `GL.VIEW`
- **Preconditions / Seed:** postings spanning ≥2 periods.
- **Steps:**
  1. UI: choose a period in the period `<select>`; observe the request.
  2. API: call `/trial-balance/period?companyId=<id>&periodId=<numeric period id>` directly with the correct numeric id.
- **Expected Result:** the **API** returns a trial balance limited to that period. The **UI** is expected to fail/ignore the filter because `getTrialBalanceForPeriod` sends `periodUid` instead of the numeric `periodId` the controller declares — capture this as defect **G1** if reproduced.
- **Convention Assertions:** C2; C8; C1.
- **Negative / Edge:** invalid periodId → error state; closed-period TB still computable (read-only).

---

## H. Dimension Types (`/admin/cost-centre/dimensions` · `/api/v1/dimensions`)

### TC-GL-050 — List dimension types (seeded, read-only catalogue)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Dimension Types (`/admin/cost-centre/dimensions` · `GET /dimensions?companyId=`)
- **Permission / Role:** `COSTING.VIEW` — also NO-PERMISSION → forbidden
- **Preconditions / Seed:** company seeded with built-in COST_CENTRE + DEPARTMENT dimensions.
- **Steps:** navigate; observe the dimension rows (slot, code, name, builtIn, mandatory flag, status).
- **Expected Result:** at least Cost Centre + Department shown; **no create/delete control** (types are seeded — confirm absence of an "Add dimension" button); status badge per `MasterStatus`.
- **Convention Assertions:** C1 (no uid shown); C4; C6; C7.
- **Negative / Edge:** NO-PERMISSION user → forbidden state, nav hidden.

### TC-GL-051 — Toggle a dimension to mandatory (COSTING.MANAGE)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Dimension Types (`PATCH /dimensions/uid/{uid}/mandatory` · `@perm.scoped(#uid,'dimension','COSTING.MANAGE')`)
- **Permission / Role:** `COSTING.MANAGE` — also `COSTING.VIEW`-only → toggle hidden, API 403
- **Variation:** slot = COST_CENTRE
- **Steps:** click the mandatory toggle on the Cost Centre row.
- **Expected Result:** `SetDimensionMandatoryRequest{mandatory:true}` sent; row updates to mandatory; toast "Dimension mandatory". (Now affects manual posting — see TC-GL-065.)
- **Convention Assertions:** C1; C2; C3.
- **Negative / Edge:** toggle back to optional; `COSTING.VIEW`-only user cannot toggle (403).

### TC-GL-052 — Get dimension by uid + tenant isolation
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Dimension Types (`GET /dimensions/uid/{uid}` · `@perm.scoped(#uid,'dimension','COSTING.VIEW')`)
- **Permission / Role:** `COSTING.VIEW`
- **Steps:** fetch a dimension uid from another company.
- **Expected Result:** cross-company uid → scope-denied; own uid → `DimensionDto`.
- **Convention Assertions:** C7; C1.

---

## I. Dimension Values (`/admin/cost-centre/values*` · `/api/v1/dimension-values`)

### TC-GL-053 — List dimension values (paged, per dimension)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Dimension Values (`/admin/cost-centre/values` · `GET /dimension-values?dimensionUid=`)
- **Permission / Role:** `COSTING.VIEW`
- **Preconditions / Seed:** a dimension with ≥1 value.
- **Steps:** navigate; the dimension-type selector defaults to the first dimension; the value list loads paged (size 20).
- **Expected Result:** rows show code, name, parent, active/status; paginator present; switching the dimension reloads page 0.
- **Convention Assertions:** C1 values by code/name (uid only in row link); C2 meta; C4; C5; C6; C7.
- **Negative / Edge:** dimension with no values → empty state; paginator hidden when ≤1 page.

### TC-GL-054 — Create a root dimension value (COSTING.MANAGE)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Dimension Values (`POST /dimension-values` · `@perm.has('COSTING.MANAGE')`)
- **Permission / Role:** `COSTING.MANAGE`
- **Variation:** root value (no parent)
- **Steps:** open create form; enter code + name; leave parent blank; submit `CreateDimensionValueRequest{dimensionUid, code, name, parentUid:null}`.
- **Test Data:** dimension = Cost Centre; code `CC-100`, name `Head Office`.
- **Expected Result:** HTTP 201; row added; `status = ACTIVE`, `active = true`, `parentUid = null`.
- **Convention Assertions:** C1 dimension chosen via the dimension `<select>` (by name); C2; C3; C6.
- **Negative / Edge:** missing code → "Code is required."; missing name → "Name is required."; no dimension selected → "Select a dimension type first."

### TC-GL-055 — Create a child dimension value (hierarchy)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Dimension Values (`POST /dimension-values`)
- **Permission / Role:** `COSTING.MANAGE`
- **Variation:** child (parent set)
- **Preconditions / Seed:** a root value (TC-GL-054).
- **Steps:** create a value supplying the parent.
- **Test Data:** code `CC-110`, name `Finance`, parent = `CC-100`.
- **Expected Result:** 201; value created with the parent set; supports roll-up in the sliced report (TC-GL-070).
- **Convention Assertions:** C1; C2.
- **Negative / Edge / DEVIATION (gap G4):** the create form takes the parent via a raw `newParentUid` **text input**, not a picker — assert the actual UI behaviour and flag the C1 deviation (parent should be a name picker).

### TC-GL-056 — Deactivate a dimension value (excludes from new tagging)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Dimension Values (`PATCH /dimension-values/uid/{uid}/deactivate` · `@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')`)
- **Permission / Role:** `COSTING.MANAGE`
- **Preconditions / Seed:** an ACTIVE value.
- **Steps:** click "Deactivate".
- **Expected Result:** value `active = false`, status reflects deactivation; can no longer be used to tag a new posting (BR-CC-04).
- **Convention Assertions:** C9 soft; C2; C3.
- **Negative / Edge:** a deactivated value used in a manual post would be rejected by the engine ("inactive … BR-CC-04").

### TC-GL-057 — Reactivate a dimension value
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Dimension Values (`PATCH /dimension-values/uid/{uid}/activate`)
- **Permission / Role:** `COSTING.MANAGE`
- **Preconditions / Seed:** a deactivated value (TC-GL-056).
- **Steps:** click "Activate".
- **Expected Result:** value ACTIVE again; usable for tagging.
- **Convention Assertions:** C9; C3.

### TC-GL-058 — Edit a dimension value (name / parent / clear-parent)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Dimension Values (`PUT /dimension-values/uid/{uid}` · `@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')`)
- **Permission / Role:** `COSTING.MANAGE`
- **Preconditions / Seed:** a value with a parent.
- **Steps:**
  1. Open `/admin/cost-centre/values/uid/:uid`.
  2. Change the name; save → partial update `UpdateDimensionValueRequest{name, parentUid, clearParent}`.
  3. Tick "Clear parent"; save → value becomes root (`clearParent:true` sent).
- **Expected Result:** name updated; with clear-parent, the value loses its parent (becomes root). Partial-update semantics: only non-null fields applied.
- **Convention Assertions:** C1 uid only in URL; C2; C3.
- **Negative / Edge / DEVIATION (gap G4):** parent is edited via a raw `fParentUid` text input (not a picker) — flag the C1 deviation; missing name → "Name is required."

### TC-GL-059 — Delete a dimension value with NO postings (hard delete, 204)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Dimension Values (`DELETE /dimension-values/uid/{uid}` · `@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')`)
- **Permission / Role:** `COSTING.MANAGE`
- **Preconditions / Seed:** a freshly-created value with zero postings.
- **Steps:** click "Delete".
- **Expected Result:** HTTP 204; row removed; success toast.
- **Convention Assertions:** C2; C3.
- **Negative / Edge:** see TC-GL-060 for the postings-present rejection.

### TC-GL-060 — Reject delete of a value that has postings (BR-CC-05)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Dimension Values (`DELETE /dimension-values/uid/{uid}`)
- **Permission / Role:** `COSTING.MANAGE`
- **Preconditions / Seed:** a value referenced by at least one journal line (tag it via a system poster, or via an earlier dimensioned post).
- **Steps:** attempt "Delete".
- **Expected Result:** server rejects (4xx); UI surfaces "Delete rejected … this value has journal postings. Use Deactivate instead." Value remains; use deactivate (TC-GL-056).
- **Convention Assertions:** C9 (history-bearing master is deactivated, not destroyed); C2.
- **Negative / Edge:** confirm the same value CAN be deactivated successfully afterward.

### TC-GL-061 — Dimension-value RBAC denial
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Dimension Values (`/admin/cost-centre/values`)
- **Permission / Role:** CUSTOM role with `COSTING.VIEW` only
- **Steps:** view list (allowed); confirm create / deactivate / activate / delete controls are hidden (`canManage()` false); attempt each API directly.
- **Expected Result:** read works; all writes 403.
- **Convention Assertions:** C3.

### TC-GL-062 — Dimension-value tenant isolation
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Dimension Values (`GET /dimension-values/uid/{uid}`, writes by uid)
- **Permission / Role:** `COSTING.MANAGE` (company A)
- **Steps:** attempt to read/update a company-B value uid.
- **Expected Result:** scope-denied (`@perm.scoped`).
- **Convention Assertions:** C7; C3.

---

## J. Mandatory-dimension enforcement on MANUAL journals (engine behaviour)

### TC-GL-063 — Cross-slot dimension tag rejected (BR-CC-04) — system poster path
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** GL posting engine (dimension validity, all sources)
- **Permission / Role:** internal (validated for any posting that carries a tag)
- **Preconditions / Seed:** a Cost-Centre value and a Department value.
- **Steps:** construct a draft line putting a DEPARTMENT value into the COST_CENTRE slot (reproducible at the service/integration level).
- **Expected Result:** rejected — "belongs to slot DEPARTMENT but was supplied in slot COST_CENTRE (BR-CC-04)".
- **Convention Assertions:** C2.
- **Negative / Edge:** cross-company value id → "cross-company or unknown value rejected (BR-CC-04)"; inactive value id → "is inactive … (BR-CC-04)".

### TC-GL-064 — Step-2 validity applies to ALL postings; Step-3 mandatory only to MANUAL
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** GL posting engine (`validateDimensions`)
- **Permission / Role:** internal
- **Preconditions / Seed:** a dimension set mandatory.
- **Steps:** trigger a non-MANUAL system posting (e.g. a sales invoice → `SALES` journal) that does not carry a cost-centre tag.
- **Expected Result:** the system posting is NOT blocked by mandatory enforcement (Step-3 is skipped for non-MANUAL sources). A tag that IS present on any source is still validated (Step-2).
- **Convention Assertions:** C9 (system postings remain reliable); C2.
- **Negative / Edge:** confirm a stock-receipt/sales post still succeeds when a dimension is mandatory.

### TC-GL-065 — Mandatory dimension blocks UI-posted manual journals (gap G3)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Journals + Dimension mandatory (`POST /api/v1/gl/journals` + `PATCH /dimensions/.../mandatory`)
- **Permission / Role:** `GL.POST` + `COSTING.MANAGE`
- **Preconditions / Seed:** set COST_CENTRE mandatory (TC-GL-051).
- **Steps:**
  1. Go to `/admin/gl/journals/post`; build a balanced 2-line entry.
  2. Note there is **no dimension field** on the line editor (the `PostJournalLineRequest` has none).
  3. Post.
- **Expected Result:** server rejects — "Dimension slot COST_CENTRE is mandatory … missing a value for this slot (FR-CC-08 / BR-CC-03)". Because the UI cannot supply the slot, **every** manual journal is blocked while the dimension is mandatory. Capture as defect **G3**.
- **Convention Assertions:** C2 error surfaced inline; C9 (no partial write).
- **Negative / Edge:** set the dimension back to optional → the same manual journal posts successfully (confirms the dimension toggle is the trigger).

---

## K. Dimension-Sliced Trial Balance (`/admin/cost-centre/report` · `/api/v1/costing/reports`)

### TC-GL-066 — Sliced trial balance by COST_CENTRE (all values)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Dimension Report (`/admin/cost-centre/report` · `GET /costing/reports/sliced-trial-balance` · `@perm.has('COSTING.VIEW') and @perm.has('GL.VIEW')`)
- **Permission / Role:** `COSTING.VIEW` **and** `GL.VIEW`
- **Preconditions / Seed:** journal lines tagged with cost-centre values.
- **Steps:** choose slot = Cost Centre; run the report.
- **Expected Result:** rows per (value × account) with totalDebit/totalCredit/net; the slice does **NOT** net to zero (do not show a "Balanced" indicator); negative net rendered in red.
- **Convention Assertions:** C1 values + accounts by code/name; C8 money; C4; C6; C7.
- **Negative / Edge:** no tagged postings → empty report.

### TC-GL-067 — Sliced TB requires BOTH COSTING.VIEW and GL.VIEW
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Dimension Report (`/costing/reports/sliced-trial-balance`)
- **Permission / Role:** CUSTOM role with `COSTING.VIEW` but **without** `GL.VIEW`
- **Steps:** open `/admin/cost-centre/report`; attempt to run.
- **Expected Result:** report forbidden — `canView()` requires both; the API returns 403 (`and` in the SpEL).
- **Convention Assertions:** C3 compound permission; C4 forbidden state.
- **Negative / Edge:** the inverse (GL.VIEW without COSTING.VIEW) also forbidden.

### TC-GL-068 — Sliced TB by DEPARTMENT slot
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Dimension Report (`/costing/reports/sliced-trial-balance?slot=DEPARTMENT`)
- **Permission / Role:** `COSTING.VIEW` + `GL.VIEW`
- **Steps:** switch the slot picker to Department; run.
- **Expected Result:** rows sliced by department values.
- **Convention Assertions:** C1; C8.
- **Negative / Edge:** reserved slots DIMENSION_3 / DIMENSION_4 are selectable in the picker but yield empty rows when unconfigured.

### TC-GL-069 — Sliced TB filtered to a single value
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Dimension Report (`...&valueUid=`)
- **Permission / Role:** `COSTING.VIEW` + `GL.VIEW`
- **Steps:** supply a specific value; run.
- **Expected Result:** rows limited to that value's postings.
- **Convention Assertions:** C1 value chosen by name (the `valueUid` carried under the hood); C8.
- **Negative / Edge:** value with no postings → empty.

### TC-GL-070 — Sliced TB with roll-up (include descendant values, FR-CC-16)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Dimension Report (`...&rollUp=true`)
- **Permission / Role:** `COSTING.VIEW` + `GL.VIEW`
- **Preconditions / Seed:** a parent value with child values that have postings (TC-GL-055).
- **Steps:** select the parent value, tick "Roll up", run.
- **Expected Result:** totals include descendant values' postings (vs rollUp=false which excludes them).
- **Convention Assertions:** C8; C1.
- **Negative / Edge:** rollUp toggled off → parent-only totals.

### TC-GL-071 — Sliced TB four-state (loading/empty/error/forbidden)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Dimension Report (`/admin/cost-centre/report`)
- **Permission / Role:** as above; also a user lacking GL.VIEW
- **Steps:** exercise each state.
- **Expected Result:** distinct loading / empty (`report() === null`) / error / forbidden states.
- **Convention Assertions:** C4; C6.

### TC-GL-072 — Sliced TB period filter (verify FE/BE contract — gap G2, expected PASS)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Dimension Report (`...&periodId=<Long>`)
- **Permission / Role:** `COSTING.VIEW` + `GL.VIEW`
- **Preconditions / Seed:** tagged postings across ≥2 periods.
- **Steps:**
  1. UI: pick a period in the report's period `<select>`; run; observe the request param.
  2. API: call with the correct **numeric** `periodId`.
- **Expected Result:** the API limits the slice to that period. The UI period `<select>` binds `[value]="p.id"` (the numeric `FiscalPeriodDto.id`) and `getSlicedTrialBalance` sends `periodId`, matching the controller's `@RequestParam Long periodId` — so the FE period filter works correctly (UI result == API result for the same period). This is the regression-guard against the G1-style param-name mismatch; it should PASS (no defect on this screen).
- **Convention Assertions:** C2; C8.
- **Negative / Edge:** invalid periodId → error state.

---

## L. GL Account-Ledger drill-down report (`/admin/reporting/account-ledger` · `/api/v1/reports/account-ledger`)

### TC-GL-073 — Account ledger for an account over a date range
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Reporting (`/admin/reporting/account-ledger` · `GET /reports/account-ledger` · `@perm.has('REPORT.LEDGER.VIEW')`)
- **Permission / Role:** `REPORT.LEDGER.VIEW` — also a user lacking it → forbidden
- **Preconditions / Seed:** an account with posted lines in the range.
- **Steps:** pick the account (by name), set fromDate/toDate, run; page through results.
- **Expected Result:** `AccountLedgerDto` with running-balance ledger lines for the account; paginated (`page`,`size` default 50).
- **Convention Assertions:** C1 account chosen by name (`accountUid` under the hood); C2; C5 pagination; C8 money + ISO dates; C4; C7.
- **Negative / Edge:** no postings in range → empty ledger; missing required `fromDate`/`toDate` → 4xx.

### TC-GL-074 — Account ledger RBAC + tenant isolation
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Reporting (`GET /reports/account-ledger`)
- **Permission / Role:** user without `REPORT.LEDGER.VIEW` → 403; company-A user cannot read company-B account ledger
- **Steps:** attempt as unauthorised role and cross-tenant.
- **Expected Result:** 403 both; nav item hidden when permission absent.
- **Convention Assertions:** C3; C7.

---

## M. Cross-cutting convention & nav cases

### TC-GL-075 — GL + Costing nav visibility by permission
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Shell nav (`shell.component.ts`)
- **Permission / Role:** matrix across roles
- **Steps:** log in as each role; inspect the sidebar.
- **Expected Result:** "Chart of Accounts / Journal Entries / Trial Balance / Fiscal Periods" appear with `GL.VIEW`; "Posting Accounts" with `GL.MANAGE`; "Year-End Close" with `GL.YEAR.CLOSE`; the Costing group (Dimension Types / Dimension Values / Sliced Trial Balance) with `COSTING.VIEW`; "Account Ledger" with `REPORT.LEDGER.VIEW`. A NO-PERMISSION user sees none of these.
- **Convention Assertions:** C3 nav gating; C6 keyboard-navigable menu.
- **Negative / Edge:** custom role with only a subset shows exactly that subset.

### TC-GL-076 — Company switcher re-scopes every GL/costing screen
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** all GL/costing list screens
- **Permission / Role:** user assigned to multiple companies
- **Steps:** on CoA, journals, periods, trial balance, gl-config, dimensions, values, sliced report — change the company `<select>`.
- **Expected Result:** each screen reloads its data scoped to the newly selected company; no cross-company bleed.
- **Convention Assertions:** C7 scoping; C4 (loading shown during reload).
- **Negative / Edge:** selecting a company the user is not assigned to is not offered (the list is the user's companies).

### TC-GL-077 — uid never displayed; resources chosen by picker (C1 sweep)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** all GL/costing screens
- **Permission / Role:** `GL.VIEW` / `COSTING.VIEW`
- **Steps:** sweep CoA list, journal list/detail, trial balance, gl-config, dimension list/values, sliced report.
- **Expected Result:** no raw uid string is rendered in any table cell, label, or detail field; uids appear only in URL paths (`/uid/:uid`) and as hidden values behind pickers/dropdowns. Resource references (account in journal lines, account in gl-config, dimension in value list, value in sliced report) are chosen by NAME via a dropdown/picker, never hand-typed. EXCEPTIONS to flag (gap G4): dimension-value parent is a raw text uid input on create + detail screens.
- **Convention Assertions:** C1 across the domain.
- **Negative / Edge:** the journal detail's closing-journal/source-ref display must not expose a hand-typeable uid as the primary handle.

### TC-GL-078 — Money & date formatting sweep (C8)
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** journals, trial balance, sliced report, account ledger
- **Permission / Role:** `GL.VIEW`
- **Steps:** inspect amount columns and date fields.
- **Expected Result:** amounts are decimal-formatted (strings on the wire); dates ISO `yyyy-MM-dd`; base currency TZS.
- **Convention Assertions:** C8.
- **Negative / Edge:** zero and negative nets render correctly (negative in red on the sliced report).

### TC-GL-079 — Accessibility (axe) sweep on every GL/costing screen
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** all screens in scope
- **Permission / Role:** `GL.VIEW` / `COSTING.VIEW`
- **Steps:** run an axe scan on each list, form, detail, and report screen.
- **Expected Result:** axe-clean; tables have captions + scoped headers; forms have labels; controls keyboard-operable.
- **Convention Assertions:** C6 WCAG 2.1 AA.
- **Negative / Edge:** the dynamic journal line editor remains operable by keyboard (add/remove/select).

### TC-GL-080 — Append-only ledger end-to-end (C9 integrity)
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Journals + Trial Balance
- **Permission / Role:** `GL.POST` + `GL.VIEW`
- **Steps:**
  1. Post a balanced manual journal; note the trial balance totals.
  2. Reverse it (TC-GL-027).
  3. Re-read the trial balance.
- **Expected Result:** there is no "edit" or "delete" path for a posted entry anywhere in the UI; the original entry persists; the reversal nets the effect; trial balance still balances (debits == credits) after both entries.
- **Convention Assertions:** C9 append-only; C2.
- **Negative / Edge:** confirm no API allows editing/deleting a posted `journal_entry` line.
