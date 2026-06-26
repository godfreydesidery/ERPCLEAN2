/**
 * TaxRateListComponent spec.
 * Mirrors units-of-measure-list.component.spec.ts (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Startup: loads companies then tax rates on init.
 *  2. canManage=false: edit button absent.
 *  3. startEdit / cancelEdit / saveEdit round-trip.
 *  4. saveEdit() validation: requires a numeric rate.
 *  5. saveEdit() calls salesService.updateTaxRate.
 *  6. 403 response sets state to 'forbidden'.
 *  7. submitAdd() calls createTaxRate with the active companyId, chosen vatStatus, fraction rate.
 *  8. availableVatStatuses excludes classifications already present in rows().
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
import { SalesService } from './sales.service';
import { TaxRateListComponent } from './tax-rate-list.component';
import type { TaxRateDto } from '../models/sales.model';

// ── Helpers ────────────────────────────────────────────────────────────────────

function makeTaxRate(overrides: Partial<TaxRateDto> = {}): TaxRateDto {
  return {
    id: '1',
    uid: 'TR1',
    companyId: '10',
    vatStatus: 'STANDARD',
    rate: '0.1800', // backend stores a fraction; 0.18 = 18%
    version: null,
    createdAt: null,
    createdBy: null,
    updatedAt: null,
    updatedBy: null,
    ...overrides,
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

function makeBed(opts: {
  canManage?: boolean;
  listImpl?: () => any;
  createImpl?: () => any;
} = {}) {
  const {
    canManage = false,
    listImpl = () => of([makeTaxRate()]),
    createImpl = () => of(makeTaxRate({ uid: 'TR2', id: '2', vatStatus: 'ZERO_RATED', rate: '0.0000' })),
  } = opts;
  const sessionStore = makeSessionStore(canManage);

  TestBed.configureTestingModule({
    imports: [TaxRateListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: SalesService,
        useValue: {
          listTaxRates: vi.fn(listImpl),
          updateTaxRate: vi.fn(() => of(makeTaxRate({ rate: '0.2000' }))),
          createTaxRate: vi.fn(createImpl),
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

describe('TaxRateListComponent — init', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('loads companies and tax rates on startup', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    expect(comp.companies().length).toBe(1);
    expect(svc.listTaxRates).toHaveBeenCalledWith('10');
    expect(comp.state()).toBe('idle');
    expect(comp.rows().length).toBe(1);
  });

  it('isEmpty is false when rows are returned', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(false);
  });
});

// ── canManage=false ────────────────────────────────────────────────────────────

describe('TaxRateListComponent — canManage=false', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canManage: false });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('canManage is false', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.canManage()).toBe(false);
  });
});

// ── Inline edit ────────────────────────────────────────────────────────────────

describe('TaxRateListComponent — inline edit', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canManage: true });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('startEdit sets editingUid and editRate', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    await vi.runAllTimersAsync();

    const tr = comp.rows()[0];
    comp.startEdit(tr);

    expect(comp.editingUid()).toBe(tr.uid);
    // The edit field is a percentage prefilled from the stored fraction (0.18 → "18").
    expect(comp.editRate()).toBe('18');
  });

  it('cancelEdit clears editingUid', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.startEdit(comp.rows()[0]);
    comp.cancelEdit();

    expect(comp.editingUid()).toBeNull();
  });

  it('saveEdit() validation: requires numeric rate', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    const tr = comp.rows()[0];
    comp.startEdit(tr);
    comp.editRate.set('');
    comp.saveEdit(tr);

    expect(comp.editError()).toBeTruthy();
    expect(svc.updateTaxRate).not.toHaveBeenCalled();
  });

  it('saveEdit() rejects non-numeric input', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    const tr = comp.rows()[0];
    comp.startEdit(tr);
    comp.editRate.set('abc');
    comp.saveEdit(tr);

    expect(comp.editError()).toBeTruthy();
    expect(svc.updateTaxRate).not.toHaveBeenCalled();
  });

  it('saveEdit() calls updateTaxRate with uid and rate', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    const tr = comp.rows()[0];
    comp.startEdit(tr);
    comp.editRate.set('20'); // user types a percentage
    comp.saveEdit(tr);

    expect(svc.updateTaxRate).toHaveBeenCalledOnce();
    expect(svc.updateTaxRate.mock.calls[0][0]).toBe(tr.uid);
    // …converted to the backend's fraction contract.
    expect(svc.updateTaxRate.mock.calls[0][1]).toEqual({ rate: '0.2000' });
  });

  it('saveEdit() rejects a percentage out of range (>= 100%)', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    const tr = comp.rows()[0];
    comp.startEdit(tr);
    comp.editRate.set('150');
    comp.saveEdit(tr);

    expect(comp.editError()).toBeTruthy();
    expect(svc.updateTaxRate).not.toHaveBeenCalled();
  });

  it('saveEdit() clears editingUid on success', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    await vi.runAllTimersAsync();

    const tr = comp.rows()[0];
    comp.startEdit(tr);
    comp.editRate.set('20.00');
    comp.saveEdit(tr);

    expect(comp.editingUid()).toBeNull();
  });

  it('saveEdit() updates the row in place on success', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    await vi.runAllTimersAsync();

    const tr = comp.rows()[0];
    comp.startEdit(tr);
    comp.editRate.set('20');
    comp.saveEdit(tr);

    expect(comp.rows()[0].rate).toBe('0.2000');
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('TaxRateListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('sets state to forbidden when listTaxRates returns 403', async () => {
    makeBed({
      listImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});

// ── Add tax rate ───────────────────────────────────────────────────────────────

describe('TaxRateListComponent — submitAdd()', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed({ canManage: true });
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('calls createTaxRate with active companyId, chosen vatStatus and fraction rate', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    // rows() has one STANDARD row; pick ZERO_RATED for the add form
    comp.addVatStatus.set('ZERO_RATED');
    comp.addRate.set('18'); // user types 18 % -> fraction 0.1800
    comp.submitAdd();

    expect(svc.createTaxRate).toHaveBeenCalledOnce();
    expect(svc.createTaxRate.mock.calls[0][0]).toEqual({
      companyId: '10',
      vatStatus: 'ZERO_RATED',
      rate: '0.1800',
    });
  });

  it('defaults addVatStatus to a MISSING classification after load, so an untouched submit is not a duplicate', async () => {
    // Company already has STANDARD + ZERO_RATED → only EXEMPT is available.
    makeBed({
      canManage: true,
      listImpl: () =>
        of([makeTaxRate(), makeTaxRate({ uid: 'TR2', id: '2', vatStatus: 'ZERO_RATED', rate: '0.0000' })]),
      createImpl: () => of(makeTaxRate({ uid: 'TR3', id: '3', vatStatus: 'EXEMPT', rate: '0.0000' })),
    });
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    // After load the default must follow the available list, not the initial 'STANDARD'.
    expect(comp.addVatStatus()).toBe('EXEMPT');

    // Submit WITHOUT touching the dropdown → must post the available classification, not STANDARD.
    comp.addRate.set('0');
    comp.submitAdd();
    expect(svc.createTaxRate.mock.calls[0][0].vatStatus).toBe('EXEMPT');
  });

  it('appends the returned row to rows() on success', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    await vi.runAllTimersAsync();

    const initialCount = comp.rows().length; // 1 (STANDARD)
    comp.addVatStatus.set('ZERO_RATED');
    comp.addRate.set('0'); // zero-rated band is typically 0 %
    comp.submitAdd();

    expect(comp.rows().length).toBe(initialCount + 1);
    expect(comp.rows().at(-1)?.vatStatus).toBe('ZERO_RATED');
  });

  it('sets addError on 409 without calling the global error modal', async () => {
    makeBed({
      canManage: true,
      createImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 409, statusText: 'Conflict' })),
    });
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    const alerts = TestBed.inject(AlertService) as any;
    await vi.runAllTimersAsync();

    comp.addVatStatus.set('ZERO_RATED');
    comp.addRate.set('18');
    comp.submitAdd();

    expect(comp.addError()).toBe('A rate for this classification already exists.');
    expect(alerts.error).not.toHaveBeenCalled();
  });

  it('validates: empty rate sets addError and does not call createTaxRate', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    comp.addVatStatus.set('ZERO_RATED');
    comp.addRate.set('');
    comp.submitAdd();

    expect(comp.addError()).toBeTruthy();
    expect(svc.createTaxRate).not.toHaveBeenCalled();
  });

  it('validates: rate > 99.99 sets addError', async () => {
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    const svc = TestBed.inject(SalesService) as any;
    await vi.runAllTimersAsync();

    comp.addVatStatus.set('ZERO_RATED');
    comp.addRate.set('150');
    comp.submitAdd();

    expect(comp.addError()).toBeTruthy();
    expect(svc.createTaxRate).not.toHaveBeenCalled();
  });
});

// ── availableVatStatuses ───────────────────────────────────────────────────────

describe('TaxRateListComponent — availableVatStatuses', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('excludes classifications already present in rows()', async () => {
    // Seed with STANDARD — ZERO_RATED and EXEMPT should still be available.
    makeBed({ listImpl: () => of([makeTaxRate({ vatStatus: 'STANDARD' })]) });
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    await vi.runAllTimersAsync();

    const available = comp.availableVatStatuses();
    expect(available).not.toContain('STANDARD');
    expect(available).toContain('ZERO_RATED');
    expect(available).toContain('EXEMPT');
  });

  it('allConfigured is true when all three classifications are in rows()', async () => {
    makeBed({
      listImpl: () =>
        of([
          makeTaxRate({ uid: 'TR1', vatStatus: 'STANDARD' }),
          makeTaxRate({ uid: 'TR2', vatStatus: 'ZERO_RATED' }),
          makeTaxRate({ uid: 'TR3', vatStatus: 'EXEMPT' }),
        ]),
    });
    const comp = TestBed.createComponent(TaxRateListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.allConfigured()).toBe(true);
    expect(comp.availableVatStatuses().length).toBe(0);
  });
});
