import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  ApproveBudgetVersionRequest,
  BudgetDto,
  BudgetVersionDto,
  BudgetVersionStatus,
  CreateBudgetRequest,
  CreateBudgetVersionRequest,
  DepartmentalActualsDto,
  RejectBudgetVersionRequest,
  UpsertBudgetLineRequest,
  VarianceReportDto,
} from './models/budgeting.model';

export interface BudgetPage {
  rows: BudgetDto[];
  meta: PageMeta;
}

/**
 * Budgeting API client.
 * list() uses SKIP_UNWRAP to preserve PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips envelope).
 */
@Injectable({ providedIn: 'root' })
export class BudgetingService {
  private readonly http = inject(HttpClient);
  private readonly budgetBase = `${environment.apiBaseUrl}/budgets`;
  private readonly versionBase = `${environment.apiBaseUrl}/budget-versions`;
  private readonly reportBase = `${environment.apiBaseUrl}/budgeting`;

  // ── Budgets ───────────────────────────────────────────────────────────────────

  list(
    companyId: string,
    page = 0,
    size = 20,
    fiscalYearUid?: string,
    costCentreValueUid?: string,
    versionStatus?: BudgetVersionStatus,
  ): Observable<BudgetPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (fiscalYearUid) params = params.set('fiscalYearUid', fiscalYearUid);
    if (costCentreValueUid) params = params.set('costCentreValueUid', costCentreValueUid);
    if (versionStatus) params = params.set('versionStatus', versionStatus);
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<BudgetDto[]>>(this.budgetBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<BudgetDto> {
    return this.http.get<BudgetDto>(`${this.budgetBase}/uid/${uid}`);
  }

  create(request: CreateBudgetRequest): Observable<BudgetDto> {
    return this.http.post<BudgetDto>(this.budgetBase, request);
  }

  createVersion(budgetUid: string, request?: CreateBudgetVersionRequest): Observable<BudgetVersionDto> {
    return this.http.post<BudgetVersionDto>(`${this.budgetBase}/uid/${budgetUid}/versions`, request ?? {});
  }

  // ── Budget Versions ───────────────────────────────────────────────────────────

  getVersionByUid(uid: string): Observable<BudgetVersionDto> {
    return this.http.get<BudgetVersionDto>(`${this.versionBase}/uid/${uid}`);
  }

  upsertLines(uid: string, request: UpsertBudgetLineRequest): Observable<BudgetVersionDto> {
    return this.http.put<BudgetVersionDto>(`${this.versionBase}/uid/${uid}/lines`, request);
  }

  submit(uid: string): Observable<BudgetVersionDto> {
    return this.http.post<BudgetVersionDto>(`${this.versionBase}/uid/${uid}/submit`, {});
  }

  recall(uid: string): Observable<BudgetVersionDto> {
    return this.http.post<BudgetVersionDto>(`${this.versionBase}/uid/${uid}/recall`, {});
  }

  approve(uid: string, request?: ApproveBudgetVersionRequest): Observable<BudgetVersionDto> {
    return this.http.post<BudgetVersionDto>(`${this.versionBase}/uid/${uid}/approve`, request ?? {});
  }

  reject(uid: string, request: RejectBudgetVersionRequest): Observable<BudgetVersionDto> {
    return this.http.post<BudgetVersionDto>(`${this.versionBase}/uid/${uid}/reject`, request);
  }

  // ── Reports ───────────────────────────────────────────────────────────────────

  varianceReport(
    companyId: string,
    fiscalYearUid: string,
    fromPeriodNo = 1,
    toPeriodNo = 12,
    costCentreValueUid?: string,
    accountType?: string,
  ): Observable<VarianceReportDto> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('fiscalYearUid', fiscalYearUid)
      .set('fromPeriodNo', String(fromPeriodNo))
      .set('toPeriodNo', String(toPeriodNo));
    if (costCentreValueUid) params = params.set('costCentreValueUid', costCentreValueUid);
    if (accountType) params = params.set('accountType', accountType);
    return this.http.get<VarianceReportDto>(`${this.reportBase}/variance`, { params });
  }

  departmentalActuals(
    companyId: string,
    fiscalYearUid: string,
    fromPeriodNo = 1,
    toPeriodNo = 12,
  ): Observable<DepartmentalActualsDto> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('fiscalYearUid', fiscalYearUid)
      .set('fromPeriodNo', String(fromPeriodNo))
      .set('toPeriodNo', String(toPeriodNo));
    return this.http.get<DepartmentalActualsDto>(`${this.reportBase}/departmental-actuals`, { params });
  }
}
