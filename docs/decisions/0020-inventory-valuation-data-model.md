# 0020 — Inventory Valuation & COGS data model: a moving weighted-average cost per product carried on `stock_on_hand`, perpetual inventory via a new GRNI clearing liability (goods receipt posts DR Inventory / CR GRNI; the AP bill-match swaps its goods debit from Purchases to GRNI), COGS posted DR COGS / CR Inventory at the current average on every sale (incl. recipe explosion), opening inventory valuation against Opening-Balance-Equity, stock-adjustment revaluation against a new Shrinkage expense, and a stock-valuation report that reconciles Σ(qty×avg) to the 1300 Inventory GL balance — all on the existing idempotent stock event handlers posting through `GLPostingService`

- **Status:** Accepted
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (owner-ratified Inventory-Valuation/COGS requirements 2026-06-10 — all seven scoping forks resolved; no ADR-0020-blocking open question remains, inventory-valuation.md §11. The cost-into-event seam, the GRNI account + key, the Stock-Adjustment account + key, the GRNI granularity, the reversal cost policy, the negative-on-hand issue cost, the GOODS-vs-SERVICE bill predicate, and the average precision are the **decisions this ADR makes**, not requirements blockers — the *behaviour* is fixed by the requirements.)
- **Context source:** [docs/requirements/inventory-valuation.md](../requirements/inventory-valuation.md) (RATIFIED 2026-06-10 — FR-INV-01..10, BR-INV-01..12, NFR-INV-01..08, US-INV-01..08, §7 flows, §10 accepted boundary, §11 OQ log; the ground truth for every rule below). Verified against the **shipped** code:
  - **Stock** ([ADR-0010](0010-stock-data-model.md) / [V7__stock.sql](../../backend/src/main/resources/db/migration/V7__stock.sql)): `StockOnHand` (`stock_on_hand` — `id`, `uid` VARCHAR(26), `company_id`, `branch_id`, `product_id`, `quantity` NUMERIC(19,6) **signed, no `>=0` CHECK**, `reorder_level`, `@Version version`, audit cols; `uq_stock_on_hand_scope (company_id, branch_id, product_id)` — **NO cost/value columns**); `StockMovement` (`stock_movements` — append-only, `movement_type` CHECK ∈ {GOODS_RECEIPT, SALE_ISSUE, SALE_REVERSAL, GOODS_RECEIPT_REVERSAL, ADJUSTMENT, OPENING_BALANCE}, `quantity` signed CHECK `<>0`, `source_event_uid`/`source_document_type`/`source_document_uid` scalar cross-module refs, `reason_code`, `uq_stock_movement_source_event (source_event_uid, product_id)` idempotency backstop — **NO cost columns**); `StockPostingService.post(companyId, branchId, productId, quantity, movementType, sourceEventUid, sourceDocumentType, sourceDocumentUid, reasonCode, note, occurredAt, actorId)` returns the movement uid, `@Transactional(MANDATORY)`, upserts on-hand with a one-shot optimistic-lock retry (**posts NO GL today**); the four handlers `GoodsReceiptStockHandler` (STOCK.RECEIVED), `SaleIssueStockHandler` (SALE.FINALISED, recipe explosion via `RecipeExplosionResolver`), `SaleReversalStockHandler` (SALE.VOIDED, reverse-from-ledger), `GoodsReceiptReversalStockHandler` (STOCK.RECEIPT.VOIDED, reverse-from-ledger) — all `@Transactional(MANDATORY)` + `IdempotencyGuard` (`alreadyProcessed(consumer, uid)` / `markProcessed`) + system `RequestContext.Principal`; `RecipeExplosionResolver.explode(composedUid, qty)→List<ExplosionLine(productId, signed qty)>` + `isComposed(uid)`; `StockServiceImpl.adjust(AdjustStockRequest)` / `openingBalance(OpeningBalanceRequest)` (manual paths, audited, **post NO GL today**); `StockReceivedPayload(receiptUid, companyId, branchId, receivedAt, List<LineItem(productId, productUid, unitId, qtyInBase)>)` — **carries NO unit cost (the seam)**; `AdjustmentReason` ∈ {SHRINKAGE, DAMAGE, EXPIRY, COUNT_CORRECTION, RECEIPT_CORRECTION, OTHER}.
  - **Purchases** ([ADR-0011](0011-purchases-data-model.md) / [V8__purchases.sql](../../backend/src/main/resources/db/migration/V8__purchases.sql)): `goods_receipt_lines.unit_cost_amount` NUMERIC(19,4) + `line_cost_amount` NUMERIC(19,4) (**the cost input at receipt** — `CHECK (unit_cost_amount >= 0 AND line_cost_amount >= 0)`); `GoodsReceiptLineDto(id, uid, goodsReceiptId, purchaseOrderLineId, lineNo, productId, productCode, productName, unitId, unitName, receivedQty, qtyInBase, unitCostAmount, lineCostAmount, currency)`; `goods_receipts.status` ∈ {DRAFT, RECEIVED, VOID}; Purchases produces STOCK.RECEIVED.
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / V10): `GLPostingService.post(JournalEntryDraft)→JournalEntryDto` (validates ≥2 lines, balance, OPEN period, active accounts, base currency; writes batch+entry+lines atomically; returns the new `journal_entries.uid`) + `postReversal(originalEntryUid, reversalDate, sourceType, sourceRef, postedBy)`; `JournalEntryDraft(companyId, branchId, postingDate LocalDate, description, sourceType, sourceRef, reversalOfId, postedBy, List<LineDraft>)` + `LineDraft(accountId, debitAmount, creditAmount, currency, lineMemo)`; `GLConfigResolver.resolve(companyId, GlConfigKey)→ChartOfAccount` (throws on missing mapping / inactive account — BR-GL-10); `GLPostingSafeInvoker.postInNewTx(draft)` / `postReversalInNewTx(...)` (REQUIRES_NEW; catches all, returns null, never poisons the dispatch TX — the handler-posts-GL safety wrapper); `GlConfigKey` (**`INVENTORY`, `COGS`, `ACCOUNTS_PAYABLE` already defined; `OPENING_BALANCE_EQUITY`, `PURCHASES`, `VAT_INPUT` defined — NO `GRNI`, NO `STOCK_ADJUSTMENT`**); `JournalSourceType` (admits MANUAL/SALES/SALES_REVERSAL/OPENING_BALANCE/AR_*/AP_*/CASH_*/VAT_RETURN/YEAR_END_CLOSE; reserved tokens incl. `COGS` not yet admitted by the DB CHECK); `JournalLineRepository.accountBalance(companyId, accountId)` = `SUM(debit) − SUM(credit)` (debit-positive — for `1300` debit-normal this is the positive inventory asset balance, the recon expected side); `ChartOfAccount(companyId, accountCode, name, AccountType, createdBy)`; `AccountType` ∈ {ASSET, LIABILITY, EQUITY, INCOME, EXPENSE}; the **shipped `SalesPostingHandler`** (the precedent: an outbox handler posting GL via `GLPostingSafeInvoker` in REQUIRES_NEW, idempotent under `IdempotencyGuard`); the new-company seeders `ChartOfAccountServiceImpl.DEFAULT_ACCOUNTS` (includes `1300 Inventory ASSET`, `5100 COGS EXPENSE`) + `GlConfigServiceImpl.DEFAULT_MAPPINGS` (includes `INVENTORY→1300`, `COGS→5100`) + `ApGlSeeder`/`ArGlSeeder`/`CashBankSeeder` (the per-module new-company seeder pattern, wired in `BootstrapRunner` + `CompanyService.create`).
  - **AP** ([ADR-0015](0015-accounts-payable-data-model.md) / V12): the **shipped** `BillMatchServiceImpl.postMatchedBillToGl(SupplierBill)` posts **DR PURCHASES (5150) net · [DR VAT_INPUT vat>0] · CR ACCOUNTS_PAYABLE (2100) gross** (verified lines 265-309 — the goods debit this ADR swaps); `SupplierBill` carries `source` (`SupplierBillSource` ∈ {BILL, OPENING_BALANCE}), nullable `purchaseOrderUid`, `billDate`, `netAmount`/`vatAmount`/`grossAmount`; `SupplierBillLine` carries nullable `poLineUid` + `grLineUid` (**the line-level goods signal**); the 3-way match already resolves the GR line by `grLineUid`. **Finding #15** (a concurrent fix on `develop`) adds `bill_number` assignment in `postMatchedBillToGl` — a *different* line; the GRNI swap below is designed to coexist (it changes the goods *debit* leg, not the numbering).
  - **AP-input-VAT swap precedent** ([ADR-0017](0017-vat-return-data-model.md) D-7): AP's input-VAT debit was moved from `VAT_PAYABLE` to a new `VAT_INPUT` account by editing one leg of `postMatchedBillToGl` — the exact shape of the GRNI swap below.
  - **Recon-bar precedent** ([ADR-0018](0018-financial-reporting-read-model.md) D-5/D-6 + `ReconciliationDto.of(label, computed, expected)`; ADR-0015 `ApReconciliationDto(companyId, subLedgerTotal, glControlBalance, difference, currency)`): the valuation report's `Σ(qty×avg) == 1300 balance` bar is the same structural self-check.
  - **Money** ([ADR-0005](0005-money-and-currency.md)): `BigDecimal`, base currency only (TZS, 0-dp display in practice), HALF_UP.
  - **Outbox / idempotency** ([ADR-0009](0009-transactional-outbox.md)): `processed_events(consumer, event_uid)` via `IdempotencyGuard`; the GRNI swap keeps the AP→GL direction (NFR-INV-07).
  - [[db-naming-convention]] verified against V1–V16 (plural masters/logs, singular junctions, singular constraint roots `uq_`/`fk_`/`chk_`, plural `ix_` indexes, `uid VARCHAR(26)` ULID, `company_id` BIGINT scalar, the additive `DROP/ADD CONSTRAINT` widen for `chk_gl_config_key` / `chk_journal_*_source_type`). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **Latest shipped migration is `V16__year_end_close.sql` → Inventory Valuation is `V17__inventory_valuation.sql`** (additive; V1–V16 FROZEN). Next ADR is 0021.

This ADR is the **technical data model + integration design** for Inventory Valuation & COGS (ROADMAP T2.2, PATH-TO-FULL-ERP §4 critical-dependency #2 — the highest-leverage Phase B piece). It translates the ratified spec into: the cost columns on `stock_on_hand` (+ the cost columns on `stock_movements` that make reversals exact), the moving-average recompute formula + the concurrency mechanism, the cost-into-event payload change, every GL posting leg (receipt / bill-swap / sale / reversals / opening / adjustment) with its `GlConfigKey` and `JournalSourceType`, the idempotency stance, opening valuation, the valuation report + recon bar, the two new accounts + keys, the `V17` migration ordering with **#12-safe seed-uids**, and the ArchUnit edges. It is **concrete enough that the backend engineer writes `V17` + the cost layer + the receipt/bill/sale/reversal/opening/adjustment postings + the report + the seeders without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. Nothing ratified is re-litigated.

## Context

The books balance and the trading core is event-wired, but **inventory has no money in it** (inventory-valuation.md §1). `stock_on_hand` is quantity-only; `StockPostingService` posts no GL; AP expenses goods to `5150 Purchases` (periodic). So the P&L has no cost of sales, the balance sheet has no inventory asset, and there is no margin. The cost input already exists on `goods_receipt_lines.unit_cost_amount` (V8) — the work is to (a) carry it into the stock receipt handler, (b) recompute a moving average and store it, (c) drive GL postings on paths that post none today (receipt, sale, adjustment), and (d) swap AP's goods debit to clear a new GRNI bridge. The forces:

- **The cost must reach the stock event path (the load-bearing seam, OQ-INV-05).** `StockReceivedPayload.LineItem` carries no unit cost. Two options: extend the payload with a per-line `unitCostAmount`, or have `GoodsReceiptStockHandler` read the GR line cost by uid as a DTO. The payload extension is the leanest additive shape, keeps the cost atomic with the event the handler already deserialises, and adds no hot-path read. Resolved in **D-3**.

- **Perpetual requires a bridge between "received" and "billed" (GRNI).** A receipt capitalises inventory (DR 1300) before the supplier bill arrives; the credit cannot be AP (not yet billed) and cannot be Purchases (perpetual, not periodic). A **new GRNI clearing liability** holds "goods received, not invoiced"; the AP bill-match later debits GRNI to clear it. This is exactly the ADR-0017 D-7 VAT_INPUT pattern — a new control account + a one-leg swap in the same shipped AP method. Resolved in **D-4 / D-8**.

- **Where does the cost live, and how is the recompute made race-safe?** The average + on-hand value can live as additive columns on `stock_on_hand` (NFR-STOCK-06 anticipates cost columns) or in a separate valuation table. The on-hand row already exists per (company, branch, product), already carries the `@Version` optimistic lock that serialises concurrent movements, and is upserted by the one primitive every path funnels through. Putting the cost on it makes the recompute a single-row read-modify-write under the lock already present (NFR-INV-05). Resolved in **D-2**.

- **The chief acceptance bar is GL reconciliation (BR-INV-06, NFR-INV-01).** `Σ(on-hand qty × moving-average cost)` MUST equal the `1300 Inventory` GL balance. With every receipt posting DR 1300 at the same qty×cost the recompute uses, every sale crediting 1300 at qty×current-avg, opening valuation seeding both sides, and adjustments moving both sides at the current avg, the stored `on_hand_value` and the 1300 balance move together by construction. The report computes `Σ(qty×avg)` and compares to `accountBalance(companyId, 1300)`. Resolved in **D-6**.

- **Reversals must not create phantom gain/loss.** A void reverses COGS; if it reversed at the *now-current* average (which a later receipt may have moved) the books would not return to their pre-sale state. Reversing at the **original issue/receipt cost** is symmetric. That requires the original cost to be **knowable** — hence cost columns on `stock_movements`. Resolved in **D-2 / D-5**.

- **Schema freeze / direction.** IAM=V1 … Year-End Close=V16, all frozen. Inventory Valuation is additive `V17`: it ALTERs `stock_on_hand`/`stock_movements` (additive columns), adds two CoA accounts + two `gl_config` keys + the source-type CHECK widen + two permissions, all referencing frozen tables by scalar uid. The valuation slice reads `goods_receipt_lines` and posts to GL through `GLPostingService` — it imports no Purchases/Sales/AP entity (NFR-INV-07). The AP swap is *within* AP, using AP's own `GLConfigResolver`.

## Decision

### D-1 — Module placement: valuation lives in `com.erp.modules.stock`; stock gains a `stock → gl.service` posting edge (the SalesPostingHandler precedent)

The valuation logic stays in **`com.erp.modules.stock`** — it owns on-hand, the movement ledger, the four event handlers, the recipe explosion, and the manual adjust/opening paths that the cost + GL postings hang directly off. A separate module would have to re-read the on-hand row, duplicate the concurrency lock, and re-derive the recipe explosion. Reject.

Stock gains a **new outbound edge to `gl.service`** (`GLPostingService` / `GLConfigResolver` / `GLPostingSafeInvoker`). This is **not** a new boundary precedent: `ap.service.BillMatchServiceImpl` already imports `GLPostingService` + `GLConfigResolver`, and `gl.events.SalesPostingHandler` posts GL from an outbox handler via `GLPostingSafeInvoker`. Stock-posts-GL is the same shape. The cost recompute and the GL post commit in the **same handler transaction** as the quantity movement (NFR-INV-04), but the *GL post itself* goes through **`GLPostingSafeInvoker.postInNewTx` (REQUIRES_NEW)** so a missing `gl_config` / closed period cannot mark the shared dispatch TX rollback-only and silently roll back a co-dispatched handler (the exact reason `GLPostingSafeInvoker` exists — see its Javadoc). The quantity movement + average recompute remain in the handler's own MANDATORY TX; the GL leg is the isolated, null-on-anomaly unit.

Internal layout (additive to the shipped `stock` package):

```
com.erp.modules.stock
├── domain.entity   StockOnHand (+ avg_cost, on_hand_value cols, D-2), StockMovement (+ unit_cost, value cols, D-2)
├── domain.dto      StockReceivedPayload.LineItem (+ unitCostAmount, D-3),
│                   StockValuationRowDto, StockValuationReportDto (+ ReconciliationDto reuse, D-6),
│                   SetOpeningValuationRequest, OpeningValuationResultDto (D-5)
├── service         StockPostingService(+Impl)              — signature gains nullable unitCost/value params; the quantity primitive
│                   InventoryValuationService(+Impl)        — NEW: recomputeOnReceipt, costIssue, setOpeningValue,
│                                                             revalueAdjustment, the moving-avg engine (D-2/D-5)
│                   InventoryGlPoster                        — NEW: builds + posts the inventory/COGS/GRNI/
│                                                             adjustment/opening drafts via GLPostingSafeInvoker (D-4)
│                   StockValuationQuery                      — NEW: the report aggregate + 1300 recon (D-6)
│                   InventoryGlSeeder                        — NEW: seeds GRNI 2150 + SHRINKAGE 5160 + keys
│                                                             for a new company (D-8, the ApGlSeeder pattern)
└── events          GoodsReceiptStockHandler                — + recompute + DR 1300 / CR GRNI (D-4)
                    SaleIssueStockHandler                   — + DR 5100 / CR 1300 at current avg (D-4)
                    SaleReversalStockHandler                — + reverse COGS at original cost (D-5)
                    GoodsReceiptReversalStockHandler        — + reverse inventory/GRNI at original cost (D-5)
```

`InventoryValuationService` is the cost authority; `InventoryGlPoster` is the only place that builds GL drafts for stock (keeps the handlers thin, mirrors how AP isolates `postMatchedBillToGl`). The manual **adjustment** (`StockServiceImpl.adjust`) and **opening valuation** are synchronous human-act postings (the operator's TX), so they call `GLPostingService.post` **directly** (not the REQUIRES_NEW safe-invoker) — a missing `gl_config` there MUST fail the operator's command (BR-INV-12), exactly as AP's `postMatchedBillToGl` does. Only the **event-driven** receipt/sale/reversal postings use the safe-invoker (a poisoned dispatch TX is the failure they guard against).

### D-2 — Cost storage: additive columns on `stock_on_hand` (the running average + value) and on `stock_movements` (the cost at the moment of the movement); the moving-average recompute + concurrency

**On `stock_on_hand` (the running cost state) — ALTER, additive:**

| column | type | null | default | notes |
|---|---|---|---|---|
| `avg_cost` | `NUMERIC(19,4)` | NULL | NULL | the running moving-average unit cost in base currency; **NULL = no cost established yet** (never received, never opened). The internal scale is **4 dp** (OQ-INV-06: a higher internal scale than the 0-dp TZS display, avoiding cumulative drift). |
| `on_hand_value` | `NUMERIC(19,4)` | NOT NULL | `0` | the inventory value carried = the running `Σ` of costed deltas. **This is the authoritative value** the recompute maintains and the report sums; it equals `quantity × avg_cost` after every receipt but is held explicitly so issues/adjustments can debit/credit it exactly without re-rounding (NFR-INV-02). |

`CHECK (avg_cost IS NULL OR avg_cost >= 0)` — `chk_stock_on_hand_avg_nonneg` (the average must never go negative, BR-INV-01/NFR-INV-02). **No** `CHECK` on `on_hand_value` sign — negative on-hand × a positive avg can yield a negative value, a designed flagged state (mirrors the no-`>=0`-on-quantity stance). `avg_cost`/`on_hand_value` are added to the `@Version`-guarded entity so they ride the existing optimistic lock.

**On `stock_movements` (the cost AT the movement — makes reversals exact) — ALTER, additive:**

| column | type | null | notes |
|---|---|---|---|
| `unit_cost_amount` | `NUMERIC(19,4)` | NULL | the unit cost applied to **this** movement: the receipt cost (GOODS_RECEIPT), the current avg at issue (SALE_ISSUE / ADJUSTMENT-out), the opening cost (OPENING_BALANCE). NULL for a movement that posts no cost. |
| `value_amount` | `NUMERIC(19,4)` | NULL | the signed value of this movement = `quantity × unit_cost_amount`, rounded HALF_UP to 4 dp. Signed: + for receipt, − for issue. **This is what a reversal reverses** (D-5 — reverse at the original cost) and what the GL leg posts. |

`CHECK ((unit_cost_amount IS NULL AND value_amount IS NULL) OR (unit_cost_amount >= 0))` — `chk_stock_movement_cost`. No `value_amount` sign CHECK (it follows the signed quantity). Both columns are immutable (append-only ledger). `StockPostingService.post(...)` gains **two trailing parameters** `unitCostAmount`, `valueAmount` (nullable) — additive to the signature; existing manual paths pass null until valued.

**The moving-average recompute (BR-INV-01), executed by `InventoryValuationService.recomputeOnReceipt` inside the receipt handler TX, under the on-hand row's optimistic lock:**

```
// read the (locked) on-hand row for (company, branch, product); current = qty, avg_cost, on_hand_value
receiptQty   = line.qtyInBase                      // > 0 (a GOODS_RECEIPT)
receiptCost  = line.unitCostAmount                 // from goods_receipt_lines.unit_cost_amount (D-3), >= 0
receiptValue = round4(receiptQty * receiptCost)    // HALF_UP, 4 dp

newQty   = qty + receiptQty
if (newQty <= 0)                                   // pathological (receipt into deep-negative); guard
    newAvg   = (avg_cost != null ? avg_cost : receiptCost)   // keep last-known; do not divide by <=0
    newValue = on_hand_value + receiptValue
else if (avg_cost == null || qty <= 0)             // FIRST receipt, or receipt onto zero/negative on-hand
    newAvg   = receiptCost                          // first receipt sets avg to the receipt cost
    newValue = round4(newQty * receiptCost)
else                                                // the normal weighted-average recompute
    newValue = on_hand_value + receiptValue
    newAvg   = round4(newValue / newQty)            // HALF_UP, 4 dp; new_avg = (old_value + recv_value)/new_qty

// persist newQty (via the existing applyDelta), newAvg, newValue on the SAME row
// the GOODS_RECEIPT movement records unit_cost = receiptCost, value = receiptValue
```

This is `new_avg = (on_hand_value + receipt_qty × receipt_unit_cost) / (on_hand_qty + receipt_qty)` with `on_hand_value` held explicitly (so it equals `qty×avg` but never re-derives it lossily). **A zero-cost receipt** (`receiptCost == 0`) is accepted, drags the average toward zero, and is surfaced for review via a WARN log + audit detail — not rejected (BR-INV-01 edge (b)).

**Concurrency (NFR-INV-05):** the recompute is a read-modify-write on the single `stock_on_hand` row that already carries `@Version`. Two racing receipts for the same product each load the row, compute, and save; the second save raises `ObjectOptimisticLockingFailureException`. The existing `StockPostingServiceImpl` retries the on-hand delta **once**; the recompute MUST be inside that same retried unit so the loser **re-reads** the now-updated `qty`/`avg`/`value` and recomputes against fresh state (never overwrites). **Decision:** move the recompute *into* `StockPostingService` for the GOODS_RECEIPT path (it already owns the locked upsert + the retry), OR — preferred to keep `StockPostingService` cost-agnostic — have `InventoryValuationService.recomputeOnReceipt` perform the locked read-modify-write itself with the same one-retry-on-`ObjectOptimisticLockingFailureException`, and the handler call recompute **before** `posting.post(...)` so a clash retries the whole costed unit. A single one-shot retry matches the single-instance QA posture; the `@Version` is the durable guard when multi-instance lands. No `SELECT FOR UPDATE` is added — the optimistic lock is the chosen mechanism (consistent with ADR-0010 NFR-STOCK-04; a pessimistic lock is the discouraged alternative, see Alternatives).

**Edge — issue at zero/negative on-hand (OQ-INV-01, recommended default):** when `SaleIssueStockHandler` / adjustment-out would issue a product whose `avg_cost IS NULL` (never received, never opened — no established cost), the **costed COGS leg is blocked**: the quantity deduction still posts (the quantity model allows negative on-hand, ADR-0010 BR-STOCK-03 — unchanged), but the COGS GL leg is **skipped**, a WARN + audit anomaly is recorded, and the on-hand falls negative with `avg_cost` still NULL. The next receipt establishes the cost (first-receipt branch above). The discouraged alternative (value at last-known average) is a one-line config flag (`inventory.negative-issue-cost = BLOCK|LAST_AVG`), defaulting BLOCK. When `avg_cost` is non-NULL but on-hand is merely going negative, COGS posts at the current avg as normal (a known cost exists).

### D-3 — The cost-into-event seam: extend `StockReceivedPayload.LineItem` with `unitCostAmount` (Purchases populates it from `goods_receipt_lines`)

`StockReceivedPayload.LineItem` gains one additive field:

```java
public record LineItem(
        Long       productId,
        String     productUid,
        Long       unitId,
        BigDecimal qtyInBase,
        BigDecimal unitCostAmount    // NEW — goods_receipt_lines.unit_cost_amount (base currency, >= 0); D-3
) {}
```

**Who populates it:** the **goods-receipt confirmation** path in Purchases (the producer of STOCK.RECEIVED — it already writes `goods_receipt_lines.unit_cost_amount`) sets `unitCostAmount` per line when it builds the payload. This is a Purchases-side change (out of this slice's stock package, but a required upstream touch — flagged in the cross-module touch list). **Stock only reads the payload.** No extra read on the hot path; the cost travels with the event atomically. Backward note: because the field is additive on a record and the producer is updated in the same release, no in-flight events carry a null cost; defensively, `GoodsReceiptStockHandler` treats a null `unitCostAmount` as a **zero-cost receipt with a WARN** (it cannot invent a cost — it must not silently skip the GL leg and break the recon), surfacing the data gap.

Reject the alternative (handler re-reads the GR line cost by uid as a DTO via `purchases.service`): it adds an N-line cross-module read on every receipt, couples stock to a Purchases read API on the hot path, and the cost is *already* on the event Purchases emits. The payload extension is the leanest additive shape (the requirement's recommended default, OQ-INV-05).

### D-4 — GL postings: exact legs, keys, source types, granularity, idempotency

All amounts are base currency (BR-INV-11), HALF_UP, posted via `InventoryGlPoster` → `GLPostingSafeInvoker.postInNewTx(draft)` for the event-driven legs (REQUIRES_NEW, null-on-anomaly) and `GLPostingService.post(draft)` directly for the synchronous human-act legs (adjustment / opening — must fail the command). Accounts resolved via `GLConfigResolver.resolve(companyId, key)` (throws on missing/inactive — BR-GL-10 / BR-INV-12).

**(a) Goods receipt — `GoodsReceiptStockHandler`, after the +quantity movement + recompute (FR-INV-02, BR-INV-02):**

- **DR `INVENTORY` (1300)** = Σ(line qty × line unit cost) ; **CR `GRNI` (2150, NEW key `GRNI`)** = same total.
- **Granularity (OQ-INV-03 — decided per receipt, one journal with per-line legs):** **one journal entry per STOCK.RECEIVED event**, with **one DR-1300 leg + one CR-GRNI leg per receipt line** (a `LineDraft` pair per stockable line). This keeps the journal-per-event mapping (one event → one entry, mirrors `SalesPostingHandler`), gives the GRNI clear (D-8) line-level traceability via the leg memo carrying the GR line uid, and lets a partial bill clear the matched portion against the right legs. (A separate journal *per line* would multiply journals N× with no reconciliation benefit; rejected. A single net DR/CR with no per-line legs would lose the partial-clear traceability the per-line recommendation wants; rejected.)
- `sourceType = STOCK_RECEIPT` (**NEW** `JournalSourceType`); `sourceRef = receiptUid`; `postedBy = null` (SYSTEM). `description = "Goods receipt " + receiptUid`; each leg `lineMemo` carries the GR line uid + product code.

**(b) Sale — `SaleIssueStockHandler`, after the −quantity deduction + recipe explosion (FR-INV-04, BR-INV-04):**

- For each **stockable** issued line / exploded component: **DR `COGS` (5100)** = issued qty × **current `avg_cost`** ; **CR `INVENTORY` (1300)** = same. One journal per SALE.FINALISED, one DR/CR leg pair per issued component (the explosion already yields component rows). A composed product posts a leg per stockable component **at its own** current avg (the resolver gives component productIds; the poster reads each component's `avg_cost`). Non-stockable / no-stockable-component lines post no COGS (mirrors the quantity rule).
- If a line's `avg_cost IS NULL` (D-2 edge — never costed): **skip the COGS leg**, WARN + anomaly audit; the quantity deduction still posts. Lines with a cost still post; the journal is the balanced subset.
- `sourceType = COGS` (the reserved `JournalSourceType.COGS` — admit it in the CHECK, D-10); `sourceRef = invoiceUid`; `postedBy = null`. The `value_amount` recorded on each SALE_ISSUE movement = `−(qty × avg_cost)` (D-2) so the void reverses at exactly this cost (D-5).

**(c) AP bill-match — the GRNI swap (FR-INV-03, BR-INV-03/08) — in `ap.service.BillMatchServiceImpl.postMatchedBillToGl`:**

- **GOODS-vs-SERVICE predicate (OQ-INV-04 — decided):** a bill clears GRNI iff it has **goods lines linked to a receipt**. The concrete test, line-aware: a `SupplierBillLine` is a **goods line** iff `grLineUid != null` (it matched a goods-receipt line). Sum the **net of goods lines** → debit **GRNI**; sum the **net of non-goods/service lines** (`grLineUid == null`) → debit **PURCHASES (5150)** as today. A bill with `purchaseOrderUid == null` / no goods lines is wholly a service bill and retains `DR 5150` unchanged. Per-line is correct because a single bill may mix goods and service lines; the swap is per-leg, not per-bill.
- Resulting entry for a matched bill: **DR `GRNI` (goods-line net)** · **DR `PURCHASES` 5150 (service-line net, if any)** · **[DR `VAT_INPUT` (vat>0), UNCHANGED — ADR-0017 D-7]** · **CR `ACCOUNTS_PAYABLE` 2100 (gross)**. Balanced by construction (goods-net + service-net + vat = gross). After receipt (CR GRNI) + bill (DR GRNI), **GRNI nets to zero** for fully-billed goods (BR-INV-08).
- `sourceType` stays **`AP_BILL`** (it is still the AP bill posting — only a debit leg changed; mirrors how ADR-0017 D-7 kept AP_BILL when swapping the VAT leg). **Coexists with finding #15** (`bill_number`): that change touches numbering, not the goods-debit leg; the swap edits only the `purchasesAcct` leg construction (now a goods/service split) — a clean, separable diff in the same method.

**(d) Reversals — see D-5** (reverse at original cost): goods-receipt reversal posts **DR `GRNI` / CR `INVENTORY`** (`sourceType = STOCK_RECEIPT` with `reversalOfId`); sale void posts **DR `INVENTORY` / CR `COGS`** (`sourceType = COGS` with `reversalOfId`).

**(e) Opening valuation — see D-5b:** **DR `INVENTORY` (1300) / CR `OPENING_BALANCE_EQUITY` (3100)**; `sourceType = OPENING_INVENTORY` (**NEW**, distinct from the GL `OPENING_BALANCE` so the opening-stock-valuation journals are filterable and never conflated with the GL opening-balance import); `sourceRef = stockOnHand.uid`; `postedBy = operator`.

**(f) Adjustment revaluation — see D-7:** **DR `STOCK_ADJUSTMENT` (5160) / CR `INVENTORY`** (decrease) or the reverse (increase); `sourceType = STOCK_ADJUSTMENT` (**NEW**); `sourceRef = movementUid`; `postedBy = operator`.

**Idempotency (NFR-INV-04).** The event-driven legs (a, b, d) inherit the handlers' existing `IdempotencyGuard` (`processed_events(consumer, event_uid)`): the recompute + the GL post run inside the same `handle(...)` that is skipped wholesale on a re-delivered event. A second defence is the GL **`sourceRef` + `sourceType`**: the poster can short-circuit if an entry with the same `(companyId, sourceType, sourceRef)` already exists (the `journal_entries` carry both — used as a belt-and-braces existence check before posting, the AR/AP precedent). The on-hand recompute is *not* separately idempotent at the row level, so the **`IdempotencyGuard` is the authoritative dedup** for the average (a re-delivered receipt must not double-recompute) — it already wraps the whole handler, so the average and the GL leg are deduped together. The DB backstop `uq_stock_movement_source_event (source_event_uid, product_id)` independently prevents a duplicate movement row.

### D-5 — Reversals reverse at the original cost (FR-INV-05, BR-INV-05, OQ-INV-02)

**Decision: reverse at the original receipt/issue cost**, read from the **`value_amount` recorded on the original `stock_movements` row** (D-2). The reversal handlers already reverse-from-ledger (they read the original GOODS_RECEIPT / SALE_ISSUE rows by `source_document_uid`); they now read each original row's `unit_cost_amount` + `value_amount` and:

- **Sale void (`SaleReversalStockHandler`):** for each original SALE_ISSUE row, post a SALE_REVERSAL movement with `unit_cost = original.unit_cost`, `value = −original.value` (i.e. + back into inventory), **apply the value back to `on_hand_value`** (the DR-1300 effect), and post a GL reversal **DR `INVENTORY` / CR `COGS`** at `|original.value|`. The **`avg_cost` is left unchanged** (an issue never moved it; restoring qty+value at the original cost is consistent). Posted via `postReversalInNewTx` on the stored COGS journal uid if tracked, else a fresh balanced reversing draft with `reversalOfId`.
- **Goods-receipt reversal (`GoodsReceiptReversalStockHandler`):** for each original GOODS_RECEIPT row, post a GOODS_RECEIPT_REVERSAL with `value = −original.value`, **back out the value from `on_hand_value`**, and post **DR `GRNI` / CR `INVENTORY`** at `|original.value|`. **`avg_cost` on a receipt-reversal** is the inverse of the recompute: `newValue = on_hand_value − original.value`, `newQty = qty − original.qty`, `newAvg = (newQty > 0 ? round4(newValue/newQty) : avg_cost)` (keep last-known if it empties to ≤0). This re-derives the average as though the receipt never happened, the symmetric inverse of D-2.

Reversing at the **current** average (the discouraged alternative) would, after intervening receipts moved the average, restore a different value than was removed — a phantom gain/loss on 1300 and a recon break. Reject. The original-cost columns on `stock_movements` are what make the symmetric reversal exact (and are why D-2 adds them rather than deriving cost on the fly).

### D-5b — Opening inventory valuation (FR-INV-06, BR-INV-07) — `InventoryValuationService.setOpeningValue`

A new operation gated **`INVENTORY.OPENING.SET`**, on the on-hand row by uid (or product uid + active branch), in the operator's TX:

- input: opening **unit cost** (`>= 0`). Reject if the product already has a value: **guard** = `avg_cost IS NOT NULL OR on_hand_value <> 0` → reject "already valued" (mirrors the stock opening-balance "rejected if prior movement" rule; BR-INV-07, once per product).
- set `avg_cost = openingCost`; `on_hand_value = round4(quantity × openingCost)`. **The movement row:** the opening valuation is about value, not quantity (the quantity opening-balance is a separate ADR-0010 act). When on-hand qty is **0**, post **no movement row** (a zero-qty movement violates `chk_stock_movement_qty <> 0`) — only set `avg_cost`/`on_hand_value` and audit. When qty `> 0`, the opening valuation does **not** re-post a quantity movement (the quantity already exists); it updates `avg_cost`/`on_hand_value` on the existing on-hand row and audits, the GL leg below carrying the value. (Optionally a zero-effect cost-only annotation is recorded in audit detail; no `stock_movements` row is created for a pure revaluation.)
- post **DR `INVENTORY` (1300) / CR `OPENING_BALANCE_EQUITY` (3100)** at `on_hand_value`, `sourceType = OPENING_INVENTORY`, `sourceRef = stockOnHand.uid`, directly via `GLPostingService.post`. Audited (NFR-INV-03). After this, the report total includes the seeded value and ties to the new 1300 balance (BR-INV-06).
- `OpeningValuationResultDto(productUid, quantity, openingCost, openingValue, glEntryUid, currency)`.

### D-6 — Stock valuation report + the recon bar (FR-INV-07, BR-INV-06, NFR-INV-06)

Lives in **`stock`** as `StockValuationQuery` + a controller `com.erp.api.StockValuationController` (gated `INVENTORY.VALUATION.VIEW`). It is a stock concern (it reads `stock_on_hand`); it reaches into `gl.repository.JournalLineRepository.accountBalance` for the recon expected side — the exact **leaf-reader-into-gl.read** pattern the shipped `CashGlReconciliationQuery` and ADR-0018 Reporting already use (D-12). (Reject extending `reporting`: this is operational stock data, not a financial statement; the recon is a stock-vs-GL tie, not a statement self-check.)

- **Aggregate (in SQL, not row-by-row — NFR-INV-06):** `SELECT product_id, SUM(quantity) qty, SUM(on_hand_value) value FROM stock_on_hand WHERE company_id = ? GROUP BY product_id` (single location v1 — sum across branches per company, since the average is per-company-product; OQ-INV-07), enriched with product code/name via the products read DTO, paginated. Per row: `qty`, `avgCost` (= value/qty for display, or the stored avg), `value` (the authoritative `on_hand_value`).
- **`StockValuationRowDto(productId, productUid, productCode, productName, quantity, avgCost, value, currency)`**; **`StockValuationReportDto(companyId, List<StockValuationRowDto> rows, BigDecimal totalValue, ReconciliationDto recon, currency)`**.
- **Recon bar (BR-INV-06):** `recon = ReconciliationDto.of("Inventory valuation vs GL 1300", computed=Σ on_hand_value, expected=accountBalance(companyId, resolve(INVENTORY).id))`. `ties == false` is a finance-grade defect surfaced on-screen (NFR-INV-01). The 1300 balance read is a single `accountBalance` call (not a row scan — NFR-INV-06). On-screen + CSV export (PDF/XLSX rides the X.1 document enabler — out of scope here).
- `assertCanActIn(principal, principal.companyId())` on the read path (the #1 anti-regression guard); per-company scope (BR-INV-10).

### D-7 — Stock-adjustment revaluation (FR-INV-08, BR-INV-09)

The shipped `StockServiceImpl.adjust(AdjustStockRequest)` (manual `STOCK.ADJUST`, mandatory `AdjustmentReason`) now revalues, synchronously, in the operator's TX:

- compute `value = round4(|adjustQty| × current avg_cost)` at the **current** average (read from the locked on-hand row); the **average is unchanged** (BR-INV-09 — an adjustment in *at the current average* leaves it unchanged; an adjustment out consumes at the current average).
- **decrease** (`quantity < 0`): **DR `STOCK_ADJUSTMENT` (5160, NEW key) / CR `INVENTORY` (1300)** at `value`; reduce `on_hand_value` by `value`. Record the ADJUSTMENT movement with `unit_cost = avg_cost`, `value = −value`.
- **increase** (`quantity > 0`): **DR `INVENTORY` / CR `STOCK_ADJUSTMENT`** at `value`; raise `on_hand_value` by `value`. Movement `value = +value`. (An increase at the current avg does not move the avg.)
- If `avg_cost IS NULL` (no established cost) an adjustment-out **blocks the GL leg** (D-2 edge), quantity still moves, anomaly audited. `sourceType = STOCK_ADJUSTMENT`, `sourceRef = movementUid`, `postedBy = operator`. Posted **directly** via `GLPostingService.post` (a missing config fails the operator's adjust command — BR-INV-12). Audited (NFR-INV-03).

### D-8 — New CoA accounts + `gl_config` keys

Two new posting roles. Added to `GlConfigKey` (Java) and admitted by `chk_gl_config_key` (V17):

| key (NEW) | account code (NEW) | name | `AccountType` / normal balance | role |
|---|---|---|---|---|
| `GRNI` | `2150` | Goods Received Not Invoiced | `LIABILITY` / CREDIT | the receipt→bill clearing bridge (CR on receipt, DR on bill) |
| `STOCK_ADJUSTMENT` | `5160` | Stock Adjustment / Shrinkage | `EXPENSE` / DEBIT | the write-off / shrinkage / count-correction expense home |

`INVENTORY (1300)` + `COGS (5100)` **already exist** as accounts and `gl_config` keys (verified: `ChartOfAccountServiceImpl.DEFAULT_ACCOUNTS` + `GlConfigServiceImpl.DEFAULT_MAPPINGS`) — this slice only finally posts to them. `OPENING_BALANCE_EQUITY (3100)` exists (V11/V12).

**Seeding (two surfaces — new companies AND existing companies):**
1. **New companies:** add `2150 → LIABILITY` and `5160 → EXPENSE` to `ChartOfAccountServiceImpl.DEFAULT_ACCOUNTS`, and `GRNI → 2150` + `STOCK_ADJUSTMENT → 5160` to `GlConfigServiceImpl.DEFAULT_MAPPINGS`. Add a **`InventoryGlSeeder.seedDefaults(companyId)`** (the `ApGlSeeder` pattern — idempotent, called from `BootstrapRunner` + `CompanyService.create`) as the belt-and-braces seeder so a company created out-of-order still gets both. (Adding to the two DEFAULT maps is sufficient for the standard bootstrap; the seeder mirrors the AP/AR/Cash convention for consistency and ordering safety.)
2. **Existing companies (V17 back-seed):** insert `2150`/`5160` into `chart_of_accounts` per company and `GRNI`/`STOCK_ADJUSTMENT` into `gl_configs` per company — **#12-safe seed-uids** (D-10).

### D-9 — Permissions + scope

Two new permissions (V17 seed + `ORG_ADMIN` grant, the V7/V12/V14 pattern):

| code | module | description |
|---|---|---|
| `INVENTORY.VALUATION.VIEW` | `stock` | View the stock valuation report (qty × avg = value) and the GL reconciliation bar |
| `INVENTORY.OPENING.SET` | `stock` | Set the one-time opening inventory valuation (cost/value) for a product |

The **receipt / sale / adjustment** costed postings ride existing permissions (the GR confirm, the sale finalise, `STOCK.ADJUST`) — they are consequences of those acts, not separately gated (FR-INV-10). **`@PreAuthorize`** on the new controller methods gates the two codes; `assertCanActIn` guards the report read and the opening-valuation write (NFR-INV-01). **ScopeGuard:** no new uid-addressed valuation entity is introduced — the report is company-scoped (resolved from `RequestContext`) and opening valuation addresses the existing `stock_on_hand.uid` (resolved to its company on the row). No `ScopeGuard.companyIdOf` switch entry is required.

### D-10 — V17 migration ordering (additive; V1–V16 FROZEN; #12-safe seeds)

`V17__inventory_valuation.sql`, in this order (each block additive; never edits V1–V16 DDL):

1. **ALTER `stock_on_hand`** — `ADD COLUMN avg_cost NUMERIC(19,4)` (NULL), `ADD COLUMN on_hand_value NUMERIC(19,4) NOT NULL DEFAULT 0`; `ADD CONSTRAINT chk_stock_on_hand_avg_nonneg CHECK (avg_cost IS NULL OR avg_cost >= 0)`.
2. **ALTER `stock_movements`** — `ADD COLUMN unit_cost_amount NUMERIC(19,4)` (NULL), `ADD COLUMN value_amount NUMERIC(19,4)` (NULL); `ADD CONSTRAINT chk_stock_movement_cost CHECK ((unit_cost_amount IS NULL AND value_amount IS NULL) OR unit_cost_amount >= 0)`. (No change to `chk_stock_movement_type` — no new movement type; OPENING_BALANCE/ADJUSTMENT reused.)
3. **CoA seed — existing companies** — INSERT `2150 Goods Received Not Invoiced` (LIABILITY/CREDIT) + `5160 Stock Adjustment / Shrinkage` (EXPENSE/DEBIT) per company, `ON CONFLICT (company_id, account_code) DO NOTHING`. Seed-uid `'INV' || lpad(c.id::text,6,'0') || '2150'` / `… || '5160'` (account-code suffix; 3+6+4 = 13 chars ≤ 26, the V12/V14 CoA-seed convention).
4. **`gl_configs` CHECK widen** — `DROP/ADD CONSTRAINT chk_gl_config_key` adding `'GRNI','STOCK_ADJUSTMENT'` to the existing IN-list (the V14 additive-widen pattern; keep all existing tokens).
5. **`gl_configs` key seed — existing companies** — INSERT `GRNI→2150`, `STOCK_ADJUSTMENT→5160` per company joining the just-seeded CoA. **#12-safe seed-uid:** `'INC' || lpad(coa.company_id::text,6,'0') || substr(md5(m.config_key),1,12)` (3+6+12 = 21 chars ≤ 26 — **never** `|| config_key`), `ON CONFLICT (company_id, config_key) DO NOTHING`.
6. **journal source-type CHECK widen** — `DROP/ADD CONSTRAINT chk_journal_batch_source_type` AND `chk_journal_entry_source_type` adding `'STOCK_RECEIPT','COGS','STOCK_ADJUSTMENT','OPENING_INVENTORY'` to the existing IN-list (the V14 pattern; `COGS` was a reserved token, now admitted; keep all existing tokens).
7. **permission seed + grant** — INSERT `INVENTORY.VALUATION.VIEW` + `INVENTORY.OPENING.SET` (module `stock`) `ON CONFLICT (code) DO NOTHING`; grant both to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN (the V7 stock-perm pattern). (Perms have no `uid` — #12 N/A.)

`MigrationKeepDataIT` must extend to V17 (the #12 seed-uid trap fires only on keep-data deploys where companies already exist; CI/Testcontainers DBs have no companies, so the suite is otherwise blind to it). `JournalSourceType` (Java) gains the newly used admitted tokens: `STOCK_RECEIPT` + `OPENING_INVENTORY` (new) and uses `COGS` (was reserved) + `STOCK_ADJUSTMENT` (new) — all admitted by the V17 CHECK.

### D-11 — Rounding & precision (NFR-INV-02, OQ-INV-06, OQ-CUR-03)

`avg_cost` and the movement/value columns are `NUMERIC(19,4)` — a **higher internal scale (4 dp)** than the 0-dp TZS display, to avoid cumulative drift. The recompute divides to 4 dp HALF_UP; every GL leg amount is rounded HALF_UP to the **base-currency posting scale** on the way into the `LineDraft` (consistent with the shipped `Money`/GL posting). The report displays the stored `on_hand_value` (already 4 dp, the authoritative figure) and reconciles `BigDecimal`-exact (`compareTo == 0`) against the 1300 balance. The **display dp for the average** (4 vs 0) is a presentation choice the owner confirms before go-live (OQ-CUR-03); it does not change the stored scale.

### D-12 — ArchUnit edges (no cycle)

- **`stock.service`/`stock.events` → `gl.service`** (`GLPostingService`, `GLConfigResolver`, `GLPostingSafeInvoker`) and **`stock.service` → `gl.repository`** (`JournalLineRepository.accountBalance` for the recon) + **`gl.domain.dto`/`gl.domain.enums`** (`JournalEntryDraft`, `GlConfigKey`, `JournalSourceType`). **Allowed** — the same cross-module-read-into-`gl` stance the shipped `ap.service.BillMatchServiceImpl` (→ `gl.service`) and `cashbank` `CashGlReconciliationQuery` (→ `gl.repository`) already take, and the documented `ModuleBoundaryTest` allowance (ADR-0018 D-12). The shipped `ModuleBoundaryTest` enforces only controller↛repository, service↛controller, and the audit-append-only rule today — this edge introduces **no** violation of the active rules.
- **`stock` → `products`** (`ProductService` DTO reads — already shipped, unchanged).
- **No edge `stock → ap` and no edge `ap → stock`.** The GRNI swap is *within* `ap` (AP reads nothing from stock; it splits its own bill lines by `grLineUid` and resolves `GRNI` via its own `GLConfigResolver`). The valuation slice reads `goods_receipt_lines` only via the cost-on-event payload (D-3) — no `purchases` entity import.
- **No cycle:** stock → gl, ap → gl, sales → (events). gl is a sink; products/gl are leaves stock depends on. The direction is acyclic.

## Consequences

**Positive**
- Inventory becomes a real asset (1300 posted-to); the P&L carries COGS (5100 posted-to); gross margin is visible; the balance sheet shows inventory value. PATH-TO-FULL-ERP §4 critical-dependency #2 is closed, unblocking true P&L and Phase C manufacturing WIP.
- The recon bar makes the valuation finance-grade: `Σ(on_hand_value) == 1300 balance` is a structural self-check (BR-INV-06), the same discipline as VAT/AR/AP/Cash/Reporting.
- The change is additive and surgical: two ALTERs, two accounts, two keys, two permissions, one extended payload field, one swapped AP debit leg, GL legs added to four existing handlers + two manual paths. No table is rebuilt; no shipped DDL is edited.
- Reversals are exact (original cost on the movement row) — no phantom gain/loss, no recon drift from average movement between sale and void.

**Negative / costs**
- Every goods receipt and every sale now does a synchronous GL post in-handler — more work per event; mitigated by REQUIRES_NEW isolation (a GL anomaly degrades to a logged null, never a poisoned dispatch).
- `on_hand_value` is a maintained denormalisation that MUST stay tied to the movement ledger and to 1300; the recon bar is the guardrail, but a bug in the recompute is a finance-grade defect. Tests must assert the tie after every path (receipt, sale, void, receipt-void, adjustment, opening).
- A null-cost / zero-cost receipt drags the average toward zero (accepted, surfaced — BR-INV-01). Negative-on-hand issues block the COGS leg (default) leaving a costless deduction the next receipt repairs — an accepted v1 imprecision (OQ-INV-01).
- Upstream Purchases must populate the new payload field in the same release (cross-module coordination, D-3).

**Neutral / deferred**
- Single location, base currency, moving average only (OQ-INV-07/08, §2). Multi-location per-location cost, FIFO/standard cost, landed cost, batch/serial costing, cycle-count variance, inter-branch-transfer valuation, manufacturing WIP — all deferred, none precluded (NFR-INV-08). The `avg_cost`/`on_hand_value` columns and the per-movement cost columns are the foundation those build on.

## Alternatives considered

- **Costing method — moving weighted average vs FIFO vs standard cost.** *Decided: moving weighted average* (owner-ratified, inventory-valuation.md). FIFO needs cost layers (a `stock_cost_layer` table consumed FIFO); standard cost needs a planned cost + variance accounts. Both are heavier and deferred; the model adds a method dimension later without precluding (NFR-INV-08). The single `avg_cost` column is the moving-average choice made physical.
- **Perpetual mechanism — GRNI clearing at receipt vs expense-at-bill (periodic) vs accrue-to-AP-directly.** *Decided: GRNI at receipt, bill-match swap.* Periodic (status quo) gives no perpetual asset and no COGS — rejected by the requirement. Crediting AP directly at receipt double-counts the payable before the bill exists — rejected. GRNI is the standard receipt-to-invoice bridge and reuses the ADR-0017 D-7 one-leg-swap shape.
- **Cost storage — columns on `stock_on_hand` (+ `stock_movements`) vs a separate `product_cost`/valuation table.** *Decided: columns on the existing rows.* A separate table duplicates the (company, branch, product) key, needs its own version/lock, and forces a second read+write on the hottest path. The on-hand row already exists, already locks, already upserts through one primitive. The per-movement cost columns are required regardless (for exact reversal). A separate table is the discouraged alternative — more joins, no benefit at v1's single-location scope.
- **GRNI granularity — per receipt (one journal, per-line legs) vs per GR line (a journal per line) vs per-receipt net (no per-line legs).** *Decided: one journal per receipt event with per-line DR/CR legs* — one event→one entry (mirrors `SalesPostingHandler`), per-line traceability for partial bill clears, no journal explosion.
- **Stock posts GL vs a separate valuation consumer.** *Decided: stock posts GL* (the recompute and the post must be atomic with the quantity movement, NFR-INV-04; a separate async consumer reintroduces the eventual-consistency gap the requirement forbids). The REQUIRES_NEW safe-invoker gives the GL leg its own commit boundary without a separate module.
- **Reversal cost — original cost vs current average.** *Decided: original cost* (symmetric, no phantom gain/loss; OQ-INV-02). Current average would break the recon after intervening receipts. The cost columns on `stock_movements` exist to make original-cost reversal exact.
- **Concurrency — optimistic `@Version` (with retry) vs `SELECT FOR UPDATE` pessimistic lock.** *Decided: optimistic `@Version` + one retry* (reuse the shipped NFR-STOCK-04 mechanism; the recompute re-reads fresh state on a clash). Pessimistic locking serialises all movements on a hot product and is heavier than the single-instance QA posture needs; the `@Version` is the durable multi-instance guard. Discouraged.
- **Negative-on-hand issue cost — block vs last-known average vs deferred-cost true-up.** *Decided: block the COGS leg, quantity still moves* (OQ-INV-01 recommended default; a config flag offers last-known-avg). Deferred-cost-on-negative + true-up-on-next-receipt is a deferred richer policy (§2).

## Open items (OQ-INV — recommended defaults adopted; none blocks the build)

- **OQ-INV-01 — negative-on-hand issue cost:** adopted **BLOCK the costed leg** (quantity still moves); config flag `inventory.negative-issue-cost = BLOCK|LAST_AVG`, default BLOCK. Owner (finance) may flip to LAST_AVG.
- **OQ-INV-02 — reversal cost:** adopted **original cost** (from the movement `value_amount`). Settled.
- **OQ-INV-03 — GRNI granularity:** adopted **one journal per receipt, per-line legs**. Settled.
- **OQ-INV-04 — GOODS-vs-SERVICE:** adopted **per-line `grLineUid != null` → GRNI; else 5150 Purchases** (a mixed bill splits per leg). The richer dedicated GOODS/SERVICE PO-type flag is a separate Procurement item.
- **OQ-INV-05 — cost-into-event seam:** adopted **extend `StockReceivedPayload.LineItem.unitCostAmount`**; Purchases populates it. Settled.
- **OQ-INV-06 / OQ-CUR-03 — precision:** adopted **4-dp internal `avg_cost`, HALF_UP, base-currency posting scale on legs**; display dp (4 vs 0) confirmed by owner before go-live (presentation only).
- **OQ-INV-07 — multi-location valuation:** deferred; v1 sums per company-product across branches. Not precluded.
- **OQ-INV-08 — multi-currency inventory:** deferred; base currency only (BR-INV-11). Not precluded.
