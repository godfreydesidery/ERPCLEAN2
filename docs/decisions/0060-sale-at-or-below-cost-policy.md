# ADR-0060: Sale at or below cost — a per-company policy enforced at invoice finalise

- **Status:** Accepted (2026-08-01) — owner-decided (SAM Electronix: "a cashier must not be able to
  ring an item at or under what it cost us"); `V93` DDL owner-approved before authoring.
- **Deciders:** Owner + Solutions Architect
- **Effort:** M. **Migration:** `V93__sales_below_cost_policy.sql` — one additive, metadata-only
  `ADD COLUMN ... DEFAULT 'OFF'` + `CHECK` on the existing populated `sales_settings` table
  (ADR-0043, additive-only). Plus one permission code (`SALES.BELOW_COST.OVERRIDE`) in the
  repeatable `R__seed_permissions.sql`.
- **Related:** ADR-0056 (VAT-inclusive pricing — why the comparison must be on the *derived net*,
  not the stored unit price), ADR-0020 (moving weighted-average cost on `stock_on_hand` — the cost
  basis), ADR-0026 D-10 (`LeafCostResolver` dependency-inversion port — how `sales` reads a stock
  cost without importing `stock`), ADR-0028 D-2 (company-wide average, multi-location aggregation),
  ADR-0058 (kits/phantoms that explode at issue — the coverage hole this control inherits),
  ADR-0002 (RBAC by permission code), ADR-0004 (audit-by-aspect / `AuditService`), the
  negative-stock guard and its 2026-08-01 repair (commit `9e03f7e4`) — the structural sibling and
  the cautionary tale behind D-6.

## Context

Nothing stopped a sale going out at a loss. A cashier (or a salesperson on a walk-in invoice) could
ring any line at any price; the totals engine happily computed the invoice, revenue was committed,
and the loss only showed up later in a margin report — if anyone ran one. The client asked for a
switch: **a supervisor should have to approve a sale priced at or below cost**, with the stricter
"never allow it" and the softer "let it through but tell me" also on the table.

Three things constrain the design:

1. **Where a price becomes final.** A draft invoice is still being negotiated. Line prices change,
   lines are removed, a document-level discount is applied at the end. There is exactly one moment
   where the price the customer will pay is known and still revocable: `finalise`.
2. **What "cost" means here.** The system carries at least three candidates — the product master's
   `cost_amount` (a Money field on `Product`, hand-entered), the last goods-receipt `unit_cost_amount`,
   and `stock_on_hand.avg_cost` (the moving weighted average maintained by ADR-0020/0028). They
   routinely disagree.
3. **The data is not uniformly good.** Real tenants have products that have never been received,
   services with no stock row at all, and stock that was oversold before costing was in use. A rule
   that demands a cost for every line will fire hardest exactly where the system knows least.

The sibling control (block-sales-that-go-negative) shipped **broken to a live client** three weeks
earlier for a reason worth repeating here: the settings API and the guard held *opposite* defaults
for the same "company has no `sales_settings` row" state, each unit-tested in isolation, both suites
green. The screen said protected; the till oversold. That failure mode is a direct input to D-6.

## Decision

A per-company `sales_settings.below_cost_action` with four values — `OFF` (default) · `WARN` ·
`APPROVE` · `BLOCK` — enforced synchronously by `BelowCostGuard` (`com.erp.modules.sales.service`),
a structural sibling of `NegativeStockGuard` (same `Propagation.MANDATORY`, same read-the-setting-once
shape, same friendly `ConflictException`).

An **action enum, not a boolean**, because the owner named four distinct stances and a boolean can
express two. `VARCHAR(16)` + `CHECK` rather than a Postgres enum type, matching every other status
column in the schema (a new value is a `CHECK` swap, not a type migration).

### D-1 — The enforcement point is sales-invoice `finalise`, immediately AFTER `totalsCalc.recompute`

`SalesInvoiceServiceImpl.finalise` calls `totalsCalc.recompute(inv, lineList)` and then, on the very
next statement, `belowCostGuard.assertNotBelowCost(...)`. **That ordering is load-bearing, not
incidental.**

`InvoiceTotalsCalculator.recompute` is where a line's `netAmount` becomes correct:

- it strips VAT out of a **VAT-inclusive** line (ADR-0056 D-5: `net = round(gross / (1 + rate))`),
  and
- it applies the line discount **and** the apportioned **document-level** discount.

Before that call, `netAmount` is either stale (a previous recompute's value) or, on an inclusive
price list, a figure with VAT still in it. After it, `netAmount` is the money the company actually
keeps on that line.

Finalise is also the **single choke point every origin passes through**. The POS does not have its
own posting path: `PosSaleServiceImpl` rings a sale by building an invoice and delegating to
`SalesInvoiceServiceImpl.finalise`. One call site therefore covers walk-in invoicing, POS, and
order-billed invoicing alike — there is no second place to remember. Rejecting inside the finalise
transaction rolls the whole thing back, so a rejected sale emits no outbox event, issues no stock and
posts no GL.

**Consequence (the trap):** moving the guard call earlier — above the recompute, into `addLine`, or
into a "validate" pre-pass — silently breaks the VAT-inclusive case **without failing a single unit
test**. The guard's own tests hand it `PricedLine` records directly, so they cannot see the
ordering; the exclusive-VAT path would keep passing because gross == net there. Anyone refactoring
`finalise` must treat "recompute, then guard" as an invariant. The comment at the call site says so;
this ADR is the durable record.

### D-2 — Cost basis is the moving-average cost, read through the existing `LeafCostResolver` port

The comparison is against `stock_on_hand.avg_cost` — the moving weighted average maintained by
ADR-0020, aggregated across the branch's stock locations by ADR-0028 D-2. `sales` reads it through
`LeafCostResolver.avgCosts(companyId, branchId, List<Long> productIds)`, the port declared in
`products` and implemented in `stock` (ADR-0026 D-10). No new port, no new query, and no
`sales → stock` import — the module graph is unchanged and `ModuleBoundaryTest` stays green.

The guard makes **one** `avgCosts` call per invoice, carrying the DISTINCT product ids, so a basket
with the same product on five lines costs one lookup.

**Why not the product master's "Cost (buying) price" (`Product.cost`).** It is a manually entered
field that **nothing refreshes**. No receipt, bill, landed-cost allocation or revaluation writes to
it. In practice it holds whatever someone typed when the product was created — often a year-old
supplier quote, often blank, occasionally a *selling* price entered in the wrong box. Enforcing a
company's loss policy against a field with no maintenance story would produce a control that is
confidently wrong: it would block profitable sales of items whose cost fell, and wave through
genuine losses on items whose cost rose.

**Why not the last purchase price.** Closer to reality but too jumpy: one atypical small top-up
order at a bad price, or one freight-heavy consignment, moves the floor for every subsequent sale
until the next receipt. It also ignores what is actually sitting on the shelf — a company can hold
500 units bought cheaply and be blocked from selling them because the last 10 units cost more. The
moving average is the figure the rest of the system already uses to value inventory and to post
COGS, so the policy stays consistent with what the P&L will say about the same sale.

**The known weaknesses of this basis, recorded honestly:**

- **`avg_cost` is NULL until a product has been costed.** A product that has never been received
  (or was received before valuation was in use) has no average at all. Those lines are unchecked —
  see D-4.
- **A goods receipt onto zero-or-negative aggregate stock RESETS the average rather than blending
  it** (`InventoryValuationServiceImpl.doRecomputeOnReceipt`: `!hasExistingAvg || totalQty <= 0`
  → `newAvg = cost`). This is correct valuation behaviour — there is no positive quantity left to
  weight against — but it has a real consequence for this policy: **for a product that has been
  oversold, the floor is simply whatever the most recent receipt cost.** A company running on
  backorder with negative on-hand effectively gets a "last purchase price" floor, which is exactly
  the volatility D-2 rejected. Nothing here fixes that; it is inherent to a moving average kept on a
  negative balance, and it is a further argument for running the negative-stock block alongside this
  one.
- The multi-location weighted average has an unweighted-mean fallback when every costed row is at
  zero/negative quantity (`StockLeafCostResolver.weightedAverageCost`) — an approximation, inherited,
  not introduced here.
- `StockLeafCostResolver` still issues **one SELECT per product id** inside the batch call (an N+1
  its own javadoc records). At till basket sizes this is fine, and it only happens when the policy is
  not `OFF`, but batching that resolver is the obvious optimisation if a large basket ever shows up
  in latency.

### D-3 — The comparison is on the NET unit price after discounts, using `<=`

For each line: `netUnitPrice = line.netAmount / line.qtyInBase`, compared against the product's
`avg_cost`. A line trips the policy when **`netUnitPrice <= unitCost`** — "equal or less", so selling
*exactly* at cost counts as below cost. That is the owner's rule: a sale at cost earns nothing and
still consumes stock, staff time and warranty exposure.

Three properties follow from using `netAmount` rather than the stored `unit_price_amount`:

- **VAT-inclusive lists are handled correctly.** On an inclusive list (ADR-0056) `unit_price_amount`
  holds the **GROSS** amount. Comparing gross against a net cost would let a line sitting ~18% *under*
  cost sail through — the control would appear to work while being wrong by exactly the VAT rate on
  every retail price list. This is the single most likely way to break the feature, and it is why D-1
  pins the call after the recompute.
- **Discounts are included.** `netAmount` is post-line-discount and post-apportioned-document-discount,
  so a sale that was profitable at list price and got discounted into a loss — the common real case —
  is caught. A guard on the list price would miss all of them.
- **Units line up.** Dividing by `qtyInBase` puts the price in the product's base unit, the same unit
  the cost is held in, so a pack line compares pack-net-per-base against per-base cost rather than a
  pack price against a unit cost (ADR-0048 multi-unit pricing).

`netUnitPrice` is derived at scale 4, `HALF_UP` — the same scale `avg_cost` is stored at, so the
comparison is not decided by a rounding artefact.

### D-4 — An unknown or zero cost NEVER blocks; it is recorded as unchecked

A line whose product has no `avg_cost`, or an `avg_cost` of zero, is **allowed under every action,
including `BLOCK`**. It is not silently dropped: it is logged and written to `audit_log` as
`SALES.BELOW_COST.WARNING` with `reason = COST_UNKNOWN`, naming the products.

Why:

- **The rule would otherwise fire hardest where the data is weakest.** Missing cost is a data gap,
  not a pricing decision. Blocking on it jams a till over something the cashier cannot fix and did
  not cause.
- **Zero is read as "not costed yet", not as "this item is free".** A `<=` comparison against a zero
  cost is true for every non-negative price, so treating zero literally would reject **every line**
  for a company that has never costed its stock — the exact population most likely to switch the
  policy on first.

**The consequence, stated plainly so no one over-reads the control:** `BLOCK` does **not** mean
"nothing can ever be sold under cost." The policy does not cover —

- **services and fees** (non-stockable; no `stock_on_hand` row, so no cost — a service-only invoice
  is never checked at all);
- **drop-ship / never-received products** (no receipt has established a cost);
- **products stocked before valuation was in use** (`avg_cost` still NULL);
- **kits and phantoms that explode at issue** (ADR-0058 — a POS kit with `product_components`, or a
  non-stockable BOM phantom). The parent carries no on-hand row of its own, so it resolves as
  uncosted and passes. Unlike `NegativeStockGuard`, this guard does **not** explode the recipe and
  does **not** compare a rolled-up component cost. **Selling a kit under cost is not caught.**

Rolling the guard forward onto the exploded components (reusing `RecipeExplosionResolver` and
`BomCostRollUpService`) is the natural follow-up if kit pricing turns out to matter; it is not in
this change. Until then, the honest description of the feature is *"catches under-cost pricing on
stocked products that have been received at least once"* — not *"prevents loss-making sales."* The
`COST_UNKNOWN` audit rows exist precisely so the gap is measurable rather than assumed away.

### D-5 — The default is `OFF`, and the upgrade changes nothing

`below_cost_action` defaults to `'OFF'` in the DDL, in the entity field initialiser, and in the
transient default row the settings API returns for a company with no row. Nothing changes for any
company until an admin explicitly picks a mode.

This is a **deliberate contrast with the negative-stock repair**, which flipped its fallback to the
*protective* value. The two are different kinds of change:

- Negative-stock was a **broken existing promise** — the screen already claimed the block was on
  while the till oversold. Fixing it meant making reality match what operators had already been told,
  so the safe direction was "block".
- Below-cost is a **new opt-in control**. No one has been promised anything. Defaulting it to `WARN`
  or `APPROVE` would, on the first deploy, start rejecting or flagging sales at every existing
  tenant — including clearance, staff sales, promotional loss-leaders and every legitimate
  below-cost sale a business deliberately makes. A control that switches itself on across a live
  estate is how a pricing policy becomes an outage.

So: additive column, inert until chosen, no backfill, no provisioning dependency. (Unlike the
negative-stock flag, nothing about this policy depends on the `sales_settings` row *existing* —
D-6 exists to keep it that way.)

### D-6 — The missing-row value must be IDENTICAL on both sides, pinned by a cross-layer test

For a company with no `sales_settings` row, the value the **API reports** and the value the **guard
enforces** must be the same. Both are `OFF`:

- `SalesSettingsServiceImpl` returns a transient default `SalesSettings` whose `belowCostAction`
  field initialiser is `OFF`;
- `BelowCostGuard` maps the empty `Optional` with `.orElse(BelowCostAction.OFF)`.

`BelowCostSettingCrossLayerContractTest` drives the **real** `SalesSettingsServiceImpl` and the
**real** `BelowCostGuard` off **one shared repository state** and asserts they agree — for the
missing row and for each configured action.

**This test exists because of a specific, shipped defect.** The negative-stock setting reached a
live client broken in exactly this shape: `SalesSettingsServiceImpl` returned a transient default
with `allowNegativeStock=false` (screen renders "blocking: ON") while `NegativeStockGuard` mapped the
same missing row to `.orElse(true)` (allow). Each layer had passing unit tests. The suites asserted
**opposite** things about the identical state and neither could see the other, because no test in
the codebase occupied the gap *between* the layers. The screen lied for weeks.

Below-cost is the same shape — one column, on the same row, read by an API on one side and a guard
on the other — so it is exactly as easy to get wrong in exactly the same way. A per-layer test cannot
catch it by construction; only a test that owns the agreement can. Any future change to either
default breaks this test, which is the entire point.

Generalisation (not enforced mechanically, worth stating): **any settings field read by both a
read-API and an enforcement path needs a contract test on its unconfigured state.** Two green suites
are not evidence that two layers agree.

### D-7 — `APPROVE` requires BOTH an explicit request flag AND the permission

Under `APPROVE`, a below-cost sale is allowed only when the caller **both**:

1. set `belowCostApproved = true` on the request (`FinaliseInvoiceRequest`, threaded through from
   `PosSaleRequest` by `PosSaleServiceImpl`), **and**
2. holds `SALES.BELOW_COST.OVERRIDE` (checked via `PermissionResolver` against the live
   `RequestContext`).

Neither alone suffices. A flag alone would make the policy decorative — any client could set it. The
permission alone would mean a supervisor's own till session silently auto-approves every below-cost
line without a deliberate act, which is not an approval, it is an exemption. Requiring both keeps
"someone with authority consciously decided this one sale" as the actual semantics. The successful
override writes a distinct `SALES.BELOW_COST.OVERRIDE` audit row naming the products; a refusal
raises the friendly "a supervisor needs to approve this sale" `ConflictException`.

`SALES.BELOW_COST.OVERRIDE` is seeded in `R__seed_permissions.sql` (convergent reference data) and
granted to `SALES_MANAGER` and `BRANCH_MANAGER` — the supervisor a cashier actually calls over
(ADR-0057 bundles).

### D-8 — Error messages name products, never numbers

Rejections name the offending products (capped at three, then "…and N more items"), and **never**
quote the cost, the margin, an id or a uid — the error-hygiene standing rule, and also a commercial
one: a till operator has no business reading the company's margin off an error toast. The technical
detail (counts, company, branch, invoice uid) goes to the log and the audit row only.

## Consequences

- **A real control, with a bounded promise.** Under `BLOCK`, a stocked, previously-received product
  priced at or under its moving average cannot be invoiced — through any origin, including the till.
  Under `APPROVE`, it takes a permissioned, explicit act, and that act is audited. Under `WARN`, the
  sale completes and leaves a trail. Under `OFF` (default), nothing changes.
- **Coverage is NOT universal** (D-4). Services, fees, drop-ship, never-received products and
  kits/phantoms are all unchecked and pass under every mode. This must be said out loud when the
  feature is handed over; "BLOCK" is not a guarantee that no loss-making line can be sold.
- **The floor moves with the data.** `avg_cost` changes on every receipt and revaluation, so a price
  that finalises today may be rejected tomorrow. For an oversold product the floor collapses to the
  last receipt cost (D-2). Neither is a bug; both are consequences of choosing the moving average.
- **Ordering is now an invariant of `finalise`** (D-1) that no unit test protects. Recorded here and
  in a call-site comment.
- **Audit volume rises once the policy is not `OFF`** — see the open questions.
- **No cross-module boundary change, no new port, no new query.** All of it lives in `sales`, reading
  `stock` through the port `products` already owns.
- **Schema cost:** one nullable-free `VARCHAR(16) DEFAULT 'OFF'` column and one `CHECK` on
  `sales_settings` — metadata-only on a populated table, validating instantly because every existing
  row takes the default.

## Open questions (explicitly unresolved — do not read an answer into this ADR)

- **OQ-1 — Should the guard run for every invoice origin, or only DIRECT/POS?** It currently runs for
  **every** origin, on the reasoning that an order-billed invoice commits revenue at the same price a
  walk-in does. **The hazard is real and not yet mitigated:** an invoice billed from a sales order can
  be rejected at finalise **after delivery has already issued the stock**. The goods are gone, the
  customer has them, and under `BLOCK` there is *no override* — leaving the delivered goods stranded
  with no invoice, and the operator with no path forward except changing the policy or re-pricing a
  document that may be contractually fixed. Candidate resolutions (none chosen): restrict the guard
  to `DIRECT`/`POS` origins; check at *order confirmation* instead for the order-billed path; or
  allow the `APPROVE` override to apply under `BLOCK` for already-delivered invoices. Owner decision
  required.
- **OQ-2 — The Flutter till cannot send the approval flag yet.** `PosSaleRequest.belowCostApproved`
  exists and is threaded through, but the OrbixPOS client does not set it and has no supervisor-
  approval UI. **Until it ships, a company on `APPROVE` gets every below-cost sale rejected at the
  till with no way to approve it — `APPROVE` behaves as `BLOCK` there.** The web invoice screen is
  unaffected. Companies using the till should therefore be pointed at `WARN` (or `OFF`) until the
  app catches up; documenting that limitation at handover is not optional. Whether the till should
  prompt for a supervisor's credentials inline, or rely on the supervisor being logged in, is also
  undecided.
- **OQ-3 — Audit volume.** Once the policy is not `OFF`, **every** invoice containing an uncosted
  line writes a `SALES.BELOW_COST.WARNING` row (`reason = COST_UNKNOWN`) — for a service-heavy or
  poorly-costed catalogue that is potentially one row per sale, forever, in an append-only table the
  app role cannot prune. That is deliberate (the gap in D-4 should be measurable, not invisible), but
  it is untuned: no sampling, no de-duplication per invoice-product, no retention story. Whether to
  keep it as-is, throttle it, downgrade the `COST_UNKNOWN` case to a log-only signal, or surface it
  as a "products with no cost" report instead is open. Revisit after the first tenant runs a non-`OFF`
  mode for a month.

## Alternatives considered

- **Cost = the product master's `Product.cost` ("Cost (buying) price").** Rejected (D-2): a manual
  field nothing refreshes; it disagrees with the figure the system posts COGS at, so the policy would
  contradict the P&L on the same sale.
- **Cost = last purchase price.** Rejected (D-2): too volatile — one atypical or freight-heavy
  receipt moves the floor for all subsequent sales, and it ignores the cost of what is actually on the
  shelf. (Note the irony recorded in D-2: for an *oversold* product the moving average degenerates to
  exactly this.)
- **Compare the stored `unit_price_amount`.** Rejected (D-3): on a VAT-inclusive price list that
  value is GROSS, so a line ~18% under cost would pass — a control that looks like it works.
  Comparing pre-discount price would also miss every discounted-into-a-loss sale, which is the common
  case.
- **Strict `<` (below cost only, allow selling exactly at cost).** Rejected by the owner: at-cost
  earns nothing while consuming stock and staff time. `<=` is the rule.
- **Block when the cost is unknown (fail-closed).** Rejected (D-4): it fires hardest where the data
  is weakest, jams a till over a data gap the cashier cannot fix, and — with a zero cost — rejects
  every line for a company that has never costed its stock. The trade is honest coverage gaps that
  are *audited* over a control that is unusable in the field.
- **A boolean `block_below_cost` instead of an action enum.** Rejected: the owner named four
  stances (off / observe / approve / forbid) and the requested mode is the middle one; a boolean
  cannot express "allow with an audited supervisor override" and would have needed a second column
  within the month.
- **A margin-percentage floor (e.g. "never below cost + 5%").** Rejected as scope: a different (and
  bigger) product decision about pricing policy, needing per-category/per-product thresholds to be
  useful. The at-or-below-cost line is the one the client asked for and the one with an unambiguous
  meaning. `below_cost_action` does not preclude a later `min_margin_pct` alongside it.
- **Enforce at line-add / price-entry time instead of finalise.** Rejected (D-1): a draft is still
  being negotiated, the document discount has not been apportioned yet, and the VAT-inclusive net is
  not yet derived — so the check would be both premature and, on an inclusive list, wrong. Finalise is
  the last revocable moment and the single choke point the POS also routes through. A *soft*
  line-level hint in the UI (non-blocking, purely advisory) is compatible with this and not built.
- **A pending-approval state machine via the approvals engine** (invoice parks as `PENDING_APPROVAL`,
  a supervisor approves asynchronously). Genuine segregation of duties, but it adds a document state,
  a queue and workflow latency to a till transaction where the supervisor is standing three metres
  away. The request-flag + permission pair (D-7) gives the same audit trail synchronously. Revisit if
  approvals need to happen remotely.
