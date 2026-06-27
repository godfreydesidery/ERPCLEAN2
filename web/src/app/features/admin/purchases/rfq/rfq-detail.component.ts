import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { SupplierService } from '../../parties/supplier.service';
import {
  CaptureSupplierQuoteRequest,
  RfqDto,
  RfqLineDto,
  SupplierQuoteDto,
} from './models/rfq.model';
import { RfqService } from './rfq.service';
import { UidOption, UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { PageMeta } from '../../../../core/api/api-response.model';

/** Per-line entry in the capture-quote form. */
interface QuoteLineEntry {
  rfqLineId: string;
  rfqLineUid: string;
  productName: string;
  unitName: string;
  rfqQty: string;
  quotedQty: string;
  unitPriceAmount: string;
}

/**
 * RFQ detail screen.
 * Shows RFQ header + line items + lifecycle actions (Send, Cancel).
 * Shows captured supplier quotes (fetched via /by-rfq/{rfqUid}).
 * Provides a capture-quote form (supplier picker + per-line unit price).
 * Award action picks the winning quote → creates PO; shows the PO uid link.
 */
@Component({
  selector: 'app-rfq-detail',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, UidPickerComponent, PaginatorComponent],
  templateUrl: './rfq-detail.component.html',
  styleUrl: './rfq-detail.component.scss',
})
export class RfqDetailComponent {
  readonly uid = input.required<string>();

  private readonly rfqService = inject(RfqService);
  private readonly supplierService = inject(SupplierService);
  private readonly router = inject(Router);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── RFQ load ───────────────────────────────────────────────────────────────
  readonly rfq = signal<RfqDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  // ── Supplier quotes list ───────────────────────────────────────────────────
  readonly quotes = signal<SupplierQuoteDto[]>([]);
  readonly quotesMeta = signal<PageMeta>({ page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false });
  readonly quotesState = signal<'loading' | 'idle' | 'error'>('idle');

  // ── Supplier options for the capture form ─────────────────────────────────
  readonly supplierOptions = signal<UidOption[]>([]);

  // ── Capture quote form ─────────────────────────────────────────────────────
  readonly showCaptureForm = signal(false);
  readonly captureSupplierUid = signal('');
  readonly captureValidUntil = signal('');
  readonly captureLeadTimeDays = signal('');
  readonly captureNotes = signal('');
  readonly captureLines = signal<QuoteLineEntry[]>([]);
  readonly capturing = signal(false);
  readonly captureError = signal<string | null>(null);

  // ── Lifecycle action state ─────────────────────────────────────────────────
  readonly sending = signal(false);
  readonly cancelling = signal(false);
  readonly awarding = signal(false);
  readonly awardingQuoteUid = signal('');
  readonly actionError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('PURCHASE.RFQ.MANAGE'));
  readonly canAward = computed(() => this.session.hasPermission('PURCHASE.RFQ.MANAGE'));
  readonly canCaptureQuote = computed(() => this.session.hasPermission('PURCHASE.RFQ.MANAGE'));

  // Expose global String constructor for use in templates.
  protected readonly String = String;

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.rfqService.getRfqByUid(this.uid()).subscribe({
      next: (rfq) => {
        this.rfq.set(rfq);
        this.state.set('idle');
        this.loadQuotes();
        this.buildCaptureLines(rfq.lines);
        // Load supplier display names for the invited uids
        this.loadSupplierOptions(rfq);
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  private loadSupplierOptions(rfq: RfqDto): void {
    // We have the supplier uids; fetch per-uid to build options.
    // Use a single list call with companyId derived from rfq.companyId.
    this.supplierService.list(rfq.companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => {
        const invited = new Set(rfq.supplierUids);
        this.supplierOptions.set(
          rows
            .filter((s) => invited.has(s.uid))
            .map((s) => ({ uid: s.uid, label: s.displayName, hint: s.code })),
        );
      },
      error: () => {},
    });
  }

  loadQuotes(): void {
    this.quotesState.set('loading');
    this.rfqService.listQuotesByRfq(this.uid()).subscribe({
      next: ({ rows, meta }) => {
        this.quotes.set(rows);
        this.quotesMeta.set(meta);
        this.quotesState.set('idle');
      },
      error: () => this.quotesState.set('error'),
    });
  }

  private buildCaptureLines(lines: RfqLineDto[]): void {
    this.captureLines.set(
      lines.map((l) => ({
        rfqLineId: l.id,
        rfqLineUid: l.uid,
        productName: l.productName,
        unitName: l.unitName,
        rfqQty: l.quantity,
        quotedQty: l.quantity,
        unitPriceAmount: '',
      })),
    );
  }

  // ── Lifecycle actions ──────────────────────────────────────────────────────

  send(): void {
    this.actionError.set(null);
    this.sending.set(true);
    this.rfqService.sendRfq(this.uid()).subscribe({
      next: (updated) => {
        this.rfq.set(updated);
        this.sending.set(false);
        this.alerts.success('RFQ sent', 'Suppliers have been notified.');
      },
      error: (err) => {
        this.actionError.set(this.messageFrom(err, 'Could not send RFQ.'));
        this.sending.set(false);
      },
    });
  }

  cancel(): void {
    this.actionError.set(null);
    this.cancelling.set(true);
    this.rfqService.cancelRfq(this.uid()).subscribe({
      next: (updated) => {
        this.rfq.set(updated);
        this.cancelling.set(false);
        this.alerts.success('RFQ cancelled', updated.rfqNumber);
      },
      error: (err) => {
        this.actionError.set(this.messageFrom(err, 'Could not cancel RFQ.'));
        this.cancelling.set(false);
      },
    });
  }

  award(quoteUid: string): void {
    this.actionError.set(null);
    this.awarding.set(true);
    this.awardingQuoteUid.set(quoteUid);
    this.rfqService.awardRfq(this.uid(), quoteUid).subscribe({
      next: (updated) => {
        this.rfq.set(updated);
        this.awarding.set(false);
        this.awardingQuoteUid.set('');
        this.loadQuotes();
        this.alerts.success('RFQ awarded', 'Purchase order created from this RFQ.');
      },
      error: (err) => {
        this.actionError.set(this.messageFrom(err, 'Could not award RFQ.'));
        this.awarding.set(false);
        this.awardingQuoteUid.set('');
      },
    });
  }

  // ── Capture quote ──────────────────────────────────────────────────────────

  toggleCaptureForm(): void {
    this.showCaptureForm.update((v) => !v);
    if (!this.showCaptureForm()) {
      this.resetCaptureForm();
    }
  }

  private resetCaptureForm(): void {
    this.captureSupplierUid.set('');
    this.captureValidUntil.set('');
    this.captureLeadTimeDays.set('');
    this.captureNotes.set('');
    this.captureError.set(null);
    const rfq = this.rfq();
    if (rfq) this.buildCaptureLines(rfq.lines);
  }

  updateCaptureLineQty(index: number, val: unknown): void {
    this.captureLines.update((ls) =>
      ls.map((l, i) => (i === index ? { ...l, quotedQty: String(val ?? '') } : l)),
    );
  }

  updateCaptureLinePrice(index: number, val: unknown): void {
    this.captureLines.update((ls) =>
      ls.map((l, i) => (i === index ? { ...l, unitPriceAmount: String(val ?? '') } : l)),
    );
  }

  submitCapture(): void {
    this.captureError.set(null);
    const rfq = this.rfq();
    if (!rfq) return;

    const supplierUid = this.captureSupplierUid();
    if (!supplierUid) { this.captureError.set('Select a supplier.'); return; }

    const ls = this.captureLines();
    for (let i = 0; i < ls.length; i++) {
      const l = ls[i];
      const qty = Number(l.quotedQty);
      if (!l.quotedQty.trim() || isNaN(qty) || qty <= 0) {
        this.captureError.set(`Line ${i + 1}: qty must be greater than zero.`); return;
      }
      const price = Number(l.unitPriceAmount);
      if (!l.unitPriceAmount.trim() || isNaN(price) || price < 0) {
        this.captureError.set(`Line ${i + 1}: unit price is required.`); return;
      }
    }

    this.capturing.set(true);

    const request: CaptureSupplierQuoteRequest = {
      rfqUid: rfq.uid,
      supplierUid,
      validUntil: this.captureValidUntil().trim() || undefined,
      leadTimeDays: this.captureLeadTimeDays().trim()
        ? Number(this.captureLeadTimeDays())
        : undefined,
      notes: this.captureNotes().trim() || undefined,
      lines: ls.map((l) => ({
        rfqLineId: l.rfqLineId,
        quotedQty: l.quotedQty.trim(),
        unitPriceAmount: l.unitPriceAmount.trim(),
      })),
    };

    this.rfqService.captureQuote(request).subscribe({
      next: (quote) => {
        this.capturing.set(false);
        this.showCaptureForm.set(false);
        this.resetCaptureForm();
        this.alerts.success('Quote captured', quote.quoteNumber);
        this.loadQuotes();
      },
      error: (err) => {
        this.captureError.set(this.messageFrom(err, 'Could not capture quote.'));
        this.capturing.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  supplierLabel(uid: string): string {
    const opt = this.supplierOptions().find((o) => o.uid === uid);
    return opt ? `${opt.hint} — ${opt.label}` : uid;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
