/**
 * SalesSummaryReportComponent — behaviour specs.
 *
 *  1. Runs on open with the default grouping; never calls the API without SALES.INVOICE.VIEW.
 *  2. ?groupBy= preselects the grouping (deep link for "agent performance" etc.).
 *  3. An UNKNOWN cost / margin renders as a dash, never 0.00, and the partial foot is disclosed.
 *  4. The group column heading follows the grouping.
 *  5. Export sends the same filter; buttons hidden without REPORT.EXPORT.
 *  6. A refused branch shows the server's sentence.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { SalesSummaryReportDto } from './models/sales-summary.model';
import { ReportFilterOptionsService } from './report-filter-options.service';
import { SalesSummaryReportComponent } from './sales-summary-report.component';
import { SalesSummaryReportsService } from './sales-summary-reports.service';

const SALES_SUMMARY_FIXTURE: SalesSummaryReportDto = {
  company: {
    name: 'Kilimanjaro Star', legalName: null, addressLine1: null, addressLine2: null,
    city: 'Moshi', region: null, country: 'Tanzania', contactPhone: null, contactEmail: null,
    taxId: null, vrn: null,
  },
  fromDate: '2026-10-01',
  toDate: '2026-10-02',
  groupBy: 'CUSTOMER',
  branchName: null,
  currency: 'TZS',
  rows: [
    {
      groupKey: 'C1', groupLabel: 'Alpha Shop', groupCode: 'C001', invoiceCount: 2, qty: 51,
      grossAmount: 8260, discount: 100, vatAmount: 1260, netAmount: 7000,
      costOfSales: 3900, margin: 3100, marginPercent: 44.29, unknownCostItems: 0,
    },
    {
      groupKey: 'C2', groupLabel: 'Beta Kiosk', groupCode: 'C002', invoiceCount: 1, qty: 1,
      grossAmount: 590, discount: 0, vatAmount: 90, netAmount: 500,
      costOfSales: null, margin: null, marginPercent: null, unknownCostItems: 1,
    },
  ],
  totals: {
    invoiceCount: 3, qty: 52, grossAmount: 8850, discount: 100, vatAmount: 1350, netAmount: 7500,
    costOfSales: 3900, margin: 3100, marginPercent: 44.29, groupsWithUnknownCost: 1,
    unknownCostItems: 1,
  },
  generatedAt: '2026-10-02T08:00:00Z',
};

function makeBed(o: {
  reportSpy?: ReturnType<typeof vi.fn>;
  exportSpy?: ReturnType<typeof vi.fn>;
  hasPermission?: (code: string) => boolean;
  groupByParam?: string;
} = {}) {
  const reportSpy = o.reportSpy ?? vi.fn(() => of(SALES_SUMMARY_FIXTURE));
  const exportSpy = o.exportSpy ?? vi.fn(() => of(new Blob(['x'])));
  TestBed.configureTestingModule({
    imports: [SalesSummaryReportComponent],
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            queryParamMap: convertToParamMap(o.groupByParam ? { groupBy: o.groupByParam } : {}),
          },
        },
      },
      {
        provide: SalesSummaryReportsService,
        useValue: { salesSummary: reportSpy, exportSalesSummary: exportSpy },
      },
      {
        provide: ReportFilterOptionsService,
        useValue: { branchOptions: vi.fn(() => of([{ uid: 'BR1', label: 'Head Office' }])) },
      },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(o.hasPermission ?? (() => true)),
          user: signal({ isRoot: false }),
        },
      },
    ],
  });
  return { reportSpy, exportSpy };
}

describe('SalesSummaryReportComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('runs on open, grouped by customer', () => {
    const { reportSpy } = makeBed();
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    fixture.detectChanges();

    expect(reportSpy).toHaveBeenCalledTimes(1);
    const f = reportSpy.mock.calls[0][0] as { groupBy: string; fromDate: string };
    expect(f.groupBy).toBe('CUSTOMER');
    expect(f.fromDate).toMatch(/^\d{4}-\d{2}-01$/);
  });

  it('never calls the API without SALES.INVOICE.VIEW', () => {
    const { reportSpy } = makeBed({ hasPermission: () => false });
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    fixture.detectChanges();

    expect(reportSpy).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("don't have permission");
  });

  it('preselects the grouping from ?groupBy=', () => {
    const { reportSpy } = makeBed({ groupByParam: 'AGENT' });
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    fixture.detectChanges();

    expect((reportSpy.mock.calls[0][0] as { groupBy: string }).groupBy).toBe('AGENT');
  });

  it('shows an unknown cost and margin as a dash, and discloses the partial foot', () => {
    makeBed();
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const beta = el.querySelectorAll('tbody tr')[1].querySelectorAll('td');
    expect(beta[7].textContent?.trim()).toBe('—');
    expect(beta[8].textContent?.trim()).toBe('—');
    expect(beta[9].textContent?.trim()).toBe('—');
    expect(beta[6].textContent?.trim()).toBe('500.00');

    const alpha = el.querySelectorAll('tbody tr')[0].querySelectorAll('td');
    expect(alpha[9].textContent?.trim()).toBe('44.29%');

    expect(el.textContent).toContain('sold before their stock had ever been costed');
  });

  it('heads the group column after the grouping', () => {
    makeBed({
      reportSpy: vi.fn(() => of({ ...SALES_SUMMARY_FIXTURE, groupBy: 'DAY' })),
    });
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    fixture.detectChanges();

    const th = (fixture.nativeElement as HTMLElement).querySelector('thead th');
    expect(th?.textContent?.trim()).toBe('Date');
  });

  it('exports with the same filter and the chosen format', () => {
    const { exportSpy } = makeBed();
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();

    comp.groupBy.set('ROUTE');
    comp.branchUid.set('BR1');
    comp.export('XLSX');

    const [f, fmt] = exportSpy.mock.calls[0] as [{ groupBy: string; branchUid: string }, string];
    expect(f.groupBy).toBe('ROUTE');
    expect(f.branchUid).toBe('BR1');
    expect(fmt).toBe('XLSX');
  });

  it('hides the export buttons without REPORT.EXPORT', () => {
    makeBed({ hasPermission: (c) => c !== 'REPORT.EXPORT' });
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Export Excel');
  });

  it('asks nothing of the server when the end date is before the start', () => {
    const { reportSpy } = makeBed();
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    reportSpy.mockClear();

    comp.fromDate.set('2026-10-02');
    comp.toDate.set('2026-10-01');
    comp.run();

    expect(reportSpy).not.toHaveBeenCalled();
  });

  it('shows the server sentence when the branch filter is refused', () => {
    makeBed({
      reportSpy: vi.fn(() => throwError(() => new HttpErrorResponse({
        status: 403, error: { errors: ['You are not assigned to that branch.'] },
      }))),
    });
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('You are not assigned to that branch.');
  });
});
