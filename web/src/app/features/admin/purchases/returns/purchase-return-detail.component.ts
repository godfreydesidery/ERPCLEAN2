import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { PurchaseReturnDto, PurchaseReturnStatus } from '../../models/purchases.model';
import { PurchaseReturnService } from './purchase-return.service';

type LoadState = 'loading' | 'idle' | 'error';

@Component({
  selector: 'app-purchase-return-detail',
  imports: [RouterLink, DatePipe, DecimalPipe],
  templateUrl: './purchase-return-detail.component.html',
  styleUrl: './purchase-return-detail.component.scss',
})
export class PurchaseReturnDetailComponent {
  private readonly returnService = inject(PurchaseReturnService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly entity = signal<PurchaseReturnDto | null>(null);
  readonly state = signal<LoadState>('loading');

  readonly confirming = signal(false);
  readonly confirmError = signal<string | null>(null);

  readonly canConfirm = computed(() => this.session.hasPermission('PURCHASE.RETURN.CONFIRM'));
  readonly isDraft = computed(() => this.entity()?.status === 'DRAFT');

  constructor() {
    queueMicrotask(() => this.load());
  }

  private load(): void {
    this.state.set('loading');
    this.returnService.getByUid(this.uid()).subscribe({
      next: (r) => { this.entity.set(r); this.state.set('idle'); },
      error: () => this.state.set('error'),
    });
  }

  confirm(): void {
    if (this.confirming()) return;
    this.confirming.set(true);
    this.confirmError.set(null);
    this.returnService.confirm(this.uid()).subscribe({
      next: (updated) => {
        this.confirming.set(false);
        this.entity.set(updated);
        this.alerts.success('Purchase return confirmed', updated.returnNumber);
      },
      error: (err) => {
        this.confirmError.set(this.messageFrom(err, 'Could not confirm return.'));
        this.confirming.set(false);
      },
    });
  }

  statusBadgeClass(status: PurchaseReturnStatus): string {
    return status === 'CONFIRMED' ? 'text-bg-success' : 'text-bg-warning';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
