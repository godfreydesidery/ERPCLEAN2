import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { DimensionDto } from './models/cost-centre.model';
import { CostCentreService } from './cost-centre.service';

/**
 * Dimension-type list screen (seeded per company, not created by the UI).
 * Shows all dimension slots for the selected company.
 * Allows toggling mandatory via COSTING.MANAGE.
 * Route: /admin/cost-centre/dimensions
 */
@Component({
  selector: 'app-dimension-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './dimension-list.component.html',
  styleUrl: './dimension-list.component.scss',
})
export class DimensionListComponent {
  private readonly service = inject(CostCentreService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<DimensionDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  // ── Row actions ────────────────────────────────────────────────────────────
  readonly rowBusyUid = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('COSTING.MANAGE'));

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
    this.service.listDimensions(companyId).subscribe({
      next: (list) => {
        this.rows.set(list);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  toggleMandatory(dim: DimensionDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(dim.uid);
    this.service.setMandatory(dim.uid, { mandatory: !dim.mandatory }).subscribe({
      next: (updated) => {
        this.rows.update((list) => list.map((d) => (d.uid === updated.uid ? updated : d)));
        this.rowBusyUid.set(null);
        this.alerts.success(
          `Dimension ${updated.mandatory ? 'mandatory' : 'optional'}`,
          updated.name,
        );
      },
      error: (err) => {
        this.rowBusyUid.set(null);
        this.alerts.error('Could not update dimension', this.messageFrom(err, 'Update failed.'));
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
