/**
 * StockCountDetailComponent — pack-unit count entry (STK-08 / OPN-01).
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { ProductService } from '../../products/product.service';
import { StockCountDto } from './stock-count.model';
import { StockCountService } from './stock-count.service';
import { StockCountDetailComponent } from './stock-count-detail.component';

const COUNT: StockCountDto = {
  uid: 'CNT1', id: '1', companyId: '10', branchId: '20', countNumber: 'SC-0001',
  status: 'COUNTING', countType: 'FULL', locationId: '5', countDate: '2026-10-10',
  frozenAt: null, postedAt: null, cancelledAt: null, varianceGlEntryUid: null, notes: null,
  lines: [{
    id: '300', uid: 'L1', lineNo: 1, productId: '7', productCode: 'BEER', productName: 'Beer',
    unitName: 'Pieces', systemQty: '55', countedQty: null, varianceQty: null,
    unitCostAmount: '0', varianceValue: null, reasonCode: null, movementUid: null,
    currency: 'TZS', productUid: 'PROD-BEER',
  }],
};

function setup() {
  const enterCount = vi.fn(() => of(COUNT));
  TestBed.configureTestingModule({
    imports: [StockCountDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: StockCountService, useValue: { getByUid: vi.fn(() => of(COUNT)), enterCount } },
      {
        provide: ProductService,
        useValue: {
          listProductUnits: vi.fn(() => of([{ uid: 'PCS-UID', code: 'PCS', name: 'Pieces' }])),
          listBulkPacks: vi.fn(() => of([
            { uid: 'BP1', unitUid: 'CTN-UID', unitCode: 'CTN', unitName: 'Carton', factorToBase: '12' },
          ])),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: { hasPermission: vi.fn(() => true), user: signal(null), permissions: signal([]) },
      },
    ],
  });
  const fixture = TestBed.createComponent(StockCountDetailComponent);
  fixture.componentRef.setInput('uid', 'CNT1');
  return { fixture, comp: fixture.componentInstance, enterCount };
}

describe('StockCountDetailComponent — pack units', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('loads pack sizes, shows the system qty as cartons, and sends the chosen unit', async () => {
    const { fixture, comp, enterCount } = setup();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const line = COUNT.lines[0];
    expect(comp.unitsFor(line).map((u) => u.unitUid)).toEqual(['', 'CTN-UID']);
    expect(comp.packs(line, line.systemQty)).toBe('4 CTN + 7 PCS');

    comp.onUnitChange('300', 'CTN-UID');
    comp.onQtyChange('300', 4);
    expect(comp.basePreview(line)).toBe('= 48 Pieces');

    comp.enterCount();
    await vi.runAllTimersAsync();

    expect(enterCount).toHaveBeenCalledWith('CNT1', {
      lines: [{ lineId: '300', countedQty: '4', reasonCode: undefined, unitUid: 'CTN-UID' }],
    });
    // Saved figures come back in base units, so the line returns to the base unit.
    expect(comp.editedUnits()).toEqual({});
  });
});
