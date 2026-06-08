import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AssignRouteAgentRequest,
  AssignRouteBranchRequest,
  AssignRouteCustomerRequest,
  CreateRouteRequest,
  RouteAgentDto,
  RouteBranchDto,
  RouteCustomerDto,
  RouteDto,
  UpdateRouteRequest,
} from './models/route.model';

export interface RoutePage {
  rows: RouteDto[];
  meta: PageMeta;
}

/**
 * Routes API client.
 * list() uses SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 * Base: /api/v1/routes.
 */
@Injectable({ providedIn: 'root' })
export class RoutesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/routes`;

  // ── Route CRUD ────────────────────────────────────────────────────────────

  list(companyId: string, q?: string, page = 0, size = 20): Observable<RoutePage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<RouteDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<RouteDto> {
    return this.http.get<RouteDto>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateRouteRequest): Observable<RouteDto> {
    return this.http.post<RouteDto>(this.base, request);
  }

  update(uid: string, request: UpdateRouteRequest): Observable<RouteDto> {
    return this.http.put<RouteDto>(`${this.base}/uid/${uid}`, request);
  }

  archive(uid: string): Observable<RouteDto> {
    return this.http.put<RouteDto>(`${this.base}/uid/${uid}/archive`, {});
  }

  restore(uid: string): Observable<RouteDto> {
    return this.http.put<RouteDto>(`${this.base}/uid/${uid}/restore`, {});
  }

  // ── Customer associations ─────────────────────────────────────────────────

  listCustomers(uid: string): Observable<RouteCustomerDto[]> {
    return this.http.get<RouteCustomerDto[]>(`${this.base}/uid/${uid}/customers`);
  }

  assignCustomer(uid: string, request: AssignRouteCustomerRequest): Observable<RouteCustomerDto> {
    return this.http.post<RouteCustomerDto>(`${this.base}/uid/${uid}/customers`, request);
  }

  removeCustomer(uid: string, customerUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/customers/${customerUid}`);
  }

  // ── Agent associations ────────────────────────────────────────────────────

  listAgents(uid: string): Observable<RouteAgentDto[]> {
    return this.http.get<RouteAgentDto[]>(`${this.base}/uid/${uid}/agents`);
  }

  assignAgent(uid: string, request: AssignRouteAgentRequest): Observable<RouteAgentDto> {
    return this.http.post<RouteAgentDto>(`${this.base}/uid/${uid}/agents`, request);
  }

  removeAgent(uid: string, agentUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/agents/${agentUid}`);
  }

  // ── Branch associations ───────────────────────────────────────────────────

  listBranches(uid: string): Observable<RouteBranchDto[]> {
    return this.http.get<RouteBranchDto[]>(`${this.base}/uid/${uid}/branches`);
  }

  assignBranch(uid: string, request: AssignRouteBranchRequest): Observable<RouteBranchDto> {
    return this.http.post<RouteBranchDto>(`${this.base}/uid/${uid}/branches`, request);
  }

  removeBranch(uid: string, branchUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/branches/${branchUid}`);
  }
}
