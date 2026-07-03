import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import { SalesSettingsDto, UpdateSalesSettingsRequest } from '../../models/sales.model';

/**
 * Sales Settings API client (D-4: SO approval threshold).
 * getByCompany: GET /api/v1/sales-settings/by-company/{companyUid}
 * update:       PUT /api/v1/sales-settings
 */
@Injectable({ providedIn: 'root' })
export class SalesSettingsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/sales-settings`;

  getByCompany(companyUid: string): Observable<SalesSettingsDto> {
    return this.http.get<SalesSettingsDto>(`${this.base}/by-company/${companyUid}`);
  }

  update(request: UpdateSalesSettingsRequest): Observable<SalesSettingsDto> {
    return this.http.put<SalesSettingsDto>(this.base, request);
  }
}
