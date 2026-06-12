-- V43 — Sales Depth: Point-of-Sale (ADR-0029, Stage 1b — POS)
-- pos_tills, pos_sessions, pos_session_payouts;
-- ALTER sales_invoices ADD pos_session_id + widen chk_sales_invoice_origin (admit POS);
-- CoA seed: 4900 Cash Over + 5170 Cash Short per company;
-- gl_configs CHECK widen: add POS_CASH_OVER + POS_CASH_SHORT;
-- gl_configs seed per existing company;
-- journal source-type CHECK widen: add POS_VARIANCE;
-- permission seed + ORG_ADMIN grant.
-- Additive only. V1–V42 (excl. in-flight V20–V41) are FROZEN.

-- ============================================================================
-- (1) pos_tills — the physical till / register master
-- ============================================================================
CREATE TABLE pos_tills (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    company_id           BIGINT          NOT NULL,
    branch_id            BIGINT          NOT NULL,
    code                 VARCHAR(30)     NOT NULL,
    name                 VARCHAR(120)    NOT NULL,
    cash_bank_account_id BIGINT          NOT NULL,
    status               VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_pos_till_uid             UNIQUE (uid),
    CONSTRAINT uq_pos_till_company_code    UNIQUE (company_id, code),
    CONSTRAINT fk_pos_till_company         FOREIGN KEY (company_id)           REFERENCES companies(id),
    CONSTRAINT fk_pos_till_branch          FOREIGN KEY (branch_id)            REFERENCES branches(id),
    CONSTRAINT fk_pos_till_cashaccount     FOREIGN KEY (cash_bank_account_id) REFERENCES cash_bank_accounts(id),
    CONSTRAINT chk_pos_till_status         CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);

CREATE INDEX ix_pos_tills_company_id ON pos_tills (company_id);
CREATE INDEX ix_pos_tills_branch_id  ON pos_tills (branch_id);

-- ============================================================================
-- (2) pos_sessions — cashier session header
-- ============================================================================
CREATE TABLE pos_sessions (
    id                       BIGSERIAL PRIMARY KEY,
    uid                      VARCHAR(26)     NOT NULL,
    company_id               BIGINT          NOT NULL,
    branch_id                BIGINT          NOT NULL,
    session_number           VARCHAR(30)     NOT NULL,
    pos_till_id              BIGINT          NOT NULL,
    status                   VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    cashier_id               BIGINT          NOT NULL,
    opening_float_amount     NUMERIC(19,4)   NOT NULL,
    opened_at                TIMESTAMPTZ     NOT NULL DEFAULT now(),
    closed_at                TIMESTAMPTZ,
    reconciled_at            TIMESTAMPTZ,
    counted_cash_amount      NUMERIC(19,4),
    counted_mobile_amount    NUMERIC(19,4),
    expected_cash_amount     NUMERIC(19,4),
    variance_amount          NUMERIC(19,4),
    variance_gl_entry_uid    VARCHAR(26),
    variance_journal_id      BIGINT,
    reconciled_by            BIGINT,
    notes                    VARCHAR(500),
    version                  BIGINT          NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by               BIGINT,
    updated_at               TIMESTAMPTZ,
    updated_by               BIGINT,
    CONSTRAINT uq_pos_session_uid             UNIQUE (uid),
    CONSTRAINT uq_pos_session_company_number  UNIQUE (company_id, session_number),
    CONSTRAINT fk_pos_session_company         FOREIGN KEY (company_id)  REFERENCES companies(id),
    CONSTRAINT fk_pos_session_branch          FOREIGN KEY (branch_id)   REFERENCES branches(id),
    CONSTRAINT fk_pos_session_till            FOREIGN KEY (pos_till_id) REFERENCES pos_tills(id),
    CONSTRAINT fk_pos_session_cashier         FOREIGN KEY (cashier_id)  REFERENCES app_users(id),
    CONSTRAINT fk_pos_session_reconciled_by    FOREIGN KEY (reconciled_by)       REFERENCES app_users(id),
    CONSTRAINT fk_pos_session_variance_journal FOREIGN KEY (variance_journal_id) REFERENCES journal_entries(id),
    CONSTRAINT chk_pos_session_status          CHECK (status IN ('OPEN','CLOSED','RECONCILED')),
    CONSTRAINT chk_pos_session_float          CHECK (opening_float_amount >= 0),
    CONSTRAINT chk_pos_session_counted_cash   CHECK (counted_cash_amount IS NULL OR counted_cash_amount >= 0),
    CONSTRAINT chk_pos_session_counted_mobile CHECK (counted_mobile_amount IS NULL OR counted_mobile_amount >= 0)
);

-- One-open-session-per-till invariant (BR-SD-02): partial unique index (Postgres)
CREATE UNIQUE INDEX ux_pos_session_one_open ON pos_sessions (pos_till_id) WHERE status = 'OPEN';

CREATE INDEX ix_pos_sessions_company_id  ON pos_sessions (company_id);
CREATE INDEX ix_pos_sessions_branch_id   ON pos_sessions (branch_id);
CREATE INDEX ix_pos_sessions_till_id     ON pos_sessions (pos_till_id);

-- ============================================================================
-- (3) pos_session_payouts — cash leaving the drawer (refunds + misc)
-- ============================================================================
CREATE TABLE pos_session_payouts (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    pos_session_id       BIGINT          NOT NULL,
    company_id           BIGINT          NOT NULL,
    branch_id            BIGINT          NOT NULL,
    payout_type          VARCHAR(20)     NOT NULL,
    amount               NUMERIC(19,4)   NOT NULL,
    source_invoice_uid   VARCHAR(26),
    reason               VARCHAR(255),
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_pos_session_payout_uid     UNIQUE (uid),
    CONSTRAINT fk_pos_session_payout_session FOREIGN KEY (pos_session_id) REFERENCES pos_sessions(id),
    CONSTRAINT chk_pos_session_payout_type   CHECK (payout_type IN ('CASH_IN','CASH_OUT')),
    CONSTRAINT chk_pos_session_payout_amount CHECK (amount > 0)
);

CREATE INDEX ix_pos_session_payouts_session_id ON pos_session_payouts (pos_session_id);

-- ============================================================================
-- (4) ALTER sales_invoices — add pos_session_id nullable FK
-- ============================================================================
ALTER TABLE sales_invoices
    ADD COLUMN pos_session_id BIGINT;

ALTER TABLE sales_invoices
    ADD CONSTRAINT fk_sales_invoice_pos_session
        FOREIGN KEY (pos_session_id) REFERENCES pos_sessions(id);

-- ============================================================================
-- (5) Widen chk_sales_invoice_origin — admit 'POS' (keep DIRECT, SALES_ORDER)
-- Union of V18 origins + POS. Additive DROP/ADD pattern (V18 precendent).
-- ============================================================================
ALTER TABLE sales_invoices
    DROP CONSTRAINT IF EXISTS chk_sales_invoice_origin;

ALTER TABLE sales_invoices
    ADD CONSTRAINT chk_sales_invoice_origin
        CHECK (origin IN ('DIRECT','SALES_ORDER','POS'));

-- ============================================================================
-- (6) CoA seed per existing company: 4900 Cash Over + 5170 Cash Short
-- uid: 'POS' || lpad(company_id,6,'0') || account_code  = 3+6+4 = 13 chars (<=26)
-- ON CONFLICT (company_id, account_code) DO NOTHING — idempotent.
-- ============================================================================
INSERT INTO chart_of_accounts (
    uid, company_id, account_code, name, account_type, normal_balance,
    is_active, status, version, created_at
)
SELECT
    'POS' || lpad(c.id::text, 6, '0') || '4900' AS uid,
    c.id,
    '4900',
    'Cash Over (Till Surplus)',
    'INCOME',
    'CREDIT',
    true,
    'ACTIVE',
    0,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (
    uid, company_id, account_code, name, account_type, normal_balance,
    is_active, status, version, created_at
)
SELECT
    'POS' || lpad(c.id::text, 6, '0') || '5170' AS uid,
    c.id,
    '5170',
    'Cash Short / Till Shortage',
    'EXPENSE',
    'DEBIT',
    true,
    'ACTIVE',
    0,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

-- ============================================================================
-- (7) gl_configs CHECK widen — add POS_CASH_OVER + POS_CASH_SHORT
-- FULL union: V17 base + procurement-depth LANDED_COST_CLEARING (V34) + POS keys.
-- Must be superset of V36 constraint so existing LANDED_COST_CLEARING rows are valid.
-- ============================================================================
ALTER TABLE gl_configs
    DROP CONSTRAINT IF EXISTS chk_gl_config_key;

ALTER TABLE gl_configs
    ADD CONSTRAINT chk_gl_config_key CHECK (
        config_key IN (
            'SALES_REVENUE','VAT_PAYABLE','ACCOUNTS_RECEIVABLE','CASH',
            'INVENTORY','COGS','ACCOUNTS_PAYABLE',
            'BAD_DEBT_EXPENSE','OPENING_BALANCE_EQUITY',
            'PURCHASES',
            'VAT_INPUT','VAT_DUE','WHT_PAYABLE','WHT_RECEIVABLE',
            'RETAINED_EARNINGS',
            'GRNI','STOCK_ADJUSTMENT',
            -- procurement-depth (ADR-0027) ---
            'LANDED_COST_CLEARING',
            -- sales-depth (ADR-0029) ---
            'POS_CASH_OVER','POS_CASH_SHORT'
        ));

-- ============================================================================
-- (8) gl_configs seed per existing company: POS_CASH_OVER→4900 + POS_CASH_SHORT→5170
-- CRITICAL #12-safe seed-uid: 'POC' || lpad(company_id,6,'0') || substr(md5(key),1,12)
--   = 3+6+12 = 21 chars (<=26). NEVER || config_key directly.
-- ON CONFLICT (company_id, config_key) DO NOTHING — idempotent.
-- ============================================================================
INSERT INTO gl_configs (uid, company_id, config_key, account_id, version, created_at)
SELECT
    'POC' || lpad(coa.company_id::text, 6, '0') || substr(md5(m.config_key), 1, 12) AS uid,
    coa.company_id,
    m.config_key,
    coa.id,
    0,
    now()
FROM (VALUES
    ('POS_CASH_OVER',  '4900'),
    ('POS_CASH_SHORT', '5170')
) AS m(config_key, account_code)
JOIN chart_of_accounts coa ON coa.account_code = m.account_code
ON CONFLICT (company_id, config_key) DO NOTHING;

-- ============================================================================
-- (9) journal source-type CHECK widen — add POS_VARIANCE
-- FULL union: V17 base + procurement-depth tokens (V36) + POS_VARIANCE.
-- Superset of V36 so existing LANDED_COST/PURCHASE_RETURN rows remain valid.
-- ============================================================================
ALTER TABLE journal_batches
    DROP CONSTRAINT IF EXISTS chk_journal_batch_source_type;

ALTER TABLE journal_batches
    ADD CONSTRAINT chk_journal_batch_source_type CHECK (
        source_type IN (
            'MANUAL','SALES','SALES_REVERSAL','OPENING_BALANCE',
            'AR_RECEIPT','AR_WRITEOFF','AR_CREDIT_NOTE',
            'AP_BILL','AP_PAYMENT','AP_DEBIT_NOTE',
            'CASH_TRANSFER','CASH_DIRECT',
            'VAT_RETURN',
            'YEAR_END_CLOSE',
            'STOCK_RECEIPT','COGS','STOCK_ADJUSTMENT','OPENING_INVENTORY',
            -- procurement-depth (ADR-0027) ---
            'LANDED_COST','PURCHASE_RETURN',
            -- sales-depth (ADR-0029) ---
            'POS_VARIANCE'
        ));

ALTER TABLE journal_entries
    DROP CONSTRAINT IF EXISTS chk_journal_entry_source_type;

ALTER TABLE journal_entries
    ADD CONSTRAINT chk_journal_entry_source_type CHECK (
        source_type IN (
            'MANUAL','SALES','SALES_REVERSAL','OPENING_BALANCE',
            'AR_RECEIPT','AR_WRITEOFF','AR_CREDIT_NOTE',
            'AP_BILL','AP_PAYMENT','AP_DEBIT_NOTE',
            'CASH_TRANSFER','CASH_DIRECT',
            'VAT_RETURN',
            'YEAR_END_CLOSE',
            'STOCK_RECEIPT','COGS','STOCK_ADJUSTMENT','OPENING_INVENTORY',
            -- procurement-depth (ADR-0027) ---
            'LANDED_COST','PURCHASE_RETURN',
            -- sales-depth (ADR-0029) ---
            'POS_VARIANCE'
        ));

-- ============================================================================
-- (10) permission seed + ORG_ADMIN grant
-- ============================================================================
INSERT INTO permissions (code, module, description) VALUES
    ('SALES.POS.TILL.MANAGE',        'sales', 'Create and manage POS tills (registers)'),
    ('SALES.POS.SESSION.OPEN',       'sales', 'Open a POS cashier session on a till'),
    ('SALES.POS.SESSION.CLOSE',      'sales', 'Close an open POS session, declaring counted cash'),
    ('SALES.POS.SESSION.RECONCILE',  'sales', 'Reconcile a closed POS session (posts variance to GL)'),
    ('SALES.POS.SELL',               'sales', 'Ring a POS sale on an open session'),
    ('SALES.POS.REFUND',             'sales', 'Process a POS refund (void / return + cash payout)'),
    ('SALES.POS.VIEW',               'sales', 'View POS tills, sessions, X/Z reads, and session history')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.code IN (
      'SALES.POS.TILL.MANAGE',
      'SALES.POS.SESSION.OPEN',
      'SALES.POS.SESSION.CLOSE',
      'SALES.POS.SESSION.RECONCILE',
      'SALES.POS.SELL',
      'SALES.POS.REFUND',
      'SALES.POS.VIEW'
  )
ON CONFLICT DO NOTHING;
