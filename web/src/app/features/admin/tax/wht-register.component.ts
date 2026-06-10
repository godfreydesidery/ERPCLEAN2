import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { WhtRegisterDto } from './models/tax.model';
import { TaxService } from './tax.service';

/**
 * WHT Register screen (FR-WHT-04 / US-VAT-06).
 * Gated WHT.VIEW.
 *
 * Period selector: year+month OR explicit date range.
 * Two groups: WHT Payable to TRA (WHT_ON_PAYMENT) and WHT Receivable (WHT_ON_RECEIPT).
 * Rows: certificate number, party, source ref, taxable base, WHT amount, date.
 * Group totals at the bottom of each section.
 */
@Component({
  selector: 'app-wht-register',
  imports: [FormsModule],
  templateUrl: './wht-register.component.html',
  styleUrl: './wht-register.component.scss',
})
export class WhtRegisterComponent {
  private readonly taxService = inject(TaxService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly _alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Period selector ────────────────────────────────────────────────────────
  /** 'month' = year+month; 'range' = explicit dates */
  readonly periodMode = signal<'month' | 'range'>('month');
  readonly periodYear = signal(new Date().getFullYear());
  readonly periodMonth = signal(new Date().getMonth() + 1);
  readonly periodStart = signal('');
  readonly periodEnd = signal('');

  // ── Register ───────────────────────────────────────────────────────────────
  readonly register = signal<WhtRegisterDto | null>(null);
  readonly state = signal<'idle' | 'loading' | 'error' | 'forbidden'>('idle');

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('WHT.VIEW'));

  readonly months = [
    { value: 1, label: 'January' }, { value: 2, label: 'February' }, { value: 3, label: 'March' },
    { value: 4, label: 'April' }, { value: 5, label: 'May' }, { value: 6, label: 'June' },
    { value: 7, label: 'July' }, { value: 8, label: 'August' }, { value: 9, label: 'September' },
    { value: 10, label: 'October' }, { value: 11, label: 'November' }, { value: 12, label: 'December' },
  ];

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
    this.register.set(null);
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    this.state.set('loading');
    this.register.set(null);

    const obs = this.periodMode() === 'month'
      ? this.taxService.getWhtRegisterByMonth(companyId, this.periodYear(), this.periodMonth())
      : this.taxService.getWhtRegisterByRange(
          companyId,
          String(this.periodStart() ?? '').trim(),
          String(this.periodEnd() ?? '').trim(),
        );

    obs.subscribe({
      next: (reg) => {
        this.register.set(reg);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }
}
