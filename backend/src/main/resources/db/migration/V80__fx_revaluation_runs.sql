-- V80 — FX/Multi-currency: unrealized period-end FX revaluation run tables
--       (ADR-0036 D-6, Tranche 4)
--
-- Creates:
--   fx_revaluation_runs  — header / idempotency anchor (one per company+period)
--   fx_revaluation_run_lines — per-currency detail lines
--
-- Naming conventions (db-naming-convention memory): plural owned-children, singular junctions,
--   singular constraint roots (uq_/fk_/chk_), plural ix_, uid VARCHAR(26).
-- version BIGINT NOT NULL DEFAULT 0 mandatory on every new UidEntity table.
-- Additive only. V1–V79 are FROZEN. No edit to any prior migration.

-- ============================================================================
-- fx_revaluation_runs (header)
-- ============================================================================
CREATE TABLE fx_revaluation_runs (
    id                    BIGSERIAL PRIMARY KEY,
    uid                   VARCHAR(26)      NOT NULL,
    company_id            BIGINT           NOT NULL,
    branch_id             BIGINT,
    run_number            VARCHAR(30)      NOT NULL,
    fiscal_period_id      BIGINT           NOT NULL,
    posting_date          DATE             NOT NULL,
    spot_rate_date        DATE             NOT NULL,
    status                VARCHAR(20)      NOT NULL DEFAULT 'PREVIEWED',
    total_gain_amount     NUMERIC(19,4)    NOT NULL DEFAULT 0,
    total_loss_amount     NUMERIC(19,4)    NOT NULL DEFAULT 0,
    net_adjustment_amount NUMERIC(19,4)    NOT NULL DEFAULT 0,
    gl_entry_uid          VARCHAR(26),
    reversal_gl_entry_uid VARCHAR(26),
    executed_at           TIMESTAMPTZ,
    version               BIGINT           NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ      NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_at            TIMESTAMPTZ,
    updated_by            BIGINT,

    CONSTRAINT uq_fx_revaluation_run_uid    UNIQUE (uid),
    CONSTRAINT uq_fx_revaluation_run_number UNIQUE (company_id, run_number),

    -- D-6: one run per (company, fiscal_period) — the idempotency anchor
    CONSTRAINT uq_fx_revaluation_run_company_period UNIQUE (company_id, fiscal_period_id),

    CONSTRAINT fk_fx_revaluation_run_company
        FOREIGN KEY (company_id)       REFERENCES companies(id),
    CONSTRAINT fk_fx_revaluation_run_branch
        FOREIGN KEY (branch_id)        REFERENCES branches(id),
    CONSTRAINT fk_fx_revaluation_run_period
        FOREIGN KEY (fiscal_period_id) REFERENCES fiscal_periods(id),

    CONSTRAINT chk_fx_revaluation_run_status
        CHECK (status IN ('PREVIEWED','POSTED','REVERSED')),

    CONSTRAINT chk_fx_revaluation_run_amounts
        CHECK (total_gain_amount >= 0 AND total_loss_amount >= 0)
);

CREATE INDEX ix_fx_revaluation_runs_company
    ON fx_revaluation_runs (company_id, fiscal_period_id);

-- ============================================================================
-- fx_revaluation_run_lines (per-currency detail)
-- ============================================================================
CREATE TABLE fx_revaluation_run_lines (
    id                     BIGSERIAL PRIMARY KEY,
    uid                    VARCHAR(26)   NOT NULL,
    fx_revaluation_run_id  BIGINT        NOT NULL,
    company_id             BIGINT        NOT NULL,
    source_type            VARCHAR(10)   NOT NULL,    -- AR / AP / CASH
    currency               VARCHAR(3)    NOT NULL,
    control_account_id     BIGINT        NOT NULL,    -- FK → chart_of_accounts(id)
    outstanding_txn_amount NUMERIC(19,4) NOT NULL,    -- Σ face outstanding in foreign CCY
    carrying_base_amount   NUMERIC(19,4) NOT NULL,    -- Σ base_outstanding_amount (frozen book value)
    spot_rate              NUMERIC(19,8) NOT NULL,    -- period-end spot (units of base per 1 foreign)
    revalued_base_amount   NUMERIC(19,4) NOT NULL,    -- outstanding_txn_amount × spot_rate
    adjustment_amount      NUMERIC(19,4) NOT NULL,    -- revalued − carrying (signed; + = gain, − = loss)
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by             BIGINT,
    updated_at             TIMESTAMPTZ,
    updated_by             BIGINT,

    CONSTRAINT uq_fx_revaluation_run_line_uid UNIQUE (uid),

    CONSTRAINT fk_fx_revaluation_run_line_run
        FOREIGN KEY (fx_revaluation_run_id) REFERENCES fx_revaluation_runs(id),
    CONSTRAINT fk_fx_revaluation_run_line_company
        FOREIGN KEY (company_id)            REFERENCES companies(id),
    CONSTRAINT fk_fx_revaluation_run_line_account
        FOREIGN KEY (control_account_id)    REFERENCES chart_of_accounts(id),

    CONSTRAINT chk_fx_revaluation_run_line_source
        CHECK (source_type IN ('AR','AP','CASH')),

    CONSTRAINT chk_fx_revaluation_run_line_spot
        CHECK (spot_rate > 0)
);

CREATE INDEX ix_fx_revaluation_run_lines_run
    ON fx_revaluation_run_lines (fx_revaluation_run_id);

-- ============================================================================
-- Permission seed: FX.REVALUE + FX.EXPOSURE.VIEW (ADR-0036 D-7)
-- Grant to ORG_ADMIN (CROSS JOIN ON CONFLICT DO NOTHING — the V7/V12/V14 pattern).
-- ============================================================================

INSERT INTO permissions (code, module, description)
VALUES
    ('FX.REVALUE',       'fx', 'Preview and post the period-end FX revaluation run'),
    ('FX.EXPOSURE.VIEW', 'fx', 'View FX exposure report and document FX detail')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS  JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.code IN ('FX.REVALUE', 'FX.EXPOSURE.VIEW')
ON CONFLICT DO NOTHING;
