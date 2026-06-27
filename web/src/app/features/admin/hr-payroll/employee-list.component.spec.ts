/**
 * EmployeeListComponent — spec.
 *
 * Covers: company loading, create-form happy-path, department/branch dropdown
 * contract (numeric id sent; blank omitted; non-numeric value impossible via dropdown).
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { HrPayrollService } from './hr-payroll.service';
import { HrDepartmentService } from './departments/hr-department.service';
import { EmployeeListComponent } from './employee-list.component';
import type { DepartmentDto, EmployeeDto } from './models/hr-payroll.model';

vi.useFakeTimers();

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeEmployee(overrides: Partial<EmployeeDto> = {}): EmployeeDto {
  return {
    id: '1', uid: 'emp-1', companyId: '10', branchId: '20',
    employeeNumber: 'EMP-001', firstName: 'Bob', lastName: 'Jones',
    nationalId: '', tin: '', nssfNumber: '', heslbNumber: '',
    dateOfBirth: '', gender: 'MALE', hireDate: '2023-01-01',
    departmentId: '5', departmentName: 'IT', jobTitle: 'Dev',
    status: 'ACTIVE', userId: '',
    ...overrides,
  };
}

function makeDept(overrides: Partial<DepartmentDto> = {}): DepartmentDto {
  return { id: '5', uid: 'dept-uid-1', companyId: '10', code: 'IT', name: 'IT', active: true, ...overrides };
}

function makeBed(
  hrOverrides: Partial<{
    listEmployees: ReturnType<typeof vi.fn>;
    createEmployee: ReturnType<typeof vi.fn>;
  }> = {},
  canManage = true,
) {
  const hrSvc = {
    listEmployees: vi.fn(() => of({ rows: [makeEmployee()], meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false } })),
    createEmployee: vi.fn(() => of(makeEmployee())),
    ...hrOverrides,
  };

  TestBed.configureTestingModule({
    imports: [EmployeeListComponent],
    providers: [
      provideRouter([]),
      { provide: HrPayrollService, useValue: hrSvc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((p: string) => canManage && p === 'HR.EMPLOYEE.MANAGE'),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal(canManage ? ['HR.EMPLOYEE.MANAGE'] : []),
          activeBranchUid: signal(null),
        },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'org-uid-1', name: 'Test Org' })) },
      },
      {
        provide: CompanyService,
        useValue: {
          list: vi.fn(() => of([{ id: '10', uid: 'co-uid-1', organisationId: '1', code: 'CO1', name: 'Test Co', legalName: null, taxId: null, timeZone: 'UTC', status: 'ACTIVE' }])),
        },
      },
      {
        provide: HrDepartmentService,
        useValue: { list: vi.fn(() => of([makeDept()])) },
      },
      {
        provide: BranchService,
        useValue: {
          list: vi.fn(() => of([{ id: '20', uid: 'br-uid-1', companyId: '10', companyUid: 'co-uid-1', code: 'HQ', name: 'HQ Branch', timeZone: 'UTC', isDefault: true, status: 'ACTIVE' }])),
        },
      },
    ],
  });
  return hrSvc;
}

// ── Initial load ──────────────────────────────────────────────────────────────

describe('EmployeeListComponent — initial load', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('loads companies and employees on init', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(EmployeeListComponent);
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.companies().length).toBe(1);
    expect(svc.listEmployees).toHaveBeenCalled();
    expect(fixture.componentInstance.rows().length).toBe(1);
  });

  it('sets companyState = "error" when OrganisationService fails', async () => {
    TestBed.configureTestingModule({
      imports: [EmployeeListComponent],
      providers: [
        provideRouter([]),
        { provide: HrPayrollService, useValue: { listEmployees: vi.fn(() => of({ rows: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false } })), createEmployee: vi.fn() } },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: SessionStore, useValue: { hasPermission: vi.fn(() => true), isAuthenticated: signal(true), user: signal(null), permissions: signal([]), activeBranchUid: signal(null) } },
        { provide: OrganisationService, useValue: { current: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 }))) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([])) } },
        { provide: HrDepartmentService, useValue: { list: vi.fn(() => of([])) } },
        { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      ],
    });
    const fixture = TestBed.createComponent(EmployeeListComponent);
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.companyState()).toBe('error');
  });
});

// ── Department / branch dropdown contract ─────────────────────────────────────

describe('EmployeeListComponent — department/branch dropdown contract', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('sends the selected department numeric id in the create payload', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(EmployeeListComponent);
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fFirstName.set('Jane');
    comp.fLastName.set('Doe');
    comp.fHireDate.set('2024-01-01');
    comp.fDepartmentId.set('5');   // simulates selecting from the dropdown
    comp.fBranchId.set('');
    comp.create();
    vi.runAllTimers();
    await fixture.whenStable();

    const payload = svc.createEmployee.mock.calls[0][0] as Record<string, unknown>;
    expect(payload['departmentId']).toBe('5');
  });

  it('sends the selected branch numeric id in the create payload', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(EmployeeListComponent);
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fFirstName.set('Jane');
    comp.fLastName.set('Doe');
    comp.fHireDate.set('2024-01-01');
    comp.fDepartmentId.set('');
    comp.fBranchId.set('20');      // simulates selecting from the dropdown
    comp.create();
    vi.runAllTimers();
    await fixture.whenStable();

    const payload = svc.createEmployee.mock.calls[0][0] as Record<string, unknown>;
    expect(payload['branchId']).toBe('20');
  });

  it('omits departmentId and branchId when blank option is selected', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(EmployeeListComponent);
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fFirstName.set('Jane');
    comp.fLastName.set('Doe');
    comp.fHireDate.set('2024-01-01');
    comp.fDepartmentId.set('');
    comp.fBranchId.set('');
    comp.create();
    vi.runAllTimers();
    await fixture.whenStable();

    const payload = svc.createEmployee.mock.calls[0][0] as Record<string, unknown>;
    expect(payload['departmentId']).toBeUndefined();
    expect(payload['branchId']).toBeUndefined();
  });

  it('populates departments signal from HrDepartmentService after company loads', async () => {
    makeBed();
    const fixture = TestBed.createComponent(EmployeeListComponent);
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.departments().length).toBeGreaterThan(0);
    expect(fixture.componentInstance.departments()[0].id).toBe('5');
  });

  it('populates branches signal from BranchService after company loads', async () => {
    makeBed();
    const fixture = TestBed.createComponent(EmployeeListComponent);
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.branches().length).toBeGreaterThan(0);
    expect(fixture.componentInstance.branches()[0].id).toBe('20');
  });
});

// ── Create form validation ────────────────────────────────────────────────────

describe('EmployeeListComponent — create form validation', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('blocks submission when first name is blank', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(EmployeeListComponent);
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fLastName.set('Doe');
    comp.fHireDate.set('2024-01-01');
    comp.create();

    expect(svc.createEmployee).not.toHaveBeenCalled();
    expect(comp.formError()).toBe('First name is required.');
  });

  it('blocks submission when last name is blank', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(EmployeeListComponent);
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fFirstName.set('Jane');
    comp.fHireDate.set('2024-01-01');
    comp.create();

    expect(svc.createEmployee).not.toHaveBeenCalled();
    expect(comp.formError()).toBe('Last name is required.');
  });

  it('blocks submission when hire date is blank', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(EmployeeListComponent);
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fFirstName.set('Jane');
    comp.fLastName.set('Doe');
    comp.create();

    expect(svc.createEmployee).not.toHaveBeenCalled();
    expect(comp.formError()).toBe('Hire date is required.');
  });
});
