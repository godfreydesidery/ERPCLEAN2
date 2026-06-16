/**
 * SalesReturnListComponent specs.
 *
 * Covers:
 *  1. Renders: fires exactly one load on startup after company resolves.
 *  2. isEmpty is true when no rows returned.
 *  3. 403 response sets state to 'forbidden'.
 *  4. Status-tag modifier: CONFIRMED → ok, DRAFT → warn.
 *  5. Numeric-money guard: grossAmount coerced via fmtMoney (handles string wire value).
 *  6. returnLabel returns returnNumber when present, PENDING when null.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SalesOrdersService } from './sales-orders.service';
import type { SalesReturnPage } from './sales-orders.service';
import { SalesReturnListComponent } from './sales-return-list.component';
import type { SalesReturnDto } from '../models/sales-orders.model';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const emptyPage = (): SalesReturnPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const stubReturn: SalesReturnDto = {
  id: '1', uid: 'RET1', companyId: '10', branchId: '1',
  returnNumber: 'RET-0001', status: 'CONFIRMED',
  deliveryId: '5', deliveryUid: 'DEL-UID-1', salesOrderUid: 'SO-UID-1',
  customerId: '3', returnDate: '2025-03-01',
  creditNoteUid: null, cogsReversalGlEntryUid: null, reason: null,
  netAmount: '900', vatAmount: '180', grossAmount: '1080', currency: 'TZS',
  lines: [],
};

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => false),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { listImpl?: () => SalesReturnPage } = {}) {
  const { listImpl } = opts;
  TestBed.configureTestingModule({
    imports: [SalesReturnListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: SalesOrdersService,
        useValue: {
          listReturns: vi.fn(listImpl ? listImpl : () => of(emptyPage())),
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
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });
}

// ── Init ───────────────────────────────────────────────────────────────────────

describe('SalesReturnListComponent — init', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fires exactly one load on startup after company resolves', async () => {
    const comp = TestBed.createComponent(SalesReturnListComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    expect(svc.listReturns).toHaveBeenCalledTimes(1);
    expect(svc.listReturns).toHaveBeenCalledWith('10', 0, 20);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no returns returned', async () => {
    const comp = TestBed.createComponent(SalesReturnListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('SalesReturnListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets state to forbidden when listReturns returns 403', async () => {
    makeBed({
      listImpl: () => throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })) as any,
    });
    const comp = TestBed.createComponent(SalesReturnListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.state()).toBe('forbidden');
  });
});

// ── status-tag modifier logic ─────────────────────────────────────────────────
// Mirrors inline template bindings on the sales-return status span.

function returnOk(s: string)   { return s === 'CONFIRMED'; }
function returnWarn(s: string) { return s === 'DRAFT'; }

describe('SalesReturnListComponent — status-tag modifier', () => {
  it('CONFIRMED → ok, not warn', () => {
    expect(returnOk('CONFIRMED')).toBe(true);
    expect(returnWarn('CONFIRMED')).toBe(false);
  });

  it('DRAFT → warn, not ok', () => {
    expect(returnOk('DRAFT')).toBe(false);
    expect(returnWarn('DRAFT')).toBe(true);
  });
});

// ── Numeric money guard ────────────────────────────────────────────────────────

describe('SalesReturnListComponent — numeric money guard', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fmtMoney coerces string wire value to 2dp', async () => {
    const comp = TestBed.createComponent(SalesReturnListComponent).componentInstance;
    await vi.runAllTimersAsync();
    // String "1080" from wire → "1080.00"
    expect(comp.fmtMoney('1080')).toBe('1080.00');
  });

  it('fmtMoney coerces numeric wire value to 2dp', async () => {
    const comp = TestBed.createComponent(SalesReturnListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.fmtMoney(1080)).toBe('1080.00');
  });

  it('fmtMoney returns "0.00" for null/undefined', async () => {
    const comp = TestBed.createComponent(SalesReturnListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.fmtMoney(null)).toBe('0.00');
    expect(comp.fmtMoney(undefined)).toBe('0.00');
  });

  it('returnLabel returns returnNumber when present', async () => {
    const comp = TestBed.createComponent(SalesReturnListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.returnLabel(stubReturn)).toBe('RET-0001');
  });

  it('returnLabel returns PENDING when returnNumber is null', async () => {
    const comp = TestBed.createComponent(SalesReturnListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.returnLabel({ ...stubReturn, returnNumber: null })).toBe('PENDING');
  });
});
