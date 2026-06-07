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
