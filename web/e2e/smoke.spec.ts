/**
 * Playwright smoke + axe e2e spec.
 *
 * PREREQUISITES (not part of `npm test`):
 *   - npm run e2e:install   (installs Chromium)
 *   - Backend running on :8081 (docker-compose up or mvn spring-boot:run)
 *   - ROOT_PASS env variable set to the root-admin password
 *   - npm run e2e  (or playwright test)
 *
 * Mirrors the flow in repo-root e2e/ui-smoke.js:
 *   1. GET /  → redirects to /login
 *   2. Fill login form → submit → land on /admin
 *   3. Crawl a couple of admin sub-routes
 *   4. Run an axe accessibility scan on a rendered page
 */
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const ROOT_PASS = process.env['ROOT_PASS'] ?? 'change-me';

test.describe('ERP web smoke', () => {
  test('login and reach the admin home page', async ({ page }) => {
    await page.goto('/');
    // The auth guard should redirect to /login
    await expect(page).toHaveURL(/\/login/);

    await page.fill('input[name="username"]', 'rootadmin');
    await page.fill('input[name="password"]', ROOT_PASS);
    await page.click('button[type="submit"]');

    // After login, the app routes to /admin
    await expect(page).toHaveURL(/\/admin/, { timeout: 15_000 });
  });

  test('navigate to customers list page', async ({ page }) => {
    // Login first
    await page.goto('/login');
    await page.fill('input[name="username"]', 'rootadmin');
    await page.fill('input[name="password"]', ROOT_PASS);
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/\/admin/, { timeout: 15_000 });

    await page.goto('/admin/customers');
    // Page must load — expect the heading or table to appear
    await expect(page.locator('h1, h2').first()).toBeVisible({ timeout: 10_000 });
  });

  test('navigate to fixed assets list page', async ({ page }) => {
    await page.goto('/login');
    await page.fill('input[name="username"]', 'rootadmin');
    await page.fill('input[name="password"]', ROOT_PASS);
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/\/admin/, { timeout: 15_000 });

    await page.goto('/admin/fixed-assets');
    await expect(page.locator('h1, h2').first()).toBeVisible({ timeout: 10_000 });
  });

  test('axe accessibility scan of the login page (no violations)', async ({ page }) => {
    await page.goto('/login');
    await expect(page.locator('form')).toBeVisible();

    const results = await new AxeBuilder({ page })
      .disableRules(['color-contrast']) // cannot compute in headless without a theme
      .analyze();

    expect(results.violations).toHaveLength(0);
  });

  test('axe accessibility scan of the admin home page after login (no violations)', async ({ page }) => {
    await page.goto('/login');
    await page.fill('input[name="username"]', 'rootadmin');
    await page.fill('input[name="password"]', ROOT_PASS);
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/\/admin/, { timeout: 15_000 });
    // Give the page a moment to fully render
    await page.waitForTimeout(500);

    const results = await new AxeBuilder({ page })
      .disableRules(['color-contrast'])
      .analyze();

    expect(results.violations).toHaveLength(0);
  });
});
