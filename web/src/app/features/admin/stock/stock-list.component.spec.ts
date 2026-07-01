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
