/**
 * FX / Multi-currency feature models (ADR-0036 Tranche 5).
 * Mirrors backend DTOs exactly. All Long/BigDecimal fields arrive as strings on the wire
 * per PROJECT-CONVENTIONS identity pattern.
 */

// ── Enums (string unions) ────────────────────────────────────────────────────

export type FxRevaluationRunStatus = 'PREVIEWED' | 'POSTED' | 'REVERSED';

// ── Currency master ──────────────────────────────────────────────────────────

/**
 * Mirrors CurrencyDto. Both id and uid are strings (Long serialised by Jackson).
 */
export interface CurrencyDto {
  id: string;
  uid: string;
  code: string;           // ISO 4217 3-letter code (e.g. "USD")
  name: string;
  symbol: string;
  minorUnits: number;     // short — not a monetary amount, safe as number
  numericCode: string | null; // ISO 4217 numeric code (P2-M1); null if not seeded
  active: boolean;
  status: string;
}

// ── Currency rates ───────────────────────────────────────────────────────────

/**
 * Mirrors CurrencyRateDto.
 * rate, companyId come as strings on the wire (BigDecimal/Long via Jackson).
 */
export interface CurrencyRateDto {
  id: string;
  uid: string;
  companyId: string;
  fromCurrency: string;
  toCurrency: string;
  rate: string;           // BigDecimal → string
  effectiveDate: string;  // LocalDate → ISO string
  rateType: string | null;
  source: string | null;
  active: boolean;
}

/**
 * Mirrors UpsertRateRequest.
 * companyId is a Long in the backend; we send it as a number (Jackson deserialises correctly).
 */
export interface UpsertRateRequest {
  companyId: string;     // sent as numeric string; backend accepts Long
  fromCurrency: string;  // ISO 4217, 3 chars
  toCurrency: string;    // ISO 4217, 3 chars
  rate: string;          // BigDecimal as string
  effectiveDate: string; // ISO date YYYY-MM-DD
  rateType?: string | null;
  source?: string | null;
}

// ── Revaluation run lines ────────────────────────────────────────────────────

/**
 * Mirrors FxRevaluationRunLineDto.
 * All BigDecimal/Long fields are strings.
 */
export interface FxRevaluationRunLineDto {
  id: string;
  uid: string;
  sourceType: string;
  currency: string;
  controlAccountId: string;
  outstandingTxnAmount: string;
  carryingBaseAmount: string;
  spotRate: string;
  revaluedBaseAmount: string;
  adjustmentAmount: string;
}

// ── Revaluation run header ────────────────────────────────────────────────────

/**
 * Mirrors FxRevaluationRunDto.
 */
export interface FxRevaluationRunDto {
  id: string;
  uid: string;
  companyId: string;
  runNumber: string;
  fiscalPeriodId: string;
  postingDate: string;       // LocalDate → ISO string
  spotRateDate: string;      // LocalDate → ISO string
  status: FxRevaluationRunStatus;
  totalGainAmount: string;   // BigDecimal → string
  totalLossAmount: string;
  netAdjustmentAmount: string;
  glEntryUid: string | null;
  reversalGlEntryUid: string | null;
  executedAt: string | null; // Instant → ISO string
  lines: FxRevaluationRunLineDto[];
}

// ── Revaluation preview ───────────────────────────────────────────────────────

/**
 * Mirrors FxRevaluationPreviewLineDto.
 */
export interface FxRevaluationPreviewLineDto {
  sourceType: string;
  currency: string;
  controlAccountId: string;
  outstandingTxnAmount: string;
  carryingBaseAmount: string;
  spotRate: string;
  revaluedBaseAmount: string;
  adjustmentAmount: string;
}

/**
 * Mirrors FxRevaluationPreviewDto.
 */
export interface FxRevaluationPreviewDto {
  companyId: string;
  fiscalPeriodUid: string;
  spotRateDate: string;      // LocalDate → ISO string
  totalGainAmount: string;
  totalLossAmount: string;
  netAdjustmentAmount: string;
  lines: FxRevaluationPreviewLineDto[];
}

// ── Post revaluation request ──────────────────────────────────────────────────

/**
 * Mirrors PostFxRevaluationRequest.
 */
export interface PostFxRevaluationRequest {
  companyId: string;      // Long → string, backend accepts
  fiscalPeriodUid: string;
  postingDate: string;    // LocalDate → ISO YYYY-MM-DD
  spotRateDate?: string | null;
}

// ── Rates paginated page ──────────────────────────────────────────────────────

export interface CurrencyRatePage {
  rows: CurrencyRateDto[];
  meta: import('../../../../core/api/api-response.model').PageMeta;
}

// ── Revaluation runs paginated page ──────────────────────────────────────────

export interface FxRevaluationRunPage {
  rows: FxRevaluationRunDto[];
  meta: import('../../../../core/api/api-response.model').PageMeta;
}

// ── Currency enablement (ADR-0039 D-7/D-8) ───────────────────────────────────

/**
 * Mirrors CompanyCurrencyDto.
 * Long fields (id, companyId) arrive as strings (global Jackson config).
 */
export interface CompanyCurrencyDto {
  id: string;
  uid: string;
  companyId: string;
  currencyCode: string;
  isDefault: boolean;
  active: boolean;
}

/**
 * Mirrors EnableCurrencyRequest.
 */
export interface EnableCurrencyRequest {
  currencyCode: string;   // ISO 4217, exactly 3 chars
  setAsDefault: boolean;
}

/**
 * Mirrors EnabledCurrenciesDto — GET /fx/currencies/enabled.
 */
export interface EnabledCurrenciesDto {
  resolvedDefault: string;
  enabled: string[];
}
