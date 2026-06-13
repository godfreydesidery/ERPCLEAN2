import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AddOperationRequest,
  ApplyCostRequest,
  CloseWorkOrderRequest,
  CompleteWorkOrderRequest,
  CreateWorkOrderRequest,
  IssueComponentsRequest,
  ReleaseWorkOrderRequest,
  UpdateWorkOrderRequest,
  WorkOrderCostReportDto,
  WorkOrderDto,
  WorkOrderOperationDto,
  WorkOrderStatus,
  WipReconciliationDto,
} from './models/manufacturing.model';

export interface WorkOrderPage {
  rows: WorkOrderDto[];
  meta: PageMeta;
}

/**
 * Manufacturing API client.
 * list() uses SKIP_UNWRAP to retain PageMeta.
 * All uid-targeted and report endpoints use the auto-unwrap path.
 */
@Injectable({ providedIn: 'root' })
export class ManufacturingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/work-orders`;
  private readonly reportBase = `${environment.apiBaseUrl}/manufacturing`;

  // ── List ──────────────────────────────────────────────────────────────────

  list(
    companyId: string,
    page = 0,
    size = 20,
    status?: WorkOrderStatus | '',
  ): Observable<WorkOrderPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (status) params = params.set('status', status);
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<WorkOrderDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? {
            page,
            size,
            totalElements: env.data?.length ?? 0,
            totalPages: 1,
            hasNext: false,
          },
        })),
      );
  }

  // ── Single ─────────────────────────────────────────────────────────────────

  getByUid(uid: string): Observable<WorkOrderDto> {
    return this.http.get<WorkOrderDto>(`${this.base}/uid/${uid}`);
  }

  // ── Create / Update ────────────────────────────────────────────────────────

  create(request: CreateWorkOrderRequest): Observable<WorkOrderDto> {
    return this.http.post<WorkOrderDto>(this.base, request);
  }

  update(uid: string, request: UpdateWorkOrderRequest): Observable<WorkOrderDto> {
    return this.http.put<WorkOrderDto>(`${this.base}/uid/${uid}`, request);
  }

  // ── Lifecycle actions ──────────────────────────────────────────────────────

  release(uid: string, request?: ReleaseWorkOrderRequest): Observable<WorkOrderDto> {
    return this.http.post<WorkOrderDto>(`${this.base}/uid/${uid}/release`, request ?? null);
  }

  cancel(uid: string, reason?: string): Observable<WorkOrderDto> {
    let params = new HttpParams();
    if (reason) params = params.set('reason', reason);
    return this.http.post<WorkOrderDto>(`${this.base}/uid/${uid}/cancel`, null, { params });
  }

  issueComponents(uid: string, request: IssueComponentsRequest): Observable<WorkOrderDto> {
    return this.http.post<WorkOrderDto>(`${this.base}/uid/${uid}/issue-components`, request);
  }

  applyCost(uid: string, request: ApplyCostRequest): Observable<WorkOrderDto> {
    return this.http.post<WorkOrderDto>(`${this.base}/uid/${uid}/apply-cost`, request);
  }

  complete(uid: string, request: CompleteWorkOrderRequest): Observable<WorkOrderDto> {
    return this.http.post<WorkOrderDto>(`${this.base}/uid/${uid}/complete`, request);
  }

  close(uid: string, request: CloseWorkOrderRequest): Observable<WorkOrderDto> {
    return this.http.post<WorkOrderDto>(`${this.base}/uid/${uid}/close`, request);
  }

  // ── Operations child resource ─────────────────────────────────────────────

  addOperation(uid: string, request: AddOperationRequest): Observable<WorkOrderOperationDto> {
    return this.http.post<WorkOrderOperationDto>(`${this.base}/uid/${uid}/operations`, request);
  }

  removeOperation(uid: string, opUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/operations/${opUid}`);
  }

  // ── Cost report ───────────────────────────────────────────────────────────

  getCostReport(uid: string): Observable<WorkOrderCostReportDto> {
    return this.http.get<WorkOrderCostReportDto>(`${this.base}/uid/${uid}/cost-report`);
  }

  // ── WIP reconciliation report ─────────────────────────────────────────────

  getWipReconciliation(companyId: string): Observable<WipReconciliationDto> {
    const params = new HttpParams().set('companyId', companyId);
    return this.http.get<WipReconciliationDto>(`${this.reportBase}/wip-reconciliation`, {
      params,
    });
  }
}
