/**
 * ReorderReportComponent — behaviour specs.
 *
 *  1. Runs on open; never calls the API without STOCK.VIEW.
 *  2. With costVisible the cost columns and the order-value foot appear; without it they are
 *     LEFT OUT (not shown blank, which would read as "no cost on record").
 *  3. The supplier picker searches the server, scoped to the caller's company.
 *  4. Export sends the same filter.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { SupplierService } from '../parties/supplier.service';
import { ReportFilterOptionsService } from '../reporting/report-filter-options.service';
import { ReorderReportDto } from './models/reorder-report.model';
import { ReorderReportComponent } from './reorder-report.component';
import { StockReorderAgeingService } from './stock-reorder-ageing.service';

const ROW = {
  productUid: 'P1', productCode: 'KON500', productName: 'Konyagi 500ml', unitName: 'PCS',
  branchName: 'Head Office', locationCode: 'MAIN', locationName: 'Main store',
  onHand: 25, reorderLevel: 30, maxQty: 50, shortfall: 5, suggestedOrderQty: 25,
  supplierUid: 'S1', supplierName: 'Mbasha Holdings', lastCost: 110, estimatedOrderValue: 2750,
};

function report(costVisible: boolean): ReorderReportDto {
  return {
    company: {
      name: 'Kilimanjaro Star', legalName: null, addressLine1: null, addressLine2: null,
      city: null, region: null, country: null, contactPhone: null, contactEmail: null,
      taxId: null, vrn: null,
    },
    branchName: null,
    supplierName: null,
    currency: 'TZS',
    costVisible,
    rows: [costVisible ? ROW : { ...ROW, lastCost: null, estimatedOrderValue: null }],
    itemCount: 1,
    estimatedOrderValue: costVisible ? 2750 : null,
    rowsWithoutCost: 0,
    generatedAt: '2026-10-02T08:00:00Z',
  };
}

function makeBed(o: {
  reportSpy?: ReturnType<typeof vi.fn>;
  exportSpy?: ReturnType<typeof vi.fn>;
  supplierList?: ReturnType<typeof vi.fn>;
  hasPermission?: (code: string) => boolean;
} = {}) {
  const reportSpy = o.reportSpy ?? vi.fn(() => of(report(true)));
  const exportSpy = o.exportSpy ?? vi.fn(() => of(new Blob(['x'])));
  const supplierList = o.supplierList ?? vi.fn(() => of({
    rows: [{ uid: 'S1', displayName: 'Mbasha Holdings', code: 'SUP1', status: 'ACTIVE' }],
    meta: {},
  }));
  TestBed.configureTestingModule({
    imports: [ReorderReportComponent],
    providers: [
      { provide: StockReorderAgeingService, useValue: { reorder: reportSpy, exportReorder: exportSpy } },
      {
        provide: ReportFilterOptionsService,
        useValue: {
          company: vi.fn(() => of({ id: '10', uid: 'CO1' })),
          branchOptions: vi.fn(() => of([])),
        },
      },
      { provide: SupplierService, useValue: { list: supplierList } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(o.hasPermission ?? (() => true)),
          user: signal({ isRoot: false }),
        },
      },
    ],
  });
  return { reportSpy, exportSpy, supplierList };
}

describe('ReorderReportComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('runs on open', () => {
    const { reportSpy } = makeBed();
    const fixture = TestBed.createComponent(ReorderReportComponent);
    fixture.detectChanges();
    expect(reportSpy).toHaveBeenCalledTimes(1);
  });

  it('never calls the API without STOCK.VIEW', () => {
    const { reportSpy } = makeBed({ hasPermission: () => false });
    const fixture = TestBed.createComponent(ReorderReportComponent);
    fixture.detectChanges();

    expect(reportSpy).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("don't have permission");
  });

  it('shows the cost columns and the order value when cost is visible', () => {
    makeBed();
    const fixture = TestBed.createComponent(ReorderReportComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelectorAll('thead th').length).toBe(10);
    const cells = el.querySelectorAll('tbody tr')[0].querySelectorAll('td');
    expect(cells[6].textContent?.trim()).toBe('25');
    expect(cells[8].textContent?.trim()).toBe('110.00');
    expect(el.querySelector('tfoot')?.textContent).toContain('2,750.00');
  });

  it('leaves the cost columns out entirely when cost is withheld', () => {
    makeBed({ reportSpy: vi.fn(() => of(report(false))) });
    const fixture = TestBed.createComponent(ReorderReportComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelectorAll('thead th').length).toBe(8);
    expect(el.textContent).not.toContain('Last cost');
    expect(el.querySelector('tfoot')).toBeNull();
  });

  it('searches suppliers on the server within the company', async () => {
    const { supplierList } = makeBed();
    const fixture = TestBed.createComponent(ReorderReportComponent);
    fixture.detectChanges();

    const found = await firstValueFrom(fixture.componentInstance.searchSuppliers('mba'));
    expect(supplierList).toHaveBeenCalledWith('10', 'mba', 0, 20);
    expect(found[0].label).toBe('Mbasha Holdings');
  });

  it('exports with the same filter', () => {
    const { exportSpy } = makeBed();
    const fixture = TestBed.createComponent(ReorderReportComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();

    comp.supplierUid.set('S1');
    comp.export('CSV');

    const [f, fmt] = exportSpy.mock.calls[0] as [{ supplierUid: string }, string];
    expect(f.supplierUid).toBe('S1');
    expect(fmt).toBe('CSV');
  });
});
