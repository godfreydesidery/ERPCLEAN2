import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Organisation } from '../models/organisation.model';
import { CreateTenantRequest, TenantSummary } from './tenant.model';

/** Platform-operator API client for tenant organisations. Typed to the unwrapped DTOs. */
@Injectable({ providedIn: 'root' })
export class TenantService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/organisations`;

  list(): Observable<TenantSummary[]> {
    return this.http.get<TenantSummary[]>(`${this.base}/tenants`);
  }

  create(request: CreateTenantRequest): Observable<Organisation> {
    return this.http.post<Organisation>(this.base, request);
  }

  suspend(uid: string): Observable<Organisation> {
    return this.http.post<Organisation>(`${this.base}/uid/${uid}/suspend`, {});
  }

  resume(uid: string): Observable<Organisation> {
    return this.http.post<Organisation>(`${this.base}/uid/${uid}/resume`, {});
  }
}
