/**
 * PettyCashFundDetailComponent specs (ADR-0050 D-7 PR-B).
 *
 * Covers:
 *  1. New-mode: createDisabled until code + name + currency + company are set.
 *  2. New-mode: createFund() posts the correct CreatePettyCashFundRequest and navigates to the
 *     new fund's detail route.
 *  3. New-mode: createFund() surfaces a friendly error on failure (e.g. duplicate code).
 *  4. Detail-mode: loads an existing fund by uid and its transaction ledger.
 *  5. recordDisabled gating: no amount/date, and false without PETTY_CASH.MANAGE.
 *  6. recordTransaction() posts the correct request, prepends the new txn to the ledger, and
 *     updates the fund's displayed balance from `balanceAfter`.
 *  7. recordTransaction() surfaces a friendly error on failure (e.g. an overdraw rejection).
 *  8. Record-transaction form is hidden in the DOM when the user lacks PETTY_CASH.MANAGE.
 *  9. Forbidden state on a 403 loading the fund.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Route, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PettyCashFundDetailComponent } from './petty-cash-fund-detail.component';
import { CashbankService } from './cashbank.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { UserService } from '../user/user.service';
import { GlService } from '../gl/gl.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import type { PettyCashFundDto, PettyCashTransactionDto } from './models/cashbank.model';

@Component({ template: '', standalone: true })
class StubRouteComponent {}
const STUB_ROUTES: Route[] = [{ path: '**', component: StubRouteComponent }];

function makeSession(canManage = true) {
  return {
    hasPermission: vi.fn(() => canManage),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeFund(overrides: Partial<PettyCashFundDto> = {}): PettyCashFundDto {
  return {
    uid: 'PCF1',
    companyId: '10',
    branchId: '1',
    code: 'PCF-001',
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

function makeTxn(overrides: Partial<PettyCashTransactionDto> = {}): PettyCashTransactionDto {
  return {
    uid: 'PCT1',
    fundUid: 'PCF1',
    txnNumber: 'PC-0001',
    txnType: 'DISBURSEMENT',
    txnDate: '2026-07-04',
    amount: 5000,
    balanceAfter: 95000,
    glAccountUid: null,
    reference: null,
    description: null,
    createdAt: '2026-07-04T09:00:00Z',
    ...overrides,
  };
}

function makeBed(opts: { canManage?: boolean; cashbankOverrides?: Record<string, unknown> } = {}) {
  const { canManage = true, cashbankOverrides = {} } = opts;

  TestBed.configureTestingModule({
    imports: [PettyCashFundDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter(STUB_ROUTES),
      {
        provide: CashbankService,
        useValue: {
          getPettyCashFund: vi.fn(() => of(makeFund())),
          listPettyCashTransactions: vi.fn(() => of([])),
          createPettyCashFund: vi.fn(() => of(makeFund())),
          recordPettyCashTransaction: vi.fn(() => of(makeTxn())),
          ...cashbankOverrides,
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: UserService, useValue: { list: vi.fn(() => of([{ uid: 'U1', displayName: 'Asha K.' }])) } },
      { provide: GlService, useValue: { listAllActiveAccounts: vi.fn(() => of([])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSession(canManage) },
    ],
  });
}

// ── New-mode: create form ──────────────────────────────────────────────────

describe('PettyCashFundDetailComponent — new mode (create form)', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('createDisabled true initially (no code/name)', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isNewMode()).toBe(true);
    expect(comp.createDisabled()).toBe(true);
  });

  it('createFund() posts the correct request and navigates to the new fund', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CashbankService) as any;
    const router = TestBed.inject(Router);
    const navSpy = vi.spyOn(router, 'navigate');
    await vi.runAllTimersAsync();

    comp.newCode.set('PCF-001');
    comp.newName.set('Front Office Petty Cash');
    comp.newFloatAmount.set('100000');
    comp.newCurrency.set('tzs');
    expect(comp.createDisabled()).toBe(false);

    comp.createFund();
    await vi.runAllTimersAsync();

    expect(svc.createPettyCashFund).toHaveBeenCalledWith({
      companyUid: 'CO1',
      code: 'PCF-001',
      name: 'Front Office Petty Cash',
      floatAmount: 100000,
      currency: 'TZS',
    });
    expect(navSpy).toHaveBeenCalledWith(['/admin/petty-cash/funds/uid', 'PCF1'], { replaceUrl: true });
  });

  it('createFund() includes custodianUid when a custodian is selected', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CashbankService) as any;
    await vi.runAllTimersAsync();

    comp.newCode.set('PCF-001');
    comp.newName.set('Front Office Petty Cash');
    comp.newFloatAmount.set('50000');
    comp.newCustodianUid.set('U1');
    comp.createFund();
    await vi.runAllTimersAsync();

    expect(svc.createPettyCashFund).toHaveBeenCalledWith(
      expect.objectContaining({ custodianUid: 'U1' }),
    );
  });

  it('createFund() surfaces a friendly error on failure', async () => {
    vi.useFakeTimers();
    makeBed({
      cashbankOverrides: {
        createPettyCashFund: vi.fn(() =>
          throwError(() => new HttpErrorResponse({ status: 409, error: { errors: ['A fund with this code already exists.'] } })),
        ),
      },
    });
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newCode.set('PCF-001');
    comp.newName.set('Front Office Petty Cash');
    comp.newFloatAmount.set('100000');
    comp.createFund();
    await vi.runAllTimersAsync();

    expect(comp.createError()).toBe('A fund with this code already exists.');
    expect(comp.fund()).toBeNull();
  });
});

// ── Detail-mode load ─────────────────────────────────────────────────────────

describe('PettyCashFundDetailComponent — detail mode load', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads an existing fund and its transaction ledger by uid', async () => {
    vi.useFakeTimers();
    makeBed({
      cashbankOverrides: {
        listPettyCashTransactions: vi.fn(() => of([makeTxn()])),
      },
    });
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    fixture.componentRef.setInput('uid', 'PCF1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('idle');
    expect(comp.fund()?.uid).toBe('PCF1');
    expect(comp.transactions()).toHaveLength(1);
    expect(comp.transactions()[0].uid).toBe('PCT1');
  });

  it('sets forbidden state on a 403', async () => {
    vi.useFakeTimers();
    makeBed({
      cashbankOverrides: {
        getPettyCashFund: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))),
      },
    });
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    fixture.componentRef.setInput('uid', 'PCF1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});

// ── recordTransaction() ───────────────────────────────────────────────────────

describe('PettyCashFundDetailComponent — recordTransaction()', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('recordDisabled true initially (no amount entered)', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    fixture.componentRef.setInput('uid', 'PCF1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.recordDisabled()).toBe(true);
  });

  it('recordDisabled is true without PETTY_CASH.MANAGE even with a valid amount+date', async () => {
    vi.useFakeTimers();
    makeBed({ canManage: false });
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    fixture.componentRef.setInput('uid', 'PCF1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.txnAmount.set('5000');
    expect(comp.recordDisabled()).toBe(true);
  });

  it('allows a NEGATIVE amount for ADJUSTMENT but blocks it for DISBURSEMENT/REPLENISHMENT (signed-adjustment gating)', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    fixture.componentRef.setInput('uid', 'PCF1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.txnType.set('ADJUSTMENT');
    comp.txnAmount.set('-50');
    expect(comp.recordDisabled()).toBe(false); // a downward adjustment is a valid signed delta

    comp.txnType.set('DISBURSEMENT');
    expect(comp.recordDisabled()).toBe(true); // negatives blocked for the magnitude types

    comp.txnType.set('ADJUSTMENT');
    comp.txnAmount.set('0');
    expect(comp.recordDisabled()).toBe(true); // zero adjustment is invalid

    // txnEffect renders a signed balance impact per type
    expect(comp.txnEffect({ txnType: 'DISBURSEMENT', amount: 50 } as never)).toBe(-50);
    expect(comp.txnEffect({ txnType: 'REPLENISHMENT', amount: 50 } as never)).toBe(50);
    expect(comp.txnEffect({ txnType: 'ADJUSTMENT', amount: -50 } as never)).toBe(-50);
  });

  it('posts the correct request, prepends the new txn, and updates the displayed balance', async () => {
    vi.useFakeTimers();
    makeBed({
      cashbankOverrides: {
        recordPettyCashTransaction: vi.fn(() =>
          of(makeTxn({ uid: 'PCT2', txnNumber: 'PC-0002', amount: 20000, balanceAfter: 115000, txnType: 'REPLENISHMENT' })),
        ),
      },
    });
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    fixture.componentRef.setInput('uid', 'PCF1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CashbankService) as any;
    await vi.runAllTimersAsync();

    comp.txnType.set('REPLENISHMENT');
    comp.txnAmount.set('20000');
    comp.txnDate.set('2026-07-04');
    comp.txnReference.set('Bank top-up');
    expect(comp.recordDisabled()).toBe(false);

    comp.recordTransaction();
    await vi.runAllTimersAsync();

    expect(svc.recordPettyCashTransaction).toHaveBeenCalledWith('PCF1', {
      type: 'REPLENISHMENT',
      amount: 20000,
      txnDate: '2026-07-04',
      reference: 'Bank top-up',
    });
    expect(comp.transactions()[0].uid).toBe('PCT2');
    expect(comp.fund()?.balanceAmount).toBe(115000);
    // Form resets after a successful record.
    expect(comp.txnAmount()).toBe('');
  });

  it('surfaces a friendly error when a disbursement would overdraw the fund', async () => {
    vi.useFakeTimers();
    makeBed({
      cashbankOverrides: {
        recordPettyCashTransaction: vi.fn(() =>
          throwError(() => new HttpErrorResponse({ status: 422, error: { errors: ['Disbursement exceeds the available fund balance.'] } })),
        ),
      },
    });
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    fixture.componentRef.setInput('uid', 'PCF1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.txnAmount.set('999999');
    comp.txnDate.set('2026-07-04');
    comp.recordTransaction();
    await vi.runAllTimersAsync();

    expect(comp.recordError()).toBe('Disbursement exceeds the available fund balance.');
    expect(comp.transactions()).toHaveLength(0);
  });
});

// ── DOM: record-transaction form gating ───────────────────────────────────────

describe('PettyCashFundDetailComponent — record-transaction form DOM gating', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('renders the record-transaction form when the user holds PETTY_CASH.MANAGE', async () => {
    vi.useFakeTimers();
    makeBed({ canManage: true });
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    fixture.componentRef.setInput('uid', 'PCF1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some((b) => b.textContent?.includes('Record Transaction'))).toBe(true);
  });

  it('hides the record-transaction form when the user lacks PETTY_CASH.MANAGE', async () => {
    vi.useFakeTimers();
    makeBed({ canManage: false });
    const fixture = TestBed.createComponent(PettyCashFundDetailComponent);
    fixture.componentRef.setInput('uid', 'PCF1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some((b) => b.textContent?.includes('Record Transaction'))).toBe(false);
  });
});
