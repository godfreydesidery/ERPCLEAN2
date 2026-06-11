-- V20 — Approvals Workflow Engine (ADR-0022, additive on frozen V1–V19, #12-safe).
-- Five tables + permissions + ORG_ADMIN grant.
-- No per-company CROSS-JOIN seed-uids; APPROVAL numbering kind is lazy (no seed row).

-- ============================================================
-- 1. approval_policies (master, per company) — D-3
-- ============================================================
CREATE TABLE approval_policies (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid           VARCHAR(26)    NOT NULL,
    company_id    BIGINT         NOT NULL,
    document_type VARCHAR(60)    NOT NULL,
    name          VARCHAR(160)   NOT NULL,
    branch_scope  VARCHAR(20)    NOT NULL DEFAULT 'COMPANY_WIDE',
    branch_id     BIGINT,
    min_amount    NUMERIC(19,4)  NOT NULL DEFAULT 0,
    max_amount    NUMERIC(19,4),
    currency      VARCHAR(3)     NOT NULL DEFAULT 'TZS',
    is_active     BOOLEAN        NOT NULL DEFAULT true,
    status        VARCHAR(32)    NOT NULL DEFAULT 'ACTIVE',
    notes         VARCHAR(500),
    version       BIGINT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by    BIGINT,
    updated_at    TIMESTAMPTZ,
    updated_by    BIGINT,

    CONSTRAINT uq_approval_policy_uid              UNIQUE (uid),
    CONSTRAINT fk_approval_policy_company          FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_approval_policy_branch           FOREIGN KEY (branch_id)  REFERENCES branches(id),
    CONSTRAINT chk_approval_policy_branch_scope    CHECK (branch_scope IN ('COMPANY_WIDE','BRANCH')),
    CONSTRAINT chk_approval_policy_branch_consistency
        CHECK ((branch_scope = 'COMPANY_WIDE' AND branch_id IS NULL)
            OR (branch_scope = 'BRANCH' AND branch_id IS NOT NULL)),
    CONSTRAINT chk_approval_policy_min             CHECK (min_amount >= 0),
    CONSTRAINT chk_approval_policy_band            CHECK (max_amount IS NULL OR max_amount > min_amount),
    CONSTRAINT chk_approval_policy_status          CHECK (status IN ('ACTIVE','ARCHIVED','INACTIVE'))
);

CREATE INDEX ix_approval_policies_match   ON approval_policies (company_id, document_type, is_active);
CREATE INDEX ix_approval_policies_company ON approval_policies (company_id);

-- ============================================================
-- 2. approval_policy_steps (child of policy — the ordered chain) — D-3
-- ============================================================
CREATE TABLE approval_policy_steps (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid                VARCHAR(26)  NOT NULL,
    approval_policy_id BIGINT       NOT NULL,
    company_id         BIGINT       NOT NULL,
    sequence           SMALLINT     NOT NULL,
    approver_role_code VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         BIGINT,

    CONSTRAINT uq_approval_policy_step_uid  UNIQUE (uid),
    CONSTRAINT uq_approval_policy_step_seq  UNIQUE (approval_policy_id, sequence),
    CONSTRAINT fk_approval_policy_step_policy
        FOREIGN KEY (approval_policy_id) REFERENCES approval_policies(id),
    CONSTRAINT chk_approval_policy_step_seq  CHECK (sequence >= 1),
    CONSTRAINT chk_approval_policy_step_role CHECK (length(trim(approver_role_code)) > 0)
);

-- ============================================================
-- 3. approval_requests (runtime instance, per submitted document) — D-5
-- ============================================================
CREATE TABLE approval_requests (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid              VARCHAR(26)  NOT NULL,
    company_id       BIGINT       NOT NULL,
    branch_id        BIGINT       NOT NULL,
    request_number   VARCHAR(30)  NOT NULL,
    document_type    VARCHAR(60)  NOT NULL,
    document_uid     VARCHAR(26)  NOT NULL,
    amount           NUMERIC(19,4) NOT NULL,
    currency         VARCHAR(3)   NOT NULL DEFAULT 'TZS',
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    auto_approved    BOOLEAN      NOT NULL DEFAULT false,
    source_policy_id BIGINT,
    source_policy_uid VARCHAR(26),
    summary          VARCHAR(500),
    submitted_by     BIGINT       NOT NULL,
    submitted_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    resolved_by      BIGINT,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    updated_by       BIGINT,

    CONSTRAINT uq_approval_request_uid             UNIQUE (uid),
    CONSTRAINT uq_approval_request_company_number  UNIQUE (company_id, request_number),
    CONSTRAINT uq_approval_request_document        UNIQUE (company_id, document_type, document_uid),
    CONSTRAINT fk_approval_request_company         FOREIGN KEY (company_id)       REFERENCES companies(id),
    CONSTRAINT fk_approval_request_branch          FOREIGN KEY (branch_id)        REFERENCES branches(id),
    CONSTRAINT fk_approval_request_policy          FOREIGN KEY (source_policy_id) REFERENCES approval_policies(id),
    CONSTRAINT chk_approval_request_status         CHECK (status IN ('PENDING','APPROVED','REJECTED','RECALLED','CANCELLED')),
    CONSTRAINT chk_approval_request_amount         CHECK (amount >= 0),
    CONSTRAINT chk_approval_request_auto
        CHECK ((auto_approved = false) OR (auto_approved = true AND source_policy_id IS NULL))
);

CREATE INDEX ix_approval_requests_status    ON approval_requests (company_id, status);
CREATE INDEX ix_approval_requests_submitter ON approval_requests (company_id, submitted_by);

-- ============================================================
-- 4. approval_request_steps (frozen snapshot of policy steps) — D-5
-- (FK to approval_decisions added after table 5 exists — mutual reference resolved below)
-- ============================================================
CREATE TABLE approval_request_steps (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid                   VARCHAR(26)  NOT NULL,
    approval_request_id   BIGINT       NOT NULL,
    company_id            BIGINT       NOT NULL,
    branch_id             BIGINT,
    sequence              SMALLINT     NOT NULL,
    approver_role_code    VARCHAR(64)  NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    resolved_by           BIGINT,
    resolved_at           TIMESTAMPTZ,
    resolving_decision_id BIGINT,          -- FK added after approval_decisions exists (below)
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            BIGINT,

    CONSTRAINT uq_approval_request_step_uid  UNIQUE (uid),
    CONSTRAINT uq_approval_request_step_seq  UNIQUE (approval_request_id, sequence),
    CONSTRAINT fk_approval_request_step_request
        FOREIGN KEY (approval_request_id) REFERENCES approval_requests(id),
    CONSTRAINT chk_approval_request_step_status
        CHECK (status IN ('PENDING','APPROVED','REJECTED','SKIPPED')),
    CONSTRAINT chk_approval_request_step_seq  CHECK (sequence >= 1),
    CONSTRAINT chk_approval_request_step_role CHECK (length(trim(approver_role_code)) > 0)
);

CREATE INDEX ix_approval_request_steps_open
    ON approval_request_steps (approval_request_id, status, sequence);

-- ============================================================
-- 5. approval_decisions (append-only log) — D-4
-- ============================================================
CREATE TABLE approval_decisions (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid                      VARCHAR(26)  NOT NULL,
    approval_request_id      BIGINT       NOT NULL,
    approval_request_step_id BIGINT       NOT NULL,
    company_id               BIGINT       NOT NULL,
    branch_id                BIGINT,
    action                   VARCHAR(10)  NOT NULL,
    decided_by               BIGINT       NOT NULL,
    decided_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    comment                  VARCHAR(1000),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by               BIGINT,

    CONSTRAINT uq_approval_decision_uid   UNIQUE (uid),
    CONSTRAINT fk_approval_decision_request
        FOREIGN KEY (approval_request_id)      REFERENCES approval_requests(id),
    CONSTRAINT fk_approval_decision_step
        FOREIGN KEY (approval_request_step_id) REFERENCES approval_request_steps(id),
    CONSTRAINT chk_approval_decision_action CHECK (action IN ('APPROVE','REJECT'))
);

CREATE INDEX ix_approval_decisions_request ON approval_decisions (approval_request_id);
CREATE INDEX ix_approval_decisions_step    ON approval_decisions (approval_request_step_id);

-- 5a. Now add the deferred FK from approval_request_steps → approval_decisions
--     (mutual reference: steps reference the decision that resolved them — append after both tables exist)
ALTER TABLE approval_request_steps
    ADD CONSTRAINT fk_approval_request_step_decision
        FOREIGN KEY (resolving_decision_id) REFERENCES approval_decisions(id);

-- ============================================================
-- 6. Permissions + ORG_ADMIN grant — D-3 (block 6, V19 pattern)
-- ============================================================
INSERT INTO permissions (code, module, description) VALUES
    ('APPROVALS.POLICY.VIEW',    'approvals', 'View approval policies'),
    ('APPROVALS.POLICY.MANAGE',  'approvals', 'Create/edit/deactivate approval policies and their step chains'),
    ('APPROVALS.REQUEST.VIEW',   'approvals', 'View approval requests'),
    ('APPROVALS.DECIDE',         'approvals', 'Approve/reject approval-request steps and see the approvals inbox'),
    ('APPROVALS.ADMIN',          'approvals', 'Recall/cancel any approval request; override a stuck chain')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.code IN (
      'APPROVALS.POLICY.VIEW',
      'APPROVALS.POLICY.MANAGE',
      'APPROVALS.REQUEST.VIEW',
      'APPROVALS.DECIDE',
      'APPROVALS.ADMIN'
  )
ON CONFLICT DO NOTHING;
