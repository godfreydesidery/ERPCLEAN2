/**
 * Fixed Assets module — TypeScript models mirroring backend DTOs.
 * All Long/BigDecimal fields are strings on the wire (global serialiser config).
 * int counts (lifePeriods, periodSeq, scheduleVersion, assetCount, PageMeta fields) are JSON numbers.
 */

// ── Enums ──────────────────────────────────────────────────────────────────────

export type FixedAssetStatus = 'DRAFT' | 'IN_SERVICE' | 'DISPOSED' | 'WRITTEN_OFF';
export type DepreciationMethod = 'STRAIGHT_LINE' | 'REDUCING_BALANCE';
export type AssetDisposalType = 'SALE' | 'WRITE_OFF';
export type RevaluationDirection = 'UP' | 'DOWN';
export type DepreciationRunStatus = 'POSTED';
export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

// ── Asset Category ────────────────────────────────────────────────────────────

/** Mirrors AssetCategoryDto */
export interface AssetCategoryDto {
  id: string;
  uid: string;
  companyId: string;
  code: string;
  name: string;
  defaultMethod: DepreciationMethod;
  defaultLifePeriods: number;
  defaultReducingRate: string;
  assetAccountId: string;
  accumDepAccountId: string;
  depExpenseAccountId: string;
  status: MasterStatus;
}

/** Mirrors CreateAssetCategoryRequest */
export interface CreateAssetCategoryRequest {
  companyId: string;
  code: string;
  name: string;
  defaultMethod: DepreciationMethod;
  defaultLifePeriods: number;
  defaultReducingRate?: string;
  assetAccountId: string;
  accumDepAccountId: string;
  depExpenseAccountId: string;
}

/** Mirrors UpdateAssetCategoryRequest */
export interface UpdateAssetCategoryRequest {
  name: string;
  defaultMethod: DepreciationMethod;
  defaultLifePeriods: number;
  defaultReducingRate?: string;
  assetAccountId: string;
  accumDepAccountId: string;
  depExpenseAccountId: string;
}

// ── Fixed Asset ────────────────────────────────────────────────────────────────

/** Mirrors FixedAssetDto */
export interface FixedAssetDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  assetNumber: string;
  categoryId: string;
  name: string;
  status: FixedAssetStatus;
  acquisitionCost: string;
  salvageValue: string;
  depreciationMethod: DepreciationMethod;
  lifePeriods: number;
  reducingRate: string;
  acquisitionDate: string;
  depreciationStartDate: string;
  carryingCost: string;
  accumulatedDepreciation: string;
  /** Derived: carryingCost - accumulatedDepreciation (read-only) */
  nbv: string;
  revaluationReserveBalance: string;
  supplierId: string;
  sourceBillUid: string;
  location: string;
  costCentreId: string;
  assetTag: string;
  capitalisedGlEntryUid: string;
  disposedAt: string;
}

/** Mirrors RegisterAssetRequest */
export interface RegisterAssetRequest {
  companyId: string;
  branchId: string;
  categoryId: string;
  name: string;
  acquisitionCost: string;
  salvageValue?: string;
  depreciationMethod: DepreciationMethod;
  lifePeriods: number;
  reducingRate?: string;
  acquisitionDate: string;
  depreciationStartDate: string;
  location?: string;
  costCentreId?: string;
  assetTag?: string;
}

/** Mirrors AcquireFromBillRequest */
export interface AcquireFromBillRequest {
  companyId: string;
  branchId: string;
  billUid: string;
  billLineUid: string;
  categoryId: string;
  name: string;
  salvageValue?: string;
  depreciationMethod: DepreciationMethod;
  lifePeriods: number;
  reducingRate?: string;
  acquisitionDate: string;
  depreciationStartDate: string;
  location?: string;
  costCentreId?: string;
  assetTag?: string;
}

/** Mirrors UpdateAssetRequest (non-financial; DRAFT only) */
export interface UpdateAssetRequest {
  name: string;
  location?: string;
  assetTag?: string;
  costCentreId?: string;
}

/** Mirrors PlaceInServiceRequest */
export interface PlaceInServiceRequest {
  postingDate: string;
}

/** Mirrors TransferAssetRequest */
export interface TransferAssetRequest {
  branchId?: string;
  location?: string;
  costCentreId?: string;
}

/** Mirrors DisposeAssetRequest */
export interface DisposeAssetRequest {
  disposalDate: string;
  proceedsAmount: string;
  reason?: string;
}

/** Mirrors WriteOffAssetRequest */
export interface WriteOffAssetRequest {
  disposalDate: string;
  reason?: string;
}

/** Mirrors RevalueAssetRequest */
export interface RevalueAssetRequest {
  direction: RevaluationDirection;
  deltaAmount: string;
  revaluationDate: string;
  reason?: string;
}

// ── Disposal / Revaluation child DTOs ─────────────────────────────────────────

/** Mirrors AssetDisposalDto */
export interface AssetDisposalDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  fixedAssetId: string;
  disposalType: AssetDisposalType;
  disposalDate: string;
  fiscalPeriodId: string;
  proceedsAmount: string;
  nbvAtDisposal: string;
  /** Signed: positive = gain, negative = loss */
  gainLossAmount: string;
  glEntryUid: string;
  currency: string;
  reason: string;
}

/** Mirrors AssetRevaluationDto */
export interface AssetRevaluationDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  fixedAssetId: string;
  revaluationDate: string;
  fiscalPeriodId: string;
  direction: RevaluationDirection;
  deltaAmount: string;
  carryingBefore: string;
  carryingAfter: string;
  glEntryUid: string;
  currency: string;
  reason: string;
}

/** Mirrors DepreciationScheduleLineDto */
export interface DepreciationScheduleLineDto {
  id: string;
  uid: string;
  fixedAssetId: string;
  periodSeq: number;
  scheduleVersion: number;
  periodDate: string;
  plannedCharge: string;
  accumulatedAfter: string;
  nbvAfter: string;
  posted: boolean;
  depreciationRunId: string;
}

// ── Reconciliation Report ─────────────────────────────────────────────────────

/** Mirrors FixedAssetReconciliationDto */
export interface FixedAssetReconciliationDto {
  registerCostSum: string;
  glCostBalance: string;
  costTies: boolean;
  registerAccumDepSum: string;
  glAccumDepBalance: string;
  accumDepTies: boolean;
}

// ── Depreciation Runs ─────────────────────────────────────────────────────────

/** Mirrors DepreciationRunLineDto */
export interface DepreciationRunLineDto {
  id: string;
  uid: string;
  fixedAssetId: string;
  scheduleLineId: string;
  chargeAmount: string;
  accumDepAfter: string;
  nbvAfter: string;
}

/** Mirrors DepreciationRunDto */
export interface DepreciationRunDto {
  id: string;
  uid: string;
  companyId: string;
  runNumber: string;
  fiscalPeriodId: string;
  postingDate: string;
  status: DepreciationRunStatus;
  totalChargeAmount: string;
  assetCount: number;
  glEntryUid: string;
  currency: string;
  executedAt: string;
  lines: DepreciationRunLineDto[];
}

/** Mirrors RunDepreciationRequest */
export interface RunDepreciationRequest {
  companyId: string;
  fiscalPeriodUid: string;
  postingDate: string;
}

/** Mirrors DepreciationRunPreviewLineDto */
export interface DepreciationRunPreviewLineDto {
  fixedAssetId: string;
  fixedAssetUid: string;
  assetNumber: string;
  assetName: string;
  categoryId: string;
  periodSeq: number;
  plannedCharge: string;
}

/** Mirrors DepreciationRunPreviewDto */
export interface DepreciationRunPreviewDto {
  companyId: string;
  fiscalPeriodUid: string;
  assetCount: number;
  totalChargeAmount: string;
  lines: DepreciationRunPreviewLineDto[];
}
