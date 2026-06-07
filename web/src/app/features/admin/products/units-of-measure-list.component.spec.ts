/**
 * UnitsOfMeasureListComponent spec.
 *
 * Mirrors price-list-list / product-list spec patterns (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Startup: loads companies then units on init.
 *  2. canManage = false: create button absent.
 *  3. canManage = true: create button present; create() calls productService.createUnit.
 *  4. create() validation: requires code and name.
 *  5. startEdit / cancelEdit / saveEdit round-trip.
 *  6. archive() calls productService.archiveUnit.
 *  7. restore() calls productService.restoreUnit.
 *  8. 403 response sets state to 'forbidden'.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from './product.service';
import { UnitsOfMeasureListComponent } from './units-of-measure-list.component';
import type { UnitOfMeasureDto } from '../models/product.model';

// ── Helpers ────────────────────────────────────────────────────────────────────

function makeUnit(overrides: Partial<UnitOfMeasureDto> = {}): UnitOfMeasureDto {
  return {
    id: '1',
    uid: 'U1',
    companyId: '10',
    code: 'PCS',
    name: 'Pieces',
    status: 'ACTIVE',
    version: null,
    createdAt: null,
    createdBy: null,
    updatedAt: null,
    updatedBy: null,
    ...overrides,
  };
}

function makeUnitPage(units: UnitOfMeasureDto[] = []) {
  return {
    rows: units,
    meta: { page: 0, size: 200, totalElements: units.length, totalPages: 1, hasNext: false },
  };
}

function makeSessionStore(canManage = false) {
  return {
    hasPermission: vi.fn(() => canManage),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { canManage?: boolean; listUnits?: () => any } = {}) {
  const { canManage = false, listUnits = () => of(makeUnitPage()) } = opts;
  const sessionStore = makeSessionStore(canManage);

  TestBed.configureTestingModule({
    imports: [UnitsOfMeasureListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ProductService,
        useValue: {
          listUnits: vi.fn(listUnits),
          createUnit: vi.fn(() => of(makeUnit())),
          updateUnit: vi.fn(() => of(makeUnit({ name: 'Updated' }))),
          archiveUnit: vi.fn(() => of(makeUnit({ status: 'ARCHIVED' }))),
          restoreUnit: vi.fn(() => of(makeUnit({ status: 'ACTIVE' }))),
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

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('UnitsOfMeasureListComponent — init', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('loads companies and units on startup', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    expect(comp.companies().length).toBe(1);
    expect(svc.listUnits).toHaveBeenCalledWith('10');
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no units returned', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isEmpty()).toBe(true);
  });

  it('isEmpty is false when units are returned', async () => {
    TestBed.resetTestingModule();
    makeBed({ listUnits: () => of(makeUnitPage([makeUnit()])) });
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isEmpty()).toBe(false);
    expect(comp.rows().length).toBe(1);
  });
});

describe('UnitsOfMeasureListComponent — canManage=false', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canManage: false });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('canManage is false', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.canManage()).toBe(false);
  });
});

describe('UnitsOfMeasureListComponent — create', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canManage: true });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('create() validation: requires code and name', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    comp.newCode.set('');
    comp.newName.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createUnit).not.toHaveBeenCalled();
  });

  it('create() calls createUnit with companyUid, code, name', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    comp.newCode.set('BOX');
    comp.newName.set('Box');
    comp.create();

    expect(svc.createUnit).toHaveBeenCalledOnce();
    const req = svc.createUnit.mock.calls[0][0];
    expect(req.companyUid).toBe('CO1');
    expect(req.code).toBe('BOX');
    expect(req.name).toBe('Box');
  });

  it('create() resets form on success', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newCode.set('KG');
    comp.newName.set('Kilogram');
    comp.showCreateForm.set(true);
    comp.create();

    expect(comp.newCode()).toBe('');
    expect(comp.newName()).toBe('');
    expect(comp.showCreateForm()).toBe(false);
  });
});

describe('UnitsOfMeasureListComponent — inline edit', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canManage: true, listUnits: () => of(makeUnitPage([makeUnit()])) });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('startEdit sets editingUid and editName', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    const u = comp.rows()[0];
    comp.startEdit(u);

    expect(comp.editingUid()).toBe(u.uid);
    expect(comp.editName()).toBe(u.name);
  });

  it('cancelEdit clears editingUid', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.startEdit(comp.rows()[0]);
    comp.cancelEdit();

    expect(comp.editingUid()).toBeNull();
  });

  it('saveEdit() validation: requires name', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    const u = comp.rows()[0];
    comp.startEdit(u);
    comp.editName.set('');
    comp.saveEdit(u);

    expect(comp.editError()).toBeTruthy();
    expect(svc.updateUnit).not.toHaveBeenCalled();
  });

  it('saveEdit() calls updateUnit and updates row in-place', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    const u = comp.rows()[0];
    comp.startEdit(u);
    comp.editName.set('Updated');
    comp.saveEdit(u);

    expect(svc.updateUnit).toHaveBeenCalledOnce();
    expect(svc.updateUnit.mock.calls[0][0]).toBe(u.uid);
    expect(comp.editingUid()).toBeNull();
  });
});

describe('UnitsOfMeasureListComponent — archive / restore', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canManage: true, listUnits: () => of(makeUnitPage([makeUnit()])) });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('archive() calls archiveUnit with uid', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    comp.archive(comp.rows()[0]);

    expect(svc.archiveUnit).toHaveBeenCalledOnce();
    expect(svc.archiveUnit.mock.calls[0][0]).toBe('U1');
  });

  it('restore() calls restoreUnit with uid', async () => {
    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    comp.restore(comp.rows()[0]);

    expect(svc.restoreUnit).toHaveBeenCalledOnce();
    expect(svc.restoreUnit.mock.calls[0][0]).toBe('U1');
  });
});

describe('UnitsOfMeasureListComponent — 403 forbidden', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('sets state to forbidden when listUnits returns 403', async () => {
    makeBed({
      listUnits: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const fixture = TestBed.createComponent(UnitsOfMeasureListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
