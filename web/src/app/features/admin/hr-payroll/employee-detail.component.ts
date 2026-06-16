import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { HrPayrollService } from './hr-payroll.service';
import { CreateEmployeeRequest, EmployeeDto, EmploymentStatus } from './models/hr-payroll.model';

/**
 * Employee detail + edit screen.
 * Route: /admin/hr/employees/uid/:uid
 */
@Component({
  selector: 'app-employee-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './employee-detail.component.html',
  styleUrl: './employee-detail.component.scss',
})
export class EmployeeDetailComponent {
  private readonly hrService = inject(HrPayrollService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly employee = signal<EmployeeDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Edit form ────────────────────────────────────────────────────────────────
  readonly fFirstName = signal('');
  readonly fLastName = signal('');
  readonly fHireDate = signal('');
  readonly fJobTitle = signal('');
  readonly fGender = signal('');
  readonly fNationalId = signal('');
  readonly fTin = signal('');
  readonly fNssfNumber = signal('');
  readonly fHeslbNumber = signal('');
  readonly fDateOfBirth = signal('');
  readonly fDepartmentId = signal('');
  readonly fBranchId = signal('');
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly archiving = signal(false);

  readonly canManage = computed(() => this.session.hasPermission('HR.EMPLOYEE.MANAGE'));

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadEmployee();
  }

  private loadEmployee(): void {
    this.state.set('loading');
    this.hrService.getEmployeeByUid(this.uid()).subscribe({
      next: (e) => {
        this.employee.set(e);
        this.state.set('idle');
        this.patchForm(e);
      },
      error: () => this.state.set('error'),
    });
  }

  private patchForm(e: EmployeeDto): void {
    this.fFirstName.set(e.firstName ?? '');
    this.fLastName.set(e.lastName ?? '');
    this.fHireDate.set(e.hireDate ?? '');
    this.fJobTitle.set(e.jobTitle ?? '');
    this.fGender.set(e.gender ?? '');
    this.fNationalId.set(e.nationalId ?? '');
    this.fTin.set(e.tin ?? '');
    this.fNssfNumber.set(e.nssfNumber ?? '');
    this.fHeslbNumber.set(e.heslbNumber ?? '');
    this.fDateOfBirth.set(e.dateOfBirth ?? '');
    this.fDepartmentId.set(e.departmentId ?? '');
    this.fBranchId.set(e.branchId ?? '');
  }

  save(): void {
    const firstName = this.fFirstName().trim();
    const lastName = this.fLastName().trim();
    const hireDate = this.fHireDate().trim();

    if (!firstName) { this.saveError.set('First name is required.'); return; }
    if (!lastName) { this.saveError.set('Last name is required.'); return; }
    if (!hireDate) { this.saveError.set('Hire date is required.'); return; }

    this.saving.set(true);
    this.saveError.set(null);

    const request: CreateEmployeeRequest = {
      firstName,
      lastName,
      hireDate,
      jobTitle: this.fJobTitle().trim() || undefined,
      gender: this.fGender().trim() || undefined,
      nationalId: this.fNationalId().trim() || undefined,
      tin: this.fTin().trim() || undefined,
      nssfNumber: this.fNssfNumber().trim() || undefined,
      heslbNumber: this.fHeslbNumber().trim() || undefined,
      dateOfBirth: this.fDateOfBirth().trim() || undefined,
      departmentId: this.fDepartmentId().trim() || undefined,
      branchId: this.fBranchId().trim() || undefined,
    };

    this.hrService.updateEmployee(this.uid(), request).subscribe({
      next: (updated) => {
        this.employee.set(updated);
        this.saving.set(false);
        this.alerts.success('Employee saved', `${updated.firstName} ${updated.lastName}`);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save employee.'));
        this.saving.set(false);
      },
    });
  }

  archive(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.saveError.set(null);
    this.hrService.archiveEmployee(this.uid()).subscribe({
      next: () => {
        this.archiving.set(false);
        this.employee.update((e) => (e ? { ...e, status: 'TERMINATED' as EmploymentStatus } : e));
        this.alerts.success('Employee archived');
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not archive employee.'));
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
