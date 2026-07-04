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
 *  7. Branch filter passes branchUid.
 *  8. Post-create filter switches to created branch.
 *  9. Agent selector (ADR-0051 D-8.4): create sends agentUid for a VAN location.
 * 10. Agent field is cleared client-side when the location type changes away from VAN.
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
import { AgentService } from '../../parties/agent.service';
import { StockLocationService } from './stock-location.service';
import { StockLocationListComponent } from './stock-location-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const STUB_BRANCH_ACTIVE = { id: '5', uid: 'BR-ACTIVE', companyId: '10', companyUid: 'CO1', code: 'B1', name: 'Active Branch', timeZone: 'UTC', isDefault: true, status: 'ACTIVE' };
const STUB_BRANCH_OTHER = { id: '7', uid: 'BR-OTHER', companyId: '10', companyUid: 'CO1', code: 'B2', name: 'Other Branch', timeZone: 'UTC', isDefault: false, status: 'ACTIVE' };
const STUB_LOCATION = {
  uid: 'LOC1', id: '100',
  companyId: '10', branchId: '5',
  code: 'WH-01', name: 'Main Warehouse',
  locationType: 'WAREHOUSE' as const,
  isDefault: true,
  status: 'ACTIVE' as const,
  agentUid: null,
  agentName: null,
};
const STUB_VAN_LOCATION = { ...STUB_LOCATION, uid: 'LOC-VAN', id: '102', code: 'VAN-01', name: 'Van 01', locationType: 'VAN' as const, isDefault: false };
const STUB_AGENT = { uid: 'AGT1', id: '1', companyId: '10', code: 'AG-01', partyType: 'INDIVIDUAL' as const, displayName: 'Hamisi', legalName: null, tin: null, vatRegistered: false, vrn: null, businessRegNo: null, mobileMoneyNo: null, phone: null, email: null, physicalAddress: null, postalAddress: null, region: null, district: null, agentKind: 'EXTERNAL' as const, appUserId: null, status: 'ACTIVE' as const, version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null };
// A location created in the OTHER branch (branchId 7 → uid BR-OTHER).
const STUB_LOCATION_OTHER_BRANCH = { ...STUB_LOCATION, uid: 'LOC2', id: '101', branchId: '7', code: 'WH-02', isDefault: false };

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
  updateSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const listSpy = overrides.listSpy ?? vi.fn(() => of(locationPage()));
  const createSpy = overrides.createSpy ?? vi.fn(() => of(STUB_LOCATION));
  const deactivateSpy = overrides.deactivateSpy ?? vi.fn(() => of(undefined));
  const updateSpy = overrides.updateSpy ?? vi.fn(() => of(STUB_LOCATION));

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
          update: updateSpy,
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
        useValue: { list: vi.fn(() => of([STUB_BRANCH_ACTIVE, STUB_BRANCH_OTHER])) },
      },
      {
        provide: AgentService,
        useValue: {
          list: vi.fn(() => of({ rows: [STUB_AGENT], meta: { page: 0, size: 200, totalElements: 1, totalPages: 1, hasNext: false } })),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal('BR-ACTIVE'),
        },
      },
    ],
  });

  return { listSpy, createSpy, deactivateSpy, updateSpy };
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

  // ── 7. Branch filter passes branchUid ───────────────────────────────────────

  it('defaults the filter to the active branch and passes branchUid on filter change', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // Filter defaults to the active branch uid.
    expect(comp.filterBranchUid()).toBe('BR-ACTIVE');

    // Change the filter to another accessible branch → list re-scopes to that branchUid.
    comp.onFilterBranchChange('BR-OTHER');
    await vi.runAllTimersAsync();

    const lastCall = listSpy.mock.calls.at(-1)!;
    // list(page, size, branchUid)
    expect(lastCall[2]).toBe('BR-OTHER');
  });

  // ── 8. Post-create filter switches to created branch ─────────────────────────

  it('switches the filter to the created location branch and reloads after create', async () => {
    const createSpy = vi.fn(() => of(STUB_LOCATION_OTHER_BRANCH));
    const { listSpy } = makeBed({ createSpy });
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // Filter starts on the active branch.
    expect(comp.filterBranchUid()).toBe('BR-ACTIVE');

    comp.showCreateForm.set(true);
    comp.fCode.set('WH-02');
    comp.fName.set('Other Branch Store');
    comp.fLocationType.set('STORE');
    comp.fBranchUid.set('BR-OTHER');

    comp.submitCreate();
    await vi.runAllTimersAsync();

    // The created location's branch (branchId 7 → BR-OTHER) becomes the active filter…
    expect(comp.filterBranchUid()).toBe('BR-OTHER');
    // …and the list reloads scoped to that branch so the new row is visible.
    const lastCall = listSpy.mock.calls.at(-1)!;
    expect(lastCall[2]).toBe('BR-OTHER');
  });

  // ── 9. Agent selector — create sends agentUid for a VAN location ─────────────

  it('sends agentUid on create when locationType is VAN', async () => {
    const { createSpy } = makeBed({ createSpy: vi.fn(() => of(STUB_VAN_LOCATION)) });
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.agentOptions().map((a) => a.uid)).toEqual(['AGT1']);

    comp.showCreateForm.set(true);
    comp.fCode.set('VAN-01');
    comp.fName.set('Van 01');
    comp.onCreateLocationTypeChange('VAN');
    comp.fBranchUid.set('BRANCH-UID-1');
    comp.fAgentUid.set('AGT1');

    comp.submitCreate();
    await vi.runAllTimersAsync();

    expect(createSpy).toHaveBeenCalledOnce();
    const req = createSpy.mock.calls[0][0];
    expect(req.locationType).toBe('VAN');
    expect(req.agentUid).toBe('AGT1');
  });

  it('omits agentUid on create when locationType is not VAN', async () => {
    const { createSpy } = makeBed();
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.showCreateForm.set(true);
    comp.fCode.set('WH-03');
    comp.fName.set('Third Warehouse');
    comp.onCreateLocationTypeChange('WAREHOUSE');
    comp.fBranchUid.set('BRANCH-UID-1');
    // Force a value into fAgentUid directly (bypassing the type-change clear) to prove the
    // submit-time guard also suppresses agentUid for a non-VAN request, not just the UI clear.
    comp.fAgentUid.set('AGT1');

    comp.submitCreate();
    await vi.runAllTimersAsync();

    const req = createSpy.mock.calls[0][0];
    expect(req.agentUid).toBeUndefined();
  });

  // ── 10. Agent field cleared when type changes away from VAN ─────────────────

  it('onCreateLocationTypeChange clears fAgentUid when leaving VAN', async () => {
    makeBed();
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.onCreateLocationTypeChange('VAN');
    comp.fAgentUid.set('AGT1');
    expect(comp.fAgentUid()).toBe('AGT1');

    comp.onCreateLocationTypeChange('STORE');
    expect(comp.fAgentUid()).toBe('');
  });

  it('submitEdit sends agentUid when eLocationType is VAN', async () => {
    const { updateSpy } = makeBed();
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.startEdit(STUB_VAN_LOCATION);
    comp.onEditLocationTypeChange('VAN');
    comp.eAgentUid.set('AGT1');
    comp.submitEdit(STUB_VAN_LOCATION);
    await vi.runAllTimersAsync();

    expect(updateSpy).toHaveBeenCalledWith(STUB_VAN_LOCATION.uid, {
      name: STUB_VAN_LOCATION.name,
      locationType: 'VAN',
      agentUid: 'AGT1',
    });
  });

  it('submitEdit sends an explicit empty-string agentUid when the agent is cleared on a VAN (never omits it)', async () => {
    // The backend distinguishes omitted/null ("leave unchanged") from "" ("clear") — a lingering
    // `|| undefined` fallback would silently swallow a clear action. Regression for that bug.
    const alreadyAssignedVan = { ...STUB_VAN_LOCATION, agentUid: 'AGT1', agentName: 'Hamisi' };
    const { updateSpy } = makeBed();
    const fixture = TestBed.createComponent(StockLocationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.startEdit(alreadyAssignedVan);
    expect(comp.eAgentUid()).toBe('AGT1');

    comp.eAgentUid.set(''); // user clears the agent picker back to "no agent"
    comp.submitEdit(alreadyAssignedVan);
    await vi.runAllTimersAsync();

    expect(updateSpy).toHaveBeenCalledWith(alreadyAssignedVan.uid, {
      name: alreadyAssignedVan.name,
      locationType: 'VAN',
      agentUid: '',
    });
  });
});
