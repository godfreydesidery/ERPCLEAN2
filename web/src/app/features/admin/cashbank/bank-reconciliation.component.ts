import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  BankReconciliationDto,
  CashBankAccountDto,
  CashTransactionDto,
  MarkClearedRequest,
  OpenReconciliationRequest,
} from './models/cashbank.model';
import { CashbankService } from './cashbank.service';

/**
 * Bank Reconciliation screen. Gated CASH.RECONCILE.
 *
 * Flow:
 *  1. Pick company + bank account.
 *  2. Open a reconciliation (statement date + closing balance).
 *  3. Account's transactions load — each has a cleared checkbox.
 *  4. Running cleared-book-balance vs statement-closing-balance shows the difference.
 *  5. Complete button is enabled ONLY when clearedBookBalance == statementClosingBalance (within 0.000001).
 *
 * Money arrives as number on wire — coerce with +v throughout.
 * NEVER call .startsWith/.trim on a money value.
 */
@Component({
  selector: 'app-bank-reconciliation',
  imports: [FormsModule],
  templateUrl: './bank-reconciliation.component.html',
  styleUrl: './bank-reconciliation.component.scss',
})
export class BankReconciliationComponent {
  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Bank accounts ──────────────────────────────────────────────────────────
  readonly bankAccounts = signal<CashBankAccountDto[]>([]);
  readonly selectedAccountUid = signal('');

  // ── Open-reconciliation form ───────────────────────────────────────────────
  readonly showOpenForm = signal(false);
  readonly statementDate = signal('');
  readonly statementClosingBalanceInput = signal('');
  readonly opening = signal(false);
  readonly openError = signal<string | null>(null);

  // ── Active reconciliation ──────────────────────────────────────────────────
  readonly reconciliation = signal<BankReconciliationDto | null>(null);

  // ── Account transactions (loaded when recon is open) ─────────────────────
  readonly transactions = signal<CashTransactionDto[]>([]);
  readonly txnState = signal<'idle' | 'loading' | 'error'>('idle');

  /** Set of transaction uids the user has checked as cleared in this session. */
  readonly clearedUids = signal<Set<string>>(new Set());

  // ── Submit / complete state ────────────────────────────────────────────────
  readonly completing = signal(false);
  readonly completeError = signal<string | null>(null);
  readonly markingCleared = signal(false);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canReconcile = computed(() => this.session.hasPermission('CASH.RECONCILE'));

  // ── Computed balances ──────────────────────────────────────────────────────

  readonly statementClosingBalance = computed(() => {
    const recon = this.reconciliation();
    return recon ? +(recon.statementClosingBalance ?? 0) : 0;
  });

  /**
   * Running cleared book balance:
   * Start from zero and add/subtract only the cleared transactions.
   * IN = credit to cash (positive), OUT = debit from cash (negative).
   */
  readonly clearedBookBalance = computed(() => {
    const cleared = this.clearedUids();
    return this.transactions()
      .filter((t) => cleared.has(t.uid))
      .reduce((sum, t) => {
        const amt = +(t.amount ?? 0);
        return sum + (t.direction === 'IN' ? amt : -amt);
      }, 0);
  });

  readonly difference = computed(() =>
    this.clearedBookBalance() - this.statementClosingBalance(),
  );

  /**
   * Balanced: difference is within float rounding tolerance.
   * Complete is only enabled when balanced AND a reconciliation is open.
   */
  readonly isBalanced = computed(() =>
    Math.abs(this.difference()) < 0.000001,
  );

  readonly completeDisabled = computed(() =>
    !this.reconciliation() ||
    this.reconciliation()!.status === 'COMPLETED' ||
    !this.isBalanced() ||
    this.completing(),
  );

  constructor() {
    this.statementDate.set(new Date().toISOString().slice(0, 10));
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
              this.loadBankAccounts(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadBankAccounts(companyId: string): void {
    this.cashbankService.listAllAccounts(companyId).subscribe({
      next: (list) => this.bankAccounts.set(list.filter((a) => a.accountType === 'BANK')),
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedAccountUid.set('');
    this.reconciliation.set(null);
    this.transactions.set([]);
    this.clearedUids.set(new Set());
    if (id) this.loadBankAccounts(id);
  }

  onAccountChange(uid: string): void {
    this.selectedAccountUid.set(uid);
    this.reconciliation.set(null);
    this.transactions.set([]);
    this.clearedUids.set(new Set());
    this.showOpenForm.set(false);
    // Load statement transactions for the selected account
    if (uid) this.loadTransactions(uid);
  }

  private loadTransactions(accountUid: string): void {
    this.txnState.set('loading');
    this.cashbankService.getAccountStatement(accountUid).subscribe({
      next: (stmt) => {
        this.transactions.set(stmt.transactions ?? []);
        // Pre-seed cleared flags from the transaction data
        const alreadyCleared = new Set(
          stmt.transactions.filter((t) => t.cleared).map((t) => t.uid),
        );
        this.clearedUids.set(alreadyCleared);
        this.txnState.set('idle');
      },
      error: () => this.txnState.set('error'),
    });
  }

  // ── Open reconciliation ────────────────────────────────────────────────────

  openNewRecon(): void {
    this.showOpenForm.set(true);
    this.openError.set(null);
    this.statementDate.set(new Date().toISOString().slice(0, 10));
    this.statementClosingBalanceInput.set('');
  }

  cancelOpenRecon(): void {
    this.showOpenForm.set(false);
    this.openError.set(null);
  }

  submitOpenRecon(): void {
    const accountUid = String(this.selectedAccountUid() ?? '').trim();
    const date = String(this.statementDate() ?? '').trim();
    const balance = String(this.statementClosingBalanceInput() ?? '').trim();

    if (!accountUid) { this.openError.set('Select a bank account first.'); return; }
    if (!date) { this.openError.set('Statement date is required.'); return; }
    if (!balance || isNaN(+balance)) { this.openError.set('Enter a valid statement closing balance.'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.openError.set('Could not resolve company.'); return; }

    const request: OpenReconciliationRequest = {
      companyUid: company.uid,
      cashBankAccountUid: accountUid,
      statementDate: date,
      statementClosingBalance: balance,
    };

    this.opening.set(true);
    this.openError.set(null);

    this.cashbankService.openReconciliation(request).subscribe({
      next: (recon) => {
        this.opening.set(false);
        this.showOpenForm.set(false);
        this.reconciliation.set(recon);
        this.alerts.success('Reconciliation opened', recon.reconciliationNumber);
        // Reset cleared state — user will tick from scratch
        this.clearedUids.set(new Set());
        this.loadTransactions(accountUid);
      },
      error: (err) => {
        this.openError.set(this.messageFrom(err, 'Could not open reconciliation.'));
        this.opening.set(false);
      },
    });
  }

  // ── Mark cleared ───────────────────────────────────────────────────────────

  toggleCleared(txnUid: string, checked: boolean): void {
    this.clearedUids.update((set) => {
      const next = new Set(set);
      if (checked) next.add(txnUid); else next.delete(txnUid);
      return next;
    });

    const recon = this.reconciliation();
    if (!recon) return; // No open recon — just track locally

    const request: MarkClearedRequest = {
      transactionUids: [txnUid],
      cleared: checked,
    };

    this.markingCleared.set(true);
    this.cashbankService.markCleared(recon.uid, request).subscribe({
      next: () => this.markingCleared.set(false),
      error: () => {
        // Rollback optimistic update
        this.clearedUids.update((set) => {
          const next = new Set(set);
          if (checked) next.delete(txnUid); else next.add(txnUid);
          return next;
        });
        this.markingCleared.set(false);
      },
    });
  }

  isCleared(uid: string): boolean {
    return this.clearedUids().has(uid);
  }

  // ── Complete reconciliation ────────────────────────────────────────────────

  completeReconciliation(): void {
    const recon = this.reconciliation();
    if (!recon || this.completeDisabled()) return;

    this.completing.set(true);
    this.completeError.set(null);

    this.cashbankService.completeReconciliation(recon.uid).subscribe({
      next: (completed) => {
        this.completing.set(false);
        this.reconciliation.set(completed);
        this.alerts.success('Reconciliation completed', completed.reconciliationNumber);
      },
      error: (err) => {
        this.completeError.set(this.messageFrom(err, 'Could not complete reconciliation.'));
        this.completing.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  directionBadgeClass(direction: string): string {
    return direction === 'IN' ? 'text-bg-success' : 'text-bg-danger';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
