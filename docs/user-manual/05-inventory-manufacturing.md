# Inventory and Manufacturing

This chapter covers all inventory screens and the manufacturing (Bill of Materials and Work Orders) screens. All screens are available from the **Inventory** and **Manufacturing** groups in the left-hand navigation.

---

## 1. Permissions quick reference

Before starting, confirm that the required permission codes have been granted to your role.

| Task | Permission code required |
|---|---|
| View stock on-hand | `STOCK.VIEW` |
| Record adjustments / set reorder levels | `STOCK.ADJUST` |
| Record opening balances | `STOCK.OPENING` |
| View stock locations | `STOCK.LOCATION.VIEW` |
| Manage stock locations | `STOCK.LOCATION.MANAGE` |
| View stock transfers | `STOCK.TRANSFER.VIEW` |
| Create / dispatch / cancel transfers | `STOCK.TRANSFER.CREATE` |
| Receive a transfer | `STOCK.TRANSFER.RECEIVE` |
| View stock counts | `STOCK.COUNT.VIEW` |
| Create / enter / cancel stock counts | `STOCK.COUNT.CREATE` |
| Post a stock count | `STOCK.COUNT.POST` |
| View expiring batches | `INVENTORY.EXPIRY.VIEW` |
| View inventory valuation report | `INVENTORY.VALUATION.VIEW` |
| Set opening valuation | `INVENTORY.OPENING.SET` |
| View / manage Bills of Materials | `BOM.VIEW` / `BOM.MANAGE` |
| View Work Orders / cost report / WIP | `MANUFACTURING.VIEW` |
| Create / edit / cancel Work Orders | `WORKORDER.MANAGE` |
| Release a Work Order | `WORKORDER.RELEASE` |
| Complete / close a Work Order | `WORKORDER.CLOSE` |

Navigation items are hidden when the corresponding permission is absent. Attempting to access a route directly without the permission shows a **Forbidden** message.

---

## 2. Stock on-hand

### 2.1 Viewing the on-hand list

Navigate to **Inventory > Stock On-Hand** (`/admin/stock`).

The table shows every stockable product that has had at least one movement at the active branch. Each row contains the product code, product name, unit of measure, quantity on-hand (to three decimal places), reorder level, and two derived flags:

- **Negative** — the quantity has gone below zero (an overselling indicator; the system does not hard-block it).
- **Low stock** — the quantity is at or below the reorder level. This flag is blank when no reorder level has been set.

**Filtering and pagination.** Use the search box to filter by product name or code (the list refreshes after a short pause). Use the paginator controls (First, Previous, page numbers, Next, Last) to move between pages. The paginator hides itself when there is only one page.

**Switching views.** The list offers three view modes via a toggle at the top:

- **By product** (default) — one row per product, summed across all locations.
- **By location** — one row per product-location combination. A branch must be selected.
- **By product (single product drill-down)** — pick a product from the search picker to see its quantity broken down by every location holding it.

### 2.2 Recording a manual adjustment

Use an adjustment to correct a stock quantity that is wrong for any reason other than a physical count (which has its own workflow — see section 5).

1. On the on-hand list, find the product row and click **Adjust**.
2. The product is pre-selected. Choose the **Reason** from the dropdown:

| Reason code | When to use |
|---|---|
| `COUNT_CORRECTION` | Correcting after an informal count |
| `DAMAGE` | Goods physically damaged |
| `SHRINKAGE` | Unexplained loss |
| `EXPIRY` | Goods past their expiry date |
| `RECEIPT_CORRECTION` | Correcting a goods-receipt error |
| `OTHER` | Any other reason — add a note |

3. Enter the **Quantity** — positive to increase, negative to decrease.
4. Optionally add a free-text note.
5. Click **Submit**.

The system creates a new stock movement (`ADJUSTMENT`) and reloads the on-hand list. Adjustments are permanent records; they cannot be deleted or edited after posting.

**Validation.** Quantity must be non-zero. Reason is required. A role with only `STOCK.VIEW` cannot see the Adjust button; a direct API call returns 403.

### 2.3 Recording an opening balance

An opening balance sets the initial quantity for a product that has never had any movement at this branch. Use this task at go-live or when adding a new branch or product.

1. On the on-hand list, click **Opening Balance**.
2. Pick the product from the picker (search by name or code).
3. Enter the quantity. Must be greater than zero.
4. Optionally add a note such as `go-live`.
5. Click **Submit**.

**Important.** The system rejects an opening balance if the product already has any prior movement at the active branch. A second opening balance on the same product at the same branch is not permitted. To adjust existing stock, use the Adjust flow (section 2.2).

### 2.4 Setting a reorder level

A reorder level triggers the Low Stock flag when the on-hand quantity reaches or falls below it.

1. On the on-hand list, click the inline edit icon in the **Reorder Level** column.
2. Enter a positive number and save. To remove the reorder level, clear the field and save.

The Low Stock flag recalculates immediately after saving.

### 2.5 Viewing movement history

Click **Movements** on a product row to open the movement ledger drawer. Movements are displayed in chronological order with:

- Movement type (Goods Receipt, Sale Issue, Adjustment, Opening Balance, Transfer In/Out, etc.)
- Direction (IN or OUT)
- Signed quantity
- Date and time
- Reason code or note where applicable

The drawer has its own paginator. Movements are append-only records; there is no edit or delete.

---

## 3. Stock locations

Navigate to **Inventory > Stock Locations** (`/admin/stock/locations`).

A stock location is a named physical area within a branch where stock is stored. Every stock movement and count is associated with a location.

### Location types

| Type | Typical use |
|---|---|
| `WAREHOUSE` | Main storage area |
| `STORE` | Shop floor / retail |
| `VAN` | Mobile / vehicle storage |
| `QUARANTINE` | Held goods pending inspection |
| `OTHER` | Any other purpose |

### 3.1 Creating a location

1. Click **Create**.
2. Enter a short **Code** (up to 30 characters, unique within the branch) and a **Name** (up to 120 characters).
3. Choose the **Location Type**.
4. Pick the **Branch** from the picker.
5. Tick **Make default** if this should be the primary location for the branch. There can be only one default location per branch — making a new location the default automatically clears the prior one.
6. Click **Submit**.

New locations are created in **Active** status.

### 3.2 Editing a location

Click the edit icon on a row. You can change the name and location type. The code is not editable after creation.

### 3.3 Marking as default

Click **Set default** on any active, non-default location. The previous default is cleared automatically.

### 3.4 Deactivating and reactivating

Click **Deactivate** to set the location to **Inactive**. It no longer appears in pickers used by transfers and counts. Click **Reactivate** to restore it to Active.

Locations are never hard-deleted. An Inactive location and its history remain in the list (visible with the status filter).

---

## 4. Stock transfers

Navigate to **Inventory > Stock Transfers** (`/admin/stock-transfers`).

A stock transfer moves stock from one location to another. Two modes are available:

- **Instant** — for transfers between two locations within the same branch. Stock moves in a single step.
- **In-transit** — for transfers that cross branches. The transfer follows a Dispatch → Receive workflow; stock is in transit between the two events.

### Transfer status lifecycle

```
DRAFT
  |-- (Instant mode)  --> COMPLETED
  |-- (In-transit mode) -> DISPATCHED --> RECEIVED
  \-- (any mode, before dispatch) --> CANCELLED
```

### 4.1 Creating a transfer

1. Navigate to **Stock Transfers > Create** (`/admin/stock-transfers/create`).
2. Pick the **Source Branch** and **Source Location**.
3. Pick the **Destination Branch** and **Destination Location**. Source and destination must be different locations.
4. Set the **Transfer Date**.
5. Choose the **Transfer Mode**: Instant (same-branch) or In-transit (cross-branch).
6. Click **Add Line** for each product to transfer. Pick the product by name and enter the quantity.
7. Click **Submit**.

The transfer is created with status **Draft** and a system-generated transfer number. The screen navigates to the transfer detail.

**Validation.** Source and destination locations must differ. At least one line is required. Quantity per line must be positive. Transfer date is required.

### 4.2 Dispatching an in-transit transfer

On a Draft, In-transit transfer, click **Dispatch**. The status changes to **Dispatched** and the source location's stock decreases immediately.

The Dispatch button is only available when the transfer is in Draft status and the mode is In-transit. Dispatching requires the `STOCK.TRANSFER.CREATE` permission.

### 4.3 Receiving an in-transit transfer

On a Dispatched transfer, the destination operator clicks **Receive**. The status changes to **Received** and the destination location's stock increases.

Receiving requires the `STOCK.TRANSFER.RECEIVE` permission. This allows organisations to separate the dispatcher and receiver roles.

### 4.4 Completing an instant transfer

On a Draft, Instant transfer, click **Complete instant**. The transfer completes in a single step; both locations update simultaneously.

### 4.5 Cancelling a transfer

On a Draft transfer, click **Cancel**. The status changes to **Cancelled** and no stock movement is recorded. Only Draft transfers can be cancelled.

### 4.6 Viewing the transfer list and detail

The list shows transfer number, source and destination, transfer date, mode, and status. Click any row to open the detail view. The transfer is referenced by its human transfer number throughout the UI; the internal identifier appears only in the browser address bar.

---

## 5. Stock counts

Navigate to **Inventory > Stock Counts** (`/admin/stock-counts`).

A stock count records what is physically present at a location and reconciles it against the system quantity. Any variance is posted as a stock adjustment and a GL entry.

### Count status lifecycle

```
COUNTING --> POSTED
     \-----> CANCELLED
```

When a count is created the system immediately freezes the on-hand quantities (the snapshot) and moves the document to **Counting** status.

### 5.1 Creating a stock count

1. Navigate to **Stock Counts > Create** (`/admin/stock-counts/create`).
2. Select the **Company** and **Branch**.
3. Pick the **Location** from the picker.
4. Set the **Count Date** (defaults to today).
5. Choose the **Count Type**:
   - **Full** — all products held at the location are included.
   - **Cycle** — a subset of products. Use the product pickers to choose which products to count.
6. Click **Submit**.

The count is created with status **Counting** and a system-generated count number. The system records the current on-hand quantity for each product as the **System Qty** snapshot. This snapshot is frozen and cannot change.

### 5.2 Entering counted quantities

Open the count detail. For each product line:

1. Enter the physically counted quantity in the **Counted Qty** column.
2. Optionally choose a reason code for lines that have a variance.
3. Click **Enter / Save**.

The **Variance** column shows `Counted Qty − System Qty`. A positive variance means more stock was found than expected; a negative variance means less was found.

The document stays in Counting status after saving. You can enter counts in multiple sessions.

### 5.3 Posting a count

Posting creates stock adjustment movements for every line with a variance and generates a single GL variance journal.

1. Open a count in Counting status.
2. Click **Post**.
3. Enter the **Posting Date**.
4. Confirm.

Posting requires the `STOCK.COUNT.POST` permission (typically held by an accountant or supervisor). After posting, the document is read-only.

### 5.4 Cancelling a count

Open a Counting count and click **Cancel**. No stock movements or GL entries are created. A Posted count cannot be cancelled. If corrections are needed after posting, create a new count.

---

## 6. Batches and lot tracking

Navigate to **Inventory > Stock Batches** (`/admin/stock/batches`).

Batches (lots) are created automatically when lot-tracked products are received. This screen provides a read-only view; you cannot create or edit batches directly.

### 6.1 Viewing batches by location and product

1. Pick a **Location** from the picker.
2. Pick a **Product** from the picker.
3. The table lists all batches at that location for that product, showing lot number, manufacture date, expiry date, quantity on-hand, and an expiry flag.

### 6.2 Expiring batches report

Click the **Expiring Soon** tab. Set a horizon date (default: 30 days from today). The report lists all batches whose expiry date falls on or before the horizon and whose quantity is greater than zero.

- Batches already past their expiry date are flagged in red.
- Batches expiring before the horizon are flagged as a warning.

The expiring batches tab requires the `INVENTORY.EXPIRY.VIEW` permission.

> **Known limitation.** The batch detail screen (`STOCK.BATCH.VIEW`) and serial detail screen (`STOCK.SERIAL.VIEW`) are accessible to superuser (`rootadmin`) only on seeded data. ORG_ADMIN and other roles will see a Forbidden message on those views until a permission-code fix is deployed. The Expiring Soon tab is unaffected and works for ORG_ADMIN.

---

## 7. Serial number tracking

Navigate to **Inventory > Stock Serials** (`/admin/stock/serials`).

Serial numbers are assigned to individual units of serialised products. This screen is read-only; serials are created and updated by the purchasing, sales, and transfer flows.

### 7.1 Viewing serials by location

1. Select **By Location** mode.
2. Pick a **Location** and a **Product**.
3. Optionally filter by **Status**: All, In Stock, Issued, Returned.
4. The table shows serial number, current status, and the related documents.

### 7.2 Viewing serial history by product

Switch to **By Product** mode. Pick a product to see all of its serials across all statuses and locations.

### 7.3 Looking up a serial number

Switch to **Lookup** mode. Pick a product, then type the serial number and click **Look up**. The system returns the current status and location, or shows a Not Found message if the serial does not exist for that product.

---

## 8. Inventory valuation

### 8.1 Valuation report

Navigate to **Inventory > Valuation** (`/admin/stock/valuation`). Requires the `INVENTORY.VALUATION.VIEW` permission.

The report shows every stockable product with its average cost, quantity, and calculated inventory value. A reconciliation bar at the top compares the sum of on-hand values (the stock ledger) against the GL inventory account balance:

- **Reconciled to GL** (green) — the stock ledger and GL agree.
- **Does not reconcile** (red) — there is a discrepancy. The difference amount is shown. Finance review is required.

### 8.2 Setting an opening valuation

Navigate to **Inventory > Opening Valuation** (`/admin/stock/valuation/opening`). Requires the `INVENTORY.OPENING.SET` permission.

Use this screen to assign an initial cost to products that have a quantity on-hand but no established average cost.

1. The screen lists all on-hand rows that are currently unvalued.
2. Find the product row and enter the **Opening Cost per unit**.
3. Click **Submit**.

The system posts a GL entry (DR Inventory / CR Opening Balance Equity) and the product's average cost is established. Opening valuation is a one-time operation per on-hand row. Once a row has been valued it no longer appears on this screen.

---

## 9. Bills of Materials

Navigate to **Manufacturing > Bills of Materials** (`/admin/boms`).

A Bill of Materials (BOM) defines what components and quantities are needed to produce a given quantity of a finished product. One BOM can be active at a time for each finished product; creating and activating a new BOM version automatically archives the previous one.

### BOM status lifecycle

```
DRAFT --> (activate) --> ACTIVE --> (archive) --> ARCHIVED
```

Only a DRAFT BOM can be activated. ARCHIVED is a permanent terminal state.

### 9.1 Creating a BOM

1. On the BOM list, click **New BOM**.
2. Pick the **Finished Product** from the picker (search by name or code). The product must be a GOODS type and must be active.
3. Enter the **Output Quantity** (how many units the BOM produces per run) and optionally the **Yield %** (default 100%).
4. Optionally add notes.
5. Click **Submit**.

The BOM is created in **Draft** status with the next version number for that finished product (v1 for the first BOM, v2 for the next, etc.).

**Validation.** Output quantity must be positive. Yield must be between 0.0001% and 100%.

### 9.2 Adding components

Open a Draft BOM detail and click **Add Component**.

1. Pick the **Component Product** from the picker.
2. Enter **Qty Per** (quantity of the component per one run of the BOM output).
3. Optionally enter **Scrap %** (allowance for waste, 0–99.9999%).
4. Optionally enter a **Reference** (for engineering cross-reference).
5. Choose **Sourcing**:
   - **Auto (derive)** — the system determines whether the component is made internally (MAKE) or purchased (BUY) based on whether it has an Active BOM.
   - **MAKE** — the component is itself manufactured.
   - **BUY** — the component is purchased from a supplier.
6. Click **Submit**.

Components can be added, edited, or removed only while the BOM is in Draft status.

### 9.3 Editing a BOM header

On a Draft BOM detail, click **Edit**. You can change Output Quantity, Yield %, and Notes. On an Active BOM only Notes can be changed; structural fields are frozen.

### 9.4 Activating a BOM

A BOM must have at least one component before it can be activated.

1. Open a Draft BOM.
2. Click **Activate**.
3. Enter an **Effective From** date.
4. Confirm.

Activating a BOM automatically archives the current Active BOM (if any) for the same finished product. Only one BOM can be Active per product at a time.

**Validation.** Effective From date is required. The BOM must have at least one component. A circular BOM (where a component's BOM ultimately references this product back) is rejected.

### 9.5 Archiving a BOM

On a Draft or Active BOM, click **Archive**. The BOM moves to Archived status permanently. Header and component editing controls disappear.

---

## 10. Work Orders

Navigate to **Manufacturing > Work Orders** (`/admin/work-orders`).

A Work Order authorises the production of a specified quantity of a finished product and tracks the cost of materials, labour, and overhead consumed.

### Work Order status lifecycle

```
PLANNED --> (release) --> RELEASED --> (first issue) --> IN_PROGRESS
        --> (cancel)  --> CANCELLED
                                  \--> (complete) --> COMPLETED
                                                  \--> (close) --> CLOSED
                                  \--> (cancel)   --> CANCELLED
```

CANCELLED, COMPLETED (after close), and CLOSED are terminal. A COMPLETED Work Order must be closed before any other action.

### 10.1 Creating a Work Order

1. On the Work Orders list, click **New Work Order**.
2. Pick the **Finished Product** from the picker.
3. Pick the **Branch** from the picker.
4. Enter the **Planned Quantity**.
5. Optionally pin a specific **BOM version** via the picker (if blank, the system uses the product's current Active BOM at release time).
6. Optionally enter a **Planned Date** and **Notes**.
7. Click **Submit**.

The Work Order is created in **Planned** status with a generated Work Order number.

### 10.2 Editing a Work Order

A Work Order can only be edited while in Planned status. Open the Work Order detail and click **Edit**. You can change the Planned Quantity, Branch, Planned Date, and Notes.

### 10.3 Adding and removing operations

Operations represent discrete production steps (e.g. Cutting, Assembly) with associated labour and overhead cost estimates. They can be added to a Work Order at any status before it is Closed or Cancelled.

- **Add operation**: Enter sequence number, description, work centre, and optional labour/overhead amounts. Click **Submit**.
- **Remove operation**: Click **Remove** on an operation row. An operation that has already had costs applied to it cannot be removed.

### 10.4 Releasing a Work Order

Releasing a Work Order locks the BOM and generates the component plan.

1. Open a Planned Work Order.
2. Click **Release**.
3. Optionally override the BOM via the picker.
4. Confirm.

Status changes to **Released**. The system emits a production event. No stock movements or GL entries are posted yet.

**Validation.** The finished product must have an Active BOM (or a BOM must be pinned). Releasing requires the `WORKORDER.RELEASE` permission.

### 10.5 Issuing components

Issuing deducts the component materials from stock and accumulates costs in the Work-in-Progress (WIP) account.

1. Open a Released or In-Progress Work Order.
2. Enter the **Posting Date**.
3. Click **Issue Components**.

The system issues all un-issued component lines simultaneously (full issue). Status moves to **In-Progress** on the first issue.

Stock movements of type `PRODUCTION_ISSUE` are posted for each component. GL entries: DR WIP / CR Inventory.

**Validation.** Posting date is required. If a component's average cost is not yet established, that component is cost-skipped (the quantity still moves but no GL leg is posted). An incomplete-cost indicator appears on the Work Order header when any component was cost-skipped.

### 10.6 Applying labour and overhead costs

1. Open a Released or In-Progress Work Order.
2. In the **Apply Cost** section, enter a **Labour Amount** and/or an **Overhead Amount** and a **Posting Date**.
3. Optionally link the cost to a specific operation via the Operation picker.
4. Click **Submit**.

GL entries: DR WIP / CR the relevant cost account. An operation can only have costs applied to it once; a second attempt is rejected.

### 10.7 Completing a Work Order

Completing records the finished goods receipt and calculates the unit cost.

1. Open an In-Progress Work Order.
2. In the **Complete** section, enter **Good Quantity** produced, **Scrap Quantity** (if any), and a **Posting Date**.
3. If the combined good and scrap quantities exceed the planned quantity, tick **Allow Over-run**.
4. Click **Submit**.

Status changes to **Completed**. A `PRODUCTION_RECEIPT` stock movement is posted for the finished goods. The computed unit cost is the total WIP debit divided by the good quantity. GL entries: DR Finished Goods / CR WIP.

**Validation.** Good quantity must be positive. If good + scrap exceeds planned quantity and Allow Over-run is not ticked, the submission is rejected.

### 10.8 Closing a Work Order

Closing clears any residual WIP balance (rounding or variance) and marks the order as final.

1. Open a Completed Work Order.
2. In the **Close** section, enter a **Posting Date**.
3. Click **Submit**.

Status changes to **Closed**. Any residual WIP is posted to the Manufacturing Variance account. GL entries: DR or CR Manufacturing Variance / CR or DR WIP (depending on sign).

### 10.9 Cancelling a Work Order

A Work Order can be cancelled from Planned, Released, or In-Progress status.

1. Open the Work Order.
2. Click **Cancel** and enter an optional reason.
3. Confirm.

If components have already been issued, the system reverses all issue movements and GL entries automatically (`PRODUCTION_ISSUE_REVERSAL`). Applied labour and overhead costs are also reversed. No reversal is needed for Planned Work Orders (nothing has moved).

A Completed or Closed Work Order cannot be cancelled.

### 10.10 Work Order cost report

From the Work Order detail, click **Cost Report** or navigate directly to `/admin/work-orders/uid/:uid/cost-report`.

The report shows:

| Section | Contents |
|---|---|
| Components | Planned vs actual component lines, quantity, unit cost, total value |
| Labour | Applied labour costs |
| Overhead | Applied overhead costs |
| WIP summary | Total WIP debits, total WIP credits, net WIP balance |
| Unit cost | Computed unit cost (WIP debit / good qty) |
| Variance | Residual variance (cleared at close) |

An incomplete-cost indicator appears when any component was cost-skipped.

---

## 11. WIP reconciliation

Navigate to **Manufacturing > WIP Reconciliation** (`/admin/manufacturing/wip-reconciliation`). Requires the `MANUFACTURING.VIEW` permission.

1. Select the **Company**.
2. The report compares the sum of open Work Order WIP balances (the manufacturing ledger) against the WIP Inventory GL account balance (account 1320).

A **Balanced** indicator means the two totals agree. A **Does Not Balance** alert means there is a discrepancy and a finance review is required.

---

## 12. Frequently asked questions

**Can I adjust stock below zero?**
Yes. The system records negative on-hand and flags the row with the Negative indicator, but it does not block the transaction. The overselling indicator is a monitoring tool; you should investigate and correct the root cause.

**What is the difference between an adjustment and a stock count?**
An adjustment corrects a single product's quantity immediately. A stock count covers all products at a location, freezes the system quantities as a snapshot, lets you enter physical counts across multiple sessions, and only posts variances when you explicitly post the count.

**Why do I see Forbidden on the Batches and Serials screens?**
There is a known permission-code mismatch in the current seed data. Only the superuser (`rootadmin`) can access the by-location and by-detail views for batches and serials until a fix is deployed. The Expiring Batches tab remains functional for ORG_ADMIN.

**Can I have more than one active BOM for a product?**
No. Only one BOM can be active at a time per product. Activating a new version automatically archives the previous one. Historical archived versions remain visible.

**Can I cancel a Work Order after it is completed?**
No. Once a Work Order reaches Completed status it can only be Closed. Use the Close action to clear any remaining WIP balance.
