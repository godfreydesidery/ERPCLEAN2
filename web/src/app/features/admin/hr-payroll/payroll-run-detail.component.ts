import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { formatMoney } from '../../../shared/money.util';
import { downloadBlob } from '../reporting/reporting.utils';
import { HrPayrollService } from './hr-payroll.service';
import { DisburseRequest, PayrollLineDto, PayrollRunDto, PayslipDto } from './models/hr-payroll.model';
import { PayrollRunStatutoryReportDto, StatutoryExportFormat } from './models/payroll-statutory.model';

/**
 * Payroll run detail screen with full lifecycle actions:
 * calculate -> approve -> post -> disburse -> reverse
 * Route: /admin/hr/payroll-runs/uid/:uid
 */
@Component({
  selector: 'app-payroll-run-detail',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './payroll-run-detail.component.html',
  styleUrl: './payroll-run-detail.component.scss',
})
export class PayrollRunDetailComponent {
  private readonly hrService = inject(HrPayrollService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly run = signal<PayrollRunDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');

  readonly lines = signal<PayrollLineDto[]>([]);
  readonly linesState = signal<'loading' | 'idle' | 'error'>('idle');

  readonly payslips = signal<PayslipDto[]>([]);
  readonly payslipsState = signal<'loading' | 'idle' | 'error'>('idle');

  // ── Statutory summary (FR-HR-23) ─────────────────────────────────────────────
  readonly statutory = signal<PayrollRunStatutoryReportDto | null>(null);
  readonly statutoryState = signal<'loading' | 'idle' | 'error'>('idle');
  readonly exportingStatutory = signal(false);
  readonly statutoryExportError = signal<string | null>(null);

  // ── Bank (EFT) file ──────────────────────────────────────────────────────────
  readonly downloadingBankFile = signal(false);
  readonly bankFileError = signal<string | null>(null);

  /** Coerce + format money with thousand separators (shared util). */
  readonly fmtMoney = formatMoney;

  // ── Disburse form ────────────────────────────────────────────────────────────
  readonly showDisburseForm = signal(false);
  readonly fCashBankAccountUid = signal('');
  readonly fTxnDate = signal('');
  readonly disbursing = signal(false);
  readonly disburseError = signal<string | null>(null);

  // ── Action busy state ────────────────────────────────────────────────────────
  readonly calculating = signal(false);
  readonly approving = signal(false);
  readonly posting = signal(false);
  readonly reversing = signal(false);
  readonly actionError = signal<string | null>(null);

  // ── Permissions ──────────────────────────────────────────────────────────────
  readonly canRun = computed(() => this.session.hasPermission('HR.PAYROLL.RUN'));
  readonly canApprove = computed(() => this.session.hasPermission('HR.PAYROLL.APPROVE'));
  readonly canPost = computed(() => this.session.hasPermission('HR.PAYROLL.POST'));
  readonly canDisburse = computed(() => this.session.hasPermission('HR.PAYROLL.DISBURSE'));
  readonly canReverse = computed(() => this.session.hasPermission('HR.PAYROLL.REVERSE'));
  /** Statutory export = the screen's own gate (route guard HR.PAYROLL.VIEW) + REPORT.EXPORT. */
  readonly canExportStatutory = computed(
    () => this.session.hasPermission('HR.PAYROLL.VIEW') && this.session.hasPermission('REPORT.EXPORT'),
  );

  // ── Status predicates for button visibility ──────────────────────────────────
  readonly canCalculate = computed(() => {
    const s = this.run()?.status;
    return s === 'DRAFT' || s === 'CALCULATED' || s === 'APPROVED';
  });
  readonly canApproveAction = computed(() => this.run()?.status === 'CALCULATED');
  readonly canPostAction = computed(() => this.run()?.status === 'APPROVED');
  readonly canDisburseAction = computed(() => {
    const r = this.run();
    return r?.status === 'POSTED' && parseFloat(r.netTotal ?? '0') > 0;
  });
  readonly canReverseAction = computed(() => {
    const s = this.run()?.status;
    return s === 'POSTED' || s === 'PAID';
  });

  /**
   * The bank file is offered once the run is posted (POSTED, then PAID): before that the net pay can
   * still change, and a reversed run must not be paid. The endpoint itself does not refuse earlier
   * statuses, so this screen is where a premature bank upload is prevented.
   */
  readonly canBankFileAction = computed(() => {
    const s = this.run()?.status;
    return s === 'POSTED' || s === 'PAID';
  });

  readonly hasFlaggedLines = computed(() => this.lines().some((l) => l.status === 'FLAGGED'));

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadRun();
  }

  private loadRun(): void {
    this.state.set('loading');
    this.hrService.getPayrollRunByUid(this.uid()).subscribe({
      next: (r) => {
        this.run.set(r);
        this.state.set('idle');
        this.loadLines();
        this.loadPayslips();
      },
      error: () => this.state.set('error'),
    });
  }

  private loadLines(): void {
    this.linesState.set('loading');
    this.hrService.listPayrollLines(this.uid()).subscribe({
      next: (lines) => {
        this.lines.set(lines);
        this.linesState.set('idle');
      },
      error: () => this.linesState.set('error'),
    });
    this.loadStatutory();
  }

  /** Statutory summary — skipped for a DRAFT run, which has no lines to summarise yet. */
  private loadStatutory(): void {
    if (this.run()?.status === 'DRAFT') {
      this.statutory.set(null);
      this.statutoryState.set('idle');
      return;
    }
    this.statutoryState.set('loading');
    this.hrService.getStatutorySummary(this.uid()).subscribe({
      next: (dto) => {
        this.statutory.set(dto);
        this.statutoryState.set('idle');
      },
      error: () => this.statutoryState.set('error'),
    });
  }

  exportStatutory(format: StatutoryExportFormat): void {
    const r = this.run();
    if (!r || this.exportingStatutory()) return;
    this.exportingStatutory.set(true);
    this.statutoryExportError.set(null);
    this.hrService.exportStatutorySummary(this.uid(), format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `payroll-statutory_${r.runNumber}.${format.toLowerCase()}`);
        this.exportingStatutory.set(false);
      },
      error: (err) => {
        this.exportingStatutory.set(false);
        this.statutoryExportError.set(this.downloadMessage(err, 'the statutory summary'));
      },
    });
  }

  downloadBankFile(): void {
    const r = this.run();
    if (!r || this.downloadingBankFile()) return;
    this.downloadingBankFile.set(true);
    this.bankFileError.set(null);
    this.hrService.downloadEftFile(this.uid()).subscribe({
      next: (blob) => {
        downloadBlob(blob, `bank-file_${r.runNumber}.csv`);
        this.downloadingBankFile.set(false);
      },
      error: (err) => {
        this.downloadingBankFile.set(false);
        this.bankFileError.set(this.downloadMessage(err, 'the bank file'));
      },
    });
  }

  /**
   * A blob download's error body is a Blob, not the JSON envelope, so the server's errors[] is not
   * readable here — the message is chosen by status instead, and never echoes server detail.
   */
  private downloadMessage(err: unknown, what: string): string {
    const status = err instanceof HttpErrorResponse ? err.status : 0;
    if (status === 401 || status === 403) {
      return `You don't have permission to download ${what}.`;
    }
    if (status === 404) {
      return 'This payroll run could not be found. Refresh the page and try again.';
    }
    if (status >= 400 && status < 500) {
      return `${what.charAt(0).toUpperCase()}${what.slice(1)} is not available for this payroll run yet.`;
    }
    return `Could not download ${what}. Please try again.`;
  }

  private loadPayslips(): void {
    this.payslipsState.set('loading');
    this.hrService.listPayslipsByRun(this.uid()).subscribe({
      next: (payslips) => {
        this.payslips.set(payslips);
        this.payslipsState.set('idle');
      },
      error: () => this.payslipsState.set('error'),
    });
  }

  calculate(): void {
    if (this.calculating()) return;
    this.calculating.set(true);
    this.actionError.set(null);
    this.hrService.calculatePayrollRun(this.uid()).subscribe({
      next: (updated) => {
        this.run.set(updated);
        this.calculating.set(false);
        this.alerts.success('Payroll calculated', updated.runNumber);
        this.loadLines();
      },
      error: (err) => {
        this.calculating.set(false);
        this.actionError.set(this.messageFrom(err, 'Could not calculate payroll.'));
      },
    });
  }

  approve(): void {
    if (this.approving()) return;
    this.approving.set(true);
    this.actionError.set(null);
    this.hrService.approvePayrollRun(this.uid()).subscribe({
      next: (updated) => {
        this.run.set(updated);
        this.approving.set(false);
        this.alerts.success('Payroll approved', updated.runNumber);
        this.loadStatutory(); // no longer provisional
      },
      error: (err) => {
        this.approving.set(false);
        this.actionError.set(this.messageFrom(err, 'Could not approve payroll. Check for flagged lines.'));
      },
    });
  }

  post(): void {
    if (this.posting()) return;
    this.posting.set(true);
    this.actionError.set(null);
    this.hrService.postPayrollRun(this.uid()).subscribe({
      next: (updated) => {
        this.run.set(updated);
        this.posting.set(false);
        this.alerts.success('Payroll posted', updated.runNumber);
      },
      error: (err) => {
        this.posting.set(false);
        this.actionError.set(this.messageFrom(err, 'Could not post payroll.'));
      },
    });
  }

  toggleDisburseForm(): void {
    this.showDisburseForm.update((v) => !v);
    this.disburseError.set(null);
    if (!this.showDisburseForm()) {
      this.fCashBankAccountUid.set('');
      this.fTxnDate.set('');
    }
  }

  disburse(): void {
    const cashBankAccountUid = this.fCashBankAccountUid().trim();
    if (!cashBankAccountUid) { this.disburseError.set('Cash/Bank account UID is required.'); return; }
    if (this.disbursing()) return;
    this.disbursing.set(true);
    this.disburseError.set(null);

    const request: DisburseRequest = {
      cashBankAccountUid,
      txnDate: this.fTxnDate().trim() || undefined,
    };

    this.hrService.disbursePayrollRun(this.uid(), request).subscribe({
      next: (updated) => {
        this.run.set(updated);
        this.disbursing.set(false);
        this.showDisburseForm.set(false);
        this.fCashBankAccountUid.set('');
        this.fTxnDate.set('');
        this.alerts.success('Payroll disbursed', updated.runNumber);
      },
      error: (err) => {
        this.disbursing.set(false);
        this.disburseError.set(this.messageFrom(err, 'Could not disburse payroll.'));
      },
    });
  }

  reverse(): void {
    if (this.reversing()) return;
    this.reversing.set(true);
    this.actionError.set(null);
    this.hrService.reversePayrollRun(this.uid()).subscribe({
      next: (updated) => {
        this.run.set(updated);
        this.reversing.set(false);
        this.alerts.success('Payroll reversed', updated.runNumber);
        this.loadStatutory(); // a reversed run's figures must not be filed
      },
      error: (err) => {
        this.reversing.set(false);
        this.actionError.set(this.messageFrom(err, 'Could not reverse payroll.'));
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
