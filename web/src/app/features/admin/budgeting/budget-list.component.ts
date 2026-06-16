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
import { BudgetingService } from './budgeting.service';
import type { BudgetPage } from './budgeting.service';
import {
  BudgetDto,
  BudgetVersionStatus,
  CreateBudgetRequest,
} from './models/budgeting.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Budget list screen.
 * Route: /admin/budgets
 */
@Component({
  selector: 'app-budget-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './budget-list.component.html',
  styleUrl: './budget-list.component.scss',
})
export class BudgetListComponent {
  private readonly budgetingService = inject(BudgetingService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ───────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Filters ───────────────────────────────────────────────────────────────────
  readonly filterStatus = signal<BudgetVersionStatus | ''>('');

  // ── List state ────────────────────────────────────────────────────────────────
  readonly rows = signal<BudgetDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  // ── Create form ───────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly fName = signal('');
  readonly fFiscalYearUid = signal('');
  readonly fNotes = signal('');
  readonly fInitialLabel = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('BUDGETING.BUDGET.MANAGE'));

  readonly statusOptions: Array<{ value: BudgetVersionStatus | ''; label: string }> = [
    { value: '', label: 'All statuses' },
    { value: 'DRAFT', label: 'Draft' },
    { value: 'SUBMITTED', label: 'Submitted' },
    { value: 'APPROVED', label: 'Approved' },
    { value: 'REJECTED', label: 'Rejected' },
    { value: 'SUPERSEDED', label: 'Superseded' },
  ];

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const filterTrigger$ = toObservable(this.filterStatus).pipe(
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
          const status = this.filterStatus() || undefined;
          return this.budgetingService.list(companyId, page, DEFAULT_SIZE, undefined, undefined, status);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: BudgetPage) => {
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

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.fName.set('');
    this.fFiscalYearUid.set('');
    this.fNotes.set('');
    this.fInitialLabel.set('');
  }

  create(): void {
    const name = this.fName().trim();
    const fiscalYearUid = this.fFiscalYearUid().trim();

    if (!name) { this.formError.set('Budget name is required.'); return; }
    if (!fiscalYearUid) { this.formError.set('Fiscal Year UID is required.'); return; }

    const companyId = this.selectedCompanyId();
    if (!companyId) { this.formError.set('Select a company first.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateBudgetRequest = {
      companyId,
      name,
      fiscalYearUid,
      notes: this.fNotes().trim() || undefined,
      initialVersionLabel: this.fInitialLabel().trim() || undefined,
    };

    this.budgetingService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Budget created', created.budgetNumber ?? created.name);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create budget.'));
        this.saving.set(false);
      },
    });
  }

  latestStatus(row: BudgetDto): BudgetVersionStatus | null {
    if (!row.versions || row.versions.length === 0) return null;
    // prefer APPROVED, then latest by versionNo
    const approved = row.versions.find((v) => v.status === 'APPROVED');
    if (approved) return 'APPROVED';
    return row.versions.at(-1)!.status;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
