/**
 * ProductDetailComponent — Prices section specs (ADR-0048 multi-unit / per-pack pricing).
 *
 * Runs under @angular/build:unit-test with Vitest in zoneless mode. The component's
 * constructor defers init() via queueMicrotask — use vi.useFakeTimers() + vi.runAllTimersAsync()
 * (mirrors delivery-detail.component.spec.ts).
 *
 * Covers:
 *  1. The Unit select on "Set Price" renders "Base unit" plus each configured pack unit
 *     (sourced from ProductService.listBulkPacks — the product's own pack units).
 *  2. setPrice() omits unitUid from the request when Base unit is selected (back-compat).
 *  3. setPrice() includes unitUid when a pack unit is selected.
 *  4. removePrice() appends unitUid for a per-unit (pack) price row, and omits it for the
 *     base row (back-compatible).
 *  5. The prices table renders "Base" for a null-unit row and the unit name for a pack row.
 *  6. Weighed goods (ADR-0044 D-1b): toggle reveals/hides tare/scaleStep/maxSaleWeight inputs,
 *     saveWeighing() threads the request correctly (including the clear-on-off path), the
 *     read-back coerces wire numbers to strings, and a server validation error surfaces inline.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchService } from '../branch/branch.service';
import { ProductService } from './product.service';
import { ProductDetailComponent } from './product-detail.component';
import type { ProductBulkPackDto, ProductPriceDto } from '../models/product.model';

// ── Fixtures ────────────────────────────────────────────────────────────────────

const ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const CO = { uid: 'CO1', id: '10', name: 'Main Co', organisationId: '1', code: 'CO1', legalName: null, taxId: null, timeZone: 'Africa/Dar_es_Salaam', status: 'ACTIVE' };

const PRODUCT = {
  id: '100', uid: 'PUID1', companyId: '10', code: 'PROD-0001', name: 'Widget',
  description: null, type: 'GOODS' as const, sellable: true, stockable: true,
  baseUnitUid: 'U1', baseUnitCode: 'PCS', baseUnitName: 'Pieces',
  cost: null, vatStatus: 'STANDARD' as const, status: 'ACTIVE' as const,
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
};

const BULK_PACK_CARTON: ProductBulkPackDto = {
  id: '1', uid: 'BP1', productId: '100',
  unitUid: 'U2', unitCode: 'CTN', unitName: 'Carton', factorToBase: '12',
};

const PRICE_LIST = {
  id: '1', uid: 'PL1', companyId: '10', code: 'DEFAULT', name: 'Default List',
  isDefault: true, status: 'ACTIVE', version: null, createdAt: null, createdBy: null,
  updatedAt: null, updatedBy: null,
};

const BASE_PRICE_ROW: ProductPriceDto = {
  id: '1', productId: '100', priceListId: '1', priceListUid: 'PL1',
  priceListCode: 'DEFAULT', priceListName: 'Default List', companyId: '10',
  price: { amount: '1000.00', currency: 'TZS' },
  unitUid: null, unitCode: null, unitName: null,
};

const PACK_PRICE_ROW: ProductPriceDto = {
  id: '2', productId: '100', priceListId: '1', priceListUid: 'PL1',
  priceListCode: 'DEFAULT', priceListName: 'Default List', companyId: '10',
  price: { amount: '11000.00', currency: 'TZS' },
  unitUid: 'U2', unitCode: 'CTN', unitName: 'Carton',
};

type MockSvc = Record<string, ReturnType<typeof vi.fn>>;

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal<string | null>(null),
  };
}

function makeProductService(overrides: Partial<MockSvc> = {}): MockSvc {
  return {
    getByUid: vi.fn(() => of(PRODUCT)),
    listUnits: vi.fn(() => of({ rows: [], meta: { page: 0, size: 200, totalElements: 0, totalPages: 1, hasNext: false } })),
    listBarcodes: vi.fn(() => of([])),
    listBulkPacks: vi.fn(() => of([BULK_PACK_CARTON])),
    listPrices: vi.fn(() => of([BASE_PRICE_ROW, PACK_PRICE_ROW])),
    listPriceLists: vi.fn(() => of([PRICE_LIST])),
    listComponents: vi.fn(() => of([])),
    listBranches: vi.fn(() => of([])),
    setPrice: vi.fn(() => of(BASE_PRICE_ROW)),
    removePrice: vi.fn(() => of(undefined)),
    setWeighing: vi.fn(() => of(PRODUCT)),
    ...overrides,
  };
}

function asMock(token: unknown): MockSvc {
  return token as unknown as MockSvc;
}

function makeBed(productServiceOverrides: Partial<MockSvc> = {}) {
  TestBed.configureTestingModule({
    imports: [ProductDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: ProductService, useValue: makeProductService(productServiceOverrides) },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([CO])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });
}

async function createDetail() {
  const fixture = TestBed.createComponent(ProductDetailComponent);
  fixture.componentRef.setInput('uid', 'PUID1');
  await vi.runAllTimersAsync();
  fixture.detectChanges();
  return fixture;
}

// ── 1 + 5. Unit select + table rendering ────────────────────────────────────────

describe('ProductDetailComponent — Prices: unit select + table rendering', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('renders "Base unit" plus each configured pack unit in the Unit select', async () => {
    makeBed();
    const fixture = await createDetail();

    const select: HTMLSelectElement | null = fixture.nativeElement.querySelector('#newPriceUnit');
    expect(select).toBeTruthy();
    const optionLabels = Array.from(select!.options).map((o) => o.textContent?.trim());
    expect(optionLabels).toContain('Base unit');
    expect(optionLabels).toContain('Carton');
  });

  it('labels the Unit select for accessibility', async () => {
    makeBed();
    const fixture = await createDetail();

    const label: HTMLLabelElement | null = fixture.nativeElement.querySelector('label[for="newPriceUnit"]');
    expect(label?.textContent?.trim()).toBe('Unit');
  });

  it('renders "Base" for a null-unit price row and the unit name for a pack row', async () => {
    makeBed();
    const fixture = await createDetail();

    const cells: string[] = Array.from(fixture.nativeElement.querySelectorAll('.erp-table tbody tr'))
      .map((row) => (row as HTMLElement).textContent ?? '');
    expect(cells.some((t) => t.includes('Base'))).toBe(true);
    expect(cells.some((t) => t.includes('Carton'))).toBe(true);
  });
});

// ── 2 + 3. setPrice() unitUid threading ─────────────────────────────────────────

describe('ProductDetailComponent — setPrice() unitUid threading', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('omits unitUid from the request when Base unit is selected (back-compat)', async () => {
    makeBed();
    const fixture = await createDetail();
    const comp = fixture.componentInstance;
    const svc = asMock(TestBed.inject(ProductService));

    comp.newPriceListUid.set('PL1');
    comp.newPriceAmount.set('1500.00');
    // newPriceUnitUid left at its default '' (Base unit).
    comp.setPrice();
    await vi.runAllTimersAsync();

    expect(svc['setPrice']).toHaveBeenCalledTimes(1);
    const [, request] = svc['setPrice'].mock.calls[0];
    expect(request.priceListUid).toBe('PL1');
    expect('unitUid' in request).toBe(false);
  });

  it('includes unitUid in the request when a pack unit is selected', async () => {
    makeBed();
    const fixture = await createDetail();
    const comp = fixture.componentInstance;
    const svc = asMock(TestBed.inject(ProductService));

    comp.newPriceListUid.set('PL1');
    comp.newPriceAmount.set('11000.00');
    comp.newPriceUnitUid.set('U2');
    comp.setPrice();
    await vi.runAllTimersAsync();

    expect(svc['setPrice']).toHaveBeenCalledTimes(1);
    const [, request] = svc['setPrice'].mock.calls[0];
    expect(request.priceListUid).toBe('PL1');
    expect(request.unitUid).toBe('U2');
  });

  it('resets newPriceUnitUid to Base after a successful set', async () => {
    makeBed();
    const fixture = await createDetail();
    const comp = fixture.componentInstance;

    comp.newPriceListUid.set('PL1');
    comp.newPriceAmount.set('11000.00');
    comp.newPriceUnitUid.set('U2');
    comp.setPrice();
    await vi.runAllTimersAsync();

    expect(comp.newPriceUnitUid()).toBe('');
  });
});

// ── 4. removePrice() unitUid query param ────────────────────────────────────────

describe('ProductDetailComponent — removePrice() unitUid threading', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls removePrice with the unitUid for a per-unit (pack) price row', async () => {
    makeBed();
    const fixture = await createDetail();
    const comp = fixture.componentInstance;
    const svc = asMock(TestBed.inject(ProductService));

    comp.removePrice(PACK_PRICE_ROW);
    await vi.runAllTimersAsync();

    expect(svc['removePrice']).toHaveBeenCalledWith('PUID1', 'PL1', 'U2');
  });

  it('calls removePrice without a unitUid for the base price row (back-compatible)', async () => {
    makeBed();
    const fixture = await createDetail();
    const comp = fixture.componentInstance;
    const svc = asMock(TestBed.inject(ProductService));

    comp.removePrice(BASE_PRICE_ROW);
    await vi.runAllTimersAsync();

    expect(svc['removePrice']).toHaveBeenCalledWith('PUID1', 'PL1', undefined);
  });
});

// ── Weighed goods (ADR-0044 D-1b) ───────────────────────────────────────────────

describe('ProductDetailComponent — Weighed goods', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('hides the tare/scale-step/max-weight inputs until "Sold by weight" is toggled on', async () => {
    makeBed();
    const fixture = await createDetail();
    const comp = fixture.componentInstance;

    expect(fixture.nativeElement.querySelector('#fWeighed')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('#fTareWeight')).toBeNull();
    expect(fixture.nativeElement.querySelector('#fScaleStep')).toBeNull();
    expect(fixture.nativeElement.querySelector('#fMaxSaleWeight')).toBeNull();

    comp.onWeighedToggle(true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#fTareWeight')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('#fScaleStep')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('#fMaxSaleWeight')).toBeTruthy();

    comp.onWeighedToggle(false);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#fTareWeight')).toBeNull();
  });

  it('saveWeighing() posts weighed=true with the trimmed tare/scaleStep/maxSaleWeight', async () => {
    makeBed();
    const fixture = await createDetail();
    const comp = fixture.componentInstance;
    const svc = asMock(TestBed.inject(ProductService));

    comp.onWeighedToggle(true);
    comp.fTareWeight.set(' 0.050 ');
    comp.fScaleStep.set(' 0.005 ');
    comp.fMaxSaleWeight.set(' 25 ');
    comp.saveWeighing();
    await vi.runAllTimersAsync();

    expect(svc['setWeighing']).toHaveBeenCalledWith('PUID1', {
      weighed: true,
      tareWeight: '0.050',
      scaleStep: '0.005',
      maxSaleWeight: '25',
    });
  });

  it('saveWeighing() sends nulls for tare/scaleStep/maxSaleWeight when unmarking as weighed', async () => {
    makeBed();
    const fixture = await createDetail();
    const comp = fixture.componentInstance;
    const svc = asMock(TestBed.inject(ProductService));

    // Leftover text in the fields must not leak through once weighed is turned off.
    comp.fTareWeight.set('0.050');
    comp.onWeighedToggle(false);
    comp.saveWeighing();
    await vi.runAllTimersAsync();

    expect(svc['setWeighing']).toHaveBeenCalledWith('PUID1', {
      weighed: false,
      tareWeight: null,
      scaleStep: null,
      maxSaleWeight: null,
    });
  });

  it('reflects the returned values after a successful save, coercing the wire numbers to strings', async () => {
    // Real wire shape: BigDecimal fields come back as JSON numbers, not strings — patchWeighingForm
    // must String()-coerce them or a later .trim() on the signal would throw (wire-number-vs-string).
    const updated = {
      ...PRODUCT,
      weighed: true,
      tareWeight: 0.05 as unknown as string,
      scaleStep: 0.005 as unknown as string,
      maxSaleWeight: 25 as unknown as string,
    };
    makeBed({ setWeighing: vi.fn(() => of(updated)) });
    const fixture = await createDetail();
    const comp = fixture.componentInstance;

    comp.onWeighedToggle(true);
    comp.saveWeighing();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(comp.fWeighed()).toBe(true);
    expect(comp.fTareWeight()).toBe('0.05');
    expect(comp.fScaleStep()).toBe('0.005');
    expect(comp.fMaxSaleWeight()).toBe('25');
    // Must not throw when trimmed again (e.g. a subsequent save without editing the field).
    expect(() => comp.fTareWeight().trim()).not.toThrow();
  });

  it('surfaces the server validation message inline on a failed save', async () => {
    const error = new HttpErrorResponse({
      status: 422,
      error: { errors: ['A weighed product must use a weight base unit such as kilograms.'] },
    });
    makeBed({ setWeighing: vi.fn(() => throwError(() => error)) });
    const fixture = await createDetail();
    const comp = fixture.componentInstance;

    comp.onWeighedToggle(true);
    comp.saveWeighing();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(comp.weighingError()).toBe('A weighed product must use a weight base unit such as kilograms.');
    expect(comp.savingWeighing()).toBe(false);
    const alertEl = fixture.nativeElement.querySelector('form[aria-label="Configure weighed goods"] [role="alert"]');
    expect(alertEl?.textContent).toContain('A weighed product must use a weight base unit');
  });
});
