# 0005 — Money & currency: every amount is currency-aware from day one

- **Status:** Accepted
- **Date:** 2026-06-06
- **Deciders:** solutions-architect (owner instruction: "consider multicurrency, do not tie up things"
  — confirmed intent DESIGN-READY, BUILD LATER)
- **Context source:** [docs/requirements/multicurrency.md](../requirements/multicurrency.md) (business
  FRs/BRs — authored in parallel by system-analyst; the business source for this decision);
  [PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md) §1 (Postgres-native, BigDecimal), §3.6
  (persistence discipline); [DATA-MODEL.md](../../DATA-MODEL.md) §Conventions ("Money/quantity
  `NUMERIC(18,4)`" — refined by this ADR); ADR-0001 D-G (uid/id, Long-as-string rationale).

## Context

The owner is about to start the first money-bearing modules — **Sales, Purchases, Stock** — and ruled:
*"consider multicurrency, do not tie up things."* The confirmed reading is **design-ready, build later**:
every monetary value must be currency-aware from the first money column, so nothing is hard-wired to a
single currency (TZS today), but the full FX engine — rate feeds, conversion service, revaluation,
realized/unrealized gain-loss, cross-currency settlement — is **not** built now.

The forces:
- **The retrofit trap.** Adding currency to money columns *after* Sales/Purchases/Stock have shipped
  means a migration across every transactional table, every DTO, every report, and the Angular layer —
  exactly the "tie up" the owner warned against. The cheapest time to make money currency-aware is
  before the first money column exists. This ADR predates the first money-bearing module deliberately.
- **Don't over-build.** A live multi-currency ledger (rate sourcing, revaluation, gain/loss) is real
  engineering the business has not yet specified and does not need on day one. Reserving the *shape*
  costs little; building the *engine* now is heavy and speculative.
- **Precision is a correctness invariant, not a preference.** Money in `float`/`double` is a defect
  class, not a style choice. The wire format must not silently lose precision either (the same reason
  ADR-0001 D-G serialises 64-bit ids as strings).
- **Boundaries must hold.** A `Money` building block is cross-cutting; it cannot live in a business
  module or every later module would import it across a `ModuleBoundaryTest` line. It belongs in
  `platform.common`.

This ADR fixes the **technical principle** every money-bearing module enforces identically. It is a
**standard, not a schema**: it defines the building block, the storage and rounding convention, the
foreign-currency recording shape, the arithmetic rules, and the wire contract — concrete enough that a
backend engineer applies it the same way everywhere, without inventing per-module variants. The
currency master table and the FX engine are the analyst's / a later ADR's concern (D-3, D-8).

## Decision

### D-1 — A monetary value is an inseparable `(amount, currency)` pair — never a bare number

The headline rule: **no money column exists without an adjacent currency column.** A bare amount with
an implied currency is forbidden, in the database, in Java, and on the wire.

This manifests as **one** reusable JPA `@Embeddable` value object in `com.erp.platform.common`
(working name `Money`), holding `amount` (BigDecimal) + `currency` (ISO 4217 alpha-3 code, see D-3).
Every entity that holds money embeds it; no module re-invents it. Embedding a `Money` materialises a
column pair — e.g. `@AttributeOverride` to `unit_price_amount` / `unit_price_currency`,
`line_total_amount` / `line_total_currency`. A single naming convention applies: **`<field>_amount`
NUMERIC paired with `<field>_currency` CHAR(3)**, both `NOT NULL` together (or nullable together).

`Money` is a value object: immutable, no identity, equality by value, constructed through a factory
that rejects a null/blank currency and a null amount. There is no setter that mutates amount without
currency. The rule "no money column without an adjacent currency" is therefore enforced **structurally**
by the embeddable (you cannot embed half of it) and backed by an ArchUnit rule: a `BigDecimal` field
named `*_amount` (or `*Amount`) on an entity must be part of an embedded `Money`, not a free column.

> Why an embeddable rather than a separate `money` row/table: money is an attribute of its owning
> document line/header, not an entity with its own lifecycle; a join per amount is needless cost. The
> embeddable gives the column pair, type safety, and the arithmetic guard (D-6) in one place.

### D-2 — Amount type & precision: `BigDecimal` in Java, `NUMERIC` in Postgres — never float/double

- **Java:** `BigDecimal` for every amount and rate. `float`/`double` for money is banned (an ArchUnit
  rule already has precedent for structural bans; add: no `double`/`float` field named `*amount*`/
  `*price*`/`*rate*`/`*total*` on an entity or money DTO). All `BigDecimal` construction uses the
  `String`/`BigDecimal` constructors, never `new BigDecimal(double)`.
- **Postgres storage convention (fixed):**
  - **Amounts:** `NUMERIC(19,4)`. Scale **4** carries every ISO 4217 minor-unit currency in scope,
    including 2-decimal (TZS, USD), 3-decimal (e.g. BHD, KWD, OMR — 1000 minor units), and 0-decimal
    (e.g. JPY), with one spare decimal for intermediate sub-minor-unit values (e.g. unit prices that
    are not whole cents). Precision **19** holds totals into the trillions of minor units — ample for
    TZS-denominated documents. This **refines** DATA-MODEL.md's `NUMERIC(18,4)` line to `NUMERIC(19,4)`
    for monetary amounts (19 keeps headroom and matches the common JDBC/`long`-fits boundary);
    quantities remain a separate convention.
  - **Exchange rates:** `NUMERIC(19,8)` (scale **8**) on the foreign-currency recording fields (D-5).
    Rates need more scale than amounts (a rate can be `0.00042100` TZS-per-minor-unit); 8 decimals is
    the conventional FX rate scale and avoids rounding the rate itself before it is applied.
- **Rounding (fixed convention):**
  - **Boundaries (display, settlement, the stored amount of a posted document):** round **HALF_UP** to
    the currency's minor-unit scale (D-3 supplies the decimals per currency; TZS/USD = 2, JPY = 0,
    BHD = 3). HALF_UP is the boring, widely-expected commercial rounding; this is a convention, not a
    tax/jurisdiction ruling — if a jurisdiction later mandates banker's rounding or per-line vs
    per-document rounding, that is a requirement change with its own ADR.
  - **Intermediate calculations:** preserve full precision; do **not** round between steps. Round once,
    at the boundary. Storing at scale 4 gives intermediate headroom; in-memory math may use higher
    `MathContext` and round only when producing a settled/displayed/persisted figure.

### D-3 — Currency identified by ISO 4217 alpha-3 code; the CODE is the canonical key

A currency is identified everywhere by its **ISO 4217 alpha-3 code** stored as `CHAR(3)` (e.g. `TZS`,
`USD`, `KES`, `BHD`). The code is the canonical key that every `Money` references — entities,
balances, the base-currency config (D-4), and the foreign-currency fields (D-5) all carry the code,
not a numeric FK to a currency row.

A **currency master** (code, name, minor-unit decimals, symbol, `active`) will exist to validate codes,
drive rounding scale (D-2), and feed the UI — **but the master table itself is the analyst's /
build-time concern**, introduced by the first money-bearing module (see Consequences). This ADR fixes
only that the **code is the key**; `Money.currency` holds the code, never a master id. Referencing by
code (not FK) keeps `Money` an embeddable with no join and means an amount's currency is legible in the
raw row — at the documented cost of a soft (code-must-exist-in-master) rather than DB-FK integrity,
validated in the service layer against the master.

### D-4 — Each company has a configurable base (functional) currency — never a hard-coded literal

Each **company** has a **base currency** (its functional/reporting currency), stored as config on the
company (an ISO 4217 code column on `company`, defaulting to `TZS` at seed time). It is **config-driven,
never a hard-coded `"TZS"` literal in Java or SQL.** Reporting, aggregation, and any company-level
balance roll-up are expressed in the **base currency**.

- The default at deployment is TZS, but the value is read from the company row, not assumed. No query,
  no service, no migration outside the seed may compare against or default to a literal currency code.
- Base currency lives on `company` (not `organisation`): companies are the legal/reporting entity
  (per parties.md and IAM D-A), and a multi-company org may run different functional currencies.
- An ArchUnit / review check: a string literal matching a 3-letter currency code in a non-test, non-seed
  source file is a smell to flag (the only legitimate literal is the seed migration's default).

### D-5 — Foreign-currency transactions: record shape fixed now, rate **sourcing** deferred

When a transactional amount is in a currency **other than** the company's base currency, the document
stores **all three** of:

1. the **document-currency** amount + code (what the customer/supplier sees — the `Money` of D-1);
2. the **base-currency equivalent** amount (`<field>_base_amount` NUMERIC(19,4) + the company base code);
3. the **exchange rate used** (`<field>_rate` NUMERIC(19,8)) plus enough to interpret it — the rate's
   from/to currencies and a `rate_at` timestamp.

These are captured **at transaction time** and are **IMMUTABLE thereafter**: a posted historical amount
is **never** recomputed when rates later move. The base-currency equivalent on a 2024 invoice is the
rate that applied in 2024, frozen — consistent with the append-only posting discipline
(PROJECT-CONVENTIONS §3.6).

What is **fixed now**: the **recording shape** above — the columns exist on money-bearing documents so
that adding the engine later is **non-breaking** (additive behaviour, not a schema migration of live
posting tables). What is **deferred** (D-8): *where the rate comes from* — live feed, manual entry,
or a `currency_rate` table — and the conversion service that populates (2) and (3). Until the engine
exists, the practical reality is single-currency (document currency = base currency, rate = 1,
base amount = document amount), but the **shape is already multi-currency**, so the first foreign-currency
transaction needs no table change.

> Pattern for engineers: a money-bearing document that can be foreign-currency embeds a `Money`
> (document amount/currency) **and** declares the base-equivalent triple (base amount, rate, rate_at).
> A future `ForeignMoney`/`ConvertedMoney` embeddable may package the triple; the columns are the
> contract regardless of the wrapper.

### D-6 — Arithmetic & aggregation: mixed-currency operations are an error, not a silent cast

The `Money` value object makes **mixed-currency arithmetic illegal at the type/runtime level**:

- `Money.plus`, `minus`, `compareTo` **throw** (a `CurrencyMismatchException`) when operands carry
  different currency codes. There is no silent coercion, no implicit conversion. Adding USD to TZS is a
  bug the type surfaces, never a number it computes.
- Crossing currencies requires an **explicit** conversion through the (deferred, D-8) conversion service
  — never an arithmetic operator.
- **Per-currency balances are distinct.** A party's USD balance and TZS balance are **separate
  quantities**; they are never summed into one number without an explicit, rate-stamped conversion. A
  customer "balance" is therefore a set of per-currency balances, not a scalar. Any "total balance" is a
  derived, base-currency figure produced by conversion (engine, deferred), clearly labelled as such —
  not a raw SUM across currencies.
- `scale`/`compareTo` comparisons use `BigDecimal.compareTo` (value equality), not `equals` (which is
  scale-sensitive); `Money.equals` compares amount-by-value **and** currency.

### D-7 — Wire & web contract: money is an object `{amount, currency}`, amount as a **string**

Money crosses the `ApiResponse<T>` envelope (PROJECT-CONVENTIONS §3.1) as a **structured object**,
never a bare number:

```jsonc
"unitPrice": { "amount": "1500.0000", "currency": "TZS" }
// optionally a server-formatted display string for convenience:
"unitPrice": { "amount": "1500.0000", "currency": "TZS", "display": "TZS 1,500.00" }
```

- **`amount` is serialised as a JSON string**, not a number — recommended and decided. Same rationale as
  ADR-0001 D-G's Long-as-string rule: JSON numbers are IEEE-754 doubles in JavaScript, so a large or
  high-precision decimal can lose precision in transit/parse. A string is exact end-to-end; the Angular
  side parses it deliberately (and should use a decimal library, not `parseFloat`, for any client math).
- **`currency`** is the ISO 4217 code string (D-3).
- **`display`** (optional) is a server-rendered, locale/minor-unit-correct string for read views, so the
  UI need not re-implement rounding/formatting. It is presentational only — never the field a client
  computes from.
- **Foreign-currency documents** expose the base-equivalent triple alongside (e.g.
  `{ amount, currency, baseAmount, baseCurrency, rate, rateAt }`) once D-5/D-8 are live; the document
  `Money` object is the always-present part.
- **Angular type:** `interface Money { amount: string; currency: string; display?: string; }`. The web
  treats `amount` as a string (consistent with every id field already being `string` per
  PROJECT-CONVENTIONS §3.3). Request DTOs accept money the same way; the server parses to `BigDecimal`.

### D-8 — Explicitly deferred (reserved by the design, built later under their own ADR)

Out of build scope now, but the shape above keeps each additive (non-breaking) when introduced:

1. **Currency master table** population/management (codes, decimals, symbols, active) — introduced with
   the first money-bearing module (D-3, Consequences).
2. **FX rate sourcing** — live feed, manual entry, and/or a `currency_rate` table (D-5).
3. **Conversion service** — turning a document-currency `Money` into a base-currency equivalent at a
   stamped rate (populates D-5 fields).
4. **Revaluation** of open foreign-currency balances at period close.
5. **Realized / unrealized gain-loss** posting.
6. **Cross-currency settlement** — settling an invoice in a currency different from the one billed.

Each of (2)–(6) is a distinct future ADR when the business specifies it. None changes the column shape
fixed by D-1/D-5; they add behaviour and (for rates) one reference table.

## Consequences

**Easier / safer:**
- Every module is **currency-safe from its first money column** — no day-N migration across Sales,
  Purchases, Stock, their DTOs, reports, and Angular. The retrofit the owner warned against cannot
  happen, because there is never a bare-number money column to retrofit.
- **No hard-coded TZS** anywhere — base currency is config on `company`; a second company in another
  functional currency, or a future multi-currency rollout, is a config + engine addition, not a rewrite.
- **Precision is structural** — `BigDecimal`/`NUMERIC` end to end, string on the wire; a whole class of
  float-rounding and JS-number defects is designed out.
- **Mixed-currency bugs surface at the type** (D-6) instead of producing a plausible-but-wrong number.
- One `Money` embeddable + one wire shape means every engineer applies money **identically**; reviews
  check one pattern, not per-module inventions.

**Costs / to watch:**
- **Every money field is now a column pair** (`*_amount` + `*_currency`), and foreign-currency-capable
  documents carry the base triple (D-5) — more columns, slightly wider rows. This is the deliberate
  price of currency-safety; the columns are small and the alternative (retrofit) is far costlier.
- **Discipline + tooling:** the `Money` embeddable, the arithmetic guard, and the ArchUnit rules
  (no bare `*_amount`, no float money, no currency literals) must land *with* the building block so the
  rule is enforced, not merely documented.
- **Currency is a soft (service-validated) reference, not a DB FK** (D-3) — the trade for keeping `Money`
  a join-free embeddable; the currency master must validate codes on write.
- **A "total balance" is no longer a scalar** (D-6) — per-currency balances and an explicit converted
  roll-up. Reports and UI must respect this; it is correct, but it is more than `SUM(amount)`.

**Migration / build implication (the clean-landing point):**
- This ADR **predates** the first money-bearing module on purpose. The **first money-bearing module**
  (Parties balances → Sales/Purchases/Stock) is what physically introduces the `Money` embeddable in
  `platform.common`, the **currency master** table + seed (with TZS and the in-scope codes, `active`),
  and the `company.base_currency` column/seed. Because this standard lands first, those tables and the
  `Money` type arrive **already shaped right** — no corrective migration, no edit of a posting table
  after data exists.
- **DATA-MODEL.md amendment:** the `Conventions` line "Money/quantity `NUMERIC(18,4)`" is refined —
  **monetary amounts `NUMERIC(19,4)`**, **exchange rates `NUMERIC(19,8)`**, money always as the
  `*_amount`/`*_currency` pair; quantities keep their own (non-currency) numeric convention. I will
  update DATA-MODEL.md when the first money-bearing module's tables are specified.

## Alternatives considered

- **Bare-number amounts with an implicit company currency** (a single `*_amount` column, currency assumed
  = company currency). Simplest schema, fewest columns. **Rejected:** this *is* the "tie up" the owner
  explicitly warned against — it hard-wires TZS, and adding currency later is the cross-cutting migration
  (every table, DTO, report, and the web) we are spending this ADR to avoid. Reversibility is the worst
  of all options once live data exists.

- **Store amounts as integer/long minor units** (e.g. `1500` cents in a `BIGINT`, currency code beside
  it) instead of `BigDecimal`/`NUMERIC`. Exact, no decimal class, compact. **Rejected (judgment call —
  see below):** minor-unit storage couples the column's meaning to the currency's decimals (a `1500`
  means 15.00 in USD, 1.500 in BHD, 1500 in JPY) — the *same* column is interpreted differently per row,
  which is brittle for reports, mixed-currency tables, and unit prices that need sub-minor-unit precision
  (fractional cents). `NUMERIC(19,4)` is self-describing, supports sub-minor-unit unit prices, and is the
  boring, well-understood Postgres choice (PROJECT-CONVENTIONS §1). We pick **BigDecimal + NUMERIC**.

- **Build the full FX engine now** (currency master + rate table/feed + conversion + revaluation +
  gain/loss). Most capable. **Rejected:** the business has not specified it, it is heavy and speculative,
  and it is unnecessary for single-currency day-one operation. The owner's instruction is explicit:
  *design-ready, build later.* D-5 reserves the recording shape so the engine is a **non-breaking
  addition** under its own ADR (D-8) when the business asks for it.

- **A `Money` entity in its own table (FK per amount)** instead of an embeddable. Centralises money, but
  money has no independent lifecycle and a join per amount is gratuitous cost on every document line.
  **Rejected:** the embeddable gives the column pair, value semantics, and the arithmetic guard with no
  join — the boring JPA-normal pattern.
