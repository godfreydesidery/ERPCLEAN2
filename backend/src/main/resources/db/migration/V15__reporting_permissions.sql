-- V15 — Financial Reporting permissions (ADR-0018 D-10). PERMS ONLY — Reporting owns no business table.
-- Additive only. V1–V14 are FROZEN.
-- No uid-bearing rows — finding #12 (seed-uid overflow) does NOT apply (D-10).

INSERT INTO permissions (code, module, description) VALUES
    ('REPORT.VIEW',          'reporting', 'View all financial statements + the account-ledger drill-down (coarse)'),
    ('REPORT.PL.VIEW',       'reporting', 'View the Income Statement / Profit & Loss'),
    ('REPORT.BS.VIEW',       'reporting', 'View the Balance Sheet'),
    ('REPORT.CASHFLOW.VIEW', 'reporting', 'View the Cash-Flow Statement (indirect)'),
    ('REPORT.LEDGER.VIEW',   'reporting', 'Drill into the GL account-ledger from a statement line'),
    ('REPORT.EXPORT',        'reporting', 'Export any statement / the ledger to PDF / Excel / CSV')
ON CONFLICT (code) DO NOTHING;

-- Grant all REPORT.* permissions to ORG_ADMIN (existing companies + future companies via the
-- BootstrapRunner role assignment — the CROSS JOIN covers all existing ORG_ADMIN role rows;
-- new companies created post-V15 receive the grant via the same bootstrap path the other modules use).
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
AND    p.code IN (
    'REPORT.VIEW',
    'REPORT.PL.VIEW',
    'REPORT.BS.VIEW',
    'REPORT.CASHFLOW.VIEW',
    'REPORT.LEDGER.VIEW',
    'REPORT.EXPORT'
)
ON CONFLICT DO NOTHING;
