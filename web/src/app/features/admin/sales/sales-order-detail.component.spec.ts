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
import { SalesOrderDto, SalesOrderLineDto } from '../models/sales-orders.model';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const agentlessOrder: SalesOrderDto = {
  uid: 'SO1', id: '1', companyId: '10', branchId: '1',
  branchName: 'Head Office', branchCode: 'BR-01',
  orderNumber: 'SO-0001', status: 'CONFIRMED' as const,
  customerId: '5', customerName: 'Acme Traders', customerCode: 'ACME',
  agentId: null, agentName: null, currency: 'TZS', orderDate: '2025-01-01',
  sourceQuotationUid: null,
  docDiscountAmount: null, docDiscountPercent: null,
  netTotalAmount: '1000', vatTotalAmount: '180', grossTotalAmount: '1180',
  confirmedAt: null, cancelledAt: null, cancelReason: null, notes: null, lines: [],
};

const orderWithAgent = { ...agentlessOrder, agentId: '42' };

const draftOrder: SalesOrderDto = {
  ...agentlessOrder,
  status: 'DRAFT' as const,
  approvalStatus: null,
};

const stubLine: SalesOrderLineDto = {
  id: '1', uid: 'LN1', lineNo: 1, productId: '1', productCode: 'P001', productName: 'Widget',
  unitId: '1', unitName: 'Each', qtyOrdered: '10', qtyOrderedBase: '10',
  qtyFulfilledBase: '0', qtyInvoicedBase: '0', qtyReservedBase: '0', openQtyBase: '10',
  listPriceAmount: null, unitPriceAmount: '100', priceOverridden: false,
  lineDiscountAmount: null, lineDiscountPercent: null, vatStatus: null, vatRate: null,
  netAmount: '1000', vatAmount: '180', grossAmount: '1180', currency: 'TZS',
};

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
  lines?: SalesOrderLineDto[];
  setAgentImpl?: () => any;
  submitForApprovalImpl?: () => any;
  canCreate?: boolean;
} = {}) {
  const { order = agentlessOrder, lines = [], setAgentImpl, submitForApprovalImpl, canCreate = true } = opts;
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
          listOrderLines: vi.fn(() => of(lines)),
          listDeliveriesForOrder: vi.fn(() => of([])),
          setOrderAgent: vi.fn(setAgentImpl ?? (() => of(orderWithAgent))),
          submitForApproval: vi.fn(submitForApprovalImpl ?? (() => of({ ...order, approvalStatus: 'PENDING' as const }))),
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

function createFixture(uid = 'SO1') {
  const fixture = TestBed.createComponent(SalesOrderDetailComponent);
  fixture.componentRef.setInput('uid', uid);
  return fixture;
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

  it('submitAgent() success shows a confirmation toast — Daudi should not have to reload to confirm it stuck', async () => {
    makeBed();
    const comp = createComponent();
    const alerts = TestBed.inject(AlertService) as unknown as { success: ReturnType<typeof vi.fn> };
    await vi.runAllTimersAsync();

    comp.selectedAgent.set({ uid: 'AGT1', label: 'Agent B' });
    comp.submitAgent();
    await vi.runAllTimersAsync();

    expect(alerts.success).toHaveBeenCalledWith(
      'Agent assigned',
      'Agent B is now the sales agent on this order.',
    );
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

// ── Header: customer + agent display (Bug A) ───────────────────────────────────

describe('SalesOrderDetailComponent — header shows customer and agent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('renders the customer name and code prominently, never the raw customerId', async () => {
    makeBed({ order: agentlessOrder });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const dts = Array.from(el.querySelectorAll('dt')).map((d) => d.textContent?.trim());
    const customerIdx = dts.indexOf('Customer');
    expect(customerIdx).toBeGreaterThanOrEqual(0);
    const customerDd = el.querySelectorAll('dd')[customerIdx];
    const customerText = customerDd.textContent ?? '';
    expect(customerText).toContain('Acme Traders');
    expect(customerText).toContain('ACME');
    // The raw numeric FK must never be shown to the user in place of the name/code.
    expect(customerText.trim()).not.toBe(agentlessOrder.customerId);
  });

  it('renders "—" for the sales agent when agentName is null', async () => {
    makeBed({ order: agentlessOrder });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Sales Agent');
    expect(text).toContain('—');
  });

  it('renders the sales agent name when present', async () => {
    makeBed({ order: { ...orderWithAgent, agentName: 'Jane Agent' } });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Jane Agent');
  });

  it('renders the branch name and code prominently, never the raw branchId', async () => {
    makeBed({ order: agentlessOrder });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const dts = Array.from(el.querySelectorAll('dt')).map((d) => d.textContent?.trim());
    const branchIdx = dts.indexOf('Branch');
    expect(branchIdx).toBeGreaterThanOrEqual(0);
    const branchDd = el.querySelectorAll('dd')[branchIdx];
    const branchText = branchDd.textContent ?? '';
    expect(branchText).toContain('Head Office');
    expect(branchText).toContain('BR-01');
    expect(branchText.trim()).not.toBe(agentlessOrder.branchId);
  });

  it('renders "—" for the branch when branchName is null', async () => {
    makeBed({ order: { ...agentlessOrder, branchName: null, branchCode: null } });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const dts = Array.from(el.querySelectorAll('dt')).map((d) => d.textContent?.trim());
    const branchIdx = dts.indexOf('Branch');
    const branchDd = el.querySelectorAll('dd')[branchIdx];
    expect(branchDd.textContent?.trim()).toBe('—');
  });
});

// ── Submit for approval + approval-status badge + confirm gating ────────────────

describe('SalesOrderDetailComponent — submit for approval', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('shows "Submit for Approval" for a DRAFT order with a null approvalStatus', async () => {
    makeBed({ order: draftOrder });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.canSubmitForApprovalNow()).toBe(true);

    const el = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(el.querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(buttons.some((t) => t?.includes('Submit for Approval'))).toBe(true);
  });

  it('hides "Submit for Approval" once the order is not DRAFT', async () => {
    makeBed({ order: agentlessOrder }); // CONFIRMED
    const comp = createComponent();
    await vi.runAllTimersAsync();
    expect(comp.canSubmitForApprovalNow()).toBe(false);
  });

  it('hides "Submit for Approval" when approvalStatus is already PENDING or APPROVED', async () => {
    makeBed({ order: { ...draftOrder, approvalStatus: 'PENDING' as const } });
    const comp = createComponent();
    await vi.runAllTimersAsync();
    expect(comp.canSubmitForApprovalNow()).toBe(false);

    TestBed.resetTestingModule();
    makeBed({ order: { ...draftOrder, approvalStatus: 'APPROVED' as const } });
    const comp2 = createComponent();
    await vi.runAllTimersAsync();
    expect(comp2.canSubmitForApprovalNow()).toBe(false);
  });

  it('does NOT re-offer "Submit for Approval" after a terminal decision — the engine forbids re-submitting the same order; it shows closed-approval guidance instead', async () => {
    // REJECTED / CANCELLED / RECALLED are terminal: the approvals engine 409s a re-submit, so the
    // button must be hidden and the order recovers by cancel + recreate (approvalClosed guidance).
    for (const status of ['REJECTED', 'CANCELLED', 'RECALLED'] as const) {
      TestBed.resetTestingModule();
      makeBed({ order: { ...draftOrder, approvalStatus: status } });
      const comp = createComponent();
      await vi.runAllTimersAsync();
      expect(comp.canSubmitForApprovalNow()).toBe(false);
      expect(comp.approvalClosed()).toBe(true);
    }
  });

  it('locks line editing (canEditLines=false) while an order is PENDING or APPROVED, but allows it when null or terminal-closed', async () => {
    for (const status of ['PENDING', 'APPROVED'] as const) {
      TestBed.resetTestingModule();
      makeBed({ order: { ...draftOrder, approvalStatus: status }, canCreate: true });
      const comp = createComponent();
      await vi.runAllTimersAsync();
      expect(comp.canEditLines()).toBe(false);
    }
    for (const status of [null, 'REJECTED'] as const) {
      TestBed.resetTestingModule();
      makeBed({ order: { ...draftOrder, approvalStatus: status }, canCreate: true });
      const comp = createComponent();
      await vi.runAllTimersAsync();
      expect(comp.canEditLines()).toBe(true);
    }
  });

  it('submitForApproval() calls the service, updates the order, and shows a success toast', async () => {
    makeBed({ order: draftOrder });
    const comp = createComponent();
    const svc = TestBed.inject(SalesOrdersService) as any;
    const alerts = TestBed.inject(AlertService) as unknown as { success: ReturnType<typeof vi.fn> };
    await vi.runAllTimersAsync();

    comp.submitForApproval();
    await vi.runAllTimersAsync();

    expect(svc.submitForApproval).toHaveBeenCalledOnce();
    expect(svc.submitForApproval).toHaveBeenCalledWith('SO1');
    expect(comp.order()?.approvalStatus).toBe('PENDING');
    expect(alerts.success).toHaveBeenCalledWith(
      'Submitted for approval',
      'SO-0001 is now awaiting approval.',
    );
    expect(comp.submittingForApproval()).toBe(false);
  });

  it('submitForApproval() surfaces a friendly API error', async () => {
    makeBed({
      order: draftOrder,
      submitForApprovalImpl: () =>
        throwError(() => new HttpErrorResponse({
          status: 409,
          error: { errors: ['This order does not require approval.'] },
        })),
    });
    const comp = createComponent();
    await vi.runAllTimersAsync();

    comp.submitForApproval();
    await vi.runAllTimersAsync();

    expect(comp.submitForApprovalError()).toContain('does not require approval');
    expect(comp.submittingForApproval()).toBe(false);
  });

  it('renders the "Awaiting approval" badge for a PENDING order', async () => {
    makeBed({ order: { ...draftOrder, approvalStatus: 'PENDING' as const } });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const badge = el.querySelector('.status-tag--warn[aria-label="Awaiting approval"]');
    expect(badge).toBeTruthy();
    expect(badge?.textContent).toContain('Awaiting approval');
  });

  it('renders the "Approved" badge for an APPROVED order', async () => {
    makeBed({ order: { ...draftOrder, approvalStatus: 'APPROVED' as const } });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const badge = el.querySelector('.status-tag--ok[aria-label="Approved"]');
    expect(badge).toBeTruthy();
  });

  it('renders no approval badge when approvalStatus is null', async () => {
    makeBed({ order: draftOrder });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[aria-label="Awaiting approval"]')).toBeNull();
    expect(el.querySelector('[aria-label="Approved"]')).toBeNull();
    expect(el.querySelector('[aria-label="Rejected"]')).toBeNull();
  });

  it('disables Confirm and shows a hint when approvalStatus is PENDING', async () => {
    makeBed({
      order: { ...draftOrder, approvalStatus: 'PENDING' as const },
      lines: [stubLine],
    });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.isApprovalPending()).toBe(true);
    expect(comp.canConfirmNow()).toBe(false);

    const el = fixture.nativeElement as HTMLElement;
    const confirmBtn = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Confirm Order'),
    );
    expect(confirmBtn?.disabled).toBe(true);
    expect(el.textContent).toContain('Awaiting approval before it can be confirmed.');
  });

  it('keeps Confirm enabled when approvalStatus is null or APPROVED (never-submitted / approved orders)', async () => {
    makeBed({ order: draftOrder, lines: [stubLine] });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.canConfirmNow()).toBe(true);

    TestBed.resetTestingModule();
    makeBed({ order: { ...draftOrder, approvalStatus: 'APPROVED' as const }, lines: [stubLine] });
    const fixture2 = createFixture();
    await vi.runAllTimersAsync();
    fixture2.detectChanges();
    expect(fixture2.componentInstance.canConfirmNow()).toBe(true);
  });
});
