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
