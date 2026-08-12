import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Stock Report models (SAM Electronix go-live) — an operational print report distinct from the
 * GL-reconciled Stock Valuation Report (StockValuationReportDto). Mirrors the backend
 * StockReportDto exactly. BigDecimal fields arrive as JSON numbers — coerce with `+(v ?? 0)`
 * before arithmetic or display (numeric-money guard).
 */

export interface StockReportRowDto {
  productCode: string;
  productName: string;
  quantityOnHand: number | string | null;
  buyingPrice: number | string | null;
  sellingPrice: number | string | null;
  value: number | string | null;
}

export interface StockReportDto {
  company: ReportCompanyHeaderDto;
  /** Branch the listing was narrowed to; null when it spans the whole company. */
  branchUid: string | null;
  branchName: string | null;
  /**
   * Never null. The branch's name when one was filtered, "All branches" when none was.
   * Render this verbatim — the server authors the phrase so the screen, the PDF and any
   * integration all say the same thing about the report's scope.
   */
  branchLabel: string;
  currency: string;
  rows: StockReportRowDto[];
  totalValue: number | string | null;
  generatedAt: string;
}
