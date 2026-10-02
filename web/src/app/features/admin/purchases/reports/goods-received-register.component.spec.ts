/**
 * GoodsReceivedRegisterComponent — behaviour.
 *
 *  1. Never calls the API without PURCHASE.GOODS_RECEIPT.VIEW.
 *  2. Sends the filters as chosen; blank pickers are sent as "all" (null).
 *  3. A VOID line renders negative and marked; a direct receipt is badged.
 *  4. The footer shows the whole-set total, and a foreign-currency count is disclosed.
 *  5. The server's branch refusal is shown in its own words.
 *  6. Export follows REPORT.EXPORT.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { GoodsReceivedRegisterComponent } from './goods-received-register.component';
import { providersFor, registerDto, reportServiceMock } from './purchase-report.testing';

const VIEW = 'PURCHASE.GOODS_RECEIPT.VIEW';

function create(permissions: string[], service = reportServiceMock({ goodsReceived: vi.fn(() => of(registerDto())) })) {
  TestBed.configureTestingModule({
    imports: [GoodsReceivedRegisterComponent],
    providers: providersFor(service, permissions),
  });
  const fixture = TestBed.createComponent(GoodsReceivedRegisterComponent);
  fixture.detectChanges();
  return { fixture, service, comp: fixture.componentInstance };
}

describe('GoodsReceivedRegisterComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('refuses without the receipt view permission and calls nothing', () => {
    const { fixture, service, comp } = create([]);
    comp.run();
    expect(service.goodsReceived).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain("You don't have permission");
  });

  it('sends the chosen filters, blanks as null', () => {
    const { service, comp } = create([VIEW]);
    comp.fromDate.set('2026-03-01');
    comp.toDate.set('2026-03-31');
    comp.supplierUid.set('SUP-1');
    comp.run();
    expect(service.goodsReceived).toHaveBeenCalledWith(
      { fromDate: '2026-03-01', toDate: '2026-03-31', branchUid: null, supplierUid: 'SUP-1', productUid: null },
      0,
      50,
    );
  });

  it('renders void lines negative and marked, direct receipts badged, and the whole-set total', () => {
    const { fixture, comp } = create([VIEW]);
    comp.run();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const rows = Array.from(el.querySelectorAll('tbody tr')) as HTMLElement[];
    expect(rows).toHaveLength(3);
    expect(rows[2].classList).toContain('row-void');
    expect(rows[2].textContent).toContain('Voided');
    expect(rows[2].textContent).toContain('-200.00');
    expect(rows[1].textContent).toContain('Direct');
    expect(rows[0].textContent).toContain('05/03/2026');
    expect(el.querySelector('tfoot')?.textContent).toContain('1,020.00');
    expect(el.querySelector('tfoot')?.textContent).toContain('1 void(s)');
  });

  it('discloses lines left out of the total for being in another currency', () => {
    const service = reportServiceMock({
      goodsReceived: vi.fn(() =>
        of(registerDto({ totals: { receipts: 2, voids: 1, lines: 3, value: 1020, rowsInOtherCurrency: 2 } })),
      ),
    });
    const { fixture, comp } = create([VIEW], service);
    comp.run();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('2 line(s) are in another currency');
  });

  it("shows the server's branch refusal in its own words", () => {
    const service = reportServiceMock({
      goodsReceived: vi.fn(() =>
        throwError(() => new HttpErrorResponse({
          status: 403,
          error: { errors: ['You are not assigned to that branch.'] },
        })),
      ),
    });
    const { fixture, comp } = create([VIEW], service);
    comp.run();
    fixture.detectChanges();
    expect(comp.state()).toBe('forbidden');
    expect(fixture.nativeElement.textContent).toContain('You are not assigned to that branch.');
  });

  it('offers export only with REPORT.EXPORT, and exports the same filter', () => {
    const without = create([VIEW]);
    without.comp.run();
    without.fixture.detectChanges();
    expect(without.fixture.nativeElement.textContent).not.toContain('Export Excel');
    TestBed.resetTestingModule();

    const { fixture, service, comp } = create([VIEW, 'REPORT.EXPORT']);
    comp.run();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Export Excel');
    const origCreate = URL.createObjectURL;
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    comp.export('XLSX');
    URL.createObjectURL = origCreate;
    expect(service.exportGoodsReceived).toHaveBeenCalledWith(
      expect.objectContaining({ fromDate: comp.fromDate(), toDate: comp.toDate() }),
      'XLSX',
    );
  });

  it('does not call the server for an inverted date range', () => {
    const { service, comp } = create([VIEW]);
    comp.fromDate.set('2026-03-31');
    comp.toDate.set('2026-03-01');
    comp.run();
    expect(service.goodsReceived).not.toHaveBeenCalled();
  });
});
