import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { formatMoney } from '../../../shared/money.util';
import { downloadBlob } from '../reporting/reporting.utils';
import { HrPayrollService } from './hr-payroll.service';
import {
  PayrollStatutoryPeriodReportDto,
  StatutoryExportFormat,
} from './models/payroll-statutory.model';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden' | 'invalid';

/**
 * Payroll Statutory Report (FR-HR-23). Route: /admin/reports/payroll-statutory — gated
 * HR.PAYROLL.VIEW (the same code as the backend endpoint and the nav entry).
 *
 * One row per APPROVED / POSTED / PAID payroll run whose pay date falls in the chosen range, with
 * PAYE / NSSF / WCF / SDL / HESLB totals for filing. Unapproved and reversed runs are left out and
 * counted so the reader knows. The company comes from the session server-side — nothing is sent.
 */
@Component({
  selector: 'app-payroll-statutory-report',
  imports: [FormsModule, DatePipe, RouterLink],
  templateUrl: './payroll-statutory-report.component.html',
  styleUrl: './payroll-statutory-report.component.scss',
})
export class PayrollStatutoryReportComponent {
  private readonly hrService = inject(HrPayrollService);
  protected readonly session = inject(SessionStore);

  readonly fromDate = signal(this.firstDayOfYear());
  readonly toDate = signal(this.today());

  readonly report = signal<PayrollStatutoryPeriodReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly errorMessage = signal<string | null>(null);
  readonly exporting = signal(false);
  readonly exportError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('HR.PAYROLL.VIEW'));
  /** The export endpoint is HR.PAYROLL.VIEW AND REPORT.EXPORT server-side. */
  readonly canExport = computed(
    () => this.session.hasPermission('HR.PAYROLL.VIEW') && this.session.hasPermission('REPORT.EXPORT'),
  );
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  readonly fmtMoney = formatMoney;

  run(): void {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!from || !to) return;

    this.state.set('loading');
    this.report.set(null);
    this.errorMessage.set(null);
    this.hrService.getStatutoryPeriodReport(from, to).subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 403) {
          this.state.set('forbidden');
          return;
        }
        if (err instanceof HttpErrorResponse && err.status === 400) {
          // A 400 carries a user-safe message ("The end date cannot be before the start date.").
          const errors = (err.error as { errors?: unknown[] } | null)?.errors;
          const first = errors?.length ? errors[0] : null;
          this.errorMessage.set(typeof first === 'string' ? first : 'Check the dates and try again.');
          this.state.set('invalid');
          return;
        }
        this.state.set('error');
      },
    });
  }

  export(format: StatutoryExportFormat): void {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!from || !to || this.exporting()) return;

    this.exporting.set(true);
    this.exportError.set(null);
    this.hrService.exportStatutoryPeriodReport(from, to, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `payroll-statutory_${from}_${to}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: (err: unknown) => {
        this.exporting.set(false);
        const status = err instanceof HttpErrorResponse ? err.status : 0;
        this.exportError.set(
          status === 401 || status === 403
            ? "You don't have permission to export this report."
            : 'Could not export the report. Please try again.',
        );
      },
    });
  }

  /** Sum of two JSON-number amounts (guards against a string sneaking through). */
  add(a: number, b: number): number {
    return +a + +b;
  }

  period(year: number, month: number): string {
    return `${year}-${String(month).padStart(2, '0')}`;
  }

  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private firstDayOfYear(): string {
    return `${new Date().getFullYear()}-01-01`;
  }
}
