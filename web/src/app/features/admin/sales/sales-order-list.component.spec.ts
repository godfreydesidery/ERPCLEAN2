/**
 * SalesOrderListComponent specs.
 *
 * Covers:
 *  1. Renders: fires exactly one load on startup.
 *  2. isEmpty is true when no rows returned.
 *  3. create() validation: requires customer.
 *  4. create() validation: requires currency.
 *  5. create() validation: requires orderDate.
 *  6. create() calls soService.createOrder with correct payload (incl. optional agent).
 *  7. 403 response sets state to 'forbidden'.
 *  8. statusBadgeClass: key status values.
 *  9. Numeric money guard: grossTotalAmount coerced via +value in template.
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
import { CustomerService } from '../parties/customer.service';
import { AgentService } from '../parties/agent.service';
import { SalesOrdersService } from './sales-orders.service';
import type { SalesOrderPage } from './sales-orders.service';
import { SalesOrderListComponent } from './sales-order-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const emptyPage = (): SalesOrderPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const stubOrder = {
  uid: 'SO1', id: '1', companyId: '10', branchId: '1',
  orderNumber: 'SO-0001', status: 'DRAFT' as const,
  customerId: '5', agentId: null, currency: 'TZS', orderDate: '2025-01-01',
  sourceQuotationUid: null,
  docDiscountAmount: null, docDiscountPercent: null,
  netTotalAmount: '1000', vatTotalAmount: '180', grossTotalAmount: '1180',
  confirmedAt: null, cancelledAt: null, cancelReason: null, notes: null, lines: [],
};

function makeSessionStore(canCreate = false) {
  return {
    hasPermission: vi.fn(() => canCreate),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: {
  listImpl?: () => any;
  canCreate?: boolean;
} = {}) {
  const { listImpl, canCreate = false } = opts;
  const sessionStore = makeSessionStore(canCreate);

  TestBed.configureTestingModule({
    imports: [SalesOrderListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: SalesOrdersService,
        useValue: {
          listOrders: vi.fn(listImpl ?? (() => of(emptyPage()))),
          createOrder: vi.fn(() => of(stubOrder)),
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
        provide: CustomerService,
        useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) },
      },
      {
        provide: AgentService,
        useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      { provide: SessionStore, useValue: sessionStore },
    ],
  });
}

// ── Init ───────────────────────────────────────────────────────────────────────

describe('SalesOrderListComponent — init', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('fires exactly one load on startup after company resolves', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    expect(svc.listOrders).toHaveBeenCalledTimes(1);
    expect(svc.listOrders).toHaveBeenCalledWith('10', 0, 20);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no orders returned', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── Create form validation ─────────────────────────────────────────────────────

describe('SalesOrderListComponent — create form', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canCreate: true });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('create() validation: requires customer', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set(null);
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createOrder).not.toHaveBeenCalled();
  });

  it('create() validation: requires currency', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.newCurrency.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createOrder).not.toHaveBeenCalled();
  });

  it('create() validation: requires orderDate', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.newCurrency.set('TZS');
    comp.newOrderDate.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createOrder).not.toHaveBeenCalled();
  });

  it('create() calls createOrder with correct payload', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.newCurrency.set('TZS');
    comp.newOrderDate.set('2025-01-10');
    comp.create();

    expect(svc.createOrder).toHaveBeenCalledOnce();
    const req = svc.createOrder.mock.calls[0][0];
    expect(req.companyUid).toBe('CO1');
    expect(req.customerUid).toBe('CUST1');
    expect(req.currency).toBe('TZS');
    expect(req.orderDate).toBe('2025-01-10');
  });

  it('create() with optional agent passes agentUid', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.selectedAgent.set({ uid: 'AGT1', label: 'Agent B' });
    comp.newCurrency.set('TZS');
    comp.newOrderDate.set('2025-01-10');
    comp.create();

    const req = svc.createOrder.mock.calls[0][0];
    expect(req.agentUid).toBe('AGT1');
  });
});

// ── Status badge class ──────────────────────────────────────────────────────────

describe('SalesOrderListComponent — statusBadgeClass', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('DRAFT → text-bg-warning', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.statusBadgeClass('DRAFT')).toBe('text-bg-warning');
  });

  it('CONFIRMED → text-bg-primary', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.statusBadgeClass('CONFIRMED')).toBe('text-bg-primary');
  });

  it('FULFILLED → text-bg-success', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.statusBadgeClass('FULFILLED')).toBe('text-bg-success');
  });

  it('CANCELLED → text-bg-danger', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.statusBadgeClass('CANCELLED')).toBe('text-bg-danger');
  });
});

// ── Numeric money guard ──────────────────────────────────────────────────────────

describe('SalesOrderListComponent — numeric money guard', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('grossTotalAmount string "1180" coerces to number via unary +', () => {
    // The template uses +o.grossTotalAmount | number:'1.2-2'
    // Verify the coercion contract used in the template
    expect(+stubOrder.grossTotalAmount).toBe(1180);
    expect(isNaN(+stubOrder.grossTotalAmount)).toBe(false);
  });

  it('orderLabel returns orderNumber when present, DRAFT when null', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.orderLabel(stubOrder as any)).toBe('SO-0001');
    expect(comp.orderLabel({ ...stubOrder, orderNumber: null } as any)).toBe('DRAFT');
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('SalesOrderListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('sets state to forbidden when listOrders returns 403', async () => {
    makeBed({
      listImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
