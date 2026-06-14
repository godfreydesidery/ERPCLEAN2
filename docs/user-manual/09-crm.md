# CRM — Customer Relationship Management

The CRM module helps your sales team track every potential customer from first contact through to a closed sale. It is organised around three concepts:

- **Leads** — an initial expression of interest, before you know whether the person will become a customer.
- **Opportunities** — a qualified, qualified sales chance with an estimated value and a pipeline stage.
- **Activities** — any interaction logged against a lead or opportunity (calls, emails, meetings, notes, and tasks).

The CRM section also provides a **Pipeline Dashboard** showing deal value across stages, a **Forecast** for a chosen date range, and **Pipeline Stages** settings where an administrator can customise the stage list.

**Navigation:** Sidebar **CRM** group — **Leads**, **Opportunities**, **Pipeline Dashboard**, **Pipeline Stages**, **CRM Activities**.

Each item in the CRM nav group is hidden if you do not have the required permission. The sections below state the required permission for each action.

---

## Leads

Navigate to **CRM › Leads** (`/admin/crm/leads`).

**View:** `CRM.LEAD.VIEW` | **Create / edit / contact / disqualify:** `CRM.LEAD.MANAGE` | **Qualify:** `CRM.LEAD.QUALIFY`

### Lead status lifecycle

A lead passes through the following statuses:

```
NEW → CONTACTED → QUALIFIED → CONVERTED
              ↓                    ↑
         DISQUALIFIED       (via opportunity)
```

| Status | Meaning |
|---|---|
| **New** | Freshly captured; no contact made yet. |
| **Contacted** | You have made initial contact. |
| **Qualified** | Linked to a customer record; ready to become an opportunity. |
| **Converted** | An opportunity was created from this lead. Terminal — no further edits. |
| **Disqualified** | Ruled out. Terminal — no further edits. |

Once a lead reaches **Converted** or **Disqualified** it is locked: you cannot edit it, contact it, qualify it, or disqualify it again.

### How to capture a lead

1. Navigate to **CRM › Leads** (`/admin/crm/leads`).
2. Click **New Lead**. An inline form appears.
3. Enter the **Display Name** (required).
4. Select the **Lead Source** from the dropdown (Website, Referral, Walk-in, Campaign, Cold Call, Existing Customer, or Other).
5. Optionally enter Company Name, Contact, Phone, Email, and Notes.
6. Click **Submit**.

The system assigns a **Lead Number** (for example, `LEAD-0001`) and sets the status to **New**. The lead is stamped with your active branch.

### How to mark a lead as contacted

1. Open the lead from the list (`/admin/crm/leads/uid/:uid`).
2. Click **Mark as Contacted** (only available when status is New).
3. The status changes to **Contacted**.

### How to qualify a lead

Qualifying a lead links it to a customer record and moves it to **Qualified** status. You need the `CRM.LEAD.QUALIFY` permission.

1. Open a New or Contacted lead (`/admin/crm/leads/uid/:uid`).
2. Click **Qualify**.
3. Choose one of the two modes:

**Link an existing customer:**
- Select **Link existing customer**.
- Choose the customer by name from the picker. The customer must belong to the same company as the lead.
- Click **Submit**.

**Create a new customer from this lead:**
- Select **Create new customer**.
- Enter the new customer's **Name** (required) and select **Customer Kind** (Cash / Walk-in or Credit Account).
- Optionally enter Phone, Email, and Address.
- Click **Submit**. A new customer record is created automatically and the lead is linked to it.

After qualifying, the status badge changes to **Qualified** and the linked customer name is shown on the detail page.

### How to disqualify a lead

1. Open any non-terminal lead (New, Contacted, or Qualified) at `/admin/crm/leads/uid/:uid`.
2. Click **Disqualify**.
3. Enter a **Reason** (required — for example, "Budget too low" or "Not the right fit").
4. Click **Submit**.

The status changes to **Disqualified**. The reason is stored and displayed on the detail page.

### Editing a lead

Open the lead detail page and change any editable fields (display name, source, contact details, notes). Click **Save**. Editing is not available once the lead is Converted or Disqualified.

### Searching leads

On the Leads list, the search box filters by name. Pagination controls appear when the list exceeds 20 rows. Use the NEXT / PREVIOUS / page-number / FIRST / LAST controls to move between pages.

---

**Example — Capture a referral lead and qualify it to a new customer:**

Sales executive Amina Msangi at Kijenge branch receives a phone call from Juma Banda, who was referred by an existing client and wants to discuss buying office furniture in bulk.

1. Navigate to **CRM › Leads** (`/admin/crm/leads`). Click **New Lead**.
2. Display Name: `Juma Banda`; Lead Source: `Referral`; Phone: `+255754001122`; Notes: `Referred by Baraka Supplies — bulk office furniture interest`.
3. Click **Submit**. System creates `LEAD-0005`, status **New**.
4. Next day, Amina calls Juma. She opens `LEAD-0005` and clicks **Mark as Contacted**. Status becomes **Contacted**.
5. After the call confirms he runs a legitimate business, Amina clicks **Qualify**. She selects **Create new customer**, enters Name: `Banda Office Solutions`, Customer Kind: `Credit Account`, Phone: `+255754001122`. Clicks **Submit**.
6. A new customer record "Banda Office Solutions" is created. Lead status flips to **Qualified**. The linked customer name appears on the detail page.
7. Amina can now create an opportunity from this lead (see Opportunities section).

---

## Opportunities

Navigate to **CRM › Opportunities** (`/admin/crm/opportunities`).

**View:** `CRM.OPPORTUNITY.VIEW` | **Create / edit / stage / win / lose:** `CRM.OPPORTUNITY.MANAGE` | **Convert to document:** `CRM.OPPORTUNITY.CONVERT`

### Opportunity status lifecycle

```
OPEN → WON
OPEN → LOST
```

Once an opportunity is Won or Lost it is closed. Closed opportunities cannot be edited, and lines cannot be added or removed. Conversion to a quotation or sales order is still available on a closed opportunity (with the restrictions described below).

### How to create an opportunity

1. Navigate to **CRM › Opportunities** (`/admin/crm/opportunities`).
2. Click **New Opportunity** (or navigate to **CRM › Opportunities › Create** at `/admin/crm/opportunities/create`).
3. Select the **Customer** using the picker. Type part of the customer name to search; select from the results.
4. Select the **Pipeline Stage** from the dropdown. Only active stages are offered. The stage's default win probability is applied automatically unless you override it.
5. Enter the **Title** (required).
6. Select the **Currency** (defaults to TZS).
7. Optionally enter an **Estimated Value**, **Expected Close Date**, and **Win Probability** override.
8. Optionally select a **Source Lead** using the picker — only Qualified leads appear in this list. Selecting a source lead converts that lead to **Converted** status.
9. Click **Submit**.

The opportunity is created with status **Open** and an automatically assigned number (for example, `OPP-0001`). You land on the opportunity detail page (`/admin/crm/opportunities/uid/:uid`).

### How to add lines to an opportunity

Lines represent the products or services you expect to sell. You can add them while the opportunity is Open.

1. Open the opportunity detail page.
2. In the **Lines** section, type a product name into the search box and select the product (shown as `code — name`).
3. Select the **Unit** from the units dropdown.
4. Enter the **Quantity** (must be greater than zero).
5. Optionally enter the **Unit Price** and a **Discount %** (0–100).
6. Click **Add**.

To remove a line, click **Remove** on the row.

### How to advance the pipeline stage

1. Open the opportunity detail page (must be Open).
2. Click **Advance Stage**.
3. Select the **Target Stage** from the active-stages dropdown.
4. Optionally set a **Win Probability** to override the stage default.
5. Click **Submit**.

The stage and win probability update immediately.

### How to mark an opportunity as Won

1. Open the opportunity detail page (must be Open).
2. Click **Won**.
3. Optionally set the **Won Date** (defaults to today).
4. Click **Submit**.

Status changes to **Won**. Edit, add-line, advance-stage, win, and lose actions are no longer available. The Convert action remains available.

### How to mark an opportunity as Lost

1. Open an Open opportunity.
2. Click **Lose**.
3. Enter a **Loss Reason** (required — for example, "Lost to competitor on price").
4. Click **Submit**.

Status changes to **Lost**.

### How to convert an opportunity to a quotation or sales order

Conversion creates a Sales document (Quotation or Sales Order) pre-populated with the opportunity's customer, currency, and lines. You need the `CRM.OPPORTUNITY.CONVERT` permission.

**Requirements before converting:**
- The opportunity must have at least one line.
- To convert to a **Quotation**: opportunity must be Open or Won.
- To convert to a **Sales Order**: opportunity must be Won.

**Steps:**
1. Open the opportunity detail page.
2. Click **Convert**.
3. Select the **Target** (Quotation or Sales Order).
4. For a Quotation, optionally set a **Valid Until** date (defaults to today + 30 days).
5. Click **Convert**.

The system creates the document and shows a link to it (referenced by the document number, not a uid). Clicking the link navigates to the new Quotation or Sales Order.

Conversion is idempotent: if you click Convert a second time, the system returns the document that was already created rather than making a duplicate.

### Editing an opportunity

Open the detail page (must be Open). Change title, estimated value, expected close date, win probability, or stage. Click **Save**. Editing is blocked once the opportunity is Won or Lost.

---

**Example — Full pipeline journey: lead → opportunity through stages → won → convert to sales order:**

Sales manager Benson Kileo at Dar es Salaam branch handles a qualified lead for Banda Office Solutions (created in the lead example above).

1. Navigate to **CRM › Opportunities › Create** (`/admin/crm/opportunities/create`).
2. Customer: `Banda Office Solutions`; Pipeline Stage: `Qualification`; Title: `Bulk Office Furniture — Q3 2026`; Currency: `TZS`; Estimated Value: `4,500,000`; Expected Close Date: `2026-09-30`; Source Lead: `LEAD-0005 — Juma Banda` (auto-converts that lead to Converted).
3. Click **Submit**. Opportunity `OPP-0012` created, status **Open**.
4. Add lines to `OPP-0012`:
   - Executive Desk EXD-01, Unit: EA, Qty: 5, Unit Price: 480,000 = TZS 2,400,000.
   - Ergonomic Chair CHR-02, Unit: EA, Qty: 20, Unit Price: 105,000 = TZS 2,100,000.
5. After a needs-analysis call, Benson clicks **Advance Stage**, selects `Needs Analysis` (default probability 25%). Clicks **Submit**.
6. After sending a detailed proposal, Benson advances to `Proposal` (50%). After negotiation the stage moves to `Negotiation` (75%).
7. Juma accepts the quote. Benson opens the opportunity, clicks **Won**, sets Won Date: `2026-08-15`. Status becomes **Won**.
8. Click **Convert**, Target: `Sales Order`. System creates `SO-0034` with all lines pre-filled. Benson clicks the link to open the new Sales Order and proceeds with delivery.

---

## Pipeline Dashboard

Navigate to **CRM › Pipeline Dashboard** (`/admin/crm/pipeline`). **Permission:** `CRM.PIPELINE.VIEW`.

The pipeline dashboard shows the current state of all open opportunities across your sales pipeline. It is scoped to a company and branch — select both to load the data.

### Board summary

The board shows each active pipeline stage with the count of open opportunities in that stage and their combined estimated value.

### Weighted forecast

The forecast section calculates expected revenue for a date range, weighting each opportunity's estimated value by its win probability. Set the **From** and **To** dates and click **Apply**.

### Win-rate and cycle-time KPIs

The KPI panel shows:
- **Win Rate** — the percentage of closed opportunities marked Won in the selected period.
- **Average Cycle Time** — the average number of days from opportunity creation to close.

Set the date range and click **Apply** to recalculate.

---

**Example — Reading the pipeline board and setting a forecast:**

Branch manager Zawadi Ngowi opens the **CRM › Pipeline Dashboard** (`/admin/crm/pipeline`), selects company `Kijenge Trading Ltd` and branch `DSM Main`. The board shows:

| Stage | Open deals | Combined value |
|---|---|---|
| Qualification | 3 | TZS 8,200,000 |
| Needs Analysis | 5 | TZS 21,500,000 |
| Proposal | 4 | TZS 18,750,000 |
| Negotiation | 2 | TZS 9,600,000 |
| Closing | 1 | TZS 4,500,000 |

Zawadi sets From: `2026-07-01`, To: `2026-09-30` and clicks **Apply** on the Forecast panel. The weighted forecast shows TZS 29,340,000 (each deal's estimated value × its win probability). The KPI panel shows Win Rate: 62% and Average Cycle Time: 34 days for deals closed in Q2 2026.

---

## Pipeline Stages (Settings)

Navigate to **CRM › Pipeline Stages** (`/admin/crm/settings/pipeline-stages`). **Permission to view the settings screen:** `CRM.STAGE.MANAGE` | **Permission to read stages via API:** `CRM.OPPORTUNITY.VIEW`

Pipeline stages define the steps in your sales process. Five stages are seeded per company: Qualification, Needs Analysis, Proposal, Negotiation, and Closing. You can add, rename, reorder, change probabilities, and deactivate stages.

### How to create a stage

1. Navigate to **CRM › Pipeline Stages** (`/admin/crm/settings/pipeline-stages`).
2. Click **New Stage**.
3. Enter the **Name** (must be unique within the company).
4. Enter the **Display Order** (a number; must be unique within the company).
5. Enter the **Default Probability** (0–100).
6. Click **Submit**.

### How to edit a stage

Click **Edit** on a row. Change the name, display order, default probability, or the **Active** toggle. Click **Save**.

### How to deactivate a stage

Click **Deactivate** (or use the Active toggle in the edit form). The stage record is kept but marked inactive. Inactive stages:
- No longer appear in the stage selection dropdowns when creating or advancing an opportunity.
- Are rejected if you attempt to use them via the API.
- Still appear in historical records.

Deactivation is not permanent — you can reactivate a stage by editing it and switching Active back on.

### Stage validation rules

- Name must be unique within the company.
- Display order must be a positive number and unique within the company.
- Default probability must be between 0 and 100 (whole number).

---

## Activities

Navigate to **CRM › CRM Activities** (`/admin/crm/activities`) for the open-task inbox. Activities are also embedded on Lead and Opportunity detail pages.

**View activities:** `CRM.ACTIVITY.VIEW` | **Log / complete activities:** `CRM.ACTIVITY.MANAGE`

An activity records an interaction or a task related to a lead or opportunity. Every activity is attached to exactly one parent: either a lead or an opportunity — not both, and not neither.

### Activity types

| Type | Has due date | Can be completed |
|---|---|---|
| Call | No | No |
| Email | No | No |
| Meeting | No | No |
| Note | No | No |
| Task | Yes (required) | Yes |

Only **Task** activities appear in the open-task inbox. Only Tasks can be completed.

### How to log an activity on a lead or opportunity

1. Open the lead (`/admin/crm/leads/uid/:uid`) or opportunity (`/admin/crm/opportunities/uid/:uid`) detail page.
2. Scroll to the **Activity** panel.
3. Click **Log Activity**.
4. Select the **Type** (Call, Email, Meeting, Note, or Task).
5. Enter the **Subject** (required).
6. Optionally enter a **Body** / notes and an **Occurred At** date.
7. If Type is **Task**, enter the **Due Date** (required for Tasks).
8. Click **Submit**.

The activity appears at the top of the panel list (latest first), and the system assigns an activity number (for example, `ACT-0001`).

### Activity panel pagination

The activity panel on a lead or opportunity detail page shows 10 activities per page. Use the paginator controls to move between pages if there are more than 10.

### How to complete a task

A task can be completed from the open-task inbox or from the activity panel on the parent lead or opportunity.

1. Find the task (either on the detail page or in **CRM › CRM Activities** at `/admin/crm/activities`).
2. Click **Complete** on the task row.

The task is marked done and disappears from the open-task inbox. You cannot complete an activity that is not a Task, and you cannot complete a Task that is already done.

### Open-task inbox

Navigate to **CRM › CRM Activities** (`/admin/crm/activities`). **Permission:** `CRM.ACTIVITY.VIEW` (view) / `CRM.ACTIVITY.MANAGE` (complete).

The CRM Activities screen lists all open (not-yet-done) Tasks for the selected company, across all leads and opportunities. It is scoped to the company you select; you can optionally filter by assignee.

The list is paginated (20 per page). Use the paginator controls to browse. When you complete a task, it is removed from the inbox and the list refreshes.

---

**Example — Log activities across the sales journey and manage the task inbox:**

Sales rep Farida Hassan is managing opportunity `OPP-0012` (Banda Office Solutions). She logs activities at each step.

1. After the initial qualification call, she opens `OPP-0012` at `/admin/crm/opportunities/uid/:uid`, scrolls to the Activity panel, clicks **Log Activity**: Type `Call`, Subject `Initial qualification call — confirmed budget TZS 4.5M`, Occurred At `2026-07-03`. Clicks **Submit**. Activity `ACT-0018` appears.
2. She sends a proposal by email: Type `Email`, Subject `Proposal email sent — 5 desks + 20 chairs`, Occurred At `2026-07-10`.
3. After the proposal, she needs a follow-up. She creates a task: Type `Task`, Subject `Follow up on proposal — confirm decision`, Due Date `2026-07-17`. Activity `ACT-0021` created.
4. On 2026-07-17, Farida opens **CRM › CRM Activities** (`/admin/crm/activities`). She sees `ACT-0021` in the open-task inbox. After a productive call, she clicks **Complete**. The task disappears from the inbox.
5. A meeting is later held: back on `OPP-0012`, Type `Meeting`, Subject `Site visit — DSM Main showroom`, Occurred At `2026-07-22`. The activity panel shows all four interactions, newest first.
