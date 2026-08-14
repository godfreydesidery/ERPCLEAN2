-- ## V99 — expand.  Nullable columns only. Idempotent. No foreign keys.
-- ###########################################################################

-- Per-transaction; Flyway wraps each migration in one transaction.
-- NOTE lock_timeout bounds EACH STATEMENT, not the migration: the true worst
-- case is (statements x timeout). 1s is ample — every statement below is a
-- catalogue-only change and needs no grace period.
SET LOCAL lock_timeout = '1s';

-- Named `alias`, not `code`: `code` is already taken on companies, branches
-- and roles, and this one is user-facing.
ALTER TABLE organisations ADD COLUMN IF NOT EXISTS alias VARCHAR(20);

ALTER TABLE app_users  ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE roles      ADD COLUMN IF NOT EXISTS organisation_id BIGINT;  -- NULL = shipped/global (D-3)
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS organisation_id BIGINT;  -- G10

-- D-7a. Structure only: min 2, max 20, lowercase alphanumeric with internal
-- hyphens, no leading or trailing hyphen.
--
-- RESERVED WORDS ARE DELIBERATELY NOT ENCODED HERE. A reserved list is policy
-- and will change; a CHECK constraint's definition freezes with its checksum
-- the moment it applies. The service enforces the list. Record in the plan that
-- D-7a's "and in the service, so a seeder cannot bypass it" cannot be satisfied
-- by both halves at once, and the stable half wins.
ALTER TABLE organisations DROP CONSTRAINT IF EXISTS ck_organisation_alias;
ALTER TABLE organisations ADD  CONSTRAINT ck_organisation_alias
    CHECK (alias ~ '^[a-z0-9][a-z0-9-]{0,18}[a-z0-9]$') NOT VALID;

-- Not tenancy work; a live performance fix shipped here because this migration
-- is already touching the table. audit_logs has no index leading with
-- company_id (V1__baseline.sql:317-319) while AuditReadService pages with a
-- count(*), so the Audit screen sequential-scans the largest table.
--
-- This DOES scan the table and DOES take a ShareLock that blocks INSERTs.
-- Measured at 6,265 rows / 3.4 MB it is milliseconds — but audit_logs is the
-- fastest-growing, never-purged table in the schema, so do not copy this
-- statement into a future migration without re-measuring.
--
-- Naming follows the existing ix_audit_log_* convention (singular).
CREATE INDEX IF NOT EXISTS ix_audit_log_company_at ON audit_logs (company_id, at DESC);


-- ###########################################################################
