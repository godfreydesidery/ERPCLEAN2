# Domain Glossary

One definition per term, used consistently across the team. Add terms as modules are specified.

## IAM

- **Organisation** — The top of the structure; one per deployment. Parent of all companies.
- **Company** — A legal entity within the organisation. Scoping parent of company-bound master data
  and transactions (`company_id`). A deployment may have several.
- **Branch** — A physical location (shop, depot, warehouse) under a company. Smallest unit at which
  stock and a business day exist. Identified by a `code` unique within its company.
- **Default branch (of a company)** — The branch used as a company's fallback context.
- **App user / user** — A login identity. Distinct from an *employee* (an HR record). A user may or
  may not be an employee. Logs in with a **username** (unique org-wide).
- **Branch assignment** — A link making a user "present" at a branch (`user_branch`). A user has
  many; exactly one is the user's **default branch**.
- **Default branch (of a user)** — The single branch a user lands in at login. Must be one of the
  user's current assignments. Auto-promoted to the earliest-assigned branch if removed.
- **Default company (of a user)** — The company a user lands in at login (the company of their
  default branch). Relevant because a user may span companies.
- **Permission** — The atomic access unit, a dot-separated code (`USER.MANAGE`). Org-wide catalogue,
  seeded. Authorisation checks reference permission codes, never role names.
- **Role** — A named, reusable bundle of permissions (`CASHIER`, `BRANCH_MANAGER`). Org-wide.
- **Role assignment (`user_role`)** — Binds a role to a user, scoped to a company and optionally to
  one branch. Branch unset ⇒ all branches of that company the user is assigned to.
- **Super-admin / root** — An identity that transcends company/branch scoping for administration and
  recovery. Bootstrap-created, tightly held, fully audited.
- **Bootstrap** — The env-driven first-run process that creates organisation + first company +
  default branch + root admin on an empty database. No interactive wizard.
- **Branch-override header** — The request header by which an authenticated user switches their
  active branch without re-login. Honoured only for branches the user is assigned to and whose role
  scope covers them.
- **Access token / refresh token** — Short-lived JWT (15 min) used per request; longer-lived refresh
  token (7 days, single-use rotated) used to obtain a new pair.
- **Lockout** — Temporary block after 5 consecutive failed logins (15 minutes); admin can clear.

## Parties

- **Party** — Umbrella term for an external counterparty held as master data. In v1 there is **no**
  single unified party record; "party" denotes the *category* comprising the separate Customer,
  Supplier, Sales Agent, and generic Other records. A party is **not** an app user and **not** an
  employee.
- **Customer** — A person/organisation we sell to. Self-contained record (own identity, contact, tax
  fields). Sub-kinds: cash/walk-in and credit/account.
- **Supplier** — A person/organisation we buy from. Self-contained record. Sub-kinds: goods and
  service.
- **Sales Agent** — A person/organisation that introduces or closes sales for commission.
  Self-contained record with an **agent kind**:
  - **Sales Agent (internal)** — a member of our staff (an app user); the agent record **references**
    an IAM user; commission accrues to that staff identity.
  - **Sales Agent (external)** — an outside broker; a standalone party with its own identity/tax
    fields and no IAM reference.
- **Other / Misc party** — A generic, lightly-typed counterparty record for something that must be
  captured now but is not yet a customer, supplier, or agent. Safety-valve.
- **Party-branch association** — The many-to-many *business relationship* linking a party to the
  branches of its company at which it may be used. A branch sees only its associated parties; a
  party's branches must all belong to its company. (A relationship, not a join table.)
- **TIN** — Taxpayer Identification Number (Tanzania). Mandatory for a business party.
- **VRN** — VAT Registration Number (Tanzania). Captured only for a VAT-registered party.
- **Business registration number** — A general, registrar-agnostic company/business registration
  number for registered businesses (BRELA in Tanzania, or another registrar elsewhere). Recommended,
  not mandatory; the system does not hard-code BRELA.
- **Walk-in / cash customer** — A customer who pays at point of sale, no credit terms; minimal
  identification (often just a name). May be a reusable anonymous counter-sale customer.
- **Credit / account customer** — A customer who buys on account with a balance and (later) credit
  terms; must be more fully identified (a business needs a TIN).
- **Debtor-as-lens** — **Not a party type.** A *finance view* of a customer who currently owes us
  money (a receivable balance). Derived by Finance from customer balances; never a separate master
  record.
- **Creditor-as-lens** — **Not a party type.** A *finance view* of a supplier we currently owe (a
  payable balance). Derived from supplier balances; never a separate master record.

## Multicurrency

- **Monetary amount** — A *value together with the currency it is denominated in* — never a bare
  number. The atomic unit of money in the system; the technical "amount + currency" type lives in
  ADR-0005. An amount without a currency is invalid (BR-CUR-01).
- **Base / reporting currency** — The single currency a **company** reports and values in. One per
  company, configurable (default TZS for TZ deployments, **never hard-coded**), set once at company
  setup; changing it is a controlled operation (BR-CUR-02).
- **Transaction / document currency** — The currency a specific transaction or document is *expressed
  in*. Usually equals the company base in v1; may differ from base (then it is a *foreign* currency).
- **Foreign currency** — Any currency other than the company's base currency. A foreign-currency
  transaction records its base-currency equivalent + the rate used, on the transaction (FR-CUR-09).
  The *capability* exists in v1; foreign-currency *operations* are largely deferred.
- **Exchange rate** — The rate converting a foreign-currency amount into the base currency, recorded
  **with** the transaction and immutable thereafter (BR-CUR-05). v1 does **not** source rates from any
  feed (FR-CUR-10); a needed rate is entered/known with the transaction.
- **ISO 4217** — The international standard for currency codes (`TZS`, `USD`, `EUR`) and minor units.
  Each currency in the master carries its ISO 4217 code (FR-CUR-01).
- **Minor units** — The number of decimal places a currency uses (USD/EUR = 2, TZS/JPY = 0,
  KWD/BHD = 3). Comes from the currency record; the system never assumes "2" (FR-CUR-05, BR-CUR-03).
- **FX gain/loss** — **Deferred.** The gain or loss arising when a foreign-currency balance is settled
  at a different rate than it was billed (*realised*) or re-stated at period end (*unrealised*). Out
  of scope for v1 (FR-CUR-11/12); not precluded by the v1 model.

## Terminology rulings (pick one, stay consistent)
- Use **user** (not "account") for the login identity; "account" only when discussing lockout state.
- Use **company** for the legal entity, **branch** for the location — never interchangeably.
- Use **permission** (not "privilege" or "right") for the atomic access unit.
- Use **party** for the master-data category; name the specific kind (**customer / supplier / sales
  agent**) when you mean one. Never call a debtor or creditor a "party" — they are finance *lenses*.
- Use **app user** for the login identity, **employee** for an HR record (deferred), **party** for an
  external counterparty — three distinct things; never blur them.
- Money is always a **monetary amount** (value + currency), never a bare number; say **base currency**
  for a company's reporting currency and **document / transaction currency** for what a transaction is
  expressed in — never conflate the two.

## Products

- **Product / Item** — a catalogue entry the company produces, buys, or sells; the master definition,
  **not** a stock quantity. "Product" is canonical; "item" is a synonym.
- **Goods** — a tangible product. **Service** — an intangible product. Every product is one or the other.
- **Sellable** — may appear on a customer sale. **Stockable** — inventory quantities are tracked (in
  the future Stock module). Two independent flags; a service is non-stockable, a raw good may be
  non-sellable.
- **Unit of measure (UoM)** — how a product is counted. **Base unit** (piece, kg, litre) — stock is
  held in it. **Bulk pack** (carton, crate) — a larger unit with a **conversion factor** to the base.
- **Barcode** — a scannable product identifier; a product may have several, one **primary**; unique
  within the company.
- **Price list** — a named selling-price set (Retail / Wholesale / Distributor); a product appears on
  one or more, with a currency-aware price per list. **Cost price** — what the product costs the
  company, tracked separately.
- **Composition / Recipe / BOM** — the **components** (other products) and quantities that make up a
  **composed** product (Ugali Meat = 1 Ugali + 1 Meat). v1 is **single-level** and records structure
  only — no stock deduction or cost roll-up yet.
- **Stock-on-hand** — the quantity of a stockable product at a branch. **NOT the Products module** — a
  future Stock concern. Products are definitions; stock-on-hand is a level. Never conflate them.

## Sales

> Status: **RATIFIED 2026-06-07** — terms reflect the ratified v1 in sales.md.

- **Sale** — the act and record of selling priced products to a customer; the umbrella term. The
  recorded instance is a **sales document**.
- **Sales document** — a recorded sale. v1's canonical sales document is the **Invoice**. A **Sales
  Order** and a **POS sale/receipt** are other sales-document kinds (channels), both **deferred**.
- **Channel** — *how* a sale is captured: **Sales Order (SO)**, **Invoice**, or **POS till**. They
  share the sale spine and differ in lifecycle, who captures them, and how money is taken. **v1 builds
  the Invoice only**; POS and SO are deferred.
- **Invoice** — the canonical v1 sale: the demand/record of a sale (may be unpaid). Distinct from a
  **receipt**. Carries customer, attached agent, lines, VAT, totals, document number.
- **Receipt** — evidence that **money was taken** against a sale. A cash sale produces an invoice and a
  receipt at once; a credit invoice produces a receipt later when paid. **Invoice ≠ receipt** — never
  use them interchangeably.
- **Sales line** — one product on a sale: product, quantity (in a chosen base/bulk unit), unit price,
  line discount, line VAT, line total. Every amount is a monetary amount (amount + currency).
- **Attached sales agent** — the agent (internal app-user or external broker) a sale is **attached to**
  (Parties). Distinct from the **operator** (the logged-in user who keys the sale). Internal agent
  auto-defaults to the operator when the operator is that agent. v1 captures the attachment only; it
  does **not** compute commission.
- **Operator** — the logged-in user creating the sale; not necessarily the attached agent.
- **Tender** — a means of payment taken against a sale. **v1: cash and mobile money** (card deferred).
  A sale may be settled by one tender or **split** across several, **paid in full at finalise**.
- **Settlement** — recording payment against a sale. A fully-paid sale is **settled**; **v1 takes
  payment in full at finalise**, so there is **no part-settled / outstanding-balance state** until
  credit sales land (deferred).
- **Credit sale** — a sale where an account customer does not pay now; an amount becomes a
  **receivable** drawn against the customer's credit limit. **Deferred in v1** (paid-at-sale only;
  lands with a Finance-aware round); a **walk-in customer can never take credit**.
- **Discount** — a reduction applied to a sale, at **line level** and/or **document level** (percent or
  amount). Applied **before VAT** on the taxable base. Both supported in v1.
- **Price override** — a permission-gated, **audited** manual change to a line's unit price away from
  the price-list value (original and applied price both recorded). An override beyond a configured
  threshold may require supervisor approval (threshold value OQ-SALES-10).
- **Commission (on a sale)** — the agent's earning on a sale. v1 **records/captures** a commission
  record but **does not compute** it (rates deferred, OQ-PARTY-03).
- **VAT status (of a product)** — **standard-rated** (TZ standard rate), **zero-rated** (0% but a
  taxable supply), or **exempt** (no VAT, outside the taxable base). A **field on the product master**
  (resolves Products OQ-PROD-05 = yes); the rate is maintained data, **never hard-coded**. v1 computes
  VAT per line from this status.
- **VAT invoice** — an invoice carrying seller VRN, customer details, per-line tax, and a VAT summary
  by rate band. v1 produces this. **Not** the same as a fiscal receipt.
- **Fiscalisation (TRA EFD/VFD)** — applying TRA electronic-fiscal-device rules: a fiscal receipt
  number, signing/QR, and the TRA-mandated format. **Deferred in v1** as a separable later integration;
  a v1 VAT invoice is **not** a TRA fiscal receipt until that integration lands.
- **Void** — a permissioned, audited reversal of a finalised sale (and its receipt). **The only v1
  correction path.** **Return / credit note / refund** (partial reversal with restocking and reverse
  tender) is **deferred**.
- **Till / POS session (shift)** — a cash point and the open period a cashier works it, opened with a
  **float** and closed with a **cash-drawer reconciliation** (X/Z report). A POS concept; **deferred**
  with the POS channel.
- **Document number** — a sale's identifier, **unique within its company**, allocated from the generic
  `code_sequence` numbering primitive (ADR-0007). Not the same as a TRA fiscal receipt number.

## Stock

> Status: **Ratified 2026-06-07** — terms reflect the owner-confirmed v1 in stock.md. Built with
> Purchases this round.

- **On-hand / stock-on-hand** — the current quantity of a **stockable** product **at a branch**, in
  the product's **base unit**. The level the Stock module owns. A non-stockable product or service
  **never** has on-hand. Never conflate with the Products **catalogue** (a definition, not a level).
- **Stock movement** — one recorded, **immutable** change to on-hand: a row in an **append-only
  ledger** carrying a **type**, a **signed quantity** (base units), the product, the branch, a
  timestamp, the actor, and a **reference** to its cause. On-hand is the running sum of movements; a
  movement is never edited or deleted (corrected by a compensating movement).
- **Movement type** — *why* a movement happened: **GOODS_RECEIPT** (IN, from a purchase),
  **SALE_ISSUE** (OUT, from a finalised sale), **SALE_REVERSAL** (IN, compensating a voided sale),
  **ADJUSTMENT** (± manual, reasoned), **OPENING_BALANCE** (initial seed). **TRANSFER_OUT /
  TRANSFER_IN** (branch-to-branch) are reserved but **deferred** in v1.
- **Base unit (in Stock)** — the unit on-hand and every movement are expressed in (Products
  FR-PROD-05). A bulk-pack quantity (crate, carton) converts to base **before** it touches stock.
- **Negative on-hand** — on-hand below zero. **Valid and flagged in v1** (overselling allowed): more
  was issued than recorded as received. A surfaced data-accuracy signal, **never** a blocked sale.
- **Overselling** — issuing more than is on-hand. **Allowed** in v1: the sale proceeds, on-hand goes
  negative, the negative level is flagged. Stock never blocks a sale (sales.md FR-SALES-21).
- **Recipe explosion** — when a **composed** product is sold, deducting its **component** products'
  on-hand per the single-level recipe (Products §9), **not** the composed product itself. A
  non-stockable component is skipped (recorded).
- **Adjustment** — a manual ± stock movement with a **mandatory reason** (count correction / damage /
  shrinkage / other), permission-gated and audited. The v1 way to correct on-hand.
- **Opening balance** — an initial on-hand seeded for a never-before-tracked stockable product at a
  branch (an `OPENING_BALANCE` movement).
- **Idempotency (stock consumption)** — processing the same outbox event twice yields the **same**
  on-hand: a redelivered `SALE.FINALISED` / `SALE.VOIDED` / `STOCK.RECEIVED` does **not** move stock
  again. A v1 release blocker if violated.
- **Quantity-only valuation** — v1 Stock tracks **quantity** and movement history only — **no** FIFO /
  weighted-average / standard cost, **no** stock value, **no** COGS, **no** composed-cost roll-up.
  Deferred to a Finance-aware round; the v1 model must not preclude it.
- **Transactional outbox (`domain_event`)** — the platform mechanism by which one module's change
  (a sale finalising, a purchase receipting) reliably triggers another module's effect: the producer
  writes an event row in the **same transaction**; a poller/dispatcher delivers it; **never** an
  in-memory event (lost on crash). **Built this round** (own ADR); **Stock is its first consumer**.
- **`SALE.FINALISED` / `SALE.VOIDED`** — outbox events Sales emits (contract fixed in ADR-0008 D-9);
  Stock consumes them to issue / reverse stock. **`STOCK.RECEIVED`** — the outbox event Purchases'
  **Goods Receipt** emits on finalise; Stock consumes it to receive stock IN.

## Purchases

> Status: **Ratified 2026-06-07** — terms reflect the owner-confirmed v1 in purchases.md (a
> **two-document** flow: Purchase Order, then a separate Goods Receipt). Built with Stock this round.

- **Purchase** — the act and record of buying products from a supplier; the umbrella term covering a
  Purchase Order and the Goods Receipt(s) raised against it.
- **Purchase Order (PO)** — the **v1 ordering document**: a commitment to buy from a supplier, raised
  **before** goods arrive, with **ordered lines** (product × ordered-qty × unit × unit-cost). A PO
  **moves no stock**. Lifecycle DRAFT → ORDERED → (partially/fully) RECEIVED → CLOSED / VOID. Numbered
  `PO-####`.
- **Goods Receipt (GR / GRN)** — the **v1 receiving document**: records the actual arrival of goods
  **against a PO**, receiving some or all of the ordered quantity. **Finalising a Goods Receipt pushes
  stock IN** (emits `STOCK.RECEIVED`; Stock posts a `GOODS_RECEIPT` movement). Lifecycle DRAFT →
  RECEIVED → VOID. Numbered `GRN-####`, assigned at receive. (NOT the same as a single-step GRN — in v1
  a Goods Receipt is always raised against a PO.)
- **PO line** — one ordered product on a PO: product, **ordered quantity** (base/bulk unit), **unit**,
  **unit cost** (a monetary amount), line total. Quantity converts to base per Products FR-PROD-06.
  Each PO line tracks an **outstanding quantity**.
- **GR line** — one received product on a Goods Receipt: against a specific PO line, with a **received
  quantity** ≤ that PO line's outstanding quantity.
- **Partial receipt** — receiving **less than** the ordered quantity in one Goods Receipt; the
  remainder stays **outstanding** and can be received on a later Goods Receipt against the same PO,
  until fully received.
- **Outstanding quantity** — per PO line, ordered **minus** cumulative received across all (non-void)
  Goods Receipts against it. Drives the PO's RECEIVED / CLOSED state.
- **Goods receipt (the act)** — receiving purchased goods into inventory: the event that pushes stock
  **IN** (a `GOODS_RECEIPT` movement via the `STOCK.RECEIVED` outbox event). In v1 it is the act of
  finalising a **Goods Receipt** document against a PO.
- **Cost** — the purchase price the supplier charges, a **monetary amount** (amount + currency).
  Recorded on the PO/GR line; in v1 **not** turned into a stock value, VAT, or a payable.
- **Supplier invoice / bill** — the supplier's **demand for payment** (an accounts-payable document),
  distinct from the PO and the Goods Receipt. **Deferred in v1** (AP/Finance); a v1 purchase owes
  nothing.
- **3-way match** — matching PO ↔ Goods Receipt ↔ supplier invoice before paying. **Deferred in v1**;
  v1 builds the PO ↔ Goods Receipt match (ordered-vs-received) only, no invoice leg.
- **Return to supplier / debit note** — sending goods back and reversing the receipt. **Deferred in
  v1**; the v1 correction path is a permissioned **void** of the Goods Receipt.
- **Landed cost** — the full cost of getting goods to the shelf (purchase price + freight + duty +
  insurance). **Deferred**; v1 records the supplier's line cost only.
- **Void (purchase)** — a permissioned, audited reversal of a received Goods Receipt (and its
  stock-in), or of a PO, mirroring the Sales void. Voiding a Goods Receipt **restores** the received
  quantity to the PO's outstanding. The v1 correction path; full returns-to-supplier are deferred.

## Routes

> Status: **Ratified 2026-06-08** — terms reflect the owner-confirmed v1 in routes.md. A per-company
> master sibling to Customer/Agent; adds a nullable route to the Sales invoice (ADR-0008 → ADR-0012).

- **Route** — a per-company **master** record naming a **physical sales area / zone** where customers
  reside and along which **external** sales agents sell. Carries a code (`ROUTE-####`), a name, a
  free-text location identifier, a MasterStatus, and audit. A **sibling master** to Customer/Agent — it
  is **not** a party, **not** a branch, and **not** the customer's region/district. Canonical term:
  *route*.
- **Location identifier (of a route)** — the route's **free-text** description of the area it covers
  (e.g. "Kariakoo market block 3–7"). v1 geography is **free-text only** — **not** bound to
  region/district or to any geo-hierarchy/coordinates (a structured `route_geography` binding is
  deferred).
- **Route ↔ Customer membership** — the **many-to-many** assignment of customers to a route (a customer
  may belong to several routes; a route holds many). **All customers routable** (cash/walk-in +
  credit/account). An **explicit, curated** grouping — **never** auto-derived from the customer's address.
- **Route ↔ Agent assignment** — the **many-to-many** assignment of **EXTERNAL** agents to a route (an
  external agent covers several routes; a route has several). **INTERNAL agents are never
  route-assigned.** Mirrors the `agent_branch` association pattern.
- **Primary agent (of a route)** — an **optional, advisory, at-most-one** external agent flagged as a
  route's main coverer. Used to **default the route onto an invoice** from the selling agent. A hint, not
  exclusivity — other assigned external agents still cover the route.
- **Route ↔ Branch association** — the per-company **multi-branch** association making a route
  visible/usable at given branches (mirrors `customer_branch` / `agent_branch`). A route can **span
  branches**.
- **Route on the invoice** — a **nullable** route reference recorded on a sales invoice noting which
  route the sale came from. **Defaulted from the selling agent's primary route, operator-editable,
  OPTIONAL** — a blank route never blocks a sale. **Captured, not validated** against the customer or
  agent in v1 (accepted risk). Cannot be auto-derived from the customer (route↔customer is N:M).
- **Captured-not-validated (route)** — the v1 stance that the invoice route is **recorded as
  defaulted/supplied** but **not** checked against the customer's or agent's route memberships. The input
  to the deferred route-coverage / sales-by-route reporting; never a control on the sale.
