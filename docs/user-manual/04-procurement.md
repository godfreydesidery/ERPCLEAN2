# Procurement (Procure-to-Pay)

This chapter covers the full procure-to-pay (P2P) chain from raising a purchase request through to settling the supplier's invoice, including goods receipt, landed costs, purchase returns, and purchase settings.

---

## Overview

The P2P chain follows this path:

```
Purchase Requisition → RFQ → Supplier Quotes → Award → Purchase Order
    → Goods Receipt → [Landed Cost] → Supplier Bill → 3-Way Match → AP Payment
```

For direct purchases without a sourcing process, the chain can start at the Purchase Order (created from a converted requisition or from the RFQ award).

**Required permissions** — the navigation menu only shows items your role includes:

| Activity | Permission codes |
|---|---|
| Requisitions | `PURCHASE.REQUISITION.VIEW`, `PURCHASE.REQUISITION.CREATE`, `PURCHASE.REQUISITION.APPROVE` |
| RFQ | `PURCHASE.RFQ.VIEW`, `PURCHASE.RFQ.CREATE`, `PURCHASE.RFQ.AWARD` |
| Supplier Quotes | `PURCHASE.QUOTE.VIEW`, `PURCHASE.QUOTE.CREATE` |
| Purchase Orders | `PURCHASE.ORDER.VIEW`, `PURCHASE.ORDER.CREATE`, `PURCHASE.ORDER.VOID`, `PURCHASE.ORDER.APPROVE` |
| Goods Receipt | `PURCHASE.GOODS_RECEIPT.VIEW`, `PURCHASE.RECEIVE` |
| Landed Cost | `PURCHASE.LANDED_COST.VIEW`, `PURCHASE.LANDED_COST.CREATE`, `PURCHASE.LANDED_COST.CONFIRM` |
| Supplier Bills / AP | `AP.VIEW`, `AP.BILL.ENTER`, `AP.BILL.MATCH` |
| Purchase Returns | `PURCHASE.RETURN.VIEW`, `PURCHASE.RETURN.CREATE`, `PURCHASE.RETURN.CONFIRM` |
| Purchase Settings | `PURCHASE.SETTINGS.VIEW`, `PURCHASE.SETTINGS.EDIT` |

Contact your administrator if an expected menu item is missing.

---

## 1. Purchase Requisitions

Navigate to **Purchasing › Purchase Requisitions** (`/admin/purchase-requisitions`).

A purchase requisition is an internal request for goods or services. It must be approved before a purchase order or RFQ can be raised.

### 1.1 Create a requisition

1. Navigate to **Purchasing › Purchase Requisitions** (`/admin/purchase-requisitions`).
2. Click **New Requisition**, or go directly to `/admin/purchase-requisitions/create`.
3. Set the **Required By** date and optionally a cost centre and notes.
4. Add lines: for each item, pick the **Product** by name, choose a **Unit**, and enter the **Requested Quantity** and an **Estimated Unit Cost**.
5. Click **Save**. The requisition is saved in **DRAFT**.

### 1.2 Submit a requisition

When the requisition is complete and ready for approval:

1. Open the draft requisition (navigate to `/admin/purchase-requisitions/uid/{uid}`).
2. Click **Submit**.
3. The status changes to **SUBMITTED** and the requisition is routed for approval.

### 1.3 Approve or reject a requisition

An approver (a user with `PURCHASE.REQUISITION.APPROVE`) reviews submitted requisitions at **Purchasing › Purchase Requisitions** (`/admin/purchase-requisitions`).

- **Approve** — open the submitted requisition and click **Approve**. Status → **APPROVED**. The Convert action becomes available.
- **Reject** — click **Reject**, enter a mandatory reason, and confirm. Status → **REJECTED**. The requisitioner is notified via the audit trail.

### 1.4 Convert a requisition

An approved requisition can be converted into either a Purchase Order or an RFQ:

1. Open the approved requisition (navigate to `/admin/purchase-requisitions/uid/{uid}`).
2. Click **Convert**.
3. Choose the target type:
   - **Purchase Order** — a DRAFT PO is created immediately from the requisition lines.
   - **RFQ** — a DRAFT RFQ is created; proceed to section 2 to send it to suppliers.
4. Confirm. A link to the created document appears. The requisition status changes to **CONVERTED**.

### 1.5 Cancel a requisition

A requisition can be cancelled from any non-final status (DRAFT, SUBMITTED, APPROVED):

1. Open the requisition.
2. Click **Cancel**, enter an optional reason, and confirm. Status → **CANCELLED**.

### 1.6 Requisition status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared |
| SUBMITTED | Awaiting approval |
| APPROVED | Approved; ready to convert |
| REJECTED | Rejected by approver |
| CONVERTED | Converted to PO or RFQ |
| CANCELLED | Cancelled |

---

**Example — Requisition for office stationery:**

Store clerk Amani opens **Purchasing › Purchase Requisitions** (`/admin/purchase-requisitions`) and clicks **New Requisition**. He sets Required By **2026-06-20**, notes "Monthly stationery re-order", and adds two lines:

- Product **Karatasi A4 (Ream)**, Unit **REAM**, Qty **20**, Estimated Cost **TZS 8,500** each.
- Product **Kalamu Nyeusi**, Unit **BOX**, Qty **5**, Estimated Cost **TZS 3,200** each.

He saves — requisition **REQ-0072** is created in DRAFT. He clicks **Submit** — status → SUBMITTED.

Purchasing manager Neema opens the requisition and clicks **Approve** — status → APPROVED, estimated total TZS 186,000. She clicks **Convert** and picks **RFQ** — RFQ **RFQ-0031** is created in DRAFT.

---

## 2. RFQ (Request for Quotation)

Navigate to **Purchasing › RFQs / Sourcing** (`/admin/rfqs`).

An RFQ invites one or more suppliers to submit prices for a defined list of items.

### 2.1 Create an RFQ

An RFQ can be created directly or by converting an approved requisition (see section 1.4).

**To create directly:**

1. Navigate to **Purchasing › RFQs / Sourcing** (`/admin/rfqs`) and click **New RFQ**, or go to `/admin/rfqs/create`.
2. Set the **Response Due Date** and optionally add notes.
3. In the **Invited Suppliers** section, pick each supplier by name. Invite at least one supplier.
4. Add lines: pick each product by name, choose a unit, and enter the required quantity.
5. Click **Save**. The RFQ is created in **DRAFT**.

### 2.2 Send an RFQ to suppliers

1. Open the DRAFT RFQ (navigate to `/admin/rfqs/uid/{uid}`).
2. Click **Send**. Status → **SENT**. Suppliers are notified that they should submit a quote.

### 2.3 Capture supplier quotes

When a supplier responds with a price:

1. Open the SENT RFQ (navigate to `/admin/rfqs/uid/{uid}`).
2. Click **Capture Quote**.
3. Pick the **Supplier** by name (only invited suppliers are listed).
4. Optionally set a valid-until date, lead time in days, and notes.
5. For each RFQ line, enter the **Quoted Quantity** and **Unit Price**.
6. Click **Save**. The quote is recorded with status **RECEIVED** and the RFQ status moves to **QUOTES_RECEIVED**.

Repeat for each responding supplier. You can compare their prices side-by-side in the quotes panel on the RFQ detail page.

### 2.4 Award the RFQ

To select the winning supplier and create a Purchase Order:

1. In the quotes panel on the RFQ detail page (`/admin/rfqs/uid/{uid}`), identify the preferred quote (usually the lowest compliant price).
2. Click **Award** on that quote row.
3. The winning quote status changes to **AWARDED** and all other quotes become **NOT_AWARDED**. The RFQ status changes to **AWARDED**.
4. A **Purchase Order** is created in DRAFT from the awarded quote lines and prices. A link to the PO is shown.

### 2.5 Cancel an RFQ

Open the RFQ (navigate to `/admin/rfqs/uid/{uid}`) and click **Cancel**. Status → **CANCELLED**. An awarded RFQ cannot be cancelled.

### 2.6 RFQ status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared |
| SENT | Sent to suppliers; awaiting responses |
| QUOTES_RECEIVED | At least one supplier quote captured |
| AWARDED | Winning supplier selected; PO created |
| CANCELLED | Cancelled |

---

**Example — RFQ for cement (continuing from requisition example, fresh scenario):**

A warehouse requisition for 500 bags of **Saruji 50kg** has been approved and converted to RFQ **RFQ-0031**. Purchasing officer Zawadi opens **Purchasing › RFQs / Sourcing** (`/admin/rfqs`), opens RFQ-0031, and adds two invited suppliers: **Tanzania Cement Distributors** and **Simba Cement Ltd**. Response Due Date is set to **2026-06-17**. She clicks **Send** — RFQ goes to SENT.

Both suppliers respond. Zawadi captures two quotes:
- **Tanzania Cement Distributors**: 500 bags @ TZS 14,800 each = TZS 7,400,000 (lead time 3 days).
- **Simba Cement Ltd**: 500 bags @ TZS 14,500 each = TZS 7,250,000 (lead time 5 days).

After review, Zawadi awards the RFQ to **Simba Cement Ltd** (cheaper price, acceptable lead time). Purchase Order **PO-0088** is created in DRAFT at TZS 14,500/bag.

---

## 3. Purchase Orders

Navigate to **Purchasing › Purchase Orders** (`/admin/purchase-orders`).

A Purchase Order (PO) is the formal commitment to buy from a supplier. POs are created from a converted requisition or from an awarded RFQ. There is no standalone "New PO" form in the UI.

### 3.1 View and manage a DRAFT Purchase Order

1. Navigate to **Purchasing › Purchase Orders** (`/admin/purchase-orders`).
2. Open the DRAFT PO (navigate to `/admin/purchase-orders/uid/{uid}`).
3. While the PO is in DRAFT you can:
   - **Add a line** — pick the product by name, choose a unit, enter the ordered quantity and unit cost.
   - **Edit a line** — change quantity or cost on an existing line.
   - **Remove a line** — click the delete icon on the line row.

### 3.2 Place a Purchase Order

Placing the PO sends it to the supplier and locks the lines.

1. Open the DRAFT PO (it must have at least one line).
2. Click **Place**.
3. Status → **ORDERED** and a PO number (PO-####) is assigned.

### 3.3 Close a Purchase Order

Closing finalises the PO without receiving all goods (for example, if a partial shipment is accepted as complete).

1. Open the PO (navigate to `/admin/purchase-orders/uid/{uid}`) — status ORDERED, PARTIALLY_RECEIVED, or RECEIVED.
2. Click **Close**.
3. Status → **CLOSED**. The PO is read-only.

### 3.4 Void a Purchase Order

Voiding cancels the PO if goods have not all been received.

1. Open the PO (status DRAFT, ORDERED, or PARTIALLY_RECEIVED).
2. Click **Void**, enter a mandatory reason, and confirm.
3. Status → **VOID**.

### 3.5 PO approval (if enabled)

If your administrator has enabled PO approval thresholds in Purchase Settings, Purchase Orders above the configured amount enter a **PENDING** approval state after being placed. An approver with `PURCHASE.ORDER.APPROVE` must then approve or reject the PO before goods can be received.

PO approval actions are currently only available via the API; contact your administrator or a system manager if a PO is stuck awaiting approval.

### 3.6 PO status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; lines editable |
| ORDERED | Placed; sent to supplier; lines locked |
| PARTIALLY_RECEIVED | Some lines received; outstanding qty remains |
| RECEIVED | All lines fully received |
| CLOSED | Manually closed |
| VOID | Cancelled before full receipt |

---

**Example — Placing the cement PO:**

Zawadi opens **Purchasing › Purchase Orders** (`/admin/purchase-orders`), finds PO-0088 (DRAFT, 500 bags @ TZS 14,500), reviews the line, and clicks **Place**. Status → ORDERED. The formal PO number is confirmed and the document is locked for editing. A PDF can be generated and sent to Simba Cement Ltd.

---

## 4. Goods Receipt

Navigate to **Purchasing › Goods Receipts** (`/admin/goods-receipts`).

A goods receipt (GR) records the physical arrival of goods from the supplier. Creating a GR increases stock and updates the PO outstanding quantities.

### 4.1 Receive goods

1. Navigate to **Purchasing › Goods Receipts** (`/admin/goods-receipts`) and click **New Goods Receipt**, or go directly to `/admin/goods-receipts/create`.
2. Pick the **Purchase Order** by its PO number.
3. The form lists all open (unreceived) lines with the outstanding quantity pre-filled.
4. Adjust individual quantities if you are receiving a **partial shipment**. The quantity cannot exceed the outstanding balance on each line.
5. Set the **Receipt Date**.
6. Click **Submit**.

The goods receipt is created with status **RECEIVED** and assigned a GRN-#### number. Stock is added to the branch. The PO status updates:

- Partial receipt → PO status **PARTIALLY_RECEIVED**
- Full receipt → PO status **RECEIVED**

### 4.2 Partial receipts (multiple deliveries)

If the supplier delivers in stages, create a separate goods receipt for each delivery. Each GRN records the quantity received on that date. The PO tracks the cumulative received and outstanding quantities across all GRNs.

### 4.3 Goods receipt status reference

| Status | Meaning |
|---|---|
| RECEIVED | Active receipt; stock increased |
| VOID | Voided (reversed); stock decremented (API only) |

---

**Example — Receiving cement:**

Simba Cement delivers 500 bags on 2026-06-22. Storekeeper John opens **Purchasing › Goods Receipts** (`/admin/goods-receipts`), clicks **New Goods Receipt**, and picks PO **PO-0088**. The form shows 500 bags Saruji 50kg outstanding. John enters Receipt Date **2026-06-22** and keeps all 500 bags. He submits — GRN **GRN-0061** is created (status RECEIVED), 500 bags added to stock at the branch, PO-0088 status → RECEIVED.

**Partial receipt scenario:** If Simba had delivered only 300 bags on day 1, John would receive 300 bags (GRN-0061), PO → PARTIALLY_RECEIVED, outstanding = 200 bags. When the remaining 200 arrive, John creates GRN-0062 for 200 bags, PO → RECEIVED.

---

## 5. Landed Costs

Navigate to **Purchasing › Landed Costs** (`/admin/landed-costs`).

Landed costs allocate incidental import charges (freight, duty, insurance, clearing fees, and other charges) to the items received. Landed costs are applied to one or more goods receipts and allocated to individual GR lines.

### 5.1 Create a landed cost

1. Navigate to **Purchasing › Landed Costs** (`/admin/landed-costs`) and click **New Landed Cost**, or go directly to `/admin/landed-costs/create`.
2. Select the **Allocation Basis**:
   - **By Value** — charges are spread proportionally to the value of each GR line.
   - **By Quantity** — charges are spread proportionally to the quantity received on each GR line.
3. Pick the **Goods Receipt(s)** by GRN number. You can include multiple GRNs in one landed cost document.
4. Add one or more **Charges**: select the charge type (Freight, Duty, Clearing, Insurance, or Other) and enter the amount.
5. Click **Save**. The landed cost is created in **DRAFT**.

### 5.2 Confirm a landed cost

Confirming allocates the charges to the GR lines and posts the cost adjustment to the GL.

1. Open the DRAFT landed cost (navigate to `/admin/landed-costs/uid/{uid}`).
2. Click **Confirm**.
3. Status → **CONFIRMED**. The allocation per GR line is shown in the detail.

A confirmed landed cost cannot be edited. If there is an error, contact your administrator.

### 5.3 Landed cost status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; charges editable |
| CONFIRMED | Charges allocated; GL posted; immutable |

---

**Example — Landed cost for imported cement:**

The cement shipment also incurred TZS 850,000 in port clearing fees and TZS 600,000 in freight. Accountant Sarah opens **Purchasing › Landed Costs** (`/admin/landed-costs`), clicks **New Landed Cost**, selects Allocation Basis **By Quantity**, and picks GRN **GRN-0061** (500 bags). She adds two charges:

- Type **Clearing**, Amount TZS 850,000.
- Type **Freight**, Amount TZS 600,000.

Total landed cost TZS 1,450,000. She saves (DRAFT), reviews the per-bag allocation (TZS 2,900/bag), and clicks **Confirm**. Status → CONFIRMED. The moving-average cost for Saruji 50kg increases by TZS 2,900/bag, and the GL is posted accordingly.

---

## 6. Supplier Bills and 3-Way Bill Match

Navigate to **Accounting › Payables** (`/admin/ap/supplier-bills`).

A supplier bill is the invoice received from the supplier. It is entered into the system and then matched against the Purchase Order and Goods Receipt to verify quantities and prices before payment is approved.

### 6.1 Enter a supplier bill

1. Navigate to **Accounting › Enter Bill** (`/admin/ap/supplier-bills/enter`).
2. Pick the **Supplier** by name.
3. Enter the supplier's own **Invoice Number**, **Bill Date**, and **Due Date**.
4. Set the **Currency**.
5. Optionally pick the **Purchase Order** by number. Linking the PO enables the 3-way match (see section 6.2). For service bills with no PO, leave this blank.
6. Add **Bill Lines**: for each, pick the product, enter the billed quantity and unit price.
7. Click **Enter Bill & Match**. The bill is created and the 3-way match runs automatically.

### 6.2 Understanding the 3-way match

The 3-way match compares three documents for each bill line:

```
Supplier Bill line  ←→  Purchase Order line  ←→  Goods Receipt line
    (billed qty/price)       (ordered qty/price)      (received qty/price)
```

The match result for each line is one of:

| Match Status | Meaning |
|---|---|
| MATCHED | Quantities and prices are within tolerance; line approved |
| HELD_PRICE_VARIANCE | Bill unit price is outside the acceptable tolerance versus the PO price |
| HELD_QTY_VARIANCE | Billed quantity is outside the acceptable tolerance versus the received quantity |
| VARIANCE_ACCEPTED | A held variance was reviewed and manually accepted |

The overall bill status depends on its lines:

| Bill Status | Meaning |
|---|---|
| MATCHED | All lines matched; bill ready for payment approval |
| HELD | One or more lines have an unresolved variance |

### 6.3 Accept a variance

If a bill line is HELD due to a price or quantity variance:

1. Open the bill (navigate to `/admin/ap/supplier-bills/uid/{uid}`) or use the **Match** action on the bills list at **Accounting › Payables** (`/admin/ap/supplier-bills`).
2. Review the variance amount and percentage shown on the held line.
3. If the variance is acceptable, click **Accept Variance** on that line.
4. When all held lines are resolved, the bill status moves to **MATCHED**.

Accepting variances requires the `AP.BILL.MATCH` permission.

### 6.4 Re-run match from the bills list

For a bill that was entered without running a match (or needs re-matching after a correction):

1. Navigate to **Accounting › Payables** (`/admin/ap/supplier-bills`).
2. Click **Match** on the bill row.
3. The match result is displayed inline.

### 6.5 Service bills (no PO)

For invoices from service suppliers where there is no corresponding PO or GR:

- Leave the Purchase Order field blank when entering the bill.
- No 3-way match is run.
- The bill is entered for manual review and approval.

### 6.6 Bill status reference

| Status | Meaning |
|---|---|
| DRAFT | Entered but not yet matched |
| MATCHED | All lines matched; ready for payment |
| HELD | One or more lines have an open variance |
| APPROVED | Approved for payment by the AP team |
| PARTIALLY_PAID | Payment partially applied |
| PAID | Fully paid |

### 6.7 Record an AP payment

Payments against supplier bills are managed in the Accounts Payable module. Navigate to **Accounting › Record Payment** (`/admin/ap/payments/record`) to record a payment. See the Finance chapter for details on recording and reconciling AP payments.

---

**Example — Supplier bill for Simba Cement (clean 3-way match):**

Simba Cement sends Invoice **SIM/2026/1041**, Bill Date 2026-06-22, Due Date 2026-07-22, for 500 bags @ TZS 14,500 each = TZS 7,250,000 net.

Sarah opens **Accounting › Enter Bill** (`/admin/ap/supplier-bills/enter`), picks supplier **Simba Cement Ltd**, enters Invoice No **SIM/2026/1041**, Bill Date 2026-06-22, Due Date 2026-07-22. She links PO **PO-0088** and adds one bill line: **Saruji 50kg**, Qty 500, Unit Price TZS 14,500. She clicks **Enter Bill & Match**.

The system runs the 3-way match:
- Bill line: 500 bags @ 14,500
- PO line: 500 bags @ 14,500 ✓
- GRN line: 500 bags received ✓

All lines → **MATCHED**. Bill status → MATCHED. Bill **BILL-0051** is ready for payment.

**Example — Bill with price variance (held):**

A different shipment arrives and the supplier bills at TZS 14,900/bag (TZS 400 over the PO price). After 3-way match, the bill line shows **HELD_PRICE_VARIANCE** with variance TZS 200,000. The AP manager opens the bill, reviews the variance, decides it is within business tolerance, and clicks **Accept Variance**. Line moves to VARIANCE_ACCEPTED; bill → MATCHED.

---

## 7. Purchase Returns

Navigate to **Purchasing › Purchase Returns** (`/admin/purchase-returns`).

A purchase return records goods being sent back to the supplier (for example, damaged or incorrect items received). Creating a confirmed return decreases stock and notifies the AP module to expect a supplier credit.

### 7.1 Create a purchase return

1. Navigate to **Purchasing › Purchase Returns** (`/admin/purchase-returns`) and click **New Purchase Return**, or go directly to `/admin/purchase-returns/create`.
2. Pick the **Goods Receipt** by GRN number (the GR must have status RECEIVED).
3. Enter a mandatory **Reason**.
4. For each line being returned, enter the **Returned Quantity** (cannot exceed the quantity originally received on that GR line).
5. Click **Save**. The return is created in **DRAFT**.

### 7.2 Confirm a purchase return

Confirming the return physically ships the goods back and adjusts stock.

1. Open the DRAFT purchase return (navigate to `/admin/purchase-returns/uid/{uid}`).
2. Click **Confirm**.
3. Status → **CONFIRMED**. Stock is removed from the branch and a purchase return event is posted.

### 7.3 Purchase return status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; quantities editable |
| CONFIRMED | Return confirmed; stock decremented; supplier debit notified |

---

**Example — Purchase return for damaged cement:**

After receiving GRN-0061, the storekeeper discovers 20 bags of cement arrived wet and unusable. He opens **Purchasing › Purchase Returns** (`/admin/purchase-returns`), clicks **New Purchase Return**, picks GRN **GRN-0061**, enters Reason **"20 bags arrived wet — product damaged"**, and sets Returned Quantity **20** on the Saruji 50kg line. He saves — return **PRET-0018** is created in DRAFT.

The purchasing manager reviews and clicks **Confirm** — status → CONFIRMED. Stock decreases by 20 bags (480 bags remain). The AP module is notified to expect a supplier credit note for 20 × TZS 14,500 = TZS 290,000 from Simba Cement.

---

## 8. Purchase Settings

Navigate to **Purchasing › Purchase Settings** (`/admin/purchase-settings`).

Purchase settings control the PO approval workflow.

### 8.1 PO approval threshold

| Setting | Description |
|---|---|
| PO Approval Enabled | When turned on, Purchase Orders above the threshold amount require approval before goods can be received |
| PO Approval Threshold | The minimum order total that triggers the approval requirement |
| Currency | The currency of the threshold amount |

To change these settings, navigate to **Purchasing › Purchase Settings** (`/admin/purchase-settings`), click **Edit**, update the values, and click **Save**.

When PO approval is enabled, a user with `PURCHASE.ORDER.APPROVE` must approve or reject POs that exceed the threshold.

---

**Example — Enabling PO approval:**

The CFO wants all purchase orders above TZS 5,000,000 to require a second-level approval. She opens **Purchasing › Purchase Settings** (`/admin/purchase-settings`), clicks **Edit**, sets **PO Approval Enabled** to ON, **PO Approval Threshold** to **5,000,000**, **Currency** to **TZS**, and saves. From now on any placed PO with a total above TZS 5,000,000 enters PENDING approval status and cannot proceed to goods receipt until an authorised approver acts on it.

---

## 9. End-to-end procure-to-pay example

The following steps illustrate a complete P2P cycle for a stock purchase with real sample values.

**Scenario: Warehouse restocking — 500 bags of Saruji 50kg from Simba Cement Ltd**

---

**Step 1 — Raise a Requisition**

Storekeeper John opens **Purchasing › Purchase Requisitions** (`/admin/purchase-requisitions`), clicks **New Requisition**, sets Required By **2026-06-18**, notes "Stock replenishment — cement for construction projects". He adds one line: **Saruji 50kg**, Unit **BAG**, Qty **500**, Estimated Cost **TZS 14,800**. He saves (REQ-0080 = DRAFT) and clicks **Submit** (status → SUBMITTED).

**Step 2 — Approve**

Purchasing manager Neema opens REQ-0080 and clicks **Approve** (status → APPROVED).

**Step 3 — Convert to RFQ**

Neema clicks **Convert**, selects **RFQ** — RFQ-0031 is created in DRAFT.

**Step 4 — Invite suppliers and send**

Neema opens **Purchasing › RFQs / Sourcing** (`/admin/rfqs`), opens RFQ-0031, adds invited suppliers **Tanzania Cement Distributors** and **Simba Cement Ltd**, sets Response Due Date **2026-06-17**, and clicks **Send** (status → SENT).

**Step 5 — Capture supplier quotes**

Two suppliers respond:
- Tanzania Cement Distributors: 500 bags @ TZS 14,800 = TZS 7,400,000.
- Simba Cement Ltd: 500 bags @ TZS 14,500 = TZS 7,250,000 (lead time 5 days).

Purchasing officer Zawadi captures both quotes on RFQ-0031. RFQ status → QUOTES_RECEIVED.

**Step 6 — Award the RFQ**

Zawadi clicks **Award** on the Simba Cement quote (lower price). RFQ status → AWARDED. Purchase Order **PO-0088** (DRAFT, 500 bags @ TZS 14,500) is created automatically.

**Step 7 — Place the PO**

Zawadi opens **Purchasing › Purchase Orders** (`/admin/purchase-orders`), finds PO-0088, reviews the line, and clicks **Place** (status → ORDERED, total TZS 7,250,000).

**Step 8 — Receive goods**

On 2026-06-22, 500 bags arrive. Storekeeper John opens **Purchasing › Goods Receipts** (`/admin/goods-receipts`), clicks **New Goods Receipt**, picks PO-0088, enters Receipt Date 2026-06-22, keeps 500 bags, and submits. GRN-0061 created (RECEIVED); PO-0088 status → RECEIVED; 500 bags added to stock.

**Step 9 — Allocate landed costs**

Port clearing TZS 850,000 + freight TZS 600,000 are entered as a landed cost against GRN-0061 (Basis: By Quantity). Accountant Sarah opens **Purchasing › Landed Costs** (`/admin/landed-costs`), creates the landed cost, and clicks **Confirm** — TZS 2,900/bag added to the moving-average cost.

**Step 10 — Enter the supplier bill and run 3-way match**

Simba Cement's invoice arrives: SIM/2026/1041, 500 bags @ TZS 14,500. Sarah opens **Accounting › Enter Bill** (`/admin/ap/supplier-bills/enter`), links PO-0088, enters the bill, and clicks **Enter Bill & Match**. All lines → MATCHED. BILL-0051 is ready for payment.

**Step 11 — Record AP payment**

Finance officer David opens **Accounting › Record Payment** (`/admin/ap/payments/record`), picks BILL-0051 (TZS 7,250,000 due 2026-07-22), records a bank transfer payment on 2026-07-20. The bill status moves to PAID and the AP balance for Simba Cement is cleared.

**Step 12 — Purchase return (if needed)**

If 20 bags arrived damaged, John opens **Purchasing › Purchase Returns** (`/admin/purchase-returns`), creates a return against GRN-0061 for 20 bags, and the manager confirms it — stock decreases by 20 bags and the AP module notes a TZS 290,000 credit note expected from Simba Cement.
