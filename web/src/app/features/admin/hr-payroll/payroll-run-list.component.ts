import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { HrPayrollService, PayrollRunPage } from './hr-payroll.service';
import { CreatePayrollRunRequest, PayrollRunDto, PayrollRunStatus } from './models/hr-payroll.model';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Payroll run list with inline create.
 * Route: /admin/hr/payroll-runs
 * Note: companyId for create comes from auth principal — we send only period + payDate + branchId.
 */
@Component({
  selector: 'app-payroll-run-list',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './payroll-run-list.component.html',
  styleUrl: './payroll-run-list.component.scss',
})
export class PayrollRunListComponent {
  private readonly hrService = inject(HrPayrollService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  readonly rows = signal<PayrollRunDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Create form ──────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly fPeriodYear = signal('');
  readonly fPeriodMonth = signal('');
  readonly fPayDate = signal('');
  readonly fBranchId = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  readonly canRun = computed(() => this.session.hasPermission('HR.PAYROLL.RUN'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly MONTHS = [
    { value: '1', label: 'January' }, { value: '2', label: 'February' },
    { value: '3', label: 'March' }, { value: '4', label: 'April' },
    { value: '5', label: 'May' }, { value: '6', label: 'June' },
    { value: '7', label: 'July' }, { value: '8', label: 'August' },
    { value: '9', label: 'September' }, { value: '10', label: 'October' },
    { value: '11', label: 'November' }, { value: '12', label: 'December' },
  ];

  constructor() {
    const companyChangeTrigger$ = toObservable(this.selectedCompanyId).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(companyChangeTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.hrService.listPayrollRuns(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: PayrollRunPage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
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
              this.load(0);
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
    if (id) this.load(0);
  }

  load(page: number): void {
    if (!this.selectedCompanyId()) return;
    this.immediateTrigger$.next({ page });
  }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.fPeriodYear.set('');
    this.fPeriodMonth.set('');
    this.fPayDate.set('');
    this.fBranchId.set('');
  }

  create(): void {
    const periodYear = parseInt(this.fPeriodYear().trim(), 10);
    const periodMonth = parseInt(this.fPeriodMonth().trim(), 10);
    const payDate = this.fPayDate().trim();

    if (!periodYear || isNaN(periodYear)) { this.formError.set('Period year is required.'); return; }
    if (!periodMonth || isNaN(periodMonth) || periodMonth < 1 || periodMonth > 12) {
      this.formError.set('Period month (1–12) is required.'); return;
    }
    if (!payDate) { this.formError.set('Pay date is required.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreatePayrollRunRequest = {
      periodYear,
      periodMonth,
      payDate,
      branchId: this.fBranchId().trim() || undefined,
    };

    this.hrService.createPayrollRun(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Payroll run created', created.runNumber);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create payroll run.'));
        this.saving.set(false);
      },
    });
  }

  statusBadgeClass(status: PayrollRunStatus): string {
    switch (status) {
      case 'CALCULATED': return 'text-bg-info';
      case 'APPROVED': return 'text-bg-primary';
      case 'POSTED': return 'text-bg-warning';
      case 'PAID': return 'text-bg-success';
      case 'REVERSED': return 'text-bg-danger';
      default: return 'text-bg-secondary'; // DRAFT
    }
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
