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
