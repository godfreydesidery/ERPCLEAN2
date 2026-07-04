-- D-1 (ADR-0048): repartition product_prices uniqueness into two partial unique indexes now that
-- unit_id (V80) lets a product carry more than one price per (product, price_list) — one base row
-- (unit_id IS NULL) plus zero or more per-unit override rows. Postgres treats NULLs as DISTINCT, so
-- a single UNIQUE(product, price_list, unit) would permit many base rows — hence the split.
--
-- Plain (transactional) index builds — this repo has NO non-transactional migration pattern
-- (see the V78 header: CREATE INDEX CONCURRENTLY is deliberately avoided; there is no .conf /
-- executeInTransaction wiring). product_prices is small, so a brief share lock during the build is
-- fine. The whole migration runs in one transaction, so the constraint→index swap is atomic (no
-- uniqueness gap). DROP INDEX IF EXISTS guards keep it idempotent.

DROP INDEX IF EXISTS uq_product_price_base;
CREATE UNIQUE INDEX uq_product_price_base
    ON product_prices (product_id, price_list_id)
    WHERE unit_id IS NULL;

DROP INDEX IF EXISTS uq_product_price_pack;
CREATE UNIQUE INDEX uq_product_price_pack
    ON product_prices (product_id, price_list_id, unit_id)
    WHERE unit_id IS NOT NULL;

-- old table-wide unique is now subsumed by uq_product_price_base
ALTER TABLE product_prices DROP CONSTRAINT IF EXISTS uq_product_price;
