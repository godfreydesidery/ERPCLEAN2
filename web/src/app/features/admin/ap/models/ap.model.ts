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

/**
 * How much of a bill was actually checked against a purchase order and a goods receipt
 * (UAT 2026-08-12). Derived server-side at read time from the per-line match rows; never stored.
 *
 * A bill can be MATCHED, posted and payable with no comparison behind it at all — a service charge
 * carries no purchase link, and an AP opening balance never reaches the match engine. Those answer
 * NO_LINES_COMPARED and NEVER_MATCHED respectively, never ALL_LINES_COMPARED: an unknown must never
 * render as verified.
 */
export type BillComparisonState =
  | 'ALL_LINES_COMPARED'
  | 'SOME_LINES_COMPARED'
  | 'NO_LINES_COMPARED'
  | 'NEVER_MATCHED';

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
  /**
   * Derived, never stored: how much of this bill was actually checked against a purchase order and
   * a goods receipt. Optional because older responses and test fixtures may omit it — but a missing
   * value means "we do not know", NOT "checked". Render it as such.
   */
  comparisonState?: BillComparisonState | null;
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

/**
 * One line's 3-way match outcome — mirrors the backend
 * `BillMatchResultDto.LineMatchDto` record field for field.
 *
 * NULL IS NOT ZERO. Every fact and variance below is null when that leg of the comparison did not
 * run (no order line resolved, no goods receipt found). A UAT bill of 149,999,985 came back
 * "MATCHED" with both facts null and every variance rendered as `0.00`, which read as "checked and
 * equal". The backend now fails closed and sends null; the UI must render null as *not checked* and
 * never as a number. Only coerce with +v after checking the value is present.
 */
export interface LineMatchDto {
  billLineId: string;
  billLineUid: string;
  matchStatus: BillMatchStatus;
  /** Wire: number, or NULL when the price leg did not run. */
  priceVarianceAmount: number | string | null;
  /** Wire: number, or NULL when the price leg did not run. */
  priceVariancePct: number | string | null;
  /** Wire: number, or NULL when the quantity leg did not run. */
  qtyVariance: number | string | null;
  /** Wire: number, or NULL when the purchase-order line could not be resolved. */
  poUnitCostAmount: number | string | null;
  /** Wire: number, or NULL when the goods receipt line could not be resolved. */
  grReceivedQty: number | string | null;
  /** Wire: number. Taken from the bill line itself, so it is always present. */
  billedQty: number | string;
  matchedAt: string | null;
  /**
   * False when the 3-way control did NOT run on this line — the facts and variances above are then
   * null and mean nothing. Optional so a response predating the fail-closed fix (and fixtures that
   * do not care) simply reads as "unknown" rather than asserting a comparison happened.
   */
  comparisonPerformed?: boolean;
  /**
   * Plain-English explanation and next step, written for an accountant. Null when the line matched
   * cleanly and there is nothing to explain. Show this instead of the raw match status.
   */
  matchNote?: string | null;
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
  /**
   * Which cash/bank account the money actually left (UAT 2026-08). The request's
   * `cashBankAccountUid` is optional and falls back to the company default, and the posted
   * response used to say nothing at all — so a run against the wrong account looked identical to a
   * correct one until reconciliation. Null on payments that predate the account being stamped.
   */
  cashBankAccountId: string | null;
  cashBankAccountUid: string | null;
  cashBankAccountName: string | null;
  /** Bank account number; null for a pure cash account, which has none. */
  cashBankAccountNumber: string | null;
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
