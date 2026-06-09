import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
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

  getAgeing(companyId: string, customerUid?: string): Observable<ArAgeingRowDto[]> {
    let params = new HttpParams().set('companyId', companyId);
    if (customerUid?.trim()) params = params.set('customerUid', customerUid.trim());
    return this.http.get<ArAgeingRowDto[]>(`${this.base}/ageing`, { params });
  }

  // ── Balance ───────────────────────────────────────────────────────────────

  getBalance(companyId: string, customerUid: string): Observable<ArBalanceDto> {
    return this.http.get<ArBalanceDto>(`${this.base}/balance`, {
      params: { companyId, customerUid },
    });
  }
}
