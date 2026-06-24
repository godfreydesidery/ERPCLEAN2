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
  /** Batch/lot tracking — null when not captured. */
  lotNumber: string | null;
  manufactureDate: string | null;   // 'YYYY-MM-DD'
  expiryDate: string | null;        // 'YYYY-MM-DD'
  serialNumbers: string[];          // [] when none
}

// ── GoodsReceiptDto ─────────────────────────────────────────────────────────────

export interface GoodsReceiptDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  purchaseOrderId: string;
  purchaseOrderUid: string | null;
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
  /** Batch/lot tracking — omit when not provided. */
  lotNumber?: string;
  manufactureDate?: string;   // ISO date 'YYYY-MM-DD'
  expiryDate?: string;        // ISO date 'YYYY-MM-DD'
  serialNumbers?: string[];
}

export interface CreateGoodsReceiptRequest {
  purchaseOrderUid: string;
  notes?: string;
  lines: GoodsReceiptLineRequest[];
}

export interface VoidGoodsReceiptRequest {
  reason: string;
}

// ── Purchase Return enums / DTOs / requests ─────────────────────────────────

export type PurchaseReturnStatus = 'DRAFT' | 'CONFIRMED';

export interface PurchaseReturnLineDto {
  id: string;
  uid: string;
  lineNo: number;
  goodsReceiptLineId: string;
  goodsReceiptLineUid: string;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  returnedQty: string;
  returnedQtyInBase: string;
  unitCostAmount: string;
  lineValueAmount: string;
  currency: string;
}

export interface PurchaseReturnDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  returnNumber: string;
  status: PurchaseReturnStatus;
  goodsReceiptId: string;
  goodsReceiptUid: string;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  reason: string;
  netAmount: string;
  vatAmount: string;
  grossAmount: string;
  currency: string;
  debitNoteUid: string | null;
  glEntryUid: string | null;
  confirmedAt: string | null;
  createdAt: string | null;
  lines: PurchaseReturnLineDto[] | null;
}

export interface CreatePurchaseReturnLineRequest {
  goodsReceiptLineUid: string;
  returnedQty: string;
}

export interface CreatePurchaseReturnRequest {
  companyUid: string;
  goodsReceiptUid: string;
  reason: string;
  lines: CreatePurchaseReturnLineRequest[];
}

// ── Landed Cost enums / DTOs / requests ──────────────────────────────────────

export type LandedCostStatus = 'DRAFT' | 'CONFIRMED';
export type LandedCostBasis = 'BY_VALUE' | 'BY_QUANTITY';
export type LandedCostChargeType = 'FREIGHT' | 'DUTY' | 'CLEARING' | 'INSURANCE' | 'OTHER';

export interface LandedCostChargeDto {
  id: string;
  uid: string;
  lineNo: number;
  chargeType: LandedCostChargeType;
  amount: string;
  billed: boolean;
  supplierBillUid: string | null;
  currency: string;
}

export interface LandedCostDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  landedCostNumber: string;
  status: LandedCostStatus;
  basis: LandedCostBasis;
  totalChargeAmount: string;
  currency: string;
  glEntryUid: string | null;
  notes: string | null;
  confirmedAt: string | null;
  createdAt: string | null;
  receiptUids: string[];
  charges: LandedCostChargeDto[];
}

export interface CreateLandedCostChargeRequest {
  chargeType: LandedCostChargeType;
  amount: string;
}

export interface CreateLandedCostRequest {
  companyUid: string;
  basis: LandedCostBasis;
  notes?: string;
  receiptUids: string[];
  charges: CreateLandedCostChargeRequest[];
}

// ── Purchase Settings DTOs / requests ────────────────────────────────────────

export interface PurchaseSettingsDto {
  id: string;
  uid: string;
  companyId: string;
  poApprovalEnabled: boolean;
  poApprovalThresholdAmount: string;
  currency: string;
}

export interface UpdatePurchaseSettingsRequest {
  companyUid: string;
  poApprovalEnabled: boolean;
  poApprovalThresholdAmount: string;
  currency: string;
}
