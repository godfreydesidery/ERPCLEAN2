# Requirements — Purchases (buying from suppliers)

> Status: **Ratified (owner-confirmed 2026-06-07).** Built **together with Stock** this round (owner
> ruling: stock-in is the real goods-receipt from a purchase, from day one). The owner has confirmed
> the round-level decisions and every Purchases second-order choice (OQ-PURCH-01..08, full log in
> [open-questions.md](open-questions.md)). **The headline owner ruling: v1 Purchases is a TWO-DOCUMENT
> flow — a Purchase Order (PO) raised first, then a separate Goods Receipt (GR/GRN) recorded against
> the PO** (NOT a single-step GRN). The Goods Receipt is what pushes stock IN. No ADR-blocking question
> remains; the solutions-architect may write **ADR-0010 (Purchases)** on this confirmed scope.
>
> Author: system-analyst · Domain: `purchases` (operational / transactional). **Business-level spec
> only — no schema, no API shapes, no tables, no code.** Those are the solutions-architect's, in
> **ADR-0010** (next step). Do not infer a data model from this document.
>
> **Depends on:** IAM (org → company → branch, permissions, `RequestContext`, audit, the generic
> `code_sequence` numbering primitive — ADR-0007 D-6); Parties (**Supplier** master already exists —
> FR-PARTY-02/07, goods/service sub-kinds); Products (the catalogue — stockable flag, base unit,
> bulk-pack conversion FR-PROD-06); Multicurrency (ADR-0005 — money = amount + currency; purchases are
> **money-bearing** even though Stock v1 is quantity-only, §6). Mirrors the Sales document shape
> (header → lines, `code_sequence` numbering, per-company + multi-branch scope, audit) — see
> [sales.md](sales.md) / ADR-0008.
>
> **Sibling module:** [stock.md](stock.md) — the **Goods Receipt** here pushes stock **IN** via the
> outbox (the `STOCK.RECEIVED` event), built this round under the **platform-outbox ADR** (OQ-STOCK-09
> RESOLVED).

## 1. Business context & why now

The company **buys** to **sell**. Stock-on-hand only rises because goods are **received** from a
supplier — so a Stock module with no way to bring goods in is half a module. The owner ruled that
**Purchases is built with Stock this round** so that stock-in is the **real goods-receipt from a
purchase, from day one** — not a synthetic seed. A purchase records **what we bought, from whom, in
what quantity, at what cost**, and **receiving** those goods is the event that increases inventory.

**The owner ruled v1 Purchases is a two-document flow (OQ-PURCH-01 RESOLVED = PO + separate receipt).**
A **Purchase Order (PO)** is raised first — a commitment to buy from a supplier, with ordered
quantities and costs. Then a separate **Goods Receipt (GR/GRN)** is recorded **against that PO**,
receiving some or all of the ordered quantity. **The Goods Receipt is the event that pushes stock IN**
(it emits the `STOCK.RECEIVED` outbox event Stock consumes). Receiving may be **partial** — multiple
Goods Receipts can be recorded against one PO until the ordered quantity is fully received — so the
system tracks **received-vs-ordered** (the outstanding quantity per PO line). This is the bigger,
ordering-first flow the owner chose over the single-step GRN.

Everything Purchases needs already exists:

- **Who we buy from** — the **Supplier** master (Parties FR-PARTY-02), with goods/service sub-kinds
  (FR-PARTY-07). A goods supplier feeds Purchases → Stock; a service supplier supplies non-stockable
  services (no stock movement).
- **What we buy** — the **Product** catalogue (Products), with stockable flag, base unit, and
  bulk-pack conversion (FR-PROD-05/06). A purchase line names a product, a quantity, a unit, and a
  cost.
- **Money** — every cost amount is amount + currency (ADR-0005); document currency = company base
  (TZS) in practice for v1 (multicurrency.md §5). Purchases are money-bearing **even though Stock v1
  is quantity-only** — the cost is recorded on the purchase but is **not** rolled into stock value
  in v1 (§6, stock.md §10).
- **Numbering** — the generic `code_sequence` primitive (ADR-0007 D-6) the system already uses for
  product/party/invoice numbers; Purchases adds a new `entity_kind` for its document series, minting
  no new counter (FR-PURCH-12).
- **The cross-module link to Stock** — the **transactional outbox** (ARCHITECTURE.md §9, built this
  round under its own platform ADR, OQ-STOCK-09 RESOLVED, stock.md §3.4): a **Goods Receipt** writes a
  **`STOCK.RECEIVED`** event in the same transaction; Stock consumes it and posts a `GOODS_RECEIPT`
  movement (stock.md FR-STOCK-06). At-least-once delivery + consumer-side idempotency (dedupe on
  event id).

### Vocabulary distinction (read this first)

- **Purchase** — the act and record of buying products from a supplier. The umbrella term covering
  the PO and the Goods Receipt(s) raised against it.
- **Purchase Order (PO)** — the **v1 ordering document**: a commitment to buy from a supplier, raised
  **before** the goods arrive. It carries the supplier and **ordered lines** (product × ordered-qty ×
  unit × unit-cost). A PO **moves no stock** — it is the intent to buy. Lifecycle DRAFT → ORDERED →
  (partially/fully) RECEIVED → CLOSED / VOID (FR-PURCH-02a). Numbered `PO-####` (OQ-PURCH-03 RESOLVED).
- **Goods Receipt (GR / GRN)** — the **v1 receiving document**: it records the actual arrival of goods
  **against a PO**, receiving some or all of the ordered quantity. **The Goods Receipt is what pushes
  stock IN** — finalising it emits the `STOCK.RECEIVED` outbox event Stock consumes (FR-PURCH-08).
  Lifecycle DRAFT → RECEIVED → VOID (FR-PURCH-02b). Numbered `GRN-####`, assigned at receive
  (OQ-PURCH-03 RESOLVED).
- **PO line** — one ordered product on a PO: product, **ordered quantity** (in a chosen base/bulk
  unit), **unit**, **unit cost** (a monetary amount), line total. Quantity converts to base per
  Products FR-PROD-06. Each PO line tracks an **outstanding quantity** (ordered − cumulative received).
- **GR line** — one received product on a Goods Receipt: a (subset of a) PO line, with a **received
  quantity** ≤ the PO line's outstanding quantity, in base units for the stock effect.
- **Partial receipt** — receiving **less than** the ordered quantity on a PO line in one Goods
  Receipt; the remainder stays **outstanding** and can be received on a later Goods Receipt against the
  same PO, until the line is fully received (FR-PURCH-07).
- **Outstanding quantity** — per PO line, the ordered quantity **minus** the cumulative quantity
  received across all (non-void) Goods Receipts against that line. Drives the PO's RECEIVED/CLOSED
  state.
- **Goods receipt (the act)** — receiving purchased goods into inventory: the event that **pushes
  stock IN** (a `GOODS_RECEIPT` movement, via the `STOCK.RECEIVED` outbox event). In v1 this is the
  act of finalising a **Goods Receipt** document against a PO.
- **Supplier invoice / bill** — the supplier's **demand for payment** (an accounts-payable document),
  distinct from the PO and the Goods Receipt. **DEFERRED in v1** (AP/Finance, OQ-PURCH-05). v1 records
  the **purchase cost** on the PO / GR but creates **no payable, no payment, no AP ageing, no 3-way
  match**.
- **Return to supplier / debit note** — sending goods back to a supplier and reversing the receipt.
  **DEFERRED in v1** (OQ-PURCH-06); the v1 correction path is a permissioned **void** of the Goods
  Receipt.
- **Void** — a permissioned reversal of a finalised Goods Receipt (and its stock-in), or of a PO,
  mirroring the Sales void. The v1 correction path.
- **Cost** — the purchase price the supplier charges, a **monetary amount** (amount + currency,
  ADR-0005). Recorded on the PO/GR line; **not** turned into a stock valuation in v1 (§6).
- **Landed cost** — the full cost of getting goods to the shelf (purchase price + freight + duty +
  insurance). **DEFERRED** (OQ-PURCH-07) — v1 records the supplier's line cost only.
- **3-way match** — matching PO ↔ Goods Receipt ↔ supplier invoice before paying. **DEFERRED in v1**
  (lands with AP, OQ-PURCH-05); v1 builds the PO ↔ Goods Receipt match (ordered-vs-received) only, no
  invoice leg.

## 2. Scope

> Lines tagged **[RATIFIED]** are owner-confirmed decisions (2026-06-07). With the OQ rulings now in,
> **every line in this section is owner-confirmed** — the `[OQ-PURCH-NN]` tag remains only as a
> cross-reference to the resolved log entry, not as an open question.

### In scope (v1 — "order from a supplier, then receive goods against the order and push stock in")

- **[RATIFIED] Two purchase documents — a Purchase Order (PO), then a separate Goods Receipt (GR/GRN).**
  A PO is raised first (the commitment to buy); a Goods Receipt is then recorded **against** the PO to
  receive goods (OQ-PURCH-01 RESOLVED = PO + separate receipt). Both support create, view, list/search,
  and a lifecycle (FR-PURCH-01a / FR-PURCH-01b).
- **[RATIFIED] A PO is raised first** — from one supplier (Parties Supplier master), with **ordered
  lines** (product × ordered-qty × unit × unit-cost). A PO **moves no stock**; it is the intent to buy
  (FR-PURCH-01a, FR-PURCH-03, FR-PURCH-04).
- **[RATIFIED] A Goods Receipt is recorded against a PO** — receiving some or all of the ordered
  quantity. **Finalising a Goods Receipt pushes stock IN** by emitting a **`STOCK.RECEIVED`** outbox
  event that **Stock** consumes to post a `GOODS_RECEIPT` movement and increment on-hand (stock.md
  FR-STOCK-06) — the **real** stock-in, not a stub (FR-PURCH-01b, FR-PURCH-08).
- **[RATIFIED] Partial receipts + received-vs-ordered tracking.** Multiple Goods Receipts may be
  recorded against one PO until the ordered quantity is fully received; each PO line tracks an
  **outstanding quantity** (ordered − cumulative received). A receipt cannot exceed a line's
  outstanding quantity (FR-PURCH-07, BR-PURCH-10).
- **[RATIFIED] PO lifecycle: DRAFT → ORDERED → (partially/fully) RECEIVED → CLOSED / VOID.** A draft is
  freely editable and holds no number; **placing the order** assigns `PO-####` and freezes the ordered
  lines; receiving advances the PO to RECEIVED (partial or full); CLOSED ends the PO; VOID cancels it
  (FR-PURCH-02a). `[OQ-PURCH-02 RESOLVED]`
- **[RATIFIED] Goods Receipt lifecycle: DRAFT → RECEIVED → VOID.** A draft GR moves no stock and holds
  no number; **receiving (finalising)** assigns `GRN-####`, freezes the GR, and emits `STOCK.RECEIVED`;
  a permissioned **void** reverses a received GR and its stock-in (FR-PURCH-02b, FR-PURCH-09).
  `[OQ-PURCH-02 RESOLVED]`
- **[RATIFIED] Cost is recorded, money-aware** — every unit cost and total (on the PO and the GR)
  carries its currency (ADR-0005); document currency = company base in practice (FR-PURCH-06). **No
  tax/VAT computation on purchases in v1** (input-VAT recovery is a Finance/AP concern); **cost is
  required on a goods line** (OQ-PURCH-04 RESOLVED).
- **[RATIFIED] Document numbering** via the generic `code_sequence`: **PO `PO-####`** (entity_kind e.g.
  `PURCHASE_ORDER`, assigned at order-placement) and **Goods Receipt `GRN-####`** (entity_kind e.g.
  `GOODS_RECEIPT`, assigned at receive), both a **single per-company series**, concurrency-safe —
  mirroring Sales `INV-####` (FR-PURCH-12). `[OQ-PURCH-03 RESOLVED]`
- **[RATIFIED] Per-company + multi-branch scope, permission-gated, audited** — consistent with Sales
  (FR-PURCH-10/11, NFR-PURCH-01/03).
- **[RATIFIED] Quantity-only into stock, cost on the documents.** The PO/GR record cost (money) but the
  stock-in a Goods Receipt drives is **quantity-only** (Stock v1 is quantity-only, stock.md §10); no
  stock valuation is computed in v1 (FR-PURCH-13).

### Deferred (recognised, NOT built in v1)

- **Multi-step PO approval workflow** — approval thresholds / authorisation chains before a PO is
  placed. v1 has the PO lifecycle but **no approval gate** beyond the create/place permission (a
  single approver/placer); a richer approval chain is deferred (OQ-PURCH-01 RESOLVED scope note).
- **3-way match (PO ↔ Goods Receipt ↔ supplier invoice)** — v1 builds the **PO ↔ Goods Receipt** match
  (ordered-vs-received, outstanding qty) only; the **supplier-invoice leg** is deferred with AP
  (OQ-PURCH-05).
- **Supplier invoices / accounts payable / payments** — recording the supplier's bill, matching it to
  the receipt, ageing the payable, and paying it. **Deferred to a Finance-aware round** (OQ-PURCH-05);
  v1 records the **purchase cost** on the PO/GR but creates **no payable and takes no payment**. (Mirror
  of Sales deferring credit/receivables.)
- **Returns to supplier / debit notes** — sending goods back and reversing the receipt (partial or
  full), with the supplier-side credit. **Deferred** (OQ-PURCH-06); the v1 correction is a void of the
  Goods Receipt.
- **Landed cost** — apportioning freight / duty / insurance across received lines to a true landed
  cost. **Deferred** (OQ-PURCH-07); v1 records the supplier's line cost only. (Tied to the deferred
  valuation work in Stock, stock.md §10.)
- **Purchase VAT / input-tax recovery** — computing recoverable input VAT on purchases. **Deferred**
  (OQ-PURCH-04 RESOLVED, Finance/AP); v1 records cost without a VAT computation.
- **Service purchases / expense capture** — buying a non-stockable service (transport, utilities)
  that moves no stock. **NOT in v1** (OQ-PURCH-08 RESOLVED) — v1 PO/GR are for **goods that move
  stock**; expense/service purchases are deferred to AP/expenses. (A service supplier exists in
  Parties; the purchase of its service is the deferred expense flow.)
- **Foreign-currency purchase operations** — a foreign-currency cost capability is **reserved** (the
  cost carries its currency, ADR-0005) but day-one purchases are base-currency; FX operations are
  deferred (multicurrency.md §5/§8).

### Explicitly NOT this module

- **Stock-on-hand & movements** — the sibling **Stock** module. Purchases *produces* the
  `STOCK.RECEIVED` event; Stock *consumes* it and owns on-hand/movements.
- **The supplier master itself** — owned by **Parties** (FR-PARTY-02); Purchases consumes its DTO.
- **The product catalogue** — owned by **Products**; Purchases references products, never defines
  them.
- **Accounts payable / supplier payments / the general ledger** — the future **Finance** module.
  Purchases *produces* the cost facts; Finance will *post* and *pay* them (mirrors the Sales →
  receivables handoff).
- **Stock valuation / costing** — deferred with Stock (stock.md §10). Purchases records cost; it does
  not value inventory.

## 3. The two purchase documents and their v1 shape

### 3.1 The purchase spine (two documents, one match)

v1 Purchases is a **two-document flow** (owner ruling, OQ-PURCH-01 RESOLVED):

- A **Purchase Order (PO)** — header carrying company + branch scope, a **supplier**, a **document
  currency**, a **status**, a **document number** (`PO-####`), an **audit trail**, and one or more
  **PO lines** (product, ordered-qty, unit, unit cost, line total). The PO **moves no stock**.
- A **Goods Receipt (GR/GRN)** — header carrying company + branch scope, a **reference to its PO**, a
  **status**, a **document number** (`GRN-####`), an **audit trail**, and one or more **GR lines**
  (each against a PO line, received-qty ≤ that PO line's outstanding qty). **Finalising a GR pushes
  stock IN.**

Each is the same header → lines shape as the Sales Invoice. The **link between them** is the PO ↔
Goods Receipt match: a PO line's **outstanding quantity** = ordered − cumulative received across its
(non-void) Goods Receipts. The match drives the PO's RECEIVED/CLOSED state (the v1 two-way match; the
supplier-invoice leg of a 3-way match is deferred, OQ-PURCH-05).

### 3.2 v1 flow — order first, then receive (one or many times)

The owner chose the **bigger, ordering-first** flow over a single-step GRN: a PO captures the
commitment to buy; one or more Goods Receipts then record actual arrivals against it. This supports
**partial receipts** (a supplier delivers in instalments) and gives the business a record of what was
**ordered but not yet received** (outstanding). It mirrors a real procurement cycle (raise order →
goods arrive → receive against order) rather than collapsing both into one action.

### 3.3 Document interplay (v1) and the deferred richer flow

| Document | Role in v1 | Stock effect |
|---|---|---|
| **Purchase Order (PO)** | Raised first; the commitment to buy; ordered lines; tracks outstanding qty | **None** — a PO moves no stock. |
| **Goods Receipt (GR/GRN)** | Recorded **against** a PO; receives some/all of the ordered qty; partial receipts allowed | **Pushes stock IN** — emits `STOCK.RECEIVED`; Stock posts `GOODS_RECEIPT` (FR-PURCH-08). |

| Deferred over the v1 two-document flow | What it adds | Why deferred |
|---|---|---|
| **Multi-step PO approval** | Approval thresholds / authorisation chains before a PO is placed | v1 gates PO placement by permission only; an approval chain is additive (OQ-PURCH-01 scope note). |
| **Supplier invoice / AP + 3-way match** | The supplier's bill; 3-way match (PO ↔ receipt ↔ invoice); payable + payment + ageing | Finance/AP territory; lands with a Finance-aware round (OQ-PURCH-05). v1 records cost, owes nothing, matches PO ↔ receipt only. |
| **Returns to supplier / debit notes** | Sending goods back, reversing the receipt with a supplier credit | Deferred (OQ-PURCH-06); v1 correction is a void of the Goods Receipt. |

## 4. Actors / personas

- **Purchasing officer / buyer (branch operator)** — raises a **Purchase Order**, selects supplier +
  products, enters ordered quantities and costs, **places the order** (ORDERED). Sees only their active
  branch's suppliers and products. Holds a `PURCHASE.CREATE`-style permission.
- **Storekeeper / receiving clerk (branch operator)** — records a **Goods Receipt** against a PO when
  goods arrive, entering received quantities (≤ outstanding), and **receives** (finalises) it — the act
  that pushes stock IN. Holds a `PURCHASE.RECEIVE`-style permission. (In a small branch the same person
  may both order and receive.)
- **Branch manager / supervisor** — may void a finalised Goods Receipt (`PURCHASE.VOID`) or a PO;
  reviews purchases and outstanding orders. (A multi-step PO-approval role is deferred, OQ-PURCH-01.)
- **System (event producer/consumer)** — Purchases *writes* the `STOCK.RECEIVED` outbox event when a
  Goods Receipt is finalised; Stock's consumer *reads* it. The producer side records the Goods Receipt
  as the event's source.
- **Finance / accounts-payable user** — will later consume the PO/GR cost as a payable, match a
  supplier invoice (the 3rd leg), and pay it (deferred, OQ-PURCH-05). Named here for the AP handoff.

## 5. Functional requirements

> IDs are `FR-PURCH-NN`. Each is a crisp, testable statement. All values below are **owner-confirmed**
> (the `[OQ-PURCH-NN]` references point to the resolved log entry). "PO" = Purchase Order; "GR/GRN" =
> Goods Receipt.

### Core records & lifecycle (two documents)

- **FR-PURCH-01a** The system maintains a **Purchase Order (PO)** document — the v1 **ordering**
  document: create, view, list/search, and progress through a lifecycle. A PO carries company + branch
  scope, a supplier, **PO lines** (product, ordered-qty, unit, unit cost, line total), currency, total
  cost, status, a document number (`PO-####`), and audit. **A PO moves no stock** (OQ-PURCH-01
  RESOLVED).
- **FR-PURCH-01b** The system maintains a **Goods Receipt (GR/GRN)** document — the v1 **receiving**
  document, recorded **against a PO**: create, view, list/search, and a lifecycle. A GR carries company
  + branch scope, a **reference to its PO**, **GR lines** (each against a PO line, with a received
  quantity), status, a document number (`GRN-####`), and audit. **Finalising a GR pushes stock IN**
  (FR-PURCH-08).
- **FR-PURCH-02a** A PO has a **status lifecycle**: **DRAFT** (editable, no number) → **ORDERED**
  (numbered `PO-####`, ordered lines frozen, awaiting goods) → **RECEIVED** (partially or fully — driven
  by the cumulative received quantity against its lines, FR-PURCH-07) → **CLOSED** (no further receipts
  expected) and **VOID** (PO cancelled). A draft may be freely edited or discarded; placing the order
  freezes the ordered lines. `[OQ-PURCH-02 RESOLVED]`
- **FR-PURCH-02b** A GR has a **status lifecycle**: **DRAFT** (editable, no number, no stock moved) →
  **RECEIVED** (numbered `GRN-####`, frozen, stock pushed in) → **VOID** (received GR reversed). A draft
  may be freely edited or discarded; finalising = **receiving** the goods. `[OQ-PURCH-02 RESOLVED]`
- **FR-PURCH-03** A PO is **from exactly one supplier**, selected from suppliers associated with the
  active branch (Parties FR-PARTY-12). The supplier must be in the same company as the PO (BR-PURCH-02).
  A Goods Receipt inherits the supplier from its PO.

### Lines, products, units & the PO ↔ receipt match

- **FR-PURCH-04** A PO has **one or more PO lines**. Each line names a product associated with the
  active branch (Products FR-PROD-22), an **ordered quantity**, and the **unit** (base or a bulk pack)
  the quantity is expressed in; quantity converts to base per Products FR-PROD-06 (so a Goods Receipt
  against the line, and the `STOCK.RECEIVED` event, carry `qtyInBase`, FR-PURCH-08).
- **FR-PURCH-05** Each PO line carries a **unit cost** (a monetary amount, ADR-0005); the line total =
  unit cost × ordered quantity, and the PO totals the lines. Recording cost is **required** on a goods
  line (a purchase has a price); a zero cost is allowed only for a free/sample line with a reason
  (OQ-PURCH-04 RESOLVED). A GR line inherits its PO line's unit cost.
- **FR-PURCH-07** **Partial receipts + outstanding tracking.** A Goods Receipt records, per GR line, a
  **received quantity** against a specific PO line; the received quantity **must not exceed** that PO
  line's **outstanding quantity** (ordered − cumulative received across non-void GRs, BR-PURCH-10).
  Multiple Goods Receipts may be recorded against one PO until every line is fully received; the PO
  reaches **fully RECEIVED** when all lines' outstanding quantity is zero, and is **partially RECEIVED**
  while some remains. `[OQ-PURCH-01 RESOLVED — partial receipts]`

### Cost & currency

- **FR-PURCH-06** Every cost amount on a PO or GR (unit cost, line total, document total) **carries its
  currency** (ADR-0005 / BR-CUR-01); all amounts on one document are in the **document currency**
  (BR-CUR-07). Document currency = company base (TZS) in practice for v1; the foreign-currency
  capability is reserved (FR-CUR-08), not exercised.
- **FR-PURCH-13** v1 records cost on the PO/GR but **does NOT compute stock valuation or any VAT/tax** on
  the purchase: the stock-in a Goods Receipt drives is **quantity-only** (Stock v1, stock.md §10), and
  input-VAT recovery / costing are deferred (OQ-PURCH-04 RESOLVED, OQ-PURCH-07). Cost is captured **for
  the record and for the future valuation/AP rounds**, not to value inventory now. The v1 model must
  not preclude later cost-into-valuation (NFR-PURCH-05).

### Receiving → stock-in (the cross-module effect)

- **FR-PURCH-08** **Finalising (receiving) a Goods Receipt emits a `STOCK.RECEIVED` outbox event** in
  the **same transaction**, carrying company + branch + lines of `{ productId, productUid, unitId,
  qtyInBase }` (mirroring the `SALE.FINALISED` payload shape, ADR-0008 D-9). The **Stock** module
  consumes it and posts a `GOODS_RECEIPT` in-movement, incrementing on-hand (stock.md FR-STOCK-06).
  This is the **real** stock-in from day one (RATIFIED). A **non-stockable** product line emits **no**
  stock effect (Stock skips it, stock.md BR-STOCK-02). The outbox is built this round under its own
  platform ADR (OQ-STOCK-09 RESOLVED); delivery is at-least-once with consumer-side idempotency.
- **FR-PURCH-09** **Void** of a received Goods Receipt (`PURCHASE.VOID`) reverses the receipt and emits
  a compensating event so Stock reverses the `GOODS_RECEIPT` (a reversing in/out movement), audited.
  The GR and its number are **retained** (void ≠ delete); voiding a GR **restores** the received
  quantity to its PO lines' outstanding (so the PO can be re-received). `[OQ-PURCH-06 RESOLVED — full
  returns deferred]`

### Numbering, scope & permissions

- **FR-PURCH-12** Each document has a **number unique within its company**: a PO from a **single
  per-company series `PO-####`** (entity_kind e.g. `PURCHASE_ORDER`, assigned at order-placement) and a
  Goods Receipt from a **single per-company series `GRN-####`** (entity_kind e.g. `GOODS_RECEIPT`,
  assigned at receive). Both allocate concurrency-safe from the generic `code_sequence` primitive —
  Purchases mints **no** new counter, mirroring Sales (ADR-0008 D-7) and Products. Drafts hold no
  number. `[OQ-PURCH-03 RESOLVED]`
- **FR-PURCH-10** Purchases are **scoped per company and filtered by the active branch**: an operator
  sees and creates POs/GRs only in their active branch; supplier/product selection is branch-filtered
  (Parties FR-PARTY-12, Products FR-PROD-22). Cross-company/branch leakage is a release blocker
  (NFR-PURCH-01).
- **FR-PURCH-11** All purchase operations are **gated by IAM permissions** (e.g. `PURCHASE.CREATE` to
  raise/place a PO, `PURCHASE.RECEIVE` to record/finalise a Goods Receipt, `PURCHASE.VIEW`,
  `PURCHASE.VOID`). Exact codes are seeded with the module (FR-IAM-11).

## 6. Business rules (invariants)

- **BR-PURCH-01** A PO and its Goods Receipts **belong to exactly one company** and are recorded **at
  one branch**; neither changes by edit (mirrors BR-SALES-01). A Goods Receipt is at the **same company
  and branch as its PO**. Cross-tenant purchase data is forbidden.
- **BR-PURCH-02** A PO's **supplier must be associated with the PO's branch and in the same company**
  (Parties FR-PARTY-12, BR-PARTY-01). An archived supplier is not selectable on a new PO (BR-PARTY-09).
  A Goods Receipt inherits this supplier from its PO.
- **BR-PURCH-03** Every product on a PO line must be **associated with the PO's branch and in the same
  company** (Products FR-PROD-22, BR-PROD-10); an archived product is not selectable (BR-PROD-10). A
  line may name a **non-stockable** product (it is bought but moves no stock — Stock skips it, stock.md
  BR-STOCK-02).
- **BR-PURCH-04** Every monetary amount on a PO or GR **carries its currency** (ADR-0005 / BR-CUR-01);
  all amounts on one document share the **document currency** (BR-CUR-07). A bare-number cost is
  invalid.
- **BR-PURCH-05** An **ORDERED PO's lines are frozen** (the ordered quantities/costs do not change by
  edit) and a **received Goods Receipt's content is immutable**; the only v1 changes are a permissioned,
  audited **void** of the GR (FR-PURCH-09) or of the PO. A draft (PO or GR) is freely editable until
  placed/received. (Mirrors BR-SALES-08.)
- **BR-PURCH-06** **Receiving a Goods Receipt pushes stock in exactly once** via `STOCK.RECEIVED`;
  Stock's consumer is idempotent (stock.md FR-STOCK-13, dedupe on event id), so a redelivered event does
  not double-receive.
- **BR-PURCH-07** A PO's number is unique within its company (`PO-####`, assigned at order-placement)
  and a Goods Receipt's number is unique within its company (`GRN-####`, assigned at receive), both
  allocated from `code_sequence` so drafts consume no number (mirrors BR-SALES-12). `[OQ-PURCH-03
  RESOLVED]`
- **BR-PURCH-08** **No payable, no payment, no VAT computation in v1.** Recording a PO or GR creates
  **no** accounts-payable balance, takes **no** payment, and computes **no** input VAT (all deferred to
  Finance/AP, OQ-PURCH-04/05). The documents record cost for the record and the future rounds; they
  settle nothing.
- **BR-PURCH-09** **Quantity into stock, cost on the documents.** The stock-in a Goods Receipt drives is
  quantity-only (Stock v1, stock.md §10); the cost recorded on the PO/GR is **not** carried into a stock
  value in v1 (BR-STOCK-10). No consumer may assume v1 Purchases valued the inventory it received.
- **BR-PURCH-10** **A receipt cannot exceed what is outstanding.** A GR line's received quantity must
  be ≤ the PO line's outstanding quantity (ordered − cumulative received across non-void GRs); a PO
  reaches **fully RECEIVED** only when all lines are fully received. Over-receipt is rejected in v1
  (FR-PURCH-07). Voiding a GR restores the received quantity to the PO's outstanding.

## 7. Process flows — order, then receive (happy paths + main unhappy paths)

### 7.1 Raise a Purchase Order (no stock effect) — happy path
1. A purchasing officer (logged in, active branch) starts a **new PO**.
2. Selects a **supplier** (branch-associated, same company — FR-PURCH-03).
3. Adds **PO lines**: pick product (branch-scoped), enter **ordered quantity + unit** (converts to
   base, FR-PURCH-04) and **unit cost** (a monetary amount, FR-PURCH-05). System totals the lines.
4. Officer **places the order** → the PO is **numbered** (`PO-####` via `code_sequence`), its ordered
   lines **freeze** (BR-PURCH-05), and it moves to **ORDERED**. **No stock moves** (a PO is intent).
5. Each PO line now carries an **outstanding quantity** = ordered (nothing received yet).
6. **Audit** records create → order (NFR-PURCH-03).

### 7.2 Receive goods against a PO (stock IN) — happy path
1. Goods arrive. A storekeeper (`PURCHASE.RECEIVE`, active branch) starts a **Goods Receipt against the
   PO** (selected from the branch's outstanding POs).
2. Enters, per line, the **received quantity** (≤ the PO line's outstanding quantity, BR-PURCH-10).
   A partial receipt leaves the remainder outstanding (FR-PURCH-07).
3. Storekeeper **receives** (finalises) the GR → it is **numbered** (`GRN-####`), becomes **immutable**
   (BR-PURCH-05), and — in the **same transaction** — writes a **`STOCK.RECEIVED`** outbox event (lines
   of `productId` + `qtyInBase`).
4. The outbox dispatcher delivers `STOCK.RECEIVED` to **Stock**, which posts a **`GOODS_RECEIPT`**
   in-movement and **increments on-hand** (stock.md §7.1); a non-stockable line moves no stock.
5. The PO lines' **outstanding quantity decreases** by what was received; the PO advances to
   **partially RECEIVED** (some outstanding remains) or **fully RECEIVED** (all outstanding = 0).
6. **Audit** records the receipt (NFR-PURCH-03). On-hand at the branch now reflects the receipt.

### 7.3 Subsequent partial receipt(s) — happy path
1. A later delivery arrives against the **same PO** while quantity remains outstanding.
2. Repeat 7.2: a **new Goods Receipt** (its own `GRN-####`) receives more, each emitting its own
   `STOCK.RECEIVED`, until every line is fully received and the PO is **fully RECEIVED** (then optionally
   **CLOSED**).

### 7.4 Main unhappy paths
- **Archived / non-branch supplier or product** (7.1.2/7.1.3) → not offered / rejected (BR-PURCH-02/03).
- **Non-stockable product on a line** → ordered and recorded, but on receipt it emits **no** stock
  movement (Stock skips it, stock.md BR-STOCK-02). Not an error.
- **Bare-number cost (no currency)** (7.1.3) → rejected (BR-PURCH-04).
- **Over-receipt** (7.2.2) — received qty exceeds the PO line's outstanding → **rejected**; v1 does not
  allow receiving more than ordered (BR-PURCH-10). (Tolerance/over-receipt policy is a later additive
  decision.)
- **Wrong Goods Receipt received** (7.2.3) → **void** the GR within the permitted window (FR-PURCH-09),
  which reverses the stock-in via the compensating event **and restores** the received quantity to the
  PO's outstanding (the PO can be re-received); full returns-to-supplier are deferred (OQ-PURCH-06).
- **PO no longer wanted before any receipt** → **void** the PO (no stock was ever moved).
- **`STOCK.RECEIVED` redelivered** (7.2.4) → Stock's idempotent consumer does not double-receive
  (BR-PURCH-06, stock.md FR-STOCK-13).

## 8. Non-functional

- **NFR-PURCH-01** **Tenant isolation:** every PO and GR (and their lines) is scoped by `company_id` +
  `branch_id` through the tenant-predicate repository base (ARCHITECTURE.md §5, PROJECT-CONVENTIONS
  §3.2). Cross-company/branch purchase leakage is a **release blocker**.
- **NFR-PURCH-02** **Money correctness:** all cost amounts are amount + currency (ADR-0005); line and
  document totals round to the currency's minor units (BR-CUR-03; mode OQ-CUR-03) identically backend
  and frontend.
- **NFR-PURCH-03** **Audit:** PO create / order / void and GR create / receive / void are written to
  the IAM append-only audit trail with actor, action, target, timestamp, and company/branch context
  (mirrors NFR-SALES-03).
- **NFR-PURCH-04** **Numbering concurrency:** two officers placing orders, or two storekeepers
  receiving, simultaneously get distinct `PO-####` / `GRN-####` numbers (the `code_sequence` row-locked
  allocation guarantees this — ADR-0007 D-6).
- **NFR-PURCH-05** **Forward-compatibility:** the v1 model must not **preclude** later (a) a multi-step
  PO approval workflow, (b) the supplier-invoice leg of a 3-way match, (c) supplier invoices / AP /
  payments, (d) returns to supplier, (e) landed cost, or (f) carrying the recorded cost into stock
  valuation. Building these is deferred (§2, §10); precluding them is a defect.
- **NFR-PURCH-07** **Outstanding-quantity correctness:** a PO line's outstanding quantity must always
  reconcile to ordered − cumulative received across its non-void Goods Receipts (BR-PURCH-10); a receipt
  and a concurrent receipt against the same PO line must serialise so outstanding never goes negative or
  is over-received. The mechanism is the architect's; the requirement is consistent outstanding under
  concurrency.
- **NFR-PURCH-06** Timestamps are UTC, displayed per branch/company time zone (Africa/Dar_es_Salaam
  default, iam.md locale).

## 9. Assumptions

- The **Supplier** master exists and is consumed synchronously via DTO (Parties FR-PARTY-02;
  ADR-0006/0007 anticipated Purchases reading party DTOs). Purchases owns no supplier facts.
- The **outbox is built this round** (stock.md §3.4, OQ-STOCK-09 RESOLVED) under its **own platform
  ADR**; Purchases is an event **producer** (`STOCK.RECEIVED`, emitted by the Goods Receipt) the way
  Sales is for `SALE.FINALISED`. Until the outbox exists, a received Goods Receipt cannot push stock in
  — so the outbox is a **prerequisite** of this round.
- **A Goods Receipt is always raised against a PO** in v1 (there is no receipt-without-order path);
  the PO is the source of the supplier, the ordered lines, and the outstanding quantity a GR draws down.
- **Document currency = company base (TZS)** in practice for v1; the foreign-currency capability is
  reserved (FR-CUR-08/09) but not exercised.
- **Cost is recorded but not valued.** v1 Purchases captures cost for the record and the future
  valuation/AP rounds; it computes no stock value, no VAT, no payable (stock.md §10, BR-PURCH-08/09).
- The generic `code_sequence` numbering primitive already exists (ADR-0007 D-6); Purchases reuses it
  with **two** new `entity_kind`s (`PURCHASE_ORDER` → `PO-####`, `GOODS_RECEIPT` → `GRN-####`), minting
  no new counter.

## 10. RATIFIED SCOPE — PO + separate Goods Receipt; partial receipts; cost recorded, not valued; no AP in v1 (owner-confirmed 2026-06-07)

> **Read this before building or consuming Purchases.** Four deliberate, **owner-confirmed** v1 stances.

1. **v1 is a TWO-DOCUMENT flow — a Purchase Order, then a separate Goods Receipt** (OQ-PURCH-01
   RESOLVED = PO + separate receipt). A **PO** is raised first (the commitment to buy; ordered lines;
   moves no stock); a **Goods Receipt** is then recorded **against** the PO to receive some or all of
   the ordered quantity. **Partial receipts are supported** — multiple Goods Receipts against one PO,
   each drawing down the **outstanding quantity**, until the PO is fully received. This is the bigger,
   ordering-first flow the owner chose over a single-step GRN. (A multi-step PO **approval** workflow
   and the supplier-invoice leg of a 3-way match are deferred — NFR-PURCH-05.)

2. **Receiving a Goods Receipt pushes stock IN — RATIFIED, from day one.** Finalising a Goods Receipt
   emits `STOCK.RECEIVED` that Stock consumes to post a `GOODS_RECEIPT` movement and increment on-hand
   (real stock-in, not a stub). A PO alone moves no stock. This is the owner-ruled core of the round.

3. **Cost is recorded on the PO/GR but NOT turned into stock value, VAT, or a payable.** v1 captures the
   supplier's line cost (money) for the record and the future valuation/AP rounds. It computes **no**
   stock valuation (Stock is quantity-only, stock.md §10), **no** input VAT (OQ-PURCH-04 RESOLVED), and
   creates **no** accounts-payable balance or payment (OQ-PURCH-05). A v1 purchase therefore **owes
   nothing and values nothing** — it brings goods in (quantity) and records what they cost. **Cost is
   required on a goods line** (zero only for a free/sample line with a reason).

4. **The transactional outbox is built this round** (OQ-STOCK-09 RESOLVED) under its own platform ADR;
   the Goods Receipt is a `STOCK.RECEIVED` **producer**, at-least-once with consumer-side idempotency.

All deferred items are additive-by-design (NFR-PURCH-05); none is precluded by the v1 model.

## 11. Open questions — ALL RESOLVED (owner-confirmed 2026-06-07)

> Every Purchases open question is **RESOLVED** by the owner (full log, with the resolution wording, in
> `docs/requirements/open-questions.md`). **No ADR-0010-blocking question remains.**

- **OQ-PURCH-01 — Single-step GRN vs separate Purchase Order.** ✅ **RESOLVED = PO + SEPARATE GOODS
  RECEIPT** (two documents, two steps). A PO is raised first; a separate Goods Receipt is recorded
  against it and pushes stock IN. **Partial receipts** (multiple GRs against one PO) with
  received-vs-ordered (outstanding) tracking are in scope. Multi-step PO approval and the
  supplier-invoice 3rd leg are deferred. *(Was the shape-defining question — now closed.)*
- **OQ-PURCH-02 — Document lifecycles.** ✅ **RESOLVED.** **PO: DRAFT → ORDERED → (partially/fully)
  RECEIVED → CLOSED / VOID** (FR-PURCH-02a). **Goods Receipt: DRAFT → RECEIVED → VOID** (FR-PURCH-02b).
- **OQ-PURCH-03 — Numbering.** ✅ **RESOLVED.** PO `PO-####` and Goods Receipt `GRN-####`, both
  **per-company** via `code_sequence` (PO number at order-placement, GRN number at receive).
- **OQ-PURCH-04 — Purchase VAT / input tax & cost requiredness.** ✅ **RESOLVED.** **No purchase VAT**
  in v1 (input-VAT recovery is Finance/AP, deferred); **cost required** on a goods line (zero only for a
  free/sample line with a reason).
- **OQ-PURCH-05 — Supplier invoices / accounts payable / payments.** ✅ **RESOLVED = deferred.** v1
  records cost on the PO/GR, creates **no payable**, takes **no payment**; AP and the supplier-invoice
  leg of a 3-way match land with a Finance-aware round.
- **OQ-PURCH-06 — Returns to supplier / debit notes.** ✅ **RESOLVED = deferred.** The v1 correction is
  a permissioned **void** of the Goods Receipt (reversing the stock-in, restoring the PO outstanding);
  partial returns with a supplier credit/debit note are a later round.
- **OQ-PURCH-07 — Landed cost.** ✅ **RESOLVED = deferred.** v1 records the supplier's line cost only;
  apportioning freight/duty/insurance ties to the deferred valuation work (stock.md §10).
- **OQ-PURCH-08 — Service / expense purchases.** ✅ **RESOLVED = goods-only.** v1 PO/GR are for **goods
  that move stock**; service/expense purchases are deferred to AP/expenses.

## 12. Out of scope for v1 (deferred — restated)

Multi-step PO approval workflow (OQ-PURCH-01); the supplier-invoice leg of a 3-way match + supplier
invoices / accounts payable / supplier payments / AP ageing (Finance-aware round, OQ-PURCH-05); returns
to supplier / debit notes beyond the basic void (OQ-PURCH-06); landed cost apportionment (OQ-PURCH-07,
tied to valuation); purchase VAT / input-tax recovery (OQ-PURCH-04, Finance/AP); service / expense
purchases (OQ-PURCH-08); stock valuation from purchase cost (deferred with Stock, stock.md §10);
foreign-currency purchase operations (capability reserved, multicurrency.md §8). Each tracked for a
later round; none precluded by the v1 model (NFR-PURCH-05).
