import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AddVatAdjustmentRequest,
  CreateWhtTypeRequest,
  FileVatReturnRequest,
  OpenVatReturnRequest,
  UpdateWhtTypeRequest,
  VatAdjustmentDto,
  VatReturnDto,
  WhtRegisterDto,
  WhtTypeDto,
} from './models/tax.model';
import { ExportFormat } from '../reporting/models/reporting.model';

export interface VatReturnPage {
  rows: VatReturnDto[];
  meta: PageMeta;
}

/**
 * Tax API client. Two backend resource roots:
 *   /api/v1/vat  — VAT returns + adjustments
 *   /api/v1/wht  — WHT types + register
 *
 * list() methods use SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 */
@Injectable({ providedIn: 'root' })
export class TaxService {
  private readonly http = inject(HttpClient);
  private readonly vatBase = `${environment.apiBaseUrl}/vat`;
  private readonly whtBase = `${environment.apiBaseUrl}/wht`;

  // ── VAT Returns ────────────────────────────────────────────────────────────

  listReturns(companyId: string, page = 0, size = 50): Observable<VatReturnPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<VatReturnDto[]>>(`${this.vatBase}/returns`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getReturn(uid: string): Observable<VatReturnDto> {
    return this.http.get<VatReturnDto>(`${this.vatBase}/returns/uid/${uid}`);
  }

  /** The return face as a document (binary; gated VAT.VIEW + REPORT.EXPORT). */
  exportReturn(uid: string, format: ExportFormat): Observable<Blob> {
    const params = new HttpParams().set('format', format);
    return this.http.get(`${this.vatBase}/returns/uid/${uid}/export`, { params, responseType: 'blob' });
  }

  openReturn(request: OpenVatReturnRequest): Observable<VatReturnDto> {
    return this.http.post<VatReturnDto>(`${this.vatBase}/returns`, request);
  }

  recomputeReturn(uid: string): Observable<VatReturnDto> {
    return this.http.post<VatReturnDto>(`${this.vatBase}/returns/uid/${uid}/recompute`, {});
  }

  fileReturn(uid: string, request: FileVatReturnRequest): Observable<VatReturnDto> {
    return this.http.post<VatReturnDto>(`${this.vatBase}/returns/uid/${uid}/file`, request);
  }

  // ── VAT Adjustments ────────────────────────────────────────────────────────

  listAdjustments(returnUid: string): Observable<VatAdjustmentDto[]> {
    return this.http.get<VatAdjustmentDto[]>(`${this.vatBase}/returns/uid/${returnUid}/adjustments`);
  }

  addAdjustment(returnUid: string, request: AddVatAdjustmentRequest): Observable<VatAdjustmentDto> {
    return this.http.post<VatAdjustmentDto>(`${this.vatBase}/returns/uid/${returnUid}/adjustments`, request);
  }

  removeAdjustment(returnUid: string, adjustmentUid: string): Observable<void> {
    return this.http.delete<void>(`${this.vatBase}/returns/uid/${returnUid}/adjustments/uid/${adjustmentUid}`);
  }

  // ── WHT Types ──────────────────────────────────────────────────────────────

  listWhtTypes(companyId: string): Observable<WhtTypeDto[]> {
    const params = new HttpParams().set('companyId', companyId);
    return this.http.get<WhtTypeDto[]>(`${this.whtBase}/types`, { params });
  }

  createWhtType(request: CreateWhtTypeRequest): Observable<WhtTypeDto> {
    return this.http.post<WhtTypeDto>(`${this.whtBase}/types`, request);
  }

  updateWhtType(uid: string, request: UpdateWhtTypeRequest): Observable<WhtTypeDto> {
    return this.http.put<WhtTypeDto>(`${this.whtBase}/types/uid/${uid}`, request);
  }

  deactivateWhtType(uid: string): Observable<WhtTypeDto> {
    return this.http.post<WhtTypeDto>(`${this.whtBase}/types/uid/${uid}/deactivate`, {});
  }

  // ── WHT Register ───────────────────────────────────────────────────────────

  getWhtRegisterByMonth(companyId: string, year: number, month: number): Observable<WhtRegisterDto> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('year', String(year))
      .set('month', String(month));
    return this.http.get<WhtRegisterDto>(`${this.whtBase}/register`, { params });
  }

  getWhtRegisterByRange(companyId: string, periodStart: string, periodEnd: string): Observable<WhtRegisterDto> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('periodStart', periodStart)
      .set('periodEnd', periodEnd);
    return this.http.get<WhtRegisterDto>(`${this.whtBase}/register`, { params });
  }

  /**
   * The WHT register as a document (binary; gated WHT.VIEW + REPORT.EXPORT). Pass either
   * year+month or periodStart+periodEnd — the same choice the screen makes.
   */
  exportWhtRegister(
    companyId: string,
    period: { year: number; month: number } | { periodStart: string; periodEnd: string },
    format: ExportFormat,
  ): Observable<Blob> {
    let params = new HttpParams().set('companyId', companyId).set('format', format);
    if ('year' in period) {
      params = params.set('year', String(period.year)).set('month', String(period.month));
    } else {
      params = params.set('periodStart', period.periodStart).set('periodEnd', period.periodEnd);
    }
    return this.http.get(`${this.whtBase}/register/export`, { params, responseType: 'blob' });
  }
}
