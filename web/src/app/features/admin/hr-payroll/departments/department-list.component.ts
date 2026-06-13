import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { HrDepartmentService } from './hr-department.service';
import { CreateDepartmentRequest, DepartmentDto } from '../models/hr-payroll.model';

/**
 * HR Department list with inline create and edit.
 * Route: /admin/hr/departments
 * Permissions: HR.EMPLOYEE.VIEW (list), HR.EMPLOYEE.MANAGE (create/edit/deactivate)
 */
@Component({
  selector: 'app-department-list',
  imports: [FormsModule],
  templateUrl: './department-list.component.html',
  styleUrl: './department-list.component.scss',
})
export class DepartmentListComponent {
  private readonly deptService = inject(HrDepartmentService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ───────────────────────────────────────────────────────────
  readonly rows = signal<DepartmentDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── Create form ──────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly fCode = signal('');
  readonly fName = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Edit inline ──────────────────────────────────────────────────────────
  readonly editingUid = signal<string | null>(null);
  readonly editCode = signal('');
  readonly editName = signal('');
  readonly editSaving = signal(false);
  readonly editError = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('HR.EMPLOYEE.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

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
    this.deptService.list(companyId).subscribe({
      next: (rows) => { this.rows.set(rows); this.state.set('idle'); },
      error: (err: unknown) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) { this.fCode.set(''); this.fName.set(''); }
  }

  create(): void {
    const code = this.fCode().trim();
    const name = this.fName().trim();
    if (!code) { this.formError.set('Code is required.'); return; }
    if (!name) { this.formError.set('Name is required.'); return; }

    this.saving.set(true);
    this.formError.set(null);
    const req: CreateDepartmentRequest = { code, name };
    this.deptService.create(req).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.fCode.set(''); this.fName.set('');
        this.showCreateForm.set(false);
        this.alerts.success('Department created', created.name);
        this.load();
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create department.'));
        this.saving.set(false);
      },
    });
  }

  startEdit(dept: DepartmentDto): void {
    this.editingUid.set(dept.uid);
    this.editCode.set(dept.code);
    this.editName.set(dept.name);
    this.editError.set(null);
  }

  cancelEdit(): void {
    this.editingUid.set(null);
    this.editError.set(null);
  }

  saveEdit(): void {
    const uid = this.editingUid();
    if (!uid) return;
    const code = this.editCode().trim();
    const name = this.editName().trim();
    if (!code) { this.editError.set('Code is required.'); return; }
    if (!name) { this.editError.set('Name is required.'); return; }

    this.editSaving.set(true);
    this.editError.set(null);
    this.deptService.update(uid, { code, name }).subscribe({
      next: (updated) => {
        this.editSaving.set(false);
        this.editingUid.set(null);
        this.alerts.success('Department updated', updated.name);
        this.load();
      },
      error: (err: unknown) => {
        this.editError.set(this.messageFrom(err, 'Could not update department.'));
        this.editSaving.set(false);
      },
    });
  }

  deactivate(dept: DepartmentDto): void {
    this.deptService.deactivate(dept.uid).subscribe({
      next: () => {
        this.alerts.success('Department deactivated', dept.name);
        this.load();
      },
      error: (err: unknown) =>
        this.alerts.error('Deactivate failed', this.messageFrom(err, 'Could not deactivate.')),
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
