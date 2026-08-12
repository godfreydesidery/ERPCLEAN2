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
 *  8. Export (UAT: /gl/trial-balance/export was a 404 — the one statement that could not be
 *     printed): the buttons are offered only to a holder of REPORT.EXPORT, export() asks for the
 *     same company + period the screen is showing, and the period filter sends periodId (the uid
 *     bound nothing server-side and 400'd).
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
import type { FiscalPeriodDto, TrialBalanceDto } from './models/gl.model';
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

/** `has` may be a flat answer or a per-code predicate (GL.VIEW granted, REPORT.EXPORT denied). */
function makeSessionStore(has: boolean | ((code: string) => boolean) = true) {
  const answer = typeof has === 'function' ? has : () => has;
  return {
    hasPermission: vi.fn((code: string) => answer(code)),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

const PERIODS: FiscalPeriodDto[] = [
  {
    id: '77', uid: 'PER-77', companyId: '10', periodNo: 3,
    startDate: '2026-03-01', endDate: '2026-03-31', status: 'OPEN',
  },
];

function makeBed(
  opts: {
    // `any` throughout: these stubs deliberately return off-contract shapes (numeric money,
    // HttpErrorResponse) to reproduce what the wire actually sends.
    tbImpl?: () => any;
    canView?: boolean | ((code: string) => boolean);
    periods?: FiscalPeriodDto[];
  } = {},
) {
  const { tbImpl, canView = true, periods = [] } = opts;

  const forPeriodSpy = vi.fn(() => of(balancedTb()));
  const exportSpy = vi.fn(() => of(new Blob()));

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
          getTrialBalanceForPeriod: forPeriodSpy,
          exportTrialBalance: exportSpy,
          listPeriods: vi.fn(() => of(periods)),
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

  return { forPeriodSpy, exportSpy };
}

/** jsdom chokes on anchor.click(); stub the whole download side-effect. */
function stubBrowserDownload(): void {
  vi.spyOn(document.body, 'appendChild').mockImplementation((n) => n as Node);
  vi.spyOn(document.body, 'removeChild').mockImplementation((n) => n as Node);
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
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

// ── Export ─────────────────────────────────────────────────────────────────────
// Found live: the Finance Director exported the P&L, balance sheet, cash flow and stock valuation
// on the same token, but the trial balance — the first page of a period-close pack — had no export
// at all. These lock down the three ways that can regress: the button vanishing, the download
// asking for something other than what is on the screen, and it being offered to someone the
// server will refuse.

describe('TrialBalanceComponent — export', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks(); // undo the document.body / URL spies
    TestBed.resetTestingModule();
  });

  it('offers the export buttons to a holder of REPORT.EXPORT', async () => {
    makeBed();
    const fixture = TestBed.createComponent(TrialBalanceComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(fixture.componentInstance.canExport()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Export PDF');
    expect(fixture.nativeElement.textContent).toContain('Export Excel');
    expect(fixture.nativeElement.textContent).toContain('Export CSV');
  });

  it('hides them from a caller who may view the trial balance but not export', async () => {
    makeBed({ canView: (code) => code !== 'REPORT.EXPORT' });
    const fixture = TestBed.createComponent(TrialBalanceComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(fixture.componentInstance.canView()).toBe(true);
    expect(fixture.componentInstance.canExport()).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('Export PDF');
  });

  it('export() asks for the company on screen, in the requested format', async () => {
    const { exportSpy } = makeBed();
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();
    stubBrowserDownload();

    comp.export('PDF');

    expect(exportSpy).toHaveBeenCalledWith('10', 'PDF', null);
    expect(comp.exporting()).toBe(false);
  });

  it('export() carries the selected period, so paper and screen show the same figures', async () => {
    const { exportSpy } = makeBed({ periods: PERIODS });
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();
    stubBrowserDownload();

    comp.onPeriodChange('77');
    comp.export('XLSX');

    expect(exportSpy).toHaveBeenCalledWith('10', 'XLSX', '77');
  });

  it('a failed download clears the busy flag instead of wedging the buttons', async () => {
    const { exportSpy } = makeBed();
    exportSpy.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })) as never,
    );
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.export('CSV');

    expect(comp.exporting()).toBe(false);
  });
});

// ── Period filter: periodId, not periodUid ─────────────────────────────────────
// The endpoint binds `?periodId=` (a numeric id). The screen used to send `periodUid`, which bound
// nothing: every period-filtered run came back 400 and the user read "Could not load trial
// balance". The export takes the same id, so the two can never drift apart again.

describe('TrialBalanceComponent — period filter', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sends the period id (not the uid) when a period is chosen', async () => {
    const { forPeriodSpy } = makeBed({ periods: PERIODS });
    const comp = TestBed.createComponent(TrialBalanceComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.onPeriodChange('77');

    expect(forPeriodSpy).toHaveBeenCalledWith('10', '77');
    expect(comp.selectedPeriodId()).toBe('77');
  });

  it('renders the period options keyed by id', async () => {
    makeBed({ periods: PERIODS });
    const fixture = TestBed.createComponent(TrialBalanceComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const options: HTMLOptionElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('#periodPicker option'),
    );
    expect(options.map((o) => o.value)).toContain('77');
    expect(options.map((o) => o.value)).not.toContain('PER-77');
  });
});
