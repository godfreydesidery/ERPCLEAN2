import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AdjustStockRequest,
  OpeningBalanceRequest,
  SetReorderLevelRequest,
  StockMovementDto,
  StockOnHandDto,
} from '../models/stock.model';

export interface StockOnHandPage {
  rows: StockOnHandDto[];
  meta: PageMeta;
}

export interface StockMovementPage {
  rows: StockMovementDto[];
  meta: PageMeta;
}

/**
 * Stock API client.
 * list() and movements() use SKIP_UNWRAP to read both data and PageMeta.
 * Mutation methods use the auto-unwrap path (interceptor strips the envelope).
 * Base: /api/v1/stock.
 */
@Injectable({ providedIn: 'root' })
export class StockService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock`;

  // ── On-hand list ─────────────────────────────────────────────────────────────

  listOnHand(
    companyId: string,
    branchId?: string,
    q?: string,
    page = 0,
    size = 20,
  ): Observable<StockOnHandPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (branchId?.trim()) params = params.set('branchId', branchId.trim());
    if (q?.trim()) params = params.set('q', q.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<StockOnHandDto[]>>(`${this.base}/on-hand`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  // ── Movements ledger ─────────────────────────────────────────────────────────

  listMovements(
    productUid: string,
    companyId: string,
    page = 0,
    size = 20,
  ): Observable<StockMovementPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<StockMovementDto[]>>(
        `${this.base}/products/uid/${productUid}/movements`,
        { params, context },
      )
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  // ── Adjustment ───────────────────────────────────────────────────────────────

  adjust(request: AdjustStockRequest): Observable<StockMovementDto> {
    return this.http.post<StockMovementDto>(`${this.base}/adjustments`, request);
  }

  // ── Opening balance ──────────────────────────────────────────────────────────

  openingBalance(request: OpeningBalanceRequest): Observable<StockMovementDto> {
    return this.http.post<StockMovementDto>(`${this.base}/opening-balances`, request);
  }

  // ── Reorder level ────────────────────────────────────────────────────────────

  setReorderLevel(uid: string, request: SetReorderLevelRequest): Observable<StockOnHandDto> {
    return this.http.put<StockOnHandDto>(`${this.base}/on-hand/uid/${uid}/reorder-level`, request);
  }
}
