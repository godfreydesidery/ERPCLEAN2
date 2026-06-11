# Requirements — Cost-centre / Accounting-dimension framework (analysis tags on the books)

> Status: **DRAFT — architect-authored, owner-style assumptions made.** This module sits in
> docs/PATH-TO-FULL-ERP.md §3.11 (Budgeting & Management Accounting) as the *build-first enabler*:
> "Dimensions / analytical tags on GL lines (beyond branch) — branch tag exists; cost-centre / project /
> department dimensions designed but not built (extend `journal_lines` with nullable FKs + GROUP BY on
> reads). Build-first enabler." and as critical-dependency #4 (§4): "Cost-centre / dimension framework on
> GL — needed before Budgeting, profit centres, controlling / management-accounting reports, and clean
> project costing. GL is dimension-ready (branch tag); the framework activates it." This document is the
> business spec; the schema/API/code live in **ADR-0025** (next step). Do not infer a data model from
> this document.
>
> Author: solutions-architect (standing in for system-analyst — discovery is normally analyst-led; this
> spec is architect-authored with the load-bearing open questions flagged for owner ratification before
> the ADR is built-to without caveat). Domain: `costing` (a cross-cutting analysis-dimension framework on
> GL + source documents). Business-level spec only. **No schema, no API shapes, no tables/columns, no
> code** here.
>
> **This is the Cost-centre / dimension framework — the foundation Budgeting (§3.14) and Projects
> (§3.13) build on.** It depends on **no un-shipped capability**; it gates **Budgeting** and **Projects**
> (both need the dimension). It is deliberately **thin**: it adds the *dimension model* + the *tagging
> seam on the books and on source documents* + *dimension-tagged reporting*. It does **not** add budgets,
> allocations, profit-centre roll-ups, or approval — those are the modules that consume it.
>
> **Depends on (all shipped):** **GL** (ADR-0013 / V10 — the books this tags: `journal_lines` already
> carries a nullable `branch_id` analysis tag; `chart_of_accounts`; the `GLPostingService` engine; the
> `SalesPostingHandler` / valuation / AR / AP posters that build `JournalEntryDraft`s); **IAM**
> (ADR-0001 — org → company → branch, `RequestContext`, RBAC, `ScopeGuard`, audit; the `branch` is the
> one analysis dimension that already exists); the platform spine (`code_sequence` numbering,
> `MasterStatus` soft-delete, `Money`, uid/ULID, the transactional outbox). All shipped.

## 1. Business context & why now

The books balance and report (TB / P&L / BS / cash-flow), but they answer only *"how much revenue, how
much rent, how much COGS — for the company (optionally by branch)."* They cannot yet answer *"how much
did the **Logistics department** spend on fuel,"* or *"what is the contribution margin of the **Northern
sales region**,"* or *"what did **this project** cost across labour, materials and overhead."* Management
accounting — budgets, profit centres, departmental P&Ls, job costing — needs a way to **tag** financial
effects with **analysis dimensions** beyond the account and the branch.

GL was built **dimension-ready** on purpose (ADR-0013 D-7): `journal_lines.branch_id` is a *nullable
analysis tag*, never a separate ledger; a branch P&L is `journal_lines GROUP BY branch_id × account
type`. The branch is, in effect, the **first dimension**. What is missing is (a) a **generic dimension
model** — a small, configurable set of dimension *types* (Cost Centre, Department, … extensible) each
with its own list of *values* (the actual cost centres / departments); and (b) the **seam** that lets a
posting carry those dimension values onto its journal lines (and lets a source document — a sales
invoice, a supplier bill, a manual journal — declare its dimensions so the posting inherits them), plus
the **reporting** that slices the ledger by dimension.

This is the **build-first enabler** for two greenfield modules:
- **Budgeting** (§3.14) budgets *per account / period / cost-centre* and reports budget-vs-actual — it
  needs the cost-centre dimension to exist and to be present on the actuals (the journal lines).
- **Projects / Job Costing** (§3.13) tags cost/revenue *across GL/AR/AP/stock/sales* with a `project`
  dimension and rolls up project actuals — the cleanest model is for **project to be one more
  dimension** in this framework (not a bespoke `project_id` column scattered across every posting), so
  Projects builds on this seam rather than duplicating it.

Built **first and thin**, this framework lets every later posting be dimension-aware additively and
gives an immediate, useful read: a **dimension-sliced trial balance / P&L** ("show me the P&L for cost
centre X" / "for department Y"). It changes **no existing posting's correctness**: dimensions are
**optional and nullable** everywhere — an untagged posting behaves exactly as today.

### Vocabulary (read this first)

- **Dimension (analysis dimension)** — an *axis* along which financial effects can be classified for
  management reporting, orthogonal to the account. v1 ships two: **Cost Centre** and **Department**.
  The model is generic so further dimensions (Region, Project, Product Line, Channel) are configuration,
  not schema changes — within a fixed maximum number of dimension *slots* (see BR).
- **Dimension type** — the definition of one dimension: a code (e.g. `COST_CENTRE`), a name, whether it
  is mandatory on postings, and which slot it occupies. Per company.
- **Dimension value (member)** — one selectable entry within a dimension: e.g. cost centres
  `CC-100 Administration`, `CC-200 Logistics`, `CC-300 Sales`; departments `DEPT-FIN Finance`,
  `DEPT-OPS Operations`. Carries a code (unique per dimension per company), a name, an active/inactive
  state, an optional parent (for roll-up), and audit. Per company.
- **Cost centre** — a dimension value of the built-in **Cost Centre** dimension: a unit of the business
  to which costs (and optionally revenues) are attributed for management accounting (a department, a
  division, a location, a function). The canonical first dimension this framework delivers.
- **Department** — a dimension value of the built-in **Department** dimension: an organisational unit.
  (Cost Centre and Department are two distinct axes — a single journal line may carry one value of
  each.)
- **Dimension tag (on a journal line)** — the dimension value(s) recorded on a posted `journal_line`,
  alongside the existing `branch_id` analysis tag. The **authoritative analysis fact** the reports
  group by. A line may carry zero, one, or one-per-dimension-slot tags.
- **Source-document dimension default** — the dimension value(s) declared on a *source document* (a
  manual journal entry, a sales invoice header/line, a supplier bill, a stock adjustment) that the
  **posting inherits** so the resulting journal lines are tagged without re-keying. The document is
  where the human picks the dimension; the journal line is where it lands and is reported from.
- **Dimension-tagged report** — a trial balance / account ledger / P&L sliced (filtered and/or grouped)
  by a dimension value: "the P&L for Cost Centre CC-200 Logistics."
- **Mandatory dimension** — a dimension a company has configured as *required on every posting*. When
  on, a posting whose lines lack the mandatory dimension is rejected (a controlled, opt-in governance
  policy — **off** by default in v1 so existing postings are unaffected).
- **Branch (the pre-existing dimension)** — the one analysis tag already on every journal line
  (ADR-0013 D-7). This framework treats branch as a *fixed, system dimension* it does not re-implement;
  it adds *user-defined* dimensions alongside it. Branch is not modelled as a `dimension_value`.

## 2. In-scope (v1) vs Deferred

### In v1 (the thin enabler)

1. **A generic dimension model** — a configurable set of dimension *types* (per company), **seeded with
   two built-ins: Cost Centre and Department**, each with its own list of dimension *values* (cost
   centres / departments) maintained by an admin (create / edit / activate-deactivate; soft-delete via
   `MasterStatus`; cannot hard-delete a value that has postings). Values carry an optional **parent** for
   hierarchical roll-up (e.g. CC-200 Logistics rolls into CC-DIV-OPS Operations Division).
2. **A bounded, fixed number of dimension slots** (see BR-CC-02) so the journal-line tag is a small,
   fixed set of nullable columns (not an unbounded EAV) — the boring, indexable, GROUP-BY-able shape.
3. **The tagging seam on the books** — every `journal_line` can carry the dimension value(s) for each
   slot (additive nullable references, alongside the existing `branch_id`). The `GLPostingService`
   posting path accepts per-line dimension values and persists them; an untagged line is unchanged.
4. **The source-document dimension default seam** — a small set of source documents declare their
   dimension value(s) so the posting inherits them onto the journal lines:
   - **Manual journal entry** (GL composer): per-line dimension pickers (the primary, always-available
     tagging point — an accountant tags an accrual to a cost centre).
   - **Sales invoice** (header-level default, inherited by the revenue/COGS lines) — optional.
   - **Supplier bill** (header-level default, inherited by the expense/inventory lines) — optional.
   - **Stock adjustment** (the shrinkage/adjustment expense line) — optional.
   The seam is **additive and optional**: a document with no dimension declared posts untagged lines
   exactly as today. The list above is the v1 set of "wired" documents; other posters (AR receipt, AP
   payment, cash, year-end close) are **not** wired in v1 (their lines post untagged) — additive later.
5. **Mandatory-dimension governance (opt-in, off by default)** — a company may flag a dimension as
   *required on postings*; when on, the manual-journal post and the wired source-document finalise reject
   a line missing that dimension. Off by default so v1 never breaks an existing flow.
6. **Dimension-tagged reporting (read)** — extend the trial balance and (when Reporting consumes it) the
   account ledger / P&L to **filter by and group by** a dimension value: a dimension-sliced TB and a
   per-cost-centre P&L. The read is `journal_lines` grouped by the dimension column(s) × account, scoped
   by company + `assertCanActIn`, RBAC-gated.
7. **Permissions, scope, audit, numbering** — `COSTING.*` permission catalogue; per-company isolation;
   `assertCanActIn` on every read path; audit on every dimension-type / dimension-value mutation;
   `code_sequence` numbering for dimension values if codes are system-generated (see OQ).

### Deferred (explicitly NOT in v1 — the consuming modules own these)

- **Budgets** (budget headers/lines per account/period/cost-centre, budget-vs-actual variance) — the
  **Budgeting** module (§3.14) consumes this framework; not built here.
- **Profit-centre roll-ups / contribution-margin reports / departmental P&L dashboards** — Budgeting /
  Reporting depth; this framework delivers the *raw* dimension-sliced TB/P&L, not the curated management
  dashboards.
- **Cost allocation / distribution** (spread an overhead cost centre across others; `source_type =
  ALLOCATION` postings) — Budgeting (§3.14) / a dedicated allocation slice; not built here.
- **Project as a dimension wired across every poster** — v1 seeds Cost Centre + Department; **Project**
  is added as a *third dimension* by the Projects module (§3.13) using this exact seam (additive
  dimension type + value list + wiring the remaining posters), not re-litigated here. The framework is
  designed so Projects is a configuration + wiring addition, not a reshape.
- **Wiring the remaining posters** (AR receipt, AP payment, cash transactions, payroll, depreciation,
  year-end close) to inherit document dimensions — additive per-poster; v1 wires only the four documents
  in scope item 4.
- **Dimension combinations / validation rules** (e.g. "Cost Centre CC-200 only valid with Department
  DEPT-OPS") — a rules engine; deferred.
- **Statistical / non-financial dimensions** (headcount, floor area for allocation drivers) — deferred
  to allocation.
- **Per-dimension security** (restrict which users may post to which cost centre) — deferred; v1 scope
  is company-level (a user who can post can pick any active dimension value in their company).
- **FX / multi-currency dimension reporting** — base-currency-only inherited from the platform.

## 3. Actors

- **Costing administrator / Controller** (`COSTING.MANAGE`) — defines dimension values (cost centres,
  departments), sets a dimension mandatory/optional, deactivates obsolete values.
- **Accountant / Finance user** (`GL.POST` + `COSTING.TAG`) — tags manual journals and source documents
  with dimension values when posting.
- **Sales / Purchasing user** — optionally picks a cost-centre / department default on an invoice / bill
  (gated by `COSTING.TAG`); if they lack the permission the dimension pickers are hidden and the
  document posts untagged (when the dimension is not mandatory).
- **Management / Reporting user** (`COSTING.VIEW` + the relevant report permission) — runs the
  dimension-sliced trial balance / P&L.
- **SYSTEM** (the auto-posters) — inherits a source document's declared dimensions onto the journal
  lines it posts; runs under no user permission, bounded by the event's company/branch (the
  `SalesPostingHandler` precedent).

## 4. Functional requirements (FR-CC)

**Dimension model & masters**
- **FR-CC-01** The system shall ship a fixed set of **dimension types** per company, **seeded with two
  built-ins — Cost Centre and Department** — each occupying a distinct, fixed *slot*. A company may
  rename a built-in dimension and toggle its mandatory flag; it may not delete a built-in or exceed the
  fixed slot count (BR-CC-02).
- **FR-CC-02** The system shall let a `COSTING.MANAGE` user maintain the **dimension values** of each
  dimension: create, edit (name, parent, active state), and deactivate. Each value carries a **code**
  (unique per dimension per company), a **name**, an optional **parent value** (same dimension, for
  roll-up), an **active/inactive** flag, a `MasterStatus`, and audit.
- **FR-CC-03** The system shall **soft-delete** a dimension value via `MasterStatus` (deactivate); it
  shall **reject hard-deletion** of a value that is referenced by any posted journal line (it may be
  deactivated, excluding it from new tagging while preserving historical tags) — the
  `chart_of_accounts` no-delete-if-posted precedent (BR-GL-07).
- **FR-CC-04** A deactivated dimension value shall be **excluded from new tagging** (not offered in
  pickers, rejected by the post path) but shall remain on historical journal lines and in reports.
- **FR-CC-05** The system shall validate a dimension value's **parent** is in the *same dimension* and
  the *same company*, and shall reject a cycle in the parent chain.

**Tagging the books**
- **FR-CC-06** The `GLPostingService` posting path shall accept, **per journal line**, an optional
  dimension value for each slot, and persist it on the `journal_line` alongside the existing `branch_id`.
  A line with no dimension values posts exactly as today (optional/nullable).
- **FR-CC-07** The system shall **validate** every dimension value supplied at post time is an **active**
  value of the **correct dimension** in the **same company**; an unknown / cross-company / wrong-slot /
  inactive value rejects the post (the active-account precedent, BR-GL-04).
- **FR-CC-08** When a company has flagged a dimension **mandatory** (FR-CC-13), the post path shall
  **reject** any line missing that dimension's value (with a clear error). When the flag is off (default),
  a missing value is accepted (untagged line).

**Source-document dimension defaults (inheritance)**
- **FR-CC-09** A **manual journal entry** composer shall let a `COSTING.TAG` user pick dimension value(s)
  **per line**; the posted journal line carries exactly those values.
- **FR-CC-10** A **sales invoice** shall let a `COSTING.TAG` user declare an optional **header-level**
  dimension default (per slot); on finalise the auto-posted revenue (and COGS, where applicable) journal
  lines **inherit** those values. (v1 inheritance is header-level; per-invoice-line dimension is
  deferred unless the owner requires it — OQ-CC-04.)
- **FR-CC-11** A **supplier bill** shall let a `COSTING.TAG` user declare an optional header-level
  dimension default; on bill-match posting the expense/inventory journal lines inherit those values.
- **FR-CC-12** A **stock adjustment** shall let a `COSTING.TAG` user declare an optional dimension default;
  the adjustment expense journal line inherits it.
- **FR-CC-13** A `COSTING.MANAGE` user shall be able to set a dimension **mandatory** or **optional** for
  the company (default optional). The setting governs FR-CC-08 enforcement at post time.

**Reporting**
- **FR-CC-14** The system shall provide a **dimension-sliced trial balance**: the existing TB
  (`SUM(debit) − SUM(credit)` per account) **filterable by a dimension value** and/or **grouped by**
  dimension value, scoped to company + `assertCanActIn`, RBAC-gated (`COSTING.VIEW` + `GL.VIEW`).
- **FR-CC-15** The system shall provide a **dimension value list** read (the picker source) and a
  **dimension-value drill** (the posted lines carrying a given dimension value, paged), both scoped +
  gated.
- **FR-CC-16** Roll-up: a dimension-sliced report requested for a **parent** value shall optionally
  include its descendant values' lines (a `COST CENTRE roll-up`), using the parent chain (FR-CC-02).
- **FR-CC-17** (Contract for consumers — not a v1 screen) The framework shall expose, for **Budgeting**
  and **Projects**, a documented service/DTO to (a) resolve a dimension value by uid → its id/company,
  (b) list active values of a dimension, and (c) read actuals grouped by `(account, dimension value,
  period)` — so those modules build on the seam, not on the tables. (See ADR cross-module contract.)

**Numbering / scope / audit**
- **FR-CC-18** Dimension-value codes shall be unique per dimension per company; if system-generated,
  allocated via `code_sequence` (OQ-CC-03 — default: user-supplied code, like the chart of accounts).
- **FR-CC-19** Every dimension-type and dimension-value mutation, and every mandatory-flag change, shall
  be **audited** (actor, target, before/after) per the platform audit trail.
- **FR-CC-20** Every read path shall be company-scoped and call `assertCanActIn`; every mutation shall be
  `@perm`-gated on a `COSTING.*` permission (never `hasAuthority`).

## 5. Business rules (BR-CC)

- **BR-CC-01** Dimensions are **analysis tags, never a separate ledger** (the ADR-0013 D-7 branch
  doctrine extended). The books remain company-level and double-entry-balanced; a dimension does **not**
  have to balance on its own (a single journal line may carry a cost centre while its balancing line
  carries a different one or none). Reports may show a dimension's net is non-zero — that is correct, not
  an error.
- **BR-CC-02** The number of user-defined dimension *slots* on a journal line is **fixed and bounded**
  (recommended: **4** — two built-ins Cost Centre + Department, two reserved for Project + one future,
  alongside the pre-existing `branch_id` which is **not** counted as a slot). This keeps the journal-line
  tag a small set of nullable indexed columns, not an unbounded EAV. Exceeding the slot count is a schema
  change under a new ADR, deliberately.
- **BR-CC-03** Dimensions are **optional by default**: an untagged posting is valid and behaves exactly
  as the system does today. Mandatory enforcement (FR-CC-08/13) is **opt-in per company per dimension**,
  off by default — a v1 deployment changes no existing posting's behaviour until a controller turns it on.
- **BR-CC-04** A dimension value supplied at post time must be **active**, belong to the **correct
  dimension slot**, and live in the **same company** as the posting — else the post is rejected (the
  active-account / same-company invariant, BR-GL-04/05). Validation is in the **service** (the posting
  engine), with the DB FK as the same-company backstop.
- **BR-CC-05** A dimension value referenced by any posted journal line **cannot be hard-deleted**, only
  deactivated (FR-CC-03) — historical tags are immutable (the append-only ledger, BR-GL-02).
- **BR-CC-06** The source-document → journal-line **inheritance is one-way and at post time**: the
  document's declared dimension default is *copied* onto the journal lines when the posting is built;
  later editing the document's default (where the document is still mutable, e.g. a draft) does not
  retro-tag already-posted lines (the lines are append-only).
- **BR-CC-07** The framework introduces **no new GL account, no new `gl_config` key, and no new posting**:
  it does not change *which* accounts a posting hits or *what* it debits/credits — it only *tags* the
  lines a posting already produces. (This is the property that makes it safe to integrate with the
  shipped `GLPostingService` without regression.)
- **BR-CC-08** Branch remains the **system dimension** (`journal_lines.branch_id`); it is not modelled as
  a user-defined dimension value and is not maintained through `COSTING.MANAGE`. Dimension-sliced reports
  may combine branch with user-defined dimensions (branch × cost centre).
- **BR-CC-09** A dimension value's **parent** must be the same dimension and same company, acyclic
  (FR-CC-05). Roll-up reads walk the parent chain; a value with no parent is a root.
- **BR-CC-10** Per-company isolation: a company sees and tags only its own dimension types/values; the
  posting validation and every read assert the active company (`assertCanActIn`).

## 6. Key flows

### 6.1 Happy path — define a cost centre, tag a manual journal, report

1. A controller (`COSTING.MANAGE`) opens the Cost Centre dimension and creates values
   `CC-100 Administration`, `CC-200 Logistics`, `CC-300 Sales` (each audited; codes unique per company).
2. An accountant (`GL.POST` + `COSTING.TAG`) posts a manual accrual journal: DR `5410 Fuel` 50,000 / CR
   `2100 Accruals` 50,000, and on the fuel line picks Cost Centre `CC-200 Logistics`.
3. `GLPostingService` validates the journal (≥2 lines, balanced, open period, active accounts) **and**
   validates `CC-200` is an active Cost Centre value in this company; it persists the journal line with
   `cost_centre_value_id = CC-200`'s id (the credit line carries no dimension — that is allowed,
   BR-CC-01).
4. A management user (`COSTING.VIEW`) runs the dimension-sliced TB filtered to Cost Centre `CC-200`: the
   `5410 Fuel` row shows the 50,000 attributed to Logistics. The company-level TB still nets to zero;
   the cost-centre slice does not have to (BR-CC-01).

### 6.2 Happy path — source-document inheritance (sales invoice)

1. A salesperson (`COSTING.TAG`) finalises an invoice and sets the header Cost Centre default
   `CC-300 Sales`.
2. On `SALE.FINALISED`, the `SalesPostingHandler` builds the revenue journal as today **and** stamps each
   journal line it posts with `cost_centre_value_id = CC-300` (inherited from the invoice header
   default the handler re-reads alongside the totals). COGS lines (from the delivery handler, where
   applicable) likewise inherit.
3. The dimension-sliced P&L for `CC-300 Sales` now includes this sale's revenue (and COGS).

### 6.3 Unhappy paths

- **Inactive / wrong-dimension / cross-company value at post.** The accountant picks a deactivated cost
  centre (or a Department value in the Cost-Centre slot, or a value from another company). The post path
  rejects with a clear message; nothing partial is written (the balanced-or-rejected invariant extends to
  dimension validity — BR-CC-04).
- **Mandatory dimension missing.** The company has flagged Cost Centre mandatory. The accountant posts a
  line with no cost centre. The post is **rejected** ("Cost Centre is required on every line"). With the
  flag off (default), the same post **succeeds** with an untagged line (BR-CC-03/08).
- **Delete a used cost centre.** A controller tries to delete `CC-200` after it has postings. The system
  **refuses hard-delete**, offers **deactivate** instead (FR-CC-03 / BR-CC-05); the value disappears from
  pickers but its historical tags remain in reports.
- **Cycle / cross-dimension parent.** A controller sets `CC-200`'s parent to a Department value, or to a
  descendant of itself. Rejected (FR-CC-05 / BR-CC-09).
- **Tagging without permission.** A user without `COSTING.TAG` opens the journal composer: the dimension
  pickers are hidden; if a mandatory dimension is on for the company, that user **cannot post** (they
  lack the means to satisfy the mandatory rule) — a deliberate governance consequence the controller
  accepts when turning mandatory on.

## 7. Non-functional requirements (NFR-CC)

- **NFR-CC-01 (Zero-regression on existing postings)** — the framework must be **purely additive** to the
  posting path: with no dimensions configured and no document defaults set, every existing posting (sales,
  COGS, AR, AP, cash, VAT, year-end) produces **byte-for-byte the same journal lines** as today (untagged).
  An integration test must assert an existing posting flow is unchanged when dimensions are unused.
- **NFR-CC-02 (Read performance)** — the dimension-sliced TB/P&L must be index-supported: the
  `journal_lines` dimension columns must be indexed for the `WHERE company_id = ? AND <dim> = ? GROUP BY
  account` aggregate (the `ix_journal_lines_company_account` precedent), so a dimension slice is not a
  table scan at QA scale.
- **NFR-CC-03 (Bounded model)** — the journal-line dimension tag is a **fixed small set of nullable
  columns** (BR-CC-02), not an EAV / join table per line, so reads are simple indexed GROUP BYs and the
  hot posting path writes no extra rows.
- **NFR-CC-04 (Tenant isolation)** — every dimension type/value and every read is company-scoped;
  `assertCanActIn` on every read path; no cross-company dimension is selectable or reportable.
- **NFR-CC-05 (Auditable)** — every master mutation and every mandatory-flag change is audited
  (append-only IAM audit). The posting's dimension tag is part of the immutable journal line.
- **NFR-CC-06 (Additive / extensible)** — adding a further dimension (Project, Region) within the slot
  budget is configuration + per-poster wiring, not a schema reshape; exceeding the slot budget is a
  deliberate ADR. The framework must not preclude the Budgeting / Projects consumers (their service/DTO
  contract is documented up front — FR-CC-17).
- **NFR-CC-07 (Boundary discipline)** — the framework is a module that GL/Sales/Purchases/Stock posting
  paths *call into* (or read a DTO from) for validation + value resolution; no module→module cycle forms;
  cross-module references are scalar id/uid + DTO, never a foreign entity import (the `ModuleBoundary`
  discipline).
- **NFR-CC-08 (Migrations additive)** — additive Flyway only; never edit a shipped migration.

## 8. Open questions (OQ-CC) — owner-style assumptions made; load-bearing ones flagged

> The architect has made reasonable defaults so the ADR can be built-to; the **flagged** OQs are the ones
> whose answer changes the data model or a posting invariant and should be owner-ratified before the ADR
> is taken as final. None blocks *starting* the design.

- **OQ-CC-01 (LOAD-BEARING) — fixed dimension slots vs a generic dimension-line table.** *Assumption: a
  fixed, bounded set of nullable dimension columns on `journal_lines` (BR-CC-02, recommended 4 slots
  alongside branch).* The alternative is a `journal_line_dimensions(line_id, dimension_id, value_id)`
  child table (unbounded dimensions, one row per tag). The fixed-column model is faster and simpler to
  group/index and matches the existing `branch_id` precedent; the child-table model is more flexible but
  adds a join + rows on the hot path. **This is the single decision that most shapes the schema** —
  flagged for ratification. *Architect recommendation: fixed columns (the boring, index-friendly,
  branch-consistent choice); revisit only if the business genuinely needs >4 simultaneous dimensions.*
- **OQ-CC-02 (LOAD-BEARING) — which posters inherit document dimensions in v1.** *Assumption: manual
  journal (per-line), sales invoice (header), supplier bill (header), stock adjustment (header) — scope
  item 4.* Wiring **every** poster (AR receipt, AP payment, cash, payroll, depreciation, year-end) is
  more complete but multiplies the touch-points and risk. *Recommendation: the four above in v1; the rest
  additive as each is needed — flagged because the owner may want a different starting set (e.g.
  prioritise AP payment over stock adjustment).*
- **OQ-CC-03 — dimension-value code: user-supplied vs system-generated.** *Assumption: user-supplied
  code, unique per dimension per company (the `chart_of_accounts.account_code` precedent), no
  `code_sequence`.* If the owner wants auto-numbered cost centres (`CC-0001`), add a `code_sequence`
  kind. *Recommendation: user-supplied (controllers want meaningful codes).*
- **OQ-CC-04 — sales-invoice dimension granularity: header vs per-line.** *Assumption: header-level
  default inherited by all the invoice's posted lines (FR-CC-10).* Per-invoice-line dimension (different
  cost centre per product line) is richer but needs the dimension on `sales_invoice_lines` and a
  line→journal-line mapping. *Recommendation: header in v1; per-line additive if a multi-cost-centre
  invoice is a real need.*
- **OQ-CC-05 — mandatory enforcement scope.** *Assumption: mandatory applies to the manual-journal post
  and the wired source documents only; un-wired posters never enforce (their lines are untagged
  regardless).* This means turning Cost Centre mandatory does **not** retroactively force AR-receipt
  lines to carry it. *Recommendation: accept — full mandatory coverage waits on full poster wiring;
  document the limitation clearly.*
- **OQ-CC-06 — module name / placement.** *Assumption: a new `com.erp.modules.costing` module owns the
  dimension masters + validation/resolution service + the reporting query; GL and the posters call into
  it for validation/resolution and persist the resolved value id on the journal line.* The alternative is
  to fold dimensions into `gl` (they tag GL lines). *Recommendation: a dedicated `costing` module — the
  dimension master is a cross-cutting concern Budgeting/Projects/Sales/Purchases all touch; folding it
  into `gl` would pull Sales/Purchases document-tagging concerns into GL. Flagged as an architecture
  choice — the ADR decides and justifies.*
- **OQ-CC-07 — does the framework emit any domain event?** *Assumption: NO outbox event in v1.* The
  framework is master-data + a synchronous validation/resolution call in the posting path + read
  reporting; it has no cross-module *effect* to choreograph (it changes no balances). A
  `DIMENSION.VALUE.DEACTIVATED` event could let consumers react, but there is no v1 consumer.
  *Recommendation: no event; Budgeting/Projects read the master directly via the documented DTO contract.*
- **OQ-CC-08 — Project as a dimension now or later.** *Assumption: seed only Cost Centre + Department in
  v1; reserve a slot for Project; the Projects module adds the Project dimension + wires the remaining
  posters when it builds (§3.13).* *Recommendation: reserve, don't build — Projects owns its dimension's
  rollout so this framework isn't blocked on Projects' scope.*

---

*This spec is the business ground truth for ADR-0025. The eight OQs carry architect defaults; the two
LOAD-BEARING ones (OQ-CC-01 fixed-slots, OQ-CC-02 poster-wiring set) should be owner-confirmed before the
ADR is treated as final, but the design proceeds on the recommended defaults. The framework's defining
guarantee — **it tags, it never changes a balance or an account** (BR-CC-07) — is what makes it safe to
integrate with the shipped `GLPostingService` and the existing posters additively.*
