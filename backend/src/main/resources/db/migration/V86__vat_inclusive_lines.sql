-- ============================================================================
-- V86 — VAT-inclusive pricing (ADR-0056): per-line snapshot of whether the resolved
-- price came from a VAT-inclusive price list.
--
-- The inclusive/exclusive STANCE lives on price_lists.price_includes_vat (already shipped,
-- P2 D5 / ADR-0041 — plumbed through entity/DTOs/CRUD but read by nothing in pricing math
-- until this change). Each sales line now SNAPSHOTS that stance at line-add time so the
-- totals calculator can strip VAT out of a gross (inclusive) price without re-reading a
-- price list that may since have changed (immutability of a posted/drafted line, matching
-- the existing list_price_amount/vat_status/vat_rate snapshot columns).
--
-- Metadata-only on populated tables: NOT NULL DEFAULT false backfills every existing row to
-- false (= exclusive = today's only behaviour), so no existing total changes (ADR-0056).
-- Plain transactional ALTER — same shape as V80 (product_prices.unit_id-adjacent additive add).
-- ============================================================================

ALTER TABLE sales_invoice_lines ADD COLUMN price_inclusive BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE sales_order_lines   ADD COLUMN price_inclusive BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE quotation_lines     ADD COLUMN price_inclusive BOOLEAN NOT NULL DEFAULT false;
