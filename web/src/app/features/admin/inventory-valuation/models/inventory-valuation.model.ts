/**
 * TypeScript models mirroring the backend inventory-valuation DTOs (ADR-0020 D-6, D-5b).
 * All Long id fields typed `string`; BigDecimal fields typed `number | string | null` —
 * coerce with `+(v ?? 0)` (numeric-money guard) before arithmetic or display.
 */

/** One row in the stock valuation report: per-product on-hand value. */
export interface StockValuationRowDto {
  productId: string;
  productUid: string;
  productCode: string;
  productName: string;
  /** BigDecimal — arrives as JSON number; coerce with `+(v ?? 0)`. */
  quantity: number | string | null;
  /** Moving-average cost; null when product has on-hand qty but no cost yet. */
  avgCost: number | string | null;
  /** Authoritative carried on_hand_value figure. */
  value: number | string | null;
  currency: string;
}

/**
 * Reconciliation bar for the stock valuation report (BR-INV-06).
 * `ties == true` → Σ(on_hand_value) == GL 1300 Inventory account balance.
 * `ties == false` → finance-grade defect: stock ledger and GL are out of sync.
 */
export interface StockValuationReconDto {
  label: string;
  /** Σ on_hand_value across all products for the company. */
  computed: number | string | null;
  /** accountBalance(companyId, INVENTORY account) from GL. */
  expected: number | string | null;
  /** computed − expected. */
  difference: number | string | null;
  ties: boolean;
}

/** Full stock valuation report + GL reconciliation bar (FR-INV-07). */
export interface StockValuationReportDto {
  companyId: string;
  rows: StockValuationRowDto[];
  /** Σ of all row values. */
  totalValue: number | string | null;
  recon: StockValuationReconDto;
  currency: string;
}

/** Request: set the one-time opening cost for an existing on-hand row (FR-INV-06). */
export interface SetOpeningValuationRequest {
  /** uid of the stock_on_hand row to value. */
  stockOnHandUid: string;
  /** Opening unit cost in base currency (>= 0). Sent as string to avoid float precision. */
  openingCost: string;
}

/** Result of setting the opening inventory valuation for one product. */
export interface OpeningValuationResultDto {
  productUid: string;
  quantity: number | string | null;
  openingCost: number | string | null;
  openingValue: number | string | null;
  /** uid of the posted DR 1300 / CR 3100 journal entry. */
  glEntryUid: string;
  currency: string;
}
