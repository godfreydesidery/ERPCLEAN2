/**
 * PettyCashFundsListComponent specs (ADR-0050 D-7 PR-B).
 *
 * Covers:
 *  1. Funds load for the auto-selected first company.
 *  2. onCompanyChange() reloads rows for the newly selected company.
 *  3. isEmpty() is false once rows have loaded, true when the result set is genuinely empty.
 *  4. Forbidden state on a 403.
 *  5. canView false hides the list; "New Fund" gated on PETTY_CASH.MANAGE.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PettyCashFundsListComponent } from './petty-cash-funds-list.component';
import { CashbankService } from './cashbank.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SessionStore } from '../../../core/auth/session.store';
import type { PettyCashFundDto } from './models/cashbank.model';

function makeSession(canView = true, canManage = true) {
  return {
    hasPermission: vi.fn((code: string) => (code === 'PETTY_CASH.MANAGE' ? canManage : canView)),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeFund(uid: string, overrides: Partial<PettyCashFundDto> = {}): PettyCashFundDto {
  return {
    uid,
    companyId: '10',
    branchId: '1',
    code: `PCF-000${uid.replace('PCF', '')}`,
    name: 'Front Office Petty Cash',
    custodianUid: null,
    custodianName: null,
    floatAmount: 100000,
    balanceAmount: 100000,
    currency: 'TZS',
    status: 'ACTIVE',
    version: '0',
    createdAt: '2026-07-04T08:00:00Z',
    ...overrides,
  };
}

function makeBed(opts: { canView?: boolean; canManage?: boolean; listSpy?: ReturnType<typeof vi.fn> } = {}) {
  const { canView = true, canManage = true, listSpy = vi.fn(() => of([makeFund('PCF1')])) } = opts;

  TestBed.configureTestingModule({
    imports: [PettyCashFundsListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: CashbankService, useValue: { listPettyCashFunds: listSpy } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: SessionStore, useValue: makeSession(canView, canManage) },
    ],
  });

  return { listSpy };
}

describe('PettyCashFundsListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads funds for the auto-selected first company', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(PettyCashFundsListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.selectedCompanyId()).toBe('10');
    expect(listSpy).toHaveBeenCalledWith('10');
    expect(comp.rows().map((r) => r.uid)).toEqual(['PCF1']);
  });

  it('onCompanyChange() reloads rows for the newly selected company', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(PettyCashFundsListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    listSpy.mockClear();
    listSpy.mockReturnValue(of([makeFund('PCF2')]));
    comp.onCompanyChange('20');
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalledWith('20');
    expect(comp.rows().map((r) => r.uid)).toEqual(['PCF2']);
  });

  it('isEmpty is false once rows have loaded', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PettyCashFundsListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.rows().length).toBe(1);
    expect(comp.isEmpty()).toBe(false);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true for a genuinely empty result set', async () => {
    makeBed({ listSpy: vi.fn(() => of([])) });
    const fixture = TestBed.createComponent(PettyCashFundsListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.rows()).toEqual([]);
    expect(comp.isEmpty()).toBe(true);
  });

  it('sets forbidden state on a 403', async () => {
    makeBed({ listSpy: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))) });
    const fixture = TestBed.createComponent(PettyCashFundsListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });

  it('canView false when session lacks PETTY_CASH.VIEW', () => {
    makeBed({ canView: false });
    const fixture = TestBed.createComponent(PettyCashFundsListComponent);
    const comp = fixture.componentInstance;
    expect(comp.canView()).toBe(false);
  });

  it('canManage reflects PETTY_CASH.MANAGE independently of PETTY_CASH.VIEW', () => {
    makeBed({ canView: true, canManage: false });
    const fixture = TestBed.createComponent(PettyCashFundsListComponent);
    const comp = fixture.componentInstance;
    expect(comp.canView()).toBe(true);
    expect(comp.canManage()).toBe(false);
  });

  it('does not render the "New Fund" link when the user lacks PETTY_CASH.MANAGE', async () => {
    makeBed({ canManage: false });
    const fixture = TestBed.createComponent(PettyCashFundsListComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const links = Array.from(fixture.nativeElement.querySelectorAll('a')) as HTMLAnchorElement[];
    expect(links.some((a) => a.textContent?.includes('New Fund'))).toBe(false);
  });

  it('renders the "New Fund" link when the user holds PETTY_CASH.MANAGE', async () => {
    makeBed({ canManage: true });
    const fixture = TestBed.createComponent(PettyCashFundsListComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const links = Array.from(fixture.nativeElement.querySelectorAll('a')) as HTMLAnchorElement[];
    expect(links.some((a) => a.textContent?.includes('New Fund'))).toBe(true);
  });
});
