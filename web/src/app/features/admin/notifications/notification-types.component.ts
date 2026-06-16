import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { NotificationTypeDto } from './models/notifications.model';
import { NotificationsService } from './notifications.service';

/**
 * Admin: notification type catalogue — list + per-company enable/disable toggle.
 * Route: /admin/notification-types
 */
@Component({
  selector: 'app-notification-types',
  imports: [],
  templateUrl: './notification-types.component.html',
  styleUrl: './notification-types.component.scss',
})
export class NotificationTypesComponent {
  private readonly svc = inject(NotificationsService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly rows = signal<NotificationTypeDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  /** typeKey → busy flag for toggle action */
  readonly busyKey = signal<string | null>(null);

  readonly canAdmin = computed(() => this.session.hasPermission('NOTIFICATION.ADMIN'));

  private readonly loadTrigger$ = new Subject<void>();

  constructor() {
    this.loadTrigger$
      .pipe(
        switchMap(() => {
          this.state.set('loading');
          return this.svc.listTypes();
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (rows) => {
          this.rows.set(rows);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

    queueMicrotask(() => this.loadTrigger$.next());
  }

  toggleEnabled(row: NotificationTypeDto): void {
    if (this.busyKey() !== null) return;
    this.busyKey.set(row.typeKey);
    const newEnabled = !row.companyEnabled;
    this.svc.setTypeState(row.typeKey, { enabled: newEnabled }).subscribe({
      next: (updated) => {
        this.busyKey.set(null);
        this.rows.update((rows) =>
          rows.map((r) => r.typeKey === row.typeKey ? updated : r),
        );
        this.alerts.success(
          newEnabled ? 'Type enabled' : 'Type disabled',
          row.displayName,
        );
      },
      error: (err: unknown) => {
        this.busyKey.set(null);
        this.alerts.error(
          'Could not update type state',
          this.messageFrom(err, 'Unknown error'),
        );
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
