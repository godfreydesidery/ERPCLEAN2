# 0028 — Inventory / Warehouse Depth data model: a `stock_locations` master under the branch (org → company → branch → location), on-hand re-grained to per-`(company, branch, location, product)` with the company-product moving average UNCHANGED (value attributed to locations pro-rata so ADR-0020's `Σ == 1300` recon holds untouched), a value-preserving `stock_transfers` document (in-transit `TRANSFER_OUT`/`TRANSFER_IN`, no P&L, no GL for a same-cost-grain move), a `stock_counts` document that snapshots system on-hand and posts the net variance to `STOCK_ADJUSTMENT` via the shipped ADR-0020 adjustment-revaluation in one act, and `stock_batches` (lot/expiry, FEFO) + `stock_serials` (per-unit identity) tracking layered on the movement ledger — all on the existing `StockPostingService` / `InventoryValuationService` / outbox / `IdempotencyGuard` / `code_sequence` / `MasterStatus` spine, additive as `V37-V41` on the frozen V1–V19

- **Status:** Proposed
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (consuming the Inventory / Warehouse Depth requirements, docs/requirements/inventory-depth.md — DRAFT pending owner ratification; the nine OQ-INVD forks have recommended defaults adopted as the ADR's decisions below. The **cost grain with locations** (OQ-INVD-01), the **transfer mode + GL stance** (OQ-INVD-02/05/08), the **count movement-type reuse** (OQ-INVD-07), the **lot consumption order** (OQ-INVD-04), and the **on-hand re-grain migration** are the decisions THIS ADR makes; the *behaviour* is fixed by the requirements.)
- **Context source:** [docs/requirements/inventory-depth.md](../requirements/inventory-depth.md) (FR-INVD-01..28, BR-INVD-01..14, NFR-INVD-01..09, US-INVD, §6 flows, §10 accepted boundary, §11 OQ log). Verified against the **shipped** code:
  - **Stock** ([ADR-0010](0010-stock-data-model.md) / [V7__stock.sql](../../backend/src/main/resources/db/migration/V7__stock.sql)): `StockOnHand` (`stock_on_hand` — `id`, `uid` VARCHAR(26), `company_id`, `branch_id`, `product_id`, `quantity` NUMERIC(19,6) signed/no-`>=0`-CHECK, `reorder_level`, `avg_cost` NUMERIC(19,4) nullable, `on_hand_value` NUMERIC(19,4) NOT NULL DEFAULT 0, `reserved_qty` NUMERIC(19,6) NOT NULL DEFAULT 0, `@Version version`, audit; **`uq_stock_on_hand_scope (company_id, branch_id, product_id)`** — the scope this ADR re-grains; `applyDelta`/`applyCostRecompute`/`applyReservationDelta`/`availableQty()` domain methods); `StockMovement` (`stock_movements` — append-only, `movement_type` VARCHAR(25) CHECK ∈ {GOODS_RECEIPT, SALE_ISSUE, SALE_REVERSAL, GOODS_RECEIPT_REVERSAL, ADJUSTMENT, OPENING_BALANCE} — **`TRANSFER_OUT`/`TRANSFER_IN` reserved in the Java enum but EXCLUDED from the CHECK** (ADR-0010 OQ-STOCK-08, the seam this ADR builds); `quantity` signed CHECK `<>0`, `source_event_uid`/`source_document_type`/`source_document_uid` scalar refs, `unit_cost_amount`/`value_amount` cost cols, `reason_code`, `note`, `uq_stock_movement_source_event (source_event_uid, product_id)` idempotency backstop, `chk_stock_movement_reason` (ADJUSTMENT must carry a reason)); `StockPostingService.post(companyId, branchId, productId, quantity, movementType, sourceEventUid, sourceDocumentType, sourceDocumentUid, reasonCode, note, occurredAt, actorId, unitCostAmount, valueAmount)` (MANDATORY, optimistic-lock one-retry upsert on the scope row); `MovementType` enum (the 6 built + the 2 reserved TRANSFER_*); `AdjustmentReason` ∈ {SHRINKAGE, DAMAGE, EXPIRY, COUNT_CORRECTION, RECEIPT_CORRECTION, OTHER}; the four event handlers + `StockServiceImpl.adjust/openingBalance`; `StockReservationService` (ADR-0021 D-5, the `reserved_qty` primitive).
  - **Inventory Valuation** ([ADR-0020](0020-inventory-valuation-data-model.md) / [V17__inventory_valuation.sql](../../backend/src/main/resources/db/migration/V17__inventory_valuation.sql)): `InventoryValuationService.recomputeOnReceipt / costIssue / reverseIssue / reverseReceipt / setOpeningValue / revalueAdjustment`; `InventoryGlPoster.postReceiptInNewTx / postCogsInNewTx / postReceiptReversalInNewTx / postSaleReversalInNewTx / postOpeningValuationDirect / postAdjustmentDirect(AdjustmentPostCmd)` (REQUIRES_NEW for event-driven, direct for human-act; resolves config INSIDE the REQUIRES_NEW boundary — "FIX B"); the **company-product moving average** on `stock_on_hand.avg_cost` + `on_hand_value`; `StockValuationQuery` + `StockValuationController` (gated `INVENTORY.VALUATION.VIEW`) summing `on_hand_value` per company-product and reconciling to `accountBalance(companyId, INVENTORY)`; the two shipped accounts/keys **`GRNI` (2150)** + **`STOCK_ADJUSTMENT` (5160)**; `GlConfigKey.{INVENTORY, COGS, GRNI, STOCK_ADJUSTMENT, OPENING_BALANCE_EQUITY}`; `JournalSourceType.{STOCK_RECEIPT, COGS, STOCK_ADJUSTMENT, OPENING_INVENTORY}` (all admitted since V17); `InventoryGlSeeder` (the per-company seeder pattern).
  - **Sales Orders / reservation** ([ADR-0021](0021-sales-orders-data-model.md) / V18-V19): `stock_on_hand.reserved_qty`; `DELIVERY.CONFIRMED` → `DeliveryIssueStockHandler` (the SO-sourced issue path, `source_document_type='DELIVERY'`); `DELIVERY.RETURNED` → `DeliveryReturnStockHandler`; `DomainEventType.{DELIVERY_CONFIRMED, DELIVERY_RETURNED, AGG_DELIVERY, AGG_SALES_RETURN}`. **These paths gain a location parameter (D-3) but their accounting is unchanged.**
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / V10): `GLPostingService.post(JournalEntryDraft)→JournalEntryDto` + `GLPostingSafeInvoker` (REQUIRES_NEW null-on-anomaly); `GLConfigResolver.resolve(companyId, key)` (throws on missing/inactive); `JournalLineRepository.accountBalance(companyId, accountId)` (the recon expected side); `ChartOfAccount` / `AccountType`.
  - **Products** ([ADR-0007](0007-products-data-model.md) / V3): `Product` (`products` — `stockable` boolean + `chk_product_service_stockable`; the master this ADR adds `lot_tracked`/`serial_tracked` flags to, D-7); `ScopeGuard.companyIdOf` target-type switch + `code_sequence` numbering (ADR-0007 D-6).
  - **IAM** ([ADR-0001](0001-iam-and-rbac.md) / V1): `branches` (org → company → branch — the parent of the new location master); `MasterStatus` soft-delete enum; the `permissions` + `role_permission` seed/grant pattern (V7/V12/V14/V17).
  - **Money** ([ADR-0005](0005-money-and-currency.md)): BigDecimal, base currency only, HALF_UP, NUMERIC(19,4) value / NUMERIC(19,6) qty.
  - **Outbox / idempotency** ([ADR-0009](0009-transactional-outbox.md)): `OutboxPublisher.publish(...)`; `DomainEventHandler` + `IdempotencyGuard.alreadyProcessed(consumer, uid)`/`markProcessed`; `processed_events(consumer, event_uid)`.
  - [[db-naming-convention]] verified against V1–V19 (plural masters/children `stock_locations`/`stock_transfers`/`stock_transfer_lines`/`stock_counts`/`stock_count_lines`/`stock_batches`/`stock_serials`; singular constraint roots `uq_`/`fk_`/`chk_` on the singular entity; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id`/`branch_id` BIGINT scalar; additive `DROP/ADD CONSTRAINT` widen). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **Latest shipped migration is `V19__sales_returns.sql` → Inventory Depth uses `V37-V41`** (a reserved additive band; V1–V19 FROZEN — the gap V20–V36 is left for in-flight modules between V19 and this slice, per the coordinator's range assignment). **Next free ADR is 0028.**

This ADR is the **technical data model + integration design** for Inventory / Warehouse Depth (PATH-TO-FULL-ERP §3.5 remaining items). It translates the spec into: the `stock_locations` master + branch-default rule, the **on-hand re-grain** to `(company, branch, location, product)` with the migration that backfills a default location and preserves the recon, the `stock_transfers` + lines model with the in-transit `TRANSFER_OUT`/`TRANSFER_IN` movements (no P&L, no GL for the same-cost-grain default), the `stock_counts` + lines model with snapshot → variance → one-act `STOCK_ADJUSTMENT` posting via the shipped ADR-0020 revaluation, `stock_batches` (lot/expiry/FEFO) + `stock_serials` (per-unit identity) layered on the movement ledger, the new `DomainEventType` values + handlers, the perms, the `ScopeGuard` cases, the Angular nav routes, the V37-V41 migration ordering with `#12`-safe seeds, the ArchUnit edges, and the staged build split. It is **concrete enough that the engineer builds without guessing a business rule.** It writes **no production code, no entities, no migration SQL.**

## Context

The stock module is quantity + company-product moving-average value, perpetual and reconciled (ADR-0020), with reservation + the delivery seam (ADR-0021). But the warehouse model is **flat**: one bucket per branch, no place below the branch, no value-preserving move between places, no count document, no lot/serial identity. The `MovementType` enum already **reserves** `TRANSFER_OUT`/`TRANSFER_IN` (ADR-0010 OQ-STOCK-08) for exactly this. The forces:

- **The on-hand re-grain is the load-bearing migration (BR-INVD-02, NFR-INVD-09).** The shipped `uq_stock_on_hand_scope (company_id, branch_id, product_id)` must become `(company_id, branch_id, location_id, product_id)`. This touches the hottest table and every path that upserts it. The migration MUST be **behaviour-neutral**: backfill one default location per branch, attach the unique constraint to include `location_id`, and leave every quantity/value/recon unchanged. This is the chief delivery risk.

- **The cost grain decision sets everything downstream (OQ-INVD-01).** If the moving average becomes **per-location**, the average engine, the recon, and every transfer's GL change. If it stays **per-company-product** (one average, value attributed to locations pro-rata), the ADR-0020 engine is untouched, transfers net to zero on `1300`, and the recon holds by construction. The spec's recommended default is per-company-product. Resolved in **D-2**.

- **A transfer must be a non-P&L, non-adjustment movement (BR-INVD-05).** Faking it as adjustment-out + adjustment-in would post phantom `STOCK_ADJUSTMENT` expense. The reserved `TRANSFER_OUT`/`TRANSFER_IN` types are the correct category; with a single company-product average a transfer re-attributes value across locations in the **sub-ledger only** and posts **no GL** (it is not a GL event). Resolved in **D-5**.

- **A count must reuse the shipped adjustment-revaluation, not invent accounting (BR-INVD-08).** A count's variance is exactly a stock adjustment at the current average — `DR/CR STOCK_ADJUSTMENT vs 1300` (ADR-0020 D-7). The count document batches the per-line variances and posts the net through the shipped `revalueAdjustment` / `postAdjustmentDirect`. No new account, no new key. Resolved in **D-6**.

- **Lot/serial is identity, not a new cost basis (BR-INVD-10/11, §10).** A lot's value is its quantity × the company-product average; a serial is one unit at that average. Tracking lives in two new child tables keyed off `(location, product)`, layered on the movement ledger, with integrity invariants (Σ lot on-hand = location on-hand). No per-lot/per-serial cost layer in v1. Resolved in **D-7**.

- **Schema freeze / direction.** V1–V19 frozen. Inventory Depth is additive in the reserved **V37-V41** band: it ALTERs `stock_on_hand` (add `location_id`, re-grain the unique constraint), ALTERs `stock_movements` (add `location_id`, widen `chk_stock_movement_type` to admit `TRANSFER_OUT`/`TRANSFER_IN`), ALTERs `products` (add tracking flags), CREATEs seven new tables, widens the journal source-type CHECK (one new token), seeds permissions, and backfills a default location per branch (`#12`-safe). It imports no Sales/Purchases entity; it posts to GL through the shipped `InventoryGlPoster` only.

## Decision

### D-1 — Module placement: everything lives in `com.erp.modules.stock`; no new module; the existing `stock → gl.service` + `stock → gl.repository` edges are reused

The location master, transfers, counts, lots, and serials all live in **`com.erp.modules.stock`** — it owns on-hand, the movement ledger, `StockPostingService`, the four event handlers, the valuation engine, and the manual paths these documents extend. A separate `warehouse` module would re-read the on-hand row, duplicate the concurrency lock, and re-derive valuation. Reject.

No new outbound module edge is introduced: stock already imports `gl.service` (`GLPostingService`, `GLConfigResolver`) and `gl.repository` (`JournalLineRepository.accountBalance`) for valuation (ADR-0020 D-12). The transfer (no GL by default) and the count (reuses `InventoryGlPoster.postAdjustmentDirect`) need no new edge. The product tracking flags are read via the shipped `products` DTO edge.

Internal layout (additive to the shipped `stock` package):

```
com.erp.modules.stock
├── domain.entity   StockLocation                              (NEW master, MasterStatus)
│                   StockOnHand        (+ location_id col, re-grained scope, D-3)
│                   StockMovement      (+ location_id col, TRANSFER_* admitted, D-3)
│                   StockTransfer, StockTransferLine            (NEW, D-5)
│                   StockCount, StockCountLine                  (NEW, D-6)
│                   StockBatch, StockSerial                     (NEW, D-7)
├── domain.dto      StockLocationDto / CreateStockLocationRequest / SetDefaultLocationRequest,
│                   StockTransferDto / CreateStockTransferRequest / DispatchTransferRequest / ReceiveTransferRequest / StockTransferLineDto,
│                   StockCountDto / CreateStockCountRequest / EnterCountRequest / StockCountLineDto,
│                   StockBatchDto, StockSerialDto, ExpiryReportRowDto, LocationOnHandRowDto,
│                   TransferDispatchedPayload, TransferReceivedPayload  (NEW outbox payloads, D-5),
│                   StockCountPostedPayload                             (NEW outbox payload, D-6)
├── domain.enums    LocationType, StockTransferStatus, StockCountStatus, SerialStatus, LotConsumptionPolicy (D-2..D-7)
├── repository      StockLocationRepository, StockTransferRepository, StockTransferLineRepository,
│                   StockCountRepository, StockCountLineRepository, StockBatchRepository, StockSerialRepository
│                   (+ StockOnHandRepository re-grained finders, D-3)
└── service         StockLocationService(+Impl)               — NEW: location master + default-location rule
                    StockTransferService(+Impl)               — NEW: create/dispatch/receive; the TRANSFER_* posts
                    StockCountService(+Impl)                  — NEW: create/snapshot/enter/post (REUSE revalueAdjustment)
                    StockBatchService(+Impl)                  — NEW: lot record on receipt, FEFO consume
                    StockSerialService(+Impl)                 — NEW: serial record/issue/return/lookup
                    LocationResolver                          — NEW: resolve the branch default location (D-3 default)
                    WarehouseNumberGenerator                  — NEW: TRF/CNT via code_sequence (D-9)
                    LocationOnHandQuery, ExpiryReportQuery     — NEW: the per-location + expiry reads (D-8)
└── events          TransferDispatchStockHandler              — NEW: consumes TRANSFER.DISPATCHED → −qty at source (TRANSFER_OUT)
                    TransferReceiveStockHandler               — NEW: consumes TRANSFER.RECEIVED → +qty at dest (TRANSFER_IN)
                    (GoodsReceiptStockHandler / DeliveryIssueStockHandler / SaleIssueStockHandler / *Reversal — gain a location param, D-3)
```

`StockTransferService` and `StockCountService` are thin orchestrators over `StockPostingService` + `InventoryValuationService` + `InventoryGlPoster` (the count) — they introduce **no new posting primitive** and **no new accounting**. `LocationResolver.defaultLocationId(companyId, branchId)` is the single place that supplies the branch default location when a path does not specify one (D-3 migration-safety).

### D-2 — Cost grain: the moving average stays PER-COMPANY-PRODUCT; per-location value is ATTRIBUTED pro-rata (OQ-INVD-01) — the ADR-0020 engine is untouched

**Decision: keep the single moving-average cost per `(company, product)` exactly as ADR-0020 ships it; do NOT introduce a per-location average in v1.** The on-hand **quantity** is re-grained per location (D-3); the **`avg_cost`** remains a company-product figure; the **per-location `on_hand_value`** is the location's `quantity × avg_cost` attributed at the company-product average.

Two storage options were weighed:

1. **(adopted) `avg_cost` is conceptually per-company-product but physically carried on each location row, kept in sync.** Each `(company, branch, location, product)` `stock_on_hand` row carries `avg_cost` + `on_hand_value` (the existing columns). On a receipt, the recompute uses the **company-product totals** (Σ quantity and Σ on_hand_value across all the product's location rows) to derive the new company-product average, then writes that same `avg_cost` to every location row of the product and re-attributes each location's `on_hand_value = location_qty × avg_cost`. The valuation report sums `on_hand_value` across all rows = `Σ qty × avg` = the `1300` balance, recon UNCHANGED.
2. **(rejected for v1) per-location average** — each location carries its own independent average; a transfer carries the source's value to the destination and shifts the destination's average. This is richer (a location's cost reflects what it actually received) but changes the average engine, the recon decomposition, and forces every transfer to post a value-carrying GL leg. Deferred (§ Alternatives); the per-location rows + the company-product roll-up make it an additive later refinement, not precluded.

**Why option 1 preserves ADR-0020 exactly:** the recon is `Σ(on_hand_value) == accountBalance(1300)`. With the average kept per-company-product and `on_hand_value = qty × avg` per row, the sum over location rows of a product equals `(Σ qty) × avg` = the same company-product value ADR-0020 computes today. Receipts and issues touch `1300` identically; transfers move value between location rows of the **same** product at the **same** average → the sum is invariant → `1300` does not move → **no GL entry needed for a transfer** (D-5). The recompute now reads/writes across a product's location rows under their `@Version` locks (D-3 concurrency).

`InventoryValuationService` gains location-aware internal helpers but its **public contract is unchanged** for the existing callers (receipt/issue/reverse/opening/adjust) — they now pass through `LocationResolver` for the location, and the recompute aggregates across the product's location rows. The new `LotConsumptionPolicy` enum (`FEFO`, reserved `FIFO`, `MANUAL`) defaults FEFO (D-7).

### D-3 — On-hand re-grain: ALTER `stock_on_hand` + `stock_movements` to add `location_id`; re-grain `uq_stock_on_hand_scope`; the behaviour-neutral backfill (NFR-INVD-09)

**The `stock_locations` master (NEW, D-4) is created first; then:**

**ALTER `stock_on_hand` (additive):**

| column | type | null | default | notes |
|---|---|---|---|---|
| `location_id` | BIGINT | NO* | (backfilled) | FK → `stock_locations(id)`; the physical location within the branch. *Added NULL, backfilled to each branch's default location, then SET NOT NULL (the keep-data-safe 3-step). |

- Drop `uq_stock_on_hand_scope (company_id, branch_id, product_id)`; add **`uq_stock_on_hand_scope (company_id, branch_id, location_id, product_id)`** — the re-grained natural key. (Because the backfill puts exactly one location per branch, the existing one-row-per-(branch,product) data maps 1:1 into the new key with no collision — NFR-INVD-09.)
- `fk_stock_on_hand_location FOREIGN KEY (location_id) REFERENCES stock_locations(id)`.
- `avg_cost` / `on_hand_value` semantics per D-2 (`avg_cost` synced across the product's location rows; `on_hand_value` = row qty × avg). `reserved_qty` (ADR-0021) stays per row.
- New index `ix_stock_on_hand_location ON stock_on_hand (company_id, branch_id, location_id)`.

**ALTER `stock_movements` (additive):**

| column | type | null | default | notes |
|---|---|---|---|---|
| `location_id` | BIGINT | NO* | (backfilled) | FK → `stock_locations(id)`; the location this movement affected. *3-step backfill to the branch default. |

- Widen `chk_stock_movement_type` to admit **`TRANSFER_OUT`** and **`TRANSFER_IN`** (the reserved enum values, ADR-0010 OQ-STOCK-08) — the additive `DROP/ADD CONSTRAINT` union pattern, keeping all existing tokens. **No `COUNT` movement type** — a count uses the existing `ADJUSTMENT` type with a count `source_document` (OQ-INVD-07, D-6).
- `chk_stock_movement_reason` is **widened additively** so a count-driven `ADJUSTMENT` (which now carries `source_document_type='STOCK_COUNT'`) still satisfies "ADJUSTMENT must have a reason" — the count posts each variance line with an `AdjustmentReason` (FR-INVD-16), so the existing CHECK already holds; **no change to the reason CHECK is required** (verified: the count supplies a reason on every ADJUSTMENT row). TRANSFER_* rows carry **no reason** — the CHECK already says non-ADJUSTMENT carries no reason, so TRANSFER_* is admitted with `reason_code = NULL`. ✓
- New index `ix_stock_movements_location ON stock_movements (company_id, branch_id, location_id, product_id, occurred_at)`.

**`StockPostingService.post(...)` gains a `locationId` parameter** (additive, inserted after `branchId`) — the single primitive now records the location on the movement and upserts the `(company, branch, location, product)` on-hand row. Existing callers pass `LocationResolver.defaultLocationId(...)` until they thread an explicit location. **`StockReservationService` (ADR-0021) gains the same `locationId`** — reservation is per location row (a reservation is "spoken for at this location"); available-to-promise becomes per location (the branch ATP is the sum). (v1 default: reservation lands at the branch default location, preserving ADR-0021 behaviour; explicit per-location reservation is the additive hook.)

**The backfill (the load-bearing migration step):** for every existing branch, INSERT one **system default location** (D-4, `is_default = true`); then `UPDATE stock_on_hand SET location_id = <branch default>` and `UPDATE stock_movements SET location_id = <branch default>` (joined by `branch_id`); then `ALTER ... SET NOT NULL`. Every quantity/value/recon is unchanged because all stock simply now lives "at the default location" — a pure re-labelling. `MigrationKeepDataIT` extends to assert the recon and on-hand totals are identical before/after (NFR-INVD-09).

### D-4 — `stock_locations` master (FR-INVD-01..04, BR-INVD-01/03)

`stock_locations` (plural master; `MasterStatus` soft-delete):

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_stock_location_uid` |
| `company_id` | BIGINT | NO | `fk_stock_location_company` |
| `branch_id` | BIGINT | NO | `fk_stock_location_branch`; the owning branch |
| `code` | VARCHAR(30) | NO | `uq_stock_location_company_code UNIQUE (company_id, code)` (unique per company, BR-INVD-01) |
| `name` | VARCHAR(120) | NO | |
| `location_type` | VARCHAR(20) | NO | `LocationType`; `chk_stock_location_type CHECK (location_type IN ('WAREHOUSE','STORE','VAN','QUARANTINE','OTHER'))` |
| `is_default` | BOOLEAN | NO | DEFAULT false; exactly one true per branch (FR-INVD-03) |
| `status` | VARCHAR(20) | NO | `MasterStatus`; DEFAULT `'ACTIVE'`; `chk_stock_location_status` |
| `version` + audit cols | | | `@Version`, created/updated by/at |

- **`is_default` partial-unique:** `CREATE UNIQUE INDEX uq_stock_location_one_default ON stock_locations (company_id, branch_id) WHERE is_default = true` — a Postgres partial unique index enforcing exactly-one-default per branch (BR-INVD-01). Setting a new default is a service op that clears the old default and sets the new in one TX (`StockLocationService.setDefault`).
- A location with movement/on-hand history is **never hard-deleted** — `deactivate` flips `status` to INACTIVE (BR-INVD-03); the service rejects deactivating a branch's only/default location without first naming a replacement default.
- `LocationType` enum in `stock.domain.enums`: `WAREHOUSE, STORE, VAN, QUARANTINE, OTHER`.
- **ScopeGuard target type `stocklocation`** → `stockLocations.findCompanyIdByUid(uid)` (D-10).

### D-5 — Inter-location transfer: `stock_transfers` + `stock_transfer_lines`; in-transit `TRANSFER_OUT`/`TRANSFER_IN`; NO GL (value-preserving, same cost grain) (FR-INVD-08..11, BR-INVD-05/06/07)

`stock_transfers` (header):

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_stock_transfer_uid` |
| `company_id` | BIGINT | NO | tenant |
| `transfer_number` | VARCHAR(30) | NO | `TRF-####` at create; `uq_stock_transfer_company_number UNIQUE (company_id, transfer_number)` |
| `status` | VARCHAR(20) | NO | `StockTransferStatus`; DEFAULT `'DRAFT'`; `chk_stock_transfer_status` |
| `transfer_mode` | VARCHAR(12) | NO | `INSTANT` \| `IN_TRANSIT`; `chk_stock_transfer_mode` (OQ-INVD-02) |
| `source_branch_id` / `source_location_id` | BIGINT | NO | FK → branches / stock_locations |
| `dest_branch_id` / `dest_location_id` | BIGINT | NO | FK; dest may be a different branch (same company) |
| `transfer_date` | DATE | NO | |
| `dispatched_at` / `received_at` | TIMESTAMPTZ | YES | transition stamps |
| `notes` | VARCHAR(500) | YES | |
| `version` + audit | | | |

Constraints: `chk_stock_transfer_status CHECK (status IN ('DRAFT','DISPATCHED','RECEIVED','COMPLETED','CANCELLED'))`; `chk_stock_transfer_mode CHECK (transfer_mode IN ('INSTANT','IN_TRANSIT'))`; `chk_stock_transfer_distinct CHECK (source_location_id <> dest_location_id)`; FKs to companies/branches/locations.

`stock_transfer_lines` (child): `id`/`uid` (`uq_stock_transfer_line_uid`), `stock_transfer_id` FK, `company_id`, `line_no` SMALLINT (`uq_stock_transfer_line_no UNIQUE (stock_transfer_id, line_no)`), `product_id`/`product_code`/`product_name` snapshot, `unit_id`/`unit_name` snapshot, `qty_transferred` NUMERIC(19,6) `CHECK > 0`, `qty_transferred_base` NUMERIC(19,6) `CHECK > 0`, `value_amount` NUMERIC(19,4) NULL (the attributed value moved = qty × source avg at dispatch, for audit/diagnostics; the move re-attributes value via the on-hand recompute, not this column), `currency`, audit.

**Lifecycle + mechanism (D-2 cost grain → no GL):**

- **`StockTransferStatus`:** `DRAFT → DISPATCHED → RECEIVED` (IN_TRANSIT mode) or `DRAFT → COMPLETED` (INSTANT mode); `DRAFT → CANCELLED`. Numbering `TRF-####` at create. Immutable once dispatched/completed (corrections are a reverse transfer).
- **INSTANT (same-branch default, OQ-INVD-02):** `complete` posts, per line, a `TRANSFER_OUT` movement at the source location and a `TRANSFER_IN` movement at the dest location in **one TX**, both at the company-product `avg_cost` (the value column on each movement). On-hand at source falls, at dest rises; the company-product Σ qty and Σ value are **invariant** (same product, same average) → `1300` does not move → **no GL entry** (BR-INVD-05). `MovementType.TRANSFER_OUT` (− at source), `TRANSFER_IN` (+ at dest).
- **IN_TRANSIT (cross-branch default):** `dispatch` publishes `TRANSFER.DISPATCHED` (outbox) → `TransferDispatchStockHandler` posts the `TRANSFER_OUT` at source (− qty, − value) and the qty/value goes to the **in-transit holding** (modelled as a movement out of the source whose counterpart has not yet landed — the company total includes in-transit because the value left source but the matching `TRANSFER_IN` has not posted; the recon counts in-transit as inventory the company still owns, BR-INVD-07). `receive` publishes `TRANSFER.RECEIVED` → `TransferReceiveStockHandler` posts the `TRANSFER_IN` at dest (+ qty, + value). Each handler is `IdempotencyGuard`-wrapped (consumers `"STOCK.TRANSFER_DISPATCH"` / `"STOCK.TRANSFER_RECEIVE"`) and backstopped by `uq_stock_movement_source_event`.

> **In-transit recon (BR-INVD-07) — the one subtlety.** Between dispatch and receive, the source has posted a `TRANSFER_OUT` (−value) but the dest has not yet posted the matching `TRANSFER_IN` (+value), so `Σ(on_hand_value)` over location rows is **temporarily short** by the in-transit value while `1300` is **unchanged** (no GL posted). To keep `Σ == 1300` true *throughout* an in-transit transfer, the dispatched value is held in an **in-transit on-hand row**: a `stock_on_hand` row at a per-transfer / per-branch **in-transit pseudo-location** (a `LocationType` value is **not** added; instead the dispatch lands the `TRANSFER_OUT` counterpart into an **in-transit location** of the destination branch — a `stock_locations` row of type `OTHER` flagged in-transit, created lazily per branch). **Decision: dispatch moves qty/value from the source location into the destination branch's in-transit location; receive moves it from in-transit to the dest location.** Both legs are `TRANSFER_OUT`/`TRANSFER_IN` against on-hand rows that all sum into the company total → `Σ(on_hand_value) == 1300` holds at every instant, and no GL is ever posted. (This is the boring, recon-safe realisation of "in-transit"; the in-transit location is an internal book-keeping place, not a user-managed warehouse.)

**No GL is posted for a transfer in v1** (D-2 single average → net-zero on `1300`). The `JournalSourceType` is therefore **not** widened for transfers. (If per-location costing is ever adopted, a cross-average transfer posts a carrying-value leg — additive, a new `STOCK_TRANSFER` source type then.)

### D-6 — Stock count / cycle count: `stock_counts` + `stock_count_lines`; snapshot → variance → ONE-act `STOCK_ADJUSTMENT` posting via the shipped ADR-0020 revaluation (FR-INVD-12..17, BR-INVD-08/09)

`stock_counts` (header):

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_stock_count_uid` |
| `company_id` / `branch_id` | BIGINT | NO | tenant |
| `count_number` | VARCHAR(30) | NO | `CNT-####` at create; `uq_stock_count_company_number` |
| `status` | VARCHAR(20) | NO | `StockCountStatus`; DEFAULT `'DRAFT'`; `chk_stock_count_status` |
| `count_type` | VARCHAR(12) | NO | `FULL` \| `CYCLE`; `chk_stock_count_type` (a cycle count is a filtered-scope count, FR-INVD-17) |
| `location_id` | BIGINT | NO | the counted location |
| `count_date` | DATE | NO | |
| `frozen_at` / `posted_at` / `cancelled_at` | TIMESTAMPTZ | YES | transition stamps |
| `variance_gl_entry_uid` | VARCHAR(26) | YES | the net-variance journal uid posted on post (diagnostic) |
| `notes` | VARCHAR(500) | YES | |
| `version` + audit | | | |

`stock_count_lines` (child): `id`/`uid` (`uq_stock_count_line_uid`), `stock_count_id` FK, `company_id`/`branch_id`, `line_no` SMALLINT (`uq_stock_count_line_no`), `product_id`/`product_code`/`product_name`/`unit_id`/`unit_name` snapshot, `system_qty` NUMERIC(19,6) (the frozen snapshot), `counted_qty` NUMERIC(19,6) NULL (entered during COUNTING), `variance_qty` NUMERIC(19,6) NULL (computed = counted − live-on-hand at post), `unit_cost_amount` NUMERIC(19,4) NULL (the avg at post), `variance_value` NUMERIC(19,4) NULL, `reason_code` VARCHAR(40) NULL (`AdjustmentReason` for material variances, FR-INVD-16), `movement_uid` VARCHAR(26) NULL (the ADJUSTMENT movement posted for this line), `currency`, audit.

**Lifecycle + mechanism (reuse ADR-0020, no new accounting):**

- **`StockCountStatus`:** `DRAFT → COUNTING → POSTED`; `DRAFT/COUNTING → CANCELLED`. Numbering `CNT-####` at create. POSTED is immutable (FR-INVD-15).
- **create + freeze:** snapshot `system_qty` per in-scope product (a FULL count covers all products with on-hand at the location; a CYCLE count covers a supplied product subset — OQ-INVD-05). `frozen_at` set.
- **enter:** record `counted_qty` per line (COUNTING).
- **post (BR-INVD-08/09):** in the operator's TX, per line with `counted_qty` set: compute **`variance_qty = counted_qty − live_on_hand`** at the location (recomputed against **live** on-hand, not the stale snapshot — OQ-INVD-06/BR-INVD-09; warn if `live != system_qty`). If non-zero, post an **`ADJUSTMENT`** movement at the location via `StockPostingService.post(... MovementType.ADJUSTMENT, source_document_type='STOCK_COUNT', source_document_uid=countUid, reason_code=<AdjustmentReason>, unitCost=avg, value=variance×avg ...)` and call **`InventoryValuationService.revalueAdjustment(movementUid, soh, variance_qty, postDate)`** — which posts **`DR/CR STOCK_ADJUSTMENT vs 1300`** via the shipped `InventoryGlPoster.postAdjustmentDirect` (D-7 of ADR-0020). **One journal per count** is preferred (batch the net per direction), but reusing the per-line `revalueAdjustment` (one journal per non-zero line) is the simplest correct realisation — **Decision: post one `STOCK_ADJUSTMENT` journal per count** by accumulating the net debit/credit across lines and calling a single `postAdjustmentDirect` with the net (the count is one document act, FR-INVD-14); per-line movements still post individually (the on-hand truth is per line). `variance_gl_entry_uid` records the journal.
- **`sourceType = STOCK_ADJUSTMENT`** (admitted since V17 — no widen needed for the count). The count introduces **no new account, no new key** — it reuses `STOCK_ADJUSTMENT (5160)` and `INVENTORY (1300)`.

> The count is **deliberately not a new accounting treatment** — it is the shipped ADR-0020 adjustment, batched into a document with a snapshot and per-line variances. The recon holds because each variance moves `1300` and `STOCK_ADJUSTMENT` by the same value (BR-INVD-12).

### D-7 — Batch/lot + serial tracking: `stock_batches` (lot/expiry/FEFO) + `stock_serials` (per-unit identity); product-master flags (FR-INVD-18..27, BR-INVD-10/11/13)

**Product-master flags (ALTER `products`, additive):**

| column | type | null | default | notes |
|---|---|---|---|---|
| `lot_tracked` | BOOLEAN | NO | false | the product tracks lots/batches (FR-INVD-18) |
| `serial_tracked` | BOOLEAN | NO | false | the product tracks individual serials (FR-INVD-23) |

- `chk_product_tracking_exclusive CHECK (NOT (lot_tracked AND serial_tracked))` — a product is lot-tracked **or** serial-tracked **or** neither (a serial is one unit; a lot is a quantity; v1 keeps them exclusive for clarity — OQ-INVD-05). Both default false → all existing products are fungible quantity, unchanged.
- **Immutability (BR-INVD-13):** the flags are settable only while the product has **no stock movement history** — the service rejects flipping `lot_tracked`/`serial_tracked` once a movement exists (a `count(stock_movements WHERE product_id = ?) = 0` guard), mirroring the opening-balance once-only rule. (No DB trigger; a service guard + an audited rejection.)

**`stock_batches` (NEW — lot/expiry, per `(location, product, lot)`):**

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_stock_batch_uid` |
| `company_id` / `branch_id` | BIGINT | NO | tenant |
| `location_id` | BIGINT | NO | FK → stock_locations; the lot's on-hand location |
| `product_id` | BIGINT | NO | FK → products (lot_tracked) |
| `lot_number` | VARCHAR(60) | NO | `uq_stock_batch_product_lot UNIQUE (company_id, product_id, lot_number)` (unique per product, BR-INVD-10) — but on-hand is per `(location, product, lot)`: use `uq_stock_batch_scope UNIQUE (company_id, branch_id, location_id, product_id, lot_number)` for the per-location on-hand row |
| `manufacture_date` | DATE | YES | |
| `expiry_date` | DATE | YES | drives FEFO + the expiry report (FR-INVD-21/22) |
| `qty_on_hand` | NUMERIC(19,6) | NO | DEFAULT 0; the lot's on-hand at this location (signed; flagged not blocked if negative) |
| `version` + audit | | | `@Version` (concurrent lot consume) |

- **FEFO consume (FR-INVD-21, `LotConsumptionPolicy.FEFO`):** on an issue (sale/delivery/transfer-out/count shortage) of a lot-tracked product, `StockBatchService.consume(location, product, qty)` decrements lots **ordered by `expiry_date NULLS LAST, id`** until the qty is satisfied; a shortfall drives the oldest lot negative and is flagged (not blocked — BR-INVD-06). `LotConsumptionPolicy` enum: `FEFO` (default), `FIFO`, `MANUAL` (reserved).
- **Integrity invariant (BR-INVD-10):** `Σ stock_batches.qty_on_hand` for `(location, product)` = the `stock_on_hand.quantity` of that row (a lot-tracked product). The service maintains it; a verification query asserts it.
- **Receipt records the lot (FR-INVD-19):** the receipt path, for a lot-tracked product, requires a lot number + optional expiry on the receipt line and lands the lot's `qty_on_hand`. (Requires the goods-receipt payload to carry lot data for lot-tracked lines — a Purchases-side touch, flagged in the cross-module list; absent lot data on a lot-tracked receipt is rejected.)
- **ScopeGuard target type `stockbatch`** → `stockBatches.findCompanyIdByUid(uid)`.

**`stock_serials` (NEW — per-unit identity):**

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_stock_serial_uid` |
| `company_id` / `branch_id` | BIGINT | NO | tenant |
| `location_id` | BIGINT | YES | FK; current location (NULL when ISSUED) |
| `product_id` | BIGINT | NO | FK → products (serial_tracked) |
| `serial_number` | VARCHAR(80) | NO | `uq_stock_serial_product_serial UNIQUE (company_id, product_id, serial_number)` (unique per product, BR-INVD-11) |
| `serial_status` | VARCHAR(12) | NO | `SerialStatus`; `chk_stock_serial_status CHECK (serial_status IN ('IN_STOCK','ISSUED','RETURNED'))` |
| `received_document_uid` / `issued_document_uid` | VARCHAR(26) | YES | the receipt / delivery the serial rode |
| `version` + audit | | | |

- **`SerialStatus`:** `IN_STOCK → ISSUED → RETURNED` (→ IN_STOCK on restock). Each transition is audited.
- **Integrity invariant (BR-INVD-11):** `count(stock_serials WHERE status='IN_STOCK' AND location=L AND product=P)` = the `stock_on_hand.quantity` of `(L, P)` for a serial-tracked product.
- **Receipt records serials (FR-INVD-24):** the receipt path, for a serial-tracked product, requires N serial numbers for N units (qty must be a whole number = the serial count) and creates N `stock_serials` (IN_STOCK at the receiving location). **Sale/delivery (FR-INVD-25):** captures the serials issued → ISSUED; **transfer (FR-INVD-26):** moves `location_id`; **lookup (FR-INVD-27):** by `serial_number` returns status + location + movement history.
- **ScopeGuard target type `stockserial`** → `stockSerials.findCompanyIdByUid(uid)`.

### D-8 — Reads: per-location on-hand + expiry report (FR-INVD-05/06/22, NFR-INVD-06)

- **`LocationOnHandQuery`** (gated `STOCK.VIEW` — the existing perm; no new gate for a read of on-hand): per-location on-hand `SELECT company_id, branch_id, location_id, product_id, SUM(quantity), SUM(on_hand_value) ... GROUP BY ...` paginated, enriched with location code/name + product code/name. `LocationOnHandRowDto(locationUid, locationCode, locationName, productUid, productCode, productName, quantity, value, currency)`. `assertCanActIn(principal, principal.companyId())` on the read path.
- **`ExpiryReportQuery`** (gated **`INVENTORY.EXPIRY.VIEW`** — NEW perm): lots `WHERE expiry_date <= :horizon AND qty_on_hand > 0 ORDER BY expiry_date`, per product/location, with qty + value at risk (qty × company-product avg). `ExpiryReportRowDto(productUid, productCode, lotNumber, locationCode, expiryDate, qtyOnHand, valueAtRisk, expired, currency)`. `assertCanActIn` on the read.
- The **valuation recon** (ADR-0020 `StockValuationQuery`) is **unchanged** — it sums `on_hand_value` across all rows (now including location rows + in-transit rows) per company-product and reconciles to `1300`. The re-grain adds rows to the sum; the sum is invariant (D-2). The report MAY add a per-location breakdown column (additive, optional).

### D-9 — Numbering: two new `code_sequence` kinds (TRANSFER / STOCK_COUNT)

`WarehouseNumberGenerator` reuses the shipped `code_sequence` row-locked allocation (ADR-0007 D-6) with two new `entity_kind` values: `STOCK_TRANSFER` (`TRF-%04d`) and `STOCK_COUNT` (`CNT-%04d`), per company, created lazily on first use (`next_value = 1`). No seed rows → **no `#12` exposure for numbering**. The `uq_<doc>_company_number` constraints backstop generator bugs. (Locations use a user-supplied `code`, not a sequence; batches use a user-supplied `lot_number`; serials a user-supplied `serial_number`.)

### D-10 — `ScopeGuard` cases (D-1/D-4/D-5/D-6/D-7)

New target-type entries in `ScopeGuard.companyIdOf` (each backed by a `findCompanyIdByUid(uid)` on the new repository), and the repos added to the constructor (the established additive pattern):

- `case "stocklocation"  -> stockLocations.findCompanyIdByUid(uid);`
- `case "stocktransfer"  -> stockTransfers.findCompanyIdByUid(uid);`
- `case "stockcount"     -> stockCounts.findCompanyIdByUid(uid);`
- `case "stockbatch"     -> stockBatches.findCompanyIdByUid(uid);`
- `case "stockserial"    -> stockSerials.findCompanyIdByUid(uid);`

`assertCanActIn(principal, principal.companyId())` is called on every read path (the per-location on-hand read, the expiry report, the serial lookup) and every write path (location create/edit/default, transfer create/dispatch/receive, count create/enter/post, lot/serial mutation) — the #1 anti-regression guard (BR-INVD-14).

### D-11 — Permissions (FR-INVD-28; V37-V41 seed + `ORG_ADMIN` grant, the V7/V12/V17 pattern)

| code | module | description |
|---|---|---|
| `STOCK.LOCATION.VIEW` | `stock` | View stock locations |
| `STOCK.LOCATION.MANAGE` | `stock` | Create/edit/deactivate locations and set the branch default |
| `STOCK.TRANSFER.VIEW` | `stock` | View inter-location transfers |
| `STOCK.TRANSFER.CREATE` | `stock` | Create + dispatch an inter-location transfer |
| `STOCK.TRANSFER.RECEIVE` | `stock` | Receive an in-transit transfer at the destination |
| `STOCK.COUNT.VIEW` | `stock` | View stock counts |
| `STOCK.COUNT.CREATE` | `stock` | Create a stock count, freeze the snapshot, enter counted quantities |
| `STOCK.COUNT.POST` | `stock` | Post a stock count's variance to the books (the variance-posting authority) |
| `INVENTORY.BATCH.VIEW` | `stock` | View batch/lot on-hand and lookups |
| `INVENTORY.SERIAL.VIEW` | `stock` | View serial numbers, status, location, and history |
| `INVENTORY.EXPIRY.VIEW` | `stock` | View the lot expiry / near-expiry report |

The **receipt / delivery-issue / adjustment / opening** postings keep their existing gates (they gain a `location_id` parameter, not a new permission — FR-INVD-28). `@PreAuthorize` on the new controller methods gates these codes via `@perm.has(...)` / `@perm.scoped(#uid, '<targetType>', '<CODE>')`; **never** `hasAuthority`. All eleven are granted to `ORG_ADMIN` in the migration.

### D-12 — Events (new `DomainEventType` values + handlers)

Two new event constants in `DomainEventType` (the transfer dispatch/receive seam, D-5); the count posts **synchronously** in the operator's TX (no event — it is a human act with a direct GL post, the ADR-0020 adjustment precedent):

```java
public static final String STOCK_TRANSFER_DISPATCHED = "STOCK.TRANSFER.DISPATCHED";  // NEW (D-5)
public static final String STOCK_TRANSFER_RECEIVED    = "STOCK.TRANSFER.RECEIVED";     // NEW (D-5)
public static final String AGG_STOCK_TRANSFER          = "STOCK_TRANSFER";              // NEW agg
```

- `STOCK.TRANSFER.DISPATCHED` → `TransferDispatchStockHandler` (consumer `"STOCK.TRANSFER_DISPATCH"`): `TRANSFER_OUT` at source → in-transit (for IN_TRANSIT mode). Payload `TransferDispatchedPayload(transferUid, companyId, sourceBranchId, sourceLocationId, inTransitLocationId, dispatchedAt, List<LineItem(productId, productUid, unitId, qtyInBase)>)`.
- `STOCK.TRANSFER.RECEIVED` → `TransferReceiveStockHandler` (consumer `"STOCK.TRANSFER_RECEIVE"`): `TRANSFER_IN` from in-transit → dest location. Payload `TransferReceivedPayload(transferUid, companyId, destBranchId, destLocationId, inTransitLocationId, receivedAt, List<LineItem>)`.
- **INSTANT mode** posts both movements synchronously in the `complete` TX (no event needed — same-branch, no crash-safety gap worth an event); IN_TRANSIT uses the events for the dispatch/receive split + idempotency + crash-safety (NFR-INVD-04).
- Both handlers are `IdempotencyGuard`-wrapped + backstopped by `uq_stock_movement_source_event`. **No GL is posted by either** (D-2/D-5 — net-zero on `1300`).
- `StockCountPostedPayload` is declared for an optional downstream consumer (e.g. notifications) but the **count GL posts synchronously** — the payload is informational, not the posting trigger (the post is a direct `postAdjustmentDirect`, BR-INV-12: a missing config must fail the operator's command).

**No new `JournalSourceType`** — transfers post no GL; the count reuses `STOCK_ADJUSTMENT` (admitted since V17). **No new `gl_config` key, no new CoA account** — the count reuses `STOCK_ADJUSTMENT (5160)` + `INVENTORY (1300)` (ADR-0020).

### D-13 — V37-V41 migration ordering (additive; V1–V19 FROZEN; #12-safe seeds)

The slice spans five migrations in the reserved V37-V41 band (one logical block each; the engineer MAY collapse to fewer files if the coordinator's range allows — the table/column/constraint names are fixed regardless). **Order is load-bearing: the location master + the default-location backfill MUST precede the on-hand re-grain.**

- **`V37__stock_locations.sql`** — CREATE `stock_locations` (+ `uq_stock_location_uid` / `uq_stock_location_company_code` / `chk_stock_location_type` / `chk_stock_location_status` / the partial-unique `uq_stock_location_one_default` / FKs). **Backfill:** INSERT one default location per branch — `#12`-safe seed-uid `'SLC' || lpad(b.company_id::text,6,'0') || substr(md5(b.id::text),1,12)` (3+6+12 = 21 ≤ 26; **never** raw-key concat), `code` derived deterministically from the branch (e.g. `'MAIN-' || b.code`), `is_default = true`, `location_type='WAREHOUSE'`, `status='ACTIVE'`, `ON CONFLICT (company_id, code) DO NOTHING`. (Also seed one in-transit `OTHER`-type location per branch for D-5 in-transit holding, lazily — or create lazily in code; recommend the migration seeds it for determinism, `#12`-safe uid `'SLT' || ...`.)
- **`V38__stock_onhand_regrain.sql`** — the behaviour-neutral re-grain (3-step, NFR-INVD-09): (1) `ALTER stock_on_hand ADD COLUMN location_id BIGINT` (NULL); `ALTER stock_movements ADD COLUMN location_id BIGINT` (NULL). (2) `UPDATE stock_on_hand SET location_id = <branch default>` joined by `branch_id`; same for `stock_movements`. (3) `ALTER ... SET NOT NULL`; `ADD CONSTRAINT fk_*_location`; **drop** `uq_stock_on_hand_scope` and **re-add** including `location_id`; add `ix_stock_on_hand_location` + `ix_stock_movements_location`. **Widen `chk_stock_movement_type`** (DROP/ADD, union) to admit `'TRANSFER_OUT','TRANSFER_IN'` (keep all existing 6 tokens).
- **`V39__stock_transfers.sql`** — CREATE `stock_transfers` + `stock_transfer_lines` (+ constraints per D-5).
- **`V40__stock_counts.sql`** — CREATE `stock_counts` + `stock_count_lines` (+ constraints per D-6). (No journal source-type widen — reuses `STOCK_ADJUSTMENT`.)
- **`V41__stock_batch_serial_perms.sql`** — `ALTER products ADD lot_tracked / serial_tracked BOOLEAN NOT NULL DEFAULT false` + `chk_product_tracking_exclusive`; CREATE `stock_batches` + `stock_serials` (+ constraints per D-7); **permission seed** (the 11 codes of D-11) `ON CONFLICT (code) DO NOTHING` + the `ORG_ADMIN` grant via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (the V7/V12/V17 pattern; perms have no uid → #12 N/A).

`MigrationKeepDataIT` extends to V41 and **asserts NFR-INVD-09**: after V37+V38, every existing `stock_on_hand.quantity` / `on_hand_value` and the valuation recon `Σ == 1300` are identical to pre-V37 (the re-grain is pure re-labelling). The `#12` seed-uid trap fires only on keep-data deploys where branches already exist — the IT must seed a company+branch before the migration to exercise the default-location backfill uid.

`MovementType` (Java) admits `TRANSFER_OUT`/`TRANSFER_IN` (already reserved — now CHECK-admitted). New enums `LocationType`, `StockTransferStatus`, `StockCountStatus`, `SerialStatus`, `LotConsumptionPolicy` are pure Java (no DB CHECK token reuse beyond their own table CHECKs).

### D-14 — Angular nav routes (the `admin/<module>/<resource>` pattern, web admin.routes.ts)

New lazy routes under `admin`, each `canActivate: [requirePermission('<CODE>')]`:

- `admin/stock/locations` (`STOCK.LOCATION.VIEW`) — location list/manage
- `admin/stock/transfers` (`STOCK.TRANSFER.VIEW`) — transfer list
- `admin/stock/transfers/create` (`STOCK.TRANSFER.CREATE`) — new transfer
- `admin/stock/transfers/uid/:uid` (`STOCK.TRANSFER.VIEW`) — transfer detail / dispatch / receive
- `admin/stock/counts` (`STOCK.COUNT.VIEW`) — count list
- `admin/stock/counts/create` (`STOCK.COUNT.CREATE`) — new count / enter
- `admin/stock/counts/uid/:uid` (`STOCK.COUNT.VIEW`) — count detail / post
- `admin/stock/by-location` (`STOCK.VIEW`) — per-location on-hand report
- `admin/stock/batches` (`INVENTORY.BATCH.VIEW`) — batch/lot lookup
- `admin/stock/serials` (`INVENTORY.SERIAL.VIEW`) — serial lookup/history
- `admin/stock/expiry` (`INVENTORY.EXPIRY.VIEW`) — expiry report

### D-15 — ArchUnit module-edge rules (no cycle)

- **`stock.service`/`stock.events` → `gl.service`** (`InventoryGlPoster` already; the count reuses it) + **`stock.service` → `gl.repository`** (`accountBalance` for the recon, unchanged) + `gl.domain.dto`/`gl.domain.enums`. **Allowed** — the shipped ADR-0020 D-12 allowance; this slice adds no new GL edge (the count reuses `InventoryGlPoster`, transfers post no GL).
- **`stock` → `products`** (DTO reads — the tracking flags; already shipped, unchanged) and **`stock` → `iam`** (branches, for the location's owning branch — the location FKs `branches`; the read is via the shipped scope/repository pattern, no new entity import beyond the existing branch reference).
- **No new edge `stock → sales` / `stock → purchases`** — the receipt/delivery handlers already consume the sales/purchases *payload DTOs* (ADR-0021 D-13), unchanged; the location flows through those payloads (a Purchases/Sales-side touch to populate `locationId` on the receipt/delivery payload, flagged in the cross-module list — but no new module-edge in stock).
- **No cycle:** stock → gl, stock → products, stock → iam; gl/products/iam do not depend on stock. Acyclic. The shipped `ModuleBoundaryTest` enforces controller↛repository, service↛controller, audit-append-only — **none of these edges violates an active rule** (verified against ADR-0020 D-12 / ADR-0021 D-13 documented allowances). The new controllers (`StockLocationController`, `StockTransferController`, `StockCountController`, plus the batch/serial/expiry read controllers, flat in `com.erp.api`) touch only services.

## Cross-module touch-points

1. **Purchases → stock (receipt location + lot/serial on receipt).** The goods-receipt confirmation that produces `STOCK.RECEIVED` must populate a **receiving `locationId`** on the payload (default = branch default location), and for lot-tracked / serial-tracked products must carry the **lot number/expiry** / **serial numbers** on the receipt lines. This is a Purchases-side change (payload extension + GR-line capture); stock only reads it. Absent a location, stock defaults to the branch default (migration-safe); absent lot/serial data on a tracked product, stock rejects (FR-INVD-19/24).
2. **Sales → stock (issue location + serials on delivery).** The delivery (`DELIVERY.CONFIRMED`) and direct-invoice issue must carry a **picking `locationId`** (default = branch default) and, for serial-tracked products, the **serials issued**. A Sales-side payload extension; stock reads it. (ADR-0021's `DeliveryIssueStockHandler` / `SaleIssueStockHandler` gain the location param, D-3.)
3. **stock → gl (count variance only).** The count reuses `InventoryGlPoster.postAdjustmentDirect` (DR/CR `STOCK_ADJUSTMENT` vs `1300`) — no new GL surface. Transfers post no GL.
4. **products master flags.** `lot_tracked` / `serial_tracked` are added to `products` (ALTER) — a stock-driven change to the products table (additive columns + a CHECK), within the products schema but seeded/managed by stock semantics. The product-edit UI surfaces the two flags (a Products-side UI touch).

## Consequences

**Positive**
- The warehouse becomes physical: on-hand and value are per location; receipts land somewhere, issues pick from somewhere; the branch view is a roll-up. The flat model is gone with **zero recon change** (D-2).
- Inter-location transfers are correct accounting: a value-preserving, non-P&L, non-adjustment move on the reserved `TRANSFER_OUT`/`TRANSFER_IN` types, posting **no GL** (net-zero on `1300`) — the in-transit realisation keeps `Σ == 1300` true at every instant (BR-INVD-07/12).
- Stock counts post variances through the **shipped** ADR-0020 adjustment-revaluation in one document act — no new accounting, the recon discipline unchanged (BR-INVD-08).
- Batch/lot (FEFO + expiry) and serial identity are layered on the movement ledger with integrity invariants, serving recall/expiry/warranty traceability — **without** a per-lot/per-serial cost layer (identity, not costing — §10), keeping the average engine untouched.
- The change is additive: 7 new tables, 2 on-hand/movement columns + 1 re-grained unique constraint, 2 product flags, 2 CHECK widens (movement type + product tracking), 2 numbering kinds, 11 perms, 2 events + 2 handlers, 5 ScopeGuard cases. **V1–V19 frozen.**

**Negative / costs**
- The on-hand re-grain (V38) touches the hottest table and its unique constraint, and every path that upserts on-hand gains a `location_id`. The backfill MUST be behaviour-neutral — the chief delivery risk; `MigrationKeepDataIT` is the guardrail (NFR-INVD-09).
- The `avg_cost` is now physically carried on every location row and **must stay in sync** across a product's location rows (D-2). A receipt's recompute reads/writes all of a product's location rows under their `@Version` locks — more rows touched per receipt; mitigated by the single-instance QA posture and the one-retry. A bug that desyncs per-location `avg_cost` is a recon-grade defect (the recon catches the *total*, but a per-location attribution bug needs its own test).
- In-transit transfers need an internal in-transit location per branch (a book-keeping place, not a user warehouse) to keep the recon intact mid-flight — a small modelling subtlety the engineer must implement exactly (D-5).
- Lot/serial capture pushes a real data-entry burden onto receipt/issue and requires Purchases/Sales payload extensions in the same release (cross-module coordination).

**Neutral / deferred**
- Single company-product average (per-location costing deferred), bins below the location, putaway/picking orchestration, barcode/handheld, reorder automation, ABC, batch/serial costing, QC inspection lifecycle, lot/serial selection on transfers/counts — all deferred, none precluded (§2 / §10 / NFR-INVD-08). The location FK, the per-location rows, and the lot/serial tables are the foundations those build on.

## Alternatives considered

- **Cost grain — per-company-product average (locations share, value attributed) vs per-location average.** *Decided: per-company-product (D-2).* Per-location average is richer but changes the average engine, the recon decomposition, and forces every transfer to post a value-carrying GL leg. Per-company-product preserves ADR-0020 exactly and makes transfers net-zero on `1300`. Per-location average is a deferred additive refinement (the per-location rows make it reachable without a re-grain).
- **Transfer mechanism — `TRANSFER_OUT`/`TRANSFER_IN` (no GL) vs adjustment-out + adjustment-in vs a transfer GL clearing account.** *Decided: the reserved transfer movement types, no GL (D-5).* Adjustment pairs would post phantom `STOCK_ADJUSTMENT` expense (a P&L event a transfer is not). A transfer clearing account is only needed if locations carry different averages (deferred). With one average, a transfer is a sub-ledger re-attribution, not a GL event.
- **In-transit realisation — an in-transit on-hand row at an internal location vs an "in-transit" flag on the movement vs no in-transit (instant only).** *Decided: an internal in-transit location per branch (D-5).* A flag would leave `Σ(on_hand_value)` short of `1300` mid-flight (recon break, BR-INVD-12). Instant-only forgoes the dispatch/receive split real cross-branch logistics need. The in-transit location keeps the recon true at every instant and is the boring, legible choice.
- **Count movement type — reuse `ADJUSTMENT` (count source-doc) vs a new `STOCK_COUNT` movement type.** *Decided: reuse `ADJUSTMENT` (D-6, OQ-INVD-07).* A count variance *is* a stock adjustment at the current average; reusing the type avoids a CHECK widen and reuses `revalueAdjustment` verbatim. The count document + `source_document_type='STOCK_COUNT'` provides the traceability a distinct type would.
- **Count GL — one journal per count vs one per non-zero line.** *Decided: one journal per count (D-6)* — the count is one document act (FR-INVD-14); the net debit/credit is accumulated and posted once via `postAdjustmentDirect`. Per-line movements still post individually (on-hand truth is per line).
- **Lot/serial — identity-only (inherit the average) vs per-lot/per-serial cost layers.** *Decided: identity-only for v1 (D-7, §10).* Per-lot/per-serial costing is FIFO-by-another-name and is deferred with FIFO/standard cost (ADR-0020 §2). Identity serves recall/expiry/warranty fully; the cost is the company-product average.
- **New module vs depth in `stock`.** *Decided: depth in `stock` (D-1).* A `warehouse` module would re-read on-hand, duplicate the lock and valuation, and create a cycle risk. Location/transfer/count/lot/serial all hang off the on-hand row and the movement ledger stock owns.

## Open items (OQ-INVD — recommended defaults adopted as decisions; none blocks the build)

- **OQ-INVD-01 — cost grain:** adopted **per-company-product average, value attributed to locations pro-rata** (D-2). Per-location average deferred. The load-bearing decision; owner (finance) confirms before build if a per-location cost is wanted at v1 (it is not recommended).
- **OQ-INVD-02 — transfer mode:** adopted **IN_TRANSIT for cross-branch, INSTANT for same-branch** (D-5). Owner may force all-instant or all-in-transit (a service default flip).
- **OQ-INVD-03 — transfer-out on insufficient source:** adopted **flag-and-allow** (D-5, BR-INVD-06) — matches the overselling stance. Owner may block cross-branch.
- **OQ-INVD-04 — lot consumption order:** adopted **FEFO** (D-7); `FIFO`/`MANUAL` reserved in `LotConsumptionPolicy`.
- **OQ-INVD-05 — lot/serial on transfers/counts:** adopted **product-quantity grain for transfers/counts; lot/serial captured on receipt + issue only** (D-7, §10). Full lot/serial selection on transfer/count lines deferred. Also: lot vs serial **mutually exclusive** per product (D-7 CHECK).
- **OQ-INVD-06 — stale count snapshot:** adopted **recompute variance against live on-hand at post + warn** (D-6, BR-INVD-09) — the posted variance is always correct; the recon never breaks.
- **OQ-INVD-07 — count movement type:** adopted **reuse `ADJUSTMENT` with `source_document_type='STOCK_COUNT'`** (D-6). No new movement type.
- **OQ-INVD-08 — transfer movement types:** adopted **build the reserved `TRANSFER_OUT`/`TRANSFER_IN`** (D-3/D-5). CHECK widened additively.
- **OQ-INVD-09 — location backfill code:** adopted **deterministic per-branch code (e.g. `'MAIN-' || branch.code`) + `#12`-safe seed-uid** (D-13). Idempotent, keep-data-safe.

---

## Summary

ADR-0028 designs **Inventory / Warehouse Depth** as a depth slice in `com.erp.modules.stock`: a `stock_locations` master under the branch (`MasterStatus`, one default per branch via a partial-unique index), the **behaviour-neutral on-hand re-grain** to `(company, branch, location, product)` with a default-location backfill that leaves every quantity/value and the ADR-0020 `Σ == 1300` recon **unchanged** (the moving average stays **per-company-product**, value attributed to locations pro-rata — D-2), a value-preserving `stock_transfers` document on the reserved `TRANSFER_OUT`/`TRANSFER_IN` movement types that posts **no GL** (net-zero on `1300`, with an internal in-transit location keeping the recon true mid-flight — D-5), a `stock_counts` document that snapshots system on-hand and posts the **net variance to `STOCK_ADJUSTMENT` via the shipped ADR-0020 revaluation** in one act (D-6), and `stock_batches` (lot/expiry/FEFO) + `stock_serials` (per-unit identity) layered on the movement ledger as identity-only (no per-lot/serial cost layer — D-7). **Additive on frozen V1–V19, in the reserved V37-V41 band**, reusing `StockPostingService` / `InventoryValuationService` / `InventoryGlPoster` / the outbox / `IdempotencyGuard` / `code_sequence` / `ScopeGuard` with no new account, no new `gl_config` key, and no new `JournalSourceType`. **Recon is the chief acceptance bar** (NFR-INVD-01 / BR-INVD-12) and `MigrationKeepDataIT` guards the re-grain. **Cross-module touch list:** (1) Purchases populates a receiving `locationId` + lot/serial on `STOCK.RECEIVED`; (2) Sales populates a picking `locationId` + serials on `DELIVERY.CONFIRMED` / direct issue; (3) `products` gains `lot_tracked`/`serial_tracked` flags; (4) the count reuses `InventoryGlPoster` (no new GL surface).
