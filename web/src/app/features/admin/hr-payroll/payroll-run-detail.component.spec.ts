/**
 * PayrollRunDetailComponent — Payslips section spec.
 *
 * Covers the new "Payslips" panel on the run detail: loads listPayslipsByRun(uid), renders
 * employee name + number and thousand-separated money (via formatMoney), a "View" link per row
 * routing to /admin/hr/payslips/uid/:uid, the empty state (no payslips yet), and the error state.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { HrPayrollService } from './hr-payroll.service';
import { PayrollRunDetailComponent } from './payroll-run-detail.component';
import type { PayrollRunDto, PayslipDto } from './models/hr-payroll.model';

vi.useFakeTimers();

function makeRun(overrides: Partial<PayrollRunDto> = {}): PayrollRunDto {
  return {
    id: '1', uid: 'run-uid-1', companyId: '10', branchId: '20', runNumber: 'PR-2026-06',
    periodYear: 2026, periodMonth: 6, payDate: '2026-06-30', status: 'POSTED',
    grossTotal: '1000000', deductionTotal: '200000', netTotal: '800000', employerCostTotal: '150000',
    calculatedAt: null, approvedAt: null, postedAt: '2026-06-30T00:00:00Z', paidAt: null,
    reversedAt: null, approvedBy: null, postedBy: null, glEntryUid: null, reversalOfRunUid: null,
    ...overrides,
  };
}

function makePayslip(overrides: Partial<PayslipDto> = {}): PayslipDto {
  return {
    id: '1', uid: 'payslip-uid-1', companyId: '10', payrollRunId: '1', payrollLineId: '1',
    employeeId: '5', employeeName: 'Alice Smith', employeeNumber: 'EMP-001',
    payslipNumber: 'PS-2026-06-001', payDate: '2026-06-30',
    grossAmount: 1234567.89, deductionAmount: 234567.89, netAmount: 1000000,
    employerCostAmount: 150000, ytdGross: 5000000, ytdPaye: 400000, ytdNssfEmployee: 300000,
    ytdNet: 4000000,
    ...overrides,
  };
}

function makeBed(
  hrService: Partial<{
    getPayrollRunByUid: ReturnType<typeof vi.fn>;
    listPayrollLines: ReturnType<typeof vi.fn>;
    listPayslipsByRun: ReturnType<typeof vi.fn>;
  }> = {},
) {
  const svc = {
    getPayrollRunByUid: vi.fn(() => of(makeRun())),
    listPayrollLines: vi.fn(() => of([])),
    listPayslipsByRun: vi.fn(() => of([makePayslip()])),
    ...hrService,
  };

  TestBed.configureTestingModule({
    imports: [PayrollRunDetailComponent],
    providers: [
      provideRouter([]),
      { provide: HrPayrollService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => false),
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

describe('PayrollRunDetailComponent — Payslips section', () => {
  afterEach(() => {
    vi.clearAllTimers();
    TestBed.resetTestingModule();
  });

  it('loads payslips for the run and renders employee name + number and separated money', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PayrollRunDetailComponent);
    fixture.componentRef.setInput('uid', 'run-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.payslipsState()).toBe('idle');
    expect(comp.payslips().length).toBe(1);

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('Alice Smith');
    expect(html).toContain('EMP-001');
    // formatMoney renders thousand separators + 2dp
    expect(html).toContain('1,234,567.89');
    expect(html).toContain('1,000,000.00');
    expect(html).toContain('/admin/hr/payslips/uid/payslip-uid-1');
  });

  it('shows an empty state when the run has not generated payslips yet', async () => {
    makeBed({ listPayslipsByRun: vi.fn(() => of([])) });
    const fixture = TestBed.createComponent(PayrollRunDetailComponent);
    fixture.componentRef.setInput('uid', 'run-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.payslips().length).toBe(0);
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('No payslips yet');
  });

  it('sets payslipsState = "error" when the payslips call fails', async () => {
    makeBed({ listPayslipsByRun: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 }))) });
    const fixture = TestBed.createComponent(PayrollRunDetailComponent);
    fixture.componentRef.setInput('uid', 'run-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.payslipsState()).toBe('error');
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('Could not load payslips.');
  });
});
