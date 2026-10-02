/**
 * Accessibility gate — FinancialRatiosComponent.
 *
 * Scans the filter bar (incl. the branch picker), a populated ratio table with a value-less ratio
 * (dash + reason) and the source-statement warning, which only renders on that path.
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
import { FinancialRatiosComponent } from './financial-ratios.component';
import { FinancialRatiosDto } from './models/reporting.model';
import { ReportingService } from './reporting.service';
import { assertA11y } from '../../../../testing/a11y.helper';

const MOCK: FinancialRatiosDto = {
  header: {
    companyId: '10', companyName: 'Main Co', currency: 'TZS',
    periodLabel: '2026-04-01 – 2026-06-29', comparativeLabel: 'Opening as at 2026-03-31',
    fromDate: '2026-04-01', toDate: '2026-06-29', asAtDate: null,
    generatedAt: '2026-10-01T08:00:00Z', branchUid: 'BR-ARU', branchLabel: 'Arusha',
  },
  company: null,
  periodDays: '90',
  ratios: [
    {
      key: 'CURRENT_RATIO', name: 'Current ratio', formula: 'Current assets ÷ Current liabilities',
      inputs: [{ label: 'Current assets', amount: 8000 }, { label: 'Current liabilities', amount: 1800 }],
      value: 4.44, unit: 'x', unavailableReason: null, note: null,
    },
    {
      key: 'GROSS_MARGIN', name: 'Gross margin', formula: 'Gross profit ÷ Revenue × 100',
      inputs: [{ label: 'Revenue', amount: 0 }],
      value: null, unit: '%', unavailableReason: 'There is no revenue in the period.',
      note: 'For the selected period.',
    },
  ],
  incomeStatementTies: true,
  balanceSheetTies: false,
  notes: ['A ratio shows no value when what it divides by is zero.'],
};

describe('FinancialRatiosComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with a populated report', async () => {
    TestBed.configureTestingModule({
      imports: [FinancialRatiosComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        {
          provide: ReportingService,
          useValue: { financialRatios: vi.fn(() => of(MOCK)), exportFinancialRatios: vi.fn() },
        },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1' })) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
        { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
        { provide: AuthService, useValue: { myBranches: vi.fn(() => of([])) } },
        {
          provide: SessionStore,
          useValue: {
            hasPermission: vi.fn(() => true),
            isAuthenticated: signal(true),
            user: signal({ isRoot: false }),
            permissions: signal([]),
            activeBranchUid: signal(null),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(FinancialRatiosComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
