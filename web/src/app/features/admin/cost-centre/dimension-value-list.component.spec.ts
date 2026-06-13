/**
 * DimensionValueListComponent spec.
 * Mirrors chart-of-accounts-list.component.spec.ts conventions (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Startup: loads companies then dimensions then values on init.
 *  2. isEmpty is true when no values returned.
 *  3. create() validation: requires code and name.
 *  4. create() calls service.createValue with correct payload.
 *  5. 403 response sets state to 'forbidden'.
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
import type { DimensionValuePage } from './cost-centre.service';
import { DimensionValueListComponent } from './dimension-value-list.component';

// ── Helpers ────────────────────────────────────────────────────────────────────

const emptyPage = (): DimensionValuePage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const sampleDimension = () => ({
  id: '1', uid: 'DIM1', companyId: '10',
  slot: 'COST_CENTRE' as const, code: 'CC', name: 'Cost Centre',
  builtIn: true, mandatory: false, status: 'ACTIVE' as const,
});

const sampleValue = () => ({
  id: '100', uid: 'VAL1', companyId: '10',
  dimensionId: '1', dimensionUid: 'DIM1', slot: 'COST_CENTRE',
  code: 'CC001', name: 'Head Office',
  parentId: null, parentUid: null, active: true, status: 'ACTIVE' as const,
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
  const sessionStore = makeSessionStore(canManage);

  TestBed.configureTestingModule({
    imports: [DimensionValueListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: CostCentreService,
        useValue: {
          listDimensions: vi.fn(() => of([sampleDimension()])),
          listValues: vi.fn(listImpl ?? (() => of(emptyPage()))),
          createValue: vi.fn(() => of(sampleValue())),
          deactivateValue: vi.fn(() => of(sampleValue())),
          activateValue: vi.fn(() => of(sampleValue())),
          deleteValue: vi.fn(() => of(undefined)),
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

// ── Init ───────────────────────────────────────────────────────────────────────

describe('DimensionValueListComponent — init', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fires exactly one load after companies + dimensions resolve', async () => {
    TestBed.createComponent(DimensionValueListComponent);
    const svc = TestBed.inject(CostCentreService) as any;
    await vi.runAllTimersAsync();

    expect(svc.listDimensions).toHaveBeenCalledTimes(1);
    expect(svc.listValues).toHaveBeenCalledTimes(1);
    expect(svc.listValues).toHaveBeenCalledWith('DIM1', 0, 20);
  });

  it('isEmpty is true when no values returned', async () => {
    const comp = TestBed.createComponent(DimensionValueListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── Create form ────────────────────────────────────────────────────────────────

describe('DimensionValueListComponent — create form', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canManage: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('create() validation: requires code', async () => {
    const comp = TestBed.createComponent(DimensionValueListComponent).componentInstance;
    const svc = TestBed.inject(CostCentreService) as any;
    await vi.runAllTimersAsync();

    comp.newCode.set('');
    comp.newName.set('Head Office');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createValue).not.toHaveBeenCalled();
  });

  it('create() validation: requires name', async () => {
    const comp = TestBed.createComponent(DimensionValueListComponent).componentInstance;
    const svc = TestBed.inject(CostCentreService) as any;
    await vi.runAllTimersAsync();

    comp.newCode.set('CC001');
    comp.newName.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createValue).not.toHaveBeenCalled();
  });

  it('create() calls service.createValue with correct payload', async () => {
    const comp = TestBed.createComponent(DimensionValueListComponent).componentInstance;
    const svc = TestBed.inject(CostCentreService) as any;
    await vi.runAllTimersAsync();

    comp.newCode.set('CC001');
    comp.newName.set('Head Office');
    comp.newParentUid.set('');
    comp.create();

    expect(svc.createValue).toHaveBeenCalledOnce();
    const req = svc.createValue.mock.calls[0][0];
    expect(req.dimensionUid).toBe('DIM1');
    expect(req.code).toBe('CC001');
    expect(req.name).toBe('Head Office');
    expect(req.parentUid).toBeNull();
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('DimensionValueListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets state to forbidden when list returns 403', async () => {
    makeBed({
      listImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(DimensionValueListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
