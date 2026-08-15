-- ###########################################################################
-- ## V103 — the R-1 trigger matched the row it was validating.
-- ##
-- ## V102's erp_role_code_not_reserved_by_global() asked:
-- ##   "does a GLOBAL role already hold this code?"
-- ## In a BEFORE UPDATE trigger the table still contains the OLD row, so a
-- ## role transitioning from global (organisation_id NULL) to tenant-scoped
-- ## matched ITSELF and was refused.
-- ##
-- ## That is exactly what TenancyReconciler does on every boot: it stamps a
-- ## customer's roles with their organisation. On a database where those roles
-- ## were not yet stamped, the reconciler threw, the exception propagated out
-- ## of the @Transactional ApplicationRunner, and the application would not
-- ## start. SHIPPED TO A LIVE CUSTOMER IN 1.8.0 AND CAUSED A CRASH LOOP.
-- ##
-- ## `AND g.id <> NEW.id` is the whole fix: compare against OTHER roles only.
-- ##
-- ## Why the tests missed it, recorded so the next trigger is tested properly:
-- ##   - the isolation harness INSERTED a tenant role colliding with a
-- ##     DIFFERENT global role; it never UPDATED a global role into a tenant
-- ##     one, which is the operation the reconciler performs;
-- ##   - the local full-chain rehearsal used a FRESH database, where the
-- ##     reconciler has nothing to stamp;
-- ##   - QA passed because its customer roles were ALREADY stamped, so the
-- ##     reconciler had no work and the trigger never fired.
-- ##   The one database that would have caught it — a restored copy of the
-- ##   customer's own — was used to rehearse the INDEX behaviour, but the
-- ##   application was never booted against it.
-- ##
-- ## Idempotent and safe to re-apply. On the live customer's box this exact
-- ## body was already installed by hand to stop the crash loop, so there this
-- ## migration is a no-op that simply brings Flyway's record into line.
-- ###########################################################################

CREATE OR REPLACE FUNCTION erp_role_code_not_reserved_by_global()
RETURNS trigger AS $$
BEGIN
    IF NEW.organisation_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM roles g
                   WHERE g.organisation_id IS NULL
                     AND g.code = NEW.code
                     AND g.id <> NEW.id) THEN
        RAISE EXCEPTION 'Role code % is reserved by a global role', NEW.code
              USING ERRCODE = 'unique_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
