import { HttpClient, HttpContext, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, throwError } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SILENT_ERROR, SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AddInvoiceLineRequest,
  AddPaymentRequest,
  CreateSalesInvoiceRequest,
  CreateTaxRateRequest,
  FinaliseInvoiceRequest,
  FiscalReceiptDto,
  OverrideLinePriceRequest,
  SalesInvoiceDto,
  SalesInvoiceLineDto,
  SalesInvoicePaymentDto,
  TaxRateDto,
  UpdateTaxRateRequest,
  UpdateInvoiceLineRequest,
  VoidInvoiceRequest,
} from '../models/sales.model';

export interface SalesInvoicePage {
  rows: SalesInvoiceDto[];
  meta: PageMeta;
}

/**
 * Sales API client.
 * list() uses SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 * Base: /api/v1/sales-invoices; tax-rates: /api/v1/tax-rates.
 */
@Injectable({ providedIn: 'root' })
export class SalesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/sales-invoices`;
  private readonly taxRateBase = `${environment.apiBaseUrl}/tax-rates`;

  // ── Invoice CRUD ──────────────────────────────────────────────────────────

  list(
    companyId: string,
    q?: string,
    status?: string,
    page = 0,
    size = 20,
    dateFrom?: string,
    dateTo?: string,
  ): Observable<SalesInvoicePage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());
    if (status?.trim()) params = params.set('status', status.trim());
    // SAL-10: optional creation-date window (yyyy-MM-dd, inclusive).
    if (dateFrom) params = params.set('dateFrom', dateFrom);
    if (dateTo) params = params.set('dateTo', dateTo);

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<SalesInvoiceDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<SalesInvoiceDto> {
    return this.http.get<SalesInvoiceDto>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateSalesInvoiceRequest): Observable<SalesInvoiceDto> {
    return this.http.post<SalesInvoiceDto>(this.base, request);
  }

  finalise(uid: string): Observable<void> {
    const request: FinaliseInvoiceRequest = {};
    return this.http.put<void>(`${this.base}/uid/${uid}/finalize`, request);
  }

  void(uid: string, request: VoidInvoiceRequest): Observable<void> {
    return this.http.put<void>(`${this.base}/uid/${uid}/void`, request);
  }

  /**
   * SAL-13 / LSF-17: discard an abandoned DRAFT invoice (no number, nothing posted). A finalised
   * invoice is refused by the server — it is voided instead.
   */
  cancelDraft(uid: string, reason?: string): Observable<void> {
    const r = reason?.trim();
    return this.http.delete<void>(`${this.base}/uid/${uid}`, r ? { params: { reason: r } } : {});
  }

  // ── Lines ─────────────────────────────────────────────────────────────────

  listLines(uid: string): Observable<SalesInvoiceLineDto[]> {
    return this.http.get<SalesInvoiceLineDto[]>(`${this.base}/uid/${uid}/lines`);
  }

  addLine(uid: string, request: AddInvoiceLineRequest): Observable<SalesInvoiceLineDto> {
    return this.http.post<SalesInvoiceLineDto>(`${this.base}/uid/${uid}/lines`, request);
  }

  /** SAL-12: change a DRAFT line's quantity and/or discount. */
  updateLine(uid: string, lineUid: string, request: UpdateInvoiceLineRequest): Observable<SalesInvoiceLineDto> {
    return this.http.put<SalesInvoiceLineDto>(`${this.base}/uid/${uid}/lines/${lineUid}`, request);
  }

  removeLine(uid: string, lineUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/lines/${lineUid}`);
  }

  /** Override a DRAFT line's unit price (FR-SALES-08, BR-SALES-09). Requires SALES.INVOICE.OVERRIDE. */
  overrideLinePrice(uid: string, lineUid: string, unitPriceAmount: number): Observable<SalesInvoiceLineDto> {
    const request: OverrideLinePriceRequest = { unitPriceAmount };
    return this.http.post<SalesInvoiceLineDto>(
      `${this.base}/uid/${uid}/lines/uid/${lineUid}/override-price`,
      request,
    );
  }

  // ── Payments ──────────────────────────────────────────────────────────────

  listPayments(uid: string): Observable<SalesInvoicePaymentDto[]> {
    return this.http.get<SalesInvoicePaymentDto[]>(`${this.base}/uid/${uid}/payments`);
  }

  addPayment(uid: string, request: AddPaymentRequest): Observable<SalesInvoicePaymentDto> {
    return this.http.post<SalesInvoicePaymentDto>(`${this.base}/uid/${uid}/payments`, request);
  }

  removePayment(uid: string, paymentUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/payments/${paymentUid}`);
  }

  // ── Tax Rates ─────────────────────────────────────────────────────────────

  listTaxRates(companyId: string): Observable<TaxRateDto[]> {
    return this.http.get<TaxRateDto[]>(this.taxRateBase, { params: { companyId } });
  }

  updateTaxRate(uid: string, request: UpdateTaxRateRequest): Observable<TaxRateDto> {
    return this.http.put<TaxRateDto>(`${this.taxRateBase}/uid/${uid}`, request);
  }

  createTaxRate(request: CreateTaxRateRequest): Observable<TaxRateDto> {
    return this.http.post<TaxRateDto>(this.taxRateBase, request);
  }

  // ── Fiscal receipt (D-6: EFD, ADR-0049) ──────────────────────────────────

  /**
   * No receipt issued yet → backend 404, mapped here to `null` (not an error). SILENT_ERROR
   * (LUI-03): without it the interceptor raised the blocking "Something went wrong — Fiscal
   * receipt not found" modal on every finalised invoice. The detail screen renders its own
   * fiscal-panel state for a genuine failure.
   */
  getFiscalReceipt(uid: string): Observable<FiscalReceiptDto | null> {
    const context = new HttpContext().set(SILENT_ERROR, true);
    return this.http.get<FiscalReceiptDto>(`${this.base}/uid/${uid}/fiscal-receipt`, { context }).pipe(
      catchError((err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 404) return of(null);
        return throwError(() => err);
      }),
    );
  }

  /** Issues or retries the invoice's fiscal receipt (idempotent per ADR-0049 §4). */
  issueFiscalReceipt(uid: string): Observable<FiscalReceiptDto> {
    return this.http.post<FiscalReceiptDto>(`${this.base}/uid/${uid}/fiscal-receipt`, {});
  }
}
