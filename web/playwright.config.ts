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
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: process.env['CI'] ? 2 : 0,
  reporter: process.env['CI'] ? 'github' : 'list',

  use: {
    baseURL: 'http://localhost:4200',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  /**
   * Start `ng serve` before the test run.
   * reuseExistingServer lets a developer who already has `npm start` running
   * skip the startup time (or set PLAYWRIGHT_REUSE_SERVER=1 in CI to do the same).
   */
  webServer: {
    command: 'npm run start',
    url: 'http://localhost:4200',
    reuseExistingServer: !!process.env['PLAYWRIGHT_REUSE_SERVER'],
    timeout: 120_000,
  },
});
