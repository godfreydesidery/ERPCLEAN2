# 0036 — FX / Multi-currency: a per-company effective-dated `currency_rates` master + a platform `CurrencyConversionService`, **convert-at-the-poster-boundary so the ledger stays base-only** (the sacred Σbase invariant and `GLPostingServiceImpl` are byte-untouched), the ADR-0005 D-5 base triple on the four source-document headers (NOT on `journal_lines`), realized FX as a **balancing plug leg** inline in the existing AR-receipt / AP-payment settlement builders, an unrealized period-end **FX revaluation run** cloned from `DepreciationRun` + reverse-next-period via `postReversal`, four split gl_config keys (REALIZED/UNREALIZED × GAIN/LOSS), additive as `V77`–`V81`

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** solutions-architect (multi-currency requirements [docs/requirements/multicurrency.md](../requirements/multicurrency.md) — FR-CUR-01..13, BR-CUR-01..08; ADR-0005 [Money & Currency](0005-money-and-currency.md) D-1..D-9 reserved this engine by name in D-8). Synthesised from three competing FX proposals (A minimal-surface, B audit-purist dual-ledger, C operationally-rich) under three independent judge reviews; the judges unanimously ranked **Proposal A the base** (it is the only design that preserves the sacred Σbase invariant *by construction* rather than by reimplementation) and named the exact grafts taken below.
- **Context source:** the FX codebase recon [docs/decisions/_fx-recon/recon.md](_fx-recon/recon.md), verified against the **shipped** code (latest migration **V76**, next free **V77**; next free ADR **0036**):
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / V10): `GLPostingService.post(JournalEntryDraft)→JournalEntryDto` is the **ONLY** ledger write path (validates ≥2 lines, one-sided lines, active accounts, same company, OPEN period, **Σdebit==Σcredit via `reduce(ZERO,add)` + `compareTo`**, and **`validateLine` BR-GL-06: `if (!baseCurrency.equals(ld.currency())) throw`** — the single hard "base-currency-only" gate); `postReversal(originalEntryUid, reversalDate, sourceType, sourceRef, postedBy)` re-fetches the original lines, swaps debit↔credit, sets `reversalOfId`, and re-enters `post(...)` (balanced by construction); `resolveBaseCurrency(companyId)` = `Company.getBaseCurrency()` (the single base read in the posting path); `JournalEntryDraft(companyId, branchId, postingDate, description, sourceType, sourceRef, reversalOfId, postedBy, List<LineDraft>)` + `LineDraft(accountId, debitAmount, creditAmount, currency, lineMemo, …dimension/project tags)` with 5/9/12-arg convenience constructors; `JournalLine` (`journal_lines`, **append-only**, javadoc "All amounts in company base currency") carries `debit_amount`/`credit_amount` NUMERIC(19,4) + `currency` CHAR(3); `GLConfigResolver.resolve(companyId, GlConfigKey)→ChartOfAccount` (`@Transactional(MANDATORY)`, throws on missing/inactive — BR-GL-10); `GlConfigKey` enum + `chk_gl_config_key` DB CHECK (the real gate, widened additively V46/V56/V74 superset); `JournalSourceType` enum + `chk_journal_batch_source_type`/`chk_journal_entry_source_type`; `GLPostingSafeInvoker.postSaleInNewTx` (REQUIRES_NEW, null-on-anomaly — event-driven legs only); `FiscalPeriodResolver.resolveOpen(companyId, postingDate)` (the period gate, BR-GL-03); `JournalLineRepository.accountBalance`/`periodMovementByAccount` (the windowed-aggregate read primitives); `YearEndCloseServiceImpl` (windowed-aggregate-then-post-while-period-OPEN + `reopenFiscalYear` → `postReversal(closingJournalUid, …)` with `existsByReversalOfId` idempotency — the reverse-next-period mechanism); the `DepreciationRun` run-header/run-lines/preview/idempotency(`findByCompanyIdAndFiscalPeriodId`)/outbox/audit pattern ([ADR-0030](0030-fixed-assets.md) D-4).
  - **AR** ([ADR-0014](0014-accounts-receivable-data-model.md) / V11): `ArInvoice` (`ar_invoices` — `original_amount`, `outstanding_amount`, `currency` VARCHAR(3) updatable=false, `status` ∈ OPEN/PARTIAL/PAID/WRITTEN_OFF, `chk_ar_invoice_amounts`); `ArReceipt` (`ar_receipts` — `amount`, `unallocated_amount`, `currency` updatable=false); `ArReceiptAllocation` (`ar_receipt_allocations` — `allocated_amount`, no currency/rate, UNIQUE(receipt_id, ar_invoice_id)); `ArReceiptServiceImpl.recordAndAllocate` builds the settlement GL legs **inline** (allocation loop ~155-173 reduces each invoice outstanding; GL builder ~215-238: DR Cash (+DR WHT) / CR AR), currency **forced to base** via `companies.findById(companyId).map(Company::getBaseCurrency).orElse("TZS")` (~line 122); the `.orElse("TZS")` literal recurs in `ArAgeingQuery`, `ArBalanceServiceImpl`, `ArCreditNoteServiceImpl`, `ArOpeningBalanceServiceImpl`, `ArWriteOffServiceImpl`, `ArReconciliationQuery` (8 sites — an ADR-0005 D-4 "never hard-code TZS" violation, in scope to clean up).
  - **AP** ([ADR-0015](0015-accounts-payable-data-model.md) / V12): `SupplierBill` (`supplier_bills` — `net_amount`/`vat_amount`/`gross_amount`/`outstanding_amount`, `currency` VARCHAR(3) updatable=false, `status` ∈ DRAFT/MATCHED/HELD/APPROVED/PARTIALLY_PAID/PAID); `ApPayment` (`ap_payments` — `amount`, `currency` updatable=false); `ApPaymentAllocation` (`ap_payment_allocations` — `allocated_amount` updatable=false, no currency/rate, UNIQUE(ap_payment_id, supplier_bill_id)); `ApPaymentServiceImpl.postPaymentToGl` (~267-336: DR AP / CR Cash (+CR WHT)); `BillMatchServiceImpl.postMatchedBillToGl` (DR Purchases/GRNI [DR VAT_INPUT] / CR AP).
  - **Sales** ([ADR-0021](0021-sales-order-to-cash.md) / V13): `SalesInvoice` (`sales_invoices` — `currency` CHAR(3) NOT NULL, `net_total_amount`/`vat_total_amount`/`gross_total_amount`; "all monetary columns share this currency", BR-SALES-04); `SalesInvoiceServiceImpl.create` takes `currency` raw from `req.currency()` (no base reconciliation today); the sale posts via `GLPostingSafeInvoker.postSaleInNewTx` (DR AR / CR Revenue + CR VAT) and `ArSalePostedHandler` opens the AR invoice.
  - **Money** ([ADR-0005](0005-money-and-currency.md)): `Money` @Embeddable (`platform.common.money.Money`, `amount` NUMERIC(19,4) + `currency` CHAR(3)) — arithmetic (plus/minus/compareTo across currencies) and the conversion service are **explicitly deferred to this ADR** (D-6/D-8); `MoneyDto(amount, currency)` wire shape, `amount` as STRING (D-7), with `baseAmount`/`baseCurrency`/`rate`/`rateAt` **reserved** alongside; rates NUMERIC(19,8) scale-8 (D-5); rounding HALF_UP to minor units (D-2). `Company.baseCurrency` (`companies.base_currency` VARCHAR(3) DEFAULT 'TZS', V10) is per-company (D-4). **No `currencies` master, no `currency_rates`, no rate/base columns, no FX gl_config key, no FX source type exist anywhere today** (recon: full-codebase grep = zero hits).
  - [[db-naming-convention]] verified: plural masters/owned-children, singular junctions, singular constraint roots (`uq_`/`fk_`/`chk_`), plural `ix_`, `uid VARCHAR(26)` ULID, `company_id`/`branch_id` BIGINT scalar, audit cols, `@Version` (`version BIGINT NOT NULL DEFAULT 0`) on every new `UidEntity` table, the additive `DROP/ADD CONSTRAINT` full-union widen for the CHECKs. **ISSUES-REGISTER #12:** per-company CROSS-JOIN seed uids MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26), **never** `|| key`.

This ADR is the **technical data model + integration design** for FX / multi-currency v1 (FR-CUR-08..12, BR-CUR-05/06). It is concrete enough that the engineers write the migrations, the entities, the conversion service, the settlement-leg changes, the revaluation run, and the Angular screens **without guessing a business rule**. It writes **no production code, no entities, no migration SQL** — that is the engineers' next step.

## Context

The whole financial spine ships single-currency: every transactional document carries one `currency` column at an implied rate of 1, and the GL is hard-locked to base currency by `GLPostingServiceImpl.validateLine` (BR-GL-06). ADR-0005 reserved the multi-currency *shape* (the base triple, the `Money` embeddable, the wire contract) and deferred the *engine* by name (D-8: rate sourcing, conversion service, revaluation, realized/unrealized gain-loss, cross-currency settlement). The business now needs to invoice, bill, receive and pay in a foreign currency, with correct realized FX on settlement and unrealized FX on open balances at period-end. The forces:

- **THE SACRED INVARIANT is non-negotiable and load-bearing.** Every GL journal must balance and the ledger carries base currency (TZS-per-company): `Σ base-debits == Σ base-credits`, enforced **only** in `GLPostingServiceImpl.post` (no DB Σ constraint exists). 789 single-currency tests exercise that exact `reduce`/`compareTo` block. The decisive design question is **how a foreign-currency document posts to a base-currency ledger without touching that arithmetic.** Three structurally different answers were proposed: (A) convert in the posters, keep the ledger base-only, the FX gain/loss is a base-currency balancing leg; (B) a dual-amount ledger — redefine `journal_lines.debit_amount/credit_amount` as the base leg and add `txn_*` overlay columns, Σ still base-only; (C) add `base_*` columns to `journal_lines` and **move** the Σ reducer + relax `validateLine`. **A is the only one that leaves the reducer, `validateLine`, `JournalLine`, every `LineDraft` constructor, and the `postReversal` line-copy byte-for-byte unchanged.** The invariant is then satisfied *by construction*, not by the correctness of a back-fill. This is the decision the whole ADR pivots on. Resolved in **D-3 / D-4**.

- **ADR-0005 D-5 reserved the triple "on the document," and the recon confirms it.** D-5 says a foreign money field stores `<field>_base_amount` + `<field>_rate` + `rate_at`, **on the document**, captured at txn time and IMMUTABLE (BR-CUR-05). It never said "on every journal line." Proposal A honours D-5 literally — the triple lands on the four source-document headers that actually need it for realized/unrealized FX (`ar_invoices`, `supplier_bills`, `sales_invoices`, plus `ar_receipts`/`ap_payments` for the settlement rate) — and consciously **declines** to spray it across `journal_lines`, where the leg is already base and the triple would be dead weight. This is the SAP/NetSuite single-functional-currency-ledger posture: the ledger is base, the sub-ledger documents are self-describing. We **consciously confirm** (not silently drop) D-5: the literal "on money-bearing documents" reading is honoured; the dual-amount *ledger* (B/C) is rejected for v1 and recorded as a deferred, purely-additive future option. Resolved in **D-2 / Alternatives**.

- **Realized FX needs no ledger change at all.** It arises on AR-receipt / AP-payment settlement when the settlement rate differs from the original invoice/bill rate. The gain/loss is inherently a *base* figure, so it is a normal base-currency balancing leg in the **existing inline** AR/AP settlement builders, reusing the existing `AR_RECEIPT`/`AP_PAYMENT` source tokens — it works the moment the FX gl_config keys exist. v1 keeps BR-CUR-06 (settle in the document's own currency), so realized FX is purely a **rate** difference, never a currency difference. Resolved in **D-5**.

- **Unrealized FX is a `DepreciationRun` clone.** Period-end revaluation of open foreign AR/AP (and foreign cash/bank) at spot is an operator-triggered, idempotent-per-(company, period) run that posts one base-currency journal while the period is still OPEN (the `YearEndClose` ordering) and **reverses it on the first day of the next period** via `postReversal` (the `reopenFiscalYear` mechanism) — because the mark-to-market is provisional and must back out so the next settlement measures realized FX off the **original** invoice rate. Resolved in **D-6**.

- **The conversion engine is cross-cutting and must not live in a business module.** GL/AR/AP/sales all need it. It lands in `platform.common.money` (beside `Money`, finally building the arithmetic D-6 deferred) so no module imports another across a `ModuleBoundaryTest` edge. A missing **foreign** rate must **fail loudly** at entry (never silently post at rate 1); the `from==base` identity is the *only* rate=1 short-circuit. Resolved in **D-1**.

- **Day-1 single-currency must be provably unchanged.** Every new rate column DEFAULTs 1, every `base_*` back-fills to the face amount, the `from==base` short-circuit needs no rate row, and a zero FX delta emits no FX leg. The rate=1 path computes and posts the identical numbers it does today, on the identical engine code. This is the owner's hard constraint, met by construction. Resolved in **D-8**.

- **Schema freeze / direction.** FX is additive in the assigned range **V77–V81** on frozen V1–V19 (and on V20–V76 shipped): two new master tables, additive columns on five document headers + two allocation junctions (all DEFAULT-1 back-filled), one new run table-pair, four new `gl_config` keys + four CoA accounts, the `FX_REVALUATION` source token, the CHECK widens, and the FX permissions. It changes **no GL engine code** (`post`/`validateLine`/`postReversal`/`JournalLine`/`LineDraft` untouched); it changes the AR/AP settlement builders (additive FX leg) and adds a conversion service.

## Decision

### D-1 — `CurrencyConversionService` + `FxRateService` in `platform.common.money`; `from==base` is the only rate=1 short-circuit; a missing foreign rate fails loudly

FX conversion is **cross-cutting** — consumed by gl/ar/ap/sales — so it lives at the platform level beside `Money` (`com.erp.platform.common.money`), **not** in a business module (would force a `ModuleBoundaryTest`-crossing import). This builds the arithmetic `Money` deferred in ADR-0005 D-6/D-8.

```
com.erp.platform.common.money
├── Money (existing @Embeddable — gains NO cross-currency arithmetic operators;
│          conversion is an explicit service call, never an operator, ADR-0005 D-6)
├── FxRateService(+Impl)            — rateOn(companyId, from, to, onDate) → BigDecimal (the effective-dated lookup)
├── CurrencyConversionService(+Impl)— toBase(companyId, txnCurrency, txnAmount, onDate) → ConvertedAmount(baseAmount, rate, rateAt)
│                                      + rateOn(...) passthrough; @Transactional(MANDATORY) like GLConfigResolver
├── ConvertedAmount (record: BigDecimal baseAmount, BigDecimal rate, Instant rateAt)
└── FxRateNotFoundException         — typed, user-safe; thrown when from != to and no active rate row resolves
```

Behaviour (the three rules that protect the single-currency path **and** correctness):
1. **Identity short-circuit (the ONLY rate=1 path):** when `txnCurrency.equals(resolveBaseCurrency(companyId))`, return `ConvertedAmount(txnAmount, ONE, now)` with **no rate-table lookup**. This is the byte-identical single-currency fast path.
2. **Foreign conversion:** otherwise look up `FxRateService.rateOn(...)`, compute `baseAmount = round(txnAmount × rate, baseMinorUnits)` HALF_UP (minor units from the `currencies` master, ADR-0005 D-2/D-3), and stamp `rate`, `rateAt`.
3. **Loud failure (graft from Proposal C):** if `from != base` and **no** active rate resolves for the date, throw `FxRateNotFoundException` — a foreign document must **never** silently post at par. The short-circuit applies strictly to `from==base`; it is never a catch-all default for a missing foreign row. The UI blocks the save until a rate exists.

`resolveBaseCurrency(companyId)` (the existing single read in `GLPostingServiceImpl`) is reused as the "to" currency; the AR `.orElse("TZS")` literals are replaced by `Company.getBaseCurrency()` (D-9).

### D-2 — `currencies` master (global) + `currency_rates` (per-company, effective-dated) — the rate source

Two new `UidEntity` tables (`version BIGINT NOT NULL DEFAULT 0`, `uid VARCHAR(26)`, audit cols).

#### `currencies` (global reference data — **the one table that deliberately carries NO `company_id`**, stance declared explicitly)

Currency codes are org-wide reference data (FR-CUR-01 says "not per-company"). This is the long-deferred ADR-0005 D-3/D-8-item-1 master that turns `Money.currency` from an unvalidated free string into a validated soft FK and supplies the minor-unit decimals that drive rounding.

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_currency_uid` |
| `code` | CHAR(3) | NO | `uq_currency_code UNIQUE (code)`; ISO-4217 |
| `name` | VARCHAR(60) | NO | e.g. "US Dollar" |
| `symbol` | VARCHAR(8) | YES | e.g. "$" |
| `minor_units` | SMALLINT | NO | `chk_currency_minor_units CHECK (minor_units IN (0,2,3))`; TZS=0, USD/EUR/KES/GBP=2, BHD=3 — drives HALF_UP rounding scale (BR-CUR-03) |
| `active` | BOOLEAN | NO | DEFAULT true; only active currencies selectable (BR-CUR-04) |
| `version` + audit | | | |

> **Tenancy stance (explicit):** `currencies` is the single new table with **no tenant predicate** — it is org-wide reference data, the same exception `chart_of_accounts` *types* are not. It is read-only to all but `CURRENCY.MANAGE`. Every *other* new table below carries `company_id` (+ `branch_id`) and the tenant predicate, and every read path calls `assertCanActIn` (the #1 anti-regression guard).

#### `currency_rates` (per-company, effective-dated — **strictly per-company; no global/NULL rows in v1**)

Base currency is per-company (ADR-0005 D-4); a rate is meaningfully "to a specific company's base," and a company may negotiate its own rate. Rates therefore carry `company_id` and the tenant predicate like every other transactional table. (Proposal C's hybrid `company_id IS NULL` global-override row is **rejected** — it punches a hole in the universal tenant predicate for a rate-feed capability FR-CUR-10 explicitly defers; global feed rows are a future ADR.)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_currency_rate_uid` |
| `company_id` | BIGINT | NO | `fk_currency_rate_company` → `companies(id)`; tenant |
| `branch_id` | BIGINT | YES | nullable — rates are company-level; carried for the tenant predicate only |
| `from_currency` | CHAR(3) | NO | the foreign currency; soft FK → `currencies.code` (validated in service, ADR-0005 D-3) |
| `to_currency` | CHAR(3) | NO | == company base in v1, stored explicitly so cross-rates are not precluded; soft FK → `currencies.code` |
| `rate` | NUMERIC(19,8) | NO | `chk_currency_rate_positive CHECK (rate > 0)`; scale 8 (ADR-0005 D-5) |
| `effective_date` | DATE | NO | as-of date |
| `active` | BOOLEAN | NO | DEFAULT true |
| `version` + audit | | | |

- `uq_currency_rate UNIQUE (company_id, from_currency, to_currency, effective_date)`.
- `ix_currency_rates_lookup (company_id, from_currency, to_currency, effective_date DESC)` (the as-of lookup index).
- **Rate direction (fixed convention, documented once, applied everywhere):** `rate` = **units of `to` (base) per ONE unit of `from` (foreign)**, i.e. `base = face × rate` (multiply, never divide). A `USD→TZS` rate of `2500.00000000` means 1 USD = 2500 TZS. The conversion service is the **only** code that applies it, so the classic invert-the-rate defect cannot occur ad hoc.
- **Effective-dating:** `FxRateService.rateOn(companyId, from, to, onDate)` returns the row with the greatest `effective_date <= onDate` that is `active`, for (company, from, to). New `CurrencyRateRepository.findEffective(...)` (`ORDER BY effective_date DESC` LIMIT 1). Rows are **never updated in place** — a correction is a new effective-dated row, preserving full rate history (the audit trail).
- **Who maintains:** manual entry only in v1 (FR-CUR-10 feeds deferred) via `CURRENCY.MANAGE` (D-7). Validation (service): rate > 0; `from`/`to` exist and are `active` in `currencies`; `to` == company base; `effective_date` present.

### D-3 — `GLPostingServiceImpl` is **byte-untouched**: conversion happens in the posters *before* `LineDraft` construction; the ledger only ever sees base TZS

This is the spine of the decision and the reason the sacred invariant holds.

- **`validateLine` BR-GL-06 is KEPT VERBATIM** (`if (!baseCurrency.equals(ld.currency())) throw`). Every poster passes the **base** currency code as `LineDraft.currency` and the **base** amount as the debit/credit, because the leg *is* in base. The gate now always passes by construction — **no relaxation, no widening of the attack surface on the one rule that guarantees a base-only ledger.** A foreign line never reaches the ledger in v1.
- **The Σ reducer (the `reduce(ZERO, add)` + `compareTo` balance block) is UNCHANGED** and keeps summing the existing `debitAmount`/`creditAmount` draft fields. We do **not** make Σ currency-aware (the recon's option (b)) and we do **not** move it to base columns (Proposal C's headline risk, **killed** by all three judges).
- **`JournalLine`, `journal_lines`, all `LineDraft` constructors (5/9/12-arg), `JournalEntryDraft`, `JournalLineDto`, and the `postReversal` line-copy are UNCHANGED.** `journal_lines` gains **no** new columns; its javadoc "All amounts in company base currency" remains literally true.
- **Rounding residual — the balancing-leg-as-plug rule (KEEP from A, the cleanest in-house answer):** when a multi-line foreign document converts to base, per-leg HALF_UP rounding can leave a 1-minor-unit base residual. The poster computes the **control/AR/AP leg (and the FX gain/loss leg at settlement) as the BALANCING figure** = `Σ` of the other base legs — never as an independent rounded conversion. The entry then balances exactly and the unchanged Σ-check passes. This is the same plug trick multi-line VAT already uses, and that `YearEndClose` uses against `RETAINED_EARNINGS`.
- **`FxDocumentConverter` — the single conversion chokepoint (graft from Proposal B):** all document-level foreign→base conversion routes through **one** helper in `platform.common.money` that converts each leg HALF_UP and assigns the rounding residual to the designated balancing leg (residual-to-balancing-leg policy). Posters call it instead of hand-rolling conversion, so the plug rule is implemented and tested **once**, not re-derived in each of the ~10 posters. The unchanged `post()` Σ-check is the defence-in-depth backstop (it rejects a draft a poster got wrong; nothing partial is written on rejection).

**Property test (mandated, graft from B):** assert (a) a mixed-currency document's draft balances on the base legs; (b) an **unbalanced** base draft is still rejected by `post()`; (c) the rate=1 path posts byte-identical legs/amounts to pre-FX.

### D-4 — Where the base triple is stamped (immutable, captured-once) — the ADR-0005 D-5 columns on the four source-document headers

The D-5 triple (`*_rate` NUMERIC(19,8), `*_base_amount` NUMERIC(19,4), `rate_at` TIMESTAMPTZ) lands **only** on the documents that need a frozen original rate for realized/unrealized FX. The base currency code is **not** stored per row (it always equals `company.base_currency`, ADR-0005 D-4 — stored once on the company; storing it per row is dead weight, a point all three judges affirmed). All new rate/base columns except the `*_outstanding` base figures are `updatable=false` (IMMUTABLE, BR-CUR-05).

Conversion is stamped at **document finalise** through `FxDocumentConverter`, captured once:

| table | additive columns (all DEFAULT-1 / face-backfilled) | mutable? |
|---|---|---|
| `sales_invoices` | `fx_rate NUMERIC(19,8) NOT NULL DEFAULT 1`, `base_gross_total_amount NUMERIC(19,4)`, `rate_at TIMESTAMPTZ` | immutable |
| `ar_invoices` | `fx_rate NUMERIC(19,8) NOT NULL DEFAULT 1`, `base_original_amount NUMERIC(19,4)`, `base_outstanding_amount NUMERIC(19,4)`, `rate_at TIMESTAMPTZ` | `base_outstanding_amount` moves with `outstanding_amount`; rest immutable |
| `supplier_bills` | `fx_rate NUMERIC(19,8) NOT NULL DEFAULT 1`, `base_gross_amount NUMERIC(19,4)`, `base_outstanding_amount NUMERIC(19,4)`, `rate_at TIMESTAMPTZ` | `base_outstanding_amount` moves; rest immutable |
| `ar_receipts` | `fx_rate NUMERIC(19,8) NOT NULL DEFAULT 1`, `rate_at TIMESTAMPTZ` (the settlement rate) | immutable |
| `ap_payments` | `fx_rate NUMERIC(19,8) NOT NULL DEFAULT 1`, `rate_at TIMESTAMPTZ` (the settlement rate) | immutable |
| `ar_receipt_allocations` | `base_allocated_amount NUMERIC(19,4)`, `settlement_rate NUMERIC(19,8)` | immutable |
| `ap_payment_allocations` | `base_allocated_amount NUMERIC(19,4)`, `settlement_rate NUMERIC(19,8)` | immutable |

- **The allocation-junction base capture (graft from Proposal B)** is the one addition beyond Proposal A's headers: each allocation row stores the base value settled (at the settlement rate) and the settlement rate, so **partial-settlement realized FX is pure arithmetic and fully audit-traceable** rather than re-derived. This closes A's only traceability gap on partial settlements.
- **Posting flow per document:** SALES (`SalesInvoiceServiceImpl.create` / posting handler) resolves the rate via `CurrencyConversionService`, stamps `fx_rate`/`rate_at`/`base_gross_total_amount`, and the sale's GL legs (DR AR / CR Revenue + CR VAT) post in **base** with AR as the balancing leg; `ArSalePostedHandler` inherits the invoice `fx_rate` and sets `base_original_amount = base_outstanding_amount`. AP (`BillMatchServiceImpl`) is symmetric (DR Purchases / CR AP-control + CR VAT in base, AP balancing). Base-currency documents (rate=1) stamp `fx_rate=1`, `base_*=face` and change nothing.

### D-5 — Realized FX on settlement: a base-currency balancing plug leg inline in the existing AR/AP builders; four split keys; original rate read off the locked open item

Realized FX arises at AR-receipt / AP-payment settlement when the settlement rate ≠ the original invoice/bill rate. It is posted as **base-currency legs** (the gain/loss is inherently base), so it needs **no** engine change and **no** new source token — it rides the existing settlement journal under the existing `AR_RECEIPT`/`AP_PAYMENT` source. v1 keeps BR-CUR-06 (same-currency settlement), so the allocation tables need no currency column; realized FX is purely a rate difference. The receipt/payment currency is **no longer forced to base** via `.orElse("TZS")` — it is read from `req.currency()`, validated to equal the allocated documents' currency (BR-CUR-06).

**AR (`ArReceiptServiceImpl.recordAndAllocate` — allocation loop ~155-173 + GL builder ~215-238):** inside the loop, where each `ArInvoice` (locked, `fx_rate` in scope) has its outstanding reduced:
- per allocation: `base_settled = round(allocated_face × receipt.fx_rate)`; `base_relieved = round(allocated_face × invoice.fx_rate)` (the slice of `base_outstanding_amount` being cleared). Store `base_allocated_amount = base_settled` and `settlement_rate` on the allocation row; decrement `ar_invoices.base_outstanding_amount` by `base_relieved` alongside `outstanding_amount`.
- GL legs: **DR Cash = Σ base_settled** (at the settlement rate; + unallocated at receipt rate) · **CR AR control = Σ base_relieved** (the base value originally debited to AR — relieved at the **original** invoice rate, not the cash value) · **the FX leg is the BALANCING figure** = `Σ base_relieved − Σ base_settled`: customer's base-worth **less** than the booked receivable ⇒ FX **loss** (DR `REALIZED_FX_LOSS`); **more** ⇒ FX **gain** (CR `REALIZED_FX_GAIN`). Balanced by construction; the unchanged Σ-check passes.

**AP (`ApPaymentServiceImpl.postPaymentToGl` ~267-336; per-bill in `paySingle` ~134-141 / `paymentRun` ~207-216):** mirror image — **DR AP control = Σ base_relieved** (original bill rate) · **CR Cash = Σ base_settled** (payment rate) · residual to `REALIZED_FX_GAIN`/`REALIZED_FX_LOSS`. For a payable: settling at a base-worth **greater** than booked ⇒ loss; **less** ⇒ gain.

**Original rate storage/read:** `ar_invoices.fx_rate`/`base_outstanding_amount` and `supplier_bills.fx_rate`/`base_outstanding_amount` are read directly off the already-locked entity in the settlement loop (no extra query); `fx_rate`/`base_*original` are `updatable=false` (BR-CUR-05) so a later rate edit can never recompute a posted base value.

**Four split gl_config keys (graft from B/C — supersedes Proposal A's two-key scheme):** `REALIZED_FX_GAIN` (INCOME), `REALIZED_FX_LOSS` (EXPENSE), and (for D-6) `UNREALIZED_FX_GAIN` (INCOME), `UNREALIZED_FX_LOSS` (EXPENSE) — four keys so the P&L distinguishes **crystallised** (realized) from **provisional** (unrealized, reversed) gain/loss, and gain stays in INCOME / loss in EXPENSE for a clean trial balance (the auditor preference; a single combined key is the cheaper alternative, rejected). Resolved via the unchanged `GLConfigResolver.resolve(companyId, key)` — no resolver change.

Single-currency path: `invoice.fx_rate == receipt.fx_rate == 1` ⇒ `realized_fx == 0` ⇒ **no FX leg emitted**, the settlement journal has the identical legs/amounts as today.

### D-6 — Unrealized period-end FX revaluation: a `DepreciationRun` clone + `FX_REVALUATION` source + reverse-next-period via `postReversal`

Period-end revaluation of OPEN foreign AR/AP (and foreign cash/bank) at spot (FR-CUR-12), modelled **exactly** on the `DepreciationRun` run-header/run-lines/preview/idempotency/outbox/audit pattern + the `YearEndClose` windowed-aggregate-then-post + the `reopenFiscalYear` reverse-next-period mechanism. **Operator-triggered on demand at period end, not a scheduled scanner.**

#### `fx_revaluation_runs` (header — the idempotency anchor) + `fx_revaluation_run_lines` (child)

`fx_revaluation_runs` (`UidEntity`, `version`): `id`/`uid` (`uq_fx_revaluation_run_uid`), `company_id` (+ `branch_id`), `run_number` VARCHAR(30) (`FXR-####`, lazy `code_sequence` kind `FX_REVALUATION_RUN`; `uq_fx_revaluation_run_company_number`), `fiscal_period_id` (`fk_fx_revaluation_run_period` → `fiscal_periods(id)`), `posting_date` DATE, `spot_rate_date` DATE, `status` VARCHAR(20) (`chk_fx_revaluation_run_status CHECK (status IN ('PREVIEWED','POSTED','REVERSED'))`), `total_gain_amount`/`total_loss_amount`/`net_adjustment_amount` NUMERIC(19,4), `gl_entry_uid` VARCHAR(26), `reversal_gl_entry_uid` VARCHAR(26) NULL, `executed_at` TIMESTAMPTZ, `version` + audit. **Idempotency: `uq_fx_revaluation_run_company_period UNIQUE (company_id, fiscal_period_id)`** — one run per company+period (the `DepreciationRun` D-4 precedent); the post path checks `findByCompanyIdAndFiscalPeriodId` first and returns the existing run (no-op).

`fx_revaluation_run_lines`: `id`/`uid`, `fx_revaluation_run_id` (`fk_fx_revaluation_run_line_run`), `company_id`, `source_type` VARCHAR(10) (`AR`/`AP`/`CASH`), `currency` CHAR(3), `control_account_id` (`fk_...` → `chart_of_accounts(id)`), `outstanding_txn_amount` NUMERIC(19,4), `carrying_base_amount` NUMERIC(19,4) (the frozen `base_outstanding_amount`), `spot_rate` NUMERIC(19,8), `revalued_base_amount` NUMERIC(19,4), `adjustment_amount` NUMERIC(19,4) (signed) + audit.

#### What it revalues, the aggregate query, the post, the reversal

- **Scope:** OPEN foreign-currency `ar_invoices` (status OPEN/PARTIAL, currency≠base), `supplier_bills` (MATCHED/APPROVED/PARTIALLY_PAID, currency≠base), and foreign-currency `cash_bank_accounts`. (`cash_bank_accounts` already has `currency`; no per-row change — it is revalued via its outstanding/GL balance, same shape.) v1 may stage AR+AP first; cash/bank is identical shape.
- **Aggregate query** (mirrors `YearEndCloseServiceImpl.buildMovementMap` windowed-aggregate + `JournalLineRepository.periodMovementByAccount`, native behind a named repo method — allowed for reports): `SUM(base_outstanding_amount)` (carrying base) and `SUM(outstanding_amount)` (face) grouped by **(currency, control account)** over open items as at period-end. It reads the **sub-ledger open-item base** (the correct revaluation base), **not** the GL account balance (which mixes realized movement). For each currency: `revalued_base = round(Σ face_outstanding × spot_rate_on(period_end))`; `adjustment = revalued_base − Σ carrying_base`.
- **Post:** ONE balanced base-currency journal via the **unchanged** `glPostingService.post(draft)` under the NEW `JournalSourceType.FX_REVALUATION`, posted at period-end **while the period is still OPEN** (run before `FiscalCalendarServiceImpl.closePeriod`, exactly as `YearEndClose` posts before auto-closing). Net gain ⇒ DR control / CR `UNREALIZED_FX_GAIN`; net loss ⇒ DR `UNREALIZED_FX_LOSS` / CR control (revaluing the control's base carrying value to spot). Routed via a new `GLPostingSafeInvoker(REQUIRES_NEW)` wrapper `postFxInNewTx` (graft from B, mirroring `postSaleInNewTx`) so a missing `UNREALIZED_FX_*` config returns null rather than poisoning the dispatch TX.
- **Reverse-next-period (mandatory):** immediately schedule `glPostingService.postReversal(runGlEntryUid, firstDayOfNextPeriod, FX_REVALUATION, run.uid, actorId)` — the mark-to-market is provisional and must back out so the next settlement computes realized FX off the **original** rate, never double-counted. Idempotency via `journalEntries.existsByReversalOfId` (the `YearEndClose` precedent). **Sequencing rule:** the next period must be OPEN for the reversal; if it is not yet created/open, the run records the reversal intent and the reversal posts when that period opens (a documented known sequencing rule).
- **Preview** (`preview(companyUid, fiscalPeriodUid)`, read-only, `assertCanActIn`): a dry run returning the per-currency would-be lines (carrying base, spot rate, revalued base, adjustment, totals) with **no** posting — the `DepreciationRunServiceImpl.preview` clone.
- New `AuditActions.FX_REVALUATION_RUN`; outbox `DomainEventType.FX_REVALUATION_EXECUTED` + `DepreciationRunExecutedPayload`-shaped payload (emitted after the synchronous post, audit/downstream only).

### D-7 — Frontend screens + permissions (RBAC by permission code; `assertCanActIn` on every read)

All screens use the shipped `ApiResponse<T>` envelope and the ADR-0005 D-7 Money wire shape (`amount` as STRING; the reserved `baseAmount`/`baseCurrency`/`rate`/`rateAt` fields now get **populated** — additive, no new TS type). Day-1 single-currency users see no change (the base block is suppressed when `currency == base`; FX widgets appear only when a foreign currency/document exists).

1. **Currency master** (`fx/currencies`, `CURRENCY.MANAGE`): list/activate/deactivate currencies (code/name/symbol/minor_units/active). Reference-data admin.
2. **Rate maintenance** (`fx/rates`, `CURRENCY.MANAGE`): per-company `currency_rates` grid — add a rate (`from` picker of active currencies, `to` defaulted+locked to company base, `rate` at 8dp, `effective_date`); effective-dated history newest-first; **no edit-in-place** (a correction is a new effective-dated row). Direction shown explicitly as "1 {from} = {rate} {base}" to kill ambiguity.
3. **Document FX display:** foreign sales invoices / supplier bills / AR receipts / AP payments show the document-currency face (primary) with the base equivalent + rate + `rate_at` as read-only secondary info ("USD 1,000.00 @ 2,500.0000 = TZS 2,500,000 on 2026-06-13"). AR-receipt / AP-payment detail shows the **realized FX gain/loss** line when present, labelled, read-only.
4. **FX revaluation run** (`fx/revaluation-runs`, `FX.REVALUE`): clone the `DepreciationRun` UI — pick fiscal period → **Preview** (per-currency adjustment table, dry run) → **Run**; run history with gain/loss totals, `gl_entry_uid` link, and the scheduled reversal status. Idempotency surfaced as "already run for this period" (read-only, post disabled).
5. **FX exposure report** (`fx/exposure`, `FX.EXPOSURE.VIEW`) (graft from C, read-only): open AR/AP/cash by currency, total base exposure at the latest rate, unrealized gain/loss if revalued now.

**Permissions** (MODULE.RESOURCE.ACTION, `@perm.has`/`@perm.scoped`, NEVER `hasAuthority`; seeded + granted to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING`): `CURRENCY.MANAGE` (master + rates), `FX.REVALUE` (preview + run revaluation), `FX.EXPOSURE.VIEW` (exposure + FX-on-document read). Remapping `REALIZED_FX_*`/`UNREALIZED_FX_*` gl_config reuses `GL.MANAGE`. `assertCanActIn(RequestContext, companyId)` on **every** read path — rates, runs, preview, exposure, document FX views (the #1 anti-regression guard; no FX read bypasses it).

### D-8 — Rounding policy + the day-1 single-currency no-regression guarantee

- **Rounding:** convert each leg at full precision, round once to the **base** minor units (from the `currencies` master, ADR-0005 D-2/D-3) HALF_UP; the rounding residual is absorbed by the **balancing leg** (D-3), never by an independent rounded conversion. Rates are stored at scale 8 and never pre-rounded.
- **No-regression guarantee (the headline constraint, met by construction):**
  - (a) every new rate column DEFAULTs 1; every `base_*` back-fills to the face amount — existing rows are correct without a conversion.
  - (b) `CurrencyConversionService` short-circuits `from==base` ⇒ returns the amount unchanged, rate=1, **no** rate-table row required.
  - (c) `realized_fx == 0` when invoice rate == settlement rate ⇒ **no** FX leg emitted ⇒ the settlement journal is identical to today.
  - (d) `JournalLine`, every `LineDraft` constructor, `JournalEntryDraft`, `GLPostingServiceImpl.post()`'s Σ reduce/compareTo, `validateLine` BR-GL-06, `postReversal`'s line-copy, and `JournalLineDto` are **UNCHANGED** — the ledger never sees a foreign line; the 789 tests run the identical code on the rate=1 path.
  - (e) the only rate=1-path behaviour change is an extra in-memory `base==face` computation yielding the identical number; the only touched query sites are the 8 `.orElse("TZS")` literals replaced by `Company.getBaseCurrency()` (D-9), behaviourally identical for a TZS-base company — each with a regression check.

### D-9 — ADR-0005 D-4 cleanup: replace the 8 hard-coded `.orElse("TZS")` literals with `Company.getBaseCurrency()`

The 8 sites (`ArReceiptServiceImpl` ~123, `ArAgeingQuery` 61/84, `ArBalanceServiceImpl` 42, `ArCreditNoteServiceImpl` 88, `ArOpeningBalanceServiceImpl` 74, `ArWriteOffServiceImpl` 86, `ArReconciliationQuery` 48) hard-code `"TZS"` — an ADR-0005 D-4 "never hard-code TZS" violation and an FX correctness risk for a non-TZS-base company. Each reads `company.getBaseCurrency()` instead (behaviourally identical for TZS-base companies; correct for any base). Per-currency balances stay distinct (BR-CUR-08); the new `base_outstanding_amount` is a clearly-labelled converted roll-up, never summed across currencies with face amounts.

### D-10 — Enum / source-type / audit additions (additive; the DB CHECK is the real gate)

- `GlConfigKey` += `REALIZED_FX_GAIN`, `REALIZED_FX_LOSS`, `UNREALIZED_FX_GAIN`, `UNREALIZED_FX_LOSS`.
- `JournalSourceType` += `FX_REVALUATION` (realized FX **reuses** `AR_RECEIPT`/`AP_PAYMENT` — no settlement token).
- `AuditActions` += `FX_REVALUATION_RUN` (+ optional `FX_RATE_SET`).
- `DomainEventType` += `FX_REVALUATION_EXECUTED` (+ `FX_REVALUATION_RUN` aggregate constant).
- `GlConfigServiceImpl.DEFAULT_MAPPINGS` += the four FX keys → the four new account codes (so `seedDefaults` wires new companies).

## Migration ordering (V77–V81; additive; V1–V19 FROZEN, V20–V76 never edited; #12-safe seeds)

Latest shipped migration is **V76**. All additive — new tables, ADD COLUMN (DEFAULT-1, back-filled), CHECK-widen (DROP+ADD full union), CoA/gl_config/permission seeds.

- **`V77__fx_currency_master_and_rates.sql`**
  1. CREATE `currencies` (global, **no `company_id`** — stance in a comment; `uq_currency_code`; `chk_currency_minor_units IN (0,2,3)`).
  2. CREATE `currency_rates` (per-company; `version`; FKs to `companies`/`branches`; `uq_currency_rate (company_id, from_currency, to_currency, effective_date)`; `ix_currency_rates_lookup`; `chk_currency_rate_positive`).
  3. SEED `currencies`: TZS(0), USD(2), EUR(2), KES(2), GBP(2) active=true. **No rate rows seeded** (single-currency companies need none; base→base is the rate=1 short-circuit).
  4. SEED permission `CURRENCY.MANAGE` + grant to `ORG_ADMIN` (the V7/V12/V14 CROSS-JOIN `ON CONFLICT DO NOTHING` pattern).
- **`V78__fx_document_rate_columns.sql`** — additive ALTERs, **ADD nullable → back-fill → SET NOT NULL where applicable** (the ordering discipline, graft from B), all DEFAULT-1 so single-currency = byte-identical:
  - ALTER `sales_invoices` ADD `fx_rate` (NOT NULL DEFAULT 1), `base_gross_total_amount`, `rate_at`; back-fill `base_gross_total_amount = gross_total_amount`.
  - ALTER `ar_invoices` ADD `fx_rate`, `base_original_amount`, `base_outstanding_amount`, `rate_at`; back-fill `base_* = face` WHERE NULL; `fx_rate`/`base_original_amount` `updatable=false` in the entity.
  - ALTER `supplier_bills` ADD `fx_rate`, `base_gross_amount`, `base_outstanding_amount`, `rate_at`; back-fill `base_* = face`.
  - ALTER `ar_receipts`, `ap_payments` ADD `fx_rate`, `rate_at`.
  - ALTER `ar_receipt_allocations`, `ap_payment_allocations` ADD `base_allocated_amount`, `settlement_rate`; back-fill `base_allocated_amount = allocated_amount`, `settlement_rate = 1`.
- **`V79__fx_gl_keys_and_source_type.sql`**
  1. `chk_gl_config_key` DROP+ADD = **full V74 superset union** + `REALIZED_FX_GAIN`, `REALIZED_FX_LOSS`, `UNREALIZED_FX_GAIN`, `UNREALIZED_FX_LOSS` (copy the prior IN-list verbatim, append four).
  2. `chk_journal_batch_source_type` **AND** `chk_journal_entry_source_type` DROP+ADD = full union (incl. all V74 manufacturing + FA tokens) + `FX_REVALUATION` (`AR_RECEIPT`/`AP_PAYMENT` already present).
  3. SEED CoA accounts per existing company (mirror V46 §1; free codes, e.g. `4910` Unrealized FX Gain (INCOME), `4920` Realized FX Gain (INCOME), `5190` Realized FX Loss (EXPENSE), `5191` Unrealized FX Loss (EXPENSE)); uid `'FX' || lpad(c.id::text,6,'0') || account_code` (≤26).
  4. SEED `gl_configs` rows mapping the four keys → the four accounts for every existing company (mirror V46 §3); uid `'FXC' || lpad(company_id::text,6,'0') || substr(md5(config_key),1,12)` (21 chars, **#12-safe** — never `|| config_key`); `ON CONFLICT (company_id, config_key) DO NOTHING`. Add the four to `GlConfigServiceImpl.DEFAULT_MAPPINGS`.
- **`V80__fx_revaluation_runs.sql`** — CREATE `fx_revaluation_runs` (+ `uq_fx_revaluation_run_company_period`) + `fx_revaluation_run_lines` (`UidEntity`, `version`, FKs to `fiscal_periods`/`chart_of_accounts`).
- **`V81__fx_permissions.sql`** — SEED `FX.REVALUE`, `FX.EXPOSURE.VIEW` + grant to `ORG_ADMIN` (CROSS-JOIN `ON CONFLICT DO NOTHING`). (`CURRENCY.MANAGE` seeded in V77.)

`MigrationKeepDataIT` extends to V81 (all seeds `ON CONFLICT DO NOTHING`, keep-data-safe). An IT asserts every new `GlConfigKey`/`JournalSourceType` value is admitted by the widened DB CHECK (the superset-drift backstop). No edit to any prior migration.

## Build tranches (parallelizable units + migration ranges)

The work splits into five units; **Tranche 1 is the shared dependency** (the conversion service + rate tables); 2/3/4 are then largely parallel; 5 (frontend) tracks each as its API lands.

1. **Rate-table + conversion service** — `V77`. `currencies` + `currency_rates` + `CurrencyRateRepository.findEffective`; `FxRateService`, `CurrencyConversionService`, `ConvertedAmount`, `FxRateNotFoundException`, `FxDocumentConverter` in `platform.common.money`; `CURRENCY.MANAGE`. The shared dependency for all others. (D-1, D-2)
2. **Posting conversion (document finalise)** — `V78` (the five header columns) + `V79` (keys/source/CoA/gl_config). Stamp the D-5 triple at sales/AP-bill finalise via `FxDocumentConverter`; posters pass base legs + balancing-leg-as-plug; **GL engine untouched**; the property test (D-3). Depends on Tranche 1. (D-3, D-4, D-10)
3. **Realized-FX settlement** — `V78` allocation columns (shared with T2) + `V79` keys (shared). The inline FX balancing leg in `ArReceiptServiceImpl.recordAndAllocate` + `ApPaymentServiceImpl.postPaymentToGl`; allocation base capture; the `.orElse("TZS")` → `getBaseCurrency()` cleanup (D-9). Depends on Tranche 1 + the `REALIZED_FX_*` keys from T2's V79. (D-5, D-9)
4. **Unrealized revaluation run** — `V80`. `fx_revaluation_runs`/`_lines`, `FxRevaluationRunService(+Impl)` (`DepreciationRun` clone: preview/post/idempotency), `FxRevaluationGlPoster` via `postFxInNewTx`, `postReversal(next-period)`, the aggregate query, outbox/audit, `FxRevaluationRunController`. Depends on Tranche 1 + the `UNREALIZED_FX_*` keys + `FX_REVALUATION` source from T2's V79. (D-6, D-10)
5. **Frontend** — tracks each backend tranche. `V81` perms (`FX.REVALUE`, `FX.EXPOSURE.VIEW`). Currency + rate maintenance (after T1), document FX display + realized-FX line (after T2/T3), revaluation-run + exposure screens (after T4). (D-7)

## Consequences

**Positive**
- The sacred Σbase invariant is preserved **by construction**: `post()`'s reduce/compareTo, `validateLine` BR-GL-06, `JournalLine`, every `LineDraft` constructor, and `postReversal`'s line-copy are byte-for-byte unchanged. The 789 single-currency tests exercise the identical engine on the rate=1 path; a foreign line never reaches the ledger.
- ADR-0005 D-5 is honoured literally — the base triple lives on the four source-document headers where it earns its keep — and the dual-amount *ledger* is consciously declined for v1 (the SAP/NetSuite single-functional-currency posture), recorded as a deferred additive option, not silently dropped.
- Realized FX is a normal base-currency balancing leg in the existing inline AR/AP builders (reusing `AR_RECEIPT`/`AP_PAYMENT`); unrealized FX is a `DepreciationRun` clone with `postReversal` reverse-next-period — every mechanism is a proven in-house pattern, no new abstraction, no exotic Postgres.
- Four split keys give the auditor clean realized-vs-unrealized P&L separation; allocation-junction base capture makes partial-settlement realized FX arithmetic and traceable; a missing foreign rate fails loudly at entry (`FxRateNotFoundException`), never silent par.
- Fully additive (V77–V81) and reversible: if FX is never switched on the columns sit at their DEFAULT-1 / face values harmlessly; the design is the additive foundation for a future true multi-currency ledger or rate feed, not a wall.

**Negative / costs**
- The ledger line is **not** self-describing: an auditor reading `journal_lines` sees TZS 2,500,000 with no on-line record that it was USD 1,000 @ 2,500 — they must join to the source document (possibly across a `ModuleBoundaryTest` edge) to reconstruct the rate. This is the deliberate price of the base-only ledger; the document-level triple + the FX-on-document UI mitigate it. If GL-level FX audit is later demanded, it is the deferred dual-amount-ledger option (Alternatives), implemented B's way (overlay; Σ never reads `txn_*`; `postReversal` line-copy extended).
- More columns on five headers + two junctions + two master tables + four CoA accounts/keys per company — the ADR-0005 D-5 cost already accepted.
- The revaluation reversal depends on the next period being OPEN; if not, the run records intent and the reversal posts on next-period open (a documented sequencing rule, idempotency-guarded).
- The 8 `.orElse("TZS")` removals are behaviourally identical for TZS-base companies but each touched site needs a regression check.

**Neutral / deferred** — cross-currency settlement (paying a USD bill in EUR — BR-CUR-06 keeps same-currency v1), rate feeds + a global/shared rate row (FR-CUR-10), a `rate_type` SPOT/CLOSING/AVG dimension, a self-describing dual-amount ledger, and currency-aware Σ / per-currency balancing — all deferred, none precluded (each is additive: a new column or a new ADR, never a redefinition of an existing one).

## Alternatives considered

- **Convert-at-the-poster-boundary, base-only ledger (Proposal A) — CHOSEN as the spine.** The only design that leaves the Σ reducer, `validateLine`, `JournalLine`, `LineDraft`, and `postReversal` untouched; the rate=1 path is provably byte-identical; lowest blast radius on the most-tested code. Its one gap (no on-ledger txn face) is an audit-surface trade, not a correctness one, and is mitigated at the document level. All three judges ranked it best.
- **Dual-amount ledger, txn overlay (Proposal B) — DEFERRED additive option.** Redefine `journal_lines.debit_amount/credit_amount` as the base leg and add `txn_currency`/`txn_debit_amount`/`txn_credit_amount`/`rate`/`rate_at` as a pure overlay the Σ **never** reads, a 13th nullable `FxLeg` field on `LineDraft` (all old constructors delegate `null`), `FxDocumentConverter` residual-to-last-line, and **the `postReversal` line-copy extended to carry the txn quad** (else reversals silently drop the overlay). Maximum auditability and the literal D-5-on-the-ledger reading, but it ALTERs the highest-volume append-only table and adds the most surface for benefit FR-CUR-08..12 does not require in v1. Take it **only** if the owner's audit priority later demands a self-describing ledger — and implement it B's way (overlay, not Proposal C's re-point). We grafted B's *good* parts now (split keys, allocation base capture, the converter, the ADD-nullable→backfill→SET-NOT-NULL ordering, the property test) without the ledger churn.
- **Move the sacred Σ to base columns + relax `validateLine` (Proposal C) — REJECTED outright.** C is the only proposal that **edits the balance method itself** (sums `base_debit_amount`/`base_credit_amount`) and **relaxes BR-GL-06** to admit `currency != base` — surgery on the exact reduce/compareTo block and the one currency gate the 789 tests cover, with correctness resting on every back-fill/default being exactly right, for zero business gain over A. Also rejected from C: the hybrid `company_id IS NULL` global/override rate table (breaches the universal tenant predicate + the `assertCanActIn` guard for a deferred feed feature) and the `rate_type` SPOT/CLOSING/AVG enum (speculative — one effective-dated rate per (company, from, to) suffices in v1). We grafted C's *good* parts: the `platform.common.money` service home, `FxRateNotFoundException` loud-fail, and the read-only exposure report.
- **Currency-aware Σ / per-currency balancing of a single entry** — over-build that breaks the single base-currency invariant the whole engine and year-end close rest on; the business (FR-CUR-08..12, BR-CUR-06) does not need cross-currency settlement in v1. Rejected (both B and C also reject it).
- **Storing the base currency code per journal line / per document row** — dead weight (it always equals `company.base_currency`); stored once on the company. Rejected.
- **A single combined `FX_GAIN_LOSS` key** — cheaper but loses the realized-vs-unrealized and gain-vs-loss P&L split the auditor wants. Rejected in favour of four keys.
- **Scheduled auto-run for revaluation** — deferred (needs the general scheduler); operator-initiated preview→post is the v1 stance (the `DepreciationRun` OQ-FA-06 precedent).

## Open Questions

- **OQ-FX-01 — on-ledger FX audit (the one A-vs-B-graft call for the owner):** does the auditor need the original-currency face **on the journal line**, or is the document-level D-5 triple + the FX-on-document UI sufficient? The recon shows realized FX needs no ledger change either way, so this is purely an audit-surface decision. **Default: document-level triple (A).** If the answer is "on the line," execute the deferred dual-amount-ledger option (B's overlay shape, with the `postReversal` line-copy extension) under a follow-on ADR.
- **OQ-FX-02 — CoA account codes:** the four FX account codes (`4910`/`4920`/`5190`/`5191` suggested) must not collide with the shipped + manufacturing/FA-seeded set; the engineer confirms free codes against the live `chart_of_accounts` seed before writing V79. Default codes stand pending that check.
- **OQ-FX-03 — foreign cash/bank in v1 revaluation:** revalue foreign `cash_bank_accounts` in the v1 run, or stage AR+AP first and add cash/bank in v1.1? It is the same shape and table-driven. **Default: include cash/bank in v1** (no extra schema); descope to v1.1 only if delivery pressure demands.
- **OQ-FX-04 — reversal into a not-yet-open next period:** the run records reversal intent and posts on next-period open. Is "intent recorded, posts on open" acceptable, or must the run **refuse** unless the next period already exists? **Default: record intent + post on open** (idempotency-guarded); revisit if finance wants a hard pre-condition.
- **OQ-FX-05 — minor-unit seed set:** `currencies` seeds TZS/USD/EUR/KES/GBP. Confirm the full active set the business needs at go-live (additive — more rows later, no schema change).
- **OQ-FX-06 — sales-invoice currency validation:** today `SalesInvoiceServiceImpl.create` takes `currency` raw from `req.currency()` with no base reconciliation. v1 stamps the rate but should it also **validate** `currency` against `currencies.active`? **Default: yes** (BR-CUR-04, service-layer) — confirm no flow relies on an unvalidated code.

## Build-readiness

This ADR is concrete enough to build the FX engine + the `V77`–`V81` migrations without guessing a rule: every table, column, constraint name, the rate-direction convention, the conversion-service contract + short-circuit + loud-fail, the exact (unchanged) GL engine stance + the balancing-leg-as-plug rule, the realized-FX legs + the four keys + where the original rate is read, the revaluation run (idempotency, aggregate, post, reverse-next-period, preview), the CHECK-widen recipe, the #12-safe seeds, the perms, the screens, the rounding policy, and the byte-identical single-currency proof are specified. **Additive on frozen V1–V19** (and on V20–V76 shipped). **No GL engine code change** (`post`/`validateLine`/`postReversal`/`JournalLine`/`LineDraft`/`JournalLineDto` untouched); the only posting-code changes are the additive FX leg in the AR/AP settlement builders and the document-finalise rate stamp.
