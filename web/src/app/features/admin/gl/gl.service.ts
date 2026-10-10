import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import { ExportFormat } from '../reporting/models/reporting.model';
import {
  AccountDto,
  CreateAccountRequest,
  FiscalPeriodDto,
  FiscalYearDto,
  GlConfigDto,
  GlPostingExceptionDto,
  GlPostingExceptionFilter,
  GlPostingRepostResultDto,
  GlSalesTieOutDto,
  JournalEntryDto,
  JournalFilter,
  OpenFiscalYearRequest,
  PostJournalRequest,
  SetGlConfigRequest,
  TrialBalanceDto,
  TrialBalanceRangeDto,
  TrialBalanceRangeFilter,
  UpdateAccountRequest,
} from './models/gl.model';

export interface AccountPage {
  rows: AccountDto[];
  meta: PageMeta;
}

export interface JournalPage {
  rows: JournalEntryDto[];
  meta: PageMeta;
}

export interface PostingExceptionPage {
  rows: GlPostingExceptionDto[];
  meta: PageMeta;
}

/**
 * GL API client. Base: /api/v1/gl.
 * list() methods use SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 */
@Injectable({ providedIn: 'root' })
export class GlService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/gl`;

  // ── Accounts ──────────────────────────────────────────────────────────────

  listAccounts(
    companyId: string,
    q?: string,
    page = 0,
    size = 50,
  ): Observable<AccountPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<AccountDto[]>>(`${this.base}/accounts`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getAccountByUid(uid: string): Observable<AccountDto> {
    return this.http.get<AccountDto>(`${this.base}/accounts/uid/${uid}`);
  }

  createAccount(request: CreateAccountRequest): Observable<AccountDto> {
    return this.http.post<AccountDto>(`${this.base}/accounts`, request);
  }

  updateAccount(uid: string, request: UpdateAccountRequest): Observable<AccountDto> {
    return this.http.put<AccountDto>(`${this.base}/accounts/uid/${uid}`, request);
  }

  deleteAccount(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/accounts/uid/${uid}`);
  }

  /** Convenience: load ALL active accounts for a company (for pickers). */
  listAllActiveAccounts(companyId: string): Observable<AccountDto[]> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', '0')
      .set('size', '500');

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<AccountDto[]>>(`${this.base}/accounts`, { params, context })
      .pipe(
        map((env) => (env.data ?? []).filter((a) => a.active)),
      );
  }

  // ── Journals ──────────────────────────────────────────────────────────────

  /** Journal list, newest first; every filter optional (ACC-19). */
  listJournals(companyId: string, page = 0, size = 20, filter: JournalFilter = {}): Observable<JournalPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size))
      .append('sort', 'postingDate,desc')
      .append('sort', 'id,desc');
    if (filter.from) params = params.set('from', filter.from);
    if (filter.to) params = params.set('to', filter.to);
    if (filter.sourceType) params = params.set('sourceType', filter.sourceType);
    if (filter.accountUid) params = params.set('accountUid', filter.accountUid);
    if (filter.q?.trim()) params = params.set('q', filter.q.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<JournalEntryDto[]>>(`${this.base}/journals`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getJournalByUid(uid: string): Observable<JournalEntryDto> {
    return this.http.get<JournalEntryDto>(`${this.base}/journals/uid/${uid}`);
  }

  postJournal(request: PostJournalRequest): Observable<JournalEntryDto> {
    return this.http.post<JournalEntryDto>(`${this.base}/journals`, request);
  }

  reverseJournal(uid: string): Observable<JournalEntryDto> {
    return this.http.post<JournalEntryDto>(`${this.base}/journals/uid/${uid}/reverse`, {});
  }

  // ── Posting exceptions (ACC-02) ───────────────────────────────────────────

  listPostingExceptions(
    companyId: string,
    filter: GlPostingExceptionFilter = {},
    page = 0,
    size = 20,
  ): Observable<PostingExceptionPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size))
      .set('includeResolved', String(!!filter.includeResolved));
    if (filter.sourceType) params = params.set('sourceType', filter.sourceType);
    if (filter.from) params = params.set('from', filter.from);
    if (filter.to) params = params.set('to', filter.to);

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<GlPostingExceptionDto[]>>(`${this.base}/posting-exceptions`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  /** Re-post one exception; `postingDate` (yyyy-MM-dd) moves it to another date, else the original. */
  repostPostingException(
    companyId: string,
    uid: string,
    postingDate?: string | null,
  ): Observable<GlPostingRepostResultDto> {
    return this.http.post<GlPostingRepostResultDto>(
      `${this.base}/posting-exceptions/uid/${uid}/repost`,
      { postingDate: postingDate || null },
      { params: { companyId } },
    );
  }

  getSalesTieOut(companyId: string, from?: string, to?: string): Observable<GlSalesTieOutDto> {
    let params = new HttpParams().set('companyId', companyId);
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    return this.http.get<GlSalesTieOutDto>(`${this.base}/posting-exceptions/sales-tie-out`, { params });
  }

  // ── Fiscal Periods ────────────────────────────────────────────────────────

  listPeriods(companyId: string): Observable<FiscalPeriodDto[]> {
    return this.http.get<FiscalPeriodDto[]>(`${this.base}/periods`, {
      params: { companyId },
    });
  }

  closePeriod(uid: string): Observable<FiscalPeriodDto> {
    return this.http.post<FiscalPeriodDto>(`${this.base}/periods/uid/${uid}/close`, {});
  }

  reopenPeriod(uid: string): Observable<FiscalPeriodDto> {
    return this.http.post<FiscalPeriodDto>(`${this.base}/periods/uid/${uid}/reopen`, {});
  }

  listFiscalYears(companyId: string): Observable<FiscalYearDto[]> {
    return this.http.get<FiscalYearDto[]>(`${this.base}/periods/fiscal-years`, {
      params: { companyId },
    });
  }

  openFiscalYear(request: OpenFiscalYearRequest): Observable<FiscalYearDto> {
    return this.http.post<FiscalYearDto>(`${this.base}/periods/fiscal-years`, request);
  }

  // ── Configs ───────────────────────────────────────────────────────────────

  listConfigs(companyId: string): Observable<GlConfigDto[]> {
    return this.http.get<GlConfigDto[]>(`${this.base}/configs`, {
      params: { companyId },
    });
  }

  setConfig(request: SetGlConfigRequest): Observable<GlConfigDto> {
    return this.http.post<GlConfigDto>(`${this.base}/configs`, request);
  }

  // ── Trial Balance ─────────────────────────────────────────────────────────

  getTrialBalance(companyId: string): Observable<TrialBalanceDto> {
    return this.http.get<TrialBalanceDto>(`${this.base}/trial-balance`, {
      params: { companyId },
    });
  }

  /**
   * The endpoint takes the period's numeric `id` (`?periodId=`), not its uid — see
   * TrialBalanceController#getForPeriod. Sending `periodUid` produced a 400 with no `periodId`
   * bound, which the screen showed as "Could not load trial balance": the period filter never
   * worked. Long ids arrive as JSON strings, hence `string` here.
   */
  getTrialBalanceForPeriod(companyId: string, periodId: string): Observable<TrialBalanceDto> {
    return this.http.get<TrialBalanceDto>(`${this.base}/trial-balance/period`, {
      params: { companyId, periodId },
    });
  }

  /** Trial balance as at a date with opening / movement / closing; optional range and branch (ACC-14). */
  getTrialBalanceRange(companyId: string, filter: TrialBalanceRangeFilter = {}): Observable<TrialBalanceRangeDto> {
    let params = new HttpParams().set('companyId', companyId);
    if (filter.from) params = params.set('from', filter.from);
    if (filter.asAt) params = params.set('asAt', filter.asAt);
    if (filter.branchUid) params = params.set('branchUid', filter.branchUid);
    return this.http.get<TrialBalanceRangeDto>(`${this.base}/trial-balance/range`, { params });
  }

  /**
   * The trial balance as a file. Server-gated GL.VIEW + REPORT.EXPORT (an export discloses more
   * than one on-screen page, so it needs the screen's own permission as well as the export one).
   * Raw bytes: responseType 'blob' means the envelope interceptor sees a Blob and passes it
   * through, so no SKIP_UNWRAP token is needed — same as the ReportingService exports.
   */
  exportTrialBalance(
    companyId: string,
    format: ExportFormat,
    periodId?: string | null,
    range?: TrialBalanceRangeFilter,
  ): Observable<Blob> {
    let params = new HttpParams().set('companyId', companyId).set('format', format);
    if (periodId) params = params.set('periodId', periodId);
    if (range) {
      // An "as at" export prints the closing balances (ACC-14).
      params = params.set('asAt', range.asAt || new Date().toISOString().slice(0, 10));
      if (range.from) params = params.set('from', range.from);
      if (range.branchUid) params = params.set('branchUid', range.branchUid);
    }
    return this.http.get(`${this.base}/trial-balance/export`, { params, responseType: 'blob' });
  }
}
