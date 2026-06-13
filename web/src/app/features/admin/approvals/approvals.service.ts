import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  ApprovalPolicyDto,
  ApprovalRequestDto,
  CreateApprovalPolicyRequest,
  DecideRequest,
  UpdateApprovalPolicyRequest,
} from './models/approvals.model';

export interface ApprovalPolicyPage {
  rows: ApprovalPolicyDto[];
  meta: PageMeta;
}

export interface ApprovalRequestPage {
  rows: ApprovalRequestDto[];
  meta: PageMeta;
}

/**
 * Approvals module API client.
 * list*() use SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips envelope).
 */
@Injectable({ providedIn: 'root' })
export class ApprovalsService {
  private readonly http = inject(HttpClient);
  private readonly policyBase = `${environment.apiBaseUrl}/approvals/policies`;
  private readonly requestBase = `${environment.apiBaseUrl}/approvals/requests`;

  // ── Approval Policies ──────────────────────────────────────────────────────

  listPolicies(companyId: string, documentType?: string, page = 0, size = 20): Observable<ApprovalPolicyPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (documentType) {
      params = params.set('documentType', documentType);
    }
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ApprovalPolicyDto[]>>(this.policyBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getPolicyByUid(uid: string): Observable<ApprovalPolicyDto> {
    return this.http.get<ApprovalPolicyDto>(`${this.policyBase}/uid/${uid}`);
  }

  createPolicy(request: CreateApprovalPolicyRequest): Observable<ApprovalPolicyDto> {
    return this.http.post<ApprovalPolicyDto>(this.policyBase, request);
  }

  updatePolicy(uid: string, request: UpdateApprovalPolicyRequest): Observable<ApprovalPolicyDto> {
    return this.http.put<ApprovalPolicyDto>(`${this.policyBase}/uid/${uid}`, request);
  }

  deactivatePolicy(uid: string): Observable<ApprovalPolicyDto> {
    return this.http.post<ApprovalPolicyDto>(`${this.policyBase}/uid/${uid}/deactivate`, {});
  }

  // ── Approval Requests ──────────────────────────────────────────────────────

  listRequests(companyId: string, status?: string, page = 0, size = 20): Observable<ApprovalRequestPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (status) {
      params = params.set('status', status);
    }
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ApprovalRequestDto[]>>(this.requestBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  listInbox(page = 0, size = 20): Observable<ApprovalRequestPage> {
    const params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ApprovalRequestDto[]>>(`${this.requestBase}/inbox`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getRequestByUid(uid: string): Observable<ApprovalRequestDto> {
    return this.http.get<ApprovalRequestDto>(`${this.requestBase}/uid/${uid}`);
  }

  approveRequest(uid: string, request: DecideRequest): Observable<ApprovalRequestDto> {
    return this.http.post<ApprovalRequestDto>(`${this.requestBase}/uid/${uid}/approve`, request);
  }

  rejectRequest(uid: string, request: DecideRequest): Observable<ApprovalRequestDto> {
    return this.http.post<ApprovalRequestDto>(`${this.requestBase}/uid/${uid}/reject`, request);
  }

  recallRequest(uid: string): Observable<ApprovalRequestDto> {
    return this.http.post<ApprovalRequestDto>(`${this.requestBase}/uid/${uid}/recall`, {});
  }

  cancelRequest(uid: string): Observable<ApprovalRequestDto> {
    return this.http.post<ApprovalRequestDto>(`${this.requestBase}/uid/${uid}/cancel`, {});
  }
}
