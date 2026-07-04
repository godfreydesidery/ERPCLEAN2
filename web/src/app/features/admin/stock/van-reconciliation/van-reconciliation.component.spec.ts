/**
 * VanReconciliationComponent specs (ADR-0051 D-8).
 *
 * Covers:
 *  1. New-mode: createDisabled until a VAN location + business date are set.
 *  2. New-mode: createReconciliation() posts the correct request and navigates to the detail route.
 *  3. Detail-mode: loads an existing reconciliation and seeds the lines grid from its DTO.
 *  4. Lines grid: expectedQty = loaded − sold − returned, live on every keystroke.
 *  5. Lines grid: varianceQty = physical − expected; tone over/short/balanced/not-counted.
 *  6. editable() is false once RECONCILED/CANCELLED, and false without STOCK.VAN_RECON.MANAGE.
 *  7. saveLines() sends only lines with a physical count entered.
 *  8. reconcile()/cancel() only call the service when status is OPEN.
 *  9. Reconcile/Cancel buttons render in the DOM only when OPEN and the user can manage.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Route, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { VanReconciliationComponent } from './van-reconciliation.component';
import { VanReconciliationService } from './van-reconciliation.service';
import { CompanyService } from '../../company/company.service';
import { BranchService } from '../../branch/branch.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { StockLocationService } from '../locations/stock-location.service';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import type { VanReconciliationDto } from './van-reconciliation.model';

@Component({ template: '', standalone: true })
class StubRouteComponent {}
const STUB_ROUTES: Route[] = [{ path: '**', component: StubRouteComponent }];

function makeSession(canManage = true) {
  return {
    hasPermission: vi.fn(() => canManage),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const STUB_BRANCH = { id: '5', uid: 'BR1', companyId: '10', companyUid: 'CO1', code: 'HQ', name: 'HQ Branch', timeZone: 'UTC', isDefault: true, status: 'ACTIVE' };
const STUB_VAN_LOCATION = { id: '20', uid: 'LOC-VAN', companyId: '10', branchId: '5', code: 'VAN-01', name: 'Van 01', locationType: 'VAN' as const, isDefault: false, status: 'ACTIVE' as const, agentUid: 'AGT1', agentName: 'Hamisi' };
const STUB_WAREHOUSE_LOCATION = { ...STUB_VAN_LOCATION, id: '21', uid: 'LOC-WH', code: 'WH-01', name: 'Main Warehouse', locationType: 'WAREHOUSE' as const, agentUid: null, agentName: null };

function makeRecon(overrides: Partial<VanReconciliationDto> = {}): VanReconciliationDto {
  return {
    id: '1',
    uid: 'VR1',
    companyId: '10',
    branchId: '5',
    vanLocationUid: 'LOC-VAN',
    vanLocationName: 'Van 01',
    agentUid: 'AGT1',
    agentName: 'Hamisi',
    reconNumber: 'VR-0001',
    businessDate: '2026-07-04',
    status: 'OPEN',
    notes: null,
    lines: [
      { id: '101', lineNo: 1, productUid: 'P1', productName: 'Widget', unitCostAmount: 10, loadedQty: 100, soldQty: 0, returnedQty: 25, expectedQty: 75, physicalQty: null, varianceQty: 0, varianceValue: null },
      { id: '102', lineNo: 2, productUid: 'P2', productName: 'Gadget', unitCostAmount: 5, loadedQty: 50, soldQty: 10, returnedQty: 5, expectedQty: 35, physicalQty: null, varianceQty: 0, varianceValue: null },
    ],
    reconciledAt: null,
    ...overrides,
  };
}

function makeBed(opts: { canManage?: boolean; reconOverrides?: Record<string, unknown> } = {}) {
  const { canManage = true, reconOverrides = {} } = opts;

  TestBed.configureTestingModule({
    imports: [VanReconciliationComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter(STUB_ROUTES),
      {
        provide: VanReconciliationService,
        useValue: {
          create: vi.fn(() => of(makeRecon())),
          enterLines: vi.fn(() => of(makeRecon({ lines: makeRecon().lines.map((l) => ({ ...l, soldQty: 70, physicalQty: 5, expectedQty: 5, varianceQty: 0 })) }))),
          reconcile: vi.fn(() => of(makeRecon({ status: 'RECONCILED', reconciledAt: '2026-07-04T18:00:00Z' }))),
          cancel: vi.fn(() => of(makeRecon({ status: 'CANCELLED' }))),
          get: vi.fn(() => of(makeRecon())),
          ...reconOverrides,
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([STUB_BRANCH])) } },
      {
        provide: StockLocationService,
        useValue: { activeForBranch: vi.fn(() => of([STUB_VAN_LOCATION, STUB_WAREHOUSE_LOCATION])) },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSession(canManage) },
    ],
  });
}

// ── New-mode: create form ───────────────────────────────────────────────────

describe('VanReconciliationComponent — new mode (create form)', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('createDisabled true initially (no van location selected)', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isNewMode()).toBe(true);
    expect(comp.createDisabled()).toBe(true);
  });

  it('van location options are filtered to VAN-type locations only', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.vanLocationOptions().map((o) => o.uid)).toEqual(['LOC-VAN']);
    // Agent info surfaced in the hint when available.
    expect(comp.vanLocationOptions()[0].hint).toContain('Hamisi');
  });

  it('createReconciliation() posts the correct request and navigates to the detail route', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(VanReconciliationService) as any;
    const router = TestBed.inject(Router);
    const navSpy = vi.spyOn(router, 'navigate');
    await vi.runAllTimersAsync();

    comp.fVanLocationUid.set('LOC-VAN');
    comp.fBusinessDate.set('2026-07-04');
    expect(comp.createDisabled()).toBe(false);

    comp.createReconciliation();
    await vi.runAllTimersAsync();

    expect(svc.create).toHaveBeenCalledWith({ vanLocationUid: 'LOC-VAN', businessDate: '2026-07-04' });
    expect(comp.entity()?.uid).toBe('VR1');
    expect(navSpy).toHaveBeenCalledWith(['/admin/van-reconciliations/uid', 'VR1'], { replaceUrl: true });
  });

  it('createReconciliation() surfaces a friendly error on failure', async () => {
    vi.useFakeTimers();
    makeBed({
      reconOverrides: {
        create: vi.fn(() =>
          throwError(() => new HttpErrorResponse({ status: 422, error: { errors: ['A reconciliation already exists for this van and date.'] } })),
        ),
      },
    });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.fVanLocationUid.set('LOC-VAN');
    comp.fBusinessDate.set('2026-07-04');
    comp.createReconciliation();
    await vi.runAllTimersAsync();

    expect(comp.createError()).toBe('A reconciliation already exists for this van and date.');
    expect(comp.entity()).toBeNull();
  });
});

// ── Lines grid: expected + variance live compute ────────────────────────────

describe('VanReconciliationComponent — lines grid computes expected + variance', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('expectedQty = loaded − sold − returned, live on every keystroke', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // P1: loaded=100, sold=0 (server default), returned=25 → expected=75
    let p1 = comp.computedRows().find((r) => r.productUid === 'P1')!;
    expect(p1.expectedQty).toBe(75);

    // Agent corrects sold to 70 → expected recomputes to 5
    comp.setSold('P1', '70');
    p1 = comp.computedRows().find((r) => r.productUid === 'P1')!;
    expect(p1.expectedQty).toBe(5);

    // Agent overrides the derived returned qty
    comp.setReturned('P1', '20');
    p1 = comp.computedRows().find((r) => r.productUid === 'P1')!;
    expect(p1.expectedQty).toBe(10); // 100 - 70 - 20
  });

  it('varianceQty = physical − expected; tone is "ok" when physical exceeds expected (over)', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // P1: expected = 75 (100-0-25). physical=80 → variance=+5 (over)
    comp.setPhysical('P1', '80');
    const p1 = comp.computedRows().find((r) => r.productUid === 'P1')!;
    expect(p1.varianceQty).toBe(5);
    expect(p1.tone).toBe('ok');
    expect(p1.label).toBe('Over');
  });

  it('tone is "danger" (short) when physical is below expected', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // P2: expected = 35 (50-10-5). physical=30 → variance=-5 (short)
    comp.setPhysical('P2', '30');
    const p2 = comp.computedRows().find((r) => r.productUid === 'P2')!;
    expect(p2.varianceQty).toBe(-5);
    expect(p2.tone).toBe('danger');
    expect(p2.label).toBe('Short');
  });

  it('tone is "neutral" (balanced) when physical exactly matches expected', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.setPhysical('P1', '75'); // matches expected exactly
    const p1 = comp.computedRows().find((r) => r.productUid === 'P1')!;
    expect(p1.varianceQty).toBe(0);
    expect(p1.tone).toBe('neutral');
    expect(p1.label).toBe('Balanced');
  });

  it('a line with no physical count entered reports "Not counted" and a null variance', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    const p1 = comp.computedRows().find((r) => r.productUid === 'P1')!;
    expect(p1.physicalQty).toBeNull();
    expect(p1.varianceQty).toBeNull();
    expect(p1.tone).toBe('neutral');
    expect(p1.label).toBe('Not counted');
  });

  it('totals() sums loaded/sold/returned/expected/physical/variance across all lines', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.setPhysical('P1', '80'); // variance +5
    comp.setPhysical('P2', '30'); // variance -5

    const totals = comp.totals();
    expect(totals.loadedQty).toBe(150); // 100 + 50
    expect(totals.expectedQty).toBe(110); // 75 + 35
    expect(totals.physicalQty).toBe(110); // 80 + 30
    expect(totals.varianceQty).toBe(0); // +5 + -5
  });
});

// ── editable() gating ────────────────────────────────────────────────────────

describe('VanReconciliationComponent — editable() gating', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('editable is true for an OPEN reconciliation when the user can manage', async () => {
    vi.useFakeTimers();
    makeBed({ canManage: true });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.editable()).toBe(true);
  });

  it('editable is false once RECONCILED', async () => {
    vi.useFakeTimers();
    makeBed({ reconOverrides: { get: vi.fn(() => of(makeRecon({ status: 'RECONCILED' }))) } });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.editable()).toBe(false);
  });

  it('editable is false once CANCELLED', async () => {
    vi.useFakeTimers();
    makeBed({ reconOverrides: { get: vi.fn(() => of(makeRecon({ status: 'CANCELLED' }))) } });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.editable()).toBe(false);
  });

  it('editable is false without STOCK.VAN_RECON.MANAGE, even for an OPEN reconciliation', async () => {
    vi.useFakeTimers();
    makeBed({ canManage: false });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.canManage()).toBe(false);
    expect(comp.editable()).toBe(false);
  });
});

// ── Detail-mode load + seeding ────────────────────────────────────────────────

describe('VanReconciliationComponent — detail mode load', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads an existing reconciliation by uid and seeds the grid from its lines', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('idle');
    expect(comp.loadedOf('P1')).toBe('100');
    expect(comp.soldOf('P1')).toBe('0');
    expect(comp.returnedOf('P1')).toBe('25');
    expect(comp.physicalOf('P1')).toBe(''); // never counted — blank, not '0'
  });

  it('sets forbidden state on a 403', async () => {
    vi.useFakeTimers();
    makeBed({ reconOverrides: { get: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))) } });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});

// ── saveLines() ────────────────────────────────────────────────────────────────

describe('VanReconciliationComponent — saveLines()', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sends only the lines the agent edited this session', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(VanReconciliationService) as any;
    await vi.runAllTimersAsync();

    comp.setSold('P1', '70');
    comp.setPhysical('P1', '5');
    // P2 untouched — its server-seeded sold must NOT be resent (enterLines is a patch).
    comp.saveLines();
    await vi.runAllTimersAsync();

    expect(svc.enterLines).toHaveBeenCalledWith('VR1', {
      lines: [{ productUid: 'P1', soldQty: 70, physicalQty: 5, loadedQty: 100, returnedQty: 25 }],
    });
    expect(comp.saveError()).toBeNull();
  });

  it('persists a sold-only line with physicalQty null (physical count not yet done)', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(VanReconciliationService) as any;
    await vi.runAllTimersAsync();

    comp.setSold('P1', '40'); // sold entered, physical left blank — must NOT be dropped
    comp.saveLines();
    await vi.runAllTimersAsync();

    expect(svc.enterLines).toHaveBeenCalledWith('VR1', {
      lines: [{ productUid: 'P1', soldQty: 40, physicalQty: null, loadedQty: 100, returnedQty: 25 }],
    });
    expect(comp.saveError()).toBeNull();
  });

  it('sets a validation error and does not call the service when no line has been edited', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(VanReconciliationService) as any;
    await vi.runAllTimersAsync();

    comp.saveLines();

    expect(comp.saveError()).toBeTruthy();
    expect(svc.enterLines).not.toHaveBeenCalled();
  });

  it('does not call the service when not editable (already RECONCILED)', async () => {
    vi.useFakeTimers();
    makeBed({ reconOverrides: { get: vi.fn(() => of(makeRecon({ status: 'RECONCILED' }))) } });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(VanReconciliationService) as any;
    await vi.runAllTimersAsync();

    comp.setPhysical('P1', '75');
    comp.saveLines();
    expect(svc.enterLines).not.toHaveBeenCalled();
  });
});

// ── reconcile() / cancel() ───────────────────────────────────────────────────

describe('VanReconciliationComponent — reconcile() and cancel()', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('reconcile() does nothing when status is not OPEN', async () => {
    vi.useFakeTimers();
    makeBed({ reconOverrides: { get: vi.fn(() => of(makeRecon({ status: 'CANCELLED' }))) } });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(VanReconciliationService) as any;
    await vi.runAllTimersAsync();

    comp.reconcile();
    expect(svc.reconcile).not.toHaveBeenCalled();
  });

  it('reconcile() posts the action and updates the entity when status is OPEN', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(VanReconciliationService) as any;
    await vi.runAllTimersAsync();

    comp.reconcile();
    await vi.runAllTimersAsync();

    expect(svc.reconcile).toHaveBeenCalledWith('VR1');
    expect(comp.entity()?.status).toBe('RECONCILED');
  });

  it('cancel() does nothing when status is not OPEN', async () => {
    vi.useFakeTimers();
    makeBed({ reconOverrides: { get: vi.fn(() => of(makeRecon({ status: 'RECONCILED' }))) } });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(VanReconciliationService) as any;
    await vi.runAllTimersAsync();

    comp.cancel();
    expect(svc.cancel).not.toHaveBeenCalled();
  });

  it('cancel() posts the action and updates the entity when status is OPEN', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(VanReconciliationService) as any;
    await vi.runAllTimersAsync();

    comp.cancel();
    await vi.runAllTimersAsync();

    expect(svc.cancel).toHaveBeenCalledWith('VR1');
    expect(comp.entity()?.status).toBe('CANCELLED');
  });
});

// ── DOM: Reconcile/Cancel button visibility ─────────────────────────────────

describe('VanReconciliationComponent — Reconcile/Cancel DOM visibility', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('renders Reconcile and Cancel buttons while status is OPEN and the user can manage', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some((b) => b.textContent?.includes('Reconcile'))).toBe(true);
    expect(buttons.some((b) => b.textContent?.trim() === 'Cancel')).toBe(true);
  });

  it('does not render Reconcile/Cancel once RECONCILED', async () => {
    vi.useFakeTimers();
    makeBed({ reconOverrides: { get: vi.fn(() => of(makeRecon({ status: 'RECONCILED' }))) } });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some((b) => b.textContent?.includes('Reconcile'))).toBe(false);
    expect(buttons.some((b) => b.textContent?.trim() === 'Cancel')).toBe(false);
  });

  it('does not render Reconcile/Cancel when the user lacks STOCK.VAN_RECON.MANAGE', async () => {
    vi.useFakeTimers();
    makeBed({ canManage: false });
    const fixture = TestBed.createComponent(VanReconciliationComponent);
    fixture.componentRef.setInput('uid', 'VR1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some((b) => b.textContent?.includes('Reconcile'))).toBe(false);
    expect(buttons.some((b) => b.textContent?.trim() === 'Cancel')).toBe(false);
  });
});
