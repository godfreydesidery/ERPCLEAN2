/**
 * ProjectDetailComponent — customer-picker disambiguation spec.
 *
 * The project's customer picker must show the customer code alongside the
 * display name so two same-named customers are distinguishable — never
 * render name alone.
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
import { UserService } from '../user/user.service';
import { ProductService } from '../products/product.service';
import { ProjectsService } from './projects.service';
import { ProjectDto } from './models/projects.model';
import { ProjectDetailComponent } from './project-detail.component';

const stubOrg = { uid: 'ORG1', id: '1', name: 'Acme Org' };
const stubCompany = { uid: 'CO1', id: '10', name: 'Main Co', status: 'ACTIVE' };

const stubProject: ProjectDto = {
  id: '1', uid: 'PROJ1', companyId: '10', branchId: '1',
  projectNumber: 'PRJ-0001', name: 'ERP Rollout', customerId: '5',
  managerUserId: '2', projectStatus: 'ACTIVE', plannedStartDate: '2026-01-01',
  plannedEndDate: '2026-06-30', budgetAmount: '100000', currency: 'TZS',
  notes: '', status: 'ACTIVE', activatedAt: null, completedAt: null, cancelledAt: null,
} as ProjectDto;

const customerA = { uid: 'CUST1', code: 'CUST-001', displayName: 'Joseph Ulimboka' };
const customerB = { uid: 'CUST2', code: 'CUST-002', displayName: 'Joseph Ulimboka' };

function makeBed() {
  TestBed.configureTestingModule({
    imports: [ProjectDetailComponent],
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
      { provide: UserService, useValue: { list: vi.fn(() => of([])) } },
      { provide: ProductService, useValue: { list: vi.fn(() => of({ rows: [] })) } },
      {
        provide: ProjectsService,
        useValue: {
          getByUid: vi.fn(() => of(stubProject)),
          listTasks: vi.fn(() => of([])),
          listTimesheets: vi.fn(() => of({ rows: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false } })),
        },
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

describe('ProjectDetailComponent — customer picker disambiguation', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('carries the customer code as the picker hint for two same-named customers', async () => {
    const fixture = TestBed.createComponent(ProjectDetailComponent);
    fixture.componentRef.setInput('uid', 'PROJ1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    const options = comp.customerOptions();
    expect(options).toHaveLength(2);
    expect(options.every((o) => !!o.hint)).toBe(true);
    expect(options.map((o) => o.hint)).toEqual(['CUST-001', 'CUST-002']);
  });
});
