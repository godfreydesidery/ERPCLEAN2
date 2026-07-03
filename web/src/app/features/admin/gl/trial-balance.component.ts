import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  AccountType,
  FiscalPeriodDto,
  TrialBalanceDto,
  TrialBalanceRowDto,
} from './models/gl.model';
import { GlService } from './gl.service';
import { formatMoney } from '../../../shared/money.util';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

const ACCOUNT_TYPE_ORDER: AccountType[] = ['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'];

/**
 * Trial balance screen. Gated GL.VIEW.
 * Company + optional period selector.
 * Rows grouped/sorted by accountType; footer shows totalDebits / totalCredits + "Balanced" indicator.
 */
@Component({
  selector: 'app-trial-balance',
  imports: [FormsModule],
  templateUrl: './trial-balance.component.html',
  styleUrl: './trial-balance.component.scss',
})
export class TrialBalanceComponent {
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Period selector ────────────────────────────────────────────────────────
  readonly periods = signal<FiscalPeriodDto[]>([]);
  readonly selectedPeriodUid = signal('');
  readonly periodsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Trial balance data ─────────────────────────────────────────────────────
  readonly tb = signal<TrialBalanceDto | null>(null);
  readonly state = signal<LoadState>('idle');

  readonly canView = computed(() => this.session.hasPermission('GL.VIEW'));

  /** Rows sorted by canonical account type order. */
  readonly sortedRows = computed<TrialBalanceRowDto[]>(() => {
    const rows = this.tb()?.rows ?? [];
    return [...rows].sort((a, b) => {
      const ai = ACCOUNT_TYPE_ORDER.indexOf(a.accountType);
      const bi = ACCOUNT_TYPE_ORDER.indexOf(b.accountType);
      if (ai !== bi) return ai - bi;
      return a.accountCode.localeCompare(b.accountCode);
    });
  });

  readonly groupedByType = computed<Map<AccountType, TrialBalanceRowDto[]>>(() => {
    const map = new Map<AccountType, TrialBalanceRowDto[]>();
    for (const row of this.sortedRows()) {
      const group = map.get(row.accountType) ?? [];
      group.push(row);
      map.set(row.accountType, group);
    }
    return map;
  });

  readonly groupedTypes = computed<AccountType[]>(() =>
    ACCOUNT_TYPE_ORDER.filter((t) => (this.groupedByType().get(t)?.length ?? 0) > 0),
  );

  readonly totalDebits = computed(() => this.tb()?.totalDebits ?? '0.00');
  readonly totalCredits = computed(() => this.tb()?.totalCredits ?? '0.00');

  readonly isBalanced = computed(() => {
    const dr = Number.parseFloat(this.totalDebits() || '0');
    const cr = Number.parseFloat(this.totalCredits() || '0');
    return Math.abs(dr - cr) < 0.000001;
  });

  readonly isEmpty = computed(() => this.state() === 'idle' && this.tb() === null);

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
              this.loadPeriods(list[0].id);
              this.loadTrialBalance();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadPeriods(companyId: string): void {
    this.periodsState.set('loading');
    this.glService.listPeriods(companyId).subscribe({
      next: (list) => {
        this.periods.set(list);
        this.periodsState.set('idle');
      },
      error: () => this.periodsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedPeriodUid.set('');
    this.tb.set(null);
    this.periods.set([]);
    if (id) {
      this.loadPeriods(id);
      this.loadTrialBalance();
    }
  }

  onPeriodChange(periodUid: string): void {
    this.selectedPeriodUid.set(periodUid);
    this.loadTrialBalance();
  }

  loadTrialBalance(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');

    const periodUid = this.selectedPeriodUid();
    const call$ = periodUid
      ? this.glService.getTrialBalanceForPeriod(companyId, periodUid)
      : this.glService.getTrialBalance(companyId);

    call$.subscribe({
      next: (data) => {
        this.tb.set(data);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  rowsForType(type: AccountType): TrialBalanceRowDto[] {
    return this.groupedByType().get(type) ?? [];
  }

  /** Coerce + format money with thousand separators (shared util). */
  readonly fmtMoney = formatMoney;
}
