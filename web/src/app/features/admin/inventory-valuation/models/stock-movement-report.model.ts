import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Period Stock Movement report (K9) — mirrors the backend DTOs on
 * `GET /api/v1/reports/stock-movement` exactly.
 *
 * Distinct from the present-moment {@link StockReportDto}: this one is genuinely period-windowed
 * (OPENING → PURCHASES → SALES → ADJUSTMENTS/OTHER → CLOSING).
 *
 * Wire types: BigDecimal quantities arrive as JSON NUMBERS, the paging fields as JSON numbers too
 * (they are not ids, so the Long-as-string rule does not apply). Coerce with `+(v ?? 0)` before
 * arithmetic or display — never call a string method on them.
 */

export type StockMovementReportMode = 'SUMMARY' | 'DETAIL';

/** One product row of the SUMMARY grid. `closing = opening + purchases − sales + adjustments`. */
export interface StockMovementSummaryRowDto {
  productCode: string | null;
  productName: string | null;
  /** Base-unit symbol, falling back to the unit code — a carton base makes bare numbers ambiguous. */
  unitLabel: string | null;
  openingQty: number | string | null;
  purchasesIn: number | string | null;
  /** Net sold in the period, expressed POSITIVE (the column is already a subtraction). */
  salesOut: number | string | null;
  adjustmentsOther: number | string | null;
  closingQty: number | string | null;
}

/** One movement row of the DETAIL grid. */
export interface StockMovementDetailRowDto {
  movementUid: string;
  /** ISO-8601 with the COMPANY's UTC offset — the zone the period window is computed in. */
  occurredAt: string;
  productCode: string | null;
  productName: string | null;
  unitLabel: string | null;
  movementType: string;
  /** 'IN' for a positive quantity, 'OUT' for a negative one. */
  direction: string | null;
  /** SIGNED quantity in base units (+ into stock, − out of stock). */
  quantity: number | string | null;
  /** On-hand after this movement; continuous across page boundaries. */
  runningBalance: number | string | null;
  reason: string | null;
  sourceDocumentType: string | null;
  sourceDocumentUid: string | null;
}

/** Period totals across every matching product — not just the current page. */
export interface StockMovementReportTotalsDto {
  openingQty: number | string | null;
  purchasesIn: number | string | null;
  salesOut: number | string | null;
  adjustmentsOther: number | string | null;
  closingQty: number | string | null;
}

export interface StockMovementReportDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  mode: StockMovementReportMode;
  /** The branch filter's display name; null when unfiltered (all branches). */
  branchName: string | null;
  /** The product filter's display name; null when unfiltered. */
  productLabel: string | null;
  currency: string;
  /** Populated in SUMMARY mode; empty otherwise. */
  summaryRows: StockMovementSummaryRowDto[];
  /** Populated in DETAIL mode; empty otherwise. */
  detailRows: StockMovementDetailRowDto[];
  totals: StockMovementReportTotalsDto;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  generatedAt: string;
}

/** Query filter for GET /api/v1/reports/stock-movement (and its /export counterpart). */
export interface StockMovementReportFilter {
  fromDate: string;
  toDate: string;
  mode: StockMovementReportMode;
  branchUid?: string | null;
  productUid?: string | null;
}
