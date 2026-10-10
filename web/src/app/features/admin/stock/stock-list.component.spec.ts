/**
 * StockListComponent — key behaviour specs.
 *
 * Covers:
 *  1. Loads on-hand list on company selection.
 *  2. Adjust: calls stockService.adjust with correct payload.
 *  3. Opening balance: validates positive qty, calls stockService.openingBalance.
 *  4. Forbidden (403) sets state = 'forbidden'.
 *  5. Reorder edit: calls setReorderLevel.
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
import { BranchService } from '../branch/branch.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { StockService } from './stock.service';
import { StockListComponent } from './stock-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const STUB_ON_HAND_ROW = {
  uid: 'SOH1', id: '1',
  companyId: '10', branchId: '1', productId: '5',
  productCode: 'P001', productName: 'Test Product',
  quantity: '100', reorderLevel: '10', maxQty: null,
  lastMovementAt: null, lastCountedAt: null,
  negative: false, low: false,
  version: '1', createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
};

const STUB_MOVEMENT = {
  uid: 'MOV1', id: '1',
  companyId: '10', branchId: '1', productId: '5',
  movementType: 'ADJUSTMENT', quantity: '5', direction: 'IN',
  sourceEventUid: null, sourceDocumentType: null, sourceDocumentUid: null,
  reasonCode: 'COUNT_CORRECTION', note: null,
  occurredAt: null, createdAt: null, createdBy: null,
};

const emptyPage = () => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const onHandPage = () => ({
  rows: [STUB_ON_HAND_ROW],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(overrides: {
  listOnHandSpy?: ReturnType<typeof vi.fn>;
  adjustSpy?: ReturnType<typeof vi.fn>;
  openingBalanceSpy?: ReturnType<typeof vi.fn>;
  setReorderLevelSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const listOnHandSpy = overrides.listOnHandSpy ?? vi.fn(() => of(onHandPage()));
  const adjustSpy = overrides.adjustSpy ?? vi.fn(() => of(STUB_MOVEMENT));
  const openingBalanceSpy = overrides.openingBalanceSpy ?? vi.fn(() => of(STUB_MOVEMENT));
  const setReorderLevelSpy = overrides.setReorderLevelSpy ?? vi.fn(() => of(STUB_ON_HAND_ROW));

  TestBed.configureTestingModule({
    imports: [StockListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: StockService,
        useValue: {
          listOnHand: listOnHandSpy,
          listMovements: vi.fn(() => of(emptyPage())),
          adjust: adjustSpy,
          openingBalance: openingBalanceSpy,
          setReorderLevel: setReorderLevelSpy,
        },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of(STUB_ORG)) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([STUB_COMPANY])) },
      },
      {
        provide: BranchService,
        useValue: { list: vi.fn(() => of([])) },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of(emptyPage())),
          listUnits: vi.fn(() => of(emptyPage())),
          listProductUnits: vi.fn(() => of([{ uid: 'PCS-UID', code: 'PCS', name: 'Pieces' }])),
          listBulkPacks: vi.fn(() => of([
            { uid: 'BP1', unitUid: 'CTN-UID', unitCode: 'CTN', unitName: 'Carton', factorToBase: '12' },
          ])),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { listOnHandSpy, adjustSpy, openingBalanceSpy, setReorderLevelSpy };
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('StockListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Loads list ──────────────────────────────────────────────────────────

  it('loads stock on-hand on company selection', async () => {
    const { listOnHandSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(listOnHandSpy).toHaveBeenCalled();
    expect(comp.rows()).toHaveLength(1);
    expect(comp.state()).toBe('idle');
  });

  // ── 1b. On-hand list sends ONLY q (no companyId/branchId) ───────────────────

  it('sends the search term as q and does not send companyId/branchId', async () => {
    const { listOnHandSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // Type into the search box (debounced) and let the pipeline fire.
    comp.searchQ.set('apple');
    await vi.runAllTimersAsync();

    // New service signature is listOnHand(q, page, size) — no company/branch args.
    expect(listOnHandSpy).toHaveBeenCalled();
    const lastCall = listOnHandSpy.mock.calls.at(-1)!;
    expect(lastCall[0]).toBe('apple');                 // q
    expect(typeof lastCall[1]).toBe('number');         // page
    expect(typeof lastCall[2]).toBe('number');         // size
    // No argument should look like a company/branch id payload object.
    expect(lastCall.length).toBeLessThanOrEqual(3);
  });

  // ── 1c. On-Hand view no longer renders Company/Branch dropdowns ─────────────

  it('does not render Company or Branch dropdowns on the On-Hand view', async () => {
    // Two companies + a branch available — yet neither dropdown should show on On-Hand.
    makeBed({});
    TestBed.overrideProvider(CompanyService, {
      useValue: { list: vi.fn(() => of([STUB_COMPANY, { ...STUB_COMPANY, uid: 'CO2', id: '11', name: 'Second Co' }])) },
    });
    TestBed.overrideProvider(BranchService, {
      useValue: { list: vi.fn(() => of([{ uid: 'BR1', id: '1', name: 'Main Branch' }])) },
    });

    const fixture = TestBed.createComponent(StockListComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('#companyPicker')).toBeNull();
    expect(el.querySelector('#branchFilter')).toBeNull();
    // The search box IS present on the On-Hand view.
    expect(el.querySelector('#stockSearch')).not.toBeNull();
  });

  // ── 2. Adjust ──────────────────────────────────────────────────────────────

  it('calls stockService.adjust with correct payload', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // Manually set up the adjust form as if openAdjustForm was called
    // and a product was resolved.
    comp.adjustingUid.set(STUB_ON_HAND_ROW.uid);
    comp.adjustSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test Product' });
    comp.adjustQty.set('10');
    comp.adjustReason.set('COUNT_CORRECTION');
    comp.adjustNote.set('Test note');

    comp.submitAdjust();
    await vi.runAllTimersAsync();

    expect(adjustSpy).toHaveBeenCalledOnce();
    const req = adjustSpy.mock.calls[0][0];
    expect(req.productUid).toBe('PROD-UID-1');
    expect(req.quantity).toBe('10');
    expect(req.reasonCode).toBe('COUNT_CORRECTION');
    expect(req.note).toBe('Test note');
  });

  // ── 3. Adjust validation — zero qty ───────────────────────────────────────

  it('sets adjustError and does NOT call adjust when qty is zero', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.adjustingUid.set(STUB_ON_HAND_ROW.uid);
    comp.adjustSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test' });
    comp.adjustQty.set('0');
    comp.adjustReason.set('COUNT_CORRECTION');

    comp.submitAdjust();

    expect(comp.adjustError()).toBeTruthy();
    expect(adjustSpy).not.toHaveBeenCalled();
  });

  // ── 4. Opening balance — positive qty required ─────────────────────────────

  it('sets openingError when qty is not positive', async () => {
    makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.openingSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test' });
    comp.openingQty.set('-5');

    comp.submitOpeningBalance();

    expect(comp.openingError()).toBeTruthy();
  });

  // ── 5. Opening balance calls service ──────────────────────────────────────

  it('calls stockService.openingBalance with correct payload', async () => {
    const { openingBalanceSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.openingSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test' });
    comp.openingQty.set('50');
    comp.openingNote.set('Initial load');

    comp.submitOpeningBalance();
    await vi.runAllTimersAsync();

    expect(openingBalanceSpy).toHaveBeenCalledOnce();
    const req = openingBalanceSpy.mock.calls[0][0];
    expect(req.productUid).toBe('PROD-UID-1');
    expect(req.quantity).toBe('50');
    expect(req.note).toBe('Initial load');
  });

  // ── 6. 403 forbidden ──────────────────────────────────────────────────────

  it('sets state=forbidden on 403 response', async () => {
    const listOnHandSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
    );
    makeBed({ listOnHandSpy });
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });

  // ── Absolute-mode adjustment ("Set to counted quantity") ────────────────────

  it('absolute mode computes the signed delta from the counted quantity and posts it', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.openAdjustForm(STUB_ON_HAND_ROW); // row.quantity = '100'
    comp.adjustSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test Product' });
    comp.setAdjustMode('absolute');
    comp.adjustNewQty.set('120');
    comp.adjustReason.set('COUNT_CORRECTION');

    comp.submitAdjust();
    await vi.runAllTimersAsync();

    expect(adjustSpy).toHaveBeenCalledOnce();
    const req = adjustSpy.mock.calls[0][0];
    expect(req.productUid).toBe('PROD-UID-1');
    expect(req.quantity).toBe('20'); // 120 - 100
  });

  it('absolute mode computes a negative delta when the counted quantity is lower', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.openAdjustForm(STUB_ON_HAND_ROW); // row.quantity = '100'
    comp.adjustSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test Product' });
    comp.setAdjustMode('absolute');
    comp.adjustNewQty.set('70');

    comp.submitAdjust();
    await vi.runAllTimersAsync();

    const req = adjustSpy.mock.calls[0][0];
    expect(req.quantity).toBe('-30'); // 70 - 100
  });

  it('absolute mode blocks a no-op submit (counted qty == current qty) with a friendly message', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.openAdjustForm(STUB_ON_HAND_ROW); // row.quantity = '100'
    comp.adjustSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test Product' });
    comp.setAdjustMode('absolute');
    comp.adjustNewQty.set('100'); // same as current

    comp.submitAdjust();

    expect(comp.adjustError()).toBeTruthy();
    expect(adjustSpy).not.toHaveBeenCalled();
  });

  it('the toolbar Adjust Stock button opens the standalone form and closes any per-row form', async () => {
    makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.openAdjustForm(STUB_ON_HAND_ROW);
    expect(comp.adjustingUid()).toBe(STUB_ON_HAND_ROW.uid);

    comp.toggleAdjustForm();
    expect(comp.showAdjustForm()).toBe(true);
    expect(comp.adjustingUid()).toBeNull(); // mutual exclusion

    comp.toggleAdjustForm();
    expect(comp.showAdjustForm()).toBe(false);
  });

  // ── LUI-01: the forms must actually save through the DOM ─────────────────────
  // The signal-level specs above never rendered the inputs, so they could not see that three of
  // them sat inside a <form> with [ngModel] and no `name` — Angular throws NG01352 on that, the
  // binding never attaches, and Save said "Enter a non-zero quantity" whatever was typed.

  /** Type into an input the way a user does: set the value, then fire `input`. */
  function typeInto(fixture: { nativeElement: HTMLElement }, selector: string, value: string): void {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement | null;
    expect(input, `${selector} should be rendered`).not.toBeNull();
    input!.value = value;
    input!.dispatchEvent(new Event('input'));
  }

  function submitForm(fixture: { nativeElement: HTMLElement }, selector: string): void {
    const form = fixture.nativeElement.querySelector(selector) as HTMLFormElement | null;
    expect(form, `${selector} should be rendered`).not.toBeNull();
    form!.dispatchEvent(new Event('submit'));
  }

  it('toolbar Adjust Stock saves a +/- quantity typed into the form', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    comp.toggleAdjustForm();
    fixture.detectChanges();
    comp.adjustSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test Product' });
    fixture.detectChanges();
    // ngModel registers with its parent form in a microtask — let it attach before typing.
    await vi.runAllTimersAsync();

    typeInto(fixture, '#adjustQtyToolbar', '-3');
    fixture.detectChanges();
    submitForm(fixture, '#adjustStockForm');
    await vi.runAllTimersAsync();

    expect(comp.adjustError()).toBeNull();
    expect(adjustSpy).toHaveBeenCalledOnce();
    expect(adjustSpy.mock.calls[0][0].quantity).toBe('-3');
  });

  it('toolbar "Set to counted quantity" saves the delta from the typed count', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    comp.toggleAdjustForm();
    comp.setAdjustMode('absolute');
    comp.adjustSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test Product' });
    comp.adjustCurrentQty.set('100');
    fixture.detectChanges();
    // ngModel registers with its parent form in a microtask — let it attach before typing.
    await vi.runAllTimersAsync();

    typeInto(fixture, '#adjNewQtyToolbar', '97');
    fixture.detectChanges();
    submitForm(fixture, '#adjustStockForm');
    await vi.runAllTimersAsync();

    expect(comp.adjustError()).toBeNull();
    expect(adjustSpy).toHaveBeenCalledOnce();
    expect(adjustSpy.mock.calls[0][0].quantity).toBe('-3');
  });

  it('row "Set to counted quantity" saves the delta from the typed count', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    comp.openAdjustForm(STUB_ON_HAND_ROW); // row.quantity = '100'
    comp.adjustSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test Product' });
    comp.setAdjustMode('absolute');
    fixture.detectChanges();
    // ngModel registers with its parent form in a microtask — let it attach before typing.
    await vi.runAllTimersAsync();

    typeInto(fixture, '#adjNewQty', '104');
    fixture.detectChanges();
    submitForm(fixture, 'form[aria-label^="Adjust stock for"]');
    await vi.runAllTimersAsync();

    expect(comp.adjustError()).toBeNull();
    expect(adjustSpy).toHaveBeenCalledOnce();
    expect(adjustSpy.mock.calls[0][0].quantity).toBe('4');
  });

  it('row Adjust corrects the row\'s own location (STK-01)', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.openAdjustForm({ ...STUB_ON_HAND_ROW, locationUid: 'LOC-BACK' });
    comp.adjustSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test Product' });
    comp.adjustQty.set('-2');
    comp.submitAdjust();
    await vi.runAllTimersAsync();

    expect(adjustSpy.mock.calls[0][0].locationUid).toBe('LOC-BACK');
  });

  it('toolbar adjust in cartons sends the carton unit and previews the base quantity (STK-08)', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.toggleAdjustForm();
    comp.selectAdjustProduct({ uid: 'PROD-UID-1', code: 'P001', name: 'Test Product' } as never);
    await vi.runAllTimersAsync();
    expect(comp.adjustUnits().map((u) => u.unitUid)).toEqual(['', 'CTN-UID']);

    comp.adjustUnitUid.set('CTN-UID');
    comp.adjustQty.set('-2');
    expect(comp.adjustBasePreview()).toBe('= -24 Pieces');
    comp.submitAdjust();
    await vi.runAllTimersAsync();

    expect(adjustSpy.mock.calls[0][0]).toMatchObject({ quantity: '-2', unitUid: 'CTN-UID' });
  });

  it('absolute mode in cartons converts the count to base before taking the delta (STK-08)', async () => {
    const { adjustSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.openAdjustForm(STUB_ON_HAND_ROW); // current 100 pieces
    comp.adjustSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test Product' });
    comp.adjustUnits.set([
      { unitUid: '', code: 'PCS', name: 'Pieces', factor: 1 },
      { unitUid: 'CTN-UID', code: 'CTN', name: 'Carton', factor: 12 },
    ]);
    expect(comp.adjustCurrentBreakdown()).toBe('8 CTN + 4 PCS');
    comp.setAdjustMode('absolute');
    comp.adjustUnitUid.set('CTN-UID');
    comp.adjustNewQty.set('9');                 // 108 pieces
    comp.submitAdjust();
    await vi.runAllTimersAsync();

    expect(adjustSpy.mock.calls[0][0].quantity).toBe('8');
    expect(adjustSpy.mock.calls[0][0].unitUid).toBeUndefined();
  });

  it('toolbar adjust asks for a location when the product sits at two shelves', async () => {
    const listOnHandSpy = vi.fn(() => of({
      rows: [
        { ...STUB_ON_HAND_ROW, uid: 'S1', quantity: '5', locationUid: 'LOC-A', locationName: 'Main Store' },
        { ...STUB_ON_HAND_ROW, uid: 'S2', quantity: '7', locationUid: 'LOC-B', locationName: 'Back Store' },
      ],
      meta: { page: 0, size: 20, totalElements: 2, totalPages: 1, hasNext: false },
    }));
    const { adjustSpy } = makeBed({ listOnHandSpy });
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.toggleAdjustForm();
    comp.selectAdjustProduct({ uid: 'PROD-UID-1', code: 'P001', name: 'Test Product' } as never);
    await vi.runAllTimersAsync();
    expect(comp.adjustLocations().map((l) => l.uid)).toEqual(['LOC-A', 'LOC-B']);

    comp.adjustQty.set('-1');
    comp.submitAdjust();
    expect(adjustSpy).not.toHaveBeenCalled();
    expect(comp.adjustError()).toContain('choose the location');

    comp.onAdjustLocationChange('LOC-B');
    expect(comp.adjustCurrentQty()).toBe('7');
    comp.submitAdjust();
    await vi.runAllTimersAsync();
    expect(adjustSpy.mock.calls[0][0].locationUid).toBe('LOC-B');
  });

  it('opening balance sends the chosen unit and cost (STK-08, PRD-07)', async () => {
    const { openingBalanceSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.toggleOpeningForm();
    comp.selectOpeningProduct({ uid: 'PROD-UID-1', code: 'P001', name: 'Test Product' } as never);
    await vi.runAllTimersAsync();
    comp.openingUnitUid.set('CTN-UID');
    comp.openingQty.set('3');
    comp.openingUnitCost.set('24000');
    expect(comp.openingBasePreview()).toBe('= 36 Pieces');
    comp.submitOpeningBalance();
    await vi.runAllTimersAsync();

    expect(openingBalanceSpy.mock.calls[0][0]).toMatchObject({
      productUid: 'PROD-UID-1', quantity: '3', unitUid: 'CTN-UID', unitCost: '24000',
    });
  });
  it('opening balance shows the server\'s reason on a 409 (STK-17)', async () => {
    const openingBalanceSpy = vi.fn(() => throwError(() => new HttpErrorResponse({
      status: 409, error: { errors: ['This product already has stock activity at this branch.'] },
    })));
    makeBed({ openingBalanceSpy });
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.openingSelectedProduct.set({ uid: 'PROD-UID-1', label: 'P001 — Test' });
    comp.openingQty.set('5');
    comp.submitOpeningBalance();
    await vi.runAllTimersAsync();

    expect(comp.openingError()).toBe('This product already has stock activity at this branch.');
  });

  // ── 7. Reorder level edit ─────────────────────────────────────────────────

  it('calls setReorderLevel with correct payload', async () => {
    const { setReorderLevelSpy } = makeBed();
    const fixture = TestBed.createComponent(StockListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.startReorderEdit(STUB_ON_HAND_ROW);
    comp.reorderEditValue.set('25');

    comp.saveReorderLevel(STUB_ON_HAND_ROW);
    await vi.runAllTimersAsync();

    expect(setReorderLevelSpy).toHaveBeenCalledOnce();
    const [uid, req] = setReorderLevelSpy.mock.calls[0];
    expect(uid).toBe(STUB_ON_HAND_ROW.uid);
    expect(req.reorderLevel).toBe('25');
  });
});
