-- V56 — HR & Payroll: GL key widen + journal source-type widen (ADR-0032 D-8)
-- (1) Widen chk_gl_config_key: add 9 payroll keys (full union of all prior keys + new).
-- (2) Widen chk_journal_batch_source_type + chk_journal_entry_source_type: add PAYROLL.
-- Additive only. V1–V30 are FROZEN.

-- ============================================================================
-- (1) gl_configs CHECK widen — full union (V17 + new HR payroll keys)
-- Prior keys (V10..V17): SALES_REVENUE, VAT_PAYABLE, ACCOUNTS_RECEIVABLE, CASH,
--   INVENTORY, COGS, ACCOUNTS_PAYABLE, BAD_DEBT_EXPENSE, OPENING_BALANCE_EQUITY,
--   PURCHASES, VAT_INPUT, VAT_DUE, WHT_PAYABLE, WHT_RECEIVABLE,
--   RETAINED_EARNINGS, GRNI, STOCK_ADJUSTMENT
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
            -- V10..V17 (existing)
            'SALES_REVENUE','VAT_PAYABLE','ACCOUNTS_RECEIVABLE','CASH',
            'INVENTORY','COGS','ACCOUNTS_PAYABLE',
            'BAD_DEBT_EXPENSE','OPENING_BALANCE_EQUITY',
            'PURCHASES',
            'VAT_INPUT','VAT_DUE','WHT_PAYABLE','WHT_RECEIVABLE',
            'RETAINED_EARNINGS',
            'GRNI','STOCK_ADJUSTMENT',
            -- HR payroll (ADR-0032 D-8)
            'SALARY_EXPENSE','EMPLOYER_STATUTORY_EXPENSE',
            'PAYE_PAYABLE','NSSF_PAYABLE','WCF_PAYABLE','SDL_PAYABLE','HESLB_PAYABLE',
            'NET_WAGES_PAYABLE','EMPLOYEE_LOAN_RECEIVABLE'
        ));

-- ============================================================================
-- (2) journal source-type CHECK widen — add PAYROLL
-- Union of all V17 tokens + PAYROLL; additive ALTER.
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
            'PAYROLL'
        ));
