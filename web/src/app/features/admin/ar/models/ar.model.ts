/**
 * AR feature models.
 * All Long/BigDecimal fields arrive as numbers OR strings on the wire depending on the Jackson
 * serialiser version — coerce defensively: use +v (Number coercion) for arithmetic, and
 * String(v ?? '').trim() for string operations. NEVER call .startsWith/.trim directly on a
 * money value.
 *
 * Mirrors the backend AR controller DTOs exactly.
 */

// ── Enums (string unions) ────────────────────────────────────────────────────

export type ArInvoiceStatus = 'OPEN' | 'PARTIAL' | 'PAID' | 'WRITTEN_OFF';
export type ArInvoiceSource = 'SALE' | 'OPENING_BALANCE';
export type AgeingBucket = 'CURRENT' | 'DAYS_1_30' | 'DAYS_31_60' | 'DAYS_61_90' | 'DAYS_91_PLUS';
export type TenderType = 'CASH' | 'CHEQUE' | 'BANK_TRANSFER' | 'MOBILE_MONEY' | 'OTHER';

// ── AR Invoice ────────────────────────────────────────────────────────────────

/**
 * ArInvoiceDto — mirrors the backend.
 * originalAmount and outstandingAmount arrive as numbers or strings on the wire; coerce with +v.
 */
export interface ArInvoiceDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  customerId: string;
  sourceInvoiceUid: string | null;
  documentNo: string;
  /** Wire: number or string — always coerce: +(dto.originalAmount) */
  originalAmount: number | string;
  /** Wire: number or string — always coerce: +(dto.outstandingAmount) */
  outstandingAmount: number | string;
  currency: string;
  invoiceDate: string;
  dueDate: string | null;
  status: ArInvoiceStatus;
  source: ArInvoiceSource;
}

// ── AR Receipt ────────────────────────────────────────────────────────────────

export interface AllocationLineDto {
  arInvoiceUid: string;
  /** Wire: number or string */
  allocatedAmount: number | string;
}

export interface ArReceiptDto {
  uid: string;
  customerId: string;
  receiptNumber: string;
  receiptDate: string;
  /** Wire: number or string */
  amount: number | string;
  /** Wire: number or string */
  unallocatedAmount: number | string;
  currency: string;
  tenderType: TenderType;
  allocations: AllocationLineDto[];
}

/** Allocation line inside RecordReceiptRequest. */
export interface AllocationLineRequest {
  arInvoiceUid: string;
  /** Send as string. */
  allocatedAmount: string;
}

export interface RecordReceiptRequest {
  companyUid: string;
  customerUid: string;
  /** Send as string. */
  amount: string;
  currency: string;
  receiptDate: string;
  tenderType: TenderType;
  bankReference?: string;
  allocations: AllocationLineRequest[];
}

// ── Write-off ─────────────────────────────────────────────────────────────────

export interface ArWriteOffDto {
  uid: string;
  arInvoiceUid: string;
  writeOffDate: string;
  reason: string;
}

export interface WriteOffRequest {
  arInvoiceUid: string;
  writeOffDate: string;
  reason: string;
}

// ── Credit note ───────────────────────────────────────────────────────────────

export interface ArCreditNoteDto {
  uid: string;
  arInvoiceUid: string | null;
  noteDate: string;
  /** Wire: number or string */
  netAmount: number | string;
  /** Wire: number or string */
  vatAmount: number | string;
  currency: string;
  reason: string;
}

export interface RaiseCreditNoteRequest {
  companyUid: string;
  customerUid: string;
  arInvoiceUid?: string;
  noteDate: string;
  /** Send as string. */
  netAmount: string;
  /** Send as string. */
  vatAmount: string;
  currency: string;
  reason: string;
}

// ── Opening balance ────────────────────────────────────────────────────────────

export interface SetOpeningBalanceRequest {
  companyUid: string;
  customerUid: string;
  /** Send as string. */
  amount: string;
  currency: string;
  invoiceDate: string;
  dueDate?: string;
  documentNo?: string;
}

// ── Statement ─────────────────────────────────────────────────────────────────

export interface ArAgeingBucketDto {
  bucket: AgeingBucket;
  /** Wire: number or string */
  amount: number | string;
  currency: string;
}

export interface ArStatementDto {
  companyId: string;
  customerId: string;
  asAt: string;
  /** Wire: number or string */
  totalOutstanding: number | string;
  currency: string;
  ageing: ArAgeingBucketDto[];
  openItems: ArInvoiceDto[];
  recentReceipts: ArReceiptDto[];
}

// ── Ageing row (standalone ageing endpoint) ───────────────────────────────────

export interface ArAgeingRowDto {
  customerId: string;
  customerCode: string;
  customerName: string;
  /** Wire: number or string */
  current: number | string;
  /** Wire: number or string */
  days1to30: number | string;
  /** Wire: number or string */
  days31to60: number | string;
  /** Wire: number or string */
  days61to90: number | string;
  /** Wire: number or string */
  days91Plus: number | string;
  /** Wire: number or string */
  total: number | string;
  currency: string;
}

// ── Balance ───────────────────────────────────────────────────────────────────

export interface ArBalanceDto {
  customerId: string;
  /** Wire: number or string */
  balance: number | string;
  currency: string;
}
