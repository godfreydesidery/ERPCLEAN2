import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AccountDto } from '../gl/models/gl.model';
import { GlService } from '../gl/gl.service';
import {
  CashAccountOptionDto,
  CashTransactionDto,
  CashTxnDirection,
  RecordDirectEntryRequest,
} from './models/cashbank.model';
import { CashbankService } from './cashbank.service';
import { todayLocal } from '../../../shared/date.util';

/** Counter-account types in the order they are offered: what the money most likely was first. */
const COUNTER_ORDER: Record<CashTxnDirection, readonly string[]> = {
  OUT: ['EXPENSE', 'EQUITY', 'INCOME'],
  IN: ['INCOME', 'EQUITY', 'EXPENSE'],
};

/**
 * Record Direct Cash/Bank Entry screen. Gated CASH.ENTRY.RECORD.
 * Account, direction (IN/OUT), amount, date, counter GL account picker, memo.
 *
 * ARC-12 / LBO-17: this is the screen an owner uses to pay rent, electricity or transport out of
 * cash, so it now defaults to money OUT, offers expense accounts first, and lists the entries
 * already recorded on the chosen account (CASH.VIEW) so today's expenses can be checked.
 */
@Component({
  selector: 'app-record-entry',
  imports: [FormsModule],
  templateUrl: './record-entry.component.html',
  styleUrl: './record-entry.component.scss',
})
export class RecordEntryComponent {
  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly glService = inject(GlService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Cash accounts ──────────────────────────────────────────────────────────
  /** Narrow option rows (GET /cash/accounts/options) — open to CASH.ENTRY.RECORD, no CASH.VIEW needed. */
  readonly cashAccounts = signal<CashAccountOptionDto[]>([]);
  readonly cashAccountsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Entries already on the chosen account (needs CASH.VIEW) ───────────────
  readonly entries = signal<CashTransactionDto[]>([]);
  readonly entriesState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly canViewEntries = computed(() => this.session.hasPermission('CASH.VIEW'));
  /** Direct entries only (receipts / payments / transfers live on their own screens), newest first. */
  readonly recentEntries = computed(() =>
    this.entries()
      .filter((t) => t.txnType === 'DIRECT_ENTRY')
      .slice()
      .reverse()
      .slice(0, 25),
  );
  readonly selectedAccount = computed(() =>
    this.cashAccounts().find((a) => a.uid === this.selectedAccountUid()) ?? null,
  );

  // ── GL accounts (counter) ──────────────────────────────────────────────────
  readonly glAccounts = signal<AccountDto[]>([]);
  readonly glAccountsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly selectedAccountUid = signal('');
  /** ARC-12: most entries made here are expenses paid out, so money OUT is the default. */
  readonly direction = signal<CashTxnDirection>('OUT');
  readonly amount = signal('');
  readonly txnDate = signal('');
  readonly counterGlAccountUid = signal('');
  readonly memo = signal('');
  /** ACC-13 / PAR-08: input VAT included in the amount — money OUT only. */
  readonly vatAmount = signal('');

  /** Fill the VAT field with 18/118 of the amount (VAT-inclusive standard rate). */
  vatFromAmount(): void {
    const amt = this.amountNum();
    if (amt > 0) this.vatAmount.set((Math.round((amt * 18) / 118 * 100) / 100).toFixed(2));
  }

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);
  readonly savedEntry = signal<CashTransactionDto | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canRecord = computed(() => this.session.hasPermission('CASH.ENTRY.RECORD'));

  readonly amountNum = computed(() => {
    const v = +(String(this.amount() ?? '').trim() || '0');
    return Number.isFinite(v) ? v : 0;
  });

  readonly submitDisabled = computed(() =>
    !this.selectedAccountUid() ||
    !String(this.amount() ?? '').trim() ||
    this.amountNum() <= 0 ||
    !String(this.txnDate() ?? '').trim() ||
    !this.counterGlAccountUid() ||
    !this.selectedCompanyId() ||
    this.submitting(),
  );

  /**
   * GL accounts that are not the cash/bank asset account — income/expense/equity for counter.
   * Ordered by what the money most likely was: expenses first for money out, income first for in.
   */
  readonly counterGlOptions = computed(() => {
    const order = COUNTER_ORDER[this.direction()] ?? COUNTER_ORDER.OUT;
    return this.glAccounts()
      .filter((a) => order.includes(a.accountType))
      .sort((a, b) =>
        order.indexOf(a.accountType) - order.indexOf(b.accountType) ||
        String(a.accountCode).localeCompare(String(b.accountCode)),
      );
  });

  constructor() {
    this.txnDate.set(todayLocal());
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
              this.loadCashAccounts(list[0].id);
              this.loadGlAccounts(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadCashAccounts(companyId: string): void {
    this.cashAccountsState.set('loading');
    this.cashbankService.listAccountOptions(companyId).subscribe({
      next: (list) => {
        this.cashAccounts.set(list ?? []);
        this.cashAccountsState.set('idle');
        // Preselect this branch's cash drawer (then the company default) — where expenses are paid from.
        const preferred =
          list.find((a) => a.accountType === 'CASH' && a.inCurrentBranch) ??
          list.find((a) => a.isDefault);
        if (preferred && !this.selectedAccountUid()) this.onAccountChange(preferred.uid);
      },
      error: () => this.cashAccountsState.set('error'),
    });
  }

  onAccountChange(uid: string): void {
    this.selectedAccountUid.set(uid ?? '');
    this.loadEntries();
  }

  /** The chosen account's direct entries (GET /cash/entries needs companyId AND accountId). */
  loadEntries(): void {
    const account = this.selectedAccount();
    const companyId = this.selectedCompanyId();
    if (!account || !companyId || !this.canViewEntries()) {
      this.entries.set([]);
      this.entriesState.set('idle');
      return;
    }
    this.entriesState.set('loading');
    this.cashbankService.listEntries(companyId, String(account.id)).subscribe({
      next: (rows) => {
        this.entries.set(rows ?? []);
        this.entriesState.set('idle');
      },
      error: () => this.entriesState.set('error'),
    });
  }

  counterAccountLabel(id: string | null): string {
    if (id == null) return '—';
    const gl = this.glAccounts().find((a) => String(a.id) === String(id));
    return gl ? `${gl.accountCode} — ${gl.name}` : '—';
  }

  private loadGlAccounts(companyId: string): void {
    this.glAccountsState.set('loading');
    this.glService.listAllActiveAccounts(companyId).subscribe({
      next: (list) => {
        this.glAccounts.set(list);
        this.glAccountsState.set('idle');
      },
      error: () => this.glAccountsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedAccountUid.set('');
    this.counterGlAccountUid.set('');
    this.entries.set([]);
    if (id) {
      this.loadCashAccounts(id);
      this.loadGlAccounts(id);
    }
  }

  submit(): void {
    if (this.submitDisabled()) return;

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve company.'); return; }

    const accountUid = String(this.selectedAccountUid() ?? '').trim();
    const amt = String(this.amount() ?? '').trim();
    const date = String(this.txnDate() ?? '').trim();
    const counterUid = String(this.counterGlAccountUid() ?? '').trim();
    const memoVal = String(this.memo() ?? '').trim();

    if (!accountUid) { this.formError.set('Cash account is required.'); return; }
    if (!amt || +amt <= 0) { this.formError.set('Enter a valid amount.'); return; }
    if (!date) { this.formError.set('Transaction date is required.'); return; }
    if (!counterUid) { this.formError.set('Counter GL account is required.'); return; }
    const vat = this.direction() === 'OUT' ? String(this.vatAmount() ?? '').trim() : '';
    if (vat && (!(+vat >= 0) || +vat >= +amt)) {
      this.formError.set('The VAT must be less than the amount paid.');
      return;
    }

    const request: RecordDirectEntryRequest = {
      companyUid: company.uid,
      cashBankAccountUid: accountUid,
      direction: this.direction(),
      amount: amt,
      txnDate: date,
      counterGlAccountUid: counterUid,
      memo: memoVal || undefined,
      vatAmount: vat && +vat > 0 ? vat : undefined,
    };

    this.submitting.set(true);
    this.formError.set(null);

    this.cashbankService.recordEntry(request).subscribe({
      next: (txn) => {
        this.submitting.set(false);
        this.alerts.success('Entry recorded', String(txn.txnNumber ?? ''));
        this.savedEntry.set(txn);
        this.loadEntries();
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not record entry.'));
        this.submitting.set(false);
      },
    });
  }

  reset(): void {
    // Keep the chosen cash account (and its list): the next expense is usually paid from the same drawer.
    this.savedEntry.set(null);
    this.direction.set('OUT');
    this.amount.set('');
    this.vatAmount.set('');
    this.counterGlAccountUid.set('');
    this.memo.set('');
    this.txnDate.set(todayLocal());
    this.formError.set(null);
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
