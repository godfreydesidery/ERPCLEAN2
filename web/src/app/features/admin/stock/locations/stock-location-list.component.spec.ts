/**
 * StockLocationListComponent — key behaviour specs.
 *
 * Covers:
 *  1. Loads list on company selection (load-once pattern).
 *  2. isEmpty signal is true when rows are empty after load.
 *  3. Validation guard: create rejects blank code.
 *  4. Success payload: create calls service and reloads.
 *  5. 403 forbidden sets state = 'forbidden'.
 *  6. Deactivate calls service and patches row status.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { BranchService } from '../../branch/branch.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { StockLocationService } from './stock-location.service';
import { StockLocationListComponent } from './stock-location-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const STUB_LOCATION = {
  uid: 'LOC1', id: '100',
  companyId: '10', branchId: '5',
  code: 'WH-01', name: 'Main Warehouse',
  locationType: 'WAREHOUSE' as const,
  isDefault: true,
  status: 'ACTIVE' as const,
};

const emptyPage = () => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const locationPage = () => ({
  rows: [STUB_LOCATION],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(overrides: {
  listSpy?: ReturnType<typeof vi.fn>;
  createSpy?: ReturnType<typeof vi.fn>;
  deactivateSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const listSpy = overrides.listSpy ?? vi.fn(() => of(locationPage()));
  const createSpy = overrides.createSpy ?? vi.fn(() => of(STUB_LOCATION));
  const deactivateSpy = overrides.deactivateSpy ?? vi.fn(() => of(undefined));

  TestBed.configureTestingModule({
    imports: [StockLocationListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: StockLocationService,
        useValue: {
          list: listSpy,
          create: createSpy,
          update: vi.fn(() => of(STUB_LOCATION)),
          deactivate: deactivateSpy,
          reactivate: vi.fn(() => of(STUB_LOCATION)),
          setDefault: vi.fn(() => of(STUB_LOCATION)),
        },
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
        provide: BranchService,
        useValue: { list: vi.fn(() => of([])) },
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

  return { listSpy, createSpy, deactivateSpy };
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('StockLocationListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Loads list ──────────────────────────────────────────────────────────

  it('loads stock locations on company selection', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalled();
    expect(comp.rows()).toHaveLength(1);
    expect(comp.state()).toBe('idle');
  });

  // ── 2. isEmpty signal ──────────────────────────────────────────────────────

  it('isEmpty is true when rows are empty after load', async () => {
    makeBed({ listSpy: vi.fn(() => of(emptyPage())) });
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isEmpty()).toBe(true);
  });

  // ── 3. Validation guard — blank code ──────────────────────────────────────

  it('sets formError and does NOT call create when code is blank', async () => {
    const { createSpy } = makeBed();
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.showCreateForm.set(true);
    comp.fCode.set('');
    comp.fName.set('Warehouse 1');
    comp.fBranchUid.set('BRANCH-UID-1');

    comp.submitCreate();

    expect(comp.formError()).toBeTruthy();
    expect(createSpy).not.toHaveBeenCalled();
  });

  // ── 4. Success payload ────────────────────────────────────────────────────

  it('calls create with correct payload and reloads', async () => {
    const { createSpy, listSpy } = makeBed();
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.showCreateForm.set(true);
    comp.fCode.set('WH-02');
    comp.fName.set('Second Warehouse');
    comp.fLocationType.set('WAREHOUSE');
    comp.fBranchUid.set('BRANCH-UID-1');
    comp.fMakeDefault.set(false);

    comp.submitCreate();
    await vi.runAllTimersAsync();

    expect(createSpy).toHaveBeenCalledOnce();
    const req = createSpy.mock.calls[0][0];
    expect(req.code).toBe('WH-02');
    expect(req.name).toBe('Second Warehouse');
    expect(req.branchUid).toBe('BRANCH-UID-1');
    // list is called again after create
    expect(listSpy.mock.calls.length).toBeGreaterThan(1);
  });

  // ── 5. 403 forbidden ──────────────────────────────────────────────────────

  it('sets state=forbidden on 403 response', async () => {
    const listSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
    );
    makeBed({ listSpy });
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });

  // ── 6. Deactivate ─────────────────────────────────────────────────────────

  it('calls deactivate and patches row status to INACTIVE', async () => {
    const { deactivateSpy } = makeBed();
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.rows()[0].status).toBe('ACTIVE');
    comp.deactivate(STUB_LOCATION);
    await vi.runAllTimersAsync();

    expect(deactivateSpy).toHaveBeenCalledWith(STUB_LOCATION.uid);
    expect(comp.rows()[0].status).toBe('INACTIVE');
  });
});
