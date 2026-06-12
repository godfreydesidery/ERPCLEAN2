-- V63 — HR & Payroll: permissions + ORG_ADMIN grant (ADR-0032 D-12)
-- Additive only. V1–V62 FROZEN.

INSERT INTO permissions (code, module, description) VALUES
    ('HR.EMPLOYEE.VIEW',    'hr', 'View employee, contract, and department records'),
    ('HR.EMPLOYEE.MANAGE',  'hr', 'Create/update employees, contracts, departments, recurring items (salary data)'),
    ('HR.PAYCOMPONENT.MANAGE', 'hr', 'Manage pay-component catalogue (earnings/deduction definitions)'),
    ('HR.LEAVE.VIEW',       'hr', 'View leave types, balances, and requests'),
    ('HR.LEAVE.MANAGE',     'hr', 'Create/manage leave types and balances; submit leave on behalf'),
    ('HR.LEAVE.APPROVE',    'hr', 'Approve or reject employee leave requests'),
    ('HR.LOAN.MANAGE',      'hr', 'Create and manage employee loans/advances'),
    ('HR.STATUTORY.MANAGE', 'hr', 'Add effective-dated PAYE band sets and statutory rate sets'),
    ('HR.PAYROLL.VIEW',     'hr', 'View payroll runs and lines'),
    ('HR.PAYROLL.RUN',      'hr', 'Create payroll runs and trigger calculation'),
    ('HR.PAYROLL.APPROVE',  'hr', 'Approve a calculated payroll run'),
    ('HR.PAYROLL.POST',     'hr', 'Post an approved payroll run to GL'),
    ('HR.PAYROLL.REVERSE',  'hr', 'Reverse a posted payroll run'),
    ('HR.PAYROLL.DISBURSE', 'hr', 'Disburse net wages and statutory payables via Cash & Bank'),
    ('HR.PAYSLIP.VIEW',     'hr', 'View the payslip register and statutory summary (HR/finance scope)'),
    ('HR.SELF.VIEW',        'hr', 'Employee self-service: view own payslips and leave, submit leave requests')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.code IN (
    'HR.EMPLOYEE.VIEW',
    'HR.EMPLOYEE.MANAGE',
    'HR.PAYCOMPONENT.MANAGE',
    'HR.LEAVE.VIEW',
    'HR.LEAVE.MANAGE',
    'HR.LEAVE.APPROVE',
    'HR.LOAN.MANAGE',
    'HR.STATUTORY.MANAGE',
    'HR.PAYROLL.VIEW',
    'HR.PAYROLL.RUN',
    'HR.PAYROLL.APPROVE',
    'HR.PAYROLL.POST',
    'HR.PAYROLL.REVERSE',
    'HR.PAYROLL.DISBURSE',
    'HR.PAYSLIP.VIEW',
    'HR.SELF.VIEW'
  )
ON CONFLICT DO NOTHING;
