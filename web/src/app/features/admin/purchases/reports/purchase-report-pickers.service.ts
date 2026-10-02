import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, switchMap } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { UidOption } from '../../../../shared/uid-picker/uid-picker.component';
import { BranchService } from '../../branch/branch.service';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PRODUCT_PICKER_SEARCH_SIZE, ProductService } from '../../products/product.service';
import { SupplierService } from '../../parties/supplier.service';

/** How many suppliers/products seed a picker before the user types. Everything else is a search. */
const SEED_SIZE = 20;

/**
 * Filter-picker options shared by the four purchase report screens.
 *
 * <ul>
 *   <li><b>Branches</b> — only the branches the caller may actually filter to: their own
 *       assignments, because the server refuses any other branch. Root forks to the full company
 *       list, since the server exempts root from the assignment check.</li>
 *   <li><b>Suppliers / products</b> — a short seed plus a server-side search. Preloading a fixed
 *       number and filtering in memory would make everything past it unfindable.</li>
 * </ul>
 * Every lookup fails soft to an empty list: a missing picker leaves the report runnable for "all".
 */
@Injectable({ providedIn: 'root' })
export class PurchaseReportPickersService {
  private readonly organisationService = inject(OrganisationService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly supplierService = inject(SupplierService);
  private readonly productService = inject(ProductService);
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionStore);

  /**
   * The caller's first accessible company — the same convention as the other report screens.
   * Cached per signed-in user — this is a root singleton and outlives a sign-out, so a cache
   * keyed on nothing could hand the next user the previous user's company — and only once it has
   * resolved, so a failed lookup is retried rather than remembered.
   */
  private cached: { userKey: string; company: { id: string; uid: string } } | null = null;

  private company(): Observable<{ id: string; uid: string } | null> {
    const userKey = String(this.session.user()?.uid ?? '');
    if (this.cached && this.cached.userKey === userKey) {
      return of(this.cached.company);
    }
    return this.organisationService.current().pipe(
      switchMap((org) => this.companyService.list(org.uid)),
      map((list) => {
        if (list.length === 0) return null;
        const company = { id: list[0].id, uid: list[0].uid };
        this.cached = { userKey, company };
        return company;
      }),
      catchError(() => of(null)),
    );
  }

  branchOptions(): Observable<UidOption[]> {
    return this.company().pipe(
      switchMap((company) => {
        if (!company) return of([] as UidOption[]);
        if (this.session.user()?.isRoot === true) {
          return this.branchService.list(company.uid).pipe(
            map((branches) =>
              branches
                .filter((b) => b.status === 'ACTIVE')
                .map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
            ),
          );
        }
        return this.auth.myBranches().pipe(
          map((branches) =>
            branches
              .filter((b) => b.companyUid === company.uid)
              .map((b) => ({ uid: b.branchUid, label: b.branchName, hint: b.branchCode })),
          ),
        );
      }),
      catchError(() => of([] as UidOption[])),
    );
  }

  supplierSeed(): Observable<UidOption[]> {
    return this.suppliers('');
  }

  /** Arrow-safe server search for `[search]`. */
  readonly searchSuppliers = (q: string): Observable<readonly UidOption[]> => this.suppliers(q);

  productSeed(): Observable<UidOption[]> {
    return this.products('', SEED_SIZE);
  }

  readonly searchProducts = (q: string): Observable<readonly UidOption[]> =>
    this.products(q, PRODUCT_PICKER_SEARCH_SIZE);

  // ---------------------------------------------------------------------------

  private suppliers(q: string): Observable<UidOption[]> {
    return this.company().pipe(
      switchMap((company) =>
        company ? this.supplierService.list(company.id, q, 0, SEED_SIZE) : of({ rows: [] }),
      ),
      map(({ rows }) =>
        rows
          .filter((s) => s.status !== 'ARCHIVED')
          .map((s) => ({ uid: s.uid, label: s.displayName, hint: s.code })),
      ),
      catchError(() => of([] as UidOption[])),
    );
  }

  private products(q: string, size: number): Observable<UidOption[]> {
    return this.company().pipe(
      switchMap((company) =>
        company ? this.productService.list(company.id, q, 0, size) : of({ rows: [] }),
      ),
      map(({ rows }) =>
        rows
          .filter((p) => p.status !== 'ARCHIVED')
          .map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
      ),
      catchError(() => of([] as UidOption[])),
    );
  }
}
