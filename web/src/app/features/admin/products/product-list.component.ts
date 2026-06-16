import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import {
  CreateProductRequest,
  Money,
  ProductModel,
  ProductType,
  UnitOfMeasureDto,
  VatStatus,
} from '../models/product.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from './product.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { q: string; page: number }

/**
 * Paged list of products scoped to the active company.
 * Provides debounced search, a barcode-scan lookup affordance, and an inline create form.
 * All four states (loading / empty / error / forbidden) handled.
 * BR-PROD-01: SERVICE type cannot be stockable — stockable disabled when type=SERVICE.
 */
@Component({
  selector: 'app-product-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './product-list.component.html',
  styleUrl: './product-list.component.scss',
})
export class ProductListComponent {
  private readonly productService = inject(ProductService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<ProductModel[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Search ─────────────────────────────────────────────────────────────────
  readonly searchQ = signal('');

  // ── Barcode lookup ─────────────────────────────────────────────────────────
  readonly barcodeQ = signal('');
  readonly barcodeResult = signal<ProductModel | null>(null);
  readonly barcodeState = signal<'idle' | 'loading' | 'notfound' | 'error'>('idle');

  // ── Create form ────────────────────────────────────────────────────────────
  /** Optional product code; blank → backend auto-assigns PROD-####. */
  readonly newCode = signal('');
  readonly newName = signal('');
  readonly newDescription = signal('');
  readonly newType = signal<ProductType>('GOODS');
  readonly newSellable = signal(true);
  readonly newStockable = signal(true);
  readonly newBaseUnitUid = signal('');
  readonly newCostAmount = signal('');
  readonly newCostCurrency = signal('TZS');
  readonly newVatStatus = signal<VatStatus>('STANDARD');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  readonly showCreateForm = signal(false);

  // ── Company units (for base-unit <select>) ────────────────────────────────
  readonly companyUnits = signal<UnitOfMeasureDto[]>([]);
  readonly unitsState = signal<'idle' | 'loading' | 'error'>('idle');

  // BR-PROD-01: SERVICE cannot be stockable.
  readonly stockableDisabled = computed(() => this.newType() === 'SERVICE');

  readonly canManage = computed(() => this.session.hasPermission('PRODUCT.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  // ── Search pipeline ────────────────────────────────────────────────────────
  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const typingTrigger$ = toObservable(this.searchQ).pipe(
      skip(1),
      debounceTime(300),
      distinctUntilChanged(),
      map((q): LoadTrigger => ({ q, page: 0 })),
    );

    merge(typingTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ q, page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.productService.list(companyId, q || undefined, page, DEFAULT_SIZE);
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
              this.loadUnits(list[0].id);
              this.load(0);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) {
      this.newBaseUnitUid.set('');
      this.loadUnits(id);
      this.load(0);
    }
  }

  private loadUnits(companyId: string): void {
    this.unitsState.set('loading');
    this.productService.listUnits(companyId).subscribe({
      next: ({ rows }) => {
        this.companyUnits.set(rows.filter((u) => u.status === 'ACTIVE'));
        this.unitsState.set('idle');
      },
      error: () => this.unitsState.set('error'),
    });
  }

  applySearch(): void {
    this.load(0);
  }

  clearSearch(): void {
    this.searchQ.set('');
    this.load(0);
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ q: this.searchQ(), page });
  }

  // ── Barcode lookup ─────────────────────────────────────────────────────────

  lookupBarcode(): void {
    const barcode = this.barcodeQ().trim();
    const companyId = this.selectedCompanyId();
    if (!barcode || !companyId) return;
    this.barcodeState.set('loading');
    this.barcodeResult.set(null);
    this.productService.barcodeLookup(companyId, barcode).subscribe({
      next: (p) => {
        this.barcodeResult.set(p);
        this.barcodeState.set('idle');
      },
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 404) {
          this.barcodeState.set('notfound');
        } else {
          this.barcodeState.set('error');
        }
      },
    });
  }

  clearBarcodeSearch(): void {
    this.barcodeQ.set('');
    this.barcodeResult.set(null);
    this.barcodeState.set('idle');
  }

  // ── Create form ────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
  }

  private resetCreateForm(): void {
    this.newCode.set('');
    this.newName.set('');
    this.newDescription.set('');
    this.newType.set('GOODS');
    this.newSellable.set(true);
    this.newStockable.set(true);
    this.newBaseUnitUid.set('');
    this.newCostAmount.set('');
    this.newCostCurrency.set('TZS');
    this.newVatStatus.set('STANDARD');
  }

  onNewTypeChange(type: ProductType): void {
    this.newType.set(type);
    // BR-PROD-01: SERVICE cannot be stockable.
    if (type === 'SERVICE') {
      this.newStockable.set(false);
    }
  }

  create(): void {
    const companyId = this.selectedCompanyId();
    const name = this.newName().trim();
    const baseUnitUid = this.newBaseUnitUid();

    if (!companyId || !name) {
      this.formError.set('Company and name are required.');
      return;
    }
    if (!baseUnitUid) {
      this.formError.set('Base unit is required.');
      return;
    }

    // Resolve the selected company's uid for the create request (backend contract: companyUid).
    const company = this.companies().find((c) => c.id === companyId);
    if (!company) {
      this.formError.set('Could not resolve selected company.');
      return;
    }

    this.saving.set(true);
    this.formError.set(null);

    const cost: Money | undefined =
      this.newCostAmount().trim()
        ? { amount: this.newCostAmount().trim(), currency: this.newCostCurrency().trim() || 'TZS' }
        : undefined;

    const request: CreateProductRequest = {
      companyUid: company.uid,
      // Optional code; blank → backend auto-assigns PROD-####.
      code: this.newCode().trim() || undefined,
      name,
      description: this.newDescription().trim() || undefined,
      type: this.newType(),
      sellable: this.newSellable(),
      // BR-PROD-01: SERVICE is never stockable regardless of checkbox.
      stockable: this.newType() === 'SERVICE' ? false : this.newStockable(),
      baseUnitUid,
      cost,
      vatStatus: this.newVatStatus(),
    };

    this.productService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Product created', created.name);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err));
        this.saving.set(false);
      },
    });
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Could not save the product.';
  }
}
