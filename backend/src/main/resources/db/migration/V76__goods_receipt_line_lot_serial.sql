-- V76: capture batch/lot, manufacture/expiry dates, and per-unit serial/IMEI numbers at goods receipt.
-- Additive: nullable columns on goods_receipt_lines + a new child table. No backfill, no seed.
-- Soft validation is enforced at the app layer (all optional; serials not unique-constrained here).
-- The receiving location is NOT stored — it is resolved at posting time to the branch's default
-- stock_locations row (is_default = true), feeding stock_batches / stock_serials.

ALTER TABLE goods_receipt_lines
    ADD COLUMN lot_number       VARCHAR(60),
    ADD COLUMN manufacture_date DATE,
    ADD COLUMN expiry_date      DATE;

CREATE TABLE goods_receipt_line_serials (
    id                    BIGSERIAL    PRIMARY KEY,
    uid                   VARCHAR(26)  NOT NULL,
    goods_receipt_line_id BIGINT       NOT NULL
        REFERENCES goods_receipt_lines (id),
    company_id            BIGINT       NOT NULL,
    serial_number         VARCHAR(80)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            BIGINT,
    CONSTRAINT uq_goods_receipt_line_serial_uid UNIQUE (uid)
);

CREATE INDEX ix_goods_receipt_line_serials_line
    ON goods_receipt_line_serials (goods_receipt_line_id);
