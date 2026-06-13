/**
 * DepartmentListComponent — unit spec.
 * Covers: load-once, isEmpty, 403→forbidden, validation guard, create success.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { HrDepartmentService } from './hr-department.service';
import { DepartmentListComponent } from './department-list.component';
import type { DepartmentDto } from '../models/hr-payroll.model';

vi.useFakeTimers();

function makeDept(overrides: Partial<DepartmentDto> = {}): DepartmentDto {
  return {
    id: '1', uid: 'dept-uid-1', companyId: '10',
    code: 'FIN', name: 'Finance', active: true,
    ...overrides,
  };
}

function makeBed(
  deptSvc: Partial<{ list: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn> }> = {},
  canManage = true,
) {
  const svc = {
    list: vi.fn(() => of([makeDept()])),
    create: vi.fn(() => of(makeDept())),
    update: vi.fn(() => of(makeDept())),
    deactivate: vi.fn(() => of(undefined)),
    ...deptSvc,
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
    imports: [DepartmentListComponent],
    providers: [
      provideRouter([]),
      { provide: HrDepartmentService, useValue: svc },
      { provide: OrganisationService, useValue: orgSvc },
      { provide: CompanyService, useValue: companySvc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: session },
    ],
  });

  const fixture = TestBed.createComponent(DepartmentListComponent);
  const comp = fixture.componentInstance;
  return { fixture, comp, svc, session };
}

describe('DepartmentListComponent', () => {
  it('loads departments on init', () => {
    const { comp, svc } = makeBed();
    TestBed.runInInjectionContext(() => {});
    // constructor triggers loadCompanies → loadDepts
    expect(svc.list).toHaveBeenCalledOnce();
  });

  it('sets state=idle and populates rows after success', () => {
    const { comp } = makeBed();
    expect(comp.state()).toBe('idle');
    expect(comp.rows().length).toBe(1);
    expect(comp.rows()[0].code).toBe('FIN');
  });

  it('isEmpty is true when no rows returned', () => {
    const { comp } = makeBed({ list: vi.fn(() => of([])) });
    expect(comp.isEmpty()).toBe(true);
  });

  it('sets state=forbidden on 403', () => {
    const err = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    const { comp } = makeBed({ list: vi.fn(() => throwError(() => err)) });
    expect(comp.state()).toBe('forbidden');
  });

  it('sets state=error on 500', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    const { comp } = makeBed({ list: vi.fn(() => throwError(() => err)) });
    expect(comp.state()).toBe('error');
  });

  it('validates required code before create', () => {
    const { comp, svc } = makeBed();
    comp.fCode.set('');
    comp.fName.set('HR');
    comp.create();
    expect(svc.create).not.toHaveBeenCalled();
    expect(comp.formError()).toBeTruthy();
  });

  it('validates required name before create', () => {
    const { comp, svc } = makeBed();
    comp.fCode.set('HR');
    comp.fName.set('');
    comp.create();
    expect(svc.create).not.toHaveBeenCalled();
    expect(comp.formError()).toBeTruthy();
  });

  it('calls create service and reloads on success', () => {
    const { comp, svc } = makeBed();
    comp.fCode.set('IT');
    comp.fName.set('Information Technology');
    comp.create();
    expect(svc.create).toHaveBeenCalledWith({ code: 'IT', name: 'Information Technology' });
    // list called again after create
    expect(svc.list).toHaveBeenCalledTimes(2);
  });

  it('canManage is false when permission is absent', () => {
    const { comp } = makeBed({}, false);
    expect(comp.canManage()).toBe(false);
  });
});
