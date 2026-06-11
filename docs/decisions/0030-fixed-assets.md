# 0030 — Fixed Assets data model: an asset register + categories in a new `com.erp.modules.fixedassets` module, straight-line + reducing-balance depreciation with a generated schedule, an operator-initiated **idempotent-per-(company, fiscal-period)** depreciation run posting one period-gated GL journal (DR Depreciation Expense / CR Accumulated Depreciation), acquisition from a matched AP supplier bill (FA posts its own capitalisation journal — AP is unchanged) or manual, disposal/write-off with gain/loss, and a simple carrying-value revaluation, all synchronous human-act postings through `GLPostingService` resolving five new `gl_config` keys, additive as `V46`–`V50`

- **Status:** Accepted
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (Fixed Assets requirements [docs/requirements/fixed-assets.md](../requirements/fixed-assets.md) — FR-FA-01..19, BR-FA-01..12, NFR-FA-01..08, §7 flows, §10 boundary, §11 OQ log. The OQ-FA log's load-bearing items — the **depreciation-run granularity + idempotency mechanism** (OQ-FA-01), the **capitalisation posting** (OQ-FA-02), the **first/last-period convention** (OQ-FA-03), the **per-asset GL override** (OQ-FA-04), the **revaluation depth** (OQ-FA-05), the **run trigger** (OQ-FA-06), the **cost-centre availability** (OQ-FA-07) — are the **decisions this ADR makes**, not requirements blockers; the *behaviour* is fixed by the requirements.)
- **Context source:** [docs/requirements/fixed-assets.md](../requirements/fixed-assets.md) + [docs/PATH-TO-FULL-ERP.md](../PATH-TO-FULL-ERP.md) §3.8 (area 11, Fixed Assets, L). Verified against the **shipped** code:
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / V10): `GLPostingService.post(JournalEntryDraft)→JournalEntryDto` (validates ≥2 lines, balanced, OPEN period, active accounts, base currency; writes batch+entry+lines atomically) + `postReversal(originalEntryUid, reversalDate, sourceType, sourceRef, postedBy)`; `JournalEntryDraft(companyId, branchId, postingDate LocalDate, description, sourceType, sourceRef, reversalOfId, postedBy, List<LineDraft>)` + `LineDraft(accountId, debitAmount, creditAmount, currency, lineMemo)`; `GLConfigResolver.resolve(companyId, GlConfigKey)→ChartOfAccount` (throws on missing mapping / inactive account — BR-GL-10); `GLPostingSafeInvoker.postInNewTx` (REQUIRES_NEW, null-on-anomaly — for **event-driven** legs only); `FiscalPeriodResolver.resolveOpen(companyId, postingDate)→FiscalPeriod` (`@Transactional(MANDATORY)`, throws if no OPEN period — the period gate, BR-GL-03); `JournalLineRepository.accountBalance(companyId, accountId)` = `SUM(debit) − SUM(credit)` (the recon read); `GlConfigKey` enum (verified current set ends at `GRNI`, `STOCK_ADJUSTMENT` — **NO FA keys**); `JournalSourceType` enum (verified: `DEPRECIATION` is **a RESERVED token NOT yet admitted by the DB CHECK** — this ADR admits it + adds the disposal/revaluation tokens); `ChartOfAccountServiceImpl.DEFAULT_ACCOUNTS` (the per-company CoA seed list: 1000/1100/1200/1300/1400/1500/2100/2150/2200/2300/2400/3000/3900/4100/5100/5160/5200/5300/5400 — **NO FA accounts**) + `GlConfigServiceImpl.DEFAULT_MAPPINGS` (the key→code seed map — **NO FA keys**); the per-module new-company seeder pattern (`ApGlSeeder`/`InventoryGlSeeder` wired in `BootstrapRunner` + `CompanyService.create`).
  - **AP** ([ADR-0015](0015-accounts-payable-data-model.md) / V12): `SupplierBill` (`supplier_bills` — `id`, `uid`, `company_id`, `branch_id`, `bill_number`, `supplier_id`, `bill_date`, `net_amount`/`vat_amount`/`gross_amount`, `status` ∈ {DRAFT, MATCHED, …}, `source` ∈ {BILL, OPENING_BALANCE}); `SupplierBillLine` (net per line, nullable `poLineUid`/`grLineUid`); `BillMatchServiceImpl.postMatchedBillToGl` (DR Purchases/GRNI · [DR VAT_INPUT] / CR AP). FA reads a bill **by uid as a DTO via `ap.service`** — it does NOT import an AP entity and does NOT change AP's posting (D-7).
  - **Outbox / idempotency** ([ADR-0009](0009-transactional-outbox.md)): `OutboxPublisher.publish(eventType, aggregateType, aggregateId, aggregateUid, companyId, branchId, payload)` in the caller's TX; `DomainEventType` string constants (verified current set: `SALE.FINALISED`/`SALE.VOIDED`/`STOCK.RECEIVED`/`STOCK.RECEIPT.VOIDED`/`DELIVERY.CONFIRMED`/`DELIVERY.RETURNED` — **NO FA events**); `IdempotencyGuard.alreadyProcessed(consumer, uid)`/`markProcessed`; `processed_events(consumer, event_uid)`.
  - **Platform** ([ADR-0002](0002-rbac-enforcement.md)/[ADR-0004](0004-iam-audit-trail.md)/[ADR-0007](0007-products-data-model.md)): `@perm.has('CODE')` / `@perm.scoped(#uid,'targetType','CODE')` (NEVER `hasAuthority`); `ScopeGuard.assertCanActIn(principal, companyId)` + `companyIdOf(targetType, uid)` switch (verified current cases — **NO FA cases**); `AuditService` append-only; `code_sequence(company_id, entity_kind)` row-locked numbering (ADR-0007 D-6, lazy-created on first use); `MasterStatus` soft-delete; `Money` `NUMERIC(19,4)` base-currency HALF_UP (ADR-0005).
  - [[db-naming-convention]] verified against V1–V19 (plural masters/owned-children, singular junctions, singular constraint roots `uq_`/`fk_`/`chk_`, plural `ix_` indexes, `uid VARCHAR(26)` ULID, `company_id`/`branch_id` BIGINT scalar, audit cols, the additive `DROP/ADD CONSTRAINT` widen for `chk_gl_config_key`/`chk_journal_*_source_type`). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key` (overflow). **Latest shipped migration is `V19__sales_returns.sql`.** Fixed Assets uses the assigned additive range **`V46`–`V50`** (the coordinator has reserved V20–V45 for in-flight modules; FA is additive on the frozen V1–V19 and on whatever lands in V20–V45 — it touches only its own new tables + additive ALTERs/seeds on `gl`-owned objects via the documented additive-widen pattern). Next free ADR is 0030.

This ADR is the **technical data model + integration design** for Fixed Assets v1 (PATH-TO-FULL-ERP §3.8). It translates the ratified spec into: the new `com.erp.modules.fixedassets` module, the four table groups (`asset_categories`, `fixed_assets`, `depreciation_schedule_lines`, `depreciation_runs` + `depreciation_run_lines`, `asset_disposals`, `asset_revaluations`), the status enums + transitions, the straight-line + reducing-balance schedule generation (with the salvage-floor plug), the **idempotent-per-(company, fiscal-period) depreciation run** posting one period-gated GL journal, the acquisition-from-bill (FA posts its own capitalisation journal; AP is unchanged) + manual acquisition paths, disposal/write-off with gain/loss, the simple revaluation, every GL leg + its `GlConfigKey` + `JournalSourceType`, the five new accounts + keys, the `DEPRECIATION.RUN.EXECUTED` event, the `ScopeGuard` cases, the perms, the Angular routes, the `V46`–`V50` migration ordering with **#12-safe seeds**, and the ArchUnit edges. It is **concrete enough that the backend engineer writes the migrations + the entities + the schedule engine + the run + the postings + the seeders without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step.

## Context

The full financial spine ships and is event-wired; what is missing is a depreciating-asset register. Capital purchases are mis-treated (expensed or hand-journalled), the balance sheet has no fixed-asset block, and depreciation is a remembered manual entry. Fixed Assets is **the second live GL-posting module after the financial spine** and the **first scheduled, recurring, period-gated posting** the platform runs. The forces:

- **The depreciation run is the load-bearing decision (OQ-FA-01).** A run posts one fiscal period's depreciation for every eligible asset. The two risks are **double-charging a period** (idempotency) and **schedule drift** (rounding that never reaches salvage). The run must be **idempotent per (company, fiscal period)** — a period runs exactly once — and the schedule must sum **exactly** to (cost − salvage) via a final-period plug. Resolved in **D-4 / D-5**.

- **The capitalisation posting is the second design decision (OQ-FA-02).** Acquiring an asset from an AP bill must land the cost on the **Fixed Asset** account, not the P&L. Two mechanisms: **(a)** change AP's bill-match to capitalise (a goods/asset predicate like the GRNI swap), or **(b)** FA posts its **own** capitalisation journal reading the bill net, leaving AP unchanged. **(b) is the boring, decoupled choice** — AP stays a closed module, FA owns its posting, no AP code changes, no AP regression risk. Resolved in **D-6 / D-7**.

- **Every FA posting is a synchronous human act, not an event.** Depreciation, disposal, revaluation, and capitalisation are operator-initiated commands; a missing `gl_config` or closed period **must fail the command** (the operator sees the error and fixes it), not silently park. So FA posts via `GLPostingService.post` **directly** (the ADR-0020 D-1 "synchronous human-act posts directly, not the REQUIRES_NEW safe-invoker" stance) — the safe-invoker is for event-driven dispatch legs FA does not have. The `DEPRECIATION.RUN.EXECUTED` event is emitted **for audit/downstream**, after the synchronous post, not as the posting mechanism. Resolved in **D-3 / D-6**.

- **The schedule arithmetic must not drift (BR-FA-01/02, NFR-FA-01).** Straight-line = equal charge with the final period as the residual plug to salvage; reducing-balance = rate on opening NBV, capped at salvage, with a final plug at end-of-life. The schedule is generated once at IN_SERVICE and regenerated on revaluation; the run reads the schedule, never re-derives the charge. Resolved in **D-5**.

- **FA reconciles to the GL (BR-FA-08).** Σ(in-service asset cost ± revaluation) == the Fixed Asset GL block; Σ(accumulated depreciation) == the Accumulated Depreciation GL balance. Because every capitalisation debits the asset account at the same cost the register carries, every run credits accumulated depreciation at the same charge the asset accrues, and disposal removes both at the carried values, the register and the GL move together by construction; the report computes both sides and ties (the BR-INV-06 recon precedent). Resolved in **D-9**.

- **Cost-centre is an optional, not-yet-built dependency (OQ-FA-07).** FA wants to tag an asset (and its depreciation expense) with a cost centre. The dimension framework is not built (PATH-TO-FULL-ERP §3.11). FA carries a **nullable scalar** `cost_centre_id` with **no FK** (the framework activates it later — design-to-contract). Resolved in **D-2**.

- **Schema freeze / direction.** FA is a **new** module landing as additive migrations in the assigned range **V46–V50**: new FA tables + a CoA seed (5 new accounts per company) + 5 new `gl_config` keys + the `gl_config`-key CHECK widen + the journal-source-type CHECK widen + the FA permissions. It references frozen `companies`/`branches`/`app_users` (V1) + `chart_of_accounts`/`fiscal_periods`/`gl_configs` (V10) by scalar id/uid; it imports **no GL/AP entity** at runtime (posts via `GLPostingService`, reads a bill via a DTO). No edit to any prior migration.

## Decision

### D-1 — Module placement: a new `com.erp.modules.fixedassets` module; controllers flat in `com.erp.api`; FA gains outbound edges to `gl.service` and (soft) `ap.service`

Fixed Assets is a **new module** `com.erp.modules.fixedassets` (one word, flat, consistent with `cashbank`). It owns the register, categories, the schedule, the run, disposals, and revaluations. It is a **GL-posting leaf** (like AR/AP/Inventory) — it posts *into* GL and reads AP, and nothing depends back on it.

```
com.erp.modules.fixedassets
├── domain.entity   AssetCategory, FixedAsset,
│                   DepreciationScheduleLine,
│                   DepreciationRun, DepreciationRunLine,
│                   AssetDisposal, AssetRevaluation
├── domain.dto      AssetCategoryDto / CreateAssetCategoryRequest / UpdateAssetCategoryRequest,
│                   FixedAssetDto / RegisterAssetRequest / UpdateAssetRequest / AcquireFromBillRequest,
│                   DepreciationScheduleLineDto,
│                   DepreciationRunPreviewDto / DepreciationRunDto / DepreciationRunLineDto / RunDepreciationRequest,
│                   DisposeAssetRequest / WriteOffAssetRequest / AssetDisposalDto,
│                   RevalueAssetRequest / AssetRevaluationDto,
│                   TransferAssetRequest,
│                   FixedAssetRegisterRowDto / FixedAssetReconciliationDto (the recon bar, D-9),
│                   DepreciationRunExecutedPayload  (NEW outbox payload, D-8)
├── domain.enums    DepreciationMethod (STRAIGHT_LINE | REDUCING_BALANCE),
│                   FixedAssetStatus (DRAFT | IN_SERVICE | DISPOSED | WRITTEN_OFF),
│                   DepreciationRunStatus (POSTED)            // single-step, created POSTED (D-4)
│                   AssetDisposalType (SALE | WRITE_OFF),
│                   RevaluationDirection (UP | DOWN)
├── repository      AssetCategoryRepository, FixedAssetRepository,
│                   DepreciationScheduleLineRepository,
│                   DepreciationRunRepository, DepreciationRunLineRepository,
│                   AssetDisposalRepository, AssetRevaluationRepository
└── service         AssetCategoryService(+Impl), FixedAssetService(+Impl),
                    DepreciationScheduleService(+Impl)        — generate/regenerate the schedule (D-5),
                    DepreciationRunService(+Impl)             — preview + post the period run (D-4),
                    AssetDisposalService(+Impl)               — dispose / write-off (D-6),
                    AssetRevaluationService(+Impl)            — revalue (D-6),
                    FixedAssetGlPoster                        — builds + posts ALL FA journals via GLPostingService (D-6),
                    FixedAssetNumberGenerator                 — FA-#### / DEPR-#### via code_sequence (D-10),
                    FixedAssetReconQuery                      — Σ cost / Σ accum-dep vs GL (D-9),
                    FixedAssetGlSeeder                        — seeds the 5 accounts + 5 keys for a new company (D-11)
```

Controllers stay flat in `com.erp.api`: `AssetCategoryController`, `FixedAssetController`, `DepreciationRunController`, `AssetDisposalController` (+ revalue/transfer endpoints), touching only services (`ModuleBoundaryTest`). `FixedAssetGlPoster` is the **only** place FA builds GL drafts (keeps services thin, mirrors how AP isolates `postMatchedBillToGl` and Inventory isolates `InventoryGlPoster`).

**Boundary note (D-12):** FA reads **DTOs only** from AP (the bill at acquisition) and posts via `GLPostingService`/`GLConfigResolver`/`FiscalPeriodResolver` — it imports no AP/GL **entity**. Cross-module references it persists are **scalar `Long` id + `String` uid columns** (the source bill uid, the fiscal period id, the GL account ids) with real DB FKs only to GL-owned `chart_of_accounts`/`fiscal_periods` and platform `companies`/`branches` — no cross-module `@ManyToOne` into AP.

### D-2 — `asset_categories` + `fixed_assets` tables

All tables: plural names; `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) ULID with `uq_<root>_uid`; `company_id` BIGINT NOT NULL (+ `branch_id` on the asset); standard audit cols (`created_at`/`created_by`/`updated_at`/`updated_by`); `@Version` on mutable headers. Money columns `NUMERIC(19,4)`; rate columns `NUMERIC(9,4)`; quantity/period counts SMALLINT/INTEGER. Cross-module references are **scalar `Long` id + `String` uid** (no cross-module `@ManyToOne`); the FK to `chart_of_accounts(id)` (intra-GL, a leaf reference) is allowed as a scalar id with a real DB FK (the `gl_configs.account_id` precedent).

#### `asset_categories` (master, per company)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_asset_category_uid` |
| `company_id` | BIGINT | NO | `fk_asset_category_company` → `companies(id)` |
| `code` | VARCHAR(30) | NO | `uq_asset_category_company_code UNIQUE (company_id, code)` |
| `name` | VARCHAR(160) | NO | e.g. "Motor Vehicles" |
| `default_method` | VARCHAR(20) | NO | `DepreciationMethod`; `chk_asset_category_method CHECK (default_method IN ('STRAIGHT_LINE','REDUCING_BALANCE'))` |
| `default_life_periods` | INTEGER | NO | `chk_asset_category_life CHECK (default_life_periods > 0)`; useful life in fiscal periods (months) |
| `default_reducing_rate` | NUMERIC(9,4) | YES | required iff method = REDUCING_BALANCE; `chk_asset_category_rate CHECK (default_reducing_rate IS NULL OR (default_reducing_rate > 0 AND default_reducing_rate <= 100))` |
| `asset_account_id` | BIGINT | NO | `fk_asset_category_asset_acct` → `chart_of_accounts(id)`; the Fixed Asset account this category capitalises to |
| `accum_dep_account_id` | BIGINT | NO | `fk_asset_category_accum_acct` → `chart_of_accounts(id)`; the Accumulated Depreciation contra-asset |
| `dep_expense_account_id` | BIGINT | NO | `fk_asset_category_expense_acct` → `chart_of_accounts(id)`; the Depreciation Expense |
| `status` | VARCHAR(32) | NO | `MasterStatus`; DEFAULT `'ACTIVE'`; soft-delete |
| `version` + audit | | | |

> The category owns the three GL accounts (OQ-FA-04 — an asset inherits, cannot override in v1). On a new company the three accounts default to the seeded FA accounts (D-11); a company may create extra categories pointing at any of its CoA accounts of the right type (the service validates `account_type`: asset account ASSET, accum ASSET (contra), expense EXPENSE).

#### `fixed_assets` (master, per company/branch)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_fixed_asset_uid` |
| `company_id` / `branch_id` | BIGINT | NO | tenant; `fk_fixed_asset_company` / `fk_fixed_asset_branch` |
| `asset_number` | VARCHAR(30) | NO | `FA-####` at register (D-10); `uq_fixed_asset_company_number UNIQUE (company_id, asset_number)` |
| `category_id` | BIGINT | NO | `fk_fixed_asset_category` → `asset_categories(id)` |
| `name` | VARCHAR(200) | NO | |
| `status` | VARCHAR(20) | NO | `FixedAssetStatus`; DEFAULT `'DRAFT'`; `chk_fixed_asset_status CHECK (status IN ('DRAFT','IN_SERVICE','DISPOSED','WRITTEN_OFF'))` |
| `acquisition_cost` | NUMERIC(19,4) | NO | the capitalised **net** cost; `chk_fixed_asset_cost CHECK (acquisition_cost > 0)` |
| `salvage_value` | NUMERIC(19,4) | NO | DEFAULT 0; `chk_fixed_asset_salvage CHECK (salvage_value >= 0 AND salvage_value < acquisition_cost)` |
| `depreciation_method` | VARCHAR(20) | NO | `DepreciationMethod` (copied from category, overridable while DRAFT); `chk_fixed_asset_method` |
| `life_periods` | INTEGER | NO | `chk_fixed_asset_life CHECK (life_periods > 0)` |
| `reducing_rate` | NUMERIC(9,4) | YES | required iff method = REDUCING_BALANCE; `chk_fixed_asset_rate` |
| `acquisition_date` | DATE | NO | |
| `depreciation_start_date` | DATE | NO | `chk_fixed_asset_start CHECK (depreciation_start_date >= acquisition_date)`; governs the first period charged (BR-FA-11) |
| `carrying_cost` | NUMERIC(19,4) | NO | DEFAULT = acquisition_cost; the **current depreciable base** (changes on revaluation, D-6) — the schedule regenerates on this |
| `accumulated_depreciation` | NUMERIC(19,4) | NO | DEFAULT 0; running Σ charged (maintained by the run + disposal); `chk_fixed_asset_accum CHECK (accumulated_depreciation >= 0)` |
| `revaluation_reserve_balance` | NUMERIC(19,4) | NO | DEFAULT 0; running revaluation-up reserve held against this asset (for audit; the reserve account holds the GL side) |
| `supplier_id` | BIGINT | YES | scalar (denormalised, no FK — cross-module); the supplier the asset was bought from (bill-sourced) |
| `source_bill_uid` | VARCHAR(26) | YES | the AP supplier bill it was capitalised from (acquisition-from-bill, D-7); NULL for manual |
| `location` | VARCHAR(200) | YES | free text in v1 |
| `cost_centre_id` | BIGINT | YES | **nullable scalar, NO FK** — reserved for the cost-centre dimension framework (OQ-FA-07, design-to-contract) |
| `asset_tag` | VARCHAR(100) | YES | tag / serial, free text |
| `capitalised_gl_entry_uid` | VARCHAR(26) | YES | the capitalisation journal uid (audit trace; set on IN_SERVICE if FA posts capitalisation, D-7) |
| `disposed_at` | DATE | YES | set on disposal |
| `version` + audit | | | |

NBV is **derived** (`carrying_cost − accumulated_depreciation`) — **not** a stored column (it changes every run; storing it invites drift). `chk_fixed_asset_rate CHECK ((depreciation_method = 'STRAIGHT_LINE' AND reducing_rate IS NULL) OR (depreciation_method = 'REDUCING_BALANCE' AND reducing_rate IS NOT NULL AND reducing_rate > 0 AND reducing_rate <= 100))`.

Indexes: `ix_fixed_assets_company`, `ix_fixed_assets_company_status (company_id, status)` (the run's eligible-asset working set + register filters), `ix_fixed_assets_company_category (company_id, category_id)`.

### D-3 — Status enums + transitions (service-guarded, audited, append-only on the books)

**`FixedAssetStatus`** (FR-FA-03/04/13/14):

```
DRAFT ──place-in-service──▶ IN_SERVICE ──dispose(SALE)────▶ DISPOSED      (terminal)
  │         (generates schedule,           │
  │          effects capitalisation)       └──write-off────▶ WRITTEN_OFF  (terminal)
  └──(hard delete allowed while DRAFT — no schedule, no GL, no number consumed? see note)
```

- `FA-####` allocated **at register (create)** — a draft asset is a real working record (the SO-create precedent, ADR-0021 D-2). DRAFT generates no schedule and posts no GL. Financial fields editable only while DRAFT (BR-FA-09).
- **IN_SERVICE** is the gate that (1) validates inputs, (2) generates the schedule (D-5), (3) effects capitalisation (D-7). Immutable financial fields thereafter; carrying value changes are a **revaluation**.
- DISPOSED / WRITTEN_OFF are terminal; depreciation stops; the run skips them (FR-FA-12).

**`DepreciationRunStatus`** = `{POSTED}` — a run is created **already POSTED** (it posts the journal synchronously on the post command; preview is a separate read that creates no row, D-4). The enum is a single value for forward-compat (a future review-then-post DRAFT state is additive).

**`AssetDisposalType`** = `{SALE, WRITE_OFF}`. **`RevaluationDirection`** = `{UP, DOWN}`. **`DepreciationMethod`** = `{STRAIGHT_LINE, REDUCING_BALANCE}`. All carried with their `chk_*` IN-list CHECKs on the owning table.

### D-4 — The depreciation run: idempotent per (company, fiscal period), one period-gated GL journal (OQ-FA-01)

This is the load-bearing decision. A run posts **one fiscal period's** depreciation for **all eligible assets** in a company, **once**.

#### `depreciation_runs` (header) — the idempotency anchor

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_depreciation_run_uid` |
| `company_id` | BIGINT | NO | `fk_depreciation_run_company` |
| `run_number` | VARCHAR(30) | NO | `DEPR-####` at create (D-10); `uq_depreciation_run_company_number UNIQUE (company_id, run_number)` |
| `fiscal_period_id` | BIGINT | NO | `fk_depreciation_run_period` → `fiscal_periods(id)`; the period this run charges |
| `posting_date` | DATE | NO | the date the journal posts (the period's last day, or operator-named within the period) |
| `status` | VARCHAR(20) | NO | `DepreciationRunStatus`; DEFAULT `'POSTED'`; `chk_depreciation_run_status CHECK (status IN ('POSTED'))` |
| `total_charge_amount` | NUMERIC(19,4) | NO | the run total (Σ of all run lines) |
| `asset_count` | INTEGER | NO | the number of assets charged |
| `gl_entry_uid` | VARCHAR(26) | NO | the journal uid the run posted (the audit trace + the recon anchor) |
| `currency` | VARCHAR(3) | NO | base |
| `executed_at` | TIMESTAMPTZ | NO | DEFAULT now() |
| `version` + audit | | | |

**The idempotency guard — the single most important constraint in the module:**
`uq_depreciation_run_company_period UNIQUE (company_id, fiscal_period_id)`. **A (company, fiscal period) can have at most one run row.** The post path first checks for an existing run for (company, period); if present it **returns it (no-op)** — re-running a posted period is a no-op (BR-FA-06, FR-FA-11). The unique constraint is the DB backstop against a concurrent double-post (the second insert raises a constraint violation, caught and translated to the no-op/return-existing). No `IdempotencyGuard`/`processed_events` row is needed (this is a synchronous human act, not an outbox consumer — the unique key *is* the idempotency mechanism, simpler and stronger than a processed-events check for a non-event path).

#### `depreciation_run_lines` (child) — the per-asset charge in the run

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_depreciation_run_line_uid` |
| `depreciation_run_id` | BIGINT | NO | `fk_depreciation_run_line_run` → `depreciation_runs(id)` |
| `company_id` | BIGINT | NO | denormalised |
| `fixed_asset_id` | BIGINT | NO | `fk_depreciation_run_line_asset` → `fixed_assets(id)`; `uq_depreciation_run_line_asset UNIQUE (depreciation_run_id, fixed_asset_id)` (one charge per asset per run) |
| `schedule_line_id` | BIGINT | NO | `fk_depreciation_run_line_schedule` → `depreciation_schedule_lines(id)`; the schedule period this charge posted |
| `charge_amount` | NUMERIC(19,4) | NO | `chk_depreciation_run_line_charge CHECK (charge_amount >= 0)`; the asset's charge this period |
| `accum_dep_after` | NUMERIC(19,4) | NO | the asset's accumulated depreciation after this charge (snapshot) |
| `nbv_after` | NUMERIC(19,4) | NO | NBV after (snapshot, for the schedule-vs-actual report) |
| audit cols | | | |

#### The run flow (`DepreciationRunService.post(companyUid, fiscalPeriodUid, postingDate)`, one transaction)
1. `scopeGuard.assertCanActIn(principal, companyId)`; `@perm.has('FA.DEPRECIATE')`.
2. **Idempotency:** if a `depreciation_runs` row exists for (company, fiscal period) → return it (no-op, BR-FA-06).
3. **Period gate:** `FiscalPeriodResolver.resolveOpen(companyId, postingDate)` — must be the OPEN period matching `fiscalPeriodUid`; else reject (BR-GL-03). (Resolve by period uid → its date range → assert it is OPEN and `postingDate` falls in it.)
4. Select **eligible assets**: status = IN_SERVICE, with a `depreciation_schedule_lines` row for this period that is **not yet posted** (`posted = false`), charge > 0 (FR-FA-12 skips DRAFT/DISPOSED/WRITTEN_OFF/fully-depreciated).
5. For each eligible asset, read its **scheduled charge** for the period (the run reads the schedule, never re-derives — D-5). Accumulate a per-category total (assets in the same category share the same expense/accum accounts).
6. **Post one GL journal** via `FixedAssetGlPoster` → `GLPostingService.post(draft)` (synchronous, must-fail-on-anomaly): for each distinct category in the run, a **DR Depreciation Expense (category's `dep_expense_account_id`)** leg + a **CR Accumulated Depreciation (category's `accum_dep_account_id`)** leg, each = the category's summed charge. `sourceType = DEPRECIATION`; `sourceRef = runUid`; `postedBy = operator`; `description = "Depreciation run " + runNumber + " " + period`; line memos carry the category code. One journal, per-category leg pairs (OQ-FA-01 decision — **not** one journal per asset; NFR-FA-03).
7. Persist the `depreciation_runs` header (`gl_entry_uid` = the posted journal uid) + a `depreciation_run_line` per asset; mark each `depreciation_schedule_lines.posted = true` with `depreciation_run_id` set; increment each `fixed_assets.accumulated_depreciation += charge`.
8. **Emit `DEPRECIATION.RUN.EXECUTED`** (D-8) in the same TX (audit/downstream).
9. Audit the run (ADR-0004).

**Preview** (`DepreciationRunService.preview(companyUid, fiscalPeriodUid)`, read-only, `@perm.has('FA.DEPRECIATE')`): runs steps 4–5 and returns `DepreciationRunPreviewDto` (per-asset lines + totals) **without** posting or persisting. The operator reviews, then calls post (FR-FA-10).

### D-5 — Depreciation schedule generation (the arithmetic; the salvage-floor plug)

#### `depreciation_schedule_lines` (child of the asset)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_depreciation_schedule_line_uid` |
| `fixed_asset_id` | BIGINT | NO | `fk_depreciation_schedule_line_asset` → `fixed_assets(id)` |
| `company_id` | BIGINT | NO | denormalised |
| `period_seq` | INTEGER | NO | 1..life_periods; `uq_depreciation_schedule_line_seq UNIQUE (fixed_asset_id, period_seq, schedule_version)` |
| `schedule_version` | INTEGER | NO | DEFAULT 1; bumped on regenerate (revaluation, D-6) — the prior version's unposted lines are superseded; **posted lines are never deleted** (append-only) |
| `period_date` | DATE | NO | the fiscal period this seq maps to (start_date + (seq−1) periods) |
| `planned_charge` | NUMERIC(19,4) | NO | `chk_depreciation_schedule_line_charge CHECK (planned_charge >= 0)` |
| `accumulated_after` | NUMERIC(19,4) | NO | planned accumulated depreciation after this period |
| `nbv_after` | NUMERIC(19,4) | NO | planned NBV after (>= salvage by construction, BR-FA-01) |
| `posted` | BOOLEAN | NO | DEFAULT false; set true by the run (D-4) |
| `depreciation_run_id` | BIGINT | YES | `fk_depreciation_schedule_line_run` → `depreciation_runs(id)`; set when posted |
| audit cols | | | |

#### The arithmetic (BR-FA-02, executed by `DepreciationScheduleService.generate`)

```
base       = carrying_cost - salvage_value      // the depreciable amount
n          = life_periods
remaining  = base
accum      = accumulated_depreciation (0 at first IN_SERVICE; >0 if regenerating after a revaluation)

STRAIGHT_LINE:
  per       = round4(base / n)                  // HALF_UP, 4 dp
  for seq in 1..n:
    charge  = (seq < n) ? min(per, remaining) : remaining   // final period = the residual PLUG
    remaining -= charge
    accum   += charge
    nbv      = carrying_cost - accum            // == salvage at seq=n exactly
    emit schedule line(seq, charge, accum, nbv)

REDUCING_BALANCE:
  rate      = reducing_rate / 100
  openingNBV= carrying_cost - accum
  for seq in 1..n:
    raw     = round4(openingNBV * rate)
    floor   = openingNBV - salvage_value        // cannot depreciate below salvage
    charge  = (seq < n) ? min(raw, floor) : floor   // final period PLUGS to salvage
    openingNBV -= charge
    accum   += charge
    emit schedule line(seq, charge, accum, carrying_cost - accum)
```

- **The final-period plug** guarantees `Σ charge == base` exactly and `nbv == salvage` at end-of-life (BR-FA-01, NFR-FA-01 — no drift). A reducing-balance asset that has not reached salvage by `n` takes the residual in the final period.
- `period_date` for seq maps to the fiscal period containing `depreciation_start_date + (seq−1) months` (BR-FA-11 default: **full period from the period of the start date** — OQ-FA-03, the simplest correct convention; pro-rata is deferred).
- **Regeneration on revaluation (D-6):** bump `schedule_version`, set `n' = remaining unposted periods`, `carrying_cost' = new carrying value`, `accum` = depreciation already posted; generate the new remaining schedule on the new base. Posted lines (prior version) are untouched (append-only); only future lines are superseded.

### D-6 — GL postings: exact legs, keys, source types (all synchronous via `GLPostingService.post`)

All amounts base currency (NFR-FA-01), HALF_UP, posted via `FixedAssetGlPoster` → `GLPostingService.post(draft)` **directly** (synchronous human acts — a missing config / closed period **fails the command**, the ADR-0020 D-1 stance; FA has no event-driven posting). Accounts via `GLConfigResolver.resolve(companyId, key)` for the module-default accounts and via the **category's** mapped account ids for depreciation. Period via `FiscalPeriodResolver.resolveOpen` on every post.

**(a) Capitalisation — `FixedAssetService.placeInService` (D-7):**
- **DR Fixed Asset (category `asset_account_id`) = acquisition_cost** / **CR `FIXED_ASSET_CLEARING` (new key, the offset) = acquisition_cost**. `sourceType = FA_ACQUISITION` (NEW); `sourceRef = assetUid`; `postedBy = operator`. (See D-7 for the clearing-account rationale + the manual vs bill paths.)

**(b) Depreciation run — `DepreciationRunService.post` (D-4):**
- per category: **DR Depreciation Expense (category `dep_expense_account_id`)** / **CR Accumulated Depreciation (category `accum_dep_account_id`)** = the category's summed period charge. One journal per run. `sourceType = DEPRECIATION` (admit the reserved token in the CHECK, D-13); `sourceRef = runUid`.

**(c) Disposal (SALE) — `AssetDisposalService.dispose`:** the period's final depreciation must be charged first (BR-FA-10 — the disposal flow first posts the asset's outstanding scheduled charge up to the disposal period, then):
- **CR Fixed Asset (category `asset_account_id`) = acquisition_cost (gross removal)**
- **DR Accumulated Depreciation (category `accum_dep_account_id`) = accumulated_depreciation (to date)**
- **DR `FIXED_ASSET_DISPOSAL_CLEARING` (the new key, proceeds offset) = proceeds** (the cash/clearing leg the operator's downstream cash receipt clears; v1 uses a clearing account, not a direct cash post — keeps FA decoupled from Cash&Bank)
- **gain/loss to `GAIN_LOSS_ON_DISPOSAL` (new key):** `gainLoss = proceeds − NBV`; **CR** for a gain, **DR** for a loss (the leg balances the entry). `sourceType = FA_DISPOSAL` (NEW); `sourceRef = disposalUid`.

**(d) Write-off (WRITE_OFF) — `AssetDisposalService.writeOff`:** a disposal with proceeds = 0 → loss = full NBV:
- **CR Fixed Asset = acquisition_cost** / **DR Accumulated Depreciation = accumulated_depreciation** / **DR `GAIN_LOSS_ON_DISPOSAL` = NBV** (the loss). `sourceType = FA_DISPOSAL`; `sourceRef = disposalUid`.

**(e) Revaluation — `AssetRevaluationService.revalue` (simple carrying-value model, OQ-FA-05):**
- **UP:** **DR Fixed Asset (category `asset_account_id`) = delta** / **CR `REVALUATION_RESERVE` (new key, equity) = delta**; `fixed_assets.carrying_cost += delta`, `revaluation_reserve_balance += delta`; regenerate the remaining schedule (D-5).
- **DOWN:** **DR `GAIN_LOSS_ON_DISPOSAL` (the loss; reuse the disposal gain/loss expense) = delta** / **CR Fixed Asset = delta**; `carrying_cost −= delta`; regenerate. (A down revaluation below the reserve first reverses the reserve, then expenses the excess — v1 simplification: expense the whole down-delta to `GAIN_LOSS_ON_DISPOSAL`; full reserve-recycling is deferred, OQ-FA-05.) `sourceType = FA_REVALUATION` (NEW); `sourceRef = revaluationUid`.

**(f) Transfer / relocate (FR-FA-16):** **no GL** — a register edit (location/branch/cost_centre), audited only.

#### `asset_disposals` (header) + `asset_revaluations` (header)

`asset_disposals`: `id`/`uid` (`uq_asset_disposal_uid`), `company_id`/`branch_id`, `fixed_asset_id` (`fk_asset_disposal_asset`), `disposal_type` VARCHAR(20) (`chk_asset_disposal_type CHECK (disposal_type IN ('SALE','WRITE_OFF'))`), `disposal_date` DATE, `fiscal_period_id` (`fk_asset_disposal_period`), `proceeds_amount` NUMERIC(19,4) (`chk_asset_disposal_proceeds CHECK (proceeds_amount >= 0)`; 0 for write-off), `nbv_at_disposal` NUMERIC(19,4), `gain_loss_amount` NUMERIC(19,4) (signed: + gain, − loss), `gl_entry_uid` VARCHAR(26), `currency`, `reason` VARCHAR(255), `@Version` + audit. `uq_asset_disposal_asset UNIQUE (fixed_asset_id)` — an asset disposes once.

`asset_revaluations`: `id`/`uid` (`uq_asset_revaluation_uid`), `company_id`/`branch_id`, `fixed_asset_id` (`fk_asset_revaluation_asset`), `revaluation_date` DATE, `fiscal_period_id` (`fk_asset_revaluation_period`), `direction` VARCHAR(10) (`chk_asset_revaluation_direction CHECK (direction IN ('UP','DOWN'))`), `delta_amount` NUMERIC(19,4) (`chk_asset_revaluation_delta CHECK (delta_amount > 0)`), `carrying_before`/`carrying_after` NUMERIC(19,4), `gl_entry_uid` VARCHAR(26), `currency`, `reason` VARCHAR(255), `@Version` + audit. (Multiple revaluations per asset over its life — no unique on `fixed_asset_id`.)

### D-7 — Acquisition: FA posts its own capitalisation journal; AP is unchanged (OQ-FA-02)

**Decision: option (b) — FA posts its own capitalisation journal; AP's `postMatchedBillToGl` is NOT changed.** This is the decoupled, boring choice: AP stays a closed module (no regression risk, no AP code change), FA owns its posting, and the GL effect is explicit and FA-traceable.

**Manual acquisition (`FixedAssetService.placeInService` for a manual asset):** post **DR Fixed Asset (category `asset_account_id`) / CR `FIXED_ASSET_CLEARING`** at `acquisition_cost`. The `FIXED_ASSET_CLEARING` account (a new asset-side clearing/suspense, see D-11) holds the offset; the operator clears it with the manual journal that recorded the original spend (or it nets against the original expense posting). This is the simplest correct v1: the asset lands on the Fixed Asset block; the clearing account is the bridge the operator reconciles. (OQ-FA-02 also offered "no automatic journal" for manual — **rejected** here: a registered IN_SERVICE asset MUST appear on the GL Fixed Asset block for the BR-FA-08 recon to hold; a no-journal manual asset would break the recon.)

**Acquisition-from-bill (`FixedAssetService.acquireFromBill(AcquireFromBillRequest{billUid, billLineUid, categoryUid, salvage, life, ...})`):**
1. FA reads the supplier bill by uid as a **DTO via `ap.service`** (FA → `ap.service`, returns `SupplierBillDto` — no AP entity import, D-12). Validates the bill is MATCHED and the line exists.
2. Creates the `fixed_assets` row with `acquisition_cost = bill line net` (VAT excluded, BR-FA-07), `supplier_id` + `source_bill_uid` set, then places IN_SERVICE.
3. Posts the **same** capitalisation journal as manual: **DR Fixed Asset / CR `FIXED_ASSET_CLEARING`**. Because the AP bill already posted **DR Purchases (5150) / CR AP** (or DR GRNI for a goods bill), the operator/period-close reclassifies the `5150` spend against `FIXED_ASSET_CLEARING` — **v1 simplification: FA's capitalisation credits `FIXED_ASSET_CLEARING`, and the operator posts a manual reclass DR `FIXED_ASSET_CLEARING` / CR `5150 Purchases` to move the cost off the P&L**. (This keeps AP untouched. A future tighter coupling — AP capitalises directly via an asset-line predicate at bill-match, the GRNI-swap shape — is deferred; OQ-FA-02 names it the alternative.)

> **Why not change AP (option a):** changing `postMatchedBillToGl` to detect an "asset line" and DR a Fixed Asset account would (1) require AP to know about FA categories/accounts (a backward dependency AP → FA, a **cycle**), (2) require the asset to exist *before* the bill matches (an ordering inversion — assets are typically registered after the bill), and (3) risk an AP regression on the shipped 3-way-match path. Option (b) keeps the dependency arrow FA → AP (read-only DTO) and FA → GL (post), no cycle, no AP change. The cost — a manual reclass journal off `5150` — is accepted for v1 and flagged (the recon bar surfaces any un-reclassed spend).

### D-8 — `DEPRECIATION.RUN.EXECUTED` event (audit / downstream seam)

The run publishes, in its own TX after the synchronous post, a new outbox event:

```
DomainEventType.DEPRECIATION_RUN_EXECUTED = "DEPRECIATION.RUN.EXECUTED"     (NEW constant)
aggregateType = "DEPRECIATION_RUN"                                          (NEW AGG constant)
payload = DepreciationRunExecutedPayload(
    runUid, companyId, fiscalPeriodId, postingDate,
    totalChargeAmount, assetCount, glEntryUid, executedAt
)
```

No consumer is required in v1 — it is the seam a future notifications / management-reporting / cost-centre-allocation module consumes (NFR-FA-06). The GL post is **synchronous** (the run posts in its own TX via `GLPostingService.post`); the event is a downstream notification, not the posting mechanism. (Contrast ADR-0020/0021 where the *stock* effect is event-driven because it crosses into another module's hot path; FA's posting stays in FA's command TX because it is a single synchronous human act into GL.)

### D-9 — FA-to-GL reconciliation (BR-FA-08)

`FixedAssetReconQuery` (read, `@perm.has('FA.VIEW')`, `assertCanActIn`) computes two recon bars per company, reusing the `ReconciliationDto.of(label, computed, expected)` shape (ADR-0018 D-5):
- **Cost bar:** computed = Σ(`fixed_assets.carrying_cost` WHERE status = IN_SERVICE) ; expected = `JournalLineRepository.accountBalance(companyId, <Fixed Asset account>)` (debit-normal). They tie because every capitalisation/revaluation-up debits the asset account at the same amount the register carries, and disposal removes it.
- **Accumulated-depreciation bar:** computed = Σ(`fixed_assets.accumulated_depreciation` WHERE status = IN_SERVICE) ; expected = −`accountBalance(companyId, <Accum Dep account>)` (the contra-asset is credit-normal, so `SUM(debit)−SUM(credit)` is negative; negate for the positive accumulated figure). They tie because every run credits accum dep at the same charge the asset accrues.

A disagreement is a finance-grade defect (the un-reclassed-`5150` from D-7 is the one known gap the operator clears — surfaced, not hidden).

### D-10 — Numbering: two new `code_sequence` kinds (FA / DEPR)

`FixedAssetNumberGenerator` reuses the shipped `code_sequence` row-locked allocation (ADR-0007 D-6) with two new `entity_kind` values: `FIXED_ASSET` (`FA-%04d`) and `DEPRECIATION_RUN` (`DEPR-%04d`), per company, concurrency-safe (NFR-FA-07). Allocation timing: `FA-####` at **register (create)**, `DEPR-####` at **run-create (post)**. No new numbering table — only new `entity_kind` rows, lazily created with `next_value = 1` on first use (the shipped mechanism). The `uq_*_company_number` constraints backstop generator bugs. **No #12 seed-uid exposure** — numbering kinds are lazy, not seeded.

### D-11 — New GL accounts + `gl_config` keys + the new-company seeder

**Five new `gl_config` keys + five new CoA accounts** (the recommended codes — chosen to avoid collision with the shipped set 1000–5400 + 2150/5160; the gaps `1600/1700/3200/4200/5500` are free):

| `GlConfigKey` (NEW) | account code | account name | `AccountType` | normal balance |
|---|---|---|---|---|
| `FIXED_ASSETS` | `1600` | Fixed Assets (at cost) | ASSET | DEBIT |
| `ACCUMULATED_DEPRECIATION` | `1700` | Accumulated Depreciation | ASSET | CREDIT (contra-asset) |
| `DEPRECIATION_EXPENSE` | `5500` | Depreciation Expense | EXPENSE | DEBIT |
| `GAIN_LOSS_ON_DISPOSAL` | `4200` | Gain / (Loss) on Asset Disposal | INCOME | CREDIT (sign carries gain vs loss; a loss is a DR) |
| `REVALUATION_RESERVE` | `3200` | Asset Revaluation Reserve | EQUITY | CREDIT |

Plus a **clearing account** for the capitalisation offset + the disposal-proceeds offset (D-6/D-7). To keep the new-key count at the five PATH-TO-FULL-ERP names, **the capitalisation/disposal clearing reuses an existing offset**: v1 uses **`FIXED_ASSETS` as both sides is wrong** — instead the capitalisation CR side and the disposal-proceeds DR side use a single new clearing key. **Decision: add ONE more key `FIXED_ASSET_CLEARING` → a new account `1650` (ASSET, DEBIT) "Fixed Asset Acquisition/Disposal Clearing"** (the suspense the operator reclasses against, D-7). So the precise new-key set is **six**: `FIXED_ASSETS`, `ACCUMULATED_DEPRECIATION`, `DEPRECIATION_EXPENSE`, `GAIN_LOSS_ON_DISPOSAL`, `REVALUATION_RESERVE`, `FIXED_ASSET_CLEARING`; and **six** new accounts: `1600`, `1650`, `1700`, `3200`, `4200`, `5500`.

> **Note on `DEPRECIATION_EXPENSE` vs `5500`:** PATH-TO-FULL-ERP §3.8 names the gl_config keys (FIXED_ASSETS / ACCUMULATED_DEPRECIATION / DEPRECIATION_EXPENSE / GAIN_LOSS_ON_DISPOSAL / REVALUATION_RESERVE / CWIP). CWIP is **deferred** (§2) so its key is **not** added in v1 (added when CWIP lands). The `FIXED_ASSET_CLEARING` key is the one v1 addition beyond that list, required by the decoupled-capitalisation decision (D-7).

`FixedAssetGlSeeder` (the `InventoryGlSeeder`/`ApGlSeeder` pattern) seeds the six accounts + six key→account mappings for a **new** company (wired in `BootstrapRunner` + `CompanyService.create`); the migration seeds them for **existing** companies. The category create-default points its three accounts at `1600`/`1700`/`5500`.

### D-12 — ArchUnit edges (no cycle)

- **`fixedassets.service` → `gl.service`** (`GLPostingService`, `GLConfigResolver`, `GLPostingSafeInvoker` (unused in v1 but available), `FiscalPeriodResolver`) — the same edge AP/AR/Inventory already have. **Allowed.**
- **`fixedassets.service` → `ap.service`** (read the bill DTO at acquisition-from-bill) — the same cross-module-service-call stance `ap → gl` takes; AP returns a `SupplierBillDto`, FA imports no AP entity. **Allowed.**
- **`fixedassets` → `gl.domain.enums`** (`GlConfigKey`, `JournalSourceType` — FA references the FA keys/tokens) — a DTO/enum dependency, the shipped pattern (AP/AR/Inventory all reference `gl.domain.enums`). **Allowed.**
- **`fixedassets` → `platform.events`** (`DomainEventType`, `OutboxPublisher`, the payload DTO lives in `fixedassets.domain.dto`) — the producer pattern. **Allowed.**
- **No edge back into `fixedassets`** from gl/ap/platform — FA is a **leaf consumer**. **No cycle** (gl/ap do not depend on fixedassets).
- **`ScopeGuard`** gains the FA repositories + target-type cases (D-14) — the cross-cutting-spine allowance ADR-0002/0008/0013/0021 already document (the security layer reads module repositories; not a peer-module edge).
- The shipped `ModuleBoundaryTest` enforces controller↛repository, service↛controller, audit-append-only — none of these edges violates an active rule.

### D-13 — `JournalSourceType` + `gl_config`-key CHECK widens (additive)

- **`JournalSourceType`** gains `FA_ACQUISITION`, `DEPRECIATION` (the reserved token — now admitted), `FA_DISPOSAL`, `FA_REVALUATION`. The `chk_journal_batch_source_type` + `chk_journal_entry_source_type` CHECKs are widened additively (the V17 `DROP/ADD CONSTRAINT` pattern — union of all prior tokens + the four new). Prior migration DDL untouched.
- **`GlConfigKey`** gains the six FA keys (D-11); `chk_gl_config_key` widened additively (union of all prior keys + the six new).

### D-14 — `ScopeGuard` cases + perms

**`ScopeGuard.companyIdOf` gains three new target-type cases** (the FA repositories are added to `ScopeGuard`'s constructor — the documented cross-cutting-spine pattern):
- `case "assetcategory"  -> assetCategories.findCompanyIdByUid(uid)`
- `case "fixedasset"     -> fixedAssets.findCompanyIdByUid(uid)`
- `case "depreciationrun"-> depreciationRuns.findCompanyIdByUid(uid)`

(Disposals/revaluations are addressed via the asset uid — they are sub-resources of `fixedasset`; no separate scope case needed, the asset's case covers the path-uid gate.)

**Permissions (MODULE.RESOURCE.ACTION, module `fixedassets`)** — gated with `@perm.has` / `@perm.scoped` (NEVER `hasAuthority`):
- `FA.CATEGORY.VIEW`, `FA.CATEGORY.MANAGE` (categories)
- `FA.VIEW` (register + reports + recon, read)
- `FA.REGISTER.MANAGE` (register/edit/place-in-service/transfer assets)
- `FA.DEPRECIATE` (preview + post the depreciation run)
- `FA.DISPOSE` (dispose / write-off / revalue)
- `FA.VERIFY` (the lightweight verification flag, FR-FA — reserved; read-adjacent)

Seeded + granted to `ORG_ADMIN` via the V7/V12/V14/V17 `CROSS JOIN … ON CONFLICT DO NOTHING` pattern.

### D-15 — API surface (controllers + endpoints; flat in `com.erp.api`)

`AssetCategoryController` (`/api/fixed-assets/categories`):
- `GET /` (list, `@perm.has('FA.CATEGORY.VIEW')`) · `POST /` (`FA.CATEGORY.MANAGE`) · `GET /{uid}` (`@perm.scoped(#uid,'assetcategory','FA.CATEGORY.VIEW')`) · `PUT /{uid}` / `DELETE /{uid}` (`@perm.scoped(#uid,'assetcategory','FA.CATEGORY.MANAGE')`).

`FixedAssetController` (`/api/fixed-assets`):
- `GET /` (register list, filterable, `FA.VIEW`) · `POST /` (register, `FA.REGISTER.MANAGE`) · `GET /{uid}` (`@perm.scoped(#uid,'fixedasset','FA.VIEW')`) · `PUT /{uid}` (edit non-financial, scoped MANAGE) · `POST /{uid}/place-in-service` (scoped MANAGE) · `POST /acquire-from-bill` (`FA.REGISTER.MANAGE`) · `POST /{uid}/transfer` (scoped MANAGE) · `POST /{uid}/dispose` + `POST /{uid}/write-off` (`@perm.scoped(#uid,'fixedasset','FA.DISPOSE')`) · `POST /{uid}/revalue` (scoped DISPOSE) · `GET /{uid}/schedule` (`@perm.scoped(#uid,'fixedasset','FA.VIEW')`) · `GET /reconciliation` (`FA.VIEW`).

`DepreciationRunController` (`/api/fixed-assets/depreciation-runs`):
- `GET /` (list, `FA.VIEW`) · `POST /preview` (body: companyUid? + fiscalPeriodUid, `FA.DEPRECIATE`) · `POST /` (post the run, `FA.DEPRECIATE`) · `GET /{uid}` (`@perm.scoped(#uid,'depreciationrun','FA.VIEW')`).

All responses use the shipped `ApiResponse<T>` envelope; URLs address by `uid`; Long ids serialise as JSON strings (the global convention).

### D-16 — Angular nav routes (under the admin shell, `<module>/<resource>` convention)

- `fixed-assets/categories` (list) · `fixed-assets/categories/uid/:uid` (detail/edit)
- `fixed-assets/register` (asset register list) · `fixed-assets/register/create` (register/acquire) · `fixed-assets/register/uid/:uid` (asset detail: schedule, disposal, revalue)
- `fixed-assets/depreciation-runs` (run list + preview/post) · `fixed-assets/depreciation-runs/uid/:uid` (run detail)
- `fixed-assets/reconciliation` (the FA-to-GL recon bars)

## Consequences

**Positive**
- The books carry a correct fixed-asset block and a scheduled depreciation charge; FA-to-GL reconciles by construction (D-9). It is the first scheduled, period-gated, idempotent recurring posting the platform runs — the pattern HR/Payroll and recurring-journals reuse.
- The **depreciation run is idempotent per (company, fiscal period)** by a single unique constraint `uq_depreciation_run_company_period` (D-4) — double-charging a period is structurally impossible; the run reads the schedule (never re-derives) so it cannot drift; the schedule's final-period plug guarantees `Σ charge == cost − salvage` exactly (BR-FA-01).
- **AP is unchanged** (D-7): FA posts its own capitalisation journal and reads the bill as a DTO — no AP regression risk, no AP → FA cycle, no bill-before-asset ordering inversion. The cost is one accepted manual reclass off `5150`, surfaced by the recon bar.
- Additive and contained: new module, six new tables, six new `gl_config` keys + six CoA accounts, four new `JournalSourceType` tokens, one new event + payload, two new `code_sequence` kinds, three new `ScopeGuard` cases, six perms, four nav route groups. **No edit to any prior migration; no change to AP/GL/Stock code beyond the additive enum-token + key + ScopeGuard-case additions** (and the seeder wiring).

**Negative / costs**
- The capitalisation-offset clearing account (`1650`) requires a manual reclass off `5150` for bill-sourced assets in v1 (D-7) — an accepted decoupling cost, surfaced by the recon bar; a future AP-side capitalisation (the GRNI-swap shape) tightens this (deferred, OQ-FA-02 alt).
- `accumulated_depreciation` + `carrying_cost` are maintained denormalisations on the asset that MUST stay tied to the posted runs/revaluations; the recon bar (D-9) is the backstop, but a service bug in the run rollup is a correctness defect — tests must assert the asset-sum ↔ GL-balance tie after every run/disposal/revaluation.
- The simple revaluation (D-6e) does not do full IAS-16 reserve recycling — a down revaluation expenses the whole delta rather than first reversing any prior up-reserve. Documented (OQ-FA-05) so a future reader does not assume the full model.
- FA posts synchronously in the operator's TX (not via the outbox) — a large run posts one journal with per-category legs; for thousands of assets this is one bounded journal (NFR-FA-03), but a very large estate would want batching (deferred; not a v1 concern at small-business scale).

**Neutral / deferred**
- CWIP, units-of-production, component depreciation, impairment, full IAS-16 revaluation, landed cost on assets, maintenance/insurance/verification workflows, inter-branch GL transfer, automatic scheduled run, multi-currency assets — all deferred (§2), none precluded (NFR-FA-08). The `cost_centre_id` nullable scalar is the seam the dimension framework activates (OQ-FA-07).

## Alternatives considered

- **Capitalisation posting — FA posts its own journal (b) vs AP capitalises at bill-match (a).** *Decided: (b).* (a) requires AP to know FA categories/accounts (a backward AP → FA dependency = a cycle), inverts the register-after-bill ordering, and risks an AP 3-way-match regression. (b) keeps FA → AP read-only + FA → GL post, no cycle, no AP change. The cost — a manual `5150` reclass — is accepted for v1 and surfaced by the recon. (a) is the right tightening later (deferred).
- **Depreciation run idempotency — a unique `(company, period)` constraint vs the `IdempotencyGuard`/`processed_events` pattern.** *Decided: the unique constraint.* The run is a **synchronous human act**, not an outbox consumer; `processed_events` keys on an event uid that does not exist here. A unique `(company_id, fiscal_period_id)` on `depreciation_runs` is simpler, stronger (the DB rejects a concurrent double-post), and directly models "one run per period". `IdempotencyGuard` stays the mechanism for the event-driven paths (Stock/Sales), not for FA's synchronous post.
- **Run granularity — one journal per run (per-category legs) vs one journal per asset.** *Decided: one journal per run.* Per-asset journals multiply entries N× with no reconciliation benefit and a perf cost on a large estate; per-category legs keep the journal small, give expense/accum traceability by category, and post atomically (NFR-FA-03). (The schedule + run lines preserve per-asset detail for the report.)
- **NBV — derived vs stored.** *Decided: derived* (`carrying_cost − accumulated_depreciation`). Storing NBV invites drift (it changes every run); the two stored quantities (carrying cost, accumulated depreciation) are the authoritative state, NBV computed on read. (Schedule lines snapshot `nbv_after` for the report — a frozen plan figure, not the live NBV.)
- **Posting — synchronous in the operator's TX vs event-driven via the outbox.** *Decided: synchronous.* FA's postings are single human acts into GL where a missing config / closed period must fail the command (the operator fixes it) — the manual-journal model, not the cross-module-hot-path model that justifies the outbox in Stock/Sales. The `DEPRECIATION.RUN.EXECUTED` event is a downstream notification, emitted after the post, not the posting mechanism.
- **Module placement — a new `fixedassets` module vs folding into `gl`.** *Decided: a new module.* FA owns its own register/categories/schedule/run lifecycle and master data; folding it into `gl` would bloat the posting engine with domain it does not own. FA is a sibling GL-posting module (like AR/AP/Inventory), the consistent pattern.

## Open items (OQ-FA — recommended defaults adopted; none blocks the build)

- **OQ-FA-01 — run granularity + idempotency:** adopted **one journal per run (per-category legs) + a unique `(company_id, fiscal_period_id)` on `depreciation_runs`** (the DB idempotency). Settled — the load-bearing decision.
- **OQ-FA-02 — capitalisation posting:** adopted **FA posts its own capitalisation journal (DR Fixed Asset / CR `FIXED_ASSET_CLEARING`); AP unchanged; a manual reclass off `5150` clears the clearing account**. Settled. (AP-side capitalisation is the deferred tightening.)
- **OQ-FA-03 — first/last-period convention:** adopted **full period from the period containing the start date** (the simplest correct default); the disposal period takes its full final charge before gain/loss. Pro-rata-by-day deferred. Settled (math only, no schema impact).
- **OQ-FA-04 — per-asset GL override:** adopted **the category owns the three GL accounts; an asset inherits, cannot override in v1** (keeps the recon clean). Per-asset overrides are additive later. Settled.
- **OQ-FA-05 — revaluation depth:** adopted **simple carrying-value revaluation** (up → reserve, down → expense the whole delta, regenerate remaining schedule). Full IAS-16 (reserve recycling, depreciation on the revalued amount, reserve→RE on disposal) deferred. Settled.
- **OQ-FA-06 — run trigger:** adopted **operator-initiated** (preview → post; human review before a finance-grade post). Automatic `@Scheduled` month-end run deferred (needs the general scheduler). Settled.
- **OQ-FA-07 — cost-centre availability:** adopted **a nullable `cost_centre_id` scalar with NO FK** (reserved for the dimension framework; FA does not block on it). Settled.
- **OQ-FA-08 — non-recoverable VAT capitalisation:** v1 capitalises **net only** (BR-FA-07); non-recoverable-VAT capitalisation deferred. Owner confirms no v1 requirement. Default stands.

## Build-readiness

The ADR is concrete enough to build the FA module + the `V46`–`V50` migrations without guessing a rule — every table, column, constraint name, enum, transition, schedule formula (with the salvage-floor plug), run idempotency mechanism, GL leg + key + source-type, event/payload, `code_sequence` kind, `ScopeGuard` case, perm, controller/endpoint, and nav route is specified. **Additive on frozen V1–V19** (and on V20–V45 reserved by the coordinator). **#12-safe** — the only per-company CROSS-JOIN seeds are the six CoA accounts (uid `'FA' || lpad(company_id,6,'0') || account_code` = 2+6+4 = 12 chars, ≤26) and the six `gl_config` mappings (uid `'FAC' || lpad(company_id,6,'0') || substr(md5(config_key),1,12)` = 3+6+12 = 21 chars, ≤26 — **never** `|| config_key`), plus the uid-less permission grant; numbering kinds are lazy (no seed-uid). **Cross-module touch list:** (1) **FA → GL** — the posts (`GLPostingService`/`GLConfigResolver`/`FiscalPeriodResolver`) + the six new keys + the four new source-type tokens (additive CHECK widens); (2) **FA → AP** — the read-only bill DTO at acquisition (no AP change); (3) **`ScopeGuard`** — three new target-type cases + the FA repositories in the constructor; (4) the new-company **seeder wiring** (`FixedAssetGlSeeder` in `BootstrapRunner` + `CompanyService.create`). **No GL/AP/Stock posting code changes** beyond the additive enum-token + key additions.

---

## Migration ordering (V46–V50; additive; V1–V19 FROZEN; #12-safe seeds)

The FA migrations split across the assigned `V46`–`V50` range (DDL is cheap and additive; the split lets the build stage). One valid layout:

- **`V46__fixed_assets_gl_config.sql`** — the GL touch (does not depend on FA tables):
  1. **CoA seed** per existing company — INSERT `1600`/`1650`/`1700`/`3200`/`4200`/`5500` with the right `account_type`/`normal_balance`; uid `'FA' || lpad(c.id::text,6,'0') || account_code` (12 chars); `ON CONFLICT (company_id, account_code) DO NOTHING`.
  2. **`chk_gl_config_key` widen** — `DROP/ADD CONSTRAINT` adding `FIXED_ASSETS`, `FIXED_ASSET_CLEARING`, `ACCUMULATED_DEPRECIATION`, `DEPRECIATION_EXPENSE`, `GAIN_LOSS_ON_DISPOSAL`, `REVALUATION_RESERVE` to the union of all prior keys.
  3. **`gl_configs` seed** per existing company — the six key→account mappings; uid `'FAC' || lpad(coa.company_id::text,6,'0') || substr(md5(m.config_key),1,12)` (21 chars, **#12-safe**); `ON CONFLICT (company_id, config_key) DO NOTHING`.
  4. **`chk_journal_batch_source_type` + `chk_journal_entry_source_type` widen** — add `FA_ACQUISITION`, `DEPRECIATION`, `FA_DISPOSAL`, `FA_REVALUATION` to the union of all prior tokens.
- **`V47__asset_categories.sql`** — CREATE `asset_categories` (+ constraints/indexes, D-2). (FKs to `chart_of_accounts(id)` reference the V46 seed.)
- **`V48__fixed_assets.sql`** — CREATE `fixed_assets` + `depreciation_schedule_lines` (+ constraints/indexes, D-2/D-5).
- **`V49__depreciation_runs.sql`** — CREATE `depreciation_runs` (+ `uq_depreciation_run_company_period`) + `depreciation_run_lines` (D-4).
- **`V50__asset_disposals_revaluations_perms.sql`** — CREATE `asset_disposals` + `asset_revaluations` (D-6); then the **permission seed + `ORG_ADMIN` grant** — INSERT `FA.CATEGORY.VIEW/MANAGE`, `FA.VIEW`, `FA.REGISTER.MANAGE`, `FA.DEPRECIATE`, `FA.DISPOSE`, `FA.VERIFY` (module `fixedassets`) `ON CONFLICT (code) DO NOTHING`; grant all to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING`.

`MigrationKeepDataIT` extends to V50 (the additive CoA/gl_config/perm seeds + the new tables are keep-data-safe — `ON CONFLICT DO NOTHING` on re-deploy). **No new movement type, no Stock change** — FA does not touch inventory. **No #12-vulnerable seed-uid** beyond the two documented #12-safe forms above.

## Summary

ADR-0030 designs **Fixed Assets v1** as a new `com.erp.modules.fixedassets` module: six tables (`asset_categories`, `fixed_assets` + `depreciation_schedule_lines`, `depreciation_runs` + `depreciation_run_lines`, `asset_disposals`, `asset_revaluations`), straight-line + reducing-balance depreciation with a generated schedule (final-period plug to salvage, no drift), an **operator-initiated depreciation run that is idempotent per (company, fiscal period)** via `uq_depreciation_run_company_period` and posts **one period-gated GL journal** (DR Depreciation Expense / CR Accumulated Depreciation, per-category legs), acquisition from a matched AP supplier bill (**FA posts its own capitalisation journal; AP is unchanged**, D-7) or manual, disposal/write-off with gain/loss, and a simple carrying-value revaluation — **all synchronous human-act postings through `GLPostingService`** (never the event-driven safe-invoker), resolving six new `gl_config` keys and gating every post to an OPEN period via `FiscalPeriodResolver`. The run emits a `DEPRECIATION.RUN.EXECUTED` outbox event for audit/downstream (not the posting mechanism). FA-to-GL reconciles by construction (D-9). **Additive as V46–V50 on frozen V1–V19; #12-safe seeds; no AP/GL/Stock posting-code changes beyond additive enum-token + key + ScopeGuard-case additions.**
