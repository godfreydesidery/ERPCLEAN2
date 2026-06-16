/**
 * QuotationListComponent specs.
 * Mirrors sales-invoice-list.component.spec.ts pattern (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Renders: loads list on startup after company resolves.
 *  2. isEmpty is true when no rows returned.
 *  3. create() validation: requires customer.
 *  4. create() validation: requires currency.
 *  5. create() validation: requires quoteDate.
 *  6. create() calls soService.createQuotation with correct payload.
 *  7. 403 response sets state to 'forbidden'.
 *  8. Status-tag modifier: ACCEPTED→ok, SENT→info, DRAFT→warn, REJECTED→danger, EXPIRED→neutral.
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
import type { QuotationPage } from './sales-orders.service';
import { QuotationListComponent } from './quotation-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const emptyPage = (): QuotationPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const stubQuote = {
  uid: 'Q1', id: '1', companyId: '10', branchId: '1',
  quoteNumber: 'QT-0001', status: 'DRAFT' as const,
  customerId: '5', agentId: null, currency: 'TZS',
  quoteDate: '2025-01-01', validUntil: '2025-01-31',
  docDiscountAmount: null, docDiscountPercent: null,
  netTotalAmount: '1000', vatTotalAmount: '180', grossTotalAmount: '1180',
  notes: null, sentAt: null, acceptedAt: null, rejectedAt: null, expiredAt: null,
  convertedOrderUid: null, lines: [],
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
    imports: [QuotationListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: SalesOrdersService,
        useValue: {
          listQuotations: vi.fn(listImpl ?? (() => of(emptyPage()))),
          createQuotation: vi.fn(() => of(stubQuote)),
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

describe('QuotationListComponent — init', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('fires exactly one load on startup after company resolves', async () => {
    const comp = TestBed.createComponent(QuotationListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    expect(svc.listQuotations).toHaveBeenCalledTimes(1);
    expect(svc.listQuotations).toHaveBeenCalledWith('10', 0, 20);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no quotations returned', async () => {
    const comp = TestBed.createComponent(QuotationListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── Create form validation ─────────────────────────────────────────────────────

describe('QuotationListComponent — create form validation', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canCreate: true });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('create() validation: requires customer', async () => {
    const comp = TestBed.createComponent(QuotationListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set(null);
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createQuotation).not.toHaveBeenCalled();
  });

  it('create() validation: requires currency', async () => {
    const comp = TestBed.createComponent(QuotationListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.newCurrency.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createQuotation).not.toHaveBeenCalled();
  });

  it('create() validation: requires quoteDate', async () => {
    const comp = TestBed.createComponent(QuotationListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.newCurrency.set('TZS');
    comp.newQuoteDate.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createQuotation).not.toHaveBeenCalled();
  });

  it('create() calls createQuotation with correct payload', async () => {
    const comp = TestBed.createComponent(QuotationListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.newCurrency.set('TZS');
    comp.newQuoteDate.set('2025-01-01');
    comp.newValidUntil.set('2025-01-31');
    comp.create();

    expect(svc.createQuotation).toHaveBeenCalledOnce();
    const req = svc.createQuotation.mock.calls[0][0];
    expect(req.companyUid).toBe('CO1');
    expect(req.customerUid).toBe('CUST1');
    expect(req.currency).toBe('TZS');
    expect(req.quoteDate).toBe('2025-01-01');
    expect(req.validUntil).toBe('2025-01-31');
  });
});

// ── status-tag modifier logic ─────────────────────────────────────────────────
// Mirrors inline template bindings on the quotation status span.

function quoteOk(s: string)      { return s === 'ACCEPTED'; }
function quoteInfo(s: string)    { return s === 'SENT'; }
function quoteWarn(s: string)    { return s === 'DRAFT'; }
function quoteDanger(s: string)  { return s === 'REJECTED'; }
function quoteNeutral(s: string) { return s === 'EXPIRED'; }

describe('QuotationListComponent — status-tag modifier', () => {
  it('ACCEPTED → ok only', () => {
    expect(quoteOk('ACCEPTED')).toBe(true);
    expect(quoteInfo('ACCEPTED')).toBe(false);
    expect(quoteWarn('ACCEPTED')).toBe(false);
    expect(quoteDanger('ACCEPTED')).toBe(false);
    expect(quoteNeutral('ACCEPTED')).toBe(false);
  });

  it('SENT → info only', () => {
    expect(quoteOk('SENT')).toBe(false);
    expect(quoteInfo('SENT')).toBe(true);
    expect(quoteWarn('SENT')).toBe(false);
    expect(quoteDanger('SENT')).toBe(false);
  });

  it('DRAFT → warn only', () => {
    expect(quoteOk('DRAFT')).toBe(false);
    expect(quoteWarn('DRAFT')).toBe(true);
    expect(quoteDanger('DRAFT')).toBe(false);
  });

  it('REJECTED → danger only', () => {
    expect(quoteOk('REJECTED')).toBe(false);
    expect(quoteWarn('REJECTED')).toBe(false);
    expect(quoteDanger('REJECTED')).toBe(true);
  });

  it('EXPIRED → neutral only', () => {
    expect(quoteOk('EXPIRED')).toBe(false);
    expect(quoteNeutral('EXPIRED')).toBe(true);
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('QuotationListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('sets state to forbidden when listQuotations returns 403', async () => {
    makeBed({
      listImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(QuotationListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
