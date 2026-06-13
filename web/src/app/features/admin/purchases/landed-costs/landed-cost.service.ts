import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  CreateLandedCostRequest,
  LandedCostDto,
} from '../../models/purchases.model';

export interface LandedCostPage {
  rows: LandedCostDto[];
  meta: PageMeta;
}

/**
 * Landed Cost API client.
 * list() uses SKIP_UNWRAP to retain PageMeta.
 * All other methods use the auto-unwrap interceptor path.
 */
@Injectable({ providedIn: 'root' })
export class LandedCostService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/landed-costs`;

  list(companyId: string, page = 0, size = 20): Observable<LandedCostPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<LandedCostDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<LandedCostDto> {
    return this.http.get<LandedCostDto>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateLandedCostRequest): Observable<LandedCostDto> {
    return this.http.post<LandedCostDto>(this.base, request);
  }

  confirm(uid: string): Observable<LandedCostDto> {
    return this.http.post<LandedCostDto>(`${this.base}/uid/${uid}/confirm`, {});
  }
}
