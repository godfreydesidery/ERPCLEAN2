-- V26 — Notification trigger scaffolding (ADR-0024 D-14).
-- The new DomainEventType.PAYMENT_RECEIVED constant is CODE, not schema
-- (domain_events.event_type is a free VARCHAR(60) with no CHECK to widen — verified).
-- This migration adds additive scanner-support indexes on the source read models
-- to ensure the overdue and low-stock scan queries are index-backed at production volume.

-- Partial index for the invoice-overdue scanner: open/partial invoices by company + due_date.
-- This supports the scanner's "WHERE company_id = ? AND status IN ('OPEN','PARTIAL') AND due_date < now()" query.
CREATE INDEX IF NOT EXISTS ix_ar_invoices_overdue_scan
    ON ar_invoices (company_id, due_date)
    WHERE status IN ('OPEN', 'PARTIAL');

-- Partial index for the low-stock scanner: rows with a reorder_level set (non-null).
-- Supports "WHERE company_id = ? AND reorder_level IS NOT NULL AND quantity <= reorder_level".
CREATE INDEX IF NOT EXISTS ix_stock_on_hand_low_stock_scan
    ON stock_on_hand (company_id, branch_id)
    WHERE reorder_level IS NOT NULL;
