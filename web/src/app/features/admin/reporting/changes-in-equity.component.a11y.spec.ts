/**
 * Accessibility gate — ChangesInEquityComponent.
 *
 * Scans the filter bar and a populated statement on the alarm path (a row that does not add up +
 * the "does not tie" bar), which would otherwise never be rendered in a scan.
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
import { ChangesInEquityComponent } from './changes-in-equity.component';
import { ChangesInEquityDto, EquityMovementRowDto } from './models/reporting.model';
import { ReportingService } from './reporting.service';
import { assertA11y } from '../../../../testing/a11y.helper';

function row(overrides: Partial<EquityMovementRowDto>): EquityMovementRowDto {
  return {
    accountId: null, accountUid: null, accountCode: null, component: 'x', earningsFold: false,
    opening: 0, profitForPeriod: 0, openingBalancesPosted: 0, capitalIntroduced: 0,
    drawingsAndDividends: 0, transfers: 0, closing: 0, ties: true,
    ...overrides,
  };
}

const UNTIED = {
  label: 'Opening + movements == Balance Sheet equity at period end',
  computed: { current: 3150, comparative: 0 },
  expected: { current: 3100, comparative: 0 },
  difference: { current: 50, comparative: 0 },
  ties: false,
};

const MOCK: ChangesInEquityDto = {
  header: {
    companyId: '10', companyName: 'Main Co', currency: 'TZS',
    periodLabel: '2026-01-01 – 2026-09-30', comparativeLabel: 'Opening as at 2025-12-31',
    fromDate: '2026-01-01', toDate: '2026-09-30', asAtDate: null,
    generatedAt: '2026-10-01T08:00:00Z', branchUid: null, branchLabel: 'All branches',
  },
  company: null,
  rows: [
    row({ accountId: '7', accountCode: '3000', component: 'Capital', capitalIntroduced: 500, closing: 2450, ties: false }),
    row({ component: 'Current-year earnings', earningsFold: true, profitForPeriod: 700, closing: 700 }),
  ],
  totals: row({ component: 'Total equity', capitalIntroduced: 500, profitForPeriod: 700, closing: 3150, ties: false }),
  balanceSheetOpeningEquity: 0,
  balanceSheetClosingEquity: 3100,
  profitForPeriod: 700,
  reconciliation: UNTIED,
  transfersCheck: { ...UNTIED, ties: true, difference: { current: 0, comparative: 0 } },
};

describe('ChangesInEquityComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with a populated statement on the alarm path', async () => {
    TestBed.configureTestingModule({
      imports: [ChangesInEquityComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        {
          provide: ReportingService,
          useValue: { changesInEquity: vi.fn(() => of(MOCK)), exportChangesInEquity: vi.fn() },
        },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1' })) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
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
    const fixture = TestBed.createComponent(ChangesInEquityComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
