import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AssignPartyBranchRequest,
  CreateCustomerRequest,
  CustomerModel,
  PartyBranch,
  UpdateCustomerRequest,
} from '../models/party.model';

export interface CustomerPage {
  rows: CustomerModel[];
  meta: PageMeta;
}

/**
 * Customer API client.
 * list() uses SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 * Base: GET/POST /api/v1/customers; entity: /api/v1/customers/uid/{uid}.
 */
@Injectable({ providedIn: 'root' })
export class CustomerService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/customers`;

  list(companyId: string, q?: string, page = 0, size = 20): Observable<CustomerPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<CustomerModel[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<CustomerModel> {
    return this.http.get<CustomerModel>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateCustomerRequest): Observable<CustomerModel> {
    return this.http.post<CustomerModel>(this.base, request);
  }

  update(uid: string, request: UpdateCustomerRequest): Observable<CustomerModel> {
    return this.http.put<CustomerModel>(`${this.base}/uid/${uid}`, request);
  }

  archive(uid: string): Observable<void> {
    return this.http.put<void>(`${this.base}/uid/${uid}/archive`, {});
  }

  restore(uid: string): Observable<void> {
    return this.http.put<void>(`${this.base}/uid/${uid}/restore`, {});
  }

  listBranches(uid: string): Observable<PartyBranch[]> {
    return this.http.get<PartyBranch[]>(`${this.base}/uid/${uid}/branches`);
  }

  assignBranch(uid: string, request: AssignPartyBranchRequest): Observable<PartyBranch> {
    return this.http.post<PartyBranch>(`${this.base}/uid/${uid}/branches`, request);
  }

  removeBranch(uid: string, branchUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/branches/${branchUid}`);
  }
}
