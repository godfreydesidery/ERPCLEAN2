import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CrmService } from './crm.service';
import {
  CreatePipelineStageRequest,
  PipelineStageDto,
  UpdatePipelineStageRequest,
} from './models/crm.model';

/**
 * Pipeline stage settings screen (admin). Company scope, inline create + inline edit.
 * Route: /admin/crm/settings/pipeline-stages — gated CRM.STAGE.MANAGE.
 * List is NOT paginated (bare array, ordered by displayOrder).
 */
@Component({
  selector: 'app-pipeline-stage-list',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './pipeline-stage-list.component.html',
  styleUrl: './pipeline-stage-list.component.scss',
})
export class PipelineStageListComponent {
  private readonly crmService = inject(CrmService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<PipelineStageDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly rowBusyUid = signal<string | null>(null);

  // ── Create form ────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newName = signal('');
  readonly newDisplayOrder = signal('10');
  readonly newDefaultProbability = signal('50');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Inline edit ────────────────────────────────────────────────────────────
  readonly editingUid = signal<string | null>(null);
  readonly editName = signal('');
  readonly editDisplayOrder = signal('');
  readonly editDefaultProbability = signal('');
  readonly editActive = signal(true);
  readonly editSaving = signal(false);
  readonly editError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('CRM.STAGE.MANAGE'));
  readonly canView = computed(() => this.session.hasPermission('CRM.OPPORTUNITY.VIEW'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

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
    this.crmService.listPipelineStages(companyId).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.state.set('idle');
      },
      error: (err: unknown) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Create ─────────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.newName.set('');
    this.newDisplayOrder.set('10');
    this.newDefaultProbability.set('50');
  }

  create(): void {
    const name = this.newName().trim();
    if (!name) { this.formError.set('Stage name is required.'); return; }
    // type="number" stores a JS number in the signal; coerce before .trim() to avoid crash.
    const order = String(this.newDisplayOrder() ?? '').trim();
    if (!order || isNaN(+order)) { this.formError.set('Display order must be a number.'); return; }
    const prob = String(this.newDefaultProbability() ?? '').trim();
    if (!prob || isNaN(+prob) || +prob < 0 || +prob > 100) {
      this.formError.set('Default probability must be 0–100.');
      return;
    }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve selected company.'); return; }

    this.saving.set(true);
    this.formError.set(null);
    const request: CreatePipelineStageRequest = {
      companyId: company.id,
      name,
      displayOrder: order,
      defaultProbability: prob,
    };
    this.crmService.createPipelineStage(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Stage created', created.name);
        this.load();
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create stage.'));
        this.saving.set(false);
      },
    });
  }

  // ── Inline edit ────────────────────────────────────────────────────────────

  startEdit(stage: PipelineStageDto): void {
    this.editingUid.set(stage.uid);
    this.editName.set(stage.name);
    this.editDisplayOrder.set(stage.displayOrder);
    this.editDefaultProbability.set(stage.defaultProbability);
    this.editActive.set(stage.active);
    this.editError.set(null);
  }

  cancelEdit(): void {
    this.editingUid.set(null);
    this.editError.set(null);
  }

  saveEdit(stage: PipelineStageDto): void {
    const name = this.editName().trim();
    if (!name) { this.editError.set('Stage name is required.'); return; }
    // type="number" stores a JS number in the signal; coerce before .trim() to avoid crash.
    const order = String(this.editDisplayOrder() ?? '').trim();
    if (!order || isNaN(+order)) { this.editError.set('Display order must be a number.'); return; }
    const prob = String(this.editDefaultProbability() ?? '').trim();
    if (!prob || isNaN(+prob) || +prob < 0 || +prob > 100) {
      this.editError.set('Default probability must be 0–100.');
      return;
    }

    this.editSaving.set(true);
    this.editError.set(null);
    const request: UpdatePipelineStageRequest = {
      name,
      displayOrder: order,
      defaultProbability: prob,
      active: this.editActive(),
    };
    this.crmService.updatePipelineStage(stage.uid, request).subscribe({
      next: (updated) => {
        this.rows.update((list) => list.map((s) => s.uid === updated.uid ? updated : s));
        this.editSaving.set(false);
        this.editingUid.set(null);
        this.alerts.success('Stage updated', updated.name);
      },
      error: (err: unknown) => {
        this.editError.set(this.messageFrom(err, 'Could not update stage.'));
        this.editSaving.set(false);
      },
    });
  }

  // ── Deactivate ─────────────────────────────────────────────────────────────

  deactivate(stage: PipelineStageDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(stage.uid);
    this.crmService.deactivatePipelineStage(stage.uid).subscribe({
      next: () => {
        this.rowBusyUid.set(null);
        this.alerts.success('Stage deactivated', stage.name);
        this.load();
      },
      error: () => this.rowBusyUid.set(null),
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
