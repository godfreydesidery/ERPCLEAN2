import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  CreateStockTransferRequest,
  StockLocationDto,
  StockTransferDto,
} from './stock-transfer.model';

export interface StockTransferPage {
  rows: StockTransferDto[];
  meta: PageMeta;
}

/**
 * Stock Transfer API client.
 * list() uses SKIP_UNWRAP to preserve PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips envelope).
 * Base: /api/v1/stock-transfers.
 */
@Injectable({ providedIn: 'root' })
export class StockTransferService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock-transfers`;
  private readonly locationBase = `${environment.apiBaseUrl}/stock-locations`;

  // ── List ─────────────────────────────────────────────────────────────────────

  list(page = 0, size = 20): Observable<StockTransferPage> {
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<StockTransferDto[]>>(this.base, {
        params: { page: String(page), size: String(size) },
        context,
      })
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

  // ── Get by uid ───────────────────────────────────────────────────────────────

  getByUid(uid: string): Observable<StockTransferDto> {
    return this.http.get<StockTransferDto>(`${this.base}/uid/${uid}`);
  }

  // ── Create (DRAFT) ───────────────────────────────────────────────────────────

  create(request: CreateStockTransferRequest): Observable<StockTransferDto> {
    return this.http.post<StockTransferDto>(this.base, request);
  }

  // ── Lifecycle actions ────────────────────────────────────────────────────────

  completeInstant(uid: string): Observable<StockTransferDto> {
    return this.http.patch<StockTransferDto>(`${this.base}/uid/${uid}/complete-instant`, {});
  }

  dispatch(uid: string): Observable<StockTransferDto> {
    return this.http.patch<StockTransferDto>(`${this.base}/uid/${uid}/dispatch`, {});
  }

  receive(uid: string): Observable<StockTransferDto> {
    return this.http.patch<StockTransferDto>(`${this.base}/uid/${uid}/receive`, {});
  }

  cancel(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }

  // ── Stock locations (for pickers) ────────────────────────────────────────────

  /** Returns active stock locations for the given branchUid. */
  activeLocations(branchUid: string): Observable<StockLocationDto[]> {
    return this.http.get<StockLocationDto[]>(`${this.locationBase}/active`, {
      params: { branchUid },
    });
  }
}
