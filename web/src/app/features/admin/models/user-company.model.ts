/** Mirrors the backend UserCompanyDto. Every numeric id typed `string` (wire contract). */

export interface UserCompany {
  id: string;
  uid: string;
  userUid: string;
  companyUid: string;
  companyName: string;
  assignedAt: string;
  revokedAt: string | null;
}

export interface AssignCompanyRequest {
  userUid: string;
  companyUid: string;
}
