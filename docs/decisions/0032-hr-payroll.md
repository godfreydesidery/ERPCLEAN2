# 0032 — HR & Payroll (Tanzania) data model: an employee/contract/leave master plus a configurable, effective-dated TZ-statutory payroll engine (PAYE bands + NSSF + WCF + SDL + HESLB held as updatable rate tables), a DRAFT→CALCULATED→APPROVED→POSTED→PAID payroll run that posts one balanced journal over the outbox (`PAYROLL.FINALISED`) and disburses net + statutory payables through the shipped Cash & Bank `CashDirectEntryService`, all on the existing GL posting engine, idempotent outbox, `code_sequence` numbering, and `ScopeGuard`/RBAC spine

- **Status:** Proposed
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (pending system-analyst ratification of [docs/requirements/hr-payroll.md](../requirements/hr-payroll.md) — the load-bearing OQs OQ-HR-01/04/07 must be owner-confirmed before this ADR moves to Accepted; the *data model* below is buildable now because the statutory engine is deliberately rate-table-driven, so the open rate values are seed data, not schema).
- **Context source:** [docs/requirements/hr-payroll.md](../requirements/hr-payroll.md) (FR-HR-01..24, BR-HR-01..12, NFR-HR-01..08, US-HR-01..08, §6 flows, §9 seed defaults, §11 OQ log — the ground truth for every rule below). [docs/PATH-TO-FULL-ERP.md](../PATH-TO-FULL-ERP.md) §3.7 (the backlog scope) + §5 Phase C. Verified against the **shipped** code:
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / V10): `GLPostingService.post(JournalEntryDraft)→JournalEntryDto` (validates ≥2 lines, balance, OPEN period, active accounts, base currency; writes batch+entry+lines atomically; returns the new `journal_entries.uid`) + `postReversal(originalEntryUid, reversalDate, sourceType, sourceRef, postedBy)`; `JournalEntryDraft(companyId, branchId, postingDate LocalDate, description, sourceType, sourceRef, reversalOfId, postedBy, List<LineDraft>)` + `LineDraft(accountId, debitAmount, creditAmount, currency, lineMemo)`; `GLConfigResolver.resolve(companyId, GlConfigKey)→ChartOfAccount` (throws on missing mapping / inactive account — BR-GL-10); `GLPostingSafeInvoker.postInNewTx(draft)` (REQUIRES_NEW; catches all, returns null — the handler-posts-GL safety wrapper); `GlConfigKey` enum (verified: `SALES_REVENUE,VAT_PAYABLE,ACCOUNTS_RECEIVABLE,CASH,INVENTORY,COGS,ACCOUNTS_PAYABLE,BAD_DEBT_EXPENSE,OPENING_BALANCE_EQUITY,PURCHASES,VAT_INPUT,VAT_DUE,WHT_PAYABLE,WHT_RECEIVABLE,RETAINED_EARNINGS,GRNI,STOCK_ADJUSTMENT` — **NO payroll keys**) + the `chk_gl_config_key` IN-list CHECK widened additively per increment; `JournalSourceType` (verified: reserves **`PAYROLL`** but the DB CHECK does **not yet admit it** — this ADR widens `chk_journal_batch_source_type`/`chk_journal_entry_source_type`); the new-company seeders `ChartOfAccountServiceImpl.DEFAULT_ACCOUNTS` + `GlConfigServiceImpl.DEFAULT_MAPPINGS` (the per-module seeder pattern wired in `BootstrapRunner` + `CompanyService.create`).
  - **Cash & Bank** ([ADR-0016](0016-cash-and-bank-data-model.md) / V13): the **shipped** `CashDirectEntryService.recordDirectEntry(...)` posts **DR `counter_gl_account_id` / CR the chosen account's `gl_account_id`** (or the reverse, by `direction`) synchronously in the same TX and appends one `cash_transactions(txn_type=DIRECT_ENTRY, direction, amount, counter_gl_account_id, journal_entry_ref)` row; `CashBankAccountResolver(companyId, cashBankAccountUid?)→linked GL account`; `JournalSourceType.CASH_DIRECT` admitted (V13). **This is the disbursement vehicle** — payroll pays exactly the way a `DIRECT_ENTRY` does: DR the payroll payable (counter) / CR the bank account's GL.
  - **Outbox / idempotency** ([ADR-0009](0009-transactional-outbox.md)): `OutboxPublisher.publish(eventType, aggregateType, aggregateId, aggregateUid, companyId, branchId, payload)` in the caller's TX; `DomainEventHandler.eventType()/handle()`, `@Transactional(MANDATORY)`; `IdempotencyGuard.alreadyProcessed(consumer, eventUid)/markProcessed`; `processed_events(consumer, event_uid)`; `DomainEventType` (verified: `SALE.FINALISED/SALE.VOIDED/STOCK.RECEIVED/STOCK.RECEIPT.VOIDED/DELIVERY.CONFIRMED/DELIVERY.RETURNED` — **this ADR adds `PAYROLL.FINALISED` + `PAYROLL.REVERSED` + agg `PAYROLL_RUN`**). The shipped `SalesPostingHandler` is the **exact template** the `PayrollPostingHandler` copies (outbox handler posting GL via `GLPostingSafeInvoker`, idempotent, system principal).
  - **IAM** ([ADR-0001](0001-iam-architecture.md)/[0002](0002-rbac-enforcement.md)): `RequestContext` (company/branch from JWT + override), `ScopeGuard.companyIdOf(targetType, uid)` switch + `assertCanActIn`, `@perm.has(code)` / `@perm.scoped(uid, type, code)` (the `PermissionChecks` bean — verified; **NEVER `hasAuthority`**), the `permissions`/`roles`/`role_permissions` seed + `ORG_ADMIN` CROSS-JOIN grant pattern, `app_users(id)` (the optional self-service link), audit append-only.
  - **Money** ([ADR-0005](0005-money-and-currency.md)): base currency TZS, `NUMERIC(19,4)`, HALF_UP.
  - **Numbering** ([ADR-0007](0007-products-data-model.md) D-6): `code_sequence(company_id, entity_kind)` row-locked allocation; new `entity_kind` rows created lazily on first use.
  - [[db-naming-convention]] verified against V1–V19 (plural masters/owned-children + log tables; singular constraint roots `uq_`/`fk_`/`chk_`; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id`/`branch_id` BIGINT scalar; the additive `DROP/ADD CONSTRAINT` widen for `chk_gl_config_key`/`chk_journal_*_source_type`). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`.

> **Migration band (coordinator-assigned).** Latest shipped is **V19__sales_returns.sql**; this module is being designed in parallel with other extension modules, so it is assigned the reserved additive band **V56–V63** (the coordinator owns cross-module collision avoidance). All V56–V63 are **additive on the frozen V1–V19** — they never edit a shipped migration. Next free ADR number was assigned **0032** by the coordinator.

This ADR is the **technical data model + integration design** for HR & Payroll (PATH §3.7, Phase C). It translates the spec into: the employee/department/contract/pay-component/leave master tables, the **configurable effective-dated TZ-statutory rate tables** (the heavy part), the payroll-run + payroll-line + payslip + statutory-snapshot tables, the run lifecycle enums + transitions, the statutory computation contract, every GL posting leg (with the new `GlConfigKey`s + CoA accounts), the disbursement reuse of `CashDirectEntryService`, the `PAYROLL.FINALISED` event + handler, idempotency, numbering, perms, `ScopeGuard` cases, Angular routes, the V56–V63 migration ordering with **#12-safe seeds**, and the ArchUnit edges. It is **concrete enough that the backend engineer builds without guessing a business rule.** It writes **no production code, no entities, no migration SQL.**

## Context

Payroll is, financially, "AP for staff": a periodic run that computes amounts, posts a balanced journal, and disburses cash — and every platform piece it needs is shipped (GL posting engine, `gl_configs`, Cash & Bank disbursement, the idempotent outbox, numbering, RBAC). The forces specific to this module:

- **THE HARD PART — statutory configurability (NFR-HR-01).** Tanzanian PAYE bands, NSSF/WCF/SDL/HESLB rates change with the budget. A rate must be **data in updatable, effective-dated, append-only tables**, never a code constant, and a posted run must reproduce exactly under the rates in force on its pay date (BR-HR-08, NFR-HR-02). The shape question: one polymorphic rate table vs a small family of typed rate-set tables (PAYE needs ordered bands; NSSF/WCF/SDL/HESLB need a single percentage each). Resolved in **D-3**.

- **Reproducibility demands a per-line snapshot.** A payroll line must record *which* rate sets it applied and the resulting amounts, so a re-open/re-render reproduces without re-resolving rates that may since have changed. Resolved in **D-4/D-5**.

- **The run lifecycle is a control spine (FR-HR-14..18).** DRAFT→CALCULATED→APPROVED→POSTED→PAID(+REVERSED). CALCULATE is re-runnable (re-opens to CALCULATED, voids approval); POST is the one-way GL event; PAID is the disbursement. The grain: run header + one line per employee + a statutory snapshot per line. Resolved in **D-2/D-6**.

- **GL posting reuses the engine; the question is the chart of accounts + which leg is employer-cost.** Salary expense (DR), employer NSSF/WCF/SDL expense (DR), each statutory payable (CR), voluntary-deduction targets (CR), net-wages payable (CR). New CoA accounts + `GlConfigKey`s, posted via the same `GLPostingService`. Resolved in **D-7/D-8**.

- **Disbursement is not a new mechanism — it is a Cash & Bank `DIRECT_ENTRY` (FR-HR-20).** Paying net wages / a statutory remittance is DR the payable / CR the bank account's linked GL — exactly `CashDirectEntryService.recordDirectEntry`. Payroll calls it; it does not re-implement cash posting. Resolved in **D-9**.

- **Sensitive data, least privilege (BR-HR-11, NFR-HR-05).** Salary and payslip access is gated by dedicated perms; an employee self-service principal sees only their own records. Resolved in **D-11/D-12**.

- **Schema freeze / direction.** V1–V19 frozen; HR is additive (V56–V63), FKing only frozen platform tables (`companies`/`branches`/`app_users`/`chart_of_accounts`/`fiscal_periods`) + intra-module HR tables, referencing GL `journal_entries` / Cash `cash_transactions` by **scalar uid**. HR is a **new leaf**: it posts *into* GL (the `ap.service → gl.service` precedent) and calls *into* Cash & Bank (the AR/AP → cashbank precedent), and nothing depends back on HR. No cycle. Resolved in **D-1/D-13**.

## Decision

### D-1 — Module placement: one `com.erp.modules.hr` module; controllers flat in `com.erp.api`; outbound edges to `gl.service` and `cashbank.service`

HR & Payroll lives under **`com.erp.modules.hr`** — a flat sibling of `gl`/`ar`/`ap`/`cashbank`/`sales` (PROJECT-CONVENTIONS §2). **`hr`, not `hr.payroll`** — the module owns both the HR master (employees/contracts/leave) and the payroll engine as one cohesive domain; payroll without the employee master is meaningless, and a split would force two modules to share the employee aggregate. Internal layout:

```
com.erp.modules.hr
├── domain.entity   Department, Employee, EmploymentContract,
│                   PayComponent, EmployeeRecurringItem,
│                   PayeBandSet, PayeBand, StatutoryRateSet,
│                   LeaveType, LeaveRequest, LeaveBalance,
│                   EmployeeLoan, EmployeeLoanInstallment,
│                   PayrollRun, PayrollLine, PayrollLineItem, PayrollStatutorySnapshot,
│                   Payslip
├── domain.dto      EmployeeDto / CreateEmployeeRequest / UpdateEmployeeRequest,
│                   ContractDto / CreateContractRequest,
│                   DepartmentDto, PayComponentDto, EmployeeRecurringItemDto,
│                   PayeBandSetDto / CreatePayeBandSetRequest (+ bands),
│                   StatutoryRateSetDto / CreateStatutoryRateSetRequest,
│                   LeaveTypeDto, LeaveRequestDto / SubmitLeaveRequest / DecideLeaveRequest, LeaveBalanceDto,
│                   EmployeeLoanDto / CreateLoanRequest,
│                   PayrollRunDto / CreatePayrollRunRequest, PayrollLineDto, PayslipDto, PayslipYtdDto,
│                   StatutorySummaryDto,
│                   PayrollFinalisedPayload  (NEW outbox payload, D-10),
│                   PayrollReversedPayload    (NEW outbox payload, D-10)
├── domain.enums    EmploymentStatus, ContractType, PayFrequency,
│                   PayComponentKind, PayComponentBasis,
│                   StatutoryRateType, LeaveRequestStatus, LeaveAccrualMethod,
│                   PayrollRunStatus, PayrollLineStatus, LoanStatus  (D-2)
├── repository      one Spring-Data repository per entity (company-scoped finders + the run/YTD aggregates)
└── service         EmployeeService(+Impl), DepartmentService(+Impl), ContractService(+Impl),
                    PayComponentService(+Impl), LeaveService(+Impl), EmployeeLoanService(+Impl),
                    StatutoryRateService(+Impl)        — manage PAYE band sets + rate sets, append-only (D-3),
                    StatutoryCalculator                 — PAYE/NSSF/WCF/SDL/HESLB compute from in-force sets (D-5),
                    PayrollRunService(+Impl)            — create/calculate/approve/post/reverse/pay (D-6),
                    PayrollGlPoster                     — builds the balanced PAYROLL draft (D-7/D-8),
                    PayslipQuery / StatutorySummaryQuery — payslip + YTD + statutory-summary reads (D-12),
                    HrNumberGenerator                   — EMP/PAYRUN/PAYSLIP/LOAN via code_sequence (D-14),
                    HrGlSeeder                          — seeds payroll CoA accounts + keys for a new company (D-8),
                    HrStatutorySeeder                   — seeds the default TZ rate sets for a new company (D-3)
   events           PayrollPostingHandler               — PAYROLL.FINALISED → balanced journal (D-10),
                    PayrollReversalHandler              — PAYROLL.REVERSED → reversing journal (D-10)
```

Controllers flat in `com.erp.api`: `EmployeeController`, `DepartmentController`, `ContractController`, `PayComponentController`, `LeaveController`, `EmployeeLoanController`, `StatutoryRateController`, `PayrollRunController`, `PayslipController`, `HrReportController` (statutory summary + payslip register). They touch only services (`ModuleBoundaryTest`). The two `events` handlers are HR beans implementing `platform.events.DomainEventHandler` (the only cross-cutting coupling), exactly as the Stock/GL handlers do.

**Boundary note (D-13):** HR reads **DTOs only** from IAM (the `app_user` link is a scalar `Long user_id` + uid, no cross-module `@ManyToOne`). The payroll GL effect goes through the outbox → `gl.service` (the `SalesPostingHandler` precedent); the disbursement goes through a **synchronous service call** into `cashbank.service` (`CashDirectEntryService`), the AR/AP → cashbank precedent. HR imports no GL/Cash entity.

### D-2 — Lifecycle + status enums (the exact sets, transitions)

All transitions are **service-guarded, audited, append-only** (NFR-HR-02/03); status is never free-set.

**`PayrollRunStatus`** (FR-HR-14..18, the control spine):
```
DRAFT ──calculate──▶ CALCULATED ──approve──▶ APPROVED ──post──▶ POSTED ──disburseNet──▶ PAID
  │                      ▲   │                    │                 │
  │      recalculate ────┘   │ (recalculate voids │                 └──reverse──▶ REVERSED (terminal;
  │   (re-opens, voids       │  approval, →CALC)   │                              reversing GL posted)
  │    approval)             └─────────────────────┘
  └──(hard delete allowed while DRAFT — consumed no number until create; see D-14)
```
- `PAYRUN-####` allocated **at create** (the run is a working document from draft).
- `recalculate` is allowed in CALCULATED/APPROVED and **re-opens to CALCULATED** (voids a prior approval — FR-HR-16); not allowed once POSTED.
- `post` is the one-way GL event (FR-HR-17); `disburseNet` moves POSTED→PAID (FR-HR-20); statutory remittances may be disbursed while POSTED or PAID without changing the run status (they clear their own payables, D-9).
- `reverse` is allowed from POSTED/PAID → **REVERSED** (terminal); it posts a reversing journal (D-10) and the correction is a **new run** (FR-HR-18). Stored set: `DRAFT, CALCULATED, APPROVED, POSTED, PAID, REVERSED`.

**`PayrollLineStatus`** (per-employee line within a run): `OK, FLAGGED` — `FLAGGED` when net < 0 or a required input is missing (BR-HR-07); a run with any `FLAGGED` line **cannot be APPROVED** until resolved. (Two values; stored on the line.)

**`EmploymentStatus`**: `ACTIVE, ON_LEAVE, SUSPENDED, TERMINATED` (FR-HR-03; only ACTIVE with an active contract is selected by a run — D-6).

**`ContractType`**: `PERMANENT, FIXED_TERM, CASUAL, PROBATION`. **`PayFrequency`**: `MONTHLY` (v1; the column carries it so weekly/bi-weekly are additive — OQ-HR-02).

**`PayComponentKind`**: `EARNING, DEDUCTION` — voluntary/recurring components only; statutory items are computed, not catalogue components. **`PayComponentBasis`**: `FIXED, PERCENT_OF_BASIC`.

**`StatutoryRateType`**: `NSSF, WCF, SDL, HESLB` (the single-percentage statutory types; PAYE is its own banded table, D-3). **`LeaveRequestStatus`**: `PENDING, APPROVED, REJECTED, CANCELLED`. **`LeaveAccrualMethod`**: `ANNUAL_GRANT, MONTHLY_ACCRUAL`. **`LoanStatus`**: `ACTIVE, SETTLED, CANCELLED`.

### D-3 — The configurable, effective-dated TZ-statutory rate tables (the heavy part — NFR-HR-01, BR-HR-08, FR-HR-07/08/09)

**Decision: a typed family — a banded `paye_band_sets` + `paye_bands` for PAYE, and a single flat `statutory_rate_sets` (one row per type per effective date) for NSSF/WCF/SDL/HESLB.** PAYE is genuinely banded (ordered marginal bands with a cumulative fixed tax); NSSF/WCF/SDL/HESLB are each a single percentage with a few scalar parameters (ceiling, threshold, basis) — a polymorphic one-table-fits-all would force PAYE's ordered children into a JSON blob or a second table anyway, and would lose the per-type typed columns. The typed family is the legible, query-able, append-only choice. **All sets are `effective_from`-dated and append-only** — a posted run reproduces under the set whose `effective_from ≤ pay_date` and is the latest such (BR-HR-08).

#### `paye_band_sets` (header — the PAYE schedule effective from a date)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_paye_band_set_uid`; `ScopeGuard case "payebandset"` |
| `company_id` | BIGINT | NO | FK → `companies(id)`; tenant; `fk_paye_band_set_company` |
| `effective_from` | DATE | NO | the date this schedule takes effect (FR-HR-07) |
| `tax_free_threshold` | NUMERIC(19,4) | NO | the monthly tax-free amount (e.g. 270000); `CHECK >= 0` |
| `description` | VARCHAR(160) | YES | e.g. "FY2025/26 PAYE bands" |
| `version` + audit cols | | | append-only on the *historical* set (D-9 note) |

Constraints: `uq_paye_band_set_uid`; `uq_paye_band_set_company_effective UNIQUE (company_id, effective_from)` (one schedule per effective date); `fk_paye_band_set_company`; `chk_paye_band_set_threshold CHECK (tax_free_threshold >= 0)`. Index `ix_paye_band_sets_company_eff ON paye_band_sets (company_id, effective_from DESC)` — the in-force resolution hits this.

#### `paye_bands` (child — the ordered marginal bands)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_paye_band_uid` |
| `paye_band_set_id` | BIGINT | NO | FK → `paye_band_sets(id)`; `fk_paye_band_set` |
| `company_id` | BIGINT | NO | denormalised tenant |
| `band_no` | SMALLINT | NO | 1-based ordinal; `uq_paye_band_set_no UNIQUE (paye_band_set_id, band_no)` |
| `lower_bound` | NUMERIC(19,4) | NO | the monthly-income lower bound this band's marginal rate applies above; `CHECK >= 0` |
| `marginal_rate` | NUMERIC(9,4) | NO | the % on the excess over `lower_bound` (e.g. 8.0000); `CHECK BETWEEN 0 AND 100` |
| `cumulative_fixed_tax` | NUMERIC(19,4) | NO | the tax accrued on all lower bands (e.g. 20000 at the 520000 band); `CHECK >= 0` |
| audit cols | | | |

PAYE compute (D-5): find the highest band whose `lower_bound ≤ taxable_pay`; `tax = cumulative_fixed_tax + marginal_rate% × (taxable_pay − lower_bound)`. The seeded TZ default (per requirements §9, owner-confirmable OQ-HR-04) is the five-line schedule (threshold 270000; bands at 270000/8%/0, 520000/20%/20000, 760000/25%/68000, 1000000/30%/128000). `chk_paye_band_rate CHECK (marginal_rate BETWEEN 0 AND 100)`.

#### `statutory_rate_sets` (NSSF / WCF / SDL / HESLB — one row per type per effective date)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_statutory_rate_set_uid`; `ScopeGuard case "statutoryrateset"` |
| `company_id` | BIGINT | NO | FK → `companies(id)`; tenant |
| `rate_type` | VARCHAR(10) | NO | `StatutoryRateType` ∈ {NSSF,WCF,SDL,HESLB}; `chk_statutory_rate_set_type` |
| `effective_from` | DATE | NO | |
| `employee_rate` | NUMERIC(9,4) | YES | employee % (NSSF: e.g. 10; HESLB: e.g. 15; WCF/SDL: NULL — employer-only); `CHECK (employee_rate IS NULL OR employee_rate BETWEEN 0 AND 100)` |
| `employer_rate` | NUMERIC(9,4) | YES | employer % (NSSF/WCF/SDL employer; HESLB: NULL); `CHECK (employer_rate IS NULL OR employer_rate BETWEEN 0 AND 100)` |
| `basis` | VARCHAR(16) | NO | what the % applies to: `GROSS` (WCF/SDL), `PENSIONABLE` (NSSF), `BASIC` (HESLB); `chk_statutory_rate_set_basis` |
| `ceiling_amount` | NUMERIC(19,4) | YES | optional contribution ceiling (NSSF — NULL = no ceiling, the v1 default, OQ-HR-05) |
| `headcount_threshold` | SMALLINT | YES | SDL applies only when in-scope headcount ≥ this (e.g. 10); NULL for non-SDL |
| `active` | BOOLEAN | NO | DEFAULT true — a type may be switched off (e.g. HESLB if not used) |
| `description` | VARCHAR(160) | YES | |
| `version` + audit | | | append-only on historical sets |

Constraints: `uq_statutory_rate_set_uid`; `uq_statutory_rate_set_company_type_eff UNIQUE (company_id, rate_type, effective_from)` (one set per type per effective date); `fk_statutory_rate_set_company`; `chk_statutory_rate_set_type CHECK (rate_type IN ('NSSF','WCF','SDL','HESLB'))`; `chk_statutory_rate_set_basis CHECK (basis IN ('GROSS','PENSIONABLE','BASIC'))`. Index `ix_statutory_rate_sets_company_type_eff ON statutory_rate_sets (company_id, rate_type, effective_from DESC)` — the in-force resolution.

> **Why effective-dated append-only, not editable rows.** A posted run must reproduce exactly (NFR-HR-02). If a rate row were edited, every prior run that read it would silently re-compute differently. So a rate change is **a new row with a later `effective_from`** (FR-HR-09); `StatutoryRateService` rejects an edit to a set that any POSTED run has consumed (it may correct a not-yet-used future set). The per-line snapshot (D-4) is the durable record of what a given run actually applied. **NHIF (OQ-HR-07) is additive** — a new `StatutoryRateType.NHIF` value + a CHECK widen + a payable key/account, no reshape.

### D-4 — Employee / contract / pay-component / leave / loan master tables

All: `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) `uq_<root>_uid`; `company_id` BIGINT NOT NULL (tenant; denormalised onto children, set-once); standard audit cols; `@Version` on mutable masters. Money `NUMERIC(19,4)`, percent `NUMERIC(9,4)`, days `NUMERIC(9,2)`. `MasterStatus`/lifecycle via the relevant status enum.

#### `departments`
`id`/`uid` (`uq_department_uid`), `company_id`, `code` VARCHAR(30) (`uq_department_company_code`), `name` VARCHAR(120), `active` BOOLEAN DEFAULT true, audit. `ScopeGuard case "department"`.

#### `employees`
| column | type | null | notes |
|---|---|---|---|
| `id`/`uid` | | NO | `uq_employee_uid`; `ScopeGuard case "employee"` |
| `company_id` | BIGINT | NO | tenant FK |
| `branch_id` | BIGINT | YES | the employee's posting/analysis branch (`journal_lines.branch_id` source) |
| `employee_number` | VARCHAR(30) | NO | `EMP-####`; `uq_employee_company_number UNIQUE (company_id, employee_number)` |
| `first_name`/`last_name` | VARCHAR(80) | NO | |
| `national_id` | VARCHAR(40) | YES | |
| `tin` | VARCHAR(20) | YES | TRA TIN (for PAYE) |
| `nssf_number` | VARCHAR(40) | YES | |
| `heslb_number` | VARCHAR(40) | YES | |
| `date_of_birth` | DATE | YES | |
| `gender` | VARCHAR(10) | YES | |
| `hire_date` | DATE | NO | |
| `department_id` | BIGINT | YES | FK → `departments(id)` |
| `job_title` | VARCHAR(120) | YES | |
| `status` | VARCHAR(16) | NO | `EmploymentStatus`; DEFAULT `'ACTIVE'`; `chk_employee_status CHECK (status IN ('ACTIVE','ON_LEAVE','SUSPENDED','TERMINATED'))` |
| `user_id` | BIGINT | YES | optional FK → `app_users(id)` — the self-service link (BR-HR-11, D-12); `uq_employee_user UNIQUE (company_id, user_id)` (NULLs distinct) |
| `version` + audit | | | |

FKs `fk_employee_company`/`_branch`/`_department`/`_user`. Indexes `ix_employees_company`, `ix_employees_company_status`, `ix_employees_user (user_id) WHERE user_id IS NOT NULL`.

#### `employment_contracts`
`id`/`uid` (`uq_employment_contract_uid`; `ScopeGuard case "employmentcontract"`), `company_id`, `employee_id` FK → `employees(id)`, `contract_type` VARCHAR(16) (`chk_employment_contract_type CHECK (... IN ('PERMANENT','FIXED_TERM','CASUAL','PROBATION'))`), `base_salary_amount` NUMERIC(19,4) `CHECK >= 0`, `currency` VARCHAR(3) (= base), `pay_frequency` VARCHAR(12) DEFAULT `'MONTHLY'` (`chk_employment_contract_frequency CHECK (... IN ('MONTHLY'))` — widen for weekly/bi-weekly), `start_date` DATE, `end_date` DATE NULL, the enrolment flags `paye_resident`/`nssf_member`/`heslb_borrower`/`wcf_covered`/`sdl_counted` BOOLEAN NOT NULL DEFAULT (sensible), `active` BOOLEAN NOT NULL DEFAULT true, `version` + audit. **At most one active contract per employee** — `uq_employment_contract_active UNIQUE (company_id, employee_id) WHERE active = true` (the partial-unique `uq_user_branch_default` precedent). `chk_employment_contract_dates CHECK (end_date IS NULL OR end_date >= start_date)`.

#### `pay_components` (master) + `employee_recurring_items`
`pay_components`: `id`/`uid` (`uq_pay_component_uid`; `ScopeGuard case "paycomponent"`), `company_id`, `code` VARCHAR(30) (`uq_pay_component_company_code`), `name` VARCHAR(120), `kind` VARCHAR(10) (`chk_pay_component_kind CHECK (... IN ('EARNING','DEDUCTION'))`), `basis` VARCHAR(20) (`chk_pay_component_basis CHECK (... IN ('FIXED','PERCENT_OF_BASIC'))`), `gl_account_id` BIGINT NOT NULL FK → `chart_of_accounts(id)` (the expense account for an EARNING, the payable/receivable for a DEDUCTION), `taxable` BOOLEAN NOT NULL (counts toward PAYE taxable pay), `pensionable` BOOLEAN NOT NULL (counts toward NSSF pensionable pay), `active` BOOLEAN DEFAULT true, `version` + audit.

`employee_recurring_items`: `id`/`uid` (`uq_employee_recurring_item_uid`), `company_id`, `employee_id` FK, `pay_component_id` FK → `pay_components(id)`, `amount_or_percent` NUMERIC(19,4) NOT NULL `CHECK >= 0` (interpreted by the component's `basis`), `effective_from` DATE NOT NULL, `effective_to` DATE NULL, `version` + audit. Index `ix_employee_recurring_items_employee (company_id, employee_id)`. The run picks items where `effective_from ≤ pay_date AND (effective_to IS NULL OR effective_to ≥ pay_date)`.

#### `leave_types` + `leave_requests` + `leave_balances`
`leave_types`: `id`/`uid` (`uq_leave_type_uid`; `ScopeGuard case "leavetype"`), `company_id`, `code` VARCHAR(30) (`uq_leave_type_company_code`), `name` VARCHAR(120), `is_paid` BOOLEAN NOT NULL (UNPAID type → `false`, drives the pro-rata gross reduction BR/FR-HR-13), `annual_entitlement_days` NUMERIC(9,2), `accrual_method` VARCHAR(16) (`chk_leave_type_accrual CHECK (... IN ('ANNUAL_GRANT','MONTHLY_ACCRUAL'))`), `active` BOOLEAN DEFAULT true, `version` + audit.

`leave_requests`: `id`/`uid` (`uq_leave_request_uid`; `ScopeGuard case "leaverequest"`), `company_id`, `employee_id` FK, `leave_type_id` FK, `from_date`/`to_date` DATE (`chk_leave_request_dates CHECK (to_date >= from_date)`), `days` NUMERIC(9,2) `CHECK > 0`, `status` VARCHAR(12) DEFAULT `'PENDING'` (`chk_leave_request_status CHECK (... IN ('PENDING','APPROVED','REJECTED','CANCELLED'))`), `decided_by` BIGINT NULL FK → `app_users(id)`, `decided_at` TIMESTAMPTZ NULL, `reason`/`decision_note` VARCHAR(255) NULL, `version` + audit. Index `ix_leave_requests_employee (company_id, employee_id, status)`.

`leave_balances`: `id`/`uid` (`uq_leave_balance_uid`), `company_id`, `employee_id` FK, `leave_type_id` FK, `as_of_year` SMALLINT, `entitled_days`/`taken_days`/`balance_days` NUMERIC(9,2) NOT NULL DEFAULT 0, `version` + audit. `uq_leave_balance_employee_type_year UNIQUE (company_id, employee_id, leave_type_id, as_of_year)`. The balance is the per-(employee, type, year) running figure approval decrements (FR-HR-12).

#### `employee_loans` + `employee_loan_installments`
`employee_loans`: `id`/`uid` (`uq_employee_loan_uid`; `ScopeGuard case "employeeloan"`), `company_id`, `employee_id` FK, `loan_number` VARCHAR(30) (`LOAN-####`; `uq_employee_loan_company_number`), `principal_amount` NUMERIC(19,4) `CHECK > 0`, `installment_amount` NUMERIC(19,4) `CHECK > 0`, `outstanding_amount` NUMERIC(19,4) NOT NULL `CHECK >= 0`, `gl_account_id` BIGINT NOT NULL FK → `chart_of_accounts(id)` (the loan-receivable account the run's deduction credits), `status` VARCHAR(12) DEFAULT `'ACTIVE'` (`chk_employee_loan_status CHECK (... IN ('ACTIVE','SETTLED','CANCELLED'))`), `start_date` DATE, `currency` VARCHAR(3), `version` + audit. `employee_loan_installments`: `id`/`uid`, `company_id`, `employee_loan_id` FK, `installment_no` SMALLINT (`uq_employee_loan_installment_no UNIQUE (employee_loan_id, installment_no)`), `due_amount` NUMERIC(19,4), `due_period` VARCHAR(7) (`YYYY-MM`), `deducted_in_run_uid` VARCHAR(26) NULL (set when a run deducts it), `status` VARCHAR(12), audit. The run (D-6) deducts the installment due in its period and decrements `outstanding_amount`.

### D-5 — The statutory computation contract (`StatutoryCalculator`)

A stateless service: given `(companyId, payDate, contract, grossEarnings, pensionablePay, basicPay, unpaidLeaveDays, periodWorkingDays, inScopeHeadcount)` it resolves the **in-force** rate sets (the latest `effective_from ≤ payDate` per type, plus the latest PAYE band set) and returns a `StatutoryResult(payeAmount, nssfEmployee, nssfEmployer, wcfEmployer, sdlEmployer, heslbAmount, taxablePay, appliedPayeBandSetUid, appliedRateSetUids)`. Algorithm (HALF_UP at each statutory boundary; whole-shilling PAYE per OQ-HR-06):

```
grossForPeriod   = grossEarnings × (periodWorkingDays − unpaidLeaveDays) / periodWorkingDays   // FR-HR-13 pro-rata (paid leave unaffected)
nssfEmployee     = contract.nssf_member ? round(min(pensionablePay, nssfSet.ceiling? ) × nssfSet.employee_rate%) : 0
nssfEmployer     = contract.nssf_member ? round(min(pensionablePay, nssfSet.ceiling? ) × nssfSet.employer_rate%) : 0
taxablePay       = round( grossForPeriod − allowablePreTax )      // allowablePreTax = nssfEmployee by default (OQ-HR-03, config)
payeAmount       = contract.paye_resident ? payeFromBands(payeBandSet, taxablePay) : 0
heslbAmount      = contract.heslb_borrower ? round(basicPay × heslbSet.employee_rate%) : 0
wcfEmployer      = contract.wcf_covered   ? round(grossForPeriod × wcfSet.employer_rate%) : 0
sdlEmployer      = (contract.sdl_counted && inScopeHeadcount >= sdlSet.headcount_threshold) ? round(grossForPeriod × sdlSet.employer_rate%) : 0
```
- `payeFromBands` = the D-3 banded lookup. A missing in-force set for an enrolled type → the line is **FLAGGED** (BR-HR-07) and the run cannot approve, never a silent zero.
- The computed amounts + the applied set uids are snapshotted on the line (D-6) so the run reproduces (NFR-HR-02). `inScopeHeadcount` is the count of SDL-counted active employees in the run (an SDL is a run-level employer cost — it is computed per line for transparency but the GL posts the run total, D-7).

### D-6 — Payroll run tables + the line grain (FR-HR-14/15)

#### `payroll_runs` (header)
| column | type | null | notes |
|---|---|---|---|
| `id`/`uid` | | NO | `uq_payroll_run_uid`; `ScopeGuard case "payrollrun"` |
| `company_id` | BIGINT | NO | tenant |
| `branch_id` | BIGINT | YES | analysis tag for the run's GL entry |
| `run_number` | VARCHAR(30) | NO | `PAYRUN-####` at create; `uq_payroll_run_company_number` |
| `period_year` | SMALLINT | NO | e.g. 2026 |
| `period_month` | SMALLINT | NO | 1..12; `chk_payroll_run_month CHECK (period_month BETWEEN 1 AND 12)` |
| `pay_date` | DATE | NO | drives statutory-set resolution + the GL posting period (NFR-HR-02) |
| `status` | VARCHAR(12) | NO | `PayrollRunStatus`; DEFAULT `'DRAFT'`; `chk_payroll_run_status CHECK (... IN ('DRAFT','CALCULATED','APPROVED','POSTED','PAID','REVERSED'))` |
| `gross_total`/`deduction_total`/`net_total` | NUMERIC(19,4) | NO | DEFAULT 0; run roll-ups |
| `employer_cost_total` | NUMERIC(19,4) | NO | DEFAULT 0; Σ employer NSSF+WCF+SDL |
| `calculated_at`/`approved_at`/`posted_at`/`paid_at`/`reversed_at` | TIMESTAMPTZ | YES | transition stamps |
| `approved_by`/`posted_by` | BIGINT | YES | FK → `app_users(id)` |
| `gl_entry_uid` | VARCHAR(26) | YES | the `journal_entries.uid` the post produced (set by the handler; diagnostic + the reversal anchor, D-10) |
| `reversal_of_run_uid` | VARCHAR(26) | YES | set on a correcting re-run pointing at the reversed run (audit) |
| `version` + audit | | | |

`uq_payroll_run_company_period UNIQUE (company_id, period_year, period_month) WHERE status <> 'REVERSED'` — **one live run per company per month** (BR-HR-03; a reversed run frees the period for a re-run). FKs `fk_payroll_run_company`/`_branch`/`_approved_by`/`_posted_by`. Index `ix_payroll_runs_company_period (company_id, period_year, period_month)`.

#### `payroll_lines` (child — one per employee)
| column | type | null | notes |
|---|---|---|---|
| `id`/`uid` | | NO | `uq_payroll_line_uid` |
| `payroll_run_id` | BIGINT | NO | FK → `payroll_runs(id)` |
| `company_id` | BIGINT | NO | denormalised tenant |
| `employee_id` | BIGINT | NO | FK → `employees(id)`; `uq_payroll_line_run_employee UNIQUE (payroll_run_id, employee_id)` |
| `employee_number`/`employee_name`/`department_name` | VARCHAR | NO | snapshots |
| `gross_amount`/`taxable_amount`/`net_amount` | NUMERIC(19,4) | NO | computed (D-5) |
| `paye_amount`/`nssf_employee_amount`/`heslb_amount`/`voluntary_deduction_total`/`loan_deduction_total` | NUMERIC(19,4) | NO | DEFAULT 0 — employee-side |
| `nssf_employer_amount`/`wcf_employer_amount`/`sdl_employer_amount` | NUMERIC(19,4) | NO | DEFAULT 0 — employer-side |
| `status` | VARCHAR(10) | NO | `PayrollLineStatus`; DEFAULT `'OK'`; `chk_payroll_line_status CHECK (... IN ('OK','FLAGGED'))` |
| `flag_reason` | VARCHAR(255) | YES | why FLAGGED (net<0, missing rate set) |
| `currency` | VARCHAR(3) | NO | |
| audit cols | | | |
`chk_payroll_line_net_nonneg CHECK (net_amount >= 0 OR status = 'FLAGGED')` — a negative net is only tolerated on a FLAGGED line (which blocks approval, BR-HR-07). Index `ix_payroll_lines_run (payroll_run_id)`, `ix_payroll_lines_company_employee (company_id, employee_id)` (the YTD read, D-12).

#### `payroll_line_items` (child of line — the earning/deduction detail for the payslip + GL component grouping)
`id`/`uid`, `payroll_line_id` FK, `company_id`, `pay_component_id` BIGINT NULL FK → `pay_components(id)` (NULL for a statutory line), `item_kind` VARCHAR(20) (`EARNING|DEDUCTION|EMPLOYER_COST|STATUTORY`), `label` VARCHAR(120), `amount` NUMERIC(19,4), `gl_account_id` BIGINT NULL FK → `chart_of_accounts(id)` (resolved at calc: component account, or the statutory account from `gl_configs`), audit. Drives the payslip detail (D-12) and the per-account GL grouping (D-7).

#### `payroll_statutory_snapshots` (child of line — reproducibility anchor, NFR-HR-02)
`id`/`uid`, `payroll_line_id` FK (`uq_payroll_statutory_snapshot_line UNIQUE (payroll_line_id)` — one per line), `company_id`, `applied_paye_band_set_uid` VARCHAR(26), `applied_nssf_set_uid`/`applied_wcf_set_uid`/`applied_sdl_set_uid`/`applied_heslb_set_uid` VARCHAR(26) NULL, `pay_date` DATE, audit. The durable record of *which* rate sets the line applied (so a future re-render reproduces even after the Administrator adds a newer set).

#### `payslips`
`id`/`uid` (`uq_payslip_uid`; `ScopeGuard case "payslip"`), `company_id`, `payroll_run_id` FK, `payroll_line_id` FK, `employee_id` FK, `payslip_number` VARCHAR(30) (`PAYSLIP-####`; `uq_payslip_company_number`), `pay_date` DATE, the frozen gross/deductions/net + employer-cost figures (denormalised from the line for an immutable document), `ytd_gross`/`ytd_paye`/`ytd_nssf_employee`/`ytd_net` NUMERIC(19,4) (computed at freeze from prior posted lines, D-12), `version` + audit. One payslip per (run, employee), created at POST (D-6 transition). Immutable once written (no `updated_*` semantics beyond audit).

**The run transitions (`PayrollRunService`):**
- **create** → DRAFT, `PAYRUN-####`, captures period + pay date.
- **calculate** → selects ACTIVE employees with an active contract; per employee runs `StatutoryCalculator` (D-5) + recurring items + due loan installment + unpaid-leave pro-rata; writes/replaces `payroll_lines` + `payroll_line_items` + `payroll_statutory_snapshots`; recomputes run roll-ups; flags lines (BR-HR-07); status → CALCULATED. Re-runnable (replaces lines, voids approval).
- **approve** → guard: no FLAGGED line; sets `approved_by/at`; status → APPROVED.
- **post** → emits `PAYROLL.FINALISED` (D-10) in the same TX; status → POSTED; freezes payslips (one per line, with YTD). Idempotent.
- **reverse** → emits `PAYROLL.REVERSED` (reverses `gl_entry_uid`); status → REVERSED; frees the period.
- **disburseNet** → calls Cash & Bank (D-9) to pay net-wages-payable; status → PAID.

### D-7 — The payroll GL posting (the balanced journal — FR-HR-19, BR-HR-06)

`PayrollGlPoster` builds one `JournalEntryDraft` (`sourceType = PAYROLL`, `sourceRef = runUid`, `postingDate = pay_date`, `branchId = run.branch_id`) for the whole run, grouped by GL account:

| leg | side | amount | account (via `gl_configs` key or component mapping) |
|---|---|---|---|
| Salary & allowance expense | **DR** | Σ earning `payroll_line_items` by account | the EARNING components' `gl_account_id` (incl. `SALARY_EXPENSE` for basic) |
| Employer NSSF expense | **DR** | Σ `nssf_employer_amount` | `EMPLOYER_STATUTORY_EXPENSE` (or split keys, D-8) |
| Employer WCF expense | **DR** | Σ `wcf_employer_amount` | `EMPLOYER_STATUTORY_EXPENSE` |
| Employer SDL expense | **DR** | Σ `sdl_employer_amount` | `EMPLOYER_STATUTORY_EXPENSE` |
| PAYE payable | **CR** | Σ `paye_amount` | `PAYE_PAYABLE` |
| NSSF payable | **CR** | Σ (`nssf_employee_amount` + `nssf_employer_amount`) | `NSSF_PAYABLE` |
| WCF payable | **CR** | Σ `wcf_employer_amount` | `WCF_PAYABLE` |
| SDL payable | **CR** | Σ `sdl_employer_amount` | `SDL_PAYABLE` |
| HESLB payable | **CR** | Σ `heslb_amount` | `HESLB_PAYABLE` |
| Loan recovery | **CR** | Σ `loan_deduction_total` | the loan's `gl_account_id` (loan-receivable) |
| Other voluntary deductions | **CR** | Σ voluntary by account | the DEDUCTION components' `gl_account_id` |
| **Net wages payable** | **CR** | Σ `net_amount` | `NET_WAGES_PAYABLE` |

The draft balances by construction: `Σ DR (gross + employer cost) = Σ CR (all payables + net wages)` because `net = gross − (employee deductions)` and every employee deduction is a CR payable, every employer cost is both a DR expense and a CR payable. The GL engine re-validates balance + open period + active accounts (BR-HR-06). A missing required `gl_config` throws → the event parks → finance maps it → replay (the BR-GL-10 stance).

### D-8 — New `GlConfigKey`s + CoA accounts (D-13 GL extension)

**New `GlConfigKey` values** (enum + the `chk_gl_config_key` IN-list widen, V56): `SALARY_EXPENSE`, `EMPLOYER_STATUTORY_EXPENSE`, `PAYE_PAYABLE`, `NSSF_PAYABLE`, `WCF_PAYABLE`, `SDL_PAYABLE`, `HESLB_PAYABLE`, `NET_WAGES_PAYABLE`, `EMPLOYEE_LOAN_RECEIVABLE`. (NHIF adds `NHIF_PAYABLE` later if OQ-HR-07 confirms — additive.)

**New CoA accounts to seed** (the architect's codes, in the shipped TZ numeric ranges; seeded by `HrGlSeeder` for new companies + back-filled for existing in V57):
- `5200` **Salary & Wages Expense** (EXPENSE) → `SALARY_EXPENSE`
- `5210` **Employer Statutory Contributions Expense** (EXPENSE) → `EMPLOYER_STATUTORY_EXPENSE`
- `2300` is taken (`VAT_DUE`); use `2400` **PAYE Payable** (LIABILITY) → `PAYE_PAYABLE`
- `2410` **NSSF Payable** (LIABILITY) → `NSSF_PAYABLE`
- `2420` **WCF Payable** (LIABILITY) → `WCF_PAYABLE`
- `2430` **SDL Payable** (LIABILITY) → `SDL_PAYABLE`
- `2440` **HESLB Payable** (LIABILITY) → `HESLB_PAYABLE`
- `2450` **Net Wages Payable** (LIABILITY) → `NET_WAGES_PAYABLE`
- `1450` **Employee Loans Receivable** (ASSET) → `EMPLOYEE_LOAN_RECEIVABLE`

> The exact codes are confirmed by the architect against the live seeded CoA at build time (the seeder reads existing codes; any clash shifts within-range). The keys are the stable contract; the codes are seed data. `HrGlSeeder` mirrors `ApGlSeeder`/`CashBankSeeder` (per-company seed wired in `BootstrapRunner` + `CompanyService.create`); V57 back-fills existing companies with **#12-safe seed-uids**.

### D-9 — Disbursement: reuse Cash & Bank `CashDirectEntryService` (FR-HR-20, BR-HR-10)

Payroll does **not** re-implement cash posting. To pay net wages, `PayrollRunService.disburseNet(runUid, cashBankAccountUid)` calls **`CashDirectEntryService.recordDirectEntry(...)`** with `direction = OUT`, `amount = net_total`, `counter_gl_account_id = NET_WAGES_PAYABLE account`, the chosen bank account — which posts **DR `NET_WAGES_PAYABLE` / CR the bank account's `gl_account_id`** synchronously and appends a `cash_transactions(txn_type=DIRECT_ENTRY)` row (the GL leg and the cash-book row in one TX, the shipped mechanism). The run → PAID. Each **statutory remittance** (PAYE to TRA, NSSF, WCF, SDL, HESLB) is a separate `recordDirectEntry` (DR the relevant payable / CR bank) on its own statutory deadline, clearing that payable. The disbursement clears the payable exactly (BR-HR-10); over-disbursing a payable is rejected by a service check against the posted payable balance. No new disbursement table — `cash_transactions.source_ref = runUid` is the trace; the run's DTO surfaces the disbursement state by reading Cash & Bank by `source_ref`.

> **Why synchronous, not an event.** Disbursement is an in-request human action (finance clicks "pay"), exactly like an AP payment — the AR/AP → cashbank precedent (ADR-0016 D-7/D-8). Only the *posting of the run journal* is event-driven (it is a system effect of approval that must survive a crash); the disbursement is the operator's command in their TX.

### D-10 — Events: `PAYROLL.FINALISED` + `PAYROLL.REVERSED` + the handlers (the `SalesPostingHandler` precedent)

**New `DomainEventType` constants** (in `platform.events.DomainEventType`): `PAYROLL_FINALISED = "PAYROLL.FINALISED"`, `PAYROLL_REVERSED = "PAYROLL.REVERSED"`, agg `AGG_PAYROLL_RUN = "PAYROLL_RUN"`.

`PayrollRunService.post(...)` publishes `PAYROLL.FINALISED` in its TX:
```
payload = PayrollFinalisedPayload(runUid, companyId, branchId, payDate, runNumber)   // no amounts — handler re-reads, the SaleFinalisedPayload precedent
```
**`PayrollPostingHandler`** (in `hr.events`, mirroring `SalesPostingHandler`) consumes it under `IdempotencyGuard("HR.PAYROLL_POST", event.uid)`, system `RequestContext.Principal` from the event company/branch: re-reads the run + lines + line-items (the monetary facts), builds the `JournalEntryDraft` (D-7) via `PayrollGlPoster`, posts via **`GLPostingSafeInvoker.postInNewTx`** (REQUIRES_NEW, null-on-anomaly — a missing `gl_config`/closed period parks the event, never poisons the dispatch TX), writes back `payroll_runs.gl_entry_uid`, freezes payslips, `markProcessed`. **`PayrollReversalHandler`** consumes `PAYROLL.REVERSED` (`IdempotencyGuard("HR.PAYROLL_REVERSE")`): re-reads `gl_entry_uid`, posts a reversing journal via `GLPostingSafeInvoker.postReversalInNewTx(...)` (`sourceType = PAYROLL`, `reversalOfId = original`).

**Idempotency backstop (DB).** A partial-unique on `journal_entries (company_id, source_type, source_ref) WHERE source_type IN ('PAYROLL')` already follows the GL `uq_journal_entry_sales_source` pattern — but that constraint is GL-owned and would need the GL engine to admit `PAYROLL`. Since GL's `uq_journal_entry_sales_source` is restricted to `SALES`/`SALES_REVERSAL`, the payroll DB backstop is **`payroll_runs.gl_entry_uid` being set + the run status guard** (a POSTED run cannot re-post) plus the `IdempotencyGuard` `processed_events` row — sufficient for the single-poster-per-run shape (BR-HR-09). (If a future audit wants the GL-level partial-unique extended to `PAYROLL`, that is an additive widen under a GL ADR.)

### D-11 — ScopeGuard cases + the self-service own-record rule

New `ScopeGuard.companyIdOf` switch cases (each backed by a `findCompanyIdByUid` on the repo): `department`, `employee`, `employmentcontract`, `paycomponent`, `leavetype`, `leaverequest`, `employeeloan`, `payebandset`, `statutoryrateset`, `payrollrun`, `payslip`. The standard `assertCanActIn` runs on every read/write path (BR-HR-01).

**Self-service own-record (BR-HR-11):** a `PayslipController.myPayslips` / `LeaveController.myBalance` path resolves the caller's `employees.user_id = RequestContext.principal.userId` and returns **only** that employee's records — a dedicated `HR.SELF.VIEW` perm gates it, and the query is pinned to the principal's employee id (never a uid path that could address another employee). The general `HR.PAYSLIP.VIEW` perm (for HR/finance) is separate and scoped by company. An employee principal holds `HR.SELF.VIEW` only.

### D-12 — Permissions (MODULE.RESOURCE.ACTION) + the read queries

New permissions (module `hr`), seeded `ON CONFLICT (code) DO NOTHING` + granted to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN (the V7/V12/V17 pattern):

- `HR.EMPLOYEE.VIEW`, `HR.EMPLOYEE.MANAGE` — employee + contract + department + recurring-item CRUD (salary data behind MANAGE).
- `HR.PAYCOMPONENT.MANAGE` — pay-component catalogue.
- `HR.LEAVE.VIEW`, `HR.LEAVE.MANAGE`, `HR.LEAVE.APPROVE` — leave types/balances, request decisions.
- `HR.LOAN.MANAGE` — employee loans/advances.
- `HR.STATUTORY.MANAGE` — add effective-dated PAYE band sets + statutory rate sets (the sensitive Administrator capability, D-3).
- `HR.PAYROLL.VIEW`, `HR.PAYROLL.RUN` (create/calculate), `HR.PAYROLL.APPROVE`, `HR.PAYROLL.POST`, `HR.PAYROLL.REVERSE`, `HR.PAYROLL.DISBURSE`.
- `HR.PAYSLIP.VIEW` — payslip register (HR/finance, company-scoped).
- `HR.SELF.VIEW` — the employee self-service own-record view (D-11).

`@perm.has("HR.PAYROLL.RUN")` on create/calculate/list; `@perm.scoped(runUid, "payrollrun", "HR.PAYROLL.POST")` on the post path; `@perm.scoped(employeeUid, "employee", "HR.EMPLOYEE.MANAGE")` on employee edits — **never `hasAuthority`** (the `PermissionChecks` bean). `PayslipQuery`/`StatutorySummaryQuery` compute YTD (Σ posted lines for the employee in the calendar year) and the per-run statutory totals (FR-HR-22/23), CSV export via the shipped pattern.

### D-13 — ArchUnit module edges (no cycle)

- **`hr.events` → `gl.service`** (`GLPostingSafeInvoker`/`GLConfigResolver`) — the payroll posting handler. **Allowed** — the `SalesPostingHandler`/`ap.service → gl.service` precedent.
- **`hr.service` → `cashbank.service`** (`CashDirectEntryService`/`CashBankAccountResolver`) — the disbursement. **Allowed** — the AR/AP → cashbank precedent.
- **`hr` → `iam`** (DTO/scalar `user_id` read only) — the self-service link.
- **No edge back into `hr`** from gl/cashbank/iam (HR is a leaf; it publishes a payload its own handler consumes — `hr.events` reading `hr.domain.dto.PayrollFinalisedPayload` is intra-module). **No cycle.**
- The shipped `ModuleBoundaryTest` (controller↛repository, service↛controller, audit-append-only, no module cycles) — none of these edges violates an active rule (they mirror documented allowances).

### D-14 — Numbering (`code_sequence`)

`HrNumberGenerator` reuses the shipped `code_sequence(company_id, entity_kind)` row-locked allocation with new lazily-created `entity_kind` values: `EMPLOYEE` (`EMP-%04d`), `PAYROLL_RUN` (`PAYRUN-%04d`), `PAYSLIP` (`PAYSLIP-%04d`), `EMPLOYEE_LOAN` (`LOAN-%04d`). No new numbering table; no pre-seed (created on first use). Timing: `EMP-####` at employee create, `PAYRUN-####` at run create, `PAYSLIP-####` at run post (one per line), `LOAN-####` at loan create. The `uq_*_company_number` constraints backstop generator bugs.

## Migration plan — V56–V63 (additive on frozen V1–V19; #12-safe seeds)

The HR build is large; it is split across the assigned band so DDL and seeds land in legible, separately-deployable units (each additive; never edits V1–V19):

1. **`V56__hr_gl_keys.sql`** — widen `chk_gl_config_key` to add the nine payroll keys (D-8); widen `chk_journal_batch_source_type` + `chk_journal_entry_source_type` to admit `'PAYROLL'` (the additive `DROP/ADD CONSTRAINT` widen; `JournalSourceType.PAYROLL` already reserved in the enum). No new tables.
2. **`V57__hr_coa_seed.sql`** — INSERT the nine payroll CoA accounts for every existing company (`ON CONFLICT (company_id, account_code) DO NOTHING`) + the nine `gl_configs` mappings (`ON CONFLICT (company_id, config_key) DO NOTHING`). **#12-safe seed-uids:** `'HR' || lpad(company_id::text,6,'0') || substr(md5(<account_code or key>),1,12)` — never `|| code`. (New companies get these from `HrGlSeeder`.)
3. **`V58__hr_employee_master.sql`** — CREATE `departments`, `employees`, `employment_contracts` (+ constraints/indexes, D-4).
4. **`V59__hr_pay_components.sql`** — CREATE `pay_components`, `employee_recurring_items` (D-4).
5. **`V60__hr_statutory.sql`** — CREATE `paye_band_sets`, `paye_bands`, `statutory_rate_sets` (D-3); seed the **default TZ rate sets** for every existing company (PAYE 5-line schedule + NSSF/WCF/SDL/HESLB rows, requirements §9, owner-confirmable). **#12-safe seed-uids.** New companies get these from `HrStatutorySeeder`.
6. **`V61__hr_leave_loans.sql`** — CREATE `leave_types`, `leave_requests`, `leave_balances`, `employee_loans`, `employee_loan_installments` (D-4); seed the default leave types per company (#12-safe).
7. **`V62__hr_payroll_runs.sql`** — CREATE `payroll_runs`, `payroll_lines`, `payroll_line_items`, `payroll_statutory_snapshots`, `payslips` (D-6).
8. **`V63__hr_permissions.sql`** — INSERT the `hr` permissions (D-12) `ON CONFLICT (code) DO NOTHING` + grant all to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (permissions have no `uid` — #12 N/A). `MigrationKeepDataIT` extends to V63.

**No #12 hazard beyond the noted seeds** — every per-company CROSS-JOIN seed-uid (V57 CoA/configs, V60 rate sets, V61 leave types) uses the `md5`-bounded form, never raw-key concat. The `code_sequence` kinds (EMPLOYEE/PAYROLL_RUN/PAYSLIP/EMPLOYEE_LOAN) are lazy, not seeded — no #12 exposure.

## Angular nav routes

Under a new top-level **HR & Payroll** nav section (gated by holding any `HR.*` perm), shipped Angular feature-routing additive:
- `/hr/employees` (+ `/hr/employees/:uid`) — employee + contract + recurring items (`HR.EMPLOYEE.VIEW`).
- `/hr/departments` — department master (`HR.EMPLOYEE.MANAGE`).
- `/hr/pay-components` — pay-component catalogue (`HR.PAYCOMPONENT.MANAGE`).
- `/hr/leave` (requests + balances + approvals) (`HR.LEAVE.*`).
- `/hr/loans` — employee loans/advances (`HR.LOAN.MANAGE`).
- `/hr/statutory` — PAYE band sets + statutory rate sets (`HR.STATUTORY.MANAGE`).
- `/hr/payroll-runs` (+ `/hr/payroll-runs/:uid`) — run lifecycle, calculate/approve/post/reverse/disburse (`HR.PAYROLL.*`).
- `/hr/payslips` — payslip register + statutory summary report (`HR.PAYSLIP.VIEW`).
- `/hr/my-payslips` + `/hr/my-leave` — self-service (`HR.SELF.VIEW`).

## Consequences

**Positive**
- Statutory rules are **data, not code** (D-3) — a budget rate change is a new effective-dated row an Administrator enters; old runs reproduce exactly from their per-line snapshot. The headline NFR is met structurally.
- The financial plumbing is **100% reuse**: posting via `GLPostingService` over the outbox (the `SalesPostingHandler` template), disbursement via the shipped `CashDirectEntryService` (the AR/AP precedent). No new posting or cash mechanism is invented.
- The run journal **balances by construction** (D-7) and is append-only/idempotent (D-10) — payroll cannot un-balance the books or double-post.
- Additive and surgical against frozen V1–V19: nine GL keys + nine CoA accounts, the `PAYROLL` source-type widen, the HR tables, two events + two handlers, the `hr` perms. No frozen migration edited.
- NHIF, attendance, gratuity, extra pay frequencies, cost-centre tagging, and e-filing are all additive seams (a `StatutoryRateType` value, a component, a column, a key) — never a reshape.

**Negative / costs**
- The statutory engine is the correctness-critical surface: the PAYE banded lookup, the pre-tax allowable basis (OQ-HR-03), the pro-rata unpaid-leave reduction, and the rounding boundary (OQ-HR-06) must be tested exhaustively against worked TZ examples; a compute bug mis-pays staff and mis-files with TRA. Tests must assert the seeded TZ schedule against published examples.
- The per-line statutory snapshot (D-4) is a denormalisation that must stay tied to the rate sets it names; the reproducibility guarantee rests on it never being orphaned.
- Eight-migration band (V56–V63) is more files than a single module usually needs; the split is deliberate (legible, separately-deployable) but the engineer must apply them in order (keys → seed → tables → perms).
- The load-bearing OQs (OQ-HR-01 salaried-only, OQ-HR-04 rate authority/values, OQ-HR-07 NHIF) must be owner-confirmed before this ADR is Accepted — the schema is built for either answer, but the seed values and the attendance question affect what ships in v1.

**Neutral / deferred**
- Single pension scheme (NSSF), base TZS, MONTHLY frequency, branch (not cost-centre) cost dimension, statutory **report** (not e-filing files) — all in requirements §2.2 with their activation seams.

## Alternatives considered

- **Statutory rates as code constants / a properties file** vs **effective-dated DB tables.** *Decided: DB tables.* Constants fail reproducibility (an old run can't re-compute under its historical rate) and force a redeploy per budget — the opposite of NFR-HR-01. A properties file is still a deploy and is not effective-dated or audited. DB rate sets + per-line snapshot is the only shape that reproduces.
- **One polymorphic `statutory_rates` table** vs **the typed family (PAYE banded + flat rate sets).** *Decided: typed family.* PAYE's ordered marginal bands with cumulative fixed tax do not fit a single-percentage row; forcing them into JSON loses query-ability and the typed CHECKs. The flat `statutory_rate_sets` handles the single-percentage types cleanly; PAYE gets its proper parent/child. Two small tables vs one lossy one.
- **Payroll posts to GL synchronously (like AP bill-match)** vs **via the outbox (like sales).** *Decided: outbox.* Posting is a system effect of approval that must survive a crash and be idempotent across redelivery — the `SalesPostingHandler` shape. The *disbursement*, by contrast, is an in-request human command and is synchronous (D-9). This mirrors the shipped split (sales posts via outbox, AP pays synchronously).
- **A dedicated `payroll_disbursements` table** vs **reuse `cash_transactions` with `source_ref = runUid`.** *Decided: reuse.* A disbursement is already a first-class `cash_transactions(DIRECT_ENTRY)` row with full audit + GL trace; a parallel table would duplicate it and need its own reconciliation. The run's DTO reads Cash & Bank by `source_ref`.
- **A separate `hr.payroll` module** vs **one `hr` module.** *Decided: one module.* The employee aggregate is shared by HR-master and payroll; splitting forces a cross-module dependency on the employee for every payroll read. One cohesive module, flat under `com.erp.modules.hr`.
- **Net-pay as an AP bill per employee** vs **a `NET_WAGES_PAYABLE` control + a single disbursement.** *Decided: net-wages-payable control.* Per-employee AP bills would flood AP with payroll noise and conflate the supplier sub-ledger with staff pay. A single net-wages-payable control (cleared by the disbursement) keeps payroll out of AP and the books clean.

## Open items (carried from requirements §11; none blocks the *schema*, the load-bearing ones gate ratification)

- **OQ-HR-01 (load-bearing)** — attendance-driven vs salaried-only v1 (assumed salaried + manual unpaid-leave; the schema carries `pay_frequency` + the pro-rata so attendance is additive).
- **OQ-HR-04 (load-bearing)** — exact current TZ statutory values + the rate authority (esp. SDL 3.5% vs 0.5%, HESLB rate/basis, SDL headcount threshold, NSSF ceiling). Seed values are owner-confirmable; the engine is built for any.
- **OQ-HR-07 (load-bearing)** — NHIF in/out (assumed out; additive `StatutoryRateType.NHIF` + `NHIF_PAYABLE` key/account if in).
- **OQ-HR-03** — the PAYE pre-tax allowable set (NSSF EE assumed); config-driven.
- **OQ-HR-06** — statutory rounding boundary (whole-shilling PAYE; net rounding).
- **OQ-HR-09** — cost dimension: branch v1; cost-centre when the dimension framework (PATH §3.11) lands.
- **OQ-HR-10** — single-approver gate v1 (the cross-cutting approvals engine X.5 is not built; the run's APPROVE is a single `HR.PAYROLL.APPROVE` action — when the approvals engine lands, the run approval becomes a consumer of it, additively).
- **OQ-HR-11** — gratuity/end-of-service as a manual final-pay component v1 (full statutory engine deferred).

---

## Summary

ADR-0032 designs **HR & Payroll (Tanzania)** in `com.erp.modules.hr`: an employee/department/contract/pay-component/leave/loan master, a **configurable effective-dated TZ-statutory engine** (`paye_band_sets`+`paye_bands` for the progressive PAYE schedule; a flat `statutory_rate_sets` for NSSF/WCF/SDL/HESLB — all append-only so a posted run reproduces under the rates in force on its pay date), and a `DRAFT→CALCULATED→APPROVED→POSTED→PAID(+REVERSED)` payroll run (header + one `payroll_line` per employee + `payroll_line_items` + a `payroll_statutory_snapshot` per line + frozen `payslips`). The run **posts one balanced journal over the outbox** (`PAYROLL.FINALISED` → `PayrollPostingHandler`, the `SalesPostingHandler` template, via `GLPostingSafeInvoker`, idempotent) — DR salary + employer-statutory expense / CR the statutory payables + loan-receivable + `NET_WAGES_PAYABLE` — and **disburses through the shipped Cash & Bank `CashDirectEntryService`** (DR the payable / CR the bank's GL), clearing each payable. Nine new `GlConfigKey`s + nine CoA accounts, two events + two handlers, eleven new `ScopeGuard` cases, the `hr` perm set, and the Angular HR nav. **Additive on frozen V1–V19** as **V56–V63** (keys+source-type widen → CoA seed → master tables → statutory → leave/loans → run tables → perms), **#12-safe seeds** throughout. **Build effort XL.** **Depends on:** GL + Cash & Bank + IAM + outbox + Money + numbering (all shipped); **optional/soft** cost-centre dimension (not built — v1 tags branch). **Gates:** none. The load-bearing OQs (salaried-only, the statutory values/authority, NHIF) gate moving the ADR from Proposed to Accepted; the *data model* is buildable now because the statutory rules are deliberately rate-table-driven.
