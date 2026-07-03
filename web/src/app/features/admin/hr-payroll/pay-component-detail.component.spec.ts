/**
 * PayComponentDetailComponent — unit spec.
 *
 * Covers the load triad and a template-render regression test for the "silent form" bug: the
 * Taxable/Pensionable checkboxes use ngModel inside a template-driven <form> and MUST carry a
 * name attribute (or standalone ngModelOptions), else Angular throws NG01352 during change
 * detection — aborting the CD pass before it reaches the saveError banner further down the
 * template, so a real server validation error ("GlAccount not found.") never rendered.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { GlService } from '../gl/gl.service';
import { HrPayrollService } from './hr-payroll.service';
import { PayComponentDetailComponent } from './pay-component-detail.component';
import type { PayComponentDto } from './models/hr-payroll.model';

vi.useFakeTimers();

function makePayComponent(overrides: Partial<PayComponentDto> = {}): PayComponentDto {
  return {
    id: '1', uid: 'pc-uid-1', companyId: '10', code: 'BASIC', name: 'Basic Salary',
    kind: 'EARNING', basis: 'FIXED', glAccountId: '100', taxable: true, pensionable: true,
    active: true,
    ...overrides,
  };
}

function makeBed(
  hrService: Partial<{
    getPayComponentByUid: ReturnType<typeof vi.fn>;
    updatePayComponent: ReturnType<typeof vi.fn>;
    deactivatePayComponent: ReturnType<typeof vi.fn>;
  }> = {},
) {
  const svc = {
    getPayComponentByUid: vi.fn(() => of(makePayComponent())),
    updatePayComponent: vi.fn(() => of(makePayComponent())),
    deactivatePayComponent: vi.fn(() => of(undefined)),
    ...hrService,
  };

  TestBed.configureTestingModule({
    imports: [PayComponentDetailComponent],
    providers: [
      provideRouter([]),
      { provide: HrPayrollService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal(['HR.PAYCOMPONENT.MANAGE']),
          activeBranchUid: signal(null),
        },
      },
      { provide: GlService, useValue: { listAllActiveAccounts: vi.fn(() => of([])) } },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ id: '10', uid: 'co-uid-1', name: 'Test Co' }])) },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'org-uid-1' })) } },
    ],
  });
  return svc;
}

describe('PayComponentDetailComponent', () => {
  afterEach(() => {
    vi.clearAllTimers();
    TestBed.resetTestingModule();
  });

  it('loads the pay component and patches the form', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PayComponentDetailComponent);
    fixture.componentRef.setInput('uid', 'pc-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.state()).toBe('idle');
    expect(comp.fCode()).toBe('BASIC');
    expect(comp.fTaxable()).toBe(true);
  });

  it('renders the edit form (Taxable/Pensionable checkboxes) without throwing NG01352', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PayComponentDetailComponent);
    fixture.componentRef.setInput('uid', 'pc-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(() => fixture.detectChanges()).not.toThrow();
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('dPcTaxable');
    expect(html).toContain('dPcPens');
  });

  it('a server validation error on save renders the on-screen banner (not silently swallowed)', async () => {
    const svc = makeBed({
      updatePayComponent: vi.fn(() =>
        throwError(
          () =>
            new HttpErrorResponse({ status: 422, error: { errors: ['GlAccount not found.'] } }),
        ),
      ),
    });
    const fixture = TestBed.createComponent(PayComponentDetailComponent);
    fixture.componentRef.setInput('uid', 'pc-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.fGlAccountId.set('999999');
    comp.save();
    fixture.detectChanges();

    expect(svc.updatePayComponent).toHaveBeenCalledOnce();
    expect(comp.saveError()).toBe('GlAccount not found.');

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('GlAccount not found.');
  });
});
