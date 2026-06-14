-- V82 — Fix pos_tills.code: make nullable, relax unique to (company_id, name).
-- ISSUE-008: pos_tills.code was NOT NULL with no service-side generator → INSERT always
-- violated the NOT-NULL constraint → every POST /api/v1/pos/tills returned 500.
-- Fix option A (chosen): make code nullable (no code-generation required at create time);
-- drop the code-based unique constraint; add a name-based unique constraint so that
-- the logical uniqueness rule (one till per name per company) is enforced by the DB.
-- Additive DDL only — no data is modified.

ALTER TABLE pos_tills
    ALTER COLUMN code DROP NOT NULL;

ALTER TABLE pos_tills
    DROP CONSTRAINT IF EXISTS uq_pos_till_company_code;

ALTER TABLE pos_tills
    ADD CONSTRAINT uq_pos_till_company_name UNIQUE (company_id, name);
