-- V91 — Weighed-goods safety ceiling (ADR-0044 D-1b follow-up).
--
--   max_sale_weight : a fat-finger at the till — keying "813" (meaning 813 g) against a per-kg
--                     product — rings 813 kg. When set, a weighed sale line above this weight is
--                     rejected. Part of the weighing profile (is_weighed / tare_weight / scale_step,
--                     V89), in the base weight unit.
--
-- Nullable + additive (NULL = today's behaviour: no ceiling), safe on a populated table, consistent
-- with the frozen-schema / durable-DB discipline. Must be > 0 when set.

ALTER TABLE products
    ADD COLUMN max_sale_weight NUMERIC(19,6);

ALTER TABLE products
    ADD CONSTRAINT chk_product_max_sale_weight_positive
        CHECK (max_sale_weight IS NULL OR max_sale_weight > 0);
