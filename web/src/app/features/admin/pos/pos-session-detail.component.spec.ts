/**
 * PosSessionDetailComponent — regression specs for numeric-input string-op crash class.
 *
 * payoutAmount and countedCash are bound to type="number" inputs.
 * NumberValueAccessor stores a JS number in the signal; recordPayout() and closeSession()
 * previously called .trim() on the raw number (crash). After the fix they coerce first.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { PosService } from './pos.service';
import { PosSessionDetailComponent } from './pos-session-detail.component';

const stubSession = {
  id: '5', uid: 'SESS1', companyId: '10', branchId: '2', posTillId: '1', cashierId: '99',
  status: 'OPEN' as const, openedAt: '2025-01-01T08:00:00', closedAt: null, reconciledAt: null,
  openingFloatAmount: '100.00', countedCashAmount: null, expectedCashAmount: null,
  varianceAmount: null, varianceJournalId: null, notes: null,
};

const stubXRead = { sessionUid: 'SESS1', salesCount: 0, salesTotal: '0.00', payoutsTotal: '0.00', expectedCash: '100.00' };

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed() {
  TestBed.configureTestingModule({
    imports: [PosSessionDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PosService,
        useValue: {
          getSessionByUid: vi.fn(() => of(stubSession)),
          xRead: vi.fn(() => of(stubXRead)),
          recordPayout: vi.fn(() => of(undefined)),
          closeSession: vi.fn(() => of({ ...stubSession, status: 'CLOSED' })),
          reconcileSession: vi.fn(() => of({ sessionUid: 'SESS1' })),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });
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
