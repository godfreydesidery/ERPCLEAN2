import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  CloseSessionRequest,
  PosPayoutRequest,
  PosPayoutType,
  PosSessionDto,
  PosSessionStatus,
  ReconcileSessionRequest,
  XReadDto,
  ZReadDto,
} from './models/pos.model';
import { PosService } from './pos.service';

/**
 * POS Session detail: header, X-read panel, payout form, close form, reconcile.
 * Route: /admin/pos/sessions/uid/:uid
 * Gated per action: POS.SESSION.VIEW / OPEN / CLOSE / RECONCILE.
 */
@Component({
  selector: 'app-pos-session-detail',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './pos-session-detail.component.html',
  styleUrl: './pos-session-detail.component.scss',
})
export class PosSessionDetailComponent {
  private readonly posService = inject(PosService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  // ── Entity ─────────────────────────────────────────────────────────────────
  readonly entity = signal<PosSessionDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  // ── X-Read ─────────────────────────────────────────────────────────────────
  readonly xRead = signal<XReadDto | null>(null);
  readonly xReadState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Z-Read (after reconcile) ───────────────────────────────────────────────
  readonly zRead = signal<ZReadDto | null>(null);

  // ── Payout form ────────────────────────────────────────────────────────────
  readonly showPayoutForm = signal(false);
  readonly payoutType = signal<PosPayoutType>('PAID_OUT');
  readonly payoutAmount = signal('');
  readonly payoutReason = signal('');
  readonly paying = signal(false);
  readonly payoutError = signal<string | null>(null);

  // ── Close form ─────────────────────────────────────────────────────────────
  readonly showCloseForm = signal(false);
  readonly countedCash = signal('');
  readonly closeNotes = signal('');
  readonly closing = signal(false);
  readonly closeError = signal<string | null>(null);

  // ── Reconcile ──────────────────────────────────────────────────────────────
  readonly reconciling = signal(false);
  readonly reconcileError = signal<string | null>(null);
  readonly reconcileNotes = signal('');
  readonly showReconcileForm = signal(false);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canClose = computed(() => this.session.hasPermission('POS.SESSION.CLOSE'));
  readonly canReconcile = computed(() => this.session.hasPermission('POS.SESSION.RECONCILE'));
  readonly canView = computed(() => this.session.hasPermission('POS.SESSION.VIEW'));

  readonly payoutTypes: PosPayoutType[] = ['PAID_OUT', 'REFUND'];

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    const uid = this.uid();
    this.state.set('loading');
    this.posService.getSessionByUid(uid).subscribe({
      next: (s) => {
        this.entity.set(s);
        this.state.set('idle');
        this.loadXRead(uid);
      },
      error: (err: unknown) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  loadXRead(uid: string): void {
    this.xReadState.set('loading');
    this.posService.xRead(uid).subscribe({
      next: (x) => { this.xRead.set(x); this.xReadState.set('idle'); },
      error: () => this.xReadState.set('error'),
    });
  }

  refresh(): void { this.init(); }

  // ── Payout ─────────────────────────────────────────────────────────────────

  togglePayoutForm(): void {
    this.showPayoutForm.update((v) => !v);
    this.payoutError.set(null);
  }

  recordPayout(): void {
    const amount = this.payoutAmount().trim();
    const reason = this.payoutReason().trim();

    if (!amount || isNaN(+amount) || +amount <= 0) { this.payoutError.set('Enter a valid positive amount.'); return; }
    if (!reason) { this.payoutError.set('Reason is required.'); return; }

    const request: PosPayoutRequest = { payoutType: this.payoutType(), amount, reason };
    this.paying.set(true);
    this.payoutError.set(null);

    this.posService.recordPayout(this.uid(), request).subscribe({
      next: () => {
        this.paying.set(false);
        this.payoutAmount.set('');
        this.payoutReason.set('');
        this.showPayoutForm.set(false);
        this.alerts.success('Payout recorded', `${request.payoutType} — ${amount}`);
        this.loadXRead(this.uid());
      },
      error: (err: unknown) => {
        this.payoutError.set(this.messageFrom(err, 'Could not record payout.'));
        this.paying.set(false);
      },
    });
  }

  // ── Close ──────────────────────────────────────────────────────────────────

  toggleCloseForm(): void {
    this.showCloseForm.update((v) => !v);
    this.closeError.set(null);
  }

  closeSession(): void {
    const counted = this.countedCash().trim();
    if (!counted || isNaN(+counted) || +counted < 0) { this.closeError.set('Enter a valid counted cash amount.'); return; }

    const request: CloseSessionRequest = { countedCashAmount: counted, notes: this.closeNotes().trim() || undefined };
    this.closing.set(true);
    this.closeError.set(null);

    this.posService.closeSession(this.uid(), request).subscribe({
      next: (updated) => {
        this.closing.set(false);
        this.entity.set(updated);
        this.showCloseForm.set(false);
        this.alerts.success('Session closed', updated.uid);
      },
      error: (err: unknown) => {
        this.closeError.set(this.messageFrom(err, 'Could not close session.'));
        this.closing.set(false);
      },
    });
  }

  // ── Reconcile ──────────────────────────────────────────────────────────────

  toggleReconcileForm(): void {
    this.showReconcileForm.update((v) => !v);
    this.reconcileError.set(null);
  }

  reconcile(): void {
    const request: ReconcileSessionRequest = { notes: this.reconcileNotes().trim() || undefined };
    this.reconciling.set(true);
    this.reconcileError.set(null);

    this.posService.reconcileSession(this.uid(), request).subscribe({
      next: (z) => {
        this.reconciling.set(false);
        this.zRead.set(z);
        this.showReconcileForm.set(false);
        this.alerts.success('Session reconciled', z.sessionUid);
        // Refresh entity to get updated status
        this.posService.getSessionByUid(this.uid()).subscribe({ next: (s) => this.entity.set(s) });
      },
      error: (err: unknown) => {
        this.reconcileError.set(this.messageFrom(err, 'Could not reconcile session.'));
        this.reconciling.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  statusBadgeClass(status: PosSessionStatus): string {
    switch (status) {
      case 'OPEN': return 'text-bg-success';
      case 'CLOSED': return 'text-bg-warning';
      case 'RECONCILED': return 'text-bg-secondary';
      default: return 'text-bg-light';
    }
  }

  varianceBadgeClass(variance: string | null): string {
    if (!variance) return 'text-bg-secondary';
    const n = +variance;
    if (n === 0) return 'text-bg-success';
    if (n > 0) return 'text-bg-info';
    return 'text-bg-danger';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
