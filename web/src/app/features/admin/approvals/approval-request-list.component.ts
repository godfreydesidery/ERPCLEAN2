import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe, SlicePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ApprovalsService } from './approvals.service';
import type { ApprovalRequestPage } from './approvals.service';
import { ApprovalRequestDto, ApprovalRequestStatus } from './models/approvals.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import { documentTypeLabel } from './document-type.util';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Approval requests list with company + status filter.
 * Route: /admin/approvals/requests
 */
@Component({
  selector: 'app-approval-request-list',
  imports: [FormsModule, RouterLink, DecimalPipe, SlicePipe, PaginatorComponent],
  templateUrl: './approval-request-list.component.html',
  styleUrl: './approval-request-list.component.scss',
})
export class ApprovalRequestListComponent {
  private readonly approvalsService = inject(ApprovalsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Filters ──────────────────────────────────────────────────────────────────
  readonly filterStatus = signal<ApprovalRequestStatus | ''>('');

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<ApprovalRequestDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly statusOptions: Array<{ value: ApprovalRequestStatus | ''; label: string }> = [
    { value: '', label: 'All statuses' },
    { value: 'PENDING', label: 'Pending' },
    { value: 'APPROVED', label: 'Approved' },
    { value: 'REJECTED', label: 'Rejected' },
    { value: 'RECALLED', label: 'Recalled' },
    { value: 'CANCELLED', label: 'Cancelled' },
  ];

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const filterTrigger$ = toObservable(this.selectedCompanyId).pipe(
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
          return this.approvalsService.listRequests(companyId, status, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: ApprovalRequestPage) => {
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

  applyFilter(): void {
    if (this.selectedCompanyId()) this.load(0);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  docTypeLabel(documentType: string): string {
    return documentTypeLabel(documentType);
  }
}
