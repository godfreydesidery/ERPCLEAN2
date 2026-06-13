/**
 * Stock location models — mirrors backend DTOs exactly.
 * All Long id fields are typed `string` (wire contract: Jackson stringifies Longs).
 */

// ── Enums ────────────────────────────────────────────────────────────────────

export type LocationType = 'WAREHOUSE' | 'STORE' | 'VAN' | 'QUARANTINE' | 'OTHER';

export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

// ── StockLocationDto ─────────────────────────────────────────────────────────

export interface StockLocationDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  code: string;
  name: string;
  locationType: LocationType;
  isDefault: boolean;
  status: MasterStatus;
}

// ── Request types ────────────────────────────────────────────────────────────

export interface CreateStockLocationRequest {
  code: string;
  name: string;
  locationType: LocationType;
  branchUid: string;
  makeDefault: boolean;
}

export interface UpdateStockLocationRequest {
  name: string;
  locationType: LocationType;
}
