/**
 * Accessibility gate — OpenPurchaseOrdersComponent: the filter bar before a run, and the populated report.
 */
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { assertA11y } from '../../../../../testing/a11y.helper';
import { OpenPurchaseOrdersComponent } from './open-purchase-orders.component';
import { openOrdersDto, providersFor, reportServiceMock } from './purchase-report.testing';

function makeBed() {
  TestBed.configureTestingModule({
    imports: [OpenPurchaseOrdersComponent],
    providers: providersFor(
      reportServiceMock({ openOrders: vi.fn(() => of(openOrdersDto())) }),
      ['PURCHASE.ORDER.VIEW', 'REPORT.EXPORT'],
    ),
  });
}

describe('OpenPurchaseOrdersComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations before a run', async () => {
    makeBed();
    const fixture = TestBed.createComponent(OpenPurchaseOrdersComponent);
    fixture.componentInstance.report.set(null);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with a populated report', async () => {
    makeBed();
    const fixture = TestBed.createComponent(OpenPurchaseOrdersComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
