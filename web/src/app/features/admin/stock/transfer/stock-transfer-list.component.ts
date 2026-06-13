import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { SessionStore } from '../../../../core/auth/session.store';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { StockTransferDto, StockTransferStatus } from './stock-transfer.model';
import { StockTransferService } from './stock-transfer.service';

const DEFAULT_SIZE = 20;

interface LoadTrigger {
  page: number;
}

@Component({
  selector: 'app-stock-transfer-list',
  imports: [DatePipe, RouterLink, PaginatorComponent],
  templateUrl: './stock-transfer-list.component.html',
  styleUrl: './stock-transfer-list.component.scss',
})
export class StockTransferListComponent {
  private readonly transferService = inject(StockTransferService);
  protected readonly session = inject(SessionStore);

  // ── List state ────────────────────────────────────────────────────────────────
  readonly rows = signal<StockTransferDto[]>([]);
  readonly meta = signal<PageMeta>({
    page: 0,
    size: DEFAULT_SIZE,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
  });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');
  readonly currentPage = signal(0);

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);
  readonly canCreate = computed(() => this.session.hasPermission('STOCK.TRANSFER.CREATE'));
  readonly canView = computed(() => this.session.hasPermission('STOCK.TRANSFER.VIEW'));

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const pageTrigger$ = toObservable(this.currentPage).pipe(
      skip(1),
      debounceTime(0),
      distinctUntilChanged(),
      map((page): LoadTrigger => ({ page })),
    );

    merge(pageTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          this.state.set('loading');
          return this.transferService.list(page, DEFAULT_SIZE);
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

    this.load(0);
  }

  load(page: number): void {
    this.currentPage.set(page);
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void {
    this.load(page);
  }

  statusBadgeClass(status: StockTransferStatus): string {
    switch (status) {
      case 'DRAFT':
        return 'text-bg-warning';
      case 'DISPATCHED':
        return 'text-bg-info';
      case 'RECEIVED':
        return 'text-bg-primary';
      case 'COMPLETED':
        return 'text-bg-success';
      case 'CANCELLED':
        return 'text-bg-danger';
      default:
        return 'text-bg-secondary';
    }
  }

  transferLabel(t: StockTransferDto): string {
    return t.transferNumber ?? 'DRAFT';
  }
}
