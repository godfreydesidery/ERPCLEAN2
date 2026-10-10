/**
 * Stock feature models — mirrors backend DTOs exactly.
 * All Long id fields are typed `string` (wire contract: Jackson stringifies Longs).
 * BigDecimal quantities/amounts arrive as strings on the wire.
 */

// ── Enums ───────────────────────────────────────────────────────────────────────

export type MovementType =
  | 'GOODS_RECEIPT'
  | 'SALE_ISSUE'
  | 'SALE_REVERSAL'
  | 'GOODS_RECEIPT_REVERSAL'
  | 'ADJUSTMENT'
  | 'OPENING_BALANCE';

export type MovementDirection = 'IN' | 'OUT';

export type AdjustmentReason =
  | 'COUNT_CORRECTION'
  | 'DAMAGE'
  | 'SHRINKAGE'
  | 'EXPIRY'
  | 'RECEIPT_CORRECTION'
  | 'OTHER';

// ── StockOnHandDto ──────────────────────────────────────────────────────────────

export interface StockOnHandDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  productId: string;
  /** Denormalised from the product row — always present on the wire. */
  productCode: string;
  productName: string;
  quantity: string;
  reorderLevel: string | null;
  maxQty: string | null;
  lastMovementAt: string | null;
  lastCountedAt: string | null;
  negative: boolean;
  low: boolean;
  /**
   * STK-10: the level `low` is judged against — this row's own `reorderLevel`, else the product's
   * level for the branch, else the Product Master level. Absent on older servers.
   */
  effectiveReorderLevel?: string | null;
  /** Location of this on-hand row (enriched server-side; optional for older servers). */
  locationUid?: string | null;
  locationName?: string | null;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

// ── StockMovementDto ────────────────────────────────────────────────────────────

export interface StockMovementDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  productId: string;
  movementType: MovementType;
  /** Signed delta in base units. Positive = IN, negative = OUT. */
  quantity: string;
  direction: MovementDirection;
  sourceEventUid: string | null;
  sourceDocumentType: string | null;
  sourceDocumentUid: string | null;
  reasonCode: string | null;
  note: string | null;
  occurredAt: string | null;
  createdAt: string | null;
  createdBy: string | null;
}

// ── Request types ───────────────────────────────────────────────────────────────

export interface AdjustStockRequest {
  productUid: string;
  /** Signed delta — positive increases stock, negative decreases. */
  quantity: string;
  reasonCode: AdjustmentReason;
  note?: string;
  /**
   * Location to correct (STK-01). The per-row Adjust sends the row's own location; omitted, the
   * server corrects the one location that holds the product (ignoring empty and in-transit rows).
   */
  locationUid?: string;
  /** STK-08: unit `quantity` is stated in (base unit or a pack size). Omitted = base unit. */
  unitUid?: string;
}

export interface OpeningBalanceRequest {
  productUid: string;
  /** Must be positive — opening balance seeds an initial level. */
  quantity: string;
  note?: string;
  /** STK-08: unit `quantity` is stated in. Omitted = base unit. */
  unitUid?: string;
  /** PRD-07: cost of one `unitUid` (needs INVENTORY.OPENING.SET). Omitted = the product cost. */
  unitCost?: string;
}

export interface SetReorderLevelRequest {
  /** Nullable — null clears the threshold. */
  reorderLevel: string | null;
}

// ── LocationOnHandRowDto (ADR-0028 D-8, FR-INVD-05/06) ─────────────────────────

/**
 * Per-location on-hand row returned by GET /stock/on-hand/by-location and
 * GET /stock/on-hand/by-product/uid/{productUid}.
 * All Long/BigDecimal fields arrive as numbers or strings on the wire — coerce with +v.
 */
export interface LocationOnHandRowDto {
  locationId: string;
  locationUid: string;
  locationCode: string;
  locationName: string;
  productId: string;
  productUid: string;
  productCode: string;
  productName: string;
  /** The product's base-unit label — every quantity on this row is in this unit. */
  unitLabel: string | null;
  /** Wire: BigDecimal — coerce with +v */
  quantity: number | string;
  /** Wire: BigDecimal — coerce with +v */
  onHandValue: number | string;
  /** Wire: BigDecimal — coerce with +v */
  avgCost: number | string;
  currency: string;
}
