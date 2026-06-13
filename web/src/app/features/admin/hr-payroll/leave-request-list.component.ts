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
import { HrPayrollService, LeaveRequestPage } from './hr-payroll.service';
import { LeaveRequestDto, LeaveRequestStatus, SubmitLeaveRequest } from './models/hr-payroll.model';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Leave request list with inline submit form.
 * Route: /admin/hr/leave-requests
 */
@Component({
  selector: 'app-leave-request-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './leave-request-list.component.html',
  styleUrl: './leave-request-list.component.scss',
})
export class LeaveRequestListComponent {
  private readonly hrService = inject(HrPayrollService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  readonly rows = signal<LeaveRequestDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Submit form (needs employee uid) ─────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly fEmployeeUid = signal('');
  readonly fLeaveTypeId = signal('');
  readonly fFromDate = signal('');
  readonly fToDate = signal('');
  readonly fDays = signal('');
  readonly fReason = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  readonly canManage = computed(() => this.session.hasPermission('HR.LEAVE.MANAGE'));
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
          return this.hrService.listLeaveRequests(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: LeaveRequestPage) => {
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
    this.fEmployeeUid.set('');
    this.fLeaveTypeId.set('');
    this.fFromDate.set('');
    this.fToDate.set('');
    this.fDays.set('');
    this.fReason.set('');
  }

  submit(): void {
    const employeeUid = this.fEmployeeUid().trim();
    const leaveTypeId = this.fLeaveTypeId().trim();
    const fromDate = this.fFromDate().trim();
    const toDate = this.fToDate().trim();
    const days = this.fDays().trim();

    if (!employeeUid) { this.formError.set('Employee UID is required.'); return; }
    if (!leaveTypeId) { this.formError.set('Leave type ID is required.'); return; }
    if (!fromDate) { this.formError.set('From date is required.'); return; }
    if (!toDate) { this.formError.set('To date is required.'); return; }
    if (!days || parseFloat(days) <= 0) { this.formError.set('Days must be a positive number.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: SubmitLeaveRequest = {
      leaveTypeId,
      fromDate,
      toDate,
      days,
      reason: this.fReason().trim() || undefined,
    };

    this.hrService.submitLeaveRequest(employeeUid, request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Leave request submitted', created.employeeName);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not submit leave request.'));
        this.saving.set(false);
      },
    });
  }

  statusBadgeClass(status: LeaveRequestStatus): string {
    switch (status) {
      case 'APPROVED': return 'text-bg-success';
      case 'REJECTED': return 'text-bg-danger';
      case 'CANCELLED': return 'text-bg-secondary';
      default: return 'text-bg-warning'; // PENDING
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
