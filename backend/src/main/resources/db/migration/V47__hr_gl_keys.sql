-- V56 — HR & Payroll: GL key widen + journal source-type widen (ADR-0032 D-8)
-- (1) Widen chk_gl_config_key: full union of ALL prior keys + 9 payroll keys.
-- (2) Widen chk_journal_batch_source_type + chk_journal_entry_source_type: add PAYROLL.
-- Additive only. V1–V30 are FROZEN.
-- SUPERSET NOTE: V56 runs after V46 (FA keys), V43 (POS keys), V36 (procurement keys).
-- This union MUST include every key from every earlier DROP/ADD or existing rows violate it.

-- ============================================================================
-- (1) gl_configs CHECK widen — full union of ALL prior keys + HR payroll keys
-- Prior keys through V46: V17 base + LANDED_COST_CLEARING (V34/V36)
--   + POS_CASH_OVER/SHORT (V43) + FA keys (V46)
-- New HR payroll keys (ADR-0032 D-8):
--   SALARY_EXPENSE, EMPLOYER_STATUTORY_EXPENSE,
--   PAYE_PAYABLE, NSSF_PAYABLE, WCF_PAYABLE, SDL_PAYABLE, HESLB_PAYABLE,
--   NET_WAGES_PAYABLE, EMPLOYEE_LOAN_RECEIVABLE
-- ============================================================================
ALTER TABLE gl_configs
    DROP CONSTRAINT IF EXISTS chk_gl_config_key;

ALTER TABLE gl_configs
    ADD CONSTRAINT chk_gl_config_key CHECK (
        config_key IN (
            -- V10..V17 (existing base)
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
            -- HR payroll (ADR-0032 D-8)
            'SALARY_EXPENSE','EMPLOYER_STATUTORY_EXPENSE',
            'PAYE_PAYABLE','NSSF_PAYABLE','WCF_PAYABLE','SDL_PAYABLE','HESLB_PAYABLE',
            'NET_WAGES_PAYABLE','EMPLOYEE_LOAN_RECEIVABLE'
        ));

-- ============================================================================
-- (2) journal source-type CHECK widen — add PAYROLL
-- Full union: V17 base + procurement tokens (V36) + POS_VARIANCE (V43)
--   + FA tokens (V46) + PAYROLL (ADR-0032 D-8).
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
            'PAYROLL'
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
            -- procurement-depth (ADR-0027)
            'LANDED_COST','PURCHASE_RETURN',
            -- sales-depth (ADR-0029)
            'POS_VARIANCE',
            -- fixed-assets (ADR-0030)
            'FA_ACQUISITION','DEPRECIATION','FA_DISPOSAL','FA_REVALUATION',
            -- HR payroll (ADR-0032)
            'PAYROLL'
        ));
