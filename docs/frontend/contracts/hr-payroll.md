# API Contract — hr-payroll module (Phase-B frontend)

**Nav group:** HR & Payroll  
**Permission codes (use VERBATIM — do not invent):** HR.EMPLOYEE.VIEW, HR.EMPLOYEE.MANAGE, HR.PAYCOMPONENT.MANAGE, HR.PAYROLL.VIEW, HR.PAYROLL.RUN, HR.PAYROLL.APPROVE, HR.PAYROLL.POST, HR.PAYROLL.DISBURSE, HR.PAYROLL.REVERSE, HR.LEAVE.VIEW, HR.LEAVE.MANAGE, HR.LEAVE.APPROVE, HR.LOAN.MANAGE

## Module notes
PAGINATION: list endpoints on employees, payroll-runs, leave-requests, and loans accept a Spring `Pageable` (page,size,sort query params) and return `ApiResponse<List<...>>` with a `PageMeta` envelope (the list is in `data`, paging in `meta`). The pay-components list takes NO Pageable â€” it returns the full company list in `ApiResponse<List<PayComponentDto>>` (data only, no meta). ENVELOPE INCONSISTENCY (important for FE): only the LIST endpoints wrap in `ApiResponse`; ALL single-item / detail / action endpoints return the RAW DTO (e.g. EmployeeDto, PayrollRunDto) with no ApiResponse wrapper. The payroll-run `/lines` endpoint returns a raw `List<PayrollLineDto>` (no wrapper, not paginated).

COMPANY SCOPING: every list endpoint REQUIRES a `companyId` query param (Long) and runs scopeGuard.assertCanActIn. Detail/action endpoints are scoped by the uid path var via @perm.scoped.

UID-BASED ROUTING: all detail/update/delete/action paths use `/uid/{uid}` (or `/employee/{employeeUid}` for nested creates), never the numeric id. The path var name is literally `uid` (or `employeeUid`).

PAYROLL-RUN LIFECYCLE (PayrollRunStatus: DRAFT, CALCULATED, APPROVED, POSTED, PAID, REVERSED). Actions + exact preconditions (verified in PayrollRunServiceImpl):
- create -> DRAFT. Rejects (409 ConflictException) if an active (non-REVERSED) run already exists for the same company+period(year,month). Allocates PAYRUN-#### number. companyId comes from the principal (NOT a request field); CreatePayrollRunRequest carries period + payDate + branchId only.
- POST /uid/{uid}/calculate -> CALCULATED. Allowed ONLY when status is DRAFT, CALCULATED, or APPROVED (re-runnable; recalculating an APPROVED run re-opens it and voids approval). Rejected once POSTED/PAID/REVERSED. Selects ACTIVE employees with an active contract; rebuilds lines/line-items/snapshots; flags lines with negative net (PayrollLineStatus.FLAGGED).
- POST /uid/{uid}/approve -> APPROVED. Requires status == CALCULATED AND no FLAGGED lines (else 409 listing the flagged employee numbers + reasons).
- POST /uid/{uid}/post -> POSTED. Requires status == APPROVED. Publishes PAYROLL.FINALISED outbox event (handler posts the balanced GL journal async and writes back glEntryUid), and freezes one payslip per line.
- POST /uid/{uid}/disburse -> PAID. Requires status == POSTED AND netTotal > 0. Body = DisburseRequest{cashBankAccountUid (required), txnDate (optional, defaults to run payDate)}. Calls Cash & Bank recordDirectEntry: DR NET_WAGES_PAYABLE / CR bank account GL for netTotal.
- POST /uid/{uid}/reverse -> REVERSED (terminal). Requires status == POSTED or PAID. Publishes PAYROLL.REVERSED (posts reversing journal); frees the period for a new run.

LEAVE LIFECYCLE (LeaveRequestStatus: PENDING, APPROVED, REJECTED, CANCELLED). submit creates a PENDING request (nested under /employee/{employeeUid}). decide (POST /uid/{uid}/decide) sets status from DecideLeaveRequest.decision but the service REJECTS any decision other than APPROVED or REJECTED (IllegalArgumentException -> 400); PENDING/CANCELLED are not valid decision values to send.

LOAN LIFECYCLE (LoanStatus: ACTIVE, SETTLED, CANCELLED). create is nested under /employee/{employeeUid}; note CreateLoanRequest ALSO carries employeeId as a body field (redundant with the path var â€” FE should send both; the service resolves the employee by the path uid). approve (POST /uid/{uid}/approve) simply sets status to ACTIVE. There is a service-level close() (-> SETTLED) but it is NOT exposed by any controller endpoint.

MONEY/CURRENCY: all amount fields are BigDecimal NUMERIC(19,4), base currency TZS. PayrollRunDto carries grossTotal/deductionTotal/netTotal/employerCostTotal. PayrollLineDto breaks down payeAmount, nssfEmployeeAmount, heslbAmount, voluntaryDeductionTotal, loanDeductionTotal (employee-side) and nssfEmployerAmount/wcfEmployerAmount/sdlEmployerAmount (employer-side), plus grossAmount/taxableAmount/netAmount and a currency code. EmployeeLoanDto has principalAmount/installmentAmount/outstandingAmount + currency. Each DTO carries its own `currency` (VARCHAR(3)) field at the line/loan level; payroll-run rolls up totals without a currency field.

REPORTS: NO dedicated report endpoint exists in these 5 controllers. The ADR (D-12) plans a PayslipController / HrReportController (payslip register, statutory summary, YTD) and a self-service HR.SELF.VIEW perm, but those controllers are NOT shipped in the listed files â€” do not build screens against them. PayslipDto/StatutorySummaryDto/LeaveBalanceDto/DepartmentDto/ContractDto exist as DTOs but have no controller here.

CHILD/NESTED RESOURCES:
- payroll-runs has a child collection: GET /uid/{uid}/lines returns the per-employee PayrollLineDto list (one line per employee; each line further has line-items + a statutory snapshot in the data model, but those are not exposed as endpoints here).
- leave-requests create is nested under an employee: POST /employee/{employeeUid}.
- loans create is nested under an employee: POST /employee/{employeeUid}.

PERM PATTERN: @perm.has('CODE') for collection/create (company-wide), @perm.scoped(#uid,'<type>','CODE') for item/action. Scope types seen: 'employee', 'paycomponent', 'payrollrun', 'leaverequest', 'employeeloan'. NEVER use Spring hasAuthority â€” these are the custom @perm bean checks.

EMPLOYEE: create/update share the SAME request DTO (CreateEmployeeRequest); update is PUT /uid/{uid}. archive is DELETE /uid/{uid} (204 No Content) â€” soft archive, gated by HR.EMPLOYEE.MANAGE. CreateEmployeeRequest takes departmentId/branchId/userId as Long ids (not uids).

PAY-COMPONENT: create/update share CreatePayComponentRequest; delete (DELETE /uid/{uid}) is a deactivate (204). Both VIEW and MANAGE on the same resource use HR.PAYCOMPONENT.MANAGE â€” there is NO separate VIEW perm for pay components (the list itself requires MANAGE).


## Resource: `employees`  (base `/api/v1/hr/employees`)

**Status enum:** ACTIVE, ON_LEAVE, SUSPENDED, TERMINATED

**Primary DTO fields:** id:string, uid:string, companyId:string, branchId:string, employeeNumber:string, firstName:string, lastName:string, nationalId:string, tin:string, nssfNumber:string, heslbNumber:string, dateOfBirth:LocalDate, gender:string, hireDate:LocalDate, departmentId:string, departmentName:string, jobTitle:string, status:EmploymentStatus(ACTIVE|ON_LEAVE|SUSPENDED|TERMINATED), userId:string

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/hr/employees` | `HR.EMPLOYEE.VIEW` | list | query params: companyId:string (required), Pageable (page,size,sort) | ApiResponse<List<EmployeeDto>> + PageMeta; EmployeeDto fields: id,uid,companyId,branchId,employeeNumber,firstName,lastName,status,... |
| POST | `/api/v1/hr/employees` | `HR.EMPLOYEE.MANAGE` | create | CreateEmployeeRequest{firstName:string(req), lastName:string(req), nationalId:string, tin:string, nssfNumber:string, heslbNumber:string, dateOfBirth:LocalDate, gender:string, hireDate:LocalDate(req), departmentId:string, jobTitle:string, branchId:string, userId:string} | EmployeeDto (raw, 201 Created) |
| GET | `/api/v1/hr/employees/uid/{uid}` | `HR.EMPLOYEE.VIEW` | getByUid |  | EmployeeDto (raw) |
| PUT | `/api/v1/hr/employees/uid/{uid}` | `HR.EMPLOYEE.MANAGE` | update | CreateEmployeeRequest{firstName:string(req), lastName:string(req), nationalId:string, tin:string, nssfNumber:string, heslbNumber:string, dateOfBirth:LocalDate, gender:string, hireDate:LocalDate(req), departmentId:string, jobTitle:string, branchId:string, userId:string} | EmployeeDto (raw) |
| DELETE | `/api/v1/hr/employees/uid/{uid}` | `HR.EMPLOYEE.MANAGE` | archive |  | void (204 No Content) - soft archive |

## Resource: `pay-components`  (base `/api/v1/hr/pay-components`)

**Primary DTO fields:** id:string, uid:string, companyId:string, code:string, name:string, kind:PayComponentKind(EARNING|DEDUCTION), basis:PayComponentBasis(FIXED|PERCENT_OF_BASIC), glAccountId:string, taxable:boolean, pensionable:boolean, active:boolean

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/hr/pay-components` | `HR.PAYCOMPONENT.MANAGE` | list | query param: companyId:string (required). NOT paginated. | ApiResponse<List<PayComponentDto>> (data only, no PageMeta) |
| POST | `/api/v1/hr/pay-components` | `HR.PAYCOMPONENT.MANAGE` | create | CreatePayComponentRequest{code:string(req), name:string(req), kind:PayComponentKind(req, EARNING\|DEDUCTION), basis:PayComponentBasis(req, FIXED\|PERCENT_OF_BASIC), glAccountId:string(req), taxable:boolean, pensionable:boolean} | PayComponentDto (raw, 201 Created) |
| GET | `/api/v1/hr/pay-components/uid/{uid}` | `HR.PAYCOMPONENT.MANAGE` | getByUid |  | PayComponentDto (raw) |
| PUT | `/api/v1/hr/pay-components/uid/{uid}` | `HR.PAYCOMPONENT.MANAGE` | update | CreatePayComponentRequest{code:string(req), name:string(req), kind:PayComponentKind(req), basis:PayComponentBasis(req), glAccountId:string(req), taxable:boolean, pensionable:boolean} | PayComponentDto (raw) |
| DELETE | `/api/v1/hr/pay-components/uid/{uid}` | `HR.PAYCOMPONENT.MANAGE` | deactivate |  | void (204 No Content) - deactivate, not hard delete |

## Resource: `payroll-runs`  (base `/api/v1/hr/payroll-runs`)

**Status enum:** DRAFT, CALCULATED, APPROVED, POSTED, PAID, REVERSED

**Primary DTO fields:** id:string, uid:string, companyId:string, branchId:string, runNumber:string, periodYear:short, periodMonth:short, payDate:LocalDate, status:PayrollRunStatus(DRAFT|CALCULATED|APPROVED|POSTED|PAID|REVERSED), grossTotal:string(BigDecimal), deductionTotal:string(BigDecimal), netTotal:string(BigDecimal), employerCostTotal:string(BigDecimal), calculatedAt:Instant, approvedAt:Instant, postedAt:Instant, paidAt:Instant, reversedAt:Instant, approvedBy:string, postedBy:string, glEntryUid:string, reversalOfRunUid:string

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/hr/payroll-runs` | `HR.PAYROLL.VIEW` | list | query params: companyId:string (required), Pageable | ApiResponse<List<PayrollRunDto>> + PageMeta |
| POST | `/api/v1/hr/payroll-runs` | `HR.PAYROLL.RUN` | create | CreatePayrollRunRequest{periodMonth:short(req,1-12), periodYear:short, payDate:LocalDate(req), branchId:string}. NOTE: companyId is taken from the auth principal, NOT the body. | PayrollRunDto (raw, 201 Created), status=DRAFT, runNumber=PAYRUN-#### |
| GET | `/api/v1/hr/payroll-runs/uid/{uid}` | `HR.PAYROLL.VIEW` | getByUid |  | PayrollRunDto (raw) |
| GET | `/api/v1/hr/payroll-runs/uid/{uid}/lines` | `HR.PAYROLL.VIEW` | list (child resource: per-employee payroll lines) |  | List<PayrollLineDto> (raw list, not paginated, no ApiResponse wrapper). PayrollLineDto fields: id,uid,payrollRunId,employeeId,employeeNumber,employeeName,departmentName,grossAmount,taxableAmount,netAmount,payeAmount,nssfEmployeeAmount,heslbAmount,voluntaryDeductionTotal,loanDeductionTotal,nssfEmployerAmount,wcfEmployerAmount,sdlEmployerAmount,status(OK\|FLAGGED),flagReason,currency |
| POST | `/api/v1/hr/payroll-runs/uid/{uid}/calculate` | `HR.PAYROLL.RUN` | calculate (re-runnable; precondition status in DRAFT\|CALCULATED\|APPROVED, re-opens to CALCULATED voiding approval) |  | PayrollRunDto (raw), status -> CALCULATED |
| POST | `/api/v1/hr/payroll-runs/uid/{uid}/approve` | `HR.PAYROLL.APPROVE` | approve (precondition status==CALCULATED and no FLAGGED lines) |  | PayrollRunDto (raw), status -> APPROVED |
| POST | `/api/v1/hr/payroll-runs/uid/{uid}/post` | `HR.PAYROLL.POST` | post (precondition status==APPROVED; emits PAYROLL.FINALISED, freezes payslips) |  | PayrollRunDto (raw), status -> POSTED |
| POST | `/api/v1/hr/payroll-runs/uid/{uid}/disburse` | `HR.PAYROLL.DISBURSE` | disburse (precondition status==POSTED and netTotal>0; pays net wages via Cash & Bank) | DisburseRequest{cashBankAccountUid:string(req), txnDate:LocalDate(optional, defaults to run payDate)} | PayrollRunDto (raw), status -> PAID |
| POST | `/api/v1/hr/payroll-runs/uid/{uid}/reverse` | `HR.PAYROLL.REVERSE` | reverse (precondition status==POSTED or PAID; emits PAYROLL.REVERSED reversing journal) |  | PayrollRunDto (raw), status -> REVERSED (terminal) |

## Resource: `leave-requests`  (base `/api/v1/hr/leave-requests`)

**Status enum:** PENDING, APPROVED, REJECTED, CANCELLED

**Primary DTO fields:** id:string, uid:string, companyId:string, employeeId:string, employeeName:string, leaveTypeId:string, leaveTypeName:string, fromDate:LocalDate, toDate:LocalDate, days:string(BigDecimal), status:LeaveRequestStatus(PENDING|APPROVED|REJECTED|CANCELLED), decidedBy:string, decidedAt:Instant, reason:string, decisionNote:string

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/hr/leave-requests` | `HR.LEAVE.VIEW` | list | query params: companyId:string (required), Pageable | ApiResponse<List<LeaveRequestDto>> + PageMeta |
| POST | `/api/v1/hr/leave-requests/employee/{employeeUid}` | `HR.LEAVE.MANAGE` | create (submit a leave request for an employee, nested under employeeUid) | SubmitLeaveRequest{leaveTypeId:string(req), fromDate:LocalDate(req), toDate:LocalDate(req), days:string(BigDecimal, req, positive), reason:string} | LeaveRequestDto (raw, 201 Created), status=PENDING. Perm is scoped on 'employee' type using employeeUid. |
| GET | `/api/v1/hr/leave-requests/uid/{uid}` | `HR.LEAVE.VIEW` | getByUid |  | LeaveRequestDto (raw). Perm scoped on 'leaverequest'. |
| POST | `/api/v1/hr/leave-requests/uid/{uid}/decide` | `HR.LEAVE.APPROVE` | approve/reject (decide a pending leave request) | DecideLeaveRequest{decision:LeaveRequestStatus(req), decisionNote:string}. SERVICE CONSTRAINT: decision MUST be APPROVED or REJECTED only (other values -> 400). | LeaveRequestDto (raw), status -> APPROVED\|REJECTED. Perm scoped on 'leaverequest'. |

## Resource: `loans`  (base `/api/v1/hr/loans`)

**Status enum:** ACTIVE, SETTLED, CANCELLED

**Primary DTO fields:** id:string, uid:string, companyId:string, employeeId:string, employeeName:string, loanNumber:string, principalAmount:string(BigDecimal), installmentAmount:string(BigDecimal), outstandingAmount:string(BigDecimal), glAccountId:string, status:LoanStatus(ACTIVE|SETTLED|CANCELLED), startDate:LocalDate, currency:string

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/hr/loans` | `HR.LOAN.MANAGE` | list | query params: companyId:string (required), Pageable | ApiResponse<List<EmployeeLoanDto>> + PageMeta |
| POST | `/api/v1/hr/loans/employee/{employeeUid}` | `HR.LOAN.MANAGE` | create (nested under employeeUid) | CreateLoanRequest{employeeId:string(req), principalAmount:string(BigDecimal, req, positive), installmentAmount:string(BigDecimal, req, positive), glAccountId:string(req), startDate:LocalDate(req), currency:string(defaults TZS)}. Note employeeId in body is redundant with the path employeeUid (service resolves by path uid). Perm scoped on 'employee'. | EmployeeLoanDto (raw, 201 Created) |
| GET | `/api/v1/hr/loans/uid/{uid}` | `HR.LOAN.MANAGE` | getByUid |  | EmployeeLoanDto (raw). Perm scoped on 'employeeloan'. |
| POST | `/api/v1/hr/loans/uid/{uid}/approve` | `HR.LOAN.MANAGE` | approve (sets status to ACTIVE) |  | EmployeeLoanDto (raw), status -> ACTIVE. Perm scoped on 'employeeloan'. |