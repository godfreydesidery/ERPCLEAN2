/**
 * PosSessionDetailComponent specs.
 *
 * 1. Numeric-input string-op crash class: payoutAmount and countedCash are bound to type="number"
 *    inputs. NumberValueAccessor stores a JS number in the signal; recordPayout() and
 *    closeSession() previously called .trim() on the raw number (crash) — they coerce first now.
 * 2. Shift report vs session status: the page used to call x-read unconditionally, which the
 *    server refuses (409) once a session is RECONCILED — so a manager who closed the shift saw an
 *    error and concluded the till figures were gone. A reconciled session must read z-read.
 *
 * The SessionStore stub is a NON-root manager holding only the POS permissions: root bypasses
 * every check server-side and would mask a gap.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import type { PosSessionDto } from './models/pos.model';
import { PosService } from './pos.service';
import { PosSessionDetailComponent } from './pos-session-detail.component';

const stubSession: PosSessionDto = {
  id: '5', uid: 'SESS1', sessionNumber: 'POS-0001', companyId: '10',
  branchId: '2', branchName: 'Kilimanjaro',
  posTillId: '1', tillName: 'Counter 1',
  cashierId: '99', cashierName: 'Asha Mrema',
  status: 'OPEN', openedAt: '2025-01-01T08:00:00', closedAt: null, reconciledAt: null,
  openingFloatAmount: '100.00', countedCashAmount: null, expectedCashAmount: null,
  varianceAmount: null, varianceJournalId: null, notes: null,
};

const reconciledSession: PosSessionDto = {
  ...stubSession,
  status: 'RECONCILED',
  closedAt: '2025-01-01T18:00:00',
  reconciledAt: '2025-01-01T18:05:00',
  countedCashAmount: '740.00',
  expectedCashAmount: '750.00',
  varianceAmount: '-10.00',
};

const stubXRead = {
  sessionUid: 'SESS1',
  posTillId: '1', tillName: 'Counter 1',
  cashierId: '99', cashierName: 'Asha Mrema',
  branchId: '2', branchName: 'Kilimanjaro',
  openedAt: '2025-01-01T08:00:00',
  openingFloatAmount: '100.00', totalSalesAmount: '650.00', totalPayoutsNetAmount: '0.00',
  expectedCashAmount: '750.00', invoiceCount: 3,
};

const stubZRead = {
  ...stubXRead,
  closedAt: '2025-01-01T18:00:00', reconciledAt: '2025-01-01T18:05:00',
  totalSalesAmount: '900.00', countedCashAmount: '740.00', varianceAmount: '-10.00',
  varianceJournalId: null, invoiceCount: 4, notes: null,
};

/** A non-root manager: permissions come from the granted list, never from an isRoot bypass. */
function makeSessionStore(granted: string[] = ['POS.SESSION.VIEW', 'POS.SESSION.CLOSE', 'POS.SESSION.RECONCILE']) {
  return {
    hasPermission: vi.fn((code: string) => granted.includes(code)),
    isAuthenticated: signal(true),
    user: signal({ isRoot: false }),
    permissions: signal(granted),
    activeBranchUid: signal(null),
  };
}

function makeBed(session: PosSessionDto = stubSession, svcOverrides: Record<string, unknown> = {}) {
  const svc = {
    getSessionByUid: vi.fn(() => of(session)),
    xRead: vi.fn(() => of(stubXRead)),
    zRead: vi.fn(() => of(stubZRead)),
    recordPayout: vi.fn(() => of(undefined)),
    closeSession: vi.fn(() => of({ ...session, status: 'CLOSED' })),
    reconcileSession: vi.fn(() => of(stubZRead)),
    ...svcOverrides,
  };

  TestBed.configureTestingModule({
    imports: [PosSessionDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: PosService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });
  return svc;
}

// ── recordPayout numeric coercion ─────────────────────────────────────────────

describe('PosSessionDetailComponent — recordPayout numeric amount', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('recordPayout() does not throw when payoutAmount holds a number (type="number")', async () => {
    const fixture = TestBed.createComponent(PosSessionDetailComponent);
    fixture.componentRef.setInput('uid', 'SESS1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.payoutReason.set('Cash float top-up');
    // Simulate NumberValueAccessor storing a JS number.
    comp.payoutAmount.set(150 as unknown as string);

    expect(() => comp.recordPayout()).not.toThrow();
    const svc = TestBed.inject(PosService) as any;
    expect(svc.recordPayout).toHaveBeenCalledOnce();
    expect(svc.recordPayout.mock.calls[0][1].amount).toBe('150');
  });
});

// ── closeSession numeric coercion ─────────────────────────────────────────────

describe('PosSessionDetailComponent — closeSession numeric countedCash', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('closeSession() does not throw when countedCash holds a number (type="number")', async () => {
    const fixture = TestBed.createComponent(PosSessionDetailComponent);
    fixture.componentRef.setInput('uid', 'SESS1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // Simulate NumberValueAccessor storing a JS number.
    comp.countedCash.set(200 as unknown as string);

    expect(() => comp.closeSession()).not.toThrow();
    const svc = TestBed.inject(PosService) as any;
    expect(svc.closeSession).toHaveBeenCalledOnce();
    expect(svc.closeSession.mock.calls[0][1].countedCashAmount).toBe('200');
  });
});

// ── shift report follows the session status ───────────────────────────────────

describe('PosSessionDetailComponent — shift report matches the session status', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  async function renderFor(session: PosSessionDto, svcOverrides: Record<string, unknown> = {}) {
    const svc = makeBed(session, svcOverrides);
    const fixture = TestBed.createComponent(PosSessionDetailComponent);
    fixture.componentRef.setInput('uid', 'SESS1');
    await vi.runAllTimersAsync();
    await fixture.whenStable();
    fixture.detectChanges();
    return { svc, fixture, text: fixture.nativeElement.textContent as string };
  }

  it('a RECONCILED session reads z-read and never fires the x-read the server refuses', async () => {
    const { svc, text } = await renderFor(reconciledSession);

    expect(svc.zRead).toHaveBeenCalledWith('SESS1');
    expect(svc.xRead).not.toHaveBeenCalled();
    // The figures a manager came for survive the shift being closed and the till reset.
    expect(text).toContain('Z-Read');
    expect(text).not.toContain('X-Read');
    expect(text).toContain('900.00');
  });

  it('an OPEN session reads x-read and labels it as a mid-shift snapshot', async () => {
    const { svc, text } = await renderFor(stubSession);

    expect(svc.xRead).toHaveBeenCalledWith('SESS1');
    expect(svc.zRead).not.toHaveBeenCalled();
    expect(text).toContain('X-Read');
    expect(text).toContain('Mid-Shift Snapshot');
    expect(text).not.toContain('Z-Read');
  });

  it('a CLOSED session still reads x-read (valid until the session is reconciled)', async () => {
    const { svc } = await renderFor({ ...stubSession, status: 'CLOSED', closedAt: '2025-01-01T18:00:00' });

    expect(svc.xRead).toHaveBeenCalledWith('SESS1');
    expect(svc.zRead).not.toHaveBeenCalled();
  });

  it('renders a friendly, detail-free message when the report cannot be loaded', async () => {
    const { text } = await renderFor(reconciledSession, {
      zRead: vi.fn(() =>
        throwError(() => new HttpErrorResponse({
          status: 403,
          statusText: 'Forbidden',
          error: { errors: ['Access denied: POS.SESSION.VIEW'] },
        })),
      ),
    });

    expect(text).toContain("We couldn't load this shift's figures");
    // Error-hygiene rule: no status code, no permission code, no exception text on screen.
    expect(text).not.toContain('403');
    expect(text).not.toContain('POS.SESSION.VIEW');
    expect(text).not.toContain('Forbidden');
  });
});

// ── UAT 2026-08: the shift has to be readable at a glance ─────────────────────
// The defect: the page carried cashierId "99", posTillId "1" and branchId "2" and no labels,
// so "whose shift, on which till, at which branch?" needed three cross-reference lookups.

describe('PosSessionDetailComponent — names the cashier, till and branch', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  async function render(session: PosSessionDto) {
    makeBed(session);
    const fixture = TestBed.createComponent(PosSessionDetailComponent);
    fixture.componentRef.setInput('uid', 'SESS1');
    await vi.runAllTimersAsync();
    await fixture.whenStable();
    fixture.detectChanges();
    const cards = fixture.nativeElement.querySelectorAll('.erp-card') as NodeListOf<HTMLElement>;
    return {
      text: fixture.nativeElement.textContent as string,
      // The Session Overview card — asserted on its own so the shift-report panel below it
      // (which is fed by its own X/Z-read stub) cannot satisfy an assertion by accident.
      overview: cards[0].textContent ?? '',
    };
  }

  it('shows the names on the session overview, not just the ids', async () => {
    const { overview } = await render(stubSession);

    expect(overview).toContain('Asha Mrema');
    expect(overview).toContain('Counter 1');
    expect(overview).toContain('Kilimanjaro');
  });

  it('names the shift on the Z-read, which is the document it is signed off on', async () => {
    const { text } = await render(reconciledSession);

    expect(text).toContain('Z-Read');
    expect(text).toContain('Asha Mrema');
    expect(text).toContain('Counter 1');
    expect(text).toContain('Kilimanjaro');
  });

  it('renders a dash, never a raw id, when a name could not be resolved', async () => {
    const { overview } = await render({
      ...stubSession,
      cashierName: null,
      tillName: null,
      branchName: null,
    });

    expect(overview).toContain('Cashier—');
    expect(overview).toContain('Till—');
    expect(overview).toContain('Branch—');
    // Never fall back to printing the internal id as a label.
    expect(overview).not.toContain('99');
    expect(overview).not.toContain('Asha Mrema');
  });
});
