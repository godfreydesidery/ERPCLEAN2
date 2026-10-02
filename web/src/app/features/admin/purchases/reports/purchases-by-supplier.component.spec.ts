/**
 * PurchasesBySupplierComponent — behaviour.
 *
 *  1. Never calls the API without PURCHASE.GOODS_RECEIPT.VIEW.
 *  2. Renders per-supplier figures and the base-currency totals.
 *  3. Columns the server withheld (returnsShown / billsShown false) are NOT rendered as zero — they
 *     are absent, and the screen says why.
 */
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { PurchasesBySupplierComponent } from './purchases-by-supplier.component';
import { bySupplierDto, providersFor, reportServiceMock } from './purchase-report.testing';

const VIEW = 'PURCHASE.GOODS_RECEIPT.VIEW';

function create(permissions: string[], dto = bySupplierDto()) {
  const service = reportServiceMock({ bySupplier: vi.fn(() => of(dto)) });
  TestBed.configureTestingModule({
    imports: [PurchasesBySupplierComponent],
    providers: providersFor(service, permissions),
  });
  const fixture = TestBed.createComponent(PurchasesBySupplierComponent);
  fixture.detectChanges();
  return { fixture, service, comp: fixture.componentInstance };
}

describe('PurchasesBySupplierComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('refuses without the receipt view permission and calls nothing', () => {
    const { service, comp, fixture } = create([]);
    comp.run();
    expect(service.bySupplier).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain("You don't have permission");
  });

  it('renders each supplier and the totals', () => {
    const { fixture, comp, service } = create([VIEW]);
    comp.branchUid.set('BR-A');
    comp.run();
    fixture.detectChanges();
    expect(service.bySupplier).toHaveBeenCalledWith(expect.objectContaining({ branchUid: 'BR-A' }));
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('thead th')).toHaveLength(8);
    const first = el.querySelector('tbody tr') as HTMLElement;
    expect(first.textContent).toContain('Alpha Traders');
    expect(first.textContent).toContain('1,200.00');
    expect(first.textContent).toContain('900.00');
    expect(first.textContent).toContain('1,050.00');
    const foot = el.querySelector('tfoot')?.textContent ?? '';
    expect(foot).toContain('1,300.00');
    expect(foot).toContain('1,000.00');
  });

  it('drops withheld columns entirely and explains why', () => {
    const dto = bySupplierDto({
      returnsShown: false,
      billsShown: false,
      rows: [
        {
          supplierCode: 'S1', supplierName: 'Alpha Traders', currency: 'TZS', receipts: 2,
          receivedValue: 1200, returnsValue: null, netPurchases: null, billedAmount: null, unpaidAmount: null,
        },
      ],
      totals: {
        receipts: 2, receivedValue: 1200, returnsValue: null, netPurchases: null,
        billedAmount: null, unpaidAmount: null, rowsInOtherCurrency: 0,
      },
    });
    const { fixture, comp } = create([VIEW], dto);
    comp.run();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('thead th')).toHaveLength(4);
    expect(el.textContent).toContain('Returns are hidden');
    expect(el.textContent).toContain('Billed and unpaid amounts are hidden');
  });
});
