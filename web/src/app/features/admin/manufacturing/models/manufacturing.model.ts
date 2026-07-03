/**
 * Manufacturing module DTOs — mirror backend WorkOrderDto / WorkOrderComponentDto /
 * WorkOrderOperationDto / WorkOrderCostReportDto / WipReconciliationDto.
 * ALL Long / BigDecimal fields are typed string (stringified on the wire).
 */

export type WorkOrderStatus =
  | 'PLANNED'
  | 'RELEASED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CLOSED'
  | 'CANCELLED';

export type ComponentLineStatus = 'PLANNED' | 'PARTIAL' | 'ISSUED';

// ── Child: WorkOrderComponentDto ─────────────────────────────────────────────

export interface WorkOrderComponentDto {
  id: string;
  uid: string;
  workOrderId: string;
  lineNo: string;
  componentProductId: string;
  componentProductCode: string;
  componentProductName: string;
  plannedQty: string;
  issuedQty: string;
  issuedValue: string;
  unitCostAtIssue: string;
  costSkipped: boolean;
  status: ComponentLineStatus;
}

// ── Child: WorkOrderOperationDto ─────────────────────────────────────────────

export interface WorkOrderOperationDto {
  id: string;
  uid: string;
  workOrderId: string;
  seqNo: string;
  description: string;
  workCentre: string;
  labourAmount: string;
  overheadAmount: string;
  applied: boolean;
}

// ── Primary: WorkOrderDto ────────────────────────────────────────────────────

export interface WorkOrderDto {
  id: string;
  uid: string;
  woNumber: string;
  companyId: string;
  branchId: string;
  /** Display name for the branch — never render branchId to users. */
  branchName: string | null;
  /** Branch short code, shown as a muted secondary alongside the name. */
  branchCode: string | null;
  finishedProductId: string;
  finishedProductCode: string;
  finishedProductName: string;
  bomId: string;
  bomUid: string;
  plannedQty: string;
  goodQty: string;
  scrapQty: string;
  status: WorkOrderStatus;
  wipDebitTotal: string;
  wipCreditTotal: string;
  labourAppliedTotal: string;
  overheadAppliedTotal: string;
  computedUnitCost: string;
  varianceAmount: string;
  incompleteCost: boolean;
  costCentreValueId: string;
  plannedDate: string;
  releasedAt: string;
  completedAt: string;
  closedAt: string;
  cancelledAt: string;
  notes: string;
  createdAt: string;
  createdBy: string;
  components: WorkOrderComponentDto[];
  operations: WorkOrderOperationDto[];
}

// ── Report: WorkOrderCostReportDto ───────────────────────────────────────────

export interface WorkOrderCostReportDto {
  woNumber: string;
  finishedProductCode: string;
  finishedProductName: string;
  plannedQty: string;
  goodQty: string;
  scrapQty: string;
  wipDebitTotal: string;
  wipCreditTotal: string;
  labourAppliedTotal: string;
  overheadAppliedTotal: string;
  computedUnitCost: string;
  varianceAmount: string;
  incompleteCost: boolean;
  components: WorkOrderComponentDto[];
  operations: WorkOrderOperationDto[];
}

// ── Report: WipReconciliationDto ─────────────────────────────────────────────

export interface WipReconciliationDto {
  label: string;
  computed: string;
  expected: string;
  ties: boolean;
}

// ── Request: CreateWorkOrderRequest ─────────────────────────────────────────

export interface CreateWorkOrderRequest {
  finishedProductUid: string;
  plannedQty: string;
  branchUid: string;
  bomUid?: string;
  plannedDate?: string;
  costCentreValueUid?: string;
  notes?: string;
}

// ── Request: UpdateWorkOrderRequest ─────────────────────────────────────────

export interface UpdateWorkOrderRequest {
  plannedQty?: string;
  bomUid?: string;
  branchUid?: string;
  plannedDate?: string;
  costCentreValueUid?: string;
  notes?: string;
}

// ── Request: ReleaseWorkOrderRequest ────────────────────────────────────────

export interface ReleaseWorkOrderRequest {
  bomUid?: string;
}

// ── Request: IssueComponentsRequest ─────────────────────────────────────────

export interface IssueComponentsRequest {
  full: boolean;
  componentUids?: string[];
  postingDate: string;
}

// ── Request: ApplyCostRequest ────────────────────────────────────────────────

export interface ApplyCostRequest {
  labourAmount?: string;
  overheadAmount?: string;
  operationUid?: string;
  postingDate: string;
}

// ── Request: CompleteWorkOrderRequest ────────────────────────────────────────

export interface CompleteWorkOrderRequest {
  goodQty: string;
  scrapQty?: string;
  allowOverRun: boolean;
  postingDate: string;
}

// ── Request: CloseWorkOrderRequest ───────────────────────────────────────────

export interface CloseWorkOrderRequest {
  postingDate: string;
}

// ── Request: AddOperationRequest ─────────────────────────────────────────────

export interface AddOperationRequest {
  seqNo: string;
  description: string;
  workCentre?: string;
  labourAmount?: string;
  overheadAmount?: string;
}
