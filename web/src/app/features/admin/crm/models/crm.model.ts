/**
 * CRM module models — mirrors backend DTOs exactly.
 * All Long id fields typed `string` (wire contract: Jackson stringifies Longs).
 * BigDecimal amounts and probabilities arrive as strings on the wire.
 * Timestamps (Instant) and dates (LocalDate) arrive as ISO strings.
 */

// ── Enums ─────────────────────────────────────────────────────────────────────

export type LeadStatus = 'NEW' | 'CONTACTED' | 'QUALIFIED' | 'CONVERTED' | 'DISQUALIFIED';

export type LeadSource =
  | 'WEBSITE'
  | 'REFERRAL'
  | 'WALK_IN'
  | 'CAMPAIGN'
  | 'COLD_CALL'
  | 'EXISTING_CUSTOMER'
  | 'OTHER';

export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

export type OpportunityStatus = 'OPEN' | 'WON' | 'LOST';

export type ConvertTarget = 'QUOTATION' | 'SALES_ORDER';

export type CustomerKind = 'CASH_WALK_IN' | 'CREDIT_ACCOUNT';

// ── LeadDto ───────────────────────────────────────────────────────────────────

export interface LeadDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  leadNumber: string;
  leadStatus: LeadStatus;
  leadSource: LeadSource;
  displayName: string;
  companyName: string | null;
  contactPerson: string | null;
  phone: string | null;
  email: string | null;
  ownerUserId: string | null;
  customerId: string | null;
  customerUid: string | null;
  disqualifyReason: string | null;
  notes: string | null;
  qualifiedAt: string | null;
  convertedAt: string | null;
  disqualifiedAt: string | null;
  status: MasterStatus;
  version: string;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

// ── Lead Request DTOs ─────────────────────────────────────────────────────────

export interface CreateLeadRequest {
  companyId: string;
  displayName: string;
  leadSource: LeadSource;
  companyName?: string;
  contactPerson?: string;
  phone?: string;
  email?: string;
  ownerUserId?: string;
  notes?: string;
}

export interface UpdateLeadRequest {
  displayName: string;
  leadSource: LeadSource;
  companyName?: string;
  contactPerson?: string;
  phone?: string;
  email?: string;
  ownerUserId?: string;
  notes?: string;
}

export interface NewCustomerDetailsDto {
  displayName: string;
  customerKind: CustomerKind;
  phone?: string;
  email?: string;
  physicalAddress?: string;
  region?: string;
  district?: string;
}

export interface QualifyLeadRequest {
  existingCustomerUid?: string;
  newCustomerDetails?: NewCustomerDetailsDto;
}

export interface DisqualifyLeadRequest {
  reason: string;
}

// ── OpportunityLineDto ────────────────────────────────────────────────────────

export interface OpportunityLineDto {
  id: string;
  uid: string;
  opportunityId: string;
  companyId: string;
  branchId: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  estimatedQty: string;
  estimatedUnitPriceAmount: string;
  lineDiscountAmount: string | null;
  lineDiscountPercent: string | null;
  currency: string;
  version: string;
  createdAt: string;
  createdBy: string | null;
}

// ── OpportunityDto ────────────────────────────────────────────────────────────

export interface OpportunityDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  opportunityNumber: string;
  opportunityStatus: OpportunityStatus;
  title: string;
  customerId: string;
  customerUid: string;
  agentId: string | null;
  ownerUserId: string | null;
  sourceLeadId: string | null;
  sourceLeadUid: string | null;
  pipelineStageId: string;
  winProbability: string;
  estimatedValueAmount: string;
  currency: string;
  expectedCloseDate: string | null;
  wonAt: string | null;
  lostAt: string | null;
  lossReason: string | null;
  convertedDocumentKind: string | null;
  convertedDocumentUid: string | null;
  convertedAt: string | null;
  status: MasterStatus;
  version: string;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
  lines: OpportunityLineDto[];
}

// ── Opportunity Request DTOs ──────────────────────────────────────────────────

export interface CreateOpportunityRequest {
  companyId: string;
  customerUid: string;
  title: string;
  pipelineStageUid: string;
  currency: string;
  estimatedValueAmount?: string;
  winProbability?: string;
  expectedCloseDate?: string;
  agentUid?: string;
  ownerUserId?: string;
  sourceLeadUid?: string;
  notes?: string;
}

export interface UpdateOpportunityRequest {
  title: string;
  estimatedValueAmount?: string;
  winProbability?: string;
  expectedCloseDate?: string;
  agentUid?: string;
  ownerUserId?: string;
  notes?: string;
}

export interface AddOpportunityLineRequest {
  productUid: string;
  unitUid: string;
  estimatedQty: string;
  estimatedUnitPriceAmount?: string;
  lineDiscountAmount?: string;
  lineDiscountPercent?: string;
}

export interface AdvanceStageRequest {
  pipelineStageUid: string;
  winProbabilityOverride?: string;
}

export interface WinOpportunityRequest {
  wonAt: string;
}

export interface LoseOpportunityRequest {
  lossReason: string;
}

export interface ConvertOpportunityRequest {
  target: ConvertTarget;
  validUntil?: string;
}

export interface ConvertResultDto {
  opportunityUid: string;
  convertedDocumentKind: string;
  convertedDocumentUid: string;
  convertedDocumentNumber: string | null;
}

// ── PipelineStageDto ──────────────────────────────────────────────────────────

export interface PipelineStageDto {
  id: string;
  uid: string;
  companyId: string;
  name: string;
  displayOrder: string;
  defaultProbability: string;
  active: boolean;
  status: MasterStatus;
  version: string;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

// ── Pipeline Stage Request DTOs ───────────────────────────────────────────────

export interface CreatePipelineStageRequest {
  companyId: string;
  name: string;
  displayOrder: string;
  defaultProbability: string;
}

export interface UpdatePipelineStageRequest {
  name: string;
  displayOrder: string;
  defaultProbability: string;
  active: boolean;
}

// ── Pipeline Report DTOs ──────────────────────────────────────────────────────

export interface PipelineStageSummaryRowDto {
  stageUid: string;
  stageName: string;
  displayOrder: string;
  openCount: string;
  totalValueAmount: string;
  weightedValueAmount: string;
  currency: string;
}

export interface PipelineSummaryDto {
  stages: PipelineStageSummaryRowDto[];
}

export interface ForecastDto {
  periodStart: string;
  periodEnd: string;
  weightedValueAmount: string;
  openCount: string;
  currency: string;
}

export interface CrmKpiDto {
  periodStart: string;
  periodEnd: string;
  wonCount: string;
  lostCount: string;
  winRatePercent: string;
  avgCycleDays: string;
}
