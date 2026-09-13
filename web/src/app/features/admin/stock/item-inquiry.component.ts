import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Observable, Subject, catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { BranchService } from '../branch/branch.service';
import { ProductService } from '../products/product.service';
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
 * <b>Read-only by design</b> (Kilimanjaro 2026-09-13). The screen answers a question and offers no
 * way to act on the answer. It used to carry an "Open" button per row into the Product Master,
 * which is an EDIT screen — so a lookup that a cashier opens mid-sale was one click from changing
 * the catalogue. The client asked for display only, and that is now the rule for this screen: no
 * links, no buttons, no navigation out of a result row. The search box and the branch picker stay;
 * they steer the question, they do not change anything. A regression test asserts the results table
 * contains no interactive element at all, because the natural instinct of the next person here is
 * to add the convenient link back.
 *
 * Route: /admin/stock/item-inquiry. Guard = PRODUCT.VIEW AND STOCK.VIEW, identical to the endpoint's
 * @PreAuthorize.
 */
@Component({
  selector: 'app-item-inquiry',
  imports: [FormsModule, UidPickerComponent],
  templateUrl: './item-inquiry.component.html',
  styleUrl: './item-inquiry.component.scss',
})
export class ItemInquiryComponent {
  private readonly stockService = inject(StockService);
  private readonly organisationService = inject(OrganisationService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly productService = inject(ProductService);
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

  // ── Pick one item and look at it on its own ────────────────────────────

  /**
   * Typing a term answers "which items match this"; the picker answers "tell me about THIS item".
   * Both are needed: a counter clerk searching "kony" wants the list, while somebody checking one
   * known item wants it without reading a table to find its row.
   *
   * <p>Still read-only. This opens a panel on the same screen — it does NOT navigate to the Product
   * Master, which is an edit screen and is the door this screen deliberately does not have.
   */
  readonly companyUid = signal('');
  readonly selectedProductUid = signal('');
  readonly selectedItem = signal<ItemInquiryRowDto | null>(null);
  /**
   * The answer the picked item came back in. Held separately from {@link result} because the two
   * are answers to different questions: whether cost may be shown, and which price list the selling
   * price came from, belong to THIS lookup — reading them off the typed search would describe the
   * panel using a different question's answer.
   */
  private readonly selectedDto = signal<ItemInquiryDto | null>(null);
  readonly selectedState = signal<LoadState>('idle');
  readonly selectedMissing = signal(false);

  /**
   * Server-side product lookup for the picker (arrow property so {@code this} survives the binding).
   * Never a preloaded list filtered in the browser: past the first page an item would be neither
   * listed nor findable, which is exactly how the product pickers came to be reported as "missing
   * products".
   */
  /** False also when nothing is selected — the panel is not rendered then anyway. */
  readonly selectedCostVisible = computed(() => this.selectedDto()?.costVisible ?? false);

  /** Mirrors {@link sellingHeader}, but for the picked item's own answer. */
  readonly selectedSellingHeader = computed(() => {
    const d = this.selectedDto();
    if (!d || d.priceListName === null) return 'Selling price';
    return d.priceIncludesVat ? 'Selling price (VAT incl.)' : 'Selling price (excl. VAT)';
  });

  readonly searchProducts = (q: string): Observable<readonly UidOption[]> =>
    this.productService.list(this.companyUid(), q, 0, 25).pipe(
      map(({ rows }) =>
        rows
          .filter((p) => p.status !== 'ARCHIVED')
          .map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
      ),
    );

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

  /**
   * An item was picked from the dropdown. Resolve it to its code, ask the same inquiry endpoint
   * about it, and keep the row that IS that product — matched on uid, not on the code string,
   * because a code search can legitimately match more than one item.
   */
  onProductSelected(uid: string): void {
    this.selectedProductUid.set(uid);
    this.selectedItem.set(null);
    this.selectedDto.set(null);
    this.selectedMissing.set(false);
    if (!uid) {
      this.selectedState.set('idle');
      return;
    }

    this.selectedState.set('loading');
    this.productService.getByUid(uid).subscribe({
      next: (product) => {
        this.stockService.itemInquiry(product.code, this.branchUid() || null).subscribe({
          next: (dto) => {
            if (this.selectedProductUid() !== uid) return;   // a newer pick already superseded this
            const row = dto.rows.find((r) => r.productUid === uid) ?? null;
            this.selectedDto.set(dto);
            this.selectedItem.set(row);
            // The picker offered it, so it exists; if the inquiry cannot see it the item is not
            // stocked in this branch, and saying so beats showing an empty panel.
            this.selectedMissing.set(row === null);
            this.selectedState.set('idle');
          },
          error: (err: unknown) => {
            if (this.selectedProductUid() !== uid) return;
            this.loadError.set(this.messageFrom(err));
            this.selectedState.set('error');
          },
        });
      },
      error: (err: unknown) => {
        if (this.selectedProductUid() !== uid) return;
        this.loadError.set(this.messageFrom(err));
        this.selectedState.set('error');
      },
    });
  }

  clearSelectedItem(): void {
    this.selectedProductUid.set('');
    this.selectedItem.set(null);
    this.selectedDto.set(null);
    this.selectedMissing.set(false);
    this.selectedState.set('idle');
  }

  /** Changing the branch re-asks the same question about a different shelf. */
  onBranchChange(uid: string): void {
    this.branchUid.set(uid);
    const term = this.searchQ().trim();
    // The picked item is answered for a branch too, so it must be re-asked for the new one.
    if (this.selectedProductUid()) this.onProductSelected(this.selectedProductUid());
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
            this.companyUid.set(list[0].id);   // the product list is queried by company ID
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
