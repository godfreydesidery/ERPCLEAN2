-- V22 — Approvals hardening (ADR-0022 reserved slot).
-- Reserved for the append-only DB grant on approval_decisions + approval_request_steps
-- (the F11 no-update/no-delete grant precedent) once the operational shape is confirmed.
-- Currently a no-op marker — the append-only invariant is enforced at the service layer in v1.

-- Placeholder: future REVOKE UPDATE, DELETE ON approval_decisions FROM <app_role>
-- will land here as a hardening follow-up without reopening V20 or V21.

-- (no DDL in this version)
