/**
 * PurchaseOrderDetailComponent — line-unit picker behaviour specs.
 *
 * Covers:
 *  1. Selecting a product calls listProductUnits(productUid).
 *  2. Unit dropdown is populated with ONLY the returned units.
 *  3. First returned unit (base unit) is pre-selected after product pick.
 *  4. Unit list is cleared and pre-selection reset when a new product search starts.
 *  5. lineUnitsState reflects loading → idle lifecycle.
 *  6. lineUnitsState set to 'error' when listProductUnits fails.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ProductService } from '../products/product.service';
import { PurchasesService } from './purchases.service';
import { DocumentsService } from '../documents/documents.service';
import { PurchaseOrderDetailComponent } from './purchase-order-detail.component';

// ── Stubs ──────────────────────────────────────────────────────────────────────

const STUB_PO = {
  uid: 'PO-UID-1', id: '1', companyId: '10', branchId: '1',
  orderNumber: 'PO-0001', status: 'DRAFT', supplierId: '2',
  supplierCode: 'SUP001', supplierName: 'Supplier A',
  currency: 'TZS', orderTotalAmount: '0',
  expectedDate: null, notes: null, orderedAt: null,
  voidedAt: null, voidReason: null, closedAt: null, createdAt: null, lines: null,
};

const STUB_UNITS = [
  { uid: 'U-EACH', id: '1', code: 'EA', name: 'Each', symbol: 'ea', status: 'ACTIVE' },
  { uid: 'U-BOX',  id: '2', code: 'BX', name: 'Box',  symbol: 'bx', status: 'ACTIVE' },
];

const STUB_PRODUCT = {
  uid: 'PROD-UID-1', id: '5', code: 'P001', name: 'Widget',
  status: 'ACTIVE', companyId: '10',
};

function makeBed(overrides: {
  listProductUnitsSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const listProductUnitsSpy =
    overrides.listProductUnitsSpy ?? vi.fn(() => of(STUB_UNITS));

  TestBed.configureTestingModule({
    imports: [PurchaseOrderDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PurchasesService,
        useValue: {
          getOrderByUid: vi.fn(() => of(STUB_PO)),
          listOrderLines: vi.fn(() => of([])),
          addLine: vi.fn(() => of({})),
          removeLine: vi.fn(() => of(undefined)),
          placeOrder: vi.fn(() => of(STUB_PO)),
          closeOrder: vi.fn(() => of(STUB_PO)),
          voidOrder: vi.fn(() => of(STUB_PO)),
        },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [], meta: {} })),
          listProductUnits: listProductUnitsSpy,
        },
      },
      {
        provide: DocumentsService,
        useValue: { renderBlob: vi.fn(() => of(new Blob())) },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
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

  return { listProductUnitsSpy };
}

// ── Specs ──────────────────────────────────────────────────────────────────────

describe('PurchaseOrderDetailComponent — product-scoped unit picker', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Selecting a product calls listProductUnits ──────────────────────────

  it('calls listProductUnits with the selected product uid', async () => {
    const { listProductUnitsSpy } = makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    // Satisfy required input binding
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);

    expect(listProductUnitsSpy).toHaveBeenCalledWith('PROD-UID-1');
  });

  // ── 2. Unit dropdown populated with ONLY the returned units ───────────────

  it('populates lineUnits exclusively with units from listProductUnits', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    expect(comp.lineUnits()).toHaveLength(2);
    expect(comp.lineUnits().map((u) => u.uid)).toEqual(['U-EACH', 'U-BOX']);
  });

  // ── 3. First returned unit (base) is pre-selected ─────────────────────────

  it('pre-selects the first returned unit after product pick', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    expect(comp.newLineUnitUid()).toBe('U-EACH');
  });

  // ── 4. Unit list cleared when product search changes ──────────────────────

  it('clears lineUnits and resets unit selection when product search changes', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // First pick a product so units are loaded.
    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();
    expect(comp.lineUnits().length).toBeGreaterThan(0);

    // Then clear the search — units must be wiped.
    comp.onProductSearchChange('');
    expect(comp.lineUnits()).toHaveLength(0);
    expect(comp.newLineUnitUid()).toBe('');
    expect(comp.selectedProduct()).toBeNull();
  });

  // ── 5. lineUnitsState resolves to idle after a successful product pick ────

  it('lineUnitsState is idle after listProductUnits resolves successfully', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    // After the synchronous of() resolves, state must be idle (not loading or error).
    expect(comp.lineUnitsState()).toBe('idle');
  });

  // ── 6. lineUnitsState set to error on failure ─────────────────────────────

  it('sets lineUnitsState to error when listProductUnits fails', async () => {
    const { listProductUnitsSpy } = makeBed({
      listProductUnitsSpy: vi.fn(() => throwError(() => new Error('network'))),
    });
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    expect(comp.lineUnitsState()).toBe('error');
    expect(listProductUnitsSpy).toHaveBeenCalled();
  });
});
