import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { SupplierBillDto } from './models/ap.model';
import { ApService } from './ap.service';
import {
  DirectReceiptRatificationComponent,
  hasRatificationNotice,
} from './direct-receipt-ratification.component';

type LoadState = 'loading' | 'idle' | 'error' | 'forbidden';

/**
 * Bill Detail screen.
 * Displays bill header + lines + status + match status.
 * Gated AP.VIEW.
 */
@Component({
  selector: 'app-bill-detail',
  imports: [RouterLink, DirectReceiptRatificationComponent],
  templateUrl: './bill-detail.component.html',
  styleUrl: './bill-detail.component.scss',
})
export class BillDetailComponent implements OnInit {
  private readonly apService = inject(ApService);
  protected readonly session = inject(SessionStore);

  /** Route param :uid — bound via withComponentInputBinding. */
  readonly uid = input<string>('');

  readonly bill = signal<SupplierBillDto | null>(null);
  readonly state = signal<LoadState>('loading');

  readonly canPay = computed(() => this.session.hasPermission('AP.PAYMENT.RUN'));
  readonly canView = computed(() => this.session.hasPermission('AP.VIEW'));

  ngOnInit(): void {
    const u = this.uid();
    if (!u) { this.state.set('error'); return; }
    this.apService.getBill(u).subscribe({
      next: (b) => { this.bill.set(b); this.state.set('idle'); },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  canPayBill(bill: SupplierBillDto): boolean {
    return bill.status === 'MATCHED' || bill.status === 'APPROVED' || bill.status === 'PARTIALLY_PAID';
  }

  /** True only for bills backed by a direct goods receipt — ordinary bills show no notice. */
  showsRatification(bill: SupplierBillDto): boolean {
    return hasRatificationNotice(bill.directReceiptRatification);
  }

  /** Payment is refused server-side while the delivery is unratified or refused. */
  ratificationBlocksPayment(bill: SupplierBillDto): boolean {
    return (
      bill.directReceiptRatification === 'AWAITING_RATIFICATION' ||
      bill.directReceiptRatification === 'RATIFICATION_REFUSED'
    );
  }

  /** Why the Record Payment button is unavailable, in the clerk's words. */
  paymentHoldNote(bill: SupplierBillDto): string {
    return bill.directReceiptRatification === 'RATIFICATION_REFUSED'
      ? 'Payment is blocked: a manager refused this delivery.'
      : 'Payment is on hold until a manager confirms this delivery.';
  }
}
