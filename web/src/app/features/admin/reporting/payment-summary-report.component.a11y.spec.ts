/**
 * Accessibility gate — PaymentSummaryReportComponent: filter bar, populated table with a
 * two-currency foot, and the no-payments state.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
import { PaymentSummaryReportDto } from './models/payment-summary.model';
import { PaymentSummaryReportComponent } from './payment-summary-report.component';
import { ReportFilterOptionsService } from './report-filter-options.service';
import { SalesSummaryReportsService } from './sales-summary-reports.service';

const FIXTURE: PaymentSummaryReportDto = {
  company: {
    name: 'Kilimanjaro Star', legalName: null, addressLine1: 'Plot 12', addressLine2: null,
    city: 'Moshi', region: null, country: 'Tanzania', contactPhone: null, contactEmail: null,
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
  cashiers: [{ uid: 'U1', name: 'Asha' }],
  generatedAt: '2026-10-02T18:00:00Z',
};

function makeBed(dto: PaymentSummaryReportDto) {
  TestBed.configureTestingModule({
    imports: [PaymentSummaryReportComponent],
    providers: [
      {
        provide: SalesSummaryReportsService,
        useValue: {
          paymentSummary: vi.fn(() => of(dto)),
          exportPaymentSummary: vi.fn(() => of(new Blob())),
        },
      },
      { provide: ReportFilterOptionsService, useValue: { branchOptions: vi.fn(() => of([])) } },
      {
        provide: SessionStore,
        useValue: { hasPermission: vi.fn(() => true), user: signal({ isRoot: false }) },
      },
    ],
  });
}

describe('PaymentSummaryReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with a populated two-currency report', async () => {
    makeBed(FIXTURE);
    const fixture = TestBed.createComponent(PaymentSummaryReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when nothing was taken', async () => {
    makeBed({ ...FIXTURE, rows: [], totals: [FIXTURE.totals[0]] });
    const fixture = TestBed.createComponent(PaymentSummaryReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
