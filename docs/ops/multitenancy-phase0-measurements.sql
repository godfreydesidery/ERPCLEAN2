-- ===========================================================================
-- Multi-tenancy Phase 0 / Stage A — measurements
--
-- READ-ONLY. Every statement is a SELECT. Nothing here writes, locks or
-- migrates anything, so it is safe to run against live production during
-- business hours.
--
-- Run against PRODUCTION and QA separately and keep the outputs apart:
--   psql "$DATABASE_URL" -f docs/ops/multitenancy-phase0-measurements.sql
--
-- Paste the whole output back, INCLUDING any errors — a statement that fails
-- because a column does not exist is itself a finding worth having.
--
-- Each block names the plan item it closes. See MULTITENANCY-PLAN.md
-- P0-1c, P1-0, P1-8 and §10 H-5.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 0. Which database am I looking at?
--    Guards against the single most embarrassing outcome: measuring QA twice.
-- ---------------------------------------------------------------------------
SELECT 'environment'            AS block,
       current_database()       AS database,
       current_user             AS db_user,
       version()                AS pg_version,
       now()                    AS measured_at;

SELECT 'flyway'                          AS block,
       max(version::numeric)             AS current_version,
       count(*)                          AS applied_count,
       count(*) FILTER (WHERE NOT success) AS failed_count
FROM   flyway_schema_history
WHERE  version IS NOT NULL;


-- ---------------------------------------------------------------------------
-- 1. P0-1c — how many organisations exist?
--
--    Several arguments in the plan are predicated on this being exactly 1:
--    the V101 self-sufficient backfill, the single-organisation invariant,
--    and the claim that every Phase 3 predicate is inert today.
--    If this returns anything other than 1, STOP and tell me before V99 is
--    authored — it invalidates §5.1 as written.
-- ---------------------------------------------------------------------------
SELECT 'organisations' AS block, count(*) AS organisation_count FROM organisations;

SELECT 'organisations_detail' AS block, id, uid, name, status
FROM   organisations
ORDER  BY id;

SELECT 'companies' AS block,
       count(*)                            AS company_count,
       count(DISTINCT organisation_id)     AS distinct_organisations,
       count(*) FILTER (WHERE organisation_id IS NULL) AS orphan_companies
FROM   companies;


-- ---------------------------------------------------------------------------
-- 2. P1-0 — audit_logs: is it big enough to matter?
--
--    This decides whether cutting audit_logs out of Phase 1 is URGENT or
--    merely prudent. The concern is that two CREATE INDEX plus a whole-table
--    UPDATE would run inside a hard-coded 900s health-check window, on a
--    table inflated by ROOT_BYPASS rows (ScopeGuard writes one on every root
--    scope assertion) with no purge path anywhere.
-- ---------------------------------------------------------------------------
SELECT 'audit_logs_size' AS block,
       count(*)                                              AS row_count,
       pg_size_pretty(pg_total_relation_size('audit_logs'))  AS total_size,
       pg_size_pretty(pg_relation_size('audit_logs'))        AS heap_size,
       pg_size_pretty(pg_indexes_size('audit_logs'))         AS index_size
FROM   audit_logs;

-- Which rows could be attributed at all, and by which key.
-- Confirms the correction to §5.1 step 3: actor-only derivation discards the
-- entire system/outbox trail, where actor is NULL but company_id is present.
SELECT 'audit_logs_attributability' AS block,
       count(*)                                                          AS total_rows,
       count(*) FILTER (WHERE company_id IS NOT NULL)                    AS has_company,
       count(*) FILTER (WHERE actor_user_id IS NOT NULL)                 AS has_actor,
       count(*) FILTER (WHERE company_id IS NOT NULL
                          AND actor_user_id IS NULL)                     AS company_only,
       count(*) FILTER (WHERE company_id IS NULL
                          AND actor_user_id IS NOT NULL)                 AS actor_only,
       count(*) FILTER (WHERE company_id IS NULL
                          AND actor_user_id IS NULL)                     AS unattributable
FROM   audit_logs;

-- How much of the table is ROOT_BYPASS noise, and the overall action mix.
SELECT 'audit_logs_by_action' AS block, action, count(*) AS rows
FROM   audit_logs
GROUP  BY action
ORDER  BY count(*) DESC
LIMIT  15;


-- ---------------------------------------------------------------------------
-- 3. P1-0 — how would V101's derivation passes actually attribute users?
--
--    Passes, most authoritative key first:
--      (a) user_company  -> companies.organisation_id
--      (b) user_branch   -> branches -> companies.organisation_id
--      (c) user_role     -> companies.organisation_id
--      (d) the sole organisation
--
--    If (d) carries most users, the design rests entirely on the
--    single-organisation invariant and P5-1's expiry becomes urgent.
--    NOTE: user_company is a SUPERSET of live membership — UserCompanyBackfill
--    derived pairs from ALL branch assignments including revoked ones — so
--    pass (a) can attribute from a company the user left years ago.
-- ---------------------------------------------------------------------------
SELECT 'app_users' AS block,
       count(*)                                        AS user_count,
       count(*) FILTER (WHERE is_root)                 AS root_users,
       count(DISTINCT lower(email))
              FILTER (WHERE email IS NOT NULL)         AS distinct_emails,
       count(*) FILTER (WHERE email IS NOT NULL)       AS users_with_email
FROM   app_users;

WITH pass_a AS (
    SELECT DISTINCT uc.user_id FROM user_company uc JOIN companies c ON c.id = uc.company_id
),
pass_b AS (
    SELECT DISTINCT ub.user_id
    FROM   user_branch ub JOIN branches b ON b.id = ub.branch_id
                          JOIN companies c ON c.id = b.company_id
    WHERE  ub.user_id NOT IN (SELECT user_id FROM pass_a)
),
pass_c AS (
    SELECT DISTINCT ur.user_id
    FROM   user_role ur JOIN companies c ON c.id = ur.company_id
    WHERE  ur.user_id NOT IN (SELECT user_id FROM pass_a)
      AND  ur.user_id NOT IN (SELECT user_id FROM pass_b)
)
SELECT 'derivation_passes' AS block,
       (SELECT count(*) FROM pass_a) AS pass_a_user_company,
       (SELECT count(*) FROM pass_b) AS pass_b_user_branch,
       (SELECT count(*) FROM pass_c) AS pass_c_user_role,
       (SELECT count(*) FROM app_users)
         - (SELECT count(*) FROM pass_a)
         - (SELECT count(*) FROM pass_b)
         - (SELECT count(*) FROM pass_c) AS pass_d_sole_organisation_fallback;

-- Would any pass DISAGREE with another? Unreachable at one organisation, but
-- this is the query that proves it rather than assuming it.
SELECT 'derivation_conflicts' AS block, count(*) AS users_with_conflicting_orgs
FROM (
    SELECT u.id
    FROM   app_users u
    LEFT   JOIN user_company uc ON uc.user_id = u.id
    LEFT   JOIN companies    c1 ON c1.id = uc.company_id
    LEFT   JOIN user_branch  ub ON ub.user_id = u.id
    LEFT   JOIN branches     b  ON b.id = ub.branch_id
    LEFT   JOIN companies    c2 ON c2.id = b.company_id
    GROUP  BY u.id
    HAVING count(DISTINCT coalesce(c1.organisation_id, c2.organisation_id)) > 1
) x;


-- ---------------------------------------------------------------------------
-- 4. P1-0 — roles: has the seed already adopted customer-authored roles?
--
--    R__seed_permissions.sql ends `ON CONFLICT (code) DO UPDATE ... SET
--    is_system = true`, so a customer role whose code collided with a bundle
--    code was silently flipped. Role.createdBy is never set, so there is no
--    other discriminator. Any row below is a customer role the reconciler
--    would otherwise publish to every tenant as a global role.
-- ---------------------------------------------------------------------------
SELECT 'roles_summary' AS block,
       count(*)                                  AS role_count,
       count(*) FILTER (WHERE is_system)         AS system_roles,
       count(*) FILTER (WHERE NOT is_system)     AS custom_roles
FROM   roles;

SELECT 'roles_adopted_suspects' AS block, id, uid, code, name, is_system
FROM   roles
WHERE  is_system
  AND  uid NOT IN (
        '0000000000XVKF7J9FAGX51RMQ',                 -- ORG_ADMIN  (V1)
        '00000000000000000000000001','00000000000000000000000002',
        '00000000000000000000000003','00000000000000000000000004',
        '00000000000000000000000005','00000000000000000000000006',
        '00000000000000000000000007','00000000000000000000000008',
        '00000000000000000000000009','0000000000000000000000000A',
        '0000000000000000000000000B','0000000000000000000000000C'  -- ADR-0057
       )
ORDER  BY code;


-- ---------------------------------------------------------------------------
-- 5. P1-8 — is the append-only audit grant actually applied anywhere?
--
--    CLAUDE.md invariant 7 and ADR-0004 D-5 say the app DB role is denied
--    UPDATE/DELETE on the audit table. No GRANT or REVOKE was found anywhere
--    in the repo — but "not in the repo" is not "not applied". If UPDATE is
--    absent below, any future audit backfill fails `permission denied` on
--    EVERY boot.
-- ---------------------------------------------------------------------------
SELECT 'audit_logs_privileges' AS block, grantee, privilege_type
FROM   information_schema.role_table_grants
WHERE  table_name = 'audit_logs'
ORDER  BY grantee, privilege_type;


-- ---------------------------------------------------------------------------
-- 6. §10 H-5 pre-check — would filtering on revocation lock anyone out?
--
--    The fix to JwtRequestContextFilter is correct, but if a live user is
--    currently working through a revoked or inactive assignment, shipping it
--    locks them out. Any non-zero number here needs a conversation BEFORE
--    that item ships.
-- ---------------------------------------------------------------------------
SELECT 'user_branch_revocation' AS block,
       count(*)                                                    AS total_assignments,
       count(*) FILTER (WHERE revoked_at IS NOT NULL)              AS revoked,
       count(*) FILTER (WHERE NOT active)                          AS inactive,
       count(DISTINCT user_id) FILTER (WHERE revoked_at IS NOT NULL
                                          OR NOT active)           AS users_affected
FROM   user_branch;

-- Users whose ONLY assignments are revoked/inactive — these are the ones the
-- H-5 fix would actually stop, as opposed to merely narrowing.
SELECT 'user_branch_fully_revoked' AS block, ub.user_id, count(*) AS assignments
FROM   user_branch ub
GROUP  BY ub.user_id
HAVING count(*) FILTER (WHERE ub.revoked_at IS NULL AND ub.active) = 0
ORDER  BY ub.user_id;


-- ---------------------------------------------------------------------------
-- 7. Sizing the rest of Phase 1 — everything the backfill touches.
--    Expected to be small; this is the number that says the migration window
--    is seconds rather than minutes.
-- ---------------------------------------------------------------------------
SELECT 'phase1_write_volume' AS block,
       (SELECT count(*) FROM app_users)     AS app_users_rows,
       (SELECT count(*) FROM roles)         AS roles_rows,
       (SELECT count(*) FROM organisations) AS organisations_rows,
       (SELECT count(*) FROM user_company)  AS user_company_rows,
       (SELECT count(*) FROM user_branch)   AS user_branch_rows,
       (SELECT count(*) FROM user_role)     AS user_role_rows;

-- The ten largest tables, for context on what a migration window looks like
-- here at all.
SELECT 'largest_tables' AS block,
       relname                                        AS table_name,
       n_live_tup                                     AS approx_rows,
       pg_size_pretty(pg_total_relation_size(relid))  AS total_size
FROM   pg_stat_user_tables
ORDER  BY pg_total_relation_size(relid) DESC
LIMIT  10;
