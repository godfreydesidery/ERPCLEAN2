-- V58 — HR & Payroll: employee master tables (ADR-0032 D-4)
-- Creates: departments, employees, employment_contracts
-- Additive only. V1–V57 FROZEN.

-- ============================================================================
-- departments
-- ============================================================================
CREATE TABLE departments (
    id          BIGSERIAL PRIMARY KEY,
    uid         VARCHAR(26)  NOT NULL,
    company_id  BIGINT       NOT NULL,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    updated_at  TIMESTAMPTZ,
    updated_by  BIGINT,
    CONSTRAINT uq_department_uid         UNIQUE (uid),
    CONSTRAINT uq_department_company_code UNIQUE (company_id, code),
    CONSTRAINT fk_department_company     FOREIGN KEY (company_id) REFERENCES companies(id)
);

CREATE INDEX ix_departments_company ON departments (company_id);

-- ============================================================================
-- employees
-- ============================================================================
CREATE TABLE employees (
    id               BIGSERIAL    PRIMARY KEY,
    uid              VARCHAR(26)  NOT NULL,
    company_id       BIGINT       NOT NULL,
    branch_id        BIGINT,
    employee_number  VARCHAR(30)  NOT NULL,
    first_name       VARCHAR(80)  NOT NULL,
    last_name        VARCHAR(80)  NOT NULL,
    national_id      VARCHAR(40),
    tin              VARCHAR(20),
    nssf_number      VARCHAR(40),
    heslb_number     VARCHAR(40),
    date_of_birth    DATE,
    gender           VARCHAR(10),
    hire_date        DATE         NOT NULL,
    department_id    BIGINT,
    job_title        VARCHAR(120),
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    user_id          BIGINT,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    updated_by       BIGINT,
    CONSTRAINT uq_employee_uid            UNIQUE (uid),
    CONSTRAINT uq_employee_company_number UNIQUE (company_id, employee_number),
    CONSTRAINT uq_employee_user           UNIQUE (company_id, user_id),
    CONSTRAINT chk_employee_status        CHECK  (status IN ('ACTIVE','ON_LEAVE','SUSPENDED','TERMINATED')),
    CONSTRAINT fk_employee_company        FOREIGN KEY (company_id)   REFERENCES companies(id),
    CONSTRAINT fk_employee_branch         FOREIGN KEY (branch_id)    REFERENCES branches(id),
    CONSTRAINT fk_employee_department     FOREIGN KEY (department_id) REFERENCES departments(id),
    CONSTRAINT fk_employee_user           FOREIGN KEY (user_id)      REFERENCES app_users(id)
);

CREATE INDEX ix_employees_company        ON employees (company_id);
CREATE INDEX ix_employees_company_status ON employees (company_id, status);
CREATE INDEX ix_employees_user           ON employees (user_id) WHERE user_id IS NOT NULL;

-- ============================================================================
-- employment_contracts
-- ============================================================================
CREATE TABLE employment_contracts (
    id                  BIGSERIAL    PRIMARY KEY,
    uid                 VARCHAR(26)  NOT NULL,
    company_id          BIGINT       NOT NULL,
    employee_id         BIGINT       NOT NULL,
    contract_type       VARCHAR(16)  NOT NULL,
    base_salary_amount  NUMERIC(19,4) NOT NULL,
    currency            VARCHAR(3)   NOT NULL DEFAULT 'TZS',
    pay_frequency       VARCHAR(12)  NOT NULL DEFAULT 'MONTHLY',
    start_date          DATE         NOT NULL,
    end_date            DATE,
    paye_resident       BOOLEAN      NOT NULL DEFAULT true,
    nssf_member         BOOLEAN      NOT NULL DEFAULT true,
    heslb_borrower      BOOLEAN      NOT NULL DEFAULT false,
    wcf_covered         BOOLEAN      NOT NULL DEFAULT true,
    sdl_counted         BOOLEAN      NOT NULL DEFAULT true,
    active              BOOLEAN      NOT NULL DEFAULT true,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    updated_by          BIGINT,
    CONSTRAINT uq_employment_contract_uid    UNIQUE (uid),
    -- at most one active contract per employee
    CONSTRAINT uq_employment_contract_active UNIQUE (company_id, employee_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_employment_contract_type      CHECK (contract_type IN ('PERMANENT','FIXED_TERM','CASUAL','PROBATION')),
    CONSTRAINT chk_employment_contract_frequency CHECK (pay_frequency  IN ('MONTHLY')),
    CONSTRAINT chk_employment_contract_salary    CHECK (base_salary_amount >= 0),
    CONSTRAINT chk_employment_contract_dates     CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT fk_employment_contract_company    FOREIGN KEY (company_id)  REFERENCES companies(id),
    CONSTRAINT fk_employment_contract_employee   FOREIGN KEY (employee_id) REFERENCES employees(id)
);

CREATE INDEX ix_employment_contracts_employee ON employment_contracts (company_id, employee_id);
CREATE UNIQUE INDEX uq_employment_contract_active_partial
    ON employment_contracts (company_id, employee_id)
    WHERE active = true;
