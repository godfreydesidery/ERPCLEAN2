import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  CreateStockCountRequest,
  EnterCountRequest,
  StockCountDto,
} from './stock-count.model';

export interface StockCountPage {
  rows: StockCountDto[];
  meta: PageMeta;
}

/**
 * Stock Count API client — mirrors /api/v1/stock-counts.
 * list() uses SKIP_UNWRAP to preserve PageMeta.
 * All mutation methods use the auto-unwrap path.
 */
@Injectable({ providedIn: 'root' })
export class StockCountService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock-counts`;

  // ── Paged list ────────────────────────────────────────────────────────────────

  list(page = 0, size = 20): Observable<StockCountPage> {
    const params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<StockCountDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  // ── Single read ───────────────────────────────────────────────────────────────

  getByUid(uid: string): Observable<StockCountDto> {
    return this.http.get<StockCountDto>(`${this.base}/uid/${uid}`);
  }

  // ── Create ────────────────────────────────────────────────────────────────────

  create(request: CreateStockCountRequest): Observable<StockCountDto> {
    return this.http.post<StockCountDto>(this.base, request);
  }

  // ── Enter counted quantities ──────────────────────────────────────────────────

  enterCount(uid: string, request: EnterCountRequest): Observable<StockCountDto> {
    return this.http.patch<StockCountDto>(`${this.base}/uid/${uid}/enter`, request);
  }

  // ── Post (variance + GL) ──────────────────────────────────────────────────────

  post(uid: string, postingDate: string): Observable<StockCountDto> {
    return this.http.patch<StockCountDto>(`${this.base}/uid/${uid}/post`, null, {
      params: new HttpParams().set('postingDate', postingDate),
    });
  }

  // ── Cancel ────────────────────────────────────────────────────────────────────

  cancel(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }
}
