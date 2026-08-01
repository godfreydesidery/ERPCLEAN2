-- ============================================================================
-- V93 — Configurable "sale at or below cost" policy (owner decision 2026-08-01).
--
-- A cashier can currently ring a line at any price, including at or under what the stock cost the
-- company. This adds a per-company policy for that case, expressed as an ACTION rather than a
-- boolean so every stance the owner named is representable:
--   OFF     — no check (today's behaviour, and the default so nothing changes on upgrade);
--   WARN    — allow the sale, record that it went out at or below cost;
--   APPROVE — reject unless the caller supplied the below-cost approval AND holds
--             SALES.BELOW_COST.OVERRIDE (the mode the client wants);
--   BLOCK   — always reject.
--
-- Cost basis is the moving-average cost already maintained on stock_on_hand (ADR-0020), read via
-- the existing LeafCostResolver port; an unknown/zero cost never blocks a sale. Enforced
-- synchronously by BelowCostGuard at sales-invoice finalise (which is also the POS path) —
-- see its javadoc for why that is THE enforcement point.
--
-- Metadata-only ADD COLUMN ... DEFAULT on the existing (populated) sales_settings table
-- (V79__sales_settings.sql, extended by V87) — additive, no backfill needed, safe on Postgres 11+.
-- The CHECK is added in a second statement so it validates against rows that already carry the
-- default; every existing row is 'OFF', so it validates instantly.
-- ============================================================================

ALTER TABLE sales_settings
    ADD COLUMN below_cost_action VARCHAR(16) NOT NULL DEFAULT 'OFF';

ALTER TABLE sales_settings
    ADD CONSTRAINT chk_sales_settings_below_cost_action
    CHECK (below_cost_action IN ('OFF','WARN','APPROVE','BLOCK'));
