-- V57 — HR & Payroll: CoA accounts + gl_configs seed per existing company (ADR-0032 D-8)
-- Seeds 9 payroll CoA accounts + 9 gl_config mappings for every existing company.
-- #12-safe seed-uids: 'HR' || lpad(company_id,6,'0') || substr(md5(<code or key>),1,12) = 2+6+12 = 20 chars (<=26).
-- ON CONFLICT ... DO NOTHING — idempotent on keep-data re-deploy.
-- New companies get these via HrGlSeeder (wired in BootstrapRunner + CompanyService).
-- Additive only. V1–V56 are FROZEN.
--
-- Account codes chosen to avoid conflicts with existing CoA (V10 baseline + V17):
--   Existing: 1000,1100,1200,1300,1400,1500,2100,2150,2200,2300,2400,3000,3900,
--             4100,5100,5160,5200,5300,5400
--   WHT_PAYABLE → 2400 (taken); PAYE and statutory payables → 2500-2550 range
--   SALARY_EXPENSE → 5700 (5200=Rent taken; 5300=Salaries generic)
--   EMPLOYER_STATUTORY_EXPENSE → 5710
--   EMPLOYEE_LOAN_RECEIVABLE → 1450

-- ============================================================================
-- (1) CoA accounts — 9 payroll accounts per company
-- ============================================================================
INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance,
                                is_active, status, version, created_at)
SELECT 'HR' || lpad(c.id::text, 6, '0') || substr(md5('5700'), 1, 12),
       c.id, '5700', 'Salary & Wages Expense', 'EXPENSE', 'DEBIT', true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance,
                                is_active, status, version, created_at)
SELECT 'HR' || lpad(c.id::text, 6, '0') || substr(md5('5710'), 1, 12),
       c.id, '5710', 'Employer Statutory Contributions Expense', 'EXPENSE', 'DEBIT', true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance,
                                is_active, status, version, created_at)
SELECT 'HR' || lpad(c.id::text, 6, '0') || substr(md5('2500'), 1, 12),
       c.id, '2500', 'PAYE Payable', 'LIABILITY', 'CREDIT', true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance,
                                is_active, status, version, created_at)
SELECT 'HR' || lpad(c.id::text, 6, '0') || substr(md5('2510'), 1, 12),
       c.id, '2510', 'NSSF Payable', 'LIABILITY', 'CREDIT', true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance,
                                is_active, status, version, created_at)
SELECT 'HR' || lpad(c.id::text, 6, '0') || substr(md5('2520'), 1, 12),
       c.id, '2520', 'WCF Payable', 'LIABILITY', 'CREDIT', true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance,
                                is_active, status, version, created_at)
SELECT 'HR' || lpad(c.id::text, 6, '0') || substr(md5('2530'), 1, 12),
       c.id, '2530', 'SDL Payable', 'LIABILITY', 'CREDIT', true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance,
                                is_active, status, version, created_at)
SELECT 'HR' || lpad(c.id::text, 6, '0') || substr(md5('2540'), 1, 12),
       c.id, '2540', 'HESLB Payable', 'LIABILITY', 'CREDIT', true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance,
                                is_active, status, version, created_at)
SELECT 'HR' || lpad(c.id::text, 6, '0') || substr(md5('2550'), 1, 12),
       c.id, '2550', 'Net Wages Payable', 'LIABILITY', 'CREDIT', true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

INSERT INTO chart_of_accounts (uid, company_id, account_code, name, account_type, normal_balance,
                                is_active, status, version, created_at)
SELECT 'HR' || lpad(c.id::text, 6, '0') || substr(md5('1450'), 1, 12),
       c.id, '1450', 'Employee Loans Receivable', 'ASSET', 'DEBIT', true, 'ACTIVE', 0, now()
FROM companies c
ON CONFLICT (company_id, account_code) DO NOTHING;

-- ============================================================================
-- (2) gl_configs seed — 9 mappings per company
-- uid: 'HR' || lpad(company_id,6,'0') || substr(md5(config_key),1,12) = 20 chars (<=26)
-- ============================================================================
INSERT INTO gl_configs (uid, company_id, config_key, account_id, version, created_at)
SELECT 'HR' || lpad(coa.company_id::text, 6, '0') || substr(md5(m.config_key), 1, 12),
       coa.company_id,
       m.config_key,
       coa.id,
       0,
       now()
FROM (VALUES
    ('SALARY_EXPENSE',              '5700'),
    ('EMPLOYER_STATUTORY_EXPENSE',  '5710'),
    ('PAYE_PAYABLE',                '2500'),
    ('NSSF_PAYABLE',                '2510'),
    ('WCF_PAYABLE',                 '2520'),
    ('SDL_PAYABLE',                 '2530'),
    ('HESLB_PAYABLE',               '2540'),
    ('NET_WAGES_PAYABLE',           '2550'),
    ('EMPLOYEE_LOAN_RECEIVABLE',    '1450')
) AS m(config_key, account_code)
JOIN chart_of_accounts coa ON coa.account_code = m.account_code
ON CONFLICT (company_id, config_key) DO NOTHING;
