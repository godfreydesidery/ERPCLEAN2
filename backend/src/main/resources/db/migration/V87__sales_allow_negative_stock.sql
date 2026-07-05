-- ============================================================================
-- Configurable "block negative stock on sale" (owner decision 2026-07-05).
--
-- Default behaviour is unchanged: a sale that would drive stock negative is BLOCKED
-- (allow_negative_stock = false). A company may opt into backorder by flipping this flag via
-- SalesSettingsServiceImpl.update (SALES.SETTINGS.MANAGE, already seeded — no new permission).
--
-- Metadata-only ADD COLUMN ... DEFAULT on the existing (populated) sales_settings table
-- (V79__sales_settings.sql) — additive, no backfill needed, safe on Postgres 11+.
-- ============================================================================

ALTER TABLE sales_settings ADD COLUMN allow_negative_stock BOOLEAN NOT NULL DEFAULT false;
