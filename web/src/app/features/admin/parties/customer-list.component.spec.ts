/**
 * CustomerListComponent — live-search debounce + race-safety spec.
 *
 * Uses Vitest fake timers (vi.useFakeTimers) — this project runs under
 * @angular/build:unit-test with Vitest in zoneless mode; Angular's fakeAsync
 * is unavailable because zone-testing.js is not loaded.
 *
 * Covers:
 *  1. Typing triggers a load after 300 ms debounce (not before).
 *  2. Typing the same value twice does not re-fire (distinctUntilChanged).
 *  3. applySearch (button/Enter) fires immediately, no debounce wait.
 *  4. clearSearch resets the query and fires immediately.
 *  5. Race-safety: stale response discarded; only latest query result lands.
 *  6. BR-PARTY-04 guard: BUSINESS without TIN sets formError, does NOT call create.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from './customer.service';
import type { CustomerPage } from './customer.service';
import { CustomerListComponent } from './customer-list.component';

// ── Helpers ────────────────────────────────────────────────────────────────────

const emptyPage = (): CustomerPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

function makeBed(listImpl: () => any) {
  listImpl ??= () => of(emptyPage());
  TestBed.configureTestingModule({
    imports: [CustomerListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: CustomerService,
        useValue: { list: vi.fn(listImpl), create: vi.fn(() => of({})) },
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
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => false),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

// ── Spec ───────────────────────────────────────────────────────────────────────

// ── BR-PARTY-04 guard ─────────────────────────────────────────────────────────

describe('CustomerListComponent — BR-PARTY-04 client-side guard', () => {
  let createSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    createSpy = vi.fn(() => of({}));

    TestBed.configureTestingModule({
      imports: [CustomerListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: CustomerService,
          useValue: { list: vi.fn(() => of(emptyPage())), create: createSpy },
        },
        {
          provide: OrganisationService,
          useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
        },
        {
          provide: CompanyService,
          useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
        },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        {
          provide: SessionStore,
          useValue: {
            hasPermission: vi.fn(() => false),
            isAuthenticated: signal(true),
            user: signal(null),
            permissions: signal([]),
            activeBranchUid: signal(null),
          },
        },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('sets formError and does NOT call create when partyType=BUSINESS and TIN is blank', async () => {
    const fixture = TestBed.createComponent(CustomerListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync(); // let startup settle

    comp.newDisplayName.set('Acme Ltd');
    comp.newPartyType.set('BUSINESS');
    comp.newTin.set(''); // blank TIN — should trigger BR-PARTY-04 guard

    comp.create();

    expect(comp.formError()).toBe('A business party must have a TIN (BR-PARTY-04).');
    expect(createSpy).not.toHaveBeenCalled();
  });

  it('calls create when partyType=BUSINESS and TIN is provided', async () => {
    const fixture = TestBed.createComponent(CustomerListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newDisplayName.set('Acme Ltd');
    comp.newPartyType.set('BUSINESS');
    comp.newTin.set('123-456-789');

    comp.create();

    expect(comp.formError()).toBeNull();
    expect(createSpy).toHaveBeenCalledOnce();
  });

  it('calls create for INDIVIDUAL without TIN (BR-PARTY-05: optional)', async () => {
    const fixture = TestBed.createComponent(CustomerListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newDisplayName.set('John Doe');
    comp.newPartyType.set('INDIVIDUAL');
    comp.newTin.set(''); // blank is fine for individual

    comp.create();

    expect(comp.formError()).toBeNull();
    expect(createSpy).toHaveBeenCalledOnce();
  });

  it('clears VRN when VAT registered is unchecked (BR-PARTY-06)', async () => {
    const fixture = TestBed.createComponent(CustomerListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newVatRegistered.set(true);
    comp.newVrn.set('VRN-999');

    comp.onNewVatRegisteredChange(false);

    expect(comp.newVatRegistered()).toBe(false);
    expect(comp.newVrn()).toBe('');
  });
});

// ── Live search ───────────────────────────────────────────────────────────────

describe('CustomerListComponent — live search', () => {
  let listSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    listSpy = vi.fn(() => of(emptyPage()));

    TestBed.configureTestingModule({
      imports: [CustomerListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: CustomerService, useValue: { list: listSpy, create: vi.fn(() => of({})) } },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        {
          provide: SessionStore,
          useValue: {
            hasPermission: vi.fn(() => false),
            isAuthenticated: signal(true),
            user: signal(null),
            permissions: signal([]),
            activeBranchUid: signal(null),
          },
        },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  // ── 1. Startup load ──────────────────────────────────────────────────────────

  it('fires exactly one load on startup (immediate trigger from company resolve)', async () => {
    TestBed.createComponent(CustomerListComponent);
    // The toObservable initial emission is skipped (skip(1)).
    // Only the immediateTrigger$ from loadCompanies → load(0) should fire.
    await vi.runAllTimersAsync();
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', undefined, 0, 20);
  });

  // ── 2. Debounce: does not fire before 300 ms ─────────────────────────────────

  it('does NOT fire before 300 ms when the user types', async () => {
    const fixture = TestBed.createComponent(CustomerListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync(); // let startup load settle
    listSpy.mockClear();

    comp.searchQ.set('ac');
    await vi.advanceTimersByTimeAsync(200); // still within debounce window
    expect(listSpy).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(100); // debounce fires at 300 ms total
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', 'ac', 0, 20);
  });

  // ── 3. Typed query resets to page 0 ─────────────────────────────────────────

  it('resets to page 0 when a new query is typed', async () => {
    const fixture = TestBed.createComponent(CustomerListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('xyz');
    await vi.advanceTimersByTimeAsync(300);
    expect(listSpy).toHaveBeenCalledWith('10', 'xyz', 0, 20);
  });

  // ── 4. distinctUntilChanged: same value typed again ──────────────────────────

  it('does not re-fire when the same query is typed twice (distinctUntilChanged)', async () => {
    const fixture = TestBed.createComponent(CustomerListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    expect(listSpy).toHaveBeenCalledTimes(1);
    listSpy.mockClear();

    // Set the exact same value — distinctUntilChanged on typingTrigger$ suppresses it.
    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    expect(listSpy).not.toHaveBeenCalled();
  });

  // ── 5. applySearch (button/Enter) is immediate ───────────────────────────────

  it('applySearch fires immediately via immediateTrigger$ without waiting for debounce', async () => {
    const fixture = TestBed.createComponent(CustomerListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('fast');
    // Call applySearch synchronously — goes through immediateTrigger$, no timer needed.
    comp.applySearch();
    await vi.advanceTimersByTimeAsync(0);

    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', 'fast', 0, 20);
  });

  // ── 6. clearSearch ───────────────────────────────────────────────────────────

  it('clearSearch resets the query and fires an immediate load', async () => {
    const fixture = TestBed.createComponent(CustomerListComponent);
    const comp = fixture.componentInstance;
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

  // ── 7. Race-safety via switchMap ─────────────────────────────────────────────

  it('race-safety: only the latest (fresh) response lands; stale response is discarded', async () => {
    /**
     * Uses the immediate path (applySearch / immediateTrigger$) to trigger two
     * overlapping requests without needing debounce timers, isolating the
     * switchMap cancellation behaviour.
     *
     *  call 1 = startup (returns emptyPage immediately)
     *  call 2 = applySearch('slow') → stale$ (held open)
     *  call 3 = applySearch('fast') → fresh$ (switchMap cancels stale$)
     *
     * Pushing into stale$ must be ignored; fresh$ must land.
     */
    const stale$ = new Subject<CustomerPage>();
    const fresh$ = new Subject<CustomerPage>();
    let callCount = 0;

    const raceSpy = vi.fn(() => {
      callCount++;
      if (callCount === 2) return stale$.asObservable();
      if (callCount === 3) return fresh$.asObservable();
      return of(emptyPage());
    });

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [CustomerListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: CustomerService, useValue: { list: raceSpy, create: vi.fn(() => of({})) } },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        {
          provide: SessionStore,
          useValue: {
            hasPermission: vi.fn(() => false),
            isAuthenticated: signal(true),
            user: signal(null),
            permissions: signal([]),
            activeBranchUid: signal(null),
          },
        },
      ],
    });

    const fixture = TestBed.createComponent(CustomerListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync(); // startup load (call 1) settles
    expect(raceSpy).toHaveBeenCalledTimes(1);

    // First search via button path (immediate) → stale$ subscribed.
    comp.searchQ.set('slow');
    comp.applySearch(); // call 2 → stale$ subscribed (switchMap switches to it)
    await vi.advanceTimersByTimeAsync(0);
    expect(raceSpy).toHaveBeenCalledTimes(2);

    // Second search via button path before first resolves →
    // switchMap unsubscribes stale$, subscribes fresh$.
    comp.searchQ.set('fast');
    comp.applySearch(); // call 3 → fresh$ subscribed
    await vi.advanceTimersByTimeAsync(0);
    expect(raceSpy).toHaveBeenCalledTimes(3);

    // Emit from the abandoned stale subject — switchMap has unsubscribed it.
    stale$.next({
      rows: [{ uid: 'STALE' } as any],
      meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
    });
    expect(comp.rows()).toHaveLength(0); // stale must not land

    // Emit from the active fresh subject — must update rows synchronously.
    fresh$.next({
      rows: [{ uid: 'FRESH' } as any],
      meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
    });
    expect(comp.rows()).toHaveLength(1);
    expect(comp.rows()[0].uid).toBe('FRESH');
  });
});
