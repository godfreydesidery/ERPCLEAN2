import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  CreateEmployeeRequest,
  CreateLoanRequest,
  CreatePayComponentRequest,
  CreatePayrollRunRequest,
  DecideLeaveRequest,
  DisburseRequest,
  EmployeeDto,
  EmployeeLoanDto,
  LeaveRequestDto,
  PayComponentDto,
  PayrollLineDto,
  PayrollRunDto,
  SubmitLeaveRequest,
} from './models/hr-payroll.model';

export interface EmployeePage {
  rows: EmployeeDto[];
  meta: PageMeta;
}

export interface PayrollRunPage {
  rows: PayrollRunDto[];
  meta: PageMeta;
}

export interface LeaveRequestPage {
  rows: LeaveRequestDto[];
  meta: PageMeta;
}

export interface LoanPage {
  rows: EmployeeLoanDto[];
  meta: PageMeta;
}

/**
 * HR & Payroll API client.
 * Paginated list() methods use SKIP_UNWRAP to read both data and PageMeta.
 * All single-item / action endpoints use auto-unwrap (interceptor strips envelope).
 * Pay-component list is NOT paginated — returns raw list via auto-unwrap after ApiResponse strip.
 */
@Injectable({ providedIn: 'root' })
export class HrPayrollService {
  private readonly http = inject(HttpClient);

  private readonly employeeBase = `${environment.apiBaseUrl}/hr/employees`;
  private readonly payCompBase = `${environment.apiBaseUrl}/hr/pay-components`;
  private readonly payRunBase = `${environment.apiBaseUrl}/hr/payroll-runs`;
  private readonly leaveBase = `${environment.apiBaseUrl}/hr/leave-requests`;
  private readonly loanBase = `${environment.apiBaseUrl}/hr/loans`;

  // ── Employees ─────────────────────────────────────────────────────────────────

  listEmployees(companyId: string, page = 0, size = 20): Observable<EmployeePage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<EmployeeDto[]>>(this.employeeBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getEmployeeByUid(uid: string): Observable<EmployeeDto> {
    return this.http.get<EmployeeDto>(`${this.employeeBase}/uid/${uid}`);
  }

  createEmployee(request: CreateEmployeeRequest): Observable<EmployeeDto> {
    return this.http.post<EmployeeDto>(this.employeeBase, request);
  }

  updateEmployee(uid: string, request: CreateEmployeeRequest): Observable<EmployeeDto> {
    return this.http.put<EmployeeDto>(`${this.employeeBase}/uid/${uid}`, request);
  }

  archiveEmployee(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.employeeBase}/uid/${uid}`);
  }

  // ── Pay Components ─────────────────────────────────────────────────────────────

  /** NOT paginated — returns full company list via auto-unwrap (interceptor strips ApiResponse). */
  listPayComponents(companyId: string): Observable<PayComponentDto[]> {
    const params = new HttpParams().set('companyId', companyId);
    return this.http.get<PayComponentDto[]>(this.payCompBase, { params });
  }

  getPayComponentByUid(uid: string): Observable<PayComponentDto> {
    return this.http.get<PayComponentDto>(`${this.payCompBase}/uid/${uid}`);
  }

  createPayComponent(request: CreatePayComponentRequest): Observable<PayComponentDto> {
    return this.http.post<PayComponentDto>(this.payCompBase, request);
  }

  updatePayComponent(uid: string, request: CreatePayComponentRequest): Observable<PayComponentDto> {
    return this.http.put<PayComponentDto>(`${this.payCompBase}/uid/${uid}`, request);
  }

  deactivatePayComponent(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.payCompBase}/uid/${uid}`);
  }

  // ── Payroll Runs ───────────────────────────────────────────────────────────────

  listPayrollRuns(companyId: string, page = 0, size = 20): Observable<PayrollRunPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<PayrollRunDto[]>>(this.payRunBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getPayrollRunByUid(uid: string): Observable<PayrollRunDto> {
    return this.http.get<PayrollRunDto>(`${this.payRunBase}/uid/${uid}`);
  }

  createPayrollRun(request: CreatePayrollRunRequest): Observable<PayrollRunDto> {
    return this.http.post<PayrollRunDto>(this.payRunBase, request);
  }

  /** Raw list — no ApiResponse wrapper, not paginated. */
  listPayrollLines(uid: string): Observable<PayrollLineDto[]> {
    return this.http.get<PayrollLineDto[]>(`${this.payRunBase}/uid/${uid}/lines`);
  }

  calculatePayrollRun(uid: string): Observable<PayrollRunDto> {
    return this.http.post<PayrollRunDto>(`${this.payRunBase}/uid/${uid}/calculate`, {});
  }

  approvePayrollRun(uid: string): Observable<PayrollRunDto> {
    return this.http.post<PayrollRunDto>(`${this.payRunBase}/uid/${uid}/approve`, {});
  }

  postPayrollRun(uid: string): Observable<PayrollRunDto> {
    return this.http.post<PayrollRunDto>(`${this.payRunBase}/uid/${uid}/post`, {});
  }

  disbursePayrollRun(uid: string, request: DisburseRequest): Observable<PayrollRunDto> {
    return this.http.post<PayrollRunDto>(`${this.payRunBase}/uid/${uid}/disburse`, request);
  }

  reversePayrollRun(uid: string): Observable<PayrollRunDto> {
    return this.http.post<PayrollRunDto>(`${this.payRunBase}/uid/${uid}/reverse`, {});
  }

  // ── Leave Requests ─────────────────────────────────────────────────────────────

  listLeaveRequests(companyId: string, page = 0, size = 20): Observable<LeaveRequestPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<LeaveRequestDto[]>>(this.leaveBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getLeaveRequestByUid(uid: string): Observable<LeaveRequestDto> {
    return this.http.get<LeaveRequestDto>(`${this.leaveBase}/uid/${uid}`);
  }

  /** Create is nested under an employee: POST /employee/{employeeUid} */
  submitLeaveRequest(employeeUid: string, request: SubmitLeaveRequest): Observable<LeaveRequestDto> {
    return this.http.post<LeaveRequestDto>(`${this.leaveBase}/employee/${employeeUid}`, request);
  }

  decideLeaveRequest(uid: string, request: DecideLeaveRequest): Observable<LeaveRequestDto> {
    return this.http.post<LeaveRequestDto>(`${this.leaveBase}/uid/${uid}/decide`, request);
  }

  // ── Loans ──────────────────────────────────────────────────────────────────────

  listLoans(companyId: string, page = 0, size = 20): Observable<LoanPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<EmployeeLoanDto[]>>(this.loanBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getLoanByUid(uid: string): Observable<EmployeeLoanDto> {
    return this.http.get<EmployeeLoanDto>(`${this.loanBase}/uid/${uid}`);
  }

  /** Create is nested under an employee: POST /employee/{employeeUid} */
  createLoan(employeeUid: string, request: CreateLoanRequest): Observable<EmployeeLoanDto> {
    return this.http.post<EmployeeLoanDto>(`${this.loanBase}/employee/${employeeUid}`, request);
  }

  approveLoan(uid: string): Observable<EmployeeLoanDto> {
    return this.http.post<EmployeeLoanDto>(`${this.loanBase}/uid/${uid}/approve`, {});
  }
}
