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

type Calls =
  | 'waiterSales'
  | 'waiterItems'
  | 'bills'
  | 'billsSummary'
  | 'pendingBills'
  | 'pendingSummary'
  | 'daypart'
  | 'payments'
  | 'export'
  | 'waiters';

/** The bed with every service call a spy (answering the fixtures unless overridden). */
function makeReportsBed(
  perms: string[] = REPORT_PERMS,
  overrides: Partial<Record<Calls, ReturnType<typeof vi.fn>>> = {},
) {
  const stub = stubReportService();
  const svc = {
    waiterSales: overrides.waiterSales ?? vi.fn(stub.waiterSales),
    waiterItems: overrides.waiterItems ?? vi.fn(stub.waiterItems),
    bills: overrides.bills ?? vi.fn(stub.bills),
    billsSummary: overrides.billsSummary ?? vi.fn(stub.billsSummary),
    pendingBills: overrides.pendingBills ?? vi.fn(stub.pendingBills),
    pendingSummary: overrides.pendingSummary ?? vi.fn(stub.pendingSummary),
    daypart: overrides.daypart ?? vi.fn(stub.daypart),
    payments: overrides.payments ?? vi.fn(stub.payments),
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

  // The service stubs answer synchronously (of()), so a run has rendered by the next detectChanges;
  // no whenStable, which can wait on another spec's leftovers in a shared worker.
  it('runs Sales by waiter with the form filter; blank waiter and branch go as null', () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.fromDate.set('2026-10-01');
    comp.toDate.set('2026-10-30');
    comp.run();
    fixture.detectChanges();

    expect(svc.waiterSales).toHaveBeenCalledWith({
      fromDate: '2026-10-01', toDate: '2026-10-30', waiterUid: null, branchUid: null,
    });
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Baraka Juma');
    expect(text).toContain('94,000.00');
  });

  it('Bills paid marks a voided receipt', () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.setKind('bills');
    comp.waiterUid.set('W1');
    comp.run();
    fixture.detectChanges();

    expect(svc.bills).toHaveBeenCalledWith(expect.objectContaining({ waiterUid: 'W1' }));
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('INV-0011');
    expect(text).toContain('Void');
    expect(text).toContain('Sir Jacob');
  });

  it('Pending bills needs no dates and shows open orders without an amount', () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.setKind('pending-bills');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#rr-from')).toBeNull();

    comp.run();
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

  it('a 403 reads as forbidden', () => {
    makeReportsBed(undefined, {
      waiterSales: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))),
    });
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    expect(fixture.componentInstance.state()).toBe('forbidden');
  });

  it('Detail on Sales by waiter lists each waiter\'s items under a subtotal', () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.setView('detail');
    expect(comp.kind()).toBe('waiter-items');
    comp.run();
    fixture.detectChanges();

    expect(svc.waiterItems).toHaveBeenCalled();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Neema Ali · 1 bills');
    expect(text).toContain('Tacos');
    expect(text).toContain('111,000.00');
  });

  it('a tab opens in the view last chosen: Bills paid summary splits by payment type', () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.setTab('bills');
    expect(comp.kind()).toBe('bills-summary');
    comp.run();
    fixture.detectChanges();

    expect(svc.billsSummary).toHaveBeenCalled();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Mobile money');
    expect(text).toContain('Complimentary');
    expect(text).toContain('40,000.00');

    comp.setView('detail');
    comp.setTab('pending');
    expect(comp.kind()).toBe('pending-bills');
  });

  it('Pending bills summary needs no dates and shows who has been waiting longest', () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.setKind('pending-summary');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#rr-from')).toBeNull();
    comp.run();
    fixture.detectChanges();
    expect(svc.pendingSummary).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('2026-10-30 19:00');
  });

  it('Sales by hour & day shows a column per department and hides the waiter filter', () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.setTab('daypart');
    fixture.detectChanges();
    expect(comp.hasViews()).toBe(false);
    expect(fixture.nativeElement.querySelector('#rr-waiter')).toBeNull();
    comp.run();
    fixture.detectChanges();

    expect(svc.daypart).toHaveBeenCalled();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('12:00-13:00');
    expect(text).toContain('Friday');
    expect(text).toContain('Drinks');
    expect(text).toContain('22,200.00');
  });

  it('Payments & completion shows payment shares, completion and what did not end in a sale', () => {
    const svc = makeReportsBed();
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp.setTab('payments');
    comp.run();
    fixture.detectChanges();

    expect(svc.payments).toHaveBeenCalled();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('63.1%');
    expect(text).toContain('75%');
    expect(text).toContain('Cold food');
    expect(text).toContain('INV-0011');
    expect(text).toContain('Customer left');
    expect(text).toContain('Wrong order');
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
