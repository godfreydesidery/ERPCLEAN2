import { HttpErrorResponse } from '@angular/common/http';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ApprovalsService } from './approvals.service';
import { ApprovalPolicyListComponent } from './approval-policy-list.component';

vi.useFakeTimers();

const EMPTY_META: PageMeta = { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false };

function makePolicy(overrides = {}) {
  return {
    id: '1', uid: 'pol-1', companyId: '10', documentType: 'PURCHASE_ORDER',
    name: 'PO Policy', branchScope: 'COMPANY_WIDE' as const, branchId: null,
    minAmount: '0', maxAmount: null, currency: 'TZS', active: true,
    status: 'ACTIVE' as const, notes: null, steps: [],
    ...overrides,
  };
}

describe('ApprovalPolicyListComponent', () => {
  let approvalsService: { listPolicies: ReturnType<typeof vi.fn>; createPolicy: ReturnType<typeof vi.fn> };
  let organisationService: { current: ReturnType<typeof vi.fn> };
  let companyService: { list: ReturnType<typeof vi.fn> };
  let sessionStore: { hasPermission: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    approvalsService = {
      listPolicies: vi.fn(() => of({ rows: [], meta: EMPTY_META })),
      createPolicy: vi.fn(),
    };

    organisationService = {
      current: vi.fn(() => of({ uid: 'org-1', name: 'Test Org' })),
    };

    companyService = {
      list: vi.fn(() => of([{ id: '10', uid: 'co-1', name: 'Test Co' }])),
    };

    sessionStore = { hasPermission: vi.fn(() => false) };

    TestBed.configureTestingModule({
      imports: [ApprovalPolicyListComponent],
      providers: [
        provideRouter([]),
        { provide: ApprovalsService, useValue: approvalsService },
        { provide: OrganisationService, useValue: organisationService },
        { provide: CompanyService, useValue: companyService },
        // BranchService added by uid-picker sweep (loadBranchOptions)
        { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
        { provide: SessionStore, useValue: sessionStore },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    });
  });

  it('loads once and shows empty state', async () => {
    const fixture = TestBed.createComponent(ApprovalPolicyListComponent);
    const comp = fixture.componentInstance;
    vi.runAllTimers();
    await fixture.whenStable();

    expect(approvalsService.listPolicies).toHaveBeenCalledOnce();
    expect(comp.isEmpty()).toBe(true);
  });

  it('populates rows on successful load', async () => {
    approvalsService.listPolicies.mockReturnValue(
      of({ rows: [makePolicy()], meta: { ...EMPTY_META, totalElements: 1 } }),
    );
    const fixture = TestBed.createComponent(ApprovalPolicyListComponent);
    const comp = fixture.componentInstance;
    vi.runAllTimers();
    await fixture.whenStable();

    expect(comp.rows().length).toBe(1);
    expect(comp.isEmpty()).toBe(false);
  });

  it('sets forbidden state on 403', async () => {
    approvalsService.listPolicies.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    );
    const fixture = TestBed.createComponent(ApprovalPolicyListComponent);
    const comp = fixture.componentInstance;
    vi.runAllTimers();
    await fixture.whenStable();

    expect(comp.state()).toBe('forbidden');
  });

  it('sets error state on generic API error', async () => {
    approvalsService.listPolicies.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );
    const fixture = TestBed.createComponent(ApprovalPolicyListComponent);
    const comp = fixture.componentInstance;
    vi.runAllTimers();
    await fixture.whenStable();

    expect(comp.state()).toBe('error');
  });

  it('hides New Policy button when user lacks APPROVALS.POLICY.MANAGE', async () => {
    (sessionStore.hasPermission as ReturnType<typeof vi.fn>).mockReturnValue(false);
    const fixture = TestBed.createComponent(ApprovalPolicyListComponent);
    const comp = fixture.componentInstance;
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.canManage()).toBe(false);
  });

  it('shows New Policy button when user has APPROVALS.POLICY.MANAGE', async () => {
    (sessionStore.hasPermission as ReturnType<typeof vi.fn>).mockImplementation(
      (perm: string) => perm === 'APPROVALS.POLICY.MANAGE',
    );
    const fixture = TestBed.createComponent(ApprovalPolicyListComponent);
    const comp = fixture.componentInstance;
    vi.runAllTimers();
    await fixture.whenStable();

    expect(comp.canManage()).toBe(true);
  });
});
