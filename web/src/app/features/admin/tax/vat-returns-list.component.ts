import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { VatReturnDto } from './models/tax.model';
import { TaxService } from './tax.service';

/**
 * VAT Returns list screen (FR-VAT-01 / US-VAT-01).
 * - Table of returns for the selected company.
 * - "New VAT Return" opens an inline form (year + month) — gated VAT.RETURN.PREPARE.
 * - Status badges: DRAFT (warning) / FILED (success).
 * - Clicking a return row navigates to the detail screen.
 */
@Component({
  selector: 'app-vat-returns-list',
  imports: [FormsModule],
  templateUrl: './vat-returns-list.component.html',
  styleUrl: './vat-returns-list.component.scss',
})
export class VatReturnsListComponent {
  private readonly taxService = inject(TaxService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List ───────────────────────────────────────────────────────────────────
  readonly rows = signal<VatReturnDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── New return form ────────────────────────────────────────────────────────
  readonly showNewForm = signal(false);
  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);
  readonly newYear = signal(new Date().getFullYear());
  readonly newMonth = signal(new Date().getMonth() + 1);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('VAT.VIEW'));
  readonly canPrepare = computed(() => this.session.hasPermission('VAT.RETURN.PREPARE'));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

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
    this.taxService.listReturns(companyId, 0, 100).subscribe({
      next: ({ rows }) => {
        this.rows.set(rows);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── New return form ────────────────────────────────────────────────────────

  openNewForm(): void {
    this.showNewForm.set(true);
    this.createError.set(null);
    this.newYear.set(new Date().getFullYear());
    this.newMonth.set(new Date().getMonth() + 1);
  }

  cancelNew(): void {
    this.showNewForm.set(false);
    this.createError.set(null);
  }

  submitNew(): void {
    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.createError.set('Could not resolve company.'); return; }

    const year = this.newYear();
    const month = this.newMonth();
    if (!year || year < 2000 || year > 2100) { this.createError.set('Enter a valid year (2000–2100).'); return; }
    if (!month || month < 1 || month > 12) { this.createError.set('Select a valid month.'); return; }

    this.creating.set(true);
    this.createError.set(null);

    this.taxService.openReturn({ companyUid: company.uid, periodYear: year, periodMonth: month }).subscribe({
      next: (ret) => {
        this.creating.set(false);
        this.showNewForm.set(false);
        this.alerts.success('VAT Return opened', ret.returnNumber);
        this.router.navigate(['/admin/tax/vat-returns/uid', ret.uid]);
      },
      error: (err) => {
        this.createError.set(this.messageFrom(err, 'Could not open VAT Return.'));
        this.creating.set(false);
      },
    });
  }

  openDetail(ret: VatReturnDto): void {
    this.router.navigate(['/admin/tax/vat-returns/uid', ret.uid]);
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  periodLabel(ret: VatReturnDto): string {
    return `${ret.periodYear}-${String(ret.periodMonth).padStart(2, '0')}`;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
