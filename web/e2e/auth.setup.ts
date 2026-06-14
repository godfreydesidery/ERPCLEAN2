/**
 * Playwright global setup — authenticate ONCE and save storage state.
 *
 * The app persists session in sessionStorage (erp.accessToken, erp.refreshToken, erp.user,
 * erp.permissions, erp.activeBranchUid). Playwright's built-in storageState only serialises
 * cookies + localStorage; sessionStorage is tab-scoped and not captured. To reuse auth across
 * parallel workers we:
 *   1. Log in via the real form → wait for /admin.
 *   2. Copy all erp.* sessionStorage entries into localStorage so they survive context restore.
 *   3. Save the storageState (which now includes the copied localStorage entries).
 *
 * The shell's SessionStore reads from sessionStorage on init; the companion auth.setup helper
 * (injected via initScript in the chromium project) copies the localStorage snapshot back into
 * sessionStorage at page-load time, so every worker starts authenticated without a login call.
 *
 * State file: e2e/.auth/root.json (gitignored).
 */
import { test as setup, expect } from '@playwright/test';
import path from 'path';
import fs from 'fs';

export const AUTH_FILE = path.join(__dirname, '.auth', 'root.json');

const SESSION_KEYS = [
  'erp.accessToken',
  'erp.refreshToken',
  'erp.user',
  'erp.permissions',
  'erp.activeBranchUid',
];

const ROOT_USER = process.env['ROOT_USER'] ?? 'rootadmin';
const ROOT_PASS = process.env['ROOT_PASS'] ?? 'RootPass12345';

setup('authenticate as rootadmin', async ({ page }) => {
  await page.goto('/login');
  await page.fill('input[name="username"]', ROOT_USER);
  await page.fill('input[name="password"]', ROOT_PASS);
  await page.click('button[type="submit"]');
  await expect(page).toHaveURL(/\/admin/, { timeout: 20_000 });

  // Copy sessionStorage → localStorage so Playwright's storageState can capture it.
  await page.evaluate((keys: string[]) => {
    for (const k of keys) {
      const v = sessionStorage.getItem(k);
      if (v !== null) localStorage.setItem(k, v);
    }
  }, SESSION_KEYS);

  // Ensure the .auth directory exists before saving.
  const authDir = path.dirname(AUTH_FILE);
  if (!fs.existsSync(authDir)) fs.mkdirSync(authDir, { recursive: true });

  await page.context().storageState({ path: AUTH_FILE });
});
