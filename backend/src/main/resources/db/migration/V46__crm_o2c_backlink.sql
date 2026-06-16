-- V52 — CRM → O2C convert back-link (ADR-0031 D-7, Stage 2):
-- Adds a nullable source_opportunity_uid to quotations and sales_orders
-- so the converted document carries a trace back to the CRM opportunity.
-- Mirrors ADR-0021 source_quotation_uid (additive; existing rows default to NULL).
-- V1–V51 are FROZEN.

-- ============================================================================
-- (1) ALTER quotations — add source_opportunity_uid  (ADR-0031 D-7-(3))
-- ============================================================================

ALTER TABLE quotations
    ADD COLUMN source_opportunity_uid VARCHAR(26) NULL;

CREATE INDEX ix_quotations_source_opportunity
    ON quotations (source_opportunity_uid)
    WHERE source_opportunity_uid IS NOT NULL;

-- ============================================================================
-- (2) ALTER sales_orders — add source_opportunity_uid  (ADR-0031 D-7-(3))
-- ============================================================================

ALTER TABLE sales_orders
    ADD COLUMN source_opportunity_uid VARCHAR(26) NULL;

CREATE INDEX ix_sales_orders_source_opportunity
    ON sales_orders (source_opportunity_uid)
    WHERE source_opportunity_uid IS NOT NULL;
