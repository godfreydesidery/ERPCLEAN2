/**
 * ApprovalInboxComponent — documentType friendly label (UPR fix).
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { ApprovalsService } from './approvals.service';
import { ApprovalInboxComponent } from './approval-inbox.component';
import type { ApprovalRequestDto } from './models/approvals.model';

vi.useFakeTimers();

const EMPTY_META: PageMeta = { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false };

function makeRequest(overrides: Partial<ApprovalRequestDto> = {}): ApprovalRequestDto {
  return {
    id: '1', uid: 'req-1', companyId: '10', branchId: '100',
    branchName: 'Dar Es Salaam HQ', branchCode: 'DSM-HQ',
    requestNumber: 'APR-000001', documentType: 'PURCHASE_ORDER', documentUid: 'PO-UID-1',
    amount: '50000', currency: 'TZS', status: 'PENDING', autoApproved: false,
    sourcePolicyId: null, sourcePolicyUid: null, summary: null,
    submittedBy: '42', submittedByName: 'Jane Doe', submittedAt: '2026-07-01T10:00:00Z',
    resolvedAt: null, resolvedBy: null, resolvedByName: null, steps: [],
    ...overrides,
  };
}

function makeBed(rows: ApprovalRequestDto[] = [makeRequest()]) {
  const svc = { listInbox: vi.fn(() => of({ rows, meta: EMPTY_META })) };

  TestBed.configureTestingModule({
    imports: [ApprovalInboxComponent],
    providers: [
      provideRouter([]),
      { provide: ApprovalsService, useValue: svc },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => false),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return svc;
}

describe('ApprovalInboxComponent — document type friendly label', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('docTypeLabel() maps PURCHASE_ORDER to "Purchase Order"', () => {
    makeBed();
    const fixture = TestBed.createComponent(ApprovalInboxComponent);
    expect(fixture.componentInstance.docTypeLabel('PURCHASE_ORDER')).toBe('Purchase Order');
  });

  it('renders the friendly label in the Document Type column, not the raw code', async () => {
    makeBed([makeRequest({ documentType: 'PURCHASE_ORDER' })]);
    const fixture = TestBed.createComponent(ApprovalInboxComponent);
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const html: string = fixture.nativeElement.textContent;
    expect(html).toContain('Purchase Order');
  });
});

describe('ApprovalInboxComponent — branch + submitter visibility (GM/procurement/branch-manager UPR)', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('shows the branch name and submitter name, never a bare numeric id', async () => {
    makeBed([
      makeRequest({
        branchName: 'Arusha Branch', branchCode: 'ARS-01',
        submittedBy: '77', submittedByName: 'Amina Hassan',
      }),
    ]);
    const fixture = TestBed.createComponent(ApprovalInboxComponent);
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const html: string = fixture.nativeElement.textContent;
    expect(html).toContain('Arusha Branch');
    expect(html).toContain('ARS-01');
    expect(html).toContain('Amina Hassan');
    expect(html).not.toContain('77');
  });

  it('falls back to an em dash when branch/submitter names are absent', async () => {
    makeBed([makeRequest({ branchName: null, branchCode: null, submittedByName: null })]);
    const fixture = TestBed.createComponent(ApprovalInboxComponent);
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const html: string = fixture.nativeElement.textContent;
    expect(html).toContain('—');
  });

  it('shows the current step\'s friendly role name, not the raw role code, when both are present', async () => {
    makeBed([
      makeRequest({
        steps: [
          {
            id: '1', uid: 'step-1', sequence: '1',
            approverRoleCode: 'PROCUREMENT_MGR', approverRoleName: 'Procurement Manager',
            status: 'PENDING', resolvedBy: null, resolvedAt: null, decisions: [],
          },
        ],
      }),
    ]);
    const fixture = TestBed.createComponent(ApprovalInboxComponent);
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const html: string = fixture.nativeElement.textContent;
    expect(html).toContain('Procurement Manager');
  });
});
