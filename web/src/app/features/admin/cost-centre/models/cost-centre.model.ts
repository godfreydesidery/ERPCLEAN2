/**
 * Cost-centre module DTOs — mirrors the backend response shapes.
 * All Long / BigDecimal fields are strings on the wire (global Jackson config).
 */

// ── Dimension type (seeded, not created by the UI) ──────────────────────────

export type DimensionSlot = 'COST_CENTRE' | 'DEPARTMENT' | 'DIMENSION_3' | 'DIMENSION_4';
export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

export interface DimensionDto {
  id: string;
  uid: string;
  companyId: string;
  slot: DimensionSlot;
  code: string;
  name: string;
  builtIn: boolean;
  mandatory: boolean;
  status: MasterStatus;
}

/** Request body for the PATCH .../mandatory toggle. */
export interface SetDimensionMandatoryRequest {
  mandatory: boolean;
}

/** Request body for POST /api/v1/dimensions — backend assigns the next free custom slot. */
export interface CreateDimensionRequest {
  companyId: string;
  code: string;
  name: string;
  description?: string;
}

// ── Dimension value (user-created, children of a dimension) ──────────────────

export interface DimensionValueDto {
  id: string;
  uid: string;
  companyId: string;
  dimensionId: string;
  dimensionUid: string;
  /** DimensionSlot name as string */
  slot: string;
  code: string;
  name: string;
  parentId: string | null;
  parentUid: string | null;
  /** Tagging gate: false = excluded from NEW tagging (historical lines unaffected) */
  active: boolean;
  status: MasterStatus;
}

export interface CreateDimensionValueRequest {
  dimensionUid: string;
  code: string;
  name: string;
  /** null = root (no parent) */
  parentUid?: string | null;
}

export interface UpdateDimensionValueRequest {
  /** null = unchanged */
  name?: string | null;
  /** set new parent; null in combination with clearParent=true = make root */
  parentUid?: string | null;
  /** explicit flag to null the parent (make root); null/false = leave untouched */
  clearParent?: boolean | null;
}

// ── Costing report ────────────────────────────────────────────────────────────

export interface DimensionSlicedTbRowDto {
  valueId: string;
  valueUid: string;
  valueCode: string;
  valueName: string;
  accountId: string;
  accountUid: string;
  accountCode: string;
  accountName: string;
  accountType: string;
  /** BigDecimal as string */
  totalDebit: string;
  /** BigDecimal as string */
  totalCredit: string;
  /** BigDecimal as string; net = totalDebit - totalCredit (slice does NOT net to zero) */
  net: string;
}

export interface DimensionSlicedTbDto {
  dimensionSlot: DimensionSlot;
  rollUp: boolean;
  rows: DimensionSlicedTbRowDto[];
}
