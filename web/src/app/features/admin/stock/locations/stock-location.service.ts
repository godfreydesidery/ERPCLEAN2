import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  CreateStockLocationRequest,
  StockLocationDto,
  UpdateStockLocationRequest,
} from './stock-location.model';

export interface StockLocationPage {
  rows: StockLocationDto[];
  meta: PageMeta;
}

/**
 * Stock Location API client.
 * list() uses SKIP_UNWRAP to read both data and PageMeta.
 * Mutation methods use the auto-unwrap path.
 * Base: /api/v1/stock-locations
 */
@Injectable({ providedIn: 'root' })
export class StockLocationService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock-locations`;

  // ── Reads ─────────────────────────────────────────────────────────────────

  list(page = 0, size = 20): Observable<StockLocationPage> {
    const params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<StockLocationDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  activeForBranch(branchUid: string): Observable<StockLocationDto[]> {
    const params = new HttpParams().set('branchUid', branchUid);
    return this.http.get<StockLocationDto[]>(`${this.base}/active`, { params });
  }

  getByUid(uid: string): Observable<StockLocationDto> {
    return this.http.get<StockLocationDto>(`${this.base}/uid/${uid}`);
  }

  // ── Writes ────────────────────────────────────────────────────────────────

  create(request: CreateStockLocationRequest): Observable<StockLocationDto> {
    return this.http.post<StockLocationDto>(this.base, request);
  }

  update(uid: string, request: UpdateStockLocationRequest): Observable<StockLocationDto> {
    return this.http.put<StockLocationDto>(`${this.base}/uid/${uid}`, request);
  }

  deactivate(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }

  reactivate(uid: string): Observable<StockLocationDto> {
    return this.http.patch<StockLocationDto>(`${this.base}/uid/${uid}/reactivate`, {});
  }

  setDefault(uid: string): Observable<StockLocationDto> {
    return this.http.patch<StockLocationDto>(`${this.base}/uid/${uid}/default`, {});
  }
}
