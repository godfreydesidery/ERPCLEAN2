import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { blobErrorMessage } from '../../../core/api/blob-error';
import {
  GoodsReceiptDto,
  GoodsReceiptLineDto,
  VoidGoodsReceiptRequest,
} from '../models/purchases.model';
import { DocumentsService } from '../documents/documents.service';
import { PurchasesService } from './purchases.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Goods Receipt detail. Route: /admin/goods-receipts/uid/:uid.
 * Shows header (receipt number, PO, status, supplier, dates) + lines.
 * Void action for RECEIVED receipts (PURCHASE.VOID).
 */
@Component({
  selector: 'app-goods-receipt-detail',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe],
  templateUrl: './goods-receipt-detail.component.html',
  styleUrl: './goods-receipt-detail.component.scss',
})
export class GoodsReceiptDetailComponent {
  private readonly purchasesService = inject(PurchasesService);
  private readonly documentsService = inject(DocumentsService);
  private readonly alerts = inject(AlertService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly gr = signal<GoodsReceiptDto | null>(null);
  readonly grState = signal<LoadState>('loading');

  // ── Void ──────────────────────────────────────────────────────────────────
  readonly showVoidForm = signal(false);
  readonly voidReason = signal('');
  readonly voiding = signal(false);
  readonly voidError = signal<string | null>(null);

  // ── Print ───────────────────────────────────────────────────────────────
  readonly printing = signal(false);
  readonly printError = signal<string | null>(null);

  readonly canVoid = computed(() => this.session.hasPermission('PURCHASE.VOID'));
  readonly canPrint = computed(() => this.session.hasPermission('DOCUMENT.RENDER'));

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

  /**
   * Render the goods receipt as a PDF and trigger a browser download.
   * The backend already supports GOODS_RECEIPT end to end (template + render dispatch);
   * DRAFT receipts are blocked server-side and hidden in the template.
   */
  printPdf(): void {
    if (this.printing()) return;
    this.printing.set(true);
    this.printError.set(null);
    this.documentsService
      .renderBlob('GOODS_RECEIPT', this.uid())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          this.printing.set(false);
          const name = `goods-receipt-${this.gr()?.receiptNumber ?? this.uid()}.pdf`;
          triggerBlobDownload(blob, name);
        },
        error: (err) => {
          this.printing.set(false);
          // Error body is a Blob (responseType: 'blob') — surface the friendly server message.
          void blobErrorMessage(err, 'Could not generate the PDF.').then((m) => this.printError.set(m));
        },
      });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}

/** Create an object URL for a blob and click a synthetic anchor to download it. */
function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
