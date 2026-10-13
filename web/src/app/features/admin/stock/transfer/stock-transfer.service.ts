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
/** STK-19: optional filters for the transfer list. */
export interface StockTransferListFilters {
  status?: string;
  direction?: '' | 'INCOMING' | 'OUTGOING' | 'BRANCH';
  /** ISO yyyy-MM-dd */
  fromDate?: string;
  /** ISO yyyy-MM-dd */
  toDate?: string;
  /** Transfer-number fragment. */
  q?: string;
}
@Injectable({ providedIn: 'root' })
export class StockTransferService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock-transfers`;
  private readonly locationBase = `${environment.apiBaseUrl}/stock-locations`;

  // ── Export / print ───────────────────────────────────────────────────────────

  /**
   * The transfer as a printable document. PDF is what goes with the goods; XLSX and CSV are for
   * working with the figures. Returns the raw blob — the caller names the file and saves it.
   */
  export(uid: string, format: 'PDF' | 'XLSX' | 'CSV'): Observable<Blob> {
    return this.http.get(`${this.base}/uid/${uid}/export`, {
      params: { format },
      responseType: 'blob',
    });
  }

  // ── List ─────────────────────────────────────────────────────────────────────

  /**
   * GET /stock/transfers — newest transfer date first (server default). STK-19 filters are all
   * optional; `direction` is relative to the ACTIVE branch (INCOMING / OUTGOING / BRANCH).
   */
  list(page = 0, size = 20, filters: StockTransferListFilters = {}): Observable<StockTransferPage> {
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    const params: Record<string, string> = { page: String(page), size: String(size) };
    for (const [k, v] of Object.entries(filters)) {
      if (typeof v === 'string' && v.trim()) params[k] = v.trim();
    }
    return this.http
      .get<ApiResponse<StockTransferDto[]>>(this.base, {
        params,
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
