import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import { ActivityDto, CreateActivityRequest } from './activity.model';

export interface ActivityPage {
  rows: ActivityDto[];
  meta: PageMeta;
}

/**
 * CRM Activity API client.
 * listByLead / listByOpportunity / openTasks use SKIP_UNWRAP (paginated, ApiResponse envelope).
 * create, getByUid, complete use the auto-unwrap path (interceptor strips envelope).
 */
@Injectable({ providedIn: 'root' })
export class ActivityService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/crm/activities`;

  create(request: CreateActivityRequest): Observable<ActivityDto> {
    return this.http.post<ActivityDto>(this.base, request);
  }

  getByUid(uid: string): Observable<ActivityDto> {
    return this.http.get<ActivityDto>(`${this.base}/uid/${uid}`);
  }

  listByLead(leadUid: string, page = 0, size = 20): Observable<ActivityPage> {
    const params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ActivityDto[]>>(`${this.base}/by-lead/${leadUid}`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  listByOpportunity(opportunityUid: string, page = 0, size = 20): Observable<ActivityPage> {
    const params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ActivityDto[]>>(`${this.base}/by-opportunity/${opportunityUid}`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  openTasks(companyId: string, page = 0, size = 20, assigneeUserId?: string): Observable<ActivityPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (assigneeUserId) {
      params = params.set('assigneeUserId', assigneeUserId);
    }
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ActivityDto[]>>(`${this.base}/open-tasks`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  complete(uid: string): Observable<ActivityDto> {
    return this.http.post<ActivityDto>(`${this.base}/uid/${uid}/complete`, {});
  }
}
