import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SILENT_ERROR, SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AddPurchaseOrderLineRequest,
  CreateGoodsReceiptRequest,
  CreatePurchaseOrderRequest,
  GoodsReceiptDto,
  PurchaseOrderDto,
  PurchaseOrderLineDto,
  UpdatePurchaseOrderRequest,
  VoidGoodsReceiptRequest,
  VoidPurchaseOrderRequest,
} from '../models/purchases.model';

export interface PurchaseOrderPage {
  rows: PurchaseOrderDto[];
  meta: PageMeta;
}

export interface GoodsReceiptPage {
  rows: GoodsReceiptDto[];
  meta: PageMeta;
}

/**
 * Purchases API client.
 * list() methods use SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 * Purchase Orders: /api/v1/purchase-orders
 * Goods Receipts:  /api/v1/goods-receipts
 */
@Injectable({ providedIn: 'root' })
export class PurchasesService {
  private readonly http = inject(HttpClient);
  private readonly poBase = `${environment.apiBaseUrl}/purchase-orders`;
  private readonly grBase = `${environment.apiBaseUrl}/goods-receipts`;

  // ── Purchase Orders — list / get ─────────────────────────────────────────────

  listOrders(
    companyId: string,
    q?: string,
    status?: string,
    page = 0,
    size = 20,
  ): Observable<PurchaseOrderPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());
    if (status?.trim()) params = params.set('status', status.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<PurchaseOrderDto[]>>(this.poBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getOrderByUid(uid: string): Observable<PurchaseOrderDto> {
    return this.http.get<PurchaseOrderDto>(`${this.poBase}/uid/${uid}`);
  }

  listOrderLines(uid: string): Observable<PurchaseOrderLineDto[]> {
    return this.http.get<PurchaseOrderLineDto[]>(`${this.poBase}/uid/${uid}/lines`);
  }

  // ── Purchase Orders — mutations ──────────────────────────────────────────────

  createOrder(request: CreatePurchaseOrderRequest): Observable<PurchaseOrderDto> {
    return this.http.post<PurchaseOrderDto>(this.poBase, request);
  }

  updateOrder(uid: string, request: UpdatePurchaseOrderRequest): Observable<PurchaseOrderDto> {
    return this.http.put<PurchaseOrderDto>(`${this.poBase}/uid/${uid}`, request);
  }

  addLine(uid: string, request: AddPurchaseOrderLineRequest): Observable<PurchaseOrderLineDto> {
    return this.http.post<PurchaseOrderLineDto>(`${this.poBase}/uid/${uid}/lines`, request);
  }

  updateLine(uid: string, lineUid: string, request: AddPurchaseOrderLineRequest): Observable<PurchaseOrderLineDto> {
    return this.http.put<PurchaseOrderLineDto>(`${this.poBase}/uid/${uid}/lines/${lineUid}`, request);
  }

  removeLine(uid: string, lineUid: string): Observable<void> {
    return this.http.delete<void>(`${this.poBase}/uid/${uid}/lines/${lineUid}`);
  }

  // ── Purchase Orders — lifecycle ──────────────────────────────────────────────

  placeOrder(uid: string): Observable<PurchaseOrderDto> {
    return this.http.post<PurchaseOrderDto>(`${this.poBase}/uid/${uid}/place`, {});
  }

  /**
   * Submit a DRAFT PO to the approval engine (PURCHASE.ORDER.CREATE).
   * The backend transitions approval_status → PENDING (or APPROVED when auto-approved).
   * Returns 409 when the PO is already pending or does not require approval.
   */
  submitForApproval(uid: string): Observable<PurchaseOrderDto> {
    return this.http.post<PurchaseOrderDto>(`${this.poBase}/uid/${uid}/submit-for-approval`, {});
  }

  closeOrder(uid: string): Observable<PurchaseOrderDto> {
    return this.http.post<PurchaseOrderDto>(`${this.poBase}/uid/${uid}/close`, {});
  }

  voidOrder(uid: string, request: VoidPurchaseOrderRequest): Observable<PurchaseOrderDto> {
    return this.http.post<PurchaseOrderDto>(`${this.poBase}/uid/${uid}/void`, request);
  }

  // ── Goods Receipts — list / get ──────────────────────────────────────────────

  listReceipts(
    companyId: string,
    q?: string,
    page = 0,
    size = 20,
  ): Observable<GoodsReceiptPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<GoodsReceiptDto[]>>(this.grBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getReceiptByUid(uid: string): Observable<GoodsReceiptDto> {
    return this.http.get<GoodsReceiptDto>(`${this.grBase}/uid/${uid}`);
  }

  // ── Goods Receipts — mutations ───────────────────────────────────────────────

  createReceipt(request: CreateGoodsReceiptRequest): Observable<GoodsReceiptDto> {
    // The create screen renders its own inline over-receipt (409) banner, so silence the global
    // error notification for this call — the user sees one calm message, not a duplicate toast.
    return this.http.post<GoodsReceiptDto>(this.grBase, request, {
      context: new HttpContext().set(SILENT_ERROR, true),
    });
  }

  voidReceipt(uid: string, request: VoidGoodsReceiptRequest): Observable<void> {
    return this.http.post<void>(`${this.grBase}/uid/${uid}/void`, request);
  }
}
