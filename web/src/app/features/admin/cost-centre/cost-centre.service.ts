import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  CreateDimensionRequest,
  CreateDimensionValueRequest,
  DimensionDto,
  DimensionSlicedTbDto,
  DimensionSlot,
  DimensionValueDto,
  SetDimensionMandatoryRequest,
  UpdateDimensionValueRequest,
} from './models/cost-centre.model';

export interface DimensionValuePage {
  rows: DimensionValueDto[];
  meta: PageMeta;
}

/**
 * Cost-centre module API client.
 * Endpoints:
 *   GET /api/v1/dimensions                  — non-paginated list (plain array, no SKIP_UNWRAP)
 *   GET /api/v1/dimension-values            — PAGINATED (SKIP_UNWRAP -> {rows, meta})
 *   GET /api/v1/costing/reports/sliced-trial-balance — non-paginated report
 */
@Injectable({ providedIn: 'root' })
export class CostCentreService {
  private readonly http = inject(HttpClient);
  private readonly dimBase = `${environment.apiBaseUrl}/dimensions`;
  private readonly valBase = `${environment.apiBaseUrl}/dimension-values`;
  private readonly reportBase = `${environment.apiBaseUrl}/costing/reports`;

  // ── Dimension types (seeded, read-only list + mandatory toggle) ───────────

  /** Non-paginated list — returns Observable<DimensionDto[]> directly (interceptor unwraps). */
  listDimensions(companyId: string): Observable<DimensionDto[]> {
    const params = new HttpParams().set('companyId', companyId);
    return this.http.get<DimensionDto[]>(this.dimBase, { params });
  }

  getDimensionByUid(uid: string): Observable<DimensionDto> {
    return this.http.get<DimensionDto>(`${this.dimBase}/uid/${uid}`);
  }

  /** PATCH /dimensions/uid/{uid}/mandatory — toggle mandatory flag. */
  setMandatory(uid: string, request: SetDimensionMandatoryRequest): Observable<DimensionDto> {
    return this.http.patch<DimensionDto>(`${this.dimBase}/uid/${uid}/mandatory`, request);
  }

  /**
   * POST /dimensions — create a custom dimension; backend assigns the next free slot
   * (DIMENSION_3 then DIMENSION_4). Returns 409 when both custom slots are in use.
   */
  createDimension(request: CreateDimensionRequest): Observable<DimensionDto> {
    return this.http.post<DimensionDto>(this.dimBase, request);
  }

  // ── Dimension values (paginated list + CRUD + actions) ────────────────────

  /** Paginated — uses SKIP_UNWRAP to preserve PageMeta. */
  listValues(dimensionUid: string, page = 0, size = 20): Observable<DimensionValuePage> {
    const params = new HttpParams()
      .set('dimensionUid', dimensionUid)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<DimensionValueDto[]>>(this.valBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getValueByUid(uid: string): Observable<DimensionValueDto> {
    return this.http.get<DimensionValueDto>(`${this.valBase}/uid/${uid}`);
  }

  /** POST — HTTP 201 Created */
  createValue(request: CreateDimensionValueRequest): Observable<DimensionValueDto> {
    return this.http.post<DimensionValueDto>(this.valBase, request);
  }

  /** PUT — partial update (name / parentUid / clearParent) */
  updateValue(uid: string, request: UpdateDimensionValueRequest): Observable<DimensionValueDto> {
    return this.http.put<DimensionValueDto>(`${this.valBase}/uid/${uid}`, request);
  }

  /** PATCH .../deactivate — sets active=false (excludes from new tagging) */
  deactivateValue(uid: string): Observable<DimensionValueDto> {
    return this.http.patch<DimensionValueDto>(`${this.valBase}/uid/${uid}/deactivate`, {});
  }

  /** PATCH .../activate — reverses deactivate */
  activateValue(uid: string): Observable<DimensionValueDto> {
    return this.http.patch<DimensionValueDto>(`${this.valBase}/uid/${uid}/activate`, {});
  }

  /**
   * DELETE — HTTP 204. Hard-delete; service rejects if the value has ANY journal postings (BR-CC-05).
   * Prefer deactivate when postings may exist.
   */
  deleteValue(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.valBase}/uid/${uid}`);
  }

  // ── Costing report ────────────────────────────────────────────────────────

  /**
   * GET /costing/reports/sliced-trial-balance
   * Requires BOTH COSTING.VIEW and GL.VIEW.
   * companyId and slot are required; all others optional.
   */
  getSlicedTrialBalance(
    companyId: string,
    slot: DimensionSlot,
    options?: {
      valueUid?: string | null;
      rollUp?: boolean;
      periodId?: string | null;
    },
  ): Observable<DimensionSlicedTbDto> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('slot', slot)
      .set('rollUp', String(options?.rollUp ?? false));
    if (options?.valueUid) params = params.set('valueUid', options.valueUid);
    if (options?.periodId) params = params.set('periodId', options.periodId);
    return this.http.get<DimensionSlicedTbDto>(`${this.reportBase}/sliced-trial-balance`, { params });
  }
}
