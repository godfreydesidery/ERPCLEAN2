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

  // ── Add Dimension form ────────────────────────────────────────────────────
  /** True when the company already has both custom slots (DIMENSION_3 + DIMENSION_4). */
  readonly customSlotsFull = computed(
    () => this.rows().filter((d) => !d.builtIn).length >= 2,
  );

  readonly newDimCode = signal('');
  readonly newDimName = signal('');
  readonly newDimDescription = signal('');
  readonly addDimSaving = signal(false);
  readonly addDimFormError = signal<string | null>(null);

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

  addDimension(): void {
    const code = this.newDimCode().trim();
    const name = this.newDimName().trim();
    if (!code || !name) {
      this.addDimFormError.set('Code and name are required.');
      return;
    }
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    this.addDimSaving.set(true);
    this.addDimFormError.set(null);

    const description = this.newDimDescription().trim() || undefined;
    this.service.createDimension({ companyId, code, name, description }).subscribe({
      next: (created) => {
        this.rows.update((list) => [...list, created]);
        this.newDimCode.set('');
        this.newDimName.set('');
        this.newDimDescription.set('');
        this.addDimSaving.set(false);
        this.alerts.success('Dimension added', created.name);
      },
      error: (err) => {
        this.addDimSaving.set(false);
        if (err instanceof HttpErrorResponse && err.status === 409) {
          this.addDimFormError.set('You can have at most 2 custom dimensions.');
        } else {
          this.addDimFormError.set(this.messageFrom(err, 'Could not add dimension.'));
        }
      },
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
