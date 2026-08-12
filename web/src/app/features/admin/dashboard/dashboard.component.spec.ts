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
    branchUid: null,
    branchName: null,
    branchLabel: 'All branches',
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

// ── Null-panel DTO (all panels null — user lacks fine perms) ──────────────────

const NULL_PANELS_DTO: DashboardDto = {
  ...MOCK_DTO,
  finance: null,
  workingCapital: null,
  inventory: null,
  crm: null,
  revenueTrend: null,
  netProfitTrend: null,
  salesByBranch: null,
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
    // selectedBranchId defaults to '' (All branches) → service receives undefined
    expect(svc.getDashboard).toHaveBeenCalledWith('10', expect.anything(), expect.anything(), undefined);
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

  // 8. healthPrefix — drives the text label inside every health status-tag
  it('healthPrefix returns [OK] when ties, [!] when not', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    expect(comp.healthPrefix(true)).toBe('[OK]');
    expect(comp.healthPrefix(false)).toBe('[!]');
  });

  it('health strip renders status-tag--ok for a balanced indicator, status-tag--danger for imbalance', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO));
    const fixture = TestBed.createComponent(DashboardComponent);
    TestBed.flushEffects();
    fixture.detectChanges();
    // MOCK_DTO.health = [{label:'AR-GL', ties:true}, {label:'AP-GL', ties:false}]
    const tags: NodeListOf<HTMLElement> =
      fixture.nativeElement.querySelectorAll('output .status-tag');
    expect(tags.length).toBeGreaterThanOrEqual(2);
    const arTag = Array.from(tags).find((el) => el.textContent?.includes('AR-GL'));
    const apTag = Array.from(tags).find((el) => el.textContent?.includes('AP-GL'));
    expect(arTag?.classList).toContain('status-tag--ok');
    expect(apTag?.classList).toContain('status-tag--danger');
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

  // 10. Sales by Branch: panel populates on success; null panel + no perm → forbidden
  it('salesByBranch panel populates with rows and grand total', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO));
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    expect(comp.salesByBranchState()).toBe('idle');
    expect(comp.salesByBranch()?.rows.length).toBe(2);
    expect(comp.salesByBranch()?.grandTotal).toBe('350000');
    expect(comp.salesByBranch()?.invoiceCount).toBe(12);
  });

  it('salesByBranch null + user lacks BI.FINANCE.VIEW → state forbidden', () => {
    vi.useFakeTimers();
    const restrictedSession = makeSession(['BI.VIEW', 'BI.OPS.VIEW', 'BI.CRM.VIEW']); // no BI.FINANCE.VIEW
    makeBed(of(NULL_PANELS_DTO), restrictedSession);
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    expect(comp.salesByBranchState()).toBe('forbidden');
    expect(comp.salesByBranch()).toBeNull();
  });

  it('salesByBranch null + user has BI.FINANCE.VIEW → state idle (empty)', () => {
    vi.useFakeTimers();
    makeBed(of(NULL_PANELS_DTO), makeSession(['BI.VIEW', 'BI.FINANCE.VIEW']));
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    expect(comp.salesByBranchState()).toBe('idle');
    expect(comp.salesByBranch()).toBeNull();
  });

  // 11. Branch picker defaults to empty ("All branches")
  it('selectedBranchId defaults to empty string after branch load', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO));
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    expect(comp.selectedBranchId()).toBe('');
  });

  // 12. 403 error includes salesByBranch in the forbidden sweep
  it('403 error → salesByBranchState becomes forbidden', () => {
    vi.useFakeTimers();
    const err403 = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    makeBed(throwError(() => err403));
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    expect(comp.salesByBranchState()).toBe('forbidden');
  });

  // ── Branch-filter honesty labelling (UPR "silently doesn't scope most panels") ──
  // Backend wiring: only /crm-summary and /sales-by-branch accept branchId; finance,
  // working-capital, inventory and the two trend endpoints are always company-wide.

  // 13. branchScopeActive reflects the branch picker
  it('branchScopeActive is false for "All branches", true once a branch is picked', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO));
    const comp = TestBed.createComponent(DashboardComponent).componentInstance as any;
    TestBed.flushEffects();
    expect(comp.branchScopeActive()).toBe(false);
    comp.onBranchChange('100');
    expect(comp.branchScopeActive()).toBe(true);
  });

  // 14. "Group-wide" badge is always present on the company-wide panels, regardless
  // of branch selection — the reader should never have to guess.
  it('renders a "Group-wide" badge on Finance, Cash Position, Working Capital, Inventory and both Trend panels', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO));
    const fixture = TestBed.createComponent(DashboardComponent);
    TestBed.flushEffects();
    fixture.detectChanges();
    const badges = Array.from(
      fixture.nativeElement.querySelectorAll('h2 .status-tag'),
    ) as HTMLElement[];
    const groupWide = badges.filter((b) => b.textContent?.trim() === 'Group-wide');
    // Finance, Cash Position, Working Capital, Inventory, Revenue Trend, Net Profit Trend
    expect(groupWide.length).toBe(6);
  });

  // 15. Once a specific branch is selected, the company-wide panels also carry an
  // explicit inline note (belt-and-braces beyond the badge).
  it('selecting a specific branch shows the "not affected by the branch filter" note on company-wide panels', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO));
    const fixture = TestBed.createComponent(DashboardComponent);
    const comp = fixture.componentInstance as any;
    TestBed.flushEffects();
    fixture.detectChanges();

    let notes = Array.from(fixture.nativeElement.querySelectorAll('p')) as HTMLElement[];
    expect(notes.some((p) => p.textContent?.includes('not affected by the branch filter'))).toBe(false);

    comp.onBranchChange('100');
    TestBed.flushEffects();
    fixture.detectChanges();

    notes = Array.from(fixture.nativeElement.querySelectorAll('p')) as HTMLElement[];
    const scopeNotes = notes.filter((p) => p.textContent?.includes('not affected by the branch filter'));
    expect(scopeNotes.length).toBe(6);
  });

  // 16. CRM and Sales-by-Branch DO react to the branch filter — label them "This branch"
  // only once a specific branch is picked (no ambiguity when "All branches" is active).
  it('shows "This branch" badge on CRM and Sales by Branch only once a branch is selected', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO));
    const fixture = TestBed.createComponent(DashboardComponent);
    const comp = fixture.componentInstance as any;
    TestBed.flushEffects();
    fixture.detectChanges();

    let thisBranchBadges = Array.from(fixture.nativeElement.querySelectorAll('h2 .status-tag'))
      .filter((b: any) => b.textContent?.trim() === 'This branch');
    expect(thisBranchBadges.length).toBe(0);

    comp.onBranchChange('100');
    TestBed.flushEffects();
    fixture.detectChanges();

    thisBranchBadges = Array.from(fixture.nativeElement.querySelectorAll('h2 .status-tag'))
      .filter((b: any) => b.textContent?.trim() === 'This branch');
    expect(thisBranchBadges.length).toBe(2); // CRM + Sales by Branch
  });

  // ── UAT 2026-08: the header must echo the branch the SERVER filtered to ──────
  // The badges above are rendered from this browser's own picker state, which proves nothing
  // about what the server did. This banner is the server's own account of the request's scope.

  // 17. No branch filter → the page says so out loud rather than saying nothing.
  it('renders the server scope banner as "All branches" when no branch was filtered', () => {
    vi.useFakeTimers();
    makeBed(of(MOCK_DTO));
    const fixture = TestBed.createComponent(DashboardComponent);
    TestBed.flushEffects();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Branch: All branches');
  });

  // 18. Branch filtered → the header names the branch the server actually used.
  it('renders the branch the server reports it filtered to', () => {
    vi.useFakeTimers();
    const branchDto: DashboardDto = {
      ...MOCK_DTO,
      header: {
        ...MOCK_DTO.header,
        branchUid: 'BR-UID-KILI',
        branchName: 'Kilimanjaro',
        branchLabel: 'Kilimanjaro',
      },
    };
    makeBed(of(branchDto));
    const fixture = TestBed.createComponent(DashboardComponent);
    TestBed.flushEffects();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Branch: Kilimanjaro');
    expect(fixture.nativeElement.textContent).not.toContain('Branch: All branches');
  });
});
