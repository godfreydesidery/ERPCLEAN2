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
- **Product master data** — the controlled, shared definition of a product (code, barcode, unit +
  conversion, sellable/stockable, VAT status, prices, cost, composition). Created once by the
  **master-data owner** (procurement, `PRODUCT.MANAGE`) and consumed read-only everywhere
  (`PRODUCT.VIEW`); **production views and consumes products, it does not create them** (it requests
  a new SKU). Applies to sourced and manufactured goods alike. Ruling:
  [product-master-data-ownership.md](product-master-data-ownership.md).
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

## General Ledger (GL) / Financial Accounting

> Status: **Ratified 2026-06-08** — terms reflect the owner-confirmed GL Increment 1 in gl.md. GL = the
> books; the critical-path gate the rest of the roadmap (AR/AP/Cash/Reporting) posts into / reads from.

- **General Ledger (GL) / the books** — the single, **append-only, double-entry** record of every
  financial effect, organised by **account**, from which a trial balance / P&L / balance sheet are read.
  The financial **spine**; per company.
- **Chart of accounts (CoA)** — a company's complete list of **accounts**, organised by **numeric range**
  (1000s Assets, 2000s Liabilities, 3000s Equity, 4000s Income, 5000s Expenses). **System-seeded** with a
  standard Tanzanian small-business set, then **editable** (add/edit/deactivate; **cannot delete an
  account that has postings**). Per company.
- **Account (GL account)** — a named bucket in the CoA that financial effects post to (e.g. `1200 Accounts
  Receivable`, `4100 Sales Revenue`). Carries a **code** (unique per company), a **name**, an **account
  type**, an active/inactive state, and audit. **Not** a party (customer/supplier) and **not** a cash/bank
  account (a money location, T1.4) — three distinct things.
- **Account type** — one of **ASSET · LIABILITY · EQUITY · INCOME · EXPENSE**. Drives **financial-statement
  placement** (INCOME/EXPENSE → P&L; ASSET/LIABILITY/EQUITY → Balance Sheet) and **normal balance**
  (ASSET/EXPENSE = debit; LIABILITY/EQUITY/INCOME = credit). The **type**, not the code range alone, is the
  authority for placement and sign.
- **Normal balance** — the side (**debit** or **credit**) an account normally carries a positive balance
  on, set by its account type. Used for statement sign/presentation, **never** to block a posting.
- **Debit / Credit** — the two sides of double-entry. Every **journal line** is one or the other; every
  **journal entry**'s debits sum equals its credits sum. Whether a debit increases or decreases an account
  depends on the account's type.
- **Journal entry** — one **balanced** financial transaction posted to the books: a date, a description, a
  source reference, and **≥ 2 journal lines** whose **debits == credits**. The unit of posting; once posted
  it is **immutable** (corrected only by a reversing entry). **Not** a sales invoice (the invoice is the
  *source*; the journal entry is what GL posts from it).
- **Journal line** — one leg of a journal entry: exactly **one account** and a **debit OR a credit amount**
  (positive; never both, never neither). Base currency in v1.
- **Journal batch** — the numbered container (`JB-####` from `code_sequence`) a posting run groups its
  journal entries under. A manual post, a sales auto-post, and a reversal are each one batch.
- **Posting** — the act of **writing a balanced journal entry to the books**. **Append-only**: a posted
  entry is never edited or deleted (PROJECT-CONVENTIONS §3.6). Not "saving a draft."
- **Fiscal period** — one **month** of a company's financial year (12 per year). **OPEN** (postings
  allowed) or **CLOSED** (postings rejected). The **fiscal-year start month is configurable per company**
  (e.g. Jan or Jul).
- **Fiscal year** — the 12-period accounting year of a company, beginning at its configured start month.
- **Trial balance (TB)** — the read listing every account with its total debits / total credits (or net),
  as-at a date or over a period. A sound set of books yields **total debits == total credits** (the TB
  **nets to zero**) — the first proof the books are correct; the source for P&L / balance sheet (Reporting).
- **Control account** — a GL account whose balance summarises a sub-ledger (e.g. `1200 Accounts Receivable`
  is the AR sub-ledger's control account). v1 **seeds** the AR/AP control accounts and **posts AR/VAT** from
  sales; **sub-ledger reconciliation is a later increment** (T1.2/T1.3).
- **Reversing entry** — a journal entry that **negates** a prior posted entry (debits ↔ credits), used to
  correct an error or void a posted transaction. The **only** way to undo a posting in an append-only
  ledger; the original entry **stays** on the books beside the reversal — never deleted.
- **Opening balance** — an account's starting balance when the books begin (or at a new fiscal year),
  entered as a **manual journal** that must itself **balance** (assets debit, liabilities/equity credit,
  the balancing figure to equity / retained earnings).
- **`gl_configs` (account mapping)** — the per-company config mapping **posting roles** (SALES_REVENUE,
  VAT_PAYABLE, ACCOUNTS_RECEIVABLE, CASH, …) to **actual CoA accounts**, so the auto-poster never hard-codes
  account codes. The required sales mappings **must be set before sales auto-posting works**.
- **Sales auto-posting** — finalising a sale **auto-posts a balanced journal** via the outbox (a
  `SalesPostingHandler` consuming `SALE.FINALISED`, mirroring `SaleIssueStockHandler`): **DR Accounts
  Receivable / Cash, CR Sales Revenue, CR VAT Payable**, using `gl_configs`. **Idempotent**; **`SALE.VOIDED`
  posts the reversing entry**. GL consumes the event as a **DTO only** — it never imports a Sales entity.
- **Double-entry invariant** — every journal entry has **≥ 2 lines**, **Σ debits == Σ credits**, each line
  hits **one account** with a **debit OR a credit**, and the entry's date falls in an **OPEN** period;
  otherwise the entry is **rejected** (balanced-or-rejected).
- **SYSTEM (auto-poster)** — **not a human** — the outbox consumers (`SalesPostingHandler`,
  `SaleVoidingHandler`) that post the sales journal / reversal automatically under the **originating event's
  company/branch context**, with no user permission check (the producing sale was already permissioned).

### GL terminology rulings (pick one, stay consistent)
- Use **account** for a GL bucket in the chart of accounts; never call a customer/supplier an "account"
  (they are **parties**) and never call a cash/bank money location a GL "account" without the **cash/bank**
  qualifier — three distinct things.
- Use **journal entry** for a balanced posting, **sales invoice** for the source sales document — never
  blur the source with what GL posts from it.
- Use **posting** for writing to the books (append-only); a posted entry is **reversed**, never **edited**
  or **deleted**. Say **reversing entry** for the correction, never "amend the journal."
- Use **fiscal period** (one month) and **fiscal year** (12 periods) consistently; a period is **OPEN** or
  **CLOSED**, never "active/inactive" (that is an *account* state).

## Accounts Receivable (AR) — the customer sub-ledger

> Status: **Ratified 2026-06-09** — terms reflect the owner-confirmed AR Increment 2 (T1.2) in
> accounts-receivable.md. AR = who owes us; the **detail behind the GL `1200 Accounts Receivable` control
> account**.

- **Accounts Receivable (AR)** — money customers owe us for credit sales not yet paid; **the customer
  sub-ledger** — the per-customer detail behind the GL `1200 Accounts Receivable` control account.
- **Sub-ledger** — a detailed ledger for one **control account**: AR holds, per customer, the open items +
  receipts whose **net balance equals** the GL AR control-account balance. The *detail*; the GL control
  account is the *summary*.
- **Control account (AR)** — the GL account `1200 Accounts Receivable` whose balance summarises the AR
  sub-ledger. The sub-ledger must **reconcile** to it at all times.
- **Open item** — an unpaid (or partly paid) receivable: one credit-sale invoice's outstanding amount in
  the sub-ledger. Created when a **credit** sale finalises; reduced by receipts / credit notes; closed when
  settled or written off. **A cash sale creates no open item** (settled at the till).
- **Receipt (AR)** — money received from a customer against their account (`RCT-####`). **Not** the Sales
  **receipt** (the till slip on a paid-at-sale invoice). A receipt is **allocated** to open items and posts
  **DR Cash/Bank / CR AR control** to GL.
- **Allocation** — applying a receipt (or credit note) to specific open items, reducing each. v1
  **auto-allocates oldest-open-first** by default; an operator may **manually override**. The amount
  allocated is **≤ the receipt** (over-allocation rejected); a remainder is **on-account**.
- **On-account / unapplied (unallocated) receipt** — a receipt (or part of one) **not yet allocated** to an
  open item: a **credit balance** on the customer's account, applied later. Allowed in v1.
- **Ageing bucket** — a band of how overdue an open item is, by **due date**: **Current** (not yet due),
  **1–30**, **31–60**, **61–90**, **90+** days overdue.
- **Due date** — when an open item falls due, from the **customer's payment terms** applied to the invoice
  date; if no terms, the v1 default is **net-on-receipt (0 days)** so due date = invoice date.
- **Statement** — a per-customer document listing the customer's **open items + ageing** (and recent
  activity), to **view or print**. v1 = read/print only (no emailing / dunning).
- **Write-off** — removing an uncollectable open item as a **bad debt**: closes the open item and posts
  **DR bad-debt expense / CR AR control** to GL.
- **Credit note (AR)** — a document that **reduces a receivable** (return / over-charge correction): reduces
  the open item and the AR control. Reduces what *a customer owes us* — not the AP **debit note**.
- **Opening balance (AR)** — a customer's pre-existing receivable at go-live, entered as an opening open
  item; the sum of opening open items equals the AR control's opening balance.
- **Credit limit** — the maximum a credit customer may owe (`customers.credit_limit_amount`, Parties V2). v1
  **warns + allows with `SALES.CREDIT.OVERRIDE`** (audited) when a credit sale would push current AR balance
  + the new sale over it; the check lives in the **Sales finalise path**.
- **`SALES.CREDIT.OVERRIDE`** — the IAM permission to finalise a credit sale that exceeds the customer's
  credit limit (the audited override).
- **No-double-post (AR)** — the rule that AR **does not post to GL when it creates an open item**, because
  the credit sale's `SalesPostingHandler` **already** debited the AR control; only the *new* events
  (receipt, write-off, standalone credit note) post to GL.

### AR terminology rulings (pick one, stay consistent)
- Use **open item** for an unpaid receivable in the sub-ledger; **receipt** for money received against a
  customer's account (`RCT-####`) — never call the AR receipt the Sales **receipt** (the till slip).
- Use **allocation** for applying a receipt to open items; it is **not** a GL **posting** (the cash leg
  posts once at the receipt; re-allocation posts nothing).
- Use **on-account / unapplied** for an unallocated receipt credit; never "deposit" (deposits-as-liability
  are deferred).
- Use **control account** for the GL `1200` summary, **sub-ledger** for the per-customer detail; the two
  **reconcile** — never blur them.
- A **customer** is a **party**; the AR control **account** is a GL bucket — three distinct things (party /
  GL account / cash-bank account), never interchangeably "account".

## Accounts Payable (AP) — the supplier sub-ledger

> Status: **Ratified 2026-06-09** — terms reflect the owner-confirmed AP Increment 2 (T1.3) in
> accounts-payable.md. AP = who we owe; the **detail behind the GL `2100 Accounts Payable` control
> account**. AP is **bill-entry-driven**.

- **Accounts Payable (AP)** — money we owe suppliers for bills not yet paid; **the supplier sub-ledger** —
  the per-supplier detail behind the GL `2100 Accounts Payable` control account.
- **Sub-ledger (AP)** — a detailed ledger for the AP control account: holds, per supplier, the open payables
  whose **net balance equals** the GL AP control-account balance.
- **Control account (AP)** — the GL account `2100 Accounts Payable` whose balance summarises the AP
  sub-ledger. The sub-ledger must **reconcile** to it at all times.
- **Supplier bill / supplier invoice** — the supplier's **demand for payment** (their invoice), the AP
  document the operator **enters** (`BILL-####`). Distinct from the **PO** (our order) and the **Goods
  Receipt** (our record of arrival). 3-way matched; a matched bill becomes a **payable**.
- **Payable** — an entered, matched bill we **owe**: the supplier sub-ledger open item, reduced by payments
  / debit notes, closed when paid. Created when a bill **matches within tolerance** and posts to GL.
- **3-way match** — reconciling the **supplier bill** against the **PO** and the **Goods Receipt** —
  **quantity AND price** — within a **tolerance**, before the bill becomes a payable. (Purchases v1 built the
  **two-way** PO↔GR match; AP adds the **third leg**, the bill.)
- **Tolerance** — the allowed price (and/or quantity) variance within which a bill matches automatically; an
  **over-tolerance** bill is **held for review** (accept-variance or reject). v1 default ≈ price within 2% or
  a small absolute (OQ-AP-01).
- **GRNI (Goods Received Not Invoiced)** — the liability for goods received but not yet billed. **v1 has NO
  GRNI accrual** — the goods receipt posts no liability, so between receipt and bill entry the liability is
  **not on the books** (the accepted bill-driven-AP gap).
- **Payment (AP)** — money paid to a supplier against open payables; settling it posts **DR AP control / CR
  Cash/Bank** to GL. v1 = a **single** bill payment and a **payment run**.
- **Payment run** — a batch (`PAYRUN-####`) that **selects due / matched bills** and pays them in **one
  payment**, settling many payables at once.
- **Debit note (AP)** — a document that **reduces an open payable** (a supplier credit / over-charge /
  short-delivery correction): reduces the sub-ledger payable and the AP control. Reduces what *we owe a
  supplier* — not the AR **credit note**.
- **Opening balance (AP)** — a supplier's pre-existing payable at go-live, entered as an opening payable; the
  sum of opening payables equals the AP control's opening balance.
- **First-GL-posting-for-the-purchase (AP)** — the rule that, because the goods receipt posts **Stock only,
  NOT GL**, the **AP bill match is where the purchase first hits the books** (CR AP control). The mirror of
  AR's no-double-post rule.
- **Inventory-or-Purchases (bill debit)** — the GL account the matched bill debits (per `gl_configs`:
  `INVENTORY` or a purchases / GRNI-clearing / expense account). v1 books it **without a COGS roll-up**
  (inventory valuation + COGS are T2.2, deferred).

### AP terminology rulings (pick one, stay consistent)
- Use **supplier bill** for the supplier's invoice we enter; never confuse it with the **PO** (our order) or
  the **Goods Receipt** (our record of arrival) — the three documents the 3-way match reconciles.
- Use **payable** for an open matched bill we owe; **payment run** (batch) / **single payment** for settling
  it; never "invoice payment" loosely.
- Use **debit note** for reducing what we owe a supplier (AP); use **credit note** for reducing what a
  customer owes us (AR) — never swap them.
- Use **control account** for the GL `2100` summary, **sub-ledger** for the per-supplier detail; the two
  **reconcile** — never blur them.
- A **supplier** is a **party**; the AP control **account** is a GL bucket — never interchangeably "account".
- Say **bill-entry-driven** (the operator enters the bill) — a goods receipt does **not** create a payable
  in v1; never say "the receipt makes us owe."

## Cash & Bank — the cash book + bank book

> Status: **Ratified 2026-06-09** — terms reflect the owner-confirmed Cash & Bank Increment 3 (T1.4) in
> cash-and-bank.md. Cash & Bank = where the money actually lives; named money locations each linked to a GL
> `1xxx` account, reconciled to the bank statement and to GL.

- **Cash/bank account** — a named **money location** the company holds funds in: a **CASH** account (petty
  cash, a till) or a **BANK** account (an account at a bank, per bank / per branch). Carries a name, a type
  (CASH | BANK), bank details (bank name, account number, bank branch — for BANK), a currency (= base in v1),
  a **link to one GL `1xxx` asset account**, an active/status state, audit, and a `CB-####` code. **Not** a
  GL account (a posting bucket) and **not** a party — a *money location* (party / GL account / cash-bank
  account are three distinct things).
- **Cash book** — the set of CASH accounts (petty cash, tills) and their transactions. **Bank book** — the
  set of BANK accounts and their transactions.
- **Linked GL account** — the single GL `1xxx` asset account a cash/bank account maps to. Every movement on
  the account posts to it; the account's book balance equals its balance. **Replaces the single
  `gl_configs` `CASH` account** with real named accounts.
- **Book balance** — a cash/bank account's balance **per our records**: the running sum of its (non-void)
  transactions. Equals its linked GL account's balance (the GL reconciliation rule).
- **Statement balance / bank balance** — the **bank's** closing balance for an account as at a statement
  date, taken from the bank statement and entered during reconciliation. The figure the **book balance must
  agree with** to complete a reconciliation.
- **Cleared / uncleared** — a transaction is **uncleared** until it is confirmed against the bank statement,
  then **cleared**. The operator marks transactions cleared during reconciliation; once a transaction is in
  a **completed** reconciliation its cleared flag is **immutable**.
- **(Bank) reconciliation** — the **manual** v1 act of agreeing an account's records to the bank statement:
  mark the cleared transactions, record a statement date + statement closing balance, and confirm **book
  balance == statement balance** before completing. No statement file import in v1.
- **Inter-account transfer** — moving money **between two cash/bank accounts of the same company** (bank →
  petty cash, cash deposit → bank): posts **DR the destination account's GL account / CR the source
  account's GL account**, balanced. Numbered `CBT-####`. Source ≠ destination.
- **Direct cash/bank entry** — an ad-hoc receipt or payment **not** tied to AR/AP (bank charges, interest
  received, owner drawings, sundry cash expense): posts the cash/bank account's GL account against a **chosen
  GL counter-account** (income / expense / equity). The treasury equivalent of a manual journal that moves a
  real money location.
- **Cheque register** — the record of cheques related to bank-account payments: each cheque carries a
  **cheque number** (unique per bank account), a **status** (ISSUED | CLEARED | CANCELLED), an **issue date**
  and a **value date**, the bank account it draws on, and the payment it settles.
- **Post-dated cheque (PDC)** — a cheque whose **value date is later than its issue date** — written now,
  clears later; tracked as ISSUED until it clears on/after its value date.
- **Default cash/bank account** — the **one** cash/bank account per company used when an AR receipt / AP
  payment does **not** name a target account. At most one default per company.

### Cash & Bank terminology rulings (pick one, stay consistent)
- A **cash/bank account** is a **money location** (this module); a **GL account** is a posting bucket (gl.md);
  a **party** is a customer/supplier (Parties) — three distinct things, never interchangeably "account". The
  cash/bank account *links to* exactly one GL account.
- Use **book balance** for our records and **statement / bank balance** for the bank's — the reconciliation
  makes them **agree**; never conflate them.
- A **cleared** transaction (confirmed on the bank statement, later) is **not** a **posted** journal entry
  (on the books at the moment of the transaction); every transaction posts to GL immediately, clearing is a
  separate bank-statement confirmation.
- Use **inter-account transfer** for money between our own accounts; never call it an AR **receipt** (from a
  customer) or an AP **payment** (to a supplier).
- A **cheque** is the *instrument* in the register; the **payment** is the financial event it settles —
  never blur the cheque with the payment's GL posting.

## VAT Return / Tax — the monthly VAT obligation

> Status: **Ratified 2026-06-09** — terms reflect the owner-confirmed VAT Return / Tax module (T1.5 /
> Phase A) in vat-return.md. The last Tier-1 finance piece; a monthly, accrual-basis return that nets output
> (Sales) against input (AP), files to a locked GL settlement, carries a net credit forward, and tracks
> withholding tax.

- **VAT (Value Added Tax)** — the Tanzanian consumption tax (standard rate **18%**), administered by
  **TRA**, charged on taxable sales (**output**) and incurred on taxable purchases (**input**). A
  VAT-registered company collects output VAT for TRA, recovers input VAT against it, and remits the **net**
  monthly.
- **Output VAT** — the VAT the company **charges customers** — the sum of `sales_invoices.vat_total_amount`
  (by band, from `tax_summary`) for invoices **FINALISED in the period**. Already credited to GL **`2200 VAT
  Payable`** by the sales auto-poster. A liability to TRA.
- **Input VAT** — the VAT the company **was charged by suppliers** — the sum of `supplier_bills.vatAmount`
  for **matched/approved bills DATED in the period**. **Recoverable** against output. Needs a **VAT Input /
  Recoverable** account that does not exist yet (the ADR-0017 seam, OQ-VAT-01).
- **Net VAT** — **output VAT − input VAT + adjustments + opening credit carried forward**, for the period.
  **Net positive** = **VAT payable** to TRA; **net negative** = a **VAT credit**.
- **VAT payable (to TRA)** — a **net positive** return: what the company owes TRA for the period, due the
  **20th of the next month**.
- **VAT credit** — a **net negative** return (input > output): a credit balance. In v1 it **carries
  forward** (not a cash refund) — never call a carried credit a "refund."
- **Return period** — one **calendar month** of VAT, **per company**, on an **invoice/accrual basis**;
  exactly one per company per month; **due the 20th of the following month**.
- **Accrual basis (invoice basis)** — VAT recognised when the invoice/bill is **dated**, **not** when money
  moves: output on **finalise**, input on **bill date**. (Cash-basis is deferred.)
- **Filing reference** — the **operator-entered** TRA acknowledgement/submission reference recorded when a
  return is **FILED** (there is **no** TRA e-filing in v1). Not the internal `VATR-####` document number.
- **Credit carry-forward** — a net credit becoming the **opening credit on the next period's return**,
  offsetting its net. The v1 treatment of a net credit (no cash refund).
- **VAT adjustment** — a **manual line** on a **DRAFT** return (a **reason + amount + sign**) that **affects
  the net** — bad-debt VAT relief, prior-period corrections, credit/debit-note VAT. Audited; DRAFT-only.
- **Withholding tax (WHT)** — tax the **payer** withholds from a payment and remits to TRA **on behalf of**
  the payee (e.g. the TZ **2% withholding**). When **we pay a supplier** we may withhold (pay less, owe TRA
  a **WHT liability**); when a **customer pays us** they may withhold (we receive less, hold a **WHT
  receivable/asset**). Reduces the cash paid/received; **separate** from the VAT output−input net.
- **Withholding VAT (WHT-VAT)** — a WHT regime where VAT is withheld at payment; modelled as a WHT **type/
  rate** on the same capture-and-track machinery, not a separate engine.
- **WHT certificate** — the document evidencing tax withheld (issued to the supplier when we withhold,
  received from the customer when they withhold); the supporting record for the WHT register / remittance.
- **WHT register / return** — the period's list of WHT **withheld** (a liability to remit) and **received**
  (a receivable) — the basis for **remitting** withheld tax to TRA. A **sibling** to the VAT return,
  **distinct** from it.
- **VAT Input / Recoverable account** — the GL `1xxx` account ADR-0017 **adds** (with a `gl_configs`
  **`VAT_INPUT`** key) to carry recoverable input VAT — it does **not** exist today (ADR-0013 D-13 has
  `VAT_PAYABLE` only). The filing seam (OQ-VAT-01).
- **VAT settlement entry** — the **synchronous GL journal** posted **on filing**: DR `2200 VAT Payable`
  (clear output), CR the `VAT_INPUT` account (clear input), book the **net** to a VAT-due liability (or
  carry a credit). Balanced; the books' record that the period was filed.

### VAT terminology rulings (pick one, stay consistent)
- Use **output VAT** for what we charge customers and **input VAT** for what suppliers charge us; the return
  **nets** them — never blur the two sides.
- Use **net VAT** for output − input + adjustments; say **VAT payable** only when the net is **positive** and
  **VAT credit** when **negative** — a net credit **carries forward**, it is **not** a "refund" in v1.
- Use **VAT return** for the periodic output−input obligation and **WHT return / register** for the separate
  withholding remittance — two distinct statutory filings; never fold WHT into the VAT net.
- Use **withholding** for tax the payer holds back **for** the payee; it reduces the **cash paid/received**,
  **not** the bill's VAT — never call a withheld amount "VAT."
- A return is **FILED** (locked, period closed), **never** "edited" — correct a filed period via the **next
  period's adjustment** (append-only), and undo a GL post via a **reversing entry**, never an edit.
- Use **filing reference** for TRA's acknowledgement and **`VATR-####`** for our internal return number —
  never conflate them; there is **no** TRA e-filing in v1.
- A **VAT account** is a GL bucket (`2200 VAT Payable`, the new VAT Input/Recoverable) — never call a
  customer/supplier "a VAT account" (they are **parties**), the three-distinct-things rule again
  (party / GL account / cash-bank account).

## Financial Reporting — the three primary statements + the GL account-ledger drill-down

- **Financial statement** — a structured, grouped presentation of the books for a management / external
  reader, derived **read-only** from GL `journal_lines`. The three primary statements (v1): the **Income
  Statement / P&L**, the **Balance Sheet**, the **Cash-Flow Statement**. **Not** a trial balance (a flat
  account list — the TB is the raw input, the statement is the presentation).
- **Income Statement / Profit & Loss (P&L)** — the statement of **INCOME − EXPENSE over a period** (a date
  range): revenue, less cost of sales = **gross profit**; less operating expenses = **net profit**. A period
  (flow) statement. Reads the INCOME (4xxx) and EXPENSE (5xxx) accounts.
- **Gross profit** — **revenue (INCOME) − cost of sales** (the cost-of-sales expense band, e.g. 5100 COGS +
  5150 Purchases). The first P&L subtotal.
- **Operating profit / net profit** — **gross profit − operating expenses** (5200 Rent, 5300 Salaries, 5400
  Utilities, …). In v1 (no separate finance/tax lines) the bottom line is **net profit**; operating and net
  profit coincide.
- **Balance Sheet** — the statement of financial position **as-at a date** (a stock statement): **ASSET =
  LIABILITY + EQUITY**, grouped **current vs non-current**. Reads ASSET (1xxx) + LIABILITY (2xxx) + EQUITY
  (3xxx) accounts + the current-year net income folded into equity. **Must balance.**
- **Current vs non-current** — the Balance-Sheet sub-grouping by realisation horizon: current = realised /
  settled within ~12 months (cash, AR, inventory, AP, tax payable); non-current = beyond (fixed assets,
  long-term loans — sparse in v1). Derived from the account-code band.
- **Retained earnings** — the EQUITY account (3900) carrying accumulated prior-period profit. On the Balance
  Sheet: **as-at equity = retained earnings + the current-year net income to date**.
- **Current-year net income** — the P&L **net profit for the current fiscal year up to the as-at date**,
  **folded into Balance-Sheet equity** so ASSET = LIABILITY + EQUITY holds mid-year. A **presentation
  derivation** (year-to-date INCOME − EXPENSE), **not** a posted closing entry (v1 has no automated year-end
  roll-up — gl.md §10.6).
- **Cash-Flow Statement (indirect method)** — how cash moved over a period, built **indirectly**: net income
  + non-cash items (depreciation — sparse in v1) ± **working-capital changes** (ΔAR, ΔAP, ΔInventory,
  ΔVAT/WHT) → **operating**; ± non-current-asset changes → **investing**; ± equity / borrowing changes →
  **financing**. The **net change in cash == the Cash + Bank GL account movement** between the two dates.
- **Operating / investing / financing** — the three cash-flow sections. Operating = cash from trading;
  investing = cash for/from long-term assets (sparse, no Fixed Assets yet); financing = cash from/to owners
  and lenders (sparse, no Loans yet). Classified from `account_type` + code range.
- **Working-capital change** — the period **change** (closing − opening) in a current operating account
  (AR, AP, Inventory, VAT/WHT control accounts), used in the indirect operating section: a rise in AR *uses*
  cash (subtract); a rise in AP *provides* cash (add).
- **Comparative period** — the **prior** period / as-at date shown **alongside** the selected one on every
  statement (this period vs prior period for P&L/CF; this date vs a prior date for BS) — standard accounting
  presentation. **Not** a budget (a plan — Budgeting, deferred).
- **Account ledger / drill-down** — for a single GL account over a period: **opening balance**, each
  **journal line** (posting date, source, reference, debit, credit), the **running balance**, the **closing
  balance**. The **drill target** from any statement line. The **GL** account ledger — **not** the AR/AP
  **sub-ledger** (per-party detail).
- **As-at vs period** — a **Balance Sheet** is **as-at a date** (a point-in-time stock, balances from
  inception to that date); a **P&L / Cash-Flow** is **over a period** (a flow between two dates).
- **Auto-derived statement mapping** — the rule that places each account on a statement: **`account_type`**
  decides the statement + top section (INCOME/EXPENSE → P&L; ASSET/LIABILITY/EQUITY → Balance Sheet — the
  type is the authority); the **account-code range** decides the sub-grouping (current/non-current;
  cost-of-sales/operating; CF section). **NO configurable statement-template table** in v1.
- **Reconciliation / correctness bar** — the exact tie a statement asserts back to GL: the **BS balances**
  (ASSET == LIABILITY + EQUITY); the **P&L net == the period's INCOME − EXPENSE GL movement**; the
  **Cash-Flow net change == the Cash + Bank GL account movement**. A broken bar is a defect / data-integrity
  alarm, surfaced — never silently corrected (Reporting is read-only).

### Reporting terminology rulings (pick one, stay consistent)
- Use **financial statement** (or P&L / Balance Sheet / Cash-Flow Statement) for the grouped presentation;
  **trial balance** for the flat account list (gl.md) — the TB is the input, the statement is the output;
  never call a statement "a trial balance."
- Use **as-at** for a Balance Sheet (a point in time) and **over a period / for a date range** for a P&L or
  Cash-Flow (a flow) — never blur the two; a Balance Sheet has no date range, a P&L has no single as-at date.
- Use **net profit** (the P&L bottom line, a period flow) distinct from **equity** (a Balance-Sheet as-at
  stock) — but say the net profit **folds into** equity (current-year net income); never equate them.
- Use **comparative** for the prior-window column (standard presentation); never call it a "budget" (a plan —
  deferred to Budgeting).
- A **drill-down** reaches the **account ledger** (GL `journal_lines` for one account); a **sub-ledger** is
  the AR/AP per-party detail (their modules) — never conflate the GL account ledger with a sub-ledger.
- Reporting is **read-only**: it **runs / views / exports** a statement; it never "posts", "files", or
  "closes" — those are GL/VAT verbs. A figure that breaks a bar is **surfaced**, never "fixed" by Reporting.
- Statement placement is by **`account_type`** (the authority — gl.md BR-GL-12); the **code range** drives
  only the sub-grouping — never place an account by code alone against its type.

## Year-End Close — freeze the year, reset P&L, roll profit into retained earnings

- **Fiscal year close (year-end close)** — the **annual** operation that **freezes** a fiscal year: it
  auto-closes the year's still-OPEN periods, posts the **closing entry** (P&L → retained earnings), and marks
  the **fiscal year CLOSED** (`fiscal_years.status = CLOSED`). One end-to-end action, per company per year.
  After it, all posting into the year is blocked (its periods are CLOSED). **Not** a **period close** (the
  monthly open/close of one `fiscal_period`, `GL.PERIOD.CLOSE`).
- **Closing entry (closing journal)** — the **one balanced journal** the close posts, dated at the fiscal
  year's **`end_date`**: **DEBIT each INCOME account** by its year balance, **CREDIT each EXPENSE account** by
  its year balance (zeroing every P&L account), with the **net profit/loss to 3900 Retained Earnings** (CR 3900
  for a profit, DR 3900 for a loss). Σ debits == Σ credits (`GLPostingService` enforces). Source type
  **YEAR_END_CLOSE**. **No Income Summary intermediate account** — the roll is **direct to 3900**. **Not** an
  **opening-balance journal** (gl.md FR-GL-13) and **not** the Reporting **current-year-earnings fold** (a
  read-time presentation derivation, NOT a posted entry — reporting.md BR-REP-05).
- **Retained earnings roll** — moving the year's **net profit/loss** into **3900 Retained Earnings** as the
  balancing figure of the closing entry. Sequential year-on-year (the prior year must be closed first); 3900
  accumulates each closed year's net. Posts to **3900** (EQUITY) — **not** **3100 Opening Balance Equity** and
  **not** any "Income Summary" account (v1 uses none).
- **Net profit / loss for the year** — the year's **INCOME − EXPENSE** (the P&L net over the fiscal year). The
  balancing figure rolled to 3900: a **profit** (INCOME > EXPENSE) **credits** 3900; a **loss** (EXPENSE >
  INCOME) **debits** 3900.
- **P&L reset (zeroing)** — the effect of the closing entry on the **INCOME (4xxx) and EXPENSE (5xxx)**
  accounts: each is brought to a **zero** year-end balance, so the new fiscal year starts the P&L fresh. The
  account ledger shows the closing line that takes it to zero (drill-down via Reporting).
- **Reopen (close reversal)** — the operation that **un-freezes** a CLOSED fiscal year for a late adjustment:
  it posts the **reversal of the closing journal** (restoring the P&L balances + backing out the 3900 roll),
  flips the **fiscal year and its periods back to OPEN**, and makes the year **re-closeable**. Append-only — a
  **new reversing entry** (source type **YEAR_END_CLOSE_REVERSAL** or a reversal-of the closing entry),
  **never** an edit/delete of the closing journal. Permission-gated (sensitive), audited. **Not** a period
  **reopen** (gl.md FR-GL-15 — re-open one closed *period*, posting nothing).
- **Closed-period posting gate** — the **existing** GL rule (BR-GL-03): a CLOSED fiscal period rejects all new
  postings. Year-end close **uses** this gate (after close, the year's periods are CLOSED → posting blocked);
  the closing / reversing entries are **system-posted** as part of the operation, dated at year-end.
- **Reverse-then-adjust-then-re-close** — the **late-adjustment flow**: reopen a CLOSED year (reversal posts,
  P&L restored, year OPEN) → post the adjusting journal(s) → re-close (a fresh closing entry posts the corrected
  net to 3900). The append-only way to correct a closed year — no closing journal is ever edited.

### Year-End Close terminology rulings (pick one, stay consistent)
- Use **year close / fiscal year close** (`GL.YEAR.CLOSE`) for the annual freeze + closing entry + retained-
  earnings roll; **period close** (`GL.PERIOD.CLOSE`) for the monthly open/close of one period — never blur the
  two (different permissions, different effects: a period close posts **nothing**; a year close posts the
  **closing entry**).
- Use **closing entry** (this slice's P&L → 3900 journal) distinct from **opening-balance journal** (gl.md
  FR-GL-13, a manual capital/equity entry) and from the Reporting **current-year-earnings fold** (a read-time
  derivation, never posted) — never conflate the posted close with the read-time fold.
- The roll posts to **3900 Retained Earnings** (EQUITY); never to **3100 Opening Balance Equity** and never via
  an **Income Summary** account (v1 has none — the roll is **direct to 3900**).
- A close **freezes** a year; a **reopen** un-freezes it — neither ever **deletes** a posted journal (BR-GL-02);
  the reopen is a **new reversing entry**, the correction path is **reverse-then-adjust-then-re-close**.
- After a close, **Reporting needs no change** — the closed year's P&L nets to zero, the rolled net sits in
  posted 3900, and the inception-to-date equity fold (ADR-0018 D-6) keeps the Balance Sheet balancing with **no
  double-count**; never say the close "requires a Reporting change."

## Inventory Valuation & COGS terms

> Source: [docs/requirements/inventory-valuation.md](inventory-valuation.md) (RATIFIED 2026-06-10). The
> valuation-depth slice on the existing stock module — moving weighted-average cost, perpetual books via GRNI,
> COGS on sale, opening valuation, the valuation report reconciled to GL, and adjustment revaluation.

- **Moving weighted average (moving average cost)** — the v1 costing method: **one running average unit cost
  per product** (per company; single location), recomputed **at each receipt** as `new_avg = (on_hand_value +
  receipt_qty × receipt_unit_cost) / (on_hand_qty + receipt_qty)`. Issues are valued at the **current**
  average and **do not** change it; the **first** receipt sets the average to the receipt unit cost. **Not**
  FIFO (cost layers) and **not** standard cost (a planned cost + variances) — both deferred.
- **Inventory value** — per product **on-hand quantity × moving-average cost**, summed = the inventory asset.
  Sits on GL **`1300 Inventory`** in the perpetual model. The **valuation report** computes it and **must tie**
  to the `1300` GL balance (the recon bar, BR-INV-06). **Not** COGS.
- **Cost of goods sold (COGS)** — the **cost** of inventory issued on a sale: **issued qty × the current
  moving-average cost**, posted **DR `5100 COGS` / CR `1300 Inventory`** on SALE.FINALISED (incl. recipe
  explosion, each component at its own average). The P&L expense matched to revenue that makes **gross margin**
  (revenue − COGS) visible. **Not** the periodic `5150 Purchases` expense.
- **GRNI (Goods-Received-Not-Invoiced)** — a **NEW clearing liability** (`2xxx`) bridging **receiving goods**
  (DR Inventory / CR GRNI at receipt) and **being billed** (DR GRNI / CR AP at bill-match). After both, GRNI
  **nets to zero** for the receipt; a standing GRNI balance is **goods received not yet invoiced** (a real
  accrual), not a leak. **Not** Accounts Payable (the billed payable) and **not** `5150 Purchases`.
- **Perpetual inventory** — inventory tracked **continuously** as an asset that rises on receipt and falls on
  issue (the v1 model). **Not** **periodic inventory** — the shipped treatment (goods → `5150 Purchases`
  expense at bill-match, value known only by a count) that v1 **replaces for stock bills** (service bills keep
  `5150`).
- **Opening (inventory) valuation** — the **one-time** seed of cost/value onto existing quantity-only on-hand
  at go-live: per product, set the average and post **DR `1300 Inventory` / CR `3100 Opening-Balance-Equity`**
  at qty × cost, **once per product**. **Not** a goods receipt (DR Inventory / CR GRNI) and **not** the
  stock-module quantity opening balance.
- **Revaluation** — a **value** change with no sale. In v1 it is the **stock-adjustment revaluation** — a
  manual adjustment posts **DR Stock-Adjustment/Shrinkage / CR Inventory** (decrease) or the reverse (increase)
  at the current average; the average is **unchanged**. General mark-to-market / standard-cost revaluation is
  deferred.
- **Shrinkage** — inventory **lost** (theft / spillage / damage / expiry), recorded as a stock adjustment
  **out**; its value (qty × avg) is expensed to the **Stock-Adjustment / Shrinkage** account (a NEW `5xxx`).
  The `AdjustmentReason` enum already carries SHRINKAGE / DAMAGE / EXPIRY / COUNT_CORRECTION.
- **Average-cost recompute** — the recalculation of a product's moving average **on a receipt** — the **only**
  event that changes the average in v1. Issues consume at the current average; opening valuation seeds it; an
  adjustment out consumes at it (without changing it).
- **Cost-into-event seam** — the gap this slice closes: the receipt unit cost lives on
  `goods_receipt_lines.unit_cost_amount` (V8) but the **STOCK.RECEIVED payload carries no unit cost** today; it
  must reach the stock receipt handler to recompute the average and post to GL (OQ-INV-05, an ADR-0020 seam).
- **Reconciliation bar (inventory)** — the rule that the valuation report's **Σ (qty × avg) == the `1300
  Inventory` GL balance** (BR-INV-06); a disagreement is a **finance-grade defect** — the same discipline as
  the VAT return's BR-VAT-08 and Reporting's recon ties.

### Inventory-valuation terminology rulings (pick one, stay consistent)
- Use **moving weighted average** (or **moving average cost**) for the v1 method — never "weighted average"
  bare (which a reader may take as a periodic-end average) and never "average cost" loosely; the average
  **recomputes at receipt** and **issues consume at it**.
- Use **inventory value** for the asset (qty × avg, on `1300`) and **COGS** for the issued cost (on `5100`) —
  never blur them; a receipt grows inventory value, a sale moves it into COGS.
- Use **GRNI** for the goods-received-not-invoiced clearing liability — never "purchases accrual" loosely and
  never conflate it with **AP** (the billed payable) or **`5150 Purchases`** (the periodic expense v1 retires
  for stock bills); GRNI **nets to zero** once a receipt is billed.
- Use **perpetual** vs **periodic** precisely — v1 makes the books **perpetual** for stock bills; **periodic**
  (`5150 Purchases`) is retained only for non-stock / service bills.
- Use **opening valuation** for the one-time cost seed (DR Inventory / CR 3100) — never "opening balance" bare
  (that is the stock-module **quantity** seed) and never "goods receipt" (DR Inventory / CR GRNI).

## Sales Orders / Order-to-Cash terms

> Source: [docs/requirements/sales-orders.md](sales-orders.md) (RATIFIED 2026-06-10). The Order-to-Cash depth
> increment — quote → order → reserve → deliver → invoice → [return] on the shipped invoice channel. THE KEY
> SEAM: stock issue + COGS move from invoice-finalise to delivery-time; SO-sourced invoices post revenue only.

- **Quotation (quote)** — a **non-binding priced offer** (`QUOTE-####`) with draft pricing + a **validity
  date**; lifecycle DRAFT → SENT → ACCEPTED → EXPIRED / REJECTED. It **reserves no stock and posts no GL**; on
  **acceptance** it **converts to a sales order** (lines + pricing copied). **Not** a sales order and **not** an
  invoice.
- **Sales order (SO)** — a customer's **committed order** (`SO-####`) with order lines (product, qty ordered,
  unit price, discount); lifecycle DRAFT → CONFIRMED → (PARTIALLY_)FULFILLED → (PARTIALLY_)INVOICED → CLOSED,
  plus CANCELLED. **Confirming reserves** stock. The SO posts **nothing** on its own — the delivery and the
  invoice do. **Not** a quotation (which commits nothing) and **not** an invoice (the bill).
- **Order line** — one product on a sales order: product, **qty ordered**, unit price (price-list default,
  overridable), optional line discount; tracks its own **fulfilled** (delivered) and **invoiced** quantities,
  from which the SO status rollup derives.
- **Reservation (soft allocation)** — confirming an SO **reserves** the ordered qty: a soft hold that records
  the quantity is **spoken for** but **moves no stock (no `stock_movements` row) and posts no GL**. Released by
  delivery (converted to issue) or cancel. **Not** a stock issue and **not** a journal entry.
- **Available-to-promise (ATP) / available** — **available = on_hand − reserved**: the quantity the business
  can still commit to a *new* order. **Not** on-hand (the physical quantity). v1's ATP is the simple
  on_hand − reserved; forward-looking ATP / MRP is deferred.
- **Fulfilment / delivery** — the act of **shipping** an SO's goods, recorded as a **delivery** (`DEL-####`).
  Delivering **issues the stock** (a real deduction) **and posts DR COGS / CR Inventory at the moving average**
  (reusing ADR-0020 — the delivery now **drives** the engine) **and releases** the matching reservation. The
  moment **goods physically leave and the cost is incurred**. **Not** the invoice (the bill) and **not** the
  reservation (the soft hold).
- **Partial delivery** — a delivery that ships **less than** an SO line's open qty; the shipped portion issues
  + COGS, the unshipped balance becomes a **backorder**. One line may be delivered over several deliveries.
- **Backorder** — the **unshipped open balance** of an SO line (ordered − fulfilled); it **stays open** on the
  SO (still reserved, awaiting stock/delivery) until delivered or the SO is cancelled. A quantity state, not a
  document.
- **COGS-at-delivery** — THE KEY SEAM: for an SO-sourced sale, the **delivery** (not the invoice) is the
  trigger that **issues stock and posts COGS** at the moving average. (A **direct** walk-in invoice still issues
  + COGS at finalise — there is no delivery in front of it.)
- **Partial invoicing** — invoicing the **delivered** qty of an SO — possibly **several invoices** across
  several deliveries against one order; each invoice **references the delivery / SO** and posts **revenue only**
  (no stock re-issue). The SO is **INVOICED** when all delivered qty is invoiced.
- **Order-level discount / line discount** — a discount on top of the price-list price: **per-line** (% or
  amount) and **order-level** (% or amount, apportioned across lines pro-rata to net). Both flow to the invoice
  totals; **VAT is computed on the discounted net** (reusing the `InvoiceTotalsCalculator` algorithm).
- **Sales return / RMA** — a **return** (`RET-####`) of delivered goods **against a delivery**: the returned
  qty comes **back into stock** (reversing COGS/inventory at the **original issued cost** — reusing the ADR-0020
  reversal) **and** raises a **credit note** (reusing `ArCreditNoteService` to reverse revenue / AR / VAT).
  Partial returns allowed; capped at the delivered qty. **Against a delivery**, not a free-standing negative
  invoice; **not** a void (a full reversal of a finalised invoice).
- **Credit note** — the AR document that **reduces** what a customer owes (reverses revenue / AR / VAT), raised
  by a sales return. **Reused from AR** (ADR-0014); O2C **raises** one, it does not own the posting. **Not** an
  invoice and **not** a cash refund (the refund tender is a deferred Cash & Bank act).

### Order-to-Cash terminology rulings (pick one, stay consistent)
- Use **quotation** (a non-binding offer), **sales order** (a committed order), **delivery** (goods leave, COGS
  posts), and **invoice** (the customer is billed, revenue posts) precisely — never blur them; each is a
  distinct document with a distinct effect.
- Use **reservation** for the soft allocation and **issue** for the real stock deduction — confirming
  **reserves**, delivering **issues**; never call a reservation a stock movement.
- Use **available-to-promise** (or **available**) = **on_hand − reserved** — never "available" loosely as a
  synonym for on-hand.
- Use **backorder** for the open unshipped balance on the order — never treat it as a separate document.
- Use **COGS-at-delivery** for the seam — for an SO-sourced sale COGS posts **at delivery**, for a direct sale
  **at finalise**; never both, and an **SO-sourced invoice never re-issues stock**.
- Use **return / RMA** (against a delivery, partial-capable, stock-in + credit-note) vs **void** (full reversal
  of a finalised invoice) precisely — they are different acts.
- A **revaluation** changes value with no sale (the adjustment path); a **sale** reduces both quantity and
  value (to COGS) — never call a sale a revaluation.
