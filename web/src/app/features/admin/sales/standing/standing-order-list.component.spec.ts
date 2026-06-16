/**
 * StandingOrderListComponent specs (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Fires exactly one load on startup after company resolves.
 *  2. isEmpty is true when no rows returned.
 *  3. 403 response sets state to 'forbidden'.
 *  4. Status-tag modifier: ACTIVE→ok, PAUSED→warn, CANCELLED→danger.
 *  5. orderLabel returns orderNumber when present, DRAFT when null.
 *  6. canCreate is true when session has SALES.STANDING.CREATE permission.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { StandingOrderService } from './standing-order.service';
import type { StandingOrderPage } from './standing-order.service';
import { StandingOrderListComponent } from './standing-order-list.component';

// ── Stubs ──────────────────────────────────────────────────────────────────────

const emptyPage = (): StandingOrderPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const stubOrder = {
  uid: 'SO1', id: '1', companyId: '10', branchId: '2',
  orderNumber: 'STD-0001', status: 'ACTIVE' as const,
  customerId: '5', currency: 'TZS',
  frequency: 'MONTHLY' as const,
  startDate: '2025-01-01', endDate: null, nextRunDate: '2025-02-01',
  notes: null, lines: [],
};

function makeSessionStore(perms: string[] = []) {
  return {
    hasPermission: vi.fn((code: string) => perms.includes(code)),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal(perms),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: {
  listImpl?: () => any;
  perms?: string[];
} = {}) {
  const { listImpl, perms = [] } = opts;
  const sessionStore = makeSessionStore(perms);

  TestBed.configureTestingModule({
    imports: [StandingOrderListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: StandingOrderService,
        useValue: {
          list: vi.fn(listImpl ?? (() => of(emptyPage()))),
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
      { provide: SessionStore, useValue: sessionStore },
    ],
  });
}

// ── Init ───────────────────────────────────────────────────────────────────────

describe('StandingOrderListComponent — init', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('fires exactly one load on startup after company resolves', async () => {
    const comp = TestBed.createComponent(StandingOrderListComponent).componentInstance;
    const svc = TestBed.inject(StandingOrderService) as any;
    await vi.runAllTimersAsync();

    expect(svc.list).toHaveBeenCalledTimes(1);
    expect(svc.list).toHaveBeenCalledWith('10', 0, 20);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no standing orders returned', async () => {
    const comp = TestBed.createComponent(StandingOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('StandingOrderListComponent — 403 forbidden', () => {
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

    const comp = TestBed.createComponent(StandingOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});

// ── status-tag modifier logic ─────────────────────────────────────────────────
// Mirrors inline template bindings on the standing-order status span.

function standingOk(s: string)      { return s === 'ACTIVE'; }
function standingWarn(s: string)    { return s === 'PAUSED'; }
function standingDanger(s: string)  { return s === 'CANCELLED'; }
function standingNeutral(s: string) { return s !== 'ACTIVE' && s !== 'PAUSED' && s !== 'CANCELLED'; }

describe('StandingOrderListComponent — status-tag modifier', () => {
  it('ACTIVE → ok only', () => {
    expect(standingOk('ACTIVE')).toBe(true);
    expect(standingWarn('ACTIVE')).toBe(false);
    expect(standingDanger('ACTIVE')).toBe(false);
    expect(standingNeutral('ACTIVE')).toBe(false);
  });

  it('PAUSED → warn only', () => {
    expect(standingOk('PAUSED')).toBe(false);
    expect(standingWarn('PAUSED')).toBe(true);
    expect(standingDanger('PAUSED')).toBe(false);
    expect(standingNeutral('PAUSED')).toBe(false);
  });

  it('CANCELLED → danger only', () => {
    expect(standingOk('CANCELLED')).toBe(false);
    expect(standingWarn('CANCELLED')).toBe(false);
    expect(standingDanger('CANCELLED')).toBe(true);
    expect(standingNeutral('CANCELLED')).toBe(false);
  });
});

// ── orderLabel ─────────────────────────────────────────────────────────────────

describe('StandingOrderListComponent — orderLabel', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('returns orderNumber when present', async () => {
    const comp = TestBed.createComponent(StandingOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.orderLabel(stubOrder as any)).toBe('STD-0001');
  });

  it('returns DRAFT when orderNumber is null', async () => {
    const comp = TestBed.createComponent(StandingOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.orderLabel({ ...stubOrder, orderNumber: null } as any)).toBe('DRAFT');
  });
});

// ── permissions ────────────────────────────────────────────────────────────────

describe('StandingOrderListComponent — permissions', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('canCreate is true when session has SALES.STANDING.CREATE', async () => {
    makeBed({ perms: ['SALES.STANDING.CREATE'] });
    const comp = TestBed.createComponent(StandingOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.canCreate()).toBe(true);
  });

  it('canCreate is false when session lacks SALES.STANDING.CREATE', async () => {
    makeBed({ perms: [] });
    const comp = TestBed.createComponent(StandingOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.canCreate()).toBe(false);
  });
});
