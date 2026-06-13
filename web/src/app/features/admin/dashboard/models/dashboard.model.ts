/**
 * BI dashboard models — mirrors backend bi.domain.dto exactly.
 * All Long/BigDecimal fields typed `string` (Jackson stringifies on the wire).
 * Dates (LocalDate) and timestamps (Instant) arrive as ISO strings.
 * ADR-0037 D-6.
 */

// ── Header ────────────────────────────────────────────────────────────────────

export interface BiHeaderDto {
  companyId: string;
  companyName: string;
  currency: string;
  periodLabel: string;
  fromDate: string;   // LocalDate ISO
  toDate: string;     // LocalDate ISO
  asOf: string;       // LocalDate ISO
  generatedAt: string; // Instant ISO
}

// ── Health strip ─────────────────────────────────────────────────────────────

export interface HealthIndicatorDto {
  label: string;
  ties: boolean;
  difference: string; // BigDecimal string
}

// ── Finance summary ───────────────────────────────────────────────────────────

export interface FinanceSummaryDto {
  netProfitPeriod: string;
  revenue: string;
  cogs: string;
  grossProfit: string;
  grossMarginPct: string;
  opex: string;
  netProfit: string;
  tbTies: boolean;
  tbTotalDebit: string;
  tbTotalCredit: string;
  cash: CashPositionDto;
}

// ── Cash position ─────────────────────────────────────────────────────────────

export interface CashAccountBalanceDto {
  cashBankAccountId: string;
  cashBankAccountUid: string;
  accountCode: string;
  accountName: string;
  bookBalance: string; // BigDecimal string
  currency: string;
}

export interface CashPositionDto {
  total: string;
  accounts: CashAccountBalanceDto[];
  cashTies: boolean;
  cashGlDifference: string;
}

// ── Working capital ────────────────────────────────────────────────────────────

export interface WorkingCapitalDto {
  arOutstanding: string;
  arTies: boolean;
  arDifference: string;
  apOutstanding: string;
  apTies: boolean;
  apDifference: string;
}

// ── Inventory ─────────────────────────────────────────────────────────────────

export interface InventorySummaryDto {
  stockValue: string;
  stockTies: boolean;
  stockDifference: string;
}

// ── CRM (reuses CRM DTOs) ─────────────────────────────────────────────────────

export interface PipelineStageSummaryRowDto {
  stageUid: string;
  stageName: string;
  displayOrder: number;
  openCount: number;
  totalValueAmount: string;
  weightedValueAmount: string;
  currency: string;
}

export interface PipelineSummaryDto {
  stages: PipelineStageSummaryRowDto[];
}

export interface CrmKpiDto {
  periodStart: string;
  periodEnd: string;
  wonCount: number;
  lostCount: number;
  winRatePercent: string;
  avgCycleDays: string;
}

export interface ForecastDto {
  periodStart: string;
  periodEnd: string;
  weightedValueAmount: string;
  openCount: number;
  currency: string;
}

export interface CrmSnapshotDto {
  pipeline: PipelineSummaryDto | null;
  kpis: CrmKpiDto | null;
  forecast: ForecastDto | null;
}

// ── Trend ─────────────────────────────────────────────────────────────────────

export interface TrendPointDto {
  periodLabel: string;
  periodStart: string;
  periodEnd: string;
  value: string; // BigDecimal string
}

export interface TrendDto {
  metricLabel: string;
  currency: string;
  points: TrendPointDto[];
}

// ── Composite dashboard ───────────────────────────────────────────────────────

export interface DashboardDto {
  header: BiHeaderDto;
  finance: FinanceSummaryDto | null;        // null when BI.FINANCE.VIEW not held
  workingCapital: WorkingCapitalDto | null; // null when BI.FINANCE.VIEW not held
  inventory: InventorySummaryDto | null;   // null when BI.OPS.VIEW not held
  crm: CrmSnapshotDto | null;              // null when BI.CRM.VIEW not held
  revenueTrend: TrendDto | null;           // null when BI.FINANCE.VIEW not held
  netProfitTrend: TrendDto | null;         // null when BI.FINANCE.VIEW not held
  health: HealthIndicatorDto[];
}
