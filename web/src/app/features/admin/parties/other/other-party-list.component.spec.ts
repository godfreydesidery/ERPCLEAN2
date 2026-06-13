/**
 * OtherPartyListComponent — list + create + search spec.
 *
 * Uses Vitest fake timers (vi.useFakeTimers) — project runs under
 * @angular/build:unit-test with Vitest in zoneless mode.
 *
 * Covers:
 *  1. Startup fires exactly one load after companies resolve.
 *  2. Typing debounces 300 ms (does not fire before).
 *  3. applySearch fires immediately via immediateTrigger$.
 *  4. clearSearch resets query and fires immediately.
 *  5. BR-PARTY-04 guard: BUSINESS without TIN → formError, no create call.
 *  6. INDIVIDUAL without TIN → calls create (TIN optional).
 *  7. 403 response sets state to 'forbidden'.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { OtherPartyService } from './other-party.service';
import type { OtherPartyPage } from './other-party.service';
import { OtherPartyListComponent } from './other-party-list.component';

// ── Helpers ────────────────────────────────────────────────────────────────────

const emptyPage = (): OtherPartyPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

function makeBed(listImpl?: () => any) {
  const listFn = listImpl ?? (() => of(emptyPage()));
  TestBed.configureTestingModule({
    imports: [OtherPartyListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: OtherPartyService,
        useValue: { list: vi.fn(listFn), create: vi.fn(() => of({})) },
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

// ── BR-PARTY-04 guard ─────────────────────────────────────────────────────────

describe('OtherPartyListComponent — BR-PARTY-04 client-side guard', () => {
  let createSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    createSpy = vi.fn(() => of({}));

    TestBed.configureTestingModule({
      imports: [OtherPartyListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: OtherPartyService,
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
    const fixture = TestBed.createComponent(OtherPartyListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newDisplayName.set('Acme Ltd');
    comp.newPartyType.set('BUSINESS');
    comp.newTin.set('');

    comp.create();

    expect(comp.formError()).toBe('A business party must have a TIN (BR-PARTY-04).');
    expect(createSpy).not.toHaveBeenCalled();
  });

  it('calls create when partyType=BUSINESS and TIN is provided', async () => {
    const fixture = TestBed.createComponent(OtherPartyListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newDisplayName.set('Acme Ltd');
    comp.newPartyType.set('BUSINESS');
    comp.newTin.set('123-456-789');

    comp.create();

    expect(comp.formError()).toBeNull();
    expect(createSpy).toHaveBeenCalledOnce();
  });

  it('calls create for INDIVIDUAL without TIN (TIN optional for individuals)', async () => {
    const fixture = TestBed.createComponent(OtherPartyListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newDisplayName.set('John Doe');
    comp.newPartyType.set('INDIVIDUAL');
    comp.newTin.set('');

    comp.create();

    expect(comp.formError()).toBeNull();
    expect(createSpy).toHaveBeenCalledOnce();
  });

  it('clears VRN when vatRegistered is unchecked', async () => {
    const fixture = TestBed.createComponent(OtherPartyListComponent);
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

describe('OtherPartyListComponent — live search', () => {
  let listSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    listSpy = vi.fn(() => of(emptyPage()));

    TestBed.configureTestingModule({
      imports: [OtherPartyListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: OtherPartyService, useValue: { list: listSpy, create: vi.fn(() => of({})) } },
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

  it('fires exactly one load on startup (immediate trigger from company resolve)', async () => {
    TestBed.createComponent(OtherPartyListComponent);
    await vi.runAllTimersAsync();
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', undefined, 0, 20);
  });

  it('does NOT fire before 300 ms when the user types', async () => {
    const fixture = TestBed.createComponent(OtherPartyListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('ac');
    await vi.advanceTimersByTimeAsync(200);
    expect(listSpy).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(100);
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', 'ac', 0, 20);
  });

  it('applySearch fires immediately via immediateTrigger$ without waiting for debounce', async () => {
    const fixture = TestBed.createComponent(OtherPartyListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('fast');
    comp.applySearch();
    await vi.advanceTimersByTimeAsync(0);

    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', 'fast', 0, 20);
  });

  it('clearSearch resets the query and fires an immediate load', async () => {
    const fixture = TestBed.createComponent(OtherPartyListComponent);
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

  it('sets state to forbidden on 403 response', async () => {
    const forbiddenError = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    listSpy.mockReturnValue(throwError(() => forbiddenError));

    const fixture = TestBed.createComponent(OtherPartyListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // The state is set in the error handler of the switchMap subscribe
    // After companies load, list is called and returns 403
    expect(comp.state()).toBe('forbidden');
  });
});
