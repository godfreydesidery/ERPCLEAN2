/**
 * Accessibility gate — ReorderReportComponent: filter bar (incl. the server-search supplier
 * picker), the populated table with cost columns and the never-received banner, and the empty state.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
import { SupplierService } from '../parties/supplier.service';
import { ReportFilterOptionsService } from '../reporting/report-filter-options.service';
import { ReorderReportDto } from './models/reorder-report.model';
import { ReorderReportComponent } from './reorder-report.component';
import { StockReorderAgeingService } from './stock-reorder-ageing.service';

const REPORT: ReorderReportDto = {
  company: {
    name: 'Kilimanjaro Star', legalName: null, addressLine1: null, addressLine2: null,
    city: null, region: null, country: null, contactPhone: null, contactEmail: null,
    taxId: null, vrn: null,
  },
  branchName: null,
  supplierName: null,
  currency: 'TZS',
  costVisible: true,
  rows: [
    {
      productUid: 'P1', productCode: 'KON500', productName: 'Konyagi 500ml', unitName: 'PCS',
      branchName: 'Head Office', locationCode: 'MAIN', locationName: 'Main store',
      onHand: 25, reorderLevel: 30, maxQty: 50, shortfall: 5, suggestedOrderQty: 25,
      supplierUid: 'S1', supplierName: 'Mbasha Holdings', lastCost: 110, estimatedOrderValue: 2750,
    },
    {
      productUid: 'P2', productCode: 'NEW01', productName: 'New line', unitName: 'PCS',
      branchName: 'Head Office', locationCode: 'MAIN', locationName: 'Main store',
      onHand: 0, reorderLevel: 5, maxQty: null, shortfall: 5, suggestedOrderQty: 5,
      supplierUid: null, supplierName: null, lastCost: null, estimatedOrderValue: null,
    },
  ],
  itemCount: 2,
  estimatedOrderValue: 2750,
  rowsWithoutCost: 1,
  generatedAt: '2026-10-02T08:00:00Z',
};

function makeBed(dto: ReorderReportDto) {
  TestBed.configureTestingModule({
    imports: [ReorderReportComponent],
    providers: [
      {
        provide: StockReorderAgeingService,
        useValue: { reorder: vi.fn(() => of(dto)), exportReorder: vi.fn(() => of(new Blob())) },
      },
      {
        provide: ReportFilterOptionsService,
        useValue: {
          company: vi.fn(() => of({ id: '10', uid: 'CO1' })),
          branchOptions: vi.fn(() => of([])),
        },
      },
      { provide: SupplierService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
      {
        provide: SessionStore,
        useValue: { hasPermission: vi.fn(() => true), user: signal({ isRoot: false }) },
      },
    ],
  });
}

describe('ReorderReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with cost columns and the never-received banner', async () => {
    makeBed(REPORT);
    const fixture = TestBed.createComponent(ReorderReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when nothing needs reordering', async () => {
    makeBed({ ...REPORT, rows: [], itemCount: 0, rowsWithoutCost: 0 });
    const fixture = TestBed.createComponent(ReorderReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
