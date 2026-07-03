/**
 * Approvals module models — mirrors backend DTOs exactly.
 * All Long/BigDecimal fields are typed `string` (Jackson stringifies them on the wire).
 * Timestamps are ISO-8601 Instant strings.
 */

// ── Enums ────────────────────────────────────────────────────────────────────

export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';
export type PolicyBranchScope = 'COMPANY_WIDE' | 'BRANCH';
export type ApprovalRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'RECALLED' | 'CANCELLED';
export type ApprovalStepStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SKIPPED';
export type DecisionAction = 'APPROVE' | 'REJECT';

// ── Policy DTOs ───────────────────────────────────────────────────────────────

export interface ApprovalPolicyStepDto {
  id: string;
  uid: string;
  sequence: string; // int serialised as string
  approverRoleCode: string;
}

export interface ApprovalPolicyDto {
  id: string;
  uid: string;
  companyId: string;
  documentType: string;
  name: string;
  branchScope: PolicyBranchScope;
  branchId: string | null;
  minAmount: string;
  maxAmount: string | null;
  currency: string;
  active: boolean;
  status: MasterStatus;
  notes: string | null;
  steps: ApprovalPolicyStepDto[];
}

// ── Policy request DTOs ───────────────────────────────────────────────────────

export interface PolicyStepInputDto {
  sequence: string; // int, dense from 1
  approverRoleCode: string;
}

export interface CreateApprovalPolicyRequest {
  companyId: string;
  documentType: string;
  name: string;
  branchScope: PolicyBranchScope;
  branchUid: string | null;
  minAmount: string;
  maxAmount: string | null;
  notes: string | null;
  steps: PolicyStepInputDto[];
}

export interface UpdateApprovalPolicyRequest {
  documentType: string;
  name: string;
  branchScope: PolicyBranchScope;
  branchUid: string | null;
  minAmount: string;
  maxAmount: string | null;
  notes: string | null;
  active: boolean;
  steps: PolicyStepInputDto[];
}

// ── Request DTOs ──────────────────────────────────────────────────────────────

export interface ApprovalDecisionDto {
  id: string;
  uid: string;
  approvalRequestStepId: string;
  action: DecisionAction;
  decidedBy: string;
  /** Friendly display name for decidedBy (user id); null if not resolvable. */
  decidedByName: string | null;
  decidedAt: string; // ISO Instant
  comment: string | null;
}

export interface ApprovalRequestStepDto {
  id: string;
  uid: string;
  sequence: string; // int serialised as string
  approverRoleCode: string;
  /** Friendly name for approverRoleCode (e.g. "Procurement Manager"); null if not resolvable. */
  approverRoleName: string | null;
  status: ApprovalStepStatus;
  resolvedBy: string | null;
  resolvedAt: string | null; // ISO Instant
  decisions: ApprovalDecisionDto[];
}

export interface ApprovalRequestDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  requestNumber: string;
  documentType: string;
  documentUid: string;
  amount: string;
  currency: string;
  status: ApprovalRequestStatus;
  autoApproved: boolean;
  sourcePolicyId: string | null;
  sourcePolicyUid: string | null;
  summary: string | null;
  submittedBy: string;
  /** Friendly display name for submittedBy (user id); null if not resolvable. */
  submittedByName: string | null;
  submittedAt: string; // ISO Instant
  resolvedAt: string | null; // ISO Instant
  resolvedBy: string | null;
  /** Friendly display name for resolvedBy (user id); null if not resolvable / not yet resolved. */
  resolvedByName: string | null;
  /** Friendly name for branchId; null if not resolvable. */
  branchName: string | null;
  /** Short code for branchId; null if not resolvable. */
  branchCode: string | null;
  steps: ApprovalRequestStepDto[];
}

// ── Action request DTOs ───────────────────────────────────────────────────────

export interface DecideRequest {
  action: DecisionAction;
  comment?: string | null;
}
