# Requirements — Sales Orders / Order-to-Cash (the quote→order→reserve→deliver→invoice→[return] spine)

> Status: **RATIFIED (owner-confirmed 2026-06-10).** The owner ratified the full Order-to-Cash depth scope —
> **(1)** a **Quotation** stage IN (QUOTE-####, validity date, draft pricing, DRAFT→SENT→ACCEPTED→
> EXPIRED/REJECTED) that, on acceptance, **converts to a Sales Order** (copies lines/pricing); a quote
> **reserves nothing and posts nothing**; **(2)** a **Sales Order** (SO-####) lifecycle DRAFT → CONFIRMED →
> (partially/fully) FULFILLED → (partially/fully) INVOICED → CLOSED (+ CANCELLED), with lines (product, qty
> ordered, unit price, discount); **(3)** **soft stock reservation on confirm** — confirming an SO reserves
> the ordered qty against on-hand (**available = on_hand − reserved**); a reservation **moves no stock and
> posts no GL** (a soft allocation); a delivery converts reservation → issue, a cancel releases it; **(4)** a
> separate **Delivery** document (DEL-####) ships some/all of an SO's open qty — delivering **issues the
> stock and posts DR COGS / CR Inventory at moving-average cost** (reusing the ADR-0020 valuation/COGS
> engine — the delivery is now the stock-issue trigger), partial deliveries allowed, the unshipped balance a
> **backorder**; **(5)** **THE KEY SEAM** — stock issue + COGS **move from invoice-finalise to delivery-time**,
> so an invoice **created from an SO/delivery posts REVENUE ONLY** (DR AR/Cash, CR Sales Revenue, CR VAT) and
> **must NOT re-issue stock** (the delivery already did), while a **direct invoice** (the existing walk-in
> channel, no SO) **keeps issuing stock on finalise as today**; **(6)** **partial invoicing per delivery** —
> invoice the delivered qty, one SO may yield several invoices, reusing the shipped VAT invoice channel for
> revenue/AR/VAT; **(7)** **order-level + line discounts** flowing through to the invoice totals (VAT computed
> on the discounted net); **(8)** **sales returns / RMA IN** (RET-####) against a delivery — returned qty
> comes back into stock (reverse COGS/inventory at the original issued cost, reusing the ADR-0020 reversal)
> AND raises a **credit note** (reusing `ArCreditNoteService` to reverse revenue/AR/VAT), partial returns
> allowed; **(9)** permissions/numbering/scope — `SALES.ORDER.*`, `SALES.QUOTE.*`, `SALES.DELIVERY.CREATE`,
> `SALES.RETURN.CREATE`; `code_sequence` kinds QUOTE / SO / DELIVERY / RETURN; per-company isolation;
> `assertCanActIn` on every read; audit on every state transition. Each is reflected below as a fixed v1
> requirement; everything not chosen has moved to the **Deferred** list (§2). **No ADR-0021-blocking open
> question remains** — the meaty items (the **invoice-origin mechanism** for the stock-issue seam, the
> **over-reservation / backorder policy**, the **reservation-release timing**, the **return cost basis**, the
> **discount rounding**, the **quote→order edit semantics**) are **ADR decisions**, not requirements
> blockers (the *behaviour* is fixed; the mechanism / model / precision are the ADR's to choose — flagged
> below).
>
> Author: system-analyst · Domain: `sales` (Order-to-Cash depth — new quote / order / delivery / return
> documents on the existing sales spine, driving the now-valued stock + COGS engine and the AR credit-note
> path) with touches into `stock` (reservation + the delivery-driven issue/COGS) and `ar` (the return credit
> note). Business-level spec only. **No schema, no API shapes, no tables/columns, no code** — those are the
> solutions-architect's, in **ADR-0021** (next step). Do not infer a data model from this document.
>
> **This is Sales Orders / Order-to-Cash depth — Phase B (docs/PATH-TO-FULL-ERP.md area 6 / docs/ROADMAP.md
> T2.1).** The Sales invoice channel (ADR-0008 / V5) is fully functional and credit-aware; GL (ADR-0013), AR
> (ADR-0014), AP (ADR-0015), Cash & Bank (ADR-0016), VAT return + WHT (ADR-0017), Reporting (ADR-0018),
> Year-End Close (ADR-0019), and — critically — **Inventory Valuation & COGS (ADR-0020 / V17)** all ship: stock
> is now **valued** (moving weighted average), and on SALE.FINALISED the `SaleIssueStockHandler` deducts
> quantity **and** posts **DR COGS / CR Inventory at the average cost** (incl. recipe explosion). What is
> missing is the **pre-invoice operational depth**: there is no way to capture a **quotation**, place a **sales
> order**, **reserve** stock for it, **deliver** it in parts (with a **backorder** on the balance), **invoice
> the delivered portion**, or process a **return / RMA**. This slice adds that Order-to-Cash spine on top of
> the shipped invoice channel — and, in doing so, makes one **load-bearing change**: it **moves the stock
> issue + COGS posting from invoice-finalise to delivery-time** (the key seam — §1 flag), so an SO-sourced
> invoice posts **revenue only** while a direct walk-in invoice keeps issuing stock on finalise as today.
>
> **Depends on:** **Sales** (the spine this slice extends — ADR-0008 / V5: `sales_invoices` (header) +
> `sales_invoice_lines` + `sales_invoice_payments`, the `InvoiceTotalsCalculator` tax-exclusive per-band VAT
> algorithm, the DRAFT→FINALISED→VOID lifecycle, `INV-####` at finalise, the `SALE.FINALISED` / `SALE.VOIDED`
> outbox events, the `SaleFinalisedPayload` `{ invoiceUid, companyId, branchId, finalisedAt, lines:[{ productId,
> productUid, unitId, qtyInBase }] }`, the `tax_rates` master, `customers` (CASH_WALK_IN / CREDIT_ACCOUNT),
> `agents`, `routes`, and the price-lists Sales reads); **Stock** (ADR-0010 / V7 + valuation ADR-0020 / V17:
> `stock_on_hand` (per company, branch, product — **quantity-only today: no reserved / available column**,
> now carrying `avg_cost` + `on_hand_value` after V17), the single `StockPostingService.post(...)` primitive,
> the `SaleIssueStockHandler` that on SALE.FINALISED deducts qty **and posts COGS at the moving average**
> (recipe explosion via `RecipeExplosionResolver`), `SaleReversalStockHandler`, and the moving-average
> recompute-at-receipt / consume-at-average rules — **the valuation/COGS engine a DELIVERY will now drive, and
> a RETURN will now reverse**); **Accounts Receivable** (ADR-0014 / V11: `ArCreditNoteService` (STANDALONE /
> SALE_VOID origins) — **REUSED for returns** to reverse revenue / AR / VAT; AR open items, receipts,
> credit-limit enforcement at finalise); **GL** (ADR-0013 / V10: the synchronous `GLPostingService.post`;
> revenue posting on invoice finalise — DR AR/Cash, CR Sales Revenue, CR VAT; COGS posting now at stock issue,
> ADR-0020); **Products** (ADR-0007 / V3: sellable products, units, price-lists, single-level recipes);
> **Money** (ADR-0005 — base currency only); **`code_sequence`** (ADR-0007 — numbering); **RBAC /
> `assertCanActIn` / audit / the idempotent transactional outbox + `IdempotencyGuard`** (the platform spine).
> All shipped. **Latest migration is V17; Sales Orders is V18.**

## 1. Business context & why now

ERPCLEAN2 sells today through **one channel: the direct invoice** (ADR-0008 / V5). A user creates a draft
invoice, adds lines, takes payment, and finalises — at which point the invoice gets its `INV-####` number,
revenue + VAT post to the books (the sales auto-poster, gl.md §3.1), AR opens an item for a credit sale, and
— since ADR-0020 — the `SaleIssueStockHandler` **deducts the quantity and posts COGS at the moving-average
cost**. That is the **right** flow for a walk-in / over-the-counter sale: the goods leave the shop as the
invoice is raised, so issue + COGS at finalise is correct.

But real **wholesale / B2B trading is not a single counter event** — it is a **process over time**:

- a customer asks for a **price** before committing — a **quotation** (with a validity window);
- they accept it and place an **order** — a **sales order** the business commits to fulfil, that **reserves**
  the goods so they are not sold out from under the order;
- the goods are **delivered** — sometimes **all at once, sometimes in parts** (a partial delivery, with the
  unshipped balance carried as a **backorder**); delivery is when the **goods physically leave** and the cost
  is incurred;
- the customer is **invoiced for what was delivered** — possibly **several invoices** across several
  deliveries against one order;
- and sometimes goods come back — a **return / RMA** — which must put stock **back** and **credit** the
  customer.

None of that exists. There is no quotation, no sales order, no reservation, no delivery document, no
backorder, no partial invoicing against an order, and no structured sales return (today a sale is corrected
only by a **full void**, sales.md FR-SALES-22). **Order-to-Cash depth closes that gap** — it adds the
quote → order → reserve → deliver → invoice → [return] spine on top of the shipped invoice channel, reusing
everything underneath: the **invoice channel** posts the revenue/AR/VAT (unchanged math), the **valuation/COGS
engine** (ADR-0020) is now driven by the **delivery** (and reversed by the **return**), and the **AR
credit-note service** (ADR-0014) raises the return's credit.

### THE KEY INTEGRATION SEAM — stock issue + COGS move from invoice-finalise to delivery-time (read this before anything else; flag for ADR-0021)

This is the single load-bearing change in the slice, and it must be stated unambiguously because it changes
**when** the cost of goods sold hits the books.

- **Today** (ADR-0008 + ADR-0020): finalising **any** invoice emits `SALE.FINALISED`, which the
  `SaleIssueStockHandler` consumes to **deduct the stock quantity and post DR COGS / CR Inventory** at the
  moving average. Stock leaves and the cost posts **at invoice finalise**.
- **With Sales Orders**: the **delivery** is the moment goods physically leave. So **stock issue + COGS move
  to DELIVERY time** — creating a delivery (DEL-####) against an SO **issues the stock and posts DR COGS / CR
  Inventory at the moving average** (the delivery now drives the ADR-0020 engine), and **releases** the
  corresponding reservation.
- **Therefore an invoice CREATED FROM an SO/delivery must POST REVENUE ONLY** — DR AR/Cash, CR Sales Revenue,
  CR VAT (the existing invoice-finalise revenue posting, unchanged) — and **must NOT re-issue stock or
  re-post COGS** (the delivery already did). Re-issuing would **double-deduct stock and double-count COGS** — a
  finance-grade defect.
- **A DIRECT invoice (no SO — the existing walk-in channel) keeps issuing stock + COGS on finalise as today.**
  Nothing about the walk-in flow changes; the counter sale still issues at finalise because there is no
  delivery document in front of it.

This is a **hard business rule** (BR-SO-09): **SO-sourced invoices skip the stock-issue/COGS step; direct
invoices keep it.** The *behaviour* is fixed and ratified. The **mechanism** — how the invoice-finalise path
knows whether to issue stock — is the architect's call in ADR-0021 (OQ-SO-03). The leading options:

- **(a) an invoice origin/flag** — the invoice carries an `origin` (DIRECT vs SO/DELIVERY); the
  `SaleIssueStockHandler` (or the finalise path) skips the stock-issue/COGS when origin is SO-sourced, e.g. by
  the `SALE.FINALISED` payload carrying an `issuesStock=false` flag, or a distinct event type for the
  revenue-only post; **or**
- **(b) the delivery owns the stock event** — the delivery emits its own stock-issue/COGS event (a new
  `DELIVERY.SHIPPED` consumed by the valuation engine), and SO-sourced invoice-finalise simply never emits
  `SALE.FINALISED` (it emits a revenue-only posting event), so there is no stock event to skip.

Either reconciles to the same books; the architect chooses. **The requirement fixes that an SO-sourced
invoice never moves stock and a direct invoice always does** — option (a) vs (b) is the design.

> **Flag for the architect (ADR-0021):** the load-bearing decisions are **(1)** the **invoice-origin
> mechanism** for the stock-issue seam above (OQ-SO-03) — the one that, gotten wrong, double-counts COGS;
> **(2)** the **reservation model** — `stock_on_hand` has **no reserved / available column today** (V7/V17
> carry `quantity`, `reorder_level`, `avg_cost`, `on_hand_value`); ADR-0021 introduces a soft reservation
> (likely an additive `reserved` quantity on `stock_on_hand` and/or a reservation ledger keyed to the SO line),
> with **available = on_hand − reserved** as the available-to-promise figure (OQ-SO-01); **(3)** the
> **over-reservation / backorder policy** — whether confirming an SO may reserve **beyond** on-hand (→ negative
> available, flagged) since backorders are supported (recommended **allow**, OQ-SO-02); **(4)** the
> **reservation-release timing** — a delivery converts reservation → issue (release the delivered portion); a
> cancel releases the whole remaining reservation (OQ-SO-04); **(5)** the **return cost basis** — a return
> reverses COGS at the **original issued cost** of the delivery it returns against (recommended, mirroring
> OQ-INV-02), not the now-current average (OQ-SO-05); **(6)** the **discount rounding / apportionment** — an
> order-level discount apportioned across lines before VAT, HALF_UP, identical backend/frontend (reusing the
> `InvoiceTotalsCalculator` algorithm, OQ-SO-06); **(7)** the **quote→order edit semantics** — what may change
> when a quote converts to an SO and when an SO is confirmed (OQ-SO-07); plus the new `ScopeGuard` target
> types (quote / salesorder / delivery / salesreturn) and the `code_sequence` kinds (QUOTE / SO / DELIVERY /
> RETURN). State these; do not design the tables here. **None blocks the requirements** — the behaviour is
> fixed; the mechanism / model / policy / precision are the ADR's to choose.

### The Order-to-Cash flow (the spine this slice builds)

```
   QUOTATION                SALES ORDER                 DELIVERY                 INVOICE                 RETURN
   (QUOTE-####)             (SO-####)                   (DEL-####)               (INV-####)              (RET-####)
   reserves nothing         confirm → RESERVES stock    issues stock + COGS      revenue ONLY            stock IN + COGS reverse
   posts nothing            avail = on_hand − reserved  releases reservation     (NO stock re-issue)     + credit note (AR)
        │                        │                          │                        │                       │
   accept → CONVERT ─────────────┘                          │                        │                       │
                                  partial deliver ──────────┘   backorder = open balance stays on the SO     │
                                                                  invoice the delivered qty ─────────────────┘ (against a delivery)
```

A **quotation** is a non-binding priced offer with a validity date; accepting it **converts** to a **sales
order** (lines + pricing copied). Confirming the SO **reserves** the ordered qty (a soft allocation —
**available = on_hand − reserved**; no stock moves, no GL posts). A **delivery** ships some or all of the SO's
open qty: it **issues the stock and posts COGS at the moving average** (driving the ADR-0020 engine) and
**releases** the matching reservation; an under-delivery leaves the unshipped balance as a **backorder** open
on the SO. An **invoice** is raised for a delivery's delivered qty and posts **revenue only** (the delivery
already moved the stock) — one SO may yield **several** invoices as deliveries occur. A **return / RMA**
against a delivery puts the returned qty **back into stock** (reversing COGS/inventory at the **original
issued cost**) and raises a **credit note** (reversing revenue/AR/VAT). Cancelling an SO **releases** its
remaining reservation.

### Vocabulary (read this first)

- **Quotation (quote)** — a **non-binding priced offer** to a customer (`QUOTE-####`), with **draft pricing**
  (line products, quantities, unit prices, discounts) and a **validity date** beyond which it **expires**. Its
  lifecycle is **DRAFT → SENT → ACCEPTED → EXPIRED / REJECTED**. A quote **reserves no stock and posts no GL**
  (it commits nothing); on **acceptance** it **converts to a sales order**, copying its lines and pricing
  (BR-SO-01). A quote is **not** an order and **not** an invoice.
- **Sales order (SO)** — a customer's **committed order** (`SO-####`) the business undertakes to fulfil. It has
  **order lines** (product, **qty ordered**, unit price, discount) and a lifecycle **DRAFT → CONFIRMED →
  (partially/fully) FULFILLED → (partially/fully) INVOICED → CLOSED**, plus **CANCELLED**. **Confirming** an SO
  **reserves** the ordered stock (BR-SO-02). An SO is the spine the deliveries and invoices hang off; it is
  **not** itself a financial posting (it posts nothing on its own — the delivery and the invoice do).
- **Order line** — one product on a sales order: the **product**, the **qty ordered**, the **unit price**
  (defaulted from the applicable price list, like a sale line), and an optional **line discount** (% or
  amount). It tracks its own **fulfilled** (delivered) and **invoiced** quantities, from which the SO's status
  rollup is derived (BR-SO-12).
- **Reservation (soft allocation) / available-to-promise (ATP)** — **confirming** an SO **reserves** the
  ordered qty against on-hand: a **soft allocation** that **moves no stock and posts no GL** — it only records
  that this quantity is **spoken for**. **Available = on_hand − reserved** is the **available-to-promise**: the
  quantity the business can still commit to a *new* order. A delivery **converts** a reservation into an
  **issue** (the goods actually leave); a cancel **releases** the reservation (BR-SO-03/05/06). A reservation
  is **not** a stock movement (it is not on the `stock_movements` ledger) and **not** a GL entry.
- **Fulfilment / delivery** — the act of **shipping** an SO's goods, recorded as a **delivery** document
  (`DEL-####`). Delivering **issues the stock** (a real `stock_movements` deduction) and **posts DR COGS / CR
  Inventory at the moving-average cost** (reusing the ADR-0020 engine — the delivery now **drives** it), and
  **releases** the corresponding reservation (BR-SO-04). A delivery is the moment **goods physically leave**
  and the **cost is incurred** — distinct from the invoice (the bill) and the reservation (the soft hold).
- **Partial delivery** — a delivery that ships **less than** an SO line's open qty. The shipped portion issues
  + COGS; the **unshipped balance** stays open on the SO as a **backorder**. One SO line may be delivered over
  **several** deliveries (BR-SO-07).
- **Backorder** — the **unshipped open balance** of an SO line after a partial delivery (or before any
  delivery): qty ordered − qty fulfilled. It **remains open** on the SO (still reserved, awaiting stock /
  delivery) until delivered or the SO is cancelled (BR-SO-07). A backorder is a quantity-on-the-order state,
  not a separate document.
- **COGS-at-delivery** — the consequence of THE KEY SEAM: the **delivery** (not the invoice) is now the
  trigger that **issues stock and posts COGS** at the moving average, for an SO-sourced sale (BR-SO-04/09).
  (A **direct** walk-in invoice still issues + COGS at finalise — there is no delivery in front of it.)
- **Partial invoicing** — invoicing the **delivered** qty of an SO — possibly **several invoices** across
  several deliveries against one order. Each invoice **references the delivery / SO** it bills, reuses the
  shipped VAT invoice channel for the **revenue / AR / VAT** posting, and **posts revenue only** (no stock
  re-issue — BR-SO-08/09). An SO is **INVOICED** when all delivered qty is invoiced (BR-SO-12).
- **Order-level discount / line discount** — a discount applied on top of the price-list price: a **per-line**
  discount (% or amount on one order line) and an **order-level** discount (% or amount on the whole order,
  apportioned across lines). Both flow through to the invoice totals, and **VAT is computed on the discounted
  net** (the tax-exclusive base, reusing the `InvoiceTotalsCalculator` algorithm — BR-SO-10).
- **Sales return / RMA** — a **return** (`RET-####`, a.k.a. RMA — Return Merchandise Authorisation) of
  delivered goods **against a delivery**: the returned qty comes **back into stock** (a real stock IN, reversing
  the COGS/inventory at the **original issued cost** — reusing the ADR-0020 reversal) **and** raises a **credit
  note** (reusing `ArCreditNoteService` to reverse the revenue / AR / VAT). **Partial returns** are allowed; a
  return **cannot exceed the delivered qty** (BR-SO-11). A return is **against a delivery**, not a free-standing
  negative invoice.
- **Credit note** — the AR document that **reduces** what a customer owes (reverses revenue / AR / VAT),
  raised by a sales return. **Reused from AR** (ADR-0014 `ArCreditNoteService`); Order-to-Cash **raises** one
  on a return, it does not own the credit-note posting. A credit note is **not** an invoice and **not** a cash
  refund (the refund tender is a Cash & Bank act, deferred §2).

> **Word discipline (carried into the glossary):** a **quotation** (a non-binding priced offer) is **not** a
> **sales order** (a committed order) and **not** an **invoice** (the bill) — a quote reserves/posts nothing,
> an order reserves, a delivery issues, an invoice bills. A **reservation** (a soft allocation, **available =
> on_hand − reserved**) is **not** a **stock issue** (a real `stock_movements` deduction) — confirming reserves,
> delivering issues. **Available-to-promise** (on_hand − reserved) is **not** **on-hand** (the physical
> quantity). A **delivery** (goods physically leave, COGS posts) is **not** an **invoice** (the customer is
> billed, revenue posts) — they are **separate documents** that may happen at different times and in different
> counts. A **backorder** (an open unshipped balance on the order) is **not** a separate document. A **return /
> RMA** (goods back into stock + a credit note, against a **delivery**) is **not** a **void** (a full reversal
> of a finalised invoice, sales.md FR-SALES-22) — a return is **partial-capable** and stock-in + credit-note,
> a void is all-or-nothing. **COGS now posts at delivery** for SO-sourced sales, **at finalise** for direct
> sales — never twice.

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-10). This is **Order-to-Cash depth (T2.1)**:
> the **quotation → sales order → reservation → delivery (partial + backorder) → partial invoicing →
> return/RMA** spine on the shipped invoice channel, with the **key seam** (stock issue + COGS at delivery, not
> invoice; SO-sourced invoices revenue-only) ratified as a hard rule. It is a **depth slice on the existing
> sales module** that **reuses** the invoice channel (revenue/AR/VAT), the inventory valuation/COGS + reversal
> engine (ADR-0020), and the AR credit-note service (ADR-0014). It does **not** rebuild Sales, Stock, AR, or
> GL. **The build is staged: the core O2C spine first (quote → SO → reserve → deliver → invoice), returns/RMA
> second** — §10.

### In scope (v1 — "quote it, order it, reserve it, deliver it in parts, invoice the delivered portion, and take it back")

- **Quotation stage.** A quotation (`QUOTE-####`) with **draft pricing** (lines: product, qty, unit price,
  discount) and a **validity date**; lifecycle **DRAFT → SENT → ACCEPTED → EXPIRED / REJECTED**; on
  **acceptance** it **converts to a sales order** (lines + pricing copied). A quote **reserves nothing and
  posts nothing** (FR-SO-01/02/03, BR-SO-01).
- **Sales order lifecycle.** An SO (`SO-####`) with **order lines** (product, qty ordered, unit price,
  discount) and lifecycle **DRAFT → CONFIRMED → (partially/fully) FULFILLED → (partially/fully) INVOICED →
  CLOSED**, plus **CANCELLED**. Create / edit draft, **confirm**, **cancel** (FR-SO-04/05/06, BR-SO-02).
- **Soft stock reservation on confirm.** **Confirming** an SO **reserves** the ordered qty against on-hand —
  **available = on_hand − reserved** (available-to-promise) — a **soft allocation** that **moves no stock and
  posts no GL**. Over-reservation **beyond on-hand** is **allowed** (→ negative available, **flagged**), since
  backorders are supported (recommended default — OQ-SO-02). A **delivery** converts the reservation to an
  **issue**; a **cancel** releases the remaining reservation (FR-SO-07/08, BR-SO-03/05/06).
- **Delivery / fulfilment (partial + backorder) → issues stock + COGS.** A separate **delivery** document
  (`DEL-####`) ships some/all of an SO's open qty. Delivering **issues the stock** (a real `stock_movements`
  deduction) **and posts DR COGS / CR Inventory at the moving-average cost** (reusing the ADR-0020 engine —
  incl. recipe explosion for composed products) **and releases** the matching reservation. **Partial
  deliveries** are allowed; the unshipped balance is a **backorder** that **remains open** on the SO
  (FR-SO-09/10, BR-SO-04/07).
- **THE KEY SEAM — SO-sourced invoices post revenue only; direct invoices keep issuing stock.** An invoice
  **created from an SO/delivery** posts **REVENUE ONLY** (DR AR/Cash, CR Sales Revenue, CR VAT — the shipped
  finalise math) and **MUST NOT re-issue stock or re-post COGS** (the delivery already did). A **direct**
  invoice (no SO — the existing walk-in channel) **keeps issuing stock + COGS on finalise** exactly as today
  (FR-SO-11, **BR-SO-09 — the hard rule**). The **mechanism** (invoice origin/flag vs delivery-owns-the-event)
  is the architect's (OQ-SO-03).
- **Partial invoicing per delivery.** An invoice bills a **delivery's delivered qty** (referencing the
  delivery / SO); **one SO may yield several invoices** across several deliveries. Reuses the shipped VAT
  invoice channel for the **revenue / AR / VAT** posting. An SO is **INVOICED** when all delivered qty is
  invoiced; **CLOSED** when fully delivered + fully invoiced (FR-SO-12, BR-SO-08/12).
- **Order-level + line discounts.** A **per-line** discount (% or amount) and an **order-level** discount (%
  or amount, apportioned across lines), applied on top of the price-list price, flowing through to the invoice
  totals, with **VAT computed on the discounted net** (the tax-exclusive base — reusing the
  `InvoiceTotalsCalculator` algorithm) (FR-SO-13, BR-SO-10).
- **Sales returns / RMA (staged second).** A **return** (`RET-####`) against a **delivery**: the returned qty
  comes **back into stock** (a stock IN, **reversing the COGS/inventory at the original issued cost** — reusing
  the ADR-0020 reversal) **and** raises a **credit note** (reusing `ArCreditNoteService` to reverse revenue /
  AR / VAT). **Partial returns** allowed; a return **cannot exceed the delivered qty** (FR-SO-14, BR-SO-11).
- **Status rollups.** The SO's status is **derived** from its lines' fulfilled / invoiced quantities:
  CONFIRMED → PARTIALLY_FULFILLED → FULFILLED as deliveries occur; → PARTIALLY_INVOICED → INVOICED as invoices
  occur; → CLOSED when fully delivered **and** fully invoiced (FR-SO-15, BR-SO-12).
- **Permissions** — `SALES.QUOTE.VIEW / CREATE / SEND / ACCEPT`, `SALES.ORDER.VIEW / CREATE / CONFIRM /
  CANCEL`, `SALES.DELIVERY.CREATE` (+ `VIEW`), `SALES.RETURN.CREATE` (+ `VIEW`) — sensible `MODULE.RESOURCE.
  ACTION`, reusing the `SALES.*` conventions; the invoice raised from a delivery rides the existing
  `SALES.INVOICE.*` perms. Per-company scope; `assertCanActIn` on **every read path**; **audit** on **every
  state transition** (FR-SO-16, NFR-SO-04).
- **Numbering** — `code_sequence` kinds **QUOTE** (`QUOTE-####`), **SO** (`SO-####`), **DELIVERY**
  (`DEL-####`), **RETURN** (`RET-####`); per company; concurrency-safe (the shipped `code_sequence`
  mechanism). Invoices keep `INV-####` (FR-SO-17).
- **Migration footprint (V18, additive).** The new quote / SO / delivery / return documents + lines; the
  reservation model (likely an additive `reserved` quantity on `stock_on_hand` and/or a reservation ledger —
  the architect's, OQ-SO-01); the new permissions; the new `code_sequence` kinds; the **invoice-origin
  mechanism** for the stock-issue seam (OQ-SO-03). **V1–V17 frozen.**

### Deferred (recognised, NOT built in v1 — separate later increments)

- **Point-of-Sale (POS).** Till devices, cashier sessions + float + cash-drawer reconciliation, X/Z reports,
  offline buffering — a separate Sales channel (PATH-TO-FULL-ERP §3.3); not Order-to-Cash. The walk-in invoice
  remains the over-the-counter channel in v1.
- **Drop-ship / third-party fulfilment.** An SO fulfilled directly by a supplier to the customer (no own-stock
  issue) is deferred — v1 fulfils from own stock (PATH-TO-FULL-ERP §3.3).
- **Multi-warehouse allocation / location-aware reservation.** v1 reserves/issues against the **single
  location** the SO is raised at (the company-branch on-hand). Reserving/allocating across warehouses, picking
  the source location, and inter-location transfer to fulfil are deferred (ties to Stock multi-location,
  PATH-TO-FULL-ERP §3.5; inventory valuation is single-location, OQ-INV-07).
- **Advanced ATP / MRP.** v1's available-to-promise is the simple **on_hand − reserved**. Forward-looking ATP
  (netting against inbound POs, capable-to-promise, demand explosion / MRP) is deferred (PATH-TO-FULL-ERP
  §3.6).
- **Blanket / standing orders.** A blanket SO with a cumulative limit and call-offs over time, and standing /
  recurring orders, are deferred (PATH-TO-FULL-ERP §3.3 recurring/subscription billing).
- **Recurring / subscription billing.** Standing orders, renewals, proration — deferred.
- **Complex pricing / promotions.** v1 supports the price-list price + line discount + order-level discount
  (the shipped pricing depth). Volume/tier discounts, promotions/campaigns, contract pricing, seasonal pricing,
  a price-rule engine with effective dates, and bundles are deferred (PATH-TO-FULL-ERP §3.3 pricing depth).
- **Delivery-document depth (picking / packing / dispatch).** v1's delivery is the **stock-issue + COGS
  document** that records what shipped. A formal **picking slip → packing → dispatch-confirmation** workflow
  (and the delivery-note PDF) is deferred (PATH-TO-FULL-ERP §3.3 delivery notes; rides the X.1 document
  enabler).
- **Pro-forma invoices.** A non-binding pro-forma estimate distinct from the quotation is deferred
  (PATH-TO-FULL-ERP §3.3). The quotation serves the priced-offer need in v1.
- **Refund tenders on a return.** v1's return raises a **credit note** (reduces what the customer owes). A
  **cash / mobile-money refund** paid out on a return is a **Cash & Bank** act (a payment against the credit
  note) — deferred to the returns-refund slice; the credit note is the v1 outcome.
- **Commission on the order / delivery.** Agent attachment is captured (as on the invoice); commission
  **calculation** (rates/tiers/accrual/runs) remains deferred (OQ-PARTY-03, sales depth).
- **Multi-currency Order-to-Cash.** v1 is **base currency** (TZS) on quotes / orders / deliveries / invoices /
  returns; FX on an order placed in a foreign currency is deferred (multicurrency.md / X.6).

### Explicitly NOT this module

- **The invoice channel's revenue/AR/VAT posting** — **Sales** (ADR-0008) owns the invoice, the
  `InvoiceTotalsCalculator`, finalise, the `INV-####` numbering, and the revenue posting (gl.md §3.1); this
  slice **raises an invoice from a delivery** through that channel (revenue-only) and **reuses** its totals
  math for the discounted-net VAT — it does not reimplement the invoice or its posting.
- **The stock quantity engine + the valuation/COGS engine** — **Stock** (ADR-0010 / ADR-0020) owns
  `stock_movements` / `stock_on_hand`, the moving-average cost, the COGS posting on issue, and the recipe
  explosion; this slice makes the **delivery** the **trigger** for the issue+COGS (and the **return** the
  trigger for the reversal) and adds the **reservation** concept — it does not change the costing method, the
  quantity model, or the recipe explosion.
- **The General Ledger** — **GL** (ADR-0013) owns the books; this slice posts **nothing new directly** — it
  drives the **existing** revenue posting (via the invoice), the **existing** COGS posting (via the delivery,
  ADR-0020), and the **existing** credit-note reversal (via AR) — all through their shipped paths.
- **The AR sub-ledger + the credit note** — **AR** (ADR-0014) owns open items, receipts, ageing, and the
  `ArCreditNoteService`; this slice **raises** a credit note on a return through that service and creates an AR
  open item via the SO-sourced invoice — it does not own the AR posting or the credit-note mechanism.
- **Picking / packing / dispatch logistics, carrier integration, delivery-note PDFs** — deferred (§2); the v1
  delivery is the stock-issue + COGS event, not a warehouse-logistics workflow.
- **POS, drop-ship, multi-warehouse allocation, MRP, blanket/recurring orders, promotions** — all deferred
  (§2).

## 3. The model: quote, order, reservation/ATP, delivery, invoicing, discounts, returns, the seam, and the status rollups

### 3.1 Quotation → conversion (reserves nothing, posts nothing)

A **quotation** (`QUOTE-####`) is a **non-binding priced offer**: lines (product, qty, unit price defaulted
from the applicable price list, optional line discount) + a **validity date**. Its lifecycle:

- **DRAFT** — being composed; lines editable.
- **SENT** — issued to the customer (the `QUOTE-####` is allocated at send, mirroring the invoice's
  number-at-finalise discipline — OQ-SO-07).
- **ACCEPTED** — the customer accepts; the system **converts** the quote to a **sales order**, **copying** its
  lines and pricing (the SO opens in DRAFT or CONFIRMED per the conversion policy — OQ-SO-07; recommended the
  SO opens **DRAFT** so it can be confirmed (and reserve) deliberately).
- **EXPIRED** — the validity date passed without acceptance (a quote past validity cannot be accepted —
  BR-SO-01).
- **REJECTED** — the customer declined.

A quote **reserves no stock and posts no GL** at any stage — it commits nothing (BR-SO-01). Conversion copies
the lines/pricing into the SO; what may be **edited** on conversion (re-price to current list? keep the quoted
price?) is the quote→order edit policy (OQ-SO-07; recommended: keep the quoted pricing as the SO's starting
pricing — the customer accepted *that* offer).

### 3.2 Sales order + the reservation on confirm (available = on_hand − reserved)

A **sales order** (`SO-####`) carries **order lines** — product, **qty ordered**, **unit price** (defaulted
from the applicable price list, overridable like a sale line with `SALES.INVOICE.OVERRIDE`, sales.md
FR-SALES-08), an optional **line discount** — plus an optional **order-level discount** (§3.6). Lifecycle:

- **DRAFT** — being composed; lines editable; **no reservation, no posting**.
- **CONFIRMED** — the order is committed: the system **reserves** the ordered qty (§3.3). From here, deliveries
  and invoices hang off it.
- **PARTIALLY_FULFILLED / FULFILLED** — derived as deliveries ship part / all of the ordered qty (§3.7).
- **PARTIALLY_INVOICED / INVOICED** — derived as invoices bill part / all of the delivered qty (§3.7).
- **CLOSED** — fully delivered **and** fully invoiced (§3.7).
- **CANCELLED** — the order is cancelled; the **remaining reservation is released** (§3.3). (The policy on
  cancelling an SO with deliveries already made — recommended: cancel only the **undelivered** balance, the
  delivered portion stands and is invoiced/returned normally — OQ-SO-04.)

### 3.3 Reservation (soft allocation) + available-to-promise

**Confirming** an SO **reserves** each line's ordered qty against on-hand. The reservation is a **soft
allocation**:

- it **records that the quantity is spoken for** — `reserved` rises by the ordered qty;
- it **moves no stock** (nothing on the `stock_movements` ledger) and **posts no GL** (BR-SO-03);
- **available-to-promise = on_hand − reserved** — the quantity the business can still commit to a *new* order
  (BR-SO-05).

**Over-reservation** — confirming an SO when the ordered qty **exceeds** available on-hand — is **allowed** (→
**negative available**, **flagged** for the operator), because **backorders are supported**: a business takes
an order it cannot yet fulfil and delivers when stock arrives (recommended default — OQ-SO-02). A delivery
**converts** the reservation into an **issue** (the delivered portion's reservation is released as the stock
actually leaves — §3.4); a **cancel** releases the remaining reservation (BR-SO-06). The **reservation model**
(an additive `reserved` quantity on `stock_on_hand` and/or a per-SO-line reservation ledger) is the
architect's (OQ-SO-01); v1 is **single-location** (reserve against the SO's company-branch on-hand —
multi-warehouse allocation deferred, §2).

### 3.4 Delivery / fulfilment → issues stock + COGS, releases the reservation (THE engine driver)

A **delivery** (`DEL-####`) ships some/all of an SO's **open** qty (open = ordered − already fulfilled). On
creating a delivery, for each delivered line:

- the system **issues the stock** — a real `stock_movements` deduction (the shipped `StockPostingService`
  primitive), reducing on-hand by the delivered qty;
- it **posts DR `5100 COGS` / CR `1300 Inventory`** at **delivered qty × the current moving-average cost** —
  **reusing the ADR-0020 valuation/COGS engine** (incl. **recipe explosion** for a composed product, each
  stockable component at its own average; a non-stockable / non-composed line posts no COGS, ADR-0010 D-8);
- it **releases** the matching **reservation** (the delivered portion converts reservation → issue — the
  reserved qty falls by the delivered qty, BR-SO-06).

The delivery is **the moment goods physically leave and the cost is incurred** — this is **COGS-at-delivery**,
THE KEY SEAM (§1). **Partial deliveries** are allowed: the **unshipped balance** (ordered − fulfilled) stays
open on the SO as a **backorder** (BR-SO-07); one SO line may be delivered over several deliveries. A delivery
**cannot deliver more than the SO line's open qty** (BR-SO-11). Whether the delivery emits its own
stock-issue/COGS event (option b) or the seam is keyed off the invoice's origin (option a) is the architect's
mechanism (OQ-SO-03) — the **behaviour** (delivery issues + COGS, idempotently, append-only) is fixed.

### 3.5 Invoicing the delivery → revenue only (no stock re-issue), partial invoicing

An **invoice** is raised against a **delivery** (referencing the delivery / SO), for the **delivered** qty. It
goes through the **shipped invoice channel** (ADR-0008): the `InvoiceTotalsCalculator` computes net / VAT /
gross (tax-exclusive, per band) on the discounted net (§3.6), the invoice gets its `INV-####` at finalise, and
on finalise it posts **REVENUE ONLY** — **DR AR/Cash, CR Sales Revenue, CR VAT** (the shipped revenue posting,
gl.md §3.1) — and creates an **AR open item** for a credit sale. **It MUST NOT re-issue stock or re-post
COGS** — the delivery already did (BR-SO-09, the hard rule). A direct walk-in invoice (no delivery in front of
it) **still** issues + COGS at finalise (unchanged).

**Partial invoicing**: one SO may yield **several invoices** as deliveries occur — invoice each delivery's
delivered qty (or aggregate several deliveries into one invoice — the invoicing granularity is a non-blocking
detail, recommended **invoice per delivery** for the clean delivery↔invoice trace, OQ-SO-04). An invoice
**cannot invoice more than the delivered (and not-yet-invoiced) qty** (BR-SO-11).

### 3.6 Order-level + line discounts → flow to the invoice totals, VAT on the discounted net

Discounts apply **on top of the price-list price**, **before VAT**:

- a **per-line discount** (% or amount) reduces that order line's net (the tax-exclusive taxable base);
- an **order-level discount** (% or amount) reduces the whole order, **apportioned across lines pro-rata to
  each line's net** (so each line's taxable base is reduced fairly and the per-band VAT summary stays correct
  after the order discount).

When the delivery is invoiced, the discounts flow through to the invoice, and **VAT is computed on the
discounted net** — the **identical algorithm** the shipped `InvoiceTotalsCalculator` already uses for the sale
line + document discount (sales.md D-4): round per line, apportion the order/document discount pro-rata before
VAT, compute VAT on the discounted net, sum the bands, round HALF_UP at each boundary, identical
backend/frontend (BR-SO-10, NFR-SO-03). The discount rounding / apportionment detail is OQ-SO-06 (recommended:
reuse the `InvoiceTotalsCalculator` rule unchanged).

### 3.7 Status rollups (derived from the lines' fulfilled / invoiced quantities)

The SO's status is **derived**, never free-set, from each order line's **fulfilled** (delivered) and
**invoiced** quantities (BR-SO-12):

- **CONFIRMED** — reserved, nothing delivered.
- **PARTIALLY_FULFILLED** — some (not all) ordered qty delivered.
- **FULFILLED** — all ordered qty delivered (no backorder remaining).
- **PARTIALLY_INVOICED** — some (not all) delivered qty invoiced.
- **INVOICED** — all delivered qty invoiced.
- **CLOSED** — **fully delivered AND fully invoiced** (the order is complete).

(Fulfilment and invoicing progress in parallel — an SO can be PARTIALLY_FULFILLED and PARTIALLY_INVOICED at
once; the status surfaces both dimensions, presented per the architect's chosen status model. CANCELLED is a
terminal state distinct from the rollup.)

### 3.8 Sales return / RMA → stock back in + credit note (staged second)

A **return** (`RET-####`) is raised **against a delivery** (the goods being returned shipped on that
delivery), for some/all of the delivered qty:

- the returned qty comes **back into stock** — a real `stock_movements` **IN**, **reversing the
  COGS/inventory at the original issued cost** of the delivery it returns against (recommended — symmetric, no
  phantom gain/loss from average drift, OQ-SO-05, mirroring OQ-INV-02): **DR `1300 Inventory` / CR `5100
  COGS`** at returned qty × the delivery's original issue cost (reusing the ADR-0020 reversal engine, incl.
  recipe explosion);
- it raises a **credit note** (reusing `ArCreditNoteService`, ADR-0014) that **reverses the revenue / AR /
  VAT** for the returned value (DR Sales Revenue, DR VAT, CR AR/Cash) — the customer's open balance falls (or a
  refund is owed — refund tender deferred, §2).

**Partial returns** are allowed; a return **cannot return more than the delivered qty** (less anything already
returned) — BR-SO-11. The return is **against a delivery**, so the original issued cost and the original
revenue are both reconstructable. (The credit note origin — a new `RETURN` origin alongside the shipped
STANDALONE / SALE_VOID — is the architect's, OQ-SO-05.)

## 4. Actors / personas

- **Sales officer / order clerk** — creates **quotations** (`SALES.QUOTE.CREATE`), **sends** them
  (`SALES.QUOTE.SEND`), records customer **acceptance** → converts to an SO (`SALES.QUOTE.ACCEPT`), creates and
  edits **sales orders** (`SALES.ORDER.CREATE`), and **confirms** them (`SALES.ORDER.CONFIRM` — the act that
  reserves stock). The front line of order capture.
- **Sales manager / supervisor** — **cancels** orders (`SALES.ORDER.CANCEL` — releasing reservations),
  approves price overrides on order lines (the shipped `SALES.INVOICE.OVERRIDE`), and oversees the backorder /
  available-to-promise picture. The authority over the order book.
- **Stock controller / warehouse officer** — creates **deliveries** (`SALES.DELIVERY.CREATE` — the act that
  **issues stock and posts COGS** and releases the reservation) against confirmed orders; processes
  **returns** (`SALES.RETURN.CREATE` — the stock-in + COGS reversal). The operator whose acts **move the
  inventory asset** for an SO-sourced sale.
- **Accountant / AR officer** — **invoices** the delivered qty through the existing invoice channel
  (`SALES.INVOICE.CREATE` — posting **revenue only**), manages the resulting **AR open items** and the
  **credit notes** the returns raise (ADR-0014), and reconciles that **COGS posted at delivery** and **revenue
  posted at invoice** tie out. The owner of the financial consequences of the O2C flow.
- **Owner / general manager** — reads the **order book** (open orders, backorders, fulfilment / invoicing
  status) and the **margin** (revenue − COGS) the O2C flow produces (via Reporting, T2.3); the consumer of the
  end-to-end outcome.
- *(No new human actor on the **COGS-at-delivery** and **revenue-at-invoice** postings themselves — they are
  **system-posted in-request / via the outbox** on the existing engine paths (the delivery drives the ADR-0020
  COGS posting; the invoice-finalise drives the shipped revenue posting), under the acting company/branch
  scope, like the shipped auto-posters. The reservation is a soft allocation, posting nothing.)*

## 5. Functional requirements

> IDs are `FR-SO-NN`. Each is a crisp, testable, **ratified** statement. "Available" = on_hand − reserved
> (ATP); "reserve" = a soft allocation that moves no stock and posts no GL; "issue + COGS" = a real
> `stock_movements` deduction plus the ADR-0020 DR COGS / CR Inventory posting at the moving average;
> "revenue-only invoice" = the shipped finalise revenue posting (DR AR/Cash, CR Sales Revenue, CR VAT) with
> **no** stock issue / COGS; "post to GL" via the synchronous / outbox paths the shipped modules use.

### Quotation

- **FR-SO-01** A user with `SALES.QUOTE.CREATE` may create a **quotation** (`QUOTE-####`) with **draft
  pricing** — lines (product, quantity, unit price defaulted from the applicable price list, optional line
  discount %/amount) — and a **validity date**. A draft quote is editable; it **reserves no stock and posts no
  GL** (BR-SO-01).
- **FR-SO-02** A quotation has the lifecycle **DRAFT → SENT → ACCEPTED → EXPIRED / REJECTED**. A user with
  `SALES.QUOTE.SEND` **sends** a draft (allocating `QUOTE-####`); a quote past its **validity date** is
  **EXPIRED** and **cannot be accepted** (BR-SO-01).
- **FR-SO-03** A user with `SALES.QUOTE.ACCEPT` may **accept** a SENT quotation, which **converts it to a sales
  order**, **copying** the quote's lines and pricing into the SO (BR-SO-01). The conversion edit semantics
  (re-price vs keep the quoted price; SO opens DRAFT vs CONFIRMED) follow the conversion policy (OQ-SO-07;
  recommended: keep the quoted pricing, SO opens DRAFT).

### Sales order + reservation

- **FR-SO-04** A user with `SALES.ORDER.CREATE` may create a **sales order** (`SO-####`) with **order lines**
  (product, **qty ordered**, **unit price** defaulted from the applicable price list and overridable with
  `SALES.INVOICE.OVERRIDE`, optional **line discount** %/amount) and an optional **order-level discount**
  (%/amount). A DRAFT SO is editable; it **reserves nothing and posts nothing** until confirmed (BR-SO-02).
- **FR-SO-05** A user with `SALES.ORDER.CONFIRM` may **confirm** a DRAFT sales order. Confirming **reserves**
  each line's ordered qty against on-hand — **available = on_hand − reserved** — a **soft allocation** that
  **moves no stock and posts no GL** (FR-SO-07, BR-SO-02/03).
- **FR-SO-06** A user with `SALES.ORDER.CANCEL` may **cancel** a sales order. Cancelling **releases the
  remaining (undelivered) reservation** (FR-SO-08, BR-SO-06). An SO with deliveries already made cancels only
  the **undelivered** balance; the delivered portion stands (OQ-SO-04).

### Reservation / available-to-promise

- **FR-SO-07** On **confirming** an SO, the system **reserves** the ordered qty: `reserved` rises by the
  ordered qty and **available = on_hand − reserved** falls. The reservation is a **soft allocation** — **no
  `stock_movements` row, no GL entry** (BR-SO-03/05). The reservation model (additive `reserved` on
  `stock_on_hand` and/or a reservation ledger) is the architect's (OQ-SO-01).
- **FR-SO-08** A reservation is **released** when its qty is **delivered** (converted to an issue —
  FR-SO-09) or the SO is **cancelled** (FR-SO-06). After full delivery / cancellation, the SO holds **no open
  reservation** (BR-SO-06). **Over-reservation beyond on-hand is allowed** (→ negative available, **flagged**),
  since backorders are supported (BR-SO-05, OQ-SO-02).

### Delivery / fulfilment → issue + COGS

- **FR-SO-09** A user with `SALES.DELIVERY.CREATE` may create a **delivery** (`DEL-####`) against a CONFIRMED
  SO, shipping some/all of each line's **open** qty (ordered − already fulfilled). Creating the delivery, for
  each delivered line: **(a)** **issues the stock** (a real `stock_movements` deduction reducing on-hand);
  **(b)** **posts DR `5100 COGS` / CR `1300 Inventory`** at **delivered qty × the current moving-average cost**
  (reusing the ADR-0020 engine, incl. **recipe explosion**; a non-stockable / non-composed line posts no COGS);
  **(c)** **releases** the matching reservation (BR-SO-04/06). The delivery is the stock-issue + COGS trigger
  for an SO-sourced sale (THE KEY SEAM, §1).
- **FR-SO-10** **Partial deliveries** are allowed: a delivery may ship **less than** an SO line's open qty; the
  **unshipped balance** (ordered − fulfilled) **remains open** on the SO as a **backorder** and may be
  delivered later (one SO line over several deliveries) (BR-SO-07). A delivery **cannot deliver more than the
  line's open qty** (BR-SO-11).

### THE KEY SEAM — invoice-from-delivery posts revenue only; direct invoice keeps issuing stock

- **FR-SO-11** An invoice **created from an SO/delivery** posts **REVENUE ONLY** on finalise — **DR AR/Cash, CR
  Sales Revenue, CR VAT** (the shipped revenue posting) — and **MUST NOT re-issue stock or re-post COGS** (the
  delivery already issued + posted COGS). A **direct** invoice (no SO — the existing walk-in channel) **keeps
  issuing stock + COGS on finalise** exactly as today (BR-SO-09 — the hard rule). The **mechanism** (invoice
  origin/flag vs delivery-owns-the-stock-event) is the architect's (OQ-SO-03).

### Partial invoicing

- **FR-SO-12** A user with `SALES.INVOICE.CREATE` may **invoice a delivery's delivered qty** (the invoice
  **references** the delivery / SO), through the **shipped invoice channel** (revenue / AR / VAT). **One SO may
  yield several invoices** as deliveries occur. An invoice **cannot invoice more than the delivered (and not
  yet invoiced) qty** (BR-SO-11). An SO is **INVOICED** when all delivered qty is invoiced; **CLOSED** when
  fully delivered **and** fully invoiced (BR-SO-08/12).

### Discounts

- **FR-SO-13** A sales order supports a **per-line discount** (% or amount) and an **order-level discount** (%
  or amount, **apportioned across lines pro-rata to each line's net**), applied on top of the price-list price
  **before VAT**. When invoiced, the discounts flow through to the invoice totals and **VAT is computed on the
  discounted net** (the tax-exclusive base) using the **shipped `InvoiceTotalsCalculator` algorithm** —
  identical backend/frontend, HALF_UP at each boundary (BR-SO-10, NFR-SO-03; the discount rounding /
  apportionment detail is OQ-SO-06).

### Return / RMA (staged second)

- **FR-SO-14** A user with `SALES.RETURN.CREATE` may create a **return** (`RET-####`) **against a delivery**,
  for some/all of the delivered qty: **(a)** the returned qty comes **back into stock** (a real
  `stock_movements` **IN**) **reversing the COGS/inventory at the original issued cost** of that delivery — **DR
  `1300 Inventory` / CR `5100 COGS`** (reusing the ADR-0020 reversal, incl. recipe explosion); **(b)** a
  **credit note** is raised (reusing `ArCreditNoteService`, ADR-0014) reversing the **revenue / AR / VAT** for
  the returned value. **Partial returns** allowed; a return **cannot return more than the delivered qty** (less
  already-returned) (BR-SO-11). The reversal cost basis (original issued cost) is OQ-SO-05.

### Status rollups, scope, permissions, numbering

- **FR-SO-15** The SO's status is **derived** from its lines' fulfilled / invoiced quantities — CONFIRMED →
  PARTIALLY_FULFILLED → FULFILLED as deliveries occur, → PARTIALLY_INVOICED → INVOICED as invoices occur, →
  CLOSED when fully delivered **and** fully invoiced; CANCELLED is terminal. The status is never free-set
  (BR-SO-12).
- **FR-SO-16** Order-to-Cash is **scoped per company**; every quote, order, order line, reservation, delivery,
  and return belongs to exactly one company; **no read crosses company scope**; `assertCanActIn` guards **every
  read path**; **audit** records **every state transition** (quote sent/accepted/expired/rejected; SO
  confirmed/cancelled; delivery created; invoice raised; return created) with actor, action, target, and
  company context (BR-SO-13, NFR-SO-01/04).
- **FR-SO-17** The new operations are **gated by IAM permissions**: `SALES.QUOTE.VIEW / CREATE / SEND /
  ACCEPT`, `SALES.ORDER.VIEW / CREATE / CONFIRM / CANCEL`, `SALES.DELIVERY.VIEW / CREATE`, `SALES.RETURN.VIEW /
  CREATE` (`MODULE.RESOURCE.ACTION`, reusing the `SALES.*` conventions); the invoice raised from a delivery
  rides the existing `SALES.INVOICE.*` perms. Numbering uses `code_sequence` kinds **QUOTE** (`QUOTE-####`),
  **SO** (`SO-####`), **DELIVERY** (`DEL-####`), **RETURN** (`RET-####`), per company, concurrency-safe; the
  invoice keeps `INV-####`. Exact codes/kinds are seeded with the module (the V18 migration).

## 6. Business rules (invariants)

> Ratified. These are the Order-to-Cash invariants; a violation that double-issues stock / double-counts COGS,
> over-delivers / over-invoices / over-returns, or breaks the revenue↔COGS / delivery↔reservation ties is a
> finance-grade / inventory-grade defect (a release blocker).

- **BR-SO-01 — A quotation reserves nothing, posts nothing; accept converts to an SO.** A quote (DRAFT → SENT →
  ACCEPTED → EXPIRED / REJECTED) commits **no** stock and **no** GL at any stage; a quote **past its validity
  date** is EXPIRED and **cannot be accepted**; **acceptance converts** it to a sales order, copying lines +
  pricing (FR-SO-01/02/03).
- **BR-SO-02 — Confirming a sales order reserves stock; a DRAFT SO reserves nothing.** A DRAFT SO is editable
  and reserves/posts nothing; **CONFIRMED** reserves each line's ordered qty (FR-SO-04/05). The status flows
  DRAFT → CONFIRMED → (PARTIALLY_)FULFILLED → (PARTIALLY_)INVOICED → CLOSED, with CANCELLED terminal (BR-SO-12).
- **BR-SO-03 — A reservation is a soft allocation: no stock movement, no GL.** Reserving records that a
  quantity is spoken for — it writes **no `stock_movements` row and no journal entry**. The physical on-hand and
  the books are untouched by a reservation (FR-SO-07).
- **BR-SO-04 — A delivery issues stock + posts COGS at the moving average, and releases the reservation.**
  Creating a delivery, per delivered line: **issues the stock** (a real deduction), **posts DR `5100 COGS` / CR
  `1300 Inventory`** at delivered qty × the **current moving-average cost** (reusing ADR-0020, incl. recipe
  explosion; non-stockable / non-composed → no COGS), and **releases** the matching reservation (FR-SO-09).
  This is COGS-at-delivery (THE KEY SEAM, §1). Σ debits == Σ credits.
- **BR-SO-05 — Available-to-promise = on_hand − reserved; over-reservation is allowed (flagged).** The
  available-to-promise is **on_hand − reserved**. Confirming an SO **may reserve beyond on-hand** (→ negative
  available), **flagged** to the operator, because **backorders are supported** (recommended default — OQ-SO-02).
- **BR-SO-06 — Reservations release on delivery (converted to issue) or cancel.** A reservation falls by the
  **delivered** qty when a delivery ships (reservation → issue) and the **remaining** reservation is released on
  **cancel**; after full delivery / cancel the SO holds **no open reservation** (FR-SO-08).
- **BR-SO-07 — Partial delivery → backorder; the unshipped balance stays open.** A delivery may ship less than
  the open qty; the **unshipped balance** (ordered − fulfilled) **remains open** on the SO as a **backorder**,
  deliverable later (one SO line over several deliveries) (FR-SO-10).
- **BR-SO-08 — Invoice the delivered qty; an SO yields one or more invoices.** An invoice bills a **delivery's
  delivered** qty (referencing the delivery / SO); **one SO may yield several invoices** as deliveries occur.
  An SO is **INVOICED** when all delivered qty is invoiced; **CLOSED** when fully delivered **and** invoiced
  (FR-SO-12/15).
- **BR-SO-09 — THE KEY SEAM: an SO-sourced invoice posts revenue only and NEVER re-issues stock; a direct
  invoice always issues stock.** An invoice **created from an SO/delivery** posts **DR AR/Cash, CR Sales
  Revenue, CR VAT** and **must NOT issue stock or post COGS** — the **delivery already did** (FR-SO-11). A
  **direct** invoice (no SO — the walk-in channel) **keeps** issuing stock + COGS on finalise as today.
  Re-issuing an SO-sourced invoice's stock **double-deducts stock and double-counts COGS** — a **release
  blocker**. The mechanism is OQ-SO-03; the rule is fixed.
- **BR-SO-10 — Discounts flow to the VAT-exclusive net; VAT is computed on the discounted net.** A per-line
  discount and an order-level discount (apportioned across lines pro-rata to net) apply **before VAT**; VAT is
  computed on the **discounted net** using the **shipped `InvoiceTotalsCalculator` algorithm** (round per line,
  apportion the order discount pro-rata, VAT on discounted net, sum bands, HALF_UP at each boundary — sales.md
  D-4), **identical backend/frontend** (FR-SO-13, NFR-SO-03).
- **BR-SO-11 — Can't deliver / invoice / return more than the open / delivered qty.** A **delivery** cannot
  ship more than an SO line's **open** (ordered − fulfilled) qty; an **invoice** cannot bill more than the
  **delivered (not-yet-invoiced)** qty; a **return** cannot return more than the **delivered (less
  already-returned)** qty. Each is a **service-enforced** cross-document quantity guard (FR-SO-09/12/14).
- **BR-SO-12 — Status is derived from the lines' fulfilled / invoiced quantities, never free-set.** The SO's
  status (CONFIRMED / PARTIALLY_FULFILLED / FULFILLED / PARTIALLY_INVOICED / INVOICED / CLOSED) is **computed**
  from each line's ordered / fulfilled / invoiced quantities; a user does not set it directly (FR-SO-15).
- **BR-SO-13 — A return reverses COGS at the original issued cost + credit-notes the revenue, against a
  delivery.** A return (against a **delivery**) posts **DR `1300 Inventory` / CR `5100 COGS`** at returned qty ×
  the **original issued cost** of that delivery (recommended — symmetric, no phantom gain/loss, OQ-SO-05;
  mirrors OQ-INV-02) and raises a **credit note** (reusing `ArCreditNoteService`) reversing **revenue / AR /
  VAT** for the returned value. Partial returns allowed; capped at the delivered qty (FR-SO-14, BR-SO-11).
- **BR-SO-14 — Per-company isolation.** Every quote, order, order line, reservation, delivery, return, and
  derived figure **belongs to exactly one company**; no read or figure crosses company scope. Cross-company O2C
  leakage is a **release blocker** (NFR-SO-01), as for Sales/GL/AR/Stock.
- **BR-SO-15 — Base-currency Order-to-Cash (v1).** Quotes, orders, deliveries (COGS), invoices, and returns are
  in the company **base currency** (TZS in practice; ADR-0005 / sales.md BR-CUR); foreign-currency orders / FX
  are deferred (§2).
- **BR-SO-16 — Append-only state transitions; reversals reverse, never edit.** Every document state transition
  is **audited and append-only** (FR-SO-16, NFR-SO-04); the COGS posting (at delivery) and its reversal (at
  return), and the revenue posting (at invoice) and its reversal (at credit note / void) are **reversing
  entries**, never edits (gl.md BR-GL-02, ADR-0020 BR-INV-05). A finalised invoice is still voidable via the
  shipped void (sales.md FR-SALES-22).
- **BR-SO-17 — Idempotent stock / COGS effects.** The delivery's stock-issue + COGS and the return's stock-in +
  COGS-reversal are **idempotent** (the shipped `IdempotencyGuard` / `processed_events` discipline, ADR-0009);
  a redelivered / retried event moves stock and posts COGS **once**. A violation double-counts inventory /
  COGS — a **release blocker** (NFR-SO-02).

## 7. Process flows (happy path + main unhappy paths), ratified v1

### 7.1 Quote → accept → sales order — happy path
1. A sales officer creates a **quotation** (`SALES.QUOTE.CREATE`): lines (product, qty, unit price from the
   price list, optional line discount) + a **validity date**. DRAFT — reserves/posts nothing (FR-SO-01).
2. They **send** it (`SALES.QUOTE.SEND`) — `QUOTE-####` allocated; status SENT (FR-SO-02). It is **audited**.
3. The customer accepts; the officer **accepts** the quote (`SALES.QUOTE.ACCEPT`) — the system **converts** it
   to a **sales order** (DRAFT, lines + pricing copied — OQ-SO-07) (FR-SO-03, BR-SO-01). Audited.

### 7.2 Confirm the SO → reserve stock — happy path
1. The officer **confirms** the DRAFT SO (`SALES.ORDER.CONFIRM`) (FR-SO-05).
2. The system **reserves** each line's ordered qty: `reserved` rises, **available = on_hand − reserved** falls
   — a **soft allocation, no stock movement, no GL** (FR-SO-07, BR-SO-03). The SO is **CONFIRMED**. Audited.
3. A new order for the same product now sees the **reduced available-to-promise** (BR-SO-05).

### 7.3 Partial delivery → issue stock + COGS + backorder — happy path (THE engine driver)
1. The stock controller creates a **delivery** (`SALES.DELIVERY.CREATE`) against the CONFIRMED SO, shipping
   **part** of an SO line's open qty (e.g. 6 of 10) (FR-SO-09, FR-SO-10).
2. For the delivered line, the system **issues the stock** (a real `stock_movements` deduction of 6) **and
   posts DR `5100 COGS` / CR `1300 Inventory`** at 6 × the current moving-average cost (ADR-0020, incl. recipe
   explosion) **and releases** the reservation for the 6 delivered (reservation → issue) (BR-SO-04/06).
3. The **unshipped balance (4)** stays open on the SO as a **backorder**; the SO is **PARTIALLY_FULFILLED**
   (BR-SO-07/12). The delivery is `DEL-####`, audited; the COGS posting is audited (SYSTEM / handler).

### 7.4 Invoice the delivery → revenue only (no stock re-issue) — happy path
1. The accountant **invoices** the delivery's delivered qty (`SALES.INVOICE.CREATE`), the invoice
   **referencing** the delivery / SO (FR-SO-12).
2. The invoice goes through the **shipped channel**: `InvoiceTotalsCalculator` computes net / VAT / gross on
   the **discounted net** (§3.6); finalise allocates `INV-####` and posts **REVENUE ONLY** — **DR AR/Cash, CR
   Sales Revenue, CR VAT** — creating an AR open item for a credit sale (gl.md §3.1) (FR-SO-11).
3. **No stock is re-issued and no COGS re-posts** — the delivery already did (BR-SO-09, the hard rule). The SO
   becomes **PARTIALLY_INVOICED** (the 6 delivered are invoiced; the 4 backorder await delivery) (BR-SO-12).
4. When the backorder is later delivered (7.3 again) and invoiced (7.4 again), the SO becomes **FULFILLED** then
   **INVOICED**, and — fully delivered **and** invoiced — **CLOSED** (BR-SO-12).

### 7.5 Return → stock back in + credit note — happy path (staged second)
1. The stock controller creates a **return** (`SALES.RETURN.CREATE`) **against a delivery**, for some/all of
   the delivered qty (FR-SO-14).
2. The returned qty comes **back into stock** (a real `stock_movements` **IN**) **reversing COGS/inventory** at
   the **original issued cost** of that delivery: **DR `1300 Inventory` / CR `5100 COGS`** (ADR-0020 reversal)
   (BR-SO-13, OQ-SO-05).
3. A **credit note** is raised (`ArCreditNoteService`, ADR-0014) reversing the **revenue / AR / VAT** for the
   returned value (DR Sales Revenue, DR VAT, CR AR/Cash) — the customer's open balance falls (BR-SO-13). `RET-
   ####`, audited; the postings audited.

### 7.6 Cancel the SO → release the reservation — happy path
1. The sales manager **cancels** the SO (`SALES.ORDER.CANCEL`) (FR-SO-06).
2. The **remaining (undelivered) reservation is released** — `reserved` falls, **available rises** (BR-SO-06).
   An SO with deliveries cancels only the **undelivered** balance (OQ-SO-04). The SO is **CANCELLED**
   (terminal); audited.

### 7.7 Main unhappy paths
- **Accept an expired quote** (7.1.3) → **rejected**; a quote past its validity date is EXPIRED and cannot be
  accepted (BR-SO-01). Re-quote.
- **Over-deliver** (deliver more than an SO line's open qty, 7.3.1) → **rejected** (BR-SO-11); the delivery
  caps at the open qty.
- **Over-invoice** (invoice more than the delivered, not-yet-invoiced qty, 7.4.1) → **rejected** (BR-SO-11).
- **Over-return** (return more than the delivered, less-already-returned qty, 7.5.1) → **rejected** (BR-SO-11).
- **Confirm reserving beyond on-hand** (7.2.1) → **allowed, flagged** (negative available) — backorders are
  supported (BR-SO-05, OQ-SO-02); the SO confirms and the backorder is delivered when stock arrives.
- **Deliver with insufficient stock** (7.3.1, on-hand < open qty) → the **costed issue at negative on-hand**
  follows the ADR-0020 negative-stock cost rule (OQ-INV-01; recommended block the costed issue until a receipt
  establishes a cost) — the quantity may go negative per the shipped stock model, the **cost** rule is the
  inventory-valuation OQ; the unshipped balance stays a **backorder** (BR-SO-07).
- **An SO-sourced invoice re-issues stock** (a defect, 7.4) → **must not happen** (BR-SO-09); the invoice posts
  revenue only — re-issuing double-counts COGS, a **release blocker**. The seam mechanism (OQ-SO-03) guarantees
  it.
- **Redelivered delivery / return event** (7.3 / 7.5) → the **idempotency guard** short-circuits; stock moves
  and COGS posts **once** (BR-SO-17, NFR-SO-02).
- **Cancel an SO mid-fulfilment** (7.6, deliveries already made) → cancels only the **undelivered** balance;
  the delivered portion stands and is invoiced / returnable normally (OQ-SO-04).
- **Edit a CONFIRMED SO** (change a confirmed line's qty / pricing) → governed by the confirm edit policy
  (OQ-SO-07; recommended: a confirmed line is adjusted only by an explicit re-confirm that re-reserves, or the
  SO is cancelled and re-raised) — not a silent edit of a reserved line.

## 8. Non-functional

- **NFR-SO-01 — Tenant isolation.** Every quote, order, order line, reservation, delivery, return, and derived
  figure is scoped by `company_id` and goes through the tenant-predicate repository base (PROJECT-CONVENTIONS
  §3.2); `assertCanActIn` guards **every read path**. Cross-company O2C leakage is a **release blocker**, as for
  Sales/GL/AR/Stock (BR-SO-14).
- **NFR-SO-02 — Idempotency of the stock / COGS effects (a top correctness risk).** The delivery's
  stock-issue + COGS and the return's stock-in + COGS-reversal ride the shipped outbox / `IdempotencyGuard` /
  `processed_events` discipline (ADR-0009): a redelivered / retried event moves stock and posts COGS **once**.
  An integration test must deliver the same delivery/return event twice and assert stock + COGS move **once**.
  A violation double-counts inventory / COGS — a **release blocker** (BR-SO-17).
- **NFR-SO-03 — Money / discount correctness.** Every amount is a `Money` (amount + currency, ADR-0005) in the
  base currency; the discount apportionment + VAT-on-discounted-net computation **reuses the shipped
  `InvoiceTotalsCalculator`** (round per line, apportion the order discount pro-rata before VAT, HALF_UP at
  each boundary — sales.md D-4) so the totals are **identical backend and frontend**; a stored total must equal
  the recomputed total (the shipped test discipline). COGS uses the ADR-0020 moving average (HALF_UP base
  currency).
- **NFR-SO-04 — Audit on every state transition.** Every transition (quote sent / accepted / expired /
  rejected; SO confirmed / cancelled; delivery created; invoice raised; return created) and every costed
  posting (COGS at delivery, COGS reversal at return) is written to the append-only audit trail with actor (or
  SYSTEM for the engine-driven postings), action, target, timestamp, and company context (NFR-SO-01,
  sales.md / gl.md audit precedent).
- **NFR-SO-05 — Reservation concurrency.** Two confirmations (or a confirmation and a delivery) racing on the
  same product's on-hand must not lose a reservation or let `reserved` drift: the recompute of `reserved` /
  `available` reuses the `stock_on_hand` optimistic `@Version` (the ADR-0020 NFR-INV-05 precedent for racing
  receipts) — a lost reservation update is a defect. The reservation-release on delivery must serialise with
  the stock-issue (they touch the same `stock_on_hand` row).
- **NFR-SO-06 — Outbox / event choreography (delivery → stock).** The delivery → stock-issue + COGS effect, and
  the return → stock-in + COGS-reversal effect, flow via the **transactional outbox** (never in-memory events,
  which lose on crash — ADR-0009); the chosen mechanism (delivery emits its own event, or the seam keys off the
  invoice origin — OQ-SO-03) must keep the **same-TX-as-the-document** guarantee. The revenue posting rides the
  shipped invoice-finalise path.
- **NFR-SO-07 — Numbering concurrency.** Concurrent quotes / orders / deliveries / returns for the same company
  get distinct numbers (the `code_sequence` row-locked allocation — ADR-0007 D-6 — for kinds QUOTE / SO /
  DELIVERY / RETURN); different companies don't contend.
- **NFR-SO-08 — Forward-compatibility.** The v1 model must not preclude the deferred depth (§2): POS as a
  further channel, drop-ship, multi-warehouse / location-aware reservation, advanced ATP / MRP, blanket /
  recurring orders, complex pricing / promotions, refund tenders on returns, delivery-note PDFs, and
  multi-currency O2C. Building these is deferred; precluding them is a defect.
- **NFR-SO-09 — Timestamps** are UTC, displayed per company time zone (Africa/Dar_es_Salaam default). The
  **document dates** (quote date / validity date, order date, delivery date, return date) are business dates,
  distinct from the posting timestamps; the **delivery date** (which drives the COGS posting period) and the
  **invoice date** (which drives the revenue posting period) may differ, and each posting obeys the GL
  open-period rule (gl.md BR-GL-03).

## 9. Assumptions

- The dependency platform exists and is consumed as designed: the **invoice channel** (ADR-0008) posts revenue
  / AR / VAT via `InvoiceTotalsCalculator` + the shipped finalise path; the **valuation/COGS engine** (ADR-0020)
  posts DR COGS / CR Inventory at the moving average on a stock issue (and reverses it) — the **delivery now
  drives it, the return reverses it**; **`ArCreditNoteService`** (ADR-0014) reverses revenue / AR / VAT for the
  return; the **outbox** + `IdempotencyGuard` (ADR-0009), **Money** (ADR-0005), **`code_sequence`** (ADR-0007),
  **RBAC / `assertCanActIn` / audit** are the platform spine. All shipped.
- **`stock_on_hand` has no reserved / available column today** (V7 carries `quantity` + `reorder_level`; V17
  added `avg_cost` + `on_hand_value`). The **reservation model is new** — ADR-0021 introduces it (additive
  `reserved` on `stock_on_hand` and/or a reservation ledger; **available = on_hand − reserved** derived).
- **The `SaleFinalisedPayload`** is `{ invoiceUid, companyId, branchId, finalisedAt, lines:[{ productId,
  productUid, unitId, qtyInBase }] }` (the shipped DTO the `SaleIssueStockHandler` consumes). The seam
  mechanism (OQ-SO-03) decides whether a delivery emits a new payload (e.g. `DELIVERY.SHIPPED`) and an SO-
  sourced invoice's finalise carries an `issuesStock=false` flag / a distinct event, or the delivery owns the
  stock event outright — both keep the existing direct-invoice behaviour intact.
- **Document currency = company base (TZS) in practice** for v1 (sales.md §9 / BR-SO-15); the convert-at-entry
  step is identity in practice; the shape supports a foreign-currency order, FX depth deferred (§2).
- The **single location** assumption: v1 reserves / issues against the SO's company-branch on-hand (inventory
  valuation is single-location, OQ-INV-07); multi-warehouse allocation is deferred (§2).
- The **price defaulting + override** on an order line reuses the shipped sale-line discipline (price-list
  snapshot + `SALES.INVOICE.OVERRIDE`, sales.md FR-SALES-07/08); the order line snapshots its pricing as a sale
  line does (the architect confirms snapshot timing across quote→order→delivery→invoice, OQ-SO-07).

## 10. ACCEPTED SCOPE BOUNDARY — what Order-to-Cash v1 deliberately does and does NOT do (owner-accepted 2026-06-10)

> **Read this before building or consuming Order-to-Cash.** v1 delivers the **quote → order → reservation →
> delivery (partial + backorder) → partial invoicing → return/RMA** spine on the shipped invoice channel, with
> **the key seam** (stock issue + COGS at delivery, not invoice; SO-sourced invoices revenue-only) as a hard
> rule. The following are **deliberate boundaries**, owner-accepted; nobody may quietly assume otherwise.

1. **THE KEY SEAM is fixed behaviour, ADR-mechanism.** Stock issue + COGS **move to delivery time**; an
   **SO-sourced invoice posts revenue only and never re-issues stock**; a **direct (walk-in) invoice keeps
   issuing stock + COGS on finalise** (BR-SO-09). The *behaviour* is ratified; the *mechanism* (invoice
   origin/flag vs delivery-owns-the-stock-event) is ADR-0021's (OQ-SO-03). This is the load-bearing change —
   getting it wrong double-counts COGS.
2. **Reservation is a SOFT allocation (no stock movement, no GL).** Confirming reserves; **available = on_hand
   − reserved**; **over-reservation beyond on-hand is allowed and flagged** (backorders are supported). A
   reservation is not a `stock_movements` row and not a journal entry (BR-SO-03/05).
3. **Delivery drives the ADR-0020 engine; return reverses it.** The delivery is the stock-issue + COGS trigger
   (incl. recipe explosion); the return is the stock-in + COGS-reversal trigger (at the **original issued
   cost**, OQ-SO-05). This slice does **not** change the costing method, the recipe explosion, or the GL
   posting — it changes the **trigger**.
4. **The build is STAGED.** The **core O2C spine** (quote → SO → reserve → deliver → invoice, with the seam +
   discounts) ships **first**; **returns / RMA** ship **second** (they reuse the ADR-0020 reversal +
   `ArCreditNoteService`, so they are additive on the spine). The increment is **large** — ADR-0021 designs the
   whole model, the build sequences it (project-manager).
5. **POS, drop-ship, multi-warehouse allocation, advanced ATP/MRP, blanket/recurring orders, and complex
   pricing/promotions are DEFERRED** (§2) — accepted boundaries, not precluded by the v1 model (NFR-SO-08).
6. **Delivery-document logistics depth (picking / packing / dispatch / delivery-note PDF) is DEFERRED.** The
   v1 delivery is the **stock-issue + COGS document** that records what shipped — not a warehouse-logistics
   workflow (§2; PDF rides the X.1 enabler).
7. **Refund tenders on a return are DEFERRED.** A return raises a **credit note** (reduces what the customer
   owes); a **cash / mobile-money refund** paid out is a Cash & Bank act against the credit note — deferred
   (§2). The credit note is the v1 outcome.
8. **Multi-currency O2C is DEFERRED.** v1 is **base currency** (BR-SO-15); FX on a foreign-currency order is
   deferred (multicurrency.md / X.6), not precluded.

All boundaries are additive by design (NFR-SO-08); none is precluded by the v1 model.

## 11. Open questions — status after ratification (2026-06-10)

> The **full Order-to-Cash scope** the owner ratified (quotation stage; SO lifecycle + reservation; soft
> reservation / ATP; delivery (partial + backorder) → issue + COGS; the key seam — SO-sourced invoices
> revenue-only / direct invoices keep issuing; partial invoicing; order/line discounts; returns/RMA;
> permissions / numbering / scope) is **RESOLVED 2026-06-10** (see §2/§5/§6/§7). **No ADR-0021-blocking open
> question remains.** What stays open is detail with a recommended default; the architecturally meaty items are
> **decisions ADR-0021 makes**, not requirements blockers (the *behaviour* is fixed).

### The ADR-0021 design seams (DECISIONS the architect makes — do NOT block the requirements)

- **OQ-SO-01 — The reservation model.** `stock_on_hand` has **no reserved / available column today**. How is a
  soft reservation tracked — an **additive `reserved` quantity on `stock_on_hand`** (with **available = on_hand
  − reserved** derived), a **per-SO-line reservation ledger**, or both? *Recommended default:* an additive
  `reserved` on `stock_on_hand` for the fast available-to-promise read **plus** a per-SO-line reservation record
  for traceability and release. *Decider:* architect (ADR-0021). *Blocks ADR-0021:* **NO** — it **is** the
  model decision.
- **OQ-SO-02 — Over-reservation / backorder policy.** May confirming an SO reserve **beyond** on-hand (→
  negative available)? *Recommended default:* **allow** (negative available, **flagged**), because backorders
  are supported (BR-SO-05). *Decider:* owner (sales policy). *Blocks ADR-0021:* **NO** — allow-and-flag is the
  default; a block-on-over-reserve is a one-line policy alternative.
- **OQ-SO-03 — The invoice-origin mechanism for the stock-issue seam (the load-bearing one).** How does the
  invoice-finalise path know **not** to issue stock for an SO-sourced invoice while a direct invoice still
  does? **(a)** an **invoice origin/flag** (DIRECT vs SO/DELIVERY) read by the issue path (e.g. a
  `SALE.FINALISED` payload flag `issuesStock=false` or a distinct revenue-only event), **or (b)** the
  **delivery owns the stock event** (a new `DELIVERY.SHIPPED` drives the issue + COGS, and SO-sourced invoice-
  finalise emits a revenue-only posting, never a stock event). *Recommended default:* **(b) the delivery owns
  the stock event** — it keeps the direct-invoice path **completely untouched** and the SO-sourced invoice
  simply never emits a stock event. *Decider:* architect (ADR-0021). *Blocks ADR-0021:* **NO** — it **is** the
  seam decision; both reconcile to BR-SO-09.
- **OQ-SO-04 — Reservation-release timing + invoicing/cancel granularity.** (a) A delivery releases the
  **delivered** portion's reservation (reservation → issue); a cancel releases the **remaining** reservation —
  confirm the exact release points. (b) Invoice **per delivery** vs **aggregate several deliveries into one
  invoice**. (c) Cancelling an SO with deliveries made cancels only the **undelivered** balance. *Recommended
  defaults:* release the delivered portion at delivery + the remaining at cancel; **invoice per delivery** (the
  clean delivery↔invoice trace); cancel only the undelivered balance. *Decider:* architect / owner. *Blocks
  ADR-0021:* **NO** — defaults stand; alternatives are additive.
- **OQ-SO-05 — Return cost basis + the credit-note origin.** A return reverses COGS at the **original issued
  cost** of the delivery (recommended — symmetric, no phantom gain/loss, mirrors OQ-INV-02) vs the
  **now-current** average; and the credit-note **origin** is a **new `RETURN`** value alongside the shipped
  STANDALONE / SALE_VOID. *Recommended default:* original issued cost; a `RETURN` credit-note origin. *Decider:*
  architect (ADR-0021) + owner (finance). *Blocks ADR-0021:* **NO** — original-cost is the default;
  current-average is discouraged.
- **OQ-SO-06 — Discount rounding / apportionment.** The order-level discount apportioned across lines pro-rata
  to net, before VAT, HALF_UP — confirm this **reuses the shipped `InvoiceTotalsCalculator` algorithm
  unchanged** (sales.md D-4) so the SO totals and the invoice totals agree to the cent. *Recommended default:*
  reuse `InvoiceTotalsCalculator` unchanged. *Decider:* architect (ADR-0021) + owner (finance) on display dp.
  *Blocks ADR-0021:* **NO** — confirm before go-live.
- **OQ-SO-07 — Quote→order + confirm edit semantics + numbering timing.** (a) On quote acceptance, **re-price
  to current list or keep the quoted price**, and does the SO open **DRAFT or CONFIRMED**? (b) What may be
  **edited** on a CONFIRMED SO (a reserved line) — a re-confirm that re-reserves, or cancel-and-re-raise? (c)
  When is each number allocated (`QUOTE-####` at send? `SO-####` at create or confirm? `DEL-####` /
  `RET-####` at create)? *Recommended defaults:* keep the quoted pricing, SO opens **DRAFT**; a confirmed line
  is adjusted by an explicit re-confirm (re-reserve) or cancel-and-re-raise (no silent edit); allocate
  `QUOTE-####` at send, `SO-####` at create, `DEL-####` / `RET-####` at create. *Decider:* architect / owner.
  *Blocks ADR-0021:* **NO** — defaults stand; alternatives are additive.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0021)

- **OQ-SO-08 — POS as a further channel.** Deferred (§2). *Recommended default:* the walk-in invoice is the
  over-the-counter channel in v1; POS (sessions / float / X-Z) is a later channel on the same spine. *Decider:*
  owner. *Blocks ADR-0021:* **NO** — deferred, not precluded (NFR-SO-08).
- **OQ-SO-09 — Multi-warehouse / location-aware reservation + allocation.** Deferred (§2). *Recommended
  default:* single-location reserve/issue against the SO's company-branch on-hand; per-location allocation lands
  with Stock multi-location. *Decider:* owner. *Blocks ADR-0021:* **NO** — deferred, not precluded.
- **OQ-SO-10 — Refund tender on a return.** Deferred (§2). *Recommended default:* a return raises a **credit
  note**; a cash/mobile-money refund is a Cash & Bank act against the credit note, later. *Decider:* owner.
  *Blocks ADR-0021:* **NO** — deferred.
- **OQ-CUR-03 (carried) — Rounding mode + TZS decimals.** Confirm the discount apportionment, the VAT-on-
  discounted-net, the COGS-at-delivery, and the return reversal all round identically backend/frontend
  (NFR-SO-03). *Recommended default:* HALF_UP, TZS = 0 dp display, reuse `InvoiceTotalsCalculator` + the
  ADR-0020 average precision. *Decider:* owner (finance input). *Blocks ADR-0021:* **NO** for the model;
  **confirm before go-live**.

## 12. Out of scope for v1 (deferred — restated)

POS (sessions / float / X-Z / offline, T2.1 separate channel); drop-ship / third-party fulfilment;
multi-warehouse / location-aware reservation + allocation (ties to Stock multi-location, PATH-TO-FULL-ERP
§3.5); advanced ATP / capable-to-promise / MRP (§3.6); blanket / standing / recurring / subscription orders;
complex pricing / promotions / campaigns / contract / tiered / bundle pricing (sales pricing depth);
delivery-document logistics depth (picking / packing / dispatch / delivery-note PDF — rides X.1); pro-forma
invoices; refund tenders on returns (a Cash & Bank act, deferred); commission calculation on the order /
delivery (OQ-PARTY-03); and multi-currency Order-to-Cash (FX on a foreign-currency order — multicurrency.md /
X.6). Each is tracked for a later increment; none is precluded by the v1 model (NFR-SO-08).
