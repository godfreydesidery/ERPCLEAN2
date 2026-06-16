import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierModel } from '../models/party.model';
import { SupplierService } from '../parties/supplier.service';
import {
  ApPaymentDto,
  PaymentRunRequest,
  SupplierBillDto,
  TenderType,
} from './models/ap.model';
import { ApService } from './ap.service';
import { WhtTypeDto } from '../tax/models/tax.model';
import { TaxService } from '../tax/tax.service';

/**
 * Record Payment screen — AP.PAYMENT.RUN.
 *
 * Flow:
 *  1. Pick supplier (typeahead).
 *  2. Their open / matched bills load into a checkbox-selectable list.
 *  3. Enter paymentDate, tenderType, optional bankReference.
 *  4. Submit disabled when zero bills are selected.
 *  5. POST /ap/payments/payment-run → show success summary.
 *
 * Money arrives as number|string on wire — coerce with +v throughout.
 */
@Component({
  selector: 'app-record-payment',
  imports: [FormsModule, RouterLink],
  templateUrl: './record-payment.component.html',
  styleUrl: './record-payment.component.scss',
})
export class RecordPaymentComponent {
  private readonly apService = inject(ApService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly supplierService = inject(SupplierService);
  private readonly taxService = inject(TaxService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Supplier picker ────────────────────────────────────────────────────────
  readonly supplierSearchQ = signal('');
  readonly supplierResults = signal<SupplierModel[]>([]);
  readonly selectedSupplier = signal<{ uid: string; label: string } | null>(null);

  // ── Bill list ──────────────────────────────────────────────────────────────
  readonly bills = signal<SupplierBillDto[]>([]);
  readonly billsState = signal<'idle' | 'loading' | 'error'>('idle');
  /** Set of billUids the user has checked for payment. */
  readonly selectedBillUids = signal<Set<string>>(new Set());

  // ── Payment header ─────────────────────────────────────────────────────────
  readonly paymentDate = signal('');
  readonly tenderType = signal<TenderType>('BANK_TRANSFER');
  readonly bankReference = signal('');

  // ── WHT section (optional, WHT_ON_PAYMENT) ────────────────────────────────
  readonly whtTypes = signal<WhtTypeDto[]>([]);
  readonly whtTypeUid = signal('');
  readonly whtAmount = signal('');

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);
  readonly savedPayments = signal<ApPaymentDto[] | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canPay = computed(() => this.session.hasPermission('AP.PAYMENT.RUN'));

  // ── Computed totals ────────────────────────────────────────────────────────

  readonly selectedTotal = computed(() => {
    const uids = this.selectedBillUids();
    return this.bills()
      .filter((b) => uids.has(b.uid))
      .reduce((sum, b) => sum + +(b.outstandingAmount ?? 0), 0);
  });

  readonly selectedCount = computed(() => this.selectedBillUids().size);

  /** Submit disabled: no supplier, no bills selected, or missing required fields. */
  readonly submitDisabled = computed(() =>
    !this.selectedSupplier() ||
    this.selectedCount() === 0 ||
    !String(this.paymentDate() ?? '').trim() ||
    !this.selectedCompanyId() ||
    this.submitting(),
  );

  private readonly supplierSearch$ = new Subject<string>();

  constructor() {
    this.paymentDate.set(new Date().toISOString().slice(0, 10));

    this.supplierSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) {
            this.supplierResults.set([]);
            return [];
          }
          return this.supplierService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.supplierResults.set(rows.filter((s) => s.status === 'ACTIVE')),
        error: () => this.supplierResults.set([]),
      });

    this.loadCompanies();
  }

  private loadCompanies(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
              this.loadWhtTypes(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadWhtTypes(companyId: string): void {
    this.taxService.listWhtTypes(companyId).subscribe({
      next: (list) => this.whtTypes.set(list.filter((t) => t.active && t.kind === 'WHT_ON_PAYMENT')),
      error: () => this.whtTypes.set([]),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.resetSupplier();
    if (id) this.loadWhtTypes(id);
  }

  // ── Supplier picker ────────────────────────────────────────────────────────

  onSupplierSearchChange(q: string): void {
    this.supplierSearchQ.set(q);
    if (!q.trim()) {
      this.selectedSupplier.set(null);
      this.supplierResults.set([]);
      this.bills.set([]);
      this.selectedBillUids.set(new Set());
      return;
    }
    this.selectedSupplier.set(null);
    this.supplierSearch$.next(q);
  }

  selectSupplier(s: SupplierModel): void {
    this.selectedSupplier.set({ uid: s.uid, label: `${s.code} — ${s.displayName}` });
    this.supplierSearchQ.set(`${s.code} — ${s.displayName}`);
    this.supplierResults.set([]);
    this.loadPayableBills(s.uid);
  }

  private resetSupplier(): void {
    this.selectedSupplier.set(null);
    this.supplierSearchQ.set('');
    this.supplierResults.set([]);
    this.bills.set([]);
    this.selectedBillUids.set(new Set());
  }

  // ── Load payable bills (MATCHED, APPROVED, PARTIALLY_PAID) ────────────────

  private loadPayableBills(supplierUid: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.billsState.set('loading');
    this.bills.set([]);
    this.selectedBillUids.set(new Set());

    // Load up to 200 payable bills — enough for a payment run.
    this.apService.listBills(companyId, supplierUid, undefined, 0, 200).subscribe({
      next: ({ rows }) => {
        const payable = rows.filter((b) =>
          b.status === 'MATCHED' || b.status === 'APPROVED' || b.status === 'PARTIALLY_PAID',
        );
        this.bills.set(payable);
        this.billsState.set('idle');
      },
      error: () => this.billsState.set('error'),
    });
  }

  // ── Bill selection ─────────────────────────────────────────────────────────

  toggleBill(uid: string, checked: boolean): void {
    this.selectedBillUids.update((set) => {
      const next = new Set(set);
      if (checked) next.add(uid); else next.delete(uid);
      return next;
    });
  }

  isBillSelected(uid: string): boolean {
    return this.selectedBillUids().has(uid);
  }

  selectAll(): void {
    this.selectedBillUids.set(new Set(this.bills().map((b) => b.uid)));
  }

  clearSelection(): void {
    this.selectedBillUids.set(new Set());
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  submit(): void {
    if (this.submitDisabled()) return;

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve company.'); return; }

    const supplier = this.selectedSupplier();
    if (!supplier) { this.formError.set('Supplier is required.'); return; }

    const date = String(this.paymentDate() ?? '').trim();
    const bankRef = String(this.bankReference() ?? '').trim();

    if (!date) { this.formError.set('Payment date is required.'); return; }
    if (this.selectedCount() === 0) { this.formError.set('Select at least one bill to pay.'); return; }

    const request: PaymentRunRequest = {
      companyUid: company.uid,
      supplierUid: supplier.uid,
      dueOnOrBefore: date,
      paymentDate: date,
      tenderType: this.tenderType(),
      bankReference: bankRef || null,
      billUids: [...this.selectedBillUids()],
    };

    // Optional WHT (WHT_ON_PAYMENT)
    const whtUid = String(this.whtTypeUid() ?? '').trim();
    const whtAmt = String(this.whtAmount() ?? '').trim();
    if (whtUid && whtAmt && +whtAmt > 0) {
      request.whtTypeUid = whtUid;
      request.whtAmount = whtAmt;
    }

    this.submitting.set(true);
    this.formError.set(null);

    this.apService.paymentRun(request).subscribe({
      next: (payments) => {
        this.submitting.set(false);
        this.savedPayments.set(payments);
        this.alerts.success('Payment run complete', `${payments.length} payment(s) recorded`);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not record payment.'));
        this.submitting.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

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
