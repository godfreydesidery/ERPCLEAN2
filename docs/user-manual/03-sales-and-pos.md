# Sales and Point of Sale

This chapter covers everything from quoting a customer through to collecting payment, including recurring and blanket agreements, advanced pricing, and the Point of Sale cashier workflow.

---

## Overview

The sales module follows the order-to-cash (O2C) path:

```
Quotation → Sales Order → Delivery → Sales Invoice → Payment
```

Walk-in cash sales skip the first three steps and begin directly with a Sales Invoice or a POS sale.

**Required permissions** — the navigation menu only shows items your role includes. Key permission groups:

| Activity | Permission codes required |
|---|---|
| Quotations | `SALES.QUOTE.VIEW`, `SALES.QUOTE.CREATE`, `SALES.QUOTE.SEND`, `SALES.QUOTE.ACCEPT` |
| Sales Orders | `SALES.ORDER.VIEW`, `SALES.ORDER.CREATE`, `SALES.ORDER.CONFIRM`, `SALES.ORDER.CANCEL` |
| Deliveries | `SALES.DELIVERY.VIEW`, `SALES.DELIVERY.CREATE` |
| Sales Invoices | `SALES.INVOICE.VIEW`, `SALES.INVOICE.CREATE`, `SALES.INVOICE.SETTLE`, `SALES.INVOICE.VOID` |
| Sales Returns | `SALES.RETURN.VIEW`, `SALES.RETURN.CREATE` |
| Blanket Orders | `SALES.BLANKET.VIEW`, `SALES.BLANKET.CREATE` |
| Standing Orders | `SALES.STANDING.VIEW`, `SALES.STANDING.CREATE` |
| Pricing Rules | `SALES.PRICING.RULE.VIEW`, `SALES.PRICING.RULE.MANAGE` |
| POS (tills) | `POS.TILL.VIEW`, `POS.TILL.MANAGE` |
| POS (cashier) | `POS.SESSION.OPEN`, `POS.SALE.CREATE`, `POS.SESSION.VIEW` |
| POS (close/reconcile) | `POS.SESSION.CLOSE`, `POS.SESSION.RECONCILE` |

Contact your administrator if an expected menu item is missing.

---

## 1. Quotations

Navigate to **Sales › Quotations** (`/admin/quotations`).

A quotation is an offer sent to a customer. When accepted it becomes a Sales Order automatically.

### 1.1 Create a quotation

1. Navigate to **Sales › Quotations** (`/admin/quotations`).
2. Click **New Quotation**.
3. In the **Customer** field, type part of the customer name or code and select the correct entry from the list. Do not type or paste a raw ID.
4. Set **Quote Date** (today by default) and **Valid Until** (the date the offer expires).
5. Click **Save**. The quotation is saved in **DRAFT** status. A quote number is assigned later when you send it.

**Required fields:** Customer, Quote Date, Valid Until.

### 1.2 Add lines to a quotation

1. Open the draft quotation (navigate to **Sales › Quotations** then click the quotation row, or go to `/admin/quotations/uid/{uid}`).
2. In the **Lines** section, search for the product by name or code and select it.
3. Choose a **Unit**, enter **Quantity**, and optionally enter a **Line Discount** (either a percentage or a fixed amount — not both).
4. Click **Add Line**. The system calculates net amount, VAT, and gross from the configured price list.

Repeat for each product. You can also add **Service** products; these are priced the same way but do not affect stock.

To remove a line, click the delete icon on the line row. Lines can only be changed while the quotation is in DRAFT.

### 1.3 Send a quotation

When the quotation is ready to share with the customer:

1. Open the draft quotation.
2. Click **Send**.
3. The status changes to **SENT** and a quote number (QUOTE-####) is assigned.

**Prerequisites:** The quotation must have at least one line, and the Valid Until date must be today or in the future.

### 1.4 Accept or reject a quotation

When the customer responds:

- **Accept** — click **Accept** on the sent quotation. A **Sales Order** is created automatically with the same lines and discounts. The quotation status changes to **ACCEPTED**. A success message shows the new order number and provides a link to it.
- **Reject** — click **Reject**. The quotation status changes to **REJECTED**.

Both actions require the `SALES.QUOTE.ACCEPT` permission. If the Valid Until date has already passed, the system prevents acceptance and marks the quotation **EXPIRED**.

### 1.5 Quotation statuses

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; lines can be added or removed |
| SENT | Sent to customer; awaiting response |
| ACCEPTED | Customer accepted; Sales Order created |
| REJECTED | Customer declined |
| EXPIRED | Valid Until date passed before acceptance |

---

**Example — Quotation for Karibu Supermarkets:**

Salesperson Ali opens **Sales › Quotations** (`/admin/quotations`) and clicks **New Quotation**. He types "Karibu" in the Customer field and selects **Karibu Supermarkets Ltd**. He sets Quote Date to **2026-06-14** and Valid Until to **2026-07-14**, then saves. The quotation is created in DRAFT with no number yet.

Ali adds two lines:
- Product **Unga wa Ngano 2kg**, Unit **CARTON (12 pcs)**, Qty **50**, Line Discount **0%** — system prices at TZS 18,000 per carton = TZS 900,000 net.
- Product **Mafuta ya Kupikia 1L**, Unit **CARTON (12 pcs)**, Qty **30**, Line Discount **5%** — list price TZS 22,000; after 5% = TZS 20,900 per carton = TZS 627,000 net.

VAT at 18% is added by the system: total gross = TZS 1,535,400 + VAT. Ali clicks **Send** — status becomes SENT and the number **QUOTE-0047** is assigned.

Karibu calls back and accepts. Ali clicks **Accept**. The system creates **Sales Order SO-0112** from the same lines and shows a link. Quotation status is now ACCEPTED.

---

## 2. Sales Orders

Navigate to **Sales › Sales Orders** (`/admin/sales-orders`).

A Sales Order (SO) can be created in two ways: automatically when a quotation is accepted, or directly from **Sales › Sales Orders → New Order**.

### 2.1 Create a standalone Sales Order

1. Navigate to **Sales › Sales Orders** (`/admin/sales-orders`).
2. Click **New Order**.
3. Pick the **Customer** by name.
4. Set **Order Date**. Optionally set a **Document Discount** (percentage or amount — not both).
5. Click **Save**. The order is created in **DRAFT**.

### 2.2 Add lines to a Sales Order

The same process as adding quotation lines. Lines can only be added, edited, or removed while the order is in DRAFT.

### 2.3 Confirm an order

Confirming an order reserves stock for every GOODS line.

1. Open the draft order at **Sales › Sales Orders** then click the order row (or navigate to `/admin/sales-orders/uid/{uid}`).
2. The order must have at least one line.
3. Click **Confirm**.
4. The status changes to **CONFIRMED** and each line shows its reserved quantity.

This requires the `SALES.ORDER.CONFIRM` permission. A user who can create orders but not confirm them will not see this button.

### 2.4 Cancel an order

Cancelling an order releases any stock reservations.

1. Open the order.
2. Click **Cancel**, enter an optional reason, and confirm.

Cancellation is allowed from any status except **CANCELLED** and **CLOSED**.

### 2.5 Order status lifecycle

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; no stock reserved |
| CONFIRMED | Stock reserved |
| PARTIALLY_FULFILLED | At least one delivery made; not all lines delivered |
| FULFILLED | All lines delivered |
| PARTIALLY_INVOICED | Some deliveries invoiced |
| CLOSED | Fully fulfilled and fully invoiced |
| CANCELLED | Cancelled; reservations released |

---

## 3. Deliveries

Navigate to **Sales › Deliveries** (`/admin/deliveries`).

A delivery records that goods have physically left the warehouse. Deliveries can only be created against a **CONFIRMED** or **PARTIALLY_FULFILLED** order.

### 3.1 Create a delivery

1. Navigate to **Sales › Deliveries** (`/admin/deliveries`) and click **New Delivery**, or open a confirmed Sales Order and use the **Create Delivery** action.
2. The delivery create form is at `/admin/deliveries/create`. Pick the **Sales Order** by order number.
3. The form shows all open (undelivered) lines with the remaining quantity pre-filled.
4. Adjust individual line quantities if you are making a **partial delivery** (backorder). The quantity you enter cannot exceed the open balance.
5. Set **Delivery Date** and click **Submit**.

Deliveries are created immediately in **CONFIRMED** status and cannot be undone. Each delivery is assigned a DELIVERY-#### number.

### 3.2 Partial delivery (backorder)

Enter a quantity less than the open balance on any line to create a partial delivery. The Sales Order status moves to **PARTIALLY_FULFILLED**. Create another delivery later for the remaining quantity.

### 3.3 Generate an invoice from a delivery

Once goods are delivered, you can invoice the customer for that delivery:

1. Open the delivery (navigate to **Sales › Deliveries**, click the row, or go to `/admin/deliveries/uid/{uid}`).
2. Click **Create Invoice from Delivery**.
3. A draft **Sales Invoice** is created automatically with the delivered lines. The doc discount from the source order is pro-rated to the delivered quantity.

Proceed to section 4 to finalise the invoice.

---

## 4. Sales Invoices

Navigate to **Sales › Invoices** (`/admin/sales-invoices`).

An invoice is the formal billing document. There are two origins:

- **From a delivery** (origin: SALES_ORDER) — created via section 3.3 above.
- **Direct walk-in** (origin: DIRECT) — created manually for cash customers without a prior order.

### 4.1 Create a direct (walk-in) invoice

1. Navigate to **Sales › Invoices** (`/admin/sales-invoices`).
2. Click **New Invoice**.
3. Pick the **Customer** by name. Optionally pick an **Agent** and a **Route**; if omitted the system uses the logged-in user's linked agent and that agent's primary route.
4. Click **Save**. A draft invoice is created.

### 4.2 Add lines to an invoice

Same process as adding lines to a quotation or order. Lines can only be added, edited, or removed while the invoice is in DRAFT.

### 4.3 Record a payment

Payments can be recorded on a draft invoice before it is finalised.

1. In the **Payments** panel, click **Add Payment**.
2. Choose the **Tender Type**: Cash or Mobile Money.
3. Enter the **Amount**. For Mobile Money, enter the transaction reference.
4. Click **Add**.

Recording payments requires the `SALES.INVOICE.SETTLE` permission (separate from the permission to create lines).

For **cash / walk-in customers** the total payments must equal the invoice gross before you can finalise. Credit customers may have a balance that becomes an open AR item.

### 4.4 Finalise an invoice

1. Open the draft invoice.
2. Click **Finalise**.
3. The status changes to **FINALISED** and an invoice number is assigned.

After finalisation:
- For DIRECT invoices, stock is issued from the branch.
- For invoices from a delivery, revenue is posted (stock was already issued at delivery).
- For credit customers, an AR open item is created for any unpaid balance.

**Paid-in-full rule:** walk-in (cash) customers must be fully paid before finalisation is allowed.

**Credit limit:** if a credit customer's outstanding balance plus this invoice would exceed their credit limit, finalisation is blocked unless you hold the `SALES.CREDIT.OVERRIDE` permission.

### 4.5 Void an invoice

A finalised invoice can be voided if it was issued in error:

1. Open the finalised invoice (navigate to **Sales › Invoices**, click the row, or go to `/admin/sales-invoices/uid/{uid}`).
2. Click **Void**, enter a mandatory reason, and confirm.
3. The invoice status changes to **VOID** and a reversing credit note is posted.

The original invoice number is retained on the voided record. Voiding is not the same as deletion.

### 4.6 Invoice status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; lines and payments editable |
| FINALISED | Issued; posted to AR and GL; immutable |
| VOID | Reversed; credit note posted; number retained |

---

**Example — Full O2C: Karibu Supermarkets (credit account):**

Continuing from section 1's example, Sales Order **SO-0112** was created from the accepted quotation. The warehouse confirms goods are ready.

1. **Confirm SO:** Ali opens **Sales › Sales Orders** (`/admin/sales-orders`), clicks SO-0112, and clicks **Confirm**. Status becomes CONFIRMED; stock reserved — 50 cartons Unga + 30 cartons Mafuta.

2. **Deliver:** Ali navigates to **Sales › Deliveries** (`/admin/deliveries`), clicks **New Delivery**, picks **SO-0112**. He delivers the full quantity (50 + 30 cartons) on 2026-06-15 and submits. Delivery **DELIVERY-0089** is created; SO status → FULFILLED.

3. **Invoice from delivery:** Ali opens DELIVERY-0089 at `/admin/deliveries/uid/{uid}` and clicks **Create Invoice from Delivery**. A DRAFT invoice is created. Since Karibu is a CREDIT_ACCOUNT customer, Ali clicks **Finalise** without adding a payment — the unpaid balance of TZS 1,535,400 (plus 18% VAT = TZS 1,811,772 gross) becomes an open AR item. Invoice number **INV-0203** is assigned.

**Example — Walk-in direct invoice (cash customer):**

Cashier Fatuma opens **Sales › Invoices** (`/admin/sales-invoices`) and clicks **New Invoice**. She picks customer **Amina Hassan (walk-in)**. She adds one line: **Sukari 1kg**, Unit **KG**, Qty **5**, price TZS 2,200/kg = TZS 11,000 net; VAT 18% = TZS 1,980; gross = TZS 12,980. In the Payments panel she adds **Cash, Amount TZS 12,980**. She clicks **Finalise** — status becomes FINALISED, invoice number **INV-0204** is assigned, stock is issued, and the cash is recorded.

---

## 5. Sales Returns (RMA)

Navigate to **Sales › Sales Returns** (`/admin/sales-returns`).

A sales return records goods coming back from the customer. Returns are always against a specific delivery and immediately generate a credit note.

### 5.1 Create a return

1. Navigate to **Sales › Sales Returns** (`/admin/sales-returns`) and click **New Return**, or go directly to `/admin/sales-returns/create`.
2. Pick the **Delivery** by its delivery number.
3. The form shows the delivered lines. Enter the **Quantity Returned** for each line being returned (cannot exceed the quantity delivered minus what has already been returned).
4. Set the **Return Date** and enter a **Reason**.
5. Click **Submit**.

Returns are created directly in **CONFIRMED** status. Stock is returned to the branch. A credit note is raised automatically (pro-rated to the returned quantity).

### 5.2 Returnable quantity

Each return reduces the returnable balance for that delivery line. You can process multiple returns against the same delivery line until the full delivered quantity has been returned.

---

**Example — Partial sales return (Karibu Supermarkets):**

Two days after delivery, Karibu reports 5 cartons of Mafuta ya Kupikia arrived leaking. The stock controller opens **Sales › Sales Returns** (`/admin/sales-returns`), clicks **New Return**, and picks delivery **DELIVERY-0089**. She enters **Qty Returned = 5** on the Mafuta line, sets return date **2026-06-17**, reason **"Damaged packaging — leaking oil"**, and submits. Return **RET-0031** is created in CONFIRMED status. Five cartons of Mafuta stock are returned to the warehouse and a credit note for TZS 104,500 (5 × TZS 20,900) plus VAT is automatically raised against INV-0203.

---

## 6. Blanket Orders

Navigate to **Sales › Blanket Orders** (`/admin/blanket-orders`).

A blanket order is a framework agreement with a customer that commits to supplying a total quantity at a fixed unit price over a validity window. Actual deliveries are created as **releases** (draw-downs) against the blanket.

### 6.1 Create a blanket order

1. Navigate to **Sales › Blanket Orders** (`/admin/blanket-orders`) and click **New Blanket Order**, or go directly to `/admin/blanket-orders/create`.
2. Select the **Company** and **Branch**.
3. Pick the **Customer** by name.
4. Set **Currency**, **Valid From**, and **Valid To** dates.
5. Add one or more **Lines**: for each, pick the product by name, choose a unit, and enter the committed quantity and unit price.
6. Optionally add notes (up to 500 characters).
7. Click **Save**.

The blanket order is created with status **ACTIVE** and assigned an order number.

### 6.2 Create a release (draw-down)

When the customer calls off part of their commitment:

1. Open the blanket order (navigate to `/admin/blanket-orders/uid/{uid}`).
2. Click **Draw Release** (visible only when the blanket is ACTIVE and you hold the manage permission).
3. Enter the **Branch ID** for the delivery branch.
4. Pick the **Agent** by name.
5. For each line you want to include, tick it and enter the **Draw Quantity** (cannot exceed the remaining committed quantity).
6. Click **Create Release**.

A **Sales Order** is created and linked to the blanket. The committed remaining quantity on each drawn line decreases.

### 6.3 Cancel a blanket order

Open the blanket order and click **Cancel** then confirm. The status changes to **CANCELLED**. Previously generated Sales Orders from this blanket are unaffected.

### 6.4 Blanket order statuses

| Status | Meaning |
|---|---|
| ACTIVE | Agreement in force; releases can be drawn |
| CANCELLED | Cancelled; no further releases |

---

**Example — Blanket supply agreement:**

Duka Kuu Ltd signs a 6-month supply deal for 1,000 bags of Mchele wa Zambia at TZS 6,500/bag. The sales manager opens **Sales › Blanket Orders** (`/admin/blanket-orders`), creates a new blanket for **Duka Kuu Ltd**, Valid From **2026-07-01** to **2026-12-31**, adds one line: **Mchele wa Zambia 10kg**, Unit **BAG**, Qty **1,000**, Unit Price **6,500**. Order is saved as ACTIVE.

In July, Duka Kuu calls off 200 bags. The sales manager opens the blanket, clicks **Draw Release**, draws 200 bags → Sales Order **SO-0145** is created. Remaining committed quantity on the blanket is now 800 bags.

---

## 7. Standing Orders (Recurring)

Navigate to **Sales › Standing Orders** (`/admin/standing-orders`).

A standing order is a recurring template that generates a new Sales Order automatically on a schedule (daily, weekly, bi-weekly, or monthly). It is useful for regular supply contracts.

### 7.1 Create a standing order

1. Navigate to **Sales › Standing Orders** (`/admin/standing-orders`) and click **New Standing Order**, or go directly to `/admin/standing-orders/create`.
2. Pick the **Branch**, **Customer**, and set **Currency**.
3. Choose a **Frequency**: Daily, Weekly, Bi-Weekly, or Monthly.
4. Set a **Start Date**. Optionally set an **End Date**; leave it blank for open-ended.
5. Add lines: pick each product and unit by name, enter quantity and unit price.
6. Click **Save**.

The standing order is created with status **ACTIVE** and the first `Next Run Date` is set.

### 7.2 Pause and resume

- **Pause** — open the standing order (navigate to `/admin/standing-orders/uid/{uid}`) and click **Pause**. No Sales Orders are generated while the order is paused.
- **Resume** — click **Resume** to make it active again.

### 7.3 Trigger a run manually

Click **Trigger Now** to generate a Sales Order immediately (without waiting for the scheduled run). The next run date advances by the configured frequency.

### 7.4 Cancel a standing order

Click **Cancel** to stop the standing order permanently. The status changes to **CANCELLED** and no further Sales Orders are generated.

### 7.5 Automatic generation

The system checks every night at midnight and generates Sales Orders for all ACTIVE standing orders whose next run date is today or earlier. Each generated order advances the next run date by one period.

### 7.6 Standing order statuses

| Status | Meaning |
|---|---|
| ACTIVE | Generating on schedule |
| PAUSED | Temporarily stopped; can be resumed |
| CANCELLED | Permanently stopped |

---

**Example — Weekly bread delivery for Hoteli ya Pwani:**

Hoteli ya Pwani orders 50 loaves of bread every Monday. The sales rep opens **Sales › Standing Orders** (`/admin/standing-orders`), creates a new standing order for **Hoteli ya Pwani**, Frequency **Weekly**, Start Date **2026-06-16**, no end date. Line: **Mkate Mzima**, Unit **PCS**, Qty **50**, Unit Price **TZS 800**. The system auto-generates **Sales Order SO-0151** on Monday 16 June, then **SO-0158** on 23 June, and so on every week without manual action.

---

## 8. Pricing Rules

Navigate to **Sales › Pricing Rules** (`/admin/pricing-rules`).

Pricing rules let you set volume-break discounts and customer-specific contract prices.

### 8.1 Price tiers (quantity breaks)

A price tier gives a lower unit price when a customer orders at least a minimum quantity of a product on a given price list.

**To create a tier:**

1. Open **Sales › Pricing Rules** (`/admin/pricing-rules`) and go to the **Price Tiers** tab.
2. Click **New Tier**.
3. Pick the **Product** and **Price List** by name.
4. Enter **Min Quantity**, **Unit Price**, and **Currency**.
5. Click **Save**.

The tier status is **ACTIVE**. To deactivate a tier, click the **Deactivate** button on the row; the tier is soft-deactivated and no longer applied to new transactions.

You cannot have two active tiers for the same product, price list, and minimum quantity combination.

### 8.2 Customer prices (contract prices)

A customer price sets a fixed unit price for a specific product for a specific customer, overriding the standard price list.

**To create a customer price:**

1. Open **Sales › Pricing Rules** (`/admin/pricing-rules`) and go to the **Customer Prices** tab.
2. Click **New Customer Price**.
3. Pick the **Customer** and **Product** by name.
4. Enter the **Unit Price** and **Currency**.
5. Optionally set **Effective From** and **Effective To** dates for a time-limited contract.
6. Click **Save**.

Only one customer price record can exist per customer-and-product pair. Deactivate the existing record before creating a new one is not possible (the unique constraint is status-agnostic); raise a support request to change an existing contract price.

### 8.3 Pricing resolution order

When a sale line is priced the system applies the first matching rule in this priority:

1. Customer price (highest priority)
2. Active promotion (managed via the API; no UI currently)
3. Price tier
4. Standard list price
5. No price configured (the line is rejected)

---

**Example — Volume tier for cement:**

The sales manager opens **Sales › Pricing Rules** (`/admin/pricing-rules`), goes to **Price Tiers**, and clicks **New Tier**. He picks product **Saruji 50kg**, Price List **Wholesale TZS**, Min Quantity **100**, Unit Price **TZS 14,500**, Currency **TZS**. Any order for 100+ bags on the Wholesale price list will now use TZS 14,500 instead of the standard TZS 15,200.

**Example — Contract price for Karibu Supermarkets:**

Under the **Customer Prices** tab the manager creates: Customer **Karibu Supermarkets Ltd**, Product **Unga wa Ngano 2kg**, Unit Price **TZS 17,500** (negotiated). From the next sale, whenever a sales line is added for this customer and product, TZS 17,500 is applied — regardless of the price list.

---

## 9. Point of Sale

Navigate to the **Point of Sale** group in the sidebar.

POS is used for face-to-face retail transactions. A **till** is a physical cash register position. Each till must be opened in a **session** before sales can be processed. The session is closed and reconciled at end of day.

### 9.1 Roles

| Role | Typical permissions |
|---|---|
| Cashier | Open session, ring sales, view sessions |
| Manager | All cashier permissions plus create/deactivate tills, close sessions, reconcile |

Your administrator assigns the appropriate POS permissions to your role. Contact them if POS is not visible in your menu.

### 9.2 Set up a till

This is a one-time setup task done by a manager.

1. Navigate to **Point of Sale › POS Tills** (`/admin/pos/tills`).
2. Click **New Till**.
3. Enter a **Till Name** (e.g. "Counter 1").
4. Pick the **Branch** by name.
5. Click **Create Till**.

The till is created with status **ACTIVE**. To deactivate a till, click **Deactivate** on its row.

### 9.3 Open a session (start of day)

1. Navigate to **Point of Sale › POS Sessions** (`/admin/pos/sessions`).
2. Click **Open Session**.
3. Pick the **Till** by name (only ACTIVE tills are listed).
4. Enter the **Opening Float** — the cash amount placed in the drawer at the start of the day.
5. Click **Open Session**.

A new session is created with status **OPEN**. Only one session can be open on a till at a time.

### 9.4 Ring a sale

1. Navigate to **Point of Sale › Point of Sale** (`/admin/pos/sell`) — this is the checkout screen.
2. If your organisation has more than one company, select the correct company.
3. Pick the **Session** — only OPEN sessions are listed.
4. Pick the **Customer** by name.
5. Pick the **Agent** by name (required — leaving Agent blank will cause the sale to be rejected).
6. Set the **Currency**.
7. Click **Add Line**. Pick the **Product** by name; confirm or adjust the **Unit**, enter **Quantity** and **Unit Price**, and optionally a line **Discount**.
8. Add further lines as needed. The **Total** updates in the footer.
9. Enter the **Tendered Amount** (the cash handed over by the customer). The **Change** is calculated immediately. The sale cannot be submitted if the tendered amount is less than the total.
10. Click **Complete Sale**.

A success receipt is displayed showing the invoice number and total. Click **View Invoice** to open the full invoice, or **New Sale** to start the next transaction.

**Notes:**
- POS sales are always settled in cash. There is no tender-type selector; payment is recorded as Cash automatically.
- The agent field is mandatory on the backend; leaving it blank will cause the sale to be rejected.

### 9.5 Record a payout

A payout records cash leaving the drawer during the session — for example, a drop to the safe or a petty-cash refund.

1. Open the session detail (**Point of Sale › POS Sessions** (`/admin/pos/sessions`), click **View** on the OPEN session, or navigate to `/admin/pos/sessions/uid/{uid}`).
2. Click **Record Payout**.
3. Select the **Type**: Paid Out (cash removed from the drawer) or Refund (customer cash refund).
4. Enter the **Amount** and a **Reason**.
5. Click **Record**.

Both payout types reduce the expected closing cash. The live X-read total updates automatically.

### 9.6 X-Read (live totals during the day)

The **X-Read** card on the session detail page shows running totals without closing the session:

| Field | Meaning |
|---|---|
| Sales Total | Sum of all POS sale totals in this session |
| Payouts | Sum of all payouts (PAID_OUT + REFUND) |
| Expected Cash | Opening Float + Sales Total − Payouts |
| Invoice Count | Number of sales processed |

Click the refresh icon to reload the X-read at any time.

### 9.7 Close a session (end of day)

Closing records the physical cash count.

1. Open the session detail (navigate to `/admin/pos/sessions/uid/{uid}`).
2. Click **Close Session**.
3. Enter the **Counted Cash** — the amount physically in the drawer.
4. Optionally add closing notes.
5. Click **Close**.

The session status changes to **CLOSED** and a **variance** is computed:

```
Variance = Counted Cash − Expected Cash
```

- **Positive variance** (over): more cash in the drawer than expected.
- **Negative variance** (short): less cash than expected.
- **Zero variance**: drawer balances exactly.

### 9.8 Reconcile a session (Z-Read)

Reconciliation posts the variance to the general ledger and produces the final Z-Read report.

1. Open a **CLOSED** session (navigate to `/admin/pos/sessions/uid/{uid}`).
2. Click **Reconcile**.
3. Optionally add notes.
4. Click **Reconcile**.

The session status changes to **RECONCILED**. The **Z-Read** card shows all session figures plus the variance and (if non-zero) the journal reference:

- **Over variance** — debit Cash, credit income account 4900 (Till Surplus).
- **Short variance** — debit expense account 5170 (Till Shortage), credit Cash.
- **Zero variance** — no journal posted.

After reconciliation the session is read-only and no further sales or payouts can be recorded.

### 9.9 Session lifecycle

| Status | Meaning |
|---|---|
| OPEN | Sales and payouts can be recorded |
| CLOSED | Session counted; reconciliation pending |
| RECONCILED | Final Z-read produced; GL posted; session closed |

Transitions are one-way: OPEN → CLOSED → RECONCILED. A session cannot be re-opened.

### 9.10 Daily workflow summary

1. **Open** a session on your till with the day's opening float.
2. **Ring sales** as customers arrive.
3. **Record payouts** for any cash removed from the drawer.
4. Check the **X-Read** at any time for running totals.
5. At end of day, **count** the cash in the drawer.
6. **Close** the session by entering the counted amount.
7. A manager **reconciles** the closed session; the system posts any variance to the GL.

---

**Example — Walk-in cash sale (full POS day):**

Cashier Jane starts her shift at Duka Moja. She navigates to **Point of Sale › POS Sessions** (`/admin/pos/sessions`) and clicks **Open Session**. She picks till **Counter 1** (Branch: Dar es Salaam Main) and enters Opening Float **TZS 100,000**. Session **SES-0041** opens with status OPEN.

During the morning Jane processes three customers at **Point of Sale › Point of Sale** (`/admin/pos/sell`):

1. She picks session **SES-0041**, customer **Mteja wa Kawaida**, agent **Omar Salim**, currency TZS. She adds: **Sukari 1kg** × 2 @ TZS 2,500 = TZS 5,000; **Mafuta ya Kupikia 1L** × 1 @ TZS 8,000 = TZS 8,000. Total TZS 13,000. Customer hands over TZS 20,000 — Change shown as TZS 7,000. Jane clicks **Complete Sale** — Invoice **INV-0211** issued.

2. Second sale: **Unga wa Ngano 2kg** × 3 @ TZS 3,200 = TZS 9,600. Tendered TZS 10,000, change TZS 400. Invoice INV-0212 issued.

3. Third sale: **Chumvi 500g** × 5 @ TZS 500 = TZS 2,500. Tendered exact. Invoice INV-0213 issued.

At midday Jane does a safe drop: she opens session detail (`/admin/pos/sessions/uid/{uid}`), clicks **Record Payout**, Type **Paid Out**, Amount **TZS 20,000**, Reason "Midday safe drop". Expected cash now: TZS 100,000 + TZS 25,100 − TZS 20,000 = **TZS 105,100**.

Jane checks the X-Read: Sales Total TZS 25,100, Payouts TZS 20,000, Expected Cash TZS 105,100, Invoice Count 3. Correct.

At end of day Jane counts the drawer: TZS 105,200 (TZS 100 over). She clicks **Close Session**, enters Counted Cash **TZS 105,200** — Variance is **+TZS 100.00** (over).

Manager Rehema opens the session detail, clicks **Reconcile**. Status → RECONCILED. Z-Read confirms the +TZS 100 variance and shows Journal **JNL-0519**: DR Cash 100 / CR Till Surplus (4900) 100.
