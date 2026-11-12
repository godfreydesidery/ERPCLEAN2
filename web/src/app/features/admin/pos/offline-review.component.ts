import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { AppDatePipe } from '../../../shared/app-date.pipe';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import {
  OfflinePeriodDto,
  OfflineReviewDto,
  OfflineReviewKind,
  OfflineReviewStatus,
} from './models/pos.model';
import { PosService } from './pos.service';

const PAGE_SIZE = 20;
const EMPTY_META: PageMeta = { page: 0, size: PAGE_SIZE, totalElements: 0, totalPages: 0, hasNext: false };

const KIND_LABELS: Record<OfflineReviewKind, string> = {
  PRICE_DIFFERENCE: 'Price differed',
  NEGATIVE_STOCK: 'Stock went below zero',
  CLOSED_PERIOD: 'Closed accounting period',
  BELOW_COST: 'Sold below cost',
  HELD: 'Held — not posted',
};

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Offline sales review (ADR-0068 D-8): items a manager must look at after a till sold offline,
 * plus the history of offline periods. Gated by POS.OFFLINE.REVIEW (route guard + sidebar + here).
 */
@Component({
  selector: 'app-offline-review',
  imports: [AppDatePipe, FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './offline-review.component.html',
})
export class OfflineReviewComponent {
  private readonly pos = inject(PosService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly canReview = computed(() => this.session.hasPermission('POS.OFFLINE.REVIEW'));

  // ── Review items ───────────────────────────────────────────────────────────
  readonly statusFilter = signal<OfflineReviewStatus>('OPEN');
  readonly items = signal<OfflineReviewDto[]>([]);
  readonly itemsMeta = signal<PageMeta>(EMPTY_META);
  readonly itemsState = signal<LoadState>('loading');
  readonly itemsError = signal<string | null>(null);

  readonly expandedUid = signal<string | null>(null);
  readonly ackUid = signal<string | null>(null);
  readonly ackNote = signal('');
  readonly ackBusy = signal(false);
  readonly ackError = signal<string | null>(null);

  // ── Periods ────────────────────────────────────────────────────────────────
  readonly periods = signal<OfflinePeriodDto[]>([]);
  readonly periodsMeta = signal<PageMeta>(EMPTY_META);
  readonly periodsState = signal<LoadState>('loading');
  readonly periodsError = signal<string | null>(null);

  constructor() {
    if (this.canReview()) {
      this.loadItems(0);
      this.loadPeriods(0);
    }
  }

  kindLabel(kind: OfflineReviewKind): string {
    return KIND_LABELS[kind] ?? kind;
  }

  reasonLabel(reason: OfflinePeriodDto['reason']): string {
    return reason === 'UNREACHABLE' ? 'No connection' : 'Switched by a manager';
  }

  setStatus(status: OfflineReviewStatus): void {
    this.statusFilter.set(status);
    this.cancelAck();
    this.expandedUid.set(null);
    this.loadItems(0);
  }

  loadItems(page: number): void {
    this.itemsState.set('loading');
    this.itemsError.set(null);
    this.pos.listOfflineReviews(this.statusFilter(), page, PAGE_SIZE).subscribe({
      next: ({ rows, meta }) => {
        this.items.set(rows);
        this.itemsMeta.set(meta);
        this.itemsState.set('idle');
      },
      error: (err: unknown) => {
        this.itemsError.set(this.messageFrom(err, 'Could not load review items. Please try again.'));
        this.itemsState.set('error');
      },
    });
  }

  loadPeriods(page: number): void {
    this.periodsState.set('loading');
    this.periodsError.set(null);
    this.pos.listOfflinePeriods(page, PAGE_SIZE).subscribe({
      next: ({ rows, meta }) => {
        this.periods.set(rows);
        this.periodsMeta.set(meta);
        this.periodsState.set('idle');
      },
      error: (err: unknown) => {
        this.periodsError.set(this.messageFrom(err, 'Could not load offline periods. Please try again.'));
        this.periodsState.set('error');
      },
    });
  }

  toggleExpand(uid: string): void {
    this.expandedUid.update((cur) => (cur === uid ? null : uid));
  }

  /** Pretty-prints the held request. Parsed as data only; falls back to the raw text if invalid. */
  prettyHeld(raw: string | null): string {
    if (!raw) return '';
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      return raw;
    }
  }

  startAck(uid: string): void {
    this.ackUid.set(uid);
    this.ackNote.set('');
    this.ackError.set(null);
  }

  cancelAck(): void {
    this.ackUid.set(null);
    this.ackNote.set('');
    this.ackError.set(null);
  }

  confirmAck(): void {
    const uid = this.ackUid();
    if (!uid || this.ackBusy()) return;
    const note = this.ackNote().trim();
    if (note.length > 500) { this.ackError.set('The note can be at most 500 characters.'); return; }
    this.ackBusy.set(true);
    this.ackError.set(null);
    this.pos.acknowledgeOfflineReview(uid, note || undefined).subscribe({
      next: () => {
        this.ackBusy.set(false);
        this.cancelAck();
        this.alerts.success('Review item acknowledged');
        this.loadItems(this.itemsMeta().page);
      },
      error: (err: unknown) => {
        this.ackBusy.set(false);
        this.ackError.set(this.messageFrom(err, 'Could not acknowledge this item.'));
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] } | null)?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
