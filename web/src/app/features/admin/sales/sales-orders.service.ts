import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AddQuotationLineRequest,
  AddSalesOrderLineRequest,
  CancelSalesOrderRequest,
  CreateDeliveryRequest,
  CreateQuotationRequest,
  CreateSalesOrderRequest,
  CreateSalesReturnRequest,
  DeliveryDto,
  QuotationDto,
  QuotationLineDto,
  SalesOrderDto,
  SalesOrderLineDto,
  SalesReturnDto,
  SetSalesOrderAgentRequest,
} from '../models/sales-orders.model';
import { SalesInvoiceDto } from '../models/sales.model';

export interface QuotationPage {
  rows: QuotationDto[];
  meta: PageMeta;
}

export interface SalesOrderPage {
  rows: SalesOrderDto[];
  meta: PageMeta;
}

export interface DeliveryPage {
  rows: DeliveryDto[];
  meta: PageMeta;
}

export interface SalesReturnPage {
  rows: SalesReturnDto[];
  meta: PageMeta;
}

/**
 * Sales Orders / Order-to-Cash API client.
 * list*() use SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips envelope).
 */
@Injectable({ providedIn: 'root' })
export class SalesOrdersService {
  private readonly http = inject(HttpClient);
  private readonly quoteBase = `${environment.apiBaseUrl}/quotations`;
  private readonly orderBase = `${environment.apiBaseUrl}/sales-orders`;
  private readonly deliveryBase = `${environment.apiBaseUrl}/deliveries`;
  private readonly returnBase = `${environment.apiBaseUrl}/sales-returns`;

  // ── Quotations ────────────────────────────────────────────────────────────────

  listQuotations(companyId: string, page = 0, size = 20): Observable<QuotationPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<QuotationDto[]>>(this.quoteBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getQuotationByUid(uid: string): Observable<QuotationDto> {
    return this.http.get<QuotationDto>(`${this.quoteBase}/uid/${uid}`);
  }

  createQuotation(request: CreateQuotationRequest): Observable<QuotationDto> {
    return this.http.post<QuotationDto>(this.quoteBase, request);
  }

  listQuotationLines(uid: string): Observable<QuotationLineDto[]> {
    return this.http.get<QuotationLineDto[]>(`${this.quoteBase}/uid/${uid}/lines`);
  }

  addQuotationLine(uid: string, request: AddQuotationLineRequest): Observable<QuotationLineDto> {
    return this.http.post<QuotationLineDto>(`${this.quoteBase}/uid/${uid}/lines`, request);
  }

  removeQuotationLine(uid: string, lineUid: string): Observable<void> {
    return this.http.delete<void>(`${this.quoteBase}/uid/${uid}/lines/${lineUid}`);
  }

  sendQuotation(uid: string): Observable<void> {
    return this.http.put<void>(`${this.quoteBase}/uid/${uid}/send`, {});
  }

  acceptQuotation(uid: string): Observable<SalesOrderDto> {
    return this.http.put<SalesOrderDto>(`${this.quoteBase}/uid/${uid}/accept`, {});
  }

  rejectQuotation(uid: string): Observable<void> {
    return this.http.put<void>(`${this.quoteBase}/uid/${uid}/reject`, {});
  }

  // ── Sales Orders ──────────────────────────────────────────────────────────────

  listOrders(companyId: string, page = 0, size = 20): Observable<SalesOrderPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<SalesOrderDto[]>>(this.orderBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getOrderByUid(uid: string): Observable<SalesOrderDto> {
    return this.http.get<SalesOrderDto>(`${this.orderBase}/uid/${uid}`);
  }

  createOrder(request: CreateSalesOrderRequest): Observable<SalesOrderDto> {
    return this.http.post<SalesOrderDto>(this.orderBase, request);
  }

  listOrderLines(uid: string): Observable<SalesOrderLineDto[]> {
    return this.http.get<SalesOrderLineDto[]>(`${this.orderBase}/uid/${uid}/lines`);
  }

  addOrderLine(uid: string, request: AddSalesOrderLineRequest): Observable<SalesOrderLineDto> {
    return this.http.post<SalesOrderLineDto>(`${this.orderBase}/uid/${uid}/lines`, request);
  }

  removeOrderLine(uid: string, lineUid: string): Observable<void> {
    return this.http.delete<void>(`${this.orderBase}/uid/${uid}/lines/${lineUid}`);
  }

  confirmOrder(uid: string): Observable<void> {
    return this.http.put<void>(`${this.orderBase}/uid/${uid}/confirm`, {});
  }

  /**
   * Submits a DRAFT order into the approval engine (approval_status → PENDING, or APPROVED when
   * auto-approved). Confirming an order whose approval is PENDING or REJECTED is rejected by the
   * backend with a friendly error.
   */
  submitForApproval(uid: string): Observable<SalesOrderDto> {
    return this.http.put<SalesOrderDto>(`${this.orderBase}/uid/${uid}/submit-for-approval`, {});
  }

  cancelOrder(uid: string, request?: CancelSalesOrderRequest): Observable<void> {
    return this.http.put<void>(`${this.orderBase}/uid/${uid}/cancel`, request ?? {});
  }

  setOrderAgent(uid: string, request: SetSalesOrderAgentRequest): Observable<SalesOrderDto> {
    return this.http.patch<SalesOrderDto>(`${this.orderBase}/uid/${uid}/agent`, request);
  }

  // ── Deliveries ────────────────────────────────────────────────────────────────

  listDeliveries(companyId: string, page = 0, size = 20): Observable<DeliveryPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<DeliveryDto[]>>(this.deliveryBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getDeliveryByUid(uid: string): Observable<DeliveryDto> {
    return this.http.get<DeliveryDto>(`${this.deliveryBase}/uid/${uid}`);
  }

  listDeliveriesForOrder(salesOrderUid: string): Observable<DeliveryDto[]> {
    return this.http.get<DeliveryDto[]>(`${this.deliveryBase}/for-order/${salesOrderUid}`);
  }

  createDelivery(request: CreateDeliveryRequest): Observable<DeliveryDto> {
    return this.http.post<DeliveryDto>(this.deliveryBase, request);
  }

  createInvoiceFromDelivery(deliveryUid: string): Observable<SalesInvoiceDto> {
    return this.http.post<SalesInvoiceDto>(`${this.deliveryBase}/uid/${deliveryUid}/invoice`, {});
  }

  // ── Sales Returns ─────────────────────────────────────────────────────────

  listReturns(companyId: string, page = 0, size = 20): Observable<SalesReturnPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<SalesReturnDto[]>>(this.returnBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  listReturnsForDelivery(deliveryUid: string, page = 0, size = 20): Observable<SalesReturnPage> {
    const params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<SalesReturnDto[]>>(`${this.returnBase}/for-delivery/${deliveryUid}`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getReturnByUid(uid: string): Observable<SalesReturnDto> {
    return this.http.get<SalesReturnDto>(`${this.returnBase}/uid/${uid}`);
  }

  createReturn(request: CreateSalesReturnRequest): Observable<SalesReturnDto> {
    return this.http.post<SalesReturnDto>(this.returnBase, request);
  }
}
