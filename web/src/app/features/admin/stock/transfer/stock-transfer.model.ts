/**
 * Stock Transfer feature models — mirrors backend DTOs exactly.
 * All Long id fields typed `string` (wire: Jackson stringifies Longs).
 * BigDecimal fields typed `string` on the wire.
 */

// ── Enum ──────────────────────────────────────────────────────────────────────

export type StockTransferStatus =
  | 'DRAFT'
  | 'DISPATCHED'
  | 'RECEIVED'
  | 'COMPLETED'
  | 'CANCELLED';

// ── Response DTOs ────────────────────────────────────────────────────────────

export interface StockTransferLineDto {
  id: string;
  uid: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  qtyTransferred: string;
  qtyTransferredBase: string;
  valueAmount: string;
  currency: string;
}

export interface StockTransferDto {
  id: string;
  uid: string;
  companyId: string;
  transferNumber: string;
  status: StockTransferStatus;
  transferMode: string;
  sourceBranchId: string;
  /** Display name for the source branch — never render sourceBranchId to users. */
  sourceBranchName: string | null;
  /** Source branch short code, shown as a muted secondary alongside the name. */
  sourceBranchCode: string | null;
  sourceLocationId: string;
  /** Display name for the source location — never render sourceLocationId to users. */
  sourceLocationName: string | null;
  destBranchId: string;
  /** Display name for the destination branch — never render destBranchId to users. */
  destBranchName: string | null;
  /** Destination branch short code, shown as a muted secondary alongside the name. */
  destBranchCode: string | null;
  destLocationId: string;
  /** Display name for the destination location — never render destLocationId to users. */
  destLocationName: string | null;
  transferDate: string;
  dispatchedAt: string | null;
  receivedAt: string | null;
  notes: string | null;
  lines: StockTransferLineDto[];
}

// ── Request DTOs ─────────────────────────────────────────────────────────────

export interface StockTransferLineRequest {
  productUid: string;
  qty: string;
}

export interface CreateStockTransferRequest {
  sourceLocationUid: string;
  destLocationUid: string;
  transferDate: string;
  transferMode: string;
  notes?: string;
  lines: StockTransferLineRequest[];
}

// ── StockLocationDto (minimal — for picker) ──────────────────────────────────

export interface StockLocationDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  code: string;
  name: string;
  locationType: string;
  isDefault: boolean;
  status: string;
}
