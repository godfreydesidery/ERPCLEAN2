import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { PettyCashFundDto } from './models/cashbank.model';
import { CashbankService } from './cashbank.service';
import { formatMoney } from '../../../shared/money.util';

/**
 * Petty Cash funds list (ADR-0050 D-7 PR-B). Company-scoped, plain unwrapped array — no
 * pagination (mirrors listCashCounts' style, not the paginated cashbank lists).
 *
 * Gated PETTY_CASH.VIEW; "New Fund" action gated PETTY_CASH.MANAGE.
 */
@Component({
  selector: 'app-petty-cash-funds-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './petty-cash-funds-list.component.html',
  styleUrl: './petty-cash-funds-list.component.scss',
})
export class PettyCashFundsListComponent {
  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<PettyCashFundDto[]>([]);
  readonly state = signal<'idle' | 'loading' | 'error' | 'forbidden'>('idle');

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('PETTY_CASH.VIEW'));
  readonly canManage = computed(() => this.session.hasPermission('PETTY_CASH.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

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
              this.load();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) this.load();
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.cashbankService.listPettyCashFunds(companyId).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  readonly fmtMoney = formatMoney;
}
