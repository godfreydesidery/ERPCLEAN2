import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import { ExportFormat } from '../../reporting/models/reporting.model';
import {
  GoodsReceivedRegisterDto,
  GoodsReceivedRegisterFilter,
  OpenPurchaseOrdersDto,
  OpenPurchaseOrdersFilter,
  PeriodBranchFilter,
  PeriodBranchSupplierFilter,
  PurchasePriceVarianceDto,
  PurchasesBySupplierDto,
} from './models/purchase-report.model';

/**
 * Purchase reports API client.
 *
 *   GET /api/v1/reports/purchases/goods-received   (+ /export)  PURCHASE.GOODS_RECEIPT.VIEW
 *   GET /api/v1/reports/purchases/by-supplier      (+ /export)  PURCHASE.GOODS_RECEIPT.VIEW
 *   GET /api/v1/reports/purchases/open-orders      (+ /export)  PURCHASE.ORDER.VIEW
 *   GET /api/v1/reports/purchases/price-variance   (+ /export)  PURCHASE.ORDER.VIEW + GOODS_RECEIPT.VIEW
 *
 * Every export additionally needs REPORT.EXPORT. Company scope comes from the JWT; no companyId is
 * sent. An omitted filter means "all".
 */
@Injectable({ providedIn: 'root' })
export class PurchaseReportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/reports/purchases`;

  goodsReceived(f: GoodsReceivedRegisterFilter, page = 0, size = 50): Observable<GoodsReceivedRegisterDto> {
    const params = this.period(f)
      .set('page', String(page))
      .set('size', String(size));
    return this.http.get<GoodsReceivedRegisterDto>(`${this.base}/goods-received`, {
      params: this.optional(params, { supplierUid: f.supplierUid, productUid: f.productUid }),
    });
  }

  exportGoodsReceived(f: GoodsReceivedRegisterFilter, format: ExportFormat): Observable<Blob> {
    const params = this.optional(this.period(f).set('format', format), {
      supplierUid: f.supplierUid,
      productUid: f.productUid,
    });
    return this.http.get(`${this.base}/goods-received/export`, { params, responseType: 'blob' });
  }

  bySupplier(f: PeriodBranchFilter): Observable<PurchasesBySupplierDto> {
    return this.http.get<PurchasesBySupplierDto>(`${this.base}/by-supplier`, { params: this.period(f) });
  }

  exportBySupplier(f: PeriodBranchFilter, format: ExportFormat): Observable<Blob> {
    return this.http.get(`${this.base}/by-supplier/export`, {
      params: this.period(f).set('format', format),
      responseType: 'blob',
    });
  }

  openOrders(f: OpenPurchaseOrdersFilter): Observable<OpenPurchaseOrdersDto> {
    return this.http.get<OpenPurchaseOrdersDto>(`${this.base}/open-orders`, {
      params: this.optional(new HttpParams(), {
        asOfDate: f.asOfDate,
        branchUid: f.branchUid,
        supplierUid: f.supplierUid,
      }),
    });
  }

  exportOpenOrders(f: OpenPurchaseOrdersFilter, format: ExportFormat): Observable<Blob> {
    const params = this.optional(new HttpParams().set('format', format), {
      asOfDate: f.asOfDate,
      branchUid: f.branchUid,
      supplierUid: f.supplierUid,
    });
    return this.http.get(`${this.base}/open-orders/export`, { params, responseType: 'blob' });
  }

  priceVariance(f: PeriodBranchSupplierFilter): Observable<PurchasePriceVarianceDto> {
    return this.http.get<PurchasePriceVarianceDto>(`${this.base}/price-variance`, {
      params: this.optional(this.period(f), { supplierUid: f.supplierUid }),
    });
  }

  exportPriceVariance(f: PeriodBranchSupplierFilter, format: ExportFormat): Observable<Blob> {
    const params = this.optional(this.period(f).set('format', format), { supplierUid: f.supplierUid });
    return this.http.get(`${this.base}/price-variance/export`, { params, responseType: 'blob' });
  }

  // ---------------------------------------------------------------------------

  private period(f: PeriodBranchFilter): HttpParams {
    return this.optional(new HttpParams().set('fromDate', f.fromDate).set('toDate', f.toDate), {
      branchUid: f.branchUid,
    });
  }

  private optional(params: HttpParams, values: Record<string, string | null | undefined>): HttpParams {
    let p = params;
    for (const [key, value] of Object.entries(values)) {
      if (value) p = p.set(key, value);
    }
    return p;
  }
}
