# Requirements — Multicurrency (cross-cutting)

> Status: **DESIGN-READY, BUILD-LATER (2026-06-06)** — the owner's instruction is *"consider
> multicurrency, do not tie up things"*: design so nothing is hard-wired to one currency, but full
> FX (rate feeds, conversion at settlement, revaluation, gain/loss) is **NOT** built in v1.
> Author: system-analyst · Concern: **cross-cutting** (not a standalone module). This is the
> business-level spec. The **technical enforcement** — that money is always *amount + currency* and
> can never exist as a bare number — is the solutions-architect's, in **ADR-0005** (in parallel). Do
> not infer a schema from this document; it describes the business principle, rules, and what each
> module must honour, not tables or columns.

## 1. Purpose & scope

### The principle (business terms)

**Every monetary amount in the system travels with the currency it is expressed in.** A price, a
line total, a tax amount, a payment, a balance — none of them is "just a number". Each is a
**monetary amount**: a *value* together with the *currency* that value is denominated in. The system
**never assumes a single currency** anywhere — not in storage, not in calculation, not in display.

This is stated now, **before** the money-bearing operational modules (Sales, Purchases, Stock,
later Finance) are specified, so that none of them is built with an implicit "everything is TZS"
assumption baked in. Retro-fitting currency onto modules that assumed one is exactly the rework this
document exists to prevent.

### What this is — and is not

This is a **cross-cutting concern**, not a module. There is no "multicurrency screen". Rather, every
module that records money must satisfy the requirements below. Where this document and a module
document (e.g. sales.md, purchases.md) overlap, **this document owns the currency principle** and the
module document references it.

### In scope NOW (v1 — "currency-aware, single-currency in practice")

- A **currency master**: the system holds a set of currencies (a known concept, maintainable), each
  with its ISO 4217 code, symbol, name, decimal places (minor units), and an active flag.
- A **base / reporting currency per company**: each company has one configured base currency. For
  Tanzanian deployments the default is **TZS**, but it is **configurable, never hard-coded**.
- **Money always carries its currency**: every monetary value recorded anywhere is associated with a
  currency and rounded to that currency's minor units.
- The **capability** to express a transaction in a currency other than the company base (a *foreign*
  currency), with the base-currency equivalent and the rate used **recorded on the transaction** —
  even if day-one data is entirely base-currency. The capability is *reserved in the model*; the day
  it is needed, no schema migration of the meaning of money is required.

In practice, v1 operates **effectively single-currency-per-company** (almost all transactions are in
the company base, e.g. TZS). The point of v1 is that the **model and the requirements do not preclude
multi-currency** — not that multi-currency operations are switched on.

### DEFERRED (recognised, NOT built in v1 — flagged, not designed away)

- **Live FX rate feeds / rate sourcing** — no automatic rate retrieval, no rate-table maintenance UI
  beyond what (if anything) the architect deems the minimum stub. Where a foreign-currency amount
  needs a rate in v1, the rate is **entered/known with the transaction**; the *source* of that rate
  is out of scope.
- **Settling/paying a document in a currency different from the one it was billed in** (cross-currency
  settlement) and the **realised FX gain/loss** that arises from it.
- **Period-end revaluation** of open foreign-currency balances and the **unrealised FX gain/loss** it
  produces.
- **Multi-currency price lists** (a catalogue item priced in several currencies at once).
- A separate **group/reporting currency at organisation level** distinct from each company's base
  (see OQ-CUR-01).

Each deferred item becomes its own requirement when prioritised. None of them may be *precluded* by
v1 design — that is the whole instruction.

## 2. Actors / personas

- **System / company administrator** — maintains the currency master (activate/deactivate
  currencies), sets a company's base currency at company setup. Acts within IAM permission scope.
- **Branch operator (cashier / sales clerk / purchasing clerk)** — records transactions; in v1 these
  are virtually always in the company base currency. The operator sees amounts displayed with the
  correct symbol and decimal places for their currency.
- **Finance / accounts user** — reads balances *per currency* (a party's USD receivable and TZS
  receivable are distinct balances, never summed). Defined fully when Finance is specified; named
  here so the per-currency-balance principle is owned early.

## 3. Functional requirements

> IDs are `FR-CUR-NN`. Each is a crisp, testable statement. Each is tagged **[NOW]** (built in v1) or
> **[DEFERRED]** (capability must not be precluded, but not built in v1). "Money" / "monetary amount"
> below always means *value + currency* per the principle in §1.

### Currency master

- **FR-CUR-01** **[NOW]** The system maintains a set of **currencies**. Each currency record carries:
  an **ISO 4217 code** (e.g. `TZS`, `USD`, `EUR`), a **symbol** (e.g. `TSh`, `$`, `€`), a **name**
  (e.g. "Tanzanian Shilling"), the number of **decimal places / minor units** (see FR-CUR-05), and an
  **active flag**. Currencies are reference/master data, not per-company.
- **FR-CUR-02** **[NOW]** A currency can be **activated or deactivated**. Only **active** currencies
  may be selected on a new transaction or set as a company base; deactivating a currency must not
  alter or invalidate amounts already recorded in it (history is immutable — BR-CUR-05).

### Base / reporting currency per company

- **FR-CUR-03** **[NOW]** Each **company has exactly one configured base (reporting) currency**,
  chosen from the active currencies. For Tanzanian deployments the **default is TZS**, but the base
  currency is **configurable per company and is never hard-coded** anywhere in the system. A company
  reports and values in its base currency.
- **FR-CUR-04** **[NOW]** A company's base currency is **set once at company setup**. Changing it
  afterwards is a **controlled, exceptional operation** (BR-CUR-02), not an everyday edit — flag it as
  out-of-band; this document does not design how (that is a future requirement + architect detail).

### Money always carries currency; correct minor units

- **FR-CUR-05** **[NOW]** Every monetary value recorded anywhere (price, quantity-extended line
  total, discount, tax, document total, payment, balance, valuation) is **associated with a currency**
  and is stored and displayed to that currency's **minor units** (decimal places). The system must
  support currencies with **2 decimals** (USD, EUR), **0 decimals** (e.g. TZS in practice, JPY), and
  **3 decimals** (e.g. KWD, BHD); the decimal count comes from the currency record (FR-CUR-01), it is
  **not** assumed to be 2.
- **FR-CUR-06** **[NOW]** A monetary amount with **no currency is invalid** and must be rejected at the
  point of entry/save (BR-CUR-01). There is no "default to base, currency omitted" path — base
  currency is still *recorded as the currency*, never left implicit.
- **FR-CUR-07** **[NOW]** Display and rounding follow the **currency's own minor units and symbol**:
  amounts are shown with the right symbol/placement and decimal places for the currency they are in,
  not a global format. (Exact symbol placement and locale formatting are a frontend/preference detail;
  the requirement is that decimals/symbol come *from the currency*, not a hard-coded assumption.)

### Foreign-currency capability (reserved)

- **FR-CUR-08** **[DEFERRED capability — model must allow]** A transaction **may be expressed in a
  currency other than the company base** (a **foreign-currency transaction**). v1 day-one data is
  expected to be base-currency only, but the model and the requirements **must not preclude** a
  document raised in USD by a TZS-base company. The *capability* is reserved now; the *operations*
  around it (below) are deferred.
- **FR-CUR-09** **[NOW for the rule; the data is DEFERRED]** **When a foreign-currency amount exists,
  the base-currency equivalent and the exchange rate used are recorded WITH the transaction** at the
  time it is recorded. History is thereby **immutable to later rate changes** (BR-CUR-05): a document
  raised at one rate keeps that rate forever; the system never recomputes a past base value when a
  rate later changes. (In v1, with base-currency-only data, the rate is effectively 1 and the base
  equivalent equals the amount — but the *requirement to carry rate + base equivalent on any
  foreign-currency line* stands so the capability is not precluded.)
- **FR-CUR-10** **[DEFERRED]** **FX rate sourcing/feeds** — automatic retrieval of exchange rates from
  any source, and any rate-maintenance workflow — are **out of scope for v1**. Where a foreign rate is
  needed, it is **known/entered with the transaction**; the system does not source it. Building this is
  a future requirement.
- **FR-CUR-11** **[DEFERRED]** **Cross-currency settlement** (paying/receiving a document in a currency
  different from the one it was billed in) and the **realised FX gain/loss** that results are **out of
  scope for v1**. A v1 payment is in the **same currency** as the document it settles (BR-CUR-06).
- **FR-CUR-12** **[DEFERRED]** **Revaluation** of open foreign-currency balances at period end, and the
  resulting **unrealised FX gain/loss**, are **out of scope for v1**.

### Permissions (gating)

- **FR-CUR-13** **[NOW]** Maintaining the currency master and setting a company's base currency are
  **gated by IAM permissions** (e.g. a `CURRENCY.MANAGE`-style permission; setting/changing a company
  base is an administrative permission). Exact permission codes are seeded with the concern; this FR
  only fixes that currency administration is permission-gated per IAM (FR-IAM-11).

## 4. Business rules (invariants)

- **BR-CUR-01** **An amount without a currency is invalid.** Money is never a bare number; the
  currency is part of the value and must be present on save (FR-CUR-06).
- **BR-CUR-02** **A company's base currency is set once; changing it is a controlled operation.**
  Re-basing an in-use company has system-wide implications for every historical amount and balance —
  it is not an everyday edit. This rule **flags** that implication; it does **not** design the
  re-basing procedure (future requirement + architect).
- **BR-CUR-03** **Rounding uses the currency's defined minor units.** An amount is rounded to the
  decimal places declared on its currency (FR-CUR-05), never to a global "2 dp" assumption. (The
  *rounding mode* — half-up vs banker's — is OQ-CUR-03.)
- **BR-CUR-04** **Only an active currency may be chosen** for a new transaction or as a company base
  (FR-CUR-02). Deactivating a currency does not touch existing amounts in it.
- **BR-CUR-05** **A stored historical transaction's currency and the rate it was recorded at are
  immutable.** The system **never recomputes** a past base-currency value when an exchange rate later
  changes. Each foreign-currency transaction carries the rate that applied when it was recorded, for
  the life of the record.
- **BR-CUR-06** **A v1 payment settles a document in the document's own currency.** Cross-currency
  settlement is deferred (FR-CUR-11); until built, the currency of a receipt/payment equals the
  currency of the document it settles.
- **BR-CUR-07** **Cross-currency arithmetic is forbidden without an explicit conversion.** The system
  must never add, subtract, or compare amounts of different currencies as if they were the same
  number. Combining a USD amount and a TZS amount requires an explicit, recorded conversion (deferred);
  silently summing them is a defect, not a feature.
- **BR-CUR-08** **Balances are held per currency.** A party's receivable in USD and the same party's
  receivable in TZS are **two distinct balances**, never summed into one figure. (Owned by Finance
  when specified; the invariant is fixed here so Finance cannot violate it later.)

## 5. What this means for other modules

This concern constrains every money-bearing module. Each module document references this one rather
than re-deriving the principle. Business-level expectations:

- **Parties** ([parties.md](parties.md)) — a customer/supplier (and external agent paid commission)
  **may have a preferred/default transacting currency** as an **optional** attribute, so a document
  raised for that party can default to their currency. Whether v1 captures this attribute at all is
  **OQ-CUR-05**; if captured, it is optional and defaults to the company base when unset.
- **Sales / Purchases** (future docs) — every sales/purchase document carries a **document currency**;
  line amounts, discounts, taxes, and totals are in the **document currency**; where the document
  currency differs from the company base, the **base-currency equivalent and the rate** are recorded
  on the document (FR-CUR-09). In v1, document currency = company base in practice.
- **Stock** (future doc) — stock **valuation currency = the company base currency**. Stock is valued
  and reported in the base; a purchase in a foreign currency is converted (at the recorded rate) into
  a base-currency cost when it lands in inventory. (Valuation method — FIFO/avg — is a Stock-module
  question, not this one.)
- **Finance / AR / AP** (future doc) — balances are **per currency** (BR-CUR-08): a party's USD
  receivable and TZS receivable are distinct; statements and ageing are per currency. Realised and
  unrealised FX gain/loss accounts are **deferred** (FR-CUR-11/12) but must not be precluded.

Each of the above is **business-level guidance** for the module rounds that follow; the exact fields
and the technical "money = amount + currency" type live in **ADR-0005**, not here.

## 6. Non-functional

- The "money is always amount + currency" rule is a **correctness invariant**, enforced technically
  per ADR-0005 (e.g. a money value object), not left to each developer's discipline. A bare-number
  money field anywhere is a release blocker, the same way tenant leakage is for IAM (iam.md §7).
- Currency-aware rounding must be consistent across backend calculation and frontend display — the
  same amount rounds to the same value in both. (Mismatch between displayed and stored totals is a
  finance-grade defect.)
- The currency master is small, slow-changing reference data; no special performance concern beyond
  normal caching.

## 7. Assumptions

- v1 deployments are Tanzanian; the **company base default is TZS** but configurable (FR-CUR-03).
- Default time zone is **Africa/Dar_es_Salaam**; time zone is an IAM/locale concern (iam.md §8), not
  currency — listed only to keep locale assumptions in one place.
- TZS is treated with **0 minor units in practice** (cents/senti are not used operationally); the
  currency record still declares its decimal places explicitly (FR-CUR-05) rather than the system
  assuming any value. Confirm the TZS decimal count in OQ-CUR-03.
- "Effectively single-currency-per-company" is an operational expectation for v1, **not** a model
  constraint — the model must permit foreign currency (FR-CUR-08) even though daily use will not
  exercise it.

## 8. Out of scope for v1 (deferred)

Live FX rate feeds / rate sourcing (FR-CUR-10); cross-currency settlement and realised FX gain/loss
(FR-CUR-11, BR-CUR-06); period-end revaluation and unrealised FX gain/loss (FR-CUR-12);
multi-currency price lists; a distinct org-level group/reporting currency separate from company base
(OQ-CUR-01); company base re-basing procedure (BR-CUR-02). Each tracked for a later round and **none**
precluded by v1 design.
