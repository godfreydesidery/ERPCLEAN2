-- V66 — HR: employee contact/payee profile, payroll line payee snapshot, next-of-kin (ADR-0040 D-11)
-- Tables modified: employees, payroll_lines
-- Table created:   employee_next_of_kin
-- Additive only. V1–V65 FROZEN.

-- ============================================================================
-- employees: contact + payee columns
-- ============================================================================
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS phone             VARCHAR(40),
    ADD COLUMN IF NOT EXISTS email             VARCHAR(160),
    ADD COLUMN IF NOT EXISTS address_line      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS region            VARCHAR(120),
    ADD COLUMN IF NOT EXISTS district          VARCHAR(120),
    ADD COLUMN IF NOT EXISTS postal_address    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS payment_method    VARCHAR(20),
    ADD COLUMN IF NOT EXISTS bank_name         VARCHAR(120),
    ADD COLUMN IF NOT EXISTS bank_branch       VARCHAR(120),
    ADD COLUMN IF NOT EXISTS bank_account_no   VARCHAR(60),
    ADD COLUMN IF NOT EXISTS bank_account_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS mobile_money_no   VARCHAR(60);

ALTER TABLE employees
    ADD CONSTRAINT chk_employee_payment_method
        CHECK (payment_method IS NULL
            OR payment_method IN ('BANK_TRANSFER','MOBILE_MONEY','CASH','CHEQUE'));

-- ============================================================================
-- payroll_lines: payee snapshot columns (set at calculate time)
-- ============================================================================
ALTER TABLE payroll_lines
    ADD COLUMN IF NOT EXISTS payee_method       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS payee_account_ref  VARCHAR(60),
    ADD COLUMN IF NOT EXISTS payee_bank_name    VARCHAR(120),
    ADD COLUMN IF NOT EXISTS payee_account_name VARCHAR(120);

-- ============================================================================
-- employee_next_of_kin (owned child of Employee, partial-unique on is_primary)
-- ============================================================================
CREATE TABLE employee_next_of_kin (
    id           BIGSERIAL     PRIMARY KEY,
    uid          VARCHAR(26)   NOT NULL,
    company_id   BIGINT        NOT NULL,
    employee_id  BIGINT        NOT NULL,
    name         VARCHAR(120)  NOT NULL,
    relationship VARCHAR(60),
    phone        VARCHAR(40),
    email        VARCHAR(160),
    is_primary   BOOLEAN       NOT NULL DEFAULT false,
    status       VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
    version      BIGINT        NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   BIGINT,
    updated_at   TIMESTAMPTZ,
    updated_by   BIGINT,
    CONSTRAINT uq_employee_next_of_kin_uid  UNIQUE (uid),
    CONSTRAINT fk_employee_next_of_kin_employee
        FOREIGN KEY (employee_id) REFERENCES employees(id),
    CONSTRAINT fk_employee_next_of_kin_company
        FOREIGN KEY (company_id) REFERENCES companies(id)
);

-- at most one primary NOK per employee
CREATE UNIQUE INDEX uq_employee_next_of_kin_primary
    ON employee_next_of_kin (employee_id)
    WHERE is_primary;

CREATE INDEX ix_employee_next_of_kin_employee
    ON employee_next_of_kin (employee_id);
