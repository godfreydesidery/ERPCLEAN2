/**
 * Accessibility gate — SalesReportComponent.
 *
 * Covers: the filter bar in its empty state, and the populated report (company header + table +
 * totals row).
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AgentService } from '../parties/agent.service';
import { SupplierService } from '../parties/supplier.service';
import { RoutesService } from '../routes/routes.service';
import { ReportingService } from './reporting.service';
import { SalesReportComponent } from './sales-report.component';
import { SalesReportDto } from './models/sales-report.model';
import { assertA11y } from '../../../../testing/a11y.helper';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const emptyListPage = () => ({
  rows: [],
  meta: { page: 0, size: 200, totalElements: 0, totalPages: 0, hasNext: false },
});

const MOCK_REPORT: SalesReportDto = {
  company: {
    name: 'SAM Electronix Ltd', legalName: null,
    addressLine1: 'Plot 12', addressLine2: null, city: 'Dar es Salaam', region: null,
    country: 'Tanzania', contactPhone: '+255700000000', contactEmail: null,
    taxId: 'TIN-999', vrn: 'VRN-888',
  },
  fromDate: '2026-07-01', toDate: '2026-07-19',
  supplierName: null, agentName: 'Jane Agent', routeName: null,
  currency: 'TZS',
  rows: [
    { productCode: 'P001', productName: 'Widget', currentStock: 10, qtySold: 5, discount: 100, vat: 50, margin: 20, amount: 500 },
  ],
  totals: { qtySold: 5, discount: 100, vat: 50, margin: 20, amount: 500, marginRowsUnknown: 0 },
  generatedAt: '2026-07-19T12:00:00Z',
};

function makeBed() {
  TestBed.configureTestingModule({
    imports: [SalesReportComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ReportingService,
        useValue: {
          salesReport: vi.fn(() => of(MOCK_REPORT)),
          exportSalesReport: vi.fn(() => of(new Blob())),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: AgentService, useValue: { list: vi.fn(() => of(emptyListPage())) } },
      { provide: RoutesService, useValue: { list: vi.fn(() => of(emptyListPage())) } },
      { provide: SupplierService, useValue: { list: vi.fn(() => of(emptyListPage())) } },
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

describe('SalesReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations in the empty (pre-run) state', async () => {
    makeBed();
    const fixture = TestBed.createComponent(SalesReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with a populated report', async () => {
    makeBed();
    const fixture = TestBed.createComponent(SalesReportComponent);
    const comp = fixture.componentInstance;
    comp.fromDate.set('2026-07-01');
    comp.toDate.set('2026-07-19');
    comp.run();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
