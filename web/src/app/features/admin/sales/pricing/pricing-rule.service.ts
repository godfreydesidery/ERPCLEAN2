import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import {
  CreateCustomerPriceRequest,
  CreatePriceTierRequest,
  CustomerPriceDto,
  PriceTierDto,
} from './pricing-rule.model';

/**
 * Pricing-rule API client (ADR-0029 D-6).
 * All list methods return plain arrays (the backend returns a plain List, not a Page,
 * so the interceptor auto-unwraps to T[] — no SKIP_UNWRAP needed).
 * Base: /api/v1/pricing-rules
 */
@Injectable({ providedIn: 'root' })
export class PricingRuleService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/pricing-rules`;

  // ── Price Tiers ──────────────────────────────────────────────────────────────

  createTier(request: CreatePriceTierRequest): Observable<PriceTierDto> {
    return this.http.post<PriceTierDto>(`${this.base}/tiers`, request);
  }

  getTierByUid(uid: string): Observable<PriceTierDto> {
    return this.http.get<PriceTierDto>(`${this.base}/tiers/uid/${uid}`);
  }

  /**
   * List tiers for a product within a price list.
   * Backend signature: GET /tiers?companyId=&productId=&priceListId=
   */
  listTiers(companyId: string, productId: string, priceListId: string): Observable<PriceTierDto[]> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('productId', productId)
      .set('priceListId', priceListId);
    return this.http.get<PriceTierDto[]>(`${this.base}/tiers`, { params });
  }

  deactivateTier(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/tiers/uid/${uid}`);
  }

  // ── Customer Prices ──────────────────────────────────────────────────────────

  createCustomerPrice(request: CreateCustomerPriceRequest): Observable<CustomerPriceDto> {
    return this.http.post<CustomerPriceDto>(`${this.base}/customer-prices`, request);
  }

  getCustomerPriceByUid(uid: string): Observable<CustomerPriceDto> {
    return this.http.get<CustomerPriceDto>(`${this.base}/customer-prices/uid/${uid}`);
  }

  /**
   * List customer-specific prices for a customer.
   * Backend signature: GET /customer-prices?companyId=&customerId=
   */
  listCustomerPrices(companyId: string, customerId: string): Observable<CustomerPriceDto[]> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('customerId', customerId);
    return this.http.get<CustomerPriceDto[]>(`${this.base}/customer-prices`, { params });
  }

  deactivateCustomerPrice(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/customer-prices/uid/${uid}`);
  }
}
