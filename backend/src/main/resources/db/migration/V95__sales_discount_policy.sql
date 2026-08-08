-- ============================================================================
-- V95 — Manager-authorised discount policy (K7, Kilimanjaro 2026-08-08).
--
-- WHY: nothing currently stands between a cashier and a discount on ANY surface. There is no discount
-- limit anywhere in the schema, no authorisation gate, and no audit of who allowed it. DiscountValidator
-- checks only sign and the 0-100 range, so "100% off" is valid input and the totals calculator silently
-- floors the line to zero. The identical ungated input exists on the web POS, so a till-only control
-- would be cosmetic — the policy therefore lives in sales_settings and is enforced server-side on both
-- the POS sale path and the web invoice-line path.
--
-- Shape follows V93 (below_cost_action) exactly, and for the same reason: an ACTION, not a boolean, so
-- every stance is representable —
--   OFF     — no check (today's behaviour, and the DEFAULT, so nothing changes for any tenant on deploy);
--   WARN    — allow, but record that the line went out over the ceiling;
--   APPROVE — reject unless a manager authorised it via the step-up endpoint (the mode Kilimanjaro wants);
--   BLOCK   — always reject over the ceiling.
-- max_discount_percent is the ceiling a cashier may apply unaided; NULL means "no ceiling configured",
-- which the guard treats as 0 under APPROVE/BLOCK (every discount needs authority) and ignores under OFF.
--
-- discount_authorised_by records WHICH user approved an over-ceiling line — the audit trail that is
-- missing today. It mirrors the existing overridden_by column on the same table. Nullable, no default:
-- metadata-only ADD COLUMN, no backfill, no table rewrite.
--
-- NOTE on the FK to app_users: sales_invoice_lines is a large populated table, but every value added
-- here is NULL, so the constraint validates against zero candidate rows. The scan is trivial; the brief
-- ACCESS EXCLUSIVE lock is the only cost. If that lock is unacceptable on a busy production box, the
-- alternative is ADD CONSTRAINT ... NOT VALID followed by VALIDATE CONSTRAINT in a later migration.
-- Owner accepted the plain form on 2026-08-08.
-- ============================================================================

ALTER TABLE sales_settings
    ADD COLUMN discount_approval_action VARCHAR(16) NOT NULL DEFAULT 'OFF',
    ADD COLUMN max_discount_percent     NUMERIC(5,2);

ALTER TABLE sales_settings
    ADD CONSTRAINT chk_sales_settings_discount_approval_action
    CHECK (discount_approval_action IN ('OFF','WARN','APPROVE','BLOCK'));

ALTER TABLE sales_settings
    ADD CONSTRAINT chk_sales_settings_max_discount_percent
    CHECK (max_discount_percent IS NULL OR (max_discount_percent >= 0 AND max_discount_percent <= 100));

ALTER TABLE sales_invoice_lines
    ADD COLUMN discount_authorised_by BIGINT;

ALTER TABLE sales_invoice_lines
    ADD CONSTRAINT fk_sales_invoice_line_discount_authoriser
    FOREIGN KEY (discount_authorised_by) REFERENCES app_users (id);
