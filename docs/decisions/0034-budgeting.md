# 0034 — Budgeting & Management Accounting data model: budgets by account × cost-centre × fiscal period that **consume the ADR-0025 cost-centre dimension framework** (a budget's cost centre is a `dimension_values(id)` in the COST_CENTRE dimension slot — budgeting builds NO cost-centre table and adds NO `journal_lines` tag of its own), append-only budget versions with a self-contained DRAFT→SUBMITTED→APPROVED/REJECTED/SUPERSEDED approval lifecycle (exactly one APPROVED version active per scope), and a budget-vs-actual variance read that joins approved-version lines against GL `journal_lines` grouped by account [× ADR-0025's `cost_centre_value_id`], signed by normal balance — all measure-only (budgets post NO GL), in a new `com.erp.modules.budgeting` module, additive as `V69–V73` on the frozen V1–V19

> **Amended 2026-06-11 (Wave-2 collision resolution — collision #1, BLOCKER).** Budgeting is **re-scoped to CONSUME the ADR-0025 cost-centre/accounting-dimension framework** instead of building its own. The original ADR-0034 added its own `cost_centres` master + a `journal_lines.cost_centre_id` analysis tag + a `LineDraft.costCentreId` extension + a `CostCentreLookup` port + a `ScopeGuard "costcentre"` case — **all of which collide with ADR-0025** (which authoritatively owns the `dimensions`/`dimension_values` masters and the `journal_lines.cost_centre_value_id` slot-column). **ADR-0025 is authoritative.** This amendment **DROPS** budgeting's duplicated cost-centre infrastructure entirely:
> - **DROP** the `cost_centres` table (D-3a) — budgets reference **`dimension_values(id)`** (the value in ADR-0025's `COST_CENTRE` dimension slot).
> - **DROP** the `journal_lines.cost_centre_id` column + its index + the V69 ALTER — the variance read GROUPs by **ADR-0025's `journal_lines.cost_centre_value_id`** (V27).
> - **DROP** the `LineDraft.costCentreId` extension, the `CostCentreLookup` port (D-6/D-10), and the manual-journal cost-centre write path — cost-centre tagging onto the ledger is **wholly ADR-0025's** (its `LineDraft` dimension-value-id extension + its four wired document defaults).
> - **DROP** the `ScopeGuard "costcentre"` case and the `COST_CENTRE` `code_sequence` kind (D-11/D-13) — cost-centre identity/numbering is ADR-0025's.
> - **KEEP** budgeting's own tables (`budgets`, `budget_versions`, `budget_lines`), its version lifecycle, its variance read, and its **V69–V73 range** — only the duplicated cost-centre infrastructure is removed.
>
> **Consequence: budgeting is now HARD-GATED by ADR-0025 (cost-centre/dimension framework).** It cannot build until ADR-0025 ships (`dimensions` + `dimension_values` + `journal_lines.cost_centre_value_id` exist). This matches the Wave-2 tranche plan (budgeting is Tranche-2, "needs cost-centre"). The per-section edits below carry their own `Amended 2026-06-11` notes.

- **Status:** Proposed
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (consuming the DRAFT Budgeting requirements 2026-06-11 — `docs/requirements/budgeting.md`. The load-bearing scoping calls — cost-centre tagging breadth (OQ-BUD-01), dimension grain (OQ-BUD-02), budget period grain (OQ-BUD-03), the approval-lifecycle home (OQ-BUD-05) — are **design decisions this ADR makes** with owner-style defaults; they are flagged, not blockers. The owner should ratify OQ-BUD-01/02/03 before build since they shape the model.)
- **Context source:** [docs/requirements/budgeting.md](../requirements/budgeting.md) (FR-BUD-01..20, BR-BUD-01..17, NFR-BUD-01..08, §6 flows, §8 OQ log — the ground truth for every rule below) + [docs/PATH-TO-FULL-ERP.md](../PATH-TO-FULL-ERP.md) §3.11 (area 14, T3.6 — "cost-centre / dimension framework is the build-first enabler") + §4 item 4. Verified against the **shipped** code:
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / [V10__general_ledger.sql](../../backend/src/main/resources/db/migration/V10__general_ledger.sql)): `chart_of_accounts` (`id`, `uid` VARCHAR(26), `company_id`, `account_code`, `name`, `account_type` ∈ {ASSET,LIABILITY,EQUITY,INCOME,EXPENSE}, `normal_balance` ∈ {DEBIT,CREDIT}, `is_active`, `status` MasterStatus); `fiscal_years` + `fiscal_periods` (`id`, `uid`, `company_id`, `fiscal_year_id`, `period_no` 1..12, `start_date`/`end_date`, `status` OPEN|CLOSED); `journal_batches`→`journal_entries`→`journal_lines` (the posting; `journal_lines` carries `company_id`, **nullable `branch_id` analysis tag** verified V10 lines 184/199/281, `entry_id`, `account_id`, `debit_amount`/`credit_amount` NUMERIC(19,4), `currency`, `line_memo`, NO `updated_*` — append-only); `JournalLineRepository.trialBalanceSums`/`trialBalanceSumsByPeriod`/`periodMovementByAccount` (the `GROUP BY account` aggregates the variance read mirrors — verified); `GLPostingService.post(JournalEntryDraft)` + `JournalEntryDraft.LineDraft(accountId, debitAmount, creditAmount, currency, lineMemo)` (the manual-journal path the cost-centre tag threads into — D-6); `GlConfigKey` / `JournalSourceType` (NO new value needed — budgets post nothing, D-7).
  - **Reporting** ([ADR-0018](0018-financial-reporting-read-model.md) / V15): `IncomeStatementBuilder` / `journal_lines GROUP BY account` signed-by-`normal_balance` read pattern (the variance report is a **sibling read** over the same lines, additionally grouped by **ADR-0025's `cost_centre_value_id`** [Amended 2026-06-11: was budgeting's own `cost_centre_id`]); `ReportExporter` / `CsvStatementRenderer` (the CSV export the variance report reuses, FR-BUD-18); `StatementType`/`StatementSection` enums (the variance/departmental reads add their own DTOs, not these enums).
  - **Numbering** ([ADR-0007](0007-products-data-model.md) D-6): `code_sequence(company_id, entity_kind)` row-locked allocation — **one new lazy `entity_kind` value** `BUDGET` (created on first use, the shipped mechanism — no seed rows, **no #12 seed-uid exposure for numbering**). **[Amended 2026-06-11: the `COST_CENTRE` kind is DROPPED — cost-centre numbering belongs to ADR-0025; budgeting allocates only `BUDGET-####`.]**
  - **Cost-centre / accounting-dimension framework** ([ADR-0025](0025-cost-centre.md) — **the authoritative dimension owner this module CONSUMES**): the `dimensions` (per-company dimension types, seeded `COST_CENTRE` + `DEPARTMENT` in bounded slots) + `dimension_values` (the per-company members / cost centres) masters; the **`journal_lines.cost_centre_value_id`** slot-column (V27 — the analysis tag the variance read GROUPs by); the documented Budgeting read contract (ADR-0025 D-8): `DimensionService.resolveValue(uid)→DimensionValueDto`, `DimensionService.listActiveValues(companyId, COST_CENTRE)→List<DimensionValueDto>` (the cost-centre picker source), and `DimensionSlicedReportQuery.actualsByAccountValuePeriod(companyId, COST_CENTRE, fromDate, toDate)→List<DimensionActualsRowDto>` (**the actuals aggregate the variance read consumes — budgeting does NOT query `journal_lines` directly for cost-centre actuals; it reads this DTO**). **[Amended 2026-06-11: this bullet is the load-bearing dependency added by the Wave-2 re-scope — budgeting is hard-gated by ADR-0025.]**
  - **Security** ([ScopeGuard](../../backend/src/main/java/com/erp/platform/security/ScopeGuard.java)): the `companyIdOf(targetType, uid)` switch (verified — `case "account"`, `"salesorder"`, … land here; this ADR adds `"budget"`, `"budgetversion"` [Amended 2026-06-11: `"costcentre"` DROPPED — cost-centre scoping uses ADR-0025's `"dimensionvalue"` case]); `assertCanActIn` on every read path; [PermissionChecks](../../backend/src/main/java/com/erp/platform/security/PermissionChecks.java) bean `@Component("perm")` with `has(code)` / `scoped(uid, targetType, code)` — `@perm.has(...)` / `@perm.scoped(...)`, **NEVER `hasAuthority`**.
  - **Money** ([ADR-0005](0005-money-and-currency.md)): base currency only (TZS), NUMERIC(19,4), HALF_UP.
  - **Audit / outbox** ([ADR-0009](0009-transactional-outbox.md)): `AuditService` for every transition. **No outbox event is needed in v1** — budgeting causes **no cross-module effect** (it posts no GL, moves no stock, touches no cash). **[Amended 2026-06-11: budgeting no longer sets any `journal_lines` cost-centre tag — cost-centre tagging onto the ledger is wholly ADR-0025's (its `journal_lines.cost_centre_value_id` + its wired document defaults). Budgeting only READS the cost-centre actuals via ADR-0025's `actualsByAccountValuePeriod` DTO contract (D-8).]** One `DomainEventType` constant (`BUDGET.VERSION.APPROVED`) is **reserved/declared but not yet published** for the deferred enforcement/commitment round (D-9) so the coordinator can see the namespace claim. **[Amended 2026-06-11: the `COST_CENTRE.DEACTIVATED` reserved constant is DROPPED — cost-centre lifecycle events belong to ADR-0025.]**
  - [[db-naming-convention]] verified against V1–V19 (plural masters/children `budgets`/`budget_versions`/`budget_lines` [Amended 2026-06-11: `cost_centres` DROPPED — the cost-centre master is ADR-0025's `dimension_values`]; singular constraint roots `uq_`/`fk_`/`chk_` on the singular entity; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id`/`branch_id` BIGINT scalar; additive `ADD COLUMN`/`ADD CONSTRAINT`). **ISSUES-REGISTER #12:** any per-company CROSS-JOIN seed-uid MUST be md5-bounded `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars), **never** raw-key concat. **Latest shipped migration is `V19__sales_returns.sql` → Budgeting uses `V69–V73`** (additive; V1–V19 FROZEN; V20–V68 reserved for other in-flight modules the coordinator sequences). **Next free ADR is 0034.**

This ADR is the **technical data model + integration design** for the Budgeting & Management Accounting module (Phase D, PATH-TO-FULL-ERP §3.11). It translates the ratified-pending spec into: the **three budgeting tables** (`budgets`, `budget_versions`, `budget_lines`) which **consume ADR-0025's cost-centre dimension** (a budget's cost centre is a `dimension_values(id)` in the `COST_CENTRE` slot — **budgeting builds no `cost_centres` master and adds no `journal_lines` tag of its own**, both being ADR-0025's), the version-lifecycle enum + service-guarded transitions + the single-active-approved-version invariant, the **budget-vs-actual variance read** (the GL `journal_lines` aggregate — GROUPed by account [× ADR-0025's `cost_centre_value_id`] — joined to approved-version lines, signed by normal balance), the API surface, the permissions, the `ScopeGuard` cases, the Angular routes, and the **V69–V73** migration ordering. It is **concrete enough that an engineer builds the model + the version lifecycle + the variance read without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. **[Amended 2026-06-11 (Wave-2 collision #1): the original framing built a `cost_centres` master + a `journal_lines.cost_centre_id` tag + a manual-journal cost-centre threading path — ALL of which are ADR-0025's; this ADR now CONSUMES that framework and is HARD-GATED by it.]** **Budgets post no GL** — there is deliberately no `GLPostingService` call, no `gl_config` key, and no `JournalSourceType` value in this module (the one defensive deferred-namespace note aside, D-9).

## Context

GL keeps books at **company level** with **one** working analytical dimension: `journal_lines.branch_id`, a nullable analysis tag (ADR-0013 D-7) that lets a branch P&L be `journal_lines` grouped by branch. Management accounting needs a **second** dimension — the **cost centre** — and the **planning + control** layer that hangs off it: budgets, budget versions/approval, and budget-vs-actual variance. None exists. The forces:

- **THE ENABLER (the load-bearing decision): actuals must be groupable by cost centre — provided by ADR-0025, NOT built here (OQ-BUD-02).** Variance = budget vs actual, and actual = `journal_lines` movement grouped by cost centre. **[Amended 2026-06-11 (Wave-2 collision #1):** the original resolved this by adding budgeting's OWN nullable `cost_centre_id` on `journal_lines`. That collides with **ADR-0025**, which authoritatively adds **`journal_lines.cost_centre_value_id`** (the `COST_CENTRE` dimension slot, V27) for exactly this purpose. **Budgeting now CONSUMES ADR-0025's tag** — it reads the by-cost-centre actuals via ADR-0025's `actualsByAccountValuePeriod` DTO contract (D-8), grouping by `cost_centre_value_id`. Budgeting adds **no** `journal_lines` column. Resolved in **D-3 / D-8** (consume, not build).**]**

- **Cost-centre tagging breadth is ADR-0025's concern, not budgeting's (OQ-BUD-01).** **[Amended 2026-06-11 (Wave-2 collision #1):** how cost-centre tags reach the ledger — manual-journal per-line, sales-invoice/supplier-bill/stock-adjustment document defaults — is **wholly ADR-0025's** (its D-6 wires four documents). Budgeting does **not** wire any tagging path and does **not** add a `LineDraft` field. The by-cost-centre P&L completeness (which postings are tagged) is governed by ADR-0025's poster-wiring set, not by budgeting. Budgeting simply reads whatever ADR-0025 has tagged. Resolved by **deferring entirely to ADR-0025**.**]**

- **Budgets are reference data — they post NOTHING (BR-BUD-01).** A budget is a plan, not a transaction: no GL, no stock, no cash, no outbox event, no `gl_config`, no `JournalSourceType`. This is a *deliberate* departure from every prior financial module (which all post). The force is resisting the reflex to wire a posting; the budget tables are plain master/child reference data the variance read joins. Resolved in **D-4 / D-7**.

- **Budget versioning + approval is append-only with a single-active-version invariant (BR-BUD-06/12/13).** A budget has many versions; lifecycle DRAFT→SUBMITTED→APPROVED/REJECTED/SUPERSEDED; **exactly one APPROVED version per (company, FY, cost centre)** is active; approving a new version supersedes the prior. Approved/rejected/superseded versions are immutable (re-plan = new version). The new questions: the lifecycle enum + transition guards, the single-active-version enforcement (a partial unique index + a serialised supersede), and where the approval lifecycle lives. Resolved in **D-5**.

- **The approval lifecycle has no engine to lean on (OQ-BUD-05).** The generic cross-module approvals engine (PATH §3.12 X.5) is **not built**. So budget approval is a **self-contained module-local state machine** (DRAFT→SUBMITTED→APPROVED/REJECTED), designed so it can re-point at X.5 later. Resolved in **D-5**.

- **The variance read mirrors the shipped P&L read (NFR-BUD-03).** Actual = `journal_lines` aggregated by account over a period range, signed by `normal_balance` — exactly the `IncomeStatementBuilder` / `trialBalanceSumsByPeriod` pattern (ADR-0018, ADR-0013) — now additionally grouped/filtered by **ADR-0025's `cost_centre_value_id`**. **[Amended 2026-06-11: was `cost_centre_id` (budgeting's own); now ADR-0025's `cost_centre_value_id`. The by-centre index is ADR-0025's `ix_journal_lines_cost_centre` (V27), not a budgeting index.]** Resolved in **D-8**.

- **No module→module cycle; budgeting is a pure leaf CONSUMER (NFR-BUD-06, `ModuleBoundaryTest`).** Budgeting **reads** GL (accounts, fiscal periods) and **reads** ADR-0025's costing module (the cost-centre actuals aggregate + the value picker via the D-8 DTO contract). It **writes nothing** to `journal_lines`. **[Amended 2026-06-11 (Wave-2 collision #1):** the original had budgeting *write* a `cost_centre_id` tag through a GL manual-journal path (needing a `CostCentreLookup` port to avoid a `gl→budgeting` cycle). **That write path is DROPPED** — cost-centre tagging is ADR-0025's. So there is **no GL→budgeting edge at all** and **no port** is needed; budgeting is `budgeting → gl (read)` + `budgeting → costing (read)`, both leaf-consumer edges like `reporting`. Resolved in **D-10**.**]**

- **Schema freeze / direction.** IAM=V1 … Sales Returns=V19, all frozen; V20–V68 reserved for other in-flight modules. Budgeting is additive **V69–V73**: the **three budget tables** (`budgets`, `budget_versions`, `budget_lines`), the permission seed, the `BUDGET` `code_sequence` kind (lazy — no seed). **[Amended 2026-06-11 (Wave-2 collision #1): the `cost_centres` master and the `journal_lines.cost_centre_id` tag are DROPPED from this range — both are ADR-0025's (`dimension_values` + `journal_lines.cost_centre_value_id`, V27–V29). Budgeting's V69–V73 therefore touch NO frozen table.]** It imports no other module's *entity*; it **reads** `gl` (accounts/periods) and **reads** `costing`/ADR-0025 (the cost-centre value picker + the actuals aggregate, D-8) — both leaf-consumer read edges, like `reporting` (D-10).

## Decision

### D-1 — Module placement: one new `com.erp.modules.budgeting` module; controllers flat in `com.erp.api`

Budgets + versions + variance live under **`com.erp.modules.budgeting`**. **`budgeting`, not `controlling`:** the dominant deliverable is budgeting & management accounting (PATH area 14); a future profit-centre / allocation / forecast slice is a sibling *within* budgeting. `budgeting` is the durable flat name, consistent with `gl`/`ar`/`ap`/`tax`/`reporting`.

> **Amended 2026-06-11 (Wave-2 collision #1): the cost-centre dimension is NOT owned by `budgeting` — it is ADR-0025's (`com.erp.modules.costing`).** The original ADR-0034 owned the cost-centre master + its lifecycle + a `journal_lines.cost_centre_id` column it set through `gl.service`. That collides with ADR-0025, which owns the `dimensions`/`dimension_values` masters, their lifecycle/resolver, and the `journal_lines.cost_centre_value_id` slot-column. **Budgeting now CONSUMES that framework**: a budget's cost centre is a `dimension_values(id)` in the `COST_CENTRE` slot, resolved via ADR-0025's `DimensionService.resolveValue(uid)` / `listActiveValues(companyId, COST_CENTRE)`. Budgeting owns **no** cost-centre entity, repository, service, controller, enum, or numbering kind, and writes **nothing** to `journal_lines`.

Internal layout:

```
com.erp.modules.budgeting
├── domain.entity   Budget, BudgetVersion, BudgetLine
│                   // Amended 2026-06-11: CostCentre entity DROPPED — the cost-centre master is ADR-0025's DimensionValue.
├── domain.dto      BudgetDto / CreateBudgetRequest,            // CreateBudgetRequest carries costCentreValueUid (ADR-0025 dimension value), nullable = company-wide
│                   BudgetVersionDto / CreateBudgetVersionRequest / SubmitBudgetVersionRequest /
│                       ApproveBudgetVersionRequest / RejectBudgetVersionRequest,
│                   BudgetLineDto / UpsertBudgetLineRequest / SpreadAnnualRequest /
│                       SeedFromVersionRequest,
│                   VarianceReportDto / VarianceRowDto / VarianceQuery,
│                   DepartmentalActualsDto / DepartmentalActualsRowDto
│                   // Amended 2026-06-11: CostCentreDto/Create/Update DROPPED — the picker uses ADR-0025's DimensionValueDto.
├── domain.enums    BudgetVersionStatus (DRAFT|SUBMITTED|APPROVED|REJECTED|SUPERSEDED) (D-5)
│                   // Amended 2026-06-11: CostCentreType enum DROPPED — ADR-0025's dimension model has no per-value type enum.
├── repository      BudgetRepository, BudgetVersionRepository, BudgetLineRepository
│                   // Amended 2026-06-11: CostCentreRepository DROPPED.
└── service         BudgetService(+Impl)            — budget + version CRUD + the lifecycle (D-5),
                    BudgetVersionLifecycle          — the service-guarded transition machine (D-5),
                    BudgetSpreadCalculator          — annual→12-period even spread, HALF_UP (D-4),
                    BudgetingNumberGenerator        — BUDGET-#### via code_sequence (D-11),   // CC-#### DROPPED (ADR-0025 owns CC numbering)
                    VarianceReportQuery             — budget vs GL-actual aggregate by cost_centre_value_id (D-8),
                    DepartmentalActualsQuery        — GL actuals by cost_centre_value_id × account (D-8)
                    // Amended 2026-06-11: CostCentreService DROPPED — budgeting reads ADR-0025's DimensionService.
```

Controllers stay flat in `com.erp.api`: **`BudgetController`**, **`BudgetVersionController`**, **`BudgetReportController`** — touching only services (`ModuleBoundaryTest`). **[Amended 2026-06-11: `CostCentreController` DROPPED — cost-centre CRUD is ADR-0025's `DimensionValueController`. Budgeting writes nothing to `journal_lines`.]**

### D-2 — The three budget tables (no cost-centre master, no GL column — both are ADR-0025's)

All children plural per the shipped convention. Every table carries `company_id` (NFR-BUD-01) and participates in the §3.2 tenant predicate. Money columns `NUMERIC(19,4)`; `uid VARCHAR(26)` ULID; standard audit cols; `@Version` on the headers that take lifecycle transitions.

Table groups:
- **(a)** `budgets` — the budget header, scoped to (company, fiscal year, [cost centre = ADR-0025 `dimension_values(id)`]) (D-4a).
- **(b)** `budget_versions` — one revision with the lifecycle (D-4b / D-5).
- **(c)** `budget_lines` — (version, account, fiscal period, amount) (D-4c).

> **Amended 2026-06-11 (Wave-2 collision #1):** the original D-2 listed **four** tables (a `cost_centres` master + the budget triple) **plus** an additive `journal_lines.cost_centre_id` column. The **`cost_centres` master is DROPPED** (the cost-centre master is ADR-0025's `dimension_values`) and the **`journal_lines.cost_centre_id` column is DROPPED** (the cost-centre tag is ADR-0025's `journal_lines.cost_centre_value_id`, V27). Budgeting's migrations touch **no frozen table** and create **no dimension master**.

### D-3 — Cost-centre dimension: CONSUMED from ADR-0025 (not built here)

> **Amended 2026-06-11 (Wave-2 collision #1) — the entire original D-3 ("the cost-centre dimension framework") is REMOVED and replaced by this consume-statement.** The original D-3 built (a) a `cost_centres` master and (b) an additive `journal_lines.cost_centre_id` analysis tag + the `ix_journal_lines_company_cc_account` index. **All of that is ADR-0025's**, and re-building it collides with ADR-0025 on a frozen, finance-critical table. Budgeting now CONSUMES ADR-0025:

- **The cost-centre master = ADR-0025's `dimension_values`** (the members of the built-in `COST_CENTRE` dimension, ADR-0025 D-2). Budgeting references a cost centre by its `dimension_values(id)` (a `Long`, scalar FK) and addresses it externally by `dimension_values.uid`. A budget's cost-centre picker calls ADR-0025's `DimensionService.listActiveValues(companyId, COST_CENTRE)` and resolves a chosen uid via `DimensionService.resolveValue(uid)` (D-8 contract). Cost-centre CRUD, lifecycle, `is_active` tagging-gate, numbering, and `parent_id` roll-up are **all ADR-0025's** — budgeting builds none of it.
- **The GL analysis tag = ADR-0025's `journal_lines.cost_centre_value_id`** (the `COST_CENTRE` slot-column, ADR-0025 D-3, V27). It is the line-grain, nullable, analysis-only tag (NULL = Unallocated) that mirrors `branch_id` — exactly the shape the original ADR-0034 D-3b proposed, now provided once by ADR-0025. **Budgeting adds no `journal_lines` column and no index** — the by-centre variance aggregate uses ADR-0025's `ix_journal_lines_cost_centre` (V27).
- **Tagging the ledger** (which postings carry a cost centre) is **ADR-0025's poster-wiring** (its D-6 wires manual journal + sales invoice + supplier bill + stock adjustment). Budgeting wires **no** posting path and adds **no** `LineDraft` field. The "by-centre P&L is partial until operational documents are wired" caveat is now governed by ADR-0025's wired-poster set, surfaced honestly by the variance report's Unallocated bucket (OQ-BUD-07).
- **The non-breaking guarantee** (every shipped statement byte-identical for untagged lines) is ADR-0025's proof obligation (its `MigrationKeepDataIT` extends to V27); budgeting inherits it and adds nothing of its own to that frozen table.

### D-4 — The budget triple (header → version → line); budgets post NOTHING

#### (a) `budgets` (header, per company)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | BIGINT IDENTITY / VARCHAR(26) | NO | `uq_budget_uid`; `ScopeGuard case "budget"` |
| `company_id` | BIGINT | NO | FK → `companies(id)`; tenant scope |
| `budget_number` | VARCHAR(30) | NO | `BUDGET-####` at create (D-11); `uq_budget_company_number UNIQUE (company_id, budget_number)` |
| `name` | VARCHAR(160) | NO | e.g. `Sales Dept FY2026 Operating Budget` |
| `fiscal_year_id` | BIGINT | NO | FK → `fiscal_years(id)`; the FY this budget plans (BR-BUD-03) |
| `cost_centre_value_id` | BIGINT | YES | FK → **ADR-0025 `dimension_values(id)`** (the value in the `COST_CENTRE` slot); the centre this budget is for; **NULL = company-wide budget** (BR-BUD-03). **[Amended 2026-06-11: was `cost_centre_id → cost_centres(id)`; now references ADR-0025's dimension value.]** |
| `notes` | VARCHAR(500) | YES | |
| `version` (`@Version`) + audit cols | | | standard |

Constraints:
- `uq_budget_uid UNIQUE (uid)`; `uq_budget_company_number UNIQUE (company_id, budget_number)`.
- **`uq_budget_company_year_cc UNIQUE (company_id, fiscal_year_id, cost_centre_value_id)`** — at most **one budget per (company, FY, cost centre)** (a company-wide budget has `cost_centre_value_id IS NULL`; Postgres treats NULLs as distinct, so multiple company-wide budgets for the *same* FY would slip past — guard the single company-wide budget per FY in the **service** + a **partial unique** `uq_budget_company_year_companywide UNIQUE (company_id, fiscal_year_id) WHERE cost_centre_value_id IS NULL`). This makes "the budget for this scope" unambiguous, which the single-active-version invariant (D-5) builds on.
- `fk_budget_company`, `fk_budget_fiscal_year` (→ `fiscal_years`), `fk_budget_cost_centre_value` (→ **ADR-0025 `dimension_values(id)`** — a scalar-id cross-module FK within the one Postgres, the same shape ADR-0025's own `journal_lines.cost_centre_value_id` FK takes). **[Amended 2026-06-11.]**

Index: `CREATE INDEX ix_budgets_company_year ON budgets (company_id, fiscal_year_id);`

#### (b) `budget_versions` (child of `budgets` — carries the lifecycle, D-5)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_budget_version_uid`; `ScopeGuard case "budgetversion"` |
| `budget_id` | BIGINT | NO | FK → `budgets(id)` |
| `company_id` | BIGINT | NO | denormalised (tenant predicate without a join) |
| `fiscal_year_id` | BIGINT | NO | denormalised from the budget (the single-active-version partial unique reads it — see below) |
| `cost_centre_value_id` | BIGINT | YES | denormalised from the budget (same reason; NULL for company-wide); FK → ADR-0025 `dimension_values(id)`. **[Amended 2026-06-11: was `cost_centre_id`.]** |
| `version_no` | SMALLINT | NO | 1-based ordinal within the budget; `uq_budget_version_no UNIQUE (budget_id, version_no)` |
| `status` | VARCHAR(20) | NO | `BudgetVersionStatus`; DEFAULT `'DRAFT'`; `chk_budget_version_status` (D-5) |
| `label` | VARCHAR(120) | YES | optional human label, e.g. `Q2 re-plan` |
| `seeded_from_version_id` | BIGINT | YES | FK → `budget_versions(id)` (self); the version this was copied from (FR-BUD-08c), NULL if blank |
| `submitted_at`/`submitted_by` | TIMESTAMPTZ/BIGINT | YES | set on SUBMIT |
| `approved_at`/`approved_by` | TIMESTAMPTZ/BIGINT | YES | set on APPROVE |
| `rejected_at`/`rejected_by` | TIMESTAMPTZ/BIGINT | YES | set on REJECT |
| `superseded_at` | TIMESTAMPTZ | YES | set when another version is approved over it |
| `decision_reason` | VARCHAR(500) | YES | reject reason / approval note (audited, BR-BUD-12/13) |
| `version` (`@Version`) + audit cols | | | `@Version` is load-bearing for the concurrent-approve race (NFR-BUD-04) |

Constraints:
- `uq_budget_version_uid UNIQUE (uid)`; `uq_budget_version_no UNIQUE (budget_id, version_no)`.
- `chk_budget_version_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','SUPERSEDED'))`.
- `fk_budget_version_budget` (→ `budgets`), `fk_budget_version_company`, `fk_budget_version_fy` (→ `fiscal_years`), `fk_budget_version_cc_value` (→ **ADR-0025 `dimension_values(id)`**), `fk_budget_version_seeded_from` (→ `budget_versions`, self), `fk_budget_version_submitted_by`/`_approved_by`/`_rejected_by` (→ `app_users`). **[Amended 2026-06-11: the cost-centre FK now targets ADR-0025's `dimension_values`, was `cost_centres`.]**
- **THE SINGLE-ACTIVE-VERSION INVARIANT (BR-BUD-12) at the DB — a partial unique:**
  ```
  CREATE UNIQUE INDEX uq_budget_version_one_approved
      ON budget_versions (company_id, fiscal_year_id, cost_centre_value_id)
      WHERE status = 'APPROVED';
  ```
  At most one APPROVED version per (company, FY, cost centre). **Caveat:** Postgres treats NULL `cost_centre_value_id` (company-wide) as distinct, so this partial unique does **not** guard the company-wide case — that one is guarded by the service supersede-in-one-TX + a second partial unique `uq_budget_version_one_approved_companywide ... WHERE status='APPROVED' AND cost_centre_value_id IS NULL` over `(company_id, fiscal_year_id)`. Both backstops + the serialised supersede (D-5) make two-APPROVED structurally impossible.

Index: `CREATE INDEX ix_budget_versions_budget ON budget_versions (budget_id);` + `CREATE INDEX ix_budget_versions_active ON budget_versions (company_id, fiscal_year_id, cost_centre_value_id) WHERE status = 'APPROVED';` (the variance read's "find the active version" lookup).

#### (c) `budget_lines` (child of `budget_versions`)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_budget_line_uid` |
| `budget_version_id` | BIGINT | NO | FK → `budget_versions(id)` |
| `company_id` | BIGINT | NO | denormalised |
| `account_id` | BIGINT | NO | FK → `chart_of_accounts(id)`; the account this line budgets (active at entry, service — FR-BUD-07) |
| `fiscal_period_id` | BIGINT | NO | FK → `fiscal_periods(id)`; the period; must belong to the budget's FY (service, BR-BUD-04) |
| `amount` | NUMERIC(19,4) | NO | the budgeted magnitude (base currency, HALF_UP); `chk_budget_line_amount CHECK (amount >= 0)` (BR-BUD-09) |
| `currency` | VARCHAR(3) | NO | = company base (BR-BUD-10); service asserts |
| `line_memo` | VARCHAR(255) | YES | |
| audit cols | | | **no `@Version`** — lines are replaced wholesale per version edit; the version's `@Version` guards concurrency |

Constraints:
- `uq_budget_line_uid UNIQUE (uid)`.
- **`uq_budget_line_version_account_period UNIQUE (budget_version_id, account_id, fiscal_period_id)`** — at most one amount per (account, period) per version (BR-BUD-04).
- `chk_budget_line_amount CHECK (amount >= 0)`.
- `fk_budget_line_version` (→ `budget_versions`), `fk_budget_line_company`, `fk_budget_line_account` (→ `chart_of_accounts`), `fk_budget_line_period` (→ `fiscal_periods`).

Index:
```
CREATE INDEX ix_budget_lines_version          ON budget_lines (budget_version_id);
CREATE INDEX ix_budget_lines_version_account  ON budget_lines (budget_version_id, account_id);  -- the variance join
```

> **Budgets post NOTHING (BR-BUD-01) — the deliberate non-decision.** There is **no** `GLPostingService.post` from this module, **no** new `GlConfigKey`, **no** new `JournalSourceType`. A budget line is plain reference data. The variance read **joins** approved-version `budget_lines` against the `journal_lines` aggregate; nothing in budgeting writes a journal. This is intentional and stated so a future reader does not "complete" the module by wiring a posting. (The one place budgeting touches the ledger is *reading* it for actuals (D-8) and *setting the analysis tag* on a manual-journal post (D-6) — neither is a budget posting.)

### D-5 — Budget version lifecycle: a self-contained, service-guarded state machine; single active approved version

**`BudgetVersionStatus`** enum + transitions (the generic approvals engine X.5 is **not built**, so this is module-local — OQ-BUD-05):

```
DRAFT ──submit──▶ SUBMITTED ──approve──▶ APPROVED   (active; supersedes the prior approved version of the same scope)
  ▲                   │                       │
  │                   ├──reject──▶ REJECTED   (terminal; re-plan = new version)
  └──recall───────────┘                       │
                                              ▼
                                          SUPERSEDED   (when a newer version is approved; terminal, retained)
```

- **`submit` (DRAFT→SUBMITTED, `BUDGETING.BUDGET.SUBMIT`):** rejects if the version has **zero lines** (BR-BUD-11); locks lines from edit; stamps `submitted_at/by`; audited.
- **`recall` (SUBMITTED→DRAFT, `BUDGETING.BUDGET.SUBMIT`):** unlocks for correction; audited. (FR-BUD-14.)
- **`approve` (SUBMITTED→APPROVED, `BUDGETING.BUDGET.APPROVE`):** in **one TX** — (1) find the current APPROVED version for the same (company, FY, cost centre); if present set it `SUPERSEDED` + `superseded_at` (under its `@Version`); (2) set this version `APPROVED` + `approved_at/by` (under its `@Version`); the partial unique `uq_budget_version_one_approved` is the DB backstop — a concurrent second approve hits the unique violation and retries/loses (NFR-BUD-04). Audited with the active-version handover.
- **`reject` (SUBMITTED→REJECTED, `BUDGETING.BUDGET.APPROVE`):** stamps `rejected_at/by` + `decision_reason`; terminal; audited.
- **Immutability (BR-BUD-06):** `budget_lines` are editable **only** while the owning version is DRAFT (or recalled-to-DRAFT). The service rejects any line upsert on a SUBMITTED/APPROVED/REJECTED/SUPERSEDED version. (SUBMITTED is line-locked too — edit requires recall first.)
- **Re-plan (FR-BUD-13):** `BudgetService.createVersion(budgetUid, seedFromVersionUid?)` opens a new DRAFT version (`version_no = max+1`), optionally copying lines from any existing version (`seeded_from_version_id` set). The prior approved version stays APPROVED until the new one is approved (then superseded) — so there is always a clean active version.

**Maker≠checker (OQ-BUD-04):** **not hard-enforced** in v1 (the same user may hold `SUBMIT` + `APPROVE`); a one-line "approver ≠ submitter" guard is the addable later. Stated so it is a conscious choice, not an oversight.

**No outbox event** for any transition — approval has **no cross-module effect** (it changes which version variance reads; nothing else reacts). Pure synchronous service + audit. (Contrast: every prior financial module posted GL on its lifecycle; budgeting does not — D-7.)

### D-6 — Cost-centre tagging onto the ledger: ADR-0025's job, NOT budgeting's

> **Amended 2026-06-11 (Wave-2 collision #1) — the entire original D-6 ("manual-journal cost-centre tagging") is REMOVED.** The original made budgeting the owner of the only write path to a budgeting-defined `journal_lines.cost_centre_id`: it added a `LineDraft.costCentreId` field, a `CostCentreLookup` port in GL (DIP) implemented by budgeting, a `CostCentreService.resolveActiveIdForCompany`, and a cost-centre picker on the post-journal screen. **All of that is ADR-0025's** (its D-4 extends `LineDraft` with the dimension value ids; its D-5 `DimensionResolver` validates them; its D-6 wires the manual-journal per-line picker + three other document defaults; its `journal_lines.cost_centre_value_id` is the written column). **Budgeting now writes NOTHING to `journal_lines`, adds NO `LineDraft` field, declares NO `CostCentreLookup` port, and adds NO picker** — and therefore needs no `gl → budgeting` edge and no DIP indirection at all.

- **Who tags the ledger:** ADR-0025. Whichever postings carry a `cost_centre_value_id` is governed by ADR-0025's wired-poster set (manual journal + sales invoice + supplier bill + stock adjustment, its D-6) and its `is_mandatory` governance flag. Budgeting consumes whatever is tagged.
- **Budgeting's only ledger interaction is READING** the cost-centre actuals aggregate via ADR-0025's `actualsByAccountValuePeriod` DTO contract (D-8). It never sets a tag.
- **No code touch on GL from budgeting.** No `LineDraft` change, no port, no manual-journal UI change attributable to budgeting (those are ADR-0025's, if/when its picker ships).

### D-7 — GL postings: NONE. No new `gl_config` key, no new `JournalSourceType`, no new CoA account.

**Explicitly: this module introduces zero GL posting.** Budgets are reference data (BR-BUD-01). Therefore:
- **No new `GlConfigKey`** — there is no posting-role to map.
- **No new `JournalSourceType`** — there is no journal source (budgeting writes no journal). The `chk_journal_batch_source_type` / `chk_journal_entry_source_type` CHECKs are **untouched**.
- **No new CoA account code** — budgeting plans against the **existing** chart of accounts (5300 Salaries, 5400 Utilities, 4100 Sales Revenue, … all shipped V10); it adds none.
- The **only** ledger interaction is **reading** `journal_lines` for actuals (D-8), grouped by ADR-0025's `cost_centre_value_id`. **[Amended 2026-06-11: the original point (2) "setting the `cost_centre_id` analysis tag on a manual-journal post" is REMOVED — budgeting sets no tag; tagging is wholly ADR-0025's (D-6).]**

This is the headline departure from the prior nine financial modules and is stated up front so it survives review: **do not add a `gl_config` key or `JournalSourceType` for budgeting.**

### D-8 — Budget-vs-actual variance read + departmental actuals (mirrors the shipped P&L read)

**`VarianceReportQuery.run(VarianceQuery)`** — `@Transactional(readOnly = true)`, `assertCanActIn` first (NFR-BUD-01). Inputs: `companyId`, `fiscalYearId`, period range (`fromPeriodNo`..`toPeriodNo`, default 1..12), optional `costCentreValueUid` (resolved via ADR-0025's `DimensionService.resolveValue`), optional account-type/range filter. **[Amended 2026-06-11: was `costCentreId`; now the cost centre is ADR-0025's dimension value, addressed by `costCentreValueUid`.]**

1. **Resolve the active budget version** for (company, fiscalYear, costCentreValue) — the single APPROVED version (`ix_budget_versions_active`). If none → all budget = 0 + `noApprovedBudget = true` flag (FR-BUD-16 / BR-BUD-12).
2. **Budget side:** `Σ budget_lines.amount` for that version's lines whose `fiscal_period_id` is in the chosen period range, GROUP BY `account_id` (`ix_budget_lines_version_account`).
3. **Actual side — CONSUMED from ADR-0025, not a budgeting-owned query.** **[Amended 2026-06-11 (Wave-2 collision #1):** the original described a **new** `JournalLineRepository` method (a `cost_centre_id`-aware sibling of `periodMovementByAccount`) owned by GL. That is now superseded by **ADR-0025's documented read contract `DimensionSlicedReportQuery.actualsByAccountValuePeriod(companyId, COST_CENTRE, fromDate, toDate)`** (ADR-0025 D-8) — the actuals aggregate over `journal_lines` GROUPed by `account_id × cost_centre_value_id`, signed by `normal_balance`, hitting ADR-0025's `ix_journal_lines_cost_centre`. **Budgeting consumes this DTO; it does NOT query `journal_lines` for cost-centre actuals and adds no GL repository method.** When a centre is chosen, budgeting filters the returned rows to that `cost_centre_value_id`; company-wide sums across all values + the NULL (Unallocated) bucket. The normal-balance signing is ADR-0025's (its read mirrors the shipped `IncomeStatementBuilder`).**]**
4. **Combine:** full-outer-join budget and actual on `account_id` (an account may have a budget but no actual, or vice versa); per row compute `variance = actual − budget`, `variancePct = (budget != 0) ? variance/budget : null` (BR-BUD-15). The favourable/adverse **label** is derived from `account_type` in the DTO/UI, not stored.
5. **Unallocated bucket (OQ-BUD-07):** when **no** centre is chosen (company-wide variance) the actual aggregate includes all lines; when a centre **is** chosen, only that centre's `cost_centre_value_id` rows — and the `cost_centre_value_id IS NULL` "Unallocated" rows from ADR-0025's aggregate are surfaced separately so they are never silently misattributed (BR-BUD-14).

Output `VarianceReportDto { header(companyUid, fiscalYearCode, fromPeriod, toPeriod, costCentreValueUid?, noApprovedBudget), rows: [VarianceRowDto(accountCode, accountName, accountType, budgetAmount, actualAmount, varianceAmount, variancePct)], totalsByType }`.

**`DepartmentalActualsQuery.run(...)`** (FR-BUD-17): consumes ADR-0025's `actualsByAccountValuePeriod` and presents it GROUPed by `cost_centre_value_id × account_id` over a period range, **no budget join** — the by-centre P&L slice (each row = centre × account × actual; NULL value = Unallocated). **[Amended 2026-06-11: reads ADR-0025's aggregate; was a budgeting `cost_centre_id` GL query.]** (This overlaps ADR-0025's own dimension-sliced TB read; budgeting's departmental view is the account-type-signed P&L presentation of the same underlying aggregate, owner-confirmable whether it lives in budgeting or is dropped in favour of ADR-0025's read.)

**Export (FR-BUD-18):** both reads flatten to the shipped `ReportExporter` / `CsvStatementRenderer` (ADR-0018) for CSV; PDF/Excel only if the existing renderers accept the flattened model without new work (else CSV-only in v1).

### D-9 — Events: NONE published in v1; one `DomainEventType` constant reserved (namespace claim only)

Budgeting publishes **no outbox event in v1** — no cross-module effect exists (D-5/D-7). For the coordinator's collision-detection and the deferred enforcement/commitment round (PATH §3.11), **one `DomainEventType` constant is declared (reserved) but NOT published**:
- `BUDGET.VERSION.APPROVED` (`"BUDGET.VERSION.APPROVED"`) — reserved for a future round where downstream consumers (e.g. commitment/encumbrance, notifications) react to an approved budget.

Aggregate-type constant reserved: `AGG_BUDGET_VERSION = "BUDGET_VERSION"`. **Declared in `DomainEventType` to claim the namespace; no handler consumes it and no producer publishes it in this increment.** (If the build team prefers, they may omit the declaration until the consuming round lands. The coordinator decides.)

> **Amended 2026-06-11 (Wave-2 collision #1): the `COST_CENTRE.DEACTIVATED` reserved event constant + `AGG_COST_CENTRE` aggregate constant are DROPPED** — cost-centre lifecycle (including deactivation events) belongs to ADR-0025, which owns the cost-centre master (`dimension_values`). Budgeting reserves only its own `BUDGET.VERSION.APPROVED` namespace.

### D-10 — ArchUnit edges (no cycle)

- **`budgeting.service` → `gl.repository` / `gl.domain.dto`** — budgeting **reads** GL: `ChartOfAccountRepository` (validate the budget-line account active + same company), `FiscalYearRepository`/`FiscalPeriodRepository` (validate FY/period). **Allowed** — budgeting is a leaf **consumer** of GL, the same stance `reporting` takes. Budgeting imports **no GL entity**; it reads ids/DTOs/projections.
- **`budgeting.service` → `costing.service` / `costing.domain.dto`** (ADR-0025) — budgeting **reads** the cost-centre dimension: `DimensionService.resolveValue(uid)` / `listActiveValues(companyId, COST_CENTRE)` (the value picker + budget-attachment resolution) and `DimensionSlicedReportQuery.actualsByAccountValuePeriod(...)` (the cost-centre actuals aggregate, D-8). **Allowed** — a leaf service/DTO read edge, the established consumer shape (ADR-0025 D-8 designed this contract precisely for budgeting). **[Amended 2026-06-11 (Wave-2 collision #1): this is the NEW load-bearing edge — budgeting depends on ADR-0025's `costing` module.]**
- **`gl` → `budgeting`: NONE.** **[Amended 2026-06-11: the original `CostCentreLookup` port in GL (DIP, implemented by budgeting) is DROPPED entirely — budgeting writes no `journal_lines` tag, so GL needs no budgeting lookup. There is no `gl → budgeting` edge of any kind and no port/adapter indirection. The cost-centre validation/resolution at post time is ADR-0025's `DimensionResolver` (its D-5), reached by ADR-0025's wired posters, not budgeting's.]**
- **`budgeting` → `iam` (companies/fiscal scope), `platform.security` (ScopeGuard/perm), `platform.audit`** — the standard spine edges, allowed.
- **Controllers (`com.erp.api.*`) → `budgeting.service` only** — never a repository (`ModuleBoundaryTest` controller↛repository).
- **No cycle:** `budgeting → gl` (read) + `budgeting → costing` (read) — both one-directional leaf-consumer edges. Neither `gl`, `costing`, nor `reporting` depends on `budgeting`'s packages. The `ModuleBoundaryTest` rule set is unchanged; the new module's edges fit the existing allowances (consumer-reads-gl / consumer-reads-costing, like reporting reads gl).

### D-11 — Numbering: one new `code_sequence` kind (BUDGET)

`BudgetingNumberGenerator` reuses the shipped `code_sequence` row-locked allocation (ADR-0007 D-6) with **one** new `entity_kind` value: `BUDGET` (`BUDGET-%04d`), per company, concurrency-safe (NFR-BUD-07). Allocation timing: `BUDGET-####` at budget **create**. **No new numbering table** — only the new `entity_kind` row, created lazily with `next_value = 1` on first use (the shipped mechanism). The `uq_budget_company_number` constraint backstops generator bugs. **No seed rows → no #12 seed-uid exposure for numbering.** **[Amended 2026-06-11 (Wave-2 collision #1): the `COST_CENTRE` (`CC-%04d`) kind is DROPPED — cost-centre numbering is ADR-0025's concern (ADR-0025 D-2 adopts user-supplied value codes, no `code_sequence` kind).]**

### D-12 — Permissions (`BUDGETING.RESOURCE.ACTION`) + `@perm` gating

Six permissions, module `budgeting`, gated with `@perm.has` / `@perm.scoped` (NEVER `hasAuthority`):

| permission | gates |
|---|---|
| `BUDGETING.BUDGET.VIEW` | list/view budgets + versions + lines |
| `BUDGETING.BUDGET.MANAGE` | create budget; create/edit version + lines; create new version (seed/re-plan) |
| `BUDGETING.BUDGET.SUBMIT` | submit a version for approval; recall a submitted version |
| `BUDGETING.BUDGET.APPROVE` | approve / reject a submitted version |
| `BUDGETING.REPORT.VIEW` | budget-vs-actual variance report; departmental actuals view |
| `BUDGETING.REPORT.EXPORT` | export the variance / departmental reports (CSV) |

> **Amended 2026-06-11 (Wave-2 collision #1): the `BUDGETING.COSTCENTRE.VIEW` and `BUDGETING.COSTCENTRE.MANAGE` permissions are DROPPED** (was eight perms; now six). Cost-centre list/CRUD is gated by **ADR-0025's `COSTING.VIEW` / `COSTING.MANAGE`** (its D-10). The cost-centre **picker** on the budget create/edit screen reads ADR-0025's active cost-centre values and is gated by `COSTING.VIEW` (the budget screen itself is gated by `BUDGETING.BUDGET.MANAGE`). There is no budgeting cost-centre picker on the post-journal screen — that picker is ADR-0025's (its D-6/D-11), gated by `COSTING.TAG`.

- Path-uid ops use `@perm.scoped(#uid, '<targetType>', '<CODE>')` (e.g. `@perm.scoped(#uid,'budgetversion','BUDGETING.BUDGET.APPROVE')`); list/create ops use `@perm.has('<CODE>')` + `assertCanActIn` on the body company. **[Amended 2026-06-11: the prior note about a manual-journal cost-centre field/picker gated by `BUDGETING.COSTCENTRE.VIEW` is removed — that field is ADR-0025's, gated by `COSTING.TAG`/`COSTING.VIEW`.]**
- Seeded in V69 with `ON CONFLICT (code) DO NOTHING`; granted to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (the V7/V12/V14/V17 pattern). Permissions have no `uid` → **#12 N/A**.

### D-13 — `ScopeGuard` cases + the new repositories injected

**Two** new target types added to `ScopeGuard.companyIdOf(...)` (the verified switch) + their repositories injected into `ScopeGuard` (the documented pattern, lines 108-113 added `quotation`/`salesorder`/`delivery`/`salesreturn`):
- `case "budget" -> budgets.findCompanyIdByUid(uid);`
- `case "budgetversion" -> budgetVersions.findCompanyIdByUid(uid);`

> **Amended 2026-06-11 (Wave-2 collision #1): the `case "costcentre"` is DROPPED** (was three cases; now two). Cost-centre uid scoping uses **ADR-0025's `case "dimensionvalue"`** (its D-5). Budgeting injects only `BudgetRepository` + `BudgetVersionRepository` into `ScopeGuard` (no `CostCentreRepository`).

(`budget_lines` are addressed only via their version — no standalone `case`; line ops scope through the version's company.) Each repository exposes `findCompanyIdByUid(String uid)` returning `Optional<Long>` (the shipped projection method on every scoped repository).

### D-14 — Angular nav routes (additive to `web/src/app/features/admin/admin.routes.ts`)

A new "Budgeting" nav group, each route `requirePermission`-guarded (the shipped pattern):

| route path | permission | screen |
|---|---|---|
| `budgeting/budgets` | `BUDGETING.BUDGET.VIEW` | budget list (with active-version badge) |
| `budgeting/budgets/uid/:uid` | `BUDGETING.BUDGET.VIEW` | budget detail — versions + lines + submit/approve actions (action buttons gated client-side by `BUDGETING.BUDGET.SUBMIT`/`APPROVE`; server is the authority) |
| `budgeting/variance` | `BUDGETING.REPORT.VIEW` | budget-vs-actual variance report (FY / period-range / cost-centre-value / account filters; CSV export) |
| `budgeting/departmental-actuals` | `BUDGETING.REPORT.VIEW` | departmental actuals (cost-centre-value × account) |

> **Amended 2026-06-11 (Wave-2 collision #1): the `budgeting/cost-centres` + `budgeting/cost-centres/uid/:uid` routes are DROPPED** (was six routes; now four). Cost-centre list/detail/CRUD lives under **ADR-0025's `/costing/cost-centres`** route (its D-11). The budget create/edit screen's cost-centre **picker** reads ADR-0025's active cost-centre values. No manual-journal cost-centre picker is contributed by budgeting (that field is ADR-0025's, on the `gl/journals/post` screen, its D-6/D-11).

### API surface (controllers + endpoints — flat in `com.erp.api`)

- **[Amended 2026-06-11 (Wave-2 collision #1): `CostCentreController` is DROPPED — cost-centre CRUD is ADR-0025's `DimensionValueController` (`/api/cost-centres` belongs to ADR-0025/costing). The budget create request carries a `costCentreValueUid` (an ADR-0025 dimension value uid) which `BudgetService` resolves via ADR-0025's `DimensionService.resolveValue`.]**
- **`BudgetController`** — `GET /api/budgets` (list, paged, `BUDGETING.BUDGET.VIEW`); `GET /api/budgets/{uid}` (scoped VIEW — includes versions); `POST /api/budgets` (create, carries `costCentreValueUid?`, `BUDGETING.BUDGET.MANAGE`); `POST /api/budgets/{uid}/versions` (create new version, optional `seedFromVersionUid`, scoped MANAGE).
- **`BudgetVersionController`** — `GET /api/budget-versions/{uid}` (scoped `budgetversion` VIEW — includes lines); `PUT /api/budget-versions/{uid}/lines` (upsert lines — per-period / annual-spread / seed; scoped MANAGE; rejected if version not DRAFT); `POST /api/budget-versions/{uid}/submit` (scoped SUBMIT); `POST /api/budget-versions/{uid}/recall` (scoped SUBMIT); `POST /api/budget-versions/{uid}/approve` (scoped APPROVE); `POST /api/budget-versions/{uid}/reject` (reason; scoped APPROVE).
- **`BudgetReportController`** — `GET /api/budgeting/variance?fiscalYearUid=&fromPeriod=&toPeriod=&costCentreValueUid=&accountType=` (`BUDGETING.REPORT.VIEW`) [Amended 2026-06-11: `costCentreUid` → `costCentreValueUid`]; `GET /api/budgeting/departmental-actuals?...` (`BUDGETING.REPORT.VIEW`); `GET /api/budgeting/variance/export?format=CSV&...` (`BUDGETING.REPORT.EXPORT`).
- All responses in the shipped `ApiResponse<T>` envelope; DTOs `*Dto`-suffixed; `assertCanActIn` in every service method.

## Migration ordering (additive; V1–V19 FROZEN; #12-safe seeds; V69–V73)

> **Amended 2026-06-11 (Wave-2 collision #1): the cost-centre migration steps are REMOVED from this range.** The original V69 created the `cost_centres` table AND altered the frozen `journal_lines` (adding `cost_centre_id` + its FK + `ix_journal_lines_company_cc_account`). **All of that is now ADR-0025's V27** (`dimensions`/`dimension_values` + the `journal_lines.cost_centre_value_id` slot-column + `ix_journal_lines_cost_centre`). **Budgeting's V69–V73 therefore touch NO frozen table, create NO dimension master, and add NO `journal_lines` column or index** — budgeting only creates its own three tables + perms. The V69–V73 **range is unchanged** (kept exactly as the coordinator assigned); only the *content* of the migrations changes.

The increment is now **additive migrations within V69–V73** (one logical concern each; they could collapse to fewer files, but the V69–V73 range is claimed and the split keeps each reviewable and lets the build stage cleanly). **Budgeting is HARD-GATED by ADR-0025's V27–V29** (the `dimension_values` table + the `journal_lines.cost_centre_value_id` column must exist before V70's `budgets.fk_budget_cost_centre_value` FK can be created — so V69–V73 must run *after* V27–V29; the coordinator's range map already orders V27 ≪ V69):

- **`V69__budgets.sql`** — **CREATE** `budgets`, `budget_versions`, `budget_lines` (+ all constraints incl. the cost-centre-value FKs → **ADR-0025 `dimension_values(id)`**, the two single-active-version partial uniques over `cost_centre_value_id`, the indexes, D-4). **[Amended 2026-06-11: was `V69__cost_centres.sql` (the dropped dimension framework + GL tag); the budget-triple CREATE moves up from the old V70 into V69. No frozen-table ALTER, no `cost_centres` CREATE.]**
- **`V70__budgeting_permissions.sql`** — INSERT the **6** `BUDGETING.*` permissions (module `budgeting`) `ON CONFLICT (code) DO NOTHING`; grant all to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (the shipped pattern). (Permissions have no `uid` — #12 N/A.) **[Amended 2026-06-11: was 8 perms in the old V71; now 6 — the two `BUDGETING.COSTCENTRE.*` perms are dropped (ADR-0025's `COSTING.*` cover cost centres).]**
- **`V71`–`V73`** — **headroom (no DDL required).** v1 seeds **no** budgets (a company defines its own); **no** cost-centre seed exists here (cost-centre seeding, including the per-company built-in dimensions, is ADR-0025's V27). These slots are reserved so the engineer may split `budget_versions`/`budget_lines` or add an index migration without renumbering. **[Amended 2026-06-11: the old `V72__budgeting_seed.sql` default-cost-centre seed is DROPPED (cost centres are ADR-0025's `dimension_values`); the old `V73__journal_line_cc_backfill_check.sql` is DROPPED (there is no budgeting `journal_lines` change to guard — ADR-0025's `MigrationKeepDataIT` covers its V27 column).]**

**`code_sequence` kind** (`BUDGET`) is **not pre-seeded** — created lazily on first use by `BudgetingNumberGenerator` (the shipped mechanism), so **no seed rows and no #12 seed-uid exposure for numbering.** **[Amended 2026-06-11: the `COST_CENTRE` kind is DROPPED — ADR-0025 owns cost-centre numbering.]** **Therefore the only per-company CROSS-JOIN seed in the range is the uid-less permission grant (V70) — no #12-vulnerable per-company seed-uid exists** (no per-company-uid inserts like `chart_of_accounts`/`gl_configs`; no default-cost-centre seed — cost centres are ADR-0025's `dimension_values`). **No `JournalSourceType` widen, no `GlConfigKey` add, no CoA account add** — budgets post nothing (D-7). **[Amended 2026-06-11: budgeting's migrations make NO `journal_lines` change — the `cost_centre_value_id` column + its index are ADR-0025's V27, covered by ADR-0025's `MigrationKeepDataIT`. Budgeting's V69–V73 create only new tables (`budgets`/`budget_versions`/`budget_lines`) which are trivially keep-data-safe.]**

## Consequences

> **Amended 2026-06-11 (Wave-2 collision #1): the Consequences below are restated to the consume-ADR-0025 design — budgeting no longer owns the cost-centre dimension or touches `journal_lines`.**

**Positive**
- The **cost-centre dimension is consumed, not duplicated** — budgeting references ADR-0025's `dimension_values` (the `COST_CENTRE` slot) and reads ADR-0025's `journal_lines.cost_centre_value_id` aggregate. **Budgeting touches no frozen table** and adds no posting path; the one finance-critical-table change (the tag column) is owned + proven once by ADR-0025.
- **Budgets post nothing** (D-7): no `gl_config` key, no `JournalSourceType`, no CoA account, no outbox event. The module is plain reference data + a read. This is simpler and lower-risk than every prior financial module and is stated so it is not "completed" with a spurious posting.
- The **variance read is a sibling of the shipped P&L** (signed by normal balance — ADR-0018) layered over **ADR-0025's `actualsByAccountValuePeriod`** aggregate (grouped by `account × cost_centre_value_id`) joined to the approved-version budget lines. It reuses the reporting CSV exporter and hits ADR-0025's `ix_journal_lines_cost_centre` — budgeting adds no GL repository method or index.
- **Append-only versioning + single-active-approved-version** is enforced at the DB (partial unique over `cost_centre_value_id`) + the service (serialised supersede under `@Version`) — two-APPROVED is structurally impossible (NFR-BUD-04).
- **No module cycle, no port indirection** — budgeting is a pure leaf consumer (`budgeting → gl` read + `budgeting → costing` read), like `reporting`. There is **no `gl → budgeting` edge** and **no `CostCentreLookup` port** (both dropped) because budgeting writes nothing to `journal_lines`.
- Additive and surgical: **3 new tables** (budget triple), **no `journal_lines` change**, **no `LineDraft` change**, **6 perms**, **1 `code_sequence` kind** (`BUDGET`), **2 `ScopeGuard` cases** (`budget`/`budgetversion`), **4 nav routes**. **V1–V19 frozen; the cost-centre infra is ADR-0025's.**

**Negative / costs**
- **Budgeting is now HARD-GATED by ADR-0025** (collision-resolution consequence). It cannot build until ADR-0025 has shipped `dimensions` + `dimension_values` + `journal_lines.cost_centre_value_id` (V27). This matches the Wave-2 tranche plan (budgeting is Tranche-2, "needs cost-centre"). **[Amended 2026-06-11: this replaces the old top cost — the `journal_lines.cost_centre_id` regression-test obligation — which moves to ADR-0025.]**
- **Cost-centre tagging completeness is ADR-0025's** (OQ-BUD-01 / ADR-0025 D-6) — automatic postings are tagged only where ADR-0025 wires them; a by-centre P&L is incomplete until operational documents are wired (ADR-0025's additive work). The variance report is honest about this (the Unallocated bucket, OQ-BUD-07), but management should know the by-centre actuals are as complete as ADR-0025's poster-wiring makes them.
- The **self-contained approval lifecycle** (D-5) duplicates state-machine plumbing that a future generic approvals engine (X.5 / ADR-0022) would centralise; re-pointing later is a refactor (designed for, OQ-BUD-05, but not free).
- The **single-active-version invariant** has the Postgres-NULL caveat for company-wide budgets (`cost_centre_value_id IS NULL`) requiring a second partial unique + a service guard (D-4b/D-5) — a modelling wrinkle the engineer must implement exactly.
- **A cross-module read dependency on `costing`** (ADR-0025) — budgeting reads the dimension value picker + the actuals aggregate. A leaf read edge (no cycle), but it ties budgeting's build + tests to ADR-0025's D-8 contract being stable.

**Neutral / deferred**
- Profit centres, forecasting, commitment/encumbrance, allocations (posting `source_type=ALLOCATION`), what-if scenarios, management dashboards, non-monthly calendars, multi-currency budgets, hard budget enforcement — **all deferred** (budgeting.md §2.2), none precluded (NFR-BUD-06). The reserved `BUDGET.VERSION.APPROVED` `DomainEventType` constant (D-9) is the additive hook for the deferred rounds. **[Amended 2026-06-11: cost-centre parent roll-up + per-document cost-centre wiring + multi-dimensional (project) tagging are ADR-0025's deferred items, not budgeting's; the `parent_id` roll-up hook is on ADR-0025's `dimension_values`.]**

## Alternatives considered

> **Amended 2026-06-11 (Wave-2 collision #1): the two cost-centre-shape alternatives below are SUPERSEDED.** The "additive `journal_lines.cost_centre_id` vs EAV" and "dimension grain `journal_lines` vs `journal_entries`" decisions are now **ADR-0025's** (it chose the fixed slot-column shape, line grain, exactly as budgeting had — see ADR-0025 D-3 / OQ-CC-01). Budgeting no longer makes a dimension-shape decision; it consumes ADR-0025's. They are retained here only as historical context for why the consumed shape is right.
- **(superseded — now ADR-0025's) The cost-centre dimension — additive slot-column vs EAV.** ADR-0025 chose the fixed nullable slot-column (`journal_lines.cost_centre_value_id`) mirroring `branch_id`, rejecting a `journal_line_dimensions` EAV table for the same hot-path reasons budgeting had. Budgeting consumes that column.
- **(superseded — now ADR-0025's) Dimension grain — line vs entry.** ADR-0025 chose line grain (a balanced entry may split legs across centres). Budgeting consumes it.
- **Budget posting — post a memo/statistical journal vs post nothing.** *Decided: post nothing.* Some ERPs post budgets to a statistical ledger so budget and actual share the journal-line read. That conflates plan with fact in the real ledger (risking the trial balance), needs a parallel statistical-account scheme, and contradicts BR-BUD-01. A plain budget-lines table joined to the actuals aggregate is the boring, correct choice — the budget never pollutes the books.
- **Approval lifecycle — module-local vs the generic approvals engine (X.5 / ADR-0022).** *Decided: module-local.* The generic engine (ADR-0022) is a separate Tranche-1 build; coupling budgeting's lifecycle to it now would gate this increment on a second integration. A self-contained DRAFT→SUBMITTED→APPROVED/REJECTED machine ships now and re-points later (OQ-BUD-05). Rejected: building the generic engine here (scope creep — that is ADR-0022's).
- **Single-active-version — partial unique index vs a `is_active`/`active_version_id` pointer on the budget.** *Decided: a partial unique on `status='APPROVED'` (+ the company-wide NULL caveat handled by a second partial unique + service supersede), keyed on `cost_centre_value_id`.* A pointer column on `budgets` (`active_version_id`) is an alternative, but it duplicates the truth already in `budget_versions.status` and can drift; the partial unique makes "≤1 approved per scope" a DB invariant, the stronger guarantee.
- **(superseded — now N/A) GL↔budgeting coupling for the cost-centre lookup — port/adapter vs direct call.** **[Amended 2026-06-11: budgeting writes no cost-centre tag, so there is no GL↔budgeting coupling to resolve — the `CostCentreLookup` port is dropped. Cost-centre resolution at post time is ADR-0025's `DimensionResolver`, reached by ADR-0025's wired posters.]**
- **Variance actual source — read ADR-0025's `actualsByAccountValuePeriod` vs re-aggregate `journal_lines` directly vs a Reporting snapshot.** *Decided: read ADR-0025's documented aggregate contract.* **[Amended 2026-06-11: was "re-aggregate `journal_lines` via a new `cost_centre`-aware budgeting repository method".]** ADR-0025 owns the cost-centre-sliced actuals aggregate (its D-8) and designed it precisely for budgeting to consume; re-implementing the same `journal_lines` GROUP BY in budgeting would duplicate ADR-0025's query and index. A Reporting materialised snapshot is the right optimisation later (Reporting deferred them, ADR-0018 / PATH §3.2).

## Open items (OQ-BUD — recommended defaults adopted; the ★ items shape the model — owner should confirm before build)

- **OQ-BUD-01 — cost-centre tagging breadth:** **[Amended 2026-06-11: this is now ADR-0025's OQ-CC-02 (its wired-poster set), not budgeting's.]** Budgeting reads whatever ADR-0025 tags; broadening the tagged-poster set is an ADR-0025 change.
- **OQ-BUD-02 — dimension grain:** **[Amended 2026-06-11: settled by ADR-0025 (line grain, `journal_lines.cost_centre_value_id`, its OQ-CC-01).]** Budgeting consumes it; no longer a budgeting decision.
- **★ OQ-BUD-03 — budget period grain:** adopted **per-period (12 monthly) storage grain**; annual amount is a spread convenience. **Owner: confirm** (vs a genuine annual-only budget).
- **OQ-BUD-04 — maker≠checker:** adopted **not hard-enforced in v1** (role policy); a one-line guard addable later.
- **OQ-BUD-05 — approval-lifecycle home:** adopted **module-local** (X.5 not built); re-pointable later.
- **OQ-BUD-06 — variance sign/labels:** adopted **signed variance = actual − budget**; favourable/adverse labelled by account type in presentation. Confirm finance's preferred labels (presentation only).
- **OQ-BUD-07 — Unallocated treatment:** adopted **separate "Unallocated" bucket** (NULL `cost_centre_value_id` rows from ADR-0025's aggregate), never folded/dropped. **[Amended 2026-06-11: keyed on ADR-0025's `cost_centre_value_id`.]**
- **OQ-BUD-08 — parent roll-up:** **[Amended 2026-06-11: cost-centre parent roll-up is ADR-0025's (its `dimension_values.parent_id` + its dimension-sliced TB roll-up, FR-CC-16).]** Budgeting's variance reports the chosen cost-centre value; if roll-up is wanted, it consumes ADR-0025's roll-up read.
- **OQ-BUD-09 (deferred, non-blocking):** profit-centre object, forecasting, commitment/encumbrance, allocations, dashboards, multi-dimensional tagging, hard enforcement — all deferred (§2.2); none precluded (NFR-BUD-06).

---

## Summary

> **Amended 2026-06-11 (Wave-2 collision #1): the Summary below is restated to the consume-ADR-0025 design.**

ADR-0034 designs the **Budgeting & Management Accounting** module in a new `com.erp.modules.budgeting` that **CONSUMES the ADR-0025 cost-centre dimension framework** (it builds no `cost_centres` master and adds no `journal_lines` tag of its own — a budget's cost centre is a `dimension_values(id)` in ADR-0025's `COST_CENTRE` slot): **three budgeting tables** (`budgets`, `budget_versions`, `budget_lines`), an append-only **budget version lifecycle** (DRAFT→SUBMITTED→APPROVED/REJECTED/SUPERSEDED, exactly one APPROVED version active per (company, FY, cost-centre value), enforced by a partial unique over `cost_centre_value_id` + a serialised supersede), and a **budget-vs-actual variance read** that joins approved-version `budget_lines` against **ADR-0025's `actualsByAccountValuePeriod` aggregate** (grouped by account [× `cost_centre_value_id`], signed by normal balance) — a sibling of the shipped P&L read, CSV-exportable. **Budgeting writes NOTHING to `journal_lines` and adds no `LineDraft` field** — cost-centre tagging onto the ledger is wholly ADR-0025's.

**The headline: budgets post NOTHING** (D-7) — no `gl_config` key, no `JournalSourceType`, no new CoA account, no outbox event published. The only ledger interaction is *reading* the cost-centre actuals via ADR-0025's DTO contract (D-8). **Budgeting is HARD-GATED by ADR-0025** — `dimension_values` + `journal_lines.cost_centre_value_id` (ADR-0025 V27) must exist before budgeting builds (it is Tranche-2, "needs cost-centre").

**Additive on frozen V1–V19, range V69–V73 (unchanged) — but budgeting's migrations touch NO frozen table** (the `journal_lines` cost-centre column + the `cost_centres` master are ADR-0025's V27–V29). **#12-safe** (the only per-company CROSS-JOIN seed is the uid-less permission grant; the `BUDGET` numbering kind is lazy; no default-cost-centre seed — cost centres are ADR-0025's). **No cycle** — budgeting is a pure leaf consumer: `budgeting → gl` (accounts/periods) + `budgeting → costing` (cost-centre value picker + actuals aggregate), like `reporting`; **no `gl → budgeting` edge, no `CostCentreLookup` port**. **Cross-module touch list:** (1) **budgeting → gl** — reads `ChartOfAccountRepository`/`FiscalYear/PeriodRepository`; (2) **budgeting → costing (ADR-0025)** — reads `DimensionService.resolveValue`/`listActiveValues(COST_CENTRE)` + `DimensionSlicedReportQuery.actualsByAccountValuePeriod`; (3) **reporting** — the variance/departmental reads reuse the `ReportExporter` CSV path. The model is concrete enough to build V69–V73 + the three entities + the version lifecycle + the variance read without guessing a business rule, **once ADR-0025 has shipped**.
