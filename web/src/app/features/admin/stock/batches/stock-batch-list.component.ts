import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { ProductModel } from '../../models/product.model';
import { StockLocationService } from '../locations/stock-location.service';
import { StockLocationDto } from '../locations/stock-location.model';
import { StockBatchService } from './stock-batch.service';
import { StockBatchDto } from './stock-batch.model';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';
import type { UidOption } from '../../../../shared/uid-picker/uid-picker.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number; mode: 'location' | 'expiring' }

/**
 * Stock Batch list screen (read-only).
 * - Filter by location + product (uses <app-uid-picker>).
 * - "Expiring Soon" tab shows batches expiring before a configurable horizon.
 * Route: /admin/stock/batches
 */
@Component({
  selector: 'app-stock-batch-list',
  imports: [FormsModule, PaginatorComponent, UidPickerComponent],
  templateUrl: './stock-batch-list.component.html',
  styleUrl: './stock-batch-list.component.scss',
})
export class StockBatchListComponent {
  private readonly batchService = inject(StockBatchService);
  private readonly locationService = inject(StockLocationService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly productService = inject(ProductService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Location picker (uid for picker, id for API param) ─────────────────────
  readonly locations = signal<StockLocationDto[]>([]);
  readonly locationOptions = computed<UidOption[]>(() =>
    this.locations().map((l) => ({ uid: l.uid, label: l.name, hint: l.code })),
  );
  readonly selectedLocationUid = signal('');
  /** Resolved numeric id of the selected location, used in API params. */
  readonly selectedLocationId = computed<string>(() => {
    const uid = this.selectedLocationUid();
    return this.locations().find((l) => l.uid === uid)?.id ?? '';
  });

  // ── Product picker (uid for picker, id for API param) ──────────────────────
  readonly products = signal<ProductModel[]>([]);
  readonly productOptions = computed<UidOption[]>(() =>
    this.products().map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
  );
  readonly selectedProductUid = signal('');
  readonly selectedProductId = computed<string>(() => {
    const uid = this.selectedProductUid();
    return this.products().find((p) => p.uid === uid)?.id ?? '';
  });

  // ── Expiry view ────────────────────────────────────────────────────────────
  readonly viewMode = signal<'location' | 'expiring'>('location');
  readonly horizonDate = signal<string>(this.defaultHorizon());

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<StockBatchDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly canView = computed(() => this.session.hasPermission('STOCK.BATCH.VIEW'));
  readonly canExpiryView = computed(() => this.session.hasPermission('INVENTORY.EXPIRY.VIEW'));

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    this.immediateTrigger$
      .pipe(
        switchMap(({ page, mode }) => {
          this.state.set('loading');
          this.currentPage.set(page);
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          if (mode === 'expiring') {
            return this.batchService.listExpiring(companyId, this.horizonDate(), page, DEFAULT_SIZE);
          }
          const locationId = this.selectedLocationId();
          const productId = this.selectedProductId();
          if (!locationId || !productId) return [];
          return this.batchService.listAtLocation(companyId, locationId, productId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

    this.loadCompanies();
  }

  // ── Company / Location / Product loading ───────────────────────────────────

  private loadCompanies(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
              this.loadLocations(list[0].id);
              this.loadProducts(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadLocations(companyId: string): void {
    // Use the paged list with a large page to populate the picker;
    // the active list endpoint requires branchUid which we may not have.
    this.locationService.list(0, 200).subscribe({
      next: ({ rows }) => this.locations.set(rows.filter((l) => l.companyId === companyId)),
      error: () => this.locations.set([]),
    });
  }

  private loadProducts(companyId: string): void {
    this.productService.list(companyId, '', 0, 200).subscribe({
      next: ({ rows }) => this.products.set(rows.filter((p) => p.status !== 'ARCHIVED')),
      error: () => this.products.set([]),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedLocationUid.set('');
    this.selectedProductUid.set('');
    this.rows.set([]);
    this.loadLocations(id);
    this.loadProducts(id);
  }

  onLocationChange(uid: string): void {
    this.selectedLocationUid.set(uid);
    this.tryLoad();
  }

  onProductChange(uid: string): void {
    this.selectedProductUid.set(uid);
    this.tryLoad();
  }

  private tryLoad(): void {
    const mode = this.viewMode();
    if (mode === 'expiring') {
      this.load(0);
      return;
    }
    if (this.selectedLocationId() && this.selectedProductId()) {
      this.load(0);
    }
  }

  switchToExpiring(): void {
    this.viewMode.set('expiring');
    if (this.selectedCompanyId()) this.load(0);
  }

  switchToLocation(): void {
    this.viewMode.set('location');
    this.rows.set([]);
    this.meta.set({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  }

  load(page: number): void {
    this.immediateTrigger$.next({ page, mode: this.viewMode() });
  }

  goToPage(page: number): void { this.load(page); }

  private defaultHorizon(): string {
    const d = new Date();
    d.setDate(d.getDate() + 30);
    return d.toISOString().slice(0, 10);
  }

  expiryBadgeClass(expired: boolean): string {
    return expired ? 'text-bg-danger' : 'text-bg-warning';
  }
}
