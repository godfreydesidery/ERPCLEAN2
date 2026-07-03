/**
 * TrialBalanceComponent spec.
 *
 * Covers:
 *  1. Renders rows when trial balance data loads.
 *  2. isBalanced is true when totalDebits === totalCredits.
 *  3. isBalanced is false when totals differ.
 *  4. sortedRows groups and sorts by accountType order (ASSET first, then LIABILITY…).
 *  5. groupedTypes returns only types that have rows.
 *  6. 403 response sets state to 'forbidden'.
 *  7. State goes to 'idle' and tb() is populated on success.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { GlService } from './gl.service';
import type { TrialBalanceDto } from './models/gl.model';
import { TrialBalanceComponent } from './trial-balance.component';

// ── Helpers ────────────────────────────────────────────────────────────────────

const balancedTb = (): TrialBalanceDto => ({
  rows: [
    { accountCode: '1001', accountName: 'Cash', accountType: 'ASSET', totalDebit: '1000.00', totalCredit: '0.00', net: '1000.00' },
    { accountCode: '4001', accountName: 'Revenue', accountType: 'INCOME', totalDebit: '0.00', totalCredit: '1000.00', net: '-1000.00' },
  ],
  totalDebits: '1000.00',
  totalCredits: '1000.00',
});

const unbalancedTb = (): TrialBalanceDto => ({
  rows: [
    { accountCode: '1001', accountName: 'Cash', accountType: 'ASSET', totalDebit: '1000.00', totalCredit: '0.00', net: '1000.00' },
    { accountCode: '4001', accountName: 'Revenue', accountType: 'INCOME', totalDebit: '0.00', totalCredit: '500.00', net: '-500.00' },
  ],
  totalDebits: '1000.00',
  totalCredits: '500.00',
});

function makeSessionStore(canView = true) {
  return {
    hasPermission: vi.fn(() => canView),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { tbImpl?: () => any; canView?: boolean } = {}) {
  const { tbImpl, canView = true } = opts;

  TestBed.configureTestingModule({
    imports: [TrialBalanceComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: GlService,
        useValue: {
          getTrialBalance: vi.fn(tbImpl ?? (() => of(balancedTb()))),
          getTrialBalanceForPeriod: vi.fn(() => of(balancedTb())),
          listPeriods: vi.fn(() => of([])),
        },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      { provide: SessionStore, useValue: makeSessionStore(canView) },
    ],
  });
}

// ── Rendering ──────────────────────────────────────────────────────────────────

describe('TrialBalanceComponent — render', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('tb() is populated and state is idle after load', async () => {
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('idle');
    expect(comp.tb()).not.toBeNull();
    expect(comp.tb()!.rows).toHaveLength(2);
  });

  it('sortedRows contains both accounts', async () => {
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.sortedRows()).toHaveLength(2);
  });

  it('ASSET row comes before INCOME row in sortedRows', async () => {
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();

    const types = comp.sortedRows().map((r) => r.accountType);
    expect(types[0]).toBe('ASSET');
    expect(types[1]).toBe('INCOME');
  });

  it('groupedTypes returns only types that have rows', async () => {
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();

    const types = comp.groupedTypes();
    expect(types).toContain('ASSET');
    expect(types).toContain('INCOME');
    expect(types).not.toContain('LIABILITY');
    expect(types).not.toContain('EQUITY');
    expect(types).not.toContain('EXPENSE');
  });
});

// ── Regression: money fields arrive as NUMBERS on the wire (BigDecimal → JSON number),
//    not strings. The template must not assume strings (row.net.startsWith crashed live,
//    blanking the per-row amounts — see ISSUES-REGISTER). Render the template with numeric
//    money and assert it renders the rows without throwing.
describe('TrialBalanceComponent — numeric money (wire reality) renders', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('renders account rows when net/debit/credit are numbers, not strings', async () => {
    vi.useFakeTimers();
    const numericTb = (): any => ({
      rows: [
        { accountCode: '1000', accountName: 'Cash', accountType: 'ASSET', totalDebit: 50000, totalCredit: 0, net: 50000 },
        { accountCode: '4100', accountName: 'Sales Revenue', accountType: 'INCOME', totalDebit: 0, totalCredit: 50000, net: -50000 },
      ],
      totalDebits: 50000,
      totalCredits: 50000,
    });
    makeBed({ tbImpl: () => of(numericTb()) });

    const fixture = TestBed.createComponent(TrialBalanceComponent);
    await vi.runAllTimersAsync();
    // Would throw "row.net.startsWith is not a function" before the fix.
    expect(() => fixture.detectChanges()).not.toThrow();

    const text = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('Cash');
    expect(text).toContain('Sales Revenue');
    // Rendered via the shared formatMoney util — thousand-separated, 2dp.
    expect(text).toContain('50,000.00');
  });
});

// ── Money formatting: thousand-separators via the shared formatMoney util ──────

describe('TrialBalanceComponent — money formatting', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fmtMoney renders thousand-separated, 2dp amounts (shared util, not toFixed)', async () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    expect(comp.fmtMoney(2221486)).toBe('2,221,486.00');
    expect(comp.fmtMoney('2221486.00')).toBe('2,221,486.00');
    expect(comp.fmtMoney(0)).toBe('0.00');
    expect(comp.fmtMoney(null)).toBe('0.00');
  });

  it('renders a thousand-separated total in the footer for a large balance', async () => {
    vi.useFakeTimers();
    const largeTb = (): any => ({
      rows: [
        { accountCode: '1000', accountName: 'Cash', accountType: 'ASSET', totalDebit: 2221486, totalCredit: 0, net: 2221486 },
        { accountCode: '4100', accountName: 'Sales Revenue', accountType: 'INCOME', totalDebit: 0, totalCredit: 2221486, net: -2221486 },
      ],
      totalDebits: 2221486,
      totalCredits: 2221486,
    });
    makeBed({ tbImpl: () => of(largeTb()) });

    const fixture = TestBed.createComponent(TrialBalanceComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('2,221,486.00');
  });
});

// ── Balanced indicator ─────────────────────────────────────────────────────────

describe('TrialBalanceComponent — balanced indicator', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('isBalanced is true when totalDebits equals totalCredits', async () => {
    vi.useFakeTimers();
    makeBed({ tbImpl: () => of(balancedTb()) });
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isBalanced()).toBe(true);
  });

  it('isBalanced is false when totalDebits differs from totalCredits', async () => {
    vi.useFakeTimers();
    makeBed({ tbImpl: () => of(unbalancedTb()) });
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isBalanced()).toBe(false);
  });

  it('totalDebits and totalCredits reflect the server values', async () => {
    vi.useFakeTimers();
    makeBed({ tbImpl: () => of(balancedTb()) });
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.totalDebits()).toBe('1000.00');
    expect(comp.totalCredits()).toBe('1000.00');
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('TrialBalanceComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets state to forbidden when getTrialBalance returns 403', async () => {
    makeBed({
      tbImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
