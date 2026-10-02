/**
 * Accessibility gate — PurchasesBySupplierComponent: the filter bar before a run, and the populated report.
 */
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { assertA11y } from '../../../../../testing/a11y.helper';
import { PurchasesBySupplierComponent } from './purchases-by-supplier.component';
import { bySupplierDto, providersFor, reportServiceMock } from './purchase-report.testing';

function makeBed() {
  TestBed.configureTestingModule({
    imports: [PurchasesBySupplierComponent],
    providers: providersFor(
      reportServiceMock({ bySupplier: vi.fn(() => of(bySupplierDto())) }),
      ['PURCHASE.GOODS_RECEIPT.VIEW', 'REPORT.EXPORT'],
    ),
  });
}

describe('PurchasesBySupplierComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations before a run', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchasesBySupplierComponent);
    fixture.componentInstance.report.set(null);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with a populated report', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchasesBySupplierComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
