/** Mirrors the backend UserRoleDto. Every numeric id typed `string` (wire contract). */

export interface UserRole {
  id: string;
  uid: string;
  userUid: string;
  roleCode: string;
  companyUid: string;
  branchUid: string | null;
  grantedAt: string;
}

export interface GrantRoleRequest {
  userUid: string;
  roleUid: string;
  companyUid: string;
  branchUid?: string;
}
