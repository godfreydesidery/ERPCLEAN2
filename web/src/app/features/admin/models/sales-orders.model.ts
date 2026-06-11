/**
 * Sales Orders / Order-to-Cash models — mirrors backend DTOs exactly.
 * All Long id fields are typed `string` (wire contract: Jackson stringifies Longs).
 * BigDecimal amounts arrive as strings on the wire.
 * Timestamps (Instant / LocalDate) arrive as ISO strings.
 */

// ── Enums ────────────────────────────────────────────────────────────────────────

export type QuotationStatus =
  | 'DRAFT'
  | 'SENT'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'EXPIRED';

export type SalesOrderStatus =
  | 'DRAFT'
  | 'CONFIRMED'
  | 'PARTIALLY_FULFILLED'
  | 'FULFILLED'
  | 'PARTIALLY_INVOICED'
  | 'INVOICED'
  | 'CLOSED'
  | 'CANCELLED';

export type DeliveryStatus =
  | 'DRAFT'
  | 'CONFIRMED'
  | 'CANCELLED';

// ── QuotationLineDto ─────────────────────────────────────────────────────────────

export interface QuotationLineDto {
  id: string;
  uid: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  quantity: string;
  qtyInBase: string;
  listPriceAmount: string | null;
  unitPriceAmount: string;
  lineDiscountAmount: string | null;
  lineDiscountPercent: string | null;
  vatStatus: string | null;
  vatRate: string | null;
  netAmount: string;
  vatAmount: string;
  grossAmount: string;
  currency: string;
}

// ── QuotationDto ──────────────────────────────────────────────────────────────────

export interface QuotationDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  quoteNumber: string | null;
  status: QuotationStatus;
  customerId: string;
  agentId: string | null;
  currency: string;
  quoteDate: string;
  validUntil: string;
  docDiscountAmount: string | null;
  docDiscountPercent: string | null;
  netTotalAmount: string;
  vatTotalAmount: string;
  grossTotalAmount: string;
  notes: string | null;
  sentAt: string | null;
  acceptedAt: string | null;
  rejectedAt: string | null;
  expiredAt: string | null;
  convertedOrderUid: string | null;
  lines: QuotationLineDto[];
}

// ── SalesOrderLineDto ─────────────────────────────────────────────────────────────

export interface SalesOrderLineDto {
  id: string;
  uid: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  qtyOrdered: string;
  qtyOrderedBase: string;
  qtyFulfilledBase: string;
  qtyInvoicedBase: string;
  qtyReservedBase: string;
  openQtyBase: string;
  listPriceAmount: string | null;
  unitPriceAmount: string;
  priceOverridden: boolean;
  lineDiscountAmount: string | null;
  lineDiscountPercent: string | null;
  vatStatus: string | null;
  vatRate: string | null;
  netAmount: string;
  vatAmount: string;
  grossAmount: string;
  currency: string;
}

// ── SalesOrderDto ──────────────────────────────────────────────────────────────────

export interface SalesOrderDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  orderNumber: string | null;
  status: SalesOrderStatus;
  customerId: string;
  agentId: string | null;
  currency: string;
  orderDate: string;
  sourceQuotationUid: string | null;
  docDiscountAmount: string | null;
  docDiscountPercent: string | null;
  netTotalAmount: string;
  vatTotalAmount: string;
  grossTotalAmount: string;
  confirmedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  notes: string | null;
  lines: SalesOrderLineDto[];
}

// ── DeliveryLineDto ───────────────────────────────────────────────────────────────

export interface DeliveryLineDto {
  id: string;
  uid: string;
  lineNo: number;
  salesOrderLineId: string;
  salesOrderLineUid: string;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  qtyDelivered: string;
  qtyDeliveredBase: string;
  qtyInvoicedBase: string;
  returnedQtyBase: string;
  issueValueAmount: string | null;
  currency: string;
}

// ── DeliveryDto ────────────────────────────────────────────────────────────────────

export interface DeliveryDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  deliveryNumber: string | null;
  salesOrderId: string;
  salesOrderUid: string;
  status: DeliveryStatus;
  customerId: string;
  deliveryDate: string;
  confirmedAt: string | null;
  notes: string | null;
  lines: DeliveryLineDto[];
}

// ── Request DTOs ───────────────────────────────────────────────────────────────────

export interface CreateQuotationRequest {
  companyUid: string;
  customerUid: string;
  agentUid?: string;
  currency: string;
  quoteDate: string;
  validUntil: string;
  docDiscountAmount?: string;
  docDiscountPercent?: string;
  notes?: string;
}

export interface AddQuotationLineRequest {
  productUid: string;
  unitUid: string;
  quantity: string;
  unitPriceOverride?: string;
  lineDiscountAmount?: string;
  lineDiscountPercent?: string;
}

export interface CreateSalesOrderRequest {
  companyUid: string;
  customerUid: string;
  agentUid?: string;
  currency: string;
  orderDate: string;
  docDiscountAmount?: string;
  docDiscountPercent?: string;
  notes?: string;
}

export interface AddSalesOrderLineRequest {
  productUid: string;
  unitUid: string;
  quantity: string;
  unitPriceOverride?: string;
  lineDiscountAmount?: string;
  lineDiscountPercent?: string;
}

export interface CancelSalesOrderRequest {
  reason?: string;
}

export interface CreateDeliveryRequest {
  salesOrderUid: string;
  deliveryDate: string;
  notes?: string;
  lines: Array<{
    salesOrderLineUid: string;
    qtyDelivered: string;
  }>;
}
