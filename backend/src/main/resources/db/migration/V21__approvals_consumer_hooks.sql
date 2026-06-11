-- V21 — Approvals consumer hooks (ADR-0022 reserved slot).
-- Reserved for additive columns a first consumer (Procurement PO-approval) may need,
-- e.g. a denormalised approval_request_uid on purchase_orders.
-- V20 ships the full engine schema; this slot holds the consumer-increment addition.
-- Currently a no-op marker — idempotent re-assert of the permission seeds.

-- Ensure approvals permissions are seeded (idempotent — V20 already inserted them).
INSERT INTO permissions (code, module, description) VALUES
    ('APPROVALS.POLICY.VIEW',    'approvals', 'View approval policies'),
    ('APPROVALS.POLICY.MANAGE',  'approvals', 'Create/edit/deactivate approval policies and their step chains'),
    ('APPROVALS.REQUEST.VIEW',   'approvals', 'View approval requests'),
    ('APPROVALS.DECIDE',         'approvals', 'Approve/reject approval-request steps and see the approvals inbox'),
    ('APPROVALS.ADMIN',          'approvals', 'Recall/cancel any approval request; override a stuck chain')
ON CONFLICT (code) DO NOTHING;
