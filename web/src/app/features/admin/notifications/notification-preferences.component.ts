import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { NotificationPreferenceDto, SetPreferenceRequest } from './models/notifications.model';
import { NotificationsService } from './notifications.service';

/**
 * Notification preferences — per-user upsert of muted + channelsEnabled per typeKey.
 * Route: /admin/notification-preferences
 */
@Component({
  selector: 'app-notification-preferences',
  imports: [FormsModule],
  templateUrl: './notification-preferences.component.html',
  styleUrl: './notification-preferences.component.scss',
})
export class NotificationPreferencesComponent {
  private readonly svc = inject(NotificationsService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly rows = signal<NotificationPreferenceDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  /** typeKey → in-progress state for the save button */
  readonly busyKey = signal<string | null>(null);
  /** typeKey → muted edit signal (local copy per row) */
  readonly mutedEdits = signal<Record<string, boolean>>({});
  /** typeKey → channelsEnabled edit signal */
  readonly channelsEdits = signal<Record<string, string>>({});
  /** typeKey → save error */
  readonly rowError = signal<Record<string, string>>({});

  readonly canManage = computed(() => this.session.hasPermission('NOTIFICATION.PREFERENCE.MANAGE'));

  private readonly loadTrigger$ = new Subject<void>();

  constructor() {
    this.loadTrigger$
      .pipe(
        switchMap(() => {
          this.state.set('loading');
          return this.svc.listPreferences();
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (rows) => {
          this.rows.set(rows);
          this.state.set('idle');
          // Seed local edit copies
          const muted: Record<string, boolean> = {};
          const channels: Record<string, string> = {};
          rows.forEach((r) => {
            muted[r.typeKey] = r.muted;
            channels[r.typeKey] = r.channelsEnabled ?? '';
          });
          this.mutedEdits.set(muted);
          this.channelsEdits.set(channels);
        },
        error: (err: unknown) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

    queueMicrotask(() => this.loadTrigger$.next());
  }

  getMuted(typeKey: string): boolean {
    return this.mutedEdits()[typeKey] ?? false;
  }

  setMuted(typeKey: string, v: boolean): void {
    this.mutedEdits.update((m) => ({ ...m, [typeKey]: v }));
  }

  getChannels(typeKey: string): string {
    return this.channelsEdits()[typeKey] ?? '';
  }

  setChannels(typeKey: string, v: string): void {
    this.channelsEdits.update((m) => ({ ...m, [typeKey]: v }));
  }

  save(typeKey: string): void {
    if (this.busyKey() !== null) return;
    this.busyKey.set(typeKey);
    this.rowError.update((e) => ({ ...e, [typeKey]: '' }));

    const request: SetPreferenceRequest = {
      muted: this.getMuted(typeKey),
      channelsEnabled: this.getChannels(typeKey),
    };

    this.svc.upsertPreference(typeKey, request).subscribe({
      next: (updated) => {
        this.busyKey.set(null);
        this.rows.update((rows) =>
          rows.map((r) => r.typeKey === typeKey ? updated : r),
        );
        this.alerts.success('Preference saved', typeKey);
      },
      error: (err: unknown) => {
        this.busyKey.set(null);
        this.rowError.update((e) => ({
          ...e,
          [typeKey]: this.messageFrom(err, 'Could not save preference.'),
        }));
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
