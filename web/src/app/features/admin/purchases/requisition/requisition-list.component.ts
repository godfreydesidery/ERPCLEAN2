import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { merge, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { PurchaseRequisitionDto, RequisitionStatus } from './purchase-requisition.model';
import { PurchaseRequisitionService } from './purchase-requisition.service';

const DEFAULT_SIZE = 20;

interface LoadTrigger { status: string; page: number }

@Component({
  selector: 'app-requisition-list',
  imports: [FormsModule, RouterLink, DatePipe, PaginatorComponent],
  templateUrl: './requisition-list.component.html',
  styleUrl: './requisition-list.component.scss',
})
export class RequisitionListComponent {
  private readonly reqService = inject(PurchaseRequisitionService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<PurchaseRequisitionDto[]>([]);
  readonly meta = signal<PageMeta>({
    page: 0,
    size: DEFAULT_SIZE,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
  });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Filters ────────────────────────────────────────────────────────────────
  readonly statusFilter = signal('');

  readonly canCreate = computed(() => this.session.hasPermission('PURCHASE.REQUISITION.CREATE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly statusOptions: Array<{ value: string; label: string }> = [
    { value: '', label: 'All statuses' },
    { value: 'DRAFT', label: 'Draft' },
    { value: 'SUBMITTED', label: 'Submitted' },
    { value: 'APPROVED', label: 'Approved' },
    { value: 'REJECTED', label: 'Rejected' },
    { value: 'CONVERTED', label: 'Converted' },
    { value: 'CANCELLED', label: 'Cancelled' },
  ];

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    merge(this.immediateTrigger$)
      .pipe(
        switchMap(({ status, page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.reqService.list(
            companyId,
            status || undefined,
            page,
            DEFAULT_SIZE,
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err) =>
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

  onStatusChange(status: string): void {
    this.statusFilter.set(status);
    this.load(0);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ status: this.statusFilter(), page });
  }

  goToPage(page: number): void { this.load(page); }

  // ── Display helpers ────────────────────────────────────────────────────────

  reqLabel(r: PurchaseRequisitionDto): string {
    return r.requisitionNumber ?? 'DRAFT';
  }

  statusBadgeClass(status: RequisitionStatus): string {
    switch (status) {
      case 'SUBMITTED': return 'text-bg-primary';
      case 'APPROVED': return 'text-bg-success';
      case 'REJECTED': return 'text-bg-danger';
      case 'CONVERTED': return 'text-bg-info';
      case 'CANCELLED': return 'text-bg-secondary';
      default: return 'text-bg-warning'; // DRAFT
    }
  }
}
