import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProjectsService } from './projects.service';
import { ProjectWipRowDto } from './models/projects.model';

/**
 * Cross-project WIP report screen (read-only).
 * Route: /admin/projects/wip-report
 * Gated on PROJECTS.COSTING.VIEW.
 */
@Component({
  selector: 'app-project-wip-report',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './project-wip-report.component.html',
  styleUrl: './project-wip-report.component.scss',
})
export class ProjectWipReportComponent {
  private readonly projectsService = inject(ProjectsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Report state ───────────────────────────────────────────────────────────
  readonly rows = signal<ProjectWipRowDto[]>([]);
  readonly reportState = signal<'idle' | 'loading' | 'error' | 'forbidden'>('idle');
  readonly loaded = signal(false);

  readonly canCosting = computed(() => this.session.hasPermission('PROJECTS.COSTING.VIEW'));
  readonly isEmpty = computed(() => this.loaded() && this.rows().length === 0);

  constructor() {
    this.loadCompanies();
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
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.rows.set([]);
    this.loaded.set(false);
  }

  loadReport(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.reportState.set('loading');
    this.loaded.set(false);
    this.projectsService.getWipReport(companyId).subscribe({
      next: (data) => {
        this.rows.set(data ?? []);
        this.reportState.set('idle');
        this.loaded.set(true);
      },
      error: (err: unknown) => {
        this.reportState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error');
      },
    });
  }

  // ── Computed totals ────────────────────────────────────────────────────────

  totalCostIncurred(): number {
    return this.rows().reduce((sum, r) => sum + +r.costIncurred, 0);
  }

  totalBilled(): number {
    return this.rows().reduce((sum, r) => sum + +r.billed, 0);
  }

  totalWip(): number {
    return this.rows().reduce((sum, r) => sum + +r.wip, 0);
  }
}
