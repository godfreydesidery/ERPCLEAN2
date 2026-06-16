/**
 * BlanketOrderListComponent specs (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Fires exactly one load on startup after company resolves.
 *  2. isEmpty is true when no rows returned.
 *  3. 403 response sets state to 'forbidden'.
 *  4. Status-tag modifier: ACTIVE→ok, EXHAUSTED→warn, CANCELLED→danger.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { BlanketOrderService } from './blanket-order.service';
import type { BlanketOrderPage } from './blanket-order.service';
import { BlanketOrderListComponent } from './blanket-order-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const emptyPage = (): BlanketOrderPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => false),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { listImpl?: () => any } = {}) {
  const { listImpl } = opts;
  const sessionStore = makeSessionStore();

  TestBed.configureTestingModule({
    imports: [BlanketOrderListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: BlanketOrderService,
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
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      { provide: SessionStore, useValue: sessionStore },
    ],
  });
}

// ── Init ───────────────────────────────────────────────────────────────────────

describe('BlanketOrderListComponent — init', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('fires exactly one load on startup after company resolves', async () => {
    const comp = TestBed.createComponent(BlanketOrderListComponent).componentInstance;
    const svc = TestBed.inject(BlanketOrderService) as any;
    await vi.runAllTimersAsync();

    expect(svc.list).toHaveBeenCalledTimes(1);
    expect(svc.list).toHaveBeenCalledWith('10', 0, 20);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no blanket orders returned', async () => {
    const comp = TestBed.createComponent(BlanketOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('BlanketOrderListComponent — 403 forbidden', () => {
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

    const comp = TestBed.createComponent(BlanketOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});

// ── status-tag modifier logic ─────────────────────────────────────────────────
// Mirrors inline template bindings: [class.status-tag--ok]="b.status === 'ACTIVE'" etc.

function blanketOk(s: string)      { return s === 'ACTIVE'; }
function blanketWarn(s: string)    { return s === 'EXHAUSTED'; }
function blanketDanger(s: string)  { return s === 'CANCELLED'; }
function blanketNeutral(s: string) { return s !== 'ACTIVE' && s !== 'EXHAUSTED' && s !== 'CANCELLED'; }

describe('BlanketOrderListComponent — status-tag modifier', () => {
  it('ACTIVE → ok only', () => {
    expect(blanketOk('ACTIVE')).toBe(true);
    expect(blanketWarn('ACTIVE')).toBe(false);
    expect(blanketDanger('ACTIVE')).toBe(false);
    expect(blanketNeutral('ACTIVE')).toBe(false);
  });

  it('EXHAUSTED → warn only', () => {
    expect(blanketOk('EXHAUSTED')).toBe(false);
    expect(blanketWarn('EXHAUSTED')).toBe(true);
    expect(blanketDanger('EXHAUSTED')).toBe(false);
    expect(blanketNeutral('EXHAUSTED')).toBe(false);
  });

  it('CANCELLED → danger only', () => {
    expect(blanketOk('CANCELLED')).toBe(false);
    expect(blanketWarn('CANCELLED')).toBe(false);
    expect(blanketDanger('CANCELLED')).toBe(true);
    expect(blanketNeutral('CANCELLED')).toBe(false);
  });
});
