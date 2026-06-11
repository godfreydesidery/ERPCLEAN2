# 0034 — Budgeting & Management Accounting data model: a cost-centre dimension framework on GL (an additive nullable `journal_lines.cost_centre_id` analysis tag, mirroring the shipped `branch_id` tag), budgets by account × cost-centre × fiscal period, append-only budget versions with a self-contained DRAFT→SUBMITTED→APPROVED/REJECTED/SUPERSEDED approval lifecycle (exactly one APPROVED version active per scope), and a budget-vs-actual variance read that joins approved-version lines against GL `journal_lines` grouped by account [× cost_centre], signed by normal balance — all measure-only (budgets post NO GL), in a new `com.erp.modules.budgeting` module, additive as `V69–V73` on the frozen V1–V19

- **Status:** Proposed
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (consuming the DRAFT Budgeting requirements 2026-06-11 — `docs/requirements/budgeting.md`. The load-bearing scoping calls — cost-centre tagging breadth (OQ-BUD-01), dimension grain (OQ-BUD-02), budget period grain (OQ-BUD-03), the approval-lifecycle home (OQ-BUD-05) — are **design decisions this ADR makes** with owner-style defaults; they are flagged, not blockers. The owner should ratify OQ-BUD-01/02/03 before build since they shape the model.)
- **Context source:** [docs/requirements/budgeting.md](../requirements/budgeting.md) (FR-BUD-01..20, BR-BUD-01..17, NFR-BUD-01..08, §6 flows, §8 OQ log — the ground truth for every rule below) + [docs/PATH-TO-FULL-ERP.md](../PATH-TO-FULL-ERP.md) §3.11 (area 14, T3.6 — "cost-centre / dimension framework is the build-first enabler") + §4 item 4. Verified against the **shipped** code:
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / [V10__general_ledger.sql](../../backend/src/main/resources/db/migration/V10__general_ledger.sql)): `chart_of_accounts` (`id`, `uid` VARCHAR(26), `company_id`, `account_code`, `name`, `account_type` ∈ {ASSET,LIABILITY,EQUITY,INCOME,EXPENSE}, `normal_balance` ∈ {DEBIT,CREDIT}, `is_active`, `status` MasterStatus); `fiscal_years` + `fiscal_periods` (`id`, `uid`, `company_id`, `fiscal_year_id`, `period_no` 1..12, `start_date`/`end_date`, `status` OPEN|CLOSED); `journal_batches`→`journal_entries`→`journal_lines` (the posting; `journal_lines` carries `company_id`, **nullable `branch_id` analysis tag** verified V10 lines 184/199/281, `entry_id`, `account_id`, `debit_amount`/`credit_amount` NUMERIC(19,4), `currency`, `line_memo`, NO `updated_*` — append-only); `JournalLineRepository.trialBalanceSums`/`trialBalanceSumsByPeriod`/`periodMovementByAccount` (the `GROUP BY account` aggregates the variance read mirrors — verified); `GLPostingService.post(JournalEntryDraft)` + `JournalEntryDraft.LineDraft(accountId, debitAmount, creditAmount, currency, lineMemo)` (the manual-journal path the cost-centre tag threads into — D-6); `GlConfigKey` / `JournalSourceType` (NO new value needed — budgets post nothing, D-7).
  - **Reporting** ([ADR-0018](0018-financial-reporting-read-model.md) / V15): `IncomeStatementBuilder` / `journal_lines GROUP BY account` signed-by-`normal_balance` read pattern (the variance report is a **sibling read** over the same lines, additionally grouped by `cost_centre_id`); `ReportExporter` / `CsvStatementRenderer` (the CSV export the variance report reuses, FR-BUD-18); `StatementType`/`StatementSection` enums (the variance/departmental reads add their own DTOs, not these enums).
  - **Numbering** ([ADR-0007](0007-products-data-model.md) D-6): `code_sequence(company_id, entity_kind)` row-locked allocation — **two new lazy `entity_kind` values** `COST_CENTRE`, `BUDGET` (created on first use, the shipped mechanism — no seed rows, **no #12 seed-uid exposure for numbering**).
  - **Security** ([ScopeGuard](../../backend/src/main/java/com/erp/platform/security/ScopeGuard.java)): the `companyIdOf(targetType, uid)` switch (verified — `case "account"`, `"salesorder"`, … land here; this ADR adds `"costcentre"`, `"budget"`, `"budgetversion"`); `assertCanActIn` on every read path; [PermissionChecks](../../backend/src/main/java/com/erp/platform/security/PermissionChecks.java) bean `@Component("perm")` with `has(code)` / `scoped(uid, targetType, code)` — `@perm.has(...)` / `@perm.scoped(...)`, **NEVER `hasAuthority`**.
  - **Money** ([ADR-0005](0005-money-and-currency.md)): base currency only (TZS), NUMERIC(19,4), HALF_UP.
  - **Audit / outbox** ([ADR-0009](0009-transactional-outbox.md)): `AuditService` for every transition. **No outbox event is needed in v1** — budgeting causes **no cross-module effect** (it posts no GL, moves no stock, touches no cash). The `journal_lines.cost_centre_id` tag is set **inline** in the existing manual-journal post TX (D-6); no event. Two `DomainEventType` constants are **reserved/declared but not yet published** for the deferred enforcement/commitment round (D-9) so the coordinator can see the namespace claim.
  - [[db-naming-convention]] verified against V1–V19 (plural masters/children `cost_centres`/`budgets`/`budget_versions`/`budget_lines`; singular constraint roots `uq_`/`fk_`/`chk_` on the singular entity; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id`/`branch_id` BIGINT scalar; additive `ADD COLUMN`/`ADD CONSTRAINT`). **ISSUES-REGISTER #12:** any per-company CROSS-JOIN seed-uid MUST be md5-bounded `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars), **never** raw-key concat. **Latest shipped migration is `V19__sales_returns.sql` → Budgeting uses `V69–V73`** (additive; V1–V19 FROZEN; V20–V68 reserved for other in-flight modules the coordinator sequences). **Next free ADR is 0034.**

This ADR is the **technical data model + integration design** for the Budgeting & Management Accounting module (Phase D, PATH-TO-FULL-ERP §3.11). It translates the ratified-pending spec into: the **cost-centre dimension framework** (a `cost_centres` master + the **load-bearing additive nullable `journal_lines.cost_centre_id` analysis tag**), the four budgeting tables (`budgets`, `budget_versions`, `budget_lines`, and the cost-centre master), the version-lifecycle enum + service-guarded transitions + the single-active-approved-version invariant, the manual-journal cost-centre threading (D-6), the **budget-vs-actual variance read** (the GL `journal_lines` aggregate joined to approved-version lines, signed by normal balance), the API surface, the permissions, the `ScopeGuard` cases, the Angular routes, and the **V69–V73** migration ordering. It is **concrete enough that an engineer builds the model + the dimension tag + the version lifecycle + the variance read without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. **Budgets post no GL** — there is deliberately no `GLPostingService` call, no `gl_config` key, and no `JournalSourceType` value in this module (the one defensive deferred-namespace note aside, D-9).

## Context

GL keeps books at **company level** with **one** working analytical dimension: `journal_lines.branch_id`, a nullable analysis tag (ADR-0013 D-7) that lets a branch P&L be `journal_lines` grouped by branch. Management accounting needs a **second** dimension — the **cost centre** — and the **planning + control** layer that hangs off it: budgets, budget versions/approval, and budget-vs-actual variance. None exists. The forces:

- **THE ENABLER (the load-bearing decision): actuals must be groupable by cost centre, which means a cost-centre dimension on the GL line (OQ-BUD-02).** Variance = budget vs actual, and actual = `journal_lines` movement. To slice actual *by cost centre*, the line must carry the centre. GL is *dimension-ready* — `branch_id` is already a nullable line tag — so the boring, proven move is **an additive nullable `cost_centre_id` on `journal_lines`** exactly mirroring `branch_id` (same nullability, same "analysis-only, never part of the double-entry" stance, same denormalise-onto-the-line-for-scoped-aggregates pattern). This is the single change that touches a frozen, shipped, finance-critical table — it must be **additive and non-breaking for NULL lines** (BR-BUD-17, NFR-BUD-02). Resolved in **D-3**.

- **Cost-centre tagging breadth — how much wiring in v1 (OQ-BUD-01)?** Tagging every operational document (sales invoice, supplier bill, cash transaction, payroll) to a cost centre is a large, cross-module slice. The lean v1 is: tag the **manual journal** path explicitly (finance can attribute accruals/adjustments) + leave automatic posters Unallocated (NULL), and make per-document cost-centre fields an **additive, later** change. Resolved in **D-6** (manual-only + pass-through-ready).

- **Budgets are reference data — they post NOTHING (BR-BUD-01).** A budget is a plan, not a transaction: no GL, no stock, no cash, no outbox event, no `gl_config`, no `JournalSourceType`. This is a *deliberate* departure from every prior financial module (which all post). The force is resisting the reflex to wire a posting; the budget tables are plain master/child reference data the variance read joins. Resolved in **D-4 / D-7**.

- **Budget versioning + approval is append-only with a single-active-version invariant (BR-BUD-06/12/13).** A budget has many versions; lifecycle DRAFT→SUBMITTED→APPROVED/REJECTED/SUPERSEDED; **exactly one APPROVED version per (company, FY, cost centre)** is active; approving a new version supersedes the prior. Approved/rejected/superseded versions are immutable (re-plan = new version). The new questions: the lifecycle enum + transition guards, the single-active-version enforcement (a partial unique index + a serialised supersede), and where the approval lifecycle lives. Resolved in **D-5**.

- **The approval lifecycle has no engine to lean on (OQ-BUD-05).** The generic cross-module approvals engine (PATH §3.12 X.5) is **not built**. So budget approval is a **self-contained module-local state machine** (DRAFT→SUBMITTED→APPROVED/REJECTED), designed so it can re-point at X.5 later. Resolved in **D-5**.

- **The variance read mirrors the shipped P&L read (NFR-BUD-03).** Actual = `journal_lines` aggregated by account over a period range, signed by `normal_balance` — exactly the `IncomeStatementBuilder` / `trialBalanceSumsByPeriod` pattern (ADR-0018, ADR-0013) — now additionally grouped/filtered by `cost_centre_id`. The new question is the index that keeps the by-centre aggregate off a table scan. Resolved in **D-8**.

- **No module→module cycle; budgeting is a leaf consumer (NFR-BUD-06, `ModuleBoundaryTest`).** Budgeting **reads** GL (accounts, fiscal periods, journal-line aggregates) via GL's repositories/DTOs and **writes** the `cost_centre_id` tag through the GL manual-journal path (a `gl.service` call passing the resolved cost-centre id). No GL→budgeting edge. GL never imports a budgeting entity. Resolved in **D-10**.

- **Schema freeze / direction.** IAM=V1 … Sales Returns=V19, all frozen; V20–V68 reserved for other in-flight modules. Budgeting is additive **V69–V73**: the cost-centre master, the budget tables, the additive `journal_lines.cost_centre_id` tag, the permission seed, the `code_sequence` kinds (lazy — no seed). It imports no other module's *entity*; it reaches `gl.service` the way `ap.service` reaches `gl.service` (D-10).

## Decision

### D-1 — Module placement: one new `com.erp.modules.budgeting` module; controllers flat in `com.erp.api`

The cost-centre dimension + budgets + variance live under **`com.erp.modules.budgeting`**. **`budgeting`, not `controlling` or `costcentre`:** the dominant deliverable is budgeting & management accounting (PATH area 14); the cost centre is the *enabler dimension* it owns, not a peer module — and a future profit-centre / allocation / forecast slice is a sibling *within* budgeting, not under a separate umbrella. `budgeting` is the durable flat name, consistent with `gl`/`ar`/`ap`/`tax`/`reporting`.

> **Why the cost-centre dimension lives in `budgeting`, not in `gl` or a standalone module.** The dimension *column* lands on `journal_lines` (a GL table, additive — D-3), but the cost-centre **master, its lifecycle, and the resolver** are owned by `budgeting` (it is *the* consumer; GL stays a pure posting engine that accepts an opaque `Long cost_centre_id` analysis tag exactly as it accepts `branch_id`). GL gains **no** dependency on budgeting (it never resolves or validates the cost centre beyond the FK — the validation that the centre is active + same-company happens in the **caller** before the post, D-6). This keeps GL a leaf and budgeting the consumer, no cycle (D-10).

Internal layout:

```
com.erp.modules.budgeting
├── domain.entity   CostCentre, Budget, BudgetVersion, BudgetLine
├── domain.dto      CostCentreDto / CreateCostCentreRequest / UpdateCostCentreRequest,
│                   BudgetDto / CreateBudgetRequest,
│                   BudgetVersionDto / CreateBudgetVersionRequest / SubmitBudgetVersionRequest /
│                       ApproveBudgetVersionRequest / RejectBudgetVersionRequest,
│                   BudgetLineDto / UpsertBudgetLineRequest / SpreadAnnualRequest /
│                       SeedFromVersionRequest,
│                   VarianceReportDto / VarianceRowDto / VarianceQuery,
│                   DepartmentalActualsDto / DepartmentalActualsRowDto
├── domain.enums    CostCentreType (DEPARTMENT|DIVISION|PROJECT|LOCATION|OTHER),
│                   BudgetVersionStatus (DRAFT|SUBMITTED|APPROVED|REJECTED|SUPERSEDED) (D-5)
├── repository      CostCentreRepository, BudgetRepository, BudgetVersionRepository,
│                   BudgetLineRepository
└── service         CostCentreService(+Impl),
                    BudgetService(+Impl)            — budget + version CRUD + the lifecycle (D-5),
                    BudgetVersionLifecycle          — the service-guarded transition machine (D-5),
                    BudgetSpreadCalculator          — annual→12-period even spread, HALF_UP (D-4),
                    BudgetingNumberGenerator        — CC-#### / BUDGET-#### via code_sequence (D-11),
                    VarianceReportQuery             — budget vs GL-actual aggregate (D-8),
                    DepartmentalActualsQuery        — GL actuals by cost-centre × account (D-8)
```

Controllers stay flat in `com.erp.api`: **`CostCentreController`**, **`BudgetController`**, **`BudgetVersionController`**, **`BudgetReportController`** — touching only services (`ModuleBoundaryTest`). The cost-centre dimension column is set on the GL line through `gl.service` (D-6), not through a budgeting repository writing a GL table.

### D-2 — The four tables (cost-centre master + budget triple) + the one additive GL column

All masters/children plural per the shipped convention. Every table carries `company_id` (NFR-BUD-01) and participates in the §3.2 tenant predicate. Money columns `NUMERIC(19,4)`; `uid VARCHAR(26)` ULID; standard audit cols; `@Version` on the headers that take lifecycle transitions.

Table groups:
- **(a)** `cost_centres` — the dimension master (D-3a).
- **(b)** `budgets` — the budget header, scoped to (company, fiscal year, [cost centre]) (D-4a).
- **(c)** `budget_versions` — one revision with the lifecycle (D-4b / D-5).
- **(d)** `budget_lines` — (version, account, fiscal period, amount) (D-4c).
- **(e)** the **additive** `journal_lines.cost_centre_id` analysis tag (the enabler — D-3b).

### D-3 — The cost-centre dimension framework (the enabler)

#### (a) `cost_centres` (master, per company)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT GENERATED BY DEFAULT AS IDENTITY PK | NO | FK target (journal lines + budget headers reference this) |
| `uid` | VARCHAR(26) | NO | ULID; `uq_cost_centre_uid`; URLs address by uid; `ScopeGuard case "costcentre"` (D-10) |
| `company_id` | BIGINT | NO | FK → `companies(id)`; tenant scope; never updated |
| `cost_centre_code` | VARCHAR(30) | NO | human key, e.g. `SALES`; unique per company |
| `cc_number` | VARCHAR(30) | NO | `CC-####` from `code_sequence (company_id,'COST_CENTRE')` (D-11); unique per company; the stable display number |
| `name` | VARCHAR(160) | NO | e.g. `Sales Department` |
| `cc_type` | VARCHAR(20) | NO | `CostCentreType`; DEFAULT `'DEPARTMENT'`; `chk_cost_centre_type` |
| `parent_id` | BIGINT | YES | FK → `cost_centres(id)` (self); shallow grouping tree; nullable; **roll-up read deferred** (OQ-BUD-08) — the column exists so parent roll-up is additive |
| `is_active` | BOOLEAN | NO | DEFAULT `true`; inactive excluded from NEW tagging/budget lines, stays on historical (BR-BUD-05) |
| `status` | VARCHAR(32) | NO | `MasterStatus`; DEFAULT `'ACTIVE'` (the shipped master-status column, matches `chart_of_accounts.status`) |
| `version` | BIGINT | NO | optimistic lock, DEFAULT 0 |
| `created_at`/`created_by`/`updated_at`/`updated_by` | TIMESTAMPTZ/BIGINT | mixed | standard audit cols (`*_by` → `app_users.id`, no FK — the shipped system-write pattern) |

Constraints:
- `uq_cost_centre_uid UNIQUE (uid)`.
- `uq_cost_centre_company_code UNIQUE (company_id, cost_centre_code)` (BR-BUD-02).
- `uq_cost_centre_company_number UNIQUE (company_id, cc_number)` (numbering backstop).
- `fk_cost_centre_company FOREIGN KEY (company_id) REFERENCES companies (id)`.
- `fk_cost_centre_parent FOREIGN KEY (parent_id) REFERENCES cost_centres (id)` (self; nullable).
- `chk_cost_centre_type CHECK (cc_type IN ('DEPARTMENT','DIVISION','PROJECT','LOCATION','OTHER'))`.

Indexes:
```
CREATE INDEX ix_cost_centres_company        ON cost_centres (company_id);
CREATE INDEX ix_cost_centres_active         ON cost_centres (company_id) WHERE is_active = true;  -- the picker working set
CREATE INDEX ix_cost_centres_parent         ON cost_centres (parent_id) WHERE parent_id IS NOT NULL;  -- future roll-up
```

#### (b) THE LOAD-BEARING ADDITIVE GL CHANGE — `journal_lines.cost_centre_id` (mirrors `branch_id`)

**ALTER `journal_lines` (additive, V69):**

| column | type | null | default | notes |
|---|---|---|---|---|
| `cost_centre_id` | BIGINT | **YES** | NULL | FK → `cost_centres(id)`; the **analysis tag** (OQ-BUD-02 default: line grain, mirroring `branch_id`). NULL = **Unallocated**. **Analysis-only — never part of the double-entry** (BR-BUD-07). Existing + automatic-poster lines remain NULL (no backfill, BR-BUD-08). |

- `fk_journal_line_cost_centre FOREIGN KEY (cost_centre_id) REFERENCES cost_centres (id)` — a real DB FK (intra-company integrity; the service asserts same-company + active at post, D-6).
- **NEW index for the by-centre variance aggregate (NFR-BUD-03):**
  ```
  CREATE INDEX ix_journal_lines_company_cc_account
      ON journal_lines (company_id, cost_centre_id, account_id);
  ```
  This covers the variance read's `WHERE company_id = ? AND cost_centre_id = ? GROUP BY account_id` aggregate. The shipped `ix_journal_lines_company_account` still serves the company-wide (no-centre) variance and the unchanged trial balance. For the period filter the read joins `journal_entries` on `ix_journal_entries_company_period` (shipped).

> **Non-breaking proof obligation (BR-BUD-17 / NFR-BUD-02).** The column is nullable with no default-other-than-NULL and no CHECK; it sits beside the existing nullable `branch_id`. No existing posting writes it (the manual-journal path sets it only when the poster supplies one — D-6; every other poster leaves it NULL). No existing read references it (TB, P&L, BS, cash-flow, AR/AP reconciliation all `GROUP BY account_id` and never select `cost_centre_id`). The migration test `MigrationKeepDataIT` extends to V69 to assert existing journal lines back-fill to `cost_centre_id = NULL` and every shipped statement returns byte-identical output. **The variance + departmental reads are the ONLY new consumers of the column.**

> **Why on `journal_lines`, not `journal_entries` (OQ-BUD-02).** `branch_id` is denormalised onto batch/entry/line in V10; the **line** is the analysis grain because a single balanced entry may legitimately split its legs across cost centres (e.g. a shared-cost accrual debiting two departments). Tagging only the entry would force one centre per entry. Line grain is the correct, `branch_id`-consistent choice. (The entry/batch keep their `branch_id`; we do **not** add `cost_centre_id` to entry/batch in v1 — the line is sufficient for the by-centre aggregate, and adding it to entry would invite the "one centre per entry" misuse. Additive later if a use case appears.)

### D-4 — The budget triple (header → version → line); budgets post NOTHING

#### (a) `budgets` (header, per company)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | BIGINT IDENTITY / VARCHAR(26) | NO | `uq_budget_uid`; `ScopeGuard case "budget"` |
| `company_id` | BIGINT | NO | FK → `companies(id)`; tenant scope |
| `budget_number` | VARCHAR(30) | NO | `BUDGET-####` at create (D-11); `uq_budget_company_number UNIQUE (company_id, budget_number)` |
| `name` | VARCHAR(160) | NO | e.g. `Sales Dept FY2026 Operating Budget` |
| `fiscal_year_id` | BIGINT | NO | FK → `fiscal_years(id)`; the FY this budget plans (BR-BUD-03) |
| `cost_centre_id` | BIGINT | YES | FK → `cost_centres(id)`; the centre this budget is for; **NULL = company-wide budget** (BR-BUD-03) |
| `notes` | VARCHAR(500) | YES | |
| `version` (`@Version`) + audit cols | | | standard |

Constraints:
- `uq_budget_uid UNIQUE (uid)`; `uq_budget_company_number UNIQUE (company_id, budget_number)`.
- **`uq_budget_company_year_cc UNIQUE (company_id, fiscal_year_id, cost_centre_id)`** — at most **one budget per (company, FY, cost centre)** (a company-wide budget has `cost_centre_id IS NULL`; Postgres treats NULLs as distinct, so multiple company-wide budgets for the *same* FY would slip past — guard the single company-wide budget per FY in the **service** + a **partial unique** `uq_budget_company_year_companywide UNIQUE (company_id, fiscal_year_id) WHERE cost_centre_id IS NULL`). This makes "the budget for this scope" unambiguous, which the single-active-version invariant (D-5) builds on.
- `fk_budget_company`, `fk_budget_fiscal_year` (→ `fiscal_years`), `fk_budget_cost_centre` (→ `cost_centres`).

Index: `CREATE INDEX ix_budgets_company_year ON budgets (company_id, fiscal_year_id);`

#### (b) `budget_versions` (child of `budgets` — carries the lifecycle, D-5)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_budget_version_uid`; `ScopeGuard case "budgetversion"` |
| `budget_id` | BIGINT | NO | FK → `budgets(id)` |
| `company_id` | BIGINT | NO | denormalised (tenant predicate without a join) |
| `fiscal_year_id` | BIGINT | NO | denormalised from the budget (the single-active-version partial unique reads it — see below) |
| `cost_centre_id` | BIGINT | YES | denormalised from the budget (same reason; NULL for company-wide) |
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
- `fk_budget_version_budget` (→ `budgets`), `fk_budget_version_company`, `fk_budget_version_fy` (→ `fiscal_years`), `fk_budget_version_cc` (→ `cost_centres`), `fk_budget_version_seeded_from` (→ `budget_versions`, self), `fk_budget_version_submitted_by`/`_approved_by`/`_rejected_by` (→ `app_users`).
- **THE SINGLE-ACTIVE-VERSION INVARIANT (BR-BUD-12) at the DB — a partial unique:**
  ```
  CREATE UNIQUE INDEX uq_budget_version_one_approved
      ON budget_versions (company_id, fiscal_year_id, cost_centre_id)
      WHERE status = 'APPROVED';
  ```
  At most one APPROVED version per (company, FY, cost centre). **Caveat:** Postgres treats NULL `cost_centre_id` (company-wide) as distinct, so this partial unique does **not** guard the company-wide case — that one is guarded by the service supersede-in-one-TX + a second partial unique `uq_budget_version_one_approved_companywide ... WHERE status='APPROVED' AND cost_centre_id IS NULL` over `(company_id, fiscal_year_id)`. Both backstops + the serialised supersede (D-5) make two-APPROVED structurally impossible.

Index: `CREATE INDEX ix_budget_versions_budget ON budget_versions (budget_id);` + `CREATE INDEX ix_budget_versions_active ON budget_versions (company_id, fiscal_year_id, cost_centre_id) WHERE status = 'APPROVED';` (the variance read's "find the active version" lookup).

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

### D-6 — Manual-journal cost-centre tagging (the only write path to `journal_lines.cost_centre_id` in v1)

The dimension column is populated through the **existing GL manual-journal post path**, additively. `JournalEntryDraft.LineDraft` (verified `record LineDraft(accountId, debitAmount, creditAmount, currency, lineMemo)`) gains **one additive nullable field `Long costCentreId`** (DEFAULT null — backward-safe for every existing caller). The `GLPostingService.post(...)` writes `journal_lines.cost_centre_id` from `LineDraft.costCentreId` (null when unset). **This is a GL change owned by GL** (the field is on GL's draft DTO, the write is in GL's posting service) — budgeting does not write the GL table; it **supplies a validated cost-centre id** to the manual-journal request.

- **The validation seam (who checks the centre is active + same-company):** the **manual-journal controller/service path** (GL.POST) resolves the per-line `costCentreUid` (from the request) to a `cost_centres` row, asserting `is_active` + same company, **before** building the draft — using a thin `gl → budgeting` read (a `CostCentreService.resolveActiveIdForCompany(uid, companyId)` returning a `Long` id or throwing). This is the **one `gl → budgeting` edge** (D-10) and it is a *read* of a budgeting DTO/id, not an entity import — the `ap.service → gl.service` precedent in reverse. **Alternatively** (the leaner option, recommended): the manual-journal **request DTO** carries `costCentreUid` per line, the **GL service** resolves it via an injected `CostCentreLookup` interface that `budgeting` implements (dependency-inversion — GL declares the port, budgeting provides the adapter), so GL depends on its own interface, not on budgeting. **Decision: the port/adapter (`CostCentreLookup` in `gl`, implemented by `budgeting`)** — it keeps GL a leaf (no compile edge into budgeting) and is the boring DIP move. (D-10 details the ArchUnit stance.)
- **Automatic posters (sales/AR/AP/cash/COGS) are unchanged** — they build their drafts with `costCentreId = null` (the additive field defaults null), so every automatic posting is **Unallocated** in v1 (BR-BUD-08). Wiring a cost centre onto an operational document is additive later (OQ-BUD-01).
- The Angular post-journal screen (`gl/journals/post`, shipped) gains an **optional per-line cost-centre picker** (lists active cost centres via `BUDGETING.COSTCENTRE.VIEW`). No new route — an additive field on the existing screen.

> **Migration impact of D-6:** `journal_lines.cost_centre_id` is added in V69 (D-3b). The `LineDraft.costCentreId` field, the `CostCentreLookup` port, and the post-journal UI field are **code**, not migration. No V10 edit (the column is `ADD COLUMN`, additive).

### D-7 — GL postings: NONE. No new `gl_config` key, no new `JournalSourceType`, no new CoA account.

**Explicitly: this module introduces zero GL posting.** Budgets are reference data (BR-BUD-01). Therefore:
- **No new `GlConfigKey`** — there is no posting-role to map.
- **No new `JournalSourceType`** — there is no journal source (budgeting writes no journal). The `chk_journal_batch_source_type` / `chk_journal_entry_source_type` CHECKs are **untouched**.
- **No new CoA account code** — budgeting plans against the **existing** chart of accounts (5300 Salaries, 5400 Utilities, 4100 Sales Revenue, … all shipped V10); it adds none.
- The **only** ledger interactions are **(1)** *reading* `journal_lines` for actuals (D-8) and **(2)** *setting* the additive `cost_centre_id` analysis tag on a manual-journal post (D-6) — neither is a budget posting.

This is the headline departure from the prior nine financial modules and is stated up front so it survives review: **do not add a `gl_config` key or `JournalSourceType` for budgeting.**

### D-8 — Budget-vs-actual variance read + departmental actuals (mirrors the shipped P&L read)

**`VarianceReportQuery.run(VarianceQuery)`** — `@Transactional(readOnly = true)`, `assertCanActIn` first (NFR-BUD-01). Inputs: `companyId`, `fiscalYearId`, period range (`fromPeriodNo`..`toPeriodNo`, default 1..12), optional `costCentreId`, optional account-type/range filter.

1. **Resolve the active budget version** for (company, fiscalYear, costCentre) — the single APPROVED version (`ix_budget_versions_active`). If none → all budget = 0 + `noApprovedBudget = true` flag (FR-BUD-16 / BR-BUD-12).
2. **Budget side:** `Σ budget_lines.amount` for that version's lines whose `fiscal_period_id` is in the chosen period range, GROUP BY `account_id` (`ix_budget_lines_version_account`).
3. **Actual side:** the GL aggregate — `Σ (debit_amount − credit_amount)` (or the normal-balance-signed equivalent the shipped `IncomeStatementBuilder` uses) over `journal_lines` joined to `journal_entries` (for the period filter), `WHERE company_id = ? AND je.fiscal_period_id IN (range)` **and**, when a centre is chosen, `AND jl.cost_centre_id = ?`; GROUP BY `account_id`. Signed by the account's `normal_balance` so an INCOME/EXPENSE actual is comparable to the budget magnitude (BR-BUD-14). Uses **`ix_journal_lines_company_cc_account`** (centre-filtered) or `ix_journal_lines_company_account` (company-wide). **This is a new query method on `JournalLineRepository`** (a sibling of `periodMovementByAccount`, adding the `cost_centre_id` filter + grouping) — owned by GL, called by budgeting via the GL repository/DTO read (D-10).
4. **Combine:** full-outer-join budget and actual on `account_id` (an account may have a budget but no actual, or vice versa); per row compute `variance = actual − budget`, `variancePct = (budget != 0) ? variance/budget : null` (BR-BUD-15). The favourable/adverse **label** is derived from `account_type` in the DTO/UI, not stored.
5. **Unallocated bucket (OQ-BUD-07):** when **no** centre is chosen (company-wide variance) the actual aggregate includes all lines; when a centre **is** chosen, only that centre's lines — and a separate read can surface the `cost_centre_id IS NULL` "Unallocated" actuals so they are never silently misattributed (BR-BUD-14).

Output `VarianceReportDto { header(companyUid, fiscalYearCode, fromPeriod, toPeriod, costCentreUid?, noApprovedBudget), rows: [VarianceRowDto(accountCode, accountName, accountType, budgetAmount, actualAmount, varianceAmount, variancePct)], totalsByType }`.

**`DepartmentalActualsQuery.run(...)`** (FR-BUD-17): the GL actual aggregate GROUP BY `cost_centre_id, account_id` over a period range, **no budget join** — the by-centre P&L slice (each row = centre × account × actual; NULL centre = Unallocated). Uses `ix_journal_lines_company_cc_account`.

**Export (FR-BUD-18):** both reads flatten to the shipped `ReportExporter` / `CsvStatementRenderer` (ADR-0018) for CSV; PDF/Excel only if the existing renderers accept the flattened model without new work (else CSV-only in v1).

### D-9 — Events: NONE published in v1; two `DomainEventType` constants reserved (namespace claim only)

Budgeting publishes **no outbox event in v1** — no cross-module effect exists (D-5/D-7). For the coordinator's collision-detection and the deferred enforcement/commitment round (PATH §3.11), **two `DomainEventType` constants are declared (reserved) but NOT published**:
- `BUDGET.VERSION.APPROVED` (`"BUDGET.VERSION.APPROVED"`) — reserved for a future round where downstream consumers (e.g. commitment/encumbrance, notifications) react to an approved budget.
- `COST_CENTRE.DEACTIVATED` (`"COST_CENTRE.DEACTIVATED"`) — reserved for a future round where consumers must react to a centre going inactive.

Aggregate-type constants reserved: `AGG_BUDGET_VERSION = "BUDGET_VERSION"`, `AGG_COST_CENTRE = "COST_CENTRE"`. **These are declared in `DomainEventType` to claim the namespace; no handler consumes them and no producer publishes them in this increment.** (If the build team prefers, they may omit the declarations until the consuming round lands — but declaring now prevents a later collision. The coordinator decides.)

### D-10 — ArchUnit edges (no cycle)

- **`budgeting.service` → `gl.repository` / `gl.domain.dto`** — budgeting **reads** GL: `ChartOfAccountRepository` (validate the budget-line account active + same company), `FiscalYearRepository`/`FiscalPeriodRepository` (validate FY/period), and `JournalLineRepository` (the actuals aggregate — the new `cost_centre`-aware method, D-8). **Allowed** — budgeting is a leaf **consumer** of GL, the same stance `reporting` already takes (reporting reads `JournalLineRepository`). Budgeting imports **no GL entity** beyond what the repository returns; it reads ids/DTOs/projections.
- **`gl` → `budgeting`: NONE as a compile edge.** The manual-journal cost-centre resolution (D-6) uses a **port in `gl`** (`CostCentreLookup` interface declared in `com.erp.modules.gl`) **implemented by an adapter in `budgeting`** (Spring injects the bean). GL depends on its own interface; budgeting depends on GL's interface to implement it. **No `gl → budgeting` package import** — DIP. (If the team instead chooses a direct `gl.service → budgeting.service` call, that creates a `gl → budgeting` edge; since `budgeting → gl` already exists, that would be a **cycle** — **rejected**. The port/adapter is mandatory to keep GL a leaf. This is the load-bearing ArchUnit decision.)
- **`budgeting` → `iam` (companies/fiscal scope), `platform.security` (ScopeGuard/perm), `platform.audit`** — the standard spine edges, allowed.
- **Controllers (`com.erp.api.*`) → `budgeting.service` only** — never a repository (`ModuleBoundaryTest` controller↛repository).
- **No cycle:** `budgeting → gl` (read) + `gl → CostCentreLookup` (own interface, implemented by budgeting via Spring DI, not a package edge). `reporting`/`gl` do not depend on `budgeting`'s packages. The `ModuleBoundaryTest` rule set is unchanged; the new module's edges fit the existing allowances (consumer-reads-gl, like reporting).

### D-11 — Numbering: two new `code_sequence` kinds (COST_CENTRE / BUDGET)

`BudgetingNumberGenerator` reuses the shipped `code_sequence` row-locked allocation (ADR-0007 D-6) with two new `entity_kind` values: `COST_CENTRE` (`CC-%04d`) and `BUDGET` (`BUDGET-%04d`), per company, concurrency-safe (NFR-BUD-07). Allocation timing: `CC-####` at cost-centre **create**, `BUDGET-####` at budget **create**. **No new numbering table** — only new `entity_kind` rows, created lazily with `next_value = 1` on first use (the shipped mechanism). The `uq_*_company_number` constraints backstop generator bugs. **No seed rows → no #12 seed-uid exposure for numbering.**

### D-12 — Permissions (`BUDGETING.RESOURCE.ACTION`) + `@perm` gating

Eight permissions, module `budgeting`, gated with `@perm.has` / `@perm.scoped` (NEVER `hasAuthority`):

| permission | gates |
|---|---|
| `BUDGETING.COSTCENTRE.VIEW` | list/view cost centres; the cost-centre picker on the post-journal screen |
| `BUDGETING.COSTCENTRE.MANAGE` | create/edit/deactivate cost centres |
| `BUDGETING.BUDGET.VIEW` | list/view budgets + versions + lines |
| `BUDGETING.BUDGET.MANAGE` | create budget; create/edit version + lines; create new version (seed/re-plan) |
| `BUDGETING.BUDGET.SUBMIT` | submit a version for approval; recall a submitted version |
| `BUDGETING.BUDGET.APPROVE` | approve / reject a submitted version |
| `BUDGETING.REPORT.VIEW` | budget-vs-actual variance report; departmental actuals view |
| `BUDGETING.REPORT.EXPORT` | export the variance / departmental reports (CSV) |

- Path-uid ops use `@perm.scoped(#uid, '<targetType>', '<CODE>')` (e.g. `@perm.scoped(#uid,'budgetversion','BUDGETING.BUDGET.APPROVE')`); list/create ops use `@perm.has('<CODE>')` + `assertCanActIn` on the body company. The manual-journal cost-centre **field** rides the existing `GL.POST` (no new perm to *tag* a line — the poster already holds GL.POST; viewing the picker needs `BUDGETING.COSTCENTRE.VIEW`).
- Seeded in V69 with `ON CONFLICT (code) DO NOTHING`; granted to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (the V7/V12/V14/V17 pattern). Permissions have no `uid` → **#12 N/A**.

### D-13 — `ScopeGuard` cases + the new repositories injected

Three new target types added to `ScopeGuard.companyIdOf(...)` (the verified switch) + their repositories injected into `ScopeGuard` (the documented pattern, lines 108-113 added `quotation`/`salesorder`/`delivery`/`salesreturn`):
- `case "costcentre" -> costCentres.findCompanyIdByUid(uid);`
- `case "budget" -> budgets.findCompanyIdByUid(uid);`
- `case "budgetversion" -> budgetVersions.findCompanyIdByUid(uid);`

(`budget_lines` are addressed only via their version — no standalone `case`; line ops scope through the version's company.) Each repository exposes `findCompanyIdByUid(String uid)` returning `Optional<Long>` (the shipped projection method on every scoped repository).

### D-14 — Angular nav routes (additive to `web/src/app/features/admin/admin.routes.ts`)

A new "Budgeting" nav group, each route `requirePermission`-guarded (the shipped pattern):

| route path | permission | screen |
|---|---|---|
| `budgeting/cost-centres` | `BUDGETING.COSTCENTRE.VIEW` | cost-centre list |
| `budgeting/cost-centres/uid/:uid` | `BUDGETING.COSTCENTRE.VIEW` | cost-centre detail/edit |
| `budgeting/budgets` | `BUDGETING.BUDGET.VIEW` | budget list (with active-version badge) |
| `budgeting/budgets/uid/:uid` | `BUDGETING.BUDGET.VIEW` | budget detail — versions + lines + submit/approve actions (action buttons gated client-side by `BUDGETING.BUDGET.SUBMIT`/`APPROVE`; server is the authority) |
| `budgeting/variance` | `BUDGETING.REPORT.VIEW` | budget-vs-actual variance report (FY / period-range / cost-centre / account filters; CSV export) |
| `budgeting/departmental-actuals` | `BUDGETING.REPORT.VIEW` | departmental actuals (cost-centre × account) |

(The manual-journal cost-centre picker is an additive field on the **existing** `gl/journals/post` screen — **no new route**, D-6.)

### API surface (controllers + endpoints — flat in `com.erp.api`)

- **`CostCentreController`** — `GET /api/cost-centres` (list, paged, `@perm.has('BUDGETING.COSTCENTRE.VIEW')`); `GET /api/cost-centres/{uid}` (`@perm.scoped(#uid,'costcentre','BUDGETING.COSTCENTRE.VIEW')`); `POST /api/cost-centres` (create, `@perm.has('BUDGETING.COSTCENTRE.MANAGE')`); `PUT /api/cost-centres/{uid}` (edit, scoped MANAGE); `POST /api/cost-centres/{uid}/deactivate` (scoped MANAGE).
- **`BudgetController`** — `GET /api/budgets` (list, paged, `BUDGETING.BUDGET.VIEW`); `GET /api/budgets/{uid}` (scoped VIEW — includes versions); `POST /api/budgets` (create, `BUDGETING.BUDGET.MANAGE`); `POST /api/budgets/{uid}/versions` (create new version, optional `seedFromVersionUid`, scoped MANAGE).
- **`BudgetVersionController`** — `GET /api/budget-versions/{uid}` (scoped `budgetversion` VIEW — includes lines); `PUT /api/budget-versions/{uid}/lines` (upsert lines — per-period / annual-spread / seed; scoped MANAGE; rejected if version not DRAFT); `POST /api/budget-versions/{uid}/submit` (scoped SUBMIT); `POST /api/budget-versions/{uid}/recall` (scoped SUBMIT); `POST /api/budget-versions/{uid}/approve` (scoped APPROVE); `POST /api/budget-versions/{uid}/reject` (reason; scoped APPROVE).
- **`BudgetReportController`** — `GET /api/budgeting/variance?fiscalYearUid=&fromPeriod=&toPeriod=&costCentreUid=&accountType=` (`BUDGETING.REPORT.VIEW`); `GET /api/budgeting/departmental-actuals?...` (`BUDGETING.REPORT.VIEW`); `GET /api/budgeting/variance/export?format=CSV&...` (`BUDGETING.REPORT.EXPORT`).
- All responses in the shipped `ApiResponse<T>` envelope; DTOs `*Dto`-suffixed; `assertCanActIn` in every service method.

## Migration ordering (additive; V1–V19 FROZEN; #12-safe seeds; V69–V73)

The increment is **five additive migrations V69–V73** (one logical concern each; they could collapse to fewer files, but the V69–V73 range is claimed and the split keeps each reviewable and lets the build stage cleanly):

- **`V69__cost_centres.sql`** — the dimension framework + the load-bearing GL tag:
  1. **CREATE** `cost_centres` (+ constraints/indexes, D-3a).
  2. **ALTER `journal_lines`** — `ADD COLUMN cost_centre_id BIGINT NULL` + `ADD CONSTRAINT fk_journal_line_cost_centre FOREIGN KEY (cost_centre_id) REFERENCES cost_centres (id)` (additive; existing rows back-fill to NULL = Unallocated — correct, BR-BUD-08).
  3. **CREATE INDEX** `ix_journal_lines_company_cc_account ON journal_lines (company_id, cost_centre_id, account_id)` (the variance aggregate, NFR-BUD-03).
- **`V70__budgets.sql`** — **CREATE** `budgets`, `budget_versions`, `budget_lines` (+ all constraints, the two single-active-version partial uniques, the indexes, D-4).
- **`V71__budgeting_permissions.sql`** — INSERT the 8 `BUDGETING.*` permissions (module `budgeting`) `ON CONFLICT (code) DO NOTHING`; grant all to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (the shipped pattern). (Permissions have no `uid` — #12 N/A.)
- **`V72__budgeting_seed.sql`** — **optional / minimal.** v1 seeds **no** cost centres and **no** budgets (a company defines its own). If the owner wants a default "General / Head Office" cost centre per company, it seeds **#12-safe**: `uid = 'CC' || lpad(c.id::text,6,'0') || substr(md5('GENERAL'),1,12)`, `cc_number` allocated lazily is not possible in SQL seed → use a deterministic `'CC-0001'` with the service reconciling the sequence on first allocation (or simpler: **skip the seed**, recommended — cost centres are pure user data). **Recommendation: V72 is empty/omitted; cost centres + budgets are user-created.** (Kept in the range as a placeholder so the range is contiguous; the engineer may drop it and use V69–V71 + V73 if no seed is needed.)
- **`V73__journal_line_cc_backfill_check.sql`** — **no DDL; a guard/comment migration** (or fold into V69). Documents that existing `journal_lines` are `cost_centre_id = NULL` and that `MigrationKeepDataIT` asserts no shipped statement output changes. *(If the team prefers, V73 is unused and the range is V69–V71; claiming V69–V73 reserves headroom for the engineer to split `budget_versions`/`budget_lines` or add an index migration without renumbering.)*

**`code_sequence` kinds** (`COST_CENTRE`, `BUDGET`) are **not pre-seeded** — created lazily on first use by `BudgetingNumberGenerator` (the shipped mechanism), so **no seed rows and no #12 seed-uid exposure for numbering.** **Therefore the only per-company CROSS-JOIN seed in the range is the uid-less permission grant (V71) — no #12-vulnerable per-company seed-uid exists** (no per-company-uid inserts like `chart_of_accounts`/`gl_configs`, unless the owner opts into the optional V72 default cost centre, which is then md5-bounded). **No `JournalSourceType` widen, no `GlConfigKey` add, no CoA account add** — budgets post nothing (D-7). `MigrationKeepDataIT` extends to V69 (the additive `journal_lines.cost_centre_id` + the new index are verified keep-data-safe and statement-output-neutral).

## Consequences

**Positive**
- The **cost-centre dimension** lands the boring, proven way — an additive nullable `journal_lines.cost_centre_id` exactly mirroring the shipped `branch_id` analysis tag (ADR-0013 D-7). No new posting path, no double-entry change, NULL = Unallocated, no backfill. The one frozen-table change is additive and statement-output-neutral for NULL lines (BR-BUD-17).
- **Budgets post nothing** (D-7): no `gl_config` key, no `JournalSourceType`, no CoA account, no outbox event. The module is plain reference data + a read. This is simpler and lower-risk than every prior financial module and is stated so it is not "completed" with a spurious posting.
- The **variance read is a sibling of the shipped P&L** (`journal_lines GROUP BY account`, signed by normal balance — ADR-0018) with a `cost_centre_id` filter/group and a budget join. It reuses the reporting CSV exporter. New index `ix_journal_lines_company_cc_account` keeps the by-centre aggregate off a table scan.
- **Append-only versioning + single-active-approved-version** is enforced at the DB (partial unique) + the service (serialised supersede under `@Version`) — two-APPROVED is structurally impossible (NFR-BUD-04).
- **GL stays a leaf** — the manual-journal cost-centre threading uses a port (`CostCentreLookup`) in GL implemented by budgeting (DIP), so there is **no `gl → budgeting` compile edge** and **no cycle** (D-10); budgeting reads GL like `reporting` does.
- Additive and surgical: 4 new tables, 1 additive `journal_lines` column + 1 index, 1 additive `LineDraft.costCentreId` field + a port, 8 perms, 2 `code_sequence` kinds, 3 `ScopeGuard` cases, 6 nav routes. **V1–V19 frozen.**

**Negative / costs**
- The increment touches a **frozen, finance-critical, shipped table** (`journal_lines`) with the `cost_centre_id` add. Additive and NULL-safe, but it carries a regression-test obligation: `MigrationKeepDataIT` + a statement-output-equality test must prove every shipped report is byte-identical for NULL lines. Flagged as the top verification gate.
- **Cost-centre tagging is manual-journal-only in v1** (OQ-BUD-01) — automatic postings (sales/AR/AP/cash/COGS) are all Unallocated, so a by-centre P&L is incomplete until operational documents are wired (additive later). The variance report is honest about this (the Unallocated bucket, OQ-BUD-07), but management should know the by-centre actuals are partial until then.
- The **self-contained approval lifecycle** (D-5) duplicates state-machine plumbing that a future generic approvals engine (X.5) would centralise; re-pointing later is a refactor (designed for, OQ-BUD-05, but not free).
- The **single-active-version invariant** has the Postgres-NULL caveat for company-wide budgets (`cost_centre_id IS NULL`) requiring a second partial unique + a service guard (D-4b/D-5) — a modelling wrinkle the engineer must implement exactly.
- The **port/adapter (`CostCentreLookup`)** is one extra indirection on the manual-journal path; it is the price of keeping GL a leaf (cheaper than the cycle the direct call would create).

**Neutral / deferred**
- Profit centres, forecasting, commitment/encumbrance, allocations (posting `source_type=ALLOCATION`), what-if scenarios, management dashboards, multi-dimensional tagging (project), non-monthly calendars, multi-currency budgets, hard budget enforcement, parent-centre roll-up, per-document cost-centre wiring — **all deferred** (budgeting.md §2.2), none precluded (NFR-BUD-06). The two reserved `DomainEventType` constants (D-9) and the `parent_id` column (D-3a) are the additive hooks for the deferred rounds.

## Alternatives considered

- **The cost-centre dimension — additive `journal_lines.cost_centre_id` vs a separate `journal_line_dimensions` ledger / EAV table.** *Decided: a column on `journal_lines`, mirroring `branch_id`.* A separate dimension table (one row per line per dimension) generalises to N dimensions but adds a join on every variance read, a write on every post, and an EAV-shaped query — over-engineering for v1's **one** new dimension. The column is the proven, indexable, `branch_id`-consistent choice. When a **second** dimension (project) lands, the same additive-column move repeats (a `project_id` column) — still simpler than EAV at 2–3 dimensions. (EAV becomes right only at many sparse dimensions, which the roadmap does not foresee.)
- **Dimension grain — `journal_lines` vs `journal_entries` (OQ-BUD-02).** *Decided: line grain.* Entry grain forces one cost centre per balanced entry, breaking the shared-cost accrual that splits legs across departments. Line grain matches `branch_id` and the analytical reality. (Examined in D-3b.)
- **Budget posting — post a memo/statistical journal vs post nothing.** *Decided: post nothing.* Some ERPs post budgets to a statistical ledger so budget and actual share the journal-line read. That conflates plan with fact in the real ledger (risking the trial balance), needs a parallel statistical-account scheme, and contradicts BR-BUD-01. A plain budget-lines table joined to the actuals aggregate is the boring, correct choice — the budget never pollutes the books.
- **Approval lifecycle — module-local vs the generic approvals engine (X.5).** *Decided: module-local.* X.5 is **not built**; blocking budgeting on it stalls the increment. A self-contained DRAFT→SUBMITTED→APPROVED/REJECTED machine ships now and re-points later (OQ-BUD-05). Rejected: waiting for X.5 (stalls); building the generic engine here (scope creep — that is X.5's ADR).
- **Single-active-version — partial unique index vs a `is_active`/`active_version_id` pointer on the budget.** *Decided: a partial unique on `status='APPROVED'` (+ the company-wide NULL caveat handled by a second partial unique + service supersede).* A pointer column on `budgets` (`active_version_id`) is an alternative, but it duplicates the truth already in `budget_versions.status` and can drift; the partial unique makes "≤1 approved per scope" a DB invariant, the stronger guarantee.
- **GL↔budgeting coupling for the cost-centre lookup — port/adapter (DIP) vs a direct `gl.service → budgeting.service` call.** *Decided: port/adapter (`CostCentreLookup` in `gl`).* A direct call creates a `gl → budgeting` edge; with `budgeting → gl` already present that is a **cycle** `ModuleBoundaryTest` would reject. The port keeps GL a leaf. (Examined in D-10 — the load-bearing ArchUnit decision.)
- **Variance actual source — re-aggregate `journal_lines` vs read a Reporting snapshot/materialised view.** *Decided: re-aggregate `journal_lines` (a new `cost_centre`-aware repository method).* Reporting has no materialised snapshot in v1 (ADR-0018 deferred them); the live aggregate over the indexed `journal_lines` is the shipped pattern and is fast enough with `ix_journal_lines_company_cc_account`. A snapshot is the right optimisation later (shared with Reporting's deferred snapshot item, PATH §3.2).

## Open items (OQ-BUD — recommended defaults adopted; the ★ items shape the model — owner should confirm before build)

- **★ OQ-BUD-01 — cost-centre tagging breadth:** adopted **manual-journal-only explicit tagging in v1** + best-effort pass-through (automatic posters Unallocated); per-document cost-centre fields additive later. **Owner: confirm** — broadening to tag sales/purchase/cash documents *in this increment* enlarges the slice (additive columns + UI + posting pass-through on those modules, listed as cross-module touch points if chosen).
- **★ OQ-BUD-02 — dimension grain:** adopted **`journal_lines.cost_centre_id` (line grain)**, mirroring `branch_id`. **Owner: confirm** (line grain enables split-across-centre entries; entry grain does not).
- **★ OQ-BUD-03 — budget period grain:** adopted **per-period (12 monthly) storage grain**; annual amount is a spread convenience. **Owner: confirm** (vs a genuine annual-only budget).
- **OQ-BUD-04 — maker≠checker:** adopted **not hard-enforced in v1** (role policy); a one-line guard addable later.
- **OQ-BUD-05 — approval-lifecycle home:** adopted **module-local** (X.5 not built); re-pointable later.
- **OQ-BUD-06 — variance sign/labels:** adopted **signed variance = actual − budget**; favourable/adverse labelled by account type in presentation. Confirm finance's preferred labels (presentation only).
- **OQ-BUD-07 — Unallocated treatment:** adopted **separate "Unallocated" bucket** (NULL `cost_centre_id`), never folded/dropped.
- **OQ-BUD-08 — parent roll-up:** adopted **`parent_id` stored, roll-up read deferred** (v1 reports the chosen leaf centre). Confirm whether parent roll-up is wanted in v1 (additive read).
- **OQ-BUD-09 (deferred, non-blocking):** profit-centre object, forecasting, commitment/encumbrance, allocations, dashboards, multi-dimensional tagging, hard enforcement — all deferred (§2.2); none precluded (NFR-BUD-06).

---

## Summary

ADR-0034 designs the **Budgeting & Management Accounting** module in a new `com.erp.modules.budgeting`: a **cost-centre dimension framework** (`cost_centres` master + the load-bearing **additive nullable `journal_lines.cost_centre_id` analysis tag**, mirroring the shipped `branch_id` tag — NULL = Unallocated, no double-entry change, no backfill), four budgeting tables (`cost_centres`, `budgets`, `budget_versions`, `budget_lines`), an append-only **budget version lifecycle** (DRAFT→SUBMITTED→APPROVED/REJECTED/SUPERSEDED, exactly one APPROVED version active per (company, FY, cost centre), enforced by a partial unique + a serialised supersede), manual-journal cost-centre tagging via a `CostCentreLookup` port in GL (DIP — no `gl → budgeting` cycle), and a **budget-vs-actual variance read** that joins approved-version `budget_lines` against the GL `journal_lines` aggregate grouped by account [× cost_centre], signed by normal balance — a sibling of the shipped P&L read, CSV-exportable.

**The headline: budgets post NOTHING** (D-7) — no `gl_config` key, no `JournalSourceType`, no new CoA account, no outbox event published. The only ledger interactions are *reading* actuals (D-8) and *setting* the analysis tag on a manual-journal post (D-6). **The enabler (D-3): `journal_lines.cost_centre_id`** is additive, nullable, NULL-safe for every shipped read (proof obligation: `MigrationKeepDataIT` + statement-output equality).

**Additive on frozen V1–V19, range V69–V73.** **#12-safe** (the only per-company CROSS-JOIN seed is the uid-less permission grant; numbering kinds are lazy; the optional default cost-centre seed, if taken, is md5-bounded). **No cycle** — budgeting reads GL like reporting does; GL's `CostCentreLookup` port keeps GL a leaf. **Cross-module touch list:** (1) **budgeting → gl** — reads `ChartOfAccountRepository`/`FiscalYear/PeriodRepository`/`JournalLineRepository` (a new `cost_centre`-aware aggregate method); (2) **gl → budgeting via DIP** — the additive `JournalEntryDraft.LineDraft.costCentreId` field + a `CostCentreLookup` port in `gl` implemented by `budgeting` (the one manual-journal write path to the new tag); (3) **reporting** — the variance/departmental reads reuse the `ReportExporter` CSV path. The model is concrete enough to build V69–V73 + the four entities + the version lifecycle + the variance read without guessing a business rule.
