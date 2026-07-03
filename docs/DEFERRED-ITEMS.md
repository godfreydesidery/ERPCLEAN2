# Deferred engineering items

Items consciously deferred (owner-approved) to be addressed on a later day. Each needs a design
decision (ADR) and/or a schema migration, so they are tracked here rather than rushed.

> Process: before implementing any of these, present the proposed approach + DDL + version number
> to the owner for approval (per the migration-approval standing rule), and write the ADR.

---

## D-1 · Per-pack / multi-unit pricing (weight/volume goods)

**Deferred:** 2026-06-24 (owner). **Effort:** L. **Needs:** ADR + migration.

**The gap.** A product can have only ONE price per price list — `product_prices` is
`UNIQUE(product_id, price_list_id)` with no unit/pack column. Units of measure already support
dimensions (COUNT/WEIGHT/VOLUME/LENGTH/TIME) and `product_bulk_packs` already model multiple pack
sizes over a base unit via `factor_to_base`, but pricing is strictly **linear** in base units. So
you cannot price, e.g., a chocolate sold by grams where a 500 g pack = 5,000 and a 1,000 g pack =
6,000 (non-linear) — the 1,000 g pack is always exactly 2× the per-gram list price.

**Way forward (outline).**
- Add a nullable `unit_id` (or `bulk_pack_id`) to `product_prices`; widen the unique key to
  `(product_id, price_list_id, unit_id)`, where `NULL unit_id` = the existing per-base-unit price
  (back-compat).
- `PriceResolutionServiceImpl` resolves price by product + price list + selected unit, falling back
  to the base-unit row × factor when no pack-specific row exists.
- Thread the selected unit into price resolution across sales order / invoice / quotation / POS.
- Secondary: fix the UoM seeders — GRAM/KG/LITRE/ML are seeded with the COUNT default dimension and
  `decimal_places = 0`; they should be WEIGHT/VOLUME with sensible decimals/`is_fractional`.

**Key files:** `ProductPrice` + `product_prices` (V3), `PriceResolutionServiceImpl`,
`SalesOrderServiceImpl.resolveListPrice`/`appliedPrice` (+ invoice/quotation/POS equivalents),
`UnitOfMeasureSeeder` / `V4__units_of_measure.sql`.

---

## D-2 · Batch / serial(IMEI) reversal on GRN void

**Deferred:** 2026-06-24 (from the V76 GRN-tracking work). **Effort:** M. **Needs:** ADR.

**The gap.** Goods receipt now writes `stock_batches` (lot/expiry) and `stock_serials` (per-unit
IMEI) on receipt (V76). Voiding a receipt correctly reverses **quantity + GL**, but does **not**
back out those batch/serial rows — they retain their original receipt values until addressed. A
clear `TODO` is in `GoodsReceiptReversalStockHandler`.

**Way forward (outline).** Add two purpose-built reversal methods and call them from the void
handler symmetrically with the forward path:
- `StockBatchService.reverseReceiptQty(companyId, branchId, locationId, productId, lotNumber, qty)`
- `StockSerialService.removeReceived(companyId, productId, serialNumber)` (delete or mark RETURNED)

**Key files:** `GoodsReceiptReversalStockHandler` (the TODO), `StockBatchServiceImpl`,
`StockSerialServiceImpl`.

---

## D-3 · Requisition "Convert" that actually creates the RFQ / PO

**Deferred:** 2026-07-03 (round-2 persona interviews — Yusuf). **Effort:** M. **Needs:** design (ADR); likely no schema.

**The gap.** The purchase-requisition **Convert** action (`RequisitionServiceImpl.convert`) sets
the requisition status to `CONVERTED` and returns a success toast, **but never creates the target
RFQ or PO** and never sets `convertedToUid` — so the "View RFQ/PO" link is a dead end. The claimed
document does not exist.

**Way forward (outline).** Decide the conversion contract: does Convert produce an RFQ (multi-
supplier quote) or a PO directly? Either way it needs a **supplier** (Convert must prompt for one,
or convert per-supplier) and a **line mapping** (requisition lines → RFQ/PO lines with qty + UoM).
Create the target document in the same TX, set `convertedToUid`, and only then flip status +
toast. Add a service test asserting the target doc is actually persisted. The RFQ/PO tables already
exist, so this is likely **no migration** — but confirm `convertedToUid` columns exist on both
sides before starting.

**Key files:** `RequisitionServiceImpl.convert`, `RfqServiceImpl` / `PurchaseOrderServiceImpl`
(create paths), the requisition detail web component (Convert button + dead link).

---

## D-4 · Sales-order approval — automatic threshold gate

**Deferred:** 2026-07-03 (round-2 persona interviews — Bakari/Halima/Emanuel). **Effort:** M. **Needs:** migration + ADR.

**The gap.** A **manual, no-schema** sales-order approval flow shipped this session (a
"Submit for approval" action routes to the generic approval engine as document type `SALES_ORDER`;
`SalesOrderDto.approvalStatus` is engine-derived on read; `confirm()` is blocked while the request
is PENDING/REJECTED). What it does **not** do is *automatically require* approval for orders above a
configurable value — submission is manual, because there is no sales-side settings/threshold table
(POs use `purchase_settings.po_approval_threshold_amount`; sales has no equivalent).

**Way forward (outline).** Mirror the PO seam: add a `sales_settings` row (or reuse a company-scoped
settings table) with `so_approval_enabled` + `so_approval_threshold_amount`, and a
`SalesApprovalGate` that auto-submits over-threshold orders at confirm time (exactly like
`PoApprovalGate.requiresApproval` / `submit`). Then confirm hard-blocks over-threshold orders until
approved instead of relying on a manual click.

**Key files:** `PoApprovalGate` (template), `SalesOrderServiceImpl.doConfirm`, new
`SalesApprovalGate` + `SalesSettings` entity/migration.

---

## D-5 · Default the sales agent to the logged-in user

**Deferred:** 2026-07-03 (round-2 persona interviews — Hamisi). **Effort:** S–M. **Needs:** migration + design.

**The gap.** Route agents want a new sales order / invoice to pre-fill the **agent = themselves**.
There is **no link between `app_user` and `agent`** in the schema (`app_user` has no `agent_id`;
`agent` has no `user_id`), so the app cannot resolve "the current user's agent record" to default it.

**Way forward (outline).** Add a nullable `user_id` on `agent` (or `agent_id` on `app_user`) with a
uniqueness/backfill decision (an agent maps to at most one login; some users are not agents). On new
SO/invoice, if the current user has a linked agent, pre-select it (still editable). Expand→backfill→
constrain across migrations per the durable-DB rules.

**Key files:** `Agent` entity + migration, `SalesOrderServiceImpl.create` / `SalesInvoiceServiceImpl.create`
(default resolution), the SO/invoice create web forms.

---

## D-6 · EFD / fiscal receipt integration (TRA)

**Deferred:** 2026-07-03 (round-2 persona interviews — Sabina). **Effort:** L. **Needs:** ADR + migration + external device/API.

**The gap.** Counter sales need a **fiscal (EFD) receipt** issued to the tax authority (TRA VFD/EFD
in TZ). The system has no fiscalisation record or device/API integration; "EFD receipt" is a persona
expectation with no backing feature.

**Way forward (outline).** Design a fiscalisation seam (device/VFD API abstraction), persist a
`fiscal_receipt` record per invoice (fiscal number, verification URL/QR, signature, status), and a
retry/queue for offline. This is a substantial external-integration workstream — scope separately.

**Key files:** new `fiscal` concern; `SalesInvoiceServiceImpl` (post-issue hook), invoice detail
web (receipt render/print).

---

## D-7 · End-of-Day Cash Count + Petty Cash

**Deferred:** 2026-07-03 (round-2 persona interviews — John). **Effort:** M–L. **Needs:** ADR + migration.

**The gap.** The cashier has receipt posting but **no end-of-day cash-count/reconciliation** (counted
cash vs expected, over/short) and **no petty-cash** workflow (float, disbursements, replenishment).

**Way forward (outline).** Add `cash_count` (session, denominations, expected vs counted, variance →
GL) and a `petty_cash` float/disbursement model with approval + replenishment; wire both into the
cash & bank module and reporting.

**Key files:** new cash-count / petty-cash entities + migrations; cash & bank service + web screens.

---

## D-8 · Van-stock reconciliation (route sales)

**Deferred:** 2026-07-03 (round-2 persona interviews — Hamisi). **Effort:** L. **Needs:** ADR + migration.

**The gap.** Route agents load stock onto a van, sell off it on the round, and must **reconcile van
stock** (loaded − sold − returned = on-van) at day end. There is no mobile/van stock location model
or reconciliation flow.

**Way forward (outline).** Model the van as a (mobile) stock location per agent; load = transfer to
van, route sales issue from the van location, day-end reconciliation posts variances. Integrates
stock + sales + the route module.

**Key files:** stock location model (van type), `StockTransfer*`, route-sales issue path, new
van-reconciliation service + web screen.
