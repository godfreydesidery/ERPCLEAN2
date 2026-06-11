# 0033 — Projects / Job Costing data model: a **project as a costing dimension** in `com.erp.modules.projects` — a `projects` master + one-level `project_tasks` + informational `project_timesheets`, with a nullable `project_id` (+ `project_task_id`) analytical tag added to the posting rows the shipped engines already write (`journal_lines` foremost, plus `supplier_bill_lines`, `sales_invoices`, `stock_movements`, and the SO/delivery for propagation), a lightweight **issue-materials-to-job** path reusing the ADR-0020 COGS engine, and a **Project P&L + WIP read model** that rolls the GL `journal_lines` up by `project_id` (revenue = Σ tagged INCOME credits, cost = Σ tagged EXPENSE debits by cost-type, margin, budget variance, WIP = cost − billed) with a `ReconciliationDto` self-check — **no parallel cost ledger, no new GL leg for tagging, no posting-math change** — additive on the frozen V1–V19 across **V64–V68**

- **Status:** Proposed
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (consuming [docs/requirements/projects.md](../requirements/projects.md) DRAFT + PATH-TO-FULL-ERP §3.10 / area 13; owner ratification pending — the load-bearing fork **OQ-PROJ-01** (the project-as-dimension shape, given the generic cost-centre framework is unbuilt) is the decision this ADR makes, not a requirements blocker).
- **Context source:** [docs/requirements/projects.md](../requirements/projects.md) (FR-PROJ-01..13, BR-PROJ-01..12, NFR-PROJ-01..07, §6 flows, §10 boundary, §11 OQ log — the ground truth for every rule below) + [docs/PATH-TO-FULL-ERP.md](../PATH-TO-FULL-ERP.md) §3.10 (area 13) / §3.11 (area 14 — the **unbuilt** dimension framework this slice integrates *toward*). Verified against the **shipped** code:
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / [V10__general_ledger.sql](../../backend/src/main/resources/db/migration/V10__general_ledger.sql)): `journal_lines` (`id`, `uid` VARCHAR(26), `company_id`, **`branch_id` BIGINT NULLABLE — the existing nullable analysis tag this ADR mirrors**, `entry_id`, `line_no`, `account_id`, `debit_amount`/`credit_amount` NUMERIC(19,4), `currency`, `line_memo`, `@Version`, audit; `ix_journal_lines_company_account`, `ix_journal_lines_company_branch`); `journal_entries` (`source_type`/`source_ref`, `posting_date`, `fiscal_period_id`); `chart_of_accounts.account_type` ∈ {ASSET,LIABILITY,EQUITY,INCOME,EXPENSE} (**the P&L placement authority** — revenue=INCOME, cost=EXPENSE, BR-GL-12); `GLPostingService.post(JournalEntryDraft)→JournalEntryDto` + `LineDraft(accountId, debitAmount, creditAmount, currency, lineMemo)` — **the LineDraft this ADR extends with a nullable `projectId`/`projectTaskId`**; `GLPostingSafeInvoker.postInNewTx`; `GLConfigResolver.resolve(companyId, GlConfigKey)`; `JournalLineRepository.accountBalance(companyId, accountId)`; `ReconciliationDto.of(label, computed, expected)` (ADR-0018).
  - **AP / Purchases** ([ADR-0015](0015-accounts-payable-data-model.md) / V12 + [ADR-0011](0011-purchases-data-model.md) / V8): `supplier_bills` + `supplier_bill_lines` (`grLineUid` goods signal, `net_amount`); `BillMatchServiceImpl.postMatchedBillToGl(SupplierBill)` posts **DR GRNI/PURCHASES · [DR VAT_INPUT] · CR AP** per line — **the per-line GL legs this ADR threads the bill line's `project_id` onto** (the ADR-0020 D-4 / ADR-0017 D-7 in-place one-leg-edit precedent).
  - **Stock / Valuation** ([ADR-0010](0010-stock-data-model.md) / V7 + [ADR-0020](0020-inventory-valuation-data-model.md) / V17): `stock_movements` (append-only, `movement_type` CHECK ∈ {GOODS_RECEIPT,SALE_ISSUE,SALE_REVERSAL,GOODS_RECEIPT_REVERSAL,ADJUSTMENT,OPENING_BALANCE}, `unit_cost_amount`/`value_amount` cost cols, `source_document_type`/`source_document_uid`); `StockPostingService.post(...)` (the qty+cost primitive, MANDATORY, one-retry); `InventoryValuationService.costIssue(...)`; `InventoryGlPoster.postCogsInNewTx(companyId, branchId, postingDate, sourceRef, currency, List<CogsLeg>)` (DR COGS 5100 / CR INVENTORY 1300) — **the engine the issue-to-job path reuses**; `SaleIssueStockHandler` / `DeliveryIssueStockHandler` (ADR-0021 D-6 — the COGS posters this ADR makes project-tag-aware).
  - **Sales / O2C** ([ADR-0008](0008-sales-data-model.md) / V5 + [ADR-0021](0021-sales-orders-data-model.md) / V18–V19): `sales_invoices` (header, `origin` DIRECT|SALES_ORDER, `source_order_uid`/`source_delivery_uid`) + `sales_invoice_lines`; `SaleFinalisedPayload(invoiceUid, companyId, branchId, finalisedAt, lines, issuesStock)`; `sales_orders`/`sales_order_lines`, `deliveries`/`delivery_lines`; `DELIVERY.CONFIRMED` + `DeliveryConfirmedPayload`; `SalesPostingHandler` / `ArSalePostedHandler` (revenue/AR on `SALE.FINALISED`) — **the revenue posters this ADR makes project-tag-aware**.
  - **Outbox / idempotency** ([ADR-0009](0009-transactional-outbox.md)): `OutboxPublisher.publish(...)`; `DomainEventType` constants; `DomainEventHandler` + `IdempotencyGuard.alreadyProcessed/markProcessed`; `processed_events(consumer, event_uid)`.
  - **Platform**: `code_sequence(company_id, entity_kind)` (ADR-0007 D-6, lazy kinds); `ScopeGuard.companyIdOf(targetType, uid)` switch (the `case "..."` table — this ADR adds `project`/`projecttask`); `MasterStatus` soft-delete; `@perm.has`/`@perm.scoped` (NEVER `hasAuthority`); `assertCanActIn` on every read; `Money` base-currency NUMERIC(19,4) HALF_UP.
  - [[db-naming-convention]] verified (plural masters/children `projects`/`project_tasks`/`project_timesheets`; singular constraint roots `uq_`/`fk_`/`chk_` on the singular entity; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id`/`branch_id` BIGINT scalar; additive `ADD COLUMN`/`ADD CONSTRAINT`). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **Latest shipped migration is `V19__sales_returns.sql` → Projects uses the coordinator-assigned additive range `V64__projects.sql … V68` (V1–V19 FROZEN).** ADR number **0033** (coordinator-assigned for the parallel module build).

This ADR is the **technical data model + integration design** for Projects / Job Costing (PATH-TO-FULL-ERP area 13). It translates the ratified spec into: the three new `projects` tables, the project lifecycle enums, the **nullable `project_id`/`project_task_id` analytical tag** added additively to the posting rows the shipped engines already write, the tag-propagation seams across the outbox (a tagged sale → its COGS leg; a tagged SO/delivery → invoice + COGS), the lightweight issue-materials-to-job path (reusing the ADR-0020 COGS engine), the Project P&L + WIP roll-up read model (a GL `journal_lines` GROUP BY, with the recon bar), the perms / numbering / ScopeGuard / nav routes, the `V64–V68` migration ordering with **#12-safe seeds**, and the ArchUnit edges. It is **concrete enough that the engineer builds without guessing a business rule.** It writes **no production code, no entities, no migration SQL.** Nothing ratified is re-litigated.

## Context

The financial + trading core is complete and posts correctly (revenue/VAT/COGS on sale, AP/GRNI on purchase, COGS at stock-issue), and reports a company-level P&L. What is missing (projects.md §1) is a **job lens**: the ability to slice those same postings by a **project / job** dimension and answer cost / revenue / margin / WIP per job. The forces:

- **THE LOAD-BEARING FORK — the dimension model, given the generic framework is unbuilt (OQ-PROJ-01).** Project costing is fundamentally a **dimensional analytics** concern: a project is one *analysis tag* on a posting, exactly as `journal_lines.branch_id` already is (ADR-0013 D-7 designed branch as a nullable analysis tag, and PATH-TO-FULL-ERP §3.11 explicitly says "GL is dimension-ready … the framework activates it"). The *generic* cost-centre/dimension framework (area 14) — configurable dimensions (department, cost-centre, project) as a `dimensions` + `dimension_values` + a `journal_line_dimensions` link — is the **right long-term home** but is **not built**, and building it speculatively now would be gold-plating that pre-empts area 14's own design. The scope note is explicit: *use the cost-centre/dimension framework; do NOT invent a parallel dimension model.* The resolution is to add the **project dimension specifically** as a nullable scalar column (`project_id`) on the posting rows — the leanest possible realisation of "a project is a dimension" — **shaped so the future framework subsumes it** (the framework reads/migrates `journal_lines.project_id` into a dimension slot additively, never rewriting posted lines). This is not a parallel *model* — it is the **one dimension this slice needs**, on the same row GL already tags with branch. Resolved in **D-1/D-2**.

- **The roll-up must read the GL, not a parallel ledger (BR-PROJ-03/09).** The single source of truth for project money is what the books posted. If the projects module kept its own cost ledger it would drift from the GL and break the recon. So the P&L roll-up is a **`journal_lines` GROUP BY `project_id`** — by construction it ties to the GL (the recon is structural). The source-document tables carry the tag only for **drill-down**, never for the money. Resolved in **D-6**.

- **The tag must reach the GL line on every cost/revenue path — but the math must not change (NFR-PROJ-04, BR-PROJ-02/10).** The tag rides the existing posting on: manual journal (the accountant sets it on the line), supplier bill (the bill line carries it, threaded onto the AP GL leg), stock issue / COGS (the issue carries it, threaded onto the COGS leg), and sales revenue (the invoice carries it, threaded onto the revenue leg). For the event-driven legs (sale COGS, delivery COGS, sale revenue) the tag must **propagate over the outbox** idempotently. The discipline: extend `LineDraft` with a nullable `projectId`/`projectTaskId`, thread the tag from each source line into the draft, and change **nothing else**. An untagged posting is byte-identical to today. Resolved in **D-3/D-4/D-5**.

- **Append-only re-tagging (BR-PROJ-11, OQ-PROJ-04).** A posted journal line is immutable (BR-GL-02). But the project tag is **analytical attribution**, not a financial amount. *Decision: the tag is mutable analytical metadata on the line* (an audited update of `journal_lines.project_id` / source-line `project_id`, never touching debit/credit) — re-tagging is cheap, matches how `branch_id` is treated as analysis, and the recon stays green because the money never moves. Resolved in **D-7**.

- **A lightweight issue-materials-to-job path (FR-PROJ-08).** Issuing stock to an internal project must consume stock + post COGS-at-average tagged to the project, without a customer or an invoice. This **reuses** the ADR-0020 `costIssue` + `InventoryGlPoster`-style COGS post — a new thin service path, not a new engine. Resolved in **D-5**.

- **WIP is reported, not booked (BR-PROJ-07, OQ-PROJ-03).** v1 WIP = cost − billed, floored at zero, **a read-model figure with no WIP journal**. Revenue recognition / booked WIP is deferred. Resolved in **D-6**.

- **Schema freeze / direction.** IAM=V1 … Sales Returns=V19, all frozen. Projects is additive across **V64–V68** (coordinator-assigned for the parallel module build): three new tables, nullable `project_id`/`project_task_id` columns ADDed to `journal_lines` / `supplier_bill_lines` / `sales_invoices` / `sales_invoice_lines` / `stock_movements` (and `sales_orders`/`sales_order_lines`/`deliveries`/`delivery_lines` for propagation), one new movement type (`ISSUE_TO_PROJECT` — or reuse `ADJUSTMENT`; see D-5), the perm seed, and the nav. **No new gl_config key and no new CoA account** for tagging (tagging adds no GL leg) — the issue-to-job path reuses the existing `COGS`/`INVENTORY` keys; an optional `PROJECT_WIP` config key + CoA account is **reserved but NOT created** in v1 (WIP is unbooked — D-6/D-8). The projects module reads GL/Sales/Stock/AP DTOs and posts through the shipped `GLPostingService`/`InventoryGlPoster`; it imports no foreign entity (D-13).

## Decision

### D-1 — Module placement: `com.erp.modules.projects`; the project is a **dimension** realised as a nullable scalar tag (NOT a generic dimension framework, NOT a parallel cost ledger)

A new module **`com.erp.modules.projects`** owns the project master + tasks + timesheets + the roll-up read model + the issue-to-job path. It does **not** own a cost ledger — the money lives in the GL it tags. The project is one **analytical dimension** (OQ-PROJ-01), realised as a **nullable `project_id` scalar column** on the posting rows, mirroring the shipped nullable `journal_lines.branch_id` analysis tag (ADR-0013 D-7). When the generic cost-centre/dimension framework (area 14) lands, it subsumes this column as one dimension (it can read `journal_lines.project_id` directly, or migrate it into a dimension-slot additively — neither rewrites posted lines; NFR-PROJ-06). **We do not build the generic framework here** (gold-plating; pre-empts area 14) and **we do not build a parallel cost-centre model** (the scope prohibition).

Internal layout:

```
com.erp.modules.projects
├── domain.entity   Project, ProjectTask, ProjectTimesheet
├── domain.dto      ProjectDto / CreateProjectRequest / UpdateProjectRequest / ProjectStatusChangeRequest,
│                   ProjectTaskDto / CreateProjectTaskRequest,
│                   ProjectTimesheetDto / CreateTimesheetRequest,
│                   IssueToProjectRequest / IssueToProjectResultDto         (D-5),
│                   ProjectCostingRowDto, ProjectPnlDto, ProjectWipRowDto,   (D-6)
│                   ProjectTag  (record: Long projectId, Long projectTaskId — the tag passed into LineDraft, D-3)
├── domain.enums    ProjectStatus, ProjectCostType                          (D-2/D-6)
├── repository      ProjectRepository, ProjectTaskRepository, ProjectTimesheetRepository,
│                   ProjectCostingQueryRepository  (native GROUP BY over journal_lines, D-6)
└── service         ProjectService(+Impl), ProjectTaskService(+Impl), ProjectTimesheetService(+Impl),
                    ProjectTagResolver        — validates a project/task uid → ids, same-company, ACTIVE (D-3),
                    IssueToProjectService(+Impl) — the issue-materials-to-job path (reuse ADR-0020, D-5),
                    ProjectCostingQuery       — the P&L + WIP roll-up + recon bar (D-6),
                    ProjectNumberGenerator    — PRJ-#### via code_sequence (D-2)
```

Controllers flat in `com.erp.api`: `ProjectController`, `ProjectTaskController`, `ProjectTimesheetController`, `ProjectCostingController`, `IssueToProjectController`. They touch only services (`ModuleBoundaryTest`).

### D-2 — The three project tables + lifecycle enums

All: plural names; `id` BIGINT GENERATED BY DEFAULT AS IDENTITY PK; `uid` VARCHAR(26) ULID `uq_<root>_uid`; `company_id` + `branch_id` BIGINT NOT NULL (tenant; `branch_id` denormalised onto children, set-once); `status` `MasterStatus` soft-delete; `@Version`; standard audit cols. Money `NUMERIC(19,4)`; hours `NUMERIC(9,2)`.

#### `projects` (master)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_project_uid`; URLs address by uid; `ScopeGuard case "project"` (D-9) |
| `company_id` / `branch_id` | BIGINT | NO | tenant; `fk_project_company` / `fk_project_branch` |
| `project_number` | VARCHAR(30) | NO | `PRJ-####`; at create (D-2 numbering); `uq_project_company_number UNIQUE (company_id, project_number)` |
| `name` | VARCHAR(160) | NO | |
| `customer_id` | BIGINT | YES | scalar FK → `customers(id)`; NULL = internal project; `fk_project_customer` |
| `manager_user_id` | BIGINT | YES | scalar FK → `app_users(id)`; `fk_project_manager` |
| `project_status` | VARCHAR(20) | NO | `ProjectStatus`; DEFAULT `'DRAFT'`; `chk_project_status` |
| `planned_start_date` | DATE | YES | |
| `planned_end_date` | DATE | YES | `chk_project_dates CHECK (planned_end_date IS NULL OR planned_start_date IS NULL OR planned_end_date >= planned_start_date)` |
| `budget_amount` | NUMERIC(19,4) | YES | single planned-cost figure (v1); budget-by-type deferred |
| `currency` | VARCHAR(3) | NO | base currency |
| `notes` | VARCHAR(500) | YES | |
| `status` | VARCHAR(32) | NO | `MasterStatus`; DEFAULT `'ACTIVE'` (the soft-delete lifecycle, distinct from `project_status`) |
| `activated_at` / `completed_at` / `cancelled_at` | TIMESTAMPTZ | YES | transition stamps |
| `version` + audit | | | |

Constraints: `chk_project_status CHECK (project_status IN ('DRAFT','ACTIVE','ON_HOLD','COMPLETED','CANCELLED'))`.

**`ProjectStatus`** (FR-PROJ-02): `DRAFT ──activate──▶ ACTIVE ──hold──▶ ON_HOLD ──resume──▶ ACTIVE`; `ACTIVE|ON_HOLD ──complete──▶ COMPLETED` (terminal); any non-terminal `──cancel──▶ CANCELLED` (terminal). Tagging allowed only when `project_status IN ('ACTIVE','ON_HOLD')` (the ON_HOLD allowance is a one-line config, default allow — BR-PROJ-04). Transitions are service-guarded + audited (NFR-PROJ-03).

#### `project_tasks` (child of project; one level in v1)

`id`, `uid` (`uq_project_task_uid`), `project_id` BIGINT FK → `projects(id)` (`fk_project_task_project`), `company_id`/`branch_id` (denormalised), `task_code` VARCHAR(30) (`uq_project_task_code UNIQUE (project_id, task_code)`), `name` VARCHAR(160), `parent_id` BIGINT NULL **reserved** (self-FK `fk_project_task_parent` → `project_tasks(id)`; NULL in v1 — one level; the hierarchy is additive later), `planned_hours` NUMERIC(9,2) NULL, `is_billable` BOOLEAN NOT NULL DEFAULT true, `status` `MasterStatus` DEFAULT `'ACTIVE'`, `@Version`, audit.

#### `project_timesheets` (informational labour estimate, v1 — no GL)

`id`, `uid` (`uq_project_timesheet_uid`), `project_id` FK + `project_task_id` BIGINT NULL FK → `project_tasks(id)`, `company_id`/`branch_id`, `user_id` BIGINT FK → `app_users(id)` (the person), `work_date` DATE, `hours` NUMERIC(9,2) `chk_project_timesheet_hours CHECK (hours > 0)`, `is_billable` BOOLEAN NOT NULL DEFAULT true, `planned_rate_amount` NUMERIC(19,4) NULL (informational), `notes` VARCHAR(255), `status` `MasterStatus`, audit. **Posts no GL in v1** (OQ-PROJ-05) — it drives the planned-rate labour estimate on the project card only.

### D-3 — The project tag: a nullable `project_id`/`project_task_id` added to the posting rows; `LineDraft` carries it; `ProjectTagResolver` validates it

**The tag is two nullable scalar columns** — `project_id BIGINT NULL` + `project_task_id BIGINT NULL` — added additively to:

1. **`journal_lines`** (V64) — **the authoritative tag** (the roll-up reads this). `fk_journal_line_project` / `fk_journal_line_project_task`; nullable; the existing posting math/CHECKs unchanged. This is the exact shape of the shipped nullable `branch_id` analysis tag.
2. **`supplier_bill_lines`** (V65) — the AP cost source-line tag (drill-down + threaded onto the AP GL leg, D-4).
3. **`sales_invoices`** (header default) + **`sales_invoice_lines`** (optional override) (V66) — the revenue source tag (drill-down + threaded onto the revenue GL leg, D-4); OQ-PROJ-07 header-default-with-line-override.
4. **`stock_movements`** (V67) — the stock-issue cost source tag (drill-down + threaded onto the COGS leg, D-5).
5. **`sales_orders`/`sales_order_lines`** + **`deliveries`/`delivery_lines`** (V66) — for **propagation** (a tagged SO/delivery carries the tag to the invoice + the COGS leg without re-tagging — FR-PROJ-07).

**`GLPostingService.LineDraft` gains two additive nullable fields** `Long projectId, Long projectTaskId` (defaulting null — existing callers pass null, byte-identical behaviour). `GLPostingServiceImpl` writes them onto `journal_lines.project_id`/`project_task_id`. **This is the single point where a project tag becomes a fact in the books.** Every cost/revenue path that wants to tag threads its source-line tag into the relevant `LineDraft`.

**`ProjectTagResolver` (in `projects.service`)** — the one validator every tag path calls: `resolve(companyId, projectUid, projectTaskUidOrNull) → ProjectTag(projectId, projectTaskId)`; it (a) resolves uid→id, (b) asserts the project is the **same company** as the posting (BR-PROJ-01), (c) asserts `project_status IN ('ACTIVE','ON_HOLD')` (BR-PROJ-04, else reject per OQ-PROJ-06 — the posting proceeds untagged or the action is rejected, the chosen posture = **reject the tag with a validation error**), (d) asserts the task (if given) belongs to the project. Other modules call this via the projects **service** returning a DTO/record (D-13) — never importing a projects entity.

### D-4 — Threading the tag onto the AP and revenue GL legs (no math change)

**(a) Manual journal (GL).** The `PostJournalRequest` line gains an optional `projectUid`/`projectTaskUid`; `JournalController`→`GLPostingService` resolves via `ProjectTagResolver` and sets `LineDraft.projectId`. (GL gains a `projects.service` read edge — D-13.) The simplest, most general tag path; covers any expense an accountant books.

**(b) Supplier bill (AP).** `supplier_bill_lines.project_id`/`project_task_id` are set when the bill line is entered (the bill-entry UI offers a project picker). `BillMatchServiceImpl.postMatchedBillToGl` (the shipped per-line GL builder) threads each line's `project_id` onto the **goods/expense debit leg** it already builds for that line (the `GRNI`/`PURCHASES` debit) — a one-field add to the `LineDraft` construction, exactly the in-place one-leg edit the ADR-0020 D-4 GRNI swap and ADR-0017 D-7 VAT swap already did in this method. The CR-AP and VAT legs are **not** tagged (they are control/tax, not project cost). AP gains a `projects.service` read edge (to validate the tag at bill-entry; the posting itself just copies the stored line tag — D-13).

**(c) Sales revenue (Sales/GL).** `sales_invoices.project_id` (header) + `sales_invoice_lines.project_id` (optional override) are set at invoice entry / propagated from the SO (D-4d). The **revenue posting** (`SalesPostingHandler` on `SALE.FINALISED` — it re-reads the invoice totals) threads the invoice's project tag onto the **CR Sales Revenue leg(s)** (per-line if line-tagged, else the header tag on the revenue legs). The DR-AR/Cash and CR-VAT legs are **not** tagged. To carry the tag over the outbox, **`SaleFinalisedPayload` gains nullable `projectId`/`projectTaskId`** (additive, defaulting null — the same additive-record-field move ADR-0021 D-6 made for `issuesStock`); `SalesPostingHandler` reads them (or re-reads `sales_invoices.project_id` directly, the D-6 precedent — preferred, since the handler already re-reads the invoice; **decision: re-read from `sales_invoices` to avoid widening the payload for line-level tags**, falling back to the header tag).

**(d) Propagation SO/delivery → invoice + COGS (FR-PROJ-07).** A `sales_orders.project_id` set at SO creation copies to each `sales_order_line.project_id`; on delivery, `delivery_lines.project_id` is copied from the SO line; on invoice-from-delivery (`createFromDelivery`, ADR-0021 D-10), the invoice header/line `project_id` is copied from the SO/delivery. The **COGS leg** at delivery (`DeliveryIssueStockHandler`) threads the delivery line's `project_id` onto the COGS `LineDraft` (D-5). So one tag on the SO flows to both the cost (COGS) and the revenue (invoice) — no re-tagging.

**Invariant (BR-PROJ-02/10):** in every case the tag is added to a leg the engine *already posts*; **no new leg, no amount change**. An untagged posting (null tag) is identical to today (NFR-PROJ-04). Propagation over the outbox is idempotent (`IdempotencyGuard`, BR-PROJ-10).

### D-5 — Issue-materials-to-job path (FR-PROJ-08): reuse the ADR-0020 COGS engine, tagged

`IssueToProjectService.issue(IssueToProjectRequest(companyUid, branchUid, projectUid, projectTaskUidOrNull, List<Line(productUid, qtyInBase)>, issueDate, reason))`, in one transaction, gated `PROJECTS.ISSUE.CREATE`:

1. resolve + validate the project tag (`ProjectTagResolver`).
2. for each line (recipe-explode if composed via `RecipeExplosionResolver`): call `InventoryValuationService.costIssue(companyId, branchId, productId, qty)` (debits `on_hand_value` at current avg, returns issued value or null) and `StockPostingService.post(... −qty ... movementType, sourceDocumentType = "PROJECT_ISSUE", sourceDocumentUid = <issue uid> ... unitCost, value, project_id, project_task_id)`. **Movement type decision:** add a new `stock_movements` type **`ISSUE_TO_PROJECT`** (V67 widens `chk_stock_movement_type` additively) so a project consumption is distinguishable from a sale issue and a manual adjustment in the movement ledger (the cost-type derivation D-6 and any future inter-job transfer want it explicit). (Alternative: reuse `ADJUSTMENT` with a reason — rejected: it conflates a project consumption with a shrinkage/count correction and muddies the COGS-vs-shrinkage account split.)
3. post **DR `COGS` (5100) / CR `INVENTORY` (1300)** at the issued value via `InventoryGlPoster.postCogsInNewTx(...)` (REUSE — REQUIRES_NEW, null-on-anomaly), `sourceType = COGS`, `sourceRef = <issue uid>`, **threading `project_id` onto the DR-COGS leg** (the cost the project P&L reads). Null-avg edge → skip the COGS leg, WARN + anomaly, qty still moves (the ADR-0020 D-2 edge, unchanged).
4. `IssueToProjectResultDto(issueUid, projectUid, lines:[{productUid, qty, value}], cogsGlEntryUid, totalValue, currency)`; audited.

This is a **thin new path**, not a new engine — it reuses `costIssue` + `InventoryGlPoster` verbatim, adding only the project tag and the new movement type. No new gl_config key (uses existing `COGS`/`INVENTORY`).

> **Numbering:** the issue document gets a number from a new `code_sequence` kind `PROJECT_ISSUE` (`PJI-%04d`) at create. Lazy kind (no seed row). The issue itself is recorded only as `stock_movements` rows (sourceDocumentType `PROJECT_ISSUE`, sourceDocumentUid the generated uid) in v1 — **no new issue-header table** (a project material issue is fully described by its tagged COGS movements + journal; a header table is deferred unless the owner wants an editable issue document). The `sourceDocumentUid` ties the movements together for drill-down.

### D-6 — Project P&L + WIP roll-up read model (FR-PROJ-09/10/11, BR-PROJ-05/06/07/09)

Lives in **`projects`** as `ProjectCostingQuery` + `ProjectCostingQueryRepository` (native SQL) + controller `com.erp.api.ProjectCostingController` (gated `PROJECTS.COSTING.VIEW`). It reads **`gl.repository`** for the journal-line aggregate (the leaf-reader-into-gl pattern the ADR-0020 `StockValuationQuery` and ADR-0018 reporting already use — D-13).

- **Revenue / cost aggregate (in SQL — NFR-PROJ-02):** join `journal_lines` (filtered `company_id = ? AND project_id = ?`) to `chart_of_accounts` for `account_type`, GROUP BY `account_type` (and cost-type bucket, and optionally `project_task_id`):
  - **revenue** = Σ `credit_amount − debit_amount` over lines whose account is **INCOME** (credit-normal; a credit is positive revenue);
  - **cost** = Σ `debit_amount − credit_amount` over lines whose account is **EXPENSE** (debit-normal; a debit is positive cost);
  - **margin** = revenue − cost; **margin %** = margin / revenue (guarded for zero).
- **Cost-type breakdown (BR-PROJ-05, OQ-PROJ-02):** bucket each EXPENSE line into `ProjectCostType ∈ {MATERIAL, SUBCONTRACT, LABOUR, OVERHEAD, OTHER}` derived from the **journal `source_type` + account**: `COGS`/`STOCK_RECEIPT`/stock-issue → MATERIAL; AP bill goods leg (account = GRNI/Inventory/Purchases) → MATERIAL, AP service leg → SUBCONTRACT/OVERHEAD by account; payroll (future, `source_type = PAYROLL`) → LABOUR; manual journal → an explicit cost-type the accountant chose at tag time (carried in `journal_lines.line_memo` convention or — cleaner — a nullable `project_cost_type VARCHAR(20)` on `journal_lines`, V64; **decision: add the nullable `project_cost_type` column** so the bucket is explicit and not parsed from a memo), else OTHER. The ADR fixes this derivation; the engineer implements the mapping in `ProjectCostingQuery`.
- **WIP (BR-PROJ-07, OQ-PROJ-03):** `wip = max(0, cost − billedRevenue)` per project, where `billedRevenue` = the revenue figure above (Σ tagged INCOME credits = what has been invoiced to the project). Floored at zero (over-billing → recognised margin, not negative WIP). **No WIP journal is posted** (v1 — WIP is a reported figure). A `PROJECT_WIP` gl_config key + a CWIP-style asset account are **reserved for the deferred revenue-recognition slice** but NOT created in v1 (D-8).
- **Budget variance (BR-PROJ-08):** `budgetVariance = project.budget_amount − cost`; `% spent = cost / budget_amount` (guarded).
- **DTOs:** `ProjectCostingRowDto(costType, amount)`; `ProjectPnlDto(projectUid, projectNumber, name, customerUid, revenue, totalCost, List<ProjectCostingRowDto> costByType, margin, marginPct, budget, budgetVariance, wip, recon, currency)` where `recon = ReconciliationDto.of("Project P&L vs GL tagged lines", computed=read-model totals, expected=GL Σ by account-type)`; `ProjectWipRowDto(projectUid, projectNumber, costIncurred, billed, wip, currency)`.
- **Recon (FR-PROJ-11, BR-PROJ-09):** because the roll-up **is** a GL query, the recon is structural — the bar exists as the same finance-grade self-check the inventory valuation / AR / AP recons use; a red bar is a bug. Untagged postings are correctly excluded (the company P&L still has everything; Σ project P&Ls ≤ company P&L, difference = untagged activity — surfaced as an "Unassigned" pseudo-bucket in the WIP/cost report if the owner wants it).
- `assertCanActIn(principal, principal.companyId())` on every read (NFR-PROJ-01); per-company scope.

### D-7 — Re-tag of a posted line: the project tag is mutable analytical metadata (audited), not a reversing posting (BR-PROJ-11, OQ-PROJ-04)

**Decision: the `project_id`/`project_task_id`/`project_cost_type` columns are analytical attribution metadata the `ProjectTagService.retag(...)` may update with full audit — the financial amounts (debit/credit) are NEVER touched.** Re-tagging a posted `journal_line` (and the mirrored source-document line) from project A to project B is an **audited metadata UPDATE** of the tag columns only, gated `PROJECTS.TAG.MANAGE`. This is consistent with treating the tag as a reporting dimension (like `branch_id`), keeps re-tagging cheap, and the recon stays green because no money moves. The append-only rule (BR-GL-02) governs **financial** mutation (debit/credit/account/date) — it is not violated by re-attributing an analysis dimension, exactly as a future correction of a mistyped `branch_id` analysis tag would be. The retag is fully audited (actor, before/after project, affected line uid) per NFR-PROJ-03. (Alternative — reverse-and-re-post the tagged legs — rejected: it doubles the journal volume for a pure attribution fix and is the heavyweight tool for a reporting-dimension correction; reserved only if the owner later books WIP as a real asset, where the tag drives a posted amount.)

### D-8 — GL config keys + CoA accounts: NONE new in v1 (tagging adds no GL leg)

**No new `gl_config` key and no new CoA account are created in v1.** The project tag adds no GL leg — it rides legs the shipped engines already post (revenue via `SALES_REVENUE`/`4100`, cost via `COGS`/`5100`, AP via `GRNI`/`PURCHASES`, inventory via `INVENTORY`/`1300`). The issue-to-job path reuses `COGS`/`INVENTORY` (D-5). **Reserved (NOT created in v1):** a `PROJECT_WIP` config key → a CWIP-style asset account (e.g. `1350 Project Work-in-Progress`, ASSET) for the **deferred** revenue-recognition / booked-WIP slice (D-6). Documented here so the future slice claims those names; v1 seeds neither. **This means V64–V68 add zero per-company CoA/gl_config seed rows → no #12-vulnerable per-company seed-uid in this slice** (D-10).

### D-9 — Permissions + ScopeGuard

New permissions (V68 seed + `ORG_ADMIN` grant, the V7/V12/V14/V17 pattern), module `projects`:

| code | description |
|---|---|
| `PROJECTS.PROJECT.VIEW` | View projects + project detail |
| `PROJECTS.PROJECT.CREATE` | Create a project |
| `PROJECTS.PROJECT.MANAGE` | Edit a project + change its status (activate/hold/complete/cancel) |
| `PROJECTS.TASK.VIEW` | View project tasks |
| `PROJECTS.TASK.MANAGE` | Create / edit / deactivate project tasks |
| `PROJECTS.TIMESHEET.VIEW` | View project timesheets |
| `PROJECTS.TIMESHEET.RECORD` | Record project timesheet hours |
| `PROJECTS.TAG.MANAGE` | Attach / detach / re-tag a project tag on a posting (D-7) |
| `PROJECTS.ISSUE.CREATE` | Issue materials from stock to a project (D-5) |
| `PROJECTS.COSTING.VIEW` | View the project P&L / WIP / job-cost roll-up |

`@perm.has('PROJECTS.PROJECT.CREATE')` etc. on the controller methods (NEVER `hasAuthority`). Tag attachment **at point of entry** on a *foreign* document (a supplier-bill line, a journal line, an invoice) is gated by **that document's** own create/post permission **plus** `PROJECTS.TAG.MANAGE` (the projects-side gate to attach a project dimension) — the engineer adds `@perm.has('PROJECTS.TAG.MANAGE')` to the tag-bearing operations, or treats the tag as an attribute of the existing gated operation (the simpler posture — **decision: the tag rides the existing document permission; `PROJECTS.TAG.MANAGE` gates only the standalone re-tag operation D-7**). `assertCanActIn` guards every projects read + the issue + the re-tag.

**ScopeGuard** — two new `case` entries in the `companyIdOf(targetType, uid)` switch:
- `case "project"        -> projects.findCompanyIdByUid(uid);`
- `case "projecttask"    -> projectTasks.findCompanyIdByUid(uid);`

(The timesheet is addressed via its project; no separate case needed unless a uid-addressed timesheet endpoint is exposed — if so, add `case "projecttimesheet"`.)

### D-10 — Migration ordering (additive; V1–V19 FROZEN; #12-safe; range V64–V68)

The coordinator assigns Projects the range **V64–V68**. Split by the table/column it touches (each additive; never edits V1–V19):

1. **`V64__projects.sql`** — CREATE `projects`, `project_tasks`, `project_timesheets` (+ all constraints/indexes per D-2); **ALTER `journal_lines`** `ADD COLUMN project_id BIGINT NULL`, `ADD COLUMN project_task_id BIGINT NULL`, `ADD COLUMN project_cost_type VARCHAR(20) NULL` + `fk_journal_line_project`/`fk_journal_line_project_task` + `chk_journal_line_project_cost_type CHECK (project_cost_type IS NULL OR project_cost_type IN ('MATERIAL','SUBCONTRACT','LABOUR','OVERHEAD','OTHER'))`; indexes `ix_journal_lines_company_project ON journal_lines (company_id, project_id) WHERE project_id IS NOT NULL` (partial — the roll-up working set, NFR-PROJ-02) + `ix_projects_company` / `ix_project_tasks_project`.
2. **`V65__projects_ap_tags.sql`** — **ALTER `supplier_bill_lines`** `ADD COLUMN project_id BIGINT NULL` + `project_task_id BIGINT NULL` + FKs (`fk_supplier_bill_line_project` etc.); index `ix_supplier_bill_lines_project (company_id, project_id) WHERE project_id IS NOT NULL`.
3. **`V66__projects_sales_tags.sql`** — **ALTER `sales_invoices`** + `sales_invoice_lines` + `sales_orders` + `sales_order_lines` + `deliveries` + `delivery_lines` each `ADD COLUMN project_id BIGINT NULL` (+ `project_task_id` where line-grained) + FKs; partial `(company_id, project_id)` indexes on `sales_invoices` + `sales_invoice_lines` (the revenue roll-up + propagation reads).
4. **`V67__projects_stock_tags.sql`** — **ALTER `stock_movements`** `ADD COLUMN project_id BIGINT NULL` + `project_task_id BIGINT NULL` + FKs; **widen `chk_stock_movement_type`** via `DROP/ADD CONSTRAINT` to add `'ISSUE_TO_PROJECT'` (keep all existing tokens — the V17/additive-widen pattern); index `ix_stock_movements_project (company_id, project_id) WHERE project_id IS NOT NULL`.
5. **`V68__projects_permissions.sql`** — INSERT the ten `PROJECTS.*` permissions (module `projects`) `ON CONFLICT (code) DO NOTHING`; grant all to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (the V7/V12/V14/V17 grant pattern). (Permissions have no `uid` — #12 N/A.)

**No new CoA account, no new gl_config key, no per-company seed row** (D-8) → **V64–V68 have no #12-vulnerable per-company seed-uid** (the only CROSS-JOIN seed is the uid-less permission grant). The `code_sequence` kinds `PROJECT` (`PRJ-%04d`) + `PROJECT_ISSUE` (`PJI-%04d`) are **lazy** (created on first use by `ProjectNumberGenerator` — no seed). `MigrationKeepDataIT` extends to V68 (the additive nullable columns + the `chk_stock_movement_type` widen are keep-data-safe; existing rows back-fill `project_id = NULL` — correct, untagged). `JournalSourceType` (Java) is **unchanged** — tagging adds no new source type; the issue-to-job COGS reuses `COGS`. `chk_journal_entry_source_type` / `chk_journal_batch_source_type` are **not** widened.

### D-11 — Tag propagation events: no NEW DomainEventType is required for tagging

Tagging rides the **existing** events: the revenue tag is read by `SalesPostingHandler` on the existing `SALE.FINALISED` (D-4c, re-read from `sales_invoices.project_id`); the COGS tag is read by `SaleIssueStockHandler` / `DeliveryIssueStockHandler` on the existing `SALE.FINALISED` / `DELIVERY.CONFIRMED` (D-4d/D-5 — re-read the tag from the invoice / delivery line). **No new `DomainEventType` constant is introduced for tag propagation** — the handlers already re-read their source documents, so the tag is read from the (now project-tagged) `sales_invoices` / `delivery_lines` rows. The issue-to-job COGS is posted **synchronously in the issue TX** via `InventoryGlPoster.postCogsInNewTx` (no event needed — it is a single-step operator action, the ADR-0020 adjustment precedent). **One new event is OPTIONAL and recommended for analytics/audit symmetry but NOT required for v1 costing:** `PROJECT.MATERIAL.ISSUED` (payload `ProjectMaterialIssuedPayload(issueUid, projectUid, companyId, branchId, issuedAt, lines)`) emitted by `IssueToProjectService` for downstream consumers (none in v1). **Decision: introduce `PROJECT.MATERIAL.ISSUED` as a reserved constant + emit it** (cheap, future-proofs notifications/analytics), with **no v1 consumer** — it is the only new `DomainEventType` value, and it is non-load-bearing (the costing works without any consumer). If the coordinator wants zero new event types in this slice, drop the emit — costing is unaffected.

### D-12 — ArchUnit module-edge rules (no cycle)

- **`projects.service` → `gl.service`/`gl.repository`/`gl.domain.dto`/`gl.domain.enums`** — the roll-up reads `JournalLineRepository` aggregates + `chart_of_accounts.account_type`; the manual-journal tag path resolves via `GLPostingService`. **Allowed** — the same leaf-reader-into-gl stance ADR-0020 `StockValuationQuery` and ADR-0018 reporting take (the documented `ModuleBoundaryTest` allowance).
- **`projects.service` → `stock.service`** (`StockPostingService` / `InventoryValuationService` / `InventoryGlPoster` for the issue-to-job path) + **`products`** (recipe explosion / product DTO reads). **Allowed** — the ADR-0021 `sales → stock` precedent.
- **`gl.service` → `projects.service`** (`ProjectTagResolver`, for the manual-journal tag validation) — a **read-only validation call returning a record** (`ProjectTag`), GL imports no projects entity. **`ap.service` → `projects.service`** and **`sales.service` → `projects.service`** likewise (validate the tag at bill/invoice entry). These are NEW edges **into** `projects`. **Cycle check:** `projects → gl` (roll-up read) AND `gl → projects` (tag validate) would be a **cycle** if both are *compile-time module* dependencies. **Resolution: the tag-validation contract (`ProjectTagResolver` interface + `ProjectTag` record) lives in a dependency-safe location** — either (a) `projects.domain.dto` (a DTO-only edge, the allowed cross-module shape — GL/AP/Sales import `projects.domain.dto`, not `projects.service` impl), with `ProjectTagResolver` as an interface in `projects.domain.dto` and the impl in `projects.service`; or (b) the foreign modules store the **raw `project_id` already resolved by the projects controller/service at the document-entry API call** and the posting paths just copy the stored scalar (no call into projects at post time). **Decision: (b) for the posting paths + (a) for entry-time validation** — at *document entry* (bill line, invoice, journal), the foreign module calls `projects.domain.dto.ProjectTagResolver` (a DTO/interface edge, acyclic) to validate + resolve the uid→id; it then **stores the scalar `project_id`** on its own row; at *post time* the posting path copies the stored scalar onto the `LineDraft` with **no call into projects** (no runtime edge). The roll-up's `projects → gl` read is the only service-level edge, and it is one-directional. **No cycle.** (The engineer places `ProjectTagResolver` + `ProjectTag` in `projects.domain.dto` so the foreign-module edge is the allowed DTO edge, mirroring how `stock.events` imports `sales.domain.dto`.)
- The shipped `ModuleBoundaryTest` enforces controller↛repository, service↛controller, audit-append-only — none of these edges violates an active rule; the projects-edge allowances are documented here (ADR-0020 D-12 / ADR-0021 D-13 precedent for documenting cross-module read/post edges).

### D-13 — Cross-module touch-points (the coordination list)

1. **GL** — `LineDraft` gains nullable `projectId`/`projectTaskId`; `GLPostingServiceImpl` writes them onto `journal_lines`; `PostJournalRequest` line gains optional `projectUid`/`projectTaskUid` + `projectCostType`; the manual-journal path validates via `projects.domain.dto.ProjectTagResolver`. (V64 adds the `journal_lines` columns.)
2. **AP / Purchases** — `supplier_bill_lines.project_id` set at bill entry (validated via `ProjectTagResolver`); `BillMatchServiceImpl.postMatchedBillToGl` threads the line's stored `project_id` onto its goods/expense debit leg (one-field add, the GRNI/VAT-swap precedent). (V65.)
3. **Sales** — `sales_invoices`/lines + `sales_orders`/lines + `deliveries`/lines gain `project_id`; `SalesPostingHandler` re-reads `sales_invoices.project_id` and threads it onto the revenue legs; the SO→delivery→invoice propagation copies the tag; `createFromDelivery` copies it. (V66.)
4. **Stock** — `stock_movements.project_id` + the new `ISSUE_TO_PROJECT` movement type; `DeliveryIssueStockHandler`/`SaleIssueStockHandler` thread the source line's `project_id` onto the COGS `LineDraft`; the issue-to-job path posts a project-tagged COGS via `InventoryGlPoster`. (V67.)
5. **Platform** — `ScopeGuard` gains `case "project"`/`case "projecttask"`; the perm seed + `ORG_ADMIN` grant (V68); optional `PROJECT.MATERIAL.ISSUED` event constant (D-11).
6. **Web** — the nav routes (D-14).

### D-14 — Angular nav routes

Under a new top-level **Projects** nav group (gated `PROJECTS.PROJECT.VIEW`):

- `/projects` — project list (filter by status / customer / manager; budget vs actual).
- `/projects/new` — create project.
- `/projects/:uid` — project detail (header + tasks + tagged-transactions drill-down + the P&L/WIP card).
- `/projects/:uid/tasks` — task management.
- `/projects/:uid/costing` — the Project P&L / job-cost card (revenue, cost-by-type, margin, budget variance, WIP, recon bar).
- `/projects/:uid/timesheets` — timesheet capture (informational).
- `/projects/issue-to-job` — the issue-materials-to-job screen (`PROJECTS.ISSUE.CREATE`).
- `/projects/wip` — the cross-project WIP report (`PROJECTS.COSTING.VIEW`).

The project picker is also surfaced **inline** on the existing bill-entry, manual-journal, and invoice/SO screens (a nullable project field) — a small additive control on shipped screens, not a new route.

## Consequences

**Positive**
- A full job-costing lens ships **without a parallel ledger and without changing any posting math**: the project is one nullable analytical tag on the rows the shipped engines already write, and the P&L is a `journal_lines` GROUP BY `project_id`. The recon (`Σ tagged lines == read-model totals`) is structural — finance-grade by construction (BR-PROJ-09).
- The change is additive and surgical: 3 new tables, nullable tag columns on 8 existing tables (`journal_lines` authoritative + 7 source-document tables for drill-down/propagation), 1 new movement type, 1 `LineDraft` field, a thin issue-to-job path reusing the ADR-0020 engine, a read-model query, 10 perms, 2 ScopeGuard cases, nav. **V1–V19 frozen.**
- It does **not** pre-empt the generic cost-centre/dimension framework (area 14): the project tag is shaped to be subsumed as one dimension when the framework lands (NFR-PROJ-06), and no parallel dimension model is invented (the scope prohibition is honoured).
- Untagged workflows are byte-identical to today (NFR-PROJ-04) — zero regression risk to the financial core.

**Negative / costs**
- The tag touches five modules (gl, ap, sales, stock, platform) + web in one slice — the widest cross-module touch list of the costing modules. Mitigated: every touch is a **nullable additive column** + a **one-field thread onto an existing leg** (no new posting), and the entry-time validation goes through a **DTO-level `ProjectTagResolver`** so no service-level cycle forms (D-12).
- The cost-type derivation (D-6) is a mapping from `source_type` + account that must be implemented exactly; a mis-bucketed cost is a reporting (not a books) defect, but it confuses the P&L. The mapping is specified (OQ-PROJ-02) and testable.
- WIP is a **reported figure, not a booked asset** (v1) — a reader expecting WIP on the balance sheet must understand it is unbooked until the deferred revenue-recognition slice. Documented (BR-PROJ-07, D-6/D-8); the `PROJECT_WIP` key + `1350` account are reserved for that slice.
- Re-tagging a posted line mutates analytical metadata in place (D-7) — a deliberate exception to append-only that is **safe because no money moves** and is fully audited; a future reader must not "fix" it into a reversing posting unless WIP becomes a booked amount.

**Neutral / deferred**
- Revenue recognition / milestone billing / POC / ASC-606, budget-by-type + budget-control gating, multi-level WBS, Gantt/scheduling/utilisation, payroll→project labour posting, multi-currency / consolidated projects — all deferred (projects.md §2), none precluded. The model (tasks + dates + planned hours + the tag + the reserved `PROJECT_WIP`) is the foundation those build on.

## Alternatives considered

- **The dimension model — project-specific nullable column vs build the generic cost-centre/dimension framework now vs a parallel project cost ledger.** *Decided: project-specific nullable `project_id` column on the posting rows (mirroring `branch_id`).* Building the generic framework now is gold-plating that pre-empts area 14's own design (and the scope note forbids inventing a parallel dimension model). A parallel project cost ledger duplicates the GL, drifts from it, and breaks the recon (BR-PROJ-03). The nullable column is the lean realisation of "a project is a dimension," shaped to fold into the future framework (NFR-PROJ-06). This is the OQ-PROJ-01 recommended default.
- **The roll-up source — read the GL `journal_lines` vs maintain a projects-owned actuals ledger.** *Decided: read the GL.* The GL is what the books posted; reading it makes the project P&L a filtered company P&L and the recon structural. A projects-owned ledger needs its own write on every tagged posting (a second source of truth) and a reconciliation job. Rejected.
- **Re-tag of a posted line — mutable metadata vs reverse-and-re-post.** *Decided: mutable analytical metadata, audited* (the tag is attribution, not money; no financial mutation; recon stays green; cheap). Reverse-and-re-post doubles journal volume for a pure attribution fix and is the wrong tool unless WIP is booked. Reserved for that future case. (OQ-PROJ-04.)
- **Issue-to-job — new movement type `ISSUE_TO_PROJECT` vs reuse `ADJUSTMENT`.** *Decided: new type* — a project consumption is semantically distinct from a shrinkage/count-correction (`ADJUSTMENT` posts to `STOCK_ADJUSTMENT`/5160, a project issue posts to `COGS`/5100), and the cost-type derivation + future inter-job transfer want it explicit. Reusing `ADJUSTMENT` conflates the two and the GL account. Rejected.
- **WIP — reported figure vs booked asset (DR WIP / CR …).** *Decided: reported figure only in v1* (BR-PROJ-07, OQ-PROJ-03) — booking WIP is revenue recognition (deferred); a reported cost-minus-billing figure answers the management question without a new posting path, and the `PROJECT_WIP` key/account are reserved for when recognition lands.
- **Tag propagation — re-read source vs widen the event payload.** *Decided: re-read the source document* (the handlers already re-read the invoice/delivery; `sales_invoices.project_id` / `delivery_lines.project_id` carry the tag) — this supports line-level tags without bloating the payload, the ADR-0013 D-6 re-read precedent. A widened payload (the ADR-0021 `issuesStock` move) is the fallback if a handler ever stops re-reading.

## Open items (OQ-PROJ — recommended defaults adopted; none blocks the build)

- **OQ-PROJ-01 (LOAD-BEARING) — dimension model:** adopted **project-specific nullable `project_id`/`project_task_id`/`project_cost_type` on the posting rows** (not a generic framework, not a parallel ledger), shaped to fold into the future cost-centre/dimension framework (area 14). Settled — the decision this ADR makes (D-1/D-2). *Owner ratification of the requirements doc confirms.*
- **OQ-PROJ-02 — cost-type derivation:** adopted **source_type + account → MATERIAL/SUBCONTRACT/LABOUR/OVERHEAD/OTHER**, with an explicit `project_cost_type` on manual-journal lines. Settled (D-6).
- **OQ-PROJ-03 — WIP definition + flooring + GL:** adopted **cost − billed, floored at zero, reported-only (no WIP journal)**; `PROJECT_WIP` key/`1350` account reserved for the deferred recognition slice. Settled (D-6/D-8).
- **OQ-PROJ-04 — re-tag of a posted line:** adopted **mutable analytical metadata, audited** (no financial mutation). Settled (D-7).
- **OQ-PROJ-05 — labour cost source:** adopted **timesheets informational in v1; actual labour cost = tagged GL postings only; payroll tagging folds in later.** Settled.
- **OQ-PROJ-06 — tag against a closed project:** adopted **reject the tag (validation error); the cost still posts untagged** — the company books are never blocked by a project-status problem. Settled (D-3).
- **OQ-PROJ-07 — revenue tag granularity:** adopted **header default + optional per-line override** on the invoice (mirrors the SO discount pattern). Settled (D-3/D-4).
- **OQ-PROJ-08 (deferred):** milestone/POC recognition, budget-control gating, multi-level WBS, Gantt, utilisation, multi-currency — deferred (projects.md §2), none precluded.

---

## Summary

ADR-0033 designs **Projects / Job Costing** as a **costing dimension** in `com.erp.modules.projects`: three new tables (`projects`, `project_tasks`, `project_timesheets`), a project lifecycle (`ProjectStatus`), and a nullable **`project_id`/`project_task_id`/`project_cost_type` analytical tag** added additively to the posting rows the shipped engines already write — **authoritatively on `journal_lines`** (the roll-up source) and on `supplier_bill_lines` / `sales_invoices`+lines / `sales_orders`+lines / `deliveries`+lines / `stock_movements` for drill-down + propagation. `GLPostingService.LineDraft` gains nullable `projectId`/`projectTaskId`; every cost/revenue path threads its source-line tag onto the leg the engine **already posts** — **no new GL leg, no math change, untagged = byte-identical to today**. A thin **issue-materials-to-job** path (new `ISSUE_TO_PROJECT` movement type) reuses the ADR-0020 `costIssue` + `InventoryGlPoster` COGS engine, project-tagged. The **Project P&L + WIP** read model is a `journal_lines` GROUP BY `project_id` (revenue = Σ tagged INCOME credits, cost = Σ tagged EXPENSE debits by cost-type, margin, budget variance, WIP = max(0, cost − billed), unbooked) with a structural `ReconciliationDto` self-check. Re-tagging a posted line is an **audited metadata update** (the tag is attribution, not money — append-only governs financial mutation only). The **dimension fork (OQ-PROJ-01)** is settled: ship the **project dimension specifically** (a nullable scalar column mirroring `branch_id`), shaped to fold into the future generic cost-centre/dimension framework — **no parallel dimension model invented**. **No new gl_config key or CoA account in v1** (`PROJECT_WIP`/`1350` reserved for the deferred recognition slice), so **V64–V68 carry no #12-vulnerable per-company seed-uid** (the only CROSS-JOIN seed is the uid-less permission grant). **Additive on frozen V1–V19** across `V64__projects.sql` (tables + `journal_lines` tag) / `V65` (AP tag) / `V66` (sales tags) / `V67` (stock tag + movement-type widen) / `V68` (permissions). **No service-level module cycle** — entry-time tag validation goes through the DTO-level `projects.domain.dto.ProjectTagResolver` (the allowed DTO edge) and post-time paths copy the stored scalar (no runtime call into projects), while the roll-up's `projects → gl` read is the only service edge (D-12). **Cross-module touch list:** gl (`LineDraft` + `journal_lines` columns), ap (bill-line tag + the goods-leg thread), sales (invoice/SO/delivery tags + revenue-leg thread + propagation), stock (`stock_movements` tag + `ISSUE_TO_PROJECT` + COGS-leg thread), platform (ScopeGuard `project`/`projecttask` + perms + the optional `PROJECT.MATERIAL.ISSUED` event), web (nav).
