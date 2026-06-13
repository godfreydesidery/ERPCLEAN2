-- V38 — Inventory Depth (ADR-0028, D-3/D-13): behaviour-neutral on-hand re-grain.
-- 3-step backfill (NFR-INVD-09): ADD NULL → UPDATE → SET NOT NULL + re-grain constraint.
-- Also widens chk_stock_movement_type to admit TRANSFER_OUT / TRANSFER_IN (D-3, OQ-STOCK-08).
-- Also adds cost-centre dimension columns to stock_movements (ADR-0025 D-6 carried forward).
-- MUST run after V37 (location master + default-location backfill must exist).
-- Additive only. V1–V37 are FROZEN.

-- ============================================================================
-- (1a) ADD location_id to stock_on_hand — NULL first (3-step backfill)
-- ============================================================================
ALTER TABLE stock_on_hand
    ADD COLUMN location_id BIGINT;

-- ============================================================================
-- (1b) ADD location_id to stock_movements — NULL first
-- ============================================================================
ALTER TABLE stock_movements
    ADD COLUMN location_id BIGINT;

-- ============================================================================
-- (2a) BACKFILL stock_on_hand.location_id → the branch's default location
-- ============================================================================
UPDATE stock_on_hand soh
SET    location_id = sl.id
FROM   stock_locations sl
WHERE  sl.branch_id  = soh.branch_id
  AND  sl.company_id = soh.company_id
  AND  sl.is_default = true;

-- ============================================================================
-- (2b) BACKFILL stock_movements.location_id → the branch's default location
-- ============================================================================
UPDATE stock_movements sm
SET    location_id = sl.id
FROM   stock_locations sl
WHERE  sl.branch_id  = sm.branch_id
  AND  sl.company_id = sm.company_id
  AND  sl.is_default = true;

-- ============================================================================
-- (3a) SET NOT NULL + FK + re-grain unique constraint on stock_on_hand
-- ============================================================================
ALTER TABLE stock_on_hand
    ALTER COLUMN location_id SET NOT NULL;

ALTER TABLE stock_on_hand
    ADD CONSTRAINT fk_stock_on_hand_location
        FOREIGN KEY (location_id) REFERENCES stock_locations (id);

-- Drop the old (company, branch, product) unique; re-grain to include location_id (D-3)
ALTER TABLE stock_on_hand
    DROP CONSTRAINT uq_stock_on_hand_scope;

ALTER TABLE stock_on_hand
    ADD CONSTRAINT uq_stock_on_hand_scope
        UNIQUE (company_id, branch_id, location_id, product_id);

CREATE INDEX ix_stock_on_hand_location
    ON stock_on_hand (company_id, branch_id, location_id);

-- ============================================================================
-- (3b) SET NOT NULL + FK + index on stock_movements
-- ============================================================================
ALTER TABLE stock_movements
    ALTER COLUMN location_id SET NOT NULL;

ALTER TABLE stock_movements
    ADD CONSTRAINT fk_stock_movement_location
        FOREIGN KEY (location_id) REFERENCES stock_locations (id);

CREATE INDEX ix_stock_movements_location
    ON stock_movements (company_id, branch_id, location_id, product_id, occurred_at);

-- ============================================================================
-- (4) Widen chk_stock_movement_type to admit TRANSFER_OUT + TRANSFER_IN + PURCHASE_RETURN
--     Union of ALL prior tokens + new tokens (additive DROP/ADD pattern, D-3, OQ-STOCK-08).
--     PURCHASE_RETURN added here as superset for procurement-depth (V36 runs before V38).
-- ============================================================================
ALTER TABLE stock_movements
    DROP CONSTRAINT IF EXISTS chk_stock_movement_type;

ALTER TABLE stock_movements
    ADD CONSTRAINT chk_stock_movement_type CHECK (
        movement_type IN (
            'GOODS_RECEIPT', 'SALE_ISSUE', 'SALE_REVERSAL',
            'GOODS_RECEIPT_REVERSAL', 'ADJUSTMENT', 'OPENING_BALANCE',
            'TRANSFER_OUT', 'TRANSFER_IN',
            -- procurement-depth (ADR-0027) ---
            'PURCHASE_RETURN'
        )
    );

-- ============================================================================
-- (5) Also widen chk_stock_movement_reason to allow NULL reason for TRANSFER_*
--     The existing CHECK already allows reason_code = NULL for non-ADJUSTMENT types,
--     so TRANSFER_OUT / TRANSFER_IN (non-ADJUSTMENT) already satisfy it — no change needed.
--     Verified: (movement_type = 'ADJUSTMENT' AND reason_code IS NOT NULL)
--               OR (movement_type <> 'ADJUSTMENT' AND reason_code IS NULL)
--     TRANSFER_* are non-ADJUSTMENT, so reason_code IS NULL passes. No DDL change required.
-- ============================================================================
