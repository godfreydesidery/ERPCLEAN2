import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchService } from '../branch/branch.service';
import { Company } from '../models/company.model';
import { Branch } from '../models/branch.model';
import { DashboardService } from './dashboard.service';
import {
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

  // ── Permissions ───────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('BI.VIEW'));
  readonly canFinance = computed(() => this.session.hasPermission('BI.FINANCE.VIEW'));
  readonly canOps = computed(() => this.session.hasPermission('BI.OPS.VIEW'));
  readonly canCrm = computed(() => this.session.hasPermission('BI.CRM.VIEW'));
  readonly canExport = computed(() => this.session.hasPermission('BI.EXPORT'));

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

  private loadBranches(companyUid: string): void {
    this.branchState.set('loading');
    this.branchService.list(companyUid).subscribe({
      next: (list) => {
        this.branches.set(list);
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

    const branchId = this.selectedBranchId() || undefined;
    const from = this.fromDate() || undefined;
    const to = this.toDate() || undefined;

    this.dashboardService.getDashboard(companyId, from, to, branchId).subscribe({
      next: (dto: DashboardDto) => this.applyDto(dto),
      error: (err: unknown) => this.applyGlobalError(err),
    });
  }

  private applyDto(dto: DashboardDto): void {
    this.health.set(dto.health ?? []);

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
    const branchId = this.selectedBranchId() || undefined;
    const from = this.fromDate() || undefined;
    const to = this.toDate() || undefined;
    const fmt = this.exportFormat();
    this.dashboardService.exportDashboard(companyId, fmt, from, to, branchId).subscribe({
      next: (blob) => {
        const ext = fmt.toLowerCase();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `dashboard.${ext}`;
        a.click();
        URL.revokeObjectURL(url);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }
}
