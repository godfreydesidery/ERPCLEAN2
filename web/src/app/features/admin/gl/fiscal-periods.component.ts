import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  FiscalPeriodDto,
  FiscalYearDto,
  OpenFiscalYearRequest,
  PeriodStatus,
} from './models/gl.model';
import { GlService } from './gl.service';

type LoadState = 'idle' | 'loading' | 'error';

/**
 * Fiscal periods screen. Gated GL.VIEW (list), GL.PERIOD.CLOSE (close/reopen), GL.MANAGE (open year).
 * Lists periods with status badges + close/reopen actions.
 * Small "Open fiscal year" form (yearCode, startMonth, calendarYear) gated GL.MANAGE.
 */
@Component({
  selector: 'app-fiscal-periods',
  imports: [FormsModule],
  templateUrl: './fiscal-periods.component.html',
  styleUrl: './fiscal-periods.component.scss',
})
export class FiscalPeriodsComponent {
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Periods ────────────────────────────────────────────────────────────────
  readonly periods = signal<FiscalPeriodDto[]>([]);
  readonly periodsState = signal<LoadState>('idle');
  readonly rowBusyUid = signal<string | null>(null);

  // ── Fiscal years ──────────────────────────────────────────────────────────
  readonly fiscalYears = signal<FiscalYearDto[]>([]);
  readonly fiscalYearsState = signal<LoadState>('idle');

  // ── Open year form ─────────────────────────────────────────────────────────
  readonly showOpenYearForm = signal(false);
  readonly newYearCode = signal('');
  readonly newStartMonth = signal('1');
  readonly newCalendarYear = signal(String(new Date().getFullYear()));
  readonly openingYear = signal(false);
  readonly openYearError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('GL.VIEW'));
  readonly canClose = computed(() => this.session.hasPermission('GL.PERIOD.CLOSE'));
  readonly canManage = computed(() => this.session.hasPermission('GL.MANAGE'));

  readonly months = [
    { value: '1', label: 'January' }, { value: '2', label: 'February' },
    { value: '3', label: 'March' }, { value: '4', label: 'April' },
    { value: '5', label: 'May' }, { value: '6', label: 'June' },
    { value: '7', label: 'July' }, { value: '8', label: 'August' },
    { value: '9', label: 'September' }, { value: '10', label: 'October' },
    { value: '11', label: 'November' }, { value: '12', label: 'December' },
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
              this.loadPeriods(list[0].id);
              this.loadFiscalYears(list[0].id);
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

  private loadFiscalYears(companyId: string): void {
    this.fiscalYearsState.set('loading');
    this.glService.listFiscalYears(companyId).subscribe({
      next: (list) => {
        this.fiscalYears.set(list);
        this.fiscalYearsState.set('idle');
      },
      error: () => this.fiscalYearsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.periods.set([]);
    this.fiscalYears.set([]);
    if (id) {
      this.loadPeriods(id);
      this.loadFiscalYears(id);
    }
  }

  // ── Period actions ─────────────────────────────────────────────────────────

  closePeriod(period: FiscalPeriodDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(period.uid);
    this.glService.closePeriod(period.uid).subscribe({
      next: (updated) => {
        this.rowBusyUid.set(null);
        this.alerts.success(`Period ${updated.periodNo} closed`);
        this.loadPeriods(this.selectedCompanyId());
      },
      error: (err) => {
        this.rowBusyUid.set(null);
        this.alerts.error('Could not close period', this.messageFrom(err, ''));
      },
    });
  }

  reopenPeriod(period: FiscalPeriodDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(period.uid);
    this.glService.reopenPeriod(period.uid).subscribe({
      next: (updated) => {
        this.rowBusyUid.set(null);
        this.alerts.success(`Period ${updated.periodNo} reopened`);
        this.loadPeriods(this.selectedCompanyId());
      },
      error: (err) => {
        this.rowBusyUid.set(null);
        this.alerts.error('Could not reopen period', this.messageFrom(err, ''));
      },
    });
  }

  // ── Open fiscal year ───────────────────────────────────────────────────────

  toggleOpenYearForm(): void {
    this.showOpenYearForm.update((v) => !v);
    this.openYearError.set(null);
  }

  openFiscalYear(): void {
    const companyId = this.selectedCompanyId();
    const yearCode = String(this.newYearCode() ?? '').trim();
    const startMonth = parseInt(String(this.newStartMonth() ?? '1'), 10);
    const calendarYear = parseInt(String(this.newCalendarYear() ?? ''), 10);

    if (!companyId || !yearCode) {
      this.openYearError.set('Year code is required.');
      return;
    }
    if (!calendarYear || calendarYear < 2000 || calendarYear > 2100) {
      this.openYearError.set('Enter a valid 4-digit calendar year (2000–2100).');
      return;
    }

    const company = this.companies().find((c) => c.id === companyId);
    if (!company) {
      this.openYearError.set('Could not resolve selected company.');
      return;
    }

    this.openingYear.set(true);
    this.openYearError.set(null);

    const request: OpenFiscalYearRequest = {
      companyUid: company.uid,
      yearCode,
      startMonth,
      calendarYear,
    };

    this.glService.openFiscalYear(request).subscribe({
      next: (fy) => {
        this.openingYear.set(false);
        this.showOpenYearForm.set(false);
        this.alerts.success('Fiscal year opened', fy.yearCode);
        this.newYearCode.set('');
        this.loadFiscalYears(companyId);
        this.loadPeriods(companyId);
      },
      error: (err) => {
        this.openYearError.set(this.messageFrom(err, 'Could not open fiscal year.'));
        this.openingYear.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  statusBadgeClass(status: PeriodStatus): string {
    return status === 'OPEN' ? 'text-bg-success' : 'text-bg-secondary';
  }

  yearStatusBadgeClass(status: string): string {
    return status === 'OPEN' ? 'text-bg-success' : 'text-bg-secondary';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
