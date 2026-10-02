import { ReportCompanyHeaderDto } from '../../../models/report-company-header.model';

/**
 * Purchase reports — mirror the backend DTOs on `GET /api/v1/reports/purchases/...` exactly.
 *
 * Wire types: BigDecimal amounts and quantities arrive as JSON NUMBERS (typed `number`), counts and
 * paging fields as JSON numbers too. Timestamps are ISO-8601 strings in the company's UTC offset;
 * dates are `YYYY-MM-DD` strings. A `null` money field means "not shown to you" or "not known" —
 * never render it as 0.00.
 */

export type Amount = number | null;

// ── Goods Received Register ──────────────────────────────────────────────────

export interface GoodsReceivedRegisterRowDto {
  /** `RECEIPT`, or `VOID` for the negative reversal of a receipt voided in the period. */
  entryType: 'RECEIPT' | 'VOID';
  entryAt: string | null;
  receiptNumber: string | null;
  receiptUid: string;
  orderNumber: string | null;
  /** Recorded with "Receive Without Order" — its order was raised automatically. */
  direct: boolean;
  supplierCode: string | null;
  supplierName: string | null;
  branchName: string | null;
  productCode: string | null;
  productName: string | null;
  unitName: string | null;
  quantity: number;
  unitCost: number;
  value: number;
  currency: string;
}

export interface GoodsReceivedRegisterTotalsDto {
  receipts: number;
  voids: number;
  lines: number;
  value: number;
  rowsInOtherCurrency: number;
}

export interface GoodsReceivedRegisterDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  branchName: string | null;
  supplierName: string | null;
  productName: string | null;
  currency: string;
  rows: GoodsReceivedRegisterRowDto[];
  totals: GoodsReceivedRegisterTotalsDto;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  generatedAt: string;
}

export interface GoodsReceivedRegisterFilter {
  fromDate: string;
  toDate: string;
  branchUid: string | null;
  supplierUid: string | null;
  productUid: string | null;
}

// ── Purchases by Supplier ────────────────────────────────────────────────────

export interface PurchasesBySupplierRowDto {
  supplierCode: string | null;
  supplierName: string | null;
  currency: string;
  receipts: number;
  receivedValue: number;
  returnsValue: Amount;
  netPurchases: Amount;
  billedAmount: Amount;
  unpaidAmount: Amount;
}

export interface PurchasesBySupplierTotalsDto {
  receipts: number;
  receivedValue: number;
  returnsValue: Amount;
  netPurchases: Amount;
  billedAmount: Amount;
  unpaidAmount: Amount;
  rowsInOtherCurrency: number;
}

export interface PurchasesBySupplierDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  branchName: string | null;
  currency: string;
  returnsShown: boolean;
  billsShown: boolean;
  rows: PurchasesBySupplierRowDto[];
  totals: PurchasesBySupplierTotalsDto;
  generatedAt: string;
}

export interface PeriodBranchFilter {
  fromDate: string;
  toDate: string;
  branchUid: string | null;
}

// ── Open Purchase Orders ─────────────────────────────────────────────────────

export interface OpenPurchaseOrderRowDto {
  orderNumber: string | null;
  orderUid: string;
  orderDate: string | null;
  expectedDate: string | null;
  supplierCode: string | null;
  supplierName: string | null;
  branchName: string | null;
  productCode: string | null;
  productName: string | null;
  unitName: string | null;
  orderedQty: number;
  receivedQty: number;
  outstandingQty: number;
  unitCost: number;
  outstandingValue: number;
  currency: string;
  ageDays: number;
  overdue: boolean;
}

export interface OpenPurchaseOrdersTotalsDto {
  orders: number;
  lines: number;
  outstandingValue: number;
  rowsInOtherCurrency: number;
}

export interface OpenPurchaseOrdersDto {
  company: ReportCompanyHeaderDto;
  asOfDate: string;
  branchName: string | null;
  supplierName: string | null;
  currency: string;
  rows: OpenPurchaseOrderRowDto[];
  totals: OpenPurchaseOrdersTotalsDto;
  generatedAt: string;
}

export interface OpenPurchaseOrdersFilter {
  asOfDate: string | null;
  branchUid: string | null;
  supplierUid: string | null;
}

// ── Purchase Price Variance ──────────────────────────────────────────────────

export interface PurchasePriceVarianceRowDto {
  receivedAt: string | null;
  receiptNumber: string | null;
  receiptUid: string;
  orderNumber: string | null;
  supplierCode: string | null;
  supplierName: string | null;
  productCode: string | null;
  productName: string | null;
  unitName: string | null;
  receivedQty: number;
  poPrice: number;
  receiptCost: number;
  receiptVariancePerUnit: number;
  receiptVarianceTotal: number;
  receiptVariancePct: Amount;
  billedQty: Amount;
  billPrice: Amount;
  billVariancePerUnit: Amount;
  billVarianceTotal: Amount;
  billVariancePct: Amount;
  currency: string;
}

export interface PurchasePriceVarianceTotalsDto {
  lines: number;
  receiptVarianceTotal: number;
  billVarianceTotal: Amount;
  rowsInOtherCurrency: number;
}

export interface PurchasePriceVarianceDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  branchName: string | null;
  supplierName: string | null;
  currency: string;
  billsShown: boolean;
  rows: PurchasePriceVarianceRowDto[];
  totals: PurchasePriceVarianceTotalsDto;
  generatedAt: string;
}

export interface PeriodBranchSupplierFilter {
  fromDate: string;
  toDate: string;
  branchUid: string | null;
  supplierUid: string | null;
}

// ── Display helpers (shared by the four screens) ─────────────────────────────

/** Money at 2 dp; null/undefined renders as an em dash, never as 0.00. */
export function fmtMoney(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = +v;
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    : '—';
}

/** Quantity: whole numbers clean, up to 3 dp kept so weighed goods never print as a round number. */
export function fmtQty(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = +v;
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 3 })
    : '—';
}

/** Percentage at 2 dp with a sign, or an em dash. */
export function fmtPct(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = +v;
  if (!Number.isFinite(n)) return '—';
  const s = n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return `${n > 0 ? '+' : ''}${s}%`;
}

/** Today as `YYYY-MM-DD` in the browser's zone. */
export function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function firstOfMonthIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
}

/**
 * The calendar day of a server timestamp, as `dd/MM/yyyy`. Read from the string itself rather than
 * through a Date: the server already renders it in the COMPANY's offset, and re-zoning it to the
 * browser's could move a late-evening receipt onto the next day.
 */
export function fmtDay(iso: string | null | undefined): string {
  if (!iso || iso.length < 10) return '—';
  const [y, m, d] = iso.slice(0, 10).split('-');
  return `${d}/${m}/${y}`;
}
