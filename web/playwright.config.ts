/**
 * Playwright configuration for the ERP web app.
 *
 * Prerequisites (CI / opt-in, NOT part of `npm test`):
 *   1. npm run e2e:install   — installs Chromium browser
 *   2. Running backend on :8081  (docker-compose up or mvn spring-boot:run)
 *   3. npm run e2e           — spins up `ng serve` on :4200, runs specs
 *
 * The proxy.conf.json already forwards /api → :8081; Playwright's webServer
 * relies on that so no extra proxy config is needed here.
 *
 * Auth reuse strategy:
 *   The app stores session tokens in sessionStorage (erp.*). Playwright's storageState only
 *   captures cookies + localStorage, so a two-step bridge is used:
 *     1. auth.setup.ts logs in once, copies erp.* into localStorage, saves state to
 *        e2e/.auth/root.json (gitignored).
 *     2. The 'chromium' project loads the saved state (localStorage snapshot) and relies on
 *        _test-authenticated.ts, which registers a per-context addInitScript that copies the
 *        localStorage entries back into sessionStorage before Angular boots.
 *     3. Result: every parallel worker page starts with a valid session — no /login calls,
 *        no concurrent login races, no toHaveURL(/admin) flakes.
 *   The 'chromium-unauthenticated' project (smoke.spec.ts) has no storageState so its explicit
 *   login-form and redirect tests run from a cold unauthenticated session.
 */
import { defineConfig, devices } from '@playwright/test';
import path from 'path';

const AUTH_FILE = path.join(__dirname, 'e2e', '.auth', 'root.json');

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: process.env['CI'] ? 2 : 0,
  reporter: process.env['CI'] ? 'github' : 'list',

  use: {
    baseURL: process.env['PW_BASE_URL'] || 'http://localhost:4200',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    /**
     * Setup project: logs in once, copies erp.* from sessionStorage → localStorage,
     * then writes state to e2e/.auth/root.json.
     * Runs before any authenticated project.
     */
    {
      name: 'setup',
      testMatch: /auth\.setup\.ts/,
    },

    /**
     * Authenticated project for routes-smoke.spec.ts and conventions.spec.ts.
     * Starts each context with the saved localStorage snapshot (storageState).
     * The _test-authenticated fixture copies those entries into sessionStorage on every
     * page load → Angular's SessionStore sees a live session from frame 0.
     * No per-test login() calls; parallel workers all reuse the single saved token.
     */
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: AUTH_FILE,
      },
      dependencies: ['setup'],
      testMatch: /\/(routes-smoke|conventions|massive-data)\.spec\.ts/,
    },

    /**
     * Unauthenticated project for smoke.spec.ts.
     * smoke.spec.ts tests the login form explicitly (redirect to /login, form submit,
     * axe scan of the login page) — it requires a cold unauthenticated session.
     * No storageState, no initScript.
     */
    {
      name: 'chromium-unauthenticated',
      use: { ...devices['Desktop Chrome'] },
      testMatch: /\/smoke\.spec\.ts/,
    },
  ],

  /**
   * Start `ng serve` before the test run.
   * reuseExistingServer lets a developer who already has `npm start` running
   * skip the startup time (or set PLAYWRIGHT_REUSE_SERVER=1 in CI to do the same).
   */
  webServer: {
    command: 'npm run start',
    url: process.env['PW_BASE_URL'] || 'http://localhost:4200',
    reuseExistingServer: !!process.env['PLAYWRIGHT_REUSE_SERVER'],
    timeout: 120_000,
  },
});
