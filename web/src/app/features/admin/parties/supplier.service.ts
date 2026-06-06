import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AssignPartyBranchRequest,
  CreateSupplierRequest,
  PartyBranch,
  SupplierModel,
  UpdateSupplierRequest,
} from '../models/party.model';

export interface SupplierPage {
  rows: SupplierModel[];
  meta: PageMeta;
}

/**
 * Supplier API client.
 * Base: /api/v1/suppliers.
 */
@Injectable({ providedIn: 'root' })
export class SupplierService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/suppliers`;

  list(companyId: string, q?: string, page = 0, size = 20): Observable<SupplierPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<SupplierModel[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<SupplierModel> {
    return this.http.get<SupplierModel>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateSupplierRequest): Observable<SupplierModel> {
    return this.http.post<SupplierModel>(this.base, request);
  }

  update(uid: string, request: UpdateSupplierRequest): Observable<SupplierModel> {
    return this.http.put<SupplierModel>(`${this.base}/uid/${uid}`, request);
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
