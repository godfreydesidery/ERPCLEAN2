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
  /** Route agent assigned to run this VAN location (ADR-0051 D-8.4). Null for non-VAN locations. */
  agentUid: string | null;
  /** Display name for agentUid — never render the uid to users. */
  agentName: string | null;
}

// ── Request types ────────────────────────────────────────────────────────────

export interface CreateStockLocationRequest {
  code: string;
  name: string;
  locationType: LocationType;
  branchUid: string;
  makeDefault: boolean;
  /** Route agent to assign to a VAN location (service-enforced: VAN type only). */
  agentUid?: string;
}

export interface UpdateStockLocationRequest {
  name: string;
  locationType: LocationType;
  /**
   * Route agent to (re)assign on a VAN location. The backend distinguishes omitted/`undefined`
   * ("leave the existing assignment unchanged") from an explicit `""` ("clear it") — callers
   * clearing an agent must send `''`, not omit the field. A non-VAN location silently drops any
   * existing assignment server-side regardless of what is sent here.
   */
  agentUid?: string;
}
