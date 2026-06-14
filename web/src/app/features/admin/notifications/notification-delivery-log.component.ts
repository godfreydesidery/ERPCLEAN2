import { HttpErrorResponse } from '@angular/common/http';
import { SlicePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { NotificationDeliveryDto } from './models/notifications.model';
import { NotificationsService, DeliveryLogPage } from './notifications.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Admin: notification delivery log — paginated audit trail with channel/outcome filters.
 * Route: /admin/notification-deliveries
 */
@Component({
  selector: 'app-notification-delivery-log',
  imports: [FormsModule, SlicePipe, PaginatorComponent],
  templateUrl: './notification-delivery-log.component.html',
  styleUrl: './notification-delivery-log.component.scss',
})
export class NotificationDeliveryLogComponent {
  private readonly svc = inject(NotificationsService);
  protected readonly session = inject(SessionStore);

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<NotificationDeliveryDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Filters ──────────────────────────────────────────────────────────────────
  readonly filterChannel = signal('');
  readonly filterOutcome = signal('');

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);
  readonly canAdmin = computed(() => this.session.hasPermission('NOTIFICATION.ADMIN'));

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const channelTrigger$ = toObservable(this.filterChannel).pipe(
      skip(1),
      debounceTime(300),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );
    const outcomeTrigger$ = toObservable(this.filterOutcome).pipe(
      skip(1),
      debounceTime(300),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(channelTrigger$, outcomeTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          this.state.set('loading');
          this.currentPage.set(page);
          const ch = this.filterChannel().trim().toUpperCase() || undefined;
          const oc = this.filterOutcome().trim().toUpperCase() || undefined;
          return this.svc.listDeliveries(ch, oc, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: DeliveryLogPage) => {
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

  /**
   * Strip any embedded ULID from the triggerKey (C1 — no raw uid in visible text).
   * Scan-based keys have the form "scan:TYPE_KEY:<26-char-uid>" — return only
   * "scan:TYPE_KEY".  Plain keys ("TYPE_KEY") are returned as-is.
   */
  shortTriggerKey(key: string): string {
    if (!key) return '—';
    return key.replace(/:[0-9A-HJKMNP-TV-Z]{26}$/i, '');
  }

  outcomeBadgeClass(outcome: string): string {
    switch (outcome?.toUpperCase()) {
      case 'SENT': return 'text-bg-success';
      case 'FAILED': return 'text-bg-danger';
      case 'SUPPRESSED': return 'text-bg-warning text-dark';
      case 'PENDING': return 'text-bg-info';
      default: return 'text-bg-secondary';
    }
  }
}
