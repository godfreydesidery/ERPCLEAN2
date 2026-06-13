-- V81 — BI / Analytics & Dashboards permissions (ADR-0037 D-5)
--
-- The BI module owns NO business table. This migration seeds ONLY the role-aware permission
-- family that gates the read-only dashboard + its per-panel sections, and grants them to
-- ORG_ADMIN (existing + future companies via the INSERT ... SELECT ... ON CONFLICT pattern,
-- cloned from V15 reporting). Additive only; V1-V80 are FROZEN.
--
-- permissions.code is free-form (no CHECK constraint to widen — confirmed in recon).
-- Grant uses r.code = 'ORG_ADMIN' (NOT r.name — the Wave-1 V80 grant bug).

INSERT INTO permissions (code, module, description) VALUES
    ('BI.VIEW',          'bi', 'View the BI / analytics dashboard (the coarse gate for the dashboard route)'),
    ('BI.FINANCE.VIEW',  'bi', 'View the finance panels (revenue/net-profit trend, AR/AP, cash, working capital)'),
    ('BI.OPS.VIEW',      'bi', 'View the operations panels (inventory value, stock health)'),
    ('BI.CRM.VIEW',      'bi', 'View the CRM panel (pipeline value, win-rate, forecast KPIs)'),
    ('BI.EXPORT',        'bi', 'Export the dashboard to PDF / Excel / CSV')
ON CONFLICT (code) DO NOTHING;

-- Grant all BI.* permissions to ORG_ADMIN (existing companies + future companies via the
-- idempotent seed; per-company role grants are layered on top by operators).
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS  JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.code IN (
    'BI.VIEW',
    'BI.FINANCE.VIEW',
    'BI.OPS.VIEW',
    'BI.CRM.VIEW',
    'BI.EXPORT'
  )
ON CONFLICT DO NOTHING;
