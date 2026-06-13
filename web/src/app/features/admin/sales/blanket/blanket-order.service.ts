import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  BlanketOrderDto,
  CreateBlanketOrderRequest,
  DrawBlanketRequest,
} from './blanket-order.model';
import { SalesOrderDto } from '../../models/sales-orders.model';

export interface BlanketOrderPage {
  rows: BlanketOrderDto[];
  meta: PageMeta;
}

/**
 * Blanket Order API client.
 * list() uses SKIP_UNWRAP to retain PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 * Base: /api/v1/blanket-orders.
 */
@Injectable({ providedIn: 'root' })
export class BlanketOrderService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/blanket-orders`;

  list(companyId: string, page = 0, size = 20): Observable<BlanketOrderPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<BlanketOrderDto[]>>(this.base, { params, context })
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

  getByUid(uid: string): Observable<BlanketOrderDto> {
    return this.http.get<BlanketOrderDto>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateBlanketOrderRequest): Observable<BlanketOrderDto> {
    return this.http.post<BlanketOrderDto>(this.base, request);
  }

  cancel(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }

  draw(uid: string, request: DrawBlanketRequest): Observable<SalesOrderDto> {
    return this.http.post<SalesOrderDto>(`${this.base}/uid/${uid}/draw`, request);
  }
}
