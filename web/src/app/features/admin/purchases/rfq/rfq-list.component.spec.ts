/**
 * RfqListComponent — key behaviour specs.
 *
 * Covers:
 *  1. Loads RFQ list on company selection.
 *  2. isEmpty when no rows returned.
 *  3. 403 → state = 'forbidden'.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { RfqService } from './rfq.service';
import { RfqListComponent } from './rfq-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const STUB_RFQ = {
  uid: 'RFQ1', id: '1', companyId: '10', branchId: '1',
  rfqNumber: 'RFQ-0001', status: 'DRAFT' as const,
  sourceRequisitionUid: null, responseDueDate: null,
  awardedQuoteUid: null, awardedPoUid: null, notes: null,
  sentAt: null, awardedAt: null, cancelledAt: null,
  createdAt: '2024-01-01T00:00:00Z', lines: [], supplierUids: ['SUP1'],
};

const emptyPage = () => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const rfqPage = () => ({
  rows: [STUB_RFQ],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(overrides: { listRfqsSpy?: ReturnType<typeof vi.fn> } = {}) {
  const listRfqsSpy = overrides.listRfqsSpy ?? vi.fn(() => of(rfqPage()));

  TestBed.configureTestingModule({
    imports: [RfqListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: RfqService, useValue: { listRfqs: listRfqsSpy } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
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

  return { listRfqsSpy };
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('RfqListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Loads on company selection ─────────────────────────────────────────

  it('loads RFQs on company selection', async () => {
    const { listRfqsSpy } = makeBed();
    const fixture = TestBed.createComponent(RfqListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(listRfqsSpy).toHaveBeenCalledWith('10', 0, 20);
    expect(comp.rows()).toHaveLength(1);
    expect(comp.rows()[0].rfqNumber).toBe('RFQ-0001');
    expect(comp.state()).toBe('idle');
  });

  // ── 2. isEmpty when no rows ────────────────────────────────────────────────

  it('isEmpty is true when service returns empty list', async () => {
    makeBed({ listRfqsSpy: vi.fn(() => of(emptyPage())) });
    const fixture = TestBed.createComponent(RfqListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isEmpty()).toBe(true);
  });

  // ── 3. 403 → forbidden ────────────────────────────────────────────────────

  it('sets state=forbidden on 403 response', async () => {
    const listRfqsSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
    );
    makeBed({ listRfqsSpy });
    const fixture = TestBed.createComponent(RfqListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
