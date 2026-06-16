import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe, SlicePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { ApprovalsService } from './approvals.service';
import type { ApprovalRequestPage } from './approvals.service';
import { ApprovalRequestDto } from './models/approvals.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Approval inbox — work queue of PENDING requests the current user can decide.
 * No company filter — endpoint is implicitly scoped to the caller's company/roles/branches.
 * Route: /admin/approvals/inbox
 */
@Component({
  selector: 'app-approval-inbox',
  imports: [RouterLink, DecimalPipe, SlicePipe, PaginatorComponent],
  templateUrl: './approval-inbox.component.html',
  styleUrl: './approval-inbox.component.scss',
})
export class ApprovalInboxComponent {
  private readonly approvalsService = inject(ApprovalsService);
  protected readonly session = inject(SessionStore);

  readonly rows = signal<ApprovalRequestDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');
  readonly currentPage = signal(0);
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  private readonly trigger$ = new Subject<LoadTrigger>();

  constructor() {
    this.trigger$
      .pipe(
        switchMap(({ page }) => {
          this.state.set('loading');
          this.currentPage.set(page);
          return this.approvalsService.listInbox(page, DEFAULT_SIZE);
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

    // Fire initial load immediately
    queueMicrotask(() => this.load(0));
  }

  load(page: number): void {
    this.trigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

}
