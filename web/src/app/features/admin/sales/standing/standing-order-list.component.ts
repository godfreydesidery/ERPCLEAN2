import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { StandingOrderDto, StandingStatus } from './standing-order.model';
import { StandingOrderService } from './standing-order.service';
import type { StandingOrderPage } from './standing-order.service';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Standing-order list screen. Company scope, status badge, link to create.
 * Route: /admin/standing-orders
 * Guard: SALES.STANDING.VIEW
 */
@Component({
  selector: 'app-standing-order-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './standing-order-list.component.html',
  styleUrl: './standing-order-list.component.scss',
})
export class StandingOrderListComponent {
  private readonly standingService = inject(StandingOrderService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<StandingOrderDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);
  readonly canCreate = computed(() => this.session.hasPermission('SALES.STANDING.CREATE'));
  readonly canManage = computed(() => this.session.hasPermission('SALES.STANDING.MANAGE'));

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const companyChangeTrigger$ = toObservable(this.selectedCompanyId).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(companyChangeTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.standingService.list(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: StandingOrderPage) => {
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

  // ── Display helpers ──────────────────────────────────────────────────────────

  statusBadgeClass(status: StandingStatus): string {
    switch (status) {
      case 'ACTIVE': return 'text-bg-success';
      case 'PAUSED': return 'text-bg-warning';
      case 'CANCELLED': return 'text-bg-danger';
      default: return 'text-bg-secondary';
    }
  }

  orderLabel(so: StandingOrderDto): string {
    return so.orderNumber ?? 'DRAFT';
  }
}
