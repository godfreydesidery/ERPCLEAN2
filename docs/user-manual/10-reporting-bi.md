# Reporting and Business Intelligence

**What is the Reporting and BI module?**
Reporting and Business Intelligence (BI) is the read-only analytical layer of the system. It does not create, change, or post anything — it reads what the financial and operational modules have already recorded and presents the results in standard formats that management and external stakeholders (auditors, banks, tax authorities) can read and act on. The four financial statements summarise the company's performance and position in internationally recognised forms; the account ledger lets you drill into the individual transactions behind any figure; the BI dashboard composes key indicators from across all modules into a single at-a-glance view. Because all reports are computed on demand from the live General Ledger, a report run at any moment reflects the current state of the books. Nothing is stored or posted when you run a report (ADR-0018, ADR-0037).

This chapter describes the financial statements, the GL account-ledger drill-down, and the analytics dashboard. All reports are read-only and computed on demand — nothing is stored or posted when you run a report.

---

## Financial Statement Reports

**What are financial statements, and why do companies produce them?**
Financial statements are standardised summaries of a company's financial activity and position. They are the language that businesses, investors, lenders, and regulators use to assess financial health. Every trading company is legally required to produce them at least annually. In this system they are generated directly from the General Ledger and carry a reconciliation bar that confirms the figures tie back to the underlying journal entries — so there is no separate spreadsheet to maintain and no risk of a mismatch between the books and the reports.

The four financial statements are available from the **Accounting** navigation group. Each report requires the relevant permission and a company and period selection before it can be run.

**Common controls on every statement screen:**

- **Company selector** — if your organisation has more than one company, choose which company to report on by name.
- **Period inputs** — date fields specifying the reporting period.
- **Run button** — computes and displays the statement.
- **Export buttons** (PDF, Excel, CSV) — download the statement in the chosen format. Requires the additional `REPORT.EXPORT` permission. The buttons are hidden if you do not hold that permission.
- **Comparative period** — most statements accept an optional comparative period or date to populate a second column.
- **Reconciliation indicator** — a green **"Reconciled"** bar confirms the computed figures tie back to the underlying GL movement. A red **data-integrity alarm** means the figures do not agree and the books require investigation; the report is shown but no automatic correction is made.

---

### Profit & Loss (Income Statement)

**What is the Profit and Loss statement, and what does it tell you?**
The Profit and Loss statement (also called the Income Statement) shows how much revenue the company earned and how much it spent over a period of time, arriving at a net profit or loss. Revenue is income from sales and services; Cost of Sales is the direct cost of what was sold (purchases, materials, production); Operating Expenses are the overhead costs of running the business (salaries, rent, utilities). Gross Profit is Revenue minus Cost of Sales — a measure of trading margin. Net Profit is what remains after all operating expenses. The P&L answers the question: "Did the business make money this period, and where did the money come from and go?" It is the report most used for management decisions, bank covenants, and tax assessments. The comparative column lets you benchmark the current period against a prior period (same quarter last year, for example) to spot trends.

Navigate to **Accounting › Income Statement** (`/admin/reporting/income-statement`). Permission required: `REPORT.PL.VIEW`.

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

**Example — Run a comparative P&L for two quarters and export to Excel:**

Chief accountant Rehema Mwangi needs to compare Q1 2026 performance against Q1 2025 for the board report.

1. Navigate to **Accounting › Income Statement** (`/admin/reporting/income-statement`).
2. Company: `Kijenge Trading Ltd`.
3. Period from: `2026-01-01`; Period to: `2026-03-31`.
4. Comparative from: `2025-01-01`; Comparative to: `2025-03-31`.
5. Click **Run**.

The statement loads. The green **Reconciled** bar confirms net profit ties to the INCOME − EXPENSE GL movement. Results:

| Section | Q1 2026 | Q1 2025 |
|---|---|---|
| Revenue | TZS 48,250,000 | TZS 39,100,000 |
| Cost of Sales | TZS 29,340,000 | TZS 24,600,000 |
| Gross Profit | TZS 18,910,000 | TZS 14,500,000 |
| Operating Expenses | TZS 9,720,000 | TZS 8,850,000 |
| Net Profit | TZS 9,190,000 | TZS 5,650,000 |

Rehema clicks **Excel** in the export toolbar. The file `income-statement_2026-01-01_2026-03-31.xlsx` downloads with both columns. She forwards it to the board.

To drill into "Sales Revenue", she clicks the account name link — the Account Ledger opens pre-filled with that account and the Q1 2026 period, showing every posted journal line and a running balance.

---

### Balance Sheet

**What is the Balance Sheet, and what does it tell you?**
The Balance Sheet (also called the Statement of Financial Position) shows what the company owns and what it owes at a single point in time. **Assets** are what the company owns — cash, trade receivables, stock, fixed assets, and other resources. **Liabilities** are what the company owes — supplier payables, loans, tax obligations. **Equity** is the residual interest of the owners — the difference between assets and liabilities, representing the net worth of the business. A correctly prepared balance sheet always satisfies the fundamental accounting equation: Assets = Liabilities + Equity. If this equation does not balance, something has been mis-posted. The Balance Sheet answers the question: "What is the company worth, and how solvent is it?" It is used by banks to assess creditworthiness, by investors to evaluate the business, and by management to monitor liquidity. The comparative "as at" date lets you compare financial position at two year-ends side by side.

Navigate to **Accounting › Balance Sheet** (`/admin/reporting/balance-sheet`). Permission required: `REPORT.BS.VIEW`.

1. Select the company by name.
2. Set the **As-at date**.
3. Optionally set a **Compare as-at** date to add a prior-date column.
4. Click **Run**.

The statement shows sections for Current Assets, Non-Current Assets, Current Liabilities, Non-Current Liabilities, and Equity, each with detail lines, subtotals, and three totals: **Total Assets**, **Total Liabilities**, **Total Equity**. A balanced set of books shows **Total Assets = Total Liabilities + Total Equity** (green reconciliation bar).

**Drill-through:** click any real account name link to open the Account Ledger for that account as at the selected date.

**Export:** file is named `balance-sheet_<asAt>.<ext>`.

---

**Example — Run a comparative balance sheet at year-end:**

Rehema Mwangi needs the balance sheet as at 30 June 2026 compared with 30 June 2025.

1. Navigate to **Accounting › Balance Sheet** (`/admin/reporting/balance-sheet`).
2. Company: `Kijenge Trading Ltd`; As-at date: `2026-06-30`; Compare as-at: `2025-06-30`.
3. Click **Run**.

The green Reconciled bar appears ("total assets == total liabilities + total equity"). Rehema spots that "Trade Receivables" has grown from TZS 12.4M to TZS 19.7M year-on-year. She clicks the "Trade Receivables" account name to open its ledger for the full fiscal year and reviews each transaction. She then exports to PDF for the audit file.

---

### Cash-Flow Statement

**What is the Cash-Flow Statement, and what does it tell you?**
The Cash-Flow Statement shows how cash moved into and out of the business over a period, organised into three categories. **Operating activities** are cash flows from the company's main trading activities — collecting from customers, paying suppliers, paying wages. **Investing activities** are cash flows from buying or selling long-term assets — purchasing a vehicle or machinery, receiving proceeds from selling an asset. **Financing activities** are cash flows from raising or repaying capital — new loans drawn, loan repayments, equity injections. The statement reconciles the opening and closing cash balance, confirming that the movement in the company's bank accounts is fully explained. The Cash-Flow Statement answers the question: "Where did the cash come from, and where did it go?" It is particularly important for businesses that are profitable on paper but cash-constrained in practice — a common situation when customers pay late or large capital purchases are made. The system uses the **indirect method** (starting from net profit and adjusting for non-cash items), which is the most common format for external reporting.

Navigate to **Accounting › Cash-Flow Statement** (`/admin/reporting/cash-flow`). Permission required: `REPORT.CASHFLOW.VIEW`.

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

**Example — Cash-flow analysis for H1 2026:**

1. Navigate to **Accounting › Cash-Flow Statement** (`/admin/reporting/cash-flow`).
2. Company: `Kijenge Trading Ltd`; Period from: `2026-01-01`; Period to: `2026-06-30`.
3. Click **Run**.

Results show Opening Cash: TZS 6,800,000; Operating inflow: TZS 11,250,000; Investing outflow: TZS −4,200,000 (purchase of delivery van); Financing outflow: TZS −1,500,000 (loan repayment); Net Change: TZS 5,550,000; Closing Cash: TZS 12,350,000. The green Reconciled bar confirms the net change ties to the actual movement in the bank account GL balances.

---

### Account-Ledger Drill-Down

**What is the Account Ledger, and when do you use it?**
The Account Ledger shows every individual journal line posted to a single GL account within a date range, with a running balance. It is the most granular view available in the system: while the financial statements show totals and subtotals, the ledger shows the individual transactions behind each total. It is the primary tool for investigating a balance — for example, if Trade Receivables on the balance sheet is higher than expected, you open the ledger for that account to see every invoice and receipt that has been posted. The ledger is also the standard tool for preparing a bank reconciliation (compare the bank account ledger to the bank statement) and for answering auditor queries about specific transactions. The opening balance is the account's position before the chosen date range, so every line in the report can be traced back to a source document.

Navigate to **Accounting › Account Ledger** (`/admin/reporting/account-ledger`). Permission required: `REPORT.LEDGER.VIEW`.

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

**Example — Investigate the bank account movements for April 2026:**

1. Navigate to **Accounting › Account Ledger** (`/admin/reporting/account-ledger`).
2. Company: `Kijenge Trading Ltd`.
3. Account picker: type `Bank` — select **Bank — Main Current (1100)**.
4. Period from: `2026-04-01`; Period to: `2026-04-30`.
5. Click **Run**.

Opening balance: TZS 12,350,000. The ledger shows 28 lines — 15 customer receipts credited and 13 payments debited, with a closing balance of TZS 14,890,000. The paginator is hidden (fewer than 50 lines). Rehema exports to CSV for the bank reconciliation working paper.

---

### Trial Balance

The Trial Balance is covered fully in the Finance chapter (Accounting › Trial Balance, `/admin/gl/trial-balance`). It can also be reached from the Accounting navigation group. Permission required: `GL.VIEW`. See the General Ledger section for full usage.

---

## Business Intelligence Dashboard

**What is the BI Dashboard, and who uses it?**
The Business Intelligence Dashboard is a single-screen summary that composes key performance indicators (KPIs) from Finance, Operations, and CRM into one view. Rather than opening the income statement, then the AR list, then the stock valuation report separately, a finance director or general manager can open the dashboard and see the essential health indicators at a glance: is the trial balance balanced? Are the AR and AP sub-ledgers in agreement with the GL? How much cash is in the accounts? What is the current pipeline forecast? Each panel has a health badge (green `[OK]` / red `[!]`) that instantly signals whether the underlying sub-ledger ties to the GL control account — a critical integrity check the finance team would otherwise have to perform manually. Drill-through links let the reader navigate directly to the relevant detail screen with a single click. The dashboard is permission-gated at the panel level: a user with only operations permissions sees the stock panel but not the finance panel, and gets a calm "no permission" message for the panels they cannot access (ADR-0037).

Navigate to **Analytics › Dashboard** (`/admin/dashboard`). Permission required: `BI.VIEW`.

The dashboard is a composite view of key performance indicators drawn from Finance, Operations, and CRM data. Each panel loads independently and has its own permission. If you hold `BI.VIEW` but lack a panel-specific permission, that panel shows a calm "no permission" message rather than blocking the whole page.

**Filters at the top of the page:**

- **Company** — the active company (determined by your login context).
- **Branch** — optionally filter data to a specific branch (chosen by code — name).
- **From / To dates** — the reporting date range (defaults to the current month). Change dates and click the **Refresh** button to re-fetch all panels.

---

**Example — Read the dashboard KPIs and drill through to source screens:**

Finance director Gideon Moshi logs in, navigates to **Analytics › Dashboard** (`/admin/dashboard`). The company `Kijenge Trading Ltd` and branch `DSM Main` auto-select; dates default to the current month (2026-06-01 to 2026-06-14).

1. **Health strip** — all five badges (AR, AP, Cash, Stock, TB) show green `[OK]`. No reconciliation issues.

2. **Finance panel** — Revenue: TZS 9,850,000; OpEx: TZS 4,200,000; Net Profit: TZS 3,480,000. Trial Balance status: Balanced. Gideon clicks **Income Statement** in the Finance panel — this drills through to `/admin/reporting/income-statement` where he can run a full P&L.

3. **Cash Position panel** — Cash balance across all accounts: TZS 14,890,000. He clicks **Cash Accounts** to open the cash & bank accounts list.

4. **Working Capital panel** — Outstanding AR: TZS 19,700,000 (AR sub-ledger reconciles to GL). Outstanding AP: TZS 6,450,000. He clicks **View Receivables** to drill into the AR invoices list.

5. **Inventory panel** — Total stock value: TZS 38,250,000 (stock sub-ledger ties to GL inventory account). Clicking **Inventory** opens the stock valuation screen.

6. **CRM pipeline panel** — 15 open deals across five stages; Win Rate: 62%; Weighted Forecast for the period: TZS 29,340,000. He clicks **CRM** to open the pipeline dashboard.

7. Gideon changes the **Branch** to `Arusha Branch` and clicks **Refresh**. All panels re-fetch and show Arusha-scoped figures.

8. He selects format **Excel** in the export dropdown and clicks **Download**. File `dashboard.xlsx` downloads with the currently visible panel data. (Requires `BI.EXPORT`.)

---

### KPI Panels

**What are KPI panels?**
Each KPI panel on the dashboard is a self-contained summary of one operational or financial domain, sourced from the module that owns that data. The panels display figures that have already been computed by the underlying modules (the AR reconciliation query, the stock valuation query, the CRM pipeline query, etc.); the dashboard simply composes them into one screen. A health badge (`[OK]` or `[!]`) accompanies any panel whose data has a GL tie-out — it tells you at a glance whether the sub-ledger agrees with the General Ledger. A red badge is a prompt for the finance team to investigate before closing the period.

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

The following specialised reports sit under the **Budgeting** and **Costing** navigation groups but are described here because they are reporting outputs, not data-entry screens.

### Budget Variance Report

**What is the Budget Variance Report, and why is it produced?**
A budget variance report compares what the business planned to spend (or earn) against what actually happened. A variance is the difference: if you budgeted TZS 3,200,000 for fuel but spent TZS 3,850,000, the variance is TZS 650,000 **adverse** (worse than plan). For income accounts, spending more than budgeted is **favourable** (you earned more than expected). This report is the primary tool for **management by exception** — the finance team and department heads review it monthly to identify lines that have gone significantly off-plan and investigate why. It drives conversations about cost control, re-forecasting, and budget reallocation. For the report to show non-zero budget figures, at least one budget version covering the selected fiscal year and scope must have been **approved** (see Part 2 — Budgeting, in the HR, Budgeting, and Platform chapter).

Navigate to **Budgeting › Budget Variance Report** (`/admin/budgeting/variance`). Permission required: `BUDGETING.REPORT.VIEW`.

The report compares GL actuals against an approved budget version.

1. Select the company by name.
2. Enter the **Fiscal Year UID** (available from the budget detail screen).
3. Set the **Period range** (1–12; from must be ≤ to).
4. Optionally filter by **Account Type** (Income, Expense, Asset, Liability, Equity) and enter a cost-centre value UID.
5. Click **Run**.

The report shows account-level rows with budget amount, actual amount, variance, and a **Favourable** or **Adverse** label. For income accounts, actual > budget is favourable. For expense accounts, actual < budget is favourable.

---

**Example — Run a budget variance report for the first half of the fiscal year:**

Management accountant Yasmin Juma navigates to **Budgeting › Budget Variance Report** (`/admin/budgeting/variance`).

1. Company: `Kijenge Trading Ltd`.
2. Fiscal Year UID: copied from the approved budget at **Budgeting › Budgets**.
3. Period from: `1`; Period to: `6` (January through June).
4. Account Type: `Expense` (to focus the board on cost discipline).
5. Click **Run**.

Results show that "Fuel & Transport" (actual TZS 3,850,000 vs budget TZS 3,200,000) is marked **Adverse** by TZS 650,000, while "Office Supplies" (actual TZS 480,000 vs budget TZS 600,000) is **Favourable** by TZS 120,000. Yasmin notes the fuel over-run for discussion in the monthly management meeting.

---

### Departmental Actuals Report

**What is the Departmental Actuals Report?**
The Departmental Actuals Report shows real GL postings broken down by cost centre and account, without any budget comparison. It answers the question: "How much did each department actually spend on each expense type?" It is useful when a department manager wants to understand their spending in detail, or when the finance team needs to review allocations across departments without the distraction of a budget column. Cost centres are assigned to journal entries when transactions are posted; entries posted without a cost-centre tag appear as **Unallocated**.

Navigate to **Budgeting › Departmental Actuals** (`/admin/budgeting/departmental-actuals`). Permission required: `BUDGETING.REPORT.VIEW`.

Shows actual GL postings broken down by cost centre and account for the chosen fiscal year and period range. The inputs are the same as the variance report. This report has no budget comparison — it shows actuals only, useful for analysing spending by department or cost centre.

### Dimension-Sliced Trial Balance

Navigate to **Costing › Sliced Trial Balance** (`/admin/cost-centre/report`). Requires both `COSTING.VIEW` and `GL.VIEW`.

See the General Ledger section (Cost-Centre Dimensions) in the Finance chapter for full usage instructions.
