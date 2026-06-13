-- V59 — HR & Payroll: pay component catalogue + employee recurring items (ADR-0032 D-4)
-- Creates: pay_components, employee_recurring_items
-- Additive only. V1–V58 FROZEN.

-- ============================================================================
-- pay_components (master catalogue)
-- ============================================================================
CREATE TABLE pay_components (
    id              BIGSERIAL    PRIMARY KEY,
    uid             VARCHAR(26)  NOT NULL,
    company_id      BIGINT       NOT NULL,
    code            VARCHAR(30)  NOT NULL,
    name            VARCHAR(120) NOT NULL,
    kind            VARCHAR(10)  NOT NULL,
    basis           VARCHAR(20)  NOT NULL,
    gl_account_id   BIGINT       NOT NULL,
    taxable         BOOLEAN      NOT NULL DEFAULT true,
    pensionable     BOOLEAN      NOT NULL DEFAULT true,
    active          BOOLEAN      NOT NULL DEFAULT true,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,
    CONSTRAINT uq_pay_component_uid          UNIQUE (uid),
    CONSTRAINT uq_pay_component_company_code UNIQUE (company_id, code),
    CONSTRAINT chk_pay_component_kind   CHECK (kind  IN ('EARNING','DEDUCTION')),
    CONSTRAINT chk_pay_component_basis  CHECK (basis IN ('FIXED','PERCENT_OF_BASIC')),
    CONSTRAINT fk_pay_component_company     FOREIGN KEY (company_id)    REFERENCES companies(id),
    CONSTRAINT fk_pay_component_gl_account  FOREIGN KEY (gl_account_id) REFERENCES chart_of_accounts(id)
);

CREATE INDEX ix_pay_components_company ON pay_components (company_id);

-- ============================================================================
-- employee_recurring_items
-- ============================================================================
CREATE TABLE employee_recurring_items (
    id                  BIGSERIAL     PRIMARY KEY,
    uid                 VARCHAR(26)   NOT NULL,
    company_id          BIGINT        NOT NULL,
    employee_id         BIGINT        NOT NULL,
    pay_component_id    BIGINT        NOT NULL,
    amount_or_percent   NUMERIC(19,4) NOT NULL,
    effective_from      DATE          NOT NULL,
    effective_to        DATE,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    updated_by          BIGINT,
    CONSTRAINT uq_employee_recurring_item_uid  UNIQUE (uid),
    CONSTRAINT chk_employee_recurring_item_amt CHECK (amount_or_percent >= 0),
    CONSTRAINT fk_employee_recurring_item_company       FOREIGN KEY (company_id)      REFERENCES companies(id),
    CONSTRAINT fk_employee_recurring_item_employee      FOREIGN KEY (employee_id)     REFERENCES employees(id),
    CONSTRAINT fk_employee_recurring_item_pay_component FOREIGN KEY (pay_component_id) REFERENCES pay_components(id)
);

CREATE INDEX ix_employee_recurring_items_employee ON employee_recurring_items (company_id, employee_id);
