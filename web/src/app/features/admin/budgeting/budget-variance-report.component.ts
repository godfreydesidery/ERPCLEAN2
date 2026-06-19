import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BudgetingService } from './budgeting.service';
import { AccountType, VarianceReportDto } from './models/budgeting.model';
import { GlService } from '../gl/gl.service';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Budget variance (budget-vs-actual) report screen.
 * Route: /admin/budgeting/variance
 */
@Component({
  selector: 'app-budget-variance-report',
  imports: [FormsModule, DecimalPipe, UidPickerComponent],
  templateUrl: './budget-variance-report.component.html',
  styleUrl: './budget-variance-report.component.scss',
})
export class BudgetVarianceReportComponent {
  private readonly budgetingService = inject(BudgetingService);
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ───────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Picker options ────────────────────────────────────────────────────────
  readonly fiscalYearOptions = signal<UidOption[]>([]);

  // ── Report params ─────────────────────────────────────────────────────────
  readonly fFiscalYearUid = signal('');
  readonly fFromPeriod = signal('1');
  readonly fToPeriod = signal('12');
  readonly fCostCentreUid = signal('');
  readonly fAccountType = signal<AccountType | ''>('');

  // ── Report state ──────────────────────────────────────────────────────────
  readonly report = signal<VarianceReportDto | null>(null);
  readonly reportState = signal<LoadState>('idle');

  readonly canView = computed(() => this.session.hasPermission('BUDGETING.REPORT.VIEW'));

  readonly accountTypeOptions: Array<{ value: AccountType | ''; label: string }> = [
    { value: '', label: 'All account types' },
    { value: 'ASSET', label: 'Asset' },
    { value: 'LIABILITY', label: 'Liability' },
    { value: 'EQUITY', label: 'Equity' },
    { value: 'INCOME', label: 'Income' },
    { value: 'EXPENSE', label: 'Expense' },
  ];

  readonly ACCOUNT_TYPES: AccountType[] = ['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'];

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
              this.loadFiscalYears(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadFiscalYears(companyId: string): void {
    this.glService.listFiscalYears(companyId).subscribe({
      next: (list) => this.fiscalYearOptions.set(
        list.map((fy) => ({ uid: fy.uid, label: fy.yearCode, hint: fy.status })),
      ),
      error: () => {},
    });
  }

  run(): void {
    const companyId = this.selectedCompanyId();
    const fiscalYearUid = this.fFiscalYearUid().trim();
    if (!companyId) { this.alerts.error('Validation', 'Select a company.'); return; }
    if (!fiscalYearUid) { this.alerts.error('Validation', 'Fiscal year is required.'); return; }

    const fromPeriod = parseInt(this.fFromPeriod(), 10) || 1;
    const toPeriod = parseInt(this.fToPeriod(), 10) || 12;
    if (fromPeriod < 1 || fromPeriod > 12 || toPeriod < 1 || toPeriod > 12 || fromPeriod > toPeriod) {
      this.alerts.error('Validation', 'Period range must be 1–12 and from ≤ to.');
      return;
    }

    this.reportState.set('loading');
    this.report.set(null);

    this.budgetingService.varianceReport(
      companyId,
      fiscalYearUid,
      fromPeriod,
      toPeriod,
      this.fCostCentreUid().trim() || undefined,
      this.fAccountType() || undefined,
    ).subscribe({
      next: (r) => {
        this.report.set(r);
        this.reportState.set('idle');
      },
      error: (err: unknown) =>
        this.reportState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────

  /**
   * Favourable/adverse label: for INCOME a positive variance (actual > budget) is favourable;
   * for EXPENSE a negative variance (actual < budget) is favourable.
   * ASSET/LIABILITY/EQUITY: show neutral label.
   */
  varianceLabel(accountType: string, varianceAmount: string): { text: string; cls: string } {
    const v = +varianceAmount;
    if (v === 0) return { text: 'On budget', cls: 'text-muted' };
    if (accountType === 'INCOME') {
      return v > 0 ? { text: 'Favourable', cls: 'text-success' } : { text: 'Adverse', cls: 'text-danger' };
    }
    if (accountType === 'EXPENSE') {
      return v < 0 ? { text: 'Favourable', cls: 'text-success' } : { text: 'Adverse', cls: 'text-danger' };
    }
    return { text: v > 0 ? '+' : '', cls: 'text-muted' };
  }

  totalsFor(map: Record<string, string> | undefined, type: AccountType): number {
    if (!map) return 0;
    return +(map[type] ?? '0');
  }
}
