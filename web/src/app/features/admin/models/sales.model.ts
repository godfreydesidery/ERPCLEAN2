/**
 * Sales feature models — mirrors backend DTOs exactly.
 * All Long id fields are typed `string` (wire contract: Jackson stringifies Longs).
 * Money amounts are strings on the wire (amount: string).
 */

// ── Enums ──────────────────────────────────────────────────────────────────────

export type InvoiceStatus = 'DRAFT' | 'FINALISED' | 'VOID';
export type InvoiceDocumentType = 'INVOICE';
export type VatStatus = 'STANDARD' | 'ZERO_RATED' | 'EXEMPT';
export type TenderType = 'CASH' | 'MOBILE_MONEY';

// ── SalesInvoiceDto ───────────────────────────────────────────────────────────

export interface SalesInvoiceDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  documentType: InvoiceDocumentType;
  /** Null until finalised (e.g. INV-0001). */
  invoiceNumber: string | null;
  status: InvoiceStatus;
  /** Read-time resolution of the posted SALES journal entry; null when not finalised/posted. */
  postedGlEntryUid: string | null;
  customerId: string;
  customerName: string;
  agentId: string | null;
  agentName: string | null;
  /** Route defaulted from the agent's primary route; may be null. */
  routeUid: string | null;
  routeCode: string | null;
  routeName: string | null;
  currency: string;
  docDiscountAmount: string | null;
  docDiscountPercent: string | null;
  netTotalAmount: string;
  vatTotalAmount: string;
  grossTotalAmount: string;
  /** JSON string from server — display only, not parsed. */
  taxSummary: string | null;
  finalisedAt: string | null;
  finalisedBy: string | null;
  voidedAt: string | null;
  voidedBy: string | null;
  voidReason: string | null;
  notes: string | null;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

// ── SalesInvoiceLineDto ───────────────────────────────────────────────────────

export interface SalesInvoiceLineDto {
  id: string;
  uid: string;
  invoiceId: string;
  companyId: string;
  branchId: string;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  quantity: string;
  qtyInBase: string;
  /**
   * FR-SALES-08 / weighed-goods scale-step rounding: what the caller entered before the scale
   * stepped the quantity to a billable increment. Set on every add/update-line call, so it is
   * usually equal to `quantity` — only differs when scale-step rounding actually moved it; null
   * only for lines predating this field.
   * Wire contract: BigDecimal → JSON number — unlike the sibling `quantity`/`qtyInBase` above
   * (legacy-typed `string`), this field is typed to the real wire shape (same precedent as
   * SalesSettingsDto.soApprovalThresholdAmount below).
   */
  requestedQuantity: number | null;
  listPriceAmount: string;
  unitPriceAmount: string;
  priceOverridden: boolean;
  overriddenBy: string | null;
  lineDiscountAmount: string | null;
  lineDiscountPercent: string | null;
  vatStatus: VatStatus;
  vatRate: string;
  netAmount: string;
  vatAmount: string;
  grossAmount: string;
  /**
   * ADR-0056: snapshot of whether unitPriceAmount was sourced from a VAT-inclusive price list —
   * true = unitPriceAmount is a gross (customer-facing) amount, false = net/exclusive.
   */
  priceInclusive: boolean;
  currency: string;
  createdAt: string | null;
  createdBy: string | null;
}

// ── SalesInvoicePaymentDto ────────────────────────────────────────────────────

export interface SalesInvoicePaymentDto {
  id: string;
  uid: string;
  invoiceId: string;
  companyId: string;
  branchId: string;
  tenderType: TenderType;
  amount: string;
  currency: string;
  changeAmount: string;
  reference: string | null;
  receivedAt: string | null;
  receivedBy: string | null;
  createdAt: string | null;
  createdBy: string | null;
}

// ── TaxRateDto ────────────────────────────────────────────────────────────────

export interface TaxRateDto {
  id: string;
  uid: string;
  companyId: string;
  vatStatus: VatStatus;
  rate: string;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

// ── Request types ─────────────────────────────────────────────────────────────

export interface CreateSalesInvoiceRequest {
  companyUid: string;
  customerUid: string;
  agentUid?: string;
  /** Optional; if omitted the backend defaults from the agent's primary route. */
  routeUid?: string;
  currency: string;
  notes?: string;
}

export interface FinaliseInvoiceRequest {
  // intentionally empty per backend contract
}

export interface VoidInvoiceRequest {
  reason: string;
}

export interface AddInvoiceLineRequest {
  productUid: string;
  unitUid: string;
  quantity: string;
  lineDiscountAmount?: string;
  lineDiscountPercent?: string;
}

/**
 * Override a DRAFT line's unit price (FR-SALES-08, BR-SALES-09). Requires SALES.INVOICE.OVERRIDE.
 * Wire contract: BigDecimal → JSON number (see requestedQuantity above for the same precedent).
 */
export interface OverrideLinePriceRequest {
  unitPriceAmount: number;
}

export interface AddPaymentRequest {
  tenderType: TenderType;
  amount: string;
  currency: string;
  reference?: string;
}

export interface UpdateTaxRateRequest {
  rate: string;
}

export interface CreateTaxRateRequest {
  companyId: string;
  vatStatus: VatStatus;
  rate: string;
}

// ── Sales Settings DTOs / requests (D-4: SO approval threshold) ──────────────
//
// NOTE: unlike most amount fields on this page (typed `string` per the legacy
// convention above), the backend SalesSettingsDto serialises
// soApprovalThresholdAmount as a BigDecimal, which Jackson emits as a JSON
// *number* (per the wire contract — only Long ids are stringified). Type it
// `number` to match the real wire shape.

/**
 * Per-company policy for a sale line priced at or below its average cost
 * (owner decision 2026-08-01, V93). Serialised as the enum NAME by Jackson.
 *  - OFF     — no check (the default, and the pre-V93 behaviour)
 *  - WARN    — allow the sale, record that it went out at or below cost
 *  - APPROVE — needs a supervisor who holds SALES.BELOW_COST.OVERRIDE
 *  - BLOCK   — always rejected
 */
export type BelowCostAction = 'OFF' | 'WARN' | 'APPROVE' | 'BLOCK';

export interface SalesSettingsDto {
  id: string;
  uid: string;
  companyId: string;
  soApprovalEnabled: boolean;
  soApprovalThresholdAmount: number | null;
  currency: string;
  /**
   * D-9: "block negative stock on sale" toggle (owner decision 2026-07-05, V87).
   * true = overselling allowed (backorder); false (default) = sale blocked when stock is short.
   */
  allowNegativeStock: boolean;
  /** "Sale at or below cost" policy (owner decision 2026-08-01, V93). */
  belowCostAction: BelowCostAction;
}

export interface UpdateSalesSettingsRequest {
  companyUid: string;
  soApprovalEnabled: boolean;
  soApprovalThresholdAmount: number | null;
  currency: string;
  allowNegativeStock: boolean;
  belowCostAction: BelowCostAction;
}

// ── FiscalReceiptDto (D-6: EFD / fiscal receipts, ADR-0049) ───────────────────
//
// One receipt row per FINALISED invoice. GET returns 404 (mapped to `null` by
// the service) when no receipt has been issued yet — absence is not an error.

export type FiscalReceiptStatus = 'PENDING' | 'ISSUED' | 'FAILED' | 'NOT_CONFIGURED' | 'VOID';

export interface FiscalReceiptDto {
  uid: string;
  invoiceUid: string;
  status: FiscalReceiptStatus;
  providerCode: string | null;
  fiscalNumber: string | null;
  verificationUrl: string | null;
  deviceSerial: string | null;
  issuedAt: string | null;
  attemptCount: number;
  errorDetail: string | null;
  version: string;
  createdAt: string;
}
