-- ## V104 — close the tenancy spine: constrain, reference, re-scope, validate.
--
-- The third and last step of the expand -> backfill -> constrain sequence that
-- V99 and V101 began. V101 ended by writing down exactly what this migration
-- must do, in what order, and why each step depends on the one before it; this
-- file follows that plan.
--
-- EVERY step here runs against a database that is already carrying a customer's
-- data. None of it creates anything: it takes columns that are already populated
-- and makes the guarantees the application has been assuming since V99 actually
-- true in the schema.
--
-- Rehearsed against a restored copy of the live customer's database (1 organisation,
-- 12 users, 0 unattributed), not a fresh one — a fresh database has none of the
-- shape that has actually broken releases here.

-- ---------------------------------------------------------------------------
-- 1. HARD GATE — refuse to start if any user is unattributed.
--
-- SET NOT NULL on a column with a NULL aborts the migration mid-flight, and a
-- half-applied release against a customer database is the failure mode that made
-- an earlier attempt unrecoverable. This check changes nothing and fails first,
-- with a message that names the actual problem instead of a constraint violation.
--
-- If it ever fires, the fix is NOT to patch data by hand here: TenancyReconciler
-- attributes users at boot, so the answer is to run the application once and let
-- it do that, then migrate.
-- ---------------------------------------------------------------------------
DO $$
DECLARE orphans BIGINT;
BEGIN
    SELECT count(*) INTO orphans FROM app_users WHERE organisation_id IS NULL;
    IF orphans > 0 THEN
        RAISE EXCEPTION
            'V104 aborted: % app_users row(s) have no organisation_id. Start the '
            'application once so TenancyReconciler can attribute them, then migrate.',
            orphans;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. app_users.organisation_id NOT NULL.
--
-- This is the constraint the whole tenancy spine rests on. That column is the
-- ONLY authoritative source of a caller's tenant — scope is derived from it after
-- authentication, never from caller input — so a NULL is a user who belongs to no
-- customer, and every predicate built on it silently stops meaning anything.
--
-- Cheap here: app_users is small on every shipped install (12 rows on the
-- customer copy this was rehearsed against), so the ACCESS EXCLUSIVE lock is
-- held for microseconds. On a large table this would want the
-- add-CHECK-NOT-VALID / VALIDATE / SET-NOT-NULL dance instead.
-- ---------------------------------------------------------------------------
ALTER TABLE app_users ALTER COLUMN organisation_id SET NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. The two foreign keys to organisations.
--
-- Deferred out of V101 for a specific reason, now resolved: re-adding them
-- re-introduced a `pg_restore --clean` failure, because --clean drops objects one
-- at a time and cannot always honour the dependency order these keys create. The
-- shipped restore no longer uses it — `orbixerp.sh` now does
-- `DROP SCHEMA IF EXISTS public CASCADE` and restores into an empty schema
-- (6159fbe7) — so the hazard V101 warned about no longer exists.
--
-- NOT VALID first, then VALIDATE, deliberately. NOT VALID takes a brief lock and
-- makes the key apply to new and changed rows immediately; VALIDATE then scans
-- the existing rows under a SHARE UPDATE EXCLUSIVE lock, which does NOT block
-- reads or writes. Doing it in one step would hold a stronger lock for the whole
-- scan — pointless on a table this size, but the pattern is the one to keep.
--
-- ON DELETE RESTRICT, not CASCADE: deleting an organisation must never silently
-- delete a customer's users. Under a shared instance (D-11) that would be one
-- customer's mistake destroying another's account rows.
-- ---------------------------------------------------------------------------
ALTER TABLE app_users DROP CONSTRAINT IF EXISTS fk_app_users_organisation;
ALTER TABLE app_users ADD  CONSTRAINT fk_app_users_organisation
    FOREIGN KEY (organisation_id) REFERENCES organisations (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE app_users VALIDATE CONSTRAINT fk_app_users_organisation;

-- roles.organisation_id stays NULLABLE and gets the same key.
--
-- NULL is meaningful here and must remain so: the thirteen shipped role bundles
-- are GLOBAL (D-3) and carry no organisation. A NOT NULL on this column would
-- also break R__seed_permissions.sql, which inserts roles without it — NOT NULL
-- is checked before the ON CONFLICT arbiter, so the repeatable seed would fail
-- on every deploy. The foreign key is still worth having: it stops a tenant role
-- pointing at an organisation that does not exist.
ALTER TABLE roles DROP CONSTRAINT IF EXISTS fk_roles_organisation;
ALTER TABLE roles ADD  CONSTRAINT fk_roles_organisation
    FOREIGN KEY (organisation_id) REFERENCES organisations (id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE roles VALIDATE CONSTRAINT fk_roles_organisation;

-- ---------------------------------------------------------------------------
-- 4. D-10 — e-mail uniqueness becomes per-tenant.
--
-- MUST follow step 2. While organisation_id was nullable, a composite unique
-- would have permitted duplicate e-mails across every NULL-org row — the exact
-- collision it exists to prevent.
--
-- Measured to be a literal no-op on both estates: zero users have an e-mail on
-- QA or production, so the partial index covers no rows today. What changes is
-- the shape of the namespace going forward — two customers may each have a
-- j.doe@example.com, which under one global unique they could not.
--
-- Plain CREATE INDEX rather than CONCURRENTLY: the table is tiny and the index
-- covers no rows, so the brief lock costs nothing, and CONCURRENTLY would force
-- this into its own non-transactional migration for no benefit.
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS uq_app_users_email;
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_users_org_email
    ON app_users (organisation_id, email)
    WHERE email IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 5. D-7a — enforce the alias format V99 added as NOT VALID.
--
-- V99 could not validate it at the time: aliases were still being derived and
-- back-filled by TenancyReconciler, so a scan could have failed on a row the
-- application was about to fix. That work is done, so the constraint can now be
-- made real.
--
-- The pattern (V99) is `^[a-z0-9][a-z0-9-]{0,18}[a-z0-9]$` — lowercase, no dots,
-- no spaces, no leading or trailing hyphen, 2..20 characters. It is deliberately
-- stricter than D-7a's proposal, which permitted a trailing hyphen.
--
-- RESERVED WORDS (admin, root, system, api, support) are enforced in
-- OrganisationAlias.derive, NOT here. A CHECK could express the blocklist, but it
-- would then be two sources of truth for one rule, and the service is the half
-- that can give the operator a sentence explaining what to type instead.
--
-- VALIDATE takes SHARE UPDATE EXCLUSIVE: it does not block reads or writes, and
-- there is one organisation per install to scan.
-- ---------------------------------------------------------------------------
ALTER TABLE organisations VALIDATE CONSTRAINT ck_organisation_alias;
