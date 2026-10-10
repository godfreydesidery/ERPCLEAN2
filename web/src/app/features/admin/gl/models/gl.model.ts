/**
 * GL feature models. All Long/BigDecimal fields arrive as strings on the wire.
 * Mirrors AccountDto, JournalEntryDto, FiscalPeriodDto, GlConfigDto, TrialBalanceDto
 * from the backend GL controllers.
 */

// ── Enums (string unions) ────────────────────────────────────────────────────

export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'INCOME' | 'EXPENSE';
export type NormalBalance = 'DEBIT' | 'CREDIT';
export type PeriodStatus = 'OPEN' | 'CLOSED';
export type JournalSourceType =
  | 'MANUAL'
  | 'SALES'
  | 'SALES_REVERSAL'
  | 'OPENING_BALANCE'
  | 'PURCHASE'
  | 'PURCHASE_REVERSAL';

// ── Chart of Accounts ────────────────────────────────────────────────────────

export interface AccountDto {
  id: string;
  uid: string;
  companyId: string;
  accountCode: string;
  name: string;
  accountType: AccountType;
  normalBalance: NormalBalance;
  active: boolean;
  status: string;
}

export interface CreateAccountRequest {
  companyUid: string;
  accountCode: string;
  name: string;
  accountType: AccountType;
}

export interface UpdateAccountRequest {
  name: string;
  active: boolean;
}

// ── Journal Entries ──────────────────────────────────────────────────────────

export interface JournalLineDto {
  lineNo: number;
  accountCode: string;
  accountName: string;
  debitAmount: string;
  creditAmount: string;
  currency: string;
  lineMemo: string | null;
}

export interface JournalEntryDto {
  id: string;
  uid: string;
  companyId: string;
  batchNumber: string;
  postingDate: string;
  description: string;
  sourceType: JournalSourceType;
  sourceRef: string | null;
  reversalOfId: string | null;
  lines: JournalLineDto[];
  /** Source document number (GRN-0007, INV-0453) when the server could read it (ACC-19). */
  documentRef?: string | null;
}

/** Optional journal-list filters (ACC-19). Dates are yyyy-MM-dd. */
export interface JournalFilter {
  from?: string;
  to?: string;
  sourceType?: string;
  accountUid?: string;
  q?: string;
}

export interface PostJournalLineRequest {
  accountUid: string;
  debitAmount: string;
  creditAmount: string;
  lineMemo?: string;
}

export interface PostJournalRequest {
  companyUid: string;
  postingDate: string;
  description: string;
  sourceType: JournalSourceType;
  sourceRef?: string;
  lines: PostJournalLineRequest[];
  /** Optional branch uid; omitted = a company-level journal. Must be a branch the caller works in. */
  branchUid?: string;
}

// ── Fiscal Periods ───────────────────────────────────────────────────────────

export interface FiscalPeriodDto {
  id: string;
  uid: string;
  companyId: string;
  periodNo: number;
  startDate: string;
  endDate: string;
  status: PeriodStatus;
}

export interface FiscalYearDto {
  id: string;
  uid: string;
  companyId: string;
  yearCode: string;
  startMonth: number;
  startDate: string;
  endDate: string;
  status: PeriodStatus;
  /** Populated on close (ADR-0019 D-7); null when OPEN or after reopen. */
  closedAt: string | null;
  closedBy: string | null;
  closingJournalUid: string | null;
}

export interface OpenFiscalYearRequest {
  companyUid: string;
  yearCode: string;
  startMonth: number;
  calendarYear: number;
}

// ── GL Config ────────────────────────────────────────────────────────────────

export interface GlConfigDto {
  uid: string;
  configKey: string | null;
  accountId: string;
  accountCode: string;
  accountName: string;
}

export interface SetGlConfigRequest {
  companyUid: string;
  configKey: string;
  accountUid: string;
}

// ── Trial Balance ────────────────────────────────────────────────────────────

export interface TrialBalanceRowDto {
  accountCode: string;
  accountName: string;
  accountType: AccountType;
  totalDebit: string;
  totalCredit: string;
  net: string;
}

export interface TrialBalanceDto {
  rows: TrialBalanceRowDto[];
  totalDebits: string;
  totalCredits: string;
}

// ── Posting exceptions (ACC-02) ──────────────────────────────────────────────

/**
 * An automatic GL posting (sale, COGS, goods receipt, reversal…) that failed and was swallowed so
 * its document could stand. BigDecimal `amount` arrives as a JSON number; format with formatMoney.
 */
export interface GlPostingExceptionDto {
  uid: string;
  kind: string;
  sourceType: string | null;
  sourceRef: string | null;
  documentNumber: string | null;
  postingDate: string | null;
  amount: number | string | null;
  reason: string | null;
  failedAt: string;
  status: 'OPEN' | 'RESOLVED';
  resolvedAt: string | null;
  resolvedBy: string | null;
  outcome: 'REPOSTED' | 'ALREADY_POSTED' | null;
  journalEntryUid: string | null;
  batchNumber: string | null;
}

export interface GlPostingRepostResultDto {
  exceptionUid: string;
  outcome: 'REPOSTED' | 'ALREADY_POSTED';
  journalEntryUid: string | null;
  batchNumber: string | null;
  postingDate: string | null;
}

export interface GlPostingExceptionFilter {
  sourceType?: string;
  from?: string;
  to?: string;
  includeResolved?: boolean;
}

/** Sales-vs-GL revenue/VAT tie-out (ACC-02). Amounts are JSON numbers. */
export interface GlSalesTieOutDto {
  from: string;
  to: string;
  salesNet: number | string;
  salesVat: number | string;
  glRevenue: number | string;
  glVat: number | string;
  revenueDifference: number | string;
  vatDifference: number | string;
}
