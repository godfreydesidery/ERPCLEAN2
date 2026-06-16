import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { SalesReturnDto } from '../models/sales-orders.model';
import { SalesOrdersService } from './sales-orders.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Sales Return detail screen.
 * Route: /admin/sales-returns/uid/:uid
 * Gate: SALES.RETURN.VIEW
 *
 * Shows return header, lines, and a link to the credit note raised on creation.
 */
@Component({
  selector: 'app-sales-return-detail',
  imports: [RouterLink, DecimalPipe],
  templateUrl: './sales-return-detail.component.html',
  styleUrl: './sales-return-detail.component.scss',
})
export class SalesReturnDetailComponent {
  private readonly soService = inject(SalesOrdersService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly returnDoc = signal<SalesReturnDto | null>(null);
  readonly returnState = signal<LoadState>('loading');

  readonly returnLabel = computed(() => this.returnDoc()?.returnNumber ?? 'PENDING');

  constructor() {
    queueMicrotask(() => this.loadReturn());
  }

  private loadReturn(): void {
    this.returnState.set('loading');
    this.soService.getReturnByUid(this.uid()).subscribe({
      next: (r) => { this.returnDoc.set(r); this.returnState.set('idle'); },
      error: () => this.returnState.set('error'),
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Coerce money: handles both number and string on the wire. */
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
