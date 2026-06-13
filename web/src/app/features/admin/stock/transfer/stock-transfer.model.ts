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
  sourceLocationId: string;
  destBranchId: string;
  destLocationId: string;
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
