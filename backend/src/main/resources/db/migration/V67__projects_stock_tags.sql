-- V67 — Projects: stock movement tags + ISSUE_TO_PROJECT movement type (ADR-0033 D-3/D-5/D-10).
-- ALTER stock_movements — add project_id + project_task_id;
-- Widen chk_stock_movement_type to add ISSUE_TO_PROJECT (keep all existing tokens — V17 widen pattern).
-- Additive only. V1–V66 are FROZEN.

-- ============================================================================
-- (1) ALTER stock_movements — project tag columns (ADR-0033 D-3)
-- ============================================================================
ALTER TABLE stock_movements
    ADD COLUMN project_id      BIGINT,
    ADD COLUMN project_task_id BIGINT;

ALTER TABLE stock_movements
    ADD CONSTRAINT fk_stock_movement_project      FOREIGN KEY (project_id)
        REFERENCES projects (id),
    ADD CONSTRAINT fk_stock_movement_project_task FOREIGN KEY (project_task_id)
        REFERENCES project_tasks (id);

CREATE INDEX ix_stock_movements_project
    ON stock_movements (company_id, project_id)
    WHERE project_id IS NOT NULL;

-- ============================================================================
-- (2) Widen chk_stock_movement_type — add ISSUE_TO_PROJECT (ADR-0033 D-5)
-- DROP + re-ADD is the only way to modify a CHECK in PostgreSQL.
-- Full union of all prior tokens (V7/V17 pattern) + new ISSUE_TO_PROJECT.
-- ============================================================================
ALTER TABLE stock_movements
    DROP CONSTRAINT IF EXISTS chk_stock_movement_type;

ALTER TABLE stock_movements
    ADD CONSTRAINT chk_stock_movement_type CHECK (
        movement_type IN (
            'GOODS_RECEIPT',
            'SALE_ISSUE',
            'SALE_REVERSAL',
            'GOODS_RECEIPT_REVERSAL',
            'ADJUSTMENT',
            'OPENING_BALANCE',
            'ISSUE_TO_PROJECT'
        ));
