import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  CreateVanReconciliationRequest,
  EnterVanReconciliationLinesRequest,
  VanReconciliationDto,
} from './van-reconciliation.model';

export interface VanReconciliationPage {
  rows: VanReconciliationDto[];
  meta: PageMeta;
}

/**
 * Van-Stock Reconciliation API client (ADR-0051 D-8).
 * Base: /api/v1/van-reconciliations. list() is a plain Spring `Pageable` endpoint — scoped
 * implicitly to the caller's company + active branch via `RequestContext` (JWT + the
 * `X-Branch-Uid` header the interceptor already attaches), NOT explicit companyId/branchId query
 * params. Switching the app's branch selector re-scopes the list with no extra wiring here.
 * Uses SKIP_UNWRAP to read PageMeta, mirroring stock-counts/stock-transfers.
 */
@Injectable({ providedIn: 'root' })
export class VanReconciliationService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/van-reconciliations`;

  // ── List ─────────────────────────────────────────────────────────────────────

  list(page = 0, size = 20): Observable<VanReconciliationPage> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<VanReconciliationDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  // ── Get by uid ───────────────────────────────────────────────────────────────

  get(uid: string): Observable<VanReconciliationDto> {
    return this.http.get<VanReconciliationDto>(`${this.base}/uid/${uid}`);
  }

  // ── Create (OPEN, lines prefilled) ──────────────────────────────────────────

  create(request: CreateVanReconciliationRequest): Observable<VanReconciliationDto> {
    return this.http.post<VanReconciliationDto>(this.base, request);
  }

  // ── Enter sold/physical (+ optional loaded/returned overrides) ──────────────

  enterLines(uid: string, request: EnterVanReconciliationLinesRequest): Observable<VanReconciliationDto> {
    return this.http.post<VanReconciliationDto>(`${this.base}/uid/${uid}/lines`, request);
  }

  // ── Lifecycle actions ────────────────────────────────────────────────────────

  reconcile(uid: string): Observable<VanReconciliationDto> {
    return this.http.post<VanReconciliationDto>(`${this.base}/uid/${uid}/reconcile`, {});
  }

  cancel(uid: string): Observable<VanReconciliationDto> {
    return this.http.post<VanReconciliationDto>(`${this.base}/uid/${uid}/cancel`, {});
  }
}
