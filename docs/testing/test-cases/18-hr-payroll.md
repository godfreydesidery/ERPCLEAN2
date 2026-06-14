# HR & Payroll — Test Cases

End-to-end and functional test cases for the HR & Payroll domain: departments, employees (employment-status lifecycle), employment contracts (types + terminate), leave requests (lifecycle + accrual inputs), employee loans (lifecycle), pay components, the payroll run lifecycle (calculate → approve → post → disburse → reverse, with GL + Cash & Bank effects), and statutory setup (PAYE band sets + statutory rate sets).
Scope is the eight `Hr*Controller` REST endpoints under `/api/v1/hr/**` and their Angular admin screens under `/admin/hr/**`. Every endpoint, every status transition (legal and illegal), every relevant enum variation, RBAC per permission code, the four screen states, pagination, and the C1–C9 conventions are covered.

## Modules / submodules covered

| Submodule | Controller (base path) | Frontend route(s) | Frontend service |
|---|---|---|---|
| Departments | `HrDepartmentController` (`/api/v1/hr/departments`) | `/admin/hr/departments` | `departments/hr-department.service.ts` |
| Employees | `HrEmployeeController` (`/api/v1/hr/employees`) | `/admin/hr/employees`, `/admin/hr/employees/uid/:uid` | `hr-payroll.service.ts` |
| Employment Contracts | `HrContractController` (`/api/v1/hr/contracts`) | `/admin/hr/contracts` (employee-picker driven; no per-contract route) | `contracts/hr-contract.service.ts` |
| Leave Requests | `HrLeaveController` (`/api/v1/hr/leave-requests`) | `/admin/hr/leave-requests`, `/admin/hr/leave-requests/uid/:uid` | `hr-payroll.service.ts` |
| Employee Loans | `HrLoanController` (`/api/v1/hr/loans`) | `/admin/hr/loans`, `/admin/hr/loans/uid/:uid` | `hr-payroll.service.ts` |
| Pay Components | `HrPayComponentController` (`/api/v1/hr/pay-components`) | `/admin/hr/pay-components`, `/admin/hr/pay-components/uid/:uid` | `hr-payroll.service.ts` |
| Payroll Runs | `HrPayrollController` (`/api/v1/hr/payroll-runs`) | `/admin/hr/payroll-runs`, `/admin/hr/payroll-runs/uid/:uid` | `hr-payroll.service.ts` |
| Statutory Setup | `HrStatutoryController` (`/api/v1/hr/statutory`) | `/admin/hr/statutory` (PAYE bands + rate sets; no per-set route) | `statutory/hr-statutory.service.ts` |

Nav: shell group **"HR & Payroll"** (`shell.component.ts`) exposes Employees, Departments, Employee Contracts, Pay Components, Payroll Runs, Leave Requests, Employee Loans, Statutory Setup.

### Endpoint inventory (verified)

- **Departments:** `GET /` (`HR.EMPLOYEE.VIEW`), `POST /` (`HR.EMPLOYEE.MANAGE`), `GET /uid/{uid}`, `PUT /uid/{uid}` (`HR.EMPLOYEE.MANAGE`), `DELETE /uid/{uid}` deactivate (`HR.EMPLOYEE.MANAGE`).
- **Employees:** `GET /` (paged, `HR.EMPLOYEE.VIEW`), `POST /` (`HR.EMPLOYEE.MANAGE`), `GET /uid/{uid}` (`HR.EMPLOYEE.VIEW`), `PUT /uid/{uid}` (`HR.EMPLOYEE.MANAGE`), `DELETE /uid/{uid}` archive→TERMINATED (`HR.EMPLOYEE.MANAGE`).
- **Contracts:** `GET /?companyId&employeeId` list-by-employee (`HR.EMPLOYEE.VIEW`), `POST /employee/{employeeUid}` (`HR.EMPLOYEE.MANAGE`), `GET /uid/{uid}` (`HR.EMPLOYEE.VIEW`), `DELETE /uid/{uid}/terminate` (`HR.EMPLOYEE.MANAGE`).
- **Leave:** `GET /` (paged, `HR.LEAVE.VIEW`), `POST /employee/{employeeUid}` submit (`HR.LEAVE.MANAGE`), `GET /uid/{uid}` (`HR.LEAVE.VIEW`), `POST /uid/{uid}/decide` (`HR.LEAVE.APPROVE`).
- **Loans:** `GET /` (paged, `HR.LOAN.MANAGE`), `POST /employee/{employeeUid}` (`HR.LOAN.MANAGE`), `GET /uid/{uid}` (`HR.LOAN.MANAGE`), `POST /uid/{uid}/approve` (`HR.LOAN.MANAGE`).
- **Pay components:** `GET /` (`HR.PAYCOMPONENT.MANAGE`), `POST /` (`HR.PAYCOMPONENT.MANAGE`), `GET /uid/{uid}`, `PUT /uid/{uid}`, `DELETE /uid/{uid}` deactivate (all `HR.PAYCOMPONENT.MANAGE`).
- **Payroll runs:** `GET /` (paged, `HR.PAYROLL.VIEW`), `POST /` (`HR.PAYROLL.RUN`), `GET /uid/{uid}` (`HR.PAYROLL.VIEW`), `GET /uid/{uid}/lines` (`HR.PAYROLL.VIEW`), `POST /uid/{uid}/calculate` (`HR.PAYROLL.RUN`), `POST /uid/{uid}/approve` (`HR.PAYROLL.APPROVE`), `POST /uid/{uid}/post` (`HR.PAYROLL.POST`), `POST /uid/{uid}/disburse` (`HR.PAYROLL.DISBURSE`), `POST /uid/{uid}/reverse` (`HR.PAYROLL.REVERSE`).
- **Statutory:** `GET /paye-bands` + `POST /paye-bands` + `GET /paye-bands/uid/{uid}` (`HR.STATUTORY.MANAGE`); `GET /rates` + `POST /rates` + `GET /rates/uid/{uid}` (`HR.STATUTORY.MANAGE`).

> **Backend-only (no controller / no UI):** `CreateRecurringItemRequest` + `EmployeeRecurringItemDto` (per-employee recurring earnings/deductions) and `LeaveType` / `LeaveBalance` (DTOs `LeaveTypeDto`, `LeaveBalanceDto`) have **no REST endpoint** in this domain. Recurring items and leave-type accrual are consumed by `PayrollRunServiceImpl.calculate()` and `sumApprovedUnpaidDaysOverlapping(...)` but are not editable via these controllers. `leaveTypeId` is supplied as a numeric FK in `SubmitLeaveRequest` — there is no leave-type picker endpoint in scope. Tests that need a leave type / recurring item / leave balance must seed them directly in the DB or via another module; this is called out in the affected cases.

## Permission codes in scope (exact `@PreAuthorize` codes; seeded in `V63__hr_permissions.sql`)

`HR.EMPLOYEE.VIEW`, `HR.EMPLOYEE.MANAGE`, `HR.PAYCOMPONENT.MANAGE`, `HR.LEAVE.VIEW`, `HR.LEAVE.MANAGE`, `HR.LEAVE.APPROVE`, `HR.LOAN.MANAGE`, `HR.STATUTORY.MANAGE`, `HR.PAYROLL.VIEW`, `HR.PAYROLL.RUN`, `HR.PAYROLL.APPROVE`, `HR.PAYROLL.POST`, `HR.PAYROLL.REVERSE`, `HR.PAYROLL.DISBURSE`.

> Note: there is **no** `HR.LOAN.VIEW` or `HR.LOAN.APPROVE` — loan list/view/approve all gate on `HR.LOAN.MANAGE`. There is **no** `HR.PAYROLL.MANAGE`; payroll uses fine-grained VIEW/RUN/APPROVE/POST/DISBURSE/REVERSE. Contracts and departments share the employee permissions (`HR.EMPLOYEE.VIEW`/`MANAGE`). Authorization is **by permission code only**, never role name.

## Enums in scope (exact values, from `domain/enums/`)

- `ContractType` = {PERMANENT, FIXED_TERM, CASUAL, PROBATION}
- `EmploymentStatus` = {ACTIVE, ON_LEAVE, SUSPENDED, TERMINATED}
- `LeaveRequestStatus` = {PENDING, APPROVED, REJECTED, CANCELLED}
- `LeaveAccrualMethod` = {ANNUAL_GRANT, MONTHLY_ACCRUAL}
- `LoanStatus` = {ACTIVE, SETTLED, CANCELLED}
- `PayComponentKind` = {EARNING, DEDUCTION}; `PayComponentBasis` = {FIXED, PERCENT_OF_BASIC}
- `PayFrequency` = {MONTHLY} (v1 only)
- `PayrollRunStatus` = {DRAFT, CALCULATED, APPROVED, POSTED, PAID, REVERSED}
- `PayrollLineStatus` = {OK, FLAGGED}
- `StatutoryRateType` = {NSSF, WCF, SDL, HESLB}

### Verified lifecycle transition rules (from service impls)

- **Payroll run** (`PayrollRunServiceImpl`): create→`DRAFT`; `calculate` allowed from {DRAFT, CALCULATED, APPROVED} → sets `CALCULATED` (idempotent recalc, deletes prior lines); `approve` requires `CALCULATED` AND zero `FLAGGED` lines → `APPROVED`; `post` requires `APPROVED` → `POSTED` (publishes `PAYROLL_FINALISED`, generates payslips); `disburse` requires `POSTED` and `netTotal > 0` → `PAID` (posts Cash & Bank OUT DR NET_WAGES_PAYABLE / CR bank); `reverse` requires `POSTED` or `PAID` → `REVERSED` (publishes `PAYROLL_REVERSED`). Create blocks a second **active** run for the same period (ConflictException). A negative net line is set `FLAGGED` and blocks approval.
- **Employment status** (`EmployeeServiceImpl`): create→`ACTIVE`; `archive` (DELETE) sets `TERMINATED`. ON_LEAVE / SUSPENDED are valid enum states but there is **no controller endpoint** to set them in this domain — assert they are not settable via these screens.
- **Contract** (`ContractServiceImpl`): create blocked if employee already has an active contract; `terminate` (DELETE …/terminate) sets `active=false` (no separate status enum — boolean `active`). Currency is forced to `TZS`; `payFrequency` defaults `MONTHLY`.
- **Leave** (`LeaveServiceImpl`): submit→`PENDING`; `decide` accepts **only** `APPROVED` or `REJECTED` (PENDING/CANCELLED in the decision body → `IllegalArgumentException` 400). There is no controller-level guard preventing a re-decide of an already-decided request — assert observed behaviour.
- **Loan** (`EmployeeLoanServiceImpl`): create→default status from `EmployeeLoan` ctor; `approve` sets `ACTIVE`. `close`→`SETTLED` exists in the service but has **no controller endpoint** (backend-only).

## Type / role variations exercised

| Dimension | Variations covered |
|---|---|
| User roles (by permission) | `rootadmin` (superuser bypass; positive smoke only), ACCOUNTANT / ORG_ADMIN-style holders of HR perms, a CUSTOM role with a subset (e.g. only `HR.EMPLOYEE.VIEW`), a NO-PERMISSION user (forbidden + empty nav) |
| Payroll perm split | distinct users holding only `HR.PAYROLL.VIEW`, only `…RUN`, only `…APPROVE`, only `…POST`, only `…DISBURSE`, only `…REVERSE` (each action denied to the others) |
| Leave perm split | `HR.LEAVE.VIEW` (read), `HR.LEAVE.MANAGE` (submit), `HR.LEAVE.APPROVE` (decide) on separate users |
| ContractType | PERMANENT, FIXED_TERM (with endDate), CASUAL, PROBATION |
| EmploymentStatus | ACTIVE (created), TERMINATED (archived); ON_LEAVE/SUSPENDED asserted not-settable |
| PayComponent | EARNING×FIXED, EARNING×PERCENT_OF_BASIC, DEDUCTION×FIXED, taxable vs non-taxable, pensionable vs not |
| StatutoryRateType | NSSF, WCF, SDL, HESLB (+ PAYE band set) |
| LoanStatus | created → ACTIVE (approve) |
| LeaveRequestStatus | PENDING → APPROVED, PENDING → REJECTED, illegal decision values |
| Branch/company | default vs non-default branch on employee/run; multi-branch company; user assigned to ONE branch acting in another (denied); multi-company isolation (tenant A cannot see tenant B) |
| Screen states | loading / empty / error / forbidden on every list + detail |

---

## Departments

### TC-HR-001 — List departments (loaded state + envelope)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Departments (`/admin/hr/departments` · `GET /api/v1/hr/departments`)
- **Permission / Role:** `HR.EMPLOYEE.VIEW` — runs as a user with that permission; also as the NO-PERMISSION user → expect forbidden / hidden nav.
- **Preconditions / Seed:** A company exists; ≥1 department seeded for that company.
- **Steps:**
  1. Log in; navigate to `/admin/hr/departments`.
  2. Select the company in the company selector if shown.
  3. Wait for the table to render.
- **Test Data:** Department code `HR`, name `Human Resources`.
- **Expected Result:** Table lists departments by **code + name** (human-readable). Response is `ApiResponse<List<DepartmentDto>>` (non-paginated list — `GET /` takes only `companyId`).
- **Convention Assertions:** C2 envelope unwrapped; C4 loaded state; C1 no raw uid in any cell (reference by code/name); C6 axe scan clean; C7 only this company's departments shown.
- **Negative / Edge:** NO-PERMISSION user → nav item hidden and direct route → forbidden (route guard `requirePermission('HR.EMPLOYEE.VIEW')`).

### TC-HR-002 — Departments list: empty / error / forbidden states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Departments (`/admin/hr/departments` · `GET /api/v1/hr/departments`)
- **Permission / Role:** `HR.EMPLOYEE.VIEW`; forbidden run as NO-PERMISSION user.
- **Preconditions / Seed:** A company with **zero** departments (empty); a route-intercept to force a 500 (error); a no-perm session (forbidden).
- **Steps:**
  1. Empty: select a company with no departments → assert empty state copy.
  2. Error: intercept `GET …/departments` → 500 → assert error state.
  3. Forbidden: log in as NO-PERMISSION user → assert forbidden state / guard redirect.
- **Expected Result:** Distinct empty, error, forbidden renderings.
- **Convention Assertions:** C4 four states distinct; C3 RBAC forbidden; C6 axe on each state.
- **Negative / Edge:** Network timeout treated as error not empty.

### TC-HR-003 — Create department (valid)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Departments (`/admin/hr/departments` · `POST /api/v1/hr/departments`)
- **Permission / Role:** `HR.EMPLOYEE.MANAGE` — runs as a manager; also as a `HR.EMPLOYEE.VIEW`-only user → create control hidden/disabled, API 403.
- **Preconditions / Seed:** A company selected.
- **Steps:**
  1. Navigate to `/admin/hr/departments`; click **New department**.
  2. Enter code + name; save.
- **Test Data:** code `FIN`, name `Finance`.
- **Expected Result:** 201 Created; `DepartmentDto` returned; row appears in list with code `FIN` / name `Finance`.
- **Convention Assertions:** C1 no uid typed/shown; C2 envelope; C3 manage-gated; C9 masters are deactivated not deleted (see TC-HR-005).
- **Negative / Edge:** blank code or name → `@NotBlank` 400; code > 30 chars or name > 120 chars → `@Size` 400; VIEW-only user → 403.

### TC-HR-004 — Edit department
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Departments (`PUT /api/v1/hr/departments/uid/{uid}`)
- **Permission / Role:** `HR.EMPLOYEE.MANAGE` (scoped) — also VIEW-only → 403.
- **Preconditions / Seed:** TC-HR-003 department exists.
- **Steps:** Open the department for edit (selected by name in list); change name; save.
- **Test Data:** name `Finance & Accounts`.
- **Expected Result:** Updated `DepartmentDto`; list reflects new name.
- **Convention Assertions:** C1 uid only in URL path, never shown; C2 envelope; C3 scoped manage.
- **Negative / Edge:** unknown uid → 404; blank name → 400.

### TC-HR-005 — Deactivate department (soft-delete)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Departments (`DELETE /api/v1/hr/departments/uid/{uid}`)
- **Permission / Role:** `HR.EMPLOYEE.MANAGE` (scoped) — VIEW-only → 403.
- **Preconditions / Seed:** A department exists.
- **Steps:** From the department row, choose **Deactivate**; confirm.
- **Expected Result:** 204 No Content; department becomes inactive (soft-delete via `service.deactivate`), not hard-deleted.
- **Convention Assertions:** C9 soft-delete (deactivate, not destroy); C3 manage-gated; C1 picked by name.
- **Negative / Edge:** deactivating an unknown uid → 404; VIEW-only user → 403.

---

## Employees

### TC-HR-010 — Employees list with pagination
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Employees (`/admin/hr/employees` · `GET /api/v1/hr/employees` paged)
- **Permission / Role:** `HR.EMPLOYEE.VIEW`; NO-PERMISSION user → forbidden.
- **Preconditions / Seed:** ≥ 21 employees in the company (so > 1 page at size 20).
- **Steps:**
  1. Navigate to `/admin/hr/employees`.
  2. Read the table; operate the paginator (FIRST/PREVIOUS/page-numbers/NEXT/LAST).
- **Expected Result:** Page 1 shows 20 rows by **employee number + name + department name + status**; `meta` carries `{page,size,totalElements,totalPages,hasNext}`; navigating pages refetches.
- **Convention Assertions:** C5 full paginator controls (hidden when 1 page); C2 envelope+meta (service uses SKIP_UNWRAP to read meta); C1 no uid in cells; C4 loaded; C6 axe clean; C8 status as enum text/badge.
- **Negative / Edge:** size param boundary (page beyond last → empty page meta); NO-PERMISSION → guard forbidden.

### TC-HR-011 — Employees list: loading / empty / error / forbidden
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Employees (`/admin/hr/employees`)
- **Permission / Role:** `HR.EMPLOYEE.VIEW`; forbidden via NO-PERMISSION user.
- **Preconditions / Seed:** empty company; intercept for 500; no-perm session.
- **Steps:** Exercise each of the four states as in TC-HR-002.
- **Expected Result:** Four visually distinct states.
- **Convention Assertions:** C4 four states; C6 axe each; C3 RBAC.

### TC-HR-012 — Create employee (minimal required fields)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Employees (`POST /api/v1/hr/employees`)
- **Permission / Role:** `HR.EMPLOYEE.MANAGE` — also VIEW-only user → control hidden, API 403.
- **Variation:** department chosen by **name via picker**; branch = default.
- **Preconditions / Seed:** A company and ≥1 department exist.
- **Steps:**
  1. Navigate to `/admin/hr/employees`; click **New employee**.
  2. Fill `firstName`, `lastName`, `hireDate` (required); pick a department by name; leave optional fields blank.
  3. Save.
- **Test Data:** first `Asha`, last `Mussa`, hireDate `2026-01-15`, department picked = `Finance`.
- **Expected Result:** 201; `EmployeeDto` returned with server-generated `employeeNumber` (`EMP-000001` format), `status = ACTIVE`. Row appears in list.
- **Convention Assertions:** C1 department selected via picker by name (uid stored under the hood, never typed; no raw uid on screen); C2 envelope; C3 manage-gated; C8 dates ISO `yyyy-MM-dd`.
- **Negative / Edge:** missing firstName/lastName/hireDate → `@NotBlank`/`@NotNull` 400; VIEW-only user → 403.

### TC-HR-013 — Create employee with full optional set
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Employees (`POST /api/v1/hr/employees`)
- **Permission / Role:** `HR.EMPLOYEE.MANAGE`.
- **Variation:** branch = **non-default** branch in a multi-branch company.
- **Preconditions / Seed:** Multi-branch company; user assigned to the non-default branch.
- **Steps:** Create employee supplying `nationalId`, `tin`, `nssfNumber`, `heslbNumber`, `dateOfBirth`, `gender`, `jobTitle`, non-default `branchId` (picked by branch name).
- **Test Data:** tin `123-456-789`, nssfNumber `NSSF-998`, dob `1990-04-02`, gender `F`, jobTitle `Accountant`.
- **Expected Result:** Employee created on the chosen branch; detail screen shows all fields.
- **Convention Assertions:** C1 branch via picker by name; C7 employee scoped to that company+branch.
- **Negative / Edge:** user acting in a branch they are NOT assigned to → denied by ScopeGuard.

### TC-HR-014 — Employee detail: view + edit
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Employees (`/admin/hr/employees/uid/:uid` · `GET`/`PUT …/uid/{uid}`)
- **Permission / Role:** `HR.EMPLOYEE.VIEW` to view; `HR.EMPLOYEE.MANAGE` to save (edit form disabled without it — `canManage` computed).
- **Preconditions / Seed:** TC-HR-012 employee exists.
- **Steps:**
  1. From the list, open the employee (link uses uid in the URL path only).
  2. Verify detail fields; edit `jobTitle`; save.
- **Test Data:** jobTitle `Senior Accountant`.
- **Expected Result:** Detail shows employee number, name, status badge (ACTIVE), department name; save returns updated `EmployeeDto`; success alert.
- **Convention Assertions:** C1 uid only in URL, not rendered as a label; C2 envelope; C3 edit gated on MANAGE (VIEW-only sees read-only); C4 loading/idle/error on detail; C6 axe clean.
- **Negative / Edge:** clearing firstName → client validation blocks save ("First name is required."); server-side `@NotBlank` 400 if bypassed; unknown uid → 404 → error state.

### TC-HR-015 — Archive employee → TERMINATED
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Employees (`DELETE /api/v1/hr/employees/uid/{uid}`)
- **Permission / Role:** `HR.EMPLOYEE.MANAGE` (scoped); VIEW-only → 403.
- **Variation:** EmploymentStatus ACTIVE → TERMINATED.
- **Preconditions / Seed:** An ACTIVE employee.
- **Steps:** On the employee detail, click **Archive**; confirm.
- **Expected Result:** 204; employee `status` flips to `TERMINATED` (UI updates badge to danger/`text-bg-danger`); record retained (soft-delete, append-only status change).
- **Convention Assertions:** C9 soft-delete (status change, not row removal); C3 manage-gated; C1 picked by name/URL uid only.
- **Negative / Edge:** archive a non-existent uid → 404; VIEW-only → 403; **assert ON_LEAVE/SUSPENDED cannot be set via UI** (no endpoint) — only ACTIVE (create) and TERMINATED (archive) are reachable in this domain.

### TC-HR-016 — Multi-tenant isolation on employees
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Employees (`GET /api/v1/hr/employees?companyId=…`)
- **Permission / Role:** `HR.EMPLOYEE.VIEW` in tenant A.
- **Preconditions / Seed:** Tenant A and tenant B each have employees.
- **Steps:**
  1. As a tenant-A user, list employees → only A's appear.
  2. Attempt to fetch a tenant-B employee uid directly via the detail route.
- **Expected Result:** Tenant B employees never listed; direct B-uid fetch denied by `ScopeGuard.assertCanActIn` (403/forbidden).
- **Convention Assertions:** C7 company-scoping enforced; C3 scoped permission; C1 uid-in-URL only.
- **Negative / Edge:** cross-company `companyId` query param for a company the user can't act in → 403.

---

## Employment Contracts

### TC-HR-020 — Contracts screen: pick employee, list their contracts
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Contracts (`/admin/hr/contracts` · `GET /api/v1/hr/contracts?companyId&employeeId`)
- **Permission / Role:** `HR.EMPLOYEE.VIEW`; NO-PERMISSION user → forbidden.
- **Preconditions / Seed:** An employee with ≥1 contract.
- **Steps:**
  1. Navigate to `/admin/hr/contracts`; select company.
  2. Pick an employee via `<app-uid-picker>` (search by name, hint = employee number).
  3. Read the contract panel.
- **Expected Result:** Panel lists that employee's contracts with type, base salary, start/end, active flag.
- **Convention Assertions:** C1 employee chosen via picker by name — the resolved `employeeId` (body FK) is derived from the picked uid, never typed; no raw uid on screen; C2 envelope; C4 loading/idle/empty/error states; C6 axe; C7 company-scoped.
- **Negative / Edge:** employee with no contracts → empty panel state; NO-PERMISSION → forbidden.

### TC-HR-021 — Create PERMANENT contract
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Contracts (`POST /api/v1/hr/contracts/employee/{employeeUid}`)
- **Permission / Role:** `HR.EMPLOYEE.MANAGE` (scoped to employee uid); VIEW-only → 403.
- **Variation:** ContractType = PERMANENT; statutory toggles payeResident=true, nssfMember=true, others false.
- **Preconditions / Seed:** An employee with **no active contract**.
- **Steps:**
  1. On `/admin/hr/contracts`, pick the employee by name; open the create form.
  2. Choose contract type PERMANENT, enter base salary, start date; set statutory checkboxes; save.
- **Test Data:** baseSalary `1500000`, startDate `2026-01-01`, payeResident on, nssfMember on.
- **Expected Result:** 201; `ContractDto` with currency forced `TZS`, `payFrequency = MONTHLY`, `active = true`.
- **Convention Assertions:** C1 employee via picker; C2 envelope; C3 scoped manage; C8 money handled as string ("TZS 1,500,000.00" on display).
- **Negative / Edge:** baseSalary missing or ≤ 0 → `@NotNull`/`@Positive` 400; startDate missing → 400; creating a 2nd contract while one is active → ConflictException ("Employee already has an active contract.") 409.

### TC-HR-022 — Create FIXED_TERM contract with end date
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Contracts (`POST …/employee/{employeeUid}`)
- **Permission / Role:** `HR.EMPLOYEE.MANAGE`.
- **Variation:** ContractType = FIXED_TERM with `endDate`.
- **Preconditions / Seed:** Employee with no active contract.
- **Steps:** Create contract type FIXED_TERM with start and end dates.
- **Test Data:** start `2026-02-01`, end `2026-12-31`, baseSalary `900000`.
- **Expected Result:** Contract created with the end date retained.
- **Convention Assertions:** C1 picker; C8 ISO dates.
- **Negative / Edge:** end date before start date (no explicit BE guard verified — record observed behaviour; flag if accepted).

### TC-HR-023 — Create CASUAL and PROBATION contracts
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Contracts (`POST …/employee/{employeeUid}`)
- **Permission / Role:** `HR.EMPLOYEE.MANAGE`.
- **Variation:** ContractType = CASUAL; then PROBATION (on different employees).
- **Preconditions / Seed:** Two employees, each with no active contract.
- **Steps:** Create a CASUAL contract for employee 1, a PROBATION contract for employee 2.
- **Expected Result:** Both created with the correct `contractType` echoed in `ContractDto`.
- **Convention Assertions:** C1 picker; C3 manage.
- **Negative / Edge:** invalid/blank contractType → `@NotNull` 400.

### TC-HR-024 — Terminate contract (active → inactive)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Contracts (`DELETE /api/v1/hr/contracts/uid/{uid}/terminate`)
- **Permission / Role:** `HR.EMPLOYEE.MANAGE` (scoped); VIEW-only → 403.
- **Variation:** contract `active` true → false.
- **Preconditions / Seed:** Employee with one active contract.
- **Steps:** From the contract row, click **Terminate**; confirm.
- **Expected Result:** 204; contract `active = false`. A new contract can now be created for that employee (re-test TC-HR-021 succeeds afterward).
- **Convention Assertions:** C9 append-only / no hard delete (active flag flips); C3 manage; C1 picked by display, uid in URL only.
- **Negative / Edge:** terminate unknown uid → 404; terminating an already-inactive contract (record observed behaviour — no status guard verified).

### TC-HR-025 — Contract RBAC denial (VIEW-only)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Contracts (`POST`/`DELETE …`)
- **Permission / Role:** user with only `HR.EMPLOYEE.VIEW`.
- **Steps:** Open `/admin/hr/contracts`; pick employee; attempt create/terminate.
- **Expected Result:** Create form / terminate action hidden or disabled (`canManage` false); direct API call → 403.
- **Convention Assertions:** C3 RBAC; C4 forbidden affordance.

---

## Leave Requests

### TC-HR-030 — Leave requests list (paged) + four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Leave (`/admin/hr/leave-requests` · `GET /api/v1/hr/leave-requests` paged)
- **Permission / Role:** `HR.LEAVE.VIEW`; NO-PERMISSION → forbidden.
- **Preconditions / Seed:** ≥ 21 leave requests for pagination.
- **Steps:** Navigate; read table; operate paginator; force empty/error/forbidden.
- **Expected Result:** Rows show employee name, leave-type name, from/to dates, days, status badge; meta paginates.
- **Convention Assertions:** C5 paginator; C2 envelope+meta; C1 names not uids; C4 four states; C6 axe; C8 ISO dates.
- **Negative / Edge:** NO-PERMISSION → forbidden.

### TC-HR-031 — Submit leave request
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Leave (`POST /api/v1/hr/leave-requests/employee/{employeeUid}`)
- **Permission / Role:** `HR.LEAVE.MANAGE` (scoped to employee); user with only `HR.LEAVE.VIEW` → 403.
- **Variation:** new request → status PENDING.
- **Preconditions / Seed:** An employee; a `LeaveType` seeded in DB (no UI/endpoint — seed directly; `leaveTypeId` is a numeric FK in `SubmitLeaveRequest`).
- **Steps:**
  1. Pick the employee by name.
  2. Enter leaveTypeId (or pick if UI provides the seeded type), fromDate, toDate, days, reason; submit.
- **Test Data:** leaveTypeId = seeded Annual leave id, from `2026-03-10`, to `2026-03-14`, days `5`, reason `Family`.
- **Expected Result:** 201; `LeaveRequestDto` with `status = PENDING`, employeeName + leaveTypeName resolved.
- **Convention Assertions:** C1 employee via picker; C2 envelope; C3 manage-gated; C8 ISO dates, `days` numeric.
- **Negative / Edge:** missing leaveTypeId/fromDate/toDate/days → `@NotNull` 400; days ≤ 0 → `@Positive` 400; VIEW-only user → 403.

### TC-HR-032 — Approve leave request (PENDING → APPROVED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Leave (`POST /api/v1/hr/leave-requests/uid/{uid}/decide`)
- **Permission / Role:** `HR.LEAVE.APPROVE` (scoped); a `HR.LEAVE.MANAGE`-only user (submitter) → 403 on decide.
- **Variation:** decision = APPROVED.
- **Preconditions / Seed:** A PENDING leave request (TC-HR-031).
- **Steps:** Open the request detail (`/admin/hr/leave-requests/uid/:uid`); choose **Approve**; add a note; submit.
- **Test Data:** decision `APPROVED`, decisionNote `OK`.
- **Expected Result:** Status → APPROVED; `decidedBy`/`decidedAt` set; note stored.
- **Convention Assertions:** C3 approve permission separate from submit; C2 envelope; C1 uid in URL only.
- **Negative / Edge:** decide as MANAGE-only user → 403; decide with decision = `PENDING` or `CANCELLED` → `IllegalArgumentException` ("Decision must be APPROVED or REJECTED.") 400.

### TC-HR-033 — Reject leave request (PENDING → REJECTED)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Leave (`POST …/uid/{uid}/decide`)
- **Permission / Role:** `HR.LEAVE.APPROVE`.
- **Variation:** decision = REJECTED.
- **Preconditions / Seed:** A PENDING leave request.
- **Steps:** Open detail; choose **Reject**; submit with note.
- **Test Data:** decision `REJECTED`, note `Coverage gap`.
- **Expected Result:** Status → REJECTED; decidedBy/At set.
- **Convention Assertions:** C3; C2 envelope.
- **Negative / Edge:** missing `decision` in body → `@NotNull` 400.

### TC-HR-034 — Illegal leave decision values rejected
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Leave (`POST …/uid/{uid}/decide`)
- **Permission / Role:** `HR.LEAVE.APPROVE`.
- **Variation:** illegal transitions — decision in {PENDING, CANCELLED}.
- **Preconditions / Seed:** A PENDING request.
- **Steps:** Send decide with decision = `PENDING`; then `CANCELLED`.
- **Expected Result:** 400 with message "Decision must be APPROVED or REJECTED." for both; status unchanged.
- **Convention Assertions:** C2 errors array populated; C3 still gated.
- **Negative / Edge:** re-deciding an already-APPROVED request (no controller guard verified — record observed behaviour; flag if it silently overwrites).

### TC-HR-035 — Leave accrual inputs feed payroll (documentation/integration)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Leave ↔ Payroll (`PayrollRunServiceImpl.calculate` → `sumApprovedUnpaidDaysOverlapping`)
- **Permission / Role:** `HR.LEAVE.APPROVE` + `HR.PAYROLL.RUN`.
- **Variation:** approved **unpaid** leave overlapping the payroll period drives pro-rata.
- **Preconditions / Seed:** Approved unpaid leave (via a leave type flagged unpaid — seeded in DB) overlapping the run month.
- **Steps:** Approve an unpaid leave spanning days in month M; create + calculate the payroll run for M.
- **Expected Result:** The employee's basic salary is pro-rated against `DEFAULT_PERIOD_WORKING_DAYS = 22` by the approved unpaid leave days.
- **Convention Assertions:** C7 same-company scoping; documents that leave-type accrual/unpaid flag is **backend-only** (no UI in this domain).
- **Negative / Edge:** leave fully outside the period → no pro-rata effect.

---

## Employee Loans

### TC-HR-040 — Loans list (paged) + four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Loans (`/admin/hr/loans` · `GET /api/v1/hr/loans` paged)
- **Permission / Role:** `HR.LOAN.MANAGE` (note: there is **no** separate LOAN.VIEW — list itself requires MANAGE); NO-PERMISSION → forbidden.
- **Preconditions / Seed:** ≥ 21 loans.
- **Steps:** Navigate; read table; paginate; force empty/error/forbidden.
- **Expected Result:** Rows show employee name, loan number, principal, installment, outstanding, status; meta paginates.
- **Convention Assertions:** C5 paginator; C2 envelope+meta; C1 names not uids; C4 four states; C8 money as "TZS 1,234.56".
- **Negative / Edge:** a user with HR perms but lacking `HR.LOAN.MANAGE` → forbidden even for read.

### TC-HR-041 — Create loan
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Loans (`POST /api/v1/hr/loans/employee/{employeeUid}`)
- **Permission / Role:** `HR.LOAN.MANAGE` (scoped to employee); a user without it → 403.
- **Variation:** currency defaults TZS when omitted.
- **Preconditions / Seed:** An employee; a GL account exists (the loan's `glAccountId`).
- **Steps:**
  1. From `/admin/hr/loans`, pick the employee by name.
  2. Enter principal, installment, start date; pick the GL account by name; save.
- **Test Data:** principal `600000`, installment `100000`, startDate `2026-02-01`, currency omitted.
- **Expected Result:** 201; `EmployeeLoanDto` with server `loanNumber`, `outstandingAmount` = principal, currency `TZS`.
- **Convention Assertions:** C1 employee and GL account chosen via picker by name (the FK ids resolved under the hood, never typed); C2 envelope; C3 manage; C8 money string display.
- **Negative / Edge:** missing principal/installment/glAccountId/startDate or `employeeId` → `@NotNull` 400; principal/installment ≤ 0 → `@Positive` 400.

### TC-HR-042 — Approve loan (→ ACTIVE)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Loans (`POST /api/v1/hr/loans/uid/{uid}/approve`)
- **Permission / Role:** `HR.LOAN.MANAGE` (scoped).
- **Variation:** LoanStatus → ACTIVE.
- **Preconditions / Seed:** A loan exists (TC-HR-041).
- **Steps:** Open the loan detail (`/admin/hr/loans/uid/:uid`); click **Approve**.
- **Expected Result:** `status = ACTIVE`; once active with outstanding balance, it is picked up by `loans.findActiveWithBalance` during the next payroll calculate and deducted via installments.
- **Convention Assertions:** C3 manage; C2 envelope; C1 uid in URL only.
- **Negative / Edge:** approve unknown uid → 404; no `close`/`settle`/`cancel` endpoint exists — assert SETTLED/CANCELLED are not reachable via UI (close is backend-only).

### TC-HR-043 — Loan deduction flows into payroll
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Loans ↔ Payroll (`PayrollRunServiceImpl.calculate`)
- **Permission / Role:** `HR.LOAN.MANAGE` + `HR.PAYROLL.RUN`.
- **Preconditions / Seed:** An ACTIVE loan with outstanding > 0 for an employee who has an active contract; a payroll run for the current month.
- **Steps:** Calculate the payroll run.
- **Expected Result:** The line shows a DEDUCTION "Loan Repayment: <loanNumber>" of `min(installment, outstanding)`; net reduced accordingly; loan deduction total reflected on the line.
- **Convention Assertions:** C8 amounts; C7 company-scoped.
- **Negative / Edge:** installment > outstanding → only the remaining outstanding is deducted (min applied).

---

## Pay Components

### TC-HR-050 — Pay components list (non-paginated)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Pay Components (`/admin/hr/pay-components` · `GET /api/v1/hr/pay-components`)
- **Permission / Role:** `HR.PAYCOMPONENT.MANAGE` (list itself requires MANAGE — no VIEW perm); NO-PERMISSION → forbidden.
- **Preconditions / Seed:** ≥1 pay component.
- **Steps:** Navigate; read table.
- **Expected Result:** Lists code, name, kind (EARNING/DEDUCTION), basis (FIXED/PERCENT_OF_BASIC), taxable, pensionable, active. List is **not paginated** (full company list).
- **Convention Assertions:** C2 envelope unwrapped; C4 loaded/empty/error/forbidden; C1 no uid shown; C6 axe; C5 paginator **absent/self-hidden** (not paginated).
- **Negative / Edge:** NO-PERMISSION → forbidden.

### TC-HR-051 — Create EARNING (FIXED, taxable, pensionable)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Pay Components (`POST /api/v1/hr/pay-components`)
- **Permission / Role:** `HR.PAYCOMPONENT.MANAGE`.
- **Variation:** kind=EARNING, basis=FIXED, taxable=true, pensionable=true.
- **Preconditions / Seed:** A GL account exists for the component (`glAccountId`).
- **Steps:** New component; enter code/name; choose EARNING + FIXED; check taxable + pensionable; pick GL account by name; save.
- **Test Data:** code `HOUSING`, name `Housing Allowance`.
- **Expected Result:** 201; `PayComponentDto` with the chosen kind/basis/flags; `active = true`.
- **Convention Assertions:** C1 GL account via picker by name; C2 envelope; C3 manage.
- **Negative / Edge:** blank code/name → `@NotBlank` 400; null kind/basis/glAccountId → `@NotNull` 400.

### TC-HR-052 — Create EARNING (PERCENT_OF_BASIC)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Pay Components (`POST …`)
- **Permission / Role:** `HR.PAYCOMPONENT.MANAGE`.
- **Variation:** basis=PERCENT_OF_BASIC.
- **Steps:** Create an earning with basis PERCENT_OF_BASIC.
- **Test Data:** code `TRANSPORT_PCT`, name `Transport %`.
- **Expected Result:** Component stored with basis PERCENT_OF_BASIC. During payroll calc, a recurring item referencing it is computed as `basic × percent ÷ 100` (HALF_UP).
- **Convention Assertions:** C1 picker; C2 envelope.
- **Negative / Edge:** n/a — percent value lives on the (backend-only) recurring item.

### TC-HR-053 — Create DEDUCTION (FIXED, non-taxable)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Pay Components (`POST …`)
- **Permission / Role:** `HR.PAYCOMPONENT.MANAGE`.
- **Variation:** kind=DEDUCTION, taxable=false, pensionable=false.
- **Steps:** Create a DEDUCTION component.
- **Test Data:** code `UNION`, name `Union Dues`.
- **Expected Result:** Stored as DEDUCTION; during payroll it reduces net and shows as a DEDUCTION line item.
- **Convention Assertions:** C3 manage; C2 envelope.

### TC-HR-054 — Edit + deactivate pay component
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Pay Components (`PUT`/`DELETE /api/v1/hr/pay-components/uid/{uid}`)
- **Permission / Role:** `HR.PAYCOMPONENT.MANAGE` (scoped).
- **Steps:** Open a component (`/admin/hr/pay-components/uid/:uid`); rename; save; then deactivate.
- **Expected Result:** Update returns new name; deactivate → 204, `active = false` (soft-delete).
- **Convention Assertions:** C9 soft-delete; C1 uid in URL only; C3 manage.
- **Negative / Edge:** unknown uid → 404; blank code/name on edit → 400.

---

## Payroll Runs

### TC-HR-060 — Payroll runs list (paged) + four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Payroll Runs (`/admin/hr/payroll-runs` · `GET /api/v1/hr/payroll-runs` paged)
- **Permission / Role:** `HR.PAYROLL.VIEW`; NO-PERMISSION → forbidden.
- **Preconditions / Seed:** ≥ 21 runs.
- **Steps:** Navigate; read; paginate; force empty/error/forbidden.
- **Expected Result:** Rows show run number, period (year-month), pay date, status badge, gross/deduction/net/employer-cost totals; meta paginates.
- **Convention Assertions:** C5 paginator; C2 envelope+meta; C1 run number (not uid); C4 four states; C8 money strings + ISO pay date.
- **Negative / Edge:** NO-PERMISSION → forbidden.

### TC-HR-061 — Create payroll run (DRAFT)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Payroll Runs (`POST /api/v1/hr/payroll-runs`)
- **Permission / Role:** `HR.PAYROLL.RUN`; `HR.PAYROLL.VIEW`-only user → create hidden / API 403.
- **Variation:** periodMonth 1–12; branch optional (default vs specified).
- **Preconditions / Seed:** Active company; no existing active run for the chosen period.
- **Steps:** New run; enter periodMonth, periodYear, payDate; optional branch (picked by name); save.
- **Test Data:** periodMonth `6`, periodYear `2026`, payDate `2026-06-30`.
- **Expected Result:** 201; `PayrollRunDto` status `DRAFT`, server `runNumber`, totals zero.
- **Convention Assertions:** C1 branch via picker if set; C2 envelope; C3 RUN-gated; C8 ISO payDate.
- **Negative / Edge:** periodMonth < 1 or > 12 → `@Min`/`@Max` 400; missing payDate → `@NotNull` 400; **duplicate active run for the same period** → ConflictException 409 ("Active payroll run already exists for period …").

### TC-HR-062 — Calculate payroll run (DRAFT → CALCULATED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Payroll Runs (`POST /api/v1/hr/payroll-runs/uid/{uid}/calculate`, `GET …/lines`)
- **Permission / Role:** `HR.PAYROLL.RUN` (scoped) to calculate; `HR.PAYROLL.VIEW` to read lines.
- **Variation:** status DRAFT → CALCULATED.
- **Preconditions / Seed:** A DRAFT run; ≥1 ACTIVE employee with an active contract; statutory sets + PAYE bands seeded (`HrStatutorySeeder`).
- **Steps:**
  1. Open run detail (`/admin/hr/payroll-runs/uid/:uid`); click **Calculate** (visible because status ∈ {DRAFT,CALCULATED,APPROVED}).
  2. View the **Lines** tab.
- **Expected Result:** Status → CALCULATED; one line per active employee with active contract (employees without a contract are skipped); each line shows basic salary earning, statutory deductions (PAYE/NSSF/HESLB per contract flags), loan deductions, voluntary deductions, gross/net; run totals populated; lines persisted (recalc deletes & rebuilds).
- **Convention Assertions:** C2 envelope; C3 scoped RUN; C8 amounts; C1 uid in URL only.
- **Negative / Edge:** calculate when status ∈ {POSTED,PAID,REVERSED} → ConflictException 409; recalculate from CALCULATED/APPROVED is **allowed** (idempotent rebuild).

### TC-HR-063 — Negative-net line is FLAGGED and blocks approval
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Payroll Runs (`calculate` then `approve`)
- **Permission / Role:** `HR.PAYROLL.RUN` + `HR.PAYROLL.APPROVE`.
- **Variation:** PayrollLineStatus OK vs FLAGGED.
- **Preconditions / Seed:** An employee whose deductions exceed gross (e.g. large loan installment) so net < 0.
- **Steps:** Calculate the run; observe the line; attempt **Approve**.
- **Expected Result:** The line is `FLAGGED` with reason "Net amount negative after deductions: …" (net kept negative, not zeroed); approve → ConflictException 409 listing the flagged employee(s): "Cannot approve run with FLAGGED payroll lines. Resolve: …".
- **Convention Assertions:** C2 errors array; C3 approve permission; UI shows flagged badge (`hasFlaggedLines`).
- **Negative / Edge:** resolve the cause (reduce installment), recalculate → line OK → approve succeeds.

### TC-HR-064 — Approve payroll run (CALCULATED → APPROVED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Payroll Runs (`POST …/uid/{uid}/approve`)
- **Permission / Role:** `HR.PAYROLL.APPROVE` (scoped); a `HR.PAYROLL.RUN`-only user → 403.
- **Variation:** status CALCULATED → APPROVED.
- **Preconditions / Seed:** A CALCULATED run with no flagged lines.
- **Steps:** Open detail; click **Approve** (visible only when status = CALCULATED).
- **Expected Result:** Status → APPROVED; `approvedAt`/`approvedBy` set.
- **Convention Assertions:** C3 approve permission distinct from RUN; C2 envelope.
- **Negative / Edge:** approve from DRAFT/POSTED/PAID/REVERSED → ConflictException 409 ("must be in CALCULATED status"); approve as RUN-only user → 403.

### TC-HR-065 — Post payroll run (APPROVED → POSTED) with GL journal + payslips
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Payroll Runs (`POST …/uid/{uid}/post`)
- **Permission / Role:** `HR.PAYROLL.POST` (scoped); APPROVE-only user → 403.
- **Variation:** status APPROVED → POSTED.
- **Preconditions / Seed:** An APPROVED run; GL config keys resolvable for payroll posting.
- **Steps:** Open detail; click **Post** (visible only when status = APPROVED).
- **Expected Result:** Status → POSTED; `postedAt`/`postedBy` set; a `PAYROLL_FINALISED` outbox event is published (the GL journal is posted asynchronously by the posting handler, which sets `glEntryUid`); payslips generated (one per line, `PAYSLIP-#####`).
- **Convention Assertions:** C3 POST permission; C9 financial posting append-only (no edit, reversal only); C2 envelope.
- **Negative / Edge:** post from CALCULATED/DRAFT/POSTED → ConflictException 409 ("must be in APPROVED status").

### TC-HR-066 — Disburse payroll run (POSTED → PAID) via Cash & Bank
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Payroll Runs (`POST …/uid/{uid}/disburse`)
- **Permission / Role:** `HR.PAYROLL.DISBURSE` (scoped); POST-only user → 403.
- **Variation:** status POSTED → PAID; net total > 0 required.
- **Preconditions / Seed:** A POSTED run with `netTotal > 0`; a Cash & Bank account exists.
- **Steps:** Open detail; click **Disburse**; in the form, choose the cash/bank account (`cashBankAccountUid`), optionally set txn date; submit.
- **Test Data:** cashBankAccountUid = selected bank account; txnDate omitted (defaults to run `payDate`).
- **Expected Result:** Status → PAID; `paidAt` set; a Cash & Bank **OUT** direct entry is recorded (DR NET_WAGES_PAYABLE / CR bank GL) for the net total with memo "Net wages disbursement for <runNumber>".
- **Convention Assertions:** C1 cash/bank account chosen via picker by name (uid stored under the hood; the `cashBankAccountUid` in the request body is set from the picked account, never typed); C2 envelope; C3 DISBURSE permission; C8 net amount; C9 append-only posting.
- **Negative / Edge:** disburse from non-POSTED status → 409 ("must be in POSTED status to disburse"); `netTotal ≤ 0` → 409 ("net total must be > 0"); blank `cashBankAccountUid` → `@NotBlank` 400; POST-only user → 403.

### TC-HR-067 — Reverse payroll run (POSTED or PAID → REVERSED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Payroll Runs (`POST …/uid/{uid}/reverse`)
- **Permission / Role:** `HR.PAYROLL.REVERSE` (scoped); DISBURSE-only user → 403.
- **Variation:** status POSTED → REVERSED; and PAID → REVERSED.
- **Preconditions / Seed:** (a) a POSTED run; (b) a separate PAID run.
- **Steps:** For each, open detail and click **Reverse** (visible when status ∈ {POSTED, PAID}).
- **Expected Result:** Status → REVERSED; `reversedAt` set; a `PAYROLL_REVERSED` outbox event is published (reversing GL journal posted by handler). No edit of original postings (append-only reversal).
- **Convention Assertions:** C9 reversal not edit; C3 REVERSE permission; C2 envelope.
- **Negative / Edge:** reverse from DRAFT/CALCULATED/APPROVED → ConflictException 409 ("Only POSTED or PAID payroll runs can be reversed"); reverse an already-REVERSED run → 409.

### TC-HR-068 — Illegal payroll transitions matrix
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Payroll Runs (all action endpoints)
- **Permission / Role:** users holding the relevant action permission (so failures are status-driven, not RBAC).
- **Variation:** exhaustively assert each illegal transition is rejected with 409.
- **Preconditions / Seed:** runs prepared in each status (DRAFT, CALCULATED, APPROVED, POSTED, PAID, REVERSED).
- **Steps / Expected (each → ConflictException 409):**
  - approve from DRAFT / APPROVED / POSTED / PAID / REVERSED.
  - post from DRAFT / CALCULATED / POSTED / PAID / REVERSED.
  - disburse from DRAFT / CALCULATED / APPROVED / PAID / REVERSED (and POSTED with net ≤ 0).
  - reverse from DRAFT / CALCULATED / APPROVED / REVERSED.
  - calculate from POSTED / PAID / REVERSED.
- **Expected Result:** Each returns 409 with a clear message; status unchanged. (Legal: calculate from DRAFT/CALCULATED/APPROVED; approve from CALCULATED; post from APPROVED; disburse from POSTED; reverse from POSTED/PAID.)
- **Convention Assertions:** C2 errors array; C9 lifecycle integrity.
- **Negative / Edge:** action buttons are also hidden in the UI when not applicable (status predicates `canApproveAction`, `canPostAction`, `canDisburseAction`, `canReverseAction`, `canCalculate`).

### TC-HR-069 — Payroll action RBAC split (per-permission denial)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Payroll Runs (all actions)
- **Permission / Role:** six users, each holding exactly one of VIEW/RUN/APPROVE/POST/DISBURSE/REVERSE.
- **Steps:** For each single-permission user, load a run in the matching status and verify only their action button is enabled; attempt each other action via API.
- **Expected Result:** Each unauthorized action → 403; UI shows only the permitted control (`canRun`/`canApprove`/`canPost`/`canDisburse`/`canReverse` computed from `session.hasPermission`).
- **Convention Assertions:** C3 fine-grained RBAC by permission code; C4 forbidden affordance.
- **Negative / Edge:** VIEW-only user can read the run + lines but no action buttons appear.

### TC-HR-070 — Payroll run detail four states + lines
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Payroll Runs (`GET …/uid/{uid}`, `GET …/uid/{uid}/lines`)
- **Permission / Role:** `HR.PAYROLL.VIEW`.
- **Preconditions / Seed:** a CALCULATED run (lines present) and a DRAFT run (no lines = empty lines).
- **Steps:** Open each run detail; observe header + lines table states (loading/idle/empty/error).
- **Expected Result:** Header shows totals + status; lines table renders per-employee rows or an empty state for DRAFT.
- **Convention Assertions:** C4 four states on both header and lines; C1 uid in URL only; C6 axe; C8 money strings.
- **Negative / Edge:** unknown run uid → 404 → error state.

---

## Statutory Setup

### TC-HR-080 — Statutory screen lists PAYE band sets + rate sets
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Statutory (`/admin/hr/statutory` · `GET /api/v1/hr/statutory/paye-bands`, `GET …/rates`)
- **Permission / Role:** `HR.STATUTORY.MANAGE`; NO-PERMISSION → forbidden.
- **Preconditions / Seed:** Seeded PAYE band set + statutory rate sets (`HrStatutorySeeder`).
- **Steps:** Navigate; select company; read both the PAYE bands list and the rate sets list.
- **Expected Result:** PAYE band sets show effectiveFrom, taxFreeThreshold, bands (bandNo, lowerBound, marginalRate, cumulativeFixedTax); rate sets show rateType (NSSF/WCF/SDL/HESLB), effectiveFrom, employee/employer rates, basis, ceiling, headcountThreshold, active.
- **Convention Assertions:** C2 envelope (both lists `ApiResponse<List<…>>`); C4 loaded/empty/error/forbidden; C1 no uid shown; C6 axe; C7 company-scoped.
- **Negative / Edge:** NO-PERMISSION → forbidden (route guard `requirePermission('HR.STATUTORY.MANAGE')`).

### TC-HR-081 — Create PAYE band set
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Statutory (`POST /api/v1/hr/statutory/paye-bands`)
- **Permission / Role:** `HR.STATUTORY.MANAGE`.
- **Variation:** multiple bands with ascending bandNo.
- **Preconditions / Seed:** A company selected.
- **Steps:** New PAYE band set; enter effectiveFrom + taxFreeThreshold + description; add ≥2 bands (bandNo, lowerBound, marginalRate, cumulativeFixedTax); save.
- **Test Data:** effectiveFrom `2026-01-01`, taxFreeThreshold `270000`; band 1 (lower `270000`, rate `0.08`, cumFixed `0`); band 2 (lower `520000`, rate `0.20`, cumFixed `20000`).
- **Expected Result:** 201; `PayeBandSetDto` with the bands echoed and uids assigned; appears in list.
- **Convention Assertions:** C2 envelope; C3 manage; C8 ISO date, decimal rates.
- **Negative / Edge:** missing effectiveFrom/taxFreeThreshold/bands → `@NotNull` 400; negative taxFreeThreshold or band amounts → `@PositiveOrZero` 400; empty bands list (record observed behaviour — `@NotNull` permits empty list).

### TC-HR-082 — Create NSSF statutory rate set
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Statutory (`POST /api/v1/hr/statutory/rates`)
- **Permission / Role:** `HR.STATUTORY.MANAGE`.
- **Variation:** StatutoryRateType = NSSF (employee + employer rates).
- **Steps:** New rate set; choose NSSF; enter effectiveFrom, employeeRate, employerRate, basis; save.
- **Test Data:** NSSF, effectiveFrom `2026-01-01`, employeeRate `0.10`, employerRate `0.10`, basis `GROSS`.
- **Expected Result:** 201; `StatutoryRateSetDto` with rateType NSSF, both rates, basis, `active`.
- **Convention Assertions:** C2 envelope; C3 manage; C8 decimal rates.
- **Negative / Edge:** missing rateType/effectiveFrom/basis → `@NotNull`/`@NotBlank` 400.

### TC-HR-083 — Create WCF / SDL / HESLB rate sets
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Statutory (`POST …/rates`)
- **Permission / Role:** `HR.STATUTORY.MANAGE`.
- **Variation:** rateType = WCF (employer-only), SDL (employer-only, with `headcountThreshold`), HESLB (employee-only).
- **Steps:** Create one rate set per type with the appropriate rate side populated.
- **Test Data:** WCF employerRate `0.005`, basis `GROSS`; SDL employerRate `0.035`, headcountThreshold `10`, basis `GROSS`; HESLB employeeRate `0.15`, basis `BASIC`.
- **Expected Result:** Each created with the correct type; SDL retains `headcountThreshold` (SDL applies only when company headcount ≥ threshold, per `StatutoryCalculator`).
- **Convention Assertions:** C2 envelope; C3 manage; C8 decimals.
- **Negative / Edge:** blank basis → `@NotBlank` 400.

### TC-HR-084 — Statutory rate set drives payroll calc (integration)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Statutory ↔ Payroll (`StatutoryCalculator` via `PayrollRunServiceImpl.calculate`)
- **Permission / Role:** `HR.STATUTORY.MANAGE` + `HR.PAYROLL.RUN`.
- **Preconditions / Seed:** Active PAYE band set + NSSF/WCF/SDL/HESLB rate sets effective on the run's pay date; an employee with a contract whose statutory flags select which apply.
- **Steps:** Calculate a payroll run.
- **Expected Result:** Each line's PAYE/NSSF/HESLB deductions and NSSF/WCF/SDL employer costs are computed from the **effective** sets for the pay date; the applied set uids are captured in the statutory snapshot (reproducibility); contract flags gate which statutory lines apply (e.g. `nssfMember=false` ⇒ NSSF zero).
- **Convention Assertions:** C7 company-scoped; C9 snapshot = append-only reproducibility record.
- **Negative / Edge:** no effective set for the pay date → calculator behaviour per `StatutoryCalculator` (record observed: zero or error).

### TC-HR-085 — Statutory RBAC denial
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Statutory (all endpoints)
- **Permission / Role:** a user **without** `HR.STATUTORY.MANAGE` (e.g. only `HR.EMPLOYEE.VIEW`).
- **Steps:** Attempt to open `/admin/hr/statutory` and call the create endpoints.
- **Expected Result:** Nav item hidden; route guard blocks navigation; API → 403.
- **Convention Assertions:** C3 RBAC; C4 forbidden.

---

## Cross-cutting

### TC-HR-090 — HR nav visibility by permission
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Shell nav "HR & Payroll" (`shell.component.ts`)
- **Permission / Role:** (a) NO-PERMISSION user, (b) a user with only `HR.EMPLOYEE.VIEW`, (c) a user with the full HR set.
- **Steps:** Log in as each; inspect the HR & Payroll nav group.
- **Expected Result:**
  - (a) HR group entirely hidden (no item's permission held).
  - (b) Only Employees / Departments / Employee Contracts visible (all gated `HR.EMPLOYEE.VIEW`); Pay Components / Payroll Runs / Leave / Loans / Statutory hidden.
  - (c) All eight items visible.
- **Convention Assertions:** C3 nav gated by permission code; C4 empty-nav handled.
- **Negative / Edge:** direct route to a hidden item → guard forbidden, not a blank screen.

### TC-HR-091 — Branch scoping: act in an unassigned branch is denied
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** All HR endpoints (`ScopeGuard.assertCanActIn`)
- **Permission / Role:** holder of HR perms but assigned to ONE branch only.
- **Variation:** user assigned to branch B1 sets active branch to B2 (not assigned).
- **Preconditions / Seed:** Multi-branch company; user on B1 only.
- **Steps:** Switch the active branch to B2 (X-Branch-Uid) and attempt any HR list/create.
- **Expected Result:** Denied (403) — scope guard rejects acting in an unassigned branch.
- **Convention Assertions:** C7 branch scoping; C3 RBAC.
- **Negative / Edge:** a user assigned to ALL branches succeeds in any branch; switching to a valid assigned branch succeeds.

### TC-HR-092 — Money & date formatting across HR screens
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** All HR money/date fields
- **Permission / Role:** any HR-read permission.
- **Steps:** Inspect contract base salary, loan amounts, payroll totals, pay dates, leave dates, contract dates.
- **Expected Result:** Money rendered as `CUR 1,234.56` (string on the wire, currency = TZS); dates ISO `yyyy-MM-dd`.
- **Convention Assertions:** C8 money/date conventions.
- **Negative / Edge:** zero/negative net on a flagged line still formats correctly.

### TC-HR-093 — Accessibility sweep of HR screens
- **Type:** Automated (Playwright + axe)
- **Priority:** P2
- **Module / Submodule:** All HR list + detail screens
- **Permission / Role:** full HR set.
- **Steps:** For each route (employees, departments, contracts, pay-components, payroll-runs + detail, leave-requests + detail, loans + detail, statutory) run an axe scan and a keyboard-only pass.
- **Expected Result:** axe-clean; tables have captions + scoped headers; form controls have labels; pickers and action buttons are keyboard operable.
- **Convention Assertions:** C6 WCAG 2.1 AA; C5 paginator keyboard operable on paged lists.
- **Negative / Edge:** error/empty/forbidden states also axe-clean (cross-ref TC-HR-002/011/070).
