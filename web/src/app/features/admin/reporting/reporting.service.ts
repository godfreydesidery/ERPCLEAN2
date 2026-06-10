import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  AccountLedgerDto,
  BalanceSheetDto,
  CashFlowStatementDto,
  ExportFormat,
  IncomeStatementDto,
} from './models/reporting.model';

/**
 * Financial Reporting API client (ADR-0018, FR-REP-01..05).
 *
 * Read endpoints return the statement DTOs directly (the backend returns the DTO,
 * NOT an ApiResponse<T> envelope — same pattern as TrialBalanceController). The
 * interceptor's isEnvelope() check will not match (no `errors` array), so the body
 * passes through unwrapped. No SKIP_UNWRAP token required for reads.
 *
 * Export endpoints return raw binary bytes (ResponseEntity<byte[]> with Content-Disposition).
 * We request responseType:'blob' so Angular treats the body as a Blob. The interceptor's
 * isEnvelope() check receives a Blob, not a plain object, so it passes through safely
 * without SKIP_UNWRAP. The blob() helper triggers a browser download.
 */
@Injectable({ providedIn: 'root' })
export class ReportingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/reports`;

  // ── Income Statement / P&L ────────────────────────────────────────────────

  incomeStatement(
    companyId: string,
    fromDate: string,
    toDate: string,
    cmpFrom?: string | null,
    cmpTo?: string | null,
  ): Observable<IncomeStatementDto> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('fromDate', fromDate)
      .set('toDate', toDate);
    if (cmpFrom) params = params.set('cmpFrom', cmpFrom);
    if (cmpTo) params = params.set('cmpTo', cmpTo);
    return this.http.get<IncomeStatementDto>(`${this.base}/income-statement`, { params });
  }

  exportIncomeStatement(
    companyId: string,
    fromDate: string,
    toDate: string,
    format: ExportFormat,
    cmpFrom?: string | null,
    cmpTo?: string | null,
  ): Observable<Blob> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('fromDate', fromDate)
      .set('toDate', toDate)
      .set('format', format);
    if (cmpFrom) params = params.set('cmpFrom', cmpFrom);
    if (cmpTo) params = params.set('cmpTo', cmpTo);
    return this.http.get(`${this.base}/income-statement/export`, {
      params,
      responseType: 'blob',
    });
  }

  // ── Balance Sheet ──────────────────────────────────────────────────────────

  balanceSheet(
    companyId: string,
    asAtDate: string,
    compareAsAt?: string | null,
  ): Observable<BalanceSheetDto> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('asAtDate', asAtDate);
    if (compareAsAt) params = params.set('compareAsAt', compareAsAt);
    return this.http.get<BalanceSheetDto>(`${this.base}/balance-sheet`, { params });
  }

  exportBalanceSheet(
    companyId: string,
    asAtDate: string,
    format: ExportFormat,
    compareAsAt?: string | null,
  ): Observable<Blob> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('asAtDate', asAtDate)
      .set('format', format);
    if (compareAsAt) params = params.set('compareAsAt', compareAsAt);
    return this.http.get(`${this.base}/balance-sheet/export`, {
      params,
      responseType: 'blob',
    });
  }

  // ── Cash-Flow Statement ───────────────────────────────────────────────────

  cashFlow(
    companyId: string,
    fromDate: string,
    toDate: string,
    cmpFrom?: string | null,
    cmpTo?: string | null,
  ): Observable<CashFlowStatementDto> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('fromDate', fromDate)
      .set('toDate', toDate);
    if (cmpFrom) params = params.set('cmpFrom', cmpFrom);
    if (cmpTo) params = params.set('cmpTo', cmpTo);
    return this.http.get<CashFlowStatementDto>(`${this.base}/cash-flow`, { params });
  }

  exportCashFlow(
    companyId: string,
    fromDate: string,
    toDate: string,
    format: ExportFormat,
    cmpFrom?: string | null,
    cmpTo?: string | null,
  ): Observable<Blob> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('fromDate', fromDate)
      .set('toDate', toDate)
      .set('format', format);
    if (cmpFrom) params = params.set('cmpFrom', cmpFrom);
    if (cmpTo) params = params.set('cmpTo', cmpTo);
    return this.http.get(`${this.base}/cash-flow/export`, {
      params,
      responseType: 'blob',
    });
  }

  // ── Account Ledger drill-down ─────────────────────────────────────────────

  accountLedger(
    companyId: string,
    accountUid: string,
    fromDate: string,
    toDate: string,
    page = 0,
    size = 50,
  ): Observable<AccountLedgerDto> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('accountUid', accountUid)
      .set('fromDate', fromDate)
      .set('toDate', toDate)
      .set('page', String(page))
      .set('size', String(size));
    return this.http.get<AccountLedgerDto>(`${this.base}/account-ledger`, { params });
  }

  exportAccountLedger(
    companyId: string,
    accountUid: string,
    fromDate: string,
    toDate: string,
    format: ExportFormat,
  ): Observable<Blob> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('accountUid', accountUid)
      .set('fromDate', fromDate)
      .set('toDate', toDate)
      .set('format', format);
    return this.http.get(`${this.base}/account-ledger/export`, {
      params,
      responseType: 'blob',
    });
  }
}
