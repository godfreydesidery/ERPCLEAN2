import { PayrollRunStatus } from './hr-payroll.model';

/**
 * Payroll Statutory Summary (FR-HR-23) — mirrors the backend records in
 * com.erp.modules.hr.domain.dto. Long ids arrive as JSON strings; BigDecimal amounts as JSON
 * numbers (typed `number`, never `string`).
 */

export type StatutoryExportFormat = 'PDF' | 'XLSX' | 'CSV';

export interface StatutorySummaryDto {
  runUid: string;
  runNumber: string;
  periodYear: number;
  periodMonth: number;
  payDate: string;
  status: PayrollRunStatus;
  employeeCount: number;
  grossTotal: number;
  payeTotal: number;
  nssfEmployeeTotal: number;
  nssfEmployerTotal: number;
  wcfTotal: number;
  sdlTotal: number;
  heslbTotal: number;
  netTotal: number;
  employerCostTotal: number;
}

export interface StatutoryLineDto {
  employeeNumber: string;
  employeeName: string;
  departmentName: string | null;
  tin: string | null;
  nssfNumber: string | null;
  heslbNumber: string | null;
  grossAmount: number;
  payeAmount: number;
  nssfEmployeeAmount: number;
  nssfEmployerAmount: number;
  wcfAmount: number;
  sdlAmount: number;
  heslbAmount: number;
  netAmount: number;
}

export interface PayrollRunStatutoryReportDto {
  companyId: string;
  summary: StatutorySummaryDto;
  lines: StatutoryLineDto[];
  /** True while the run is not approved (figures can change) or after it was reversed. */
  provisional: boolean;
  currency: string;
  generatedAt: string;
}

export interface StatutoryTotalsDto {
  runCount: number;
  payslipCount: number;
  grossTotal: number;
  payeTotal: number;
  nssfEmployeeTotal: number;
  nssfEmployerTotal: number;
  wcfTotal: number;
  sdlTotal: number;
  heslbTotal: number;
  netTotal: number;
  employerCostTotal: number;
}

export interface PayrollStatutoryPeriodReportDto {
  fromDate: string;
  toDate: string;
  runs: StatutorySummaryDto[];
  totals: StatutoryTotalsDto;
  /** Runs in the window still DRAFT / CALCULATED — not counted. */
  pendingRunCount: number;
  /** Reversed runs in the window — not counted. */
  reversedRunCount: number;
  currency: string;
  generatedAt: string;
}

// ── ACC-07: paying statutory liabilities ─────────────────────────────────────────

export type StatutoryLiability = 'PAYE' | 'NSSF' | 'WCF' | 'SDL' | 'HESLB';

/** GET /hr/payroll/statutory-payments/outstanding — the ledger balance still owed per liability. */
export interface StatutoryLiabilityBalanceDto {
  liability: StatutoryLiability;
  accountCode: string;
  accountName: string;
  /** Wire: number (BigDecimal) — coerce with +v. */
  outstanding: number | string;
  /** Wire: string (Long) — the caller's company, for the cash/bank account picker. */
  companyId: string;
}

/** POST /hr/payroll/statutory-payments — DR the liability / CR the chosen cash or bank account. */
export interface RecordStatutoryPaymentRequest {
  liability: StatutoryLiability;
  cashBankAccountUid: string;
  paymentDate: string;
  amount: string;
  reference?: string;
}

/** The cash transaction the payment created (only the fields this screen reads). */
export interface StatutoryPaymentResultDto {
  uid: string;
  txnNumber: string;
}
