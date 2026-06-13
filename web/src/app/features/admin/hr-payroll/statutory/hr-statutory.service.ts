import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import {
  CreatePayeBandSetRequest,
  CreateStatutoryRateSetRequest,
  PayeBandSetDto,
  StatutoryRateSetDto,
} from '../models/hr-payroll.model';

/**
 * HR Statutory API client.
 * Both list endpoints are NOT paginated — return raw arrays via auto-unwrap.
 */
@Injectable({ providedIn: 'root' })
export class HrStatutoryService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/hr/statutory`;

  // ── PAYE Band Sets ─────────────────────────────────────────────────────────

  listPayeBandSets(companyId: string): Observable<PayeBandSetDto[]> {
    const params = new HttpParams().set('companyId', companyId);
    return this.http.get<PayeBandSetDto[]>(`${this.base}/paye-bands`, { params });
  }

  createPayeBandSet(req: CreatePayeBandSetRequest): Observable<PayeBandSetDto> {
    return this.http.post<PayeBandSetDto>(`${this.base}/paye-bands`, req);
  }

  getPayeBandSetByUid(uid: string): Observable<PayeBandSetDto> {
    return this.http.get<PayeBandSetDto>(`${this.base}/paye-bands/uid/${uid}`);
  }

  // ── Statutory Rate Sets ────────────────────────────────────────────────────

  listRateSets(companyId: string): Observable<StatutoryRateSetDto[]> {
    const params = new HttpParams().set('companyId', companyId);
    return this.http.get<StatutoryRateSetDto[]>(`${this.base}/rates`, { params });
  }

  createRateSet(req: CreateStatutoryRateSetRequest): Observable<StatutoryRateSetDto> {
    return this.http.post<StatutoryRateSetDto>(`${this.base}/rates`, req);
  }

  getRateSetByUid(uid: string): Observable<StatutoryRateSetDto> {
    return this.http.get<StatutoryRateSetDto>(`${this.base}/rates/uid/${uid}`);
  }
}
