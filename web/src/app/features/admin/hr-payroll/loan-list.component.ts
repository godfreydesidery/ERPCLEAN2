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
import { HrPayrollService, LoanPage } from './hr-payroll.service';
import { CreateLoanRequest, EmployeeLoanDto } from './models/hr-payroll.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { CurrencySelectComponent } from '../../../shared/currency-select/currency-select.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Employee loan list with inline create.
 * Route: /admin/hr/loans
 */
@Component({
  selector: 'app-loan-list',
  imports: [FormsModule, RouterLink, DecimalPipe, PaginatorComponent, UidPickerComponent, CurrencySelectComponent],
  templateUrl: './loan-list.component.html',
  styleUrl: './loan-list.component.scss',
})
export class LoanListComponent {
  private readonly hrService = inject(HrPayrollService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = computed(() => this.companies().find((c) => c.id === this.selectedCompanyId())?.uid ?? '');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  readonly rows = signal<EmployeeLoanDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Employee picker options ────────────────────────────────────────────────
  readonly employeeOptions = signal<UidOption[]>([]);
  /** Map from employee uid → employee id (numeric string), for request building. */
  private readonly employeeIdByUid = new Map<string, string>();

  // ── Create form (nested under employee uid) ──────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly fEmployeeUid = signal('');
  // fEmployeeId removed — derived from fEmployeeUid via employeeIdByUid map
  readonly fPrincipalAmount = signal('');
  readonly fInstallmentAmount = signal('');
  readonly fGlAccountId = signal('');
  readonly fStartDate = signal('');
  readonly fCurrency = signal('TZS');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  readonly canManage = computed(() => this.session.hasPermission('HR.LOAN.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

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
          return this.hrService.listLoans(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: LoanPage) => {
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
              this.loadEmployeeOptions(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadEmployeeOptions(companyId: string): void {
    this.hrService.listEmployees(companyId, 0, 500).subscribe({
      next: ({ rows }) => {
        this.employeeIdByUid.clear();
        rows.forEach((e) => this.employeeIdByUid.set(e.uid, e.id));
        this.employeeOptions.set(
          rows
            .filter((e) => e.status === 'ACTIVE')
            .map((e) => ({
              uid: e.uid,
              label: `${e.firstName} ${e.lastName}`,
              hint: e.employeeNumber,
            })),
        );
      },
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) {
      this.load(0);
      this.loadEmployeeOptions(id);
    }
  }

  load(page: number): void {
    if (!this.selectedCompanyId()) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.fEmployeeUid.set('');
    this.fPrincipalAmount.set('');
    this.fInstallmentAmount.set('');
    this.fGlAccountId.set('');
    this.fStartDate.set('');
    this.fCurrency.set('TZS');
  }

  /** Coerce a value from ngModel on type="number" to string; prevents .trim() crashes. */
  coerceNumStr(v: string | number | null | undefined): string {
    return v === null || v === undefined ? '' : String(v);
  }

  create(): void {
    const employeeUid = this.fEmployeeUid().trim();
    const employeeId = this.employeeIdByUid.get(employeeUid) ?? '';
    const principalAmount = this.fPrincipalAmount().trim();
    const installmentAmount = this.fInstallmentAmount().trim();
    const glAccountId = this.fGlAccountId().trim();
    const startDate = this.fStartDate().trim();

    if (!employeeUid) { this.formError.set('Employee is required.'); return; }
    if (!employeeId) { this.formError.set('Could not resolve employee ID — please re-select the employee.'); return; }
    if (!principalAmount || parseFloat(principalAmount) <= 0) { this.formError.set('Principal amount must be positive.'); return; }
    if (!installmentAmount || parseFloat(installmentAmount) <= 0) { this.formError.set('Installment amount must be positive.'); return; }
    if (!glAccountId) { this.formError.set('GL Account ID is required.'); return; }
    if (!startDate) { this.formError.set('Start date is required.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateLoanRequest = {
      employeeId,
      principalAmount,
      installmentAmount,
      glAccountId,
      startDate,
      currency: this.fCurrency().trim() || 'TZS',
    };

    this.hrService.createLoan(employeeUid, request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Loan created', created.loanNumber);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create loan.'));
        this.saving.set(false);
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
