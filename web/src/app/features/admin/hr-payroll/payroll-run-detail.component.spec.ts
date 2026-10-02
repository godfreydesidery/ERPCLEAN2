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
import type { PayrollRunStatutoryReportDto } from './models/payroll-statutory.model';

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

function makeStatutory(overrides: Partial<PayrollRunStatutoryReportDto> = {}): PayrollRunStatutoryReportDto {
  return {
    companyId: '10',
    summary: {
      runUid: 'run-uid-1', runNumber: 'PR-2026-06', periodYear: 2026, periodMonth: 6,
      payDate: '2026-06-30', status: 'POSTED', employeeCount: 1,
      grossTotal: 1200000, payeTotal: 160000, nssfEmployeeTotal: 120000, nssfEmployerTotal: 120000,
      wcfTotal: 6000, sdlTotal: 42000, heslbTotal: 0, netTotal: 920000, employerCostTotal: 168000,
    },
    lines: [
      {
        employeeNumber: 'EMP-001', employeeName: 'Alice Smith', departmentName: null,
        tin: 'TIN-123', nssfNumber: 'NSSF-777', heslbNumber: null,
        grossAmount: 1200000, payeAmount: 160000, nssfEmployeeAmount: 120000,
        nssfEmployerAmount: 120000, wcfAmount: 6000, sdlAmount: 42000, heslbAmount: 0,
        netAmount: 920000,
      },
    ],
    provisional: false,
    currency: 'TZS',
    generatedAt: '2026-06-30T00:00:00Z',
    ...overrides,
  };
}

function makeBed(
  hrService: Partial<{
    getPayrollRunByUid: ReturnType<typeof vi.fn>;
    listPayrollLines: ReturnType<typeof vi.fn>;
    listPayslipsByRun: ReturnType<typeof vi.fn>;
    getStatutorySummary: ReturnType<typeof vi.fn>;
    exportStatutorySummary: ReturnType<typeof vi.fn>;
    downloadEftFile: ReturnType<typeof vi.fn>;
  }> = {},
  permissions: string[] = [],
) {
  const svc = {
    getPayrollRunByUid: vi.fn(() => of(makeRun())),
    listPayrollLines: vi.fn(() => of([])),
    listPayslipsByRun: vi.fn(() => of([makePayslip()])),
    getStatutorySummary: vi.fn(() => of(makeStatutory())),
    exportStatutorySummary: vi.fn(() => of(new Blob())),
    downloadEftFile: vi.fn(() => of(new Blob(['employee_number']))),
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

async function mount(
  hrService: Parameters<typeof makeBed>[0] = {},
  permissions: string[] = [],
) {
  const svc = makeBed(hrService, permissions);
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
  const fixture = TestBed.createComponent(PayrollRunDetailComponent);
  fixture.componentRef.setInput('uid', 'run-uid-1');
  vi.runAllTimers();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, svc, html: () => (fixture.nativeElement as HTMLElement).innerHTML };
}

function findButton(fixture: { nativeElement: unknown }, label: string): HTMLButtonElement | undefined {
  return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) =>
    (b.textContent ?? '').includes(label),
  );
}

describe('PayrollRunDetailComponent — Statutory Summary', () => {
  afterEach(() => {
    vi.clearAllTimers();
    TestBed.resetTestingModule();
  });

  it('renders the per-employee statutory lines with TIN / NSSF numbers and totals', async () => {
    const { svc, html } = await mount();
    expect(svc.getStatutorySummary).toHaveBeenCalledWith('run-uid-1');
    expect(html()).toContain('Statutory Summary');
    expect(html()).toContain('TIN-123');
    expect(html()).toContain('NSSF-777');
    expect(html()).toContain('160,000.00'); // PAYE
    expect(html()).toContain('42,000.00'); // SDL
  });

  it('flags a provisional (not yet approved) summary', async () => {
    const { html } = await mount({
      getPayrollRunByUid: vi.fn(() => of(makeRun({ status: 'CALCULATED' }))),
      getStatutorySummary: vi.fn(() =>
        of(makeStatutory({ provisional: true, summary: { ...makeStatutory().summary, status: 'CALCULATED' } })),
      ),
    });
    expect(html()).toContain('Provisional');
  });

  it('does not ask for a summary of a DRAFT run (no lines yet)', async () => {
    const { svc, html } = await mount({
      getPayrollRunByUid: vi.fn(() => of(makeRun({ status: 'DRAFT' }))),
    });
    expect(svc.getStatutorySummary).not.toHaveBeenCalled();
    expect(html()).not.toContain('Statutory Summary');
  });

  it('offers export only with HR.PAYROLL.VIEW + REPORT.EXPORT', async () => {
    const without = await mount({}, ['HR.PAYROLL.VIEW']);
    expect(findButton(without.fixture, 'Export PDF')).toBeUndefined();
    TestBed.resetTestingModule();

    const withExport = await mount({}, ['HR.PAYROLL.VIEW', 'REPORT.EXPORT']);
    const btn = findButton(withExport.fixture, 'Export PDF');
    expect(btn).toBeDefined();
    btn!.click();
    expect(withExport.svc.exportStatutorySummary).toHaveBeenCalledWith('run-uid-1', 'PDF');
  });
});

describe('PayrollRunDetailComponent — Download bank file', () => {
  afterEach(() => {
    vi.clearAllTimers();
    TestBed.resetTestingModule();
  });

  it('is shown for a POSTED run to a holder of HR.PAYROLL.DISBURSE, and downloads', async () => {
    const { fixture, svc } = await mount({}, ['HR.PAYROLL.DISBURSE']);
    const btn = findButton(fixture, 'Download bank file');
    expect(btn).toBeDefined();
    btn!.click();
    expect(svc.downloadEftFile).toHaveBeenCalledWith('run-uid-1');
  });

  it('is shown for a PAID run', async () => {
    const { fixture } = await mount(
      { getPayrollRunByUid: vi.fn(() => of(makeRun({ status: 'PAID' }))) },
      ['HR.PAYROLL.DISBURSE'],
    );
    expect(findButton(fixture, 'Download bank file')).toBeDefined();
  });

  it('is hidden without HR.PAYROLL.DISBURSE', async () => {
    const { fixture } = await mount({}, ['HR.PAYROLL.VIEW']);
    expect(findButton(fixture, 'Download bank file')).toBeUndefined();
  });

  it.each(['DRAFT', 'CALCULATED', 'APPROVED', 'REVERSED'] as const)(
    'is hidden for a %s run',
    async (status) => {
      const { fixture } = await mount(
        { getPayrollRunByUid: vi.fn(() => of(makeRun({ status }))) },
        ['HR.PAYROLL.DISBURSE'],
      );
      expect(findButton(fixture, 'Download bank file')).toBeUndefined();
    },
  );

  it('shows a friendly message on a 403, never the server text', async () => {
    const { fixture, html } = await mount(
      {
        downloadEftFile: vi.fn(() =>
          throwError(() => new HttpErrorResponse({ status: 403, error: 'AccessDeniedException at x.y.Z' })),
        ),
      },
      ['HR.PAYROLL.DISBURSE'],
    );
    findButton(fixture, 'Download bank file')!.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.bankFileError()).toBe("You don't have permission to download the bank file.");
    expect(html()).not.toContain('AccessDeniedException');
  });

  it('shows a friendly message on another 4xx', async () => {
    const { fixture } = await mount(
      { downloadEftFile: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 409 }))) },
      ['HR.PAYROLL.DISBURSE'],
    );
    findButton(fixture, 'Download bank file')!.click();
    expect(fixture.componentInstance.bankFileError()).toBe(
      'The bank file is not available for this payroll run yet.',
    );
  });
});
