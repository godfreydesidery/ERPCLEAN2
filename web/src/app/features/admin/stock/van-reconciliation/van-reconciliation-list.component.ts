import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { merge, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { SessionStore } from '../../../../core/auth/session.store';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { VanReconciliationDto } from './van-reconciliation.model';
import { VanReconciliationService } from './van-reconciliation.service';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Van-Stock Reconciliation list (ADR-0051 D-8). Paged via <app-paginator> — the backend endpoint
 * is a plain Spring `Pageable` list scoped implicitly to the caller's company + active branch via
 * `RequestContext` (JWT + the `X-Branch-Uid` header the interceptor already attaches). No
 * companyId/branchId query params exist; switching the app's branch selector re-scopes the list.
 *
 * Note: the list DTO's `lines` are always empty (the backend only loads lines for a single
 * worksheet, not for the paged list) — a per-row variance rollup would need a header-level summary
 * field on the DTO; not present in this contract, so it is not fabricated here. Open the detail
 * page to see the lines/variance.
 *
 * Gated STOCK.VAN_RECON.VIEW (route guard admits VIEW or MANAGE); "New" action gated
 * STOCK.VAN_RECON.MANAGE.
 */
@Component({
  selector: 'app-van-reconciliation-list',
  imports: [RouterLink, PaginatorComponent],
  templateUrl: './van-reconciliation-list.component.html',
  styleUrl: './van-reconciliation-list.component.scss',
})
export class VanReconciliationListComponent {
  private readonly reconService = inject(VanReconciliationService);
  protected readonly session = inject(SessionStore);

  // ── List state ────────────────────────────────────────────────────────────────
  readonly rows = signal<VanReconciliationDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');
  readonly currentPage = signal(0);

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);
  readonly canView = computed(() => this.session.hasPermission('STOCK.VAN_RECON.VIEW'));
  readonly canManage = computed(() => this.session.hasPermission('STOCK.VAN_RECON.MANAGE'));

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    merge(this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          this.state.set('loading');
          this.currentPage.set(page);
          return this.reconService.list(page, DEFAULT_SIZE);
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
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

    this.load(0);
  }

  load(page: number): void {
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }
}
