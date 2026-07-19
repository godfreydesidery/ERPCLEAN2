import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Sales Report models (SAM Electronix go-live). Mirrors the backend SalesReportDto exactly.
 * BigDecimal fields arrive as JSON numbers — coerce with `+(v ?? 0)` before arithmetic or display
 * (numeric-money guard, see stock-valuation-report.component / income-statement.component).
 */

export interface SalesReportRowDto {
  productCode: string;
  productName: string;
  currentStock: number | string | null;
  qtySold: number | string | null;
  discount: number | string | null;
  vat: number | string | null;
  margin: number | string | null;
  amount: number | string | null;
}

export interface SalesReportTotalsDto {
  qtySold: number | string | null;
  discount: number | string | null;
  vat: number | string | null;
  margin: number | string | null;
  amount: number | string | null;
}

export interface SalesReportDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  /** Resolved display name of the active supplier filter, or null when unfiltered. */
  supplierName: string | null;
  /** Resolved display name of the active agent filter, or null when unfiltered. */
  agentName: string | null;
  /** Resolved display name of the active route filter, or null when unfiltered. */
  routeName: string | null;
  currency: string;
  rows: SalesReportRowDto[];
  totals: SalesReportTotalsDto;
  generatedAt: string;
}

/** Query filter for GET /api/v1/reports/sales (and its /export counterpart). */
export interface SalesReportFilter {
  fromDate: string;
  toDate: string;
  agentUid?: string | null;
  routeUid?: string | null;
  supplierUid?: string | null;
  branchUid?: string | null;
}
