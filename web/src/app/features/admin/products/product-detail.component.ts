import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Branch } from '../models/branch.model';
import { Company } from '../models/company.model';
import {
  AddBarcodeRequest,
  AddComponentRequest,
  AssignProductBranchRequest,
  CreateBulkPackRequest,
  Money,
  PriceListDto,
  ProductBarcodeDto,
  ProductBranchDto,
  ProductBulkPackDto,
  ProductComponentDto,
  ProductModel,
  ProductPriceDto,
  ProductType,
  SetProductPriceRequest,
  SetProductWeighingRequest,
  UnitOfMeasureDto,
  UpdateProductRequest,
  VatStatus,
} from '../models/product.model';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from './product.service';
import { CurrencySelectComponent } from '../../../shared/currency-select/currency-select.component';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Product detail + edit screen. Route: /admin/products/uid/:uid.
 * Edit form: name, description, type, sellable/stockable, baseUnit, cost.
 * BR-PROD-01: SERVICE cannot be stockable — stockable disabled when type=SERVICE.
 * Sub-sections: barcodes, bulk packs, prices, components/recipe, branch associations.
 */
@Component({
  selector: 'app-product-detail',
  imports: [FormsModule, RouterLink, CurrencySelectComponent],
  templateUrl: './product-detail.component.html',
  styleUrl: './product-detail.component.scss',
})
export class ProductDetailComponent {
  private readonly productService = inject(ProductService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Product header ────────────────────────────────────────────────────────
  readonly product = signal<ProductModel | null>(null);
  readonly productState = signal<LoadState>('loading');

  // ── Edit form fields ───────────────────────────────────────────────────────
  readonly fName = signal('');
  readonly fDescription = signal('');
  readonly fType = signal<ProductType>('GOODS');
  readonly fSellable = signal(true);
  readonly fStockable = signal(true);
  readonly fBaseUnitUid = signal('');
  readonly fCostAmount = signal('');
  readonly fCostCurrency = signal('TZS');
  readonly fVatStatus = signal<VatStatus>('STANDARD');

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly archiving = signal(false);

  // BR-PROD-01: SERVICE cannot be stockable.
  readonly stockableDisabled = computed(() => this.fType() === 'SERVICE');
  readonly canManage = computed(() => this.session.hasPermission('PRODUCT.MANAGE'));
  readonly canAssign = computed(() => this.session.hasPermission('PRODUCT.BRANCH.ASSIGN'));

  // ── Weighed goods (ADR-0044 D-1b) ───────────────────────────────────────────
  readonly fWeighed = signal(false);
  readonly fTareWeight = signal('');
  readonly fScaleStep = signal('');
  readonly fMaxSaleWeight = signal('');
  readonly savingWeighing = signal(false);
  readonly weighingError = signal<string | null>(null);
  /**
   * Client-side pre-gate: the server requires a WEIGHT base unit to mark a product weighed
   * (surfaced inline as a backstop if the user still manages to submit an invalid combination),
   * but disabling the toggle up-front is friendlier than a round-trip rejection.
   */
  readonly selectedBaseUnit = computed(() =>
    this.companyUnits().find((u) => u.uid === this.fBaseUnitUid()),
  );
  readonly baseUnitIsWeight = computed(() => this.selectedBaseUnit()?.dimensionType === 'WEIGHT');

  // ── Barcodes ──────────────────────────────────────────────────────────────
  readonly barcodes = signal<ProductBarcodeDto[]>([]);
  readonly barcodesState = signal<LoadState>('loading');
  readonly newBarcode = signal('');
  readonly newBarcodePrimary = signal(false);
  readonly addingBarcode = signal(false);
  readonly barcodeFormError = signal<string | null>(null);
  readonly rowBusyBarcodeId = signal<string | null>(null);

  // ── Bulk packs ────────────────────────────────────────────────────────────
  readonly bulkPacks = signal<ProductBulkPackDto[]>([]);
  readonly bulkPacksState = signal<LoadState>('loading');
  readonly newBulkPackUnitUid = signal('');
  readonly newBulkPackFactor = signal('');
  readonly addingBulkPack = signal(false);
  readonly bulkPackFormError = signal<string | null>(null);
  readonly rowBusyBulkPackId = signal<string | null>(null);

  // ── Prices ────────────────────────────────────────────────────────────────
  readonly prices = signal<ProductPriceDto[]>([]);
  readonly pricesState = signal<LoadState>('loading');
  readonly priceLists = signal<PriceListDto[]>([]);
  readonly priceListsState = signal<LoadState>('loading');
  readonly newPriceListUid = signal('');
  readonly newPriceAmount = signal('');
  readonly newPriceCurrency = signal('TZS');
  /** '' = base-unit price; else the uid of a configured pack unit (ADR-0048). */
  readonly newPriceUnitUid = signal('');
  readonly settingPrice = signal(false);
  readonly priceFormError = signal<string | null>(null);
  readonly rowBusyPriceId = signal<string | null>(null);

  // ── Company units (base-unit + bulk-pack selects) ─────────────────────────
  readonly companyUnits = signal<UnitOfMeasureDto[]>([]);
  readonly unitsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Components / Recipe ───────────────────────────────────────────────────
  readonly components = signal<ProductComponentDto[]>([]);
  readonly componentsState = signal<LoadState>('loading');
  /** Free-text search query for the component picker. */
  readonly componentSearchQ = signal('');
  /** Results from the debounced component search. */
  readonly componentResults = signal<ProductModel[]>([]);
  /** The product selected from componentResults; holds uid + display label. */
  readonly selectedComponent = signal<{ uid: string; label: string } | null>(null);
  readonly newComponentQuantity = signal('');
  readonly addingComponent = signal(false);
  readonly componentFormError = signal<string | null>(null);
  readonly rowBusyComponentId = signal<string | null>(null);

  private readonly componentSearch$ = new Subject<string>();

  // ── Branch associations ────────────────────────────────────────────────────
  readonly branches = signal<ProductBranchDto[]>([]);
  readonly branchesState = signal<LoadState>('loading');
  readonly rowBusyBranchId = signal<string | null>(null);

  // ── Branch assign form ─────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly companiesState = signal<LoadState>('loading');
  readonly selectedCompanyUid = signal('');

  /** Uid of the company that owns this product — used to scope the currency picker. */
  readonly productCompanyUid = computed(() => {
    const p = this.product();
    if (!p) return '';
    const match = this.companies().find((c) => c.id === p.companyId);
    return match?.uid ?? '';
  });
  readonly companyBranches = signal<Branch[]>([]);
  readonly companyBranchesState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly selectedBranchUid = signal('');
  readonly assigning = signal(false);
  readonly assignError = signal<string | null>(null);

  private readonly branchById = signal<Map<string, Branch>>(new Map());

  readonly hasBranches = computed(() => this.branches().length > 0);
  readonly hasBarcodes = computed(() => this.barcodes().length > 0);
  readonly hasBulkPacks = computed(() => this.bulkPacks().length > 0);
  readonly hasPrices = computed(() => this.prices().length > 0);
  readonly hasComponents = computed(() => this.components().length > 0);

  branchDisplay(branchId: string): string {
    const b = this.branchById().get(branchId);
    return b ? `${b.code} — ${b.name}` : branchId;
  }

  priceListLabel(pp: ProductPriceDto): string {
    // The price DTO carries the enriched price-list code/name directly; fall back
    // to the loaded price-lists or the uid if either is missing.
    if (pp.priceListCode || pp.priceListName) {
      return `${pp.priceListCode ?? ''} — ${pp.priceListName ?? ''}`.replace(/^ — | — $/g, '').trim();
    }
    const pl = this.priceLists().find((p) => p.uid === pp.priceListUid);
    return pl ? `${pl.code} — ${pl.name}` : pp.priceListUid;
  }

  /** ADR-0048: null unitUid = the base-unit price row. */
  priceUnitLabel(pp: ProductPriceDto): string {
    return pp.unitUid ? (pp.unitName ?? pp.unitCode ?? pp.unitUid) : 'Base';
  }

  /**
   * ADR-0056: the VAT-inclusive/exclusive nature is a property of the price LIST, not the
   * product-price row itself — looked up from the loaded price lists for this company.
   * Defaults to false (exclusive) if the owning list hasn't loaded yet.
   */
  priceIncludesVat(pp: ProductPriceDto): boolean {
    return this.priceLists().find((pl) => pl.uid === pp.priceListUid)?.priceIncludesVat ?? false;
  }

  constructor() {
    // Debounced component-product search (Fix 2).
    this.componentSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.product()?.companyId;
          if (!companyId || !q.trim()) {
            this.componentResults.set([]);
            return [];
          }
          return this.productService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => {
          const currentUid = this.product()?.uid;
          // BR-PROD-05: exclude self; exclude ARCHIVED.
          this.componentResults.set(
            rows.filter((r) => r.uid !== currentUid && r.status !== 'ARCHIVED'),
          );
        },
        error: () => this.componentResults.set([]),
      });

    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadProduct();
    this.loadBarcodes();
    this.loadBulkPacks();
    this.loadPrices();
    this.loadComponents();
    this.loadBranches();
    this.loadCompanies();
  }

  private loadProduct(): void {
    this.productState.set('loading');
    this.productService.getByUid(this.uid()).subscribe({
      next: (p) => {
        this.product.set(p);
        this.productState.set('idle');
        this.patchForm(p);
        // Load price lists and units for the product's company.
        this.loadPriceLists(p.companyId);
        this.loadUnits(p.companyId);
      },
      error: () => this.productState.set('error'),
    });
  }

  private loadUnits(companyId: string): void {
    this.unitsState.set('loading');
    this.productService.listUnits(companyId).subscribe({
      next: ({ rows }) => {
        this.companyUnits.set(rows.filter((u) => u.status === 'ACTIVE'));
        this.unitsState.set('idle');
        // Preselect the product's current base unit once units are loaded.
        const p = this.product();
        if (p && !this.fBaseUnitUid()) {
          this.fBaseUnitUid.set(p.baseUnitUid ?? '');
        }
      },
      error: () => this.unitsState.set('error'),
    });
  }

  private patchForm(p: ProductModel): void {
    this.fName.set(p.name ?? '');
    this.fDescription.set(p.description ?? '');
    this.fType.set(p.type);
    this.fSellable.set(p.sellable);
    this.fStockable.set(p.stockable);
    this.fBaseUnitUid.set(p.baseUnitUid ?? '');
    this.fCostAmount.set(p.cost?.amount ?? '');
    this.fCostCurrency.set(p.cost?.currency ?? 'TZS');
    this.fVatStatus.set(p.vatStatus ?? 'STANDARD');
    this.patchWeighingForm(p);
  }

  /**
   * tareWeight/scaleStep/maxSaleWeight are BigDecimal on the wire (JSON numbers), same as
   * reorderLevel/weight elsewhere on ProductDto — coerce with String() before handing to a
   * text-input signal so a later .trim() never runs on a number (wire-number-vs-string gotcha).
   */
  private patchWeighingForm(p: ProductModel): void {
    this.fWeighed.set(p.weighed ?? false);
    this.fTareWeight.set(p.tareWeight != null ? String(p.tareWeight) : '');
    this.fScaleStep.set(p.scaleStep != null ? String(p.scaleStep) : '');
    this.fMaxSaleWeight.set(p.maxSaleWeight != null ? String(p.maxSaleWeight) : '');
  }

  onTypeChange(type: ProductType): void {
    this.fType.set(type);
    // BR-PROD-01: SERVICE cannot be stockable.
    if (type === 'SERVICE') {
      this.fStockable.set(false);
    }
  }

  /** Moving off a weight base unit while "Sold by weight" is on must never leave an invalid combination. */
  onBaseUnitChange(unitUid: string): void {
    this.fBaseUnitUid.set(unitUid);
    if (this.fWeighed() && !this.baseUnitIsWeight()) {
      this.fWeighed.set(false);
    }
  }

  // ── Barcodes ──────────────────────────────────────────────────────────────

  loadBarcodes(): void {
    this.barcodesState.set('loading');
    this.productService.listBarcodes(this.uid()).subscribe({
      next: (rows) => {
        this.barcodes.set(rows);
        this.barcodesState.set('idle');
      },
      error: () => this.barcodesState.set('error'),
    });
  }

  addBarcode(): void {
    const barcode = this.newBarcode().trim();
    if (!barcode) {
      this.barcodeFormError.set('Barcode value is required.');
      return;
    }
    this.addingBarcode.set(true);
    this.barcodeFormError.set(null);
    const request: AddBarcodeRequest = { barcode, primary: this.newBarcodePrimary() };
    this.productService.addBarcode(this.uid(), request).subscribe({
      next: () => {
        this.newBarcode.set('');
        this.newBarcodePrimary.set(false);
        this.addingBarcode.set(false);
        this.alerts.success('Barcode added');
        this.loadBarcodes();
      },
      error: (err) => {
        this.barcodeFormError.set(this.messageFrom(err, 'Could not add barcode.'));
        this.addingBarcode.set(false);
      },
    });
  }

  removeBarcode(bc: ProductBarcodeDto): void {
    if (this.rowBusyBarcodeId() !== null) return;
    this.rowBusyBarcodeId.set(bc.uid);
    this.productService.removeBarcode(this.uid(), bc.uid).subscribe({
      next: () => {
        this.rowBusyBarcodeId.set(null);
        this.alerts.success('Barcode removed');
        this.loadBarcodes();
      },
      error: () => this.rowBusyBarcodeId.set(null),
    });
  }

  // ── Bulk packs ────────────────────────────────────────────────────────────

  loadBulkPacks(): void {
    this.bulkPacksState.set('loading');
    this.productService.listBulkPacks(this.uid()).subscribe({
      next: (rows) => {
        this.bulkPacks.set(rows);
        this.bulkPacksState.set('idle');
      },
      error: () => this.bulkPacksState.set('error'),
    });
  }

  addBulkPack(): void {
    const unitUid = this.newBulkPackUnitUid();
    const factor = this.newBulkPackFactor().trim();
    if (!unitUid || !factor) {
      this.bulkPackFormError.set('Unit and factor are required.');
      return;
    }
    this.addingBulkPack.set(true);
    this.bulkPackFormError.set(null);
    const request: CreateBulkPackRequest = { unitUid, factorToBase: factor };
    this.productService.addBulkPack(this.uid(), request).subscribe({
      next: () => {
        this.newBulkPackUnitUid.set('');
        this.newBulkPackFactor.set('');
        this.addingBulkPack.set(false);
        this.alerts.success('Bulk pack added');
        this.loadBulkPacks();
      },
      error: (err) => {
        this.bulkPackFormError.set(this.messageFrom(err, 'Could not add bulk pack.'));
        this.addingBulkPack.set(false);
      },
    });
  }

  removeBulkPack(bp: ProductBulkPackDto): void {
    if (this.rowBusyBulkPackId() !== null) return;
    this.rowBusyBulkPackId.set(bp.uid);
    this.productService.removeBulkPack(this.uid(), bp.uid).subscribe({
      next: () => {
        this.rowBusyBulkPackId.set(null);
        this.alerts.success('Bulk pack removed');
        this.loadBulkPacks();
      },
      error: () => this.rowBusyBulkPackId.set(null),
    });
  }

  // ── Prices ────────────────────────────────────────────────────────────────

  loadPrices(): void {
    this.pricesState.set('loading');
    this.productService.listPrices(this.uid()).subscribe({
      next: (rows) => {
        this.prices.set(rows);
        this.pricesState.set('idle');
      },
      error: () => this.pricesState.set('error'),
    });
  }

  private loadPriceLists(companyId: string): void {
    this.priceListsState.set('loading');
    this.productService.listPriceLists(companyId).subscribe({
      next: (rows) => {
        this.priceLists.set(rows);
        this.priceListsState.set('idle');
      },
      error: () => this.priceListsState.set('error'),
    });
  }

  setPrice(): void {
    const priceListUid = this.newPriceListUid();
    const amount = this.newPriceAmount().trim();
    if (!priceListUid || !amount) {
      this.priceFormError.set('Price list and amount are required.');
      return;
    }
    this.settingPrice.set(true);
    this.priceFormError.set(null);
    const unitUid = this.newPriceUnitUid();
    const request: SetProductPriceRequest = {
      priceListUid,
      price: { amount, currency: this.newPriceCurrency().trim() || 'TZS' },
      // ADR-0048: '' (base unit selected) omits unitUid ⇒ base-unit price row.
      ...(unitUid ? { unitUid } : {}),
    };
    this.productService.setPrice(this.uid(), request).subscribe({
      next: () => {
        this.newPriceListUid.set('');
        this.newPriceAmount.set('');
        this.newPriceCurrency.set('TZS');
        this.newPriceUnitUid.set('');
        this.settingPrice.set(false);
        this.alerts.success('Price set');
        this.loadPrices();
      },
      error: (err) => {
        this.priceFormError.set(this.messageFrom(err, 'Could not set price.'));
        this.settingPrice.set(false);
      },
    });
  }

  removePrice(pp: ProductPriceDto): void {
    if (this.rowBusyPriceId() !== null) return;
    this.rowBusyPriceId.set(pp.id);
    this.productService.removePrice(this.uid(), pp.priceListUid, pp.unitUid ?? undefined).subscribe({
      next: () => {
        this.rowBusyPriceId.set(null);
        this.alerts.success('Price removed');
        this.loadPrices();
      },
      error: () => this.rowBusyPriceId.set(null),
    });
  }

  // ── Components / Recipe ───────────────────────────────────────────────────

  loadComponents(): void {
    this.componentsState.set('loading');
    this.productService.listComponents(this.uid()).subscribe({
      next: (rows) => {
        this.components.set(rows);
        this.componentsState.set('idle');
      },
      error: () => this.componentsState.set('error'),
    });
  }

  onComponentSearchChange(q: string): void {
    this.componentSearchQ.set(q);
    // Clear the selection when the user edits the search box.
    this.selectedComponent.set(null);
    this.componentSearch$.next(q);
  }

  selectComponent(product: ProductModel): void {
    this.selectedComponent.set({ uid: product.uid, label: `${product.code} — ${product.name}` });
    this.componentResults.set([]);
    this.componentSearchQ.set(`${product.code} — ${product.name}`);
  }

  addComponent(): void {
    const selected = this.selectedComponent();
    const quantity = this.newComponentQuantity().trim();
    if (!selected || !quantity) {
      this.componentFormError.set('Select a component product and enter a quantity.');
      return;
    }
    this.addingComponent.set(true);
    this.componentFormError.set(null);
    const request: AddComponentRequest = { componentProductUid: selected.uid, quantity };
    this.productService.addComponent(this.uid(), request).subscribe({
      next: () => {
        this.selectedComponent.set(null);
        this.componentSearchQ.set('');
        this.componentResults.set([]);
        this.newComponentQuantity.set('');
        this.addingComponent.set(false);
        this.alerts.success('Component added');
        this.loadComponents();
      },
      error: (err) => {
        this.componentFormError.set(this.messageFrom(err, 'Could not add component.'));
        this.addingComponent.set(false);
      },
    });
  }

  removeComponent(comp: ProductComponentDto): void {
    if (this.rowBusyComponentId() !== null) return;
    this.rowBusyComponentId.set(comp.id);
    this.productService.removeComponent(this.uid(), comp.componentProductUid).subscribe({
      next: () => {
        this.rowBusyComponentId.set(null);
        this.alerts.success('Component removed');
        this.loadComponents();
      },
      error: () => this.rowBusyComponentId.set(null),
    });
  }

  // ── Branch associations ────────────────────────────────────────────────────

  loadBranches(): void {
    this.branchesState.set('loading');
    this.productService.listBranches(this.uid()).subscribe({
      next: (rows) => {
        this.branches.set(rows);
        this.branchesState.set('idle');
      },
      error: () => this.branchesState.set('error'),
    });
  }

  private loadCompanies(): void {
    this.companiesState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companiesState.set('idle');
            list.forEach((co) => this.loadCompanyBranchesForDisplay(co.uid));
          },
          error: () => this.companiesState.set('error'),
        });
      },
      error: () => this.companiesState.set('error'),
    });
  }

  private loadCompanyBranchesForDisplay(companyUid: string): void {
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.branchById.update((map) => {
          const next = new Map(map);
          rows.forEach((b) => next.set(b.id, b));
          return next;
        });
      },
      error: () => undefined,
    });
  }

  onCompanyChange(companyUid: string): void {
    this.selectedCompanyUid.set(companyUid);
    this.selectedBranchUid.set('');
    this.companyBranches.set([]);
    if (!companyUid) {
      this.companyBranchesState.set('idle');
      return;
    }
    this.companyBranchesState.set('loading');
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.companyBranches.set(rows);
        this.companyBranchesState.set('idle');
        this.branchById.update((map) => {
          const next = new Map(map);
          rows.forEach((b) => next.set(b.id, b));
          return next;
        });
      },
      error: () => this.companyBranchesState.set('error'),
    });
  }

  assign(): void {
    const branchUid = this.selectedBranchUid();
    if (!branchUid) {
      this.assignError.set('Select a branch to assign.');
      return;
    }
    this.assigning.set(true);
    this.assignError.set(null);
    const request: AssignProductBranchRequest = { branchUid };
    this.productService.assignBranch(this.uid(), request).subscribe({
      next: () => {
        this.selectedBranchUid.set('');
        this.assigning.set(false);
        this.alerts.success('Branch assigned');
        this.loadBranches();
      },
      error: (err) => {
        this.assignError.set(this.messageFrom(err, 'Could not assign branch.'));
        this.assigning.set(false);
      },
    });
  }

  removeBranch(pb: ProductBranchDto): void {
    if (this.rowBusyBranchId() !== null) return;
    const branch = this.branchById().get(pb.branchId);
    if (!branch) {
      this.alerts.success('Branch not found in loaded list — refresh and try again.');
      return;
    }
    this.rowBusyBranchId.set(pb.branchId);
    this.productService.removeBranch(this.uid(), branch.uid).subscribe({
      next: () => {
        this.rowBusyBranchId.set(null);
        this.alerts.success('Branch removed', branch.name);
        this.loadBranches();
      },
      error: () => this.rowBusyBranchId.set(null),
    });
  }

  // ── Edit + Archive ─────────────────────────────────────────────────────────

  save(): void {
    const name = this.fName().trim();
    if (!name) {
      this.saveError.set('Name is required.');
      return;
    }
    const baseUnitUid = this.fBaseUnitUid();
    if (!baseUnitUid) {
      this.saveError.set('Base unit is required.');
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);

    const cost: Money | undefined =
      this.fCostAmount().trim()
        ? { amount: this.fCostAmount().trim(), currency: this.fCostCurrency().trim() || 'TZS' }
        : undefined;

    const request: UpdateProductRequest = {
      name,
      description: this.fDescription().trim() || undefined,
      type: this.fType(),
      sellable: this.fSellable(),
      // BR-PROD-01: SERVICE is never stockable regardless of checkbox.
      stockable: this.fType() === 'SERVICE' ? false : this.fStockable(),
      baseUnitUid,
      cost,
      vatStatus: this.fVatStatus(),
    };

    this.productService.update(this.uid(), request).subscribe({
      next: (updated) => {
        this.product.set(updated);
        this.saving.set(false);
        this.alerts.success('Product saved', updated.name);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the product.'));
        this.saving.set(false);
      },
    });
  }

  // ── Weighed goods (ADR-0044 D-1b) ───────────────────────────────────────────

  /** Backstop against the (disabled) toggle being flipped on for a non-weight base unit. */
  onWeighedToggle(on: boolean): void {
    this.fWeighed.set(on && this.baseUnitIsWeight());
  }

  /**
   * POSTs the weighing profile as a separate action from the main product save (mirrors
   * setPrice/addBarcode/addBulkPack). Sends null for tare/scaleStep/maxSaleWeight when the
   * toggle is off — the server also clears them itself, this just keeps the form consistent
   * with what will come back on the next load.
   */
  saveWeighing(): void {
    if (this.savingWeighing()) return;
    this.savingWeighing.set(true);
    this.weighingError.set(null);

    const weighed = this.fWeighed();
    const request: SetProductWeighingRequest = {
      weighed,
      tareWeight: weighed ? this.fTareWeight().trim() || null : null,
      scaleStep: weighed ? this.fScaleStep().trim() || null : null,
      maxSaleWeight: weighed ? this.fMaxSaleWeight().trim() || null : null,
    };

    this.productService.setWeighing(this.uid(), request).subscribe({
      next: (updated) => {
        this.product.set(updated);
        this.patchWeighingForm(updated);
        this.savingWeighing.set(false);
        this.alerts.success('Weighing profile saved');
      },
      error: (err) => {
        this.weighingError.set(this.messageFrom(err, 'Could not save the weighing profile.'));
        this.savingWeighing.set(false);
      },
    });
  }

  archive(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.productService.archive(this.uid()).subscribe({
      next: (updated) => {
        this.archiving.set(false);
        this.product.set(updated);
        this.alerts.success('Product archived');
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not archive the product.'));
      },
    });
  }

  restore(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.productService.restore(this.uid()).subscribe({
      next: (updated) => {
        this.archiving.set(false);
        this.product.set(updated);
        this.alerts.success('Product restored');
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not restore the product.'));
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
