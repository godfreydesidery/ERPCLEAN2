/**
 * Accessibility gate — ProfitabilityReportComponent (K-2026-08-30 #2).
 *
 * Covers the period filter bar, the populated report (company header + summary tiles + table +
 * totals foot) and the partial-cost warning banner, which only renders on the incomplete path and
 * would otherwise never be scanned.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProfitabilityReportDto } from './models/profitability-report.model';
import { ProfitabilityReportComponent } from './profitability-report.component';
import { ReportingService } from './reporting.service';
import { assertA11y } from '../../../../testing/a11y.helper';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const MOCK_REPORT: ProfitabilityReportDto = {
  company: {
    name: 'Kilimanjaro Star Liquor Store', legalName: null,
    addressLine1: 'Plot 12', addressLine2: null, city: 'Moshi', region: null,
    country: 'Tanzania', contactPhone: '+255667216866', contactEmail: null,
    taxId: 'TIN-999', vrn: 'VRN-888',
  },
  fromDate: '2026-08-01',
  toDate: '2026-08-31',
  branchName: null,
  currency: 'TZS',
  rows: [
    {
      productCode: 'KON500', productName: 'Konyagi 500ml', qtySold: 120,
      grossSales: 1180000, vatAmount: 180000, netAmount: 1000000,
      costOfSales: 700000, profit: 300000,
    },
    {
      productCode: 'NEW01', productName: 'New arrival', qtySold: 10,
      grossSales: 118000, vatAmount: 18000, netAmount: 100000,
      costOfSales: null, profit: null,
    },
  ],
  totals: {
    qtySold: 130, grossSales: 1298000, vatAmount: 198000, netAmount: 1100000,
    costOfSales: 700000, profit: 300000, rowsWithUnknownCost: 1,
  },
  generatedAt: '2026-09-01T08:00:00Z',
};

function makeBed(reportSpy = vi.fn(() => of(MOCK_REPORT))) {
  TestBed.configureTestingModule({
    imports: [ProfitabilityReportComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ReportingService,
        useValue: {
          profitabilityReport: reportSpy,
          exportProfitabilityReport: vi.fn(() => of(new Blob())),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: AuthService, useValue: { myBranches: vi.fn(() => of([])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal({ activeCompanyUid: 'CO1', isRoot: false }),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

describe('ProfitabilityReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with a populated report and the partial-cost warning', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations in the empty state', async () => {
    makeBed(vi.fn(() => of({ ...MOCK_REPORT, rows: [] })));
    const fixture = TestBed.createComponent(ProfitabilityReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
