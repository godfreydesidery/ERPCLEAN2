import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Sales Summary — GET /api/v1/reports/sales-summary. Mirrors SalesSummaryReportDto.
 *
 * Amounts are BigDecimal on the wire, so they arrive as JSON NUMBERS. A null cost / margin /
 * margin % means UNKNOWN (stock sold before it was ever costed), never zero.
 */
export type SalesSummaryGroupBy = 'CUSTOMER' | 'AGENT' | 'ROUTE' | 'BRANCH' | 'DAY' | 'CASHIER';

export interface SalesSummaryRowDto {
  /** The group's uid, or the ISO date for a DAY row; null for "(no route)" / "(not recorded)". */
  groupKey: string | null;
  groupLabel: string | null;
  groupCode: string | null;
  invoiceCount: number;
  qty: number;
  grossAmount: number;
  discount: number;
  vatAmount: number;
  netAmount: number;
  costOfSales: number | null;
  margin: number | null;
  marginPercent: number | null;
  unknownCostItems: number;
}

export interface SalesSummaryTotalsDto {
  invoiceCount: number;
  qty: number;
  grossAmount: number;
  discount: number;
  vatAmount: number;
  netAmount: number;
  costOfSales: number | null;
  margin: number | null;
  marginPercent: number | null;
  groupsWithUnknownCost: number;
  unknownCostItems: number;
}

export interface SalesSummaryReportDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  groupBy: SalesSummaryGroupBy;
  branchName: string | null;
  currency: string;
  rows: SalesSummaryRowDto[];
  totals: SalesSummaryTotalsDto;
  generatedAt: string;
  /**
   * False when the caller may not see cost (no INVENTORY.VALUATION.VIEW): cost of sales, margin and
   * margin % are null because they were withheld. Absent from older servers.
   */
  costVisible?: boolean;
}

export interface SalesSummaryFilter {
  fromDate: string;
  toDate: string;
  groupBy: SalesSummaryGroupBy;
  branchUid?: string | null;
}

/** The "Group by" choices, worded the way the question is asked. */
export const SALES_SUMMARY_GROUPINGS: readonly { value: SalesSummaryGroupBy; label: string; column: string }[] = [
  { value: 'CUSTOMER', label: 'Customer (sales by customer)', column: 'Customer' },
  { value: 'AGENT', label: 'Sales agent (agent performance)', column: 'Agent' },
  { value: 'ROUTE', label: 'Route (sales by route)', column: 'Route' },
  { value: 'BRANCH', label: 'Branch', column: 'Branch' },
  { value: 'DAY', label: 'Day (daily sales)', column: 'Date' },
  { value: 'CASHIER', label: 'Cashier (who rang the sale)', column: 'Cashier' },
];
