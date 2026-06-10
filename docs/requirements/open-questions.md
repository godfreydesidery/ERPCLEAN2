# Open Questions Log

Each entry: why it matters · who decides · does it block build.

## IAM

- **OQ-IAM-01** — ✅ **RESOLVED 2026-06-05.** Branch assignment is **decoupled** from roles — a user
  may be assigned to a branch without a role; they can only *act* where a role also covers them.
  See FR-IAM-24; BR-6 relaxed.

- **OQ-IAM-02** — ✅ **RESOLVED 2026-06-05.** When no branch assignment remains, **login succeeds**
  into a read-only "no branch assigned — contact admin" state. See FR-IAM-19.

- **OQ-IAM-03** — Does a user need an explicit **default company** field, given they can span
  companies (D5)? Lean: yes — default company + default branch (branch implies its company, so a
  single `default_branch` may suffice if the branch carries its company). Architect to resolve the
  minimal-field form.
  - *Decider:* architect (data-model). *Blocks build:* no (modelling detail).

- **OQ-IAM-04** — Time zone of record: store IAM timestamps in UTC and display per branch/company
  tz? Assumed yes (NFR §7). *Decider:* architect. *Blocks build:* no.

## Parties

- **OQ-PARTY-01** — ✅ **RESOLVED (owner):** each party kind has its **own** numbering sequence per
  company (e.g. customers `CUST-####`, suppliers `SUPP-####`, agents `AGENT-####`) — not a shared
  sequence. Consistent with the separate-records model (Decision D1). Reflected in FR-PARTY-19 and
  BR-PARTY-08.

- **OQ-PARTY-02** — Is there a **credit-limit and/or credit-terms approval workflow** for credit/
  account customers (e.g. a limit that requires manager approval, or terms like net-30)? Out of v1
  party scope; belongs to Finance/Sales. *Decider:* owner. *Blocks build:* no for parties; yes for
  credit sales later.

- **OQ-PARTY-03** — Do sales agents have **commission tiers / rates** captured now, or is commission
  setup deferred to the Sales module? Currently deferred (parties.md §10). Confirm whether even a
  flat commission rate should live on the agent record in v1. *Decider:* owner. *Blocks build:* no.

- **OQ-PARTY-04** — Should an **Other/Misc party be promotable** to a typed party later (e.g. convert
  an Other into a Customer, preserving history), or is it always a separate record requiring re-keying
  when its kind is known? Affects whether "Other" is a transient holding type or permanent.
  *Decider:* owner. *Blocks build:* no (can ship as separate-record now, add promotion later).

- **OQ-PARTY-05** — ✅ **RESOLVED (owner):** the registration number is **recommended, not
  mandatory**, and the field is **generalised to a "business registration number"** — NOT
  BRELA-specific. It captures whatever registrar applies (BRELA or other); the system does not
  hard-code or require BRELA. Reflected in FR-PARTY-14 and BR-PARTY-04/05.

- **OQ-PARTY-06** — Should the system support a single reusable **default walk-in / anonymous
  customer** per branch (or per company) for fast counter sales, and if so where is it seeded?
  Implied by the walk-in sub-kind but not specified. *Decider:* owner. *Blocks build:* no (Sales
  detail), but informs the cash-sale flow.

- **OQ-PARTY-07** — When a deferred party type (e.g. distributor, import agent) is later prioritised,
  is it a **new typed party** or a **sub-kind** of an existing one (customer/supplier)? Captured now
  so the decision is conscious later, not assumed. *Decider:* owner + architect at that round.
  *Blocks build:* no (future scope).

## Multicurrency

- **OQ-CUR-01** — Is there **one base currency per company** only, or do we also need a separate
  **group / reporting currency at the organisation level** (consolidating several companies' books
  into one reporting currency)? v1 assumes company-base only. Matters because a group reporting
  currency adds a second conversion layer to every consolidated report. *Decider:* owner. *Blocks
  build:* no (company-base ships now; org-level reporting is a later round). See multicurrency.md §1.
- **OQ-CUR-02** — Will we ever need to **settle/pay a document in a currency different from the one it
  was billed in** (cross-currency settlement), and if so **when**? This introduces **realised FX
  gain/loss** and is the single biggest deferred item. Matters because it touches Finance posting and
  the payment flow. *Decider:* owner. *Blocks build:* no for v1 (deferred, FR-CUR-11); yes for any
  future multi-currency settlement round.
- **OQ-CUR-03** — **Rounding policy per currency**: which **rounding mode** (half-up, half-even/
  banker's, half-down) applies, and confirm the **decimal places for TZS** (0 in practice vs a nominal
  2). Matters because backend and frontend must round identically (multicurrency.md §6) and the mode
  affects every total. *Decider:* owner (with finance input). *Blocks build:* low — a default
  (half-up, TZS = 0 dp) can ship and be revisited; confirm before Sales/Finance go live.
- **OQ-CUR-04** — **Which currencies to seed initially** in the currency master? Proposed: **TZS**
  (base), **USD**, **EUR**; candidates **KES**, **UGX**, **RWF**, **GBP**, **ZAR**. Matters only for
  the seed list, not the model. *Decider:* owner. *Blocks build:* no (seed list is easily amended).
- **OQ-CUR-05** — Do **parties get a default/preferred transacting currency in v1**, or is that field
  added later? It is an *optional* attribute (multicurrency.md §5); capturing it in v1 lets documents
  default to a party's currency, but adds a field to the party master now. *Decider:* owner. *Blocks
  build:* no (party master can ship without it; add when foreign-currency documents are switched on).

## Products

- **OQ-PROD-01** — **Product numbering scheme**: a single per-company sequence (`PROD-####`), or a
  category/type-prefixed sequence (goods vs services, or per category)? Affects the code scheme and
  uniqueness scope (BR-PROD-08). *Decider:* owner. *Blocks build:* no (generated either way) — close
  before Sales/Purchases reference product codes on documents.
- **OQ-PROD-02** — **Barcode uniqueness scope**: unique within the **company** (assumed, BR-PROD-07)
  or ever org-wide? Company is the consistent tenancy choice; org-wide only matters if the same
  physical barcode must resolve identically across companies. *Decider:* owner. *Blocks build:* no
  (company-scope ships; widen later if needed).
- **OQ-PROD-03** — **Is a price required at product create, or only at sale-time?** Assumed sale-time
  (BR-PROD-11): a product may be created un-priced and priced later; Sales blocks selling an un-priced
  product. Confirm vs requiring at least one price to save a sellable product. *Decider:* owner.
  *Blocks build:* no.
- **OQ-PROD-04** — **Product categories / groups in v1 or later?** Grouping products (Beverages,
  Spare Parts, Kitchen) aids browsing/reporting and often drives default tax/pricing. Not in current
  v1 scope; pull in now or defer. *Decider:* owner. *Blocks build:* no (additive later).
- **OQ-PROD-05** — ✅ **RESOLVED 2026-06-07 = yes (with Sales ratification).** The product master gains
  a **VAT-status field** (standard-rated / zero-rated / exempt) — a clean **additive** change to the
  product master, **designed with Sales** in ADR-0008. Sales computes VAT per line from this status
  (sales.md FR-SALES-10). A tax-code attribute beyond the three-way status is deferred until a richer
  tax-code scheme is needed. *Note:* products.md §10 still lists this as pending — update when ADR-0008
  lands the field.
- **OQ-PROD-06** — **Composed-product pricing**: confirmed v1 = a composed product is **independently
  priced** as a sellable line (components recorded for display + future stock-deduction, not to derive
  price). Re-confirm no v1 requirement to compute a composed product's price from its components.
  *Decider:* owner. *Blocks build:* no (assumption recorded in products.md §9).
- **OQ-PROD-07** — **Unit precision / fractional base units**: can a base unit be sold/stocked in
  fractions (0.5 kg, 1.25 litre), and to how many decimal places? Affects quantity precision across
  Products/Stock/Sales. *Decider:* owner. *Blocks build:* low — a default (allow fractional, fixed dp)
  can ship; confirm before Stock/Sales quantity math.

## Sales

> **FULLY RATIFIED 2026-06-07.** All eight headline decision areas **and the two ADR-blocking detail
> rulings — OQ-SALES-03b (VAT entry = tax-EXCLUSIVE) and OQ-SALES-12 (numbering = single per-company
> `INV-####`)** — are **RESOLVED** (owner rulings below). sales.md is fully ratified. **No
> ADR-0008-blocking open question remains** for Sales. The remaining Sales OQs are **non-blocking**
> detail (confirm before go-live, not before ADR). **solutions-architect may start ADR-0008 now.**

### Resolved (owner, 2026-06-07)

- **OQ-SALES-01** — ✅ **RESOLVED: Invoice only in v1.** POS till and Sales Order **deferred** (both
  reuse the Invoice spine). Reflected in sales.md §2/§3, FR-SALES-01.
- **OQ-SALES-02** — ✅ **RESOLVED: sales agent mandatory on every sale**; auto-defaults to the logged-in
  user when they are an INTERNAL agent (overridable); **commission recorded/captured but NOT computed**
  in v1 (rates deferred → OQ-PARTY-03). Reflected in FR-SALES-14/15/16, BR-SALES-06.
- **OQ-SALES-03** — ✅ **RESOLVED: v1 computes VAT per line; TRA EFD/VFD fiscalisation deferred** as a
  separable later integration. v1 prints a proper VAT invoice. Reflected in FR-SALES-10/11/13, §10.
- **OQ-SALES-04** — ✅ **RESOLVED:** price from a **company default price list, optionally overridden per
  customer**; **manual line-price override allowed, permission-gated and audited**; **line + document
  discounts, applied before VAT.** Reflected in FR-SALES-07/08/09, BR-SALES-09.
- **OQ-SALES-05** — ✅ **RESOLVED:** tenders = **cash + mobile money**, **split allowed**, **paid in full
  at finalise**; card deferred; no partial-payment / outstanding-balance state in v1. Reflected in
  FR-SALES-17/18, BR-SALES-07.
- **OQ-SALES-06** — ✅ **RESOLVED: credit sales / receivables / credit-limit enforcement DEFERRED.** v1
  is paid-at-sale only; lands with a Finance-aware round (→ OQ-PARTY-02). Reflected in FR-SALES-20.
- **OQ-SALES-07** — ✅ **RESOLVED: permissioned VOID only in v1**; returns / credit notes / refunds
  deferred. Reflected in FR-SALES-03/22, BR-SALES-08. *(Void-window value is an architect detail.)*
- **OQ-SALES-08** — ✅ **N/A in v1** (POS deferred per OQ-SALES-01). Retained for the future POS round
  (tills, sessions/shifts, cash-drawer reconciliation X/Z, offline).
- **OQ-SALES-09** — ✅ **RESOLVED & ACCEPTED RISK (owner-accepted):** v1 records sold quantities, deducts
  **no** inventory, is stock-agnostic (no over-sell warning); deduction designed to fire via the
  transactional outbox when Stock lands. Made prominent in sales.md §10. Reflected in FR-SALES-21,
  BR-SALES-11.
- **OQ-SALES-12** — ✅ **RESOLVED 2026-06-07 (owner): single per-company series `INV-####`** via the
  generic `code_sequence` (entity_kind `SALES_INVOICE`), mirroring Products/Parties. Per-branch /
  per-channel numbering can be added later **additively** via the `entity_kind` discriminator. Reflected
  in sales.md FR-SALES-23, BR-SALES-12, §2, §7. **(Was the last lightly-blocking ADR-0008 question.)**
- **OQ-SALES-03b** — ✅ **RESOLVED 2026-06-07 (owner): tax-EXCLUSIVE.** Line prices are **net**; VAT is
  **added on top** to reach the gross total (gross = net + VAT). A tax-inclusive entry mode is **not**
  built in v1 (revisit for the deferred POS channel, additively). Reflected in sales.md FR-SALES-09/11/12,
  the VAT vocabulary, §2 scope, §7 totals flow. **(Was the last ADR-0008-blocking question.)**

### Still open — NON-blocking detail (recommended defaults stand; confirm before go-live, NOT before ADR-0008)

> **None of these block ADR-0008.** solutions-architect may proceed now; each refines a value inside an
> already-ratified feature and lands additively or is confirmed during build / before go-live.

- **OQ-SALES-10** — **Override / approval threshold value.** The permission-gated, audited override is
  ratified; the **threshold above which a supervisor must approve** (e.g. discount > X% or price below
  cost) and its value are open. *Recommended default:* single configurable percent threshold, owner-set;
  ship the permissioned override regardless. *Decider:* owner. *Blocks ADR-0008:* **NO** (additive) —
  confirm value before go-live.
- **OQ-SALES-11** — **Number-assignment point.** Draft state confirmed; confirm the document number is
  **assigned at finalise** (so drafts don't consume numbers). *Recommended default:* number at finalise
  (the architect models to this default). *Decider:* owner. *Blocks ADR-0008:* **NO** — default stands.
- **OQ-CUR-03** — *(carried)* confirm **rounding mode + TZS decimals** before Sales computes totals;
  backend and frontend must round identically (NFR-SALES-02). *Recommended default:* half-up, TZS = 0 dp.
  *Decider:* owner (finance input). *Blocks ADR-0008:* **NO** for the model; **confirm before go-live**.

## Stock

> **FULLY RATIFIED 2026-06-07.** Built with Purchases this round to close the "a sale must update
> stock" gap. **Four headline decisions RATIFIED** (owner): (1) build Stock + Purchases together,
> stock-in = real goods-receipt; (2) overselling ALLOWED (on-hand may go negative, flagged, not
> blocked); (3) composed-product sale EXPLODES the recipe to deduct components; (4) QUANTITIES ONLY in
> v1 — no valuation/COGS. **Every second-order OQ (OQ-STOCK-01..10) is now RESOLVED at the recommended
> default, and OQ-STOCK-09 RESOLVED = YES (build the outbox this round, own platform ADR).** stock.md is
> **Ratified**. **No ADR-0009-blocking question remains.** solutions-architect may start the
> platform-outbox ADR + ADR-0009 now.

- **OQ-STOCK-01** — ✅ **RESOLVED (owner): maintained on-hand row + append-only movement ledger** (fast
  read + full history; on-hand == Σ movements). Pure-derived not chosen. Reflected in FR-STOCK-03,
  stock.md §3.1.
- **OQ-STOCK-02** — ✅ **RESOLVED (owner): negative on-hand allowed EVERYWHERE — no per-product block** in
  v1; negatives flagged. A per-product block is not precluded but not built. Reflected in FR-STOCK-04,
  BR-STOCK-03.
- **OQ-STOCK-03** — ✅ **RESOLVED (owner): non-stockable component on explosion = SKIP + RECORD** (no
  on-hand to deduct); deduct only stockable components. Reflected in FR-STOCK-08, BR-STOCK-04.
- **OQ-STOCK-04** — ✅ **RESOLVED (owner): small fixed reason list, reason MANDATORY, NO approval
  threshold** in v1 (permission `STOCK.ADJUST` alone gates). Reflected in FR-STOCK-09, BR-STOCK-05.
- **OQ-STOCK-05** — ✅ **RESOLVED (owner): MANUAL opening balance** in v1 (one `OPENING_BALANCE` per
  product/branch); bulk import is a later additive convenience. Reflected in FR-STOCK-10.
- **OQ-STOCK-06** — ✅ **RESOLVED (owner): OPTIONAL reorder level, INDICATOR-ONLY** (no auto-reorder, no
  purchase suggestion). Reflected in FR-STOCK-11.
- **OQ-STOCK-07** — ✅ **RESOLVED (owner): manual ADJUSTMENT only** in v1; a formal stock-count /
  stocktake workflow is deferred. Reflected in stock.md §2 deferred list.
- **OQ-STOCK-08** — ✅ **RESOLVED (owner): branch-to-branch transfers DEFERRED** (the TRANSFER_OUT /
  TRANSFER_IN vocabulary is reserved so it is additive); v1 moves stock in/out/± only. Reflected in
  stock.md §2/§3.2.
- **OQ-STOCK-09** — ✅ **RESOLVED (owner) = YES: build the transactional outbox THIS ROUND under its own
  PLATFORM ADR.** `domain_event` + poller/dispatcher; **at-least-once delivery + consumer-side
  idempotency (dedupe on event id)**; **Sales wired to actually emit** `SALE.FINALISED` / `SALE.VOIDED`
  (closing the ADR-0008 D-9 seam); **Purchases' Goods Receipt emits** `STOCK.RECEIVED`; Stock is the
  consumer. Reflected in stock.md §3.4, FR-STOCK-13. **(Was the one hard prerequisite — now closed.)**
- **OQ-STOCK-10** — ✅ **RESOLVED (owner): reverse only what was issued; if nothing matches, record an
  ANOMALY** for review rather than posting a phantom negative. Confirmed with the platform-outbox ADR.
  Reflected in stock.md §7.5.

## Purchases

> **FULLY RATIFIED 2026-06-07.** Built with Stock this round. The round-level decisions are RATIFIED
> (build together; stock-in = real goods-receipt). **Every Purchases OQ (OQ-PURCH-01..08) is now
> RESOLVED by the owner**, and **OQ-PURCH-01 CHANGED from the recommended default to a TWO-DOCUMENT
> flow: Purchase Order + separate Goods Receipt (with partial receipts).** purchases.md is **Ratified**.
> **No ADR-0010-blocking question remains.** solutions-architect may start ADR-0010 now.

- **OQ-PURCH-01** — ✅ **RESOLVED (owner) = PURCHASE ORDER + SEPARATE GOODS RECEIPT (two documents, two
  steps)** — **NOT** the single-step GRN that was the recommended default. A **PO** is raised first
  (supplier + ordered lines: product × ordered-qty × unit × unit-cost; lifecycle DRAFT → ORDERED →
  partially/fully RECEIVED → CLOSED / VOID; numbered `PO-####`). A separate **Goods Receipt (GR/GRN)** is
  recorded **against the PO** (some/all of the ordered qty) and **pushes stock IN** (emits
  `STOCK.RECEIVED`; numbered `GRN-####` at receive; lifecycle DRAFT → RECEIVED → VOID). **Partial
  receipts** (multiple GRs per PO) with **received-vs-ordered / outstanding-qty** tracking are in scope.
  **Deferred:** multi-step PO approval; the supplier-invoice leg of a 3-way match (with AP). Reflected
  throughout purchases.md (FR-PURCH-01a/01b, 02a/02b, 07; BR-PURCH-10; §3, §7, §10).
  *(Was the shape-defining question — now closed as PO + separate receipt.)*
- **OQ-PURCH-02** — ✅ **RESOLVED (owner): two lifecycles.** **PO: DRAFT → ORDERED → (partially/fully)
  RECEIVED → CLOSED / VOID** (FR-PURCH-02a). **Goods Receipt: DRAFT → RECEIVED → VOID** (FR-PURCH-02b).
- **OQ-PURCH-03** — ✅ **RESOLVED (owner): PO `PO-####` and Goods Receipt `GRN-####`, both per-company**
  via `code_sequence` (PO number at order-placement; GRN number at receive). Reflected in FR-PURCH-12,
  BR-PURCH-07.
- **OQ-PURCH-04** — ✅ **RESOLVED (owner): NO purchase VAT in v1** (input-VAT recovery is Finance/AP,
  deferred); **cost REQUIRED on a goods line** (zero only for a free/sample line with a reason).
  Reflected in FR-PURCH-05, FR-PURCH-13, BR-PURCH-08.
- **OQ-PURCH-05** — ✅ **RESOLVED (owner): AP / supplier invoices / payments DEFERRED.** v1 records cost
  on the PO/GR, creates **no payable**, takes **no payment**; the supplier-invoice leg of a 3-way match
  lands with a Finance-aware round. Reflected in BR-PURCH-08, §2 deferred.
- **OQ-PURCH-06** — ✅ **RESOLVED (owner): returns to supplier / debit notes DEFERRED.** v1 correction is
  a permissioned **void** of the Goods Receipt (reversing the stock-in, restoring the PO outstanding);
  partial returns with a supplier credit/debit note are a later round. Reflected in FR-PURCH-09.
- **OQ-PURCH-07** — ✅ **RESOLVED (owner): landed cost DEFERRED.** v1 records the supplier's line cost
  only; apportioning freight/duty/insurance ties to the deferred valuation work (stock.md §10).
- **OQ-PURCH-08** — ✅ **RESOLVED (owner): GOODS-ONLY.** v1 PO/GR are for **goods that move stock**;
  service / expense purchases are deferred to AP/expenses.

## Routes

> **FULLY RATIFIED 2026-06-08.** The owner closed **all eight scoping forks** (route master shape;
> route↔customer cardinality; route↔agent cardinality + EXTERNAL-only; route↔branch filtering; free-text
> geography vs geo-hierarchy binding; route captured on the v1 invoice; permission set; numbering).
> routes.md is **Ratified**. **No ADR-0012-blocking question remains.** solutions-architect may start
> **ADR-0012** now (routes data model, migration `V9`) + a **small additive Sales-invoice change** (a
> nullable `route_id` snapshot, mirroring how `products.vat_status` was added for Sales). The remaining
> OQ-ROUTE items are **non-blocking** detail (recommended defaults stand).

### Resolved (owner, 2026-06-08)

- **ROUTE fork 1 — Route master shape** — ✅ **RESOLVED: per-company master**, code `ROUTE-####` via
  `code_sequence`, name + free-text location identifier/description, MasterStatus
  (ACTIVE/INACTIVE/ARCHIVED soft-delete), audit. Mirrors the Customer/Agent/Product master. Reflected in
  routes.md FR-ROUTE-01/02/03/16, BR-ROUTE-06.
- **ROUTE fork 2 — Route ↔ Customer cardinality** — ✅ **RESOLVED: MANY-TO-MANY**; a customer may belong
  to several routes. **All customers routable** (cash/walk-in + credit/account). Reflected in FR-ROUTE-04/05/06.
- **ROUTE fork 3 — Route ↔ Agent cardinality + agent kind** — ✅ **RESOLVED: MANY-TO-MANY, EXTERNAL agents
  ONLY** (INTERNAL agents cannot be route-assigned); optional **advisory primary** agent per route.
  Reflected in FR-ROUTE-07/08/09, BR-ROUTE-02/04.
- **ROUTE fork 4 — Route ↔ Branch** — ✅ **RESOLVED: company-owned, branch-filtered, can span branches**
  (mirrors `customer_branch` / `agent_branch`). Reflected in FR-ROUTE-10/11/12, BR-ROUTE-01/03.
- **ROUTE fork 5 — Geography** — ✅ **RESOLVED: free-text area label / location identifier**, NOT
  structurally bound to region/district or any geo-hierarchy (a `route_geography` binding is a future
  additive option). The customer's region/district are **not** the route. Reflected in FR-ROUTE-03/06,
  BR-ROUTE-08. *(See OQ-ROUTE-03 below — the geo-hierarchy round is deferred, conscious.)*
- **ROUTE fork 6 — Route on the invoice** — ✅ **RESOLVED: capture it in v1.** The sales invoice gains a
  **nullable** route, **defaulted from the selling agent's primary route, editable, OPTIONAL** (never
  blocks a sale; cannot be derived from the customer because route↔customer is N:M). A **small additive
  cross-module change to Sales** (like `products.vat_status` was), designed in ADR-0012 + a tiny Sales
  addition. **Captured-not-validated** = accepted risk. Reflected in FR-ROUTE-13/14/15, BR-ROUTE-05/09,
  routes.md §9/§10.
- **ROUTE fork 7 — Permissions** — ✅ **RESOLVED: `ROUTE.VIEW` / `ROUTE.MANAGE` / `ROUTE.ASSIGN`**
  (mirroring `PRODUCT.*` / `PARTY.BRANCH.ASSIGN`); per-company scope; `assertCanActIn` on every read path;
  audit on every mutation. Reflected in FR-ROUTE-17, NFR-ROUTE-01/03.
- **ROUTE fork 8 — Numbering** — ✅ **RESOLVED: `ROUTE-####` per company** via the generic `code_sequence`
  (entity_kind `ROUTE`); no new per-module counter. Reflected in FR-ROUTE-16, BR-ROUTE-06.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0012)

- **OQ-ROUTE-01** — **Primary-route default when an external agent is primary on MORE THAN ONE route.**
  Which route then defaults onto the invoice? *Recommended default:* if exactly one primary route is
  associated with the active branch, default to it; if zero or several qualify, default to **blank** (the
  operator selects). *Decider:* owner. *Blocks ADR-0012:* **NO** — blank-on-ambiguity default stands; a
  one-primary-route-per-agent constraint is an additive option later.
- **OQ-ROUTE-02** — **Route name uniqueness within a company.** Code is unique per company (BR-ROUTE-06);
  is the **name** also unique per company? *Recommended default:* name **not** unique (code disambiguates,
  matching Products/Parties). *Decider:* owner. *Blocks ADR-0012:* **NO** — additive constraint if wanted.
- **OQ-ROUTE-03** — **Geo-hierarchy / region-district binding (`route_geography`).** Confirmed **deferred**
  (free-text only in v1, BR-ROUTE-08); recorded so the future structured-geography round is conscious.
  *Decider:* owner + architect at that round. *Blocks ADR-0012:* **NO** (deferred; not precluded —
  NFR-ROUTE-04).

## General Ledger (GL)

> **FULLY RATIFIED 2026-06-08.** GL **Increment 1** of the full-ERP roadmap (docs/ROADMAP.md T1.1 / §5).
> The owner closed **all six scoping forks** (chart of accounts; sales auto-posting; fiscal calendar;
> corrections; manual journals; multi-currency). gl.md is **Ratified**. **No ADR-0013-blocking question
> remains.** solutions-architect may start **ADR-0013** now (GL data model — chart_of_accounts,
> journal_batches/entries/lines, fiscal_periods, gl_configs; migration **V10**; the `SalesPostingHandler`
> + `SaleVoidingHandler`; the `ScopeGuard` "account" case; TZ CoA seed). The remaining OQ-GL items are
> **non-blocking** detail (recommended defaults stand).

### Resolved (owner, 2026-06-08) — the six scoping forks

- **GL fork 1 — Chart of accounts** — ✅ **RESOLVED:** numeric ranges (1000s Assets, 2000s Liabilities,
  3000s Equity, 4000s Income, 5000s Expenses); **system-seeded** standard **Tanzanian small-business CoA**
  that is **editable** (add/edit/deactivate; **cannot delete a posted-to account**); each account has an
  **account TYPE** (ASSET/LIABILITY/EQUITY/INCOME/EXPENSE) driving **statement placement** (P&L vs Balance
  Sheet) and **normal balance** (debit/credit); **per company**; account **code unique per company**
  (user/seed-defined codes, uniqueness enforced — `code_sequence` not required for accounts). Reflected in
  gl.md FR-GL-01..05, BR-GL-04/05/07/12, §3.1.
- **GL fork 2 — Sales auto-posting** — ✅ **RESOLVED:** finalising a sale **auto-posts a balanced journal**
  via the outbox (a `SalesPostingHandler` consuming **`SALE.FINALISED`**, mirroring `SaleIssueStockHandler`)
  using a configurable **account map (`gl_configs`)** — **DR Accounts Receivable** (credit) **or Cash**
  (cash sale), **CR Sales Revenue**, **CR VAT Payable**; **`SALE.VOIDED` posts the reversing entry**;
  **fixed mapping in v1** (one revenue account, one VAT-payable account — **NOT** per-product-category, a
  later option); **idempotent** (`processed_events`). Reflected in FR-GL-10/11/12/18, BR-GL-09/10/11.
- **GL fork 3 — Fiscal calendar** — ✅ **RESOLVED:** **12 monthly periods**; **fiscal-year start month
  configurable per company** (e.g. Jan or Jul); periods **OPEN/CLOSED**; **posting into a CLOSED period is
  rejected**; period-12 close yields the year-end state for opening balances (**full year-end-close
  automation deferred** — OQ-GL-03). Reflected in FR-GL-08/14/15, BR-GL-03, §3.3.
- **GL fork 4 — Corrections** — ✅ **RESOLVED: append-only immutable ledger** — posted journal entries are
  **never edited or deleted**; corrections are **reversing entries** then a correct re-post
  (PROJECT-CONVENTIONS §3.6); full audit trail. Reflected in FR-GL-12, BR-GL-02/11, NFR-GL-04/06.
- **GL fork 5 — Manual journals** — ✅ **RESOLVED: INCLUDED in v1** — accountants post manual journals
  (accruals, adjustments, **opening balances**) with DR/CR lines that **must balance (Σ debits == Σ
  credits) before posting**. Reflected in FR-GL-06/07/09/13, BR-GL-01/08.
- **GL fork 6 — Multi-currency** — ✅ **RESOLVED: base-currency-only in v1** — GL posts in the **company
  base currency** only; foreign-currency transactions **convert at entry**; **FX revaluation / realised
  gain-loss DEFERRED** (ROADMAP X.6 / ADR-0005 D-8) — an accepted scope boundary. Reflected in BR-GL-06,
  §10.5, NFR-GL-09.

> **Permissions & double-entry invariant (ratified with the forks):** `GL.VIEW` / `GL.MANAGE` (CoA +
> config) / `GL.POST` (manual journals) / `GL.PERIOD.CLOSE`; per-company scope; `assertCanActIn` on every
> read path; audit on every post and close; `ScopeGuard` gains a new **"account"** target type (note for
> ADR-0013). The **double-entry invariant** (≥ 2 lines, Σ debits == Σ credits, one account + one side per
> line, date in an OPEN period, balanced-or-rejected) is fixed. Reflected in FR-GL-19, BR-GL-01/08, §4/§5.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0013)

- **OQ-GL-01** — **Closed-period policy for an auto-post.** When a `SALE.FINALISED` would post into a
  **closed** period (a late/replayed event), does the handler **fail-and-retry until reopened** or **post
  to the next open period**? *Recommended default:* **fail-and-retry** (BR-GL-03 holds for automatic
  posting too — no sale posts to a closed period); finance reopens / moves the period. *Decider:* owner
  (finance). *Blocks ADR-0013:* **NO** — default stands; the alternative is an additive configurable policy.
- **OQ-GL-02** — **Cash-vs-credit signal for AR vs Cash.** v1 sales are paid-at-sale (cash), so the live
  auto-post is **DR Cash**; the **AR** posting path goes live with credit sales + the AR increment.
  *Recommended default:* DR Cash for v1; the AR mapping role is seeded from day one so credit-sale AR
  posting is additive. *Decider:* owner (with the AR increment). *Blocks ADR-0013:* **NO**.
- **OQ-GL-03** — **Year-end-close automation depth.** v1 = manual opening balances + period-12 close yields
  the year-end state. *Recommended default:* manual opening-balance journal in v1; automated
  P&L→retained-earnings closing entry + opening-balance carry-forward is a later slice (gl.md §10.6).
  *Decider:* owner. *Blocks ADR-0013:* **NO** — deferred, not precluded.
- **OQ-GL-04** — **Per-category revenue / VAT mapping.** v1 = one Sales Revenue + one VAT Payable account
  (the ratified fixed mapping). *Recommended default:* fixed single mapping; per-product-category split is
  an additive `gl_configs` option later (gl.md §10.7). *Decider:* owner. *Blocks ADR-0013:* **NO** —
  additive.
- **OQ-CUR-03** — *(carried)* confirm **rounding mode + TZS decimals** before GL computes/balances totals;
  the balance check (Σ debits == Σ credits) and every posted amount must round identically backend/frontend
  (NFR-GL-02). *Recommended default:* half-up, TZS = 0 dp. *Decider:* owner (finance input). *Blocks
  ADR-0013:* **NO** for the model; **confirm before go-live**.

## Accounts Receivable (AR)

> **FULLY RATIFIED 2026-06-09.** AR is half of **Increment 2** of the full-ERP roadmap (docs/ROADMAP.md
> T1.2 / §5), built in parallel with AP (T1.3). The owner closed **all five AR scoping forks**
> (open-item creation; ageing buckets; receipts & allocation; credit limit; v1 feature set).
> accounts-receivable.md is **Ratified**. **No ADR-0014-blocking question remains.** solutions-architect
> may start **ADR-0014** now (AR data model — the customer sub-ledger: open items, receipts, allocations,
> balances/ageing views; migration **V11**; the AR open-item handler consuming `SALE.FINALISED` for credit
> sales; the receipt → GL cash-leg posting; the **sub-ledger⇄GL-control reconciliation** design; the
> GL-posting mechanism choice (synchronous `GLPostingService` vs outbox); the **Sales credit-limit additive
> touch**). The remaining OQ-AR items are **non-blocking** detail (recommended defaults stand).

### Resolved (owner, 2026-06-09) — the five AR scoping forks

- **AR fork 1 — Open-item creation** — ✅ **RESOLVED:** finalising a **CREDIT-account** sale auto-creates
  an AR **open item** (consumes `SALE.FINALISED` for credit sales); **cash sales create NO AR** (settled at
  the till). **AR does NOT re-post to GL** — the credit sale's `SalesPostingHandler` already debited the AR
  control (the no-double-post rule). Reflected in accounts-receivable.md FR-AR-01/02/05, BR-AR-01/02, §3.2.
- **AR fork 2 — Ageing buckets** — ✅ **RESOLVED:** **Current, 1–30, 31–60, 61–90, 90+** days, by **due
  date** (due date from customer terms, else net-on-receipt / 0 days — OQ-AR-01). Reflected in FR-AR-03/08,
  glossary.
- **AR fork 3 — Receipts / allocation** — ✅ **RESOLVED:** a receipt **auto-allocates oldest-open-first by
  default**; operator may **manually override** (re-pick invoices); **on-account (unallocated) receipts
  allowed** (a credit balance applied later); **over-allocation rejected**. A receipt posts **DR Cash/Bank /
  CR AR control** to GL (re-allocation posts nothing). Reflected in FR-AR-06/07/09/10/11/16, BR-AR-03/04/05/12.
- **AR fork 4 — Credit limit** — ✅ **RESOLVED: warn + allow with a permission** (`SALES.CREDIT.OVERRIDE`),
  **audited**, when a credit sale would push (current AR balance + new sale) over the customer's
  `credit_limit_amount`; the check is an **additive insertion into the Sales finalise path** (like
  `products.vat_status` was). Reflected in FR-AR-19, BR-AR-10, §3.5.
- **AR fork 5 — v1 feature set** — ✅ **RESOLVED: IN v1 =** customer **statements** (open items + ageing,
  view/print), **write-offs** (bad-debt, posts DR bad-debt expense / CR AR control) **+ credit notes**
  (reduce a receivable), and **opening balances** (pre-existing receivables at go-live). Reflected in
  FR-AR-12/13/14/15, BR-AR-06/09.

> **Permissions & reconciliation (ratified with the forks):** `AR.VIEW` / `AR.INVOICE.VIEW` /
> `AR.RECEIPT.RECORD` / `AR.RECEIPT.ALLOCATE` / `AR.WRITEOFF` / `AR.STATEMENT.VIEW` / `AR.OPENING.SET` (+
> `SALES.CREDIT.OVERRIDE` for the credit-limit override); per-company scope; `assertCanActIn` on every read
> path; audit on every mutation; `RCT-####` via `code_sequence`; `ScopeGuard` gains new AR target types
> (note for ADR-0014). The **reconciliation invariant** (AR sub-ledger total == GL `1200 Accounts
> Receivable` control balance at all times) is fixed and is the chief acceptance bar. Reflected in
> FR-AR-18/20/21, BR-AR-02/07, NFR-AR-01.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0014)

- **OQ-AR-01** — **Customer payment terms / due-date default.** Ageing is by **due date**, from customer
  terms if defined, **else net-on-receipt (0 days)**. *Recommended default:* a simple per-customer net-days
  field if present, else 0 days; a rich terms master (net-30, early-payment discounts, instalments) is a
  later additive slice. *Decider:* owner (finance). *Blocks ADR-0014:* **NO** — the due-date field + the
  five ageing buckets are fixed.
- **OQ-AR-02** — **Statement format.** v1 ships **one** statement layout (open items + ageing + recent
  activity, view/print). *Recommended default:* open-item statement with the five ageing buckets;
  period-statement / branded / emailed variants are later additive options. *Decider:* owner. *Blocks
  ADR-0014:* **NO** — presentation detail.
- **OQ-AR-03** — **Write-off approval.** v1 write-off is a **permissioned, audited single act**
  (`AR.WRITEOFF`). *Recommended default:* permission + audit, no approval workflow and no doubtful-debt
  allowance/provision account in v1; both are later additive slices. *Decider:* owner (finance). *Blocks
  ADR-0014:* **NO** — additive.
- **OQ-AR-04** — **Credit note: standalone vs ride the void path.** A voided sale rides `SALE.VOIDED` (GL
  already reverses — AR closes the open item with no second GL post); a non-void correction may be a
  **standalone AR credit note** (which posts the reduction). *Recommended default:* ride `SALE.VOIDED` for
  voided sales; allow a standalone AR credit note for non-void corrections; both reconcile (BR-AR-02).
  *Decider:* owner. *Blocks ADR-0014:* **NO** — both paths reconcile; the standalone path is additive if
  deferred.
- **OQ-CUR-03** — *(carried)* confirm **rounding mode + TZS decimals** — allocations and the GL legs must
  round identically to the AR balance and the control account (NFR-AR-02). *Recommended default:* half-up,
  TZS = 0 dp. *Decider:* owner (finance input). *Blocks ADR-0014:* **NO** for the model; **confirm before
  go-live**.

## Accounts Payable (AP)

> **FULLY RATIFIED 2026-06-09.** AP is half of **Increment 2** of the full-ERP roadmap (docs/ROADMAP.md
> T1.3 / §5), built in parallel with AR (T1.2). The owner closed **all AP scoping forks**
> (bill-entry-driven AP; 3-way match within tolerance; no GRN accrual; payment runs + single payment; debit
> notes; opening balances). accounts-payable.md is **Ratified**. **No ADR-0015-blocking question remains.**
> solutions-architect may start **ADR-0015** now (AP data model — the supplier sub-ledger: supplier bills +
> lines, the 3-way bill↔PO↔GR match, payables, payments + payment runs, debit notes; migration **V12**; the
> bill match → GL posting; the **sub-ledger⇄GL-control reconciliation** design; the GL-posting mechanism
> choice; and the **bill debit account choice** — inventory value vs a purchases / GRNI-clearing account).
> The remaining OQ-AP items are **non-blocking** detail (recommended defaults stand).

### Resolved (owner, 2026-06-09) — the AP scoping forks

- **AP fork 1 — Bill-entry-driven AP** — ✅ **RESOLVED:** the operator **enters a supplier bill**; it is
  **3-way matched** against the PO and the Goods Receipt (**quantity AND price**) within a **tolerance**; a
  matched bill becomes a **payable** that posts to GL. **A goods receipt alone does NOT create a payable**
  (no GRN accrual in v1). **Accepted consequence:** the liability is **not on the books between receipt and
  bill entry** (a known bill-driven-AP trade-off — accepted risk). Because the GR posted **Stock only, not
  GL**, the **AP bill match is the FIRST GL posting for the purchase** (DR Inventory-or-Purchases / CR AP
  control). Reflected in accounts-payable.md FR-AP-01..06, BR-AP-01/02/03/04, §3.2, §10.1.
- **AP fork 2 — Tolerance** — ✅ **RESOLVED (concept fixed):** a **tolerance** governs the 3-way match;
  **over-tolerance bills are held for review** (accept-variance — audited — or reject); nothing posts while
  held. The exact **value** is OQ-AP-01 (recommended: price within ~2% or a small absolute). Reflected in
  FR-AP-04/05, BR-AP-04.
- **AP fork 3 — v1 feature set** — ✅ **RESOLVED: IN v1 =** supplier **payment runs** (batch-select due /
  matched bills → one payment), **single bill payment**, **debit notes / adjustments** (against open
  payables), and **opening balances** (pre-existing payables at go-live). Reflected in FR-AP-09/10/11/12/13,
  BR-AP-05/06/07.
- **AP fork 4 — COGS / inventory valuation** — ✅ **RESOLVED = DEFERRED (T2.2):** v1 AP books the purchase
  debit to **inventory-or-expense per `gl_configs` WITHOUT a COGS roll-up** — it creates the liability and a
  debit, but values no inventory and posts no COGS. Reflected in FR-AP-06, BR-AP-11, §10.3.

> **Permissions & reconciliation (ratified with the forks):** `AP.VIEW` / `AP.BILL.ENTER` / `AP.BILL.MATCH`
> / `AP.PAYMENT.RUN` / `AP.DEBITNOTE` / `AP.OPENING.SET`; per-company scope; `assertCanActIn` on every read
> path; audit on every mutation; `BILL-####` / `PAYRUN-####` via `code_sequence`; `ScopeGuard` gains new AP
> target types (note for ADR-0015). The **reconciliation invariant** (AP sub-ledger total == GL `2100
> Accounts Payable` control balance at all times) is fixed and is the chief acceptance bar. Reflected in
> FR-AP-08/14/15, BR-AP-02/08, NFR-AP-01.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0015)

- **OQ-AP-01** — **Tolerance value/shape.** The tolerance *concept* is fixed (over-tolerance held); the
  **value** is open. *Recommended default:* **price within 2% OR a small absolute (whichever is greater),
  per line**; quantity must match the received qty (no quantity tolerance by default). *Decider:* owner
  (finance). *Blocks ADR-0015:* **NO** — a configurable setting confirmed before go-live.
- **OQ-AP-02** — **Bill due date / supplier terms.** *Recommended default:* due date from the bill's stated
  terms if present, else a per-supplier net-days field, else net-on-receipt (0 days); a rich terms master is
  a later additive slice. *Decider:* owner. *Blocks ADR-0015:* **NO** — additive.
- **OQ-AP-03** — **Payment method / bank selection & payment approval.** v1 posts the payment bank leg to a
  Cash/Bank GL account from `gl_configs` (full Cash&Bank is T1.4). *Recommended default:* one default
  Cash/Bank GL account per company from `gl_configs`; payment-method (cash / bank transfer / mobile money)
  selection and a payment-approval workflow are later additive slices (Cash&Bank T1.4 / Approvals X.5).
  *Decider:* owner. *Blocks ADR-0015:* **NO** — additive.
- **OQ-AP-04** — **Input VAT on the bill / service (non-goods) bills.** Whether v1 captures input VAT on the
  bill (for the eventual VAT return, T1.5) and whether a **pure expense / service bill** (no GR to match) is
  in v1. *Recommended default:* capture the bill total **incl. any stated VAT** for the payable (the VAT
  *return* is T1.5); v1 focuses on the **goods 3-way match** — a pure expense/service bill posts to an
  expense account **without** the goods match (a thin additive path) or is deferred, owner's call. *Decider:*
  owner. *Blocks ADR-0015:* **NO** — the goods 3-way match (the core) is fixed.
- **OQ-AP-05** — **Partial bill vs partial GR.** How the match handles a bill for some of the received
  quantity (or a GR not yet fully billed). *Recommended default:* match **per line up to the
  received-not-yet-billed quantity**; the remainder stays open for a later bill; over-billing the received
  quantity is **held as a variance** (BR-AP-04). *Decider:* owner. *Blocks ADR-0015:* **NO** — the
  per-line partial-match default stands.
- **OQ-CUR-03** — *(carried)* confirm **rounding mode + TZS decimals** — the bill total, the GL legs, and
  payment allocations must round identically to the AP balance and the control account (NFR-AP-02).
  *Recommended default:* half-up, TZS = 0 dp. *Decider:* owner (finance input). *Blocks ADR-0015:* **NO**
  for the model; **confirm before go-live**.

## Cash & Bank

> **FULLY RATIFIED 2026-06-09.** Cash & Bank is **Increment 3 (T1.4)** of the full-ERP roadmap
> (docs/ROADMAP.md T1.4 / §5) — the Tier-1 finance finisher. The owner closed **all Cash & Bank scoping
> forks** (multiple named cash/bank accounts each linked to a GL `1xxx` account, replacing the single
> `gl_configs` `CASH`; **manual** bank reconciliation with the book==statement completion check, **no
> statement file import**; the four v1 operations — inter-account transfers, direct cash/bank entries,
> cheque register, per-account statement & balance — **plus** AR receipts / AP payments routing to a chosen
> cash/bank account; synchronous GL posting; the scope/permission set). cash-and-bank.md is **Ratified**.
> **No ADR-0016-blocking question remains.** solutions-architect may start **ADR-0016** now (Cash & Bank
> data model — cash_bank_accounts, cash_transactions, transfers, bank_reconciliations, the cheque register;
> migration **V13**; the **additive AR/AP cash-account-selection touch**; the synchronous `GLPostingService`
> posting; the `ScopeGuard` `cashbankaccount` target type; the **cash-account ⇄ linked-GL-account
> reconciliation**). The remaining OQ-CASH items are **non-blocking** detail (recommended defaults stand).

### Resolved (owner, 2026-06-09) — the Cash & Bank scoping forks

- **CASH fork 1 — Accounts** — ✅ **RESOLVED:** model **multiple** CASH accounts (petty cash, tills) and
  BANK accounts (per bank / per branch) per company. Each account: name, type (CASH | BANK), bank details
  (for BANK), currency (= base v1), a **link to its own GL `1xxx` asset account**, active/status, audit, a
  `CB-####` code. **Each cash/bank account maps to its own GL account — this replaces the single
  `gl_configs` `CASH`** with real named accounts. Reflected in cash-and-bank.md FR-CASH-01..04, BR-CASH-01,
  §3.1.
- **CASH fork 2 — Bank reconciliation = MANUAL** — ✅ **RESOLVED:** the operator marks ledger transactions
  **CLEARED** against the bank statement, records a reconciliation (statement date + statement closing
  balance), and the system checks **book balance == statement/bank balance** — required to **complete** the
  reconciliation. A **cleared flag is immutable once reconciled**. **NO statement file import** in v1 (CSV /
  MT940 deferred — OQ-CASH-01). Reflected in FR-CASH-13/14/15, BR-CASH-06/07.
- **CASH fork 3 — v1 operations** — ✅ **RESOLVED: all four IN v1** — (a) **inter-account transfers** (DR
  destination GL / CR source GL, same company); (b) **direct cash/bank entries** not tied to AR/AP (post the
  cash/bank GL account against a chosen counter-account); (c) **cheque management** (a cheque register —
  number, ISSUED/CLEARED/CANCELLED, post-dated cheques (issue vs value date), and cheque printing — printing
  **depends on the cross-cutting PDF capability X.1**, flagged); (d) **per-account statement & balance** (a
  running statement + current balance, a read). **PLUS the always-in core: AR receipts and AP payments route
  to a chosen cash/bank account** (the additive AR/AP touch; default if unspecified). Reflected in
  FR-CASH-05..12, BR-CASH-04/05/09/12, §3.2..§3.6.
- **CASH fork 4 — GL posting** — ✅ **RESOLVED:** every cash/bank movement posts to GL **synchronously** via
  `GLPostingService.post` (transfers, direct entries, and the cash legs of AR receipts / AP payments) — the
  established AR/AP precedent (ADR-0014 D-4), not the outbox. Append-only; corrections via reversing entries.
  The cash/bank account **book balance reconciles to its linked GL account** (the sub-ledger ⇄ control rule).
  Reflected in FR-CASH-16/17, BR-CASH-02/03/10, NFR-CASH-04.

> **Permissions & reconciliation (ratified with the forks):** `CASH.VIEW` / `CASH.ACCOUNT.MANAGE` /
> `CASH.TRANSFER` / `CASH.ENTRY.RECORD` / `CASH.RECONCILE` / `CHEQUE.MANAGE`; per-company scope (+ branch
> where relevant — a till / petty-cash may be branch-scoped, banks company-level); `assertCanActIn` on every
> read path; audit on every mutation; `CB-####` / `CBT-####` via `code_sequence`; `ScopeGuard` gains a new
> **`cashbankaccount`** target type (note for ADR-0016). The **two reconciliation invariants** — (1)
> cash/bank account book balance == its linked GL account balance at all times, and (2) a completed bank
> reconciliation had book == statement — are fixed and are the chief acceptance bars. Reflected in
> FR-CASH-17/18/19, BR-CASH-02/06/08, NFR-CASH-01.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0016)

- **OQ-CASH-01** — **Bank statement file import format.** v1 reconciliation is **manual**; importing a
  statement file (CSV / MT940 / BAI2 / OFX) + auto-matching is deferred. *Recommended default:* manual
  marking + a hand-entered statement closing balance in v1; a **CSV** importer first when prioritised,
  feeding the same manual-reconciliation model. *Decider:* owner (finance). *Blocks ADR-0016:* **NO** — the
  manual model is fixed; an importer is additive.
- **OQ-CASH-02** — **Cheque printing dependency.** The cheque **register** is in v1; **printing** depends on
  the cross-cutting PDF capability (ROADMAP X.1). *Recommended default:* ship the register in v1; printing
  lands with / after X.1 (`DocumentService`), reusing the register data. *Decider:* owner + architect (with
  X.1). *Blocks ADR-0016:* **NO** — the register data is fixed; printing is an additive consumer.
- **OQ-CASH-03** — **Petty-cash / till branch-scoping default.** A CASH account (till / petty cash) **may**
  be branch-scoped; a BANK account is company-level. *Recommended default:* a CASH account **may carry a
  branch** (nullable); a BANK account carries none (company-level); the books stay company-level (gl.md
  NFR-GL-01). *Decider:* owner. *Blocks ADR-0016:* **NO** — the nullable-branch-on-CASH default stands.
- **OQ-CASH-04** — **Multi-currency / foreign-currency bank accounts.** v1 is **base currency** (BR-CASH-11);
  a foreign-currency bank account + FX revaluation are deferred to the FX cross-cutting item (X.6 / gl.md
  §10.5, ADR-0005 D-8). *Recommended default:* base-currency cash/bank accounts in v1; multi-currency bank
  accounts land with FX. *Decider:* owner. *Blocks ADR-0016:* **NO** — deferred, not precluded.
- **OQ-CASH-05** — **Deposit slips / batched lodgements.** Grouping several AR receipts into one bank deposit
  (a deposit batch clearing as one statement line). *Recommended default:* route each receipt to a chosen
  account directly in v1; deposit batching is a later additive convenience that reconciles onto the same
  per-account statement. *Decider:* owner. *Blocks ADR-0016:* **NO** — additive.
- **OQ-CASH-06** — **Reconciliation reversal / un-reconcile.** Unwinding a **completed** reconciliation to
  fix a mistake. *Recommended default:* v1 corrects via a reversing entry / a new reconciliation (reconciled
  cleared flags are immutable — BR-CASH-07); an explicit un-reconcile workflow is a later additive slice.
  *Decider:* owner. *Blocks ADR-0016:* **NO** — additive.
- **OQ-CASH-07** — **How the existing `gl_configs` `CASH` maps to the new default cash/bank account.** Cash &
  Bank replaces the single `CASH` account with named accounts. *Recommended default:* the **company default
  cash/bank account is the resolution of `CASH`**, so AR/AP callers that do not name an account keep working
  (the default account's linked GL account becomes the cash leg). *Decider:* architect (ADR-0016, with the
  AR/AP additive touch). *Blocks ADR-0016:* **NO** — it is exactly the design decision ADR-0016 makes; the
  requirement fixes the *behaviour* (default if unspecified, BR-CASH-09).
- **OQ-CASH-08** — **One-to-one cash/bank account ⇄ GL account.** Whether two cash/bank accounts may share a
  linked GL account. *Recommended default:* **one GL account per cash/bank account** (so each balance
  reconciles cleanly to a distinct GL account, BR-CASH-02). *Decider:* owner + architect. *Blocks ADR-0016:*
  **NO** — the one-to-one default stands.
- **OQ-CUR-03** — *(carried)* confirm **rounding mode + TZS decimals** — transfer legs, direct-entry legs,
  the AR/AP cash legs, and the reconciliation balance check must round identically to the cash/bank book
  balance and the linked GL account (NFR-CASH-02). *Recommended default:* half-up, TZS = 0 dp. *Decider:*
  owner (finance input). *Blocks ADR-0016:* **NO** for the model; **confirm before go-live**.

## VAT Return / Tax

> **FULLY RATIFIED 2026-06-09.** The VAT Return / Tax module is the **last Tier-1 finance piece**
> (docs/ROADMAP.md T1.5 / docs/PATH-TO-FULL-ERP.md Phase A). The owner closed **all VAT scoping forks**
> (MONTHLY accrual-basis returns due the 20th; net = output − input + adjustments, net positive payable /
> net negative carries forward; DRAFT → FILE that **locks** the return + posts a **synchronous GL
> settlement**; manual DRAFT adjustments; **WHT IN v1** — capture + track + register + certificate on AP
> payments / AR receipts; **TRA EFD/VFD / e-filing DEFERRED**; the permission set). vat-return.md is
> **Ratified**. **No ADR-0017-blocking question remains.** solutions-architect may start **ADR-0017** now
> (VAT-return data model — `vat_returns` + lines/adjustments, the WHT register; migration **V14**; the new
> **VAT Input/Recoverable** CoA account + `gl_configs` **`VAT_INPUT`** key + a VAT-due account/key; the
> **filing GL settlement posting** + the **credit carry-forward**; the **WHT touch** on the AP-payment /
> AR-receipt cash legs; the `ScopeGuard` **`vatreturn`** target type). The one meaty item — the
> `VAT_INPUT`-account / AP-input-VAT-booking seam (OQ-VAT-01) — **is the decision ADR-0017 makes**, not a
> requirements blocker (the *behaviour* is fixed). The remaining OQ-VAT items are **non-blocking** detail
> (recommended defaults stand).

### Resolved (owner, 2026-06-09) — the VAT scoping forks

- **VAT fork 1 — Period & basis** — ✅ **RESOLVED: MONTHLY returns** (one per company per calendar month,
  **due the 20th of the next month**) on an **invoice/accrual basis** — output VAT from sales **FINALISED**
  in the month, input VAT from supplier bills **DATED** in the month, **payment-independent**. Reflected in
  vat-return.md FR-VAT-01/03/04, BR-VAT-01/04/05.
- **VAT fork 2 — Computation** — ✅ **RESOLVED: net VAT = output VAT (sales) − input VAT (purchases) +
  adjustments + opening credit carried forward.** Output = Σ `sales_invoices.vat_total_amount` (by band,
  from `tax_summary`) for finalised invoices in the period; input = Σ `supplier_bills.vatAmount` for
  matched/approved bills dated in the period. **Net positive = VAT PAYABLE; net negative = CREDIT.**
  Reflected in FR-VAT-03/04/06, BR-VAT-03.
- **VAT fork 3 — Filing posts to GL** — ✅ **RESOLVED:** DRAFT → **FILE** posts a **synchronous GL journal**
  that settles the period's VAT control accounts — **DR `2200 VAT Payable`** (clear output), **CR the new
  `VAT_INPUT` recoverable account** (clear input), book the **net to a VAT-due / -payable-to-TRA liability**
  (net positive) or carry a **credit** (net negative), balanced. A **net credit carries forward** (not a
  cash refund in v1). Reflected in FR-VAT-07/08, BR-VAT-03/06.
- **VAT fork 4 — Return lifecycle** — ✅ **RESOLVED:** **DRAFT** (computed, recomputable as more
  invoices/bills land) → **FILED** (records a filing reference + filing date; **LOCKS** the return — figures
  frozen, immutable, VAT period closed). Append-only — a filed return is corrected via the **next period's
  adjustments**, not edited. Reflected in FR-VAT-02/08/09, BR-VAT-02/10/11.
- **VAT fork 5 — Credit carry-forward** — ✅ **RESOLVED:** when input VAT > output VAT (net credit), the
  credit carries forward as the **opening credit on the next period's return**. Reflected in FR-VAT-07,
  BR-VAT-03.
- **VAT fork 6 — VAT adjustments** — ✅ **RESOLVED:** **manual adjustment lines on a DRAFT** return before
  filing (bad-debt VAT relief, prior-period corrections, credit/debit-note VAT) — each a **reason + amount +
  sign**, **audited**, affecting the net. Reflected in FR-VAT-05, BR-VAT-09.
- **VAT fork 7 — Withholding tax (WHT)** — ✅ **RESOLVED = IN v1 (lean but real).** Track WHT (the TZ 2%
  withholding; withholding VAT) captured at an **AP payment** (we withhold — pay the supplier less, book a
  **WHT liability**) and/or an **AR receipt** (a customer withholds — we receive less, book a **WHT
  receivable/asset**), with a **WHT rate/type**, the withheld amount, a **WHT certificate**, and a **WHT
  register/return** for remittance — WHT **reduces the cash paid/received** and is **separate** from the VAT
  net. **Minimal v1 = capture + track + register/certificate; the full WHT-by-type matrix + e-filing are
  deferred** (OQ-VAT-02). The biggest scope item; the additive AP/AR cash-leg touch is flagged for ADR-0017.
  Reflected in FR-VAT-10/11/12, BR-VAT-12, §3.7.
- **VAT fork 8 — TRA/EFD/VFD fiscalisation** — ✅ **RESOLVED = DEFERRED (accepted boundary):** the return is
  **computed + a filing record kept** (an operator-entered filing reference + date); **no** direct TRA
  e-filing / EFD/VFD integration in v1 (the same separable integration Sales deferred — OQ-SALES-03).
  Reflected in §10.1, NFR-VAT-08.
- **VAT fork 9 — Permissions** — ✅ **RESOLVED:** `VAT.VIEW` / `VAT.RETURN.PREPARE` / `VAT.RETURN.FILE` /
  `VAT.ADJUST` / `WHT.VIEW` / `WHT.MANAGE`; per-company scope; `assertCanActIn` on every read path; audit on
  prepare/file/adjust/WHT capture; `ScopeGuard` gains a new **`vatreturn`** target type; numbering
  `VATR-####` / `WHT-####` via `code_sequence`. Reflected in FR-VAT-14/15, NFR-VAT-01/03/05.

> **Reconciliation bar (ratified with the forks):** a **filed** return reconciles to the books — the
> period's **output VAT == the `2200 VAT Payable` movement**, the **input VAT == the `VAT_INPUT` movement**,
> and the **filing settlement entry's net == the return's net** (BR-VAT-08, NFR-VAT-01) — the chief
> acceptance bar. A return **cannot be filed twice** (BR-VAT-11); a WHT capture's **cash reduction == its
> liability/receivable** (BR-VAT-12).

### The ADR-0017 design seam (a DECISION the architect makes — does NOT block the requirements)

- **OQ-VAT-01** — **The `VAT_INPUT` account + how AP's input VAT relates to it.** There is **no** VAT
  input/recoverable account / `gl_configs` key yet (ADR-0013 D-13 has `VAT_PAYABLE` only). ADR-0017 must add
  a CoA **"VAT Input / Recoverable"** account + a `gl_configs` **`VAT_INPUT`** key (and a **VAT-due**
  liability account/key for the net), and decide **how the AP bill's input VAT reaches it** — **either** AP
  books the bill's stated VAT to `VAT_INPUT` at bill-match (accounts-payable.md FR-AP-06's "[+ DR VAT input
  if captured]" goes live), **or** the return reads `supplier_bills.vatAmount` and the **filing journal** is
  where input VAT is first separated onto the books (both reconcile — BR-VAT-08). *Recommended default:*
  **book the bill's input VAT to `VAT_INPUT` at AP bill-match** (so the books carry input VAT continuously,
  mirroring how output VAT already sits on `2200`, and the return reconciles to the period movement); the
  filing journal then clears `2200` + `VAT_INPUT` to the VAT-due liability. *Decider:* **architect
  (ADR-0017).** *Blocks ADR-0017:* **NO** — it **is** the decision ADR-0017 makes; the requirement fixes the
  behaviour.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0017)

- **OQ-VAT-02** — **WHT scope depth.** v1 WHT is **lean** (a configurable rate/type incl. withholding VAT +
  capture + track + register + certificate, on AP payments / AR receipts). *Recommended default:* a small
  set of owner-configurable WHT rates/types booking a single WHT liability + a single WHT receivable
  account; the **full WHT-by-type matrix** (payment type × residency × treaty) and **WHT e-filing** are
  deferred. *Decider:* owner (finance / tax). *Blocks ADR-0017:* **NO** — the lean capture model is fixed.
- **OQ-VAT-03** — **Adjustment sources (bad-debt VAT relief, prior-period).** v1 adjustments are **manual**.
  *Recommended default:* manual lines in v1; **auto-deriving bad-debt VAT relief from AR write-offs** and
  prior-period wizards are later additive conveniences. *Decider:* owner (finance). *Blocks ADR-0017:*
  **NO** — manual is fixed; auto is additive.
- **OQ-VAT-04** — **Cash-basis vs accrual-basis VAT.** v1 is **accrual/invoice basis** (output on finalise,
  input on bill date). *Recommended default:* accrual in v1; a cash-basis scheme (recognise on payment) is a
  later additive option. *Decider:* owner. *Blocks ADR-0017:* **NO** — accrual is fixed.
- **OQ-VAT-05** — **Multi-rate / historical VAT-rate changes.** v1 reads STANDARD 18 / ZERO_RATED 0 / EXEMPT
  from `tax_rates`. *Recommended default:* the three current bands in v1; rate-effective-dating / extra
  schedules ride a richer tax-code scheme later (sales.md OQ-PROD-05 note). *Decider:* owner. *Blocks
  ADR-0017:* **NO** — additive.
- **OQ-VAT-06** — **Partial-exemption / input-VAT apportionment.** v1 recovers input VAT **in full** from
  matched bills. *Recommended default:* full recovery in v1; partial-exemption apportionment (recover only
  the taxable-supply proportion for a mixed business) is deferred. *Decider:* owner (finance). *Blocks
  ADR-0017:* **NO** — additive.
- **OQ-VAT-07** — **Multi-currency VAT.** v1 is **base currency (TZS)** (BR-VAT-13). *Recommended default:*
  base-currency VAT in v1; foreign-currency VAT treatment lands with FX (X.6 / gl.md §10.5). *Decider:*
  owner. *Blocks ADR-0017:* **NO** — deferred, not precluded.
- **OQ-VAT-08** — **Partial-period bills + VAT rounding per band.** (a) A bill **dated in the period but
  matched later** — which period does it count in? *Recommended default:* the period of its **bill date**
  (accrual basis, BR-VAT-04); a bill matched after that period's return is **filed** is corrected via the
  **next period's adjustment** (BR-VAT-10). (b) **VAT rounding per band.** *Recommended default:* sum the
  already-rounded per-invoice band amounts (no re-rounding of an already-computed line VAT), half-up,
  TZS = 0 dp. *Decider:* owner (finance). *Blocks ADR-0017:* **NO** — defaults stand; confirm before
  go-live.
- **OQ-CUR-03** — *(carried)* confirm **rounding mode + TZS decimals** — the output/input sums, the net, the
  GL settlement legs, and the WHT legs must round identically (NFR-VAT-02). *Recommended default:* half-up,
  TZS = 0 dp. *Decider:* owner (finance input). *Blocks ADR-0017:* **NO** for the model; **confirm before
  go-live**.
