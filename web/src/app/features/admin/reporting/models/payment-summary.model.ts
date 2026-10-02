import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Payment Summary / daily cash-up — GET /api/v1/reports/payment-summary. Mirrors
 * PaymentSummaryReportDto. Amounts are JSON numbers, already net of change given.
 */
export interface PaymentSummaryRowDto {
  date: string;
  cashierUid: string | null;
  cashierName: string | null;
  currency: string;
  cash: number;
  mobileMoney: number;
  card: number;
  cheque: number;
  total: number;
  payments: number;
}

/** One per currency — amounts in different currencies are never added together. */
export interface PaymentSummaryTotalsDto {
  currency: string;
  cash: number;
  mobileMoney: number;
  card: number;
  cheque: number;
  total: number;
  payments: number;
}

export interface CashierRefDto {
  uid: string;
  name: string;
}

export interface PaymentSummaryReportDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  branchName: string | null;
  cashierName: string | null;
  baseCurrency: string;
  rows: PaymentSummaryRowDto[];
  /** Base currency first. */
  totals: PaymentSummaryTotalsDto[];
  /** Everyone who took a payment in the period — the cashier picker's options. */
  cashiers: CashierRefDto[];
  generatedAt: string;
}

export interface PaymentSummaryFilter {
  fromDate: string;
  toDate: string;
  branchUid?: string | null;
  cashierUid?: string | null;
}
