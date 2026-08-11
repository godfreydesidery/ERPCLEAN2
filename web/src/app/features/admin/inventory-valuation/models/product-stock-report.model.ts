import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Product List / Stock Value reports — mirrors ProductStockReportDto on
 * GET /api/v1/stock/reports/{product-list|stock-value}.
 *
 * Money and quantity arrive as JSON NUMBERS (BigDecimal serialises as a number; only Long ids
 * become strings), but `null` is a REAL answer here rather than a zero: a null buyingPrice means the
 * product has never been costed, a null sellingPrice means it has never been priced. Rendering
 * either as "0.00" would read as "free" — always format money through `formatStockAmount()`.
 */
export interface ProductStockRowDto {
  productCode: string | null;
  productName: string | null;
  /** Preferred supplier's display name; null when the product has no supplier set. */
  supplierName: string | null;
  quantityOnHand: number | string | null;
  /** Weighted-average unit cost carried on the stock itself; null when never valued. */
  buyingPrice: number | string | null;
  /** quantity x buyingPrice; null when unvalued — never a silent zero. */
  costValue: number | string | null;
  /** Unit price from the company's default price list; null when never priced. */
  sellingPrice: number | string | null;
  /** quantity x sellingPrice; null when unpriced — never a silent zero. */
  saleValue: number | string | null;
  /**
   * True when the product has left the catalogue (archived, inactive or made non-stockable) but
   * still holds stock. Always false on the Product List, which lists the catalogue only; the Stock
   * Value report widens to these rows deliberately — they are what keeps its cost total tied to the
   * GL-reconciled valuation — so that screen MARKS them rather than hiding them. Without the mark,
   * a reader comparing the two reports finds an item and a cost in one and not the other with no
   * explanation.
   */
  discontinued: boolean;
}

/**
 * Grand totals for the Stock Value report.
 *
 * `unvaluedItems` / `unpricedItems` travel beside the money so the foot of the report cannot quietly
 * mislead: those rows hold stock but contribute nothing to the corresponding total, so a screen that
 * shows the money without the counts presents an understated figure as if it were complete.
 */
export interface ProductStockTotalsDto {
  totalQuantity: number | string | null;
  totalCostValue: number | string | null;
  totalSaleValue: number | string | null;
  /** totalSaleValue − totalCostValue: the margin still sitting on the shelf. */
  potentialMargin: number | string | null;
  /** Rows holding stock but carrying no cost — EXCLUDED from totalCostValue. */
  unvaluedItems: number;
  /** Rows holding stock but carrying no selling price — EXCLUDED from totalSaleValue. */
  unpricedItems: number;
  /**
   * Rows INCLUDED in the totals that have left the catalogue but still hold stock. They belong in a
   * valuation (the books are carrying them), but they are absent from the Product List — so the
   * screen states the count, exactly as the exported PDF/XLSX/CSV header line does.
   */
  discontinuedItems: number;
}

export interface ProductStockReportDto {
  company: ReportCompanyHeaderDto;
  /** null = every branch. */
  branchName: string | null;
  /** null = every supplier. */
  supplierName: string | null;
  currency: string;
  /** The default price list the selling prices came from; null when the company has none. */
  priceListName: string | null;
  priceIncludesVat: boolean;
  rows: ProductStockRowDto[];
  /** null on the Product List (a catalogue has no meaningful grand total); set on Stock Value. */
  totals: ProductStockTotalsDto | null;
  generatedAt: string;
}

/** Query filter shared by both reports and their /export counterparts. Both fields are optional. */
export interface ProductStockReportFilter {
  branchUid?: string | null;
  supplierUid?: string | null;
}

/**
 * Money that may genuinely be UNKNOWN.
 *
 * This is the one formatting rule these two reports do NOT share with the other report screens
 * (whose `fmtMoney` coerces null to 0.00): the backend sends null for "never costed" / "never
 * priced", and printing 0.00 there would tell a shopkeeper the goods are free. An absent figure
 * renders as an em dash — the same thing the exported PDF shows as a blank cell. A REAL zero still
 * prints as 0.00.
 *
 * Lives here, beside the DTO, so both screens (and the exported page) cannot drift apart on it —
 * the same reason `formatReportAddress` sits in report-company-header.model.ts.
 */
export function formatStockAmount(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = +v;
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    : '—';
}

/**
 * Quantity, in the product's BASE unit.
 *
 * Up to 3 dp (K4): rounding to whole numbers made 20 loose pieces under a carton base unit print as
 * "0", and the shop read it as out of stock. Whole numbers still print clean ("120", not "120.000").
 */
export function formatStockQty(v: number | string | null | undefined): string {
  const n = +(v ?? 0);
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 3 })
    : '0';
}
