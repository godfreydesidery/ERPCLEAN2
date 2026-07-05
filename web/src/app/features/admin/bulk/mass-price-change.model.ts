/** Rule-based mass price change (Excel-free bulk price update) — POST /api/v1/prices/mass-change. */

export type PriceChangeType = 'PERCENT' | 'AMOUNT' | 'SET';

export interface MassPriceChangeRequest {
  readonly priceListUid: string;
  readonly type: PriceChangeType;
  readonly value: number;
  readonly roundToDecimals?: number | null;
  readonly dryRun: boolean;
}

export interface MassPriceChangeSample {
  readonly productCode: string;
  /** Money amounts arrive as a JSON number or string (BigDecimal on the wire) — coerce, never
   * string-op directly. */
  readonly oldAmount: string | number;
  readonly newAmount: string | number;
  readonly currency: string;
}

export interface MassPriceChangeResult {
  readonly priceListUid: string;
  readonly priceListCode: string;
  readonly priceListName: string;
  readonly totalRows: number;
  readonly affected: number;
  readonly dryRun: boolean;
  readonly samples: readonly MassPriceChangeSample[];
}
