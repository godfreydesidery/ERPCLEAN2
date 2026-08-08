import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ExportFormat } from '../reporting/models/reporting.model';
import {
  StockMovementReportDto,
  StockMovementReportFilter,
} from './models/stock-movement-report.model';

/**
 * Period Stock Movement report API client (K9).
 *
 *   GET /api/v1/reports/stock-movement         → StockMovementReportDto (one page)
 *   GET /api/v1/reports/stock-movement/export  → raw PDF/XLSX/CSV bytes (responseType:'blob')
 *
 * Company and branch scope come from the JWT + X-Branch-Uid — no companyId is ever sent. The
 * optional `branchUid` filter narrows WITHIN the caller's company; omitted means every branch.
 * Gated INVENTORY.VALUATION.VIEW to read and REPORT.EXPORT to download (two distinct permissions).
 */
@Injectable({ providedIn: 'root' })
export class StockMovementReportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/reports/stock-movement`;

  report(filter: StockMovementReportFilter, page = 0, size = 50): Observable<StockMovementReportDto> {
    const params = this.paramsFor(filter)
      .set('page', String(page))
      .set('size', String(size));
    return this.http.get<StockMovementReportDto>(this.base, { params });
  }

  /** The whole report (not page-limited) as a downloadable file. */
  export(filter: StockMovementReportFilter, format: ExportFormat): Observable<Blob> {
    const params = this.paramsFor(filter).set('format', format);
    return this.http.get(`${this.base}/export`, { params, responseType: 'blob' });
  }

  private paramsFor(filter: StockMovementReportFilter): HttpParams {
    let params = new HttpParams()
      .set('fromDate', filter.fromDate)
      .set('toDate', filter.toDate)
      .set('mode', filter.mode);
    if (filter.branchUid) params = params.set('branchUid', filter.branchUid);
    if (filter.productUid) params = params.set('productUid', filter.productUid);
    return params;
  }
}
