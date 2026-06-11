import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  OpeningValuationResultDto,
  SetOpeningValuationRequest,
  StockValuationReportDto,
} from './models/inventory-valuation.model';

/**
 * Inventory Valuation API client (ADR-0020, FR-INV-06/07).
 *
 * The backend auto-wraps responses in ApiResponse<T> via ApiResponseAdvice.
 * The HTTP interceptor unwraps the envelope — feature code receives T directly.
 *
 * Endpoints:
 *   GET  /api/v1/stock/valuation/report   → StockValuationReportDto  (INVENTORY.VALUATION.VIEW)
 *   POST /api/v1/stock/valuation/opening  → OpeningValuationResultDto (INVENTORY.OPENING.SET)
 */
@Injectable({ providedIn: 'root' })
export class InventoryValuationService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock/valuation`;

  /**
   * Fetch the stock valuation report for the caller's company context.
   * The backend derives companyId from the JWT principal — no param needed.
   * Optional `asOf` is reserved for future point-in-time queries; currently ignored.
   */
  report(): Observable<StockValuationReportDto> {
    return this.http.get<StockValuationReportDto>(`${this.base}/report`);
  }

  /**
   * Set the one-time opening unit cost for an on-hand row that has quantity but no cost.
   * Rejected by the backend if the row is already valued (avg_cost IS NOT NULL).
   */
  setOpening(request: SetOpeningValuationRequest): Observable<OpeningValuationResultDto> {
    return this.http.post<OpeningValuationResultDto>(`${this.base}/opening`, request);
  }
}
