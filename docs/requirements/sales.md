# Requirements — Sales (selling to customers)

> Status: **FULLY RATIFIED (owner-confirmed 2026-06-07).** The owner answered all eight headline
> decision areas (channels, VAT/fiscalisation, agent attachment, pricing/discount, payment, credit,
> stock coupling, corrections) **and the two ADR-blocking detail rulings: VAT entry = tax-EXCLUSIVE
> (OQ-SALES-03b) and numbering = single per-company `INV-####` (OQ-SALES-12).** Each ruling is
> reflected below as a fixed v1 requirement; everything not chosen has moved to the **Deferred** list
> (§2). **No ADR-0008-blocking open question remains.** A few non-blocking detail questions are still
> open (override threshold value, number-at-finalise, rounding/TZS decimals) with recommended defaults
> — they confirm before go-live, not before ADR — flagged in §11.
>
> Author: system-analyst · Domain: `sales` (operational / transactional). Business-level spec only.
> **No schema, no API shapes, no tables, no code** — those are the solutions-architect's, in
> **ADR-0008** (next step). Do not infer a data model from this document.
>
> **Depends on:** IAM (org → company → branch, permissions, `RequestContext`, audit), Parties
> (Customer, Sales Agent, walk-in customer), Products (catalogue, price lists, sellable flag,
> per-product VAT status — OQ-PROD-05), Multicurrency (money = amount + currency, ADR-0005). Mirrors
> the platform patterns in ARCHITECTURE.md / ADR-0006 / ADR-0007: per-company + multi-branch scope,
> the generic `code_sequence` document numbering, the transactional outbox for cross-module effects,
> and the IAM audit trail.

## 1. Business context & why now

The company sells. IAM gave us the scoping spine (company → branch → user → permission), Parties gave
us **who we sell to** (customers) and **who introduces/closes the sale** (sales agents), and Products
gave us **what we sell** (the priced, sellable catalogue with VAT status pending). Sales is the first
**money-bearing transactional module** — it turns the catalogue and the party master into an actual
**sale**: a customer buys priced products, tax is applied, money is taken, and a document is issued.

Everything Sales needs as inputs now exists or is one additive change away:

- **Who we sell to** — Customer (cash/walk-in or credit/account), incl. the reusable walk-in
  (parties.md, OQ-PARTY-06).
- **Who the sale is attached to** — Sales Agent, internal (an app user) or external (a broker)
  (parties.md FR-PARTY-13).
- **What we sell, and at what price** — Product on a price list, sellable, with a cost price
  (products.md FR-PROD-10..13). Selling-price selection at sale-time is *explicitly Sales' job*
  (FR-PROD-13). Selling an un-priced product is blocked at sale-time (BR-PROD-11).
- **Money** — every amount is amount + currency (ADR-0005); document currency = company base (TZS)
  in practice for v1 (multicurrency.md §5).
- **Per-product VAT** — products.md deferred VAT status to Sales (OQ-PROD-05); **RESOLVED this round =
  yes**: the product master gains a **VAT-status field** (standard-rated / zero-rated / exempt), a
  clean additive change to be designed with Sales (ADR-0008). A TZ sale document is a VAT document, so
  v1 computes VAT per line from this status.

What Sales **does not** yet have: a **Stock** module. Stock does not exist. So the deduction of stock
on a sale (and the cost roll-up for a composed product) is a sequencing fact, not a v1 feature — see
§7 and the accepted-risk note §10. The recipes captured in Products (FR-PROD-14..17) are the future
input to that deduction; v1 Sales records quantities only.

### Vocabulary distinction (read this first)

- **Sale** — the act and the record of selling priced products to a customer. The umbrella term.
- **Sales document** — a recorded sale instance. v1's canonical sales document is the **Invoice**
  (see §3). A **Sales Order** and a **POS sale/receipt** are other sales-document kinds (channels).
- **Channel** — *how* a sale is captured: **Sales Order (SO)**, **Invoice**, or **POS till**. The
  owner named these three; **v1 builds the Invoice only**, with POS and SO **deferred** (both reuse the
  Invoice spine). They share most of the sale spine (customer, agent, lines, tax, total) and differ
  mainly in their lifecycle, who captures them, and how money is taken (§3.3).
- **Sales line** — one product on a sale: product, quantity (in a chosen unit), unit price, line
  discount, line tax, line total. All amounts are amount + currency (ADR-0005).
- **Sales agent (on a sale)** — the agent the sale is *attached to* (parties.md). Internal = an app
  user; external = a broker. Distinct from the **operator** (the logged-in user who keys the sale).
- **Tender** — a means of payment taken against a sale. **v1 tenders are cash and mobile money**
  (card **deferred**). A sale may be settled by one or more tenders (split payment allowed), **paid in
  full at finalise** — see §6.
- **Credit sale** — a sale where the customer does not pay now; an amount becomes **receivable** from
  a credit/account customer, drawn against their credit limit (parties.md, OQ-PARTY-02). **DEFERRED in
  v1** (v1 is paid-at-sale only; lands with a Finance-aware round).
- **Fiscalisation** — applying the TRA (Tanzania Revenue Authority) electronic-fiscal-device rules to
  a sale: a fiscal receipt number, signing, and the TRA-mandated receipt format (EFD/VFD). **DEFERRED
  in v1** as a separable later integration (see §5, §10); v1 prints a proper VAT invoice but is **not**
  TRA-fiscalised.
- **VAT** — Tanzanian Value Added Tax applied to taxable sale lines per the product's VAT status
  (standard-rated / zero-rated / exempt). v1 **computes VAT per line** from this status; the
  *per-product VAT status* (OQ-PROD-05) is **RESOLVED = yes** and added to the product master. v1 entry
  is **tax-EXCLUSIVE** (OQ-SALES-03b RESOLVED): line prices are **net** of VAT and VAT is **added on
  top** to reach the gross total.
- **Return / credit note** — reversing a sale in whole or part (goods returned, money/credit given
  back). **DEFERRED in v1**; the only v1 correction path is a permissioned **void** (see §8 below).
- **Void** — a permissioned reversal of a finalised sale (and its receipt). The v1 correction path.
- **Till / POS session (shift)** — a cash point and the open period a cashier works it, opened with a
  float and closed with a cash-drawer reconciliation. A POS concept; **DEFERRED with the POS channel**.

> **Word discipline (carried into the glossary):** *invoice* ≠ *receipt*. An **invoice** is the
> demand/record of a sale (may be unpaid); a **receipt** evidences money taken. A POS cash sale
> typically produces both at once; a credit invoice produces an invoice now and a receipt later when
> paid. Keep the two apart.

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-07). Detail-level questions that remain
> open are noted in `[OQ-SALES-NN]` and do **not** change the in/out split below — they refine values
> within a chosen feature.

### In scope (v1 — "issue a VAT sale and take payment")

- **One sales document: the Invoice** as the canonical sale — create, view, list/search, and the
  invoice lifecycle (draft → issued/finalised → settled), scoped per-company and per active branch
  (mirrors Products/Parties scoping). **POS till and Sales Order are deferred** (both reuse this spine).
- **A sale is attached to a sales agent** (internal or external), **mandatory on every sale**; the
  internal agent **auto-defaults from the logged-in user** when that user is itself an internal agent
  (overridable with permission). **Commission is recorded/captured but NOT computed in v1.**
- **Sale lines** of sellable products, each with quantity (base or bulk unit, converting per
  Products), a **sale-time unit price** taken from a **company default price list (optionally
  overridden per customer)**, a permissioned + audited **manual line-price override**, an optional
  **line discount**, an optional **document-level discount** (both applied **before VAT**), and
  **line VAT** per the product's VAT status.
- **VAT computation per line** on the sale: each line is standard-rated / zero-rated / exempt per the
  **product's VAT status** (resolves OQ-PROD-05 = yes); the document shows net, VAT (by rate band), and
  gross totals. **Entry is tax-EXCLUSIVE** (OQ-SALES-03b RESOLVED): line prices are net and VAT is added
  on top to the gross total.
- **A proper VAT invoice document/printout** (seller VRN, customer details, line tax breakdown,
  totals, VAT summary) — but **NOT** TRA EFD/VFD device signing/fiscal numbering (deferred).
- **Payment / settlement** with **cash** and **mobile money** tenders, supporting a **single or split
  payment**, **paid in full at finalise**, producing a **receipt**. (No outstanding balance / partial
  payment in v1 — that arrives only with credit.)
- **Permissioned VOID** as the v1 correction path (returns / credit notes / refunds deferred).
- **Document numbering** via the generic `code_sequence` primitive (entity_kind `SALES_INVOICE`),
  **single per-company series `INV-####`** (OQ-SALES-12 RESOLVED), concurrency-safe — the same mechanism
  Products/Parties use. Per-branch / per-channel numbering can be added **later additively** via the
  `entity_kind` discriminator without reworking v1.
- **Audit** of sale create/finalise/settle/void and price/discount overrides per the IAM audit trail.
- **Currency-aware throughout** — document currency = company base (TZS) in practice; the model does
  not preclude a foreign-currency invoice (ADR-0005 / FR-CUR-08/09).
- **Per-product VAT status added to the product master** — a clean **additive** change to Products
  (resolves OQ-PROD-05), designed alongside Sales in ADR-0008.

### Deferred (recognised, NOT built in v1)

- **POS till channel** — tills, **shifts/sessions**, **cash-drawer reconciliation (X/Z reports)**,
  **offline mode**. The Invoice spine is built so POS layers onto it later.
- **Sales Order channel** — quote/commitment that converts to an invoice; reservations/back-orders.
- **TRA EFD/VFD fiscalisation** — fiscal receipt numbering, device signing/QR, TRA receipt format,
  Z-report submission. A separable external-integration workstream (see §10).
- **Stock deduction on sale** — Sales does **not** move inventory in v1 (no Stock module); it records
  sold quantities only. Component (recipe) deduction for composed products is likewise deferred (it was
  prepared in Products §9). **Explicit accepted risk — see §10.**
- **Credit sales / receivables / credit-limit enforcement** — v1 is **paid-at-sale only**. Credit
  terms (net-30 etc.) and the credit-limit check are Finance/Sales territory; lands with a
  Finance-aware round (OQ-PARTY-02).
- **Returns / credit notes / refunds** — the only v1 correction is a permissioned **void**; full
  returns and credit-note machinery deferred.
- **Commission *calculation*** — the sale captures the agent **and the commission record**; computing/
  accruing/paying commission (rates, tiers) is deferred (OQ-PARTY-03). v1 captures, does not compute.
- **Card / EFTPOS, loyalty, gift cards, layaway/deposits, recurring/subscription billing,
  multi-currency settlement** (the last is cross-cut deferred per FR-CUR-11).
- **Delivery / dispatch / proforma** documents; **quotations** as a distinct doc (folded into the
  deferred SO).

### Explicitly NOT this module

- **Stock-on-hand / inventory movement & valuation** — the future Stock module. Sales references
  products; it does not hold or move levels.
- **Purchases** — buying from suppliers is the Purchases module.
- **General ledger / receivables ageing / FX gain-loss / financial statements** — the future Finance
  module derives these (debtor-as-lens, parties.md). Sales *produces the events*; Finance *posts* them.
- **The customer/agent/product masters themselves** — owned by Parties and Products; Sales consumes
  their DTOs (synchronously, as ADR-0006/0007 anticipated).

## 3. The sale and its channels

### 3.1 The sale spine (common to every channel)

A sale, whatever the channel, carries: company + branch scope; a **customer** (the walk-in customer
for anonymous counter sales); an **attached sales agent**; one or more **sale lines** (product, qty,
unit, unit price, line discount, line VAT); a **document currency**; **net / VAT / gross totals**;
**tenders/settlement**; a **status**; a **document number**; and an **audit trail**.

### 3.2 v1 channel — the Invoice (ratified)

The **Invoice** is the canonical v1 sale, and **the only channel built in v1**. It is the superset
document: it carries money, tax, customer, agent, and lines; **POS is a fast cash-invoice** and **SO is
a pre-invoice**, so both deferred channels reuse this spine rather than re-deriving it. Building Invoice
first means the hard VAT/numbering/receipt decisions are made once.

### 3.3 Deferred channels (recognised, NOT in v1)

| Channel | What it adds over the Invoice | Why deferred |
|---|---|---|
| **POS till** | Till devices, cashier **sessions/shifts**, opening float, **cash-drawer reconciliation (X/Z)**, fast keypad/barcode UX, offline buffering | Largest UX + session/reconciliation surface; reuses the Invoice spine once that is proven (POS specifics tracked as OQ-SALES-08) |
| **Sales Order** | Quote/commitment lifecycle, convert-to-invoice, reservation/back-order | A pre-invoice; adds a lifecycle but no new money/tax semantics |

## 4. Actors / personas

- **Sales clerk / cashier (branch operator)** — creates sales, selects customer + agent + products,
  takes payment, issues the invoice/receipt. Sees only their active branch's parties and products.
  Holds a `SALES.CREATE`-style permission.
- **Sales agent (subject, not necessarily operator)** — the party the sale is attached to. An
  **internal** agent is also an app user who may be the operator (then the agent **auto-defaults**
  to themselves); an **external** agent is a broker who does not log in.
- **Sales supervisor / manager** — may be required to approve a **price/discount override** beyond a
  threshold, or to **void** a finalised sale. Holds a `SALES.OVERRIDE` / `SALES.VOID`-style
  permission. (Approval threshold values are OQ-SALES-10.)
- **Finance / accounts user** — consumes settled/credit sales as receivables (debtor-as-lens,
  parties.md). Defined fully when Finance is specified; named here for the credit-sale handoff.

## 5. Functional requirements

> IDs are `FR-SALES-NN`. Each is a crisp, testable, **ratified** statement; where a detail value is
> still open, the governing `[OQ-SALES-NN]` is named (it refines a value, not the feature). "Sale" =
> the v1 Invoice (the only v1 channel).

### Core record & lifecycle

- **FR-SALES-01** The system maintains a **Sale (Invoice)** document — the **only v1 sales channel**:
  create, view, list/search, and progress through a lifecycle. A sale carries company + branch scope, a
  customer, an attached sales agent, sale lines, currency, totals, settlement, status, a document
  number, and audit.
- **FR-SALES-02** A sale has a **status lifecycle**: **draft** (editable, no number issued, no money
  committed), **issued/finalised** (numbered, totals + tax fixed, customer obligated), and **settled**
  (paid in full — v1 takes payment in full at finalise, so there is **no part-settled / outstanding
  balance** state in v1; that arrives only with credit). The presence of a pre-finalisation draft is
  confirmed; the only remaining lifecycle detail is **when the number is assigned** (recommended: at
  finalise, so drafts don't consume numbers). `[OQ-SALES-11]`
- **FR-SALES-03** A **finalised sale is immutable in its commercial content** (lines, prices, tax,
  totals): corrections are made **only** by a permissioned **void** in v1 (returns / credit notes are
  deferred), never by silently editing a finalised sale. `[BR-SALES-08]`

### Sale lines, products & units

- **FR-SALES-04** A sale has **one or more sale lines**. Each line names a **sellable, non-archived
  product** associated with the active branch (Products FR-PROD-20/22, BR-PROD-10), a **quantity**, and
  the **unit** (base or a defined bulk pack) the quantity is expressed in; quantity converts to base
  per Products FR-PROD-06.
- **FR-SALES-05** A line that names a product **with no price on the applicable list is blocked**
  (Products BR-PROD-11 enforced *here*, at sale-time): an un-priced product cannot be sold until
  priced.
- **FR-SALES-06** A **composed product** (recipe, Products FR-PROD-14) is sold as a **single priced
  line** at its own price; v1 does **NOT** explode it into components, deduct component stock, or roll
  up cost (Products §9, this doc §10 — accepted risk). Component deduction is designed to fire later via
  the transactional outbox when Stock lands.

### Pricing, discount, override at sale-time

- **FR-SALES-07** The line **unit price defaults from the company default price list, optionally
  overridden per customer** (Products FR-PROD-13). If the sale's customer has an associated price list,
  that list applies; otherwise the company default list applies.
- **FR-SALES-08** An operator with a **price-override permission** may **manually change a line's unit
  price**; the override is **always recorded and audited** (original list price, overridden price, who,
  when). Overrides beyond a configured threshold may require supervisor approval — the **threshold
  value / permission detail** is the only open part (OQ-SALES-10). `[OQ-SALES-10]`
- **FR-SALES-09** A sale supports a **line-level discount** and a **document-level discount** (both in
  v1); a discount may be a percentage or an amount (amount is amount + currency). Discounts are applied
  **before VAT** on the **net (tax-exclusive) taxable base** (OQ-SALES-03b RESOLVED = tax-exclusive):
  net line = (unit price × qty) − discounts; VAT is then computed on that discounted net.

### VAT (resolves OQ-PROD-05)

- **FR-SALES-10** Each sale line is taxed per the **product's VAT status**: **standard-rated**
  (TZ standard VAT rate), **zero-rated** (0% but a taxable supply), or **exempt** (outside VAT). The
  product carries this status — a **VAT-status field added to the product master** (resolves Products
  OQ-PROD-05 = yes, an additive change designed with Sales); the rate value comes from a maintained tax
  setting, **not hard-coded**.
- **FR-SALES-11** The system computes, per line and per document, the **net (tax-exclusive, taxable)
  amount**, the **VAT amount** (by rate band), and the **gross total** (= net + VAT), each currency-aware
  and rounded to the document currency's minor units (ADR-0005, BR-CUR-03; rounding mode/TZS decimals
  OQ-CUR-03). VAT is **summarised by rate** on the document (a VAT analysis the invoice prints).
- **FR-SALES-12** v1 VAT line **entry is tax-EXCLUSIVE** (OQ-SALES-03b RESOLVED): the unit price keyed
  and displayed on a line is the **net** price, and VAT is **added on top** of the discounted net to
  produce the line and document gross. (A tax-inclusive entry mode — VAT backed out of a gross price —
  is **not** built in v1; it may be revisited for the deferred POS channel, additively.)
- **FR-SALES-13** The system produces a **proper VAT invoice document/printout** carrying seller
  identity + **VRN**, customer identity (+ VRN where the customer is VAT-registered), per-line tax
  detail, and the VAT summary by rate band. v1 does **NOT** apply **TRA EFD/VFD** fiscal numbering/
  signing/format — **fiscalisation is deferred** as a separable later integration (§10) that augments
  the document number with a fiscal receipt number and adds signing/QR. A v1 invoice is a correct VAT
  invoice but is **not, by itself, a TRA fiscal receipt**.

### Sales agent attachment

- **FR-SALES-14** Every sale **is attached to a sales agent** (internal or external) — **mandatory on
  every sale** — selected from agents associated with the active branch (Parties FR-PARTY-12). A
  designated default agent for counter sales (analogous to the walk-in customer) keeps cashiers from
  being blocked.
- **FR-SALES-15** When the **logged-in user is themselves an internal sales agent**, the sale's agent
  **auto-defaults to that user's agent record**, **overridable** (with permission) to another
  selectable agent. An internal agent whose referenced IAM user is disabled is **not selectable**
  (Parties BR-PARTY-10).
- **FR-SALES-16** v1 **records/captures the agent attachment and a commission record on the sale**, but
  does **NOT** compute, accrue, or pay **commission** (rates/tiers deferred, OQ-PARTY-03). The captured
  attachment and commission record are the input the future commission feature consumes. `[OQ-PARTY-03]`

### Payment, settlement & credit

- **FR-SALES-17** A sale is **settled by one or more tenders**. v1 tenders are **cash** and **mobile
  money** (**card deferred**). A sale may be settled by a **single tender or split across tenders**
  (e.g. part cash + part mobile money).
- **FR-SALES-18** A sale is **paid in full at finalise**: the tenders taken must cover the gross total,
  and settlement produces a **receipt** evidencing money taken (distinct from the invoice). A fully-paid
  sale is **settled**. v1 has **no partial-payment / outstanding-balance state** (that arrives with
  credit, deferred).
- **FR-SALES-19** A **payment settles a sale in the sale's own currency** (no cross-currency
  settlement in v1, FR-CUR-11 / BR-CUR-06).
- **FR-SALES-20** **Credit sales** — a credit/account customer taking goods without paying now,
  creating a **receivable** drawn against a credit limit — are **DEFERRED in v1**: v1 is **paid-at-sale
  only**. Credit sales, receivables, and credit-limit enforcement land with a **Finance-aware round**
  (OQ-PARTY-02). `[OQ-PARTY-02]`

### Stock coupling (sequencing)

- **FR-SALES-21** v1 Sales **records quantities sold but does NOT deduct stock** (no Stock module
  exists) — an **explicit accepted risk** the owner has signed off (§10). A sale never asserts stock
  availability and never moves inventory in v1; it does not warn on over-sell (stock-agnostic). When
  Stock lands, finalising a sale will emit a stock-deduction effect **via the transactional outbox**
  (the cross-module pattern, ARCHITECTURE.md §9), consuming the recipe (Products §9) for composed
  products. The v1 model must not preclude that later outbox event (NFR-SALES-07).

### Void / return (corrections)

- **FR-SALES-22** v1's **only** correction path is a **permissioned void** of a sale (`SALES.VOID`)
  that reverses the sale and any receipt, audited. **Returns, credit notes, and refunds** (partial
  return, restocking, refund tender) are **deferred**. The exact void window (e.g. same-business-day vs
  unrestricted-with-permission) is a detail the architect can fix; the recommended default is a
  permissioned void within a configurable window.

### Numbering, scope & permissions

- **FR-SALES-23** Each sale has a **document number unique within its company**, a **single
  per-company series `INV-####`** (OQ-SALES-12 RESOLVED), allocated concurrency-safe from the **generic
  `code_sequence`** primitive (entity_kind `SALES_INVOICE`) the platform already provides (ADR-0007 D-6)
  — Sales does **not** mint a new per-module counter, mirroring Products/Parties. The number is assigned
  at finalise (recommended, OQ-SALES-11). **Per-branch / per-channel series can be added later
  additively** via the `entity_kind` discriminator (e.g. a distinct `entity_kind` per channel/branch)
  without reworking v1's single-series model.
- **FR-SALES-24** Sales are **scoped per company and filtered by the active branch**: an operator sees
  and creates sales only in their active branch; selection of customers/agents/products is the
  branch-filtered selection the dependency modules already enforce (Parties FR-PARTY-12, Products
  FR-PROD-22). Cross-company/branch sale leakage is a release blocker (NFR).
- **FR-SALES-25** All sale operations are **gated by IAM permissions** (e.g. `SALES.CREATE`,
  `SALES.VIEW`, `SALES.OVERRIDE` for price/discount override, `SALES.VOID`, `SALES.SETTLE`). Exact
  codes are seeded with the module; this FR fixes only that sale operations are permission-gated per
  IAM (FR-IAM-11).

## 6. Business rules (invariants)

> Ratified; where a detail value is still open, the governing OQ is named (it refines a value, not the
> rule).

- **BR-SALES-01** A sale **belongs to exactly one company** and is recorded **at one branch**; neither
  changes by edit (mirrors BR-PARTY-02 / BR-PROD-18). Cross-tenant sale data is forbidden.
- **BR-SALES-02** Every product on a sale line must be **sellable, non-archived, and associated with
  the sale's branch**, and in the **same company** as the sale (Products BR-PROD-10, FR-PROD-22).
- **BR-SALES-03** A sale line's product **must have a price on the applicable price list**; an
  un-priced product cannot be sold (Products BR-PROD-11, enforced at sale-time here).
- **BR-SALES-04** Every monetary amount on a sale (unit price, discount, line/doc net, VAT, gross,
  tender, balance) **carries its currency** (ADR-0005 / BR-CUR-01); a bare-number amount is invalid.
  All amounts on one sale are in the **document currency**; cross-currency arithmetic is forbidden
  (BR-CUR-07).
- **BR-SALES-05** **VAT is computed per line from the product's VAT status and a maintained rate, never
  a hard-coded rate** (FR-SALES-10). A standard-rated line bears VAT; a zero-rated line bears 0% VAT but
  is a taxable supply; an exempt line bears no VAT and is excluded from the taxable base.
- **BR-SALES-06** A sale **is attached to exactly one sales agent** (mandatory); the agent must be
  **selectable** (associated with the branch; if internal, its IAM user is active — Parties BR-PARTY-10).
  When the operator is an internal agent, the agent auto-defaults to them (overridable, FR-SALES-15).
- **BR-SALES-07** A **payment settles a sale in the sale's own currency** and the **tenders taken must
  cover the gross total** (paid in full at finalise); over-tender is handled as **change** for cash, not
  as a negative balance. (Cross-currency settlement deferred, BR-CUR-06.)
- **BR-SALES-08** A **finalised sale's commercial content is immutable**; the only v1 change is a
  permissioned, audited **void** (FR-SALES-03 / FR-SALES-22). A draft is freely editable until
  finalised.
- **BR-SALES-09** A **price/discount override is permissioned, always recorded and audited** (original
  vs applied, operator, time); an override beyond the configured threshold requires the approval step
  (threshold value OQ-SALES-10). `[OQ-SALES-10]`
- **BR-SALES-10** A **walk-in/anonymous sale** uses the reusable walk-in customer (Parties OQ-PARTY-06).
  Because **credit sales are deferred**, every v1 sale is **paid at finalise** regardless of customer
  kind; the walk-in-cannot-take-credit rule (Parties BR-PARTY-07) becomes load-bearing only when credit
  lands.
- **BR-SALES-11** A sale **does not move stock in v1** (FR-SALES-21); finalising a sale asserts nothing
  about stock-on-hand. No code or downstream consumer may assume a v1 sale deducted inventory — an
  **explicit accepted risk** (§10).
- **BR-SALES-12** A sale's **document number is unique within its company**, a **single per-company
  `INV-####` series** (OQ-SALES-12 RESOLVED), gap-tolerant only as the `code_sequence` mechanism allows,
  and is **assigned at finalisation** (recommended, OQ-SALES-11) so drafts do not consume numbers.
  Per-branch / per-channel series are a later additive change (new `entity_kind`). `[OQ-SALES-11]`

## 7. Process flow — cash sale (happy path + main unhappy paths), ratified v1

**Happy path (paid-at-sale invoice):**
1. Operator (logged in, active branch) starts a **new sale**.
2. Selects a **customer** (or the **walk-in** customer for an anonymous counter sale).
3. The **sales agent** defaults to the operator if they are an internal agent (overridable), else is
   selected — **mandatory** on every sale.
4. Adds **sale lines**: pick product (branch-scoped, sellable), enter quantity + unit; the **net
   (tax-exclusive) unit price** defaults from the applicable price list (FR-SALES-07); optional **line
   discount**; **price override** if permitted (FR-SALES-08, recorded).
5. System computes totals **tax-exclusively** (OQ-SALES-03b RESOLVED): for each line, **net =
   (net unit price × qty) − discounts**, then **VAT = net × rate** (by the product's VAT status), then
   **line gross = net + VAT**; the document rolls up **net, VAT by rate band, and gross = net + VAT**
   (FR-SALES-11), all currency-aware.
6. Operator **finalises** the sale → it is **numbered** (single per-company `INV-####` via
   `code_sequence`, OQ-SALES-12 RESOLVED) and becomes immutable commercially (BR-SALES-08).
7. Operator takes **payment** — one or more tenders (cash / mobile money), covering the gross total in
   full (split allowed) — producing a **receipt**; the sale becomes **settled**.
8. The **VAT invoice / receipt** prints (no TRA fiscal signing in v1 — fiscalisation deferred).
9. **Audit** records create → finalise → settle (NFR).

**Main unhappy paths:**
- **Un-priced product** (4) → line rejected with a clear "product not priced" message (BR-SALES-03).
- **Archived / non-branch / non-sellable product** (4) → not offered / rejected (BR-SALES-02).
- **Disabled internal agent** (3) → not selectable; choose another agent (BR-PARTY-10).
- **Over-tender on cash** (7) → accepted; **change** computed and shown (BR-SALES-07).
- **Insufficient tender / customer can't pay** (7) → because credit is deferred, the sale cannot
  finalise as paid; the sale is held or cancelled (no receivable is created in v1).
- **Override beyond threshold without approval** (4) → blocked pending supervisor approval
  (threshold value OQ-SALES-10).
- **Wrong sale finalised** (6/7) → **void** within the permitted window (FR-SALES-22), audited;
  returns / credit notes are deferred.

## 8. Non-functional

- **NFR-SALES-01** **Tenant isolation:** every sale is scoped by `company_id` + `branch_id` and goes
  through the tenant-predicate repository base (ARCHITECTURE.md §5, PROJECT-CONVENTIONS §3.2).
  Cross-company / cross-branch sale leakage is a **release blocker**, as for IAM.
- **NFR-SALES-02** **Money correctness:** all sale amounts are amount + currency (ADR-0005); totals
  computed and displayed must round **identically** backend and frontend to the currency's minor units
  (BR-CUR-03; mode OQ-CUR-03). A displayed total that disagrees with the stored total is a
  finance-grade defect.
- **NFR-SALES-03** **Audit:** sale create / finalise / settle / void, and price/discount overrides,
  are written to the IAM append-only audit trail with actor, action, target, timestamp, and
  company/branch context (mirrors FR-IAM-23). Audit rows are immutable.
- **NFR-SALES-04** **Numbering concurrency:** two cashiers finalising simultaneously must get distinct
  document numbers (the `code_sequence` row-locked allocation guarantees this — ADR-0007 D-6).
- **NFR-SALES-05** **Responsiveness at the counter:** product/customer/agent lookup and sale finalise
  must be fast enough for counter use (indexed lookups; Products NFR-PROD-01 barcode scan). This is the
  load the future POS channel will intensify.
- **NFR-SALES-06** Timestamps are UTC, displayed per branch/company time zone (Africa/Dar_es_Salaam
  default, iam.md locale).
- **NFR-SALES-07** **Forward-compatibility for fiscalisation & stock:** the v1 model must not preclude
  later (a) TRA EFD/VFD fiscal numbering/signing replacing/augmenting the document number, or (b) a
  stock-deduction outbox event on finalise. Building these is deferred; precluding them is a defect
  (mirrors the multicurrency "do not preclude" stance).

## 9. Assumptions

- The dependency masters exist and are consumed **synchronously via DTOs** (ADR-0006/0007 explicitly
  anticipated Sales reading party/product DTOs): Customer, Sales Agent, walk-in customer (Parties);
  sellable Products on price lists with cost + **VAT status** (Products — the VAT-status field is the
  one additive product change this round adds, OQ-PROD-05 resolved); active currencies (ADR-0005).
- **Document currency = company base (TZS) in practice** for v1; the foreign-currency *capability* is
  reserved (FR-CUR-08/09) but day-one sales are base-currency.
- "Member of staff" for an internal agent = an **app user** (HR deferred); the agent auto-default in
  FR-SALES-15 keys off the IAM user ↔ internal-agent reference (Parties FR-PARTY-13).
- The **generic `code_sequence`** numbering primitive and the **transactional outbox** already exist as
  platform patterns (ADR-0007); Sales reuses them rather than inventing numbering or cross-module calls.
- TZ standard VAT rate and the **rounding mode / TZS decimals** (OQ-CUR-03) are confirmed before Sales
  goes live; the rate is **maintained data, never hard-coded** (FR-SALES-10).

## 10. ACCEPTED RISK — no stock movement, no fiscalisation in v1 (owner-accepted 2026-06-07)

> **Read this before building or consuming Sales.** Two deliberate v1 omissions, **explicitly accepted
> by the owner on 2026-06-07.** They are not oversights; nobody may quietly assume otherwise.

1. **A v1 sale does NOT move inventory — ACCEPTED RISK.** There is no Stock module; a finalised sale
   **records the sold quantities but deducts no stock-on-hand**, and selling a composed product does
   **not** deduct its recipe components (prepared in Products §9). No code, report, or downstream
   consumer may assume a v1 sale affected inventory. The deduction is **designed to later fire via the
   transactional outbox** when Stock lands (composed-product component deduction also then). The owner
   has signed off that v1 ships without inventory accuracy guarantees.

2. **A v1 sale is NOT TRA-fiscalised — ACCEPTED RISK.** v1 produces a correct **VAT invoice** (computed
   per-line tax, seller VRN, VAT summary) but does **not** integrate the **TRA EFD/VFD** device (fiscal
   receipt number, signing/QR, TRA-mandated format, Z-report). Fiscalisation is a **separable later
   integration** that augments the document number with a fiscal receipt number and adds signing.
   **A v1 invoice is not, by itself, a TRA-compliant fiscal receipt** until that integration lands —
   the owner has accepted this gap for the v1 window.

Both are reversible/additive by design (NFR-SALES-07); neither is precluded by the v1 model.

## 11. Open questions — status after ratification (2026-06-07)

> The eight headline decision areas **plus both ADR-blocking detail rulings (OQ-SALES-03b VAT entry,
> OQ-SALES-12 numbering scheme)** are **RESOLVED** (below). **No ADR-0008-blocking open question
> remains.** What is still open is **non-blocking** detail that confirms before go-live, not before ADR
> — each with a recommended default that stands unless the owner overrides. Full log in
> `docs/requirements/open-questions.md`.

### Resolved by the owner (2026-06-07)

- **OQ-SALES-01 — Channel scope** → **RESOLVED: Invoice only in v1.** POS till and Sales Order
  **deferred** (both reuse the Invoice spine).
- **OQ-SALES-02 — Agent attachment** → **RESOLVED: mandatory on every sale**; auto-defaults to the
  logged-in user when they are an INTERNAL agent (overridable); **commission recorded/captured but NOT
  computed** in v1 (rates deferred → OQ-PARTY-03).
- **OQ-SALES-03 — VAT compute & TRA fiscalisation** → **RESOLVED: compute VAT per line in v1**;
  **TRA EFD/VFD fiscalisation deferred** as a separable later integration. v1 prints a proper VAT
  invoice.
- **OQ-PROD-05 — Per-product VAT status** → **RESOLVED: yes.** Add a **VAT-status field**
  (standard / zero-rated / exempt) to the product master — an additive change designed with Sales.
- **OQ-SALES-04 — Pricing & discount** → **RESOLVED:** sale-time price from a **company default price
  list, optionally overridden per customer**; **manual line-price override allowed, permission-gated and
  audited**; **line + document discounts supported, applied before VAT.**
- **OQ-SALES-05 — Tenders & payment** → **RESOLVED:** tenders = **cash + mobile money**, **split
  allowed**, **paid in full at finalise**; card deferred; no partial-payment / outstanding-balance state.
- **OQ-SALES-06 — Credit sales & receivables** → **RESOLVED: deferred.** v1 is paid-at-sale only; lands
  with a Finance-aware round (→ OQ-PARTY-02).
- **OQ-SALES-09 — Stock coupling** → **RESOLVED & ACCEPTED RISK:** v1 records sold quantities, deducts
  **no** inventory, is stock-agnostic (no over-sell warning); deduction designed to fire via the outbox
  when Stock lands (§10).
- **OQ-SALES-07 — Corrections** → **RESOLVED: permissioned VOID only in v1**; returns / credit notes /
  refunds deferred.
- **OQ-SALES-08 — POS specifics** → **N/A in v1** (POS deferred per OQ-SALES-01); retained in the log
  for the future POS round.
- **OQ-SALES-12 — Numbering scheme** → **RESOLVED: single per-company series `INV-####`** via the
  generic `code_sequence` (entity_kind `SALES_INVOICE`), mirroring Products/Parties. Per-branch /
  per-channel numbering can be added later **additively** via the `entity_kind` discriminator. Reflected
  in FR-SALES-23, BR-SALES-12, §2. **(Was the last lightly-blocking ADR-0008 question.)**
- **OQ-SALES-03b — VAT-inclusive vs -exclusive entry** → **RESOLVED: tax-EXCLUSIVE.** Line prices are
  **net**; VAT is **added on top** to the gross total (gross = net + VAT). A tax-inclusive entry mode is
  **not** built in v1 (revisit for the deferred POS channel, additively). Reflected in FR-SALES-09/11/12,
  the VAT vocabulary, §2 scope, and the §7 totals flow. **(Was the last ADR-0008-blocking question.)**

### Still open — NON-blocking detail (recommended defaults stand; confirm before go-live, NOT before ADR-0008)

> **None of these block ADR-0008.** The architect may proceed; each refines a value inside an already
> ratified feature and can land additively or be confirmed during build / before go-live.

- **OQ-SALES-10 — Override / approval threshold value.** The permission-gated override itself is ratified;
  the **threshold above which a supervisor must approve** (e.g. discount > X% or a price below cost) and
  its value are open. *Recommended default:* a single configurable percent threshold, owner-set; ship the
  permissioned override regardless. *Blocks ADR-0008:* **NO** (additive; confirm the value before go-live).
- **OQ-SALES-11 — Number-assignment point.** Draft state is confirmed; confirm the document number is
  **assigned at finalise** (so drafts don't consume numbers). *Recommended default:* number at finalise.
  *Blocks ADR-0008:* **NO** — the recommended default (at finalise) stands; the architect models to it.
- **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** Confirm rounding mode (half-up vs banker's) and
  TZS decimal places (0 in practice). *Recommended default:* half-up, TZS = 0 dp. *Blocks ADR-0008:*
  **NO** for the model; **must confirm before go-live** (totals must round identically backend/frontend,
  NFR-SALES-02).

## 12. Out of scope for v1 (deferred — restated)

POS till channel + sessions/shifts + cash-drawer reconciliation + offline (future POS round);
Sales Order channel + reservations/back-orders; TRA EFD/VFD fiscalisation (separable later integration,
§10); stock deduction & composed-product component deduction/cost roll-up (accepted risk §10, Products
§9); credit sales, receivables & credit-limit enforcement (Finance-aware round, OQ-PARTY-02); commission
calculation/accrual (captured-not-computed, OQ-PARTY-03); returns / credit notes / refunds beyond the
basic void; card/EFTPOS, loyalty, gift cards, layaway/deposits, recurring billing; cross-currency
settlement (FR-CUR-11); delivery/dispatch/proforma documents. Each tracked for a later round; none
precluded by the v1 model (NFR-SALES-07).
