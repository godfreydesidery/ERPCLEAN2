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
  JournalEntryDto,
  OpenFiscalYearRequest,
  PostJournalRequest,
  SetGlConfigRequest,
  TrialBalanceDto,
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

  listJournals(companyId: string, page = 0, size = 20): Observable<JournalPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));

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
  ): Observable<Blob> {
    let params = new HttpParams().set('companyId', companyId).set('format', format);
    if (periodId) params = params.set('periodId', periodId);
    return this.http.get(`${this.base}/trial-balance/export`, { params, responseType: 'blob' });
  }
}
