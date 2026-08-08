-- ============================================================================
-- V97 — POS_TILL_EXPENSE GL config key, so a till expense stops posting to the
--       till-shortage account (product-wide defect, surfaced at Kilimanjaro 2026-08-08).
--
-- WHY: cash paid out of the drawer for a legitimate operating expense (transport, water,
-- casual labour) currently posts to the account mapped as POS_CASH_SHORT — "Cash Short /
-- Till Shortage". That is two misstatements from one shortcut:
--   1. POS_CASH_SHORT is a VARIANCE account whose whole purpose is detecting drawer
--      discrepancies. Commingling routine spend into it destroys that control signal —
--      a genuine shortage becomes indistinguishable from a bus fare.
--   2. Operating expense is misclassified by nature on the P&L. Expenses must be
--      classified by nature to be readable, and today every category collapses into one
--      line that is not even an expense category.
-- The two are only distinguishable by source_type inside the journals, i.e. not at all
-- on any statement a business owner reads.
--
-- This is NOT client-specific: every tenant recording a till payout hits it.
--
-- SCOPE OF THIS MIGRATION: widen the key CHECK only. The ACCOUNT each company maps to
-- POS_TILL_EXPENSE is per-tenant data and is provisioned by application code on demand,
-- per the standing "provisioning over data migrations" rule — never seeded here.
-- Per-category mapping (transport vs utilities vs wages) is deliberately NOT modelled
-- yet: one correct expense account is a strict improvement over a variance account, and
-- category-level mapping is a separate owner decision.
--
-- Follows the V63 widening pattern exactly: DROP IF EXISTS then re-add the FULL union of
-- every prior key plus the new one. Purely additive — the new set is a strict superset,
-- so every existing row already satisfies it and the constraint validates instantly with
-- no failing-row scan. No prior migration is edited; V1..V96 remain frozen.
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
            -- manufacturing (V59/V74)
            'WIP_INVENTORY','FINISHED_GOODS','LABOUR_APPLIED','OVERHEAD_APPLIED',
            'MANUFACTURING_VARIANCE',
            -- FX (V63)
            'REALIZED_FX_GAIN','REALIZED_FX_LOSS',
            'UNREALIZED_FX_GAIN','UNREALIZED_FX_LOSS',
            -- till expenses (V97, this migration)
            'POS_TILL_EXPENSE'
        )
    );
