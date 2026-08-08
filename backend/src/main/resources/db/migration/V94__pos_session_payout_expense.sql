-- ============================================================================
-- V94 — Till expenses: categorise a drawer payout and post it to the GL (K8, Kilimanjaro 2026-08-08).
--
-- WHY: pos_session_payouts (V37) records cash leaving the drawer and NOTHING ELSE — no journal, no
-- cash-book row. And because expected cash already nets payouts, a correctly-recorded payout yields a
-- ZERO variance, so the reconcile posting never catches it either. GL cash is therefore overstated and
-- the P&L understated by the value of every till payout ever taken. The payout is also untyped beyond
-- REFUND/PAID_OUT and its reason is free text, so "expense" is not a thing the system can report on.
--
-- This migration adds only the columns that let a payout BE an expense:
--   category          — the expense bucket the till operator picked (transport, water, casual labour…);
--   gl_account_id     — the expense account it was posted to, resolved from that category;
--   journal_entry_uid — the GL journal the posting produced, so the row is traceable and the posting
--                       can be made idempotent (a non-null value means "already posted").
-- All three are NULLABLE with no default: metadata-only ADD COLUMN on the populated pos_session_payouts
-- table, no backfill, no rewrite, safe on Postgres 11+.
--
-- Historical rows deliberately keep NULL on all three. They were never posted to the GL and this
-- migration does NOT back-post them — that would write into closed periods. Whether to restate the
-- historical understatement is an owner decision, handled operationally, not here.
--
-- The payout-type CHECK is WIDENED to admit 'EXPENSE'. Dropping and re-adding the constraint inside
-- this new file is the standard way to widen an enum-like CHECK; it does not touch V37, and because the
-- new set is a strict superset of the old one every existing row already satisfies it, so the constraint
-- validates instantly with no table scan of failing rows.
-- ============================================================================

ALTER TABLE pos_session_payouts
    ADD COLUMN category          VARCHAR(40),
    ADD COLUMN gl_account_id     BIGINT,
    ADD COLUMN journal_entry_uid VARCHAR(26);

ALTER TABLE pos_session_payouts
    ADD CONSTRAINT fk_pos_session_payout_gl_account
    FOREIGN KEY (gl_account_id) REFERENCES chart_of_accounts (id);

ALTER TABLE pos_session_payouts
    DROP CONSTRAINT chk_pos_session_payout_type;

ALTER TABLE pos_session_payouts
    ADD CONSTRAINT chk_pos_session_payout_type
    CHECK (payout_type IN ('REFUND','PAID_OUT','EXPENSE'));
