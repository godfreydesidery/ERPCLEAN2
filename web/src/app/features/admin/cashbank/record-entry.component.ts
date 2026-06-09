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
  CashBankAccountDto,
  CashTransactionDto,
  CashTxnDirection,
  RecordDirectEntryRequest,
} from './models/cashbank.model';
import { CashbankService } from './cashbank.service';

/**
 * Record Direct Cash/Bank Entry screen. Gated CASH.ENTRY.RECORD.
 * Account, direction (IN/OUT), amount, date, counter GL account picker, memo.
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
  readonly cashAccounts = signal<CashBankAccountDto[]>([]);
  readonly cashAccountsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── GL accounts (counter) ──────────────────────────────────────────────────
  readonly glAccounts = signal<AccountDto[]>([]);
  readonly glAccountsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly selectedAccountUid = signal('');
  readonly direction = signal<CashTxnDirection>('IN');
  readonly amount = signal('');
  readonly txnDate = signal('');
  readonly counterGlAccountUid = signal('');
  readonly memo = signal('');

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

  /** GL accounts that are not the cash/bank asset account — income/expense/equity for counter. */
  readonly counterGlOptions = computed(() =>
    this.glAccounts().filter((a) =>
      a.accountType === 'INCOME' || a.accountType === 'EXPENSE' || a.accountType === 'EQUITY',
    ),
  );

  constructor() {
    this.txnDate.set(new Date().toISOString().slice(0, 10));
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
    this.cashbankService.listAllAccounts(companyId).subscribe({
      next: (list) => {
        this.cashAccounts.set(list);
        this.cashAccountsState.set('idle');
      },
      error: () => this.cashAccountsState.set('error'),
    });
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

    const request: RecordDirectEntryRequest = {
      companyUid: company.uid,
      cashBankAccountUid: accountUid,
      direction: this.direction(),
      amount: amt,
      txnDate: date,
      counterGlAccountUid: counterUid,
      memo: memoVal || undefined,
    };

    this.submitting.set(true);
    this.formError.set(null);

    this.cashbankService.recordEntry(request).subscribe({
      next: (txn) => {
        this.submitting.set(false);
        this.alerts.success('Entry recorded', String(txn.txnNumber ?? ''));
        this.savedEntry.set(txn);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not record entry.'));
        this.submitting.set(false);
      },
    });
  }

  reset(): void {
    this.savedEntry.set(null);
    this.selectedAccountUid.set('');
    this.direction.set('IN');
    this.amount.set('');
    this.counterGlAccountUid.set('');
    this.memo.set('');
    this.txnDate.set(new Date().toISOString().slice(0, 10));
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
