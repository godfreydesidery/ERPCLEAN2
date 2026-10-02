/**
 * FinancialRatiosComponent — key behaviour specs.
 *
 * Covers:
 *  1. Run sends company, period and the picked branch; every ratio, its formula and inputs render.
 *  2. A ratio with no value shows a dash and its reason — never 0.00.
 *  3. The screen needs BOTH REPORT.PL.VIEW and REPORT.BS.VIEW (as the endpoint does).
 *  4. A broken source statement raises a warning.
 *  5. The branch refusal's own sentence is shown, not a generic error.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { FinancialRatiosComponent } from './financial-ratios.component';
import { FinancialRatiosDto } from './models/reporting.model';
import { ReportingService } from './reporting.service';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const MY_BRANCHES = [
  {
    id: '11', uid: 'UB1', userUid: 'U1', branchUid: 'BR-ARU', branchCode: 'ARU',
    branchName: 'Arusha', companyUid: 'CO1', isDefault: true, assignedAt: '2026-01-01T00:00:00Z',
  },
];

function ratios(overrides: Partial<FinancialRatiosDto> = {}): FinancialRatiosDto {
  return {
    header: {
      companyId: '10', companyName: 'Main Co', currency: 'TZS',
      periodLabel: '2026-04-01 – 2026-06-29', comparativeLabel: 'Opening as at 2026-03-31',
      fromDate: '2026-04-01', toDate: '2026-06-29', asAtDate: null,
      generatedAt: '2026-10-01T08:00:00Z', branchUid: null, branchLabel: 'All branches',
    },
    company: null,
    periodDays: '90',
    ratios: [
      {
        key: 'CURRENT_RATIO', name: 'Current ratio', formula: 'Current assets ÷ Current liabilities',
        inputs: [{ label: 'Current assets', amount: 8000 }, { label: 'Current liabilities', amount: 1800 }],
        value: 4.44, unit: 'x', unavailableReason: null, note: null,
      },
      {
        key: 'GROSS_MARGIN', name: 'Gross margin', formula: 'Gross profit ÷ Revenue × 100',
        inputs: [{ label: 'Gross profit', amount: 0 }, { label: 'Revenue', amount: 0 }],
        value: null, unit: '%', unavailableReason: 'There is no revenue in the period.', note: null,
      },
      {
        key: 'DEBTOR_DAYS', name: 'Debtor days (DSO)', formula: 'Average receivables ÷ Revenue × Days in period',
        inputs: [{ label: 'Average receivables', amount: 1500 }],
        value: 45, unit: 'days', unavailableReason: null, note: 'Uses total revenue.',
      },
    ],
    incomeStatementTies: true,
    balanceSheetTies: true,
    notes: ['A ratio shows no value when what it divides by is zero.'],
    ...overrides,
  };
}

function makeBed(opts: {
  hasPermission?: (code: string) => boolean;
  readSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const readSpy = opts.readSpy ?? vi.fn(() => of(ratios()));
  const exportSpy = vi.fn(() => of(new Blob(['x'])));
  TestBed.configureTestingModule({
    imports: [FinancialRatiosComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: ReportingService, useValue: { financialRatios: readSpy, exportFinancialRatios: exportSpy } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: AuthService, useValue: { myBranches: vi.fn(() => of(MY_BRANCHES)) } },
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

function create() {
  const fixture = TestBed.createComponent(FinancialRatiosComponent);
  fixture.detectChanges();
  return fixture;
}

describe('FinancialRatiosComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('offers only the caller’s branches — no company-level slice for ratios', () => {
    makeBed();
    const fixture = create();
    expect(fixture.componentInstance.branch.options().map((o) => o.uid)).toEqual(['BR-ARU']);
  });

  it('sends the picked branch and renders each ratio with its formula and inputs', () => {
    const { readSpy } = makeBed();
    const fixture = create();
    fixture.componentInstance.onBranchChange('BR-ARU');
    fixture.componentInstance.run();
    fixture.detectChanges();

    expect(readSpy).toHaveBeenCalledTimes(1);
    expect(readSpy.mock.calls[0][0]).toBe('10');
    expect(readSpy.mock.calls[0][3]).toBe('BR-ARU');

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Current assets ÷ Current liabilities');
    expect(text).toContain('Current assets: 8,000.00');
    expect(text).toContain('4.44 ×');
    expect(text).toContain('45.00 days');
    expect(text).toContain('Uses total revenue.');
  });

  it('shows a ratio with no value as a dash plus the reason — never 0.00', () => {
    makeBed();
    const fixture = create();
    fixture.componentInstance.run();
    fixture.detectChanges();

    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    const result = rows[1].querySelectorAll('td')[2].textContent ?? '';
    expect(result).toContain('—');
    expect(result).toContain('There is no revenue in the period.');
    expect(result).not.toContain('0.00');
  });

  it('needs BOTH the P&L and the Balance Sheet permission', () => {
    const { readSpy } = makeBed({ hasPermission: (c) => c !== 'REPORT.BS.VIEW' });
    const fixture = create();
    fixture.componentInstance.run();
    expect(readSpy).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('both the Income Statement');
  });

  it('warns when a source statement fails its own check', () => {
    makeBed({ readSpy: vi.fn(() => of(ratios({ balanceSheetTies: false }))) });
    const fixture = create();
    fixture.componentInstance.run();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('does not pass its own');
  });

  it('shows the server’s branch-refusal sentence', () => {
    const refusal = 'You are not assigned to that branch. Choose a branch you work in, or clear the branch filter to see the whole company.';
    makeBed({
      readSpy: vi.fn(() =>
        throwError(() => new HttpErrorResponse({ status: 403, error: { errors: [refusal] } }))),
    });
    const fixture = create();
    fixture.componentInstance.run();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(refusal);
  });
});
