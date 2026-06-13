/**
 * WorkOrderDetailComponent — deep spec.
 *
 * Covers load/error triad, form validation, and the
 * release + issueComponents lifecycle actions.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ManufacturingService } from './manufacturing.service';
import { WorkOrderDetailComponent } from './work-order-detail.component';
import type { WorkOrderDto } from './models/manufacturing.model';

vi.useFakeTimers();

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeWO(overrides: Partial<WorkOrderDto> = {}): WorkOrderDto {
  return {
    id: '1', uid: 'wo-1', woNumber: 'WO-001', companyId: '10', branchId: 'br-1',
    finishedProductId: 'prod-1', finishedProductCode: 'SKU-001',
    finishedProductName: 'Widget A', bomId: '', bomUid: '',
    plannedQty: '100', goodQty: '0', scrapQty: '0',
    status: 'PLANNED', notes: '',
    wipDebitTotal: '0', wipCreditTotal: '0',
    labourAppliedTotal: '0', overheadAppliedTotal: '0',
    computedUnitCost: '0', varianceAmount: '0',
    incompleteCost: false, costCentreValueId: '',
    plannedDate: '2024-06-01', releasedAt: '', completedAt: '',
    closedAt: '', cancelledAt: '',
    createdAt: '2024-01-01T00:00:00Z', createdBy: 'admin',
    components: [], operations: [],
    ...overrides,
  };
}

function makeBed(
  mfgService: Partial<{
    getByUid: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    release: ReturnType<typeof vi.fn>;
    issueComponents: ReturnType<typeof vi.fn>;
    addOperation: ReturnType<typeof vi.fn>;
    removeOperation: ReturnType<typeof vi.fn>;
    complete: ReturnType<typeof vi.fn>;
    close: ReturnType<typeof vi.fn>;
    cancel: ReturnType<typeof vi.fn>;
    applyCost: ReturnType<typeof vi.fn>;
  }> = {},
  perms: string[] = ['WORKORDER.MANAGE', 'WORKORDER.RELEASE', 'WORKORDER.CLOSE'],
) {
  const svc = {
    getByUid: vi.fn(() => of(makeWO())),
    update: vi.fn(() => of(makeWO())),
    release: vi.fn(() => of(makeWO({ status: 'RELEASED' }))),
    issueComponents: vi.fn(() => of(makeWO({ status: 'IN_PROGRESS' }))),
    addOperation: vi.fn(() => of({ uid: 'op-1', seqNo: '1', description: 'Test Op' } as any)),
    removeOperation: vi.fn(() => of(undefined)),
    complete: vi.fn(() => of(makeWO({ status: 'COMPLETED' }))),
    close: vi.fn(() => of(makeWO({ status: 'CLOSED' }))),
    cancel: vi.fn(() => of(makeWO({ status: 'CANCELLED' }))),
    applyCost: vi.fn(() => of(makeWO())),
    ...mfgService,
  };

  TestBed.configureTestingModule({
    imports: [WorkOrderDetailComponent],
    providers: [
      provideRouter([]),
      { provide: ManufacturingService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      // Stubs for services added by loadBranchOptions (resolves org→company→branches)
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'org-1' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((p: string) => perms.includes(p)),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal(perms),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return svc;
}

// ── Load triad ────────────────────────────────────────────────────────────────

describe('WorkOrderDetailComponent — load triad', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('loads the work order and patches form on success', async () => {
    makeBed();
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.woState()).toBe('idle');
    expect(comp.wo()?.uid).toBe('wo-1');
    expect(comp.fPlannedQty()).toBe('100');
  });

  it('sets woState = "error" on 500', async () => {
    makeBed({ getByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 }))) });
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.woState()).toBe('error');
  });

  it('sets woState = "error" on 403', async () => {
    makeBed({ getByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))) });
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.woState()).toBe('error');
  });
});

// ── Form validation (PLANNED save) ───────────────────────────────────────────

describe('WorkOrderDetailComponent — save validation', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('sets saveError when plannedQty is zero', async () => {
    makeBed();
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fPlannedQty.set('0');
    comp.save();

    expect(comp.saveError()).toMatch(/positive number/);
  });

  it('sets saveError when plannedQty is non-numeric', async () => {
    makeBed();
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fPlannedQty.set('abc');
    comp.save();

    expect(comp.saveError()).toMatch(/positive number/);
  });

  it('calls mfgService.update on valid save', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    fixture.componentInstance.save();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.update).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.saving()).toBe(false);
  });
});

// ── Permission-based computed properties ──────────────────────────────────────

describe('WorkOrderDetailComponent — permissions / canXxx computeds', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('canReleaseAction is true for PLANNED WO with WORKORDER.RELEASE', async () => {
    makeBed();
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.canReleaseAction()).toBe(true);
  });

  it('canEdit is true for PLANNED WO with WORKORDER.MANAGE', async () => {
    makeBed();
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.canEdit()).toBe(true);
  });

  it('canEdit is false for RELEASED WO (only PLANNED is editable)', async () => {
    makeBed({ getByUid: vi.fn(() => of(makeWO({ status: 'RELEASED' }))) });
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.canEdit()).toBe(false);
  });

  it('canManage is false when user lacks WORKORDER.MANAGE', async () => {
    makeBed({}, ['WORKORDER.RELEASE']);
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.canManage()).toBe(false);
  });
});

// ── Release lifecycle action ──────────────────────────────────────────────────

describe('WorkOrderDetailComponent — release action', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('release() calls mfgService.release and updates status to RELEASED', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    fixture.componentInstance.release();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.release).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.wo()?.status).toBe('RELEASED');
    expect(fixture.componentInstance.releasing()).toBe(false);
  });

  it('release() sets releaseError on failure', async () => {
    makeBed({ release: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 409 }))) });
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    fixture.componentInstance.release();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.releaseError()).toBeTruthy();
    expect(fixture.componentInstance.releasing()).toBe(false);
  });
});

// ── Issue components lifecycle action ────────────────────────────────────────

describe('WorkOrderDetailComponent — issueComponents action', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('sets issueError when posting date is blank', async () => {
    makeBed({ getByUid: vi.fn(() => of(makeWO({ status: 'RELEASED' }))) });
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.issuePostingDate.set('');
    comp.issueComponents();

    expect(comp.issueError()).toBe('Posting date is required.');
  });

  it('issueComponents() calls service and transitions status to IN_PROGRESS', async () => {
    const svc = makeBed({ getByUid: vi.fn(() => of(makeWO({ status: 'RELEASED' }))) });
    const fixture = TestBed.createComponent(WorkOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'wo-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.issuePostingDate.set('2024-06-15');
    comp.issueComponents();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.issueComponents).toHaveBeenCalledOnce();
    expect(comp.wo()?.status).toBe('IN_PROGRESS');
    expect(comp.issuing()).toBe(false);
  });
});
