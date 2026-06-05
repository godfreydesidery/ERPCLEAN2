/** Mirrors the backend BranchDto. Numeric ids typed `string` (wire contract). */
export interface Branch {
  id: string;
  uid: string;
  companyId: string;
  companyUid: string;
  code: string;
  name: string;
  timeZone: string;
  isDefault: boolean;
  status: string;
}

export interface CreateBranchRequest {
  companyUid: string;
  code: string;
  name: string;
  timeZone?: string;
  makeDefault: boolean;
}

export interface UpdateBranchRequest {
  name: string;
  timeZone?: string;
}
