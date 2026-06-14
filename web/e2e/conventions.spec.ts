/**
 * L3 — Convention gates (data-driven across list/detail routes).
 *
 *  C1: a uid (26-char ULID) is a machine id — it must NEVER appear in visible UI text
 *      (it belongs only in the URL path). Scans each screen's visible body text.
 *  C6: WCAG 2.1 AA — axe scan, fail on serious/critical violations.
 *
 * Auth: uses the pre-saved storageState from auth.setup.ts (no per-test login).
 * Workers share the authenticated token, eliminating concurrent /login race timeouts.
 *
 * Each route is its own test → per-route issues in docs/testing/ISSUES.md.
 */
import { test, expect } from './_test-authenticated';
import AxeBuilder from '@axe-core/playwright';
import { ULID_RE } from './_helpers';
import routesRaw from './_routes.json';

const ROUTES: [string, string][] = (routesRaw as [string, string][])
  .filter(([p]) => p && !p.includes(':'))
  .filter((r, i, a) => a.findIndex((x) => x[0] === r[0]) === i);

test.describe('L3 conventions', () => {
  for (const [route] of ROUTES) {
    test(`C1 no uid visible on /admin/${route}`, async ({ page }) => {
      await page.goto(`/admin/${route}`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(500);
      const text = await page.locator('body').innerText().catch(() => '');
      const m = text.match(ULID_RE);
      expect(
        m,
        `C1 violation: a raw uid "${m?.[0]}" is visible on /admin/${route} (uids belong only in the URL; show a name instead)`,
      ).toBeNull();
    });

    test(`C6 axe a11y on /admin/${route}`, async ({ page }) => {
      await page.goto(`/admin/${route}`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(400);
      const results = await new AxeBuilder({ page })
        .disableRules(['color-contrast'])
        .analyze();
      const serious = results.violations.filter(
        (v) => v.impact === 'serious' || v.impact === 'critical',
      );
      const summary = serious.map((v) => `${v.id}(${v.nodes.length})`).join(', ');
      expect(serious, `axe serious/critical on /admin/${route}: ${summary}`).toHaveLength(0);
    });
  }
});
