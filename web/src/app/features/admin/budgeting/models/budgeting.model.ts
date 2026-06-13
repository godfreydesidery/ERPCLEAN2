/**
 * Budgeting module TypeScript models — mirrors backend DTOs exactly.
 * All Long/BigDecimal/Integer fields arrive as JSON strings (global Jackson config).
 * uid is the URL key; id is for body-level FK joins.
 */

// ── Status enum ───────────────────────────────────────────────────────────────

export type BudgetVersionStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED';

export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'INCOME' | 'EXPENSE';

export type NormalBalance = 'DEBIT' | 'CREDIT';

export type EntryMode = 'DIRECT' | 'ANNUAL_SPREAD' | 'SEED';

// ── Budget ─────────────────────────────────────────────────────────────────────

export interface BudgetDto {
  id: string;
  uid: string;
  companyId: string;
  budgetNumber: string;
  name: string;
  fiscalYearId: string;
  fiscalYearUid: string;
  fiscalYearCode: string;
  costCentreValueId: string | null;
  costCentreValueUid: string | null;
  costCentreValueName: string | null;
  notes: string | null;
  version: string;
  createdAt: string;
  createdBy: string;
  updatedAt: string;
  updatedBy: string;
  versions: BudgetVersionDto[];
}

export interface CreateBudgetRequest {
  companyId: string;            // Long — sent as string
  name: string;                 // required, max 160
  fiscalYearUid: string;        // required
  costCentreValueUid?: string;  // optional, null = company-wide
  notes?: string;               // optional, max 500
  initialVersionLabel?: string; // optional, max 120
}

// ── Budget Version ────────────────────────────────────────────────────────────

export interface BudgetVersionDto {
  id: string;
  uid: string;
  budgetId: string;
  budgetUid: string;
  companyId: string;
  fiscalYearId: string;
  costCentreValueId: string | null;
  versionNo: string;            // int on wire → string
  status: BudgetVersionStatus;
  label: string | null;
  seededFromVersionId: string | null;
  seededFromVersionUid: string | null;
  submittedAt: string | null;
  submittedBy: string | null;
  approvedAt: string | null;
  approvedBy: string | null;
  rejectedAt: string | null;
  rejectedBy: string | null;
  supersededAt: string | null;
  decisionReason: string | null;
  version: string;
  createdAt: string;
  createdBy: string;
  lines: BudgetLineDto[];
}

export interface CreateBudgetVersionRequest {
  label?: string;               // optional, max 120
  seedFromVersionUid?: string;  // optional, null = blank
}

// ── Budget Line ───────────────────────────────────────────────────────────────

export interface BudgetLineDto {
  id: string;
  uid: string;
  accountId: string;
  accountUid: string;
  accountCode: string;
  accountName: string;
  fiscalPeriodId: string;
  fiscalPeriodUid: string;
  periodNo: string;             // int → string
  amount: string;               // BigDecimal → string
  lineMemo: string | null;
}

export interface LineInputDto {
  accountUid: string;
  fiscalPeriodUid: string;
  amount: string;               // BigDecimal → string
  lineMemo?: string;            // max 255
}

export interface UpsertBudgetLineRequest {
  mode: EntryMode;
  lines?: LineInputDto[];       // for DIRECT
  annualAmount?: string;        // for ANNUAL_SPREAD — BigDecimal → string
  accountUid?: string;          // for ANNUAL_SPREAD / SEED
  seedFromVersionUid?: string;  // for SEED
}

// ── Lifecycle action request DTOs ─────────────────────────────────────────────

export interface ApproveBudgetVersionRequest {
  note?: string;                // optional, max 500
}

export interface RejectBudgetVersionRequest {
  reason: string;               // required, @NotBlank, max 500
}

// ── Report DTOs ───────────────────────────────────────────────────────────────

export interface VarianceReportHeaderDto {
  companyId: string;
  fiscalYearUid: string;
  fiscalYearCode: string;
  fromPeriodNo: string;
  toPeriodNo: string;
  costCentreValueUid: string | null;
  costCentreValueName: string | null;
  noApprovedBudget: boolean;
}

export interface VarianceRowDto {
  accountId: string;
  accountCode: string;
  accountName: string;
  accountType: AccountType;
  normalBalance: NormalBalance;
  costCentreValueId: string | null;
  costCentreValueName: string | null;
  budgetAmount: string;
  actualAmount: string;
  varianceAmount: string;
  variancePct: string | null;
}

export interface VarianceReportDto {
  header: VarianceReportHeaderDto;
  rows: VarianceRowDto[];
  totalsBudgetByType: Record<AccountType, string>;
  totalsActualByType: Record<AccountType, string>;
  totalsVarianceByType: Record<AccountType, string>;
}

export interface DepartmentalActualsRowDto {
  costCentreValueId: string | null;
  costCentreValueName: string | null;
  accountId: string;
  accountCode: string;
  accountName: string;
  accountType: AccountType;
  normalBalance: NormalBalance;
  actualAmount: string;
}

export interface DepartmentalActualsDto {
  companyId: string;
  fiscalYearUid: string;
  fromDate: string;             // LocalDate → ISO string
  toDate: string;
  rows: DepartmentalActualsRowDto[];
}
