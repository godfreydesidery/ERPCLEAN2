-- ============================================================================
-- PROPOSED — DEFERRED ITEM D-1 (2 of 2): re-key product_prices uniqueness.
-- Assign the real V<n> immediately AFTER D1a. Additive only. ADR: to be written.
--
-- CREATE INDEX CONCURRENTLY cannot run inside a transaction, so this migration
-- MUST be non-transactional. Keep the directive below (Flyway 9.1+).
-- ============================================================================
-- flyway:executeInTransaction=false

-- Postgres treats NULLs as DISTINCT in a plain UNIQUE, so a single
-- UNIQUE(product_id, price_list_id, bulk_pack_id) would allow many base rows.
-- Split into two PARTIAL unique indexes: one base price per (product, list) AND
-- at most one price per (product, list, pack).
--
-- NOTE: brief window with no uniqueness enforcement between the DROP and the
-- CONCURRENTLY builds — run in a maintenance window.

ALTER TABLE product_prices DROP CONSTRAINT IF EXISTS uq_product_price;

CREATE UNIQUE INDEX CONCURRENTLY uq_product_price_base
    ON product_prices (product_id, price_list_id)
    WHERE bulk_pack_id IS NULL;

CREATE UNIQUE INDEX CONCURRENTLY uq_product_price_pack
    ON product_prices (product_id, price_list_id, bulk_pack_id)
    WHERE bulk_pack_id IS NOT NULL;
