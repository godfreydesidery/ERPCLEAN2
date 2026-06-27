/**
 * SalesOrderDetailComponent specs — Set/Change-agent action.
 *
 * Covers:
 *  1. hasAgent reflects the loaded order's agentId.
 *  2. canSetAgentNow: true in pre-invoice states with permission, false when invoiced/cancelled.
 *  3. submitAgent() validation: requires a selected agent.
 *  4. submitAgent() calls setOrderAgent with the picked uid, then updates the order signal.
 *  5. submitAgent() surfaces a friendly error from the API and stays open.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { AgentService } from '../parties/agent.service';
import { ProductService } from '../products/product.service';
import { SalesOrdersService } from './sales-orders.service';
import { SalesOrderDetailComponent } from './sales-order-detail.component';
import { SalesOrderDto } from '../models/sales-orders.model';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const agentlessOrder: SalesOrderDto = {
  uid: 'SO1', id: '1', companyId: '10', branchId: '1',
  orderNumber: 'SO-0001', status: 'CONFIRMED' as const,
  customerId: '5', agentId: null, currency: 'TZS', orderDate: '2025-01-01',
  sourceQuotationUid: null,
  docDiscountAmount: null, docDiscountPercent: null,
  netTotalAmount: '1000', vatTotalAmount: '180', grossTotalAmount: '1180',
  confirmedAt: null, cancelledAt: null, cancelReason: null, notes: null, lines: [],
};

const orderWithAgent = { ...agentlessOrder, agentId: '42' };

function makeSessionStore(canCreate = true) {
  return {
    hasPermission: vi.fn(() => canCreate),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: {
  order?: SalesOrderDto;
  setAgentImpl?: () => any;
  canCreate?: boolean;
} = {}) {
  const { order = agentlessOrder, setAgentImpl, canCreate = true } = opts;
  const sessionStore = makeSessionStore(canCreate);

  TestBed.configureTestingModule({
    imports: [SalesOrderDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: SalesOrdersService,
        useValue: {
          getOrderByUid: vi.fn(() => of(order)),
          listOrderLines: vi.fn(() => of([])),
          listDeliveriesForOrder: vi.fn(() => of([])),
          setOrderAgent: vi.fn(setAgentImpl ?? (() => of(orderWithAgent))),
        },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [], meta: {} })),
          listProductUnits: vi.fn(() => of([])),
        },
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

function createComponent(uid = 'SO1') {
  const fixture = TestBed.createComponent(SalesOrderDetailComponent);
  fixture.componentRef.setInput('uid', uid);
  return fixture.componentInstance;
}

describe('SalesOrderDetailComponent — set agent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('hasAgent is false for an agentless order, true once an agent is present', async () => {
    makeBed({ order: agentlessOrder });
    const comp = createComponent();
    await vi.runAllTimersAsync();
    expect(comp.hasAgent()).toBe(false);

    TestBed.resetTestingModule();
    makeBed({ order: orderWithAgent });
    const comp2 = createComponent();
    await vi.runAllTimersAsync();
    expect(comp2.hasAgent()).toBe(true);
  });

  it('canSetAgentNow is true in CONFIRMED with permission', async () => {
    makeBed({ order: agentlessOrder, canCreate: true });
    const comp = createComponent();
    await vi.runAllTimersAsync();
    expect(comp.canSetAgentNow()).toBe(true);
  });

  it('canSetAgentNow is false when the order is cancelled', async () => {
    makeBed({ order: { ...agentlessOrder, status: 'CANCELLED' as const }, canCreate: true });
    const comp = createComponent();
    await vi.runAllTimersAsync();
    expect(comp.canSetAgentNow()).toBe(false);
  });

  it('canSetAgentNow is false when the order is invoiced', async () => {
    makeBed({ order: { ...agentlessOrder, status: 'INVOICED' as const }, canCreate: true });
    const comp = createComponent();
    await vi.runAllTimersAsync();
    expect(comp.canSetAgentNow()).toBe(false);
  });

  it('canSetAgentNow is false without SALES.ORDER.CREATE permission', async () => {
    makeBed({ order: agentlessOrder, canCreate: false });
    const comp = createComponent();
    await vi.runAllTimersAsync();
    expect(comp.canSetAgentNow()).toBe(false);
  });

  it('submitAgent() requires a selected agent', async () => {
    makeBed();
    const comp = createComponent();
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedAgent.set(null);
    comp.submitAgent();

    expect(comp.agentError()).toBeTruthy();
    expect(svc.setOrderAgent).not.toHaveBeenCalled();
  });

  it('submitAgent() calls setOrderAgent with the picked uid and updates the order', async () => {
    makeBed();
    const comp = createComponent();
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedAgent.set({ uid: 'AGT1', label: 'Agent B' });
    comp.submitAgent();
    await vi.runAllTimersAsync();

    expect(svc.setOrderAgent).toHaveBeenCalledOnce();
    expect(svc.setOrderAgent).toHaveBeenCalledWith('SO1', { agentUid: 'AGT1' });
    expect(comp.order()?.agentId).toBe('42');
    expect(comp.hasAgent()).toBe(true);
    expect(comp.showAgentForm()).toBe(false);
  });

  it('submitAgent() surfaces a friendly API error and keeps the form open', async () => {
    makeBed({
      setAgentImpl: () =>
        throwError(() => new HttpErrorResponse({
          status: 409,
          error: { errors: ['This order has already been invoiced, so its agent can no longer be changed.'] },
        })),
    });
    const comp = createComponent();
    await vi.runAllTimersAsync();

    comp.showAgentForm.set(true);
    comp.selectedAgent.set({ uid: 'AGT1', label: 'Agent B' });
    comp.submitAgent();
    await vi.runAllTimersAsync();

    expect(comp.agentError()).toContain('already been invoiced');
    expect(comp.savingAgent()).toBe(false);
    expect(comp.showAgentForm()).toBe(true);
  });
});
