import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { DashboardDto } from './models/dashboard.model';

/**
 * BI dashboard service (ADR-0037 D-7).
 * Base URL: /api/v1/bi — the interceptor auto-unwraps ApiResponse<T>.
 * Auth + X-Branch-Uid headers added by authHeaderInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/bi`;

  /**
   * Composite dashboard fetch.
   * companyId is always explicit; from/to/branchId optional (backend fills defaults).
   */
  getDashboard(
    companyId: string,
    from?: string,
    to?: string,
    branchId?: string,
  ): Observable<DashboardDto> {
    let params = new HttpParams().set('companyId', companyId);
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    if (branchId) params = params.set('branchId', branchId);
    return this.http.get<DashboardDto>(`${this.base}/dashboard`, { params });
  }

  /**
   * Export the dashboard as a blob (PDF / XLSX / CSV).
   * Gate on BI.EXPORT. responseType:'blob' bypasses the ApiResponse unwrap.
   */
  exportDashboard(
    companyId: string,
    format: 'PDF' | 'XLSX' | 'CSV' = 'PDF',
    from?: string,
    to?: string,
    branchId?: string,
  ): Observable<Blob> {
    let params = new HttpParams().set('companyId', companyId).set('format', format);
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    if (branchId) params = params.set('branchId', branchId);
    return this.http.get(`${this.base}/dashboard/export`, {
      params,
      responseType: 'blob',
    });
  }
}
