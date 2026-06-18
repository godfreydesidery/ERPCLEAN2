# 0039 — Currency as a `CurrencyCode` value type (not a `String`, not the `Currency` entity, not a FK): a thin platform value object persisted to the existing `CHAR(3)` column via a JPA `AttributeConverter`, folded into `Money`, with the **standalone-value-vs-document rule** governing where `Money` is used — plus per-company / per-branch **enabled-currency allow-lists** (`company_currency` / `branch_currency`), a **default document currency** distinct from the ledger base, and **filtered (not free-typed) currency selection**

- **Status:** Accepted (design ratified by owner 2026-06-17; build pending)
- **Date:** 2026-06-17
- **Deciders:** owner + solutions-architect. Follows [ADR-0005 Money & Currency](0005-money-and-currency.md) (which reserved a currency master + typed currency by name, D-3/D-8) and [ADR-0036 FX / Multi-currency](0036-fx-multicurrency.md) (which shipped the `currencies` master + `currency_rates` but, by design, kept every `currency` column a raw `String` soft-FK). This ADR upgrades that raw `String` to a typed value object **without** reintroducing the cross-module coupling ADR-0036 deliberately avoided.
- **Context source (verified against shipped code, 2026-06-17):**
  - **No `@ManyToOne Currency` / `currency_id` anywhere** — every entity stores the 3-char ISO-4217 code as a bare column: **56 `private String currency;`** fields across the entity layer (plus `from_currency`/`to_currency` on `CurrencyRate`, `base_currency` on `Company`). Currency is a **denormalised soft (logical) FK to `currencies.code`**, validated in the service layer, never by a DB FK or a JPA relationship.
  - **`Money` @Embeddable** (`platform.common.money.Money`): `amount NUMERIC(19,4)` + `currency String CHAR(3)`. Used by only a few fields (`Customer.creditLimit`, `Product.cost`, `ProductPrice.price`); the other ~56 monetary fields use a loose `BigDecimal amount` + `String currency` pair (the both-null/both-set invariant `Money` enforces is therefore not applied to them).
  - **No `AttributeConverter` / `@Convert` precedent** in the codebase — this introduces a new (standard JPA) pattern.
  - **Currency is read in JPQL and native SQL:** JPQL filters/projections — `SupplierBillRepository`/`ArInvoiceRepository` (`WHERE currency <> :baseCurrency`), `OpportunityRepository` (`SELECT … o.currency … GROUP BY o.currency`); native readers — `ProjectCostingQueryRepository`, `StockValuationQuery`, `VatReturnComputationReader`, plus a native `UPDATE … SET currency = :currency` patch in `ArInvoiceRepository`.
  - **DTOs expose `String currency`** widely (records, e.g. `SupplierBillDto`, `ApPaymentDto`, ageing/balance DTOs); the Angular client expects the wire value `"TZS"`.
  - `Company.baseCurrency` (`companies.base_currency` VARCHAR(3) DEFAULT `'TZS'`) is the functional-currency anchor (ADR-0005 D-4).
  - **No currency enablement / restriction exists today:** there is the global `currencies` catalog (with an `active` flag = "is this a recognised ISO currency at all") and `Company.baseCurrency`, but **no per-company or per-branch allow-list** of which currencies a tenant may transact in, and **no default *document* currency** distinct from base. Document currency is taken raw from the request (`SalesInvoiceServiceImpl.create` takes `req.currency()` unvalidated; AR/AP force base via `.orElse("TZS")` — ADR-0036 D-9).
  - Currency-length drift: most columns are `CHAR(3)`/`VARCHAR(3)`; `StockTransferLine`/`StockCountLine` use `length=10` ([ENTITY-ATTRIBUTE-GAP-REVIEW.md](../data-model/ENTITY-ATTRIBUTE-GAP-REVIEW.md) finding **X21**).

This ADR is the **type-safety decision** for currency handling. It writes no production code; the engineers implement the value object, the converter, and the call-site changes next.

## Context

Currency is handled as a raw `String` everywhere. That is storage-correct (denormalised code, no cross-module FK — the modular-monolith stance) but type-unsafe at the Java level: a method taking `(BigDecimal amount, String currency)` lets a caller transpose arguments, pass a malformed/`lowercase` code, or pass any unrelated string; normalization (uppercase/trim) and format validation are re-done ad hoc or skipped. The owner wants currency to be a **type**, expressed as `amount: BigDecimal, currency: <type>` on value-bearing rows (e.g. price lists).

The forces:

- **Keep the storage exactly as-is.** No `currency_id` FK, no `@ManyToOne` to the `Currency` entity — that would re-couple every module to `fx`, add a join/N+1, and contradict the shipped soft-FK design. The column stays the 3-char code.
- **Naming collision is a real trap.** The obvious name `Currency` is already the JPA **entity**. A field typed `currency: Currency` reads as "embed the entity" → the very relationship we reject. The value type therefore needs a distinct name.
- **`Money` already exists for the amount+currency pair** but is under-used and its `currency` is a `String`. The decision must converge on `Money`, not introduce a competing pattern.
- **Not every amount deserves its own embedded currency.** Transactional documents (invoices, orders) carry many amounts (net/vat/gross, totals) in *one* currency. Embedding `Money` per amount would duplicate the currency column N× per row and make per-field currency drift *structurally expressible* — a class of bug the current single-`currency`-column design cannot represent.
- **Type safety is not referential integrity.** A value object validates *format*; it cannot guarantee the code *exists and is active* in `currencies` without a DB read. Existence validation stays where it is (service layer), optionally hardened with a DB `CHECK`.
- **Boundaries must stay stable.** The JSON wire contract (`"TZS"`), native-SQL read paths, and JSONB payloads must not change behaviour.

## Decision

### D-1 — A thin `CurrencyCode` value object in `platform.common.money`, persisted via an auto-applied `AttributeConverter` to the existing `CHAR(3)` column — **not** the `Currency` entity, **not** a FK

Introduce a value type (a `record`, immutable) wrapping the validated 3-char code, beside `Money`:

```
com.erp.platform.common.money
├── CurrencyCode (record: String value)
│     • factory normalises: trim + upper-case; validates format = exactly 3 ASCII letters (ISO-4217 alpha-3 shape)
│     • NOT validated for existence/active here (that needs the currencies master — D-5)
│     • @JsonValue/@JsonCreator → serialises to/from the plain string "TZS" (wire unchanged)
│     • equals/hashCode by the normalised code
└── CurrencyCodeConverter implements AttributeConverter<CurrencyCode,String> (@Converter(autoApply = true))
      • toDatabaseColumn → code.value() ; null → null
      • toEntityAttribute → CurrencyCode.of(db) ; null → null
```

- **`autoApply = true`** so every `CurrencyCode`-typed field maps to its `CHAR(3)` column with no per-field `@Convert`.
- **No schema change** for the conversion itself — the column stays the 3-char code. (A separate, optional migration handles the `length=10` drift and the existence `CHECK` — D-5.)
- The **`Currency` entity stays the reference master** (`currencies` table). `CurrencyCode` is the in-memory type; `Currency` is the row that defines which codes are valid. They are deliberately different classes with different names so no one can accidentally turn the field into a `@ManyToOne`.

> **Naming rule (load-bearing):** the field reads `currency: CurrencyCode`, **never** `currency: Currency`. `Currency` = the master entity; `CurrencyCode` = the value carried on documents/money.

### D-2 — Fold currency into `Money`; `Money.currency` becomes `CurrencyCode`

`Money` is the canonical carrier of a standalone monetary value. Upgrade it:

```
Money @Embeddable { BigDecimal amount;  CurrencyCode currency; }   // was: String currency
```

The both-null/both-set invariant (ADR-0005 D-1) is retained. `MoneyDto` keeps `amount` as STRING and `currency` as the plain string on the wire (D-6). All fields that are a genuinely **standalone** monetary value migrate from a loose `(BigDecimal, String)` pair to `Money` — closing the "Money used inconsistently" gap (e.g. `PriceTier.unitPrice`, `Product.cost` already `Money`, `Customer.creditLimit` already `Money`).

### D-3 — The **standalone-value-vs-document rule** — do NOT explode multi-amount documents into many `Money`

This is the decision that prevents over-correction.

| Shape | Model | Rationale |
|---|---|---|
| A **standalone** monetary value (a price, a unit cost, a credit limit) | `Money` (`amount` + `CurrencyCode`) | One value, one invariant. |
| A row/document with **multiple amounts sharing one currency** (invoice `net/vat/gross`, order totals, payment/allocation amounts) | **one** row-level `currency: CurrencyCode` + plain `BigDecimal` amounts | Avoids N× duplicated currency columns and makes per-field currency drift *impossible to express* (it's one column for the whole row). |

So the ~50 transactional headers/lines do **not** become bags of `Money`; they keep their single `currency` column — its **type** changes from `String` to `CurrencyCode` (via the auto-applied converter), nothing else. Only standalone-value fields use `Money`.

### D-4 — `CurrencyCode` stays thin: format/normalisation only; `minor_units`/rounding live with `Money` + the `Currency` master; it is **not** an enum

- `CurrencyCode` validates **format** (3 letters) and **normalises** (upper/trim). It does **not** carry `minor_units`, symbol, or name — those live on the `Currency` master and require a DB read.
- Rounding (HALF_UP to minor units, ADR-0005 D-2) stays in `Money` arithmetic / the FX conversion service (ADR-0036 D-1), which looks up `minor_units` from the `Currency` master when needed. Do not push that data into the value object.
- **Not a Java `enum`:** the currency set is tenant-extendable reference data (`currencies` is a table, codes are added at runtime), so a compiled enum is wrong; a value object over the open code set is correct.

### D-5 — Existence validation stays service-layer; optionally hardened with a DB `CHECK`; fix the `length=10` drift

- **Type ≠ referential integrity.** `CurrencyCode` guarantees *shape*, not that the code exists/active in `currencies`. Existence/active validation stays in the service layer (the same place ADR-0036 validates rates), unchanged by this ADR.
- **Optional DB hardening (recommended, separate migration):** a `CHECK`/soft-FK `currency IN (SELECT code FROM currencies)` is *not* a `currency_id` FK — it enforces the code is real without an id relationship. (A true FK on `code` is also possible but reintroduces cross-table coupling; a trigger/`CHECK` or service validation is preferred per the modular-monolith stance.)
- **Fix `length=10` → `3`** on `StockTransferLine`/`StockCountLine` (X21) in the same migration; the converter also naturally rejects over-length values.
- **Superseded by D-7/D-8:** plain "exists in `currencies`" is too weak — the real rule is "**enabled for this company/branch**." See D-7 (the allow-lists) and D-8 (the enablement check + filtered selection), which replace the simple `CHECK ∈ currencies.code` with an enablement check.

### D-6 — Boundary stability: JSON wire, native SQL, JSONB payloads

- **JSON / API contract unchanged.** DTOs may keep `String currency` (convert entity→DTO via `code.value()` in the mapper — lowest blast radius) **or** adopt `CurrencyCode` with `@JsonValue`/`@JsonCreator` so it still serialises as `"TZS"`. Either way the wire format does not change.
- **Native SQL is the explicit edge:** `AttributeConverter` does **not** apply to native queries. `ProjectCostingQueryRepository`, `StockValuationQuery`, `VatReturnComputationReader`, and the native `UPDATE` in `ArInvoiceRepository` continue to read/write the raw `String`; wrap to `CurrencyCode` in the row-mapper where it surfaces into typed code.
- **JPQL is the contained ripple:** comparisons/`GROUP BY` (`WHERE currency <> :baseCurrency`, `GROUP BY o.currency`) work with the converter, binding `:baseCurrency` as a `CurrencyCode`. **JPQL constructor-expression DTOs** that pass `currency` (e.g. `OpportunityRepository`) read it back as `CurrencyCode`, so the DTO component must be `CurrencyCode` or the value is converted in the mapper.
- **JSONB payloads stay strings:** currency inside `DomainEvent.payload`, `taxSummary`, `sourceParams` is serialized JSON text, not a mapped column — it remains a plain string; the converter does not touch it.

### D-7 — Enabled-currency allow-lists + a default *document* currency: `company_currency` (+ optional `branch_currency`)

Currency usage is **restricted** to a tenant-curated allow-list, and the *default document* currency is **separate from the ledger base** (it defaults to base but may differ, and a branch may override it). Two new tenant-scoped tables (junction-style, singular per [[db-naming-convention]]; `UidEntity`, `version`, audit).

#### `company_currency` (the company allow-list — required)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_company_currency_uid` |
| `company_id` | BIGINT | NO | `fk_company_currency_company` → `companies(id)`; tenant |
| `currency_code` | CHAR(3) | NO | soft-FK → `currencies.code` (must be globally `active`) |
| `is_default` | BOOLEAN | NO | DEFAULT false; the **default document currency**. Partial-unique `uq_company_currency_default (company_id) WHERE is_default` — exactly one per company |
| `active` | BOOLEAN | NO | DEFAULT true; per-company enablement toggle |
| `version` + audit | | | |

- `uq_company_currency (company_id, currency_code)`.
- **Invariants (service-enforced):** the company's `base_currency` MUST exist here, be `active`, and **cannot be deactivated**; `is_default` row must be `active`; a code can only be enabled if it is globally `currencies.active`.

#### `branch_currency` (optional per-branch subset + branch default)

| column | type | null | notes |
|---|---|---|---|
| `id`/`uid` | | NO | `uq_branch_currency_uid` |
| `company_id` | BIGINT | NO | tenant |
| `branch_id` | BIGINT | NO | `fk_branch_currency_branch` → `branches(id)` |
| `currency_code` | CHAR(3) | NO | **must be enabled & active in `company_currency`** for the same company (subset rule, service-validated) |
| `is_default` | BOOLEAN | NO | DEFAULT false; partial-unique `uq_branch_currency_default (branch_id) WHERE is_default` |

- `uq_branch_currency (branch_id, currency_code)`.
- **Inheritance:** a branch with **no** `branch_currency` rows inherits the **full company allow-list** and the **company default**. Rows present ⇒ the branch is restricted to that subset.
- **Default resolution order:** branch default (if any) → company default → company base.

> **Base vs default (the owner's decision):** `Company.baseCurrency` stays the immutable *ledger/functional* currency (ADR-0036). The new `is_default` flag is the *document* default (pre-selected on new documents). They default to the same value but are independent fields with independent rules.

### D-8 — Enablement validation + "filtered, not typed" selection

- **Validation (supersedes D-5's existence check):** a document's `currency` must be **enabled** for its company — and, when the branch has its own `branch_currency` list, enabled for the branch. Enforced in the **service layer** (the same gate that today reads base). This replaces the weaker "exists in `currencies`."
- **Optional DB hardening — composite FK, still no `currency_id`:** company-scoped currency-bearing tables already carry `(company_id, currency)`; a **composite FK `(company_id, currency)` → `company_currency(company_id, currency_code)`** gives *real referential enablement integrity* without an id relationship (the currency stays the code). Branch-subset enforcement stays service-layer (`branch_id` is nullable on many rows). This is the recommended hardening once the allow-lists are populated.
- **"Filtered, not typed" (frontend):** the currency control is a **filtered picker** over the enabled set for the active company/branch, with the default **pre-selected** — free-text entry is disabled. A `GET /currencies/enabled?companyUid=…&branchUid=…` endpoint returns the allowed list. This is **orthogonal to the `CurrencyCode` storage type**: currency is still *stored* as `CurrencyCode`; the picker just constrains the *choices*. The value object gives the type; the allow-list gives the options.
- **Default wiring:** new documents pre-fill `currency` from the resolved default (D-7), not from a hard-coded base — this is where the ADR-0036 D-9 `getBaseCurrency()` default is replaced by the company/branch *default document* currency.

### D-9 — Bootstrap defaults: global catalog seed + `BootstrapProperties`-driven company policy (base=TZS, default=TZS, enabled=Classic EAC-6 + USD + EUR)

Separate **what currencies exist** (catalog) from **what a new company starts with** (policy).

**Global `currencies` catalog (seeded `active=true`) — the recognised set:**

| code | currency | `minor_units` |
|---|---|---|
| TZS | Tanzanian Shilling | 0 *(deliberate ISO divergence — whole-shilling rounding; OQ-CCY-07 resolved)* |
| KES | Kenyan Shilling | 2 |
| UGX | Ugandan Shilling | 0 |
| RWF | Rwandan Franc | 0 |
| BIF | Burundian Franc | 0 |
| SSP | South Sudanese Pound | 2 |
| USD / EUR | — | 2 |
| GBP | Pound Sterling | 2 *(retained from V77 catalog; not enabled by default)* |

**Bootstrap company policy — driven by `BootstrapProperties`, shipped defaults (overridable per deployment):**
- **base currency** → `TZS` (`Company.baseCurrency`, the immutable ledger currency).
- **default document currency** → `TZS` (the `company_currency.is_default` row).
- **enabled set** → **Classic EAC-6 + USD + EUR** = `{TZS, KES, UGX, RWF, BIF, SSP, USD, EUR}` (`company_currency` rows, `active=true`; `TZS` is `is_default`). No `branch_currency` rows (branches inherit).
- **No FX rates seeded** (a rate is required only at the first foreign document — D-7, ADR-0036).
- Suggested keys: `bootstrap.currency.base`, `bootstrap.currency.default`, `bootstrap.currency.enabled` (CSV). A non-TZ deployment overrides these without touching a seed.

**Post-bootstrap mutability (the "change after bootstrap" rule, made precise):**
- **Enabled set + default document currency** — freely editable anytime (`CURRENCY.MANAGE`).
- **Base currency** — editable **only while the company has no posted GL transactions**; **blocked** thereafter (the ledger is kept in base; changing it post-data would require revaluing every entry). On a fresh bootstrap both are TZS, so an immediate base change is allowed.

### D-10 — Where the seed lives (dev-phase: edit existing migrations in place)

Per [[dev-phase-migrations-editable]] (DB ephemeral, development): fold these into existing migrations rather than appending additive `Vnn`, and drop the ADD-nullable→backfill ceremony.
- **Catalog rows** (`UGX`, `RWF`, `BIF`, `SSP`) → add to the existing V77 `currencies` seed (`TZS/KES/USD/EUR/GBP` already there).
- **`company_currency` / `branch_currency` tables** → create in the FX migration set (or a clean migration); no back-fill ceremony.
- **Bootstrap company's** base + default + enabled set → seeded by the **bootstrap service at startup from `BootstrapProperties`** (Java), not a migration — because the bootstrap company itself is created there.

### D-11 — Excluded from the `CurrencyCode` flip: lookup keys & ledger primitives stay `String`

`CurrencyCode` applies to **document & master currencies** (the user-facing amount currency). It is deliberately **NOT** applied to currency fields that are **query/lookup keys or ledger primitives**, where the String soft-FK design is load-bearing (ADR-0036). These stay `String`:

| Field | Why it stays `String` |
|---|---|
| `JournalLine.currency`, `LineDraft` | The base-only ledger + the sacred `validateLine` Σ-gate (ADR-0036); a foreign line never reaches the ledger. |
| `Company.baseCurrency` | Compared against the GL line currency in `validateLine`; flipping it ripples into the sacred gate. |
| `CurrencyRate.fromCurrency` / `toCurrency` | **Rate-lookup keys.** `FxRateService.rateOn(String, String)` → `CurrencyRateRepository.findEffective(... :fromCurrency, :toCurrency)` and the derived `…FromCurrencyAndToCurrency…` queries bind **String** params; the whole conversion engine (ADR-0036) is built on String keys. |

**Lesson learned (recorded so it isn't repeated):** with a JPA `AttributeConverter`, a JPQL/derived-query parameter must be bound as the **attribute type**, not the column type. Flipping an entity's `currency` to `CurrencyCode` silently breaks any repository query that still passes a `String` for that attribute — Hibernate throws `QueryArgumentException: Argument [USD] of type String did not match parameter type CurrencyCode` at **runtime** (compiles fine). Two consequences of keeping document currencies as `CurrencyCode`:
- Repo queries that filter a **kept-`CurrencyCode`** attribute against a base value (`ArInvoiceRepository`/`SupplierBillRepository.findOpenForeignForRevaluation`, `WHERE currency <> :baseCurrency`) take a **`CurrencyCode`** param; callers pass `CurrencyCode.of(baseCurrency)`.
- `equals()` comparisons must be like-for-like: `CurrencyCode.equals(String)` compiles but is always `false` — compare via `.value()` or `CurrencyCode.of()`.

## Migration

> **Dev-phase note ([[dev-phase-migrations-editable]]):** the DB is ephemeral, so the items below are **edited into existing migrations in place** (see D-10) rather than appended as new additive `Vnn` files; the ADD-nullable→backfill→SET-NOT-NULL ceremony is dropped. The "additive" framing below is the *production* shape, retained for when production-hardening re-imposes it.

The type change itself is **migration-free** (same `CHAR(3)` columns). The schema work alongside:

**Enablement (D-7/D-8) — `Vxx__currency_enablement.sql`:**
1. CREATE `company_currency` (partial-unique default, `uq_company_currency`, fk to `companies`) + `branch_currency` (partial-unique default, `uq_branch_currency`, fk to `branches`).
2. **SEED day-1 behaviour:** for each existing company, insert one `company_currency` row = its `base_currency`, `active=true`, `is_default=true` (`#12`-safe uid e.g. `'CCY' || lpad(company_id::text,6,'0') || base_currency`). No `branch_currency` rows seeded (branches inherit). Result: every company has exactly its base enabled & default — behaviour unchanged.
3. Permissions: reuse `CURRENCY.MANAGE` (ADR-0036) for managing the allow-lists, or add `CURRENCY.ENABLE` if finer control is wanted.
4. (Optional, phase 2) add the composite FK `(company_id, currency) → company_currency(company_id, currency_code)` on company-scoped currency-bearing tables.

**Type/length (D-1/D-5):** still migration-free for the converter; one small additive migration is recommended alongside:
1. `ALTER` `stock_transfer_lines.currency` / `stock_count_lines.currency` from `length=10` → `VARCHAR(3)`/`CHAR(3)` (X21).
2. (optional) add a `CHECK`/validation that `currency` ∈ `currencies.code` on the currency-bearing tables (D-5), if DB-level existence enforcement is wanted.

## Consequences

**Positive**
- Compile-time safety: amount/currency can't be transposed; malformed codes are rejected at one chokepoint; uppercase/trim normalisation is centralised.
- Storage and architecture unchanged: no `currency_id`, no `@ManyToOne`, no cross-module coupling, no join/N+1 — the shipped soft-FK design is preserved.
- `Money` converges into the canonical carrier; the loose `(BigDecimal, String)` standalone pairs are eliminated; the `length=10` drift is fixed.
- The wire contract and native read paths are untouched; rollout is incremental (auto-apply converter + field-type swaps, module by module).

**Negative / costs**
- A new (standard) JPA pattern (`AttributeConverter`) enters the codebase — worth an ArchUnit note so currency fields are `CurrencyCode`, not `String`, going forward.
- JPQL constructor-expression DTOs and native readers each need a touch (small, enumerated in D-6).
- Still no referential integrity unless the optional DB `CHECK` is added — the type does not provide it.

**Neutral / deferred**
- Full `Money`-ification of every monetary field is **not** pursued (D-3 explicitly keeps document amounts as bare `BigDecimal` under one row-level `CurrencyCode`).
- A true DB FK on `currencies.code` is left as an option, not adopted (cross-table coupling trade-off).

## Alternatives considered

- **Field typed as the `Currency` entity (`@ManyToOne`) — REJECTED.** Reintroduces a join/FK and cross-module coupling to `fx`, the exact thing ADR-0036 avoided; N+1 risk; no benefit over the code.
- **Keep raw `String` (status quo) — REJECTED.** Storage-correct but type-unsafe; the owner's explicit ask is a type.
- **Java `enum` for currency — REJECTED.** Currency is tenant-extendable reference data; an enum can't represent runtime-added codes.
- **`Money` for every monetary field — REJECTED (D-3).** Duplicates the currency column N× per document and makes per-field currency drift expressible; reserved for standalone values.
- **True DB FK on `currencies.code` — NOT ADOPTED (left optional).** A `CHECK`/service validation gives existence enforcement without an id relationship, consistent with the soft-FK convention; a hard FK is available if DB-level enforcement is later mandated. (Superseded in practice by the composite-FK-to-`company_currency` option in D-8, which enforces *enablement*, not just existence.)
- **Global `currencies.active` only, no per-company allow-list — REJECTED.** The global flag means "is this a real ISO currency," not "may *this company* use it." Without `company_currency` there is no way to restrict a tenant to its actual operating currencies (the owner's explicit requirement).
- **Default document currency = base, single concept — REJECTED (owner decision).** Conflating them prevents a branch (or company) from defaulting to a transaction currency other than the ledger base; kept as two independent fields (D-7).
- **Company-level enablement only (no `branch_currency`) — CONSIDERED, not chosen.** Simpler, but the owner wants true per-branch restriction; `branch_currency` is an optional subset with inheritance so the simple case (no branch rows) costs nothing.

## Open Questions

- **OQ-CCY-01 — DTO strategy:** keep DTOs `String currency` (convert in mapper) or adopt `CurrencyCode` end-to-end with `@JsonValue`/`@JsonCreator`? **Default: keep DTOs `String`** (lowest blast radius); revisit if we want the type at the API edge too.
- **OQ-CCY-02 — DB existence `CHECK`:** add the `currency ∈ currencies.code` constraint now, or rely on service validation? **Default: add it** in the same migration that fixes `length=10` (cheap, real integrity).
- **OQ-CCY-03 — rollout granularity:** big-bang the auto-apply converter (all 56 fields flip type at once) or module-by-module? **Default: converter + `Money`/`CurrencyCode` core first, then migrate fields module-by-module** behind the green test gate.
- **OQ-CCY-04 — DB enforcement of enablement:** add the composite FK `(company_id, currency) → company_currency` now, or rely on service validation first? **Default: service validation first, composite FK as a phase-2 hardening** once allow-lists are populated and back-filled.
- **OQ-CCY-05 — disabling a currency in use:** block or warn when deactivating a `company_currency` that still has open balances/transactions? **Default: block** (consistent with master soft-delete rules); base can never be disabled.
- **OQ-CCY-06 — branch-list management UX:** is per-branch restriction edited per branch, or bulk-assigned? **Default: per-branch grid** (subset of the company list), branches with no rows inheriting the company set.
- **OQ-CCY-07 — TZS `minor_units`: RESOLVED → keep `0`.** `minor_units` is this system's *rounding scale*, and TZS is the base/ledger currency; `2` would create un-settleable fractional shillings against TRA/bank (which use whole shillings). A deliberate, documented divergence from ISO 4217 (which nominally says `2`; the senti is defunct). The other currencies have no conflict (UGX/RWF/BIF = `0` in ISO too; KES/SSP/USD/EUR = `2`). If ISO-literal output is ever needed for an integration, split a separate informational `iso_minor_units` from the operational `rounding_scale` — not now.
- **OQ-CCY-08 — base-change guard: RESOLVED → service-side, lock once any GL posting exists.** Matches the ERP standard (NetSuite/Xero/QuickBooks/SAP all treat base as set-once, immutable after transactions). Enforce in the company-update service: allow a `baseCurrency` change only when no `journal_entries` row exists for the company (the GL is the universal sink), gated behind an admin permission and audit-logged; no DB trigger. Base stays in the enabled set and can't be disabled (D-7).
