/**
 * PosSaleComponent specs.
 *
 * Covers:
 *  1. Renders once: fires company + options load on startup.
 *  2. submit() requires session.
 *  3. submit() requires customer.
 *  4. submit() requires currency.
 *  5. submit() requires at least one line.
 *  6. submit() validates tendered >= subtotal.
 *  7. submit() calls processSale with correct payload (customerId resolved from uid).
 *  8. 403 is the guard — canSell computed from hasPermission.
 *  9. lineSubtotal computes qty × price − discount.
 * 10. resetSale clears form state.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from '../parties/customer.service';
import { AgentService } from '../parties/agent.service';
import { ProductService } from '../products/product.service';
import { PosService } from './pos.service';
import { PosSaleComponent } from './pos-sale.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const stubCustomer = {
  id: '5', uid: 'CUST1', companyId: '10', code: 'C001', displayName: 'Walk-in',
  legalName: null, tin: null, vatRegistered: false, vrn: null, businessRegNo: null,
  mobileMoneyNo: null, phone: null, email: null, physicalAddress: null, postalAddress: null,
  region: null, district: null, partyType: 'INDIVIDUAL' as const,
  customerKind: 'CASH_WALK_IN' as const, creditLimit: null, paymentTermsDays: null,
  status: 'ACTIVE' as const,
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
};

const stubInvoice = {
  id: '99', uid: 'INV1', companyId: '10', branchId: '2', documentType: 'INVOICE' as const,
  invoiceNumber: 'INV-0001', status: 'FINALISED' as const, customerId: '5',
  customerName: 'Walk-in', agentId: null, agentName: null, routeUid: null, routeCode: null,
  routeName: null, currency: 'TZS', docDiscountAmount: null, docDiscountPercent: null,
  netTotalAmount: '1000.00', vatTotalAmount: '0.00', grossTotalAmount: '1000.00',
  taxSummary: null, finalisedAt: null, finalisedBy: null, voidedAt: null, voidedBy: null,
  voidReason: null, notes: null, version: null, createdAt: null, createdBy: null,
  updatedAt: null, updatedBy: null,
};

const emptySessionPage = () => ({
  rows: [],
  meta: { page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false },
});

function makeSessionStore(canSell = false) {
  return {
    hasPermission: vi.fn(() => canSell),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { canSell?: boolean; processSaleImpl?: () => any } = {}) {
  const { canSell = true, processSaleImpl } = opts;
  const sessionStore = makeSessionStore(canSell);

  TestBed.configureTestingModule({
    imports: [PosSaleComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PosService,
        useValue: {
          listSessions: vi.fn(() => of(emptySessionPage())),
          processSale: vi.fn(processSaleImpl ?? (() => of(stubInvoice))),
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
        useValue: { list: vi.fn(() => of({ rows: [stubCustomer], meta: {} })) },
      },
      {
        provide: AgentService,
        useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [], meta: {} })),
          listUnits: vi.fn(() => of({ rows: [], meta: {} })),
        },
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

describe('PosSaleComponent — init', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads companies on startup', async () => {
    const _comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const orgSvc = TestBed.inject(OrganisationService) as any;
    await vi.runAllTimersAsync();
    expect(orgSvc.current).toHaveBeenCalledTimes(1);
  });
});

// ── submit() validation ────��───────────────────────────────────────────────────

describe('PosSaleComponent — submit validation', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('requires session', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('');
    comp.submit();
    expect(comp.formError()).toMatch(/session/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });

  it('requires customer', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('');
    comp.submit();
    expect(comp.formError()).toMatch(/customer/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });

  it('requires currency', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1');
    comp.currency.set('');
    comp.submit();
    expect(comp.formError()).toMatch(/currency/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });

  it('requires at least one line', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1');
    comp.currency.set('TZS');
    comp.lines.set([]);
    comp.submit();
    expect(comp.formError()).toMatch(/line/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });

  it('rejects tendered < subtotal', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1');
    comp.currency.set('TZS');
    // seed customers so resolve works
    comp.customers.set([stubCustomer]);
    comp.lines.set([{
      id: 'line-1', productUid: 'P1', productId: '10', productName: 'Widget',
      unitUid: 'U1', unitId: '1', unitName: 'pcs', quantity: '2', unitPrice: '500.00', lineDiscountAmount: '0.00',
    }]);
    comp.tenderedAmount.set('500'); // subtotal=1000, tendered=500 → under
    comp.submit();
    expect(comp.formError()).toMatch(/less than/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });
});

// ── submit() success ───────────────────────────────────────────────────────────

describe('PosSaleComponent — submit success', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls processSale with customerId (id, not uid) resolved from uid', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.customers.set([stubCustomer]);
    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1'); // uid
    comp.currency.set('TZS');
    comp.lines.set([{
      id: 'line-1', productUid: 'P1', productId: '10', productName: 'Widget',
      unitUid: 'U1', unitId: '1', unitName: 'pcs', quantity: '2', unitPrice: '500.00', lineDiscountAmount: '0.00',
    }]);
    comp.tenderedAmount.set('1000');
    comp.submit();

    expect(svc.processSale).toHaveBeenCalledOnce();
    const payload = svc.processSale.mock.calls[0][0];
    // customerId must be the numeric id '5', not the uid 'CUST1'
    expect(payload.customerId).toBe('5');
    expect(payload.sessionUid).toBe('SESS1');
    expect(payload.currency).toBe('TZS');
    expect(payload.lines).toHaveLength(1);
    expect(payload.tenderedAmount).toBe('1000');
  });
});

// ── lineSubtotal ───────────────────────────────────────────────────────────────

describe('PosSaleComponent — lineSubtotal', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('computes qty × price − discount', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    await vi.runAllTimersAsync();
    const line = { id: 'l1', productUid: '', productId: '', productName: '', unitUid: '', unitId: '', unitName: '', quantity: '3', unitPrice: '200.00', lineDiscountAmount: '50.00' };
    expect(comp.lineSubtotal(line)).toBe(550);
  });
});

// ── resetSale ─────────────────────────────────────────────────────────────────

describe('PosSaleComponent — resetSale', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('clears form state after reset', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1');
    comp.lines.set([{ id: 'l1', productUid: 'p', productId: '1', productName: 'P', unitUid: 'u', unitId: '1', unitName: 'pcs', quantity: '1', unitPrice: '100', lineDiscountAmount: '0' }]);
    comp.resetSale();

    expect(comp.selectedSessionUid()).toBe('');
    expect(comp.selectedCustomerUid()).toBe('');
    expect(comp.lines()).toHaveLength(0);
  });
});

// ── canSell permission ─────────────────────────────────────────────────────────

describe('PosSaleComponent — canSell gate', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('canSell is false when hasPermission returns false', async () => {
    makeBed({ canSell: false });
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.canSell()).toBe(false);
  });
});
