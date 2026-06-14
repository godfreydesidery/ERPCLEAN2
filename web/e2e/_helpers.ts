/**
 * Shared Playwright helpers for the ERPCLEAN2 system e2e suite.
 * See docs/testing/TESTING-STRATEGY.md.
 */
import { Page, expect } from '@playwright/test';

export const ROOT_USER = process.env['ROOT_USER'] ?? 'rootadmin';
export const ROOT_PASS = process.env['ROOT_PASS'] ?? 'RootPass12345';

/** A ULID uid is 26 chars of Crockford base32 — must NEVER be visible in the UI (convention C1). */
export const ULID_RE = /\b[0-9A-HJKMNP-TV-Z]{26}\b/;

/** Log in via the real login form. Lands on /admin. */
export async function login(page: Page, user = ROOT_USER, pass = ROOT_PASS): Promise<void> {
  await page.goto('/login');
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/admin/, { timeout: 20_000 });
}

/** Collects console errors + failed /api/ responses while on a page, for issue evidence. */
export interface PageProblems {
  consoleErrors: string[];
  apiFailures: { url: string; status: number; body: string }[];
  pageErrors: string[];
}

export function watchProblems(page: Page): PageProblems {
  const p: PageProblems = { consoleErrors: [], apiFailures: [], pageErrors: [] };
  page.on('console', (m) => {
    if (m.type() === 'error') p.consoleErrors.push(m.text().slice(0, 300));
  });
  page.on('pageerror', (e) => p.pageErrors.push(String(e).slice(0, 300)));
  page.on('response', async (r) => {
    const u = r.url();
    if (u.includes('/api/') && r.status() >= 400) {
      let body = '';
      try { body = (await r.text()).slice(0, 200); } catch { /* ignore */ }
      p.apiFailures.push({ url: u.replace(/^https?:\/\/[^/]+/, ''), status: r.status(), body });
    }
  });
  return p;
}

/** Filter out noise console errors that are not product defects (favicon, ResizeObserver, etc.). */
export function realConsoleErrors(errs: string[]): string[] {
  return errs.filter(
    (e) =>
      !/favicon|ResizeObserver|Failed to load resource.*(404).*favicon|net::ERR_/.test(e),
  );
}
