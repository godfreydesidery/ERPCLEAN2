/**
 * ApprovalRequestDetailComponent — documentType friendly label + Document link.
 *
 * UPR fix: Document Type was shown as a raw enum code, and the "Document" link
 * routed to /admin/documents/uid/{uid} (the generated-PDF log) instead of the real
 * business document. This spec locks in: friendly label rendering, and the Document
 * link resolving to the correct business-document route for a known type
 * (PURCHASE_ORDER — the only type currently wired end-to-end, PoApprovalGate), plus
 * the honest no-link fallback for an unmapped type.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ApprovalsService } from './approvals.service';
import { ApprovalRequestDetailComponent } from './approval-request-detail.component';
import type { ApprovalRequestDto } from './models/approvals.model';

vi.useFakeTimers();

function makeRequest(overrides: Partial<ApprovalRequestDto> = {}): ApprovalRequestDto {
  return {
    id: '1',
    uid: 'req-1',
    companyId: '10',
    branchId: '100',
    branchName: 'Dar Es Salaam HQ',
    branchCode: 'DSM-HQ',
    requestNumber: 'APR-000001',
    documentType: 'PURCHASE_ORDER',
    documentUid: 'PO-UID-1',
    amount: '50000',
    currency: 'TZS',
    status: 'PENDING',
    autoApproved: false,
    sourcePolicyId: null,
    sourcePolicyUid: null,
    summary: null,
    submittedBy: '42',
    submittedByName: 'Jane Doe',
    submittedAt: '2026-07-01T10:00:00Z',
    resolvedAt: null,
    resolvedBy: null,
    resolvedByName: null,
    steps: [],
    ...overrides,
  };
}

function makeBed(request: ApprovalRequestDto = makeRequest()) {
  const svc = {
    getRequestByUid: vi.fn(() => of(request)),
    approveRequest: vi.fn(() => of(request)),
    rejectRequest: vi.fn(() => of(request)),
    recallRequest: vi.fn(() => of(request)),
    cancelRequest: vi.fn(() => of(request)),
  };

  TestBed.configureTestingModule({
    imports: [ApprovalRequestDetailComponent],
    providers: [
      provideRouter([]),
      { provide: ApprovalsService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
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

describe('ApprovalRequestDetailComponent — document type label + link', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('maps PURCHASE_ORDER to the friendly label "Purchase Order"', async () => {
    makeBed(makeRequest({ documentType: 'PURCHASE_ORDER' }));
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.docTypeLabel()).toBe('Purchase Order');
  });

  it('resolves the Document link to /admin/purchase-orders/uid/{documentUid} for PURCHASE_ORDER', async () => {
    makeBed(makeRequest({ documentType: 'PURCHASE_ORDER', documentUid: 'PO-UID-1' }));
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.docLink()).toEqual(['/admin/purchase-orders/uid', 'PO-UID-1']);
  });

  it('renders the Document link with routerLink pointing at the purchase order, not the documents log', async () => {
    makeBed(makeRequest({ documentType: 'PURCHASE_ORDER', documentUid: 'PO-UID-1' }));
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const anchors: HTMLAnchorElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('a'),
    );
    const docLink = anchors.find((a) => a.textContent?.includes('Purchase Order'));
    expect(docLink).toBeTruthy();
    expect(docLink?.getAttribute('href')).toBe('/admin/purchase-orders/uid/PO-UID-1');
    expect(docLink?.getAttribute('href')).not.toContain('/admin/documents/uid');
  });

  it('maps SALES_ORDER to its friendly label and route', async () => {
    makeBed(makeRequest({ documentType: 'SALES_ORDER', documentUid: 'SO-UID-1' }));
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.docTypeLabel()).toBe('Sales Order');
    expect(comp.docLink()).toEqual(['/admin/sales-orders/uid', 'SO-UID-1']);
  });

  it('falls back to the raw code with no link for an unmapped documentType (no unrelated-screen link)', async () => {
    makeBed(makeRequest({ documentType: 'SOMETHING_NEW', documentUid: 'X-1' }));
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.docTypeLabel()).toBe('SOMETHING_NEW');
    expect(comp.docLink()).toBeNull();

    const anchors: HTMLAnchorElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('a'),
    );
    expect(anchors.some((a) => a.getAttribute('href')?.includes('/admin/documents/uid'))).toBe(false);
  });
});

describe('ApprovalRequestDetailComponent — submitter/decider/branch + step role name (GM/procurement/branch-manager UPR)', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('shows Submitted By, Decided By and Branch as friendly names, never a bare numeric id', async () => {
    makeBed(
      makeRequest({
        status: 'APPROVED',
        branchName: 'Arusha Branch',
        branchCode: 'ARS-01',
        submittedBy: '77',
        submittedByName: 'Amina Hassan',
        resolvedAt: '2026-07-02T09:00:00Z',
        resolvedBy: '88',
        resolvedByName: 'Godfrey Desidery',
      }),
    );
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const html: string = fixture.nativeElement.textContent;
    expect(html).toContain('Arusha Branch');
    expect(html).toContain('ARS-01');
    expect(html).toContain('Amina Hassan');
    expect(html).toContain('Godfrey Desidery');
    expect(html).not.toContain('77');
    expect(html).not.toContain('88');
  });

  it('does not show a Decided By field while the request is still pending', async () => {
    makeBed(makeRequest({ status: 'PENDING', resolvedBy: null, resolvedByName: null, resolvedAt: null }));
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const html: string = fixture.nativeElement.textContent;
    expect(html).not.toContain('Decided By');
  });

  it('falls back to an em dash when submitter/branch names are absent', async () => {
    makeBed(makeRequest({ submittedByName: null, branchName: null, branchCode: null }));
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const html: string = fixture.nativeElement.textContent;
    expect(html).toContain('—');
  });

  it('shows the step\'s friendly approverRoleName as the primary label, with the raw code as secondary text', async () => {
    makeBed(
      makeRequest({
        steps: [
          {
            id: '1', uid: 'step-1', sequence: '1',
            approverRoleCode: 'PROCUREMENT_MGR', approverRoleName: 'Procurement Manager',
            status: 'PENDING', resolvedBy: null, resolvedAt: null, decisions: [],
          },
        ],
      }),
    );
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const html: string = fixture.nativeElement.textContent;
    expect(html).toContain('Procurement Manager');
    expect(html).toContain('PROCUREMENT_MGR');
  });

  it('falls back to the raw approverRoleCode when approverRoleName is not resolvable', async () => {
    makeBed(
      makeRequest({
        steps: [
          {
            id: '1', uid: 'step-1', sequence: '1',
            approverRoleCode: 'SOME_ROLE', approverRoleName: null,
            status: 'PENDING', resolvedBy: null, resolvedAt: null, decisions: [],
          },
        ],
      }),
    );
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const html: string = fixture.nativeElement.textContent;
    expect(html).toContain('SOME_ROLE');
  });

  it('shows decidedByName directly in the decision history, never a raw numeric id', async () => {
    makeBed(
      makeRequest({
        submittedBy: '42',
        submittedByName: 'Jane Doe',
        resolvedBy: '88',
        resolvedByName: 'Godfrey Desidery',
        steps: [
          {
            id: '1', uid: 'step-1', sequence: '1',
            approverRoleCode: 'PROCUREMENT_MGR', approverRoleName: 'Procurement Manager',
            status: 'APPROVED', resolvedBy: '88', resolvedAt: '2026-07-02T09:00:00Z',
            decisions: [
              {
                id: 'd1', uid: 'dec-1', approvalRequestStepId: '1',
                action: 'APPROVE', decidedBy: '999', decidedByName: null,
                decidedAt: '2026-07-02T08:55:00Z', comment: null,
              },
              {
                id: 'd2', uid: 'dec-2', approvalRequestStepId: '1',
                action: 'APPROVE', decidedBy: '88', decidedByName: 'Godfrey Desidery',
                decidedAt: '2026-07-02T09:00:00Z', comment: null,
              },
            ],
          },
        ],
      }),
    );
    const fixture = TestBed.createComponent(ApprovalRequestDetailComponent);
    fixture.componentRef.setInput('uid', 'req-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    // stepResolvedByName looks up the decision matching the step's resolvedBy — not a
    // request-level submitter/resolver guess.
    expect(comp.stepResolvedByName(comp.request()!.steps[0])).toBe('Godfrey Desidery');

    const html: string = fixture.nativeElement.textContent;
    expect(html).toContain('Godfrey Desidery');
    // Decision with no resolvable name renders the em-dash fallback, never the raw id.
    expect(html).not.toContain('999');
  });
});
