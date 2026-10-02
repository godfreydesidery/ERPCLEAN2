/**
 * PaymentSummaryReportComponent — behaviour specs.
 *
 *  1. Opens on TODAY and runs; never calls the API without POS.CASHUP.VIEW.
 *  2. Lines and one total per currency reach the DOM.
 *  3. The cashier picker is filled from the report's own cashier list.
 *  4. Export sends the same filter; hidden without REPORT.EXPORT.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { PaymentSummaryReportDto } from './models/payment-summary.model';
import { PaymentSummaryReportComponent } from './payment-summary-report.component';
import { ReportFilterOptionsService, todayIso } from './report-filter-options.service';
import { SalesSummaryReportsService } from './sales-summary-reports.service';

const FIXTURE: PaymentSummaryReportDto = {
  company: {
    name: 'Kilimanjaro Star', legalName: null, addressLine1: null, addressLine2: null,
    city: null, region: null, country: null, contactPhone: null, contactEmail: null,
    taxId: null, vrn: null,
  },
  fromDate: '2026-10-02',
  toDate: '2026-10-02',
  branchName: null,
  cashierName: null,
  baseCurrency: 'TZS',
  rows: [
    {
      date: '2026-10-02', cashierUid: 'U1', cashierName: 'Asha', currency: 'TZS',
      cash: 3000, mobileMoney: 540, card: 0, cheque: 0, total: 3540, payments: 2,
    },
    {
      date: '2026-10-02', cashierUid: 'U1', cashierName: 'Asha', currency: 'USD',
      cash: 10, mobileMoney: 0, card: 0, cheque: 0, total: 10, payments: 1,
    },
  ],
  totals: [
    { currency: 'TZS', cash: 3000, mobileMoney: 540, card: 0, cheque: 0, total: 3540, payments: 2 },
    { currency: 'USD', cash: 10, mobileMoney: 0, card: 0, cheque: 0, total: 10, payments: 1 },
  ],
  cashiers: [{ uid: 'U1', name: 'Asha' }, { uid: 'U2', name: 'Baraka' }],
  generatedAt: '2026-10-02T18:00:00Z',
};

function makeBed(o: {
  reportSpy?: ReturnType<typeof vi.fn>;
  exportSpy?: ReturnType<typeof vi.fn>;
  hasPermission?: (code: string) => boolean;
} = {}) {
  const reportSpy = o.reportSpy ?? vi.fn(() => of(FIXTURE));
  const exportSpy = o.exportSpy ?? vi.fn(() => of(new Blob(['x'])));
  TestBed.configureTestingModule({
    imports: [PaymentSummaryReportComponent],
    providers: [
      {
        provide: SalesSummaryReportsService,
        useValue: { paymentSummary: reportSpy, exportPaymentSummary: exportSpy },
      },
      { provide: ReportFilterOptionsService, useValue: { branchOptions: vi.fn(() => of([])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(o.hasPermission ?? (() => true)),
          user: signal({ isRoot: false }),
        },
      },
    ],
  });
  return { reportSpy, exportSpy };
}

describe('PaymentSummaryReportComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('opens on today and runs', () => {
    const { reportSpy } = makeBed();
    const fixture = TestBed.createComponent(PaymentSummaryReportComponent);
    fixture.detectChanges();

    const f = reportSpy.mock.calls[0][0] as { fromDate: string; toDate: string };
    expect(f.fromDate).toBe(todayIso());
    expect(f.toDate).toBe(todayIso());
  });

  it('never calls the API without POS.CASHUP.VIEW', () => {
    const { reportSpy } = makeBed({ hasPermission: () => false });
    const fixture = TestBed.createComponent(PaymentSummaryReportComponent);
    fixture.detectChanges();

    expect(reportSpy).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("don't have permission");
  });

  it('prints the lines and one total per currency', () => {
    makeBed();
    const fixture = TestBed.createComponent(PaymentSummaryReportComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const first = el.querySelectorAll('tbody tr')[0].querySelectorAll('td');
    expect(first[3].textContent?.trim()).toBe('3,000.00');
    expect(first[4].textContent?.trim()).toBe('540.00');
    expect(first[7].textContent?.trim()).toBe('3,540.00');

    const feet = el.querySelectorAll('tfoot tr');
    expect(feet.length).toBe(2);
    expect(feet[1].textContent).toContain('USD');
  });

  it('offers the cashiers the report found', () => {
    makeBed();
    const fixture = TestBed.createComponent(PaymentSummaryReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.cashierOptions().map((c) => c.label))
      .toEqual(['Asha', 'Baraka']);
  });

  it('exports with the same filter', () => {
    const { exportSpy } = makeBed();
    const fixture = TestBed.createComponent(PaymentSummaryReportComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();

    comp.cashierUid.set('U2');
    comp.export('PDF');

    const [f, fmt] = exportSpy.mock.calls[0] as [{ cashierUid: string }, string];
    expect(f.cashierUid).toBe('U2');
    expect(fmt).toBe('PDF');
  });

  it('hides the export buttons without REPORT.EXPORT', () => {
    makeBed({ hasPermission: (c) => c !== 'REPORT.EXPORT' });
    const fixture = TestBed.createComponent(PaymentSummaryReportComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Export Excel');
  });

  it('shows the server sentence when the branch filter is refused', () => {
    makeBed({
      reportSpy: vi.fn(() => throwError(() => new HttpErrorResponse({
        status: 403, error: { errors: ['You are not assigned to that branch.'] },
      }))),
    });
    const fixture = TestBed.createComponent(PaymentSummaryReportComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('You are not assigned to that branch.');
  });
});
