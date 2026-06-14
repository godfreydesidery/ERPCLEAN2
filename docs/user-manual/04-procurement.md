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

A purchase requisition is an internal request for goods or services. It must be approved before a purchase order or RFQ can be raised.

### 1.1 Create a requisition

1. Navigate to **Procurement → Purchase Requisitions**.
2. Click **New Requisition**.
3. Set the **Required By** date and optionally a cost centre and notes.
4. Add lines: for each item, pick the **Product** by name, choose a **Unit**, and enter the **Requested Quantity** and an **Estimated Unit Cost**.
5. Click **Save**. The requisition is saved in **DRAFT**.

### 1.2 Submit a requisition

When the requisition is complete and ready for approval:

1. Open the draft requisition.
2. Click **Submit**.
3. The status changes to **SUBMITTED** and the requisition is routed for approval.

### 1.3 Approve or reject a requisition

An approver (a user with `PURCHASE.REQUISITION.APPROVE`) reviews submitted requisitions.

- **Approve** — click **Approve**. Status → **APPROVED**. The Convert action becomes available.
- **Reject** — click **Reject**, enter a mandatory reason, and confirm. Status → **REJECTED**. The requisitioner is notified via the audit trail.

### 1.4 Convert a requisition

An approved requisition can be converted into either a Purchase Order or an RFQ:

1. Open the approved requisition.
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

## 2. RFQ (Request for Quotation)

An RFQ invites one or more suppliers to submit prices for a defined list of items.

### 2.1 Create an RFQ

An RFQ can be created directly or by converting an approved requisition (see section 1.4).

**To create directly:**

1. Navigate to **Procurement → RFQs** and click **New RFQ**.
2. Set the **Response Due Date** and optionally add notes.
3. In the **Invited Suppliers** section, pick each supplier by name. Invite at least one supplier.
4. Add lines: pick each product by name, choose a unit, and enter the required quantity.
5. Click **Save**. The RFQ is created in **DRAFT**.

### 2.2 Send an RFQ to suppliers

1. Open the DRAFT RFQ.
2. Click **Send**. Status → **SENT**. Suppliers are notified that they should submit a quote.

### 2.3 Capture supplier quotes

When a supplier responds with a price:

1. Open the SENT RFQ.
2. Click **Capture Quote**.
3. Pick the **Supplier** by name (only invited suppliers are listed).
4. Optionally set a valid-until date, lead time in days, and notes.
5. For each RFQ line, enter the **Quoted Quantity** and **Unit Price**.
6. Click **Save**. The quote is recorded with status **RECEIVED** and the RFQ status moves to **QUOTES_RECEIVED**.

Repeat for each responding supplier. You can compare their prices side-by-side in the quotes panel on the RFQ detail page.

### 2.4 Award the RFQ

To select the winning supplier and create a Purchase Order:

1. In the quotes panel, identify the preferred quote (usually the lowest compliant price).
2. Click **Award** on that quote row.
3. The winning quote status changes to **AWARDED** and all other quotes become **NOT_AWARDED**. The RFQ status changes to **AWARDED**.
4. A **Purchase Order** is created in DRAFT from the awarded quote lines and prices. A link to the PO is shown.

### 2.5 Cancel an RFQ

Open the RFQ and click **Cancel**. Status → **CANCELLED**. An awarded RFQ cannot be cancelled.

### 2.6 RFQ status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared |
| SENT | Sent to suppliers; awaiting responses |
| QUOTES_RECEIVED | At least one supplier quote captured |
| AWARDED | Winning supplier selected; PO created |
| CANCELLED | Cancelled |

---

## 3. Purchase Orders

A Purchase Order (PO) is the formal commitment to buy from a supplier. POs are created from a converted requisition or from an awarded RFQ. There is no standalone "New PO" form in the UI.

### 3.1 View and manage a DRAFT Purchase Order

1. Navigate to **Procurement → Purchase Orders**.
2. Open the DRAFT PO.
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

1. Open the PO (status ORDERED, PARTIALLY_RECEIVED, or RECEIVED).
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

## 4. Goods Receipt

A goods receipt (GR) records the physical arrival of goods from the supplier. Creating a GR increases stock and updates the PO outstanding quantities.

### 4.1 Receive goods

1. Navigate to **Procurement → Goods Receipts** and click **New Goods Receipt**.
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

## 5. Landed Costs

Landed costs allocate incidental import charges (freight, duty, insurance, clearing fees, and other charges) to the items received. Landed costs are applied to one or more goods receipts and allocated to individual GR lines.

### 5.1 Create a landed cost

1. Navigate to **Procurement → Landed Costs** and click **New Landed Cost**.
2. Select the **Allocation Basis**:
   - **By Value** — charges are spread proportionally to the value of each GR line.
   - **By Quantity** — charges are spread proportionally to the quantity received on each GR line.
3. Pick the **Goods Receipt(s)** by GRN number. You can include multiple GRNs in one landed cost document.
4. Add one or more **Charges**: select the charge type (Freight, Duty, Clearing, Insurance, or Other) and enter the amount.
5. Click **Save**. The landed cost is created in **DRAFT**.

### 5.2 Confirm a landed cost

Confirming allocates the charges to the GR lines and posts the cost adjustment to the GL.

1. Open the DRAFT landed cost.
2. Click **Confirm**.
3. Status → **CONFIRMED**. The allocation per GR line is shown in the detail.

A confirmed landed cost cannot be edited. If there is an error, contact your administrator.

### 5.3 Landed cost status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; charges editable |
| CONFIRMED | Charges allocated; GL posted; immutable |

---

## 6. Supplier Bills and 3-Way Bill Match

A supplier bill is the invoice received from the supplier. It is entered into the system and then matched against the Purchase Order and Goods Receipt to verify quantities and prices before payment is approved.

### 6.1 Enter a supplier bill

1. Navigate to **Procurement → Supplier Bills** and click **Enter Bill**.
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

1. Open the bill (or use the **Match** action on the bills list).
2. Review the variance amount and percentage shown on the held line.
3. If the variance is acceptable, click **Accept Variance** on that line.
4. When all held lines are resolved, the bill status moves to **MATCHED**.

Accepting variances requires the `AP.BILL.MATCH` permission.

### 6.4 Re-run match from the bills list

For a bill that was entered without running a match (or needs re-matching after a correction):

1. Navigate to **Procurement → Supplier Bills**.
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

Payments against supplier bills are managed in the Accounts Payable module. See the Finance chapter for details on recording and reconciling AP payments.

---

## 7. Purchase Returns

A purchase return records goods being sent back to the supplier (for example, damaged or incorrect items received). Creating a confirmed return decreases stock and notifies the AP module to expect a supplier credit.

### 7.1 Create a purchase return

1. Navigate to **Procurement → Purchase Returns** and click **New Purchase Return**.
2. Pick the **Goods Receipt** by GRN number (the GR must have status RECEIVED).
3. Enter a mandatory **Reason**.
4. For each line being returned, enter the **Returned Quantity** (cannot exceed the quantity originally received on that GR line).
5. Click **Save**. The return is created in **DRAFT**.

### 7.2 Confirm a purchase return

Confirming the return physically ships the goods back and adjusts stock.

1. Open the DRAFT purchase return.
2. Click **Confirm**.
3. Status → **CONFIRMED**. Stock is removed from the branch and a purchase return event is posted.

### 7.3 Purchase return status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; quantities editable |
| CONFIRMED | Return confirmed; stock decremented; supplier debit notified |

---

## 8. Purchase Settings

Purchase settings control the PO approval workflow. Navigate to **Procurement → Purchase Settings** to view or edit them.

### 8.1 PO approval threshold

| Setting | Description |
|---|---|
| PO Approval Enabled | When turned on, Purchase Orders above the threshold amount require approval before goods can be received |
| PO Approval Threshold | The minimum order total that triggers the approval requirement |
| Currency | The currency of the threshold amount |

To change these settings, click **Edit**, update the values, and click **Save**.

When PO approval is enabled, a user with `PURCHASE.ORDER.APPROVE` must approve or reject POs that exceed the threshold.

---

## 9. End-to-end procure-to-pay example

The following steps illustrate a complete P2P cycle for a stock purchase:

1. **Requisition** — a department raises a purchase requisition for 100 bags of cement.
2. **Submit and Approve** — the requisition is submitted and approved by the purchasing manager.
3. **Convert to RFQ** — the approved requisition is converted to an RFQ.
4. **Send RFQ** — the RFQ is sent to two shortlisted suppliers.
5. **Capture quotes** — prices are received from both suppliers and entered as supplier quotes.
6. **Award** — the cheaper supplier is awarded the RFQ; a Draft PO is created automatically.
7. **Place PO** — the PO is placed (status: ORDERED; PO number assigned).
8. **Receive goods** — when the cement arrives at the warehouse, a goods receipt is created for the delivered quantity. The PO status updates to RECEIVED.
9. **Landed cost** — freight and duty charges for the shipment are entered as a landed cost against the GR and confirmed.
10. **Enter supplier bill** — the supplier's invoice is entered against the PO. The 3-way match confirms quantities and prices match.
11. **AP payment** — the matched and approved bill is paid through the Accounts Payable module.
12. **Purchase return** (if needed) — any damaged bags are returned to the supplier by creating and confirming a purchase return against the GR.
