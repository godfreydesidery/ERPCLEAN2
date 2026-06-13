import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { CashTransferDto } from './models/cashbank.model';
import { CashbankService } from './cashbank.service';

/**
 * Cash Transfer detail view. Loaded by uid. Gated CASH.VIEW.
 */
@Component({
  selector: 'app-cash-transfer-detail',
  imports: [RouterLink],
  templateUrl: './cash-transfer-detail.component.html',
  styleUrl: './cash-transfer-detail.component.scss',
})
export class CashTransferDetailComponent {
  readonly uid = input.required<string>();

  private readonly cashbankService = inject(CashbankService);
  protected readonly session = inject(SessionStore);

  readonly entity = signal<CashTransferDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');
  readonly canView = computed(() => this.session.hasPermission('CASH.VIEW'));

  constructor() { queueMicrotask(() => this.init()); }

  private init(): void {
    const uid = this.uid();
    if (!uid) return;
    this.state.set('loading');
    this.cashbankService.getTransfer(uid).subscribe({
      next: (t) => { this.entity.set(t); this.state.set('idle'); },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }
}
