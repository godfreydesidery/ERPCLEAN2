# 0011 — Purchases data model: Purchase Order (header + ordered lines, maintained received/outstanding) + a SEPARATE Goods Receipt (header + lines, FK→PO), two per-company `code_sequence` series (PO-#### / GRN-####), the second transactional-outbox producer — finalising a Goods Receipt emits `STOCK.RECEIVED` (and a void emits `STOCK.RECEIPT.VOIDED`) inside the receive/void transaction; quantities into Stock, cost recorded but not valued; over-receipt rejected in the service, outstanding never negative

- **Status:** Accepted
- **Date:** 2026-06-07
- **Deciders:** solutions-architect (owner-ratified Purchases requirements 2026-06-07 — the headline ruling
  **v1 Purchases is a TWO-DOCUMENT flow: a Purchase Order raised first, then a SEPARATE Goods Receipt recorded
  against it**, partial receipts with outstanding tracking, cost recorded-not-valued, no AP in v1, the Goods Receipt
  is the `STOCK.RECEIVED` producer; every second-order choice OQ-PURCH-01..08 RESOLVED)
- **Context source:** [docs/requirements/purchases.md](../requirements/purchases.md) (RATIFIED 2026-06-07 —
  FR-PURCH-01a/01b/02a/02b/03/04/05/06/07/08/09/10/11/12/13, BR-PURCH-01..10, NFR-PURCH-01..07, §2 accepted-scope
  [two documents, partial receipts, cost-not-valued, no AP/VAT], §3 the two-document spine, §5/§6/§7 flows, §10
  RATIFIED scope, §11 OQ-PURCH-01..08 ALL RESOLVED, §12 deferred list); [ADR-0009](0009-transactional-outbox.md)
  (the outbox Purchases EMITS to — `domain_events` table, `OutboxPublisher.publish(eventType, aggregateType,
  aggregateId, aggregateUid, companyId, branchId, payload)` called **inside the caller's transaction** [D-3], the
  `DomainEventType` constants holder where new event types are registered [D-3], the `@Scheduled`
  `DomainEventDispatcher` poller + `DomainEventHandler` consumer contract [D-4/D-5], at-least-once delivery with
  consumer-side idempotency dedupe on event uid [D-6], best-effort `occurred_at` ordering + compensation-is-the-
  consumer's-job [D-7], no outbox audit [D-9]); [ADR-0010](0010-stock-data-model.md) (**the Stock consumer of
  `STOCK.RECEIVED`** — `GoodsReceiptStockHandler.eventType()=STOCK.RECEIVED` [D-5(3)] reads the payload
  `{ receiptUid, companyId, branchId, receivedAt, lines:[{ productId, productUid, unitId, qtyInBase }] }` and posts a
  `+qtyInBase` `GOODS_RECEIPT` movement per line; the **reserved GR-void compensating event** [D-5 note,
  lines 383–385] that Stock reverses **from its own ledger** by `source_document_uid`; idempotency on
  `(source_event_uid, product_id)` [D-4/D-6b]; a defensive non-stockable line skipped-and-recorded [D-3];
  `MovementType.GOODS_RECEIPT` is `+` in [D-6]); [ADR-0008](0008-sales-data-model.md) (**the closest precedent —
  Sales is the mirror**: header + lines, per-company + per-raising-branch denormalised scope [D-2], `code_sequence`
  numbering at a lifecycle transition [D-7], a lifecycle status enum with transitions in the service [D-7], snapshot
  columns on lines [D-2/D-3], the `Money`-pair-with-shared-document-currency discipline [D-2], the additive-grant
  permission seed + gate shapes [D-11/D-12], `companyUid`-in-create / branch-from-context [D-12], the
  `ScopeGuard.companyIdOf` `case "invoice"` extension [D-10], the audit emit table with plural `target_type`
  [D-13], the `SALE.FINALISED` payload [D-9] this ADR's `STOCK.RECEIVED` is parallel to);
  [ADR-0006](0006-parties-data-model.md) (the **Supplier** master Purchases references — `suppliers` table,
  scalar `Long supplier_id` FK + cross-module DTO read via `SupplierService`, `SUPPLIER.VIEW`/`SUPPLIER.MANAGE`,
  the per-company + multi-branch association + branch-must-be-same-company rule [BR-PARTY-01], the
  archived-not-selectable rule); [ADR-0007](0007-products-data-model.md) (the catalogue Purchases reads via DTO —
  `stockable` flag, base unit, bulk-pack conversion FR-PROD-06; the generic `code_sequence` `SELECT … FOR UPDATE`
  numbering primitive [D-6]; the `ScopeGuard.companyIdOf` target-type pattern [D-10]; the DB-can't/service-must
  enforcement split [D-9]; additive-migration discipline [D-14]; uid/id + Long-as-string + `PageMeta`);
  [ADR-0005](0005-money-and-currency.md) (`Money` = `amount` NUMERIC(19,4) + `currency` VARCHAR(3); every monetary
  value is an `(amount,currency)` pair; HALF_UP rounding; one document currency; wire `{amount:string,currency}`);
  [ADR-0004](0004-iam-audit-trail.md) (audit emit points, `AuditService.record`, plural `target_type`, fact-only
  `detail`); [ADR-0002](0002-rbac-enforcement.md) (RBAC permission + `ScopeGuard`); [ADR-0001](0001-iam-architecture.md)
  (D-A tenancy, D-G uid/ULID + internal-table rule); [ARCHITECTURE.md](../../ARCHITECTURE.md) §2 (module layout),
  §5 (tenant predicate + branch-override), §6 (audit), §9 (outbox); [PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md)
  §2 (module layout + `ModuleBoundaryTest`), §3.2 (tenant predicate), §3.3 (uid/id). **Verified against the shipped
  SQL** (ground truth; the prose naming doc was stale — [[db-naming-convention]]): the migration directory holds
  **V1__baseline.sql, V2__parties.sql, V3__products.sql, V4__units_of_measure.sql, V5__sales.sql** on disk
  (table style: `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`, `uid VARCHAR(26)`, plural tables, singular
  constraint roots `uq_`/`fk_`/`chk_`, plural index names `ix_`, `NUMERIC(19,6)` quantity scale, `NUMERIC(19,4)`
  money scale, audit cols, `JSONB` where used, the `code_sequence` shape, the permission seed + additive ORG_ADMIN
  grant). **Confirmed: no `com.erp.modules.purchases` package and no `purchase_*`/`goods_receipt*` table exist
  yet.** ADR-0009's outbox is **V6** (written, not yet shipped as SQL); ADR-0010's Stock is **V7** (written, not yet
  shipped). **Purchases therefore lands as `V8__purchases.sql`** (additive; never edits V1–V7).

This ADR is the **technical data model** for the Purchases module. It translates the ratified business spec
([purchases.md](../requirements/purchases.md)) and the outbox foundation ([ADR-0009](0009-transactional-outbox.md))
into tables, columns, types, keys, indexes, constraints, the two lifecycle status enums and their transitions, the
two `code_sequence` series, the `STOCK.RECEIVED` (+ void-reversal) outbox emit, the enforcement split, the
permission catalogue, the audit emit points, and the API/uid discipline — **concrete enough that the backend
engineer builds `V8__purchases.sql`, the `com.erp.modules.purchases` entities, and the receive/void service paths
without guessing a business rule.** It does **not** write production code, entities, or the migration — that is the
engineer's next step. The owner's ratified v1 decisions (two documents — PO then a separate GR; partial receipts
with maintained outstanding tracking; over-receipt rejected; cost recorded but not valued; no AP/VAT; the Goods
Receipt is the `STOCK.RECEIVED` producer, a GR void emits a compensating event) are taken as given and designed to
exactly. **Nothing ratified is re-litigated.** Purchases is the **second transactional-outbox producer** (Sales is
the first); it consumes nothing — it emits `STOCK.RECEIVED` for Stock (ADR-0010) to consume.

## Context

Purchases is the **buying** half of the trade loop Stock closes: stock-on-hand only rises because goods are
**received** from a supplier, and v1 makes that receipt the **real** goods-receipt from a purchase (purchases.md
§1), not a synthetic seed. The owner ruled (OQ-PURCH-01 RESOLVED) a **two-document flow** — a **Purchase Order**
raised first (the commitment to buy; moves no stock), then a **separate Goods Receipt** recorded against it (which
pushes stock IN via `STOCK.RECEIVED`). Everything Purchases consumes already exists or is one ADR away: Parties
gives `suppliers` (ADR-0006), Products gives stockable products with base-unit + bulk-pack conversion (ADR-0007),
ADR-0005 gives `Money`, IAM gives the tenant spine + `code_sequence` + audit, and ADR-0009 gives the outbox
Purchases emits to. The central architectural force is therefore the same as Sales': **mirror the proven ADR-0008
patterns; resolve only the genuinely new modelling questions the two-document, partial-receipt flow introduces.**
Those new questions, and the forces around each:

- **Two documents, not one — a PO header→lines AND a GR header→lines, linked by an order-to-receipt match.** Unlike
  Sales (one document, header→lines→payments), Purchases is **two** header→lines documents with a parent/child link
  across them: a GR line draws down a specific PO line's outstanding quantity. Forces: this is four tables
  (`purchase_orders`, `purchase_order_lines`, `goods_receipts`, `goods_receipt_lines`), two lifecycles, two
  numbering series, and a **cross-document quantity invariant** (a PO line's outstanding = ordered − cumulative
  received across its non-void GRs) that must hold under concurrency (NFR-PURCH-07). The new questions are *where
  the outstanding/received quantity is tracked* (maintained on the PO line vs derived), *where the PO's
  partial-vs-full RECEIVED state is computed*, and *how over-receipt is rejected*. Resolved in D-2/D-3/D-4/D-6.

- **Partial receipts + outstanding tracking is the genuinely new piece (FR-PURCH-07, BR-PURCH-10, NFR-PURCH-07).**
  Multiple GRs draw down one PO until every line is fully received; a receipt **must not exceed** a line's
  outstanding; voiding a GR **restores** outstanding. Forces: the outstanding figure is read on every GR draft
  (to validate and to show "what's left"), so a per-line maintained `received_qty` (outstanding = ordered −
  received) gives the O(1) read and the cheap invariant, at the cost of keeping it in lockstep with the GR lines —
  the same maintained-projection-kept-consistent-in-one-TX discipline the Stock on-hand row (ADR-0010 D-2) and the
  Sales totals (ADR-0008 D-4) already use. Resolved in D-3 (recommend **maintained `received_qty`** on the PO line).

- **The Goods Receipt is a `STOCK.RECEIVED` producer; a GR void is a compensating-event producer (FR-PURCH-08/09,
  BR-PURCH-06).** Finalising a GR must emit `STOCK.RECEIVED` **in the same transaction** as the receive, carrying the
  exact payload ADR-0010's `GoodsReceiptStockHandler` reads; voiding a finalised GR must emit a compensating event
  Stock reverses from its ledger. Forces: the payload shape is **fixed** by what the Stock consumer reads (ADR-0010
  D-5(3)) — parallel to `SALE.FINALISED` (ADR-0008 D-9); the emit must enrol in the caller's TX (ADR-0009 D-3 — never
  `REQUIRES_NEW`); the GR line must carry a snapshotted `qty_in_base` so the event is lossless (no live unit
  conversion at dispatch). Resolved in D-7/D-8.

- **Quantities into Stock, cost recorded but NOT valued (RATIFIED, §10; FR-PURCH-13, BR-PURCH-08/09).** Every PO/GR
  line carries a `Money` unit cost (required on a goods line — OQ-PURCH-04), but the stock effect is **quantity-only**
  (Stock v1 is quantity-only, ADR-0010); v1 computes **no** stock valuation, **no** VAT, **no** payable. Forces: the
  `STOCK.RECEIVED` payload carries **only quantity** (`qtyInBase`) — no cost (Stock has no money column, ADR-0010
  D-9); the cost lives on the Purchases line for the record and the future valuation/AP rounds; the model must not
  **preclude** later cost-into-valuation (NFR-PURCH-05). Resolved in D-5/D-8.

- **A document is immutable once placed/received; the v1 correction is a void.** A draft PO/GR is freely editable; an
  ORDERED PO's lines are frozen; a RECEIVED GR is immutable; the only changes are a permissioned, audited void of the
  GR or the PO (BR-PURCH-05, FR-PURCH-09). Forces: two lifecycle enums + transition guards in the service; the
  number is assigned at the lifecycle transition (PO at order-placement, GRN at receive) so a draft holds no number —
  the same nullable-number-until-finalise pattern Sales uses (ADR-0008 D-7). Resolved in D-6.

- **Schema freeze / migration ordering.** IAM=V1, Parties=V2, Products=V3, Units=V4, Sales=V5 — all frozen and
  shipped. ADR-0009's outbox is **V6**, ADR-0010's Stock is **V7** (both written this round, landing before
  Purchases). Purchases is a **new** module landing as a purely **additive `V8__purchases.sql`**; it must not edit
  V1–V7. Its FK targets (`companies`, `branches`, `suppliers`, `products`, `units_of_measure`, `app_users`) all
  exist in frozen V1–V4; it depends on V6's outbox (`OutboxPublisher`) as **runtime infrastructure** but, like Stock,
  takes **no FK into `domain_events`** (the link is the publish call, not a coupling FK).

## Decision

### D-1 — Module placement: one `com.erp.modules.purchases` module; controllers flat in `com.erp.api`

The two purchase documents live in a **single** module `com.erp.modules.purchases` with the standard internal
layout:

```
com.erp.modules.purchases
├── domain.entity   PurchaseOrder, PurchaseOrderLine, GoodsReceipt, GoodsReceiptLine
├── domain.dto      PurchaseOrderDto, PurchaseOrderSummaryDto, CreatePurchaseOrderRequest,
│                   UpdatePurchaseOrderRequest, PurchaseOrderLineDto, AddPurchaseOrderLineRequest,
│                   UpdatePurchaseOrderLineRequest, PlaceOrderRequest,
│                   GoodsReceiptDto, GoodsReceiptSummaryDto, CreateGoodsReceiptRequest,
│                   GoodsReceiptLineDto, ReceiveGoodsReceiptRequest, VoidRequest,
│                   StockReceivedPayload (the outbox payload record, D-8), …
├── domain.enums    PurchaseOrderStatus (DRAFT|ORDERED|PARTIALLY_RECEIVED|RECEIVED|CLOSED|VOID),
│                   GoodsReceiptStatus (DRAFT|RECEIVED|VOID)
├── repository      PurchaseOrderRepository, PurchaseOrderLineRepository,
│                   GoodsReceiptRepository, GoodsReceiptLineRepository
└── service         PurchaseOrderService(+Impl), GoodsReceiptService(+Impl),
                    PurchaseNumberGenerator (D-6, via code_sequence — two entity_kinds),
                    PurchaseOrderTotalsCalculator (D-5, line × cost roll-up),
                    OutstandingTracker (D-3, the receive-vs-restore on PO-line received_qty)
```

**Why `purchases`, not `procurement` / `purchasing`:** the requirements file, the vocabulary, and the owner all say
"purchase / purchase order / goods receipt" (purchases.md §3). `purchases` is the durable noun and matches the
module-named-for-its-dominant-noun precedent (ADR-0007 D-1, ADR-0008 D-1, ADR-0010 D-1). Both documents live in one
module because they share an enforcement spine (tenant predicate, `code_sequence` numbering, `ScopeGuard`
company-consistency, snapshot-on-line, lifecycle guards) and are tightly coupled (a GR cannot exist without its PO,
FR-PURCH-01b / §9 assumption). Controllers stay flat in `com.erp.api` — `PurchaseOrderController` and
`GoodsReceiptController` — and touch only services (PROJECT-CONVENTIONS §2; `ModuleBoundaryTest`).

> **Boundary note for `ModuleBoundaryTest` (the headline boundary statement of this ADR).** Purchases creates
> **no module→module entity edge**:
> - **It reads Supplier and Product via DTO only** — exactly the boundary Sales uses (ADR-0008 D-1, anticipated by
>   ADR-0006 "Purchases reads `suppliers`"). The supplier (branch-associated, same-company, not-archived) comes
>   through **`SupplierService`** returning `parties.domain.dto.SupplierDto`; the product (stockable, branch,
>   same-company, base unit, bulk-pack factor) and the unit come through **`ProductService`** /
>   `UnitOfMeasureService` returning `products.domain.dto` — never `parties.*.entity` / `products.*.entity` or their
>   repositories. The cross-module references Purchases persists are **scalar `Long` columns** (`supplier_id`,
>   `product_id`, `unit_id`) with real DB FKs — the same SQL-only-FK / no-cross-module-`@ManyToOne` convention
>   `sales_invoice_lines.product_id` and `agents.app_user_id` already use.
> - **It emits the outbox by depending on the `platform.events.OutboxPublisher` interface** (a platform primitive on
>   the `ModuleBoundaryTest` allow-list alongside `platform.security`/`audit`/`common`, added by ADR-0009 D-1) and
>   calling `publish(...)` inside its own receive/void transaction. **Purchases does not depend on Stock** — it
>   publishes a `STOCK.RECEIVED` row and is done; Stock's `GoodsReceiptStockHandler` (a `DomainEventHandler` bean the
>   dispatcher discovers by DI) consumes it. The thing that flows is the `DomainEvent` (a plain row: event-type
>   string + a JSONB payload of scalars and uids), **not** a Stock entity.
> - **No new boundary allow-list entry is required** — `platform.events` was added by ADR-0009; Products/Parties DTO
>   reads are already permitted (Sales established the precedent). The one new ArchUnit-relevant fact is that
>   `purchases` depends on `platform.events.OutboxPublisher` — a platform interface, allowed.

### D-2 — Four tables: `purchase_orders` (header) + `purchase_order_lines` (child) + `goods_receipts` (header, FK→PO) + `goods_receipt_lines` (child, FK→PO line)

Two header→lines documents, all plural per the shipped convention. Each header extends the `UidEntity` shape; the
children carry their own `uid` (they are API-addressable child records — add/edit/remove a PO line, enter a GR line —
the same reasoning that gives `sales_invoice_lines` rows a uid, ADR-0008 D-2). Every row of all four tables carries
**`company_id` + `branch_id`** (NFR-PURCH-01, BR-PURCH-01) and participates in the §3.2 tenant predicate;
`company_id`/`branch_id` are **denormalised onto the child tables** (set-once from the header, immutable) so the
tenant predicate filters every table without a join — the same discipline ADR-0008 D-2 used for
`sales_invoice_lines`. A GR is at the **same company and branch as its PO** (BR-PURCH-01), enforced in the service
(D-9).

#### `purchase_orders` (header)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | NO | internal FK target |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_purchase_order_uid`; URLs address by uid |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope (BR-PURCH-01); **never updated** |
| `branch_id` | `BIGINT` | NO | FK → `branches(id)`; the branch the PO was raised at (BR-PURCH-01); **never updated** |
| `order_number` | `VARCHAR(30)` | YES | `PO-####`, assigned **at order-placement** (D-6); NULL while DRAFT |
| `status` | `VARCHAR(20)` | NO | enum `DRAFT`\|`ORDERED`\|`PARTIALLY_RECEIVED`\|`RECEIVED`\|`CLOSED`\|`VOID`; DEFAULT `'DRAFT'`; CHECK below (FR-PURCH-02a) |
| `supplier_id` | `BIGINT` | NO | FK → `suppliers(id)` (scalar; branch-associated, same-company, not-archived — service guard D-9, BR-PURCH-02) |
| `supplier_code` | `VARCHAR(20)` | NO | **snapshot** of the supplier code at order-placement (the PO prints what was ordered even if the supplier is later renamed/archived) |
| `supplier_name` | `VARCHAR(200)` | NO | **snapshot** of the supplier name at order-placement |
| `currency` | `VARCHAR(3)` | NO | the **document currency** (ISO 4217); every `Money` on the PO is in this currency (BR-PURCH-04, BR-CUR-07); = company base in practice |
| `order_total_amount` | `NUMERIC(19,4)` | NO | computed roll-up: Σ line totals (D-5); DEFAULT 0; CHECK `>= 0` |
| `expected_date` | `DATE` | YES | optional expected-delivery date (operational convenience; no v1 rule rides on it) |
| `ordered_at` | `TIMESTAMPTZ` | YES | set at order-placement (DRAFT → ORDERED) |
| `ordered_by` | `BIGINT` | YES | FK → `app_users(id)`; the operator who placed the order |
| `closed_at` | `TIMESTAMPTZ` | YES | set on CLOSED |
| `closed_by` | `BIGINT` | YES | FK → `app_users(id)` |
| `voided_at` | `TIMESTAMPTZ` | YES | set on VOID |
| `voided_by` | `BIGINT` | YES | FK → `app_users(id)` |
| `void_reason` | `VARCHAR(255)` | YES | captured on void (mirrors Sales) |
| `notes` | `VARCHAR(500)` | YES | free-text PO note |
| `version` | `BIGINT` | NO | optimistic lock; DEFAULT 0 |
| `created_at` / `created_by` / `updated_at` / `updated_by` | `TIMESTAMPTZ` / `BIGINT` | mixed | standard audit columns (`*_by` → `app_users.id`) |

**Constraints on `purchase_orders`:**
- `uq_purchase_order_uid UNIQUE (uid)`.
- **`uq_purchase_order_company_number UNIQUE (company_id, order_number)`** — `PO-####` unique per company
  (BR-PURCH-07). Postgres treats NULLs as distinct, so the many DRAFT rows (NULL number) coexist; the constraint
  bites only once a number is assigned. The backstop for D-6's generator, exactly as `uq_sales_invoice_company_number`
  backstops the Sales generator (ADR-0008 D-2).
- `fk_purchase_order_company`, `fk_purchase_order_branch`, `fk_purchase_order_supplier` (→ `suppliers`),
  `fk_purchase_order_ordered_by`/`_closed_by`/`_voided_by` (→ `app_users`).
- `chk_purchase_order_status CHECK (status IN ('DRAFT','ORDERED','PARTIALLY_RECEIVED','RECEIVED','CLOSED','VOID'))`.
- **`chk_purchase_order_number_when_ordered CHECK ((status = 'DRAFT' AND order_number IS NULL) OR (status <> 'DRAFT' AND order_number IS NOT NULL))`**
  — a DRAFT has no number; every non-DRAFT state (ORDERED/PARTIALLY_RECEIVED/RECEIVED/CLOSED/VOID) has one (a voided
  PO keeps its number — void ≠ delete). Single-row-expressible, so it lands at the DB (mirrors
  `chk_sales_invoice_number_when_finalised`).
- `chk_purchase_order_total_nonneg CHECK (order_total_amount >= 0)`.

#### `purchase_order_lines` (child of `purchase_orders`)

One ordered product on the PO (FR-PURCH-04). The **received quantity is maintained on the line** (D-3) — outstanding
is derived from it cheaply; the cost is snapshotted at order-placement (the PO freezes its ordered lines,
BR-PURCH-05).

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | NO | internal key (the GR line FKs to this) |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_purchase_order_line_uid`; lines are uid-addressed child records |
| `purchase_order_id` | `BIGINT` | NO | FK → `purchase_orders(id)`; the owning header |
| `company_id` | `BIGINT` | NO | denormalised from header (tenant predicate, set-once-immutable) |
| `branch_id` | `BIGINT` | NO | denormalised from header (tenant predicate, set-once-immutable) |
| `line_no` | `SMALLINT` | NO | 1-based ordinal for stable display/print order; `uq_purchase_order_line_no` per PO |
| `product_id` | `BIGINT` | NO | FK → `products(id)` (scalar; branch-associated, same-company, not-archived checked at add-time, service, D-9; **may be non-stockable** — ordered but moves no stock, BR-PURCH-03) |
| `product_code` | `VARCHAR(20)` | NO | **snapshot** of the product code at order-placement |
| `product_name` | `VARCHAR(200)` | NO | **snapshot** of the product name at order-placement |
| `unit_id` | `BIGINT` | NO | FK → `units_of_measure(id)` (scalar); the unit the ordered quantity is expressed in (base or a bulk pack, FR-PURCH-04) |
| `unit_name` | `VARCHAR(60)` | NO | **snapshot** of the unit name at order-placement |
| `ordered_qty` | `NUMERIC(19,6)` | NO | ordered quantity in `unit_id` units; CHECK `> 0`; scale 6 (ADR-0007 D-3) |
| `ordered_qty_in_base` | `NUMERIC(19,6)` | NO | `ordered_qty` converted to the product's base unit (× bulk-pack factor, FR-PROD-06) — **snapshotted** so receipt math and the `STOCK.RECEIVED` payload need no live conversion; CHECK `> 0` |
| `received_qty_in_base` | `NUMERIC(19,6)` | NO | **maintained** cumulative received quantity in **base units** across this line's non-void GR lines (D-3); DEFAULT 0; CHECK `>= 0`; outstanding = `ordered_qty_in_base − received_qty_in_base` (derived, D-3) |
| `unit_cost_amount` | `NUMERIC(19,4)` | NO | the unit cost per `unit_id` (a `Money` amount, ADR-0005); **required** on a goods line (OQ-PURCH-04); CHECK `>= 0` (zero only for a free/sample line with a reason — service, D-5) |
| `line_total_amount` | `NUMERIC(19,4)` | NO | computed: `unit_cost_amount × ordered_qty` (D-5); CHECK `>= 0` |
| `currency` | `VARCHAR(3)` | NO | document currency, denormalised from header (the `Money` embeddable currency; service asserts = header currency, BR-PURCH-04/BR-CUR-07) |
| `created_at` / `created_by` / `updated_at` / `updated_by` | `TIMESTAMPTZ` / `BIGINT` | mixed | audit columns |

**Constraints on `purchase_order_lines`:**
- `uq_purchase_order_line_uid UNIQUE (uid)`.
- `uq_purchase_order_line_no UNIQUE (purchase_order_id, line_no)` — stable ordinal per PO.
- `fk_purchase_order_line_order` (→ `purchase_orders`), `fk_purchase_order_line_product` (→ `products`),
  `fk_purchase_order_line_unit` (→ `units_of_measure`), `fk_purchase_order_line_company` (→ `companies`),
  `fk_purchase_order_line_branch` (→ `branches`).
- `chk_purchase_order_line_qty CHECK (ordered_qty > 0 AND ordered_qty_in_base > 0)`.
- **`chk_purchase_order_line_received CHECK (received_qty_in_base >= 0 AND received_qty_in_base <= ordered_qty_in_base)`**
  — the **outstanding-never-negative / no-over-receipt DB backstop** (BR-PURCH-10, NFR-PURCH-07): a single-row CHECK
  comparing two columns *on the same row* is expressible at the DB, so it lands there. The service does the
  per-receipt validation against the live outstanding (D-3); this CHECK is the structural guarantee that no code path
  can drive `received` past `ordered` (a defence the Sales totals had no equivalent of, earned here by the
  cross-document quantity invariant).
- `chk_purchase_order_line_cost CHECK (unit_cost_amount >= 0 AND line_total_amount >= 0)`.

> **Why snapshot `product_code`/`product_name`/`unit_name`/`unit_cost` onto the PO line, and `ordered_qty_in_base`:**
> an ORDERED PO is a frozen commitment (BR-PURCH-05). FK-ing only to the live product and re-reading its name/cost
> would let a later rename/re-price silently mutate what the PO appears to say. Snapshotting the human-facing facts +
> the base-unit quantity at order-placement makes the document honest and makes the receipt math + the
> `STOCK.RECEIVED` payload need no live conversion (the same line-snapshot discipline ADR-0008 D-2 justified). The
> `product_id`/`unit_id` FKs are kept **as well** (for joins, the event, reporting); the printed truth lives on the
> line.

#### `goods_receipts` (header, FK → PO)

The receiving document, recorded **against a PO** (FR-PURCH-01b, §9 assumption — there is no receipt-without-order
path in v1). It inherits supplier + company + branch from its PO (BR-PURCH-01/02).

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | NO | internal FK target (the GR line FKs to this) |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_goods_receipt_uid`; URLs address by uid; **the `receiptUid` in the `STOCK.RECEIVED` payload** (D-8) |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope (BR-PURCH-01; = the PO's company); **never updated** |
| `branch_id` | `BIGINT` | NO | FK → `branches(id)`; the branch goods were received at (BR-PURCH-01; = the PO's branch); **never updated** |
| `purchase_order_id` | `BIGINT` | NO | FK → `purchase_orders(id)`; the PO this GR receives against (FR-PURCH-01b) |
| `receipt_number` | `VARCHAR(30)` | YES | `GRN-####`, assigned **at receive** (D-6); NULL while DRAFT |
| `status` | `VARCHAR(20)` | NO | enum `DRAFT`\|`RECEIVED`\|`VOID`; DEFAULT `'DRAFT'`; CHECK below (FR-PURCH-02b) |
| `supplier_id` | `BIGINT` | NO | FK → `suppliers(id)` (scalar; **inherited from the PO**, denormalised for the receipt record and reporting; BR-PURCH-02) |
| `received_at` | `TIMESTAMPTZ` | YES | set at receive (DRAFT → RECEIVED); **the `receivedAt` in the `STOCK.RECEIVED` payload** (D-8) |
| `received_by` | `BIGINT` | YES | FK → `app_users(id)`; the storekeeper who received |
| `voided_at` | `TIMESTAMPTZ` | YES | set on VOID |
| `voided_by` | `BIGINT` | YES | FK → `app_users(id)` |
| `void_reason` | `VARCHAR(255)` | YES | captured on void (FR-PURCH-09) |
| `notes` | `VARCHAR(500)` | YES | free-text GR note (e.g. delivery-note reference) |
| `version` | `BIGINT` | NO | optimistic lock; DEFAULT 0 |
| `created_at` / `created_by` / `updated_at` / `updated_by` | `TIMESTAMPTZ` / `BIGINT` | mixed | standard audit columns |

**Constraints on `goods_receipts`:**
- `uq_goods_receipt_uid UNIQUE (uid)`.
- **`uq_goods_receipt_company_number UNIQUE (company_id, receipt_number)`** — `GRN-####` unique per company
  (BR-PURCH-07); NULLs distinct → DRAFT rows coexist; the backstop for D-6's generator.
- `fk_goods_receipt_company`, `fk_goods_receipt_branch`, `fk_goods_receipt_order` (→ `purchase_orders`),
  `fk_goods_receipt_supplier` (→ `suppliers`), `fk_goods_receipt_received_by`/`_voided_by` (→ `app_users`).
- `chk_goods_receipt_status CHECK (status IN ('DRAFT','RECEIVED','VOID'))`.
- **`chk_goods_receipt_number_when_received CHECK ((status = 'DRAFT' AND receipt_number IS NULL) OR (status IN ('RECEIVED','VOID') AND receipt_number IS NOT NULL))`**
  — a DRAFT has no number; a RECEIVED/VOID GR always has one (a voided GR keeps its number, FR-PURCH-09). Mirrors the
  Sales pattern.

#### `goods_receipt_lines` (child of `goods_receipts`, FK → PO line)

One received product on a GR — a draw-down of a specific PO line's outstanding (FR-PURCH-07). It inherits the unit
cost from its PO line (FR-PURCH-05) and snapshots `qty_in_base` for the stock effect.

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | NO | internal key |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_goods_receipt_line_uid`; lines are uid-addressed child records |
| `goods_receipt_id` | `BIGINT` | NO | FK → `goods_receipts(id)`; the owning header |
| `purchase_order_line_id` | `BIGINT` | NO | FK → `purchase_order_lines(id)`; **the PO line this draws down** (FR-PURCH-07); the line whose `received_qty_in_base` this updates (D-3) |
| `company_id` | `BIGINT` | NO | denormalised from header (tenant predicate, set-once-immutable) |
| `branch_id` | `BIGINT` | NO | denormalised from header (tenant predicate, set-once-immutable) |
| `line_no` | `SMALLINT` | NO | 1-based ordinal; `uq_goods_receipt_line_no` per GR |
| `product_id` | `BIGINT` | NO | FK → `products(id)` (scalar; = the PO line's product; the product whose stock this receipt moves) |
| `product_code` | `VARCHAR(20)` | NO | **snapshot** (carried from the PO line at GR entry) |
| `product_name` | `VARCHAR(200)` | NO | **snapshot** |
| `unit_id` | `BIGINT` | NO | FK → `units_of_measure(id)` (scalar); the unit the received quantity is expressed in (the PO line's unit) |
| `unit_name` | `VARCHAR(60)` | NO | **snapshot** |
| `received_qty` | `NUMERIC(19,6)` | NO | received quantity in `unit_id` units; CHECK `> 0` (a GR line records an actual receipt; a zero line is omitted) |
| `qty_in_base` | `NUMERIC(19,6)` | NO | `received_qty` converted to the product's base unit — **snapshotted** so the `STOCK.RECEIVED` payload carries `qtyInBase` directly (D-8); CHECK `> 0`; this is the value added to the PO line's `received_qty_in_base` (D-3) |
| `unit_cost_amount` | `NUMERIC(19,4)` | NO | **inherited** from the PO line (FR-PURCH-05); recorded for the receipt cost record; CHECK `>= 0` |
| `line_cost_amount` | `NUMERIC(19,4)` | NO | computed: `unit_cost_amount × received_qty` (the received cost, for the record — not valued into stock, BR-PURCH-09); CHECK `>= 0` |
| `currency` | `VARCHAR(3)` | NO | document currency, denormalised from the PO/header |
| `created_at` / `created_by` | `TIMESTAMPTZ` / `BIGINT` | mixed | append-only audit columns; a GR line is entered then frozen at receive — **no `updated_*`** after receive (BR-PURCH-05; while DRAFT the service replaces lines rather than tracking edits, mirroring add/remove) |

**Constraints on `goods_receipt_lines`:**
- `uq_goods_receipt_line_uid UNIQUE (uid)`.
- `uq_goods_receipt_line_no UNIQUE (goods_receipt_id, line_no)`.
- `fk_goods_receipt_line_receipt` (→ `goods_receipts`), **`fk_goods_receipt_line_po_line` (→ `purchase_order_lines`)**,
  `fk_goods_receipt_line_product` (→ `products`), `fk_goods_receipt_line_unit` (→ `units_of_measure`),
  `fk_goods_receipt_line_company` (→ `companies`), `fk_goods_receipt_line_branch` (→ `branches`).
- `chk_goods_receipt_line_qty CHECK (received_qty > 0 AND qty_in_base > 0)`.
- `chk_goods_receipt_line_cost CHECK (unit_cost_amount >= 0 AND line_cost_amount >= 0)`.

> **Why the GR line FKs to the PO line, not just the product:** the partial-receipt match is **per PO line**
> (FR-PURCH-07 — "a received quantity against a specific PO line"). A GR line draws down a particular PO line's
> outstanding; FK-ing to `purchase_order_lines.id` makes the draw-down unambiguous (two PO lines could name the same
> product at different costs), makes the outstanding update a keyed write (D-3), and makes "what receipts hit this PO
> line" a cheap query. This is the structural expression of the order-to-receipt match.

### D-3 — Outstanding tracking: a **maintained `received_qty_in_base` on the PO line** (outstanding derived); updated in the same TX as the receive/void

Each PO line carries a **maintained** `received_qty_in_base` (D-2); **outstanding = `ordered_qty_in_base −
received_qty_in_base`** (derived, computed on the DTO, not stored). On a GR receive, the service adds each GR line's
`qty_in_base` to its PO line's `received_qty_in_base`; on a GR void, it subtracts them back (restoring outstanding —
FR-PURCH-09, BR-PURCH-10). This update happens **in the same transaction** as the receive/void, under the PO line's
optimistic `version` (NFR-PURCH-07 — no lost update under two concurrent receipts against the same PO line; the
losing TX retries-once and re-validates outstanding).

**Why maintained `received_qty`, not derived-on-read (the recommendation):**
1. **Outstanding is read on every GR draft** — to validate each line against the live outstanding (BR-PURCH-10) and
   to show the receiver "what's left". A maintained column makes this an O(1) read of the PO line; a derived
   `Σ qty_in_base FROM goods_receipt_lines WHERE po_line=… AND gr.status<>'VOID'` is an aggregate join over the GR
   lines on every validation — the same NFR-PURCH-07/NFR-STOCK-08 read-cost argument the Stock on-hand row (ADR-0010
   D-2) and the Sales totals (ADR-0008 D-4) resolved in favour of a maintained projection.
2. **The PO's `PARTIALLY_RECEIVED`/`RECEIVED` state is computed from it cheaply** (D-6): "all lines fully received"
   = `NOT EXISTS (line WHERE received_qty_in_base < ordered_qty_in_base)` — a maintained column makes this a cheap
   check at the end of each receive; a derived sum would re-aggregate per line.
3. **The `chk_purchase_order_line_received` CHECK (D-2) is the structural backstop** — `received <= ordered` is a
   single-row CHECK that no code path can violate, so the maintained column cannot silently exceed ordered. The
   cost — the maintained column could diverge from Σ its GR lines if a code path updated one without the other — is
   contained by (a) the **single posting path** (only `OutstandingTracker.applyReceipt(...)` / `reverseReceipt(...)`
   touch it, in the same TX as the GR line write), (b) the CHECK, and (c) an **integration test asserting
   `po_line.received_qty_in_base == Σ its non-void GR lines' qty_in_base` after every receive/void** (NFR-PURCH-07) —
   the same maintained-projection-kept-consistent-in-one-TX-and-test-pinned discipline used throughout.

> The derived-on-read alternative (no `received_qty` column; recompute from GR lines) keeps a single source of truth
> and cannot diverge, but loses the O(1) outstanding read and the cheap PO-state check, and makes every GR-line
> validation an aggregate scan. Given the read frequency (every draft GR line) and the precedent (Stock/Sales both
> chose maintained), **maintained is recommended**; the CHECK + same-TX update + test-pin contain its only risk.

### D-4 — Two documents, the order-to-receipt match: where partial-vs-full RECEIVED is computed

The two documents and one match (purchases.md §3.1): a PO line's outstanding (D-3) is the link; it drives the PO's
RECEIVED/CLOSED state. **Where the match state is computed — at the end of each GR receive/void, in the service, in
the same TX:**
- After `OutstandingTracker.applyReceipt(...)` has updated the touched PO lines' `received_qty_in_base`, the
  `PurchaseOrderService` recomputes the PO status:
  - **all lines fully received** (every line `received_qty_in_base == ordered_qty_in_base`) → PO → `RECEIVED`;
  - **some received, some outstanding** (at least one line with `received > 0` and at least one line with
    `received < ordered`, or any line partially received) → PO → `PARTIALLY_RECEIVED`;
  - **nothing yet received** → PO stays `ORDERED`.
- On a GR **void**, after `reverseReceipt(...)` subtracts the quantities back, the PO status is **recomputed the same
  way** (a void can move a PO from `RECEIVED` back to `PARTIALLY_RECEIVED`, or from `PARTIALLY_RECEIVED` back to
  `ORDERED` if it was the only receipt — FR-PURCH-09 "the PO can be re-received").
- **`CLOSED` is a separate, explicit operator action** (FR-PURCH-02a — "no further receipts expected"), not an
  automatic transition: an operator may close a PO that is `RECEIVED` (fully received, tidy completion) **or**
  `PARTIALLY_RECEIVED` (the supplier will not deliver the rest — accept the shortfall and stop expecting it). A
  `CLOSED` PO accepts no further GRs (D-6). This is a permissioned act (D-11).

This computation lives in the **service** (cross-row over the PO's lines — a per-row CHECK cannot express "all lines
of this PO are fully received"); it is the Purchases equivalent of the Sales finalise's totals recompute (ADR-0008
D-4), and it is **test-pinned** (a receipt that completes the last line moves the PO to RECEIVED; a partial leaves
PARTIALLY_RECEIVED; a void restores the prior state).

### D-5 — Cost: recorded on the line (`Money`), totalled per document, NOT valued into stock; required on a goods line

- **Every PO/GR line carries a `Money` unit cost** (`unit_cost_amount` + `currency`, ADR-0005; D-2). The PO line
  total = `unit_cost_amount × ordered_qty`; the PO `order_total_amount` = Σ line totals (computed by
  `PurchaseOrderTotalsCalculator`, service-computed-and-stored — the same single-Java-source discipline as the Sales
  totals, ADR-0008 D-4, so any future cost-bearing report mirrors one calculator). The GR line carries the inherited
  unit cost + a `line_cost_amount` (received cost, for the record).
- **Cost is required on a goods line** (OQ-PURCH-04, FR-PURCH-05): the service rejects a missing cost; a **zero** cost
  is allowed **only** for a free/sample line **with a reason** (a `note` on the PO line carrying the justification —
  the `unit_cost_amount >= 0` CHECK permits zero, the service enforces "zero requires a reason"). This is a
  cross-field/conditional rule, so it lives in the service (D-9).
- **Cost is NOT valued into stock (RATIFIED, BR-PURCH-09, FR-PURCH-13).** The `STOCK.RECEIVED` payload carries **only
  quantity** (`qtyInBase`) — Stock has no money column (ADR-0010 D-9), posts a quantity-only `GOODS_RECEIPT`
  movement, and computes no valuation. The cost lives on the Purchases line **for the record and the future
  valuation/AP rounds** (NFR-PURCH-05). **No VAT is computed on purchases in v1** (OQ-PURCH-04, FR-PURCH-13) — there
  is no `vat_status`/`vat_rate`/`vat_amount` on a purchase line (unlike Sales): input-VAT recovery is a Finance/AP
  concern, deferred. The model does not **preclude** later cost-into-valuation or a purchase-VAT leg (both are
  additive — a nullable cost on the Stock movement, ADR-0010's reserved column; a VAT column set on the purchase
  line) — but neither is built.
- **One document currency** (BR-PURCH-04, BR-CUR-07): every `Money` on a PO (and its GR) shares the header `currency`;
  the service asserts every child `currency == header.currency` (cross-row, D-9). Document currency = company base
  (TZS) in practice; the foreign-currency capability is reserved (ADR-0005), not exercised.

### D-6 — Lifecycles & numbering: two status enums, transitions in the service, two `code_sequence` series

**PO status enum `DRAFT → ORDERED → PARTIALLY_RECEIVED → RECEIVED → CLOSED / VOID`** (D-2 `status` + CHECK), the
legal transitions (FR-PURCH-02a):
- `DRAFT → ORDERED` — **place the order**: in one TX, (a) `PurchaseOrderTotalsCalculator` recomputes the total, (b)
  `PurchaseNumberGenerator.next(companyId, 'PURCHASE_ORDER')` allocates `PO-####`, (c) the ordered lines **freeze**
  (BR-PURCH-05), (d) `status='ORDERED'`, `ordered_at/by`, `order_number` set, (e) the `PURCHASE.ORDER.PLACE` audit
  row (D-12). **No stock moves** (a PO is intent). Requires `PURCHASE.ORDER.CREATE` (the create-and-place permission,
  D-11).
- `ORDERED → PARTIALLY_RECEIVED → RECEIVED` — driven by GR receives (D-4), computed at the end of each receive; not a
  direct operator transition.
- `{ORDERED, PARTIALLY_RECEIVED, RECEIVED} → CLOSED` — **close the order** (D-4): an explicit operator act; a CLOSED
  PO accepts no further GRs. Requires `PURCHASE.ORDER.CLOSE` (folded into `PURCHASE.ORDER.CREATE` for v1 unless the
  owner wants it split, D-11).
- `{DRAFT, ORDERED, PARTIALLY_RECEIVED} → VOID` — **void the PO** (FR-PURCH-02a). A DRAFT may instead be **hard-
  deleted** (it consumed no number). Voiding an ORDERED/partially-received PO is permissioned and audited; **a PO with
  any non-void GR cannot be voided** (void the GRs first — service guard, so the order-to-receipt match stays
  coherent). A fully `RECEIVED` PO is **not** voidable (it is complete; correct it by voiding its GRs then closing).
  Requires `PURCHASE.ORDER.VOID`.
- A DRAFT PO is freely editable (supplier/lines/costs); a non-DRAFT PO's ordered lines are immutable (BR-PURCH-05) —
  the service rejects line/cost mutation on a non-DRAFT PO; the optimistic `version` + the number CHECK backstop it.

**GR status enum `DRAFT → RECEIVED → VOID`** (D-2 `status` + CHECK), the legal transitions (FR-PURCH-02b):
- `DRAFT → RECEIVED` — **receive (finalise)** the goods: in one TX, (a) validate each GR line's `qty_in_base` ≤ its PO
  line's live outstanding (BR-PURCH-10, D-3) — **over-receipt rejected**, (b) allocate `GRN-####` via
  `PurchaseNumberGenerator.next(companyId, 'GOODS_RECEIPT')`, (c) `OutstandingTracker.applyReceipt(...)` updates each
  touched PO line's `received_qty_in_base` (D-3), (d) recompute the PO status (D-4), (e) `status='RECEIVED'`,
  `received_at/by`, `receipt_number` set, (f) **emit `STOCK.RECEIVED`** via `OutboxPublisher.publish(...)` (D-8), (g)
  the `PURCHASE.GOODS_RECEIPT.RECEIVE` audit row (D-12). Requires `PURCHASE.RECEIVE`. The GR (and its PO it draws
  against) must be `ORDERED`/`PARTIALLY_RECEIVED` (not DRAFT/CLOSED/VOID) — service guard.
- `RECEIVED → VOID` — **void the receipt** (FR-PURCH-09): in one TX, (a) `status='VOID'`, `voided_at/by`,
  `void_reason`, (b) `OutstandingTracker.reverseReceipt(...)` subtracts each line's `qty_in_base` back (restoring PO
  outstanding), (c) recompute the PO status (D-4), (d) **emit the compensating event `STOCK.RECEIPT.VOIDED`** (D-8),
  (e) the `PURCHASE.GOODS_RECEIPT.VOID` audit row. **The GR and its number are retained** (void ≠ delete,
  FR-PURCH-09). Requires `PURCHASE.VOID`.
- A DRAFT GR is freely editable (its lines) or hard-deleted; a RECEIVED GR is immutable except the void (BR-PURCH-05).

**Numbering — two per-company series via the generic `code_sequence`, at the lifecycle transition (BR-PURCH-07,
FR-PURCH-12, OQ-PURCH-03 RESOLVED).** Purchases reuses the shipped `code_sequence` primitive (ADR-0007 D-6) with
**two new `entity_kind`s, minting no new counter**:
- `entity_kind = 'PURCHASE_ORDER'` → `PO-%04d` (`PO-####`), allocated **at order-placement** (DRAFT → ORDERED).
- `entity_kind = 'GOODS_RECEIPT'` → `GRN-%04d` (`GRN-####`), allocated **at receive** (DRAFT → RECEIVED).

`PurchaseNumberGenerator.next(companyId, entityKind)` does `SELECT … FOR UPDATE` on the `code_sequence` row for
`(company_id, entityKind)` (creating it with `next_value = 1` on first use), formats, increments, writes back —
**inside the place/receive transaction**. The row lock serialises concurrent placements/receives for the same company
(NFR-PURCH-04 — two officers, two storekeepers, get distinct numbers); different companies don't contend. The
`uq_*_company_number` constraints (D-2) backstop any generator bug into a constraint violation. This is the
**identical mechanism** ADR-0007 D-6 / ADR-0008 D-7 shipped; drafts hold a NULL number and consume none.

### D-7 — Purchases is the second outbox producer: finalising a GR emits `STOCK.RECEIVED`; a GR void emits `STOCK.RECEIPT.VOIDED` — inside the receive/void TX

Purchases is a **producer** (ADR-0009 D-3), the mirror of Sales' `SALE.FINALISED`/`SALE.VOIDED` emit (ADR-0008 D-9,
closed by ADR-0009 D-3). The GR service calls `OutboxPublisher.publish(...)` **inside its own receive/void
`@Transactional` method**, so the `domain_events` row commits **iff** the GR receive/void commits (the atomicity
invariant — ADR-0009 D-3; **never** `REQUIRES_NEW`). It depends only on the `platform.events.OutboxPublisher`
interface (D-1 boundary); it knows nothing of Stock.

- **On receive (`DRAFT → RECEIVED`, D-6 step f):**
  `outbox.publish(DomainEventType.STOCK_RECEIVED, DomainEventType.AGG_GOODS_RECEIPT, gr.getId(), gr.getUid(),
  gr.getCompanyId(), gr.getBranchId(), payload)` where `payload` is the `StockReceivedPayload` (D-8). One event per
  GR receive (a partial receipt emits its own event with its own `gr.uid` — FR-PURCH-08; Stock posts an idempotent
  `GOODS_RECEIPT` movement per line, ADR-0010 D-5(3)).
- **On void (`RECEIVED → VOID`, D-6 step d):**
  `outbox.publish(DomainEventType.STOCK_RECEIPT_VOIDED, DomainEventType.AGG_GOODS_RECEIPT, gr.getId(), gr.getUid(),
  …, payload)` carrying the same `receiptUid` so Stock reverses **from its own ledger** by `source_document_uid =
  receiptUid` (ADR-0010 D-5 note, lines 383–385 — the same reverse-from-the-ledger discipline as a sale void: post
  opposite-sign movements for exactly what the receipt's `GOODS_RECEIPT` movements added; if nothing matches, record
  an anomaly, no phantom negative).

**New `DomainEventType` constants** (registered in `platform.events.DomainEventType`, ADR-0009 D-3 — "new event types
are added here under their owning module's ADR"):
```
STOCK_RECEIVED         = "STOCK.RECEIVED"          // GR receive (Purchases) → Stock GOODS_RECEIPT
STOCK_RECEIPT_VOIDED   = "STOCK.RECEIPT.VOIDED"    // GR void (Purchases)    → Stock reverse-from-ledger
AGG_GOODS_RECEIPT      = "GOODS_RECEIPT"           // aggregate type (already named by ADR-0009 D-3 for the GR)
```
> **Event-name reconciliation (non-blocking).** ADR-0009 D-3 enumerated `SALE.FINALISED`/`SALE.VOIDED`/`STOCK.RECEIVED`
> as the v1 set and named `AGG_GOODS_RECEIPT`; it deferred the **GR-void compensating event** to ADR-0010/0011 (D-5
> note "Purchases emits its own compensating event … reserved for ADR-0011 to specify the event"). This ADR specifies
> it as **`STOCK.RECEIPT.VOIDED`** (the `MODULE.RESOURCE.EVENT` form, parallel to `SALE.VOIDED`). ADR-0010's
> `GoodsReceiptStockHandler` consumes `STOCK.RECEIVED`; a **second Stock handler** (`eventType()=STOCK.RECEIPT.VOIDED`,
> the reverse-from-ledger mirror of `SaleReversalStockHandler`) consumes the void — this is the symmetry ADR-0010 D-5
> reserved; its concrete handler is an additive Stock bean specified under ADR-0010's family (flagged below, a
> one-bean addition Stock's posting primitive already supports). Purchases' obligation is only to **emit** both events
> correctly inside the TX; what Stock does with them is ADR-0010's.

### D-8 — The `STOCK.RECEIVED` payload (parallel to `SALE.FINALISED`), consistent with what ADR-0010's handler reads

The payload is **fixed by what the Stock consumer reads** (ADR-0010 D-5(3), verbatim) and is **parallel to the
`SALE.FINALISED` shape** (ADR-0008 D-9). A `StockReceivedPayload` record in `purchases.domain.dto`, serialised to the
`domain_events.payload` JSONB by `OutboxPublisherImpl` (Jackson, ADR-0009 D-3):

```
STOCK.RECEIVED payload:
{
  "receiptUid":  "<goods_receipts.uid>",        // the aggregate uid; Stock's source_document_uid (ADR-0010 D-5)
  "companyId":   <company id, as JSON string>,  // the event tenant scope (Stock posts under this company)
  "branchId":    <branch id, as JSON string>,   // the branch the receipt was at
  "receivedAt":  "<ISO-8601 timestamp>",        // the GR received_at; Stock's movement occurred_at
  "lines": [
    {
      "productId":  <product id, as JSON string>,  // the line product (= PO line product)
      "productUid": "<product uid>",               // Stock resolves the ProductDto by uid (stockable check, D? — ADR-0010 D-3)
      "unitId":     <unit id, as JSON string>,     // the unit (carried for completeness/traceability)
      "qtyInBase":  "<NUMERIC(19,6) as string>"    // the goods_receipt_lines.qty_in_base — Stock adds +qtyInBase
    }
    // one entry per GR line (the lines received in THIS receipt — a partial receipt carries only its own lines)
  ]
}
```

```
STOCK.RECEIPT.VOIDED payload (the compensating event, D-7):
{
  "receiptUid": "<goods_receipts.uid>",   // same uid — Stock reverses by source_document_uid = receiptUid (ADR-0010 D-5)
  "companyId":  <…>, "branchId": <…>,
  "voidedAt":   "<ISO-8601 timestamp>"
  // NO lines needed — Stock reverses from its own ledger (ADR-0010 D-5 reverse-from-the-ledger); the receiptUid is
  // the lookup key. (Lines MAY be echoed for diagnostics but Stock does not rely on them — parallel to SALE.VOIDED.)
}
```

- **`qtyInBase` is the snapshotted `goods_receipt_lines.qty_in_base`** (D-2) — Stock needs no live unit conversion
  (the receipt froze it). It is a **string** on the wire (NUMERIC-as-string, ADR-0010 D-11 / ADR-0008 D-12 — never a
  JS double, to avoid precision loss); ids serialise as JSON strings (the global Long-as-string rule).
- **NO cost in the payload** — quantity-only into Stock (BR-PURCH-09, D-5; Stock has no money column, ADR-0010 D-9).
- **A non-stockable line** is emitted like any other (Purchases does not gate on `stockable`); Stock's handler
  **defensively skips-and-records** a non-stockable line (ADR-0010 D-3 — "Purchases should not emit it; Stock does
  not trust the producer blindly"). *(Recommended: Purchases need not filter non-stockable lines from the payload —
  Stock's skip is the single authority on what moves; filtering at the producer would duplicate the stockable check
  Stock already owns. Flagged, non-blocking.)*
- **The payload is parallel to `SALE.FINALISED`** (ADR-0008 D-9: `{ invoiceUid, companyId, branchId, finalisedAt,
  lines:[{ productId, productUid, unitId, qtyInBase }] }`) — same line shape, `receiptUid` where Sales has
  `invoiceUid`, `receivedAt` where Sales has `finalisedAt`. This parallelism is deliberate (ADR-0010's two handlers
  share a skeleton).

### D-9 — Enforcement split (DB vs service): DB enforces unconditional/single-row; service enforces cross-row/cross-document/cross-module

Consistent with ADR-0006 D-6 / ADR-0007 D-9 / ADR-0008 D-10 / ADR-0010 D-9:

| rule | enforcement | mechanism |
| --- | --- | --- |
| BR-PURCH-01 PO/GR belong to one company, at one branch; GR same company+branch as PO | **DB + service** | `company_id`/`branch_id` NOT NULL + FKs on all four tables, never updated (DB); GR inherits PO's company/branch + service asserts equality on GR create |
| FR-PURCH-02a/02b status ∈ enum | **DB CHECK** | `chk_purchase_order_status`, `chk_goods_receipt_status` |
| BR-PURCH-07 numbers unique per company; number⇔non-DRAFT | **DB + service** | `uq_*_company_number` + `chk_*_number_when_*` + `PurchaseNumberGenerator` (two `entity_kind`s) |
| FR-PURCH-04 ordered_qty > 0; received_qty > 0; costs ≥ 0 | **DB CHECK** | `chk_purchase_order_line_qty`, `chk_goods_receipt_line_qty`, the `_cost` CHECKs, header `_total_nonneg` |
| **BR-PURCH-10 over-receipt rejected; outstanding never negative** | **DB CHECK + service** | `chk_purchase_order_line_received (received_qty_in_base <= ordered_qty_in_base)` (single-row structural backstop, D-2) **and** the service validates each GR line's `qty_in_base` ≤ live outstanding **before** receive (the friendly 422; D-3/D-6) |
| FR-PURCH-07 / NFR-PURCH-07 outstanding reconciles to ordered − Σ non-void receipts | **service + test** | `OutstandingTracker` updates `received_qty_in_base` in the **same TX** as the GR receive/void, under the PO line `version`; IT asserts `received == Σ non-void GR lines` after every op |
| D-4 PO partial-vs-full RECEIVED state | **service + test** | recompute over the PO's lines at the end of each receive/void (cross-row); IT pins the transitions |
| FR-PURCH-03 / BR-PURCH-02 supplier branch-associated, same-company, not-archived | **service** | `SupplierService` DTO read at PO draft (Parties owns the facts); a GR inherits the PO's supplier (no re-check) |
| BR-PURCH-03 product branch-associated, same-company, not-archived; non-stockable allowed | **service** | `ProductService` DTO read at PO-line add (Products owns the facts); non-stockable line is allowed (no movement — Stock skips on receipt, D-8) |
| BR-PURCH-04 / BR-CUR-07 all amounts share the document currency | **service** | place/receive asserts every child `currency == header.currency` (cross-row) |
| FR-PURCH-05 / OQ-PURCH-04 cost required on a goods line; zero only with a reason | **DB CHECK + service** | `unit_cost_amount >= 0` (DB); service rejects missing cost, requires a `note` reason when cost = 0 (conditional/cross-field) |
| BR-PURCH-05 ORDERED PO lines frozen; RECEIVED GR immutable | **service + DB backstop** | service rejects mutation on a non-DRAFT PO/GR; `version` + the number CHECK backstop; GR line has no `updated_*` |
| BR-PURCH-06 receiving pushes stock in exactly once | **service + consumer** | GR receive emits one `STOCK.RECEIVED` in-TX (D-7); Stock's consumer is idempotent on `(source_event_uid, product_id)` (ADR-0010 D-4/D-6) |
| FR-PURCH-09 void restores outstanding + reverses stock | **service** | `OutstandingTracker.reverseReceipt` (in-TX) + `STOCK.RECEIPT.VOIDED` emit (D-7); GR + number retained |
| BR-PURCH-08 no payable/payment/VAT in v1 | **by design** | no AP/payment/VAT columns or tables anywhere; cost recorded only (D-5) |
| BR-PURCH-09 quantity into stock, cost not valued | **by design** | `STOCK.RECEIVED` payload carries only `qtyInBase`, no cost (D-8); no valuation computed |
| a GR is always against a PO (§9 assumption) | **DB** | `goods_receipts.purchase_order_id` NOT NULL FK (no receipt-without-order path in v1) |

**ScopeGuard addition (ADR-0002 / ADR-0007 D-10 / ADR-0008 D-10 / ADR-0010 D-10 follow-on):** `ScopeGuard.companyIdOf`
gains **two** target types so the 2-arg `@perm.scoped` gates resolve a PO uid and a GR uid to their company:
```java
case "purchaseorder" -> purchaseOrders.findCompanyIdByUid(uid);
case "goodsreceipt"  -> goodsReceipts.findCompanyIdByUid(uid);
```
backed by single-column JPQL projections on `PurchaseOrderRepository` / `GoodsReceiptRepository`
(`@Query("SELECT p.companyId FROM PurchaseOrder p WHERE p.uid = :uid")`), mirroring the existing cases (`product`,
`customer`, `agent`, `unit`, `invoice`, `stockonhand`, `stockmovement`). This adds two repository constructor
dependencies to `ScopeGuard` — the same cross-cutting-spine pattern already accepted for the product/party/sales/
stock repositories (ScopeGuard is the security spine, ArchUnit-allowed). **Not optional** — without it the
target-uid gates fail closed. PO lines and GR lines are addressed **under** their header uid in the API, so they need
no own target type (the gate resolves on the parent header uid).

> **Cross-tenant DTO resolution (the carried-in anti-regression guard).** Purchases resolves a supplier/product/unit
> from a uid in a request body to its scalar `Long` id by a **company-scoped finder** (`findByCompanyIdAndUid`-style)
> — never a bare `findByUid` — and then `ScopeGuard.assertCanActIn(principal, resolved.companyId)`; child lines are
> read by `findByUidAndParentId`-style (the line uid scoped under its header id) so a line uid from another document
> cannot be addressed. **Every read path asserts `assertCanActIn`** before returning a PO/GR (the #1 anti-regression
> guard carried in from prior modules — a read that skips it leaks cross-tenant data, a release blocker, NFR-PURCH-01).

### D-10 — `ScopeGuard` targets recap (the two new target types)

(Folded into D-9 for placement, restated for the engineer's checklist.) Add `purchaseorder` and `goodsreceipt` to
`ScopeGuard.companyIdOf` with the two repository projections above. These resolve the **header** uid; everything
addressed under a header (lines, the place/receive/void sub-actions) gates on the header's target type.

### D-11 — Permission catalogue additions (seeded in V8, module `purchases`) — mirrors `SALES.*` spelling

| code | module | description |
| --- | --- | --- |
| `PURCHASE.ORDER.VIEW` | purchases | View and list/search purchase orders (and their outstanding) |
| `PURCHASE.ORDER.CREATE` | purchases | Create and edit draft POs; add/edit/remove ordered lines; **place** the order; **close** a PO |
| `PURCHASE.ORDER.VOID` | purchases | Void a purchase order |
| `PURCHASE.GOODS_RECEIPT.VIEW` | purchases | View and list/search goods receipts |
| `PURCHASE.RECEIVE` | purchases | Create a draft GR against a PO and **receive** (finalise) it — the act that pushes stock in |
| `PURCHASE.VOID` | purchases | Void a finalised goods receipt (reverses the stock-in, restores PO outstanding) |

- **Naming mirrors the shipped `SALES.*` catalogue spelling** (ADR-0008 D-11: `SALES.INVOICE.CREATE`,
  `SALES.INVOICE.VOID`, …): dot-separated, `MODULE.RESOURCE.ACTION` / `MODULE.ACTION`. The spec names the verb set
  `PURCHASE.CREATE` / `PURCHASE.RECEIVE` / `PURCHASE.VIEW` / `PURCHASE.VOID` (purchases.md FR-PURCH-11); I qualify the
  PO-side verbs with `ORDER` and the GR-side with `GOODS_RECEIPT` so the two documents have distinct view/create
  codes (parallel to Sales qualifying with `INVOICE` to leave room for POS/SO). **`PURCHASE.RECEIVE` and
  `PURCHASE.VOID` are the spec's exact codes** for the GR receive/void (the storekeeper's and supervisor's acts,
  purchases.md §4); I keep them unqualified to match the spec verbatim. If the owner prefers fully-qualified GR codes
  (`PURCHASE.GOODS_RECEIPT.RECEIVE`/`.VOID`) or unqualified PO codes (`PURCHASE.CREATE`/`.VIEW`), it is a trivial seed
  rename before build — flagged.
- **`PURCHASE.ORDER.CLOSE` is folded into `PURCHASE.ORDER.CREATE`** for v1 (closing is a PO-management act by the same
  role that places — D-4/D-6); split additively later if the owner wants a separate closer role (flagged).
- **Seeding (V8, idempotent):** `INSERT INTO permissions (code, module, description) VALUES (...) ON CONFLICT (code)
  DO NOTHING`, then the additive `INSERT INTO role_permission SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
  WHERE r.code = 'ORG_ADMIN' AND p.module = 'purchases' ON CONFLICT DO NOTHING` — the **exact** V2/V3/V5/V7 pattern.
- **Gate shapes (ADR-0002, mirroring ADR-0008 D-11):**
  - `POST /purchase-orders` (create draft) → `@PreAuthorize("@perm.scoped(#request.companyUid, 'company', 'PURCHASE.ORDER.CREATE')")`
    (active company is the target — `companyUid` in the body, D-12).
  - `PUT /purchase-orders/uid/{uid}` and PO-line sub-resource mutations →
    `@PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.CREATE')")`.
  - `POST /purchase-orders/uid/{uid}/place` → `@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.CREATE')`.
  - `POST /purchase-orders/uid/{uid}/close` → `@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.CREATE')`.
  - `POST /purchase-orders/uid/{uid}/void` → `@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.VOID')`.
  - `GET /purchase-orders` (list/search) → `@PreAuthorize("hasAuthority('PURCHASE.ORDER.VIEW')")`, results scoped by
    the tenant predicate + active branch.
  - `POST /goods-receipts` (create draft against a PO) → `@perm.scoped(#request.purchaseOrderUid, 'purchaseorder', 'PURCHASE.RECEIVE')`
    (the GR's authority derives from the PO it receives against — resolve the PO's company).
  - `PUT /goods-receipts/uid/{uid}` (edit draft lines) → `@perm.scoped(#uid, 'goodsreceipt', 'PURCHASE.RECEIVE')`.
  - `POST /goods-receipts/uid/{uid}/receive` → `@perm.scoped(#uid, 'goodsreceipt', 'PURCHASE.RECEIVE')`.
  - `POST /goods-receipts/uid/{uid}/void` → `@perm.scoped(#uid, 'goodsreceipt', 'PURCHASE.VOID')`.
  - `GET /goods-receipts` (list/search) → `@PreAuthorize("hasAuthority('PURCHASE.GOODS_RECEIPT.VIEW')")`.

### D-12 — API / uid / companyUid discipline (mirror ADR-0008 D-12 / ADR-0010 D-11)

- **uids in URLs; ids (as JSON strings) in bodies for joins.** The PO header is addressed by uid
  (`/purchase-orders/uid/{uid}`), the GR by uid (`/goods-receipts/uid/{uid}`); lines are addressed under their header
  (`/purchase-orders/uid/{uid}/lines/{lineUid}`, `/goods-receipts/uid/{uid}/lines/{lineUid}`). Cross-module references
  in request bodies are **uids** — `supplierUid`, per PO line `productUid`/`unitUid`, and on a GR line the
  `purchaseOrderLineUid` it draws down — which the service resolves to the scalar `Long` ids it persists (the same
  resolve-uid-to-id discipline Sales uses, via **company-scoped finders**, D-9).
- **`companyUid` (String) in the PO create body** (ADR-0007/0008 D-12 convention): `CreatePurchaseOrderRequest`
  carries `companyUid`; the service resolves it and runs `ScopeGuard.assertCanActIn`. The active **branch** comes from
  `RequestContext` (the `X-Branch-Uid` header / default branch, ARCHITECTURE.md §5) — the PO is raised at the
  operator's active branch; the create body does **not** carry a `branchUid` (it would let an operator raise a PO at a
  branch they are not in — the active branch is authoritative, mirroring ADR-0008 D-12 / ADR-0010 D-11). **The GR
  create body carries `purchaseOrderUid`** (not `companyUid` — the GR inherits company/branch/supplier from its PO,
  and the active branch must equal the PO's branch — service guard, BR-PURCH-01).
- **`ApiResponse<T>` envelope** everywhere; list/search paged via `PageMeta`.
- **Money on the wire** is `{ "amount": "1500.0000", "currency": "TZS" }` with `amount` a **string** (ADR-0005 D-7),
  via the shared `MoneyDto` in `platform.common.money` (Purchases must not import `parties`/`products`/`sales`
  `MoneyDto` — boundary). Every cost field on the PO/GR DTO serialises this way.
- **Quantities serialise as strings** (`"12.000000"` — NUMERIC-as-string, ADR-0010 D-11), including
  `ordered_qty`/`received_qty`/`qty_in_base` and the **derived `outstandingQtyInBase`** on the PO-line DTO.
- **Enums on the wire:** the string name (`DRAFT`, `ORDERED`, `PARTIALLY_RECEIVED`, `RECEIVED`, `CLOSED`, `VOID`).
- **Derived read fields on the DTO** (not stored columns): per PO line `outstandingQtyInBase`
  (`ordered_qty_in_base − received_qty_in_base`) and a `fullyReceived` flag; on the PO header a `hasOutstanding`
  flag — computed in the service for the response, never persisted.

### D-13 — Audit (ADR-0004): emit points and `target_type` strings (plural table names)

Purchases' mutating service emits via the existing `AuditService.record(...)` (MANDATORY, same-TX, append-only —
NFR-PURCH-03). `target_type` strings are the **plural table names** (the shipped V2/V3/V5/V7 convention):

| action | target_type | when | detail (fact-only, ADR-0004 D-6) |
| --- | --- | --- | --- |
| `PURCHASE.ORDER.CREATE` | `purchase_orders` | on draft PO create | `supplierUid`, `currency` |
| `PURCHASE.ORDER.UPDATE` | `purchase_orders` | on draft PO edit (supplier/notes) | minimal/fact-only |
| `PURCHASE.ORDER.LINE.ADD` / `LINE.UPDATE` / `LINE.REMOVE` | `purchase_order_lines` | PO line change (DRAFT only) | `productUid`, `orderedQty`, `unitCost` |
| `PURCHASE.ORDER.PLACE` | `purchase_orders` | DRAFT → ORDERED | `orderNumber`, `orderTotal` |
| `PURCHASE.ORDER.CLOSE` | `purchase_orders` | → CLOSED | `orderNumber` |
| `PURCHASE.ORDER.VOID` | `purchase_orders` | → VOID | `orderNumber`, `voidReason` |
| `PURCHASE.GOODS_RECEIPT.CREATE` | `goods_receipts` | on draft GR create | `purchaseOrderUid` |
| `PURCHASE.GOODS_RECEIPT.RECEIVE` | `goods_receipts` | DRAFT → RECEIVED | `receiptNumber`, `purchaseOrderUid`, per-line `{productUid, qtyInBase}` |
| `PURCHASE.GOODS_RECEIPT.VOID` | `goods_receipts` | RECEIVED → VOID | `receiptNumber`, `voidReason` |

- **Place, receive, and void ARE audited** (NFR-PURCH-03 names PO create/order/void and GR create/receive/void
  explicitly).
- **The `STOCK.RECEIVED`/`STOCK.RECEIPT.VOIDED` outbox events are distinct from audit** — audit is the human/security
  trail (always written, in `audit_logs`); the outbox is the cross-module effect channel (`domain_events`). The GR
  receive/void emits **both** (the audit row AND the outbox event), in the same TX; the **outbox does not double-audit**
  (ADR-0009 D-9 — the business action audits itself, the `domain_events` row records the emission, Stock's movement
  ledger records the effect: three append-only trails, no redundant outbox audit).

### D-14 — Migration: additive `V8__purchases.sql`, never a V1–V7 edit

IAM=V1, Parties=V2, Products=V3, Units=V4, Sales=V5, **Outbox=V6** (ADR-0009), **Stock=V7** (ADR-0010) — all land
before Purchases this round. Purchases is a **new** module → purely **additive `V8__purchases.sql`**. It **must not**
edit V1–V7. Ordering within V8 (parent-before-child, mirrors ADR-0008 D-14):

1. **`purchase_orders`** (header) with FKs to `companies`, `branches`, `suppliers`, `app_users`; the status /
   number-when-ordered / total-nonneg CHECKs.
2. **`purchase_order_lines`** (child) with FKs to `purchase_orders`, `products`, `units_of_measure`, `companies`,
   `branches`; the qty / **received-≤-ordered** / cost CHECKs.
3. **`goods_receipts`** (header) with FKs to `purchase_orders`, `companies`, `branches`, `suppliers`, `app_users`;
   the status / number-when-received CHECKs.
4. **`goods_receipt_lines`** (child) with FKs to `goods_receipts`, **`purchase_order_lines`**, `products`,
   `units_of_measure`, `companies`, `branches`; the qty / cost CHECKs.
5. **Indexes** (D-15 below).
6. **Permission seed + additive ORG_ADMIN grant** (D-11).
7. **No new numbering table** (`code_sequence` exists; Purchases adds two `entity_kind` rows — `PURCHASE_ORDER`,
   `GOODS_RECEIPT` — at runtime, D-6). **No outbox table** (V6 owns it; Purchases emits via the publisher). **No FK
   into `domain_events`** (the link is the publish call). **No data seed** (documents accrue at runtime). **No
   trigger** (the outstanding maintenance is the service `OutstandingTracker`, D-3, not a DB trigger — same
   service-owns-the-invariant stance as the Sales totals / Stock on-hand).

All non-Purchases FK targets (`companies`, `branches`, `suppliers`, `products`, `units_of_measure`, `app_users`,
`code_sequence`, `roles`, `permissions`, `role_permission`) **already exist** in frozen V1–V4. V8 depends on V6's
outbox existing as **runtime infrastructure** (the GR service calls `OutboxPublisher`), but takes **no schema FK**
into it.

### D-15 — Indexes (lookup + tenant + the outstanding/receive read paths)

```
-- purchase_orders
CREATE INDEX ix_purchase_orders_company          ON purchase_orders (company_id);
CREATE INDEX ix_purchase_orders_company_branch   ON purchase_orders (company_id, branch_id);    -- active-branch list (FR-PURCH-10)
CREATE INDEX ix_purchase_orders_supplier         ON purchase_orders (supplier_id);               -- a supplier's POs
CREATE INDEX ix_purchase_orders_status           ON purchase_orders (company_id, status);        -- "outstanding POs to receive against" (ORDERED/PARTIALLY_RECEIVED filter — §7.2)
CREATE INDEX ix_purchase_orders_created_at        ON purchase_orders (company_id, created_at);    -- date-range list/report
-- (uq_purchase_order_company_number already indexes number lookup)

-- purchase_order_lines
CREATE INDEX ix_purchase_order_lines_order       ON purchase_order_lines (purchase_order_id);     -- read a PO's lines
CREATE INDEX ix_purchase_order_lines_product     ON purchase_order_lines (product_id);            -- "what we order" / future analysis
CREATE INDEX ix_purchase_order_lines_company     ON purchase_order_lines (company_id);            -- tenant-scoped reporting
-- partial index over lines still outstanding — the receive-screen "what's left" probe (D-3):
CREATE INDEX ix_purchase_order_lines_outstanding ON purchase_order_lines (purchase_order_id)
    WHERE received_qty_in_base < ordered_qty_in_base;

-- goods_receipts
CREATE INDEX ix_goods_receipts_company           ON goods_receipts (company_id);
CREATE INDEX ix_goods_receipts_company_branch    ON goods_receipts (company_id, branch_id);       -- active-branch list
CREATE INDEX ix_goods_receipts_order             ON goods_receipts (purchase_order_id);           -- "all receipts against this PO" (the match read)
CREATE INDEX ix_goods_receipts_supplier          ON goods_receipts (supplier_id);
-- (uq_goods_receipt_company_number already indexes number lookup)

-- goods_receipt_lines
CREATE INDEX ix_goods_receipt_lines_receipt      ON goods_receipt_lines (goods_receipt_id);       -- read a GR's lines
CREATE INDEX ix_goods_receipt_lines_po_line      ON goods_receipt_lines (purchase_order_line_id); -- "what was received against this PO line" (the outstanding reconciliation, NFR-PURCH-07)
CREATE INDEX ix_goods_receipt_lines_product      ON goods_receipt_lines (product_id);
CREATE INDEX ix_goods_receipt_lines_company      ON goods_receipt_lines (company_id);
```

Native SQL is permitted for heavier purchase reports (outstanding-PO ageing, received-vs-ordered summaries) where
JPQL can't express an aggregate cleanly, kept behind a clearly-named repository method (PROJECT-CONVENTIONS — native
allowed for reports/bulk).

## Consequences

**Easier / safer:**
- **The buy→stock loop is complete and the right way.** A Goods Receipt receiving writes its `STOCK.RECEIVED` event
  in the **same TX** as the receive (D-7), so the stock-in can never be silently lost nor fire for a rolled-back
  receive; Stock (ADR-0010) consumes it idempotently and posts a `GOODS_RECEIPT` movement. A GR void emits
  `STOCK.RECEIPT.VOIDED`, which Stock reverses from its ledger. Purchases is the second producer, exactly parallel to
  Sales (ADR-0008 D-9 / ADR-0009 D-3).
- **Tenant-safe, currency-safe, immutable-by-design from day one** — header + lines of both documents carry
  `company_id`/`branch_id` under the §3.2 predicate; every cost is a `Money` pair (ADR-0005); ORDERED PO lines and
  RECEIVED GRs are frozen by the lifecycle guard + the number CHECK. The retrofit traps cannot occur.
- **Partial receipts + outstanding are fast and structurally correct.** The maintained `received_qty_in_base` (D-3)
  gives an O(1) outstanding read for the receive screen; the `chk_purchase_order_line_received` CHECK makes
  over-receipt **structurally impossible** at the DB; the same-TX `OutstandingTracker` + the PO-line `version` keep
  the figure consistent under concurrency (NFR-PURCH-07), test-pinned. The PO partial-vs-full RECEIVED state is a
  cheap cross-row recompute at the end of each receive/void.
- **Numbering reuses the shipped `code_sequence`** (D-6) — two new `entity_kind`s, no new sequence table,
  concurrency-safe per-company `PO-####`/`GRN-####` at the lifecycle transition (NFR-PURCH-04); drafts consume no
  number.
- **Snapshotted lines make a placed PO / received GR an honest record** — renaming/re-pricing/archiving a
  product or supplier never mutates what a past document says.
- **Cost recorded, not valued — and not precluded.** Every line carries the `Money` cost for the record and the
  future valuation/AP rounds; the `STOCK.RECEIVED` payload carries only quantity (BR-PURCH-09); the additive paths
  (cost-into-Stock-valuation, a purchase-VAT leg, AP/payments, returns/debit notes, landed cost) are all named and
  none is precluded (NFR-PURCH-05).
- **Purchases stays decoupled** — it reads `parties.domain.dto`/`products.domain.dto` and persists scalar-id FKs;
  emits via the `platform.events.OutboxPublisher` interface; **no module→module entity edge**; two new `ScopeGuard`
  cases; two new `code_sequence` kinds; no dependency on Stock. `ModuleBoundaryTest` stays green.

**Harder / to watch:**
- **The outstanding invariant is service-owned** (D-3, NFR-PURCH-07) — `OutstandingTracker` must update
  `received_qty_in_base` in the **same TX** as the GR line write, and the void must subtract it back. **Must have**
  an integration test that, after every receive/partial-receive/void, asserts `po_line.received_qty_in_base == Σ its
  non-void GR lines' qty_in_base`, and that a void restores outstanding so the PO can be re-received. Highest-
  discipline surface in the module (the CHECK backstops over-receipt but not divergence below ordered).
- **Same-TX outbox emit is service-owned** (D-7) — `OutboxPublisher.publish` must be called **inside** the
  receive/void TX and **must not** be given `REQUIRES_NEW` (ADR-0009 D-3 invariant); else the event could commit for a
  rolled-back receive (phantom stock-in) or be lost. **Must have** an integration test: roll back a receive → no
  `domain_events` row, no `received_qty` change; commit → exactly one `STOCK.RECEIVED` with the correct payload.
- **Over-receipt rejection is two-layered** (D-9): the service gives the friendly 422 against the live outstanding
  **before** the receive; the `chk_purchase_order_line_received` CHECK is the structural backstop. Reviewers must not
  remove the CHECK assuming the service suffices, nor the service check assuming the CHECK suffices (the CHECK's error
  is a raw constraint violation; the service's is the user-facing message).
- **The GR-void compensating event is a new event type ADR-0010 must consume** (D-7) — `STOCK.RECEIPT.VOIDED` needs a
  second Stock `DomainEventHandler` (the reverse-from-ledger mirror of `SaleReversalStockHandler`). ADR-0010 D-5
  reserved the symmetry; the concrete handler is a one-bean additive Stock change. Until it exists, a GR void emits an
  event with no handler (ADR-0009 D-4's no-handler policy: marked DISPATCHED + DEBUG-logged) — so the **handler must
  ship with Stock this round** for a GR void to reverse stock (sequencing fact below).
- **`supplier_id`/`product_id`/`unit_id` are cross-module scalar references with real FKs but DTO-sourced facts** —
  the FK guarantees existence; the **service** guarantees branch-association/same-company/not-archived/stockable via
  the Parties/Products DTOs (D-9). Reviewers must not "fix" a validation by importing a Parties/Products entity.
- **`qty_in_base`/cost/`product_name` snapshots can drift from the live master** — *by design* (immutability);
  reviewers must not re-read the live product. Documented; do not normalise away (same note as ADR-0008 D-2).

**Migration / delivery cost:**
- 1 additive Flyway file (`V8__purchases.sql`): **4 new tables** (`purchase_orders`, `purchase_order_lines`,
  `goods_receipts`, `goods_receipt_lines`), their FKs/uniques/CHECKs, ~16 indexes (one partial-on-outstanding), **6
  permission rows + 1 additive ORG_ADMIN grant**. **No** new numbering table, **no** outbox table, **no** trigger,
  **no** data seed, **no** FK into V6. Depends on frozen V1–V4 (FK targets) and runtime V6 (`OutboxPublisher`).
- Backend (Purchases module): the `purchases` entity set (4 entities + DTOs + 4 repositories +
  `PurchaseOrderService`/Impl + `GoodsReceiptService`/Impl + `PurchaseNumberGenerator` + `PurchaseOrderTotalsCalculator`
  + `OutstandingTracker`); the `StockReceivedPayload`/`StockReceiptVoidedPayload` records in `purchases.domain.dto`;
  the **`OutboxPublisher` dependency** on `GoodsReceiptServiceImpl` + the two `publish` calls (receive → `STOCK.RECEIVED`,
  void → `STOCK.RECEIPT.VOIDED`); the two new `DomainEventType` constants (registered in `platform.events.DomainEventType`,
  D-7); the `ScopeGuard` `purchaseorder`/`goodsreceipt` cases (D-9) — adds two repository deps to `ScopeGuard`.
- Backend (Stock — cross-round dependency, ADR-0010's): a **second `DomainEventHandler`** for `STOCK.RECEIPT.VOIDED`
  (the reverse-from-ledger mirror, ADR-0010 D-5 reserved it). One bean; Stock's posting primitive already supports it.
- Web: a purchase-order screen (supplier pick, line add with product+unit+ordered-qty+unit-cost, live PO total,
  place/close/void) and a goods-receipt screen (pick an outstanding PO, per-line received-qty ≤ outstanding shown,
  receive/void) — reusing the Sales/Stock list + form patterns and the ADR-0005 `Money` input. Quantities + costs as
  strings.
- Deployment risk: **low** — additive migration on frozen schema; both documents start empty; the GR emits via the V6
  publisher (a runtime dependency, no schema FK). The one sequencing note: **V6 (outbox) + V7 (Stock, including the
  `STOCK.RECEIVED` handler and the `STOCK.RECEIPT.VOIDED` handler) must ship with or before V8**, or a received GR's
  event has no consumer (ADR-0009 D-4's no-handler case applies).

## Alternatives considered

- **Single-step GRN (one document that both orders and receives), no separate PO.** Simpler — one table pair, one
  lifecycle, one number, no cross-document outstanding. **Rejected (OQ-PURCH-01 RESOLVED, owner-ruled two documents):**
  the owner chose the bigger, ordering-first flow (a commitment to buy, then receipts against it) to support partial
  receipts and a record of what was ordered-but-not-received (purchases.md §3.2). A single-step GRN cannot express
  outstanding or partial receipts. The two-document model is the ratified scope; not re-litigated.
- **Outstanding derived-on-read (no `received_qty_in_base` column; recompute from GR lines).** One source of truth,
  cannot diverge. **Rejected (D-3):** outstanding is read on every draft GR line (validation + "what's left"),
  making derivation an aggregate scan per validation; the maintained column gives the O(1) read and the cheap PO-state
  check, with the `received <= ordered` CHECK + same-TX update + test-pin containing the divergence risk — the same
  maintained-projection choice Stock (ADR-0010 D-2) and Sales (ADR-0008 D-4) made. Maintained recommended.
- **A `>= 0` / no-over-receipt enforced only in the service (no DB CHECK).** Fewer constraints. **Rejected (D-2/D-9):**
  `received_qty_in_base <= ordered_qty_in_base` is a **single-row** comparison expressible at the DB, so the structural
  backstop is cheap and free — making over-receipt impossible even under a service bug. The service still does the
  friendly pre-validation; the CHECK is belt-and-braces (the DB-can't/service-must split puts what the DB *can* do at
  the DB).
- **A DB trigger to maintain `received_qty_in_base` from GR-line inserts/updates.** Keeps it in lockstep at the DB.
  **Rejected (D-3/D-14):** triggers are the hidden-logic, hard-to-test mechanism the system avoids (the Sales totals
  and Stock on-hand both chose a service primitive over a trigger, ADR-0008 D-4 / ADR-0010 D-4). The
  `OutstandingTracker` is legible, testable, the single funnel; the optimistic `version` handles concurrency without a
  trigger.
- **A separate `purchase_receipts`-without-PO path (receive goods with no order).** Matches a "goods arrived
  unexpectedly" reality. **Rejected for v1 (§9 assumption, FR-PURCH-01b):** the owner ruled a GR is **always** against
  a PO in v1 (the PO is the source of supplier, ordered lines, and outstanding). A receipt-without-order is a later
  additive path (a nullable `purchase_order_id` + a direct-receipt flow); not built. The NOT NULL FK enforces the v1
  rule.
- **A `vat_status`/`vat_rate`/`vat_amount` column set on the purchase line (mirror Sales' VAT).** Forward for input-VAT
  recovery. **Rejected for v1 (OQ-PURCH-04 RESOLVED, FR-PURCH-13, BR-PURCH-08):** no purchase VAT is computed in v1
  (input-VAT recovery is Finance/AP, deferred). Adding VAT columns now would invite the assumption v1 computes
  purchase VAT. It is a clean additive column set when the AP/VAT round lands; reserved, not built.
- **Cost carried into the `STOCK.RECEIVED` payload / a stock valuation.** Forward for inventory value/COGS.
  **Rejected (BR-PURCH-09, D-5/D-8):** Stock v1 is quantity-only (ADR-0010 D-9 — no money column); carrying cost would
  imply a valuation v1 does not do. Cost lives on the Purchases line; the clean additive later change is a nullable
  cost on the Stock movement (ADR-0010's reserved column). Quantity-only payload chosen.
- **One polymorphic `purchase_documents` table (PO + GR sharing one table with a `document_type` discriminator).**
  Fewer tables. **Rejected (D-2):** PO and GR differ structurally — a GR FKs to a PO and to PO lines, draws down
  outstanding, and emits a stock event; a PO does none of these. They are two documents with a parent/child link, not
  two channels of one spine (unlike Sales' INVOICE/POS/SO, which *do* share a spine, ADR-0008 D-7). Two table pairs is
  the normal-form, boring choice.

## Open / flagged items (do NOT block the build; recommended defaults stand)

None changes the four-table schema or the producer/payload contract; all are policy values, naming, or additive-symmetry
choices with defaults the design is built to.

1. **Permission code spelling (D-11).** I qualified PO verbs with `ORDER` / GR-view with `GOODS_RECEIPT` and kept the
   spec's `PURCHASE.RECEIVE`/`PURCHASE.VOID` verbatim. If the owner prefers fully-qualified GR codes or unqualified PO
   codes, it is a seed rename before build. **Recommended default:** as in D-11. **Blocks build:** NO.
2. **`PURCHASE.ORDER.CLOSE` distinct vs folded into `PURCHASE.ORDER.CREATE` (D-6/D-11).** Folded for v1. If the owner
   wants a separate closer role, split additively (a seed change). **Recommended default:** folded. **Blocks build:** NO.
3. **`STOCK.RECEIPT.VOIDED` event name + the second Stock handler (D-7).** I named the GR-void compensating event
   `STOCK.RECEIPT.VOIDED` (parallel to `SALE.VOIDED`) and noted Stock needs a second handler (ADR-0010 D-5 reserved
   the symmetry). If the owner/Stock-owner prefers a different event name, it is a constant rename coordinated across
   ADR-0010/0011 before build. **Recommended default:** `STOCK.RECEIPT.VOIDED`. **Blocks build:** NO (it is a
   one-constant + one-bean addition; Purchases' emit obligation is unchanged).
4. **Producer-side non-stockable filtering (D-8).** Recommended: Purchases does NOT filter non-stockable lines from the
   payload — Stock's defensive skip (ADR-0010 D-3) is the single authority on what moves. If the owner prefers the
   producer to filter, it is a service tweak (no schema change). **Recommended default:** do not filter; let Stock
   skip. **Blocks build:** NO.
5. **Zero-cost free/sample line reason (D-5).** The `unit_cost_amount >= 0` CHECK permits zero; the service requires a
   `note` reason when cost = 0 (OQ-PURCH-04). If the owner wants a dedicated `is_free_sample` flag instead of a note,
   it is an additive column. **Recommended default:** note-as-reason. **Blocks build:** NO.
6. **Receipt-without-order (direct receipt) (Alternatives).** v1's GR is always against a PO (NOT NULL FK). A direct
   receipt is a later additive path (nullable FK + a direct-receive flow). **Recommended default:** PO-required in
   v1. **Blocks build:** NO.
7. **Reserve a nullable cost on the Stock movement for future valuation (NFR-PURCH-05).** Not Purchases' table; noted
   in ADR-0010's flags. **Recommended default:** do not add in v1 (quantity-only). **Blocks build:** NO.

No FR/BR is ambiguous enough to halt implementation; the items above are policy values, naming, and the cross-ADR
event-name/handler symmetry, all defaulted here and overridable without a schema change.

## Summary

This ADR specifies the Purchases data model as **two header→lines documents** in `com.erp.modules.purchases`: a
**`purchase_orders`** header + **`purchase_order_lines`** child (ordered quantity, `Money` unit cost, a **maintained
`received_qty_in_base`** with the `received <= ordered` CHECK so over-receipt is structurally impossible and
outstanding never negative — D-2/D-3), and a **separate `goods_receipts`** header (FK → PO) + **`goods_receipt_lines`**
child (FK → the PO line it draws down, snapshotting `qty_in_base` for the stock event). Both are scoped per company +
per raising/receiving branch (denormalised onto the children, §3.2 predicate). The PO lifecycle **DRAFT → ORDERED →
PARTIALLY_RECEIVED → RECEIVED → CLOSED / VOID** and the GR lifecycle **DRAFT → RECEIVED → VOID** have their transitions
in the service, with **partial-vs-full RECEIVED computed over the PO's lines at the end of each receive/void** (D-4),
and two per-company `code_sequence` series — **`PO-####` at order-placement, `GRN-####` at receive** (entity_kinds
`PURCHASE_ORDER`/`GOODS_RECEIPT`, no new counter — D-6). Cost is recorded as a `Money` line value and totalled per
document but **NOT valued into stock and computes no VAT/payable** (BR-PURCH-08/09; the `STOCK.RECEIVED` payload
carries only `qtyInBase` — D-5/D-8). **Purchases is the second transactional-outbox producer** (the mirror of Sales):
finalising a Goods Receipt calls `OutboxPublisher.publish(STOCK.RECEIVED, …)` **inside the receive transaction**
(atomicity invariant — never `REQUIRES_NEW`), with a payload **parallel to `SALE.FINALISED`** and **exactly what
ADR-0010's `GoodsReceiptStockHandler` reads** (`{ receiptUid, companyId, branchId, receivedAt, lines:[{ productId,
productUid, unitId, qtyInBase }] }`); voiding a Goods Receipt emits **`STOCK.RECEIPT.VOIDED`** so Stock reverses from
its own ledger (the symmetry ADR-0010 D-5 reserved). The enforcement split is the established DB-can't / service-must
(DB CHECKs for status/qty/received-≤-ordered/cost; service for over-receipt pre-validation, same-company, currency
consistency, outstanding consistency under the PO-line `version`, the same-TX outbox emit); two new `ScopeGuard` cases
(`purchaseorder`/`goodsreceipt`), `assertCanActIn` on **every read path**, company-scoped uid resolution, and
`companyUid`-in-PO-create / `purchaseOrderUid`-in-GR-create with branch-from-context. Audit emits with plural
`target_type` (`purchase_orders`/`purchase_order_lines`/`goods_receipts`) on place/receive/void; the outbox does not
double-audit. The migration is a single additive **`V8__purchases.sql`** (four tables, ~16 indexes, two
`code_sequence` kinds at runtime, six permissions + an ORG_ADMIN grant, **no outbox table, no FK into V6, no
trigger**) that never edits V1–V7. **This ADR feeds Stock via ADR-0009's transactional outbox exactly as Sales does
(the mirror producer), closing the buy→stock loop: a received Goods Receipt now increments on-hand, idempotently, in
base units, and a GR void reverses it.** No ADR-blocking question remains — every flagged item is a policy/naming/
cross-ADR-symmetry choice with a recommended default the design is built to. **Readiness for build (a PM sequencing
fact, not a date): V6 (outbox, ADR-0009) and V7 (Stock, ADR-0010 — including the `STOCK.RECEIVED` handler and the
`STOCK.RECEIPT.VOIDED` handler) must land before V8 (Purchases), or a received Goods Receipt's event has no consumer.
The model is ready for project-manager sequencing and backend build.**
