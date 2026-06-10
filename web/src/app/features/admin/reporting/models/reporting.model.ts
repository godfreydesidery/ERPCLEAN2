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

/** Export formats the backend accepts. */
export type ExportFormat = 'PDF' | 'XLSX' | 'CSV';
