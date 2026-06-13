import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { WipReconciliationDto } from './models/manufacturing.model';
import { ManufacturingService } from './manufacturing.service';

type LoadState = 'loading' | 'idle' | 'error' | 'forbidden';

/**
 * WIP Reconciliation report screen. Company-scoped.
 * Compares sum of open-WO WIP (RELEASED/IN_PROGRESS/COMPLETED) vs WIP_INVENTORY GL 1320.
 * ties=false is rendered as a finance-grade defect.
 * Route: /admin/manufacturing/wip-reconciliation
 * Perm: MANUFACTURING.VIEW
 */
@Component({
  selector: 'app-wip-reconciliation',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './wip-reconciliation.component.html',
  styleUrl: './wip-reconciliation.component.scss',
})
export class WipReconciliationComponent {
  private readonly mfgService = inject(ManufacturingService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  readonly report = signal<WipReconciliationDto | null>(null);
  readonly state = signal<LoadState>('idle');

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
    this.mfgService.getWipReconciliation(companyId).subscribe({
      next: (r) => {
        this.report.set(r);
        this.state.set('idle');
        if (!r.ties) {
          this.alerts.error(
            'WIP Reconciliation Defect',
            `Computed WIP (${r.computed}) does not match GL balance (${r.expected}). Finance review required.`,
          );
        }
      },
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 403) {
          this.state.set('forbidden');
        } else {
          this.state.set('error');
        }
      },
    });
  }
}
