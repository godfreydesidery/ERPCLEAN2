import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import { AssignPartyBranchRequest, PartyBranch } from '../../models/party.model';
import {
  CreateOtherPartyRequest,
  OtherPartyModel,
  UpdateOtherPartyRequest,
} from './other-party.model';

export interface OtherPartyPage {
  rows: OtherPartyModel[];
  meta: PageMeta;
}

/**
 * OtherParty API client.
 * list() uses SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 * Base: GET/POST /api/v1/other-parties; entity: /api/v1/other-parties/uid/{uid}.
 */
@Injectable({ providedIn: 'root' })
export class OtherPartyService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/other-parties`;

  list(companyId: string, q?: string, page = 0, size = 20): Observable<OtherPartyPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<OtherPartyModel[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<OtherPartyModel> {
    return this.http.get<OtherPartyModel>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateOtherPartyRequest): Observable<OtherPartyModel> {
    return this.http.post<OtherPartyModel>(this.base, request);
  }

  update(uid: string, request: UpdateOtherPartyRequest): Observable<OtherPartyModel> {
    return this.http.put<OtherPartyModel>(`${this.base}/uid/${uid}`, request);
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
