import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ExportFormat } from '../reporting/models/reporting.model';
import {
  ProductStockReportDto,
  ProductStockReportFilter,
} from './models/product-stock-report.model';

/**
 * Product List / Stock Value report API client.
 *
 *   GET /api/v1/stock/reports/product-list         → ProductStockReportDto (totals null)
 *   GET /api/v1/stock/reports/product-list/export  → raw PDF/XLSX/CSV bytes
 *   GET /api/v1/stock/reports/stock-value          → ProductStockReportDto (totals populated)
 *   GET /api/v1/stock/reports/stock-value/export   → raw PDF/XLSX/CSV bytes
 *
 * One client for both reports: they share a DTO and a parameter set, and differ only in whether the
 * server computes totals. Company scope comes from the JWT + X-Branch-Uid — no companyId is ever
 * sent; the optional branchUid / supplierUid narrow WITHIN the caller's company. Reading is gated
 * INVENTORY.VALUATION.VIEW and exporting additionally REPORT.EXPORT (two distinct permissions).
 *
 * Typed to the UNWRAPPED DTO — apiResponseInterceptor strips the ApiResponse envelope. SKIP_UNWRAP
 * is deliberately not set: these responses carry no paging `meta`.
 */
@Injectable({ providedIn: 'root' })
export class ProductStockReportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock/reports`;

  productList(filter: ProductStockReportFilter): Observable<ProductStockReportDto> {
    return this.http.get<ProductStockReportDto>(`${this.base}/product-list`, {
      params: this.paramsFor(filter),
    });
  }

  stockValue(filter: ProductStockReportFilter): Observable<ProductStockReportDto> {
    return this.http.get<ProductStockReportDto>(`${this.base}/stock-value`, {
      params: this.paramsFor(filter),
    });
  }

  exportProductList(filter: ProductStockReportFilter, format: ExportFormat): Observable<Blob> {
    return this.http.get(`${this.base}/product-list/export`, {
      params: this.paramsFor(filter).set('format', format),
      responseType: 'blob',
    });
  }

  exportStockValue(filter: ProductStockReportFilter, format: ExportFormat): Observable<Blob> {
    return this.http.get(`${this.base}/stock-value/export`, {
      params: this.paramsFor(filter).set('format', format),
      responseType: 'blob',
    });
  }

  /** A blank filter is OMITTED, never sent as an empty string — an empty uid is not "no filter". */
  private paramsFor(filter: ProductStockReportFilter): HttpParams {
    let params = new HttpParams();
    if (filter.branchUid) params = params.set('branchUid', filter.branchUid);
    if (filter.supplierUid) params = params.set('supplierUid', filter.supplierUid);
    return params;
  }
}
