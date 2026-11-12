/** Mirrors the backend TenantSummaryDto. Counts are JSON numbers; ids are strings. */
export interface TenantSummary {
  uid: string;
  name: string;
  alias: string | null;
  status: 'ACTIVE' | 'INACTIVE';
  createdAt: string;
  companyCount: number;
  userCount: number;
  /** The organisation the caller is working in right now (home, or the tenant entered). */
  current?: boolean;
}

/** Mirrors the backend CreateTenantRequest. Optional fields are omitted when blank. */
export interface CreateTenantRequest {
  organisationName: string;
  timeZone?: string;
  companyCode: string;
  companyName: string;
  companyLegalName?: string;
  companyTaxId?: string;
  companyVrn?: string;
  branchCode: string;
  branchName: string;
  adminUsername: string;
  adminPassword: string;
  adminDisplayName?: string;
  baseCurrency?: string;
  defaultCurrency?: string;
  enabledCurrencies?: string[];
  priceListCode?: string;
  priceListName?: string;
  priceListIncludesVat?: boolean;
  walkInCustomerName?: string;
  posTillName?: string;
}

/** Where the platform operator lands when it enters a tenant (GET /organisations/uid/{uid}/entry). */
export interface TenantEntry {
  organisationUid: string;
  organisationName: string;
  companyName: string;
  branchUid: string;
  branchName: string;
  branchCode: string;
}
