-- V69 — Consolidated uniqueness: user email, employee national_id and TIN
--
-- These partial unique indexes enforce real-world identifier uniqueness without
-- rejecting rows that legitimately omit the field (NULLs are excluded so multiple
-- employees/users without an email/id can coexist).
--
-- IMPORTANT: requires a fresh DB on existing installs — if duplicate values already
-- exist in any of these columns the migration will fail with a unique-violation.
-- Drop the conflicting rows (or the volume) before applying.
--
-- The global DataIntegrityViolationException handler already maps the resulting
-- PG-23505 (unique_violation) to HTTP 409, so no service code change is required
-- for the 409 behaviour; only the @Pattern username fix (in the DTO) is a code
-- change and is covered in the same commit.
--
-- Scope notes:
--   app_users  — org-wide (no company_id column); email unique across the whole
--                deployment when present.
--   employees  — company-scoped; uniqueness is per-company for national_id and tin.
-- Additive only. V1–V68 FROZEN.

-- ---------------------------------------------------------------------------
-- app_users.email — org-wide, partial (NULL excluded)
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_app_users_email
    ON app_users (email)
    WHERE email IS NOT NULL;

-- ---------------------------------------------------------------------------
-- employees.national_id — per-company, partial (NULL excluded)
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_employees_national_id
    ON employees (company_id, national_id)
    WHERE national_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- employees.tin — per-company, partial (NULL excluded)
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_employees_tin
    ON employees (company_id, tin)
    WHERE tin IS NOT NULL;
