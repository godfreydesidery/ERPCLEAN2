import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchService } from '../branch/branch.service';
import { Company } from '../models/company.model';
import { Branch } from '../models/branch.model';
import { downloadBlob } from '../reporting/reporting.utils';
import { serverMessage } from '../reporting/report-filter-options.service';
import { DashboardService } from './dashboard.service';
import {
  BiHeaderDto,
  DashboardDto,
  FinanceSummaryDto,
  WorkingCapitalDto,
  InventorySummaryDto,
  CrmSnapshotDto,
  TrendDto,
  HealthIndicatorDto,
  SalesByBranchDto,
} from './models/dashboard.model';

type LoadState = 'loading' | 'idle' | 'error' | 'forbidden';

/**
 * BI Analytics Dashboard (ADR-0037 D-7/D-8).
 * Route: /admin/dashboard — gated BI.VIEW.
 * Per-panel signal trios + four-state @switch + graceful per-panel forbidden.
 * Chart-free: stat-cards, CSS bars, tables.
 */
@Component({
  selector: 'app-dashboard',
  imports: [DecimalPipe, FormsModule, RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent {
  private readonly dashboardService = inject(DashboardService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly auth = inject(AuthService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Branch context ────────────────────────────────────────────────────────────
  readonly branches = signal<Branch[]>([]);
  readonly selectedBranchId = signal('');
  readonly branchState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Date range ────────────────────────────────────────────────────────────────
  readonly fromDate = signal<string>(this.defaultFrom());
  readonly toDate = signal<string>(this.defaultTo());

  // ── Finance panel ─────────────────────────────────────────────────────────────
  readonly finance = signal<FinanceSummaryDto | null>(null);
  readonly financeState = signal<LoadState>('idle');

  // ── Working capital panel ─────────────────────────────────────────────────────
  readonly workingCapital = signal<WorkingCapitalDto | null>(null);
  readonly workingCapitalState = signal<LoadState>('idle');

  // ── Inventory panel ───────────────────────────────────────────────────────────
  readonly inventory = signal<InventorySummaryDto | null>(null);
  readonly inventoryState = signal<LoadState>('idle');

  // ── CRM panel ─────────────────────────────────────────────────────────────────
  readonly crm = signal<CrmSnapshotDto | null>(null);
  readonly crmState = signal<LoadState>('idle');

  // ── Trend panels ─────────────────────────────────────────────────────────────
  readonly revenueTrend = signal<TrendDto | null>(null);
  readonly revenueTrendState = signal<LoadState>('idle');
  readonly netProfitTrend = signal<TrendDto | null>(null);
  readonly netProfitTrendState = signal<LoadState>('idle');

  // ── Sales by Branch panel ─────────────────────────────────────────────────────
  readonly salesByBranch = signal<SalesByBranchDto | null>(null);
  readonly salesByBranchState = signal<LoadState>('idle');

  // ── Health strip ──────────────────────────────────────────────────────────────
  readonly health = signal<HealthIndicatorDto[]>([]);

  // ── Header (UAT 2026-08) ─────────────────────────────────────────────────────
  // The SERVER's account of what it just filtered to. Deliberately not the same thing as
  // selectedBranchLabel() below, which only says what this browser asked for: the point of the
  // defect was that nothing proved the request had been honoured, and a label rendered from the
  // picker's own state would prove exactly as little.
  readonly header = signal<BiHeaderDto | null>(null);

  /**
   * The server's own sentence when it refuses the branch filter (the caller is not assigned to
   * that branch). The picker only offers the caller's branches, so this is the rare case — an
   * assignment revoked mid-session — and without it every panel would just say "no permission".
   */
  readonly branchRefusal = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('BI.VIEW'));
  readonly canFinance = computed(() => this.session.hasPermission('BI.FINANCE.VIEW'));
  readonly canOps = computed(() => this.session.hasPermission('BI.OPS.VIEW'));
  readonly canCrm = computed(() => this.session.hasPermission('BI.CRM.VIEW'));
  /**
   * Exact parity with GET /bi/dashboard/export, which requires BI.VIEW AND BI.EXPORT. (The route
   * guard already demands BI.VIEW; stating it here keeps the button honest if the guard changes.)
   */
  readonly canExport = computed(
    () => this.session.hasPermission('BI.VIEW') && this.session.hasPermission('BI.EXPORT'),
  );

  // ── Derived: branch-filter scope (honesty labelling — UPR "silently doesn't
  // scope most panels") ────────────────────────────────────────────────────────
  // Backend wiring (BiDashboardController): only /crm-summary and /sales-by-branch
  // accept branchId. /finance-summary, /working-capital, /inventory, /revenue-trend
  // and /net-profit-trend never take a branchId — they are always company-wide.
  readonly branchScopeActive = computed(() => this.selectedBranchId() !== '');

  readonly selectedBranchLabel = computed(() => {
    const id = this.selectedBranchId();
    if (!id) return '';
    const b = this.branches().find((x) => x.id === id);
    return b ? `${b.code} — ${b.name}` : '';
  });

  /**
   * Cash total in base currency, plus each foreign currency kept out of it. The payload's
   * `cash.total` adds every account's own-currency balance together (TZS + USD = a number in no
   * currency); the per-account rows carry their currency, so the split is made here from them —
   * the AR/AP rule (owner ruling 2026-10-02). No currency on an account counts as base.
   */
  readonly cashSplit = computed(() => {
    const cash = this.finance()?.cash;
    const base = this.header()?.currency;
    if (!cash) return null;
    if (!base) return { total: +cash.total, foreign: [] as { currency: string; amount: number }[] };
    let total = 0;
    const foreign = new Map<string, number>();
    for (const acc of cash.accounts) {
      const cur = (acc.currency ?? '').trim().toUpperCase();
      if (!cur || cur === base.toUpperCase()) total += +acc.bookBalance;
      else foreign.set(cur, (foreign.get(cur) ?? 0) + +acc.bookBalance);
    }
    return {
      total,
      foreign: [...foreign.entries()]
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([currency, amount]) => ({ currency, amount })),
    };
  });

  // ── Derived: trend max for CSS bar scaling ───────────────────────────────────
  readonly revenueTrendMax = computed(() => {
    const pts = this.revenueTrend()?.points ?? [];
    if (pts.length === 0) return 1;
    return Math.max(...pts.map((p) => +p.value), 1);
  });

  readonly netProfitTrendMax = computed(() => {
    const pts = this.netProfitTrend()?.points ?? [];
    if (pts.length === 0) return 1;
    const abs = pts.map((p) => Math.abs(+p.value));
    return Math.max(...abs, 1);
  });

  // ── Export state ──────────────────────────────────────────────────────────────
  readonly exporting = signal(false);
  readonly exportFormat = signal<'PDF' | 'XLSX' | 'CSV'>('PDF');
  readonly exportError = signal<string | null>(null);

  constructor() {
    this.loadCompanies();
  }

  // ── Bootstrap ────────────────────────────────────────────────────────────────

  private defaultFrom(): string {
    const d = new Date();
    d.setDate(1);
    return d.toISOString().slice(0, 10);
  }

  private defaultTo(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private loadCompanies(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
              this.loadBranches(list[0].uid);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  /**
   * The Branch picker offers only branches the caller may filter to: the dashboard refuses a branch
   * the caller is not assigned to (BranchReadGuard), so listing every branch offered choices that
   * always failed. The company list supplies the numeric id the endpoint takes; GET
   * /auth/my-branches (self-scoped) says which of them are the caller's. Root is exempt from the
   * assignment check server-side, so root keeps the full list — the ReportFilterOptionsService rule.
   */
  private loadBranches(companyUid: string): void {
    this.branchState.set('loading');
    const assigned$ = this.session.user()?.isRoot === true
      ? of(null)
      : this.auth.myBranches().pipe(
          map((mine) => new Set(mine.filter((b) => b.companyUid === companyUid).map((b) => b.branchUid))),
          // Unknown assignments → offer no single branch; "All branches" still works.
          catchError(() => of(new Set<string>())),
        );
    forkJoin([this.branchService.list(companyUid), assigned$]).subscribe({
      next: ([list, assigned]) => {
        this.branches.set(assigned === null ? list : list.filter((b) => assigned.has(b.uid)));
        this.branchState.set('idle');
        // Default to "All branches" (empty) so the sales-by-branch panel shows
        // the full per-branch breakdown on first load.
        this.selectedBranchId.set('');
        if (this.selectedCompanyId()) {
          this.loadDashboard();
        }
      },
      error: () => this.branchState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedBranchId.set('');
    this.branches.set([]);
    if (!id) return;
    const company = this.companies().find((c) => c.id === id);
    if (company) this.loadBranches(company.uid);
  }

  onBranchChange(id: string): void {
    this.selectedBranchId.set(id);
    // Reload for any value — including empty ("All branches").
    if (this.selectedCompanyId()) this.loadDashboard();
  }

  applyDates(): void {
    if (this.selectedCompanyId()) this.loadDashboard();
  }

  // ── Dashboard fetch (composite) ───────────────────────────────────────────────

  loadDashboard(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    // Reset all panels to loading
    this.financeState.set('loading');
    this.workingCapitalState.set('loading');
    this.inventoryState.set('loading');
    this.crmState.set('loading');
    this.revenueTrendState.set('loading');
    this.netProfitTrendState.set('loading');
    this.salesByBranchState.set('loading');
    this.finance.set(null);
    this.workingCapital.set(null);
    this.inventory.set(null);
    this.crm.set(null);
    this.revenueTrend.set(null);
    this.netProfitTrend.set(null);
    this.salesByBranch.set(null);
    this.health.set([]);
    this.header.set(null);
    this.branchRefusal.set(null);

    const branchId = this.selectedBranchId() || undefined;
    const from = this.fromDate() || undefined;
    const to = this.toDate() || undefined;

    this.dashboardService.getDashboard(companyId, from, to, branchId).subscribe({
      next: (dto: DashboardDto) => this.applyDto(dto),
      error: (err: unknown) => {
        if (branchId && err instanceof HttpErrorResponse && err.status === 403) {
          this.branchRefusal.set(serverMessage(
            err,
            "You are not assigned to that branch. Choose a branch you work in, or choose All branches.",
          ));
        }
        this.applyGlobalError(err);
      },
    });
  }

  private applyDto(dto: DashboardDto): void {
    this.health.set(dto.health ?? []);
    this.header.set(dto.header ?? null);

    // Finance panel
    if (dto.finance !== null && dto.finance !== undefined) {
      this.finance.set(dto.finance);
      this.financeState.set('idle');
    } else if (!this.canFinance()) {
      this.financeState.set('forbidden');
    } else {
      this.financeState.set('idle'); // empty state — no data yet
    }

    // Working capital panel
    if (dto.workingCapital !== null && dto.workingCapital !== undefined) {
      this.workingCapital.set(dto.workingCapital);
      this.workingCapitalState.set('idle');
    } else if (!this.canFinance()) {
      this.workingCapitalState.set('forbidden');
    } else {
      this.workingCapitalState.set('idle');
    }

    // Inventory panel
    if (dto.inventory !== null && dto.inventory !== undefined) {
      this.inventory.set(dto.inventory);
      this.inventoryState.set('idle');
    } else if (!this.canOps()) {
      this.inventoryState.set('forbidden');
    } else {
      this.inventoryState.set('idle');
    }

    // CRM panel
    if (dto.crm !== null && dto.crm !== undefined) {
      this.crm.set(dto.crm);
      this.crmState.set('idle');
    } else if (!this.canCrm()) {
      this.crmState.set('forbidden');
    } else {
      this.crmState.set('idle');
    }

    // Trend panels
    if (dto.revenueTrend !== null && dto.revenueTrend !== undefined) {
      this.revenueTrend.set(dto.revenueTrend);
      this.revenueTrendState.set('idle');
    } else if (!this.canFinance()) {
      this.revenueTrendState.set('forbidden');
    } else {
      this.revenueTrendState.set('idle');
    }

    if (dto.netProfitTrend !== null && dto.netProfitTrend !== undefined) {
      this.netProfitTrend.set(dto.netProfitTrend);
      this.netProfitTrendState.set('idle');
    } else if (!this.canFinance()) {
      this.netProfitTrendState.set('forbidden');
    } else {
      this.netProfitTrendState.set('idle');
    }

    // Sales by Branch panel — gated by BI.FINANCE.VIEW (same as revenue panels)
    if (dto.salesByBranch !== null && dto.salesByBranch !== undefined) {
      this.salesByBranch.set(dto.salesByBranch);
      this.salesByBranchState.set('idle');
    } else if (!this.canFinance()) {
      this.salesByBranchState.set('forbidden');
    } else {
      this.salesByBranchState.set('idle');
    }
  }

  /**
   * On a top-level 403 (user lacks BI.VIEW entirely — guard should have caught this,
   * but belt-and-braces), mark all panels forbidden.
   * On any other error mark all panels error.
   */
  private applyGlobalError(err: unknown): void {
    const state: LoadState = err instanceof HttpErrorResponse && err.status === 403
      ? 'forbidden'
      : 'error';
    this.financeState.set(state);
    this.workingCapitalState.set(state);
    this.inventoryState.set(state);
    this.crmState.set(state);
    this.revenueTrendState.set(state);
    this.netProfitTrendState.set(state);
    this.salesByBranchState.set(state);
  }

  // ── Health chip helper ────────────────────────────────────────────────────────

  healthPrefix(ties: boolean): string {
    return ties ? '[OK]' : '[!]';
  }

  // ── Trend bar width (%) ───────────────────────────────────────────────────────

  pipelineMax(stages: { totalValueAmount: string }[]): number {
    if (!stages || stages.length === 0) return 1;
    return Math.max(...stages.map((s) => +s.totalValueAmount), 1);
  }

  trendBarWidth(value: string, max: number): number {
    const v = +value;
    if (max === 0) return 0;
    const pct = (Math.abs(v) / max) * 100;
    return Math.min(pct, 100);
  }

  // ── Export ────────────────────────────────────────────────────────────────────

  exportDashboard(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId || this.exporting()) return;
    this.exporting.set(true);
    this.exportError.set(null);
    const branchId = this.selectedBranchId() || undefined;
    const from = this.fromDate() || undefined;
    const to = this.toDate() || undefined;
    const fmt = this.exportFormat();
    this.dashboardService.exportDashboard(companyId, fmt, from, to, branchId).subscribe({
      next: (blob) => {
        downloadBlob(blob, `dashboard_${from ?? ''}_${to ?? ''}.${fmt.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: (err: unknown) => {
        this.exporting.set(false);
        // A blob error body is not the JSON envelope — choose the wording by status, never echo it.
        const status = err instanceof HttpErrorResponse ? err.status : 0;
        this.exportError.set(
          status === 401 || status === 403
            ? "You don't have permission to export the dashboard."
            : status >= 400 && status < 500
              ? 'The dashboard could not be exported with these filters. Check the dates and try again.'
              : 'Could not export the dashboard. Please try again.',
        );
      },
    });
  }
}
