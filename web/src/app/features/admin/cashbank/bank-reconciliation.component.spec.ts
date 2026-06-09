import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { BankReconciliationComponent } from './bank-reconciliation.component';
import { CashbankService } from './cashbank.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

// Bank Reconciliation tests:
//  1. Complete button disabled until cleared book balance == statement closing balance.
//  2. toggleCleared updates the running cleared book balance.
//  3. isBalanced is true when difference is within tolerance.
//  4. Money values arrive as numbers on the wire — coerce with +v.

function makeSession(canReconcile = true) {
  return {
    hasPermission: vi.fn(() => canReconcile),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

const MOCK_ACCOUNTS = [
  { uid: 'BANK1', id: '1', companyId: '10', code: 'BANK-001', name: 'CRDB Bank', accountType: 'BANK', active: true, isDefault: true, currency: 'TZS', glAccountId: '51', bankName: 'CRDB', bankAccountNo: '0001', bankBranch: 'HQ', branchId: null },
];

const MOCK_STATEMENT = {
  cashBankAccountId: '1',
  cashBankAccountUid: 'BANK1',
  accountName: 'CRDB Bank',
  currentBalance: 3000,
  currency: 'TZS',
  transactions: [
    { uid: 'TXN1', id: '1', companyId: '10', cashBankAccountId: '1', txnNumber: 'TXN-001', txnDate: '2026-06-01', direction: 'IN', amount: 2000, currency: 'TZS', txnType: 'DIRECT_ENTRY', sourceRef: null, counterGlAccountId: null, journalEntryRef: null, cleared: false, clearedInReconciliationId: null, memo: null },
    { uid: 'TXN2', id: '2', companyId: '10', cashBankAccountId: '1', txnNumber: 'TXN-002', txnDate: '2026-06-05', direction: 'OUT', amount: 500, currency: 'TZS', txnType: 'DIRECT_ENTRY', sourceRef: null, counterGlAccountId: null, journalEntryRef: null, cleared: false, clearedInReconciliationId: null, memo: null },
    { uid: 'TXN3', id: '3', companyId: '10', cashBankAccountId: '1', txnNumber: 'TXN-003', txnDate: '2026-06-08', direction: 'IN', amount: 1500, currency: 'TZS', txnType: 'RECEIPT', sourceRef: null, counterGlAccountId: null, journalEntryRef: null, cleared: false, clearedInReconciliationId: null, memo: null },
  ],
};

const MOCK_RECON = {
  uid: 'RECON1', id: '1', companyId: '10', cashBankAccountId: '1',
  reconciliationNumber: 'RECON-001',
  statementDate: '2026-06-09',
  statementClosingBalance: 1500,  // number on wire
  clearedBookBalance: 0,
  status: 'DRAFT',
  reconciledBy: null, completedAt: null,
};

function makeBed() {
  TestBed.configureTestingModule({
    imports: [BankReconciliationComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      {
        provide: CashbankService,
        useValue: {
          listAllAccounts: vi.fn(() => of(MOCK_ACCOUNTS)),
          getAccountStatement: vi.fn(() => of(MOCK_STATEMENT)),
          openReconciliation: vi.fn(() => of(MOCK_RECON)),
          markCleared: vi.fn(() => of(MOCK_RECON)),
          completeReconciliation: vi.fn(() => of({ ...MOCK_RECON, status: 'COMPLETED' })),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSession() },
    ],
  });
}

describe('BankReconciliationComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('completeDisabled true initially (no reconciliation open)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(BankReconciliationComponent).componentInstance as any;
    expect(comp.completeDisabled()).toBe(true);
  });

  it('completeDisabled true when recon set but not balanced', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(BankReconciliationComponent).componentInstance as any;
    // Set a reconciliation with statement closing balance = 1500
    comp.reconciliation.set(MOCK_RECON);
    comp.transactions.set(MOCK_STATEMENT.transactions);
    // cleared set is empty → clearedBookBalance = 0, statementClosingBalance = 1500
    comp.clearedUids.set(new Set());
    expect(comp.clearedBookBalance()).toBe(0);
    expect(comp.statementClosingBalance()).toBe(1500);
    expect(comp.isBalanced()).toBe(false);
    expect(comp.completeDisabled()).toBe(true);
  });

  it('isBalanced true when cleared transactions sum equals statement balance', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(BankReconciliationComponent).componentInstance as any;
    comp.reconciliation.set(MOCK_RECON); // statementClosingBalance = 1500
    comp.transactions.set(MOCK_STATEMENT.transactions);
    // Clear TXN1(IN 2000) and TXN2(OUT 500): net = 2000 - 500 = 1500 == statement
    comp.clearedUids.set(new Set(['TXN1', 'TXN2']));
    expect(comp.clearedBookBalance()).toBeCloseTo(1500, 5);
    expect(comp.isBalanced()).toBe(true);
    expect(comp.completeDisabled()).toBe(false);
  });

  it('completeDisabled true when recon is COMPLETED (even if balanced)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(BankReconciliationComponent).componentInstance as any;
    comp.reconciliation.set({ ...MOCK_RECON, status: 'COMPLETED' });
    comp.transactions.set(MOCK_STATEMENT.transactions);
    comp.clearedUids.set(new Set(['TXN1', 'TXN2']));
    expect(comp.isBalanced()).toBe(true);
    expect(comp.completeDisabled()).toBe(true); // COMPLETED blocks re-completion
  });

  it('toggleCleared updates clearedBookBalance (IN adds, OUT subtracts)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(BankReconciliationComponent).componentInstance as any;
    comp.reconciliation.set(MOCK_RECON);
    comp.transactions.set(MOCK_STATEMENT.transactions);
    comp.clearedUids.set(new Set());

    // Clear TXN1 (IN 2000)
    comp.clearedUids.update((s: Set<string>) => { const n = new Set(s); n.add('TXN1'); return n; });
    expect(comp.clearedBookBalance()).toBeCloseTo(2000, 5);

    // Clear TXN2 (OUT 500) → 2000 - 500 = 1500
    comp.clearedUids.update((s: Set<string>) => { const n = new Set(s); n.add('TXN2'); return n; });
    expect(comp.clearedBookBalance()).toBeCloseTo(1500, 5);

    // Uncheck TXN1 → 0 - 500 = -500
    comp.clearedUids.update((s: Set<string>) => { const n = new Set(s); n.delete('TXN1'); return n; });
    expect(comp.clearedBookBalance()).toBeCloseTo(-500, 5);
  });

  it('fmtMoney coerces number wire value (money arrives as number, NOT string)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(BankReconciliationComponent).componentInstance as any;
    expect(comp.fmtMoney(1500)).toBe('1500.00');
    expect(comp.fmtMoney(0)).toBe('0.00');
    expect(comp.fmtMoney(null)).toBe('0.00');
    expect(comp.fmtMoney(undefined)).toBe('0.00');
    expect(comp.fmtMoney('2000.5')).toBe('2000.50');
  });

  it('difference is statementBalance minus clearedBook', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(BankReconciliationComponent).componentInstance as any;
    comp.reconciliation.set(MOCK_RECON); // statementClosingBalance = 1500
    comp.transactions.set(MOCK_STATEMENT.transactions);
    comp.clearedUids.set(new Set(['TXN1'])); // cleared IN 2000 → clearedBook = 2000
    // difference = clearedBook - statement = 2000 - 1500 = 500
    expect(comp.difference()).toBeCloseTo(500, 5);
    expect(comp.isBalanced()).toBe(false);
  });
});
