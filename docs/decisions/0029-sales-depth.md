# 0029 — Sales Depth (POS / advanced pricing / drop-ship / blanket-standing) data model: a till-session wrapper over the shipped DIRECT invoice channel (POS sale = a `DIRECT` invoice tagged with a `pos_session_id`, no new costing seam) with drawer open/close/reconcile and over/short posted to a new POS_CASH_OVER/POS_CASH_SHORT GL pair; a single deterministic price-resolution layer in `products` (customer-specific > active promotion > quantity tier > base list) feeding POS/SO/quote/invoice lines identically while `InvoiceTotalsCalculator` math stays unchanged; drop-ship SO lines that issue NO own stock and recognise COGS at the supplier bill cost through a reused GRNI clearing leg; and blanket/standing orders (commitment + call-off draw-down, template + scheduled generation) — all additive across `sales` + `products` on the frozen V1–V19 as V42–V45

- **Status:** Proposed
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (consuming [docs/requirements/sales-depth.md](../requirements/sales-depth.md) — FR-SD-01..24, BR-SD-01..20, NFR-SD-01..09, §7 flows, §10 accepted boundary, §11 OQ log). **Owner ratification of the v1 scope is OQ-SD-00 (pending);** the eleven design seams (the POS no-new-seam mechanism OQ-SD-01, the variance GL treatment OQ-SD-02, the pricing precedence/stacking OQ-SD-03, the rule-home OQ-SD-04, promotion granularity OQ-SD-05, till↔account binding OQ-SD-06, refund authority OQ-SD-07, X/Z-read shape OQ-SD-08, drop-ship COGS recognition OQ-SD-09, standing pricing/trigger OQ-SD-10, blanket basis OQ-SD-11) are the **decisions this ADR makes**, not blockers — the *behaviour* is fixed by the requirements.
- **Context source:** [docs/requirements/sales-depth.md](../requirements/sales-depth.md) + [docs/PATH-TO-FULL-ERP.md](../PATH-TO-FULL-ERP.md) §3.3. Verified against the **shipped** code:
  - **Sales / O2C** ([ADR-0008](0008-sales-data-model.md) / V5 + [ADR-0021](0021-sales-orders-data-model.md) / V18-V19): `SalesInvoice`/`SalesInvoiceLine`/`SalesInvoicePayment` (`sales_invoices` — `id`, `uid` VARCHAR(26), `company_id`, `branch_id`, `document_type` CHECK `IN ('INVOICE')`, `invoice_number` nullable-until-finalise, `status` ∈ {DRAFT,FINALISED,VOID}, `customer_id`/`agent_id` scalar, `origin` `DocumentOrigin` ∈ {DIRECT,SALES_ORDER} DEFAULT `'DIRECT'`, `source_order_uid`/`source_delivery_uid`, `doc_discount_*`, `net/vat/gross_total_amount`, `tax_summary` JSONB, `@Version`); `sales_invoice_payments` (`tender_type` `TenderType` ∈ {CASH,MOBILE_MONEY}, `amount`, denormalised `company_id`/`branch_id`); `InvoiceTotalsCalculator.recompute(inv, lines)` (tax-exclusive per-band VAT + line/doc discount apportionment — **REUSED UNCHANGED**, D-7); `SalesInvoiceServiceImpl.create/addLine/addPayment/finalise/voidInvoice` (finalise allocates `INV-####`, freezes totals, posts revenue/AR/VAT, emits `SALE.FINALISED` with `issuesStock = (origin == DIRECT)` — **the channel POS reuses verbatim**, D-1); `SaleFinalisedPayload(invoiceUid, companyId, branchId, finalisedAt, List<LineItem>, boolean issuesStock)`; `SalesOrder`/`SalesOrderLine` (`sales_orders` — `order_number` `SO-####`, `status` `SalesOrderStatus`, the `qty_ordered_base`/`qty_reserved_base`/`qty_fulfilled_base`/`qty_invoiced_base` line counters, the D-2/D-4 rollup); `Delivery`/`DeliveryLine` (`deliveries` — `DEL-####`, emits `DELIVERY.CONFIRMED`); `DeliveryConfirmedPayload`; `StockReservationService.reserve(companyId, branchId, productId, deltaBase, actorId)` (the soft-reservation primitive on `stock_on_hand.reserved_qty`); `code_sequence` numbering (ADR-0007 D-6, `entity_kind` discriminator, lazy create on first use); `DocumentOrigin` enum in `sales.domain.enums` (**this ADR adds `POS`**).
  - **Products / pricing** ([ADR-0007](0007-products-data-model.md) / V3): `price_lists` (`PriceList` — `company_id`, user-supplied `code` `RETAIL`/`WHOLESALE`, `status` `MasterStatus`, **no uid? — has uid via `UidEntity`**), `product_prices` (`ProductPrice` — `product_id`+`price_list_id`+denormalised `company_id`, `@Embedded Money price`, **no uid** — addressed by product_uid+price_list_uid); `PriceListService`/`PriceListServiceImpl`; the customer's price-list assignment on `customers`; `Product`/`ProductComponent` (single-level recipe, the `RecipeExplosionResolver` Stock owns). **This ADR's pricing rules live in `products` (OQ-SD-04) extending this foundation.**
  - **Parties** ([ADR-0006](0006-parties-data-model.md) / V2): `customers` (`Customer` — `CASH_WALK_IN`/`CREDIT_ACCOUNT`, the assigned price list, `credit_limit_amount`), `agents`. Customer-specific prices key (customer_id, product_id).
  - **Stock / Valuation** ([ADR-0010](0010-stock-data-model.md) / V7 + [ADR-0020](0020-inventory-valuation-data-model.md) / V17): `StockPostingService.post(... MovementType ... unitCost, value)`, `StockOnHand` (`avg_cost`/`on_hand_value`/`reserved_qty`), `InventoryValuationService.costIssue/reverseIssue`, `InventoryGlPoster.postCogsInNewTx(companyId, branchId, postingDate, sourceRef, currency, List<CogsLeg>)` (DR COGS 5100 / CR INVENTORY 1300, REQUIRES_NEW null-on-anomaly), `SaleIssueStockHandler` (consumes `SALE.FINALISED`, `issuesStock` guard — **a POS sale is `issuesStock = true`, issues exactly as a walk-in invoice, no change**). **A drop-ship line is NOT in own stock — D-12 deliberately bypasses this engine.**
  - **Purchases / P2P** ([ADR-0011](0011-purchases-data-model.md) / V8 + [ADR-0015](0015-accounts-payable-data-model.md) / V12): `purchase_orders`/`po_lines`, `goods_receipts`/`goods_receipt_lines` (`unit_cost_amount`/`line_cost_amount` — the cost input), `STOCK.RECEIVED`, `BillMatchServiceImpl.postMatchedBillToGl` (DR PURCHASES/GRNI / CR AP), the shipped 3-way match. **A drop-ship PO is raised through this channel, ship-to-customer (D-12).**
  - **Cash & Bank** ([ADR-0016](0016-cash-and-bank-data-model.md) / V13): `CashBankAccount` (`cash_bank_accounts` — `company_id`, nullable `branch_id`, `code`, `account_type` `CashBankAccountType`, `currency`, `gl_account_id` → CoA 1xxx, `is_default`); `cash_transactions`; the receipt/payment cash-leg flow. **A till binds to a `cash_bank_account` (the drawer); the session recognises its net cash here (D-3).**
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / V10 + ADR-0020 / V17): `GLPostingService.post(JournalEntryDraft)→JournalEntryDto` + `GLPostingSafeInvoker.postInNewTx` (REQUIRES_NEW null-on-anomaly); `GLConfigResolver.resolve(companyId, GlConfigKey)→ChartOfAccount`; `GlConfigKey` (`SALES_REVENUE`,`VAT_PAYABLE`,`ACCOUNTS_RECEIVABLE`,`CASH`,`INVENTORY`,`COGS`,`ACCOUNTS_PAYABLE`,`BAD_DEBT_EXPENSE`,`OPENING_BALANCE_EQUITY`,`PURCHASES`,`VAT_INPUT`,`VAT_DUE`,`WHT_PAYABLE`,`WHT_RECEIVABLE`,`RETAINED_EARNINGS`,`GRNI`,`STOCK_ADJUSTMENT` — **this ADR adds `POS_CASH_OVER` + `POS_CASH_SHORT`**); `JournalSourceType` CHECK currently `('MANUAL','SALES','SALES_REVERSAL','OPENING_BALANCE','AR_RECEIPT','AR_WRITEOFF','AR_CREDIT_NOTE','AP_BILL','AP_PAYMENT','AP_DEBIT_NOTE','CASH_TRANSFER','CASH_DIRECT','VAT_RETURN','YEAR_END_CLOSE','STOCK_RECEIPT','COGS','STOCK_ADJUSTMENT','OPENING_INVENTORY')` (**this ADR adds `POS_VARIANCE`**; drop-ship COGS reuses `COGS`); `ChartOfAccountServiceImpl.DEFAULT_ACCOUNTS` (1000 Cash … 5160 Stock Adjustment — **this ADR adds `4900 Cash Over (Income)` + `5170 Cash Short / Till Shortage (Expense)`**) + `GlConfigServiceImpl.DEFAULT_MAPPINGS`.
  - **Outbox / idempotency** ([ADR-0009](0009-transactional-outbox.md)): `OutboxPublisher.publish(eventType, aggregateType, aggregateId, aggregateUid, companyId, branchId, payload)`; `DomainEventType` constants (`SALE.FINALISED`/`SALE.VOIDED`/`STOCK.RECEIVED`/`STOCK.RECEIPT.VOIDED`/`DELIVERY.CONFIRMED`/`DELIVERY.RETURNED` — **this ADR adds `DROPSHIP.FULFILLED` + `STANDING_ORDER.GENERATED`**); `DomainEventHandler` + `IdempotencyGuard.alreadyProcessed(consumer, uid)`/`markProcessed`; `processed_events(consumer, event_uid)`.
  - **Money** ([ADR-0005](0005-money-and-currency.md)): base currency only (TZS), `NUMERIC(19,4)`, HALF_UP.
  - **Security** ([ADR-0002](0002-rbac-enforcement.md)): `@perm.has('PERM')` / `@perm.scoped(#uid,'targetType','PERM')` (NEVER `hasAuthority`); `ScopeGuard.companyIdOf(targetType, uid)` switch + `assertCanActIn`. **This ADR adds target types `possession`, `till`, `blanketorder`, `standingorder`, `pricingrule` and the perms below.**
  - [[db-naming-convention]] verified against V1–V19 (plural masters/owned-children `pos_tills`/`pos_sessions`/`pos_session_payouts`/`price_tiers`/`customer_prices`/`promotions`/`blanket_orders`/`blanket_order_lines`/`standing_orders`/`standing_order_lines`; singular constraint roots `uq_`/`fk_`/`chk_` on the singular entity; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id`/`branch_id` BIGINT scalar; additive `DROP/ADD CONSTRAINT` widen; the **junction is singular** `role_permission`). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **Latest shipped migration is `V19__sales_returns.sql`. This module is assigned V42–V45 (additive; V1–V19 + any V20–V41 reserved by parallel modules FROZEN). Next ADR is 0029.**

This ADR is the **technical data model + integration design** for Sales Depth (PATH-TO-FULL-ERP §3.3: POS, advanced pricing, drop-ship, blanket/standing orders). It translates the ratified requirements into: the POS till/session documents + the drawer-reconciliation arithmetic + the over/short GL pair; the price-resolution rule layer (tiers/customer-prices/promotions) in `products` with a deterministic precedence and a single resolver feeding every sales channel; the drop-ship SO-line variant that issues no own stock and recognises COGS at the supplier cost via a reused GRNI clearing leg; the blanket/standing-order documents + the call-off draw-down + the scheduled generation; every table/column/constraint name, enum, service, controller+endpoint, event/payload/handler, GL config key + CoA account, ScopeGuard case, permission, and Angular nav route; the V42–V45 migration ordering with **#12-safe seeds**; and the ArchUnit edges. It is **concrete enough that the engineer builds without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step.

## Context

The shipped O2C spine (ADR-0021) gives quote → SO → reserve → deliver → invoice → return on a `DIRECT`/`SALES_ORDER` invoice channel, with a flat price-list price + line/doc discount and own-stock fulfilment only. Four depth gaps remain (sales-depth.md §1): **no till / cashier session** (a walk-in is a bare invoice with no drawer accountability), **no pricing rules** (every special price is a manual override, untraceable as a rule), **no drop-ship** (every SO fulfils from own stock), and **no blanket/standing orders** (every order is a one-shot). All four are additive on the spine. The forces:

- **POS must NOT introduce a new costing seam (the top risk, OQ-SD-01).** ADR-0021's whole achievement was making COGS double-count structurally impossible by routing each sale through exactly one issue path. A POS sale is a counter sale with goods leaving on finalise — i.e. **exactly the `DIRECT` invoice channel** (`issuesStock = true`, issues stock + COGS on finalise). If POS forked a parallel sale document or a second issue path, it would reopen the double-count risk. **Decision: a POS sale IS a `DIRECT` `sales_invoices` row tagged with a `pos_session_id`** — it reuses the entire shipped finalise/issue/COGS/revenue/tender path with zero change; the session is a pure attachment for drawer reconciliation. Resolved in **D-1/D-2/D-3**.

- **The drawer must reconcile to the books (BR-SD-03/04).** A session's expected cash is `opening_float + Σ cash tenders − Σ cash payouts`; the counted-vs-expected variance (over/short) must post to GL and the session's net cash recognise against the till's cash/bank account. The variance needs a new income/expense account pair (over → income, short → expense). Resolved in **D-3/D-4**.

- **Pricing rules must resolve a number, then stop (BR-SD-06, the second risk).** The rule layer (tiers/customer-prices/promotions) must compute the `unit_price` *before* the line is built and then hand off to the **unchanged** `InvoiceTotalsCalculator`. A rule that reached into the VAT/totals/COGS math would corrupt the cent-exact agreement ADR-0008/0021 depend on. **Decision: one `PriceResolutionService` in `products` returning (unitPrice, ruleDiscount, priceSource) by a deterministic precedence, consumed identically by POS/SO/quote/invoice.** Resolved in **D-6/D-7**.

- **Drop-ship must issue NO own stock and cost at the supplier price (the third risk, OQ-SD-09).** A drop-ship line's goods never enter own inventory, so the delivery must not decrement `stock_on_hand` or post moving-average COGS; the cost of sale is the supplier's bill cost. Recognising it wrongly either invents phantom stock or mis-states margin. **Decision: a drop-ship SO line carries a `fulfilment_mode = DROP_SHIP`; confirm raises a ship-to-customer PO; fulfilment marks the SO line delivered WITHOUT a stock event and posts COGS at the supplier cost DR COGS / CR GRNI (reusing the shipped GRNI bridge — the AP bill clears it on match, exactly as a normal receipt does).** Resolved in **D-9/D-10/D-11**.

- **Blanket/standing must reuse the SO, not fork it (BR-SD-14/15/16).** A blanket is a framework the call-off SOs draw against; a standing order is a template that generates child SOs. Neither posts on its own; both produce ordinary SOs. **Decision: `blanket_orders` + `standing_orders` are new lightweight documents; call-offs are ordinary `sales_orders` carrying a `source_blanket_uid`; generated SOs carry `source_standing_uid`.** Resolved in **D-12/D-13/D-14**.

- **Schema freeze / direction.** IAM=V1 … Sales-Returns=V19 (plus any V20–V41 reserved by parallel modules), all frozen. Sales Depth is additive **V42–V45**: new POS / pricing / blanket / standing tables, additive columns on `sales_invoices` (`pos_session_id`) and `sales_order_lines` (`fulfilment_mode` + supplier/PO link + drop-ship cost) and `product_prices`/`price_lists` (tier link), two new CoA accounts + two new `gl_config` keys + one `JournalSourceType` token + two new `DomainEventType` constants + several new `code_sequence` kinds + the new permissions + new `DocumentOrigin.POS`. It imports no foreign *entity*: `sales` reaches `products.service` (the resolver), `purchases.service` (the drop-ship PO), `cashbank.service` (the session cash leg), `gl.service` (the variance + drop-ship COGS) the same way `ap.service` reaches `gl.service` (D-15).

## Decision

### D-1 — Module placement: POS, drop-ship, and blanket/standing live in `com.erp.modules.sales`; the pricing rule layer lives in `com.erp.modules.products` (OQ-SD-04)

POS / drop-ship / blanket / standing are **sales documents** — they live in **`com.erp.modules.sales`** (it owns the invoice channel POS reuses, the SO spine drop-ship/blanket/standing extend, the delivery the drop-ship fulfilment mirrors). The **pricing rule layer** (tiers / customer-prices / promotions + the resolver) lives in **`com.erp.modules.products`** — it extends `price_lists` / `product_prices` (the pricing master), and `sales` already reads Products' pricing DTOs; putting it in `sales` would make `sales` own pricing data that belongs next to the product price (OQ-SD-04, recommended default). Sales gains outbound edges to `products.service` (the resolver), `purchases.service` (the drop-ship PO), `cashbank.service` (the session cash leg), and `gl.service` (the variance + drop-ship COGS).

Internal layout (additive):

```
com.erp.modules.sales
├── domain.entity   PosTill, PosSession, PosSessionPayout,
│                   BlanketOrder, BlanketOrderLine,
│                   StandingOrder, StandingOrderLine
│                   (+ additive cols on SalesInvoice.posSessionId, SalesOrderLine.fulfilmentMode/supplierId/dropshipPoUid/dropshipUnitCost)
├── domain.dto      PosTillDto/CreatePosTillRequest, PosSessionDto/OpenSessionRequest/CloseSessionRequest/ReconcileSessionRequest,
│                   PosSaleRequest (rings a sale on a session), XReadDto, ZReadDto,
│                   BlanketOrderDto/CreateBlanketOrderRequest/BlanketOrderLineDto,
│                   StandingOrderDto/CreateStandingOrderRequest/StandingOrderLineDto,
│                   DropshipFulfilledPayload (NEW outbox payload, D-11),
│                   StandingOrderGeneratedPayload (NEW outbox payload, D-14)
├── domain.enums    PosSessionStatus, PosPayoutType, FulfilmentMode, BlanketStatus, StandingStatus, StandingFrequency (D-2)
├── repository      PosTillRepository, PosSessionRepository, PosSessionPayoutRepository,
│                   BlanketOrderRepository, BlanketOrderLineRepository,
│                   StandingOrderRepository, StandingOrderLineRepository
├── service         PosTillService(+Impl), PosSessionService(+Impl) (open/close/reconcile/X/Z),
│                   PosSaleService(+Impl) (rings the DIRECT invoice in a session, D-2),
│                   DropshipService(+Impl) (the SO-line drop-ship flag + the linked PO, D-9/D-10),
│                   BlanketOrderService(+Impl), StandingOrderService(+Impl) (D-12/D-13/D-14),
│                   SalesDepthNumberGenerator (POS_SESSION/BLANKET_ORDER/STANDING_ORDER via code_sequence, D-16)
└── events          DropshipFulfilStockNoopHandler  (consumes DROPSHIP.FULFILLED, posts supplier-cost COGS, D-11)

com.erp.modules.products
├── domain.entity   PriceTier (child of product_prices), CustomerPrice, Promotion (D-6)
├── domain.dto      PriceTierDto, CustomerPriceDto, PromotionDto + create/update requests,
│                   ResolvePriceRequest, ResolvedPriceDto (D-7)
├── domain.enums    PromotionTarget, PromotionEffect (D-6)
├── repository      PriceTierRepository, CustomerPriceRepository, PromotionRepository
└── service         PricingRuleService(+Impl) (CRUD on tiers/customer-prices/promotions),
                    PriceResolutionService(+Impl)  (the single resolver, D-7)
```

Controllers stay flat in `com.erp.api`: `PosTillController`, `PosSessionController`, `PosSaleController`, `BlanketOrderController`, `StandingOrderController` (sales) + `PricingRuleController` (products). They touch only services (`ModuleBoundaryTest`).

**Boundary note (D-15):** every module reads **DTOs only** across boundaries (never foreign entities/repositories). Cross-module references persisted in sales tables are **scalar `Long` id + `String` uid** with real DB FKs *within sales* only (no cross-module `@ManyToOne`). The drop-ship PO effect, the session cash leg, the variance/COGS postings, and the price resolution go through **service-layer calls / outbox events** returning DTOs (the `ap.service → gl.service` precedent, ADR-0020 D-1).

### D-2 — Lifecycle + status enums

New enums (the exact sets + transitions; every transition service-guarded, audited, append-only — NFR-SD-04):

**`PosSessionStatus`** (sales, FR-SD-02/03/04, BR-SD-02/03/04):

```
OPEN ──close──▶ CLOSED ──reconcile──▶ RECONCILED   (terminal, immutable)
```
- `POS-####` allocated at **open**. Exactly one OPEN session per till (enforced by a partial-unique index, D-3). Selling/refunds only while OPEN; reconcile only from CLOSED.

**`PosPayoutType`** (sales): `REFUND` (cash paid out on a POS refund), `PAID_OUT` (a misc cash payout — drawer-to-safe drop / petty payout). v1 only `REFUND` is written by a flow; `PAID_OUT` reserved for the deferred drawer-drop feature (additive, not built).

**`FulfilmentMode`** (sales, on the SO line, FR-SD-15, BR-SD-11): `OWN_STOCK` (DEFAULT — the shipped delivery path), `DROP_SHIP` (the supplier ships; no own-stock issue). A back-fill of existing `sales_order_lines` to `OWN_STOCK` is correct (every existing line is own-stock).

**`BlanketStatus`** (sales, FR-SD-20/21, BR-SD-14/15): `OPEN ──(fully drawn OR window expired OR manual)──▶ CLOSED` (terminal). A blanket posts nothing; CLOSED ends draw-down.

**`StandingStatus`** (sales, FR-SD-22/23, BR-SD-16): `ACTIVE ──pause──▶ PAUSED ──resume──▶ ACTIVE`; `ACTIVE|PAUSED ──cancel──▶ CANCELLED` (terminal). Generation runs only against ACTIVE.

**`StandingFrequency`** (sales): `WEEKLY`, `MONTHLY`, `QUARTERLY` (the schedule period; v1 set). Drives the next-due-date computation.

**`PromotionTarget`** (products, FR-SD-11): `PRODUCT`, `CATEGORY`, `ALL`. **`PromotionEffect`** (products): `PERCENT_DISCOUNT`, `AMOUNT_DISCOUNT`, `OVERRIDE_PRICE`.

### D-3 — POS tables: `pos_tills`, `pos_sessions`, `pos_session_payouts`; the one-open-session invariant; the drawer arithmetic

All tables: plural names; `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) ULID `uq_<root>_uid`; `company_id` + `branch_id` BIGINT NOT NULL; standard audit cols; `@Version` on the mutable headers. Money `NUMERIC(19,4)`.

#### `pos_tills` (master)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | BIGINT / VARCHAR(26) | NO | `uq_pos_till_uid` |
| `company_id` / `branch_id` | BIGINT | NO | tenant; `fk_pos_till_company` / `fk_pos_till_branch` |
| `code` | VARCHAR(30) | NO | user-supplied short code; `uq_pos_till_company_code UNIQUE (company_id, code)` |
| `name` | VARCHAR(120) | NO | |
| `cash_bank_account_id` | BIGINT | NO | scalar FK → `cash_bank_accounts(id)` — the drawer's money location (OQ-SD-06); `fk_pos_till_cashaccount` |
| `status` | VARCHAR(20) | NO | `MasterStatus` (ACTIVE/INACTIVE) DEFAULT `'ACTIVE'`; `chk_pos_till_status` |
| `version` + audit | | | |

#### `pos_sessions` (header)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_pos_session_uid` |
| `company_id` / `branch_id` | BIGINT | NO | tenant FKs |
| `session_number` | VARCHAR(30) | NO | `POS-####` at open (D-16); `uq_pos_session_company_number UNIQUE (company_id, session_number)` |
| `pos_till_id` | BIGINT | NO | FK → `pos_tills(id)`; `fk_pos_session_till` |
| `status` | VARCHAR(20) | NO | `PosSessionStatus`; DEFAULT `'OPEN'`; `chk_pos_session_status CHECK (status IN ('OPEN','CLOSED','RECONCILED'))` |
| `cashier_id` | BIGINT | NO | FK → `app_users(id)`; the opening cashier |
| `opening_float_amount` | NUMERIC(19,4) | NO | declared at open; `CHECK >= 0` |
| `opened_at` | TIMESTAMPTZ | NO | DEFAULT now() |
| `closed_at` / `reconciled_at` | TIMESTAMPTZ | YES | transition stamps |
| `counted_cash_amount` | NUMERIC(19,4) | YES | declared at close; `CHECK (counted_cash_amount IS NULL OR counted_cash_amount >= 0)` |
| `counted_mobile_amount` | NUMERIC(19,4) | YES | declared at close (OQ-SD-02 — mobile reconciled separately) |
| `expected_cash_amount` | NUMERIC(19,4) | YES | computed at close = `opening_float + Σ cash tenders − Σ cash payouts` (BR-SD-03) |
| `variance_amount` | NUMERIC(19,4) | YES | `counted_cash − expected_cash` (+ over / − short); stamped at close |
| `variance_gl_entry_uid` | VARCHAR(26) | YES | the variance journal uid posted at reconcile (diagnostic) |
| `reconciled_by` | BIGINT | YES | FK → `app_users(id)` |
| `notes` | VARCHAR(500) | YES | |
| `version` + audit | | | |

Constraints: `fk_pos_session_company`/`_branch`/`_till`/`_cashier`; **the one-open-session invariant** as a **partial unique index** `ux_pos_session_one_open ON pos_sessions (pos_till_id) WHERE status = 'OPEN'` (Postgres partial unique — the boring, race-safe enforcement of BR-SD-02; the open service also checks for friendly errors). The X/Z reads are **computed on demand** from the session's attached invoices + payouts (OQ-SD-08 — no separate persisted Z document); the close stamps `counted_*`/`expected_*`/`variance_*`.

#### `pos_session_payouts` (child — cash leaving the drawer)

`id`, `uid` (`uq_pos_session_payout_uid`), `pos_session_id` FK (`fk_pos_session_payout_session`), `company_id`/`branch_id` (denormalised), `payout_type` VARCHAR(20) (`PosPayoutType`; `chk_pos_session_payout_type CHECK (payout_type IN ('REFUND','PAID_OUT'))`), `amount` NUMERIC(19,4) `CHECK > 0`, `source_invoice_uid` VARCHAR(26) NULL (the refunded POS sale, for `REFUND`), `reason` VARCHAR(255) NULL, audit. A refund's cash payout writes one row; it feeds the expected-drawer subtraction.

**The drawer arithmetic (PosSessionServiceImpl.close):**
```
cashTenders   = Σ sales_invoice_payments.amount  WHERE invoice.pos_session_id = this AND tender_type = 'CASH'
cashPayouts   = Σ pos_session_payouts.amount      WHERE pos_session_id = this AND payout_type IN ('REFUND','PAID_OUT')
expected_cash = opening_float_amount + cashTenders − cashPayouts
variance      = counted_cash_amount − expected_cash        // + over, − short
```
The mobile leg is reconciled separately (`counted_mobile_amount` vs `Σ MOBILE_MONEY tenders`) and surfaced on the close DTO; only the **cash** variance posts to GL (OQ-SD-02). The amounts are computed from the session's own legs, never free-typed (BR-SD-03).

### D-4 — POS GL: the over/short pair + the reconcile posting; the session cash recognition

Two new CoA accounts (seeded per company, D-17) + two new `GlConfigKey` (D-17):

| account code | name | type / normal balance | gl_config key |
|---|---|---|---|
| `4900` | Cash Over (Till Surplus) | INCOME / CREDIT | `POS_CASH_OVER` |
| `5170` | Cash Short / Till Shortage | EXPENSE / DEBIT | `POS_CASH_SHORT` |

**Reconcile posting (`PosSessionServiceImpl.reconcile`, the operator's TX, synchronous — a human act, so it posts via `GLPostingService.post` directly, BR-INV-12 precedent: a missing config fails the operator's command):**

- **Over** (`variance > 0`, more cash than expected): **DR `CASH` (the till's `gl_account_id`) / CR `POS_CASH_OVER` 4900** for `variance`.
- **Short** (`variance < 0`): **DR `POS_CASH_SHORT` 5170 / CR `CASH` (the till's account)** for `|variance|`.
- **Zero variance**: no variance journal.

`sourceType = POS_VARIANCE` (the new token, D-17), `sourceRef = session uid`. The **session's net sale cash** (the sum of cash tenders) is **already** recognised against the books by each POS sale's finalise (the shipped `DIRECT` revenue posting DR CASH / CR Sales Revenue, ADR-0008/0014) — **the POS sale uses the till's cash account as its cash leg** (the finalise resolves the `CASH` config; OQ-SD-06 recommended: the till's bound cash/bank account is the cash leg for its sessions' sales). **Decision:** the per-sale revenue posting recognises the cash on each finalise; the reconcile posts **only the variance** + recognises any over/short, not the gross takings again (no double-count). The till↔cash-account binding makes the per-session cash traceable to one GL cash account.

> **Note on the CASH leg for POS sales (OQ-SD-06).** A POS sale's finalise must post its cash tender to the **till's** `cash_bank_account.gl_account_id`, not a generic `CASH` config, so multiple tills with distinct drawers reconcile independently. **Decision:** when `PosSaleService` builds the `DIRECT` invoice for a session, it passes the till's cash account so the finalise cash leg resolves to it (the cash leg already resolves a cash account; this threads the till's account through — a small, additive parameter on the cash-sale path, not a change to the revenue math). If the shipped finalise hard-codes a single `CASH` config and cannot accept a per-sale cash account, the engineer adds an optional cash-account override on the cash-sale path (additive); flagged as the one Sales touch on the invoice channel.

### D-5 — POS sale = a `DIRECT` invoice tagged with `pos_session_id` (OQ-SD-01); the no-new-seam guarantee

**`sales_invoices` gains one additive column** (D-17): `pos_session_id` BIGINT NULL (FK → `pos_sessions(id)`, `fk_sales_invoice_pos_session`). A non-null `pos_session_id` marks an invoice as a POS sale attached to that session; NULL = a back-office invoice (every existing row back-fills NULL — correct). **`DocumentOrigin` gains a `POS` value** (alongside `DIRECT`, `SALES_ORDER`) so the channel is explicit on the invoice; **a POS invoice issues stock exactly like a DIRECT one**: `issuesStock = (origin == DIRECT || origin == POS)`. The `chk_sales_invoice_origin` CHECK is widened to admit `'POS'` (D-17).

`PosSaleService.ring(sessionUid, PosSaleRequest)`:
1. assert the session is OPEN + `assertCanActIn`; resolve each line's price via `PriceResolutionService` (D-7);
2. create a `sales_invoices` row with `origin = POS`, `pos_session_id = session.id`, customer = the request's customer or the POS walk-in CASH_WALK_IN default, the till's cash account threaded for the cash leg (D-4);
3. add lines + tender(s) + compute change through the **shipped** `addLine`/`addPayment` path;
4. **finalise through the shipped `SalesInvoiceServiceImpl.finalise`** — it allocates `INV-####`, posts revenue/AR/VAT, and emits `SALE.FINALISED` with **`issuesStock = true`** (POS is a DIRECT-class origin), so the shipped `SaleIssueStockHandler` issues stock + posts COGS at the moving average **once** (the existing path; recipe explosion applies). **No new event, no new handler, no second issue path** (BR-SD-01).

**Why POS cannot double-count COGS:** a POS sale is one `sales_invoices` finalise → one `SALE.FINALISED` (`issuesStock = true`) → `SaleIssueStockHandler` issues once (guarded by `IdempotencyGuard` + the `uq_stock_movement_source_event` DB backstop). It has no delivery (the ADR-0021 `SALES_ORDER`-origin seam does not apply) so no `DELIVERY.CONFIRMED` fires. The channel is mutually exclusive per sale: a POS sale is DIRECT-class (issues at finalise, no delivery), never SALES_ORDER-class. **The double-count impossibility argument of ADR-0021 D-6 holds unchanged** — POS adds no new issue path.

### D-6 — Pricing rule tables in `products`: `price_tiers`, `customer_prices`, `promotions`

All tables: plural; `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) (`uq_<root>_uid`); `company_id` BIGINT NOT NULL (denormalised; pricing is **company-scoped, not branch** — like `product_prices`); audit; `@Version` on the mutable masters. Money `NUMERIC(19,4)`.

#### `price_tiers` (child of `product_prices` — quantity breaks on a list price)

`product_prices` has **no uid** today (addressed by product_uid + price_list_uid). A tier needs a stable parent reference. **Decision:** `price_tiers` references the parent by **(`product_id`, `price_list_id`)** scalar FKs (the `product_prices` natural key) — not by a `product_price_id`, since `ProductPrice` exposes no uid and the engineer addresses prices by the pair already.

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_price_tier_uid` |
| `company_id` | BIGINT | NO | denormalised; `fk_price_tier_company` |
| `product_id` | BIGINT | NO | scalar FK → `products(id)`; `fk_price_tier_product` |
| `price_list_id` | BIGINT | NO | scalar FK → `price_lists(id)`; `fk_price_tier_pricelist` |
| `min_qty` | NUMERIC(19,6) | NO | the break floor (inclusive), in the product's sell unit; `CHECK > 0` |
| `unit_price_amount` | NUMERIC(19,4) | NO | the tier price; `CHECK >= 0` |
| `currency` | VARCHAR(3) | NO | base currency |
| `status` | VARCHAR(20) | NO | `MasterStatus` DEFAULT `'ACTIVE'`; `chk_price_tier_status` |
| `version` + audit | | | |

Constraints: `uq_price_tier_break UNIQUE (product_id, price_list_id, min_qty)` (no duplicate break floor → tiers partition the quantity axis cleanly, BR-SD-07); the service validates **no gaps/overlaps** on save (consecutive `min_qty` floors, ascending). The resolver picks the tier with the greatest `min_qty <= lineQty`. A product with no tier rows resolves to the flat `product_prices` price (back-compatible — FR-SD-09).

#### `customer_prices` (a contract price for a (customer, product))

`id`, `uid` (`uq_customer_price_uid`), `company_id`, `customer_id` BIGINT scalar FK → `customers(id)` (`fk_customer_price_customer`), `product_id` BIGINT scalar FK → `products(id)` (`fk_customer_price_product`), `unit_price_amount` NUMERIC(19,4) `CHECK >= 0`, `currency` VARCHAR(3), `effective_from` DATE NULL, `effective_to` DATE NULL (`chk_customer_price_window CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)`), `status` `MasterStatus` DEFAULT `'ACTIVE'` (`chk_customer_price_status`), `@Version` + audit. `uq_customer_price_scope UNIQUE (customer_id, product_id)` (one active contract price per pair; the window narrows applicability — a future multi-window per pair is additive). The resolver applies it when active for the customer + date (BR-SD-08).

#### `promotions` (a time-boxed discount/override rule)

`id`, `uid` (`uq_promotion_uid`), `company_id`, `code` VARCHAR(30) (`uq_promotion_company_code UNIQUE (company_id, code)`), `name` VARCHAR(120), `target` VARCHAR(20) (`PromotionTarget`; `chk_promotion_target CHECK (target IN ('PRODUCT','CATEGORY','ALL'))`), `target_product_id` BIGINT NULL (FK → `products(id)`; set when target=PRODUCT), `target_category` VARCHAR(60) NULL (the product category string; set when target=CATEGORY — OQ-SD-05: reuses the product's existing category attribute, no new category master in v1), `effect` VARCHAR(20) (`PromotionEffect`; `chk_promotion_effect CHECK (effect IN ('PERCENT_DISCOUNT','AMOUNT_DISCOUNT','OVERRIDE_PRICE'))`), `effect_value` NUMERIC(19,4) `CHECK >= 0` (a percent 0..100 when PERCENT_DISCOUNT — `chk_promotion_pct CHECK (effect <> 'PERCENT_DISCOUNT' OR effect_value BETWEEN 0 AND 100)`), `effective_from` DATE NOT NULL, `effective_to` DATE NOT NULL (`chk_promotion_window CHECK (effective_to >= effective_from)`), `priority` SMALLINT NOT NULL DEFAULT 0 (tiebreak when two promotions match — higher wins; OQ-SD-03), `status` `MasterStatus` DEFAULT `'ACTIVE'` (`chk_promotion_status`), `@Version` + audit. The resolver applies the active promotion matching (target, date) by precedence (D-7). `chk_promotion_target_ref CHECK ((target='PRODUCT' AND target_product_id IS NOT NULL) OR (target='CATEGORY' AND target_category IS NOT NULL) OR (target='ALL'))`.

### D-7 — The single price resolver + its deterministic precedence (BR-SD-06/10, OQ-SD-03)

`PriceResolutionService.resolve(ResolvePriceRequest(companyId, customerId, productId, quantity, businessDate, priceListId))→ResolvedPriceDto(unitPriceAmount, ruleDiscountAmount, ruleDiscountPercent, currency, priceSource)` where `priceSource ∈ {CUSTOMER_PRICE, PROMOTION, TIER, LIST_PRICE, NONE}`. The **deterministic precedence** (recommended default, OQ-SD-03 — single rule, no stacking):

```
1. CUSTOMER_PRICE  — active customer_prices for (customerId, productId) on businessDate → unit_price (no further discount)
2. PROMOTION       — active promotions matching (target=PRODUCT id | CATEGORY of product | ALL) on businessDate,
                     pick the highest `priority`, tiebreak lowest uid;
                       PERCENT_DISCOUNT/AMOUNT_DISCOUNT → list/tier price as base + a ruleDiscount;
                       OVERRIDE_PRICE → unit_price = effect_value (no further discount)
3. TIER            — the price_tiers row with greatest min_qty <= quantity, on the customer's assigned price list → unit_price
4. LIST_PRICE      — the flat product_prices price on the customer's assigned price list → unit_price   (today's behaviour)
5. NONE            — no price found → unit_price = null; the caller blocks the line (the shipped "no price" behaviour)
```

The resolver returns **one** number + the **source** (recorded on the line as a `price_source` audit string — the line already snapshots `list_price`/`unit_price`; the source is an additive diagnostic on the line, see D-8). It is **read-only** (no GL, no stock). **Pricing rules NEVER touch the totals math**: once the line carries the resolved `unit_price` (+ a rule discount mapped onto the line's existing `line_discount_amount/percent`), the **unchanged** `InvoiceTotalsCalculator` computes net/VAT/gross (BR-SD-06). A manual `SALES.INVOICE.OVERRIDE` typed price trumps the resolved one and is flagged/audited on the line as today (FR-SD-13). The resolver is consumed by `PosSaleService`, `SalesOrderService`/`QuotationService` (the existing line-add paths thread the resolver in place of the flat price lookup), and the direct-invoice path — **one resolver, every channel** (BR-SD-10), so POS and back-office agree to the cent (NFR-SD-03).

> **Performance (NFR-SD-05):** the resolver does at most three indexed lookups (customer_price by (customer,product), promotions by (company,target,date), tiers by (product,price_list,qty)) — all backed by indexes `ix_customer_prices_scope (customer_id, product_id)`, `ix_promotions_active (company_id, target, effective_from, effective_to)`, `ix_price_tiers_lookup (product_id, price_list_id, min_qty)`. No table scan on the POS hot path.

### D-8 — `sales_order_lines` + `sales_invoice_lines` additive columns for the price source + drop-ship

- **`sales_order_lines`** (additive, D-17): `fulfilment_mode` VARCHAR(20) NOT NULL DEFAULT `'OWN_STOCK'` (`FulfilmentMode`; `chk_sales_order_line_fulfilment CHECK (fulfilment_mode IN ('OWN_STOCK','DROP_SHIP'))`), `dropship_supplier_id` BIGINT NULL (scalar FK → `suppliers(id)`; set when DROP_SHIP), `dropship_po_uid` VARCHAR(26) NULL (the linked PO this line was placed on, D-10), `dropship_unit_cost_amount` NUMERIC(19,4) NULL (the supplier unit cost captured at fulfilment, D-11), `price_source` VARCHAR(20) NULL (the resolver source, D-7 — diagnostic). `chk_sales_order_line_dropship CHECK ((fulfilment_mode='OWN_STOCK' AND dropship_supplier_id IS NULL) OR (fulfilment_mode='DROP_SHIP' AND dropship_supplier_id IS NOT NULL))`.
- **`sales_invoice_lines`** (additive, D-17): `price_source` VARCHAR(20) NULL (the resolver source — diagnostic; existing rows back-fill NULL).

A drop-ship SO line is **excluded from the reservation step** at confirm (`StockReservationService.reserve` is not called for it — BR-SD-11) and excluded from the own-stock delivery path (D-10).

### D-9 — Drop-ship: confirm raises a ship-to-customer PO (FR-SD-15/16, BR-SD-11/12, OQ-SD-09)

On `SalesOrderService.confirm`, for the lines where `fulfilment_mode = DROP_SHIP`, `DropshipService` raises **one PO per supplier** through `purchases.service` (the shipped `PurchaseOrderService.create`), marked **ship-to-customer**:
- the PO carries the drop-ship quantities for that supplier's lines;
- the **ship-to** is the customer's delivery address (not the warehouse). **Decision (OQ-SD-09 contract):** Purchases' PO is assumed to accept a ship-to-customer indicator + a back-reference to the SO. If the shipped `purchase_orders` does **not** carry a ship-to-customer field, **this ADR designs to its expected contract**: the engineer adds an additive `ship_to_customer_id` BIGINT NULL + `source_sales_order_uid` VARCHAR(26) NULL on `purchase_orders` (a Purchases-owned additive column in V42 — the one Purchases touch, flagged in the cross-module list) OR, if Purchases prefers to own that change, it is split to a Purchases migration. **Recommended: the additive columns ride V42** (DDL is cheap; the *code* is Sales calling `PurchaseOrderService` with the new fields).
- the SO line records `dropship_po_uid` (and the engineer records the SO-line↔PO-line link for traceability, BR-SD-12).

Own-stock lines on the same SO confirm and reserve normally (the shipped path). A mixed SO (some own-stock, some drop-ship) is supported.

### D-10 — Drop-ship fulfilment: a delivery that issues NO own stock (FR-SD-17, BR-SD-11)

When the drop-ship PO is **received** (`STOCK.RECEIVED` for that PO — the supplier shipped) **or** a manual drop-ship-fulfil action fires, `DropshipService` marks the SO line **fulfilled**:
- it creates a **delivery record** (a `deliveries` row + `delivery_lines`, the shipped tables) for the drop-shipped quantity **but with a delivery-line marker that it issues no own stock** — concretely, the delivery for a drop-ship line **does not emit `DELIVERY.CONFIRMED` for that line / does not call the own-stock issue**. **Decision:** rather than overload the own-stock `DELIVERY.CONFIRMED` (which the `DeliveryIssueStockHandler` consumes to issue stock + post moving-average COGS), a drop-ship fulfilment emits the **new `DROPSHIP.FULFILLED`** event (D-11) consumed by a handler that posts **supplier-cost COGS** and **no stock movement**. The SO line's `qty_fulfilled_base` advances exactly as for own-stock (driving the D-2/D-4 rollup), but no `stock_movements` row is written for it (the goods never were in own stock — BR-SD-11).
- a drop-ship PO that is **not** yet received cannot fulfil the SO line (recommended: do not invoice before fulfilment so revenue + COGS land together — OQ-SD-09; the unhappy path §7.6).

> **Why a new event, not the own-stock delivery event:** the own-stock `DELIVERY.CONFIRMED` is contractually "issue this from own stock at the moving average". A drop-ship fulfilment is "recognise the supplier cost, touch no own stock". Reusing the own-stock event would force the `DeliveryIssueStockHandler` to branch on a drop-ship flag and *skip* the issue — the ADR-0021 "option a flag" fragility we rejected. A distinct event makes the no-own-stock path **structurally** unable to issue own stock (the legible, boring choice).

### D-11 — Drop-ship COGS at the supplier cost, via a reused GRNI clearing leg (FR-SD-17, BR-SD-13, OQ-SD-09)

`DROPSHIP.FULFILLED` (NEW constant, D-17), `aggregateType = "SALES_ORDER"`, payload:
```
DropshipFulfilledPayload(
    salesOrderUid, salesOrderLineUid, dropshipPoUid, companyId, branchId, fulfilledAt,
    List<LineItem(productId, productUid, unitId, qtyInBase, supplierUnitCostAmount)>
)
```
A new handler `DropshipFulfilCogsHandler` (in `sales.events`, under `IdempotencyGuard("SALES.DROPSHIP_COGS")`) consumes it:
- for each line, **posts no `stock_movements` row** (no own stock) and posts **one COGS journal** **DR `5100 COGS` / CR `2150 GRNI`** at `qtyInBase × supplierUnitCostAmount` via `GLPostingSafeInvoker.postInNewTx` (REQUIRES_NEW, null-on-anomaly), `sourceType = COGS`, `sourceRef = salesOrderLineUid`. **Decision (OQ-SD-09):** **reuse the shipped GRNI 2150 / `GRNI` key** — the goods-received-not-invoiced bridge already means "goods we owe the supplier for, not yet billed"; a drop-ship is exactly that shape. The supplier's bill, when matched in AP (the shipped `BillMatchServiceImpl`, which already debits `GRNI` to clear a perpetual receipt), **clears the GRNI** — so the cost flows: drop-ship fulfilment recognises COGS against GRNI; AP bill match clears GRNI against AP. No new account where the boring one fits. The supplier `unit_cost` comes from the drop-ship PO line (the cost input already on `goods_receipt_lines.unit_cost_amount` / `po_lines`).
- The customer is then **invoiced revenue-only** through the shipped channel (a `SALES_ORDER`-origin invoice, `issuesStock = false` — the existing ADR-0021 D-6 mechanism; the drop-ship line is delivered, so it invoices like any delivered SO line) (FR-SD-18). Revenue + COGS land in the same period if invoicing follows fulfilment (the recommended policy).

**COGS cannot mis-count for drop-ship:** the only COGS path for a drop-ship line is `DROPSHIP.FULFILLED → DropshipFulfilCogsHandler` (idempotent, `sourceRef = lineUid`); the line is `DROP_SHIP` so the own-stock `DeliveryIssueStockHandler` never touches it (it has no `DELIVERY.CONFIRMED` for that line) and the revenue-only invoice carries `issuesStock = false`. The line traverses exactly one COGS path (supplier-cost), never the moving-average path.

### D-12 — Blanket order tables + the call-off draw-down (FR-SD-20/21, BR-SD-14/15)

#### `blanket_orders` (header) + `blanket_order_lines` (child)

`blanket_orders`: `id`/`uid` (`uq_blanket_order_uid`), `company_id`/`branch_id`, `blanket_number` VARCHAR(30) (`BLK-####` at create; `uq_blanket_order_company_number`), `status` VARCHAR(20) (`BlanketStatus`; DEFAULT `'OPEN'`; `chk_blanket_order_status CHECK (status IN ('OPEN','CLOSED'))`), `customer_id` BIGINT scalar FK, `agent_id` BIGINT NULL, `currency` VARCHAR(3), `valid_from` DATE NOT NULL, `valid_until` DATE NOT NULL (`chk_blanket_order_window CHECK (valid_until >= valid_from)`), `closed_at` TIMESTAMPTZ NULL, `notes` VARCHAR(500) NULL, `@Version` + audit.

`blanket_order_lines`: `id`/`uid` (`uq_blanket_order_line_uid`), `blanket_order_id` FK (`fk_blanket_order_line_blanket`), `company_id`/`branch_id` (denormalised), `line_no` SMALLINT (`uq_blanket_order_line_no UNIQUE (blanket_order_id, line_no)`), `product_id`/`product_code`/`product_name`/`unit_id`/`unit_name` snapshots, `committed_qty_base` NUMERIC(19,6) `CHECK > 0` (the committed total in base units; quantity-basis — OQ-SD-11), `drawn_qty_base` NUMERIC(19,6) NOT NULL DEFAULT 0 (`CHECK (drawn_qty_base >= 0 AND drawn_qty_base <= committed_qty_base)` — **the over-draw guard at the DB**, BR-SD-15), `agreed_unit_price_amount` NUMERIC(19,4) (the agreed call-off price), `currency`, audit.

**Call-off (FR-SD-21):** a `sales_orders` row gains an additive `source_blanket_uid` VARCHAR(26) NULL (D-17). `BlanketOrderService.callOff(blanketUid, CreateSalesOrderRequest)` (or `SalesOrderService.createFromBlanket`) creates an ordinary SO whose lines draw from the blanket lines (at the agreed price), and **increments `blanket_order_line.drawn_qty_base`** by the called-off quantity in the **same TX, under the blanket's `@Version`** (NFR-SD-06 — no lost draw; the line CHECK is the DB backstop). The call-off SO confirms/reserves/delivers/invoices as a **normal SO** (the shipped O2C path). A blanket is **CLOSED** when every line is fully drawn, the window expires, or by manual close (FR-SD-21). The blanket posts **nothing** itself (BR-SD-14).

### D-13 — Standing order tables (FR-SD-22, BR-SD-16)

#### `standing_orders` (header) + `standing_order_lines` (child)

`standing_orders`: `id`/`uid` (`uq_standing_order_uid`), `company_id`/`branch_id`, `standing_number` VARCHAR(30) (`STD-####` at create; `uq_standing_order_company_number`), `status` VARCHAR(20) (`StandingStatus`; DEFAULT `'ACTIVE'`; `chk_standing_order_status CHECK (status IN ('ACTIVE','PAUSED','CANCELLED'))`), `customer_id` BIGINT scalar FK, `agent_id` BIGINT NULL, `currency` VARCHAR(3), `frequency` VARCHAR(20) (`StandingFrequency`; `chk_standing_order_frequency CHECK (frequency IN ('WEEKLY','MONTHLY','QUARTERLY'))`), `start_date` DATE NOT NULL, `next_run_date` DATE NOT NULL (the next due generation date; advanced after each run), `end_date` DATE NULL, `lock_pricing` BOOLEAN NOT NULL DEFAULT false (OQ-SD-10 — false = re-resolve at generation; true = use the locked line price), `last_generated_at` TIMESTAMPTZ NULL, `notes` VARCHAR(500) NULL, `@Version` + audit.

`standing_order_lines`: `id`/`uid` (`uq_standing_order_line_uid`), `standing_order_id` FK (`fk_standing_order_line_standing`), `company_id`/`branch_id`, `line_no` SMALLINT (`uq_standing_order_line_no UNIQUE (standing_order_id, line_no)`), `product_id`/`product_code`/`product_name`/`unit_id`/`unit_name` snapshots, `qty_base` NUMERIC(19,6) `CHECK > 0`, `locked_unit_price_amount` NUMERIC(19,4) NULL (used when `lock_pricing = true`), `currency`, audit.

### D-14 — Standing-order generation (FR-SD-23, BR-SD-16, OQ-SD-10)

`StandingOrderService.generate(standingUid)` (manual) + a `@Scheduled` sweep `generateDue()` (a simple daily poll: `WHERE status='ACTIVE' AND next_run_date <= today` — OQ-SD-10, manual + simple sweep). For each due standing order, in its own TX under `@Version`:
1. **idempotency / period guard:** compute the period key `standingUid + ':' + next_run_date`; if a child SO already exists for that key (a `sales_orders.source_standing_uid = standingUid AND order_date = next_run_date` check, or an `IdempotencyGuard("SALES.STANDING_GEN", periodKey)` mark), **skip** (BR-SD-18 — one child per period);
2. create an ordinary `sales_orders` (DRAFT) with `source_standing_uid = standingUid` (additive column, D-17), copying the standing lines; prices are **re-resolved** via `PriceResolutionService` at the run date (or the `locked_unit_price_amount` when `lock_pricing = true`);
3. advance `next_run_date` by the `frequency`; stamp `last_generated_at`;
4. publish `STANDING_ORDER.GENERATED` (NEW constant, D-17; `aggregateType = "STANDING_ORDER"`, payload `StandingOrderGeneratedPayload(standingUid, generatedOrderUid, companyId, branchId, runDate)`) — a notification/audit event (no downstream stock/GL effect; the generated SO is confirmed/delivered by a human as a normal SO). The generated SO is DRAFT (a human confirms it, so reservation is deliberate — the ADR-0021 quote→SO precedent).

### D-15 — ArchUnit edges (no cycle)

- **`sales.service` → `products.service`** — `PriceResolutionService.resolve` (the resolver) for POS/SO/quote/invoice line pricing. Plus **`sales` → `products.domain.dto`** (`ResolvedPriceDto`). **Allowed** — sales already reads products' pricing DTOs (the shipped pattern).
- **`sales.service` → `purchases.service`** — `PurchaseOrderService.create` for the drop-ship PO (D-9). **Allowed** — a new cross-module-service-call edge in the established `ap.service → gl.service` stance; purchases returns a DTO, sales imports no purchases entity. **No reverse edge** (purchases does not depend on sales). No cycle.
- **`sales.service` → `cashbank.service`** — reading the till's cash account / recognising the session net cash. **Allowed** — DTO-returning service call; no reverse edge.
- **`sales.service`/`sales.events` → `gl.service`** — `GLPostingService.post` (the POS variance, a synchronous human act) + `GLPostingSafeInvoker.postInNewTx` (the drop-ship COGS, event-driven). **Allowed** — the shipped `ap.service`/`gl.events` precedent.
- **`products.service` → `parties.domain.dto`** (the customer for customer-prices — DTO read) + **`products` self** (tiers/promotions on its own price master). **No edge `products → sales`** (the resolver is pulled by sales, products does not know sales). No cycle.
- The shipped `ModuleBoundaryTest` enforces controller↛repository, service↛controller, audit-append-only — **none of these edges violates an active rule** (the documented ADR-0020 D-12 / ADR-0021 D-13 allowances cover the cross-module-service-call shape). **The one new allowance to document:** `sales → purchases.service` (drop-ship) + `sales → cashbank.service` (the drawer) — both `sales → X` outbound, no reverse, no cycle.

### D-16 — Numbering: three new `code_sequence` kinds

`SalesDepthNumberGenerator` reuses the shipped `code_sequence` row-locked allocation (ADR-0007 D-6) with three new `entity_kind` values: `POS_SESSION` (`POS-%04d`), `BLANKET_ORDER` (`BLK-%04d`), `STANDING_ORDER` (`STD-%04d`), per company, concurrency-safe (NFR-SD-07). POS sales keep `INV-####` (the shipped invoice numbering — a POS sale is an invoice); call-off / generated SOs keep `SO-####`. Allocation timing: `POS-####` at session **open**; `BLK-####`/`STD-####` at **create**. No new numbering table — only new lazily-created `entity_kind` rows (`next_value = 1` on first use, the shipped mechanism). The `uq_<doc>_company_number` constraints backstop generator bugs.

## Consequences

**Positive**
- POS ships with **zero new costing seam**: a POS sale is a `DIRECT`-class invoice tagged with a `pos_session_id`, issuing stock + COGS on finalise through the unchanged shipped path. The ADR-0021 double-count-impossibility argument holds — POS adds no second issue path (D-5).
- Advanced pricing is a **single resolver** in `products` feeding every channel identically; the `InvoiceTotalsCalculator` math is untouched, so POS and back-office agree to the cent (D-6/D-7).
- Drop-ship issues **no own stock** and costs at the **supplier price** via a **reused GRNI bridge** (no new account where the boring one fits); a distinct `DROPSHIP.FULFILLED` event makes the no-own-stock path structurally unable to issue own stock (D-9/D-10/D-11).
- Blanket/standing orders **reuse the SO** (call-offs and generated SOs are ordinary `sales_orders` with a back-reference); the blanket draw-down has a DB-level over-draw guard (D-12/D-14).
- The change is additive and surgical: 11 new tables (3 POS, 3 pricing, 2 blanket, 2 standing, +1 payout child), additive columns on `sales_invoices` (`pos_session_id`), `sales_orders` (`source_blanket_uid`/`source_standing_uid`), `sales_order_lines` (`fulfilment_mode`/drop-ship cols/`price_source`), `sales_invoice_lines` (`price_source`), `purchase_orders` (`ship_to_customer_id`/`source_sales_order_uid` — the one Purchases touch), 2 new CoA accounts + 2 GL config keys + 1 source-type token + 1 `DocumentOrigin.POS` + 2 event constants + 3 `code_sequence` kinds + the permissions. **V1–V19 frozen; V42–V45 range.**

**Negative / costs**
- The slice touches five modules in one programme: sales (the documents + the POS sale wiring + drop-ship/blanket/standing), products (the pricing rule layer + resolver), purchases (the ship-to-customer PO columns), cashbank (the drawer cash account read), gl (the variance + drop-ship COGS keys/accounts). Cross-module coordination — flagged in the touch list and D-15.
- The **per-sale cash leg to the till's account** (D-4) requires threading the till's cash account through the cash-sale finalise path; if the shipped finalise hard-codes a single `CASH` config, that is the one invoice-channel touch (an additive optional cash-account override). The engineer verifies the shipped cash-leg resolution before assuming the parameter exists.
- `blanket_order_line.drawn_qty_base` is a maintained denormalisation that MUST stay tied to the call-off SOs; the line CHECK (`drawn ≤ committed`) is the DB backstop, but a service bug in the draw is a correctness defect — tests must assert `Σ called-off == drawn` after each call-off.
- Drop-ship COGS recognition at fulfilment-before-bill leaves GRNI carrying the cost until the AP bill matches — correct (the shipped GRNI semantics), but the engineer must ensure the drop-ship PO is matched in AP so GRNI clears (an unmatched drop-ship PO leaves a GRNI balance, exactly as an unmatched receipt does).
- The X/Z read computed-on-demand (OQ-SD-08) means there is no immutable Z document; the close stamps the counted/expected/variance figures on the session, which (once RECONCILED) are immutable — sufficient for audit. A persisted Z document is additive if a regulator later requires it (NFR-SD-08).

**Neutral / deferred**
- Online POS only, base currency, single location, cash + mobile-money tenders, single-condition promotions, one-supplier drop-ship, simple standing generation (inherited from sales-depth.md §2). Offline POS, POS hardware, card/gateway tenders, bundle/stacking promotions, drop-ship split/ASN, standing proration/renewal, multi-warehouse, multi-currency — all deferred, none precluded (NFR-SD-08). The pricing rule tables, the till↔account binding, and the blanket/standing back-references are the foundations those build on.

## Alternatives considered

- **POS sale — a `DIRECT` invoice tagged with a session id (chosen) vs a distinct POS document delegating to the invoice channel vs a new POS issue path.** *Decided: a tagged `DIRECT` invoice (option a of OQ-SD-01).* A distinct POS document would duplicate the invoice header/lines/tender/finalise and risk a second issue path; a new POS issue path would reopen the COGS-double-count risk ADR-0021 closed. A tagged invoice reuses the entire shipped channel with one nullable FK column — the lean, structurally-safe choice. Rejecting the distinct document and the new issue path.
- **Pricing rules — in `products` (chosen) vs in `sales`.** *Decided: `products` (OQ-SD-04).* Tiers/customer-prices/promotions extend `price_lists`/`product_prices` (the pricing master); sales already consumes Products' pricing DTOs and would only re-read the price master if it owned the rules. Putting them in `products` keeps pricing data in one place and the resolver a Products service. Rejecting `sales`-owned pricing.
- **Drop-ship COGS — reuse GRNI 2150 (chosen) vs a new drop-ship clearing account vs expense-direct at bill.** *Decided: reuse GRNI (OQ-SD-09).* GRNI already means "goods we owe for, not yet billed" and the AP bill-match already clears it; a drop-ship is the same shape. A new clearing account adds a CoA row + a key for no behavioural gain. Expensing direct at the supplier bill (skipping the fulfilment COGS) would land COGS in a later period than revenue (a margin-timing distortion). Rejecting the new account and the bill-direct expense.
- **Drop-ship fulfilment event — a new `DROPSHIP.FULFILLED` (chosen) vs overloading `DELIVERY.CONFIRMED` with a skip-issue flag.** *Decided: a new event.* Overloading the own-stock delivery event forces the `DeliveryIssueStockHandler` to branch on a drop-ship flag and *skip* the issue — the fragile "option a flag" ADR-0021 D-6 rejected for the seam. A distinct event makes the no-own-stock path structurally unable to issue own stock — the legible, boring choice mirroring ADR-0021 D-6's primary mechanism.
- **Promotion stacking — single highest-precedence rule (chosen) vs configurable stacking.** *Decided: single rule, deterministic precedence (OQ-SD-03).* Stacking ("best of N" / additive) is a commercial-policy feature with edge cases (does a customer-price stack with a promotion?) that v1 does not need; a deterministic precedence (customer > promotion > tier > list) is unambiguous and testable. Stacking is additive later (NFR-SD-08). Rejecting configurable stacking in v1.
- **Blanket basis — quantity per product (chosen) vs total value vs both.** *Decided: quantity per product (OQ-SD-11).* A quantity draw-down has a clean DB over-draw guard (`drawn ≤ committed`); a value basis needs price-at-call-off normalisation and a fuzzier "remaining value" invariant. Value commitments are additive later. Rejecting value-basis in v1.

## Open items (OQ-SD — recommended defaults adopted; OQ-SD-00 is the only owner-ratification gate)

- **OQ-SD-00 — owner ratification of the four-feature v1 scope.** PENDING — the requirements are architect-authored. The ADR is written on the assumed §2 scope; **owner confirms before the build starts**. The other OQs are architect/finance decisions made below.
- **OQ-SD-01 — POS no-new-seam mechanism:** adopted **a `DIRECT`-class invoice (new `DocumentOrigin.POS`) tagged with `pos_session_id`** (D-5). Settled — the load-bearing decision.
- **OQ-SD-02 — POS variance GL + tender-mix:** adopted **`POS_CASH_OVER` 4900 (income) + `POS_CASH_SHORT` 5170 (expense)`**; cash + mobile reconciled as distinct tender lines, variance posted on the **cash** drawer (D-3/D-4). Owner (finance) confirms the account choice.
- **OQ-SD-03 — pricing precedence / stacking:** adopted **customer-specific > active promotion > tier > base list, single rule, no stacking** (D-7). Settled.
- **OQ-SD-04 — rule home:** adopted **`products`** (D-1/D-6). Settled.
- **OQ-SD-05 — promotion granularity:** adopted **product / category (reusing the product's existing category attribute) / all; no customer-segment targeting in v1** (D-6). Settled.
- **OQ-SD-06 — till ↔ cash account + recognition timing:** adopted **a till binds one cash/bank account (shareable); the POS sale's cash leg posts to the till's account on finalise; the variance posts on reconcile** (D-3/D-4). Owner confirms the per-sale cash-leg threading.
- **OQ-SD-07 — refund authority:** adopted **any open session, `SALES.POS.REFUND`, no threshold in v1** (FR-SD-06). Owner may add a threshold later (a one-line guard).
- **OQ-SD-08 — X/Z read shape:** adopted **computed on demand from the session's invoices/payouts; close stamps counted/expected/variance** (D-3). Settled.
- **OQ-SD-09 — drop-ship COGS recognition + clearing:** adopted **recognise at fulfilment, DR COGS / CR GRNI (reused), cleared by the AP bill match; do not invoice before fulfilment** (D-9/D-10/D-11). Settled (owner-finance confirms reusing GRNI vs a dedicated clearing account).
- **OQ-SD-10 — standing pricing lock + trigger:** adopted **then-current resolved pricing (or `lock_pricing` per standing order); generation manual + a simple scheduled sweep, idempotent per period** (D-13/D-14). Settled.
- **OQ-SD-11 — blanket basis:** adopted **quantity per product** (D-12). Settled.

## Permissions, ScopeGuard, GL config, events, nav routes (the shared-contract surface)

- **New permissions** (module `sales` unless noted; seeded + granted to `ORG_ADMIN`, D-17): `SALES.POS.TILL.MANAGE`, `SALES.POS.SESSION.OPEN`, `SALES.POS.SESSION.CLOSE`, `SALES.POS.SESSION.RECONCILE`, `SALES.POS.SELL`, `SALES.POS.REFUND`, `SALES.POS.VIEW`, `SALES.DROPSHIP.VIEW`, `SALES.DROPSHIP.CREATE`, `SALES.BLANKET.VIEW`, `SALES.BLANKET.CREATE`, `SALES.BLANKET.CLOSE`, `SALES.STANDING.VIEW`, `SALES.STANDING.CREATE`, `SALES.STANDING.GENERATE`, and (module `products`) `SALES.PRICING.RULE.VIEW`, `SALES.PRICING.RULE.MANAGE`. (POS sales ride the existing `SALES.INVOICE.*`; the drop-ship PO rides `PURCHASE.ORDER.*`.)
- **New `ScopeGuard` target types** (D-1, added to `ScopeGuard.companyIdOf` switch, repositories injected): `till` → `PosTillRepository`, `possession` → `PosSessionRepository` (POS session; `possession` chosen to avoid clashing with HTTP "session"), `blanketorder` → `BlanketOrderRepository`, `standingorder` → `StandingOrderRepository`, `pricingrule` → a resolver over `PromotionRepository`/`CustomerPriceRepository`/`PriceTierRepository` (or three separate types `promotion`/`customerprice`/`pricetier` — **recommended: three explicit types** for clean `@perm.scoped` gating: `promotion`, `customerprice`, `pricetier`).
- **New `GlConfigKey` values:** `POS_CASH_OVER` (→ CoA 4900), `POS_CASH_SHORT` (→ CoA 5170). **New CoA account codes** (seeded per company): `4900 Cash Over (Till Surplus)` INCOME/CREDIT, `5170 Cash Short / Till Shortage` EXPENSE/DEBIT. (Drop-ship COGS reuses `COGS`/5100 + `GRNI`/2150 — no new key/account.)
- **New `JournalSourceType` token:** `POS_VARIANCE` (the reconcile journal; drop-ship COGS reuses `COGS`).
- **New `DomainEventType` constants:** `DROPSHIP.FULFILLED` (`DROPSHIP_FULFILLED`), `STANDING_ORDER.GENERATED` (`STANDING_ORDER_GENERATED`); new aggregate types reuse `SALES_ORDER` (drop-ship) + add `STANDING_ORDER`.
- **New `code_sequence` kinds:** `POS_SESSION` (`POS-####`), `BLANKET_ORDER` (`BLK-####`), `STANDING_ORDER` (`STD-####`).
- **New `DocumentOrigin` value:** `POS`.
- **New Angular nav routes** (under the admin shell, mirroring the shipped `sales-orders`/`deliveries` route style): `pos/tills`, `pos/sessions`, `pos/sessions/uid/:uid`, `pos/sell`, `pricing/rules`, `pricing/rules/promotions`, `pricing/rules/customer-prices`, `blanket-orders`, `blanket-orders/uid/:uid`, `standing-orders`, `standing-orders/uid/:uid` (drop-ship is a flag on the existing `sales-orders` screens — no new top-level route, but `sales-orders/uid/:uid` gains a drop-ship section).

## V42–V45 migration ordering (additive; V1–V19 + V20–V41 FROZEN; #12-safe seeds)

The DDL is split across the assigned range so each stage ships its own migration (the ADR-0021 staged-split precedent). Table/column/constraint names above are fixed regardless of the file split.

**`V42__sales_pricing_rules.sql`** (Stage 1a — advanced pricing, products module):
1. CREATE `price_tiers`, `customer_prices`, `promotions` (+ constraints/indexes per D-6).
2. ALTER `sales_invoice_lines` ADD `price_source` VARCHAR(20) NULL; ALTER `sales_order_lines` ADD `price_source` VARCHAR(20) NULL.
3. permission seed + `ORG_ADMIN` grant: `SALES.PRICING.RULE.VIEW`/`MANAGE` (module `products`) `ON CONFLICT (code) DO NOTHING` + the `roles × permissions` CROSS JOIN into `role_permission` `ON CONFLICT DO NOTHING` (the V17/V19 pattern). (Permissions have no uid — #12 N/A.)

**`V43__pos.sql`** (Stage 1b — POS):
1. CREATE `pos_tills`, `pos_sessions`, `pos_session_payouts` (+ constraints + the partial-unique `ux_pos_session_one_open` per D-3).
2. ALTER `sales_invoices` ADD `pos_session_id` BIGINT NULL + `fk_sales_invoice_pos_session`.
3. ALTER `sales_invoices` widen `chk_sales_invoice_origin` (DROP/ADD) to admit `'POS'` (keep `DIRECT`,`SALES_ORDER`; the V18 additive-widen pattern). (`chk_sales_invoice_origin_refs` is unaffected — a POS invoice has NULL source_order/delivery, like DIRECT.)
4. CoA seed per existing company: `4900 Cash Over` INCOME/CREDIT + `5170 Cash Short` EXPENSE/DEBIT — uid `'POS' || lpad(company_id,6,'0') || account_code` (3+6+4=13 chars ≤26), `ON CONFLICT (company_id, account_code) DO NOTHING` (the V17 pattern).
5. `gl_configs` CHECK widen (DROP/ADD) — add `POS_CASH_OVER`,`POS_CASH_SHORT` to the existing IN-list (union of all V17 keys + these two).
6. `gl_configs` seed per existing company: `POS_CASH_OVER`→4900, `POS_CASH_SHORT`→5170 — **#12-safe uid** `'POC' || lpad(company_id,6,'0') || substr(md5(config_key),1,12)` (3+6+12=21 ≤26), `ON CONFLICT (company_id, config_key) DO NOTHING`.
7. `journal_batches` + `journal_entries` source-type CHECK widen (DROP/ADD) — add `POS_VARIANCE` (union of all V17 tokens + this one).
8. permission seed + grant: `SALES.POS.TILL.MANAGE`/`SESSION.OPEN`/`SESSION.CLOSE`/`SESSION.RECONCILE`/`SELL`/`REFUND`/`VIEW` (module `sales`).

**`V44__sales_dropship.sql`** (Stage 2a — drop-ship):
1. ALTER `sales_order_lines` ADD `fulfilment_mode` VARCHAR(20) NOT NULL DEFAULT `'OWN_STOCK'` + `dropship_supplier_id` BIGINT NULL + `dropship_po_uid` VARCHAR(26) NULL + `dropship_unit_cost_amount` NUMERIC(19,4) NULL + the two CHECKs (D-8). (Existing rows back-fill `OWN_STOCK` — correct.)
2. ALTER `purchase_orders` ADD `ship_to_customer_id` BIGINT NULL + `source_sales_order_uid` VARCHAR(26) NULL (the one Purchases touch, D-9). (Existing POs back-fill NULL.)
3. permission seed + grant: `SALES.DROPSHIP.VIEW`/`CREATE` (module `sales`). (No new GL key/account — drop-ship COGS reuses `COGS`/`GRNI`; no new source-type — reuses `COGS`.)

**`V45__sales_blanket_standing.sql`** (Stage 2b — blanket/standing):
1. CREATE `blanket_orders`, `blanket_order_lines` (+ the `drawn ≤ committed` CHECK, D-12).
2. CREATE `standing_orders`, `standing_order_lines` (+ constraints, D-13).
3. ALTER `sales_orders` ADD `source_blanket_uid` VARCHAR(26) NULL + `source_standing_uid` VARCHAR(26) NULL (D-12/D-14).
4. permission seed + grant: `SALES.BLANKET.VIEW`/`CREATE`/`CLOSE`, `SALES.STANDING.VIEW`/`CREATE`/`GENERATE` (module `sales`).

**`code_sequence` kinds** (POS_SESSION / BLANKET_ORDER / STANDING_ORDER) are **not** pre-seeded — they are created lazily on first use by `SalesDepthNumberGenerator` (the shipped mechanism), so **no #12-vulnerable per-company seed-uid for numbering**. The only per-company CROSS-JOIN seeds are the permission grants (no uid) and the CoA/gl_config seeds (uid built #12-safe with `lpad` + `substr(md5(...))`, never `|| key`). `MigrationKeepDataIT` extends to V42–V45 (the back-fills — `sales_invoices.pos_session_id = NULL`, `sales_order_lines.fulfilment_mode = 'OWN_STOCK'`, `purchase_orders.ship_to_customer_id = NULL`, the new columns — are verified keep-data-safe).

---

## Summary

ADR-0029 designs **Sales Depth** across `com.erp.modules.sales` + `com.erp.modules.products`: **POS** (a till bound to a cash/bank drawer + a cashier session open→close→reconcile with over/short posted to a new `POS_CASH_OVER` 4900 / `POS_CASH_SHORT` 5170 pair; a **POS sale is a `DIRECT`-class invoice — new `DocumentOrigin.POS` — tagged with `pos_session_id`, issuing stock + COGS on finalise through the unchanged shipped path, no new costing seam**); **advanced pricing** (`price_tiers`/`customer_prices`/`promotions` in `products` + a single `PriceResolutionService` with deterministic precedence customer-specific > promotion > tier > list, feeding every channel while `InvoiceTotalsCalculator` math is untouched); **drop-ship** (a `fulfilment_mode = DROP_SHIP` SO line that reserves/issues **no own stock**, raises a ship-to-customer PO on confirm, and recognises COGS at the **supplier cost** via a reused **GRNI 2150** clearing leg through a new `DROPSHIP.FULFILLED` event/handler — never the moving average); and **blanket/standing orders** (`blanket_orders` with a DB-guarded `drawn ≤ committed` draw-down by call-off SOs; `standing_orders` generating child SOs on a schedule via a new `STANDING_ORDER.GENERATED` event). Additive on the frozen V1–V19 as **V42–V45**, #12-safe, with the ArchUnit edges (`sales → products/purchases/cashbank/gl`, no cycle). It is concrete enough to build without guessing a rule; the only owner gate is **OQ-SD-00** (ratifying the v1 scope).
