import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  CreateDimensionValueRequest,
  DimensionDto,
  DimensionValueDto,
} from './models/cost-centre.model';
import { CostCentreService } from './cost-centre.service';
import type { DimensionValuePage } from './cost-centre.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Dimension-value list screen (paginated). Scoped to a company + a dimension type.
 * Inline create form for new values; deactivate/activate/delete per row.
 * Route: /admin/cost-centre/values
 */
@Component({
  selector: 'app-dimension-value-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './dimension-value-list.component.html',
  styleUrl: './dimension-value-list.component.scss',
})
export class DimensionValueListComponent {
  private readonly service = inject(CostCentreService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Dimension type selector ────────────────────────────────────────────────
  readonly dimensions = signal<DimensionDto[]>([]);
  readonly selectedDimensionUid = signal('');
  readonly dimensionsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<DimensionValueDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Create form ────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newCode = signal('');
  readonly newName = signal('');
  readonly newParentUid = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Row actions ────────────────────────────────────────────────────────────
  readonly rowBusyUid = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('COSTING.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const dimChangeTrigger$ = toObservable(this.selectedDimensionUid).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(dimChangeTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const dimUid = this.selectedDimensionUid();
          if (!dimUid) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.service.listValues(dimUid, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: DimensionValuePage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

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
              this.loadDimensions(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadDimensions(companyId: string): void {
    this.dimensionsState.set('loading');
    this.service.listDimensions(companyId).subscribe({
      next: (list) => {
        this.dimensions.set(list);
        this.dimensionsState.set('idle');
        const first = list[0];
        if (first) {
          this.selectedDimensionUid.set(first.uid);
          this.load(0);
        }
      },
      error: () => this.dimensionsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedDimensionUid.set('');
    this.rows.set([]);
    this.dimensions.set([]);
    if (id) this.loadDimensions(id);
  }

  onDimensionChange(uid: string): void {
    this.selectedDimensionUid.set(uid);
    if (uid) this.load(0);
  }

  load(page: number): void {
    const dimUid = this.selectedDimensionUid();
    if (!dimUid) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  // ── Create form ────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.newCode.set('');
    this.newName.set('');
    this.newParentUid.set('');
  }

  create(): void {
    const dimUid = this.selectedDimensionUid();
    const code = this.newCode().trim();
    const name = this.newName().trim();

    if (!dimUid) { this.formError.set('Select a dimension type first.'); return; }
    if (!code) { this.formError.set('Code is required.'); return; }
    if (!name) { this.formError.set('Name is required.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateDimensionValueRequest = {
      dimensionUid: dimUid,
      code,
      name,
      parentUid: this.newParentUid().trim() || null,
    };

    this.service.createValue(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Value created', `${created.code} — ${created.name}`);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create dimension value.'));
        this.saving.set(false);
      },
    });
  }

  // ── Row actions ────────────────────────────────────────────────────────────

  deactivate(val: DimensionValueDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(val.uid);
    this.service.deactivateValue(val.uid).subscribe({
      next: (updated) => {
        this.rows.update((list) => list.map((v) => (v.uid === updated.uid ? updated : v)));
        this.rowBusyUid.set(null);
        this.alerts.success('Value deactivated', updated.name);
      },
      error: (err) => {
        this.rowBusyUid.set(null);
        this.alerts.error('Deactivate failed', this.messageFrom(err, 'Could not deactivate.'));
      },
    });
  }

  activate(val: DimensionValueDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(val.uid);
    this.service.activateValue(val.uid).subscribe({
      next: (updated) => {
        this.rows.update((list) => list.map((v) => (v.uid === updated.uid ? updated : v)));
        this.rowBusyUid.set(null);
        this.alerts.success('Value activated', updated.name);
      },
      error: (err) => {
        this.rowBusyUid.set(null);
        this.alerts.error('Activate failed', this.messageFrom(err, 'Could not activate.'));
      },
    });
  }

  /**
   * Hard delete. Service rejects if the value has any journal postings (BR-CC-05).
   * The template should guide users to deactivate instead; the rejection error is surfaced here.
   */
  deleteValue(val: DimensionValueDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(val.uid);
    this.service.deleteValue(val.uid).subscribe({
      next: () => {
        this.rowBusyUid.set(null);
        this.alerts.success('Value deleted', val.name);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.rowBusyUid.set(null);
        this.alerts.error(
          'Delete rejected',
          this.messageFrom(err, 'Cannot delete: this value has journal postings. Use Deactivate instead.'),
        );
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  statusBadgeClass(status: string): string {
    switch (status) {
      case 'ACTIVE': return 'text-bg-success';
      case 'INACTIVE': return 'text-bg-secondary';
      case 'ARCHIVED': return 'text-bg-dark';
      default: return 'text-bg-secondary';
    }
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
