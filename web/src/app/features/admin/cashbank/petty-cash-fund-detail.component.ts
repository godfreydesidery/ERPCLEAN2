import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { User } from '../models/user.model';
import { UserService } from '../user/user.service';
import { AccountDto } from '../gl/models/gl.model';
import { GlService } from '../gl/gl.service';
import {
  CreatePettyCashFundRequest,
  PettyCashFundDto,
  PettyCashTransactionDto,
  PettyCashTxnType,
  RecordPettyCashTransactionRequest,
} from './models/cashbank.model';
import { CashbankService } from './cashbank.service';
import { formatMoney } from '../../../shared/money.util';

/**
 * Petty Cash fund screen (ADR-0050 D-7 PR-B). Dual mode, one component (mirrors the PR-A
 * CashCountComponent shape):
 *  - `/admin/petty-cash/funds/new`      — no `uid` input: pick company, code/name/custodian/float
 *    /currency, Create.
 *  - `/admin/petty-cash/funds/uid/:uid` — `uid` bound via withComponentInputBinding: load the
 *    fund, show its balance, record a transaction, and view its ledger.
 *
 * Record-only this slice (D-7.1): recording a transaction never touches GL; the fund's
 * `balanceAmount` moves and the ledger is authoritative until the GL fast-follow.
 *
 * Money coerced as numbers — NEVER call .startsWith/.trim on a money value.
 * Gated PETTY_CASH.MANAGE (create fund / record transaction); viewing is PETTY_CASH.VIEW (route guard).
 */
@Component({
  selector: 'app-petty-cash-fund-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './petty-cash-fund-detail.component.html',
  styleUrl: './petty-cash-fund-detail.component.scss',
})
export class PettyCashFundDetailComponent {
  /** Route input — bound from `:uid` via withComponentInputBinding. Absent on the `new` route. */
  readonly uid = input<string | undefined>();

  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly userService = inject(UserService);
  private readonly glService = inject(GlService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  // ── Company context (new-fund mode only) ──────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('idle');

  // ── Custodian picker (new-fund mode only; optional field) ─────────────────
  readonly users = signal<User[]>([]);

  // ── New-fund form fields ───────────────────────────────────────────────────
  readonly newCode = signal('');
  readonly newName = signal('');
  readonly newCustodianUid = signal('');
  readonly newFloatAmount = signal('');
  readonly newCurrency = signal('TZS');

  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);

  // ── Load / entity state ────────────────────────────────────────────────────
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly fund = signal<PettyCashFundDto | null>(null);

  // ── Ledger ─────────────────────────────────────────────────────────────────
  readonly txnState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly transactions = signal<PettyCashTransactionDto[]>([]);

  // ── GL accounts (optional expense picker on a disbursement) ────────────────
  readonly glAccounts = signal<AccountDto[]>([]);

  // ── Record-transaction form ─────────────────────────────────────────────────
  readonly txnType = signal<PettyCashTxnType>('DISBURSEMENT');
  readonly txnAmount = signal('');
  readonly txnDate = signal('');
  readonly txnGlAccountUid = signal('');
  readonly txnReference = signal('');
  readonly txnDescription = signal('');

  readonly recording = signal(false);
  readonly recordError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('PETTY_CASH.MANAGE'));

  // ── Computed ───────────────────────────────────────────────────────────────

  readonly isNewMode = computed(() => !this.uid());

  readonly newFloatAmountNum = computed(() => {
    const v = +(String(this.newFloatAmount() ?? '').trim() || '0');
    return Number.isFinite(v) ? v : 0;
  });

  readonly createDisabled = computed(() =>
    !String(this.newCode() ?? '').trim() ||
    !String(this.newName() ?? '').trim() ||
    !String(this.newCurrency() ?? '').trim() ||
    this.newFloatAmountNum() < 0 ||
    !this.selectedCompanyId() ||
    this.creating(),
  );

  /** Expense-type GL accounts, for the optional disbursement expense-account picker. */
  readonly expenseGlOptions = computed(() =>
    this.glAccounts().filter((a) => a.accountType === 'EXPENSE'),
  );

  readonly txnAmountNum = computed(() => {
    const v = +(String(this.txnAmount() ?? '').trim() || '0');
    return Number.isFinite(v) ? v : 0;
  });

  readonly recordDisabled = computed(() => {
    const amt = this.txnAmountNum();
    // ADJUSTMENT is a SIGNED delta (may be negative to decrease the balance) — only zero is invalid.
    // DISBURSEMENT/REPLENISHMENT are positive magnitudes (direction implied by the type).
    const amountInvalid = this.txnType() === 'ADJUSTMENT' ? amt === 0 : amt <= 0;
    return (
      !String(this.txnAmount() ?? '').trim() ||
      amountInvalid ||
      !String(this.txnDate() ?? '').trim() ||
      this.recording() ||
      !this.canManage()
    );
  });

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    const uid = this.uid();
    if (uid) {
      this.loadExisting(uid);
    } else {
      this.loadCompaniesForNew();
      this.loadUsers();
    }
    this.txnDate.set(new Date().toISOString().slice(0, 10));
  }

  private loadExisting(uid: string): void {
    this.state.set('loading');
    this.cashbankService.getPettyCashFund(uid).subscribe({
      next: (dto) => {
        this.fund.set(dto);
        this.state.set('idle');
        this.loadTransactions(dto.uid);
        this.loadGlAccounts(dto.companyId);
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  private loadTransactions(fundUid: string): void {
    this.txnState.set('loading');
    this.cashbankService.listPettyCashTransactions(fundUid).subscribe({
      next: (rows) => {
        this.transactions.set(rows);
        this.txnState.set('idle');
      },
      error: () => this.txnState.set('error'),
    });
  }

  private loadGlAccounts(companyId: string): void {
    this.glService.listAllActiveAccounts(companyId).subscribe({
      next: (list) => this.glAccounts.set(list),
      error: () => {},
    });
  }

  private loadCompaniesForNew(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) this.selectedCompanyId.set(list[0].id);
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  /** Users are not scoped to company — usable across the whole org as custodian candidates. */
  private loadUsers(): void {
    this.userService.list().subscribe({
      next: (rows) => this.users.set(rows),
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
  }

  // ── Create a new fund ───────────────────────────────────────────────────────

  createFund(): void {
    if (this.createDisabled()) return;

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.createError.set('Could not resolve company.'); return; }

    const code = String(this.newCode() ?? '').trim();
    const name = String(this.newName() ?? '').trim();
    const currency = String(this.newCurrency() ?? '').trim().toUpperCase();
    const custodianUid = String(this.newCustodianUid() ?? '').trim();

    if (!code) { this.createError.set('Fund code is required.'); return; }
    if (!name) { this.createError.set('Fund name is required.'); return; }
    if (!currency) { this.createError.set('Currency is required.'); return; }

    const request: CreatePettyCashFundRequest = {
      companyUid: company.uid,
      code,
      name,
      floatAmount: this.newFloatAmountNum(),
      currency,
    };
    if (custodianUid) request.custodianUid = custodianUid;

    this.creating.set(true);
    this.createError.set(null);

    this.cashbankService.createPettyCashFund(request).subscribe({
      next: (dto) => {
        this.creating.set(false);
        this.alerts.success('Petty cash fund created', dto.code);
        void this.router.navigate(['/admin/petty-cash/funds/uid', dto.uid], { replaceUrl: true });
      },
      error: (err) => {
        this.createError.set(this.messageFrom(err, 'Could not create the petty cash fund.'));
        this.creating.set(false);
      },
    });
  }

  // ── Record a transaction ─────────────────────────────────────────────────────

  recordTransaction(): void {
    const dto = this.fund();
    if (!dto || this.recordDisabled()) return;

    const glAccountUid = String(this.txnGlAccountUid() ?? '').trim();
    const reference = String(this.txnReference() ?? '').trim();
    const description = String(this.txnDescription() ?? '').trim();

    const request: RecordPettyCashTransactionRequest = {
      type: this.txnType(),
      amount: this.txnAmountNum(),
      txnDate: String(this.txnDate() ?? '').trim(),
    };
    if (glAccountUid) request.glAccountUid = glAccountUid;
    if (reference) request.reference = reference;
    if (description) request.description = description;

    this.recording.set(true);
    this.recordError.set(null);

    this.cashbankService.recordPettyCashTransaction(dto.uid, request).subscribe({
      next: (txn) => {
        this.recording.set(false);
        this.transactions.update((list) => [txn, ...list]);
        this.fund.update((f) => (f ? { ...f, balanceAmount: txn.balanceAfter } : f));
        this.alerts.success('Transaction recorded', txn.txnNumber);
        this.resetTxnForm();
      },
      error: (err) => {
        this.recordError.set(this.messageFrom(err, 'Could not record the transaction.'));
        this.recording.set(false);
      },
    });
  }

  private resetTxnForm(): void {
    this.txnType.set('DISBURSEMENT');
    this.txnAmount.set('');
    this.txnGlAccountUid.set('');
    this.txnReference.set('');
    this.txnDescription.set('');
    this.txnDate.set(new Date().toISOString().slice(0, 10));
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  readonly fmtMoney = formatMoney;

  /**
   * Signed balance effect of a ledger row (what it did to the fund). DISBURSEMENT decreases,
   * REPLENISHMENT increases, ADJUSTMENT carries its own sign (amount is signed for adjustments) —
   * so the Amount column reads as a real +/- delta and a downward adjustment is unambiguous.
   */
  txnEffect(t: PettyCashTransactionDto): number {
    const amt = +t.amount;
    if (t.txnType === 'DISBURSEMENT') return -Math.abs(amt);
    if (t.txnType === 'REPLENISHMENT') return Math.abs(amt);
    return amt;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
