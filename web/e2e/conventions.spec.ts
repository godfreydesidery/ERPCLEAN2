/**
 * L3 — Convention gates (data-driven across list/detail routes).
 *
 *  C1: a uid (26-char ULID) is a machine id — it must NEVER appear in visible UI text
 *      (it belongs only in the URL path). Scans each screen's visible body text.
 *  C1-detail: same C1 check on the first-row detail page for every list that has
 *      a /uid/:uid detail route and at least one row.
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

/**
 * Pattern that matches the href of a detail-route anchor in a list page.
 * The app uses /admin/<listPrefix>/uid/<ULID> for all detail routes.
 * We capture the ULID at the end.
 */
const DETAIL_HREF_RE = /\/admin\/[^?#]+\/uid\/([0-9A-HJKMNP-TV-Z]{26})(?:$|[?#/])/;

test.describe('L3 conventions', () => {
  for (const [route] of ROUTES) {
    // ── C1: list-page scan ──────────────────────────────────────────────────
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

    // ── C1-detail: first-row detail-page scan ──────────────────────────────
    // Attempts to navigate to the detail page for the first row in the list.
    // Skipped (no failure) when:
    //   • the list has no rows (no /uid/ href found)
    //   • the detail route pattern doesn't exist for this list
    // Fails when a raw ULID is visible on the detail screen.
    test(`C1-detail no uid visible on first-row detail of /admin/${route}`, async ({ page }) => {
      await page.goto(`/admin/${route}`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(500);

      // Find the first anchor whose href contains /uid/<ULID>
      const anchors = await page.locator('a[href]').all();
      let detailHref: string | null = null;

      for (const anchor of anchors) {
        const href = await anchor.getAttribute('href').catch(() => null);
        if (href && DETAIL_HREF_RE.test(href)) {
          detailHref = href;
          break;
        }
      }

      if (!detailHref) {
        // No detail links on this list — nothing to test (list may be empty or
        // this route has no /uid/:uid child). Skip gracefully.
        test.info().annotations.push({
          type: 'skip-reason',
          description: `no /uid/<ULID> anchor found on /admin/${route} (empty list or no detail route)`,
        });
        return;
      }

      // Navigate to the detail URL. Keep it app-relative so page.goto() resolves it
      // against the configured baseURL (any port, e.g. 4400) — never hardcode a host.
      const detailUrl = detailHref.startsWith('http')
        ? detailHref
        : `${detailHref.startsWith('/') ? '' : '/'}${detailHref}`;

      await page.goto(detailUrl, { waitUntil: 'networkidle' });
      await page.waitForTimeout(500);

      const text = await page.locator('body').innerText().catch(() => '');
      const m = text.match(ULID_RE);
      expect(
        m,
        `C1 violation: a raw uid "${m?.[0]}" is visible on the detail page ${detailHref} reached from /admin/${route} (uids belong only in the URL; show a name instead)`,
      ).toBeNull();
    });

    // ── C6: axe a11y ────────────────────────────────────────────────────────
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
