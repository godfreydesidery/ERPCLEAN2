/**
 * CRM Activity models — mirrors backend ActivityDto + CreateActivityRequest exactly.
 * All Long id fields typed `string` (Jackson stringifies Longs on the wire).
 * Timestamps (Instant) and dates (LocalDate) arrive as ISO strings.
 */

// ── Enums ──────────────────────────────────────────────────────────────────────

export type ActivityType = 'CALL' | 'EMAIL' | 'MEETING' | 'NOTE' | 'TASK';

export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

// ── ActivityDto ────────────────────────────────────────────────────────────────

export interface ActivityDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  activityNumber: string;
  activityType: ActivityType;
  leadId: string | null;
  opportunityId: string | null;
  subject: string;
  body: string | null;
  occurredAt: string | null;
  dueDate: string | null;
  assigneeUserId: string | null;
  done: boolean;
  doneAt: string | null;
  status: MasterStatus;
  version: string;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

// ── CreateActivityRequest ──────────────────────────────────────────────────────

export interface CreateActivityRequest {
  companyId: string;
  activityType: ActivityType;
  /** Exactly one of leadUid or opportunityUid must be set. */
  leadUid?: string;
  opportunityUid?: string;
  subject: string;
  body?: string;
  occurredAt?: string;
  /** TASK only. */
  dueDate?: string;
  /** TASK only — numeric id as string on the wire. */
  assigneeUserId?: string;
}
