-- V16 — Year-End Close (ADR-0019):
-- fiscal_years additive columns (closed_at, closed_by, closing_journal_uid);
-- gl_configs CHECK widen (RETAINED_EARNINGS);
-- RETAINED_EARNINGS config seed per existing company → 3900 (#12-safe uid);
-- journal source-type CHECK widen (YEAR_END_CLOSE);
-- GL.YEAR.CLOSE permission + ORG_ADMIN grant.
-- Additive only. V1–V15 are FROZEN.
-- Order: fiscal_years ALTER → gl_configs CHECK widen → RETAINED_EARNINGS seed →
--        journal source-type CHECK widen → permission seed + grant.

-- ============================================================================
-- (1) fiscal_years — add close-audit columns (D-7, ADR-0019)
-- ============================================================================
ALTER TABLE fiscal_years ADD COLUMN closed_at           TIMESTAMPTZ;
ALTER TABLE fiscal_years ADD COLUMN closed_by           BIGINT;
ALTER TABLE fiscal_years ADD COLUMN closing_journal_uid VARCHAR(26);

ALTER TABLE fiscal_years
    ADD CONSTRAINT fk_fiscal_year_closed_by
        FOREIGN KEY (closed_by) REFERENCES app_users (id);

-- ============================================================================
-- (2) gl_configs CHECK widen — admit RETAINED_EARNINGS (D-9, ADR-0019)
-- Union of all V14 keys + RETAINED_EARNINGS; additive ALTER, V10–V15 untouched.
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
            'RETAINED_EARNINGS'
        ));

-- ============================================================================
-- (3) RETAINED_EARNINGS config seed per existing company → 3900 (#12-safe uid)
-- 3900 Retained Earnings is seeded for every company by V10, so the JOIN
-- resolves for all. ON CONFLICT makes the seed idempotent on keep-data re-deploy.
-- uid = 'YEC' || lpad(company_id, 6, '0') || substr(md5('RETAINED_EARNINGS'), 1, 12)
--      = 3 + 6 + 12 = 21 chars (≤ VARCHAR(26)) — NEVER || config_key (overflow, finding #12).
-- ============================================================================
INSERT INTO gl_configs (uid, company_id, config_key, account_id, version, created_at)
SELECT 'YEC' || lpad(coa.company_id::text, 6, '0') || substr(md5('RETAINED_EARNINGS'), 1, 12),
       coa.company_id,
       'RETAINED_EARNINGS',
       coa.id,
       0,
       now()
FROM   chart_of_accounts coa
WHERE  coa.account_code = '3900'
ON CONFLICT (company_id, config_key) DO NOTHING;

-- ============================================================================
-- (4) journal source-type CHECK widen — add YEAR_END_CLOSE (D-10, ADR-0019)
-- Union of all V14 tokens + YEAR_END_CLOSE; additive ALTER, V10–V15 untouched.
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
            'YEAR_END_CLOSE'
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
            'YEAR_END_CLOSE'
        ));

-- ============================================================================
-- (5) GL.YEAR.CLOSE permission + ORG_ADMIN grant (D-12, ADR-0019)
-- Mirrors V14 §10 / V15 pattern. One permission covers both close and reopen.
-- ============================================================================
INSERT INTO permissions (code, module, description) VALUES
    ('GL.YEAR.CLOSE', 'gl',
     'Close / reopen a fiscal year — the year-end closing entry + the reopen reversal')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.code = 'GL.YEAR.CLOSE'
ON CONFLICT DO NOTHING;
