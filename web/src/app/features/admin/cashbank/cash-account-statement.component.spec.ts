import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { CashAccountStatementComponent } from './cash-account-statement.component';
import { CashbankService } from './cashbank.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SessionStore } from '../../../core/auth/session.store';

// Cash Account Statement tests:
//  1. Component renders (isEmpty initially).
//  2. Money coerced as numbers (wire sends numbers — NEVER .startsWith/.trim on money).
//  3. Running balance computed correctly.
//  4. glReconciled true when difference is within tolerance.

const MOCK_STATEMENT = {
  cashBankAccountId: '1',
  cashBankAccountUid: 'ACC1',
  accountName: 'Main Cash',
  currentBalance: 1500,   // arrives as number
  currency: 'TZS',
  transactions: [
    { uid: 'T1', id: '1', companyId: '10', cashBankAccountId: '1', txnNumber: 'T-001', txnDate: '2026-06-01', direction: 'IN', amount: 2000, currency: 'TZS', txnType: 'DIRECT_ENTRY', sourceRef: null, counterGlAccountId: null, journalEntryRef: null, cleared: false, clearedInReconciliationId: null, memo: 'Opening' },
    { uid: 'T2', id: '2', companyId: '10', cashBankAccountId: '1', txnNumber: 'T-002', txnDate: '2026-06-05', direction: 'OUT', amount: 500, currency: 'TZS', txnType: 'PAYMENT', sourceRef: null, counterGlAccountId: null, journalEntryRef: null, cleared: false, clearedInReconciliationId: null, memo: null },
  ],
};

const MOCK_GL_RECON_SYNCED = {
  cashBankAccountUid: 'ACC1',
  accountName: 'Main Cash',
  linkedGlAccountId: '50',
  linkedGlAccountCode: '1100',
  bookBalance: 1500,
  linkedGlBalance: 1500,
  difference: 0,   // arrives as number
};

const MOCK_GL_RECON_DIFF = {
  ...MOCK_GL_RECON_SYNCED,
  linkedGlBalance: 1400,
  difference: 100,
};

function makeSession(canView = true) {
  return {
    hasPermission: vi.fn(() => canView),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(glRecon = MOCK_GL_RECON_SYNCED) {
  TestBed.configureTestingModule({
    imports: [CashAccountStatementComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      {
        provide: CashbankService,
        useValue: {
          listAllAccounts: vi.fn(() => of([])),
          getAccountStatement: vi.fn(() => of(MOCK_STATEMENT)),
          getGlReconciliation: vi.fn(() => of(glRecon)),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: SessionStore, useValue: makeSession() },
    ],
  });
}

describe('CashAccountStatementComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('isEmpty true initially before any account selected', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CashAccountStatementComponent).componentInstance as any;
    expect(comp.isEmpty()).toBe(true);
  });

  it('currentBalance coerces number from statement (wire sends number)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CashAccountStatementComponent).componentInstance as any;
    comp.statement.set(MOCK_STATEMENT);
    // currentBalance arrives as number 1500 — must NOT call .startsWith/.trim
    expect(comp.currentBalance()).toBe(1500);
  });

  it('fmtMoney coerces number wire value correctly', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CashAccountStatementComponent).componentInstance as any;
    expect(comp.fmtMoney(1500)).toBe('1500.00');
    expect(comp.fmtMoney(0)).toBe('0.00');
    expect(comp.fmtMoney(null)).toBe('0.00');
    expect(comp.fmtMoney(undefined)).toBe('0.00');
    expect(comp.fmtMoney('999.5')).toBe('999.50');
  });

  it('txnsWithRunning computes running balance: IN adds, OUT subtracts', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CashAccountStatementComponent).componentInstance as any;
    comp.statement.set(MOCK_STATEMENT);
    const rows = comp.txnsWithRunning();
    // T1: IN 2000 → running = 2000
    expect(rows[0].runningBalance).toBeCloseTo(2000, 5);
    // T2: OUT 500 → running = 2000 - 500 = 1500
    expect(rows[1].runningBalance).toBeCloseTo(1500, 5);
  });

  it('glReconciled true when difference is 0', () => {
    vi.useFakeTimers();
    makeBed(MOCK_GL_RECON_SYNCED);
    const comp = TestBed.createComponent(CashAccountStatementComponent).componentInstance as any;
    comp.glRecon.set(MOCK_GL_RECON_SYNCED);
    expect(comp.glDifference()).toBeCloseTo(0, 5);
    expect(comp.glReconciled()).toBe(true);
  });

  it('glReconciled false when difference is non-zero', () => {
    vi.useFakeTimers();
    makeBed(MOCK_GL_RECON_DIFF);
    const comp = TestBed.createComponent(CashAccountStatementComponent).componentInstance as any;
    comp.glRecon.set(MOCK_GL_RECON_DIFF);
    expect(comp.glDifference()).toBeCloseTo(100, 5);
    expect(comp.glReconciled()).toBe(false);
  });

  it('glReconciled false when no glRecon loaded', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CashAccountStatementComponent).componentInstance as any;
    // glRecon is null by default
    expect(comp.glReconciled()).toBe(false);
  });
});
