import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  ItemInquiryDto,
  ItemInquiryRowDto,
  formatInquiryAmount,
  formatInquiryQty,
} from './item-inquiry.model';
import { StockService } from './stock.service';

type LoadState = 'idle' | 'loading' | 'error';

/**
 * Item Inquiry — one search box that answers "what is this item, what does it cost us, what do we
 * sell it for, and how many are left" (K-2026-08-30 #3).
 *
 * Every field here already existed, spread across three screens: the Product Master holds the cost
 * and the price lists but no stock, the on-hand list holds quantity and average cost but no selling
 * price, and the Product List report holds all of it but is a whole-catalogue register behind a
 * valuation permission. The person serving a customer needs one answer about one item, now.
 *
 * Search is SERVER-side and matches code, description or a full scanned barcode. It is never
 * seeded-and-filtered-in-memory: preloading N options and filtering client-side hides everything
 * past N, which is exactly how the product pickers came to be reported as "missing products".
 *
 * Cost is shown only to callers holding INVENTORY.VALUATION.VIEW. The response says whether it was
 * withheld (`costVisible`), so a hidden cost is never rendered as "this item has no cost" — the
 * screen says "hidden" and means it.
 *
 * Route: /admin/stock/item-inquiry. Guard = PRODUCT.VIEW AND STOCK.VIEW, identical to the endpoint's
 * @PreAuthorize.
 */
@Component({
  selector: 'app-item-inquiry',
  imports: [FormsModule, RouterLink, UidPickerComponent],
  templateUrl: './item-inquiry.component.html',
  styleUrl: './item-inquiry.component.scss',
})
export class ItemInquiryComponent {
  private readonly stockService = inject(StockService);
  private readonly organisationService = inject(OrganisationService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly auth = inject(AuthService);
  protected readonly session = inject(SessionStore);

  protected readonly fmtAmount = formatInquiryAmount;
  protected readonly fmtQty = formatInquiryQty;

  // ── Search ─────────────────────────────────────────────────────────────────
  readonly searchQ = signal('');
  readonly branchUid = signal('');
  readonly branchOptions = signal<UidOption[]>([]);

  readonly result = signal<ItemInquiryDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly loadError = signal<string | null>(null);

  private readonly search$ = new Subject<string>();

  readonly canView = computed(
    () => this.session.hasPermission('PRODUCT.VIEW') && this.session.hasPermission('STOCK.VIEW'),
  );

  /** True once a search has actually run — an empty result then means "nothing matched". */
  readonly hasSearched = computed(() => this.result() !== null);
  readonly rows = computed<ItemInquiryRowDto[]>(() => this.result()?.rows ?? []);
  readonly noMatches = computed(
    () => this.state() === 'idle' && this.hasSearched() && this.rows().length === 0,
  );

  /** Mirrors the backend's column head so screen and report cannot describe the price differently. */
  readonly sellingHeader = computed(() => {
    const r = this.result();
    if (!r || r.priceListName === null) return 'Selling price';
    return r.priceIncludesVat ? 'Selling price (VAT incl.)' : 'Selling price (excl. VAT)';
  });

  /** No default price list set: every selling price will be blank, and the screen must say why. */
  readonly noPriceList = computed(() => {
    const r = this.result();
    return r !== null && r.priceListName === null;
  });

  readonly branchLabel = computed(() => this.result()?.branchName ?? 'All branches');

  constructor() {
    this.search$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const term = q.trim();
          if (!term) {
            this.result.set(null);
            this.state.set('idle');
            return [];
          }
          this.state.set('loading');
          this.loadError.set(null);
          // catchError INSIDE the switchMap: an error escaping to the outer subscribe would
          // terminate the stream, leaving the search box permanently dead after one failure.
          return this.stockService.itemInquiry(term, this.branchUid() || null).pipe(
            catchError((err: unknown) => {
              this.loadError.set(this.messageFrom(err));
              this.state.set('error');
              return of(null);
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((dto) => {
        if (dto === null) return;   // handled by catchError above
        this.result.set(dto);
        this.state.set('idle');
      });

    if (this.canView()) this.loadBranchOptions();
  }

  onSearchChange(q: string): void {
    this.searchQ.set(q);
    this.search$.next(q);
  }

  /** Changing the branch re-asks the same question about a different shelf. */
  onBranchChange(uid: string): void {
    this.branchUid.set(uid);
    const term = this.searchQ().trim();
    // distinctUntilChanged would swallow the identical term, so re-issue it directly.
    if (term) this.runSearch(term);
  }

  private runSearch(term: string): void {
    this.state.set('loading');
    this.loadError.set(null);
    this.stockService.itemInquiry(term, this.branchUid() || null).subscribe({
      next: (dto) => {
        this.result.set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        this.loadError.set(this.messageFrom(err));
        this.state.set('error');
      },
    });
  }

  /**
   * Offers only the branches the caller can actually read — the endpoint refuses a branch filter
   * for a caller with no assignment to it, so listing every branch would offer a choice that always
   * fails. Root forks to the full company list: the server exempts root from the assignment check,
   * and root usually holds one assignment or none.
   */
  private loadBranchOptions(): void {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            if (list.length === 0) return;
            const companyUid = list[0].uid;
            if (this.session.user()?.isRoot === true) {
              this.branchService.list(companyUid).subscribe({
                next: (branches) =>
                  this.branchOptions.set(
                    branches
                      .filter((b) => b.status === 'ACTIVE')
                      .map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
                  ),
                error: () => this.branchOptions.set([]),
              });
              return;
            }
            this.auth.myBranches().subscribe({
              next: (branches) =>
                this.branchOptions.set(
                  branches
                    .filter((b) => b.companyUid === companyUid)
                    .map((b) => ({ uid: b.branchUid, label: b.branchName, hint: b.branchCode })),
                ),
              error: () => this.branchOptions.set([]),
            });
          },
          error: () => undefined,
        });
      },
      error: () => undefined,
    });
  }

  /**
   * A 403 here is the branch filter, not the screen: the caller reached it holding both view
   * permissions, so a bare "no permission" would send them to an administrator for access they
   * already have.
   */
  private messageFrom(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
      if (err.status === 403) {
        return 'You cannot look up items in that branch. Choose a branch you work in, or clear the branch filter.';
      }
    }
    return 'Could not look that up. Check your connection and try again.';
  }
}
