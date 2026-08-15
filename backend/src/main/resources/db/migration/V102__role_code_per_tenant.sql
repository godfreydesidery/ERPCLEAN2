-- ###########################################################################
-- ## V102 — role codes become unique PER TENANT (ADR-0062 P4-1c).
-- ##
-- ## Replaces V1__baseline.sql:243's global `uq_role_code UNIQUE (code)` with
-- ## two partial indexes, so that two tenants may each hold a SUPERVISOR while
-- ## codes stay unique inside a tenant and among the shipped global roles.
-- ##
-- ## ORDER MATTERS. Both indexes are created BEFORE the drop, so there is
-- ## never a window in which role codes are unconstrained.
-- ##
-- ## Plain transactional CREATE UNIQUE INDEX, NOT CONCURRENTLY: this repo has
-- ## no non-transactional migration wiring, and six migrations say so in their
-- ## headers (V78, V81-V85, V100). CONCURRENTLY inside Flyway's transaction
-- ## fails with SQLSTATE 25001. `roles` holds 15 rows on the live client, so
-- ## the momentary ACCESS EXCLUSIVE lock on the drop is a non-event; the
-- ## lock_timeout makes it fail fast rather than queue behind a long reader.
-- ##
-- ## ONE-WAY. `uq_role_code` can only be recreated while no duplicate codes
-- ## exist, so once a second tenant holds a colliding code this is not
-- ## reversible. It lands while every environment is still single-tenant.
-- ##
-- ## PAIRED WITH A SEED EDIT, and that pairing is not optional.
-- ## `R__seed_permissions.sql` upserts the role bundles with
-- ## `ON CONFLICT (code)`, which requires a unique index on (code) ALONE.
-- ## Dropping this constraint without changing that clause leaves a LATENT
-- ## failure: this migration does not change the seed's checksum, so the seed
-- ## does not re-run and the deploy looks clean — it breaks on the NEXT seed
-- ## edit, as a boot failure on every deployment, with an error naming a
-- ## constraint that author never touched. Rehearsed on a copy of the live
-- ## client's database; the seed fails with:
-- ##   ERROR: there is no unique or exclusion constraint matching the
-- ##          ON CONFLICT specification
-- ###########################################################################

SET LOCAL lock_timeout = '1s';

-- Global roles: the shipped thirteen (ORG_ADMIN + 12 ADR-0057 bundles).
-- organisation_id IS NULL is not "unset" — it IS the marker for a global role.
CREATE UNIQUE INDEX IF NOT EXISTS uq_role_code_global
    ON roles (code) WHERE organisation_id IS NULL;

-- Tenant roles: unique within a tenant, free to repeat across tenants.
CREATE UNIQUE INDEX IF NOT EXISTS uq_role_org_code
    ON roles (organisation_id, code) WHERE organisation_id IS NOT NULL;

-- Only now is it safe to drop the global rule. It is a table CONSTRAINT
-- (V1__baseline.sql:243), not a bare index, so ALTER TABLE is the right verb.
ALTER TABLE roles DROP CONSTRAINT IF EXISTS uq_role_code;


-- ###########################################################################
-- ## R-1 — a tenant role code may not collide with a GLOBAL role code.
-- ##
-- ## The two indexes above cover DIFFERENT PARTITIONS, so Postgres will
-- ## happily accept (NULL,'CASHIER') alongside (2,'CASHIER'). No pair of
-- ## partial indexes can express a rule that spans both partitions; it needs a
-- ## check that reads the other side.
-- ##
-- ## THIS IS THE FIRST TRIGGER IN THIS SCHEMA (101 migrations, zero triggers).
-- ## It is a constraint invisible to the ORM and to anyone reading Role.java,
-- ## and that cost is accepted deliberately: the rule has to hold against
-- ## paths that never reach RoleServiceImpl — direct SQL, a future migration,
-- ## a seeder. RoleServiceImpl.create() performs the same check first, so the
-- ## normal path still gets a friendly 409 rather than a database error.
-- ##
-- ## DELIBERATELY ONE-DIRECTIONAL. It fires only for TENANT rows reaching for
-- ## a global code. It does NOT fire when a release adds a GLOBAL role whose
-- ## code a tenant already uses — that would abort the seed, abort the
-- ## migration and fail the boot, turning a name clash into an outage on a
-- ## live customer's system caused by a release they did not ask for. That
-- ## direction is DETECTED instead, by TenancyReconciler at boot.
-- ###########################################################################

CREATE OR REPLACE FUNCTION erp_role_code_not_reserved_by_global()
RETURNS trigger AS $$
BEGIN
    IF NEW.organisation_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM roles g
                   WHERE g.organisation_id IS NULL
                     AND g.code = NEW.code) THEN
        -- unique_violation (23505) so Spring maps it to DataIntegrityViolationException
        -- and it surfaces as a conflict, consistent with the service-level refusal.
        RAISE EXCEPTION 'Role code % is reserved by a global role', NEW.code
              USING ERRCODE = 'unique_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_role_code_not_reserved_by_global ON roles;

CREATE TRIGGER trg_role_code_not_reserved_by_global
    BEFORE INSERT OR UPDATE OF code, organisation_id ON roles
    FOR EACH ROW
    EXECUTE FUNCTION erp_role_code_not_reserved_by_global();
