/**
 * PricingRulesComponent spec.
 * Mirrors tax-rate-list.component.spec.ts pattern (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Startup: loads companies + ref data on init.
 *  2. canManage=false: manage buttons absent in computed.
 *  3. loadTiers() calls service with correct companyId/productId/priceListId.
 *  4. createTier() validation: required fields guarded.
 *  5. createTier() calls pricingService.createTier with correct request.
 *  6. loadCustomerPrices() calls service with correct companyId/customerId.
 *  7. createCustomerPrice() validation: required fields guarded.
 *  8. 403 response on loadTiers sets tierState to 'forbidden'.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { CustomerService } from '../../parties/customer.service';
import { ProductService } from '../../products/product.service';
import { PricingRuleService } from './pricing-rule.service';
import { PricingRulesComponent } from './pricing-rules.component';
import type { PriceTierDto, CustomerPriceDto } from './pricing-rule.model';

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeTier(overrides: Partial<PriceTierDto> = {}): PriceTierDto {
  return {
    id: '1', uid: 'TIER1',
    companyId: '10', productId: '20', priceListId: '30',
    minQty: '10.000', unitPriceAmount: '950.00', currency: 'TZS',
    status: 'ACTIVE',
    ...overrides,
  };
}

function makeCustomerPrice(overrides: Partial<CustomerPriceDto> = {}): CustomerPriceDto {
  return {
    id: '2', uid: 'CP1',
    companyId: '10', customerId: '40', productId: '20',
    unitPriceAmount: '1200.00', currency: 'TZS',
    effectiveFrom: null, effectiveTo: null,
    status: 'ACTIVE',
    ...overrides,
  };
}

function makeProduct() {
  return { id: '20', uid: 'PROD1', code: 'P001', name: 'Widget', companyId: '10',
           description: null, type: 'GOODS' as const, sellable: true, stockable: true,
           baseUnitUid: 'U1', baseUnitCode: 'EA', baseUnitName: 'Each',
           cost: null, vatStatus: 'STANDARD' as const, status: 'ACTIVE' as const,
           version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null };
}

function makePriceList() {
  return { id: '30', uid: 'PL1', companyId: '10', code: 'RETAIL', name: 'Retail Price',
           status: 'ACTIVE', version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null };
}

function makeCustomer() {
  return { id: '40', uid: 'CUST1', companyId: '10', code: 'C001', displayName: 'Alice Ltd',
           legalName: null, partyType: 'BUSINESS' as const, tin: null, vatRegistered: false, vrn: null,
           creditLimit: null, creditBalance: null, kind: 'CREDIT_ACCOUNT' as const,
           status: 'ACTIVE' as const, version: null, createdAt: null, createdBy: null,
           updatedAt: null, updatedBy: null, branches: [] };
}

function makeSessionStore(canManage = false) {
  return {
    hasPermission: vi.fn(() => canManage),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: {
  canManage?: boolean;
  listTiersImpl?: () => any;
  listCpImpl?: () => any;
} = {}) {
  const { canManage = false } = opts;
  const listTiersImpl = opts.listTiersImpl ?? (() => of([makeTier()]));
  const listCpImpl = opts.listCpImpl ?? (() => of([makeCustomerPrice()]));
  const sessionStore = makeSessionStore(canManage);

  TestBed.configureTestingModule({
    imports: [PricingRulesComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PricingRuleService,
        useValue: {
          listTiers: vi.fn(listTiersImpl),
          createTier: vi.fn(() => of(makeTier())),
          deactivateTier: vi.fn(() => of(undefined)),
          listCustomerPrices: vi.fn(listCpImpl),
          createCustomerPrice: vi.fn(() => of(makeCustomerPrice())),
          deactivateCustomerPrice: vi.fn(() => of(undefined)),
        },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [makeProduct()], meta: { page: 0, size: 200, totalElements: 1, totalPages: 1, hasNext: false } })),
          listPriceLists: vi.fn(() => of([makePriceList()])),
        },
      },
      {
        provide: CustomerService,
        useValue: {
          list: vi.fn(() => of({ rows: [makeCustomer()], meta: { page: 0, size: 200, totalElements: 1, totalPages: 1, hasNext: false } })),
        },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      { provide: SessionStore, useValue: sessionStore },
    ],
  });
}

// ── Init ──────────────────────────────────────────────────────────────────────

describe('PricingRulesComponent — init', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads companies and ref data on startup', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    const productSvc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    expect(comp.companies().length).toBe(1);
    expect(comp.selectedCompanyId()).toBe('10');
    // An explicit seed page, not the service's 20-row default: the pickers are seeded from this
    // call and search the server for anything past it.
    expect(productSvc.list).toHaveBeenCalledWith('10', undefined, 0, 200);
    expect(productSvc.listPriceLists).toHaveBeenCalledWith('10');
    expect(comp.products().length).toBe(1);
    expect(comp.priceLists().length).toBe(1);
    expect(comp.customers().length).toBe(1);
  });

  it('default tab is tiers', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.activeTab()).toBe('tiers');
  });

  it('setTab switches to customer-prices', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    await vi.runAllTimersAsync();
    comp.setTab('customer-prices');
    expect(comp.activeTab()).toBe('customer-prices');
  });
});

// ── canManage=false ───────────────────────────────────────────────────────────

describe('PricingRulesComponent — canManage=false', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canManage: false }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('canManage is false', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.canManage()).toBe(false);
  });
});

// ── Load tiers ────────────────────────────────────────────────────────────────

describe('PricingRulesComponent — loadTiers', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canManage: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls listTiers with resolved ids', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    const svc = TestBed.inject(PricingRuleService) as any;
    await vi.runAllTimersAsync();

    comp.tierProductUid.set('PROD1');
    comp.tierPriceListUid.set('PL1');
    comp.loadTiers();
    await vi.runAllTimersAsync();

    expect(svc.listTiers).toHaveBeenCalledWith('10', '20', '30');
    expect(comp.tierState()).toBe('idle');
    expect(comp.tierRows().length).toBe(1);
  });

  it('does nothing when product or price list not selected', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    const svc = TestBed.inject(PricingRuleService) as any;
    await vi.runAllTimersAsync();

    svc.listTiers.mockClear();
    comp.tierProductUid.set('PROD1');
    // priceListUid still empty
    comp.loadTiers();

    expect(svc.listTiers).not.toHaveBeenCalled();
  });
});

// ── Create tier validation ────────────────────────────────────────────────────

describe('PricingRulesComponent — createTier validation', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canManage: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('requires product uid', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    const svc = TestBed.inject(PricingRuleService) as any;
    await vi.runAllTimersAsync();

    comp.newTierMinQty.set('10');
    comp.newTierPrice.set('100');
    comp.newTierCurrency.set('TZS');
    comp.createTier();

    expect(comp.tierFormError()).toBeTruthy();
    expect(svc.createTier).not.toHaveBeenCalled();
  });

  it('requires positive min qty', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    const svc = TestBed.inject(PricingRuleService) as any;
    await vi.runAllTimersAsync();

    comp.newTierProductUid.set('PROD1');
    comp.newTierPriceListUid.set('PL1');
    comp.newTierMinQty.set('0');
    comp.newTierPrice.set('100');
    comp.newTierCurrency.set('TZS');
    comp.createTier();

    expect(comp.tierFormError()).toBeTruthy();
    expect(svc.createTier).not.toHaveBeenCalled();
  });

  it('calls createTier with correct request on valid input', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    const svc = TestBed.inject(PricingRuleService) as any;
    await vi.runAllTimersAsync();

    comp.newTierProductUid.set('PROD1');
    comp.newTierPriceListUid.set('PL1');
    comp.newTierMinQty.set('10');
    comp.newTierPrice.set('950.00');
    comp.newTierCurrency.set('TZS');
    comp.createTier();
    await vi.runAllTimersAsync();

    expect(svc.createTier).toHaveBeenCalledOnce();
    const req = svc.createTier.mock.calls[0][0];
    expect(req.companyUid).toBe('CO1');
    expect(req.productUid).toBe('PROD1');
    expect(req.priceListUid).toBe('PL1');
    expect(req.minQty).toBe('10');
    expect(req.unitPriceAmount).toBe('950.00');
    expect(req.currency).toBe('TZS');
  });
});

// ── Load customer prices ──────────────────────────────────────────────────────

describe('PricingRulesComponent — loadCustomerPrices', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canManage: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls listCustomerPrices with resolved customerId', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    const svc = TestBed.inject(PricingRuleService) as any;
    await vi.runAllTimersAsync();

    comp.cpCustomerUid.set('CUST1');
    comp.loadCustomerPrices();
    await vi.runAllTimersAsync();

    expect(svc.listCustomerPrices).toHaveBeenCalledWith('10', '40');
    expect(comp.cpState()).toBe('idle');
    expect(comp.cpRows().length).toBe(1);
  });

  it('does nothing when customer not selected', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    const svc = TestBed.inject(PricingRuleService) as any;
    await vi.runAllTimersAsync();

    svc.listCustomerPrices.mockClear();
    comp.loadCustomerPrices();

    expect(svc.listCustomerPrices).not.toHaveBeenCalled();
  });
});

// ── Create customer price validation ──────────────────────────────────────────

describe('PricingRulesComponent — createCustomerPrice validation', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canManage: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('requires customer uid', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    const svc = TestBed.inject(PricingRuleService) as any;
    await vi.runAllTimersAsync();

    comp.newCpProductUid.set('PROD1');
    comp.newCpPrice.set('100');
    comp.newCpCurrency.set('TZS');
    comp.createCustomerPrice();

    expect(comp.cpFormError()).toBeTruthy();
    expect(svc.createCustomerPrice).not.toHaveBeenCalled();
  });

  it('calls createCustomerPrice with correct request', async () => {
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    const svc = TestBed.inject(PricingRuleService) as any;
    await vi.runAllTimersAsync();

    comp.newCpCustomerUid.set('CUST1');
    comp.newCpProductUid.set('PROD1');
    comp.newCpPrice.set('1200.00');
    comp.newCpCurrency.set('TZS');
    comp.createCustomerPrice();
    await vi.runAllTimersAsync();

    expect(svc.createCustomerPrice).toHaveBeenCalledOnce();
    const req = svc.createCustomerPrice.mock.calls[0][0];
    expect(req.companyUid).toBe('CO1');
    expect(req.customerUid).toBe('CUST1');
    expect(req.productUid).toBe('PROD1');
    expect(req.unitPriceAmount).toBe('1200.00');
    expect(req.currency).toBe('TZS');
    expect(req.effectiveFrom).toBeNull();
    expect(req.effectiveTo).toBeNull();
  });
});

// ── 403 forbidden ─────────────────────────────────────────────────────────────

describe('PricingRulesComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets tierState to forbidden when listTiers returns 403', async () => {
    makeBed({
      listTiersImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.tierProductUid.set('PROD1');
    comp.tierPriceListUid.set('PL1');
    comp.loadTiers();
    await vi.runAllTimersAsync();

    expect(comp.tierState()).toBe('forbidden');
  });

  it('sets cpState to forbidden when listCustomerPrices returns 403', async () => {
    makeBed({
      listCpImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });
    const comp = TestBed.createComponent(PricingRulesComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.cpCustomerUid.set('CUST1');
    comp.loadCustomerPrices();
    await vi.runAllTimersAsync();

    expect(comp.cpState()).toBe('forbidden');
  });
});
