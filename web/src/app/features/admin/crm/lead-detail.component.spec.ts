/**
 * LeadDetailComponent — customer-picker disambiguation spec.
 *
 * The "Link existing customer" picker (qualify flow) must show the customer
 * code alongside the display name so two same-named customers are
 * distinguishable — never render name alone.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from '../parties/customer.service';
import { CustomerModel } from '../models/party.model';
import { CrmService } from './crm.service';
import { LeadDto } from './models/crm.model';
import { LeadDetailComponent } from './lead-detail.component';

const stubOrg = { uid: 'ORG1', id: '1', name: 'Acme Org' };
const stubCompany = { uid: 'CO1', id: '10', name: 'Main Co', status: 'ACTIVE' };

const stubLead: LeadDto = {
  id: '1', uid: 'LEAD1', companyId: '10', branchId: '1',
  leadNumber: 'LEAD-0001', leadStatus: 'QUALIFIED', leadSource: 'WEBSITE',
  displayName: 'Joseph Ulimboka', companyName: null, contactPerson: null,
  phone: null, email: null, ownerUserId: null, customerId: null, customerUid: null,
  disqualifyReason: null, notes: null, qualifiedAt: null, convertedAt: null,
  disqualifiedAt: null, status: 'ACTIVE', version: '1', createdAt: '2026-01-01T00:00:00Z',
  createdBy: null, updatedAt: null,
} as unknown as LeadDto;

const customerA: Partial<CustomerModel> = { uid: 'CUST1', code: 'CUST-001', displayName: 'Joseph Ulimboka' };
const customerB: Partial<CustomerModel> = { uid: 'CUST2', code: 'CUST-002', displayName: 'Joseph Ulimboka' };

function makeBed() {
  TestBed.configureTestingModule({
    imports: [LeadDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(stubOrg)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([stubCompany])) } },
      {
        provide: CustomerService,
        useValue: { list: vi.fn(() => of({ rows: [customerA, customerB] })) },
      },
      {
        provide: CrmService,
        useValue: { getLeadByUid: vi.fn(() => of(stubLead)) },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

describe('LeadDetailComponent — customer picker disambiguation', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('carries the customer code as the picker hint for two same-named customers', async () => {
    const fixture = TestBed.createComponent(LeadDetailComponent);
    fixture.componentRef.setInput('uid', 'LEAD1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    const options = comp.customerOptions();
    expect(options).toHaveLength(2);
    expect(options.every((o) => !!o.hint)).toBe(true);
    expect(options.map((o) => o.hint)).toEqual(['CUST-001', 'CUST-002']);
  });
});
