# 0013 — General Ledger data model: chart of accounts + double-entry journals + fiscal periods + gl_configs account mapping + sales auto-posting over the outbox — the append-only posting engine the whole financial roadmap feeds

- **Status:** Accepted
- **Date:** 2026-06-08
- **Deciders:** solutions-architect (owner-ratified GL requirements 2026-06-08 — all six scoping forks resolved; no ADR-0013-blocking open question remains, gl.md §11)
- **Context source:** [docs/requirements/gl.md](../requirements/gl.md) (RATIFIED 2026-06-08 — FR-GL-01..19, BR-GL-01..12, US-GL-01..07, §7 flows, §10 accepted boundary, §11 OQ log; the ground truth for every rule below). [docs/ROADMAP.md](../ROADMAP.md) §3 **T1.1** + §4 (GL is the critical-path gate — nothing reports until it lands; the `SalesPostingHandler` over the outbox, the `gl_configs` mapping, the next-free slots **ADR-0013 / V10**, the seeded CoA + mapping `SALES_REVENUE→4100, VAT_PAYABLE→2200, AR→1200, AP→2100, INVENTORY→1300, COGS→5100`). [ADR-0009](0009-transactional-outbox.md) (THE pattern the posting handlers mirror — `DomainEventHandler.eventType()/handle()`, `IdempotencyGuard.alreadyProcessed/markProcessed`, `processed_events(consumer, event_uid)`; the `@Scheduled DomainEventDispatcher` runs each handler in a per-event TX; handlers are `@Transactional(MANDATORY)`; at-least-once + consumer-side idempotency). [ADR-0008](0008-sales-data-model.md) + [V5__sales.sql](../../backend/src/main/resources/db/migration/V5__sales.sql) (`sales_invoices`: `net_total_amount`/`vat_total_amount`/`gross_total_amount`/`tax_summary` JSONB, `customer_id`, `currency`, `status`, `finalised_at`; the `SALE.FINALISED`/`SALE.VOIDED` event types in `DomainEventType`; the `SaleFinalisedPayload`/`SaleVoidedPayload` records — **verified below: the payload carries NO amounts/customer/currency**, so the handler re-reads `sales_invoices`). [ADR-0005](0005-money-and-currency.md) (`Money` `@Embeddable` amount NUMERIC(19,4)+currency; **D-4 designed a `company.base_currency` config column that is NOT YET BUILT** — see D-9 below; GL posts base-currency only per gl.md BR-GL-06). [ADR-0010](0010-stock-data-model.md)/[ADR-0011](0011-purchases-data-model.md) (the DEFERRED COGS/AP hooks — where they plug in later; not designed here). [PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md) §2 (module layout + `ModuleBoundaryTest`), §3.2 (tenant predicate), §3.3 (uid/id), §3.6 (append-only posting). [[db-naming-convention]] verified against shipped **V1–V9** SQL (plural masters, singular junctions, singular constraint roots `uq_`/`fk_`/`chk_`, plural index names `ix_`, `uid VARCHAR(26)`, `company_id` scalar, audit cols, `code_sequence(company_id, entity_kind)`). The **shipped** [SaleIssueStockHandler](../../backend/src/main/java/com/erp/modules/stock/events/SaleIssueStockHandler.java) and [SaleReversalStockHandler](../../backend/src/main/java/com/erp/modules/stock/events/SaleReversalStockHandler.java) are the **exact templates** the `SalesPostingHandler`/`SaleVoidingHandler` copy; the [ScopeGuard](../../backend/src/main/java/com/erp/platform/security/ScopeGuard.java) switch is where `case "account"` lands. Latest shipped migration is **V9** → GL is **`V10__general_ledger.sql`** (additive; never edits V1–V9).

This ADR is the **technical data model + integration design** for the General Ledger module (GL Increment 1). It translates the ratified business spec into tables, columns, types, keys, indexes, constraints, enforcement placement, the two event-driven posting handlers, the seeded chart of accounts, and the account mapping — **concrete enough that the backend engineer writes `V10__general_ledger.sql` + the entities + the `GLPostingService` + the `SalesPostingHandler`/`SaleVoidingHandler` without guessing a business rule**. It does **not** write production code, entities, or the migration — that is the engineer's next step. The owner's ratified v1 decisions (numeric-range flat CoA, seeded editable TZ small-business set; manual journals that must balance; sales auto-posting via the outbox using `gl_configs`; void reverses; 12 monthly periods with configurable start month, open/close, closed-period posting rejected; append-only immutable ledger, reverse-only; base-currency-only) are taken as given and designed to exactly. **Nothing ratified is re-litigated.**

## Context

GL is the first **posting engine** the system has built, and the **critical-path gate** for the entire financial roadmap (ROADMAP §4): AR/AP control-account posting (T1.2/T1.3), Cash/Bank (T1.4), COGS/inventory (T2.2), the VAT return (T1.5), and all of Reporting (T2.3 — the trial balance, P&L, balance sheet) post into / read from this engine. Today's `domain_events` carries `SALE.FINALISED`/`SALE.VOIDED` but the only registered consumers are the four Stock handlers — **on the finance side the events fire into the void** (ROADMAP §1 verdict). This increment turns the system from "tracks stock" into "keeps books."

Almost everything GL consumes already exists and ships unchanged: IAM gives the tenant spine + RBAC + `ScopeGuard` + audit; the **transactional outbox** (ADR-0009) gives `DomainEventHandler` + `IdempotencyGuard` + `processed_events` (GL is a pure **consumer**, DTO-only); `Money` (ADR-0005) gives amount+currency; `code_sequence` (ADR-0007) gives concurrency-safe `JOURNAL_BATCH` numbering. The central architectural force is therefore **mirror the proven outbox-consumer and per-company-master patterns; resolve only the genuinely new modelling questions a double-entry posting engine introduces**. Those new questions, and the forces around each:

- **Double-entry is a cross-row invariant a per-row CHECK cannot express.** `Σ debits == Σ credits` over an entry's lines is a *sum across siblings*; no single-row `CHECK` can see them. The DB enforces what it cheaply can per-line (each line nonneg, exactly one side); the **service** enforces the balance. The forces: where the grain sits (entry vs line), whether a line carries debit/credit columns or a signed amount, and exactly which invariant lands at DB vs service. Resolved in D-2/D-3.

- **The ledger is append-only — correction is a reversing entry, never an edit (PROJECT-CONVENTIONS §3.6, BR-GL-02).** A posted entry is immutable: no `UPDATE`, no `DELETE`. This is structural, not policy (NFR-GL-04). The new question is the **reversing-entry mechanic** (a `reversal_of` self-FK linking a reversal to what it negates) and whether there is a DRAFT state (gl.md chose reverse-only / posted-immutable — manual journals post directly). Resolved in D-3.

- **Posting must be gated by an OPEN fiscal period, with a configurable year-start month (BR-GL-03, FR-GL-14).** The entry date must fall in an OPEN period; a closed (or non-existent) period rejects the post. The new questions: the fiscal-year / period table shape, how the year-start month is configured, and **how periods are generated** (seed on company setup vs on-demand). Resolved in D-4.

- **The auto-poster must resolve accounts from config, never hard-coded codes (BR-GL-10, FR-GL-18).** `gl_configs` maps posting roles (SALES_REVENUE, VAT_PAYABLE, AR, CASH, …) to actual CoA accounts; a missing required mapping fails the event (retry/park) rather than mis-posting. The new question is the table **shape** (key/value vs columns) and the required-keys-before-posting rule. Resolved in D-5.

- **Sales auto-posting is the integration centerpiece — and the payload does NOT carry what GL needs.** Verified against the shipped `SaleFinalisedPayload`: it carries only `{ invoiceUid, companyId, branchId, finalisedAt, lines:[{ productId, productUid, unitId, qtyInBase }] }` — **no amounts, no customer, no currency, no sale-kind**. The `SaleIssueStockHandler` uses exactly those line fields for stock deduction; GL needs the *monetary* facts (net/vat/gross). So the `SalesPostingHandler` **must re-read `sales_invoices` by `invoiceUid` scoped to company** to get the totals — exactly as the `SaleReversalStockHandler` re-reads its own ledger by `source_document_uid` rather than trusting the void payload. The forces: re-read vs widen the payload; the cash-vs-credit determination; the closed-period policy. Resolved in D-6.

- **Books are kept at COMPANY level; branch is an analysis tag, not a separate ledger (NFR-GL-01, the analyst's note).** Every GL row is `company_id`-scoped; a posting may carry the originating `branch_id` for analysis (nullable), but the trial balance and statements are company-level. The new question is where `branch_id` lives (on the entry, on the line, or both) and that it is nullable. Resolved in D-2/D-3/D-7.

- **GL reads Sales but Sales must not depend on GL — and no module→module cycle may form (NFR-GL-07, `ModuleBoundaryTest`).** GL consumes `SALE.FINALISED`/`SALE.VOIDED` as event payloads, and re-reads invoice totals through a Sales **service-layer DTO** call (or a scalar-id projection), never importing a Sales entity. GL is a NEW **leaf consumer** — like Stock — with edges *into* `platform.events` and *into* `sales.domain.dto`/`SalesInvoiceService`, and no edge *back from* Sales to GL. Resolved in D-11/D-12.

- **Schema freeze / migration ordering.** IAM=V1, Parties=V2, Products=V3, Units=V4, Sales=V5, Outbox=V6, Stock=V7, Purchases=V8, Routes=V9 — all frozen and shipped. GL is a **new** module landing as a purely **additive `V10__general_ledger.sql`**; it must not edit V1–V9. It depends only on `companies`/`branches`/`app_users` (frozen V1) for its scope/audit FKs, and reads `sales_invoices` (frozen V5) by scalar-id projection at runtime (no FK into it — cross-module).

## Decision

### D-1 — Module placement: one `com.erp.modules.gl` module; controllers flat in `com.erp.api`

The GL module lives under **`com.erp.modules.gl`** with the standard internal layout. **`gl`, not `accounting.gl`:** the spec's dominant noun is "the General Ledger / the books"; the later financial modules (AR, AP, Cash, VAT return) are **sibling modules** that post *into* GL, not sub-packages *under* an `accounting` umbrella — naming this `accounting.gl` would imply a package hierarchy (`accounting.ar`, `accounting.ap`, …) that the flat `com.erp.modules.<name>` convention (PROJECT-CONVENTIONS §2) does not use, and that `ModuleBoundaryTest` reasons about as flat peers. `gl` is the durable, flat, boring name, consistent with `sales`/`stock`/`purchases`.

```
com.erp.modules.gl
├── domain.entity   ChartOfAccount, FiscalYear, FiscalPeriod, JournalBatch, JournalEntry,
│                   JournalLine, GlConfig
├── domain.dto      AccountDto, CreateAccountRequest, UpdateAccountRequest,
│                   JournalEntryDto, JournalLineDto, PostJournalRequest, PostJournalLineRequest,
│                   FiscalYearDto, FiscalPeriodDto, OpenFiscalYearRequest,
│                   GlConfigDto, SetGlConfigRequest, TrialBalanceRowDto, TrialBalanceDto,
│                   InvoicePostingTotalsDto  (the projection the SalesPostingHandler re-reads — D-6)
├── domain.enums    AccountType (ASSET|LIABILITY|EQUITY|INCOME|EXPENSE),
│                   NormalBalance (DEBIT|CREDIT),
│                   JournalSourceType (MANUAL|SALES|SALES_REVERSAL|OPENING_BALANCE;
│                                      reserved AR|AP|COGS|CASH|PAYROLL|DEPRECIATION — D-13),
│                   PeriodStatus (OPEN|CLOSED),
│                   GlConfigKey (SALES_REVENUE|VAT_PAYABLE|ACCOUNTS_RECEIVABLE|CASH;
│                                reserved INVENTORY|COGS|ACCOUNTS_PAYABLE — D-5/D-13)
├── repository      ChartOfAccountRepository, FiscalYearRepository, FiscalPeriodRepository,
│                   JournalBatchRepository, JournalEntryRepository, JournalLineRepository,
│                   GlConfigRepository
├── service         ChartOfAccountService(+Impl), FiscalPeriodService(+Impl),
│                   GlConfigService(+Impl),
│                   GLPostingService(+Impl)        — the engine (validate→balance→post, D-3),
│                   GLConfigResolver               — role→account lookup with required-keys check (D-5),
│                   FiscalPeriodResolver           — entry-date→OPEN-period resolution (D-4),
│                   JournalBatchNumberGenerator    — JB-#### via code_sequence (D-3),
│                   TrialBalanceQuery              — GROUP BY account read (D-8),
│                   SalesInvoicePostingReader      — re-reads sales_invoices via Sales DTO (D-6/D-12)
└── events          SalesPostingHandler            — SALE.FINALISED → balanced entry (D-6),
                    SaleVoidingHandler             — SALE.VOIDED → reversing entry (D-6)
```

Controllers stay flat in `com.erp.api` — `ChartOfAccountController`, `JournalController`, `FiscalPeriodController`, `GlConfigController`, `TrialBalanceController` — touching only services (PROJECT-CONVENTIONS §2; `ModuleBoundaryTest`). The two `events` handlers are GL beans implementing the `platform.events.DomainEventHandler` interface (the only cross-cutting coupling — D-11), exactly as the Stock handlers do.

### D-2 — The six table groups: `chart_of_accounts`, `fiscal_years` + `fiscal_periods`, `journal_batches` + `journal_entries` + `journal_lines`, `gl_configs`

All masters/logs plural per the shipped convention. Every table carries `company_id` (NFR-GL-01, BR-GL-05) and participates in the §3.2 tenant predicate. The **books are company-level**; `branch_id` is a nullable **analysis tag** on the entry and the line (D-7), never a separate ledger.

#### (a) `chart_of_accounts` (master, per company)

The account — the atomic unit of the books (FR-GL-01). Flat list grouped by numeric range; `account_type` is the authority for placement and normal balance (BR-GL-12).

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | NO | internal FK target (journal lines join on this) |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_chart_of_account_uid`; URLs address by uid; `ScopeGuard` resolves `case "account"` on this (D-10) |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope; **never updated** |
| `account_code` | `VARCHAR(20)` | NO | e.g. `1200`; **unique per company** (FR-GL-01); the numeric-range convention is data, not enforced (a 4-digit string, leading-zero safe) |
| `name` | `VARCHAR(160)` | NO | e.g. `Accounts Receivable` |
| `account_type` | `VARCHAR(20)` | NO | `ASSET`\|`LIABILITY`\|`EQUITY`\|`INCOME`\|`EXPENSE`; CHECK below; the authority for statement placement + normal balance (BR-GL-12) |
| `normal_balance` | `VARCHAR(10)` | NO | `DEBIT`\|`CREDIT`; **stored, derived-from-type at write** (see note); CHECK below |
| `parent_id` | `BIGINT` | **YES** | FK → `chart_of_accounts(id)` (self); **reserved for later grouping** — v1 keeps the CoA **flat** (gl.md §3.1 numeric-ranges, NOT hierarchical); the column exists so statement grouping is additive (see note); NULL in the v1 seed |
| `is_active` | `BOOLEAN` | NO | DEFAULT `true`; an inactive account is excluded from **new** postings (BR-GL-04) but stays on historical entries + the trial balance (FR-GL-03) |
| `status` | `VARCHAR(32)` | NO | `MasterStatus`; DEFAULT `'ACTIVE'`; the shipped master-status column (matches `tax_rates.status`); `is_active` is the posting gate, `status` the lifecycle |
| `version` | `BIGINT` | NO | optimistic lock, DEFAULT 0 |
| `created_at`/`created_by`/`updated_at`/`updated_by` | `TIMESTAMPTZ`/`BIGINT` | mixed | standard audit columns (`*_by` → `app_users.id`, no FK — mirrors `stock`/`sales` system-write pattern) |

**Constraints on `chart_of_accounts`:**
- `uq_chart_of_account_uid UNIQUE (uid)`.
- `uq_chart_of_account_company_code UNIQUE (company_id, account_code)` — code unique per company (FR-GL-01); the backstop for the add path.
- `fk_chart_of_account_company FOREIGN KEY (company_id) REFERENCES companies (id)`.
- `fk_chart_of_account_parent FOREIGN KEY (parent_id) REFERENCES chart_of_accounts (id)` (self; nullable; reserved).
- `chk_chart_of_account_type CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE'))`.
- `chk_chart_of_account_normal_balance CHECK (normal_balance IN ('DEBIT','CREDIT'))`.

**Indexes:**
```
CREATE INDEX ix_chart_of_accounts_company       ON chart_of_accounts (company_id);
CREATE INDEX ix_chart_of_accounts_company_type  ON chart_of_accounts (company_id, account_type);  -- statement grouping (P&L vs BS)
CREATE INDEX ix_chart_of_accounts_active        ON chart_of_accounts (company_id) WHERE is_active = true;  -- the account-picker working set
```

> **Store `normal_balance`, do not derive-only (the pick, with the backstop).** `normal_balance` is *deterministically derivable* from `account_type` (ASSET/EXPENSE → DEBIT; LIABILITY/EQUITY/INCOME → CREDIT, BR-GL-12). It is **stored** (a redundant column) **and** the service sets it from the type on create/edit, for three reasons: (1) the trial-balance / future statement reads sign by normal balance — storing it means those reads need no per-row `CASE account_type WHEN …` and the column is directly groupable/indexable; (2) it makes the row self-describing for ad-hoc SQL and exports; (3) the cost — a row could in principle disagree with its type — is contained by the service computing it (never user-entered) and a one-line consistency check in the account-create/edit test. v1 derives it; storing it costs one column and removes a derivation from every read path. (Alternative — derive-only — examined in Alternatives.)

> **`parent_id` is reserved, not used in v1.** gl.md §3.1 ratified a **flat** numeric-range CoA, NOT hierarchical. The self-FK column exists so that *if* statement sub-totalling later wants a grouping tree (e.g. "Current Assets" rolling up `1000–1199`), it is an additive populate, not a migration. The v1 seed leaves it NULL; no read uses it. This is the only speculative column in the model and it costs nothing (nullable, no index in v1).

#### (b) `fiscal_years` (master, per company) + `fiscal_periods` (child of the year)

The fiscal calendar (FR-GL-14): 12 monthly periods per year, configurable year-start month, OPEN/CLOSED per period.

##### `fiscal_years`

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_fiscal_year_uid` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope |
| `year_code` | `VARCHAR(12)` | NO | human label, e.g. `FY2026` or `2026/27`; unique per company |
| `start_month` | `SMALLINT` | NO | the configurable fiscal-year **start month** 1..12 (FR-GL-14; default 1 = January for TZ); CHECK `BETWEEN 1 AND 12` |
| `start_date` | `DATE` | NO | first day of period 1 (derived from start_month + the calendar year) |
| `end_date` | `DATE` | NO | last day of period 12 |
| `status` | `VARCHAR(20)` | NO | `OPEN`\|`CLOSED`; DEFAULT `'OPEN'`; year-level close (period-12 close yields the year-end state, FR-GL-15); CHECK below |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| audit cols | … | | standard |

- `uq_fiscal_year_uid UNIQUE (uid)`; `uq_fiscal_year_company_code UNIQUE (company_id, year_code)`.
- `fk_fiscal_year_company FOREIGN KEY (company_id) REFERENCES companies (id)`.
- `chk_fiscal_year_start_month CHECK (start_month BETWEEN 1 AND 12)`.
- `chk_fiscal_year_status CHECK (status IN ('OPEN','CLOSED'))`.
- `CREATE INDEX ix_fiscal_years_company ON fiscal_years (company_id);`

##### `fiscal_periods` (child of `fiscal_years`)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_fiscal_period_uid`; period open/close addresses by uid (D-10 `case "fiscalperiod"`) |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; denormalised from the year (tenant predicate without a join) |
| `fiscal_year_id` | `BIGINT` | NO | FK → `fiscal_years(id)`; the owning year |
| `period_no` | `SMALLINT` | NO | 1..12 within the year; CHECK `BETWEEN 1 AND 12` |
| `start_date` | `DATE` | NO | first day of the month-period |
| `end_date` | `DATE` | NO | last day of the month-period |
| `status` | `VARCHAR(20)` | NO | `OPEN`\|`CLOSED`; DEFAULT `'OPEN'`; **the posting gate** (BR-GL-03); CHECK below |
| `closed_at` | `TIMESTAMPTZ` | YES | set on close |
| `closed_by` | `BIGINT` | YES | FK → `app_users(id)`; who closed (FR-GL-15, audited) |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| audit cols | … | | standard |

- `uq_fiscal_period_uid UNIQUE (uid)`; `uq_fiscal_period_year_no UNIQUE (fiscal_year_id, period_no)` — one period N per year.
- `fk_fiscal_period_company FOREIGN KEY (company_id) REFERENCES companies (id)`; `fk_fiscal_period_year FOREIGN KEY (fiscal_year_id) REFERENCES fiscal_years (id)`; `fk_fiscal_period_closed_by FOREIGN KEY (closed_by) REFERENCES app_users (id)`.
- `chk_fiscal_period_no CHECK (period_no BETWEEN 1 AND 12)`.
- `chk_fiscal_period_status CHECK (status IN ('OPEN','CLOSED'))`.
- `chk_fiscal_period_dates CHECK (end_date >= start_date)`.
- Index: the entry-date→period resolution is the hot path —
  ```
  CREATE INDEX ix_fiscal_periods_company_dates ON fiscal_periods (company_id, start_date, end_date);
  CREATE INDEX ix_fiscal_periods_year          ON fiscal_periods (fiscal_year_id, period_no);
  ```

#### (c) `journal_batches` → `journal_entries` → `journal_lines` (the posting)

The three-level posting structure. A **batch** is the numbered container a posting run groups its entries under (`JB-####`); an **entry** is one balanced transaction (date + description + source + period); a **line** is one leg (one account, one side). v1 batches are usually one-entry (a manual post is one batch, a sales auto-post is one batch, a reversal is one batch — gl.md §2 glossary) but the batch grain is kept so a multi-entry posting run (e.g. a future payroll batch) is additive.

##### `journal_batches`

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_journal_batch_uid` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope |
| `branch_id` | `BIGINT` | **YES** | FK → `branches(id)`; originating branch as an **analysis tag** (nullable — a manual company-level journal has none; a sales auto-post carries the event's branch); NOT a separate ledger (NFR-GL-01) |
| `batch_number` | `VARCHAR(30)` | NO | `JB-####` from `code_sequence` `(company_id, 'JOURNAL_BATCH')` (D-3); unique per company |
| `source_type` | `VARCHAR(20)` | NO | `MANUAL`\|`SALES`\|`SALES_REVERSAL`\|`OPENING_BALANCE` (v1); reserved `AR`\|`AP`\|`COGS`\|`CASH`\|… (D-13); CHECK below |
| `description` | `VARCHAR(255)` | YES | batch-level note |
| `posted_at` | `TIMESTAMPTZ` | NO | DEFAULT `now()`; when the batch was posted (append-only — set once) |
| `posted_by` | `BIGINT` | YES | FK → `app_users(id)`; the operator (NULL for the SYSTEM auto-poster, FR-GL-19) |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| `created_at`/`created_by` | `TIMESTAMPTZ`/`BIGINT` | mixed | **no `updated_*`** — batches are append-only (BR-GL-02) |

- `uq_journal_batch_uid UNIQUE (uid)`; `uq_journal_batch_company_number UNIQUE (company_id, batch_number)`.
- `fk_journal_batch_company`, `fk_journal_batch_branch`, `fk_journal_batch_posted_by` (→ `app_users`).
- `chk_journal_batch_source_type CHECK (source_type IN ('MANUAL','SALES','SALES_REVERSAL','OPENING_BALANCE'))` — **v1 admits only these four**; widen the IN-list additively as later posters land (D-13), exactly as `chk_sales_invoice_doc_type` widens for POS/SO.
- `CREATE INDEX ix_journal_batches_company ON journal_batches (company_id);`

##### `journal_entries` (child of `journal_batches`)

One balanced transaction (BR-GL-01). The grain is the **ENTRY** (carries date + description + source + period), with `>= 2` LINES — confirmed below.

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | journal lines join on this |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_journal_entry_uid`; URLs address by uid (`ScopeGuard case "journalentry"`, D-10) |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope; denormalised from batch |
| `branch_id` | `BIGINT` | **YES** | FK → `branches(id)`; analysis tag, denormalised from batch (nullable) |
| `batch_id` | `BIGINT` | NO | FK → `journal_batches(id)`; the owning batch |
| `entry_no` | `SMALLINT` | NO | 1-based ordinal within the batch; `uq_journal_entry_batch_no` |
| `posting_date` | `DATE` | NO | the **business entry date** that drives period assignment (BR-GL-03, NFR-GL-08 — distinct from `posted_at` timestamp); must fall in an OPEN period (service, D-3) |
| `fiscal_period_id` | `BIGINT` | NO | FK → `fiscal_periods(id)`; the OPEN period the `posting_date` resolved to (set by the service at post — D-3/D-4); makes "all entries in period N" an index range |
| `description` | `VARCHAR(255)` | NO | the transaction narrative (FR-GL-06) |
| `source_type` | `VARCHAR(20)` | NO | mirrors the batch (`MANUAL`/`SALES`/`SALES_REVERSAL`/`OPENING_BALANCE`); on the entry too so a single-entry filter needs no batch join; CHECK below |
| `source_ref` | `VARCHAR(60)` | YES | the source document reference, e.g. the **`sales_invoices.uid`** for a SALES entry (D-6); the key the `SaleVoidingHandler` looks up the original by; NULL for a free manual journal |
| `reversal_of_id` | `BIGINT` | **YES** | FK → `journal_entries(id)` (self); set on a **reversing entry** to the entry it negates (BR-GL-02/BR-GL-11, D-3); NULL for an original entry |
| `posted_at` | `TIMESTAMPTZ` | NO | DEFAULT `now()`; posting timestamp (append-only) |
| `posted_by` | `BIGINT` | YES | FK → `app_users(id)`; NULL for SYSTEM auto-poster |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| `created_at`/`created_by` | `TIMESTAMPTZ`/`BIGINT` | mixed | **no `updated_*`** — entries are immutable (BR-GL-02) |

- `uq_journal_entry_uid UNIQUE (uid)`; `uq_journal_entry_batch_no UNIQUE (batch_id, entry_no)`.
- `fk_journal_entry_company`, `fk_journal_entry_branch`, `fk_journal_entry_batch` (→ `journal_batches`), `fk_journal_entry_period` (→ `fiscal_periods`), `fk_journal_entry_reversal_of` (→ `journal_entries`, self), `fk_journal_entry_posted_by` (→ `app_users`).
- `chk_journal_entry_source_type CHECK (source_type IN ('MANUAL','SALES','SALES_REVERSAL','OPENING_BALANCE'))`.
- **Idempotency / re-post guard for SALES entries (the DB backstop, mirroring `uq_stock_movement_source_event`):**
  `uq_journal_entry_sales_source UNIQUE (company_id, source_type, source_ref) WHERE source_type IN ('SALES','SALES_REVERSAL')` — a **partial unique** so the many `MANUAL`/`OPENING_BALANCE` entries (which may share a NULL `source_ref`) coexist, but a second `SALES` entry for the same invoice uid in the same company is rejected at the DB even if the `processed_events` marker were somehow bypassed. This is the inventory-grade backstop the brief calls for, identical in spirit to the Stock `(source_event_uid, product_id)` partial unique.
- Indexes:
  ```
  CREATE INDEX ix_journal_entries_company_period ON journal_entries (company_id, fiscal_period_id);  -- TB / statements by period
  CREATE INDEX ix_journal_entries_company_date   ON journal_entries (company_id, posting_date);       -- TB as-at-date
  CREATE INDEX ix_journal_entries_source         ON journal_entries (company_id, source_type, source_ref);  -- SaleVoidingHandler lookup (D-6)
  CREATE INDEX ix_journal_entries_reversal_of    ON journal_entries (reversal_of_id) WHERE reversal_of_id IS NOT NULL;  -- "has this been reversed?"
  ```

##### `journal_lines` (child of `journal_entries`)

One leg of an entry: exactly one account, a debit OR a credit, both positive (BR-GL-08). **Debit/credit are two columns, exactly one nonzero** — not a single signed amount (justified in Alternatives).

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_journal_line_uid` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; denormalised from entry (tenant predicate without a join — the TB scans lines) |
| `branch_id` | `BIGINT` | **YES** | FK → `branches(id)`; analysis tag, denormalised from entry; **branch-level analytics group on the line** (a branch P&L is `journal_lines` grouped by `branch_id` × account type) |
| `entry_id` | `BIGINT` | NO | FK → `journal_entries(id)`; the owning entry |
| `line_no` | `SMALLINT` | NO | 1-based ordinal within the entry; `uq_journal_line_entry_no` |
| `account_id` | `BIGINT` | NO | FK → `chart_of_accounts(id)`; the account this leg hits (an **active** account at post time — BR-GL-04, service) |
| `debit_amount` | `NUMERIC(19,4)` | NO | DEFAULT 0; the debit leg (0 if this is a credit line) — `Money` amount, base currency |
| `credit_amount` | `NUMERIC(19,4)` | NO | DEFAULT 0; the credit leg (0 if this is a debit line) |
| `currency` | `VARCHAR(3)` | NO | the posting currency = company base currency (BR-GL-06, D-9); the `Money` embeddable currency; service asserts all lines in an entry share it |
| `line_memo` | `VARCHAR(255)` | YES | optional per-line narrative |
| `created_at`/`created_by` | `TIMESTAMPTZ`/`BIGINT` | mixed | **no `updated_*`** — lines are immutable (BR-GL-02) |

- `uq_journal_line_uid UNIQUE (uid)`; `uq_journal_line_entry_no UNIQUE (entry_id, line_no)`.
- `fk_journal_line_company`, `fk_journal_line_branch`, `fk_journal_line_entry` (→ `journal_entries`), `fk_journal_line_account` (→ `chart_of_accounts`).
- **`chk_journal_line_one_side CHECK ((debit_amount > 0 AND credit_amount = 0) OR (credit_amount > 0 AND debit_amount = 0))`** — exactly one side, both nonneg, never both/neither (BR-GL-08); the single-row half of the double-entry invariant the DB *can* express.
- `chk_journal_line_nonneg CHECK (debit_amount >= 0 AND credit_amount >= 0)` (belt-and-braces; the one-side CHECK already implies it, but it documents intent and guards a future relaxation).
- Indexes (the trial balance is `journal_lines GROUP BY account_id SUM(debit)−SUM(credit)`, scoped):
  ```
  CREATE INDEX ix_journal_lines_company_account ON journal_lines (company_id, account_id);          -- THE trial-balance index
  CREATE INDEX ix_journal_lines_entry           ON journal_lines (entry_id);                          -- entry → its lines
  CREATE INDEX ix_journal_lines_company_branch  ON journal_lines (company_id, branch_id);             -- branch-level analysis
  ```

> **Note — the TB index is the read hot path.** `ix_journal_lines_company_account (company_id, account_id)` covers the per-company `GROUP BY account_id` aggregate the trial balance (D-8) and every future statement performs. For an as-at-date TB the join to `journal_entries.posting_date` uses `ix_journal_entries_company_date`. No materialised view in v1 (D-8).

#### (d) `gl_configs` (account mapping, per company) — key/value

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_gl_config_uid` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope |
| `config_key` | `VARCHAR(40)` | NO | a `GlConfigKey`: `SALES_REVENUE`\|`VAT_PAYABLE`\|`ACCOUNTS_RECEIVABLE`\|`CASH` (v1 active); `INVENTORY`\|`COGS`\|`ACCOUNTS_PAYABLE` (reserved/seeded for later — D-13); CHECK below |
| `account_id` | `BIGINT` | NO | FK → `chart_of_accounts(id)`; the mapped account (must be active when the poster reads it — BR-GL-10, service) |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| audit cols | … | | standard |

- `uq_gl_config_uid UNIQUE (uid)`; `uq_gl_config_company_key UNIQUE (company_id, config_key)` — one account per role per company.
- `fk_gl_config_company`, `fk_gl_config_account` (→ `chart_of_accounts`).
- `chk_gl_config_key CHECK (config_key IN ('SALES_REVENUE','VAT_PAYABLE','ACCOUNTS_RECEIVABLE','CASH','INVENTORY','COGS','ACCOUNTS_PAYABLE'))` — the v1-active + reserved keys are **all** admitted by the CHECK (they are seeded, just not yet *posted to* by a live handler — D-13); a future role (e.g. `BANK_CHARGES`) widens the IN-list additively.
- `CREATE INDEX ix_gl_configs_company ON gl_configs (company_id);`

> **Key/value over columnar (the pick).** A `gl_configs (company_id, config_key, account_id)` key/value table is chosen over a wide `gl_account_map(sales_revenue_account_id, vat_payable_account_id, …)` columnar row because **every later increment adds roles** (AR, AP, Cash, COGS, Inventory, payroll clearing, FX gain/loss, …): a key/value row is an additive `INSERT` + a one-line CHECK widen, whereas a columnar table needs an `ALTER TABLE ADD COLUMN` per role for the life of the ERP. The required-keys-before-posting rule (D-5) reads cleanly off keyed rows. (Examined in Alternatives.)

### D-3 — The posting engine: grain = ENTRY with ≥2 LINES; debit/credit columns; balance enforced in SERVICE; append-only; reversing-entry via `reversal_of_id`

**Grain (ratified shape).** A **journal entry** is the unit of posting: it carries `posting_date` + `description` + `source_type`/`source_ref` + `fiscal_period_id`, and owns **≥ 2 `journal_lines`** (BR-GL-01). A **batch** groups one-or-more entries under a `JB-####` number; v1 posts one entry per batch in every path but the grain is kept (D-2c). This is the textbook double-entry shape and the boring choice.

**Line amount form — two columns (`debit_amount`, `credit_amount`), exactly one nonzero.** Chosen over a single signed amount (Alternatives) because: (1) it matches accounting vocabulary and the printed journal (a line *is* a debit or a credit, not a signed number a reader must interpret by account type); (2) the single-row invariant "exactly one side, both nonneg" is a clean DB `CHECK` (`chk_journal_line_one_side`); (3) the trial balance is the natural `SUM(debit_amount) − SUM(credit_amount)` the spec states verbatim (FR-GL-16, ROADMAP §4) — no sign convention to remember.

**The double-entry enforcement split (DB-can't / service-must) — the load-bearing table:**

| invariant | enforcement | mechanism |
| --- | --- | --- |
| BR-GL-08 line is one account, one side, both positive | **DB CHECK** | `chk_journal_line_one_side` + `chk_journal_line_nonneg` (single-row — the DB *can* see it) |
| BR-GL-01 entry has ≥ 2 lines | **service** | `GLPostingService` rejects an entry with < 2 lines (a count across siblings — no single-row CHECK sees it) |
| BR-GL-01 Σ debits == Σ credits (balanced-or-rejected) | **service** | `GLPostingService` sums lines, rejects if `Σ debit_amount != Σ credit_amount` (`BigDecimal` value compare, NFR-GL-02 — a cross-row sum no CHECK can express) |
| BR-GL-03 posting_date in an OPEN period | **service** | `FiscalPeriodResolver` finds the period covering `posting_date`; rejects if none or CLOSED; sets `fiscal_period_id` |
| BR-GL-04 every account active | **service** | each line's `account_id` checked active at post (a since-deactivated account on a *historical* line stands) |
| BR-GL-05 one company's books isolated | **DB + service** | `company_id` NOT NULL + FK on every table; tenant predicate; `assertCanActIn` on every read path |
| BR-GL-06 base-currency-only | **service** | every line `currency` == company base (D-9); cross-currency rejected |
| BR-GL-02 append-only / immutable | **structural** | no `updated_*` columns on batch/entry/line; no service update/delete path; correction = reversing entry |
| BR-GL-07 account with postings cannot be deleted | **service** | `ChartOfAccountService.delete` rejects if any `journal_lines.account_id` references it (else deactivate); a no-postings account may be deleted |
| BR-GL-10 required gl_configs mapped before auto-posting | **service** | `GLConfigResolver` resolves required keys; a missing/inactive mapping throws → the event fails/retries (D-5/D-6) |
| BR-GL-09 idempotent auto-posting | **service + DB backstop** | `IdempotencyGuard` `processed_events(consumer, event_uid)` (primary) + `uq_journal_entry_sales_source` partial unique (backstop) |
| journal batch number unique/concurrency-safe | **DB + service** | `code_sequence (company_id,'JOURNAL_BATCH')` row-lock allocation (NFR-GL-05) + `uq_journal_batch_company_number` backstop |

**`GLPostingService.post(...)` — the single engine both manual and automatic posting go through (gl.md §3.2: "the same posting engine and the same invariants"):**
1. Resolve the OPEN `fiscal_period_id` for `posting_date` via `FiscalPeriodResolver` — reject if none/CLOSED (BR-GL-03).
2. Validate `>= 2` lines; each line one-sided (DB CHECK is the backstop, service validates for a clean error); each `account_id` **active** + same company (BR-GL-04/05); each `currency` == base (BR-GL-06).
3. Compute `Σ debit_amount` and `Σ credit_amount`; reject if unequal (`BigDecimal.compareTo == 0`, NFR-GL-02) — **nothing partial is written** (BR-GL-01).
4. Allocate `JB-####` via `JournalBatchNumberGenerator.next(companyId)` (`SELECT … FOR UPDATE` on the `code_sequence` row, identical to `InvoiceNumberGenerator`, ADR-0007 D-6 / ADR-0008 D-7), inside the post TX.
5. Insert the `journal_batch` + `journal_entry` + `journal_lines` (one TX).
6. Emit the `GL.JOURNAL.POST` audit row (D-14).

**Manual journals post directly — NO draft state (gl.md ratification confirmed).** gl.md §3.2 chose reverse-only / posted-immutable; manual journals post directly. There is **no `DRAFT` journal status** and no draft table — validation is a transient pre-post check in `GLPostingService` (steps 1–3); a journal either posts (balanced, open period, active accounts) or is rejected with a clear message. A composer screen holds the unposted lines client-side (or in a request DTO) until `POST`; nothing unbalanced or unposted is ever persisted to the ledger. (If a future round wants saved-draft journals, that is an additive DRAFT-status slice under its own ADR; v1 deliberately has none — matching the append-only spirit.)

**Append-only + the reversing-entry mechanic (BR-GL-02/BR-GL-11).** A posted entry is immutable (no `updated_*`, no update/delete path). The **only** correction is a **reversing entry**: a new `journal_entry` whose lines swap the original's debits and credits (each original `debit_amount` becomes a `credit_amount` and vice versa), with `reversal_of_id` = the original entry's id and `source_type = SALES_REVERSAL` (for a sale void) or `MANUAL` (for a manual correction). Because it swaps an already-balanced entry, the reversal is **balanced by construction** (BR-GL-11); it posts into an OPEN period (typically the void/correction date's period — BR-GL-03 still applies). The original entry stays on the books beside its reversal; their net effect on every account is zero. `reversal_of_id` makes "has this entry been reversed?" a keyed lookup (`ix_journal_entries_reversal_of`) and links the audit trail.

### D-4 — Fiscal year / period generation: seeded on company setup (Java seeder) + on-demand next-year open; entry-date→period resolution

**Generation — seed the *current* fiscal year on company setup; open subsequent years on demand (the recommendation).**
- **On company setup**, a `FiscalCalendarSeeder` (called from `BootstrapRunner` for existing companies and from `CompanyService.create` for new ones — the exact `UnitOfMeasureSeeder`/`TaxRateSeeder` precedent, ADR-0008 D-5) creates the company's **current** `fiscal_year` (using the configured `start_month`, default 1=January per gl.md §9) and its **12 `fiscal_periods`**, all `OPEN`. This guarantees a brand-new company can post immediately (the sales auto-post on day one lands in an open period).
- **The next year is opened on demand** by an `OpenFiscalYear` operation (`GL.PERIOD.CLOSE` permission — opening a year is the same finance authority as closing a period) that creates the next `fiscal_year` + its 12 periods. Recommended trigger: explicit open by finance (boring, predictable); an *automatic* roll on period-12 close is the deferred year-end-close automation (OQ-GL-03, §10.6) — **not** built here. v1 also tolerates an on-demand auto-create if a `posting_date` falls in a not-yet-opened future year only if the owner opts in (flagged); the recommended default is **explicit open**, so a stray future-dated entry is rejected ("no open period for date") rather than silently spawning a year.

**The V10 migration seeds the current fiscal year for every existing company** (CROSS JOIN with `generate_series(1,12)` for the periods), the same seed-per-company pattern V5 used for `tax_rates`. Deterministic seed-uids (`'SEED' || …`) per the shipped pattern.

**Entry-date→period resolution (`FiscalPeriodResolver`).** Given `(companyId, postingDate)`, find the `fiscal_periods` row where `postingDate BETWEEN start_date AND end_date` and `status = 'OPEN'` (hits `ix_fiscal_periods_company_dates`). None → reject "no open period for date" (covers both *closed* and *non-existent* — BR-GL-03). Exactly one (the unique non-overlapping monthly periods guarantee it). This resolver is called by `GLPostingService` for both manual and automatic posts.

### D-5 — `gl_configs`: key/value shape + the required-keys-before-posting rule

`gl_configs` (D-2d) maps a posting **role** to a CoA **account**, per company. The `GLConfigResolver` service resolves a `GlConfigKey` to an `account_id`, asserting the account is **active**.

**Required-keys-before-posting (BR-GL-10, FR-GL-18).** Sales auto-posting requires, per sale kind:
- **Always:** `SALES_REVENUE`, `VAT_PAYABLE`.
- **Cash sale:** `CASH` (the debit side).
- **Credit sale (when credit lands — deferred):** `ACCOUNTS_RECEIVABLE` (the debit side).

If a **required** key is unmapped (no `gl_configs` row) or maps to an **inactive** account when a `SALE.FINALISED` is processed, `GLConfigResolver` **throws** → the handler fails the event → the outbox **retries / parks FAILED** after the cap (ADR-0009 D-4/D-8). Finance then sets the mapping (`GL.MANAGE`) and the parked event is replayed — the sale posts. **No silent post to a null/wrong account** (BR-GL-10). The reserved keys (`INVENTORY`, `COGS`, `ACCOUNTS_PAYABLE`) are **seeded** (D-13) but are **not required** by any v1 handler — they become required when their increments (T2.2/T1.3) build the posters that read them.

### D-6 — Sales auto-posting: `SalesPostingHandler` (SALE.FINALISED) + `SaleVoidingHandler` (SALE.VOIDED) — the integration centerpiece

Both handlers are GL beans in `com.erp.modules.gl.events` implementing `platform.events.DomainEventHandler`, **`@Transactional(propagation = MANDATORY)`**, mirroring `SaleIssueStockHandler`/`SaleReversalStockHandler` **exactly** (the shipped templates): primary dedup via `IdempotencyGuard.alreadyProcessed`, system `RequestContext.Principal` set from the event's `companyId`/`branchId` (save/restore the previous principal in a `finally`), effect applied, `IdempotencyGuard.markProcessed` in the **same TX**.

#### What the handler reads — RE-READ `sales_invoices`, do NOT widen the payload (the verified decision)

The shipped `SaleFinalisedPayload` carries `{ invoiceUid, companyId, branchId, finalisedAt, lines:[{ productId, productUid, unitId, qtyInBase }] }` — **the per-line stock quantities, NOT the monetary totals**. GL needs `net_total_amount` / `vat_total_amount` / `gross_total_amount` / `currency` / `customer_id` (the sale-kind signal) — none of which the payload carries. **Decision: the `SalesPostingHandler` re-reads the invoice by `invoiceUid`, scoped to `event.getCompanyId()`**, via a Sales **service-layer DTO** call (NFR-GL-07, D-12) — it does **not** widen the `SALE.FINALISED` payload, and does **not** import a Sales entity. Rationale:
1. **It mirrors the shipped pattern.** `SaleReversalStockHandler` deliberately re-reads its own ledger by `source_document_uid` rather than trusting the void payload's lines ("robust to recipe explosion … robust to non-stockable skips"). Re-reading the authoritative source at handle time is the established robustness pattern.
2. **It does not couple GL's needs into the Sales event contract.** The payload is shared by Stock (which needs the line quantities) and would have to grow GL's monetary fields, customer, and currency — bloating a contract Stock does not use and re-litigating ADR-0008 D-9's fixed payload. Re-reading keeps the event a thin notification and each consumer reads what *it* needs.
3. **The invoice is immutable once finalised** (ADR-0008 D-7 — totals frozen at finalise), so re-reading is deterministic: the figures GL reads are exactly the figures the sale finalised with; there is no race.

**The read goes through `SalesInvoiceService` returning a DTO** — concretely, a new projection method the GL module calls: `Optional<InvoicePostingTotalsDto> findPostingTotalsByUidAndCompany(String invoiceUid, Long companyId)` exposing `{ invoiceUid, status, currency, customerId (or a customerKind/isCash flag — see cash-vs-credit below), netTotalAmount, vatTotalAmount, grossTotalAmount, finalisedAt }`. This is a **Sales-owned DTO** in `sales.domain.dto`, returned by a `SalesInvoiceService` method; GL depends on the Sales **service interface + DTO**, not its entity (D-12). The implementation is a single scalar projection on `SalesInvoiceRepository` (the `findCompanyIdByUid` precedent), company-scoped so no cross-tenant read is possible. If the invoice is not found, or not `FINALISED`, the handler records an anomaly (a finalised event for a non-finalised/absent invoice is an out-of-order/data anomaly) and still marks processed — mirroring the Stock anomaly path.

#### The posted entry (FR-GL-10) — balanced by construction (net + vat == gross)

For a `SALE.FINALISED`, `SalesPostingHandler` resolves accounts via `GLConfigResolver` and posts ONE balanced entry through `GLPostingService` (one batch, `source_type = SALES`, `source_ref = invoiceUid`, `posting_date` = the finalise date — see closed-period policy):

```
DR  <AR or CASH>        gross_total_amount     (the debit side — cash-vs-credit rule below)
    CR  Sales Revenue       net_total_amount
    CR  VAT Payable         vat_total_amount
```

`net + vat == gross` (ADR-0008 D-4 computes them so), so `Σ debit == Σ credit` by construction (BR-GL-01). If `vat_total_amount == 0` (a fully zero-rated/exempt sale) the VAT line is **omitted** (a zero-amount line would violate `chk_journal_line_one_side`); the entry is then a 2-line DR cash/AR / CR revenue — still balanced, still ≥ 2 lines.

#### Cash-vs-credit determination (OQ-GL-02 — recommended rule)

gl.md §9 / OQ-GL-02: **v1 sales are paid-in-full at finalise (cash); credit sales are deferred in Sales.** Recommended rule for the handler:
- **v1 default: DR `CASH`** for the gross. Every v1 finalised invoice is fully tendered (ADR-0008 D-8 — paid-in-full at finalise, no AR state), so the live posting is always the cash side. The `ACCOUNTS_RECEIVABLE` mapping role is **seeded from day one** so the credit path is purely additive.
- **The signal, when credit lands:** the handler asks the invoice DTO for a sale-kind flag. Until Sales models credit (a real outstanding-balance state — ADR-0008 D-8 reserves it), there is no credit invoice to post, so the DTO returns "cash" (paid-in-full) and the handler DRs `CASH`. When the AR increment (T1.2) adds the credit-sale state to Sales, the DTO's flag distinguishes them and the handler DRs `ACCOUNTS_RECEIVABLE` for a credit sale — **no GL schema change, no handler-structure change**, just the branch on the flag becoming live. Documented so the AR increment is additive.

#### Closed-period policy for an auto-post (OQ-GL-01 — recommended default)

`posting_date` = the invoice **finalise date** (`finalisedAt` → its date). Its period must be OPEN (BR-GL-03 applies to automatic posting too). **Recommended default: fail-and-retry** — if the finalise date's period is CLOSED (a late/replayed event), `GLPostingService` rejects, the handler throws, the outbox retries and parks FAILED after the cap; finance reopens the period (or applies a future configurable policy) and replays. **No sale ever posts to a closed period.** The alternative ("post to the next open period") is a non-blocking, additive, configurable policy (OQ-GL-01) — not built in v1.

#### `SaleVoidingHandler` (SALE.VOIDED) — the reversing entry

`SaleVoidedPayload` carries `{ invoiceUid, companyId, branchId }`. The handler:
1. `IdempotencyGuard.alreadyProcessed("GL.SALES_VOID", event.uid)` → no-op if already done.
2. **Looks up the original SALES entry** by `(company_id, source_type = SALES, source_ref = invoiceUid)` on `JournalEntryRepository` (hits `ix_journal_entries_source`) — exactly as `SaleReversalStockHandler` looks up by `source_document_uid`.
3. **If found:** post the **reversing entry** through `GLPostingService` — a new batch (`source_type = SALES_REVERSAL`, `source_ref = invoiceUid`, `reversal_of_id` = the original entry id, `posting_date` = the void date) whose lines **swap** the original's debits/credits (original DR AR/Cash → CR; original CR Revenue/VAT → DR). Balanced by construction (BR-GL-11). Posts into the void date's OPEN period (closed → fail-and-retry, as above).
4. **If NOT found** (a void for a sale never posted — out-of-order/anomaly, FR-GL-12 / mirrors OQ-STOCK-10): **record an anomaly** (WARN log/metric), **do not** post a phantom reversal, and **still `markProcessed`** so the void is not re-attempted — verbatim the `SaleReversalStockHandler` anomaly path.
5. `markProcessed` in the same TX.

#### Idempotency consumer keys (BR-GL-09, the single biggest correctness risk — NFR-GL-03)

- `SalesPostingHandler` → consumer marker **`GL.SALES_POST`**.
- `SaleVoidingHandler` → consumer marker **`GL.SALES_VOID`**.

Distinct consumers so each dedupes independently on its own progress (ADR-0009 D-6). A redelivered `SALE.FINALISED` posts no second entry; a redelivered `SALE.VOIDED` posts no second reversal. The `processed_events(consumer, event_uid)` marker is written in the same TX as the entry (the atomicity that makes apply-once safe), backed by the `uq_journal_entry_sales_source` partial-unique DB backstop (D-2c). **An integration test must deliver the same `SALE.FINALISED` twice and assert the books move once** (NFR-GL-03 — a release-blocker if violated).

### D-7 — Branch as an analysis tag, not a ledger (NFR-GL-01)

`branch_id` is carried (nullable) on `journal_batches`, `journal_entries`, and `journal_lines` — denormalised down from the batch so a branch P&L is `journal_lines` grouped by `branch_id` × account type without a multi-level join. It is **nullable** because a manual company-level journal (an accrual, an opening balance) has no single originating branch. **The books are kept at COMPANY level**: the trial balance, P&L, and balance sheet are company-scoped reads; `branch_id` is an *analysis dimension*, never a separate set of books (a branch's debits and credits are not independently required to balance in v1). For a sales auto-post, the handler sets `branch_id` = the event's branch (the sale's branch); for a manual journal, the composer may set it or leave it NULL.

### D-8 — Trial balance is a READ (query), not a table (FR-GL-16)

The trial balance is computed on demand, **not** stored: `SELECT account_id, SUM(debit_amount) AS total_debit, SUM(credit_amount) AS total_credit, SUM(debit_amount) − SUM(credit_amount) AS net FROM journal_lines WHERE company_id = :c [AND entry's posting_date <= :asAt | within :period] GROUP BY account_id`, joined to `chart_of_accounts` for code/name/type. Implemented as `TrialBalanceQuery` (a service over `JournalLineRepository`, native or JPQL with the period/date filter joining `journal_entries`), returning `TrialBalanceDto { rows: [TrialBalanceRowDto{accountUid, code, name, type, totalDebit, totalCredit, net}], totalDebits, totalCredits }`. A correct set of books yields `totalDebits == totalCredits` (the acceptance bar — the TB nets to zero). **No materialised view, no `reporting_snapshots` table in v1** — the `ix_journal_lines_company_account` index makes the aggregate cheap at QA scale; a materialised/snapshot view is the Reporting increment's (T2.3) additive call if volume ever warrants it (NFR-GL-09 — not precluded). The as-at-date / over-period filter is on `journal_entries.posting_date` (the business date, NFR-GL-08), not `posted_at`.

### D-9 — Base-currency-only posting (BR-GL-06) — and the unbuilt `company.base_currency` column

GL posts in the **company base currency** only (BR-GL-06, ADR-0005 D-4). Every `journal_line.currency` is the base currency; a foreign-currency source is converted at entry (identity in practice — v1 sales document currency = base, sales.md §9). FX revaluation is deferred (§10.5).

**Finding the engineer must act on: `companies` has NO `base_currency` column.** ADR-0005 D-4 *designed* a base-currency config column on `company` but it was **never built** (verified: no `base_currency` in any shipped migration V1–V9; `companies` has `time_zone`, `status`, no currency). Sales/Stock/Purchases have not needed it (each document carries its own `currency`, = base in practice). GL is the **first module that posts a company-level base-currency amount** and must resolve a currency for each line. **Decision — the boring, additive resolution:**
- **v1 (no schema change beyond GL): the `SalesPostingHandler` posts each line in the invoice's `currency`** (re-read from `sales_invoices.currency`, D-6), which IS the base currency in practice (sales.md §9, single-currency reality). A **manual journal** posts in a currency the composer supplies, which the service asserts equals the company base — and since there is no stored base yet, v1 asserts only that **all lines in an entry share one currency** and that it matches any other posted entry's currency for that company (first-post-sets-the-base, consistency-enforced).
- **The clean fix is to build `ADR-0005 D-4`'s column**: add `companies.base_currency VARCHAR(3) NOT NULL DEFAULT 'TZS'` and seed it. **This is a one-column additive ALTER on the frozen `companies` table — exactly the kind of cross-module additive touch ADR-0008 D-5 made for `products.vat_status`.** Recommendation: **V10 adds `companies.base_currency` (additive ALTER + seed 'TZS' for existing rows, per ADR-0005 D-4's reserved design)**, and `GLPostingService` asserts every line's currency equals `companies.base_currency` (BR-GL-06 properly enforced, no "magic literal", ADR-0005 D-4). This closes ADR-0005 D-4's reserved seam in the first module that needs it, the same way V5 closed ADR-0007's `vat_status` seam. (Flagged as the recommended approach; if the owner prefers to defer the column, the first-post-sets-the-base consistency check is the fallback — but the column is the right home for the rule ADR-0005 already ratified.)

### D-10 — ScopeGuard additions: `case "account"` (+ `fiscalperiod`, `journalentry`)

`ScopeGuard.companyIdOf` (the security spine — [ScopeGuard.java](../../backend/src/main/java/com/erp/platform/security/ScopeGuard.java):102) gains the GL target types so 2-arg `@PreAuthorize` gates resolve a GL uid to its company:

```java
case "account"        -> chartOfAccounts.findCompanyIdByUid(uid);
case "fiscalperiod"   -> fiscalPeriods.findCompanyIdByUid(uid);
case "journalentry"   -> journalEntries.findCompanyIdByUid(uid);
case "glconfig"       -> glConfigs.findCompanyIdByUid(uid);
```

Each backed by a single-column projection (`@Query("SELECT x.companyId FROM … WHERE x.uid = :uid")`) on the respective repository, mirroring the eight existing cases. `ScopeGuard` gains four GL repository constructor dependencies — the same cross-cutting-spine pattern already accepted for the sales/stock/purchases/routes repositories (ArchUnit-allowed). **Not optional** — without `case "account"` the CoA-edit/deactivate gates fail closed. `fiscalperiod` is needed for the period open/close gate; `journalentry` for reading a posted entry by uid; `glconfig` for the mapping-edit gate. `assertCanActIn` is called on **every read path** (NFR-GL-01): trial-balance reads, CoA list, journal list — all resolve the active company from `RequestContext` and assert before returning. The SYSTEM auto-poster runs under no user permission (FR-GL-19) but is bounded by the event's company/branch context (D-6).

### D-11 — Module boundary: GL is a NEW leaf consumer (like Stock); the GL→Sales read direction; no cycle

`ModuleBoundaryTest` discipline (PROJECT-CONVENTIONS §2, NFR-GL-07):
- **GL → `platform.events`** (depends on `DomainEventHandler`, `DomainEvent`, `IdempotencyGuard`, `DomainEventType`) — the platform cross-cutting edge, identical to Stock's. `platform.events` is on the allow-list.
- **GL → `sales.domain.dto` + `SalesInvoiceService`** (reads the invoice posting totals via a DTO method — D-6/D-12) — a module→module **read** edge, **DTO/service-interface only, scalar-id**, never a Sales entity or repository import. This is the **same** edge `SaleIssueStockHandler` already has (`stock` → `sales.domain.dto.SaleFinalisedPayload` + `products.domain.dto`/`ProductService`); it is established and ArchUnit-accepted.
- **No edge back from Sales to GL** — Sales emits `SALE.FINALISED`/`SALE.VOIDED` and is done (it already does, ADR-0009 D-3); it does not know GL exists. **No cycle forms.** GL is a pure leaf consumer/reader, exactly like Stock.
- GL persists cross-module references as **scalar `Long`/`String` columns** (`source_ref` = invoice uid; `gl_configs.account_id` is *intra*-module): there is **no FK from any GL table into `sales_invoices`** (cross-module — the same no-cross-module-FK discipline `stock_movements.source_document_uid` and `domain_events.aggregate_id` use). `source_ref` is a plain `VARCHAR(60)`, not an FK.

### D-12 — The Sales read contract: a new `SalesInvoiceService` DTO method (the only Sales touch)

GL's re-read (D-6) needs one **additive** Sales-module method — no Sales schema change, no Sales contract (REST) change:
- `SalesInvoiceService.findPostingTotalsByUidAndCompany(String invoiceUid, Long companyId) : Optional<InvoicePostingTotalsDto>` returning `{ invoiceUid, status, currency, customerId, isCashSale (derived — paid-in-full → true in v1), netTotalAmount, vatTotalAmount, grossTotalAmount, finalisedAt }`.
- Backed by a projection on `SalesInvoiceRepository` (the `findCompanyIdByUid` precedent), **company-scoped** in the query so no cross-tenant read is possible.
- `InvoicePostingTotalsDto` is **Sales-owned** (`sales.domain.dto`) — GL imports the DTO, not the entity. This is a localised Sales addition (one service method + one DTO + one projection), recorded here and executed under the GL build; it is the GL analogue of how Stock consumes `ProductService`/`ProductDto`.

### D-13 — DEFERRED hooks: reserved enum values + seeded gl_configs keys, designed-for-but-not-built

The v1 model must not preclude the later posters (NFR-GL-09). Reserved, **not built**:
- **`JournalSourceType`** reserves `AR`, `AP`, `COGS`, `CASH` (and `PAYROLL`, `DEPRECIATION` for Tier 3) — **NOT** in the v1 CHECK IN-list (`chk_journal_batch_source_type` / `chk_journal_entry_source_type` admit only `MANUAL`/`SALES`/`SALES_REVERSAL`/`OPENING_BALANCE`); each later increment widens the CHECK with a one-line additive ALTER when its poster lands (the `chk_sales_invoice_doc_type` precedent).
- **`gl_configs` keys** `INVENTORY`, `COGS`, `ACCOUNTS_PAYABLE` are **admitted by `chk_gl_config_key` AND seeded** (D-15) so the mappings exist from day one, but **no v1 handler reads them as required** (D-5). They become required when:
  - **AR control posting (T1.2):** `ArPaymentRecordedHandler` (or GL consuming `AR.PAYMENT.RECORDED`) posts `DR Cash / CR AR`; `ACCOUNTS_RECEIVABLE` becomes a live debit on a credit sale (D-6 cash-vs-credit flag goes live).
  - **AP control posting (T1.3):** an AP bill-posted handler posts `DR Inventory(or expense) / CR AP`; `ACCOUNTS_PAYABLE` + `INVENTORY` go live.
  - **COGS / inventory posting (T2.2):** a `StockValuationPostingHandler` consumes a valuation event and posts `DR COGS / CR Inventory`; `COGS` + `INVENTORY` go live. (DEFERRED dependency on stock valuation — stock is quantity-only today, ADR-0010 §10.)
  - **Cash/Bank posting (T1.4):** the `CASH` role (already live for v1 cash sales) extends to a full cash/bank module.
- **Each lands as a new GL `events` handler** (a sibling of `SalesPostingHandler`) consuming a new event type, posting through the **same `GLPostingService`** — additive, not a rework. The engine is built for many posters; v1 wires only the sales poster.

### D-14 — Permission catalogue + audit emit points (seeded in V10, module `gl`)

**Permissions (FR-GL-19, seeded in V10, granted to `ORG_ADMIN` by the V7 CROSS-JOIN pattern):**

| code | module | description |
| --- | --- | --- |
| `GL.VIEW` | gl | View chart of accounts, posted journals, fiscal periods, and the trial balance |
| `GL.MANAGE` | gl | Maintain the chart of accounts (add/edit/deactivate) and the `gl_configs` account mapping |
| `GL.POST` | gl | Post manual journal entries (accruals, adjustments, opening balances) |
| `GL.PERIOD.CLOSE` | gl | Open/close fiscal periods and open a new fiscal year |

Naming mirrors the shipped catalogue (`STOCK.VIEW`/`STOCK.ADJUST`, `SALES.INVOICE.*`): `MODULE.RESOURCE.ACTION`. The **SYSTEM auto-poster** (`SalesPostingHandler`/`SaleVoidingHandler`) runs under **no** user permission — the producing sales action was already permissioned (ADR-0009 D-9, FR-GL-19) — but is bounded by the event's company/branch context.

**Audit emit points (NFR-GL-06 — every post and every period change, IAM append-only audit, ADR-0004):**

| action | when | target_type / target |
| --- | --- | --- |
| `GL.ACCOUNT.CREATE` | add a CoA account | `chart_of_accounts` / account id |
| `GL.ACCOUNT.UPDATE` | edit name/type/active | `chart_of_accounts` / account id |
| `GL.ACCOUNT.DEACTIVATE` | deactivate an account | `chart_of_accounts` / account id |
| `GL.JOURNAL.POST` | every post — manual AND automatic (actor = user, or SYSTEM for the auto-poster) | `journal_entries` / entry id (+ batch in detail) |
| `GL.PERIOD.OPEN` | open a period / open a new fiscal year | `fiscal_periods` / period id |
| `GL.PERIOD.CLOSE` | close a period | `fiscal_periods` / period id |
| `GL.CONFIG.SET` | set/change a `gl_configs` mapping | `gl_configs` / config id |

The auto-poster emits `GL.JOURNAL.POST` with actor = SYSTEM (NULL user) — GL audits the **post it performs** (the outbox does not double-audit, ADR-0009 D-9; the Sales finalise already audited the business action). `target_type` uses the plural table name (the shipped `audit_logs` convention, ADR-0004 / ADR-0007).

### D-15 — The seeded TZ small-business chart of accounts + default `gl_configs` mapping (FR-GL-02)

The V10 migration seeds, **per existing company** (and a Java `ChartOfAccountSeeder` + `GlConfigSeeder` for new companies, `BootstrapRunner`/`CompanyService.create`, the `TaxRateSeeder` precedent), this standard editable TZ small-business CoA. **All accounts `is_active = true`, `normal_balance` derived from type:**

| code | name | type | normal balance |
| --- | --- | --- | --- |
| `1000` | Cash | ASSET | DEBIT |
| `1100` | Bank | ASSET | DEBIT |
| `1200` | Accounts Receivable | ASSET | DEBIT |
| `1300` | Inventory | ASSET | DEBIT |
| `2100` | Accounts Payable | LIABILITY | CREDIT |
| `2200` | VAT Payable | LIABILITY | CREDIT |
| `3000` | Owner's Equity / Capital | EQUITY | CREDIT |
| `3900` | Retained Earnings | EQUITY | CREDIT |
| `4100` | Sales Revenue | INCOME | CREDIT |
| `5100` | Cost of Goods Sold | EXPENSE | DEBIT |
| `5200` | Rent Expense | EXPENSE | DEBIT |
| `5300` | Salaries & Wages | EXPENSE | DEBIT |
| `5400` | Utilities | EXPENSE | DEBIT |

(The seed is illustrative-minimum per gl.md §3.1 — it MUST include the accounts the auto-poster + future increments map to: 1000 Cash, 1200 AR, 1300 Inventory, 2100 AP, 2200 VAT Payable, 4100 Sales Revenue, 5100 COGS. The expense accounts 5200–5400 are the "standard expense accounts" gl.md names; the architect may extend the seed list, but these are the required floor. Codes are 4-digit strings; the numeric ranges are the convention, the `account_type` is the authority — BR-GL-12.)

**Default `gl_configs` mapping (ROADMAP T1.1, seeded per company):**

| config_key | → account_code | status |
| --- | --- | --- |
| `SALES_REVENUE` | `4100` | v1-active (required) |
| `VAT_PAYABLE` | `2200` | v1-active (required) |
| `ACCOUNTS_RECEIVABLE` | `1200` | v1-active (required for credit; seeded now) |
| `CASH` | `1000` | v1-active (required for cash sale) |
| `INVENTORY` | `1300` | reserved (T1.3/T2.2) — seeded, not yet posted-to |
| `COGS` | `5100` | reserved (T2.2) — seeded, not yet posted-to |
| `ACCOUNTS_PAYABLE` | `2100` | reserved (T1.3) — seeded, not yet posted-to |

The seed resolves each `account_id` by joining the just-seeded `chart_of_accounts` on `(company_id, account_code)` (so the mapping points at real account ids) — ordering matters (CoA seeded before gl_configs seed, D-16).

### D-16 — Migration: additive `V10__general_ledger.sql`, never a V1–V9 edit; ordering

IAM=V1 … Routes=V9 — all frozen. GL is a **new** module → purely **additive `V10__general_ledger.sql`**; it must not edit V1–V9. **Ordering within V10** (FK dependencies dictate it):

1. **(optional, recommended) `ALTER TABLE companies ADD COLUMN base_currency VARCHAR(3) NOT NULL DEFAULT 'TZS'`** + seed existing rows (D-9, closing ADR-0005 D-4) — the one cross-module additive touch, exactly as V5 ALTERed `products.vat_status`. *(If the owner defers the column, omit this step and use the first-post-sets-the-base fallback, D-9.)*
2. **`chart_of_accounts`** (self-FK `parent_id` references the same table — created in one statement, FK added after or inline; the seed leaves `parent_id` NULL so no ordering issue).
3. **`fiscal_years`** then **`fiscal_periods`** (period FKs the year).
4. **`journal_batches`** → **`journal_entries`** (FKs batch + period + self) → **`journal_lines`** (FKs entry + account).
5. **`gl_configs`** (FKs account).
6. **Indexes** for all of the above (D-2).
7. **Permission seed** (`GL.VIEW`/`GL.MANAGE`/`GL.POST`/`GL.PERIOD.CLOSE`, `ON CONFLICT (code) DO NOTHING`) + the `ORG_ADMIN` `role_permission` CROSS-JOIN grant (the V7 pattern).
8. **CoA seed** per existing company (CROSS JOIN companies × the account `VALUES` list; deterministic seed-uids).
9. **Fiscal-year + 12-period seed** per existing company (the current FY, `generate_series(1,12)` for periods; deterministic seed-uids; all OPEN).
10. **`gl_configs` seed** per existing company (joining the just-seeded `chart_of_accounts` on `(company_id, account_code)` to resolve `account_id`).

No `code_sequence` row is seeded (the `JOURNAL_BATCH` row is created on first use by `JournalBatchNumberGenerator`, the `SALES_INVOICE` precedent). All FK targets (`companies`, `branches`, `app_users`) exist in frozen V1. Table style follows shipped V7/V5 exactly: `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`, `uid VARCHAR(26)`, plural table names, singular constraint roots (`uq_`/`fk_`/`chk_`), plural index names (`ix_`), `NUMERIC(19,4)` money, `JSONB` where needed.

## Consequences

**Easier / safer:**
- **The system keeps books.** Finalising a sale auto-posts a balanced entry; voiding posts the reversal; the trial balance nets to zero — the acceptance bar (ROADMAP §5 Increment 1). The financial spine the whole roadmap feeds now exists.
- **The posting engine is built for many posters.** AR/AP/COGS/Cash/payroll/depreciation each land as a new `events` handler posting through the **same `GLPostingService`**, resolving accounts via `gl_configs` keys already seeded — additive, not a rework (D-13, NFR-GL-09). The reserved enum values + seeded keys mean the next increment is a CHECK-widen + a handler, never a schema reshape.
- **GL stays decoupled and cycle-free** (D-11): a leaf consumer like Stock, depending on `platform.events` + `sales.domain.dto`/`SalesInvoiceService`, with no edge back. `ModuleBoundaryTest` stays green; the GL→Sales read is the established DTO/scalar-id pattern.
- **Idempotency is structural** (D-6): distinct consumer markers (`GL.SALES_POST`/`GL.SALES_VOID`) + the `uq_journal_entry_sales_source` partial-unique backstop mean a redelivered event posts once — NFR-GL-03 (the round's biggest correctness risk) is addressed in the same TX, not hoped for.
- **Append-only is enforced by structure, not policy** (D-3): no `updated_*` columns on batch/entry/line, no update/delete service path; correction is a reversing entry linked by `reversal_of_id`. NFR-GL-04 is a defect-if-violated invariant the schema itself supports.
- **The enforcement split is explicit and tested** (D-3 table): the DB carries the single-row line invariant (`chk_journal_line_one_side`); the service carries the cross-row balance + ≥2-lines + open-period + active-account; an integration test asserts an unbalanced post is rejected and a balanced one nets to zero.
- **Re-reading the invoice (not widening the payload)** keeps the `SALE.FINALISED` contract thin and shared cleanly between Stock (quantities) and GL (totals), and is deterministic because the finalised invoice is immutable (D-6).

**Harder / to watch:**
- **The balance invariant is service-owned** (D-3) — no DB CHECK can sum siblings; `GLPostingService` is the single home and must be the **only** write path (no repository `save` of lines bypassing it). An integration test must pin "unbalanced → rejected, nothing written."
- **`company.base_currency` is unbuilt** (D-9) — the recommended fix (V10 ALTER) closes ADR-0005 D-4's reserved seam; if deferred, the first-post-sets-the-base fallback is weaker (it cannot validate against an authoritative base). Reviewers must pick one explicitly; the column is the right home.
- **Re-read couples GL to a Sales service method** (D-12) — `findPostingTotalsByUidAndCompany` must be company-scoped in the query (no cross-tenant read) and return the immutable finalised totals; a Sales refactor must not break this DTO contract (it is now a consumed interface).
- **Closed-period auto-post fails the event** (D-6, OQ-GL-01 default) — a late/replayed `SALE.FINALISED` into a closed period parks FAILED until finance reopens; operationally this needs the same FAILED-event visibility ADR-0009 D-8 flagged. Acceptable for QA; surfaced for production.
- **The self-FK `reversal_of_id` and `parent_id`** add two self-referencing FKs — straightforward, but the seed/migration must create the table before adding rows that reference it (the seed leaves both NULL, so no ordering trap).
- **Period generation is seeded current-year + on-demand next-year** (D-4) — a deployment that runs past period 12 without opening the next year will reject postings ("no open period"); the recommended explicit-open is predictable but requires a finance action each year (until the deferred year-end automation, OQ-GL-03).

**Migration / delivery cost:**
- 1 additive Flyway file (`V10__general_ledger.sql`): **7 new tables** (`chart_of_accounts`, `fiscal_years`, `fiscal_periods`, `journal_batches`, `journal_entries`, `journal_lines`, `gl_configs`) + their FKs/uniques/CHECKs + ~16 indexes; **1 additive ALTER** (`companies.base_currency`, recommended — D-9/D-16.1); **1 permission seed** (4 perms + ORG_ADMIN grant); **3 data seeds** per company (CoA, fiscal year+12 periods, gl_configs). No `code_sequence` row, no trigger. Depends only on frozen V1 (`companies`/`branches`/`app_users`).
- Backend (GL module): the `com.erp.modules.gl` set per D-1 — 7 entities + enums, 7 repositories (each with a `findCompanyIdByUid` projection), the services (`GLPostingService`, `GLConfigResolver`, `FiscalPeriodResolver`, `JournalBatchNumberGenerator`, `TrialBalanceQuery`, `ChartOfAccountService`, `FiscalPeriodService`, `GlConfigService`), the two `events` handlers, the 5 controllers, the 3 Java seeders (CoA/calendar/gl_configs).
- Backend (Sales touch — D-12): one `SalesInvoiceService` method + one `InvoicePostingTotalsDto` + one repository projection. **No Sales schema or REST change.**
- Backend (platform touch): `ScopeGuard` gains 4 GL cases + 4 repository deps (D-10); ArchUnit allow-list needs **no** new entry (GL→`platform.events` and GL→`sales.domain.dto` are existing patterns).
- Web: CoA admin (list/add/edit/deactivate), journal post (composer with running DR/CR totals + balance indicator, gl.md §7.1.3), fiscal-period open/close, `gl_configs` mapping screen, trial-balance read — all consuming `ApiResponse<T>`/`PageMeta`, Long-as-string, addressing by uid. (Web is the engineer's downstream; this ADR fixes the contract.)
- Deployment risk: **low** — additive migration on frozen schema; tables start empty except the per-company seeds; the two handlers reuse the proven outbox-consumer machinery; no broker. The one operational note: the same single-instance / FAILED-event caveats as ADR-0009 apply to the GL handlers.

## Alternatives considered

- **Journal line as a single signed amount (one `amount NUMERIC(19,4)`, +debit/−credit) instead of two debit/credit columns.** Fewer columns; the balance check is `SUM(amount) == 0`. **Rejected (D-3):** it loses the accounting vocabulary the printed journal and the spec use ("a line is a debit OR a credit", FR-GL-06/BR-GL-08), forces every reader to interpret sign by account type, and turns the clean single-row `chk_journal_line_one_side` into a sign convention the DB cannot meaningfully constrain (any nonzero is "valid"). The trial balance the spec states verbatim is `SUM(debit) − SUM(credit)` (FR-GL-16) — two columns express it directly. Two columns + the one-side CHECK is the boring, vocabulary-matching, DB-checkable choice.
- **Entry-only (no `journal_batches` — date/description/source on the entry, lines under it).** One fewer table; v1 posts one entry per "batch" anyway. **Rejected (D-2c):** the spec's vocabulary names the **batch** as the numbered container (`JB-####` from `code_sequence`, gl.md §2/§3.2) — a manual post, a sales auto-post, and a reversal are each "one batch." Folding the number onto the entry works for v1's one-entry-per-batch reality but precludes a multi-entry posting run (a future payroll batch posting many employees' entries under one `JB-####`) without a reshape. The batch is a thin numbered header (it costs one small table) that matches the ratified vocabulary and keeps multi-entry additive. Kept.
- **Derive `normal_balance` from `account_type` only (do not store it).** No redundant column; one less thing to keep consistent. **Rejected (D-2a):** every trial-balance and statement read would carry a `CASE account_type WHEN 'ASSET' THEN … ` derivation, and the column could not be grouped/indexed directly. Storing it (service-set from the type, never user-entered) removes the derivation from every read path at the cost of one column, with a one-line test asserting row-vs-type consistency. The redundancy is contained and the read simplicity is worth it. (The derive-only approach remains valid and is the fallback if a reviewer prefers zero redundancy — but the stored column is the pick.)
- **Columnar `gl_configs` (one row, a column per role: `sales_revenue_account_id`, `vat_payable_account_id`, …).** A single mapping row per company; type-safe columns. **Rejected (D-2d/D-5):** every later increment adds a posting role (AR, AP, Cash, COGS, Inventory, payroll clearing, FX gain/loss, …) — a columnar table needs an `ALTER TABLE ADD COLUMN` per role for the life of the ERP, and the "required-keys-before-posting" rule becomes a hard-coded per-column null-check. The key/value `(company_id, config_key, account_id)` table makes a new role an additive seed `INSERT` + a one-line CHECK widen, and the required-keys check iterates a set of keys. Key/value is the extensible, boring choice for a mapping that provably grows.
- **Widen the `SALE.FINALISED` payload to carry net/vat/gross + customer + currency (instead of re-reading the invoice).** GL would read the event and never touch Sales. **Rejected (D-6):** it bloats a payload Stock shares (Stock needs only the line quantities), re-litigates ADR-0008 D-9's fixed contract, and couples GL's evolving needs (customer kind, currency, future fields) into the event shape — every GL change would mean a payload change and a producer change. Re-reading the immutable finalised invoice by uid (scoped to company, via a Sales DTO) mirrors the shipped `SaleReversalStockHandler` re-read-from-source pattern, keeps the event a thin notification, and is deterministic (the invoice is frozen at finalise). Re-read wins.
- **A stored/materialised trial-balance table (or `reporting_snapshots`).** Pre-aggregated TB for fast reads. **Rejected for v1 (D-8):** the TB is `journal_lines GROUP BY account_id`, cheap with `ix_journal_lines_company_account` at QA scale; a snapshot adds an invalidation problem (every post would dirty it) for no v1 benefit. A materialised/snapshot view is the Reporting increment's (T2.3) additive call if volume ever warrants — not precluded (NFR-GL-09). Boring read-on-demand now.
- **A DRAFT journal status (save-then-post).** Accountants could save a half-built journal. **Rejected (D-3):** gl.md ratified reverse-only / posted-immutable with manual journals posting directly; a DRAFT state adds a mutable pre-post row that the append-only spirit avoids. Validation is a transient pre-post check; the composer holds unposted lines client-side. A saved-draft slice is additive under its own ADR if ever wanted; v1 has none by ratified design.

## Open / flagged items (do NOT block the build; recommended defaults stand — gl.md §11)

1. **OQ-GL-01 — Closed-period policy for an auto-post.** **Recommended default: fail-and-retry** (no sale ever posts to a closed period, BR-GL-03; D-6). The "post to next open period" alternative is an additive configurable policy. *Blocks build:* **NO.**
2. **OQ-GL-02 — Cash-vs-credit signal.** **Recommended default: DR `CASH`** for v1 (paid-in-full sales); the `ACCOUNTS_RECEIVABLE` role is seeded so the credit path is additive when the AR increment lands (D-6/D-13). *Blocks build:* **NO.**
3. **OQ-GL-03 — Year-end-close automation depth.** **Recommended default:** manual opening-balance journal + explicit next-year open (D-4); automated P&L→retained-earnings closing + opening-balance carry-forward is a later slice. *Blocks build:* **NO.**
4. **OQ-GL-04 — Per-category revenue/VAT mapping.** **Recommended default:** one `SALES_REVENUE` + one `VAT_PAYABLE` account (the seeded fixed mapping); per-category split is an additive `gl_configs` option later. *Blocks build:* **NO.**
5. **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** **Recommended default:** HALF_UP, TZS = 0 dp (ADR-0005 D-2); the balance check uses `BigDecimal.compareTo` (NFR-GL-02). *Blocks build:* **NO** for the model; **confirm before go-live.**
6. **`company.base_currency` column (D-9).** **Recommended:** build it in V10 (additive ALTER + 'TZS' seed, closing ADR-0005 D-4) so `GLPostingService` enforces base-currency against an authoritative config; fallback is first-post-sets-the-base. *Blocks build:* **NO** — decide before the migration is written.

None of the above changes the seven-table schema or the producer/consumer contracts; all are policy/tuning/additive choices with defaults the design is built to. The one cross-module guarantee (re-read the immutable invoice; balanced-or-rejected; idempotent same-TX posting) is placed deliberately and is test-pinned.

## Summary

This ADR is the technical design for **GL Increment 1** — the append-only double-entry **posting engine** in `com.erp.modules.gl`, the critical-path gate the whole financial roadmap feeds. It defines **seven tables** in additive **`V10__general_ledger.sql`** (never editing frozen V1–V9): `chart_of_accounts` (per-company master, `account_code` unique per company, `account_type` the authority for placement + stored `normal_balance`, `is_active` posting gate, reserved flat `parent_id`); `fiscal_years` + `fiscal_periods` (12 monthly OPEN/CLOSED periods, configurable `start_month`, seeded current-year + on-demand next-year, the entry-date→period gate); `journal_batches` → `journal_entries` → `journal_lines` (batch `JB-####` via `code_sequence`; entry carries `posting_date`/`fiscal_period_id`/`source_type`/`source_ref`/`reversal_of_id` self-FK; line carries `debit_amount`/`credit_amount` columns with `chk_journal_line_one_side`; **append-only — no `updated_*`, no update/delete path**); and key/value `gl_configs` (role→account, extensible). The **double-entry split** is explicit: the DB enforces the single-row line invariant (one side, nonneg), the **service** (`GLPostingService`) enforces the cross-row balance (`Σ debit == Σ credit`, `BigDecimal`), ≥2 lines, open-period, active-account — balanced-or-rejected, nothing partial written; correction is a **reversing entry** (`reversal_of_id`, debits/credits swapped, balanced by construction). The **sales auto-posting centerpiece**: `SalesPostingHandler` (`SALE.FINALISED`, consumer `GL.SALES_POST`) and `SaleVoidingHandler` (`SALE.VOIDED`, consumer `GL.SALES_VOID`), `@Transactional(MANDATORY)`, mirroring the shipped `SaleIssueStockHandler`/`SaleReversalStockHandler` exactly — they **re-read `sales_invoices` by `invoiceUid` scoped to company** (the verified payload carries no amounts/customer/currency) via a Sales DTO method, resolve accounts via `gl_configs`, and post `DR Cash(/AR) gross, CR Sales Revenue net, CR VAT Payable vat` (balanced by net+vat==gross); cash-vs-credit defaults to **DR Cash** (v1 paid-in-full; AR additive); closed-period auto-post **fails-and-retries** (no sale to a closed period); the void looks up the original by `source_ref` and posts the reversing entry or records an anomaly if none — idempotent in the same TX, backed by the `uq_journal_entry_sales_source` partial-unique. **Trial balance is a read** (`GROUP BY account SUM(debit)−SUM(credit)`), not a table. **Scope/security:** `ScopeGuard` gains `case "account"` (+ `fiscalperiod`/`journalentry`/`glconfig`); `assertCanActIn` on every read path; perms `GL.VIEW`/`GL.MANAGE`/`GL.POST`/`GL.PERIOD.CLOSE`; audit at `GL.ACCOUNT.*`/`GL.JOURNAL.POST`/`GL.PERIOD.OPEN|CLOSE`/`GL.CONFIG.SET`. **GL is a NEW leaf consumer** (like Stock) — GL→`platform.events` + GL→`sales.domain.dto`/`SalesInvoiceService`, **no edge back from Sales, no cycle**, scalar-id/DTO-only, no cross-module FK. The **TZ CoA seed** (1000 Cash … 5100 COGS + standard expenses) and the **default `gl_configs` mapping** (SALES_REVENUE→4100, VAT_PAYABLE→2200, AR→1200, CASH→1000; reserved INVENTORY→1300/COGS→5100/AP→2100 seeded-not-posted) are seeded per company. **DEFERRED hooks** (AR/AP control posting T1.2/T1.3, COGS/inventory T2.2, Cash T1.4) are reserved as `JournalSourceType` enum values + seeded `gl_configs` keys — each lands later as a new handler against the **same engine**, additive not rework. **One finding the engineer must act on:** `companies` has **no `base_currency` column** (ADR-0005 D-4 designed but never built) — recommendation is to add it (additive ALTER + 'TZS' seed) in V10, closing that reserved seam in the first module that needs it. The migration is a single additive **`V10__general_ledger.sql`** (7 tables, ~16 indexes, 1 recommended ALTER, 1 permission seed, 3 per-company data seeds) on **frozen V1–V9**. **Ready for build:** no ADR-blocking question remains; every flagged item has a recommended default the design is built to; the engine, the two sales handlers, and the TB are concrete enough to write without guessing a business rule.
