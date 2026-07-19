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
  currency: string;
  rows: StockReportRowDto[];
  totalValue: number | string | null;
  generatedAt: string;
}
