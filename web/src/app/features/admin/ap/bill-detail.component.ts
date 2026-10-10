import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BillMatchResultDto, LineMatchDto, SupplierBillDto } from './models/ap.model';
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
 *
 * <p><b>AP-01 — a held or failed bill is no longer a dead end.</b> The entry screen tells the
 * accountant to "re-run the match from the bill detail screen"; this is that screen. A DRAFT or
 * HELD bill (nothing posted) offers:
 * <ul>
 *   <li><b>Run match</b> (AP.BILL.MATCH) — re-runs the 3-way match, e.g. after the goods receipt
 *       was corrected or the missing FX rate was entered;
 *   <li><b>Accept variance</b> per held line (AP.BILL.MATCH) — the audited override;
 *   <li><b>Delete bill</b> (AP.BILL.ENTER) — removes it so the invoice can be entered again.
 * </ul>
 */
@Component({
  selector: 'app-bill-detail',
  imports: [RouterLink, DirectReceiptRatificationComponent],
  templateUrl: './bill-detail.component.html',
  styleUrl: './bill-detail.component.scss',
})
export class BillDetailComponent implements OnInit {
  private readonly apService = inject(ApService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  /** Route param :uid — bound via withComponentInputBinding. */
  readonly uid = input<string>('');

  readonly bill = signal<SupplierBillDto | null>(null);
  readonly state = signal<LoadState>('loading');

  readonly canPay = computed(() => this.session.hasPermission('AP.PAYMENT.RUN'));
  readonly canView = computed(() => this.session.hasPermission('AP.VIEW'));
  readonly canMatch = computed(() => this.session.hasPermission('AP.BILL.MATCH'));
  readonly canEnter = computed(() => this.session.hasPermission('AP.BILL.ENTER'));

  // ── Match / correction state (AP-01) ──────────────────────────────────────
  readonly matchResult = signal<BillMatchResultDto | null>(null);
  readonly matchState = signal<'idle' | 'running' | 'done' | 'error'>('idle');
  readonly matchError = signal<string | null>(null);
  readonly acceptingLine = signal<string | null>(null);
  readonly deleting = signal(false);
  readonly deleteError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    const u = this.uid();
    if (!u) { this.state.set('error'); return; }
    this.apService.getBill(u).subscribe({
      next: (b) => { this.bill.set(b); this.state.set('idle'); },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── AP-01: corrections on an unposted bill ─────────────────────────────────

  /** DRAFT or HELD, with nothing posted — the only bills that can be re-matched or deleted. */
  isUnposted(bill: SupplierBillDto): boolean {
    return (bill.status === 'DRAFT' || bill.status === 'HELD') && !bill.postedGlEntryUid;
  }

  runMatch(): void {
    const b = this.bill();
    if (!b || this.matchState() === 'running') return;
    this.matchState.set('running');
    this.matchError.set(null);
    this.apService.runMatch(b.uid).subscribe({
      next: (result) => {
        this.matchResult.set(result);
        this.matchState.set('done');
        if (result.billStatus === 'MATCHED') {
          this.alerts.success('Bill matched and posted');
        }
        this.load();
      },
      error: (err) => {
        this.matchState.set('error');
        this.matchError.set(this.messageFrom(err, 'The match could not be run. Please try again.'));
      },
    });
  }

  acceptVariance(lineUid: string): void {
    const b = this.bill();
    if (!b || this.acceptingLine()) return;
    this.acceptingLine.set(lineUid);
    this.apService.acceptVariance(b.uid, { billLineUid: lineUid }).subscribe({
      next: (result) => {
        this.matchResult.set(result);
        this.acceptingLine.set(null);
        this.alerts.success('Variance accepted');
        this.load();
      },
      error: (err) => {
        this.acceptingLine.set(null);
        this.matchError.set(this.messageFrom(err, 'Could not accept the variance.'));
      },
    });
  }

  deleteBill(): void {
    const b = this.bill();
    if (!b || this.deleting()) return;
    const label = b.supplierInvoiceNo || b.billNumber || 'this bill';
    if (!window.confirm(`Delete bill ${label}? You can then enter the invoice again.`)) return;
    this.deleting.set(true);
    this.deleteError.set(null);
    this.apService.deleteBill(b.uid).subscribe({
      next: () => {
        this.deleting.set(false);
        this.alerts.success('Bill deleted', label);
        void this.router.navigate(['/admin/ap/supplier-bills']);
      },
      error: (err) => {
        this.deleting.set(false);
        this.deleteError.set(this.messageFrom(err, 'Could not delete the bill.'));
      },
    });
  }

  isHeld(line: LineMatchDto): boolean {
    return line.matchStatus === 'HELD_PRICE_VARIANCE' || line.matchStatus === 'HELD_QTY_VARIANCE';
  }

  /** Human status, never the raw enum. */
  lineStatusLabel(line: LineMatchDto): string {
    if (line.matchStatus === 'VARIANCE_ACCEPTED') return 'Variance accepted';
    if (line.comparisonPerformed === false) {
      return line.matchStatus === 'MATCHED' ? 'Accepted — not checked' : 'On hold — not checked';
    }
    switch (line.matchStatus) {
      case 'MATCHED':
        return 'Matched';
      case 'HELD_PRICE_VARIANCE':
        return 'On hold — price differs';
      case 'HELD_QTY_VARIANCE':
        return 'On hold — quantity differs';
      default:
        return 'On hold';
    }
  }

  /** Bill line description for a match result row (the result carries only the line uid). */
  lineDescription(lineUid: string): string {
    return this.bill()?.lines?.find((l) => l.uid === lineUid)?.description ?? '';
  }

  acceptLabel(line: LineMatchDto): string {
    return line.comparisonPerformed === false ? 'Post without checking' : 'Accept variance';
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

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
