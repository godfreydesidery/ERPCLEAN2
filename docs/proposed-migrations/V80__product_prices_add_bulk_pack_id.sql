-- ============================================================================
-- PROPOSED — DEFERRED ITEM D-1 (1 of 2): multi-unit / per-pack pricing.
-- Assign the real V<n> (next free vs origin/develop, in build order) when this
-- ships with the PriceResolution + product-price UI change. Additive only.
-- Depends on: product_bulk_packs (V3). ADR: to be written.
-- ============================================================================
-- Expand product_prices with a nullable bulk_pack_id.
--   bulk_pack_id NULL  = the existing per-base-unit price (back-compatible).
--   bulk_pack_id set   = a pack-specific (non-linear) price for that pack size.

ALTER TABLE product_prices
    ADD COLUMN bulk_pack_id BIGINT NULL;

-- FK validates instantly: the new column is all-NULL on the populated table.
ALTER TABLE product_prices
    ADD CONSTRAINT fk_product_price_bulk_pack
        FOREIGN KEY (bulk_pack_id) REFERENCES product_bulk_packs (id);
