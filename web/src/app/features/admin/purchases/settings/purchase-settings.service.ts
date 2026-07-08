import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import {
  PurchaseSettingsDto,
  UpdatePurchaseSettingsRequest,
} from '../../models/purchases.model';

/**
 * Purchase Settings API client.
 * getByCompany: GET /api/v1/purchase-settings/by-company/{companyUid}
 * update:       PUT /api/v1/purchase-settings
 */
@Injectable({ providedIn: 'root' })
export class PurchaseSettingsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/purchase-settings`;

  getByCompany(companyUid: string): Observable<PurchaseSettingsDto> {
    return this.http.get<PurchaseSettingsDto>(`${this.base}/by-company/${companyUid}`);
  }

  update(request: UpdatePurchaseSettingsRequest): Observable<PurchaseSettingsDto> {
    // request already carries receiptTolerancePct (number | null) — see UpdatePurchaseSettingsRequest.
    return this.http.put<PurchaseSettingsDto>(this.base, request);
  }
}
