# Reporting and Business Intelligence

This chapter describes the financial statements, the GL account-ledger drill-down, and the analytics dashboard. All reports are read-only and computed on demand — nothing is stored or posted when you run a report.

---

## Financial Statement Reports

The four financial statements are available from the **Reporting** navigation group. Each report requires the relevant permission and a company and period selection before it can be run.

**Common controls on every statement screen:**

- **Company selector** — if your organisation has more than one company, choose which company to report on by name.
- **Period inputs** — date fields specifying the reporting period.
- **Run button** — computes and displays the statement.
- **Export buttons** (PDF, Excel, CSV) — download the statement in the chosen format. Requires the additional `REPORT.EXPORT` permission. The buttons are hidden if you do not hold that permission.
- **Comparative period** — most statements accept an optional comparative period or date to populate a second column.
- **Reconciliation indicator** — a green **"Reconciled"** bar confirms the computed figures tie back to the underlying GL movement. A red **data-integrity alarm** means the figures do not agree and the books require investigation; the report is shown but no automatic correction is made.

---

### Profit & Loss (Income Statement)

Navigate to **Reporting > Income Statement** (`/admin/reporting/income-statement`). Permission required: `REPORT.PL.VIEW`.

1. Select the company by name.
2. Set **Period from** and **Period to** (date inputs).
3. Optionally set a **Comparative from** and **Comparative to** to add a prior-period column.
4. Click **Run**.

The statement shows:

- Revenue lines grouped under **REVENUE**.
- Cost of sales lines under **COST\_OF\_SALES**.
- Operating expense lines under **OPERATING\_EXPENSES**.
- Section subtotals.
- A footer with **Gross Profit** and **Net Profit** rows, each carrying the current period amount and — if a comparative was set — the prior-period amount.

**Drill-through to the account ledger:** any account name shown as a link in the detail rows can be clicked to open the Account Ledger pre-filtered to that account and period.

**Export:** after running the statement, click **PDF**, **Excel**, or **CSV** in the export toolbar. The downloaded file is named `income-statement_<from>_<to>.<ext>`.

---

### Balance Sheet

Navigate to **Reporting > Balance Sheet** (`/admin/reporting/balance-sheet`). Permission required: `REPORT.BS.VIEW`.

1. Select the company by name.
2. Set the **As-at date**.
3. Optionally set a **Compare as-at** date to add a prior-date column.
4. Click **Run**.

The statement shows sections for Current Assets, Non-Current Assets, Current Liabilities, Non-Current Liabilities, and Equity, each with detail lines, subtotals, and three totals: **Total Assets**, **Total Liabilities**, **Total Equity**. A balanced set of books shows **Total Assets = Total Liabilities + Total Equity** (green reconciliation bar).

**Drill-through:** click any real account name link to open the Account Ledger for that account as at the selected date.

**Export:** file is named `balance-sheet_<asAt>.<ext>`.

---

### Cash-Flow Statement

Navigate to **Reporting > Cash-Flow Statement** (`/admin/reporting/cash-flow`). Permission required: `REPORT.CASHFLOW.VIEW`.

1. Select the company by name.
2. Set **Period from** and **Period to**.
3. Optionally set a comparative period.
4. Click **Run**.

The indirect-method statement shows movements in three sections:

- **OPERATING** — cash generated from or used in trading activities.
- **INVESTING** — cash spent on or received from capital assets.
- **FINANCING** — cash from borrowings, equity, or repayments.

The footer shows **Opening Cash**, **Net Change in Cash**, and **Closing Cash**. A reconciled bar confirms the net change matches the change in cash-equivalent GL account balances.

**Export:** file is named `cash-flow_<from>_<to>.<ext>`.

---

### Account-Ledger Drill-Down

Navigate to **Reporting > Account Ledger** (`/admin/reporting/account-ledger`). Permission required: `REPORT.LEDGER.VIEW`.

The account ledger shows every posted journal line for a single GL account within a date range, with a running balance.

1. Select the company by name.
2. In the **account picker**, start typing an account name or code and select from the suggestions. Accounts are chosen by name; no uid is typed.
3. Set **Period from** and **Period to**.
4. Click **Run**.

The report shows:

- An **opening balance** (the account's balance as at the day before the from date).
- Every journal line in date order: batch number, posting date, description, debit amount, credit amount, and running balance. Negative running balances are shown in red.
- A **closing balance** (the account's balance at the end of the to date).

**Pagination:** if the account has more than 50 lines in the period, the shared paginator appears at the bottom. Use FIRST, PREVIOUS, page numbers, NEXT, and LAST to navigate.

**Export:** the export is bounded at 10,000 rows per download. For very busy accounts spanning long periods, narrow the date range and export in segments. File is named `account-ledger_<accountCode>_<from>_<to>.<ext>`. Export requires `REPORT.EXPORT`.

---

### Trial Balance

The Trial Balance is covered fully in the Finance chapter (Accounting > Trial Balance, `/admin/gl/trial-balance`). It can also be reached from the Reporting navigation group. Permission required: `GL.VIEW`. See the General Ledger section for full usage.

---

## Business Intelligence Dashboard

Navigate to **Dashboard** (`/admin/dashboard`). Permission required: `BI.VIEW`.

The dashboard is a composite view of key performance indicators drawn from Finance, Operations, and CRM data. Each panel loads independently and has its own permission. If you hold `BI.VIEW` but lack a panel-specific permission, that panel shows a calm "no permission" message rather than blocking the whole page.

**Filters at the top of the page:**

- **Company** — the active company (determined by your login context).
- **Branch** — optionally filter data to a specific branch (chosen by code — name).
- **From / To dates** — the reporting date range (defaults to the current month). Change dates and click the **Refresh** button to re-fetch all panels.

---

### KPI Panels

**Health strip** — a row of colour-coded badges (AR, AP, Cash, Stock, Trial Balance) that show whether each sub-ledger reconciles with the GL control account. A green badge with `[OK]` means the sub-ledger ties; a red badge with `[!]` means there is a discrepancy. These badges provide a quick finance-health summary.

**Finance panel (requires `BI.FINANCE.VIEW`):**

- Revenue, Operating Expenses, and Net Profit for the selected period.
- Trial Balance status — shows whether total debits equal total credits.
- Click **Income Statement** to drill into the P&L report.
- Click **Trial Balance** to open the GL trial balance.

**Cash Position panel (requires `BI.FINANCE.VIEW`):**

- Summary cash balance across cash and bank accounts.
- Click **Cash Accounts** to open the Cash & Bank accounts list.

**Working Capital panel (requires `BI.FINANCE.VIEW`):**

- Outstanding AR balance and sub-ledger/GL reconciliation status.
- Outstanding AP balance and sub-ledger/GL reconciliation status.
- Click **View Receivables** or **View Payables** to drill into AR/AP lists.

**Inventory panel (requires `BI.OPS.VIEW`):**

- Total stock value and a flag showing whether the stock sub-ledger ties to the GL inventory account.
- Click **Inventory** to open the stock valuation screen.

**CRM pipeline panel (requires `BI.CRM.VIEW`):**

- Pipeline bar chart by stage.
- Win-rate KPI and revenue forecast for the period.
- Click **CRM** to open the sales pipeline.

**Revenue trend and Net Profit trend (requires `BI.FINANCE.VIEW`):**

- Bar charts showing the last 12 periods of revenue and net profit. Each bar represents one fiscal period.

---

### Drill-Through

Each panel contains one or more links that navigate directly to the relevant detail screen. Clicking a drill link takes you to the live module (AR, AP, GL, Inventory, CRM) with your current company and branch context preserved.

The target screen has its own permission guard. If you do not hold the necessary permission for the target screen, you will be redirected to an access-denied page.

---

### Exporting the Dashboard

Requires `BI.EXPORT`. A export toolbar appears at the top of the page.

1. Choose a format from the dropdown: **PDF**, **Excel**, or **CSV**.
2. Click **Download**.

The file is named `dashboard.<ext>` and includes the currently visible panel data for the selected company, branch, and date range.

---

## Analytical Reports

The following specialised reports sit under the **Budgeting** and **Cost Centre** navigation groups but are described here because they are reporting outputs, not data-entry screens.

### Budget Variance Report

Navigate to **Budgeting > Budget Variance** (`/admin/budgeting/variance`). Permission required: `BUDGETING.REPORT.VIEW`.

The report compares GL actuals against an approved budget version.

1. Select the company by name.
2. Enter the **Fiscal Year UID** (available from the budget detail screen).
3. Set the **Period range** (1–12; from must be ≤ to).
4. Optionally filter by **Account Type** (Income, Expense, Asset, Liability, Equity) and enter a cost-centre value UID.
5. Click **Run**.

The report shows account-level rows with budget amount, actual amount, variance, and a **Favourable** or **Adverse** label. For income accounts, actual > budget is favourable. For expense accounts, actual < budget is favourable.

### Departmental Actuals Report

Navigate to **Budgeting > Departmental Actuals** (`/admin/budgeting/departmental-actuals`). Permission required: `BUDGETING.REPORT.VIEW`.

Shows actual GL postings broken down by cost centre and account for the chosen fiscal year and period range. The inputs are the same as the variance report. This report has no budget comparison — it shows actuals only, useful for analysing spending by department or cost centre.

### Dimension-Sliced Trial Balance

Navigate to **Accounting > Cost Centre > Report** (`/admin/cost-centre/report`). Requires both `COSTING.VIEW` and `GL.VIEW`.

See the General Ledger section (Cost-Centre Dimensions) in the Finance chapter for full usage instructions.
