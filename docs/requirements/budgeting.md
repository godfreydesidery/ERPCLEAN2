# Requirements — Budgeting & Management Accounting (cost centres + budgets + versions/approval + budget-vs-actual variance)

> Status: **DRAFT (owner-style assumptions made; flag the load-bearing OQs before ratification).** This is the
> business-level specification for the **Budgeting & Management Accounting** module (docs/PATH-TO-FULL-ERP.md
> area 14 / §3.11, T3.6). It scopes the v1 increment: a **cost-centre dimension framework** on GL, **budgets**
> by account × cost-centre × fiscal period, **budget versions with a submit/approve lifecycle**, and
> **budget-vs-actual variance reporting** where actuals come from GL `journal_lines` tagged by cost-centre.
>
> Author: system-analyst (this doc) · Domain: new module `com.erp.modules.budgeting` plus one **additive,
> load-bearing** GL change — a nullable `cost_centre_id` analysis tag on `journal_lines` (mirroring the
> existing nullable `branch_id` analysis tag, ADR-0013 D-7). Business-level spec only. **No schema, no API
> shapes, no tables/columns, no code** — those are the solutions-architect's, in **ADR-0034** (next step). Do
> not infer a data model from this document.
>
> **This is Budgeting & Management Accounting — Phase D (docs/PATH-TO-FULL-ERP.md §5).** GL (ADR-0013 / V10)
> is the **critical-path gate** and is shipped; it is **dimension-ready** — `journal_lines.branch_id` is
> already a nullable analysis tag (ADR-0013 D-7) — but the **cost-centre / dimension framework is NOT built**.
> That framework is this module's **build-first enabler** (PATH-TO-FULL-ERP §4 item 4, §3.11 "Build-first
> enabler"): without a cost-centre dimension on GL lines, actuals cannot be grouped by centre and
> budget-vs-actual is impossible. This module builds the dimension and the budgeting that hangs off it.
>
> **Depends on:** **GL** (ADR-0013 / V10: `chart_of_accounts` — the budget is *per account*; `fiscal_years` +
> `fiscal_periods` — the budget is *per period*, and variance reads actuals *per period*; `journal_lines`
> (`company_id`, nullable `branch_id`, `account_id`, `debit_amount`/`credit_amount`) — the actuals source;
> `account_type` ∈ {ASSET, LIABILITY, EQUITY, INCOME, EXPENSE} as the placement authority; the
> `GLPostingService` and the trial-balance/period-movement read patterns the variance query mirrors);
> **Reporting** (ADR-0018 / V15: the `IncomeStatementBuilder` / `journal_lines GROUP BY account` read pattern —
> the variance report is a sibling read over the same lines, now also grouped by `cost_centre_id`); **IAM /
> RBAC** (`@perm.has` / `@perm.scoped`, `ScopeGuard.assertCanActIn`, audit); **`code_sequence`** (ADR-0007 —
> numbering for `BUDGET-####` and `CC-####`); **Money** (ADR-0005 — base currency only, NUMERIC(19,4),
> HALF_UP). All shipped. **Latest migration is V19; Budgeting uses V69–V73** (additive on the frozen V1–V19;
> the V20–V68 range is reserved for other in-flight modules the coordinator is sequencing — Budgeting claims
> V69–V73 to avoid collisions).
>
> **Gating:** depends on the **cost-centre dimension** (built here as the enabler). Gates **nothing** — no
> downstream module is blocked waiting on Budgeting. (Procurement encumbrance / commitment accounting and
> management dashboards *read* budgets later, but are not in this v1 and do not gate it.)

---

## 1. Business context & why now

The system keeps books at **company level** (ADR-0013 NFR-GL-01): every GL line is `company_id`-scoped, and
`branch_id` rides along as an optional **analysis tag** so a branch P&L is `journal_lines` grouped by branch.
That is the *first* analytical dimension. What the business cannot do today is **plan and control by cost
centre**: set an expected spend/revenue for a department or cost centre over a period, then measure the
**actual** against it and report the **variance**. There is no budget anywhere in the system; there is no
cost-centre dimension to budget *against*; and there is no budget-vs-actual report — the cornerstone of
management accounting.

Three things are missing, and they nest:

1. **A cost-centre dimension** — a master list of cost centres (departments, divisions, cost pools) per
   company, and the ability to **tag a GL line with the cost centre it belongs to** (the same way `branch_id`
   tags it today). Without this, "actuals by cost centre" cannot be computed. This is the **enabler**.
2. **Budgets** — for a given fiscal year, an amount **expected per account, per cost centre, per period**
   (e.g. "Salaries (5300) for the Sales department in March = TZS 12,000,000"). A budget is a *plan*, not a
   posting: it touches **no GL, no stock, no cash** — it is reference data the variance report reads.
3. **Budget versions + an approval lifecycle** — a budget is not authoritative until it is **approved**. A
   finance user drafts a version, submits it, an approver approves (or rejects) it; the **approved** version
   is the one variance reports against. Re-planning produces a **new version** (the old one is retained for
   audit and comparison), never an in-place edit of an approved budget.

With these, management can answer: *"How is the Sales department tracking against its approved budget for Q1,
account by account?"* — the budget-vs-actual variance report, the headline deliverable.

**Why now:** GL/AR/AP/Cash/VAT/Reporting/Year-End all ship; the **financial core is complete and the books
balance**. Operational depth (Sales O2C, inventory valuation/COGS) ships. The natural next controlling layer
is **management accounting** — and it is unblocked the moment the cost-centre dimension exists. This increment
builds that dimension and the budgeting on top of it.

---

## 2. Scope

### 2.1 In scope (v1)

- **Cost-centre master** (`CC-####`): create / edit / deactivate cost centres per company; code + name +
  optional type (e.g. DEPARTMENT / DIVISION / PROJECT / LOCATION / OTHER) + optional parent (a shallow
  grouping tree, reserved like GL `parent_id` but usable for roll-up); `MasterStatus` soft-delete; an inactive
  cost centre cannot be chosen on a *new* tagging or a *new* budget line but stays on historical lines/budgets
  and in reports.
- **GL line cost-centre tagging** — a **nullable `cost_centre_id` analysis tag on `journal_lines`** (the
  load-bearing enabler), populated at post time **when the posting source carries a cost-centre** (manual
  journals may set it on each line; automatic posters pass through any cost-centre on their source document if
  one exists — see §6). Untagged lines (`cost_centre_id IS NULL`) are the norm for existing and most automatic
  postings and roll up under an **"Unallocated"** bucket in by-centre reports. **No backfill** of historical
  lines — they remain NULL/Unallocated.
- **Manual journal cost-centre entry** — the manual journal post path (GL.POST) gains an **optional
  per-line cost centre** so finance can tag accruals/adjustments to a centre.
- **Budgets** — a **budget** is scoped to (company, fiscal year, cost centre) [or company-wide when no centre
  is chosen] and carries **budget lines**, each = (account, fiscal period, amount). v1 supports the **12
  monthly periods** of the fiscal year (the shipped fiscal calendar). A budget line amount is a plain
  base-currency figure (no debit/credit — the account_type's normal balance gives the sign for variance).
- **Budget entry methods** — (a) **per-period amounts** entered directly; (b) an **annual amount with even
  spread** across the 12 periods (a convenience; the engine divides and HALF_UP-rounds with the remainder on
  the last period); (c) **seed from a prior version** (copy an existing version's lines as a starting point).
  All three produce the same per-period line set.
- **Budget versions + approval lifecycle** — a budget has one-or-more **versions**; each version has a
  lifecycle **DRAFT → SUBMITTED → APPROVED** (and **REJECTED**, and **SUPERSEDED**). Exactly **one APPROVED
  version per (company, fiscal year, cost centre)** is the **active** version variance reports against;
  approving a new version **supersedes** the previously approved one (retained, not deleted). A DRAFT/SUBMITTED
  version's lines are editable; an APPROVED/REJECTED/SUPERSEDED version is **immutable** (re-plan = new
  version). The submit/approve transitions are permission-gated, audited, append-only state changes.
- **Budget-vs-actual variance report** — for a chosen (fiscal year, period-range, [cost centre], [account
  range]), produce per (account [× cost centre]): **budget** (from the approved version), **actual** (from GL
  `journal_lines` for the period-range, filtered by `cost_centre_id` when a centre is chosen, signed by the
  account's normal balance), **variance** (actual − budget, or budget − actual per account-type convention),
  and **variance %**. Comparable to the shipped P&L read (it is the same `journal_lines` aggregate with the
  budget joined in). Read-only; scoped + RBAC-gated; CSV export reusing the shipped reporting exporter
  (PDF/Excel deferred unless trivial via the existing renderer).
- **Departmental / cost-centre actuals view** — a thin read: GL actuals grouped by cost centre × account
  (no budget) for a period-range, so a centre's spend/revenue can be seen even before a budget exists.
- **Permissions / numbering / scope** — `BUDGETING.*` permission family; `code_sequence` kinds for
  `COST_CENTRE` and `BUDGET`; per-company isolation; `assertCanActIn` on every read; audit on every
  cost-centre lifecycle change, budget create/edit, and version state transition.

### 2.2 Deferred (explicitly NOT in v1)

- **Profit centres** as a distinct concept (revenue+expense roll-up by centre with its own P&L) — v1's
  cost-centre dimension *enables* a departmental P&L read, but a formal profit-centre object/report is
  deferred (PATH §3.11).
- **Forecasting / rolling forecast** (PATH §3.11) — budgets are static per fiscal year in v1.
- **Commitment / encumbrance accounting** (PO commitments vs budget) — reads `po_lines`; ties to procurement
  encumbrance; deferred (PATH §3.11, §3.4).
- **Allocations / cost distribution** (overhead spread across centres, posting GL `source_type=ALLOCATION`) —
  deferred (PATH §3.11). v1 budgets and tags; it does not *redistribute* posted cost between centres.
- **What-if / scenario modelling** beyond budget versions — deferred (PATH §3.11).
- **Management dashboards / KPI visualisations** (contribution margin charts, drill-through dashboards) —
  deferred to Reporting depth (PATH §3.2 / §3.11).
- **Multi-dimensional tagging** beyond cost centre (project / department-as-separate-dim) — the framework is
  built for **one** new dimension (cost centre); a second dimension (project, ADR §3.10) is additive later,
  not now. v1 = branch (existing) + cost centre (new).
- **Weekly / 13-period / non-monthly budget calendars** — v1 = the shipped 12 monthly periods.
- **Multi-currency budgets** — base currency only (Money ADR-0005), consistent with GL.
- **Budget at branch granularity as a separate axis** — branch is already a GL tag; v1 budgets by cost
  centre. A branch×cost-centre budget matrix is a deferred refinement.
- **Hard budget *enforcement*** (blocking a posting/PO that would exceed budget) — v1 is **plan + measure**,
  not **control-by-blocking**. Budget is reference data; nothing is rejected for being over budget.
- **A generic cross-module approvals engine** (PATH §3.12 X.5) — **not built; not assumed built.** Budget
  approval is a **self-contained lifecycle inside this module** (DRAFT→SUBMITTED→APPROVED/REJECTED), designed
  so it can be re-pointed at a shared approvals engine later if/when X.5 ships (OQ-BUD-05).

### 2.3 Accepted v1 boundaries (consequences of the deferrals)

- A budget does **not** affect any posting, stock, or cash flow — it is **measure-only** reference data
  (BR-BUD-01). Going over budget produces a **variance**, never a **block**.
- Cost-centre tagging on automatic GL postings is **best-effort pass-through**: only postings whose source
  document carries a cost centre will tag the GL line; the rest are **Unallocated** (NULL). v1 does not
  retrofit cost centres onto sales/purchase/cash documents — only the **manual journal** path gains an
  explicit per-line cost centre (BR-BUD-08). (Wiring cost centres onto operational documents is additive,
  per-document, later.)
- Exactly **one approved budget version is authoritative** per (company, FY, cost centre); variance always
  reports against it. With **no** approved version, variance shows budget = 0 (everything is variance) and
  flags "no approved budget" (BR-BUD-12).

---

## 3. Actors

- **Budget Owner / Finance Analyst** — creates cost centres, drafts budgets, enters/edits budget-version
  lines, submits a version for approval (`BUDGETING.BUDGET.MANAGE`, `BUDGETING.BUDGET.SUBMIT`,
  `BUDGETING.COSTCENTRE.MANAGE`).
- **Budget Approver / Finance Manager** — approves or rejects a submitted version
  (`BUDGETING.BUDGET.APPROVE`). Segregation of duties is a *policy* the org configures via roles (the same
  role can hold both perms if the org chooses — v1 does not hard-enforce maker≠checker; see OQ-BUD-04).
- **Management / Controller** — reads budget-vs-actual variance + departmental actuals
  (`BUDGETING.REPORT.VIEW`).
- **Manual-journal poster (existing GL.POST user)** — optionally tags journal lines with a cost centre when
  posting (no new perm; rides `GL.POST`).
- **System** — no automatic budget posting; the only system involvement is automatic GL posters passing
  through a cost centre when their source document already carries one (pass-through only in v1).

---

## 4. Functional requirements (FR-BUD)

### Cost-centre dimension (the enabler)

- **FR-BUD-01** — A user with `BUDGETING.COSTCENTRE.MANAGE` can **create a cost centre** for the active
  company: a unique-per-company **code**, a **name**, an optional **type** (DEPARTMENT / DIVISION / PROJECT /
  LOCATION / OTHER), and an optional **parent cost centre** (shallow grouping). The system allocates a stable
  external `CC-####` identifier.
- **FR-BUD-02** — A user with `BUDGETING.COSTCENTRE.MANAGE` can **edit** a cost centre's name/type/parent and
  **deactivate** (soft-delete) it. A deactivated cost centre is excluded from **new** tagging and **new**
  budget lines but remains on historical journal lines and budgets and in reports (BR-BUD-05).
- **FR-BUD-03** — A user with `BUDGETING.COSTCENTRE.VIEW` can **list and view** cost centres for the active
  company, filtered by status/type, paginated.
- **FR-BUD-04** — The system carries a **nullable cost-centre tag on each GL journal line**. A journal line
  may be tagged with one cost centre or none (Unallocated). This tag is **analysis only** — it does not change
  the double-entry, the account, the amount, or the period; it is the dimension the by-centre reports group on
  (BR-BUD-07).

### Manual-journal tagging

- **FR-BUD-05** — When posting a **manual journal** (existing `GL.POST` path), the poster may set an **optional
  cost centre per line**. The chosen cost centre must be active and belong to the same company. An unset line
  posts Unallocated (BR-BUD-08).

### Budgets

- **FR-BUD-06** — A user with `BUDGETING.BUDGET.MANAGE` can **create a budget** for the active company scoped
  to a **fiscal year** and **optionally a cost centre** (company-wide budget when no centre is chosen). The
  system allocates a `BUDGET-####` identifier and opens an initial **DRAFT version**.
- **FR-BUD-07** — A user with `BUDGETING.BUDGET.MANAGE` can **enter/edit budget lines** on a **DRAFT or
  SUBMITTED** version: each line = (**account**, **fiscal period**, **amount**). The account must be active and
  in the budget's company; the period must belong to the budget's fiscal year. Amount is a non-negative
  base-currency figure (BR-BUD-09, BR-BUD-10).
- **FR-BUD-08** — Three entry methods are supported (all yielding the same per-period line set, §2.1):
  per-period direct entry; **annual amount with even spread** across the 12 periods; **seed from an existing
  version** (copy its lines).
- **FR-BUD-09** — A user with `BUDGETING.BUDGET.VIEW` can **list and view** budgets and their versions for the
  active company, filtered by fiscal year / cost centre / version status, paginated; and can see, per budget,
  **which version is the active (approved) one**.

### Budget versions + approval lifecycle

- **FR-BUD-10** — A budget has **one or more versions**, each carrying a version label/number and a status in
  **{DRAFT, SUBMITTED, APPROVED, REJECTED, SUPERSEDED}**. A new budget opens with **version 1 = DRAFT**.
- **FR-BUD-11** — A user with `BUDGETING.BUDGET.SUBMIT` can **submit** a DRAFT version → SUBMITTED (locking its
  lines from further edit). Submission is rejected if the version has **no lines** (BR-BUD-11).
- **FR-BUD-12** — A user with `BUDGETING.BUDGET.APPROVE` can **approve** a SUBMITTED version → APPROVED, or
  **reject** it → REJECTED (with a reason). Approving **supersedes** any previously APPROVED version for the
  same (company, fiscal year, cost centre) → that prior version becomes SUPERSEDED (retained). The approve and
  reject actions are audited with actor + timestamp + reason (BR-BUD-12, BR-BUD-13).
- **FR-BUD-13** — A user with `BUDGETING.BUDGET.MANAGE` can **create a new version** of an existing budget,
  optionally **seeding** it from any existing version (FR-BUD-08c), to re-plan. The new version opens DRAFT.
  APPROVED / REJECTED / SUPERSEDED versions are **immutable** (BR-BUD-06).
- **FR-BUD-14** — A user with `BUDGETING.BUDGET.MANAGE` can **recall** a SUBMITTED version back to DRAFT (the
  submitter or an approver before approval), to correct it. Recall is audited. (A REJECTED version cannot be
  recalled — re-plan = new version.)

### Budget-vs-actual variance + departmental actuals

- **FR-BUD-15** — A user with `BUDGETING.REPORT.VIEW` can run a **budget-vs-actual variance report** for the
  active company for a chosen **fiscal year**, **period range** (one period, a quarter, or the full year),
  optional **cost centre**, and optional **account range / account type**. Output rows = per account [× cost
  centre]: **budget amount** (Σ approved-version lines in the period range), **actual amount** (Σ GL
  `journal_lines` in the period range for that account, filtered by cost centre when chosen, signed by the
  account's normal balance), **variance**, and **variance %** (BR-BUD-14, BR-BUD-15).
- **FR-BUD-16** — The variance report shows, when **no approved budget version** exists for the chosen scope,
  budget = 0 for all rows and a clear **"no approved budget"** indicator (so the report is never silently
  wrong) (BR-BUD-12).
- **FR-BUD-17** — A user with `BUDGETING.REPORT.VIEW` can run a **departmental actuals view**: GL actuals
  grouped by **cost centre × account** for a period range (no budget join) — the by-centre P&L slice.
- **FR-BUD-18** — The variance report and the departmental view are **exportable to CSV** reusing the shipped
  reporting export path (PDF/Excel only if the existing renderer supports it without new work) (BR-BUD-16).

### Cross-cutting

- **FR-BUD-19** — Every cost-centre lifecycle change, budget create/edit, budget-line change, and version
  state transition is **audited** (actor, company, timestamp, before/after where applicable).
- **FR-BUD-20** — All reads (cost-centre list, budget list/detail, variance, departmental actuals) are
  **company-scoped** via `RequestContext` + `assertCanActIn`; a caller may never see another company's cost
  centres, budgets, or actuals.

---

## 5. Business rules (BR-BUD)

- **BR-BUD-01** — A budget is **reference data only**: it posts **no GL, moves no stock, touches no cash**.
  Over-budget produces a variance, never a block (v1 is measure-only; enforcement deferred §2.2).
- **BR-BUD-02** — Cost-centre **code is unique per company**. `CC-####` is the stable external identifier;
  the code is the human key.
- **BR-BUD-03** — A budget is scoped to exactly one **(company, fiscal year)**, optionally one **cost centre**
  (company-wide when none). The fiscal year must exist for the company (shipped fiscal calendar).
- **BR-BUD-04** — A budget line is keyed by **(version, account, fiscal period)** — at most one amount per
  account per period per version. The account belongs to the budget's company; the period belongs to the
  budget's fiscal year.
- **BR-BUD-05** — A cost centre with **postings or budget lines referencing it cannot be deleted** — it is
  **deactivated** (soft-delete, `MasterStatus`); a no-reference cost centre may be hard-deleted (the GL
  `chart_of_accounts` precedent, ADR-0013 BR-GL-07).
- **BR-BUD-06** — A budget **version's lines are editable only while DRAFT or SUBMITTED**(recalled). Once
  **APPROVED / REJECTED / SUPERSEDED**, the version is **immutable** — re-planning creates a **new version**
  (append-only versioning; the GL append-only spirit, PROJECT-CONVENTIONS §3.6).
- **BR-BUD-07** — The GL line **cost-centre tag is analysis-only**: it never changes the double-entry, the
  account, the amount, the currency, or the period assignment. It is nullable (Unallocated) and mirrors the
  existing nullable `branch_id` tag (ADR-0013 D-7). The GL double-entry invariants (Σ debits = Σ credits, etc.)
  are **unchanged**.
- **BR-BUD-08** — Cost-centre tagging on a journal line is set **at post time** from the source: the manual
  journal path sets it per line (FR-BUD-05); automatic posters pass through a cost centre **only if** their
  source document carries one (v1: none do, so automatic postings are Unallocated). No retrofit/backfill of
  historical lines.
- **BR-BUD-09** — A budget-line **amount is a non-negative base-currency figure** (NUMERIC(19,4), HALF_UP).
  The **sign for variance** is taken from the account's **normal balance / account type** (an INCOME budget of
  100 is "expected revenue 100"; an EXPENSE budget of 100 is "expected spend 100") — the budget stores a
  magnitude; the variance computation applies the type convention (BR-BUD-14).
- **BR-BUD-10** — All budget amounts and actuals are **base currency only** (Money ADR-0005, BR-GL-06). No FX.
- **BR-BUD-11** — A version cannot be **submitted with zero lines** (an empty budget is not a plan).
- **BR-BUD-12** — There is **at most one APPROVED version** per (company, fiscal year, cost centre) at any
  time — it is the **active** version. Approving a new version **supersedes** the prior approved one. Variance
  always reads the active (approved) version; with none, budget = 0 + "no approved budget" flag.
- **BR-BUD-13** — Version state transitions are a **fixed, service-guarded machine**: DRAFT→SUBMITTED→
  {APPROVED | REJECTED}; SUBMITTED→DRAFT (recall); APPROVED→SUPERSEDED (only by approving another). No other
  transition; status is **never free-set**; every transition is audited.
- **BR-BUD-14** — **Actual** for an account over a period range = the GL `journal_lines` movement for that
  account in those periods, **signed by the account's normal balance** so it is comparable to the budget
  magnitude (the same convention the shipped P&L uses, ADR-0018). When a cost centre is chosen, actuals are
  filtered to `cost_centre_id = <centre>`; the **Unallocated** bucket (`cost_centre_id IS NULL`) is shown
  separately, not silently dropped or misattributed.
- **BR-BUD-15** — **Variance** = (actual − budget) for the account, with **variance %** = variance / budget
  (budget ≠ 0; shown as n/a when budget = 0). Favourable/adverse is a **presentation** label derived from
  account type (under-spend on EXPENSE = favourable; under-earn on INCOME = adverse) — the engine computes the
  signed variance; the UI labels it.
- **BR-BUD-16** — Reports are **read-only**, **company-scoped**, **RBAC-gated**, **paginated** where they list,
  and **CSV-exportable** via the shipped reporting export path. They never mutate.
- **BR-BUD-17** — Cost-centre tagging must be **additive and non-breaking** on the GL: adding the nullable
  `cost_centre_id` to `journal_lines` must not change any existing posting, read, reconciliation, or the
  trial-balance/P&L/BS output for lines that remain NULL. (The variance/by-centre reads are the only new
  consumers of the column.)

---

## 6. Key flows

### 6.1 Happy path — define a cost centre, budget it, approve, and report variance

1. Finance analyst creates cost centre **CC-0007 "Sales Department"** (type DEPARTMENT) — `BUDGETING.COSTCENTRE.MANAGE`.
2. Analyst creates a **budget** for FY2026 scoped to CC-0007 — `BUDGET-0003`, opens **version 1 (DRAFT)** —
   `BUDGETING.BUDGET.MANAGE`.
3. Analyst enters budget lines: account 5300 Salaries, annual 144,000,000 with **even spread** → 12 monthly
   lines of 12,000,000; account 5400 Utilities, per-period amounts; etc.
4. Analyst **submits** version 1 → SUBMITTED (lines lock) — `BUDGETING.BUDGET.SUBMIT`.
5. Finance manager **approves** version 1 → APPROVED; it becomes the **active** version for (FY2026, CC-0007)
   — `BUDGETING.BUDGET.APPROVE`. Audited.
6. During the year, salaries post to GL (manual accrual journals tagged `cost_centre_id = CC-0007`, or future
   payroll postings that carry the centre).
7. Controller runs **budget-vs-actual** for FY2026, period range Jan–Mar, cost centre CC-0007 —
   `BUDGETING.REPORT.VIEW`: per account, budget vs actual vs variance vs variance %. Exports CSV.

### 6.2 Happy path — re-plan (a new version supersedes the approved one)

1. Mid-year, the Sales Department budget needs revising. Analyst **creates version 2**, seeding from version 1
   (FR-BUD-13 / FR-BUD-08c) → DRAFT. Edits lines.
2. Submits → SUBMITTED; manager approves → APPROVED. **Version 1 becomes SUPERSEDED** (retained, comparable).
3. From the approval onward, variance reports against version 2. A historical comparison can still read
   version 1 (it is retained).

### 6.3 Unhappy paths

- **Submit an empty version** → rejected "a budget version must have at least one line before submission"
  (FR-BUD-11 / BR-BUD-11).
- **Edit an APPROVED version's lines** → rejected "an approved budget version is immutable; create a new
  version to re-plan" (BR-BUD-06).
- **Approve a version that is not SUBMITTED** (e.g. still DRAFT, or already REJECTED) → rejected "only a
  submitted version can be approved" (BR-BUD-13).
- **Budget line on a period outside the budget's fiscal year** → rejected "period does not belong to this
  budget's fiscal year" (BR-BUD-04).
- **Budget line on an inactive account, or tag a line with an inactive cost centre** → rejected "account /
  cost centre is inactive" (FR-BUD-02 / FR-BUD-07).
- **Delete a cost centre that has postings or budget lines** → rejected; offered **deactivate** instead
  (BR-BUD-05).
- **Variance report with no approved budget for the scope** → returns rows with budget = 0 and a "no approved
  budget" banner — not an error (FR-BUD-16 / BR-BUD-12).
- **Cross-company access** (request a cost centre / budget / variance for another company) → 403 via
  `assertCanActIn` (FR-BUD-20).
- **Two approvers race to approve two versions of the same scope** → optimistic-lock / serialised supersede
  ensures exactly one ends APPROVED, the other either supersedes-and-wins or fails-and-retries; never two
  APPROVED at once (BR-BUD-12, NFR-BUD-04).

---

## 7. Non-functional requirements (NFR-BUD)

- **NFR-BUD-01** — **Tenant isolation:** every cost centre, budget, version, line, and report is
  `company_id`-scoped; `assertCanActIn` on every read path; no cross-company leakage.
- **NFR-BUD-02** — **Additivity / non-breaking GL change:** the `journal_lines.cost_centre_id` column is
  nullable and additive; no existing posting, read, reconciliation, or statement changes for NULL lines
  (BR-BUD-17). Migrations strictly additive on the frozen V1–V19.
- **NFR-BUD-03** — **Performance:** the variance read aggregates `journal_lines` by (account [, cost_centre])
  over a period range — it must be indexed to avoid a table scan on a large GL (mirror the shipped TB index
  approach; an index supporting `(company_id, cost_centre_id, account_id)` / period filtering).
- **NFR-BUD-04** — **Concurrency:** version state transitions and the single-approved-version invariant are
  enforced under optimistic lock; concurrent approvals cannot leave two APPROVED versions for one scope.
- **NFR-BUD-05** — **Append-only versioning:** approved/rejected/superseded versions and their lines are
  immutable; re-plan creates a new version. Audit captures every transition (PROJECT-CONVENTIONS §3.6).
- **NFR-BUD-06** — **Forward-compatibility:** the cost-centre dimension and budget model are built so that
  (a) a **second dimension** (project) is additive, (b) **commitment/encumbrance** and **allocation** posting
  can read budgets/centres later, and (c) **wiring cost centres onto operational documents** (sales/purchase/
  payroll) is a per-document additive change — none precluded.
- **NFR-BUD-07** — **Numbering concurrency-safe:** `CC-####` and `BUDGET-####` allocate via the shipped
  row-locked `code_sequence` (ADR-0007 D-6).
- **NFR-BUD-08** — **Auditability of plan vs actual:** the variance report is reproducible — given the same
  approved version and the same GL state, it returns the same numbers; the active version is unambiguous.

---

## 8. Open questions (OQ-BUD) — owner-style defaults assumed; flag the load-bearing ones

> The system-analyst has assumed reasonable owner defaults so ADR-0034 is not blocked. The **load-bearing**
> ones (★) materially shape the data model / API — confirm before/at ratification.

- **★ OQ-BUD-01 — Cost-centre tagging breadth (the load-bearing scope call).** v1 tags **only manual journal
  lines** explicitly + best-effort pass-through on automatic posters (none carry a centre yet). **Assumed
  default: yes — manual-only explicit tagging in v1; operational documents (sales/purchase/cash/payroll) get a
  cost-centre field additively, later, per document.** *Impact:* if the owner wants, say, sales invoices tagged
  to a cost centre **in this increment**, that pulls a column + UI onto `sales_invoices` (and the posting
  pass-through) into scope — a meaningfully larger slice. **Confirm the breadth.** (If broadened, the
  per-document fields are additive and listed in the ADR's cross-module touch points.)
- **★ OQ-BUD-02 — Where does the cost-centre dimension live on GL — `journal_lines` only, or also
  `journal_entries`?** **Assumed default: on `journal_lines`** (the analysis grain, mirroring `branch_id`
  which sits on batch/entry/line; a single entry may legitimately split lines across centres). *Impact:* line
  vs entry grain changes the variance query and the manual-journal UI. **Confirm line grain.**
- **★ OQ-BUD-03 — Budget granularity: per-period (12 monthly) only, or also an annual-only budget?**
  **Assumed default: per-period (12 monthly) lines are the storage grain**; an "annual amount" is a convenience
  that spreads to 12 periods (§2.1b). *Impact:* if the owner wants a genuine annual-only budget with no period
  breakdown, the variance "by period range" semantics change. **Confirm period grain.**
- **OQ-BUD-04 — Maker≠checker enforcement on submit/approve.** Assumed default: **not hard-enforced** in v1
  (the same user *may* hold both `SUBMIT` and `APPROVE`; segregation is a role-configuration policy). A
  hard "the approver must differ from the submitter" guard is a one-line service check, addable later.
- **OQ-BUD-05 — Budget approval lifecycle: self-contained vs the (unbuilt) generic approvals engine
  (X.5).** Assumed default: **self-contained in this module** (DRAFT→SUBMITTED→APPROVED/REJECTED), because the
  generic approvals engine (PATH §3.12 X.5) is **not built**. Designed so it can be re-pointed at X.5 later.
  *Confirm* the owner is content with a module-local lifecycle now.
- **OQ-BUD-06 — Variance sign / favourable-adverse convention.** Assumed default: engine returns **signed
  variance = actual − budget**; the **favourable/adverse label** is derived from account type in the
  presentation (under-spend EXPENSE = favourable; under-earn INCOME = adverse). Confirm the convention/labels
  finance wants.
- **OQ-BUD-07 — "Unallocated" actuals treatment in by-centre reports.** Assumed default: the
  `cost_centre_id IS NULL` actuals are shown as a **separate "Unallocated" row/bucket**, never folded into a
  chosen centre and never dropped. Confirm.
- **OQ-BUD-08 — Cost-centre parent roll-up in v1.** Assumed default: the `parent` is stored and the
  cost-centre list shows the tree, but **variance roll-up to parent centres is deferred** (v1 reports the leaf
  centre chosen). Confirm whether parent roll-up is wanted in v1 (it is an additive read).
- **OQ-BUD-09 (deferred, non-blocking) — multi-currency budgets, forecasting, encumbrance, allocations,
  profit-centre object, dashboards** — all deferred (§2.2); none precluded (NFR-BUD-06).

---

## 9. Glossary (this module)

- **Cost centre** — an analytical grouping (department / division / project / location) a GL line and a budget
  can be attributed to; the new dimension this module adds beyond branch.
- **Dimension** — an analytical tag on a GL line used to group/report actuals (today: branch; new: cost
  centre). Not part of the double-entry; nullable.
- **Budget** — a plan, scoped to (company, fiscal year, [cost centre]), of expected amounts per account per
  period. Reference data; posts nothing.
- **Budget version** — one revision of a budget's lines, with a DRAFT→SUBMITTED→APPROVED/REJECTED/SUPERSEDED
  lifecycle; exactly one APPROVED version per scope is active.
- **Budget line** — one (account, fiscal period, amount) within a version.
- **Actual** — the GL `journal_lines` movement for an account over a period range, signed by normal balance,
  optionally filtered by cost centre — the realised figure variance compares the budget to.
- **Variance** — actual − budget per account, with a variance %, labelled favourable/adverse by account type.
- **Unallocated** — GL actuals with no cost-centre tag (`cost_centre_id IS NULL`); shown as a distinct bucket.

---

*This requirements document is the ground truth for ADR-0034. It specifies the v1 Budgeting & Management
Accounting increment at the business level only. The data model, tables/columns, API, events, GL/posting
behaviour (there is none — budgets post nothing), permissions, and the additive `journal_lines.cost_centre_id`
GL change are the solutions-architect's, in docs/decisions/0034-budgeting.md.*
