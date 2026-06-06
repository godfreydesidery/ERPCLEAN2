/** Mirrors the backend UserBranchDto. Every numeric id typed `string` (wire contract). */

export interface UserBranch {
  id: string;
  uid: string;
  userUid: string;
  branchUid: string;
  branchCode: string;
  branchName: string;
  companyUid: string;
  isDefault: boolean;
  assignedAt: string;
}

export interface AssignBranchRequest {
  userUid: string;
  branchUid: string;
  makeDefault: boolean;
}
