import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  CashAccountStatementDto,
  CashBankAccountDto,
  CashGlReconciliationDto,
  CashTransactionDto,
  CashTxnDirection,
} from './models/cashbank.model';
import { CashbankService } from './cashbank.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Cash Account Statement screen. Gated CASH.VIEW.
 * Pick account → current balance + running-balance transaction list
 * + GL reconciliation (book vs linked-GL) indicator.
 *
 * Money coerced as numbers — NEVER call .startsWith/.trim on a money value.
 */
@Component({
  selector: 'app-cash-account-statement',
  imports: [FormsModule],
  templateUrl: './cash-account-statement.component.html',
  styleUrl: './cash-account-statement.component.scss',
})
export class CashAccountStatementComponent {
  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Account picker ─────────────────────────────────────────────────────────
  readonly accounts = signal<CashBankAccountDto[]>([]);
  readonly selectedAccountUid = signal('');

  // ── Statement data ─────────────────────────────────────────────────────────
  readonly statement = signal<CashAccountStatementDto | null>(null);
  readonly state = signal<LoadState>('idle');

  // ── GL reconciliation ──────────────────────────────────────────────────────
  readonly glRecon = signal<CashGlReconciliationDto | null>(null);
  readonly glReconState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('CASH.VIEW'));

  // ── Computed ───────────────────────────────────────────────────────────────

  readonly currentBalance = computed(() => +(this.statement()?.currentBalance ?? 0));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.statement() === null);

  /**
   * Running balance: starts from zero, accumulates IN/OUT chronologically.
   * Returns the same transactions array with a runningBalance per entry.
   */
  readonly txnsWithRunning = computed<Array<CashTransactionDto & { runningBalance: number }>>(() => {
    let running = 0;
    return (this.statement()?.transactions ?? []).map((t) => {
      const amt = +(t.amount ?? 0);
      running += t.direction === 'IN' ? amt : -amt;
      return { ...t, runningBalance: running };
    });
  });

  readonly glDifference = computed(() => +(this.glRecon()?.difference ?? 0));

  readonly glReconciled = computed(() =>
    this.glRecon() !== null && Math.abs(this.glDifference()) < 0.000001,
  );

  constructor() {
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
              this.loadAccounts(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadAccounts(companyId: string): void {
    this.cashbankService.listAllAccounts(companyId).subscribe({
      next: (list) => this.accounts.set(list),
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedAccountUid.set('');
    this.statement.set(null);
    this.glRecon.set(null);
    this.state.set('idle');
    if (id) this.loadAccounts(id);
  }

  onAccountChange(uid: string): void {
    this.selectedAccountUid.set(uid);
    this.statement.set(null);
    this.glRecon.set(null);
    if (uid) this.loadStatement(uid);
  }

  private loadStatement(accountUid: string): void {
    this.state.set('loading');
    this.cashbankService.getAccountStatement(accountUid).subscribe({
      next: (data) => {
        this.statement.set(data);
        this.state.set('idle');
        this.loadGlRecon(accountUid);
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  private loadGlRecon(accountUid: string): void {
    this.glReconState.set('loading');
    this.cashbankService.getGlReconciliation(accountUid).subscribe({
      next: (data) => {
        this.glRecon.set(data);
        this.glReconState.set('idle');
      },
      error: () => this.glReconState.set('error'),
    });
  }

  refresh(): void {
    const uid = this.selectedAccountUid();
    if (uid) this.loadStatement(uid);
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Coerce money — number or string on wire — to display string. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  directionBadgeClass(direction: CashTxnDirection): string {
    return direction === 'IN' ? 'text-bg-success' : 'text-bg-danger';
  }
}
