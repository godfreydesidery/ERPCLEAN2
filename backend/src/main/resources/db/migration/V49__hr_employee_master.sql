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
    -- P2 D6 (ADR-0041): org hierarchy + ownership soft-FKs (all NULLABLE)
    parent_department_id BIGINT,   -- self soft-FK (flat-tolerant; no FK constraint to allow forward refs)
    manager_id           BIGINT,   -- soft-FK employees
    cost_centre_value_id BIGINT,   -- soft-FK gl dimension_values
    branch_id            BIGINT,   -- soft-FK branches
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
    middle_name      VARCHAR(80),                 -- P3: optional middle name
    last_name        VARCHAR(80)  NOT NULL,
    photo_ref        VARCHAR(255),                -- P3: documents-module ref to employee photo
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
    -- P2 D6 (ADR-0041): lifecycle + HR profile + org soft-FKs (all NULLABLE)
    termination_date    DATE,
    termination_reason  VARCHAR(255),
    confirmation_date   DATE,
    probation_end_date  DATE,
    manager_id          BIGINT,        -- self soft-FK employees (reporting line)
    marital_status      VARCHAR(20),   -- enum MaritalStatus
    nationality         VARCHAR(80),
    position_id         BIGINT,        -- soft-FK positions (created below)
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    updated_by       BIGINT,
    CONSTRAINT uq_employee_uid            UNIQUE (uid),
    CONSTRAINT uq_employee_company_number UNIQUE (company_id, employee_number),
    CONSTRAINT uq_employee_user           UNIQUE (company_id, user_id),
    CONSTRAINT chk_employee_status        CHECK  (status IN ('ACTIVE','ON_LEAVE','SUSPENDED','TERMINATED')),
    CONSTRAINT chk_employee_marital_status CHECK (marital_status IS NULL
                                                  OR marital_status IN ('SINGLE','MARRIED','DIVORCED','WIDOWED','SEPARATED','OTHER')),
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
    -- P2 D6 (ADR-0041): contract policy fields (all NULLABLE)
    probation_months       INT,
    notice_period_days     INT,
    working_hours_per_day  NUMERIC(5,2),
    working_days_per_week  INT,
    job_grade              VARCHAR(40),
    signed_document_ref    VARCHAR(255),
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

-- ============================================================================
-- positions — minimal company-scoped, FLAT job-position master (P2 D6, ADR-0041)
-- ============================================================================
CREATE TABLE positions (
    id          BIGSERIAL    PRIMARY KEY,
    uid         VARCHAR(26)  NOT NULL,
    company_id  BIGINT       NOT NULL,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(255),
    job_grade   VARCHAR(40),
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    updated_at  TIMESTAMPTZ,
    updated_by  BIGINT,
    CONSTRAINT uq_position_uid          UNIQUE (uid),
    CONSTRAINT uq_position_company_code UNIQUE (company_id, code),
    CONSTRAINT chk_position_status      CHECK  (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
    CONSTRAINT fk_position_company      FOREIGN KEY (company_id) REFERENCES companies(id)
);

CREATE INDEX ix_positions_company ON positions (company_id);
