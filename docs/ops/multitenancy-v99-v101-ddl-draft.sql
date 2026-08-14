-- ===========================================================================
--                    *** DRAFT v3 — NOT A MIGRATION ***
--
-- Deliberately NOT in backend/src/main/resources/db/migration. Flyway must
-- never see it. Presented for owner approval under the standing rule that no
-- migration is authored without sign-off on the exact statements.
--
-- Once approved, split at the file markers verbatim into
-- V99__*.sql / V100__*.sql / V101__*.sql.
--
-- ---------------------------------------------------------------------------
-- HISTORY
--   v1  reviewed by 7 agents -> RUN_WITH_CHANGES, 6 blockers.
--   v2  fixed those; reviewed by 7 more -> AUTHOR_WITH_CHANGES, 0 blockers,
--       13 HIGH. Both reviews restored private copies of the live customer's
--       database and ran the product's own restore command against them.
--   v3  (this) CUTS THE 31 D-9 AGGREGATE-ROOT COLUMNS from the release.
-- ---------------------------------------------------------------------------
--
-- WHY D-9 IS OUT (owner decision 2026-08-14, on the v2 review's evidence):
--
--   * The columns were justified by COST ASYMMETRY — "backfill 163 ms now, or
--     rewrite the heap later". That does not survive: NOTHING populates them
--     for new rows until P2-1, so the backfill has to be re-run later anyway,
--     on larger tables. Doing it now does not avoid a future heap rewrite; it
--     adds a second one.
--   * The 31-table boundary is the wrong SHAPE for the surviving rationale.
--     Extraction was withdrawn as a reason (182 of 205 tables are already one
--     join from their organisation). What remains is an RLS predicate without a
--     correlated subquery, plus a partition key — and both need the column on
--     the relation being scanned. Measured on the customer's data, the 31
--     chosen tables hold 3,839 rows / 960 kB while the EXCLUDED company-scoped
--     tables hold 12,373 rows / 3,120 kB. journal_lines (1,742 rows) is larger
--     than every included table and is exactly what a GL report row-scans.
--   * Backfilling them would have made all 31 read "100% attributed" while
--     decaying immediately — roughly 1,150 invoices and 3,600 stock movements
--     NULL within 30 days at the customer's measured rate — with nothing
--     reporting it. An honestly-empty column is better than a falsely-full one.
--
--   D-9 is therefore decided together with D-4 (row-level security), which is
--   the only thing that actually needs those columns and is still open.
--   NOTE: the 31 tables were all verified sound (they exist, company_id is
--   NOT NULL on every one, each has a validated FK to companies) — the cut is
--   about sequencing and boundary, not correctness.
--
-- WHAT THIS RELEASE IS NOW: the tenant spine, nothing else. Expand + backfill.
-- No foreign keys, no NOT NULL, no DEFAULT, no stored function, no D-9.
--
-- VERIFIED against a restored copy of Kilimanjaro production (Flyway v98, 205
-- tables, organisations = 1), through REAL Flyway and a full application boot:
--   * applies in ~0.1 s; second boot reports "up to date"
--   * the app starts (18.4 s) with ddl-auto: validate raising nothing
--   * pg_restore --clean of a pre-release dump AS THE erp ROLE = ZERO errors,
--     returns the DB to a true V98, and the release re-applies cleanly
--   * replaying the release onto an already-migrated DB is clean
--   * uq_app_users_email is a standalone PARTIAL INDEX; uq_role_code is a
--     table CONSTRAINT (neither is touched here)
-- ===========================================================================


-- ###########################################################################
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
-- ## V100 — the alias uniqueness partition.
-- ##
-- ## Plain transactional CREATE UNIQUE INDEX, NOT CONCURRENTLY: this repo has
-- ## no non-transactional migration wiring and six migrations say so in their
-- ## headers (V78, V81, V82, V83, V84, V85). CONCURRENTLY inside Flyway's
-- ## transaction fails with SQLSTATE 25001.
-- ##
-- ## The two role indexes (uq_role_code_global, uq_role_org_code) are NOT here.
-- ## uq_role_code is retained to protect ApprovalEngineImpl:301 and
-- ## StepApproverResolver:78-84, and while it stands those two are inert.
-- ## They land with the uq_role_code drop, behind P4-1c.
-- ###########################################################################

SET LOCAL lock_timeout = '1s';

CREATE UNIQUE INDEX IF NOT EXISTS uq_organisation_alias
    ON organisations (alias) WHERE alias IS NOT NULL;


-- ###########################################################################
-- ## V101 — backfill app_users.organisation_id, and validate.
-- ##
-- ## EXPAND + BACKFILL ONLY. Nothing here constrains. SET NOT NULL, the two
-- ## foreign keys and D-10's email index swap all move to P2-1, where the
-- ## entity is mapped and the application sets the value explicitly.
-- ##
-- ## STANDING-RULE NOTE: "provisioning over data migrations" is not breached by
-- ## a convergent backfill that fills only NULLs from unambiguous evidence.
-- ## The app-side reconciler (P1-3) is still required and still owns the alias
-- ## and the roles; it is the only healer after a partial restore.
-- ###########################################################################

SET LOCAL lock_timeout = '1s';

-- Passes run most-authoritative-key first. Each fills only rows still NULL, and
-- only where the evidence is unambiguous (exactly one distinct organisation).
--
-- LIVE MEMBERSHIP ONLY. user_company is a superset of live membership —
-- UserCompanyBackfill derived pairs from ALL branch assignments including
-- revoked ones — so without these predicates pass (a) could attribute a user
-- from a company they left. Zero rows differ today (measured: 0 revoked
-- anywhere), which is exactly why it is free to be correct now: the statement's
-- checksum freezes forever.
--
-- The HAVING clause SKIPS a user with contradictory evidence, it does not mark
-- them — so a user skipped by (a) may still be attributed by (b), (c) or (d)
-- from weaker evidence. Intended; the residual is reported below.

-- (a) user_company -> companies.organisation_id
UPDATE app_users u SET organisation_id = x.org
FROM (
    SELECT uc.user_id, min(c.organisation_id) AS org
    FROM   user_company uc
    JOIN   companies c ON c.id = uc.company_id
    WHERE  uc.revoked_at IS NULL
    GROUP  BY uc.user_id
    HAVING count(DISTINCT c.organisation_id) = 1
) x
WHERE u.id = x.user_id AND u.organisation_id IS NULL;

-- (b) user_branch -> branches -> companies.organisation_id
UPDATE app_users u SET organisation_id = x.org
FROM (
    SELECT ub.user_id, min(c.organisation_id) AS org
    FROM   user_branch ub
    JOIN   branches  b ON b.id = ub.branch_id
    JOIN   companies c ON c.id = b.company_id
    WHERE  ub.revoked_at IS NULL AND ub.active
    GROUP  BY ub.user_id
    HAVING count(DISTINCT c.organisation_id) = 1
) x
WHERE u.id = x.user_id AND u.organisation_id IS NULL;

-- (c) user_role -> companies.organisation_id
UPDATE app_users u SET organisation_id = x.org
FROM (
    SELECT ur.user_id, min(c.organisation_id) AS org
    FROM   user_role ur
    JOIN   companies c ON c.id = ur.company_id
    WHERE  ur.revoked_at IS NULL
    GROUP  BY ur.user_id
    HAVING count(DISTINCT c.organisation_id) = 1
) x
WHERE u.id = x.user_id AND u.organisation_id IS NULL;

-- (d) the sole organisation.
--     NOT a last resort but the MAIN path for root-created users:
--     UserServiceImpl.java:112-116 returns early when the creator is ROOT, and
--     on every single-tenant install the customer's own admin IS root
--     (BootstrapRunner.java:137), so such a user has no company, branch or
--     grant. Measured: 1 user on QA, 0 on production.
UPDATE app_users SET organisation_id = (SELECT id FROM organisations)
WHERE  organisation_id IS NULL
  AND  (SELECT count(*) FROM organisations) = 1;

-- --- unconditional summary --------------------------------------------------
-- Without this, a successful backfill and one that changed nothing produce
-- byte-identical operator output: silence. Counting is free at this size.
DO $$
DECLARE ok bigint; total bigint;
BEGIN
    SELECT count(*) FILTER (WHERE organisation_id IS NOT NULL), count(*)
      INTO ok, total FROM app_users;
    RAISE NOTICE 'V101 summary: app_users %/% attributed to an organisation.', ok, total;
    RAISE NOTICE 'V101 note: nothing writes organisation_id for NEW users until P2-1, so this '
                 'column re-accrues NULLs from the next user created. Expected and accepted; '
                 'P2-1 re-runs this backfill before it constrains.';
END $$;

-- --- residual report, NOT an abort ------------------------------------------
-- Nothing in this release depends on the column being complete (NOT NULL moved
-- to P2-1), so an abort would stop a customer's ERP over a harmless condition.
-- The HARD GATE belongs in P2-1, immediately before SET NOT NULL.
--
-- Note on wording: `spring.flyway.group` is unset (the only `group:` in
-- application.yml is under management.endpoint.health), so V99 and V100 are
-- already COMMITTED by the time V101 runs. Only V101's own writes roll back.
DO $$
DECLARE unattributed bigint;
BEGIN
    SELECT count(*) INTO unattributed FROM app_users WHERE organisation_id IS NULL;
    IF unattributed > 0 THEN
        RAISE WARNING
            'V101: % app_users row(s) have no organisation — their user_company / user_branch / '
            'user_role evidence names more than one organisation, or none, and there is more than '
            'one organisation to choose from. The migration SUCCEEDED and the application will start '
            'normally. P2-1 will refuse to apply NOT NULL until this is zero.', unattributed;
    END IF;
END $$;

-- --- validate what V99 registered -------------------------------------------
-- NOTE: a CHECK evaluating to NULL is SATISFIED, so this passes while every
-- alias is NULL. It proves the format of any alias that IS set; it can never
-- prove one was set. The reconciler owns that and logs it.
ALTER TABLE organisations VALIDATE CONSTRAINT ck_organisation_alias;


-- ===========================================================================
-- DEFERRED TO P2-1 (mapping AppUser.organisation), IN THIS ORDER:
--   1. Application code sets organisation_id explicitly on every insert
--      (UserServiceImpl and BootstrapRunner — note a FRESH install is born
--      unattributed otherwise, since V101's backfill only covers existing rows).
--   2. RE-RUN V101's four derivation passes verbatim. Between this release and
--      P2-1 nothing populates the column, so new rows accrue NULLs —
--      deliberately accepted (owner, 2026-08-14: the customer is low-volume,
--      12 users). Re-running the same convergent statements IS the containment.
--      Do NOT solve it with a column DEFAULT, a stored function or an urgent
--      reconciler: that is what v1 did, and it is what made the release
--      unrecoverable after a restore.
--   3. Hard gate: abort if any app_users.organisation_id IS NULL after step 2.
--   4. ALTER TABLE app_users ALTER COLUMN organisation_id SET NOT NULL;
--   5. The two foreign keys to organisations (app_users, roles) — NOT VALID
--      then VALIDATE. Re-adding them re-introduces the pg_restore --clean
--      failure v1 hit, so ship the orbixerp.sh restore fix FIRST.
--   6. D-10: DROP INDEX IF EXISTS uq_app_users_email; then
--      CREATE UNIQUE INDEX uq_app_users_org_email ON app_users
--          (organisation_id, email) WHERE email IS NOT NULL;
--      Must follow SET NOT NULL — while organisation_id is nullable a composite
--      unique permits duplicate emails on every NULL-org row.
--
-- ALSO NOT IN THIS RELEASE:
--   * D-9's aggregate-root columns — see the header. Decide with D-4 (RLS).
--   * roles.organisation_id SET NOT NULL — R__seed_permissions.sql:287 inserts
--     roles without the column and NOT NULL is checked before the ON CONFLICT
--     arbiter, so it would fail the repeatable seed. (D-3: bundles stay global.)
--   * DROP CONSTRAINT uq_role_code — breaks approvals at one organisation.
--     Behind P4-1c. Note it is a CONSTRAINT, not an index: DROP CONSTRAINT.
--   * audit_logs.organisation_id derivation — background pass. The key is
--     COALESCE(company_id -> organisation_id, actor_user_id -> organisation_id):
--     actor-only would silently lose 545 rows (8.7%) on production, and those
--     are the system/outbox rows carrying GL and stock postings.
-- ===========================================================================
