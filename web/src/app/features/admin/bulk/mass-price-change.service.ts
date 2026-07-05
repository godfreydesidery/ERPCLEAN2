import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { MassPriceChangeRequest, MassPriceChangeResult } from './mass-price-change.model';

/**
 * Mass price-change API client. One endpoint serves both preview (dryRun:true, nothing written)
 * and apply (dryRun:false) — the caller decides which by the request flag.
 */
@Injectable({ providedIn: 'root' })
export class MassPriceChangeService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/prices/mass-change`;

  apply(request: MassPriceChangeRequest): Observable<MassPriceChangeResult> {
    return this.http.post<MassPriceChangeResult>(this.base, request);
  }
}
