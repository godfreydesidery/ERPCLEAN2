import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import {
  CreateUnitOfMeasureRequest,
  UnitOfMeasureDto,
  UpdateUnitOfMeasureRequest,
} from '../models/product.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from './product.service';

/**
 * Units of Measure admin screen. Lists all units for the selected company.
 * Supports create (inline: code + name), edit name (inline), archive, restore.
 * Gated by UOM.VIEW; management actions require UOM.MANAGE.
 * Mirrors price-list-list.component exactly.
 */
@Component({
  selector: 'app-units-of-measure-list',
  imports: [FormsModule],
  templateUrl: './units-of-measure-list.component.html',
  styleUrl: './units-of-measure-list.component.scss',
})
export class UnitsOfMeasureListComponent {
  private readonly productService = inject(ProductService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<UnitOfMeasureDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── Create form ────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newCode = signal('');
  readonly newName = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Inline edit ────────────────────────────────────────────────────────────
  readonly editingUid = signal<string | null>(null);
  readonly editName = signal('');
  readonly editSaving = signal(false);
  readonly editError = signal<string | null>(null);
  readonly rowBusyUid = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('UOM.MANAGE'));
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
    this.productService.listUnits(companyId).subscribe({
      next: ({ rows }) => {
        this.rows.set(rows);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Create ─────────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
  }

  create(): void {
    const companyId = this.selectedCompanyId();
    const code = this.newCode().trim();
    const name = this.newName().trim();
    if (!code || !name) {
      this.formError.set('Code and name are required.');
      return;
    }
    const company = this.companies().find((c) => c.id === companyId);
    if (!company) {
      this.formError.set('Could not resolve selected company.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    const request: CreateUnitOfMeasureRequest = { companyUid: company.uid, code, name };
    this.productService.createUnit(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.newCode.set('');
        this.newName.set('');
        this.showCreateForm.set(false);
        this.alerts.success('Unit created', created.name);
        this.load();
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not create unit.'));
        this.saving.set(false);
      },
    });
  }

  // ── Inline edit ────────────────────────────────────────────────────────────

  startEdit(u: UnitOfMeasureDto): void {
    this.editingUid.set(u.uid);
    this.editName.set(u.name);
    this.editError.set(null);
  }

  cancelEdit(): void {
    this.editingUid.set(null);
    this.editError.set(null);
  }

  saveEdit(u: UnitOfMeasureDto): void {
    const name = this.editName().trim();
    if (!name) {
      this.editError.set('Name is required.');
      return;
    }
    this.editSaving.set(true);
    this.editError.set(null);
    const request: UpdateUnitOfMeasureRequest = { name };
    this.productService.updateUnit(u.uid, request).subscribe({
      next: (updated) => {
        this.editSaving.set(false);
        this.editingUid.set(null);
        this.rows.update((rows) => rows.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Unit updated', updated.name);
      },
      error: (err) => {
        this.editError.set(this.messageFrom(err, 'Could not update unit.'));
        this.editSaving.set(false);
      },
    });
  }

  // ── Archive / Restore ──────────────────────────────────────────────────────

  archive(u: UnitOfMeasureDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(u.uid);
    this.productService.archiveUnit(u.uid).subscribe({
      next: (updated) => {
        this.rowBusyUid.set(null);
        this.rows.update((rows) => rows.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Unit archived', updated.name);
      },
      error: () => this.rowBusyUid.set(null),
    });
  }

  restore(u: UnitOfMeasureDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(u.uid);
    this.productService.restoreUnit(u.uid).subscribe({
      next: (updated) => {
        this.rowBusyUid.set(null);
        this.rows.update((rows) => rows.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Unit restored', updated.name);
      },
      error: () => this.rowBusyUid.set(null),
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
