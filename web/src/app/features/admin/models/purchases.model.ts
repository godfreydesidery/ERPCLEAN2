/**
 * Purchases feature models — mirrors backend DTOs exactly.
 * All Long id fields are typed `string` (wire contract: Jackson stringifies Longs).
 * BigDecimal amounts arrive as strings on the wire.
 * Timestamps (Instant / LocalDate) arrive as ISO strings.
 */

// ── Enums ───────────────────────────────────────────────────────────────────────

export type PurchaseOrderStatus =
  | 'DRAFT'
  | 'ORDERED'
  | 'PARTIALLY_RECEIVED'
  | 'RECEIVED'
  | 'CLOSED'
  | 'VOID';

export type GoodsReceiptStatus = 'DRAFT' | 'RECEIVED' | 'VOID';

// ── PurchaseOrderLineDto ────────────────────────────────────────────────────────

export interface PurchaseOrderLineDto {
  id: string;
  uid: string;
  purchaseOrderId: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  orderedQty: string;
  orderedQtyInBase: string;
  receivedQtyInBase: string;
  /** Derived: orderedQtyInBase − receivedQtyInBase. */
  outstandingQtyInBase: string;
  fullyReceived: boolean;
  unitCostAmount: string;
  lineTotalAmount: string;
  currency: string;
}

// ── PurchaseOrderDto ────────────────────────────────────────────────────────────

export interface PurchaseOrderDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  /** Null until placed (e.g. PO-0001). */
  orderNumber: string | null;
  status: PurchaseOrderStatus;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  currency: string;
  orderTotalAmount: string;
  expectedDate: string | null;
  notes: string | null;
  orderedAt: string | null;
  voidedAt: string | null;
  voidReason: string | null;
  closedAt: string | null;
  createdAt: string | null;
  /** Lines included on the single-PO get endpoint; null/absent on the list endpoint. */
  lines: PurchaseOrderLineDto[] | null;
}

// ── GoodsReceiptLineDto ─────────────────────────────────────────────────────────

export interface GoodsReceiptLineDto {
  id: string;
  uid: string;
  goodsReceiptId: string;
  purchaseOrderLineId: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  receivedQty: string;
  qtyInBase: string;
  unitCostAmount: string;
  lineCostAmount: string;
  currency: string;
}

// ── GoodsReceiptDto ─────────────────────────────────────────────────────────────

export interface GoodsReceiptDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  purchaseOrderId: string;
  receiptNumber: string;
  status: GoodsReceiptStatus;
  supplierId: string;
  receivedAt: string | null;
  voidedAt: string | null;
  voidReason: string | null;
  notes: string | null;
  createdAt: string | null;
  lines: GoodsReceiptLineDto[] | null;
}

// ── Request types ───────────────────────────────────────────────────────────────

export interface AddPurchaseOrderLineRequest {
  productUid: string;
  unitUid: string;
  orderedQty: string;
  unitCostAmount: string;
  note?: string;
}

export interface CreatePurchaseOrderRequest {
  companyUid: string;
  supplierUid: string;
  currency: string;
  notes?: string;
  expectedDate?: string;
  lines?: AddPurchaseOrderLineRequest[];
}

export interface UpdatePurchaseOrderRequest {
  notes?: string;
  expectedDate?: string;
}

export interface VoidPurchaseOrderRequest {
  reason: string;
}

export interface GoodsReceiptLineRequest {
  purchaseOrderLineUid: string;
  receivedQty: string;
}

export interface CreateGoodsReceiptRequest {
  purchaseOrderUid: string;
  notes?: string;
  lines: GoodsReceiptLineRequest[];
}

export interface VoidGoodsReceiptRequest {
  reason: string;
}
