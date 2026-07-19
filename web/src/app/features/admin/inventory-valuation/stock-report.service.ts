import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ExportFormat } from '../reporting/models/reporting.model';
import { StockReportDto } from './models/stock-report.model';

/**
 * Stock Report API client (SAM Electronix go-live) — an operational print report distinct from
 * the GL-reconciled Stock Valuation Report at /stock/valuation/report (InventoryValuationService).
 *
 *   GET /api/v1/stock/report        → StockReportDto, unwrapped (no envelope), company/branch
 *                                      scope derived server-side from the JWT + X-Branch-Uid —
 *                                      no companyId param.
 *   GET /api/v1/stock/report/export → raw bytes (responseType:'blob'), Content-Disposition set
 *                                      by the server.
 */
@Injectable({ providedIn: 'root' })
export class StockReportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock/report`;

  report(): Observable<StockReportDto> {
    return this.http.get<StockReportDto>(this.base);
  }

  export(format: ExportFormat): Observable<Blob> {
    const params = new HttpParams().set('format', format);
    return this.http.get(`${this.base}/export`, { params, responseType: 'blob' });
  }
}
