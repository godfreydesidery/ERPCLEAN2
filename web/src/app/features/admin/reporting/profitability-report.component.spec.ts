/**
 * ProfitabilityReportComponent — key behaviour specs (K-2026-08-30 #2).
 *
 * Covers:
 *  1. Runs on init when permitted; never calls the API without SALES.INVOICE.VIEW.
 *  2. The five figures reach the DOM and tie together (gross − VAT = net, net − cost = profit).
 *  3. An UNKNOWN cost renders as an em dash, never 0.00 — the whole sale would otherwise read as
 *     profit, which is the defect the honest-margin fix corrected once already.
 *  4. A partial total is disclosed on screen, with the count of excluded items.
 *  5. Export sends the same filter and the requested format; the buttons are hidden without
 *     REPORT.EXPORT.
 *  6. An invalid date range asks nothing of the server.
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
import { ProfitabilityReportDto } from './models/profitability-report.model';
import { ProfitabilityReportComponent } from './profitability-report.component';
import { ReportingService } from './reporting.service';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const MY_BRANCHES = [
  {
    id: '11', uid: 'UB1', userUid: 'U1', branchUid: 'BR-HQ', branchCode: 'HQ',
    branchName: 'Head Office', companyUid: 'CO1', isDefault: true,
    assignedAt: '2026-01-01T00:00:00Z',
  },
];

const EMPTY_COMPANY_HEADER = {
  name: 'Kilimanjaro Star Liquor Store', legalName: null,
  addressLine1: null, addressLine2: null, city: null, region: null, country: null,
  contactPhone: null, contactEmail: null, taxId: null, vrn: null,
};

function report(overrides: Partial<ProfitabilityReportDto> = {}): ProfitabilityReportDto {
  return {
    company: EMPTY_COMPANY_HEADER,
    fromDate: '2026-08-01',
    toDate: '2026-08-31',
    branchName: null,
    currency: 'TZS',
    rows: [
      {
        productCode: 'KON500', productName: 'Konyagi 500ml', qtySold: 120,
        grossSales: 1180000, vatAmount: 180000, netAmount: 1000000,
        costOfSales: 700000, profit: 300000,
      },
      // Sold before it was ever costed — cost and profit unknown, NOT zero.
      {
        productCode: 'NEW01', productName: 'New arrival', qtySold: 10,
        grossSales: 118000, vatAmount: 18000, netAmount: 100000,
        costOfSales: null, profit: null,
      },
    ],
    totals: {
      qtySold: 130,
      grossSales: 1298000,
      vatAmount: 198000,
      netAmount: 1100000,
      costOfSales: 700000,
      profit: 300000,
      rowsWithUnknownCost: 1,
    },
    generatedAt: '2026-09-01T08:00:00Z',
    ...overrides,
  };
}

function makeBed(overrides: {
  reportSpy?: ReturnType<typeof vi.fn>;
  exportSpy?: ReturnType<typeof vi.fn>;
  hasPermission?: (code: string) => boolean;
} = {}) {
  const reportSpy = overrides.reportSpy ?? vi.fn(() => of(report()));
  const exportSpy = overrides.exportSpy ?? vi.fn(() => of(new Blob(['x'])));

  TestBed.configureTestingModule({
    imports: [ProfitabilityReportComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ReportingService,
        useValue: { profitabilityReport: reportSpy, exportProfitabilityReport: exportSpy },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: AuthService, useValue: { myBranches: vi.fn(() => of(MY_BRANCHES)) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(overrides.hasPermission ?? (() => true)),
          isAuthenticated: signal(true),
          user: signal({ activeCompanyUid: 'CO1', isRoot: false }),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { reportSpy, exportSpy };
}

describe('ProfitabilityReportComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('runs for the current month on open', () => {
    const { reportSpy } = makeBed();
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    fixture.detectChanges();

    expect(reportSpy).toHaveBeenCalledTimes(1);
    const filter = reportSpy.mock.calls[0][0] as { fromDate: string; toDate: string };
    expect(filter.fromDate).toMatch(/^\d{4}-\d{2}-01$/);
    expect(filter.toDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('never calls the API without SALES.INVOICE.VIEW', () => {
    const { reportSpy } = makeBed({ hasPermission: () => false });
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    fixture.detectChanges();

    expect(reportSpy).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain("don't have permission");
  });

  it('renders the five figures, and they tie together on the page', () => {
    makeBed();
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    fixture.detectChanges();

    const cells = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr'),
    )[0].querySelectorAll('td');
    const text = (i: number) => cells[i].textContent?.trim() ?? '';

    expect(text(3)).toBe('1,180,000.00');  // gross
    expect(text(4)).toBe('180,000.00');    // vat
    expect(text(5)).toBe('1,000,000.00');  // net = gross − vat
    expect(text(6)).toBe('700,000.00');    // cost
    expect(text(7)).toBe('300,000.00');    // profit = net − cost
  });

  /**
   * The rule the whole report rests on: an item sold before it was ever costed has an UNKNOWN
   * cost. Printed as 0.00 its entire sale would be reported as profit.
   */
  it('shows an unknown cost and profit as a dash, never as zero', () => {
    makeBed();
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    fixture.detectChanges();

    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    const cells = rows[1].querySelectorAll('td');

    expect(cells[6].textContent?.trim()).toBe('—');
    expect(cells[7].textContent?.trim()).toBe('—');
    // Its SALES are still fully reported — only the cost side is incomplete.
    expect(cells[3].textContent?.trim()).toBe('118,000.00');
  });

  it('says on screen when the profit total is partial, and how many items are missing', () => {
    makeBed();
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('sold before their stock had ever been costed');
    expect(text).toContain('partial');
  });

  it('does not warn when every cost is known', () => {
    makeBed({
      reportSpy: vi.fn(() => of(report({
        rows: [{
          productCode: 'KON500', productName: 'Konyagi 500ml', qtySold: 120,
          grossSales: 1180000, vatAmount: 180000, netAmount: 1000000,
          costOfSales: 700000, profit: 300000,
        }],
        totals: {
          qtySold: 120, grossSales: 1180000, vatAmount: 180000, netAmount: 1000000,
          costOfSales: 700000, profit: 300000, rowsWithUnknownCost: 0,
        },
      }))),
    });
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent)
      .not.toContain('sold before their stock had ever been costed');
  });

  it('exports with the same filter and the requested format', () => {
    const { exportSpy } = makeBed();
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();

    comp.branchUid.set('BR-HQ');
    comp.export('XLSX');

    expect(exportSpy).toHaveBeenCalledTimes(1);
    const [filter, format] = exportSpy.mock.calls[0] as [{ branchUid: string | null }, string];
    expect(filter.branchUid).toBe('BR-HQ');
    expect(format).toBe('XLSX');
  });

  it('hides the export buttons from a user without REPORT.EXPORT', () => {
    makeBed({ hasPermission: (code) => code !== 'REPORT.EXPORT' });
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Export Excel');
  });

  it('asks nothing of the server when the end date is before the start', () => {
    const { reportSpy } = makeBed();
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    reportSpy.mockClear();

    comp.fromDate.set('2026-08-31');
    comp.toDate.set('2026-08-01');
    comp.run();

    expect(reportSpy).not.toHaveBeenCalled();
    expect(comp.datesValid()).toBe(false);
  });

  it('shows the server sentence when the branch filter is refused', () => {
    makeBed({
      reportSpy: vi.fn(() => throwError(() => new HttpErrorResponse({
        status: 403,
        error: { errors: ['You are not assigned to that branch.'] },
      }))),
    });
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('You are not assigned to that branch.');
  });
});
