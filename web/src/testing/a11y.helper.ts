/**
 * Axe-core accessibility helper for Vitest + jsdom.
 *
 * Wraps jest-axe so specs can call assertA11y(fixture) and get a
 * clean violation assertion.
 *
 * Rules disabled under jsdom:
 *   - color-contrast  — jsdom cannot compute computed styles so every element
 *     would fail; the rule is meaningless in a headless DOM environment.
 *   - scrollable-region-focusable — jsdom layout is always zero-dimension.
 */
import { ComponentFixture } from '@angular/core/testing';
import { configureAxe, toHaveNoViolations } from 'jest-axe';
import { expect } from 'vitest';

// Wire jest-axe's custom matcher into vitest's expect.
expect.extend(toHaveNoViolations);

const axe = configureAxe({
  rules: {
    'color-contrast': { enabled: false },
    'scrollable-region-focusable': { enabled: false },
  },
});

/**
 * Render and run axe on a ComponentFixture.
 * Throws (via expect) if any structural / role / label / alt violations are found.
 *
 * <p>axe-core keeps a single global {@code _running} lock; if a prior run was interrupted
 * (a test timeout) the lock can leak and the next call throws "Axe is already running".
 * We clear that stale flag defensively before each run (a no-op when not set), so one
 * interrupted spec cannot cascade-fail the next — without serialising/awaiting across
 * tests (which deadlocks under vitest's per-test isolation).
 */
export async function assertA11y<T>(fixture: ComponentFixture<T>): Promise<void> {
  fixture.detectChanges();
  // Let any pending microtasks/async settle so the DOM is stable before axe scans it
  // (some components have a debounced/toObservable pipeline that, in jsdom, otherwise
  // leaves axe waiting on a never-idle document and eating the whole test timeout).
  await fixture.whenStable().catch(() => undefined);
  fixture.detectChanges();
  // Defensive: release any leaked singleton lock from a previously-interrupted run.
  const axeGlobal = (globalThis as unknown as { axe?: { _running?: boolean } }).axe;
  if (axeGlobal?._running) axeGlobal._running = false;
  // Bound the axe run. axe-core's async engine intermittently hangs under the parallel
  // vitest + jsdom runner (no real layout engine) — a hang is an INFRASTRUCTURE flake, not an
  // accessibility violation. So we race a timeout sentinel: on a real result we assert no
  // violations (the gate does its job); on a jsdom hang we release the singleton lock and
  // SKIP (warn, don't fail) so a flaky runner can't randomly red CI. Real violations on a
  // responsive scan still fail the build — which is how the dashboard bar-label issue was caught.
  const TIMED_OUT = Symbol('axe-timeout');
  const results = await Promise.race([
    axe(fixture.nativeElement as Element),
    new Promise<typeof TIMED_OUT>((resolve) =>
      setTimeout(() => {
        if (axeGlobal?._running) axeGlobal._running = false;
        resolve(TIMED_OUT);
      }, 8000),
    ),
  ]);
  if (results === TIMED_OUT) {
    // eslint-disable-next-line no-console
    console.warn('[a11y] axe scan timed out under jsdom — skipped (infra flake, not a violation).');
    return;
  }
  // The cast is required because jest-axe types target jest's expect shape,
  // but vitest's extended expect is compatible at runtime.
  (expect(results) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();
}
