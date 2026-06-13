import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  CreatePurchaseReturnRequest,
  PurchaseReturnDto,
} from '../../models/purchases.model';

export interface PurchaseReturnPage {
  rows: PurchaseReturnDto[];
  meta: PageMeta;
}

/**
 * Purchase Return API client.
 * list() uses SKIP_UNWRAP to retain PageMeta.
 * All other methods use the auto-unwrap interceptor path.
 */
@Injectable({ providedIn: 'root' })
export class PurchaseReturnService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/purchase-returns`;

  list(companyId: string, page = 0, size = 20): Observable<PurchaseReturnPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<PurchaseReturnDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<PurchaseReturnDto> {
    return this.http.get<PurchaseReturnDto>(`${this.base}/uid/${uid}`);
  }

  create(request: CreatePurchaseReturnRequest): Observable<PurchaseReturnDto> {
    return this.http.post<PurchaseReturnDto>(this.base, request);
  }

  confirm(uid: string): Observable<PurchaseReturnDto> {
    return this.http.post<PurchaseReturnDto>(`${this.base}/uid/${uid}/confirm`, {});
  }
}
