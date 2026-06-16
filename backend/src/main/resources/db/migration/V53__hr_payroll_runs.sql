-- V62 — HR & Payroll: payroll run tables (ADR-0032 D-6)
-- Creates: payroll_runs, payroll_lines, payroll_line_items,
--          payroll_statutory_snapshots, payslips
-- CRITICAL: every table has version BIGINT NOT NULL DEFAULT 0 (Hibernate ddl-validate).
-- Additive only. V1–V61 FROZEN.

-- ============================================================================
-- payroll_runs (header)
-- ============================================================================
CREATE TABLE payroll_runs (
    id                  BIGSERIAL    PRIMARY KEY,
    uid                 VARCHAR(26)  NOT NULL,
    company_id          BIGINT       NOT NULL,
    branch_id           BIGINT,
    run_number          VARCHAR(30)  NOT NULL,
    period_year         SMALLINT     NOT NULL,
    period_month        SMALLINT     NOT NULL,
    pay_date            DATE         NOT NULL,
    status              VARCHAR(12)  NOT NULL DEFAULT 'DRAFT',
    gross_total         NUMERIC(19,4) NOT NULL DEFAULT 0,
    deduction_total     NUMERIC(19,4) NOT NULL DEFAULT 0,
    net_total           NUMERIC(19,4) NOT NULL DEFAULT 0,
    employer_cost_total NUMERIC(19,4) NOT NULL DEFAULT 0,
    calculated_at       TIMESTAMPTZ,
    approved_at         TIMESTAMPTZ,
    posted_at           TIMESTAMPTZ,
    paid_at             TIMESTAMPTZ,
    reversed_at         TIMESTAMPTZ,
    approved_by         BIGINT,
    posted_by           BIGINT,
    gl_entry_uid        VARCHAR(26),
    reversal_of_run_uid VARCHAR(26),
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    updated_by          BIGINT,
    CONSTRAINT uq_payroll_run_uid            UNIQUE (uid),
    CONSTRAINT uq_payroll_run_company_number UNIQUE (company_id, run_number),
    CONSTRAINT chk_payroll_run_month         CHECK  (period_month BETWEEN 1 AND 12),
    CONSTRAINT chk_payroll_run_status        CHECK  (status IN ('DRAFT','CALCULATED','APPROVED','POSTED','PAID','REVERSED')),
    CONSTRAINT fk_payroll_run_company        FOREIGN KEY (company_id)  REFERENCES companies(id),
    CONSTRAINT fk_payroll_run_branch         FOREIGN KEY (branch_id)   REFERENCES branches(id),
    CONSTRAINT fk_payroll_run_approved_by    FOREIGN KEY (approved_by) REFERENCES app_users(id),
    CONSTRAINT fk_payroll_run_posted_by      FOREIGN KEY (posted_by)   REFERENCES app_users(id)
);

-- one live run per company per month (REVERSED frees the period — BR-HR-03)
CREATE UNIQUE INDEX uq_payroll_run_company_period
    ON payroll_runs (company_id, period_year, period_month)
    WHERE status <> 'REVERSED';

CREATE INDEX ix_payroll_runs_company_period ON payroll_runs (company_id, period_year, period_month);

-- ============================================================================
-- payroll_lines (one per employee per run)
-- ============================================================================
CREATE TABLE payroll_lines (
    id                       BIGSERIAL     PRIMARY KEY,
    uid                      VARCHAR(26)   NOT NULL,
    payroll_run_id           BIGINT        NOT NULL,
    company_id               BIGINT        NOT NULL,
    employee_id              BIGINT        NOT NULL,
    employee_number          VARCHAR(30)   NOT NULL,
    employee_name            VARCHAR(160)  NOT NULL,
    department_name          VARCHAR(120),
    gross_amount             NUMERIC(19,4) NOT NULL DEFAULT 0,
    taxable_amount           NUMERIC(19,4) NOT NULL DEFAULT 0,
    net_amount               NUMERIC(19,4) NOT NULL DEFAULT 0,
    paye_amount              NUMERIC(19,4) NOT NULL DEFAULT 0,
    nssf_employee_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,
    heslb_amount             NUMERIC(19,4) NOT NULL DEFAULT 0,
    voluntary_deduction_total NUMERIC(19,4) NOT NULL DEFAULT 0,
    loan_deduction_total     NUMERIC(19,4) NOT NULL DEFAULT 0,
    nssf_employer_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,
    wcf_employer_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
    sdl_employer_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
    status                   VARCHAR(10)   NOT NULL DEFAULT 'OK',
    flag_reason              VARCHAR(255),
    currency                 VARCHAR(3)    NOT NULL DEFAULT 'TZS',
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by               BIGINT,
    updated_at               TIMESTAMPTZ,
    updated_by               BIGINT,
    CONSTRAINT uq_payroll_line_uid          UNIQUE (uid),
    CONSTRAINT uq_payroll_line_run_employee UNIQUE (payroll_run_id, employee_id),
    CONSTRAINT chk_payroll_line_status      CHECK  (status IN ('OK','FLAGGED')),
    CONSTRAINT chk_payroll_line_net_nonneg  CHECK  (net_amount >= 0 OR status = 'FLAGGED'),
    CONSTRAINT fk_payroll_line_run          FOREIGN KEY (payroll_run_id) REFERENCES payroll_runs(id),
    CONSTRAINT fk_payroll_line_company      FOREIGN KEY (company_id)     REFERENCES companies(id),
    CONSTRAINT fk_payroll_line_employee     FOREIGN KEY (employee_id)    REFERENCES employees(id)
);

CREATE INDEX ix_payroll_lines_run               ON payroll_lines (payroll_run_id);
CREATE INDEX ix_payroll_lines_company_employee  ON payroll_lines (company_id, employee_id);

-- ============================================================================
-- payroll_line_items (earnings/deduction detail — payslip + GL grouping)
-- ============================================================================
CREATE TABLE payroll_line_items (
    id                  BIGSERIAL     PRIMARY KEY,
    uid                 VARCHAR(26)   NOT NULL,
    payroll_line_id     BIGINT        NOT NULL,
    company_id          BIGINT        NOT NULL,
    pay_component_id    BIGINT,
    item_kind           VARCHAR(20)   NOT NULL,
    label               VARCHAR(120)  NOT NULL,
    amount              NUMERIC(19,4) NOT NULL,
    gl_account_id       BIGINT,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          BIGINT,
    CONSTRAINT uq_payroll_line_item_uid  UNIQUE (uid),
    CONSTRAINT chk_payroll_line_item_kind CHECK (item_kind IN ('EARNING','DEDUCTION','EMPLOYER_COST','STATUTORY')),
    CONSTRAINT fk_payroll_line_item_line          FOREIGN KEY (payroll_line_id)  REFERENCES payroll_lines(id),
    CONSTRAINT fk_payroll_line_item_company       FOREIGN KEY (company_id)        REFERENCES companies(id),
    CONSTRAINT fk_payroll_line_item_pay_component FOREIGN KEY (pay_component_id) REFERENCES pay_components(id),
    CONSTRAINT fk_payroll_line_item_gl_account    FOREIGN KEY (gl_account_id)    REFERENCES chart_of_accounts(id)
);

CREATE INDEX ix_payroll_line_items_line ON payroll_line_items (payroll_line_id);

-- ============================================================================
-- payroll_statutory_snapshots (reproducibility anchor — one per line)
-- ============================================================================
CREATE TABLE payroll_statutory_snapshots (
    id                       BIGSERIAL    PRIMARY KEY,
    uid                      VARCHAR(26)  NOT NULL,
    payroll_line_id          BIGINT       NOT NULL,
    company_id               BIGINT       NOT NULL,
    applied_paye_band_set_uid VARCHAR(26),
    applied_nssf_set_uid     VARCHAR(26),
    applied_wcf_set_uid      VARCHAR(26),
    applied_sdl_set_uid      VARCHAR(26),
    applied_heslb_set_uid    VARCHAR(26),
    pay_date                 DATE         NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by               BIGINT,
    CONSTRAINT uq_payroll_statutory_snapshot_uid  UNIQUE (uid),
    CONSTRAINT uq_payroll_statutory_snapshot_line UNIQUE (payroll_line_id),
    CONSTRAINT fk_payroll_statutory_snapshot_line    FOREIGN KEY (payroll_line_id) REFERENCES payroll_lines(id),
    CONSTRAINT fk_payroll_statutory_snapshot_company FOREIGN KEY (company_id)      REFERENCES companies(id)
);

-- ============================================================================
-- payslips (immutable document per employee per posted run)
-- ============================================================================
CREATE TABLE payslips (
    id                    BIGSERIAL     PRIMARY KEY,
    uid                   VARCHAR(26)   NOT NULL,
    company_id            BIGINT        NOT NULL,
    payroll_run_id        BIGINT        NOT NULL,
    payroll_line_id       BIGINT        NOT NULL,
    employee_id           BIGINT        NOT NULL,
    payslip_number        VARCHAR(30)   NOT NULL,
    pay_date              DATE          NOT NULL,
    gross_amount          NUMERIC(19,4) NOT NULL DEFAULT 0,
    deduction_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
    net_amount            NUMERIC(19,4) NOT NULL DEFAULT 0,
    employer_cost_amount  NUMERIC(19,4) NOT NULL DEFAULT 0,
    ytd_gross             NUMERIC(19,4) NOT NULL DEFAULT 0,
    ytd_paye              NUMERIC(19,4) NOT NULL DEFAULT 0,
    ytd_nssf_employee     NUMERIC(19,4) NOT NULL DEFAULT 0,
    ytd_net               NUMERIC(19,4) NOT NULL DEFAULT 0,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            BIGINT,
    CONSTRAINT uq_payslip_uid            UNIQUE (uid),
    CONSTRAINT uq_payslip_company_number UNIQUE (company_id, payslip_number),
    CONSTRAINT uq_payslip_run_employee   UNIQUE (payroll_run_id, employee_id),
    CONSTRAINT fk_payslip_company     FOREIGN KEY (company_id)      REFERENCES companies(id),
    CONSTRAINT fk_payslip_run         FOREIGN KEY (payroll_run_id)  REFERENCES payroll_runs(id),
    CONSTRAINT fk_payslip_line        FOREIGN KEY (payroll_line_id) REFERENCES payroll_lines(id),
    CONSTRAINT fk_payslip_employee    FOREIGN KEY (employee_id)     REFERENCES employees(id)
);

CREATE INDEX ix_payslips_employee ON payslips (company_id, employee_id);
CREATE INDEX ix_payslips_run      ON payslips (payroll_run_id);
