import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { PageMeta } from '../../core/api/api-response.model';

/**
 * Shared pagination control (rules-compliance: every paginated list must offer FIRST, PREVIOUS,
 * NEXT, LAST and page numbers — not just prev/next). Standalone + signals; emits a 0-based page
 * index via {@link pageChange}; the parent re-loads that page. Accessible: a <nav> landmark, each
 * control is a real <button> with an aria-label, the current page is aria-current and disabled,
 * a visually-hidden status announces the position.
 *
 * Usage:
 *   <app-paginator [meta]="meta()" (pageChange)="goToPage($event)" />
 * The host hides nothing itself — the component renders nothing when there is a single page.
 */
@Component({
  selector: 'app-paginator',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (meta() && meta()!.totalPages > 1) {
      <nav class="d-flex flex-wrap align-items-center gap-1 mt-2"
           [attr.aria-label]="ariaLabel()">
        <span class="visually-hidden" aria-live="polite">
          Page {{ current() + 1 }} of {{ totalPages() }}, {{ meta()!.totalElements }} items total
        </span>

        <button type="button" class="btn btn-sm btn-outline-secondary"
                [disabled]="current() === 0" (click)="go(0)"
                aria-label="First page">
          <i class="bi bi-chevron-double-left" aria-hidden="true"></i>
        </button>
        <button type="button" class="btn btn-sm btn-outline-secondary"
                [disabled]="current() === 0" (click)="go(current() - 1)"
                aria-label="Previous page">
          <i class="bi bi-chevron-left" aria-hidden="true"></i>
        </button>

        @for (p of pages(); track p.key) {
          @if (p.page === null) {
            <span class="px-1 text-muted" aria-hidden="true">…</span>
          } @else if (p.page === current()) {
            <button type="button" class="btn btn-sm btn-primary"
                    aria-current="page" disabled
                    [attr.aria-label]="'Page ' + (p.page + 1) + ', current page'">
              {{ p.page + 1 }}
            </button>
          } @else {
            <button type="button" class="btn btn-sm btn-outline-secondary"
                    (click)="go(p.page)"
                    [attr.aria-label]="'Go to page ' + (p.page + 1)">
              {{ p.page + 1 }}
            </button>
          }
        }

        <button type="button" class="btn btn-sm btn-outline-secondary"
                [disabled]="!meta()!.hasNext && current() >= totalPages() - 1"
                (click)="go(current() + 1)"
                aria-label="Next page">
          <i class="bi bi-chevron-right" aria-hidden="true"></i>
        </button>
        <button type="button" class="btn btn-sm btn-outline-secondary"
                [disabled]="current() >= totalPages() - 1" (click)="go(totalPages() - 1)"
                aria-label="Last page">
          <i class="bi bi-chevron-double-right" aria-hidden="true"></i>
        </button>

        <span class="small text-muted ms-2">
          Page {{ current() + 1 }} of {{ totalPages() }} ({{ meta()!.totalElements }} total)
        </span>
      </nav>
    }
  `,
})
export class PaginatorComponent {
  /** The page metadata from the list's last load. */
  readonly meta = input<PageMeta | null>(null);
  /** A label for the nav landmark, e.g. "Customers pagination". */
  readonly label = input<string>('');
  /** How many numbered page buttons to show around the current page (window radius). */
  readonly windowSize = input<number>(2);

  /** Emits the 0-based target page index; the parent loads it. */
  readonly pageChange = output<number>();

  protected readonly current = computed(() => this.meta()?.page ?? 0);
  protected readonly totalPages = computed(() => Math.max(1, this.meta()?.totalPages ?? 1));
  protected readonly ariaLabel = computed(() => (this.label() ? this.label() + ' ' : '') + 'pagination');

  /**
   * The page-number list with collapsed gaps: always shows page 1 and the last page, a window of
   * ±windowSize around the current page, and a null sentinel (rendered as "…") where pages are elided.
   */
  protected readonly pages = computed<{ page: number | null; key: string }[]>(() => {
    const total = this.totalPages();
    const cur = this.current();
    const w = this.windowSize();
    const wanted = new Set<number>();
    wanted.add(0);
    wanted.add(total - 1);
    for (let p = cur - w; p <= cur + w; p++) {
      if (p >= 0 && p < total) wanted.add(p);
    }
    const sorted = [...wanted].sort((a, b) => a - b);
    const out: { page: number | null; key: string }[] = [];
    let prev = -1;
    for (const p of sorted) {
      if (prev !== -1 && p - prev > 1) {
        out.push({ page: null, key: 'gap-' + prev });
      }
      out.push({ page: p, key: 'p-' + p });
      prev = p;
    }
    return out;
  });

  protected go(page: number): void {
    const clamped = Math.min(Math.max(0, page), this.totalPages() - 1);
    if (clamped !== this.current()) {
      this.pageChange.emit(clamped);
    }
  }
}
