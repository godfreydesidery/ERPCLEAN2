/**
 * Projects module — TypeScript models mirroring backend DTOs.
 * All Long/BigDecimal fields are strings on the wire (JacksonConfig).
 * Both `id` and `uid` are typed string.
 */

// ── Enums ──────────────────────────────────────────────────────────────────────

export type ProjectStatus = 'DRAFT' | 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'CANCELLED';
export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';
export type ProjectCostType = 'MATERIAL' | 'SUBCONTRACT' | 'LABOUR' | 'OVERHEAD' | 'OTHER';

// ── Project ────────────────────────────────────────────────────────────────────

export interface ProjectDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  projectNumber: string;
  name: string;
  customerId: string;
  managerUserId: string;
  projectStatus: ProjectStatus;
  plannedStartDate: string; // LocalDate -> ISO string
  plannedEndDate: string;
  budgetAmount: string; // BigDecimal as string
  currency: string;
  notes: string;
  status: MasterStatus;
  activatedAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
}

export interface CreateProjectRequest {
  companyUid: string;
  branchUid: string;
  name: string;
  customerUid?: string;
  managerUid?: string;
  plannedStartDate?: string;
  plannedEndDate?: string;
  budgetAmount?: string;
  notes?: string;
}

export interface UpdateProjectRequest {
  name: string;
  customerUid?: string;
  managerUid?: string;
  plannedStartDate?: string;
  plannedEndDate?: string;
  budgetAmount?: string;
  notes?: string;
}

// ── Project Task ──────────────────────────────────────────────────────────────

export interface ProjectTaskDto {
  id: string;
  uid: string;
  projectId: string;
  companyId: string;
  branchId: string;
  taskCode: string;
  name: string;
  parentId: string | null; // reserved, always null in v1
  plannedHours: string; // BigDecimal as string
  billable: boolean;
  status: MasterStatus;
}

export interface CreateProjectTaskRequest {
  taskCode: string;
  name: string;
  plannedHours: string;
  billable: boolean;
}

// ── Project Timesheet ─────────────────────────────────────────────────────────

export interface ProjectTimesheetDto {
  id: string;
  uid: string;
  projectId: string;
  projectTaskId: string;
  companyId: string;
  branchId: string;
  userId: string;
  workDate: string; // LocalDate
  hours: string; // BigDecimal as string
  billable: boolean;
  plannedRateAmount: string; // BigDecimal, informational
  notes: string;
  status: MasterStatus;
}

export interface CreateTimesheetRequest {
  projectTaskUid?: string; // optional — timesheet may be at project level
  userId: string; // Long as string (NOT uid)
  workDate: string;
  hours: string;
  billable: boolean;
  plannedRateAmount?: string;
  notes?: string;
}

// ── Project Issue (Issue-to-Job) ───────────────────────────────────────────────

export interface IssueLine {
  productUid: string;
  qty: string;
}

export interface IssueToProjectRequest {
  companyUid: string;
  branchUid: string;
  projectUid: string;
  projectTaskUid?: string;
  lines: IssueLine[];
  issueDate?: string;
  reason?: string;
}

export interface IssueLineResultDto {
  productUid: string;
  qty: string;
  value: string;
}

export interface IssueToProjectResultDto {
  issueUid: string;
  issueNumber: string;
  projectUid: string;
  lines: IssueLineResultDto[];
  cogsGlEntryUid: string;
  totalValue: string;
  currency: string;
}

// ── Project Costing (P&L + WIP) ───────────────────────────────────────────────

export interface ProjectCostingRowDto {
  costType: ProjectCostType;
  amount: string;
}

export interface ReconDto {
  computedRevenue: string;
  computedCost: string;
  glRevenue: string;
  glCost: string;
  balanced: boolean;
}

export interface ProjectPnlDto {
  projectUid: string;
  projectNumber: string;
  name: string;
  customerId: string;
  revenue: string;
  totalCost: string;
  costByType: ProjectCostingRowDto[];
  margin: string;
  marginPct: string;
  budget: string;
  budgetVariance: string;
  wip: string;
  currency: string;
  recon: ReconDto;
}

export interface ProjectWipRowDto {
  projectUid: string;
  projectNumber: string;
  name: string;
  costIncurred: string;
  billed: string;
  wip: string;
  currency: string;
}
