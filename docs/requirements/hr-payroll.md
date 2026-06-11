# Requirements — HR & Payroll (Tanzania) (employees, contracts, leave, a configurable TZ-statutory payroll run that posts to the books and disburses through Cash & Bank)

> Status: **DRAFT (architect-authored placeholder, owner-style assumptions made).** Discovery-first is the
> standing rule; this document is written by the solutions-architect to **unblock ADR-0032** with reasonable
> owner-style assumptions, because the HR/Payroll backlog (PATH-TO-FULL-ERP §3.7) is well-specified at the
> sub-feature level and the TZ statutory rules are externally fixed. **Every assumption is flagged as an
> Open Question (§11);** the load-bearing ones (OQ-HR-01 attendance-driven vs salaried-only v1; OQ-HR-04
> statutory rate authority; OQ-HR-07 NHIF in/out) must be owner-ratified before the ADR is marked Accepted.
> The system-analyst should review and ratify; nothing here invents a business rule the owner has rejected.
>
> Author: solutions-architect (pending system-analyst ratification) · Domain: new module `hr`
> (`com.erp.modules.hr`) with outbound posting touches into **gl** (payroll journal) and **cashbank** (net-pay
> + statutory disbursement). Business-level spec; the data model / API / tables live in **ADR-0032**.
>
> **This is HR & Payroll — PATH-TO-FULL-ERP §3.7 / Phase C extension module.** Tier-1 finance is DONE (GL
> ADR-0013/V10, AR ADR-0014/V11, AP ADR-0015/V12, Cash & Bank ADR-0016/V13, VAT/WHT ADR-0017/V14, Reporting
> ADR-0018/V15, Year-End Close ADR-0019/V16), Inventory Valuation/COGS (ADR-0020/V17) and Sales Orders
> (ADR-0021/V18-V19) ship. Payroll is the next greenfield extension: it has no operational dependency that is
> not already built — it **posts to GL** (the shipped `GLPostingService` + `gl_configs`) and **disburses
> through Cash & Bank** (the shipped `CashDirectEntryService` / `cash_transactions`), exactly the way AP pays
> a bill. The hard part is not the plumbing; it is **modelling the Tanzanian statutory deductions
> configurably** (PAYE progressive bands, NSSF, WCF, SDL, HESLB) so a rate change is data, not a redeploy.
>
> **Depends on:** **GL** (ADR-0013 / V10 — the synchronous `GLPostingService.post(JournalEntryDraft)`,
> `GLConfigResolver.resolve(companyId, GlConfigKey)`, fiscal-period gating, the seeded TZ CoA + `gl_configs`
> mapping; payroll adds new expense / payable accounts + keys); **Cash & Bank** (ADR-0016 / V13 — the
> `CashDirectEntryService.recordDirectEntry(...)` synchronous DR-counter / CR-bank-GL posting + the
> `cash_transactions` row that is the disbursement vehicle for net pay and statutory remittances); **IAM**
> (ADR-0001/0002 — org/company/branch tenancy, `RequestContext`, `ScopeGuard`, RBAC perms, audit, the
> optional `user_id` link for self-service); **Money** (ADR-0005 — base currency TZS, `NUMERIC(19,4)`,
> HALF_UP); **the transactional outbox** (ADR-0009 — `PAYROLL.FINALISED` for the GL posting handler +
> idempotency); **`code_sequence`** numbering (ADR-0007 D-6). All shipped. **Optional / soft dependency:**
> the **cost-centre / dimension framework** (PATH §3.11, NOT built) — payroll cost should ultimately tag the
> cost centre an employee belongs to; v1 tags **branch** (the shipped `journal_lines.branch_id` analysis
> dimension) and reserves a nullable cost-centre seam for when the dimension framework lands (OQ-HR-09).

---

## 1. Business context & why now

The business employs staff and must run a compliant **monthly Tanzanian payroll**: compute each employee's
gross, apply the statutory deductions an employer is legally obliged to withhold and remit (PAYE income tax,
NSSF pension, HESLB loan repayments) plus the employer-borne statutory contributions (NSSF employer match,
WCF, SDL), produce a **payslip** per employee, post the whole run to the general ledger (salary expense, the
several statutory payables, net wages payable) and **pay** the net to employees and the statutory amounts to
TRA / NSSF / WCF / HESLB through the bank. Today none of this exists; payroll is run in a spreadsheet and
journalled by hand, which is error-prone, unauditable, and does not reconcile to the books.

The platform to do it well is already shipped. Payroll is, financially, "AP for staff": a periodic run that
computes amounts, posts a balanced journal, and disburses cash. The genuinely hard, genuinely TZ-specific
part is the **statutory computation** — and Tanzanian rates change (the national budget revises PAYE bands,
NSSF rules, SDL and WCF percentages periodically). So the **non-negotiable design property** is that every
statutory rate, band, threshold, and ceiling is **data in updatable, effective-dated tables**, never a
constant in code. A rate change must be a new effective-dated row an authorised user enters, with old runs
reproducing exactly under the rates that were in force on their pay date.

---

## 2. Scope

### 2.1 In scope (v1)

1. **Employee master & lightweight org structure** — personal + employment data, a `department` master (a
   simple per-company list), employment status lifecycle (ACTIVE / ON_LEAVE / SUSPENDED / TERMINATED), the
   optional link to an IAM `app_user` for self-service.
2. **Employment contracts** — one active contract per employee (history retained), contract type
   (PERMANENT / FIXED_TERM / CASUAL / PROBATION), base salary (monthly gross, base currency), pay frequency
   (MONTHLY in v1), start/end dates, the statutory enrolment flags (NSSF member + number, HESLB borrower +
   number, WCF-covered, SDL-counted, PAYE-resident).
3. **Pay components (earnings & deductions) master** — a configurable per-company catalogue of recurring
   earnings (basic, house allowance, transport, etc.) and voluntary deductions (loan repayment, advance
   recovery, union dues), each mapped to a GL account and flagged taxable / NSSF-able / pensionable. The
   **statutory** deductions are NOT free-text components — they are computed by the statutory engine (§2.1.5)
   from configured rate tables.
4. **Employee recurring pay items** — the per-employee assignment of earnings/deductions (amount or % of
   basic), effective-dated, so the run picks them up.
5. **TZ statutory engine (the hard part) — configurable, effective-dated rate tables:**
   - **PAYE** — progressive monthly bands (tax-free threshold + 4 marginal bands), computed on taxable pay.
   - **NSSF** — employee % + employer % of pensionable pay (10% / 10% assumed default, configurable; no
     ceiling assumed — OQ-HR-05).
   - **WCF** — employer-only % of gross (employer cost, not an employee deduction).
   - **SDL** — employer-only % of gross wage bill, applies when headcount ≥ a configured threshold (employer
     cost).
   - **HESLB** — loan-board repayment, a % of basic for flagged borrowers (configurable; deducted, remitted).
   - Each is a **rate set** with an `effective_from` date; the run resolves the set in force on the pay date.
6. **Leave management** — leave types (ANNUAL / SICK / MATERNITY / PATERNITY / UNPAID / COMPASSIONATE) with a
   per-type configurable annual entitlement + accrual, leave requests with an approval step, a per-employee
   per-type balance, and the effect of UNPAID leave on the run (pro-rata gross reduction). Attendance / time
   clocking is **out** (OQ-HR-01).
7. **Payroll runs** — a per-period (company, month/year) run with a strict lifecycle
   **DRAFT → CALCULATED → APPROVED → POSTED → PAID** (+ REVERSED), processing all active employees in scope,
   producing one **payroll line per employee** with the full earnings/deduction/employer-cost breakdown and a
   per-employee statutory snapshot.
8. **Payslips** — one immutable payslip per employee per posted run, with the gross→deductions→net
   breakdown, employer contributions (informational), and **YTD** figures; visible to the employee via
   self-service.
9. **Payroll → GL posting** — on APPROVE→POST, a balanced journal via `PAYROLL.FINALISED` over the outbox:
   DR salary + allowance expense, DR employer NSSF/WCF/SDL expense, CR each statutory payable, CR net-wages
   payable. Period-gated, idempotent, append-only (correction = a reversal run).
10. **Net-pay + statutory disbursement** — pay the net-wages-payable and each statutory payable through Cash
    & Bank (`CashDirectEntryService`), clearing the payable and crediting the chosen bank account, one
    `cash_transactions` row per disbursement.
11. **Employee loans & advances (light)** — record a loan/advance, an installment schedule, and have the run
    deduct the due installment as a deduction component that credits the loan-receivable account.
12. **Statutory & payslip reports** — a per-run statutory summary (the PAYE / NSSF / WCF / SDL / HESLB totals
    a filing needs) and the payslip register; on-screen + CSV export. The formal TRA/NSSF e-filing file
    formats are **out** (OQ-HR-08).
13. **Self-service (lite)** — an employee (via their linked `app_user`) views their own payslips, leave
    balance, and submits a leave request. No personal-data self-edit beyond a request in v1.
14. **Payroll ledger / history** — runs and payslips are immutable once POSTED; a correction is a **reversal
    run** that reverses the GL and re-runs, never an in-place edit (the GL append-only discipline).

### 2.2 Deferred (explicitly out of v1)

- **Attendance / time & clocking / shift / overtime-from-clock** (v1 is salaried + manual unpaid-leave days;
  OQ-HR-01).
- **NHIF / health-insurance deduction** unless owner confirms it applies (OQ-HR-07; the engine is built so
  it is an additive rate table + key + account if needed).
- **End-of-service / gratuity / severance** computation (recorded as a manual final-pay component in v1;
  full statutory gratuity engine deferred).
- **Multi-currency payroll** (base TZS only — Money base-currency stance).
- **Bonus / 13th-cheque / commission-from-sales** automation (a manual one-off earning component covers ad
  hoc; the sales-commission engine is a separate Sales-depth item).
- **Recruitment / ATS, onboarding workflow, performance & appraisal, training & development** (the HR-suite
  breadth — separate later increments).
- **Formal statutory e-filing files** (TRA P9, NSSF/WCF/HESLB upload formats) — the data + summary report are
  in; the exact file generators are deferred (depend on cross-cutting document/export X.4).
- **Bi-weekly / weekly / daily pay frequencies** (MONTHLY only in v1; the model carries `pay_frequency` so it
  is additive).
- **Cost-centre dimension on the payroll journal** beyond branch (reserved nullable seam; activates when the
  dimension framework lands — OQ-HR-09).
- **Pension-fund choice beyond NSSF** (PSSSF / private schemes) — single NSSF scheme in v1; the rate-set
  model generalises.

---

## 3. Actors

- **HR Officer** — maintains employees, contracts, departments, pay components, recurring items, leave types
  and balances; approves leave (or routes to a manager); initiates a payroll run and reviews the
  calculation.
- **Payroll Approver / Finance Manager** — approves the calculated run (the control gate before it posts),
  authorises posting and the disbursement.
- **Statutory-rate Administrator** — an authorised role (finance/HR senior) who enters new effective-dated
  statutory rate sets (PAYE bands, NSSF/WCF/SDL/HESLB percentages). This is a sensitive, audited capability.
- **Employee (self-service)** — views own payslips + leave balance, submits leave requests; read-only on
  everything else.
- **System (auto-poster)** — the outbox handler that posts the payroll journal to GL on `PAYROLL.FINALISED`.

---

## 4. Functional requirements

### Employee & org
- **FR-HR-01** The system shall maintain an **employee master** per company: employee number (`EMP-####`),
  names, national ID / TIN, NSSF number, HESLB number, date of birth, gender, hire date, department, job
  title, employment status (ACTIVE / ON_LEAVE / SUSPENDED / TERMINATED), and an optional link to an IAM
  `app_user` for self-service.
- **FR-HR-02** The system shall maintain a per-company **department** master (code + name) employees are
  assigned to (the v1 org structure; a hierarchy is deferred).
- **FR-HR-03** The system shall let an HR Officer transition an employee's status; a TERMINATED employee is
  excluded from future runs but retains all history and past payslips.

### Contracts & pay components
- **FR-HR-04** The system shall record an **employment contract** per employee: type, monthly base salary
  (gross, TZS), pay frequency (MONTHLY v1), start date, optional end date, and the statutory enrolment flags
  (PAYE-resident, NSSF member + number, HESLB borrower, WCF-covered, SDL-counted). One contract is **active**
  at a time per employee; superseding a contract retains the prior as history (effective-dated).
- **FR-HR-05** The system shall maintain a per-company **pay-component catalogue**: earnings and voluntary
  deductions, each with a GL account mapping, a taxable flag, an NSSF-able/pensionable flag, and a
  calculation basis (FIXED amount or PERCENT-of-basic).
- **FR-HR-06** The system shall let an HR Officer assign **recurring pay items** to an employee (a component +
  an amount or %), effective-dated, picked up by every run while in force.

### Statutory engine
- **FR-HR-07** The system shall hold the **PAYE band table** as effective-dated rate sets: a tax-free
  threshold and ordered marginal bands (lower bound, rate %, cumulative fixed tax), computed on **monthly
  taxable pay** (gross minus pre-tax allowable deductions per BR-HR-04). The run resolves the set whose
  `effective_from ≤ pay_date` and is the most recent such.
- **FR-HR-08** The system shall hold **NSSF / WCF / SDL / HESLB** as effective-dated rate sets: NSSF employee
  % + employer % (on pensionable pay), WCF employer % (on gross), SDL employer % (on gross, applied when
  in-scope headcount ≥ the configured SDL threshold), HESLB % (on basic, for flagged borrowers).
- **FR-HR-09** The system shall let the **Statutory-rate Administrator** add a new effective-dated rate set;
  rate sets are **append-only and audited** — a correction is a new effective-dated set, never an in-place
  edit of a historical set that has already driven a posted run (BR-HR-08).
- **FR-HR-10** The system shall compute, per employee per run: gross, taxable pay, **PAYE**, **NSSF
  employee**, **HESLB**, voluntary deductions, total deductions, **net pay**, and the employer-borne **NSSF
  employer**, **WCF**, **SDL** (the last as a run-level employer cost), each using the rate set in force on
  the pay date.

### Leave
- **FR-HR-11** The system shall maintain per-company **leave types** with a configurable annual entitlement
  and accrual method, and a per-employee per-type **balance**.
- **FR-HR-12** The system shall let an employee (or HR on their behalf) submit a **leave request**
  (type, from, to, days), route it through an **approval step**, and on approval decrement the balance.
- **FR-HR-13** The system shall apply **UNPAID** leave days as a **pro-rata gross reduction** in the run for
  the pay period the unpaid days fall in (paid leave does not reduce gross).

### Payroll run
- **FR-HR-14** The system shall create a **payroll run** for a (company, period) — period being a calendar
  month mapped to a pay date — that selects all ACTIVE employees with an active contract in scope.
- **FR-HR-15** The system shall **CALCULATE** the run: produce one **payroll line** per employee with the
  full earnings / statutory-deduction / voluntary-deduction / employer-cost breakdown and a per-line
  statutory-rate-set snapshot (which PAYE/NSSF/etc. sets were applied).
- **FR-HR-16** The system shall require a distinct **APPROVE** action by a Payroll Approver before posting (a
  recalculation re-opens the run to CALCULATED, voiding the prior approval).
- **FR-HR-17** The system shall **POST** an approved run to GL as one balanced journal (FR-HR-19), gated to an
  OPEN fiscal period on the pay date, idempotent, append-only; on post the run is **POSTED** and its payslips
  are frozen.
- **FR-HR-18** The system shall support a **REVERSAL run** that reverses a POSTED run's GL entry (a reversing
  journal) and returns the originals to a corrected re-run; no in-place edit of a posted run.

### GL posting & disbursement
- **FR-HR-19** On post, the system shall emit **`PAYROLL.FINALISED`** and an outbox handler shall post a
  balanced journal: **DR** salary + allowance expense (by component GL account), **DR** employer NSSF / WCF /
  SDL expense; **CR** each statutory payable (PAYE-payable, NSSF-payable, WCF-payable, SDL-payable,
  HESLB-payable), **CR** voluntary-deduction targets (e.g. loan-receivable for loan recovery), **CR** net
  wages payable. The journal balances by construction (BR-HR-06).
- **FR-HR-20** The system shall let the Finance Manager **disburse** the net wages payable and each statutory
  payable through **Cash & Bank** (`CashDirectEntryService` — DR the payable / CR the chosen bank account's
  linked GL, one `cash_transactions` row per disbursement), marking the run **PAID** when net wages are
  disbursed. Statutory remittances may be disbursed on their own (later) statutory deadlines.

### Loans, payslips, reports, self-service
- **FR-HR-21** The system shall record an **employee loan / advance** with an installment schedule, and the
  run shall deduct the due installment (a deduction crediting the loan-receivable account), tracking the
  outstanding balance.
- **FR-HR-22** The system shall produce an immutable **payslip** per employee per posted run with the
  gross→net breakdown, employer-contribution info, and **YTD** figures, visible via self-service.
- **FR-HR-23** The system shall produce a per-run **statutory summary report** (PAYE / NSSF employee+employer
  / WCF / SDL / HESLB totals) and a **payslip register**; on-screen + CSV export.
- **FR-HR-24** The system shall let an **employee** view their own payslips and leave balance and submit a
  leave request via their linked `app_user` (self-service; read-only otherwise).

---

## 5. Business rules

- **BR-HR-01** Every HR/payroll entity is **company-scoped** (`company_id`); reads enforce `ScopeGuard`
  (`assertCanActIn`) on every path. An employee may carry an analysis **branch** (their posting branch).
- **BR-HR-02** An employee has **at most one ACTIVE contract** at a time; superseding retains history.
- **BR-HR-03** A payroll run is **unique per (company, period)** — one run per company per month (a
  re-run is a reversal + new run, not a second concurrent run for the same period).
- **BR-HR-04** **PAYE is computed on taxable pay** = gross earnings minus the statutorily allowable pre-tax
  deductions (NSSF employee contribution is the assumed pre-tax allowable deduction; the engine takes the
  allowable set from config — OQ-HR-03). PAYE uses the progressive band set in force on the pay date.
- **BR-HR-05** **Employer contributions (NSSF employer, WCF, SDL) are employer costs**, not employee
  deductions — they increase salary expense and a payable, never reduce net pay.
- **BR-HR-06** A posted payroll journal must **balance** (Σ DR = Σ CR) and is **append-only** — the GL engine
  rejects an unbalanced or closed-period post; a correction is a reversal run (the GL discipline, BR-GL-01/02).
- **BR-HR-07** Net pay must be **≥ 0**; if computed deductions would exceed gross, the run **flags** the line
  and blocks approval until resolved (a deduction cap / employee query) — it never posts a negative net.
- **BR-HR-08** Statutory rate sets are **effective-dated and append-only**: a posted run is reproducible
  under the rates in force on its pay date; you never mutate a historical set.
- **BR-HR-09** Posting is **idempotent** — `PAYROLL.FINALISED` carries the run uid; the handler dedups via
  `IdempotencyGuard` and a DB partial-unique backstop on (company, source_type, source_ref) so a redelivered
  event cannot double-post.
- **BR-HR-10** A disbursement (net or statutory) **clears its payable exactly** — the DR to the payable
  equals the bank CR; over-disbursing a payable is rejected.
- **BR-HR-11** Personal employee data is **sensitive**: salary, payslips, and personal fields are gated by
  dedicated perms; an employee self-service principal sees **only their own** records.
- **BR-HR-12** All amounts are **base currency (TZS), `NUMERIC(19,4)`, HALF_UP**; statutory rounding follows
  the TRA convention (whole-shilling PAYE) applied at the documented boundary (OQ-HR-06).

---

## 6. Key flows

### 6.1 Happy path — monthly payroll run
1. HR Officer opens the **payroll run** for the month (DRAFT); the system selects active employees with
   active contracts.
2. HR **CALCULATES**: per employee, gross = basic + recurring earnings (− unpaid-leave pro-rata); taxable pay
   = gross − allowable pre-tax (NSSF EE); PAYE from the in-force band set; NSSF EE/ER, WCF, SDL, HESLB from
   the in-force rate sets; voluntary deductions + loan installments; net = gross − total deductions. One
   payroll line per employee + a per-line rate snapshot. Run → **CALCULATED**.
3. Payroll Approver reviews the register, **APPROVES**. Run → **APPROVED**.
4. Finance **POSTS**: `PAYROLL.FINALISED` emitted; the handler posts the balanced journal (DR expense / CR
   the payables + net-wages payable), period-gated, idempotent. Run → **POSTED**, payslips frozen.
5. Finance **DISBURSES** net wages via Cash & Bank (DR net-wages-payable / CR bank); run → **PAID**.
   Statutory payables are disbursed (now or by their deadlines) the same way, clearing each payable.
6. Employees view payslips + YTD via self-service.

### 6.2 Unhappy paths
- **A statutory rate changed mid-quarter.** The Administrator enters a new effective-dated set; runs before
  its `effective_from` use the old set, runs on/after use the new — automatically, no code change.
- **Computed net is negative for an employee** (deductions > gross). The line is flagged; APPROVE is blocked
  until HR resolves (cap the deduction / defer a loan installment). The run does not post a negative net.
- **A posted run had a wrong input** (a mis-keyed allowance). Finance runs a **REVERSAL** (reversing journal,
  same period or current open period), corrects the input, re-runs. No posted figure is edited in place.
- **Post lands in a closed period.** The GL engine rejects it; the run stays APPROVED until an open period is
  selected (strict policy, the GL stance).
- **A required `gl_config` (e.g. NSSF-payable) is unmapped.** The post fails / parks; finance maps the
  account; the parked `PAYROLL.FINALISED` replays — no silent post to a null account.
- **A leave request overdraws the balance.** The request is rejected (or flagged for unpaid-leave conversion).

---

## 7. Non-functional requirements

- **NFR-HR-01** **Statutory configurability** — every PAYE band, threshold, and statutory % is an
  effective-dated data row; a rate change is a new row, never a redeploy. (The headline NFR.)
- **NFR-HR-02** **Reproducibility / auditability** — a posted run reproduces its figures exactly from its
  per-line rate snapshot; every rate change, approval, post, reversal, and disbursement is audited.
- **NFR-HR-03** **Append-only finance** — posted runs, payslips, and the GL entries are immutable;
  corrections are reversals (the platform discipline).
- **NFR-HR-04** **Idempotent posting** — at-least-once outbox delivery + consumer idempotency + a DB
  backstop; no double-post.
- **NFR-HR-05** **Tenant isolation + least-privilege** — company-scoped, `ScopeGuard` on every read; salary
  and payslip data behind dedicated perms; self-service strictly own-record.
- **NFR-HR-06** **Scale** — designed for hundreds of employees per company per run (a single-instance batch
  CALCULATE is acceptable; the line grain + indexes support the run + YTD + statutory-summary reads).
- **NFR-HR-07** **Additive migrations** — V1–V19 frozen; HR lands as additive migrations (V56–V63 band),
  FKing only frozen platform tables + intra-module HR tables, referencing GL/Cash by scalar uid.
- **NFR-HR-08** **Extensibility** — attendance, NHIF, gratuity, extra pay frequencies, cost-centre tagging,
  e-filing formats are all additive (a component / rate table / key / column), never a reshape.

---

## 8. Accepted v1 boundary (what we are deliberately NOT doing)

Salaried monthly payroll only (no clocking/attendance); NSSF as the single pension scheme; NHIF gated on
owner confirmation; gratuity as a manual final-pay component; base TZS only; statutory **data + summary
report** but not the formal e-filing file formats; branch (not cost-centre) as the cost analysis dimension;
self-service read + leave-request only. Each is in §2.2 with its activation seam.

---

## 9. Assumptions taken (owner-style, to be confirmed)

- TZ statutory **defaults to seed** (current public rates, 2025/26 — all configurable, all owner-confirmable):
  - **PAYE** monthly: 0 up to 270,000 → 0%; 270,001–520,000 → 8% of excess over 270,000; 520,001–760,000 →
    20,000 + 20% of excess over 520,000; 760,001–1,000,000 → 68,000 + 25% of excess over 760,000; over
    1,000,000 → 128,000 + 30% of excess over 1,000,000.
  - **NSSF**: employee 10% + employer 10% of pensionable pay, no ceiling.
  - **WCF**: employer 0.5% of gross (private sector).
  - **SDL**: employer 3.5% of gross wage bill, when in-scope headcount ≥ 10 (the SDL threshold; sources
    diverge between 0.5% historic and 3.5% current — seed 3.5%, **flag for owner confirmation**, OQ-HR-04).
  - **HESLB**: 15% of basic for flagged borrowers (configurable; confirm rate + basis — OQ-HR-04).
- One pay run per company per month; pay date = a configured day (default last day of month).
- NSSF employee contribution is the pre-tax allowable deduction for PAYE (OQ-HR-03).
- New CoA accounts to seed (codes are the architect's in ADR-0032; ranges per the shipped TZ CoA):
  salary/wages expense (5xxx), employer-statutory expense (5xxx), and the statutory payables + net-wages
  payable + employee-loan receivable (2xxx / 1xxx).

---

## 10. User stories (indicative)

- **US-HR-01** As an HR Officer I register an employee and their contract so they are picked up by payroll.
- **US-HR-02** As a Statutory-rate Administrator I enter the new PAYE bands effective 1 July so runs from
  then apply them automatically while June runs keep the old bands.
- **US-HR-03** As an HR Officer I calculate the monthly run and see each employee's gross→net with PAYE/NSSF.
- **US-HR-04** As a Payroll Approver I review and approve the run before it can post.
- **US-HR-05** As a Finance Manager I post the approved run to the ledger and disburse net pay via the bank.
- **US-HR-06** As a Finance Manager I disburse PAYE/NSSF/WCF/SDL/HESLB to the authorities, clearing each
  payable.
- **US-HR-07** As an employee I view my payslip and YTD and submit an annual-leave request.
- **US-HR-08** As a Finance Manager I reverse a mis-run payroll and re-run it correctly without editing the
  posted figures.

---

## 11. Open questions (the load-bearing ones gate ADR ratification)

- **OQ-HR-01 (load-bearing)** — **Attendance-driven vs salaried-only v1?** Assumed **salaried monthly +
  manual unpaid-leave days** (no clocking). If the business pays hourly/overtime from a clock, attendance is
  in scope and changes gross computation. *Recommend salaried-only v1; attendance is an additive later
  module that feeds gross.*
- **OQ-HR-02** — **Pay frequency** — MONTHLY only assumed. Confirm no weekly/bi-weekly need in v1.
- **OQ-HR-03** — **PAYE taxable-pay basis** — which deductions are pre-tax allowable (NSSF EE assumed; is any
  pension/insurance also allowable?). The engine takes the allowable set from config; confirm the set.
- **OQ-HR-04 (load-bearing)** — **Statutory rate authority + exact current rates** — confirm the seed values
  (esp. **SDL 3.5% vs 0.5%**, the **HESLB rate/basis**, the **SDL headcount threshold**, NSSF ceiling). These
  are seeded defaults the Administrator can change, but the v1 seed should be correct.
- **OQ-HR-05** — **NSSF ceiling** — assumed none; confirm whether a contribution ceiling applies.
- **OQ-HR-06** — **Statutory rounding** — PAYE to the whole shilling at which boundary; net rounding
  convention. Presentation + a documented compute boundary.
- **OQ-HR-07 (load-bearing)** — **NHIF / health insurance in scope?** Assumed **out** unless the business
  operates an NHIF deduction. If in, it is an additive rate table + key + payable account.
- **OQ-HR-08** — **Statutory e-filing formats** — the TRA/NSSF/WCF/HESLB upload file formats are deferred;
  confirm the v1 report (CSV summary) is acceptable until the document/export enabler lands.
- **OQ-HR-09** — **Cost analysis dimension** — v1 tags **branch**; the cost-centre/department posting
  dimension waits for the dimension framework (PATH §3.11). Confirm branch is sufficient for v1.
- **OQ-HR-10** — **Approval routing** — leave + run approval are assumed a single explicit approver action
  (no multi-step engine yet); confirm a simple single-approver gate is sufficient v1 (the cross-cutting
  approvals engine X.5 is not built).
- **OQ-HR-11** — **End-of-service / gratuity** — assumed a manual final-pay component v1; confirm no
  automated statutory gratuity computation is required for go-live.

---

## Sources (TZ statutory grounding for §9 seed defaults)

- [Tanzania Tax Guide 2025/2026 — Habib Advisory](https://habibadvisory.co.tz/resources/guides/2025-2026-Tax-Guide.pdf)
- [Tanzania - Individual - Other taxes — PwC Worldwide Tax Summaries](https://taxsummaries.pwc.com/tanzania/individual/other-taxes)
- [Tanzania Tax Rates & Payroll Compliance (2026) — Zatra](https://www.zatra.co/post/tanzania-tax-rates-payroll-compliance-2026-complete-guide-for-businesses-investors-employers)
- [Tax Rates — Aren Software (Tanzania payroll)](https://www.aren.biz/tanzania/payroll/taxrates.htm)
