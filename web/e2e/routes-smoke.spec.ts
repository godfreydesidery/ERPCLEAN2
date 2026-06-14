/**
 * L2 — Route smoke (data-driven over every admin route with a permission).
 *
 * As rootadmin (sees everything), visit each /admin/<route> and assert the screen comes up healthy:
 *   - no API 5xx (or any /api/ 4xx that breaks the on-load) — the highest-signal defect class
 *   - no uncaught page error / console error
 *   - the session wasn't lost (didn't bounce to /login)
 *   - an error-state banner is not the dominant content
 * Each route is its own test so failures are isolated and reported per-route → docs/testing/ISSUES.md.
 *
 * Prereqs: backend :8081 (dev, bootstrapped) + seeded data + ng serve :4200. ROOT_PASS env.
 */
import { test, expect } from '@playwright/test';
import { login, watchProblems, realConsoleErrors } from './_helpers';
import routesRaw from './_routes.json';

const ROUTES: [string, string][] = (routesRaw as [string, string][])
  .filter(([p]) => p && !p.includes(':'))
  .filter((r, i, a) => a.findIndex((x) => x[0] === r[0]) === i); // dedupe by path

test.describe('L2 route smoke (rootadmin)', () => {
  for (const [route, perm] of ROUTES) {
    test(`route /admin/${route} loads cleanly [perm ${perm}]`, async ({ page }) => {
      const problems = watchProblems(page);
      await login(page);
      await page.goto(`/admin/${route}`, { waitUntil: 'networkidle' });

      // session preserved (not bounced to login)
      expect(page.url(), 'should not be redirected to /login').not.toMatch(/\/login/);

      // give async loads a beat to settle
      await page.waitForTimeout(600);

      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      const api4xx = problems.apiFailures.filter((f) => f.status >= 400 && f.status < 500);
      const consoleErrs = realConsoleErrors(problems.consoleErrors);

      // hard failures: any 5xx, any uncaught page error, or a 4xx on the screen's own load
      const detail =
        `route=/admin/${route}\n` +
        `api5xx=${JSON.stringify(api5xx)}\n` +
        `api4xx=${JSON.stringify(api4xx)}\n` +
        `pageErrors=${JSON.stringify(problems.pageErrors)}\n` +
        `consoleErrors=${JSON.stringify(consoleErrs)}`;

      expect(api5xx, `API 5xx during load — ${detail}`).toHaveLength(0);
      expect(problems.pageErrors, `uncaught page error — ${detail}`).toHaveLength(0);
      // a 4xx on a list/detail's own load is a real defect (e.g. ar/ageing requires a param the FE omits)
      expect(api4xx, `API 4xx during on-load — ${detail}`).toHaveLength(0);
      expect(consoleErrs, `console errors — ${detail}`).toHaveLength(0);
    });
  }
});
