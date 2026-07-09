-- V89 — First-class weighed goods (ADR-0044 D-1b). Additive ALTER on populated products.
--
-- Loose / scooped supermarket goods (rice, produce, deli) are sold BY WEIGHT: the price is
-- per weight-unit (e.g. per kg) and the line quantity is the actual weighed amount. The sale
-- arithmetic already supports this (quantity is NUMERIC(19,6); amount = unitPrice × quantity),
-- but nothing marks a product as "must be weighed" so an API-only client could silently ring
-- 1 unit instead of 0.810 kg. These three columns make the intent explicit and let the sale
-- path validate it.
--
--   is_weighed  : product is sold by weight. Its base unit must be a WEIGHT unit (enforced in
--                 the service, not the DB — a cross-table CHECK would need a trigger). DEFAULT
--                 false backfills every existing row inline (fast metadata-only on PG15),
--                 mirroring the vat_status / restricted_kind column shape (V8 / V71).
--   tare_weight : optional container/tare weight in the base weight unit. Stored as product
--                 configuration for the weighing client to deduct before submitting NET weight
--                 (weighing is client-orchestrated per ADR-0044 D-7); the server does not
--                 auto-deduct it, so a net scale label is never double-tared.
--   scale_step  : optional scale division (e.g. 0.005 kg). When set, the sale path rounds the
--                 weighed quantity to the nearest multiple (HALF_UP). NULL = no rounding.
--
-- All three are additive and safe on a live table (nullable, or NOT NULL + DEFAULT). Consistent
-- with the ADR-0043 frozen-schema / durable-DB discipline — no edits to shipped migrations.

ALTER TABLE products
    ADD COLUMN is_weighed  BOOLEAN       NOT NULL DEFAULT false,
    ADD COLUMN tare_weight NUMERIC(19,6),
    ADD COLUMN scale_step  NUMERIC(19,6);

ALTER TABLE products
    ADD CONSTRAINT chk_product_tare_weight_nonneg
        CHECK (tare_weight IS NULL OR tare_weight >= 0);

ALTER TABLE products
    ADD CONSTRAINT chk_product_scale_step_positive
        CHECK (scale_step IS NULL OR scale_step > 0);
