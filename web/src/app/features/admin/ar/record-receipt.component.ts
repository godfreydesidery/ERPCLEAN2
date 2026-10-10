import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerModel } from '../models/party.model';
import { CustomerService } from '../parties/customer.service';
import {
  ArInvoiceDto,
  ArReceiptDto,
  AllocationLineRequest,
  RecordReceiptRequest,
  TenderType,
} from './models/ar.model';
import { ArService } from './ar.service';
import { WhtTypeDto } from '../tax/models/tax.model';
import { TaxService } from '../tax/tax.service';
import { CashbankService } from '../cashbank/cashbank.service';
import { CashAccountOptionDto } from '../cashbank/models/cashbank.model';
import { CurrencySelectComponent } from '../../../shared/currency-select/currency-select.component';
import {
  INVALID_AMOUNT_MESSAGE,
  isInvalidAmount,
  normaliseAmount,
  parseAmount,
} from '../../../shared/money.util';

/**
 * A UI-only allocation row — wraps an ArInvoiceDto with the user's input.
 */
interface AllocationRow {
  invoice: ArInvoiceDto;
  /** User-entered allocation amount (string so the input stays reactive). */
  allocInput: string;
}

const MOBILE_MONEY_NAME = /m-?pesa|mobile|tigo|airtel|halo ?pesa|t-?pesa|wallet|lipa/i;

/** Mobile-money wallets are BANK-type accounts; the code or name tells them apart (ARC-05). */
export function isMobileMoneyAccount(a: CashAccountOptionDto): boolean {
  return a.accountType === 'BANK' && MOBILE_MONEY_NAME.test(`${a.code} ${a.name}`);
}

/**
 * The account a receipt most likely landed in, from its tender (ARC-05): cash → a cash account of
 * this branch, M-Pesa → a mobile-money account, cheque / transfer / card → a bank account. Within a
 * kind, the current branch's account wins, then the company default, then the first listed.
 * Returns '' when nothing fits, which lets the server fall back to the company default account.
 */
export function suggestAccountForTender(tender: TenderType, options: CashAccountOptionDto[]): string {
  const pick = (cands: CashAccountOptionDto[]): string =>
    (cands.find((a) => a.inCurrentBranch) ?? cands.find((a) => a.isDefault) ?? cands[0])?.uid ?? '';
  const cash = options.filter((a) => a.accountType === 'CASH');
  const mobile = options.filter(isMobileMoneyAccount);
  const bank = options.filter((a) => a.accountType === 'BANK' && !isMobileMoneyAccount(a));
  let chosen = '';
  if (tender === 'CASH') chosen = pick(cash);
  else if (tender === 'MOBILE_MONEY') chosen = pick(mobile) || pick(bank);
  else chosen = pick(bank) || pick(mobile);
  return chosen || (options.find((a) => a.isDefault)?.uid ?? '');
}

/**
 * Record Receipt screen — centerpiece of the AR module.
 * Gated AR.RECEIPT.RECORD.
 *
 * Flow:
 *  1. Pick customer (typeahead).
 *  2. Enter amount, currency, date, tender type, optional bank ref.
 *  3. The customer's OPEN/PARTIAL invoices load into an allocation editor.
 *  4. User fills allocation amounts or clicks "Auto oldest-first".
 *  5. Client guards: allocated total <= receipt amount; each allocation <= invoice outstanding.
 *  6. Submit → POST /ar/receipts → navigate to receipt confirmation.
 *
 * Money coercion: all ArInvoiceDto amounts arrive as number|string on the wire.
 * Use +v throughout; never call .startsWith/.trim on a money value.
 */
@Component({
  selector: 'app-record-receipt',
  imports: [FormsModule, RouterLink, CurrencySelectComponent],
  templateUrl: './record-receipt.component.html',
  styleUrl: './record-receipt.component.scss',
})
export class RecordReceiptComponent {
  private readonly arService = inject(ArService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  private readonly taxService = inject(TaxService);
  private readonly cashbankService = inject(CashbankService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = computed(() => this.companies().find((c) => c.id === this.selectedCompanyId())?.uid ?? '');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Customer picker ────────────────────────────────────────────────────────
  readonly customerSearchQ = signal('');
  readonly customerResults = signal<CustomerModel[]>([]);
  readonly selectedCustomer = signal<{ uid: string; label: string } | null>(null);

  // ── Receipt header ─────────────────────────────────────────────────────────
  readonly receiptAmount = signal('');
  readonly receiptCurrency = signal('TZS');
  readonly receiptDate = signal('');
  readonly tenderType = signal<TenderType>('CASH');
  readonly bankReference = signal('');

  // ── Deposit-to account (ARC-05) ───────────────────────────────────────────
  /** Active cash / bank / M-Pesa accounts the money can land in. Empty = server default only. */
  readonly accountOptions = signal<CashAccountOptionDto[]>([]);
  /** Chosen account uid; '' = let the server use the company default account. */
  readonly cashBankAccountUid = signal('');
  /** Once the user picks an account by hand, changing the tender no longer overrides it. */
  private accountChosenByUser = false;

  // ── Allocation editor ──────────────────────────────────────────────────────
  readonly allocationRows = signal<AllocationRow[]>([]);
  readonly openInvoicesState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── WHT section (optional, WHT_ON_RECEIPT) ────────────────────────────────
  readonly whtTypes = signal<WhtTypeDto[]>([]);
  readonly whtTypeUid = signal('');
  readonly whtAmount = signal('');
  /** True when the WHT type list could not be loaded (non-fatal; WHT section is optional). */
  readonly whtUnavailable = signal(false);

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Success (navigate to receipt detail) ──────────────────────────────────
  readonly savedReceipt = signal<ArReceiptDto | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canRecord = computed(() => this.session.hasPermission('AR.RECEIPT.RECORD'));

  // ── Computed allocation totals (safe coercion) ────────────────────────────

  /** Sum of all filled allocation inputs. Coerces with +v. */
  readonly allocatedTotal = computed(() =>
    this.allocationRows().reduce((sum, r) => sum + (parseAmount(r.allocInput) ?? 0), 0),
  );

  /**
   * LUI-04: "68,300" used to read as 0 (`+"68,300"` is NaN), which disabled Record and claimed the
   * allocations exceeded a zero receipt. Separators are now stripped by the shared parser.
   */
  readonly receiptAmountNum = computed(() => parseAmount(this.receiptAmount()) ?? 0);

  /** True when something is typed in Amount but it is not a readable number. */
  readonly receiptAmountInvalid = computed(() => isInvalidAmount(this.receiptAmount()));

  /** True when any amount field (receipt, WHT, an allocation) cannot be read. */
  readonly anyAmountInvalid = computed(() =>
    this.receiptAmountInvalid() ||
    isInvalidAmount(this.whtAmount()) ||
    this.allocationRows().some((r) => isInvalidAmount(r.allocInput)),
  );

  readonly invalidAmountMessage = INVALID_AMOUNT_MESSAGE;

  /** Unallocated remainder (on-account). May be positive; never negative if guard holds. */
  readonly unallocated = computed(() => {
    const r = this.receiptAmountNum() - this.allocatedTotal();
    return Math.max(0, r);
  });

  /** True when allocated total exceeds receipt amount (client guard). */
  readonly overAllocated = computed(() =>
    this.allocatedTotal() > this.receiptAmountNum() + 0.000001,
  );

  /** True if any single allocation exceeds the invoice's outstanding (client guard). */
  readonly anyAllocationExceedsOutstanding = computed(() =>
    this.allocationRows().some((r) => {
      const allocated = parseAmount(r.allocInput) ?? 0;
      const outstanding = +(r.invoice.outstandingAmount ?? 0);
      return allocated > outstanding + 0.000001;
    }),
  );

  /** Submit disabled when over-allocated, or an allocation exceeds outstanding, or missing fields. */
  readonly submitDisabled = computed(() =>
    !this.selectedCustomer() ||
    !String(this.receiptAmount() ?? '').trim() ||
    this.receiptAmountNum() <= 0 ||
    this.anyAmountInvalid() ||
    !String(this.receiptDate() ?? '').trim() ||
    !this.selectedCompanyId() ||
    this.overAllocated() ||
    this.anyAllocationExceedsOutstanding() ||
    this.submitting(),
  );

  private readonly customerSearch$ = new Subject<string>();

  constructor() {
    this.receiptDate.set(new Date().toISOString().slice(0, 10));

    // Debounced customer search
    this.customerSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) {
            this.customerResults.set([]);
            return [];
          }
          return this.customerService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.customerResults.set(rows.filter((c) => c.status === 'ACTIVE')),
        error: () => this.customerResults.set([]),
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
              this.loadAccountOptions(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadWhtTypes(companyId: string): void {
    this.whtUnavailable.set(false);
    this.taxService.listWhtTypes(companyId).subscribe({
      next: (list) => this.whtTypes.set(list.filter((t) => t.active && t.kind === 'WHT_ON_RECEIPT')),
      error: () => { this.whtTypes.set([]); this.whtUnavailable.set(true); },
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.resetCustomer();
    this.whtUnavailable.set(false);
    this.accountChosenByUser = false;
    this.accountOptions.set([]);
    this.cashBankAccountUid.set('');
    if (id) {
      this.loadWhtTypes(id);
      this.loadAccountOptions(id);
    }
  }

  // ── Deposit-to account (ARC-05) ───────────────────────────────────────────

  private loadAccountOptions(companyId: string): void {
    this.cashbankService.listAccountOptions(companyId).subscribe({
      next: (list) => {
        this.accountOptions.set(list ?? []);
        this.applySuggestedAccount();
      },
      // Non-fatal: without the list the receipt still posts to the company default account.
      error: () => { this.accountOptions.set([]); this.cashBankAccountUid.set(''); },
    });
  }

  onTenderChange(t: TenderType): void {
    this.tenderType.set(t);
    if (!this.accountChosenByUser) this.applySuggestedAccount();
  }

  onAccountChange(uid: string): void {
    this.cashBankAccountUid.set(uid ?? '');
    this.accountChosenByUser = true;
  }

  private applySuggestedAccount(): void {
    this.cashBankAccountUid.set(suggestAccountForTender(this.tenderType(), this.accountOptions()));
  }

  accountLabel(a: CashAccountOptionDto): string {
    const kind = a.accountType === 'CASH' ? 'Cash' : isMobileMoneyAccount(a) ? 'Mobile money' : 'Bank';
    return `${a.code} — ${a.name} (${kind}${a.isDefault ? ', default' : ''})`;
  }

  // ── Customer picker ────────────────────────────────────────────────────────

  onCustomerSearchChange(q: string): void {
    this.customerSearchQ.set(q);
    if (!q.trim()) {
      this.selectedCustomer.set(null);
      this.customerResults.set([]);
      this.allocationRows.set([]);
      return;
    }
    this.selectedCustomer.set(null);
    this.customerSearch$.next(q);
  }

  selectCustomer(c: CustomerModel): void {
    this.selectedCustomer.set({ uid: c.uid, label: `${c.code} — ${c.displayName}` });
    this.customerSearchQ.set(`${c.code} — ${c.displayName}`);
    this.customerResults.set([]);
    this.loadOpenInvoices(c.uid);
  }

  private resetCustomer(): void {
    this.selectedCustomer.set(null);
    this.customerSearchQ.set('');
    this.customerResults.set([]);
    this.allocationRows.set([]);
  }

  // ── Load open invoices for allocation editor ───────────────────────────────

  private loadOpenInvoices(customerUid: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    this.openInvoicesState.set('loading');
    this.allocationRows.set([]);

    // Every open / part-paid item of THIS customer, oldest due date first (server-side filter).
    this.arService.listOpenInvoices(companyId, customerUid).subscribe({
      next: (rows) => {
        this.allocationRows.set(rows.map((inv) => ({ invoice: inv, allocInput: '' })));
        this.openInvoicesState.set('idle');
      },
      error: () => this.openInvoicesState.set('error'),
    });
  }

  // ── Allocation editor helpers ─────────────────────────────────────────────

  updateAllocation(invoiceUid: string, value: string): void {
    this.allocationRows.update((rows) =>
      rows.map((r) =>
        r.invoice.uid === invoiceUid ? { ...r, allocInput: value } : r,
      ),
    );
  }

  /**
   * Auto oldest-first allocation.
   * Sorts invoices by invoiceDate ascending, fills each in turn up to the lesser of
   * (invoice.outstandingAmount, remaining unallocated budget).
   */
  autoAllocateOldestFirst(): void {
    let budget = this.receiptAmountNum();
    if (budget <= 0) return;

    const sorted = [...this.allocationRows()].sort((a, b) =>
      String(a.invoice.invoiceDate ?? '').localeCompare(String(b.invoice.invoiceDate ?? '')),
    );

    const uidOrder = sorted.map((r) => r.invoice.uid);
    this.allocationRows.update((rows) => {
      let remaining = budget;
      const rowMap = new Map(rows.map((r) => [r.invoice.uid, r]));
      const result: AllocationRow[] = [];

      for (const uid of uidOrder) {
        const r = rowMap.get(uid);
        if (!r) continue;
        const outstanding = +(r.invoice.outstandingAmount ?? 0);
        const toAllocate = Math.min(remaining, outstanding);
        result.push({ ...r, allocInput: toAllocate > 0 ? toAllocate.toFixed(2) : '' });
        remaining = Math.max(0, remaining - toAllocate);
        rowMap.delete(uid);
      }

      // Append any rows not in sorted (shouldn't happen, but be safe)
      for (const r of rowMap.values()) result.push(r);

      return result;
    });
  }

  clearAllocations(): void {
    this.allocationRows.update((rows) => rows.map((r) => ({ ...r, allocInput: '' })));
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  submit(): void {
    if (this.submitDisabled()) return;

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve company.'); return; }

    const customer = this.selectedCustomer();
    if (!customer) { this.formError.set('Customer is required.'); return; }

    // Normalised: "68,300" goes on the wire as "68300" (LUI-04).
    const amount = normaliseAmount(this.receiptAmount()) ?? '';
    const currency = String(this.receiptCurrency() ?? '').trim();
    const date = String(this.receiptDate() ?? '').trim();
    const bankRef = String(this.bankReference() ?? '').trim();

    if (!amount || +amount <= 0) { this.formError.set('Enter a valid receipt amount.'); return; }
    if (!currency) { this.formError.set('Currency is required.'); return; }
    if (!date) { this.formError.set('Receipt date is required.'); return; }

    const allocations: AllocationLineRequest[] = this.allocationRows()
      .filter((r) => (parseAmount(r.allocInput) ?? 0) > 0)
      .map((r) => ({
        arInvoiceUid: r.invoice.uid,
        allocatedAmount: normaliseAmount(r.allocInput) ?? '',
      }));

    const request: RecordReceiptRequest = {
      companyUid: company.uid,
      customerUid: customer.uid,
      amount,
      currency,
      receiptDate: date,
      tenderType: this.tenderType(),
      bankReference: bankRef || undefined,
      allocations,
    };

    // ARC-05: the account the money landed in; omitted = company default account.
    const accountUid = String(this.cashBankAccountUid() ?? '').trim();
    if (accountUid) request.cashBankAccountUid = accountUid;

    // Optional WHT (WHT_ON_RECEIPT)
    const whtUid = String(this.whtTypeUid() ?? '').trim();
    const whtAmt = normaliseAmount(this.whtAmount()) ?? '';
    if (whtUid && whtAmt && +whtAmt > 0) {
      request.whtTypeUid = whtUid;
      request.whtAmount = whtAmt;
    }

    this.submitting.set(true);
    this.formError.set(null);

    this.arService.recordReceipt(request).subscribe({
      next: (receipt) => {
        this.submitting.set(false);
        this.alerts.success('Receipt recorded', String(receipt.receiptNumber ?? ''));
        this.savedReceipt.set(receipt);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not record receipt.'));
        this.submitting.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Coerce money value — number or string on wire — to display string. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  /** Returns CSS class for an allocation row: danger when over outstanding, warning when partial. */
  allocationRowClass(row: AllocationRow): string {
    const allocated = parseAmount(row.allocInput) ?? 0;
    const outstanding = +(row.invoice.outstandingAmount ?? 0);
    if (allocated > outstanding + 0.000001) return 'table-danger';
    if (allocated > 0 && allocated < outstanding) return 'table-warning';
    if (allocated >= outstanding - 0.000001 && allocated > 0) return 'table-success';
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
