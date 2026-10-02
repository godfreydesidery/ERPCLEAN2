import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ExportFormat } from './models/reporting.model';
import { PaymentSummaryFilter, PaymentSummaryReportDto } from './models/payment-summary.model';
import { SalesSummaryFilter, SalesSummaryReportDto } from './models/sales-summary.model';

/**
 * Sales Summary and Payment Summary API client.
 *
 *   GET /api/v1/reports/sales-summary[/export]     — SALES.INVOICE.VIEW (+ REPORT.EXPORT)
 *   GET /api/v1/reports/payment-summary[/export]   — POS.SESSION.VIEW (+ REPORT.EXPORT)
 *
 * Company scope comes from the JWT + X-Branch-Uid; no companyId is sent. Reads are typed to the
 * unwrapped DTO (apiResponseInterceptor strips the envelope); exports ask for a Blob. A blank filter
 * is OMITTED, never sent as an empty string — an empty uid is not "no filter".
 */
@Injectable({ providedIn: 'root' })
export class SalesSummaryReportsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/reports`;

  salesSummary(filter: SalesSummaryFilter): Observable<SalesSummaryReportDto> {
    return this.http.get<SalesSummaryReportDto>(`${this.base}/sales-summary`, {
      params: this.salesParams(filter),
    });
  }

  exportSalesSummary(filter: SalesSummaryFilter, format: ExportFormat): Observable<Blob> {
    return this.http.get(`${this.base}/sales-summary/export`, {
      params: this.salesParams(filter).set('format', format),
      responseType: 'blob',
    });
  }

  paymentSummary(filter: PaymentSummaryFilter): Observable<PaymentSummaryReportDto> {
    return this.http.get<PaymentSummaryReportDto>(`${this.base}/payment-summary`, {
      params: this.paymentParams(filter),
    });
  }

  exportPaymentSummary(filter: PaymentSummaryFilter, format: ExportFormat): Observable<Blob> {
    return this.http.get(`${this.base}/payment-summary/export`, {
      params: this.paymentParams(filter).set('format', format),
      responseType: 'blob',
    });
  }

  private salesParams(f: SalesSummaryFilter): HttpParams {
    let p = new HttpParams().set('fromDate', f.fromDate).set('toDate', f.toDate).set('groupBy', f.groupBy);
    if (f.branchUid) p = p.set('branchUid', f.branchUid);
    return p;
  }

  private paymentParams(f: PaymentSummaryFilter): HttpParams {
    let p = new HttpParams().set('fromDate', f.fromDate).set('toDate', f.toDate);
    if (f.branchUid) p = p.set('branchUid', f.branchUid);
    if (f.cashierUid) p = p.set('cashierUid', f.cashierUid);
    return p;
  }
}
