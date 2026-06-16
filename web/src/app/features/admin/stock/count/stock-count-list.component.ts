import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { merge, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { SessionStore } from '../../../../core/auth/session.store';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { StockCountDto } from './stock-count.model';
import { StockCountService } from './stock-count.service';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Stock Count list. Paged via <app-paginator>. Status badge.
 * Permission: STOCK.COUNT.VIEW for read, STOCK.COUNT.CREATE for the new-count link.
 */
@Component({
  selector: 'app-stock-count-list',
  imports: [RouterLink, PaginatorComponent],
  templateUrl: './stock-count-list.component.html',
  styleUrl: './stock-count-list.component.scss',
})
export class StockCountListComponent {
  private readonly countService = inject(StockCountService);
  protected readonly session = inject(SessionStore);

  // ── List state ────────────────────────────────────────────────────────────────
  readonly rows = signal<StockCountDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');
  readonly currentPage = signal(0);

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);
  readonly canCreate = computed(() => this.session.hasPermission('STOCK.COUNT.CREATE'));
  readonly canView = computed(() => this.session.hasPermission('STOCK.COUNT.VIEW'));

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    merge(this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          this.state.set('loading');
          this.currentPage.set(page);
          return this.countService.list(page, DEFAULT_SIZE);
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
