import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { CustomerModel } from '../../models/party.model';
import { PriceListDto, ProductModel } from '../../models/product.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { CustomerService } from '../../parties/customer.service';
import { Observable, map, tap } from 'rxjs';
import { PRODUCT_PICKER_SEARCH_SIZE, ProductService } from '../../products/product.service';
import { UidPickerComponent, UidOption } from '../../../../shared/uid-picker/uid-picker.component';
import { CurrencySelectComponent } from '../../../../shared/currency-select/currency-select.component';
import {
  CreateCustomerPriceRequest,
  CreatePriceTierRequest,
  CustomerPriceDto,
  PriceTierDto,
} from './pricing-rule.model';
import { PricingRuleService } from './pricing-rule.service';

type Tab = 'tiers' | 'customer-prices';

/**
 * Pricing Rules screen — two tabs:
 *  (1) Price Tiers: select product + price-list, view/create quantity-break tiers, deactivate.
 *  (2) Customer Prices: select customer, view/create contract prices per product, deactivate.
 * Gated by SALES.PRICING.RULE.VIEW; management actions require SALES.PRICING.RULE.MANAGE.
 * Route: /admin/pricing-rules
 */
@Component({
  selector: 'app-pricing-rules',
  imports: [FormsModule, DecimalPipe, UidPickerComponent, CurrencySelectComponent],
  templateUrl: './pricing-rules.component.html',
  styleUrl: './pricing-rules.component.scss',
})
export class PricingRulesComponent {
  private readonly pricingService = inject(PricingRuleService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Permissions ──────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('SALES.PRICING.RULE.MANAGE'));

  // ── Tab ──────────────────────────────────────────────────────────────────────
  readonly activeTab = signal<Tab>('tiers');

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = computed(() => this.companies().find((c) => c.id === this.selectedCompanyId())?.uid ?? '');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Product / PriceList options for tiers ────────────────────────────────────
  readonly products = signal<ProductModel[]>([]);
  readonly priceLists = signal<PriceListDto[]>([]);

  readonly productOptions = computed<UidOption[]>(() =>
    this.products().map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
  );
  readonly priceListOptions = computed<UidOption[]>(() =>
    this.priceLists().map((pl) => ({ uid: pl.uid, label: pl.name, hint: pl.code })),
  );

  // ── Tier filter ──────────────────────────────────────────────────────────────
  readonly tierProductUid = signal('');
  readonly tierPriceListUid = signal('');

  // ── Tier list ────────────────────────────────────────────────────────────────
  readonly tierRows = signal<PriceTierDto[]>([]);
  readonly tierState = signal<'idle' | 'loading' | 'error' | 'forbidden'>('idle');
  readonly tierIsEmpty = computed(() => this.tierState() === 'idle' && this.tierRows().length === 0);
  readonly tierBusyUid = signal<string | null>(null);

  // ── Tier create form ─────────────────────────────────────────────────────────
  readonly showTierForm = signal(false);
  readonly newTierProductUid = signal('');
  readonly newTierPriceListUid = signal('');
  readonly newTierMinQty = signal('');
  readonly newTierPrice = signal('');
  readonly newTierCurrency = signal('TZS');
  readonly tierSaving = signal(false);
  readonly tierFormError = signal<string | null>(null);

  // ── Customer options ─────────────────────────────────────────────────────────
  readonly customers = signal<CustomerModel[]>([]);
  readonly customerOptions = computed<UidOption[]>(() =>
    this.customers().map((c) => ({ uid: c.uid, label: c.displayName, hint: c.code })),
  );

  // ── Customer price filter ─────────────────────────────────────────────────────
  readonly cpCustomerUid = signal('');

  // ── Customer price list ──────────────────────────────────────────────────────
  readonly cpRows = signal<CustomerPriceDto[]>([]);
  readonly cpState = signal<'idle' | 'loading' | 'error' | 'forbidden'>('idle');
  readonly cpIsEmpty = computed(() => this.cpState() === 'idle' && this.cpRows().length === 0);
  readonly cpBusyUid = signal<string | null>(null);

  // ── Customer price create form ────────────────────────────────────────────────
  readonly showCpForm = signal(false);
  readonly newCpCustomerUid = signal('');
  readonly newCpProductUid = signal('');
  readonly newCpPrice = signal('');
  readonly newCpCurrency = signal('TZS');
  readonly newCpEffectiveFrom = signal('');
  readonly newCpEffectiveTo = signal('');
  readonly cpSaving = signal(false);
  readonly cpFormError = signal<string | null>(null);

  constructor() {
    this.loadCompanies();
  }

  // ── Company loading ──────────────────────────────────────────────────────────

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
              this.loadRefData(list[0].id);
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
    this.tierRows.set([]);
    this.cpRows.set([]);
    this.tierProductUid.set('');
    this.tierPriceListUid.set('');
    this.cpCustomerUid.set('');
    if (id) this.loadRefData(id);
  }

  private loadRefData(companyId: string): void {
    // A SEED for the three product pickers, not the catalogue — anything past this first page is
    // reached by typing, which goes to the server via searchProducts. It was previously the
    // service's 20-row default, which put all but the first 20 products out of reach entirely.
    this.productService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.products.set(rows),
      error: () => this.products.set([]),
    });
    this.productService.listPriceLists(companyId).subscribe({
      next: (rows) => this.priceLists.set(rows),
      error: () => this.priceLists.set([]),
    });
    this.customerService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.customers.set(rows.filter((c) => c.status === 'ACTIVE')),
      error: () => this.customers.set([]),
    });
  }

  /**
   * Server-side product lookup shared by all three product pickers (arrow property so `this`
   * survives the binding). Results are folded into products() as well as shown, because loadTiers()
   * resolves the chosen uid → id from that same list and productLabel() names rows from it.
   */
  readonly searchProducts = (q: string): Observable<readonly UidOption[]> =>
    this.productService.list(this.selectedCompanyId(), q, 0, PRODUCT_PICKER_SEARCH_SIZE).pipe(
      tap(({ rows }) => this.cacheProducts(rows)),
      map(({ rows }) => rows.map((p) => ({ uid: p.uid, label: p.name, hint: p.code }))),
    );

  /** Merge freshly-seen products into the local list, keeping it unique by uid. */
  private cacheProducts(rows: readonly ProductModel[]): void {
    this.products.update((existing) => {
      const seen = new Set(existing.map((p) => p.uid));
      return [...existing, ...rows.filter((p) => !seen.has(p.uid))];
    });
  }

  // ── Tab switching ────────────────────────────────────────────────────────────

  setTab(tab: Tab): void {
    this.activeTab.set(tab);
  }

  // ── Tiers ────────────────────────────────────────────────────────────────────

  loadTiers(): void {
    const companyId = this.selectedCompanyId();
    const productOption = this.tierProductUid();
    const priceListOption = this.tierPriceListUid();
    if (!companyId || !productOption || !priceListOption) return;

    // Resolve uid -> id for API (the backend list takes numeric ids)
    const product = this.products().find((p) => p.uid === productOption);
    const priceList = this.priceLists().find((pl) => pl.uid === priceListOption);
    if (!product || !priceList) return;

    this.tierState.set('loading');
    this.pricingService.listTiers(companyId, product.id, priceList.id).subscribe({
      next: (rows) => {
        this.tierRows.set(rows);
        this.tierState.set('idle');
      },
      error: (err) =>
        this.tierState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  toggleTierForm(): void {
    this.showTierForm.update((v) => !v);
    this.tierFormError.set(null);
    if (!this.showTierForm()) this.resetTierForm();
  }

  private resetTierForm(): void {
    this.newTierProductUid.set('');
    this.newTierPriceListUid.set('');
    this.newTierMinQty.set('');
    this.newTierPrice.set('');
    this.newTierCurrency.set('TZS');
  }

  createTier(): void {
    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    const productUid = this.newTierProductUid().trim();
    const priceListUid = this.newTierPriceListUid().trim();
    const minQty = this.newTierMinQty().trim();
    const price = this.newTierPrice().trim();
    const currency = this.newTierCurrency().trim();

    if (!company) { this.tierFormError.set('Could not resolve selected company.'); return; }
    if (!productUid) { this.tierFormError.set('Product is required.'); return; }
    if (!priceListUid) { this.tierFormError.set('Price list is required.'); return; }
    if (!minQty || isNaN(Number(minQty)) || Number(minQty) <= 0) {
      this.tierFormError.set('Minimum quantity must be a positive number.');
      return;
    }
    if (!price || isNaN(Number(price)) || Number(price) < 0) {
      this.tierFormError.set('Unit price must be a non-negative number.');
      return;
    }
    if (!currency) { this.tierFormError.set('Currency is required.'); return; }

    this.tierSaving.set(true);
    this.tierFormError.set(null);

    const request: CreatePriceTierRequest = {
      companyUid: company.uid,
      productUid,
      priceListUid,
      minQty,
      unitPriceAmount: price,
      currency,
    };

    this.pricingService.createTier(request).subscribe({
      next: (created) => {
        this.tierSaving.set(false);
        this.showTierForm.set(false);
        this.resetTierForm();
        this.alerts.success('Price tier created', `Min qty: ${created.minQty}`);
        // Reload if current filter matches the new tier's context
        this.loadTiers();
      },
      error: (err) => {
        this.tierFormError.set(this.messageFrom(err, 'Could not create price tier.'));
        this.tierSaving.set(false);
      },
    });
  }

  deactivateTier(tier: PriceTierDto): void {
    if (this.tierBusyUid() !== null) return;
    this.tierBusyUid.set(tier.uid);
    this.pricingService.deactivateTier(tier.uid).subscribe({
      next: () => {
        this.tierBusyUid.set(null);
        this.tierRows.update((rows) => rows.filter((r) => r.uid !== tier.uid));
        this.alerts.success('Price tier deactivated');
      },
      error: () => this.tierBusyUid.set(null),
    });
  }

  // ── Customer Prices ──────────────────────────────────────────────────────────

  loadCustomerPrices(): void {
    const companyId = this.selectedCompanyId();
    const customerUid = this.cpCustomerUid();
    if (!companyId || !customerUid) return;

    const customer = this.customers().find((c) => c.uid === customerUid);
    if (!customer) return;

    this.cpState.set('loading');
    this.pricingService.listCustomerPrices(companyId, customer.id).subscribe({
      next: (rows) => {
        this.cpRows.set(rows);
        this.cpState.set('idle');
      },
      error: (err) =>
        this.cpState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  toggleCpForm(): void {
    this.showCpForm.update((v) => !v);
    this.cpFormError.set(null);
    if (!this.showCpForm()) this.resetCpForm();
  }

  private resetCpForm(): void {
    this.newCpCustomerUid.set('');
    this.newCpProductUid.set('');
    this.newCpPrice.set('');
    this.newCpCurrency.set('TZS');
    this.newCpEffectiveFrom.set('');
    this.newCpEffectiveTo.set('');
  }

  createCustomerPrice(): void {
    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    const customerUid = this.newCpCustomerUid().trim();
    const productUid = this.newCpProductUid().trim();
    const price = this.newCpPrice().trim();
    const currency = this.newCpCurrency().trim();

    if (!company) { this.cpFormError.set('Could not resolve selected company.'); return; }
    if (!customerUid) { this.cpFormError.set('Customer is required.'); return; }
    if (!productUid) { this.cpFormError.set('Product is required.'); return; }
    if (!price || isNaN(Number(price)) || Number(price) < 0) {
      this.cpFormError.set('Unit price must be a non-negative number.');
      return;
    }
    if (!currency) { this.cpFormError.set('Currency is required.'); return; }

    this.cpSaving.set(true);
    this.cpFormError.set(null);

    const request: CreateCustomerPriceRequest = {
      companyUid: company.uid,
      customerUid,
      productUid,
      unitPriceAmount: price,
      currency,
      effectiveFrom: this.newCpEffectiveFrom().trim() || null,
      effectiveTo: this.newCpEffectiveTo().trim() || null,
    };

    this.pricingService.createCustomerPrice(request).subscribe({
      next: (created) => {
        this.cpSaving.set(false);
        this.showCpForm.set(false);
        this.resetCpForm();
        this.alerts.success('Customer price created', created.currency + ' ' + created.unitPriceAmount);
        this.loadCustomerPrices();
      },
      error: (err) => {
        this.cpFormError.set(this.messageFrom(err, 'Could not create customer price.'));
        this.cpSaving.set(false);
      },
    });
  }

  deactivateCustomerPrice(cp: CustomerPriceDto): void {
    if (this.cpBusyUid() !== null) return;
    this.cpBusyUid.set(cp.uid);
    this.pricingService.deactivateCustomerPrice(cp.uid).subscribe({
      next: () => {
        this.cpBusyUid.set(null);
        this.cpRows.update((rows) => rows.filter((r) => r.uid !== cp.uid));
        this.alerts.success('Customer price deactivated');
      },
      error: () => this.cpBusyUid.set(null),
    });
  }

  // ── Display helpers ──────────────────────────────────────────────────────────

  /**
   * Names an existing rule's product from the locally-known set. Rows whose product is neither in
   * the seed page nor in anything searched this session still fall back to the raw id — resolving
   * those needs the product name on the tier/customer-price DTO itself, which is a backend change.
   */
  productLabel(productId: string): string {
    const p = this.products().find((x) => x.id === productId);
    return p ? `${p.code} — ${p.name}` : productId;
  }

  priceListLabel(priceListId: string): string {
    const pl = this.priceLists().find((x) => x.id === priceListId);
    return pl ? pl.name : priceListId;
  }

  customerLabel(customerId: string): string {
    const c = this.customers().find((x) => x.id === customerId);
    return c ? `${c.code} — ${c.displayName}` : customerId;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
