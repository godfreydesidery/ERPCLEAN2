import { DecimalPipe } from '@angular/common';
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
import { FixedAssetsService } from './fixed-assets.service';
import type { FixedAssetPage } from './fixed-assets.service';
import { FixedAssetDto, FixedAssetStatus } from './models/fixed-assets.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Fixed Asset register list. Route: /admin/fixed-assets
 * Company-scoped + optional status filter. Link to create route for new assets.
 */
@Component({
  selector: 'app-fixed-asset-list',
  imports: [FormsModule, RouterLink, DecimalPipe, PaginatorComponent],
  templateUrl: './fixed-asset-list.component.html',
  styleUrl: './fixed-asset-list.component.scss',
})
export class FixedAssetListComponent {
  private readonly faService = inject(FixedAssetsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Filters ──────────────────────────────────────────────────────────────────
  readonly filterStatus = signal<FixedAssetStatus | ''>('');

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<FixedAssetDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);
  readonly canCreate = computed(() => this.session.hasPermission('FA.REGISTER.MANAGE'));

  readonly statusOptions: Array<{ value: FixedAssetStatus | ''; label: string }> = [
    { value: '', label: 'All statuses' },
    { value: 'DRAFT', label: 'Draft' },
    { value: 'IN_SERVICE', label: 'In Service' },
    { value: 'DISPOSED', label: 'Disposed' },
    { value: 'WRITTEN_OFF', label: 'Written Off' },
  ];

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const filterTrigger$ = toObservable(this.filterStatus).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    const companyTrigger$ = toObservable(this.selectedCompanyId).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(filterTrigger$, companyTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          const status = this.filterStatus() || undefined;
          return this.faService.list(companyId, page, DEFAULT_SIZE, status as FixedAssetStatus | undefined);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: FixedAssetPage) => {
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

  onStatusChange(status: string): void {
    this.filterStatus.set(status as FixedAssetStatus | '');
    this.load(0);
  }

  load(page: number): void {
    if (!this.selectedCompanyId()) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  statusBadgeClass(status: FixedAssetStatus): string {
    switch (status) {
      case 'IN_SERVICE': return 'text-bg-success';
      case 'DISPOSED': return 'text-bg-secondary';
      case 'WRITTEN_OFF': return 'text-bg-dark';
      default: return 'text-bg-warning'; // DRAFT
    }
  }
}
