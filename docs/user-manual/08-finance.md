# Finance & Accounting

This chapter covers every module available under the **Accounting** navigation group: General Ledger, Accounts Receivable, Accounts Payable, Cash & Bank, Tax, and Foreign Exchange (FX). The chapter is written for finance staff — accountants, AP/AR clerks, and treasury officers — who use the system day-to-day.

---

## General Ledger

### Chart of Accounts

The Chart of Accounts (CoA) is the master list of all GL accounts for your company. Navigate to **Accounting > Chart of Accounts** (`/admin/gl/accounts`).

The table shows:

| Column | Meaning |
|---|---|
| Code | Unique account code assigned at creation |
| Name | Human-readable account name |
| Type | One of: **ASSET, LIABILITY, EQUITY, INCOME, EXPENSE** |
| Normal Balance | Derived from type — ASSET and EXPENSE accounts carry a **DEBIT** normal balance; LIABILITY, EQUITY, and INCOME accounts carry a **CREDIT** normal balance. Not user-editable. |
| Status | ACTIVE or INACTIVE |

**To create an account** (requires permission `GL.MANAGE`):

1. Click **Add account**.
2. Enter a unique account code and account name.
3. Choose the account type. The system derives the normal balance automatically.
4. Click **Save**. The new account is immediately available for journal posting and GL config mapping.

**To edit an account** (requires `GL.MANAGE`):

1. Click the edit action on the account row.
2. Update the name or type as needed.
3. Save. The normal balance is recalculated if the type changes.

**To deactivate an account** (requires `GL.MANAGE`):

1. Click **Deactivate** on the row.
2. The account becomes inactive and disappears from all posting pickers.
3. An inactive account can be reactivated by editing it and setting it back to active.

> **Note:** Deactivation is soft — the account record is never deleted. No posting can be made to an inactive account (business rule BR-GL-04).

---

### Posting a Manual Journal

Manual journal entries let you record corrections, accruals, and adjustments directly to the GL. Navigate to **Accounting > Journals** (`/admin/gl/journals`) and click **Post journal** (`/admin/gl/journals/post`).

**Requirements before posting (requires permission `GL.POST`):**

- At least two active accounts must exist.
- The posting date must fall inside an **OPEN** fiscal period.
- Total debits must equal total credits (the entry must be balanced — business rule BR-GL-01).

**Steps:**

1. Set the **Posting date** (defaults to today). Verify it falls within an open period.
2. Enter a **Description** summarising the purpose of the entry.
3. Each line requires exactly one of a debit or credit amount (not both — business rule BR-GL-08).
   - Use the account dropdown on each line to select an account **by name or code**. Only active accounts are listed.
   - Enter the debit or credit amount for that line.
4. The form shows running **Debits**, **Credits**, and **Difference** totals. The **Post** button remains disabled until the difference is exactly zero.
5. Click **Post**. A success message shows the generated batch number (`JB-####`). You are redirected to the journal detail page.

**Adding and removing lines:**

- Click **Add line** to insert another line.
- Click the remove icon on a line to delete it. The minimum is two lines.

**Validation errors surfaced by the server:**

- Unbalanced entry (BR-GL-01) — the amounts do not sum to zero.
- Inactive account (BR-GL-04) — choose an active account.
- Wrong company account (BR-GL-05) — the account belongs to a different company.
- Closed period (BR-GL-03) — the posting date is in a closed or missing fiscal period.

---

### Reversing a Manual Journal

Corrections to a posted journal are always made by **reversal** — a new entry with every line's debit and credit swapped. The ledger is append-only; the original entry is never modified.

**To reverse a journal (requires `GL.POST`):**

1. Open the journal detail from **Accounting > Journals**.
2. If the entry has `Source Type = MANUAL` and is not itself a reversal, the **Reverse** button is visible.
3. Click **Reverse**. A new journal is created immediately (using today as the reversal date) with all amounts swapped. The reversal entry links back to the original via its `Reversal Of` field.

> System-posted entries (source types such as SALES, OPENING\_BALANCE, YEAR\_END\_CLOSE) cannot be reversed here. Correct those through their originating module.

---

### Fiscal Periods (Open/Close)

The fiscal calendar determines which dates are available for posting. Navigate to **Accounting > Fiscal Periods** (`/admin/gl/periods`).

The screen shows two panels:

- **Fiscal Years** — each year with its code and current status (OPEN or CLOSED).
- **Fiscal Periods** — the twelve monthly periods within the selected year, each showing period number, date range, and status.

**Opening a new fiscal year (requires `GL.MANAGE`):**

1. Click **Open fiscal year**.
2. Enter a unique year code (e.g. `FY2027`), the start month (1 = January), and the calendar year.
3. Submit. Twelve monthly periods are created, all in OPEN status.

**Closing a fiscal period (requires `GL.PERIOD.CLOSE`):**

1. On a period row, click **Close**.
2. The period status changes to CLOSED. No further journal postings can be made into a closed period.

**Reopening a period (requires `GL.PERIOD.CLOSE`):**

1. On a CLOSED period row, click **Reopen**.
2. The period returns to OPEN and accepts journal postings again.

> Closing a period is reversible. Closing a fiscal **year** is a separate, more permanent action (see Year-End Close below).

---

### Trial Balance

The Trial Balance report summarises every GL account's total debits and credits. Navigate to **Accounting > Trial Balance** (`/admin/gl/trial-balance`).

- Select your company (if multi-company).
- Optionally select a specific **fiscal period** to view only that period's movements.
- The table groups accounts by type in canonical order (ASSET, LIABILITY, EQUITY, INCOME, EXPENSE) and shows each account's code, name, total debit, total credit, and net balance.
- The footer shows total debits, total credits, and a **Balanced** indicator. A balanced set of books shows equal debits and credits.

Permission required: `GL.VIEW`.

---

### GL Posting-Account Config

The GL Config maps system roles (e.g. "accounts receivable control account") to specific accounts in your CoA. Navigate to **Accounting > GL Config** (`/admin/gl/config`).

Permission required: `GL.MANAGE`.

The table shows each configuration key and the currently mapped account. The keys relevant to the core modules include:

- `ACCOUNTS_RECEIVABLE` — the AR control account
- `SALES_REVENUE` — the revenue account for sales auto-posting
- `VAT_PAYABLE` — the output VAT control account
- `CASH` — the default cash posting account
- `RETAINED_EARNINGS` — required for the year-end close

**To set or change a posting account:**

1. Click **Set** on the key row.
2. Pick the account by name from the account picker. Only active accounts are listed.
3. Save. The mapping takes effect immediately.

> All four sales keys (`ACCOUNTS_RECEIVABLE`, `SALES_REVENUE`, `VAT_PAYABLE`, `CASH`) must be configured before sales invoices can be auto-posted to the GL.

---

### Cost-Centre Dimensions

Dimension types (Cost Centre, Department) allow you to tag journal lines for management reporting. Navigate to **Accounting > Cost Centre > Dimensions** (`/admin/cost-centre/dimensions`).

**Dimension types** are pre-seeded per company (Cost Centre and Department are built-in). You cannot create or delete dimension types; you can only toggle whether they are **mandatory** on manual journal entries. Navigate to **Accounting > Cost Centre > Values** (`/admin/cost-centre/values`) to manage the actual dimension values.

**To create a dimension value (requires `COSTING.MANAGE`):**

1. Select the dimension type from the type picker.
2. Click **Add value**.
3. Enter a unique code and name. Optionally select a parent value to build a hierarchy.
4. Save.

**Mandatory enforcement:** if a dimension is set to mandatory, every manually posted journal line must include that dimension slot. System-posted entries (sales, year-end, etc.) are exempt.

**Viewing the dimension-sliced trial balance:** Navigate to **Accounting > Cost Centre > Report** (`/admin/cost-centre/report`). Requires both `COSTING.VIEW` and `GL.VIEW`. Select a slot (Cost Centre or Department), optionally filter to a specific value, toggle **Roll up** to include descendants, and click **Run**.

---

### Year-End Close

The year-end close posts a closing journal that zeros all income and expense accounts and transfers the net profit (or loss) to the **Retained Earnings** account. Navigate to **Accounting > Year-End Close** (`/admin/gl/year-end`).

Permission required: `GL.YEAR.CLOSE`.

**Prerequisites:**

- The `RETAINED_EARNINGS` GL config key must be mapped to an active EQUITY account.
- All prior fiscal years must already be CLOSED (you cannot close year N if year N-1 is still open — business rule BR-CLOSE-04).
- The year must have at least one fiscal period.

**To close a fiscal year:**

1. On the year row showing OPEN, click **Close**.
2. Review the confirmation panel, which describes the retained-earnings posting that will be made.
3. Confirm. The system:
   - Posts a `YEAR_END_CLOSE` journal zeroing every INCOME and EXPENSE account.
   - Credits (net profit) or debits (net loss) the Retained Earnings account.
   - Closes all periods within the year.
   - Sets the year status to CLOSED.
4. A success message appears referencing the closing journal number.

**To reopen a fiscal year (requires `GL.YEAR.CLOSE`):**

Only the most-recently-closed year may be reopened. Click **Reopen** on the CLOSED year row. The system reverses the closing journal (as a new append-only entry) and reopens all periods.

---

## Accounts Receivable

AR tracks amounts owed to your company by customers. Open items (invoices) are created automatically when a sales invoice is finalised, or manually via the opening-balance screen.

### AR Invoices (Open Items)

Navigate to **Accounting > Receivables** (`/admin/ar/invoices`).

The list shows all AR open items for the company: document number, customer name, original amount, outstanding amount, currency, invoice date, due date, and status.

**Invoice statuses:**

| Status | Meaning |
|---|---|
| OPEN | Unpaid; full outstanding balance remains |
| PARTIAL | A receipt has been applied but a balance remains |
| PAID | Fully settled |
| WRITTEN\_OFF | Outstanding amount written off as uncollectable |

**Filtering:** use the customer picker (search by name) and the status dropdown to narrow the list. The customer is selected by name — no uid is typed.

---

### Recording a Receipt

Navigate to **Accounting > Record Receipt** (`/admin/ar/receipts/record`). Permission required: `AR.RECEIPT.RECORD`.

1. Pick the **customer** by name in the typeahead.
2. Enter the **receipt amount**, select the **currency**, and set the **receipt date**.
3. Choose the **tender type** (Cash, Mobile Money, Bank Transfer, Cheque, Other). For mobile or bank payments, optionally enter the bank/mobile reference.
4. The customer's open invoices load in the **allocation editor**.
   - Click **Auto oldest-first** to distribute the receipt against invoices starting from the oldest outstanding.
   - Or manually enter allocation amounts against individual invoices.
   - The editor shows the receipt total, allocated total, and unallocated balance. The Submit button is disabled if any allocation line exceeds the invoice's outstanding balance.
5. Optionally add a **WHT** amount (see WHT section below).
6. Click **Submit**. The receipt is recorded and the allocated invoices update their outstanding balances.

**Receipt statuses:**

| Status | Meaning |
|---|---|
| UNALLOCATED | No invoices have been allocated |
| PARTIAL | Part of the receipt amount is allocated; a balance remains |
| ALLOCATED | The full receipt amount has been applied |

**WHT on receipt:** if your company withholds tax from customer receipts, select the WHT type (kind = `WHT_ON_RECEIPT`) and enter the WHT amount. The GL posts: Cash DR (amount minus WHT), WHT Receivable DR (WHT amount), AR Control CR (full amount).

**Viewing receipts:** Navigate to **Accounting > Receipts** (`/admin/ar/receipts`). The list is paged and can be filtered by customer. Click any row to open the receipt detail, which shows the header and allocation lines.

---

### Credit Notes

A credit note reduces a customer's outstanding balance. It is raised from the invoices list. Permission required: `AR.CREDITNOTE`.

1. On **Accounting > Receivables**, find the target invoice row.
2. Click **Credit note** (visible only when `AR.CREDITNOTE` is held).
3. In the modal, enter the net amount, VAT amount, and reason.
4. Submit. The invoice outstanding is reduced and a GL contra posting is made.

---

### Write-Offs

A write-off removes an uncollectable balance from AR. Permission required: `AR.WRITEOFF`.

1. On **Accounting > Receivables**, find the OPEN or PARTIAL invoice.
2. Click **Write off**.
3. Enter a reason and confirm the date.
4. Submit. The invoice moves to WRITTEN\_OFF status; the outstanding balance is posted to the Bad Debt Expense account.

Invoices already PAID or WRITTEN\_OFF cannot be written off again.

---

### AR Opening Balances

To load balances brought forward from a prior system, navigate to **Accounting > AR Opening Balance** (`/admin/ar/opening-balance`). Permission required: `AR.OPENING.SET`.

1. Pick the customer by name.
2. Enter the original amount, currency, invoice date, and an optional due date and document number.
3. Submit. An opening-balance invoice (source = `OPENING_BALANCE`) is created and posted to the AR control account.

---

### Customer Statements and Ageing

**Customer statement:** Navigate to **Accounting > Customer Statement** (`/admin/ar/statement`). Permission required: `AR.STATEMENT.VIEW`. Pick a customer by name to view total outstanding, ageing breakdown, open items, and recent receipts.

**Ageing buckets:**

| Bucket | Days Overdue |
|---|---|
| Current | 0 or not yet due |
| 1–30 | 1 to 30 days past due date |
| 31–60 | 31 to 60 days past due date |
| 61–90 | 61 to 90 days past due date |
| 90+ | More than 90 days past due date |

**Customer balance lookup:** on the **Accounting > AR Ageing** screen (`/admin/ar/ageing`), use the balance lookup section to check a specific customer's net balance (outstanding invoices minus unallocated receipts). Permission required: `AR.VIEW`.

---

## Accounts Payable

AP tracks amounts your company owes to suppliers. Only users with the appropriate AP permissions can access this module. By default, only the ORG\_ADMIN role is granted AP permissions.

### Entering a Supplier Bill

Navigate to **Accounting > Enter Bill** (`/admin/ap/supplier-bills/enter`). Permission required: `AP.BILL.ENTER`.

1. Pick the **supplier** by name in the typeahead.
2. Enter the **Supplier Invoice No.**, **Bill Date**, and **Due Date**.
3. Select the **currency**. For foreign-currency bills, an FX rate for the bill date must exist.
4. Add one or more lines. For goods supplied against a Purchase Order:
   - Select the PO number and the matching PO line for each bill line.
   - Enter the billed quantity and unit cost.
5. Submit. The system runs a **3-way match** automatically:
   - If all lines are within the price and quantity tolerance (default 2%), the bill moves to **MATCHED** and a GL posting is made (DR Purchases / CR AP Control).
   - If any line exceeds tolerance, the bill is **HELD** with a price or quantity variance flag.

**Accepting a variance (requires `AP.BILL.MATCH`):**

On a HELD bill, each variance line shows the variance amount and percentage. Click **Accept variance** to approve the line. When all variance lines are accepted the bill moves to MATCHED and the GL posts.

**Service bills (no PO):** leave the PO field blank and enter free-text line descriptions.

---

### Viewing and Navigating Bills

Navigate to **Accounting > Payables** (`/admin/ap/supplier-bills`). The list shows all bills with status, outstanding amount, and source. Click a bill number to open its detail screen, which shows the header, lines, and match result.

**Bill statuses:**

| Status | Meaning |
|---|---|
| DRAFT | Entered but not yet matched |
| MATCHED | 3-way match passed; GL posted |
| HELD | Match variance requires acceptance |
| APPROVED | Explicitly approved for payment |
| PARTIALLY\_PAID | One or more payments made; balance remains |
| PAID | Fully paid |

---

### Payments

**Single-bill payment:** From the **Accounting > Payments** list (`/admin/ap/payments`), use the inline pay form. Permission required: `AP.PAYMENT.RUN`.

1. Select the bill to pay (by bill number).
2. Enter the payment amount (can be partial), payment date, and tender type.
3. Submit. The GL posts DR AP Control / CR Cash.

**Payment run (multiple bills):** Navigate to **Accounting > Record Payment** (`/admin/ap/payments/record`).

1. Pick the supplier by name.
2. Their payable bills (MATCHED, APPROVED, or PARTIALLY\_PAID) load as a checkbox list.
3. Select the bills to pay. Use **Select all** to pay all outstanding bills for that supplier.
4. Set the payment date and tender type.
5. Optionally add a **WHT on payment** amount (see WHT section below).
6. Submit. A payment run record (`PAYRUN-####`) is created covering all selected bills.

**WHT on payment:** select a WHT type (kind = `WHT_ON_PAYMENT`) and enter the WHT amount. The GL reduces the cash credit by the withheld amount.

---

### Debit Notes

A debit note reduces the amount owed to a supplier. Raised from the payables list. Permission required: `AP.DEBITNOTE`.

1. On **Accounting > Payables**, find a MATCHED, APPROVED, or PARTIALLY\_PAID bill.
2. Click **Debit note**.
3. Enter the note date, net amount, optional VAT, and reason.
4. Submit. The bill outstanding is reduced and the GL posts DR AP / CR Purchases.

---

### AP Opening Balances

Navigate to **Accounting > AP Opening Balance** (`/admin/ap/opening-balance`). Permission required: `AP.OPENING.SET`.

1. Pick the supplier by name.
2. Enter the gross amount, bill date, due date, and optional supplier invoice number.
3. Submit. An opening-balance supplier bill is created (source = `OPENING_BALANCE`).

---

### Supplier Statement

Navigate to **Accounting > Supplier Statement** (`/admin/ap/statement`). Permission required: `AP.VIEW`.

Pick a supplier by name to view:

- **Outstanding balance** — total of unpaid bills.
- **Ageing breakdown** — same bucket structure as AR (Current, 1–30, 31–60, 61–90, 90+).
- **Open bills** — all bills with a remaining balance.
- **Reconciliation** — compares the AP sub-ledger total against the GL AP control account. A zero difference confirms the books are in agreement. A non-zero difference is a finance-grade discrepancy requiring investigation.

---

## Cash & Bank

### Cash and Bank Accounts

Navigate to **Accounting > Cash & Bank Accounts** (`/admin/cash/accounts`). Permission required: `CASH.VIEW` to view; `CASH.ACCOUNT.MANAGE` to create or set the default.

The list shows all cash and bank accounts for the company: code, name, type (CASH or BANK), linked GL account, currency, default flag, and active status.

**To create an account (requires `CASH.ACCOUNT.MANAGE`):**

1. Click **New account**.
2. Enter the account name and select the account type.
   - For **BANK** accounts, also enter the bank name (required), bank account number, and branch.
3. Select the linked **GL Asset account** from the picker (only ASSET-type accounts are listed).
4. Optionally tick **Set as default account**.
5. Save. The account code is generated automatically.

**To set the default account:** click **Set default** on any non-default row.

---

### Cash Transfers

To move funds between two accounts, navigate to **Accounting > Cash Transfer** (`/admin/cash/transfers/record`). Permission required: `CASH.TRANSFER`.

1. Select the **Source account** and **Destination account** from the pickers (by code — name). Source and destination must differ.
2. Enter the **amount**, **transfer date**, and an optional **reference**.
3. Submit. A transfer number (`CBT-####`) is generated. The GL posts a balanced entry covering the two accounts.

View the transfers list at **Accounting > Transfers** (`/admin/cash/transfers`). Click a row to see the transfer detail.

---

### Direct Cash/Bank Entries

For transactions that do not originate from AP, AR, or a transfer (e.g. bank interest, bank charges), navigate to **Accounting > Cash / Bank Entry** (`/admin/cash/entries/record`). Permission required: `CASH.ENTRY.RECORD`.

1. Select the **Cash/Bank account** by name.
2. Choose the **direction** (IN for money received by the account, OUT for money leaving the account).
3. Enter the **amount** and **transaction date**.
4. Select a **Counter GL account** from the picker. The picker lists INCOME, EXPENSE, and EQUITY accounts.
5. Enter an optional **memo**.
6. Submit.

Direct entries appear in the account statement but are not shown in a separate list screen.

---

### Bank Reconciliation

Bank reconciliation matches your book records against your bank statement. Navigate to **Accounting > Bank Reconciliation** (`/admin/cash/reconciliations`). Permission required: `CASH.RECONCILE`.

**Opening a reconciliation:**

1. Select a **BANK** account (only bank accounts can be reconciled).
2. The account's uncleared transactions load.
3. Click **Open reconciliation**.
4. Enter the **Statement Date** and the **Statement Closing Balance** from your bank statement.
5. Submit. A reconciliation is opened in **DRAFT** status.

**Marking transactions cleared:**

1. Tick the **Cleared** checkbox against each transaction that appears on the bank statement.
2. The **Cleared book balance** and **Difference** update in real time.
   - Difference = cleared book balance − statement closing balance.

**Completing the reconciliation:**

1. When the difference is exactly zero, the **Complete** button becomes active.
2. Click **Complete**. The reconciliation moves to **COMPLETED** status and is locked.

A completed reconciliation cannot be modified. The difference must be zero to complete — a non-zero difference means there are unidentified items on either side.

---

### Cheque Register

Track issued cheques at **Accounting > Cheques** (`/admin/cash/cheques`). Permission required: `CHEQUE.MANAGE`.

**Registering a cheque:**

1. Click **Register cheque**.
2. Select the **BANK account** (only bank accounts issue cheques).
3. Enter the cheque number, payee, amount, issue date, and value date.
4. Submit. The cheque is recorded with status **ISSUED**.

**Cheque lifecycle:**

- ISSUED — cheque has been written and handed out.
- Click **Clear** when the cheque has been presented and cleared the bank → status becomes **CLEARED**.
- Click **Cancel** if the cheque is lost, stopped, or voided → status becomes **CANCELLED**.

CLEARED and CANCELLED are terminal states; no further transitions are possible.

---

### Cash Account Statement

Navigate to **Accounting > Cash Statement** (`/admin/cash/statement`). Permission required: `CASH.VIEW`.

Select an account by name to view:

- **Current balance** — the running book balance.
- **Transaction history** — each cash transaction in date order with a running balance column (IN transactions increase the balance; OUT transactions decrease it).
- **GL reconciliation** — compares the account's book balance against the linked GL asset account balance. A zero difference confirms agreement. A non-zero difference requires investigation.

---

## Tax

### VAT Returns

Navigate to **Accounting > Tax > VAT Returns** (`/admin/tax/vat-returns`). Permission required: `VAT.VIEW`.

The list shows all VAT returns for the company with their period, status, and key amounts.

**VAT return statuses:**

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; editable |
| FILED | Submitted to TRA; locked |

**Opening a new VAT return (requires `VAT.RETURN.PREPARE`):**

1. Click **New VAT Return**.
2. Select the **year** and **month**.
3. Submit. The system creates a DRAFT return for the period and performs an initial computation. The opening credit (if any) is carried forward from the most recent FILED prior-period return.

**VAT return detail:** click a return row to open its detail. The detail screen shows:

- **Output VAT** — VAT collected on sales, broken down by tax band (Standard 18%, Zero-rated, Exempt).
- **Input VAT** — VAT paid on purchases.
- **Manual Adjustments** — optional signed adjustment lines (see below).
- **Opening Credit b/f** — carry-forward from the prior FILED return.
- **Net VAT** — output VAT − input VAT + adjustments − opening credit.
- The net label shows **"Payable to TRA"** (net > 0), **"Credit carried forward"** (net < 0), or **"Nil"** (net = 0).

**Recomputing a DRAFT return (requires `VAT.RETURN.PREPARE`):**

Click **Recompute** on the detail screen to re-read the current sales and purchase figures. This is useful after new invoices or bills have been entered for the period.

---

### VAT Adjustments

Adjustments can be added to a DRAFT return to correct prior-period errors or reflect credit/debit note VAT amounts. Permission required: `VAT.ADJUST`.

**To add an adjustment:**

1. On the DRAFT return detail, click **Add Adjustment**.
2. Choose the **Reason**:
   - Bad Debt Relief
   - Prior Period Correction
   - Credit Note VAT
   - Debit Note VAT
   - Other
3. Choose the **Effect** (Increase VAT or Decrease VAT).
4. Enter a positive **Amount** and an optional narrative.
5. Submit. The net VAT recalculates immediately.

To remove an adjustment, click the remove icon on the adjustment row. Adjustments cannot be added or removed from a FILED return.

---

### Filing a VAT Return

Filing locks the return and posts the settlement journal to the GL. Permission required: `VAT.RETURN.FILE`.

**Requirements:**

- The return must be in DRAFT status.
- All prior-period returns for the same company must be FILED (you cannot file period N while period N-1 is still DRAFT).
- The GL config keys `VAT_PAYABLE`, `VAT_INPUT`, and `VAT_DUE` must be mapped to active accounts.

**Steps:**

1. On the DRAFT return detail, click **File Return**.
2. Enter the **TRA Filing Reference** and **Filing Date**.
3. Click **Confirm File**. The system:
   - Runs a final recompute.
   - Posts a settlement journal (DR VAT\_PAYABLE output amount / CR VAT\_INPUT input amount / balancing leg to VAT\_DUE).
   - Sets the return to FILED and records the filing date and reference.
   - Shows a link to the posted journal.

A nil-activity return (output and input both zero) files and locks without posting a journal.

---

### WHT Types and Register

**WHT Types:** Navigate to **Accounting > Tax > WHT Types** (`/admin/tax/wht-types`). Permission required: `WHT.VIEW` to view; `WHT.MANAGE` to create, edit, and deactivate.

WHT types define the rates at which tax is withheld. Each type has:

- A unique code and name.
- A kind: **WHT\_ON\_PAYMENT** (withheld when paying a supplier) or **WHT\_ON\_RECEIPT** (withheld by the customer from your receipt).
- A rate percentage.

To create a WHT type:

1. Click **New WHT Type**.
2. Enter the code, name, kind, and rate percentage (0 or greater).
3. Save.

The kind is fixed at creation and cannot be changed. To deactivate a type, click **Deactivate** on its row. An inactive type is excluded from the pickers in the AP payment and AR receipt screens.

**WHT Register:** Navigate to **Accounting > Tax > WHT Register** (`/admin/tax/wht-register`). Permission required: `WHT.VIEW`.

The register shows all WHT certificates in a period, grouped into two sections:

- **WHT Payable to TRA** — certificates from supplier payments (`WHT_ON_PAYMENT`).
- **WHT Receivable** — certificates from customer receipts (`WHT_ON_RECEIPT`).

Select the period by choosing **Month** mode (year + month) or **Range** mode (start and end dates), then click **Load**.

---

## Foreign Exchange (FX)

### Maintaining Currencies and Rates

Navigate to **Accounting > FX > Exchange Rates** (`/admin/fx/rates`). Permission required: `CURRENCY.VIEW` to view; `CURRENCY.MANAGE` to add rates.

The available currencies (TZS, USD, EUR, KES, GBP) are seeded at system setup. The rate list shows all effective-dated exchange rates for the company, newest first.

**To add a new rate (requires `CURRENCY.MANAGE`):**

1. Click **New rate**.
2. Select the **From currency** (the foreign currency) and **To currency** (must equal the company base currency, TZS).
3. Enter the **rate** (expressed as: 1 unit of foreign currency = X units of TZS), the **effective date**, and optionally the rate type (defaults to SPOT).
4. Submit. The rate is effective from that date for documents and revaluations.

> Rate entry is append-only — there is no edit-in-place. To correct a rate, add a new row with the corrected value and the correct effective date. If a rate for the same currency, date, and type already exists, the entry is rejected.

The system uses the most recent SPOT rate on or before the document date when converting foreign-currency documents to base TZS.

---

### Foreign-Currency Documents

When you enter a sales invoice, supplier bill, or receipt in a foreign currency (e.g. USD), the system automatically converts all GL postings to the company base currency (TZS) using the effective SPOT rate for the document date. The document stores the face amounts in the foreign currency; all GL ledger entries are in TZS.

If no rate exists for the document's currency on or before the document date, the posting is rejected with a rate-not-found error.

---

### Period-End Revaluation Run

At period end, open foreign-currency balances (AR invoices and AP bills not yet settled) must be revalued at the current spot rate. Navigate to **Accounting > FX > Revaluation Runs** (`/admin/fx/revaluation-runs`). Permission required: `FX.EXPOSURE.VIEW` to view runs; `FX.REVALUE` to preview and post.

**Running a preview (dry run):**

1. Select the company and click **Preview**.
2. Choose the **fiscal period** from the picker.
3. Optionally enter a **spot rate date** (defaults to the period end date).
4. Click **Run preview**. The system shows each open foreign item with its carrying base amount, revalued base amount, and adjustment (gain or loss). No GL is posted.

**Posting the revaluation:**

1. After reviewing the preview, click **Post**.
2. Enter the **posting date** and confirm.
3. A revaluation run record is created and a balanced unrealized FX journal posts to the GL.
4. If the next fiscal period is already open, the system automatically schedules and posts a reversal on the first day of the next period.
5. If the next period is not yet open, the run status is **POSTED** and the reversal can be triggered manually later (see below).

**Manually reversing a run:**

On a POSTED run in the runs list, click **Reverse**, enter the reversal date, and confirm. The reversal journal posts and the run status moves to **REVERSED**.

**Realized FX gains and losses** are posted automatically when a foreign-currency invoice is settled. The difference between the original invoice rate and the settlement rate is posted to the `REALIZED_FX_GAIN` or `REALIZED_FX_LOSS` accounts configured in GL config. No manual action is needed.
