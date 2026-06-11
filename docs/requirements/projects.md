# Requirements — Projects / Job Costing (a project as a costing dimension: tag costs + revenue, project P&L + WIP)

> Status: **DRAFT (architect-authored, owner-style assumptions adopted; pending owner ratification).**
> This is a thin, costing-first v1: a **Project master** (+ optional Task / WBS), a way to **tag a cost or a
> revenue line to a project** at the point it is already posted (purchase / stock-issue / payroll / manual
> journal as cost; sale / invoice as revenue), and a **Project P&L + WIP read model** that rolls those tagged
> postings up per project (revenue − cost = margin; uninvoiced cost = WIP). It builds **thin**: it adds **no
> new posting path** and **no new GL leg of its own for tagging** — it reuses the shipped posting engines and
> only attaches a nullable **project dimension** to the rows they already write. Revenue recognition
> (milestone billing, percentage-of-completion, ASC-606), Gantt / resource-levelling, fixed-price contract
> billing, and a generic multi-dimension report builder are **deferred** (§2).
>
> Author: solutions-architect (consuming the PATH-TO-FULL-ERP §3.10 backlog; owner-style assumptions flagged
> in §11 as the load-bearing open questions). Domain: a new module `com.erp.modules.projects` (the project
> master + task + the cost/revenue tag seams + the roll-up read model), with **read-only / tag-only touches**
> into `gl`, `purchases`/`ap`, `stock`, and `sales`/`ar` (it tags their postings; it does not change their
> posting math). Business-level spec; the schema / API shapes / tables / columns / events live in the ADR
> (**ADR-0033**, next step). Do not infer a data model from this document.
>
> **This is Projects / Job Costing — Phase D (docs/PATH-TO-FULL-ERP.md area 13 / §3.10).** GL (ADR-0013),
> AR (ADR-0014), AP (ADR-0015), Cash & Bank (ADR-0016), VAT (ADR-0017), Reporting (ADR-0018), Year-End
> (ADR-0019), **Inventory Valuation & COGS (ADR-0020)**, and **Order-to-Cash (ADR-0021)** all ship. Books
> balance; sales post revenue/VAT/COGS; purchases post via AP; stock is valued. What is missing is a way to
> answer **"what did this job cost, what did it bill, and is it making money?"** — i.e. to slice the existing
> postings by a **project / job** dimension and produce a project-level P&L and a Work-In-Progress figure.
>
> **Depends on:**
> - **The cost-centre / dimension framework** (PATH-TO-FULL-ERP §3.11 / area 14 — **NOT YET BUILT**). Project
>   costing is a **dimensional** concern: a project is one *analytical dimension* you tag a posting with, exactly
>   as `journal_lines.branch_id` is already a nullable analysis tag (ADR-0013 D-7). The generic framework
>   (cost-centre / department / project as configurable dimensions on a posting line) is the *right* long-term
>   home. Because it is not built, this slice ships the **project dimension specifically** as a nullable
>   analytical tag on the posting rows, **designed to fold into the generic framework when it lands** (the
>   project tag becomes one dimension value among several — additive, not a parallel model). **This is the
>   single load-bearing assumption** (§11 OQ-PROJ-01). The architect does **not** invent a parallel
>   cost-centre model — only the project tag, shaped to be subsumed.
> - **GL** (ADR-0013 / V10): `journal_lines` already carries a nullable `branch_id` analysis tag and an
>   indexed `(company_id, account_id)`; the project tag rides the same row. `account_type` is statement-
>   placement authority (INCOME/EXPENSE = the P&L lines a project roll-up reads).
> - **Sales / Order-to-Cash** (ADR-0008/0021): `sales_invoices` + lines + the `SALE.FINALISED` revenue
>   posting — the **revenue** a project tags. Deliveries / SO lines — the cost (COGS) a project can also tag
>   via stock-issue.
> - **Inventory Valuation & COGS** (ADR-0020): `stock_movements` (the costed issue ledger) + COGS postings —
>   the **material cost** a project tags.
> - **Purchases / AP** (ADR-0011/0015): `supplier_bills` + lines + the AP posting — the **purchased
>   cost / subcontract** a project tags.
> - **HR / Payroll** (PATH-TO-FULL-ERP §3.7 — **NOT YET BUILT**): timesheet labour cost. v1 supports manual
>   timesheet capture against a project task for a **planned-rate labour estimate** but does **not** post
>   payroll to a project until Payroll lands (§2; the seam is designed so it folds in — OQ-PROJ-05).
> - **Money** (ADR-0005 — base currency only, NUMERIC(19,4), HALF_UP), **`code_sequence`** numbering, **RBAC /
>   `@perm.has`/`@perm.scoped` / `assertCanActIn` / audit / the transactional outbox + `IdempotencyGuard`** —
>   the platform spine. All shipped.

## 1. Business context & why now

The system can now post a sale (revenue + VAT + COGS), a purchase (AP + inventory/GRNI), a stock issue (COGS),
and a manual journal — and report a company-level P&L and balance sheet. But a great many real businesses run
**by job**: a construction firm builds *this house*; a workshop services *this vehicle*; an agency runs *this
campaign*; an installer fits out *this site*. The owner needs to know, per job, **the cost incurred, the
revenue billed, the margin, and the work-in-progress** (cost incurred but not yet billed). Today every such
posting lands in the company P&L undifferentiated — there is no job lens.

This slice adds that lens **the cheap, boring way**: a project is a **costing dimension**. You create a
project, then — at the moment a cost or revenue is *already being posted* (a supplier bill, a stock issue, a
manual journal, a sales invoice) — you **tag the posting with a project**. A read model then groups the tagged
postings into a **Project P&L** (revenue − cost = margin) and a **WIP** figure (project cost not yet billed to
the customer). No new ledger, no new posting path, no change to how revenue/COGS/AP post — only a nullable
**`project_id`** analytical column on the rows that already carry the money, plus the master to tag against and
the roll-up to read it back. This is the same architectural move GL already made with `branch_id` (a nullable
analysis tag on the journal line) — generalised to "project".

Building it now (Phase D) is correct: every cost and revenue engine it tags is shipped and stable, so the
slice is purely additive tagging + a read model, with no risk to the financial core.

## 2. Scope

### In scope (v1)

1. **Project master** — `PRJ-####` numbered; code, name, the **customer** it is for (optional — internal
   projects have none), an optional **project manager** (an `app_user`), planned start / end dates, a
   **budget amount** (a single planned-cost figure in v1; budget-by-cost-type is deferred), `status`
   lifecycle (DRAFT → ACTIVE → ON_HOLD → COMPLETED / CANCELLED), `MasterStatus` soft-delete, notes. Per
   company + branch; `assertCanActIn` on every read.
2. **Tasks / lightweight WBS** — an optional flat-or-one-level list of **tasks** under a project (code, name,
   optional planned hours, billable flag). v1 is **one level** (a task has no sub-task); the table reserves a
   nullable `parent_id` so a hierarchy is additive later. A cost/revenue tag may name a task (finer grain) or
   just the project (coarse grain).
3. **Cost tagging — the integration spine.** A nullable **project dimension** (`project_id`, optional
   `project_task_id`) attached to the rows that already post a **cost**:
   - **Manual journal** (GL) — a journal line may carry a project tag (the general case; tags any expense/cost
     posting an accountant makes).
   - **Supplier bill** (AP / Purchases) — a bill line may carry a project tag (purchased materials,
     subcontract, services for the job); the tag flows to the GL expense/inventory leg.
   - **Stock issue** (the ADR-0020 COGS posting at sale/delivery, and a **direct stock issue to a project** —
     a new lightweight "issue materials to job" path that consumes stock and posts COGS-equivalent tagged to
     the project).
4. **Revenue tagging.** A nullable project tag on the **sales invoice** (header + optionally line) so the
   revenue posting (DR AR/Cash, CR Sales Revenue) carries the project, and the project P&L sees the billed
   revenue. An SO / delivery may carry the project so the tag flows through to the invoice automatically.
5. **Project actuals roll-up (read model).** A query that, per project (and optionally per task / per
   cost-type bucket), aggregates the **tagged GL postings** into: **revenue** (Σ tagged INCOME-account
   credits), **cost** (Σ tagged EXPENSE-account debits, with a cost-type breakdown: MATERIAL / SUBCONTRACT /
   LABOUR / OVERHEAD / OTHER — derived from the source / account), **margin** (revenue − cost), **margin %**,
   **budget vs actual cost variance**, and **WIP** (cost incurred − revenue billed, floored as policy
   dictates — see BR). The roll-up reads GL `journal_lines` filtered by `project_id` (the single source of
   truth — it is what the books actually posted), **not** a parallel ledger.
6. **Project list + detail + P&L screens** — list/filter projects; a project detail with its tasks, its tagged
   transactions (drill-down), and the P&L / WIP card with the budget-variance and recon-style self-check.
7. **Permissions, numbering, scope, audit** — `PROJECTS.PROJECT.*`, `PROJECTS.TASK.*`, `PROJECTS.TAG.*`,
   `PROJECTS.COSTING.VIEW`; `code_sequence` kind `PROJECT`; per-company/branch isolation; `assertCanActIn` on
   every read; audit on every project/task state change and every tag attach/detach.

### Deferred (explicitly out of v1; none precluded)

- **The generic cost-centre / multi-dimension framework** (department, cost-centre, project as configurable
  dimensions) — the project tag is shaped to fold into it (OQ-PROJ-01), but the framework itself is a separate
  slice (PATH-TO-FULL-ERP §3.11).
- **Revenue recognition** — milestone billing, percentage-of-completion / ASC-606 / IFRS-15, deferred-revenue
  schedules, retention. v1 WIP is a **simple cost-minus-billing** figure, not a recognised-revenue accrual,
  and posts **no WIP GL journal** (WIP is a reported figure, not a booked asset, in v1 — OQ-PROJ-03).
- **Budget by cost-type / by period / budget-control gating** (blocking a posting that breaches budget) — v1
  has a single planned-cost figure and reports variance; it does **not** block.
- **Payroll → project labour posting** — until Payroll (§3.7) lands; v1 captures timesheet hours for a
  *planned-rate estimate* shown on the project, but labour cost in the P&L comes only from tagged GL postings
  (a manual journal or a future payroll tag). (OQ-PROJ-05.)
- **Gantt / timeline / scheduling / resource-levelling / utilisation** — the data model (tasks + dates +
  planned hours) supports them; the visualisation is deferred.
- **Multi-level WBS, dependencies, % complete entry, earned value** — one task level in v1.
- **Cross-company / consolidated project reporting; multi-currency projects** — base currency, single company
  per project; not precluded.
- **Project-driven procurement / sales-order auto-creation, project templates, change orders.**

## 3. Actors

- **Project Manager / Job Supervisor** — creates and runs projects/tasks; views the project P&L; tags costs
  where permitted.
- **Accountant / Finance** — tags postings to projects at point of entry (the bill, the manual journal, the
  invoice); reconciles project totals to GL; views all project P&Ls.
- **Sales / Operations** — attaches a project to a sales order / invoice so revenue is captured.
- **Storekeeper** — issues materials from stock to a project (the direct stock-issue-to-job path).
- **Owner / Management** — reads project profitability and WIP across the company.
- **System** — the roll-up read model; the outbox handlers that propagate a project tag onto a derived posting
  (e.g. the COGS leg of a tagged sale).

## 4. Functional requirements (FR-PROJ-NN)

- **FR-PROJ-01** — Create / edit a **project** (code auto-`PRJ-####` at create; name; optional customer;
  optional manager; planned start/end; budget amount; notes). Per company + branch.
- **FR-PROJ-02** — Project **status lifecycle**: DRAFT → ACTIVE → ON_HOLD ↔ ACTIVE → COMPLETED; any
  non-terminal → CANCELLED. Tagging is allowed only against an **ACTIVE** (or ON_HOLD, configurable) project;
  a COMPLETED/CANCELLED project rejects new tags (BR-PROJ-04). `MasterStatus` soft-delete for archival.
- **FR-PROJ-03** — Create / edit / deactivate **tasks** under a project (code, name, planned hours, billable
  flag, reserved `parent_id`). One level in v1.
- **FR-PROJ-04** — **Tag a manual journal line** to a project (+ optional task) when posting a GL journal. The
  tag is carried on the `journal_line`; the posting math is unchanged.
- **FR-PROJ-05** — **Tag a supplier-bill line** to a project (+ optional task). The tag flows to the GL
  expense/inventory leg the AP posting writes for that line (so the project P&L sees the cost).
- **FR-PROJ-06** — **Tag a sales invoice** (header default + optional per-line override) to a project. The tag
  flows to the GL revenue leg (and, for an SO-sourced sale, to the COGS leg via the delivery).
- **FR-PROJ-07** — **Tag an SO / delivery** to a project so the tag propagates automatically to the invoice
  (revenue) and the COGS posting (cost) without re-tagging at each step.
- **FR-PROJ-08** — **Issue materials to a project** — a lightweight stock-issue-to-job path: pick a product +
  qty + project (+ task), which posts a stock issue (−qty, COGS-at-average) **tagged to the project**, with no
  customer/invoice (an internal consumption). Reuses the ADR-0020 valuation/COGS engine; emits a project-tagged
  COGS posting.
- **FR-PROJ-09** — **Project actuals roll-up / P&L read** — per project: revenue, cost (with cost-type
  breakdown), margin, margin %, budget, budget-vs-actual variance, WIP. Optionally grouped by task. Reads GL
  `journal_lines` filtered by `project_id`.
- **FR-PROJ-10** — **Drill-down** from a project P&L line to the underlying tagged journal entries /
  source documents (the invoice, the bill, the stock issue, the manual journal).
- **FR-PROJ-11** — **Recon self-check** — the project roll-up exposes a reconciliation bar: Σ(project-tagged
  postings by account-type) computed from the GL must equal the project read model's displayed totals (a
  structural self-check, the ADR-0018 `ReconciliationDto` pattern). Untagged postings are correctly *not*
  attributed to any project (the company P&L still includes everything; the project P&Ls sum to ≤ company,
  with the difference being untagged activity).
- **FR-PROJ-12** — **Capture timesheet hours** against a project task (employee/user, date, hours, billable
  flag) for a planned-rate labour estimate shown on the project. v1 posts **no GL** from timesheets
  (OQ-PROJ-05); the estimate is informational until Payroll tags labour cost.
- **FR-PROJ-13** — **Detach / re-tag** a project tag — because the ledger is append-only (BR-GL-02), a tag on a
  *posted* journal line is **corrected by a project-tag reversal/re-tag mechanism the ADR defines**, not by an
  in-place edit of the posted line. (The ADR resolves whether the project tag is mutable analytical metadata
  separate from the immutable financial line, or whether re-tag is a reversing posting — OQ-PROJ-04.)

## 5. Business rules (BR-PROJ-NN)

- **BR-PROJ-01** — A project is **per company + branch**; every project read is `assertCanActIn`-guarded; a
  project tag must reference a project in the **same company** as the posting (cross-company tagging rejected).
- **BR-PROJ-02** — The **project dimension is nullable everywhere** — tagging is optional. An untagged posting
  is a normal company-level posting (the existing behaviour, unchanged). The project lens is **additive**: it
  never changes whether or how a posting posts, only whether it is *attributed* to a project.
- **BR-PROJ-03** — The **project roll-up reads the GL** (`journal_lines.project_id`) as the single source of
  truth for money. The projects module maintains **no parallel cost ledger**; the source-document tables
  (bill line, invoice line, stock movement) carry the tag for drill-down, but the **money** is whatever the GL
  posted. (This is what guarantees the recon — BR-PROJ-09.)
- **BR-PROJ-04** — A project tag may be attached only when the project is **ACTIVE** (or ON_HOLD if the owner
  enables it); COMPLETED/CANCELLED rejects new tags. Existing tags on historical postings are unaffected by a
  later status change (a completed project still shows its history).
- **BR-PROJ-05** — **Cost classification.** A tagged cost is bucketed into a **cost-type** (MATERIAL /
  SUBCONTRACT / LABOUR / OVERHEAD / OTHER) for the P&L breakdown. The bucket is derived from the **source**
  (a tagged stock issue/COGS = MATERIAL; a tagged supplier-bill goods line = MATERIAL, a service line =
  SUBCONTRACT or OVERHEAD per the account; a tagged payroll posting = LABOUR; a manual journal = the tag's
  explicit cost-type or OTHER). The ADR fixes the exact derivation (OQ-PROJ-02).
- **BR-PROJ-06** — **Revenue** is the Σ of project-tagged postings to **INCOME** accounts (Sales Revenue,
  etc.); **cost** is the Σ of project-tagged postings to **EXPENSE** accounts (COGS, Purchases, Shrinkage,
  Subcontract, Labour, Overhead). Account-type is the placement authority (ADR-0013 BR-GL-12) — the same rule
  the P&L uses, so a project P&L is a company P&L filtered by `project_id`.
- **BR-PROJ-07** — **WIP** (v1) = **cost incurred to date − revenue billed to date**, per project, **floored
  at zero by default** (a project that has billed more than it has cost shows zero WIP, not negative — the
  excess is recognised margin, not WIP). The floor policy and whether over-billing surfaces as deferred
  revenue is the ADR's to fix (OQ-PROJ-03). **WIP is a reported figure only — v1 posts no WIP/accrual journal**
  (no DR WIP-asset / CR …); the books are unchanged. (Booked WIP / revenue recognition is deferred.)
- **BR-PROJ-08** — **Budget variance** = budget amount − actual cost to date; reported, never enforced
  (no budget-control gating in v1).
- **BR-PROJ-09** — **Recon invariant.** For any project, the read model's revenue/cost/margin MUST equal the
  GL-computed Σ of that project's tagged journal lines by account-type. The roll-up is a GL query, so this
  holds by construction; the recon bar (FR-PROJ-11) surfaces any drift as a finance-grade defect.
- **BR-PROJ-10** — The **project tag propagation** across the outbox (a tagged sale → its COGS leg; a tagged
  delivery → its issue) MUST be idempotent (`IdempotencyGuard`) and MUST NOT change the financial amounts —
  only attach the dimension. A re-delivered event re-applies the same tag, never a second posting.
- **BR-PROJ-11** — **Append-only respect.** A project tag on a *posted* GL line follows the append-only rule:
  if the tag is treated as part of the immutable financial line, a re-tag is a reversing-and-re-post; if the
  tag is mutable analytical metadata held alongside the line, a re-tag is an audited metadata update. The ADR
  picks one (OQ-PROJ-04) and the chosen mechanism is fully audited (NFR-PROJ-03).
- **BR-PROJ-12** — Numbering: `PRJ-####` per company via `code_sequence` (concurrency-safe), allocated at
  project create. Tasks numbered within their project.

## 6. Key flows

### Happy path A — internal project, cost-tagged, P&L read

1. PM creates project `PRJ-0007 "Warehouse Fit-out"`, customer = none (internal), budget = 5,000,000;
   status ACTIVE.
2. Storekeeper **issues materials to the project** (FR-PROJ-08): 50 units of cable → stock −50, COGS posted
   DR COGS / CR Inventory at moving average, **tagged `project_id = PRJ-0007`**, cost-type MATERIAL.
3. Accountant posts a **manual journal** for hired equipment, tagging the expense line to `PRJ-0007`,
   cost-type OVERHEAD.
4. PM opens the project P&L: revenue 0, cost = (material + overhead), margin negative (internal project),
   WIP = cost (no billing), budget variance shown. Drill-down to the stock issue + the journal.

### Happy path B — customer project, revenue + cost, margin + WIP

1. PM creates `PRJ-0008 "Site B Installation"`, customer = ACME, budget = 12,000,000, ACTIVE.
2. A **supplier bill** for materials is posted, its goods line **tagged `PRJ-0008`** → AP posting carries the
   tag on the inventory/expense leg (cost-type MATERIAL/SUBCONTRACT).
3. A **sales order** for the install is raised and **tagged `PRJ-0008`**; on delivery, the COGS posts tagged
   to the project (cost); on invoice finalise, the revenue posts tagged to the project (revenue).
4. PM opens the project P&L: revenue = invoiced amount, cost = materials + COGS, margin = revenue − cost,
   **WIP** = cost incurred − revenue billed (positive while underbilled, zero once fully billed),
   budget-vs-actual variance, recon bar green.

### Unhappy path A — tag a closed/cancelled project

- Attempt to tag a posting to a COMPLETED or CANCELLED project → rejected with "project not open for tagging"
  (BR-PROJ-04). The posting itself still posts **untagged** (the cost/revenue is not lost from the company
  books — only the project attribution is refused) — or the whole action is rejected, per the ADR's chosen
  posture (OQ-PROJ-06).

### Unhappy path B — cross-company / wrong-company tag

- A tag referencing a project in another company → rejected by `assertCanActIn` + the same-company check
  (BR-PROJ-01). No posting is attributed cross-tenant.

### Unhappy path C — re-tag a posted line

- An accountant tagged the wrong project. They invoke the re-tag mechanism (FR-PROJ-13): per the ADR's
  resolution (OQ-PROJ-04), either a reversing-and-re-post of the affected legs (append-only) or an audited
  metadata re-tag. Either way the change is fully audited and the recon stays green.

### Unhappy path D — recon drift

- The project P&L recon bar shows the read-model total ≠ the GL-computed tagged total → flagged on screen as a
  finance-grade defect (should be impossible by construction — BR-PROJ-09 — so a green bar is the invariant and
  a red bar is a bug to investigate).

## 7. NFRs (NFR-PROJ-NN)

- **NFR-PROJ-01** — Every project read is `assertCanActIn(principal, companyId)`-guarded and per-company
  scoped (the #1 anti-regression guard); the roll-up never leaks another tenant's project costs.
- **NFR-PROJ-02** — The roll-up aggregates **in SQL** (GROUP BY `project_id` / cost-type over `journal_lines`
  with the partial/expression index the ADR adds), never row-by-row in Java; paginated project lists; the P&L
  read is a small number of indexed aggregate queries.
- **NFR-PROJ-03** — Every project/task lifecycle change and every tag attach/detach/re-tag is **audited**
  (append-only audit trail), with the actor, the before/after, and the affected posting reference.
- **NFR-PROJ-04** — **Additivity / non-regression.** The project dimension is nullable and the tagging is
  optional; an existing untagged workflow (a plain sale, a plain bill) behaves **exactly** as today. No
  existing posting math, lifecycle, or event payload semantics change for an untagged posting.
- **NFR-PROJ-05** — **Concurrency / idempotency.** Tag propagation across the outbox is idempotent
  (`IdempotencyGuard`); the roll-up tolerates concurrent posting (it is a read over committed GL rows).
- **NFR-PROJ-06** — **Forward-compatibility with the dimension framework.** The project tag is modelled so
  that, when the generic cost-centre/dimension framework lands, the project becomes one dimension among
  several without a data migration that rewrites posted lines (the `project_id` column either is the dimension
  slot or maps cleanly onto it — the ADR fixes the shape, OQ-PROJ-01).
- **NFR-PROJ-07** — **Additive migrations.** Schema is additive on the frozen V1–V19 (range V64–V68 per the
  coordinator); nullable `project_id` columns ADD onto `journal_lines`, `supplier_bill_lines`,
  `sales_invoices`/lines, `stock_movements` (and SO/delivery if propagation needs it); no shipped DDL edited.

## 8. Data we will need (business-level, not schema)

- A **project**: number, name, customer (optional), manager (optional), planned dates, budget, status, branch.
- A **task**: project, code, name, planned hours, billable flag, (reserved parent).
- A **project tag** on a posting: the project (+ optional task) + a derived cost-type, attached to a GL line
  and carried on the source-document line for drill-down.
- A **timesheet line** (v1, informational): project, task, user, date, hours, billable, planned rate.
- A **roll-up read**: per project (× cost-type × task): Σ revenue, Σ cost, margin, budget, variance, WIP.

## 9. Reporting / read model

- **Project list** — filter by status / customer / manager; show budget, actual cost to date, margin, % spent.
- **Project P&L / job-cost card** — revenue, cost-by-type breakdown, margin, margin %, budget variance, WIP,
  recon bar; drill-down to source postings.
- **WIP report** — across projects: cost incurred − billed, per project, company total (a management view of
  unbilled work).
- **Reuse** the ADR-0018 reporting read-model conventions (`ReconciliationDto`, pagination, CSV export rides
  the X.1 document enabler — out of scope here).

## 10. Accepted boundary (what v1 deliberately does NOT do)

v1 is a **costing dimension + roll-up**, not a project-management or revenue-recognition system. It does not
schedule, level resources, recognise revenue on a curve, book a WIP asset, gate spend on budget, run payroll,
or model contracts/change-orders/retention. It tags the postings the shipped engines already make and reports
them by job. Everything richer is deferred (§2) and the model is shaped not to preclude it (NFR-PROJ-06).

## 11. Open questions (OQ-PROJ-NN) — recommended owner-style defaults adopted; load-bearing ones flagged

- **OQ-PROJ-01 (LOAD-BEARING) — the dimension model.** The generic cost-centre/dimension framework is **not
  built**. *Recommended default (adopted):* ship the **project dimension specifically** as a nullable
  `project_id` (+ `project_task_id`) analytical column on the posting rows (`journal_lines` foremost), exactly
  mirroring the shipped nullable `journal_lines.branch_id` analysis tag — **not** a generic
  `dimension_value_id`. When the framework lands, the project tag is **one dimension** it subsumes (the
  framework can read `journal_lines.project_id` or migrate it into a dimension-slot additively). This avoids
  building a speculative generic framework now (which would be gold-plating and would pre-empt area 14's own
  design) while not precluding it. **The architect does not invent a parallel cost-centre model** — only the
  project tag. *Decider: owner.* This is the decision the ADR makes (D-1/D-2).
- **OQ-PROJ-02 — cost-type derivation.** How is a tagged cost bucketed into MATERIAL / SUBCONTRACT / LABOUR /
  OVERHEAD / OTHER? *Recommended default:* derive from the **source** (stock issue/COGS → MATERIAL; supplier
  bill goods line → MATERIAL, service line → SUBCONTRACT/OVERHEAD by account; payroll → LABOUR; manual journal
  → an explicit cost-type chosen at tag time, else OTHER). *Decider: owner.* ADR fixes the mapping (D-5).
- **OQ-PROJ-03 — WIP definition + flooring + GL.** *Recommended default:* WIP = cost − billed, floored at zero,
  **reported only (no WIP journal posted)** in v1; over-billing shows as recognised margin, not deferred
  revenue. Revenue recognition / booked WIP is deferred. *Decider: owner.* ADR fixes the formula (D-6).
- **OQ-PROJ-04 — re-tag of a posted line (append-only).** Is the project tag immutable analytical metadata
  separate from the immutable financial line (so a re-tag is an audited metadata update), or part of the line
  (so a re-tag is a reversing-and-re-post)? *Recommended default:* the tag is **analytical metadata** the
  service may re-set with full audit (the financial amounts are never touched; only the attribution changes),
  because attribution is not a posting — it is a reporting dimension; this keeps re-tagging cheap and matches
  how `branch_id` is treated. *Decider: owner.* ADR fixes the mechanism (D-7).
- **OQ-PROJ-05 — labour cost source.** Until Payroll lands, project labour cost comes from manual journals
  (or the informational timesheet estimate). *Recommended default:* v1 captures timesheets for an estimate;
  actual labour cost in the P&L is tagged GL postings only; Payroll tagging folds in later. *Decider: owner.*
- **OQ-PROJ-06 — tag against a closed project.** Reject the whole action, or post untagged with a warning?
  *Recommended default:* **reject the tag** (the cost still posts untagged — the company books are never
  blocked by a project-status problem), surfaced as a validation error so the user can re-open or re-target.
  *Decider: owner.*
- **OQ-PROJ-07 — project granularity of revenue.** Header-level project on the invoice vs per-line. *Recommended
  default:* **header default with optional per-line override** (mirrors the SO discount pattern). *Decider:
  owner.*
- **OQ-PROJ-08 (deferred)** — milestone/POC revenue recognition, budget-control gating, multi-level WBS,
  Gantt, resource utilisation, multi-currency projects — all deferred (§2), none precluded.
