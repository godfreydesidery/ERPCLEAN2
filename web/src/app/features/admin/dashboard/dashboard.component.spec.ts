import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { DashboardComponent } from './dashboard.component';
import { DashboardService } from './dashboard.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchService } from '../branch/branch.service';
import { SessionStore } from '../../../core/auth/session.store';
import { DashboardDto } from './models/dashboard.model';

// ── Minimal fixture DTO ───────────────────────────────────────────────────────

const MOCK_DTO: DashboardDto = {
  header: {
    companyId: '10',
    companyName: 'Acme Ltd',
    currency: 'TZS',
    periodLabel: 'Jan 2026',
    fromDate: '2026-01-01',
    toDate: '2026-01-31',
    asOf: '2026-01-31',
    generatedAt: '2026-01-31T10:00:00Z',
  },
  finance: {
    netProfitPeriod: '50000',
    revenue: '200000',
    opex: '30000',
    netProfit: '50000',
    tbTies: true,
    tbTotalDebit: '500000',
    tbTotalCredit: '500000',
    cash: {
      total: '75000',
      accounts: [
        {
          cashBankAccountId: '1',
          cashBankAccountUid: 'ACCT-1',
          accountCode: '1100',
          accountName: 'Main Checking',
          bookBalance: '75000',
          currency: 'TZS',
        },
      ],
      cashTies: true,
      cashGlDifference: '0',
    },
  },
  workingCapital: {
    arOutstanding: '100000',
    arTies: true,
    arDifference: '0',
    apOutstanding: '60000',
    apTies: true,
    apDifference: '0',
  },
  inventory: {
    stockValue: '300000',
    stockTies: true,
    stockDifference: '0',
  },
  crm: {
    pipeline: {
      stages: [
        {
          stageUid: 'STAGE-1',
          stageName: 'Prospecting',
          displayOrder: 1,
          openCount: 5,
          totalValueAmount: '250000',
          weightedValueAmount: '62500',
          currency: 'TZS',
        },
      ],
    },
    kpis: {
      periodStart: '2026-01-01',
      periodEnd: '2026-01-31',
      wonCount: 3,
      lostCount: 1,
      winRatePercent: '75',
      avgCycleDays: '14',
    },
    forecast: {
      periodStart: '2026-01-01',
      periodEnd: '2026-01-31',
      weightedValueAmount: '125000',
      openCount: 8,
      currency: 'TZS',
    },
  },
  revenueTrend: {
    metricLabel: 'Revenue',
    currency: 'TZS',
    points: [
      { periodLabel: 'Jan 2026', periodStart: '2026-01-01', periodEnd: '2026-01-31', value: '200000' },
      { periodLabel: 'Dec 2025', periodStart: '2025-12-01', periodEnd: '2025-12-31', value: '180000' },
    ],
  },
  netProfitTrend: {
    metricLabel: 'Net Profit',
    currency: 'TZS',
    points: [
      { periodLabel: 'Jan 2026', periodStart: '2026-01-01', periodEnd: '2026-01-31', value: '50000' },
      { periodLabel: 'Dec 2025', periodStart: '2025-12-01', periodEnd: '2025-12-31', value: '-5000' },
    ],
  },
  health: [
    { label: 'AR-GL', ties: true, difference: '0' },
    { label: 'AP-GL', ties: false, difference: '-500' },
  ],
};

// ── Null-panel DTO (all panels null — user lacks fine perms) ──────────────────

const NULL_PANELS_DTO: DashboardDto = {
  ...MOCK_DTO,
  finance: null,
  workingCapital: null,
  inventory: null,
  crm: null,
  revenueTrend: null,
  netProfitTrend: null,
  health: [],
};

// ── Session helpers ────────────────────────────────────────────────────────────

function makeSession(perms: string[] = ['BI.VIEW', 'BI.FINANCE.VIEW', 'BI.OPS.VIEW', 'BI.CRM.VIEW', 'BI.EXPORT']) {
  return {
    hasPermission: vi.fn((code: string) => perms.includes(code)),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeCompanyService() {
  return { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Acme Ltd' }])) };
}

function makeOrgService() {
  return { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme Org' })) };
}

function makeBranchService() {
  return {
    list: vi.fn(() =>
      of([{ uid: 'BR1', id: '100', code: 'HQ', name: 'Head Office' }]),
    ),
  };
}

function makeBed(
  dashboardObs = of(MOCK_DTO),
  session = makeSession(),
) {
  TestBed.configureTestingModule({
    imports: [DashboardComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      {
        provide: DashboardService,
        useValue: {
          getDashboard: vi.fn(() => dashboardObs),
          exportDashboard: vi.fn(() => of(new Blob())),
        },
      },
      { provide: OrganisationService, useValue: makeOrgService() },
      { provide: CompanyService, useValue: makeCompanyService() },
      { provide: BranchService, useValue: makeBranchService() },
      { provide: SessionStore, useValue: session },
    ],
  });
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('DashboardComponent', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  // 1. Load-once: a single getDashboard call is made on construction + company/branch bootstrap
  it('load-once: getDashboard called once when company+branch resolve', () => {
    vi.useFakeTimers();
    const svc = { getDashboard: vi.fn(() => of(MOCK_DTO)), exportDashboard: vi.fn(() => of(new Blob())) };
    TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', redirectTo: '' }]),
        { provide: DashboardService, useValue: svc },
        { provide: OrganisationService, useValue: makeOrgService() },
        { provide: CompanyService, useValue: makeCompanyService() },
        { provide: BranchService, useValue: makeBranchService() },
        { provide: SessionStore, useValue: makeSession() },
      ],
    });
    TestBed.createComponent(DashboardComponent);
    TestBed.flushEffects();
    expect(svc.getDashboard).toHaveBeenCalledTimes(1);
    expect(svc.getDashboard).toHaveBeenCalledWith('10', expect.anything(), expect.anything(), '100');
  });

  // 2. isEmpty (no panels): when all DTO panels are null, all states settle to 'idle' (empty)
  it('isEmpty: null panels → states settle to idle (empty, not error)', () => {
    vi.useFakeTimers();
    makeBed(of(NULL_PANELS_DTO), makeSession(['BI.VIEW', 'BI.FINANCE.VIEW', 'BI.OPS.VIEW', 'BI.CRM.VIEW']));
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    expect(comp.financeState()).toBe('idle');
    expect(comp.finance()).toBeNull();
    expect(comp.inventoryState()).toBe('idle');
    expect(comp.inventory()).toBeNull();
    expect(comp.crmState()).toBe('idle');
    expect(comp.crm()).toBeNull();
  });

  // 3. 403 → forbidden per panel: a top-level 403 sets ALL panel states to 'forbidden'
  it('403 error → all panel states become forbidden', () => {
    vi.useFakeTimers();
    const err403 = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    makeBed(throwError(() => err403));
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    expect(comp.financeState()).toBe('forbidden');
    expect(comp.workingCapitalState()).toBe('forbidden');
    expect(comp.inventoryState()).toBe('forbidden');
    expect(comp.crmState()).toBe('forbidden');
    expect(comp.revenueTrendState()).toBe('forbidden');
    expect(comp.netProfitTrendState()).toBe('forbidden');
  });

  // 4. Non-403 error → all panel states become 'error'
  it('500 error → all panel states become error', () => {
    vi.useFakeTimers();
    const err500 = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    makeBed(throwError(() => err500));
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    expect(comp.financeState()).toBe('error');
    expect(comp.crmState()).toBe('error');
  });

  // 5. Null panel + lacking fine perm → state becomes 'forbidden'
  it('null panel + user lacks fine perm → panelState is forbidden', () => {
    vi.useFakeTimers();
    // User has BI.VIEW but NOT BI.OPS.VIEW
    const restrictedSession = makeSession(['BI.VIEW', 'BI.FINANCE.VIEW', 'BI.CRM.VIEW']);
    makeBed(of(NULL_PANELS_DTO), restrictedSession);
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    // inventory null + no BI.OPS.VIEW → forbidden
    expect(comp.inventoryState()).toBe('forbidden');
    // finance null + has BI.FINANCE.VIEW → idle (empty, not forbidden)
    expect(comp.financeState()).toBe('idle');
  });

  // 6. Perm-gated computed: canExport reflects session permission
  it('canExport reflects BI.EXPORT permission', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO), makeSession(['BI.VIEW'])); // no BI.EXPORT
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    expect(comp.canExport()).toBe(false);
  });

  it('canExport true when BI.EXPORT granted', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO), makeSession(['BI.VIEW', 'BI.EXPORT']));
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    expect(comp.canExport()).toBe(true);
  });

  // 7. trendBarWidth: proportional scaling
  it('trendBarWidth returns 100 for value equal to max', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    expect(comp.trendBarWidth('200000', 200000)).toBe(100);
  });

  it('trendBarWidth returns 50 for half of max', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    expect(comp.trendBarWidth('100000', 200000)).toBe(50);
  });

  // 8. healthPrefix / healthBadgeClass
  it('healthPrefix returns [OK] when ties', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    expect(comp.healthPrefix(true)).toBe('[OK]');
    expect(comp.healthPrefix(false)).toBe('[!]');
  });

  it('healthBadgeClass returns text-bg-success when ties, text-bg-danger when not', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    expect(comp.healthBadgeClass(true)).toBe('text-bg-success');
    expect(comp.healthBadgeClass(false)).toBe('text-bg-danger');
  });

  // 9. Full DTO: panels all populate correctly on success
  it('successful load populates all panel signals', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO));
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    expect(comp.financeState()).toBe('idle');
    expect(comp.finance()?.revenue).toBe('200000');
    expect(comp.workingCapitalState()).toBe('idle');
    expect(comp.workingCapital()?.arOutstanding).toBe('100000');
    expect(comp.inventoryState()).toBe('idle');
    expect(comp.inventory()?.stockValue).toBe('300000');
    expect(comp.crmState()).toBe('idle');
    expect(comp.crm()?.kpis?.wonCount).toBe(3);
    expect(comp.revenueTrendState()).toBe('idle');
    expect(comp.revenueTrend()?.points.length).toBe(2);
    expect(comp.health().length).toBe(2);
  });
});
