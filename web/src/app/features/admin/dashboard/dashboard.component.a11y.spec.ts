/**
 * Accessibility gate — DashboardComponent (the Wave-2 BI dashboard, the ADR-0037 a11y pilot).
 *
 * Renders the dashboard with a fully-populated DTO (all panels + trend bars + cash table) and
 * with all panels null (the BI.VIEW-only / no-fine-perm degrade state), asserting axe finds no
 * structural / role / label / alt violations in either. Color-contrast + scrollable-region-focusable
 * are disabled by the shared helper (jsdom limitation).
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { DashboardService } from './dashboard.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchService } from '../branch/branch.service';
import { SessionStore } from '../../../core/auth/session.store';
import { DashboardDto } from './models/dashboard.model';
import { assertA11y } from '../../../../testing/a11y.helper';

const FULL_DTO: DashboardDto = {
  header: {
    companyId: '10', companyName: 'Acme Ltd',
    branchUid: 'BR-UID-1', branchName: 'Kilimanjaro', branchLabel: 'Kilimanjaro',
    currency: 'TZS', periodLabel: 'Jan 2026',
    fromDate: '2026-01-01', toDate: '2026-01-31', asOf: '2026-01-31',
    generatedAt: '2026-01-31T10:00:00Z',
  },
  finance: {
    netProfitPeriod: '50000', revenue: '200000', opex: '30000', netProfit: '50000',
    tbTies: true, tbTotalDebit: '500000', tbTotalCredit: '500000',
    cash: {
      total: '75000',
      accounts: [{
        cashBankAccountId: '1', cashBankAccountUid: 'ACCT-1', accountCode: '1100',
        accountName: 'Main Checking', bookBalance: '75000', currency: 'TZS',
      }],
      cashTies: true, cashGlDifference: '0',
    },
  },
  workingCapital: {
    arOutstanding: '100000', arTies: true, arDifference: '0',
    apOutstanding: '60000', apTies: true, apDifference: '0',
  },
  inventory: { stockValue: '300000', stockTies: true, stockDifference: '0' },
  crm: {
    pipeline: {
      stages: [{
        stageUid: 'STAGE-1', stageName: 'Prospecting', displayOrder: 1, openCount: 5,
        totalValueAmount: '250000', weightedValueAmount: '62500', currency: 'TZS',
      }],
    },
    kpis: {
      periodStart: '2026-01-01', periodEnd: '2026-01-31', wonCount: 3, lostCount: 1,
      winRatePercent: '75', avgCycleDays: '14',
    },
    forecast: {
      periodStart: '2026-01-01', periodEnd: '2026-01-31', weightedValueAmount: '125000',
      openCount: 8, currency: 'TZS',
    },
  },
  revenueTrend: {
    metricLabel: 'Revenue', currency: 'TZS',
    points: [
      { periodLabel: 'Jan 2026', periodStart: '2026-01-01', periodEnd: '2026-01-31', value: '200000' },
      { periodLabel: 'Dec 2025', periodStart: '2025-12-01', periodEnd: '2025-12-31', value: '180000' },
    ],
  },
  netProfitTrend: {
    metricLabel: 'Net Profit', currency: 'TZS',
    points: [
      { periodLabel: 'Jan 2026', periodStart: '2026-01-01', periodEnd: '2026-01-31', value: '50000' },
      { periodLabel: 'Dec 2025', periodStart: '2025-12-01', periodEnd: '2025-12-31', value: '-5000' },
    ],
  },
  salesByBranch: {
    currency: 'TZS',
    grandTotal: '350000',
    invoiceCount: 12,
    rows: [
      { branchId: '100', branchCode: 'HQ', branchName: 'Head Office', total: '250000', count: 8 },
      { branchId: '101', branchCode: 'NBI', branchName: 'Nairobi Branch', total: '100000', count: 4 },
    ],
  },
  health: [
    { label: 'AR-GL', ties: true, difference: '0' },
    { label: 'AP-GL', ties: false, difference: '-500' },
  ],
};

const NULL_PANELS_DTO: DashboardDto = {
  ...FULL_DTO,
  finance: null, workingCapital: null, inventory: null,
  crm: null, revenueTrend: null, netProfitTrend: null, salesByBranch: null, health: [],
};

function makeBed(dto: DashboardDto) {
  TestBed.configureTestingModule({
    imports: [DashboardComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: DashboardService, useValue: { getDashboard: vi.fn(() => of(dto)), exportDashboard: vi.fn(() => of(new Blob())) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Acme Ltd' }])) } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme Org' })) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([{ uid: 'BR1', id: '100', code: 'HQ', name: 'Head Office' }])) } },
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

describe('DashboardComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with all panels populated (cards, trend bars, cash table)', async () => {
    makeBed(FULL_DTO);
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(DashboardComponent);
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations in the degraded (all-panels-null) state', async () => {
    makeBed(NULL_PANELS_DTO);
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(DashboardComponent);
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with a specific branch selected (Group-wide notes + This-branch badges visible)', async () => {
    makeBed(FULL_DTO);
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(DashboardComponent);
    await vi.runAllTimersAsync();
    (fixture.componentInstance as any).onBranchChange('100');
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
