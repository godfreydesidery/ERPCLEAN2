-- V36 — Procurement depth: catch-all indexes + constraint fixup for the procurement
--        journal source-type and gl_config_key tokens that V46 (fixed-assets, which runs
--        after V34-V36) will override. This migration is reserved as a coordination hook.
--        At wave-2 merge time, the merge coordinator ensures V46 includes the full union.
--        For standalone branch testing (before V46 runs), V34 already has the tokens.
--        This migration ensures the gl_config_key constraint is correct after V34.
-- Additive only. V1–V35 are FROZEN/in-flight.
-- ADR-0027 D-11 / D-12.

-- ============================================================================
-- (1) Additional cross-table indexes not already in V32-V35 (ADR-0027 D-11)
-- ============================================================================

-- supplier_quote_lines: supplier lookup with supplier_id from parent
-- (actual last-quoted-price needs a join, but index product in quote lines helps)
-- No additional indexes needed — already covered in V33.

-- purchase_settings: already indexed via unique constraint.

-- ============================================================================
-- (2) Ensure the gl_config_key constraint still contains LANDED_COST_CLEARING.
-- (Defensive re-assert after possible override by other in-flight modules.)
-- At integration time, this will be reconciled by the merge coordinator.
-- This is a NO-OP if the prior constraint already includes LANDED_COST_CLEARING.
-- We use DROP IF EXISTS + re-add to be safe:
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
            -- fixed-assets (ADR-0030 V46 tokens — included here so V36 is superset-safe
            --   if V46 has not yet been applied; V46 is a no-op DROP/ADD which will also
            --   include these since procurement and FA run concurrently) ---
            'FIXED_ASSETS','FIXED_ASSET_CLEARING','ACCUMULATED_DEPRECIATION',
            'DEPRECIATION_EXPENSE','GAIN_LOSS_ON_DISPOSAL','REVALUATION_RESERVE',
            -- procurement-depth (ADR-0027) ---
            'LANDED_COST_CLEARING'
        ));

-- ============================================================================
-- (3) Re-assert journal source-type constraints with full union through V46.
-- Includes procurement tokens (LANDED_COST, PURCHASE_RETURN) AND FA tokens
-- (FA_ACQUISITION, DEPRECIATION, FA_DISPOSAL, FA_REVALUATION) so that this
-- migration is the true superset, leaving V46's DROP/ADD idempotent.
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
            'FA_ACQUISITION','DEPRECIATION','FA_DISPOSAL','FA_REVALUATION',
            -- procurement-depth (ADR-0027) ---
            'LANDED_COST','PURCHASE_RETURN'
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
            'FA_ACQUISITION','DEPRECIATION','FA_DISPOSAL','FA_REVALUATION',
            -- procurement-depth (ADR-0027) ---
            'LANDED_COST','PURCHASE_RETURN'
        ));

-- ============================================================================
-- (4) Widen chk_stock_movement_type to admit PURCHASE_RETURN (ADR-0027 D-7).
-- Union of ALL prior tokens (V7 + V38 superset) + PURCHASE_RETURN.
-- V38 will also DROP/ADD this constraint (for TRANSFER_OUT/IN); since V36 runs
-- BEFORE V38, V38 must be a superset — its constraint is re-asserted there.
-- At merge time the wave-2 coordinator ensures V38 includes PURCHASE_RETURN too.
-- For standalone branch testing before V38 runs, this V36 assert is sufficient.
-- ============================================================================
ALTER TABLE stock_movements
    DROP CONSTRAINT IF EXISTS chk_stock_movement_type;

ALTER TABLE stock_movements
    ADD CONSTRAINT chk_stock_movement_type CHECK (
        movement_type IN (
            'GOODS_RECEIPT', 'SALE_ISSUE', 'SALE_REVERSAL',
            'GOODS_RECEIPT_REVERSAL', 'ADJUSTMENT', 'OPENING_BALANCE',
            'TRANSFER_OUT', 'TRANSFER_IN',
            -- procurement-depth (ADR-0027) ---
            'PURCHASE_RETURN'
        )
    );
