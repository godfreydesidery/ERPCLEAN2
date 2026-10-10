import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { Branch } from '../../models/branch.model';
import { CompanyService } from '../../company/company.service';
import { BranchService } from '../../branch/branch.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { StockLocationService } from '../locations/stock-location.service';
import { UidPickerComponent, UidOption } from '../../../../shared/uid-picker/uid-picker.component';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ProductService } from '../../products/product.service';
import { ProductModel } from '../../models/product.model';
import { CreateStockCountRequest } from './stock-count.model';
import { StockCountService } from './stock-count.service';
import { todayLocal } from '../../../../shared/date.util';

/**
 * Create a new stock count document.
 * Resolves company → branch → active locations; user picks a location + count type.
 * On success navigates to the detail page.
 */
@Component({
  selector: 'app-stock-count-create',
  imports: [FormsModule, RouterLink, UidPickerComponent],
  templateUrl: './stock-count-create.component.html',
  styleUrl: './stock-count-create.component.scss',
})
export class StockCountCreateComponent {
  private readonly countService = inject(StockCountService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly organisationService = inject(OrganisationService);
  private readonly locationService = inject(StockLocationService);
  private readonly router = inject(Router);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company / Branch context ──────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');
  readonly branches = signal<Branch[]>([]);
  readonly selectedBranchUid = signal('');

  // ── Location picker options ───────────────────────────────────────────────────
  readonly locationOptions = signal<UidOption[]>([]);
  readonly locationsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Form fields ───────────────────────────────────────────────────────────────
  readonly fLocationUid = signal('');
  readonly fCountDate = signal(todayLocal());
  readonly fCountType = signal<'FULL' | 'CYCLE'>('FULL');
  readonly fNotes = signal('');

  /** STK-13: the products a CYCLE count covers, picked by server-side search. */
  readonly cycleProducts = signal<{ uid: string; label: string }[]>([]);
  readonly cycleQ = signal('');
  readonly cycleResults = signal<ProductModel[]>([]);
  private readonly cycleSearch$ = new Subject<string>();

  // ── Reference-data availability ───────────────────────────────────────────────
  /** True when the branch list could not be loaded (non-fatal; location picker uses first branch only). */
  readonly branchesUnavailable = signal(false);

  // ── Submit state ──────────────────────────────────────────────────────────────
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('STOCK.COUNT.CREATE'));

  constructor() {
    this.cycleSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.cycleResults.set([]); return []; }
          return this.productService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.cycleResults.set(
          rows.filter((p) => p.status !== 'ARCHIVED' && p.stockable !== false),
        ),
        error: () => this.cycleResults.set([]),
      });
    this.loadCompanies();
  }

  onCycleSearch(q: string): void {
    this.cycleQ.set(q);
    this.cycleSearch$.next(q);
  }

  addCycleProduct(p: ProductModel): void {
    if (!this.cycleProducts().some((x) => x.uid === p.uid)) {
      this.cycleProducts.update((list) => [...list, { uid: p.uid, label: `${p.code} — ${p.name}` }]);
    }
    this.cycleQ.set('');
    this.cycleResults.set([]);
  }

  removeCycleProduct(uid: string): void {
    this.cycleProducts.update((list) => list.filter((x) => x.uid !== uid));
  }

  // ── Loaders ───────────────────────────────────────────────────────────────────

  private loadCompanies(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              // Default to the active company from session; fall back to list[0].
              const activeCompanyUid = this.session.user()?.activeCompanyUid ?? null;
              const active = activeCompanyUid
                ? (list.find((c) => c.uid === activeCompanyUid) ?? list[0])
                : list[0];
              this.selectedCompanyId.set(active.id);
              this.loadBranches(active.uid);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadBranches(companyUid: string): void {
    this.branchesUnavailable.set(false);
    this.branchService.list(companyUid).subscribe({
      next: (list) => {
        this.branches.set(list);
        if (list.length > 0) {
          // Default to the user's active branch; fall back to list[0].
          const activeBranchUid = this.session.activeBranchUid() ?? null;
          const active = activeBranchUid
            ? (list.find((b) => b.uid === activeBranchUid) ?? list[0])
            : list[0];
          this.selectedBranchUid.set(active.uid);
          this.loadLocations(active.uid);
        }
      },
      error: () => { this.branches.set([]); this.branchesUnavailable.set(true); },
    });
  }

  private loadLocations(branchUid: string): void {
    this.locationsState.set('loading');
    this.fLocationUid.set('');
    this.locationOptions.set([]);
    this.locationService.activeForBranch(branchUid).subscribe({
      next: (locs) => {
        this.locationOptions.set(
          locs.map((l) => ({ uid: l.uid, label: l.name, hint: l.code })),
        );
        this.locationsState.set('idle');
      },
      error: () => this.locationsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedBranchUid.set('');
    this.locationOptions.set([]);
    this.fLocationUid.set('');
    const company = this.companies().find((c) => c.id === id);
    if (company) this.loadBranches(company.uid);
  }

  onBranchChange(uid: string): void {
    this.selectedBranchUid.set(uid);
    this.loadLocations(uid);
  }

  // ── Submit ────────────────────────────────────────────────────────────────────

  submit(): void {
    this.formError.set(null);

    if (!this.fLocationUid()) {
      this.formError.set('Select a stock location.');
      return;
    }
    if (!this.fCountDate()) {
      this.formError.set('Count date is required.');
      return;
    }

    if (this.fCountType() === 'CYCLE' && this.cycleProducts().length === 0) {
      this.formError.set('Choose the products to count, or use a FULL count.');
      return;
    }

    this.submitting.set(true);
    const request: CreateStockCountRequest = {
      locationUid: this.fLocationUid(),
      countDate: this.fCountDate(),
      countType: this.fCountType(),
      notes: this.fNotes().trim() || undefined,
    };
    if (this.fCountType() === 'CYCLE') request.productUids = this.cycleProducts().map((p) => p.uid);

    this.countService.create(request).subscribe({
      next: (created) => {
        this.submitting.set(false);
        this.alerts.success('Stock count created', created.countNumber);
        this.router.navigate(['/admin/stock-counts/uid', created.uid]);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not create stock count.'));
        this.submitting.set(false);
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
