import { DOCUMENT } from '@angular/common';
import { Injectable, OnDestroy, inject } from '@angular/core';

const WRAP_SELECTOR = '.erp-table-wrap';
/** Marks a wrap whose tabindex/role/aria-label we added, so we know it's safe to undo. */
const MANAGED_ATTR = 'data-a11y-scroll';
const FALLBACK_LABEL = 'Scrollable table';

/**
 * App-wide a11y fix for the shared `.erp-table-wrap` horizontal-scroll pattern
 * (web/src/styles.scss ~L284, used in ~190 templates for "wrap any table for horizontal
 * scroll on phones"). A scrollable region with no keyboard access is a SERIOUS axe
 * violation (`scrollable-region-focusable`) — the row-action column pinned to the right
 * edge (commit 3644380) is literally unreachable by keyboard when a wrap overflows.
 *
 * Rather than hand-edit ~190 templates (or thread a directive import through every
 * standalone component's `imports` array), this single service watches the DOM from the
 * app root and, for every `.erp-table-wrap` that is ACTUALLY overflowing, makes it a
 * focusable, named region: `tabindex="0"`, `role="region"`, and an `aria-label` —
 * preferring the table's own (usually visually-hidden) `<caption>` text for a contextual
 * name, falling back to a generic one. A wrap that doesn't overflow is left untouched —
 * no stray tab stop on a table that already fits.
 *
 * Re-evaluated on DOM mutation (rows/columns arriving async after the initial render)
 * and window resize (breakpoint/orientation changes), so it stays correct as data loads
 * and the viewport changes — not just at first paint.
 */
@Injectable({ providedIn: 'root' })
export class ScrollableRegionService implements OnDestroy {
  private readonly document = inject(DOCUMENT);
  private observer: MutationObserver | null = null;
  private rescanHandle: ReturnType<typeof setTimeout> | null = null;
  private readonly onResize = () => this.scheduleRescan();

  /** Begins observing the document for `.erp-table-wrap` regions. Idempotent. */
  start(): void {
    if (this.observer) return;
    this.observer = new MutationObserver(() => this.scheduleRescan());
    this.observer.observe(this.document.body, { childList: true, subtree: true });
    this.document.defaultView?.addEventListener('resize', this.onResize);
    this.scan();
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
    this.observer = null;
    this.document.defaultView?.removeEventListener('resize', this.onResize);
    if (this.rescanHandle) {
      clearTimeout(this.rescanHandle);
      this.rescanHandle = null;
    }
  }

  /** Coalesces bursts of mutations (e.g. a whole table's worth of rows loading) into one scan. */
  private scheduleRescan(): void {
    if (this.rescanHandle) return;
    this.rescanHandle = setTimeout(() => {
      this.rescanHandle = null;
      this.scan();
    }, 50);
  }

  /** Re-evaluates every `.erp-table-wrap` currently in the document. */
  scan(): void {
    this.document
      .querySelectorAll<HTMLElement>(WRAP_SELECTOR)
      .forEach((wrap) => enhanceScrollWrap(wrap));
  }
}

/**
 * Applies (or retracts) keyboard-access attributes on a single `.erp-table-wrap` element.
 * Exported as a pure DOM function so it's unit-testable without spinning up observers/timers.
 */
export function enhanceScrollWrap(wrap: HTMLElement): void {
  const scrollable = wrap.scrollWidth > wrap.clientWidth + 1;
  const managedByUs = wrap.hasAttribute(MANAGED_ATTR);

  if (scrollable) {
    if (!wrap.hasAttribute('tabindex')) {
      wrap.setAttribute('tabindex', '0');
      wrap.setAttribute(MANAGED_ATTR, '');
    }
    if (!wrap.hasAttribute('role')) {
      wrap.setAttribute('role', 'region');
    }
    if (!wrap.hasAttribute('aria-label')) {
      wrap.setAttribute('aria-label', deriveAccessibleName(wrap));
    }
  } else if (managedByUs) {
    // The viewport grew (or content shrank) since we made this focusable — drop the now-
    // pointless tab stop/landmark rather than leave one on a wrap that no longer scrolls.
    wrap.removeAttribute('tabindex');
    wrap.removeAttribute('role');
    wrap.removeAttribute('aria-label');
    wrap.removeAttribute(MANAGED_ATTR);
  }
}

/**
 * Best-effort contextual name for the scroll region: the table's own `<caption>` (present on
 * almost every `.erp-table-wrap` usage, usually visually-hidden) beats a generic label.
 */
function deriveAccessibleName(wrap: HTMLElement): string {
  const captionText = wrap.querySelector('caption')?.textContent?.trim();
  if (captionText) return captionText;
  const tableLabel = wrap.querySelector('table[aria-label]')?.getAttribute('aria-label')?.trim();
  if (tableLabel) return tableLabel;
  return FALLBACK_LABEL;
}
