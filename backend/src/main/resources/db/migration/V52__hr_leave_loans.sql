-- V61 — HR & Payroll: leave management + employee loans (ADR-0032 D-4)
-- Creates: leave_types, leave_requests, leave_balances, employee_loans, employee_loan_installments
-- Seeds default leave types per existing company (#12-safe uids).
-- Additive only. V1–V60 FROZEN.

-- ============================================================================
-- leave_types
-- ============================================================================
CREATE TABLE leave_types (
    id                       BIGSERIAL    PRIMARY KEY,
    uid                      VARCHAR(26)  NOT NULL,
    company_id               BIGINT       NOT NULL,
    code                     VARCHAR(30)  NOT NULL,
    name                     VARCHAR(120) NOT NULL,
    is_paid                  BOOLEAN      NOT NULL DEFAULT true,
    annual_entitlement_days  NUMERIC(9,2) NOT NULL DEFAULT 0,
    accrual_method           VARCHAR(16)  NOT NULL DEFAULT 'ANNUAL_GRANT',
    -- P2 D6 (ADR-0041): leave policy fields
    carry_forward            BOOLEAN      NOT NULL DEFAULT false,
    requires_approval        BOOLEAN      NOT NULL DEFAULT true,
    gender_eligibility       VARCHAR(10),                 -- NULL = ANY; else MALE|FEMALE
    max_consecutive_days     INT,
    active                   BOOLEAN      NOT NULL DEFAULT true,
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by               BIGINT,
    updated_at               TIMESTAMPTZ,
    updated_by               BIGINT,
    CONSTRAINT uq_leave_type_uid          UNIQUE (uid),
    CONSTRAINT uq_leave_type_company_code UNIQUE (company_id, code),
    CONSTRAINT chk_leave_type_accrual     CHECK  (accrual_method IN ('ANNUAL_GRANT','MONTHLY_ACCRUAL')),
    CONSTRAINT fk_leave_type_company      FOREIGN KEY (company_id) REFERENCES companies(id)
);

CREATE INDEX ix_leave_types_company ON leave_types (company_id);

-- ============================================================================
-- leave_requests
-- ============================================================================
CREATE TABLE leave_requests (
    id              BIGSERIAL    PRIMARY KEY,
    uid             VARCHAR(26)  NOT NULL,
    company_id      BIGINT       NOT NULL,
    employee_id     BIGINT       NOT NULL,
    leave_type_id   BIGINT       NOT NULL,
    from_date       DATE         NOT NULL,
    to_date         DATE         NOT NULL,
    days            NUMERIC(9,2) NOT NULL,
    status          VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    decided_by      BIGINT,
    decided_at      TIMESTAMPTZ,
    reason          VARCHAR(255),
    decision_note   VARCHAR(255),
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,
    CONSTRAINT uq_leave_request_uid    UNIQUE (uid),
    CONSTRAINT chk_leave_request_dates  CHECK (to_date >= from_date),
    CONSTRAINT chk_leave_request_days   CHECK (days > 0),
    CONSTRAINT chk_leave_request_status CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT fk_leave_request_company     FOREIGN KEY (company_id)    REFERENCES companies(id),
    CONSTRAINT fk_leave_request_employee    FOREIGN KEY (employee_id)   REFERENCES employees(id),
    CONSTRAINT fk_leave_request_leave_type  FOREIGN KEY (leave_type_id) REFERENCES leave_types(id),
    CONSTRAINT fk_leave_request_decided_by  FOREIGN KEY (decided_by)    REFERENCES app_users(id)
);

CREATE INDEX ix_leave_requests_employee ON leave_requests (company_id, employee_id, status);

-- ============================================================================
-- leave_balances
-- ============================================================================
CREATE TABLE leave_balances (
    id              BIGSERIAL    PRIMARY KEY,
    uid             VARCHAR(26)  NOT NULL,
    company_id      BIGINT       NOT NULL,
    employee_id     BIGINT       NOT NULL,
    leave_type_id   BIGINT       NOT NULL,
    as_of_year      SMALLINT     NOT NULL,
    entitled_days   NUMERIC(9,2) NOT NULL DEFAULT 0,
    taken_days      NUMERIC(9,2) NOT NULL DEFAULT 0,
    balance_days    NUMERIC(9,2) NOT NULL DEFAULT 0,
    -- P2 D6 (ADR-0041): accrual/carry-forward breakdown (columns only; accrual posting DEFERRED)
    carried_forward_days NUMERIC(9,2) NOT NULL DEFAULT 0,
    accrued_days         NUMERIC(9,2) NOT NULL DEFAULT 0,
    pending_days         NUMERIC(9,2) NOT NULL DEFAULT 0,
    adjustment_days      NUMERIC(9,2) NOT NULL DEFAULT 0,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,
    CONSTRAINT uq_leave_balance_uid                  UNIQUE (uid),
    CONSTRAINT uq_leave_balance_employee_type_year   UNIQUE (company_id, employee_id, leave_type_id, as_of_year),
    CONSTRAINT fk_leave_balance_company     FOREIGN KEY (company_id)    REFERENCES companies(id),
    CONSTRAINT fk_leave_balance_employee    FOREIGN KEY (employee_id)   REFERENCES employees(id),
    CONSTRAINT fk_leave_balance_leave_type  FOREIGN KEY (leave_type_id) REFERENCES leave_types(id)
);

CREATE INDEX ix_leave_balances_employee ON leave_balances (company_id, employee_id);

-- ============================================================================
-- employee_loans
-- ============================================================================
CREATE TABLE employee_loans (
    id                  BIGSERIAL     PRIMARY KEY,
    uid                 VARCHAR(26)   NOT NULL,
    company_id          BIGINT        NOT NULL,
    employee_id         BIGINT        NOT NULL,
    loan_number         VARCHAR(30)   NOT NULL,
    principal_amount    NUMERIC(19,4) NOT NULL,
    installment_amount  NUMERIC(19,4) NOT NULL,
    outstanding_amount  NUMERIC(19,4) NOT NULL,
    gl_account_id       BIGINT        NOT NULL,
    status              VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE',
    start_date          DATE          NOT NULL,
    currency            VARCHAR(3)    NOT NULL DEFAULT 'TZS',
    -- P2 D6 (ADR-0041): loan terms (single rate v1; dynamic rate schedule DEFERRED)
    interest_rate       NUMERIC(7,4),                 -- annual %, NULL = interest-free advance
    loan_type           VARCHAR(20),                  -- enum LoanType
    approved_by         BIGINT,                       -- soft-FK app_users
    term_months         INT,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    updated_by          BIGINT,
    CONSTRAINT uq_employee_loan_uid            UNIQUE (uid),
    CONSTRAINT uq_employee_loan_company_number UNIQUE (company_id, loan_number),
    CONSTRAINT chk_employee_loan_principal     CHECK  (principal_amount > 0),
    CONSTRAINT chk_employee_loan_installment   CHECK  (installment_amount > 0),
    CONSTRAINT chk_employee_loan_outstanding   CHECK  (outstanding_amount >= 0),
    CONSTRAINT chk_employee_loan_status        CHECK  (status IN ('ACTIVE','SETTLED','CANCELLED')),
    CONSTRAINT chk_employee_loan_type          CHECK  (loan_type IS NULL OR loan_type IN ('LOAN','SALARY_ADVANCE','EMERGENCY','OTHER')),
    CONSTRAINT chk_employee_loan_interest_rate CHECK  (interest_rate IS NULL OR interest_rate >= 0),
    CONSTRAINT fk_employee_loan_company     FOREIGN KEY (company_id)    REFERENCES companies(id),
    CONSTRAINT fk_employee_loan_employee    FOREIGN KEY (employee_id)   REFERENCES employees(id),
    CONSTRAINT fk_employee_loan_gl_account  FOREIGN KEY (gl_account_id) REFERENCES chart_of_accounts(id)
);

CREATE INDEX ix_employee_loans_employee ON employee_loans (company_id, employee_id);

-- ============================================================================
-- employee_loan_installments
-- ============================================================================
CREATE TABLE employee_loan_installments (
    id                  BIGSERIAL     PRIMARY KEY,
    uid                 VARCHAR(26)   NOT NULL,
    company_id          BIGINT        NOT NULL,
    employee_loan_id    BIGINT        NOT NULL,
    installment_no      SMALLINT      NOT NULL,
    due_amount          NUMERIC(19,4) NOT NULL,
    due_period          VARCHAR(7)    NOT NULL,
    deducted_in_run_uid VARCHAR(26),
    deducted_amount     NUMERIC(19,4),                          -- P2-M5: actual amount deducted (snapshot)
    due_date            DATE,                                   -- P2-M5: scheduled due date
    paid_at             TIMESTAMPTZ,                            -- P2-M5: timestamp installment was deducted/paid
    status              VARCHAR(12)   NOT NULL DEFAULT 'PENDING',
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    updated_by          BIGINT,
    CONSTRAINT uq_employee_loan_installment_uid UNIQUE (uid),
    CONSTRAINT uq_employee_loan_installment_no  UNIQUE (employee_loan_id, installment_no),
    CONSTRAINT chk_employee_loan_installment_status CHECK (status IN ('PENDING','DEDUCTED','CANCELLED')),
    CONSTRAINT fk_employee_loan_installment_company FOREIGN KEY (company_id)      REFERENCES companies(id),
    CONSTRAINT fk_employee_loan_installment_loan    FOREIGN KEY (employee_loan_id) REFERENCES employee_loans(id)
);

CREATE INDEX ix_employee_loan_installments_loan ON employee_loan_installments (employee_loan_id);

-- ============================================================================
-- Seed default leave types per existing company (#12-safe uids)
-- uid: 'LT' || lpad(company_id,6,'0') || substr(md5(code),1,12) = 2+6+12=20 chars (<=26)
-- ============================================================================
INSERT INTO leave_types (uid, company_id, code, name, is_paid, annual_entitlement_days, accrual_method, active, version, created_at)
SELECT 'LT' || lpad(c.id::text, 6, '0') || substr(md5(t.code), 1, 12),
       c.id, t.code, t.name, t.is_paid, t.days, t.accrual, true, 0, now()
FROM companies c
CROSS JOIN (VALUES
    ('ANNUAL',      'Annual Leave',       true,  28.0, 'ANNUAL_GRANT'),
    ('SICK',        'Sick Leave',         true,  10.0, 'ANNUAL_GRANT'),
    ('MATERNITY',   'Maternity Leave',    true,  84.0, 'ANNUAL_GRANT'),
    ('PATERNITY',   'Paternity Leave',    true,   3.0, 'ANNUAL_GRANT'),
    ('COMPASSION',  'Compassionate Leave',true,   3.0, 'ANNUAL_GRANT'),
    ('UNPAID',      'Unpaid Leave',       false,  0.0, 'ANNUAL_GRANT')
) AS t(code, name, is_paid, days, accrual)
ON CONFLICT (company_id, code) DO NOTHING;
