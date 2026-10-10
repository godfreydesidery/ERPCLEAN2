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
export type AgeingBucket = 'CURRENT' | 'D1_30' | 'D31_60' | 'D61_90' | 'D90_PLUS';
/** Exactly the values the ar_receipts tender CHECK admits (V11) — there is no 'OTHER'. */
export type TenderType = 'CASH' | 'CHEQUE' | 'BANK_TRANSFER' | 'MOBILE_MONEY' | 'CARD';

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
  /** Read-time fill: the customer's uid / code / name. Absent on older servers. */
  customerUid?: string | null;
  customerCode?: string | null;
  customerName?: string | null;
}

// ── AR Receipt ────────────────────────────────────────────────────────────────

export interface AllocationLineDto {
  arInvoiceUid: string;
  /** Wire: number or string */
  allocatedAmount: number | string;
}

export interface ArReceiptDto {
  uid: string;
  /** Wire: JSON string (Long). The receipt's company — used to load the customer's open items. */
  companyId?: string;
  customerId: string;
  receiptNumber: string;
  receiptDate: string;
  /** Wire: number or string */
  amount: number | string;
  /** Wire: number or string */
  unallocatedAmount: number | string;
  currency: string;
  tenderType: TenderType;
  /** Bank / M-Pesa / cheque reference typed at the counter. */
  bankReference?: string | null;
  status?: string;
  allocations: AllocationLineDto[];
  /** Read-time fill: the customer's uid / code / name. Absent on older servers. */
  customerUid?: string | null;
  customerCode?: string | null;
  customerName?: string | null;
  /** ARC-04: when the receipt was reversed (bounced cheque or "Reverse receipt"); null when live. */
  reversedAt?: string | null;
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
  /**
   * Optional: the cash / bank / M-Pesa account the money landed in (ARC-05). Omitted = the
   * company's default cash/bank account (ADR-0016 D-10).
   */
  cashBankAccountUid?: string;
  /**
   * How the money is applied (ARC-20). MANUAL = exactly `allocations`, the rest on account (an
   * empty list keeps it all on account); AUTO = oldest-first, send no lines; ON_ACCOUNT = none
   * applied. Omitted = the old server rule (no lines → AUTO), so this screen always sends MANUAL.
   */
  allocationMode?: 'AUTO' | 'MANUAL' | 'ON_ACCOUNT';
  /**
   * Optional WHT_ON_RECEIPT capture (ADR-0017 D-9).
   * When set, the cash DR is reduced by whtAmount and a WHT receivable leg is posted.
   */
  whtTypeUid?: string;
  /** Send as string. */
  whtAmount?: string;
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
  /** One five-bucket block per currency, base currency first. */
  ageing: ArAgeingBucketDto[];
  openItems: ArInvoiceDto[];
  recentReceipts: ArReceiptDto[];
  /**
   * Outstanding per currency, base first. `totalOutstanding` is the base-currency part only;
   * amounts in different currencies are never added together. Wire: numbers.
   */
  totalsByCurrency?: Record<string, number | string>;
}

// ── Ageing row (standalone ageing endpoint) ───────────────────────────────────
// One row per customer PER CURRENCY: a customer owing in TZS and USD has two rows.

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

/**
 * A foreign-currency amount with no reliable base-currency value (old rows whose stored rate is
 * the V62 back-fill of 1). Shown in its own currency; never part of a base-currency total.
 */
export interface ArUnconvertedAmountDto {
  currency: string;
  /** Wire: number */
  amount: number;
  itemCount: number;
}

export interface ArBalanceDto {
  customerId: string;
  /** Base-currency total over reliable rows. Wire: number or string */
  balance: number | string;
  currency: string;
  /** Foreign amounts left out of balance (per currency). Absent on older servers. */
  unconverted?: ArUnconvertedAmountDto[];
}
