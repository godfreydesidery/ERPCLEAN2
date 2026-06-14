-- V83 — Seed the POS permission codes that the controllers actually check.
-- ISSUE-009: V43 seeded SALES.POS.* codes but the controllers (PosTillController,
-- PosSessionController, PosSaleController) check POS.TILL.*, POS.SESSION.*, POS.SALE.*.
-- PermissionResolver.getPermissions() does exact Set.contains — no prefix strip —
-- so the SALES.POS.* codes are never matched → every non-root POS request returns 403.
-- Fix: insert the exact codes the controllers check + grant them to ORG_ADMIN.
-- ON CONFLICT DO NOTHING — idempotent; SALES.POS.* seeds from V43 are left in place.

INSERT INTO permissions (code, module, description) VALUES
    ('POS.TILL.VIEW',          'sales', 'View POS tills (registers)'),
    ('POS.TILL.MANAGE',        'sales', 'Create and manage POS tills (registers)'),
    ('POS.SESSION.VIEW',       'sales', 'View POS cashier sessions and X/Z reads'),
    ('POS.SESSION.OPEN',       'sales', 'Open a POS cashier session on a till'),
    ('POS.SESSION.CLOSE',      'sales', 'Close an open POS cashier session'),
    ('POS.SESSION.RECONCILE',  'sales', 'Reconcile a closed POS session (posts variance to GL)'),
    ('POS.SALE.CREATE',        'sales', 'Ring a POS sale on an open session')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.code IN (
      'POS.TILL.VIEW',
      'POS.TILL.MANAGE',
      'POS.SESSION.VIEW',
      'POS.SESSION.OPEN',
      'POS.SESSION.CLOSE',
      'POS.SESSION.RECONCILE',
      'POS.SALE.CREATE'
  )
ON CONFLICT DO NOTHING;
