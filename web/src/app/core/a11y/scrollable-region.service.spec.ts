/**
 * D1 fix — `.erp-table-wrap` scrollable regions must be keyboard-accessible + named.
 *
 * jsdom has no real layout engine, so `scrollWidth`/`clientWidth` are always 0 — we stub
 * them per-element (they're plain DOM properties, overridable via defineProperty) to
 * simulate an overflowing vs. a fitting table. This is why the shared axe helper
 * (src/testing/a11y.helper.ts) disables the `scrollable-region-focusable` rule under
 * jsdom; the REAL gate for that rule is the Playwright e2e run in a real browser. This
 * spec instead exercises the enhancer's own DOM logic directly.
 */
import { TestBed } from '@angular/core/testing';
import { enhanceScrollWrap, ScrollableRegionService } from './scrollable-region.service';

/** Stubs scrollWidth/clientWidth (jsdom always reports 0 for both — no real layout). */
function stubOverflow(el: HTMLElement, scrollWidth: number, clientWidth: number): void {
  Object.defineProperty(el, 'scrollWidth', { value: scrollWidth, configurable: true });
  Object.defineProperty(el, 'clientWidth', { value: clientWidth, configurable: true });
}

/**
 * Waits for `predicate` to hold, polling until `timeoutMs`.
 *
 * The service coalesces mutations behind a 50ms debounce, so a test that asserts on the result has
 * to wait for wall-clock time — microtask flushing will not do it. Waiting a fixed multiple of the
 * debounce is the obvious approach and is what this file did; it is also load-dependent, and it
 * produced a spec that failed in a full suite run and passed alone seconds later.
 *
 * Polling keeps the fast path fast (it returns on the first tick after the debounce fires) while
 * tolerating a machine that is busy, which is the normal condition in CI.
 */
async function waitFor(predicate: () => boolean, timeoutMs = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() > deadline) {
      // Fall through and let the assertion that follows report the real mismatch — a bare timeout
      // error here would hide WHICH attribute was wrong.
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

function buildWrap(innerHtml: string): HTMLDivElement {
  const wrap = document.createElement('div');
  wrap.className = 'erp-table-wrap';
  wrap.innerHTML = innerHtml;
  return wrap;
}

describe('enhanceScrollWrap (pure DOM logic)', () => {
  it('adds tabindex="0" + role="region" + aria-label when the wrap actually overflows', () => {
    const wrap = buildWrap(
      '<table><caption class="visually-hidden">Stock on-hand</caption></table>',
    );
    stubOverflow(wrap, 1200, 600); // scrollWidth > clientWidth → overflowing

    enhanceScrollWrap(wrap);

    expect(wrap.getAttribute('tabindex')).toBe('0');
    expect(wrap.getAttribute('role')).toBe('region');
    expect(wrap.getAttribute('aria-label')).toBe('Stock on-hand');
  });

  it('does NOT add a tab stop when the table fits (no overflow) — no stray focus stop', () => {
    const wrap = buildWrap('<table><caption>Products</caption></table>');
    stubOverflow(wrap, 600, 600); // scrollWidth === clientWidth → fits

    enhanceScrollWrap(wrap);

    expect(wrap.hasAttribute('tabindex')).toBe(false);
    expect(wrap.hasAttribute('role')).toBe(false);
    expect(wrap.hasAttribute('aria-label')).toBe(false);
  });

  it('falls back to a generic label when no caption/aria-label is available', () => {
    const wrap = buildWrap('<table><thead><tr><th>Col</th></tr></thead></table>');
    stubOverflow(wrap, 900, 400);

    enhanceScrollWrap(wrap);

    expect(wrap.getAttribute('aria-label')).toBe('Scrollable table');
  });

  it('prefers a table[aria-label] over the fallback when there is no caption', () => {
    const wrap = buildWrap('<table aria-label="Custom name"></table>');
    stubOverflow(wrap, 900, 400);

    enhanceScrollWrap(wrap);

    expect(wrap.getAttribute('aria-label')).toBe('Custom name');
  });

  it('does not clobber an author-supplied tabindex, but still fills in a missing role/aria-label', () => {
    // Mirrors notification-delivery-log.component.html — the one existing usage that
    // already hand-sets tabindex="0" but has no role/aria-label.
    const wrap = buildWrap('<table><caption>Notification delivery log</caption></table>');
    wrap.setAttribute('tabindex', '0');
    stubOverflow(wrap, 1000, 500);

    enhanceScrollWrap(wrap);

    expect(wrap.getAttribute('tabindex')).toBe('0');
    expect(wrap.getAttribute('role')).toBe('region');
    expect(wrap.getAttribute('aria-label')).toBe('Notification delivery log');
    // We didn't add the tabindex, so we must not later remove it either.
    expect(wrap.hasAttribute('data-a11y-scroll')).toBe(false);
  });

  it('retracts tabindex/role/aria-label it added once the wrap stops overflowing', () => {
    const wrap = buildWrap('<table><caption>Purchase orders</caption></table>');
    stubOverflow(wrap, 1200, 600);
    enhanceScrollWrap(wrap);
    expect(wrap.getAttribute('tabindex')).toBe('0');

    // Viewport grows (or a column is removed) so the wrap now fits.
    stubOverflow(wrap, 600, 600);
    enhanceScrollWrap(wrap);

    expect(wrap.hasAttribute('tabindex')).toBe(false);
    expect(wrap.hasAttribute('role')).toBe(false);
    expect(wrap.hasAttribute('aria-label')).toBe(false);
    expect(wrap.hasAttribute('data-a11y-scroll')).toBe(false);
  });

  it('re-adds tabindex if the wrap becomes overflowing again after being retracted', () => {
    const wrap = buildWrap('<table><caption>Sales orders</caption></table>');
    stubOverflow(wrap, 1200, 600);
    enhanceScrollWrap(wrap);
    stubOverflow(wrap, 600, 600);
    enhanceScrollWrap(wrap);
    expect(wrap.hasAttribute('tabindex')).toBe(false);

    stubOverflow(wrap, 1200, 600);
    enhanceScrollWrap(wrap);

    expect(wrap.getAttribute('tabindex')).toBe('0');
    expect(wrap.getAttribute('role')).toBe('region');
    expect(wrap.getAttribute('aria-label')).toBe('Sales orders');
  });
});

describe('ScrollableRegionService (app-wide scan)', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    document.body.innerHTML = '';
  });

  it('enhances every .erp-table-wrap already in the document on scan()', () => {
    document.body.innerHTML = `
      <div class="erp-table-wrap" id="w1"><table><caption>Customers</caption></table></div>
      <div class="erp-table-wrap" id="w2"><table><caption>Suppliers</caption></table></div>
    `;
    const w1 = document.getElementById('w1')!;
    const w2 = document.getElementById('w2')!;
    stubOverflow(w1, 1000, 500);
    stubOverflow(w2, 500, 500); // fits — should stay untouched

    const service = TestBed.inject(ScrollableRegionService);
    service.scan();

    expect(w1.getAttribute('tabindex')).toBe('0');
    expect(w1.getAttribute('aria-label')).toBe('Customers');
    expect(w2.hasAttribute('tabindex')).toBe(false);

    service.ngOnDestroy();
  });

  it('re-scans and picks up a wrap added to the DOM after rows load asynchronously', async () => {
    document.body.innerHTML = '<div id="host"></div>';
    const service = TestBed.inject(ScrollableRegionService);
    service.start();

    const host = document.getElementById('host')!;
    host.innerHTML = '<div class="erp-table-wrap" id="late"><table><caption>Late rows</caption></table></div>';
    const late = document.getElementById('late')!;
    stubOverflow(late, 900, 400);

    // MutationObserver callbacks + our 50ms debounce both fire on the macrotask queue, so this has
    // to wait for a real delay rather than flushing microtasks. It used to wait a flat 100ms —
    // twice the debounce, which is fine on an idle machine and not fine on a loaded one. It failed
    // in a full run while the machine was busy and passed on its own moments later, which is the
    // worst way for a gate to behave: it trains everyone to press retry, and the day it catches a
    // real a11y regression nobody will believe it.
    //
    // Polling for the outcome instead is still fast in the normal case (one or two ticks) and
    // survives a starved CPU, which is exactly the condition CI runs under.
    await waitFor(() => late.getAttribute('tabindex') === '0');

    expect(late.getAttribute('tabindex')).toBe('0');
    expect(late.getAttribute('role')).toBe('region');
    expect(late.getAttribute('aria-label')).toBe('Late rows');

    service.ngOnDestroy();
  });
});
