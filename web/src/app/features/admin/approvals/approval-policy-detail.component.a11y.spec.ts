/**
 * Accessibility gate — ApprovalPolicyDetailComponent.
 *
 * Covers the detail/edit screen pattern (loaded entity + form + action buttons).
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ApprovalsService } from './approvals.service';
import { ApprovalPolicyDetailComponent } from './approval-policy-detail.component';
import { assertA11y } from '../../../../testing/a11y.helper';
import type { ApprovalPolicyDto } from './models/approvals.model';

const samplePolicy = (): ApprovalPolicyDto => ({
  id: '1', uid: 'pol-1', companyId: '10', documentType: 'PURCHASE_ORDER',
  name: 'PO Approval Policy', branchScope: 'COMPANY_WIDE', branchId: null,
  minAmount: '0', maxAmount: null, currency: 'TZS', active: true,
  status: 'ACTIVE', notes: null,
  steps: [{ id: '1', uid: 'step-1', sequence: '1', approverRoleCode: 'FINANCE_MGR' }],
});

function makeBed(getPolicyImpl: () => any) {
  TestBed.configureTestingModule({
    imports: [ApprovalPolicyDetailComponent],
    providers: [
      provideRouter([]),
      {
        provide: ApprovalsService,
        useValue: {
          getPolicyByUid: vi.fn(getPolicyImpl),
          updatePolicy: vi.fn(() => of(samplePolicy())),
          deactivatePolicy: vi.fn(() => of({ ...samplePolicy(), status: 'INACTIVE', active: false })),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal(['APPROVALS.POLICY.MANAGE']),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

describe('ApprovalPolicyDetailComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations when the policy is loaded', async () => {
    makeBed(() => of(samplePolicy()));
    const fixture = TestBed.createComponent(ApprovalPolicyDetailComponent);
    // Provide the required uid input
    fixture.componentRef.setInput('uid', 'pol-1');
    await fixture.whenStable();
    await assertA11y(fixture);
  }, 15_000);
});
