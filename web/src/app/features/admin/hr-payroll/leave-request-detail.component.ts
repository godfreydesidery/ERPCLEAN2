import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { HrPayrollService } from './hr-payroll.service';
import { DecideLeaveRequest, LeaveRequestDto } from './models/hr-payroll.model';

/**
 * Leave request detail screen with approve/reject lifecycle actions.
 * Route: /admin/hr/leave-requests/uid/:uid
 */
@Component({
  selector: 'app-leave-request-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './leave-request-detail.component.html',
  styleUrl: './leave-request-detail.component.scss',
})
export class LeaveRequestDetailComponent {
  private readonly hrService = inject(HrPayrollService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly request = signal<LeaveRequestDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Decide form ──────────────────────────────────────────────────────────────
  readonly fDecision = signal<'APPROVED' | 'REJECTED'>('APPROVED');
  readonly fDecisionNote = signal('');
  readonly deciding = signal(false);
  readonly actionError = signal<string | null>(null);

  readonly canApprove = computed(() => this.session.hasPermission('HR.LEAVE.APPROVE'));
  readonly canDecide = computed(() => this.request()?.status === 'PENDING');

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.hrService.getLeaveRequestByUid(this.uid()).subscribe({
      next: (r) => {
        this.request.set(r);
        this.state.set('idle');
      },
      error: () => this.state.set('error'),
    });
  }

  decide(): void {
    if (this.deciding()) return;
    this.deciding.set(true);
    this.actionError.set(null);

    const req: DecideLeaveRequest = {
      decision: this.fDecision(),
      decisionNote: this.fDecisionNote().trim() || undefined,
    };

    this.hrService.decideLeaveRequest(this.uid(), req).subscribe({
      next: (updated) => {
        this.request.set(updated);
        this.deciding.set(false);
        this.alerts.success(`Leave request ${updated.status.toLowerCase()}`, updated.employeeName);
      },
      error: (err) => {
        this.deciding.set(false);
        this.actionError.set(this.messageFrom(err, 'Could not process decision.'));
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
