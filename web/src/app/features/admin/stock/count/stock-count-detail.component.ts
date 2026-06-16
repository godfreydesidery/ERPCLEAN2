import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe, NgClass } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { StockCountDto } from './stock-count.model';
import { StockCountService } from './stock-count.service';

/**
 * Stock Count detail page. Route: /admin/stock-counts/uid/:uid.
 *
 * States and actions per status:
 *  COUNTING  → editable countedQty per line (Enter action), Cancel
 *  COUNTING  → Post action (posting date) after at least one qty entered
 *  POSTED    → read-only; shows variance per line + GL entry uid
 *  CANCELLED → read-only
 */
@Component({
  selector: 'app-stock-count-detail',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, NgClass],
  templateUrl: './stock-count-detail.component.html',
  styleUrl: './stock-count-detail.component.scss',
})
export class StockCountDetailComponent {
  private readonly countService = inject(StockCountService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  // ── Entity ────────────────────────────────────────────────────────────────────
  readonly count = signal<StockCountDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  // ── Enter-count form state ────────────────────────────────────────────────────
  /** Map lineId → user-typed counted qty (string from number input) */
  readonly editedQtys = signal<Record<string, string>>({});
  /** Map lineId → reason code */
  readonly editedReasons = signal<Record<string, string>>({});
  readonly entering = signal(false);
  readonly enterError = signal<string | null>(null);

  // ── Post form ─────────────────────────────────────────────────────────────────
  readonly showPostForm = signal(false);
  readonly postingDate = signal(new Date().toISOString().substring(0, 10));
  readonly posting = signal(false);
  readonly postError = signal<string | null>(null);

  // ── Cancel ────────────────────────────────────────────────────────────────────
  readonly cancelling = signal(false);
  readonly cancelError = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('STOCK.COUNT.CREATE'));
  readonly canPost   = computed(() => this.session.hasPermission('STOCK.COUNT.POST'));

  // ── Derived ───────────────────────────────────────────────────────────────────
  readonly isCounting  = computed(() => this.count()?.status === 'COUNTING');
  readonly isPosted    = computed(() => this.count()?.status === 'POSTED');
  readonly isCancelled = computed(() => this.count()?.status === 'CANCELLED');

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.countService.getByUid(this.uid()).subscribe({
      next: (c) => {
        this.count.set(c);
        this.state.set('idle');
        this.initEditedQtys(c);
      },
      error: (err) => {
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error');
      },
    });
  }

  private initEditedQtys(c: StockCountDto): void {
    const qtys: Record<string, string> = {};
    const reasons: Record<string, string> = {};
    for (const line of c.lines) {
      qtys[line.id] = line.countedQty ?? '';
      reasons[line.id] = line.reasonCode ?? '';
    }
    this.editedQtys.set(qtys);
    this.editedReasons.set(reasons);
  }

  onQtyChange(lineId: string, val: unknown): void {
    this.editedQtys.update((m) => ({ ...m, [lineId]: val === null || val === undefined ? '' : String(val) }));
  }

  onReasonChange(lineId: string, val: string): void {
    this.editedReasons.update((m) => ({ ...m, [lineId]: val }));
  }

  // ── Enter counted quantities ──────────────────────────────────────────────────

  enterCount(): void {
    this.enterError.set(null);
    const qtys = this.editedQtys();
    const reasons = this.editedReasons();
    const lines = (this.count()?.lines ?? [])
      .filter((l) => {
        const v = qtys[l.id];
        return v !== '' && v !== null && v !== undefined && !isNaN(Number(v));
      })
      .map((l) => ({
        lineId: l.id,
        countedQty: String(qtys[l.id]).trim(),
        reasonCode: reasons[l.id]?.trim() || undefined,
      }));

    if (lines.length === 0) {
      this.enterError.set('Enter at least one counted quantity.');
      return;
    }

    this.entering.set(true);
    this.countService.enterCount(this.uid(), { lines }).subscribe({
      next: (updated) => {
        this.count.set(updated);
        this.initEditedQtys(updated);
        this.entering.set(false);
        this.alerts.success('Counted quantities saved');
      },
      error: (err) => {
        this.enterError.set(this.messageFrom(err, 'Could not save counted quantities.'));
        this.entering.set(false);
      },
    });
  }

  // ── Post ──────────────────────────────────────────────────────────────────────

  togglePostForm(): void {
    this.showPostForm.update((v) => !v);
    this.postError.set(null);
  }

  submitPost(): void {
    this.postError.set(null);
    if (!this.postingDate()) { this.postError.set('Posting date is required.'); return; }

    this.posting.set(true);
    this.countService.post(this.uid(), this.postingDate()).subscribe({
      next: (updated) => {
        this.count.set(updated);
        this.initEditedQtys(updated);
        this.posting.set(false);
        this.showPostForm.set(false);
        this.alerts.success('Stock count posted', 'Variance journal created.');
      },
      error: (err) => {
        this.postError.set(this.messageFrom(err, 'Could not post stock count.'));
        this.posting.set(false);
      },
    });
  }

  // ── Cancel ────────────────────────────────────────────────────────────────────

  cancelCount(): void {
    this.cancelError.set(null);
    this.cancelling.set(true);
    this.countService.cancel(this.uid()).subscribe({
      next: () => {
        // Reload to get CANCELLED status
        this.countService.getByUid(this.uid()).subscribe({
          next: (updated) => {
            this.count.set(updated);
            this.cancelling.set(false);
            this.alerts.success('Stock count cancelled');
          },
          error: () => { this.cancelling.set(false); },
        });
      },
      error: (err) => {
        this.cancelError.set(this.messageFrom(err, 'Could not cancel stock count.'));
        this.cancelling.set(false);
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────────

  varianceClass(varianceQty: string | null): string {
    if (!varianceQty) return '';
    const n = Number(varianceQty);
    if (n > 0) return 'text-success';
    if (n < 0) return 'text-danger';
    return 'text-muted';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
