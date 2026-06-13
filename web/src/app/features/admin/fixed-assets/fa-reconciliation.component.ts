import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { FixedAssetsService } from './fixed-assets.service';
import { FixedAssetReconciliationDto } from './models/fixed-assets.model';

/**
 * FA-to-GL reconciliation report. Route: /admin/fixed-assets/reconciliation
 * Shows two recon bars: cost and accum-dep. Flags ties=false in red.
 * Perm: FA.VIEW
 */
@Component({
  selector: 'app-fa-reconciliation',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './fa-reconciliation.component.html',
  styleUrl: './fa-reconciliation.component.scss',
})
export class FaReconciliationComponent {
  private readonly faService = inject(FixedAssetsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Report state ─────────────────────────────────────────────────────────────
  readonly report = signal<FixedAssetReconciliationDto | null>(null);
  readonly state = signal<'idle' | 'loading' | 'error'>('idle');

  readonly canView = computed(() => this.session.hasPermission('FA.VIEW'));

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
              this.load();
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
    if (id) this.load();
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.report.set(null);
    this.faService.getReconciliation(companyId).subscribe({
      next: (r) => { this.report.set(r); this.state.set('idle'); },
      error: () => this.state.set('error'),
    });
  }
}
