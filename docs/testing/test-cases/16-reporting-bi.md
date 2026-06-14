# Reporting & BI — Test Cases (Domain: REP)

End-to-end test cases for the **Reporting + Business-Intelligence** domain: the four financial
statements (P&L, Balance Sheet, Cash-Flow), the GL trial balance, the account-ledger drill-through,
the composite **BI Analytics dashboard** (per-panel RBAC + graceful per-panel forbidden + drill +
export), and the analytical reports (budget variance, departmental actuals, dimension-sliced TB,
project WIP / P&L, manufacturing WIP reconciliation). All reporting endpoints are **read-only over
the GL** — computed on demand, nothing stored, nothing posted (BR-REP-08). Money is base-currency
only; both a current and a comparative column are carried on every statement figure.

## Modules / submodules covered

| Submodule | Controller (verified) | API base path | Frontend route | Component |
|---|---|---|---|---|
| Income Statement / P&L | `ReportingController` | `/api/v1/reports/income-statement` (+ `/export`) | `/admin/reporting/income-statement` | `income-statement.component` |
| Balance Sheet | `ReportingController` | `/api/v1/reports/balance-sheet` (+ `/export`) | `/admin/reporting/balance-sheet` | `balance-sheet.component` |
| Cash-Flow Statement (indirect) | `ReportingController` | `/api/v1/reports/cash-flow` (+ `/export`) | `/admin/reporting/cash-flow` | `cash-flow-statement.component` |
| Account-ledger drill-down | `ReportingController` | `/api/v1/reports/account-ledger` (+ `/export`) | `/admin/reporting/account-ledger` | `account-ledger.component` |
| Trial Balance | `TrialBalanceController` | `/api/v1/gl/trial-balance` (+ `/period`) | `/admin/gl/trial-balance` | `gl/trial-balance.component` |
| BI Dashboard (composite + panels + export) | `BiDashboardController` | `/api/v1/bi/dashboard`, `/finance-summary`, `/working-capital`, `/inventory`, `/crm-summary`, `/revenue-trend`, `/net-profit-trend`, `/dashboard/export` | `/admin/dashboard` | `dashboard/dashboard.component` |
| Budget variance / departmental actuals | `BudgetReportController` | `/api/v1/budgeting/variance`, `/api/v1/budgeting/departmental-actuals` | `/admin/budgeting/variance`, `/admin/budgeting/departmental-actuals` | `budgeting/budget-variance-report.component`, `budgeting/departmental-actuals-report.component` |
| Dimension-sliced trial balance | `DimensionReportController` | `/api/v1/costing/reports/sliced-trial-balance` | `/admin/cost-centre/report` | `cost-centre/costing-report.component` |
| Manufacturing WIP reconciliation | `ManufacturingReportController` | `/api/v1/manufacturing/wip-reconciliation` | `/admin/manufacturing/wip-reconciliation` | `manufacturing/wip-reconciliation.component` |
| Project WIP report | `ProjectCostingController` | `/api/v1/project-costing/wip` | `/admin/projects/wip-report` | `projects/project-wip-report.component` |
| Project P&L (embedded, NOT a standalone report route) | `ProjectCostingController` | `/api/v1/project-costing/projects/uid/{projectUid}/pnl` | embedded in `/admin/projects/uid/:uid` (project detail) | `projects/project-detail.component` (`loadPnl()`) |

## Permission codes in scope (EXACT — verified in controllers + V15/V70/V81 seed migrations)

- **Reporting (V15):** `REPORT.VIEW` (coarse, used by FE as a fallback only — no controller enforces it), `REPORT.PL.VIEW`, `REPORT.BS.VIEW`, `REPORT.CASHFLOW.VIEW`, `REPORT.LEDGER.VIEW`, `REPORT.EXPORT`.
- **Trial Balance:** `GL.VIEW`.
- **BI (V81):** `BI.VIEW` (route gate + composite), `BI.FINANCE.VIEW`, `BI.OPS.VIEW`, `BI.CRM.VIEW`, `BI.EXPORT`.
- **Budget reports (V70):** `BUDGETING.REPORT.VIEW` (variance + departmental actuals). `BUDGETING.REPORT.EXPORT` is **seeded but not wired** — no controller endpoint and no FE export button exist (see TC-REP-110, a documentation gap to flag).
- **Dimension-sliced TB:** `COSTING.VIEW` **and** `GL.VIEW` (both required — `@perm.has('COSTING.VIEW') and @perm.has('GL.VIEW')`).
- **Manufacturing WIP recon:** `MANUFACTURING.VIEW`.
- **Project costing:** `PROJECTS.COSTING.VIEW` (WIP report uses `@perm.has`; per-project P&L uses `@perm.scoped(#projectUid,'project','PROJECTS.COSTING.VIEW')`).

### Verified enum values

- `ExportFormat { PDF, XLSX, CSV }` (export `format` query param; default `PDF`).
- `DimensionSlot { COST_CENTRE, DEPARTMENT, DIMENSION_3, DIMENSION_4 }` (v1 activates COST_CENTRE + DEPARTMENT only).
- `AccountType { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }` (budget-variance `accountType` filter).
- Statement reconciliation: `ReconciliationDto.ties` boolean — `true` = balanced (green), `false` = data-integrity alarm (red). Never plugged (read-only).

### Verified API contract notes

- **Reporting & TrialBalance read endpoints return the DTO directly (NOT an `ApiResponse<T>` envelope)** — the interceptor's `isEnvelope()` does not match (no `errors` array) so the body passes through unwrapped. (C2 applies differently here — see per-case notes.)
- **BI read endpoints DO return `ApiResponse<T>`** (`ApiResponse.ok(...)`) and auto-unwrap.
- **`companyId` is always an explicit query param** on every reporting/BI endpoint — never inferred from the principal; `scopeGuard.assertCanActIn` fires inside the service (defense in depth).
- **Export endpoints return raw `byte[]`** with `Content-Disposition: attachment; filename="…"`; FE requests `responseType:'blob'` and triggers a browser download. The ledger export is capped at `LEDGER_EXPORT_MAX_ROWS = 10_000`.
- **Account-ledger pagination** is custom: the DTO carries `page`/`size`/`totalElements` (not the standard `meta` envelope); the FE synthesises a `PageMeta` for `<app-paginator>`. Default `size=50`.
- The BI dashboard composite returns **nullable panels** — a `null` panel means the caller lacked the fine-grained panel permission (or an upstream query errored); the FE renders that panel as a calm per-panel "forbidden"/"no data" state, never a whole-page 403.

## Type / role variations exercised

| Dimension | Variations covered |
|---|---|
| User roles (allowed) | `rootadmin` (superuser bypass), `ACCOUNTANT` (full reporting), `ORG_ADMIN`, a **CUSTOM** role with a partial permission subset (e.g. `BI.VIEW`+`BI.FINANCE.VIEW` only), `SALES_MANAGER` (CRM panel), `STOREKEEPER` (OPS panel) |
| User roles (denied) | **NO-PERMISSION** user (empty nav, 403 everywhere); a role with `BI.VIEW` but lacking `BI.FINANCE.VIEW`/`BI.OPS.VIEW`/`BI.CRM.VIEW` (per-panel forbidden); a role with `REPORT.PL.VIEW` but lacking `REPORT.EXPORT` (no export button) |
| Export format | PDF, XLSX, CSV (each statement, ledger, dashboard) |
| Comparative period | with comparative (`cmpFrom/cmpTo` or `compareAsAt`) vs without |
| DimensionSlot | COST_CENTRE, DEPARTMENT (active); DIMENSION_3 (reserved — expect empty) |
| AccountType filter (variance) | all-types vs single (INCOME, EXPENSE — favourable/adverse logic differs) |
| Reconciliation state | `ties=true` (balanced/green) vs `ties=false` (data-integrity alarm/red) |
| Company / branch scoping | multi-company org (company picker), branch filter on dashboard/CRM panel; cross-tenant denial |
| Screen states | loading / empty / error / forbidden on every list & report |

---

## TEST CASES

### TC-REP-001 — Income Statement renders sections, subtotals, and reconciliation bar
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Income Statement (`/admin/reporting/income-statement` · `/api/v1/reports/income-statement`)
- **Permission / Role:** `REPORT.PL.VIEW` — runs as ACCOUNTANT; also as NO-PERMISSION user → expect forbidden
- **Variation:** no comparative; reconciliation `ties=true`
- **Preconditions / Seed:** company with posted journals across INCOME and EXPENSE accounts for the chosen period (use a seeded fiscal year with at least one sale + one expense).
- **Steps:**
  1. Navigate to `/admin/reporting/income-statement`.
  2. If `companies().length > 1`, choose company by **name** in the "Company" `<select>`.
  3. Set "Period from" and "Period to" (date inputs) to the seeded period; leave comparative blank.
  4. Click **Run** (`getByRole('button', { name: /Run/ })`).
- **Test Data:** company = "Acme Trading Ltd"; from = `2026-01-01`, to = `2026-03-31`.
- **Expected Result:** the statement table shows section headers `REVENUE`, `COST_OF_SALES`, `OPERATING_EXPENSES` (titles), each with detail lines (code + account name + period amount + comparative column "—" when blank), a per-section subtotal row, and a tfoot with **Gross Profit** and **Net Profit** rows. A green **"Reconciled"** alert (`role="status"`) is shown ("net profit equals the period INCOME − EXPENSE GL movement"). Money formatted to 2 dp.
- **Convention Assertions:** C2 (read returns DTO directly, not envelope; UI still renders fine); C3 (RBAC gate); C4 (loading "Building Income Statement…" then idle); C6 (axe scan, table has `<caption>` "Income Statement", `scope="col"` headers); C8 (money 2dp, ISO date inputs).
- **Negative / Edge:** run with from > to → empty/zero statement (no client crash); run as NO-PERMISSION user → the screen shows "You don't have permission to view the Income Statement" and the `GET` returns 403.

### TC-REP-002 — Income Statement with comparative period populates the comparative column
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Income Statement (`/admin/reporting/income-statement` · `/api/v1/reports/income-statement`)
- **Permission / Role:** `REPORT.PL.VIEW` — runs as ACCOUNTANT
- **Variation:** WITH comparative (`cmpFrom`/`cmpTo`)
- **Preconditions / Seed:** two adjacent periods both with postings.
- **Steps:**
  1. Navigate to the route, choose company by name.
  2. Set from/to = current period; set **Comparative from/to** = prior period.
  3. Click Run.
- **Test Data:** from `2026-04-01`/to `2026-06-30`; cmpFrom `2026-01-01`/cmpTo `2026-03-31`.
- **Expected Result:** the table's 4th column (`s.header.comparativeLabel`) shows the prior-period amounts; both Gross Profit and Net Profit tfoot rows carry current + comparative figures. Negative net profit renders `text-danger`.
- **Convention Assertions:** C2; C6 axe; C8 money/date. Request includes `cmpFrom`/`cmpTo` query params only when both are set.
- **Negative / Edge:** set comparative-from but leave comparative-to blank → FE only sends params when truthy (verify request omits `cmpTo`, no error).

### TC-REP-003 — Income Statement drill-through: account line links to account-ledger pre-filled
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Income Statement → Account Ledger (`/admin/reporting/account-ledger`)
- **Permission / Role:** `REPORT.PL.VIEW` + `REPORT.LEDGER.VIEW` — runs as ACCOUNTANT
- **Preconditions / Seed:** an income statement with at least one detail line whose `accountUid` is non-null (real GL account, not a synthetic equity-fold line).
- **Steps:**
  1. Run an income statement (TC-REP-001).
  2. Click an account **name** link in a detail row.
- **Test Data:** click "Sales Revenue".
- **Expected Result:** navigates to `/admin/reporting/account-ledger?accountUid=<uid>&fromDate=<from>&toDate=<to>&companyId=<id>`; the ledger screen **auto-runs** (because all params pre-filled) and shows opening balance / running lines / closing balance for that account and period.
- **Convention Assertions:** **C1** — the account is referenced via the link's `accountUid` carried *in the URL query string only*; the on-screen link label is the human account **name**, and no raw uid is displayed as visible table text. C3 (ledger gated `REPORT.LEDGER.VIEW`). C4.
- **Negative / Edge:** a synthetic line (null `accountUid`, e.g. an equity fold) renders as **plain text, not a link** — assert it is not clickable.

### TC-REP-004 — Income Statement export to PDF / XLSX / CSV
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Income Statement export (`/api/v1/reports/income-statement/export`)
- **Permission / Role:** `REPORT.EXPORT` — runs as ACCOUNTANT; also as a role with `REPORT.PL.VIEW` but NOT `REPORT.EXPORT` → export buttons hidden
- **Variation:** each `ExportFormat` value (PDF, XLSX, CSV)
- **Preconditions / Seed:** a runnable income statement (TC-REP-001).
- **Steps:**
  1. Run the statement.
  2. In the export toolbar (only visible when `canExport()`), click **PDF**, then re-run and click **Excel**, then **CSV**.
- **Test Data:** format = PDF / XLSX / CSV.
- **Expected Result:** each click triggers a file download named `income-statement_<from>_<to>.<ext>` (`.pdf`/`.xlsx`/`.csv`); the request carries `format=PDF|XLSX|CSV` and the response has `Content-Disposition: attachment`. Button disabled (`exporting()`) while in flight.
- **Convention Assertions:** C3 (export buttons absent for the no-`REPORT.EXPORT` role; backend `GET .../export` returns 403 for that role); C8.
- **Negative / Edge:** export before running a statement → no-op (guard `!companyId || !from || !to`).

### TC-REP-005 — Income Statement: data-integrity alarm when reconciliation fails
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Income Statement (`/api/v1/reports/income-statement`)
- **Permission / Role:** `REPORT.PL.VIEW` — runs as ACCOUNTANT
- **Variation:** reconciliation `ties=false`
- **Preconditions / Seed:** a books state where net profit ≠ INCOME−EXPENSE GL movement (engineered/corrupted dataset — only reproducible against a deliberately broken fixture).
- **Steps:** run the statement against the broken fixture.
- **Expected Result:** a **red `alert-danger`** ("Data integrity alarm — <label> (difference: <amount>). Investigate the books.") replaces the green Reconciled bar. The statement is still shown; nothing is auto-corrected (read-only — never plugged).
- **Convention Assertions:** C9 (reporting is read-only; no reversal/edit offered); C6 (alert has `role="alert"`).
- **Negative / Edge:** N/A (this case IS the negative path).

### TC-REP-010 — Balance Sheet renders asset/liability/equity sections and balances
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Balance Sheet (`/admin/reporting/balance-sheet` · `/api/v1/reports/balance-sheet`)
- **Permission / Role:** `REPORT.BS.VIEW` — runs as ACCOUNTANT; also as NO-PERMISSION → forbidden
- **Variation:** as-at single date, no comparative
- **Preconditions / Seed:** company with a balanced set of books as at the chosen date.
- **Steps:**
  1. Navigate to `/admin/reporting/balance-sheet`.
  2. Choose company by name; set **As-at date**; click Run.
- **Test Data:** asAtDate = `2026-06-30`.
- **Expected Result:** sections `CURRENT_ASSETS`, `NON_CURRENT_ASSETS`, `CURRENT_LIABILITIES`, `NON_CURRENT_LIABILITIES`, `EQUITY` (incl. the two synthetic equity-fold lines) with subtotals; totals **Total Assets**, **Total Liabilities**, **Total Equity**. Green Reconciled bar ("total assets == total liabilities + total equity").
- **Convention Assertions:** C2; C3; C4; C6 (caption/scope, axe); C8.
- **Negative / Edge:** NO-PERMISSION user → forbidden message + 403; an as-at date before any posting → all-zero statement (still balances, ties=true).

### TC-REP-011 — Balance Sheet with comparative as-at date
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Balance Sheet (`/api/v1/reports/balance-sheet`)
- **Permission / Role:** `REPORT.BS.VIEW` — runs as ACCOUNTANT
- **Variation:** WITH `compareAsAt`
- **Preconditions / Seed:** balances at two as-at dates.
- **Steps:** choose company; set as-at = `2026-06-30`, compare-as-at = `2025-12-31`; Run.
- **Expected Result:** the comparative column carries the prior as-at balances; both columns appear in every section line and in the three totals.
- **Convention Assertions:** C8; request sends `compareAsAt` only when set.
- **Negative / Edge:** equity-fold synthetic lines have null `accountUid` → plain text, not drill links.

### TC-REP-012 — Balance Sheet drill-through to account ledger
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Balance Sheet → Account Ledger
- **Permission / Role:** `REPORT.BS.VIEW` + `REPORT.LEDGER.VIEW` — runs as ACCOUNTANT
- **Steps:** run a balance sheet; click a real asset/liability account name (non-synthetic line).
- **Expected Result:** navigates to the account ledger pre-filled with `accountUid`, `companyId`, and (note) the **as-at date passed as the toDate**; ledger auto-runs.
- **Convention Assertions:** C1 (name link, uid only in query string; verify no uid visible on screen); C4.
- **Negative / Edge:** synthetic equity-fold line is not a link.

### TC-REP-013 — Balance Sheet export (PDF/XLSX/CSV) gated by REPORT.EXPORT
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Balance Sheet export (`/api/v1/reports/balance-sheet/export`)
- **Permission / Role:** `REPORT.EXPORT` — runs as ACCOUNTANT; denied role hides buttons + 403
- **Variation:** each ExportFormat
- **Steps:** run; click PDF / Excel / CSV.
- **Expected Result:** download `balance-sheet_<asAt>.<ext>`; request carries `asAtDate`, optional `compareAsAt`, `format`.
- **Convention Assertions:** C3 export RBAC; C8.
- **Negative / Edge:** export with no statement run → no-op.

### TC-REP-020 — Cash-Flow Statement (indirect) renders OPERATING/INVESTING/FINANCING + net change
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cash-Flow (`/admin/reporting/cash-flow` · `/api/v1/reports/cash-flow`)
- **Permission / Role:** `REPORT.CASHFLOW.VIEW` — runs as ACCOUNTANT; also NO-PERMISSION → forbidden
- **Variation:** period, no comparative
- **Preconditions / Seed:** company with cash movements in the period.
- **Steps:** navigate to `/admin/reporting/cash-flow`; choose company; set from/to; Run.
- **Test Data:** from `2026-01-01`, to `2026-06-30`.
- **Expected Result:** sections OPERATING, INVESTING, FINANCING; figures for **Opening Cash**, **Net Change in Cash**, **Closing Cash**; green Reconciled bar ("net change in cash == Δ cash-equivalent GL accounts").
- **Convention Assertions:** C2; C3; C4; C6; C8.
- **Negative / Edge:** NO-PERMISSION → forbidden + 403; period with no cash movement → zero net change, ties=true.

### TC-REP-021 — Cash-Flow with comparative period
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cash-Flow (`/api/v1/reports/cash-flow`)
- **Permission / Role:** `REPORT.CASHFLOW.VIEW` — runs as ACCOUNTANT
- **Variation:** WITH `cmpFrom`/`cmpTo`
- **Steps:** set period + comparative period; Run.
- **Expected Result:** comparative column populated across all three sections and the opening/net-change/closing figures.
- **Convention Assertions:** C8; comparative params sent only when both present.

### TC-REP-022 — Cash-Flow export (PDF/XLSX/CSV)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cash-Flow export (`/api/v1/reports/cash-flow/export`)
- **Permission / Role:** `REPORT.EXPORT` — runs as ACCOUNTANT; denied role → buttons hidden + 403
- **Variation:** each ExportFormat
- **Steps:** run; export each format.
- **Expected Result:** download `cash-flow_<from>_<to>.<ext>` (per `downloadBlob` naming); request carries `format`.
- **Convention Assertions:** C3; C8.

### TC-REP-030 — Account Ledger drill-down: opening / running / closing balances with picker
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Account Ledger (`/admin/reporting/account-ledger` · `/api/v1/reports/account-ledger`)
- **Permission / Role:** `REPORT.LEDGER.VIEW` — runs as ACCOUNTANT; also NO-PERMISSION → forbidden
- **Variation:** account chosen via `<app-uid-picker>`
- **Preconditions / Seed:** a GL account with ≥ 1 posted journal line in the period; the GL accounts list loads into the picker.
- **Steps:**
  1. Navigate to `/admin/reporting/account-ledger` (no query params).
  2. Choose company by name.
  3. In the **account picker** (`<app-uid-picker>`), select an account by its **name/code** (picker shows `label=name`, `hint=accountCode`).
  4. Set from/to; click Run.
- **Test Data:** account = "Bank — Main Current" (code 1100); from `2026-01-01`, to `2026-06-30`.
- **Expected Result:** the screen shows the account code + name header, an **opening balance** (as-at fromDate−1), the running journal lines with a running balance column (negatives in `text-danger`), and a **closing balance** (as-at toDate).
- **Convention Assertions:** **C1** — the account is selected via the uid-picker **by name**, NOT by typing a uid; the chosen uid is stored under the hood and not shown as visible label text. C2 (DTO returned directly with custom page/size/totalElements). C3; C4; C6 (axe, caption); C8.
- **Negative / Edge:** Run with no account selected → no-op (guard). NO-PERMISSION user → forbidden + 403.

### TC-REP-031 — Account Ledger pagination via shared paginator
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Account Ledger (`/api/v1/reports/account-ledger` — `page`/`size=50`)
- **Permission / Role:** `REPORT.LEDGER.VIEW` — runs as ACCOUNTANT
- **Preconditions / Seed:** an account with > 50 journal lines in the chosen period (so `totalElements > 50`, ≥ 2 pages).
- **Steps:**
  1. Run the ledger for the busy account.
  2. Use `<app-paginator>` controls: FIRST, PREVIOUS, page numbers, NEXT, LAST.
- **Test Data:** account with 130 lines → 3 pages (50/50/30).
- **Expected Result:** page 1 shows the first 50 rows; NEXT loads `page=1` (rows 51–100); LAST loads the final page; PREVIOUS/FIRST navigate back. The synthesised `PageMeta` reports `totalPages=3`, `hasNext` true on pages 0–1. Each navigation re-fetches (`loadPage`).
- **Convention Assertions:** **C5** — full paginator (FIRST/PREVIOUS/page-numbers/NEXT/LAST) present; paginator self-hides when `totalElements ≤ 50` (1 page). C6 axe.
- **Negative / Edge:** account with ≤ 50 lines → paginator hidden (single page).

### TC-REP-032 — Account Ledger export is bounded (≤ 10,000 rows)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Account Ledger export (`/api/v1/reports/account-ledger/export`)
- **Permission / Role:** `REPORT.EXPORT` — runs as ACCOUNTANT; denied role → no export controls + 403
- **Variation:** each ExportFormat
- **Steps:** run a ledger; export PDF / XLSX / CSV.
- **Expected Result:** download `account-ledger_<accountCode-or-uid>_<from>_<to>.<ext>`. **(Manual/contract)** the export pulls a single bounded page `size=10_000` (`LEDGER_EXPORT_MAX_ROWS`), NOT the whole multi-year ledger (NFR-REP-02) — verify a very long ledger truncates at 10k rows rather than OOM-ing.
- **Convention Assertions:** C3 export RBAC; C8.
- **Negative / Edge:** a > 10,000-line ledger exports only the first 10k rows (documented cap — narrow the date range for the rest).

### TC-REP-033 — Account Ledger four-state: loading / empty / error / forbidden
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Account Ledger (`/api/v1/reports/account-ledger`)
- **Permission / Role:** `REPORT.LEDGER.VIEW` — runs as ACCOUNTANT (states 1–3) and NO-PERMISSION (state 4)
- **Steps:**
  1. **Empty:** load the screen; before running, assert the empty prompt (`isEmpty()` true, `state='idle'`, `ledger=null`).
  2. **Loading:** click Run with throttled network; assert the loading state.
  3. **Error:** force a 500 (or invalid account) → assert the error state.
  4. **Forbidden:** as NO-PERMISSION, the API 403 → `state='forbidden'`.
- **Expected Result:** each state renders its distinct UI; a 403 maps to `forbidden`, any other error to `error`.
- **Convention Assertions:** **C4** four-state; C6.
- **Negative / Edge:** account with zero lines in range → opening==closing, empty rows table (not an error).

### TC-REP-040 — Trial Balance (full) computes and nets to zero
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Trial Balance (`/admin/gl/trial-balance` · `/api/v1/gl/trial-balance`)
- **Permission / Role:** `GL.VIEW` — runs as ACCOUNTANT; also NO-PERMISSION → forbidden
- **Variation:** all-periods (full)
- **Preconditions / Seed:** a posted set of books.
- **Steps:** navigate to `/admin/gl/trial-balance`; choose company; view the full TB.
- **Expected Result:** every account with its debit/credit totals; total debits == total credits (balanced indicator). Computed on demand, nothing stored.
- **Convention Assertions:** C2 (TB returns DTO directly); C3 (`GL.VIEW`); C4; C6; C8.
- **Negative / Edge:** NO-PERMISSION → forbidden + 403.

### TC-REP-041 — Trial Balance filtered to a single fiscal period
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Trial Balance period (`/api/v1/gl/trial-balance/period`)
- **Permission / Role:** `GL.VIEW` — runs as ACCOUNTANT
- **Variation:** `periodId` filter
- **Preconditions / Seed:** ≥ 2 fiscal periods with postings.
- **Steps:** choose company; select a fiscal **period** (by label) and view the period TB.
- **Test Data:** period = "2026 P03 (Mar)".
- **Expected Result:** TB restricted to that period's movement; still balances. Request hits `/period?companyId=&periodId=`.
- **Convention Assertions:** C1 (period chosen by human label; the `periodId` not hand-typed); C3; C4.
- **Negative / Edge:** a period with no postings → all-zero, balanced TB (not an error).

### TC-REP-050 — BI Dashboard loads with all panels for a full-permission user
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** BI Dashboard (`/admin/dashboard` · `/api/v1/bi/dashboard`)
- **Permission / Role:** `BI.VIEW` + `BI.FINANCE.VIEW` + `BI.OPS.VIEW` + `BI.CRM.VIEW` — runs as ACCOUNTANT/ORG_ADMIN with all BI perms
- **Variation:** single company, default branch, current-month date range (defaults)
- **Preconditions / Seed:** company with finance/cash, AR/AP, inventory, and CRM pipeline data.
- **Steps:**
  1. Navigate to `/admin/dashboard`.
  2. Wait for company + branch to auto-select and the composite `GET /bi/dashboard` to resolve.
- **Expected Result:** the **health strip** (`role="status"`, AR/AP/Cash/Stock/TB badges with `[OK]`/`[!]` text prefixes) renders; **Finance** stat-cards (Revenue, OpEx, Net Profit period, Trial Balance badge), **Cash Position**, **Working Capital** (AR/AP), **Inventory** (stock value + 1300 recon), **CRM** (pipeline bars + win-rate KPIs + forecast), **Revenue Trend** (12-period CSS bars), **Net Profit Trend** all populate.
- **Convention Assertions:** C2 (BI returns `ApiResponse<T>`, auto-unwrapped); C3 (route gated `BI.VIEW`); **C4** per-panel four-state `@switch`; C6 (axe; trend `role="img"` with `aria-label`, table captions, `[OK]`/`[!]` non-color cues); C8 (money 2dp).
- **Negative / Edge:** a company with no data → each panel shows its calm "No … data yet" empty state, not an error.

### TC-REP-051 — BI Dashboard graceful per-panel forbidden (partial-permission CUSTOM role)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** BI Dashboard (`/api/v1/bi/dashboard` — nullable panels)
- **Permission / Role:** `BI.VIEW` + `BI.FINANCE.VIEW` ONLY (a CUSTOM role lacking `BI.OPS.VIEW` and `BI.CRM.VIEW`)
- **Variation:** partial panel permissions
- **Preconditions / Seed:** the CUSTOM role; data present for all panels.
- **Steps:** log in as the CUSTOM role; open `/admin/dashboard`.
- **Expected Result:** Finance / Working Capital / Cash / trends render normally; the **Inventory** panel shows "You do not have permission to view inventory KPIs" and the **CRM** panel shows "You do not have permission to view CRM KPIs" — each a calm per-panel forbidden, **NOT a whole-page 403**. (Backend returns the composite with `inventory=null`, `crm=null`; FE maps null + `!canOps()/!canCrm()` to the `forbidden` panel state.)
- **Convention Assertions:** **C3** per-panel RBAC (`BI.OPS.VIEW`/`BI.CRM.VIEW`); **C4** the `forbidden` panel state distinct from empty/error; C6.
- **Negative / Edge:** the inverse (OPS+CRM but no FINANCE) → Finance/Working-Capital/Cash/trend panels forbidden, Inventory/CRM render.

### TC-REP-052 — BI Dashboard route guard denies user without BI.VIEW
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** BI Dashboard route (`canActivate: requirePermission('BI.VIEW')`)
- **Permission / Role:** lacks `BI.VIEW` — runs as NO-PERMISSION user
- **Steps:** as the NO-PERMISSION user, attempt to navigate to `/admin/dashboard`; also confirm the "Dashboard" nav item (gated `BI.VIEW`) is absent.
- **Expected Result:** the route guard blocks entry (redirect/forbidden); the nav item does not appear. If the composite endpoint is hit directly it returns 403, and `applyGlobalError` marks every panel `forbidden` (belt-and-braces).
- **Convention Assertions:** C3 (route + nav gate); C4 (forbidden).
- **Negative / Edge:** N/A.

### TC-REP-053 — BI Dashboard finance panel: independent per-panel endpoint
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** BI finance-summary (`/api/v1/bi/finance-summary`)
- **Permission / Role:** `BI.FINANCE.VIEW` — runs as ACCOUNTANT; denied role → 403
- **Preconditions / Seed:** finance data present.
- **Steps:** call `GET /api/v1/bi/finance-summary?companyId=…` directly (or via panel lazy-refresh); omit `from`/`to`.
- **Expected Result:** `ApiResponse<FinanceSummaryDto>` with `netProfitPeriod, revenue, opex, netProfit, tbTies, tbTotalDebit, tbTotalCredit, cash`; when `from`/`to` omitted the service defaults from = 1st-of-month, to = today. (Note: COGS/grossProfit/grossMargin intentionally absent — the income statement is the authoritative gross-profit surface.)
- **Convention Assertions:** C2 (envelope); C3 (`BI.FINANCE.VIEW`).
- **Negative / Edge:** role without `BI.FINANCE.VIEW` → 403 on this endpoint (even with `BI.VIEW`).

### TC-REP-054 — BI Dashboard inventory panel endpoint RBAC (BI.OPS.VIEW)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** BI inventory (`/api/v1/bi/inventory`)
- **Permission / Role:** `BI.OPS.VIEW` — runs as STOREKEEPER (if granted) / ORG_ADMIN; denied role → 403
- **Steps:** `GET /api/v1/bi/inventory?companyId=…`.
- **Expected Result:** `ApiResponse<InventorySummaryDto>` `{ stockValue, stockTies, stockDifference }` — stat-card only, NOT the full per-product table.
- **Convention Assertions:** C2; C3.
- **Negative / Edge:** without `BI.OPS.VIEW` → 403.

### TC-REP-055 — BI Dashboard CRM panel endpoint RBAC + branch filter (BI.CRM.VIEW)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** BI crm-summary (`/api/v1/bi/crm-summary`)
- **Permission / Role:** `BI.CRM.VIEW` — runs as SALES_MANAGER; denied role → 403
- **Variation:** with `branchId` filter
- **Steps:** `GET /api/v1/bi/crm-summary?companyId=…&branchId=…&from=…&to=…`.
- **Expected Result:** `ApiResponse<CrmSnapshotDto>` `{ pipeline, kpis, forecast }`; defaults from=1st-of-month, to=today when omitted.
- **Convention Assertions:** C2; C3; C7 (branch scoping).
- **Negative / Edge:** without `BI.CRM.VIEW` → 403.

### TC-REP-056 — BI Dashboard working-capital + trend panel endpoints RBAC (BI.FINANCE.VIEW)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** BI working-capital / revenue-trend / net-profit-trend (`/api/v1/bi/working-capital`, `/revenue-trend`, `/net-profit-trend`)
- **Permission / Role:** `BI.FINANCE.VIEW` — runs as ACCOUNTANT; denied role → 403
- **Steps:** call each of the three endpoints with `companyId`.
- **Expected Result:** working-capital → `WorkingCapitalDto {arOutstanding, arTies, arDifference, apOutstanding, apTies, apDifference}`; revenue/net-profit trends → `TrendDto {metricLabel, currency, points[]}` (12 points).
- **Convention Assertions:** C2; C3 (all three share `BI.FINANCE.VIEW`).
- **Negative / Edge:** working-capital uses AR/AP reconciliation totals (not ageing) — verify it returns non-zero totals when receivables/payables exist.

### TC-REP-057 — BI Dashboard branch & date-range filter re-fetches all panels
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** BI Dashboard (`/api/v1/bi/dashboard` — `branchId`, `from`, `to`)
- **Permission / Role:** `BI.VIEW` + all panel perms — runs as ORG_ADMIN
- **Variation:** multi-branch company; switch branch; change date range
- **Preconditions / Seed:** company with ≥ 2 branches and branch-scoped CRM/finance data.
- **Steps:**
  1. Open `/admin/dashboard`.
  2. Change **Branch** (`branchPicker` select, by `code — name`) → all panels reset to loading then re-fetch.
  3. Change **From**/**To** dates and click the refresh button (`aria-label="Refresh dashboard"`).
- **Expected Result:** changing branch calls `loadDashboard()` (panels reset to loading); the composite request includes `branchId`, `from`, `to`. Data updates per branch/range.
- **Convention Assertions:** C7 (branch scoping); C4 (loading on each panel during refetch); C6.
- **Negative / Edge:** company with no branches → branch select shows "— no branches —"; dashboard still loads company-wide.

### TC-REP-058 — BI Dashboard export to PDF / XLSX / CSV gated by BI.EXPORT
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** BI Dashboard export (`/api/v1/bi/dashboard/export`)
- **Permission / Role:** `BI.EXPORT` — runs as ORG_ADMIN; also a role with `BI.VIEW` but NOT `BI.EXPORT` → export controls hidden
- **Variation:** each ExportFormat
- **Steps:**
  1. Open the dashboard (with export permission).
  2. Choose format (PDF/Excel/CSV) in the export `<select>`; click **Download**.
- **Expected Result:** file `dashboard.<ext>` downloads; request carries `companyId`, optional `branchId/from/to`, and `format`. The export block (`@if (canExport())`) is **absent** for a non-`BI.EXPORT` role; the backend `GET /dashboard/export` returns 403 for that role.
- **Convention Assertions:** C3 (export RBAC); C8.
- **Negative / Edge:** click Download twice quickly → guarded by `exporting()` (no duplicate request).

### TC-REP-059 — BI Dashboard health strip reflects out-of-tie reconciliations
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** BI Dashboard health strip (`DashboardDto.health`)
- **Permission / Role:** `BI.VIEW` (+ panel perms) — runs as ACCOUNTANT
- **Variation:** at least one recon `ties=false`
- **Preconditions / Seed:** a books state where one of AR/AP/Cash/Stock/TB does not tie.
- **Steps:** open the dashboard against that fixture.
- **Expected Result:** the failing badge renders red (`text-bg-danger`) with prefix `[!]` and shows "(diff: <amount>)"; tying badges render green `text-bg-success` with `[OK]`.
- **Convention Assertions:** C6 (the `[OK]`/`[!]` text prefix is a non-color a11y cue); C8 (difference 2dp).
- **Negative / Edge:** empty `health` list → the strip is hidden (`@if health().length > 0`).

### TC-REP-060 — BI Dashboard panel drill-out links navigate to source screens
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** BI Dashboard drill links
- **Permission / Role:** `BI.VIEW` + relevant downstream perms — runs as ORG_ADMIN
- **Steps:** from the dashboard, click the panel drill icons: Finance → `/admin/reporting/income-statement`; Trial Balance "View TB" → `/admin/gl/trial-balance`; Cash → `/admin/cash/accounts`; AR "View Receivables" → `/admin/ar/invoices`; AP "View Payables" → `/admin/ap/supplier-bills`; Inventory → `/admin/stock/valuation`; CRM → `/admin/crm/pipeline`.
- **Expected Result:** each link navigates by **route** to the matching detail screen.
- **Convention Assertions:** C1 (links are routes, no uid surfaced); C3 (downstream routes have their own permission guards — a user lacking the target perm is blocked there).
- **Negative / Edge:** a user with `BI.VIEW` but lacking the downstream perm clicks a drill link → blocked by the target route's guard.

### TC-REP-070 — Budget Variance report (budget-vs-actual) runs and labels favourable/adverse
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Budget Variance (`/admin/budgeting/variance` · `/api/v1/budgeting/variance`)
- **Permission / Role:** `BUDGETING.REPORT.VIEW` — runs as ACCOUNTANT; also NO-PERMISSION → forbidden
- **Variation:** all account types; full year (periods 1–12)
- **Preconditions / Seed:** a fiscal year with a posted budget version AND actual GL postings.
- **Steps:**
  1. Navigate to `/admin/budgeting/variance`.
  2. Choose company by name; enter **Fiscal Year UID**; leave from=1, to=12; account type = "All account types".
  3. Click Run.
- **Test Data:** fiscalYearUid = a real fiscal-year uid; periods 1–12.
- **Expected Result:** a variance table by account showing budget, actual, variance, and a favourable/adverse label (INCOME: actual>budget = Favourable; EXPENSE: actual<budget = Favourable; ASSET/LIAB/EQUITY = neutral). Totals per account type.
- **Convention Assertions:** C3 (`BUDGETING.REPORT.VIEW`); C4; C6; C8. **C1 DEVIATION (flag):** the fiscal-year and cost-centre here are **typed UIDs** in text inputs, not a `<app-uid-picker>` — this violates C1 (uid hand-typed); record as a known UI gap for this report.
- **Negative / Edge:** NO-PERMISSION → forbidden + 403; blank Fiscal Year UID → client validation "Fiscal Year UID is required." (no request sent).

### TC-REP-071 — Budget Variance: period-range validation (1–12, from ≤ to)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Budget Variance (`/api/v1/budgeting/variance`)
- **Permission / Role:** `BUDGETING.REPORT.VIEW` — runs as ACCOUNTANT
- **Steps:** enter fiscal year uid; set from-period = 8, to-period = 3 (from > to); Run. Then try from = 0 and to = 13.
- **Expected Result:** client validation blocks: "Period range must be 1–12 and from ≤ to." No request is sent.
- **Convention Assertions:** C4 (validation feedback via AlertService).
- **Negative / Edge:** boundary values from=1,to=12 accepted; from=12,to=12 accepted (single period).

### TC-REP-072 — Budget Variance filtered by account type and cost centre
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Budget Variance (`/api/v1/budgeting/variance` — `accountType`, `costCentreValueUid`)
- **Permission / Role:** `BUDGETING.REPORT.VIEW` — runs as ACCOUNTANT
- **Variation:** accountType = EXPENSE; cost-centre filter set
- **Steps:** run with account type = "Expense" and a cost-centre uid entered.
- **Expected Result:** the variance is restricted to EXPENSE accounts (and the cost centre); favourable/adverse uses the EXPENSE rule (negative variance = Favourable). Request sends `accountType=EXPENSE` and `costCentreValueUid`.
- **Convention Assertions:** C3; verify `accountType` enum value sent is exactly `EXPENSE` (one of ASSET/LIABILITY/EQUITY/INCOME/EXPENSE).
- **Negative / Edge:** accountType = INCOME flips the favourable rule (positive variance = Favourable) — verify the label class swaps.

### TC-REP-080 — Departmental Actuals report (GL actuals by cost-centre × account)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Departmental Actuals (`/admin/budgeting/departmental-actuals` · `/api/v1/budgeting/departmental-actuals`)
- **Permission / Role:** `BUDGETING.REPORT.VIEW` — runs as ACCOUNTANT; also NO-PERMISSION → forbidden
- **Preconditions / Seed:** fiscal year with postings tagged to cost centres.
- **Steps:** navigate to the route; choose company; enter fiscal year uid; from=1/to=12; Run.
- **Expected Result:** actuals grouped by cost-centre × account (no budget join). Request hits `/departmental-actuals?companyId=&fiscalYearUid=&fromPeriodNo=&toPeriodNo=`.
- **Convention Assertions:** C3; C4; C6; C8.
- **Negative / Edge:** NO-PERMISSION → forbidden + 403; missing fiscal-year uid → validation blocks.

### TC-REP-090 — Dimension-sliced trial balance requires BOTH COSTING.VIEW and GL.VIEW
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Sliced TB (`/admin/cost-centre/report` · `/api/v1/costing/reports/sliced-trial-balance`)
- **Permission / Role:** `COSTING.VIEW` **and** `GL.VIEW` — runs as ACCOUNTANT (both); also a role with COSTING.VIEW but NOT GL.VIEW → forbidden
- **Variation:** slot = COST_CENTRE; no value filter; no rollUp
- **Preconditions / Seed:** journals tagged with cost-centre dimension values.
- **Steps:**
  1. Navigate to `/admin/cost-centre/report`.
  2. Choose company by name; choose **slot = "Cost Centre"**; leave value blank; Run.
- **Expected Result:** rows grouped by dimension value with totalDebit / totalCredit / net columns and account-type badges; **no "balanced" indicator** is shown (a slice does not net to zero — by design). Negative net rendered in `text-danger`. Footer shows total debit/credit/net.
- **Convention Assertions:** **C3** — the FE `canView()` requires BOTH `COSTING.VIEW` && `GL.VIEW`; a role missing either sets `state='forbidden'` and the backend returns 403 (`@perm.has('COSTING.VIEW') and @perm.has('GL.VIEW')`). C4; C6; C8.
- **Negative / Edge:** role with only one of the two perms → forbidden; verify the AND (not OR) semantics.

### TC-REP-091 — Sliced TB by slot variations (COST_CENTRE / DEPARTMENT / reserved DIMENSION_3)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Sliced TB (`/api/v1/costing/reports/sliced-trial-balance` — `slot`)
- **Permission / Role:** `COSTING.VIEW` + `GL.VIEW` — runs as ACCOUNTANT
- **Variation:** each `DimensionSlot`
- **Steps:** run the report with slot = COST_CENTRE, then DEPARTMENT, then DIMENSION_3.
- **Expected Result:** COST_CENTRE and DEPARTMENT (the two v1-active slots) return rows; DIMENSION_3 (reserved, not wired in v1) returns an **empty** result set, not an error.
- **Convention Assertions:** C4 (empty state distinct); enum values sent are exactly COST_CENTRE / DEPARTMENT / DIMENSION_3.
- **Negative / Edge:** DIMENSION_4 also returns empty (future reserve).

### TC-REP-092 — Sliced TB with value filter, roll-up, and period filter
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Sliced TB (`valueUid`, `rollUp`, `periodId`)
- **Permission / Role:** `COSTING.VIEW` + `GL.VIEW` — runs as ACCOUNTANT
- **Variation:** specific cost-centre value + rollUp=true + a fiscal period
- **Preconditions / Seed:** a parent cost-centre value with descendants, each carrying postings.
- **Steps:** choose slot = Cost Centre; pick a specific value; toggle **roll-up on**; pick a fiscal period; Run.
- **Expected Result:** with rollUp=true the parent's totals **include descendant values** (FR-CC-16); restricting `periodId` limits to that period. Request sends `valueUid`, `rollUp=true`, `periodId`.
- **Convention Assertions:** C1 (period & value chosen by label, not typed uid where a picker exists); C8.
- **Negative / Edge:** rollUp=false on the same parent shows only its own postings (smaller totals) — assert the difference.

### TC-REP-100 — Manufacturing WIP Reconciliation (open-WO WIP vs GL 1320)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** WIP Reconciliation (`/admin/manufacturing/wip-reconciliation` · `/api/v1/manufacturing/wip-reconciliation`)
- **Permission / Role:** `MANUFACTURING.VIEW` — runs as ORG_ADMIN/with mfg perm; also NO-PERMISSION → forbidden
- **Variation:** `ties=true` (reconciled)
- **Preconditions / Seed:** open work orders (RELEASED/IN_PROGRESS/COMPLETED) whose Σ WIP equals the WIP_INVENTORY GL account.
- **Steps:** navigate to `/admin/manufacturing/wip-reconciliation`; company auto-selects and the report auto-loads; (optionally switch company).
- **Expected Result:** the report shows computed Σ open-WO WIP vs the WIP_INVENTORY GL balance, both in base currency; a reconciled (green) indicator when `ties=true`.
- **Convention Assertions:** C2 (`ApiResponse<WipReconciliationDto>`); C3 (`MANUFACTURING.VIEW`); C4 (loading→idle, forbidden, error); C6; C8.
- **Negative / Edge:** NO-PERMISSION → forbidden + 403.

### TC-REP-101 — Manufacturing WIP Reconciliation: out-of-tie raises a finance-grade defect alert
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** WIP Reconciliation (`/api/v1/manufacturing/wip-reconciliation`)
- **Permission / Role:** `MANUFACTURING.VIEW` — runs as ORG_ADMIN
- **Variation:** `ties=false`
- **Preconditions / Seed:** a fixture where Σ WIP ≠ GL 1320.
- **Steps:** load the report against the broken fixture.
- **Expected Result:** in addition to the report, an **error AlertService toast**: "WIP Reconciliation Defect — Computed WIP (<computed>) does not match GL balance (<expected>). Finance review required."; the out-of-tie state is rendered as a finance-grade defect.
- **Convention Assertions:** C9 (read-only; no auto-plug); C6 (alert announced).
- **Negative / Edge:** N/A (this is the negative path).

### TC-REP-110 — Project WIP report (cross-project)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Project WIP (`/admin/projects/wip-report` · `/api/v1/project-costing/wip`)
- **Permission / Role:** `PROJECTS.COSTING.VIEW` — runs as ORG_ADMIN/with projects-costing perm; also NO-PERMISSION → forbidden
- **Preconditions / Seed:** projects with cost incurred and partial billing.
- **Steps:** navigate to `/admin/projects/wip-report`; choose company by name; click Load.
- **Expected Result:** a table of per-project rows (cost incurred, billed, WIP) with column totals (cost incurred / billed / WIP). `ApiResponse<List<ProjectWipRowDto>>` auto-unwrapped.
- **Convention Assertions:** C2; C3; C4 (loading/empty `isEmpty()`/error/forbidden); C6; C8.
- **Negative / Edge:** NO-PERMISSION → forbidden + 403; company with no projects → empty state.

### TC-REP-111 — Project P&L (embedded in project detail, scoped permission)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Project P&L — embedded (`/api/v1/project-costing/projects/uid/{projectUid}/pnl`), surfaced inside `/admin/projects/uid/:uid` (NOT a standalone report route)
- **Permission / Role:** `PROJECTS.COSTING.VIEW` scoped to the project (`@perm.scoped(#projectUid,'project','PROJECTS.COSTING.VIEW')`) — runs as a user scoped to that project; also a user without project scope → 403
- **Preconditions / Seed:** a project with revenue + cost postings; the acting user is scoped to that project.
- **Steps:**
  1. Navigate to the project detail by route `/admin/projects/uid/:uid` (project chosen from the project list by **name**, not by typing a uid).
  2. Trigger the **P&L** load action (`loadPnl()` button in the detail screen).
- **Expected Result:** the embedded P&L panel shows the `ProjectPnlDto` figures (revenue, cost, margin) for that project; loading → idle/error states on the panel.
- **Convention Assertions:** **C1** — the project is reached by route with the uid in the path only (the user picked it by name in the list); no raw uid shown as label text. C2 (DTO returned directly — service `getProjectPnl` does not unwrap an envelope). C3 (scoped permission — a user not scoped to the project gets 403). C4 (panel loading/error).
- **Negative / Edge:** a user with `PROJECTS.COSTING.VIEW` but NOT scoped to *this* project → 403 on the P&L call (scoped check). Note this endpoint has **no standalone route** and **no export**.

### TC-REP-120 — Cross-tenant isolation: reporting/BI cannot read another company's data
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All reporting + BI endpoints (`companyId` query param + `scopeGuard.assertCanActIn`)
- **Permission / Role:** all reporting/BI perms in tenant A; user belongs to company/branch of tenant A only
- **Variation:** request company B's `companyId`
- **Preconditions / Seed:** two companies A and B in (the same or different) orgs; user assigned only to A.
- **Steps:**
  1. As a tenant-A user, call (or manipulate the company query param to) `GET /api/v1/reports/income-statement?companyId=<B>` (repeat for balance-sheet, cash-flow, account-ledger, trial-balance, `/bi/dashboard`, `/budgeting/variance`, `/costing/reports/sliced-trial-balance`, `/manufacturing/wip-reconciliation`, `/project-costing/wip`).
- **Expected Result:** every call is **denied** (403 / scope error) — `assertCanActIn` rejects acting in company B. The UI company picker only lists tenant-A companies, so the uid for B is never selectable.
- **Convention Assertions:** **C7** multi-tenancy/scoping enforced server-side; C1 (company chosen by name from the scoped picker, never a typed cross-tenant uid); C3.
- **Negative / Edge:** also verify a user acting in a **branch they are not assigned to** (X-Branch-Uid header for an unassigned branch) is denied on the branch-scoped BI panels (dashboard/CRM `branchId`).

### TC-REP-121 — Reporting/BI nav items hidden for users lacking each permission
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Shell nav (`shell.component.ts` permission-gated menu)
- **Permission / Role:** matrix — each report's nav item gated by its permission
- **Steps:** log in as a series of users with single permissions and assert nav visibility per item:
  - `REPORT.PL.VIEW` → "Income Statement" visible; absent otherwise.
  - `REPORT.BS.VIEW` → "Balance Sheet".
  - `REPORT.CASHFLOW.VIEW` → "Cash-Flow Statement".
  - `REPORT.LEDGER.VIEW` → "Account Ledger".
  - `GL.VIEW` → "Trial Balance".
  - `BI.VIEW` → "Dashboard".
  - `COSTING.VIEW` → "Sliced Trial Balance".
  - `PROJECTS.COSTING.VIEW` → "WIP Report".
  - `BUDGETING.REPORT.VIEW` → "Budget Variance Report" + "Departmental Actuals".
  - `MANUFACTURING.VIEW` → "WIP Reconciliation".
- **Expected Result:** each nav item appears only when the user holds its gating permission; the NO-PERMISSION user sees none of them.
- **Convention Assertions:** C3 (nav gated by exact permission codes). C6.
- **Negative / Edge:** a CUSTOM role with a subset shows exactly that subset.

### TC-REP-122 — rootadmin superuser sees all reports/panels across companies
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** all reporting + BI
- **Permission / Role:** `rootadmin` (bootstrap superuser — bypasses all permission checks + cross-tenant scope)
- **Steps:** as rootadmin, open each report and the dashboard for multiple companies via the company picker.
- **Expected Result:** every screen, panel, and export is accessible for every company (no 403 anywhere); the company picker lists all companies.
- **Convention Assertions:** C3 (superuser bypass is the documented exception — do NOT use rootadmin for negative-auth cases).
- **Negative / Edge:** N/A — rootadmin is the all-allow baseline.

### TC-REP-123 — Reporting screens four-state coverage (company-load failure, empty, error, forbidden)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Income Statement / Balance Sheet / Cash-Flow (shared four-state pattern)
- **Permission / Role:** `REPORT.PL.VIEW`/`REPORT.BS.VIEW`/`REPORT.CASHFLOW.VIEW` — runs as ACCOUNTANT + NO-PERMISSION
- **Steps:** for each statement screen:
  1. **Company loading** — assert "Loading companies…" while the org/company fetch is in flight.
  2. **Company error** — force the company list to 500 → "Could not load companies."
  3. **Empty** — before Run, assert the "Select a company and period, then click Run." prompt.
  4. **Statement loading** — Run with throttling → "Building …" loading.
  5. **Statement error** — Run against a 500 → error alert.
  6. **Forbidden** — as NO-PERMISSION → the permission message + the `GET` returns 403.
- **Expected Result:** each of the six states renders distinctly on each statement screen.
- **Convention Assertions:** **C4** four-state (plus the separate company-load loading/error sub-states); C6.
- **Negative / Edge:** a 403 during Run maps to `forbidden`; any other status maps to `error`.

### TC-REP-124 — Note: budget-report export permission is seeded but not implemented
- **Type:** Manual (documentation/contract check)
- **Priority:** P3
- **Module / Submodule:** Budget reports (`BudgetReportController`)
- **Permission / Role:** `BUDGETING.REPORT.EXPORT` (V70 seed)
- **Steps:** inspect `BudgetReportController` and the budget-variance / departmental-actuals FE components.
- **Expected Result (documented gap):** **No export endpoint exists** under `/api/v1/budgeting` and **no export button** is present in the variance/departmental components, even though `BUDGETING.REPORT.EXPORT` is seeded. Record as a backend-permission-without-implementation gap — do NOT write an automated export test for budget reports until the endpoint exists.
- **Convention Assertions:** N/A (gap-flagging case).
- **Negative / Edge:** N/A.
