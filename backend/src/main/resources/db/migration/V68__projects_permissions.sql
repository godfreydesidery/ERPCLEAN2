-- V68 — Projects: permission seed + ORG_ADMIN grant (ADR-0033 D-9/D-10).
-- 10 PROJECTS.* permissions seeded; all granted to ORG_ADMIN role.
-- Permissions have no uid — #12 N/A. ON CONFLICT DO NOTHING = idempotent.
-- Additive only. V1–V67 are FROZEN.

INSERT INTO permissions (code, module, description) VALUES
    ('PROJECTS.PROJECT.VIEW',    'projects', 'View project list and project detail'),
    ('PROJECTS.PROJECT.CREATE',  'projects', 'Create a new project'),
    ('PROJECTS.PROJECT.MANAGE',  'projects', 'Edit a project and change its status (activate/hold/complete/cancel)'),
    ('PROJECTS.TASK.VIEW',       'projects', 'View project tasks'),
    ('PROJECTS.TASK.MANAGE',     'projects', 'Create, edit, and deactivate project tasks'),
    ('PROJECTS.TIMESHEET.VIEW',  'projects', 'View project timesheets'),
    ('PROJECTS.TIMESHEET.RECORD','projects', 'Record project timesheet hours'),
    ('PROJECTS.TAG.MANAGE',      'projects', 'Attach, detach, or re-tag a project dimension on a posting'),
    ('PROJECTS.ISSUE.CREATE',    'projects', 'Issue materials from stock to a project'),
    ('PROJECTS.COSTING.VIEW',    'projects', 'View the project P&L, WIP, and job-cost roll-up')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS JOIN permissions p
WHERE  r.code   = 'ORG_ADMIN'
AND    p.module = 'projects'
ON CONFLICT DO NOTHING;
