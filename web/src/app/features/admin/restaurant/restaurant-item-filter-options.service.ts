import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, switchMap } from 'rxjs';
import { UidOption } from '../../../shared/uid-picker/uid-picker.component';
import { Company } from '../models/company.model';
import { ProductModel } from '../models/product.model';
import { PRODUCT_PICKER_SEARCH_SIZE, ProductService } from '../products/product.service';

/** The department of an item with no category — what the reports show, and what the API matches. */
export const NO_DEPARTMENT = '(no department)';

/** The options behind the restaurant reports' Item and Department filters. */
export interface RestaurantItemFilterOptions {
  /** The company the item search runs against ('' when none could be loaded). */
  companyId: string;
  /** A first page of items to seed the picker; typing searches the server for the rest. */
  items: UidOption[];
  /** The departments (product categories) in use, then "(no department)". */
  departments: UidOption[];
}

const EMPTY: RestaurantItemFilterOptions = { companyId: '', items: [], departments: [] };

/**
 * Item and department filter options for the restaurant reports and dashboard (2026-11-21).
 *
 * The product list and its categories are company reference data any member of the company may
 * read (`/products`, `/products/categories` — PRODUCT.VIEW or company membership), so a user who
 * can run the reports can always fill these pickers; no new permission. A department option's value
 * is the category text itself — the API matches it trimmed and case-insensitively, the way the
 * reports group it. Every failure is non-fatal: an empty picker just means "all".
 */
@Injectable({ providedIn: 'root' })
export class RestaurantItemFilterOptionsService {
  private readonly products = inject(ProductService);

  options(company$: Observable<Company | null>): Observable<RestaurantItemFilterOptions> {
    return company$.pipe(
      switchMap((company) => {
        if (!company) return of(EMPTY);
        return forkJoin({
          items: this.products.list(company.id, '', 0, 200).pipe(
            map(({ rows }) => toItemOptions(rows)),
            catchError(() => of<UidOption[]>([])),
          ),
          departments: this.products.listCategories(company.id).pipe(
            catchError(() => of<string[]>([])),
            map(toDepartmentOptions),
          ),
        }).pipe(map((o) => ({ companyId: company.id, ...o })));
      }),
      catchError(() => of(EMPTY)),
    );
  }

  /** Server-side item search for the picker; archived items are left out. */
  search(companyId: string, q: string): Observable<readonly UidOption[]> {
    if (!companyId) return of([]);
    return this.products.list(companyId, q, 0, PRODUCT_PICKER_SEARCH_SIZE).pipe(
      map(({ rows }) => toItemOptions(rows)),
      catchError(() => of<UidOption[]>([])),
    );
  }
}

function toItemOptions(rows: readonly ProductModel[]): UidOption[] {
  return rows
    .filter((p) => p.status !== 'ARCHIVED')
    .map((p) => ({ uid: p.uid, label: p.name, hint: p.code }));
}

function toDepartmentOptions(categories: readonly string[]): UidOption[] {
  const seen = new Set<string>([NO_DEPARTMENT]);
  const out: UidOption[] = [];
  for (const c of categories) {
    const name = (c ?? '').trim();
    const key = name.toLowerCase();
    if (!name || seen.has(key)) continue;
    seen.add(key);
    out.push({ uid: name, label: name });
  }
  out.push({ uid: NO_DEPARTMENT, label: NO_DEPARTMENT });
  return out;
}
