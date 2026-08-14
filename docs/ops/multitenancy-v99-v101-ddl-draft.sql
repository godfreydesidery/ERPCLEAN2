-- ===========================================================================
--                    *** DRAFT v2 — NOT A MIGRATION ***
--
-- Deliberately NOT in backend/src/main/resources/db/migration. Flyway must
-- never see it. Presented for owner approval under the standing rule that no
-- migration is authored without sign-off on the exact statements.
--
-- v2 (2026-08-14) — revised after a 7-agent adversarial review that restored
-- private copies of the Kilimanjaro production database, applied the v1 draft,
-- and then ran the PRODUCT'S OWN restore command against it.
-- Verdict on v1: RUN_WITH_CHANGES, high confidence, 6 blockers.
--
-- WHAT CHANGED, AND WHY (each traceable to a reproduced failure):
--
--  1. THE TWO FOREIGN KEYS ARE GONE. They were the sole reason
--     `pg_restore --clean` of a pre-release backup could not drop
--     `organisations`: 6 errors ending in `COPY failed ... duplicate key value
--     violates unique constraint organisations_pkey`, the table silently NOT
--     restored while all 204 others rolled back — and orbixerp.sh:313
--     downgrades that to `warn` and prints "Restore complete". Dropping them
--     takes the same restore from 6 errors to 0. They buy nothing in Phase 1
--     (the column is nullable and the reconciler owns the values); they land
--     in the P2-1 migration instead.
--
--  2. EVERY STATEMENT IS NOW REPLAY-SAFE. After a partial restore the database
--     is a hybrid: flyway_schema_history says 98, but `organisations.alias`
--     survives. v1 then died on `column "alias" already exists` and the box
--     crash-looped with no repair path. IF NOT EXISTS / IF EXISTS throughout.
--     The repo already uses this idiom (47 ADD COLUMN IF NOT EXISTS).
--
--  3. NO `SET NOT NULL`, NO DEFAULT, NO STORED FUNCTION IN THIS RELEASE.
--     v1's `erp_sole_organisation_id()` was the only object not owned by a
--     table, so no pre-release dump contained a DROP for it — it survived
--     every restore and made replay impossible. It also broke 13 integration
--     test classes (a REQUIRED CI gate) and would 500 every POST /users the
--     moment a second organisation existed. This release is now strictly
--     EXPAND + BACKFILL. Constraining moves to P2-1, where the entity is
--     mapped and no crutch is needed.
--
--  4. `SET LOCAL lock_timeout` opens every file. Measured: with an 8 s reader
--     on `products`, unguarded V99 stalled 7,052 ms holding ACCESS EXCLUSIVE
--     on 22 tables. Guarded, it aborts at ~3 s with zero residue. Nothing in
--     backend/, infra/ or dist/ sets any timeout today.
--
--  5. `ix_audit_logs_org_at` deleted — organisation_id is 0/6265 populated and
--     stays that way this release, so it could serve no query while costing
--     writes on the hottest table. `ix_audit_logs_company_at` is kept: it
--     serves the 11 non-root users (the customer's own admin is root, and
--     AuditReadService:57 drops the predicate for root entirely).
--
--  6. Two false claims in v1's own comments are corrected in place, and the
--     D-9 backfill is now DONE here rather than deferred — v1 said deferring
--     it protected the migration window; measured, it is 163 ms.
--
-- MEASURED ON THE RESTORED PRODUCTION COPY: whole release ~0.06 s, no table
-- rewritten, 12/12 users attributed, R__seed_permissions.sql still passing.
--
-- Once approved, split at the file markers verbatim into
-- V99__*.sql / V100__*.sql / V101__*.sql.
-- ===========================================================================


-- ###########################################################################
-- ## V99 — expand.  Nullable columns only. Idempotent. No foreign keys.
-- ###########################################################################

-- Per-transaction; Flyway wraps each migration in one transaction.
-- If a statement waits more than 3 s for a lock the migration aborts cleanly
-- rather than holding ACCESS EXCLUSIVE on the estate while it queues.
SET LOCAL lock_timeout = '3s';

-- --- D-9 aggregate roots FIRST ---------------------------------------------
-- Deliberately ordered ahead of the IAM tables. Every lock is held until
-- COMMIT, so putting app_users/organisations last means a stall in this block
-- has not already taken down login and the audit write path.
--
-- ⚠ THE ONE OPEN JUDGEMENT CALL — see the note at the end of this file.
-- "Aggregate root" is NOT derivable from the schema: 169 of 204 tables carry
-- both company_id NOT NULL and a uid, including line tables such as
-- journal_lines and sales_invoice_lines. The list below is a considered
-- selection of business documents and master-data roots, expanded from v1's
-- 18 after the review found real omissions (sales_orders, quotations,
-- deliveries, stock_transfers, pos_sessions, employees, chart_of_accounts and
-- others were all missing).

-- transactional documents
ALTER TABLE sales_invoices      ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE sales_orders        ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE quotations          ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE deliveries          ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE purchase_orders     ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE supplier_quotes     ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE goods_receipts      ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE supplier_bills      ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE stock_movements     ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE stock_transfers     ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE journal_batches     ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE journal_entries     ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE ar_invoices         ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE ar_receipts         ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE ar_credit_notes     ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE ar_write_offs       ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE ap_payments         ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE ap_debit_notes      ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE payroll_runs        ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE pos_sessions        ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE vat_returns         ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE approval_requests   ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE generated_documents ADD COLUMN IF NOT EXISTS organisation_id BIGINT;

-- master data
ALTER TABLE products            ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE customers           ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE suppliers           ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE employees           ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE chart_of_accounts   ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE cash_bank_accounts  ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE pos_tills           ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE petty_cash_funds    ADD COLUMN IF NOT EXISTS organisation_id BIGINT;

-- --- the tenant spine, last ------------------------------------------------

-- Named `alias`, not `code`: `code` is already taken on companies, branches
-- and roles, and this one is user-facing.
ALTER TABLE organisations ADD COLUMN IF NOT EXISTS alias VARCHAR(20);

ALTER TABLE app_users  ADD COLUMN IF NOT EXISTS organisation_id BIGINT;
ALTER TABLE roles      ADD COLUMN IF NOT EXISTS organisation_id BIGINT;  -- NULL = shipped/global (D-3)
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS organisation_id BIGINT;  -- G10

-- D-7a, tightened. v1's regex accepted `abc-`, `a-` and `ki--li`.
-- Structure only: minimum 2, maximum 20, lowercase alphanumeric and internal
-- hyphens, no leading or trailing hyphen.
--
-- RESERVED WORDS ARE NOT ENCODED HERE, DELIBERATELY. A reserved list is
-- policy and will change; a CHECK constraint's definition is frozen by its
-- checksum the moment it applies. The service enforces the list. Record this
-- in the plan: D-7a's "and in the service, so a seeder cannot bypass it"
-- cannot be satisfied by both halves at once, and the stable half wins.
ALTER TABLE organisations DROP CONSTRAINT IF EXISTS ck_organisation_alias;
ALTER TABLE organisations ADD  CONSTRAINT ck_organisation_alias
    CHECK (alias ~ '^[a-z0-9][a-z0-9-]{0,18}[a-z0-9]$') NOT VALID;

-- --- audit_logs read index --------------------------------------------------
-- Not tenancy work; a live performance fix. audit_logs has no index leading
-- with company_id (V1__baseline.sql:317-319) while AuditReadService pages with
-- a count(*), so the Audit screen sequential-scans the largest table.
--
-- CORRECTION to v1's header: this DOES scan the table and DOES take a ShareLock
-- that blocks INSERTs — v1 claimed "nothing here scans a table or blocks a
-- write for longer than the catalogue update itself", which was false.
-- Measured at 6,265 rows / 3.4 MB it is milliseconds, but audit_logs is the
-- fastest-growing, never-purged table in the schema, so do not copy that
-- sentence into a future migration.
--
-- Naming follows the existing ix_audit_log_* convention (singular), not v1's.
CREATE INDEX IF NOT EXISTS ix_audit_log_company_at ON audit_logs (company_id, at DESC);


-- ###########################################################################
-- ## V100 — the alias uniqueness partition.
-- ##
-- ## Plain transactional CREATE UNIQUE INDEX, NOT CONCURRENTLY: this repo has
-- ## no non-transactional migration wiring and SIX migrations say so in their
-- ## headers (V78, V81, V82, V83, V84, V85). CONCURRENTLY inside Flyway's
-- ## transaction fails with SQLSTATE 25001.
-- ##
-- ## The two role indexes (uq_role_code_global, uq_role_org_code) are NOT here.
-- ## uq_role_code is retained to protect ApprovalEngineImpl:301 and
-- ## StepApproverResolver:78-84, and while it stands those two are inert.
-- ## They land with the uq_role_code drop, behind P4-1c.
-- ###########################################################################

SET LOCAL lock_timeout = '3s';

CREATE UNIQUE INDEX IF NOT EXISTS uq_organisation_alias
    ON organisations (alias) WHERE alias IS NOT NULL;


-- ###########################################################################
-- ## V101 — backfill and validate.  EXPAND + BACKFILL ONLY.
-- ##
-- ## Nothing here constrains. No SET NOT NULL, no DEFAULT, no stored function.
-- ## Those move to P2-1, together with the two foreign keys and D-10's email
-- ## index swap, at the point where AppUser.organisation is mapped in the
-- ## entity and the application sets it explicitly.
-- ##
-- ## STANDING-RULE NOTE: "provisioning over data migrations" is not breached
-- ## by a convergent backfill that only fills NULLs from unambiguous evidence.
-- ## The app-side reconciler (P1-3) is still required and still owns the alias
-- ## and the roles; it is the only healer after a partial restore.
-- ###########################################################################

SET LOCAL lock_timeout = '3s';

-- --- app_users, most authoritative key first -------------------------------
-- Each pass fills only rows still NULL, and only where the evidence is
-- unambiguous (exactly one distinct organisation).
--
-- LIVE MEMBERSHIP ONLY. v1 omitted these predicates. user_company is a
-- superset of live membership — UserCompanyBackfill derived pairs from ALL
-- branch assignments including revoked ones — so without them pass (a) could
-- attribute a user from a company they left. Zero rows differ today
-- (measured: 0 revoked anywhere), which is exactly why it is free to be
-- correct now: the statement's checksum freezes forever.
--
-- NOTE on the HAVING clause: it SKIPS a user with contradictory evidence, it
-- does not mark them — so a user skipped by (a) may still be attributed by
-- (b), (c) or (d) from weaker evidence. That is intended, and the residual is
-- reported by the reconciler.

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

-- --- D-9 aggregate roots, via company_id -----------------------------------
-- v1 deferred this claiming it "would give back the window". That was wrong by
-- roughly four orders of magnitude: MEASURED at 163 ms for the whole set on
-- the restored production copy, against V99's own 42 ms. Deferring it would
-- have shipped 31 columns that are 100% NULL with no dated item to fill them,
-- and D-5/D-6 (per-tenant restore, export, delete) would not get the property
-- the hedge was bought for.
--
-- companies.organisation_id is BIGINT NOT NULL (V1__baseline.sql:44), so this
-- is total wherever company_id is set — which is every row, all these tables
-- being company_id NOT NULL.

UPDATE sales_invoices      t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE sales_orders        t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE quotations          t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE deliveries          t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE purchase_orders     t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE supplier_quotes     t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE goods_receipts      t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE supplier_bills      t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE stock_movements     t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE stock_transfers     t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE journal_batches     t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE journal_entries     t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE ar_invoices         t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE ar_receipts         t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE ar_credit_notes     t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE ar_write_offs       t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE ap_payments         t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE ap_debit_notes      t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE payroll_runs        t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE pos_sessions        t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE vat_returns         t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE approval_requests   t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE generated_documents t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE products            t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE customers           t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE suppliers           t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE employees           t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE chart_of_accounts   t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE cash_bank_accounts  t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE pos_tills           t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;
UPDATE petty_cash_funds    t SET organisation_id = c.organisation_id FROM companies c WHERE c.id = t.company_id AND t.organisation_id IS NULL;

-- --- residual report, NOT an abort -----------------------------------------
-- v1 raised an EXCEPTION here. That is now wrong for two reasons: nothing in
-- this release depends on the column being complete (NOT NULL moved to P2-1),
-- and an abort would stop a customer's ERP over a condition that is harmless
-- today. The HARD GATE belongs in P2-1, immediately before SET NOT NULL.
--
-- v1's message also said "No data has been changed", which is false at the
-- database level: `spring.flyway.group` is unset (the only `group:` in
-- application.yml is under management.endpoint.health), so V99 and V100 are
-- already COMMITTED when V101 runs. Only V101's own writes roll back.
DO $$
DECLARE unattributed bigint;
BEGIN
    SELECT count(*) INTO unattributed FROM app_users WHERE organisation_id IS NULL;
    IF unattributed > 0 THEN
        RAISE WARNING
            'V101: % app_users row(s) have no organisation. Their user_company / user_branch / '
            'user_role evidence names more than one organisation, or none, and there is more than '
            'one organisation to choose from. The migration has SUCCEEDED and the application will '
            'start normally — nothing in this release requires the column. The boot-time reconciler '
            'reports the residual on every start, and P2-1 will refuse to apply NOT NULL until it '
            'is zero.', unattributed;
    END IF;
END $$;

-- --- validate what V99 registered ------------------------------------------
-- NOTE: a CHECK that evaluates to NULL is SATISFIED, so this passes while
-- every alias is NULL. It proves the format of any alias that IS set; it can
-- never prove one was set. The reconciler owns that and logs it.
ALTER TABLE organisations VALIDATE CONSTRAINT ck_organisation_alias;


-- ===========================================================================
-- DEFERRED TO P2-1 (mapping AppUser.organisation), IN THIS ORDER:
--   1. Application code sets organisation_id explicitly on every insert.
--   2. Hard gate: abort if any app_users.organisation_id IS NULL.
--   3. ALTER TABLE app_users ALTER COLUMN organisation_id SET NOT NULL;
--   4. The two foreign keys to organisations (app_users, roles) — NOT VALID
--      then VALIDATE. Re-adding them changes the restore behaviour described
--      at the top of this file, so ship the orbixerp.sh restore fix first.
--   5. D-10: DROP INDEX IF EXISTS uq_app_users_email; then
--      CREATE UNIQUE INDEX uq_app_users_org_email ON app_users
--          (organisation_id, email) WHERE email IS NOT NULL;
--      It must follow SET NOT NULL — while organisation_id is nullable a
--      composite unique permits duplicate emails on every NULL-org row.
--
-- ALSO NOT IN THIS RELEASE:
--   * roles.organisation_id SET NOT NULL — R__seed_permissions.sql:287 inserts
--     roles without the column and NOT NULL is checked before the ON CONFLICT
--     arbiter, so it would fail the repeatable seed on every boot everywhere.
--   * DROP CONSTRAINT uq_role_code — breaks approvals at one organisation.
--     Behind P4-1c. Note it is a CONSTRAINT, not an index: DROP CONSTRAINT.
--   * audit_logs.organisation_id derivation — background pass. The key is
--     COALESCE(company_id -> organisation_id, actor_user_id -> organisation_id):
--     actor-only would silently lose 545 rows (8.7%) on production, and those
--     are the system/outbox rows carrying GL and stock postings.
--
-- ⚠ OPEN FOR THE OWNER — the D-9 boundary.
--   "Aggregate root" cannot be derived from the schema: 169 of 204 tables have
--   both company_id NOT NULL and a uid, line tables included. The 31 tables
--   above are a judgement call — business documents and master-data roots.
--   Every other company-scoped table remains reachable in one further hop via
--   its parent's foreign key, which is what per-tenant extraction actually
--   needs. Three options, now that the cost is measured (~163 ms for 31):
--     (a) this list;  (b) add specific tables you expect to extract directly;
--     (c) all 169, which removes the judgement call entirely and contradicts
--         §2.1's "no change to the 182 company-scoped tables".
--   Decide before authoring: V99's checksum freezes on first apply.
-- ===========================================================================
