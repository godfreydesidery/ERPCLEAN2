-- V17 — Inventory Valuation & COGS (ADR-0020):
-- stock_on_hand: ADD avg_cost + on_hand_value + CHECK;
-- stock_movements: ADD unit_cost_amount + value_amount + CHECK;
-- CoA seed per existing company: 2150 GRNI (LIABILITY/CREDIT) + 5160 Stock Adjustment (EXPENSE/DEBIT);
-- gl_configs CHECK widen: add GRNI + STOCK_ADJUSTMENT;
-- gl_configs seed per existing company: GRNI→2150 + STOCK_ADJUSTMENT→5160 (#12-safe uid);
-- journal source-type CHECK widen: add STOCK_RECEIPT, COGS, STOCK_ADJUSTMENT, OPENING_INVENTORY;
-- permission seed + ORG_ADMIN grant: INVENTORY.VALUATION.VIEW + INVENTORY.OPENING.SET.
-- Additive only. V1–V16 are FROZEN.
-- Order: stock_on_hand ALTER → stock_movements ALTER → CoA seed →
--        gl_configs CHECK widen → gl_configs seed →
--        journal source-type CHECK widen → permission seed + grant.

-- ============================================================================
-- (1) ALTER stock_on_hand — add running average cost + on-hand value columns
-- ============================================================================
ALTER TABLE stock_on_hand
    ADD COLUMN avg_cost      NUMERIC(19,4),
    ADD COLUMN on_hand_value NUMERIC(19,4) NOT NULL DEFAULT 0;

ALTER TABLE stock_on_hand
    ADD CONSTRAINT chk_stock_on_hand_avg_nonneg
        CHECK (avg_cost IS NULL OR avg_cost >= 0);

-- ============================================================================
-- (2) ALTER stock_movements — add per-movement cost columns for exact reversals
-- ============================================================================
ALTER TABLE stock_movements
    ADD COLUMN unit_cost_amount NUMERIC(19,4),
    ADD COLUMN value_amount     NUMERIC(19,4);

ALTER TABLE stock_movements
    ADD CONSTRAINT chk_stock_movement_cost
        CHECK ((unit_cost_amount IS NULL AND value_amount IS NULL) OR unit_cost_amount >= 0);

-- ============================================================================
-- (3) CoA seed — add GRNI (2150) + Stock Adjustment / Shrinkage (5160) per company
-- uid: 'INV' || lpad(company_id, 6, '0') || account_code  = 3+6+4 = 13 chars (<=26)
-- ON CONFLICT (company_id, account_code) DO NOTHING — idempotent on keep-data re-deploy.
-- ============================================================================
INSERT INTO chart_of_accounts (
    uid, company_id, account_code, name, account_type, normal_balance,
    is_active, status, version, created_at
)
SELECT
    'INV' || lpad(c.id::text, 6, '0') || '2150' AS uid,
    c.id,
    '2150',
    'Goods Received Not Invoiced',
    'LIABILITY',
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
    'INV' || lpad(c.id::text, 6, '0') || '5160' AS uid,
    c.id,
    '5160',
    'Stock Adjustment / Shrinkage',
    'EXPENSE',
    'DEBIT',
    true,
    'ACTIVE',
    0,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

-- ============================================================================
-- (4) gl_configs CHECK widen — admit GRNI + STOCK_ADJUSTMENT
-- Union of ALL V16 keys + new keys; additive ALTER, V10–V16 DDL untouched.
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
            'GRNI','STOCK_ADJUSTMENT'
        ));

-- ============================================================================
-- (5) gl_configs seed per existing company: GRNI→2150 + STOCK_ADJUSTMENT→5160
-- CRITICAL #12-safe seed-uid: 'INC' || lpad(company_id,6,'0') || substr(md5(key),1,12)
--   = 3+6+12 = 21 chars (<=26). NEVER || config_key directly (overflow risk, finding #12).
-- ON CONFLICT (company_id, config_key) DO NOTHING — idempotent.
-- ============================================================================
INSERT INTO gl_configs (uid, company_id, config_key, account_id, version, created_at)
SELECT
    'INC' || lpad(coa.company_id::text, 6, '0') || substr(md5(m.config_key), 1, 12) AS uid,
    coa.company_id,
    m.config_key,
    coa.id,
    0,
    now()
FROM (VALUES
    ('GRNI',             '2150'),
    ('STOCK_ADJUSTMENT', '5160')
) AS m(config_key, account_code)
JOIN chart_of_accounts coa ON coa.account_code = m.account_code
ON CONFLICT (company_id, config_key) DO NOTHING;

-- ============================================================================
-- (6) journal source-type CHECK widen
-- Add STOCK_RECEIPT, COGS, STOCK_ADJUSTMENT, OPENING_INVENTORY.
-- Union of ALL V16 tokens + new tokens; additive ALTER, V10–V16 DDL untouched.
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
            'STOCK_RECEIPT','COGS','STOCK_ADJUSTMENT','OPENING_INVENTORY'
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
            'STOCK_RECEIPT','COGS','STOCK_ADJUSTMENT','OPENING_INVENTORY'
        ));
