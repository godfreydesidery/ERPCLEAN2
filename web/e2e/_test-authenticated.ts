/**
 * Extended Playwright `test` for authenticated specs.
 *
 * The app stores the session in sessionStorage (erp.*). Playwright's storageState restores
 * cookies + localStorage only. This fixture bridges the gap by injecting an init script into
 * every page context that copies the saved localStorage entries back into sessionStorage before
 * Angular's main.ts boots — so SessionStore.isAuthenticated() returns true from frame 0.
 *
 * Usage: import { test, expect } from './_test-authenticated' in any authenticated spec.
 */
import { test as base, expect } from '@playwright/test';

const SESSION_KEYS = [
  'erp.accessToken',
  'erp.refreshToken',
  'erp.user',
  'erp.permissions',
  'erp.activeBranchUid',
];

// Inline script injected before every page load.
const RESTORE_SCRIPT = `
(function() {
  var keys = ${JSON.stringify(SESSION_KEYS)};
  for (var i = 0; i < keys.length; i++) {
    var v = localStorage.getItem(keys[i]);
    if (v !== null) sessionStorage.setItem(keys[i], v);
  }
})();
`;

export const test = base.extend<{ _restoreSession: void }>({
  _restoreSession: [
    async ({ context }, use) => {
      await context.addInitScript(RESTORE_SCRIPT);
      await use();
    },
    { auto: true }, // runs for every test automatically
  ],
});

export { expect };
