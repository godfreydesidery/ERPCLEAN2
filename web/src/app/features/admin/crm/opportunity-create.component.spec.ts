/**
 * OpportunityCreateComponent — regression specs for numeric-input string-op crash class.
 *
 * fEstimatedValue and fWinProbability are bound to type="number" inputs.
 * NumberValueAccessor stores a JS number in the signal; create() previously called
 * .trim() on the raw number (crash). After the fix it coerces with String() first.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { CustomerService } from '../parties/customer.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CrmService } from './crm.service';
import { OpportunityCreateComponent } from './opportunity-create.component';

const stubCompany = { uid: 'CO1', id: '10', name: 'Main Co', status: 'ACTIVE' };
const stubStage = { uid: 'STG1', id: '1', name: 'Qualification', companyId: '10', displayOrder: '10', defaultProbability: '50', active: true };
const stubOpp = { uid: 'OPP1', id: '1', opportunityNumber: 'OPP-0001' };
const customerA = { uid: 'CUST1', code: 'CUST-001', displayName: 'Joseph Ulimboka' };
const customerB = { uid: 'CUST2', code: 'CUST-002', displayName: 'Joseph Ulimboka' };

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed() {
  TestBed.configureTestingModule({
    imports: [OpportunityCreateComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: 'admin/crm/opportunities/uid/:uid', component: OpportunityCreateComponent }]),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => null } } } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme Org' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([stubCompany])) } },
      { provide: CustomerService, useValue: { list: vi.fn(() => of({ rows: [customerA, customerB] })) } },
      {
        provide: CrmService,
        useValue: {
          listPipelineStages: vi.fn(() => of([stubStage])),
          listLeads: vi.fn(() => of({ rows: [] })),
          createOpportunity: vi.fn(() => of(stubOpp)),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });
}

// ── numeric field coercion ────────────────────────────────────────────────────

describe('OpportunityCreateComponent — numeric field coercion', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('create() does not throw when fEstimatedValue and fWinProbability hold numbers', async () => {
    const comp = TestBed.createComponent(OpportunityCreateComponent).componentInstance;
    const svc = TestBed.inject(CrmService) as any;
    await vi.runAllTimersAsync();

    comp.fTitle.set('New Deal');
    comp.fCustomerUid.set('CUST1');
    comp.fPipelineStageUid.set('STG1');
    comp.fCurrency.set('TZS');
    // Simulate NumberValueAccessor storing JS numbers in string-typed signals.
    comp.fEstimatedValue.set(500000 as unknown as string);
    comp.fWinProbability.set(60 as unknown as string);

    expect(() => comp.create()).not.toThrow();
    expect(svc.createOpportunity).toHaveBeenCalledOnce();
    const body = svc.createOpportunity.mock.calls[0][0];
    expect(body.estimatedValueAmount).toBe('500000');
    expect(body.winProbability).toBe('60');
  });

  it('create() treats empty number field (null from type="number" cleared) as absent', async () => {
    const comp = TestBed.createComponent(OpportunityCreateComponent).componentInstance;
    const svc = TestBed.inject(CrmService) as any;
    await vi.runAllTimersAsync();

    comp.fTitle.set('Blank Value Deal');
    comp.fCustomerUid.set('CUST1');
    comp.fPipelineStageUid.set('STG1');
    comp.fCurrency.set('TZS');
    // null is emitted by type="number" when the field is cleared.
    comp.fEstimatedValue.set(null as unknown as string);
    comp.fWinProbability.set(null as unknown as string);

    expect(() => comp.create()).not.toThrow();
    expect(svc.createOpportunity).toHaveBeenCalledOnce();
    const body = svc.createOpportunity.mock.calls[0][0];
    expect(body.estimatedValueAmount).toBeUndefined();
    expect(body.winProbability).toBeUndefined();
  });
});

// ── customer-picker disambiguation ────────────────────────────────────────────

describe('OpportunityCreateComponent — customer picker disambiguation', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('carries the customer code as the picker hint for two same-named customers', async () => {
    const comp = TestBed.createComponent(OpportunityCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    const options = comp.customerOptions();
    expect(options).toHaveLength(2);
    expect(options.every((o) => !!o.hint)).toBe(true);
    expect(options.map((o) => o.hint)).toEqual(['CUST-001', 'CUST-002']);
  });
});
