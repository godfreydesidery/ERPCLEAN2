/** Mirrors the backend CompanyDto. Every numeric id is typed `string` (wire contract). */
export interface Company {
  id: string;
  uid: string;
  organisationId: string;
  code: string;
  name: string;
  legalName: string | null;
  taxId: string | null;
  timeZone: string;
  status: string;
}

export interface CreateCompanyRequest {
  organisationUid: string;
  code: string;
  name: string;
  legalName?: string;
  taxId?: string;
  timeZone?: string;
}

export interface UpdateCompanyRequest {
  name: string;
  legalName?: string;
  taxId?: string;
  timeZone?: string;
}
