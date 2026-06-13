import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  CreateStandingOrderRequest,
  StandingOrderDto,
} from './standing-order.model';

export interface StandingOrderPage {
  rows: StandingOrderDto[];
  meta: PageMeta;
}

/**
 * Standing-order API client.
 * list() uses SKIP_UNWRAP to keep both data and PageMeta.
 * All other methods use the auto-unwrap interceptor path.
 * Base: /api/v1/standing-orders
 */
@Injectable({ providedIn: 'root' })
export class StandingOrderService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/standing-orders`;

  list(companyId: string, page = 0, size = 20): Observable<StandingOrderPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<StandingOrderDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<StandingOrderDto> {
    return this.http.get<StandingOrderDto>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateStandingOrderRequest): Observable<StandingOrderDto> {
    return this.http.post<StandingOrderDto>(this.base, request);
  }

  pause(uid: string): Observable<void> {
    return this.http.post<void>(`${this.base}/uid/${uid}/pause`, {});
  }

  resume(uid: string): Observable<void> {
    return this.http.post<void>(`${this.base}/uid/${uid}/resume`, {});
  }

  cancel(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }

  triggerNow(uid: string): Observable<StandingOrderDto> {
    return this.http.post<StandingOrderDto>(`${this.base}/uid/${uid}/trigger`, {});
  }
}
