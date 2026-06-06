/**
 * AgentListComponent — BR-PARTY-10/11 client-side guards spec.
 *
 * Covers:
 *  1. INTERNAL + no user selected → formError set, create NOT called (BR-PARTY-10).
 *  2. INTERNAL + user selected    → create called with appUserId present.
 *  3. EXTERNAL                    → create called with appUserId omitted (BR-PARTY-11).
 *  4. onNewAgentKindChange to EXTERNAL clears newAppUserId.
 *  5. resetCreateForm (via successful create) resets newAppUserId.
 *
 * Uses Vitest fake timers — project runs under @angular/build:unit-test with
 * Vitest in zoneless mode; Angular fakeAsync is unavailable.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { UserService } from '../user/user.service';
import { AgentService } from './agent.service';
import { AgentListComponent } from './agent-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const STUB_USER = { id: '99', uid: 'U1', username: 'alice', displayName: 'Alice Smith', email: null, phone: null, isRoot: false, status: 'ACTIVE', locked: false, lastLoginAt: null };
const STUB_AGENT = { uid: 'AG1', id: '1', displayName: 'Test Agent', agentKind: 'INTERNAL', status: 'ACTIVE' };

const emptyPage = () => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

// ── Test bed factory ──────────────────────────────────────────────────────────

function makeBed(overrides: { createSpy?: ReturnType<typeof vi.fn>; listUsersSpy?: ReturnType<typeof vi.fn> } = {}) {
  const createSpy = overrides.createSpy ?? vi.fn(() => of(STUB_AGENT));
  const listUsersSpy = overrides.listUsersSpy ?? vi.fn(() => of([STUB_USER]));

  TestBed.configureTestingModule({
    imports: [AgentListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: AgentService,
        useValue: { list: vi.fn(() => of(emptyPage())), create: createSpy },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of(STUB_ORG)) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([STUB_COMPANY])) },
      },
      {
        provide: UserService,
        useValue: { list: listUsersSpy },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { createSpy, listUsersSpy };
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('AgentListComponent — BR-PARTY-10/11 client-side guards', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. INTERNAL + no user → formError, no API call ──────────────────────────

  it('sets formError and does NOT call create when agentKind=INTERNAL and no user is selected (BR-PARTY-10)', async () => {
    const { createSpy } = makeBed();
    const fixture = TestBed.createComponent(AgentListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newDisplayName.set('Internal Corp');
    comp.newAgentKind.set('INTERNAL');
    comp.newAppUserId.set(''); // no user selected

    comp.create();

    expect(comp.formError()).toBe('An internal agent must reference an app user (BR-PARTY-10).');
    expect(createSpy).not.toHaveBeenCalled();
  });

  // ── 2. INTERNAL + user → create called with appUserId ────────────────────────

  it('calls create with appUserId when agentKind=INTERNAL and a user is selected', async () => {
    const { createSpy } = makeBed();
    const fixture = TestBed.createComponent(AgentListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newDisplayName.set('Internal Corp');
    comp.newAgentKind.set('INTERNAL');
    comp.newAppUserId.set('99');

    comp.create();

    expect(comp.formError()).toBeNull();
    expect(createSpy).toHaveBeenCalledOnce();
    const req = createSpy.mock.calls[0][0];
    expect(req.agentKind).toBe('INTERNAL');
    expect(req.appUserId).toBe('99');
  });

  // ── 3. EXTERNAL → create called with appUserId omitted (BR-PARTY-11) ─────────

  it('calls create WITHOUT appUserId when agentKind=EXTERNAL (BR-PARTY-11)', async () => {
    const { createSpy } = makeBed();
    const fixture = TestBed.createComponent(AgentListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newDisplayName.set('External Trader');
    comp.newAgentKind.set('EXTERNAL');
    comp.newAppUserId.set(''); // should be empty anyway

    comp.create();

    expect(comp.formError()).toBeNull();
    expect(createSpy).toHaveBeenCalledOnce();
    const req = createSpy.mock.calls[0][0];
    expect(req.agentKind).toBe('EXTERNAL');
    expect(req.appUserId).toBeUndefined();
  });

  // ── 4. Switching to EXTERNAL clears newAppUserId ──────────────────────────────

  it('clears newAppUserId when kind switches to EXTERNAL', async () => {
    makeBed();
    const fixture = TestBed.createComponent(AgentListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newAppUserId.set('99');
    comp.onNewAgentKindChange('EXTERNAL');

    expect(comp.newAppUserId()).toBe('');
    expect(comp.newAgentKind()).toBe('EXTERNAL');
  });

  // ── 5. Users loaded lazily when form opens ────────────────────────────────────

  it('loads users when the create form is opened and kind is INTERNAL', async () => {
    const { listUsersSpy } = makeBed();
    const fixture = TestBed.createComponent(AgentListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(listUsersSpy).not.toHaveBeenCalled(); // not yet — form closed

    comp.toggleCreateForm(); // open form
    await vi.runAllTimersAsync();

    expect(listUsersSpy).toHaveBeenCalledTimes(1);
    expect(comp.users()).toHaveLength(1);
    expect(comp.usersState()).toBe('idle');
  });

  // ── 6. Users not re-fetched when toggled a second time ───────────────────────

  it('does not re-fetch users when the form is toggled a second time', async () => {
    const { listUsersSpy } = makeBed();
    const fixture = TestBed.createComponent(AgentListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.toggleCreateForm(); // open → loads
    await vi.runAllTimersAsync();
    comp.toggleCreateForm(); // close
    comp.toggleCreateForm(); // re-open
    await vi.runAllTimersAsync();

    expect(listUsersSpy).toHaveBeenCalledTimes(1); // still once
  });
});
