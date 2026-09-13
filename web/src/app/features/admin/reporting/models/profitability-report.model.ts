import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Profitability Report (K-2026-08-30 #2). Mirrors the backend ProfitabilityReportDto exactly.
 *
 * BigDecimal fields arrive as JSON numbers — coerce with `+(v ?? 0)` before arithmetic. But
 * `costOfSales` and `profit` are NULLABLE and null is a real answer: it means some of that
 * product's stock was sold before it had ever been costed, so neither figure is reportable.
 * Rendering either through a money formatter that turns null into 0.00 would report the whole sale
 * as profit — the defect the honest-margin fix already corrected once on the Sales Report.
 */

export interface ProfitabilityRowDto {
  productCode: string | null;
  productName: string | null;
  qtySold: number | string | null;
  /** VAT-INCLUSIVE turnover — what the customer was charged. */
  grossSales: number | string | null;
  vatAmount: number | string | null;
  /** grossSales − vatAmount: the revenue profit is measured against. */
  netAmount: number | string | null;
  /** null = sold before it was ever costed. NOT zero. */
  costOfSales: number | string | null;
  /** netAmount − costOfSales, or null whenever the cost is null. */
  profit: number | string | null;
}

export interface ProfitabilityTotalsDto {
  qtySold: number | string | null;
  grossSales: number | string | null;
  vatAmount: number | string | null;
  netAmount: number | string | null;
  /** Sums only the rows whose cost is known — see rowsWithUnknownCost. */
  costOfSales: number | string | null;
  profit: number | string | null;
  /** Rows left out of costOfSales and profit. >0 ⇒ the foot is partial and must say so. */
  rowsWithUnknownCost: number;
}

export interface ProfitabilityReportDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  /** null = every branch. */
  branchName: string | null;
  currency: string;
  rows: ProfitabilityRowDto[];
  totals: ProfitabilityTotalsDto;
  generatedAt: string;
}

/** Query filter for GET /api/v1/reports/profitability (and its /export counterpart). */
export interface ProfitabilityReportFilter {
  fromDate: string;
  toDate: string;
  branchUid?: string | null;
}

/**
 * Money that may genuinely be UNKNOWN — an em dash, never "0.00". A real zero still prints 0.00.
 * Same rule as the product-stock reports; it exists here because an unknown COST printed as zero
 * silently becomes profit.
 */
export function formatProfitAmount(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = +v;
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    : '—';
}

/** Quantity in the product's base unit; whole numbers print clean, fractions keep 3 dp. */
export function formatProfitQty(v: number | string | null | undefined): string {
  const n = +(v ?? 0);
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 3 })
    : '0';
}
