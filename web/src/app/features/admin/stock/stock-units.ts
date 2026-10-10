import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, shareReplay } from 'rxjs';
import { ProductService } from '../products/product.service';

/**
 * One unit a stock quantity may be typed in (STK-08 / OPN-01): the product's base unit or one of
 * its pack sizes. `unitUid` is '' for the base unit — the stock endpoints read an absent unit as
 * "base", so the base option sends nothing and every caller keeps its old meaning.
 */
export interface StockUnitOption {
  unitUid: string;
  code: string;
  name: string;
  /** How many base units one of this unit is (1 for the base unit). */
  factor: number;
}

/**
 * Loads a product's stock units — base first, then its bulk packs with their factors — for the
 * adjust, opening-balance and count screens. Cached per product for the life of the app: pack
 * sizes change rarely, and a count sheet asks for the same product many times.
 */
@Injectable({ providedIn: 'root' })
export class StockUnitOptionsService {
  private readonly productService = inject(ProductService);
  private readonly cache = new Map<string, Observable<StockUnitOption[]>>();

  load(productUid: string): Observable<StockUnitOption[]> {
    const hit = this.cache.get(productUid);
    if (hit) return hit;
    const req$ = forkJoin({
      units: this.productService.listProductUnits(productUid).pipe(catchError(() => of([]))),
      packs: this.productService.listBulkPacks(productUid).pipe(catchError(() => of([]))),
    }).pipe(
      map(({ units, packs }) => {
        // The units endpoint lists the base unit first.
        const base = units[0];
        const options: StockUnitOption[] = [
          { unitUid: '', code: base?.code ?? '', name: base?.name ?? 'Base unit', factor: 1 },
        ];
        for (const p of packs) {
          const f = Number(p.factorToBase);
          if (!p.unitUid || !Number.isFinite(f) || f <= 0 || p.unitUid === base?.uid) continue;
          options.push({ unitUid: p.unitUid, code: p.unitCode, name: p.unitName, factor: f });
        }
        return options;
      }),
      shareReplay(1),
    );
    this.cache.set(productUid, req$);
    return req$;
  }
}

/** The factor of `unitUid` among `options` (1 when absent / base). */
export function unitFactor(options: StockUnitOption[], unitUid: string): number {
  return options.find((o) => o.unitUid === unitUid)?.factor ?? 1;
}

/** Rounds away binary-float noise (2.1 × 12 = 25.200000000000003) to 6 decimals. */
export function toBaseQty(qty: number, factor: number): number {
  return Math.round(qty * factor * 1e6) / 1e6;
}

/**
 * "4 CTN + 7 PCS" for a base quantity, using the largest pack size. Empty when the product has no
 * pack, the quantity is not a whole number of base units, or it is smaller than one pack — in those
 * cases the plain base figure already says it best.
 */
export function packBreakdown(baseQty: number | string | null | undefined, options: StockUnitOption[]): string {
  const q = Number(baseQty);
  if (!Number.isFinite(q) || q === 0 || options.length < 2 || !Number.isInteger(q)) return '';
  const base = options[0];
  const pack = options.slice(1).reduce((a, b) => (b.factor > a.factor ? b : a));
  if (!Number.isInteger(pack.factor) || Math.abs(q) < pack.factor) return '';
  const sign = q < 0 ? '−' : '';
  const abs = Math.abs(q);
  const packs = Math.floor(abs / pack.factor);
  const rest = abs - packs * pack.factor;
  const packLabel = pack.code || pack.name;
  const baseLabel = base.code || base.name;
  return rest === 0
    ? `${sign}${packs} ${packLabel}`
    : `${sign}${packs} ${packLabel} + ${rest} ${baseLabel}`;
}
