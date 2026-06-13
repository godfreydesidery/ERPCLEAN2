import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Company } from '../models/company.model';
import { Branch } from '../models/branch.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchService } from '../branch/branch.service';
import { CrmService } from './crm.service';
import { CrmKpiDto, ForecastDto, PipelineSummaryDto } from './models/crm.model';
import { SessionStore } from '../../../core/auth/session.store';

type LoadState = 'loading' | 'idle' | 'error' | 'forbidden';

/**
 * Pipeline dashboard: board (summary by stage), forecast, and win-rate KPIs.
 * Route: /admin/crm/pipeline — gated CRM.PIPELINE.VIEW.
 * All 3 report endpoints require companyId + branchId; forecast/kpis also need from+to dates.
 */
@Component({
  selector: 'app-pipeline-dashboard',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './pipeline-dashboard.component.html',
  styleUrl: './pipeline-dashboard.component.scss',
})
export class PipelineDashboardComponent {
  private readonly crmService = inject(CrmService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Branch context ─────────────────────────────────────────────────────────
  readonly branches = signal<Branch[]>([]);
  readonly selectedBranchId = signal('');
  readonly branchState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Date range (for forecast + KPIs) ─────────────────────────────────────
  readonly fromDate = signal<string>(this.defaultFrom());
  readonly toDate = signal<string>(this.defaultTo());

  // ── Pipeline summary ───────────────────────────────────────────────────────
  readonly summary = signal<PipelineSummaryDto | null>(null);
  readonly summaryState = signal<LoadState>('idle');

  // ── Forecast ───────────────────────────────────────────────────────────────
  readonly forecast = signal<ForecastDto | null>(null);
  readonly forecastState = signal<LoadState>('idle');

  // ── KPIs ───────────────────────────────────────────────────────────────────
  readonly kpis = signal<CrmKpiDto | null>(null);
  readonly kpisState = signal<LoadState>('idle');

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('CRM.PIPELINE.VIEW'));

  constructor() {
    this.loadCompanies();
  }

  private defaultFrom(): string {
    const d = new Date();
    d.setDate(1);
    return d.toISOString().slice(0, 10);
  }

  private defaultTo(): string {
    const d = new Date();
    d.setMonth(d.getMonth() + 3);
    return d.toISOString().slice(0, 10);
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
        if (list.length > 0) {
          this.selectedBranchId.set(list[0].id);
          this.runAllReports();
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
    if (id) this.runAllReports();
  }

  runAllReports(): void {
    const companyId = this.selectedCompanyId();
    const branchId = this.selectedBranchId();
    if (!companyId || !branchId) return;
    this.loadSummary(companyId, branchId);
    this.loadForecast(companyId, branchId);
    this.loadKpis(companyId, branchId);
  }

  private loadSummary(companyId: string, branchId: string): void {
    this.summaryState.set('loading');
    this.summary.set(null);
    this.crmService.getPipelineSummary(companyId, branchId).subscribe({
      next: (dto) => {
        this.summary.set(dto);
        this.summaryState.set('idle');
      },
      error: (err: unknown) =>
        this.summaryState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  private loadForecast(companyId: string, branchId: string): void {
    const from = this.fromDate();
    const to = this.toDate();
    if (!from || !to) return;
    this.forecastState.set('loading');
    this.forecast.set(null);
    this.crmService.getForecast(companyId, branchId, from, to).subscribe({
      next: (dto) => {
        this.forecast.set(dto);
        this.forecastState.set('idle');
      },
      error: (err: unknown) =>
        this.forecastState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  private loadKpis(companyId: string, branchId: string): void {
    const from = this.fromDate();
    const to = this.toDate();
    if (!from || !to) return;
    this.kpisState.set('loading');
    this.kpis.set(null);
    this.crmService.getKpis(companyId, branchId, from, to).subscribe({
      next: (dto) => {
        this.kpis.set(dto);
        this.kpisState.set('idle');
      },
      error: (err: unknown) =>
        this.kpisState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  applyDates(): void {
    const companyId = this.selectedCompanyId();
    const branchId = this.selectedBranchId();
    if (!companyId || !branchId) return;
    this.loadForecast(companyId, branchId);
    this.loadKpis(companyId, branchId);
  }
}
