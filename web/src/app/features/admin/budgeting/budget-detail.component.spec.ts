/**
 * BudgetDetailComponent — deep spec.
 *
 * Covers load/error triad, version lifecycle actions (submit, recall, reject),
 * and the create-version form.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BudgetingService } from './budgeting.service';
import { BudgetDetailComponent } from './budget-detail.component';
import type { BudgetDto, BudgetVersionDto } from './models/budgeting.model';

vi.useFakeTimers();

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeVersion(overrides: Partial<BudgetVersionDto> = {}): BudgetVersionDto {
  return {
    id: '10', uid: 'ver-1', budgetId: '1', budgetUid: 'bud-1',
    companyId: '10', fiscalYearId: 'fy-1', costCentreValueId: null,
    versionNo: '1', status: 'DRAFT', label: 'v1',
    seededFromVersionId: null, seededFromVersionUid: null,
    submittedAt: null, submittedBy: null,
    approvedAt: null, approvedBy: null,
    rejectedAt: null, rejectedBy: null,
    supersededAt: null, decisionReason: null,
    version: '0',
    createdAt: '2024-01-01T00:00:00Z', createdBy: 'admin',
    lines: [],
    ...overrides,
  };
}

function makeBudget(overrides: Partial<BudgetDto> = {}): BudgetDto {
  return {
    id: '1', uid: 'bud-1', companyId: '10', budgetNumber: 'BUD-001',
    name: 'FY2024 Budget', fiscalYearId: 'fy-1', fiscalYearUid: 'fy-uid-1',
    fiscalYearCode: 'FY2024', costCentreValueId: null, costCentreValueUid: null,
    costCentreValueName: null, notes: null, version: '0',
    createdAt: '2024-01-01T00:00:00Z', createdBy: 'admin',
    updatedAt: '2024-01-01T00:00:00Z', updatedBy: 'admin',
    versions: [makeVersion()],
    ...overrides,
  };
}

function makeBed(
  budgetingService: Partial<{
    getByUid: ReturnType<typeof vi.fn>;
    submit: ReturnType<typeof vi.fn>;
    recall: ReturnType<typeof vi.fn>;
    approve: ReturnType<typeof vi.fn>;
    reject: ReturnType<typeof vi.fn>;
    createVersion: ReturnType<typeof vi.fn>;
  }> = {},
  perms: string[] = ['BUDGETING.BUDGET.MANAGE', 'BUDGETING.BUDGET.SUBMIT', 'BUDGETING.BUDGET.APPROVE'],
) {
  const svc = {
    getByUid: vi.fn(() => of(makeBudget())),
    submit: vi.fn(() => of(makeVersion({ status: 'SUBMITTED' }))),
    recall: vi.fn(() => of(makeVersion({ status: 'DRAFT' }))),
    approve: vi.fn(() => of(makeVersion({ status: 'APPROVED' }))),
    reject: vi.fn(() => of(makeVersion({ status: 'REJECTED' }))),
    createVersion: vi.fn(() => of(makeVersion({ uid: 'ver-2', versionNo: '2' }))),
    ...budgetingService,
  };

  TestBed.configureTestingModule({
    imports: [BudgetDetailComponent],
    providers: [
      provideRouter([]),
      { provide: BudgetingService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
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

describe('BudgetDetailComponent — load triad', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('loads the budget and its versions on success', async () => {
    makeBed();
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.budgetState()).toBe('idle');
    expect(comp.budget()?.uid).toBe('bud-1');
    expect(comp.budget()?.versions.length).toBe(1);
  });

  it('sets budgetState = "error" on 500', async () => {
    makeBed({ getByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 }))) });
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.budgetState()).toBe('error');
  });

  it('sets budgetState = "error" on 403', async () => {
    makeBed({ getByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))) });
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.budgetState()).toBe('error');
  });
});

// ── Permissions ───────────────────────────────────────────────────────────────

describe('BudgetDetailComponent — permissions', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('canManage/canSubmit/canApprove are true when all permissions granted', async () => {
    makeBed();
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.canManage()).toBe(true);
    expect(comp.canSubmit()).toBe(true);
    expect(comp.canApprove()).toBe(true);
  });

  it('canApprove is false when BUDGETING.BUDGET.APPROVE is not granted', async () => {
    makeBed({}, ['BUDGETING.BUDGET.MANAGE', 'BUDGETING.BUDGET.SUBMIT']);
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.canApprove()).toBe(false);
  });
});

// ── Submit version action ─────────────────────────────────────────────────────

describe('BudgetDetailComponent — submit version', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('submitVersion() calls budgetingService.submit and updates version status', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    fixture.componentInstance.submitVersion('ver-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.submit).toHaveBeenCalledWith('ver-1');
    const version = fixture.componentInstance.budget()?.versions.find(v => v.uid === 'ver-1');
    expect(version?.status).toBe('SUBMITTED');
    expect(fixture.componentInstance.actionBusyUid()).toBeNull();
  });

  it('submitVersion() sets actionError on failure', async () => {
    makeBed({ submit: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 422 }))) });
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    fixture.componentInstance.submitVersion('ver-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.actionError()).toBeTruthy();
    expect(fixture.componentInstance.actionBusyUid()).toBeNull();
  });

  it('submitVersion() is a no-op when another action is in flight', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.actionBusyUid.set('ver-1'); // simulate in-flight
    comp.submitVersion('ver-1');

    expect(svc.submit).not.toHaveBeenCalled();
  });
});

// ── Recall version action ─────────────────────────────────────────────────────

describe('BudgetDetailComponent — recall version', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('recallVersion() updates version status to DRAFT', async () => {
    const svc = makeBed({
      getByUid: vi.fn(() => of(makeBudget({ versions: [makeVersion({ status: 'SUBMITTED' })] }))),
    });
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    fixture.componentInstance.recallVersion('ver-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.recall).toHaveBeenCalledWith('ver-1');
    const version = fixture.componentInstance.budget()?.versions.find(v => v.uid === 'ver-1');
    expect(version?.status).toBe('DRAFT');
  });
});

// ── Reject version action ─────────────────────────────────────────────────────

describe('BudgetDetailComponent — reject version', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('confirmReject() requires a reason — sets rejectError if blank', async () => {
    makeBed();
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.startReject('ver-1');
    comp.fRejectReason.set('');
    comp.confirmReject();

    expect(comp.rejectError()).toBe('Rejection reason is required.');
  });

  it('confirmReject() calls budgetingService.reject with reason when reason is provided', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.startReject('ver-1');
    comp.fRejectReason.set('Budget too high');
    comp.confirmReject();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.reject).toHaveBeenCalledWith('ver-1', { reason: 'Budget too high' });
    expect(comp.rejectingUid()).toBeNull();
  });

  it('cancelReject() clears rejectingUid', async () => {
    makeBed();
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.startReject('ver-1');
    expect(comp.rejectingUid()).toBe('ver-1');
    comp.cancelReject();
    expect(comp.rejectingUid()).toBeNull();
  });
});

// ── New version form ──────────────────────────────────────────────────────────

describe('BudgetDetailComponent — createVersion', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('createVersion() calls service and reloads budget', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.showNewVersionForm.set(true);
    comp.fVersionLabel.set('v2');
    comp.createVersion();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.createVersion).toHaveBeenCalledOnce();
    // getByUid should have been called twice: once on init, once after createVersion
    expect(svc.getByUid).toHaveBeenCalledTimes(2);
    expect(comp.creatingVersion()).toBe(false);
    expect(comp.showNewVersionForm()).toBe(false);
  });

  it('createVersion() is idempotent while already creating', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(BudgetDetailComponent);
    fixture.componentRef.setInput('uid', 'bud-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.creatingVersion.set(true);
    comp.createVersion();

    expect(svc.createVersion).not.toHaveBeenCalled();
  });
});
