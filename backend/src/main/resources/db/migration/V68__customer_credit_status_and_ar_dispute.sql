-- V68 — D-5 (ADR-0040): customer credit-status hard-block columns + AR dispute/dunning columns.
-- Additive only. V1–V67 are FROZEN.
-- Safe: all new columns are either nullable or carry a DEFAULT (no table-lock risk on Postgres 15+).

-- ---------------------------------------------------------------------------
-- (1) customers — credit-control columns
-- ---------------------------------------------------------------------------
ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS credit_status    VARCHAR(20)  NOT NULL DEFAULT 'OK',
    ADD COLUMN IF NOT EXISTS manual_hold      BOOLEAN      NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS credit_hold_reason VARCHAR(255);

ALTER TABLE customers
    ADD CONSTRAINT chk_customer_credit_status
        CHECK (credit_status IN ('OK', 'WARNING', 'ON_HOLD', 'STOPPED'));

-- ---------------------------------------------------------------------------
-- (2) ar_invoices — dunning + dispute recording columns (recording-only; no GL impact)
-- ---------------------------------------------------------------------------
ALTER TABLE ar_invoices
    ADD COLUMN IF NOT EXISTS dunning_level      INTEGER  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_reminder_date DATE,
    ADD COLUMN IF NOT EXISTS disputed           BOOLEAN  NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS dispute_reason     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS on_hold            BOOLEAN  NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS hold_reason        VARCHAR(255);
