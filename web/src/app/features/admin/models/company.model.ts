/**
 * Mirrors the backend CompanyDto. Every numeric id is typed `string` (wire contract).
 * The address/contact/vrn fields (SAM Electronix go-live) feed the company header block printed
 * on POS receipts and the Sales/Stock reports — every field is required-but-nullable on the DTO,
 * matching the CustomerModel/SupplierModel convention (never `?:` on a mirrored response field).
 */
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
  vrn: string | null;
  contactPhone: string | null;
  contactEmail: string | null;
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  region: string | null;
  country: string | null;
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
  vrn?: string;
  contactPhone?: string;
  contactEmail?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  region?: string;
  country?: string;
}
