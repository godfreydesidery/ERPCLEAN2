import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * TypeScript models mirroring the backend reporting DTOs (ADR-0018 D-1).
 * Every numeric money field is typed `number` — BigDecimal serialises as JSON number.
 * Always coerce with `+(v ?? 0)` before arithmetic or display (trial-balance numeric-money guard).
 */

/** Common header on every statement DTO. */
export interface StatementHeaderDto {
  companyId: string;
  companyName: string;
  currency: string;
  periodLabel: string;
  comparativeLabel: string;
  fromDate: string | null;
  toDate: string | null;
  asAtDate: string | null;
  generatedAt: string;
  /** The branch the statement is narrowed to, or null for the whole company. */
  branchUid: string | null;
  /** Branch name, "All branches", or "Company-level entries (no branch)". Never null. */
  branchLabel: string;
}

/**
 * Optional branch narrowing for the P&L / Balance Sheet / Cash-Flow reads (and their exports).
 * `branchUid` = that branch's journal lines only; `unassigned` = the company-level lines that carry
 * no branch. Neither = the whole company. Never both (the server answers 400).
 */
export interface StatementBranchFilter {
  branchUid?: string | null;
  unassigned?: boolean;
}

/** Every statement figure is a (current, comparative) pair (ADR-0018 D-1).
 * Fields are typed number | null to reflect that BigDecimal may serialize as null for zero
 * (defensive; the guard `+(v ?? 0)` in fmtMoney handles null at render time).
 */
export interface AmountPairDto {
  current: number | null;
  comparative: number | null;
}

/** Structural self-check bar for each statement (ADR-0018 D-5/D-6/D-7). */
export interface ReconciliationDto {
  label: string;
  computed: AmountPairDto;
  expected: AmountPairDto;
  difference: AmountPairDto;
  ties: boolean;
}

/** One detail line — one GL account (or a synthetic equity-fold line with null ids). */
export interface StatementLineDto {
  accountId: string | null;
  accountUid: string | null;
  accountCode: string | null;
  accountName: string;
  amounts: AmountPairDto;
}

/** A named section with detail lines + subtotal. */
export interface StatementSectionDto {
  sectionKey: string;
  title: string;
  lines: StatementLineDto[];
  subtotal: AmountPairDto;
}

/** Income Statement / P&L (FR-REP-01). */
export interface IncomeStatementDto {
  header: StatementHeaderDto;
  sections: StatementSectionDto[];
  grossProfit: AmountPairDto;
  netProfit: AmountPairDto;
  reconciliation: ReconciliationDto;
}

/** Balance Sheet (FR-REP-02). */
export interface BalanceSheetDto {
  header: StatementHeaderDto;
  sections: StatementSectionDto[];
  totalAssets: AmountPairDto;
  totalLiabilities: AmountPairDto;
  totalEquity: AmountPairDto;
  reconciliation: ReconciliationDto;
}

/** Cash-Flow Statement — indirect method (FR-REP-03). */
export interface CashFlowStatementDto {
  header: StatementHeaderDto;
  sections: StatementSectionDto[];
  netChangeInCash: AmountPairDto;
  openingCash: AmountPairDto;
  closingCash: AmountPairDto;
  reconciliation: ReconciliationDto;
}

/** One journal-line row in an account-ledger drill-down (FR-REP-04). */
export interface AccountLedgerRowDto {
  postingDate: string;
  sourceType: string;
  sourceRef: string;
  entryUid: string;
  lineMemo: string | null;
  debit: number | null;
  credit: number | null;
  runningBalance: number | null;
}

/** Account-ledger drill-down DTO — paginated (ADR-0018 D-3(d)). */
export interface AccountLedgerDto {
  header: StatementHeaderDto;
  accountId: string;
  accountUid: string;
  accountCode: string;
  accountName: string;
  openingBalance: number | null;
  rows: AccountLedgerRowDto[];
  closingBalance: number | null;
  page: number;
  size: number;
  totalElements: number;
}

// ── Statement of Changes in Equity ─────────────────────────────────────────

/**
 * One equity component (a 3xxx account, or one of the two earnings-fold lines). Amounts are
 * credit-positive so a row adds across: opening + profit + opening balances + capital + drawings
 * (negative) + transfers = closing. `ties` = that sum equals closing (the Balance Sheet's line).
 */
export interface EquityMovementRowDto {
  accountId: string | null;
  accountUid: string | null;
  accountCode: string | null;
  component: string;
  earningsFold: boolean;
  opening: number;
  profitForPeriod: number;
  openingBalancesPosted: number;
  capitalIntroduced: number;
  drawingsAndDividends: number;
  transfers: number;
  closing: number;
  ties: boolean;
}

export interface ChangesInEquityDto {
  header: StatementHeaderDto;
  company: ReportCompanyHeaderDto | null;
  rows: EquityMovementRowDto[];
  totals: EquityMovementRowDto;
  balanceSheetOpeningEquity: number;
  balanceSheetClosingEquity: number;
  profitForPeriod: number;
  reconciliation: ReconciliationDto;
  transfersCheck: ReconciliationDto;
}

// ── Financial Ratios ────────────────────────────────────────────────────────

export interface RatioInputDto {
  label: string;
  amount: number;
}

/** `value` is null — NOT zero — when the denominator is zero; `unavailableReason` then says why. */
export interface FinancialRatioDto {
  key: string;
  name: string;
  formula: string;
  inputs: RatioInputDto[];
  value: number | null;
  unit: 'x' | '%' | 'days';
  unavailableReason: string | null;
  note: string | null;
}

export interface FinancialRatiosDto {
  header: StatementHeaderDto;
  company: ReportCompanyHeaderDto | null;
  /** Long on the server — arrives as a JSON string. */
  periodDays: string | number;
  ratios: FinancialRatioDto[];
  incomeStatementTies: boolean;
  balanceSheetTies: boolean;
  notes: string[];
}

/** Export formats the backend accepts. */
export type ExportFormat = 'PDF' | 'XLSX' | 'CSV';
