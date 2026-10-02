/**
 * Accessibility gate — GoodsReceivedRegisterComponent: the filter bar before a run, and the populated report.
 */
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { assertA11y } from '../../../../../testing/a11y.helper';
import { GoodsReceivedRegisterComponent } from './goods-received-register.component';
import { registerDto, providersFor, reportServiceMock } from './purchase-report.testing';

function makeBed() {
  TestBed.configureTestingModule({
    imports: [GoodsReceivedRegisterComponent],
    providers: providersFor(
      reportServiceMock({ goodsReceived: vi.fn(() => of(registerDto())) }),
      ['PURCHASE.GOODS_RECEIPT.VIEW', 'REPORT.EXPORT'],
    ),
  });
}

describe('GoodsReceivedRegisterComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations before a run', async () => {
    makeBed();
    const fixture = TestBed.createComponent(GoodsReceivedRegisterComponent);
    fixture.componentInstance.report.set(null);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with a populated report', async () => {
    makeBed();
    const fixture = TestBed.createComponent(GoodsReceivedRegisterComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
