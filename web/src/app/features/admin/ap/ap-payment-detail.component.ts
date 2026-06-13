import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { ApPaymentDto } from './models/ap.model';
import { ApService } from './ap.service';

/**
 * AP Payment detail view. Loaded by uid. Gated AP.VIEW.
 */
@Component({
  selector: 'app-ap-payment-detail',
  imports: [RouterLink],
  templateUrl: './ap-payment-detail.component.html',
  styleUrl: './ap-payment-detail.component.scss',
})
export class ApPaymentDetailComponent {
  readonly uid = input.required<string>();

  private readonly apService = inject(ApService);
  protected readonly session = inject(SessionStore);

  readonly entity = signal<ApPaymentDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');
  readonly canView = computed(() => this.session.hasPermission('AP.VIEW'));

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

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }
}
