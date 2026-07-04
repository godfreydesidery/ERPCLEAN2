/**
 * CashCountsListComponent specs (ADR-0050 D-7 PR-A).
 *
 * The backend's listByAccount is TILL-SCOPED (requires companyId + accountId, no pagination) —
 * unlike the other cashbank lists. Covers:
 *  1. Tills are filtered to CASH-type accounts and the first one auto-loads.
 *  2. onTillChange() reloads rows for the newly selected till.
 *  3. isEmpty() is false once rows have loaded.
 *  4. Forbidden state on a 403.
 *  5. canView false hides the list; "New Count" gated on CASH.COUNT.MANAGE.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { CashCountsListComponent } from './cash-counts-list.component';
import { CashbankService } from './cashbank.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SessionStore } from '../../../core/auth/session.store';
import type { CashCountDto } from './models/cashbank.model';

function makeSession(canView = true, canManage = true) {
  return {
    hasPermission: vi.fn((code: string) => (code === 'CASH.COUNT.MANAGE' ? canManage : canView)),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

const MOCK_TILLS = [
  { uid: 'TILL1', id: '1', companyId: '10', code: 'CASH-001', name: 'Main Till', accountType: 'CASH', active: true, isDefault: true, currency: 'TZS', glAccountId: '50', bankName: null, bankAccountNo: null, bankBranch: null, branchId: null },
  { uid: 'TILL2', id: '2', companyId: '10', code: 'CASH-002', name: 'Secondary Till', accountType: 'CASH', active: true, isDefault: false, currency: 'TZS', glAccountId: '52', bankName: null, bankAccountNo: null, bankBranch: null, branchId: null },
  { uid: 'BANK1', id: '3', companyId: '10', code: 'BANK-001', name: 'CRDB Bank', accountType: 'BANK', active: true, isDefault: false, currency: 'TZS', glAccountId: '51', bankName: 'CRDB', bankAccountNo: '0001', bankBranch: 'HQ', branchId: null },
];

function makeCount(uid: string): CashCountDto {
  return {
    id: uid.replace('CC', ''),
    uid,
    companyId: '10',
    cashBankAccountUid: 'TILL1',
    cashBankAccountName: 'CASH-001 — Main Till',
    countNumber: `CC-000${uid.replace('CC', '')}`,
    businessDate: '2026-07-04',
    expectedAmount: 1000,
    countedAmount: 1000,
    varianceAmount: 0,
    currency: 'TZS',
    status: 'RECONCILED',
    journalEntryRef: 'JE1',
    denominations: [],
    countedAt: '2026-07-04T18:00:00Z',
    reconciledAt: '2026-07-04T18:05:00Z',
  };
}

function makeBed(opts: { canView?: boolean; canManage?: boolean; listSpy?: ReturnType<typeof vi.fn> } = {}) {
  const { canView = true, canManage = true, listSpy = vi.fn(() => of([makeCount('CC1')])) } = opts;

  TestBed.configureTestingModule({
    imports: [CashCountsListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: CashbankService,
        useValue: {
          listAllAccounts: vi.fn(() => of(MOCK_TILLS)),
          listCashCounts: listSpy,
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: SessionStore, useValue: makeSession(canView, canManage) },
    ],
  });

  return { listSpy };
}

describe('CashCountsListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('filters tills to CASH-type accounts and auto-selects the first one', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(CashCountsListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.tills().map((t) => t.uid)).toEqual(['TILL1', 'TILL2']);
    expect(comp.selectedTillId()).toBe('1');
    expect(listSpy).toHaveBeenCalledWith('10', '1');
  });

  it('onTillChange() reloads rows for the newly selected till', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(CashCountsListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    listSpy.mockClear();
    comp.onTillChange('2');
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalledWith('10', '2');
  });

  it('isEmpty is false once rows have loaded', async () => {
    makeBed();
    const fixture = TestBed.createComponent(CashCountsListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.rows().length).toBe(1);
    expect(comp.isEmpty()).toBe(false);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is false when no till is selected (distinct from a genuinely empty result set)', async () => {
    makeBed({ listSpy: vi.fn(() => of([])) });
    const fixture = TestBed.createComponent(CashCountsListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // A till IS auto-selected (tills exist) but the till has no counts yet.
    expect(comp.selectedTillId()).toBe('1');
    expect(comp.rows()).toEqual([]);

    // Now simulate the pre-selection state directly to assert the guard clause.
    comp.selectedTillId.set('');
    expect(comp.isEmpty()).toBe(false);
  });

  it('sets forbidden state on a 403', async () => {
    const { listSpy: _unused } = makeBed({
      listSpy: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))),
    });
    const fixture = TestBed.createComponent(CashCountsListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });

  it('canView false when session lacks CASH.COUNT.VIEW', () => {
    makeBed({ canView: false });
    const fixture = TestBed.createComponent(CashCountsListComponent);
    const comp = fixture.componentInstance;
    expect(comp.canView()).toBe(false);
  });

  it('canManage reflects CASH.COUNT.MANAGE independently of CASH.COUNT.VIEW', () => {
    makeBed({ canView: true, canManage: false });
    const fixture = TestBed.createComponent(CashCountsListComponent);
    const comp = fixture.componentInstance;
    expect(comp.canView()).toBe(true);
    expect(comp.canManage()).toBe(false);
  });
});
