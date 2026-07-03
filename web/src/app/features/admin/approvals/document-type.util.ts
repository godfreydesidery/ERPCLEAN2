/**
 * Approval `documentType` is an opaque, consumer-owned free string (ADR-0022 D-1 —
 * the engine is deliberately document-agnostic, no backend enum). This util maps the
 * codes consumer modules are known to use — matching each module's own routing-key
 * constants (`PurchaseNumberGenerator`, `OrderToCashNumberGenerator`,
 * `SalesDepthNumberGenerator`, the stock-event `DOC_TYPE` constants, `ApBillNumberGenerator`,
 * `ApPaymentKind`) — to a friendly label and the real business-document screen.
 *
 * As of this fix only `PURCHASE_ORDER` is actually wired end-to-end (`PoApprovalGate`
 * is the sole `submitForApproval` caller); the rest are forward-mapped so the UI is
 * correct the moment another module's approval integration lands, rather than lagging
 * behind it. Anything not in the map (e.g. `AR_INVOICE` — no detail route exists yet)
 * degrades to the raw code with no link, never to the wrong page.
 */
export interface DocumentTypeInfo {
  label: string;
  /** RouterLink commands for the business document's detail screen, uid-appended by the caller. */
  route: string[] | null;
}

const DOCUMENT_TYPES: Record<string, DocumentTypeInfo> = {
  PURCHASE_ORDER:        { label: 'Purchase Order',        route: ['/admin/purchase-orders/uid'] },
  PURCHASE_REQUISITION:  { label: 'Purchase Requisition',   route: ['/admin/purchase-requisitions/uid'] },
  RFQ:                   { label: 'Request for Quotation',  route: ['/admin/rfqs/uid'] },
  GOODS_RECEIPT:         { label: 'Goods Receipt',          route: ['/admin/goods-receipts/uid'] },
  PURCHASE_RETURN:       { label: 'Purchase Return',        route: ['/admin/purchase-returns/uid'] },
  QUOTATION:             { label: 'Quotation',              route: ['/admin/quotations/uid'] },
  SALES_ORDER:           { label: 'Sales Order',            route: ['/admin/sales-orders/uid'] },
  DELIVERY:              { label: 'Delivery Note',          route: ['/admin/deliveries/uid'] },
  SALES_RETURN:          { label: 'Sales Return',           route: ['/admin/sales-returns/uid'] },
  SALES_INVOICE:         { label: 'Sales Invoice',          route: ['/admin/sales-invoices/uid'] },
  BLANKET_ORDER:         { label: 'Blanket Order',          route: ['/admin/blanket-orders/uid'] },
  STANDING_ORDER:        { label: 'Standing Order',         route: ['/admin/standing-orders/uid'] },
  AP_BILL:               { label: 'Supplier Bill',          route: ['/admin/ap/supplier-bills/uid'] },
  PAYMENT_RUN:           { label: 'Payment Run',            route: ['/admin/ap/payments/uid'] },
  // No AR invoice detail screen exists yet (list-only route) — label only, no link.
  AR_INVOICE:            { label: 'AR Invoice',             route: null },
};

/** Friendly label for a documentType code; falls back to the raw code if unmapped. */
export function documentTypeLabel(documentType: string): string {
  return DOCUMENT_TYPES[documentType]?.label ?? documentType;
}

/**
 * RouterLink commands for the business document behind (documentType, documentUid), or
 * `null` when no detail screen is known — callers must not link anywhere in that case
 * (never fall back to an unrelated screen, e.g. the generated-documents log).
 */
export function documentTypeRoute(documentType: string, documentUid: string): string[] | null {
  const info = DOCUMENT_TYPES[documentType];
  if (!info?.route) return null;
  return [...info.route, documentUid];
}
