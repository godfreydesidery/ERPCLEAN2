import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import { StockBatchDto } from './stock-batch.model';

export interface StockBatchPage {
  rows: StockBatchDto[];
  meta: PageMeta;
}

/**
 * Stock Batch API client (read-only surface).
 * All list methods use SKIP_UNWRAP to read both data and PageMeta.
 * Base: /api/v1/stock-batches
 *
 * NOTE: The backend @RequestParam accepts Long numeric ids (companyId, locationId, productId).
 * Callers must pass the numeric `id` field from the relevant DTO, not the uid.
 */
@Injectable({ providedIn: 'root' })
export class StockBatchService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock-batches`;

  getByUid(uid: string): Observable<StockBatchDto> {
    return this.http.get<StockBatchDto>(`${this.base}/uid/${uid}`);
  }

  /** List batches at a given location for a product. All ids are numeric Long ids (strings on wire). */
  listAtLocation(
    companyId: string,
    locationId: string,
    productId: string,
    page = 0,
    size = 20,
  ): Observable<StockBatchPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('locationId', locationId)
      .set('productId', productId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<StockBatchDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  /**
   * List batches expiring on or before the given horizon.
   * horizon: ISO date string 'YYYY-MM-DD'.
   * Permission: INVENTORY.EXPIRY.VIEW
   */
  listExpiring(
    companyId: string,
    horizon: string,
    page = 0,
    size = 20,
  ): Observable<StockBatchPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('horizon', horizon)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<StockBatchDto[]>>(`${this.base}/expiring`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }
}
