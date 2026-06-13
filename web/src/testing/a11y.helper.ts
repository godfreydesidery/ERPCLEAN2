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
 */
export async function assertA11y<T>(fixture: ComponentFixture<T>): Promise<void> {
  fixture.detectChanges();
  const results = await axe(fixture.nativeElement as Element);
  // The cast is required because jest-axe types target jest's expect shape,
  // but vitest's extended expect is compatible at runtime.
  (expect(results) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();
}
