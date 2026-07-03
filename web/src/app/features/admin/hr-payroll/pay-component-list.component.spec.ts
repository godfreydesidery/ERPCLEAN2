/**
 * PayComponentListComponent — unit spec.
 * Covers: load, validation guards, create success — and a template-render regression test for
 * the "silent form" bug: the Taxable/Pensionable checkboxes use ngModel inside a template-driven
 * <form> and MUST carry a name attribute (or standalone ngModelOptions), else Angular throws
 * NG01352 during change detection, which aborts the CD pass before it reaches the error banner
 * further down the template — so a real server validation error never became visible on screen.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { HrPayrollService } from './hr-payroll.service';
import { PayComponentListComponent } from './pay-component-list.component';
import type { PayComponentDto } from './models/hr-payroll.model';

function makePayComponent(overrides: Partial<PayComponentDto> = {}): PayComponentDto {
  return {
    id: '1', uid: 'pc-uid-1', companyId: '10', code: 'BASIC', name: 'Basic Salary',
    kind: 'EARNING', basis: 'FIXED', glAccountId: '100', taxable: true, pensionable: true,
    active: true,
    ...overrides,
  };
}

function makeBed(
  hrOverrides: Partial<{ listPayComponents: ReturnType<typeof vi.fn>; createPayComponent: ReturnType<typeof vi.fn> }> = {},
) {
  const hrSvc = {
    listPayComponents: vi.fn(() => of([makePayComponent()])),
    createPayComponent: vi.fn(() => of(makePayComponent())),
    ...hrOverrides,
  };
  const orgSvc = { current: vi.fn(() => of({ uid: 'org-1' })) };
  const companySvc = {
    list: vi.fn(() => of([{ id: '10', uid: 'co-uid', name: 'Test Co', organisationUid: 'org-1' }])),
  };
  const session = { hasPermission: vi.fn(() => true), isAuthenticated: vi.fn(() => true) };

  TestBed.configureTestingModule({
    imports: [PayComponentListComponent],
    providers: [
      provideRouter([]),
      { provide: HrPayrollService, useValue: hrSvc },
      { provide: OrganisationService, useValue: orgSvc },
      { provide: CompanyService, useValue: companySvc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: session },
    ],
  });

  const fixture = TestBed.createComponent(PayComponentListComponent);
  const comp = fixture.componentInstance;
  return { fixture, comp, hrSvc };
}

describe('PayComponentListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('loads pay components on init', () => {
    const { hrSvc } = makeBed();
    expect(hrSvc.listPayComponents).toHaveBeenCalledOnce();
  });

  it('renders the create form (Taxable/Pensionable checkboxes) without throwing NG01352', () => {
    const { fixture, comp } = makeBed();
    comp.toggleCreateForm();
    expect(() => fixture.detectChanges()).not.toThrow();
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('pcTaxable');
    expect(html).toContain('pcPensionable');
  });

  it('a server validation error on create renders the on-screen banner (not silently swallowed)', () => {
    const { fixture, comp, hrSvc } = makeBed({
      createPayComponent: vi.fn(() =>
        throwError(
          () =>
            new HttpErrorResponse({ status: 422, error: { errors: ['GlAccount not found.'] } }),
        ),
      ),
    });
    comp.toggleCreateForm();
    fixture.detectChanges();

    comp.fCode.set('BASIC');
    comp.fName.set('Basic Salary');
    comp.fGlAccountId.set('999999');
    comp.create();
    fixture.detectChanges();

    expect(hrSvc.createPayComponent).toHaveBeenCalledOnce();
    expect(comp.formError()).toBe('GlAccount not found.');

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('GlAccount not found.');
  });
});
