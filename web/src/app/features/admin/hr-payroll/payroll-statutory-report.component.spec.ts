/**
 * PayrollStatutoryReportComponent — behaviour specs (FR-HR-23).
 *
 * Covers: run() calls the period endpoint with the chosen dates; totals and the "not included"
 * note render; 403 → forbidden; 400 → the server's user-safe message; export gating
 * (HR.PAYROLL.VIEW + REPORT.EXPORT) and the export call.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { SessionStore } from '../../../core/auth/session.store';
import { HrPayrollService } from './hr-payroll.service';
import { PayrollStatutoryReportComponent } from './payroll-statutory-report.component';
import type { PayrollStatutoryPeriodReportDto } from './models/payroll-statutory.model';

const MOCK_PERIOD_REPORT: PayrollStatutoryPeriodReportDto = {
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
  reversedRunCount: 1,
  currency: 'TZS',
  generatedAt: '2026-09-01T08:00:00Z',
};

function makeBed(svcOverrides: Record<string, unknown> = {}, permissions = ['HR.PAYROLL.VIEW']) {
  const svc = {
    getStatutoryPeriodReport: vi.fn(() => of(MOCK_PERIOD_REPORT)),
    exportStatutoryPeriodReport: vi.fn(() => of(new Blob())),
    ...svcOverrides,
  };
  TestBed.configureTestingModule({
    imports: [PayrollStatutoryReportComponent],
    providers: [
      provideRouter([]),
      { provide: HrPayrollService, useValue: svc },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((code: string) => permissions.includes(code)),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return svc;
}

describe('PayrollStatutoryReportComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('runs the period report with the chosen pay dates and renders totals', () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(PayrollStatutoryReportComponent);
    const comp = fixture.componentInstance;
    comp.fromDate.set('2026-06-01');
    comp.toDate.set('2026-08-31');
    comp.run();
    fixture.detectChanges();

    expect(svc.getStatutoryPeriodReport).toHaveBeenCalledWith('2026-06-01', '2026-08-31');
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('PR-00001');
    expect(html).toContain('210,000.00'); // PAYE
    expect(html).toContain('274,750.00'); // PAYE + SDL due to TRA
    expect(html).toContain('370,000.00'); // NSSF employee + employer
    expect(html).toContain('not approved yet');
    expect(html).toContain('reversed run');
  });

  it('sets forbidden on a 403', () => {
    makeBed({
      getStatutoryPeriodReport: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))),
    });
    const comp = TestBed.createComponent(PayrollStatutoryReportComponent).componentInstance;
    comp.run();
    expect(comp.state()).toBe('forbidden');
  });

  it('shows the server message for an invalid range (400)', () => {
    makeBed({
      getStatutoryPeriodReport: vi.fn(() =>
        throwError(() => new HttpErrorResponse({
          status: 400, error: { errors: ['The end date cannot be before the start date.'] },
        })),
      ),
    });
    const comp = TestBed.createComponent(PayrollStatutoryReportComponent).componentInstance;
    comp.run();
    expect(comp.state()).toBe('invalid');
    expect(comp.errorMessage()).toBe('The end date cannot be before the start date.');
  });

  it('hides export without REPORT.EXPORT', () => {
    makeBed();
    const comp = TestBed.createComponent(PayrollStatutoryReportComponent).componentInstance;
    expect(comp.canExport()).toBe(false);
  });

  it('exports with the same dates when REPORT.EXPORT is held', () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const svc = makeBed({}, ['HR.PAYROLL.VIEW', 'REPORT.EXPORT']);
    const comp = TestBed.createComponent(PayrollStatutoryReportComponent).componentInstance;
    expect(comp.canExport()).toBe(true);
    comp.fromDate.set('2026-06-01');
    comp.toDate.set('2026-08-31');
    comp.export('XLSX');
    expect(svc.exportStatutoryPeriodReport).toHaveBeenCalledWith('2026-06-01', '2026-08-31', 'XLSX');
  });
});
