import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  PlanCatalogue,
  RecordPaymentRequest,
  SaveSubscriptionRequest,
  TenantSubscription,
} from './subscription.model';

/** Platform-operator API client for tenant subscriptions (ADR-0069). Typed to the unwrapped DTOs. */
@Injectable({ providedIn: 'root' })
export class SubscriptionService {
  private readonly http = inject(HttpClient);
  private readonly api = environment.apiBaseUrl;

  catalogue(): Observable<PlanCatalogue> {
    return this.http.get<PlanCatalogue>(`${this.api}/subscription-plans`);
  }

  get(orgUid: string): Observable<TenantSubscription> {
    return this.http.get<TenantSubscription>(`${this.api}/organisations/uid/${orgUid}/subscription`);
  }

  /** Creates the subscription when there is none ("assign plan"), otherwise updates it. */
  save(orgUid: string, request: SaveSubscriptionRequest): Observable<TenantSubscription> {
    return this.http.put<TenantSubscription>(
      `${this.api}/organisations/uid/${orgUid}/subscription`,
      request,
    );
  }

  recordPayment(orgUid: string, request: RecordPaymentRequest): Observable<TenantSubscription> {
    return this.http.post<TenantSubscription>(
      `${this.api}/organisations/uid/${orgUid}/subscription/payments`,
      request,
    );
  }
}
