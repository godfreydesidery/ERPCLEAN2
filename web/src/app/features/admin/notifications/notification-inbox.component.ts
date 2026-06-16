import { HttpErrorResponse } from '@angular/common/http';
import { SlicePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { NotificationDto } from './models/notifications.model';
import { NotificationsService, NotificationPage } from './notifications.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Notification inbox — paginated list of the current user's IN_APP notifications.
 * Route: /admin/notifications
 */
@Component({
  selector: 'app-notification-inbox',
  imports: [FormsModule, SlicePipe, PaginatorComponent],
  templateUrl: './notification-inbox.component.html',
  styleUrl: './notification-inbox.component.scss',
})
export class NotificationInboxComponent {
  private readonly svc = inject(NotificationsService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<NotificationDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Filter ───────────────────────────────────────────────────────────────────
  readonly filterUnread = signal(false);

  // ── Busy row tracking ────────────────────────────────────────────────────────
  readonly busyUid = signal<string | null>(null);
  readonly markingAll = signal(false);

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);
  readonly canView = computed(() => this.session.hasPermission('NOTIFICATION.VIEW'));

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const filterTrigger$ = toObservable(this.filterUnread).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(filterTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          this.state.set('loading');
          this.currentPage.set(page);
          return this.svc.listInbox(this.filterUnread(), page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: NotificationPage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

    queueMicrotask(() => this.load(0));
  }

  load(page: number): void {
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  onFilterUnreadChange(v: boolean): void {
    this.filterUnread.set(v);
  }

  markRead(n: NotificationDto): void {
    if (this.busyUid() !== null || n.read) return;
    this.busyUid.set(n.uid);
    this.svc.markRead(n.uid).subscribe({
      next: () => {
        this.busyUid.set(null);
        this.rows.update((rows) =>
          rows.map((r) => r.uid === n.uid ? { ...r, read: true } : r),
        );
        this.alerts.success('Marked as read', n.title);
      },
      error: (err: unknown) => {
        this.busyUid.set(null);
        this.alerts.error('Could not mark as read', this.messageFrom(err, 'Unknown error'));
      },
    });
  }

  markAllRead(): void {
    if (this.markingAll()) return;
    this.markingAll.set(true);
    this.svc.markAllRead().subscribe({
      next: () => {
        this.markingAll.set(false);
        this.alerts.success('All notifications marked as read');
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.markingAll.set(false);
        this.alerts.error('Could not mark all as read', this.messageFrom(err, 'Unknown error'));
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
