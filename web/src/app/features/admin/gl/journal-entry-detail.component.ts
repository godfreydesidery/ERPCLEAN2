import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { JournalEntryDto, JournalSourceType } from './models/gl.model';
import { GlService } from './gl.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Journal entry detail screen. Route: /admin/gl/journals/uid/:uid.
 * Shows the balanced lines table (account, debit, credit, memo) with column totals.
 * Reverse button (GL.POST) for MANUAL entries — calls /reverse and navigates to new entry.
 * SALES auto-posted entries are read-only (no reverse button shown for sourceType SALES).
 */
@Component({
  selector: 'app-journal-entry-detail',
  imports: [RouterLink],
  templateUrl: './journal-entry-detail.component.html',
  styleUrl: './journal-entry-detail.component.scss',
})
export class JournalEntryDetailComponent {
  private readonly glService = inject(GlService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Journal state ──────────────────────────────────────────────────────────
  readonly entry = signal<JournalEntryDto | null>(null);
  readonly entryState = signal<LoadState>('loading');

  // ── Reversal ───────────────────────────────────────────────────────────────
  readonly reversing = signal(false);
  readonly reverseError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canPost = computed(() => this.session.hasPermission('GL.POST'));

  // ── Derived state ──────────────────────────────────────────────────────────
  readonly isManual = computed(() => this.entry()?.sourceType === 'MANUAL');
  readonly isAlreadyReversal = computed(() => !!this.entry()?.reversalOfId);

  readonly canReverse = computed(() =>
    this.canPost() && this.isManual() && !this.isAlreadyReversal(),
  );

  readonly totalDebits = computed(() =>
    (this.entry()?.lines ?? [])
      .reduce((sum, l) => sum + Number.parseFloat(l.debitAmount || '0'), 0)
      .toFixed(2),
  );

  readonly totalCredits = computed(() =>
    (this.entry()?.lines ?? [])
      .reduce((sum, l) => sum + Number.parseFloat(l.creditAmount || '0'), 0)
      .toFixed(2),
  );

  readonly isBalanced = computed(() => this.totalDebits() === this.totalCredits());

  constructor() {
    queueMicrotask(() => this.loadEntry());
  }

  private loadEntry(): void {
    this.entryState.set('loading');
    this.glService.getJournalByUid(this.uid()).subscribe({
      next: (e) => {
        this.entry.set(e);
        this.entryState.set('idle');
      },
      error: () => this.entryState.set('error'),
    });
  }

  reverse(): void {
    if (this.reversing()) return;
    this.reversing.set(true);
    this.reverseError.set(null);
    this.glService.reverseJournal(this.uid()).subscribe({
      next: (reversal) => {
        this.reversing.set(false);
        this.alerts.success('Journal reversed', reversal.batchNumber);
        this.router.navigate(['/admin/gl/journals/uid', reversal.uid]);
      },
      error: (err) => {
        this.reverseError.set(this.messageFrom(err, 'Could not reverse journal entry.'));
        this.reversing.set(false);
      },
    });
  }

  sourceBadgeClass(sourceType: JournalSourceType): string {
    switch (sourceType) {
      case 'MANUAL': return 'text-bg-primary';
      case 'SALES': return 'text-bg-success';
      case 'SALES_REVERSAL': return 'text-bg-warning';
      case 'OPENING_BALANCE': return 'text-bg-info';
      case 'PURCHASE': return 'text-bg-secondary';
      case 'PURCHASE_REVERSAL': return 'text-bg-warning';
      default: return 'text-bg-light border';
    }
  }

  /** Expose Number.parseFloat to the template. */
  readonly parseFloat = Number.parseFloat;

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
