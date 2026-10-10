import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { isInvalidAmount, normaliseAmount, parseAmount } from '../../../shared/money.util';
import { AllocationLineRequest, ArInvoiceDto, ArReceiptDto } from './models/ar.model';
import { ArService } from './ar.service';

/** One open invoice in the "Apply to invoices" editor, with what the user wants to apply to it. */
interface ApplyRow {
  invoice: ArInvoiceDto;
  applyInput: string;
}

/**
 * AR Receipt detail view. Loaded by uid from the list. Gated AR.VIEW.
 *
 * ARC-06: money held on account (an advance or over-payment) can be applied here to invoices raised
 * later — "Apply to invoices" loads the customer's open items, and the save replaces the receipt's
 * allocation set (existing allocations are kept and the new amounts added). Gated
 * AR.RECEIPT.ALLOCATE / AR.RECEIPT.RECORD, the same codes the server checks.
 */
@Component({
  selector: 'app-ar-receipt-detail',
  imports: [RouterLink, FormsModule],
  templateUrl: './ar-receipt-detail.component.html',
  styleUrl: './ar-receipt-detail.component.scss',
})
export class ArReceiptDetailComponent {
  readonly uid = input.required<string>();

  private readonly arService = inject(ArService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly entity = signal<ArReceiptDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  readonly canView = computed(() => this.session.hasPermission('AR.VIEW'));
  readonly canApply = computed(() =>
    this.session.hasPermission('AR.RECEIPT.ALLOCATE') || this.session.hasPermission('AR.RECEIPT.RECORD'),
  );

  /** Money on this receipt not yet applied to any invoice. */
  readonly onAccount = computed(() => +(this.entity()?.unallocatedAmount ?? 0) || 0);
  readonly showApply = computed(() =>
    this.canApply() && this.onAccount() > 0.000001 && !!this.entity()?.customerUid && !!this.entity()?.companyId,
  );

  // ── Apply-to-invoices editor ──────────────────────────────────────────────
  readonly applyOpen = signal(false);
  readonly applyRows = signal<ApplyRow[]>([]);
  readonly applyState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly applying = signal(false);
  readonly applyError = signal<string | null>(null);

  readonly applyTotal = computed(() =>
    this.applyRows().reduce((s, r) => s + (parseAmount(r.applyInput) ?? 0), 0),
  );
  readonly applyInvalid = computed(() =>
    this.applyRows().some((r) => isInvalidAmount(r.applyInput)) ||
    this.applyRows().some((r) => (parseAmount(r.applyInput) ?? 0) > +(r.invoice.outstandingAmount ?? 0) + 0.000001) ||
    this.applyTotal() > this.onAccount() + 0.000001,
  );
  readonly applyDisabled = computed(() =>
    this.applying() || this.applyTotal() <= 0 || this.applyInvalid(),
  );

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

  openApply(): void {
    const r = this.entity();
    if (!r?.companyId || !r.customerUid) return;
    this.applyOpen.set(true);
    this.applyError.set(null);
    this.applyState.set('loading');
    this.arService.listOpenInvoices(String(r.companyId), r.customerUid).subscribe({
      next: (rows) => {
        this.applyRows.set((rows ?? []).map((invoice) => ({ invoice, applyInput: '' })));
        this.applyState.set('idle');
      },
      error: () => this.applyState.set('error'),
    });
  }

  closeApply(): void {
    this.applyOpen.set(false);
    this.applyRows.set([]);
    this.applyError.set(null);
  }

  updateApply(invoiceUid: string, value: string): void {
    this.applyRows.update((rows) =>
      rows.map((r) => (r.invoice.uid === invoiceUid ? { ...r, applyInput: value } : r)),
    );
  }

  /** Fill the open invoices oldest-first with the money on account (they arrive oldest due first). */
  fillOldestFirst(): void {
    let remaining = this.onAccount();
    this.applyRows.update((rows) =>
      rows.map((r) => {
        const take = Math.max(0, Math.min(remaining, +(r.invoice.outstandingAmount ?? 0)));
        remaining -= take;
        return { ...r, applyInput: take > 0 ? take.toFixed(2) : '' };
      }),
    );
  }

  saveApply(): void {
    const r = this.entity();
    if (!r || this.applyDisabled()) return;

    // The PUT replaces the whole set: keep what is already allocated and add the new amounts.
    const merged = new Map<string, number>();
    for (const a of r.allocations ?? []) {
      merged.set(a.arInvoiceUid, (merged.get(a.arInvoiceUid) ?? 0) + (+(a.allocatedAmount ?? 0) || 0));
    }
    for (const row of this.applyRows()) {
      const amt = parseAmount(row.applyInput) ?? 0;
      if (amt > 0) merged.set(row.invoice.uid, (merged.get(row.invoice.uid) ?? 0) + amt);
    }
    const lines: AllocationLineRequest[] = [...merged.entries()].map(([arInvoiceUid, amt]) => ({
      arInvoiceUid,
      allocatedAmount: normaliseAmount(amt.toFixed(2)) ?? amt.toFixed(2),
    }));

    this.applying.set(true);
    this.applyError.set(null);
    this.arService.reallocateReceipt(r.uid, lines).subscribe({
      next: (updated) => {
        this.applying.set(false);
        this.entity.set({ ...r, ...updated, customerUid: updated.customerUid ?? r.customerUid });
        this.alerts.success('Receipt applied', String(r.receiptNumber ?? ''));
        this.closeApply();
      },
      error: (err) => {
        this.applying.set(false);
        this.applyError.set(this.messageFrom(err, 'Could not apply the receipt to these invoices.'));
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
