import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Reorder Report — GET /api/v1/reports/reorder. Mirrors ReorderReportDto.
 *
 * Quantities and amounts are BigDecimal on the wire → JSON numbers. When `costVisible` is false the
 * caller lacks INVENTORY.VALUATION.VIEW: lastCost / estimatedOrderValue are WITHHELD (and the
 * screen drops those columns), which is not the same as an item with no cost on record.
 */
export interface ReorderRowDto {
  productUid: string;
  productCode: string;
  productName: string;
  unitName: string | null;
  branchName: string;
  locationCode: string;
  locationName: string;
  onHand: number;
  reorderLevel: number;
  maxQty: number | null;
  shortfall: number;
  suggestedOrderQty: number;
  supplierUid: string | null;
  supplierName: string | null;
  lastCost: number | null;
  estimatedOrderValue: number | null;
}

export interface ReorderReportDto {
  company: ReportCompanyHeaderDto;
  branchName: string | null;
  supplierName: string | null;
  currency: string;
  costVisible: boolean;
  rows: ReorderRowDto[];
  itemCount: number;
  estimatedOrderValue: number | null;
  rowsWithoutCost: number;
  generatedAt: string;
}

export interface ReorderReportFilter {
  branchUid?: string | null;
  supplierUid?: string | null;
}
