# Requirements — Stock (inventory on-hand & movement)

> Status: **Ratified (owner-confirmed 2026-06-07).** Built **together with Purchases** this round
> (owner ruling: stock-in is the real goods-receipt from a purchase, from day one). The owner has
> **ratified the four headline decisions** (below) **and confirmed every second-order choice**
> (OQ-STOCK-01..10, full log in [open-questions.md](open-questions.md)). **OQ-STOCK-09 is RESOLVED =
> YES: the transactional outbox is built this round under its own platform ADR**, at-least-once
> delivery + consumer-side idempotency (dedupe on event id). No ADR-blocking question remains; the
> solutions-architect may write the **platform-outbox ADR** and **ADR-0009 (Stock)** on this confirmed
> scope.
>
> Author: system-analyst · Domain: `stock` (operational / inventory). **Business-level spec only — no
> schema, no API shapes, no tables, no code.** Those are the solutions-architect's, in **ADR-0009**
> (next step) and the **platform-outbox ADR** (the cross-module mechanism Stock is the first consumer
> of). Do not infer a data model from this document.
>
> **Depends on:** IAM (org → company → branch, permissions, `RequestContext`, audit); Products
> (the catalogue — **stockable** flag, **base unit**, bulk-pack conversion FR-PROD-06, **single-level
> recipe** §9); Parties (Supplier — referenced by Purchases, the source of stock-in); Multicurrency
> (ADR-0005 — money = amount + currency; note Stock v1 is **quantity-only**, see §2/§10). Consumes the
> **transactional outbox** (ARCHITECTURE.md §9) as its cross-module link to Sales and Purchases.
>
> **Sibling module:** [purchases.md](purchases.md) — the goods-receipt that pushes stock **IN**.

## 1. Business context & why now

Sales shipped with an **explicit, owner-accepted risk**: a v1 sale records the quantity sold but
**deducts no stock** (sales.md §10, FR-SALES-21, BR-SALES-11). There is no Stock module, so on-hand is
not tracked anywhere and "a sale must update stock" is unmet. **This round closes that gap.** Stock is
the module that holds **how much of each stockable product is at each branch right now**, and the
**append-only history of every movement** that changed it.

Stock does not exist in isolation: an inventory only moves because something happened elsewhere — a
**purchase's goods were received in**, a **sale issued goods out**. The owner therefore ruled that
Stock and **Purchases are built together** (the sibling [purchases.md](purchases.md)): a **Goods
Receipt** (recorded against a Purchase Order — the owner-confirmed two-document Purchases flow,
purchases.md OQ-PURCH-01) is the **real** stock-in from day one (not a synthetic seed), and a finalised
sale is the real stock-out. The two cross-module effects — **Purchases → Stock IN** (from the Goods
Receipt) and **Sales → Stock OUT** — both fire through the **transactional outbox** (ARCHITECTURE.md
§9, never an in-memory event), and **Stock is the first consumer the outbox has ever had**. Building
the outbox as platform infrastructure is therefore part of this round, **RESOLVED** as its own ADR
(see §3.4 and OQ-STOCK-09).

Everything Stock needs already exists:

- **What is stockable** — Products gives the **stockable** flag (FR-PROD-04, BR-PROD-02): only a
  stockable product has on-hand. Services and non-stockable goods never have a level.
- **The unit on-hand is held in** — Products gives a **base unit** per stockable product (FR-PROD-05);
  on-hand and every movement are expressed in **base units**, bulk packs converting per FR-PROD-06.
- **The recipe to explode** — Products gives the **single-level composition** (FR-PROD-14..17, §9):
  selling a composed product deducts its **components'** on-hand, not the composed product itself.
- **The events to consume** — Sales reserved the **`SALE.FINALISED` / `SALE.VOIDED`** outbox contract
  (ADR-0008 D-9): payload `{ invoiceUid, companyId, branchId, finalisedAt, lines:[{ productId,
  productUid, unitId, qtyInBase }] }` — the per-line `productId` + `qtyInBase` are exactly what Stock
  needs to deduct on-hand, with no back-computation. Purchases mirrors this with a `STOCK.RECEIVED`
  event (purchases.md, this doc §5).

### Vocabulary distinction (read this first)

- **On-hand (stock-on-hand)** — the current quantity of a **stockable** product **at a branch**, in
  the product's **base unit**. The level Stock owns. A non-stockable product never has on-hand.
- **Stock movement** — one recorded change to on-hand: a row in an **append-only movement ledger**
  with a **type**, a **signed quantity** (in base units), the product, the branch, a timestamp, the
  actor, and a **reference** to what caused it (a purchase receipt, a sale, an adjustment). On-hand is
  the running consequence of movements; a movement is never edited or deleted (corrected by a
  compensating movement, never in place).
- **Movement type** — *why* the movement happened: `GOODS_RECEIPT` (in, from a purchase **Goods
  Receipt** recorded against a PO), `SALE_ISSUE` (out, from a finalised sale), `SALE_REVERSAL` (in,
  compensating a voided sale),
  `ADJUSTMENT` (manual ±, reasoned), `OPENING_BALANCE` (initial seed of a never-before-tracked
  product at a branch). `TRANSFER_OUT` / `TRANSFER_IN` (branch-to-branch) are **recognised but
  deferred** in v1 (§2, OQ-STOCK-08).
- **Base unit** — Products' smallest counting unit for a stockable product (piece, kg, litre);
  **on-hand and all movements are in base units** (FR-PROD-05). A bulk-pack quantity (crate, carton)
  converts to base before it touches stock (FR-PROD-06).
- **Negative on-hand** — on-hand below zero. **Valid in v1** (owner ruling: overselling is allowed,
  not blocked); it is **flagged/surfaced**, never prevented. It means more was issued than was
  recorded as received — a data-accuracy signal, not a hard error.
- **Recipe explosion** — when a **composed product** (Products §9) is sold, deducting its **component
  products'** on-hand per the recipe quantities, rather than deducting the composed product itself.
  Single-level (Products is single-level, FR-PROD-16).
- **Idempotency** — processing the same outbox event twice produces the **same** on-hand (no
  double-deduction). A redelivered `SALE.FINALISED` must not deduct stock again.
- **Quantity-only valuation** — v1 tracks **quantity** per (product, branch) and movement history;
  it does **NOT** compute stock value, FIFO/weighted-average cost, or COGS (owner ruling; deferred to
  a Finance-aware round, §10).

## 2. Scope

> Every line below tagged **[RATIFIED]** is an owner-confirmed headline decision (2026-06-07); lines
> tagged **[default]** were the recommended default this doc adopted and are **now also owner-confirmed**
> (OQ-STOCK-01..10 all RESOLVED — §11). The `[OQ-STOCK-NN]` references remain only as cross-links to the
> resolved log entry, not as open questions.

### In scope (v1 — "track on-hand per branch and the movements that change it")

- **[RATIFIED] On-hand quantity per (stockable product, branch), in base units.** A maintained
  current level for every stockable product at every branch it is held at. **Non-stockable products
  and services have no on-hand** (BR-STOCK-02, Products BR-PROD-02). **[default]** On-hand is a
  **maintained row** updated by each movement, **plus** an append-only movement ledger that can
  reconstruct it (OQ-STOCK-01).
- **[RATIFIED] Negative on-hand is allowed and flagged, never blocked.** A sale may drive on-hand
  below zero; the system records the issue, **flags** the negative level, and does **not** prevent the
  sale (overselling allowed, FR-STOCK-04, BR-STOCK-03). Sales remains stock-agnostic (it does not
  wait on a stock check — sales.md FR-SALES-21).
- **[default] An append-only stock movement ledger.** Every change to on-hand is one immutable
  movement row carrying type, signed base-unit quantity, product, branch, timestamp, actor, and a
  reference to its cause. Movement types in v1: **GOODS_RECEIPT, SALE_ISSUE, SALE_REVERSAL,
  ADJUSTMENT, OPENING_BALANCE** (FR-STOCK-05). Movements are never edited/deleted; a mistake is
  corrected by a compensating movement (BR-STOCK-06).
- **[RATIFIED] Stock-in is the real goods-receipt from a purchase.** `GOODS_RECEIPT` movements are
  driven by the **Purchases Goods Receipt** (recorded against a Purchase Order — purchases.md), consumed
  from a `STOCK.RECEIVED` outbox event, **from day one** (not a synthetic stub). (FR-STOCK-06.)
- **[default] Stock-out is the finalised sale.** `SALE_ISSUE` movements are driven by Sales finalise,
  consumed from the **`SALE.FINALISED`** outbox event (ADR-0008 D-9). A **void** emits `SALE.VOIDED`
  → a **`SALE_REVERSAL`** compensating in-movement (FR-STOCK-07, FR-STOCK-12).
- **[RATIFIED] Recipe explosion on sale-issue.** Selling a **composed product** deducts its
  **component products'** on-hand per the single-level recipe (Products §9), **not** the composed
  product itself. **[default]** the explosion happens in the **Stock consumer** using the Products
  recipe at consume-time (FR-STOCK-08). A component that is itself **non-stockable** is **skipped**
  (no on-hand to deduct) and the skip is recorded — see OQ-STOCK-03.
- **[default] Manual stock adjustment (±), reasoned and permissioned.** An authorised user records a
  positive or negative `ADJUSTMENT` against a (product, branch) with a **mandatory reason** (e.g.
  count correction, damage, shrinkage) (FR-STOCK-09, BR-STOCK-05). The set of reason codes is
  OQ-STOCK-04.
- **[default] Opening balance seed.** A never-before-tracked stockable product at a branch can be
  given an initial on-hand via an `OPENING_BALANCE` movement (FR-STOCK-10). Whether this is operator
  data-entry, an import, or both is OQ-STOCK-05.
- **[default] View on-hand and movement history.** List/search current on-hand per branch (with a
  **negative/low flag**); drill into the movement ledger for a product at a branch (FR-STOCK-11).
  A **low-stock threshold/reorder level** indicator is OQ-STOCK-06 (recommended: a simple optional
  per-(product, branch) reorder level, indicator-only, no auto-reorder).
- **[default] Idempotent event consumption.** Each outbox event is processed **exactly once** for its
  stock effect; redelivery does not double-move stock (FR-STOCK-13, NFR-STOCK-03).
- **[default] Per-company + multi-branch scope, permission-gated, audited** — consistent with every
  prior module (FR-STOCK-14/15, NFR-STOCK-01/05).
- **[RATIFIED] Quantities only — NO valuation in v1.** On-hand quantity + movement history only. **No
  FIFO / weighted-average / standard cost, no stock value, no COGS** (FR-STOCK-16, §10). Purchase cost
  is recorded on the **purchase document** (money) but is **not** rolled into a stock value
  (purchases.md, multicurrency.md §5).

### Deferred (recognised, NOT built in v1)

- **All stock valuation / costing** — FIFO, weighted-average, standard cost; stock value reports; COGS
  on a sale; cost roll-up for a composed product from its components. **Deferred to a Finance-aware
  round** (owner ruling; §10). Stock v1 is **quantity-only**.
- **Branch-to-branch transfers** (`TRANSFER_OUT` / `TRANSFER_IN`, in-transit stock, a transfer
  document with despatch/receipt legs) — **recognised but deferred** (OQ-STOCK-08). v1 moves stock in
  (purchase), out (sale), and ± (adjustment) only; inter-branch movement waits for its own round.
- **Stock counts / cycle counting / stocktake worksheets** — a formal physical-count workflow that
  reconciles counted vs system and posts variance adjustments. v1 has the manual `ADJUSTMENT` only;
  a structured count process is deferred (OQ-STOCK-07).
- **Batch / lot / serial / expiry tracking** — a Products/Stock concern flagged in Products §2;
  deferred. v1 on-hand is a single quantity per (product, branch) with no batch dimension.
- **Reservations / allocations / available-to-promise** — holding stock against an unfinalised order
  (tied to the deferred Sales-Order channel). v1 has on-hand only, no reserved quantity.
- **Reorder automation / purchase suggestions** — auto-raising a purchase when on-hand hits a reorder
  level. v1's reorder level (if adopted, OQ-STOCK-06) is **indicator-only**.
- **Multi-level / nested recipe explosion** — Products is single-level (FR-PROD-16); nested BOM
  explosion is deferred with it.
- **Negative-stock blocking / hard stock reservations on sale** — explicitly **NOT** built: the owner
  ruled overselling is allowed (FR-STOCK-04). A future config to *optionally* block by product is not
  precluded but not built (OQ-STOCK-02).

### Explicitly NOT this module

- **The product definition itself** (stockable flag, base unit, recipe) — owned by **Products**;
  Stock consumes its DTOs. Stock holds **levels and movements**, never the catalogue.
- **Buying from suppliers** — the **Purchases** module (sibling). Purchases *produces* the
  `STOCK.RECEIVED` event; Stock *consumes* it and posts the `GOODS_RECEIPT` movement.
- **Selling to customers** — the **Sales** module. Sales *produces* `SALE.FINALISED` / `SALE.VOIDED`;
  Stock *consumes* them.
- **Stock value / COGS / inventory in the general ledger** — the future **Finance** module, with a
  valuation method. Stock v1 produces **quantity** facts; Finance will later derive value from them.
- **The transactional outbox mechanism** itself — that is **platform infrastructure** (ARCHITECTURE.md
  §9), built this round under its **own ADR** (§3.4, OQ-STOCK-09). Stock is its first **consumer**,
  not its owner.

## 3. Key concepts

### 3.1 On-hand and the movement ledger (the two together)

The recommended model keeps **both**:

- a **maintained on-hand level** per (stockable product, branch) — the fast, authoritative "how much
  is there now", updated transactionally by every movement; and
- an **append-only movement ledger** — every change as an immutable row, from which on-hand is
  reconstructable and auditable.

This mirrors the discipline elsewhere in the system: the ledger is the **truth of what happened**
(like the IAM audit trail — immutable, append-only), and the maintained level is the **fast read**
that the ledger backs. The architect decides the consistency mechanism (the on-hand row is updated in
the **same transaction** that appends the movement); the **requirement** is that on-hand always equals
the sum of its movements (BR-STOCK-01) and that no movement is ever mutated in place (BR-STOCK-06).
Pure-derived (recompute on read) vs maintained-row was OQ-STOCK-01, **RESOLVED (owner) = the
maintained row + ledger**.

### 3.2 Movement types (v1)

| Type | Direction | Source | Notes |
|---|---|---|---|
| **GOODS_RECEIPT** | IN (+) | Purchases **Goods Receipt** against a PO (`STOCK.RECEIVED` event) | The real stock-in from day one (RATIFIED). Quantity in base units; partial receipts each emit their own event. |
| **SALE_ISSUE** | OUT (−) | Sales finalise (`SALE.FINALISED` event) | May drive on-hand negative (allowed + flagged). Composed product → explodes to component issues (RATIFIED). |
| **SALE_REVERSAL** | IN (+) | Sales void (`SALE.VOIDED` event) | Compensating in-movement reversing a prior `SALE_ISSUE` (and its component issues). |
| **ADJUSTMENT** | ± | Manual, permissioned, reasoned | Count correction / damage / shrinkage. Mandatory reason (OQ-STOCK-04). |
| **OPENING_BALANCE** | IN (+) | Manual seed / import | Initial on-hand for a never-tracked (product, branch) (OQ-STOCK-05). |
| **TRANSFER_OUT / TRANSFER_IN** | OUT / IN | Branch-to-branch transfer | **DEFERRED** (OQ-STOCK-08). Named so the type vocabulary is reserved. |

### 3.3 Recipe explosion (composed products, single-level)

When `SALE.FINALISED` carries a line for a **composed** product (Products FR-PROD-14), Stock does
**not** deduct the composed product (it has no meaningful on-hand of its own — e.g. a restaurant dish
is a service). Instead the Stock consumer reads the composed product's **single-level recipe** from
Products and posts a `SALE_ISSUE` movement for **each component product**, quantity = `qtyInBase` of
the composed line × the recipe component quantity, in the component's base unit (FR-STOCK-08). This is
the deferred Products §9 "component deduction" — **now due**. A component that is **non-stockable** is
**skipped** (nothing to deduct), and the skip is recorded for traceability (OQ-STOCK-03). Because
Products is single-level (FR-PROD-16), no recursion arises in v1.

### 3.4 The transactional outbox (platform infrastructure — built this round, own ADR)

Stock's whole cross-module link is the **transactional outbox** (ARCHITECTURE.md §9): the producing
module writes a `domain_event` row in the **same transaction** as its business change (a sale
finalising, a Goods Receipt receiving); a **poller/dispatcher** later delivers it to consumers;
**never** an in-memory `ApplicationEventPublisher` (which loses events on crash). **This table and
dispatcher do not exist yet** (ADR-0008 D-9 reserved but did not build them; ARCHITECTURE.md §9 names
them as "later modules"). Because Stock is the **first consumer**, the owner **RESOLVED (OQ-STOCK-09 =
YES)** that the outbox is **built this round** as **platform infrastructure under its own ADR** (not
folded into the Stock or Purchases data-model ADR), with **at-least-once delivery + consumer-side
idempotency (dedupe on event id)**, **Sales wired to actually emit** `SALE.FINALISED` / `SALE.VOIDED`
(closing the ADR-0008 D-9 seam), and **Purchases' Goods Receipt emitting** `STOCK.RECEIVED`.

This document specifies the outbox only at the **requirements level** (what events flow, the
delivery/idempotency guarantee); the **mechanism** (table shape, poller, retry, dispatch) is the
architect's, in the **platform-outbox ADR** (OQ-STOCK-09 RESOLVED). The events Stock consumes:

- **`SALE.FINALISED`** (from Sales, contract fixed in ADR-0008 D-9) → `SALE_ISSUE` movements (with
  recipe explosion).
- **`SALE.VOIDED`** (from Sales) → `SALE_REVERSAL` compensating movements.
- **`STOCK.RECEIVED`** (from Purchases' **Goods Receipt** against a PO, this round) → `GOODS_RECEIPT`
  movements. (Payload defined in purchases.md.)

## 4. Actors / personas

- **Stock controller / inventory officer (branch operator)** — views on-hand for their active branch,
  records manual adjustments (with reason), seeds opening balances, investigates negative/flagged
  levels. Holds `STOCK.VIEW` and `STOCK.ADJUST`-style permissions.
- **Branch manager / supervisor** — reviews movement history, approves/owns adjustments beyond a
  threshold (if a threshold is adopted, OQ-STOCK-04), monitors negative-stock flags.
- **System (event consumer)** — the non-human actor: the outbox dispatcher delivering
  `SALE.FINALISED` / `SALE.VOIDED` / `STOCK.RECEIVED` to the Stock consumer, which posts the
  corresponding movements. Acts under the originating event's company/branch context, not a logged-in
  user; the movement records the originating document + (where present) the originating operator.
- **Finance / accounts user** — will later derive stock **value** and **COGS** from Stock's quantity
  facts (deferred, §10). Named here for the valuation handoff.

## 5. Functional requirements

> IDs are `FR-STOCK-NN`. Each is a crisp, testable statement. All values are **owner-confirmed**
> (OQ-STOCK-01..10 RESOLVED, §11); the `[OQ-STOCK-NN]` references point to the resolved log entry.

### On-hand model

- **FR-STOCK-01** The system maintains **on-hand quantity per (stockable product, branch)**, expressed
  in the product's **base unit** (Products FR-PROD-05). On-hand reflects every posted movement for
  that product at that branch (BR-STOCK-01).
- **FR-STOCK-02** **Only a stockable product has on-hand.** A non-stockable product or a service
  (Products BR-PROD-01/02) **never** has an on-hand level and is never the subject of a stock movement
  (BR-STOCK-02).
- **FR-STOCK-03** **[default]** On-hand is a **maintained current level** updated transactionally by
  each movement, **and** the **append-only movement ledger** can reconstruct it; on-hand always equals
  the signed sum of its movements (BR-STOCK-01). `[OQ-STOCK-01]`
- **FR-STOCK-04** **On-hand may be negative.** Issuing more than is on-hand is **allowed**, drives
  on-hand below zero, and the negative level is **flagged/surfaced** — it is **never blocked** (owner
  ruling: overselling allowed). A movement that takes on-hand negative still posts successfully
  (BR-STOCK-03). `[OQ-STOCK-02 — optional per-product block, NOT built in v1]`

### Movement ledger

- **FR-STOCK-05** The system records every change to on-hand as an **immutable stock movement** in an
  **append-only ledger**, carrying: company + branch scope, product, **movement type**
  (GOODS_RECEIPT | SALE_ISSUE | SALE_REVERSAL | ADJUSTMENT | OPENING_BALANCE), a **signed quantity in
  base units**, a timestamp, the **actor** (the operator or the system event consumer), and a
  **reference** to the cause (the source document / event). Movements are never edited or deleted
  (BR-STOCK-06).
- **FR-STOCK-06** A **GOODS_RECEIPT** in-movement is posted when a Purchases **Goods Receipt** (recorded
  against a PO) is finalised — consumed from the **`STOCK.RECEIVED`** outbox event (purchases.md
  FR-PURCH-08), per line `productId` + `qtyInBase`. This is the **real** stock-in from day one (owner
  ruling); a **partial receipt** emits its own event and posts its own movement (FR-STOCK-13
  idempotency applies).
- **FR-STOCK-07** A **SALE_ISSUE** out-movement is posted when a sale is finalised — consumed from the
  **`SALE.FINALISED`** outbox event (ADR-0008 D-9), per line `productId` + `qtyInBase`. (See
  FR-STOCK-08 for composed products.)
- **FR-STOCK-08** **Recipe explosion on SALE_ISSUE.** When a `SALE.FINALISED` line names a **composed**
  product (Products FR-PROD-14), the Stock consumer reads the product's **single-level recipe** from
  Products and posts a `SALE_ISSUE` movement for **each component product** (qty = line `qtyInBase` ×
  recipe component qty, in the component's base unit), **not** for the composed product itself. A
  component that is **non-stockable** is **skipped** and the skip recorded (BR-STOCK-04).
  `[OQ-STOCK-03]`
- **FR-STOCK-09** A permissioned user records a manual **ADJUSTMENT** (±) against a (product, branch)
  with a **mandatory reason** (BR-STOCK-05); the adjustment posts a signed movement and updates
  on-hand. Reason codes are owner-defined. `[OQ-STOCK-04]`
- **FR-STOCK-10** A never-before-tracked stockable product at a branch can be seeded with an initial
  on-hand via an **OPENING_BALANCE** in-movement (FR-STOCK-05). Entry method (manual / import) is
  `[OQ-STOCK-05]`.

### Compensation & corrections

- **FR-STOCK-12** A **sale void** posts a **SALE_REVERSAL** compensating in-movement — consumed from
  the **`SALE.VOIDED`** outbox event — that reverses the prior `SALE_ISSUE`(s) for that sale (including
  the component issues of a composed product). On-hand returns to its pre-issue level (net of any other
  movements since). A movement is **never deleted** to undo it (BR-STOCK-06).

### Idempotency, scope, permissions, viewing

- **FR-STOCK-13** **Idempotent consumption.** Each outbox event drives its stock effect **exactly
  once**: a redelivered `SALE.FINALISED` / `SALE.VOIDED` / `STOCK.RECEIVED` must **not** post the
  movement (and change on-hand) a second time (NFR-STOCK-03). The mechanism (dedup key / processed-event
  marker) is the architect's; the **requirement** is no double-movement on redelivery.
- **FR-STOCK-14** Stock on-hand and movements are **scoped per company and filtered by the active
  branch**: an operator sees and adjusts stock only for their active branch (mirrors Sales FR-SALES-24,
  Products FR-PROD-22). Cross-company/branch stock leakage is a release blocker (NFR-STOCK-01).
- **FR-STOCK-15** All stock operations are **gated by IAM permissions** (e.g. `STOCK.VIEW`,
  `STOCK.ADJUST`, `STOCK.OPENING` for opening balances; receipt/issue movements are posted by the
  system event consumer under the originating document's authority, not a direct user action). Exact
  codes are seeded with the module (FR-IAM-11). `[OQ-STOCK-04 covers adjustment approval]`
- **FR-STOCK-11** A user can **view current on-hand** for products at their active branch (with a
  **negative/low flag**) and **drill into the movement history** of a product at a branch
  (chronological ledger with type, signed qty, source reference, actor, timestamp). A simple
  **reorder-level** indicator is `[OQ-STOCK-06]`.

### Valuation (explicitly excluded in v1)

- **FR-STOCK-16** v1 Stock is **quantity-only**: it tracks on-hand quantity and movement history and
  **does NOT** compute stock value, unit cost (FIFO / weighted-average / standard), or COGS, and does
  **not** roll up a composed product's cost from components (owner ruling). Purchase cost is recorded
  on the purchase document (purchases.md) but is **not** carried into a stock value in v1. Valuation
  lands in a **Finance-aware round** (§10). The v1 model must not **preclude** later valuation
  (NFR-STOCK-06).

## 6. Business rules (invariants)

- **BR-STOCK-01** **On-hand equals the signed sum of its movements.** For any (stockable product,
  branch), the maintained on-hand level always reconciles to the sum of that product's posted
  movements at that branch. A divergence is a defect.
- **BR-STOCK-02** **Only a stockable product moves.** A non-stockable product or a service has no
  on-hand and can be the subject of no stock movement (Products BR-PROD-01/02). An event line naming a
  non-stockable product posts **no** stock effect for that line (it is skipped, recorded).
- **BR-STOCK-03** **Negative on-hand is valid.** A movement that would take on-hand below zero **still
  posts**; the resulting negative level is **flagged**, never prevented (owner ruling: overselling
  allowed). No stock check ever blocks a sale (sales.md FR-SALES-21).
- **BR-STOCK-04** **A composed product is exploded, not deducted.** A `SALE_ISSUE` for a composed
  product deducts its **component** products (single-level recipe, Products §9), never the composed
  product itself. A non-stockable component is skipped (recorded).
- **BR-STOCK-05** **A manual adjustment requires a reason and a permission.** No `ADJUSTMENT` movement
  exists without a recorded reason (BR-STOCK-05 reason set OQ-STOCK-04) and an authorised actor
  (`STOCK.ADJUST`). Adjustments are audited (NFR-STOCK-05).
- **BR-STOCK-06** **Movements are append-only.** A posted movement is never edited or deleted; an error
  is corrected by a **compensating movement** (a reversing `ADJUSTMENT` or, for a void, a
  `SALE_REVERSAL`), preserving the full history (mirrors the immutable audit trail).
- **BR-STOCK-07** **Stock belongs to exactly one company and one branch.** On-hand is per
  (company, branch, product); a movement carries the company + branch it occurred at and never crosses
  tenants (NFR-STOCK-01). Branch-to-branch movement is the deferred TRANSFER feature (OQ-STOCK-08).
- **BR-STOCK-08** **Each outbox event drives its stock effect exactly once.** Re-delivery of the same
  event is a no-op for on-hand (idempotency, FR-STOCK-13).
- **BR-STOCK-09** **Quantities are in base units.** Every on-hand level and movement quantity is in the
  product's base unit; a bulk-pack quantity is converted to base before it touches stock (Products
  FR-PROD-06). Fractional base-unit quantities follow the Products precision decision (OQ-PROD-07).
- **BR-STOCK-10** **No valuation in v1.** No stock figure carries a money value; on-hand is a pure
  quantity (owner ruling, FR-STOCK-16). No code, report, or downstream consumer may assume v1 Stock
  knows the value or cost of inventory.

## 7. Process flows

### 7.1 Goods received from a purchase (stock IN) — happy path

1. A **Goods Receipt** is finalised in **Purchases** against a Purchase Order (purchases.md §7.2; a
   partial receipt receives some of the ordered quantity); in the **same transaction** it writes a
   **`STOCK.RECEIVED`** outbox event (company, branch, lines of `productId` + `qtyInBase`).
2. The outbox dispatcher delivers `STOCK.RECEIVED` to the **Stock consumer**.
3. For each receipt line, Stock posts a **`GOODS_RECEIPT`** in-movement (signed +, base units) and
   **increments on-hand** for that (product, branch), in the **same transaction**.
4. Idempotency: if the event is redelivered, Stock recognises it as already processed and does
   **nothing** (FR-STOCK-13).
5. On-hand at the branch now reflects the receipt; the movement is visible in the product's ledger.

### 7.2 Sale finalised (stock OUT, with recipe explosion) — happy path

1. A sale is finalised in **Sales** (sales.md §7); in the **same transaction** it writes a
   **`SALE.FINALISED`** outbox event (ADR-0008 D-9 payload).
2. The dispatcher delivers `SALE.FINALISED` to the Stock consumer.
3. For each sale line:
   - if the product is **simple & stockable**, post a **`SALE_ISSUE`** out-movement (signed −,
     `qtyInBase`) and decrement on-hand;
   - if the product is **composed**, read its single-level recipe from Products and post a
     `SALE_ISSUE` for **each stockable component** (qty = `qtyInBase` × recipe qty); **skip**
     non-stockable components (recorded);
   - if the product is **non-stockable** (and not composed), post **no** movement (skipped, recorded).
4. If decrementing takes on-hand **negative**, the movement still posts and the negative level is
   **flagged** (FR-STOCK-04) — the sale is never blocked or reversed for this.
5. Idempotency as 7.1.4.

### 7.3 Sale voided (compensation) — happy path

1. A finalised sale is voided in Sales (sales.md FR-SALES-22); it writes a **`SALE.VOIDED`** outbox
   event.
2. The Stock consumer posts **`SALE_REVERSAL`** in-movements reversing the original `SALE_ISSUE`(s)
   for that sale (including the component issues of a composed product), restoring on-hand.
3. Idempotency: a redelivered `SALE.VOIDED` reverses only once (FR-STOCK-13).

### 7.4 Manual adjustment — happy path

1. An authorised user (`STOCK.ADJUST`) opens a (product, branch) at their active branch.
2. Enters a signed quantity (±) and a **mandatory reason** (count correction / damage / shrinkage —
   reason set OQ-STOCK-04).
3. Stock posts an **`ADJUSTMENT`** movement and updates on-hand; the action is **audited**.

### 7.5 Main unhappy paths

- **Event names a non-stockable product** (7.1/7.2) → that line posts **no** stock movement; the skip
  is recorded for traceability (BR-STOCK-02). Not an error.
- **Composed product with a non-stockable component** (7.2) → the non-stockable component is skipped;
  stockable components are still deducted (BR-STOCK-04, OQ-STOCK-03).
- **Issue exceeds on-hand** (7.2) → posts anyway; on-hand goes negative and is **flagged**; the sale is
  untouched (BR-STOCK-03). Not an error.
- **Event redelivered** (any) → idempotent no-op; on-hand unchanged (FR-STOCK-13, BR-STOCK-08).
- **`SALE.VOIDED` arrives for a sale Stock never issued** (e.g. ordering/edge) → the reversal is
  handled gracefully (no negative phantom movement); the architect defines the reconciliation
  (recommended: reverse only what was issued; record an anomaly if nothing matches). `[OQ-STOCK-10]`
- **Adjustment without a reason** (7.4) → rejected; a reason is mandatory (BR-STOCK-05).

## 8. Non-functional

- **NFR-STOCK-01** **Tenant isolation:** every on-hand level and movement is scoped by `company_id` +
  `branch_id` through the tenant-predicate repository base (ARCHITECTURE.md §5, PROJECT-CONVENTIONS
  §3.2). Cross-company/branch stock leakage is a **release blocker**, as for IAM/Sales.
- **NFR-STOCK-02** **On-hand correctness:** the maintained on-hand level must reconcile **exactly** to
  its movement ledger at all times (BR-STOCK-01); an integration test asserts on-hand == Σ movements
  after each operation. A divergence is an inventory-grade defect.
- **NFR-STOCK-03** **Exactly-once stock effect (idempotency):** consuming an outbox event posts its
  movement **once**; redelivery is a no-op (FR-STOCK-13, BR-STOCK-08). This is the single biggest
  correctness risk in event consumption; it is a release blocker if violated.
- **NFR-STOCK-04** **Concurrency:** concurrent movements against the same (product, branch) — e.g. two
  receipts, or a receipt and an issue — must serialise so on-hand stays consistent (no lost update).
  The mechanism (row lock / optimistic version) is the architect's; the requirement is a consistent
  on-hand under concurrency.
- **NFR-STOCK-05** **Audit:** manual operations (adjustment, opening balance) are written to the IAM
  append-only audit trail with actor, action, target (product/branch), quantity, reason, timestamp,
  and company/branch context (mirrors FR-SALES-NFR-03). Event-driven movements record their
  originating document/event as their reference (the movement ledger is itself an audit of stock).
- **NFR-STOCK-06** **Forward-compatibility for valuation:** the v1 quantity-only model must not
  **preclude** later (a) per-movement cost capture, (b) a valuation method (FIFO / weighted-average),
  (c) stock-value and COGS reporting. Building these is deferred (§10); precluding them is a defect
  (mirrors the multicurrency / Sales "do not preclude" stance).
- **NFR-STOCK-07** Timestamps are UTC, displayed per branch/company time zone (Africa/Dar_es_Salaam
  default, iam.md locale).
- **NFR-STOCK-08** **Responsiveness:** on-hand lookup for a branch and a product's movement history
  must remain fast as the ledger grows (indexed by (company, branch, product); the ledger is
  append-heavy). The architect indexes accordingly.

## 9. Assumptions

- **Products is the authority** for stockable/base-unit/recipe; Stock reads these **synchronously via
  DTOs** (the same boundary Sales uses — ADR-0006/0007). Stock owns **no** catalogue facts.
- **The outbox is built this round** as platform infrastructure (its own ADR, §3.4); Stock is its
  first consumer. Until the outbox exists, Stock cannot receive `SALE.FINALISED` / `STOCK.RECEIVED` —
  so the outbox is a **prerequisite** of this round, not an optional extra (OQ-STOCK-09).
- **Sales and Purchases produce the events Stock consumes.** Sales' `SALE.FINALISED` / `SALE.VOIDED`
  contract is fixed (ADR-0008 D-9); Sales must be wired to **actually emit** it now (ADR-0008 D-9 left
  a seam — that seam is closed this round). Purchases' `STOCK.RECEIVED` is specified in purchases.md.
- **On-hand starts at zero** for a never-tracked (product, branch); opening balances seed any
  pre-existing physical stock (FR-STOCK-10).
- **Quantity precision** follows the Products fractional-base-unit decision (OQ-PROD-07); Stock does
  not invent its own precision.
- **No valuation data is required** by v1 Stock; purchase cost lives on the purchase document, not on a
  stock movement (FR-STOCK-16). (When valuation lands, cost capture on the receipt movement is the
  clean additive change — NFR-STOCK-06.)

## 10. ACCEPTED SCOPE — quantity-only, no valuation; overselling allowed (owner-ruled 2026-06-07)

> **Read this before building or consuming Stock.** Two deliberate v1 stances, **owner-ruled**.

1. **v1 Stock is QUANTITY-ONLY — no valuation, no costing, no COGS.** On-hand quantity per (product,
   branch) and an append-only movement ledger are the whole module. There is **no** FIFO / weighted-
   average / standard cost, **no** stock value, **no** COGS on a sale, and **no** cost roll-up for a
   composed product. Purchase cost is recorded on the **purchase document** (money, purchases.md) but
   is **not** carried into inventory value. Valuation lands in a **Finance-aware round**; the v1 model
   must not preclude it (NFR-STOCK-06). No report or consumer may assume v1 Stock knows inventory
   value.

2. **Overselling is ALLOWED — on-hand may go negative.** A sale issues stock regardless of on-hand; if
   the issue exceeds on-hand, on-hand goes **negative**, is **flagged**, and the sale is **not**
   blocked (owner ruling). Negative on-hand is a **valid, surfaced state**, signalling missing
   receipts or count error — to be reconciled by adjustment, not prevented at sale time. Sales remains
   stock-agnostic (it never waits on a stock check — sales.md FR-SALES-21).

Both are deliberate and additive-by-design (NFR-STOCK-06); neither is precluded by the v1 model.

## 11. Open questions — ALL RESOLVED (owner-confirmed 2026-06-07)

> The **four headline decisions** (quantity-only; overselling allowed; recipe explosion; stock-in =
> real goods-receipt) **and every second-order question** are **RESOLVED** by the owner. **OQ-STOCK-09
> is RESOLVED = YES** (build the outbox this round, own platform ADR). **No ADR-0009-blocking question
> remains.** Full log (with resolution wording) in `docs/requirements/open-questions.md`.

- **OQ-STOCK-01 — On-hand: maintained row vs pure-derived.** ✅ **RESOLVED = maintained on-hand row +
  append-only movement ledger** (fast read + full history; on-hand == Σ movements).
- **OQ-STOCK-02 — Optional per-product negative-stock block.** ✅ **RESOLVED = negative allowed
  everywhere** (no per-product block in v1); negatives flagged. A per-product block is not precluded.
- **OQ-STOCK-03 — Non-stockable component on recipe explosion.** ✅ **RESOLVED = skip + record.** A
  non-stockable component is skipped (no on-hand to deduct) and the skip is recorded; stockable
  components are still deducted.
- **OQ-STOCK-04 — Adjustment reason codes + approval threshold.** ✅ **RESOLVED = small fixed reason
  list, reason mandatory, no approval threshold** in v1 (permission alone gates).
- **OQ-STOCK-05 — Opening-balance entry method.** ✅ **RESOLVED = manual opening balance** in v1 (bulk
  import is a later additive convenience).
- **OQ-STOCK-06 — Reorder level / low-stock indicator.** ✅ **RESOLVED = optional reorder level,
  indicator-only** (no auto-reorder, no purchase suggestion).
- **OQ-STOCK-07 — Stock count / stocktake workflow.** ✅ **RESOLVED = manual ADJUSTMENT only** in v1; a
  formal count/stocktake workflow is deferred.
- **OQ-STOCK-08 — Branch-to-branch transfers.** ✅ **RESOLVED = deferred** (the TRANSFER_OUT /
  TRANSFER_IN vocabulary is reserved so it is additive); v1 moves stock in/out/± only.
- **OQ-STOCK-09 — Outbox build scope (platform).** ✅ **RESOLVED = YES — build the transactional outbox
  this round under its own platform ADR.** `domain_event` + poller/dispatcher; **at-least-once delivery
  + consumer-side idempotency (dedupe on event id)**; **Sales wired to emit** `SALE.FINALISED` /
  `SALE.VOIDED` (closing the ADR-0008 D-9 seam); **Purchases' Goods Receipt emits** `STOCK.RECEIVED`;
  Stock is the consumer. *(Was the one genuinely blocking item — now closed.)*
- **OQ-STOCK-10 — Void-before-issue / out-of-order events.** ✅ **RESOLVED = reverse only what was
  issued; if nothing matches, record an anomaly** for review rather than posting a phantom negative.
  (Confirmed with the platform-outbox ADR.)

## 12. Out of scope for v1 (deferred — restated)

All valuation/costing (FIFO / weighted-average / standard cost, stock value, COGS, composed-cost
roll-up — Finance-aware round, §10); branch-to-branch transfers + in-transit stock (OQ-STOCK-08);
stocktake / cycle-count workflow (OQ-STOCK-07); batch/lot/serial/expiry tracking; reservations /
allocations / available-to-promise (with the deferred Sales-Order channel); reorder automation /
purchase suggestions (reorder level if adopted is indicator-only, OQ-STOCK-06); multi-level recipe
explosion (Products single-level, FR-PROD-16); negative-stock blocking (overselling allowed —
optional per-product block deferred, OQ-STOCK-02). Each tracked for a later round; none precluded by
the v1 model (NFR-STOCK-06).
