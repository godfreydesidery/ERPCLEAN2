import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { HrPayrollService } from './hr-payroll.service';
import { DisburseRequest, PayrollLineDto, PayrollRunDto } from './models/hr-payroll.model';

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
