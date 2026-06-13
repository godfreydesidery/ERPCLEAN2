import { HttpErrorResponse } from '@angular/common/http';
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
import { HrPayrollService, EmployeePage } from './hr-payroll.service';
import { CreateEmployeeRequest, EmployeeDto, EmploymentStatus } from './models/hr-payroll.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Employee list screen with inline create.
 * Route: /admin/hr/employees
 */
@Component({
  selector: 'app-employee-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './employee-list.component.html',
  styleUrl: './employee-list.component.scss',
})
export class EmployeeListComponent {
  private readonly hrService = inject(HrPayrollService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<EmployeeDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Create form ──────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly fFirstName = signal('');
  readonly fLastName = signal('');
  readonly fHireDate = signal('');
  readonly fJobTitle = signal('');
  readonly fGender = signal('');
  readonly fNationalId = signal('');
  readonly fDepartmentId = signal('');
  readonly fBranchId = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  readonly canManage = computed(() => this.session.hasPermission('HR.EMPLOYEE.MANAGE'));
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
          return this.hrService.listEmployees(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: EmployeePage) => {
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
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
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
    this.fFirstName.set('');
    this.fLastName.set('');
    this.fHireDate.set('');
    this.fJobTitle.set('');
    this.fGender.set('');
    this.fNationalId.set('');
    this.fDepartmentId.set('');
    this.fBranchId.set('');
  }

  create(): void {
    const firstName = this.fFirstName().trim();
    const lastName = this.fLastName().trim();
    const hireDate = this.fHireDate().trim();

    if (!firstName) { this.formError.set('First name is required.'); return; }
    if (!lastName) { this.formError.set('Last name is required.'); return; }
    if (!hireDate) { this.formError.set('Hire date is required.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateEmployeeRequest = {
      firstName,
      lastName,
      hireDate,
      jobTitle: this.fJobTitle().trim() || undefined,
      gender: this.fGender().trim() || undefined,
      nationalId: this.fNationalId().trim() || undefined,
      departmentId: this.fDepartmentId().trim() || undefined,
      branchId: this.fBranchId().trim() || undefined,
    };

    this.hrService.createEmployee(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Employee created', `${created.firstName} ${created.lastName}`);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create employee.'));
        this.saving.set(false);
      },
    });
  }

  statusBadgeClass(status: EmploymentStatus): string {
    switch (status) {
      case 'ACTIVE': return 'text-bg-success';
      case 'ON_LEAVE': return 'text-bg-info';
      case 'SUSPENDED': return 'text-bg-warning';
      case 'TERMINATED': return 'text-bg-danger';
      default: return 'text-bg-secondary';
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
