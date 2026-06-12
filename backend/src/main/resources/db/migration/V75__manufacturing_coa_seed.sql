-- V75 — Manufacturing CoA + gl_configs back-seed for existing companies (ADR-0035 D-7).
-- INSERT new accounts + gl_config mappings for every existing company.
-- ON CONFLICT DO NOTHING — idempotent.
-- #12-safe seed-uids: 'MFG' || lpad(id,6,'0') || account_code (3+6+4 = 13 chars ≤ 26)
-- gl_config uid:      'MFC' || lpad(company_id,6,'0') || substr(md5(config_key),1,12) (21 chars ≤ 26)
-- Additive only. V1–V74 are FROZEN / in-flight.

-- ============================================================================
-- (1) New CoA accounts per existing company
-- Four genuinely-new accounts: 1320 WIP, 2350 Labour Applied, 2360 Overhead Applied, 5180 Variance
-- FINISHED_GOODS maps to the EXISTING 1300 account (OQ-MFG-01) — no new account needed.
-- ============================================================================
INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance, is_active, created_at)
SELECT
    'MFG' || lpad(c.id::text, 6, '0') || '1320',
    c.id,
    '1320',
    'Work-In-Progress Inventory',
    'ASSET',
    'DEBIT',
    true,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance, is_active, created_at)
SELECT
    'MFG' || lpad(c.id::text, 6, '0') || '2350',
    c.id,
    '2350',
    'Labour Applied (Absorption Clearing)',
    'LIABILITY',
    'CREDIT',
    true,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance, is_active, created_at)
SELECT
    'MFG' || lpad(c.id::text, 6, '0') || '2360',
    c.id,
    '2360',
    'Overhead Applied (Absorption Clearing)',
    'LIABILITY',
    'CREDIT',
    true,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance, is_active, created_at)
SELECT
    'MFG' || lpad(c.id::text, 6, '0') || '5180',
    c.id,
    '5180',
    'Manufacturing Variance',
    'EXPENSE',
    'DEBIT',
    true,
    now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

-- ============================================================================
-- (2) gl_configs mappings per existing company
-- Five mappings: WIP_INVENTORY→1320, FINISHED_GOODS→1300, LABOUR_APPLIED→2350,
--                OVERHEAD_APPLIED→2360, MANUFACTURING_VARIANCE→5180
-- #12-safe uid: 'MFC' || lpad(company_id,6,'0') || substr(md5(config_key),1,12) ≤ 26 chars
-- ============================================================================

-- WIP_INVENTORY → 1320
INSERT INTO gl_configs (uid, company_id, config_key, account_id, created_at)
SELECT
    'MFC' || lpad(coa.company_id::text, 6, '0') || substr(md5('WIP_INVENTORY'), 1, 12),
    coa.company_id,
    'WIP_INVENTORY',
    coa.id,
    now()
FROM chart_of_accounts coa
WHERE coa.account_code = '1320'
ON CONFLICT (company_id, config_key) DO NOTHING;

-- FINISHED_GOODS → 1300 (existing Inventory account)
INSERT INTO gl_configs (uid, company_id, config_key, account_id, created_at)
SELECT
    'MFC' || lpad(coa.company_id::text, 6, '0') || substr(md5('FINISHED_GOODS'), 1, 12),
    coa.company_id,
    'FINISHED_GOODS',
    coa.id,
    now()
FROM chart_of_accounts coa
WHERE coa.account_code = '1300'
ON CONFLICT (company_id, config_key) DO NOTHING;

-- LABOUR_APPLIED → 2350
INSERT INTO gl_configs (uid, company_id, config_key, account_id, created_at)
SELECT
    'MFC' || lpad(coa.company_id::text, 6, '0') || substr(md5('LABOUR_APPLIED'), 1, 12),
    coa.company_id,
    'LABOUR_APPLIED',
    coa.id,
    now()
FROM chart_of_accounts coa
WHERE coa.account_code = '2350'
ON CONFLICT (company_id, config_key) DO NOTHING;

-- OVERHEAD_APPLIED → 2360
INSERT INTO gl_configs (uid, company_id, config_key, account_id, created_at)
SELECT
    'MFC' || lpad(coa.company_id::text, 6, '0') || substr(md5('OVERHEAD_APPLIED'), 1, 12),
    coa.company_id,
    'OVERHEAD_APPLIED',
    coa.id,
    now()
FROM chart_of_accounts coa
WHERE coa.account_code = '2360'
ON CONFLICT (company_id, config_key) DO NOTHING;

-- MANUFACTURING_VARIANCE → 5180
INSERT INTO gl_configs (uid, company_id, config_key, account_id, created_at)
SELECT
    'MFC' || lpad(coa.company_id::text, 6, '0') || substr(md5('MANUFACTURING_VARIANCE'), 1, 12),
    coa.company_id,
    'MANUFACTURING_VARIANCE',
    coa.id,
    now()
FROM chart_of_accounts coa
WHERE coa.account_code = '5180'
ON CONFLICT (company_id, config_key) DO NOTHING;
