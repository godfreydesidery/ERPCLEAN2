-- V46 — Fixed Assets GL config (ADR-0030, D-11/D-13):
-- (1) CoA seed: 1600/1650/1700/3200/4200/5500 per existing company
-- (2) chk_gl_config_key widen: add 6 FA keys (FULL union)
-- (3) gl_configs seed: 6 key→account mappings per existing company (#12-safe uids)
-- (4) chk_journal_batch_source_type + chk_journal_entry_source_type widen: add FA tokens
-- Additive only. V1–V45 are FROZEN. No edit to any prior migration.
-- uid pattern: 'FA'||lpad(company_id,6,'0')||account_code = 2+6+4=12 chars (<=26)
-- config uid:  'FAC'||lpad(company_id,6,'0')||substr(md5(key),1,12) = 3+6+12=21 chars (<=26)

-- ============================================================================
-- (1) CoA seed — 6 FA accounts per company
-- ============================================================================
INSERT INTO chart_of_accounts (
    uid, company_id, account_code, name, account_type, normal_balance,
    is_active, status, version, created_at
)
SELECT
    'FA' || lpad(c.id::text, 6, '0') || '1600' AS uid,
    c.id, '1600', 'Fixed Assets (at cost)', 'ASSET', 'DEBIT',
    true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (
    uid, company_id, account_code, name, account_type, normal_balance,
    is_active, status, version, created_at
)
SELECT
    'FA' || lpad(c.id::text, 6, '0') || '1650' AS uid,
    c.id, '1650', 'Fixed Asset Acquisition/Disposal Clearing', 'ASSET', 'DEBIT',
    true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (
    uid, company_id, account_code, name, account_type, normal_balance,
    is_active, status, version, created_at
)
SELECT
    'FA' || lpad(c.id::text, 6, '0') || '1700' AS uid,
    c.id, '1700', 'Accumulated Depreciation', 'ASSET', 'CREDIT',
    true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (
    uid, company_id, account_code, name, account_type, normal_balance,
    is_active, status, version, created_at
)
SELECT
    'FA' || lpad(c.id::text, 6, '0') || '3200' AS uid,
    c.id, '3200', 'Asset Revaluation Reserve', 'EQUITY', 'CREDIT',
    true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (
    uid, company_id, account_code, name, account_type, normal_balance,
    is_active, status, version, created_at
)
SELECT
    'FA' || lpad(c.id::text, 6, '0') || '4200' AS uid,
    c.id, '4200', 'Gain / (Loss) on Asset Disposal', 'INCOME', 'CREDIT',
    true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (
    uid, company_id, account_code, name, account_type, normal_balance,
    is_active, status, version, created_at
)
SELECT
    'FA' || lpad(c.id::text, 6, '0') || '5500' AS uid,
    c.id, '5500', 'Depreciation Expense', 'EXPENSE', 'DEBIT',
    true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

-- ============================================================================
-- (2) chk_gl_config_key widen — FULL union of all prior keys + 6 FA keys
-- Superset includes: V17 base + procurement-depth LANDED_COST_CLEARING (V34/V36)
-- + sales-depth POS_CASH_OVER/SHORT (V43) + FA keys (ADR-0030)
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
            'POS_CASH_OVER','POS_CASH_SHORT',
            -- fixed-assets (ADR-0030) ---
            'FIXED_ASSETS','FIXED_ASSET_CLEARING','ACCUMULATED_DEPRECIATION',
            'DEPRECIATION_EXPENSE','GAIN_LOSS_ON_DISPOSAL','REVALUATION_RESERVE'
        ));

-- ============================================================================
-- (3) gl_configs seed — 6 key→account per existing company (#12-safe uids)
-- uid: 'FAC'||lpad(company_id,6,'0')||substr(md5(config_key),1,12) = 21 chars (<=26)
-- ============================================================================
INSERT INTO gl_configs (uid, company_id, config_key, account_id, version, created_at)
SELECT
    'FAC' || lpad(coa.company_id::text, 6, '0') || substr(md5(m.config_key), 1, 12) AS uid,
    coa.company_id,
    m.config_key,
    coa.id,
    0,
    now()
FROM (VALUES
    ('FIXED_ASSETS',              '1600'),
    ('FIXED_ASSET_CLEARING',      '1650'),
    ('ACCUMULATED_DEPRECIATION',  '1700'),
    ('REVALUATION_RESERVE',       '3200'),
    ('GAIN_LOSS_ON_DISPOSAL',     '4200'),
    ('DEPRECIATION_EXPENSE',      '5500')
) AS m(config_key, account_code)
JOIN chart_of_accounts coa ON coa.account_code = m.account_code
ON CONFLICT (company_id, config_key) DO NOTHING;

-- ============================================================================
-- (4) journal source-type CHECK widens
-- Add FA_ACQUISITION, DEPRECIATION (admit the reserved token), FA_DISPOSAL, FA_REVALUATION.
-- Full union of V17 tokens + new tokens; V10–V17 DDL untouched.
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
            -- fixed-assets (ADR-0030) ---
            'FA_ACQUISITION','DEPRECIATION','FA_DISPOSAL','FA_REVALUATION'
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
            -- fixed-assets (ADR-0030) ---
            'FA_ACQUISITION','DEPRECIATION','FA_DISPOSAL','FA_REVALUATION'
        ));
