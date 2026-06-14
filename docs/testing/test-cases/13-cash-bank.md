# Cash & Bank — Test Cases (Domain CASH)

Exhaustive UI/e2e test cases for the Cash & Bank module: cash/bank account CRUD, inter-account
transfers (+ list/detail), direct entries, account balance/statement + GL-reconciliation read,
bank reconciliation lifecycle, and the cheque register. Targets the deployed QA app at
http://16.170.11.41/ via Playwright (navigate by route, pick resources by name, never by uid).

## Modules / submodules covered

| Submodule | Frontend route(s) | API base path | Controller |
|---|---|---|---|
| Cash/Bank Accounts (list + inline create + set-default) | `/admin/cash/accounts` | `/api/v1/cash/accounts` | `CashBankAccountController` |
| Record Transfer | `/admin/cash/transfers/record` | `/api/v1/cash/transfers` | `CashTransferController` |
| Transfers list | `/admin/cash/transfers` | `/api/v1/cash/transfers` | `CashTransferController` |
| Transfer detail | `/admin/cash/transfers/uid/:uid` | `/api/v1/cash/transfers/uid/{uid}` | `CashTransferController` |
| Record Direct Entry | `/admin/cash/entries/record` | `/api/v1/cash/entries` | `CashDirectEntryController` |
| Cheque Register | `/admin/cash/cheques` | `/api/v1/cash/cheques` | `ChequeController` |
| Bank Reconciliation | `/admin/cash/reconciliations` | `/api/v1/cash/reconciliations` | `BankReconciliationController` |
| Cash Statement / Balance / GL-recon | `/admin/cash/statement` | `/api/v1/cash/statements` | `CashAccountStatementController` |

Notes on verified behaviour:
- **No standalone "direct-entry list" or "cheque detail" route** — entries are only created (and surfaced inside the statement); cheques are listed and acted on inline on `/admin/cash/cheques`.
- The accounts list, transfers-record, entry-record, reconciliation, and statement screens all load **all active accounts of the company for in-screen `<select>` pickers** (by `code — name`). They do **not** use the shared `<app-uid-picker>` component, but they satisfy convention C1: the user chooses by human code/name and the uid is stored under the hood; **no raw uid is rendered** on screen. The shared `<app-paginator>` is used by the **cheques list** and **transfers list**.
- `update()` and `set-default` on accounts exist in the API + service (`updateAccount`, `setDefault`) but the accounts UI only wires **create** + **set-default** (no edit/deactivate form is rendered). Edit/deactivate is therefore **backend-only-with-no-UI** today — flagged in the relevant cases.

## Permission codes in scope (exact `@PreAuthorize` / seed)

| Code | Used by | Seed grant |
|---|---|---|
| `CASH.VIEW` | account reads, transfer reads, entry reads, all statement/balance/GL-recon reads, reconciliation reads | ORG_ADMIN only (V13) |
| `CASH.ACCOUNT.MANAGE` | account create / update / set-default | ORG_ADMIN only |
| `CASH.TRANSFER` | record transfer | ORG_ADMIN only |
| `CASH.ENTRY.RECORD` | record direct entry | ORG_ADMIN only |
| `CASH.RECONCILE` | open / mark-cleared / complete reconciliation | ORG_ADMIN only |
| `CHEQUE.MANAGE` | register / clear / cancel cheque | ORG_ADMIN only |

Verified scoping helpers on the controllers: writes/reads on a single resource use
`@perm.scoped(#uid,'<entity>','<CODE>')` (entities: `cashbankaccount`, `cashtransfer`,
`cashtransaction`, `bankreconciliation`, `cheque`); collection reads use `@perm.has('<CODE>')`.

**RBAC seed fact (important for negative tests):** by the V13 seed **only ORG_ADMIN** holds the
six cash/bank permissions. `ACCOUNTANT`, `SALES_*`, `STOREKEEPER`, `PURCHASE_OFFICER` have **none**
out of the box. To run a positive cash test as anyone other than ORG_ADMIN (or rootadmin bypass),
the perm(s) must be granted via a **CUSTOM role**. The NO-PERMISSION user and any non-granted
seeded role are valid "forbidden" subjects.

## Enum values in scope (read from the enum files — exact)

| Enum | Values |
|---|---|
| `CashBankAccountType` | `CASH`, `BANK` |
| `CashTxnDirection` | `IN`, `OUT` |
| `CashTxnType` | `AR_RECEIPT`, `AP_PAYMENT`, `TRANSFER_IN`, `TRANSFER_OUT`, `DIRECT_ENTRY` |
| `ChequeStatus` | `ISSUED`, `CLEARED`, `CANCELLED` |
| `ReconciliationStatus` | `DRAFT`, `COMPLETED` |

> **KNOWN FE/BE ENUM DRIFT (assert as defects):** the frontend model
> `web/src/app/features/admin/cashbank/models/cashbank.model.ts` declares
> `ChequeStatus = 'PENDING' | 'CLEARED' | 'CANCELLED'` and
> `CashTxnType = 'TRANSFER_IN'|'TRANSFER_OUT'|'DIRECT_ENTRY'|'RECEIPT'|'PAYMENT'|'CHEQUE'`,
> which do **not** match the backend enums above (`ISSUED` vs `PENDING`; `AR_RECEIPT/AP_PAYMENT`
> vs `RECEIPT/PAYMENT`). Consequences exercised by cases below:
> - cheque-register HTML gates Clear/Cancel on `chq.status === 'PENDING'`, but the API returns
>   `ISSUED` → **a freshly registered cheque shows NO Clear/Cancel buttons** (TC-CASH-041).
>   `statusBadgeClass('ISSUED')` also falls through to the default `text-bg-light border` badge.

## Type / role variations exercised

| Dimension | Variations covered |
|---|---|
| User type | rootadmin (superuser bypass, cross-tenant); ORG_ADMIN (full cash perms); a CUSTOM role granted only `CASH.VIEW` (read-only); a CUSTOM role granted only `CHEQUE.MANAGE`; NO-PERMISSION user; ACCOUNTANT (ungranted → forbidden) |
| Account type | `CASH` (no bank fields) vs `BANK` (bank name required; reconciliation + cheques restricted to BANK) |
| Direction | direct entry `IN` vs `OUT`; transfer IN/OUT legs |
| Default | default vs non-default account; set-default re-assignment |
| Company / tenant | single-company vs multi-company (company `<select>` appears only when >1); cross-tenant isolation (C7) |
| Branch | account optional `branchUid` (company-level vs branch-scoped account); acting branch via `X-Branch-Uid` |
| Cheque lifecycle | `ISSUED → CLEARED`, `ISSUED → CANCELLED`, and illegal transitions from terminal states |
| Reconciliation lifecycle | `DRAFT → COMPLETED` (balanced gate), illegal complete-while-unbalanced, illegal re-complete |
| Screen states | loading / empty / error / forbidden on every list/detail (C4) |

---

# Test Cases

## A. Cash / Bank Accounts — `/admin/cash/accounts` · `/api/v1/cash/accounts`

### TC-CASH-001 — Accounts list renders for a user with CASH.VIEW (idle state)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cash/Bank Accounts (`/admin/cash/accounts` · `/api/v1/cash/accounts`)
- **Permission / Role:** `CASH.VIEW` — runs as ORG_ADMIN; also as NO-PERMISSION user → expect forbidden
- **Variation:** at least one CASH and one BANK account seeded
- **Preconditions / Seed:** company with ≥1 active cash/bank account (seed via TC-CASH-010 or API `POST /api/v1/cash/accounts`)
- **Steps:**
  1. Log in as ORG_ADMIN; navigate to `/admin/cash/accounts`.
  2. Wait for the table with caption "Cash and bank accounts".
- **Test Data:** seeded "Main Cash Drawer" (CASH) + "CRDB Operating" (BANK).
- **Expected Result:** table shows columns Code, Name, Type, Bank, GL Account, Currency, Default, Active. Type column shows `CASH`/`BANK` badges. `GET /api/v1/cash/accounts?companyId=…` returns 200, `ApiResponse<CashBankAccountDto[]>`.
- **Convention Assertions:** C1 (Code/Name shown; **no uid anywhere in the table or DOM**); C2 (envelope auto-unwrapped); C4 (idle state); C6 (axe clean; table has `<caption>` + `scope="col"`); C8 (currency string column).
- **Negative / Edge:** as NO-PERMISSION user → screen shows the forbidden alert "You do not have permission to view cash accounts (CASH.VIEW)." and the nav item is hidden; API returns 403.

### TC-CASH-002 — Accounts list loading state
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Cash/Bank Accounts (`/admin/cash/accounts`)
- **Permission / Role:** `CASH.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** throttle/network-delay the `GET …/accounts` response.
- **Steps:** Navigate to `/admin/cash/accounts` and observe before the response resolves.
- **Expected Result:** spinner with `aria-label="Loading accounts"` and `aria-busy="true"`.
- **Convention Assertions:** C4 (loading distinct from empty/error/forbidden); C6 (busy region announced).
- **Negative / Edge:** none.

### TC-CASH-003 — Accounts list empty state
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cash/Bank Accounts (`/admin/cash/accounts`)
- **Permission / Role:** `CASH.VIEW` — ORG_ADMIN
- **Variation:** company with zero cash/bank accounts
- **Preconditions / Seed:** a fresh company that has no cash/bank accounts.
- **Steps:** Switch the company `<select>` (or log into a company) with no accounts; observe.
- **Expected Result:** info alert "No cash or bank accounts found. Create one to get started." No table rendered.
- **Convention Assertions:** C4 (empty distinct); C6 (alert has `role="status"`).
- **Negative / Edge:** confirm "New Account" button still visible if user has CASH.ACCOUNT.MANAGE.

### TC-CASH-004 — Accounts list error state + retry
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cash/Bank Accounts (`/admin/cash/accounts`)
- **Permission / Role:** `CASH.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** force `GET …/accounts` to return 500.
- **Steps:** Navigate; observe error; click "Retry".
- **Expected Result:** danger alert "Failed to load accounts." with a Retry button; clicking re-issues the GET.
- **Convention Assertions:** C4 (error distinct, NOT shown as forbidden); C6 (alert `role="alert"`).
- **Negative / Edge:** a 403 (not 500) must route to the *forbidden* branch, not the error branch.

### TC-CASH-005 — Company selector appears only for multi-company orgs
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cash/Bank Accounts (`/admin/cash/accounts`)
- **Permission / Role:** `CASH.VIEW` — ORG_ADMIN
- **Variation:** single-company vs multi-company org
- **Preconditions / Seed:** one org with 1 company; one org with ≥2 companies.
- **Steps:** Load accounts for each org; inspect the toolbar.
- **Expected Result:** single-company → no company `<select>`; multi-company → `<select>` labelled "Company" (visually-hidden label) listing company **names**; changing it reloads accounts + GL picker for that company.
- **Convention Assertions:** C1 (companies chosen by **name**, not id, in the visible option text); C7 (each company shows only its own accounts).
- **Negative / Edge:** switching company resets the list and reloads GL accounts.

### TC-CASH-006 — "New Account" button hidden without CASH.ACCOUNT.MANAGE
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cash/Bank Accounts (`/admin/cash/accounts`)
- **Permission / Role:** `CASH.ACCOUNT.MANAGE` — runs as a CUSTOM role granted only `CASH.VIEW`
- **Preconditions / Seed:** CUSTOM role user with CASH.VIEW but NOT CASH.ACCOUNT.MANAGE.
- **Steps:** Log in as that user; open `/admin/cash/accounts`.
- **Expected Result:** the list renders but the "New Account" button and the per-row "Set default" buttons are **absent** (and the Actions column header is hidden).
- **Convention Assertions:** C3 (write affordances gated by CASH.ACCOUNT.MANAGE while read works on CASH.VIEW); C6 axe.
- **Negative / Edge:** if such a user POSTs to `/api/v1/cash/accounts` directly → 403.

### TC-CASH-010 — Create a CASH account (happy path, GL asset link required)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cash/Bank Accounts (`/admin/cash/accounts` · `POST /api/v1/cash/accounts`)
- **Permission / Role:** `CASH.ACCOUNT.MANAGE` — runs as ORG_ADMIN; also as CASH.VIEW-only role → New Account button absent
- **Variation:** accountType = CASH; setAsDefault = false
- **Preconditions / Seed:** company has ≥1 **ASSET** GL account in its chart (the GL picker filters `accountType === 'ASSET'`).
- **Steps:**
  1. Click "New Account".
  2. Enter Account Name "Petty Cash Box".
  3. Account Type `<select>` = CASH.
  4. GL Asset Account `<select>` → choose an ASSET account by its `code — name` label.
  5. Click "Save Account".
- **Test Data:** name="Petty Cash Box"; type=CASH; GL = "1000 — Cash on Hand".
- **Expected Result:** 201 Created; success toast "Account created · Petty Cash Box"; list refreshes and shows the new row with an auto-generated **Code**, currency = base (TZS), Active ✓.
- **Convention Assertions:** C1 (GL account chosen by **code — name** in the picker; **its uid is sent under the hood** as `glAccountUid`, never typed; no uid shown in the table); C2 (201 envelope); C8 (currency rendered as a string; date n/a); C9 (created as ACTIVE master).
- **Negative / Edge:** for a CASH account the Bank Name/No/Branch fields are **not rendered** (only appear when type=BANK).

### TC-CASH-011 — Create a BANK account requires Bank Name
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cash/Bank Accounts (`POST /api/v1/cash/accounts`)
- **Permission / Role:** `CASH.ACCOUNT.MANAGE` — ORG_ADMIN
- **Variation:** accountType = BANK
- **Preconditions / Seed:** ≥1 ASSET GL account.
- **Steps:**
  1. New Account → Type = BANK (bank fields appear).
  2. Fill Name + GL but leave Bank Name blank → Save.
  3. Then fill Bank Name "CRDB Bank", Bank Account No "0150xxxxxxxxx", Bank Branch "Kariakoo" → Save.
- **Test Data:** name="CRDB Operating"; type=BANK; bankName="CRDB Bank".
- **Expected Result:** step 2 → inline error "Bank name is required for a BANK account." (no API call). Step 3 → 201; row shows Bank column with bank name + account no underneath.
- **Convention Assertions:** C1 (GL by picker; no uid shown); C2; C3 (write gated by CASH.ACCOUNT.MANAGE); C6 axe on the open form.
- **Negative / Edge:** server-side, `CreateCashBankAccountRequest` only `@NotBlank`s name/companyUid/glAccountUid + `@NotNull` accountType — the **bankName-required-for-BANK** rule is enforced by the **client** here (and by service `BR`); test that a direct API POST of BANK without bankName is rejected by the service.

### TC-CASH-012 — Create account: required-field validation (name, GL)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cash/Bank Accounts (`/admin/cash/accounts`)
- **Permission / Role:** `CASH.ACCOUNT.MANAGE` — ORG_ADMIN
- **Steps:**
  1. New Account → leave Name blank, leave GL unselected → Save.
  2. Fill Name only → Save.
- **Expected Result:** step 1 → "Account name is required."; step 2 → "GL account is required." Both block submission (no POST fired).
- **Convention Assertions:** C4-style inline error in the form (`role="alert"`); C6 axe.
- **Negative / Edge:** GL `<select>` default option value is empty string "— select GL account —" and must not be accepted.

### TC-CASH-013 — Create account: setAsDefault checkbox
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cash/Bank Accounts (`POST …/accounts`)
- **Permission / Role:** `CASH.ACCOUNT.MANAGE` — ORG_ADMIN
- **Variation:** setAsDefault = true; default vs non-default
- **Preconditions / Seed:** company with an existing default account.
- **Steps:** Create a new account with "Set as default account" ticked.
- **Expected Result:** new account gets the **Default** badge; the previously-default account loses it (single default per company).
- **Convention Assertions:** C9 (no hard delete of old default; just flag move).
- **Negative / Edge:** verify exactly one Default badge remains in the list.

### TC-CASH-014 — Set-default action on an existing non-default account
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cash/Bank Accounts (`POST …/accounts/uid/{uid}/set-default`)
- **Permission / Role:** `CASH.ACCOUNT.MANAGE` — ORG_ADMIN; scoped via `@perm.scoped(#uid,'cashbankaccount','CASH.ACCOUNT.MANAGE')`
- **Variation:** non-default → default
- **Preconditions / Seed:** ≥2 accounts, one default.
- **Steps:** On a non-default row click "Set default" (aria-label "Set <name> as default").
- **Expected Result:** spinner on the row; success toast "Default account set · <name>"; list reloads with the Default badge moved.
- **Convention Assertions:** C1 (action references the account by **name** in aria-label; uid only travels in the URL path under the hood); C3 (gated).
- **Negative / Edge:** the "Set default" button is **not rendered** on the already-default row (`@if (!acc.isDefault)`).

### TC-CASH-015 — Account edit / deactivate is NOT exposed in the UI (gap note)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Cash/Bank Accounts (`PUT /api/v1/cash/accounts/uid/{uid}`)
- **Permission / Role:** `CASH.ACCOUNT.MANAGE`
- **Preconditions / Seed:** an existing account.
- **Steps:** On `/admin/cash/accounts`, look for any per-row Edit / Deactivate control.
- **Expected Result:** **none rendered** — `updateAccount()` exists in the service and the `PUT …/uid/{uid}` endpoint (with `UpdateCashBankAccountRequest{name,bankName,bankAccountNo,bankBranch,active}`) is **backend-only-with-no-UI**. Soft-deactivate (`active=false`) can be exercised only via API today.
- **Convention Assertions:** C9 (deactivate, not hard-delete — verify the API path only flips `active`).
- **Negative / Edge:** document as a coverage gap; an API `PUT` with `active=false` should make the row show the inactive icon on next list load.

### TC-CASH-016 — Account get-by-uid (scoped read)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Cash/Bank Accounts (`GET /api/v1/cash/accounts/uid/{uid}`)
- **Permission / Role:** `CASH.VIEW` via `@perm.scoped(#uid,'cashbankaccount','CASH.VIEW')`
- **Preconditions / Seed:** an account uid from a prior create.
- **Steps:** API-level: GET the account by uid as ORG_ADMIN, then as a user without CASH.VIEW.
- **Expected Result:** ORG_ADMIN → 200 `CashBankAccountDto`; no-perm → 403. (No dedicated detail screen; `getAccount()` is used to back pickers/balances.)
- **Convention Assertions:** C2; C3 scoped.
- **Negative / Edge:** cross-tenant uid → 403/404 (scoping helper denies).

---

## B. Cash Transfers — `/admin/cash/transfers/record`, `/admin/cash/transfers`, detail · `/api/v1/cash/transfers`

### TC-CASH-020 — Record a transfer between two accounts (happy path)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Record Transfer (`/admin/cash/transfers/record` · `POST /api/v1/cash/transfers`)
- **Permission / Role:** `CASH.TRANSFER` — runs as ORG_ADMIN; also as ACCOUNTANT (ungranted) → forbidden route guard
- **Variation:** source = CASH "Petty Cash", destination = BANK "CRDB Operating"
- **Preconditions / Seed:** two active accounts with the source holding ≥ transfer amount.
- **Steps:**
  1. Navigate to `/admin/cash/transfers/record`.
  2. Source Account `<select>` → choose by `code — name (TYPE)`. The source **balance** loads beside it.
  3. Destination Account `<select>` → choose a different account; its balance loads.
  4. Amount = 50000; Transfer Date defaults to today; Reference = "Float top-up".
  5. Click submit.
- **Test Data:** amount=50000; date=today; reference="Float top-up".
- **Expected Result:** 201; success toast "Transfer recorded · CBT-####"; the saved-transfer summary shows the new `transferNumber`; source/dest balances refresh. Backend posts two cash_transactions (`TRANSFER_OUT`/`TRANSFER_IN`) + one balanced GL entry atomically.
- **Convention Assertions:** C1 (both accounts chosen by **code — name** picker; uids sent under the hood as `sourceAccountUid`/`destinationAccountUid`; **no uid visible**); C2 (201 envelope); C8 (amount entered/displayed; balances coerced from number, formatted 2dp).
- **Negative / Edge:** ACCOUNTANT (no CASH.TRANSFER) hitting `/admin/cash/transfers/record` is blocked by `requirePermission('CASH.TRANSFER')` route guard (redirect/forbidden), and a direct POST → 403.

### TC-CASH-021 — Transfer rejected when source == destination (client guard)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Record Transfer (`/admin/cash/transfers/record`)
- **Permission / Role:** `CASH.TRANSFER` — ORG_ADMIN
- **Steps:** Choose the same account for both Source and Destination; enter amount; attempt submit.
- **Expected Result:** the submit button is disabled (`sameAccountError`); if forced, inline error "Source and destination accounts must differ." No POST fired.
- **Convention Assertions:** C1; client-side BR mirrors backend BR-CASH-03/04.
- **Negative / Edge:** API-level POST with equal source/dest uids → service rejects with a 4xx + `errors[]`.

### TC-CASH-022 — Transfer amount validation (positive required)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Record Transfer (`POST …/transfers`)
- **Permission / Role:** `CASH.TRANSFER` — ORG_ADMIN
- **Variation:** amount = 0, negative, blank
- **Steps:** Enter amount 0 (then -10, then blank) with valid accounts/date.
- **Expected Result:** submit stays disabled (`amountNum() <= 0`); if forced, "Enter a valid transfer amount." Backend `@Positive` on `amount` would also reject.
- **Convention Assertions:** C8 (money is a string on the wire — sent as a string decimal); C2.
- **Negative / Edge:** amount with >2 decimals / non-numeric text → coerced to 0 → blocked.

### TC-CASH-023 — Transfer date required; defaults to today
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Record Transfer (`POST …/transfers`)
- **Permission / Role:** `CASH.TRANSFER` — ORG_ADMIN
- **Steps:** Open the form; confirm Transfer Date prefilled to today (yyyy-MM-dd); clear it; attempt submit.
- **Expected Result:** default = today; cleared date blocks submit → "Transfer date is required."
- **Convention Assertions:** C8 (date ISO yyyy-MM-dd).
- **Negative / Edge:** future/back-dated allowed unless service BR forbids — verify against service behaviour.

### TC-CASH-024 — Transfers list (paged) with link to detail
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Transfers list (`/admin/cash/transfers` · `GET /api/v1/cash/transfers`)
- **Permission / Role:** `CASH.VIEW` — ORG_ADMIN; also NO-PERMISSION → forbidden
- **Preconditions / Seed:** ≥1 recorded transfer (TC-CASH-020).
- **Steps:** Navigate to `/admin/cash/transfers`; observe the table; click a row link.
- **Expected Result:** table of transfers — columns Transfer #, Date, From Account, To Account, Amount, Ref, Actions; each row's Actions cell links via `[routerLink]="['/admin/cash/transfers/uid', row.uid]"` (View). Uses shared `<app-paginator label="Transfers">`.
- **Convention Assertions:** C1 (link target carries uid in the **path only**, never typed); C4 (loading/empty/error/forbidden); C5 (paginator present — first/prev/pages/next/last; self-hidden when 1 page); C8 (amount formatted `CUR n.nn`); C6 axe (caption "Cash transfers" + `scope="col"`).
- **C1 DEFECT (assert + file):** the From/To Account cells render the **raw `sourceAccountUid`/`destinationAccountUid`** (`{{ row.sourceAccountUid }}` / `{{ row.destinationAccountUid }}`), NOT a human account code/name — a direct C1 violation (a uid is shown on screen). `CashTransferDto` does not carry account names, so the list cannot resolve names today. **Assert that a raw uid string IS visible in these columns and file it** (FE should join to the account code/name, or the DTO should include them).
- **Negative / Edge:** the list backs onto `GET /api/v1/cash/transfers` which returns a plain `List` (no server pagination) — the screen slices client-side; verify the paginator still behaves (and is hidden when totalPages ≤ 1).

### TC-CASH-025 — Transfer detail view
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Transfer detail (`/admin/cash/transfers/uid/:uid` · `GET …/transfers/uid/{uid}`)
- **Permission / Role:** `CASH.VIEW` via `@perm.scoped(#uid,'cashtransfer','CASH.VIEW')`
- **Preconditions / Seed:** a transfer uid from TC-CASH-020.
- **Steps:** Open a transfer from the list; read the detail (`<dl>`).
- **Expected Result:** shows Transfer Number, Date, Amount (`CUR n.nn`), From Account, To Account, Reference (when set), and GL Entry (`journalEntryRef`, shown only when present).
- **Convention Assertions:** C1 (resource uid only in the URL path — never typed); C2 (single-object envelope, auto-unwrapped); C4 (loading/error/forbidden on detail).
- **C1 DEFECT (assert + file):** the From Account / To Account rows render the **raw `sourceAccountUid`/`destinationAccountUid`** (`{{ t.sourceAccountUid }}` / `{{ t.destinationAccountUid }}`), NOT an account code/name — same C1 violation as the list (TC-CASH-024). `CashTransferDto` carries no account names. **Assert a raw uid is visible and file it.**
- **Negative / Edge:** unknown uid → not-found/error state; cross-tenant uid → 403 via the `@perm.scoped(#uid,'cashtransfer','CASH.VIEW')` helper.

### TC-CASH-026 — Transfers empty / loading / error states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Transfers list (`/admin/cash/transfers`)
- **Permission / Role:** `CASH.VIEW` — ORG_ADMIN
- **Steps:** Load for a company with no transfers (empty); throttle response (loading); force 500 (error); force 403 (forbidden).
- **Expected Result:** four visually distinct states.
- **Convention Assertions:** C4; C6.
- **Negative / Edge:** 403 routes to forbidden, not error.

---

## C. Direct Cash/Bank Entries — `/admin/cash/entries/record` · `/api/v1/cash/entries`

### TC-CASH-030 — Record a direct IN entry (e.g. bank interest)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Record Direct Entry (`/admin/cash/entries/record` · `POST /api/v1/cash/entries`)
- **Permission / Role:** `CASH.ENTRY.RECORD` — runs as ORG_ADMIN; also as NO-PERMISSION → route forbidden
- **Variation:** direction = IN; counter GL = INCOME
- **Preconditions / Seed:** ≥1 cash/bank account; ≥1 INCOME GL account (counter picker filters INCOME/EXPENSE/EQUITY).
- **Steps:**
  1. Navigate to `/admin/cash/entries/record`.
  2. Cash Account `<select>` → choose by name.
  3. Direction = IN.
  4. Amount = 1200.50; Date = today.
  5. Counter GL Account `<select>` → choose an INCOME account by `code — name`.
  6. Memo = "Bank interest"; submit.
- **Test Data:** amount=1200.50; direction=IN; counter="4100 — Interest Income".
- **Expected Result:** 201; toast "Entry recorded · <txnNumber>"; saved-entry summary shown. Backend records a `DIRECT_ENTRY` cash_transaction + balanced GL.
- **Convention Assertions:** C1 (cash account + counter GL chosen by name/code via picker; uids under the hood; no uid shown); C2 (201); C8 (amount sent as string decimal).
- **Negative / Edge:** counter GL options exclude ASSET/LIABILITY accounts (only INCOME/EXPENSE/EQUITY listed).

### TC-CASH-031 — Record a direct OUT entry (e.g. bank charge)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Record Direct Entry (`POST …/entries`)
- **Permission / Role:** `CASH.ENTRY.RECORD` — ORG_ADMIN
- **Variation:** direction = OUT; counter GL = EXPENSE
- **Steps:** As TC-CASH-030 but Direction = OUT, counter = an EXPENSE account, memo "Monthly bank charge".
- **Expected Result:** 201; cash decreases. `CashTxnDirection=OUT` persisted.
- **Convention Assertions:** C1; C8.
- **Negative / Edge:** verify the running statement (TC-CASH-061) later shows this OUT reducing the balance.

### TC-CASH-032 — Direct entry required-field validation
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Record Direct Entry (`/admin/cash/entries/record`)
- **Permission / Role:** `CASH.ENTRY.RECORD` — ORG_ADMIN
- **Steps:** Submit with (a) no cash account, (b) amount 0, (c) no date, (d) no counter GL — one at a time.
- **Expected Result:** submit disabled; if forced, respective inline errors: "Cash account is required.", "Enter a valid amount.", "Transaction date is required.", "Counter GL account is required."
- **Convention Assertions:** C4 (inline error `role="alert"`); C6.
- **Negative / Edge:** backend `@NotBlank`/`@NotNull`/`@Positive` on `RecordDirectEntryRequest` reject the same via API.

### TC-CASH-033 — Direct-entry list is API-only (no dedicated screen) — note
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Direct entries (`GET /api/v1/cash/entries?companyId&accountId`)
- **Permission / Role:** `CASH.VIEW`
- **Preconditions / Seed:** recorded entries.
- **Steps:** Confirm there is **no** `/admin/cash/entries` list route; entries surface only inside the account **statement** (TC-CASH-061). The service `listEntries()` exists but is unused by a screen.
- **Expected Result:** documented gap; `GET …/entries` returns `List<CashTransactionDto>` for API consumers.
- **Convention Assertions:** C2.
- **Negative / Edge:** API read without CASH.VIEW → 403.

---

## D. Cheque Register — `/admin/cash/cheques` · `/api/v1/cash/cheques`

### TC-CASH-040 — Cheque register list (paged) with status badges
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cheque Register (`/admin/cash/cheques` · `GET /api/v1/cash/cheques`)
- **Permission / Role:** `CHEQUE.MANAGE` — runs as ORG_ADMIN and as a CUSTOM role granted only `CHEQUE.MANAGE`; also NO-PERMISSION → forbidden
- **Preconditions / Seed:** ≥1 registered cheque.
- **Steps:** Navigate to `/admin/cash/cheques`; observe the table + paginator.
- **Expected Result:** columns Cheque No., Payee, Amount, Issue Date, Value Date, Status, Actions. Uses shared `<app-paginator>` (`[label]="'Cheques'"`).
- **Convention Assertions:** C1 (cheque shown by **number/payee**, no uid); C2; C4; C5 (paginator first/prev/pages/next/last; hidden at 1 page); C6 (caption + scope); C8 (amount "CUR n.nn").
- **Negative / Edge:** note the route guard is `requirePermission('CHEQUE.MANAGE')` — there is **no CASH.VIEW-only read path to this screen** (a CASH.VIEW-only user is forbidden here even though the read API accepts CASH.VIEW).

### TC-CASH-041 — Register a cheque (BANK account only) — and the ISSUED-vs-PENDING action defect
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cheque Register (`POST /api/v1/cash/cheques`)
- **Permission / Role:** `CHEQUE.MANAGE` — ORG_ADMIN
- **Variation:** account = BANK
- **Preconditions / Seed:** ≥1 BANK account (the picker lists only `accountType === 'BANK'`).
- **Steps:**
  1. Click "Register Cheque".
  2. Bank Account `<select>` → choose a BANK account by `code — name`.
  3. Cheque Number = "100245"; Payee = "Tanesco"; Amount = 75000; Issue Date = today; Value Date = today+3.
  4. Submit.
  5. Observe the new row's Status and its Actions cell.
- **Test Data:** chequeNumber="100245"; payee="Tanesco"; amount=75000.
- **Expected Result (intended):** 201; toast "Cheque registered · 100245"; row appears with status `ISSUED` and offers Clear/Cancel actions.
- **Expected Result (actual / DEFECT to log):** API returns status **`ISSUED`**, but the HTML gates the actions on `chq.status === 'PENDING'` and `statusBadgeClass` has no `ISSUED` case → the new row shows **NO Clear/Cancel buttons** and a fallthrough `text-bg-light` badge. **Assert this mismatch and file it** (FE model `ChequeStatus` uses `PENDING`; backend uses `ISSUED`).
- **Convention Assertions:** C1 (bank account chosen by code—name; uid under the hood); C2 (201); C8.
- **Negative / Edge:** only BANK accounts appear in the picker (cheque register is BANK-only per controller doc); a CASH account is not selectable.

### TC-CASH-042 — Register cheque: required-field validation
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cheque Register (`/admin/cash/cheques`)
- **Permission / Role:** `CHEQUE.MANAGE` — ORG_ADMIN
- **Steps:** Submit the form missing each of: bank account, cheque number, payee, amount(≤0), issue date, value date — one at a time.
- **Expected Result:** respective inline errors: "Bank account is required.", "Cheque number is required.", "Payee is required.", "Enter a valid amount.", "Issue date is required.", "Value date is required." No POST until valid.
- **Convention Assertions:** C4 inline error; C6.
- **Negative / Edge:** backend `@NotBlank`/`@NotNull`/`@Positive` on `RegisterChequeRequest` reject the same.

### TC-CASH-043 — Clear a cheque (ISSUED → CLEARED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Cheque Register (`POST /api/v1/cash/cheques/uid/{uid}/clear`)
- **Permission / Role:** `CHEQUE.MANAGE` via `@perm.scoped(#uid,'cheque','CHEQUE.MANAGE')`
- **Variation:** lifecycle transition ISSUED → CLEARED
- **Preconditions / Seed:** a cheque in `ISSUED` status.
- **Steps (API, due to TC-CASH-041 UI defect):** call `clear` for the cheque uid; reload the list.
  - **Steps (UI, after the PENDING/ISSUED defect is fixed):** click "Clear" on the ISSUED row.
- **Expected Result:** status becomes `CLEARED`; `clearedAt` set; toast "Cheque cleared · <number>".
- **Convention Assertions:** C1 (clear action references cheque by **number** in aria-label; uid in URL path); C9 (status transition is append-only metadata, not a destructive edit).
- **Negative / Edge:** clearing an already-CLEARED or CANCELLED cheque → service rejects (illegal transition) — see TC-CASH-045.

### TC-CASH-044 — Cancel a cheque (ISSUED → CANCELLED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Cheque Register (`POST /api/v1/cash/cheques/uid/{uid}/cancel`)
- **Permission / Role:** `CHEQUE.MANAGE` scoped
- **Variation:** lifecycle ISSUED → CANCELLED
- **Preconditions / Seed:** a cheque in `ISSUED`.
- **Steps:** Cancel the cheque (UI button once defect fixed, else API).
- **Expected Result:** status `CANCELLED`; `cancelledAt` set; toast "Cheque cancelled · <number>".
- **Convention Assertions:** C1; C9.
- **Negative / Edge:** see TC-CASH-045.

### TC-CASH-045 — Illegal cheque transitions are rejected
- **Type:** Automated (API-level)
- **Priority:** P1
- **Module / Submodule:** Cheque Register (`/clear`, `/cancel`)
- **Permission / Role:** `CHEQUE.MANAGE` scoped
- **Variation:** terminal-state transitions
- **Preconditions / Seed:** one CLEARED cheque, one CANCELLED cheque.
- **Steps:**
  1. `clear` a CLEARED cheque.
  2. `cancel` a CLEARED cheque.
  3. `clear` a CANCELLED cheque.
  4. `cancel` a CANCELLED cheque.
- **Expected Result:** all four rejected with a 4xx + `errors[]` (illegal transition; `ChequeStatus` has only ISSUED→{CLEARED,CANCELLED}). No status change.
- **Convention Assertions:** C2 (error envelope `errors[]`); C9 (terminal states immutable).
- **Negative / Edge:** also `cancel`-then-`clear` of the same cheque.

### TC-CASH-046 — Cheque actions hidden / forbidden without CHEQUE.MANAGE
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cheque Register (`/admin/cash/cheques`)
- **Permission / Role:** `CHEQUE.MANAGE`
- **Preconditions / Seed:** NO-PERMISSION user; and (separately) a CASH.VIEW-only user.
- **Steps:** Attempt to open `/admin/cash/cheques` as each.
- **Expected Result:** both are blocked by the `requirePermission('CHEQUE.MANAGE')` route guard (CASH.VIEW alone is **not** enough to reach this screen); direct `register`/`clear`/`cancel` API calls → 403.
- **Convention Assertions:** C3 (write gated by CHEQUE.MANAGE).
- **Negative / Edge:** confirm "Register Cheque" + per-row action buttons are absent for any user reaching the list without CHEQUE.MANAGE (not reachable given the guard, but the `@if (canManage())` template gates also apply).

### TC-CASH-047 — Cheque list empty / loading / error states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cheque Register (`/admin/cash/cheques`)
- **Permission / Role:** `CHEQUE.MANAGE`
- **Steps:** company with no cheques (empty info alert "No cheques found for this company."); throttle (loading spinner); 500 (danger alert + Retry); 403 (forbidden alert "You do not have permission (CHEQUE.MANAGE).").
- **Expected Result:** four distinct states.
- **Convention Assertions:** C4; C6.
- **Negative / Edge:** Retry re-issues `load(0)`.

---

## E. Bank Reconciliation — `/admin/cash/reconciliations` · `/api/v1/cash/reconciliations`

### TC-CASH-050 — Open a reconciliation (DRAFT) for a BANK account
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Bank Reconciliation (`/admin/cash/reconciliations` · `POST /api/v1/cash/reconciliations`)
- **Permission / Role:** `CASH.RECONCILE` — runs as ORG_ADMIN; also NO-PERMISSION → route forbidden
- **Variation:** account = BANK (the picker lists only BANK accounts)
- **Preconditions / Seed:** a BANK account with some cleared/uncleared transactions.
- **Steps:**
  1. Navigate to `/admin/cash/reconciliations`.
  2. Pick a BANK account by name → its statement transactions load (each with a cleared checkbox).
  3. Click "Open reconciliation"; enter Statement Date (default today) + Statement Closing Balance = 250000; submit.
- **Test Data:** statementDate=today; statementClosingBalance=250000.
- **Expected Result:** 201; reconciliation in `DRAFT`; toast "Reconciliation opened · <reconciliationNumber>"; the difference indicator and Complete button appear.
- **Convention Assertions:** C1 (bank account chosen by name; uid under the hood as `cashBankAccountUid`); C2 (201); C8 (closing balance entered as string decimal; statement date ISO).
- **Negative / Edge:** only BANK accounts in the picker (CASH accounts excluded); opening without selecting an account → "Select a bank account first."

### TC-CASH-051 — Open reconciliation: validation (date + numeric closing balance)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Bank Reconciliation (`POST …/reconciliations`)
- **Permission / Role:** `CASH.RECONCILE` — ORG_ADMIN
- **Steps:** Open form; clear Statement Date → submit; enter non-numeric closing balance → submit.
- **Expected Result:** "Statement date is required." and "Enter a valid statement closing balance." respectively. No POST.
- **Convention Assertions:** C4 inline error; C8 (closing balance must be numeric — note it may legitimately be **negative/overdraft**, so only NaN is rejected, not negatives).
- **Negative / Edge:** backend `@NotNull` on `statementDate`/`statementClosingBalance`.

### TC-CASH-052 — Mark a transaction cleared / uncleared (DRAFT only)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Bank Reconciliation (`POST /api/v1/cash/reconciliations/uid/{uid}/mark-cleared`)
- **Permission / Role:** `CASH.RECONCILE` via `@perm.scoped(#uid,'bankreconciliation','CASH.RECONCILE')`
- **Variation:** cleared=true then cleared=false
- **Preconditions / Seed:** an open DRAFT reconciliation with ≥2 transactions.
- **Steps:**
  1. Tick a transaction's cleared checkbox → it is sent as `{transactionUids:[uid],cleared:true}`.
  2. Untick it → `{…,cleared:false}`.
  3. Observe the running "cleared book balance" and the difference vs statement closing balance updating live.
- **Expected Result:** each toggle persists (optimistic UI with rollback on error); the cleared-book-balance recomputes (`IN` adds, `OUT` subtracts); difference = clearedBookBalance − statementClosingBalance.
- **Convention Assertions:** C1 (transactions identified by txn number, not uid, in the visible row; uid travels in the request body/path only); C2; C8 (all amounts coerced from number, never string-method'd).
- **Negative / Edge:** marking cleared on a **COMPLETED** reconciliation must be rejected by the service (`MarkClearedRequest` doc: allowed only while DRAFT); `transactionUids` must be `@NotEmpty`.

### TC-CASH-053 — Complete is gated: enabled ONLY when cleared book balance == statement closing balance
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Bank Reconciliation (`POST …/reconciliations/uid/{uid}/complete`)
- **Permission / Role:** `CASH.RECONCILE` scoped
- **Variation:** unbalanced vs balanced
- **Preconditions / Seed:** open DRAFT recon; transactions whose cleared sum can be made to equal the closing balance.
- **Steps:**
  1. With difference ≠ 0, confirm the Complete button is **disabled**.
  2. Tick transactions until clearedBookBalance == statementClosingBalance (difference within 1e-6).
  3. Confirm Complete becomes **enabled**; click it.
- **Expected Result:** step 1 disabled; step 3 → 200; status `COMPLETED`, `completedAt`/`reconciledBy` set; toast "Reconciliation completed · <number>".
- **Convention Assertions:** C2; C8 (balance compared with float tolerance — UI uses 1e-6; backend uses BigDecimal.compareTo).
- **Negative / Edge:** forcing a `complete` API call while unbalanced → service rejects (BR-CASH-06: clearedBookBalance must equal statementClosingBalance).

### TC-CASH-054 — Illegal reconciliation transitions
- **Type:** Automated (API-level)
- **Priority:** P1
- **Module / Submodule:** Bank Reconciliation (`/complete`, `/mark-cleared`)
- **Permission / Role:** `CASH.RECONCILE` scoped
- **Variation:** re-complete; mutate after complete
- **Preconditions / Seed:** a COMPLETED reconciliation.
- **Steps:**
  1. `complete` an already-COMPLETED recon.
  2. `mark-cleared` against a COMPLETED recon.
- **Expected Result:** both rejected with 4xx + `errors[]` (`ReconciliationStatus` is DRAFT→COMPLETED only; COMPLETED is terminal). The UI also computes `completeDisabled` true when status is already COMPLETED.
- **Convention Assertions:** C2; C9 (completed recon is immutable).
- **Negative / Edge:** complete an unbalanced DRAFT (TC-CASH-053 edge).

### TC-CASH-055 — Reconciliation list by account (read)
- **Type:** Automated (Playwright/API)
- **Priority:** P2
- **Module / Submodule:** Bank Reconciliation (`GET /api/v1/cash/reconciliations?companyId&accountId`)
- **Permission / Role:** `CASH.VIEW` (read) — note the **screen** route guard is `CASH.RECONCILE`, so the list API (CASH.VIEW) is reachable on-screen only by users who also hold CASH.RECONCILE
- **Preconditions / Seed:** ≥1 reconciliation for a BANK account.
- **Steps:** API: GET reconciliations for company+account as ORG_ADMIN, then without CASH.VIEW.
- **Expected Result:** ORG_ADMIN → 200 `List<BankReconciliationDto>` (number, statement date, closing/cleared balances, status); no CASH.VIEW → 403.
- **Convention Assertions:** C2; C3 (read gated CASH.VIEW); C7 (account/company-scoped).
- **Negative / Edge:** cross-tenant `accountId` returns only the caller's company's data (or empty), never another tenant's.

### TC-CASH-056 — Reconciliation screen forbidden / loading / error
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Bank Reconciliation (`/admin/cash/reconciliations`)
- **Permission / Role:** `CASH.RECONCILE`
- **Steps:** Open as NO-PERMISSION (route forbidden); throttle statement load (loading); force the statement GET to 500 (txn error state); 403.
- **Expected Result:** distinct states; the transaction panel has its own `txnState` (loading/error/idle).
- **Convention Assertions:** C4; C6.
- **Negative / Edge:** picking an account with no transactions → empty transaction panel, recon still openable.

---

## F. Cash Statement / Balance / GL-Reconciliation read — `/admin/cash/statement` · `/api/v1/cash/statements`

### TC-CASH-060 — Account balance loads on selection
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cash Statement (`/admin/cash/statement` · `GET /api/v1/cash/statements/accounts/uid/{uid}/balance` and `/statement`)
- **Permission / Role:** `CASH.VIEW` via `@perm.scoped(#uid,'cashbankaccount','CASH.VIEW')`; also NO-PERMISSION → route forbidden
- **Preconditions / Seed:** an account with transactions.
- **Steps:** Navigate to `/admin/cash/statement`; pick an account by name.
- **Expected Result:** current balance card shows `currentBalance` formatted; the statement transaction list loads.
- **Convention Assertions:** C1 (account chosen by name; uid under the hood in the path); C2; C8 (balance coerced from number, 2dp; currency string).
- **Negative / Edge:** NO-PERMISSION user → route forbidden; scoped GET for an account in another tenant → 403.

### TC-CASH-061 — Running-balance statement (IN adds, OUT subtracts, chronological)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cash Statement (`GET …/statements/accounts/uid/{uid}/statement`)
- **Permission / Role:** `CASH.VIEW` scoped
- **Preconditions / Seed:** an account with a mix of IN and OUT transactions (from transfers + direct entries).
- **Steps:** Select the account; read the transaction table with the running-balance column.
- **Expected Result:** each row shows direction badge (IN green / OUT red), amount, and a cumulative `runningBalance` that increases on IN and decreases on OUT; final running balance reconciles with the current balance card.
- **Convention Assertions:** C1 (transactions shown by txn number, not uid); C8 (money coerced; never `.startsWith/.trim` on amount — enforced by component); C6 axe.
- **Negative / Edge:** an account with no transactions → empty/zero statement; the `txnType` values shown originate from `AR_RECEIPT/AP_PAYMENT/TRANSFER_IN/TRANSFER_OUT/DIRECT_ENTRY` (NOT the FE model's `RECEIPT/PAYMENT/CHEQUE` — assert the rendered values match the backend enum; flag drift if a label is blank/unknown).

### TC-CASH-062 — Book vs GL reconciliation indicator (single account)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cash Statement GL-recon (`GET …/statements/accounts/uid/{uid}/gl-reconciliation`)
- **Permission / Role:** `CASH.VIEW` scoped
- **Preconditions / Seed:** an account whose book balance equals its linked GL account balance.
- **Steps:** Select the account; observe the GL-reconciliation panel.
- **Expected Result:** panel shows bookBalance, linkedGlBalance, and difference; `glReconciled` true when |difference| < 1e-6 (a non-zero difference is a finance-grade defect — assert it is zero for a healthy account).
- **Convention Assertions:** C2; C8 (all three values coerced from number).
- **Negative / Edge:** if difference ≠ 0, the screen must flag the discrepancy (file as a backend posting defect, per ADR-0016 D-9).

### TC-CASH-063 — Statement screen loading / empty / error / forbidden
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cash Statement (`/admin/cash/statement`)
- **Permission / Role:** `CASH.VIEW`
- **Steps:** Before picking an account → empty (`isEmpty` = no statement); throttle statement GET → loading; force 500 → error; 403 → forbidden.
- **Expected Result:** four distinct states; GL-recon panel has its own `glReconState`.
- **Convention Assertions:** C4; C6.
- **Negative / Edge:** Refresh button re-loads the selected account's statement.

### TC-CASH-064 — Company-wide balances list (API) + GL reconciliation all (API)
- **Type:** Manual / API
- **Priority:** P2
- **Module / Submodule:** Statements (`GET …/statements/balances?companyId`, `GET …/statements/gl-reconciliation?companyId`)
- **Permission / Role:** `CASH.VIEW` (`@perm.has('CASH.VIEW')`)
- **Preconditions / Seed:** company with several accounts.
- **Steps:** API: GET `/balances` and `/gl-reconciliation` for the company as ORG_ADMIN, then as a no-perm user.
- **Expected Result:** ORG_ADMIN → 200 lists (`CashAccountBalanceDto[]` / `CashGlReconciliationDto[]`); no-perm → 403. `listBalances()` is wired in the service; `glReconciliation` all-accounts read is **API-available** (the statement screen uses the single-account variant).
- **Convention Assertions:** C2; C3; C7 (company-scoped).
- **Negative / Edge:** any account with non-zero `difference` is surfaced for investigation.

---

## G. Cross-cutting: tenancy, branch, RBAC, money/date

### TC-CASH-070 — Cross-tenant isolation (company A cannot see company B's cash data)
- **Type:** Automated (Playwright + API)
- **Priority:** P1
- **Module / Submodule:** all cash reads (`/accounts`, `/transfers`, `/cheques`, `/reconciliations`, `/statements`)
- **Permission / Role:** `CASH.VIEW` — ORG_ADMIN of tenant A
- **Variation:** tenant A vs tenant B
- **Preconditions / Seed:** two tenants each with cash accounts + transactions.
- **Steps:** As tenant A's ORG_ADMIN, list accounts/transfers/cheques (scoped to A's `companyId`); attempt to GET a tenant-B account/transfer/cheque uid directly.
- **Expected Result:** lists show only tenant A's data; B's uid via scoped endpoints → 403/404 (never B's data).
- **Convention Assertions:** C7 (company-scoped); C3 (scoped helpers enforce ownership).
- **Negative / Edge:** rootadmin (superuser) **can** see across tenants — use it only to confirm seed, never for the negative assertion.

### TC-CASH-071 — Branch-scoped account vs company-level account
- **Type:** Manual / API
- **Priority:** P2
- **Module / Submodule:** Cash/Bank Accounts (`POST …/accounts` with optional `branchUid`)
- **Permission / Role:** `CASH.ACCOUNT.MANAGE`
- **Variation:** account with `branchUid` set (branch-scoped) vs null (company-level); default vs non-default branch
- **Preconditions / Seed:** a multi-branch company; a user assigned to ONE branch.
- **Steps:** Create one account with `branchUid` = a specific branch and one without; act as a user assigned only to a different branch.
- **Expected Result:** `CreateCashBankAccountRequest.branchUid` is optional — company-level account has `branchId=null`; branch-scoped account carries the branch. Verify visibility/scoping respects the acting branch (`X-Branch-Uid`) and that a user acting in a branch they are not assigned to is denied write.
- **Convention Assertions:** C7 (branch scoping); C1 (branch chosen by name if a branch picker is offered — note the **accounts UI does not render a branch picker**, so branch-scoped creation is currently **API-only** → flag as gap).
- **Negative / Edge:** acting in an unassigned branch → denied.

### TC-CASH-072 — Money is a string on the wire, formatted "CUR 1,234.56"
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** all money-bearing screens (statement, transfer, cheque list, reconciliation)
- **Permission / Role:** `CASH.VIEW`
- **Steps:** Inspect amounts/balances in the cheque list ("CUR fmtMoney"), transfer summary, statement running balance, recon difference.
- **Expected Result:** amounts render with the currency prefix and 2 decimals; request bodies send amounts as **string** decimals (`amount: string` in the FE request models).
- **Convention Assertions:** C8 (money string on wire; never numeric-typed in requests; coerced with `+v` for display).
- **Negative / Edge:** a value with more than 2 decimals is displayed at 2dp; NaN/empty → "0.00".

### TC-CASH-073 — Dates are ISO yyyy-MM-dd everywhere
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** transfer date, entry txnDate, cheque issue/value dates, statement date
- **Permission / Role:** relevant write perms
- **Steps:** Inspect each date `<input type="date">` and the rendered date cells.
- **Expected Result:** all default/displayed dates are ISO `yyyy-MM-dd`.
- **Convention Assertions:** C8 (date ISO).
- **Negative / Edge:** cleared date inputs block submission (per TC-CASH-023/032/042/051).

### TC-CASH-074 — Empty cash & bank nav for a NO-PERMISSION user
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** shell nav (`shell.component.ts` Cash & Bank group)
- **Permission / Role:** none of CASH.* / CHEQUE.MANAGE
- **Preconditions / Seed:** NO-PERMISSION user.
- **Steps:** Log in; expand the finance nav.
- **Expected Result:** none of "Cash & Bank Accounts", "Cash Transfer", "Transfers", "Cash / Bank Entry", "Cheques", "Bank Reconciliation", "Cash Statement" appear (each gated by its `permission`); directly visiting any `/admin/cash/...` route is blocked by its `requirePermission(...)` guard.
- **Convention Assertions:** C3 (nav items + route guards both gate by permission code).
- **Negative / Edge:** a CASH.VIEW-only user sees "Cash & Bank Accounts", "Transfers", "Cash Statement" but NOT "Cash Transfer", "Cash / Bank Entry", "Cheques", "Bank Reconciliation".

### TC-CASH-075 — Axe accessibility sweep across all cash screens
- **Type:** Automated (Playwright + axe)
- **Priority:** P2
- **Module / Submodule:** all six cash routes
- **Permission / Role:** ORG_ADMIN
- **Steps:** For each of `/admin/cash/accounts`, `/transfers`, `/transfers/record`, `/entries/record`, `/cheques`, `/reconciliations`, `/statement` run an axe scan in idle and form-open states.
- **Expected Result:** zero serious/critical axe violations; tables have captions + `scope`; form controls have associated labels; spinners/alerts have appropriate aria roles.
- **Convention Assertions:** C6 (WCAG 2.1 AA).
- **Negative / Edge:** the open create/register forms (`role="form"` with aria-label) must also be axe-clean.

---

## Coverage map (controller endpoints → cases)

| Endpoint | Case(s) |
|---|---|
| `POST /cash/accounts` | TC-CASH-010/011/012/013 |
| `PUT /cash/accounts/uid/{uid}` | TC-CASH-015 (no-UI gap) |
| `POST /cash/accounts/uid/{uid}/set-default` | TC-CASH-013/014 |
| `GET /cash/accounts/uid/{uid}` | TC-CASH-016 |
| `GET /cash/accounts` | TC-CASH-001..006 |
| `POST /cash/transfers` | TC-CASH-020/021/022/023 |
| `GET /cash/transfers/uid/{uid}` | TC-CASH-025 |
| `GET /cash/transfers` | TC-CASH-024/026 |
| `POST /cash/entries` | TC-CASH-030/031/032 |
| `GET /cash/entries/uid/{uid}` | TC-CASH-033 (note) |
| `GET /cash/entries` | TC-CASH-033 (note) |
| `POST /cash/cheques` | TC-CASH-041/042 |
| `POST /cash/cheques/uid/{uid}/clear` | TC-CASH-043/045 |
| `POST /cash/cheques/uid/{uid}/cancel` | TC-CASH-044/045 |
| `GET /cash/cheques/uid/{uid}` | (read via list TC-CASH-040) |
| `GET /cash/cheques` | TC-CASH-040/046/047 |
| `POST /cash/reconciliations` | TC-CASH-050/051 |
| `POST /cash/reconciliations/uid/{uid}/mark-cleared` | TC-CASH-052/054 |
| `POST /cash/reconciliations/uid/{uid}/complete` | TC-CASH-053/054 |
| `GET /cash/reconciliations/uid/{uid}` | (read via list/flow) |
| `GET /cash/reconciliations` | TC-CASH-055/056 |
| `GET /cash/statements/accounts/uid/{uid}/balance` | TC-CASH-060 |
| `GET /cash/statements/accounts/uid/{uid}/statement` | TC-CASH-061 |
| `GET /cash/statements/balances` | TC-CASH-064 |
| `GET /cash/statements/gl-reconciliation` | TC-CASH-064 |
| `GET /cash/statements/accounts/uid/{uid}/gl-reconciliation` | TC-CASH-062 |

## Defects / gaps surfaced (file against ISSUES-REGISTER)
1. **Cheque action gating mismatch (FE/BE):** UI checks `status === 'PENDING'` but API returns `ISSUED` → Clear/Cancel buttons never render for a freshly registered cheque, and the status badge falls through to the default style. FE model `ChequeStatus` should be `ISSUED|CLEARED|CANCELLED`. (TC-CASH-041/043/044)
2. **CashTxnType FE/BE drift:** FE model lists `RECEIPT|PAYMENT|CHEQUE`; backend is `AR_RECEIPT|AP_PAYMENT|TRANSFER_IN|TRANSFER_OUT|DIRECT_ENTRY`. Any statement label keyed off the FE union risks blanks. (TC-CASH-061)
3. **Account edit/deactivate has no UI** despite `PUT …/uid/{uid}` + `updateAccount()` existing. (TC-CASH-015)
4. **Branch-scoped account creation is API-only** — no branch picker in the accounts create form. (TC-CASH-071)
5. **Direct-entry list has no screen** — entries surface only inside the statement. (TC-CASH-033)
6. **C1 violation — transfers show raw account uids:** the transfers **list** (`{{ row.sourceAccountUid }}`/`{{ row.destinationAccountUid }}`) and **detail** (`{{ t.sourceAccountUid }}`/`{{ t.destinationAccountUid }}`) render the source/destination **account uids** instead of a human account code/name. `CashTransferDto` does not carry account names, so the UI cannot resolve them today. Fix: include account code/name in the DTO (or join client-side). (TC-CASH-024/025)
