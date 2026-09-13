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
  /**
   * The product's base unit, snapshotted when the transfer was raised. Null on transfers created
   * before the unit was captured — the screen leaves it blank rather than guessing a unit, because
   * a wrong unit on a transfer document is worse than none.
   */
  unitName: string | null;
  qtyTransferred: string;
  qtyTransferredBase: string;
  /**
   * Cost of ONE `unitName` — so for a line counted in cartons this is the price of a carton, not
   * of a piece. Derived by the backend; never re-derive it here, or the screen and the printed
   * document can disagree. Null is unknown (never costed, or a zero quantity), not free.
   */
  unitCost: string | number | null;
  /**
   * Cost value of the line when the transfer was raised. A BigDecimal, so it arrives as a JSON
   * NUMBER despite the `string` typing on its siblings — coerce with +v, never call string methods.
   *
   * Null is a REAL answer: the product has never been costed, or the transfer predates the field.
   * It must not render as 0.00 — that told storekeepers the goods they were moving were worthless.
   */
  valueAmount: string | number | null;
  currency: string | null;
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
  /**
   * The unit `qty` is counted in. Omit (or send the base unit) to mean the product's base unit,
   * which is what every transfer meant implicitly before units were selectable. Must be the base
   * unit or one of the product's configured pack sizes — the backend refuses anything else rather
   * than guessing, since a silent mis-conversion moves the wrong amount of stock.
   */
  unitUid?: string;
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
