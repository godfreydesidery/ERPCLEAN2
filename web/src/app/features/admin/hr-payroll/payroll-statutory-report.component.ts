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
  StatutoryLiabilityBalanceDto,
} from './models/payroll-statutory.model';
import { CashbankService } from '../cashbank/cashbank.service';
import { CashAccountOptionDto } from '../cashbank/models/cashbank.model';

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
  private readonly cashbankService = inject(CashbankService);
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

  // ── ACC-07: pay statutory liabilities ─────────────────────────────────────────
  /** Paying is HR.PAYROLL.DISBURSE ("disburse net wages and statutory payables via Cash & Bank"). */
  readonly canPay = computed(() => this.session.hasPermission('HR.PAYROLL.DISBURSE'));
  readonly outstanding = signal<StatutoryLiabilityBalanceDto[]>([]);
  readonly outstandingState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly payFor = signal<StatutoryLiabilityBalanceDto | null>(null);
  readonly cashAccounts = signal<CashAccountOptionDto[]>([]);
  readonly payAccountUid = signal('');
  readonly payDate = signal(this.today());
  readonly payAmount = signal<string | number>('');
  readonly payRef = signal('');
  readonly paying = signal(false);
  readonly payError = signal<string | null>(null);

  constructor() {
    if (this.canPay()) this.loadOutstanding();
  }

  loadOutstanding(): void {
    this.outstandingState.set('loading');
    this.hrService.getStatutoryOutstanding().subscribe({
      next: (list) => {
        this.outstanding.set(list ?? []);
        this.outstandingState.set('idle');
      },
      error: () => this.outstandingState.set('error'),
    });
  }

  openPay(row: StatutoryLiabilityBalanceDto): void {
    this.payFor.set(row);
    this.payError.set(null);
    this.payAmount.set(+row.outstanding);
    this.payRef.set('');
    this.payDate.set(this.today());
    if (this.cashAccounts().length === 0) {
      this.cashbankService.listAccountOptions(String(row.companyId)).subscribe({
        next: (list) => {
          this.cashAccounts.set(list ?? []);
          const preferred = list.find((a) => a.accountType === 'BANK' && a.isDefault)
            ?? list.find((a) => a.isDefault);
          if (preferred && !this.payAccountUid()) this.payAccountUid.set(preferred.uid);
        },
        error: () => this.payError.set('Could not load the cash and bank accounts.'),
      });
    }
  }

  /** Something is still owed on this liability (BigDecimal on the wire — coerce). */
  owes(row: StatutoryLiabilityBalanceDto): boolean {
    return +row.outstanding > 0.005;
  }

  cancelPay(): void {
    this.payFor.set(null);
    this.payError.set(null);
  }

  submitPay(): void {
    const row = this.payFor();
    if (!row) return;
    const amount = String(this.payAmount() ?? '').trim();
    if (!this.payAccountUid()) { this.payError.set('Choose the account the payment was made from.'); return; }
    if (!this.payDate()) { this.payError.set('Payment date is required.'); return; }
    if (!(+amount > 0)) { this.payError.set('Enter a positive amount.'); return; }
    if (+amount > +row.outstanding + 0.005) {
      this.payError.set(`That is more than the ${row.liability} still owed.`);
      return;
    }
    this.paying.set(true);
    this.payError.set(null);
    this.hrService.payStatutory({
      liability: row.liability,
      cashBankAccountUid: this.payAccountUid(),
      paymentDate: this.payDate(),
      amount,
      reference: String(this.payRef() ?? '').trim() || undefined,
    }).subscribe({
      next: () => {
        this.paying.set(false);
        this.payFor.set(null);
        this.loadOutstanding();
      },
      error: (err: unknown) => {
        this.paying.set(false);
        const errors = err instanceof HttpErrorResponse
          ? (err.error as { errors?: unknown[] } | null)?.errors : undefined;
        const first = errors?.length ? errors[0] : null;
        this.payError.set(typeof first === 'string' ? first : 'Could not record the payment.');
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
