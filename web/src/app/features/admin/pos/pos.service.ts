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

  /** Mid-shift snapshot. Server accepts it while OPEN or CLOSED, and 409s once RECONCILED. */
  xRead(sessionUid: string): Observable<XReadDto> {
    return this.http.get<XReadDto>(`${this.sessionBase}/uid/${sessionUid}/x-read`);
  }

  /**
   * Final shift report for an already-RECONCILED session, recomputed server-side from persisted
   * rows. Reconciling is one-shot, so without this read the figures were only ever visible in the
   * reconcile response body — a manager who closed the shift could never see them again.
   */
  zRead(sessionUid: string): Observable<ZReadDto> {
    return this.http.get<ZReadDto>(`${this.sessionBase}/uid/${sessionUid}/z-read`);
  }

  reconcileSession(sessionUid: string, request: ReconcileSessionRequest): Observable<ZReadDto> {
    return this.http.post<ZReadDto>(`${this.sessionBase}/uid/${sessionUid}/reconcile`, request);
  }

  // ── Sales ─────────────────────────────────────────────────────────────────

  /**
   * Ring a sale. `idempotencyKey` MUST be stable for one basket and reused verbatim on every
   * retry — mint it once when the operator starts the sale, never per attempt.
   *
   * Without the header the server (where it is `required = false`) skips its dedup reserve
   * entirely, so a retried or double-submitted POST becomes a second finalised invoice: duplicate
   * revenue, VAT, COGS and stock issue. That is the shape of the doubled sales-report quantities
   * reported from the field, and it is why this parameter is not optional here.
   */
  processSale(request: PosSaleRequest, idempotencyKey: string): Observable<SalesInvoiceDto> {
    return this.http.post<SalesInvoiceDto>(this.saleBase, request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }
}
