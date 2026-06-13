import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { StockTransferDto, StockTransferStatus } from './stock-transfer.model';
import { StockTransferService } from './stock-transfer.service';

@Component({
  selector: 'app-stock-transfer-detail',
  imports: [DatePipe, DecimalPipe, RouterLink],
  templateUrl: './stock-transfer-detail.component.html',
  styleUrl: './stock-transfer-detail.component.scss',
})
export class StockTransferDetailComponent {
  readonly uid = input.required<string>();

  private readonly transferService = inject(StockTransferService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly entity = signal<StockTransferDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  readonly actionBusy = signal(false);
  readonly actionError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('STOCK.TRANSFER.CREATE'));
  readonly canReceive = computed(() => this.session.hasPermission('STOCK.TRANSFER.RECEIVE'));

  // ── Derived booleans gated by status ─────────────────────────────────────────
  readonly canDispatch = computed(() => {
    const e = this.entity();
    return this.canCreate() && e?.status === 'DRAFT' && e?.transferMode === 'IN_TRANSIT';
  });
  readonly canCompleteInstant = computed(() => {
    const e = this.entity();
    return this.canCreate() && e?.status === 'DRAFT' && e?.transferMode === 'INSTANT';
  });
  readonly canReceiveTransfer = computed(() => {
    const e = this.entity();
    return this.canReceive() && e?.status === 'DISPATCHED';
  });
  readonly canCancel = computed(() => {
    const e = this.entity();
    return this.canCreate() && e?.status === 'DRAFT';
  });

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    const uid = this.uid();
    if (!uid) return;
    this.state.set('loading');
    this.transferService.getByUid(uid).subscribe({
      next: (t) => {
        this.entity.set(t);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        ),
    });
  }

  dispatch(): void {
    this.runAction(() => this.transferService.dispatch(this.uid()), 'dispatched');
  }

  completeInstant(): void {
    this.runAction(() => this.transferService.completeInstant(this.uid()), 'completed');
  }

  receive(): void {
    this.runAction(() => this.transferService.receive(this.uid()), 'received');
  }

  cancel(): void {
    if (this.actionBusy()) return;
    this.actionBusy.set(true);
    this.actionError.set(null);
    this.transferService.cancel(this.uid()).subscribe({
      next: () => {
        this.actionBusy.set(false);
        this.alerts.success('Transfer cancelled');
        // Reload to reflect CANCELLED status
        this.init();
      },
      error: (err: unknown) => {
        this.actionError.set(this.messageFrom(err, 'Could not cancel transfer.'));
        this.actionBusy.set(false);
      },
    });
  }

  private runAction(
    call: () => ReturnType<typeof this.transferService.dispatch>,
    verb: string,
  ): void {
    if (this.actionBusy()) return;
    this.actionBusy.set(true);
    this.actionError.set(null);
    call().subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.actionBusy.set(false);
        this.alerts.success(`Transfer ${verb}`);
      },
      error: (err: unknown) => {
        this.actionError.set(this.messageFrom(err, `Could not perform action.`));
        this.actionBusy.set(false);
      },
    });
  }

  statusBadgeClass(status: StockTransferStatus): string {
    switch (status) {
      case 'DRAFT':
        return 'text-bg-warning';
      case 'DISPATCHED':
        return 'text-bg-info';
      case 'RECEIVED':
        return 'text-bg-primary';
      case 'COMPLETED':
        return 'text-bg-success';
      case 'CANCELLED':
        return 'text-bg-danger';
      default:
        return 'text-bg-secondary';
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
