-- V19 — Sales Returns / RMA stage-2 marker (ADR-0021 Stage-2, additive).
--
-- The DDL for sales_returns + sales_return_lines, the chk_ar_credit_note_origin widen,
-- and the SALES.RETURN.* permissions were already included in V18__sales_orders.sql
-- (the ADR recommendation: DDL pulled forward, code staged).
-- This script is therefore idempotent: it re-asserts the permission seeds using
-- ON CONFLICT DO NOTHING and does NO table DDL (all tables already exist).
-- V1–V18 are FROZEN.

-- (1) Ensure SALES.RETURN permissions are seeded (idempotent — V18 already inserted them).
INSERT INTO permissions (code, module, description) VALUES
    ('SALES.RETURN.VIEW',   'sales', 'View sales returns / RMA'),
    ('SALES.RETURN.CREATE', 'sales', 'Create a sales return against a delivery (stock back in + credit note)')
ON CONFLICT (code) DO NOTHING;

-- (2) Ensure ORG_ADMIN holds those permissions (idempotent).
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.code IN ('SALES.RETURN.VIEW', 'SALES.RETURN.CREATE')
ON CONFLICT DO NOTHING;
