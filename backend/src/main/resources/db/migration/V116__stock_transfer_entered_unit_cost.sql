-- ============================================================================
-- V116 — Stock transfer line: user-entered reference unit cost. DDL approved by the owner 2026-11-21.
-- Reference only: valuation and GL keep using the company moving average (ADR-0028 D-2/D-5).
-- Additive: one nullable column + a CHECK; existing rows stay NULL ("not entered"), no backfill.
-- ============================================================================
SET LOCAL lock_timeout = '10s';   -- ALTER locks stock_transfer_lines briefly

ALTER TABLE stock_transfer_lines
    ADD COLUMN entered_unit_cost NUMERIC(19,4);          -- per unit of the line's unit_id; NULL = not entered

ALTER TABLE stock_transfer_lines
    ADD CONSTRAINT chk_stock_transfer_line_entered_cost
        CHECK (entered_unit_cost IS NULL OR entered_unit_cost >= 0);
