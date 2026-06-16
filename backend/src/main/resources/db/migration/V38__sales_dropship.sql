-- V44 — Sales Depth: Drop-ship (ADR-0029, Stage 2a)
-- ALTER sales_order_lines ADD fulfilment_mode + drop-ship cols;
-- ALTER purchase_orders ADD ship_to_customer_id + source_sales_order_uid;
-- permission seed + ORG_ADMIN grant: SALES.DROPSHIP.VIEW / CREATE.
-- Additive only. V1–V43 (excl. in-flight V20–V41) are FROZEN.

-- ============================================================================
-- (1) ALTER sales_order_lines — add fulfilment_mode + drop-ship tracking columns
-- ============================================================================
ALTER TABLE sales_order_lines
    ADD COLUMN fulfilment_mode            VARCHAR(20)     NOT NULL DEFAULT 'OWN_STOCK',
    ADD COLUMN dropship_supplier_id       BIGINT,
    ADD COLUMN dropship_po_uid            VARCHAR(26),
    ADD COLUMN dropship_unit_cost_amount  NUMERIC(19,4);

ALTER TABLE sales_order_lines
    ADD CONSTRAINT chk_sales_order_line_fulfilment
        CHECK (fulfilment_mode IN ('OWN_STOCK','DROP_SHIP'));

ALTER TABLE sales_order_lines
    ADD CONSTRAINT chk_sales_order_line_dropship
        CHECK (
            (fulfilment_mode = 'OWN_STOCK' AND dropship_supplier_id IS NULL)
            OR (fulfilment_mode = 'DROP_SHIP' AND dropship_supplier_id IS NOT NULL)
        );

-- ============================================================================
-- (2) ALTER purchase_orders — add ship-to-customer + source SO link (D-9)
--     These are Purchases-owned additive columns riding V44.
-- ============================================================================
ALTER TABLE purchase_orders
    ADD COLUMN ship_to_customer_id       BIGINT,
    ADD COLUMN source_sales_order_uid    VARCHAR(26);
