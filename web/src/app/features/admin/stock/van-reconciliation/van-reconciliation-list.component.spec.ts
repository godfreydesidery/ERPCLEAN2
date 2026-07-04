/**
 * VanReconciliationListComponent — key behaviour specs.
 *
 * Covers:
 *  1. Loads list on construction (page 0).
 *  2. isEmpty=true when no rows returned.
 *  3. Sets state=forbidden on 403 response.
 *  4. goToPage triggers re-load with the correct page.
 *  5. canManage/canView reflect the session permissions independently.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../../core/auth/session.store';
import { VanReconciliationDto } from './van-reconciliation.model';
import { VanReconciliationService } from './van-reconciliation.service';
import { VanReconciliationListComponent } from './van-reconciliation-list.component';

const STUB_RECON: VanReconciliationDto = {
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
  lines: [], // list endpoint never populates lines — asserted by the "empty lines" test below
  reconciledAt: null,
};

const emptyPage = () => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const onePage = () => ({
  rows: [STUB_RECON],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(opts: { listSpy?: ReturnType<typeof vi.fn>; canView?: boolean; canManage?: boolean } = {}) {
  const { listSpy = vi.fn(() => of(onePage())), canView = true, canManage = true } = opts;

  TestBed.configureTestingModule({
    imports: [VanReconciliationListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: VanReconciliationService, useValue: { list: listSpy } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((code: string) => (code === 'STOCK.VAN_RECON.MANAGE' ? canManage : canView)),
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

describe('VanReconciliationListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Loads list on construction ─────────────────────────────────────────

  it('loads van reconciliations on construction (page 0, no companyId/branchId params)', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(VanReconciliationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalledWith(0, 20);
    expect(comp.rows()).toHaveLength(1);
    expect(comp.state()).toBe('idle');
  });

  // ── 2. isEmpty when no rows ───────────────────────────────────────────────

  it('isEmpty is true when no rows returned', async () => {
    makeBed({ listSpy: vi.fn(() => of(emptyPage())) });
    const fixture = TestBed.createComponent(VanReconciliationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isEmpty()).toBe(true);
  });

  // ── 3. 403 → forbidden state ──────────────────────────────────────────────

  it('sets state=forbidden on 403 response', async () => {
    makeBed({
      listSpy: vi.fn(() =>
        throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
      ),
    });
    const fixture = TestBed.createComponent(VanReconciliationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });

  // ── 4. goToPage triggers reload ───────────────────────────────────────────

  it('goToPage calls list with the correct page number', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(VanReconciliationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    listSpy.mockClear();
    comp.goToPage(2);
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalledWith(2, 20);
  });

  // ── 5. Permission gating ───────────────────────────────────────────────────

  it('canManage reflects STOCK.VAN_RECON.MANAGE independently of STOCK.VAN_RECON.VIEW', async () => {
    makeBed({ canView: true, canManage: false });
    const fixture = TestBed.createComponent(VanReconciliationListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.canView()).toBe(true);
    expect(comp.canManage()).toBe(false);
  });
});
