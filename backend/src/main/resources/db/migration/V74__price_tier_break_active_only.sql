-- V74 — PRICING-053: make the price-tier "break" uniqueness apply to ACTIVE tiers only.
--
-- Problem: V36 created uq_price_tier_break as an UNCONDITIONAL unique constraint on
-- (product_id, price_list_id, min_qty). The service-layer duplicate check already
-- excludes INACTIVE tiers, but the DB constraint did not — so re-creating a tier at a
-- min_qty whose previous tier was deactivated still failed with 23505 (HTTP 409).
--
-- Fix: replace the constraint with a PARTIAL unique index that only enforces uniqueness
-- over ACTIVE rows. INACTIVE (soft-deleted) tiers may now share a min_qty with a new
-- ACTIVE tier. Additive/replacement only; no data change.
--
-- Safe on the populated DB: the old unconditional constraint guaranteed there are no
-- duplicate (product_id, price_list_id, min_qty) rows at all, so the ACTIVE subset is
-- already unique and the new index builds without conflict. IF EXISTS / IF NOT EXISTS
-- guards keep it idempotent across environments.

ALTER TABLE price_tiers DROP CONSTRAINT IF EXISTS uq_price_tier_break;

CREATE UNIQUE INDEX IF NOT EXISTS uq_price_tier_break
    ON price_tiers (product_id, price_list_id, min_qty)
    WHERE status = 'ACTIVE';
