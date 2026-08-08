import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  computed,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Branch } from '../models/branch.model';
import { Company } from '../models/company.model';
import {
  AddBarcodeRequest,
  AssignProductBranchRequest,
  CreateBulkPackRequest,
  CreateProductRequest,
  Money,
  PriceListDto,
  ProductBarcodeDto,
  ProductBranchDto,
  ProductBulkPackDto,
  ProductModel,
  ProductPriceDto,
  ProductType,
  SetProductPriceRequest,
  SetProductWeighingRequest,
  UnitOfMeasureDto,
  UpdateProductRequest,
  VatStatus,
} from '../models/product.model';
import { SupplierModel } from '../models/party.model';
import { OpeningBalanceRequest } from '../models/stock.model';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from './product.service';
import { SupplierService } from '../parties/supplier.service';
import { StockService } from '../stock/stock.service';
import { CurrencySelectComponent } from '../../../shared/currency-select/currency-select.component';

// ── Save-sequence section status ──────────────────────────────────────────────

export type SectionStatus = 'idle' | 'pending' | 'saving' | 'done' | 'failed';

export interface SectionResult {
  label: string;
  status: SectionStatus;
  error: string | null;
}

// ── Barcode row (UI model) ─────────────────────────────────────────────────────

export interface BarcodeRow {
  /** Temp local id for tracking before save */
  localId: number;
  /** Set after save: uid returned by backend */
  savedUid?: string;
  barcode: string;
  barcodeType: string;
  uomUid: string;
  isPrimary: boolean;
}

// ── Bulk-pack row (UI model) ───────────────────────────────────────────────────

export interface BulkPackRow {
  localId: number;
  unitUid: string;
  factorToBase: string;
}

// ── Branch assignment row (UI model) ──────────────────────────────────────────

export interface BranchAssignRow {
  branch: Branch;
  active: boolean;
  reorderLevel: string;
  branchPrice: string;
}

// ── Price row (UI model) ───────────────────────────────────────────────────────

export interface PriceRow {
  priceListUid: string;
  amount: string;
  currency: string;
  effectiveFrom: string;
}

// ── Tab ids ───────────────────────────────────────────────────────────────────

export type TabId = 'general' | 'pricing' | 'supplier' | 'stock' | 'branches';

/**
 * Product Master — full product wizard screen.
 * Supports both create (/admin/products/master) and edit (/admin/products/master/uid/:uid).
 * Gated by PRODUCT.MANAGE.
 *
 * Save sequence:
 *   1. POST /products (anchor) or PUT /products/uid/{uid}
 *   2. POST /products/uid/{uid}/prices (per price row)
 *   3. POST /products/uid/{uid}/barcodes (per barcode row)
 *   4. POST /products/uid/{uid}/bulk-packs (per pack row)
 *   5. POST /products/uid/{uid}/branches (per branch assignment)
 *   6. POST /stock/opening-balances (if stockable + qty>0)
 *
 * Each sub-step is independent; failures surface per-section without losing the product.
 * If the anchor itself fails (e.g. duplicate code), that is surfaced too — formError() plus the
 * result panel (Product section marked failed, savedUid() stays null) — never a silent no-op
 * behind the global HTTP-error toast. "Continue editing" reopens the form to retry.
 * BR-PROD-01: SERVICE type forces stockable=false.
 */
@Component({
  selector: 'app-product-master',
  standalone: true,
  imports: [FormsModule, RouterLink, CurrencySelectComponent],
  templateUrl: './product-master.component.html',
  styleUrl: './product-master.component.scss',
})
export class ProductMasterComponent implements OnInit {
  // ── DI ────────────────────────────────────────────────────────────────────
  private readonly productService = inject(ProductService);
  private readonly branchService = inject(BranchService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly supplierService = inject(SupplierService);
  private readonly stockService = inject(StockService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  // ── Route param: present only in edit mode ────────────────────────────────
  readonly uid = input<string | undefined>(undefined);

  readonly isEdit = computed(() => !!this.uid());
  readonly canManage = computed(() => this.session.hasPermission('PRODUCT.MANAGE'));

  // ── Active tab ────────────────────────────────────────────────────────────
  readonly activeTab = signal<TabId>('general');

  // ── Company / session context ─────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = computed(
    () => this.companies().find((c) => c.id === this.selectedCompanyId())?.uid ?? '',
  );

  // ── Loaded product (edit mode) ────────────────────────────────────────────
  readonly product = signal<ProductModel | null>(null);
  readonly productLoadState = signal<'loading' | 'idle' | 'error'>('idle');

  /** uid known after create; anchors all sub-resource calls. */
  readonly savedUid = signal<string | null>(null);

  // ── Tab 1: General ────────────────────────────────────────────────────────
  readonly fCode = signal('');
  readonly fName = signal('');
  readonly fDescription = signal('');
  readonly fType = signal<ProductType>('GOODS');
  readonly fCategory = signal('');
  readonly fBrand = signal('');
  readonly fManufacturer = signal('');
  readonly fSellable = signal(true);
  readonly fStockable = signal(true);
  readonly fPurchasable = signal(true);
  readonly fVatStatus = signal<VatStatus>('STANDARD');
  readonly fHsCode = signal('');
  readonly fImageUrl = signal('');
  readonly fNotes = signal('');
  readonly fLotTracked = signal(false);
  readonly fSerialTracked = signal(false);
  readonly fExpiryTracked = signal(false);

  // BR-PROD-01: SERVICE cannot be stockable.
  readonly stockableDisabled = computed(() => this.fType() === 'SERVICE');

  // ── Tab 2: Pricing ────────────────────────────────────────────────────────
  readonly fCostAmount = signal('');
  readonly fCostCurrency = signal('TZS');
  readonly priceRows = signal<PriceRow[]>([]);
  readonly priceLists = signal<PriceListDto[]>([]);
  readonly priceListsState = signal<'loading' | 'idle' | 'error'>('idle');
  readonly defaultPriceListUid = computed(() => {
    const lists = this.priceLists();
    if (lists.length === 0) return '';
    // Resolution order:
    // 1. The list the backend marks as isDefault=true (company-configured default).
    // 2. Fallback: a list whose code matches 'DEFAULT' or 'STANDARD'.
    // 3. Fallback: the only list when exactly one exists.
    // 4. Otherwise empty — the user must pick.
    const byFlag = lists.find((pl) => pl.isDefault === true);
    if (byFlag) return byFlag.uid;
    const byCode = lists.find(
      (pl) =>
        pl.code?.toUpperCase() === 'DEFAULT' ||
        pl.code?.toUpperCase() === 'STANDARD',
    );
    if (byCode) return byCode.uid;
    if (lists.length === 1) return lists[0].uid;
    return '';
  });

  // ── Tab 3: Supplier & UoM ─────────────────────────────────────────────────
  readonly companyUnits = signal<UnitOfMeasureDto[]>([]);
  readonly unitsState = signal<'loading' | 'idle' | 'error'>('idle');
  readonly fBaseUnitUid = signal('');
  readonly bulkPackRows = signal<BulkPackRow[]>([]);
  readonly newBulkPackUnitUid = signal('');
  readonly newBulkPackFactor = signal('');

  // Weighed goods (ADR-0044 D-1b) — set via a post-create sub-resource call (setWeighing),
  // like the other sub-resources below; needs the product uid, so it can't be part of the
  // create/update anchor request.
  readonly fWeighed = signal(false);
  readonly fTareWeight = signal('');
  readonly fScaleStep = signal('');
  readonly fMaxSaleWeight = signal('');
  /**
   * Client-side pre-gate: the server requires a WEIGHT base unit to mark a product weighed
   * (still enforced as a backstop by setWeighing's own error handling), but disabling the
   * toggle up-front is friendlier than a round-trip rejection after the product is created.
   */
  readonly selectedBaseUnit = computed(() =>
    this.companyUnits().find((u) => u.uid === this.fBaseUnitUid()),
  );
  readonly baseUnitIsWeight = computed(() => this.selectedBaseUnit()?.dimensionType === 'WEIGHT');

  // ── Pack-factor guardrails (K4) ────────────────────────────────────────────
  // Same rules as the product-detail screen, and for the same reason: this one number decides how a
  // carton purchase is valued and how a carton sale is taken out of stock. State the direction in
  // words, echo the result, question anything below 1 — and never block, because a 0.5 kg pack of a
  // kg-based product is legitimate.

  /** The chosen base unit as a human label — the unit every pack size must be expressed IN. */
  readonly baseUnitLabel = computed(() => {
    const unit = this.selectedBaseUnit();
    if (unit) return unit.name || unit.code;
    return this.product()?.baseUnitName || this.product()?.baseUnitCode || 'base unit';
  });

  readonly newBulkPackUnit = computed(() =>
    this.companyUnits().find((u) => u.uid === this.newBulkPackUnitUid()),
  );
  readonly newBulkPackUnitLabel = computed(() => {
    const unit = this.newBulkPackUnit();
    return unit ? unit.name || unit.code : 'pack';
  });

  readonly newBulkPackFactorValue = computed<number | null>(() => {
    const raw = String(this.newBulkPackFactor() ?? '').trim();
    if (!raw) return null;
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : null;
  });

  readonly bulkPackFactorInvalid = computed(() => {
    const raw = String(this.newBulkPackFactor() ?? '').trim();
    if (!raw) return false;
    const value = this.newBulkPackFactorValue();
    return value === null || value <= 0;
  });

  /** SOFT warning only — a pack smaller than the base unit is unusual, not illegal. */
  readonly bulkPackFactorBelowOne = computed(() => {
    const value = this.newBulkPackFactorValue();
    return value !== null && value > 0 && value < 1;
  });

  /** "1 Carton = 12 Pieces" — the entered factor read back so a reversed entry is obvious. */
  readonly bulkPackSizeInWords = computed(() => {
    const value = this.newBulkPackFactorValue();
    if (value === null || value <= 0) return '';
    return `1 ${this.newBulkPackUnitLabel()} = ${this.trimNumber(value)} ${this.baseUnitLabel()}`;
  });

  /** Inline validation message for the add-pack row; cleared on every successful add. */
  readonly bulkPackRowError = signal<string | null>(null);

  // ── Base-unit change guard (K4, edit mode only) ────────────────────────────
  /** The base unit the product was loaded with — the yardstick every stored quantity/cost is in. */
  private readonly loadedBaseUnitUid = signal('');
  readonly loadedBaseUnitLabel = computed(() => {
    const unit = this.companyUnits().find((u) => u.uid === this.loadedBaseUnitUid());
    if (unit) return unit.name || unit.code;
    return this.product()?.baseUnitName || this.product()?.baseUnitCode || 'the previous unit';
  });
  /** True once the user has moved an EXISTING product's base-unit picker off its saved value. */
  readonly baseUnitChanged = computed(
    () => !!this.loadedBaseUnitUid() && this.fBaseUnitUid() !== this.loadedBaseUnitUid(),
  );

  // Supplier search
  readonly supplierSearchQ = signal('');
  readonly supplierResults = signal<SupplierModel[]>([]);
  readonly supplierSearchState = signal<'idle' | 'loading' | 'done' | 'error'>('idle');
  readonly selectedSupplier = signal<SupplierModel | null>(null);
  readonly fPreferredSupplierId = signal('');

  // Reorder / planning
  readonly fReorderLevel = signal('');
  readonly fReorderQty = signal('');
  readonly fSafetyStock = signal('');
  readonly fMinStock = signal('');
  readonly fMaxStock = signal('');

  // ── Tab 4: Stock & Barcodes ───────────────────────────────────────────────
  readonly barcodeRows = signal<BarcodeRow[]>([]);
  private barcodeLocalIdSeq = 0;
  readonly newBarcodeValue = signal('');
  readonly newBarcodeBarcodeType = signal('');
  readonly newBarcodeUomUid = signal('');
  readonly newBarcodePrimary = signal(false);
  readonly openingQty = signal('');
  readonly openingNote = signal('');
  readonly activeBranchUid = computed(() => this.session.activeBranchUid() ?? '');

  // ── Tab 5: Branches ────────────────────────────────────────────────────────
  readonly companyBranches = signal<Branch[]>([]);
  readonly branchesState = signal<'loading' | 'idle' | 'error'>('idle');
  readonly allBranchesToggle = signal(true);
  readonly branchRows = signal<BranchAssignRow[]>([]);

  // ── Save orchestration ────────────────────────────────────────────────────
  readonly saving = signal(false);
  readonly saveComplete = signal(false);
  readonly formError = signal<string | null>(null);
  readonly sectionResults = signal<SectionResult[]>([]);

  // ── Local id sequence for bulk packs ─────────────────────────────────────
  private bulkLocalIdSeq = 0;

  // ── Derived ───────────────────────────────────────────────────────────────
  readonly hasAnyFailure = computed(() =>
    this.sectionResults().some((r) => r.status === 'failed'),
  );
  readonly hasAllDone = computed(() => {
    const results = this.sectionResults();
    return results.length > 0 && results.every((r) => r.status === 'done');
  });
  /** True when the anchor (product create/update) step itself failed — nothing was saved,
   *  savedUid() is still null, and the sub-steps never ran. */
  readonly anchorFailed = computed(() => this.sectionResults()[0]?.status === 'failed');

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  ngOnInit(): void {
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
              const co = list[0];
              this.selectedCompanyId.set(co.id);
              this.loadDependencies(co.id, co.uid);
            }
            // In edit mode, load the product after companies are known.
            const editUid = this.uid();
            if (editUid) {
              this.loadProduct(editUid);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadDependencies(companyId: string, companyUid: string): void {
    this.loadUnits(companyId);
    this.loadPriceLists(companyId);
    this.loadBranches(companyUid);
  }

  private loadUnits(companyId: string): void {
    this.unitsState.set('loading');
    this.productService.listUnits(companyId).subscribe({
      next: ({ rows }) => {
        const active = rows.filter((u) => u.status === 'ACTIVE');
        this.companyUnits.set(active);
        this.unitsState.set('idle');
        // Auto-preselect if exactly one active unit (create mode).
        if (!this.isEdit() && active.length === 1 && !this.fBaseUnitUid()) {
          this.fBaseUnitUid.set(active[0].uid);
        }
      },
      error: () => this.unitsState.set('error'),
    });
  }

  private loadPriceLists(companyId: string): void {
    this.priceListsState.set('loading');
    this.productService.listPriceLists(companyId).subscribe({
      next: (rows) => {
        this.priceLists.set(rows.filter((pl) => pl.status === 'ACTIVE' || !pl.status));
        this.priceListsState.set('idle');
        // Initialise a default price row if none yet.
        if (!this.isEdit() && this.priceRows().length === 0) {
          this.addDefaultPriceRow();
        }
      },
      error: () => this.priceListsState.set('error'),
    });
  }

  private addDefaultPriceRow(): void {
    const uid = this.defaultPriceListUid();
    this.priceRows.set([
      {
        priceListUid: uid,
        amount: '',
        currency: this.fCostCurrency(),
        effectiveFrom: this.todayIso(),
      },
    ]);
  }

  private loadBranches(companyUid: string): void {
    this.branchesState.set('loading');
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.companyBranches.set(rows);
        this.branchesState.set('idle');
        if (!this.isEdit()) {
          // Initialise all-branches rows.
          this.branchRows.set(
            rows.map((b) => ({
              branch: b,
              active: true,
              reorderLevel: '',
              branchPrice: '',
            })),
          );
        }
      },
      error: () => this.branchesState.set('error'),
    });
  }

  private loadProduct(editUid: string): void {
    this.productLoadState.set('loading');
    this.productService.getByUid(editUid).subscribe({
      next: (p) => {
        this.product.set(p);
        this.savedUid.set(p.uid);
        this.productLoadState.set('idle');
        this.patchFromProduct(p);
        // Scope company to the product's company.
        const matchCo = this.companies().find((c) => c.id === p.companyId);
        if (matchCo) {
          this.selectedCompanyId.set(matchCo.id);
          this.loadDependencies(matchCo.id, matchCo.uid);
        }
        // Load sub-resources.
        this.loadProductSubResources(editUid);
      },
      error: () => this.productLoadState.set('error'),
    });
  }

  private loadProductSubResources(editUid: string): void {
    this.productService.listBarcodes(editUid).subscribe({
      next: (rows) => {
        this.barcodeRows.set(
          rows.map((b) => ({
            localId: ++this.barcodeLocalIdSeq,
            savedUid: b.uid,
            barcode: b.barcode,
            barcodeType: '',
            uomUid: '',
            isPrimary: b.primary,
          })),
        );
      },
      error: () => undefined,
    });

    this.productService.listBulkPacks(editUid).subscribe({
      next: (rows) => {
        this.bulkPackRows.set(
          rows.map((bp) => ({
            localId: ++this.bulkLocalIdSeq,
            unitUid: bp.unitUid,
            factorToBase: bp.factorToBase,
          })),
        );
      },
      error: () => undefined,
    });

    this.productService.listPrices(editUid).subscribe({
      next: (rows) => {
        if (rows.length > 0) {
          this.priceRows.set(
            rows.map((pr) => ({
              priceListUid: pr.priceListUid,
              amount: pr.price.amount,
              currency: pr.price.currency,
              effectiveFrom: this.todayIso(),
            })),
          );
        }
      },
      error: () => undefined,
    });

    this.productService.listBranches(editUid).subscribe({
      next: (rows) => {
        // Merge with companyBranches.
        const branchIdSet = new Set(rows.map((r) => r.branchId));
        this.branchRows.set(
          this.companyBranches().map((b) => ({
            branch: b,
            active: branchIdSet.has(b.id),
            reorderLevel: '',
            branchPrice: '',
          })),
        );
        if (branchIdSet.size > 0) {
          this.allBranchesToggle.set(false);
        }
      },
      error: () => undefined,
    });
  }

  private patchFromProduct(p: ProductModel): void {
    this.fCode.set(p.code ?? '');
    this.fName.set(p.name ?? '');
    this.fDescription.set(p.description ?? '');
    this.fType.set(p.type);
    this.fCategory.set(p.category ?? '');
    this.fBrand.set(p.brand ?? '');
    this.fManufacturer.set(p.manufacturer ?? '');
    this.fSellable.set(p.sellable);
    this.fStockable.set(p.stockable);
    this.fPurchasable.set(p.purchasable ?? true);
    this.fVatStatus.set(p.vatStatus ?? 'STANDARD');
    this.fHsCode.set(p.hsCode ?? '');
    this.fImageUrl.set(p.imageUrl ?? '');
    this.fNotes.set(p.notes ?? '');
    this.fLotTracked.set(p.lotTracked ?? false);
    this.fSerialTracked.set(p.serialTracked ?? false);
    this.fExpiryTracked.set(p.expiryTracked ?? false);
    this.fCostAmount.set(p.cost?.amount ?? '');
    this.fCostCurrency.set(p.cost?.currency ?? 'TZS');
    this.fBaseUnitUid.set(p.baseUnitUid ?? '');
    this.loadedBaseUnitUid.set(p.baseUnitUid ?? '');
    this.fReorderLevel.set(p.reorderLevel ?? '');
    this.fReorderQty.set(p.reorderQty ?? '');
    this.fSafetyStock.set(p.safetyStock ?? '');
    this.fMinStock.set(p.minStock ?? '');
    this.fMaxStock.set(p.maxStock ?? '');
    if (p.preferredSupplierId) {
      this.fPreferredSupplierId.set(p.preferredSupplierId);
    }
    // ADR-0044 D-1b: tare/scaleStep/maxSaleWeight are BigDecimal on the wire (JSON numbers), same
    // as reorderLevel/weight — String()-coerce so a later .trim() on the signal never throws
    // (wire-number-vs-string gotcha).
    this.fWeighed.set(p.weighed ?? false);
    this.fTareWeight.set(p.tareWeight != null ? String(p.tareWeight) : '');
    this.fScaleStep.set(p.scaleStep != null ? String(p.scaleStep) : '');
    this.fMaxSaleWeight.set(p.maxSaleWeight != null ? String(p.maxSaleWeight) : '');
  }

  // ── Company change ────────────────────────────────────────────────────────

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    const co = this.companies().find((c) => c.id === id);
    if (co) {
      this.loadDependencies(id, co.uid);
    }
    this.fBaseUnitUid.set('');
    // Units belong to a company: the old baseline is meaningless here, so drop it rather than let
    // it fire a "you changed the base unit" warning about a unit from another company.
    this.loadedBaseUnitUid.set('');
    // The base unit picker is being reset — a stale "weighed" can't be validated against it.
    this.fWeighed.set(false);
    this.priceRows.set([]);
    this.branchRows.set([]);
  }

  // ── Base unit change ─────────────────────────────────────────────────────────

  /** Moving off a weight base unit while "Sold by weight" is on must never leave an invalid combination. */
  onBaseUnitChange(unitUid: string): void {
    this.fBaseUnitUid.set(unitUid);
    if (this.fWeighed() && !this.baseUnitIsWeight()) {
      this.fWeighed.set(false);
    }
  }

  /** Backstop against the (disabled) toggle being flipped on for a non-weight base unit. */
  onWeighedToggle(on: boolean): void {
    this.fWeighed.set(on && this.baseUnitIsWeight());
  }

  // ── Type change ───────────────────────────────────────────────────────────

  onTypeChange(t: ProductType): void {
    this.fType.set(t);
    // BR-PROD-01
    if (t === 'SERVICE') {
      this.fStockable.set(false);
    }
  }

  // ── Tab nav ───────────────────────────────────────────────────────────────

  setTab(tab: TabId): void {
    this.activeTab.set(tab);
  }

  // ── Barcodes ─────────────────────────────────────────────────────────────

  addBarcodeRow(): void {
    const val = this.newBarcodeValue().trim();
    if (!val) return;
    const rows = this.barcodeRows();
    const isPrimary = this.newBarcodePrimary() || rows.length === 0;
    const row: BarcodeRow = {
      localId: ++this.barcodeLocalIdSeq,
      barcode: val,
      barcodeType: this.newBarcodeBarcodeType().trim(),
      uomUid: this.newBarcodeUomUid().trim(),
      isPrimary,
    };
    // Ensure only one primary.
    if (isPrimary) {
      this.barcodeRows.set([...rows.map((r) => ({ ...r, isPrimary: false })), row]);
    } else {
      this.barcodeRows.update((r) => [...r, row]);
    }
    this.newBarcodeValue.set('');
    this.newBarcodeBarcodeType.set('');
    this.newBarcodeUomUid.set('');
    this.newBarcodePrimary.set(false);
  }

  removeBarcodeRow(localId: number): void {
    this.barcodeRows.update((rows) => rows.filter((r) => r.localId !== localId));
  }

  setPrimaryBarcode(localId: number): void {
    this.barcodeRows.update((rows) =>
      rows.map((r) => ({ ...r, isPrimary: r.localId === localId })),
    );
  }

  // ── Bulk packs ────────────────────────────────────────────────────────────

  addBulkPackRow(): void {
    const unitUid = String(this.newBulkPackUnitUid() ?? '').trim();
    // ngModel on a type="number" input hands back a JS number (or null) — coerce before trimming.
    const factor = String(this.newBulkPackFactor() ?? '').trim();
    if (!unitUid) {
      this.bulkPackRowError.set('Choose the pack unit first.');
      return;
    }
    if (!factor) {
      this.bulkPackRowError.set(
        `Enter how many ${this.baseUnitLabel()} make one ${this.newBulkPackUnitLabel()}.`,
      );
      return;
    }
    const factorValue = Number(factor);
    if (!Number.isFinite(factorValue) || factorValue <= 0) {
      this.bulkPackRowError.set('The pack size must be a number greater than zero.');
      return;
    }
    this.bulkPackRowError.set(null);
    this.bulkPackRows.update((rows) => [
      ...rows,
      { localId: ++this.bulkLocalIdSeq, unitUid, factorToBase: factor },
    ]);
    this.newBulkPackUnitUid.set('');
    this.newBulkPackFactor.set('');
  }

  removeBulkPackRow(localId: number): void {
    this.bulkPackRows.update((rows) => rows.filter((r) => r.localId !== localId));
  }

  // ── Prices ────────────────────────────────────────────────────────────────

  addPriceRow(): void {
    this.priceRows.update((rows) => [
      ...rows,
      {
        priceListUid: this.defaultPriceListUid(),
        amount: '',
        currency: this.fCostCurrency() || 'TZS',
        effectiveFrom: this.todayIso(),
      },
    ]);
  }

  removePriceRow(idx: number): void {
    this.priceRows.update((rows) => rows.filter((_, i) => i !== idx));
  }

  updatePriceRow(idx: number, patch: Partial<PriceRow>): void {
    this.priceRows.update((rows) =>
      rows.map((r, i) => (i === idx ? { ...r, ...patch } : r)),
    );
  }

  // ── Branches ─────────────────────────────────────────────────────────────

  onAllBranchesToggleChange(on: boolean): void {
    this.allBranchesToggle.set(on);
    if (on) {
      this.branchRows.update((rows) => rows.map((r) => ({ ...r, active: true })));
    }
  }

  updateBranchRow(idx: number, patch: Partial<BranchAssignRow>): void {
    this.branchRows.update((rows) =>
      rows.map((r, i) => (i === idx ? { ...r, ...patch } : r)),
    );
    // If any branch is deselected, turn off the "all branches" toggle.
    if (patch.active === false) {
      this.allBranchesToggle.set(false);
    }
  }

  // ── Supplier search ───────────────────────────────────────────────────────

  searchSuppliers(): void {
    const q = this.supplierSearchQ().trim();
    const companyId = this.selectedCompanyId();
    if (!q || !companyId) return;
    this.supplierSearchState.set('loading');
    this.supplierService.list(companyId, q, 0, 20).subscribe({
      next: ({ rows }) => {
        this.supplierResults.set(rows);
        this.supplierSearchState.set('done');
      },
      error: () => {
        this.supplierResults.set([]);
        this.supplierSearchState.set('error');
      },
    });
  }

  selectSupplier(s: SupplierModel): void {
    this.selectedSupplier.set(s);
    this.fPreferredSupplierId.set(s.id);
    this.supplierSearchQ.set(s.displayName);
    this.supplierResults.set([]);
    this.supplierSearchState.set('idle');
  }

  clearSupplier(): void {
    this.selectedSupplier.set(null);
    this.fPreferredSupplierId.set('');
    this.supplierSearchQ.set('');
    this.supplierResults.set([]);
    this.supplierSearchState.set('idle');
  }

  // ── Save orchestration ─────────────────────────────────────────────────────

  /**
   * Validates required fields then runs the save sequence:
   * 1. Anchor (create/update product)
   * 2. Selling prices
   * 3. Barcodes
   * 4. Bulk packs
   * 5. Branch assignments
   * 6. Opening stock
   */
  save(): void {
    if (this.saving()) return;

    const name = this.fName().trim();
    const baseUnitUid = this.fBaseUnitUid();
    const companyId = this.selectedCompanyId();
    const company = this.companies().find((c) => c.id === companyId);

    if (!name) {
      this.formError.set('Name is required.');
      this.setTab('general');
      return;
    }
    if (!baseUnitUid) {
      this.formError.set('Base unit is required.');
      this.setTab('supplier');
      return;
    }
    if (!company) {
      this.formError.set('Select a company.');
      return;
    }

    // Validate exactly one primary barcode if rows exist.
    const barcodes = this.barcodeRows();
    if (barcodes.length > 0 && !barcodes.some((b) => b.isPrimary)) {
      this.formError.set('One barcode must be marked as primary.');
      this.setTab('stock');
      return;
    }

    this.formError.set(null);
    this.saving.set(true);
    this.saveComplete.set(false);

    const sections: SectionResult[] = [
      { label: 'Product', status: 'pending', error: null },
      { label: 'Selling prices', status: 'pending', error: null },
      { label: 'Barcodes', status: 'pending', error: null },
      { label: 'Pack units', status: 'pending', error: null },
      { label: 'Branch availability', status: 'pending', error: null },
      { label: 'Opening stock', status: 'pending', error: null },
      { label: 'Weighed goods', status: 'pending', error: null },
    ];
    this.sectionResults.set(sections);

    const cost: Money | undefined = this.fCostAmount().trim()
      ? { amount: this.fCostAmount().trim(), currency: this.fCostCurrency() || 'TZS' }
      : undefined;

    const isService = this.fType() === 'SERVICE';

    // Step 1: Create or update.
    this.setSection(0, 'saving');

    const anchor$ = this.isEdit()
      ? this.buildUpdateRequest(baseUnitUid, cost, isService)
      : this.buildCreateRequest(company.uid, baseUnitUid, cost, isService);

    anchor$.subscribe({
      next: (saved) => {
        this.savedUid.set(saved.uid);
        this.product.set(saved);
        this.setSection(0, 'done');
        this.runSubSteps(saved.uid);
      },
      error: (err) => {
        // The anchor is the product itself — nothing was saved. Surface this clearly on-screen
        // (both the inline form banner and the result panel below) rather than relying on the
        // global HTTP-error toast/modal, which otherwise makes the Save button look inert.
        const msg = this.messageFrom(err, 'Could not save the product.');
        this.setSection(0, 'failed', msg);
        this.formError.set(msg);
        this.saveComplete.set(true);
        this.saving.set(false);
      },
    });
  }

  private buildCreateRequest(
    companyUid: string,
    baseUnitUid: string,
    cost: Money | undefined,
    isService: boolean,
  ) {
    const req: CreateProductRequest = {
      companyUid,
      code: this.fCode().trim() || undefined,
      name: this.fName().trim(),
      description: this.fDescription().trim() || undefined,
      type: this.fType(),
      sellable: this.fSellable(),
      stockable: isService ? false : this.fStockable(),
      purchasable: this.fPurchasable(),
      baseUnitUid,
      cost,
      vatStatus: this.fVatStatus(),
      category: this.fCategory().trim() || undefined,
      brand: this.fBrand().trim() || undefined,
      manufacturer: this.fManufacturer().trim() || undefined,
      preferredSupplierId: this.fPreferredSupplierId() || undefined,
      hsCode: this.fHsCode().trim() || undefined,
      imageUrl: this.fImageUrl().trim() || undefined,
      notes: this.fNotes().trim() || undefined,
      lotTracked: this.fLotTracked() || undefined,
      serialTracked: this.fSerialTracked() || undefined,
      expiryTracked: this.fExpiryTracked() || undefined,
      reorderLevel: this.fReorderLevel().trim() || undefined,
      reorderQty: this.fReorderQty().trim() || undefined,
      safetyStock: this.fSafetyStock().trim() || undefined,
      minStock: this.fMinStock().trim() || undefined,
      maxStock: this.fMaxStock().trim() || undefined,
    };
    return this.productService.create(req);
  }

  private buildUpdateRequest(
    baseUnitUid: string,
    cost: Money | undefined,
    isService: boolean,
  ) {
    const req: UpdateProductRequest = {
      name: this.fName().trim(),
      description: this.fDescription().trim() || undefined,
      type: this.fType(),
      sellable: this.fSellable(),
      stockable: isService ? false : this.fStockable(),
      purchasable: this.fPurchasable(),
      baseUnitUid,
      cost,
      vatStatus: this.fVatStatus(),
      category: this.fCategory().trim() || undefined,
      brand: this.fBrand().trim() || undefined,
      manufacturer: this.fManufacturer().trim() || undefined,
      preferredSupplierId: this.fPreferredSupplierId() || undefined,
      hsCode: this.fHsCode().trim() || undefined,
      imageUrl: this.fImageUrl().trim() || undefined,
      notes: this.fNotes().trim() || undefined,
      lotTracked: this.fLotTracked() || undefined,
      serialTracked: this.fSerialTracked() || undefined,
      expiryTracked: this.fExpiryTracked() || undefined,
      reorderLevel: this.fReorderLevel().trim() || undefined,
      reorderQty: this.fReorderQty().trim() || undefined,
      safetyStock: this.fSafetyStock().trim() || undefined,
      minStock: this.fMinStock().trim() || undefined,
      maxStock: this.fMaxStock().trim() || undefined,
    };
    return this.productService.update(this.uid()!, req);
  }

  private runSubSteps(productUid: string): void {
    this.runPrices(productUid)
      .then(() => this.runBarcodes(productUid))
      .then(() => this.runBulkPacks(productUid))
      .then(() => this.runBranches(productUid))
      .then(() => this.runOpeningStock(productUid))
      .then(() => this.runWeighing(productUid))
      .finally(() => {
        this.saving.set(false);
        this.saveComplete.set(true);
      });
  }

  // ── Sub-step: prices ──────────────────────────────────────────────────────

  private runPrices(productUid: string): Promise<void> {
    const rows = this.priceRows().filter((r) => r.amount.trim() && r.priceListUid);
    if (rows.length === 0) {
      this.setSection(1, 'done');
      return Promise.resolve();
    }
    this.setSection(1, 'saving');
    return new Promise((resolve) => {
      // Sequential to avoid race on the same price-list.
      const runNext = (idx: number) => {
        if (idx >= rows.length) {
          this.setSection(1, 'done');
          resolve();
          return;
        }
        const row = rows[idx];
        const req: SetProductPriceRequest = {
          priceListUid: row.priceListUid,
          price: { amount: row.amount.trim(), currency: row.currency || 'TZS' },
          effectiveFrom: row.effectiveFrom || this.todayIso(),
        };
        this.productService.setPrice(productUid, req).subscribe({
          next: () => runNext(idx + 1),
          error: (err) => {
            this.setSection(1, 'failed', this.messageFrom(err, 'Could not set selling price.'));
            resolve();
          },
        });
      };
      runNext(0);
    });
  }

  // ── Sub-step: barcodes ────────────────────────────────────────────────────

  private runBarcodes(productUid: string): Promise<void> {
    const rows = this.barcodeRows().filter((r) => r.barcode.trim());
    if (rows.length === 0) {
      this.setSection(2, 'done');
      return Promise.resolve();
    }
    this.setSection(2, 'saving');
    return new Promise((resolve) => {
      const runNext = (idx: number) => {
        if (idx >= rows.length) {
          this.setSection(2, 'done');
          resolve();
          return;
        }
        const row = rows[idx];
        const req: AddBarcodeRequest = {
          barcode: row.barcode,
          isPrimary: row.isPrimary,
          primary: row.isPrimary, // compat
          barcodeType: row.barcodeType || undefined,
          uomUid: row.uomUid || undefined,
        };
        this.productService.addBarcode(productUid, req).subscribe({
          next: () => runNext(idx + 1),
          error: (err) => {
            this.setSection(2, 'failed', this.messageFrom(err, 'Could not add barcode.'));
            resolve();
          },
        });
      };
      runNext(0);
    });
  }

  // ── Sub-step: bulk packs ──────────────────────────────────────────────────

  private runBulkPacks(productUid: string): Promise<void> {
    const rows = this.bulkPackRows();
    if (rows.length === 0) {
      this.setSection(3, 'done');
      return Promise.resolve();
    }
    this.setSection(3, 'saving');
    return new Promise((resolve) => {
      const runNext = (idx: number) => {
        if (idx >= rows.length) {
          this.setSection(3, 'done');
          resolve();
          return;
        }
        const row = rows[idx];
        const req: CreateBulkPackRequest = {
          unitUid: row.unitUid,
          factorToBase: row.factorToBase,
        };
        this.productService.addBulkPack(productUid, req).subscribe({
          next: () => runNext(idx + 1),
          error: (err) => {
            this.setSection(3, 'failed', this.messageFrom(err, 'Could not add pack unit.'));
            resolve();
          },
        });
      };
      runNext(0);
    });
  }

  // ── Sub-step: branches ────────────────────────────────────────────────────

  private runBranches(productUid: string): Promise<void> {
    const rows = this.branchRows();
    const toAssign = this.allBranchesToggle()
      ? rows
      : rows.filter((r) => r.active);

    if (toAssign.length === 0) {
      this.setSection(4, 'done');
      return Promise.resolve();
    }
    this.setSection(4, 'saving');
    return new Promise((resolve) => {
      const runNext = (idx: number) => {
        if (idx >= toAssign.length) {
          this.setSection(4, 'done');
          resolve();
          return;
        }
        const row = toAssign[idx];
        const req: AssignProductBranchRequest = {
          branchUid: row.branch.uid,
          active: row.active,
          reorderLevel: row.reorderLevel.trim() || undefined,
          branchPrice: row.branchPrice.trim() || undefined,
        };
        this.productService.assignBranch(productUid, req).subscribe({
          next: () => runNext(idx + 1),
          error: (err) => {
            this.setSection(4, 'failed', this.messageFrom(err, 'Could not assign branch.'));
            resolve();
          },
        });
      };
      runNext(0);
    });
  }

  // ── Sub-step: opening stock ────────────────────────────────────────────────

  private runOpeningStock(productUid: string): Promise<void> {
    const isStockable = this.fType() === 'SERVICE' ? false : this.fStockable();
    const qty = this.openingQty().trim();
    if (!isStockable || !qty || +qty <= 0) {
      this.setSection(5, 'done');
      return Promise.resolve();
    }
    if (!this.activeBranchUid()) {
      this.setSection(
        5,
        'failed',
        'No active branch set — opening stock was not seeded. Switch branch and retry.',
      );
      return Promise.resolve();
    }
    this.setSection(5, 'saving');
    const req: OpeningBalanceRequest = {
      productUid,
      quantity: qty,
      note: this.openingNote().trim() || undefined,
    };
    return new Promise((resolve) => {
      this.stockService.openingBalance(req).subscribe({
        next: () => {
          this.setSection(5, 'done');
          resolve();
        },
        error: (err) => {
          this.setSection(5, 'failed', this.messageFrom(err, 'Could not seed opening stock.'));
          resolve();
        },
      });
    });
  }

  // ── Sub-step: weighed goods (ADR-0044 D-1b) ──────────────────────────────────

  /**
   * Chained after the product exists (needs its uid). Only calls setWeighing when "Sold by
   * weight" is on — skipped (section 'done', no request) otherwise so a plain product isn't
   * touched by a weighing call at all. A rejection here (e.g. base unit isn't a weight unit)
   * only fails this section — the product itself is already saved, so the result panel shows
   * "Product saved. Some details need attention." rather than implying nothing was saved.
   */
  private runWeighing(productUid: string): Promise<void> {
    if (!this.fWeighed()) {
      this.setSection(6, 'done');
      return Promise.resolve();
    }
    this.setSection(6, 'saving');
    const req: SetProductWeighingRequest = {
      weighed: true,
      tareWeight: this.fTareWeight().trim() || null,
      scaleStep: this.fScaleStep().trim() || null,
      maxSaleWeight: this.fMaxSaleWeight().trim() || null,
    };
    return new Promise((resolve) => {
      this.productService.setWeighing(productUid, req).subscribe({
        next: (updated) => {
          this.product.set(updated);
          this.setSection(6, 'done');
          resolve();
        },
        error: (err) => {
          this.setSection(6, 'failed', this.messageFrom(err, 'Could not save the weighed-goods profile.'));
          resolve();
        },
      });
    });
  }

  // ── Retry a failed section ─────────────────────────────────────────────────

  retrySection(idx: number): void {
    const productUid = this.savedUid();
    if (!productUid) return;
    this.setSection(idx, 'pending');
    const retriers: (() => Promise<void>)[] = [
      () => Promise.resolve(), // anchor not retried here
      () => this.runPrices(productUid),
      () => this.runBarcodes(productUid),
      () => this.runBulkPacks(productUid),
      () => this.runBranches(productUid),
      () => this.runOpeningStock(productUid),
      () => this.runWeighing(productUid),
    ];
    retriers[idx]?.();
  }

  openProduct(): void {
    const uid = this.savedUid();
    if (uid) {
      this.router.navigate(['/admin/products/uid', uid]);
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private setSection(idx: number, status: SectionStatus, error: string | null = null): void {
    this.sectionResults.update((sections) =>
      sections.map((s, i) => (i === idx ? { ...s, status, error } : s)),
    );
  }

  private todayIso(): string {
    return new Date().toISOString().substring(0, 10);
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }

  priceListLabel(uid: string): string {
    const pl = this.priceLists().find((p) => p.uid === uid);
    return pl ? `${pl.code} — ${pl.name}` : uid;
  }

  unitLabel(uid: string): string {
    const u = this.companyUnits().find((u) => u.uid === uid);
    return u ? `${u.code} — ${u.name}` : uid;
  }

  /** 12 → "12", 0.5 → "0.5" — a number written the way a person would write it. */
  private trimNumber(value: number): string {
    return String(Number(value.toFixed(6)));
  }

  trackByLocalId(_: number, row: { localId: number }): number {
    return row.localId;
  }

  trackByIdx(idx: number): number {
    return idx;
  }
}
