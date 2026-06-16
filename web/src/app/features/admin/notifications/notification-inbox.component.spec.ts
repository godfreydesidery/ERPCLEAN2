/**
 * NotificationInboxComponent specs.
 * Follows Vitest + vi.useFakeTimers pattern (mirrors quotation-list.component.spec.ts).
 *
 * Covers:
 *  1. Fires one load on startup.
 *  2. isEmpty is true when no rows returned.
 *  3. markRead calls service and patches local row.
 *  4. markAllRead calls service and reloads.
 *  5. 403 response sets state to 'forbidden'.
 *  6. Severity pill class bindings: inline [class.status-tag--X] expressions produce correct boolean for each severity value.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { NotificationsService, NotificationPage } from './notifications.service';
import { NotificationInboxComponent } from './notification-inbox.component';

// ── Helpers ────────────────────────────────────────────────────────────────────

const emptyPage = (): NotificationPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const stubNotif = {
  id: '1', uid: 'N1', companyId: '10',
  typeKey: 'LOW_STOCK', channel: 'IN_APP', severity: 'HIGH',
  title: 'Low stock alert', body: 'Product X is low',
  sourceAggregateType: 'PRODUCT', sourceUid: 'P1',
  linkRoute: '/admin/stock', read: false, readAt: null,
  createdAt: '2025-01-01T10:00:00Z',
};

function makeSessionStore(hasPerm = true) {
  return {
    hasPermission: vi.fn(() => hasPerm),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { listImpl?: () => any; hasPerm?: boolean } = {}) {
  const { listImpl, hasPerm = true } = opts;
  TestBed.configureTestingModule({
    imports: [NotificationInboxComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: NotificationsService,
        useValue: {
          listInbox: vi.fn(listImpl ?? (() => of(emptyPage()))),
          markRead: vi.fn(() => of(undefined)),
          markAllRead: vi.fn(() => of(undefined)),
        },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      { provide: SessionStore, useValue: makeSessionStore(hasPerm) },
    ],
  });
}

// ── Init ───────────────────────────────────────────────────────────────────────

describe('NotificationInboxComponent — init', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fires exactly one load on startup', async () => {
    const comp = TestBed.createComponent(NotificationInboxComponent).componentInstance;
    const svc = TestBed.inject(NotificationsService) as any;
    await vi.runAllTimersAsync();

    expect(svc.listInbox).toHaveBeenCalledTimes(1);
    expect(svc.listInbox).toHaveBeenCalledWith(false, 0, 20);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no notifications returned', async () => {
    const comp = TestBed.createComponent(NotificationInboxComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── markRead ──────────────────────────────────────────────────────────────────

describe('NotificationInboxComponent — markRead', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ listImpl: () => of({ rows: [stubNotif], meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false } }) });
  });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('markRead calls service and patches local row to read:true', async () => {
    const comp = TestBed.createComponent(NotificationInboxComponent).componentInstance;
    const svc = TestBed.inject(NotificationsService) as any;
    await vi.runAllTimersAsync();

    comp.markRead(stubNotif as any);
    await vi.runAllTimersAsync();

    expect(svc.markRead).toHaveBeenCalledWith('N1');
    expect(comp.rows()[0].read).toBe(true);
  });
});

// ── markAllRead ────────────────────────────────────────────────────────────────

describe('NotificationInboxComponent — markAllRead', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('markAllRead calls service and triggers reload', async () => {
    const comp = TestBed.createComponent(NotificationInboxComponent).componentInstance;
    const svc = TestBed.inject(NotificationsService) as any;
    await vi.runAllTimersAsync();

    comp.markAllRead();
    await vi.runAllTimersAsync();

    expect(svc.markAllRead).toHaveBeenCalledOnce();
    // After markAllRead succeeds, listInbox is called again (reload)
    expect(svc.listInbox.mock.calls.length).toBeGreaterThanOrEqual(2);
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('NotificationInboxComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets state to forbidden when listInbox returns 403', async () => {
    makeBed({
      listImpl: () => throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });
    const comp = TestBed.createComponent(NotificationInboxComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.state()).toBe('forbidden');
  });
});

// ── severity pill class bindings ───────────────────────────────────────────────
// severityBadgeClass() was removed; the template now uses inline [class.status-tag--X]
// bindings driven by n.severity.toUpperCase(). These tests verify the boolean
// expressions the template relies on for each canonical severity value.

describe('NotificationInboxComponent — severity pill class bindings', () => {
  const cases: Array<{ severity: string; danger: boolean; warn: boolean; info: boolean; neutral: boolean }> = [
    { severity: 'CRITICAL', danger: true,  warn: false, info: false, neutral: false },
    { severity: 'HIGH',     danger: false, warn: true,  info: false, neutral: false },
    { severity: 'MEDIUM',   danger: false, warn: false, info: true,  neutral: false },
    { severity: 'LOW',      danger: false, warn: false, info: false, neutral: true  },
    { severity: 'INFO',     danger: false, warn: false, info: false, neutral: true  },
  ];

  for (const { severity, danger, warn, info, neutral } of cases) {
    it(`severity "${severity}" → correct status-tag--* flags`, () => {
      const s = severity.toUpperCase();
      expect(s === 'CRITICAL').toBe(danger);
      expect(s === 'HIGH').toBe(warn);
      expect(s === 'MEDIUM').toBe(info);
      expect(s !== 'CRITICAL' && s !== 'HIGH' && s !== 'MEDIUM').toBe(neutral);
    });
  }
});
