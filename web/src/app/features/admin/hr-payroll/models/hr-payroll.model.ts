/**
 * HR & Payroll module — TypeScript models mirroring backend DTOs.
 * All Long / BigDecimal fields are string on the wire.
 * All date fields (LocalDate) are ISO date strings (YYYY-MM-DD).
 * All timestamp fields (Instant) are ISO datetime strings.
 */

// ── Employees ─────────────────────────────────────────────────────────────────

export type EmploymentStatus = 'ACTIVE' | 'ON_LEAVE' | 'SUSPENDED' | 'TERMINATED';

export interface EmployeeDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  employeeNumber: string;
  firstName: string;
  lastName: string;
  nationalId: string;
  tin: string;
  nssfNumber: string;
  heslbNumber: string;
  dateOfBirth: string;
  gender: string;
  hireDate: string;
  departmentId: string;
  departmentName: string;
  jobTitle: string;
  status: EmploymentStatus;
  userId: string;
}

export interface CreateEmployeeRequest {
  firstName: string;
  lastName: string;
  nationalId?: string;
  tin?: string;
  nssfNumber?: string;
  heslbNumber?: string;
  dateOfBirth?: string;
  gender?: string;
  hireDate: string;
  departmentId?: string;
  jobTitle?: string;
  branchId?: string;
  userId?: string;
}

// ── Pay Components ─────────────────────────────────────────────────────────────

export type PayComponentKind = 'EARNING' | 'DEDUCTION';
export type PayComponentBasis = 'FIXED' | 'PERCENT_OF_BASIC';

export interface PayComponentDto {
  id: string;
  uid: string;
  companyId: string;
  code: string;
  name: string;
  kind: PayComponentKind;
  basis: PayComponentBasis;
  glAccountId: string;
  taxable: boolean;
  pensionable: boolean;
  active: boolean;
}

export interface CreatePayComponentRequest {
  code: string;
  name: string;
  kind: PayComponentKind;
  basis: PayComponentBasis;
  glAccountId: string;
  taxable: boolean;
  pensionable: boolean;
}

// ── Payroll Runs ───────────────────────────────────────────────────────────────

export type PayrollRunStatus = 'DRAFT' | 'CALCULATED' | 'APPROVED' | 'POSTED' | 'PAID' | 'REVERSED';

export interface PayrollRunDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  runNumber: string;
  periodYear: number;
  periodMonth: number;
  payDate: string;
  status: PayrollRunStatus;
  grossTotal: string;
  deductionTotal: string;
  netTotal: string;
  employerCostTotal: string;
  calculatedAt: string | null;
  approvedAt: string | null;
  postedAt: string | null;
  paidAt: string | null;
  reversedAt: string | null;
  approvedBy: string | null;
  postedBy: string | null;
  glEntryUid: string | null;
  reversalOfRunUid: string | null;
}

export interface CreatePayrollRunRequest {
  periodMonth: number;
  periodYear: number;
  payDate: string;
  branchId?: string;
}

export type PayrollLineStatus = 'OK' | 'FLAGGED';

export interface PayrollLineDto {
  id: string;
  uid: string;
  payrollRunId: string;
  employeeId: string;
  employeeNumber: string;
  employeeName: string;
  departmentName: string;
  grossAmount: string;
  taxableAmount: string;
  netAmount: string;
  payeAmount: string;
  nssfEmployeeAmount: string;
  heslbAmount: string;
  voluntaryDeductionTotal: string;
  loanDeductionTotal: string;
  nssfEmployerAmount: string;
  wcfEmployerAmount: string;
  sdlEmployerAmount: string;
  status: PayrollLineStatus;
  flagReason: string | null;
  currency: string;
}

export interface DisburseRequest {
  cashBankAccountUid: string;
  txnDate?: string;
}

// ── Leave Requests ─────────────────────────────────────────────────────────────

export type LeaveRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface LeaveRequestDto {
  id: string;
  uid: string;
  companyId: string;
  employeeId: string;
  employeeName: string;
  leaveTypeId: string;
  leaveTypeName: string;
  fromDate: string;
  toDate: string;
  days: string;
  status: LeaveRequestStatus;
  decidedBy: string | null;
  decidedAt: string | null;
  reason: string | null;
  decisionNote: string | null;
}

export interface SubmitLeaveRequest {
  leaveTypeId: string;
  fromDate: string;
  toDate: string;
  days: string;
  reason?: string;
}

export interface DecideLeaveRequest {
  decision: 'APPROVED' | 'REJECTED';
  decisionNote?: string;
}

// ── Loans ──────────────────────────────────────────────────────────────────────

export type LoanStatus = 'ACTIVE' | 'SETTLED' | 'CANCELLED';

export interface EmployeeLoanDto {
  id: string;
  uid: string;
  companyId: string;
  employeeId: string;
  employeeName: string;
  loanNumber: string;
  principalAmount: string;
  installmentAmount: string;
  outstandingAmount: string;
  glAccountId: string;
  status: LoanStatus;
  startDate: string;
  currency: string;
}

export interface CreateLoanRequest {
  employeeId: string;
  principalAmount: string;
  installmentAmount: string;
  glAccountId: string;
  startDate: string;
  currency: string;
}
