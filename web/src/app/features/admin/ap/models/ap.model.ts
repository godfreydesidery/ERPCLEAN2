/**
 * AP feature models.
 * All Long/BigDecimal fields arrive as numbers on the wire (Jackson default serialisation).
 * Coerce defensively: use +v for arithmetic, String(v ?? '').trim() for string ops.
 * NEVER call .startsWith/.trim directly on a money value.
 *
 * Mirrors the backend AP controller DTOs exactly.
 */

// ── Enums (string unions) ────────────────────────────────────────────────────

export type SupplierBillStatus =
  | 'DRAFT'
  | 'MATCHED'
  | 'HELD'
  | 'APPROVED'
  | 'PARTIALLY_PAID'
  | 'PAID';

export type SupplierBillSource = 'PURCHASE_ORDER' | 'OPENING_BALANCE' | 'MANUAL';

export type BillMatchStatus =
  | 'MATCHED'
  | 'HELD_PRICE_VARIANCE'
  | 'HELD_QTY_VARIANCE'
  | 'VARIANCE_ACCEPTED';

/**
 * Post-hoc ratification state of the purchase order backing a bill (K3 follow-up).
 * Derived server-side at read time — never stored. AWAITING_RATIFICATION and
 * RATIFICATION_REFUSED both block payment release; the bill may still be entered and matched.
 */
export type DirectReceiptRatificationState =
  | 'NOT_APPLICABLE'
  | 'AWAITING_RATIFICATION'
  | 'RATIFICATION_REFUSED'
  | 'RATIFIED';

export type ApPaymentKind = 'SINGLE' | 'PAYMENT_RUN';

export type AgeingBucket =
  | 'CURRENT'
  | 'DAYS_1_30'
  | 'DAYS_31_60'
  | 'DAYS_61_90'
  | 'DAYS_91_PLUS';

export type TenderType =
  | 'CASH'
  | 'CHEQUE'
  | 'BANK_TRANSFER'
  | 'MOBILE_MONEY'
  | 'OTHER';

// ── Supplier Bill ─────────────────────────────────────────────────────────────

export interface SupplierBillLineDto {
  id: string;
  uid: string;
  supplierBillId: string;
  lineNo: number;
  productId: string | null;
  poLineUid: string | null;
  grLineUid: string | null;
  description: string;
  /** Wire: number — coerce with +v */
  billedQty: number | string;
  /** Wire: number — coerce with +v */
  unitCostAmount: number | string;
  /** Wire: number — coerce with +v */
  lineNetAmount: number | string;
  currency: string;
}

/**
 * SupplierBillDto — mirrors the backend.
 * Money fields arrive as numbers on the wire; coerce with +v.
 */
export interface SupplierBillDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  supplierId: string;
  billNumber: string;
  supplierInvoiceNo: string;
  source: SupplierBillSource;
  purchaseOrderUid: string | null;
  billDate: string;
  dueDate: string | null;
  /** Wire: number — coerce with +v */
  netAmount: number | string;
  /** Wire: number — coerce with +v */
  vatAmount: number | string;
  /** Wire: number — coerce with +v */
  grossAmount: number | string;
  /** Wire: number — coerce with +v */
  outstandingAmount: number | string;
  currency: string;
  status: SupplierBillStatus;
  postedGlEntryUid: string | null;
  /**
   * Derived, never stored. Optional so responses predating the K3 follow-up (and fixtures that
   * do not care) simply render nothing — treat a missing value as NOT_APPLICABLE.
   */
  directReceiptRatification?: DirectReceiptRatificationState | null;
  lines: SupplierBillLineDto[];
}

// ── Enter Bill request ────────────────────────────────────────────────────────

export interface BillLineRequest {
  productId?: number | null;
  poLineUid?: string | null;
  grLineUid?: string | null;
  /** Required. */
  description: string;
  /** Send as string; backend parses BigDecimal. */
  billedQty: string;
  /** Send as string; backend parses BigDecimal. */
  unitCostAmount: string;
}

export interface EnterBillRequest {
  companyUid: string;
  supplierUid: string;
  supplierInvoiceNo: string;
  purchaseOrderUid?: string | null;
  billDate: string;
  dueDate: string;
  /** Send as string; 0 if no VAT. */
  vatAmount: string;
  currency: string;
  tenderType?: string | null;
  lines: BillLineRequest[];
}

// ── 3-way match ───────────────────────────────────────────────────────────────

export interface LineMatchDto {
  billLineId: string;
  billLineUid: string;
  matchStatus: BillMatchStatus;
  /** Wire: number — coerce with +v */
  priceVarianceAmount: number | string;
  /** Wire: number — coerce with +v */
  priceVariancePct: number | string;
  /** Wire: number — coerce with +v */
  qtyVariance: number | string;
  /** Wire: number — coerce with +v */
  poUnitCostAmount: number | string;
  /** Wire: number — coerce with +v */
  grReceivedQty: number | string;
  /** Wire: number — coerce with +v */
  billedQty: number | string;
  matchedAt: string | null;
}

export interface BillMatchResultDto {
  billUid: string;
  billStatus: SupplierBillStatus;
  lineResults: LineMatchDto[];
}

export interface AcceptVarianceRequest {
  billLineUid: string;
}

// ── Payment ───────────────────────────────────────────────────────────────────

export interface PaymentAllocationDto {
  id: string;
  supplierBillId: string;
  supplierBillUid: string;
  /** Wire: number — coerce with +v */
  allocatedAmount: number | string;
}

export interface ApPaymentDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  supplierId: string;
  paymentNumber: string;
  kind: ApPaymentKind;
  paymentDate: string;
  /** Wire: number — coerce with +v */
  amount: number | string;
  currency: string;
  tenderType: string;
  bankReference: string | null;
  glEntryUid: string | null;
  allocations: PaymentAllocationDto[];
}

export interface PaySingleBillRequest {
  companyUid: string;
  supplierBillUid: string;
  /** Send as string; backend parses BigDecimal. */
  amount: string;
  paymentDate: string;
  tenderType: string;
  bankReference?: string | null;
}

export interface PaymentRunRequest {
  companyUid: string;
  supplierUid?: string | null;
  dueOnOrBefore: string;
  paymentDate: string;
  tenderType: string;
  bankReference?: string | null;
  billUids?: string[];
  /**
   * Optional WHT_ON_PAYMENT capture (ADR-0017 D-9).
   * When set, the cash CR is reduced by whtAmount and a WHT payable leg is posted.
   */
  whtTypeUid?: string | null;
  /** Send as string. */
  whtAmount?: string | null;
}

// ── Debit note ────────────────────────────────────────────────────────────────

export interface ApDebitNoteDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  supplierId: string;
  debitNoteNumber: string;
  supplierBillId: string | null;
  noteDate: string;
  /** Wire: number — coerce with +v */
  amount: number | string;
  /** Wire: number — coerce with +v */
  netAmount: number | string;
  /** Wire: number — coerce with +v */
  vatAmount: number | string;
  currency: string;
  reason: string;
  glEntryUid: string | null;
}

export interface RaiseDebitNoteRequest {
  companyUid: string;
  supplierUid: string;
  supplierBillUid?: string | null;
  noteDate: string;
  /** Send as string; backend parses BigDecimal. */
  netAmount: string;
  /** Send as string; may be null/0. */
  vatAmount?: string | null;
  reason: string;
}

// ── Opening balance ───────────────────────────────────────────────────────────

export interface SetApOpeningBalanceRequest {
  companyUid: string;
  supplierUid: string;
  /** Send as string; backend parses BigDecimal. */
  grossAmount: string;
  currency?: string | null;
  billDate: string;
  dueDate: string;
  supplierInvoiceNo?: string | null;
}

// ── Statement / ageing ────────────────────────────────────────────────────────

export interface ApAgeingRowDto {
  bucket: AgeingBucket;
  /** Wire: number — coerce with +v */
  amount: number | string;
  currency: string;
}

export interface ApBalanceDto {
  companyId: string;
  supplierId: string;
  /** Wire: number — coerce with +v */
  outstandingBalance: number | string;
  currency: string;
}

export interface ApReconciliationDto {
  companyId: string;
  /** Wire: number — coerce with +v */
  subLedgerTotal: number | string;
  /** Wire: number — coerce with +v */
  glControlBalance: number | string;
  /** Wire: number — coerce with +v */
  difference: number | string;
  currency: string;
}
