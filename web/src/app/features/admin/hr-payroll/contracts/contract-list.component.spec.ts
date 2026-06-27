/**
 * ContractListComponent — unit spec.
 * Covers: load once employee picked, isEmpty, 403→forbidden, validation guard, create success.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi } from 'vitest';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { HrPayrollService } from '../hr-payroll.service';
import { HrContractService } from './hr-contract.service';
import { ContractListComponent } from './contract-list.component';
import type { ContractDto, EmployeeDto } from '../models/hr-payroll.model';

vi.useFakeTimers();

function makeEmployee(overrides: Partial<EmployeeDto> = {}): EmployeeDto {
  return {
    id: '1', uid: 'emp-uid-1', companyId: '10', branchId: 'br-1',
    employeeNumber: 'EMP-001', firstName: 'Alice', lastName: 'Smith',
    nationalId: 'NID001', tin: '', nssfNumber: '', heslbNumber: '',
    dateOfBirth: '1990-01-01', gender: 'FEMALE', hireDate: '2022-01-01',
    departmentId: '', departmentName: '', jobTitle: '', status: 'ACTIVE', userId: '',
    ...overrides,
  };
}

function makeContract(overrides: Partial<ContractDto> = {}): ContractDto {
  return {
    id: '1', uid: 'contract-uid-1', companyId: '10', employeeId: '1',
    contractType: 'PERMANENT', baseSalaryAmount: '500000', currency: 'TZS',
    payFrequency: 'MONTHLY', startDate: '2022-01-01', endDate: null,
    payeResident: true, nssfMember: true, heslbBorrower: false,
    wcfCovered: false, sdlCounted: false, active: true,
    ...overrides,
  };
}

function makeBed(
  contractSvc: Partial<{ listByEmployee: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn> }> = {},
  canManage = true,
) {
  const cSvc = {
    listByEmployee: vi.fn(() => of([makeContract()])),
    create: vi.fn(() => of(makeContract())),
    terminate: vi.fn(() => of(undefined)),
    ...contractSvc,
  };

  const hrSvc = {
    listEmployees: vi.fn(() => of({ rows: [makeEmployee()], meta: { page: 0, size: 200, totalElements: 1, totalPages: 1, hasNext: false } })),
  };
  const orgSvc = { current: vi.fn(() => of({ uid: 'org-1' })) };
  const companySvc = {
    list: vi.fn(() => of([{ id: '10', uid: 'co-uid', name: 'Test Co', organisationUid: 'org-1' }])),
  };
  const session = {
    hasPermission: vi.fn(() => canManage),
    isAuthenticated: vi.fn(() => true),
  };

  TestBed.configureTestingModule({
    imports: [ContractListComponent],
    providers: [
      provideRouter([]),
      { provide: HrContractService, useValue: cSvc },
      { provide: HrPayrollService, useValue: hrSvc },
      { provide: OrganisationService, useValue: orgSvc },
      { provide: CompanyService, useValue: companySvc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: session },
    ],
  });

  const fixture = TestBed.createComponent(ContractListComponent);
  const comp = fixture.componentInstance;
  return { fixture, comp, cSvc, hrSvc };
}

describe('ContractListComponent', () => {
  it('loads employees on init', () => {
    const { hrSvc } = makeBed();
    expect(hrSvc.listEmployees).toHaveBeenCalledOnce();
  });

  it('loads contracts when employee is picked', () => {
    const { comp, cSvc } = makeBed();
    comp.onEmployeePick('emp-uid-1');
    expect(cSvc.listByEmployee).toHaveBeenCalledOnce();
  });

  it('populates rows after load', () => {
    const { comp } = makeBed();
    comp.onEmployeePick('emp-uid-1');
    expect(comp.rows().length).toBe(1);
    expect(comp.rows()[0].contractType).toBe('PERMANENT');
  });

  it('isEmpty is true when no contracts', () => {
    const { comp } = makeBed({ listByEmployee: vi.fn(() => of([])) });
    comp.onEmployeePick('emp-uid-1');
    expect(comp.isEmpty()).toBe(true);
  });

  it('sets state=forbidden on 403', () => {
    const err = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    const { comp } = makeBed({ listByEmployee: vi.fn(() => throwError(() => err)) });
    comp.onEmployeePick('emp-uid-1');
    expect(comp.state()).toBe('forbidden');
  });

  it('validates employee required before create', () => {
    const { comp, cSvc } = makeBed();
    // No employee picked → create should fail
    comp.selectedEmployeeUid.set('');
    comp.fBaseSalary.set('500000');
    comp.fStartDate.set('2024-01-01');
    comp.create();
    expect(cSvc.create).not.toHaveBeenCalled();
    expect(comp.formError()).toBeTruthy();
  });

  it('validates salary required before create', () => {
    const { comp, cSvc } = makeBed();
    comp.selectedEmployeeUid.set('emp-uid-1');
    comp.fBaseSalary.set('');
    comp.fStartDate.set('2024-01-01');
    comp.create();
    expect(cSvc.create).not.toHaveBeenCalled();
  });

  it('calls create and reloads on success', () => {
    const { comp, cSvc } = makeBed();
    comp.onEmployeePick('emp-uid-1');
    comp.fContractType.set('PERMANENT');
    comp.fBaseSalary.set('500000');
    comp.fStartDate.set('2024-01-01');
    comp.create();
    expect(cSvc.create).toHaveBeenCalledWith('emp-uid-1', expect.objectContaining({
      contractType: 'PERMANENT',
      baseSalaryAmount: '500000',
      startDate: '2024-01-01',
    }));
  });

  it('create() does not throw and posts a string when fBaseSalary receives a number (simulates type="number" input)', () => {
    const { comp, cSvc } = makeBed();
    comp.onEmployeePick('emp-uid-1');
    comp.fContractType.set('PERMANENT');
    // Simulate ngModel on type="number" emitting a JS number — coerceNumStr is what
    // (ngModelChange) calls in the template.
    comp.fBaseSalary.set(comp.coerceNumStr(750000));
    comp.fStartDate.set('2024-01-01');
    expect(() => comp.create()).not.toThrow();
    expect(cSvc.create).toHaveBeenCalledOnce();
    const req = cSvc.create.mock.calls[0][1] as { baseSalaryAmount: string };
    expect(req.baseSalaryAmount).toBe('750000');
    expect(typeof req.baseSalaryAmount).toBe('string');
  });
});
