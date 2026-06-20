-- V71 — Age-restricted item gate (ADR-0044 D-3a / BR-11). Additive ALTER on populated products.
--
-- restricted_kind: VARCHAR(20) CHECK enum, DEFAULT 'NONE'. Every existing product row is
-- backfilled to NONE by the DEFAULT clause — no separate UPDATE needed, no expand/constrain
-- ceremony required. Mirrors the vat_status column shape (V8). Safe on a live table: the DEFAULT
-- is applied inline by PostgreSQL 15 (fast metadata-only when NOT NULL + DEFAULT).
ALTER TABLE products
    ADD COLUMN restricted_kind VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE products
    ADD CONSTRAINT chk_product_restricted_kind
        CHECK (restricted_kind IN ('NONE','AGE_18','AGE_21'));
