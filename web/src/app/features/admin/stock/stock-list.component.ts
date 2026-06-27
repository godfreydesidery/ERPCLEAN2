import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { Branch } from '../models/branch.model';
import { ProductModel } from '../models/product.model';
import {
  AdjustmentReason,
  AdjustStockRequest,
  LocationOnHandRowDto,
  OpeningBalanceRequest,
  SetReorderLevelRequest,
  StockMovementDto,
  StockOnHandDto,
} from '../models/stock.model';
import { CompanyService } from '../company/company.service';
import { BranchService } from '../branch/branch.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { StockService, StockMovementPage, LocationOnHandPage } from './stock.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { q: string; page: number }

/**
 * Stock on-hand list. Company + optional branch scope. Debounced search. Paged.
 * Per-row actions: adjust stock (STOCK.ADJUST), set reorder level (STOCK.ADJUST).
 * Inline opening-balance form (STOCK.OPENING).
 * Per-row movements drawer (ledger history).
 * Product names resolved via a lazy lookup cache keyed by productId.
 */
@Component({
  selector: 'app-stock-list',
  imports: [FormsModule, DatePipe, PaginatorComponent],
  templateUrl: './stock-list.component.html',
  styleUrl: './stock-list.component.scss',
})
export class StockListComponent {
  private readonly stockService = inject(StockService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly organisationService = inject(OrganisationService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company / Branch context ──────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');
  readonly branches = signal<Branch[]>([]);
  readonly selectedBranchId = signal('');

  // ── List state ────────────────────────────────────────────────────────────────
  readonly rows = signal<StockOnHandDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Filters ───────────────────────────────────────────────────────────────────
  readonly searchQ = signal('');

  // ── Product name cache (productId → name) ─────────────────────────────────────
  readonly productNameMap = signal<Record<string, string>>({});

  // ── Adjust form ───────────────────────────────────────────────────────────────
  readonly adjustingUid = signal<string | null>(null);
  readonly adjustProductQ = signal('');
  readonly adjustProductResults = signal<ProductModel[]>([]);
  readonly adjustSelectedProduct = signal<{ uid: string; label: string } | null>(null);
  readonly adjustQty = signal('');
  readonly adjustReason = signal<AdjustmentReason>('COUNT_CORRECTION');
  readonly adjustNote = signal('');
  readonly adjusting = signal(false);
  readonly adjustError = signal<string | null>(null);

  // ── Opening balance form ──────────────────────────────────────────────────────
  readonly showOpeningForm = signal(false);
  readonly openingProductQ = signal('');
  readonly openingProductResults = signal<ProductModel[]>([]);
  readonly openingSelectedProduct = signal<{ uid: string; label: string } | null>(null);
  readonly openingQty = signal('');
  readonly openingNote = signal('');
  readonly openingBusy = signal(false);
  readonly openingError = signal<string | null>(null);

  // ── Reorder level inline edit ─────────────────────────────────────────────────
  readonly reorderEditUid = signal<string | null>(null);
  readonly reorderEditValue = signal('');
  readonly reorderSaving = signal(false);
  readonly reorderError = signal<string | null>(null);

  // ── Movements drawer ──────────────────────────────────────────────────────────
  readonly movementsUid = signal<string | null>(null);
  readonly movementsProductLabel = signal('');
  readonly movementRows = signal<StockMovementDto[]>([]);
  readonly movementMeta = signal<PageMeta>({ page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false });
  readonly movementsPage = signal(0);
  readonly movementsState = signal<'loading' | 'idle' | 'error'>('idle');

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();
  private readonly adjustProductSearch$ = new Subject<string>();
  private readonly openingProductSearch$ = new Subject<string>();

  readonly canAdjust = computed(() => this.session.hasPermission('STOCK.ADJUST'));
  readonly canOpening = computed(() => this.session.hasPermission('STOCK.OPENING'));
  readonly canView = computed(() => this.session.hasPermission('STOCK.VIEW'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly adjustmentReasons: AdjustmentReason[] = [
    'COUNT_CORRECTION', 'DAMAGE', 'SHRINKAGE', 'EXPIRY', 'RECEIPT_CORRECTION', 'OTHER',
  ];

  constructor() {
    // Main list pipeline
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
          // Scope (company + branch) is governed server-side by the request context / global branch
          // switcher — only the search term is sent. companyId here just gates the pipeline and
          // drives the product-name cache + the other tabs / pickers.
          return this.stockService.listOnHand(q || undefined, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
          this.resolveProductNames(rows);
        },
        error: (err) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

    // Adjust product search
    this.adjustProductSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.adjustProductResults.set([]); return []; }
          return this.productService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.adjustProductResults.set(rows.filter((r) => r.status !== 'ARCHIVED')),
        error: () => this.adjustProductResults.set([]),
      });

    // Opening balance product search
    this.openingProductSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.openingProductResults.set([]); return []; }
          return this.productService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.openingProductResults.set(rows.filter((r) => r.status !== 'ARCHIVED')),
        error: () => this.openingProductResults.set([]),
      });

    this.wireByProductSearch();
    this.loadCompanies();
  }

  // ── Product name resolution ───────────────────────────────────────────────────

  private resolveProductNames(rows: StockOnHandDto[]): void {
    const companyId = this.selectedCompanyId();
    if (!companyId || rows.length === 0) return;
    const existingMap = this.productNameMap();
    const unresolvedIds = rows
      .map((r) => r.productId)
      .filter((id) => !existingMap[id]);

    if (unresolvedIds.length === 0) return;

    // Fetch a broad page keyed by companyId to build a cache.
    // For each unresolved row we try a product lookup via the list endpoint.
    // Since the list doesn't filter by id directly, we batch by fetching a page
    // and caching whatever comes back.
    this.productService.list(companyId, '', 0, 200).subscribe({
      next: ({ rows: products }) => {
        const updated = { ...this.productNameMap() };
        for (const p of products) {
          updated[p.id] = `${p.code} — ${p.name}`;
        }
        this.productNameMap.set(updated);
      },
      error: () => undefined, // non-fatal: fall back to showing productId
    });
  }

  productLabel(productId: string): string {
    return this.productNameMap()[productId] ?? productId;
  }

  // ── Company / Branch loading ──────────────────────────────────────────────────

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
              this.loadBranches(list[0].uid);
              this.load(0);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadBranches(companyUid: string): void {
    this.branchService.list(companyUid).subscribe({
      next: (list) => this.branches.set(list),
      error: () => this.branches.set([]),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedBranchId.set('');
    const company = this.companies().find((c) => c.id === id);
    if (company) this.loadBranches(company.uid);
    // The company picker is only shown on the by-location / by-product tabs; refresh on-hand in
    // the background so a later tab switch is consistent, and clear any stale by-* selections.
    if (id) this.load(0);
  }

  /**
   * Branch selection for the By-Location tab only. The On-Hand view's branch scope is governed by
   * the global branch switcher (shell), not this control — so this handler drives by-location only.
   */
  onByLocationBranchChange(id: string): void {
    this.selectedBranchId.set(id);
    if (id) this.loadByLocation(0);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ q: this.searchQ(), page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  // ── Adjust form ───────────────────────────────────────────────────────────────

  openAdjustForm(row: StockOnHandDto): void {
    this.adjustingUid.set(row.uid);
    this.adjustQty.set('');
    this.adjustReason.set('COUNT_CORRECTION');
    this.adjustNote.set('');
    this.adjustError.set(null);
    this.adjustSelectedProduct.set(null);
    this.adjustProductQ.set(this.productLabel(row.productId));
    // Pre-select the product from the row
    this.adjustSelectedProduct.set({ uid: '', label: this.productLabel(row.productId) });
    // We need the product uid — look it up in the product name map via id
    // The map stores by id, but we need uid for the request. Fetch from product list if needed.
    this.resolveProductUidForRow(row.productId);
  }

  private pendingAdjustProductUid = signal<string>('');

  private resolveProductUidForRow(productId: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.productService.list(companyId, '', 0, 200).subscribe({
      next: ({ rows }) => {
        const found = rows.find((p) => p.id === productId);
        if (found) {
          this.pendingAdjustProductUid.set(found.uid);
          this.adjustSelectedProduct.set({ uid: found.uid, label: `${found.code} — ${found.name}` });
          this.adjustProductQ.set(`${found.code} — ${found.name}`);
        }
      },
      error: () => undefined,
    });
  }

  closeAdjustForm(): void {
    this.adjustingUid.set(null);
    this.adjustError.set(null);
  }

  onAdjustProductSearchChange(q: string): void {
    this.adjustProductQ.set(q);
    this.adjustSelectedProduct.set(null);
    this.adjustProductSearch$.next(q);
  }

  selectAdjustProduct(p: ProductModel): void {
    this.adjustSelectedProduct.set({ uid: p.uid, label: `${p.code} — ${p.name}` });
    this.adjustProductResults.set([]);
    this.adjustProductQ.set(`${p.code} — ${p.name}`);
  }

  submitAdjust(): void {
    const selected = this.adjustSelectedProduct();
    const qty = this.asStr(this.adjustQty());
    if (!selected?.uid) { this.adjustError.set('Select a product.'); return; }
    if (!qty || isNaN(Number(qty)) || Number(qty) === 0) {
      this.adjustError.set('Enter a non-zero quantity (positive or negative).');
      return;
    }
    this.adjusting.set(true);
    this.adjustError.set(null);
    const request: AdjustStockRequest = {
      productUid: selected.uid,
      quantity: qty,
      reasonCode: this.adjustReason(),
      note: this.adjustNote().trim() || undefined,
    };
    this.stockService.adjust(request).subscribe({
      next: () => {
        this.adjusting.set(false);
        this.adjustingUid.set(null);
        this.alerts.success('Stock adjusted');
        this.load(this.currentPage());
      },
      error: (err) => {
        this.adjustError.set(this.messageFrom(err, 'Could not adjust stock.'));
        this.adjusting.set(false);
      },
    });
  }

  // ── Opening balance form ──────────────────────────────────────────────────────

  toggleOpeningForm(): void {
    this.showOpeningForm.update((v) => !v);
    this.openingError.set(null);
    if (!this.showOpeningForm()) this.resetOpeningForm();
  }

  private resetOpeningForm(): void {
    this.openingProductQ.set('');
    this.openingProductResults.set([]);
    this.openingSelectedProduct.set(null);
    this.openingQty.set('');
    this.openingNote.set('');
  }

  onOpeningProductSearchChange(q: string): void {
    this.openingProductQ.set(q);
    this.openingSelectedProduct.set(null);
    this.openingProductSearch$.next(q);
  }

  selectOpeningProduct(p: ProductModel): void {
    this.openingSelectedProduct.set({ uid: p.uid, label: `${p.code} — ${p.name}` });
    this.openingProductResults.set([]);
    this.openingProductQ.set(`${p.code} — ${p.name}`);
  }

  /**
   * Coerce a signal value bound to a number-typed input to a trimmed string.
   * Angular's ngModel on `type="number"` emits a number, so `.trim()` on the raw value
   * throws; the backend DTOs take quantity/reorderLevel as strings on the wire.
   */
  private asStr(v: unknown): string {
    return v === null || v === undefined ? '' : String(v).trim();
  }

  submitOpeningBalance(): void {
    const selected = this.openingSelectedProduct();
    const qty = this.asStr(this.openingQty());
    if (!selected) { this.openingError.set('Select a product.'); return; }
    if (!qty || isNaN(Number(qty)) || Number(qty) <= 0) {
      this.openingError.set('Quantity must be greater than zero.');
      return;
    }
    this.openingBusy.set(true);
    this.openingError.set(null);
    const request: OpeningBalanceRequest = {
      productUid: selected.uid,
      quantity: qty,
      note: this.openingNote().trim() || undefined,
    };
    this.stockService.openingBalance(request).subscribe({
      next: () => {
        this.openingBusy.set(false);
        this.showOpeningForm.set(false);
        this.resetOpeningForm();
        this.alerts.success('Opening balance recorded');
        this.load(this.currentPage());
      },
      error: (err) => {
        this.openingError.set(this.messageFrom(err, 'Could not record opening balance.'));
        this.openingBusy.set(false);
      },
    });
  }

  // ── Reorder level ─────────────────────────────────────────────────────────────

  startReorderEdit(row: StockOnHandDto): void {
    this.reorderEditUid.set(row.uid);
    this.reorderEditValue.set(row.reorderLevel ?? '');
    this.reorderError.set(null);
  }

  cancelReorderEdit(): void {
    this.reorderEditUid.set(null);
    this.reorderError.set(null);
  }

  saveReorderLevel(row: StockOnHandDto): void {
    const val = this.asStr(this.reorderEditValue());
    const level = val === '' ? null : val;
    if (level !== null && (isNaN(Number(level)) || Number(level) < 0)) {
      this.reorderError.set('Reorder level must be zero or positive (leave blank to clear).');
      return;
    }
    this.reorderSaving.set(true);
    this.reorderError.set(null);
    const request: SetReorderLevelRequest = { reorderLevel: level };
    this.stockService.setReorderLevel(row.uid, request).subscribe({
      next: (updated) => {
        this.reorderSaving.set(false);
        this.reorderEditUid.set(null);
        this.rows.update((rs) => rs.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Reorder level updated');
      },
      error: (err) => {
        this.reorderError.set(this.messageFrom(err, 'Could not update reorder level.'));
        this.reorderSaving.set(false);
      },
    });
  }

  // ── Movements drawer ──────────────────────────────────────────────────────────

  openMovements(row: StockOnHandDto): void {
    this.movementsUid.set(row.uid);
    this.movementsProductLabel.set(this.productLabel(row.productId));
    this.movementsPage.set(0);
    this.loadMovements(row.productId, 0);
  }

  closeMovements(): void {
    this.movementsUid.set(null);
    this.movementRows.set([]);
  }

  private loadMovements(productId: string, page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    // productId is a Long id; we need productUid for the API.
    // Resolve uid from the cached product list, then fetch movements.
    this.productService.list(companyId, '', 0, 200).subscribe({
      next: ({ rows: products }) => {
        const found = products.find((p) => p.id === productId);
        if (!found) { this.movementsState.set('error'); return; }
        this.fetchMovements(found.uid, companyId, page);
      },
      error: () => this.movementsState.set('error'),
    });
  }

  private fetchMovements(productUid: string, companyId: string, page: number): void {
    this.movementsState.set('loading');
    this.movementsPage.set(page);
    this.stockService.listMovements(productUid, companyId, page).subscribe({
      next: ({ rows, meta }: StockMovementPage) => {
        this.movementRows.set(rows);
        this.movementMeta.set(meta);
        this.movementsState.set('idle');
      },
      error: () => this.movementsState.set('error'),
    });
  }

  goToMovementsPage(page: number): void {
    const uid = this.movementsUid();
    if (!uid) return;
    const row = this.rows().find((r) => r.uid === uid);
    if (row) this.loadMovements(row.productId, page);
  }

  movementsPrevPage(): void {
    const p = this.movementsPage();
    if (p > 0) {
      const uid = this.movementsUid();
      if (!uid) return;
      const row = this.rows().find((r) => r.uid === uid);
      if (row) this.loadMovements(row.productId, p - 1);
    }
  }

  movementsNextPage(): void {
    if (this.movementMeta().hasNext) {
      const uid = this.movementsUid();
      if (!uid) return;
      const row = this.rows().find((r) => r.uid === uid);
      if (row) this.loadMovements(row.productId, this.movementsPage() + 1);
    }
  }

  // ── View mode (on-hand / by-location / by-product) ────────────────────────────

  readonly viewMode = signal<'on-hand' | 'by-location' | 'by-product'>('on-hand');

  // by-location state
  readonly byLocationRows = signal<LocationOnHandRowDto[]>([]);
  private readonly _emptyLocationPage: LocationOnHandPage = { rows: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false } };
  readonly byLocationMeta = signal(this._emptyLocationPage.meta);
  readonly byLocationState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly byLocationCurrentPage = signal(0);

  // by-product state
  readonly byProductRows = signal<LocationOnHandRowDto[]>([]);
  readonly byProductState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly byProductQ = signal('');
  readonly byProductResults = signal<ProductModel[]>([]);
  readonly selectedByProductProduct = signal<{ uid: string; label: string } | null>(null);
  private readonly byProductSearch$ = new Subject<string>();

  readonly byLocationEmpty = computed(() => this.byLocationState() === 'idle' && this.byLocationRows().length === 0);
  readonly byProductEmpty = computed(
    () => this.byProductState() === 'idle' && this.byProductRows().length === 0 && this.selectedByProductProduct() !== null,
  );

  // Called from constructor to wire the by-product search stream
  private wireByProductSearch(): void {
    this.byProductSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.byProductResults.set([]); return []; }
          return this.productService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.byProductResults.set(rows.filter((r) => r.status !== 'ARCHIVED')),
        error: () => this.byProductResults.set([]),
      });
  }

  setViewMode(mode: 'on-hand' | 'by-location' | 'by-product'): void {
    this.viewMode.set(mode);
    if (mode === 'by-location') this.loadByLocation(0);
  }

  loadByLocation(page: number): void {
    const companyId = this.selectedCompanyId();
    const branchId = this.selectedBranchId();
    if (!companyId || !branchId) return;
    this.byLocationState.set('loading');
    this.byLocationCurrentPage.set(page);
    this.stockService.listOnHandByLocation(companyId, branchId, page, 20).subscribe({
      next: ({ rows, meta }) => {
        this.byLocationRows.set(rows);
        this.byLocationMeta.set(meta);
        this.byLocationState.set('idle');
      },
      error: () => this.byLocationState.set('error'),
    });
  }

  goToByLocationPage(page: number): void { this.loadByLocation(page); }

  onByProductSearchChange(q: string): void {
    this.byProductQ.set(q);
    this.selectedByProductProduct.set(null);
    this.byProductRows.set([]);
    this.byProductSearch$.next(q);
  }

  selectByProductProduct(p: ProductModel): void {
    this.selectedByProductProduct.set({ uid: p.uid, label: `${p.code} — ${p.name}` });
    this.byProductQ.set(`${p.code} — ${p.name}`);
    this.byProductResults.set([]);
    this.loadByProduct(p.uid);
  }

  private loadByProduct(productUid: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.byProductState.set('loading');
    this.byProductRows.set([]);
    this.stockService.listOnHandByProduct(productUid, companyId).subscribe({
      next: (rows) => { this.byProductRows.set(rows); this.byProductState.set('idle'); },
      error: () => this.byProductState.set('error'),
    });
  }

  fmtQty(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(3) : '0.000';
  }

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  // ── Display helpers ───────────────────────────────────────────────────────────

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
