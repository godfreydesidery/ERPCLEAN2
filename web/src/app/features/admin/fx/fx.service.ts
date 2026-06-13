import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  CurrencyDto,
  CurrencyRatePage,
  CurrencyRateDto,
  FxRevaluationPreviewDto,
  FxRevaluationRunDto,
  FxRevaluationRunPage,
  PostFxRevaluationRequest,
  UpsertRateRequest,
} from './models/fx.model';

/**
 * FX / Multi-currency API client (ADR-0036 Tranche 5).
 * Base: /api/v1/fx
 *
 * Currencies: non-paginated → plain Observable<T[]> (interceptor auto-unwraps).
 * Rates list: paginated (Page<CurrencyRateDto>) → SKIP_UNWRAP → {rows, meta}.
 * Revaluation runs list: paginated (ApiResponse<List<>>) → SKIP_UNWRAP → {rows, meta}.
 * Preview + post + reverse: non-paginated (interceptor strips envelope).
 */
@Injectable({ providedIn: 'root' })
export class FxService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/fx`;

  // ── Currency master ────────────────────────────────────────────────────────

  /** List all active currencies (global reference). CURRENCY.VIEW. */
  listCurrencies(): Observable<CurrencyDto[]> {
    return this.http.get<CurrencyDto[]>(`${this.base}/currencies`);
  }

  /** Get a single currency by uid. CURRENCY.VIEW. */
  getCurrency(uid: string): Observable<CurrencyDto> {
    return this.http.get<CurrencyDto>(`${this.base}/currencies/uid/${uid}`);
  }

  // ── Currency rates ─────────────────────────────────────────────────────────

  /**
   * List rates for a company, paginated (newest-first).
   * Endpoint returns Spring Page<T> wrapped in ApiResponse → SKIP_UNWRAP required.
   * CURRENCY.VIEW.
   */
  listRates(companyId: string, page = 0, size = 50): Observable<CurrencyRatePage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<CurrencyRateDto[]>>(`${this.base}/rates`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? ({
            page,
            size,
            totalElements: env.data?.length ?? 0,
            totalPages: 1,
            hasNext: false,
          } satisfies PageMeta),
        })),
      );
  }

  /** Get a single rate by uid. CURRENCY.VIEW. */
  getRate(uid: string): Observable<CurrencyRateDto> {
    return this.http.get<CurrencyRateDto>(`${this.base}/rates/uid/${uid}`);
  }

  /**
   * Add a new effective-dated rate row. No edit-in-place — correction = new row.
   * CURRENCY.MANAGE.
   */
  addRate(request: UpsertRateRequest): Observable<CurrencyRateDto> {
    return this.http.post<CurrencyRateDto>(`${this.base}/rates`, request);
  }

  // ── Revaluation runs ───────────────────────────────────────────────────────

  /**
   * Dry-run preview — compute revaluation lines, no GL post. FX.REVALUE.
   * Endpoint returns ApiResponse<FxRevaluationPreviewDto> — interceptor unwraps.
   */
  previewRevaluation(
    companyId: string,
    fiscalPeriodUid: string,
    spotRateDate?: string | null,
  ): Observable<FxRevaluationPreviewDto> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('fiscalPeriodUid', fiscalPeriodUid);
    if (spotRateDate) {
      params = params.set('spotRateDate', spotRateDate);
    }
    return this.http.get<FxRevaluationPreviewDto>(`${this.base}/revaluation-runs/preview`, { params });
  }

  /**
   * Post a revaluation run — persists + posts GL journal. FX.REVALUE.
   * Returns FxRevaluationRunDto (interceptor unwraps ApiResponse).
   */
  postRevaluation(request: PostFxRevaluationRequest): Observable<FxRevaluationRunDto> {
    return this.http.post<FxRevaluationRunDto>(`${this.base}/revaluation-runs`, request);
  }

  /**
   * Explicitly trigger reversal for a run. FX.REVALUE.
   */
  reverseRevaluation(uid: string, reversalDate: string): Observable<FxRevaluationRunDto> {
    const params = new HttpParams().set('reversalDate', reversalDate);
    return this.http.post<FxRevaluationRunDto>(
      `${this.base}/revaluation-runs/uid/${uid}/reverse`,
      {},
      { params },
    );
  }

  /**
   * List revaluation runs for a company, paginated. FX.EXPOSURE.VIEW.
   * Backend wraps in ApiResponse<List<>> with PageMeta → SKIP_UNWRAP.
   */
  listRuns(companyId: string, page = 0, size = 50): Observable<FxRevaluationRunPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<FxRevaluationRunDto[]>>(`${this.base}/revaluation-runs`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? ({
            page,
            size,
            totalElements: env.data?.length ?? 0,
            totalPages: 1,
            hasNext: false,
          } satisfies PageMeta),
        })),
      );
  }

  /**
   * Get a single revaluation run by uid (includes lines). FX.EXPOSURE.VIEW.
   */
  getRun(uid: string): Observable<FxRevaluationRunDto> {
    return this.http.get<FxRevaluationRunDto>(`${this.base}/revaluation-runs/uid/${uid}`);
  }
}
