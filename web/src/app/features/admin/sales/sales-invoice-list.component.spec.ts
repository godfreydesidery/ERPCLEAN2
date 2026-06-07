/**
 * SalesInvoiceListComponent spec.
 * Mirrors product-list.component.spec.ts exactly (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Startup: loads companies then invoices on init.
 *  2. Debounce: does NOT fire before 300 ms; fires after.
 *  3. distinctUntilChanged: same query twice does not re-fire.
 *  4. applySearch fires immediately via immediateTrigger$.
 *  5. clearSearch resets query and fires immediately.
 *  6. create() validation: requires customer.
 *  7. create() calls salesService.create with correct payload.
 *  8. 403 response sets state to 'forbidden'.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from '../parties/customer.service';
import { AgentService } from '../parties/agent.service';
import { SalesService } from './sales.service';
import type { SalesInvoicePage } from './sales.service';
import { SalesInvoiceListComponent } from './sales-invoice-list.component';

// ── Helpers ────────────────────────────────────────────────────────────────────

const emptyPage = (): SalesInvoicePage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
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
} = {}) {
  const { listImpl, canCreate = false } = opts;
  const sessionStore = makeSessionStore(canCreate);

  TestBed.configureTestingModule({
    imports: [SalesInvoiceListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: SalesService,
        useValue: {
          list: vi.fn(listImpl ?? (() => of(emptyPage()))),
          create: vi.fn(() => of({ uid: 'INV1', invoiceNumber: null, status: 'DRAFT' })),
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

describe('SalesInvoiceListComponent — init', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('fires exactly one load on startup after company resolves', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    expect(svc.list).toHaveBeenCalledTimes(1);
    expect(svc.list).toHaveBeenCalledWith('10', undefined, undefined, 0, 20);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no invoices returned', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── Debounce ───────────────────────────────────────────────────────────────────

describe('SalesInvoiceListComponent — live search debounce', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ listImpl: () => of(emptyPage()) });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('does NOT fire before 300 ms when the user types', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();
    svc.list.mockClear();

    comp.searchQ.set('inv');
    await vi.advanceTimersByTimeAsync(200);
    expect(svc.list).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(100);
    expect(svc.list).toHaveBeenCalledTimes(1);
  });

  it('does not re-fire when the same query is typed twice (distinctUntilChanged)', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();
    svc.list.mockClear();

    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    expect(svc.list).toHaveBeenCalledTimes(1);
    svc.list.mockClear();

    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    expect(svc.list).not.toHaveBeenCalled();
  });

  it('applySearch fires immediately via immediateTrigger$', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();
    svc.list.mockClear();

    comp.searchQ.set('fast');
    comp.applySearch();
    await vi.advanceTimersByTimeAsync(0);

    expect(svc.list).toHaveBeenCalledTimes(1);
  });

  it('clearSearch resets query and fires immediate load', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    svc.list.mockClear();

    comp.clearSearch();
    await vi.advanceTimersByTimeAsync(0);

    expect(comp.searchQ()).toBe('');
    expect(svc.list).toHaveBeenCalledTimes(1);
    expect(svc.list).toHaveBeenCalledWith('10', undefined, undefined, 0, 20);
  });
});

// ── Create form ────────────────────────────────────────────────────────────────

describe('SalesInvoiceListComponent — create form', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canCreate: true });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('create() validation: requires customer', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set(null);
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.create).not.toHaveBeenCalled();
  });

  it('create() validation: requires currency', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.newCurrency.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.create).not.toHaveBeenCalled();
  });

  it('create() calls salesService.create with companyUid, customerUid, currency', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.newCurrency.set('TZS');
    comp.create();

    expect(svc.create).toHaveBeenCalledOnce();
    const req = svc.create.mock.calls[0][0];
    expect(req.companyUid).toBe('CO1');
    expect(req.customerUid).toBe('CUST1');
    expect(req.currency).toBe('TZS');
  });

  it('create() with optional agent passes agentUid', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.selectedAgent.set({ uid: 'AGT1', label: 'Agent B' });
    comp.newCurrency.set('TZS');
    comp.create();

    const req = svc.create.mock.calls[0][0];
    expect(req.agentUid).toBe('AGT1');
  });

  it('create() without agent sends no agentUid', async () => {
    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST1', label: 'Customer A' });
    comp.selectedAgent.set(null);
    comp.newCurrency.set('TZS');
    comp.create();

    const req = svc.create.mock.calls[0][0];
    expect(req.agentUid).toBeUndefined();
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('SalesInvoiceListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('sets state to forbidden when list returns 403', async () => {
    makeBed({
      listImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(SalesInvoiceListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
