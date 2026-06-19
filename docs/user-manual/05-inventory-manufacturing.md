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
| View stock batches (by-location / detail) | `STOCK.BATCH.VIEW` * |
| View stock serials (by-location / by-product / lookup) | `STOCK.SERIAL.VIEW` * |
| View expiring batches | `INVENTORY.EXPIRY.VIEW` |
| View inventory valuation report | `INVENTORY.VALUATION.VIEW` |
| Set opening valuation | `INVENTORY.OPENING.SET` |
| View / manage Bills of Materials | `BOM.VIEW` / `BOM.MANAGE` |
| View Work Orders / cost report / WIP | `MANUFACTURING.VIEW` |
| Create / edit / cancel Work Orders | `WORKORDER.MANAGE` |
| Release a Work Order | `WORKORDER.RELEASE` |
| Complete / close a Work Order | `WORKORDER.CLOSE` |

Navigation items are hidden when the corresponding permission is absent. Attempting to access a route directly without the permission shows a **Forbidden** message.

\* The Stock Batches and Stock Serials screens are gated on `STOCK.BATCH.VIEW` / `STOCK.SERIAL.VIEW`, but these two codes are **not present in the seeded permission catalogue** (it seeds `INVENTORY.BATCH.VIEW` / `INVENTORY.SERIAL.VIEW` instead). As a result no ordinary role can hold them, so today these screens are reachable by the superuser (`rootadmin`) only. See the Known limitation note in section 6 and the FAQ in section 12.

---

## 2. Stock on-hand

**What "stock on-hand" means.**
Stock on-hand is the quantity of a product that is physically present and available at a branch right now. It is the central fact the business needs to answer questions like "how many bags of cement do we have?", "can we fulfil this order?", and "are we running low on cooking oil?" The system maintains this number in real time: every goods receipt adds to it, every sale or delivery deducts from it, and every adjustment, transfer, or stock count correction changes it. The on-hand figure is in the product's base unit (e.g. kilogrammes, pieces, bags) and is accurate to three decimal places.

**Why it is maintained as a running balance, not derived from history.**
The system stores both a maintained on-hand balance and an append-only movement ledger. The maintained balance gives an instant O(1) answer to "what do we have right now" — crucial for fast sales processing and re-order decisions. The ledger provides the full history and lets the balance be independently verified (on-hand should always equal the sum of all movements). Both are always in sync: every movement updates both the ledger and the balance in the same database transaction, so they can never diverge.

### 2.1 Viewing the on-hand list

Navigate to **Inventory > Stock On-Hand** (`/admin/stock`).

The table shows every stockable product that has had at least one movement at the active branch. The columns are **Product** (code and name shown together), **Quantity** (on-hand, to three decimal places), **Reorder Level**, **Flags**, and an actions column. There is no separate unit-of-measure column. Two derived flags can appear in the **Flags** column:

- **Negative** — the quantity has gone below zero (an overselling indicator; the system does not hard-block it).
- **Low** — the quantity is at or below the reorder level. This flag is blank when no reorder level has been set.

**Filtering and pagination.** Use the search box to filter by product name or code (the list refreshes after a short pause). Use the paginator controls (First, Previous, page numbers, Next, Last) to move between pages. The paginator hides itself when there is only one page.

**Switching views.** The list offers three view modes via the tabs at the top:

- **On-Hand** (default) — one row per product, summed across all locations.
- **By Location** — one row per product-location combination. A branch must be selected.
- **By Product** — pick a product from the search picker to see its quantity broken down by every location holding it.

### 2.2 Recording a manual adjustment

**What a stock adjustment is.**
A stock adjustment is a direct correction to the on-hand quantity of a specific product when the physical stock and the system quantity do not agree for a reason other than a formal stock count. Adjustments are used for damage, spoilage, theft, unexplained shrinkage, or errors discovered after the fact. Every adjustment is permanent, carries a mandatory reason, and is reflected in the movement ledger immediately.

**Why adjustments exist separately from stock counts.**
An adjustment is a single-product, immediate correction — useful for fixing a known discrepancy right away without pausing all other operations. A stock count (section 5) is a systematic, multi-product reconciliation exercise at a location that freezes a snapshot and allows bulk entry across multiple sessions before committing. Use adjustments for one-off corrections; use stock counts for periodic reconciliation.

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
5. Click **Save**.

The system creates a new stock movement (`ADJUSTMENT`) and reloads the on-hand list. Adjustments are permanent records; they cannot be deleted or edited after posting.

**Validation.** Quantity must be non-zero. Reason is required. A role with only `STOCK.VIEW` cannot see the Adjust button; a direct API call returns 403.

### 2.3 Recording an opening balance

**What an opening balance is.**
An opening balance is the initial stock quantity entered for a product at a branch that has no prior movement history. It is the "starting point" for that product at that location — the quantity that existed before the system began tracking it. This is a one-time operation; once a product has any movement at a branch, its on-hand can only be changed by the normal transaction flows (receipts, sales, adjustments, etc.).

**When to use it.**
Opening balances are entered at go-live (when migrating from a previous system or manual records), when a new branch is opened, or when a new product is added and stock already exists that needs to be brought onto the books.

An opening balance sets the initial quantity for a product that has never had any movement at this branch. Use this task at go-live or when adding a new branch or product.

1. On the on-hand list, click **Opening Balance**.
2. Pick the product from the picker (search by name or code).
3. Enter the quantity. Must be greater than zero.
4. Optionally add a note such as `go-live`.
5. Click **Record**.

**Important.** The system rejects an opening balance if the product already has any prior movement at the active branch. A second opening balance on the same product at the same branch is not permitted. To adjust existing stock, use the Adjust flow (section 2.2).

### 2.4 Setting a reorder level

**What a reorder level is.**
A reorder level (also called a reorder point or minimum stock level) is the quantity at which a product should be reordered. When on-hand falls to or below the reorder level, the system flags the product row with the **Low Stock** indicator. This is a monitoring tool — the flag is a signal to the purchasing team to raise a requisition; it does not automatically place an order.

A reorder level triggers the Low Stock flag when the on-hand quantity reaches or falls below it.

1. On the on-hand list, click the inline edit icon in the **Reorder Level** column.
2. Enter a positive number and save. To remove the reorder level, clear the field and save.

The Low Stock flag recalculates immediately after saving.

### 2.5 Viewing movement history

**What the movement ledger is.**
The movement ledger is the append-only record of every quantity change for a product at a branch — every goods receipt, every sale issue, every adjustment, every transfer in or out, every opening balance, every production issue and receipt. It is the audit trail for on-hand. Because movements are append-only (never edited or deleted), the ledger is tamper-evident: the on-hand balance can always be recomputed by summing all movements.

Click **Ledger** on a product row to open the movement ledger drawer (its header reads **Ledger — <product>**). Movements are displayed in chronological order with:

- Movement type (Goods Receipt, Sale Issue, Adjustment, Opening Balance, Transfer In/Out, etc.)
- Direction (IN or OUT)
- Signed quantity
- Date and time
- Reason code or note where applicable

The drawer has its own paginator. Movements are append-only records; there is no edit or delete.

---

## 3. Stock locations

Navigate to **Inventory > Stock Locations** (`/admin/stock/locations`).

**What a stock location is.**
A stock location is a named physical area within a branch where stock is stored and counted. Locations let a business track stock at a finer level than the branch — for example, distinguishing between the main warehouse, the shop floor, a quarantine area for goods awaiting inspection, and a van for a mobile sales team. Every stock movement and stock count is associated with a specific location, so the system can answer not just "how many bags of cement does the Dar es Salaam branch have?" but "how many are in the Warehouse versus the Store?"

**Why locations exist.**
Without locations, the business knows only how much stock is at a branch in aggregate. With locations, it can see where exactly the stock is, which is essential for efficient warehousing, picking, physical counting, and segregating goods that should not be issued until inspected. Locations are also the boundary for stock counts — a count covers one location at a time.

### Location types

| Type | Typical use |
|---|---|
| `WAREHOUSE` | Main storage area |
| `STORE` | Shop floor / retail |
| `VAN` | Mobile / vehicle storage |
| `QUARANTINE` | Held goods pending inspection |
| `OTHER` | Any other purpose |

### 3.1 Creating a location

1. Click **New Location** (the button toggles to **Cancel** while the form is open).
2. Enter a short **Code** (up to 30 characters, unique within the branch) and a **Name** (up to 120 characters).
3. Choose the **Type**.
4. Pick the **Branch** from the picker.
5. Tick **Set as default** if this should be the primary location for the branch. There can be only one default location per branch — making a new location the default automatically clears the prior one.
6. Click **Create Location**.

New locations are created in **Active** status.

### 3.2 Editing a location

Click the edit icon on a row. You can change the name and location type. The code is not editable after creation.

### 3.3 Marking as default

In the row's Actions column, click the star icon button (its accessible label is "Set <code> as default") on any active, non-default location. The previous default is cleared automatically.

### 3.4 Deactivating and reactivating

In the row's Actions column, click the pause-circle icon button (accessible label "Deactivate <code>") to set the location to **Inactive**. It no longer appears in pickers used by transfers and counts. Click the play-circle icon button (accessible label "Reactivate <code>") to restore it to Active.

Locations are never hard-deleted. The list always shows every location, both Active and Inactive — there is no status filter, so an Inactive location and its history simply remain visible in the list.

---

## 4. Stock transfers

Navigate to **Inventory > Stock Transfers** (`/admin/stock-transfers`).

**What a stock transfer is.**
A stock transfer is a document that moves stock from one physical location to another — either between two locations within the same branch (for example, from the Warehouse to the Store) or between two different branches (for example, from the Arusha branch to the Dar es Salaam branch). A transfer records a physical movement of goods without buying or selling them; it re-attributes stock from one place to another.

**Why transfers do not affect the income statement.**
A transfer does not create a sale (no revenue, no COGS) and is not a purchase (no supplier, no invoice). It is a value-preserving internal movement: the cost of the goods is the same before and after the transfer — it has simply moved to a different location. Because the moving-average cost is maintained per product at the company level (not per location), the transfer does not change the financial value of inventory — it only re-attributes it between locations. No GL journal entry is posted for a standard same-cost-grain transfer.

**When transfers are used.**
Transfers are used when stock needs to be redistributed — to replenish a retail store from a warehouse, to move goods to a van for a field sales team, or to consolidate slow-moving inventory at one location. The `STOCK.TRANSFER.CREATE` permission is required to initiate and dispatch; `STOCK.TRANSFER.RECEIVE` is required to receive.

**How transfers flow — two modes.**
An **Instant** transfer is for moves between two locations within the same branch; it completes in a single step with no in-transit period. An **In-transit** transfer is for cross-branch moves: when dispatched, the source location's stock decreases immediately (goods are "in the truck") and the destination location's stock only increases when the destination operator confirms receipt. Between dispatch and receipt, the goods are "in transit" — not counted at either location. This two-step model prevents double-counting and gives each branch an accurate view of what it physically holds.

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
6. Click **Add line** for each product to transfer. Pick the product by name and enter the quantity.
7. Click **Create Transfer**.

The transfer is created with status **Draft** and a system-generated transfer number. The screen navigates to the transfer detail.

**Validation.** Source and destination locations must differ. At least one line is required. Quantity per line must be positive. Transfer date is required.

### 4.2 Dispatching an in-transit transfer

On a Draft, In-transit transfer, click **Dispatch**. The status changes to **Dispatched** and the source location's stock decreases immediately.

The Dispatch button is only available when the transfer is in Draft status and the mode is In-transit. Dispatching requires the `STOCK.TRANSFER.CREATE` permission.

**Insufficient source stock.** If the source location does not allow negative stock, dispatch is rejected (409 Conflict) when the available quantity at the source is less than the quantity being transferred — the message names the product, the available quantity, and the requested quantity. The transfer stays in Draft so you can correct the lines or top up the source. (A source location whose `allowNegative` flag is set will let the dispatch proceed and the source on-hand can go negative.)

### 4.3 Receiving an in-transit transfer

On a Dispatched transfer, the destination operator clicks **Receive**. The status changes to **Received** and the destination location's stock increases.

Receiving requires the `STOCK.TRANSFER.RECEIVE` permission. This allows organisations to separate the dispatcher and receiver roles.

### 4.4 Completing an instant transfer

On a Draft, Instant transfer, click **Complete (Instant)**. The transfer completes in a single step; both locations update simultaneously. As with dispatch, completion is rejected (409 Conflict) if the source location does not allow negative stock and the available quantity is less than the quantity being transferred.

### 4.5 Cancelling a transfer

On a Draft transfer, click **Cancel**. The status changes to **Cancelled** and no stock movement is recorded. Only Draft transfers can be cancelled.

### 4.6 Viewing the transfer list and detail

The list shows transfer number, transfer date, mode, status, source location, and destination location. To open the detail view, click the **View** action (eye icon) in the row's Actions column — there is no row-click. The transfer is referenced by its human transfer number throughout the UI; the internal identifier appears only in the browser address bar.

---

**Example — In-transit dispatch from Arusha Warehouse to DSM Store:**

Storekeeper Grace Mwenda at Arusha branch needs to send 200 bags of Pembe Flour and 50 cartons of Cooking Oil to the Dar es Salaam main store.

1. Navigate to **Inventory › Stock Transfers › Create** (`/admin/stock-transfers/create`).
2. Source Branch: `Arusha Branch`; Source Location: `Arusha Warehouse`.
3. Destination Branch: `DSM Branch`; Destination Location: `DSM Main Store`.
4. Transfer Date: `2026-06-10`; Transfer Mode: **In-transit**.
5. Add lines:
   - Product: `Pembe Flour 2kg (FLR-002)`, Qty: `200`.
   - Product: `Cooking Oil 3L (OIL-003)`, Qty: `50`.
6. Click **Create Transfer**. Transfer `TRF-0042` is created with status **Draft**.
7. Grace reviews the lines and clicks **Dispatch**. Status becomes **Dispatched**. The Arusha Warehouse stock for both items decreases immediately (200 bags and 50 cartons deducted).
8. The following day, DSM storekeeper Omari Njau opens **Inventory › Stock Transfers** (`/admin/stock-transfers`), finds `TRF-0042` with status Dispatched, and clicks the **View** action (eye icon) in its Actions column to open the detail.
9. Omari counts the physical delivery — both lines match — and clicks **Receive**. Status becomes **Received**. DSM Main Store stock increases by 200 bags and 50 cartons.
10. Both storekeepers can now see `TRF-0042` with status **Received** in the transfer list. No cancellation is possible at this stage.

---

## 5. Stock counts

Navigate to **Inventory > Stock Counts** (`/admin/stock-counts`).

**What a stock count is.**
A stock count (also called a physical inventory or stocktake) is a scheduled exercise where a team physically counts the items held at a location and compares the counted quantities to the quantities the system believes are there. Any discrepancy (a "variance") is recorded and — after review — posted as an adjustment to bring the system records into alignment with physical reality.

**Why stock counts are necessary.**
Even in a well-run warehouse, small discrepancies accumulate over time: items get damaged and not immediately reported, products are picked without being scanned, counting errors occur during receipts, or theft occurs. Without periodic counts, these discrepancies compound silently and the business makes decisions (ordering, sales commitments, valuations) based on wrong numbers. A stock count is the mechanism to catch and correct these discrepancies systematically. Unlike ad-hoc adjustments, a count involves a controlled snapshot of the entire location's stock at a point in time, multi-session entry, and formal posting — producing an auditable record of what was found versus what was expected.

**How variances affect the books.**
When a count is posted, each line with a variance is converted into a stock adjustment movement at the current moving-average cost. The value difference is posted to the `STOCK_ADJUSTMENT` expense account (5160) against the `INVENTORY` account (1300). A negative variance (less found than expected) is an expense; a positive variance is a credit to the adjustment account. This ensures the inventory balance on the balance sheet and the GL always stay in sync.

**When it is used.**
Stock counts are run periodically — monthly, quarterly, or annually depending on the business's risk appetite and the volatility of the products. **Full** counts cover all products at a location. **Cycle** counts cover a rotating subset of products (for example, high-value or fast-moving lines), allowing more frequent reconciliation without counting everything at once. The `STOCK.COUNT.CREATE` permission is needed to create and enter counts; `STOCK.COUNT.POST` (typically held by an accountant or supervisor) is needed to post the variances.

A stock count records what is physically present at a location and reconciles it against the system quantity. Any variance is posted as a stock adjustment and a GL entry.

### Count status lifecycle

```
COUNTING --> POSTED
     \-----> CANCELLED
```

When a count is created the system immediately freezes the on-hand quantities (the snapshot) and moves the document to **Counting** status.

### 5.1 Creating a stock count

1. Navigate to **Stock Counts > Create** (`/admin/stock-counts/create`).
2. Select the **Company** and **Branch**. (The Company selector appears only when you have access to more than one company, and the Branch selector only when the chosen company has more than one branch; otherwise the single company/branch is used automatically and no selector is shown.)
3. Pick the **Stock Location** from the picker.
4. Set the **Count Date** (defaults to today).
5. Choose the **Count Type**:
   - **Full** — all products held at the location are included.
   - **Cycle** — recorded as a cycle count for reporting/classification. The on-screen hint reads *"FULL = entire location. CYCLE = selected products."*

   > **Current limitation — cycle scoping.** The create screen has **no product picker**. It collects only Location, Count Date, Count Type, and Notes. Whether you choose **Full** or **Cycle**, the count is currently snapshotted over **all** products held at the location — there is no UI control to restrict a cycle count to a chosen subset of products. The **Count Type** value is stored and shown on the document, but it does not change which lines are created. To count only a few products, either run a full count and enter quantities for just those lines, or use a single-product Adjustment (section 2.2) instead.
6. Click **Create Count**.

The count is created with status **Counting** and a system-generated count number. The system records the current on-hand quantity for each product as the **System Qty** snapshot. This snapshot is frozen and cannot change.

### 5.2 Entering counted quantities

Open the count detail. For each product line:

1. Enter the physically counted quantity in the **Counted Qty** column.
2. Optionally type a free-text reason in the **Reason** box on lines that have a variance.
3. Click **Save Counted Qtys**.

After posting, the **Variance Qty** column shows `Counted Qty − System Qty`. A positive variance means more stock was found than expected; a negative variance means less was found.

The document stays in Counting status after saving. You can enter counts in multiple sessions.

### 5.3 Posting a count

Posting creates stock adjustment movements for every line with a variance and generates a single GL variance journal.

1. Open a count in Counting status.
2. Click **Post Count** to reveal the posting form.
3. Enter the **Posting Date**.
4. Click **Confirm Post**.

Posting requires the `STOCK.COUNT.POST` permission (typically held by an accountant or supervisor). After posting, the document is read-only.

### 5.4 Cancelling a count

Open a Counting count and click **Cancel Count**. No stock movements or GL entries are created. A Posted count cannot be cancelled. If corrections are needed after posting, create a new count.

---

**Example — Cycle count of sugar and rice with a variance posted:**

Accountant supervisor Boniface Kessy wants to reconcile two fast-moving products at the DSM Main Store. Because the create screen has no product picker (see the limitation note in section 5.1), the count snapshots every product at the location; Boniface simply leaves the other lines un-entered and enters counts only for the two products he is interested in.

1. Navigate to **Inventory › Stock Counts › Create** (`/admin/stock-counts/create`).
2. Company: `Kijenge Trading Ltd`; Branch: `DSM Branch`; Location: `DSM Main Store`; Count Date: `2026-06-12`; Count Type: **Cycle**.
3. Click **Create Count**.
4. Count `CNT-0009` is created with status **Counting**. The system records snapshot quantities for **every** product at DSM Main Store, including Sugar = 850 bags and Rice = 240 bags.
5. Storekeeper Omari Njau physically counts the two shelves he is responsible for. He opens `CNT-0009` and, leaving every other line blank, enters:
   - Sugar counted: `843` (variance: −7 bags).
   - Rice counted: `245` (variance: +5 bags).
   - In the **Reason** box on the Sugar line he types `SHRINKAGE`. For Rice no reason is needed (positive variance — unrecorded receipt correction).
   Click **Save Counted Qtys**.
6. Boniface reviews the variances and clicks **Post Count**. Posting Date: `2026-06-12`. Confirms. Lines with no counted quantity entered are treated as no-variance and post nothing.
7. The system posts two stock adjustment movements:
   - Sugar: −7 bags (ADJUSTMENT, reason SHRINKAGE).
   - Rice: +5 bags (ADJUSTMENT).
   A single GL variance journal posts: DR Inventory Variance / CR Inventory for the sugar loss (valued at moving-average cost); the rice surplus reverses this direction.
8. The count document is now read-only with status **Posted**. On-hand quantities at DSM Main Store are now: Sugar = 843, Rice = 245.

---

## 6. Batches and lot tracking

Navigate to **Inventory > Stock Batches** (`/admin/stock/batches`).

**What a batch (lot) is.**
A batch — also called a lot — is a group of units of the same product that were manufactured or received together and share the same identity attributes, most importantly an expiry date and a manufacture date. For example, a batch of medicines all manufactured on the same day with the same expiry date is one lot. Batch tracking allows the business to know exactly which physical batch a unit came from — critical for food, pharmaceutical, and chemical products where recall or expiry management is required.

**Why batch tracking matters.**
Without lot tracking, if a product recall is announced (for example, a contaminated batch of cooking oil), the business cannot identify which specific units on its shelves belong to the recalled batch. With batch tracking, the system can identify every unit of that batch, where it is held, and how much remains — enabling targeted removal without wasting uninvolved stock. Batch tracking is also necessary for FEFO (First Expired, First Out) stock rotation: the system ensures that stock with the earliest expiry date is issued first, minimising spoilage and waste.

**How batches are created.**
Batches are created automatically by the purchasing flow when lot-tracked products are received via a Goods Receipt — the system assigns a lot number and records the manufacture and expiry dates at that point. You cannot create batches manually on this screen.

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

> **Known limitation — Batches and Serials are superuser-only.** The Stock Batches and Stock Serials screens, and the routes and navigation links that lead to them, are gated on the permission codes `STOCK.BATCH.VIEW` and `STOCK.SERIAL.VIEW`. These two codes **do not exist in the seeded permission catalogue** — the catalogue instead seeds `INVENTORY.BATCH.VIEW` and `INVENTORY.SERIAL.VIEW`. Because no role (not even ORG_ADMIN, which is granted the entire catalogue) can hold a code that is not in the table, this affects **all** of these views — the by-location batch view, the by-location / by-product / lookup serial views, and the batch/serial detail lookups — not just the detail screens. For every non-root role the **Stock Batches** and **Serial Numbers** navigation links are hidden, and visiting the routes directly shows a Forbidden message. Only the superuser (`rootadmin`), who passes all permission checks, can open these screens. The Expiring Soon tab is gated separately on `INVENTORY.EXPIRY.VIEW` (which **is** seeded) and works for ORG_ADMIN. This permission-code fix has **not** yet been deployed.

---

## 7. Serial number tracking

Navigate to **Inventory > Stock Serials** (`/admin/stock/serials`).

**What serial number tracking is.**
Serial number tracking assigns a unique identifier to each individual unit of a product — for example, every laptop, refrigerator, or generator has its own serial number. Unlike batches (which group many units of the same type), a serial identifies one specific physical item. The system records where each serial is, whether it is in stock, has been issued to a customer, or has been returned, giving full unit-level traceability.

**Why it is used.**
Serial tracking is valuable for high-value items, warranty management, and theft prevention. When a laptop is sold, the system records which serial number left the warehouse and to which customer. If a customer returns a laptop claiming it is faulty, the system confirms whether that serial was genuinely sold to them. Serial numbers also help with insurance claims (proving what was held) and regulatory compliance.

**How serials are managed.**
Serial numbers are created and updated automatically by the purchasing (goods receipt), sales (delivery), and transfer flows. This screen is a read-only view and lookup tool; you cannot create or modify serials directly here.

Serial numbers are assigned to individual units of serialised products. This screen is read-only; serials are created and updated by the purchasing, sales, and transfer flows.

The screen has two view-mode tabs: **By Location** and **Product History**.

### 7.1 Viewing serials by location

1. Select the **By Location** tab.
2. Pick a **Location** and a **Product**.
3. Optionally filter by **Status**: All, IN_STOCK, ISSUED, RETURNED.
4. The table shows serial number, current status, and the related documents.

### 7.2 Viewing serial history by product

Switch to the **Product History** tab. Pick a product to see all of its serials across all statuses and locations.

### 7.3 Looking up a serial number

There is no separate lookup tab. In the **By Location** tab, once a product is selected, a **Lookup by serial #** panel appears above the table. Type the serial number and click **Lookup**. The system returns the current status and related documents, or shows a "Serial number not found for this product" message if the serial does not exist for that product.

---

## 8. Inventory valuation

**What inventory valuation is.**
Inventory valuation is the process of assigning a monetary value to the goods held in stock. The business needs to know not just how many units it has but what those units are worth — for the balance sheet (Inventory is an asset), for the cost of goods sold when items are sold (COGS reduces profit), and for management decisions (is this product profitable to sell?). The system uses the **moving-average cost method**: the average unit cost is recalculated each time stock is received, blending the new purchase cost with the existing average. This means all units of a product at a branch carry the same average cost, regardless of when they were purchased.

**How the moving average is maintained.**
When a goods receipt is posted, the system computes the new average as: `(existing stock value + new receipt value) / (existing quantity + received quantity)`. This weighted average is then applied to all units held. When goods are sold, the COGS is the quantity sold multiplied by the current average cost at the moment of the sale. When stock is adjusted, the adjustment value is computed at the current average. This means the Inventory account on the balance sheet always equals the sum of (on-hand quantity × average cost) across all products — a relationship the valuation report verifies.

### 8.1 Valuation report

Navigate to **Inventory > Stock Valuation** (`/admin/stock/valuation`). Requires the `INVENTORY.VALUATION.VIEW` permission.

The report is not loaded automatically — the initial screen shows an empty state ("Click Refresh to load the current valuation"). Click **Refresh** to build the report. It then shows every stockable product with its average cost, quantity, and calculated inventory value. A reconciliation bar at the top compares the sum of on-hand values (the stock ledger) against the GL inventory account balance:

- **Reconciled to GL** (green) — the stock ledger and GL agree.
- A red **Finance-grade alarm — Stock ledger and GL are out of sync** banner — there is a discrepancy. The stock total, the GL 1300 balance, and the difference amount are shown, and the GL Reconciliation card's status tag reads **Out of balance**. Finance review is required.

### 8.2 Setting an opening valuation

**What opening valuation is.**
Opening valuation is the one-time act of assigning an initial monetary cost to stock that already has a quantity on-hand but no established cost. This occurs at system go-live (when stock was loaded via opening balances before the cost data was entered) or when a new product is added and given an opening balance. Until an average cost is established, the system cannot post COGS for sales of that product — it will issue the stock but leave the cost leg blank, flagging the anomaly.

Navigate to **Inventory > Opening Valuation** (`/admin/stock/valuation/opening`). Requires the `INVENTORY.OPENING.SET` permission.

Use this screen to assign an initial cost to products that have a quantity on-hand but no established average cost. It is a single form, not a per-row entry table.

1. Pick a product from the **Product (unvalued on-hand rows)** dropdown — only products with on-hand quantity but no cost appear in it.
2. Enter the **Opening Unit Cost** for that product.
3. Click **Set Opening Valuation**.

The system posts a GL entry (DR Inventory / CR Opening Balance Equity) and the product's average cost is established. Opening valuation is a one-time operation per on-hand row. Once a row has been valued it no longer appears on this screen.

---

## 9. Bills of Materials

Navigate to **Manufacturing > Bills of Materials** (`/admin/boms`).

**What a Bill of Materials is.**
A Bill of Materials (BOM) is the formal recipe or formula that defines exactly what components — and in what quantities — are needed to produce one run of a finished product. For example, a BOM for "Ugali Pack 1kg" might specify 1.05 kg of maize flour (the extra 5% is scrap allowance), or a BOM for a piece of furniture might specify 2 pieces of timber, 4 bolts, 1 m² of fabric, and 200 g of adhesive. The BOM is the production blueprint; without it, a work order cannot know what to consume.

**Why BOMs are versioned.**
Products change: a recipe might be reformulated, a component supplier might change, or the manufacturing process might be improved. Each change requires a new BOM version. The system keeps all historical BOM versions so that an old production run can be reproduced exactly as it was originally planned — using the BOM that was active at the time the work order was released — even if the current BOM is different.

**When a BOM is used.**
A BOM is created and maintained by the production or engineering team. It becomes active when activated (with an effective-from date), at which point it can be referenced by Work Orders. Only one BOM can be active at a time per finished product — activating a new version automatically archives the previous one. A BOM must be set up before any Work Order for that finished product can be released.

**How it connects to manufacturing.**
When a Work Order is released, the system looks up the active BOM for the finished product, explodes it to its leaf components (recursively resolving any sub-assemblies), and materialises the planned component lines on the work order. The BOM is then pinned to that work order — subsequent changes to the BOM do not affect work orders that are already in progress.

A Bill of Materials (BOM) defines what components and quantities are needed to produce a given quantity of a finished product. One BOM can be active at a time for each finished product; creating and activating a new BOM version automatically archives the previous one.

### BOM status lifecycle

```
DRAFT --> (activate) --> ACTIVE --> (archive) --> ARCHIVED
```

Only a DRAFT BOM can be activated. ARCHIVED is a permanent terminal state.

### 9.1 Creating a BOM

1. On the BOM list, click **New BOM**.
2. Pick the **Parent Product** from the picker (its placeholder reads "Select finished product"; search by name or code). The product must be a GOODS type and must be active.
3. Enter the **Output Quantity** (how many units the BOM produces per run) and optionally the **Yield %** (default 100%).
4. Optionally add notes.
5. Click **Create BOM**.

The BOM is created in **Draft** status with the next version number for that finished product (v1 for the first BOM, v2 for the next, etc.).

**Validation.** Output quantity must be positive. Yield must be between 0.0001% and 100%.

### 9.2 Adding components

**What a BOM component is.**
A BOM component is one ingredient or raw material in the recipe. Each component line specifies the product to consume, the quantity required per one run of the BOM output, and an optional scrap percentage (an allowance for material that is consumed but does not make it into the finished good — for example, offcuts when cutting fabric). A component is classified as either **MAKE** (the component is itself manufactured — the system will look for its own BOM) or **BUY** (the component is purchased from a supplier and is a raw material).

Open a Draft BOM detail and click **Add Component**.

1. Pick the **Component Product** from the picker.
2. Enter **Qty Per** (quantity of the component per one run of the BOM output).
3. Optionally enter **Scrap %** (allowance for waste, 0–99.9999%).
4. Optionally enter a **Reference** (for engineering cross-reference).
5. Choose **Sourcing**:
   - **Auto (derive)** — the system determines whether the component is made internally (MAKE) or purchased (BUY) based on whether it has an Active BOM.
   - **MAKE** — the component is itself manufactured.
   - **BUY** — the component is purchased from a supplier.
6. Click **Add**.

Components can be added, edited, or removed only while the BOM is in Draft status.

### 9.3 Editing a BOM header

There is no Edit button. The **BOM Header** form is always shown inline on the BOM detail. On a Draft BOM its Output Qty, Yield %, and Notes fields are editable; on an Active BOM only Notes can be changed (the structural fields are disabled). Make your changes and click **Save Header**.

### 9.4 Activating a BOM

A BOM must have at least one component before it can be activated.

1. Open a Draft BOM.
2. Click **Activate**.
3. Enter an **Effective From** date.
4. Confirm.

Activating a BOM automatically archives the current Active BOM (if any) for the same finished product. Only one BOM can be Active per product at a time.

**Validation.** Effective From date is required. The BOM must have at least one component. A circular BOM (where a component's BOM ultimately references this product back) is rejected.

### 9.5 Archiving a BOM

On an Active BOM, click **Archive**. The **Archive** button is shown only while the BOM is Active — it does not appear on a Draft BOM. The BOM moves to Archived status permanently, and header and component editing controls disappear. (A Draft BOM that is never needed is simply left in Draft; archiving applies to Active BOMs, consistent with the DRAFT → ACTIVE → ARCHIVED lifecycle above.)

---

## 10. Work Orders

Navigate to **Manufacturing > Work Orders** (`/admin/work-orders`).

**What a Work Order is.**
A Work Order (WO) is the production document that authorises the manufacture of a specified quantity of a finished product. It is to manufacturing what a Purchase Order is to procurement: a formal instruction to produce. The work order drives the full production accounting cycle — it specifies what to make, what materials to consume, and how much labour and overhead to apply, and it records the cost of everything consumed in producing the finished goods.

**Why Work Orders are used.**
Without a Work Order, there is no formal record of what was produced, what materials were consumed, or what the finished goods cost. The business could not track whether production is efficient (planned versus actual component usage), could not correctly value the finished goods entering inventory, and could not identify variances between the standard (budgeted) cost and the actual cost. Work Orders also create the link between the BOM (the recipe) and the actual production run — allowing the system to issue the right components out of stock and receive the finished goods back into stock at their true cost.

**How the Work-in-Progress (WIP) account works.**
During production, costs accumulate in a temporary balance sheet account called **Work-in-Progress** (WIP). When components are issued from the storeroom to the production floor, their value moves: DR WIP / CR Inventory (components leave the warehouse, enter the production area). When labour and overhead costs are applied to the order, they also accumulate in WIP: DR WIP / CR the relevant cost account. When production is complete and the finished goods are received back into the warehouse, the accumulated WIP is relieved: DR Inventory (finished goods) / CR WIP. Any residual WIP at close (due to rounding or variance) is cleared to a Manufacturing Variance account. The net effect: raw materials enter, finished goods come out, and WIP returns to zero for a closed order.

**What COGS means in manufacturing context.**
When a manufactured finished good is later sold, the Cost of Goods Sold (COGS) posted by the sale is the moving-average cost of the finished good — which was set when the work order was completed (WIP divided by the good quantity produced). The COGS therefore reflects the actual cost of production, not just the purchase price of raw materials.

**When it is used.**
A Work Order is created by the production planner or manufacturing supervisor when a production run is scheduled. It is released (which locks the BOM and generates the component plan), components are issued from stock, labour and overhead are applied, the finished goods are completed and received into stock, and the order is closed. The lifecycle covers five states: PLANNED → RELEASED → IN_PROGRESS → COMPLETED → CLOSED.

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
4. Enter the **Planned Qty**.
5. Optionally pin a specific BOM by typing its UID into the **BOM UID (optional)** field — this is a plain text input (placeholder "pin specific BOM…"), not a picker. If blank, the system uses the product's current Active BOM at release time.
6. Optionally enter a **Planned Date** and **Notes**.
7. Click **Create Work Order**.

The Work Order is created in **Planned** status with a generated Work Order number.

### 10.2 Editing a Work Order

A Work Order can only be edited while in Planned status. There is no Edit button — while the order is Planned, the **Edit Work Order** form is shown inline on the detail page. You can change the Planned Qty, **BOM UID (override)**, Branch, Planned Date, and Notes, then click **Save**.

### 10.3 Adding and removing operations

**What operations are.**
Operations are the discrete steps in the production process — for example, Cutting, Mixing, Assembly, Finishing. Each operation can carry an estimated and actual labour cost and overhead cost, giving the business a breakdown of where production costs are incurred within the work order. Operations are optional; a work order can be costed with a single bulk labour/overhead application if step-level detail is not needed.

Operations represent discrete production steps (e.g. Cutting, Assembly) with associated labour and overhead cost estimates. They can be added to a Work Order at any status before it is Closed or Cancelled.

- **Add operation**: In the **Add Operation** form, enter Seq, Description, Work Centre, and optional Labour Amt / Overhead Amt. Click **Add**.
- **Remove operation**: Click the trash-icon button in the operation row's Actions column. An operation that has already had costs applied to it cannot be removed.

### 10.4 Releasing a Work Order

**What releasing means.**
Releasing a Work Order is the act of committing to produce. At this point the system resolves and locks the BOM (so the recipe is frozen for this production run), explodes it to all leaf-level raw material components, and generates the planned component lines on the work order — the list of what will need to be issued from stock. No stock movement or GL posting happens at release; it is a planning step. Once released, the work order is ready for component issue.

Releasing a Work Order locks the BOM and generates the component plan.

1. Open a Planned Work Order.
2. In the **Release Work Order** section, optionally override the BOM by typing its UID into the **BOM UID (optional override)** field — this is a plain text input (placeholder "leave blank for active BOM"), not a picker.
3. Click **Release**.

Status changes to **Released**. The system emits a production event. No stock movements or GL entries are posted yet.

**Validation.** The finished product must have an Active BOM (or a BOM must be pinned). Releasing requires the `WORKORDER.RELEASE` permission.

### 10.5 Issuing components

**What component issue means.**
Issuing components is the physical act of taking raw materials from the stock location and bringing them to the production area. In the system, this deducts the components from inventory and charges them to the Work-in-Progress account. The GL posting is: **DR WIP / CR Inventory** for each component at its current moving-average cost. If any component has no established average cost (it has never been received or opened), the quantity deduction still posts but the WIP cost leg is skipped and the incomplete-cost flag is set on the work order — the production team should investigate and correct the missing cost.

Issuing deducts the component materials from stock and accumulates costs in the Work-in-Progress (WIP) account.

1. Open a Released or In-Progress Work Order.
2. Enter the **Posting Date**.
3. Click **Issue All Components**.

The system issues all un-issued component lines simultaneously (full issue). Status moves to **In-Progress** on the first issue.

Stock movements of type `PRODUCTION_ISSUE` are posted for each component. GL entries: DR WIP / CR Inventory.

**Validation.** Posting date is required. If a component's average cost is not yet established, that component is cost-skipped (the quantity still moves but no GL leg is posted). An incomplete-cost indicator appears on the Work Order header when any component was cost-skipped.

### 10.6 Applying labour and overhead costs

**What labour and overhead costs are.**
Labour costs are the wages and salaries paid to the workers who produce the goods. Overhead costs are the indirect production costs that cannot be assigned to a single unit but are incurred as part of running the factory — energy, depreciation of machinery, supervision, etc. Both are debited to WIP when applied to a Work Order: **DR WIP / CR the relevant cost account**. Applying these costs ensures that the finished good's cost reflects all the inputs that went into making it, not just the raw materials.

1. Open a Released or In-Progress Work Order.
2. In the **Apply Labour / Overhead Cost** section, enter a **Labour Amount** and/or an **Overhead Amount** and a **Posting Date**.
3. Optionally link the cost to a specific operation via the Operation picker.
4. Click **Apply Cost**.

GL entries: DR WIP / CR the relevant cost account. An operation can only have costs applied to it once; a second attempt is rejected.

### 10.7 Completing a Work Order

**What completion does.**
Completing a Work Order records that production has finished and the finished goods are ready to move from the production area back into the finished goods warehouse. The system computes the unit cost of the finished good as: total WIP debited divided by the good quantity produced. This computed unit cost is passed to the moving-average recompute for the finished product — so the finished good acquires its average cost through the same engine that handles purchase receipts. The GL posting is: **DR Inventory (finished goods) / CR WIP** for the value relieved. Scrap (units produced but rejected) is recorded informationally; only good quantity enters inventory.

Completing records the finished goods receipt and calculates the unit cost.

1. Open an In-Progress Work Order.
2. In the **Complete Work Order** section, enter **Good Quantity** produced, **Scrap Quantity** (if any), and a **Posting Date**.
3. If the combined good and scrap quantities exceed the planned quantity, tick **Allow overrun**.
4. Click **Complete**.

Status changes to **Completed**. A `PRODUCTION_RECEIPT` stock movement is posted for the finished goods. The computed unit cost is the total WIP debit divided by the good quantity. GL entries: DR Finished Goods / CR WIP.

**Validation.** Good quantity must be positive. If good + scrap exceeds planned quantity and Allow overrun is not ticked, the submission is rejected.

### 10.8 Closing a Work Order

**What closing does.**
Closing a Work Order is the final step that clears any remaining WIP balance. After completion, there may be a small residual WIP balance due to rounding or small variances between the planned and actual costs. Closing posts this residual to the **Manufacturing Variance** account — a P&L account that captures the difference between what production was expected to cost (based on the BOM and standard costs) and what it actually cost. After closing, the WIP balance for this order is zero and the order is read-only.

Closing clears any residual WIP balance (rounding or variance) and marks the order as final.

1. Open a Completed Work Order.
2. In the **Close Work Order** section, enter a **Posting Date**.
3. Click **Close**.

Status changes to **Closed**. Any residual WIP is posted to the Manufacturing Variance account. GL entries: DR or CR Manufacturing Variance / CR or DR WIP (depending on sign).

### 10.9 Cancelling a Work Order

A Work Order can be cancelled from Planned, Released, or In-Progress status.

1. Open the Work Order.
2. Click **Cancel** and enter an optional reason.
3. Confirm.

If components have already been issued, the system reverses all issue movements and GL entries automatically (`PRODUCTION_ISSUE_REVERSAL`). Applied labour and overhead costs are also reversed. No reversal is needed for Planned Work Orders (nothing has moved).

**Why reversals are at the original issue cost.** The system reverses each component issue at the exact cost it was issued at (read from the original movement record), not at the current average. This ensures the cancellation is symmetric — the books return to their exact pre-issue state with no phantom gain or loss introduced.

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

**What WIP reconciliation is.**
The WIP reconciliation report is the manufacturing equivalent of the inventory valuation report's GL reconciliation bar. It compares the total WIP balance accumulated across all open Work Orders (RELEASED, IN_PROGRESS, and COMPLETED orders that have not yet been closed) against the WIP account balance (account 1320) in the General Ledger. They must agree at all times — if they do not, it means a posting was made to the WIP account that was not recorded on a Work Order, or vice versa, which indicates a data integrity problem requiring investigation.

1. Select the **Company**.
2. The report is not loaded automatically — click **Refresh** to run it. It compares the sum of open Work Order WIP balances (the manufacturing ledger) against the WIP Inventory GL account balance (account 1320).

When the two totals agree, a green **WIP balances reconcile — computed equals expected** banner is shown and the difference row carries a **Reconciled** status tag. When they do not, a red **Finance-grade defect: WIP per work orders does not match the WIP Inventory GL balance** alert is shown and the difference row carries a **Defect** status tag — a finance review is required.

---

## 12. Frequently asked questions

**Can I adjust stock below zero?**
Yes. The system records negative on-hand and flags the row with the Negative indicator, but it does not block the transaction. The overselling indicator is a monitoring tool; you should investigate and correct the root cause.

**What is the difference between an adjustment and a stock count?**
An adjustment corrects a single product's quantity immediately. A stock count covers all products at a location, freezes the system quantities as a snapshot, lets you enter physical counts across multiple sessions, and only posts variances when you explicitly post the count.

**Why do I see Forbidden on the Batches and Serials screens?**
There is a known permission-code mismatch in the current seed data. The screens and their routes/nav links require `STOCK.BATCH.VIEW` / `STOCK.SERIAL.VIEW`, but the seeded catalogue only contains `INVENTORY.BATCH.VIEW` / `INVENTORY.SERIAL.VIEW`, so no ordinary role can hold the required codes. As a result, **every** batch/serial view (by-location, by-product, lookup, and detail) is Forbidden for non-root roles and the nav links are hidden — only the superuser (`rootadmin`) can open them, until the fix is deployed. The Expiring Batches tab is gated on `INVENTORY.EXPIRY.VIEW` and remains functional for ORG_ADMIN.

**Can I have more than one active BOM for a product?**
No. Only one BOM can be active at a time per product. Activating a new version automatically archives the previous one. Historical archived versions remain visible.

**Can I cancel a Work Order after it is completed?**
No. Once a Work Order reaches Completed status it can only be Closed. Use the Close action to clear any remaining WIP balance.

**Why does the average cost change when I receive goods?**
The system uses a moving-average cost method. Each time goods are received, the new receipt cost is blended with the existing inventory value to produce a new weighted average: `(old value + receipt value) / (old quantity + received quantity)`. This means all units of a product always carry the same average cost, which changes with each new receipt.

**What happens to COGS if a product has no average cost?**
If a product has never been received and has no established average cost, the system will still issue it out of stock (the quantity deducts) but it will skip the COGS GL leg and flag the anomaly. You should use the Opening Valuation screen to establish the cost before selling costed goods.
