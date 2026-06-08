/**
 * RouteListComponent spec.
 * Mirrors customer-list.component.spec.ts / product-list.component.spec.ts exactly.
 * Uses Vitest fake timers (vi.useFakeTimers) — project runs in zoneless Vitest mode.
 *
 * Covers:
 *  1. Startup: fires exactly one load after company resolves.
 *  2. Debounce: does NOT fire before 300 ms; fires after.
 *  3. distinctUntilChanged: same query typed twice does not re-fire.
 *  4. applySearch fires immediately via immediateTrigger$.
 *  5. clearSearch resets query and fires immediately.
 *  6. create() validation: requires name.
 *  7. create() calls routesService.create with correct payload.
 *  8. 403 response sets state to 'forbidden'.
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
import { RoutesService } from './routes.service';
import type { RoutePage } from './routes.service';
import { RouteListComponent } from './route-list.component';

// ── Helpers ────────────────────────────────────────────────────────────────────

const emptyPage = (): RoutePage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const stubRoute = () => ({
  id: '1', uid: 'R1', companyId: '10', code: 'RT001', name: 'Test Route',
  locationIdentifier: null, status: 'ACTIVE' as const,
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
});

function makeSessionStore(canManage = false) {
  return {
    hasPermission: vi.fn(() => canManage),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { listImpl?: () => any; canManage?: boolean } = {}) {
  const { listImpl, canManage = false } = opts;
  TestBed.configureTestingModule({
    imports: [RouteListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: RoutesService,
        useValue: {
          list: vi.fn(listImpl ?? (() => of(emptyPage()))),
          create: vi.fn(() => of(stubRoute())),
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
      { provide: SessionStore, useValue: makeSessionStore(canManage) },
    ],
  });
}

// ── Startup ────────────────────────────────────────────────────────────────────

describe('RouteListComponent — startup load', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fires exactly one load on startup after company resolves', async () => {
    TestBed.createComponent(RouteListComponent);
    const svc = TestBed.inject(RoutesService) as any;
    await vi.runAllTimersAsync();

    expect(svc.list).toHaveBeenCalledTimes(1);
    expect(svc.list).toHaveBeenCalledWith('10', undefined, 0, 20);
  });

  it('isEmpty is true when no routes returned', async () => {
    const comp = TestBed.createComponent(RouteListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── Live search ────────────────────────────────────────────────────────────────

describe('RouteListComponent — live search debounce', () => {
  let listSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    listSpy = vi.fn(() => of(emptyPage()));

    TestBed.configureTestingModule({
      imports: [RouteListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: RoutesService,
          useValue: { list: listSpy, create: vi.fn(() => of(stubRoute())) },
        },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: SessionStore, useValue: makeSessionStore() },
      ],
    });
  });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('does NOT fire before 300 ms when the user types', async () => {
    const comp = TestBed.createComponent(RouteListComponent).componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('ro');
    await vi.advanceTimersByTimeAsync(200);
    expect(listSpy).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(100);
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', 'ro', 0, 20);
  });

  it('does not re-fire when the same query is typed twice (distinctUntilChanged)', async () => {
    const comp = TestBed.createComponent(RouteListComponent).componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    expect(listSpy).toHaveBeenCalledTimes(1);
    listSpy.mockClear();

    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    expect(listSpy).not.toHaveBeenCalled();
  });

  it('applySearch fires immediately via immediateTrigger$', async () => {
    const comp = TestBed.createComponent(RouteListComponent).componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('fast');
    comp.applySearch();
    await vi.advanceTimersByTimeAsync(0);

    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', 'fast', 0, 20);
  });

  it('clearSearch resets query and fires immediate load', async () => {
    const comp = TestBed.createComponent(RouteListComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    listSpy.mockClear();

    comp.clearSearch();
    await vi.advanceTimersByTimeAsync(0);

    expect(comp.searchQ()).toBe('');
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', undefined, 0, 20);
  });
});

// ── Create form ────────────────────────────────────────────────────────────────

describe('RouteListComponent — create form', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canManage: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('create() validation: requires name', async () => {
    const comp = TestBed.createComponent(RouteListComponent).componentInstance;
    const svc = TestBed.inject(RoutesService) as any;
    await vi.runAllTimersAsync();

    comp.newName.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.create).not.toHaveBeenCalled();
  });

  it('create() calls routesService.create with companyUid and name', async () => {
    const comp = TestBed.createComponent(RouteListComponent).componentInstance;
    const svc = TestBed.inject(RoutesService) as any;
    await vi.runAllTimersAsync();

    comp.newName.set('Nairobi South');
    comp.create();

    expect(svc.create).toHaveBeenCalledOnce();
    const req = svc.create.mock.calls[0][0];
    expect(req.companyUid).toBe('CO1');
    expect(req.name).toBe('Nairobi South');
  });

  it('create() with optional locationIdentifier passes it through', async () => {
    const comp = TestBed.createComponent(RouteListComponent).componentInstance;
    const svc = TestBed.inject(RoutesService) as any;
    await vi.runAllTimersAsync();

    comp.newName.set('Mombasa North');
    comp.newLocationIdentifier.set('Zone-A');
    comp.create();

    const req = svc.create.mock.calls[0][0];
    expect(req.locationIdentifier).toBe('Zone-A');
  });

  it('create() without locationIdentifier sends undefined', async () => {
    const comp = TestBed.createComponent(RouteListComponent).componentInstance;
    const svc = TestBed.inject(RoutesService) as any;
    await vi.runAllTimersAsync();

    comp.newName.set('Route X');
    comp.newLocationIdentifier.set('');
    comp.create();

    const req = svc.create.mock.calls[0][0];
    expect(req.locationIdentifier).toBeUndefined();
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('RouteListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets state to forbidden when list returns 403', async () => {
    makeBed({
      listImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(RouteListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
