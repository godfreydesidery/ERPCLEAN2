/**
 * UI/UX Auth & Session spec — chromium-unauthenticated project.
 *
 * Exercises the user-visible authentication flows end-to-end against the real Angular
 * app with no pre-loaded session state:
 *   1. Unauthenticated access to a protected route redirects to /login.
 *   2. Wrong-password login shows a visible inline error; stays on /login.
 *   3. Empty-field submit shows an inline validation error; stays on /login.
 *   4. Valid login navigates to /admin and renders the app shell (brand + topbar).
 *   5. Reload after login keeps the session (sessionStorage survives; still on /admin).
 *   6. Logout returns to /login; a subsequent protected-route visit redirects to /login.
 *
 * Auth model:
 *   - This spec belongs to the 'chromium-unauthenticated' project (no storageState).
 *   - Each test starts with a fresh, unauthenticated context.
 *   - Valid credentials come from ROOT_PASS env (default: 'RootPass12345').
 *
 * Selectors are derived from the real component sources:
 *   - login.component.html: input[name="username"], input[name="password"],
 *     button[type="submit"], div.alert.alert-danger[role="alert"]
 *   - shell.component.html: header.topbar, .topbar-brand, .brand-word,
 *     button[aria-label="Sign out"] (the always-visible logout button)
 */
import { test, expect } from '@playwright/test';

// ── credentials ───────────────────────────────────────────────────────────────

const ROOT_USER = process.env['ROOT_USER'] ?? 'rootadmin';
const ROOT_PASS = process.env['ROOT_PASS'] ?? 'RootPass12345';

// ── selectors ─────────────────────────────────────────────────────────────────

/** The inline error/validation message rendered by the login form. */
const LOGIN_ERROR_ALERT = 'div.alert.alert-danger[role="alert"]';

/** The always-visible sign-out button in the shell topbar. */
const LOGOUT_BTN = 'button[aria-label="Sign out"]';

/** The brand link element present in the authenticated shell topbar. */
const TOPBAR_BRAND = '.topbar-brand';

/** "ERP" brand word in the shell topbar (visible on sm+ viewports; desktop Chrome always shows it). */
const BRAND_WORD = '.brand-word';

// ── helpers ───────────────────────────────────────────────────────────────────

/**
 * Fill and submit the login form without waiting for a post-submit assertion.
 * Callers assert the outcome themselves.
 */
async function fillAndSubmitLogin(
  page: import('@playwright/test').Page,
  username: string,
  password: string,
): Promise<void> {
  await page.fill('input[name="username"]', username);
  await page.fill('input[name="password"]', password);
  await page.click('button[type="submit"]');
}

// ── test suite ────────────────────────────────────────────────────────────────

test.describe('UI/UX: Authentication & Session management', () => {

  // ── TC-AUTH-01 ─────────────────────────────────────────────────────────────
  test(
    'TC-AUTH-01: unauthenticated visit to a protected route redirects to /login',
    async ({ page }) => {
      // Navigate directly to a well-known protected page — no prior login in this context.
      await page.goto('/admin/customers', { waitUntil: 'domcontentloaded' });

      // The auth guard must have redirected before Angular renders any admin content.
      await expect(page, 'expected redirect to /login from /admin/customers').toHaveURL(
        /\/login/,
        { timeout: 10_000 },
      );

      // The login form must be visible so the user is not left on a blank screen.
      await expect(
        page.locator('form[aria-label="Sign in"]'),
        'login form not visible after auth-guard redirect',
      ).toBeVisible({ timeout: 5_000 });
    },
  );

  // ── TC-AUTH-02 ─────────────────────────────────────────────────────────────
  test(
    'TC-AUTH-02: login with wrong password shows inline error and stays on /login',
    async ({ page }) => {
      await page.goto('/login', { waitUntil: 'networkidle' });

      await fillAndSubmitLogin(page, ROOT_USER, 'definitely-wrong-password-12345!');

      // The inline error alert must appear — the component renders it as
      // div.alert.alert-danger[role="alert"] when auth.login() errors.
      await expect(
        page.locator(LOGIN_ERROR_ALERT),
        'expected an inline error alert after wrong-password login attempt',
      ).toBeVisible({ timeout: 15_000 });

      // The error text must be non-empty (don't prescribe the exact copy — it varies
      // by API response, but it must not be blank).
      const errorText = await page.locator(LOGIN_ERROR_ALERT).innerText();
      expect(
        errorText.trim().length,
        'error alert is visible but empty — user sees a blank red box',
      ).toBeGreaterThan(0);

      // URL must still be /login — no navigation away.
      expect(page.url(), 'page navigated away from /login after wrong-password attempt').toMatch(
        /\/login/,
      );

      // The form must still be present so the user can retry.
      await expect(
        page.locator('form[aria-label="Sign in"]'),
        'login form disappeared after failed login — user cannot retry',
      ).toBeVisible();
    },
  );

  // ── TC-AUTH-03 ─────────────────────────────────────────────────────────────
  test(
    'TC-AUTH-03: submitting empty fields shows validation error and stays on /login',
    async ({ page }) => {
      await page.goto('/login', { waitUntil: 'networkidle' });

      // Confirm the submit button is initially enabled (the component only disables it
      // while submitting(), not on empty fields before first submit).
      const submitBtn = page.locator('button[type="submit"]');
      await expect(submitBtn, 'submit button should be enabled on page load').toBeEnabled();

      // Submit the form without filling any field.
      await submitBtn.click();

      // The component calls error.set('Enter your username and password.') synchronously —
      // no network call is made, so the error appears immediately.
      await expect(
        page.locator(LOGIN_ERROR_ALERT),
        'expected an inline validation error after submitting empty fields',
      ).toBeVisible({ timeout: 3_000 });

      const errorText = await page.locator(LOGIN_ERROR_ALERT).innerText();
      expect(
        errorText.trim().length,
        'validation error alert is empty — user sees no message',
      ).toBeGreaterThan(0);

      // User must remain on /login.
      expect(page.url(), 'navigated away from /login after empty-field submit').toMatch(
        /\/login/,
      );

      // The form fields must still be interactive so the user can type credentials.
      await expect(
        page.locator('input[name="username"]'),
        'username input not visible/enabled after empty-field submit',
      ).toBeVisible();
      await expect(
        page.locator('input[name="password"]'),
        'password input not visible after empty-field submit',
      ).toBeVisible();
    },
  );

  // ── TC-AUTH-04 ─────────────────────────────────────────────────────────────
  test(
    'TC-AUTH-04: valid login navigates to /admin and renders the app shell',
    async ({ page }) => {
      await page.goto('/login', { waitUntil: 'networkidle' });

      await fillAndSubmitLogin(page, ROOT_USER, ROOT_PASS);

      // After successful login the router navigates to /admin.
      await expect(page, 'expected navigation to /admin after successful login').toHaveURL(
        /\/admin/,
        { timeout: 20_000 },
      );

      // The shell topbar must be rendered — it wraps the whole admin area.
      await expect(
        page.locator('header.topbar'),
        'shell topbar not visible after login',
      ).toBeVisible({ timeout: 10_000 });

      // The brand link must be present (confirms the shell component rendered).
      await expect(
        page.locator(TOPBAR_BRAND),
        'topbar brand link not visible after login',
      ).toBeVisible();

      // The "ERP" brand word text must be present.
      await expect(
        page.locator(BRAND_WORD),
        '"ERP" brand word not visible in topbar after login',
      ).toBeVisible();

      await expect(
        page.locator(BRAND_WORD),
        'brand word text is not "ERP"',
      ).toHaveText('ERP');

      // No error alert should be visible in the admin shell immediately after login.
      const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(
        alertVisible,
        'an error-state banner is visible immediately after login — possible shell initialisation failure',
      ).toBe(false);
    },
  );

  // ── TC-AUTH-05 ─────────────────────────────────────────────────────────────
  test(
    'TC-AUTH-05: reload after login keeps session — user stays on /admin, not bounced to /login',
    async ({ page }) => {
      // Step 1: log in.
      await page.goto('/login', { waitUntil: 'networkidle' });
      await fillAndSubmitLogin(page, ROOT_USER, ROOT_PASS);
      await expect(page, 'login must succeed before reload test').toHaveURL(
        /\/admin/,
        { timeout: 20_000 },
      );

      // Navigate to a concrete admin page so the post-reload assertion is unambiguous.
      await page.goto('/admin/customers', { waitUntil: 'networkidle' });
      expect(page.url(), 'should be on /admin/customers before reload').toMatch(
        /\/admin\/customers/,
      );

      // Step 2: reload the page (F5 equivalent). The app boots fresh, reads sessionStorage,
      // and SessionStore.isAuthenticated() is true → auth guard allows the route.
      await page.reload({ waitUntil: 'networkidle', timeout: 30_000 });

      // Must NOT be bounced to /login.
      expect(
        page.url(),
        'reload bounced the user to /login — session was lost (sessionStorage not populated)',
      ).not.toMatch(/\/login/);

      // Must still be within /admin (may have stayed on /admin/customers or redirected
      // to /admin root — both are acceptable).
      expect(
        page.url(),
        'reload landed outside /admin — session may have been cleared unexpectedly',
      ).toMatch(/\/admin/);

      // Shell topbar still present — confirms the authenticated shell rendered.
      await expect(
        page.locator('header.topbar'),
        'shell topbar not visible after reload — session may not have been restored',
      ).toBeVisible({ timeout: 10_000 });
    },
  );

  // ── TC-AUTH-06 ─────────────────────────────────────────────────────────────
  test(
    'TC-AUTH-06: logout returns to /login and a subsequent protected visit redirects to /login',
    async ({ page }) => {
      // Step 1: log in.
      await page.goto('/login', { waitUntil: 'networkidle' });
      await fillAndSubmitLogin(page, ROOT_USER, ROOT_PASS);
      await expect(page, 'login must succeed before logout test').toHaveURL(
        /\/admin/,
        { timeout: 20_000 },
      );

      // Confirm the logout button is present in the shell topbar.
      await expect(
        page.locator(LOGOUT_BTN),
        '"Sign out" button not found in topbar — cannot proceed with logout test',
      ).toBeVisible({ timeout: 10_000 });

      // Step 2: click the always-visible "Sign out" button.
      await page.locator(LOGOUT_BTN).click();

      // The shell calls auth.logout(), then router.navigateByUrl('/login').
      await expect(page, 'expected redirect to /login after logout').toHaveURL(
        /\/login/,
        { timeout: 15_000 },
      );

      // The login form must be visible again — user can log back in.
      await expect(
        page.locator('form[aria-label="Sign in"]'),
        'login form not visible after logout',
      ).toBeVisible({ timeout: 5_000 });

      // Step 3: attempt to visit a protected route in the same context (sessionStorage cleared).
      await page.goto('/admin/customers', { waitUntil: 'domcontentloaded' });

      // Auth guard detects no access token → redirects to /login again.
      await expect(
        page,
        'expected /login redirect after visiting /admin/customers post-logout',
      ).toHaveURL(/\/login/, { timeout: 10_000 });

      // The login form must be visible, not a blank page or 404.
      await expect(
        page.locator('form[aria-label="Sign in"]'),
        'login form not visible after post-logout protected-route redirect',
      ).toBeVisible({ timeout: 5_000 });
    },
  );

});
