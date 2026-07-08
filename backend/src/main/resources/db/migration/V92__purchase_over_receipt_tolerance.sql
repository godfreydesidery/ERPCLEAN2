-- V92 — Configurable goods-receipt over-receipt tolerance (Saidi #4).
--
-- Standard ERP practice (SAP over-delivery tolerance, Oracle receiving tolerances, D365, Odoo) is
-- to treat over-receipt as a NORMAL, configurable event — a percent with an allow/block action —
-- rather than a hard "received <= ordered" block. Commodities delivered by weight (rice, produce)
-- never match the PO exactly and can come in slightly over. This makes over-receipt an opt-in
-- per-company policy (default OFF = today's strict behaviour), mirroring the existing
-- purchase_settings.match_tolerance_pct convention.
--
-- Two changes:
--   1. purchase_settings.receipt_tolerance_pct — over-receipt tolerance percent (e.g. 5.0000 = 5%).
--      NULL / 0 = strict (no over-receipt). The goods-receipt service reads it and allows a receipt
--      to exceed the outstanding PO quantity by up to this percent.
--   2. Relax chk_purchase_order_line_received from "received <= ordered" to "received >= 0". With
--      over-receipt now a legitimate state, the upper-bound is no longer an invariant; the ceiling
--      (outstanding x (1 + tolerance)) is enforced in the service (GoodsReceiptServiceImpl), which
--      already owns the over-receipt check and its user-facing message. The >= 0 sanity guard stays.
--
-- Additive + safe on populated tables: the new column is nullable; the constraint swap only widens
-- the allowed range, so every existing row still satisfies it (drop + re-add validates instantly).

ALTER TABLE purchase_settings
    ADD COLUMN receipt_tolerance_pct NUMERIC(9,4);

ALTER TABLE purchase_settings
    ADD CONSTRAINT chk_purchase_settings_receipt_tolerance_nonneg
        CHECK (receipt_tolerance_pct IS NULL OR receipt_tolerance_pct >= 0);

ALTER TABLE purchase_order_lines
    DROP CONSTRAINT chk_purchase_order_line_received;

ALTER TABLE purchase_order_lines
    ADD CONSTRAINT chk_purchase_order_line_received
        CHECK (received_qty_in_base >= 0);
