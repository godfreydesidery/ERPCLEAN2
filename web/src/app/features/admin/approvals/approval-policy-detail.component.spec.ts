/**
 * ApprovalPolicyDetailComponent — deep spec.
 *
 * Covers the load/empty/403 triad + form validation + deactivate lifecycle action.
 * Mirrors the pattern used in approval-policy-list.component.spec.ts but for the
 * detail/edit screen.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ApprovalsService } from './approvals.service';
import { ApprovalPolicyDetailComponent } from './approval-policy-detail.component';
import type { ApprovalPolicyDto } from './models/approvals.model';

vi.useFakeTimers();

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makePolicy(overrides: Partial<ApprovalPolicyDto> = {}): ApprovalPolicyDto {
  return {
    id: '1', uid: 'pol-1', companyId: '10', documentType: 'PURCHASE_ORDER',
    name: 'PO Policy', branchScope: 'COMPANY_WIDE', branchId: null,
    minAmount: '0', maxAmount: null, currency: 'TZS', active: true,
    status: 'ACTIVE', notes: null,
    steps: [{ id: 's1', uid: 'step-1', sequence: '1', approverRoleCode: 'FINANCE_MGR' }],
    ...overrides,
  };
}

// ── Test-bed factory ──────────────────────────────────────────────────────────

function makeBed(
  approvalsService: Partial<{
    getPolicyByUid: ReturnType<typeof vi.fn>;
    updatePolicy: ReturnType<typeof vi.fn>;
    deactivatePolicy: ReturnType<typeof vi.fn>;
  }> = {},
  canManage = true,
) {
  const svc = {
    getPolicyByUid: vi.fn(() => of(makePolicy())),
    updatePolicy: vi.fn(() => of(makePolicy())),
    deactivatePolicy: vi.fn(() => of(makePolicy({ status: 'INACTIVE', active: false }))),
    ...approvalsService,
  };

  TestBed.configureTestingModule({
    imports: [ApprovalPolicyDetailComponent],
    providers: [
      provideRouter([]),
      { provide: ApprovalsService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((p: string) => canManage && p === 'APPROVALS.POLICY.MANAGE'),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal(canManage ? ['APPROVALS.POLICY.MANAGE'] : []),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  // Override the template to avoid NG01352 from the @for ngModel loop:
  // the template uses [attr.name] which Angular Forms doesn't recognise as a
  // named control; we only test component logic here, not the DOM.
  TestBed.overrideTemplate(ApprovalPolicyDetailComponent, '<div></div>');
  return svc;
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('ApprovalPolicyDetailComponent — load triad', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('loads the policy and patches form fields on success', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.policyState()).toBe('idle');
    expect(comp.policy()?.uid).toBe('pol-1');
    expect(comp.fName()).toBe('PO Policy');
    expect(comp.fDocumentType()).toBe('PURCHASE_ORDER');
    expect(comp.fSteps().length).toBe(1);
  });

  it('sets policyState = "error" on any non-403 API failure', async () => {
    makeBed({
      getPolicyByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 }))),
    });
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.policyState()).toBe('error');
  });

  it('sets policyState = "error" on 403 (component maps all errors to error state)', async () => {
    makeBed({
      getPolicyByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))),
    });
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.policyState()).toBe('error');
  });
});

describe('ApprovalPolicyDetailComponent — form validation', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('sets saveError when document type is blank', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fDocumentType.set('');
    comp.save();

    expect(comp.saveError()).toBe('Document type is required.');
  });

  it('sets saveError when name is blank', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fName.set('');
    comp.save();

    expect(comp.saveError()).toBe('Policy name is required.');
  });

  it('sets saveError when steps list is empty', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fSteps.set([]);
    comp.save();

    expect(comp.saveError()).toBe('At least one approval step is required.');
  });

  it('sets saveError when a step has an empty approverRoleCode', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fSteps.set([{ sequence: '1', approverRoleCode: '' }]);
    comp.save();

    expect(comp.saveError()).toMatch(/Step 1/);
  });

  it('sets saveError when branchScope=BRANCH and branchUid is blank', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fBranchScope.set('BRANCH');
    comp.fBranchUid.set('');
    comp.save();

    expect(comp.saveError()).toMatch(/Branch UID/);
  });
});

describe('ApprovalPolicyDetailComponent — steps management', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('addStep appends a new step with the next sequence number', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.fSteps().length).toBe(1);
    comp.addStep();
    expect(comp.fSteps().length).toBe(2);
    expect(comp.fSteps()[1].sequence).toBe('2');
  });

  it('removeStep removes the step and re-sequences', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.addStep(); // now 2 steps
    comp.removeStep(0); // remove first
    expect(comp.fSteps().length).toBe(1);
    expect(comp.fSteps()[0].sequence).toBe('1'); // re-sequenced
  });
});

describe('ApprovalPolicyDetailComponent — deactivate lifecycle action', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('isDeactivatable is true when policy is ACTIVE and user canManage', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.isDeactivatable()).toBe(true);
  });

  it('isDeactivatable is false when policy is INACTIVE', async () => {
    makeBed({
      getPolicyByUid: vi.fn(() => of(makePolicy({ status: 'INACTIVE', active: false }))),
    });
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.isDeactivatable()).toBe(false);
  });

  it('isDeactivatable is false when user lacks APPROVALS.POLICY.MANAGE', async () => {
    makeBed({}, /* canManage */ false);
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.isDeactivatable()).toBe(false);
  });

  it('deactivate() calls approvalsService.deactivatePolicy and updates policy signal', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.deactivate();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.deactivatePolicy).toHaveBeenCalledOnce();
    expect(comp.policy()?.status).toBe('INACTIVE');
    expect(comp.deactivating()).toBe(false);
  });

  it('deactivate() sets deactivateError on API failure', async () => {
    makeBed({
      deactivatePolicy: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 409 }))),
    });
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    fixture.componentRef.setInput('uid', 'pol-1');
    vi.runAllTimers();
    await fixture.whenStable();

    fixture.componentInstance.deactivate();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.deactivateError()).toBeTruthy();
    expect(fixture.componentInstance.deactivating()).toBe(false);
  });
});
