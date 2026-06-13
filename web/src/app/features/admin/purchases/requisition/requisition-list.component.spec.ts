/**
 * RequisitionListComponent — key behaviour specs.
 *
 * Covers:
 *  1. Loads list on company selection.
 *  2. Status filter change fires a new list request.
 *  3. 403 → state = 'forbidden'.
 *  4. Empty state when list returns no rows.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../../core/auth/session.store';
import { AlertService } from '../../../../core/feedback/alert.service';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PurchaseRequisitionService } from './purchase-requisition.service';
import { RequisitionListComponent } from './requisition-list.component';

// ── Stubs ──────────────────────────────────────────────────────────────────────

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const STUB_REQ = {
  uid: 'REQ1', id: '1', companyId: '10', branchId: '1',
  requisitionNumber: 'PR-0001', status: 'DRAFT',
  requiredByDate: null, costCentreCode: null,
  approvalRequestUid: null, approvalStatus: null,
  convertedToType: null, convertedToUid: null,
  notes: null, submittedAt: null, approvedAt: null,
  rejectedAt: null, convertedAt: null, cancelledAt: null,
  createdAt: null, lines: [],
};

const emptyPage = () => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const reqPage = () => ({
  rows: [STUB_REQ],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(overrides: {
  listSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const listSpy = overrides.listSpy ?? vi.fn(() => of(reqPage()));

  TestBed.configureTestingModule({
    imports: [RequisitionListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PurchaseRequisitionService,
        useValue: { list: listSpy },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of(STUB_ORG)) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([STUB_COMPANY])) },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { listSpy };
}

// ── Specs ──────────────────────────────────────────────────────────────────────

describe('RequisitionListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Loads list ──────────────────────────────────────────────────────────

  it('loads requisitions on company selection', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(RequisitionListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalled();
    expect(comp.rows()).toHaveLength(1);
    expect(comp.state()).toBe('idle');
  });

  // ── 2. Status filter triggers reload ──────────────────────────────────────

  it('fires list request when status filter changes', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(RequisitionListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    const callsBefore = listSpy.mock.calls.length;
    comp.onStatusChange('SUBMITTED');
    await vi.runAllTimersAsync();

    expect(listSpy.mock.calls.length).toBeGreaterThan(callsBefore);
    const lastArgs = listSpy.mock.calls.at(-1) as unknown[];
    expect(lastArgs[1]).toBe('SUBMITTED');
  });

  // ── 3. 403 → forbidden ─────────────────────────────────────────────────────

  it('sets state=forbidden on 403 response', async () => {
    const listSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
    );
    makeBed({ listSpy });
    const fixture = TestBed.createComponent(RequisitionListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });

  // ── 4. Empty state ─────────────────────────────────────────────────────────

  it('isEmpty returns true when list is empty', async () => {
    const listSpy = vi.fn(() => of(emptyPage()));
    makeBed({ listSpy });
    const fixture = TestBed.createComponent(RequisitionListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isEmpty()).toBe(true);
    expect(comp.state()).toBe('idle');
  });
});
