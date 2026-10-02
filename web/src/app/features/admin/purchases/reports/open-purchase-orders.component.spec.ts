/**
 * OpenPurchaseOrdersComponent — behaviour.
 *
 *  1. Never calls the API without PURCHASE.ORDER.VIEW; runs on open when permitted.
 *  2. A blank "as at" is sent as null (the server reads today in the company's zone).
 *  3. Outstanding quantities and values reach the DOM; late lines are flagged.
 */
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { OpenPurchaseOrdersComponent } from './open-purchase-orders.component';
import { openOrdersDto, providersFor, reportServiceMock } from './purchase-report.testing';

const VIEW = 'PURCHASE.ORDER.VIEW';

function create(permissions: string[]) {
  const service = reportServiceMock({ openOrders: vi.fn(() => of(openOrdersDto())) });
  TestBed.configureTestingModule({
    imports: [OpenPurchaseOrdersComponent],
    providers: providersFor(service, permissions),
  });
  const fixture = TestBed.createComponent(OpenPurchaseOrdersComponent);
  fixture.detectChanges();
  return { fixture, service, comp: fixture.componentInstance };
}

describe('OpenPurchaseOrdersComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('does not run without the order view permission', () => {
    const { service, fixture } = create(['PURCHASE.GOODS_RECEIPT.VIEW']);
    expect(service.openOrders).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain("You don't have permission");
  });

  it('runs on open as at today, and renders outstanding lines with late ones flagged', () => {
    const { fixture, service } = create([VIEW]);
    expect(service.openOrders).toHaveBeenCalledTimes(1);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const rows = Array.from(el.querySelectorAll('tbody tr')) as HTMLElement[];
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('PO-0001');
    expect(rows[0].textContent).toContain('Late');
    expect(rows[0].textContent).toContain('400.00');
    expect(rows[1].textContent).not.toContain('Late');
    expect(el.querySelector('tfoot')?.textContent).toContain('550.00');
    expect(el.textContent).toContain('1 line(s) are past their expected date');
  });

  it('sends a blank as-at date as null', () => {
    const { service, comp } = create([VIEW]);
    comp.asOfDate.set('');
    comp.supplierUid.set('SUP-1');
    comp.run();
    expect(service.openOrders).toHaveBeenLastCalledWith({
      asOfDate: null, branchUid: null, supplierUid: 'SUP-1',
    });
  });
});
