import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export type LocationType = 'WAREHOUSE' | 'STORE' | 'VAN' | 'QUARANTINE' | 'OTHER';

export interface StockLocationDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  code: string;
  name: string;
  locationType: LocationType;
  isDefault: boolean;
  status: string;
}

/**
 * Stock Locations API client — mirrors /api/v1/stock-locations.
 * activeForBranch() returns an unwrapped list (no envelope); uses auto-unwrap interceptor.
 */
@Injectable({ providedIn: 'root' })
export class StockLocationService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock-locations`;

  /** Returns all ACTIVE locations for the given branch uid. */
  activeForBranch(branchUid: string): Observable<StockLocationDto[]> {
    return this.http.get<StockLocationDto[]>(`${this.base}/active`, { params: { branchUid } });
  }
}
