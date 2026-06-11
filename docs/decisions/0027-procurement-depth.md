# 0027 — Procurement / Purchase-to-Pay depth data model: a requisition→RFQ/quote→award→PO-approval-gate→landed-cost→purchase-return spine in `com.erp.modules.purchases`, where the **landed cost capitalises INTO inventory value** via a new `InventoryValuationService.applyLandedCost` primitive (raise `on_hand_value`, recompute `avg_cost`, qty unchanged) driven by a new `LANDED_COST.ALLOCATED` outbox event + handler against a new `LANDED_COST_CLEARING` GRNI-style bridge liability, the **purchase return** reverses the receipt at original cost (reusing the ADR-0020 `reverseReceipt` precedent) via a new `PURCHASE.RETURNED` event + handler and raises an AP debit note with a new `PURCHASE_RETURN` origin, and the **PO approval gate** integrates the not-yet-built approvals engine through a thin `approval_request_uid`/`approval_status` seam (in-module permission-gated fallback now, engine-swap later, no schema change) — all on the existing outbox / IdempotencyGuard / code_sequence / GLPostingService spine, additive as **V32–V36** on the frozen V1–V19

- **Status:** Proposed
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (consuming the architect-authored procurement-depth requirements — docs/requirements/procurement-depth.md FR-PROC-01..27, BR-PROC-01..13, NFR-PROC-01..09, §6 flows, §8 approvals dependency, §9 OQ log; the ground truth for every rule below). The eight design seams — the **landed-cost capitalisation primitive** (OQ-PROC-04), the **landed-cost allocation basis** (OQ-PROC-03), the **landed-cost accrual bridge** (OQ-PROC-08), the **PO approval-gate seam** (OQ-PROC-01), the **RFQ award granularity** (OQ-PROC-02), the **return cost basis + debit-note origin** (OQ-PROC-09), the **supplier-price reference** (BR-PROC-09), and the **migration split** — are the **decisions this ADR makes**, not requirements blockers.
- **Context source:** docs/requirements/procurement-depth.md (DRAFT 2026-06-11). Verified against the **shipped** code:
  - **Purchases** ([ADR-0011](0011-purchases-data-model.md) / [V8__purchases.sql](../../backend/src/main/resources/db/migration/V8__purchases.sql)): `PurchaseOrder` (`purchase_orders` — `id`, `uid` VARCHAR(26), `company_id`/`branch_id` scalar, `order_number` PO-#### NULL-while-DRAFT, `status` ∈ {DRAFT,ORDERED,PARTIALLY_RECEIVED,RECEIVED,CLOSED,VOID} `chk_purchase_order_status`, `supplier_id` scalar FK, `order_total_amount`, `@Version`); `PurchaseOrderLine` (`product_id`/`unit_id` scalar FK, `ordered_qty`/`ordered_qty_in_base`, `received_qty_in_base` maintained by `OutstandingTracker`, `unit_cost_amount`, `line_total_amount`); `GoodsReceipt`/`GoodsReceiptLine` (`goods_receipts` status ∈ {DRAFT,RECEIVED,VOID}; `goods_receipt_lines.received_qty`/`qty_in_base`/`unit_cost_amount`/`line_cost_amount`, FK `purchase_order_line_id`); `PurchaseOrderServiceImpl.place(...)` (DRAFT→ORDERED, the gate insertion point); `GoodsReceiptServiceImpl` publishes `STOCK.RECEIVED` with `StockReceivedPayload{ receiptUid, companyId, branchId, receivedAt, lines:[{ productId, productUid, unitId, qtyInBase, unitCostAmount }] }`; `PurchaseNumberGenerator` (PO-####/GRN-#### via `code_sequence`).
  - **Inventory Valuation & COGS** ([ADR-0020](0020-inventory-valuation-data-model.md) / [V17__inventory_valuation.sql](../../backend/src/main/resources/db/migration/V17__inventory_valuation.sql)): `StockOnHand` (`avg_cost` NUMERIC(19,4) nullable, `on_hand_value` NUMERIC(19,4) NOT NULL DEFAULT 0, `@Version`); `StockMovement` (`unit_cost_amount`/`value_amount`; `uq_stock_movement_source_event (source_event_uid, product_id)`; `movement_type` CHECK ∈ {GOODS_RECEIPT, SALE_ISSUE, SALE_REVERSAL, GOODS_RECEIPT_REVERSAL, ADJUSTMENT, OPENING_BALANCE}); **`InventoryValuationService`** with `recomputeOnReceipt` / `costIssue` / `reverseIssue` / **`reverseReceipt(companyId, branchId, productId, originalQty, originalValue)`** (the symmetric receipt-reversal this ADR's return reuses) / `setOpeningValue` / `revalueAdjustment` — **this ADR ADDS `applyLandedCost`** (raise value, recompute avg, qty unchanged); `StockPostingService.post(... unitCostAmount, valueAmount)` (MANDATORY, one-retry optimistic-lock upsert); `InventoryGlPoster` (`postCogsInNewTx` / `postSaleReversalInNewTx`, REQUIRES_NEW null-on-anomaly) — this ADR adds a landed-cost / return poster method; the GRNI clearing pattern (DR Inventory / CR GRNI at receipt; DR GRNI / CR AP at bill-match).
  - **AP** ([ADR-0015](0015-accounts-payable-data-model.md) / V12): `BillMatchServiceImpl.postMatchedBillToGl(SupplierBill)` (verified — partitions bill lines by `grLineUid`: goods→DR GRNI, service→DR PURCHASES, +DR VAT_INPUT / CR AP; idempotent on `(companyId, AP_BILL, bill.uid)`); `SupplierBillLine.grLineUid` (the goods-line signal — **a landed-cost freight bill line will carry a NEW `landed_cost_uid` signal so its net clears LANDED_COST_CLEARING**, D-7); **`ApDebitNoteService.raise(...)`** + `ApDebitNote` entity carrying `origin` + `ApDebitNoteOrigin` enum (**this ADR adds `PURCHASE_RETURN`**) + `chk_ap_debit_note_origin` (**widened additively**); `PurchaseMatchReader` (AP reads PO/GR DTOs).
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / V10): `GLPostingService.post(JournalEntryDraft)` + `GLPostingSafeInvoker.postInNewTx`; `GLConfigResolver.resolve(companyId, GlConfigKey)`; `GlConfigKey` (`INVENTORY`, `COGS`, `GRNI`, `STOCK_ADJUSTMENT`, `ACCOUNTS_PAYABLE`, `PURCHASES`, `VAT_INPUT` all defined — **this ADR adds `LANDED_COST_CLEARING`**); `JournalSourceType` (`STOCK_RECEIPT`, `COGS`, `STOCK_ADJUSTMENT`, `OPENING_INVENTORY`, `AP_BILL`, `AP_DEBIT_NOTE` admitted — **this ADR adds `LANDED_COST` + `PURCHASE_RETURN`**); `JournalLineRepository.accountBalance`.
  - **Outbox / idempotency** ([ADR-0009](0009-transactional-outbox.md)): `OutboxPublisher.publish(eventType, aggregateType, aggregateId, aggregateUid, companyId, branchId, payload)`; `DomainEventType` constants (`STOCK.RECEIVED`/`DELIVERY.CONFIRMED`/`DELIVERY.RETURNED` — **this ADR adds `LANDED_COST.ALLOCATED` + `PURCHASE.RETURNED`** + agg types `LANDED_COST` / `PURCHASE_RETURN`); `DomainEventHandler` + `IdempotencyGuard.alreadyProcessed(consumer, uid)`/`markProcessed`.
  - **Parties** ([ADR-0006](0006-parties-data-model.md) / V2): `suppliers` (`supplier_kind` GOODS|SERVICE, `ScopeGuard case "supplier"`).
  - **ScopeGuard** (verified): the `companyIdOf` switch (cases `purchaseorder`/`goodsreceipt`/`supplierbill`/`apdebitnote` already present) — **this ADR adds `requisition`/`rfq`/`supplierquote`/`landedcost`/`purchasereturn`** cases + the repository wiring.
  - **Approvals engine** (X.5 — **NOT BUILT**; procurement-depth.md §8): assumed `ApprovalService` contract; this ADR designs the **seam** (scalar `approval_request_uid` + cached `approval_status` columns + threshold config) and the **in-module permission-gated fallback** (no schema change on engine-swap).
  - **Money** ([ADR-0005](0005-money-and-currency.md)): base currency only (TZS), `NUMERIC(19,4)`, HALF_UP.
  - [[db-naming-convention]] verified against V1–V19 (plural masters/owned-children; singular constraint roots `uq_`/`fk_`/`chk_`; plural `ix_`; `uid VARCHAR(26)` ULID; `company_id`/`branch_id` BIGINT scalar; additive `DROP/ADD CONSTRAINT` widen). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **Latest shipped migration is `V19__sales_returns.sql`; this slice is `V32..V36` (additive; V1–V19 FROZEN; the V20–V31 gap is reserved for other in-flight modules — the coordinator owns collision detection).** This ADR is **0027**.

This ADR is the **technical data model + integration design** for Procurement / Purchase-to-Pay depth (PATH-TO-FULL-ERP §3.4, Phase B). It translates the requirements into the new requisition / RFQ / supplier-quote / landed-cost / purchase-return tables in `com.erp.modules.purchases`, the lifecycle enums + transitions, the **PO approval-gate seam** (and its engine-or-fallback decision), the **landed-cost capitalisation seam** (the new valuation primitive + the event + the handler + the clearing-liability bridge), the **purchase-return seam** (reverse-at-original-cost + the AP debit note), the API surface, the new `GlConfigKey` / `DomainEventType` / `JournalSourceType` / perms / `ScopeGuard` cases / Angular routes, the V32–V36 migration ordering with #12-safe seeds, and the ArchUnit edges. It is **concrete enough that the backend engineer builds without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step.

## Context

The procure-to-pay loop (PO→GR→bill→3-way-match→pay) ships and the inventory valuation engine (ADR-0020) gives a moving average + GRNI bridge + reverse-at-original-cost reversals. What is missing is the **upstream demand/governance** (requisition, RFQ/quote/award, PO approval) and the **cost-completeness** layers (landed cost, purchase returns). The forces:

- **THE KEY SEAM #1 — landed cost must capitalise INTO inventory value without a quantity movement (OQ-PROC-04).** The ADR-0020 engine recomputes `avg_cost` on a *quantity* receipt. Landed cost adds **value with no quantity change** — a value-only revaluation that raises `on_hand_value` and recomputes `avg_cost = on_hand_value / quantity`. The engine has `recomputeOnReceipt` (value+qty), `costIssue`/`reverseIssue` (value−), `revalueAdjustment` (value± at current avg, avg unchanged) — **none does "value+ at no qty, avg recomputed up"**. A new `applyLandedCost` primitive is required. Resolved in **D-5**.

- **THE KEY SEAM #2 — the landed-cost charge needs a home before it is billed (the LANDED_COST_CLEARING bridge, OQ-PROC-08).** A freight charge capitalised at allocation (DR Inventory) before the freight bill arrives needs a credit that is neither AP (not billed) nor an expense (it is capitalised). A **new LANDED_COST_CLEARING liability** holds it, cleared when the freight bill matches (DR LANDED_COST_CLEARING / CR AP) — the **exact GRNI pattern**. Resolved in **D-5/D-7**.

- **THE KEY SEAM #3 — the PO approval gate integrates an engine that does not exist yet (OQ-PROC-01).** The PO `DRAFT→ORDERED` transition must block over-threshold POs pending approval. The approvals engine (X.5) is unbuilt. The design must (a) place the gate now, (b) store only a thin scalar seam (`approval_request_uid` + cached `approval_status`), (c) ship a permission-gated in-module fallback approval, and (d) swap to the engine when it lands **without a schema change**. Resolved in **D-6**.

- **The return reverses the receipt at the original cost (OQ-PROC-09).** A purchase return puts stock OUT at the cost the receipt brought it in at (symmetric — the ADR-0020 `reverseReceipt` precedent), posting DR GRNI (or AP-clearing) / CR Inventory, and raises an AP debit note. Resolved in **D-8**.

- **Boundaries.** `purchases` reaches `stock`/`ap`/`gl` only by DTO + service-call + outbox (the shipped `ap→gl`, `stock→gl`, `sales→stock`/`sales→ar` precedents). It imports no cross-module entity. Resolved in **D-13**.

- **Schema freeze / direction.** V1–V19 frozen. This slice is additive across **V32–V36** (one migration per concern keeps each diff legible and lets stages ship independently — D-12): the new tables, the `stock_on_hand`/`stock_movements` reuse (no new columns needed — `applyLandedCost` reuses `on_hand_value`/`avg_cost`; the landed-cost movement is a new movement type), one new CoA account + key, one widened `ap_debit_note` origin CHECK + enum value, the new `JournalSourceType` tokens, the new `DomainEventType` constants, the new `code_sequence` kinds, the new permissions, and a new `stock_movements` movement-type widen for `LANDED_COST`.

## Decision

### D-1 — Module placement: everything lives in `com.erp.modules.purchases`; it gains outbound edges to `stock.service`, `ap.service`, and (later) `approvals`

The requisition / RFQ / quote / landed-cost / return documents live in **`com.erp.modules.purchases`** — it owns the PO + GR spine these extend, the `PurchaseNumberGenerator`, and the supplier/PO/GR reads. A separate module would re-read all of it. Reject.

Purchases gains outbound edges: **`purchases → stock.service`** (the landed-cost capitalisation + the return stock-out, via outbox events the stock module consumes — the `sales→stock` precedent, ADR-0021 D-13), **`purchases → ap.service`** (the return debit note via `ApDebitNoteService.raise`, and the landed-cost freight-bill clearing signal — the `ap→gl` cross-service-call precedent), and **`purchases → approvals`** (when X.5 lands — a scalar seam only, D-6). No `gl` edge is added to purchases itself — the GL legs for landed cost and the return are posted by the **stock-side handlers** (which already have the `stock→gl` edge, ADR-0020 D-12) and by AP's `ApDebitNoteService`.

Internal layout (additive to the shipped `purchases` package):

```
com.erp.modules.purchases
├── domain.entity   PurchaseRequisition, PurchaseRequisitionLine,
│                   Rfq, RfqLine, RfqSupplier,
│                   SupplierQuote, SupplierQuoteLine,
│                   LandedCost, LandedCostCharge, LandedCostAllocation,
│                   PurchaseReturn, PurchaseReturnLine
├── domain.dto      Requisition/Rfq/Quote/LandedCost/PurchaseReturn DTOs + request records,
│                   LandedCostAllocatedPayload (NEW outbox payload, D-5),
│                   PurchaseReturnedPayload    (NEW outbox payload, D-8)
├── domain.enums    RequisitionStatus, RfqStatus, SupplierQuoteStatus,
│                   LandedCostStatus, LandedCostChargeType, LandedCostBasis,
│                   PurchaseReturnStatus, PoApprovalStatus
├── repository      one repository per aggregate (+ findCompanyIdByUid for ScopeGuard, D-9)
└── service         PurchaseRequisitionService(+Impl), RfqService(+Impl),
                    SupplierQuoteService(+Impl), LandedCostService(+Impl),
                    PurchaseReturnService(+Impl),
                    SupplierPriceReader        — last-quoted-price lookup (BR-PROC-09),
                    PoApprovalGate             — the threshold + decision-source seam (D-6),
                    (PurchaseNumberGenerator extended with the new code_sequence kinds, D-10)
```

The landed-cost capitalisation primitive (`applyLandedCost`) and the return stock-out + GL legs live in **`stock`** (the `InventoryValuationService` + a new `LandedCostStockHandler` / `PurchaseReturnStockHandler` in `stock.events`), consuming the new events — the same shape as `DeliveryIssueStockHandler` consuming `sales.domain.dto`. Controllers stay flat in `com.erp.api`: `PurchaseRequisitionController`, `RfqController`, `SupplierQuoteController`, `LandedCostController`, `PurchaseReturnController`, and the PO-approval actions extend the existing `PurchaseOrderController`.

### D-2 — Lifecycle + status enums (exact sets + transitions)

Seven new enums in `purchases.domain.enums`. Every transition is service-guarded, audited, append-only (BR-PROC-12).

**`RequisitionStatus`** (FR-PROC-01..05):
```
DRAFT ──submit──▶ SUBMITTED ──approve──▶ APPROVED ──convert──▶ CONVERTED   (terminal)
  │                   │                      
  │                   └──reject──▶ REJECTED   (terminal)
  └──(hard delete while DRAFT; consumed no number)
{DRAFT, SUBMITTED} ──cancel──▶ CANCELLED   (terminal)
```
`PR-####` at submit. APPROVED converts once (BR-PROC-04). `chk_purchase_requisition_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CONVERTED','CANCELLED'))`.

**`RfqStatus`** (FR-PROC-06..10):
```
DRAFT ──send──▶ SENT ──(a quote captured)──▶ QUOTES_RECEIVED ──award──▶ AWARDED   (terminal)
  │              │
  └──────────────┴──cancel──▶ CANCELLED   (terminal)
```
`RFQ-####` at create. Awards once (BR-PROC-04). `chk_rfq_status CHECK (status IN ('DRAFT','SENT','QUOTES_RECEIVED','AWARDED','CANCELLED'))`.

**`SupplierQuoteStatus`** (FR-PROC-08): `RECEIVED ──(rfq awarded to it)──▶ AWARDED` / `──(rfq awarded elsewhere)──▶ NOT_AWARDED`. `SQ-####` at capture. `chk_supplier_quote_status CHECK (status IN ('RECEIVED','AWARDED','NOT_AWARDED'))`.

**`LandedCostStatus`** (FR-PROC-16..18): `DRAFT ──confirm──▶ CONFIRMED` (created DRAFT so charges/basis are set, then confirm capitalises — atomic). `LC-####` at create. Immutable once CONFIRMED. `chk_landed_cost_status CHECK (status IN ('DRAFT','CONFIRMED'))`.

**`LandedCostChargeType`** = `FREIGHT | DUTY | CLEARING | INSURANCE | OTHER`. **`LandedCostBasis`** = `BY_VALUE | BY_QUANTITY` (BY_VALUE default, OQ-PROC-03; BY_WEIGHT/manual deferred).

**`PurchaseReturnStatus`** (FR-PROC-21..24): `DRAFT ──confirm──▶ CONFIRMED` (created DRAFT, confirm does stock-out + debit note atomically — mirrors the ADR-0021 delivery/return single-step). `PRET-####` at create. Immutable once CONFIRMED. `chk_purchase_return_status CHECK (status IN ('DRAFT','CONFIRMED'))`.

**`PoApprovalStatus`** (the gate seam, D-6) = `NOT_REQUIRED | PENDING | APPROVED | REJECTED`. Stored on `purchase_orders` (additive column — see D-6). Default `NOT_REQUIRED` (below threshold or gate disabled).

### D-3 — Requisition tables: `purchase_requisitions` + `purchase_requisition_lines`

All tables: `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) ULID `uq_<root>_uid`; `company_id` + `branch_id` BIGINT NOT NULL (denormalised onto child lines, set-once-immutable); standard audit cols; `@Version` on headers. Money `NUMERIC(19,4)`; quantity `NUMERIC(19,6)`. Cross-module refs (product, unit, supplier) are scalar `Long` id + real FK; cross-document refs are scalar `uid` VARCHAR(26).

#### `purchase_requisitions` (header)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | BIGINT / VARCHAR(26) | NO | `uq_purchase_requisition_uid` |
| `company_id` / `branch_id` | BIGINT | NO | tenant; `fk_purchase_requisition_company`/`_branch` |
| `requisition_number` | VARCHAR(30) | YES | `PR-####`; NULL while DRAFT, assigned at submit (D-10) |
| `status` | VARCHAR(20) | NO | `RequisitionStatus`; DEFAULT `'DRAFT'`; `chk_purchase_requisition_status` |
| `required_by_date` | DATE | YES | when the demand is needed |
| `cost_centre_code` | VARCHAR(40) | YES | free-text cost-centre tag (OQ-PROC-06; not an FK in v1) |
| `approval_request_uid` | VARCHAR(26) | YES | the approvals-engine request (scalar, no FK) — set at submit (D-6) |
| `approval_status` | VARCHAR(20) | YES | cached `PoApprovalStatus`-style outcome on the PR; diagnostic mirror |
| `converted_to_type` | VARCHAR(20) | YES | `PURCHASE_ORDER` \| `RFQ` — what convert produced |
| `converted_to_uid` | VARCHAR(26) | YES | the produced PO/RFQ uid |
| `notes` | VARCHAR(500) | YES | |
| `submitted_at`/`approved_at`/`rejected_at`/`converted_at`/`cancelled_at` | TIMESTAMPTZ | YES | transition stamps |
| `submitted_by`/`approved_by`/`rejected_by` | BIGINT | YES | FK → `app_users(id)` |
| `reject_reason`/`cancel_reason` | VARCHAR(255) | YES | |
| `version` + audit cols | | | |

Constraints: `uq_purchase_requisition_company_number UNIQUE (company_id, requisition_number)` (NULLs distinct); `chk_purchase_requisition_number_when_submitted CHECK ((status = 'DRAFT' AND requisition_number IS NULL) OR (status <> 'DRAFT' AND requisition_number IS NOT NULL))`; `fk_purchase_requisition_submitted_by`/`_approved_by`/`_rejected_by` → `app_users`.

#### `purchase_requisition_lines` (child)

`id`, `uid` (`uq_purchase_requisition_line_uid`), `purchase_requisition_id` FK, `company_id`/`branch_id` denormalised, `line_no` SMALLINT (`uq_purchase_requisition_line_no UNIQUE (purchase_requisition_id, line_no)`), `product_id` scalar FK, `product_code`/`product_name` snapshots, `unit_id` scalar FK, `unit_name` snapshot, `requested_qty` NUMERIC(19,6) `CHECK > 0`, `requested_qty_in_base` NUMERIC(19,6) `CHECK > 0`, `estimated_unit_cost` NUMERIC(19,4) NULL (`CHECK (estimated_unit_cost IS NULL OR estimated_unit_cost >= 0)`), `note` VARCHAR(255), `currency` VARCHAR(3), audit. FKs `fk_purchase_requisition_line_product`/`_unit`/`_company`/`_branch`/`_requisition`.

### D-4 — RFQ + supplier-quote tables: `rfqs` + `rfq_lines` + `rfq_suppliers`; `supplier_quotes` + `supplier_quote_lines`

#### `rfqs` (header)
`id`/`uid` (`uq_rfq_uid`), `company_id`/`branch_id`, `rfq_number` VARCHAR(30) NOT NULL (`RFQ-####` at create; `uq_rfq_company_number`), `status` VARCHAR(20) DEFAULT `'DRAFT'` (`chk_rfq_status`), `source_requisition_uid` VARCHAR(26) NULL (the PR it seeded from), `response_due_date` DATE NULL, `awarded_quote_uid` VARCHAR(26) NULL (set on award), `awarded_po_uid` VARCHAR(26) NULL (the PO generated), `notes` VARCHAR(500), `sent_at`/`awarded_at`/`cancelled_at` TIMESTAMPTZ, `version` + audit.

#### `rfq_lines` (child)
`id`/`uid` (`uq_rfq_line_uid`), `rfq_id` FK, `company_id`/`branch_id`, `line_no` (`uq_rfq_line_no UNIQUE (rfq_id, line_no)`), `product_id`/`product_code`/`product_name`/`unit_id`/`unit_name` (snapshot), `quantity` NUMERIC(19,6) `CHECK > 0`, `quantity_in_base` NUMERIC(19,6) `CHECK > 0`, audit.

#### `rfq_suppliers` (junction — singular junction-table naming)
`id`/`uid` (`uq_rfq_supplier_uid`), `rfq_id` FK, `supplier_id` scalar FK, `company_id`/`branch_id`, `sent_at` TIMESTAMPTZ NULL, audit. `uq_rfq_supplier UNIQUE (rfq_id, supplier_id)`; `fk_rfq_supplier_rfq`/`_supplier`.

#### `supplier_quotes` (header)
`id`/`uid` (`uq_supplier_quote_uid`), `company_id`/`branch_id`, `quote_number` VARCHAR(30) NOT NULL (`SQ-####` at capture; `uq_supplier_quote_company_number`), `rfq_id` BIGINT FK + `rfq_uid` VARCHAR(26) (the RFQ it answers), `supplier_id` scalar FK + `supplier_code`/`supplier_name` snapshot, `status` VARCHAR(20) DEFAULT `'RECEIVED'` (`chk_supplier_quote_status`), `valid_until` DATE NULL, `lead_time_days` SMALLINT NULL (`CHECK (lead_time_days IS NULL OR lead_time_days >= 0)`), `quote_total_amount` NUMERIC(19,4) NOT NULL DEFAULT 0, `currency` VARCHAR(3), `notes` VARCHAR(500), `version` + audit. `fk_supplier_quote_rfq`/`_supplier`.

#### `supplier_quote_lines` (child)
`id`/`uid` (`uq_supplier_quote_line_uid`), `supplier_quote_id` FK, `rfq_line_id` BIGINT FK + `rfq_line_uid` VARCHAR(26) (the RFQ line this prices), `company_id`/`branch_id`, `line_no` (`uq_supplier_quote_line_no UNIQUE (supplier_quote_id, line_no)`), `product_id` scalar FK + snapshots, `unit_id`/`unit_name`, `quoted_qty`/`quoted_qty_in_base` NUMERIC(19,6) `CHECK > 0`, `unit_price_amount` NUMERIC(19,4) NOT NULL `CHECK (>= 0)`, `line_total_amount` NUMERIC(19,4) NOT NULL, `currency`, audit.

**Award (FR-PROC-10, whole-quote default OQ-PROC-02):** `RfqService.award(rfqUid, quoteUid)` sets the RFQ `awarded_quote_uid`/AWARDED, the winning quote AWARDED + the others NOT_AWARDED, and calls `PurchaseOrderService.createFromQuote(quoteUid)` (a new method in the shipped PO service) which copies each `supplier_quote_line` into a `purchase_order_line` carrying the **quoted unit price** as `unit_cost_amount`, opens the PO **DRAFT** (placed deliberately, going through the approval gate), sets a `source_quote_uid` (a new additive `purchase_orders` column — D-6/D-10). Per-line split award deferred.

**Supplier-price reference (BR-PROC-09):** `SupplierPriceReader.lastQuotedUnitCost(companyId, supplierId, productId) → Optional<BigDecimal>` reads the most recent `supplier_quote_lines.unit_price_amount` (by quote date) — a read convenience for defaulting RFQ/PO line costs. No separate price-list table in v1 (it reuses the captured quote rows). The index `ix_supplier_quote_lines_supplier_product` (below) backs it.

### D-5 — THE KEY SEAM #1+#2: landed cost capitalises INTO inventory via a new valuation primitive + a new event/handler + the LANDED_COST_CLEARING bridge

#### `landed_costs` (header)
`id`/`uid` (`uq_landed_cost_uid`), `company_id`/`branch_id`, `landed_cost_number` VARCHAR(30) NOT NULL (`LC-####` at create; `uq_landed_cost_company_number`), `status` VARCHAR(20) DEFAULT `'DRAFT'` (`chk_landed_cost_status`), `basis` VARCHAR(20) NOT NULL DEFAULT `'BY_VALUE'` (`chk_landed_cost_basis CHECK (basis IN ('BY_VALUE','BY_QUANTITY'))`), `total_charge_amount` NUMERIC(19,4) NOT NULL DEFAULT 0, `currency` VARCHAR(3), `gl_entry_uid` VARCHAR(26) NULL (the capitalisation journal, set by the handler — diagnostic), `notes` VARCHAR(500), `confirmed_at` TIMESTAMPTZ, `confirmed_by` BIGINT, `version` + audit.

#### `landed_cost_receipts` (junction — the receipts this LC covers)
`id`/`uid` (`uq_landed_cost_receipt_uid`), `landed_cost_id` FK, `goods_receipt_id` BIGINT FK → `goods_receipts(id)` + `goods_receipt_uid` VARCHAR(26), `company_id`/`branch_id`, audit. `uq_landed_cost_receipt UNIQUE (landed_cost_id, goods_receipt_id)`.

#### `landed_cost_charges` (child — the charge lines)
`id`/`uid` (`uq_landed_cost_charge_uid`), `landed_cost_id` FK, `company_id`/`branch_id`, `line_no` (`uq_landed_cost_charge_no UNIQUE (landed_cost_id, line_no)`), `charge_type` VARCHAR(20) NOT NULL (`chk_landed_cost_charge_type CHECK (charge_type IN ('FREIGHT','DUTY','CLEARING','INSURANCE','OTHER'))`), `amount` NUMERIC(19,4) NOT NULL `CHECK (> 0)`, `is_billed` BOOLEAN NOT NULL DEFAULT false, `supplier_bill_uid` VARCHAR(26) NULL (the freight bill, when `is_billed`; scalar), `currency`, audit. `chk_landed_cost_charge_billed CHECK ((is_billed = false AND supplier_bill_uid IS NULL) OR (is_billed = true AND supplier_bill_uid IS NOT NULL))`.

#### `landed_cost_allocations` (child — the computed per-receipt-line allocation; the audit of the maths)
`id`/`uid` (`uq_landed_cost_allocation_uid`), `landed_cost_id` FK, `goods_receipt_line_id` BIGINT FK → `goods_receipt_lines(id)` + `goods_receipt_line_uid` VARCHAR(26), `product_id` scalar FK, `company_id`/`branch_id`, `allocated_amount` NUMERIC(19,4) NOT NULL `CHECK (>= 0)`, `currency`, audit. `uq_landed_cost_allocation UNIQUE (landed_cost_id, goods_receipt_line_id)`. (One row per covered receipt line — the share of `total_charge_amount` it absorbed.)

#### The new valuation primitive (the load-bearing addition to `InventoryValuationService`)

```java
/**
 * Capitalise a landed-cost amount INTO inventory value at NO quantity change (ADR-0027 D-5).
 * Raises on_hand_value by addedValue and recomputes avg_cost = on_hand_value / quantity
 * (HALF_UP 4 dp) when quantity > 0; when quantity <= 0 (goods already issued — BR-PROC-07),
 * the value is still added to on_hand_value and avg_cost is left unchanged (carried residual),
 * a WARN + anomaly is recorded. Under the @Version lock with one retry. Returns the new on_hand_value.
 */
BigDecimal applyLandedCost(Long companyId, Long branchId, Long productId, BigDecimal addedValue);
```

This is the missing third primitive: `recomputeOnReceipt` (qty+value+, avg recomputed), `revalueAdjustment` (qty±value± at current avg, avg unchanged), and now `applyLandedCost` (qty 0, value+, avg recomputed up). It writes a **`LANDED_COST` movement** (new movement type, qty = 0 is disallowed by `chk_stock_movement_qty <> 0` — **so the landed-cost capitalisation records NO stock_movement row**; it is a pure value revaluation on `stock_on_hand`, with the per-product audit captured in `landed_cost_allocations` and the GL leg). *(Mirrors ADR-0020 D-5b opening valuation, which posts no movement row for a pure revaluation.)* **No new movement type is actually needed** — the capitalisation touches only `stock_on_hand.on_hand_value`/`avg_cost`; **strike the `LANDED_COST` movement type** (D-12 reflects this: no `chk_stock_movement_type` widen for landed cost).

#### The event + handler (idempotent, stock-side, the ADR-0020 seam shape)

`LandedCostService.confirm(landedCostUid)` (its own TX): validates DRAFT, computes each `landed_cost_allocations` row by the `basis` (BY_VALUE: `share = round4(total_charge × line.line_cost_amount / Σ line_cost_amount)`; BY_QUANTITY: pro-rata to `qty_in_base`; the largest-remainder method assigns the rounding residual to the largest line so `Σ allocated == total_charge` exactly), persists the allocations, flips to CONFIRMED, and **publishes in the same TX**:
```
DomainEventType.LANDED_COST_ALLOCATED = "LANDED_COST.ALLOCATED"   (NEW constant)
aggregateType = "LANDED_COST"                                      (NEW agg constant)
payload = LandedCostAllocatedPayload(
    landedCostUid, companyId, branchId, postingDate, basis, currency,
    boolean accrued,                       // true if ANY charge is not billed → CR LANDED_COST_CLEARING
    List<Allocation(productId, productUid, goodsReceiptLineUid, allocatedAmount)>
)
```

`LandedCostStockHandler` (in `stock.events`, under `IdempotencyGuard("STOCK.LANDED_COST")`):
- for each allocation: call `InventoryValuationService.applyLandedCost(companyId, branchId, productId, allocatedAmount)` (raise value, recompute avg);
- post **one GL journal**: **DR `INVENTORY` (1300)** = Σ allocatedAmount; **CR `LANDED_COST_CLEARING` (2160, NEW key)** = same (when `accrued`); via `InventoryGlPoster.postLandedCostInNewTx(companyId, branchId, postingDate, landedCostUid, currency, allocations)` (REQUIRES_NEW, null-on-anomaly), `sourceType = LANDED_COST` (NEW), `sourceRef = landedCostUid`. (When a charge is **billed** at capture — `is_billed` — the credit goes to LANDED_COST_CLEARING all the same and the referenced freight bill must have posted DR LANDED_COST_CLEARING / CR AP, so the clearing nets — keeping ONE credit account simplifies the handler; the billed-vs-accrued distinction lives in how the freight bill is matched, D-7. **Decision: always CR LANDED_COST_CLEARING from the capitalisation; the freight bill always DRs LANDED_COST_CLEARING.** This is the clean GRNI-symmetric choice and removes the two-path branch.)

**Why a stock-side handler not a synchronous post in `purchases`:** the capitalisation mutates `stock_on_hand` (the valuation engine's authority) and must reconcile to GL 1300 — exactly the ADR-0020 stance that stock owns valuation + posts GL. `purchases` emits the event; `stock` capitalises + posts. Symmetry with the delivery seam, idempotency, crash-safety (NFR-PROC-02/06).

#### Clearing reconciliation (FR-PROC-19, BR-PROC-08)

The freight/duty **supplier bill** (entered in AP as today) must debit **LANDED_COST_CLEARING** instead of PURCHASES for its freight lines. The signal: a `supplier_bill_lines` row gains an additive scalar **`landed_cost_uid`** (VARCHAR(26), nullable — D-7); when set, `postMatchedBillToGl` debits LANDED_COST_CLEARING for that line's net (the exact `grLineUid`→GRNI swap precedent). After capitalisation (CR LANDED_COST_CLEARING) + bill match (DR LANDED_COST_CLEARING), the clearing nets to zero for fully-billed landed cost.

### D-6 — THE KEY SEAM #3: the PO approval gate (engine-or-fallback, no schema change on swap)

**Additive `purchase_orders` columns (V-migration, additive):**

| column | type | null | default | notes |
|---|---|---|---|---|
| `approval_status` | VARCHAR(20) | NO | `'NOT_REQUIRED'` | `PoApprovalStatus`; `chk_purchase_order_approval_status CHECK (approval_status IN ('NOT_REQUIRED','PENDING','APPROVED','REJECTED'))` |
| `approval_request_uid` | VARCHAR(26) | YES | NULL | the approvals-engine request uid (scalar, no FK) — set when submitted for approval |
| `source_quote_uid` | VARCHAR(26) | YES | NULL | the awarded `supplier_quotes.uid` this PO came from (D-4 award; NULL if directly created) |
| `source_requisition_uid` | VARCHAR(26) | YES | NULL | the PR this PO converted from (D-3; NULL if directly created) |

(Existing ORDERED/RECEIVED/CLOSED/VOID rows back-fill `approval_status = 'NOT_REQUIRED'` — correct: they were all placed pre-gate.)

**The threshold config.** A per-company approval threshold lives as a **new `gl_config`-adjacent setting** — but `gl_configs` maps keys→accounts, not amounts. **Decision: a lightweight new `purchase_settings` table** (`id`/`uid`, `company_id` UNIQUE, `po_approval_threshold_amount` NUMERIC(19,4) NULL, `po_approval_enabled` BOOLEAN NOT NULL DEFAULT false, `currency`, audit) — `uq_purchase_settings_company UNIQUE (company_id)`. `po_approval_enabled = false` ⇒ gate off (today's behaviour). `threshold = 0` + enabled ⇒ all POs need approval. Seeded per company (gate off by default, V-migration #12-safe seed).

**`PoApprovalGate` (the seam):**
```
boolean approvalRequired(companyId, orderTotalAmount):
    settings = purchaseSettings.find(companyId)
    return settings.enabled && orderTotalAmount >= settings.threshold

// on PO place (DRAFT → ORDERED), inserted into the shipped PurchaseOrderServiceImpl.place:
if approvalRequired(...) && po.approval_status != APPROVED:
    reject "PO requires approval (total >= threshold)"   // FR-PROC-13
```

**The decision source — engine-or-fallback (OQ-PROC-01):**
- **When the approvals engine (X.5) exists:** `submitForApproval(poUid)` calls `ApprovalService.submit("PURCHASE_ORDER", poUid, companyId, total, requestedBy)`, stores `approval_request_uid`, sets `approval_status = PENDING`. A `APPROVAL.DECIDED` outbox event (consumed by a thin `purchases.events.PoApprovalDecisionHandler`) flips `approval_status` to APPROVED/REJECTED. The same gate logic then admits/denies ORDERED.
- **Fallback (build now, no engine):** a permission-gated manual action `approvePo(poUid)` / `rejectPo(poUid)` gated `PURCHASE.ORDER.APPROVE` flips `approval_status` to APPROVED/REJECTED in-module (audited, with the approver). **The threshold, the columns, the gate logic are identical; only the decision source differs.** Swapping in the engine replaces the manual action with the `ApprovalService` call + the decision handler — **no schema change** (the columns already exist). This ADR ships the **fallback**; an engine-integration ADR (when X.5 lands) flips the source. The **requisition** approval (FR-PROC-02/03) uses the same seam: `approval_request_uid`/`approval_status` columns on `purchase_requisitions` (D-3) + a `PURCHASE.REQUISITION.APPROVE` manual flip now.

### D-7 — Purchase-return tables + the AP touch; `supplier_bill_lines.landed_cost_uid` additive column

#### `purchase_returns` (header)
`id`/`uid` (`uq_purchase_return_uid`), `company_id`/`branch_id`, `return_number` VARCHAR(30) NOT NULL (`PRET-####` at create; `uq_purchase_return_company_number`), `status` VARCHAR(20) DEFAULT `'DRAFT'` (`chk_purchase_return_status`), `goods_receipt_id` BIGINT FK → `goods_receipts(id)` + `goods_receipt_uid` VARCHAR(26) (the receipt being returned), `supplier_id` scalar FK + snapshots, `reason` VARCHAR(255) NOT NULL, `net_amount`/`vat_amount`/`gross_amount` NUMERIC(19,4) NOT NULL DEFAULT 0, `currency`, `debit_note_uid` VARCHAR(26) NULL (the AP debit note raised; set on confirm), `gl_entry_uid` VARCHAR(26) NULL (the stock-out reversal journal; diagnostic), `confirmed_at`/`confirmed_by`, `version` + audit.

#### `purchase_return_lines` (child)
`id`/`uid` (`uq_purchase_return_line_uid`), `purchase_return_id` FK, `goods_receipt_line_id` BIGINT FK → `goods_receipt_lines(id)` + `goods_receipt_line_uid` VARCHAR(26), `company_id`/`branch_id`, `line_no` (`uq_purchase_return_line_no UNIQUE (purchase_return_id, line_no)`), `product_id` scalar FK + snapshots, `unit_id`/`unit_name`, `returned_qty` NUMERIC(19,6) `CHECK > 0`, `returned_qty_in_base` NUMERIC(19,6) `CHECK > 0`, `unit_cost_amount` NUMERIC(19,4) (the original receipt cost — for the debit-note value), `line_value_amount` NUMERIC(19,4) (the original-cost value reversed), `currency`, audit.

**`goods_receipt_lines` gains a maintained `returned_qty_in_base`** (additive column, V-migration): NUMERIC(19,6) NOT NULL DEFAULT 0, `chk_goods_receipt_line_returned CHECK (returned_qty_in_base >= 0 AND returned_qty_in_base <= qty_in_base)` — the over-return DB backstop (BR-PROC-10); maintained by the return-confirm in the same TX (mirrors `OutstandingTracker` on PO lines).

#### The return flow (`PurchaseReturnService.confirm`, one TX, the ADR-0021-return shape)

For each return line (guard `returned_qty_in_base <= grLine.qty_in_base − grLine.returned_qty_in_base` — BR-PROC-10, service + the new CHECK):
1. flip DRAFT→CONFIRMED; increment `goods_receipt_lines.returned_qty_in_base`; compute `line_value_amount` from the **original receipt cost** (read the original `GOODS_RECEIPT` `stock_movements` row by `source_document_uid = goodsReceiptUid` / apportion for partials — OQ-PROC-09; or the denormalised `goods_receipt_lines.unit_cost_amount` as the convenience);
2. **publish in the same TX:**
```
DomainEventType.PURCHASE_RETURNED = "PURCHASE.RETURNED"          (NEW constant)
aggregateType = "PURCHASE_RETURN"                                 (NEW agg constant)
payload = PurchaseReturnedPayload(
    purchaseReturnUid, goodsReceiptUid, companyId, branchId, returnDate, currency,
    boolean billed,    // true if the receipt's goods bill already matched (clears AP) vs GRNI not-yet-billed
    List<LineItem(productId, productUid, unitId, returnedQtyInBase, originalValue)>
)
```
3. `PurchaseReturnStockHandler` (in `stock.events`, under `IdempotencyGuard("STOCK.PURCHASE_RETURN")`): for each line, recipe-explode if composed (BR-PROC-13, reuse `RecipeExplosionResolver`), post a stock-OUT `GOODS_RECEIPT_REVERSAL` movement (the **existing** type — reverses a receipt; `source_document_type = "PURCHASE_RETURN"`, `source_document_uid = purchaseReturnUid`, `unit_cost`/`value` from the original) and call **`InventoryValuationService.reverseReceipt(...)`** (the ADR-0020 D-5 symmetric inverse — back out value, recompute avg), then post **one GL journal: DR `GRNI` (2150) / CR `INVENTORY` (1300)** at the returned original value via `InventoryGlPoster.postSaleReversalInNewTx`-analogue (**a new `postReceiptReversalInNewTx`**, REQUIRES_NEW), `sourceType = PURCHASE_RETURN` (NEW), `sourceRef = purchaseReturnUid`. **GRNI is debited** (reversing the receipt's CR-GRNI accrual) for goods not yet billed; if the goods bill already matched (cleared GRNI to AP), the return instead DRs **ACCOUNTS_PAYABLE** via the debit note (step 4) and the GRNI leg is skipped — **the `billed` flag selects: not-billed ⇒ DR GRNI / CR Inventory; billed ⇒ CR Inventory / DR via the debit note's AP leg.** *(Decision: keep it simple and correct — the stock handler always posts DR GRNI / CR Inventory at the original cost to undo the inventory capitalisation; the debit note (step 4) handles the payable side. When the bill already matched, the GRNI was cleared, so the DR GRNI re-opens it and the freight/goods debit-note nets it — the books stay balanced because the debit note CRs GRNI or AP per its own posting. The engineer reconciles this against the shipped `ApDebitNoteServiceImpl` legs — flagged as the one place to verify against shipped AP posting, Open items.)*
4. **raise the AP debit note (REUSE `ApDebitNoteService`):** synchronously in the return-confirm TX (AP is a synchronous service, the ADR-0015 stance), `ApDebitNoteService.raise(...)` with the returned net/vat and a **new `origin = PURCHASE_RETURN`**, referencing the supplier + the goods bill (if matched) so the credit reduces the right payable; set `purchase_returns.debit_note_uid`.

**AP change required (within `ap`, additive):** `ApDebitNoteOrigin` enum gains `PURCHASE_RETURN`; `chk_ap_debit_note_origin` widened additively (`DROP/ADD CONSTRAINT`) to include it; `RaiseDebitNoteRequest` gains an additive `ApDebitNoteOrigin origin` field defaulting the existing value (existing callers unaffected). `supplier_bill_lines` gains the additive scalar **`landed_cost_uid` VARCHAR(26) NULL** (D-5 clearing signal) and `postMatchedBillToGl` partitions: a line with `landed_cost_uid != null` debits **LANDED_COST_CLEARING** (the GRNI-swap precedent extended). These are the AP touches; all within `ap` (purchases calls `ap.service`, AP returns DTOs — D-13).

### D-8 — GL postings: exact legs, keys, source types

All base currency, HALF_UP, via `InventoryGlPoster` (event-driven, REQUIRES_NEW null-on-anomaly) or `ApDebitNoteService`/`BillMatchService` (synchronous AP). Accounts via `GLConfigResolver.resolve`.

| event/action | legs | key(s) | sourceType | sourceRef |
|---|---|---|---|---|
| **Landed cost confirm** (`LandedCostStockHandler`) | DR `INVENTORY` (1300) / CR `LANDED_COST_CLEARING` (2160) at Σ allocated | `INVENTORY`, `LANDED_COST_CLEARING` (NEW) | `LANDED_COST` (NEW) | landedCostUid |
| **Freight bill match** (existing `postMatchedBillToGl`, extended) | DR `LANDED_COST_CLEARING` (freight-line net) [+ DR GRNI goods / DR PURCHASES service / DR VAT_INPUT] / CR `ACCOUNTS_PAYABLE` (gross) | `LANDED_COST_CLEARING`, existing | `AP_BILL` (unchanged) | bill.uid |
| **Purchase return confirm — stock** (`PurchaseReturnStockHandler`) | DR `GRNI` (2150) / CR `INVENTORY` (1300) at original value (not-yet-billed); billed ⇒ AP side via debit note | `GRNI`, `INVENTORY` | `PURCHASE_RETURN` (NEW) | purchaseReturnUid |
| **Purchase return confirm — payable** (`ApDebitNoteService.raise`) | the shipped debit-note legs (CR PURCHASES/GRNI or AP per ApDebitNoteServiceImpl) with origin `PURCHASE_RETURN` | existing | `AP_DEBIT_NOTE` (unchanged) | debitNote.uid |

Requisition / RFQ / quote / award / PO-approval post **no GL** (BR-PROC-02). The PO place is a commitment, not a posting (unchanged from today).

### D-9 — New CoA account + `gl_config` key

One new posting role (added to `GlConfigKey` Java + admitted by `chk_gl_config_key`):

| key (NEW) | account code (NEW) | name | `AccountType` / normal balance | role |
|---|---|---|---|---|
| `LANDED_COST_CLEARING` | `2160` | Landed Cost Clearing | `LIABILITY` / CREDIT | the freight/duty capitalisation→bill bridge (CR at capitalisation, DR at freight-bill match) |

`INVENTORY (1300)`, `GRNI (2150)`, `ACCOUNTS_PAYABLE (2100)`, `PURCHASES (5150)`, `VAT_INPUT (1400)` all exist (ADR-0020/0017/0015). Seeding (two surfaces): add `2160 → LIABILITY` to `ChartOfAccountServiceImpl.DEFAULT_ACCOUNTS` and `LANDED_COST_CLEARING → 2160` to `GlConfigServiceImpl.DEFAULT_MAPPINGS` (new companies) + a back-seed in the V-migration for existing companies (#12-safe — D-12). (An `InventoryGlSeeder`-style belt-and-braces seeder is optional, mirroring ADR-0020 D-8.)

### D-10 — Permissions + scope + numbering

**New permissions** (V-migration seed + `ORG_ADMIN` grant, the V8/V12 CROSS-JOIN pattern; module `purchases`):

| code | description |
|---|---|
| `PURCHASE.REQUISITION.VIEW` | View/list requisitions |
| `PURCHASE.REQUISITION.CREATE` | Create/edit/submit/cancel a requisition |
| `PURCHASE.REQUISITION.APPROVE` | Approve/reject a submitted requisition (fallback decision source, D-6) |
| `PURCHASE.RFQ.VIEW` | View/list RFQs + quotes + the comparison |
| `PURCHASE.RFQ.MANAGE` | Create/send RFQs, capture quotes, award |
| `PURCHASE.ORDER.APPROVE` | Approve/reject an over-threshold PO (fallback decision source, D-6) |
| `PURCHASE.LANDEDCOST.VIEW` | View landed-cost documents + allocations |
| `PURCHASE.LANDEDCOST.MANAGE` | Create/confirm landed-cost allocation |
| `PURCHASE.RETURN.VIEW` | View/list purchase returns |
| `PURCHASE.RETURN.CREATE` | Create/confirm a purchase return |
| `PURCHASE.SETTINGS.MANAGE` | Set the PO approval threshold + enable flag (`purchase_settings`) |

Existing `PURCHASE.ORDER.*` / `PURCHASE.RECEIVE` cover PO place/receive; conversion-to-PO rides `PURCHASE.ORDER.CREATE`.

**`@perm` gating:** `@PreAuthorize("@perm.has('…')")` for list/create; `@PreAuthorize("@perm.scoped(#uid,'<type>','…')")` for uid-addressed ops (NEVER `hasAuthority`). `assertCanActIn(principal, companyId)` on every read path + every confirm (the #1 anti-regression guard).

**`ScopeGuard.companyIdOf` new cases** (+ repository `findCompanyIdByUid` + constructor wiring, the shipped pattern): `requisition` → `PurchaseRequisitionRepository`, `rfq` → `RfqRepository`, `supplierquote` → `SupplierQuoteRepository`, `landedcost` → `LandedCostRepository`, `purchasereturn` → `PurchaseReturnRepository`. (RFQ-supplier junction, quote/return lines, landed-cost charges/allocations are addressed under their header uid — no separate case.)

**Numbering (`PurchaseNumberGenerator` extended):** new `code_sequence` `entity_kind` values, lazy-created on first use (no seed rows, #12-safe): `PURCHASE_REQUISITION` (`PR-%04d`, at submit), `RFQ` (`RFQ-%04d`, at create), `SUPPLIER_QUOTE` (`SQ-%04d`, at capture), `LANDED_COST` (`LC-%04d`, at create), `PURCHASE_RETURN` (`PRET-%04d`, at create). The `uq_<doc>_company_number` constraints backstop.

**Angular nav routes** (under the existing Procurement section): `/purchases/requisitions`, `/purchases/requisitions/:uid`, `/purchases/rfqs`, `/purchases/rfqs/:uid`, `/purchases/rfqs/:uid/compare`, `/purchases/landed-costs`, `/purchases/landed-costs/:uid`, `/purchases/returns`, `/purchases/returns/:uid`, `/purchases/settings` (the approval-threshold admin). PO-approval actions live on the existing `/purchases/orders/:uid` screen (an Approve/Reject panel + a "requires approval" banner).

### D-11 — Indexes

Working-set indexes (the NFR-PROC-05 reads): `ix_purchase_requisitions_company_status (company_id, status)`; partial `ix_purchase_requisitions_pending ON purchase_requisitions (company_id) WHERE status = 'SUBMITTED'`; `ix_rfqs_company_status`; `ix_supplier_quotes_rfq (rfq_id)`; `ix_supplier_quote_lines_supplier_product (company_id, supplier_id, product_id)` (the last-quoted-price reader, D-4); `ix_landed_costs_company_status`; `ix_landed_cost_receipts_receipt (goods_receipt_id)`; `ix_landed_cost_allocations_product (company_id, product_id)`; `ix_purchase_returns_company_status`; `ix_purchase_returns_receipt (goods_receipt_id)`; `ix_purchase_return_lines_gr_line (goods_receipt_line_id)`. Standard `ix_<table>_company` on every header.

### D-12 — Migration ordering (additive; V1–V19 FROZEN; V20–V31 reserved; #12-safe seeds)

**Five additive migrations (one per concern — legible diffs, independent stage shipping; D-1 boundary):**

- **`V32__purchase_requisitions.sql`** — CREATE `purchase_requisitions` + `purchase_requisition_lines` (+ constraints/indexes, D-3); CREATE `purchase_settings` (the approval-threshold config, D-6) + per-company #12-safe seed (`'PS' || lpad(c.id::text,6,'0') || substr(md5('purchase_settings'),1,12)`, gate disabled by default) `ON CONFLICT (company_id) DO NOTHING`; ALTER `purchase_orders` ADD `approval_status`/`approval_request_uid`/`source_quote_uid`/`source_requisition_uid` + `chk_purchase_order_approval_status` (existing rows back-fill `NOT_REQUIRED`); permission seed (`PURCHASE.REQUISITION.*`, `PURCHASE.ORDER.APPROVE`, `PURCHASE.SETTINGS.MANAGE`) + ORG_ADMIN grant.
- **`V33__purchase_rfq.sql`** — CREATE `rfqs` + `rfq_lines` + `rfq_suppliers` + `supplier_quotes` + `supplier_quote_lines` (+ constraints/indexes, D-4); permission seed (`PURCHASE.RFQ.*`) + grant.
- **`V34__purchase_landed_cost.sql`** — CREATE `landed_costs` + `landed_cost_receipts` + `landed_cost_charges` + `landed_cost_allocations` (D-5); CoA back-seed `2160 Landed Cost Clearing` (LIABILITY) per company (`'PC' || lpad(c.id::text,6,'0') || '2160'`, `ON CONFLICT (company_id, account_code) DO NOTHING`); `chk_gl_config_key` widen (`DROP/ADD CONSTRAINT` add `'LANDED_COST_CLEARING'`); `gl_configs` key back-seed `LANDED_COST_CLEARING → 2160` per company (#12-safe `'PG' || lpad(coa.company_id::text,6,'0') || substr(md5('LANDED_COST_CLEARING'),1,12)`); ALTER `supplier_bill_lines` ADD `landed_cost_uid VARCHAR(26)` (the clearing signal, D-7); journal source-type CHECK widen (`chk_journal_batch_source_type` + `chk_journal_entry_source_type` add `'LANDED_COST'`); permission seed (`PURCHASE.LANDEDCOST.*`) + grant.
- **`V35__purchase_returns.sql`** — CREATE `purchase_returns` + `purchase_return_lines` (D-7); ALTER `goods_receipt_lines` ADD `returned_qty_in_base NUMERIC(19,6) NOT NULL DEFAULT 0` + `chk_goods_receipt_line_returned`; `ap_debit_note` origin CHECK widen (`DROP/ADD CONSTRAINT chk_ap_debit_note_origin` add `'PURCHASE_RETURN'`); journal source-type CHECK widen add `'PURCHASE_RETURN'`; permission seed (`PURCHASE.RETURN.*`) + grant.
- **`V36__procurement_indexes.sql`** — any cross-table indexes not co-located with their CREATE (D-11 catch-all; may be empty if all indexes ride their CREATE migration — kept reserved for the coordinator).

`MigrationKeepDataIT` extends to V36 (the #12 seed-uid trap fires only on keep-data deploys where companies exist; verify the `purchase_settings` + CoA + `gl_configs` back-seeds). `JournalSourceType` (Java) gains `LANDED_COST` + `PURCHASE_RETURN` (admitted by the V34/V35 widens). `GlConfigKey` gains `LANDED_COST_CLEARING`. `ApDebitNoteOrigin` gains `PURCHASE_RETURN`. **No new stock movement type** (landed cost = value-only revaluation, no movement row; return = existing `GOODS_RECEIPT_REVERSAL`) — `chk_stock_movement_type` untouched.

### D-13 — ArchUnit edges (no cycle)

- **`purchases.service` → `stock` (via outbox events only)** — `LANDED_COST.ALLOCATED` / `PURCHASE.RETURNED` payloads live in `purchases.domain.dto`; the consumers `LandedCostStockHandler` / `PurchaseReturnStockHandler` live in `stock.events` and import `purchases.domain.dto` (the **same direction** `stock` already imports `purchases.domain.dto.StockReceivedPayload`, verified — ADR-0020 D-3). **Allowed.**
- **`purchases.service` → `ap.service`** — `ApDebitNoteService.raise` for the return debit note (AP returns a DTO; purchases imports no AP entity). **Allowed** — the `ap→gl` cross-service-call stance.
- **`stock.events`/`stock.service` → `gl.service`** — the landed-cost + return GL legs reuse the existing `stock→gl` edge (ADR-0020 D-12). **Allowed, unchanged.**
- **`purchases` → `approvals`** (when X.5 lands) — a scalar-seam read (`ApprovalService.decisionOf`) + the `APPROVAL.DECIDED` event consumed by `purchases.events.PoApprovalDecisionHandler`. The fallback ships **no** approvals edge (in-module flip).
- **`purchases` → `parties`/`products`** (DTO reads — already shipped). **No edge `stock → purchases.entity`, no edge `ap → purchases`.** Direction: `purchases → stock`, `purchases → ap`, both `→ gl`. **No cycle** (stock/ap/gl do not depend on purchases; stock importing `purchases.domain.dto` is a DTO dependency, the shipped allowance). `ModuleBoundaryTest` (controller↛repository, service↛controller, audit-append-only) — **no active rule violated.**

## Consequences

**Positive**
- The full procure-to-pay depth ships on the existing spine: the PO/GR/bill loop is unchanged; the valuation engine gains one primitive (`applyLandedCost`) and is now driven by **landed cost** (and reversed by the **return**); the AP debit-note service raises the return's credit. No engine is rebuilt — only new triggers + one new clearing account.
- Landed cost is **capitalised, not expensed** — the moving average, the inventory asset (1300), and COGS reflect the true landed cost; the LANDED_COST_CLEARING bridge nets to zero on the freight bill, the GRNI pattern reused exactly.
- The **PO approval gate is buildable now** without the approvals engine: the threshold + columns + gate logic ship; the decision source is a permission-gated flip that swaps to the engine with **no schema change**. This unblocks the slice without waiting on X.5.
- The return reverses at the **original receipt cost** (reusing `reverseReceipt`) — symmetric, no phantom gain/loss, recon-safe.
- Additive and surgical: 13 new tables + 1 `purchase_settings` config table, 1 `purchase_orders` 4-column ALTER, 1 `goods_receipt_lines` column, 1 `supplier_bill_lines` column, 1 new CoA account + key, 1 new valuation primitive, 2 new events + 2 stock handlers, 1 widened `ap_debit_note` origin, 2 new source-type tokens, 5 new numbering kinds, 5 new ScopeGuard cases, 11 new permissions. **V1–V19 frozen.**

**Negative / costs**
- The slice touches three modules + a dependency: purchases (the documents + the gate), stock (the `applyLandedCost` primitive + 2 handlers + the GL legs), ap (the `PURCHASE_RETURN` origin + the `landed_cost_uid` clearing signal). Cross-module coordination — flagged in the touch list.
- The PO approval gate ships a **fallback** decision source; when X.5 lands, an engine-integration ADR must flip it (the columns are ready, but the wiring is a follow-up — not free).
- Landed cost on already-issued goods (BR-PROC-07) is an accepted imprecision: posted COGS is not retroactively corrected; the residual capitalisation may leave a per-unit average that does not reflect the goods that left. Surfaced as a warning; the recon still holds (value is added to 1300, the average is recomputed on what remains).
- The return's GL legs interact with the GRNI/AP state of the underlying bill (billed vs not-billed); the engineer must reconcile the stock handler's DR-GRNI leg against the shipped `ApDebitNoteServiceImpl` legs so the books balance in both states (Open items).
- `returned_qty_in_base` on `goods_receipt_lines` is a maintained denormalisation that MUST stay tied to the returns; the CHECK is the backstop, but a rollup bug is a correctness defect — tests assert the tie after every return.

**Neutral / deferred**
- Single location, base currency, moving-average only (inherited from ADR-0020). Blanket POs, PO change orders, multi-step approval routing, full contract pricing, service-PO type, supplier scorecards/disputes/compliance, import/customs docs, PO/RFQ PDFs + notifications, multi-currency + FX on landed cost, commitment/encumbrance — all deferred (§2), none precluded. The `supplier_quote_lines` rows are the foundation a future contract-price master builds on; the `LandedCostBasis` enum admits BY_WEIGHT/manual additively.

## Alternatives considered

- **Landed-cost capitalisation — a new value-only primitive vs reusing `revalueAdjustment` vs a synthetic zero-cost receipt.** *Decided: a new `applyLandedCost` primitive.* `revalueAdjustment` posts to STOCK_ADJUSTMENT (an expense) and leaves the avg unchanged — wrong for capitalisation (we need value+, avg recomputed up, no expense). A synthetic receipt would inject a phantom quantity. The new primitive is the only one that adds value at zero quantity and recomputes the average — the precise semantics landed cost needs.
- **Landed-cost charge home — a new LANDED_COST_CLEARING bridge vs CR AP directly vs CR a freight expense.** *Decided: LANDED_COST_CLEARING (the GRNI pattern).* CR AP at capitalisation double-counts the payable before the freight bill exists; CR an expense contradicts capitalisation. The clearing liability holds the accrual until the freight bill matches, netting to zero — the shipped GRNI shape reused.
- **Landed-cost effect — event-driven (stock handler) vs synchronous in purchases.** *Decided: event-driven, stock-side.* The capitalisation mutates `stock_on_hand` (the valuation authority) and must reconcile to 1300 — exactly the ADR-0020 stance that stock owns valuation + posts GL. A synchronous purchases post would re-own the lock + the recompute + the GL config in the wrong module. Symmetry with the delivery seam; idempotent, crash-safe.
- **PO approval — in-module gate now (engine-swap later) vs wait for X.5 vs a full in-module workflow.** *Decided: in-module gate + thin engine seam.* Waiting on X.5 blocks the slice. A full in-module approval workflow duplicates what X.5 will own (and would have to be torn out). The thin seam (threshold config + scalar columns + a permission-gated flip) ships the *gate* now and swaps the *decision source* later with no schema change — the lean, reversible choice.
- **RFQ award — whole-quote vs per-line split.** *Decided: whole-quote in v1* (one RFQ → one PO to one supplier). Per-line split (best price per line → several POs) is richer and deferred; the model (one `supplier_quote` per supplier, an `awarded_quote_uid` on the RFQ) does not preclude it — a future split-award reads multiple winning quote lines.
- **Return cost basis — original receipt cost vs current average.** *Decided: original cost* (reuse `reverseReceipt`, the ADR-0020 D-5 precedent). Current average would, after intervening receipts/landed-cost moved the average, restore a different value than the receipt brought in — a phantom gain/loss and a recon break. The per-movement cost columns make original-cost reversal exact.
- **Supplier price — reuse captured quotes vs a new supplier price-list master.** *Decided: reuse the captured `supplier_quote_lines`* (last-quoted-price reader). A full contract-price master (tiered, effective-dated) is deferred; the quote rows are the lightweight reference v1 needs and the foundation the master builds on.
- **Migration split — five files (one per concern) vs one big V32.** *Decided: five (V32–V36).* Each concern (requisition+settings, RFQ, landed cost, returns, indexes) is independently shippable and a legible diff; the stages can land in sequence. One big migration would couple unrelated DDL. The table/column/constraint names are fixed either way.

## Open items (recommended defaults adopted; load-bearing flagged ★)

- **★ OQ-PROC-01 — approvals-engine contract & sequencing.** Adopted **the in-module permission-gated fallback + the thin scalar seam now; engine-swap via a follow-up ADR, no schema change.** *Confirm with PM whether X.5 lands first (then skip the fallback wiring).*
- **★ OQ-PROC-02 — RFQ award granularity.** Adopted **whole-quote award** (one RFQ → one PO). Per-line split deferred, not precluded.
- **★ OQ-PROC-03 — landed-cost allocation basis.** Adopted **BY_VALUE default + BY_QUANTITY selectable per document**, largest-remainder rounding so `Σ allocated == total_charge`. BY_WEIGHT/manual deferred.
- **★ OQ-PROC-04 — landed cost on already-issued goods.** Adopted **capitalise to on-hand value, warn on the residual, do not retroactively touch posted COGS** (BR-PROC-07 accepted imprecision). Owner (finance) may later choose retroactive COGS adjustment (a richer reval) — additive.
- **OQ-PROC-05 — numbering timing.** Adopted PR@submit, RFQ/SQ/LC/PRET@create. Cheap to flip.
- **OQ-PROC-06 — cost-centre dimension.** Adopted **free-text `cost_centre_code`** on PR/PO; wire to the dimension framework (area 14) later. Not blocking.
- **OQ-PROC-07 — service/expense PO type.** Adopted **no `po_type` flag; keep the bill-level `grLineUid` goods/service split** (ADR-0020 D-9). Not blocking.
- **OQ-PROC-08 — landed-cost billed-vs-accrued GL.** Adopted **always CR LANDED_COST_CLEARING from the capitalisation; the freight bill always DRs LANDED_COST_CLEARING** (one clean clearing path, no two-branch posting). The `is_billed`/`supplier_bill_uid` on the charge is metadata for matching, not a posting fork.
- **OQ-PROC-09 — return cost basis (partial/multi-component).** Adopted **read the original `GOODS_RECEIPT` movement rows as authoritative**, apportion pro-rata for partials; `goods_receipt_lines.unit_cost_amount` is the convenience.
- **OQ-RETURN-GL (NEW, the one to verify in build):** the return's stock-side DR-GRNI leg vs the AP debit-note legs must balance in both the bill-matched and not-yet-billed states. The engineer reconciles `PurchaseReturnStockHandler`'s leg against the shipped `ApDebitNoteServiceImpl` posting before merge (an integration test asserting the GL nets and 1300/2100/2150 reconcile after a return in each state). *Not a schema blocker; a build-time correctness gate.*
- **OQ-CUR-03 (carried) — display precision.** HALF_UP, TZS 0-dp display, `NUMERIC(19,4)` internal. Confirm before go-live; does not block the model.

---

## Summary

ADR-0027 designs **Procurement / Purchase-to-Pay depth** in `com.erp.modules.purchases`: thirteen new tables + a `purchase_settings` config table across **requisition** (`purchase_requisitions`+lines), **RFQ/quote** (`rfqs`+`rfq_lines`+`rfq_suppliers`+`supplier_quotes`+`supplier_quote_lines`), **landed cost** (`landed_costs`+`landed_cost_receipts`+`landed_cost_charges`+`landed_cost_allocations`), and **purchase return** (`purchase_returns`+`purchase_return_lines`), plus additive columns on `purchase_orders` (the approval-gate seam), `goods_receipt_lines` (`returned_qty_in_base`), and `supplier_bill_lines` (`landed_cost_uid`).

**The three load-bearing seams:** (1) **landed cost** capitalises INTO inventory via a NEW `InventoryValuationService.applyLandedCost` primitive (raise `on_hand_value`, recompute `avg_cost`, qty unchanged, no movement row) driven by a NEW `LANDED_COST.ALLOCATED` event + `LandedCostStockHandler`, crediting a NEW `LANDED_COST_CLEARING` (2160) GRNI-style bridge cleared by the freight bill; (2) the **PO approval gate** blocks over-threshold DRAFT→ORDERED via a `purchase_settings` threshold + thin scalar `approval_status`/`approval_request_uid` columns, with an **in-module permission-gated fallback now** (gated `PURCHASE.ORDER.APPROVE`) that swaps to the not-yet-built approvals engine with **no schema change**; (3) the **purchase return** reverses the receipt at original cost (reusing `reverseReceipt`) via a NEW `PURCHASE.RETURNED` event + `PurchaseReturnStockHandler` (DR GRNI / CR Inventory) and raises an AP debit note with a NEW `PURCHASE_RETURN` origin.

**Readiness:** every table, column, constraint name, enum, transition, event/payload/handler, GL leg, key, source type, scope case, perm, numbering kind, and nav route is specified — concrete enough to build **V32–V36** without guessing a rule. **Additive on frozen V1–V19** (V20–V31 reserved for other modules — coordinator owns collisions). **#12-safe** (`purchase_settings` + CoA + `gl_configs` back-seeds use md5-bounded uids; numbering kinds are lazy). **Cross-module touch list:** (1) **purchases → stock** — `LANDED_COST.ALLOCATED`/`PURCHASE.RETURNED` events + the `applyLandedCost` primitive + 2 handlers; (2) **purchases → ap** — the `PURCHASE_RETURN` debit-note origin + the `supplier_bill_lines.landed_cost_uid` clearing signal in `postMatchedBillToGl`; (3) **the PO approval gate** in `PurchaseOrderServiceImpl.place`. **Depends on** the approvals engine (designed-to-contract with a shipping fallback); **gates** nothing.
