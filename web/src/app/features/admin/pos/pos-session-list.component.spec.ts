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
 *  7. status pill rendering: statuses map to the correct status-tag modifier in the DOM.
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

// ── branch selector ─────────────────────────────────────────────────────────────

describe('PosSessionListComponent — branch selector', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canOpen: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('defaults to the active/first branch and loads its tills', async () => {
    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    expect(comp.selectedBranchId()).toBe('2');
    expect(svc.listTills).toHaveBeenCalledWith('10', '2');
  });

  it('onBranchChange reloads tills for the chosen branch and clears the till selection', async () => {
    const comp = TestBed.createComponent(PosSessionListComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();
    svc.listTills.mockClear();

    comp.newTillUid.set('TILL1');
    comp.onBranchChange('7');

    expect(comp.selectedBranchId()).toBe('7');
    expect(comp.newTillUid()).toBe('');
    expect(svc.listTills).toHaveBeenCalledWith('10', '7');
  });
});

// ── status pill rendering ──────────────────────────────────────────────────────
// The legacy statusBadgeClass() helper was removed in the UI revamp; status colour
// now lives in the template as inline status-tag bindings. Assert the rendered DOM.

describe('PosSessionListComponent — status pill rendering', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('maps each session status to the correct status-tag modifier', async () => {
    const row = (uid: string, status: 'OPEN' | 'CLOSED' | 'RECONCILED') => ({
      ...stubSession, id: uid, uid, status,
    });
    makeBed({
      listImpl: () => of({
        rows: [row('S1', 'OPEN'), row('S2', 'CLOSED'), row('S3', 'RECONCILED')],
        meta: { page: 0, size: 20, totalElements: 3, totalPages: 1, hasNext: false },
      }),
    });
    const fixture = TestBed.createComponent(PosSessionListComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const tags = Array.from(
      fixture.nativeElement.querySelectorAll('.status-tag'),
    ) as HTMLElement[];
    const byText = (label: string) => tags.find((el) => el.textContent?.includes(label));

    // OPEN and RECONCILED are "ok" (active / settled); CLOSED is neutral.
    expect(byText('OPEN')?.classList.contains('status-tag--ok')).toBe(true);
    expect(byText('CLOSED')?.classList.contains('status-tag--neutral')).toBe(true);
    expect(byText('RECONCILED')?.classList.contains('status-tag--ok')).toBe(true);
  });
});
