# Requirements — Sales Depth: POS, Advanced Pricing, Drop-ship & Blanket/Standing Orders

> Status: **DRAFT (architect-authored, owner-style assumptions made; flagged OQs await ratification).**
> Author: solutions-architect (consuming the system-analyst's Order-to-Cash spec lineage) · Domain: `sales`
> (depth on the shipped O2C spine). Business-level spec, written in the house requirements style
> (FR-SD-NN / BR-SD-NN / NFR-SD-NN, key flows, NFRs, OQs). **No schema, no table/column names, no code** —
> those are the solutions-architect's, in **ADR-0029** (the companion data-model). Do not infer a data model
> from this document.
>
> **This is Sales Depth — Phase B (docs/PATH-TO-FULL-ERP.md area 6 / §3.3).** It completes the four
> remaining "partial"/"remaining" Sales bullets that the shipped O2C slice (ADR-0021) deliberately left out:
> **Point-of-Sale (POS)**, **advanced pricing (tiers / customer-specific / promotions)**, **drop-ship /
> third-party fulfilment**, and **blanket / standing orders**.
>
> **Builds on (does NOT rebuild):**
> - **Sales / O2C** (ADR-0008 / V5 + ADR-0021 / V18-V19): the invoice channel (`DIRECT` origin issues stock +
>   COGS on finalise — the channel POS reuses), `InvoiceTotalsCalculator` (tax-exclusive per-band VAT,
>   line + doc discount), `SALE.FINALISED` / `SALE.VOIDED`, `INV-####`, tender capture (CASH / MOBILE_MONEY),
>   the quotation → sales-order → delivery → return spine, `SalesOrderStatus`, the `DocumentOrigin`
>   discriminator (`DIRECT | SALES_ORDER`), the soft-reservation model (`reserved_qty` on `stock_on_hand`).
> - **Stock + Valuation** (ADR-0010 / V7 + ADR-0020 / V17): `StockPostingService`, the moving-average
>   COGS engine, `StockReservationService`, `DeliveryIssueStockHandler`, recipe explosion.
> - **Products** (ADR-0007 / V3): `price_lists` + `product_prices` (the named selling-price set per company,
>   `RETAIL` / `WHOLESALE`), products, units, single-level recipes — **the pricing foundation this slice
>   extends with rules**.
> - **Parties** (ADR-0006 / V2): `customers` (CASH_WALK_IN / CREDIT_ACCOUNT), price-list assignment on the
>   customer, agents.
> - **Purchases / P2P** (ADR-0011 / V8): purchase orders + goods receipts — **drop-ship raises a linked PO**.
> - **AR / Cash & Bank / GL** (ADR-0014/0016/0013): credit-limit at finalise, the cash leg, revenue posting.
> - The platform spine: `code_sequence` numbering, the transactional outbox + `IdempotencyGuard`, RBAC /
>   `@perm` / `ScopeGuard.assertCanActIn`, `Money` (base currency), audit. All shipped.
>
> **Migration footprint: V42–V45 (additive on the frozen V1–V19).** Depends on = none beyond the shipped
> platform; this slice gates = none.

## 1. Business context & why now

ERPCLEAN2 sells today through two channels — the **direct walk-in invoice** (ADR-0008) and the
**order-to-cash spine** (quote → SO → reserve → deliver → invoice → return, ADR-0021). Pricing is a flat
**price-list price + line/document discount**: a customer is assigned one price list (`RETAIL` or
`WHOLESALE`), each product has one price on it, and a clerk may override the price with
`SALES.INVOICE.OVERRIDE`. That is enough to ring a sale and bill an order. It is **not** enough to run a
real shop floor or a wholesale price book:

- **There is no till.** A walk-in counter sale today is a normal invoice — but a cashier has no **session**
  (open with a float, take cash all day, close and reconcile the drawer against the system), no fast
  one-screen "scan-tender-change-print" flow, no **X/Z read**, and no over/short variance posted to the
  books. A shop running 200 cash sales a day cannot account for its drawer.
- **Pricing is one flat number.** There are no **volume / tier breaks** ("100+ units at a lower unit
  price"), no **customer-specific price** that overrides the list for a contract customer, and no
  **time-boxed promotion** ("10% off category X for the festive week"). Every special price today is a
  manual line override — untraceable, unauditable as a *rule*, and invisible to the next clerk.
- **Every sale fulfils from own stock.** A **drop-ship** order (the supplier ships directly to the
  customer; the business never holds the goods) cannot be modelled — it would wrongly issue own stock it
  never had.
- **Every order is a one-shot.** A **blanket order** (a customer commits to 1,000 units over a year, called
  off in batches against agreed pricing) and a **standing / recurring order** (the same basket every month)
  must today be re-keyed each time.

**Sales Depth closes these four gaps** — all **additive on the shipped O2C spine**, reusing the invoice
channel for revenue/AR/VAT, the valuation engine for COGS, and the price-list foundation for pricing. POS
is a **session wrapper + a fast UI over the existing `DIRECT` invoice channel** (it issues stock + COGS on
finalise exactly as a walk-in invoice does today — *no new costing seam*). Advanced pricing is a **rule
layer that resolves to the same `unit_price` the clerk types today** — the downstream totals math is
unchanged. Drop-ship is an **SO fulfilment variant** that raises a linked PO instead of issuing own stock.
Blanket / standing orders are an **SO type + a call-off / generation mechanism** on the existing SO.

### The four sub-features (read this before anything else)

```
  POS                         ADVANCED PRICING            DROP-SHIP                  BLANKET / STANDING
  (till session)              (rule resolution)           (SO → linked PO)           (SO type + call-off)
  open float → sell →         tier / customer / promo     fulfil via supplier        commit a quantity/value;
  close → reconcile drawer     → resolves the unit price   ship-to-customer; no       call off in batches;
  over/short → GL              the invoice/SO line uses     own-stock issue; COGS      generate child SOs on
  reuses DIRECT invoice        (downstream math unchanged)  at supplier-bill cost      a schedule (standing)
        │                            │                          │                          │
   POS sale = a DIRECT          resolved price feeds         drop-ship delivery =       blanket/standing SO =
   invoice in a session         POS + SO + invoice lines     PO receipt that ships      a parent the child
   (issues stock + COGS)        (one resolver, all paths)    straight on                 SOs draw down
```

### THE KEY INTEGRATION SEAMS (flag for ADR-0029)

Three seams carry the design risk; each is a **decision the ADR makes** (the behaviour is fixed here):

1. **POS reuses the DIRECT invoice channel — it does NOT introduce a new costing seam.** A POS sale **is** a
   `DIRECT`-origin `sales_invoices` finalise: it issues stock + posts COGS on finalise (the shipped
   `SaleIssueStockHandler`, `issuesStock = true`), posts revenue/VAT, and takes a cash/mobile tender — all
   **unchanged**. POS adds (a) a **till session** the invoice is *attached to* (so the drawer reconciles)
   and (b) a fast UI. It must **NOT** double-issue stock, re-post COGS, or fork the costing path
   (**BR-SD-01**, a release blocker). *The mechanism — does the POS sale write a normal invoice tagged with
   a session id, or a distinct POS document? — is the ADR's (OQ-SD-01); the behaviour (POS sale = a DIRECT
   invoice in a session) is fixed.*
2. **The pricing rule layer resolves to ONE number, then stops.** Advanced pricing is a **price-resolution
   step that runs *before* the line is built** and yields the `unit_price` (and any rule-driven discount)
   the line carries. Once resolved, the line flows through the **unchanged** `InvoiceTotalsCalculator`
   (VAT on discounted net, HALF_UP). Pricing rules **never** touch the totals/VAT/COGS math (**BR-SD-06**).
   *The resolution order (tier vs customer-specific vs promotion precedence) is the ADR's (OQ-SD-03).*
3. **Drop-ship issues NO own stock; COGS is the supplier cost, not the moving average.** A drop-ship SO
   line is fulfilled by a **supplier shipping directly to the customer**. The business never holds the
   goods, so the delivery must **NOT** decrement own `stock_on_hand` or post COGS at the moving average.
   Instead the cost of sale is the **supplier's bill cost** for that drop-ship PO, recognised when the
   drop-ship is confirmed/billed (**BR-SD-11**, a finance-grade rule — getting it wrong either issues
   phantom stock or mis-states COGS). *The exact COGS recognition point + account flow is the ADR's
   (OQ-SD-09).*

## 2. Scope

> This is **Sales Depth (T2.1 remainder)**: POS, advanced pricing, drop-ship, blanket/standing orders — a
> depth slice on the shipped O2C spine that **reuses** the invoice channel, the valuation/COGS engine, the
> price-list foundation, and the purchase channel. It does **not** rebuild Sales, Stock, AR, GL, or
> Purchases. **The build is staged** — POS + advanced pricing first (the high-value, self-contained pair),
> drop-ship + blanket/standing second (they lean on Purchases / scheduling) — §10.

### In scope (v1)

**A. Point-of-Sale (POS)**

- **Till / register master.** A named **till** (POS register) per branch, bound to a **cash/bank account**
  (the drawer's money location, ADR-0016) so a session's takings post to a real GL cash account
  (FR-SD-01).
- **Cashier session lifecycle — OPEN → (sell) → CLOSED → RECONCILED.** A cashier **opens** a session on a
  till declaring an **opening float** (cash put in the drawer); rings sales during the session; **closes**
  it declaring the **counted cash** (and counted mobile-money where applicable); the system computes the
  **expected** drawer (`opening float + Σ cash tenders − Σ cash refunds/payouts`) and the **over/short
  variance** (counted − expected); a supervisor **reconciles** the variance, which **posts to GL**
  (over = misc income, short = cash-shortage expense) (FR-SD-02/03/04, BR-SD-02/03/04).
- **POS sale = a fast DIRECT invoice in the session.** Ringing a sale on an open session creates a
  finalised **`DIRECT`-origin invoice** (the shipped channel: issues stock + COGS on finalise, posts
  revenue/VAT, takes a tender, computes change), **attached to the session** so its cash tender rolls into
  the drawer reconciliation. One-screen scan/keypad → tender → change → receipt (FR-SD-05, **BR-SD-01**).
- **POS refund (against a same-session or prior POS sale) reuses the shipped void / return.** A cash refund
  at the till reverses a POS sale via the shipped **invoice void** (full) or the **sales return / RMA**
  (partial, ADR-0021 Stage 2) — stock back in, COGS reversed, a **cash payout** recorded against the
  session drawer (FR-SD-06, BR-SD-05). *(The refund-tender depth beyond cash — card reversal etc. — stays
  deferred per ADR-0021 §2; cash payout is the v1 outcome.)*
- **X read (mid-session) and Z read (at close).** A non-resetting **X read** (current session totals: sales
  count, gross, tender mix) any time; a **Z read** at close (the session's final totals, the
  expected-vs-counted line, the variance) — the audit document of the session (FR-SD-07).
- **POS permissions / numbering / scope.** `SALES.POS.SESSION.OPEN / CLOSE / RECONCILE`, `SALES.POS.SELL`,
  `SALES.POS.REFUND`, `SALES.POS.TILL.MANAGE`, `SALES.POS.VIEW`; `code_sequence` kind **POS_SESSION**
  (`POS-####`); per-company + per-branch scope; `assertCanActIn` on every read; audit every transition
  (FR-SD-08).

**B. Advanced pricing**

- **Price tiers (volume breaks) on a price list.** A product's price on a price list may carry **quantity
  break tiers** ("1–99 @ X, 100–499 @ Y, 500+ @ Z"); the resolved unit price is the tier matching the line
  quantity (FR-SD-09, BR-SD-07).
- **Customer-specific prices.** A **customer-specific price** for a (customer, product) overrides the
  customer's assigned price-list price (incl. tiers) — the contract price a particular customer gets
  (FR-SD-10, BR-SD-08).
- **Promotion / discount rules (time-boxed).** A **promotion** with an effective date range and a target
  scope (a product, a product category, or all) applying a **% or fixed-amount discount** (or a promotional
  override price), active only within its window (FR-SD-11, BR-SD-09). v1 promotions are **simple
  single-condition** rules (no stacking logic beyond a defined precedence, no buy-X-get-Y / bundles —
  deferred §2).
- **One price-resolution service, deterministic precedence.** A single **price resolver** computes, for a
  (company, customer, product, quantity, date), the **effective unit price + any rule-driven discount**,
  applying a **deterministic precedence** (recommended: customer-specific > active promotion > tier > base
  list price — the ADR fixes it, OQ-SD-03). It returns the resolved figure **and the rule that produced
  it** (for the line's price-source audit). The resolved price feeds **POS, SO, quotation, and invoice**
  lines identically (FR-SD-12, BR-SD-06/10).
- **Manual override still wins, audited.** A user with `SALES.INVOICE.OVERRIDE` may still type a price over
  the resolved one (the shipped override discipline) — the override is flagged and audited as today
  (FR-SD-13).
- **Pricing permissions / scope.** `SALES.PRICING.RULE.VIEW / MANAGE` (tiers, customer-prices, promotions
  are pricing rules); per-company scope; audit on rule create/edit/deactivate (FR-SD-14).

**C. Drop-ship / third-party fulfilment**

- **Drop-ship SO line.** An SO line may be flagged **drop-ship**: it is fulfilled by a **supplier shipping
  directly to the customer**, not from own stock. A drop-ship line **reserves no own stock** (there is none
  to reserve) (FR-SD-15, BR-SD-11).
- **Confirming a drop-ship SO raises a linked supplier PO.** Confirming an SO with drop-ship lines creates
  (or links) a **purchase order to the chosen supplier** for the drop-ship quantities, marked
  **ship-to-customer** (the customer's delivery address, not the warehouse) — through the shipped
  Purchases channel (FR-SD-16, BR-SD-12).
- **Drop-ship fulfilment = the supplier ships; COGS at supplier cost.** When the drop-ship PO is received /
  the supplier confirms shipment, the SO line is marked **fulfilled** (a delivery record that issues **no
  own stock** and posts **no moving-average COGS**); the **cost of sale is the supplier bill cost** of the
  drop-ship PO, posted DR COGS / CR (the goods-clearing leg) (FR-SD-17, **BR-SD-11/13**). The customer is
  then **invoiced revenue-only** through the shipped channel (FR-SD-18). *The exact COGS recognition point
  + account is the ADR's (OQ-SD-09).*
- **Drop-ship permissions.** `SALES.DROPSHIP.VIEW / CREATE` (the drop-ship flag + the linked-PO action);
  the linked PO rides the existing `PURCHASE.ORDER.*` perms (FR-SD-19).

**D. Blanket / standing orders**

- **Blanket order (a framework commitment with call-offs).** A **blanket SO** records a customer's
  committed **total quantity (or value)** per product over a validity window at **agreed pricing**, with
  **no immediate reservation or fulfilment**. **Call-off (release) orders** draw down against it: each
  call-off is a normal SO (reserves, delivers, invoices) that **decrements the blanket's remaining
  balance**; the blanket cannot be over-drawn (FR-SD-20/21, BR-SD-14/15).
- **Standing / recurring order (a schedule that generates SOs).** A **standing order** records a recurring
  basket + a **schedule** (e.g. monthly); a **generation run** (scheduled or manual) creates the next
  child SO from the template, at the **then-current resolved pricing** (or the locked standing pricing —
  the ADR fixes it, OQ-SD-10) (FR-SD-22/23, BR-SD-16). v1 generation is **manual or a simple scheduled
  sweep**; full proration/renewal billing stays deferred (§2).
- **Blanket / standing permissions / numbering.** `SALES.BLANKET.VIEW / CREATE / CLOSE`,
  `SALES.STANDING.VIEW / CREATE / GENERATE`; `code_sequence` kinds **BLANKET_ORDER** (`BLK-####`) and
  **STANDING_ORDER** (`STD-####`); child call-off / generated SOs use the existing `SO-####`; per-company
  scope; audit (FR-SD-24).

**Migration footprint (V42–V45, additive).** The POS till + session + session-line documents; the pricing
tier / customer-price / promotion-rule tables; the drop-ship flag on the SO line + the SO↔PO link; the
blanket / standing order tables + the blanket↔call-off draw-down link; the new permissions; the new
`code_sequence` kinds; two new GL config keys (the POS over/short accounts) + a drop-ship goods-clearing
account/key (the ADR decides reuse-vs-new, OQ-SD-09). **V1–V19 frozen.**

### Deferred (recognised, NOT built in v1)

- **Offline POS / buffered sync.** v1 POS is **online** (the till talks to the server per sale). A service
  worker + offline sale buffering + conflict-resolved sync is deferred (PATH-TO-FULL-ERP §3.13 offline).
- **POS hardware integration.** Cash-drawer kick, receipt-printer ESC/POS, barcode-scanner HID, weighing
  scales, customer-display — v1 assumes a browser + keyboard/scanner-as-keyboard; native device drivers
  deferred.
- **Card / EFTPOS / gateway tenders + their reversal.** v1 POS tenders are **cash + mobile-money** (the
  shipped `TenderType`); card / payment-gateway / loyalty / gift-card tenders and their integrated reversal
  are deferred (PATH-TO-FULL-ERP §3.3 payment methods depth).
- **Bundle / kit / buy-X-get-Y / mix-and-match promotions.** v1 promotions are single-condition
  (%/amount/override on a product/category/all, time-boxed). Multi-line conditional promotions, coupon
  codes, and loyalty-points pricing are deferred (PATH-TO-FULL-ERP §3.3 pricing depth).
- **Promotion stacking beyond the fixed precedence.** v1 applies the single best/highest-precedence rule
  (OQ-SD-03); configurable stacking / "best of N" / additive stacking is deferred.
- **Drop-ship partial / multi-supplier split + ASN.** v1 drop-ships an SO line to **one** supplier in
  one PO; splitting a line across suppliers, partial drop-ship receipts driving partial customer invoices,
  and advance-shipment notices are deferred (ties to Purchases ASN depth, §3.4).
- **Standing-order proration / renewal billing / subscription dunning.** v1 generates the next child SO on
  schedule; pro-rated periods, auto-renewal, subscription-style recurring billing with dunning are deferred
  (PATH-TO-FULL-ERP §3.3 recurring/subscription billing).
- **Multi-warehouse / location-aware POS + drop-ship.** v1 is single-location (the till's branch on-hand);
  multi-warehouse allocation stays deferred (ties to Stock multi-location, ADR-0021 §2).
- **Multi-currency.** v1 is base currency (TZS) across POS / pricing / drop-ship / blanket (ADR-0005).

### Explicitly NOT this module

- **The invoice channel's revenue/AR/VAT posting + the totals math** — Sales (ADR-0008) owns the invoice,
  `InvoiceTotalsCalculator`, finalise, `INV-####`, the revenue posting. POS rings sales **through** that
  channel (a `DIRECT` invoice); advanced pricing resolves the **unit price** the channel then uses — neither
  reimplements the invoice or its totals.
- **The stock quantity + valuation/COGS engine** — Stock (ADR-0010/0020) owns `stock_movements`,
  `stock_on_hand`, the moving-average cost, the COGS posting, recipe explosion. POS sales issue stock via
  the shipped finalise path; drop-ship deliberately **bypasses** own-stock issue (the cost is the supplier
  bill).
- **The Purchases channel** — Purchases (ADR-0011) owns POs and goods receipts. Drop-ship **raises a linked
  PO** through that channel (ship-to-customer); it does not reimplement purchasing.
- **The GL / AR / Cash & Bank posting** — GL/AR/Cash (ADR-0013/0014/0016) own the books and the cash leg.
  POS reconciliation, drop-ship COGS, and call-off invoices **post through** their shipped paths.
- **POS hardware, offline sync, card tenders, bundle promotions, drop-ship split, subscription billing** —
  all deferred (§2).

## 3. The model: till sessions, the pricing resolver, drop-ship, and blanket/standing orders

### 3.1 POS — till, session, sale, reconciliation

A **till** (POS register) is a named device-or-lane in a branch bound to a **cash/bank account** (the
drawer). A **cashier session** is the unit of accountability:

- **OPEN** — a cashier opens a session on a till, declaring the **opening float** (cash placed in the
  drawer). Exactly **one open session per till** at a time (a till cannot be double-opened).
- **(selling)** — the cashier rings POS sales (each a finalised `DIRECT` invoice attached to the session —
  §3.2) and POS refunds (a void / return + a cash payout). Cash tenders and cash payouts accumulate against
  the session drawer.
- **CLOSED** — the cashier closes the session, declaring the **counted cash** (physically counted in the
  drawer). The system computes:
  - **expected drawer** = opening float + Σ cash tenders − Σ cash refunds/payouts;
  - **variance** = counted − expected (positive = **over**, negative = **short**).
- **RECONCILED** — a supervisor reviews and reconciles the variance; the variance **posts to GL** (over →
  misc income; short → cash-shortage expense), and the session's net cash is recognised against the till's
  cash/bank account (the existing Cash & Bank flow). A reconciled session is **immutable** (corrections are
  a fresh adjustment, not an edit — BR-SD-04).

The **Z read** is the session's closing document (totals + the expected/counted/variance line); an **X
read** is the same totals mid-session without closing. A reservation/posting note: a POS sale **issues
stock + COGS on finalise** exactly as a walk-in invoice does — POS does **not** add a costing step
(**BR-SD-01**).

### 3.2 POS sale = a DIRECT invoice in a session (the no-new-seam rule)

Ringing a POS sale creates a **finalised `DIRECT`-origin `sales_invoices`** through the shipped channel:
the line prices come from the **pricing resolver** (§3.3), `InvoiceTotalsCalculator` computes net/VAT/gross,
finalise allocates `INV-####`, emits `SALE.FINALISED` with **`issuesStock = true`** (the walk-in default —
the delivery seam of ADR-0021 does **not** apply, there is no delivery), so the `SaleIssueStockHandler`
**issues stock + posts COGS at the moving average** and the revenue posting fires — **all unchanged**. The
only POS-specific facts are: the invoice is **attached to a session** (so the cash tender rolls into the
drawer), and the customer defaults to a **POS walk-in customer** (CASH_WALK_IN) unless a credit customer is
chosen. **The POS sale must never issue stock twice or fork COGS** (BR-SD-01). *Whether the link is a
column on the invoice or a join row, and whether a POS sale is a distinct document or a tagged invoice, is
the ADR's (OQ-SD-01).*

### 3.3 Advanced pricing — one resolver, deterministic precedence

Pricing is resolved **before a line is built**, by a single **price resolver** taking (company, customer,
product, quantity, business date) and returning the **effective unit price**, **any rule-driven discount**,
and the **price source** (which rule won). The resolution applies a **deterministic precedence**
(recommended, OQ-SD-03):

1. **Customer-specific price** for (customer, product) — the contract price, if one exists and is active;
2. else **active promotion** matching the product/category and the business date — applies its
   %/amount/override;
3. else **price tier** on the customer's assigned price list matching the quantity break;
4. else the **base list price** (the shipped `product_prices` figure — today's behaviour).

The resolved figure becomes the line's `unit_price` (+ a rule discount on the line where applicable), then
flows through the **unchanged** `InvoiceTotalsCalculator` (VAT on discounted net, pro-rata order discount,
HALF_UP). **Pricing rules never touch the totals/VAT/COGS math** (**BR-SD-06**). A manual override
(`SALES.INVOICE.OVERRIDE`) still trumps the resolved price and is flagged/audited (FR-SD-13). The same
resolver feeds **POS sales, quotation lines, SO lines, and direct-invoice lines** — one resolution rule,
every path (so POS and the back-office agree to the cent — BR-SD-10).

### 3.4 Drop-ship — fulfil via supplier, no own stock, COGS at supplier cost

A drop-ship SO line is fulfilled by a **supplier shipping directly to the customer**. The flow:

- the SO line is flagged **drop-ship** with a **chosen supplier**; it **reserves no own stock** (BR-SD-11);
- on **confirm**, the system raises (or links) a **supplier PO** for the drop-ship quantity, marked
  **ship-to-customer** (the customer's address) — through the shipped Purchases channel (BR-SD-12);
- when the drop-ship is **fulfilled** (the PO is received / the supplier confirms shipment), a **delivery
  record** marks the SO line fulfilled **without issuing own stock or posting moving-average COGS**; the
  **cost of sale is the supplier bill cost** of that drop-ship PO, recognised DR COGS / CR (a goods-clearing
  leg) (BR-SD-13);
- the customer is **invoiced revenue-only** for the drop-shipped quantity through the shipped channel
  (BR-SD-11 — never re-issues own stock).

This keeps inventory honest (goods never entered own stock, so none leaves) and COGS accurate (the actual
supplier cost, not a phantom moving average). *The COGS recognition point (at PO receipt vs at supplier-bill
match) + the clearing account flow is the ADR's (OQ-SD-09).*

### 3.5 Blanket / standing orders

A **blanket order** (`BLK-####`) records a customer's committed **total quantity (or value)** per product
over a validity window at **agreed pricing**, reserving and posting **nothing** itself. **Call-off** orders
(normal SOs) draw against it: each call-off **decrements the blanket's remaining balance** and reserves /
delivers / invoices normally; the blanket **cannot be over-drawn** (the cumulative called-off quantity ≤
the committed quantity — BR-SD-14/15). A blanket is **CLOSED** when fully drawn or its window expires.

A **standing / recurring order** (`STD-####`) records a recurring **basket + schedule** (e.g. monthly). A
**generation run** (manual or a scheduled sweep) creates the next **child SO** from the template at the
then-current **resolved pricing** (or the locked standing pricing — OQ-SD-10). Each generated SO is a
normal SO (reserves / delivers / invoices). v1 generation is **simple** (no proration, no auto-renewal
dunning — deferred §2).

## 4. Actors / personas

- **Cashier / POS operator** — opens a session with a float (`SALES.POS.SESSION.OPEN`), rings sales
  (`SALES.POS.SELL`) and cash refunds (`SALES.POS.REFUND`), takes an X read, and closes the session
  declaring counted cash (`SALES.POS.SESSION.CLOSE`). The front line of counter sales.
- **POS supervisor / shift manager** — reconciles the closed session's over/short variance
  (`SALES.POS.SESSION.RECONCILE`, posting it to GL), manages tills (`SALES.POS.TILL.MANAGE`), and reads
  session history / Z reads (`SALES.POS.VIEW`). The authority over the drawer.
- **Pricing manager** — defines and maintains **price tiers, customer-specific prices, and promotions**
  (`SALES.PRICING.RULE.MANAGE`); the owner of the price book. (Reads: `SALES.PRICING.RULE.VIEW`.)
- **Sales officer / order clerk** — flags **drop-ship** lines and links the supplier PO
  (`SALES.DROPSHIP.CREATE`), creates **blanket** (`SALES.BLANKET.CREATE`) and **standing**
  (`SALES.STANDING.CREATE`) orders, and triggers a standing-order **generation**
  (`SALES.STANDING.GENERATE`). The front line of contract / framework selling.
- **Sales manager** — **closes** blankets (`SALES.BLANKET.CLOSE`), oversees the call-off draw-down, and
  reads the order book. The authority over the framework commitments.
- **Accountant / AR officer** — invoices call-off and drop-ship deliveries through the shipped channel,
  reconciles that POS session takings, drop-ship COGS, and call-off revenue tie out. The owner of the
  financial consequences.
- *(No new human actor on the COGS / revenue / variance **postings** themselves — POS reconciliation,
  drop-ship COGS, and call-off revenue are **system-posted in-request / via the outbox** on the existing
  engine paths, under the acting company/branch scope, like the shipped auto-posters.)*

## 5. Functional requirements

> IDs are `FR-SD-NN`. Each is a crisp, testable statement. "POS sale" = a finalised `DIRECT` invoice in a
> session (issues stock + COGS on finalise, per BR-SD-01); "resolved price" = the price-resolver output
> (§3.3); "drop-ship" = supplier-ships-to-customer, no own stock (§3.4); "call-off" = a child SO drawing
> against a blanket (§3.5).

### POS — till & session

- **FR-SD-01** A user with `SALES.POS.TILL.MANAGE` may create/edit a **till** (POS register) in a branch,
  bound to an existing **cash/bank account** (the drawer's GL cash account, ADR-0016). A till belongs to one
  company + branch (BR-SD-14 tenant scope).
- **FR-SD-02** A user with `SALES.POS.SESSION.OPEN` may **open a session** on a till, declaring an **opening
  float**. At most **one OPEN session per till** at a time; opening allocates `POS-####`. The session is
  **OPEN** (BR-SD-02).
- **FR-SD-03** A user with `SALES.POS.SESSION.CLOSE` may **close** an OPEN session, declaring the **counted
  cash** (and counted mobile-money where used). The system computes the **expected drawer** (opening float +
  Σ cash tenders − Σ cash refunds/payouts) and the **variance** (counted − expected); the session becomes
  **CLOSED** (BR-SD-03).
- **FR-SD-04** A user with `SALES.POS.SESSION.RECONCILE` may **reconcile** a CLOSED session: the over/short
  **variance posts to GL** (over → misc income; short → cash-shortage expense) and the session's net cash is
  recognised against the till's cash/bank account (the shipped Cash & Bank flow); the session becomes
  **RECONCILED** (terminal, immutable — BR-SD-04).

### POS — sale, refund, reads

- **FR-SD-05** A user with `SALES.POS.SELL` on an OPEN session may **ring a sale**: select products
  (the resolved price from §3.3 applies), take a **cash / mobile-money tender**, compute **change**, and
  **finalise** — creating a finalised **`DIRECT`-origin invoice** (`INV-####`) **attached to the session**.
  The finalise **issues stock + posts COGS on finalise** (the shipped path, `issuesStock = true`) and posts
  revenue/VAT — **it MUST NOT double-issue stock or fork COGS** (BR-SD-01, the hard rule).
- **FR-SD-06** A user with `SALES.POS.REFUND` may **refund** a POS sale: a **full refund** via the shipped
  invoice **void**, or a **partial refund** via the shipped **sales return / RMA** (stock back in, COGS
  reversed at original cost), with a **cash payout** recorded against the session drawer (BR-SD-05).
- **FR-SD-07** A user with `SALES.POS.VIEW` may take an **X read** (current session totals: sale count,
  gross, tender mix — non-resetting) any time, and the session close produces a **Z read** (final totals +
  expected/counted/variance). Both are audited reads (FR-SD-08).
- **FR-SD-08** POS is **scoped per company + branch**; every till, session, session sale, and read belongs
  to exactly one company/branch; `assertCanActIn` guards **every read path**; **audit** records every
  transition (session open/close/reconcile; sale rung; refund) with actor, action, target, and company
  context (BR-SD-17, NFR-SD-01/04).

### Advanced pricing

- **FR-SD-09** A user with `SALES.PRICING.RULE.MANAGE` may define **quantity-break price tiers** on a
  product's price-list price (`min_qty → unit price`); the resolver returns the tier matching the line
  quantity (BR-SD-07). A product with no tiers resolves to the flat list price (today's behaviour —
  back-compatible).
- **FR-SD-10** A user with `SALES.PRICING.RULE.MANAGE` may define a **customer-specific price** for a
  (customer, product); it **overrides** the customer's price-list price (incl. tiers) when resolving for
  that customer (BR-SD-08).
- **FR-SD-11** A user with `SALES.PRICING.RULE.MANAGE` may define a **promotion** with an **effective date
  range** and a **target** (a product / a product category / all) applying a **% or fixed-amount discount or
  an override price**; it is active **only within its window** (BR-SD-09).
- **FR-SD-12** A single **price resolver** computes the **effective unit price + rule discount + price
  source** for (company, customer, product, quantity, date) by a **deterministic precedence**
  (customer-specific > active promotion > tier > base list — OQ-SD-03). The resolved figure feeds **POS, SO,
  quotation, and invoice** lines identically (BR-SD-06/10).
- **FR-SD-13** A manual price override (`SALES.INVOICE.OVERRIDE`) **trumps** the resolved price (the shipped
  override discipline); the override is flagged and audited on the line as today (BR-SD-10).
- **FR-SD-14** Pricing rules are **scoped per company**; `assertCanActIn` guards every read; **audit**
  records rule create/edit/deactivate; the resolver's chosen source is recorded on the line for traceability
  (FR-SD-12, NFR-SD-04).

### Drop-ship

- **FR-SD-15** A user with `SALES.DROPSHIP.CREATE` may flag an SO line **drop-ship** with a **chosen
  supplier**; a drop-ship line **reserves no own stock** (BR-SD-11).
- **FR-SD-16** Confirming an SO with drop-ship lines raises (or links) a **supplier PO** for the drop-ship
  quantities, marked **ship-to-customer** (the customer's address), through the shipped Purchases channel
  (BR-SD-12). The PO rides the existing `PURCHASE.ORDER.*` perms.
- **FR-SD-17** When the drop-ship PO is **received / the supplier confirms shipment**, the SO line is marked
  **fulfilled** via a **delivery record that issues no own stock and posts no moving-average COGS**; the
  **cost of sale** is recognised at the **supplier bill cost** (DR COGS / CR goods-clearing) (BR-SD-11/13).
- **FR-SD-18** The drop-shipped quantity is **invoiced revenue-only** through the shipped channel (DR
  AR/Cash, CR Sales Revenue, CR VAT); it **never re-issues own stock** (BR-SD-11).
- **FR-SD-19** Drop-ship is **scoped per company**; `assertCanActIn` on every read; **audit** the drop-ship
  flag, the linked PO, and the fulfilment.

### Blanket / standing orders

- **FR-SD-20** A user with `SALES.BLANKET.CREATE` may create a **blanket order** (`BLK-####`): per-product
  committed **total quantity (or value)**, a validity window, and **agreed pricing**; it **reserves and
  posts nothing** itself (BR-SD-14).
- **FR-SD-21** A **call-off** SO draws against a blanket: it **decrements the blanket's remaining balance**
  and reserves/delivers/invoices normally; a call-off **cannot over-draw** the blanket (cumulative ≤
  committed — BR-SD-15). A user with `SALES.BLANKET.CLOSE` may **close** a blanket; it also closes when fully
  drawn or expired.
- **FR-SD-22** A user with `SALES.STANDING.CREATE` may create a **standing / recurring order** (`STD-####`):
  a recurring **basket + schedule** (e.g. monthly) at the standing pricing (BR-SD-16).
- **FR-SD-23** A user with `SALES.STANDING.GENERATE` (or a scheduled sweep) may **generate** the next
  **child SO** from a standing order's template at the **then-current resolved pricing** (or locked standing
  pricing — OQ-SD-10); each generated SO is a normal SO (BR-SD-16).
- **FR-SD-24** Blanket / standing orders are **scoped per company**; numbering uses `code_sequence` kinds
  **BLANKET_ORDER** (`BLK-####`) and **STANDING_ORDER** (`STD-####`); `assertCanActIn` on every read;
  **audit** every transition (blanket create/close, call-off draw, standing create, generation run).

## 6. Business rules (invariants)

> A violation that double-issues stock / double-counts COGS (POS or drop-ship), mis-states the drawer, lets
> a pricing rule corrupt the totals math, or over-draws a blanket is a finance-grade / inventory-grade defect
> (a release blocker).

- **BR-SD-01 — A POS sale is a DIRECT invoice in a session; it issues stock + COGS ONCE, on finalise.** A POS
  sale reuses the shipped `DIRECT` invoice channel (`issuesStock = true`); it issues stock and posts COGS at
  the moving average **on finalise** exactly as a walk-in invoice, **once**. POS adds a session attachment +
  a fast UI, **not** a new costing seam. Double-issuing / forking COGS is a **release blocker** (FR-SD-05).
- **BR-SD-02 — One OPEN session per till; opening declares a float.** A till has **at most one** OPEN session
  at a time; opening records the opening float and allocates `POS-####`. The float is the drawer's starting
  cash (FR-SD-02).
- **BR-SD-03 — The expected drawer is deterministic; the variance is counted − expected.** expected =
  opening float + Σ cash tenders − Σ cash refunds/payouts; variance = counted − expected (over if +, short if
  −). The computation is from the session's own sale/refund cash legs, never free-typed (FR-SD-03).
- **BR-SD-04 — Reconciliation posts the variance to GL; a reconciled session is immutable.** Reconciling
  posts over → misc income, short → cash-shortage expense (the new POS GL config keys), recognises the
  session net cash against the till's cash/bank account, and freezes the session; corrections are a fresh
  adjustment, not an edit (append-only, FR-SD-04, NFR-SD-04).
- **BR-SD-05 — A POS refund reverses through the shipped void / return + a cash payout.** A full refund =
  invoice void (stock back, COGS reversed); a partial refund = sales return / RMA (ADR-0021, stock back at
  original cost, COGS reversed); a cash payout is recorded against the session drawer. No new reversal engine
  (FR-SD-06).
- **BR-SD-06 — Pricing rules resolve a price; they never touch the totals/VAT/COGS math.** The resolver
  yields the line's `unit_price` (+ rule discount) **before** the line is built; from there the **unchanged**
  `InvoiceTotalsCalculator` computes net/VAT/gross (HALF_UP, VAT on discounted net), and COGS is the
  unchanged moving average. A pricing rule that altered the totals algorithm is a defect (FR-SD-12).
- **BR-SD-07 — A tier resolves by the line quantity, within the price list.** A product's price-list tiers
  partition the quantity axis (non-overlapping `min_qty` breaks); the resolver picks the tier whose break
  the line quantity falls in. No tiers ⇒ the flat list price (FR-SD-09).
- **BR-SD-08 — A customer-specific price overrides the list (incl. tiers) for that customer.** When a
  (customer, product) customer-price exists and is active, it is the resolved price for that customer,
  ahead of promotions/tiers/list (the precedence head, OQ-SD-03) (FR-SD-10).
- **BR-SD-09 — A promotion applies only within its effective window, to its target scope.** A promotion is
  inactive outside `[effective_from, effective_to]`; it applies to its target (product / category / all) and
  yields a %/amount discount or override price (FR-SD-11).
- **BR-SD-10 — One resolver, all channels, deterministic precedence; manual override wins.** The same
  resolver (precedence customer-specific > promotion > tier > list, OQ-SD-03) serves POS, SO, quotation, and
  invoice — so all channels agree; a manual `SALES.INVOICE.OVERRIDE` trumps the resolved price, flagged and
  audited (FR-SD-12/13).
- **BR-SD-11 — A drop-ship line issues NO own stock; COGS is the supplier cost, never the moving average.** A
  drop-ship SO line reserves no own stock and, on fulfilment, issues none and posts **no moving-average
  COGS**; its cost of sale is the **supplier bill cost** of the drop-ship PO. The customer invoice is
  revenue-only. Issuing phantom own stock or mis-costing at the moving average is a **release blocker**
  (FR-SD-15/17/18).
- **BR-SD-12 — Confirming a drop-ship SO raises a ship-to-customer supplier PO.** The drop-ship quantities
  become a supplier PO marked ship-to-customer (the customer's address), through the shipped Purchases
  channel; the SO line ↔ PO line link is recorded for traceability (FR-SD-16).
- **BR-SD-13 — Drop-ship COGS recognises at the supplier cost, posted DR COGS / CR a goods-clearing leg.** On
  drop-ship fulfilment the cost of sale (supplier bill cost) posts DR `5100 COGS` / CR a goods-clearing
  account; the supplier-bill side clears that account on AP match (the exact point + account is OQ-SD-09).
  Σ debits == Σ credits.
- **BR-SD-14 — A blanket order commits a quantity/value; it reserves and posts nothing itself.** A blanket
  records a per-product committed total + validity + agreed pricing; it is a framework, not a posting — it
  reserves no stock and posts no GL on its own (FR-SD-20).
- **BR-SD-15 — Call-offs draw down the blanket and cannot over-draw it.** Each call-off SO decrements the
  blanket's remaining balance; the cumulative called-off quantity **cannot exceed** the committed quantity; a
  fully drawn or expired blanket is CLOSED (FR-SD-21).
- **BR-SD-16 — A standing order generates child SOs on a schedule at resolved (or locked) pricing.** A
  generation run creates the next child SO from the standing template at the then-current resolved pricing
  (or the locked standing pricing, OQ-SD-10); each child is a normal SO (FR-SD-22/23).
- **BR-SD-17 — Per-company isolation; POS adds per-branch isolation.** Every till, session, sale, pricing
  rule, drop-ship link, blanket, and standing order belongs to exactly one company (POS tills/sessions also
  to one branch); no read or figure crosses scope. Cross-company leakage is a **release blocker** (NFR-SD-01).
- **BR-SD-18 — Idempotent POS / drop-ship effects.** The POS sale's stock-issue + COGS (via the shipped
  finalise path) and the drop-ship fulfilment's COGS ride the shipped outbox / `IdempotencyGuard` discipline;
  a retried event posts **once** (NFR-SD-02).
- **BR-SD-19 — Base-currency Sales Depth (v1).** POS, pricing, drop-ship, and blanket/standing are in the
  company base currency (ADR-0005); FX is deferred (§2).
- **BR-SD-20 — Append-only transitions; reversals reverse, never edit.** Every session/pricing/drop-ship/
  blanket transition is audited and append-only; the POS variance posting, the drop-ship COGS, and their
  reversals are reversing entries, never edits (gl.md BR-GL-02).

## 7. Process flows (happy path + main unhappy paths)

### 7.1 POS — open → sell → close → reconcile (happy path)

1. A cashier **opens a session** on a till (`SALES.POS.SESSION.OPEN`), declaring an opening float of (say)
   100,000. `POS-####` allocated; session **OPEN**; audited (FR-SD-02).
2. The cashier **rings sales** (`SALES.POS.SELL`): each is a finalised `DIRECT` invoice (resolved prices,
   tender, change) that **issues stock + posts COGS on finalise** and posts revenue/VAT — attached to the
   session (FR-SD-05, BR-SD-01). Cash tenders accumulate against the drawer.
3. Mid-shift the supervisor takes an **X read** — current totals, no reset (FR-SD-07).
4. The cashier **closes** the session (`SALES.POS.SESSION.CLOSE`), counting the drawer. The system computes
   expected = float + Σ cash tenders − payouts; variance = counted − expected. Session **CLOSED**; a **Z
   read** is produced (FR-SD-03, BR-SD-03).
5. A supervisor **reconciles** (`SALES.POS.SESSION.RECONCILE`): the variance posts to GL (over → misc income
   / short → cash-shortage), the net cash recognises against the till's cash/bank account; session
   **RECONCILED** (immutable); audited (FR-SD-04, BR-SD-04).

### 7.2 Advanced pricing — resolve a line (happy path)

1. A clerk (or the POS) adds a product + quantity to a line for a given customer.
2. The **price resolver** runs: it finds a **customer-specific price** (if any) → else an **active
   promotion** for the date/target → else the **quantity tier** on the customer's price list → else the
   **base list price**; it returns the unit price + any rule discount + the **price source** (FR-SD-12,
   BR-SD-10).
3. The line carries the resolved price; `InvoiceTotalsCalculator` computes net/VAT/gross **unchanged**
   (BR-SD-06). The same resolution would yield the same number on POS, SO, quote, and invoice.

### 7.3 Drop-ship — flag → confirm raises PO → fulfil → invoice (happy path)

1. A clerk flags an SO line **drop-ship** with a supplier (`SALES.DROPSHIP.CREATE`); it reserves no own
   stock (FR-SD-15).
2. On **confirm**, a **supplier PO** is raised, ship-to-customer (FR-SD-16). The PO rides the Purchases
   channel.
3. The supplier ships / the PO is received → the SO line is **fulfilled** via a delivery that **issues no
   own stock and posts no moving-average COGS**; the **supplier cost** posts DR COGS / CR goods-clearing
   (FR-SD-17, BR-SD-11/13).
4. The customer is **invoiced revenue-only** for the drop-shipped quantity (FR-SD-18).

### 7.4 Blanket → call-off (happy path)

1. A clerk creates a **blanket order** (`SALES.BLANKET.CREATE`): 1,000 units committed at agreed pricing
   over a year (`BLK-####`); reserves/posts nothing (FR-SD-20).
2. A **call-off SO** for 200 units draws against the blanket (remaining → 800), reserves, delivers, invoices
   normally (FR-SD-21, BR-SD-15).
3. Subsequent call-offs draw down; the blanket **CLOSES** when fully drawn or expired (FR-SD-21).

### 7.5 Standing order → generate (happy path)

1. A clerk creates a **standing order** (`SALES.STANDING.CREATE`): the monthly basket (`STD-####`)
   (FR-SD-22).
2. A **generation run** (manual or scheduled) creates the next **child SO** at the resolved (or locked)
   pricing; the child reserves/delivers/invoices as a normal SO (FR-SD-23, BR-SD-16).

### 7.6 Main unhappy paths

- **Open a second session on a till already OPEN** → **rejected** (one OPEN session per till — BR-SD-02).
- **Close a session with a large variance** → **allowed but flagged**; the variance posts on reconcile
  (over/short), and a large variance is surfaced for supervisor review (BR-SD-03/04). It is not silently
  zeroed.
- **Reconcile / sell on a session not in the right state** (sell on a CLOSED session, reconcile an OPEN
  one) → **rejected** (state-guarded transitions, BR-SD-04).
- **A POS sale tries to issue stock twice** (a defect) → **must not happen** (BR-SD-01); the POS sale is a
  single DIRECT finalise — the seam (OQ-SD-01) guarantees one issue path.
- **Two promotions match the same product/date** → the **precedence** resolves to one (no stacking in v1 —
  OQ-SD-03); the resolver is deterministic (BR-SD-10).
- **A tier gap / overlap is configured** → **rejected at rule save** (tiers must partition the quantity axis
  — BR-SD-07); the resolver never faces an ambiguous tier.
- **A drop-ship delivery tries to issue own stock** (a defect) → **must not happen** (BR-SD-11, a release
  blocker); a drop-ship line has no own-stock issue path.
- **A drop-ship PO is not yet received but the customer is invoiced** → governed by the COGS-recognition
  policy (OQ-SD-09; recommended: do not invoice before fulfilment, so revenue and COGS land in the same
  period).
- **A call-off over-draws the blanket** → **rejected** (cumulative ≤ committed — BR-SD-15).
- **A standing generation run runs twice for the same period** → the **idempotency guard / period key**
  short-circuits; one child SO per period (BR-SD-18, NFR-SD-02).
- **A pricing rule resolves on a product with no list price** → resolves to no-price / blocks the line (the
  shipped "no price" behaviour); the resolver never invents a price.

## 8. Non-functional

- **NFR-SD-01 — Tenant isolation.** Every till, session, sale, pricing rule, drop-ship link, blanket, and
  standing order is scoped by `company_id` (POS tills/sessions also by `branch_id`) through the
  tenant-predicate repository base (PROJECT-CONVENTIONS §3.2); `assertCanActIn` guards **every read path**.
  Cross-company leakage is a **release blocker** (BR-SD-17).
- **NFR-SD-02 — Idempotency of the stock / COGS / generation effects.** The POS sale's stock-issue + COGS
  (the shipped finalise path), the drop-ship fulfilment COGS, and the standing-order generation ride the
  shipped outbox / `IdempotencyGuard` / `processed_events` discipline (ADR-0009): a retried event / run
  posts / generates **once**. An integration test must replay each and assert single effect. A violation
  double-counts inventory / COGS or double-bills — a **release blocker** (BR-SD-18).
- **NFR-SD-03 — Money / pricing correctness.** Every amount is a `Money` in the base currency (ADR-0005);
  the **price resolver** returns a base-currency unit price; from there the **shipped**
  `InvoiceTotalsCalculator` computes the totals (HALF_UP, VAT on discounted net) so POS and back-office
  totals are **identical backend and frontend** (the shipped stored-equals-recomputed test discipline). COGS
  uses the shipped moving average (own stock) or the supplier bill cost (drop-ship).
- **NFR-SD-04 — Audit on every transition.** Every session open/close/reconcile, sale rung, refund, pricing
  rule change, drop-ship link, blanket draw, and standing generation, and every costed posting (POS
  variance, drop-ship COGS) is written to the append-only audit trail with actor (or SYSTEM for engine
  postings), action, target, timestamp, and company context (NFR-SD-01).
- **NFR-SD-05 — POS responsiveness.** The POS sale path (resolve price → build line → finalise → issue →
  receipt) must be fast enough for a counter (a single-server round trip per sale; the price resolver reads
  are indexed); the fast UI is a single screen. Pricing-rule reads on the hot path are bounded (indexed by
  company/customer/product/date). (No hard SLA in v1; the design must not table-scan on every keystroke.)
- **NFR-SD-06 — Concurrency.** A POS sale's stock issue reuses the `stock_on_hand` `@Version` optimistic
  lock (the shipped path); a blanket call-off's draw-down must serialise on the blanket balance (optimistic
  `@Version`, no lost draw); two cashiers cannot share one OPEN session.
- **NFR-SD-07 — Numbering concurrency.** Concurrent sessions / blankets / standing orders for the same
  company get distinct numbers (the `code_sequence` row-locked allocation, ADR-0007 D-6 — kinds POS_SESSION
  / BLANKET_ORDER / STANDING_ORDER); the POS invoice keeps `INV-####`.
- **NFR-SD-08 — Forward-compatibility.** The v1 model must not preclude the deferred depth (§2): offline
  POS, POS hardware, card/gateway tenders, bundle promotions, drop-ship split / ASN, standing proration /
  renewal, multi-warehouse POS, and multi-currency. Building these is deferred; precluding them is a defect.
- **NFR-SD-09 — Timestamps** are UTC, displayed per company time zone (Africa/Dar_es_Salaam default). The
  session open/close times, promotion effective dates, blanket validity window, and standing schedule are
  business dates/times distinct from posting timestamps; each posting obeys the GL open-period rule (gl.md
  BR-GL-03).

## 9. Assumptions

- The dependency platform exists and is consumed as designed: the **invoice channel** (ADR-0008) finalises a
  `DIRECT` invoice that issues stock + COGS on finalise (the channel POS reuses); the **valuation/COGS
  engine** (ADR-0020) posts the moving average; the **price-list foundation** (ADR-0007: `price_lists`,
  `product_prices`, customer price-list assignment) is the base the rule layer extends; the **Purchases
  channel** (ADR-0011) raises POs (drop-ship); **Cash & Bank** (ADR-0016) holds the till's cash account and
  the session cash leg; **GL/AR** (ADR-0013/0014) post revenue and the variance; the **outbox** +
  `IdempotencyGuard` (ADR-0009), **Money** (ADR-0005), **`code_sequence`** (ADR-0007), **RBAC /
  `assertCanActIn` / audit** are the platform spine. All shipped.
- **POS reuses the `DIRECT` invoice channel** — a POS sale is a finalised `DIRECT` invoice; the ADR-0021
  delivery seam does not apply (no delivery). The session is an attachment + a UI, not a new costing path.
- **The customer's price-list assignment exists** (ADR-0006/0007); the rule layer resolves against it. A POS
  walk-in defaults to a CASH_WALK_IN customer (the shipped pattern).
- **Drop-ship leans on the Purchases channel's expected contract** — confirming a drop-ship SO raises a PO;
  if a Purchases capability (e.g. a ship-to-customer address on the PO, or a service/non-stock PO flag) is
  not yet built, the ADR designs to its expected contract and states the assumption (OQ-SD-09).
- **Standing-order scheduling leans on the platform scheduler** — the shipped `@Scheduled` outbox poller
  exists; a general scheduler for the generation sweep is a small enabler (PATH-TO-FULL-ERP §3.12 scheduled
  jobs). v1 may ship generation as a **manual action** + a simple sweep; the ADR states which.
- **Single location, base currency** (TZS in practice); multi-warehouse + FX deferred (§2).

## 10. ACCEPTED SCOPE BOUNDARY — what Sales Depth v1 does and does NOT do

> Read this before building or consuming Sales Depth. v1 delivers **POS (session + fast UI on the DIRECT
> invoice channel) + advanced pricing (tiers / customer / promotion via one resolver) + drop-ship (SO →
> linked PO, no own stock, COGS at supplier cost) + blanket/standing orders (commitment + call-off /
> schedule)**. The following are **deliberate boundaries** (owner ratification pending — OQ-SD-00):

1. **POS reuses the DIRECT invoice channel — NO new costing seam.** A POS sale is a `DIRECT` invoice in a
   session; it issues stock + COGS on finalise as today (BR-SD-01). The session is accountability + a fast
   UI. (The seam mechanism is OQ-SD-01.)
2. **Advanced pricing resolves a price, then stops.** Rules (tier / customer / promotion) resolve the
   `unit_price`; the totals/VAT/COGS math is unchanged (BR-SD-06). Precedence is deterministic (OQ-SD-03).
3. **Drop-ship issues NO own stock; COGS is the supplier cost.** A drop-ship line never touches own
   `stock_on_hand`; the cost of sale is the supplier bill cost (BR-SD-11/13). (Recognition point is
   OQ-SD-09.)
4. **The build is STAGED.** POS + advanced pricing ship **first** (self-contained, high-value);
   drop-ship + blanket/standing ship **second** (they lean on Purchases / scheduling). The ADR designs the
   whole model; the build sequences it (project-manager).
5. **Offline POS, POS hardware, card/gateway tenders, bundle promotions, drop-ship split/ASN, standing
   proration/renewal, multi-warehouse POS, multi-currency are DEFERRED** (§2) — accepted boundaries, not
   precluded (NFR-SD-08).

All boundaries are additive by design (NFR-SD-08); none is precluded by the v1 model.

## 11. Open questions

> These are the load-bearing seams the ADR resolves; none blocks the ADR from being written (the behaviour
> is fixed). The architecturally meaty ones are **OQ-SD-01 / OQ-SD-03 / OQ-SD-09** — the POS no-new-seam
> mechanism, the pricing precedence, and the drop-ship COGS recognition. The owner-policy ones are
> **OQ-SD-00 / OQ-SD-02 / OQ-SD-10**.

- **OQ-SD-00 — Owner ratification of the four-feature v1 scope (load-bearing, owner).** This requirements
  doc is architect-authored with owner-style assumptions; the owner should ratify the §2 scope (esp. that
  POS is online-only and card tenders are deferred, that promotions are single-condition, and that drop-ship
  invoices land after fulfilment). *Recommended default:* the §2 scope as written. *Blocks the build:* the
  ADR proceeds on the assumed scope; owner confirms before go-live.
- **OQ-SD-01 — The POS no-new-seam mechanism (load-bearing, architect).** Is a POS sale (a) a normal
  `sales_invoices` row **tagged** with a session id (a column / a join row), or (b) a distinct POS document
  that *delegates* to the invoice channel? *Recommended default:* **(a) a normal `DIRECT` invoice carrying a
  `pos_session_id` link** — it reuses the entire shipped channel (issue/COGS/revenue/tender) with zero new
  costing path, and the session is a pure attachment for drawer reconciliation. *Decider:* architect
  (ADR-0029). *Blocks the ADR:* **NO** — it is the seam decision.
- **OQ-SD-02 — POS variance GL treatment + tender-mix reconciliation (owner/finance).** Over/short post to a
  **misc-income** / **cash-shortage** pair (new GL config keys) — confirm the accounts; and whether the
  close reconciles **cash only** or **cash + mobile-money** separately. *Recommended default:* a new
  `POS_CASH_OVER` (income) + `POS_CASH_SHORT` (expense) config-key pair; reconcile cash + mobile-money as
  distinct tender lines, variance on the **cash** drawer. *Decider:* owner (finance). *Blocks the ADR:* **NO**
  — the model carries the variance; the account mapping is a config seed.
- **OQ-SD-03 — Pricing precedence + stacking (load-bearing, architect/owner).** The resolver precedence
  (customer-specific > promotion > tier > list) and **no stacking** in v1 (the single highest-precedence
  rule wins). *Recommended default:* that precedence, single-rule (no stacking). *Decider:* architect
  (ADR-0029) + owner (commercial policy). *Blocks the ADR:* **NO** — it is the resolver decision.
- **OQ-SD-04 — Where the pricing rules live (architect).** Do tiers / customer-prices / promotions live in
  `products` (next to `product_prices` — the pricing master) or in `sales` (the consuming module)?
  *Recommended default:* **`products`** (they extend `price_lists` / `product_prices`, the pricing master,
  and Sales already reads Products' pricing DTOs); the resolver is a Products service Sales calls.
  *Decider:* architect (ADR-0029). *Blocks the ADR:* **NO**.
- **OQ-SD-05 — Promotion target granularity (architect/owner).** Promotions target a **product / a product
  category / all** — confirm a category dimension exists (or add a simple promotion-category) and whether
  customer-segment targeting is in v1. *Recommended default:* product / category / all; no customer-segment
  targeting in v1 (customer-specific prices cover the per-customer case). *Decider:* owner. *Blocks the ADR:*
  **NO**.
- **OQ-SD-06 — Till ↔ cash/bank account binding + branch model (architect).** A till binds to one
  cash/bank account; can two tills share an account, and does a session recognise cash to the account at
  close or at reconcile? *Recommended default:* a till binds to one cash/bank account (shareable across
  tills); cash recognises at **reconcile** (so the books reflect the verified, reconciled drawer).
  *Decider:* architect (ADR-0029) + owner. *Blocks the ADR:* **NO**.
- **OQ-SD-07 — POS refund authority + same-session-only (owner).** May a refund be processed in a different
  session / by a different cashier; does a refund need supervisor authority above a threshold? *Recommended
  default:* a refund may be in any open session by a user with `SALES.POS.REFUND`; no threshold in v1 (the
  shipped void/return perms apply). *Decider:* owner. *Blocks the ADR:* **NO**.
- **OQ-SD-08 — X/Z read as a document vs a computed read (architect).** Is the Z read a stored document
  (persisted at close) or a computed-on-demand read of the session's totals? *Recommended default:* the Z
  read is **computed on demand** from the session's sale/refund/variance data (no separate persisted
  document); the close stamps the counted/variance figures on the session. *Decider:* architect. *Blocks the
  ADR:* **NO**.
- **OQ-SD-09 — Drop-ship COGS recognition point + clearing account (load-bearing, architect/finance).** When
  does drop-ship COGS recognise — at **PO receipt** (goods shipped) or at **supplier-bill match** — and what
  is the clearing leg (reuse GRNI 2150, or a new drop-ship clearing account)? *Recommended default:*
  recognise COGS at **drop-ship fulfilment** (PO receipt / supplier ship confirmation) DR COGS / CR a
  goods-clearing leg, with the AP bill clearing it on match — **reuse the shipped GRNI 2150 / `GRNI` key**
  (the goods-received-not-invoiced bridge already serves "received not billed"; a drop-ship is the same
  shape), avoiding a new account where the boring one fits. *Decider:* architect (ADR-0029) + owner
  (finance). *Blocks the ADR:* **NO** — it is the drop-ship costing decision; if Purchases' ship-to-customer
  contract is not yet built, the ADR designs to its expected contract and flags it.
- **OQ-SD-10 — Standing-order pricing lock + generation trigger (architect/owner).** Does a generated child
  SO take the **then-current resolved pricing** or the **pricing locked at standing-order create**; and is
  generation **manual**, a **scheduled sweep**, or both? *Recommended default:* **then-current resolved
  pricing** (so price changes flow), generation **manual + a simple scheduled sweep** (idempotent per
  period). *Decider:* architect + owner. *Blocks the ADR:* **NO**.
- **OQ-SD-11 — Blanket commitment basis: quantity vs value (owner).** Is a blanket committed by **quantity
  per product**, by **total value**, or both? *Recommended default:* **quantity per product** in v1 (the
  cleaner draw-down invariant, BR-SD-15); value-based commitments deferred. *Decider:* owner. *Blocks the
  ADR:* **NO**.

## 12. Out of scope for v1 (deferred — restated)

Offline POS / buffered sync; POS hardware (drawer/printer/scanner/scale/display drivers); card / EFTPOS /
gateway / loyalty / gift-card tenders + their reversal; bundle / kit / buy-X-get-Y / mix-and-match
promotions; configurable promotion stacking; customer-segment promotion targeting; drop-ship partial /
multi-supplier split + ASN; standing-order proration / auto-renewal / subscription dunning; multi-warehouse /
location-aware POS + drop-ship; and multi-currency Sales Depth. Each is tracked for a later increment; none
is precluded by the v1 model (NFR-SD-08).
