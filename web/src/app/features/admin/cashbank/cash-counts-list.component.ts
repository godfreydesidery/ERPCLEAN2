import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, switchMap } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CashBankAccountDto, CashCountDto } from './models/cashbank.model';
import { CashbankService } from './cashbank.service';
import { formatMoney } from '../../../shared/money.util';

interface LoadTrigger {
  companyId: string;
  accountId: string;
}

/**
 * Cash-count history for a till (ADR-0050 D-7 PR-A).
 *
 * The backend's `listByAccount` is TILL-SCOPED — unlike the other cashbank lists it requires
 * BOTH `companyId` AND `accountId` (no company-wide listing, no pagination). The user must pick
 * a company then a CASH till before any rows load.
 *
 * Gated CASH.COUNT.VIEW; "New Count" action gated CASH.COUNT.MANAGE.
 */
@Component({
  selector: 'app-cash-counts-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './cash-counts-list.component.html',
  styleUrl: './cash-counts-list.component.scss',
})
export class CashCountsListComponent {
  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Till picker (CASH-type accounts only) ─────────────────────────────────
  readonly tills = signal<CashBankAccountDto[]>([]);
  readonly selectedTillId = signal('');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<CashCountDto[]>([]);
  readonly state = signal<'idle' | 'loading' | 'error' | 'forbidden'>('idle');

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('CASH.COUNT.VIEW'));
  readonly canManage = computed(() => this.session.hasPermission('CASH.COUNT.MANAGE'));
  readonly isEmpty = computed(() =>
    this.state() === 'idle' && !!this.selectedTillId() && this.rows().length === 0,
  );

  private readonly loadTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    this.loadTrigger$
      .pipe(
        switchMap(({ companyId, accountId }) => {
          if (!companyId || !accountId) return [];
          this.state.set('loading');
          return this.cashbankService.listCashCounts(companyId, accountId);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (rows) => {
          this.rows.set(rows);
          this.state.set('idle');
        },
        error: (err) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
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
              this.loadTills(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  /** Tills = CASH-type cash_bank_accounts only (a BANK account is never counted). */
  private loadTills(companyId: string): void {
    this.cashbankService.listAllAccounts(companyId).subscribe({
      next: (list) => {
        const cashTills = list.filter((a) => a.accountType === 'CASH');
        this.tills.set(cashTills);
        if (cashTills.length > 0) {
          this.selectedTillId.set(cashTills[0].id);
          this.load();
        } else {
          this.selectedTillId.set('');
          this.rows.set([]);
        }
      },
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedTillId.set('');
    this.rows.set([]);
    if (id) this.loadTills(id);
  }

  onTillChange(id: string): void {
    this.selectedTillId.set(id);
    if (id) this.load();
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    const accountId = this.selectedTillId();
    if (!companyId || !accountId) return;
    this.loadTrigger$.next({ companyId, accountId });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  readonly fmtMoney = formatMoney;
}
