-- D-1 (ADR-0048): multi-unit / per-pack pricing.
-- Adds a nullable unit_id to product_prices — NULL means "the base-unit price" (every existing
-- row), a set value means a per-unit (pack-specific, possibly non-linear) price override.
-- All-NULL on the populated table, so the FK validates instantly (no backfill needed).
-- Transactional (plain ALTER TABLE); uniqueness is repartitioned separately in V81.
ALTER TABLE product_prices ADD COLUMN unit_id BIGINT NULL;

ALTER TABLE product_prices
    ADD CONSTRAINT fk_product_price_unit
        FOREIGN KEY (unit_id) REFERENCES units_of_measure (id);
