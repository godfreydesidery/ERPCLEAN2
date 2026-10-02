/**
 * Accessibility gate — PurchasePriceVarianceComponent: the filter bar before a run, and the populated report.
 */
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { assertA11y } from '../../../../../testing/a11y.helper';
import { PurchasePriceVarianceComponent } from './purchase-price-variance.component';
import { varianceDto, providersFor, reportServiceMock } from './purchase-report.testing';

function makeBed() {
  TestBed.configureTestingModule({
    imports: [PurchasePriceVarianceComponent],
    providers: providersFor(
      reportServiceMock({ priceVariance: vi.fn(() => of(varianceDto())) }),
      ['PURCHASE.ORDER.VIEW', 'PURCHASE.GOODS_RECEIPT.VIEW', 'REPORT.EXPORT'],
    ),
  });
}

describe('PurchasePriceVarianceComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations before a run', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchasePriceVarianceComponent);
    fixture.componentInstance.report.set(null);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with a populated report', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchasePriceVarianceComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
