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
import { StockUnitOption, StockUnitOptionsService, packBreakdown, toBaseQty, unitFactor } from './stock-units';

/** Display label for an on-hand row — always built from the denormalised fields. */
function rowProductLabel(row: StockOnHandDto): string {
  return `${row.productCode} — ${row.productName}`;
}

const DEFAULT_SIZE = 20;

interface LoadTrigger { q: string; page: number }

/**
 * Stock on-hand list. Company + optional branch scope. Debounced search. Paged.
 * Per-row actions: adjust stock (STOCK.ADJUST), set reorder level (STOCK.ADJUST).
 * Inline opening-balance form (STOCK.OPENING).
 * Per-row movements drawer (ledger history).
 * Product code + name are denormalised on each StockOnHandDto row — no secondary lookup needed.
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
  private readonly unitOptions = inject(StockUnitOptionsService);
  protected readonly session = inject(SessionStore);

  // ── Company / Branch context ──────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');
  readonly branches = signal<Branch[]>([]);
  readonly selectedBranchId = signal('');
  /** True when the branch list could not be loaded (non-fatal; by-location tab shows a notice). */
  readonly branchesUnavailable = signal(false);

  // ── List state ────────────────────────────────────────────────────────────────
  readonly rows = signal<StockOnHandDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Filters ───────────────────────────────────────────────────────────────────
  readonly searchQ = signal('');

  // ── Adjust form ───────────────────────────────────────────────────────────────
  // Two entry points share this same form state: (1) the per-row "Adjust" button
  // (adjustingUid = the row's uid), and (2) the toolbar "Adjust Stock" button (showAdjustForm),
  // which adds its own product search so a product can be adjusted without scrolling to its row.
  // Only one of the two is ever open at a time (each open* method closes the other).
  readonly adjustingUid = signal<string | null>(null);
  /** Location of the row being adjusted (per-row entry point only) — sent so the right row is corrected. */
  readonly adjustLocationUid = signal<string | null>(null);
  readonly showAdjustForm = signal(false);
  readonly adjustProductQ = signal('');
  readonly adjustProductResults = signal<ProductModel[]>([]);
  readonly adjustSelectedProduct = signal<{ uid: string; label: string } | null>(null);
  /** 'delta' = signed +/- adjustment (existing behaviour); 'absolute' = "set to counted qty". */
  readonly adjustMode = signal<'delta' | 'absolute'>('delta');
  readonly adjustQty = signal('');
  /** Read-only display of the row's current on-hand quantity, used by absolute mode to compute the delta. */
  readonly adjustCurrentQty = signal('0');
  /** The user-entered NEW counted quantity in absolute mode. */
  readonly adjustNewQty = signal('');
  readonly adjustReason = signal<AdjustmentReason>('COUNT_CORRECTION');
  readonly adjustNote = signal('');
  readonly adjusting = signal(false);
  readonly adjustError = signal<string | null>(null);
  /** STK-08: the units the adjust quantity may be typed in (base first, then pack sizes). */
  readonly adjustUnits = signal<StockUnitOption[]>([]);
  /** '' = base unit. */
  readonly adjustUnitUid = signal('');
  /**
   * Toolbar entry point only: the locations that actually hold the picked product. With two or
   * more the user must say which shelf is being corrected (the server refuses to guess).
   */
  readonly adjustLocations = signal<{ uid: string; name: string; quantity: string }[]>([]);
  /** "= 48 Pieces" under the quantity when a pack size is chosen. */
  readonly adjustBasePreview = computed(() => {
    const units = this.adjustUnits();
    const factor = unitFactor(units, this.adjustUnitUid());
    if (factor === 1) return '';
    const raw = this.adjustMode() === 'absolute' ? this.adjustNewQty() : this.adjustQty();
    const n = Number(this.asStr(raw));
    if (!this.asStr(raw) || !Number.isFinite(n)) return '';
    return `= ${toBaseQty(n, factor)} ${units[0]?.name ?? ''}`.trim();
  });
  /** Current on-hand shown as "4 CTN + 7 PCS" when the product has a pack size. */
  readonly adjustCurrentBreakdown = computed(() => packBreakdown(this.adjustCurrentQty(), this.adjustUnits()));

  // ── Opening balance form ──────────────────────────────────────────────────────
  readonly showOpeningForm = signal(false);
  readonly openingProductQ = signal('');
  readonly openingProductResults = signal<ProductModel[]>([]);
  readonly openingSelectedProduct = signal<{ uid: string; label: string } | null>(null);
  readonly openingQty = signal('');
  readonly openingNote = signal('');
  readonly openingBusy = signal(false);
  readonly openingError = signal<string | null>(null);
  readonly openingUnits = signal<StockUnitOption[]>([]);
  readonly openingUnitUid = signal('');
  /** PRD-07 / LSF-09: cost of one {@link openingUnitUid}; blank = the product's own cost. */
  readonly openingUnitCost = signal('');
  readonly openingBasePreview = computed(() => {
    const units = this.openingUnits();
    const factor = unitFactor(units, this.openingUnitUid());
    const n = Number(this.asStr(this.openingQty()));
    if (factor === 1 || !this.asStr(this.openingQty()) || !Number.isFinite(n)) return '';
    return `= ${toBaseQty(n, factor)} ${units[0]?.name ?? ''}`.trim();
  });

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
  /** The opening cost posts DR Inventory / CR Opening Balance Equity, so it has its own permission. */
  readonly canOpeningCost = computed(() => this.session.hasPermission('INVENTORY.OPENING.SET'));
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
    this.branchesUnavailable.set(false);
    this.branchService.list(companyUid).subscribe({
      next: (list) => this.branches.set(list),
      error: () => { this.branches.set([]); this.branchesUnavailable.set(true); },
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
    const label = rowProductLabel(row);
    this.showAdjustForm.set(false); // mutual exclusion with the toolbar-triggered form
    this.adjustingUid.set(row.uid);
    this.adjustLocationUid.set(row.locationUid ?? null);
    this.adjustMode.set('delta');
    this.adjustCurrentQty.set(row.quantity);
    this.adjustNewQty.set('');
    this.adjustQty.set('');
    this.adjustReason.set('COUNT_CORRECTION');
    this.adjustNote.set('');
    this.adjustError.set(null);
    // Pre-populate the search box with the product label; uid resolved via targeted search.
    this.adjustProductQ.set(label);
    this.adjustSelectedProduct.set({ uid: '', label });
    this.adjustUnits.set([]);
    this.adjustUnitUid.set('');
    this.adjustLocations.set([]);
    // Resolve the product uid via a targeted code search (not a 200-cap full-fetch).
    this.resolveProductUidForRow(row.productCode, label);
  }

  /** Toolbar "Adjust Stock" entry point — same form, no pre-selected row; product picked via search. */
  toggleAdjustForm(): void {
    if (this.showAdjustForm()) {
      this.closeAdjustFormStandalone();
    } else {
      this.openAdjustFormStandalone();
    }
  }

  private openAdjustFormStandalone(): void {
    this.adjustingUid.set(null); // mutual exclusion with any per-row form
    this.adjustLocationUid.set(null);
    this.showAdjustForm.set(true);
    this.adjustMode.set('delta');
    this.adjustCurrentQty.set('0');
    this.adjustNewQty.set('');
    this.adjustQty.set('');
    this.adjustReason.set('COUNT_CORRECTION');
    this.adjustNote.set('');
    this.adjustError.set(null);
    this.adjustProductQ.set('');
    this.adjustSelectedProduct.set(null);
    this.adjustProductResults.set([]);
    this.adjustUnits.set([]);
    this.adjustUnitUid.set('');
    this.adjustLocations.set([]);
  }

  private closeAdjustFormStandalone(): void {
    this.showAdjustForm.set(false);
    this.adjustError.set(null);
  }

  setAdjustMode(mode: 'delta' | 'absolute'): void {
    this.adjustMode.set(mode);
    this.adjustError.set(null);
  }

  private resolveProductUidForRow(productCode: string, label: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.productService.list(companyId, productCode, 0, 10).subscribe({
      next: ({ rows }) => {
        const found = rows.find((p) => p.code === productCode);
        if (found) {
          this.adjustSelectedProduct.set({ uid: found.uid, label });
          this.loadAdjustUnits(found.uid);
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
    this.adjustUnits.set([]);
    this.adjustUnitUid.set('');
    this.adjustLocations.set([]);
    if (!this.adjustingUid()) this.adjustLocationUid.set(null);
    this.adjustProductSearch$.next(q);
  }

  /** Units for the picked product; the quantity stays in the base unit until the user picks a pack. */
  private loadAdjustUnits(productUid: string): void {
    this.unitOptions.load(productUid).subscribe({
      next: (units) => {
        if (this.adjustSelectedProduct()?.uid === productUid) this.adjustUnits.set(units);
      },
      error: () => this.adjustUnits.set([]),
    });
  }

  /** Toolbar location picker: correct THIS location, and compare against its own quantity. */
  onAdjustLocationChange(uid: string): void {
    this.adjustLocationUid.set(uid || null);
    const loc = this.adjustLocations().find((l) => l.uid === uid);
    if (loc) this.adjustCurrentQty.set(loc.quantity);
  }

  selectAdjustProduct(p: ProductModel): void {
    this.adjustSelectedProduct.set({ uid: p.uid, label: `${p.code} — ${p.name}` });
    this.adjustProductResults.set([]);
    this.adjustProductQ.set(`${p.code} — ${p.name}`);
    // Absolute mode needs the CURRENT on-hand qty to compute the delta — refresh it for whichever
    // product was just picked (the per-row entry point already knows it from the row; the toolbar
    // entry point does not, so this covers both).
    this.loadAdjustUnits(p.uid);
    this.refreshAdjustCurrentQty(p.code);
  }

  private refreshAdjustCurrentQty(productCode: string): void {
    this.stockService.listOnHand(productCode, 0, 10).subscribe({
      next: ({ rows }) => {
        // The server corrects the one location that holds the product, ignoring empty rows (a
        // received transfer leaves a zero row at In-Transit) — show that row's quantity.
        const mine = rows.filter((r) => r.productCode === productCode);
        const holding = mine.filter((r) => Number(r.quantity) !== 0);
        const found = holding.length === 1 ? holding[0] : mine[0];
        this.adjustCurrentQty.set(found?.quantity ?? '0');
        // Wave-1 carry-over: two real locations → the user chooses which one is being corrected.
        // In-transit rows are goods on the road (corrected by receiving them), never offered.
        const shelves = holding.filter((r) => !!r.locationUid && !/transit/i.test(r.locationName ?? ''));
        if (!this.adjustingUid() && shelves.length > 1) {
          this.adjustLocations.set(shelves.map((r) => ({
            uid: r.locationUid as string,
            name: r.locationName ?? r.locationUid as string,
            quantity: r.quantity,
          })));
          this.adjustLocationUid.set(null);
          this.adjustCurrentQty.set('0');
        } else {
          this.adjustLocations.set([]);
        }
      },
      error: () => this.adjustCurrentQty.set('0'),
    });
  }

  submitAdjust(): void {
    const selected = this.adjustSelectedProduct();
    if (!selected?.uid) { this.adjustError.set('Select a product.'); return; }

    let deltaStr: string;
    if (this.adjustMode() === 'absolute') {
      const newQty = this.asStr(this.adjustNewQty());
      if (!newQty || isNaN(Number(newQty)) || Number(newQty) < 0) {
        this.adjustError.set('Enter the counted quantity (zero or positive).');
        return;
      }
      const current = Number(this.adjustCurrentQty()) || 0;
      // The counted figure may be in a pack size; the current on-hand is always in base units.
      const factor = unitFactor(this.adjustUnits(), this.adjustUnitUid());
      const delta = Math.round((toBaseQty(Number(newQty), factor) - current) * 1e6) / 1e6;
      if (delta === 0) {
        this.adjustError.set('No change — the counted quantity matches the current on-hand quantity.');
        return;
      }
      deltaStr = String(delta);
    } else {
      const qty = this.asStr(this.adjustQty());
      if (!qty || isNaN(Number(qty)) || Number(qty) === 0) {
        this.adjustError.set('Enter a non-zero quantity (positive or negative).');
        return;
      }
      deltaStr = qty;
    }
    if (!this.adjustingUid() && this.adjustLocations().length > 1 && !this.adjustLocationUid()) {
      this.adjustError.set('This product is held at more than one location — choose the location to correct.');
      return;
    }

    this.adjusting.set(true);
    this.adjustError.set(null);
    const request: AdjustStockRequest = {
      productUid: selected.uid,
      quantity: deltaStr,
      reasonCode: this.adjustReason(),
      note: this.adjustNote().trim() || undefined,
    };
    const locationUid = this.adjustLocationUid();
    if (locationUid) request.locationUid = locationUid;
    // Delta mode states the quantity in the chosen unit and the server converts it; absolute mode
    // has already worked out the base-unit difference above, so it is sent as base.
    if (this.adjustMode() === 'delta' && this.adjustUnitUid()) request.unitUid = this.adjustUnitUid();
    this.stockService.adjust(request).subscribe({
      next: () => {
        this.adjusting.set(false);
        this.adjustingUid.set(null);
        this.showAdjustForm.set(false);
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
    this.openingUnits.set([]);
    this.openingUnitUid.set('');
    this.openingUnitCost.set('');
  }

  onOpeningProductSearchChange(q: string): void {
    this.openingProductQ.set(q);
    this.openingSelectedProduct.set(null);
    this.openingUnits.set([]);
    this.openingUnitUid.set('');
    this.openingProductSearch$.next(q);
  }

  selectOpeningProduct(p: ProductModel): void {
    this.openingSelectedProduct.set({ uid: p.uid, label: `${p.code} — ${p.name}` });
    this.openingProductResults.set([]);
    this.openingProductQ.set(`${p.code} — ${p.name}`);
    this.unitOptions.load(p.uid).subscribe({
      next: (units) => {
        if (this.openingSelectedProduct()?.uid === p.uid) this.openingUnits.set(units);
      },
      error: () => this.openingUnits.set([]),
    });
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
    const cost = this.canOpeningCost() ? this.asStr(this.openingUnitCost()) : '';
    if (cost && (isNaN(Number(cost)) || Number(cost) < 0)) {
      this.openingError.set('Unit cost must be zero or more (leave blank to use the product cost).');
      return;
    }
    this.openingBusy.set(true);
    this.openingError.set(null);
    const request: OpeningBalanceRequest = {
      productUid: selected.uid,
      quantity: qty,
      note: this.openingNote().trim() || undefined,
    };
    if (this.openingUnitUid()) request.unitUid = this.openingUnitUid();
    if (cost) request.unitCost = cost;
    this.stockService.openingBalance(request).subscribe({
      next: () => {
        this.openingBusy.set(false);
        this.showOpeningForm.set(false);
        this.resetOpeningForm();
        this.alerts.success('Opening balance recorded');
        this.load(this.currentPage());
      },
      error: (err) => {
        this.openingError.set(
          this.messageFrom(err, 'Could not record opening balance.'),
        );
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
    this.movementsProductLabel.set(rowProductLabel(row));
    this.movementsPage.set(0);
    this.loadMovements(row.productId, row.productCode, 0);
  }

  closeMovements(): void {
    this.movementsUid.set(null);
    this.movementRows.set([]);
  }

  private loadMovements(productId: string, productCode: string, page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    // productId is a Long id; we need productUid for the API.
    // Resolve via a targeted code search — not a 200-cap full-fetch.
    this.productService.list(companyId, productCode, 0, 10).subscribe({
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
    if (row) this.loadMovements(row.productId, row.productCode, page);
  }

  movementsPrevPage(): void {
    const p = this.movementsPage();
    if (p > 0) {
      const uid = this.movementsUid();
      if (!uid) return;
      const row = this.rows().find((r) => r.uid === uid);
      if (row) this.loadMovements(row.productId, row.productCode, p - 1);
    }
  }

  movementsNextPage(): void {
    if (this.movementMeta().hasNext) {
      const uid = this.movementsUid();
      if (!uid) return;
      const row = this.rows().find((r) => r.uid === uid);
      if (row) this.loadMovements(row.productId, row.productCode, this.movementsPage() + 1);
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

  private messageFrom(err: unknown, fallback: string, conflictMessage?: string): string {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 409 && conflictMessage) {
        return conflictMessage;
      }
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
