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
