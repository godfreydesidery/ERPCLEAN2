/**
 * StockCountCreateComponent — key behaviour specs.
 *
 * Covers:
 *  1. Defaults company to the user's active company (not list[0]).
 *  2. Defaults branch to the user's active branch (not list[0]).
 *  3. Falls back to list[0] when active company/branch is not in the loaded list.
 *  4. Falls back to list[0] when session has no active context (null).
 *  5. Location options loaded for the defaulted branch.
 *  6. Changing company reloads branches and clears location selection.
 *  7. Changing branch reloads locations.
 *  8. Submit without location selected → formError set, service not called.
 *  9. Successful submit navigates to the detail page.
 * 10. Submit failure surfaces error message.
 */
import { Component, signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Route } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { SessionStore } from '../../../../core/auth/session.store';
import { AlertService } from '../../../../core/feedback/alert.service';
import { CompanyService } from '../../company/company.service';
import { BranchService } from '../../branch/branch.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { StockLocationService } from '../locations/stock-location.service';
import { StockCountService } from './stock-count.service';
import { StockCountCreateComponent } from './stock-count-create.component';
import { AuthUser } from '../../../../core/auth/auth.model';

// ── Stub route component ──────────────────────────────────────────────────────

@Component({ template: '', standalone: true })
class StubRouteComponent {}

const STUB_ROUTES: Route[] = [{ path: '**', component: StubRouteComponent }];

// ── Test fixtures ─────────────────────────────────────────────────────────────

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Test Org' };

/** Company that is NOT the user's active company (would be picked by list[0]). */
const STUB_CO_ALPHA: { uid: string; id: string; name: string } = {
  uid: 'CO-ALPHA', id: '10', name: 'Alpha Corp',
};

/** Company that IS the user's active company. */
const STUB_CO_DAR: { uid: string; id: string; name: string } = {
  uid: 'CO-DAR', id: '20', name: 'Dar es Salaam Co',
};

/** Branch belonging to Alpha Corp (list[0]) — wrong default. */
const STUB_BRANCH_ALPHA_HQ = {
  uid: 'BR-ALPHA-HQ', id: '100', companyId: '10', companyUid: 'CO-ALPHA',
  code: 'HQ', name: 'Alpha HQ', timeZone: 'Africa/Dar_es_Salaam',
  isDefault: true, status: 'ACTIVE',
};

/** Branch belonging to Dar Co — correct default (matches session.activeBranchUid). */
const STUB_BRANCH_DAR_HQ = {
  uid: 'BR-DAR-HQ', id: '200', companyId: '20', companyUid: 'CO-DAR',
  code: 'DAR', name: 'Dar es Salaam HQ', timeZone: 'Africa/Dar_es_Salaam',
  isDefault: true, status: 'ACTIVE',
};

const STUB_LOCATION = {
  uid: 'LOC1', id: '5', code: 'WH-A', name: 'Warehouse A',
  branchId: '200', status: 'ACTIVE',
};

const STUB_CREATED_COUNT = {
  uid: 'CNT-UID-1', id: '1', companyId: '20', branchId: '200',
  countNumber: 'SC-0001', status: 'COUNTING', countType: 'FULL',
  locationId: '5', countDate: '2025-06-01',
  frozenAt: null, postedAt: null, cancelledAt: null,
  varianceGlEntryUid: null, notes: null, lines: [],
};

// ── Helper: build user AuthUser ───────────────────────────────────────────────

function makeUser(activeCompanyUid: string | null, activeBranchUid: string | null): AuthUser {
  return {
    uid: 'USER1', username: 'testuser', displayName: 'Test User',
    isRoot: false, activeCompanyUid, activeBranchUid, hasBranch: activeBranchUid !== null,
  };
}

// ── makeBed factory ───────────────────────────────────────────────────────────

interface BedOptions {
  /** The session user (controls which company/branch is "active"). */
  sessionUser?: AuthUser | null;
  /** activeBranchUid returned by SessionStore (separate signal from user.activeBranchUid). */
  activeBranchUid?: string | null;
  /** Companies returned by CompanyService.list. */
  companies?: { uid: string; id: string; name: string }[];
  /** Branches returned by BranchService.list. */
  branches?: typeof STUB_BRANCH_ALPHA_HQ[];
  createSpy?: ReturnType<typeof vi.fn>;
}

function makeBed(opts: BedOptions = {}) {
  const {
    sessionUser = makeUser('CO-DAR', 'BR-DAR-HQ'),
    activeBranchUid = 'BR-DAR-HQ',
    companies = [STUB_CO_ALPHA, STUB_CO_DAR],
    branches = [STUB_BRANCH_ALPHA_HQ, STUB_BRANCH_DAR_HQ],
    createSpy = vi.fn(() => of(STUB_CREATED_COUNT)),
  } = opts;

  const listBranchesSpy = vi.fn(() => of(branches));
  const listLocationsSpy = vi.fn(() => of([STUB_LOCATION]));

  TestBed.configureTestingModule({
    imports: [StockCountCreateComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter(STUB_ROUTES),
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of(STUB_ORG)) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of(companies)) },
      },
      {
        provide: BranchService,
        useValue: { list: listBranchesSpy },
      },
      {
        provide: StockLocationService,
        useValue: { activeForBranch: listLocationsSpy },
      },
      {
        provide: StockCountService,
        useValue: {
          create: createSpy,
          list: vi.fn(() => of({ rows: [], meta: {} })),
          getByUid: vi.fn(),
          enterCount: vi.fn(),
          post: vi.fn(),
          cancel: vi.fn(),
        },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(sessionUser),
          permissions: signal([]),
          activeBranchUid: signal(activeBranchUid),
        },
      },
    ],
  });

  return { listBranchesSpy, listLocationsSpy, createSpy };
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('StockCountCreateComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Defaults company to active company, NOT list[0] ────────────────────

  it('defaults selectedCompanyId to the active company, not list[0]', async () => {
    makeBed({
      // CO-ALPHA is list[0]; CO-DAR is the active company
      sessionUser: makeUser('CO-DAR', 'BR-DAR-HQ'),
      companies: [STUB_CO_ALPHA, STUB_CO_DAR],
    });
    const fixture = TestBed.createComponent(StockCountCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.selectedCompanyId()).toBe(STUB_CO_DAR.id);
  });

  // ── 2. Defaults branch to active branch, NOT list[0] ─────────────────────

  it('defaults selectedBranchUid to the active branch, not list[0]', async () => {
    makeBed({
      sessionUser: makeUser('CO-DAR', 'BR-DAR-HQ'),
      activeBranchUid: 'BR-DAR-HQ',
      // STUB_BRANCH_ALPHA_HQ is list[0]; STUB_BRANCH_DAR_HQ is the active branch
      branches: [STUB_BRANCH_ALPHA_HQ, STUB_BRANCH_DAR_HQ],
    });
    const fixture = TestBed.createComponent(StockCountCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.selectedBranchUid()).toBe(STUB_BRANCH_DAR_HQ.uid);
  });

  // ── 3. Falls back to list[0] when active context not in list ─────────────

  it('falls back to list[0] when active company uid is not in the loaded list', async () => {
    makeBed({
      sessionUser: makeUser('CO-UNKNOWN', 'BR-UNKNOWN'),
      activeBranchUid: 'BR-UNKNOWN',
      companies: [STUB_CO_ALPHA, STUB_CO_DAR],
      branches: [STUB_BRANCH_ALPHA_HQ, STUB_BRANCH_DAR_HQ],
    });
    const fixture = TestBed.createComponent(StockCountCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // Company fallback: list[0] = ALPHA
    expect(comp.selectedCompanyId()).toBe(STUB_CO_ALPHA.id);
    // Branch fallback: list[0] = ALPHA_HQ
    expect(comp.selectedBranchUid()).toBe(STUB_BRANCH_ALPHA_HQ.uid);
  });

  // ── 4. Falls back to list[0] when session has no active context ───────────

  it('falls back to list[0] when session user has no active company/branch', async () => {
    makeBed({
      sessionUser: makeUser(null, null),
      activeBranchUid: null,
      companies: [STUB_CO_ALPHA, STUB_CO_DAR],
      branches: [STUB_BRANCH_ALPHA_HQ],
    });
    const fixture = TestBed.createComponent(StockCountCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.selectedCompanyId()).toBe(STUB_CO_ALPHA.id);
    expect(comp.selectedBranchUid()).toBe(STUB_BRANCH_ALPHA_HQ.uid);
  });

  // ── 5. Location options loaded for the defaulted branch ───────────────────

  it('loads location options for the defaulted (active) branch', async () => {
    const { listLocationsSpy } = makeBed({
      sessionUser: makeUser('CO-DAR', 'BR-DAR-HQ'),
      activeBranchUid: 'BR-DAR-HQ',
      branches: [STUB_BRANCH_ALPHA_HQ, STUB_BRANCH_DAR_HQ],
    });
    const fixture = TestBed.createComponent(StockCountCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(listLocationsSpy).toHaveBeenCalledWith(STUB_BRANCH_DAR_HQ.uid);
    expect(comp.locationOptions()).toHaveLength(1);
    expect(comp.locationOptions()[0].uid).toBe(STUB_LOCATION.uid);
  });

  // ── 6. Changing company reloads branches and clears location ──────────────

  it('onCompanyChange reloads branches and clears location selection', async () => {
    const { listBranchesSpy } = makeBed();
    const fixture = TestBed.createComponent(StockCountCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fLocationUid.set('LOC1');
    listBranchesSpy.mockClear();

    comp.onCompanyChange(STUB_CO_ALPHA.id);
    await vi.runAllTimersAsync();

    expect(listBranchesSpy).toHaveBeenCalled();
    expect(comp.fLocationUid()).toBe('');
  });

  // ── 7. Changing branch reloads locations ──────────────────────────────────

  it('onBranchChange reloads locations for the new branch', async () => {
    const { listLocationsSpy } = makeBed();
    const fixture = TestBed.createComponent(StockCountCreateComponent);
    await vi.runAllTimersAsync();

    listLocationsSpy.mockClear();
    const comp = fixture.componentInstance;
    comp.onBranchChange('BR-ALPHA-HQ');
    await vi.runAllTimersAsync();

    expect(listLocationsSpy).toHaveBeenCalledWith('BR-ALPHA-HQ');
  });

  // ── 8. Submit without location → formError, no service call ──────────────

  it('submit without a location sets formError and does not call create', async () => {
    const { createSpy } = makeBed();
    const fixture = TestBed.createComponent(StockCountCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fLocationUid.set('');
    comp.submit();

    expect(comp.formError()).toBeTruthy();
    expect(createSpy).not.toHaveBeenCalled();
  });

  // ── 9. Successful submit navigates to detail ──────────────────────────────

  it('successful submit navigates to the new stock count detail page', async () => {
    const { createSpy } = makeBed();
    const fixture = TestBed.createComponent(StockCountCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    const router = TestBed.inject(Router);
    const navSpy = vi.spyOn(router, 'navigate');

    comp.fLocationUid.set(STUB_LOCATION.uid);
    comp.fCountDate.set('2025-06-01');
    comp.submit();
    await vi.runAllTimersAsync();

    expect(createSpy).toHaveBeenCalled();
    expect(navSpy).toHaveBeenCalledWith(['/admin/stock-counts/uid', STUB_CREATED_COUNT.uid]);
  });

  // ── 10. Submit failure surfaces error ─────────────────────────────────────

  it('submit failure sets formError from the API error message', async () => {
    const { createSpy } = makeBed({
      createSpy: vi.fn(() =>
        throwError(() => new HttpErrorResponse({
          status: 422,
          error: { errors: ['Location is not active.'], data: null },
        })),
      ),
    });
    const fixture = TestBed.createComponent(StockCountCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fLocationUid.set(STUB_LOCATION.uid);
    comp.fCountDate.set('2025-06-01');
    comp.submit();
    await vi.runAllTimersAsync();

    expect(createSpy).toHaveBeenCalled();
    expect(comp.formError()).toBe('Location is not active.');
    expect(comp.submitting()).toBe(false);
  });
});
