/**
 * PayslipDetailComponent — unit spec.
 *
 * Covers the load triad (loading/idle/error) and renders gross/deductions/net/employer-cost +
 * the YTD block (gross/PAYE/NSSF-employee/net) with thousand-separated money via formatMoney.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { HrPayrollService } from './hr-payroll.service';
import { PayslipDetailComponent } from './payslip-detail.component';
import type { PayslipDto } from './models/hr-payroll.model';

vi.useFakeTimers();

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
  hrService: Partial<{ getPayslipByUid: ReturnType<typeof vi.fn> }> = {},
) {
  const svc = {
    getPayslipByUid: vi.fn(() => of(makePayslip())),
    ...hrService,
  };

  TestBed.configureTestingModule({
    imports: [PayslipDetailComponent],
    providers: [
      provideRouter([]),
      { provide: HrPayrollService, useValue: svc },
    ],
  });
  return svc;
}

describe('PayslipDetailComponent', () => {
  afterEach(() => {
    vi.clearAllTimers();
    TestBed.resetTestingModule();
  });

  it('loads the payslip by uid', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(PayslipDetailComponent);
    fixture.componentRef.setInput('uid', 'payslip-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.getPayslipByUid).toHaveBeenCalledWith('payslip-uid-1');
    expect(fixture.componentInstance.state()).toBe('idle');
    expect(fixture.componentInstance.payslip()?.payslipNumber).toBe('PS-2026-06-001');
  });

  it('renders gross/deductions/net/employer-cost and the YTD block with separated money', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PayslipDetailComponent);
    fixture.componentRef.setInput('uid', 'payslip-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('Alice Smith');
    expect(html).toContain('EMP-001');
    expect(html).toContain('PS-2026-06-001');
    expect(html).toContain('1,234,567.89'); // gross
    expect(html).toContain('234,567.89'); // deductions
    expect(html).toContain('1,000,000.00'); // net
    expect(html).toContain('150,000.00'); // employer cost
    expect(html).toContain('5,000,000.00'); // YTD gross
    expect(html).toContain('400,000.00'); // YTD PAYE
    expect(html).toContain('300,000.00'); // YTD NSSF (employee)
    expect(html).toContain('4,000,000.00'); // YTD net
  });

  it('sets state = "error" when the payslip fails to load (never renders raw numeric id)', async () => {
    makeBed({ getPayslipByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 404 }))) });
    const fixture = TestBed.createComponent(PayslipDetailComponent);
    fixture.componentRef.setInput('uid', 'payslip-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('error');
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('Could not load payslip');
  });
});
