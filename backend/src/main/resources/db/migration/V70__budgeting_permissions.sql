-- V70 — Budgeting permissions seed + ORG_ADMIN grant (ADR-0034 D-12).
-- Six BUDGETING.* permissions (Amended 2026-06-11: the two BUDGETING.COSTCENTRE.* are DROPPED).
-- Permissions have no uid — #12 N/A. ON CONFLICT DO NOTHING = idempotent re-run.

INSERT INTO permissions (code, module, description) VALUES
    ('BUDGETING.BUDGET.VIEW',     'budgeting', 'List and view budgets, versions, and lines'),
    ('BUDGETING.BUDGET.MANAGE',   'budgeting', 'Create budgets; create/edit versions and lines; seed/re-plan'),
    ('BUDGETING.BUDGET.SUBMIT',   'budgeting', 'Submit a budget version for approval; recall a submitted version'),
    ('BUDGETING.BUDGET.APPROVE',  'budgeting', 'Approve or reject a submitted budget version'),
    ('BUDGETING.REPORT.VIEW',     'budgeting', 'Run the budget-vs-actual variance report and departmental actuals'),
    ('BUDGETING.REPORT.EXPORT',   'budgeting', 'Export variance and departmental reports to CSV')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS JOIN permissions p
WHERE  r.code   = 'ORG_ADMIN'
  AND  p.code IN (
    'BUDGETING.BUDGET.VIEW',
    'BUDGETING.BUDGET.MANAGE',
    'BUDGETING.BUDGET.SUBMIT',
    'BUDGETING.BUDGET.APPROVE',
    'BUDGETING.REPORT.VIEW',
    'BUDGETING.REPORT.EXPORT'
  )
ON CONFLICT DO NOTHING;
