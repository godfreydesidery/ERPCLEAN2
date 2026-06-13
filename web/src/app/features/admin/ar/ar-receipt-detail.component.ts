import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { SessionStore } from '../../../core/auth/session.store';
import { ArReceiptDto } from './models/ar.model';
import { ArService } from './ar.service';

/**
 * AR Receipt detail view. Loaded by uid from the list. Gated AR.VIEW.
 */
@Component({
  selector: 'app-ar-receipt-detail',
  imports: [],
  templateUrl: './ar-receipt-detail.component.html',
  styleUrl: './ar-receipt-detail.component.scss',
})
export class ArReceiptDetailComponent {
  readonly uid = input.required<string>();

  private readonly arService = inject(ArService);
  protected readonly session = inject(SessionStore);

  readonly entity = signal<ArReceiptDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  readonly canView = computed(() => this.session.hasPermission('AR.VIEW'));

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    const uid = this.uid();
    if (!uid) return;
    this.state.set('loading');
    this.arService.getReceipt(uid).subscribe({
      next: (r) => { this.entity.set(r); this.state.set('idle'); },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }
}
