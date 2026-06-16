-- V79 — FX/Multi-currency: FX CoA accounts + gl_config keys + source types + permissions
--       (ADR-0036 D-5/D-10, Tranche 2)
--
-- (1) chk_gl_config_key widen — FULL union (V74 superset) + 4 new FX keys
-- (2) chk_journal_batch_source_type + chk_journal_entry_source_type widen — FULL union + FX_REVALUATION
-- (3) SEED CoA accounts: 4910 UNREALIZED_FX_GAIN(INCOME), 4920 REALIZED_FX_GAIN(INCOME),
--                         5190 REALIZED_FX_LOSS(EXPENSE), 5191 UNREALIZED_FX_LOSS(EXPENSE)
--     (confirmed free codes per OQ-FX-02)
-- (4) SEED gl_configs mappings per existing company
--
-- uid patterns (#12-safe, ISSUES-REGISTER #12):
--   CoA accounts : 'FX' || lpad(c.id::text,6,'0') || account_code  = 2+6+4 = 12 chars (≤26)
--   gl_configs   : 'FXC' || lpad(company_id::text,6,'0') || substr(md5(config_key),1,12) = 21 chars
--
-- Additive only. V1–V78 are FROZEN. No edit to any prior migration.

-- ============================================================================
-- (1) chk_gl_config_key widen
-- FULL union of ALL prior keys (V74 manufacturing superset) + 4 new FX keys:
--   REALIZED_FX_GAIN, REALIZED_FX_LOSS, UNREALIZED_FX_GAIN, UNREALIZED_FX_LOSS
-- ============================================================================
ALTER TABLE gl_configs
    DROP CONSTRAINT IF EXISTS chk_gl_config_key;

ALTER TABLE gl_configs
    ADD CONSTRAINT chk_gl_config_key CHECK (
        config_key IN (
            -- V10..V17 (base)
            'SALES_REVENUE','VAT_PAYABLE','ACCOUNTS_RECEIVABLE','CASH',
            'INVENTORY','COGS','ACCOUNTS_PAYABLE',
            'BAD_DEBT_EXPENSE','OPENING_BALANCE_EQUITY',
            'PURCHASES',
            'VAT_INPUT','VAT_DUE','WHT_PAYABLE','WHT_RECEIVABLE',
            'RETAINED_EARNINGS',
            'GRNI','STOCK_ADJUSTMENT',
            -- procurement-depth (ADR-0027, V34/V36)
            'LANDED_COST_CLEARING',
            -- sales-depth (ADR-0029, V43)
            'POS_CASH_OVER','POS_CASH_SHORT',
            -- fixed-assets (ADR-0030, V46)
            'FIXED_ASSETS','FIXED_ASSET_CLEARING','ACCUMULATED_DEPRECIATION',
            'DEPRECIATION_EXPENSE','GAIN_LOSS_ON_DISPOSAL','REVALUATION_RESERVE',
            -- HR payroll (ADR-0032, V56)
            'SALARY_EXPENSE','EMPLOYER_STATUTORY_EXPENSE',
            'PAYE_PAYABLE','NSSF_PAYABLE','WCF_PAYABLE','SDL_PAYABLE','HESLB_PAYABLE',
            'NET_WAGES_PAYABLE','EMPLOYEE_LOAN_RECEIVABLE',
            -- manufacturing (ADR-0035, V74)
            'WIP_INVENTORY','FINISHED_GOODS','LABOUR_APPLIED','OVERHEAD_APPLIED',
            'MANUFACTURING_VARIANCE',
            -- FX/multi-currency (ADR-0036, this migration)
            'REALIZED_FX_GAIN','REALIZED_FX_LOSS',
            'UNREALIZED_FX_GAIN','UNREALIZED_FX_LOSS'
        )
    );

-- ============================================================================
-- (2a) chk_journal_batch_source_type widen
-- FULL union (V74 manufacturing superset) + FX_REVALUATION
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
            -- procurement-depth (ADR-0027)
            'LANDED_COST','PURCHASE_RETURN',
            -- sales-depth (ADR-0029)
            'POS_VARIANCE',
            -- fixed-assets (ADR-0030)
            'FA_ACQUISITION','DEPRECIATION','FA_DISPOSAL','FA_REVALUATION',
            -- HR payroll (ADR-0032)
            'PAYROLL',
            -- manufacturing (ADR-0035)
            'PRODUCTION_ISSUE','PRODUCTION_RECEIPT','PRODUCTION_LABOUR','PRODUCTION_VARIANCE',
            -- FX/multi-currency (ADR-0036, this migration)
            'FX_REVALUATION'
        )
    );

-- ============================================================================
-- (2b) chk_journal_entry_source_type widen
-- FULL union (V74 manufacturing superset) + FX_REVALUATION
-- ============================================================================
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
            -- procurement-depth (ADR-0027)
            'LANDED_COST','PURCHASE_RETURN',
            -- sales-depth (ADR-0029)
            'POS_VARIANCE',
            -- fixed-assets (ADR-0030)
            'FA_ACQUISITION','DEPRECIATION','FA_DISPOSAL','FA_REVALUATION',
            -- HR payroll (ADR-0032)
            'PAYROLL',
            -- manufacturing (ADR-0035)
            'PRODUCTION_ISSUE','PRODUCTION_RECEIPT','PRODUCTION_LABOUR','PRODUCTION_VARIANCE',
            -- FX/multi-currency (ADR-0036, this migration)
            'FX_REVALUATION'
        )
    );

-- ============================================================================
-- (3) SEED CoA accounts per existing company
--   4910 — Unrealized FX Gain  (INCOME / CREDIT normal)
--   4920 — Realized FX Gain    (INCOME / CREDIT normal)
--   5190 — Realized FX Loss    (EXPENSE / DEBIT normal)
--   5191 — Unrealized FX Loss  (EXPENSE / DEBIT normal)
--
-- uid: 'FX' || lpad(c.id::text,6,'0') || account_code  (2+6+4 = 12 chars ≤ 26, #12-safe)
-- Note: the ADR specifies 4910=UNREALIZED_FX_GAIN, 4920=REALIZED_FX_GAIN per OQ-FX-02 note
--       (both INCOME); 5190=REALIZED_FX_LOSS, 5191=UNREALIZED_FX_LOSS (both EXPENSE).
-- ============================================================================

-- 4910 Unrealized FX Gain
INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance, is_active, created_at)
SELECT
    'FX' || lpad(c.id::text, 6, '0') || '4910',
    c.id,
    '4910',
    'Unrealized FX Gain',
    'INCOME',
    'CREDIT',
    true,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

-- 4920 Realized FX Gain
INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance, is_active, created_at)
SELECT
    'FX' || lpad(c.id::text, 6, '0') || '4920',
    c.id,
    '4920',
    'Realized FX Gain',
    'INCOME',
    'CREDIT',
    true,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

-- 5190 Realized FX Loss
INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance, is_active, created_at)
SELECT
    'FX' || lpad(c.id::text, 6, '0') || '5190',
    c.id,
    '5190',
    'Realized FX Loss',
    'EXPENSE',
    'DEBIT',
    true,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

-- 5191 Unrealized FX Loss
INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance, is_active, created_at)
SELECT
    'FX' || lpad(c.id::text, 6, '0') || '5191',
    c.id,
    '5191',
    'Unrealized FX Loss',
    'EXPENSE',
    'DEBIT',
    true,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

-- ============================================================================
-- (4) SEED gl_configs mappings per existing company
-- uid: 'FXC' || lpad(company_id::text,6,'0') || substr(md5(config_key),1,12) = 21 chars (#12-safe)
-- ON CONFLICT (company_id, config_key) DO NOTHING — idempotent
-- ============================================================================

-- UNREALIZED_FX_GAIN → 4910
INSERT INTO gl_configs (uid, company_id, config_key, account_id, created_at)
SELECT
    'FXC' || lpad(coa.company_id::text, 6, '0') || substr(md5('UNREALIZED_FX_GAIN'), 1, 12),
    coa.company_id,
    'UNREALIZED_FX_GAIN',
    coa.id,
    now()
FROM chart_of_accounts coa
WHERE coa.account_code = '4910'
ON CONFLICT (company_id, config_key) DO NOTHING;

-- REALIZED_FX_GAIN → 4920
INSERT INTO gl_configs (uid, company_id, config_key, account_id, created_at)
SELECT
    'FXC' || lpad(coa.company_id::text, 6, '0') || substr(md5('REALIZED_FX_GAIN'), 1, 12),
    coa.company_id,
    'REALIZED_FX_GAIN',
    coa.id,
    now()
FROM chart_of_accounts coa
WHERE coa.account_code = '4920'
ON CONFLICT (company_id, config_key) DO NOTHING;

-- REALIZED_FX_LOSS → 5190
INSERT INTO gl_configs (uid, company_id, config_key, account_id, created_at)
SELECT
    'FXC' || lpad(coa.company_id::text, 6, '0') || substr(md5('REALIZED_FX_LOSS'), 1, 12),
    coa.company_id,
    'REALIZED_FX_LOSS',
    coa.id,
    now()
FROM chart_of_accounts coa
WHERE coa.account_code = '5190'
ON CONFLICT (company_id, config_key) DO NOTHING;

-- UNREALIZED_FX_LOSS → 5191
INSERT INTO gl_configs (uid, company_id, config_key, account_id, created_at)
SELECT
    'FXC' || lpad(coa.company_id::text, 6, '0') || substr(md5('UNREALIZED_FX_LOSS'), 1, 12),
    coa.company_id,
    'UNREALIZED_FX_LOSS',
    coa.id,
    now()
FROM chart_of_accounts coa
WHERE coa.account_code = '5191'
ON CONFLICT (company_id, config_key) DO NOTHING;
