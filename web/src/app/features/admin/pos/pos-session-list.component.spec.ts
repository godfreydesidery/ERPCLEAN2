/**
 * PosSessionListComponent specs.
 *
 * Covers:
 *  1. Fires exactly one load on startup after company resolves.
 *  2. isEmpty is true when no sessions returned.
 *  3. openSession() validation: requires tillUid.
 *  4. openSession() validation: requires valid opening float.
 *  5. openSession() calls posService.openSession with correct payload.
 *  6. 403 response sets state to 'forbidden'.
 *  7. statusBadgeClass: key status values.
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
import { BranchService } from '../branch/branch.service';
import { PosService } from './pos.service';
import type { PosSessionPage } from './pos.service';
import { PosSessionListComponent } from './pos-session-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const emptyPage = (): PosSessionPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const stubSession = {
  id: '5', uid: 'SESS1', companyId: '10', branchId: '2', posTillId: '1', cashierId: '99',
  status: 'OPEN' as const, openedAt: '2025-01-01T08:00:00', closedAt: null, reconciledAt: null,
  openingFloatAmount: '100.00', countedCashAmount: null, expectedCashAmount: null,
  varianceAmount: null, varianceJournalId: null, notes: null,
};

function makeSessionStore(canOpen = false) {
  return {
    hasPermission: vi.fn(() => canOpen),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { listImpl?: () => any; canOpen?: boolean } = {}) {
  const { listImpl, canOpen = false } = opts;
  const sessionStore = makeSessionStore(canOpen);

  TestBed.configureTestingModule({
    imports: [PosSessionListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PosService,
        useValue: {
          listSessions: vi.fn(listImpl ?? (() => of(emptyPage()))),
          openSession: vi.fn(() => of(stubSession)),
          listTills: vi.fn(() => of([])),
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
        provide: BranchService,
        useValue: { list: vi.fn(() => of([{ uid: 'BR1', id: '2', name: 'HQ', code: 'HQ', companyId: '10', companyUid: 'CO1', isDefault: true, status: 'ACTIVE', timeZone: 'UTC' }])) },
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

describe('PosSessionListComponent — init', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fires exactly one load on startup after company resolves', async () => {
    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    expect(svc.listSessions).toHaveBeenCalledTimes(1);
    expect(svc.listSessions).toHaveBeenCalledWith('10', 0, 20);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no sessions returned', async () => {
    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── Open form validation ───────────────────────────────────────────────────────

describe('PosSessionListComponent — open form validation', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canOpen: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('openSession() requires tillUid', async () => {
    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.newTillUid.set('');
    comp.openSession();

    expect(comp.openError()).toBeTruthy();
    expect(svc.openSession).not.toHaveBeenCalled();
  });

  it('openSession() requires valid opening float', async () => {
    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.newTillUid.set('TILL1');
    comp.newOpeningFloat.set('-1');
    comp.openSession();

    expect(comp.openError()).toBeTruthy();
    expect(svc.openSession).not.toHaveBeenCalled();
  });

  it('openSession() calls posService.openSession with correct payload', async () => {
    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.newTillUid.set('TILL1');
    comp.newOpeningFloat.set('100.00');
    comp.openSession();

    expect(svc.openSession).toHaveBeenCalledOnce();
    const req = svc.openSession.mock.calls[0][0];
    expect(req.tillUid).toBe('TILL1');
    expect(req.openingFloatAmount).toBe('100.00');
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('PosSessionListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets state to forbidden when listSessions returns 403', async () => {
    makeBed({
      listImpl: () => throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});

// ── statusBadgeClass ───────────────────────────────────────────────────────────

describe('PosSessionListComponent — statusBadgeClass', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('OPEN → text-bg-success', async () => {
    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.statusBadgeClass('OPEN')).toBe('text-bg-success');
  });

  it('CLOSED → text-bg-warning', async () => {
    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.statusBadgeClass('CLOSED')).toBe('text-bg-warning');
  });

  it('RECONCILED → text-bg-secondary', async () => {
    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.statusBadgeClass('RECONCILED')).toBe('text-bg-secondary');
  });
});
