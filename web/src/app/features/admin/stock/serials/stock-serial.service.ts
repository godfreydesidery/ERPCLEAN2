import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import { SerialStatus, StockSerialDto } from './stock-serial.model';

export interface StockSerialPage {
  rows: StockSerialDto[];
  meta: PageMeta;
}

/**
 * Stock Serial API client (read-only surface).
 * All list methods use SKIP_UNWRAP.
 * Base: /api/v1/stock-serials
 *
 * NOTE: The backend @RequestParam accepts Long numeric ids (companyId, locationId, productId).
 * Callers must pass the numeric `id` field from the relevant DTO, not the uid.
 */
@Injectable({ providedIn: 'root' })
export class StockSerialService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock-serials`;

  getByUid(uid: string): Observable<StockSerialDto> {
    return this.http.get<StockSerialDto>(`${this.base}/uid/${uid}`);
  }

  /** Look up a serial by number within a company+product scope. */
  lookup(companyId: string, productId: string, serialNumber: string): Observable<StockSerialDto> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('productId', productId)
      .set('serialNumber', serialNumber);
    return this.http.get<StockSerialDto>(`${this.base}/lookup`, { params });
  }

  /** Paged list at a location for a product. All ids are numeric Long ids (strings on wire). */
  listAtLocation(
    companyId: string,
    locationId: string,
    productId: string,
    status?: SerialStatus,
    page = 0,
    size = 20,
  ): Observable<StockSerialPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('locationId', locationId)
      .set('productId', productId)
      .set('page', String(page))
      .set('size', String(size));
    if (status) params = params.set('status', status);
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<StockSerialDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  /** Full history paged list by product uid. companyId is the numeric Long id. */
  listByProduct(
    productUid: string,
    companyId: string,
    page = 0,
    size = 20,
  ): Observable<StockSerialPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<StockSerialDto[]>>(
        `${this.base}/product/uid/${productUid}`,
        { params, context },
      )
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }
}
