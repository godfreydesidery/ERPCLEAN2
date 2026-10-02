/**
 * StockAgeingReportComponent — behaviour specs.
 *
 *  1. Runs on open as of today; never calls the API without INVENTORY.VALUATION.VIEW.
 *  2. The FIFO assumption is stated on the screen.
 *  3. Buckets render in order; stock of unknown age is marked in the oldest bucket.
 *  4. A never-costed item shows a dash for value, never 0.00.
 *  5. "Only items not sold in 90 days" narrows the rows but never the totals.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { ReportFilterOptionsService, todayIso } from '../reporting/report-filter-options.service';
import { StockAgeingReportDto } from './models/stock-ageing.model';
import { StockAgeingReportComponent } from './stock-ageing-report.component';
import { StockReorderAgeingService } from './stock-reorder-ageing.service';

const FIXTURE: StockAgeingReportDto = {
  company: {
    name: 'Kilimanjaro Star', legalName: null, addressLine1: null, addressLine2: null,
    city: null, region: null, country: null, contactPhone: null, contactEmail: null,
    taxId: null, vrn: null,
  },
  asOf: '2026-10-02',
  branchName: null,
  currency: 'TZS',
  buckets: ['0–30 days', '31–60 days', '61–90 days', '91–180 days', 'Over 180 days'],
  rows: [
    {
      productUid: 'A', productCode: 'A', productName: 'Fresh mover', unitName: 'PCS',
      onHand: 25, unitCost: 100, value: 2500, bucketQty: [2, 5, 0, 18, 0],
      bucketValue: [200, 500, 0, 1800, 0], uncoveredQty: 0,
      lastSaleDate: '2026-09-22', daysSinceLastSale: 10,
    },
    {
      productUid: 'B', productCode: 'B', productName: 'Legacy stock', unitName: 'PCS',
      onHand: 30, unitCost: null, value: null, bucketQty: [10, 0, 0, 0, 20],
      bucketValue: null, uncoveredQty: 20, lastSaleDate: null, daysSinceLastSale: null,
    },
  ],
  totalOnHand: 55,
  totalBucketQty: [12, 5, 0, 18, 20],
  totalBucketValue: [200, 500, 0, 1800, 0],
  totalValue: 2500,
  unvaluedProducts: 1,
  uncoveredProducts: 1,
  negativeStockProducts: 0,
  neverSoldProducts: 1,
  valuedAtCurrentCost: false,
  generatedAt: '2026-10-02T08:00:00Z',
};

function makeBed(o: {
  reportSpy?: ReturnType<typeof vi.fn>;
  hasPermission?: (code: string) => boolean;
} = {}) {
  const reportSpy = o.reportSpy ?? vi.fn(() => of(FIXTURE));
  TestBed.configureTestingModule({
    imports: [StockAgeingReportComponent],
    providers: [
      {
        provide: StockReorderAgeingService,
        useValue: { stockAgeing: reportSpy, exportStockAgeing: vi.fn(() => of(new Blob())) },
      },
      { provide: ReportFilterOptionsService, useValue: { branchOptions: vi.fn(() => of([])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(o.hasPermission ?? (() => true)),
          user: signal({ isRoot: false }),
        },
      },
    ],
  });
  return { reportSpy };
}

describe('StockAgeingReportComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('runs on open as of today', () => {
    const { reportSpy } = makeBed();
    const fixture = TestBed.createComponent(StockAgeingReportComponent);
    fixture.detectChanges();

    expect((reportSpy.mock.calls[0][0] as { asOf: string }).asOf).toBe(todayIso());
  });

  it('never calls the API without INVENTORY.VALUATION.VIEW', () => {
    const { reportSpy } = makeBed({ hasPermission: () => false });
    const fixture = TestBed.createComponent(StockAgeingReportComponent);
    fixture.detectChanges();

    expect(reportSpy).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("don't have permission");
  });

  it('states the first-in, first-out assumption', () => {
    makeBed();
    const fixture = TestBed.createComponent(StockAgeingReportComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('first in, first out');
  });

  it('renders buckets in order and marks stock of unknown age', () => {
    makeBed();
    const fixture = TestBed.createComponent(StockAgeingReportComponent);
    fixture.detectChanges();
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');

    const a = rows[0].querySelectorAll('td');
    expect(a[4].textContent?.trim()).toBe('2');
    expect(a[7].textContent?.trim()).toBe('18');
    expect(a[9].textContent?.trim()).toBe('10');

    const b = rows[1].querySelectorAll('td');
    expect(b[3].textContent?.trim()).toBe('—');
    expect(b[8].textContent).toContain('*');
    expect(b[9].textContent?.trim()).toBe('Never');
  });

  it('narrows to slow movers without changing the totals', () => {
    makeBed();
    const fixture = TestBed.createComponent(StockAgeingReportComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();

    comp.slowOnly.set(true);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelectorAll('tbody tr').length).toBe(1);
    expect(el.querySelector('tbody')?.textContent).toContain('Legacy stock');
    expect(el.querySelector('tfoot')?.textContent).toContain('55');
  });
});
