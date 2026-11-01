-- V110 — COMPLIMENTARY tender type and the COMPLIMENTARY_EXPENSE GL config key.
-- Widens two CHECKs only; the per-company account is provisioned by application code.

SET LOCAL lock_timeout = '10s';

ALTER TABLE sales_invoice_payments
    DROP CONSTRAINT IF EXISTS chk_sales_invoice_payment_tender;

ALTER TABLE sales_invoice_payments
    ADD CONSTRAINT chk_sales_invoice_payment_tender CHECK (
        tender_type IN ('CASH','MOBILE_MONEY','CHEQUE','CARD',
                        'COMPLIMENTARY')
    );

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
            -- manufacturing (V59/V74)
            'WIP_INVENTORY','FINISHED_GOODS','LABOUR_APPLIED','OVERHEAD_APPLIED',
            'MANUFACTURING_VARIANCE',
            -- FX (V63)
            'REALIZED_FX_GAIN','REALIZED_FX_LOSS',
            'UNREALIZED_FX_GAIN','UNREALIZED_FX_LOSS',
            -- till expenses (V97)
            'POS_TILL_EXPENSE',
            -- V110
            'COMPLIMENTARY_EXPENSE'
        )
    );
