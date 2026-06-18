-- V51 — CRM module (ADR-0031, Stage 1 + Stage 2):
-- (1) pipeline_stages  — configurable per-company funnel positions
-- (2) leads            — unqualified prospects (CRM-owned contact)
-- (3) opportunities    — qualified deals against a Parties customer
-- (4) opportunity_lines — optional estimate lines on an opportunity
-- (5) activities       — interactions (CALL/EMAIL/MEETING/NOTE/TASK) against lead or opportunity
-- (6) indexes          — performance (D-10)
-- (7) pipeline-stage backfill for EXISTING companies (#12-safe)
-- (8) permission seed + ORG_ADMIN grant
-- Additive only. V1–V30 are FROZEN.

-- ============================================================================
-- (1) pipeline_stages  (ADR-0031 D-4)
-- ============================================================================

CREATE TABLE pipeline_stages (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    company_id           BIGINT          NOT NULL,
    name                 VARCHAR(60)     NOT NULL,
    display_order        SMALLINT        NOT NULL,
    default_probability  NUMERIC(5,2)    NOT NULL,
    is_active            BOOLEAN         NOT NULL DEFAULT true,
    status               VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_pipeline_stage_uid          UNIQUE (uid),
    CONSTRAINT uq_pipeline_stage_company_name  UNIQUE (company_id, name),
    CONSTRAINT uq_pipeline_stage_company_order UNIQUE (company_id, display_order),
    CONSTRAINT fk_pipeline_stage_company      FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT chk_pipeline_stage_probability CHECK (default_probability BETWEEN 0 AND 100),
    CONSTRAINT chk_pipeline_stage_status      CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);

-- ============================================================================
-- (2) leads  (ADR-0031 D-3)
-- ============================================================================

CREATE TABLE leads (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    company_id           BIGINT          NOT NULL,
    branch_id            BIGINT          NOT NULL,
    lead_number          VARCHAR(30)     NOT NULL,
    lead_status          VARCHAR(20)     NOT NULL DEFAULT 'NEW',
    lead_source          VARCHAR(30)     NOT NULL,
    display_name         VARCHAR(200)    NOT NULL,
    company_name         VARCHAR(200),
    contact_person       VARCHAR(200),
    phone                VARCHAR(40),
    email                VARCHAR(160),
    owner_user_id        BIGINT,
    customer_id          BIGINT,
    customer_uid         VARCHAR(26),
    estimated_value      NUMERIC(19,4),     -- P2: lead-qualification estimate
    next_follow_up_date  DATE,              -- P2: next planned follow-up
    industry             VARCHAR(120),      -- P2: lead industry classification
    region               VARCHAR(120),      -- P2: lead region
    disqualify_reason    VARCHAR(255),
    notes                VARCHAR(1000),
    qualified_at         TIMESTAMPTZ,
    converted_at         TIMESTAMPTZ,
    disqualified_at      TIMESTAMPTZ,
    status               VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_lead_uid                  UNIQUE (uid),
    CONSTRAINT uq_lead_company_number       UNIQUE (company_id, lead_number),
    CONSTRAINT fk_lead_company              FOREIGN KEY (company_id)   REFERENCES companies(id),
    CONSTRAINT fk_lead_branch               FOREIGN KEY (branch_id)    REFERENCES branches(id),
    CONSTRAINT fk_lead_owner                FOREIGN KEY (owner_user_id) REFERENCES app_users(id),
    CONSTRAINT fk_lead_customer             FOREIGN KEY (customer_id)  REFERENCES customers(id),
    CONSTRAINT chk_lead_status              CHECK (lead_status IN ('NEW','CONTACTED','QUALIFIED','CONVERTED','DISQUALIFIED')),
    CONSTRAINT chk_lead_source              CHECK (lead_source IN ('WEBSITE','REFERRAL','WALK_IN','CAMPAIGN','COLD_CALL','EXISTING_CUSTOMER','OTHER')),
    CONSTRAINT chk_lead_record_status       CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
    CONSTRAINT chk_lead_qualified_customer  CHECK (lead_status NOT IN ('QUALIFIED','CONVERTED') OR customer_id IS NOT NULL),
    CONSTRAINT chk_lead_disqualify_reason   CHECK (lead_status <> 'DISQUALIFIED' OR disqualify_reason IS NOT NULL)
);

-- ============================================================================
-- (3) opportunities  (ADR-0031 D-5)
-- ============================================================================

CREATE TABLE opportunities (
    id                          BIGSERIAL PRIMARY KEY,
    uid                         VARCHAR(26)     NOT NULL,
    company_id                  BIGINT          NOT NULL,
    branch_id                   BIGINT          NOT NULL,
    opportunity_number          VARCHAR(30)     NOT NULL,
    opportunity_status          VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    title                       VARCHAR(200)    NOT NULL,
    customer_id                 BIGINT          NOT NULL,
    customer_uid                VARCHAR(26)     NOT NULL,
    agent_id                    BIGINT,
    owner_user_id               BIGINT,
    source_lead_id              BIGINT,
    source_lead_uid             VARCHAR(26),
    pipeline_stage_id           BIGINT          NOT NULL,
    win_probability             NUMERIC(5,2)    NOT NULL DEFAULT 0,
    estimated_value_amount      NUMERIC(19,4)   NOT NULL DEFAULT 0,
    currency                    VARCHAR(3)      NOT NULL,
    expected_close_date         DATE,
    won_at                      TIMESTAMPTZ,
    lost_at                     TIMESTAMPTZ,
    loss_reason                 VARCHAR(255),
    next_step                   VARCHAR(255),   -- P2: planned next step toward close
    next_action_date            DATE,           -- P2: date of next action
    stage_changed_at            TIMESTAMPTZ,    -- P2: funnel ageing (days-in-stage)
    converted_document_kind     VARCHAR(20),
    converted_document_uid      VARCHAR(26),
    converted_at                TIMESTAMPTZ,
    status                      VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    version                     BIGINT          NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  BIGINT,
    updated_at                  TIMESTAMPTZ,
    updated_by                  BIGINT,
    CONSTRAINT uq_opportunity_uid                   UNIQUE (uid),
    CONSTRAINT uq_opportunity_company_number        UNIQUE (company_id, opportunity_number),
    CONSTRAINT uq_opportunity_converted_document    UNIQUE (converted_document_uid),
    CONSTRAINT fk_opportunity_company               FOREIGN KEY (company_id)        REFERENCES companies(id),
    CONSTRAINT fk_opportunity_branch                FOREIGN KEY (branch_id)         REFERENCES branches(id),
    CONSTRAINT fk_opportunity_customer              FOREIGN KEY (customer_id)       REFERENCES customers(id),
    CONSTRAINT fk_opportunity_agent                 FOREIGN KEY (agent_id)          REFERENCES agents(id),
    CONSTRAINT fk_opportunity_owner                 FOREIGN KEY (owner_user_id)     REFERENCES app_users(id),
    CONSTRAINT fk_opportunity_source_lead           FOREIGN KEY (source_lead_id)    REFERENCES leads(id),
    CONSTRAINT fk_opportunity_stage                 FOREIGN KEY (pipeline_stage_id) REFERENCES pipeline_stages(id),
    CONSTRAINT chk_opportunity_status              CHECK (opportunity_status IN ('OPEN','WON','LOST')),
    CONSTRAINT chk_opportunity_record_status       CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
    CONSTRAINT chk_opportunity_probability         CHECK (win_probability BETWEEN 0 AND 100),
    CONSTRAINT chk_opportunity_won_at              CHECK (opportunity_status <> 'WON'  OR won_at IS NOT NULL),
    CONSTRAINT chk_opportunity_lost                CHECK (opportunity_status <> 'LOST' OR (lost_at IS NOT NULL AND loss_reason IS NOT NULL)),
    -- P2: loss_reason swapped from free-text to OpportunityLossReason enum (kept column name)
    CONSTRAINT chk_opportunity_loss_reason         CHECK (loss_reason IS NULL OR loss_reason IN (
        'PRICE','COMPETITOR','NO_BUDGET','NO_DECISION','TIMING','NO_REQUIREMENT','LOST_CONTACT','PRODUCT_FIT','OTHER')),
    CONSTRAINT chk_opportunity_converted           CHECK (
        (converted_document_uid IS NULL AND converted_document_kind IS NULL)
        OR (converted_document_uid IS NOT NULL AND converted_document_kind IS NOT NULL)),
    CONSTRAINT chk_opportunity_doc_kind            CHECK (converted_document_kind IS NULL OR converted_document_kind IN ('QUOTATION','SALES_ORDER'))
);

-- ============================================================================
-- (4) opportunity_lines  (ADR-0031 D-5)
-- ============================================================================

CREATE TABLE opportunity_lines (
    id                              BIGSERIAL PRIMARY KEY,
    uid                             VARCHAR(26)     NOT NULL,
    opportunity_id                  BIGINT          NOT NULL,
    company_id                      BIGINT          NOT NULL,
    branch_id                       BIGINT          NOT NULL,
    line_no                         SMALLINT        NOT NULL,
    product_id                      BIGINT          NOT NULL,
    product_code                    VARCHAR(60)     NOT NULL,
    product_name                    VARCHAR(120)    NOT NULL,
    unit_id                         BIGINT          NOT NULL,
    unit_name                       VARCHAR(60)     NOT NULL,
    estimated_qty                   NUMERIC(19,6)   NOT NULL,
    estimated_unit_price_amount     NUMERIC(19,4)   NOT NULL DEFAULT 0,
    line_discount_amount            NUMERIC(19,4),
    line_discount_percent           NUMERIC(9,4),
    currency                        VARCHAR(3)      NOT NULL,
    version                         BIGINT          NOT NULL DEFAULT 0,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                      BIGINT,
    updated_at                      TIMESTAMPTZ,
    updated_by                      BIGINT,
    CONSTRAINT uq_opportunity_line_uid      UNIQUE (uid),
    CONSTRAINT uq_opportunity_line_no       UNIQUE (opportunity_id, line_no),
    CONSTRAINT fk_opportunity_line_header   FOREIGN KEY (opportunity_id) REFERENCES opportunities(id),
    CONSTRAINT fk_opportunity_line_product  FOREIGN KEY (product_id)     REFERENCES products(id),
    CONSTRAINT fk_opportunity_line_unit     FOREIGN KEY (unit_id)        REFERENCES units_of_measure(id),
    CONSTRAINT chk_opportunity_line_qty     CHECK (estimated_qty > 0),
    CONSTRAINT chk_opportunity_line_disc    CHECK (line_discount_percent IS NULL OR line_discount_percent BETWEEN 0 AND 100)
);

-- ============================================================================
-- (5) activities  (ADR-0031 D-6)
-- ============================================================================

CREATE TABLE activities (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    company_id           BIGINT          NOT NULL,
    branch_id            BIGINT          NOT NULL,
    activity_number      VARCHAR(30)     NOT NULL,
    activity_type        VARCHAR(20)     NOT NULL,
    lead_id              BIGINT,
    opportunity_id       BIGINT,
    subject              VARCHAR(200)    NOT NULL,
    body                 VARCHAR(2000),
    occurred_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    due_date             DATE,
    assignee_user_id     BIGINT,
    outcome              VARCHAR(255),   -- P2: interaction result/outcome
    reminder_at          TIMESTAMPTZ,    -- P2: reminder timestamp
    duration_minutes     INTEGER,        -- P2: interaction duration in minutes
    done                 BOOLEAN         NOT NULL DEFAULT false,
    done_at              TIMESTAMPTZ,
    status               VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_activity_uid              UNIQUE (uid),
    CONSTRAINT uq_activity_company_number   UNIQUE (company_id, activity_number),
    CONSTRAINT fk_activity_company          FOREIGN KEY (company_id)       REFERENCES companies(id),
    CONSTRAINT fk_activity_branch           FOREIGN KEY (branch_id)        REFERENCES branches(id),
    CONSTRAINT fk_activity_lead             FOREIGN KEY (lead_id)          REFERENCES leads(id),
    CONSTRAINT fk_activity_opportunity      FOREIGN KEY (opportunity_id)   REFERENCES opportunities(id),
    CONSTRAINT fk_activity_assignee         FOREIGN KEY (assignee_user_id) REFERENCES app_users(id),
    CONSTRAINT chk_activity_type            CHECK (activity_type IN ('CALL','EMAIL','MEETING','NOTE','TASK')),
    CONSTRAINT chk_activity_record_status   CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
    CONSTRAINT chk_activity_parent         CHECK (
        (lead_id IS NOT NULL AND opportunity_id IS NULL)
        OR  (lead_id IS NULL AND opportunity_id IS NOT NULL)),
    CONSTRAINT chk_activity_task_fields    CHECK (
        activity_type = 'TASK'
        OR (due_date IS NULL AND assignee_user_id IS NULL AND done = false))
);

-- ============================================================================
-- (6) Indexes  (ADR-0031 D-10)
-- ============================================================================

-- pipeline_stages
CREATE INDEX ix_pipeline_stages_company         ON pipeline_stages (company_id);

-- leads
CREATE INDEX ix_leads_company_branch_status     ON leads (company_id, branch_id, lead_status);
CREATE INDEX ix_leads_company_owner             ON leads (company_id, owner_user_id) WHERE owner_user_id IS NOT NULL;
CREATE INDEX ix_leads_customer                  ON leads (customer_id) WHERE customer_id IS NOT NULL;

-- opportunities
CREATE INDEX ix_opportunities_company_branch_status ON opportunities (company_id, branch_id, opportunity_status);
CREATE INDEX ix_opportunities_company_stage         ON opportunities (company_id, pipeline_stage_id) WHERE opportunity_status = 'OPEN';
CREATE INDEX ix_opportunities_company_close         ON opportunities (company_id, expected_close_date) WHERE opportunity_status = 'OPEN';
CREATE INDEX ix_opportunities_customer              ON opportunities (customer_id);

-- opportunity_lines
CREATE INDEX ix_opportunity_lines_opportunity   ON opportunity_lines (opportunity_id);

-- activities
CREATE INDEX ix_activities_lead                 ON activities (lead_id)          WHERE lead_id IS NOT NULL;
CREATE INDEX ix_activities_opportunity          ON activities (opportunity_id)   WHERE opportunity_id IS NOT NULL;
CREATE INDEX ix_activities_open_tasks           ON activities (company_id, assignee_user_id) WHERE activity_type = 'TASK' AND done = false;

-- ============================================================================
-- (7) Pipeline-stage backfill for EXISTING companies  (#12-safe, D-12 §6)
-- Each stage uid: 'CS' || lpad(company_id::text,6,'0') || substr(md5(stage_name),1,12)
-- Runs ON CONFLICT DO NOTHING — safe for repeated execution.
-- ============================================================================

INSERT INTO pipeline_stages (uid, company_id, name, display_order, default_probability, is_active, status, version)
SELECT
    'CS' || lpad(c.id::text, 6, '0') || substr(md5('QUALIFICATION'), 1, 12),
    c.id, 'QUALIFICATION', 1, 10.00, true, 'ACTIVE', 0
FROM companies c
ON CONFLICT (company_id, name) DO NOTHING;

INSERT INTO pipeline_stages (uid, company_id, name, display_order, default_probability, is_active, status, version)
SELECT
    'CS' || lpad(c.id::text, 6, '0') || substr(md5('NEEDS_ANALYSIS'), 1, 12),
    c.id, 'NEEDS_ANALYSIS', 2, 25.00, true, 'ACTIVE', 0
FROM companies c
ON CONFLICT (company_id, name) DO NOTHING;

INSERT INTO pipeline_stages (uid, company_id, name, display_order, default_probability, is_active, status, version)
SELECT
    'CS' || lpad(c.id::text, 6, '0') || substr(md5('PROPOSAL'), 1, 12),
    c.id, 'PROPOSAL', 3, 50.00, true, 'ACTIVE', 0
FROM companies c
ON CONFLICT (company_id, name) DO NOTHING;

INSERT INTO pipeline_stages (uid, company_id, name, display_order, default_probability, is_active, status, version)
SELECT
    'CS' || lpad(c.id::text, 6, '0') || substr(md5('NEGOTIATION'), 1, 12),
    c.id, 'NEGOTIATION', 4, 75.00, true, 'ACTIVE', 0
FROM companies c
ON CONFLICT (company_id, name) DO NOTHING;

INSERT INTO pipeline_stages (uid, company_id, name, display_order, default_probability, is_active, status, version)
SELECT
    'CS' || lpad(c.id::text, 6, '0') || substr(md5('CLOSING'), 1, 12),
    c.id, 'CLOSING', 5, 90.00, true, 'ACTIVE', 0
FROM companies c
ON CONFLICT (company_id, name) DO NOTHING;
