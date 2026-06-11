# Requirements — Procurement / Purchase-to-Pay depth (requisition → RFQ/quote → PO approval → landed cost → purchase return/debit-note)

> Status: **DRAFT (architect-authored, owner-style assumptions made; load-bearing OQs flagged for ratification).**
> The shipped procure-to-pay loop is **PO → Goods Receipt → supplier bill → 3-way match → payment**
> (ADR-0011 / V8 + ADR-0015 / V12). This slice adds the **upstream demand-and-governance layers** and the
> **cost-completeness layers** on top of that loop, additively, none of which blocks the loop:
> **(1)** a **Purchase Requisition (PR)** — an internal demand document (who needs what, by when), with an
> **approval gate** before it becomes a PO; **(2)** **RFQ → supplier quotes → award** — solicit prices from
> several suppliers against a requisition (or ad-hoc), capture each supplier's quote, compare, and **award**
> the winning quote into a PO; **(3)** **PO approval via the approvals engine** — a PO above a configured
> threshold cannot be placed (DRAFT → ORDERED) until approved; **(4)** **landed cost** — freight / duty /
> clearing / insurance charges captured against a receipt and **allocated INTO inventory value** (capitalised
> to unit cost) via the ADR-0020 moving-average valuation engine, so COGS reflects the true landed cost;
> **(5)** **purchase returns / RMA → debit note** — return received goods to a supplier (stock OUT at the
> original receipt cost, reversing the GRNI/inventory effect) and raise a **supplier debit note** (reusing the
> shipped AP `ApDebitNoteService`).
>
> Author: solutions-architect (consuming the system-analyst backlog — docs/PATH-TO-FULL-ERP.md §3.4).
> Domain: `purchases` (the new requisition / RFQ / quote / landed-cost / return documents on the existing
> purchases spine), with touches into `stock` (the landed-cost capitalisation + the return stock-out), `ap`
> (the return debit note + the landed-cost accrual on a freight bill), and a **dependency on the approvals
> engine** (X.5, not yet built — this spec designs to its *expected contract* and flags the assumption).
> Business-level spec only. **No schema, no API shapes, no tables/columns, no code** — those are the
> architect's, in **ADR-0027** (the companion data-model decision).
>
> **This is Procurement / Purchase-to-Pay depth — Phase B (docs/PATH-TO-FULL-ERP.md area 7 / docs/ROADMAP.md
> procurement depth).** Builds on the shipped buy→receive→match→pay loop. **Latest shipped migration is V19;
> this slice is the additive V32–V36 range (the V20–V31 gap is reserved for other in-flight modules; the
> coordinator owns collision detection).**
>
> **Depends on:**
> - **Purchases** (ADR-0011 / V8): `purchase_orders` (header — `order_number` PO-####, `status`
>   DRAFT→ORDERED→PARTIALLY_RECEIVED→RECEIVED→CLOSED/VOID, `supplier_id`, `order_total_amount`) +
>   `purchase_order_lines` (`product_id`, `ordered_qty`, `ordered_qty_in_base`, `received_qty_in_base`
>   maintained, `unit_cost_amount`, `line_total_amount`), `goods_receipts` + `goods_receipt_lines`
>   (`received_qty`, `qty_in_base`, `unit_cost_amount`, `line_cost_amount`, FK `purchase_order_line_id`),
>   the `STOCK.RECEIVED` / `STOCK.RECEIPT.VOIDED` outbox events, `PurchaseNumberGenerator`, `OutstandingTracker`.
> - **Inventory Valuation & COGS** (ADR-0020 / V17): the moving-average engine —
>   `InventoryValuationService.recomputeOnReceipt` / `costIssue` / `reverseIssue` / `revalueAdjustment`, the
>   `avg_cost` + `on_hand_value` columns on `stock_on_hand`, the per-movement `unit_cost_amount` /
>   `value_amount` columns, the **GRNI** clearing liability (DR Inventory / CR GRNI at receipt, cleared at
>   bill-match), `InventoryGlPoster`, `StockPostingService.post(...)`. **The engine landed cost capitalises
>   into, and the return reverses out of.**
> - **Accounts Payable** (ADR-0015 / V12): `ApDebitNoteService.raise(...)` (supplier credits — **REUSED for
>   purchase returns**), `supplier_bills` + 3-way match + `BillMatchServiceImpl.postMatchedBillToGl` (the
>   GRNI/PURCHASES/VAT_INPUT split), `ApDebitNoteOrigin`, `code_sequence` BILL-####/PAYRUN-####.
> - **Parties** (ADR-0006 / V2): `suppliers` (`supplier_kind` GOODS|SERVICE, per-company multi-branch).
> - **Products** (ADR-0007 / V3): products, units, recipes, the price-list mechanism (the supplier-price seam).
> - **GL** (ADR-0013 / V10): `GLPostingService.post`, `GLConfigResolver`, `gl_configs` key→account mapping.
> - **Approvals engine** (X.5 — **NOT YET BUILT**): this slice assumes an `ApprovalService` contract
>   (submit a document for approval against a policy keyed by document type + amount threshold; query approval
>   state; an APPROVED / REJECTED outcome) — see **§8 (the approvals dependency)** for the exact assumed
>   contract and the fallback if approvals does not land first.
> - **Money** (ADR-0005 — base currency only, TZS, HALF_UP); **RBAC / `@perm` / `assertCanActIn` / audit /
>   the idempotent transactional outbox + `IdempotencyGuard`** (the platform spine). All shipped except approvals.

---

## 1. Business context & why now

ERPCLEAN2 buys today through **one entry point: the Purchase Order**. A buyer creates a draft PO, places it
(PO-#### assigned, lines frozen), receives goods against it (a Goods Receipt pushes stock in and — since
ADR-0020 — accrues GRNI: DR Inventory / CR GRNI at the moving-average cost), enters the supplier's bill,
3-way-matches it (bill ↔ PO ↔ GR within tolerance), which posts DR GRNI / CR AP and clears the accrual, then
pays it. That loop is **complete, balanced, and event-wired.** What it lacks is everything *around* the PO:

- **No demand capture before the PO.** Anyone with `PURCHASE.ORDER.CREATE` can raise and place a PO directly.
  There is no **requisition** — the internal "I need 50 reams of paper by Friday" request that a department
  raises and a manager approves *before* procurement commits money. Real organisations separate **who needs
  it** (the requester) from **who buys it** (the buyer) from **who approves the spend** (the approver).

- **No price discovery.** A buyer types the unit cost on the PO line from memory or a phone call. There is no
  **RFQ** (request-for-quotation) sent to several suppliers, no captured **supplier quotes**, no
  side-by-side **comparison**, and no **award** that turns the winning quote into a PO with the quoted price.

- **No spend governance.** A PO is placed the moment a buyer clicks "Order" — there is **no approval
  threshold**. A 50,000,000 TZS PO and a 5,000 TZS PO follow the identical path. Finance governance demands
  that POs above a limit require sign-off before they become a commitment.

- **Inventory cost is incomplete.** A receipt capitalises inventory at the **PO unit cost only**. The
  **freight, import duty, clearing, and insurance** that make up the *landed* cost are expensed separately
  (or lost), so COGS understates the true cost of goods and margin is overstated. Real distribution —
  especially **import-heavy Tanzanian trade** — must **capitalise landed cost into the unit cost** so the
  moving average, the inventory asset, and COGS all reflect what the goods actually cost to get to the shelf.

- **No structured return to a supplier.** When received goods are wrong, damaged, or rejected, there is no
  **purchase return / RMA**: no document that takes the goods back out of stock (reversing the receipt's
  inventory/GRNI effect at the original cost) and raises a **supplier debit note** (the AP credit that
  reduces what we owe). Today the only recourse is a manual stock adjustment + a manual debit note, untied.

This slice adds those five layers — **requisition, RFQ/quote/award, PO approval, landed cost, purchase
return** — each **additive around the shipped loop**, none of them breaking it. A buyer who ignores
requisitions and RFQs can still place a PO directly (governance permitting); a company that does not import
never touches landed cost; a company that never rejects goods never raises a return. The depth is opt-in by
document, mandatory only where a business rule (an approval threshold) makes it so.

---

## 2. Scope

### 2.1 In scope (v1)

1. **Purchase Requisition (PR)** — an internal demand document: `PR-####`, requester, required-by date,
   cost-centre/department tag (free-text in v1 — see §8 cost-centre assumption), lines (product, qty, optional
   estimated unit cost, note). Lifecycle **DRAFT → SUBMITTED → APPROVED → CONVERTED** (+ REJECTED, CANCELLED).
   A SUBMITTED PR goes through the **approvals engine**; an APPROVED PR can be **converted to a PO** (or fed
   into an RFQ). A PR reserves nothing, commits no money, posts no GL.

2. **RFQ → supplier quotes → comparison → award** — an `RFQ-####` solicitation: a list of products+quantities
   (optionally seeded from a PR), sent to one or more suppliers; each supplier's response is captured as a
   **supplier quote** (`SQ-####`: per-line unit price, lead time, validity); the buyer **compares** quotes
   side-by-side and **awards** a winning quote (or a per-line award across suppliers — see OQ-PROC-02), which
   **generates a PO** to the awarded supplier carrying the quoted prices. RFQ lifecycle **DRAFT → SENT →
   QUOTES_RECEIVED → AWARDED** (+ CANCELLED). Quotes are reference documents — no GL, no stock.

3. **PO approval (via the approvals engine)** — a PO whose `order_total_amount` exceeds a company-configured
   **approval threshold** cannot transition **DRAFT → ORDERED** until it is **APPROVED**. Below threshold, the
   PO places as today (no approval). The approval decision is owned by the **approvals engine** (§8); this
   slice provides the **gate** (block the ORDERED transition) and the threshold config, not the approval
   workflow itself.

4. **Landed cost allocation** — capture **landed-cost charges** (freight, duty, clearing, insurance, other)
   against **one or more goods receipts**, with an **allocation basis** (by value or by quantity across the
   receipt lines), which **capitalises the charge INTO each line's inventory value** — raising `on_hand_value`
   and recomputing `avg_cost` via the ADR-0020 engine, with a balancing GL entry (DR Inventory / CR a
   landed-cost clearing or AP, depending on whether the charge is accrued or billed). After allocation, the
   moving average and COGS reflect the landed cost.

5. **Purchase return / RMA → supplier debit note** — return received goods to a supplier against a goods
   receipt: `PRET-####`, return reason, lines (the returned qty, ≤ received-not-already-returned). Confirming
   the return **takes stock OUT at the original receipt cost** (reversing inventory/GRNI at the cost the
   receipt brought it in at — symmetric, no phantom gain/loss) and **raises a supplier debit note** (reusing
   `ApDebitNoteService`) that reduces what we owe the supplier. Partial returns allowed.

6. **Supplier price reference (lightweight)** — capturing a supplier quote stores the quoted unit cost so a
   PO/RFQ can default a line cost from the last quoted price for that (supplier, product). **Not** a full
   contract-pricing engine (deferred) — just a last-quoted-price reference read.

7. **Cross-cutting (every document):** `code_sequence` numbering (PR/RFQ/SQ/landed-cost/return kinds);
   per-company isolation (`company_id`); `branch_id` as the analysis/operating tag; `@perm`-gated controllers;
   `assertCanActIn` on every read path; audit on every state transition; additive Flyway (V32–V36) on the
   frozen V1–V19; outbox + `IdempotencyGuard` for any cross-module effect (the landed-cost capitalisation and
   the return stock-out are event-driven, mirroring the ADR-0020 / ADR-0021 stock seams).

### 2.2 Deferred (explicitly NOT in v1; none precluded)

- **Blanket / framework POs** (cumulative-limit, expiry) and **PO change orders / amendments** after placement.
- **Multi-step / parallel approval routing depth** (delegation, escalation, N-of-M approvers) — v1 uses the
  approvals engine's *single-policy threshold gate*; richer routing is the approvals engine's own roadmap.
- **Service / expense (non-stock) PO type** as a first-class flag — v1 keeps the existing goods-vs-service
  split at the **bill** level (the shipped `grLineUid` predicate, ADR-0020 D-9); a dedicated PO `po_type`
  GOODS|SERVICE is deferred (OQ-PROC-07).
- **GRNI granularity changes / phased-delivery ASNs / JIT auto-release / reorder auto-PO.**
- **Full supplier contract / price-list master** (tiered, effective-dated) — v1 is last-quoted-price only.
- **Supplier scorecards / disputes / compliance-certification gating / segmentation.**
- **Import/customs document management** (HS codes, EAC docs) beyond the duty/clearing landed-cost charge.
- **PO/RFQ PDF generation + supplier notifications/portal** (rides X.1/X.2 enablers).
- **Multi-currency procurement / FX on landed cost** — base currency only (BR-PROC-11).
- **Purchase commitment / encumbrance vs budget** — needs Budgeting (§3.14).
- **Cost-centre dimension as a real FK** — v1 tags the PR/PO with a free-text cost-centre code; the dimension
  framework (area 14) wires it to GL later (OQ-PROC-06).

---

## 3. Actors

- **Requester** — raises and submits a purchase requisition; needs `PURCHASE.REQUISITION.CREATE`. Typically a
  department user without buying authority.
- **Approver** — approves/rejects a submitted requisition and an over-threshold PO (via the approvals engine);
  needs `PURCHASE.REQUISITION.APPROVE` (PR) and the approvals-engine approver assignment (PO).
- **Buyer / Procurement officer** — runs RFQs, captures quotes, awards, creates/places POs, captures landed
  cost, processes returns; needs `PURCHASE.RFQ.*`, `PURCHASE.ORDER.*`, `PURCHASE.LANDEDCOST.*`,
  `PURCHASE.RETURN.*`.
- **AP clerk** — enters and matches supplier bills, including freight/duty bills that back landed-cost
  charges; existing AP perms.
- **ORG_ADMIN** — granted all new permissions by the migration seed (the V8/V12 pattern).

---

## 4. Functional requirements

> `FR-PROC-NN`. Each is a fixed v1 requirement. Mechanism/model/precision choices flagged `(ADR)` are
> ADR-0027's to make; the behaviour here is fixed.

### Purchase Requisition

- **FR-PROC-01** The system shall let a requester create a **draft requisition** (`PR-####` assigned at
  submit, NULL while DRAFT — the PO precedent) with a required-by date, an optional cost-centre code
  (free-text v1), and lines (product, requested qty, optional estimated unit cost, note).
- **FR-PROC-02** The system shall let the requester **submit** a draft requisition for approval (DRAFT →
  SUBMITTED), at which point it is sent to the **approvals engine** (§8). A DRAFT requisition is editable; a
  SUBMITTED one is read-only pending the decision.
- **FR-PROC-03** The system shall record the approval **outcome** on the requisition: APPROVED (→ APPROVED) or
  REJECTED (→ REJECTED, with the rejection reason). An APPROVED requisition is convertible; a REJECTED one is
  terminal (the requester re-raises a new PR).
- **FR-PROC-04** The system shall let a buyer **convert an APPROVED requisition** into either (a) a **PO**
  (copying lines; the buyer fills/confirms unit costs) or (b) an **RFQ** (copying product+qty lines to
  solicit). On conversion the PR moves to CONVERTED with a link to the produced document; conversion is
  idempotent-safe (a PR converts once — guarded).
- **FR-PROC-05** The system shall let a requester or buyer **cancel** a DRAFT or SUBMITTED requisition
  (→ CANCELLED, terminal); an APPROVED/CONVERTED one cannot be cancelled (it has downstream documents).

### RFQ / supplier quotes / award

- **FR-PROC-06** The system shall let a buyer create an **RFQ** (`RFQ-####`) with a list of product+quantity
  lines (optionally seeded from an APPROVED requisition) and a set of **target suppliers**.
- **FR-PROC-07** The system shall let the buyer mark the RFQ **SENT** (records that it was issued — actual
  email/PDF delivery is deferred to X.1/X.2; v1 records the act and the supplier set).
- **FR-PROC-08** The system shall capture, per target supplier, a **supplier quote** (`SQ-####`) with per-line
  unit price, optional lead-time days, and a validity date. Several quotes per RFQ (one per responding
  supplier).
- **FR-PROC-09** The system shall present a **comparison** of the captured quotes for an RFQ — per line, each
  supplier's unit price + lead time + computed line/total — so the buyer can choose.
- **FR-PROC-10** The system shall let the buyer **award** an RFQ: select a winning quote (whole-quote award in
  v1 default; per-line split award is `(ADR)` OQ-PROC-02), which **generates a PO** to the awarded supplier
  carrying the **quoted unit prices** as the PO line costs. The RFQ moves to AWARDED with a link to the PO.
- **FR-PROC-11** The system shall store the quoted unit cost per (supplier, product) such that a later RFQ or
  PO line **can default** the unit cost from the **most recent quote** for that supplier+product (the
  lightweight supplier-price reference — read-only convenience, BR-PROC-09).

### PO approval gate

- **FR-PROC-12** The system shall hold a configurable per-company **PO approval threshold** (a base-currency
  amount; a configured value of 0 means "all POs require approval"; absent/disabled means "no PO approval").
- **FR-PROC-13** The system shall **block** the PO **DRAFT → ORDERED** transition when the PO's
  `order_total_amount` is at-or-above the threshold and the PO is not APPROVED — the placement is rejected
  with a clear "requires approval" message; the PO stays DRAFT.
- **FR-PROC-14** The system shall let an authorised user **submit an over-threshold PO for approval** (to the
  approvals engine, §8) and, on an APPROVED outcome, allow the ORDERED transition; on REJECTED, the PO stays
  DRAFT (the buyer revises or voids). Below-threshold POs place with no approval (unchanged from today).
- **FR-PROC-15** The system shall record the approval state/decision reference on the PO (which approval
  request, the outcome, the approver, the timestamp) for audit.

### Landed cost

- **FR-PROC-16** The system shall let a buyer create a **landed-cost document** (`LC-####`) that names **one or
  more goods receipts** (same supplier or mixed, same company) and captures one or more **charge lines**
  (charge type ∈ FREIGHT|DUTY|CLEARING|INSURANCE|OTHER, amount, and whether the charge is **accrued** —
  awaiting a freight bill — or already **billed** — tied to an existing supplier bill uid).
- **FR-PROC-17** The system shall **allocate** each charge across the covered receipt lines by a chosen
  **basis** (BY_VALUE — pro-rata to each line's `line_cost_amount`; or BY_QUANTITY — pro-rata to each line's
  `qty_in_base`), producing a per-line landed-cost amount. The allocation basis is per landed-cost document
  (`(ADR)` — default BY_VALUE, OQ-PROC-03).
- **FR-PROC-18** On **confirm**, the system shall **capitalise** the allocated amount into inventory: raise
  each affected product's `on_hand_value` by its allocated share and **recompute `avg_cost`** (value increases,
  quantity unchanged) via the ADR-0020 engine, and post the balancing GL entry **DR Inventory / CR**
  (LANDED_COST_CLEARING if accrued, or directly against the freight supplier bill's expense/AP path if billed
  — `(ADR)` D-decision). The landed-cost capitalisation is **stock-side and idempotent** (mirrors the
  ADR-0020 receipt seam).
- **FR-PROC-19** The system shall reconcile a landed-cost accrual: when the freight/duty supplier bill arrives
  and is matched, the **LANDED_COST_CLEARING** balance is cleared (DR LANDED_COST_CLEARING / CR AP at bill
  match — mirrors the GRNI clear), netting to zero for fully-billed landed cost (BR-PROC-08).
- **FR-PROC-20** The system shall **block / warn** when a landed-cost allocation targets a product whose
  `avg_cost`/on-hand state cannot absorb it cleanly (e.g. the receipt was already fully sold — the goods left
  before the freight landed). v1 default: still post the capitalisation to `on_hand_value` (it may make the
  per-unit average reflect a residual), surfaced as a warning; the COGS-already-taken case is an accepted v1
  imprecision (`(ADR)` OQ-PROC-04).

### Purchase return / RMA → debit note

- **FR-PROC-21** The system shall let a buyer create a **purchase return** (`PRET-####`) against a goods
  receipt, with a reason and lines (the returned qty per receipt line, each ≤ received-minus-already-returned).
- **FR-PROC-22** On **confirm**, the system shall **take the returned stock OUT** at the **original receipt
  cost** (a stock movement reversing the receipt's inventory effect at the cost it came in at — reusing the
  ADR-0020 `reverseReceipt`/original-cost mechanism), posting **DR GRNI (if not yet billed) or DR AP-clearing /
  CR Inventory** at the original value `(ADR D-decision)`, and **raise a supplier debit note** (reusing
  `ApDebitNoteService`) for the returned value with a **PURCHASE_RETURN** origin.
- **FR-PROC-23** The system shall enforce that the cumulative returned quantity per receipt line never exceeds
  the received quantity (BR-PROC-10), enforced in the service and as a DB CHECK backstop.
- **FR-PROC-24** A confirmed purchase return is **immutable** (corrections are a new offsetting document — the
  append-only posture, BR-PROC-12); partial returns allowed (several returns against one receipt over time).

### Cross-cutting

- **FR-PROC-25** The system shall assign document numbers via `code_sequence` (lazy `entity_kind` rows):
  `PR-####`, `RFQ-####`, `SQ-####`, `LC-####`, `PRET-####`, allocation timing per OQ-PROC-05.
- **FR-PROC-26** The system shall audit every state transition (submit/approve/reject/convert/send/award/
  confirm/cancel) with the actor and the before/after state.
- **FR-PROC-27** All reads shall be company-scoped (`assertCanActIn`) and `@perm`-gated; all cross-module
  effects (landed-cost capitalisation, return stock-out) shall go through the transactional outbox under
  `IdempotencyGuard` (NFR-PROC-06).

---

## 5. Business rules

- **BR-PROC-01** Every procurement document carries `company_id`; cross-company reference is impossible. A PR,
  RFQ, quote, PO, landed-cost doc, and return all live in exactly one company.
- **BR-PROC-02** A requisition reserves nothing, commits no money, and posts no GL — it is a demand-and-approval
  artefact only. The first financial/commitment event is the PO (a commitment) and the GR (the first GL hit,
  via the existing GRNI accrual).
- **BR-PROC-03** A document number is assigned at the transition that makes the document "real": `PR-####` at
  submit, `RFQ-####` at create, `SQ-####` at quote-capture, `LC-####` at create, `PRET-####` at create
  (`(ADR)` OQ-PROC-05 fixes exact timing; DRAFT carries NULL where applicable, the PO precedent).
- **BR-PROC-04** A PR converts **once**: converting an APPROVED PR to a PO (or RFQ) is guarded so a second
  conversion is rejected. An RFQ awards **once** (a second award is rejected).
- **BR-PROC-05** The PO approval gate is **threshold-driven**: `order_total_amount >= threshold` AND not
  APPROVED ⇒ ORDERED blocked. The threshold is per-company config; below it, no approval is needed. (The
  threshold and decision are the approvals engine's; the *gate* is the PO service's.)
- **BR-PROC-06** Landed cost is **capitalised, not expensed**: an allocated charge raises `on_hand_value` and
  recomputes `avg_cost` (value up, quantity flat); it never posts to a P&L expense (except the residual
  COGS-already-taken edge, BR-PROC-07). The moving average after allocation reflects the landed cost.
- **BR-PROC-07** Landed cost allocated to **product already issued** (sold before freight landed) cannot
  retroactively change a posted COGS; v1 still raises `on_hand_value` on the residual on-hand (or leaves a
  carried value if zero on-hand) and surfaces the case as a warning. (Accepted v1 imprecision — OQ-PROC-04.)
- **BR-PROC-08** A landed-cost **accrual** (charge not yet billed) credits a **LANDED_COST_CLEARING** liability
  at capitalisation; the later freight/duty **supplier bill** debits LANDED_COST_CLEARING at match, netting to
  zero — the exact GRNI bridge pattern (ADR-0020). A landed cost marked **billed** at capture references the
  already-posted supplier bill and credits AP-clearing directly (`(ADR)` D-decision).
- **BR-PROC-09** A supplier quote's per-line unit cost is stored as the **last-quoted price** for (company,
  supplier, product); RFQ/PO lines may default from it (a read convenience). It is not authoritative pricing —
  the buyer always confirms the PO cost.
- **BR-PROC-10** A purchase return's cumulative returned qty per receipt line **never exceeds** the received
  qty for that line. The return reverses inventory/GRNI at the **original receipt cost** (symmetric — no
  phantom gain/loss), exactly as ADR-0020 reverses a receipt.
- **BR-PROC-11** Base currency only (TZS); all amounts `NUMERIC(19,4)`, HALF_UP. Multi-currency procurement +
  FX on landed cost are deferred (NFR-PROC-08).
- **BR-PROC-12** All procurement documents are **append-only once confirmed/posted** (a confirmed return, a
  posted landed-cost capitalisation, a placed PO line): corrections are new offsetting documents, never edits.
- **BR-PROC-13** Recipe-composed products on a return explode to components for the stock-out, exactly as the
  receipt/issue paths do (reuse `RecipeExplosionResolver`) — `(ADR)` confirms the symmetry.

---

## 6. Key flows

### 6.1 Happy path — requisition → RFQ → award → PO (approved) → receive → landed cost → bill → pay

1. Requester creates a **PR** (3 products, required-by Friday, cost-centre "WAREHOUSE"), **submits** it →
   PR-0007, SUBMITTED, sent to approvals.
2. Approver **approves** → PR-0007 APPROVED.
3. Buyer **converts PR → RFQ** (RFQ-0003) targeting 3 suppliers; marks it **SENT**.
4. Two suppliers respond; buyer **captures** their quotes (SQ-0009, SQ-0010) — per-line prices.
5. Buyer **compares**, **awards** SQ-0009 → generates **PO-0042** to the awarded supplier with the quoted
   prices; PO total = 8,000,000 TZS, **above the 5,000,000 threshold** → ORDERED blocked.
6. Buyer **submits PO-0042 for approval**; approver **approves** → PO placed (ORDERED), PO-#### assigned.
7. Goods arrive; buyer **receives** (GRN-0051) → stock in, **GRNI accrued** (DR Inventory / CR GRNI at PO cost).
8. Freight + duty invoices arrive; buyer creates a **landed-cost doc LC-0004** against GRN-0051: freight
   400,000 + duty 600,000, basis BY_VALUE, marked **accrued**; **confirms** → each product's `on_hand_value`
   rises by its share, `avg_cost` recomputes; GL **DR Inventory 1,000,000 / CR LANDED_COST_CLEARING 1,000,000**.
9. AP clerk **enters + matches** the goods bill (clears GRNI) and the **freight/duty bill** (clears
   LANDED_COST_CLEARING) → AP open items; **pays** in a payment run. Inventory now carries the **landed**
   cost; a later sale's COGS reflects it.

### 6.2 Happy path — purchase return → debit note

1. After receipt, 10 of 100 received units are damaged. Buyer creates **PRET-0002** against GRN-0051 line for
   qty 10, reason DAMAGE; **confirms**.
2. Stock goes **OUT** 10 units at the **original receipt cost** (DR GRNI / CR Inventory at original value —
   GRNI not yet billed); on-hand and `on_hand_value` fall symmetrically; `avg_cost` recomputes as the inverse
   of the receipt.
3. A **supplier debit note** (DEBIT-####, origin PURCHASE_RETURN) is raised for the returned value, reducing
   what we owe the supplier (the AP credit). PRET-0002 CONFIRMED, immutable.

### 6.3 Unhappy paths

- **U-1 (PR rejected):** approver rejects PR-0007 with reason "over budget" → REJECTED, terminal; cannot
  convert; requester raises a fresh PR. *(FR-PROC-03, FR-PROC-05.)*
- **U-2 (over-threshold PO placed without approval):** buyer tries to place PO-0042 (8M > 5M threshold) without
  approval → rejected "requires approval", PO stays DRAFT. *(FR-PROC-13.)*
- **U-3 (double conversion / double award):** buyer converts PR-0007 a second time, or awards RFQ-0003 a second
  time → rejected (already converted/awarded). *(BR-PROC-04.)*
- **U-4 (over-return):** return 15 units against a receipt line that received 10 (or already returned 5 of 10
  → trying 7) → rejected; cumulative returned ≤ received enforced. *(FR-PROC-23, BR-PROC-10.)*
- **U-5 (landed cost on fully-sold goods):** freight lands after all received units were sold; capitalisation
  raises `on_hand_value` on a zero/residual on-hand → posts with a warning; posted COGS is unchanged (accepted
  imprecision). *(BR-PROC-07, FR-PROC-20.)*
- **U-6 (landed-cost double-confirm / event redelivery):** a re-delivered landed-cost capitalisation event is
  skipped by `IdempotencyGuard`; the GL/value effect posts exactly once. *(NFR-PROC-06.)*
- **U-7 (approvals engine absent):** if the approvals engine is not deployed, the PO gate falls back to a
  **permission-only** approval (a holder of `PURCHASE.ORDER.APPROVE` flips the PO to APPROVED) — see §8.

---

## 7. Non-functional requirements

- **NFR-PROC-01** Reconciliation integrity: landed cost capitalised into inventory keeps `Σ on_hand_value`
  tied to the GL 1300 Inventory balance (the ADR-0020 recon bar must still hold after a landed-cost post and
  after a return). A landed-cost or return that breaks the recon is a finance-grade defect.
- **NFR-PROC-02** Idempotency: every cross-module effect (landed-cost capitalisation, return stock-out, the
  GL legs) is idempotent under `IdempotencyGuard` + the `uq_stock_movement_source_event` DB backstop — a
  redelivered event never double-capitalises or double-reverses.
- **NFR-PROC-03** Numbering is concurrency-safe (`code_sequence` row-lock); no duplicate PR/RFQ/SQ/LC/PRET
  numbers per company under concurrency; the `uq_<doc>_company_number` constraints backstop.
- **NFR-PROC-04** Every state transition is audited (actor, before/after, timestamp); confirmed/posted
  documents are append-only.
- **NFR-PROC-05** Reads are paginated and indexed for the working sets (open requisitions awaiting approval,
  open RFQs awaiting quotes, receipts awaiting landed cost, returns).
- **NFR-PROC-06** Concurrency on `stock_on_hand` (landed-cost capitalisation, return stock-out) uses the
  existing `@Version` optimistic lock + one-retry mechanism (ADR-0020 NFR-INV-05) — no new lock.
- **NFR-PROC-07** Additive migrations only (V32–V36); V1–V19 frozen; `MigrationKeepDataIT` extends to cover
  any per-company seed (#12-safe seed-uids — never raw-key concat).
- **NFR-PROC-08** Base currency only; multi-currency + FX deferred, not precluded (the model accepts a
  currency dimension additively).
- **NFR-PROC-09** Module boundaries hold: `purchases` reaches `stock`/`ap`/`gl` only by DTO + service-call +
  outbox (no cross-module entity import); `ModuleBoundaryTest` passes with no new cycle.

---

## 8. The approvals dependency (load-bearing — read before building)

This slice **depends on the approvals engine (X.5), which is not yet built.** Two documents need approval: the
**requisition** (always, when submitted) and the **over-threshold PO**. The architecture must integrate with
the approvals engine *as a contract*, not duplicate an approval workflow inside `purchases`.

**Assumed approvals-engine contract (to be confirmed when X.5 is specified — OQ-PROC-01):**

- An `ApprovalService` (platform or its own module) exposing roughly:
  - `submit(documentType, documentUid, companyId, amount, requestedBy) → ApprovalRequestDto` — opens an
    approval request against a **policy** keyed by `documentType` (`PURCHASE_REQUISITION`, `PURCHASE_ORDER`)
    and the `amount` (threshold tiers).
  - `decisionOf(documentType, documentUid) → APPROVED | REJECTED | PENDING | NOT_REQUIRED` — the current state.
  - An **outcome signal** (an outbox event `APPROVAL.DECIDED` or a synchronous return) the document's module
    consumes to advance/block the document (the requisition → APPROVED/REJECTED; the PO → allow/deny ORDERED).
- `purchases` stores only a **scalar `approval_request_uid`** + a cached `approval_status` on the PR/PO (no
  cross-module FK), and **gates** its transitions on `decisionOf(...)` / the outcome event.

**Fallback if approvals does NOT land before this slice (the build must not be blocked):**

- Ship a **degenerate in-module gate**: a permission-only approval. The requisition `SUBMITTED → APPROVED`
  and the over-threshold PO `→ APPROVED` are performed by a holder of `PURCHASE.REQUISITION.APPROVE` /
  `PURCHASE.ORDER.APPROVE` (a single-step manual approve action on the document), with the `approval_status`
  column set in-module. The **threshold config + the gate logic are identical**; only the *decision source*
  differs (a permission-gated manual flip vs the approvals engine's policy). When approvals lands, the manual
  flip is replaced by the `ApprovalService` call **without a schema change** (the `approval_request_uid` /
  `approval_status` columns already exist). This is the recommended build posture: **build the gate + the
  threshold + the columns now; wire the engine when it exists.** *(ADR-0027 decides this seam.)*

---

## 9. Open questions (recommended defaults adopted; load-bearing ones flagged ★)

- **★ OQ-PROC-01 — approvals-engine contract & sequencing.** The exact `ApprovalService` shape and whether it
  lands before this slice. **Recommended default:** build the **in-module permission-gated approval + the
  threshold config + the `approval_request_uid`/`approval_status` columns** now (§8 fallback); swap to the
  engine when X.5 ships, no schema change. *Load-bearing for sequencing — confirm with PM.*
- **★ OQ-PROC-02 — RFQ award granularity.** Whole-quote award (one supplier wins the whole RFQ) vs per-line
  split award (best price per line across suppliers → possibly several POs). **Recommended default:
  whole-quote award in v1** (one RFQ → one PO to one supplier); per-line split is deferred. *Load-bearing for
  the RFQ→PO conversion shape.*
- **★ OQ-PROC-03 — landed-cost allocation basis.** BY_VALUE (pro-rata to line cost) vs BY_QUANTITY vs BY_WEIGHT
  vs manual. **Recommended default: BY_VALUE, with BY_QUANTITY selectable per document**; BY_WEIGHT/manual
  deferred (needs a weight attribute on products). *Load-bearing for the capitalisation maths.*
- **★ OQ-PROC-04 — landed cost on already-issued goods.** Retroactively adjust posted COGS vs capitalise to
  residual on-hand only (with a warning) vs expense the un-capitalisable remainder. **Recommended default:
  capitalise to on-hand, warn on the residual, do not touch posted COGS** (accepted v1 imprecision,
  BR-PROC-07). *Load-bearing for the valuation correctness story.*
- **OQ-PROC-05 — document numbering timing.** PR at submit, RFQ/SQ/LC/PRET at create. *Default adopted; cheap
  to flip.*
- **OQ-PROC-06 — cost-centre dimension.** Free-text cost-centre code on PR/PO in v1 vs a real cost-centre FK.
  **Default: free-text now**; wire to the dimension framework (area 14) later. *Not blocking.*
- **OQ-PROC-07 — service/expense PO type.** A first-class `po_type` GOODS|SERVICE vs the existing bill-level
  `grLineUid` goods/service split. **Default: keep the bill-level split (ADR-0020 D-9), no PO-type flag in
  v1.** *Not blocking.*
- **OQ-PROC-08 — landed-cost billed-vs-accrued at capture.** Whether a landed-cost charge must reference an
  existing supplier bill or may be a pure accrual cleared later. **Default: support both** — accrued (CR
  LANDED_COST_CLEARING, cleared at the freight bill match) and billed (reference the bill uid). *Default
  adopted; ADR fixes the GL legs.*
- **OQ-PROC-09 — return cost basis for partial / multi-component.** Pro-rata from the original receipt
  movement rows vs the denormalised receipt-line cost. **Default: read the original GOODS_RECEIPT movement
  rows as authoritative** (the ADR-0020 reversal precedent); apportion for partials. *Default adopted.*
- **OQ-CUR-03 (carried) — display precision.** HALF_UP, TZS 0-dp display, `NUMERIC(19,4)` internal. Confirm
  before go-live; does not block the model.
