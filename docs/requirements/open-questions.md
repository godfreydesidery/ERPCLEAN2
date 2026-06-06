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
