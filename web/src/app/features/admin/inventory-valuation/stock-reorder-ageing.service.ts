import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ExportFormat } from '../reporting/models/reporting.model';
import { ReorderReportDto, ReorderReportFilter } from './models/reorder-report.model';
import { StockAgeingFilter, StockAgeingReportDto } from './models/stock-ageing.model';

/**
 * Reorder Report and Stock Ageing API client.
 *
 *   GET /api/v1/reports/reorder[/export]        — STOCK.VIEW (+ REPORT.EXPORT)
 *   GET /api/v1/reports/stock-ageing[/export]   — INVENTORY.VALUATION.VIEW (+ REPORT.EXPORT)
 *
 * Company scope comes from the JWT; a blank filter is omitted, never sent as "".
 */
@Injectable({ providedIn: 'root' })
export class StockReorderAgeingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/reports`;

  reorder(filter: ReorderReportFilter): Observable<ReorderReportDto> {
    return this.http.get<ReorderReportDto>(`${this.base}/reorder`, {
      params: this.reorderParams(filter),
    });
  }

  exportReorder(filter: ReorderReportFilter, format: ExportFormat): Observable<Blob> {
    return this.http.get(`${this.base}/reorder/export`, {
      params: this.reorderParams(filter).set('format', format),
      responseType: 'blob',
    });
  }

  stockAgeing(filter: StockAgeingFilter): Observable<StockAgeingReportDto> {
    return this.http.get<StockAgeingReportDto>(`${this.base}/stock-ageing`, {
      params: this.ageingParams(filter),
    });
  }

  exportStockAgeing(filter: StockAgeingFilter, format: ExportFormat): Observable<Blob> {
    return this.http.get(`${this.base}/stock-ageing/export`, {
      params: this.ageingParams(filter).set('format', format),
      responseType: 'blob',
    });
  }

  private reorderParams(f: ReorderReportFilter): HttpParams {
    let p = new HttpParams();
    if (f.branchUid) p = p.set('branchUid', f.branchUid);
    if (f.supplierUid) p = p.set('supplierUid', f.supplierUid);
    return p;
  }

  private ageingParams(f: StockAgeingFilter): HttpParams {
    let p = new HttpParams();
    if (f.asOf) p = p.set('asOf', f.asOf);
    if (f.branchUid) p = p.set('branchUid', f.branchUid);
    return p;
  }
}
