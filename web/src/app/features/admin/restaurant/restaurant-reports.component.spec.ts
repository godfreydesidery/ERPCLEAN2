/**
 * RestaurantReportsComponent — the reports per waiter (J COB 2026-10-30).
 *
 * Covers: each report calls its endpoint with the form's filter (blank waiter/branch sent as null,
 * no dates for pending bills), the waiter filter is offered only with a restaurant permission,
 * a voided receipt is marked, a 403 reads as forbidden, and export passes the report kind.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { throwError } from 'rxjs';
import { RestaurantReportsComponent } from './restaurant-reports.component';
import { REPORT_PERMS, reportsBed, stubReportService } from './restaurant-reports.testing';

type Calls = 'waiterSales' | 'bills' | 'pendingBills' | 'export' | 'waiters';

/** The bed with every service call a spy (answering the fixtures unless overridden). */
function makeReportsBed(
  perms: string[] = REPORT_PERMS,
  overrides: Partial<Record<Calls, ReturnType<typeof vi.fn>>> = {},
) {
  const stub = stubReportService();
  const svc = {
    waiterSales: overrides.waiterSales ?? vi.fn(stub.waiterSales),
    bills: overrides.bills ?? vi.fn(stub.bills),
    pendingBills: overrides.pendingBills ?? vi.fn(stub.pendingBills),
    export: overrides.export ?? vi.fn(stub.export),
    waiters: overrides.waiters ?? vi.fn(stub.waiters),
  };
  reportsBed(perms, svc);
  return svc;
}

describe('RestaurantReportsComponent', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('runs Sales by waiter with the form filter; blank waiter and branch go as null', async () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.fromDate.set('2026-10-01');
    comp.toDate.set('2026-10-30');
    comp.run();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(svc.waiterSales).toHaveBeenCalledWith({
      fromDate: '2026-10-01', toDate: '2026-10-30', waiterUid: null, branchUid: null,
    });
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Baraka Juma');
    expect(text).toContain('94,000.00');
  });

  it('Bills paid marks a voided receipt', async () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.setKind('bills');
    comp.waiterUid.set('W1');
    comp.run();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(svc.bills).toHaveBeenCalledWith(expect.objectContaining({ waiterUid: 'W1' }));
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('INV-0011');
    expect(text).toContain('Void');
    expect(text).toContain('Sir Jacob');
  });

  it('Pending bills needs no dates and shows open orders without an amount', async () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.setKind('pending-bills');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#rr-from')).toBeNull();

    comp.run();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(svc.pendingBills).toHaveBeenCalled();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Bill printed');
    expect(text).toContain('Open');
    expect(text).toContain('—');
  });

  it('offers the waiter filter only to a login with a restaurant permission', () => {
    const svc = makeReportsBed(['SALES.INVOICE.VIEW']);
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#rr-waiter')).toBeNull();
    expect(svc.waiters).not.toHaveBeenCalled();
  });

  it('a 403 reads as forbidden', async () => {
    makeReportsBed(undefined, {
      waiterSales: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))),
    });
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    await fixture.whenStable();
    expect(fixture.componentInstance.state()).toBe('forbidden');
  });

  it('export passes the report kind and format', () => {
    const svc = makeReportsBed();
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.setKind('bills');
    comp.export('PDF');
    expect(svc.export).toHaveBeenCalledWith('bills', expect.objectContaining({ waiterUid: null }), 'PDF');
  });
});
