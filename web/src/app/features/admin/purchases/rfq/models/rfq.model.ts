/**
 * RFQ and Supplier Quote feature models — mirror backend DTOs exactly.
 * All Long id fields are typed `string` (Jackson stringifies Longs on the wire).
 * BigDecimal amounts arrive as strings. Timestamps (Instant/LocalDate) as ISO strings.
 */

// ── Enums ─────────────────────────────────────────────────────────────────────

export type RfqStatus =
  | 'DRAFT'
  | 'SENT'
  | 'QUOTES_RECEIVED'
  | 'AWARDED'
  | 'CANCELLED';

export type SupplierQuoteStatus = 'RECEIVED' | 'AWARDED' | 'NOT_AWARDED';

// ── RfqLineDto ────────────────────────────────────────────────────────────────

export interface RfqLineDto {
  id: string;
  uid: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  quantity: string;
  quantityInBase: string;
}

// ── RfqDto ────────────────────────────────────────────────────────────────────

export interface RfqDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  rfqNumber: string;
  status: RfqStatus;
  sourceRequisitionUid: string | null;
  responseDueDate: string | null;
  awardedQuoteUid: string | null;
  awardedPoUid: string | null;
  notes: string | null;
  sentAt: string | null;
  awardedAt: string | null;
  cancelledAt: string | null;
  createdAt: string | null;
  lines: RfqLineDto[];
  supplierUids: string[];
}

// ── SupplierQuoteLineDto ──────────────────────────────────────────────────────

export interface SupplierQuoteLineDto {
  id: string;
  uid: string;
  lineNo: number;
  rfqLineId: string;
  rfqLineUid: string;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  quotedQty: string;
  quotedQtyInBase: string;
  unitPriceAmount: string;
  lineTotalAmount: string;
  currency: string;
}

// ── SupplierQuoteDto ──────────────────────────────────────────────────────────

export interface SupplierQuoteDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  quoteNumber: string;
  rfqId: string;
  rfqUid: string;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  status: SupplierQuoteStatus;
  validUntil: string | null;
  leadTimeDays: number | null;
  quoteTotalAmount: string;
  currency: string;
  notes: string | null;
  createdAt: string | null;
  lines: SupplierQuoteLineDto[];
}

// ── Request types ─────────────────────────────────────────────────────────────

export interface CreateRfqLineRequest {
  /** backend FK: productId (Long as string) */
  productId: string;
  /** backend FK: unitId (Long as string) */
  unitId: string;
  quantity: string;
}

export interface CreateRfqRequest {
  companyUid: string;
  sourceRequisitionUid?: string;
  responseDueDate?: string;
  notes?: string;
  supplierUids: string[];
  lines: CreateRfqLineRequest[];
}

export interface CaptureSupplierQuoteLineRequest {
  /** backend FK rfqLineId (Long as string) */
  rfqLineId: string;
  quotedQty: string;
  unitPriceAmount: string;
}

export interface CaptureSupplierQuoteRequest {
  rfqUid: string;
  supplierUid: string;
  validUntil?: string;
  leadTimeDays?: number;
  notes?: string;
  lines: CaptureSupplierQuoteLineRequest[];
}
