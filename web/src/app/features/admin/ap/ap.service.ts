import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AcceptVarianceRequest,
  ApBalanceDto,
  ApDebitNoteDto,
  ApPaymentDto,
  ApAgeingRowDto,
  ApReconciliationDto,
  BillMatchResultDto,
  EnterBillRequest,
  PaymentRunRequest,
  PaySingleBillRequest,
  RaiseDebitNoteRequest,
  SetApOpeningBalanceRequest,
  SupplierBillDto,
} from './models/ap.model';

export interface SupplierBillPage {
  rows: SupplierBillDto[];
  meta: PageMeta;
}

export interface ApPaymentPage {
  rows: ApPaymentDto[];
  meta: PageMeta;
}

export interface ApDebitNotePage {
  rows: ApDebitNoteDto[];
  meta: PageMeta;
}

/**
 * AP API client. Base: /api/v1/ap.
 * list() methods use SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 */
@Injectable({ providedIn: 'root' })
export class ApService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/ap`;

  // ── Supplier bills ────────────────────────────────────────────────────────

  /**
   * Paged supplier bills.
   *
   * @param uncomparedOnly keep only bills whose lines were NOT all checked against a purchase order
   *   and a goods receipt. Filtered in the database, so paging and totals stay honest — filtering a
   *   page client-side would silently shrink pages and understate how many bills need a look.
   *   Last in the list on purpose: every other caller passes positionally, and inserting a
   *   parameter mid-signature would have silently re-bound their page number to a boolean.
   */
  listBills(
    companyId: string,
    supplierUid?: string,
    status?: string,
    page = 0,
    size = 20,
    uncomparedOnly = false,
  ): Observable<SupplierBillPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (supplierUid?.trim()) params = params.set('supplierUid', supplierUid.trim());
    if (status?.trim()) params = params.set('status', status.trim());
    if (uncomparedOnly) params = params.set('uncomparedOnly', 'true');

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<SupplierBillDto[]>>(`${this.base}/supplier-bills`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getBill(uid: string): Observable<SupplierBillDto> {
    return this.http.get<SupplierBillDto>(`${this.base}/supplier-bills/uid/${uid}`);
  }

  enterBill(request: EnterBillRequest): Observable<SupplierBillDto> {
    return this.http.post<SupplierBillDto>(`${this.base}/supplier-bills`, request);
  }

  // ── 3-way match ───────────────────────────────────────────────────────────

  runMatch(billUid: string): Observable<BillMatchResultDto> {
    return this.http.post<BillMatchResultDto>(
      `${this.base}/supplier-bills/uid/${billUid}/match/run`,
      {},
    );
  }

  acceptVariance(billUid: string, request: AcceptVarianceRequest): Observable<BillMatchResultDto> {
    return this.http.post<BillMatchResultDto>(
      `${this.base}/supplier-bills/uid/${billUid}/match/accept-variance`,
      request,
    );
  }

  // ── Payments ──────────────────────────────────────────────────────────────

  paySingle(request: PaySingleBillRequest): Observable<ApPaymentDto> {
    return this.http.post<ApPaymentDto>(`${this.base}/payments/single`, request);
  }

  paymentRun(request: PaymentRunRequest): Observable<ApPaymentDto[]> {
    return this.http.post<ApPaymentDto[]>(`${this.base}/payments/payment-run`, request);
  }

  listPayments(
    companyId: string,
    supplierUid?: string,
    page = 0,
    size = 20,
  ): Observable<ApPaymentPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (supplierUid?.trim()) params = params.set('supplierUid', supplierUid.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ApPaymentDto[]>>(`${this.base}/payments`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getPayment(uid: string): Observable<ApPaymentDto> {
    return this.http.get<ApPaymentDto>(`${this.base}/payments/uid/${uid}`);
  }

  // ── Debit notes ───────────────────────────────────────────────────────────

  raiseDebitNote(request: RaiseDebitNoteRequest): Observable<ApDebitNoteDto> {
    return this.http.post<ApDebitNoteDto>(`${this.base}/debit-notes`, request);
  }

  listDebitNotes(
    companyId: string,
    supplierUid?: string,
    page = 0,
    size = 20,
  ): Observable<ApDebitNotePage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (supplierUid?.trim()) params = params.set('supplierUid', supplierUid.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ApDebitNoteDto[]>>(`${this.base}/debit-notes`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  // ── Opening balance ───────────────────────────────────────────────────────

  setOpeningBalance(request: SetApOpeningBalanceRequest): Observable<SupplierBillDto> {
    return this.http.post<SupplierBillDto>(`${this.base}/opening-balance`, request);
  }

  // ── Statement / ageing / balance ──────────────────────────────────────────

  getBalance(companyId: string, supplierUid: string): Observable<ApBalanceDto> {
    return this.http.get<ApBalanceDto>(`${this.base}/statement/balance`, {
      params: { companyId, supplierUid },
    });
  }

  getAgeing(companyId: string, supplierUid?: string): Observable<ApAgeingRowDto[]> {
    let params = new HttpParams().set('companyId', companyId);
    if (supplierUid?.trim()) params = params.set('supplierUid', supplierUid.trim());
    return this.http.get<ApAgeingRowDto[]>(`${this.base}/statement/ageing`, { params });
  }

  getReconciliation(companyId: string): Observable<ApReconciliationDto> {
    return this.http.get<ApReconciliationDto>(`${this.base}/statement/reconciliation`, {
      params: { companyId },
    });
  }
}
