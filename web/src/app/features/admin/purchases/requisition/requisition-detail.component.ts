import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { PurchaseRequisitionDto, RequisitionStatus } from './purchase-requisition.model';
import { PurchaseRequisitionService } from './purchase-requisition.service';

@Component({
  selector: 'app-requisition-detail',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe],
  templateUrl: './requisition-detail.component.html',
  styleUrl: './requisition-detail.component.scss',
})
export class RequisitionDetailComponent {
  readonly uid = input.required<string>();

  private readonly reqService = inject(PurchaseRequisitionService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Entity state ───────────────────────────────────────────────────────────
  readonly entity = signal<PurchaseRequisitionDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  // ── Action state ───────────────────────────────────────────────────────────
  readonly actionBusy = signal(false);
  readonly actionError = signal<string | null>(null);

  // ── Reject dialog ──────────────────────────────────────────────────────────
  readonly showRejectForm = signal(false);
  readonly rejectReason = signal('');

  // ── Cancel dialog ──────────────────────────────────────────────────────────
  readonly showCancelForm = signal(false);
  readonly cancelReason = signal('');

  // ── Convert ────────────────────────────────────────────────────────────────
  readonly showConvertForm = signal(false);
  readonly convertTargetType = signal<'PURCHASE_ORDER' | 'RFQ'>('PURCHASE_ORDER');
  readonly convertedUid = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('PURCHASE.REQUISITION.CREATE'));
  readonly canApprove = computed(() => this.session.hasPermission('PURCHASE.REQUISITION.APPROVE'));

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.reqService.getByUid(this.uid()).subscribe({
      next: (r) => {
        this.entity.set(r);
        this.state.set('idle');
        if (r.convertedToUid) this.convertedUid.set(r.convertedToUid);
      },
      error: (err) => {
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        );
      },
    });
  }

  // ── Lifecycle actions ──────────────────────────────────────────────────────

  submit(): void {
    this.actionBusy.set(true);
    this.actionError.set(null);
    this.reqService.submit(this.uid()).subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.actionBusy.set(false);
        this.alerts.success('Requisition submitted', updated.requisitionNumber ?? updated.uid);
      },
      error: (err) => {
        this.actionError.set(this.messageFrom(err, 'Could not submit requisition.'));
        this.actionBusy.set(false);
      },
    });
  }

  approve(): void {
    this.actionBusy.set(true);
    this.actionError.set(null);
    this.reqService.approve(this.uid()).subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.actionBusy.set(false);
        this.alerts.success('Requisition approved', updated.requisitionNumber ?? updated.uid);
      },
      error: (err) => {
        this.actionError.set(this.messageFrom(err, 'Could not approve requisition.'));
        this.actionBusy.set(false);
      },
    });
  }

  confirmReject(): void {
    const reason = this.rejectReason().trim();
    if (!reason) { this.actionError.set('Rejection reason is required.'); return; }
    this.actionBusy.set(true);
    this.actionError.set(null);
    this.reqService.reject(this.uid(), reason).subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.actionBusy.set(false);
        this.showRejectForm.set(false);
        this.rejectReason.set('');
        this.alerts.success('Requisition rejected', updated.requisitionNumber ?? updated.uid);
      },
      error: (err) => {
        this.actionError.set(this.messageFrom(err, 'Could not reject requisition.'));
        this.actionBusy.set(false);
      },
    });
  }

  confirmCancel(): void {
    this.actionBusy.set(true);
    this.actionError.set(null);
    this.reqService.cancel(this.uid(), this.cancelReason().trim()).subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.actionBusy.set(false);
        this.showCancelForm.set(false);
        this.cancelReason.set('');
        this.alerts.success('Requisition cancelled', updated.requisitionNumber ?? updated.uid);
      },
      error: (err) => {
        this.actionError.set(this.messageFrom(err, 'Could not cancel requisition.'));
        this.actionBusy.set(false);
      },
    });
  }

  confirmConvert(): void {
    this.actionBusy.set(true);
    this.actionError.set(null);
    this.reqService.convert(this.uid(), this.convertTargetType()).subscribe({
      next: (resultUid) => {
        this.actionBusy.set(false);
        this.showConvertForm.set(false);
        this.convertedUid.set(resultUid);
        // Reload to get CONVERTED status
        this.reqService.getByUid(this.uid()).subscribe({ next: (r) => this.entity.set(r) });
        this.alerts.success(
          'Requisition converted',
          `Created ${this.convertTargetType()} — uid: ${resultUid}`,
        );
      },
      error: (err) => {
        this.actionError.set(this.messageFrom(err, 'Could not convert requisition.'));
        this.actionBusy.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  statusBadgeClass(status: RequisitionStatus): string {
    switch (status) {
      case 'SUBMITTED': return 'text-bg-primary';
      case 'APPROVED': return 'text-bg-success';
      case 'REJECTED': return 'text-bg-danger';
      case 'CONVERTED': return 'text-bg-info';
      case 'CANCELLED': return 'text-bg-secondary';
      default: return 'text-bg-warning'; // DRAFT
    }
  }

  convertedRouteBase(): string {
    const r = this.entity();
    if (!r) return '';
    return r.convertedToType === 'RFQ' ? '/admin/rfqs/uid' : '/admin/purchase-orders/uid';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
