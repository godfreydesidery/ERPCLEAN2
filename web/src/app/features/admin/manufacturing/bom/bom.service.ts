import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  ActivateBomRequest,
  AddBomComponentRequest,
  BomComponentDto,
  BomDto,
  BomStatus,
  CreateBomRequest,
  UpdateBomComponentRequest,
  UpdateBomRequest,
} from './models/bom.model';

export interface BomPage {
  rows: BomDto[];
  meta: PageMeta;
}

/**
 * BOM API client.
 * list() uses SKIP_UNWRAP to retain PageMeta.
 * All uid-targeted endpoints use the auto-unwrap path.
 * Base URL: /api/v1/boms
 */
@Injectable({ providedIn: 'root' })
export class BomService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/boms`;

  // ── List ──────────────────────────────────────────────────────────────────

  list(
    companyId: string,
    page = 0,
    size = 20,
    parentProductUid?: string,
    status?: BomStatus | '',
  ): Observable<BomPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (parentProductUid) params = params.set('parentProductUid', parentProductUid);
    if (status) params = params.set('status', status);
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<BomDto[]>>(this.base, { params, context })
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

  // ── Single ─────────────────────────────────────────────────────────────────

  getByUid(uid: string): Observable<BomDto> {
    return this.http.get<BomDto>(`${this.base}/uid/${uid}`);
  }

  // ── Create / Update ────────────────────────────────────────────────────────

  create(companyId: string, request: CreateBomRequest): Observable<BomDto> {
    const params = new HttpParams().set('companyId', companyId);
    return this.http.post<BomDto>(this.base, request, { params });
  }

  update(uid: string, request: UpdateBomRequest): Observable<BomDto> {
    return this.http.put<BomDto>(`${this.base}/uid/${uid}`, request);
  }

  // ── Component lines ────────────────────────────────────────────────────────

  addComponent(bomUid: string, request: AddBomComponentRequest): Observable<BomComponentDto> {
    return this.http.post<BomComponentDto>(`${this.base}/uid/${bomUid}/components`, request);
  }

  updateComponent(
    bomUid: string,
    componentUid: string,
    request: UpdateBomComponentRequest,
  ): Observable<BomComponentDto> {
    return this.http.put<BomComponentDto>(
      `${this.base}/uid/${bomUid}/components/${componentUid}`,
      request,
    );
  }

  removeComponent(bomUid: string, componentUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${bomUid}/components/${componentUid}`);
  }

  // ── Lifecycle transitions ─────────────────────────────────────────────────

  activate(uid: string, request: ActivateBomRequest): Observable<BomDto> {
    return this.http.post<BomDto>(`${this.base}/uid/${uid}/activate`, request);
  }

  archive(uid: string): Observable<BomDto> {
    return this.http.post<BomDto>(`${this.base}/uid/${uid}/archive`, null);
  }
}
