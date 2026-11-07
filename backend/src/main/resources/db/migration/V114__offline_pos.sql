-- ============================================================================
-- V114 — Offline POS, phase 1 (ADR-0068). DDL approved by the owner 2026-11-07.
--
-- A till that is allowed to (pos_tills.offline_allowed) may keep selling while the server is
-- unreachable; its sales arrive later through a separate sync path. This migration adds:
--   (1) the per-till switch                       pos_tills.offline_allowed
--   (2) shifts first opened offline on the till    pos_sessions.opened_offline / offline_client_key
--   (3) offline sales                              sales_invoices.sold_offline / offline_number / synced_at
--   (4) offline periods (who switched, why, when)  pos_offline_periods
--   (5) the manager review list                    pos_offline_reviews
--
-- Additive only: every new column on an existing table is nullable or defaulted, every new CHECK
-- holds for every existing row (all false / NULL), so no backfill and no behaviour change for any
-- till until an admin allows offline selling on it.
--
-- Plain transactional CREATE INDEX, not CONCURRENTLY: this repo has no non-transactional migration
-- wiring (see V102). The partial unique indexes cover no existing rows (the columns are new and
-- NULL), so they build instantly; the lock timeout below makes any held lock fail fast instead.
-- ============================================================================

SET LOCAL lock_timeout = '10s';

-- (1) D-1: which tills may sell offline. Default off: no till changes behaviour.
ALTER TABLE pos_tills
    ADD COLUMN offline_allowed BOOLEAN NOT NULL DEFAULT false;

-- (2) D-4: a shift first opened offline on the till, created once at sync.
ALTER TABLE pos_sessions
    ADD COLUMN opened_offline     BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN offline_client_key VARCHAR(64) NULL,
    ADD CONSTRAINT chk_pos_session_offline_key
        CHECK (opened_offline = false OR offline_client_key IS NOT NULL);
CREATE UNIQUE INDEX ux_pos_sessions_offline_client_key
    ON pos_sessions (company_id, offline_client_key)
    WHERE offline_client_key IS NOT NULL;

-- (3) D-3/D-5: offline sales. finalised_at stays the business date (time SOLD);
--     synced_at is when the server received it.
ALTER TABLE sales_invoices
    ADD COLUMN sold_offline   BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN offline_number VARCHAR(40) NULL,
    ADD COLUMN synced_at      TIMESTAMPTZ NULL,
    ADD CONSTRAINT chk_sales_invoice_offline
        CHECK (sold_offline = false OR (offline_number IS NOT NULL AND synced_at IS NOT NULL));
CREATE UNIQUE INDEX ux_sales_invoices_offline_number
    ON sales_invoices (company_id, offline_number)
    WHERE offline_number IS NOT NULL;

-- (4) D-1/D-8: each offline period of a till (who switched, why, how long).
CREATE TABLE pos_offline_periods (
    id              BIGSERIAL PRIMARY KEY,
    uid             VARCHAR(26) NOT NULL,
    company_id      BIGINT      NOT NULL,
    branch_id       BIGINT      NOT NULL,
    pos_till_id     BIGINT      NOT NULL,
    pos_session_id  BIGINT,
    client_key      VARCHAR(64) NOT NULL,          -- idempotent upload from the till
    reason          VARCHAR(16) NOT NULL,          -- UNREACHABLE | MANUAL
    switched_by     BIGINT      NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    sales_count     INTEGER     NOT NULL DEFAULT 0,
    synced_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,
    CONSTRAINT uq_pos_offline_period_uid        UNIQUE (uid),
    CONSTRAINT uq_pos_offline_period_client_key UNIQUE (company_id, client_key),
    CONSTRAINT fk_pos_offline_period_company    FOREIGN KEY (company_id)     REFERENCES companies(id),
    CONSTRAINT fk_pos_offline_period_branch     FOREIGN KEY (branch_id)      REFERENCES branches(id),
    CONSTRAINT fk_pos_offline_period_till       FOREIGN KEY (pos_till_id)    REFERENCES pos_tills(id),
    CONSTRAINT fk_pos_offline_period_session    FOREIGN KEY (pos_session_id) REFERENCES pos_sessions(id),
    CONSTRAINT fk_pos_offline_period_switched   FOREIGN KEY (switched_by)    REFERENCES app_users(id),
    CONSTRAINT chk_pos_offline_period_reason    CHECK (reason IN ('UNREACHABLE','MANUAL')),
    CONSTRAINT chk_pos_offline_period_times     CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT chk_pos_offline_period_sales     CHECK (sales_count >= 0)
);
CREATE INDEX ix_pos_offline_periods_company ON pos_offline_periods (company_id);
CREATE INDEX ix_pos_offline_periods_branch  ON pos_offline_periods (branch_id);
CREATE INDEX ix_pos_offline_periods_till    ON pos_offline_periods (pos_till_id);

-- (5) D-8: the manager review list (flagged or held offline sales).
CREATE TABLE pos_offline_reviews (
    id                BIGSERIAL PRIMARY KEY,
    uid               VARCHAR(26) NOT NULL,
    company_id        BIGINT      NOT NULL,
    branch_id         BIGINT      NOT NULL,
    pos_till_id       BIGINT      NOT NULL,
    pos_session_id    BIGINT,
    sales_invoice_id  BIGINT,                      -- NULL when HELD (not posted)
    offline_number    VARCHAR(40),
    kind              VARCHAR(24) NOT NULL,
    detail            VARCHAR(500),
    held_request      TEXT,                        -- the sale as rung, JSON, HELD only
    status            VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    acknowledged_by   BIGINT,
    acknowledged_at   TIMESTAMPTZ,
    acknowledge_note  VARCHAR(500),
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        BIGINT,
    CONSTRAINT uq_pos_offline_review_uid      UNIQUE (uid),
    CONSTRAINT fk_pos_offline_review_company  FOREIGN KEY (company_id)       REFERENCES companies(id),
    CONSTRAINT fk_pos_offline_review_branch   FOREIGN KEY (branch_id)        REFERENCES branches(id),
    CONSTRAINT fk_pos_offline_review_till     FOREIGN KEY (pos_till_id)      REFERENCES pos_tills(id),
    CONSTRAINT fk_pos_offline_review_session  FOREIGN KEY (pos_session_id)   REFERENCES pos_sessions(id),
    CONSTRAINT fk_pos_offline_review_invoice  FOREIGN KEY (sales_invoice_id) REFERENCES sales_invoices(id),
    CONSTRAINT fk_pos_offline_review_ack_by   FOREIGN KEY (acknowledged_by)  REFERENCES app_users(id),
    CONSTRAINT chk_pos_offline_review_kind    CHECK (kind IN ('PRICE_DIFFERENCE','NEGATIVE_STOCK',
                                                              'CLOSED_PERIOD','BELOW_COST','HELD')),
    CONSTRAINT chk_pos_offline_review_status  CHECK (status IN ('OPEN','ACKNOWLEDGED')),
    CONSTRAINT chk_pos_offline_review_ack     CHECK (status <> 'ACKNOWLEDGED'
                                                     OR (acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL)),
    CONSTRAINT chk_pos_offline_review_held    CHECK (kind <> 'HELD' OR (sales_invoice_id IS NULL AND held_request IS NOT NULL)),
    CONSTRAINT chk_pos_offline_review_posted  CHECK (kind = 'HELD' OR sales_invoice_id IS NOT NULL)
);
CREATE INDEX ix_pos_offline_reviews_company_status ON pos_offline_reviews (company_id, status);
CREATE INDEX ix_pos_offline_reviews_branch         ON pos_offline_reviews (branch_id);
CREATE INDEX ix_pos_offline_reviews_invoice        ON pos_offline_reviews (sales_invoice_id);
