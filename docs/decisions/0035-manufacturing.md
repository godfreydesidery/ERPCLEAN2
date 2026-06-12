# 0035 — Manufacturing / Production data model: a **work order** (header + component lines + optional operations) in a new `com.erp.modules.manufacturing` module that drives the lifecycle PLANNED→RELEASED→IN_PROGRESS→COMPLETED→CLOSED (+CANCELLED), explodes the **ADR-0026 multi-level BOM** to its leaf components, **issues those components out of stock at moving-average cost via the shipped ADR-0020 valuation engine** (`StockPostingService.post` + `InventoryValuationService.costIssue`) posting **DR WIP / CR Inventory**, holds the running cost as **Work-In-Progress**, **receives the finished good back into stock at the computed unit cost** (rolled-up component cost + optional flat labour/overhead ÷ good quantity) via `recomputeOnReceipt` posting **DR Inventory(finished) / CR WIP**, and clears residual WIP to a **Manufacturing Variance** account at close — all synchronous in the operator's command through `GLPostingService`, period-gated, additive as `V74__manufacturing.sql … V80` on the frozen V1–V19 + the in-flight V20–V73

- **Status:** Accepted
- **Date:** 2026-06-12
- **Deciders:** solutions-architect (consuming the architect-authored, owner-style requirements in [docs/requirements/manufacturing.md](../requirements/manufacturing.md) — FR-MFG-01..15, BR-MFG-01..12, NFR-MFG-01..08, §6 flows, §11 OQ log. The recommended defaults in §11 are adopted as the decisions of this ADR; the load-bearing OQs — finished-goods account reuse-vs-distinct (OQ-MFG-01), WIP-relief / cost-per-good arithmetic (OQ-MFG-02), and synchronous-vs-event-driven posting (OQ-MFG-09) — are resolved here, not deferred.)
- **Context source:** [manufacturing.md](../requirements/manufacturing.md) (the ground truth for every rule below). Verified against the **shipped / on-`develop`** code:
  - **Multi-level BOM** ([ADR-0026](0026-products-bom.md) / V30 — BUILT, on `develop`): `products.service.BomExplosionService.explode(BomExplosionRequest{parentUid|bomUid, outputQty, branchUid?, multiLevel, asOfDate?, withCost?})→BomExplosionResultDto{ tree, leaves: List<BomExplosionLeafDto>, costRollUp?, incompleteCostLeaves }` (the recursive explode-to-all-levels + flattened **leaf summary** of net component requirement, scrap/yield compounded per level, max-depth bounded); `BomCostRollUpService.rollUp(parentUid|bomUid, branchUid, outputQty)→BomCostRollUpDto{ standardCostAmount, currency, complete, incompleteLeaves }`; `BomDto`/`BomComponentDto`; `BomRepository.findActiveByParentProductId(...)` + `findByUid`. Manufacturing reads these **by DTO/service** (`manufacturing → products`), no back-edge.
  - **Inventory Valuation & COGS** ([ADR-0020](0020-inventory-valuation-data-model.md) / V17): `StockOnHand.avg_cost` NUMERIC(19,4) nullable + `on_hand_value` NUMERIC(19,4); `StockMovement` with `unit_cost_amount`/`value_amount` (the cost-at-movement that makes reversals exact); `StockPostingService.post(companyId, branchId, [locationId,] productId, quantity, MovementType, sourceEventUid, sourceDocumentType, sourceDocumentUid, reasonCode, note, occurredAt, actorId, unitCostAmount, valueAmount [, costCentreValueId, departmentValueId])` (the single quantity primitive, `@Transactional(MANDATORY)`, optimistic-lock upsert + one retry); `InventoryValuationService.recomputeOnReceipt(companyId, branchId, productId, receiptQty, receiptCost)→BigDecimal` (the weighted-average recompute, returns receipt value), `costIssue(companyId, branchId, productId, issuedQty)→BigDecimal|null` (debit on-hand value at current `avg_cost`, null when `avg_cost` not established — the COGS-leg-skip edge), `reverseIssue(...)` / `reverseReceipt(...)` (exact-cost reversal); the `MovementType` enum (`GOODS_RECEIPT, SALE_ISSUE, SALE_REVERSAL, GOODS_RECEIPT_REVERSAL, ADJUSTMENT, OPENING_BALANCE` admitted by `chk_stock_movement_type`; `TRANSFER_OUT/IN` reserved-but-excluded). **Manufacturing reuses this engine wholesale (NFR-MFG-02) — no second moving-average implementation.**
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / V10): `GLPostingService.post(JournalEntryDraft)→JournalEntryDto` (validates ≥2 lines / balance / OPEN period / active accounts / base currency) + `postReversal(originalEntryUid, ...)`; `JournalEntryDraft(companyId, branchId, postingDate, description, JournalSourceType, sourceRef, reversalOfId, postedBy, List<LineDraft>)` + `LineDraft(accountId, debit, credit, currency, lineMemo [, costCentreValueId, departmentValueId, dimension3ValueId, dimension4ValueId])` (the 5-arg ctor defaults the four dimension ids to null — ADR-0025 D-4); `GLConfigResolver.resolve(companyId, GlConfigKey)→ChartOfAccount` (throws on missing/inactive — BR-GL-10); `GLPostingSafeInvoker.postInNewTx(draft)` (REQUIRES_NEW, null-on-anomaly — for event-driven legs only); `FiscalPeriodResolver.resolveOpen(companyId, postingDate)` (the period gate); the `GlConfigKey` enum (the shipped + V20–V73 keys — see D-7 collision check); `JournalSourceType` (the shipped + V20–V73 tokens — see D-7); `ChartOfAccountServiceImpl.DEFAULT_ACCOUNTS` (1000–5400 + 2150 GRNI + 5160 Stock-Adj) + `GlConfigServiceImpl.DEFAULT_MAPPINGS` + the per-module GL seeder pattern (`InventoryGlSeeder`/`ApGlSeeder`/`FixedAssetGlSeeder`).
  - **Cost-centre dimension** ([ADR-0025](0025-cost-centre.md) / V27–V29): `LineDraft` carries the four nullable `*ValueId` dimension slots; `StockPostingService.post` carries the `costCentreValueId`/`departmentValueId` overload. Manufacturing passes a nullable WO cost-centre through (D-9).
  - **Approvals** ([ADR-0022](0022-approvals.md) / V20–V22): the soft gate — Manufacturing ships a permission gate (`WORKORDER.RELEASE`), not a hard dependency (the procurement-PO precedent in [wave2-build-coordination.md](wave2-build-coordination.md)).
  - **Security spine**: `@perm.has` / `@perm.scoped` (`PermissionChecks`), `ScopeGuard.companyIdOf` target-type switch + `assertCanActIn` (the #1 anti-regression read-path guard), `code_sequence` row-locked numbering (`entity_kind` discriminator, ADR-0007 D-6), the transactional outbox + `IdempotencyGuard` (ADR-0009), `Money` (ADR-0005 — base currency, HALF_UP).
  - [[db-naming-convention]] verified against V1–V19 + the V20–V73 ADRs (plural masters/owned-children `work_orders`/`work_order_components`/`work_order_operations`; singular constraint roots `uq_`/`fk_`/`chk_` on the singular entity; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id` BIGINT scalar denormalised onto children; `@Version version BIGINT NOT NULL DEFAULT 0` on **every** UidEntity table; additive `CREATE`/`ALTER`). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **This module is assigned `V74–V80` + ADR `0035` by the coordinator** ([wave2-build-coordination.md](wave2-build-coordination.md)); V1–V19 FROZEN, V20–V73 are the in-flight Wave-2 range (never edited).

This ADR is the **technical data model + integration design** for Manufacturing / Production (ROADMAP §3.6, PATH-TO-FULL-ERP area 9 — Phase C, the module the multi-level-BOM + valuation/COGS gates were built for). It translates the ratified spec into: the three new tables (`work_orders` header + `work_order_components` lines + `work_order_operations`) in `com.erp.modules.manufacturing`, the work-order lifecycle enums + transitions, the BOM-driven component-issue orchestration (reusing `BomExplosionService` + the ADR-0020 valuation engine), the WIP/finished-goods/variance GL postings with their `GlConfigKey`s + `JournalSourceType` tokens + new CoA accounts, the two new `MovementType` values, the API surface, the perms + `ScopeGuard` cases, the Angular nav routes, the ArchUnit edges, the two new `DomainEventType` values, and the `V74–V80` migration ordering with #12-safe seeds. It is **concrete enough that the backend engineer writes the migrations + the work-order entities + the lifecycle service + the costing orchestrator + the GL poster + the WIP recon report without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. Nothing ratified is re-litigated.

## Context

The platform can describe what a product is made of (multi-level BOM, ADR-0026) and cost what it would cost (the standard-cost roll-up), and it can cost **purchases** (goods receipt → moving-average recompute) and **sales** (COGS at issue) through the ADR-0020 engine — but there is **no act of production**. A made good never acquires an `avg_cost`, so it cannot be sold at a correct COGS; the balance sheet shows raw materials but no work-in-progress and no finished-goods value. Manufacturing adds the **work order**: the document that consumes the BOM's leaf components out of stock at their moving-average cost into WIP, then receives the finished good back into stock at the cost that accumulated in WIP. The forces:

- **Reuse the valuation engine and the explosion — do not reimplement either (NFR-MFG-02 — the top design risk).** ADR-0020 owns the moving-average (`recomputeOnReceipt`, `costIssue`, the exact-cost reversal) and ADR-0026 owns the explosion (`BomExplosionService`). If Manufacturing writes its own cost recompute or its own BOM walk, the system has **two definitions of cost** and **two definitions of structure** — guaranteed drift between what a production run costs and what a sale's COGS uses. Manufacturing **calls** `StockPostingService.post(... PRODUCTION_ISSUE/RECEIPT ...)` + `InventoryValuationService.*`, and **calls** `BomExplosionService.explode(...)`. It adds production *orchestration* and production *GL postings*, not a costing engine. Resolved in **D-2 / D-5 / D-6**.

- **WIP is a real booked asset that must net to zero per closed order (BR-MFG-05 — the structural invariant).** Component issue debits WIP; applied labour/overhead debit WIP; the finished-goods receipt credits WIP; close clears the residual to Manufacturing Variance. The chief acceptance bar is the **WIP recon** (Σ open-order WIP == the WIP GL balance, FR-MFG-14) — the same finance-grade self-check the inventory/AR/AP/VAT recons use. Because every issue posts DR WIP at the same value the engine debits, and every receipt credits WIP at the relieved value, and close zeroes the residual, the WIP balance moves in lockstep with the open orders' accumulated cost. Resolved in **D-4 / D-6**.

- **The finished-goods receipt must drive the SHIPPED recompute, not write `avg_cost` directly.** The made good's moving-average lives on its `stock_on_hand.avg_cost` and is owned by ADR-0020. The receipt computes a **unit cost** (WIP ÷ good qty) and passes it to `recomputeOnReceipt(... receiptCost = computedUnitCost)` exactly as a goods receipt passes the purchase cost — so the made good and a bought good acquire their average through **one code path**. Manufacturing never touches `avg_cost`. Resolved in **D-5**.

- **Postings are synchronous in the operator's command, not event-driven (OQ-MFG-09 — resolved).** ADR-0020 splits postings: event-driven legs (receipt/sale) use the REQUIRES_NEW null-on-anomaly safe-invoker; human-act legs (adjustment/opening) post **directly** via `GLPostingService.post` so a missing config / closed period **fails the operator's command** (BR-INV-12). A work-order issue/complete/close is a **human act** (the operator pressed the button) — so it posts directly, atomically with the stock movement + cost recompute (NFR-MFG-04). The `WORKORDER.*` outbox events are **informational** (notifications/reporting), not the posting trigger. Resolved in **D-4 / D-11**.

- **Reversal on cancel must be exact (no phantom gain/loss).** Cancelling a work order that already issued components must restore stock + WIP at the **original issue cost** — exactly what `InventoryValuationService.reverseIssue` does (it reads the original movement's `value_amount`). Manufacturing's cancel reverses each posted issue/receipt at original cost; the cost columns on `stock_movements` (ADR-0020 D-2) are what make this exact. Resolved in **D-3 lifecycle / D-5**.

- **Two new movement types are needed, admitted by the CHECK.** A production component issue is **not** a SALE_ISSUE (it is not a sale, posts no COGS, posts DR WIP), and a finished-goods receipt is **not** a GOODS_RECEIPT (it is not a purchase, credits WIP not GRNI). The append-only ledger needs **`PRODUCTION_ISSUE`** (− sign, DR WIP) and **`PRODUCTION_RECEIPT`** (+ sign, CR WIP) added to `MovementType` and admitted by `chk_stock_movement_type` (the additive widen). Their reversals reuse `ADJUSTMENT`-style reverse-from-ledger or dedicated `*_REVERSAL` — D-3 decides reuse. Resolved in **D-3 / D-8**.

- **Schema freeze / direction.** IAM=V1 … Sales-Returns=V19 frozen; approvals=V20 … budgeting=V73 the in-flight Wave-2 range (never edited). Manufacturing is additive `V74–V80`: three new tables in `manufacturing`, an ALTER on `stock_movements`' CHECK (additive widen — admit two movement types; no new column), new CoA accounts + `gl_config` keys + the `chk_gl_config_key` widen, the `chk_journal_*_source_type` widen, three perms + the `ORG_ADMIN` grant, one `code_sequence` kind (lazy). It ALTERs **no** prior table's columns, and changes **no** shipped sale/purchase costing path. The valuation/explosion reuse is **code-only** (no migration) — Manufacturing imports `stock.service` + `products.service` + `gl.service` (the leaf-consumer direction).

## Decision

### D-1 — Module placement: a new `com.erp.modules.manufacturing` module; edges to `stock.service`, `products.service`, `gl.service` (all leaf-consumer, no back-edge)

Manufacturing is a **new top-level module** `com.erp.modules.manufacturing` — it owns the work-order aggregate (header + component lines + operations), the lifecycle service, the costing orchestrator, and the WIP recon report. It is **not** stock depth (it is not about stock-on-hand; it consumes the stock primitive) and **not** products depth (it consumes the BOM; it is the act of production, the bright line ADR-0026 D-9 drew). A separate module is correct: it is a distinct aggregate with its own lifecycle, its own perms, its own numbering.

Manufacturing depends **outbound** on three shipped modules, all leaf-consumer (no back-edge):
- **`manufacturing → stock.service`** — `StockPostingService.post(... PRODUCTION_ISSUE/RECEIPT ...)` (the quantity primitive) + `InventoryValuationService` (the costing engine: `costIssue`, `recomputeOnReceipt`, `reverseIssue`, `reverseReceipt`) + `stock.domain.enums.MovementType`. **New edge** — the same shape as the shipped `ap.service → gl.service` and `stock.service → products.service` edges; introduces no cycle (stock does not depend on manufacturing).
- **`manufacturing → products.service`** — `BomExplosionService.explode(...)` + `BomCostRollUpService.rollUp(...)` + `BomDto`/`BomComponentDto` + `products.domain.dto`/`products.domain.enums`. **The designed-to contract ADR-0026 D-1 / cross-module touch-point 4 anticipated** ("Manufacturing reads the BOM via `BomDto`/`BomComponentDto` and drives production planning via `BomExplosionService.explode(...)` … direction `manufacturing → products`, no back-edge"). New edge, no cycle.
- **`manufacturing → gl.service`** + **`gl.repository`** (the WIP recon's `JournalLineRepository.accountBalance` for the expected side) + **`gl.domain.dto`/`gl.domain.enums`** — `GLPostingService.post`, `GLConfigResolver.resolve`, `FiscalPeriodResolver.resolveOpen`, `GLPostingSafeInvoker` (if any event-driven leg is ever added), `JournalEntryDraft`/`LineDraft`/`GlConfigKey`/`JournalSourceType`. **The same cross-module-read-into-`gl` stance** AP/Inventory/Cash/Fixed-Assets all take (ADR-0020 D-12 documents it as allowed). New edge, no cycle (gl is a sink).
- **`manufacturing → costing` (OPTIONAL, ADR-0025)** — `DimensionResolver` to resolve a nullable WO cost-centre to a dimension id for the WIP/variance P&L legs (D-9). DTO/resolver read, no cycle.

Internal layout (new package):

```
com.erp.modules.manufacturing
├── domain.entity   WorkOrder (work_orders),
│                   WorkOrderComponent (work_order_components),
│                   WorkOrderOperation (work_order_operations)
├── domain.dto      WorkOrderDto / CreateWorkOrderRequest / UpdateWorkOrderRequest,
│                   WorkOrderComponentDto, WorkOrderOperationDto / AddOperationRequest,
│                   ReleaseWorkOrderRequest (effective BOM pin?),
│                   IssueComponentsRequest (full|partial; postingDate),
│                   ApplyCostRequest (labourAmount?, overheadAmount?, operationUid?, postingDate),
│                   CompleteWorkOrderRequest (goodQty, scrapQty?, allowOverRun?, postingDate),
│                   CloseWorkOrderRequest (postingDate),
│                   WorkOrderCostReportDto (planned vs actual + WIP + computed cost + variance),
│                   WipReconciliationDto (Σ open-WO WIP vs WIP GL balance — ReconciliationDto reuse)
├── domain.enums    WorkOrderStatus (PLANNED|RELEASED|IN_PROGRESS|COMPLETED|CLOSED|CANCELLED),
│                   ComponentLineStatus (PLANNED|ISSUED|PARTIAL) — optional line-state
├── repository      WorkOrderRepository, WorkOrderComponentRepository, WorkOrderOperationRepository
└── service         WorkOrderService(+Impl)            — lifecycle: create/update/release/cancel + reads (D-3)
                    WorkOrderCostingService(+Impl)     — issue / applyCost / complete / close orchestration
                                                          (calls BomExplosionService + the ADR-0020 engine) (D-5/D-6)
                    ManufacturingGlPoster              — builds + posts the WIP/Inventory/FG/Variance/
                                                          labour-overhead drafts via GLPostingService (D-4)
                    WorkOrderNumberGenerator           — WO-#### via code_sequence (D-12)
                    WipReconQuery                       — Σ open-WO WIP vs WIP GL balance (D-6)
                    ManufacturingGlSeeder              — seeds WIP/FG/Variance/labour-overhead accounts +
                                                          keys for a new company (the InventoryGlSeeder pattern, D-7)
```

Controllers stay flat in `com.erp.api`: `WorkOrderController` (header CRUD + lifecycle transitions + issue/apply/complete/close + cost report) and `ManufacturingReportController` (the WIP recon). They touch only services (`ModuleBoundaryTest`).

### D-2 — `work_orders` (header) table + the `WorkOrderStatus` lifecycle

`work_orders`: plural name; `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) ULID `uq_work_order_uid`; `company_id` + `branch_id` (tenant + the production branch); `version BIGINT NOT NULL DEFAULT 0` (`@Version`); standard audit cols. Carries a `WO-####` document number (D-12). Quantities `NUMERIC(19,6)` (the shipped quantity scale); cost/value `NUMERIC(19,4)` (the ADR-0020 internal cost scale).

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_work_order_uid` |
| `wo_number` | VARCHAR(30) | NO | `WO-####` per company; `uq_work_order_company_number UNIQUE (company_id, wo_number)` (the numbering backstop) |
| `company_id` | BIGINT | NO | tenant; `fk_work_order_company` |
| `branch_id` | BIGINT | NO | the production branch (where components issue + finished good receives); `fk_work_order_branch` |
| `finished_product_id` | BIGINT | NO | scalar FK → `products(id)`; the product being made (must be stockable — BR-MFG-01, service-validated); `fk_work_order_product` |
| `finished_product_code` / `finished_product_name` | VARCHAR | NO | snapshot (display without a join; the sales-line snapshot pattern) |
| `bom_id` | BIGINT | YES | scalar FK → `boms(id)`; the pinned/selected BOM version, **stamped at release** (BR-MFG-02); NULL while PLANNED; `fk_work_order_bom` |
| `bom_uid` | VARCHAR(26) | YES | the BOM uid (snapshot, for the explosion call + audit) |
| `planned_qty` | NUMERIC(19,6) | NO | `chk_work_order_planned_qty CHECK (planned_qty > 0)`; planned good output in base units (FR-MFG-01) |
| `good_qty` | NUMERIC(19,6) | NO | DEFAULT 0; `chk_work_order_good_qty CHECK (good_qty >= 0)`; actual good output received (FR-MFG-07) |
| `scrap_qty` | NUMERIC(19,6) | NO | DEFAULT 0; `chk_work_order_scrap_qty CHECK (scrap_qty >= 0)`; informational scrap (FR-MFG-10) |
| `status` | VARCHAR(20) | NO | `WorkOrderStatus`; DEFAULT `'PLANNED'`; `chk_work_order_status CHECK (status IN ('PLANNED','RELEASED','IN_PROGRESS','COMPLETED','CLOSED','CANCELLED'))` |
| `wip_debit_total` | NUMERIC(19,4) | NO | DEFAULT 0; the running Σ of value debited to WIP (component issues + applied labour/overhead) — the accumulated cost |
| `wip_credit_total` | NUMERIC(19,4) | NO | DEFAULT 0; the running Σ of value credited from WIP (finished receipts + variance-clear at close) |
| `labour_applied_total` | NUMERIC(19,4) | NO | DEFAULT 0; Σ applied labour (FR-MFG-08) |
| `overhead_applied_total` | NUMERIC(19,4) | NO | DEFAULT 0; Σ applied overhead (FR-MFG-08) |
| `computed_unit_cost` | NUMERIC(19,4) | YES | the finished unit cost at the last completion (WIP relievable ÷ good qty, D-5); NULL until first completion |
| `variance_amount` | NUMERIC(19,4) | NO | DEFAULT 0; the residual cleared to Manufacturing Variance at close (FR-MFG-11); signed |
| `incomplete_cost` | BOOLEAN | NO | DEFAULT false; true if any issued leaf had a NULL `avg_cost` (BR-MFG-06) — the flagged warning state |
| `cost_centre_value_id` | BIGINT | YES | NULL | scalar FK → `dimension_values(id)` (ADR-0025); optional cost-centre tag for the WIP/variance P&L legs (D-9); `fk_work_order_cost_centre` |
| `planned_date` / `released_at` / `completed_at` / `closed_at` / `cancelled_at` | DATE / TIMESTAMPTZ | YES | schedule + transition stamps |
| `notes` | VARCHAR(500) | YES | |
| `version` (`@Version` BIGINT NOT NULL DEFAULT 0) + audit cols | | | |

Constraints + indexes:
- `uq_work_order_uid`, `uq_work_order_company_number`, `fk_work_order_company`, `fk_work_order_branch`, `fk_work_order_product`, `fk_work_order_bom`, `fk_work_order_cost_centre`, the four qty/status CHECKs above.
- `ix_work_orders_company (company_id)`, `ix_work_orders_branch (branch_id)`, `ix_work_orders_product (finished_product_id)`, `ix_work_orders_status (company_id, status)`, **`ix_work_orders_open ON work_orders (company_id) WHERE status IN ('RELEASED','IN_PROGRESS','COMPLETED')`** (the WIP recon's open-order working set — D-6).

**`WorkOrderStatus` lifecycle** (FR-MFG-03, BR-MFG-02/04/09):

```
PLANNED ──release──▶ RELEASED ──issue(first)──▶ IN_PROGRESS ──complete──▶ COMPLETED ──close──▶ CLOSED  (terminal)
   │                    │                            │                        │
   └──── cancel ────────┴──────── cancel ────────────┴──── cancel ────────────┘  (CANCELLED, terminal; reverses posted issues/receipts at original cost)
   │
   └── edit freely (PLANNED only — output qty / BOM / branch / dates / notes; BR-MFG-04)
```

- **Release** (`WorkOrderService.release(uid, ReleaseWorkOrderRequest)`, gated `WORKORDER.RELEASE`): resolve the BOM (pinned `bomUid` if given, else the finished product's ACTIVE `boms` row — BR-MFG-01/02), explode it for `planned_qty` (`BomExplosionService.explode(parentUid=finishedProductUid | bomUid, outputQty=planned_qty, branchUid, multiLevel=true)`), **materialise the planned component lines** from the leaf summary (the *plan*), stamp `bom_id`/`bom_uid`, set RELEASED + `released_at`, emit `WORKORDER.RELEASED`. **No GL/stock effect.** Rejected if the BOM is unresolvable / has no leaves (BR-MFG-01).
- **Issue** (RELEASED → IN_PROGRESS on first issue; D-5): the costing orchestration.
- **Complete** (`WorkOrderService.complete`, gated `WORKORDER.MANAGE`): the finished-goods receipt (D-5); sets COMPLETED + `completed_at`, emits `WORKORDER.COMPLETED`.
- **Close** (`WorkOrderService.close`, gated `WORKORDER.CLOSE`): clear residual WIP to variance (D-6); set CLOSED (terminal).
- **Cancel** (`WorkOrderService.cancel`, gated `WORKORDER.MANAGE`): from PLANNED/RELEASED/IN_PROGRESS; reverse posted issues/receipts/applications at original cost (D-5 / BR-MFG-09); set CANCELLED. Rejected from CLOSED.

### D-3 — `work_order_components` (lines) + `work_order_operations` (routing) + the two new `MovementType` values

**`work_order_components`** (the planned + actual per-leaf consumption): `id`, `uid` (`uq_work_order_component_uid`), `work_order_id` (FK → `work_orders(id)`), `company_id` (denormalised, set-once tenant), `line_no`, `component_product_id` (scalar FK → `products(id)`), snapshots, quantities, value, audit, `version`.

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_work_order_component_uid` |
| `work_order_id` | BIGINT | NO | FK → `work_orders(id)`; `fk_work_order_component_wo` |
| `company_id` | BIGINT | NO | denormalised tenant; `fk_work_order_component_company` |
| `line_no` | SMALLINT | NO | `uq_work_order_component_line_no UNIQUE (work_order_id, line_no)` |
| `component_product_id` | BIGINT | NO | scalar FK → `products(id)`; the leaf component; `fk_work_order_component_product` |
| `component_product_code` / `component_product_name` | VARCHAR | NO | snapshot |
| `planned_qty` | NUMERIC(19,6) | NO | `chk_work_order_component_planned CHECK (planned_qty >= 0)`; from the BOM explosion leaf summary at release |
| `issued_qty` | NUMERIC(19,6) | NO | DEFAULT 0; `chk_work_order_component_issued CHECK (issued_qty >= 0)`; actual issued (FR-MFG-05) |
| `issued_value` | NUMERIC(19,4) | NO | DEFAULT 0; Σ value debited to WIP for this leaf (Σ qty × avg at each issue) |
| `unit_cost_at_issue` | NUMERIC(19,4) | YES | the moving-average cost captured at the last issue (for the cost report); NULL if not yet issued or `avg_cost` was NULL |
| `cost_skipped` | BOOLEAN | NO | DEFAULT false; true if a costed WIP leg was skipped for this leaf (avg_cost NULL — BR-MFG-06) |
| `status` | VARCHAR(12) | NO | DEFAULT `'PLANNED'`; `ComponentLineStatus`; `chk_work_order_component_status CHECK (status IN ('PLANNED','PARTIAL','ISSUED'))` |
| `version` (`@Version` BIGINT NOT NULL DEFAULT 0) + audit cols | | | |

Constraints/indexes: `uq_work_order_component_uid`, `uq_work_order_component_line_no`, the FKs, the two qty CHECKs + status CHECK; `ix_work_order_components_wo (work_order_id)`, `ix_work_order_components_company (company_id)`, `ix_work_order_components_product (component_product_id)`.

> **Note — no duplicate-leaf unique on `(work_order_id, component_product_id)`.** The BOM explosion's leaf summary already aggregates a component appearing in multiple sub-assemblies into **one** net leaf line (ADR-0026 D-6), so one work order has one line per leaf product. The `uq_work_order_component_line_no` is the ordering guard; a `uq_work_order_component_leaf UNIQUE (work_order_id, component_product_id)` is added as the structural backstop (mirrors `bom_components`' `uq_bom_component_child`).

**`work_order_operations`** (OPTIONAL routing — FR-MFG-12): `id`, `uid` (`uq_work_order_operation_uid`), `work_order_id` (FK), `company_id`, `seq_no`, `description`, `work_centre` (free-text label, v1), `labour_amount`, `overhead_amount`, audit, `version`.

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_work_order_operation_uid` |
| `work_order_id` | BIGINT | NO | FK → `work_orders(id)`; `fk_work_order_operation_wo` |
| `company_id` | BIGINT | NO | denormalised tenant; `fk_work_order_operation_company` |
| `seq_no` | SMALLINT | NO | `uq_work_order_operation_seq UNIQUE (work_order_id, seq_no)`; the operation sequence |
| `description` | VARCHAR(200) | NO | the step (mix / bake / pack) |
| `work_centre` | VARCHAR(80) | YES | free-text label (no work-centre master in v1) |
| `labour_amount` | NUMERIC(19,4) | NO | DEFAULT 0; `chk_work_order_operation_labour CHECK (labour_amount >= 0)`; applied labour for this op (FR-MFG-08) |
| `overhead_amount` | NUMERIC(19,4) | NO | DEFAULT 0; `chk_work_order_operation_overhead CHECK (overhead_amount >= 0)`; applied overhead |
| `applied` | BOOLEAN | NO | DEFAULT false; true once this op's labour/overhead has been posted to WIP (idempotency of apply) |
| `version` (`@Version` BIGINT NOT NULL DEFAULT 0) + audit cols | | | |

Constraints/indexes: `uq_work_order_operation_uid`, `uq_work_order_operation_seq`, the FKs, the two amount CHECKs; `ix_work_order_operations_wo (work_order_id)`, `ix_work_order_operations_company (company_id)`. An order with **no** operations is valid (labour/overhead applied at the header via `ApplyCostRequest` with no `operationUid`).

**Two new `MovementType` values (D-8 admits them in the CHECK):**
- **`PRODUCTION_ISSUE`** — components consumed into a work order. − sign. Drives DR WIP / CR Inventory. Reverses on cancel via `InventoryValuationService.reverseIssue` (the SALE_REVERSAL mechanism — reverse-from-ledger at original `value_amount`); the reversing movement is posted as `PRODUCTION_ISSUE_REVERSAL`.
- **`PRODUCTION_RECEIPT`** — finished good received from a work order. + sign. Drives DR Inventory(FG) / CR WIP + the `recomputeOnReceipt`. Reverses on cancel via `reverseReceipt`; the reversing movement is `PRODUCTION_RECEIPT_REVERSAL`.

**Decision (reversal movement types):** add the two **forward** types `PRODUCTION_ISSUE`/`PRODUCTION_RECEIPT` **and** their two **reversal** types `PRODUCTION_ISSUE_REVERSAL`/`PRODUCTION_RECEIPT_REVERSAL` to the enum + the CHECK (four new values), mirroring the shipped `SALE_ISSUE`/`SALE_REVERSAL` + `GOODS_RECEIPT`/`GOODS_RECEIPT_REVERSAL` symmetry — so a cancel's reversing movement is its own typed, queryable ledger row (not an `ADJUSTMENT` that loses the production lineage). This is the boring, consistent-with-shipped choice. (Reusing `ADJUSTMENT` for the reversal was rejected — it would conflate a production reversal with a manual shrinkage adjustment and break the movement-type audit lineage.)

### D-4 — GL postings: exact legs, keys, source types, granularity, the synchronous (human-act) posting posture

All amounts base currency (BR-MFG-07), HALF_UP, posted via `ManufacturingGlPoster` → **`GLPostingService.post(draft)` directly** (the **human-act** posture — a missing `gl_config` / closed period **fails the operator's command**, BR-MFG-07/BR-GL-10, the ADR-0020 adjustment/opening precedent; **not** the REQUIRES_NEW safe-invoker, which is for event-driven legs). Accounts resolved via `GLConfigResolver.resolve(companyId, key)`. Period-gated via `FiscalPeriodResolver.resolveOpen(companyId, postingDate)` (rejects closed/absent — NFR-MFG-04). The P&L-relevant legs (labour/overhead clearing, variance) carry the WO's optional `cost_centre_value_id` in the `LineDraft` dimension slot (D-9).

**(a) Component issue — `WorkOrderCostingService.issue`, after the −quantity `PRODUCTION_ISSUE` movements + `costIssue` per leaf (FR-MFG-05/06):**
- For each **stockable** leaf with an established `avg_cost`: **DR `WIP_INVENTORY` (1320)** = issued qty × current `avg_cost` ; **CR `INVENTORY` (1300)** = same. **One journal per issue event**, one DR-WIP / CR-Inventory leg pair per leaf (the leaf summary yields the component rows — mirrors the ADR-0020 receipt per-line granularity). Increment `wip_debit_total` + each line's `issued_value`/`issued_qty`.
- A leaf with `avg_cost IS NULL` (BR-MFG-06): the `PRODUCTION_ISSUE` quantity movement **still posts** (on-hand allowed negative); `costIssue` returns null → the **costed WIP leg for that leaf is skipped** with a WARN + audit anomaly; set the line's `cost_skipped = true` and the order's `incomplete_cost = true`. The journal is the balanced subset of costed leaves.
- `sourceType = PRODUCTION_ISSUE` (**NEW** `JournalSourceType`); `sourceRef = workOrder.uid`; `postedBy = operator`; `description = "WO " + wo_number + " component issue"`; each leg `lineMemo` carries the component code + the WO number.

**(b) Applied labour / overhead — `WorkOrderCostingService.applyCost` (FR-MFG-08):**
- **DR `WIP_INVENTORY` (1320)** = labour + overhead applied ; **CR `LABOUR_APPLIED` (2350)** = labour amount, **CR `OVERHEAD_APPLIED` (2360)** = overhead amount (two CR legs if both > 0). One journal per apply. Increment `wip_debit_total`, `labour_applied_total`, `overhead_applied_total`; mark the operation `applied = true` (or the header apply, idempotent).
- `sourceType = PRODUCTION_LABOUR` (**NEW**); `sourceRef = workOrder.uid`; `postedBy = operator`.
- **Why clearing accounts (2350/2360), not direct expense:** labour/overhead applied to WIP must be **absorbed** (it becomes part of the finished-goods inventory value, not a period expense). The clearing-credit accumulates the absorbed amount; when actual labour/overhead is **incurred** (HR/Payroll DR salary expense, or a utility bill DR overhead — future modules) the absorption is reconciled against the clearing balance (an over/under-absorption a future variance round trues up). v1 books the **applied** side only (the clearing credit), which is the minimum to get the cost into WIP and onto the finished good. The clearing accounts are LIABILITY-style suspense (a credit balance = absorbed-not-yet-incurred). *This is the standard absorption-costing seam; the incurred side hooks in additively.*

**(c) Finished-goods receipt — `WorkOrderCostingService.complete`, after the +quantity `PRODUCTION_RECEIPT` movement + `recomputeOnReceipt(... receiptCost = computedUnitCost)` (FR-MFG-07, D-5):**
- **DR `FINISHED_GOODS` (→ 1300 in v1, OQ-MFG-01) / CR `WIP_INVENTORY` (1320)** = relieved value (`computedUnitCost × good_qty`). One journal per completion. Increment `wip_credit_total`; set `computed_unit_cost`, `good_qty`, `scrap_qty`.
- `sourceType = PRODUCTION_RECEIPT` (**NEW**); `sourceRef = workOrder.uid`; `postedBy = operator`.

**(d) Close — variance clear — `WorkOrderCostingService.close` (FR-MFG-09):**
- residual = `wip_debit_total − wip_credit_total` (the WIP balance for this order). If residual ≠ 0: **DR `MANUFACTURING_VARIANCE` (5180) / CR `WIP_INVENTORY` (1320)** at `residual` (residual > 0 — under-relieved, a cost); the reverse (DR WIP / CR Variance) if residual < 0 (over-relieved, a credit). One journal. Set `variance_amount = residual`; after this `wip_debit_total − wip_credit_total − variance_amount = 0` (WIP nets to zero per closed order, BR-MFG-05).
- `sourceType = PRODUCTION_VARIANCE` (**NEW**); `sourceRef = workOrder.uid`; `postedBy = operator`. Carries the WO `cost_centre_value_id`.

**(e) Cancel — reversal at original cost (D-5 / BR-MFG-09):** for each posted `PRODUCTION_ISSUE`, post a `PRODUCTION_ISSUE_REVERSAL` (`reverseIssue` restores stock + WIP at original `value_amount`) and a GL reversal **DR `INVENTORY` / CR `WIP_INVENTORY`** at the original value; for each `PRODUCTION_RECEIPT`, post a `PRODUCTION_RECEIPT_REVERSAL` (`reverseReceipt`) + **DR `WIP_INVENTORY` / CR `FINISHED_GOODS`(1300)**; reverse applied labour/overhead **DR `LABOUR_APPLIED`/`OVERHEAD_APPLIED` / CR `WIP_INVENTORY`**. Posted via `GLPostingService.postReversal(...)` on the stored entry uids, or fresh balanced reversing drafts with `reversalOfId`. After cancel, WIP for the order is 0 and stock/avg are restored. `sourceType` reuses the forward token with `reversalOfId` set (mirrors ADR-0019 D-10 / ADR-0020 D-4d — no separate reversal token).

**Idempotency (BR-MFG-12, NFR-MFG-04):** the synchronous postings ride the work-order command TX (one command → one set of postings); the lifecycle guard rejects a re-issue/re-complete/re-close on an order already in the target state; the GL poster's `(companyId, sourceType, sourceRef)` existence check is the belt-and-braces guard (the AR/AP/Inventory precedent); the `uq_stock_movement_source_event` DB backstop is N/A here (manual `sourceEventUid = null`), so the **lifecycle state + the WO-level totals are the dedup** for the cost. The `WORKORDER.*` outbox events are deduped by the consumer's `IdempotencyGuard`.

### D-5 — The costing orchestration: issue → WIP, complete → finished cost, cancel → exact reversal (the algorithmic core, all reusing the ADR-0020 engine)

**`WorkOrderCostingService.issue(uid, IssueComponentsRequest)` (FR-MFG-05/06):**
1. Load the RELEASED/IN_PROGRESS order; resolve the remaining-to-issue quantity per planned component line (planned − issued). For a **full** issue, that is the whole plan; for a **partial** issue, the requested subset.
2. For each leaf to issue (in line order): call `StockPostingService.post(companyId, branchId, locationId(default), componentProductId, −issueQty, MovementType.PRODUCTION_ISSUE, sourceEventUid=null, sourceDocumentType="WORK_ORDER", sourceDocumentUid=wo.uid, reasonCode=null, note, occurredAt=postingDate, actorId=operator, unitCostAmount=avg_cost, valueAmount=−issuedValue)` — but the **cost is computed by the engine**: call `InventoryValuationService.costIssue(companyId, branchId, componentProductId, issueQty)` to get the issued value at the current `avg_cost` (returns null if `avg_cost` NULL → skip the costed leg, set `cost_skipped`). The `StockPostingService.post` records the movement with the engine-supplied cost; the on-hand `on_hand_value` is debited by `costIssue`.
3. Accumulate the costed legs; post **(a)** the one DR-WIP / CR-Inventory journal; update `wip_debit_total` + the component lines' `issued_qty`/`issued_value`/`unit_cost_at_issue`/`status`; transition RELEASED → IN_PROGRESS on first issue.

**`WorkOrderCostingService.complete(uid, CompleteWorkOrderRequest{goodQty, scrapQty, allowOverRun, postingDate})` (FR-MFG-07, BR-MFG-04/08/11):**
1. Validate goodQty > 0; goodQty ≤ planned unless `allowOverRun` (BR-MFG-08); the finished product is stockable (BR-MFG-01).
2. **Compute the relievable WIP** = `wip_debit_total − wip_credit_total` (the un-relieved accumulated cost). The **finished unit cost** = relievableWip ÷ goodQty (HALF_UP 4 dp, BR-MFG-04); clamp to 0 with a WARN if relievableWip < 0 (BR-MFG-11). For v1's single-completion default the whole relievable WIP is relieved into goodQty; for a partial/multiple completion the relieved value = unitCost × thisGoodQty (OQ-MFG-02).
3. **Receive the finished good:** call `StockPostingService.post(... finishedProductId, +goodQty, MovementType.PRODUCTION_RECEIPT, sourceDocumentType="WORK_ORDER", sourceDocumentUid=wo.uid, unitCostAmount=unitCost, valueAmount=+relievedValue)` and `InventoryValuationService.recomputeOnReceipt(companyId, branchId, finishedProductId, goodQty, unitCost)` — **the made good acquires/moves its `avg_cost` through the SAME engine path a purchase uses** (the load-bearing reuse, D-1 force 3). The recompute returns the receipt value (= relievedValue) used for the GL leg.
4. Post **(c)** the DR-FinishedGoods / CR-WIP journal at relievedValue; update `wip_credit_total`, `computed_unit_cost`, `good_qty`, `scrap_qty`; set COMPLETED; emit `WORKORDER.COMPLETED`.

**`WorkOrderCostingService.cancel(uid)` (BR-MFG-09):** in reverse order — for each `PRODUCTION_RECEIPT` movement of the order, `reverseReceipt(...)` + DR-WIP / CR-FinishedGoods reversal; for each `PRODUCTION_ISSUE`, `reverseIssue(...)` + DR-Inventory / CR-WIP reversal; reverse applied labour/overhead. The reversals read each original movement's `value_amount` (exact, no phantom gain/loss — ADR-0020 D-5). Set CANCELLED. Rejected from CLOSED (BR-MFG-09).

**Concurrency (NFR-MFG-05):** the issue/receipt drive the shipped on-hand optimistic-lock recompute (ADR-0020 NFR-INV-05, one retry); the work-order header's `@Version` guards concurrent lifecycle transitions (a double-complete loses the optimistic lock and retries → the lifecycle guard then rejects the second as already-completed). No new locking mechanism.

### D-6 — WIP reconciliation + the work-order cost report (FR-MFG-13/14, NFR-MFG-01)

Lives in `manufacturing` as `WipReconQuery` + the report on `WorkOrderService`/`WorkOrderCostingService`. The WIP recon reaches into `gl.repository.JournalLineRepository.accountBalance` for the expected side — the exact leaf-reader-into-`gl.read` pattern the shipped inventory/cash recons use (ADR-0020 D-6/D-12).

- **WIP recon bar (BR-MFG-05, NFR-MFG-01):** `computed` = `SELECT SUM(wip_debit_total − wip_credit_total) FROM work_orders WHERE company_id = ? AND status IN ('RELEASED','IN_PROGRESS','COMPLETED')` (the open-order WIP, served by `ix_work_orders_open`); `expected` = `accountBalance(companyId, resolve(WIP_INVENTORY).id)` (a single balance read, not a row scan — NFR-MFG-07). `WipReconciliationDto = ReconciliationDto.of("WIP vs GL 1320", computed, expected)`. `ties == false` is a finance-grade defect surfaced on screen. Closed/cancelled orders are excluded (their WIP nets to zero, so they neither add to the open-WIP sum nor to the live WIP balance).
- **Work-order cost report (FR-MFG-13):** per order — planned-vs-actual component consumption (qty + value per line), applied labour/overhead, `wip_debit_total`/`wip_credit_total`, `computed_unit_cost`, `good_qty`/`scrap_qty`, `variance_amount`, `incomplete_cost` flag. A read aggregate over the order + its component lines + operations.
- `assertCanActIn(principal, companyId)` on every read path (the #1 anti-regression guard, NFR-MFG-06); per-company scope.

### D-7 — New CoA accounts + `gl_config` keys (collision-checked against V1–V19 + V20–V73)

**Five new posting roles + five new CoA accounts.** Added to `GlConfigKey` (Java) and admitted by `chk_gl_config_key` (V74 widen, **re-stating the full union of all prior keys** — the wave2 shared-file protocol). Account codes chosen in **free gaps** verified against the used set (shipped 1000–5400 + 2150/5160; procurement 2160; sales 4900/5170; fixed-assets 1600/1650/1700/3200/4200/5500; hr 1450/2400/2410/2420/2430/2440/2450/5200/5210 — note 5200 "Rent Expense" shipped, hr's "Salary Expense" is 5200 in ADR-0032 which is a *separate* coordination item; manufacturing avoids 5200 entirely):

| `GlConfigKey` (NEW) | account code (NEW) | account name | `AccountType` / normal balance | role |
|---|---|---|---|---|
| `WIP_INVENTORY` | `1320` | Work-In-Progress Inventory | ASSET / DEBIT | the production cost holding account (DR on issue/apply, CR on receipt/variance-clear) — **`1320`, NOT `1350` (`1350` is reserved by ADR-0033 projects for a deferred `PROJECT_WIP`; collision avoided)** |
| `FINISHED_GOODS` | `1300` (v1 — OQ-MFG-01) | Inventory (finished goods roll into 1300 in v1) | ASSET / DEBIT | the finished-goods receipt debit; **maps to the existing 1300 in v1** (keeps the inventory recon whole) — a distinct `1360 Finished Goods Inventory` is the one-line split alternative (D-7 note) |
| `LABOUR_APPLIED` | `2350` | Labour Applied (absorption clearing) | LIABILITY / CREDIT | the applied-labour absorption clearing (CR on apply) |
| `OVERHEAD_APPLIED` | `2360` | Overhead Applied (absorption clearing) | LIABILITY / CREDIT | the applied-overhead absorption clearing (CR on apply) |
| `MANUFACTURING_VARIANCE` | `5180` | Manufacturing Variance | EXPENSE / DEBIT | the residual-WIP clear at close (DR if under-relieved, CR if over) |

**Codes confirmed free (no collision):** `1320`, `2350`, `2360`, `5180` are unused across the shipped CoA and all V20–V73 ADRs (verified: procurement 2160; sales 4900/5170; fixed-assets 1600/1650/1700/3200/4200/5500; hr 1450/24xx/5200/5210; **projects ADR-0033 RESERVES `1350` for a deferred `PROJECT_WIP` — so manufacturing's WIP is `1320`, NOT `1350`, to avoid that latent collision**; budgeting introduces **no** new CoA codes). **`FINISHED_GOODS` is a NEW key mapped to the EXISTING 1300 account in v1** (OQ-MFG-01) — so a new account is **not** required for finished goods in v1; the four genuinely-new accounts are `1320`, `2350`, `2360`, `5180`. (If the owner elects the distinct-finished-goods fork, a fifth account `1360` — also free — is added and the inventory recon is amended to Σ(raw+WIP+FG) — flagged, not v1.)

**Seeding (two surfaces — new + existing companies, the ADR-0020 D-8 pattern):**
1. **New companies:** add `1320 → ASSET`, `2350 → LIABILITY`, `2360 → LIABILITY`, `5180 → EXPENSE` to `ChartOfAccountServiceImpl.DEFAULT_ACCOUNTS`, and `WIP_INVENTORY → 1320`, `FINISHED_GOODS → 1300`, `LABOUR_APPLIED → 2350`, `OVERHEAD_APPLIED → 2360`, `MANUFACTURING_VARIANCE → 5180` to `GlConfigServiceImpl.DEFAULT_MAPPINGS`. Add `ManufacturingGlSeeder.seedDefaults(companyId)` (the `InventoryGlSeeder`/`FixedAssetGlSeeder` pattern — idempotent, wired in `BootstrapRunner` + `CompanyService.create`).
2. **Existing companies (migration back-seed, V75):** INSERT the four new accounts per company `ON CONFLICT (company_id, account_code) DO NOTHING` + the five `gl_configs` mappings per company `ON CONFLICT (company_id, config_key) DO NOTHING` — **#12-safe seed-uids** (D-12).

### D-8 — `MovementType` widen + `JournalSourceType` widen (additive CHECK changes; no new stock column)

**`MovementType` (Java enum + `chk_stock_movement_type` widen, V74):** add **`PRODUCTION_ISSUE`, `PRODUCTION_RECEIPT`, `PRODUCTION_ISSUE_REVERSAL`, `PRODUCTION_RECEIPT_REVERSAL`** (four values) to the enum **and** to the DB CHECK `IN`-list (the additive `DROP/ADD CONSTRAINT chk_stock_movement_type` widen, re-stating the full prior union: `GOODS_RECEIPT, SALE_ISSUE, SALE_REVERSAL, GOODS_RECEIPT_REVERSAL, ADJUSTMENT, OPENING_BALANCE` + any inventory-depth additions if V37 admitted `TRANSFER_OUT/IN` — the latest-numbered migration's union must be a superset). **No new stock column** — `unit_cost_amount`/`value_amount` (ADR-0020 D-2) already carry the cost for these movements. This is the only ALTER to a shipped/in-flight table, and it is an additive CHECK widen, not a column change.

> **Coordination note (V37–V41 inventory-depth, ADR-0028):** inventory-depth admits `TRANSFER_OUT/TRANSFER_IN` (and added a `location_id`). The V74 `chk_stock_movement_type` widen MUST re-state the **full** union including those (the wave2 superset rule). The `StockPostingService.post` location-aware overload (ADR-0028 D-3) is the one manufacturing calls (passing the branch's default location via `LocationResolver.defaultLocationId`).

**`JournalSourceType` (Java enum + `chk_journal_batch_source_type` AND `chk_journal_entry_source_type` widen, V74):** add **`PRODUCTION_ISSUE`, `PRODUCTION_RECEIPT`, `PRODUCTION_LABOUR`, `PRODUCTION_VARIANCE`** (four tokens) to the enum + both CHECK IN-lists (re-stating the full prior union including the fixed-assets FA_* and hr PAYROLL tokens if their migrations precede V74 — superset rule). The cancel reversals reuse the forward token with `reversalOfId` (no separate reversal token, the ADR-0019 D-10 / ADR-0020 D-4d decision).

### D-9 — Cost-centre dimension pass-through (ADR-0025, OPTIONAL)

The work order carries a nullable `cost_centre_value_id` (FK → `dimension_values(id)`). On the **P&L-relevant** legs — applied labour/overhead's clearing (b) and the close variance (d) — `ManufacturingGlPoster` passes the resolved dimension id into the `LineDraft`'s `costCentreValueId` slot (the 9-arg `LineDraft`; ADR-0025 D-4). The WIP/Inventory **asset** legs are not dimension-tagged (dimensions tag P&L, NFR-CC-01). If the cost-centre framework is **not** integrated at build time, the field is an inert nullable scalar (the 5-arg `LineDraft` ctor defaults the slots to null — no call-site change, NFR-CC-01). Design-to-contract; not load-bearing (OQ-MFG-07).

### D-10 — ArchUnit edges (no cycle)

- **`manufacturing.service` → `stock.service`** (`StockPostingService`, `InventoryValuationService`) + **`stock.domain.enums`** (`MovementType`) — **NEW edge**, leaf-consumer; stock does not depend on manufacturing → **no cycle**. Same shape as the shipped `ap.service → gl.service`.
- **`manufacturing.service` → `products.service`** (`BomExplosionService`, `BomCostRollUpService`) + **`products.domain.dto`/`products.domain.enums`** (`BomDto`, `BomComponentDto`) — **NEW edge**, the ADR-0026 D-1 designed-to contract; products does not depend on manufacturing → **no cycle**.
- **`manufacturing.service`/`manufacturing.events` → `gl.service`** (`GLPostingService`, `GLConfigResolver`, `FiscalPeriodResolver`, `GLPostingSafeInvoker`) + **`gl.repository`** (`JournalLineRepository.accountBalance` for the WIP recon) + **`gl.domain.dto`/`gl.domain.enums`** — **NEW edge**, the documented cross-module-read-into-`gl` allowance (ADR-0020 D-12); gl is a sink → **no cycle**.
- **`manufacturing.service` → `costing.service`** (`DimensionResolver`, OPTIONAL, D-9) — DTO/resolver read; costing does not depend on manufacturing → **no cycle**.
- **No edge `stock → manufacturing`, `products → manufacturing`, `gl → manufacturing`, `sales → manufacturing`, `ap → manufacturing`.** Manufacturing is a pure leaf consumer. The dependency graph stays acyclic.
- The shipped `ModuleBoundaryTest` (controller↛repository, service↛controller, no module cycles) stays green: `WorkOrderController`/`ManufacturingReportController` touch only services; the new edges are all outbound-to-shipped, no reverse edge. A `ModuleBoundaryTest` case asserting **no module depends on `manufacturing`** is the regression guard for the leaf-consumer invariant.

### D-11 — Events: two new `DomainEventType` values (informational; postings are synchronous)

Two new outbox event types (the wave2 shared-file append protocol — append a `// --- manufacturing (ADR-0035) ---` block at the END of `DomainEventType.java`):
- **`WORK_ORDER.RELEASED`** (`WORK_ORDER_RELEASED`) — emitted at release; informational (notifications/reporting may consume — "WO released, plan exploded").
- **`WORK_ORDER.COMPLETED`** (`WORK_ORDER_COMPLETED`) — emitted at completion; informational ("finished goods received at computed cost").
- Aggregate type **`WORK_ORDER`** (`AGG_WORK_ORDER`).

**Decision (OQ-MFG-09 — synchronous posting):** the GL/stock effects post **synchronously** in the work-order command (the human-act posture, ADR-0020 adjustment/opening precedent) — **not** triggered by these events. The events are **informational** only. This avoids the eventual-consistency gap the WIP recon would otherwise have to tolerate (a released-but-not-yet-posted order would break Σ-WIP == GL). The events ride the outbox written in the same TX (the transactional-outbox guarantee, ADR-0009). No new outbox **handler** is registered by manufacturing (it is a producer, not a consumer; downstream notifications/reporting consume in their modules).

### D-12 — Numbering: one new `code_sequence` kind (`WORK_ORDER` → `WO-%04d`)

`WorkOrderNumberGenerator` reuses the shipped `code_sequence` row-locked allocation (ADR-0007 D-6) with one new `entity_kind` value `WORK_ORDER` (`WO-%04d`), per company, concurrency-safe. Allocation timing: `WO-####` at **create** (FR-MFG-01). No new numbering table — only the new `entity_kind` row, lazily created with `next_value = 1` on first use (the shipped mechanism). The `uq_work_order_company_number` constraint backstops generator bugs. **No #12 seed-uid exposure** — numbering kinds are lazy, not seeded.

### D-13 — Perms + `ScopeGuard` cases

Three new permissions (module `manufacturing`), seeded in V74 `ON CONFLICT (code) DO NOTHING` + granted to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN (the V3/V7/V17 pattern):
- `MANUFACTURING.VIEW` — view / list work orders, the cost report, the WIP recon.
- `WORKORDER.MANAGE` — create / edit-draft / issue / apply-cost / complete / cancel + add operations.
- `WORKORDER.RELEASE` — release a planned work order (the soft approval gate, OQ-MFG / wave2 soft-gate posture).
- `WORKORDER.CLOSE` — close a completed work order (the variance-clear, finance act).
- **`WORKORDER.QC`** — RESERVED (no workflow in v1, OQ-MFG-08); seeded so the hook exists. *(Owner may drop it to avoid a dead permission — flagged.)*

**Decision (perm count):** seed **five** perms — `MANUFACTURING.VIEW`, `WORKORDER.MANAGE`, `WORKORDER.RELEASE`, `WORKORDER.CLOSE`, `WORKORDER.QC`(reserved). Gating: `@perm.has('MANUFACTURING.VIEW')` for list/create-read; `@perm.scoped(#uid,'workorder', '...')` for uid-addressed transitions (issue/apply/complete/release/close/cancel use their specific perm). **Never `hasAuthority`.**

`ScopeGuard.companyIdOf` gains **three cases** (the wave2 append protocol — append fields + ctor params + `case` arms under a `// Manufacturing (ADR-0035)` comment):
- `case "workorder" -> workOrders.findCompanyIdByUid(uid)` (new `WorkOrderRepository.findCompanyIdByUid`)
- `case "workordercomponent" -> workOrderComponents.findCompanyIdByUid(uid)`
- `case "workorderoperation" -> workOrderOperations.findCompanyIdByUid(uid)`

`assertCanActIn` is called on **every** read + write path (issue/complete/close/cancel/report/recon — the #1 anti-regression guard, NFR-MFG-06), resolved via the WO's company.

## Cross-module touch-points

1. **`manufacturing → stock.service` (NEW edge, D-1/D-5)** — calls `StockPostingService.post(... PRODUCTION_ISSUE/RECEIPT/*_REVERSAL ...)` (location-aware overload, default location via `LocationResolver`) + `InventoryValuationService.costIssue`/`recomputeOnReceipt`/`reverseIssue`/`reverseReceipt`. **The valuation engine is reused, not reimplemented (NFR-MFG-02).** Code-only, no migration.
2. **`stock` `MovementType` + `chk_stock_movement_type` widen (D-8)** — four new movement types admitted by the CHECK (the only ALTER to an in-flight table; additive widen, full-union superset including inventory-depth's TRANSFER_* + location).
3. **`manufacturing → products.service` (NEW edge, D-1/D-5)** — `BomExplosionService.explode(...)` + `BomCostRollUpService.rollUp(...)` (the ADR-0026 D-1 designed-to contract). Code-only.
4. **`manufacturing → gl.service` + `gl.repository` (NEW edge, D-1/D-4/D-6)** — synchronous `GLPostingService.post` for the WIP/Inventory/FG/labour-overhead/variance legs; `JournalLineRepository.accountBalance` for the WIP recon. `GlConfigKey` + `JournalSourceType` + `chk_gl_config_key` + `chk_journal_*_source_type` widens (full-union superset). New CoA accounts + new-company seeder (`ManufacturingGlSeeder`) + DEFAULT_ACCOUNTS/DEFAULT_MAPPINGS additions.
5. **`manufacturing → costing.service` (OPTIONAL, D-9)** — `DimensionResolver` for the nullable WO cost-centre pass-through.
6. **`ScopeGuard.companyIdOf` gains three `case` arms + three repo fields (D-13)** — the wave2 append protocol.
7. **`DomainEventType` gains two event types + one aggregate type (D-11)** — appended block; manufacturing is a producer, registers no handler.
8. **`code_sequence` gains one lazy `entity_kind` (`WORK_ORDER`, D-12)** — no seed.
9. **`admin.routes.ts` + nav shell (D-14)** — appended lazy route block + nav group gated `MANUFACTURING.VIEW` (the wave2 append protocol).

## Consequences

**Positive**
- A real production path ships: a made good is costed end-to-end (raw → WIP → finished at computed cost → sold at correct COGS). The balance sheet shows inventory in all three states; the P&L's COGS is correct for manufactured products. PATH-TO-FULL-ERP area 9 opens.
- **One valuation engine, one explosion (NFR-MFG-02):** Manufacturing reuses `InventoryValuationService` for all costing and `BomExplosionService` for all explosion — no second moving-average, no second BOM walk, no drift between what a run costs and what a sale's COGS uses.
- **WIP nets to zero per closed order, and the WIP recon bar proves it (BR-MFG-05/NFR-MFG-01):** the same finance-grade self-check the inventory/AR/AP/VAT recons use.
- **Synchronous, atomic posting (OQ-MFG-09/NFR-MFG-04):** the physical act and its books commit together — no eventual-consistency gap for the recon to tolerate.
- **Exact reversal on cancel (BR-MFG-09):** original-cost reversal via the ADR-0020 engine — no phantom gain/loss.
- **Additive + contained:** a new module, three tables, one CHECK widen on `stock_movements` (additive), four new CoA accounts + five `gl_config` keys + four `JournalSourceType` tokens + four `MovementType` values + two `DomainEventType` values + one `code_sequence` kind + five perms + three `ScopeGuard` cases. **No edit to any prior migration; no behaviour change to any shipped sale/purchase costing path.**

**Negative / costs**
- The WIP totals (`wip_debit_total`/`wip_credit_total`) are maintained denormalisations that MUST stay tied to the GL WIP account and to the work-order's posted movements; the WIP recon bar is the guardrail, but a bug in the accumulation is a finance-grade defect. Tests must assert the WIP tie after every path (issue, apply, complete, close, cancel) — the ADR-0020 discipline.
- A leaf with no established `avg_cost` produces an incomplete-cost order (BR-MFG-06): the finished cost under-counts that component until it is established. Accepted v1 imprecision (surfaced loudly, the `incomplete_cost` flag) rather than blocking a physical run.
- The labour/overhead **applied** side is booked (clearing credit) but the **incurred** side is not reconciled in v1 (the absorption true-up is deferred to a future variance round / HR integration). The clearing accounts will carry a balance = absorbed-not-yet-incurred until that lands. Accepted; the seam is explicit.
- Manufacturing adds three new outbound module edges (`→ stock.service`, `→ products.service`, `→ gl`). All are leaf-consumer, no back-edge — but the engineer MUST NOT let any shipped module import `manufacturing` (the `ModuleBoundaryTest` leaf-consumer case is the guard).

**Neutral / deferred**
- MRP/MPS/scheduling/capacity, labour time × rate, overhead absorption rates, QC workflow, by-products/co-products, batch/lot/serial of the made good, subcontracting, rework, material/labour/overhead variance split, standard-costing, engineering-vs-manufacturing BOM — all deferred (manufacturing.md §2), none precluded. The work-order header (`labour_applied_total`/`overhead_applied_total`/`variance_amount`), the operations table, and the clearing accounts are the seams those build on additively.
- The distinct-Finished-Goods-account fork (OQ-MFG-01) is a one-line `FINISHED_GOODS → 1360` remap + an inventory-recon amendment; not v1.

## Alternatives considered

- **Reuse the ADR-0020 valuation engine vs a manufacturing-owned cost recompute.** *Decided: reuse (D-1/D-5).* A second moving-average implementation = two definitions of cost = drift between production cost and sale COGS. Manufacturing calls `costIssue`/`recomputeOnReceipt`; the made good acquires its average through the same path a purchase does. Rejecting the second implementation.
- **Synchronous human-act posting vs event-driven posting (OQ-MFG-09).** *Decided: synchronous, in the command (D-4/D-11).* Event-driven posting reintroduces the eventual-consistency gap the WIP recon would have to tolerate (a released-but-unposted order breaks Σ-WIP == GL). The operator pressed the button — it is a human act, posted directly like the ADR-0020 adjustment/opening. The `WORKORDER.*` events stay informational.
- **Finished-goods account: reuse Inventory 1300 vs a distinct 1360 (OQ-MFG-01).** *Decided: reuse 1300 in v1, via a NEW `FINISHED_GOODS` key mapped to 1300* (D-7). Keeps the inventory recon whole (Σ on_hand_value == 1300) and makes the split a one-line remap when a reporting need appears. The distinct account forks the recon before there is a need. Flagged for owner finance confirmation.
- **WIP relief: actual-cost ÷ good-qty (v1) vs standard-cost + per-completion variance.** *Decided: actual-cost ÷ good-qty, residual cleared at close (D-5/OQ-MFG-02).* Standard-cost needs a persisted standard + purchase/usage variance (deferred). The actual-cost basis is the moving-average choice made consistent with ADR-0020.
- **New `PRODUCTION_*` movement + reversal types vs reusing ADJUSTMENT for the reversal.** *Decided: four typed values (forward + reversal), mirroring SALE_ISSUE/SALE_REVERSAL (D-3/D-8).* Reusing ADJUSTMENT for a production reversal conflates it with manual shrinkage and breaks the movement-type audit lineage. The boring, consistent-with-shipped choice.
- **Labour/overhead to a clearing-credit (absorption) vs direct expense.** *Decided: clearing-credit (D-4b).* Direct expense would not capitalise the labour/overhead into the finished-goods inventory value (it would hit the P&L immediately, understating inventory). Absorption via a clearing account is the standard manufacturing treatment; the incurred-side reconciliation is the deferred true-up.
- **New module `manufacturing` vs stock/products depth.** *Decided: new module (D-1).* It is a distinct aggregate with its own lifecycle, perms, and numbering; ADR-0026 D-9 drew the bright line (BOM = structure; Manufacturing = the act that posts WIP/COGS). A leaf-consumer module is the clean placement.

## Open items (OQ-MFG — recommended defaults adopted; the load-bearing ones flagged for owner)

- **OQ-MFG-01 (LOAD-BEARING) — Finished-Goods account reuse-vs-distinct:** adopted **NEW `FINISHED_GOODS` key → existing 1300 in v1** (keeps inventory recon whole; one-line split to 1360 later) (D-7). **Owner (finance) confirms** whether a distinct finished-goods asset block is wanted at go-live.
- **OQ-MFG-02 (LOAD-BEARING) — WIP relief / cost-per-good arithmetic:** adopted **relievable-WIP ÷ good-qty per completion, residual cleared at close** (D-5). **Owner confirms** the costing arithmetic.
- **OQ-MFG-09 (LOAD-BEARING) — synchronous-vs-event-driven posting:** adopted **synchronous in the command** (D-4/D-11). Settled (the recon's integrity depends on it).
- **OQ-MFG-03 — issue timing:** adopted **bulk-at-start (default) + explicit partial issue**; backflush a fast-follow on the same primitive (D-5). Not load-bearing.
- **OQ-MFG-04 — scrap treatment:** adopted **absorb into the good unit cost** (no separate scrap account) (FR-MFG-10). A `SCRAP_EXPENSE` reuses 5160 if ever needed; no new key.
- **OQ-MFG-05 — labour/overhead source:** adopted **flat applied amount** (clearing absorption); time × rate hooks into HR/Payroll later (D-4b). Not load-bearing.
- **OQ-MFG-06 — routing:** adopted **descriptive + cost-input, optional** (D-3 `work_order_operations`); scheduling deferred. Not load-bearing.
- **OQ-MFG-07 — cost-centre on the WO:** adopted **nullable scalar, framework-activated** (D-9). Inert if ADR-0025 not integrated.
- **OQ-MFG-08 — `WORKORDER.QC` perm:** adopted **reserve it (no workflow)** (D-13). Owner may drop it to avoid a dead permission.
- **OQ-MFG-10 (deferred):** MRP/MPS/scheduling/capacity, material/labour/overhead variance split, by-products/co-products, batch/serial of the made good, subcontracting, rework, standard-costing — all deferred (§2), none precluded.

## V74–V80 migration ordering (additive; V1–V19 FROZEN; V20–V73 never edited; #12-safe seeds)

The coordinator reserved **V74–V80** (7 slots) for manufacturing. v1 uses **V74–V76** (3 migrations); **V77–V80 reserved** for the deferred depth (QC, by-products, batch/serial-of-made-good, variance-split). Each block additive; never edits any prior migration.

1. **`V74__manufacturing.sql`** — the schema + enum/CHECK widens:
   - **CREATE `work_orders`** (+ `uq_work_order_uid`, `uq_work_order_company_number`, the five FKs, the four qty/status CHECKs, `version BIGINT NOT NULL DEFAULT 0`; D-2).
   - **CREATE `work_order_components`** (+ `uq_work_order_component_uid`, `uq_work_order_component_line_no`, `uq_work_order_component_leaf`, the FKs, the qty/status CHECKs, `version`; D-3).
   - **CREATE `work_order_operations`** (+ `uq_work_order_operation_uid`, `uq_work_order_operation_seq`, the FKs, the two amount CHECKs, `version`; D-3).
   - **Indexes** (D-2/D-3): `ix_work_orders_company/branch/product/status/open`, `ix_work_order_components_wo/company/product`, `ix_work_order_operations_wo/company`.
   - **`chk_stock_movement_type` widen** (DROP/ADD) — add `PRODUCTION_ISSUE, PRODUCTION_RECEIPT, PRODUCTION_ISSUE_REVERSAL, PRODUCTION_RECEIPT_REVERSAL`, **re-stating the full prior union** (incl. inventory-depth TRANSFER_* if V37 admitted them — superset rule). No new stock column (D-8).
   - **`chk_gl_config_key` widen** (DROP/ADD) — add `WIP_INVENTORY, FINISHED_GOODS, LABOUR_APPLIED, OVERHEAD_APPLIED, MANUFACTURING_VARIANCE`, **full-union superset** (incl. fixed-assets + hr keys if their migrations precede V74) (D-7).
   - **`chk_journal_batch_source_type` + `chk_journal_entry_source_type` widen** (DROP/ADD both) — add `PRODUCTION_ISSUE, PRODUCTION_RECEIPT, PRODUCTION_LABOUR, PRODUCTION_VARIANCE`, full-union superset (D-8).
   - **permission seed + grant** — INSERT `MANUFACTURING.VIEW`, `WORKORDER.MANAGE`, `WORKORDER.RELEASE`, `WORKORDER.CLOSE`, `WORKORDER.QC` (module `manufacturing`) `ON CONFLICT (code) DO NOTHING`; grant all five to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN (the V7 pattern). (Perms have no `uid` — #12 N/A.)
2. **`V75__manufacturing_coa_seed.sql`** — CoA + gl_configs back-seed for **existing** companies (D-7):
   - INSERT `1320 Work-In-Progress Inventory` (ASSET), `2350 Labour Applied` (LIABILITY), `2360 Overhead Applied` (LIABILITY), `5180 Manufacturing Variance` (EXPENSE) per company, `ON CONFLICT (company_id, account_code) DO NOTHING`. Seed-uid `'MFG' || lpad(c.id::text,6,'0') || account_code` (3+6+4 = 13 chars ≤ 26, the V12/V17 CoA-seed convention).
   - INSERT the five `gl_configs` mappings (`WIP_INVENTORY→1320`, `FINISHED_GOODS→1300`, `LABOUR_APPLIED→2350`, `OVERHEAD_APPLIED→2360`, `MANUFACTURING_VARIANCE→5180`) per company joining the CoA. **#12-safe seed-uid:** `'MFC' || lpad(coa.company_id::text,6,'0') || substr(md5(m.config_key),1,12)` (3+6+12 = 21 chars ≤ 26 — **never** `|| config_key`), `ON CONFLICT (company_id, config_key) DO NOTHING`.
3. **`V76__manufacturing_reserved.sql`** — RESERVED placeholder (normally empty / a no-op comment) for any v1 follow-up additive seed; kept so the range stays disjoint. **V77–V80 reserved** for deferred depth.

`MigrationKeepDataIT` extends to V74–V76 (the #12 seed-uid trap fires only on keep-data deploys where companies already exist; CI/Testcontainers DBs have no companies). `JournalSourceType` (Java) gains the four new tokens; `MovementType` (Java) gains the four new values; `GlConfigKey` (Java) gains the five new keys; `DomainEventType` gains the two events + aggregate; `code_sequence` gains the lazy `WORK_ORDER` kind. The valuation/explosion reuse (D-5) and the cost-centre pass-through (D-9) are **code-only** — no migration.

## Angular nav routes (D-14)

Under a new **Manufacturing** nav group (shell `MenuGroup` "Manufacturing"), gated `MANUFACTURING.VIEW`; append a lazy route block under `// manufacturing (ADR-0035)` in `admin.routes.ts` (the wave2 append protocol; disjoint `/admin/work-orders` prefix):
- `/admin/work-orders` — work-order list (filter by status / finished product / branch), gated `MANUFACTURING.VIEW`. Lazy `work-order-list.component`.
- `/admin/work-orders/uid/:uid` — work-order detail (header + component lines + operations + lifecycle actions [release/issue/apply-cost/complete/close/cancel] + cost-report tab), gated `MANUFACTURING.VIEW` (actions gated per the specific perm). Lazy `work-order-detail.component`.
- `/admin/work-orders/create` — new work order, gated `WORKORDER.MANAGE`. Lazy `work-order-create.component`.
- `/admin/manufacturing/wip-recon` — the WIP reconciliation report, gated `MANUFACTURING.VIEW`. Lazy `wip-recon.component`.

(Issue/apply/complete/release/close/cancel + the cost report are actions/tabs on the detail screen, not separate routes; they call the `WorkOrderController` transition endpoints.)

## API surface (D-1) — `WorkOrderController` under `/api/v1/work-orders` + `ManufacturingReportController` (flat in `com.erp.api`)

| method + path | perm gate | body / params | returns |
|---|---|---|---|
| `GET /api/v1/work-orders` | `@perm.has('MANUFACTURING.VIEW')` | `companyId`, `status?`, `finishedProductUid?`, `branchUid?`, `Pageable` | `Page<WorkOrderDto>` |
| `GET /api/v1/work-orders/uid/{uid}` | `@perm.scoped(#uid,'workorder','MANUFACTURING.VIEW')` | — | `WorkOrderDto` (header + components + operations) |
| `POST /api/v1/work-orders` | `@perm.has('WORKORDER.MANAGE')` | `CreateWorkOrderRequest{finishedProductUid, plannedQty, branchUid, bomUid?, plannedDate?, costCentreValueUid?, notes?}` | `WorkOrderDto` (PLANNED) |
| `PUT /api/v1/work-orders/uid/{uid}` | `@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')` | `UpdateWorkOrderRequest{plannedQty?, bomUid?, branchUid?, plannedDate?, costCentreValueUid?, notes?}` (PLANNED only) | `WorkOrderDto` |
| `POST /api/v1/work-orders/uid/{uid}/operations` | `@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')` | `AddOperationRequest{seqNo, description, workCentre?, labourAmount?, overheadAmount?}` (PLANNED/RELEASED) | `WorkOrderOperationDto` |
| `POST /api/v1/work-orders/uid/{uid}/release` | `@perm.scoped(#uid,'workorder','WORKORDER.RELEASE')` | `ReleaseWorkOrderRequest{bomUid?}` | `WorkOrderDto` (RELEASED; plan exploded) |
| `POST /api/v1/work-orders/uid/{uid}/issue` | `@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')` | `IssueComponentsRequest{full?, componentUids?, postingDate}` | `WorkOrderDto` (IN_PROGRESS; DR WIP / CR Inventory posted) |
| `POST /api/v1/work-orders/uid/{uid}/apply-cost` | `@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')` | `ApplyCostRequest{labourAmount?, overheadAmount?, operationUid?, postingDate}` | `WorkOrderDto` (DR WIP / CR clearing posted) |
| `POST /api/v1/work-orders/uid/{uid}/complete` | `@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')` | `CompleteWorkOrderRequest{goodQty, scrapQty?, allowOverRun?, postingDate}` | `WorkOrderDto` (COMPLETED; FG received at computed cost) |
| `POST /api/v1/work-orders/uid/{uid}/close` | `@perm.scoped(#uid,'workorder','WORKORDER.CLOSE')` | `CloseWorkOrderRequest{postingDate}` | `WorkOrderDto` (CLOSED; residual WIP → variance) |
| `POST /api/v1/work-orders/uid/{uid}/cancel` | `@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')` | — | `WorkOrderDto` (CANCELLED; issues/receipts reversed at original cost) |
| `GET /api/v1/work-orders/uid/{uid}/cost-report` | `@perm.scoped(#uid,'workorder','MANUFACTURING.VIEW')` | — | `WorkOrderCostReportDto` |
| `GET /api/v1/manufacturing/wip-reconciliation` | `@perm.has('MANUFACTURING.VIEW')` | `companyId` | `WipReconciliationDto` |

All read endpoints call `ScopeGuard.assertCanActIn` on the resolved company (the read-path guard, NFR-MFG-06). All write transitions assert scope + the lifecycle guard before posting. Responses are `ApiResponse<T>`-wrapped by `ApiResponseAdvice`.

---

## Summary

ADR-0035 designs **Manufacturing / Production** in a new `com.erp.modules.manufacturing` module: three new tables (`work_orders` header + `work_order_components` lines + `work_order_operations` routing), a `WorkOrderStatus` lifecycle (PLANNED→RELEASED→IN_PROGRESS→COMPLETED→CLOSED + CANCELLED), a costing orchestration that **explodes the ADR-0026 multi-level BOM** to its leaf components and **issues them out of stock at moving-average cost via the shipped ADR-0020 valuation engine** (`StockPostingService.post(... PRODUCTION_ISSUE ...)` + `InventoryValuationService.costIssue`) posting **DR WIP / CR Inventory**, holds the running cost as **WIP**, **receives the finished good back into stock at the computed unit cost** (WIP ÷ good qty) via `recomputeOnReceipt` posting **DR Inventory(FG) / CR WIP**, and clears residual WIP to **Manufacturing Variance** at close — all synchronous in the operator's command, period-gated.

**The load-bearing decisions:** (D-1/D-5) Manufacturing **reuses** the ADR-0020 valuation engine + the ADR-0026 explosion — **no second moving-average, no second BOM walk** (NFR-MFG-02), and the made good acquires its `avg_cost` through the SAME `recomputeOnReceipt` path a purchase uses; (D-4/D-11) GL/stock effects post **synchronously** in the human-act command (the ADR-0020 adjustment/opening posture), `WORKORDER.*` events are **informational** — so the WIP recon (Σ open-WO WIP == WIP GL, FR-MFG-14) has no eventual-consistency gap; (D-7/OQ-MFG-01) the NEW `FINISHED_GOODS` key maps to the **existing 1300** in v1 (keeps the inventory recon whole, one-line split later); (D-3/D-8) four new typed `PRODUCTION_*` movement types (forward + reversal) keep the production ledger lineage; (D-9) the WO cost-centre passes through the `LineDraft` dimension slots; (D-10) Manufacturing is a pure leaf consumer — `→ stock.service`, `→ products.service`, `→ gl` — with **no back-edge**, keeping the module graph acyclic.

**Shared-contract identifiers introduced (for the coordinator's collision check):**
- **New `gl_config` keys:** `WIP_INVENTORY`, `FINISHED_GOODS`, `LABOUR_APPLIED`, `OVERHEAD_APPLIED`, `MANUFACTURING_VARIANCE`.
- **New CoA account codes:** `1320` (WIP), `2350` (Labour Applied), `2360` (Overhead Applied), `5180` (Manufacturing Variance). `FINISHED_GOODS` maps to the **existing 1300** in v1 (no new account). *All four codes verified free vs shipped 1000–5400 + 2150/5160 and vs V20–V73 (procurement 2160; sales 4900/5170; fixed-assets 1600/1650/1700/3200/4200/5500; hr 1450/2400/2410/2420/2430/2440/2450/5200/5210; budgeting adds no CoA). **NOTE — `1350` is NOT used: ADR-0033 (projects) reserves `1350` for a deferred `PROJECT_WIP`, so manufacturing's WIP is `1320` to avoid that latent collision.*** 
- **New `JournalSourceType` tokens:** `PRODUCTION_ISSUE`, `PRODUCTION_RECEIPT`, `PRODUCTION_LABOUR`, `PRODUCTION_VARIANCE`.
- **New `MovementType` values:** `PRODUCTION_ISSUE`, `PRODUCTION_RECEIPT`, `PRODUCTION_ISSUE_REVERSAL`, `PRODUCTION_RECEIPT_REVERSAL` (admitted by the `chk_stock_movement_type` widen).
- **New `DomainEventType` values:** `WORK_ORDER.RELEASED`, `WORK_ORDER.COMPLETED` (+ aggregate `WORK_ORDER`). *No new outbox handler (producer only).*
- **New `ScopeGuard` cases:** `workorder`, `workordercomponent`, `workorderoperation`.
- **New perms:** `MANUFACTURING.VIEW`, `WORKORDER.MANAGE`, `WORKORDER.RELEASE`, `WORKORDER.CLOSE`, `WORKORDER.QC` (reserved).
- **New `code_sequence` kind:** `WORK_ORDER` (`WO-####`, lazy).
- **New nav routes:** `/admin/work-orders`, `/admin/work-orders/uid/:uid`, `/admin/work-orders/create`, `/admin/manufacturing/wip-recon`.
- **Migration range:** `V74__manufacturing.sql` + `V75__manufacturing_coa_seed.sql` + `V76__manufacturing_reserved.sql` (V77–V80 reserved for deferred depth).

**Cross-module touch list:** (1) `→ stock.service` valuation-engine reuse + the `chk_stock_movement_type` widen (the only ALTER to an in-flight table, additive); (2) `→ products.service` BOM explosion (the ADR-0026 designed-to contract); (3) `→ gl.service`/`gl.repository` synchronous posting + recon + the key/source-type widens + CoA seed; (4) `→ costing.service` optional dimension pass-through; (5) `ScopeGuard` + `DomainEventType` + `code_sequence` + `admin.routes.ts` + nav append (wave2 protocol). **Gated on multi-level BOM (ADR-0026, BUILT) + valuation/COGS (ADR-0020, shipped); depends on nothing downstream (pure leaf consumer).** **Additive on frozen V1–V19 + in-flight V20–V73; #12-safe** (the only per-company seeds are the V75 CoA/gl_config back-seed, with #12-safe uids).
