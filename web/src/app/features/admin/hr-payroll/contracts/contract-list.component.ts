import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { HrPayrollService } from '../hr-payroll.service';
import { HrContractService } from './hr-contract.service';
import {
  ContractDto,
  ContractType,
  CreateContractRequest,
  EmployeeDto,
} from '../models/hr-payroll.model';
import { UidPickerComponent, UidOption } from '../../../../shared/uid-picker/uid-picker.component';

/**
 * Employee Contracts screen.
 * The operator picks an employee via <app-uid-picker>; the panel then lists that
 * employee's contracts and offers a create form.
 * Route: /admin/hr/contracts
 * Permissions: HR.EMPLOYEE.VIEW (list), HR.EMPLOYEE.MANAGE (create/terminate)
 */
@Component({
  selector: 'app-contract-list',
  imports: [FormsModule, DecimalPipe, UidPickerComponent],
  templateUrl: './contract-list.component.html',
  styleUrl: './contract-list.component.scss',
})
export class ContractListComponent {
  private readonly contractService = inject(HrContractService);
  private readonly hrService = inject(HrPayrollService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Employee picker ──────────────────────────────────────────────────────
  readonly employees = signal<EmployeeDto[]>([]);
  readonly employeeOptions = computed<UidOption[]>(() =>
    this.employees().map((e) => ({
      uid: e.uid,
      label: `${e.firstName} ${e.lastName}`,
      hint: e.employeeNumber,
    })),
  );
  readonly selectedEmployeeUid = signal('');
  /** Resolved employee id (body FK) from the picked uid */
  readonly selectedEmployeeId = computed<string>(() => {
    const uid = this.selectedEmployeeUid();
    return this.employees().find((e) => e.uid === uid)?.id ?? '';
  });
  readonly employeesState = signal<'loading' | 'idle' | 'error'>('idle');

  // ── Contracts list state ─────────────────────────────────────────────────
  readonly rows = signal<ContractDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── Create form ──────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly fContractType = signal<ContractType>('PERMANENT');
  readonly fBaseSalary = signal('');
  readonly fStartDate = signal('');
  readonly fEndDate = signal('');
  readonly fPayeResident = signal(true);
  readonly fNssfMember = signal(true);
  readonly fHeslbBorrower = signal(false);
  readonly fWcfCovered = signal(false);
  readonly fSdlCounted = signal(false);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('HR.EMPLOYEE.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly contractTypes: ContractType[] = ['PERMANENT', 'FIXED_TERM', 'CASUAL', 'PROBATION'];

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
              this.loadEmployees();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadEmployees(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.employeesState.set('loading');
    this.hrService.listEmployees(companyId, 0, 200).subscribe({
      next: ({ rows }) => { this.employees.set(rows); this.employeesState.set('idle'); },
      error: () => this.employeesState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedEmployeeUid.set('');
    this.rows.set([]);
    if (id) this.loadEmployees();
  }

  onEmployeePick(uid: string): void {
    this.selectedEmployeeUid.set(uid);
    if (uid) this.loadContracts();
    else this.rows.set([]);
  }

  loadContracts(): void {
    const companyId = this.selectedCompanyId();
    const employeeId = this.selectedEmployeeId();
    if (!companyId || !employeeId) return;
    this.state.set('loading');
    this.contractService.listByEmployee(companyId, employeeId).subscribe({
      next: (rows) => { this.rows.set(rows); this.state.set('idle'); },
      error: (err: unknown) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetForm();
  }

  private resetForm(): void {
    this.fContractType.set('PERMANENT');
    this.fBaseSalary.set('');
    this.fStartDate.set('');
    this.fEndDate.set('');
    this.fPayeResident.set(true);
    this.fNssfMember.set(true);
    this.fHeslbBorrower.set(false);
    this.fWcfCovered.set(false);
    this.fSdlCounted.set(false);
  }

  create(): void {
    const employeeUid = this.selectedEmployeeUid();
    const baseSalary = this.fBaseSalary().trim();
    const startDate = this.fStartDate().trim();

    if (!employeeUid) { this.formError.set('Select an employee first.'); return; }
    if (!baseSalary) { this.formError.set('Base salary is required.'); return; }
    if (!startDate) { this.formError.set('Start date is required.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const req: CreateContractRequest = {
      contractType: this.fContractType(),
      baseSalaryAmount: baseSalary,
      startDate,
      endDate: this.fEndDate().trim() || undefined,
      payeResident: this.fPayeResident(),
      nssfMember: this.fNssfMember(),
      heslbBorrower: this.fHeslbBorrower(),
      wcfCovered: this.fWcfCovered(),
      sdlCounted: this.fSdlCounted(),
    };

    this.contractService.create(employeeUid, req).subscribe({
      next: () => {
        this.saving.set(false);
        this.resetForm();
        this.showCreateForm.set(false);
        this.alerts.success('Contract created');
        this.loadContracts();
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create contract.'));
        this.saving.set(false);
      },
    });
  }

  terminate(contract: ContractDto): void {
    this.contractService.terminate(contract.uid).subscribe({
      next: () => {
        this.alerts.success('Contract terminated');
        this.loadContracts();
      },
      error: (err: unknown) =>
        this.alerts.error('Terminate failed', this.messageFrom(err, 'Could not terminate contract.')),
    });
  }

  contractTypeBadgeClass(ct: ContractType): string {
    switch (ct) {
      case 'PERMANENT': return 'text-bg-success';
      case 'FIXED_TERM': return 'text-bg-info';
      case 'CASUAL': return 'text-bg-warning';
      case 'PROBATION': return 'text-bg-secondary';
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
