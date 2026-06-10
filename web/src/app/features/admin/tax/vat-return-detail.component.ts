import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  AddVatAdjustmentRequest,
  FileVatReturnRequest,
  VatAdjustmentDto,
  VatAdjustmentReason,
  VatAdjustmentSign,
  VatReturnDto,
} from './models/tax.model';
import { TaxService } from './tax.service';

/**
 * VAT Return detail / face screen (FR-VAT-02/08 / US-VAT-02/03/04).
 *
 * Shows:
 *  - Output VAT by band (STANDARD / ZERO_RATED / EXEMPT)
 *  - Input VAT, adjustments total, opening credit, net VAT
 *  - Clearly labels net > 0 as "Payable to TRA", net < 0 as "Credit carried forward"
 *
 * DRAFT actions:
 *  - Recompute (VAT.RETURN.PREPARE)
 *  - File (VAT.RETURN.FILE) — inline confirm form with filingReference + filingDate
 *
 * Adjustments editor (VAT.ADJUST — DRAFT only):
 *  - Lists adjustment lines
 *  - Add line (reason, sign, amount, narrative)
 *  - Remove line
 *
 * FILED: shows filingRef/date + posted journal link, all read-only.
 */
@Component({
  selector: 'app-vat-return-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './vat-return-detail.component.html',
  styleUrl: './vat-return-detail.component.scss',
})
export class VatReturnDetailComponent implements OnInit {
  /** Bound from route path param /tax/vat-returns/uid/:uid */
  readonly uid = input.required<string>();

  private readonly taxService = inject(TaxService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Return ────────────────────────────────────────────────────────────────
  readonly vatReturn = signal<VatReturnDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Adjustments ───────────────────────────────────────────────────────────
  readonly adjustments = signal<VatAdjustmentDto[]>([]);
  readonly adjState = signal<'loading' | 'idle' | 'error'>('idle');
  readonly showAddAdj = signal(false);
  readonly addingAdj = signal(false);
  readonly adjError = signal<string | null>(null);

  // Add adjustment form fields
  readonly adjReason = signal<VatAdjustmentReason>('OTHER');
  readonly adjSign = signal<VatAdjustmentSign>('INCREASE');
  readonly adjAmount = signal('');
  readonly adjNarrative = signal('');

  // Remove loading set
  readonly removingUid = signal<string | null>(null);

  // ── Recompute ─────────────────────────────────────────────────────────────
  readonly recomputing = signal(false);

  // ── File action ───────────────────────────────────────────────────────────
  readonly showFileForm = signal(false);
  readonly filing = signal(false);
  readonly fileError = signal<string | null>(null);
  readonly fileRef = signal('');
  readonly fileDate = signal(new Date().toISOString().slice(0, 10));

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canPrepare = computed(() => this.session.hasPermission('VAT.RETURN.PREPARE'));
  readonly canFile = computed(() => this.session.hasPermission('VAT.RETURN.FILE'));
  readonly canAdjust = computed(() => this.session.hasPermission('VAT.ADJUST'));

  readonly isDraft = computed(() => this.vatReturn()?.status === 'DRAFT');

  readonly adjReasons: Array<{ value: VatAdjustmentReason; label: string }> = [
    { value: 'BAD_DEBT_RELIEF',        label: 'Bad Debt Relief' },
    { value: 'PRIOR_PERIOD_CORRECTION', label: 'Prior Period Correction' },
    { value: 'CREDIT_NOTE_VAT',        label: 'Credit Note VAT' },
    { value: 'DEBIT_NOTE_VAT',         label: 'Debit Note VAT' },
    { value: 'OTHER',                  label: 'Other' },
  ];

  ngOnInit(): void {
    this.loadReturn();
  }

  loadReturn(): void {
    this.state.set('loading');
    this.taxService.getReturn(this.uid()).subscribe({
      next: (ret) => {
        this.vatReturn.set(ret);
        this.state.set('idle');
        this.loadAdjustments();
      },
      error: () => this.state.set('error'),
    });
  }

  private loadAdjustments(): void {
    this.adjState.set('loading');
    this.taxService.listAdjustments(this.uid()).subscribe({
      next: (list) => {
        this.adjustments.set(list);
        this.adjState.set('idle');
      },
      error: () => this.adjState.set('error'),
    });
  }

  // ── Recompute ─────────────────────────────────────────────────────────────

  recompute(): void {
    if (!this.isDraft() || !this.canPrepare()) return;
    this.recomputing.set(true);
    this.taxService.recomputeReturn(this.uid()).subscribe({
      next: (ret) => {
        this.vatReturn.set(ret);
        this.recomputing.set(false);
        this.alerts.success('Recomputed', ret.returnNumber);
      },
      error: (err) => {
        this.recomputing.set(false);
        this.alerts.error('Recompute failed', this.messageFrom(err, 'Could not recompute.'));
      },
    });
  }

  // ── File ─────────────────────────────────────────────────────────────────

  openFileForm(): void {
    this.showFileForm.set(true);
    this.fileError.set(null);
    this.fileRef.set('');
    this.fileDate.set(new Date().toISOString().slice(0, 10));
  }

  cancelFile(): void {
    this.showFileForm.set(false);
    this.fileError.set(null);
  }

  submitFile(): void {
    const ref = String(this.fileRef() ?? '').trim();
    const date = String(this.fileDate() ?? '').trim();
    if (!ref) { this.fileError.set('Filing reference is required.'); return; }
    if (!date) { this.fileError.set('Filing date is required.'); return; }

    const request: FileVatReturnRequest = { filingReference: ref, filingDate: date };
    this.filing.set(true);
    this.fileError.set(null);

    this.taxService.fileReturn(this.uid(), request).subscribe({
      next: (ret) => {
        this.vatReturn.set(ret);
        this.filing.set(false);
        this.showFileForm.set(false);
        this.alerts.success('VAT Return filed', ret.returnNumber);
      },
      error: (err) => {
        this.fileError.set(this.messageFrom(err, 'Could not file return.'));
        this.filing.set(false);
      },
    });
  }

  // ── Adjustments ───────────────────────────────────────────────────────────

  openAddAdj(): void {
    this.showAddAdj.set(true);
    this.adjError.set(null);
    this.adjReason.set('OTHER');
    this.adjSign.set('INCREASE');
    this.adjAmount.set('');
    this.adjNarrative.set('');
  }

  cancelAddAdj(): void {
    this.showAddAdj.set(false);
    this.adjError.set(null);
  }

  submitAddAdj(): void {
    const amtStr = String(this.adjAmount() ?? '').trim();
    if (!amtStr || +amtStr <= 0) { this.adjError.set('Enter a positive amount.'); return; }

    const request: AddVatAdjustmentRequest = {
      reason: this.adjReason(),
      sign: this.adjSign(),
      amount: amtStr,
      narrative: String(this.adjNarrative() ?? '').trim() || undefined,
    };

    this.addingAdj.set(true);
    this.adjError.set(null);

    this.taxService.addAdjustment(this.uid(), request).subscribe({
      next: (adj) => {
        this.adjustments.update((list) => [...list, adj]);
        this.addingAdj.set(false);
        this.showAddAdj.set(false);
        this.alerts.success('Adjustment added', this.adjReasonLabel(adj.reason));
        // Reload the return to get updated totals
        this.taxService.getReturn(this.uid()).subscribe({
          next: (ret) => this.vatReturn.set(ret),
          error: () => undefined,
        });
      },
      error: (err) => {
        this.adjError.set(this.messageFrom(err, 'Could not add adjustment.'));
        this.addingAdj.set(false);
      },
    });
  }

  removeAdj(uid: string): void {
    if (!this.isDraft() || !this.canAdjust()) return;
    if (this.removingUid() !== null) return;
    this.removingUid.set(uid);

    this.taxService.removeAdjustment(this.uid(), uid).subscribe({
      next: () => {
        this.adjustments.update((list) => list.filter((a) => a.uid !== uid));
        this.removingUid.set(null);
        this.alerts.success('Adjustment removed', '');
        this.taxService.getReturn(this.uid()).subscribe({
          next: (ret) => this.vatReturn.set(ret),
          error: () => undefined,
        });
      },
      error: (err) => {
        this.removingUid.set(null);
        this.alerts.error('Remove failed', this.messageFrom(err, 'Could not remove adjustment.'));
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  adjReasonLabel(reason: VatAdjustmentReason): string {
    return this.adjReasons.find((r) => r.value === reason)?.label ?? reason;
  }

  netVatLabel(): string {
    const ret = this.vatReturn();
    if (!ret) return '';
    const net = +(ret.netVat ?? 0);
    if (net > 0.001) return 'Payable to TRA';
    if (net < -0.001) return 'Credit carried forward';
    return 'Nil';
  }

  netVatClass(): string {
    const ret = this.vatReturn();
    if (!ret) return '';
    const net = +(ret.netVat ?? 0);
    if (net > 0.001) return 'text-danger fw-bold';
    if (net < -0.001) return 'text-success fw-bold';
    return '';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
