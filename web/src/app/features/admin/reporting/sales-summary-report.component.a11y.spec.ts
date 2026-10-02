/**
 * Accessibility gate — SalesSummaryReportComponent: filter bar, populated table with the
 * partial-cost banner (only rendered on that path), and the empty state.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
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

function makeBed(dto: SalesSummaryReportDto) {
  TestBed.configureTestingModule({
    imports: [SalesSummaryReportComponent],
    providers: [
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
      {
        provide: SalesSummaryReportsService,
        useValue: {
          salesSummary: vi.fn(() => of(dto)),
          exportSalesSummary: vi.fn(() => of(new Blob())),
        },
      },
      { provide: ReportFilterOptionsService, useValue: { branchOptions: vi.fn(() => of([])) } },
      {
        provide: SessionStore,
        useValue: { hasPermission: vi.fn(() => true), user: signal({ isRoot: false }) },
      },
    ],
  });
}

describe('SalesSummaryReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with a populated report and the partial-cost banner', async () => {
    makeBed(SALES_SUMMARY_FIXTURE);
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when nothing was sold', async () => {
    makeBed({ ...SALES_SUMMARY_FIXTURE, rows: [] });
    const fixture = TestBed.createComponent(SalesSummaryReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
