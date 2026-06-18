import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of, shareReplay } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CurrencyDto } from '../../features/admin/fx/models/fx.model';

/**
 * Wire shape for GET /fx/currencies/enabled (ADR-0039 D-8).
 * Mirrors EnabledCurrenciesDto.
 */
export interface EnabledCurrenciesDto {
  /** Resolved default document currency code for this scope. */
  resolvedDefault: string;
  /** Allowed currency codes, alphabetical order. */
  enabled: string[];
}

/**
 * A currency entry with both code and display name, ready to show in the picker.
 */
export interface CurrencyOption {
  code: string;
  name: string;
  symbol: string;
}

/**
 * Currency service — wraps the FX enablement + currency-master calls (ADR-0039 T3).
 * Provided in root so the picker can be dropped into any form without extra setup.
 *
 * Two calls are made:
 *  1. GET /fx/currencies/enabled?companyUid=... — returns the company's allow-list + resolved default.
 *  2. GET /fx/currencies — full currency master (code → name/symbol) for display labels.
 *
 * The master is cached app-wide (shareReplay(1)); the enabled list is cached per companyUid
 * (same Map entry lives for the component lifetime and re-fetches when company changes).
 *
 * The interceptor auto-unwraps ApiResponse<T> so both calls emit the raw DTO.
 */
@Injectable({ providedIn: 'root' })
export class CurrencyService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/fx`;

  /** App-level cache for the full currency master. */
  private masterCache$: Observable<CurrencyDto[]> | null = null;

  /** Per-companyUid cache for the enabled list. */
  private readonly enabledCache = new Map<string, Observable<EnabledCurrenciesDto>>();

  /**
   * Returns the enabled currency list + resolved default for the company (optionally branch).
   * Caches per companyUid key (branchUid included if provided so they stay separate).
   */
  getEnabled(companyUid: string, branchUid?: string | null): Observable<EnabledCurrenciesDto> {
    const cacheKey = branchUid ? `${companyUid}:${branchUid}` : companyUid;
    let cached = this.enabledCache.get(cacheKey);
    if (!cached) {
      let params = new HttpParams().set('companyUid', companyUid);
      if (branchUid) params = params.set('branchUid', branchUid);
      cached = this.http
        .get<EnabledCurrenciesDto>(`${this.base}/currencies/enabled`, { params })
        .pipe(shareReplay(1));
      this.enabledCache.set(cacheKey, cached);
    }
    return cached;
  }

  /**
   * Full currency master (all active currencies). Cached app-wide.
   */
  listAll(): Observable<CurrencyDto[]> {
    if (!this.masterCache$) {
      this.masterCache$ = this.http
        .get<CurrencyDto[]>(`${this.base}/currencies`)
        .pipe(shareReplay(1));
    }
    return this.masterCache$;
  }

  /**
   * Combines the enabled list with the master to produce labelled CurrencyOption[] for the picker.
   * Falls back gracefully: if master fails or a code is missing, the code itself is shown as label.
   */
  getEnabledOptions(
    companyUid: string,
    branchUid?: string | null,
  ): Observable<{ options: CurrencyOption[]; resolvedDefault: string }> {
    if (!companyUid) {
      return of({ options: [], resolvedDefault: '' });
    }
    return new Observable((subscriber) => {
      let master: CurrencyDto[] = [];
      let enabled: EnabledCurrenciesDto | null = null;
      let masterDone = false;
      let enabledDone = false;

      const tryEmit = (): void => {
        if (!masterDone || !enabledDone || !enabled) return;
        const codeMap = new Map<string, CurrencyDto>(master.map((c) => [c.code, c]));
        const options: CurrencyOption[] = enabled.enabled.map((code) => {
          const dto = codeMap.get(code);
          return { code, name: dto?.name ?? code, symbol: dto?.symbol ?? '' };
        });
        subscriber.next({ options, resolvedDefault: enabled.resolvedDefault });
        subscriber.complete();
      };

      const masterSub = this.listAll().subscribe({
        next: (list) => {
          master = list;
          masterDone = true;
          tryEmit();
        },
        error: () => {
          master = [];
          masterDone = true;
          tryEmit();
        },
      });

      const enabledSub = this.getEnabled(companyUid, branchUid).subscribe({
        next: (dto) => {
          enabled = dto;
          enabledDone = true;
          tryEmit();
        },
        error: () => {
          // If enablement fails, emit empty so the picker shows an error state.
          subscriber.error(new Error('Could not load enabled currencies'));
        },
      });

      return () => {
        masterSub.unsubscribe();
        enabledSub.unsubscribe();
      };
    });
  }

  /** Clears the enablement cache (call when company settings are changed). */
  clearCache(): void {
    this.enabledCache.clear();
    this.masterCache$ = null;
  }
}
