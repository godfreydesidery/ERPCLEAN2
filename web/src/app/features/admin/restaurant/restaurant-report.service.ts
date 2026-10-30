import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ExportFormat } from '../reporting/models/reporting.model';
import {
  RestaurantBillsReportDto,
  RestaurantPendingBillsReportDto,
  RestaurantReportFilter,
  RestaurantReportKind,
  RestaurantWaiterDto,
  WaiterSalesReportDto,
} from './models/restaurant-report.model';

/**
 * Restaurant reports per waiter (J COB 2026-10-30) — `/reports/restaurant/*`, gated like the Sales
 * Report: SALES.INVOICE.VIEW on screen, plus REPORT.EXPORT to download.
 */
@Injectable({ providedIn: 'root' })
export class RestaurantReportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/reports/restaurant`;

  waiterSales(filter: RestaurantReportFilter): Observable<WaiterSalesReportDto> {
    return this.http.get<WaiterSalesReportDto>(`${this.base}/waiter-sales`, {
      params: this.params('waiter-sales', filter),
    });
  }

  bills(filter: RestaurantReportFilter): Observable<RestaurantBillsReportDto> {
    return this.http.get<RestaurantBillsReportDto>(`${this.base}/bills`, {
      params: this.params('bills', filter),
    });
  }

  pendingBills(filter: RestaurantReportFilter): Observable<RestaurantPendingBillsReportDto> {
    return this.http.get<RestaurantPendingBillsReportDto>(`${this.base}/pending-bills`, {
      params: this.params('pending-bills', filter),
    });
  }

  export(
    kind: RestaurantReportKind,
    filter: RestaurantReportFilter,
    format: ExportFormat,
  ): Observable<Blob> {
    return this.http.get(`${this.base}/${kind}/export`, {
      params: this.params(kind, filter).set('format', format),
      responseType: 'blob',
    });
  }

  /** The branch's waiters, for the waiter filter (needs a restaurant permission). */
  waiters(): Observable<RestaurantWaiterDto[]> {
    return this.http.get<RestaurantWaiterDto[]>(`${environment.apiBaseUrl}/restaurant/waiters`);
  }

  private params(kind: RestaurantReportKind, filter: RestaurantReportFilter): HttpParams {
    let params = new HttpParams();
    if (kind !== 'pending-bills') {
      params = params.set('fromDate', filter.fromDate).set('toDate', filter.toDate);
    }
    if (filter.waiterUid) params = params.set('waiterUid', filter.waiterUid);
    if (filter.branchUid) params = params.set('branchUid', filter.branchUid);
    return params;
  }
}
