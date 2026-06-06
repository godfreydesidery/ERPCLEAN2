/**
 * Mirrors the backend AuditLogDto. Every Long-id field is typed `string` (wire contract).
 * `detail` is a free-form JSON string persisted by the backend — typed `string` here and
 * rendered verbatim in the UI; callers that need structure can JSON.parse to `unknown`.
 */
export interface AuditLogEntry {
  actorUid: string | null;
  actorUsername: string | null;
  action: string;
  targetType: string | null;
  targetUid: string | null;
  companyUid: string | null;
  branchUid: string | null;
  /** Raw JSON string from the backend. Displayed verbatim; never structurally depended on. */
  detail: string | null;
  /** ISO-8601 instant string. */
  at: string;
  ip: string | null;
}

/** Subset of audit actions the filter select offers. Matches the backend-defined action codes. */
export const AUDIT_ACTIONS = [
  'USER.CREATE',
  'USER.DISABLE',
  'USER.ENABLE',
  'USER.UNLOCK',
  'USER.PASSWORD_SET',
  'USER.UPDATE',
  'ROLE.GRANT',
  'ROLE.REVOKE',
  'BRANCH.ASSIGN',
  'BRANCH.SET_DEFAULT',
  'BRANCH.UNASSIGN',
  'LOGIN.SUCCESS',
  'LOGIN.FAIL',
  'ACCOUNT.LOCKED',
  'ROOT.BYPASS',
] as const;

export type AuditAction = (typeof AUDIT_ACTIONS)[number];

/** Query filters for GET /api/v1/audit. All fields are optional. */
export interface AuditFilter {
  actorUid?: string;
  action?: string;
  targetType?: string;
  targetUid?: string;
  /** ISO-8601 instant (inclusive lower bound). */
  from?: string;
  /** ISO-8601 instant (inclusive upper bound). */
  to?: string;
}
