/**
 * ProductMasterComponent — unit tests.
 *
 * Runs under @angular/build:unit-test with Vitest in zoneless mode.
 * Angular fakeAsync is unavailable — uses vi.useFakeTimers() + vi.runAllTimersAsync()
 * (mirrors the pattern in agent-list.component.spec.ts and product-list.component.spec.ts).
 *
 * Covers:
 *  1. Create mode: calls productService.create (anchor) on save, then sub-resource calls.
 *  2. Default price-list resolution: 'DEFAULT' code wins; single list wins; else empty.
 *  3. All-branches toggle: assigns every branch; when deselected a branch the toggle clears.
 *  4. BR-PROD-01: SERVICE forces stockable=false on the create payload.
 *  5. Anchor failure stops the sequence — no sub-steps called.
 *  6. Sub-step (price) failure: section marked failed, product uid still present.
 *  7. Edit mode: calls productService.update (not create).
 *  8. Opening stock: skipped when qty is blank / non-positive / type=SERVICE.
 *  9. Barcode primary enforcement: first row auto-primary; setPrimaryBarcode transfers flag.
 * 10. Form validation: name required; base unit required.
 * 11. Full happy-path: all sections done.
 * 12. Weighed goods (ADR-0044 D-1b): setWeighing is skipped when the toggle is off; called with
 *     the newly-created uid when it's on; a weighing-only failure doesn't undo/hide the already-
 *     saved product (fails only that section).
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
import { SupplierService } from '../parties/supplier.service';
import { StockService } from '../stock/stock.service';
import { ProductService } from './product.service';
import { ProductMasterComponent } from './product-master.component';
import type { PriceListDto, UnitOfMeasureDto } from '../models/product.model';
import type { Branch } from '../models/branch.model';

// ── Stubs ─────────────────────────────────────────────────────────────────────

type MockSvc = Record<string, ReturnType<typeof vi.fn>>;

const ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const CO  = { uid: 'CO1', id: '10', name: 'Main Co' };

function makeUnit(uid: string, code: string): UnitOfMeasureDto {
  return { id: uid, uid, companyId: '10', code, name: code, status: 'ACTIVE',
    version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null };
}
function makePriceList(uid: string, code: string, isDefault = false): PriceListDto {
  return { id: uid, uid, companyId: '10', code, name: code, priceIncludesVat: true, isDefault,
    status: 'ACTIVE', version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null };
}
function makeBranch(uid: string, code: string): Branch {
  return { id: uid, uid, companyId: '10', companyUid: 'CO1', code, name: code,
    timeZone: 'Africa/Dar_es_Salaam', isDefault: false, status: 'ACTIVE' };
}

const UNIT_PCS          = makeUnit('U1', 'PCS');
const UNIT_CTN          = makeUnit('U2', 'CTN');
const PRICE_LIST_DEFAULT = makePriceList('PL1', 'DEFAULT', false);
const PRICE_LIST_RETAIL  = makePriceList('PL2', 'RETAIL', false);
const PRICE_LIST_ONLY    = makePriceList('PL3', 'STANDARD', false);
const BRANCH_A           = makeBranch('B1', 'BR-A');
const BRANCH_B           = makeBranch('B2', 'BR-B');

const CREATED_PRODUCT = {
  id: '100', uid: 'PUID1', companyId: '10', code: 'PROD-0001', name: 'Widget',
  type: 'GOODS' as const, sellable: true, stockable: true,
  baseUnitUid: 'U1', baseUnitCode: 'PCS', baseUnitName: 'Pieces',
  cost: null, vatStatus: 'STANDARD' as const, status: 'ACTIVE' as const,
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
};

function makeSessionStore(activeBranchUid = 'B1') {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal<string | null>(activeBranchUid),
  };
}

function makeProductService(overrides: Partial<MockSvc> = {}): MockSvc {
  return {
    create:         vi.fn(() => of(CREATED_PRODUCT)),
    update:         vi.fn(() => of(CREATED_PRODUCT)),
    getByUid:       vi.fn(() => of(CREATED_PRODUCT)),
    listUnits:      vi.fn(() => of({ rows: [UNIT_PCS, UNIT_CTN],
      meta: { page: 0, size: 200, totalElements: 2, totalPages: 1, hasNext: false } })),
    listPriceLists: vi.fn(() => of([PRICE_LIST_DEFAULT, PRICE_LIST_RETAIL])),
    listBarcodes:   vi.fn(() => of([])),
    listBulkPacks:  vi.fn(() => of([])),
    listPrices:     vi.fn(() => of([])),
    listBranches:   vi.fn(() => of([])),
    setPrice:       vi.fn(() => of({})),
    addBarcode:     vi.fn(() => of({})),
    addBulkPack:    vi.fn(() => of({})),
    assignBranch:   vi.fn(() => of({})),
    setWeighing:    vi.fn(() => of(CREATED_PRODUCT)),
    ...overrides,
  };
}

/** Cast an injected token to MockSvc for assertion. */
function asMock(token: unknown): MockSvc {
  return token as unknown as MockSvc;
}

interface BedOptions {
  productServiceOverrides?: Partial<MockSvc>;
  activeBranchUid?: string;
  openingBalance?: ReturnType<typeof vi.fn>;
}

/** Configure TestBed and return the openingBalance spy for assertions. */
function makeBed(opts: BedOptions = {}): ReturnType<typeof vi.fn> {
  const openingBalance = opts.openingBalance ?? vi.fn(() => of({}));
  TestBed.configureTestingModule({
    imports: [ProductMasterComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: ProductService,
        useValue: makeProductService(opts.productServiceOverrides ?? {}) },
      { provide: OrganisationService,
        useValue: { current: vi.fn(() => of(ORG)) } },
      { provide: CompanyService,
        useValue: { list: vi.fn(() => of([CO])) } },
      { provide: BranchService,
        useValue: { list: vi.fn(() => of([BRANCH_A, BRANCH_B])) } },
      { provide: SupplierService,
        useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
      { provide: StockService,
        useValue: { openingBalance } },
      { provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore,
        useValue: makeSessionStore(opts.activeBranchUid ?? 'B1') },
    ],
  });
  return openingBalance;
}

// ── 1. Create mode — anchor call ──────────────────────────────────────────────

describe('ProductMasterComponent — create mode anchor', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls productService.create on save with required fields', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.save();
    await vi.runAllTimersAsync();

    const svc = asMock(TestBed.inject(ProductService));
    expect(svc['create']).toHaveBeenCalledOnce();
    const req = (svc['create'].mock.calls[0] as unknown[])[0] as Record<string, unknown>;
    expect(req['name']).toBe('Widget');
    expect(req['baseUnitUid']).toBe('U1');
    expect(req['companyUid']).toBe('CO1');
  });

  it('BR-PROD-01: SERVICE type sends stockable=false regardless of checkbox', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Consulting');
    comp.fBaseUnitUid.set('U1');
    comp.onTypeChange('SERVICE');
    comp.fStockable.set(true); // overridden by BR-PROD-01

    comp.save();
    await vi.runAllTimersAsync();

    const svc = asMock(TestBed.inject(ProductService));
    const req = (svc['create'].mock.calls[0] as unknown[])[0] as Record<string, unknown>;
    expect(req['stockable']).toBe(false);
    expect(req['type']).toBe('SERVICE');
  });

  it('sends category / brand / manufacturer / purchasable in payload', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.fCategory.set('Electronics');
    comp.fBrand.set('Acme');
    comp.fManufacturer.set('ACME Corp');
    comp.fPurchasable.set(false);

    comp.save();
    await vi.runAllTimersAsync();

    const svc = asMock(TestBed.inject(ProductService));
    const req = (svc['create'].mock.calls[0] as unknown[])[0] as Record<string, unknown>;
    expect(req['category']).toBe('Electronics');
    expect(req['brand']).toBe('Acme');
    expect(req['manufacturer']).toBe('ACME Corp');
    expect(req['purchasable']).toBe(false);
  });
});

// ── 2. Validation ─────────────────────────────────────────────────────────────

describe('ProductMasterComponent — form validation', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets formError and does not call create when name is blank', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('');
    comp.fBaseUnitUid.set('U1');
    comp.save();

    expect(comp.formError()).toBeTruthy();
    expect(asMock(TestBed.inject(ProductService))['create']).not.toHaveBeenCalled();
  });

  it('sets formError and does not call create when base unit is blank', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('');
    comp.save();

    expect(comp.formError()).toBeTruthy();
    expect(asMock(TestBed.inject(ProductService))['create']).not.toHaveBeenCalled();
  });
});

// ── 3. Anchor failure stops sequence ─────────────────────────────────────────

describe('ProductMasterComponent — anchor failure', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({
      productServiceOverrides: {
        create: vi.fn(() => throwError(() => new HttpErrorResponse({
          status: 400, error: { errors: ['Name already taken'] },
        }))),
      },
    });
  });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('marks anchor section failed; setPrice and assignBranch not called', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.save();
    await vi.runAllTimersAsync();

    const sections = comp.sectionResults();
    expect(sections[0].status).toBe('failed');
    expect(sections[0].error).toBe('Name already taken');

    const svc = asMock(TestBed.inject(ProductService));
    expect(svc['setPrice']).not.toHaveBeenCalled();
    expect(svc['assignBranch']).not.toHaveBeenCalled();
  });

  it('surfaces the anchor failure on-screen instead of a silent no-op: saveComplete + formError set, saving reset, no product uid', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.save();
    await vi.runAllTimersAsync();

    // Visible feedback: the result panel renders (Product section failed) and/or the inline
    // form banner is set — either way, the screen must show something, and the button must
    // become clickable again (not stuck spinning).
    expect(comp.saveComplete()).toBe(true);
    expect(comp.sectionResults()[0].status).toBe('failed');
    expect(comp.anchorFailed()).toBe(true);
    expect(comp.formError()).toBe('Name already taken');
    expect(comp.saving()).toBe(false);
    // No product was actually created — savedUid must stay null so "Open product" can't be a
    // dead link and a retry re-attempts a real create.
    expect(comp.savedUid()).toBeNull();

    fixture.detectChanges();
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('Name already taken');
    // "Open product" must not render when nothing was saved.
    expect(html).not.toContain('Open product');
    // "Continue editing" must be available to get back to an editable form.
    expect(html).toContain('Continue editing');
  });
});

// ── 4. Sub-step price failure ─────────────────────────────────────────────────

describe('ProductMasterComponent — price sub-step failure', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({
      productServiceOverrides: {
        setPrice: vi.fn(() => throwError(() => new HttpErrorResponse({
          status: 422, error: { errors: ['Invalid amount'] },
        }))),
      },
    });
  });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('product section=done, price section=failed, savedUid captured', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.priceRows.set([{ priceListUid: 'PL1', amount: '1500', currency: 'TZS', effectiveFrom: '2026-07-01' }]);

    comp.save();
    await vi.runAllTimersAsync();

    const sections = comp.sectionResults();
    expect(sections[0].status).toBe('done');
    expect(sections[1].status).toBe('failed');
    expect(comp.savedUid()).toBe('PUID1');
  });
});

// ── 5. Default price-list resolution ─────────────────────────────────────────

describe('ProductMasterComponent — default price-list resolution', () => {
  afterEach(() => { TestBed.resetTestingModule(); });

  it('picks the list with code=DEFAULT', async () => {
    vi.useFakeTimers();
    makeBed(); // lists: [DEFAULT, RETAIL]
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.defaultPriceListUid()).toBe('PL1');
    vi.useRealTimers();
  });

  it('picks the only list when exactly one exists', async () => {
    vi.useFakeTimers();
    makeBed({ productServiceOverrides: { listPriceLists: vi.fn(() => of([PRICE_LIST_ONLY])) } });
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.defaultPriceListUid()).toBe('PL3');
    vi.useRealTimers();
  });

  it('returns empty string when multiple lists and none named DEFAULT/STANDARD', async () => {
    vi.useFakeTimers();
    makeBed({
      productServiceOverrides: {
        listPriceLists: vi.fn(() => of([
          makePriceList('PLA', 'WHOLESALE'),
          makePriceList('PLB', 'EXPORT'),
        ])),
      },
    });
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.defaultPriceListUid()).toBe('');
    vi.useRealTimers();
  });

  it('prefers isDefault=true over code-name heuristic for auto-selected selling price', async () => {
    // WHOLESALE has no DEFAULT/STANDARD code but isDefault=true — must win.
    vi.useFakeTimers();
    makeBed({
      productServiceOverrides: {
        listPriceLists: vi.fn(() => of([
          makePriceList('PLA', 'WHOLESALE', true),  // isDefault flag
          makePriceList('PLB', 'STANDARD', false),  // would win on code alone
        ])),
      },
    });
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // defaultPriceListUid should be the isDefault list, not the STANDARD-coded one.
    expect(comp.defaultPriceListUid()).toBe('PLA');
    // The auto-initialised price row should carry that uid.
    expect(comp.priceRows()[0]?.priceListUid).toBe('PLA');
    vi.useRealTimers();
  });
});

// ── 6. All-branches toggle ────────────────────────────────────────────────────

describe('ProductMasterComponent — all-branches toggle', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('initialises with all branches active (toggle=true)', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.allBranchesToggle()).toBe(true);
    expect(comp.branchRows().every((r) => r.active)).toBe(true);
    expect(comp.branchRows().length).toBe(2);
  });

  it('calls assignBranch for all 2 branches when toggle=true on save', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.save();
    await vi.runAllTimersAsync();

    const svc = asMock(TestBed.inject(ProductService));
    expect(svc['assignBranch'].mock.calls.length).toBe(2);
  });

  it('clears allBranchesToggle when a branch is deselected', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.updateBranchRow(0, { active: false });
    expect(comp.allBranchesToggle()).toBe(false);
  });

  it('calls assignBranch only for selected branch when toggle=false', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.updateBranchRow(0, { active: false }); // deselect B1
    // toggle is now false; only B2 remains active

    comp.save();
    await vi.runAllTimersAsync();

    const svc = asMock(TestBed.inject(ProductService));
    const calls = svc['assignBranch'].mock.calls as unknown[][];
    expect(calls.length).toBe(1);
    expect((calls[0]![1] as Record<string, unknown>)['branchUid']).toBe('B2');
  });
});

// ── 7. Opening stock ──────────────────────────────────────────────────────────

describe('ProductMasterComponent — opening stock', () => {
  afterEach(() => { TestBed.resetTestingModule(); });

  it('does NOT call openingBalance when qty is blank', async () => {
    vi.useFakeTimers();
    const obs = makeBed();
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.openingQty.set('');
    comp.save();
    await vi.runAllTimersAsync();

    expect(obs).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  it('does NOT call openingBalance when type=SERVICE', async () => {
    vi.useFakeTimers();
    const obs = makeBed();
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Consulting');
    comp.fBaseUnitUid.set('U1');
    comp.onTypeChange('SERVICE');
    comp.openingQty.set('10');
    comp.save();
    await vi.runAllTimersAsync();

    expect(obs).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  it('calls openingBalance with productUid when stockable + qty > 0', async () => {
    vi.useFakeTimers();
    const obs = vi.fn(() => of({ id: 'M1', uid: 'MV1' }));
    makeBed({ openingBalance: obs });
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.fStockable.set(true);
    comp.openingQty.set('50');
    comp.save();
    await vi.runAllTimersAsync();

    expect(obs).toHaveBeenCalledOnce();
    const req = (obs.mock.calls[0] as unknown[])[0] as Record<string, unknown>;
    expect(req['productUid']).toBe('PUID1');
    expect(req['quantity']).toBe('50');
    vi.useRealTimers();
  });

  it('marks opening-stock section failed when no active branch uid', async () => {
    vi.useFakeTimers();
    makeBed({ activeBranchUid: '' });
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.fStockable.set(true);
    comp.openingQty.set('10');
    comp.save();
    await vi.runAllTimersAsync();

    expect(comp.sectionResults()[5]!.status).toBe('failed');
    vi.useRealTimers();
  });
});

// ── 8. Edit mode ──────────────────────────────────────────────────────────────

describe('ProductMasterComponent — edit mode', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls productService.update (not create) when uid input is set', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    fixture.componentRef.setInput('uid', 'PUID1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget Updated');
    comp.fBaseUnitUid.set('U1');
    comp.save();
    await vi.runAllTimersAsync();

    const svc = asMock(TestBed.inject(ProductService));
    expect(svc['update']).toHaveBeenCalledOnce();
    expect(svc['create']).not.toHaveBeenCalled();
  });
});

// ── 9. Barcode primary enforcement ───────────────────────────────────────────

describe('ProductMasterComponent — barcode primary flag', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('first barcode row is automatically primary even if newBarcodePrimary=false', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newBarcodeValue.set('1234567890123');
    comp.newBarcodePrimary.set(false);
    comp.addBarcodeRow();

    expect(comp.barcodeRows()[0]?.isPrimary).toBe(true);
  });

  it('setPrimaryBarcode moves primary flag to the target row', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newBarcodeValue.set('1111111111111');
    comp.addBarcodeRow();
    const id1 = comp.barcodeRows()[0]!.localId;

    comp.newBarcodeValue.set('2222222222222');
    comp.addBarcodeRow();
    const id2 = comp.barcodeRows()[1]!.localId;

    comp.setPrimaryBarcode(id2);

    const rows = comp.barcodeRows();
    expect(rows.find((r) => r.localId === id1)?.isPrimary).toBe(false);
    expect(rows.find((r) => r.localId === id2)?.isPrimary).toBe(true);
  });
});

// ── 10. Full happy-path save sequence ─────────────────────────────────────────

describe('ProductMasterComponent — full happy-path', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ openingBalance: vi.fn(() => of({ id: 'M1', uid: 'MV1' })) });
  });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('all 6 sections=done, saveComplete=true, savedUid set', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    comp.fStockable.set(true);
    comp.openingQty.set('20');
    comp.priceRows.set([{ priceListUid: 'PL1', amount: '1500', currency: 'TZS', effectiveFrom: '2026-07-01' }]);
    comp.newBarcodeValue.set('1234567890123');
    comp.addBarcodeRow();
    comp.newBulkPackUnitUid.set('U2');
    comp.newBulkPackFactor.set('12');
    comp.addBulkPackRow();

    comp.save();
    await vi.runAllTimersAsync();

    expect(comp.saveComplete()).toBe(true);
    expect(comp.savedUid()).toBe('PUID1');
    expect(comp.sectionResults().every((s) => s.status === 'done')).toBe(true);

    const svc = asMock(TestBed.inject(ProductService));
    expect(svc['create']).toHaveBeenCalledOnce();
    expect(svc['setPrice']).toHaveBeenCalledOnce();
    expect(svc['addBarcode']).toHaveBeenCalledOnce();
    expect(svc['addBulkPack']).toHaveBeenCalledOnce();
    expect(svc['assignBranch'].mock.calls.length).toBe(2); // both branches
  });
});

// ── 11. Weighed goods (ADR-0044 D-1b) ─────────────────────────────────────────

describe('ProductMasterComponent — weighed goods sub-step', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('create-without-weighing: does not call setWeighing when "Sold by weight" is left off', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Widget');
    comp.fBaseUnitUid.set('U1');
    // fWeighed left at its default (false).
    comp.save();
    await vi.runAllTimersAsync();

    const svc = asMock(TestBed.inject(ProductService));
    expect(svc['setWeighing']).not.toHaveBeenCalled();
    expect(comp.sectionResults().find((s) => s.label === 'Weighed goods')?.status).toBe('done');
    expect(comp.sectionResults().every((s) => s.status === 'done')).toBe(true);
  });

  it('create-with-weighing: chains setWeighing with the newly-created uid after the product is saved', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Weighed Cheese');
    comp.fBaseUnitUid.set('U1');
    comp.fWeighed.set(true);
    comp.fTareWeight.set(' 0.050 ');
    comp.fScaleStep.set(' 0.005 ');
    comp.fMaxSaleWeight.set(' 25 ');
    comp.save();
    await vi.runAllTimersAsync();

    const svc = asMock(TestBed.inject(ProductService));
    expect(svc['create']).toHaveBeenCalledOnce();
    // Chained after create — called with the uid the create() call returned, not a blank/pending one.
    expect(svc['setWeighing']).toHaveBeenCalledWith('PUID1', {
      weighed: true,
      tareWeight: '0.050',
      scaleStep: '0.005',
      maxSaleWeight: '25',
    });
    expect(comp.sectionResults().find((s) => s.label === 'Weighed goods')?.status).toBe('done');
  });

  it('sends nulls for tare/scaleStep/maxSaleWeight when the fields are left blank', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Weighed Cheese');
    comp.fBaseUnitUid.set('U1');
    comp.fWeighed.set(true);
    comp.save();
    await vi.runAllTimersAsync();

    const svc = asMock(TestBed.inject(ProductService));
    expect(svc['setWeighing']).toHaveBeenCalledWith('PUID1', {
      weighed: true,
      tareWeight: null,
      scaleStep: null,
      maxSaleWeight: null,
    });
  });
});

// ── 12. Weighed goods sub-step failure ────────────────────────────────────────

describe('ProductMasterComponent — weighed goods sub-step failure', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({
      productServiceOverrides: {
        setWeighing: vi.fn(() => throwError(() => new HttpErrorResponse({
          status: 422,
          error: { errors: ['A weighed product must use a weight base unit such as kilograms.'] },
        }))),
      },
    });
  });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fails only the Weighed goods section — the already-created product is not hidden as unsaved', async () => {
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fName.set('Weighed Cheese');
    comp.fBaseUnitUid.set('U1'); // PCS in the test fixture, not a weight unit — server would reject
    comp.fWeighed.set(true);
    comp.save();
    await vi.runAllTimersAsync();

    // The anchor (product) succeeded — savedUid is set and its section is 'done' — so the
    // failure must not read as "the product wasn't saved".
    expect(comp.savedUid()).toBe('PUID1');
    expect(comp.sectionResults()[0].status).toBe('done');
    expect(comp.anchorFailed()).toBe(false);

    const weighingSection = comp.sectionResults().find((s) => s.label === 'Weighed goods');
    expect(weighingSection?.status).toBe('failed');
    expect(weighingSection?.error).toBe('A weighed product must use a weight base unit such as kilograms.');
    expect(comp.hasAnyFailure()).toBe(true);

    fixture.detectChanges();
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('Product saved. Some details need attention.');
    expect(html).toContain('Open product');
  });
});
