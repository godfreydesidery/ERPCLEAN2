import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageMeta } from '../../../core/api/api-response.model';
import { AUDIT_ACTIONS, AuditFilter, AuditLogEntry } from '../models/audit.model';
import { AuditService } from './audit.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

/** Default page size — matches backend default. */
const DEFAULT_SIZE = 50;

/**
 * Read-only, filterable, paged audit-trail screen (Slice 6).
 * All four states (loading / empty / error / forbidden) are handled.
 * No create / edit / delete — the audit trail is append-only on the backend.
 */
@Component({
  selector: 'app-audit-list',
  imports: [FormsModule, PaginatorComponent],
  templateUrl: './audit-list.component.html',
  styleUrl: './audit-list.component.scss',
})
export class AuditListComponent {
  private readonly auditService = inject(AuditService);

  // ── Filter form state ─────────────────────────────────────────────────────
  readonly filterActorUid   = signal('');
  readonly filterAction     = signal('');
  readonly filterTargetType = signal('');
  readonly filterTargetUid  = signal('');
  readonly filterFrom       = signal('');
  readonly filterTo         = signal('');

  // ── List state ────────────────────────────────────────────────────────────
  readonly rows  = signal<AuditLogEntry[]>([]);
  readonly meta  = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  // ── Paging ────────────────────────────────────────────────────────────────
  /** Current page (0-based, matches backend). */
  readonly currentPage = signal(0);

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  /** Known action codes exposed to the template for the filter select. */
  readonly auditActions = AUDIT_ACTIONS;

  constructor() {
    this.load(0);
  }

  // ── Public template methods ───────────────────────────────────────────────

  applyFilter(): void {
    this.load(0);
  }

  clearFilter(): void {
    this.filterActorUid.set('');
    this.filterAction.set('');
    this.filterTargetType.set('');
    this.filterTargetUid.set('');
    this.filterFrom.set('');
    this.filterTo.set('');
    this.load(0);
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  // ── Formatting helpers (used by template) ────────────────────────────────

  formatAt(iso: string): string {
    try {
      return new Intl.DateTimeFormat(undefined, {
        year: 'numeric', month: 'short', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
        hour12: false,
      }).format(new Date(iso));
    } catch {
      return iso;
    }
  }

  /** Returns the first 8 chars of a uid for compact display, or '—' if null/empty. */
  shortUid(uid: string | null | undefined): string {
    return uid ? uid.slice(0, 8) + '…' : '—';
  }

  actionBadgeClass(action: string): string {
    if (action.startsWith('LOGIN.'))   return 'text-bg-info';
    if (action.startsWith('USER.'))    return 'text-bg-primary';
    if (action.startsWith('ROLE.'))    return 'text-bg-warning';
    if (action.startsWith('BRANCH.'))  return 'text-bg-secondary';
    if (action.startsWith('ACCOUNT.')) return 'text-bg-danger';
    if (action.startsWith('ROOT.'))    return 'text-bg-dark';
    return 'text-bg-light border';
  }

  // ── Private ───────────────────────────────────────────────────────────────

  private load(page: number): void {
    this.state.set('loading');
    this.currentPage.set(page);

    const filters: AuditFilter = {
      actorUid:   this.filterActorUid()   || undefined,
      action:     this.filterAction()     || undefined,
      targetType: this.filterTargetType() || undefined,
      targetUid:  this.filterTargetUid()  || undefined,
      from:       this.filterFrom()       ? this.toIsoInstant(this.filterFrom()) : undefined,
      to:         this.filterTo()         ? this.toIsoInstant(this.filterTo())   : undefined,
    };

    this.auditService.search(filters, page, DEFAULT_SIZE).subscribe({
      next: ({ rows, meta }) => {
        this.rows.set(rows);
        this.meta.set(meta);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  /**
   * Converts a `datetime-local` value (e.g. "2024-06-01T14:30") to an ISO-8601 instant string
   * with a UTC offset of Z. The input has no timezone info, so we treat it as local time and let
   * `new Date()` parse it, then call `.toISOString()`.
   */
  private toIsoInstant(localDt: string): string {
    try {
      return new Date(localDt).toISOString();
    } catch {
      return localDt;
    }
  }
}
