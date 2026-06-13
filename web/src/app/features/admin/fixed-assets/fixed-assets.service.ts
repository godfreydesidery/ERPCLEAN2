import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AcquireFromBillRequest,
  AssetCategoryDto,
  AssetDisposalDto,
  AssetRevaluationDto,
  CreateAssetCategoryRequest,
  DepreciationRunDto,
  DepreciationRunPreviewDto,
  DepreciationScheduleLineDto,
  DisposeAssetRequest,
  FixedAssetDto,
  FixedAssetReconciliationDto,
  FixedAssetStatus,
  PlaceInServiceRequest,
  RegisterAssetRequest,
  RevalueAssetRequest,
  RunDepreciationRequest,
  TransferAssetRequest,
  UpdateAssetCategoryRequest,
  UpdateAssetRequest,
  WriteOffAssetRequest,
} from './models/fixed-assets.model';

export interface FixedAssetPage {
  rows: FixedAssetDto[];
  meta: PageMeta;
}

export interface DepreciationRunPage {
  rows: DepreciationRunDto[];
  meta: PageMeta;
}

/**
 * Fixed Assets module API client.
 * Paginated list()s use SKIP_UNWRAP to retain PageMeta.
 * All other calls use the auto-unwrap path (interceptor strips envelope).
 */
@Injectable({ providedIn: 'root' })
export class FixedAssetsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/fixed-assets`;
  private readonly categoryBase = `${environment.apiBaseUrl}/fixed-assets/categories`;
  private readonly runBase = `${environment.apiBaseUrl}/fixed-assets/depreciation-runs`;

  // ── Asset Categories ────────────────────────────────────────────────────────

  listCategories(companyId: string): Observable<AssetCategoryDto[]> {
    return this.http.get<AssetCategoryDto[]>(this.categoryBase, { params: { companyId } });
  }

  getCategoryByUid(uid: string): Observable<AssetCategoryDto> {
    return this.http.get<AssetCategoryDto>(`${this.categoryBase}/uid/${uid}`);
  }

  createCategory(request: CreateAssetCategoryRequest): Observable<AssetCategoryDto> {
    return this.http.post<AssetCategoryDto>(this.categoryBase, request);
  }

  updateCategory(uid: string, request: UpdateAssetCategoryRequest): Observable<AssetCategoryDto> {
    return this.http.put<AssetCategoryDto>(`${this.categoryBase}/uid/${uid}`, request);
  }

  /** Archive (soft-delete) — returns the archived DTO. */
  archiveCategory(uid: string): Observable<AssetCategoryDto> {
    return this.http.delete<AssetCategoryDto>(`${this.categoryBase}/uid/${uid}`);
  }

  // ── Fixed Assets ────────────────────────────────────────────────────────────

  list(companyId: string, page = 0, size = 20, status?: FixedAssetStatus): Observable<FixedAssetPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (status) params = params.set('status', status);
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<FixedAssetDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<FixedAssetDto> {
    return this.http.get<FixedAssetDto>(`${this.base}/uid/${uid}`);
  }

  register(request: RegisterAssetRequest): Observable<FixedAssetDto> {
    return this.http.post<FixedAssetDto>(this.base, request);
  }

  acquireFromBill(request: AcquireFromBillRequest): Observable<FixedAssetDto> {
    return this.http.post<FixedAssetDto>(`${this.base}/acquire-from-bill`, request);
  }

  update(uid: string, request: UpdateAssetRequest): Observable<FixedAssetDto> {
    return this.http.put<FixedAssetDto>(`${this.base}/uid/${uid}`, request);
  }

  placeInService(uid: string, request: PlaceInServiceRequest): Observable<FixedAssetDto> {
    return this.http.post<FixedAssetDto>(`${this.base}/uid/${uid}/place-in-service`, request);
  }

  transfer(uid: string, request: TransferAssetRequest): Observable<FixedAssetDto> {
    return this.http.post<FixedAssetDto>(`${this.base}/uid/${uid}/transfer`, request);
  }

  dispose(uid: string, request: DisposeAssetRequest): Observable<AssetDisposalDto> {
    return this.http.post<AssetDisposalDto>(`${this.base}/uid/${uid}/dispose`, request);
  }

  writeOff(uid: string, request: WriteOffAssetRequest): Observable<AssetDisposalDto> {
    return this.http.post<AssetDisposalDto>(`${this.base}/uid/${uid}/write-off`, request);
  }

  revalue(uid: string, request: RevalueAssetRequest): Observable<AssetRevaluationDto> {
    return this.http.post<AssetRevaluationDto>(`${this.base}/uid/${uid}/revalue`, request);
  }

  /** Child: depreciation schedule lines for an asset. */
  listSchedule(uid: string): Observable<DepreciationScheduleLineDto[]> {
    return this.http.get<DepreciationScheduleLineDto[]>(`${this.base}/uid/${uid}/schedule`);
  }

  /** Child: revaluation history for an asset. */
  listRevaluations(uid: string): Observable<AssetRevaluationDto[]> {
    return this.http.get<AssetRevaluationDto[]>(`${this.base}/uid/${uid}/revaluations`);
  }

  /** Report: FA-to-GL reconciliation bars. */
  getReconciliation(companyId: string): Observable<FixedAssetReconciliationDto> {
    return this.http.get<FixedAssetReconciliationDto>(`${this.base}/reconciliation`, { params: { companyId } });
  }

  // ── Depreciation Runs ────────────────────────────────────────────────────────

  listRuns(companyId: string, page = 0, size = 20): Observable<DepreciationRunPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<DepreciationRunDto[]>>(this.runBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getRunByUid(uid: string): Observable<DepreciationRunDto> {
    return this.http.get<DepreciationRunDto>(`${this.runBase}/uid/${uid}`);
  }

  /** Preview (POST with query params — nothing is posted/persisted). */
  previewRun(companyId: string, fiscalPeriodUid: string): Observable<DepreciationRunPreviewDto> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('fiscalPeriodUid', fiscalPeriodUid);
    return this.http.post<DepreciationRunPreviewDto>(`${this.runBase}/preview`, null, { params });
  }

  /** Post depreciation run (idempotent per company+period). */
  postRun(request: RunDepreciationRequest): Observable<DepreciationRunDto> {
    return this.http.post<DepreciationRunDto>(this.runBase, request);
  }
}
