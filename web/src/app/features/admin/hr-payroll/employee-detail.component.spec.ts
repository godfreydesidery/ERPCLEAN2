/**
 * EmployeeDetailComponent — deep spec.
 *
 * Covers the load/error triad, form validation (required fields),
 * and the archive lifecycle action.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { HrPayrollService } from './hr-payroll.service';
import { EmployeeDetailComponent } from './employee-detail.component';
import type { EmployeeDto } from './models/hr-payroll.model';

vi.useFakeTimers();

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeEmployee(overrides: Partial<EmployeeDto> = {}): EmployeeDto {
  return {
    id: '1', uid: 'emp-1', companyId: '10', branchId: 'br-1',
    employeeNumber: 'EMP-001', firstName: 'Alice', lastName: 'Smith',
    nationalId: 'NID001', tin: 'TIN001', nssfNumber: 'NSSF001',
    heslbNumber: '', dateOfBirth: '1990-01-15', gender: 'FEMALE',
    hireDate: '2022-03-01', departmentId: 'dept-1', departmentName: 'Finance',
    jobTitle: 'Accountant', status: 'ACTIVE', userId: '',
    ...overrides,
  };
}

function makeBed(
  hrService: Partial<{
    getEmployeeByUid: ReturnType<typeof vi.fn>;
    updateEmployee: ReturnType<typeof vi.fn>;
    archiveEmployee: ReturnType<typeof vi.fn>;
  }> = {},
  canManage = true,
) {
  const svc = {
    getEmployeeByUid: vi.fn(() => of(makeEmployee())),
    updateEmployee: vi.fn(() => of(makeEmployee())),
    archiveEmployee: vi.fn(() => of(undefined)),
    ...hrService,
  };

  TestBed.configureTestingModule({
    imports: [EmployeeDetailComponent],
    providers: [
      provideRouter([]),
      { provide: HrPayrollService, useValue: svc },
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
    ],
  });
  return svc;
}

// ── Load triad ────────────────────────────────────────────────────────────────

describe('EmployeeDetailComponent — load triad', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('loads the employee and patches form on success', async () => {
    makeBed();
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.state()).toBe('idle');
    expect(comp.employee()?.uid).toBe('emp-1');
    expect(comp.fFirstName()).toBe('Alice');
    expect(comp.fLastName()).toBe('Smith');
    expect(comp.fHireDate()).toBe('2022-03-01');
  });

  it('sets state = "error" on 500', async () => {
    makeBed({ getEmployeeByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 }))) });
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.state()).toBe('error');
  });

  it('sets state = "error" on 403 (detail maps all errors to error state)', async () => {
    makeBed({ getEmployeeByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))) });
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.state()).toBe('error');
  });
});

// ── Form validation ───────────────────────────────────────────────────────────

describe('EmployeeDetailComponent — form validation', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('sets saveError when first name is blank', async () => {
    makeBed();
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fFirstName.set('');
    comp.save();

    expect(comp.saveError()).toBe('First name is required.');
  });

  it('sets saveError when last name is blank', async () => {
    makeBed();
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fLastName.set('');
    comp.save();

    expect(comp.saveError()).toBe('Last name is required.');
  });

  it('sets saveError when hire date is blank', async () => {
    makeBed();
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fHireDate.set('');
    comp.save();

    expect(comp.saveError()).toBe('Hire date is required.');
  });

  it('calls updateEmployee and clears saving flag on success', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.save();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.updateEmployee).toHaveBeenCalledOnce();
    expect(comp.saving()).toBe(false);
    expect(comp.saveError()).toBeNull();
  });

  it('sets saveError on updateEmployee failure', async () => {
    makeBed({
      updateEmployee: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 422 }))),
    });
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.save();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(comp.saveError()).toBeTruthy();
    expect(comp.saving()).toBe(false);
  });
});

// ── Permissions ───────────────────────────────────────────────────────────────

describe('EmployeeDetailComponent — permissions', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('canManage is true when user has HR.EMPLOYEE.MANAGE', async () => {
    makeBed({}, true);
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.canManage()).toBe(true);
  });

  it('canManage is false when user lacks HR.EMPLOYEE.MANAGE', async () => {
    makeBed({}, false);
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.canManage()).toBe(false);
  });
});

// ── Archive lifecycle action ──────────────────────────────────────────────────

describe('EmployeeDetailComponent — archive action', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('archive() calls hrService.archiveEmployee and updates status to TERMINATED', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.archive();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.archiveEmployee).toHaveBeenCalledOnce();
    expect(comp.employee()?.status).toBe('TERMINATED');
    expect(comp.archiving()).toBe(false);
  });

  it('archive() is idempotent while already archiving (guard)', async () => {
    let callCount = 0;
    makeBed({
      archiveEmployee: vi.fn(() => {
        callCount++;
        return of(undefined);
      }),
    });
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    // Force archiving state to simulate in-flight request
    comp.archiving.set(true);
    comp.archive();

    expect(callCount).toBe(0); // guarded — did not re-enter
  });

  it('archive() sets saveError on failure', async () => {
    makeBed({
      archiveEmployee: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 409 }))),
    });
    const fixture = TestBed.createComponent(EmployeeDetailComponent);
    fixture.componentRef.setInput('uid', 'emp-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.archive();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(comp.saveError()).toBeTruthy();
    expect(comp.archiving()).toBe(false);
  });
});
