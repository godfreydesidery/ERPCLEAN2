/**
 * Financial Reporting component specs (ADR-0018, FR-REP-01..04).
 *
 * Covers:
 *  1. IncomeStatementComponent — renders without crashing.
 *  2. IncomeStatementComponent — fmtMoney coerces numeric wire values (BigDecimal arrives as number).
 *     Regression guard: a string method call on a number would throw — must not.
 *  3. IncomeStatementComponent — reconciliation badge: ties=true shows "Reconciled".
 *  4. IncomeStatementComponent — reconciliation badge: ties=false shows alarm.
 *  5. BalanceSheetComponent — renders without crashing.
 *  6. BalanceSheetComponent — fmtMoney numeric-money guard (same class of bug as trial-balance).
 *  7. BalanceSheetComponent — reconciliation badge: ties=true shows "Balanced".
 *  8. CashFlowStatementComponent — renders without crashing.
 *  9. CashFlowStatementComponent — fmtMoney numeric-money guard.
 * 10. AccountLedgerComponent — renders without crashing.
 * 11. AccountLedgerComponent — fmtMoney numeric-money guard.
 * 12. AccountLedgerComponent — pre-fills accountUid from query param.
 */

import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { describe, it, expect, afterEach, vi, beforeEach } from 'vitest';

import { IncomeStatementComponent } from './income-statement.component';
import { BalanceSheetComponent } from './balance-sheet.component';
import { CashFlowStatementComponent } from './cash-flow-statement.component';
import { AccountLedgerComponent } from './account-ledger.component';
import { ReportingService } from './reporting.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  AmountPairDto,
  IncomeStatementDto,
  BalanceSheetDto,
  CashFlowStatementDto,
  AccountLedgerDto,
  ReconciliationDto,
  StatementHeaderDto,
} from './models/reporting.model';

// ── Shared helpers ─────────────────────────────────────────────────────────────

const MOCK_ORG = { uid: 'ORG1', id: '1', name: 'Acme Org' };
const MOCK_COMPANY = { uid: 'CO1', id: '10', name: 'Acme Ltd' };

function zeroAmountPair(): AmountPairDto { return { current: 0, comparative: 0 }; }

function mockHeader(): StatementHeaderDto {
  return {
    companyId: '10', companyName: 'Acme Ltd', currency: 'TZS',
    periodLabel: 'Jan 2026', comparativeLabel: 'Dec 2025',
    fromDate: '2026-01-01', toDate: '2026-01-31',
    asAtDate: null, generatedAt: '2026-01-31T12:00:00Z',
  };
}

function tiedReconciliation(label: string): ReconciliationDto {
  return { label, computed: zeroAmountPair(), expected: zeroAmountPair(), difference: zeroAmountPair(), ties: true };
}

function untiedReconciliation(label: string): ReconciliationDto {
  return {
    label,
    computed: { current: 100, comparative: 0 },
    expected: { current: 0, comparative: 0 },
    difference: { current: 100, comparative: 0 },
    ties: false,
  };
}

const MOCK_INCOME_STATEMENT: IncomeStatementDto = {
  header: mockHeader(),
  sections: [
    {
      sectionKey: 'REVENUE', title: 'Revenue',
      lines: [{ accountId: '1', accountUid: 'ACT-001', accountCode: '4100', accountName: 'Sales Revenue', amounts: { current: 50000, comparative: 40000 } }],
      subtotal: { current: 50000, comparative: 40000 },
    },
    {
      sectionKey: 'COST_OF_SALES', title: 'Cost of Sales',
      lines: [{ accountId: '2', accountUid: 'ACT-002', accountCode: '5100', accountName: 'COGS', amounts: { current: 20000, comparative: 15000 } }],
      subtotal: { current: 20000, comparative: 15000 },
    },
    {
      sectionKey: 'OPERATING_EXPENSES', title: 'Operating Expenses',
      lines: [{ accountId: '3', accountUid: 'ACT-003', accountCode: '5200', accountName: 'Rent', amounts: { current: 5000, comparative: 5000 } }],
      subtotal: { current: 5000, comparative: 5000 },
    },
  ],
  grossProfit: { current: 30000, comparative: 25000 },
  netProfit: { current: 25000, comparative: 20000 },
  reconciliation: tiedReconciliation('P&L net == period INCOME − EXPENSE movement'),
};

const MOCK_BALANCE_SHEET: BalanceSheetDto = {
  header: mockHeader(),
  sections: [
    {
      sectionKey: 'CURRENT_ASSETS', title: 'Current Assets',
      lines: [{ accountId: '1', accountUid: 'ACT-001', accountCode: '1000', accountName: 'Cash', amounts: { current: 100000, comparative: 80000 } }],
      subtotal: { current: 100000, comparative: 80000 },
    },
    {
      sectionKey: 'CURRENT_LIABILITIES', title: 'Current Liabilities',
      lines: [{ accountId: '2', accountUid: 'ACT-002', accountCode: '2100', accountName: 'AP', amounts: { current: 40000, comparative: 30000 } }],
      subtotal: { current: 40000, comparative: 30000 },
    },
    {
      sectionKey: 'EQUITY', title: 'Equity',
      lines: [
        { accountId: '3', accountUid: 'ACT-003', accountCode: '3000', accountName: "Owner's Equity", amounts: { current: 35000, comparative: 35000 } },
        { accountId: null, accountUid: null, accountCode: null, accountName: 'Current-year earnings', amounts: { current: 25000, comparative: 15000 } },
      ],
      subtotal: { current: 60000, comparative: 50000 },
    },
  ],
  totalAssets: { current: 100000, comparative: 80000 },
  totalLiabilities: { current: 40000, comparative: 30000 },
  totalEquity: { current: 60000, comparative: 50000 },
  reconciliation: tiedReconciliation('ASSET == LIABILITY + EQUITY'),
};

const MOCK_CASH_FLOW: CashFlowStatementDto = {
  header: mockHeader(),
  sections: [
    {
      sectionKey: 'OPERATING', title: 'Operating Activities',
      lines: [{ accountId: null, accountUid: null, accountCode: null, accountName: 'Net income', amounts: { current: 25000, comparative: 20000 } }],
      subtotal: { current: 25000, comparative: 20000 },
    },
    {
      sectionKey: 'INVESTING', title: 'Investing Activities',
      lines: [],
      subtotal: zeroAmountPair(),
    },
    {
      sectionKey: 'FINANCING', title: 'Financing Activities',
      lines: [],
      subtotal: zeroAmountPair(),
    },
  ],
  netChangeInCash: { current: 25000, comparative: 20000 },
  openingCash: { current: 75000, comparative: 60000 },
  closingCash: { current: 100000, comparative: 80000 },
  reconciliation: tiedReconciliation('CF net change == Cash + Bank GL movement'),
};

const MOCK_LEDGER: AccountLedgerDto = {
  header: mockHeader(),
  accountId: '1', accountUid: 'ACT-001', accountCode: '1000', accountName: 'Cash',
  openingBalance: 75000,
  rows: [
    {
      postingDate: '2026-01-05', sourceType: 'SALES', sourceRef: 'JB-0001',
      entryUid: 'JE-001', lineMemo: 'Cash sale', debit: 50000, credit: 0, runningBalance: 125000,
    },
    {
      postingDate: '2026-01-10', sourceType: 'AP_PAYMENT', sourceRef: 'JB-0002',
      entryUid: 'JE-002', lineMemo: 'Supplier payment', debit: 0, credit: 25000, runningBalance: 100000,
    },
  ],
  closingBalance: 100000,
  page: 0, size: 50, totalElements: 2,
};

function makeSession() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeReportingService() {
  return {
    incomeStatement: vi.fn(() => of(MOCK_INCOME_STATEMENT)),
    exportIncomeStatement: vi.fn(() => of(new Blob())),
    balanceSheet: vi.fn(() => of(MOCK_BALANCE_SHEET)),
    exportBalanceSheet: vi.fn(() => of(new Blob())),
    cashFlow: vi.fn(() => of(MOCK_CASH_FLOW)),
    exportCashFlow: vi.fn(() => of(new Blob())),
    accountLedger: vi.fn(() => of(MOCK_LEDGER)),
    exportAccountLedger: vi.fn(() => of(new Blob())),
  };
}

function makeActivatedRoute(queryParams: Record<string, string> = {}) {
  return {
    snapshot: {
      queryParamMap: {
        get: (key: string) => queryParams[key] ?? null,
      },
    },
  };
}

// ── Income Statement ──────────────────────────────────────────────────────────

describe('IncomeStatementComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  function makeBed() {
    TestBed.configureTestingModule({
      imports: [IncomeStatementComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: '**', redirectTo: '' }]),
        { provide: ReportingService, useValue: makeReportingService() },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of(MOCK_ORG)) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([MOCK_COMPANY])) } },
        { provide: SessionStore, useValue: makeSession() },
      ],
    });
  }

  it('renders without crashing', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(IncomeStatementComponent);
    expect(fixture).toBeTruthy();
  });

  it('fmtMoney coerces numeric wire values — BigDecimal arrives as number (money-type regression guard)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(IncomeStatementComponent).componentInstance as any;
    // BigDecimal serialised as JSON number — must never call .toFixed() on a string
    expect(comp.fmtMoney(50000)).toMatch(/50,000\.00/);
    expect(comp.fmtMoney(0)).toMatch(/0\.00/);
    expect(comp.fmtMoney(null)).toMatch(/0\.00/);
    expect(comp.fmtMoney(undefined)).toMatch(/0\.00/);
    // Regression: must not throw when called with a number
    expect(() => comp.fmtMoney(50000)).not.toThrow();
    // Negative money
    expect(comp.fmtMoney(-1000)).toMatch(/-1,000\.00/);
  });

  it('reconciliation ties=true → ties flag is true on the mock DTO', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(IncomeStatementComponent).componentInstance as any;
    // Simulate a loaded statement
    comp.statement.set(MOCK_INCOME_STATEMENT);
    expect(comp.statement()?.reconciliation.ties).toBe(true);
  });

  it('reconciliation ties=false alarm is accessible via the DTO', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(IncomeStatementComponent).componentInstance as any;
    const unbalanced = { ...MOCK_INCOME_STATEMENT, reconciliation: untiedReconciliation('test') };
    comp.statement.set(unbalanced);
    expect(comp.statement()?.reconciliation.ties).toBe(false);
    expect(comp.statement()?.reconciliation.difference.current).toBe(100);
  });
});

// ── Balance Sheet ─────────────────────────────────────────────────────────────

describe('BalanceSheetComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  function makeBed() {
    TestBed.configureTestingModule({
      imports: [BalanceSheetComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: '**', redirectTo: '' }]),
        { provide: ReportingService, useValue: makeReportingService() },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of(MOCK_ORG)) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([MOCK_COMPANY])) } },
        { provide: SessionStore, useValue: makeSession() },
      ],
    });
  }

  it('renders without crashing', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(BalanceSheetComponent);
    expect(fixture).toBeTruthy();
  });

  it('fmtMoney coerces numeric money — same class of bug as the trial-balance regression', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(BalanceSheetComponent).componentInstance as any;
    expect(comp.fmtMoney(100000)).toMatch(/100,000\.00/);
    expect(comp.fmtMoney(0)).toMatch(/0\.00/);
    expect(comp.fmtMoney(null)).toMatch(/0\.00/);
    expect(() => comp.fmtMoney(100000)).not.toThrow();
  });

  it('reconciliation ties=true flag is accessible', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(BalanceSheetComponent).componentInstance as any;
    comp.statement.set(MOCK_BALANCE_SHEET);
    expect(comp.statement()?.reconciliation.ties).toBe(true);
  });

  it('reconciliation ties=false alarm — difference is non-zero', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(BalanceSheetComponent).componentInstance as any;
    comp.statement.set({ ...MOCK_BALANCE_SHEET, reconciliation: untiedReconciliation('ASSET != LIAB+EQ') });
    expect(comp.statement()?.reconciliation.ties).toBe(false);
    expect(+(comp.statement()?.reconciliation.difference.current ?? 0)).toBeGreaterThan(0);
  });
});

// ── Cash-Flow Statement ───────────────────────────────────────────────────────

describe('CashFlowStatementComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  function makeBed() {
    TestBed.configureTestingModule({
      imports: [CashFlowStatementComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: '**', redirectTo: '' }]),
        { provide: ReportingService, useValue: makeReportingService() },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of(MOCK_ORG)) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([MOCK_COMPANY])) } },
        { provide: SessionStore, useValue: makeSession() },
      ],
    });
  }

  it('renders without crashing', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(CashFlowStatementComponent);
    expect(fixture).toBeTruthy();
  });

  it('fmtMoney numeric-money guard — must not throw on numeric inputs', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CashFlowStatementComponent).componentInstance as any;
    expect(comp.fmtMoney(25000)).toMatch(/25,000\.00/);
    expect(comp.fmtMoney(-5000)).toMatch(/-5,000\.00/);
    expect(() => comp.fmtMoney(25000)).not.toThrow();
  });

  it('reconciliation ties flag and cash open/close accessible on mock DTO', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CashFlowStatementComponent).componentInstance as any;
    comp.statement.set(MOCK_CASH_FLOW);
    expect(comp.statement()?.reconciliation.ties).toBe(true);
    expect(+(comp.statement()?.openingCash.current ?? 0)).toBe(75000);
    expect(+(comp.statement()?.closingCash.current ?? 0)).toBe(100000);
    expect(+(comp.statement()?.netChangeInCash.current ?? 0)).toBe(25000);
  });
});

// ── Account Ledger ────────────────────────────────────────────────────────────

describe('AccountLedgerComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  function makeBed(queryParams: Record<string, string> = {}) {
    TestBed.configureTestingModule({
      imports: [AccountLedgerComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: '**', redirectTo: '' }]),
        { provide: ReportingService, useValue: makeReportingService() },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of(MOCK_ORG)) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([MOCK_COMPANY])) } },
        { provide: SessionStore, useValue: makeSession() },
        { provide: ActivatedRoute, useValue: makeActivatedRoute(queryParams) },
      ],
    });
  }

  it('renders without crashing', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(AccountLedgerComponent);
    expect(fixture).toBeTruthy();
  });

  it('fmtMoney numeric-money guard on ledger row values', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(AccountLedgerComponent).componentInstance as any;
    expect(comp.fmtMoney(75000)).toMatch(/75,000\.00/);
    expect(comp.fmtMoney(0)).toMatch(/0\.00/);
    expect(comp.fmtMoney(null)).toMatch(/0\.00/);
    expect(() => comp.fmtMoney(75000)).not.toThrow();
  });

  it('pre-fills accountUid from query params (drill-in from statement lines)', () => {
    vi.useFakeTimers();
    makeBed({ accountUid: 'ACT-DRILLED', fromDate: '2026-01-01', toDate: '2026-01-31', companyId: '10' });
    const fixture = TestBed.createComponent(AccountLedgerComponent);
    const comp = fixture.componentInstance as any;
    // ngOnInit runs after detectChanges
    fixture.detectChanges();
    expect(comp.accountUid()).toBe('ACT-DRILLED');
    expect(comp.fromDate()).toBe('2026-01-01');
    expect(comp.toDate()).toBe('2026-01-31');
  });

  it('runningBalanceClass returns text-danger for negative balances', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(AccountLedgerComponent).componentInstance as any;
    expect(comp.runningBalanceClass(-100)).toBe('text-danger');
    expect(comp.runningBalanceClass(0)).toBe('');
    expect(comp.runningBalanceClass(500)).toBe('');
  });

  it('hasNextPage is false when totalElements <= pageSize', async () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(AccountLedgerComponent).componentInstance;
    await vi.runAllTimersAsync();
    // MOCK_LEDGER has totalElements=2, pageSize=50
    expect(comp.hasNextPage()).toBe(false);
  });
});
