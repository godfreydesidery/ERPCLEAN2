/**
 * Accessibility gate — PayrollStatutoryReportComponent.
 *
 * Covers: the filter bar in its empty state, and the populated report (table + totals + KPI tiles
 * + "not included" note + export toolbar).
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { HrPayrollService } from './hr-payroll.service';
import { CashbankService } from '../cashbank/cashbank.service';
import { PayrollStatutoryReportComponent } from './payroll-statutory-report.component';
import type { PayrollStatutoryPeriodReportDto } from './models/payroll-statutory.model';
import { assertA11y } from '../../../../testing/a11y.helper';

const REPORT: PayrollStatutoryPeriodReportDto = {
  fromDate: '2026-06-01',
  toDate: '2026-08-31',
  runs: [
    {
      runUid: 'RUN1', runNumber: 'PR-00001', periodYear: 2026, periodMonth: 6, payDate: '2026-06-30',
      status: 'POSTED', employeeCount: 2,
      grossTotal: 1850000, payeTotal: 210000, nssfEmployeeTotal: 185000, nssfEmployerTotal: 185000,
      wcfTotal: 9250, sdlTotal: 64750, heslbTotal: 0, netTotal: 1455000, employerCostTotal: 259000,
    },
  ],
  totals: {
    runCount: 1, payslipCount: 2,
    grossTotal: 1850000, payeTotal: 210000, nssfEmployeeTotal: 185000, nssfEmployerTotal: 185000,
    wcfTotal: 9250, sdlTotal: 64750, heslbTotal: 0, netTotal: 1455000, employerCostTotal: 259000,
  },
  pendingRunCount: 1,
  reversedRunCount: 0,
  currency: 'TZS',
  generatedAt: '2026-09-01T08:00:00Z',
};

function makeBed() {
  TestBed.configureTestingModule({
    imports: [PayrollStatutoryReportComponent],
    providers: [
      provideRouter([]),
      {
        provide: HrPayrollService,
        useValue: {
          getStatutoryPeriodReport: vi.fn(() => of(REPORT)),
          exportStatutoryPeriodReport: vi.fn(() => of(new Blob())),
          getStatutoryOutstanding: vi.fn(() => of([
            { liability: 'PAYE', accountCode: '2500', accountName: 'PAYE Payable',
              outstanding: 210000, companyId: '10' },
          ])),
          payStatutory: vi.fn(),
        },
      },
      {
        provide: CashbankService,
        useValue: { listAccountOptions: vi.fn(() => of([])) },
      },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

describe('PayrollStatutoryReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations in the empty (pre-run) state', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PayrollStatutoryReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with a populated report', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PayrollStatutoryReportComponent);
    fixture.componentInstance.run();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with the statutory payment form open (ACC-07)', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PayrollStatutoryReportComponent);
    fixture.detectChanges();
    fixture.componentInstance.openPay(fixture.componentInstance.outstanding()[0]);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
