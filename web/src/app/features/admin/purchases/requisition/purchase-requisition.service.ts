import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { environment } from '../../../../../environments/environment';
import {
  ConvertRequisitionRequest,
  CreatePurchaseRequisitionRequest,
  PurchaseRequisitionDto,
} from './purchase-requisition.model';

export interface PurchaseRequisitionPage {
  rows: PurchaseRequisitionDto[];
  meta: PageMeta;
}

/**
 * Purchase Requisition API client.
 * list() uses SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 * Base: /api/v1/purchase-requisitions
 */
@Injectable({ providedIn: 'root' })
export class PurchaseRequisitionService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/purchase-requisitions`;

  // ── List / Get ─────────────────────────────────────────────────────────────

  list(
    companyId: string,
    status?: string,
    page = 0,
    size = 20,
  ): Observable<PurchaseRequisitionPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (status?.trim()) params = params.set('status', status.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<PurchaseRequisitionDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? {
            page,
            size,
            totalElements: env.data?.length ?? 0,
            totalPages: 1,
            hasNext: false,
          },
        })),
      );
  }

  getByUid(uid: string): Observable<PurchaseRequisitionDto> {
    return this.http.get<PurchaseRequisitionDto>(`${this.base}/uid/${uid}`);
  }

  // ── Create ─────────────────────────────────────────────────────────────────

  create(request: CreatePurchaseRequisitionRequest): Observable<PurchaseRequisitionDto> {
    return this.http.post<PurchaseRequisitionDto>(this.base, request);
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  submit(uid: string): Observable<PurchaseRequisitionDto> {
    return this.http.post<PurchaseRequisitionDto>(`${this.base}/uid/${uid}/submit`, {});
  }

  approve(uid: string): Observable<PurchaseRequisitionDto> {
    return this.http.post<PurchaseRequisitionDto>(`${this.base}/uid/${uid}/approve`, {});
  }

  /** reason passed as query param per controller: ?reason=... */
  reject(uid: string, reason: string): Observable<PurchaseRequisitionDto> {
    const params = new HttpParams().set('reason', reason);
    return this.http.post<PurchaseRequisitionDto>(
      `${this.base}/uid/${uid}/reject`,
      {},
      { params },
    );
  }

  /**
   * Converts the requisition to a PURCHASE_ORDER or RFQ, actually creating the target document
   * server-side (D-3). Body-based contract (no more ?targetType= query param): RFQ requires
   * supplierUids (non-empty); PURCHASE_ORDER requires supplierUid. Returns the created
   * document's uid (String).
   */
  convert(uid: string, request: ConvertRequisitionRequest): Observable<string> {
    return this.http.post<string>(`${this.base}/uid/${uid}/convert`, request);
  }

  cancel(uid: string, reason: string): Observable<PurchaseRequisitionDto> {
    const params = new HttpParams().set('reason', reason);
    return this.http.post<PurchaseRequisitionDto>(
      `${this.base}/uid/${uid}/cancel`,
      {},
      { params },
    );
  }
}
