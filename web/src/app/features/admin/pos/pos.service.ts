import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import { SalesInvoiceDto } from '../models/sales.model';
import {
  CloseSessionRequest,
  CreatePosTillRequest,
  OpenSessionRequest,
  PosPayoutRequest,
  PosSaleRequest,
  PosSessionDto,
  PosSessionStatus,
  PosTillDto,
  ReconcileSessionRequest,
  XReadDto,
  ZReadDto,
} from './models/pos.model';

export interface PosSessionPage {
  rows: PosSessionDto[];
  meta: PageMeta;
}

/**
 * POS API client (ADR-0029).
 * listSessions() uses SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips envelope).
 */
@Injectable({ providedIn: 'root' })
export class PosService {
  private readonly http = inject(HttpClient);
  private readonly tillBase = `${environment.apiBaseUrl}/pos/tills`;
  private readonly sessionBase = `${environment.apiBaseUrl}/pos/sessions`;
  private readonly saleBase = `${environment.apiBaseUrl}/pos/sales`;

  // ── Tills ─────────────────────────────────────────────────────────────────

  /** Plain list — NOT paged (backend returns PosTillDto[]). */
  listTills(companyId: string, branchId?: string): Observable<PosTillDto[]> {
    let params = new HttpParams().set('companyId', companyId);
    if (branchId) params = params.set('branchId', branchId);
    return this.http.get<PosTillDto[]>(this.tillBase, { params });
  }

  getTillByUid(uid: string): Observable<PosTillDto> {
    return this.http.get<PosTillDto>(`${this.tillBase}/uid/${uid}`);
  }

  createTill(request: CreatePosTillRequest): Observable<PosTillDto> {
    return this.http.post<PosTillDto>(this.tillBase, request);
  }

  /** Deactivate till. */
  deleteTill(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.tillBase}/uid/${uid}`);
  }

  // ── Sessions ──────────────────────────────────────────────────────────────

  listSessions(companyId: string, page = 0, size = 20, status?: PosSessionStatus): Observable<PosSessionPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (status) params = params.set('status', status);
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<PosSessionDto[]>>(this.sessionBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getSessionByUid(uid: string): Observable<PosSessionDto> {
    return this.http.get<PosSessionDto>(`${this.sessionBase}/uid/${uid}`);
  }

  openSession(request: OpenSessionRequest): Observable<PosSessionDto> {
    return this.http.post<PosSessionDto>(this.sessionBase, request);
  }

  recordPayout(sessionUid: string, request: PosPayoutRequest): Observable<void> {
    return this.http.post<void>(`${this.sessionBase}/uid/${sessionUid}/payouts`, request);
  }

  closeSession(sessionUid: string, request: CloseSessionRequest): Observable<PosSessionDto> {
    return this.http.post<PosSessionDto>(`${this.sessionBase}/uid/${sessionUid}/close`, request);
  }

  xRead(sessionUid: string): Observable<XReadDto> {
    return this.http.get<XReadDto>(`${this.sessionBase}/uid/${sessionUid}/x-read`);
  }

  reconcileSession(sessionUid: string, request: ReconcileSessionRequest): Observable<ZReadDto> {
    return this.http.post<ZReadDto>(`${this.sessionBase}/uid/${sessionUid}/reconcile`, request);
  }

  // ── Sales ─────────────────────────────────────────────────────────────────

  processSale(request: PosSaleRequest): Observable<SalesInvoiceDto> {
    return this.http.post<SalesInvoiceDto>(this.saleBase, request);
  }
}
