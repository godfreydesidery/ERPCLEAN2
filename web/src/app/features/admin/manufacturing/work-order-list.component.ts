import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
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
import { ManufacturingService } from './manufacturing.service';
import type { WorkOrderPage } from './manufacturing.service';
import {
  CreateWorkOrderRequest,
  WorkOrderDto,
  WorkOrderStatus,
} from './models/manufacturing.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger {
  page: number;
}

/**
 * Work Order list screen. Company-scoped, status filter, inline create.
 * Route: /admin/work-orders
 */
@Component({
  selector: 'app-work-order-list',
  imports: [FormsModule, RouterLink, DecimalPipe, PaginatorComponent],
  templateUrl: './work-order-list.component.html',
  styleUrl: './work-order-list.component.scss',
})
export class WorkOrderListComponent {
  private readonly mfgService = inject(ManufacturingService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ───────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ────────────────────────────────────────────────────────────
  readonly rows = signal<WorkOrderDto[]>([]);
  readonly meta = signal<PageMeta>({
    page: 0,
    size: DEFAULT_SIZE,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
  });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);
  readonly statusFilter = signal<WorkOrderStatus | ''>('');

  // ── Create form ───────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newProductUid = signal('');
  readonly newProductSearch = signal('');
  readonly newPlannedQty = signal('');
  readonly newBranchUid = signal('');
  readonly newBomUid = signal('');
  readonly newPlannedDate = signal('');
  readonly newNotes = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('WORKORDER.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly statusOptions: Array<{ value: WorkOrderStatus | ''; label: string }> = [
    { value: '', label: 'All statuses' },
    { value: 'PLANNED', label: 'Planned' },
    { value: 'RELEASED', label: 'Released' },
    { value: 'IN_PROGRESS', label: 'In Progress' },
    { value: 'COMPLETED', label: 'Completed' },
    { value: 'CLOSED', label: 'Closed' },
    { value: 'CANCELLED', label: 'Cancelled' },
  ];

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const filterTrigger$ = toObservable(this.statusFilter).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(filterTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.mfgService.list(companyId, page, DEFAULT_SIZE, this.statusFilter());
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: WorkOrderPage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(
            err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
          ),
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
              this.load(0);
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
    if (id) this.load(0);
  }

  onStatusChange(s: string): void {
    this.statusFilter.set(s as WorkOrderStatus | '');
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    if (this.currentPage() > 0) this.load(this.currentPage() - 1);
  }
  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  // ── Create form ────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.newProductUid.set('');
    this.newProductSearch.set('');
    this.newPlannedQty.set('');
    this.newBranchUid.set('');
    this.newBomUid.set('');
    this.newPlannedDate.set('');
    this.newNotes.set('');
  }

  create(): void {
    const productUid = this.newProductUid().trim();
    const plannedQty = this.newPlannedQty().trim();
    const branchUid = this.newBranchUid().trim();

    if (!productUid) {
      this.formError.set('Finished product UID is required.');
      return;
    }
    if (!plannedQty || isNaN(Number(plannedQty)) || Number(plannedQty) <= 0) {
      this.formError.set('Planned quantity must be a positive number.');
      return;
    }
    if (!branchUid) {
      this.formError.set('Branch UID is required.');
      return;
    }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateWorkOrderRequest = {
      finishedProductUid: productUid,
      plannedQty,
      branchUid,
      bomUid: this.newBomUid().trim() || undefined,
      plannedDate: this.newPlannedDate().trim() || undefined,
      notes: this.newNotes().trim() || undefined,
    };

    this.mfgService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Work order created', created.woNumber);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create work order.'));
        this.saving.set(false);
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────

  statusBadgeClass(status: WorkOrderStatus): string {
    switch (status) {
      case 'PLANNED':
        return 'text-bg-warning';
      case 'RELEASED':
        return 'text-bg-info';
      case 'IN_PROGRESS':
        return 'text-bg-primary';
      case 'COMPLETED':
        return 'text-bg-success';
      case 'CLOSED':
        return 'text-bg-secondary';
      case 'CANCELLED':
        return 'text-bg-danger';
      default:
        return 'text-bg-secondary';
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
