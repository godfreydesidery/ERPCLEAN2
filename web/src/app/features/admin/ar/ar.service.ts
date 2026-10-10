import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AllocationLineRequest,
  ArBalanceDto,
  ArInvoiceDto,
  ArAgeingRowDto,
  ArReceiptDto,
  ArStatementDto,
  ArWriteOffDto,
  ArCreditNoteDto,
  RaiseCreditNoteRequest,
  RecordReceiptRequest,
  SetOpeningBalanceRequest,
  WriteOffRequest,
} from './models/ar.model';
import { ExportFormat } from '../reporting/models/reporting.model';

export interface ArInvoicePage {
  rows: ArInvoiceDto[];
  meta: PageMeta;
}

export interface ArReceiptPage {
  rows: ArReceiptDto[];
  meta: PageMeta;
}

/**
 * AR API client. Base: /api/v1/ar.
 * list() methods use SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 */
@Injectable({ providedIn: 'root' })
export class ArService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/ar`;

  // ── Invoices ──────────────────────────────────────────────────────────────

  listInvoices(
    companyId: string,
    customerUid?: string,
    status?: string,
    page = 0,
    size = 20,
  ): Observable<ArInvoicePage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (customerUid?.trim()) params = params.set('customerUid', customerUid.trim());
    if (status?.trim()) params = params.set('status', status.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ArInvoiceDto[]>>(`${this.base}/invoices`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  /**
   * Every open / part-paid item of ONE customer, oldest due date first (not paged). Feeds the
   * allocation grid on Record Receipt and Apply-to-invoices.
   */
  listOpenInvoices(companyId: string, customerUid: string): Observable<ArInvoiceDto[]> {
    const params = new HttpParams().set('companyId', companyId).set('customerUid', customerUid);
    return this.http.get<ArInvoiceDto[]>(`${this.base}/invoices/open`, { params });
  }

  getInvoice(uid: string): Observable<ArInvoiceDto> {
    return this.http.get<ArInvoiceDto>(`${this.base}/invoices/uid/${uid}`);
  }

  // ── Receipts ──────────────────────────────────────────────────────────────

  recordReceipt(request: RecordReceiptRequest): Observable<ArReceiptDto> {
    return this.http.post<ArReceiptDto>(`${this.base}/receipts`, request);
  }

  listReceipts(
    companyId: string,
    customerUid?: string,
    page = 0,
    size = 20,
  ): Observable<ArReceiptPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (customerUid?.trim()) params = params.set('customerUid', customerUid.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ArReceiptDto[]>>(`${this.base}/receipts`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getReceipt(uid: string): Observable<ArReceiptDto> {
    return this.http.get<ArReceiptDto>(`${this.base}/receipts/uid/${uid}`);
  }

  /**
   * Replace a receipt's allocation set (ARC-06): PUT /ar/receipts/uid/{uid}/allocations. The lines
   * sent become the WHOLE set — include the existing allocations to keep them. Whatever the lines
   * do not cover stays on account. Posts nothing to the GL.
   */
  reallocateReceipt(uid: string, allocations: AllocationLineRequest[]): Observable<ArReceiptDto> {
    return this.http.put<ArReceiptDto>(`${this.base}/receipts/uid/${uid}/allocations`, { allocations });
  }

  // ── Write-offs ────────────────────────────────────────────────────────────

  writeOff(request: WriteOffRequest): Observable<ArWriteOffDto> {
    return this.http.post<ArWriteOffDto>(`${this.base}/write-offs`, request);
  }

  // ── Credit notes ──────────────────────────────────────────────────────────

  raiseCreditNote(request: RaiseCreditNoteRequest): Observable<ArCreditNoteDto> {
    return this.http.post<ArCreditNoteDto>(`${this.base}/credit-notes`, request);
  }

  // ── Opening balances ──────────────────────────────────────────────────────

  setOpeningBalance(request: SetOpeningBalanceRequest): Observable<ArInvoiceDto> {
    return this.http.post<ArInvoiceDto>(`${this.base}/opening-balances`, request);
  }

  // ── Statement ─────────────────────────────────────────────────────────────

  getStatement(companyId: string, customerUid: string): Observable<ArStatementDto> {
    return this.http.get<ArStatementDto>(`${this.base}/statement`, {
      params: { companyId, customerUid },
    });
  }

  // ── Ageing ────────────────────────────────────────────────────────────────

  /**
   * Per-customer ageing (one row per customer with open items).
   * Backed by GET /api/v1/ar/ageing/by-customer — company-wide, not per-customer-filtered.
   */
  getAgeing(companyId: string): Observable<ArAgeingRowDto[]> {
    const params = new HttpParams().set('companyId', companyId);
    return this.http.get<ArAgeingRowDto[]>(`${this.base}/ageing/by-customer`, { params });
  }

  // ── Exports (binary download; gated AR.STATEMENT.VIEW + REPORT.EXPORT) ─────

  /**
   * Customer statement document: balance b/f, every movement in the period with a running
   * balance, closing balance. An empty fromDate runs from the customer's first transaction.
   */
  exportStatement(
    companyId: string,
    customerUid: string,
    fromDate: string,
    toDate: string,
    format: ExportFormat,
  ): Observable<Blob> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('customerUid', customerUid)
      .set('format', format);
    if (fromDate) params = params.set('fromDate', fromDate);
    if (toDate) params = params.set('toDate', toDate);
    return this.http.get(`${this.base}/statement/export`, { params, responseType: 'blob' });
  }

  /** Per-customer ageing document (buckets per customer + totals). */
  exportAgeing(companyId: string, format: ExportFormat, asAt?: string): Observable<Blob> {
    let params = new HttpParams().set('companyId', companyId).set('format', format);
    if (asAt) params = params.set('asAt', asAt);
    return this.http.get(`${this.base}/ageing/by-customer/export`, { params, responseType: 'blob' });
  }

  // ── Balance ───────────────────────────────────────────────────────────────

  getBalance(companyId: string, customerUid: string): Observable<ArBalanceDto> {
    return this.http.get<ArBalanceDto>(`${this.base}/balance`, {
      params: { companyId, customerUid },
    });
  }
}
