// Playwright checks for the Orbix App PWA build.
//
//   (once)  npm install                      in mobile_exec/pwa_test
//   build   dist\build-pwa.ps1               produces mobile_exec/build/web
//   run     npx playwright test              in mobile_exec/pwa_test
//
// The ERP API must answer on http://127.0.0.1:8081 for the sign-in test
// (ORBIX_API overrides). No account is needed: it signs in with a WRONG
// password on purpose and checks the error comes back through the proxy.
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.spec\.mjs$/,
  timeout: 120_000,
  expect: { timeout: 30_000 },
  // The update test flips server-wide state (a "new deploy"), so run serially.
  workers: 1,
  fullyParallel: false,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:4310',
    viewport: { width: 1280, height: 800 },
    serviceWorkers: 'allow',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'node serve.mjs',
    url: 'http://localhost:4310/manifest.webmanifest',
    env: { PORT: '4310', ORBIX_TEST_HOOKS: '1' },
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
