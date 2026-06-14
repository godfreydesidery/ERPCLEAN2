# HR & Payroll, Budgeting, and Platform Services

This chapter covers three cross-cutting domains: the Human Resources and Payroll module (departments, employees, contracts, leave, loans, pay components, payroll runs, and statutory setup), the Budgeting module (budget creation, version lifecycle, line entry, and management reports), and the Platform services used by all other modules (document generation, notifications, the approval engine, and the audit trail).

---

## Part 1 — Human Resources and Payroll

The HR & Payroll module is accessible from the **HR & Payroll** navigation group. What appears in that group depends on your permissions.

### Permission requirements

| Screen | View permission | Manage / act permission |
|---|---|---|
| Departments | `HR.EMPLOYEE.VIEW` | `HR.EMPLOYEE.MANAGE` |
| Employees | `HR.EMPLOYEE.VIEW` | `HR.EMPLOYEE.MANAGE` |
| Employee Contracts | `HR.EMPLOYEE.VIEW` | `HR.EMPLOYEE.MANAGE` |
| Leave Requests | `HR.LEAVE.VIEW` | `HR.LEAVE.MANAGE` (submit), `HR.LEAVE.APPROVE` (decide) |
| Employee Loans | `HR.LOAN.MANAGE` | `HR.LOAN.MANAGE` |
| Pay Components | `HR.PAYCOMPONENT.MANAGE` | `HR.PAYCOMPONENT.MANAGE` |
| Payroll Runs | `HR.PAYROLL.VIEW` | `HR.PAYROLL.RUN` / `APPROVE` / `POST` / `DISBURSE` / `REVERSE` |
| Statutory Setup | `HR.STATUTORY.MANAGE` | `HR.STATUTORY.MANAGE` |

A user holding none of the HR permissions will not see the **HR & Payroll** nav group and will be blocked from accessing any HR route directly.

---

### Departments

Navigate to **HR & Payroll > Departments** (`/admin/hr/departments`).

Departments are company-level reference data. They are assigned to employees and appear on payroll reports.

**Creating a department:**

1. Click **New department**.
2. Enter a **Code** (up to 30 characters) and a **Name** (up to 120 characters).
3. Click **Save**.

**Editing a department:** click the department row to open the edit form, change the name, and save.

**Deactivating a department:** click **Deactivate** on the department row. The record is soft-deactivated, not deleted. Active employees in that department are not affected — the department reference is retained for historical records.

---

### Employees

Navigate to **HR & Payroll > Employees** (`/admin/hr/employees`).

The list shows employee number, name, department name, and employment status. Use the paginator to navigate through large lists.

**Creating an employee (minimum required fields):**

1. Click **New employee**.
2. Enter **First name**, **Last name**, and **Hire date**.
3. Choose a **Department** by searching for its name in the department picker.
4. Optionally fill in national ID, TIN, NSSF number, HESLB number, date of birth, gender, and job title.
5. Click **Save**.

The system assigns an **employee number** automatically (format `EMP-000001`). The employee's status is set to **ACTIVE** on creation.

**Viewing and editing an employee:** click the employee row in the list to open the detail page. If you hold `HR.EMPLOYEE.MANAGE`, you can edit the employee's fields and save changes.

**Archiving an employee:** on the employee detail page, click **Archive**. This changes the status to **TERMINATED** and marks the record inactive. The employee record is retained for historical and payroll purposes. There is no way to restore an archived employee through the UI — contact your system administrator if this is needed.

**Employment status:** only **ACTIVE** (on create) and **TERMINATED** (on archive) are reachable through the HR screens. The statuses ON_LEAVE and SUSPENDED exist in the system but cannot be set from these screens.

---

### Employment Contracts

Navigate to **HR & Payroll > Employee Contracts** (`/admin/hr/contracts`).

The contracts screen is employee-picker driven: you choose an employee first and their contracts are listed in a panel below.

**Viewing contracts for an employee:**

1. Select the **Company** if shown.
2. In the **employee picker**, start typing the employee's name or number and select them.
3. The panel shows all contracts for that employee: contract type, base salary (TZS), start date, end date, and active status.

**Creating a contract:**

An employee may have at most one active contract at a time. Creating a second contract while one is active will be rejected.

1. With the employee selected, click **New contract**.
2. Choose the **Contract type**: PERMANENT, FIXED_TERM, CASUAL, or PROBATION.
3. Enter the **Base salary** (stored in TZS) and **Start date**.
4. For FIXED_TERM contracts, enter an **End date**.
5. Set the statutory flags: **PAYE resident**, **NSSF member**, **HESLB member**. These control which statutory deductions are applied during payroll calculation.
6. Click **Save**.

The pay frequency is fixed at MONTHLY (v1). Currency is fixed at TZS.

**Terminating a contract:** click **Terminate** on the contract row. The contract becomes inactive (`active = false`). Once the active contract is terminated, a new one can be created for the employee.

---

### Leave Requests

Navigate to **HR & Payroll > Leave Requests** (`/admin/hr/leave-requests`).

The list shows employee name, leave type, dates, number of days, and status. Use the paginator for large lists.

**Submitting a leave request (requires `HR.LEAVE.MANAGE`):**

Leave types are configured by the system administrator in the database. The leave type's ID is required when submitting.

1. Click **New leave request**.
2. Pick the **employee** by name.
3. Select the **leave type** from the dropdown (leave types are seeded by your administrator).
4. Enter **From date**, **To date**, and the number of **Days**.
5. Optionally enter a **Reason**.
6. Click **Submit**. The request status is set to **PENDING**.

**Deciding a leave request (requires `HR.LEAVE.APPROVE`):**

1. Open the leave request from the list (link goes to `/admin/hr/leave-requests/uid/:uid`).
2. Click **Approve** or **Reject**.
3. Enter a decision note (required for Reject, optional for Approve).
4. Confirm.

The only valid decisions are **APPROVED** or **REJECTED**. PENDING and CANCELLED are not valid decision values and will be rejected.

**Leave request statuses:**

| Status | Meaning |
|---|---|
| PENDING | Submitted, awaiting a decision |
| APPROVED | Approved; approved unpaid leave days reduce the employee's basic salary pro-rata in the payroll run |
| REJECTED | Declined |
| CANCELLED | Withdrawn before decision |

**Note on unpaid leave:** if the leave type is marked as unpaid (configured by the administrator), approved leave days that overlap a payroll period will reduce that employee's basic salary pro-rata. The system uses 22 working days per month as the standard period.

---

### Employee Loans

Navigate to **HR & Payroll > Employee Loans** (`/admin/hr/loans`).

The list shows employee name, loan number, principal, installment amount, outstanding balance, and status. Viewing and managing loans both require `HR.LOAN.MANAGE`.

**Creating a loan:**

1. Click **New loan**.
2. Pick the **employee** by name.
3. Enter **Principal**, **Monthly installment**, and **Start date**.
4. Pick the **GL account** by name (the loan will be posted to this account).
5. Click **Save**. The loan is created with its outstanding balance equal to the principal.

**Approving a loan:**

1. Open the loan from the list (`/admin/hr/loans/uid/:uid`).
2. Click **Approve**. The loan status changes to **ACTIVE**.

Once ACTIVE, the loan installment is automatically deducted from the employee's net pay during each payroll calculation. If the outstanding balance is less than the installment, only the remaining outstanding amount is deducted.

**Loan statuses:** ACTIVE loans are picked up by payroll. SETTLED and CANCELLED statuses exist in the system but can only be set by the system administrator — there is no Settle or Cancel button on the UI in this version.

---

### Pay Components

Navigate to **HR & Payroll > Pay Components** (`/admin/hr/pay-components`).

Pay components define the earnings and deductions applied to employees during payroll calculation. They are company-level reference data. The list is not paginated. Viewing and managing pay components both require `HR.PAYCOMPONENT.MANAGE`.

**Creating a pay component:**

1. Click **New pay component**.
2. Enter a **Code** and a **Name**.
3. Set the **Kind**: EARNING (adds to gross) or DEDUCTION (reduces net).
4. Set the **Basis**: FIXED (a fixed amount per run) or PERCENT\_OF\_BASIC (a percentage of the employee's basic salary).
5. Check **Taxable** if this component is subject to PAYE.
6. Check **Pensionable** if this component is included in the pension-contribution base.
7. Pick the **GL account** by name (earnings and deductions post to this account).
8. Click **Save**.

**Editing and deactivating:** open the component by clicking its row (`/admin/hr/pay-components/uid/:uid`). Edit the fields and save, or click **Deactivate** to soft-deactivate the component (it becomes inactive and will no longer appear in payroll calculations going forward).

**Per-employee recurring items** (the amounts for PERCENT\_OF\_BASIC components and any fixed amounts applied to specific employees) are configured by the administrator directly in the system. These are applied automatically during payroll calculation and do not have a separate UI screen.

---

### Payroll Runs

Navigate to **HR & Payroll > Payroll Runs** (`/admin/hr/payroll-runs`).

A payroll run computes gross pay, statutory deductions, voluntary deductions, and loan repayments for all employees with an active contract in a given period.

**Payroll run lifecycle:**

```
DRAFT → CALCULATED → APPROVED → POSTED → PAID
                                         ↓
                                      REVERSED
```

Each step requires a different permission. Only one active run can exist per period.

**Step 1 — Create a run (requires `HR.PAYROLL.RUN`):**

1. Click **New payroll run**.
2. Enter **Period month** (1–12), **Period year**, and **Pay date**.
3. Optionally pick a **Branch** by name if the run covers a specific branch.
4. Click **Save**. The run is created in **DRAFT** status with zero totals.

**Step 2 — Calculate (requires `HR.PAYROLL.RUN`):**

1. Open the run (`/admin/hr/payroll-runs/uid/:uid`).
2. Click **Calculate**. The system builds one payroll line per ACTIVE employee who has an ACTIVE contract:
   - Basic salary earning.
   - PAYE income tax (from the effective PAYE band set for the pay date, if `payeResident = true`).
   - NSSF deduction (employee share, if `nssfMember = true`).
   - HESLB deduction (if `heslbMember = true`).
   - Employer contributions (NSSF/WCF/SDL employer shares from the effective statutory rate sets).
   - Any voluntary pay-component recurring items configured for the employee.
   - Loan repayment deductions for any ACTIVE loans with an outstanding balance.
   - Pro-rata reduction for any approved unpaid leave overlapping the period.
3. The run status moves to **CALCULATED** and the Lines tab populates.

You can recalculate from DRAFT, CALCULATED, or APPROVED status — recalculation rebuilds all lines from scratch.

**Reviewing lines:** click the **Lines** tab to review each employee's line. A line showing a **FLAGGED** badge means the employee's net pay is negative after deductions. You must resolve flagged lines before the run can be approved — for example by reducing a loan installment and then recalculating.

**Step 3 — Approve (requires `HR.PAYROLL.APPROVE`):**

1. With the run in CALCULATED status and zero FLAGGED lines, click **Approve**.
2. Status moves to **APPROVED**.

**Step 4 — Post (requires `HR.PAYROLL.POST`):**

1. With the run in APPROVED status, click **Post**.
2. Status moves to **POSTED**. The GL journal is written asynchronously via the payroll posting handler. Payslips are generated (one per employee line).

**Step 5 — Disburse (requires `HR.PAYROLL.DISBURSE`):**

1. With the run in POSTED status, click **Disburse**.
2. Pick the **Cash or bank account** by name from which the net wages will be paid.
3. Optionally enter a **Transaction date** (defaults to the run's pay date).
4. Click **Submit**. Status moves to **PAID**. A Cash & Bank OUT entry is recorded (debit Net Wages Payable, credit the chosen bank/cash account).

**Reversing a run (requires `HR.PAYROLL.REVERSE`):**

A POSTED or PAID run can be reversed if needed (for example, a posting error). Click **Reverse** on the run detail. The status moves to **REVERSED** and a reversing GL journal is posted.

**Legal action matrix:**

| From status | Calculate | Approve | Post | Disburse | Reverse |
|---|---|---|---|---|---|
| DRAFT | Allowed | Blocked | Blocked | Blocked | Blocked |
| CALCULATED | Allowed (recalc) | Allowed (if no FLAGGED) | Blocked | Blocked | Blocked |
| APPROVED | Allowed (recalc) | Blocked | Allowed | Blocked | Blocked |
| POSTED | Blocked | Blocked | Blocked | Allowed (if net > 0) | Allowed |
| PAID | Blocked | Blocked | Blocked | Blocked | Allowed |
| REVERSED | Blocked | Blocked | Blocked | Blocked | Blocked |

---

### Statutory Setup

Navigate to **HR & Payroll > Statutory Setup** (`/admin/hr/statutory`). Requires `HR.STATUTORY.MANAGE`.

The statutory setup screen shows two sections: **PAYE band sets** and **Statutory rate sets**. These sets determine how income tax and levies are calculated during payroll calculation.

**Creating a PAYE band set:**

1. Click **New PAYE band set**.
2. Enter an **Effective from** date, a **Tax-free threshold** (the monthly income amount below which no PAYE applies), and an optional description.
3. Add one or more bands. Each band requires: band number (ascending), lower bound (monthly income where this rate starts), marginal rate (decimal, e.g. `0.20` for 20%), and cumulative fixed tax (the tax already accumulated on income up to this band's lower bound).
4. Click **Save**.

The system uses the **most recently effective** band set whose effective date is on or before the payroll run's pay date.

**Creating a statutory rate set:**

1. Click **New rate set**.
2. Choose the **Rate type**: NSSF, WCF, SDL, or HESLB.
3. Enter the **Effective from** date and **Basis** (e.g. GROSS or BASIC).
4. Enter the applicable rates (employee rate and/or employer rate, as a decimal).
5. For SDL, enter a **Headcount threshold** (SDL applies only when the company headcount equals or exceeds this number).
6. Click **Save**.

Contract statutory flags control which rate sets apply to each employee:
- `NSSF member` → NSSF deductions apply.
- `PAYE resident` → PAYE income tax applies.
- `HESLB member` → HESLB deduction applies.
- WCF and SDL are employer-only contributions; no contract flag controls them (they apply to the run if an effective rate set exists and the SDL headcount threshold is met).

---

## Part 2 — Budgeting

The Budgeting module is accessible from the **Budgeting** navigation group. Budgets are planning tools only — they do not post to the General Ledger. GL actuals are read at report time for comparison purposes.

### Permission requirements

| Action | Permission |
|---|---|
| View budgets, versions, lines | `BUDGETING.BUDGET.VIEW` |
| Create and edit budgets, versions, lines | `BUDGETING.BUDGET.MANAGE` |
| Submit and recall versions | `BUDGETING.BUDGET.SUBMIT` |
| Approve and reject versions | `BUDGETING.BUDGET.APPROVE` |
| Run variance and actuals reports | `BUDGETING.REPORT.VIEW` |

---

### Creating a Budget

Navigate to **Budgeting > Budgets** (`/admin/budgets`). Requires `BUDGETING.BUDGET.MANAGE`.

A budget covers a specific fiscal year and may be scoped to a specific cost centre (dimension value) or set as company-wide.

1. Click **New budget**.
2. Enter a **Name** for the budget.
3. Enter the **Fiscal Year UID** of the fiscal year you are budgeting for. (This is a direct text entry — obtain the UID from your administrator or from the fiscal-year setup screen.)
4. Optionally enter a **Cost Centre UID** to scope the budget to a department or cost centre. Leave blank for a company-wide budget.
5. Click **Create**.

The system creates the budget and automatically creates **Version 1** in DRAFT status. There can be only one budget per fiscal year and cost-centre scope combination.

The budget list shows each budget's name, fiscal year, latest version number, and latest version status.

---

### Budget Versions and the Version Lifecycle

Each budget can have multiple versions representing revisions to the plan. Versions go through an approval cycle before becoming the active plan.

**Version statuses:**

| Status | Meaning |
|---|---|
| DRAFT | Under construction — lines can be edited |
| SUBMITTED | Submitted for approval — lines are locked |
| APPROVED | The active approved plan; supersedes any prior approved version |
| REJECTED | Declined — create a new version to revise |
| SUPERSEDED | A previously approved version replaced by a newer APPROVED version |

**Version lifecycle transitions:**

```
DRAFT → SUBMITTED (submit, requires ≥1 line)
SUBMITTED → DRAFT (recall)
SUBMITTED → APPROVED (approve — also supersedes the prior APPROVED version)
SUBMITTED → REJECTED (reject, reason required)
```

APPROVED, REJECTED, and SUPERSEDED are terminal — no further edits or lifecycle actions can be taken on a version in these states. To re-plan after a rejection, create a new version.

**Opening the budget detail:**

Click a budget row in the list to open its detail (`/admin/budgets/uid/:uid`). The detail shows the budget header and all versions, listed newest first. Each version row shows its version number ("V1", "V2", etc.), label, status badge, and line count.

**Creating a new version (Re-plan):**

1. On the budget detail, click **New version**.
2. Optionally enter a **label** for this version.
3. To start from a prior version's lines, pick the source version from the **Seed from version** picker by its version label and status (e.g. "V1 — FY2026 base"). Leave blank to start with an empty version.
4. Click **Create**. The new version is created in DRAFT status.

---

### Entering Budget Lines

Open the version detail by clicking a version row (`/admin/budget-versions/uid/:uid`). The lines table shows account, period, amount (TZS), and memo. Lines are editable only when the version is in DRAFT status.

Click **Edit Lines (Replace All)** to open the line editor. Choose one of three entry modes:

**DIRECT — enter each line individually:**

1. Click **Add line**.
2. In the **Account** picker, choose the GL account by name.
3. In the **Period** picker, choose the fiscal period (e.g. "P3 – March 2026").
4. Enter the **Amount** in TZS (must be ≥ 0).
5. Optionally enter a **Memo**.
6. Repeat for additional lines.
7. Click **Replace Lines**. The new lines replace all prior lines for this version.

**ANNUAL\_SPREAD — enter an annual total and spread it evenly across 12 periods:**

1. In the **Account** picker, choose the GL account by name.
2. Enter the **Annual amount** in TZS.
3. Click **Replace Lines**. The system creates 12 lines (one per period), spreading the annual amount as evenly as possible (HALF_UP rounding; any remainder is added to the last period so the sum equals the annual total exactly).

The fiscal year must have exactly 12 periods to use ANNUAL\_SPREAD mode.

**SEED — copy lines from another version:**

1. In the **Seed from version** picker, choose the source version by its label and status.
2. Click **Replace Lines**. All lines from the source version are copied to this version, replacing any existing lines.

**Note:** editing lines is blocked when the version is not in DRAFT status. If you need to edit a SUBMITTED version's lines, recall it to DRAFT first.

---

### Submitting, Approving, and Rejecting Versions

**Submit (requires `BUDGETING.BUDGET.SUBMIT`):**

1. On the budget detail, click **Submit** next to the DRAFT version.
2. The version must have at least one line. If it has no lines, submission is rejected.
3. Status moves to SUBMITTED. Lines are locked.

**Recall (requires `BUDGETING.BUDGET.SUBMIT`):**

If you need to revise a SUBMITTED version, click **Recall** to return it to DRAFT. The submission timestamp is cleared and lines become editable again.

**Approve (requires `BUDGETING.BUDGET.APPROVE`):**

1. Click **Approve** next to the SUBMITTED version.
2. Optionally enter an approval note.
3. Confirm.

The version status moves to APPROVED. If there was a previously APPROVED version for the same scope, it is automatically moved to SUPERSEDED. Only one version can be APPROVED at any time per scope.

**Reject (requires `BUDGETING.BUDGET.APPROVE`):**

1. Click **Reject** next to the SUBMITTED version.
2. Enter a **rejection reason** (required).
3. Confirm.

The version status moves to REJECTED. The rejection reason is recorded for reference. To re-plan, create a new DRAFT version.

---

### Budget Reports

Both reports require `BUDGETING.REPORT.VIEW` and are accessible from the **Budgeting** nav group.

**Budget Variance Report** (`/admin/budgeting/variance`):

Compares the approved budget lines against actual GL postings for the selected period range.

1. Select the **Company**.
2. Enter the **Fiscal Year UID**.
3. Set the **From period** and **To period** (1–12; from must be ≤ to).
4. Optionally filter by **Account Type** (Income, Expense, Asset, Liability, Equity) and enter a **Cost Centre UID** to limit results to a specific centre.
5. Click **Run**.

The report shows account-level rows with budget amount, actual amount, variance (actual − budget), and a Favourable/Adverse label. For income accounts, actual > budget is favourable. For expense accounts, actual < budget is favourable.

If no APPROVED version exists for the selected scope, the report is returned with all budget amounts as zero and a "no approved budget" notice — the report is never silently wrong.

**Departmental Actuals Report** (`/admin/budgeting/departmental-actuals`):

Shows actual GL postings grouped by cost centre and account, with no budget comparison. Useful for monitoring departmental spending.

1. Select the **Company**.
2. Enter the **Fiscal Year UID**.
3. Set the **From period** and **To period**.
4. Click **Run**.

A null cost centre (transactions posted without a cost-centre dimension) appears as an **Unallocated** row.

---

## Part 3 — Platform Services

Platform services provide cross-cutting functionality used by all modules: document generation and management, notifications, the approval engine, and the audit trail.

---

### Documents

#### Generated Documents Log

Navigate to **Documents > Generated Documents** (`/admin/documents`). Requires `DOCUMENT.VIEW`.

The log lists every document that has been rendered for the active company, with document number, type badge, source, and generated-at timestamp. Use the **Type** filter dropdown to narrow results by document type (Invoice, AR Statement, Purchase Order, Goods Receipt, Delivery Note, Credit Note).

#### Rendering a Document

Requires `DOCUMENT.RENDER`. The render form is on the same Generated Documents screen.

Six document types can be rendered in v1:

| Document type | Source |
|---|---|
| Invoice | A finalised sales invoice |
| AR Statement | A customer (with from/to date range) |
| Purchase Order | A confirmed purchase order |
| Goods Receipt | A received GRN |
| Delivery Note | A delivery record |
| Credit Note | A sales return/credit |

To render a document:

1. Click **Render document**.
2. Choose the **Document type** from the dropdown.
3. For all types except AR Statement: pick the **source record** by its number (invoice number, PO number, etc.) in the source picker.
4. For AR Statement only: enter the **customer** (chosen by name) and the **from date** and **to date** in the params section.
5. Click **Render**. A new row appears in the log.

To download a rendered document, click the **Download** button on the log row or detail page (also requires `DOCUMENT.RENDER`).

#### Document Templates

Navigate to **Documents > Document Templates** (`/admin/document-templates`). Requires `DOCUMENT.TEMPLATE.MANAGE`.

The template registry lists one row per renderable document type. You can change the template's **title** and toggle its **status** (ACTIVE or INACTIVE) by clicking the row and saving. Deactivating a template does not delete it.

#### Document Branding

Navigate to **Documents > Document Branding** (`/admin/document-branding`). Requires `DOCUMENT.BRANDING.MANAGE`.

The branding profile is a per-company singleton (one set of settings per company, no list). It controls what appears in the header and footer of rendered PDF documents.

1. Open the screen. The current branding values load into the form.
2. Edit: **Display name**, **Legal name**, **Tax ID**, **Address**, **Contact phone**, **Contact email**, **Website**, **Footer terms text**, and **Bank details**.
3. Click **Save**.

Changes take effect on all subsequent document renders. Previously generated documents are not changed (the log is append-only).

---

### Notifications

#### Notification Inbox

Navigate to **Notifications > Inbox** (`/admin/notifications`). Requires `NOTIFICATION.VIEW`.

The inbox shows notifications sent to you within the active company, with title, message body, severity badge (INFO / WARNING / CRITICAL), and created-at timestamp.

- Toggle **Unread only** to filter to unread items only.
- Click **Mark read** on an individual row to mark it as read.
- Click **Mark all read** to clear the unread count at once.

The shell navigation bar shows a badge with the count of unread notifications. The badge reflects `GET /api/v1/notifications/unread-count`.

#### Notification Preferences

Navigate to **Notifications > Preferences** (`/admin/notification-preferences`). Requires `NOTIFICATION.PREFERENCE.MANAGE`.

Each notification type listed here can be tuned per user:

- **Muted** — suppress all delivery for this type for you.
- **Channels enabled** — choose which channels (IN\_APP, EMAIL) should deliver this type to you.

Click a preference row, adjust the settings, and save.

#### Notification Type Catalogue (Admin)

Navigate to **Notifications > Type Catalogue** (`/admin/notification-types`). Requires `NOTIFICATION.ADMIN`.

The catalogue lists all notification types registered in the system for the active company. Each type shows display name, audience permission, severity, default channels, and the company-level enabled/disabled toggle.

To disable a notification type company-wide (all users in the company stop receiving it), click the row, toggle **Company enabled** to off, and save. Any delivery attempts while the type is disabled will be recorded in the delivery log with suppression reason **COMPANY\_TYPE\_OFF**.

#### Notification Delivery Log (Admin)

Navigate to **Notifications > Delivery Log** (`/admin/notification-deliveries`). Requires `NOTIFICATION.ADMIN`.

The delivery log shows every notification delivery attempt with its outcome and, if suppressed, the reason. Use the **Channel** and **Outcome** filters to diagnose delivery problems.

| Suppression reason | Meaning |
|---|---|
| MUTED | The recipient muted this type |
| CHANNEL\_DISABLED | The channel (EMAIL/SMS) is not active |
| NO\_EMAIL | The recipient has no email address configured |
| COMPANY\_TYPE\_OFF | The type is disabled for this company |
| NO\_AUDIENCE | No eligible recipients found |

---

### Approvals

The approval engine intercepts specific actions in other modules (purchase order confirmation, payroll posting, budget submission, etc.) and routes them through a human-approval chain if a matching policy exists. If no policy matches, the action is auto-approved immediately.

Approval requests are created automatically by the relevant modules — there is no "create approval request" screen.

#### Approval Policies

Navigate to **Approvals > Approval Policies** (`/admin/approvals/policies`). Requires `APPROVALS.POLICY.VIEW`.

Policies define when human approval is required and who must approve. Each policy targets one document type and a monetary band.

**Creating a policy (requires `APPROVALS.POLICY.MANAGE`):**

1. Click **New policy**.
2. Enter a **Name** and choose the **Document type** (e.g. Purchase Order).
3. Set the **Branch scope**:
   - **COMPANY\_WIDE** — applies to the entire company. Do not enter a branch.
   - **BRANCH** — applies to one specific branch. Pick the branch by name from the branch picker. A branch-scoped policy takes priority over a company-wide policy when both match the same request.
4. Set **Min amount** and **Max amount** (TZS). Leave **Max amount** blank for an unbounded top band (applies to all amounts ≥ Min amount).
5. Add one or more **Approval steps**. Each step has a **sequence number** (dense from 1) and an **Approver role code** (the permission role whose holders will see this request in their inbox). Click **Add step** to add another level of approval.
6. Click **Save**.

Policy changes only affect future submissions. In-flight PENDING requests continue under the policy that existed when they were created.

**Deactivating a policy:** open the policy detail (`/admin/approvals/policies/uid/:uid`), click **Deactivate**. Status moves to INACTIVE. Inactive policies are not matched on new submissions.

#### Approval Inbox (My Inbox)

Navigate to **Approvals > My Inbox** (`/admin/approvals/inbox`). Requires `APPROVALS.DECIDE`.

The inbox shows PENDING requests whose current open step is routed to one of your roles. These are the requests waiting for your decision.

1. Click a request to open its detail.
2. Review the request: document type, amount, submitter, submission date, and step chain.
3. Click **Approve** (and optionally add a comment) or **Reject** (comment required).
4. Confirm.

When you approve a step:
- If this was the last step in the chain, the request moves to **APPROVED** and the originating document is released.
- If there are further steps, the request remains PENDING and the next step becomes the active one.

When you reject a step, the whole request moves to **REJECTED** and all remaining steps are marked **SKIPPED**.

#### All Requests

Navigate to **Approvals > All Requests** (`/admin/approvals/requests`). Requires `APPROVALS.REQUEST.VIEW`.

Shows all approval requests for the active company regardless of who the approver is. Filter by status (PENDING / APPROVED / REJECTED / RECALLED / CANCELLED) to narrow the list.

**Request statuses:**

| Status | Terminal? | Meaning |
|---|---|---|
| PENDING | No | Awaiting a decision on the current open step |
| APPROVED | Yes | All steps approved (or auto-approved) |
| REJECTED | Yes | A step was rejected; remaining steps skipped |
| RECALLED | Yes | Withdrawn by the submitter |
| CANCELLED | Yes | Cancelled by an administrator |

Terminal requests cannot be acted on further — all action buttons are hidden on a terminal request detail.

**Recalling a request (requires `APPROVALS.REQUEST.VIEW`, submitter only):**

The original submitter can recall their own PENDING request from the All Requests list. Open the request detail and click **Recall**. Status moves to RECALLED.

**Cancelling a request (requires `APPROVALS.ADMIN`):**

An administrator can cancel any non-terminal PENDING request. Open the request detail and click **Cancel**. Status moves to CANCELLED.

---

### Audit Trail

Navigate to **Audit** (`/admin/audit`). Requires `AUDIT.VIEW`.

The audit trail is a read-only, append-only log of all state-changing actions in the system. No records can be added, edited, or deleted from the UI.

The list shows:
- **Actor** (username)
- **Action** (event code, e.g. `USER.CREATE`, `PAYROLL.POST`, `APPROVAL.STEP.DECIDE`)
- **Target type and identifier**
- **Company and branch**
- **Timestamp** and IP address

Rows are sorted newest first (default page size 50, maximum 200 per page).

**Filters:**

- **Actor** — choose a user from the user picker by name.
- **Action** — enter or select an action code.
- **Target type** and **Target UID** — filter to records affecting a specific resource.
- **From date** and **To date** — filter by time range.

Non-root users are confined to their own active company's audit rows. The system administrator (`rootadmin`) can view audit records across all companies.
