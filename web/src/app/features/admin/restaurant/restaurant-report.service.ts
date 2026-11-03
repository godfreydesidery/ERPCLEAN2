import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ExportFormat } from '../reporting/models/reporting.model';
import {
  RestaurantBillsReportDto,
  RestaurantBillsSummaryDto,
  RestaurantDashboardDto,
  RestaurantDashboardFilter,
  RestaurantDaypartDto,
  RestaurantPaymentsReportDto,
  RestaurantPendingBillsReportDto,
  RestaurantPendingSummaryDto,
  RestaurantReportFilter,
  RestaurantReportKind,
  RestaurantWaiterDto,
  WaiterItemsReportDto,
  WaiterSalesReportDto,
} from './models/restaurant-report.model';

/** Reports that are "now", with no date range. */
const NO_DATES: readonly RestaurantReportKind[] = ['pending-bills', 'pending-summary'];
/** Reports over the whole floor, with no waiter filter. */
const NO_WAITER: readonly RestaurantReportKind[] = ['daypart', 'payments'];

/**
 * Restaurant reports (J COB 2026-10-30) and the restaurant dashboard (2026-11-03) —
 * `/reports/restaurant/*`, gated like the Sales Report: SALES.INVOICE.VIEW on screen, plus
 * REPORT.EXPORT to download. Each report kind is also its endpoint's path segment.
 */
@Injectable({ providedIn: 'root' })
export class RestaurantReportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/reports/restaurant`;

  waiterSales(filter: RestaurantReportFilter): Observable<WaiterSalesReportDto> {
    return this.get<WaiterSalesReportDto>('waiter-sales', filter);
  }

  waiterItems(filter: RestaurantReportFilter): Observable<WaiterItemsReportDto> {
    return this.get<WaiterItemsReportDto>('waiter-items', filter);
  }

  bills(filter: RestaurantReportFilter): Observable<RestaurantBillsReportDto> {
    return this.get<RestaurantBillsReportDto>('bills', filter);
  }

  billsSummary(filter: RestaurantReportFilter): Observable<RestaurantBillsSummaryDto> {
    return this.get<RestaurantBillsSummaryDto>('bills-summary', filter);
  }

  pendingBills(filter: RestaurantReportFilter): Observable<RestaurantPendingBillsReportDto> {
    return this.get<RestaurantPendingBillsReportDto>('pending-bills', filter);
  }

  pendingSummary(filter: RestaurantReportFilter): Observable<RestaurantPendingSummaryDto> {
    return this.get<RestaurantPendingSummaryDto>('pending-summary', filter);
  }

  daypart(filter: RestaurantReportFilter): Observable<RestaurantDaypartDto> {
    return this.get<RestaurantDaypartDto>('daypart', filter);
  }

  payments(filter: RestaurantReportFilter): Observable<RestaurantPaymentsReportDto> {
    return this.get<RestaurantPaymentsReportDto>('payments', filter);
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

  dashboard(filter: RestaurantDashboardFilter): Observable<RestaurantDashboardDto> {
    return this.http.get<RestaurantDashboardDto>(`${this.base}/dashboard`, {
      params: this.dashboardParams(filter),
    });
  }

  /** The dashboard as the printable Flash Report. */
  exportFlashReport(filter: RestaurantDashboardFilter, format: ExportFormat): Observable<Blob> {
    return this.http.get(`${this.base}/dashboard/export`, {
      params: this.dashboardParams(filter).set('format', format),
      responseType: 'blob',
    });
  }

  /** The branch's waiters, for the waiter filter (needs a restaurant permission). */
  waiters(): Observable<RestaurantWaiterDto[]> {
    return this.http.get<RestaurantWaiterDto[]>(`${environment.apiBaseUrl}/restaurant/waiters`);
  }

  private get<T>(kind: RestaurantReportKind, filter: RestaurantReportFilter): Observable<T> {
    return this.http.get<T>(`${this.base}/${kind}`, { params: this.params(kind, filter) });
  }

  private params(kind: RestaurantReportKind, filter: RestaurantReportFilter): HttpParams {
    let params = new HttpParams();
    if (!NO_DATES.includes(kind)) {
      params = params.set('fromDate', filter.fromDate).set('toDate', filter.toDate);
    }
    if (filter.waiterUid && !NO_WAITER.includes(kind)) {
      params = params.set('waiterUid', filter.waiterUid);
    }
    if (filter.branchUid) params = params.set('branchUid', filter.branchUid);
    return params;
  }

  private dashboardParams(filter: RestaurantDashboardFilter): HttpParams {
    let params = new HttpParams().set('fromDate', filter.fromDate).set('toDate', filter.toDate);
    if (filter.branchUid) params = params.set('branchUid', filter.branchUid);
    return params;
  }
}
