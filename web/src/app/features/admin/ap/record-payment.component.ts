import { HttpErrorResponse } from '@angular/common/http';
import { INVALID_AMOUNT_MESSAGE, normaliseAmount } from '../../../shared/money.util';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
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
import { DirectReceiptRatificationComponent } from './direct-receipt-ratification.component';
import { WhtTypeDto } from '../tax/models/tax.model';
import { TaxService } from '../tax/tax.service';
import { CashbankService } from '../cashbank/cashbank.service';
import { CashBankAccountDto } from '../cashbank/models/cashbank.model';
import { todayLocal } from '../../../shared/date.util';

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
  imports: [FormsModule, RouterLink, DirectReceiptRatificationComponent],
  templateUrl: './record-payment.component.html',
  styleUrl: './record-payment.component.scss',
})
export class RecordPaymentComponent {
  private readonly apService = inject(ApService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly supplierService = inject(SupplierService);
  private readonly taxService = inject(TaxService);
  private readonly cashbank = inject(CashbankService);
  private readonly alerts = inject(AlertService);
  private readonly route = inject(ActivatedRoute);
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
  /** AP-07: amount to pay per selected bill uid; missing / blank = its full outstanding. */
  readonly payAmounts = signal<Record<string, string>>({});
  /** AP-07: "allocate X oldest-first" helper input. */
  readonly allocateTotal = signal('');

  // ── Payment header ─────────────────────────────────────────────────────────
  readonly paymentDate = signal('');
  readonly tenderType = signal<TenderType>('BANK_TRANSFER');
  readonly bankReference = signal('');

  // ── AP-08: pay-from account ───────────────────────────────────────────────
  /** Active cash / bank / mobile-money accounts of the company. */
  readonly cashAccounts = signal<CashBankAccountDto[]>([]);
  /** Chosen account uid; '' = let the server use the company default account. */
  readonly cashAccountUid = signal('');
  /** The list needs CASH.VIEW; without it the payment still goes through on the default account. */
  readonly cashAccountsUnavailable = signal(false);

  // ── WHT section (optional, WHT_ON_PAYMENT) ────────────────────────────────
  readonly whtTypes = signal<WhtTypeDto[]>([]);
  readonly whtTypeUid = signal('');
  readonly whtAmount = signal('');
  /** True when the WHT type list could not be loaded (non-fatal; WHT section is optional). */
  readonly whtUnavailable = signal(false);

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);
  readonly savedPayments = signal<ApPaymentDto[] | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canPay = computed(() => this.session.hasPermission('AP.PAYMENT.RUN'));

  // ── Computed totals ────────────────────────────────────────────────────────

  readonly selectedTotal = computed(() => {
    const uids = this.selectedBillUids();
    const amounts = this.payAmounts();
    return this.bills()
      .filter((b) => uids.has(b.uid))
      .reduce((sum, b) => sum + this.amountOf(b, amounts), 0);
  });

  /** What a selected bill will receive: the typed amount, else its full outstanding. */
  private amountOf(b: SupplierBillDto, amounts: Record<string, string>): number {
    const typed = String(amounts[b.uid] ?? '').trim();
    return typed ? +typed : +(b.outstandingAmount ?? 0);
  }

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
    this.paymentDate.set(todayLocal());

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
              this.loadCashAccounts(list[0].id);
              this.applyDeepLink();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  /**
   * AP-28: "Pay" on a bill opens this screen with ?billUid= (and ?supplierUid= when known). Select
   * the bill's supplier and tick the bill, so the clerk does not search and tick it all over again.
   * Anything that cannot be resolved simply leaves the screen as it was — the manual path still works.
   */
  private applyDeepLink(): void {
    const qp = this.route.snapshot?.queryParamMap;
    const billUid = qp?.get('billUid') ?? '';
    const supplierUid = qp?.get('supplierUid') ?? '';
    if (billUid) {
      this.apService.getBill(billUid).subscribe({
        next: (bill) => {
          const uid = bill.supplierUid || supplierUid;
          if (!uid) return;
          const label = bill.supplierName || this.supplierSearchQ() || 'Selected supplier';
          this.selectedSupplier.set({ uid, label });
          this.supplierSearchQ.set(label);
          this.loadPayableBills(uid, bill.uid);
        },
        error: () => {},
      });
    } else if (supplierUid) {
      this.supplierService.getByUid(supplierUid).subscribe({
        next: (s) => this.selectSupplier(s),
        error: () => {},
      });
    }
  }

  private loadWhtTypes(companyId: string): void {
    this.whtUnavailable.set(false);
    this.taxService.listWhtTypes(companyId).subscribe({
      next: (list) => this.whtTypes.set(list.filter((t) => t.active && t.kind === 'WHT_ON_PAYMENT')),
      error: () => { this.whtTypes.set([]); this.whtUnavailable.set(true); },
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.resetSupplier();
    this.whtUnavailable.set(false);
    if (id) {
      this.loadWhtTypes(id);
      this.loadCashAccounts(id);
    }
  }

  private loadCashAccounts(companyId: string): void {
    this.cashAccountUid.set('');
    this.cashAccountsUnavailable.set(false);
    this.cashbank.listAllAccounts(companyId).subscribe({
      next: (list) => {
        this.cashAccounts.set(list);
        // Start on the company default so the clerk sees which account that actually is.
        const def = list.find((a) => a.isDefault);
        if (def) this.cashAccountUid.set(def.uid);
      },
      error: () => { this.cashAccounts.set([]); this.cashAccountsUnavailable.set(true); },
    });
  }

  /** "CRDB Main · 0150… (TZS)" — what is printed on the cheque book / statement. */
  cashAccountLabel(a: CashBankAccountDto): string {
    const no = a.bankAccountNo ? ` · ${a.bankAccountNo}` : '';
    return `${a.name}${no} (${a.currency})${a.isDefault ? ' — default' : ''}`;
  }

  // ── Supplier picker ────────────────────────────────────────────────────────

  onSupplierSearchChange(q: string): void {
    this.supplierSearchQ.set(q);
    if (!q.trim()) {
      this.selectedSupplier.set(null);
      this.supplierResults.set([]);
      this.bills.set([]);
      this.selectedBillUids.set(new Set());
      this.payAmounts.set({});
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
    this.payAmounts.set({});
  }

  // ── Load payable bills (MATCHED, APPROVED, PARTIALLY_PAID) ────────────────

  private loadPayableBills(supplierUid: string, preselectBillUid?: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.billsState.set('loading');
    this.bills.set([]);
    this.selectedBillUids.set(new Set());
    this.payAmounts.set({});

    // Load up to 200 payable bills — enough for a payment run.
    this.apService.listBills(companyId, supplierUid, undefined, 0, 200).subscribe({
      next: ({ rows }) => {
        const payable = rows.filter((b) =>
          b.status === 'MATCHED' || b.status === 'APPROVED' || b.status === 'PARTIALLY_PAID',
        );
        this.bills.set(payable);
        this.billsState.set('idle');
        if (preselectBillUid && payable.some((b) => b.uid === preselectBillUid)) {
          this.selectedBillUids.set(new Set([preselectBillUid]));
        }
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
    if (!checked) this.setPayAmount(uid, '');
  }

  /** AP-07: the amount typed for one bill ('' = pay it in full). */
  payAmountOf(uid: string): string {
    return this.payAmounts()[uid] ?? '';
  }

  setPayAmount(uid: string, value: string): void {
    this.payAmounts.update((m) => {
      const next = { ...m };
      const v = String(value ?? '').trim();
      if (v) next[uid] = v; else delete next[uid];
      return next;
    });
  }

  /**
   * AP-07: spread a lump sum over the supplier's bills, oldest due first; the last bill reached
   * takes the remainder as a part-payment. Never puts more on a bill than it still owes.
   */
  allocateOldestFirst(): void {
    let remaining = +String(this.allocateTotal() ?? '').trim();
    if (!Number.isFinite(remaining) || remaining <= 0) {
      this.formError.set('Enter the amount you are paying to spread it over the bills.');
      return;
    }
    this.formError.set(null);
    const ordered = [...this.bills()].sort((a, b) =>
      String(a.dueDate ?? a.billDate ?? '9999').localeCompare(String(b.dueDate ?? b.billDate ?? '9999'))
      || String(a.billDate ?? '').localeCompare(String(b.billDate ?? '')));
    const picked = new Set<string>();
    const amounts: Record<string, string> = {};
    for (const b of ordered) {
      if (remaining <= 0.000001) break;
      const owed = +(b.outstandingAmount ?? 0);
      if (owed <= 0) continue;
      const pay = Math.min(owed, remaining);
      picked.add(b.uid);
      if (pay < owed) amounts[b.uid] = (Math.round(pay * 100) / 100).toFixed(2);
      remaining = Math.round((remaining - pay) * 100) / 100;
    }
    this.selectedBillUids.set(picked);
    this.payAmounts.set(amounts);
    if (remaining > 0) {
      this.formError.set(
        `That is ${this.fmtMoney(remaining)} more than these bills owe; only what is owed was allocated.`);
    }
  }

  isBillSelected(uid: string): boolean {
    return this.selectedBillUids().has(uid);
  }

  selectAll(): void {
    this.selectedBillUids.set(new Set(this.bills().map((b) => b.uid)));
  }

  clearSelection(): void {
    this.selectedBillUids.set(new Set());
    this.payAmounts.set({});
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

    // AP-07: part-payments. Only amounts below the outstanding are sent; the rest pay in full.
    const amounts = this.payAmounts();
    const partial: Record<string, string> = {};
    for (const b of this.bills()) {
      if (!this.selectedBillUids().has(b.uid)) continue;
      const typed = String(amounts[b.uid] ?? '').trim();
      if (!typed) continue;
      const n = +typed;
      const owed = +(b.outstandingAmount ?? 0);
      const label = b.supplierInvoiceNo || b.billNumber;
      if (!Number.isFinite(n) || n <= 0) {
        this.formError.set(`The amount to pay on bill ${label} must be more than zero.`);
        return;
      }
      if (n > owed + 0.000001) {
        this.formError.set(`The amount to pay on bill ${label} is more than is still owed (${this.fmtMoney(owed)}).`);
        return;
      }
      if (n < owed) partial[b.uid] = typed;
    }
    if (Object.keys(partial).length > 0) request.billAmounts = partial;

    // AP-08: send the chosen account; omitted = the company default (server-side fallback).
    const accountUid = String(this.cashAccountUid() ?? '').trim();
    if (accountUid) request.cashBankAccountUid = accountUid;

    // Optional WHT (WHT_ON_PAYMENT)
    const whtUid = String(this.whtTypeUid() ?? '').trim();
    // LUI-04: "1,800" used to read as NaN and the WHT was silently dropped from the payment.
    const whtNorm = normaliseAmount(this.whtAmount());
    if (whtNorm === null) { this.formError.set(INVALID_AMOUNT_MESSAGE); return; }
    const whtAmt = whtNorm;
    if (whtUid && whtAmt && +whtAmt > 0) {
      request.whtTypeUid = whtUid;
      request.whtAmount = whtAmt;
    }

    this.submitting.set(true);
    this.formError.set(null);

    this.apService.paymentRun(request).subscribe({
      next: (payment) => {
        this.submitting.set(false);
        // AP-17: the run returns ONE payment; the success panel lists what was recorded.
        this.savedPayments.set(payment ? [payment] : []);
        this.alerts.success(
          'Payment recorded',
          payment?.paymentNumber ? `Payment ${payment.paymentNumber} recorded` : 'Payment recorded',
        );
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
