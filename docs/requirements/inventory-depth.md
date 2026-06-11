# Requirements — Inventory / Warehouse Depth (multiple stock locations, inter-location transfers, stock counts / cycle counts, batch-lot + serial tracking)

> Status: **DRAFT for owner ratification.** This is the business spec for **Inventory / Warehouse Depth**
> (docs/PATH-TO-FULL-ERP.md §3.5 — the remaining `[ ]` items: *multi-location / multi-warehouse*,
> *inter-branch / inter-warehouse transfers*, *stock counts / cycle counts / physical inventory + variance
> posting*, *batch / lot tracking + expiry*, *serial number tracking*). It is the depth layer that sits
> **on top of** the shipped quantity model (ADR-0010 / V7), the moving-average valuation + COGS engine
> (ADR-0020 / V17), and the reservation + delivery seam (ADR-0021 / V18-V19). It is **business-level only** —
> no schema, no API shapes, no tables/columns, no code. The data model is the solutions-architect's, in
> **ADR-0028** (next step).
>
> Author: system-analyst → consumed by solutions-architect. Domain: `stock` (warehouse depth) with read
> touches into `products` (batch/serial tracking flags) and posting touches into `gl` (transfer-in-transit
> + count-variance legs reuse the ADR-0020 valuation engine + the shipped `STOCK_ADJUSTMENT` account).
> **Depends on:** none beyond shipped (`stock` ADR-0010/V7, `valuation` ADR-0020/V17, `sales/reservation`
> ADR-0021/V18). **This module gates:** none directly (it enriches Manufacturing, ABC analysis, putaway/picking
> when those land — but does not block them).

---

## 1. Business context & why now

ERPCLEAN2's stock module is **quantity + moving-average value per `(company, branch, product)`**, fully
event-wired (receipt IN, sale issue OUT via delivery, adjustment, opening balance), and the books are
**perpetual**: inventory is a real `1300` asset, COGS posts at the moving average on every delivery, and a
valuation report reconciles `Σ(qty × avg) == 1300` (ADR-0020). But the warehouse model is **flat**: there is
**one stock bucket per branch**, and the moving average is computed **per company-product, summed across
branches** (ADR-0020 OQ-INV-07 deferred true multi-location valuation). The gaps that block real warehouse
operations:

- **No location granularity below the branch.** A branch is a *legal/reporting* unit, not a *physical place*.
  A real operation has named **warehouses / stores / locations** within a branch (main store, cold room, van
  stock, damaged-goods quarantine). Today there is nowhere to say "200 units in the main store, 50 in the
  cold room". On-hand and value cannot be split below the branch.
- **No inter-location movement.** Moving stock from the main warehouse to a retail shop is a real, frequent,
  **value-preserving** operation (it is **not** a sale, **not** a P&L event). Today it can only be faked as an
  adjustment-out + adjustment-in, which double-touches the `STOCK_ADJUSTMENT` expense and breaks valuation
  (it would post a phantom shrinkage out and a phantom revaluation in). The `MovementType` enum already
  **reserves** `TRANSFER_OUT` / `TRANSFER_IN` for exactly this (ADR-0010 OQ-STOCK-08) — they are excluded from
  the DB CHECK until this slice builds them.
- **No physical count / variance reconciliation.** Inventory drifts (theft, breakage, mis-picks, data error).
  The only way to true-up today is a per-product manual adjustment — there is no **stock count document** that
  freezes a snapshot, captures the counted quantity, computes the variance per line, and posts the net
  variance to `STOCK_ADJUSTMENT` in one auditable act. Cycle counting (counting a rolling subset continuously
  rather than a full freeze) has no home at all.
- **No batch/lot or serial identity.** Many goods are **lot-controlled** (food, pharma, chemicals — with
  expiry) or **serial-controlled** (electronics, equipment — one unit = one tracked identity). Today a product
  is a fungible quantity; there is no way to record "this 500 units is lot ABC, expires 2027-03" or "serial
  SN-00417 is on hand at the main store". Without this there is no expiry management, no recall traceability,
  no per-serial warranty/history.

**Inventory / Warehouse Depth closes these gaps as a depth slice on the existing stock module** (not a new
module). It introduces a **location dimension** under the branch, makes on-hand and value **per-location**,
adds a **value-preserving inter-location transfer** (with an optional in-transit holding state), a **stock
count / cycle count document** with single-act variance posting, and **batch/lot + serial tracking** layered
on the movement ledger. Every new path reuses the shipped spine: `StockPostingService` (the one posting
primitive), the moving-average valuation engine (`InventoryValuationService` / `InventoryGlPoster`,
ADR-0020), the transactional outbox + `IdempotencyGuard`, `GLPostingService` + `gl_configs`, `ScopeGuard`,
`code_sequence` numbering, `MasterStatus` soft-delete for the location master, and `Money` (BigDecimal,
HALF_UP, base currency).

### The load-bearing decision the architect must make (flagged up front)

**Where does the location dimension live relative to the shipped `(company, branch, product)` on-hand
scope?** The shipped `stock_on_hand` keys on `(company_id, branch_id, product_id)` and the moving average is
**per company-product** (ADR-0020). Introducing a location below the branch means the on-hand grain must
become `(company, branch, location, product)` — and the **moving-average cost grain** must be decided:
**per-company-product** (one average, locations share it — simplest, preserves ADR-0020's recon exactly) vs
**per-location** (each location carries its own average — richer, but changes the valuation grain and the
recon math). The requirement fixes the *behaviour* (on-hand and value visible per location); the **cost
grain** is an ADR decision (flagged OQ-INVD-01, recommended **per-company-product average, value attributed
to locations pro-rata** to preserve ADR-0020's `Σ == 1300` recon with no change to the average engine). See
§11.

---

## 2. Scope

### In scope — v1

1. **Location master** (`stock_locations` or similar) — named physical stock locations **within a branch**
   (org → company → branch → **location**). Code + name + type (WAREHOUSE / STORE / VAN / QUARANTINE / OTHER),
   active/inactive (`MasterStatus` soft-delete), one **default location per branch**. Backfill: every existing
   branch gets one **system default location** so all current on-hand migrates into it with zero behaviour
   change. (FR-INVD-01..04)
2. **Per-location on-hand + value.** On-hand quantity (and the inventory value attribution) becomes visible
   and queryable **per `(branch, location, product)`**, not just per branch. The branch total is the sum of
   its locations. (FR-INVD-05..06)
3. **Receipt / issue land at a location.** A goods receipt lands stock at a **receiving location** (the
   branch default unless specified); a sale/delivery issues from a **picking location** (the branch default
   unless specified). The existing receipt/delivery/adjustment/opening paths gain a location dimension,
   defaulting to the branch default location so nothing breaks. (FR-INVD-07)
4. **Inter-location transfer** — a `stock_transfers` document moving stock from a **source location** to a
   **destination location** (same or different branch within the same company). It is **value-preserving**:
   it moves quantity and the attributed value, posts **no COGS, no P&L** (BR-INVD-05). Two modes (ADR to
   choose the v1 default, OQ-INVD-02): **(a) instant** (one document, qty leaves source and arrives at dest
   atomically) or **(b) in-transit** (dispatch → receive, stock sits in an in-transit holding state between).
   v1 recommended default: **in-transit two-step** for cross-branch, **instant** for same-branch. (FR-INVD-08..11)
5. **Stock count / physical inventory** — a `stock_counts` document that (a) snapshots the system on-hand for a
   scope (a location, optionally a product subset) at freeze time, (b) captures the **counted quantity** per
   line, (c) computes the **variance** (counted − system) per line, and (d) on **post**, applies each variance
   as a stock movement and posts the **net variance to `STOCK_ADJUSTMENT`** (the shipped ADR-0020 account/key)
   in one auditable act. Lifecycle DRAFT → COUNTING → POSTED (or CANCELLED). (FR-INVD-12..16)
6. **Cycle count** — the same `stock_counts` document with a **partial scope** (a rolling subset of products /
   a location), so an operation can count continuously without a full freeze. v1 difference is **scope, not a
   second document type** (a cycle count is a stock count whose scope is a filtered subset). (FR-INVD-17)
7. **Batch / lot tracking + expiry** — for products **flagged lot-controlled**, every receipt records a
   **batch/lot** (lot number + optional manufacture/expiry dates), and on-hand is tracked **per lot** within a
   location; issues consume from lots (v1 default consumption: **FEFO — first-expiry-first-out**, OQ-INVD-04);
   an **expiry report** surfaces expired / near-expiry lots. (FR-INVD-18..22)
8. **Serial number tracking** — for products **flagged serial-controlled**, every receipt records the
   individual **serial numbers** (one per unit), each serial has a **status** (IN_STOCK / ISSUED / RETURNED)
   and a current location; a sale/delivery of a serial-controlled product captures the serials issued; a
   per-serial **history / current-location lookup** is available. (FR-INVD-23..27)
9. **Permissions** — new perms gate the new documents and reports: location management, transfer
   create/receive, stock-count create/post, and the batch/serial views. The receipt/issue/adjustment postings
   continue to ride their existing permissions (they gain a location, not a new gate). (FR-INVD-28)
10. **Reconciliation preserved.** The valuation report's `Σ(value) == 1300` recon (ADR-0020 BR-INV-06) **must
    still hold** after every new path (transfer, count-variance, lot/serial issue). A transfer nets to zero on
    `1300`; a count variance moves `1300` against `STOCK_ADJUSTMENT` exactly as a manual adjustment does today.
    (BR-INVD-12, NFR-INVD-01)

### Deferred (explicitly out of v1 — none precluded by the model)

- **Bin / shelf / slot hierarchy below the location** (location is the leaf in v1; a multi-level bin tree is a
  later additive depth — the location FK is the hook).
- **Putaway / picking / packing workflows, wave picking, pick paths** (PATH §3.5 — tied to multi-location but
  a separate operational slice; v1 lands stock *at* a location, it does not orchestrate *how* it is put away
  or picked).
- **Barcode-scanner / handheld warehouse operation** (PATH §3.5 — UI/integration depth on top of this).
- **Reorder-point automation + auto-PO generation** (PATH §3.5 — a separate planning slice; the reorder-level
  indicator stays per-row).
- **ABC analysis** (PATH §3.5 — a reporting slice on valuation; gated on this only for location granularity).
- **Consignment / van-stock allocation logic** (the VAN location *type* exists in v1; route-aware allocation
  is deferred).
- **Per-location moving-average cost** (if the ADR adopts per-company-product average for v1 — OQ-INVD-01;
  per-location average is an additive later refinement).
- **FIFO / standard cost, landed-cost capitalisation, manufacturing WIP, batch/serial *costing*** (lot/serial
  identity is tracked in v1; per-lot/per-serial *cost layers* are deferred — they inherit the
  company-product moving average).
- **Lot/serial on transfers and counts as mandatory** — v1 tracks lot/serial on receipt and issue; full
  lot/serial selection on every transfer line and count line is a refinement (v1 transfers/counts operate at
  the product-quantity grain, with lot/serial captured where the source path already has it — OQ-INVD-05).
- **Quality-control hold / inspection workflow** (the QUARANTINE location *type* exists as a destination; a
  formal QC inspection lifecycle is deferred).
- **Multi-currency / FX on inventory** (inherited base-currency-only from ADR-0020 / ADR-0005).
- **Negative-on-hand policy changes** (inherited from ADR-0010 / ADR-0020 — overselling allowed and flagged).

---

## 3. Actors

- **Warehouse / stores clerk** — receives goods to a location, picks/issues from a location, raises and
  receives transfers, performs counts, records batch/lot and serials. Holds the operational perms.
- **Inventory / stock controller** — manages the location master, sets the branch default location, posts
  stock counts (the variance-posting authority), reviews the expiry report and the valuation recon.
- **Finance / accountant** — consumes the valuation report + recon (unchanged from ADR-0020), reviews
  count-variance postings to `STOCK_ADJUSTMENT`, confirms transfers net to zero on `1300`.
- **Branch manager** — views per-location on-hand and movement history for their branch.
- **System (event-driven)** — the transfer / count / lot / serial effects ride the existing outbox handlers
  and `StockPostingService`; the system principal posts the stock + GL legs idempotently.

---

## 4. Functional requirements (FR-INVD-NN)

### Location master

- **FR-INVD-01** — The system shall let an authorised user create a **stock location** within a branch:
  unique **code** (per company), **name**, **type** (WAREHOUSE / STORE / VAN / QUARANTINE / OTHER), owning
  **branch**, and active flag.
- **FR-INVD-02** — The system shall let an authorised user **edit** a location's name/type and
  **deactivate / reactivate** it (`MasterStatus` soft-delete — never hard-delete a location with movement
  history).
- **FR-INVD-03** — Exactly **one location per branch** shall be the **default** (the receiving/picking
  location used when none is specified). Changing the default is an explicit, audited act; a branch always has
  exactly one default.
- **FR-INVD-04** — On deploy, every existing branch shall be back-filled with **one system default location**
  (e.g. code derived from the branch), and **all existing on-hand and movement history shall migrate into it**
  with no change to quantity, value, or the valuation recon.

### Per-location on-hand & value

- **FR-INVD-05** — On-hand quantity shall be visible and queryable **per `(branch, location, product)`**. The
  branch on-hand total equals the sum across its locations; the company total equals the sum across branches.
- **FR-INVD-06** — The inventory **value attribution** shall be visible per location such that the sum of
  per-location value equals the branch value and the company total still reconciles to `1300` (BR-INVD-12).
- **FR-INVD-07** — Goods receipt shall land at a **receiving location** and sale/delivery issue from a
  **picking location**; when not specified, both default to the **branch default location** (FR-INVD-03) so
  existing receipt/delivery/adjustment/opening flows continue unchanged.

### Inter-location transfer

- **FR-INVD-08** — An authorised user shall create a **stock transfer** moving a quantity of one or more
  products from a **source location** to a **destination location** (same company; same or different branch).
- **FR-INVD-09** — A transfer shall be **value-preserving**: it moves quantity and the attributed value from
  source to destination and posts **no COGS and no P&L** (BR-INVD-05). On the books a same-`1300`-account
  transfer nets to zero; a cross-cost-grain transfer (if per-location costing is ever adopted) carries the
  attributed value with it.
- **FR-INVD-10** — A transfer shall support an **in-transit** mode: a **dispatch** step removes stock from the
  source into an in-transit holding state, and a **receive** step lands it at the destination. (Same-branch
  transfers may use an **instant** single-step mode per the ADR default — OQ-INVD-02.)
- **FR-INVD-11** — The transfer document shall carry a number (`TRF-####`), a lifecycle (DRAFT → DISPATCHED →
  RECEIVED, or DRAFT → COMPLETED for instant), per-line source/destination quantities, and an audit trail.

### Stock count / cycle count

- **FR-INVD-12** — An authorised user shall create a **stock count** scoped to a **location** (optionally a
  product subset for cycle counting), which **snapshots the current system on-hand** per in-scope product as
  the count's `system_qty` baseline at freeze time.
- **FR-INVD-13** — The user shall enter the **counted quantity** per line; the system shall compute the
  **variance = counted − system** per line.
- **FR-INVD-14** — On **post**, the system shall apply each non-zero variance as a stock movement (a count
  movement type) at that location, recompute on-hand, and post the **net value variance to `STOCK_ADJUSTMENT`
  vs `1300`** (DR `STOCK_ADJUSTMENT` / CR `1300` for a net shortage; the reverse for a net overage) at the
  current moving-average cost — exactly the ADR-0020 adjustment-revaluation behaviour, batched into one
  document act (BR-INVD-08).
- **FR-INVD-15** — The stock-count lifecycle shall be DRAFT → COUNTING → POSTED (or CANCELLED before post);
  a POSTED count is **immutable** (corrections are a new count).
- **FR-INVD-16** — The count shall capture, per line, the counter, the count time, and a per-line reason where
  the variance is material (reusing the shipped `AdjustmentReason` vocabulary: SHRINKAGE / DAMAGE / EXPIRY /
  COUNT_CORRECTION / OTHER).
- **FR-INVD-17** — A **cycle count** shall be the same document with a **filtered scope** (a product subset or
  a single location), enabling continuous rolling counts without a full-location freeze.

### Batch / lot tracking + expiry

- **FR-INVD-18** — A product shall be flaggable **lot-controlled** (a product-master attribute). Only
  lot-controlled products track lots; all others remain fungible quantity as today.
- **FR-INVD-19** — Every **receipt** of a lot-controlled product shall record a **lot/batch**: a lot number
  (unique per product), optional manufacture date, optional **expiry date**, and the received quantity at the
  receiving location.
- **FR-INVD-20** — On-hand of a lot-controlled product shall be tracked **per lot within a location**; the sum
  of lot quantities equals the location on-hand.
- **FR-INVD-21** — Issues (sale/delivery, transfer-out, count shortage) of a lot-controlled product shall
  consume from lots by **FEFO — first-expiry-first-out** by default (OQ-INVD-04; the ADR confirms FEFO vs
  manual-select vs FIFO), decrementing the consumed lots.
- **FR-INVD-22** — The system shall provide an **expiry report** listing lots that are **expired** or expiring
  within a horizon, per product / location, with the on-hand quantity and value at risk.

### Serial number tracking

- **FR-INVD-23** — A product shall be flaggable **serial-controlled** (a product-master attribute, mutually
  exclusive with quantity-fractional units — a serial is one whole unit).
- **FR-INVD-24** — Every **receipt** of a serial-controlled product shall record the individual **serial
  numbers** (one per received unit), each unique per product, with an initial status IN_STOCK and a current
  location.
- **FR-INVD-25** — A sale/delivery of a serial-controlled product shall capture the **serials issued**,
  transitioning them to ISSUED; a return transitions them back to RETURNED/IN_STOCK.
- **FR-INVD-26** — A transfer of a serial-controlled product shall move the named serials' **current
  location**.
- **FR-INVD-27** — The system shall provide a **per-serial lookup**: current status, current location, and the
  movement history (received on, issued on which document, returned).

### Permissions

- **FR-INVD-28** — New permissions shall gate: location management, transfer create + transfer receive,
  stock-count create + stock-count post, and the batch/serial/expiry views. The receipt/delivery/adjustment
  postings keep their existing gates (they gain a location parameter, not a new permission).

---

## 5. Business rules (BR-INVD-NN)

- **BR-INVD-01** — A stock location belongs to exactly one branch; a branch has **exactly one default**
  location at all times. A location code is unique per company.
- **BR-INVD-02** — On-hand and value are maintained **per `(company, branch, location, product)`**. The
  existing per-branch view is a roll-up (sum over locations); the existing per-company valuation recon is the
  sum over branches (BR-INVD-12).
- **BR-INVD-03** — A location with any movement history or non-zero on-hand **cannot be hard-deleted** — only
  deactivated (`MasterStatus`). A deactivated location accepts no new receipts/issues but its history and
  residual on-hand remain visible (and must be transferred out before it is meaningfully retired).
- **BR-INVD-04** — Receipt/issue/adjustment/opening **default to the branch default location** when none is
  specified — preserving the behaviour of every shipped flow (BR-INVD migration safety).
- **BR-INVD-05** — An inter-location **transfer is value-preserving and never a P&L event**: it posts no COGS,
  no revenue, no expense. With a single company-product moving average (OQ-INVD-01 default) a transfer within
  the same `1300` account **nets to zero on the GL** (it only re-attributes value across locations, which is a
  sub-ledger movement, not a GL movement) — so v1 may post **no GL entry** for a same-cost-grain transfer and
  rely on the per-location value attribution in the stock sub-ledger. (If per-location costing is ever adopted,
  a transfer carries the source's value to the destination — still net-zero on `1300`.)
- **BR-INVD-06** — Transfer quantities are bounded by source availability flags but **not blocked** when the
  source would go negative (consistent with the shipped overselling stance, BR-STOCK-03 / ADR-0020) — negative
  source on-hand is flagged, not rejected. (OQ-INVD-03 — the ADR may default cross-branch transfers to
  block-on-insufficient; recommended **flag, not block**, to match the house stance.)
- **BR-INVD-07** — A transfer's dispatched-not-yet-received quantity sits in an **in-transit** state that is
  neither at source nor at destination on-hand but **is** part of the company total (so the `1300` recon holds
  throughout an in-transit transfer — the in-transit value is still inventory the company owns).
- **BR-INVD-08** — A stock count's **post** applies the variance per line as a count movement and posts the
  **net value variance to `STOCK_ADJUSTMENT` vs `1300`** at the current moving average — identical accounting
  to a manual ADR-0020 adjustment, never a new accounting treatment (so the recon discipline is unchanged).
- **BR-INVD-09** — A count's `system_qty` baseline is the **snapshot at freeze time**; if on-hand changes
  between freeze and post (a concurrent receipt/issue), the variance shall be recomputed against the **live**
  on-hand at post time **or** the post shall warn/refuse on a stale snapshot (OQ-INVD-06 — recommended:
  recompute against live on-hand at post, so the count never posts a stale variance that breaks the recon).
- **BR-INVD-10** — Lot numbers are unique **per product**; a lot belongs to one product. Lot on-hand is
  per `(location, lot)`; the sum of lot on-hand equals the location on-hand for a lot-controlled product
  (an integrity invariant the system maintains).
- **BR-INVD-11** — A serial number is unique **per product**; a serial is exactly one whole unit; a serial is
  in exactly one status and at one location at a time; the count of IN_STOCK serials at a location equals the
  location on-hand for a serial-controlled product (an integrity invariant).
- **BR-INVD-12** — **The valuation recon is sacred.** After every new path (transfer dispatch/receive,
  count post, lot/serial issue), the valuation report total `Σ(on-hand value)` shall still equal the `1300`
  Inventory GL balance (ADR-0020 BR-INV-06). A transfer nets to zero; a count variance moves both sides
  equally; lot/serial tracking changes *identity*, not value. This is the release-blocking acceptance bar.
- **BR-INVD-13** — Lot-controlled and serial-controlled flags are **set on the product master** and are
  **immutable once the product has stock movement history** (changing the tracking mode of a product with
  history would orphan its lots/serials — the flag is fixed at first movement, like opening-balance once-only).
- **BR-INVD-14** — Every new document (location, transfer, count) and every lot/serial mutation is **audited**
  (actor, timestamp, before/after) and scope-checked (`ScopeGuard.assertCanActIn`) on every read and write
  path — the #1 anti-regression guard.

---

## 6. Key flows

### 6.1 Receipt to a location (happy)

1. Goods receipt confirmed (Purchases) → `STOCK.RECEIVED` carries the receiving location (branch default if
   unspecified). 2. The receipt handler lands +qty at `(branch, location, product)`, recomputes the
   company-product moving average (ADR-0020 unchanged), and posts `DR 1300 / CR GRNI`. 3. If the product is
   lot-controlled, the receipt records the lot (number + expiry) and lands the lot's on-hand at that location.
   4. If serial-controlled, the receipt records the serial numbers (status IN_STOCK, current location).

### 6.2 Inter-location transfer, in-transit (happy)

1. Clerk creates a transfer: source location A, destination location B, lines with quantities. 2. **Dispatch**:
   −qty leaves A's on-hand (a `TRANSFER_OUT` movement) into the in-transit state; the attributed value travels
   with it; no P&L. 3. **Receive** at B: +qty arrives at B's on-hand (a `TRANSFER_IN` movement); value lands at
   B. 4. The valuation recon holds throughout (in-transit value is still company inventory). 5. Lot/serial: the
   dispatched lots/serials move their current location to B on receive.

### 6.3 Stock count with variance (happy)

1. Controller creates a count scoped to location A (optionally a product subset). 2. The system snapshots
   `system_qty` per in-scope product. 3. Clerks enter counted quantities. 4. Controller **posts**: for each
   line, variance = counted − live-on-hand; a count movement applies the variance at A; the net value variance
   posts `DR/CR STOCK_ADJUSTMENT vs 1300` at the current average. 5. On-hand at A now equals the counted
   quantities; the recon holds (the variance moved `1300` and `STOCK_ADJUSTMENT` equally).

### 6.4 Unhappy paths

- **Transfer to/from an inactive or wrong-company location** → rejected (scope + active check).
- **Receive a transfer that was never dispatched / double-receive** → rejected (lifecycle guard + idempotency
  on the receive event).
- **Post a count whose snapshot is stale** (on-hand moved since freeze) → recompute against live on-hand at
  post (BR-INVD-09) so the posted variance is always correct; warn the user the snapshot changed.
- **Issue a lot-controlled product with insufficient unexpired lots** → FEFO consumes available lots; if it
  drives a lot negative, flag (not block) consistent with the overselling stance; surface for review.
- **Record a duplicate serial / issue a serial not IN_STOCK** → rejected (serial uniqueness + status guard).
- **Set lot/serial flag on a product that already has movement history** → rejected (BR-INVD-13).
- **Missing `gl_config` (`STOCK_ADJUSTMENT` / `INVENTORY`) on a count post** → the operator's post command
  fails (BR-INV-12 precedent), never a silent skip.

---

## 7. Non-functional requirements (NFR-INVD-NN)

- **NFR-INVD-01** — **Reconciliation is the chief acceptance bar.** `Σ(on-hand value) == 1300` must hold after
  every new path; an integration test asserts the tie after a transfer, a count post, and a lot/serial issue.
- **NFR-INVD-02** — All new tables carry `company_id` (+ `branch_id` where the row is branch-scoped) and are
  tenant-scoped via `RequestContext` + `ScopeGuard`; no cross-company leakage. Per-company `uid` VARCHAR(26)
  ULID on every externally addressed entity; URLs address by uid.
- **NFR-INVD-03** — Concurrency on per-location on-hand reuses the shipped optimistic `@Version` + one-retry
  mechanism (ADR-0010 NFR-STOCK-04 / ADR-0020 D-2); no new locking strategy. Racing receipts/issues/transfers
  at the same `(location, product)` row serialise on its version.
- **NFR-INVD-04** — Cross-module effects (transfer GL if any, count-variance GL) post via the existing
  idempotent outbox / `GLPostingSafeInvoker` (REQUIRES_NEW, null-on-anomaly) for event-driven paths, and
  directly via `GLPostingService.post` for synchronous operator acts (count post) — the ADR-0020 split.
- **NFR-INVD-05** — Money is BigDecimal, base currency, HALF_UP; value attribution per location uses the
  ADR-0020 4-dp internal scale; no FX.
- **NFR-INVD-06** — Reports (per-location on-hand, expiry, count variance) are paginated and computed in SQL
  (aggregate, not row-by-row), scoped by company/branch.
- **NFR-INVD-07** — Additive Flyway migration (V37-V41 range; V1–V19 frozen). No shipped migration edited. The
  `MovementType` CHECK widens additively to admit `TRANSFER_OUT` / `TRANSFER_IN` (and a count movement type if
  the ADR adds one); `#12`-safe md5-bounded seed-uids for any per-company seed (location backfill).
- **NFR-INVD-08** — The model must not preclude the deferred items (§2): bins below the location, putaway/
  picking, per-location costing, batch/serial costing, QC inspection — each is an additive later layer.
- **NFR-INVD-09** — Backfill must be **behaviour-neutral**: after the default-location backfill, every existing
  on-hand quantity, value, and the valuation recon are unchanged (a migration keep-data test asserts this).

---

## 8. User stories (US-INVD-NN, abbreviated)

- **US-INVD-01** — As a stock controller I create named locations within a branch and set the default, so on-hand reflects physical places.
- **US-INVD-02** — As a clerk I receive goods to a specific location and issue from a specific location.
- **US-INVD-03** — As a clerk I transfer stock from the main warehouse to a shop without it hitting the P&L.
- **US-INVD-04** — As a clerk I dispatch a cross-branch transfer and the receiving branch receives it, with the goods in-transit between.
- **US-INVD-05** — As a controller I run a stock count of a location, enter counted quantities, and post the variance to the books in one act.
- **US-INVD-06** — As a controller I run a rolling cycle count of a product subset without freezing the whole location.
- **US-INVD-07** — As a clerk I receive lot-controlled goods with lot numbers and expiry, and the system issues oldest-expiry-first.
- **US-INVD-08** — As a controller I see which lots are expired or near-expiry and the value at risk.
- **US-INVD-09** — As a clerk I receive serial-controlled goods capturing each serial, and issue specific serials on a sale.
- **US-INVD-10** — As a manager I look up any serial's current location, status, and history.
- **US-INVD-11** — As finance I confirm the valuation report still reconciles to `1300` after transfers and counts.

---

## 9. Out-of-scope confirmations (so the architect doesn't over-build)

- No bin/shelf tree below the location (location is the leaf).
- No putaway/picking/packing orchestration; no wave/zone picking; no pick paths.
- No barcode/handheld integration.
- No reorder-point automation / auto-PO; no ABC analysis.
- No per-location moving-average cost in v1 (if the ADR picks company-product average — OQ-INVD-01).
- No batch/serial cost layers (lots/serials inherit the company-product average).
- No FIFO/standard cost; no landed cost; no manufacturing WIP.
- No QC inspection lifecycle (QUARANTINE is just a location type).
- No multi-currency inventory.

---

## 10. Accepted boundary / known v1 imprecisions

- **Single company-product moving average shared across locations** (OQ-INVD-01 default): a location does not
  have its own average; value is attributed to locations pro-rata to quantity at the company-product average.
  This preserves the ADR-0020 recon exactly with zero change to the average engine. Per-location average is a
  deferred refinement.
- **Transfers post no GL entry in v1** (same cost grain → net-zero on `1300`); the move is recorded entirely in
  the stock sub-ledger (the per-location value attribution). This is correct accounting (a transfer is not a GL
  event) and keeps the recon trivially intact. (If per-location costing is ever adopted, the architect adds the
  carrying-value leg — additive.)
- **Lot/serial tracking is identity-only in v1, not cost-layered** — a lot's value is its quantity × the
  company-product average, not a captured per-lot cost. Recall/expiry/warranty traceability is fully served;
  per-lot costing is deferred.
- **Transfers and counts operate at the product-quantity grain**; lot/serial detail is carried where the
  source path already has it (FEFO on issue, named serials on issue) but full lot/serial selection on every
  transfer/count line is a refinement (OQ-INVD-05).
- **Negative on-hand remains allowed and flagged** (inherited from ADR-0010 / ADR-0020) on transfers and
  count-driven movements.

---

## 11. Open questions (OQ-INVD — recommended defaults; flagged as ADR decisions, none blocks the spec)

- **OQ-INVD-01 (load-bearing) — moving-average cost grain with locations.** Per-company-product (one average,
  locations share it, value attributed pro-rata) **vs** per-location average. *Recommended: per-company-product
  average for v1* — preserves ADR-0020's `Σ == 1300` recon with no change to the average engine; per-location
  average is a deferred additive refinement. **This is the central ADR decision** — it sets the on-hand grain
  and whether transfers post GL.
- **OQ-INVD-02 — transfer mode.** Instant (one-step) vs in-transit (dispatch→receive) vs both. *Recommended:
  in-transit two-step for cross-branch, instant single-step for same-branch* (in-transit value still counts to
  the company total — BR-INVD-07).
- **OQ-INVD-03 — transfer-out on insufficient source.** Block vs flag-and-allow. *Recommended: flag-and-allow*
  (matches the shipped overselling stance, BR-STOCK-03 / ADR-0020); owner may flip cross-branch to block.
- **OQ-INVD-04 — lot consumption order.** FEFO (first-expiry-first-out) vs FIFO vs manual-select. *Recommended:
  FEFO default* (the expiry-management driver) with manual-select as a later refinement.
- **OQ-INVD-05 — lot/serial on transfers & counts.** Whether v1 requires lot/serial selection on every transfer
  and count line, or carries it only where the source path has it. *Recommended: product-quantity grain for
  transfers/counts in v1; lot/serial selection on those documents is a deferred refinement.*
- **OQ-INVD-06 — stale count snapshot policy.** Recompute variance against live on-hand at post vs refuse on a
  changed snapshot. *Recommended: recompute against live on-hand at post* (the posted variance is always
  correct and the recon never breaks; warn the user the snapshot moved).
- **OQ-INVD-07 — count movement type.** A dedicated `STOCK_COUNT` / `COUNT_ADJUSTMENT` movement type vs reuse
  the shipped `ADJUSTMENT` type with a count `source_document`. *Recommended: reuse `ADJUSTMENT` with a
  count-document source ref* (no new movement type, no CHECK churn beyond the transfer types) — the ADR
  confirms.
- **OQ-INVD-08 — transfer movement vs adjustment.** v1 introduces `TRANSFER_OUT` / `TRANSFER_IN` (already
  reserved in the enum, ADR-0010 OQ-STOCK-08). *Recommended: build the two reserved transfer types* (they are
  the correct, non-P&L, non-adjustment movement category).
- **OQ-INVD-09 — location backfill code.** The system default location code per branch (e.g. branch code +
  suffix, or a fixed `MAIN`). *Recommended: derive deterministically from the branch* so the backfill is
  idempotent and #12-safe.

---

*This document is the business spec for Inventory / Warehouse Depth. It states the behaviour; it does not
design the data model. The tables, columns, constraint names, enums, events, GL legs, perms, nav routes, and
the V37-V41 migration are the solutions-architect's, in **ADR-0028** (next step). Do not infer a schema from
this document.*
