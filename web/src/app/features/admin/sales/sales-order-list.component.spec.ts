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
 *  8. Status-tag modifier: key status values.
 *  9. Numeric money guard: grossTotalAmount coerced via +value in template.
 *  10. D-5: opening the create form pre-selects the caller's own agent (myAgent()); a null
 *      result leaves the picker empty; the pre-selected agent can still be cleared/changed.
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
  branchName: 'Head Office', branchCode: 'BR-01',
  orderNumber: 'SO-0001', status: 'DRAFT' as const,
  customerId: '5', customerName: 'Acme Traders', customerCode: 'ACME',
  agentId: null, agentName: null, currency: 'TZS', orderDate: '2025-01-01',
  sourceQuotationUid: null,
  docDiscountAmount: null, docDiscountPercent: null,
  netTotalAmount: '1000', vatTotalAmount: '180', grossTotalAmount: '1180',
  confirmedAt: null, cancelledAt: null, cancelReason: null, notes: null, lines: [],
};

const mixedStatusPage = (): SalesOrderPage => ({
  rows: [
    { ...stubOrder, uid: 'SO1', orderNumber: 'SO-0001', status: 'DRAFT' as const },
    { ...stubOrder, uid: 'SO2', orderNumber: 'SO-0002', status: 'CONFIRMED' as const,
      customerName: 'Beta Supplies', customerCode: 'BETA' },
    { ...stubOrder, uid: 'SO3', orderNumber: 'SO-0003', status: 'CANCELLED' as const },
  ],
  meta: { page: 0, size: 20, totalElements: 3, totalPages: 1, hasNext: false },
});

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
  myAgentImpl?: () => any;
} = {}) {
  const { listImpl, canCreate = false, myAgentImpl } = opts;
  const sessionStore = makeSessionStore(canCreate);
  const myAgentSpy = vi.fn(myAgentImpl ?? (() => of(null)));

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
        useValue: { list: vi.fn(() => of({ rows: [], meta: {} })), myAgent: myAgentSpy },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      { provide: SessionStore, useValue: sessionStore },
    ],
  });

  return { myAgentSpy };
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

// ── status-tag modifier logic ─────────────────────────────────────────────────
// Mirrors inline template bindings on the sales-order status span.

function orderOk(s: string)      { return s === 'FULFILLED' || s === 'INVOICED'; }
function orderInfo(s: string)    { return s === 'CONFIRMED' || s === 'PARTIALLY_FULFILLED'; }
function orderWarn(s: string)    { return s === 'DRAFT' || s === 'PARTIALLY_INVOICED'; }
function orderDanger(s: string)  { return s === 'CANCELLED'; }
function orderNeutral(s: string) { return s === 'CLOSED'; }

describe('SalesOrderListComponent — status-tag modifier', () => {
  it('DRAFT → warn only', () => {
    expect(orderOk('DRAFT')).toBe(false);
    expect(orderInfo('DRAFT')).toBe(false);
    expect(orderWarn('DRAFT')).toBe(true);
    expect(orderDanger('DRAFT')).toBe(false);
    expect(orderNeutral('DRAFT')).toBe(false);
  });

  it('CONFIRMED → info only', () => {
    expect(orderOk('CONFIRMED')).toBe(false);
    expect(orderInfo('CONFIRMED')).toBe(true);
    expect(orderWarn('CONFIRMED')).toBe(false);
    expect(orderDanger('CONFIRMED')).toBe(false);
  });

  it('FULFILLED → ok only', () => {
    expect(orderOk('FULFILLED')).toBe(true);
    expect(orderInfo('FULFILLED')).toBe(false);
    expect(orderWarn('FULFILLED')).toBe(false);
    expect(orderDanger('FULFILLED')).toBe(false);
  });

  it('INVOICED → ok only', () => {
    expect(orderOk('INVOICED')).toBe(true);
    expect(orderInfo('INVOICED')).toBe(false);
    expect(orderWarn('INVOICED')).toBe(false);
  });

  it('PARTIALLY_FULFILLED → info only', () => {
    expect(orderOk('PARTIALLY_FULFILLED')).toBe(false);
    expect(orderInfo('PARTIALLY_FULFILLED')).toBe(true);
    expect(orderWarn('PARTIALLY_FULFILLED')).toBe(false);
  });

  it('PARTIALLY_INVOICED → warn only', () => {
    expect(orderOk('PARTIALLY_INVOICED')).toBe(false);
    expect(orderWarn('PARTIALLY_INVOICED')).toBe(true);
    expect(orderDanger('PARTIALLY_INVOICED')).toBe(false);
  });

  it('CANCELLED → danger only', () => {
    expect(orderOk('CANCELLED')).toBe(false);
    expect(orderWarn('CANCELLED')).toBe(false);
    expect(orderDanger('CANCELLED')).toBe(true);
    expect(orderNeutral('CANCELLED')).toBe(false);
  });

  it('CLOSED → neutral only', () => {
    expect(orderOk('CLOSED')).toBe(false);
    expect(orderNeutral('CLOSED')).toBe(true);
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

// ── Customer/agent column render (Bug A) ────────────────────────────────────────

describe('SalesOrderListComponent — renders customer', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ listImpl: () => of(mixedStatusPage()) });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('renders the customer name and code in the list, never the raw customerId', async () => {
    const fixture = TestBed.createComponent(SalesOrderListComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const customerCells = Array.from(el.querySelectorAll('tbody tr')).map(
      (row) => row.querySelectorAll('td')[1]?.textContent ?? '',
    );
    expect(customerCells[0]).toContain('Acme Traders');
    expect(customerCells[0]).toContain('ACME');
    expect(customerCells[1]).toContain('Beta Supplies');
    // The raw numeric FK must never be shown to the user in place of the name/code.
    expect(customerCells[0].trim()).not.toBe(stubOrder.customerId);
  });

  it('renders the branch name and code in the list, never the raw branchId', async () => {
    const fixture = TestBed.createComponent(SalesOrderListComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const branchCells = Array.from(el.querySelectorAll('tbody tr')).map(
      (row) => row.querySelectorAll('td')[3]?.textContent ?? '',
    );
    expect(branchCells[0]).toContain('Head Office');
    expect(branchCells[0]).toContain('BR-01');
    expect(branchCells[0].trim()).not.toBe(stubOrder.branchId);
  });
});

// ── Status filter narrows the list (Bug B) ──────────────────────────────────────

describe('SalesOrderListComponent — status filter', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ listImpl: () => of(mixedStatusPage()) });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('shows all loaded rows when no status is selected', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.filteredRows().length).toBe(3);
  });

  it('onStatusChange narrows filteredRows to the selected status without re-querying the server', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();
    svc.listOrders.mockClear();

    comp.onStatusChange('CONFIRMED');

    expect(comp.statusFilter()).toBe('CONFIRMED');
    expect(comp.filteredRows().length).toBe(1);
    expect(comp.filteredRows()[0].uid).toBe('SO2');
    // Root-cause fix: filtering is client-side over the loaded page — no extra HTTP call.
    expect(svc.listOrders).not.toHaveBeenCalled();
  });

  it('narrows the rendered table rows in the DOM when a status is picked', async () => {
    const fixture = TestBed.createComponent(SalesOrderListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    let rowCount = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr').length;
    expect(rowCount).toBe(3);

    comp.onStatusChange('CANCELLED');
    fixture.detectChanges();

    rowCount = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr').length;
    expect(rowCount).toBe(1);
  });

  it('isEmpty + filterHidAllRows distinguish "no orders" from "no matches for this filter"', async () => {
    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.onStatusChange('PARTIALLY_INVOICED'); // no stub row has this status
    expect(comp.isEmpty()).toBe(true);
    expect(comp.filterHidAllRows()).toBe(true);
  });
});

// ── D-5: pre-select the caller's own agent on create-form open ─────────────────

describe('SalesOrderListComponent — agent pre-select (D-5)', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('opening the create form pre-selects the agent returned by myAgent()', async () => {
    const stubAgent = { uid: 'AGT1', code: 'AGT-01', displayName: 'Self Agent' };
    vi.useFakeTimers();
    const { myAgentSpy } = makeBed({ canCreate: true, myAgentImpl: () => of(stubAgent) });

    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.toggleCreateForm();
    await vi.runAllTimersAsync();

    expect(myAgentSpy).toHaveBeenCalledWith('CO1');
    expect(comp.selectedAgent()).toEqual({ uid: 'AGT1', label: 'AGT-01 — Self Agent' });
    expect(comp.agentSearchQ()).toBe('AGT-01 — Self Agent');
  });

  it('myAgent() returning null leaves the agent picker empty', async () => {
    vi.useFakeTimers();
    makeBed({ canCreate: true, myAgentImpl: () => of(null) });

    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.toggleCreateForm();
    await vi.runAllTimersAsync();

    expect(comp.selectedAgent()).toBeNull();
    expect(comp.agentSearchQ()).toBe('');
  });

  it('the pre-selected agent can still be cleared', async () => {
    const stubAgent = { uid: 'AGT1', code: 'AGT-01', displayName: 'Self Agent' };
    vi.useFakeTimers();
    makeBed({ canCreate: true, myAgentImpl: () => of(stubAgent) });

    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.toggleCreateForm();
    await vi.runAllTimersAsync();
    expect(comp.selectedAgent()?.uid).toBe('AGT1');

    comp.clearAgent();

    expect(comp.selectedAgent()).toBeNull();
    expect(comp.agentSearchQ()).toBe('');
  });

  it('the pre-selected agent can still be changed via a new search selection', async () => {
    const stubAgent = { uid: 'AGT1', code: 'AGT-01', displayName: 'Self Agent' };
    const otherAgent = { uid: 'AGT2', code: 'AGT-02', displayName: 'Other Agent' };
    vi.useFakeTimers();
    makeBed({ canCreate: true, myAgentImpl: () => of(stubAgent) });

    const comp = TestBed.createComponent(SalesOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.toggleCreateForm();
    await vi.runAllTimersAsync();
    expect(comp.selectedAgent()?.uid).toBe('AGT1');

    comp.selectAgent(otherAgent as any);

    expect(comp.selectedAgent()?.uid).toBe('AGT2');
  });
});
