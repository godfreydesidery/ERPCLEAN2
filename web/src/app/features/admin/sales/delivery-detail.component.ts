import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { blobErrorMessage } from '../../../core/api/blob-error';
import { DeliveryDto, SalesReturnDto } from '../models/sales-orders.model';
import { SalesInvoiceDto } from '../models/sales.model';
import { DocumentsService } from '../documents/documents.service';
import { SalesOrdersService } from './sales-orders.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Delivery detail + lifecycle screen. Route: /admin/deliveries/uid/:uid.
 * Actions:
 *   DRAFT → Confirm (issues stock)
 *   CONFIRMED → Invoice this delivery (creates draft invoice, navigates to it)
 *   CONFIRMED → Create Return (navigates to return-create pre-filled with this delivery uid)
 */
@Component({
  selector: 'app-delivery-detail',
  imports: [RouterLink, DatePipe, DecimalPipe],
  templateUrl: './delivery-detail.component.html',
  styleUrl: './delivery-detail.component.scss',
})
export class DeliveryDetailComponent {
  private readonly soService = inject(SalesOrdersService);
  private readonly documentsService = inject(DocumentsService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly delivery = signal<DeliveryDto | null>(null);
  readonly deliveryState = signal<LoadState>('loading');

  // ── Confirm delivery (issues stock) ─────────────────────────────────────────
  readonly confirming = signal(false);
  readonly confirmError = signal<string | null>(null);
  readonly showConfirmDialog = signal(false);

  // ── Invoice this delivery ────────────────────────────────────────────────────
  readonly invoicing = signal(false);
  readonly invoiceError = signal<string | null>(null);
  readonly createdInvoice = signal<SalesInvoiceDto | null>(null);

  // ── Returns for this delivery ────────────────────────────────────────────────
  readonly existingReturns = signal<SalesReturnDto[]>([]);

  // ── Print PDF ────────────────────────────────────────────────────────────
  readonly printing = signal(false);
  readonly printError = signal<string | null>(null);

  // ── Permissions ──────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('SALES.DELIVERY.CREATE'));
  readonly canCreateReturn = computed(() => this.session.hasPermission('SALES.RETURN.CREATE'));
  readonly canPrint = computed(() => this.session.hasPermission('DOCUMENT.RENDER'));

  // ── Derived ──────────────────────────────────────────────────────────────────
  readonly isDraft = computed(() => this.delivery()?.status === 'DRAFT');
  readonly isConfirmed = computed(() => this.delivery()?.status === 'CONFIRMED');

  readonly deliveryLabel = computed(() => this.delivery()?.deliveryNumber ?? 'DRAFT');

  constructor() {
    queueMicrotask(() => this.loadDelivery());
  }

  private loadDelivery(): void {
    this.deliveryState.set('loading');
    this.soService.getDeliveryByUid(this.uid()).subscribe({
      next: (d) => {
        this.delivery.set(d);
        this.deliveryState.set('idle');
        this.loadReturns();
      },
      error: () => this.deliveryState.set('error'),
    });
  }

  private loadReturns(): void {
    this.soService.listReturnsForDelivery(this.uid()).subscribe({
      next: ({ rows }) => this.existingReturns.set(rows),
      error: () => undefined, // non-fatal — returns panel simply stays empty
    });
  }

  private refetchDelivery(): void {
    this.soService.getDeliveryByUid(this.uid()).subscribe({
      next: (d) => this.delivery.set(d),
      error: () => undefined,
    });
  }

  // ── Confirm ────────────────────────────────────────────────────────────────────

  toggleConfirmDialog(): void {
    this.showConfirmDialog.update((v) => !v);
    this.confirmError.set(null);
  }

  /** The backend does not yet expose a separate confirm-delivery endpoint in Stage 1;
   *  the delivery is confirmed implicitly on creation via DeliveryService.
   *  This placeholder wires the button to a future endpoint — currently a no-op alert. */
  confirmDelivery(): void {
    if (this.confirming()) return;
    this.confirming.set(true);
    this.confirmError.set(null);
    // No explicit confirm endpoint in Stage-1 backend — delivery is confirmed on creation.
    // Show informational alert and close dialog.
    this.confirming.set(false);
    this.showConfirmDialog.set(false);
    this.alerts.success('Delivery is already confirmed on creation (Stage-1 behaviour).');
  }

  // ── Create Return ──────────────────────────────────────────────────────────────

  createReturn(): void {
    void this.router.navigate(['/admin/sales-returns/create'], {
      queryParams: { deliveryUid: this.uid() },
    });
  }

  // ── Invoice ────────────────────────────────────────────────────────────────────

  invoiceDelivery(): void {
    if (this.invoicing()) return;
    this.invoicing.set(true);
    this.invoiceError.set(null);
    this.soService.createInvoiceFromDelivery(this.uid()).subscribe({
      next: (inv) => {
        this.invoicing.set(false);
        this.createdInvoice.set(inv);
        this.alerts.success('Invoice created', inv.invoiceNumber ?? 'DRAFT');
        this.refetchDelivery();
      },
      error: (err: unknown) => {
        this.invoiceError.set(this.messageFrom(err, 'Could not create invoice.'));
        this.invoicing.set(false);
      },
    });
  }

  // ── Print PDF ────────────────────────────────────────────────────────────

  /**
   * Render the delivery note as a PDF and trigger a browser download.
   * Mirrors the documents-module pattern (DocumentsService.renderBlob →
   * triggerBlobDownload). Unlike invoices/POs the backend has no renderable-state
   * guard for DELIVERY_NOTE — available regardless of delivery status.
   */
  printPdf(): void {
    if (this.printing()) return;
    this.printing.set(true);
    this.printError.set(null);
    this.documentsService
      .renderBlob('DELIVERY_NOTE', this.uid())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          this.printing.set(false);
          const name = `delivery-note-${this.delivery()?.deliveryNumber ?? this.uid()}.pdf`;
          triggerBlobDownload(blob, name);
        },
        error: (err) => {
          this.printing.set(false);
          // The error body is a Blob (responseType: 'blob'), so read the friendly server message
          // out of it; falls back to a generic line if it isn't a JSON error envelope.
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
