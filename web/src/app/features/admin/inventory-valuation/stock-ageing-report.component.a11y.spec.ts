/**
 * Accessibility gate — StockAgeingReportComponent: filter bar, FIFO note, the warnings list (only
 * rendered when something is uncovered / unvalued / negative), the bucket table and its two-line
 * foot, and the empty state.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
import { ReportFilterOptionsService } from '../reporting/report-filter-options.service';
import { StockAgeingReportDto } from './models/stock-ageing.model';
import { StockAgeingReportComponent } from './stock-ageing-report.component';
import { StockReorderAgeingService } from './stock-reorder-ageing.service';

const FIXTURE: StockAgeingReportDto = {
  company: {
    name: 'Kilimanjaro Star', legalName: null, addressLine1: null, addressLine2: null,
    city: null, region: null, country: null, contactPhone: null, contactEmail: null,
    taxId: null, vrn: null,
  },
  asOf: '2026-09-01',
  branchName: 'Head Office',
  currency: 'TZS',
  buckets: ['0–30 days', '31–60 days', '61–90 days', '91–180 days', 'Over 180 days'],
  rows: [
    {
      productUid: 'B', productCode: 'B', productName: 'Legacy stock', unitName: 'PCS',
      onHand: 30, unitCost: null, value: null, bucketQty: [10, 0, 0, 0, 20],
      bucketValue: null, uncoveredQty: 20, lastSaleDate: null, daysSinceLastSale: null,
    },
  ],
  totalOnHand: 30,
  totalBucketQty: [10, 0, 0, 0, 20],
  totalBucketValue: [0, 0, 0, 0, 0],
  totalValue: 0,
  unvaluedProducts: 1,
  uncoveredProducts: 1,
  negativeStockProducts: 2,
  neverSoldProducts: 1,
  valuedAtCurrentCost: true,
  generatedAt: '2026-10-02T08:00:00Z',
};

function makeBed(dto: StockAgeingReportDto) {
  TestBed.configureTestingModule({
    imports: [StockAgeingReportComponent],
    providers: [
      {
        provide: StockReorderAgeingService,
        useValue: {
          stockAgeing: vi.fn(() => of(dto)),
          exportStockAgeing: vi.fn(() => of(new Blob())),
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

describe('StockAgeingReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with warnings and a populated table', async () => {
    makeBed(FIXTURE);
    const fixture = TestBed.createComponent(StockAgeingReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with no stock on hand', async () => {
    makeBed({ ...FIXTURE, rows: [], uncoveredProducts: 0, unvaluedProducts: 0, negativeStockProducts: 0 });
    const fixture = TestBed.createComponent(StockAgeingReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
