import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  CaptureSupplierQuoteRequest,
  CreateRfqRequest,
  RfqDto,
  SupplierQuoteDto,
} from './models/rfq.model';

export interface RfqPage {
  rows: RfqDto[];
  meta: PageMeta;
}

export interface SupplierQuotePage {
  rows: SupplierQuoteDto[];
  meta: PageMeta;
}

/**
 * RFQ and Supplier Quote API client.
 *
 * RFQs:            /api/v1/rfqs
 * Supplier Quotes: /api/v1/supplier-quotes
 *
 * list() methods use SKIP_UNWRAP to retain PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 */
@Injectable({ providedIn: 'root' })
export class RfqService {
  private readonly http = inject(HttpClient);
  private readonly rfqBase = `${environment.apiBaseUrl}/rfqs`;
  private readonly sqBase = `${environment.apiBaseUrl}/supplier-quotes`;

  // ── RFQs — list / get ──────────────────────────────────────────────────────

  listRfqs(companyId: string, page = 0, size = 20): Observable<RfqPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<RfqDto[]>>(this.rfqBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getRfqByUid(uid: string): Observable<RfqDto> {
    return this.http.get<RfqDto>(`${this.rfqBase}/uid/${uid}`);
  }

  // ── RFQs — mutations / lifecycle ──────────────────────────────────────────

  createRfq(request: CreateRfqRequest): Observable<RfqDto> {
    return this.http.post<RfqDto>(this.rfqBase, request);
  }

  sendRfq(uid: string): Observable<RfqDto> {
    return this.http.post<RfqDto>(`${this.rfqBase}/uid/${uid}/send`, {});
  }

  /** Award: quoteUid is the winning SupplierQuoteDto.uid. Returns updated RfqDto (awardedPoUid set). */
  awardRfq(uid: string, quoteUid: string): Observable<RfqDto> {
    const params = new HttpParams().set('quoteUid', quoteUid);
    return this.http.post<RfqDto>(`${this.rfqBase}/uid/${uid}/award`, {}, { params });
  }

  cancelRfq(uid: string): Observable<RfqDto> {
    return this.http.post<RfqDto>(`${this.rfqBase}/uid/${uid}/cancel`, {});
  }

  // ── Supplier Quotes — list / get ──────────────────────────────────────────

  listQuotesByRfq(rfqUid: string, page = 0, size = 50): Observable<SupplierQuotePage> {
    const params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<SupplierQuoteDto[]>>(`${this.sqBase}/by-rfq/${rfqUid}`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getQuoteByUid(uid: string): Observable<SupplierQuoteDto> {
    return this.http.get<SupplierQuoteDto>(`${this.sqBase}/uid/${uid}`);
  }

  // ── Supplier Quotes — mutations ───────────────────────────────────────────

  captureQuote(request: CaptureSupplierQuoteRequest): Observable<SupplierQuoteDto> {
    return this.http.post<SupplierQuoteDto>(this.sqBase, request);
  }
}
