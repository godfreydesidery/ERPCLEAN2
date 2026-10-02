/**
 * PurchasePriceVarianceComponent — behaviour.
 *
 *  1. Needs BOTH PURCHASE.ORDER.VIEW and PURCHASE.GOODS_RECEIPT.VIEW — either alone is refused.
 *  2. Bill and receipt variance reach the DOM; an unbilled line shows an em dash, never 0.00.
 *  3. Without bill access (billsShown false) the bill columns are absent and the screen says why.
 */
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { PurchasePriceVarianceComponent } from './purchase-price-variance.component';
import { providersFor, reportServiceMock, varianceDto } from './purchase-report.testing';

const PO = 'PURCHASE.ORDER.VIEW';
const GRN = 'PURCHASE.GOODS_RECEIPT.VIEW';

function create(permissions: string[], dto = varianceDto()) {
  const service = reportServiceMock({ priceVariance: vi.fn(() => of(dto)) });
  TestBed.configureTestingModule({
    imports: [PurchasePriceVarianceComponent],
    providers: providersFor(service, permissions),
  });
  const fixture = TestBed.createComponent(PurchasePriceVarianceComponent);
  fixture.detectChanges();
  return { fixture, service, comp: fixture.componentInstance };
}

describe('PurchasePriceVarianceComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('needs both the order and the receipt view permission', () => {
    for (const perms of [[PO], [GRN]]) {
      const { service, comp } = create(perms);
      expect(comp.canView()).toBe(false);
      comp.run();
      expect(service.priceVariance).not.toHaveBeenCalled();
      TestBed.resetTestingModule();
    }
    const { comp } = create([PO, GRN]);
    expect(comp.canView()).toBe(true);
  });

  it('renders bill and receipt variance; an unbilled line shows a dash, not zero', () => {
    const { fixture, comp } = create([PO, GRN]);
    comp.run();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('thead th')).toHaveLength(13);
    const rows = Array.from(el.querySelectorAll('tbody tr')) as HTMLElement[];
    expect(rows[0].textContent).toContain('102.00');
    expect(rows[0].textContent).toContain('+2.00%');
    expect(rows[1].textContent).toContain('8.00');
    expect(rows[1].textContent).toContain('—');
    const foot = el.querySelector('tfoot')?.textContent ?? '';
    expect(foot).toContain('20.00');
    expect(foot).toContain('8.00');
  });

  it('drops the bill columns when the caller may not see supplier bills', () => {
    const dto = varianceDto({
      billsShown: false,
      totals: { lines: 1, receiptVarianceTotal: 8, billVarianceTotal: null, rowsInOtherCurrency: 0 },
    });
    const { fixture, comp } = create([PO, GRN], dto);
    comp.run();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('thead th')).toHaveLength(9);
    expect(el.textContent).toContain('Supplier-bill prices are hidden');
  });
});
