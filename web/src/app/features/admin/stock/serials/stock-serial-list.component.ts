import { HttpErrorResponse } from '@angular/common/http';
import { SlicePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { ProductModel } from '../../models/product.model';
import { StockLocationService } from '../locations/stock-location.service';
import { StockLocationDto } from '../locations/stock-location.model';
import { StockSerialService } from './stock-serial.service';
import { SerialStatus, StockSerialDto } from './stock-serial.model';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';
import type { UidOption } from '../../../../shared/uid-picker/uid-picker.component';

const DEFAULT_SIZE = 20;

type ViewMode = 'location' | 'product';
interface LoadTrigger { page: number; mode: ViewMode }

/**
 * Stock Serial list screen (read-only).
 * Modes:
 *  1. "By Location" — filter by location + product; optional status filter.
 *  2. "By Product" — per-product history via /product/uid/{productUid}.
 * Lookup by serial number via the lookup endpoint.
 * Route: /admin/stock/serials
 */
@Component({
  selector: 'app-stock-serial-list',
  imports: [FormsModule, SlicePipe, PaginatorComponent, UidPickerComponent],
  templateUrl: './stock-serial-list.component.html',
  styleUrl: './stock-serial-list.component.scss',
})
export class StockSerialListComponent {
  private readonly serialService = inject(StockSerialService);
  private readonly locationService = inject(StockLocationService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Location picker ────────────────────────────────────────────────────────
  readonly locations = signal<StockLocationDto[]>([]);
  readonly locationOptions = computed<UidOption[]>(() =>
    this.locations().map((l) => ({ uid: l.uid, label: l.name, hint: l.code })),
  );
  readonly selectedLocationUid = signal('');
  readonly selectedLocationId = computed<string>(() => {
    const uid = this.selectedLocationUid();
    return this.locations().find((l) => l.uid === uid)?.id ?? '';
  });

  // ── Product picker ─────────────────────────────────────────────────────────
  readonly products = signal<ProductModel[]>([]);
  readonly productOptions = computed<UidOption[]>(() =>
    this.products().map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
  );
  readonly selectedProductUid = signal('');
  readonly selectedProductId = computed<string>(() => {
    const uid = this.selectedProductUid();
    return this.products().find((p) => p.uid === uid)?.id ?? '';
  });

  // ── Status filter ──────────────────────────────────────────────────────────
  readonly statusFilter = signal<SerialStatus | ''>('');
  readonly serialStatuses: Array<SerialStatus | ''> = ['', 'IN_STOCK', 'ISSUED', 'RETURNED'];

  // ── View mode ──────────────────────────────────────────────────────────────
  readonly viewMode = signal<ViewMode>('location');

  // ── Serial lookup ──────────────────────────────────────────────────────────
  readonly lookupSerialNumber = signal('');
  readonly lookupResult = signal<StockSerialDto | null>(null);
  readonly lookupState = signal<'idle' | 'loading' | 'error' | 'not-found'>('idle');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<StockSerialDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly canView = computed(() => this.session.hasPermission('STOCK.SERIAL.VIEW'));

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    this.immediateTrigger$
      .pipe(
        switchMap(({ page, mode }) => {
          this.state.set('loading');
          this.currentPage.set(page);
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];

          if (mode === 'product') {
            const productUid = this.selectedProductUid();
            if (!productUid) return [];
            return this.serialService.listByProduct(productUid, companyId, page, DEFAULT_SIZE);
          }

          // location mode
          const locationId = this.selectedLocationId();
          const productId = this.selectedProductId();
          if (!locationId || !productId) return [];
          const status = this.statusFilter() || undefined;
          return this.serialService.listAtLocation(
            companyId, locationId, productId, status as SerialStatus | undefined, page, DEFAULT_SIZE,
          );
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

  onFilterChange(): void {
    const mode = this.viewMode();
    if (mode === 'product') {
      if (this.selectedProductUid()) this.load(0);
      return;
    }
    if (this.selectedLocationId() && this.selectedProductId()) this.load(0);
  }

  switchToLocation(): void {
    this.viewMode.set('location');
    this.rows.set([]);
    this.meta.set({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  }

  switchToProduct(): void {
    this.viewMode.set('product');
    this.rows.set([]);
    this.meta.set({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  }

  load(page: number): void {
    this.immediateTrigger$.next({ page, mode: this.viewMode() });
  }

  goToPage(page: number): void { this.load(page); }

  // ── Serial lookup ──────────────────────────────────────────────────────────

  lookupSerial(): void {
    const companyId = this.selectedCompanyId();
    const productId = this.selectedProductId();
    const serial = this.lookupSerialNumber().trim();
    if (!companyId || !productId || !serial) return;

    this.lookupState.set('loading');
    this.lookupResult.set(null);
    this.serialService.lookup(companyId, productId, serial).subscribe({
      next: (dto) => {
        this.lookupResult.set(dto);
        this.lookupState.set('idle');
      },
      error: (err) => {
        if (err instanceof HttpErrorResponse && err.status === 404) {
          this.lookupState.set('not-found');
        } else {
          this.lookupState.set('error');
        }
      },
    });
  }

  clearLookup(): void {
    this.lookupResult.set(null);
    this.lookupState.set('idle');
    this.lookupSerialNumber.set('');
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  statusBadgeClass(status: string): string {
    switch (status) {
      case 'IN_STOCK': return 'text-bg-success';
      case 'ISSUED': return 'text-bg-warning';
      case 'RETURNED': return 'text-bg-secondary';
      default: return 'text-bg-light';
    }
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
