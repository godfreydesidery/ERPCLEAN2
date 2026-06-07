/**
 * PurchaseOrderListComponent — key behaviour specs.
 *
 * Covers:
 *  1. Loads list on company selection.
 *  2. Debounced search fires a new list request.
 *  3. Create: requires supplier + currency; calls purchasesService.createOrder.
 *  4. 403 → state = 'forbidden'.
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
import { SupplierService } from '../parties/supplier.service';
import { PurchasesService } from './purchases.service';
import { PurchaseOrderListComponent } from './purchase-order-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const STUB_PO = {
  uid: 'PO1', id: '1', companyId: '10', branchId: '1',
  orderNumber: 'PO-0001', status: 'ORDERED', supplierId: '2',
  supplierCode: 'SUP001', supplierName: 'Supplier A',
  currency: 'TZS', orderTotalAmount: '50000',
  expectedDate: null, notes: null, orderedAt: null,
  voidedAt: null, voidReason: null, closedAt: null,
  createdAt: null, lines: null,
};

const emptyPage = () => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const poPage = () => ({
  rows: [STUB_PO],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(overrides: {
  listOrdersSpy?: ReturnType<typeof vi.fn>;
  createOrderSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const listOrdersSpy = overrides.listOrdersSpy ?? vi.fn(() => of(poPage()));
  const createOrderSpy = overrides.createOrderSpy ?? vi.fn(() => of(STUB_PO));

  TestBed.configureTestingModule({
    imports: [PurchaseOrderListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PurchasesService,
        useValue: { listOrders: listOrdersSpy, createOrder: createOrderSpy },
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
        provide: SupplierService,
        useValue: { list: vi.fn(() => of(emptyPage())) },
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

  return { listOrdersSpy, createOrderSpy };
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('PurchaseOrderListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Loads list ──────────────────────────────────────────────────────────

  it('loads purchase orders on company selection', async () => {
    const { listOrdersSpy } = makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(listOrdersSpy).toHaveBeenCalled();
    expect(comp.rows()).toHaveLength(1);
    expect(comp.state()).toBe('idle');
  });

  // ── 2. Debounced search ────────────────────────────────────────────────────

  it('fires list request after debounce on searchQ change', async () => {
    const { listOrdersSpy } = makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    const callsBefore = listOrdersSpy.mock.calls.length;
    comp.searchQ.set('Supplier A');
    await vi.advanceTimersByTimeAsync(310); // past debounce

    expect(listOrdersSpy.mock.calls.length).toBeGreaterThan(callsBefore);
  });

  // ── 3. Create — no supplier → formError ───────────────────────────────────

  it('sets formError and does NOT call createOrder when supplier is missing', async () => {
    const { createOrderSpy } = makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.showCreateForm.set(true);
    comp.newCurrency.set('TZS');
    // No supplier selected

    comp.create();

    expect(comp.formError()).toBe('Supplier is required.');
    expect(createOrderSpy).not.toHaveBeenCalled();
  });

  // ── 4. Create — success ───────────────────────────────────────────────────

  it('calls createOrder with correct payload when supplier and currency are set', async () => {
    const { createOrderSpy } = makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.showCreateForm.set(true);
    comp.selectedSupplier.set({ uid: 'SUP-UID-1', label: 'SUP001 — Supplier A' });
    comp.newCurrency.set('TZS');
    comp.newNotes.set('Test notes');

    comp.create();
    await vi.runAllTimersAsync();

    expect(createOrderSpy).toHaveBeenCalledOnce();
    const req = createOrderSpy.mock.calls[0][0];
    expect(req.supplierUid).toBe('SUP-UID-1');
    expect(req.currency).toBe('TZS');
    expect(req.notes).toBe('Test notes');
  });

  // ── 5. 403 → forbidden ────────────────────────────────────────────────────

  it('sets state=forbidden on 403 response', async () => {
    const listOrdersSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
    );
    makeBed({ listOrdersSpy });
    const fixture = TestBed.createComponent(PurchaseOrderListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
