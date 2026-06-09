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
