import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  ApprovalRequestDto,
  ApprovalRequestStatus,
  ApprovalStepStatus,
  DecideRequest,
} from './models/approvals.model';
import { ApprovalsService } from './approvals.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Approval request detail + lifecycle actions.
 * Route: /admin/approvals/requests/uid/:uid
 *
 * Lifecycle actions (state-gated):
 *   APPROVE  — APPROVALS.DECIDE + request PENDING
 *   REJECT   — APPROVALS.DECIDE + request PENDING
 *   RECALL   — APPROVALS.REQUEST.VIEW + request PENDING (submitter or APPROVALS.ADMIN)
 *   CANCEL   — APPROVALS.ADMIN + request non-terminal
 */
@Component({
  selector: 'app-approval-request-detail',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe],
  templateUrl: './approval-request-detail.component.html',
  styleUrl: './approval-request-detail.component.scss',
})
export class ApprovalRequestDetailComponent {
  private readonly approvalsService = inject(ApprovalsService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Entity header ─────────────────────────────────────────────────────────
  readonly request = signal<ApprovalRequestDto | null>(null);
  readonly requestState = signal<LoadState>('loading');

  // ── Approve action ────────────────────────────────────────────────────────
  readonly approveComment = signal('');
  readonly approving = signal(false);
  readonly approveError = signal<string | null>(null);
  readonly showApproveForm = signal(false);

  // ── Reject action ─────────────────────────────────────────────────────────
  readonly rejectComment = signal('');
  readonly rejecting = signal(false);
  readonly rejectError = signal<string | null>(null);
  readonly showRejectForm = signal(false);

  // ── Recall action ─────────────────────────────────────────────────────────
  readonly recalling = signal(false);
  readonly recallError = signal<string | null>(null);

  // ── Cancel action ─────────────────────────────────────────────────────────
  readonly cancelling = signal(false);
  readonly cancelError = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canDecide = computed(() => this.session.hasPermission('APPROVALS.DECIDE'));
  readonly canRecall = computed(() => this.session.hasPermission('APPROVALS.REQUEST.VIEW'));
  readonly canAdmin = computed(() => this.session.hasPermission('APPROVALS.ADMIN'));

  // ── Derived state preconditions ───────────────────────────────────────────
  readonly isPending = computed(() => this.request()?.status === 'PENDING');
  readonly isTerminal = computed(() => {
    const s = this.request()?.status;
    return s === 'APPROVED' || s === 'REJECTED' || s === 'RECALLED' || s === 'CANCELLED';
  });

  readonly canApproveAction = computed(() => this.canDecide() && this.isPending());
  readonly canRejectAction = computed(() => this.canDecide() && this.isPending());
  readonly canRecallAction = computed(() => this.canRecall() && this.isPending());
  readonly canCancelAction = computed(() => this.canAdmin() && !this.isTerminal());

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadRequest();
  }

  private loadRequest(): void {
    this.requestState.set('loading');
    this.approvalsService.getRequestByUid(this.uid()).subscribe({
      next: (r) => {
        this.request.set(r);
        this.requestState.set('idle');
      },
      error: () => this.requestState.set('error'),
    });
  }

  private refetch(): void {
    this.approvalsService.getRequestByUid(this.uid()).subscribe({
      next: (r) => this.request.set(r),
      error: () => undefined,
    });
  }

  // ── Approve ───────────────────────────────────────────────────────────────

  toggleApproveForm(): void {
    this.showApproveForm.update((v) => !v);
    this.approveError.set(null);
    this.showRejectForm.set(false);
  }

  approve(): void {
    if (this.approving()) return;
    this.approving.set(true);
    this.approveError.set(null);
    const body: DecideRequest = { action: 'APPROVE', comment: this.approveComment().trim() || null };
    this.approvalsService.approveRequest(this.uid(), body).subscribe({
      next: (updated) => {
        this.request.set(updated);
        this.approving.set(false);
        this.showApproveForm.set(false);
        this.approveComment.set('');
        this.alerts.success('Request approved', updated.requestNumber);
      },
      error: (err) => {
        this.approveError.set(this.messageFrom(err, 'Could not approve the request.'));
        this.approving.set(false);
      },
    });
  }

  // ── Reject ────────────────────────────────────────────────────────────────

  toggleRejectForm(): void {
    this.showRejectForm.update((v) => !v);
    this.rejectError.set(null);
    this.showApproveForm.set(false);
  }

  reject(): void {
    if (this.rejecting()) return;
    this.rejecting.set(true);
    this.rejectError.set(null);
    const body: DecideRequest = { action: 'REJECT', comment: this.rejectComment().trim() || null };
    this.approvalsService.rejectRequest(this.uid(), body).subscribe({
      next: (updated) => {
        this.request.set(updated);
        this.rejecting.set(false);
        this.showRejectForm.set(false);
        this.rejectComment.set('');
        this.alerts.success('Request rejected', updated.requestNumber);
      },
      error: (err) => {
        this.rejectError.set(this.messageFrom(err, 'Could not reject the request.'));
        this.rejecting.set(false);
      },
    });
  }

  // ── Recall ────────────────────────────────────────────────────────────────

  recall(): void {
    if (this.recalling()) return;
    this.recalling.set(true);
    this.recallError.set(null);
    this.approvalsService.recallRequest(this.uid()).subscribe({
      next: (updated) => {
        this.request.set(updated);
        this.recalling.set(false);
        this.alerts.success('Request recalled', updated.requestNumber);
      },
      error: (err) => {
        this.recallError.set(this.messageFrom(err, 'Could not recall the request.'));
        this.recalling.set(false);
      },
    });
  }

  // ── Cancel ────────────────────────────────────────────────────────────────

  cancel(): void {
    if (this.cancelling()) return;
    this.cancelling.set(true);
    this.cancelError.set(null);
    this.approvalsService.cancelRequest(this.uid()).subscribe({
      next: (updated) => {
        this.request.set(updated);
        this.cancelling.set(false);
        this.alerts.success('Request cancelled', updated.requestNumber);
      },
      error: (err) => {
        this.cancelError.set(this.messageFrom(err, 'Could not cancel the request.'));
        this.cancelling.set(false);
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────

  statusBadgeClass(status: ApprovalRequestStatus): string {
    switch (status) {
      case 'PENDING': return 'text-bg-warning';
      case 'APPROVED': return 'text-bg-success';
      case 'REJECTED': return 'text-bg-danger';
      case 'RECALLED': return 'text-bg-secondary';
      case 'CANCELLED': return 'text-bg-secondary';
    }
  }

  stepStatusBadgeClass(status: ApprovalStepStatus): string {
    switch (status) {
      case 'PENDING': return 'text-bg-warning';
      case 'APPROVED': return 'text-bg-success';
      case 'REJECTED': return 'text-bg-danger';
      case 'SKIPPED': return 'text-bg-secondary';
    }
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
