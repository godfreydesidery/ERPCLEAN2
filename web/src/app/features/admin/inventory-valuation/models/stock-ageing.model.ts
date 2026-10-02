import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Stock Ageing — GET /api/v1/reports/stock-ageing. Mirrors StockAgeingReportDto.
 *
 * `bucketQty` / `bucketValue` follow `buckets` order and always add up to the row's onHand / value.
 * A null value means the item was never costed (unknown, not zero).
 */
export interface StockAgeingRowDto {
  productUid: string;
  productCode: string;
  productName: string;
  unitName: string | null;
  onHand: number;
  unitCost: number | null;
  value: number | null;
  bucketQty: number[];
  bucketValue: number[] | null;
  /** Stock older than the recorded movements — shown in the oldest bucket and flagged. */
  uncoveredQty: number;
  lastSaleDate: string | null;
  daysSinceLastSale: number | null;
}

export interface StockAgeingReportDto {
  company: ReportCompanyHeaderDto;
  asOf: string;
  branchName: string | null;
  currency: string;
  buckets: string[];
  rows: StockAgeingRowDto[];
  totalOnHand: number;
  totalBucketQty: number[];
  totalBucketValue: number[];
  totalValue: number;
  unvaluedProducts: number;
  uncoveredProducts: number;
  negativeStockProducts: number;
  neverSoldProducts: number;
  /** True for a past as-of date: quantities then, cost today. */
  valuedAtCurrentCost: boolean;
  generatedAt: string;
}

export interface StockAgeingFilter {
  asOf?: string | null;
  branchUid?: string | null;
}
