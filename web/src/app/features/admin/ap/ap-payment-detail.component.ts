import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { ApPaymentDto } from './models/ap.model';
import { ApService } from './ap.service';

/**
 * AP Payment detail view. Loaded by uid. Gated AP.VIEW.
 *
 * AP-03: a wrong supplier payment can be reversed here ("Reverse payment", reason required,
 * confirmed in a panel). Gated AP.PAYMENT.REVERSE, the code the server checks. A reversed payment
 * shows a badge and no longer offers the action.
 */
@Component({
  selector: 'app-ap-payment-detail',
  imports: [RouterLink, FormsModule],
  templateUrl: './ap-payment-detail.component.html',
  styleUrl: './ap-payment-detail.component.scss',
})
export class ApPaymentDetailComponent {
  readonly uid = input.required<string>();

  private readonly apService = inject(ApService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly entity = signal<ApPaymentDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');
  readonly canView = computed(() => this.session.hasPermission('AP.VIEW'));

  // ── Reverse payment (AP-03) ────────────────────────────────────────────────
  readonly isReversed = computed(() => !!this.entity()?.reversedAt);
  /** AP.PAYMENT.REVERSE — the same code the server checks (finance seats only). */
  readonly canReverse = computed(() => this.session.hasPermission('AP.PAYMENT.REVERSE'));
  readonly showReverse = computed(() => this.canReverse() && !!this.entity() && !this.isReversed());
  readonly reverseOpen = signal(false);
  readonly reverseReason = signal('');
  readonly reversing = signal(false);
  readonly reverseError = signal<string | null>(null);
  readonly reverseDisabled = computed(() =>
    this.reversing() || !this.reverseReason().trim() || this.reverseReason().trim().length > 200,
  );

  constructor() { queueMicrotask(() => this.init()); }

  private init(): void {
    const uid = this.uid();
    if (!uid) return;
    this.state.set('loading');
    this.apService.getPayment(uid).subscribe({
      next: (p) => { this.entity.set(p); this.state.set('idle'); },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  openReverse(): void {
    this.reverseReason.set('');
    this.reverseError.set(null);
    this.reverseOpen.set(true);
  }

  closeReverse(): void {
    this.reverseOpen.set(false);
    this.reverseError.set(null);
  }

  /** Confirmed in the panel: posts the reversal, then shows the payment as reversed. */
  confirmReverse(): void {
    const p = this.entity();
    if (!p || this.reverseDisabled()) return;
    this.reversing.set(true);
    this.reverseError.set(null);
    this.apService.reversePayment(p.uid, this.reverseReason().trim()).subscribe({
      next: (updated) => {
        this.reversing.set(false);
        this.entity.set({ ...p, ...updated });
        this.alerts.success('Payment reversed', String(p.paymentNumber ?? ''));
        this.reverseOpen.set(false);
      },
      error: (err) => {
        this.reversing.set(false);
        this.reverseError.set(this.messageFrom(err, 'Could not reverse this payment.'));
      },
    });
  }

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
