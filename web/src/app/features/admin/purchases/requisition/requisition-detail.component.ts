import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { SupplierService } from '../../parties/supplier.service';
import { UidOption, UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';
import { ConvertRequisitionRequest, PurchaseRequisitionDto } from './purchase-requisition.model';
import { PurchaseRequisitionService } from './purchase-requisition.service';

@Component({
  selector: 'app-requisition-detail',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, UidPickerComponent],
  templateUrl: './requisition-detail.component.html',
  styleUrl: './requisition-detail.component.scss',
})
export class RequisitionDetailComponent {
  readonly uid = input.required<string>();

  private readonly reqService = inject(PurchaseRequisitionService);
  private readonly supplierService = inject(SupplierService);
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

  /** Supplier options for the convert form (loaded once the requisition's company is known). */
  readonly supplierOptions = signal<UidOption[]>([]);
  /** PURCHASE_ORDER branch: single supplier + optional currency override. */
  readonly convertSupplierUid = signal('');
  readonly convertCurrency = signal('');
  /** RFQ branch: multi-supplier invite list, built with an add/remove picker (mirrors rfq-create). */
  readonly convertSupplierUids = signal<string[]>([]);
  readonly convertPendingSupplierUid = signal('');

  /** Supplier options not yet invited to the RFQ (filters out already-added ones). */
  readonly availableConvertSupplierOptions = computed<UidOption[]>(() => {
    const added = new Set(this.convertSupplierUids());
    return this.supplierOptions().filter((o) => !added.has(o.uid));
  });

  /** Confirm is disabled until the required supplier(s) are chosen for the selected target type. */
  readonly canConfirmConvert = computed(() =>
    this.convertTargetType() === 'RFQ'
      ? this.convertSupplierUids().length > 0
      : !!this.convertSupplierUid(),
  );

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
        this.loadSuppliers(r.companyId);
      },
      error: (err) => {
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        );
      },
    });
  }

  /** Loads active suppliers for the requisition's company — feeds the Convert form pickers. */
  private loadSuppliers(companyId: string): void {
    this.supplierService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => {
        this.supplierOptions.set(
          rows
            .filter((s) => s.status === 'ACTIVE')
            .map((s) => ({ uid: s.uid, label: s.displayName, hint: s.code })),
        );
      },
      error: () => {},
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
        this.alerts.success('Requisition submitted', updated.requisitionNumber ?? undefined);
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
        this.alerts.success('Requisition approved', updated.requisitionNumber ?? undefined);
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
        this.alerts.success('Requisition rejected', updated.requisitionNumber ?? undefined);
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
        this.alerts.success('Requisition cancelled', updated.requisitionNumber ?? undefined);
      },
      error: (err) => {
        this.actionError.set(this.messageFrom(err, 'Could not cancel requisition.'));
        this.actionBusy.set(false);
      },
    });
  }

  confirmConvert(): void {
    const targetType = this.convertTargetType();

    if (targetType === 'RFQ') {
      if (this.convertSupplierUids().length === 0) {
        this.actionError.set('Select at least one supplier to invite.');
        return;
      }
    } else if (!this.convertSupplierUid()) {
      this.actionError.set('Select a supplier to order from.');
      return;
    }

    this.actionBusy.set(true);
    this.actionError.set(null);

    const request: ConvertRequisitionRequest =
      targetType === 'RFQ'
        ? { targetType, supplierUids: this.convertSupplierUids() }
        : {
            targetType,
            supplierUid: this.convertSupplierUid(),
            currency: this.convertCurrency().trim() || undefined,
          };

    this.reqService.convert(this.uid(), request).subscribe({
      next: (resultUid) => {
        this.actionBusy.set(false);
        this.showConvertForm.set(false);
        this.convertedUid.set(resultUid);
        this.resetConvertForm();
        // Reload to get CONVERTED status
        this.reqService.getByUid(this.uid()).subscribe({ next: (r) => this.entity.set(r) });
        this.alerts.success(
          targetType === 'RFQ' ? 'RFQ created' : 'Purchase order created',
          resultUid,
        );
      },
      error: (err) => {
        this.actionError.set(this.messageFrom(err, 'Could not convert requisition.'));
        this.actionBusy.set(false);
      },
    });
  }

  // ── Convert form helpers ───────────────────────────────────────────────────

  addConvertSupplier(): void {
    const uid = this.convertPendingSupplierUid();
    if (!uid || this.convertSupplierUids().includes(uid)) return;
    this.convertSupplierUids.update((arr) => [...arr, uid]);
    this.convertPendingSupplierUid.set('');
  }

  removeConvertSupplier(uid: string): void {
    this.convertSupplierUids.update((arr) => arr.filter((u) => u !== uid));
  }

  convertSupplierLabel(uid: string): string {
    const opt = this.supplierOptions().find((o) => o.uid === uid);
    return opt ? `${opt.hint} — ${opt.label}` : uid;
  }

  cancelConvertForm(): void {
    this.showConvertForm.set(false);
    this.resetConvertForm();
  }

  private resetConvertForm(): void {
    this.convertTargetType.set('PURCHASE_ORDER');
    this.convertSupplierUid.set('');
    this.convertCurrency.set('');
    this.convertSupplierUids.set([]);
    this.convertPendingSupplierUid.set('');
  }

  // ── Display helpers ────────────────────────────────────────────────────────

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
