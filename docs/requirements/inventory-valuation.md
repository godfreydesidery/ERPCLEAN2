# Requirements — Inventory Valuation & COGS (give quantity-only stock a cost, post inventory + cost-of-goods-sold to the books)

> Status: **RATIFIED (owner-confirmed 2026-06-10).** The owner ratified all seven Inventory-Valuation /
> COGS forks — **(1)** costing is **MOVING WEIGHTED AVERAGE**: one running average cost per product (per
> company, single location for now), recomputed at **receipt** from the goods-receipt unit cost
> (`new_avg = (on_hand_value + receipt_qty × receipt_unit_cost) / (on_hand_qty + receipt_qty)`), with issues
> valued at the **current** average; **(2)** inventory becomes **PERPETUAL via a GRNI (Goods-Received-Not-
> Invoiced) clearing account** — a goods receipt posts **DR Inventory (1300) / CR GRNI** at qty × unit cost,
> the AP bill-match **changes** its shipped goods debit from **Purchases (5150) to DR GRNI** (clearing the
> receipt) **/ CR Accounts Payable (2100)**, and a sale (SALE.FINALISED) posts **DR COGS (5100) / CR
> Inventory (1300)** at issued qty × current average (including recipe/BOM explosion, each component at its
> own average) — reversals reverse the postings, append-only; **(3)** **opening inventory valuation IN** —
> set the cost/value of existing quantity-only on-hand once per product (DR Inventory / CR Opening-Balance-
> Equity 3100); **(4)** a **stock valuation report IN** — per product qty × avg = value, with a
> **reconciliation bar: Σ valuation == the Inventory 1300 GL balance** (the BR-VAT-08 precedent), on-screen +
> export; **(5)** **stock-adjustment revaluation IN** — a manual adjustment posts **DR Stock-Adjustment /
> Shrinkage expense (NEW account+key) / CR Inventory (1300)** at the current average (and reverses
> symmetrically); **(6)** **deferred:** multi-location / multi-warehouse valuation, FIFO / standard cost,
> landed cost, stock counts / cycle counts, batch / serial costing, a negative-stock policy beyond the basic
> rule, inter-branch-transfer valuation, and manufacturing WIP; **(7)** permissions **INVENTORY.VALUATION.VIEW**
> (the report) + **INVENTORY.OPENING.SET** (opening valuation) — the receipt / sale / adjustment postings ride
> the existing stock / AP / sales permissions. Each is reflected below as a fixed v1 requirement; everything
> not chosen has moved to the **Deferred** list (§2). **No ADR-0020-blocking open question remains** — the
> meaty items (GRNI per-line vs per-receipt; reverse-at-issue-cost vs current-avg; the negative-stock issue
> cost; the fate of Purchases 5150 for service bills; the average-cost precision) are **ADR decisions**, not
> requirements blockers (the *behaviour* is fixed; the accounts / mechanism / precision are the ADR's to
> choose — flagged below).
>
> Author: system-analyst · Domain: `stock` (valuation depth — costing + the GL postings on the existing
> stock event paths) with touches into `ap` (the GRNI bill-clear) and `gl` (new accounts + keys).
> Business-level spec only. **No schema, no API shapes, no tables/columns, no code** — those are the
> solutions-architect's, in **ADR-0020** (next step). Do not infer a data model from this document.
>
> **This is Inventory Valuation & COGS — Phase B's highest-leverage piece (docs/PATH-TO-FULL-ERP.md §4
> critical-dependency #2; docs/ROADMAP.md T2.2 valuation).** Tier-1 finance is DONE — GL (ADR-0013/V10), AR
> (ADR-0014/V11), AP (ADR-0015/V12), Cash & Bank (ADR-0016/V13), VAT return + WHT (ADR-0017/V14), Financial
> Reporting (ADR-0018/V15), and Year-End Close (ADR-0019/V16) all ship. What is missing is the single biggest
> ERP-completeness gap: **stock today is quantity-only** (`stock_movements` + `stock_on_hand` carry **no cost
> or value**; `StockPostingService` updates on-hand quantity and posts **NO GL**; ADR-0010). The books are
> kept on a **periodic** basis — the shipped AP bill-match posts **DR Purchases (5150) / CR AP (2100)** (a
> period expense, no perpetual inventory asset movement, no cost of sales on a sale). So the P&L has **no cost
> of goods sold**, the balance sheet has **no inventory value**, and there is **no product margin**. This
> slice closes that gap: it gives every stockable product a **moving-average cost**, makes the books
> **perpetual** (inventory is an asset that moves with receipts and issues, bridged by a GRNI clearing
> account between receipt and bill), posts **COGS at the average on every sale** (including recipe explosion),
> and produces a **stock valuation report that reconciles to the Inventory GL balance**.
>
> **Depends on:** **Stock** (the engine this slice drives — ADR-0010 / V7: `stock_movements` (append-only,
> six movement types) + `stock_on_hand` (per company, branch, product; **quantity-only**, optimistic
> `@Version`); the single `StockPostingService.post(...)` primitive every path funnels through; the handlers
> `GoodsReceiptStockHandler` (STOCK.RECEIVED → +qty), `SaleIssueStockHandler` (SALE.FINALISED → −qty, recipe
> explosion via `RecipeExplosionResolver`), `SaleReversalStockHandler`, `GoodsReceiptReversalStockHandler`;
> the `AdjustmentReason` enum already carries **SHRINKAGE / DAMAGE / EXPIRY / COUNT_CORRECTION**); **Purchases**
> (ADR-0011 / V8: `goods_receipt_lines` already carry **`unit_cost_amount` + `line_cost_amount`** — *the cost
> input exists at receipt*; STOCK.RECEIVED is the event); **Accounts Payable** (ADR-0015 / V12: the shipped
> `BillMatchServiceImpl.postMatchedBillToGl` posts **DR Purchases (5150) [+ DR VAT_INPUT] / CR AP (2100)** —
> the line this slice changes to clear GRNI; 3-way match shipped); **Sales** (ADR-0008 / V5: SALE.FINALISED;
> sale lines carry quantity, no COGS today); **GL** (ADR-0013 / V10: the synchronous `GLPostingService.post`;
> `GlConfigKey` already has **INVENTORY (1300) + COGS (5100) defined but UNUSED**, plus PURCHASES (5150),
> ACCOUNTS_PAYABLE (2100), OPENING_BALANCE_EQUITY (3100) — this slice adds a **NEW GRNI** clearing-liability
> key + account and a **NEW Stock-Adjustment / Shrinkage** expense key + account); **Money** (ADR-0005 — base
> currency only); **RBAC / `assertCanActIn` / audit / the idempotent transactional outbox** (the platform
> spine). All shipped. **The central change:** the cost that already exists on `goods_receipt_lines` must
> **reach the stock event path** (the STOCK.RECEIVED payload carries no unit cost today — *the cost-into-event
> seam*, §1 flag), recompute the moving average, and drive a **GL posting on receipt and on sale** (paths that
> post no GL today) — while the AP bill-match swaps its Purchases debit to a **GRNI clear** (the ADR-0017
> VAT_INPUT-swap precedent).

## 1. Business context & why now

ERPCLEAN2 has a complete, balanced double-entry financial spine and a working, event-wired trading core —
but **inventory has no money in it.** Stock (ADR-0010 / V7) tracks **quantity only**: `stock_on_hand` holds a
signed `quantity` per (company, branch, product) with **no cost and no value column**, and `stock_movements`
is a quantity ledger; `StockPostingService.post(...)` updates the on-hand quantity and **posts no GL entry at
all.** On the books, purchases are kept **periodically**: the shipped AP bill-match
(`BillMatchServiceImpl.postMatchedBillToGl`) posts **DR Purchases (5150) / CR Accounts Payable (2100)** —
the goods are expensed when the bill is matched, **not** capitalised as an inventory asset, and a **sale
posts revenue and VAT but no cost of goods sold** (the `SaleIssueStockHandler` deducts quantity and stops).

The consequences are exactly the ones that make a system "balanced books, not yet an ERP":

- the **P&L has no cost of sales** — gross margin is invisible (revenue is posted; the matching cost is a
  lump `5150 Purchases` expense unrelated to what was actually sold in the period);
- the **balance sheet has no inventory asset value** — `1300 Inventory` is defined in the chart of accounts
  and mapped (`GlConfigKey.INVENTORY`) but **never posted to** (it sits at zero);
- there is **no product profitability** — without a unit cost per product, no margin-by-product, no ABC
  analysis, no landed-cost capitalisation, and **no manufacturing WIP costing** is possible (PATH-TO-FULL-ERP
  §4 names valuation/COGS the #2 critical dependency, gating true P&L and all of Phase C Manufacturing).

**Inventory Valuation & COGS closes that gap.** It is a **valuation-depth slice on the existing stock
module** (not a new module): it gives every stockable product a **moving weighted-average cost**, turns the
books **perpetual** (inventory is an asset that rises on receipt and falls on issue), and posts **cost of
goods sold at the average cost on every sale**. The cost input **already exists** — `goods_receipt_lines`
carry `unit_cost_amount` + `line_cost_amount` (V8) — so the receipt knows what was paid; the work is to carry
that cost into the stock event path (the seam — §1 flag), recompute the running average, and drive the GL
postings the receipt and sale paths do not make today.

### The perpetual model and the GRNI bridge (read this before anything else)

Today's books are **periodic** (goods → `5150 Purchases` expense at bill-match). v1 makes them **perpetual**,
and the non-obvious mechanics are:

- **A goods receipt now capitalises inventory.** When goods are received (STOCK.RECEIVED), the system posts
  **DR Inventory (1300) / CR GRNI** at **receipt quantity × receipt unit cost** (from
  `goods_receipt_lines.unit_cost_amount`). Inventory is now an **asset** carried at cost; the credit goes to a
  **NEW clearing liability — GRNI (Goods-Received-Not-Invoiced)** because the goods are *received but not yet
  invoiced by the supplier.* This is the moment the **moving average recomputes** (BR-INV-01).
- **The AP bill clears GRNI (the swap).** When the supplier bill is matched (AP 3-way match —
  accounts-payable.md FR-AP-06), the shipped posting **DR Purchases (5150) / CR AP (2100)** is **changed**:
  for stock/goods bills it becomes **DR GRNI / CR Accounts Payable (2100)** — **clearing** the GRNI accrued at
  receipt and recognising the payable. After both events, **GRNI nets to zero** for that receipt; inventory
  sits on `1300` at cost, the payable on `2100`. This mirrors the **ADR-0017 D-7 VAT_INPUT swap precedent**
  (where AP's VAT debit was moved from `VAT_PAYABLE` to `VAT_INPUT` — a small, additive change to the same
  posting method). **`5150 Purchases` is retained for non-stock / service bills** (a service or expense bill
  with no goods receipt has no GRNI to clear and still expenses to `5150`) — the fate of `5150` for the
  service-bill path is flagged (OQ-INV-04, the architect confirms the GOODS-vs-SERVICE branch).
- **A sale now posts cost of goods sold.** When a sale finalises (SALE.FINALISED), in addition to the
  quantity deduction the system already makes, it posts **DR COGS (5100) / CR Inventory (1300)** at **issued
  quantity × the current moving-average cost** — for a composed product, the **recipe/BOM explosion** posts
  each stockable component at **its own** current average (BR-INV-04). The P&L now carries **cost of sales**
  matched to the revenue; the balance sheet's inventory falls by the cost issued.
- **Reversals reverse the postings.** A sale void (SALE.VOIDED) reverses the COGS entry; a goods-receipt
  reversal reverses the inventory/GRNI entry — append-only reversing entries, **never** edits (BR-INV-05).
  The cost at which a reversal restores is fixed at **the original issue/receipt cost** (recommended) rather
  than the now-current average (flagged — OQ-INV-02).
- **The valuation report reconciles to the books.** A **stock valuation report** lists per product the
  on-hand quantity × the moving-average cost = the inventory value, and totals it; the total **must equal the
  `1300 Inventory` GL balance** (BR-INV-06, the **reconciliation bar** — the same discipline as the VAT
  return's BR-VAT-08 and Reporting's recon ties). A disagreement is a finance-grade defect.

> **Flag for the architect (ADR-0020):** the load-bearing decisions are (1) the **cost-into-event seam** —
> `StockReceivedPayload.LineItem` carries `{ productId, productUid, unitId, qtyInBase }` and **no unit cost**
> today; the receipt unit cost lives on `goods_receipt_lines.unit_cost_amount` (V8). ADR-0020 must carry the
> unit cost to the stock receipt handler (extend the STOCK.RECEIVED payload — recommended, the leanest
> additive shape — **or** have the handler read the GR line cost by uid as a DTO). (2) The **new GL accounts +
> `gl_config` keys** — a **GRNI** clearing liability (a `2xxx` account, e.g. "2150 Goods Received Not
> Invoiced") + a `GRNI` key, and a **Stock-Adjustment / Shrinkage** expense (a `5xxx` account, e.g. "5200
> Stock Adjustment / Shrinkage") + a `STOCK_ADJUSTMENT` key (the IN-list widens additively, as ADR-0013 D-13
> anticipated; INVENTORY/COGS already exist, unused). (3) The **GRNI granularity** — accrue/clear GRNI
> **per goods-receipt line** or **per receipt** (OQ-INV-03; recommended per-line so a partial bill clears the
> right portion). (4) The **reversal cost policy** (reverse at original cost vs current average — OQ-INV-02;
> recommended original cost). (5) The **negative-on-hand issue cost** (OQ-INV-01; recommended block, with the
> fallback of last-known average flagged). (6) The **average-cost storage + precision** — where the running
> average and the on-hand value live (likely cost/value columns on `stock_on_hand`, additive — NFR-STOCK-06)
> and to how many decimals (OQ-INV-06; recommended a higher internal scale than the 0-dp base-currency display,
> rounded HALF_UP on posting). (7) The **GOODS-vs-SERVICE bill branch** that decides whether a bill clears
> GRNI or still expenses to `5150` (OQ-INV-04). (8) The **concurrency guard** on the moving-average recompute
> (racing receipts) — reuse the `stock_on_hand` optimistic `@Version` (NFR-INV-05). State these; do not design
> the tables here. **None blocks the requirements** — the behaviour is fixed; the accounts / mechanism /
> granularity / precision are the ADR's to choose.

### Vocabulary (read this first)

- **Moving weighted average (moving average cost)** — the costing method: **one running average unit cost per
  product** (per company; single location in v1), recomputed **at each receipt** as
  `new_avg = (on_hand_value + receipt_qty × receipt_unit_cost) / (on_hand_qty + receipt_qty)`. Issues (sales,
  adjustments out) are valued at the **current** average; an issue **does not** change the average. The first
  receipt sets the average to the receipt unit cost (BR-INV-01).
- **Inventory value** — the money carried in stock: per product **on-hand quantity × moving-average cost**,
  summed across products = the company's inventory asset. Sits on **GL `1300 Inventory`** in the perpetual
  model. The valuation report computes it and **reconciles** it to the GL balance (BR-INV-06).
- **Cost of goods sold (COGS)** — the **cost** of the inventory issued on a sale — **issued quantity × the
  current moving-average cost** — posted **DR `5100 COGS` / CR `1300 Inventory`** on SALE.FINALISED. The P&L
  expense matched to the sale's revenue; the thing that makes **gross margin** visible (BR-INV-04).
- **GRNI (Goods-Received-Not-Invoiced)** — a **NEW clearing liability account** (a `2xxx`) bridging the gap
  between **receiving goods** (DR Inventory / CR GRNI at receipt) and **being billed** for them (DR GRNI / CR
  AP at bill-match). After both, GRNI **nets to zero** for that receipt. It is the perpetual-inventory
  accrual the periodic `5150 Purchases` posting does not make.
- **Perpetual inventory** — inventory tracked **continuously** as an asset that **rises on receipt** (DR
  Inventory) and **falls on issue** (CR Inventory, DR COGS) — as opposed to **periodic inventory** (goods
  expensed to `5150 Purchases` at bill-match, inventory value derived only at a period count). v1 moves the
  books from periodic to perpetual.
- **Periodic inventory** — the **shipped** treatment: goods → `5150 Purchases` expense at AP bill-match, no
  inventory asset movement, no COGS on sale, inventory value known only by a physical count. v1 **replaces**
  this for stock/goods bills (service bills keep `5150` — OQ-INV-04).
- **Opening (inventory) valuation** — the **one-time** act of assigning a cost/value to the **existing
  quantity-only on-hand** when this slice goes live: per product, set the average cost and post **DR Inventory
  (1300) / CR Opening-Balance-Equity (3100)** at qty × cost — **once per product** (BR-INV-07). It is **not** a
  goods receipt and **not** the stock-module opening balance (which seeds quantity).
- **Revaluation** — a change to the **value** of on-hand inventory without a quantity sale: in v1 it is the
  **stock-adjustment revaluation** — a manual adjustment (write-off, shrinkage, damage, count correction)
  posts **DR Stock-Adjustment/Shrinkage expense / CR Inventory** at the current average for a decrease (and
  the reverse for an increase). General mark-to-market / standard-cost revaluation is deferred (§2).
- **Shrinkage** — inventory **lost** (theft, spillage, damage, expiry) recorded as a stock adjustment **out**;
  its **value** (qty × avg) is expensed to the **Stock-Adjustment / Shrinkage** account. The `AdjustmentReason`
  enum already carries SHRINKAGE / DAMAGE / EXPIRY / COUNT_CORRECTION (ADR-0010 D-7).
- **Average-cost recompute** — the recalculation of a product's moving-average cost **on a receipt** (the only
  event that changes the average in v1). Receipts recompute; issues consume at the current average; opening
  valuation seeds it; an adjustment **out** consumes at the current average (and does not change it).
- **Cost-into-event seam** — the gap this slice must close: the receipt unit cost exists on
  `goods_receipt_lines.unit_cost_amount` (V8) but the STOCK.RECEIVED payload carries **no unit cost** today;
  the cost must reach the stock receipt handler to recompute the average and post to GL (§1 flag, OQ-INV-05).

> **Word discipline (carried into the glossary):** **moving weighted average** (one running avg, recomputed at
> receipt) is **not** **FIFO** (cost layers) and **not** **standard cost** (a fixed planned cost with
> variances) — v1 is moving average only (§2). **Inventory value** (qty × avg, the asset on `1300`) is **not**
> **COGS** (the cost issued on a sale, the expense on `5100`) — receipt grows inventory, a sale moves it to
> COGS. **GRNI** (a clearing **liability**, goods received not yet billed) is **not** **Accounts Payable** (the
> billed payable) and **not** **`5150 Purchases`** (the periodic expense v1 retires for stock bills) — GRNI is
> the bridge that **nets to zero** once a receipt is billed. **Perpetual** (continuous asset tracking) is
> **not** **periodic** (expense-at-bill, count-at-period). **Opening valuation** (one-time cost/value seed, DR
> Inventory / CR 3100) is **not** a **goods receipt** (DR Inventory / CR GRNI) and **not** the stock-module
> **opening balance** (quantity seed). A **revaluation** (a value change with no sale) is **not** a **sale**
> (a quantity + value reduction to COGS). The **average recomputes at receipt only** — an **issue never
> changes the average**, it consumes at the current one.

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-10). This is **Inventory Valuation & COGS —
> Phase B's highest-leverage piece (T2.2)**: a **moving weighted-average** cost per product, **perpetual**
> inventory via a **GRNI** bridge (receipt → DR Inventory / CR GRNI; bill → DR GRNI / CR AP), **COGS at the
> average on every sale** (incl. recipe explosion), **opening inventory valuation**, a **stock valuation
> report** that reconciles to the Inventory GL balance, and **stock-adjustment revaluation**. It is a
> **valuation-depth slice on the existing stock module** — it adds cost + GL postings to paths that today
> move quantity only; it does **not** rebuild Stock, Sales, or AP.

### In scope (v1 — "give stock a moving-average cost, post inventory + COGS to the perpetual books via GRNI, value the opening stock, report it reconciled to GL, and revalue on adjustment")

- **Moving weighted-average costing, per product, per company (single location).** One running average unit
  cost per stockable product, recomputed at **receipt** (`new_avg = (on_hand_value + receipt_qty ×
  receipt_unit_cost) / (on_hand_qty + receipt_qty)`); issues valued at the **current** average; first receipt
  sets the average to the receipt unit cost; rounding **HALF_UP** in base currency (BR-INV-01).
- **Average-cost recompute on goods receipt.** On STOCK.RECEIVED, carry the receipt unit cost from
  `goods_receipt_lines.unit_cost_amount` into the stock event path (the seam — §1 flag), recompute the product
  average, and store the new average + on-hand value (FR-INV-01).
- **Perpetual GL on goods receipt (DR Inventory / CR GRNI).** On STOCK.RECEIVED, post a synchronous GL entry
  **DR `1300 Inventory` / CR GRNI (NEW)** at receipt qty × unit cost — the receipt path posts **no GL** today
  (FR-INV-02). `INVENTORY` (1300) is finally posted-to.
- **AP bill clears GRNI (the swap).** Change `BillMatchServiceImpl.postMatchedBillToGl` so a **stock/goods**
  bill posts **DR GRNI / CR `2100 AP`** (clearing the receipt accrual) instead of **DR `5150 Purchases`** —
  the ADR-0017 D-7 VAT_INPUT-swap precedent; `5150 Purchases` is **retained for non-stock/service bills**
  (OQ-INV-04). After both events GRNI nets to zero for the receipt (FR-INV-03, BR-INV-08).
- **COGS on sale at the current average (incl. recipe explosion).** On SALE.FINALISED, in addition to the
  shipped quantity deduction, post a synchronous GL entry **DR `5100 COGS` / CR `1300 Inventory`** at issued
  qty × current average; for a composed product, the recipe explosion posts **each stockable component at its
  own** current average (FR-INV-04). `COGS` (5100) is finally posted-to.
- **Reversals reverse the postings.** A sale void (SALE.VOIDED) reverses the COGS entry; a goods-receipt
  reversal reverses the inventory/GRNI entry — append-only reversing entries; restore at the **original
  issue/receipt cost** (recommended — OQ-INV-02) (FR-INV-05).
- **Opening inventory valuation (once per product).** A user with `INVENTORY.OPENING.SET` sets the cost/value
  of existing quantity-only on-hand: per product, seed the average cost and post **DR `1300 Inventory` / CR
  `3100 Opening-Balance-Equity`** at qty × cost — **once per product** (a second attempt is rejected)
  (FR-INV-06, BR-INV-07).
- **Stock valuation report (reconciled to GL).** A user with `INVENTORY.VALUATION.VIEW` reads a report listing
  per product **on-hand qty × moving-average cost = value**, totalled; with a **reconciliation bar: Σ value ==
  the `1300 Inventory` GL balance** (BR-INV-06, the BR-VAT-08 precedent); on-screen + export (FR-INV-07).
- **Stock-adjustment revaluation.** A manual stock adjustment (the shipped ADJUSTMENT path —
  `AdjustmentReason` SHRINKAGE / DAMAGE / EXPIRY / COUNT_CORRECTION / RECEIPT_CORRECTION / OTHER) now posts a
  synchronous GL entry: a **decrease** posts **DR Stock-Adjustment/Shrinkage expense (NEW) / CR `1300
  Inventory`** at qty × current average; an **increase** posts the reverse (DR Inventory / CR the adjustment
  account); the average is **unchanged** by an adjustment out, and an adjustment **in** at the current average
  leaves it unchanged (FR-INV-08, BR-INV-09).
- **Permissions** — **`INVENTORY.VALUATION.VIEW`** (read the valuation report) + **`INVENTORY.OPENING.SET`**
  (set opening valuation); the **receipt / sale / adjustment postings ride the existing** stock / AP / sales
  permissions (STOCK.RECEIVE via the GR path, the sale finalise, STOCK.ADJUST) — they are automatic
  consequences of those acts, not separately gated. Per-company scope; `assertCanActIn` on **every read
  path**; **audit** on opening valuation + each costed posting (NFR-INV-03).
- **Migration footprint (V17, additive).** The new **GRNI** account + `GRNI` key + the **Stock-Adjustment /
  Shrinkage** account + `STOCK_ADJUSTMENT` key; the two new permissions; likely **average-cost + on-hand-value
  columns on `stock_on_hand`** (additive — NFR-STOCK-06 anticipates cost columns); seeded with **#12-safe
  seed-uids** (the deterministic seed-uid discipline). V1–V16 frozen.

### Deferred (recognised, NOT built in v1 — separate later increments)

- **Multi-location / multi-warehouse valuation.** v1 costs a product at **one** moving average per company
  (single location). A per-location / per-warehouse cost (and the cost effects of inter-location transfers) is
  deferred — OQ-INV-07. (Stock multi-location is itself a separate deferred item — PATH-TO-FULL-ERP §3.5.)
- **FIFO / LIFO / standard cost.** v1 is **moving weighted average** only. FIFO (cost layers), standard cost
  (a fixed planned cost + purchase-price / usage variances), and a per-product method choice are deferred —
  the model does not preclude adding a method dimension later.
- **Landed cost.** v1 values a receipt at the `goods_receipt_lines.unit_cost_amount` (the supplier price).
  Apportioning **freight / duty / insurance** into the unit cost (capitalising landed cost) is deferred — it
  rides this valuation foundation (PATH-TO-FULL-ERP §3.4/§3.5).
- **Stock counts / cycle counts.** v1 revalues on a **manual adjustment** (the shipped ADJUSTMENT path). A
  formal physical-count / cycle-count process with system-vs-counted variance posting is deferred
  (OQ-STOCK-07) — its variance posting will reuse this slice's adjustment-revaluation machinery.
- **Batch / lot / serial costing.** v1 costs at the product level. Per-batch / per-serial cost (and FEFO
  costing) is deferred.
- **Negative-stock policy beyond the basic rule.** v1 fixes a **single** rule for issuing at zero/negative
  on-hand (OQ-INV-01); richer policies (allow-with-flag, deferred-cost-on-negative, cost-true-up when the next
  receipt lands) are deferred.
- **Inter-branch / inter-warehouse transfer valuation.** The TRANSFER_OUT / TRANSFER_IN movement types are
  **reserved** in the enum (ADR-0010 D-4, OQ-STOCK-08) but not built; their cost treatment (move value at the
  source average) is deferred with the transfer feature.
- **Manufacturing WIP costing.** Production-order WIP (DR WIP / CR material+labour+overhead → DR FG / CR WIP)
  is **Phase C Manufacturing** — it **builds on** this slice's moving-average components but is out of scope
  here (PATH-TO-FULL-ERP §3.6).
- **Stock valuation / ageing / movement-value reports beyond the v1 report.** Aged inventory, slow-moving /
  obsolete analysis, ABC value segmentation, and movement-value history are **Reporting** depth (T2.3) — v1
  delivers the **valuation report + recon bar** they read.

### Explicitly NOT this module

- **The stock quantity engine** — **Stock** (ADR-0010) owns `stock_movements` / `stock_on_hand` / the six
  movement types / the recipe explosion / the optimistic-lock concurrency. This slice **adds cost + GL
  postings** to those paths; it does **not** change the quantity model, the negative-on-hand design (the
  costing rule for issuing at negative is OQ-INV-01), or the movement vocabulary.
- **The General Ledger itself** — **GL** (ADR-0013) owns the books, `1300 Inventory`, `5100 COGS`, `5150
  Purchases`, `2100 AP`, `3100 OBE`, and the **NEW GRNI + Stock-Adjustment** accounts ADR-0020 adds. This
  slice **posts** the inventory / COGS / GRNI / adjustment entries through `GLPostingService`; it never edits a
  posted journal — corrections are reversing entries (BR-INV-05).
- **The AP sub-ledger** — **AP** (ADR-0015) owns the supplier bill, the 3-way match, and the bill-match
  posting; this slice **changes one posting line** in that method (Purchases → GRNI for goods bills) and does
  not own the bill, the match tolerance, or the payable.
- **Sales** — **Sales** (ADR-0008) owns the invoice, finalise, and void; this slice **rides** SALE.FINALISED /
  SALE.VOIDED (the COGS posting + its reversal) and does not own the invoice or its revenue/VAT posting.
- **Per-category COGS / cost centres** — a single `5100 COGS` account in v1 (the gl_config `COGS` mapping);
  COGS by product category / cost centre is a Reporting/Budgeting dimension item (PATH-TO-FULL-ERP §3.11),
  deferred.
- **Financial statements & analytics** — the P&L that now shows gross margin, the balance sheet that now shows
  inventory value, and stock valuation/ageing/ABC analytics are **Reporting** (T2.3); this slice provides the
  postings + the valuation report + the recon bar they read.

## 3. The model: average cost, the perpetual GL postings, the GRNI bridge, opening valuation, the report, and revaluation

### 3.1 Moving weighted-average cost (per product, per company, single location)

Each stockable product carries **one running average unit cost** in the company base currency. The average
**recomputes only at a receipt**:

```
new_avg = (on_hand_value + receipt_qty × receipt_unit_cost) / (on_hand_qty + receipt_qty)
```

where `on_hand_value = on_hand_qty × old_avg`. The **first** receipt (on-hand zero, no prior average) sets the
average to the **receipt unit cost** (BR-INV-01). An **issue** (sale, adjustment out) is valued at the
**current** average and **does not change** the average; the on-hand value falls by `issued_qty × current_avg`.
The receipt unit cost is `goods_receipt_lines.unit_cost_amount` (V8), carried into the stock event path (the
seam — §1 flag). Rounding is **HALF_UP** in base currency (TZS); the internal average may be held at a higher
precision than the 0-dp display, rounded on posting (OQ-INV-06). The average + the on-hand value are stored
(likely additive columns on `stock_on_hand` — NFR-STOCK-06, the architect's in ADR-0020). v1 is **single
location** — one average per (company, product); multi-location per-location cost is deferred (OQ-INV-07).

### 3.2 Perpetual GL on a goods receipt — DR Inventory / CR GRNI

On STOCK.RECEIVED (the `GoodsReceiptStockHandler` path), in addition to the shipped +quantity movement, the
system posts a **synchronous GL entry** (via `GLPostingService.post`, the AP/AR/Cash/sales-auto-post
precedent): **DR `1300 Inventory` / CR GRNI** at **receipt qty × receipt unit cost** (BR-INV-02). The receipt
**capitalises** the goods as an inventory asset; the credit to the **GRNI** clearing liability records "goods
received, not yet invoiced." The receipt path posts **no GL today** — this is new (FR-INV-02). The
average-cost recompute (§3.1) and this posting happen together in the handler's transaction (idempotent — the
existing STOCK.RECEIVED idempotency guard, NFR-INV-04).

### 3.3 The AP bill clears GRNI — DR GRNI / CR AP (the swap)

When the supplier bill matches (AP 3-way match — accounts-payable.md FR-AP-06), the shipped
`postMatchedBillToGl` posting **DR `5150 Purchases` [+ DR `VAT_INPUT`] / CR `2100 AP`** is **changed** for a
**stock/goods** bill to **DR GRNI [+ DR `VAT_INPUT`] / CR `2100 AP`** — **clearing** the GRNI accrued at
receipt instead of expensing to Purchases (BR-INV-08). The VAT_INPUT leg is **unchanged** (ADR-0017 D-7). For
a **non-stock / service** bill (no goods receipt, no GRNI to clear) the posting **stays** `DR 5150 Purchases /
CR 2100 AP` — the GOODS-vs-SERVICE branch the architect confirms (OQ-INV-04). After receipt + bill, **GRNI
nets to zero** for the receipt (BR-INV-08, the recon). GRNI granularity — per-line vs per-receipt — is the
architect's (OQ-INV-03; recommended per-line so a partial bill clears the right portion). This is the
**ADR-0017 D-7 VAT_INPUT-swap precedent** applied again (a small additive change to one posting method).

### 3.4 COGS on a sale — DR COGS / CR Inventory at the current average

On SALE.FINALISED (the `SaleIssueStockHandler` path), in addition to the shipped −quantity deduction (and
recipe explosion), the system posts a **synchronous GL entry**: **DR `5100 COGS` / CR `1300 Inventory`** at
**issued qty × the current moving-average cost** (BR-INV-04). For a **simple** stockable product, one COGS leg
at its average. For a **composed** product, the recipe explosion posts COGS for **each stockable component at
its own** current average (a non-stockable or non-composed line posts no COGS, mirroring the quantity rule —
ADR-0010 D-8). The sale path posts **no COGS today** — this is new (FR-INV-04). The P&L now carries **cost of
sales** matched to the revenue (which the sales auto-poster already posts — gl.md §3.1); **gross margin =
revenue − COGS** is visible. Idempotent under the existing SALE.FINALISED guard (NFR-INV-04).

### 3.5 Reversals — reverse the postings (append-only)

- **A sale void (SALE.VOIDED)** reverses the COGS entry: it posts **DR `1300 Inventory` / CR `5100 COGS`** at
  the **original issue cost** (recommended — OQ-INV-02), restoring the inventory value that the sale removed —
  alongside the shipped quantity reversal (the `SaleReversalStockHandler`).
- **A goods-receipt reversal** reverses the inventory/GRNI entry: it posts **DR GRNI / CR `1300 Inventory`**
  at the **original receipt cost**, backing out the capitalisation — alongside the shipped quantity reversal
  (the `GoodsReceiptReversalStockHandler`).

All reversals are **append-only reversing entries**, never edits (BR-INV-05, gl.md BR-GL-02). The reversal
cost — **original issue/receipt cost** (recommended) vs the **now-current** average — is flagged (OQ-INV-02);
restoring at the original cost keeps the books symmetric and avoids a phantom gain/loss from average drift.

### 3.6 Opening inventory valuation — DR Inventory / CR Opening-Balance-Equity (once per product)

When this slice goes live, existing on-hand is **quantity-only** (no cost). A user with
`INVENTORY.OPENING.SET` performs a **one-time opening valuation** per product: provide the **opening unit
cost** (or value), seed the product's **moving-average cost**, and post a **synchronous GL entry DR `1300
Inventory` / CR `3100 Opening-Balance-Equity`** at on-hand qty × opening cost (BR-INV-07, FR-INV-06). It is
**once per product** — a second attempt for a product already valued is **rejected** (mirroring the stock
opening-balance "rejected if prior movement" rule, ADR-0010). It is **not** a goods receipt (no GRNI) and
**not** the stock-module quantity opening balance. After opening valuation, the valuation report's total
should equal the seeded `1300 Inventory` balance (BR-INV-06).

### 3.7 Stock valuation report — reconciled to the Inventory GL balance

A user with `INVENTORY.VALUATION.VIEW` reads a **stock valuation report**: per product, **on-hand quantity ×
moving-average cost = inventory value**, totalled across products. The report carries a **reconciliation bar**:
the **Σ valuation must equal the `1300 Inventory` GL balance** (BR-INV-06) — the same discipline as the VAT
return's BR-VAT-08 and Reporting's recon ties (reporting.md). A disagreement is a **finance-grade defect**
surfaced for investigation. The report is **on-screen + export** (CSV; PDF/Excel ride the X.1 document
enabler), per-company-scoped (FR-INV-07).

### 3.8 Stock-adjustment revaluation — DR/CR Stock-Adjustment ↔ Inventory at the average

A manual stock adjustment (the shipped ADJUSTMENT path, `STOCK.ADJUST`, with a mandatory `AdjustmentReason` —
SHRINKAGE / DAMAGE / EXPIRY / COUNT_CORRECTION / RECEIPT_CORRECTION / OTHER) now **revalues** the books:

- an **adjustment out** (decrease — shrinkage / damage / expiry / count-down) posts **DR Stock-Adjustment /
  Shrinkage expense (NEW) / CR `1300 Inventory`** at the adjusted qty × the **current average** (the average is
  **unchanged**);
- an **adjustment in** (increase — count-up / receipt-correction up) posts the reverse **DR `1300 Inventory` /
  CR Stock-Adjustment** at qty × the **current average** (the average is **unchanged** — an adjustment in at
  the current average does not move it).

The shipped ADJUSTMENT posts **no GL today** — this adds it (FR-INV-08, BR-INV-09). Shrinkage / write-off
value lands in the **Stock-Adjustment / Shrinkage** P&L account (the dedicated home the periodic model lacked).

## 4. Actors / personas

- **Stock controller / warehouse manager** — receives goods (drives STOCK.RECEIVED, hence the average recompute
  + the DR Inventory / CR GRNI posting), records **stock adjustments** (`STOCK.ADJUST` → the revaluation
  posting), and reads the **valuation report** (`INVENTORY.VALUATION.VIEW`). The operator whose acts move the
  inventory asset.
- **Accountant** — sets the **opening inventory valuation** (`INVENTORY.OPENING.SET`) at go-live, **reconciles**
  the valuation report to the `1300 Inventory` GL balance (BR-INV-06), reviews the **GRNI** clearing balance
  (open receipts not yet billed), and reads the COGS the P&L now carries. The owner of the perpetual-inventory
  books.
- **Owner / general manager** — reads **product margin** (revenue − COGS) and **inventory value** the slice
  unlocks; the consumer of the gross-margin / inventory-value outcome (via Reporting, T2.3).
- **AP payments / matching officer (at bill-match)** — not a valuation persona per se, but the operator whose
  **bill-match now clears GRNI** instead of expensing Purchases (§3.3, the additive change to the AP posting) —
  they match in AP; this slice changes the posting line.
- *(No new human actor on the receipt / sale COGS postings — they are **system-posted in-request** on the
  existing event-handler paths (STOCK.RECEIVED, SALE.FINALISED), under the event's company/branch scope, like
  the shipped sales auto-poster. The **opening valuation** and the **adjustment** are human acts; their
  postings are synchronous in the same transaction.)*

## 5. Functional requirements

> IDs are `FR-INV-NN`. Each is a crisp, testable, **ratified** statement. "Moving average" = the per-product
> running average of §3.1; "current average" = the product's average at the moment of an issue; "post to GL" =
> a synchronous `GLPostingService.post` in the same transaction as the stock movement (the AP/AR/Cash/sales-
> auto-post precedent — gl.md); "receipt unit cost" = `goods_receipt_lines.unit_cost_amount` (V8) carried into
> the stock event path (the seam — §1 flag, OQ-INV-05).

### Average-cost recompute

- **FR-INV-01** On a **goods receipt** (STOCK.RECEIVED), the system **recomputes the product's moving-average
  cost** as `new_avg = (on_hand_value + receipt_qty × receipt_unit_cost) / (on_hand_qty + receipt_qty)` from the
  receipt unit cost (`goods_receipt_lines.unit_cost_amount`, carried into the stock event path — the seam,
  OQ-INV-05) and stores the new average + on-hand value (likely additive columns on `stock_on_hand` —
  NFR-STOCK-06). The **first** receipt sets the average to the receipt unit cost; rounding HALF_UP base
  currency (BR-INV-01). An **issue does not change** the average (BR-INV-01).

### Perpetual GL on receipt + the GRNI clear

- **FR-INV-02** On a **goods receipt** (STOCK.RECEIVED), the system **posts a synchronous GL entry DR `1300
  Inventory` / CR GRNI (NEW)** at **receipt qty × receipt unit cost**, in the same transaction as the +quantity
  movement (the receipt path posts no GL today). `INVENTORY` (1300) is now posted-to (BR-INV-02).
- **FR-INV-03** When the **supplier bill matches** (AP 3-way match — accounts-payable.md FR-AP-06), the
  bill-match posting is **changed** for a **stock/goods** bill from **DR `5150 Purchases` / CR `2100 AP`** to
  **DR GRNI / CR `2100 AP`** — **clearing** the GRNI accrued at receipt (the VAT_INPUT leg unchanged, ADR-0017
  D-7). A **non-stock / service** bill **retains** `DR 5150 Purchases / CR 2100 AP` (the GOODS-vs-SERVICE
  branch — OQ-INV-04). After receipt + bill, GRNI **nets to zero** for the receipt (BR-INV-08).

### COGS on sale

- **FR-INV-04** On a **sale finalise** (SALE.FINALISED), the system **posts a synchronous GL entry DR `5100
  COGS` / CR `1300 Inventory`** at **issued qty × the current moving-average cost**, in the same transaction as
  the −quantity deduction (the sale path posts no COGS today). For a **composed** product, the **recipe
  explosion** posts COGS for **each stockable component at its own** current average; a non-stockable /
  non-composed line posts **no COGS** (mirroring the quantity rule, ADR-0010 D-8). `COGS` (5100) is now
  posted-to (BR-INV-04).

### Reversals

- **FR-INV-05** A **sale void** (SALE.VOIDED) **reverses the COGS entry** (DR `1300 Inventory` / CR `5100
  COGS`) and a **goods-receipt reversal reverses the inventory/GRNI entry** (DR GRNI / CR `1300 Inventory`),
  each an **append-only reversing entry** alongside the shipped quantity reversal, restoring at the **original
  issue/receipt cost** (recommended — OQ-INV-02). No posting is edited or deleted (BR-INV-05).

### Opening valuation

- **FR-INV-06** A user with `INVENTORY.OPENING.SET` may perform a **one-time opening inventory valuation** per
  product: provide the opening unit cost, seed the product's moving-average cost, and **post a synchronous GL
  entry DR `1300 Inventory` / CR `3100 Opening-Balance-Equity`** at on-hand qty × opening cost. It is **once per
  product** — a second attempt for an already-valued product is **rejected** (BR-INV-07).

### Valuation report

- **FR-INV-07** A user with `INVENTORY.VALUATION.VIEW` may read a **stock valuation report**: per product
  **on-hand qty × moving-average cost = value**, totalled, with a **reconciliation bar** showing the **Σ value
  vs the `1300 Inventory` GL balance** (BR-INV-06); on-screen + export, per-company-scoped. No read crosses
  company scope (BR-INV-10, NFR-INV-01).

### Adjustment revaluation

- **FR-INV-08** A **manual stock adjustment** (the shipped ADJUSTMENT path, `STOCK.ADJUST`, with a mandatory
  `AdjustmentReason`) **posts a synchronous GL revaluation**: a **decrease** posts **DR Stock-Adjustment /
  Shrinkage expense (NEW) / CR `1300 Inventory`** at adjusted qty × current average; an **increase** posts the
  reverse (DR `1300 Inventory` / CR Stock-Adjustment); the average is **unchanged** (the ADJUSTMENT path posts
  no GL today) (BR-INV-09).

### Scope & permissions

- **FR-INV-09** Inventory valuation is **scoped per company**; every average cost, on-hand value, opening
  valuation, and costed posting belongs to exactly one company; no read crosses company scope. `assertCanActIn`
  guards **every read path** (BR-INV-10, NFR-INV-01).
- **FR-INV-10** The new valuation operations are **gated by IAM permissions**: **`INVENTORY.VALUATION.VIEW`**
  (the report) and **`INVENTORY.OPENING.SET`** (opening valuation). The **receipt / sale / adjustment costed
  postings ride the existing** stock / AP / sales permissions (they are consequences of those acts). Exact codes
  are seeded with the module (the V17 migration). Per-company scope; **audit** on opening valuation + each
  costed posting (NFR-INV-03).

## 6. Business rules (invariants)

> Ratified. These are the inventory-valuation / COGS invariants; a violation that breaks the valuation-to-GL
> reconciliation, double-counts inventory, or posts an unbalanced cost entry is a finance-grade defect (a
> release blocker).

- **BR-INV-01 — Moving weighted average; recompute at receipt, consume at the current average.** A product's
  cost is **one running average** recomputed only on a **receipt** by `new_avg = (on_hand_value + receipt_qty ×
  receipt_unit_cost) / (on_hand_qty + receipt_qty)`; the **first** receipt sets it to the receipt unit cost; an
  **issue** is valued at the **current** average and **does not change** it; rounding HALF_UP base currency
  (FR-INV-01). **Edge cases:** (a) **issue at zero/negative on-hand** — the recommended rule is to **block the
  costed issue** (or use the last-known average) until a receipt establishes a cost (OQ-INV-01); (b) **a
  zero-cost receipt** drags the average toward zero — accepted as a data-entry consequence, surfaced for review,
  not silently rejected; (c) the average must **never go negative** — a computation yielding a negative average
  is a defect (NFR-INV-02).
- **BR-INV-02 — A goods receipt capitalises inventory at cost (DR Inventory / CR GRNI), balanced.** Every
  STOCK.RECEIVED posts a **balanced** entry **DR `1300 Inventory` / CR GRNI** at receipt qty × unit cost, in the
  same transaction as the +quantity movement (FR-INV-02). Σ debits == Σ credits.
- **BR-INV-03 — Perpetual, not periodic, for stock bills.** Stock/goods bills no longer expense to `5150
  Purchases` at bill-match; they **clear GRNI** (DR GRNI / CR AP), and the goods live on `1300 Inventory` as an
  asset until sold (FR-INV-03). `5150 Purchases` is **retained for non-stock / service bills** (OQ-INV-04).
- **BR-INV-04 — COGS at the current average on every sale, incl. recipe explosion, balanced.** Every
  SALE.FINALISED posts a **balanced** entry **DR `5100 COGS` / CR `1300 Inventory`** at issued qty × the
  current average, in the same transaction as the −quantity deduction; a **composed** product posts COGS for
  **each stockable component at its own** average; a non-stockable / non-composed line posts no COGS (FR-INV-04).
  Σ debits == Σ credits.
- **BR-INV-05 — Append-only; reversals reverse, never edit.** A sale void reverses the COGS entry and a
  goods-receipt reversal reverses the inventory/GRNI entry, each a **new reversing entry** restoring at the
  **original** issue/receipt cost (OQ-INV-02); no costed posting is edited or deleted (FR-INV-05, gl.md
  BR-GL-02).
- **BR-INV-06 — The valuation report reconciles to GL (the recon bar).** The stock valuation report's **Σ (per
  product on-hand qty × moving-average cost) MUST equal the `1300 Inventory` GL balance** for the company
  (FR-INV-07). A disagreement is a **finance-grade defect** (the reconciliation bar — the BR-VAT-08 precedent,
  reporting.md recon ties).
- **BR-INV-07 — Opening valuation once per product (DR Inventory / CR OBE).** The opening inventory valuation
  posts **DR `1300 Inventory` / CR `3100 Opening-Balance-Equity`** at on-hand qty × opening cost and seeds the
  average, **exactly once per product**; a second attempt for an already-valued product is **rejected**
  (FR-INV-06).
- **BR-INV-08 — GRNI nets to zero once a receipt is billed.** The **GRNI** clearing account is **credited at
  receipt** (CR GRNI) and **debited at bill-match** (DR GRNI); after a receipt is fully billed, **GRNI nets to
  zero** for that receipt (FR-INV-02/03). A persistent GRNI balance is **goods received not yet invoiced** (a
  real accrual to surface), not a leak; an unexplained imbalance after full billing is a defect.
- **BR-INV-09 — Adjustment revaluation at the current average; the average is unchanged.** A manual adjustment
  posts **DR Stock-Adjustment/Shrinkage / CR `1300 Inventory`** (decrease) or the reverse (increase) at adjusted
  qty × the current average; the **moving average is not changed** by an adjustment (FR-INV-08).
- **BR-INV-10 — Per-company isolation.** Every average cost, on-hand value, opening valuation, costed posting,
  and valuation-report figure **belongs to exactly one company**; no read or figure crosses company scope.
  Cross-company valuation leakage is a **release blocker** (NFR-INV-01), as for GL/AR/AP/Cash/VAT.
- **BR-INV-11 — Base-currency valuation (v1).** Average costs, inventory value, COGS, GRNI, and the adjustment
  legs are in the company **base currency** (TZS in practice; ADR-0005 / gl.md BR-GL-06); foreign-currency
  inventory valuation / FX on cost is deferred (§2, OQ-INV-08).
- **BR-INV-12 — Every costed posting obeys the GL invariants.** Each inventory / COGS / GRNI / adjustment entry
  posts to an **OPEN** fiscal period, to **active** accounts, **balanced** (gl.md BR-GL-01/03/04); a posting
  into a CLOSED period is handled per the GL closed-period policy (gl.md OQ-GL-01); a **missing required
  `gl_config`** mapping (`INVENTORY`, `COGS`, the new `GRNI`, the new `STOCK_ADJUSTMENT`, or `OPENING_BALANCE_
  EQUITY` for the opening valuation) **fails the operation** rather than mis-posting (gl.md BR-GL-10).

## 7. Process flows (happy path + main unhappy paths), ratified v1

### 7.1 Receive → average recompute + DR Inventory / CR GRNI — happy path
1. Goods are received against a goods receipt (Purchases — STOCK.RECEIVED emitted with the receipt lines; the
   receipt unit cost on `goods_receipt_lines.unit_cost_amount`, V8).
2. The stock receipt handler (system, under the event's company/branch scope) **recomputes the product's
   moving-average cost** from the receipt unit cost (FR-INV-01, BR-INV-01) and updates on-hand qty + value.
3. The handler **posts a synchronous GL entry DR `1300 Inventory` / CR GRNI** at receipt qty × unit cost
   (FR-INV-02, BR-INV-02), in the same transaction (idempotent under the STOCK.RECEIVED guard, NFR-INV-04).
4. Inventory is now an asset at cost; GRNI carries "received not invoiced." The posting is **audited**
   (NFR-INV-03).

### 7.2 Bill the receipt → clear GRNI (DR GRNI / CR AP) — happy path
1. The supplier bill arrives and is **matched** (AP 3-way match — accounts-payable.md FR-AP-06).
2. For a **stock/goods** bill, the bill-match posts **DR GRNI / CR `2100 AP`** (the VAT_INPUT leg unchanged) —
   **clearing** the GRNI accrued at receipt (FR-INV-03, BR-INV-08) instead of the shipped DR `5150 Purchases`.
3. After receipt + bill, **GRNI nets to zero** for the receipt; the goods sit on `1300 Inventory`, the payable
   on `2100 AP` (BR-INV-08). (A **service** bill with no receipt **retains** DR `5150 Purchases` — OQ-INV-04.)

### 7.3 Sell → COGS at the current average (incl. recipe explosion) — happy path
1. A sale is finalised (Sales — SALE.FINALISED; the sales auto-poster posts revenue + VAT to GL, gl.md §3.1).
2. The sale-issue handler **deducts quantity** (shipped) **and posts a synchronous GL entry DR `5100 COGS` / CR
   `1300 Inventory`** at issued qty × the **current average** (FR-INV-04, BR-INV-04); a **composed** product
   posts COGS for **each stockable component at its own** average (recipe explosion, ADR-0010 D-8).
3. The P&L now carries cost of sales matched to the revenue; **gross margin = revenue − COGS** is visible;
   inventory on `1300` falls by the cost issued. The posting is **audited** (NFR-INV-03).

### 7.4 Stock adjustment → revaluation — happy path
1. A stock controller records a manual adjustment (`STOCK.ADJUST`, a mandatory `AdjustmentReason` — e.g.
   SHRINKAGE).
2. A **decrease** posts **DR Stock-Adjustment/Shrinkage / CR `1300 Inventory`** at qty × current average; an
   **increase** posts the reverse (FR-INV-08, BR-INV-09); the **average is unchanged**.
3. Shrinkage / write-off value lands in the Stock-Adjustment / Shrinkage P&L account; the posting is **audited**.

### 7.5 Opening inventory valuation — happy path
1. At go-live, an accountant (`INVENTORY.OPENING.SET`) values existing quantity-only on-hand per product: an
   opening unit cost.
2. The system **seeds the product's average** and **posts DR `1300 Inventory` / CR `3100 Opening-Balance-
   Equity`** at on-hand qty × opening cost (FR-INV-06, BR-INV-07) — **once per product**.
3. The valuation report's total now equals the seeded `1300 Inventory` balance (BR-INV-06); the act is
   **audited**.

### 7.6 Main unhappy paths
- **An issue (sale / adjustment-out) at zero or negative on-hand** (no established cost) → the **costed issue
  is blocked** (recommended — OQ-INV-01) or valued at the **last-known average** (the flagged alternative)
  until a receipt sets a cost; the quantity rule (negative allowed — ADR-0010 BR-STOCK-03) is unchanged, but
  the **cost** rule is the open default (OQ-INV-01).
- **A goods receipt at zero unit cost** → the average is dragged toward zero; v1 **accepts** it as a
  data-entry consequence and **surfaces** it for review (a zero-cost receipt is a likely data error, not a
  system error) — not silently rejected (BR-INV-01).
- **A computation yields a negative average** → a **defect** (NFR-INV-02); the average must never go negative.
- **A second opening valuation for an already-valued product** → **rejected** (BR-INV-07); the value is set
  once — a later correction is a stock adjustment (revaluation, §3.8) or a GL reversing entry, not a re-open.
- **The valuation report total disagrees with the `1300 Inventory` GL balance** → a **finance-grade defect**
  surfaced for investigation (BR-INV-06, the recon bar).
- **A costed posting would hit a CLOSED GL period, an inactive account, or a missing `gl_config` mapping**
  (`INVENTORY` / `COGS` / `GRNI` / `STOCK_ADJUSTMENT` / `OPENING_BALANCE_EQUITY`) → handled per the GL
  invariants: closed-period per the GL policy (gl.md OQ-GL-01), the operation **fails rather than mis-posts**
  on a missing mapping (gl.md BR-GL-10, BR-INV-12).
- **A persistent GRNI balance** → **goods received not yet invoiced** — a real accrual to surface and chase
  (the unbilled receipts), **not** a leak; an unexplained GRNI imbalance after full billing is a defect
  (BR-INV-08).

## 8. Non-functional

- **NFR-INV-01 — Reconciliation integrity & tenant isolation.** The valuation report must reconcile to the
  books — **Σ (on-hand qty × moving-average cost) == the `1300 Inventory` GL balance** (BR-INV-06); a
  disagreement is a **release blocker**. Every average cost, on-hand value, and costed posting is scoped by
  `company_id` through the tenant-predicate repository base (ARCHITECTURE.md §5, PROJECT-CONVENTIONS §3.2);
  `assertCanActIn` guards **every read path**. Cross-company valuation leakage is a **release blocker**, as for
  GL/AR/AP/Cash/VAT.
- **NFR-INV-02 — Money & average-cost correctness.** Every amount is a `Money` (amount + currency, ADR-0005) in
  the company base currency; the average-cost recompute, the on-hand value, the inventory / COGS / GRNI /
  adjustment legs, and the valuation-report sum compute **exactly** (no float, `BigDecimal`, rounding HALF_UP
  per ADR-0005 / gl.md NFR-GL-02 / OQ-CUR-03). The **average cost** may be held at a **higher internal
  precision** than the 0-dp base-currency display and **rounded HALF_UP on posting** (OQ-INV-06); the **average
  must never go negative**; an unbalanced costed entry, or a valuation that does not tie to GL, is a defect.
- **NFR-INV-03 — Audit.** Every **mutation** — the **opening valuation**, each **adjustment revaluation**, and
  each **costed receipt / sale / reversal posting** — is written to the IAM append-only audit trail with actor
  (the operator, or SYSTEM for the event-driven receipt/sale postings), action, target, timestamp, and company
  context (mirrors NFR-GL-06 / NFR-VAT-03). The average-cost recompute on receipt is traceable to its movement.
- **NFR-INV-04 — Idempotent handlers & synchronous-posting atomicity.** The receipt average-recompute + the DR
  Inventory / CR GRNI posting, and the sale −quantity + the DR COGS / CR Inventory posting, commit in **one
  transaction** with the stock movement (no eventual-consistency gap) and are **idempotent** under the existing
  STOCK.RECEIVED / SALE.FINALISED idempotency guards (ADR-0009 / ADR-0010 D-6) — a re-delivered event neither
  double-recomputes the average nor double-posts the cost.
- **NFR-INV-05 — Concurrency on the moving-average recompute.** Two **racing receipts** for the same product
  must serialise their average recompute so neither is lost — reuse the `stock_on_hand` optimistic `@Version`
  guard (ADR-0010 NFR-STOCK-04); a conflicting recompute retries, never silently overwrites. The architect
  confirms the retry/lock shape (ADR-0020).
- **NFR-INV-06 — Valuation-aggregate performance.** The valuation report aggregates per-product qty × average
  across the catalogue; it must paginate / aggregate efficiently and not load entities row-by-row (pagination
  everywhere — PROJECT-CONVENTIONS); the GL-balance side of the recon reads the `1300` balance, not a row scan.
- **NFR-INV-07 — DTO-only consumption / boundary direction.** The valuation slice reads `goods_receipt_lines`
  (the receipt unit cost), Sales (the sale lines), and the product/recipe data as **DTOs / scalar-id
  projections**, never importing a Purchases / Sales / Products entity (ADR-0009; `ModuleBoundaryTest`); it
  posts to GL through the `GLPostingService` / `GLConfigResolver` boundary. The **GRNI-clear** change keeps the
  direction AP→GL (AP's own posting method changes; the valuation slice does not reach into AP entities).
- **NFR-INV-08 — Forward-compatibility.** The v1 moving-average, single-location, base-currency model must not
  preclude the later increments that build on it: **multi-location / per-location cost** (OQ-INV-07);
  **FIFO / standard cost** (a method dimension); **landed cost** (apportioning freight/duty into the unit cost);
  **stock counts / cycle counts** (variance posting reusing the adjustment-revaluation machinery, OQ-STOCK-07);
  **batch / serial costing**; **inter-branch-transfer valuation** (the reserved TRANSFER_* types, OQ-STOCK-08);
  **manufacturing WIP costing** (Phase C, building on the component averages); and **multi-currency inventory**
  (OQ-INV-08). Building these is deferred; precluding them is a defect.

## 9. Assumptions

- The dependency platform exists and is consumed as designed: **Stock** (ADR-0010 / V7) ships
  `stock_movements` + `stock_on_hand` (**quantity-only**, optimistic `@Version`), the single
  `StockPostingService.post(...)` primitive, the four event handlers (`GoodsReceiptStockHandler`,
  `SaleIssueStockHandler` with recipe explosion, `SaleReversalStockHandler`, `GoodsReceiptReversalStockHandler`),
  and the `AdjustmentReason` enum (SHRINKAGE / DAMAGE / EXPIRY / COUNT_CORRECTION / RECEIPT_CORRECTION / OTHER);
  **Purchases** (ADR-0011 / V8) ships `goods_receipt_lines.unit_cost_amount` + `line_cost_amount` (**the cost
  input at receipt**) and emits STOCK.RECEIVED; **AP** (ADR-0015 / V12) ships
  `BillMatchServiceImpl.postMatchedBillToGl` posting DR `5150 Purchases` [+ DR VAT_INPUT, ADR-0017 D-7] / CR
  `2100 AP`; **Sales** (ADR-0008 / V5) emits SALE.FINALISED / SALE.VOIDED; **GL** (ADR-0013 / V10) ships the
  synchronous `GLPostingService.post`, `fiscal_periods`, `GLConfigResolver`, and `GlConfigKey` with **INVENTORY
  (1300) + COGS (5100) defined but UNUSED**, PURCHASES (5150), ACCOUNTS_PAYABLE (2100), OPENING_BALANCE_EQUITY
  (3100); **Money** (ADR-0005) + the idempotent transactional outbox are in place. All shipped.
- **The receipt unit cost exists but does NOT reach the stock event path yet.** `goods_receipt_lines.unit_cost_
  amount` carries the cost (V8), but `StockReceivedPayload.LineItem` carries **`{ productId, productUid, unitId,
  qtyInBase }` and no unit cost** — the **cost-into-event seam** (OQ-INV-05, §1 flag). ADR-0020 carries the
  cost to the handler (extend the payload — recommended — or read the GR line cost by uid as a DTO). The
  requirement fixes the *behaviour* (average recomputes at the receipt cost; inventory posts at that cost), not
  the carriage mechanism.
- **INVENTORY (1300) + COGS (5100) are defined and mapped but never posted-to** — this slice **finally posts**
  to them. The **GRNI** clearing liability + the **Stock-Adjustment / Shrinkage** expense accounts (+ their
  `gl_config` keys) **do not exist yet** — ADR-0020 adds them (additive, as ADR-0013 D-13 anticipated). The
  requirement fixes the *behaviour*; the account codes / keys are the architect's.
- **The shipped AP bill-match posts to `5150 Purchases` (periodic)** — this slice **swaps** the goods debit to
  a **GRNI clear** for stock bills (the ADR-0017 D-7 VAT_INPUT-swap precedent), retaining `5150` for non-stock /
  service bills (OQ-INV-04).
- **Single location, base currency (TZS), moving average** for v1 — the model supports later multi-location,
  multi-currency, and method choice but those depths are deferred (BR-INV-11, NFR-INV-08).

## 10. ACCEPTED RISK & accepted scope boundary — what Inventory Valuation v1 deliberately does NOT do (owner-accepted 2026-06-10)

> **Read this before building or consuming the valuation slice.** v1 delivers a **moving weighted-average**
> cost per product, **perpetual** inventory via a **GRNI** bridge (receipt → DR Inventory / CR GRNI; bill → DR
> GRNI / CR AP), **COGS at the average on every sale** (incl. recipe explosion + reversals), **opening
> inventory valuation**, a **stock valuation report** that reconciles to the `1300 Inventory` GL balance, and
> **stock-adjustment revaluation**. The following are **deliberate boundaries**, owner-accepted.

1. **Moving weighted average only — no FIFO / standard cost.** v1 is one running average per product. FIFO
   (cost layers), standard cost (planned cost + variances), and a per-product method choice are deferred. The
   model does not preclude a method dimension later (NFR-INV-08).
2. **Single location — one average per company-product.** v1 has no per-location / per-warehouse cost and no
   inter-location transfer valuation (the TRANSFER_* movement types stay reserved). Multi-location costing is
   deferred (OQ-INV-07).
3. **No landed cost in v1.** A receipt is valued at the supplier unit cost (`goods_receipt_lines.unit_cost_
   amount`); apportioning freight / duty / insurance into the unit cost is deferred — it rides this foundation.
4. **Revaluation is via stock adjustment only.** v1 revalues on a manual adjustment (write-off / shrinkage /
   count correction). General mark-to-market / standard-cost revaluation and a formal stock-count variance
   process are deferred (the count variance will reuse this slice's adjustment machinery — OQ-STOCK-07).
5. **The negative-stock cost rule is a single recommended default.** v1 fixes one rule for issuing at
   zero/negative on-hand (block, or last-known average — OQ-INV-01); richer negative-stock cost policies are
   deferred.
6. **The GRNI account + the cost-into-event seam + the new keys are ADR decisions, not v1 gaps.** That GRNI /
   Stock-Adjustment accounts do not exist yet, and that the STOCK.RECEIVED payload carries no unit cost, are the
   **ADR-0020 integration seams** (OQ-INV-03/04/05) — the requirement fixes the behaviour; the architect chooses
   the accounts / keys / carriage / granularity. This is **not** an accepted *risk*; it is the next design step.

All are additive by design (NFR-INV-08); none is precluded by the v1 model.

## 11. Open questions — status after ratification (2026-06-10)

> The **Inventory-Valuation / COGS scoping forks** the owner answered (moving weighted average; perpetual via
> GRNI; opening valuation IN; valuation report + recon bar IN; stock-adjustment revaluation IN; the deferred
> list; the two new permissions) are **RESOLVED** (recorded in `docs/requirements/open-questions.md` under
> Inventory Valuation). **No ADR-0020-blocking open question remains.** What stays open is **detail with a
> recommended default** — and the architecturally meaty items (the GRNI granularity, the reversal cost policy,
> the negative-stock issue cost, the fate of `5150` for service bills, the cost-into-event seam, and the
> average precision) are **decisions ADR-0020 makes**, not requirements blockers (the *behaviour* is fixed).

### The ADR-0020 design seams (DECISIONS the architect makes — do NOT block the requirements)

- **OQ-INV-03 — GRNI granularity: per goods-receipt line vs per receipt.** GRNI is credited at receipt and
  debited at bill-match. Accrue/clear **per GR line** (so a partial bill clears the matched portion) or **per
  receipt** (a single clearing amount)? *Recommended default:* **per GR line** (a partial / line-level bill
  clears the right portion; aligns with the 3-way match's line granularity). *Decider:* architect (ADR-0020).
  *Blocks ADR-0020:* **NO** — it **is** the decision; both reconcile to BR-INV-08.
- **OQ-INV-04 — Fate of `5150 Purchases`: the GOODS-vs-SERVICE bill branch.** A stock/goods bill **clears
  GRNI**; a non-stock / service bill (no receipt) has no GRNI to clear. Does the service bill **retain** `DR
  5150 Purchases / CR AP`, and how is GOODS vs SERVICE decided (a `po_type` / bill flag / "has a linked GR")?
  *Recommended default:* a bill **with a linked goods receipt** clears GRNI; a bill **without** (service /
  expense) **retains** `5150 Purchases` (the dedicated service-purchasing GOODS/SERVICE split is a separate
  Procurement item — PATH-TO-FULL-ERP §3.4). *Decider:* architect (ADR-0020) confirms the branch predicate.
  *Blocks ADR-0020:* **NO** — `5150` is retained for service; the predicate is the design detail.
- **OQ-INV-05 — The cost-into-event seam.** `StockReceivedPayload.LineItem` carries no unit cost; the cost is
  on `goods_receipt_lines.unit_cost_amount` (V8). Carry it via **extending the STOCK.RECEIVED payload** (add a
  `unitCost` per line — recommended, the leanest additive shape, mirrors how the payload mirrors
  `SaleFinalisedPayload`) **or** have the receipt handler **read the GR line cost by uid** as a DTO? *Recommended
  default:* **extend the payload** with the per-line unit cost (one additive field; no extra read on the hot
  path). *Decider:* architect (ADR-0020). *Blocks ADR-0020:* **NO** — it **is** the seam decision; both deliver
  the cost.
- **OQ-INV-02 — Reversal cost: original issue/receipt cost vs the now-current average.** A sale void reverses
  COGS; at **what cost** — the **original** cost the sale issued at (recommended) or the **now-current** average?
  *Recommended default:* the **original issue/receipt cost** (symmetric reversal; no phantom gain/loss from
  average drift between the sale and the void; the slice records the cost on the original movement to reverse
  it). *Decider:* architect / owner (finance). *Blocks ADR-0020:* **NO** — original-cost is the default;
  current-average is the discouraged alternative.
- **OQ-INV-06 — Average-cost precision & storage.** To how many decimals is the running average held, and where
  does it (and the on-hand value) live? *Recommended default:* hold the **average at a higher internal scale**
  (e.g. 4–6 dp) to avoid cumulative rounding drift, **rounded HALF_UP to base-currency dp on posting**; store
  the average + on-hand value as **additive columns on `stock_on_hand`** (NFR-STOCK-06 anticipates cost
  columns). *Decider:* architect (ADR-0020) + owner (finance) on the display dp. *Blocks ADR-0020:* **NO** —
  HALF_UP + a higher internal scale is the default; confirm dp before go-live.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0020)

- **OQ-INV-01 — Issue cost at zero / negative on-hand (the negative-stock cost rule).** The quantity model
  **allows** negative on-hand (ADR-0010 BR-STOCK-03); but at **what cost** is an issue valued when there is no
  established average (on-hand zero, never received)? *Recommended default:* **block the costed issue** (the
  sale's COGS / the adjustment-out cost) until a receipt establishes a cost — **or**, the flagged alternative,
  value at the **last-known average** and true-up nothing (accept the imprecision). The richer
  deferred-cost-on-negative + true-up-on-next-receipt policy is out of scope (§2). *Decider:* owner (finance).
  *Blocks ADR-0020:* **NO** — a single rule (recommended block) stands; the alternative is a one-line config.
- **OQ-INV-07 — Multi-location valuation.** v1 is **single location** (one average per company-product).
  *Recommended default:* single-location v1; per-location cost (and inter-location transfer valuation) lands
  with Stock multi-location (PATH-TO-FULL-ERP §3.5). *Decider:* owner. *Blocks ADR-0020:* **NO** — deferred, not
  precluded (NFR-INV-08).
- **OQ-INV-08 — Multi-currency inventory valuation.** v1 is **base currency (TZS)** (BR-INV-11). *Recommended
  default:* base-currency cost in v1; foreign-currency cost / FX on the unit cost lands with FX
  (multicurrency.md / gl.md §10.5). *Decider:* owner. *Blocks ADR-0020:* **NO** — deferred, not precluded.
- **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** Confirm rounding mode (HALF_UP vs banker's) and TZS
  decimal places (0 in practice) — the average-cost rounding, the inventory / COGS / GRNI / adjustment legs, and
  the valuation-report sum must round identically (NFR-INV-02). *Recommended default:* HALF_UP, TZS = 0 dp
  display, a higher internal scale on the average (OQ-INV-06). *Decider:* owner (finance input). *Blocks
  ADR-0020:* **NO** for the model; **confirm before go-live**.

## 12. Out of scope for v1 (deferred — restated)

Multi-location / multi-warehouse valuation + inter-location transfer cost (OQ-INV-07 — v1 is single location);
**FIFO / LIFO / standard cost + per-product method choice** (v1 is moving weighted average); **landed cost**
(freight / duty / insurance into the unit cost — rides this foundation); **stock counts / cycle counts +
variance posting** (OQ-STOCK-07 — v1 revalues via manual adjustment; counts reuse this machinery later);
**batch / lot / serial costing**; **a negative-stock cost policy beyond the basic rule** (OQ-INV-01 — v1 fixes
one rule); **manufacturing WIP costing** (Phase C — builds on the component averages); **per-category / cost-
centre COGS** (Reporting/Budgeting dimension); **stock valuation / ageing / ABC analytics** (Reporting T2.3 —
v1 delivers the valuation report + recon bar they read); and **multi-currency inventory valuation / FX on cost**
(OQ-INV-08 — v1 is base currency). Each is tracked for a later increment; none is precluded by the v1 model
(NFR-INV-08).
</content>
