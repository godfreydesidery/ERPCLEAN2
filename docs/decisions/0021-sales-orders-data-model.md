# 0021 — Sales Orders / Order-to-Cash data model: a quotation→order→reservation→delivery→partial-invoice→return spine in `com.erp.modules.sales`, with the load-bearing seam moving stock issue + COGS from invoice-finalise to **delivery** time (the delivery owns a new `DELIVERY.CONFIRMED` event the ADR-0020 valuation engine consumes; the SO-sourced invoice carries an `origin=SALES_ORDER` so `SaleIssueStockHandler` never re-issues — COGS cannot double-count), a soft `reserved_qty` on `stock_on_hand` (available = quantity − reserved_qty, over-reservation allowed/flagged), per-line + order-level discounts flowing through the shipped `InvoiceTotalsCalculator`, returns reversing COGS at the original issued cost (ADR-0020 reverseIssue) + a `RETURN`-origin credit note (ArCreditNoteService), all on the existing outbox / IdempotencyGuard / code_sequence spine, additive as `V18__sales_orders.sql`

- **Status:** Accepted
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (owner-ratified Order-to-Cash requirements 2026-06-10 — full scope ratified; **no ADR-0021-blocking open question remains**, sales-orders.md §11. The seven design seams — the **invoice-origin mechanism** (OQ-SO-03), the **reservation model** (OQ-SO-01), the **over-reservation policy** (OQ-SO-02), the **reservation-release / invoicing granularity** (OQ-SO-04), the **return cost basis + credit-note origin** (OQ-SO-05), the **discount rounding** (OQ-SO-06), and the **quote→order edit/numbering semantics** (OQ-SO-07) — are the **decisions this ADR makes**, not requirements blockers; the *behaviour* is fixed by the requirements.)
- **Context source:** [docs/requirements/sales-orders.md](../requirements/sales-orders.md) (RATIFIED 2026-06-10 — FR-SO-01..17, BR-SO-01..17, NFR-SO-01..09, US-SO-01..08, §7 flows, §10 accepted boundary, §11 OQ log; the ground truth for every rule below) + [USER-STORIES.md](../../USER-STORIES.md) US-SO. Verified against the **shipped** code:
  - **Sales** ([ADR-0008](0008-sales-data-model.md) / [V5__sales.sql](../../backend/src/main/resources/db/migration/V5__sales.sql)): `SalesInvoice`/`SalesInvoiceLine`/`SalesInvoicePayment` (`sales_invoices` header — `id`, `uid` VARCHAR(26), `company_id`, `branch_id`, `document_type` CHECK `IN ('INVOICE')`, `invoice_number` nullable-until-finalise, `status` ∈ {DRAFT,FINALISED,VOID}, `customer_id`, `agent_id`, `doc_discount_amount`/`doc_discount_percent`, `net_total_amount`/`vat_total_amount`/`gross_total_amount`, `tax_summary` JSONB, `@Version`); `sales_invoice_lines` (`product_id`, snapshots, `qty_in_base`, `list_price_amount`/`unit_price_amount`, `line_discount_amount`/`line_discount_percent`, `vat_status`/`vat_rate`, computed `net/vat/gross`); `InvoiceTotalsCalculator.recompute(inv, lines)` (the tax-exclusive per-band VAT algorithm with line discount + doc-discount apportionment — **extended/reused for the SO discount, unchanged math**, D-9); `SalesInvoiceServiceImpl.create/addLine/addPayment/finalise/voidInvoice` (verified lines 147/204/319 — finalise allocates `INV-####` via `codeGen.next`, freezes totals, posts revenue via the AR/GL handlers, **and `outbox.publish(SALE.FINALISED, …, new SaleFinalisedPayload(invoiceUid, companyId, branchId, finalisedAt, payloadLines))` at line 297** — the seam this ADR keys off); `SaleFinalisedPayload(invoiceUid, companyId, branchId, finalisedAt, List<LineItem(productId, productUid, unitId, qtyInBase)>)` + `SaleVoidedPayload(invoiceUid, companyId, branchId)`; `DocumentType` enum (`INVOICE`; reserved `SALES_ORDER`); `code_sequence` numbering (ADR-0007 D-6, `entity_kind` discriminator).
  - **Stock / Inventory Valuation** ([ADR-0010](0010-stock-data-model.md) / V7 + [ADR-0020](0020-inventory-valuation-data-model.md) / [V17__inventory_valuation.sql](../../backend/src/main/resources/db/migration/V17__inventory_valuation.sql)): `StockOnHand` (`stock_on_hand` — `quantity` NUMERIC(19,6) signed/no-`>=0`-CHECK, `avg_cost` NUMERIC(19,4) nullable, `on_hand_value` NUMERIC(19,4) NOT NULL DEFAULT 0, `@Version` — **NO reserved column; reservation is new**, this ADR ALTERs it); `StockMovement` (`stock_movements` — append-only, `movement_type` CHECK ∈ {GOODS_RECEIPT, SALE_ISSUE, SALE_REVERSAL, GOODS_RECEIPT_REVERSAL, ADJUSTMENT, OPENING_BALANCE}, `unit_cost_amount`/`value_amount` cost cols, `uq_stock_movement_source_event (source_event_uid, product_id)` idempotency backstop); `StockPostingService.post(companyId, branchId, productId, quantity, movementType, sourceEventUid, sourceDocumentType, sourceDocumentUid, reasonCode, note, occurredAt, actorId, unitCostAmount, valueAmount)` (MANDATORY, optimistic-lock one-retry upsert); **`InventoryValuationService.costIssue(companyId, branchId, productId, issuedQty)→issuedValue|null`** (debits `on_hand_value` at current avg; null when avg not established) + **`reverseIssue(companyId, branchId, productId, originalValue)`** (restores value at original cost) + `recomputeOnReceipt` / `reverseReceipt`; **`InventoryGlPoster.postCogsInNewTx(companyId, branchId, postingDate, sourceRef, currency, List<CogsLeg>)`** (DR COGS 5100 / CR INVENTORY 1300, REQUIRES_NEW, null-on-anomaly) + `postSaleReversalInNewTx(...)` (DR INVENTORY / CR COGS); **`SaleIssueStockHandler`** (consumes `SALE.FINALISED`, recipe explosion via `RecipeExplosionResolver`, per-line `costIssue` + accumulate `CogsLeg` + one COGS journal — **the handler this ADR makes origin-aware so SO-sourced invoices skip the issue**); `SaleReversalStockHandler` (consumes `SALE.VOIDED`, reverses `SALE_ISSUE` rows it finds by `source_document_uid` — **finds none for a revenue-only SO invoice → clean no-op**, the safety property that makes the seam robust); `RecipeExplosionResolver.explode(uid, qty)→List<ExplosionLine(productId, signed qty)>` / `isComposed(uid)`.
  - **AR** ([ADR-0014](0014-accounts-receivable-data-model.md) / V11): `ArCreditNoteService.raise(RaiseCreditNoteRequest(companyUid, customerUid, arInvoiceUid, noteDate, netAmount, vatAmount, currency, reason))→ArCreditNoteDto` (verified `ArCreditNoteServiceImpl` — posts DR SALES_REVENUE + DR VAT_PAYABLE / CR ACCOUNTS_RECEIVABLE, reduces the target open item, `numberGen.nextCreditNote`, `sourceType=AR_CREDIT_NOTE`); `ArCreditNote` entity carries `origin`; `ArCreditNoteOrigin` enum = {STANDALONE, SALE_VOID} (**this ADR adds RETURN**); `chk_ar_credit_note_origin CHECK (origin IN ('STANDALONE','SALE_VOID'))` (V11 — **this ADR widens it to add RETURN**); `ArSalePostedHandler` (creates the AR open item for a credit-customer SO/direct invoice on `SALE.FINALISED` — **unchanged**, an SO-sourced invoice still creates its AR item).
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / V10): `GLPostingService.post(JournalEntryDraft)→JournalEntryDto` + `GLPostingSafeInvoker.postInNewTx` (REQUIRES_NEW null-on-anomaly); `JournalSourceType` (admits `COGS` since V17 — the delivery COGS post reuses it); `GlConfigKey` (`SALES_REVENUE`, `VAT_PAYABLE`, `ACCOUNTS_RECEIVABLE`, `INVENTORY`, `COGS` all defined + mapped).
  - **Outbox / idempotency** ([ADR-0009](0009-transactional-outbox.md)): `OutboxPublisher.publish(eventType, aggregateType, aggregateId, aggregateUid, companyId, branchId, payload)` in the caller's TX; `DomainEventType` constants (`SALE.FINALISED`/`SALE.VOIDED`/`STOCK.RECEIVED`/`STOCK.RECEIPT.VOIDED` — **this ADR adds `DELIVERY.CONFIRMED` + `DELIVERY.RETURNED`**); `DomainEventHandler` + `IdempotencyGuard.alreadyProcessed(consumer, uid)`/`markProcessed`; `processed_events(consumer, event_uid)`.
  - **Products** ([ADR-0007](0007-products-data-model.md) / V3): sellable products, `units_of_measure`, `price_lists`/`product_prices`, single-level recipes; the `code_sequence` numbering mechanism + `ScopeGuard.companyIdOf` target-type switch.
  - **Money** ([ADR-0005](0005-money-and-currency.md)): base currency only (TZS), `NUMERIC(19,4)`, HALF_UP.
  - [[db-naming-convention]] verified against V1–V17 (plural masters/owned-children `quotations`/`quotation_lines`/`sales_orders`/`sales_order_lines`/`deliveries`/`delivery_lines`/`sales_returns`/`sales_return_lines`; singular constraint roots `uq_`/`fk_`/`chk_` on the singular entity; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id`/`branch_id` BIGINT scalar; additive `DROP/ADD CONSTRAINT` widen). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **Latest shipped migration is `V17__inventory_valuation.sql` → Sales Orders is `V18__sales_orders.sql`** (additive; V1–V17 FROZEN). Next ADR is 0022.

This ADR is the **technical data model + integration design** for Order-to-Cash depth (ROADMAP T2.1, PATH-TO-FULL-ERP §6). It translates the ratified spec into: the eight new document/line tables in `com.erp.modules.sales`, the quotation / sales-order / delivery / return lifecycle enums + transitions + the line-quantity rollup rules, the soft-reservation model (`reserved_qty` on `stock_on_hand`) + concurrency, **the load-bearing stock-issue seam (delivery owns a new event; the SO-sourced invoice carries an origin so the existing `SaleIssueStockHandler` never re-issues — with the double-count-impossibility argument)**, partial invoicing from a delivery, the discount calc reusing `InvoiceTotalsCalculator`, the return flow (COGS reverse at original cost + a `RETURN` credit note), the `V18` migration ordering with **#12-safe seed-uids**, the ArchUnit edges, and the **Stage-1 / Stage-2 build split**. It is **concrete enough that the backend engineer writes `V18` + the quote/SO/delivery/return model + the stock-issue seam + reservation + partial invoicing + discounts + returns without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. Nothing ratified is re-litigated.

## Context

The invoice channel (ADR-0008/V5), the inventory valuation + COGS engine (ADR-0020/V17), and the AR credit-note service (ADR-0014/V11) all ship. What is missing is the **pre-invoice operational depth** (sales-orders.md §1): no quotation, no sales order, no soft reservation, no delivery document, no backorder, no partial invoicing against an order, no structured return. This slice adds the **quote → order → reserve → deliver → invoice → [return]** spine on top of the shipped invoice channel and, in doing so, makes one **load-bearing change**: it **moves the stock issue + COGS posting from invoice-finalise to delivery-time**, so an SO-sourced invoice posts **revenue only** while a direct walk-in invoice keeps issuing stock on finalise as today. The forces:

- **THE KEY SEAM (the top risk — get it wrong and COGS double-counts; OQ-SO-03).** Today *every* `SALE.FINALISED` is consumed by `SaleIssueStockHandler`, which deducts qty + posts COGS. With deliveries, the **delivery** is when goods physically leave, so the issue + COGS must move there. An SO-sourced invoice must then post revenue only and **never** re-issue. Two mechanisms: **(a)** the invoice carries an `origin`/flag and the issue path *skips* it, or **(b)** the **delivery owns its own stock event** and the SO-sourced invoice simply emits a revenue-only finalise. The requirement fixes the behaviour (BR-SO-09); the mechanism is this ADR's. Resolved in **D-6/D-7** — *both* (b) and a defensive (a) flag, belt-and-braces, with a formal double-count-impossibility argument.

- **The reservation model is new (OQ-SO-01).** `stock_on_hand` is quantity + cost only — no `reserved`. A soft reservation must record "spoken for" without moving stock or posting GL, and yield **available = quantity − reserved_qty** as the available-to-promise figure. Where does it live — a `reserved_qty` column on `stock_on_hand`, a per-SO-line reservation ledger, or both? Resolved in **D-5**.

- **Over-reservation / backorder (OQ-SO-02).** Confirming an SO may reserve beyond on-hand (→ negative available) because backorders are supported. Allow-and-flag is the ratified default. Resolved in **D-5**.

- **The line-quantity rollup is the spine of the lifecycle (BR-SO-12).** The SO status (PARTIALLY_FULFILLED / FULFILLED / PARTIALLY_INVOICED / INVOICED / CLOSED) is *derived* from each line's ordered / fulfilled / invoiced quantities, and the over-deliver / over-invoice / over-return guards (BR-SO-11) are cross-document quantity checks. The line must carry the running quantities that drive both. Resolved in **D-3/D-4**.

- **Discounts must reconcile to the cent backend/frontend (BR-SO-10, NFR-SO-03).** The SO carries per-line + order-level discounts; the invoice raised from a delivery must compute VAT on the discounted net using the *identical* algorithm the shipped `InvoiceTotalsCalculator` already runs. Resolved in **D-9** — reuse unchanged.

- **The return reverses COGS at the original issued cost (OQ-SO-05).** A return against a delivery must put stock back and reverse COGS at the cost the delivery issued at (symmetric, no phantom gain/loss — exactly the ADR-0020 D-5 `reverseIssue` precedent), and raise a credit note reversing revenue/AR/VAT. Resolved in **D-11**.

- **Schema freeze / direction.** IAM=V1 … Inventory Valuation=V17, all frozen. Order-to-Cash is additive `V18`: eight new tables, two additive `stock_on_hand` columns (`reserved_qty` + a flag is not needed — see D-5), three additive `sales_invoices` columns (origin + source refs), a one-token `chk_ar_credit_note_origin` widen, four new `code_sequence` kinds, the new permissions, and two new `DomainEventType` constants. It imports no AR/Stock *entity* — sales reaches `stock.service` (delivery issues) and `ar.service` (return credit note) the same way `ap.service` already reaches `gl.service` (D-13).

## Decision

### D-1 — Module placement: Order-to-Cash lives in `com.erp.modules.sales` (it owns the invoice channel); sales gains outbound edges to `stock.service` and `ar.service`

The quote / SO / delivery / return documents live in **`com.erp.modules.sales`** — it already owns the invoice channel (`sales_invoices`), `InvoiceTotalsCalculator`, the price/tax resolvers, and the `SALE.FINALISED` choreography these documents extend and reuse. A separate `orders` module would have to re-read the invoice channel, re-resolve pricing/VAT, and re-own the discount math. Reject (ADR-0008 D-1 already named SALES_ORDER a reserved *channel of the same spine*).

Internal layout (additive to the shipped `sales` package):

```
com.erp.modules.sales
├── domain.entity   Quotation, QuotationLine,
│                   SalesOrder, SalesOrderLine,
│                   Delivery, DeliveryLine,
│                   SalesReturn, SalesReturnLine                 (Stage 2)
├── domain.dto      QuotationDto / CreateQuotationRequest / QuotationLineDto / SendQuotationRequest …,
│                   SalesOrderDto / CreateSalesOrderRequest / SalesOrderLineDto / ConfirmOrderRequest …,
│                   DeliveryDto / CreateDeliveryRequest / DeliveryLineDto,
│                   SalesReturnDto / CreateSalesReturnRequest / SalesReturnLineDto   (Stage 2)
│                   DeliveryConfirmedPayload  (NEW outbox payload, D-6),
│                   DeliveryReturnedPayload   (NEW outbox payload, Stage 2, D-11)
├── domain.enums    QuotationStatus, SalesOrderStatus, DeliveryStatus, SalesReturnStatus (D-2)
├── repository      QuotationRepository, QuotationLineRepository,
│                   SalesOrderRepository, SalesOrderLineRepository,
│                   DeliveryRepository, DeliveryLineRepository,
│                   SalesReturnRepository, SalesReturnLineRepository
└── service         QuotationService(+Impl), SalesOrderService(+Impl),
                    DeliveryService(+Impl), SalesReturnService(+Impl)  (Stage 2),
                    OrderToCashNumberGenerator  (QUOTE/SO/DELIVERY/RETURN via code_sequence, D-12),
                    SalesOrderDiscountResolver  (apportions order discount → invoice lines, D-9)
```

Controllers stay flat in `com.erp.api`: `QuotationController`, `SalesOrderController`, `DeliveryController`, `SalesReturnController` (Stage 2). They touch only services (`ModuleBoundaryTest`).

**Boundary note (D-13):** sales reads **DTOs only** from Products/Parties/Stock/AR (never their entities/repositories). The cross-module references it persists are **scalar `Long` id + `String` uid columns** with real DB FKs *within the sales tables only* — no cross-module `@ManyToOne`. The delivery's stock-issue effect and the return's credit-note effect go through **service-layer calls / outbox events** into `stock.service` / `ar.service` returning DTOs (the `ap.service → gl.service` precedent, ADR-0020 D-1 / ADR-0015).

### D-2 — Lifecycle + status enums (the exact set, transitions, and rollup rules)

Four new enums in `sales.domain.enums`. Every transition is **service-guarded, audited, append-only** (NFR-SO-04, BR-SO-16); status is **never free-set** (BR-SO-12).

**`QuotationStatus`** (FR-SO-01/02/03, BR-SO-01):

```
DRAFT ──send──▶ SENT ──accept──▶ ACCEPTED   (converts → SalesOrder, D-3)
   │              │
   │              ├──reject──▶ REJECTED      (terminal)
   │              └──(validity date passed)──▶ EXPIRED   (terminal; cannot be accepted)
   └──(hard delete allowed while DRAFT — consumed no number)
```

- `QUOTE-####` allocated **at SEND** (OQ-SO-07 default — mirrors invoice number-at-finalise). DRAFT carries `quote_number = NULL`.
- **EXPIRED** is reached lazily: a quote whose `valid_until < today` **cannot be accepted** (service rejects with "quote expired"); a scheduled sweep (or the accept guard) sets `EXPIRED`. No timer infra is mandatory in v1 — the accept-time guard is the authoritative check; the sweep is cosmetic.
- ACCEPTED is terminal for the quote; the SO it spawned carries the link (`sales_orders.source_quotation_uid`).

**`SalesOrderStatus`** (FR-SO-04/05/06/15, BR-SO-02/12) — the exact set and transitions:

```
DRAFT ──confirm──▶ CONFIRMED ──(rollup)──▶ PARTIALLY_FULFILLED ──(rollup)──▶ FULFILLED
   │                   │                          │                              │
   │                   │                          └──────(rollup, invoicing)─────┤
   │                   ├──(rollup, invoicing)──▶ PARTIALLY_INVOICED ──(rollup)──▶ INVOICED
   │                   │                                                          │
   │                   └────────────────────────────────────────────────────────┴──▶ CLOSED
   │                                                                                   (fully delivered AND fully invoiced)
   └──(hard delete allowed while DRAFT)
  any non-terminal ──cancel──▶ CANCELLED   (releases remaining reservation, D-5; terminal)
```

- `SO-####` allocated **at CREATE** (OQ-SO-07 default — the SO exists as a document from draft; unlike the invoice, a draft SO is a real working order). DRAFT reserves nothing, posts nothing.
- **The rollup is a single derived function** computed after every delivery / invoice action, from the **line quantities** (D-4), never set by the user (BR-SO-12). Fulfilment and invoicing progress **in parallel** — the requirement (sales-orders.md §3.7) states an SO can be PARTIALLY_FULFILLED and PARTIALLY_INVOICED at once. **Decision: a single `status` column carries the *furthest-reached, most-specific* state by this precedence, with the two dimensions tracked precisely on the lines** (D-4):

  ```
  if CANCELLED                                              → CANCELLED
  else if Σ fulfilled == 0                                  → CONFIRMED
  else if fully delivered AND fully invoiced                → CLOSED
  else if fully delivered (Σ fulfilled == Σ ordered)        → FULFILLED      then, if Σ invoiced > 0 but < Σ delivered → keep FULFILLED but expose invoiced dimension on DTO
  else                                                      → PARTIALLY_FULFILLED
  // invoicing dimension overlay: when Σ invoiced > 0:
  //   PARTIALLY_FULFILLED + (0 < Σ invoiced)               → PARTIALLY_INVOICED is exposed on the DTO as the invoicing sub-state
  //   INVOICED (Σ invoiced == Σ delivered, not yet fully delivered) is a derived label, not a stored override of FULFILLED
  ```

  To avoid the lossy single-enum collapse, **the SO carries two derived label fields on the DTO** — `fulfilmentState ∈ {NONE, PARTIAL, FULL}` and `invoicingState ∈ {NONE, PARTIAL, FULL}` — computed from the line quantity sums, and the stored `status` column is the **headline** enum above (the value users filter/report on). `CLOSED` is reached only when `fulfilmentState == FULL && invoicingState == FULL`. This keeps a clean filterable `status` while preserving both dimensions (the requirement's "surfaces both dimensions, presented per the architect's chosen status model", §3.7). The stored enum set is exactly: `DRAFT, CONFIRMED, PARTIALLY_FULFILLED, FULFILLED, PARTIALLY_INVOICED, INVOICED, CLOSED, CANCELLED`.

  **Resolution of the overlap:** `PARTIALLY_INVOICED` / `INVOICED` are stored only when fulfilment has caught up such that invoicing is the lagging dimension *or* equal — concretely, the stored `status` = the **min-progress dimension's most specific reached state**, computed deterministically:
  - `fulfilmentState == NONE` → `CONFIRMED`
  - `fulfilmentState == PARTIAL` → `PARTIALLY_FULFILLED` (regardless of invoicing — fulfilment is the binding constraint; invoicingState is on the DTO)
  - `fulfilmentState == FULL && invoicingState == NONE` → `FULFILLED`
  - `fulfilmentState == FULL && invoicingState == PARTIAL` → `PARTIALLY_INVOICED`
  - `fulfilmentState == FULL && invoicingState == FULL` → `CLOSED`

  This is a total function over the line sums, never ambiguous, never user-set. (`INVOICED` as a stored value collapses into `CLOSED` when fulfilment is full; it is retained in the enum for the forward case where invoicing can exceed delivery is impossible — BR-SO-11 — so `INVOICED` is reachable only transiently and is normalised to `CLOSED`. The engineer stores the computed value each transition; the enum is the closed set the DB CHECK admits.)

**`DeliveryStatus`** (FR-SO-09/10, BR-SO-04):

```
DRAFT ──confirm──▶ CONFIRMED ──(a return is raised against it)──▶ (stays CONFIRMED; returns tracked on lines)
```

- A delivery is **confirmed in one step in v1** (no separate pick/pack — deferred §2). **Decision: the delivery is created already CONFIRMED** (the create operation issues stock + posts COGS + releases reservation atomically — there is no meaningful DRAFT delivery in v1 since picking/packing is deferred). The `DRAFT` value is reserved in the enum for the future pick/pack workflow but **not used by v1 create** (create writes `CONFIRMED` and emits `DELIVERY.CONFIRMED`). `DEL-####` allocated **at create**. A delivery is **immutable once CONFIRMED** (corrections are a return, BR-SO-16). `returned_qty` per delivery line tracks returns (D-4) but does not change the delivery's own status.

**`SalesReturnStatus`** (Stage 2 — FR-SO-14, BR-SO-13):

```
DRAFT ──confirm──▶ CONFIRMED   (stock IN + COGS reverse + credit note, atomically; RET-#### at create; immutable once CONFIRMED)
```

- Same single-step posture as the delivery: **created already CONFIRMED** in v1 (the stock-in + COGS-reversal + credit-note happen on create). `RET-####` allocated at create.

### D-3 — Quotation + Sales-Order tables (header + lines); the quote→order conversion

All tables: plural names; `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) ULID `uq_<root>_uid`; `company_id` + `branch_id` BIGINT NOT NULL (denormalised onto child lines, set-once-immutable — the ADR-0008 D-2 tenant-predicate-without-join pattern); standard audit cols; `@Version` on headers. Money columns `NUMERIC(19,4)`; quantity columns `NUMERIC(19,6)` (the shipped scale). Cross-document references are **scalar `uid` (VARCHAR(26)) + intra-DB FK by id** to the sales tables; cross-module references (product, unit, customer, agent) are **scalar `Long` id** with real FKs (the D-1 boundary rule).

#### `quotations` (header)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_quotation_uid` |
| `company_id` / `branch_id` | BIGINT | NO | tenant; `fk_quotation_company` / `fk_quotation_branch` |
| `quote_number` | VARCHAR(30) | YES | `QUOTE-####`; NULL while DRAFT, assigned at SEND (D-12) |
| `status` | VARCHAR(20) | NO | `QuotationStatus`; DEFAULT `'DRAFT'`; `chk_quotation_status` |
| `customer_id` | BIGINT | NO | scalar FK → `customers(id)` |
| `agent_id` | BIGINT | YES | scalar FK → `agents(id)`; auto-defaulted like the invoice (FR-SALES-15) |
| `currency` | VARCHAR(3) | NO | document currency (= base) |
| `quote_date` | DATE | NO | business date |
| `valid_until` | DATE | NO | validity date (FR-SO-01); `chk_quotation_validity CHECK (valid_until >= quote_date)` |
| `doc_discount_amount` | NUMERIC(19,4) | YES | order-level discount amount (D-9) |
| `doc_discount_percent` | NUMERIC(9,4) | YES | order-level discount percent; `chk_quotation_doc_discount CHECK (… BETWEEN 0 AND 100)` |
| `net_total_amount` / `vat_total_amount` / `gross_total_amount` | NUMERIC(19,4) | NO | computed roll-ups (D-9), DEFAULT 0 |
| `notes` | VARCHAR(500) | YES | |
| `sent_at` / `accepted_at` / `rejected_at` / `expired_at` | TIMESTAMPTZ | YES | transition stamps |
| `converted_order_uid` | VARCHAR(26) | YES | set on accept → the SO it produced |
| `version` + audit cols | | | |

Constraints: `uq_quotation_company_number UNIQUE (company_id, quote_number)` (NULLs distinct — many DRAFTs coexist, the ADR-0008 D-2 pattern); `chk_quotation_status CHECK (status IN ('DRAFT','SENT','ACCEPTED','EXPIRED','REJECTED'))`; `chk_quotation_number_when_sent CHECK ((status = 'DRAFT' AND quote_number IS NULL) OR (status <> 'DRAFT' AND quote_number IS NOT NULL))`.

#### `quotation_lines` (child)

`id`, `uid` (`uq_quotation_line_uid`), `quotation_id` (FK), `company_id`/`branch_id` (denormalised), `line_no` SMALLINT (`uq_quotation_line_no UNIQUE (quotation_id, line_no)`), `product_id` (scalar FK), `product_code`/`product_name` snapshots, `unit_id` (scalar FK), `unit_name` snapshot, `quantity` NUMERIC(19,6) `CHECK > 0`, `qty_in_base` NUMERIC(19,6) `CHECK > 0`, `list_price_amount`/`unit_price_amount` NUMERIC(19,4) (price-list snapshot, FR-SALES-07), `line_discount_amount`/`line_discount_percent`, `vat_status`/`vat_rate` snapshots, computed `net_amount`/`vat_amount`/`gross_amount`, `currency`, audit cols. Identical shape to `sales_invoice_lines` minus the override-audit fields (a quote is draft pricing).

#### `sales_orders` (header)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_sales_order_uid` |
| `company_id` / `branch_id` | BIGINT | NO | tenant FKs |
| `order_number` | VARCHAR(30) | NO | `SO-####`; assigned **at create** (D-12); `uq_sales_order_company_number UNIQUE (company_id, order_number)` |
| `status` | VARCHAR(20) | NO | `SalesOrderStatus`; DEFAULT `'DRAFT'`; `chk_sales_order_status` (the 8-value set, D-2) |
| `customer_id` | BIGINT | NO | scalar FK |
| `agent_id` | BIGINT | YES | scalar FK; auto-defaulted |
| `currency` | VARCHAR(3) | NO | |
| `order_date` | DATE | NO | |
| `source_quotation_uid` | VARCHAR(26) | YES | the quote it converted from (D-3 conversion), NULL if directly created |
| `doc_discount_amount` / `doc_discount_percent` | NUMERIC(19,4) / (9,4) | YES | order-level discount (D-9) |
| `net_total_amount` / `vat_total_amount` / `gross_total_amount` | NUMERIC(19,4) | NO | computed roll-ups, DEFAULT 0 |
| `confirmed_at` / `cancelled_at` | TIMESTAMPTZ | YES | transition stamps |
| `cancel_reason` | VARCHAR(255) | YES | |
| `notes` | VARCHAR(500) | YES | |
| `version` + audit | | | |

Constraints: `chk_sales_order_status CHECK (status IN ('DRAFT','CONFIRMED','PARTIALLY_FULFILLED','FULFILLED','PARTIALLY_INVOICED','INVOICED','CLOSED','CANCELLED'))`; `chk_sales_order_doc_discount` (percent 0..100); `fk_sales_order_company`/`_branch`/`_customer`/`_agent`.

#### `sales_order_lines` (child) — carries the running open-quantity tracking that drives the rollup (D-4)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_sales_order_line_uid` |
| `sales_order_id` | BIGINT | NO | FK → `sales_orders(id)` |
| `company_id` / `branch_id` | BIGINT | NO | denormalised |
| `line_no` | SMALLINT | NO | `uq_sales_order_line_no UNIQUE (sales_order_id, line_no)` |
| `product_id` | BIGINT | NO | scalar FK |
| `product_code` / `product_name` | VARCHAR | NO | snapshot |
| `unit_id` | BIGINT | NO | scalar FK |
| `unit_name` | VARCHAR(60) | NO | snapshot |
| `qty_ordered` | NUMERIC(19,6) | NO | `CHECK > 0`; in `unit_id` units |
| `qty_ordered_base` | NUMERIC(19,6) | NO | converted to base unit (drives reservation + delivery base-qty) |
| `qty_fulfilled_base` | NUMERIC(19,6) | NO | DEFAULT 0; running Σ delivered (base units); `CHECK >= 0` (D-4) |
| `qty_invoiced_base` | NUMERIC(19,6) | NO | DEFAULT 0; running Σ invoiced (base units); `CHECK >= 0` (D-4) |
| `qty_reserved_base` | NUMERIC(19,6) | NO | DEFAULT 0; the line's current open reservation (base units); `CHECK >= 0` (D-5) |
| `list_price_amount` / `unit_price_amount` | NUMERIC(19,4) | NO | snapshot + applied (override audit on confirm) |
| `price_overridden` | BOOLEAN | NO | DEFAULT false |
| `overridden_by` | BIGINT | YES | FK → `app_users(id)` |
| `line_discount_amount` / `line_discount_percent` | NUMERIC(19,4) / (9,4) | YES | |
| `vat_status` / `vat_rate` | VARCHAR(20) / NUMERIC(9,4) | NO | snapshot |
| `net_amount` / `vat_amount` / `gross_amount` | NUMERIC(19,4) | NO | computed (D-9) |
| `currency` | VARCHAR(3) | NO | |
| audit cols | | | |

Constraints: `chk_sales_order_line_qty CHECK (qty_ordered > 0 AND qty_ordered_base > 0)`; `chk_sales_order_line_progress CHECK (qty_fulfilled_base >= 0 AND qty_invoiced_base >= 0 AND qty_fulfilled_base <= qty_ordered_base AND qty_invoiced_base <= qty_fulfilled_base)` — **the over-deliver and over-invoice guards expressed at the DB as a single-row CHECK** (BR-SO-11: delivered ≤ ordered, invoiced ≤ delivered); the service still enforces the same caps with friendly errors, the CHECK is the backstop. `chk_sales_order_line_reserved CHECK (qty_reserved_base >= 0)`. (Note: `qty_reserved_base` is **not** capped at `qty_ordered_base` because the release-on-delivery decrements it — reserved + fulfilled ≈ ordered through the line's life; the invariant the service maintains is `qty_reserved_base = qty_ordered_base − qty_fulfilled_base` for a CONFIRMED line, falling to 0 on full delivery or cancel, D-5.)

**Quote → order conversion (FR-SO-03, BR-SO-01):** on `accept`, `QuotationService` calls `SalesOrderService.createFromQuotation(quotationUid)`, which copies each quote line into a `sales_order_line` — **keeping the quoted pricing** (`list_price_amount`/`unit_price_amount`/discounts/`vat_rate` copied verbatim — OQ-SO-07 default: the customer accepted *that* offer, not a re-priced one), copies the order-level discount, sets `source_quotation_uid`, allocates `SO-####`, and opens the SO **DRAFT** (OQ-SO-07 default — it is confirmed deliberately so the reservation is an explicit act). The quote moves to ACCEPTED with `converted_order_uid` set. No stock, no GL (BR-SO-01).

### D-4 — Line-level open-quantity tracking + the rollup (the mechanism behind BR-SO-11 / BR-SO-12)

Each `sales_order_line` carries four running base-unit quantities: `qty_ordered_base` (fixed at create/confirm), `qty_reserved_base`, `qty_fulfilled_base`, `qty_invoiced_base`. Every action mutates exactly the relevant counters **in the action's own transaction, under the SO's `@Version`**:

| action | effect on the SO line | guard (BR-SO-11) |
|---|---|---|
| **confirm SO** | `qty_reserved_base := qty_ordered_base` for every line; reserve on `stock_on_hand` (D-5) | — |
| **deliver `d` (base)** | `qty_fulfilled_base += d`; `qty_reserved_base −= d` (release); issue stock + COGS (D-6) | `d <= qty_ordered_base − qty_fulfilled_base` (open qty) — else reject |
| **invoice `i` (base)** | `qty_invoiced_base += i` (on the SO line, mapped from the delivery line invoiced) | `i <= qty_fulfilled_base − qty_invoiced_base` (delivered-not-invoiced) — else reject |
| **return `r` (base)** | (Stage 2) decrement the *delivery line's* `returned_qty_base` accounting; does **not** restore `qty_fulfilled_base` on the SO line (the goods were delivered; a return is a separate financial/stock reversal, not an un-delivery — BR-SO-13) | `r <= delivery_line.qty_delivered_base − delivery_line.returned_qty_base` |
| **cancel SO** | release remaining: `qty_reserved_base := 0` for every line (D-5); status → CANCELLED | only the **undelivered** balance is cancelled (delivered portion stands — OQ-SO-04) |

After deliver/invoice the SO `status` is recomputed by the D-2 total function from `Σ qty_ordered_base / Σ qty_fulfilled_base / Σ qty_invoiced_base`. **`open_qty = qty_ordered_base − qty_fulfilled_base` is the backorder** (BR-SO-07) — a derived figure, not a column. **Decision: reservation release on delivery is keyed off the SO line, not `stock_on_hand` arithmetic alone** — the delivery decrements `qty_reserved_base` on the SO line *and* `reserved_qty` on `stock_on_hand` (D-5) in the same TX, so the two never drift (NFR-SO-05).

### D-5 — Reservation model: an additive `reserved_qty` on `stock_on_hand`; the SO line is the per-order reservation record (OQ-SO-01/02)

**Decision (recommended, per OQ-SO-01): an additive `reserved_qty` column on `stock_on_hand` for the fast available-to-promise read, with the per-SO-line `qty_reserved_base` (D-4) serving as the per-order reservation record.** No separate `stock_reservations` ledger table — the SO line *is* the reservation record (it carries `qty_reserved_base` and its own uid for traceability), and `stock_on_hand.reserved_qty` is the aggregate the ATP read needs. This avoids a third table that would duplicate the (company, branch, product) key and need its own lock, while keeping per-order traceability on the line.

**ALTER `stock_on_hand` (additive, V18):**

| column | type | null | default | notes |
|---|---|---|---|---|
| `reserved_qty` | `NUMERIC(19,6)` | NO | `0` | aggregate soft-reserved quantity in base units across all confirmed SO lines for this (company, branch, product). **available-to-promise = `quantity − reserved_qty`** (a derived read, BR-SO-05). |

- **No CHECK on `reserved_qty` sign beyond `>= 0`** — `chk_stock_on_hand_reserved_nonneg CHECK (reserved_qty >= 0)`. `reserved_qty` itself is always ≥ 0; **over-reservation surfaces as `quantity − reserved_qty < 0` (negative available), which is allowed and flagged** (BR-SO-05, OQ-SO-02) — backorders are supported. There is **no** column for "available"; it is computed `quantity − reserved_qty` on read (and exposed on `StockOnHandDto` + the ATP read).
- `reserved_qty` is added to the **`@Version`-guarded** `StockOnHand` entity so it rides the existing optimistic lock (NFR-SO-05). It is mutated **only** through a new `StockPostingService`-adjacent reservation primitive (below); never through the quantity-movement path (a reservation is **not** a `stock_movements` row — BR-SO-03).

**The reservation primitive (new, in `stock.service`):** a method `StockReservationService.reserve(companyId, branchId, productId, deltaBase, actorId)` that loads the locked `stock_on_hand` row, applies `reserved_qty += deltaBase` (positive to reserve, negative to release), saves under the `@Version` lock with **one retry on `ObjectOptimisticLockingFailureException`** (the ADR-0020 D-2 precedent), and writes **no `stock_movements` row and no GL** (BR-SO-03). It upserts the on-hand row if absent (a reservation can precede any receipt — backorder). Callers:
- **confirm SO** → `reserve(+qty_ordered_base)` per line;
- **deliver** → `reserve(−delivered_base)` per line (release the delivered portion) *in the same TX as the stock issue* (D-6) so reserved and on-hand fall together (NFR-SO-05);
- **cancel SO** → `reserve(−qty_reserved_base)` per line (release the remaining).

**Why a column not a ledger:** OQ-SO-01's recommended default named both options; the SO line already gives per-order traceability and release-keying (D-4), so a separate `stock_reservations` ledger adds a table + a lock + a write on the hot path with no traceability benefit at v1's single-location scope. The column is the lean choice (Alternatives). A future multi-location / allocation feature (deferred §2) is the point to introduce a per-location reservation ledger — additive, not precluded (NFR-SO-08).

**Concurrency (NFR-SO-05):** all `reserved_qty` mutations and the delivery's stock issue touch the same `stock_on_hand` row under its `@Version`; the one-retry re-reads fresh state. The reserve-on-confirm and release-on-deliver for the same product serialise on that row. No `SELECT FOR UPDATE` — optimistic lock is the chosen mechanism (consistent with ADR-0010 NFR-STOCK-04 / ADR-0020 D-2).

### D-6 — THE KEY SEAM (OQ-SO-03): the DELIVERY owns the stock-issue + COGS event (option b), and the SO-sourced invoice carries `origin=SALES_ORDER` so `SaleIssueStockHandler` skips re-issue (option a as belt-and-braces). COGS cannot double-count.

This is the load-bearing decision. **Adopt option (b) — the delivery owns its own stock event — *and* add the defensive origin flag of option (a)**, because the existing `SaleIssueStockHandler` consumes *every* `SALE.FINALISED` and we must guarantee an SO-sourced finalise never reaches the issue path.

**(1) The delivery emits a new event.** `DeliveryService.create(...)`, in its own `@Transactional` create method, after writing the `deliveries` + `delivery_lines` rows and decrementing the SO-line/`stock_on_hand` reservations (D-5), **publishes a new outbox event in the same TX**:

```
DomainEventType.DELIVERY_CONFIRMED = "DELIVERY.CONFIRMED"     (NEW constant)
aggregateType = "DELIVERY"                                    (NEW AGG constant)
payload = DeliveryConfirmedPayload(
    deliveryUid, salesOrderUid, companyId, branchId, deliveredAt,
    List<LineItem(productId, productUid, unitId, qtyInBase)>   // exactly the SaleFinalisedPayload.LineItem shape
)
```

The payload's `LineItem` is **deliberately the same shape** as `SaleFinalisedPayload.LineItem` so the consumer reuses the identical recipe-explosion + cost logic.

**(2) A new handler consumes it — REUSING the ADR-0020 engine.** `DeliveryIssueStockHandler` (in `stock.events`, mirroring `SaleIssueStockHandler` almost verbatim) consumes `DELIVERY.CONFIRMED` under `IdempotencyGuard` (consumer `"STOCK.DELIVERY_ISSUE"`):
- for each line: recipe-explode if composed (`RecipeExplosionResolver`), then per stockable component/line call **`InventoryValuationService.costIssue(...)`** (debits `on_hand_value` at current avg, returns issued value or null), **`StockPostingService.post(... MovementType.SALE_ISSUE ... sourceEventUid = event.uid, sourceDocumentType = "DELIVERY", sourceDocumentUid = deliveryUid ... unitCost, value)`** (the qty deduction + cost on the movement), and accumulate a `CogsLeg`;
- post **one COGS journal** DR `5100 COGS` / CR `1300 INVENTORY` via **`InventoryGlPoster.postCogsInNewTx(companyId, branchId, postingDate, deliveryUid, currency, cogsLegs)`** (REQUIRES_NEW, null-on-anomaly), `sourceType = COGS`, `sourceRef = deliveryUid`;
- null-avg edge (no established cost) → skip the COGS leg, WARN + anomaly, qty still moves (the ADR-0020 D-2 edge, unchanged).

`MovementType` is **unchanged** — the delivery issue is a `SALE_ISSUE` movement (the existing type; no new movement type, no `chk_stock_movement_type` widen). The only difference from a direct-invoice issue is `source_document_type = 'DELIVERY'` and `source_document_uid = deliveryUid` (vs `'SALES_INVOICE'` / invoiceUid).

**(3) The SO-sourced invoice posts revenue only and NEVER issues stock.** `sales_invoices` gains three additive columns (D-8): `origin` (`DIRECT | SALES_ORDER`, DEFAULT `'DIRECT'`), `source_order_uid` (VARCHAR(26), nullable), `source_delivery_uid` (VARCHAR(26), nullable). An invoice raised from a delivery (D-10) is created with `origin = 'SALES_ORDER'`, `source_order_uid`, `source_delivery_uid`. **`SaleFinalisedPayload` gains one additive boolean field `issuesStock`** (DEFAULT true for backward-safety):

```java
public record SaleFinalisedPayload(
        String invoiceUid, Long companyId, Long branchId, Instant finalisedAt,
        List<LineItem> lines,
        boolean issuesStock   // NEW — false when origin = SALES_ORDER (the delivery already issued)
) { ... }
```

`SalesInvoiceServiceImpl.finalise` sets `issuesStock = (inv.getOrigin() == DocumentOrigin.DIRECT)` when building the payload (line ~304). **`SaleIssueStockHandler.handle` gains a one-line guard at the top:** `if (!payload.issuesStock()) { guard.markProcessed(...); return; }` — an SO-sourced finalise is dedup-marked and **does no stock work**. The **revenue/AR/VAT posting on finalise is completely unchanged** for both origins (the existing AR/GL handlers fire on `SALE.FINALISED` as today — `ArSalePostedHandler` still opens the AR item for a credit-customer SO invoice; the revenue journal still posts). **A DIRECT walk-in invoice keeps `issuesStock = true` and issues on finalise exactly as today — zero change to that path.**

**(4) Void / reversal robustness.** Voiding an SO-sourced invoice emits `SALE.VOIDED`; `SaleReversalStockHandler` looks up `SALE_ISSUE` movements by `source_document_uid = invoiceUid` — **finds none** (the SO invoice never issued; the delivery issued under `source_document_uid = deliveryUid`), logs the existing "no SALE_ISSUE movements found" anomaly path, and **no-ops cleanly** (verified `SaleReversalStockHandler` lines 83-89). So a void of the revenue-only invoice correctly reverses *only revenue/AR/VAT* and never touches stock — the stock reversal belongs to the **return** (D-11), which reverses the *delivery's* issue. This is exactly right: the invoice void undoes the bill; the return undoes the shipment.

#### Why COGS CANNOT double-count (the impossibility argument)

COGS is posted by exactly one code path: a `SALE_ISSUE` stock movement + its `postCogsInNewTx` journal, keyed `sourceType = COGS, sourceRef = <document uid>`. For an SO-sourced sale:
1. **The delivery is the only issuer.** `DELIVERY.CONFIRMED` → `DeliveryIssueStockHandler` posts the `SALE_ISSUE` (sourceRef = deliveryUid) + the COGS journal. This happens **once** per delivery — guarded by `IdempotencyGuard("STOCK.DELIVERY_ISSUE", event.uid)` (a redelivered event is skipped) and backstopped by `uq_stock_movement_source_event (source_event_uid, product_id)` (a duplicate movement row is rejected by the DB). BR-SO-17 / NFR-SO-02 satisfied.
2. **The SO-sourced invoice never issues.** Its `SALE.FINALISED` carries `issuesStock = false`; `SaleIssueStockHandler` returns immediately. There is **no second issue path** — the invoice posts revenue only. So for an SO sale, COGS posts **once at the delivery**, never at the invoice.
3. **A DIRECT invoice issues once, has no delivery.** A walk-in invoice has no delivery in front of it and `issuesStock = true`; it issues at finalise exactly as today, and there is no `DELIVERY.CONFIRMED` for it. So COGS posts **once at finalise**, never twice.
4. **The two channels are mutually exclusive per sale.** A given sale is either DIRECT (issues at finalise, no delivery) or SALES_ORDER (issues at delivery, invoice is revenue-only). No sale traverses both issue paths. The `origin` column makes the channel explicit and the `issuesStock` flag makes the skip mechanical; the delivery-owns-the-event design (b) means the SO-sourced finalise *never even carries* a stock event to a handler that issues. **Double-count is structurally impossible**: there is no execution in which the same sold quantity reaches the issue path twice.

The defensive `issuesStock` flag (option a) is redundant *given* option (b) — but it is cheap insurance: if a future refactor ever made an SO-sourced invoice emit `SALE.FINALISED` with stock lines (it should not), the flag still blocks the issue. Belt-and-braces on a finance-grade invariant (BR-SO-09, a release blocker) is warranted.

### D-7 — `deliveries` + `delivery_lines` tables

#### `deliveries` (header)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_delivery_uid` |
| `company_id` / `branch_id` | BIGINT | NO | tenant FKs |
| `delivery_number` | VARCHAR(30) | NO | `DEL-####`; at create (D-12); `uq_delivery_company_number UNIQUE (company_id, delivery_number)` |
| `sales_order_id` | BIGINT | NO | FK → `sales_orders(id)` (intra-DB); the SO it fulfils |
| `sales_order_uid` | VARCHAR(26) | NO | scalar uid (for the outbox payload + cross-doc reads) |
| `status` | VARCHAR(20) | NO | `DeliveryStatus`; DEFAULT `'CONFIRMED'` (created confirmed, D-2); `chk_delivery_status CHECK (status IN ('DRAFT','CONFIRMED'))` |
| `customer_id` | BIGINT | NO | scalar FK (denormalised from the SO for reporting) |
| `delivery_date` | DATE | NO | drives the COGS posting period (NFR-SO-09) |
| `confirmed_at` | TIMESTAMPTZ | NO | DEFAULT now() |
| `cogs_gl_entry_uid` | VARCHAR(26) | YES | the COGS journal uid the handler posted (audit trace; set by handler via a follow-up, or left null — diagnostic only) |
| `notes` | VARCHAR(500) | YES | |
| `version` + audit | | | |

#### `delivery_lines` (child)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_delivery_line_uid` |
| `delivery_id` | BIGINT | NO | FK → `deliveries(id)` |
| `sales_order_line_id` | BIGINT | NO | FK → `sales_order_lines(id)`; the line this ships against |
| `sales_order_line_uid` | VARCHAR(26) | NO | scalar uid |
| `company_id` / `branch_id` | BIGINT | NO | denormalised |
| `line_no` | SMALLINT | NO | `uq_delivery_line_no UNIQUE (delivery_id, line_no)` |
| `product_id` / `product_code` / `product_name` | | NO | snapshot (copied from the SO line) |
| `unit_id` / `unit_name` | | NO | snapshot |
| `qty_delivered` | NUMERIC(19,6) | NO | `CHECK > 0`; in `unit_id` units |
| `qty_delivered_base` | NUMERIC(19,6) | NO | `CHECK > 0`; base units — drives the stock issue + the SO-line `qty_fulfilled_base += this` |
| `qty_invoiced_base` | NUMERIC(19,6) | NO | DEFAULT 0; running Σ invoiced from THIS delivery line (drives partial invoicing, D-10); `CHECK (qty_invoiced_base >= 0 AND qty_invoiced_base <= qty_delivered_base)` |
| `returned_qty_base` | NUMERIC(19,6) | NO | DEFAULT 0; running Σ returned against THIS delivery line (Stage 2, D-11); `CHECK (returned_qty_base >= 0 AND returned_qty_base <= qty_delivered_base)` |
| `issue_value_amount` | NUMERIC(19,4) | YES | the COGS value the delivery issued for this line (= Σ component issued values at delivery time); **stored so the return reverses at exactly the original issued cost** (D-11, OQ-SO-05) — NULL if avg was not established (no COGS posted) |
| `currency` | VARCHAR(3) | NO | |
| audit cols | | | |

Pricing/discount/VAT are **not** carried on the delivery line — the delivery is a stock+COGS document; the price/discount/VAT that drives the *invoice* lives on the SO line and is carried to the invoice at invoice-from-delivery time (D-10). The delivery line carries only the quantities and the issued cost.

> **`issue_value_amount` is the return's cost basis anchor (OQ-SO-05).** ADR-0020 D-5 reverses at the *original* issued value read from the `stock_movements.value_amount`. For a return against a delivery line, the engineer can read the original `SALE_ISSUE` movement rows by `source_document_uid = deliveryUid` (the ADR-0020 / `SaleReversalStockHandler` precedent) **and** has `issue_value_amount` on the delivery line as the convenient aggregate — both give the original cost. For a **partial** return, the reversal value is apportioned pro-rata: `returnValue = issue_value_amount × (returnQtyBase / qty_delivered_base)` (D-11), or — preferred for exactness — re-read the per-component movement values and pro-rate each, mirroring `SaleReversalStockHandler`. The engineer uses the movement-row read as authoritative; `issue_value_amount` is the audit/denormalised convenience.

### D-8 — `sales_invoices` additive columns for the seam (origin + source refs)

ALTER `sales_invoices` (additive, V18):

| column | type | null | default | notes |
|---|---|---|---|---|
| `origin` | VARCHAR(20) | NO | `'DIRECT'` | `DocumentOrigin` enum `DIRECT | SALES_ORDER`; `chk_sales_invoice_origin CHECK (origin IN ('DIRECT','SALES_ORDER'))`; **drives `issuesStock` in the finalise payload (D-6)** |
| `source_order_uid` | VARCHAR(26) | YES | NULL | the SO this invoice bills (when `origin = SALES_ORDER`) |
| `source_delivery_uid` | VARCHAR(26) | YES | NULL | the delivery this invoice bills (the partial-invoicing trace, D-10) |

`chk_sales_invoice_origin_refs CHECK ((origin = 'DIRECT' AND source_order_uid IS NULL AND source_delivery_uid IS NULL) OR (origin = 'SALES_ORDER' AND source_order_uid IS NOT NULL))` — a DIRECT invoice has no source refs; an SO-sourced invoice always names its order (delivery uid may be NULL only if a future aggregate-invoice spans deliveries — v1 always sets it, D-10). New enum `DocumentOrigin` in `sales.domain.enums`. **The `document_type` column is unchanged** (`INVOICE`); origin is a distinct axis from the channel discriminator.

### D-9 — Discounts: per-line + order-level, reusing `InvoiceTotalsCalculator` unchanged (BR-SO-10, OQ-SO-06)

**Decision: reuse the shipped `InvoiceTotalsCalculator` algorithm unchanged** (sales.md D-4): per line `lineNet = round(unitPrice × qty) − lineDiscount`; apportion the **order-level discount** across lines pro-rata to each line's `lineNet`; VAT = `round(discountedNet × vatRate)` per band; sum bands; HALF_UP at each boundary; identical backend/frontend (NFR-SO-03). This is the *identical* math the invoice already uses for line + document discount — the SO and the invoice agree to the cent by construction.

- **Where discount is stored:** per-line discount on `sales_order_line.line_discount_amount/percent` and `quotation_line.*`; order-level discount on `sales_orders.doc_discount_amount/percent` and `quotations.*` (the `sales_invoices.doc_discount_*` columns already exist, V5). The SO/quote roll-ups (`net_total/vat_total/gross_total`) are computed by a thin `SalesOrderTotalsCalculator` that **delegates to the same `InvoiceTotalsCalculator` algorithm** (or reuses it directly by adapting the SO lines into the calculator's line abstraction — the engineer extracts the shared compute into a calculator that operates on a line interface both `SalesInvoiceLine` and `SalesOrderLine`/`QuotationLine` satisfy; **no math change**, a refactor to share the one algorithm).
- **Flow to the invoice (D-10):** when invoicing a delivery, the invoice line copies the SO line's `unit_price_amount`, `line_discount_*`, `vat_status`, `vat_rate`, and the invoice header copies the SO's `doc_discount_*` **apportioned to the delivered subset** — `SalesOrderDiscountResolver` apportions the order-level discount to the lines being invoiced (the delivered qty) pro-rata, so a partial invoice carries its fair share of the order discount and `InvoiceTotalsCalculator` computes VAT on the discounted net exactly as for a direct invoice. The proof obligation (an integration test) is `Σ invoice nets across all deliveries of an SO == SO net`, within rounding (NFR-SO-03).

### D-10 — Partial invoicing from a delivery (FR-SO-12, BR-SO-08)

A new operation `SalesInvoiceService.createFromDelivery(deliveryUid)` (or a `DeliveryService` method delegating to it):
1. loads the delivery + its lines; for each delivery line with `qty_delivered_base − qty_invoiced_base > 0`, creates a `sales_invoice_line` for the not-yet-invoiced delivered qty, copying the **SO line's** pricing/discount/VAT snapshots (D-9);
2. creates the `sales_invoices` header with `origin = 'SALES_ORDER'`, `source_order_uid`, `source_delivery_uid`, customer/agent/currency from the SO;
3. the invoice goes through the **shipped channel** unchanged: `addLine`/recompute via `InvoiceTotalsCalculator`, `finalise` allocates `INV-####`, posts revenue/AR/VAT, emits `SALE.FINALISED` **with `issuesStock = false`** (D-6) — **no stock re-issue**;
4. on finalise, increments `delivery_line.qty_invoiced_base` and the mapped `sales_order_line.qty_invoiced_base` (D-4), then recomputes the SO `status` (D-2).

**Granularity: invoice per delivery** (OQ-SO-04 default — the clean delivery↔invoice trace via `source_delivery_uid`). One SO → several deliveries → several invoices. The guard `i <= qty_delivered_base − qty_invoiced_base` (BR-SO-11) is enforced both in the service and by the `delivery_line` CHECK. An SO is `INVOICED`/`CLOSED` per the D-2 rollup when `Σ qty_invoiced_base == Σ qty_fulfilled_base == Σ qty_ordered_base`.

### D-11 — Sales return / RMA (Stage 2): stock back in at original cost + a `RETURN` credit note (FR-SO-14, BR-SO-13, OQ-SO-05)

#### `sales_returns` (header) + `sales_return_lines` (child)

`sales_returns`: `id`/`uid` (`uq_sales_return_uid`), `company_id`/`branch_id`, `return_number` VARCHAR(30) (`RET-####` at create; `uq_sales_return_company_number`), `status` VARCHAR(20) (`SalesReturnStatus`, DEFAULT `'CONFIRMED'`; `chk_sales_return_status CHECK (status IN ('DRAFT','CONFIRMED'))`), `delivery_id` BIGINT FK → `deliveries(id)` + `delivery_uid` VARCHAR(26) (the return is **against a delivery**), `sales_order_uid` VARCHAR(26), `customer_id` BIGINT scalar FK, `return_date` DATE, `credit_note_uid` VARCHAR(26) (the AR credit note raised, set on confirm), `cogs_reversal_gl_entry_uid` VARCHAR(26) (diagnostic), `reason` VARCHAR(255), `net_amount`/`vat_amount`/`gross_amount` NUMERIC(19,4), `currency`, `@Version` + audit.

`sales_return_lines`: `id`/`uid` (`uq_sales_return_line_uid`), `sales_return_id` FK, `delivery_line_id` BIGINT FK → `delivery_lines(id)` + `delivery_line_uid` VARCHAR(26) (the line being returned), `company_id`/`branch_id`, `line_no` (`uq_sales_return_line_no`), `product_id`/`product_code`/`product_name`/`unit_id`/`unit_name` snapshots, `qty_returned` NUMERIC(19,6) `CHECK > 0` + `qty_returned_base` NUMERIC(19,6) `CHECK > 0`, the pricing/discount/VAT snapshots copied from the delivery's SO line (for the credit-note value), `net_amount`/`vat_amount`/`gross_amount`, `currency`, audit.

#### The return flow (`SalesReturnService.create`, one transaction, created CONFIRMED)

For each return line (guard `qty_returned_base <= delivery_line.qty_delivered_base − delivery_line.returned_qty_base` — BR-SO-11, enforced in service + the `delivery_line` CHECK):
1. **Stock IN at original issued cost.** Recipe-explode if composed; for each stockable component/line, post a stock IN movement (**`MovementType.SALE_REVERSAL`** — the existing type the `SaleReversalStockHandler` uses; no new type) via `StockPostingService.post(... +qty ... sourceDocumentType = "SALES_RETURN", sourceDocumentUid = returnUid ... unitCost, value)` where the cost is the **original issued cost** read from the delivery's `SALE_ISSUE` movement rows (or apportioned from `delivery_line.issue_value_amount` for a partial return — D-7), and call **`InventoryValuationService.reverseIssue(companyId, branchId, productId, originalValue)`** (restores `on_hand_value` at the original cost, avg unchanged — the ADR-0020 D-5 precedent). Post **DR `1300 INVENTORY` / CR `5100 COGS`** at the returned original value via **`InventoryGlPoster.postSaleReversalInNewTx(...)`** (REUSE the existing method; `sourceType = COGS`, `sourceRef = returnUid`).
2. **Credit note (REUSE `ArCreditNoteService`).** Call `ArCreditNoteService.raise(new RaiseCreditNoteRequest(companyUid, customerUid, arInvoiceUid, returnDate, returnNet, returnVat, currency, reason))` with **a new `origin = RETURN`** (D-8 of AR — see migration). `arInvoiceUid` is the **AR open item of the SO-sourced invoice that billed the returned delivery** (resolved via `source_delivery_uid` → the invoice → its AR item) so the credit reduces the right receivable; if the delivered qty was not yet invoiced, the credit note is raised **unapplied** (`arInvoiceUid = null`, the existing `ArCreditNoteServiceImpl` path) and stands as an open credit. The credit note posts **DR Sales Revenue + DR VAT / CR AR** (the existing `ArCreditNoteServiceImpl` legs, unchanged).
3. increment `delivery_line.returned_qty_base`; set `sales_returns.credit_note_uid`; audit.

**Concurrency / idempotency.** The stock-in + COGS-reversal happen in the return-create TX (synchronous, like the delivery issue is event-driven; the return could equally emit a `DELIVERY.RETURNED` event consumed by a `DeliveryReturnStockHandler` mirroring the delivery handler — **recommended for symmetry with D-6**: the return create publishes `DELIVERY.RETURNED` (payload `DeliveryReturnedPayload(returnUid, deliveryUid, companyId, branchId, lines, originalIssueValues)`) consumed by a `DeliveryReturnStockHandler` under `IdempotencyGuard("STOCK.DELIVERY_RETURN")`, exactly the D-6 shape). The credit note is raised synchronously in the return TX (AR is a synchronous service, ADR-0014). **Decision: stock-in + COGS via the outbox event (symmetry, idempotency, crash-safety — NFR-SO-06); credit note synchronous in the return TX.** This mirrors the delivery (event-driven stock) + the invoice (synchronous AR) split exactly.

#### AR change required (within `ar`, additive)

- `ArCreditNoteOrigin` enum gains `RETURN` (alongside `STANDALONE`, `SALE_VOID`).
- `chk_ar_credit_note_origin` widened (V18) to `IN ('STANDALONE','SALE_VOID','RETURN')` — the additive `DROP/ADD CONSTRAINT` pattern (V11 untouched).
- `ArCreditNoteService.raise` accepts the origin (today it hard-codes `STANDALONE` at `ArCreditNoteServiceImpl` line 165 — **the engineer threads the origin through `RaiseCreditNoteRequest`**, an additive field `ArCreditNoteOrigin origin` defaulting `STANDALONE`, so the return passes `RETURN` and existing callers are unaffected). This is the one AR touch; it is *within* `ar` (sales calls `ar.service` returning a DTO — D-13).

### D-12 — Numbering: four new `code_sequence` kinds (QUOTE / SO / DELIVERY / RETURN)

`OrderToCashNumberGenerator` reuses the shipped `code_sequence` row-locked allocation (ADR-0007 D-6) with four new `entity_kind` values: `QUOTE` (`QUOTE-%04d`), `SALES_ORDER` (`SO-%04d`), `DELIVERY` (`DEL-%04d`), `SALES_RETURN` (`RET-%04d`), per company, concurrency-safe (NFR-SO-07). Allocation timing (OQ-SO-07): `QUOTE-####` at **send**, `SO-####` at **create**, `DEL-####` / `RET-####` at **create**. No new numbering table — only new `entity_kind` rows (created lazily with `next_value = 1` on first use, the shipped mechanism). The `uq_<doc>_company_number` constraints backstop generator bugs.

### D-13 — ArchUnit edges (no cycle)

- **`sales.service`/`sales.events` → `stock.service`** — the delivery's reservation primitive (`StockReservationService`) and (via the outbox) the stock issue. Plus **`sales` → `stock.domain.dto`/`stock.domain.enums`** (the `DeliveryConfirmedPayload` lives in `sales.domain.dto`; the consumer `DeliveryIssueStockHandler` lives in `stock.events` and imports `sales.domain.dto` — the **same direction** `SaleIssueStockHandler` already imports `sales.domain.dto.SaleFinalisedPayload`, verified). **Allowed** — stock-consumes-sales-payload is the shipped pattern.
- **`sales.service` → `ar.service`** — `ArCreditNoteService.raise` for the return credit note (Stage 2). **Allowed** — the same cross-module-service-call stance `ap.service → gl.service` takes; AR returns a DTO, sales imports no AR entity.
- **`sales` → `products`/`parties`** (DTO reads — already shipped, unchanged).
- **No edge `stock → sales.entity` and no edge `ar → sales`.** The stock handler reads the sales-owned *payload DTO* (not entities); AR reads nothing from sales. Direction: `sales → stock`, `sales → ar`, both `→ gl`. **No cycle** — stock/ar/gl do not depend on sales. (`stock.events` importing `sales.domain.dto` is a *dto* dependency, not a service/entity cycle — the same allowance `SaleIssueStockHandler` already relies on.)
- The shipped `ModuleBoundaryTest` enforces controller↛repository, service↛controller, audit-append-only — **none of these edges violates an active rule** (verified against the ADR-0020 D-12 documented allowances).

## Consequences

**Positive**
- The full Order-to-Cash spine ships on the existing channels: the invoice channel posts revenue/AR/VAT (unchanged math), the ADR-0020 valuation engine is now driven by the **delivery** (and reversed by the **return**), and the AR credit-note service raises the return's credit. No engine is rebuilt — only the **trigger** moves (sales-orders.md §10.3).
- **COGS double-count is structurally impossible** (D-6): the delivery is the sole issuer for an SO sale, the SO-sourced invoice carries `issuesStock = false`, and a given sale traverses exactly one issue path. Belt-and-braces (option b *and* the flag) on a release-blocker invariant (BR-SO-09).
- Soft reservation is a single additive `reserved_qty` column on the row that already locks (`@Version`); available = `quantity − reserved_qty`; over-reservation is allowed and surfaces as negative available (backorders, BR-SO-05). No new lock, no new ledger.
- The change is additive and surgical: 8 new tables, 1 `stock_on_hand` column, 3 `sales_invoices` columns, 1 `SaleFinalisedPayload` field, 1 `SaleIssueStockHandler` guard line, 1 widened AR origin CHECK + enum value, 2 new event constants + 2 new handlers, 4 new `code_sequence` kinds. **V1–V17 frozen.**
- Discounts reuse `InvoiceTotalsCalculator` unchanged — SO totals and invoice totals agree to the cent (NFR-SO-03).

**Negative / costs**
- The seam touches three modules in one release: sales (the documents + the `issuesStock` flag), stock (the new `DeliveryIssueStockHandler` + `reserved_qty` + reservation primitive + the return handler), ar (the `RETURN` origin). Cross-module coordination — flagged in the touch list. The `SaleFinalisedPayload` field is additive on a record; the producer is updated in the same release, so no in-flight event carries a missing flag (defensive default `true`).
- `qty_fulfilled_base` / `qty_invoiced_base` / `qty_reserved_base` are maintained denormalisations on the SO line that MUST stay tied to the deliveries/invoices/reservations; the line CHECKs (`fulfilled ≤ ordered`, `invoiced ≤ fulfilled`) are the DB backstop, but a service bug in the rollup is a correctness defect. Tests must assert the line-sum ↔ document ties after every action.
- The single-enum `status` with two DTO dimensions (D-2) is a modelling compromise to keep `status` filterable; the engineer must implement the total rollup function exactly. Documented to prevent a future reader "simplifying" it into ambiguity.
- The return reverses at the original issued cost (D-11) — requires reading the original `SALE_ISSUE` movement rows (the ADR-0020 `SaleReversalStockHandler` precedent); a partial return apportions pro-rata. The engineer must use the movement-row read as authoritative, not the convenience `issue_value_amount` alone, for multi-component exactness.

**Neutral / deferred**
- Single location, base currency, moving-average only (inherited from ADR-0020 / sales-orders.md §2). POS, drop-ship, multi-warehouse allocation, advanced ATP/MRP, blanket/recurring orders, complex pricing/promotions, delivery-note PDFs, refund tenders on returns, multi-currency O2C — all deferred, none precluded (NFR-SO-08). The `reserved_qty` column + the SO-line reservation record are the foundation multi-location allocation builds on (a per-location reservation ledger, additive).

## Alternatives considered

- **The stock-issue seam — delivery-owns-the-event (b) vs invoice-flag-skip (a).** *Decided: (b) as primary, (a) as belt-and-braces.* (a) alone keeps `SaleIssueStockHandler` consuming every `SALE.FINALISED` and relies on a flag check to skip — one missed flag and COGS double-counts; the issue path is still *reachable* for an SO sale. (b) makes the SO-sourced finalise emit no stock-bearing event at all — the issue path is *unreachable*, the structurally safer design (the requirement's recommended default, OQ-SO-03). We add (a)'s flag anyway because it is one line and defends against a future refactor regressing (b). Rejecting (a)-alone; rejecting (b)-without-the-flag (cheap insurance forgone).
- **Reservation model — `reserved_qty` column vs a `stock_reservations` ledger table.** *Decided: column + the SO line as the reservation record.* A ledger duplicates the (company, branch, product) key, needs its own version/lock, and a write on the hot reserve/release path, with no traceability benefit (the SO line already carries `qty_reserved_base` + a uid). The column is the lean choice at single-location scope (OQ-SO-01 recommended default). A ledger is the right structure for multi-location allocation (deferred) — additive then, not now.
- **New event vs reuse `SALE.FINALISED` for the delivery.** *Decided: a new `DELIVERY.CONFIRMED` event.* Reusing `SALE.FINALISED` from a delivery would conflate the bill with the shipment (the invoice and the delivery are separate documents at different times — sales-orders.md vocabulary), force the revenue handlers to distinguish, and break the clean "delivery owns stock, invoice owns revenue" split. A distinct event is the boring, legible choice; the payload reuses the `SaleFinalisedPayload.LineItem` shape so the consumer logic is shared.
- **Discount — per-line + header (apportioned) vs a separate discount table / per-line-only.** *Decided: per-line + header-apportioned, reusing `InvoiceTotalsCalculator` unchanged.* This is the shipped invoice behaviour (sales.md D-4); matching it guarantees SO↔invoice agreement to the cent (NFR-SO-03). A discount-rule engine (tiers/promotions) is deferred (§2). Header-only or line-only would not match the ratified scope (FR-SO-13 requires both).
- **SO status — single enum vs two orthogonal enums (fulfilment × invoicing).** *Decided: a single stored headline enum + two derived DTO dimensions.* Two stored enums would need every filter/report to combine them; one stored enum is filterable but lossy at the PARTIALLY_FULFILLED+PARTIALLY_INVOICED overlap — resolved by exposing the two dimensions as derived DTO labels (D-2). The total rollup function makes the stored value deterministic.
- **Delivery / return lifecycle — single-step CONFIRMED vs a DRAFT→CONFIRMED workflow.** *Decided: single-step (created CONFIRMED) in v1*, since picking/packing is deferred (§2). The `DRAFT` value is reserved in both enums for the future pick/pack workflow — additive, not built.

## Open items (OQ-SO — recommended defaults adopted; none blocks the build)

- **OQ-SO-01 — reservation model:** adopted **`reserved_qty` column on `stock_on_hand` + the SO line as the per-order reservation record** (no separate ledger). Settled.
- **OQ-SO-02 — over-reservation:** adopted **allow + flag** (negative available); backorders supported. Owner (sales policy) may flip to block-on-over-reserve (a one-line service guard). Default stands.
- **OQ-SO-03 — invoice-origin / stock-issue seam:** adopted **delivery owns `DELIVERY.CONFIRMED` (option b) + `issuesStock` flag on the finalise payload (option a) belt-and-braces**; `origin = DIRECT|SALES_ORDER` on `sales_invoices`. Settled — the load-bearing decision.
- **OQ-SO-04 — release timing / invoicing granularity / cancel:** adopted **release the delivered portion at delivery + the remaining at cancel; invoice per delivery (`source_delivery_uid` trace); cancel only the undelivered balance.** Settled.
- **OQ-SO-05 — return cost basis + credit-note origin:** adopted **original issued cost** (read from the delivery's `SALE_ISSUE` movement rows / apportioned for partials, ADR-0020 D-5 `reverseIssue`) + a **`RETURN` credit-note origin**. Settled.
- **OQ-SO-06 — discount rounding:** adopted **reuse `InvoiceTotalsCalculator` unchanged** (HALF_UP, pro-rata order discount before VAT, identical backend/frontend). Owner confirms display dp before go-live (presentation only).
- **OQ-SO-07 — quote→order + confirm edit + numbering timing:** adopted **keep the quoted pricing on conversion, SO opens DRAFT; a confirmed line is changed by cancel-and-re-raise or an explicit re-confirm that re-reserves (no silent edit of a reserved line); allocate `QUOTE-####` at send, `SO-####` at create, `DEL-####`/`RET-####` at create.** Settled.
- **OQ-SO-08/09/10 (deferred):** POS as a further channel, multi-warehouse/location-aware reservation+allocation, refund tenders on returns — all deferred (§2), none precluded (NFR-SO-08).
- **OQ-CUR-03 (carried):** HALF_UP, TZS 0-dp display, reuse `InvoiceTotalsCalculator` + the ADR-0020 average precision. Confirm before go-live; does not block the model.

## Staged build split (the increment is large — §10.4)

**Stage 1 — the O2C spine (quote → SO → reserve → deliver → COGS+seam → invoice-from-delivery + discounts):**
- Tables: `quotations`, `quotation_lines`, `sales_orders`, `sales_order_lines`, `deliveries`, `delivery_lines`; ALTER `stock_on_hand ADD reserved_qty`; ALTER `sales_invoices ADD origin / source_order_uid / source_delivery_uid`.
- Code: `Quotation`/`SalesOrder`/`Delivery` entities + services + controllers; `OrderToCashNumberGenerator` (QUOTE/SO/DELIVERY kinds); `StockReservationService` (the `reserved_qty` primitive); the seam — `DeliveryConfirmedPayload` + `DELIVERY.CONFIRMED` constant + `DeliveryIssueStockHandler` (REUSE `costIssue`/`InventoryGlPoster`); the `SaleFinalisedPayload.issuesStock` field + the `SaleIssueStockHandler` guard + the finalise origin-set; `createFromDelivery` partial invoicing; the shared discount calculator (reuse `InvoiceTotalsCalculator`); the SO rollup function (D-2/D-4).
- Permissions: `SALES.QUOTE.*`, `SALES.ORDER.*`, `SALES.DELIVERY.VIEW/CREATE` (the invoice rides existing `SALES.INVOICE.*`).
- Migration: V18 blocks (1)–(7) below (everything except the return tables + the AR origin widen, which are pulled forward harmlessly or split — see note).

**Stage 2 — returns / RMA (additive on the spine):**
- Tables: `sales_returns`, `sales_return_lines`.
- Code: `SalesReturn` entity + service + controller; `DeliveryReturnedPayload` + `DELIVERY.RETURNED` constant + `DeliveryReturnStockHandler` (REUSE `reverseIssue`/`postSaleReversalInNewTx`); the `RaiseCreditNoteRequest.origin` field + `ArCreditNoteOrigin.RETURN` + the `chk_ar_credit_note_origin` widen.
- Permissions: `SALES.RETURN.VIEW/CREATE`.

> **Migration note:** V18 is a single additive migration covering all 8 tables + ALTERs + seeds (a migration is atomic and additive; splitting it across two files (`V18` spine, `V19` returns) is equally valid and lets Stage 2 ship its own migration). **Recommendation: one `V18__sales_orders.sql` with the Stage-2 return tables + the AR origin widen included** (DDL is cheap and additive; the *code* is what stages). If the PM prefers a hard table-level cut, Stage 2's tables + the AR widen move to `V19__sales_returns.sql` — the engineer's call with the PM. Either way the table/column/constraint names above are fixed.

### V18 migration ordering (additive; V1–V17 FROZEN; #12-safe seeds)

`V18__sales_orders.sql`, in order (each block additive; never edits V1–V17 DDL):
1. **CREATE** `quotations`, `quotation_lines` (+ constraints/indexes per D-3).
2. **CREATE** `sales_orders`, `sales_order_lines` (+ the progress/reserved CHECKs, D-3/D-4).
3. **CREATE** `deliveries`, `delivery_lines` (+ the invoiced/returned CHECKs, D-7).
4. **CREATE** `sales_returns`, `sales_return_lines` (Stage 2 tables — DDL pulled forward; D-11).
5. **ALTER `stock_on_hand`** — `ADD COLUMN reserved_qty NUMERIC(19,6) NOT NULL DEFAULT 0` + `ADD CONSTRAINT chk_stock_on_hand_reserved_nonneg CHECK (reserved_qty >= 0)`.
6. **ALTER `sales_invoices`** — `ADD COLUMN origin VARCHAR(20) NOT NULL DEFAULT 'DIRECT'` + `source_order_uid` + `source_delivery_uid` VARCHAR(26) NULL + `chk_sales_invoice_origin` + `chk_sales_invoice_origin_refs` (D-8). (Existing FINALISED/VOID rows back-fill to `DIRECT` — correct: they were all direct walk-in invoices.)
7. **`chk_ar_credit_note_origin` widen** — `DROP/ADD CONSTRAINT` adding `'RETURN'` to the existing IN-list (keep `STANDALONE`,`SALE_VOID`; the V11 additive-widen pattern).
8. **permission seed + `ORG_ADMIN` grant** — INSERT `SALES.QUOTE.VIEW/CREATE/SEND/ACCEPT`, `SALES.ORDER.VIEW/CREATE/CONFIRM/CANCEL`, `SALES.DELIVERY.VIEW/CREATE`, `SALES.RETURN.VIEW/CREATE` (module `sales`) `ON CONFLICT (code) DO NOTHING`; grant all to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (the V7/V12/V14/V17 pattern). (Permissions have no `uid` — #12 N/A.)

**`code_sequence` kinds** (QUOTE / SALES_ORDER / DELIVERY / SALES_RETURN) are **not** pre-seeded — they are created lazily on first use by `OrderToCashNumberGenerator` (the shipped `code_sequence` mechanism, ADR-0007 D-6), so no seed rows and **no #12 seed-uid exposure** for numbering. The only per-company CROSS-JOIN seeds in V18 are the permission grants (no uid). **Therefore V18 has no #12-vulnerable per-company seed-uid** (no `chart_of_accounts`/`gl_configs`-style per-company-uid inserts). `MigrationKeepDataIT` extends to V18 (the back-fill of `sales_invoices.origin = 'DIRECT'` on existing rows + the additive columns are verified keep-data-safe). **No journal source-type widen needed** — the delivery COGS post reuses `JournalSourceType.COGS` (admitted since V17); the return reversal reuses `COGS` with `reversalOfId`/distinct `sourceRef`; the credit note reuses `AR_CREDIT_NOTE`. **No new movement type** — delivery issue = `SALE_ISSUE`, return = `SALE_REVERSAL` (both existing; `chk_stock_movement_type` untouched).

---

## Summary

ADR-0021 designs the **Order-to-Cash spine** in `com.erp.modules.sales`: eight new tables (`quotations`+`quotation_lines`, `sales_orders`+`sales_order_lines`, `deliveries`+`delivery_lines`, `sales_returns`+`sales_return_lines`), four lifecycle enums with service-guarded transitions and a deterministic line-quantity rollup, a soft reservation model (`reserved_qty` on `stock_on_hand`, available = `quantity − reserved_qty`, over-reservation allowed/flagged), the load-bearing stock-issue seam, per-line + order-level discounts reusing `InvoiceTotalsCalculator` unchanged, partial invoicing from a delivery, and returns reversing COGS at the original issued cost plus a `RETURN`-origin credit note.

**The seam (D-6):** the **delivery owns** a new `DELIVERY.CONFIRMED` outbox event consumed by a new `DeliveryIssueStockHandler` that REUSES `InventoryValuationService.costIssue` + `InventoryGlPoster.postCogsInNewTx` (DR COGS / CR Inventory at moving average, recipe explosion). The SO-sourced invoice carries `origin = SALES_ORDER` and a `SaleFinalisedPayload.issuesStock = false` flag; `SaleIssueStockHandler` skips it. A DIRECT walk-in invoice (`issuesStock = true`) keeps issuing on finalise **unchanged**. Revenue/AR/VAT on finalise is unchanged for both. **COGS cannot double-count**: a given sale traverses exactly one issue path (delivery for SO sales, finalise for direct), guarded by `IdempotencyGuard` + the `uq_stock_movement_source_event` DB backstop; the SO-sourced finalise emits no stock-bearing event at all (option b) and the flag (option a) is redundant insurance.

**Stage-1 / Stage-2 build split:**
- **Stage 1 (O2C spine):** quotations + sales-orders + deliveries tables; `stock_on_hand.reserved_qty`; `sales_invoices.origin`/source-refs; `StockReservationService`; `DELIVERY.CONFIRMED` + `DeliveryIssueStockHandler`; `SaleFinalisedPayload.issuesStock` + the handler guard + finalise origin-set; `createFromDelivery` partial invoicing; the shared discount calculator; the SO rollup. Perms `SALES.QUOTE.*`/`SALES.ORDER.*`/`SALES.DELIVERY.*`.
- **Stage 2 (returns/RMA):** sales_returns tables; `DELIVERY.RETURNED` + `DeliveryReturnStockHandler` (REUSE `reverseIssue`/`postSaleReversalInNewTx`); `ArCreditNoteOrigin.RETURN` + `RaiseCreditNoteRequest.origin` + the `chk_ar_credit_note_origin` widen. Perms `SALES.RETURN.*`.

**Readiness:** the ADR is concrete enough to build V18 + the full model without guessing a rule — every table, column, constraint name, enum, transition, rollup formula, event/payload/handler, idempotency key, discount flow, and return basis is specified. **Additive on frozen V1–V17.** **#12-safe** (V18 has no per-company seed-uid inserts — numbering kinds are lazy, the only CROSS-JOIN seed is the uid-less permission grant). **Cross-module touch list:** (1) **sales → stock** — the delivery-issue (`DELIVERY.CONFIRMED` → `DeliveryIssueStockHandler`) + the `reserved_qty` reservation primitive; (2) **sales → ar** — the return credit note (`ArCreditNoteService.raise` with the new `RETURN` origin); (3) **the `SaleIssueStockHandler` origin-skip** — the one-line `if (!payload.issuesStock()) return;` guard + the `SaleFinalisedPayload.issuesStock` field + the finalise origin-set. **COGS cannot double-count** — the impossibility argument is in D-6.
