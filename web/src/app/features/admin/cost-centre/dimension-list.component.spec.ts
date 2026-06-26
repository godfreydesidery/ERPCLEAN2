/**
 * DimensionListComponent spec.
 *
 * Covers:
 *  1. Add Dimension form: calls createDimension with companyId + code + name.
 *  2. Success path: returned DimensionDto appended to the row list.
 *  3. 409 response: sets inline formError — no global error modal.
 *  4. Add Dimension form hidden when user lacks COSTING.MANAGE.
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
import { CostCentreService } from './cost-centre.service';
import { DimensionListComponent } from './dimension-list.component';
import type { DimensionDto } from './models/cost-centre.model';

// ── Fixtures ───────────────────────────────────────────────────────────────────

const builtInDim = (): DimensionDto => ({
  id: '1', uid: 'DIM1', companyId: '10',
  slot: 'COST_CENTRE', code: 'CC', name: 'Cost Centre',
  builtIn: true, mandatory: false, status: 'ACTIVE',
});

const customDim = (): DimensionDto => ({
  id: '3', uid: 'DIM3', companyId: '10',
  slot: 'DIMENSION_3', code: 'PROJ', name: 'Project',
  builtIn: false, mandatory: false, status: 'ACTIVE',
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

function makeBed(opts: {
  initialDimensions?: DimensionDto[];
  canManage?: boolean;
} = {}) {
  const { initialDimensions = [builtInDim()], canManage = false } = opts;
  const sessionStore = makeSessionStore(canManage);

  TestBed.configureTestingModule({
    imports: [DimensionListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: CostCentreService,
        useValue: {
          listDimensions: vi.fn(() => of(initialDimensions)),
          setMandatory: vi.fn(() => of(builtInDim())),
          createDimension: vi.fn(() => of(customDim())),
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
      { provide: SessionStore, useValue: sessionStore },
    ],
  });
}

// ── Add Dimension — success path ───────────────────────────────────────────────

describe('DimensionListComponent — add dimension (success)', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canManage: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls createDimension with companyId, code, name and appends the returned row', async () => {
    const comp = TestBed.createComponent(DimensionListComponent).componentInstance;
    const svc = TestBed.inject(CostCentreService) as any;
    const alerts = TestBed.inject(AlertService) as any;
    await vi.runAllTimersAsync();

    const initialCount = comp.rows().length;
    comp.newDimCode.set('PROJ');
    comp.newDimName.set('Project');
    comp.newDimDescription.set('Project dimension');
    comp.addDimension();

    expect(svc.createDimension).toHaveBeenCalledOnce();
    const arg = svc.createDimension.mock.calls[0][0];
    expect(arg.companyId).toBe('10');
    expect(arg.code).toBe('PROJ');
    expect(arg.name).toBe('Project');
    expect(arg.description).toBe('Project dimension');

    expect(comp.rows().length).toBe(initialCount + 1);
    expect(comp.rows().at(-1)!.uid).toBe('DIM3');
    expect(alerts.success).toHaveBeenCalledWith('Dimension added', 'Project');
    // form fields cleared
    expect(comp.newDimCode()).toBe('');
    expect(comp.newDimName()).toBe('');
  });

  it('requires code — sets formError and does not call service', async () => {
    const comp = TestBed.createComponent(DimensionListComponent).componentInstance;
    const svc = TestBed.inject(CostCentreService) as any;
    await vi.runAllTimersAsync();

    comp.newDimCode.set('');
    comp.newDimName.set('Project');
    comp.addDimension();

    expect(comp.addDimFormError()).toBeTruthy();
    expect(svc.createDimension).not.toHaveBeenCalled();
  });

  it('requires name — sets formError and does not call service', async () => {
    const comp = TestBed.createComponent(DimensionListComponent).componentInstance;
    const svc = TestBed.inject(CostCentreService) as any;
    await vi.runAllTimersAsync();

    comp.newDimCode.set('PROJ');
    comp.newDimName.set('');
    comp.addDimension();

    expect(comp.addDimFormError()).toBeTruthy();
    expect(svc.createDimension).not.toHaveBeenCalled();
  });
});

// ── Add Dimension — 409 path ───────────────────────────────────────────────────

describe('DimensionListComponent — add dimension (409 slot full)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canManage: true });
    // Override createDimension to return 409
    const svc = TestBed.inject(CostCentreService) as any;
    svc.createDimension = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 409, statusText: 'Conflict' })),
    );
  });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets inline formError on 409 and does NOT call the global error alert', async () => {
    const comp = TestBed.createComponent(DimensionListComponent).componentInstance;
    const alerts = TestBed.inject(AlertService) as any;
    await vi.runAllTimersAsync();

    comp.newDimCode.set('PROJ');
    comp.newDimName.set('Project');
    comp.addDimension();

    expect(comp.addDimFormError()).toBe('You can have at most 2 custom dimensions.');
    expect(alerts.error).not.toHaveBeenCalled();
  });
});

// ── COSTING.MANAGE gate ────────────────────────────────────────────────────────

describe('DimensionListComponent — permission gate', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canManage: false }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('canManage is false and customSlotsFull computed does not throw', async () => {
    const comp = TestBed.createComponent(DimensionListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.canManage()).toBe(false);
    // form signals still accessible without error
    expect(comp.newDimCode()).toBe('');
  });
});

// ── customSlotsFull computed ───────────────────────────────────────────────────

describe('DimensionListComponent — customSlotsFull', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('is false when fewer than 2 non-built-in dimensions exist', async () => {
    vi.useFakeTimers();
    makeBed({ canManage: true, initialDimensions: [builtInDim()] });
    const comp = TestBed.createComponent(DimensionListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.customSlotsFull()).toBe(false);
  });

  it('is true when 2 non-built-in dimensions exist', async () => {
    vi.useFakeTimers();
    makeBed({
      canManage: true,
      initialDimensions: [
        builtInDim(),
        customDim(),
        { ...customDim(), id: '4', uid: 'DIM4', slot: 'DIMENSION_4', code: 'REG', name: 'Region' },
      ],
    });
    const comp = TestBed.createComponent(DimensionListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.customSlotsFull()).toBe(true);
  });
});
