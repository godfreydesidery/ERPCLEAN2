/**
 * Stock Count feature models — mirrors backend DTOs exactly (ADR-0028 D-6).
 * All Long id fields are typed `string` (wire: Jackson stringifies Longs).
 * BigDecimal qty/value fields arrive as strings on the wire.
 */

// ── Enums ─────────────────────────────────────────────────────────────────────

export type StockCountStatus = 'DRAFT' | 'COUNTING' | 'POSTED' | 'CANCELLED';

// ── StockCountLineDto ─────────────────────────────────────────────────────────

export interface StockCountLineDto {
  id: string;
  uid: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  systemQty: string;
  countedQty: string | null;
  varianceQty: string | null;
  unitCostAmount: string;
  varianceValue: string | null;
  reasonCode: string | null;
  movementUid: string | null;
  currency: string;
}

// ── StockCountDto ─────────────────────────────────────────────────────────────

export interface StockCountDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  countNumber: string;
  status: StockCountStatus;
  countType: string;
  locationId: string;
  countDate: string;
  frozenAt: string | null;
  postedAt: string | null;
  cancelledAt: string | null;
  varianceGlEntryUid: string | null;
  notes: string | null;
  lines: StockCountLineDto[];
}

// ── Request types ─────────────────────────────────────────────────────────────

export interface CreateStockCountRequest {
  locationUid: string;
  /** ISO date yyyy-MM-dd */
  countDate: string;
  /** FULL or CYCLE */
  countType: string;
  /** For CYCLE counts: optional product uid subset. Null/empty = FULL scope. */
  productUids?: string[];
  notes?: string;
}

export interface EnterCountLineEntry {
  /** Backend Long id — typed string on the wire. */
  lineId: string;
  countedQty: string;
  reasonCode?: string;
}

export interface EnterCountRequest {
  lines: EnterCountLineEntry[];
}
