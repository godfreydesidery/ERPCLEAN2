-- ============================================================================
-- V72 — widen chk_sales_invoice_origin_refs to permit POS origin
-- ============================================================================
-- chk_sales_invoice_origin_refs was defined in V17 permitting only DIRECT /
-- SALES_ORDER. When POS origin was introduced in V37, the *values* constraint
-- (chk_sales_invoice_origin) was widened to add 'POS' but this *refs* constraint
-- was not — so a real POS sale (origin='POS', null source refs) violates it at the
-- DB. (No end-to-end POS-sale IT existed, so it went unnoticed.)
--
-- Additive widen via DROP-IF-EXISTS / re-ADD (the sanctioned CHECK-widen pattern,
-- ADR-0043 / migrations runbook). The new predicate is a SUPERSET of the old one
-- (DIRECT and SALES_ORDER rows still satisfy it), so it applies cleanly onto a
-- populated sales_invoices table.
ALTER TABLE sales_invoices
    DROP CONSTRAINT IF EXISTS chk_sales_invoice_origin_refs;

ALTER TABLE sales_invoices
    ADD CONSTRAINT chk_sales_invoice_origin_refs
        CHECK (
            (origin IN ('DIRECT','POS') AND source_order_uid IS NULL AND source_delivery_uid IS NULL)
            OR (origin = 'SALES_ORDER' AND source_order_uid IS NOT NULL)
        );
