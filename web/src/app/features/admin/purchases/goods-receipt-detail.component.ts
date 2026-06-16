import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  GoodsReceiptDto,
  GoodsReceiptLineDto,
  VoidGoodsReceiptRequest,
} from '../models/purchases.model';
import { PurchasesService } from './purchases.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Goods Receipt detail. Route: /admin/goods-receipts/uid/:uid.
 * Shows header (receipt number, PO, status, supplier, dates) + lines.
 * Void action for RECEIVED receipts (PURCHASE.VOID).
 */
@Component({
  selector: 'app-goods-receipt-detail',
  imports: [FormsModule, RouterLink, DatePipe],
  templateUrl: './goods-receipt-detail.component.html',
  styleUrl: './goods-receipt-detail.component.scss',
})
export class GoodsReceiptDetailComponent {
  private readonly purchasesService = inject(PurchasesService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly gr = signal<GoodsReceiptDto | null>(null);
  readonly grState = signal<LoadState>('loading');

  // ── Void ──────────────────────────────────────────────────────────────────
  readonly showVoidForm = signal(false);
  readonly voidReason = signal('');
  readonly voiding = signal(false);
  readonly voidError = signal<string | null>(null);

  readonly canVoid = computed(() => this.session.hasPermission('PURCHASE.VOID'));

  readonly isReceived = computed(() => this.gr()?.status === 'RECEIVED');
  readonly isVoid = computed(() => this.gr()?.status === 'VOID');

  constructor() {
    queueMicrotask(() => this.load());
  }

  private load(): void {
    this.grState.set('loading');
    this.purchasesService.getReceiptByUid(this.uid()).subscribe({
      next: (gr) => { this.gr.set(gr); this.grState.set('idle'); },
      error: () => this.grState.set('error'),
    });
  }

  toggleVoidForm(): void {
    this.showVoidForm.update((v) => !v);
    this.voidError.set(null);
    this.voidReason.set('');
  }

  submitVoid(): void {
    const reason = this.voidReason().trim();
    if (!reason) { this.voidError.set('A void reason is required.'); return; }
    if (this.voiding()) return;
    this.voiding.set(true);
    this.voidError.set(null);
    const request: VoidGoodsReceiptRequest = { reason };
    this.purchasesService.voidReceipt(this.uid(), request).subscribe({
      next: () => {
        this.voiding.set(false);
        this.showVoidForm.set(false);
        this.alerts.success('Receipt voided');
        this.load();
      },
      error: (err) => {
        this.voidError.set(this.messageFrom(err, 'Could not void receipt.'));
        this.voiding.set(false);
      },
    });
  }

  lines(gr: GoodsReceiptDto): GoodsReceiptLineDto[] {
    return gr.lines ?? [];
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
