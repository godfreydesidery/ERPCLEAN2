import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AgentModel,
  AssignPartyBranchRequest,
  CreateAgentRequest,
  PartyBranch,
  UpdateAgentRequest,
} from '../models/party.model';

export interface AgentPage {
  rows: AgentModel[];
  meta: PageMeta;
}

/**
 * Agent API client.
 * Base: /api/v1/agents.
 */
@Injectable({ providedIn: 'root' })
export class AgentService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/agents`;

  list(companyId: string, q?: string, page = 0, size = 20): Observable<AgentPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<AgentModel[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<AgentModel> {
    return this.http.get<AgentModel>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateAgentRequest): Observable<AgentModel> {
    return this.http.post<AgentModel>(this.base, request);
  }

  update(uid: string, request: UpdateAgentRequest): Observable<AgentModel> {
    return this.http.put<AgentModel>(`${this.base}/uid/${uid}`, request);
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
