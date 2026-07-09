/**
 * Tax module models (VAT returns + WHT).
 * All Long/BigDecimal fields arrive as numbers on the wire — coerce defensively with +v.
 * NEVER call .startsWith/.trim on a money value.
 * Mirrors backend DTOs exactly (ADR-0017).
 */

// ── Enums (string unions) ────────────────────────────────────────────────────

export type VatReturnStatus = 'DRAFT' | 'FILED';
export type VatAdjustmentReason =
  | 'BAD_DEBT_RELIEF'
  | 'PRIOR_PERIOD_CORRECTION'
  | 'CREDIT_NOTE_VAT'
  | 'DEBIT_NOTE_VAT'
  | 'OTHER';
export type VatAdjustmentSign = 'INCREASE' | 'DECREASE';
export type WhtKind = 'WHT_ON_PAYMENT' | 'WHT_ON_RECEIPT';

// ── VAT Return Band ───────────────────────────────────────────────────────────

/**
 * VatReturnBandDto — one tax-band row on the return face.
 * taxableBase and outputVat arrive as numbers; coerce with +v.
 */
export interface VatReturnBandDto {
  /** Wire: number */
  id: string;
  /** Wire: number */
  vatReturnId: string;
  /** e.g. STANDARD, ZERO_RATED, EXEMPT */
  taxBand: string;
  /** Wire: number — coerce with +(dto.taxableBase) */
  taxableBase: number | string;
  /** Wire: number — coerce with +(dto.outputVat) */
  outputVat: number | string;
}

// ── VAT Return ────────────────────────────────────────────────────────────────

/**
 * VatReturnDto — the return face.
 * All BigDecimal fields arrive as numbers; coerce with +v.
 */
export interface VatReturnDto {
  /** Wire: number */
  id: string;
  uid: string;
  /** Wire: number */
  companyId: string;
  returnNumber: string;
  periodYear: number;
  periodMonth: number;
  periodStart: string;
  periodEnd: string;
  dueDate: string;
  status: VatReturnStatus;
  /** Wire: number — coerce with +(dto.outputVat) */
  outputVat: number | string;
  /** Wire: number — coerce with +(dto.inputVat) */
  inputVat: number | string;
  /** Wire: number — coerce with +(dto.adjustmentsTotal) */
  adjustmentsTotal: number | string;
  /** Wire: number — coerce with +(dto.openingCredit) */
  openingCredit: number | string;
  /** Wire: number — coerce with +(dto.netVat); positive = payable to TRA, negative = credit c/f */
  netVat: number | string;
  /** Wire: number — coerce with +(dto.closingCredit) */
  closingCredit: number | string;
  /** Wire: number | null */
  priorReturnId: string | null;
  filingReference: string | null;
  filingDate: string | null;
  postedJournalUid: string | null;
  filedAt: string | null;
  /** Wire: number | null */
  filedBy: string | null;
  /** Wire: number | null — sum of taxable bases (ex-VAT) across bands. Coerce with +v. */
  salesTurnover: number | string | null;
  /** Wire: number | null — input-side turnover; out of scope for now, render "—" if null. */
  purchasesTurnover: number | string | null;
  /** Wire: number | null — zero-rated portion of salesTurnover. Coerce with +v. */
  zeroRatedSales: number | string | null;
  /** Wire: number | null — exempt portion of salesTurnover. Coerce with +v. */
  exemptSales: number | string | null;
  bands: VatReturnBandDto[];
}

// ── VAT Adjustment ─────────────────────────────────────────────────────────────

/**
 * VatAdjustmentDto — one manual adjustment line.
 * amount arrives as number; coerce with +v.
 */
export interface VatAdjustmentDto {
  /** Wire: number */
  id: string;
  uid: string;
  /** Wire: number */
  vatReturnId: string;
  /** Wire: number */
  companyId: string;
  reason: VatAdjustmentReason;
  sign: VatAdjustmentSign;
  /** Wire: number — coerce with +(dto.amount) */
  amount: number | string;
  narrative: string | null;
}

// ── Request DTOs ──────────────────────────────────────────────────────────────

export interface OpenVatReturnRequest {
  companyUid: string;
  periodYear: number;
  periodMonth: number;
}

export interface FileVatReturnRequest {
  filingReference: string;
  filingDate: string;
}

export interface AddVatAdjustmentRequest {
  reason: VatAdjustmentReason;
  sign: VatAdjustmentSign;
  /** Send as string representation of decimal */
  amount: string;
  narrative?: string;
}

// ── WHT Type ──────────────────────────────────────────────────────────────────

/**
 * WhtTypeDto — a WHT rate master record.
 * ratePct arrives as number; coerce with +v.
 */
export interface WhtTypeDto {
  /** Wire: number */
  id: string;
  uid: string;
  /** Wire: number */
  companyId: string;
  code: string;
  name: string;
  kind: WhtKind;
  /** Wire: number — coerce with +(dto.ratePct) */
  ratePct: number | string;
  active: boolean;
}

export interface CreateWhtTypeRequest {
  companyUid: string;
  code: string;
  name: string;
  kind: WhtKind;
  /** Send as string representation of decimal */
  ratePct: string;
}

export interface UpdateWhtTypeRequest {
  name: string;
  /** Send as string representation of decimal */
  ratePct: string;
  active: boolean;
}

// ── WHT Register ──────────────────────────────────────────────────────────────

/**
 * WhtRegisterRowDto — one certificate row in the period register.
 * taxableBase and whtAmount arrive as numbers; coerce with +v.
 */
export interface WhtRegisterRowDto {
  whtNumber: string;
  kind: WhtKind;
  partyKind: string;
  partyName: string;
  sourceRef: string;
  /** Wire: number — coerce with +(dto.taxableBase) */
  taxableBase: number | string;
  /** Wire: number — coerce with +(dto.whtAmount) */
  whtAmount: number | string;
  certificateDate: string;
}

/**
 * WhtRegisterDto — the period register.
 * totalPayable and totalReceivable arrive as numbers; coerce with +v.
 */
export interface WhtRegisterDto {
  /** Wire: number */
  companyId: string;
  periodStart: string;
  periodEnd: string;
  payableRows: WhtRegisterRowDto[];
  /** Wire: number — coerce with +(dto.totalPayable) */
  totalPayable: number | string;
  receivableRows: WhtRegisterRowDto[];
  /** Wire: number — coerce with +(dto.totalReceivable) */
  totalReceivable: number | string;
}
