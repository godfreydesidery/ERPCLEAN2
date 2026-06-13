import { HttpErrorResponse } from '@angular/common/http';
import { SlicePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  BudgetDto,
  BudgetVersionDto,
  BudgetVersionStatus,
  CreateBudgetVersionRequest,
} from './models/budgeting.model';
import { BudgetingService } from './budgeting.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Budget detail screen.
 * Shows the budget header + all its versions inline.
 * Version lifecycle actions (submit/recall/approve/reject/re-plan) are shown here.
 * Line editing lives on the budget-version-detail screen.
 * Route: /admin/budgets/uid/:uid
 */
@Component({
  selector: 'app-budget-detail',
  imports: [FormsModule, RouterLink, SlicePipe],
  templateUrl: './budget-detail.component.html',
  styleUrl: './budget-detail.component.scss',
})
export class BudgetDetailComponent {
  private readonly budgetingService = inject(BudgetingService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Budget ─────────────────────────────────────────────────────────────────
  readonly budget = signal<BudgetDto | null>(null);
  readonly budgetState = signal<LoadState>('loading');

  // ── Version action busy flags ───────────────────────────────────────────────
  readonly actionBusyUid = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);

  // ── Reject form ─────────────────────────────────────────────────────────────
  readonly rejectingUid = signal<string | null>(null);
  readonly fRejectReason = signal('');
  readonly rejectError = signal<string | null>(null);

  // ── Approve note ────────────────────────────────────────────────────────────
  readonly approvingUid = signal<string | null>(null);
  readonly fApproveNote = signal('');

  // ── New version form ─────────────────────────────────────────────────────────
  readonly showNewVersionForm = signal(false);
  readonly fVersionLabel = signal('');
  readonly fSeedFromUid = signal('');
  readonly creatingVersion = signal(false);
  readonly newVersionError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('BUDGETING.BUDGET.MANAGE'));
  readonly canSubmit = computed(() => this.session.hasPermission('BUDGETING.BUDGET.SUBMIT'));
  readonly canApprove = computed(() => this.session.hasPermission('BUDGETING.BUDGET.APPROVE'));

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadBudget();
  }

  private loadBudget(): void {
    this.budgetState.set('loading');
    this.budgetingService.getByUid(this.uid()).subscribe({
      next: (b) => {
        this.budget.set(b);
        this.budgetState.set('idle');
      },
      error: () => this.budgetState.set('error'),
    });
  }

  // ── Version lifecycle actions ─────────────────────────────────────────────

  submitVersion(versionUid: string): void {
    if (this.actionBusyUid()) return;
    this.actionBusyUid.set(versionUid);
    this.actionError.set(null);
    this.budgetingService.submit(versionUid).subscribe({
      next: (updated) => {
        this.actionBusyUid.set(null);
        this.alerts.success('Version submitted for approval');
        this.replaceVersion(updated);
      },
      error: (err) => {
        this.actionBusyUid.set(null);
        this.actionError.set(this.messageFrom(err, 'Could not submit version.'));
      },
    });
  }

  recallVersion(versionUid: string): void {
    if (this.actionBusyUid()) return;
    this.actionBusyUid.set(versionUid);
    this.actionError.set(null);
    this.budgetingService.recall(versionUid).subscribe({
      next: (updated) => {
        this.actionBusyUid.set(null);
        this.alerts.success('Version recalled to Draft');
        this.replaceVersion(updated);
      },
      error: (err) => {
        this.actionBusyUid.set(null);
        this.actionError.set(this.messageFrom(err, 'Could not recall version.'));
      },
    });
  }

  startApprove(versionUid: string): void {
    this.approvingUid.set(versionUid);
    this.fApproveNote.set('');
    this.rejectingUid.set(null);
  }

  confirmApprove(): void {
    const versionUid = this.approvingUid();
    if (!versionUid || this.actionBusyUid()) return;
    this.actionBusyUid.set(versionUid);
    this.actionError.set(null);
    const note = this.fApproveNote().trim();
    this.budgetingService.approve(versionUid, note ? { note } : undefined).subscribe({
      next: (updated) => {
        this.actionBusyUid.set(null);
        this.approvingUid.set(null);
        this.alerts.success('Version approved');
        // Reload full budget to reflect superseded versions
        this.loadBudget();
      },
      error: (err) => {
        this.actionBusyUid.set(null);
        this.actionError.set(this.messageFrom(err, 'Could not approve version.'));
      },
    });
  }

  cancelApprove(): void { this.approvingUid.set(null); }

  startReject(versionUid: string): void {
    this.rejectingUid.set(versionUid);
    this.fRejectReason.set('');
    this.rejectError.set(null);
    this.approvingUid.set(null);
  }

  confirmReject(): void {
    const versionUid = this.rejectingUid();
    if (!versionUid || this.actionBusyUid()) return;
    const reason = this.fRejectReason().trim();
    if (!reason) { this.rejectError.set('Rejection reason is required.'); return; }
    this.actionBusyUid.set(versionUid);
    this.rejectError.set(null);
    this.budgetingService.reject(versionUid, { reason }).subscribe({
      next: (updated) => {
        this.actionBusyUid.set(null);
        this.rejectingUid.set(null);
        this.alerts.success('Version rejected');
        this.replaceVersion(updated);
      },
      error: (err) => {
        this.actionBusyUid.set(null);
        this.rejectError.set(this.messageFrom(err, 'Could not reject version.'));
      },
    });
  }

  cancelReject(): void { this.rejectingUid.set(null); }

  // ── New version / re-plan ─────────────────────────────────────────────────

  toggleNewVersionForm(): void {
    this.showNewVersionForm.update((v) => !v);
    if (!this.showNewVersionForm()) {
      this.fVersionLabel.set('');
      this.fSeedFromUid.set('');
      this.newVersionError.set(null);
    }
  }

  createVersion(): void {
    if (this.creatingVersion()) return;
    this.creatingVersion.set(true);
    this.newVersionError.set(null);

    const request: CreateBudgetVersionRequest = {
      label: this.fVersionLabel().trim() || undefined,
      seedFromVersionUid: this.fSeedFromUid().trim() || undefined,
    };

    this.budgetingService.createVersion(this.uid(), request).subscribe({
      next: () => {
        this.creatingVersion.set(false);
        this.showNewVersionForm.set(false);
        this.fVersionLabel.set('');
        this.fSeedFromUid.set('');
        this.alerts.success('New budget version created');
        this.loadBudget();
      },
      error: (err) => {
        this.creatingVersion.set(false);
        this.newVersionError.set(this.messageFrom(err, 'Could not create new version.'));
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────

  private replaceVersion(updated: BudgetVersionDto): void {
    this.budget.update((b) => {
      if (!b) return b;
      return {
        ...b,
        versions: b.versions.map((v) => (v.uid === updated.uid ? updated : v)),
      };
    });
  }

  statusBadgeClass(status: BudgetVersionStatus): string {
    switch (status) {
      case 'APPROVED': return 'text-bg-success';
      case 'SUBMITTED': return 'text-bg-primary';
      case 'REJECTED': return 'text-bg-danger';
      case 'SUPERSEDED': return 'text-bg-secondary';
      default: return 'text-bg-warning'; // DRAFT
    }
  }

  totalLines(version: BudgetVersionDto): number {
    return version.lines?.length ?? 0;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
