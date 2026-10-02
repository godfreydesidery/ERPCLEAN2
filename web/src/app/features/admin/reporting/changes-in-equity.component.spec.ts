/**
 * ChangesInEquityComponent — key behaviour specs.
 *
 * Covers:
 *  1. Run sends the company and period; the rows and totals reach the DOM.
 *  2. Zero movements print as a dash, opening/closing always print a figure.
 *  3. The "ties to the Balance Sheet" bar, and the alarm when it does not tie.
 *  4. A component that does not add up is flagged on its row.
 *  5. Without REPORT.BS.VIEW nothing is asked of the server; export hidden without REPORT.EXPORT.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ChangesInEquityComponent } from './changes-in-equity.component';
import { ChangesInEquityDto, EquityMovementRowDto } from './models/reporting.model';
import { ReportingService } from './reporting.service';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

function row(overrides: Partial<EquityMovementRowDto>): EquityMovementRowDto {
  return {
    accountId: null, accountUid: null, accountCode: null, component: 'x', earningsFold: false,
    opening: 0, profitForPeriod: 0, openingBalancesPosted: 0, capitalIntroduced: 0,
    drawingsAndDividends: 0, transfers: 0, closing: 0, ties: true,
    ...overrides,
  };
}

function soce(overrides: Partial<ChangesInEquityDto> = {}): ChangesInEquityDto {
  const tied = {
    label: 'Opening + movements == Balance Sheet equity at period end',
    computed: { current: 3100, comparative: 0 },
    expected: { current: 3100, comparative: 0 },
    difference: { current: 0, comparative: 0 },
    ties: true,
  };
  return {
    header: {
      companyId: '10', companyName: 'Main Co', currency: 'TZS',
      periodLabel: '2026-01-01 – 2026-09-30', comparativeLabel: 'Opening as at 2025-12-31',
      fromDate: '2026-01-01', toDate: '2026-09-30', asAtDate: null,
      generatedAt: '2026-10-01T08:00:00Z', branchUid: null, branchLabel: 'All branches',
    },
    company: null,
    rows: [
      row({
        accountId: '7', accountUid: 'ACC3000', accountCode: '3000', component: "Owner's Equity / Capital",
        openingBalancesPosted: 2000, capitalIntroduced: 500, drawingsAndDividends: -100, closing: 2400,
      }),
      row({ component: 'Retained earnings — prior years (unclosed)', earningsFold: true }),
      row({ component: 'Current-year earnings', earningsFold: true, profitForPeriod: 700, closing: 700 }),
    ],
    totals: row({
      component: 'Total equity', openingBalancesPosted: 2000, capitalIntroduced: 500,
      drawingsAndDividends: -100, profitForPeriod: 700, closing: 3100,
    }),
    balanceSheetOpeningEquity: 0,
    balanceSheetClosingEquity: 3100,
    profitForPeriod: 700,
    reconciliation: tied,
    transfersCheck: { ...tied, label: 'Transfers net to zero', computed: { current: 0, comparative: 0 }, expected: { current: 0, comparative: 0 } },
    ...overrides,
  };
}

function makeBed(opts: { hasPermission?: (code: string) => boolean; dto?: ChangesInEquityDto } = {}) {
  const readSpy = vi.fn((..._args: unknown[]) => of(opts.dto ?? soce()));
  const exportSpy = vi.fn((..._args: unknown[]) => of(new Blob(['x'])));
  TestBed.configureTestingModule({
    imports: [ChangesInEquityComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: ReportingService, useValue: { changesInEquity: readSpy, exportChangesInEquity: exportSpy } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(opts.hasPermission ?? (() => true)),
          isAuthenticated: signal(true),
          user: signal({ isRoot: false }),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return { readSpy, exportSpy };
}

function runReport() {
  const fixture = TestBed.createComponent(ChangesInEquityComponent);
  fixture.detectChanges();
  fixture.componentInstance.run();
  fixture.detectChanges();
  return fixture;
}

describe('ChangesInEquityComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('runs for the selected company and period and renders every component', () => {
    const { readSpy } = makeBed();
    const fixture = runReport();

    expect(readSpy).toHaveBeenCalledTimes(1);
    const [companyId, from, to] = readSpy.mock.calls[0] as unknown as [string, string, string];
    expect(companyId).toBe('10');
    expect(from).toMatch(/^\d{4}-01-01$/);
    expect(to).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    const el = fixture.nativeElement as HTMLElement;
    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain("3000 Owner's Equity / Capital");
    const cells = rows[0].querySelectorAll('td');
    expect(cells[2].textContent?.trim()).toBe('2,000.00'); // opening balances posted
    expect(cells[3].textContent?.trim()).toBe('500.00');   // capital introduced
    expect(cells[4].textContent?.trim()).toBe('-100.00');  // drawings
    expect(cells[6].textContent?.trim()).toBe('2,400.00'); // closing
    expect(el.querySelector('tfoot')?.textContent).toContain('3,100.00');
  });

  it('prints a zero movement as a dash but always prints opening and closing', () => {
    makeBed();
    const fixture = runReport();
    const cells = (fixture.nativeElement as HTMLElement)
      .querySelectorAll('tbody tr')[1].querySelectorAll('td');
    expect(cells[0].textContent?.trim()).toBe('0.00'); // opening
    expect(cells[1].textContent?.trim()).toBe('—');    // no profit on the prior-years line
    expect(cells[6].textContent?.trim()).toBe('0.00'); // closing
  });

  it('shows the tie-out to the Balance Sheet', () => {
    makeBed();
    const fixture = runReport();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Ties to the Balance Sheet');
  });

  it('raises the alarm, and flags the row, when a component does not add up', () => {
    const base = soce();
    makeBed({
      dto: soce({
        rows: [{ ...base.rows[0], ties: false }, base.rows[1], base.rows[2]],
        reconciliation: { ...base.reconciliation, ties: false, difference: { current: 50, comparative: 0 } },
      }),
    });
    const fixture = runReport();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Does not tie');
    expect(text).toContain('does not add up');
  });

  it('asks nothing of the server without REPORT.BS.VIEW', () => {
    const { readSpy } = makeBed({ hasPermission: () => false });
    const fixture = TestBed.createComponent(ChangesInEquityComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("don't have permission");
    expect(readSpy).not.toHaveBeenCalled();
  });

  it('hides the export buttons without REPORT.EXPORT, and exports the same period with it', () => {
    makeBed({ hasPermission: (c) => c !== 'REPORT.EXPORT' });
    let fixture = runReport();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Excel');
    TestBed.resetTestingModule();

    const { exportSpy } = makeBed();
    fixture = runReport();
    fixture.componentInstance.export('XLSX');
    expect(exportSpy).toHaveBeenCalledTimes(1);
    expect(exportSpy.mock.calls[0][3]).toBe('XLSX');
  });
});
