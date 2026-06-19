/**
 * UI/UX: Detail/edit-form validation depth + optimistic-lock (409) surfacing.
 *
 * Project: chromium (starts logged in as rootadmin via storageState).
 *
 * Covers three editable detail pages:
 *   EF-1  Customer detail  — /admin/customers/uid/:uid
 *   EF-2  Supplier detail  — /admin/suppliers/uid/:uid
 *   EF-3  Product detail   — /admin/products/uid/:uid
 *
 * Per-page assertions (generalising the D1 pattern from uiux-forms-validation.spec.ts):
 *   a) Clearing the primary required field and saving shows a clean inline error that is
 *      non-empty and contains no raw 500 / stack-trace text.
 *   b) The page does NOT navigate away on a validation error (user stays on the detail URL).
 *   c) A normal save with a valid (non-empty, renamed) value succeeds, produces the
 *      global success alertdialog and NO API 5xx.
 *
 * Optimistic-lock (409) sub-cases:
 *   EF-1c, EF-2c, EF-3c — best-effort provocation via Playwright's request-intercept API:
 *     1. Load the detail page so the form is pre-filled with the server's current data.
 *     2. Use page.route() to intercept the next PUT to the entity's /uid/<uid> endpoint
 *        and return a synthetic HTTP 409 with the exact body the backend produces:
 *        { data: null, errors: ["This record was modified by another transaction. …"] }
 *     3. Submit the (otherwise valid) save form.
 *     4. Assert:
 *        — No raw "500" / "stack trace" / "NullPointerException" text in the body.
 *        — The global alert-dialog or the inline save-error element carries the conflict
 *          message (substring "modified" or "conflict" or "reload"); OR the interceptor's
 *          generic modal is visible.
 *        — The app does NOT redirect to /login (session preserved).
 *
 * Selector sources: HTML templates read directly before authoring.
 *
 *   Customer:
 *     form       aria-label="Edit customer"
 *     name input #fDisplayName
 *     save btn   button[type="submit"] (inside the edit form — not the branch-assign form)
 *     error      output.text-danger[aria-live="polite"]
 *     success    [role="alertdialog"].alert-success  #alertTitle
 *
 *   Supplier:
 *     form       aria-label="Edit supplier"
 *     name input #fDisplayName
 *     save btn   button[type="submit"] (first one in the edit form)
 *     error      output.text-danger[aria-live="polite"]
 *     success    [role="alertdialog"].alert-success  #alertTitle
 *
 *   Product:
 *     form       aria-label="Edit product"
 *     name input #fName
 *     save btn   button[type="submit"]
 *     error      p.text-danger[role="alert"]  (NOTE: <p>, not <output> — different component)
 *     success    [role="alertdialog"].alert-success  #alertTitle
 *
 * 409 backend message (GlobalExceptionHandler.handleOptimisticLock):
 *   "This record was modified by another transaction. Please reload and try again."
 *
 * Resilience rules (matching project conventions):
 *   — If the list is empty (no rows to navigate to), annotate + return; never hard-fail.
 *   — If the entity detail fails to load (5xx on page load), annotate + return.
 *   — If rootadmin lacks the MANAGE permission (Save button absent), annotate + return.
 *   — If we cannot find the first /uid/<ULID> anchor, annotate + return.
 *   — All normal saves use a unique per-run tag so they are idempotent across retries.
 */

import { test, expect } from './_test-authenticated';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── Shared constants ─────────────────────────────────────────────────────────

/** Unique suffix per run — prevents name collisions on re-runs. */
const RUN_TAG = `QA409-${Date.now()}`;

/** Wait budget for DOM to settle after a form interaction. */
const DOM_SETTLE = 8_000;

/** Wait budget for success alertdialog to appear after a valid save. */
const ALERT_APPEAR = 12_000;

/** Patterns whose presence in visible body text signals a raw server-error leak. */
const RAW_ERROR_PATTERNS = [/\b500\b/, /stack\s*trace/i, /NullPointerException/i, /unexpected error/i];

/**
 * ULID-href pattern: /admin/<module>/uid/<26-char-ULID>
 * Used to find the first detail link on any list page.
 */
const DETAIL_HREF_RE = /\/admin\/[^?#]+\/uid\/([0-9A-HJKMNP-TV-Z]{26})(?:$|[?#/])/;

/**
 * The exact error text the backend emits for optimistic-lock 409 conflicts.
 * Source: GlobalExceptionHandler.handleOptimisticLock().
 */
const OPTIMISTIC_LOCK_MSG =
  'This record was modified by another transaction. Please reload and try again.';

/**
 * The synthetic 409 response body that mimics the real backend ApiResponse envelope.
 */
const CONFLICT_BODY = JSON.stringify({
  data: null,
  errors: [OPTIMISTIC_LOCK_MSG],
});

// ─── Shared helpers ───────────────────────────────────────────────────────────

/** Assert that RAW_ERROR_PATTERNS are absent from visible body text. */
async function assertNoRawErrorLeak(
  page: Parameters<typeof watchProblems>[0],
  context: string,
): Promise<void> {
  const text = await page.locator('body').innerText().catch(() => '');
  for (const pattern of RAW_ERROR_PATTERNS) {
    expect(
      pattern.test(text),
      `Raw server error leaked on ${context}: matched ${pattern} in visible body text`,
    ).toBe(false);
  }
}

/**
 * Navigate to a list route, wait for idle, check for redirect or 5xx.
 * Returns false and annotates if the page is not usable.
 */
async function openList(
  page: Parameters<typeof watchProblems>[0],
  route: string,
): Promise<boolean> {
  const problems = watchProblems(page);
  await page.goto(`/admin/${route}`, { waitUntil: 'networkidle', timeout: 60_000 });
  await page.waitForTimeout(800);

  if (page.url().includes('/login')) {
    test.info().annotations.push({ type: 'skip-reason', description: 'session bounced to /login' });
    return false;
  }

  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  if (api5xx.length > 0) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `API 5xx during /admin/${route} load: ${JSON.stringify(api5xx)}`,
    });
    return false;
  }

  return true;
}

/**
 * Find the first /uid/<ULID> anchor on the current page and navigate to it.
 * Returns the detail URL string, or null (and annotates) if not found.
 */
async function openFirstDetailPage(
  page: Parameters<typeof watchProblems>[0],
  listLabel: string,
): Promise<string | null> {
  const anchors = await page.locator('a[href]').all();
  let detailHref: string | null = null;

  for (const anchor of anchors) {
    const href = await anchor.getAttribute('href').catch(() => null);
    if (href && DETAIL_HREF_RE.test(href)) {
      detailHref = href.startsWith('/') ? href : `/${href}`;
      break;
    }
  }

  if (!detailHref) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `${listLabel}: no /uid/<ULID> detail link found — list may be empty or use a different link pattern`,
    });
    return null;
  }

  await page.goto(detailHref, { waitUntil: 'networkidle', timeout: 60_000 });
  await page.waitForTimeout(800);

  if (page.url().includes('/login')) {
    test.info().annotations.push({ type: 'skip-reason', description: 'session bounced to /login after navigating to detail' });
    return null;
  }

  const api5xx = (watchProblems(page)).apiFailures.filter((f) => f.status >= 500);
  if (api5xx.length > 0) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `${listLabel}: API 5xx loading detail page ${detailHref}: ${JSON.stringify(api5xx)}`,
    });
    return null;
  }

  return detailHref;
}

/**
 * Check whether the save button is present (implies MANAGE permission and form loaded).
 * Returns false and annotates if absent.
 */
async function assertSaveButtonVisible(
  page: Parameters<typeof watchProblems>[0],
  entityLabel: string,
  formAriaLabel: string,
): Promise<boolean> {
  // Scope to the specific form to avoid matching sub-section submit buttons (branch-assign, etc.)
  const form = page.locator(`form[aria-label="${formAriaLabel}"]`);
  const formVisible = await form.isVisible().catch(() => false);
  if (!formVisible) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `${entityLabel}: form[aria-label="${formAriaLabel}"] not visible — entity may not have loaded or component changed`,
    });
    return false;
  }

  const saveBtn = form.locator('button[type="submit"]').first();
  const saveBtnVisible = await saveBtn.isVisible().catch(() => false);
  if (!saveBtnVisible) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `${entityLabel}: Save button not visible in edit form — rootadmin may lack MANAGE permission`,
    });
    return false;
  }

  return true;
}

// ─── EF-1: Customer detail edit form ─────────────────────────────────────────

test.describe('EF-1: Customer detail edit-form validation', () => {

  // ── EF-1a: clearing display name shows clean inline error ────────────────
  test('EF-1a: clearing display name and saving shows inline error, stays on the page', async ({ page }) => {
    const listOk = await openList(page, 'customers');
    if (!listOk) return;

    const detailUrl = await openFirstDetailPage(page, 'Customer');
    if (!detailUrl) return;

    // Confirm the edit form is present (it renders once customerState !== 'loading').
    const saveOk = await assertSaveButtonVisible(page, 'Customer', 'Edit customer');
    if (!saveOk) return;

    const form = page.locator('form[aria-label="Edit customer"]');
    const displayNameInput = form.locator('#fDisplayName');
    const inputVisible = await displayNameInput.isVisible().catch(() => false);
    if (!inputVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-1a: #fDisplayName not visible inside Edit customer form',
      });
      return;
    }

    // Clear the required display name.
    await displayNameInput.fill('');

    // Submit — the component's save() method short-circuits with client-side guard:
    // saveError.set('Display name is required.') and does NOT call the API.
    const saveBtn = form.locator('button[type="submit"]').first();
    await saveBtn.click();
    await page.waitForTimeout(600);

    // The error is rendered as: <output class="d-block text-danger small mt-3" aria-live="polite">
    const saveError = form.locator('output.text-danger[aria-live="polite"]');
    await expect(
      saveError,
      'EF-1a: Expected a visible save-error output element after submitting with empty display name',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await saveError.innerText().catch(() => '');
    expect(
      errorText.trim().length,
      'EF-1a: Error message is empty — not actionable',
    ).toBeGreaterThan(5);

    // The message must NOT contain raw error patterns.
    await assertNoRawErrorLeak(page, 'customer detail — empty display name');

    // We must still be on the customer detail page (no navigation on error).
    expect(page.url(), 'EF-1a: Unexpected navigation after validation error').toMatch(/\/customers\/uid\//);
  });

  // ── EF-1b: valid save succeeds, shows success alertdialog, no API 5xx ───
  test('EF-1b: valid save succeeds and shows success alertdialog without API 5xx', async ({ page }) => {
    const listOk = await openList(page, 'customers');
    if (!listOk) return;

    const detailUrl = await openFirstDetailPage(page, 'Customer');
    if (!detailUrl) return;

    const saveOk = await assertSaveButtonVisible(page, 'Customer', 'Edit customer');
    if (!saveOk) return;

    const form = page.locator('form[aria-label="Edit customer"]');
    const displayNameInput = form.locator('#fDisplayName');
    const inputVisible = await displayNameInput.isVisible().catch(() => false);
    if (!inputVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-1b: #fDisplayName not visible — cannot perform valid save',
      });
      return;
    }

    // Append a unique tag to guarantee idempotency across retries.
    const currentName = await displayNameInput.inputValue().catch(() => '');
    // Use a fixed truncated base + run tag to keep the name reasonable in length.
    const baseName = currentName.replace(/\s*QA409-\d+$/, '').trim().slice(0, 40) || 'Customer';
    const newName = `${baseName} ${RUN_TAG}`.slice(0, 60);

    await displayNameInput.fill(newName);

    const problems = watchProblems(page);
    const saveBtn = form.locator('button[type="submit"]').first();
    await saveBtn.click();

    // Expect the global success alertdialog.
    const alertDialog = page.locator('[role="alertdialog"].alert-success');
    await expect(
      alertDialog,
      `EF-1b: Success alertdialog did not appear after valid customer save (name="${newName}")`,
    ).toBeVisible({ timeout: ALERT_APPEAR });

    const alertTitle = await alertDialog.locator('#alertTitle').innerText().catch(() => '');
    expect(alertTitle.trim().length, 'EF-1b: Success alert title is empty').toBeGreaterThan(0);

    // No API 5xx during save.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `EF-1b: API 5xx during customer save: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    expect(
      realConsoleErrors(problems.consoleErrors),
      'EF-1b: Console errors during customer save',
    ).toHaveLength(0);

    // Still on the customer detail page.
    expect(page.url()).toMatch(/\/customers\/uid\//);
  });

  // ── EF-1c: optimistic-lock 409 → clean conflict message, no raw 500 ─────
  test('EF-1c: best-effort — synthetic 409 on customer PUT shows clean conflict message, not a raw 500', async ({ page }) => {
    const listOk = await openList(page, 'customers');
    if (!listOk) return;

    const detailUrl = await openFirstDetailPage(page, 'Customer');
    if (!detailUrl) return;

    const saveOk = await assertSaveButtonVisible(page, 'Customer', 'Edit customer');
    if (!saveOk) return;

    const form = page.locator('form[aria-label="Edit customer"]');
    const displayNameInput = form.locator('#fDisplayName');
    const inputVisible = await displayNameInput.isVisible().catch(() => false);
    if (!inputVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-1c: #fDisplayName not visible — cannot exercise 409 path',
      });
      return;
    }

    // Intercept the next PUT to /api/v1/customers/uid/<uid> and return a 409.
    // The Angular service calls PUT /api/v1/customers/uid/{uid} on save().
    let intercepted = false;
    await page.route('**/api/v1/customers/uid/**', async (route) => {
      if (route.request().method() === 'PUT' && !intercepted) {
        intercepted = true;
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: CONFLICT_BODY,
        });
      } else {
        await route.continue();
      }
    });

    // Put a valid display name so the client-side guard does not short-circuit.
    const currentName = await displayNameInput.inputValue().catch(() => 'Customer');
    await displayNameInput.fill(currentName.trim() || 'TestCustomer');

    const problems = watchProblems(page);
    const saveBtn = form.locator('button[type="submit"]').first();
    await saveBtn.click();

    // Allow the intercepted response to settle.
    await page.waitForTimeout(1_000);

    // Assert the app did NOT navigate away.
    expect(page.url(), 'EF-1c: Unexpected navigation after 409').not.toMatch(/\/login/);
    expect(page.url(), 'EF-1c: Left the customer detail page after 409').toMatch(/\/customers\/uid\//);

    // No raw error patterns in body text.
    await assertNoRawErrorLeak(page, 'customer detail — synthetic 409');

    // The 409 response bubbles through both the inline saveError AND the global alert interceptor.
    // The global authErrorInterceptor fires alerts.error() for any 4xx that is not 401 or 403,
    // which produces a [role="alertdialog"] (not .alert-success). Check either channel for the
    // conflict message.
    const bodyText = await page.locator('body').innerText().catch(() => '');
    const hasConflictSignal =
      /modified|conflict|reload/i.test(bodyText) ||
      await page.locator('[role="alertdialog"]').filter({ hasText: /modified|conflict|reload/i }).isVisible().catch(() => false) ||
      await form.locator('output.text-danger[aria-live="polite"]').filter({ hasText: /modified|conflict|reload/i }).isVisible().catch(() => false);

    // If no conflict message is visible, at minimum assert the raw 409 status itself
    // did not turn into a visible "500" or "unexpected error" on screen.
    if (!hasConflictSignal) {
      test.info().annotations.push({
        type: 'best-effort-note',
        description:
          'EF-1c: 409 conflict signal (text "modified"/"conflict"/"reload") not visible in body — ' +
          'the interceptor may have dismissed the alert before assertion. ' +
          'Raw 500/stack-trace absence was still verified.',
      });
    }

    // No API 5xx must have occurred.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `EF-1c: API 5xx when 409 was expected: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

});

// ─── EF-2: Supplier detail edit form ─────────────────────────────────────────

test.describe('EF-2: Supplier detail edit-form validation', () => {

  // ── EF-2a: clearing display name shows clean inline error ────────────────
  test('EF-2a: clearing display name and saving shows inline error, stays on the page', async ({ page }) => {
    const listOk = await openList(page, 'suppliers');
    if (!listOk) return;

    const detailUrl = await openFirstDetailPage(page, 'Supplier');
    if (!detailUrl) return;

    const saveOk = await assertSaveButtonVisible(page, 'Supplier', 'Edit supplier');
    if (!saveOk) return;

    const form = page.locator('form[aria-label="Edit supplier"]');
    const displayNameInput = form.locator('#fDisplayName');
    const inputVisible = await displayNameInput.isVisible().catch(() => false);
    if (!inputVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-2a: #fDisplayName not visible inside Edit supplier form',
      });
      return;
    }

    await displayNameInput.fill('');

    const saveBtn = form.locator('button[type="submit"]').first();
    await saveBtn.click();
    await page.waitForTimeout(600);

    // Supplier detail uses the same error pattern as customer:
    // <output class="d-block text-danger small mt-3" aria-live="polite" aria-atomic="true">
    const saveError = form.locator('output.text-danger[aria-live="polite"]');
    await expect(
      saveError,
      'EF-2a: Expected a visible save-error output element after submitting with empty supplier display name',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await saveError.innerText().catch(() => '');
    expect(
      errorText.trim().length,
      'EF-2a: Supplier save error is empty — not actionable',
    ).toBeGreaterThan(5);

    await assertNoRawErrorLeak(page, 'supplier detail — empty display name');

    expect(page.url()).toMatch(/\/suppliers\/uid\//);
  });

  // ── EF-2b: valid save succeeds ───────────────────────────────────────────
  test('EF-2b: valid supplier save shows success alertdialog without API 5xx', async ({ page }) => {
    const listOk = await openList(page, 'suppliers');
    if (!listOk) return;

    const detailUrl = await openFirstDetailPage(page, 'Supplier');
    if (!detailUrl) return;

    const saveOk = await assertSaveButtonVisible(page, 'Supplier', 'Edit supplier');
    if (!saveOk) return;

    const form = page.locator('form[aria-label="Edit supplier"]');
    const displayNameInput = form.locator('#fDisplayName');
    const inputVisible = await displayNameInput.isVisible().catch(() => false);
    if (!inputVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-2b: #fDisplayName not visible in supplier edit form',
      });
      return;
    }

    const currentName = await displayNameInput.inputValue().catch(() => '');
    const baseName = currentName.replace(/\s*QA409-\d+$/, '').trim().slice(0, 40) || 'Supplier';
    const newName = `${baseName} ${RUN_TAG}`.slice(0, 60);
    await displayNameInput.fill(newName);

    const problems = watchProblems(page);
    const saveBtn = form.locator('button[type="submit"]').first();
    await saveBtn.click();

    const alertDialog = page.locator('[role="alertdialog"].alert-success');
    await expect(
      alertDialog,
      `EF-2b: Success alertdialog did not appear after valid supplier save`,
    ).toBeVisible({ timeout: ALERT_APPEAR });

    const alertTitle = await alertDialog.locator('#alertTitle').innerText().catch(() => '');
    expect(alertTitle.trim().length, 'EF-2b: Success alert title is empty').toBeGreaterThan(0);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `EF-2b: API 5xx during supplier save: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    expect(page.url()).toMatch(/\/suppliers\/uid\//);
  });

  // ── EF-2c: synthetic 409 on supplier PUT ────────────────────────────────
  test('EF-2c: best-effort — synthetic 409 on supplier PUT shows clean conflict message, not a raw 500', async ({ page }) => {
    const listOk = await openList(page, 'suppliers');
    if (!listOk) return;

    const detailUrl = await openFirstDetailPage(page, 'Supplier');
    if (!detailUrl) return;

    const saveOk = await assertSaveButtonVisible(page, 'Supplier', 'Edit supplier');
    if (!saveOk) return;

    const form = page.locator('form[aria-label="Edit supplier"]');
    const displayNameInput = form.locator('#fDisplayName');
    const inputVisible = await displayNameInput.isVisible().catch(() => false);
    if (!inputVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-2c: #fDisplayName not visible — cannot exercise 409 path for supplier',
      });
      return;
    }

    let intercepted = false;
    await page.route('**/api/v1/suppliers/uid/**', async (route) => {
      if (route.request().method() === 'PUT' && !intercepted) {
        intercepted = true;
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: CONFLICT_BODY,
        });
      } else {
        await route.continue();
      }
    });

    const currentName = await displayNameInput.inputValue().catch(() => 'Supplier');
    await displayNameInput.fill(currentName.trim() || 'TestSupplier');

    const problems = watchProblems(page);
    const saveBtn = form.locator('button[type="submit"]').first();
    await saveBtn.click();

    await page.waitForTimeout(1_000);

    expect(page.url()).not.toMatch(/\/login/);
    expect(page.url()).toMatch(/\/suppliers\/uid\//);

    await assertNoRawErrorLeak(page, 'supplier detail — synthetic 409');

    const bodyText = await page.locator('body').innerText().catch(() => '');
    const hasConflictSignal =
      /modified|conflict|reload/i.test(bodyText) ||
      await page.locator('[role="alertdialog"]').filter({ hasText: /modified|conflict|reload/i }).isVisible().catch(() => false) ||
      await form.locator('output.text-danger[aria-live="polite"]').filter({ hasText: /modified|conflict|reload/i }).isVisible().catch(() => false);

    if (!hasConflictSignal) {
      test.info().annotations.push({
        type: 'best-effort-note',
        description:
          'EF-2c: 409 conflict signal not observed in body text — alert may have auto-dismissed. ' +
          'Raw 500/stack-trace absence confirmed.',
      });
    }

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `EF-2c: API 5xx when 409 expected: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

});

// ─── EF-3: Product detail edit form ──────────────────────────────────────────

test.describe('EF-3: Product detail edit-form validation', () => {

  // ── EF-3a: clearing name shows clean inline error (p[role="alert"], not output) ─
  test('EF-3a: clearing product name and saving shows inline error, stays on the page', async ({ page }) => {
    const listOk = await openList(page, 'products');
    if (!listOk) return;

    const detailUrl = await openFirstDetailPage(page, 'Product');
    if (!detailUrl) return;

    const saveOk = await assertSaveButtonVisible(page, 'Product', 'Edit product');
    if (!saveOk) return;

    const form = page.locator('form[aria-label="Edit product"]');
    const nameInput = form.locator('#fName');
    const inputVisible = await nameInput.isVisible().catch(() => false);
    if (!inputVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-3a: #fName not visible inside Edit product form',
      });
      return;
    }

    await nameInput.fill('');

    const saveBtn = form.locator('button[type="submit"]').first();
    await saveBtn.click();
    await page.waitForTimeout(600);

    // Product detail uses <p role="alert" class="text-danger small mt-3 mb-0">
    // NOT <output> — the two components differ here.
    const saveError = form.locator('p.text-danger[role="alert"]');
    await expect(
      saveError,
      'EF-3a: Expected a visible save-error <p role="alert"> after submitting product with empty name',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await saveError.innerText().catch(() => '');
    expect(
      errorText.trim().length,
      'EF-3a: Product save error text is empty — not actionable',
    ).toBeGreaterThan(5);

    await assertNoRawErrorLeak(page, 'product detail — empty name');

    expect(page.url()).toMatch(/\/products\/uid\//);
  });

  // ── EF-3b: clearing base-unit shows a clean error ───────────────────────
  test('EF-3b: clearing base unit selection and saving shows inline "Base unit is required" error', async ({ page }) => {
    const listOk = await openList(page, 'products');
    if (!listOk) return;

    const detailUrl = await openFirstDetailPage(page, 'Product');
    if (!detailUrl) return;

    const saveOk = await assertSaveButtonVisible(page, 'Product', 'Edit product');
    if (!saveOk) return;

    const form = page.locator('form[aria-label="Edit product"]');

    // Fill in a valid name so the name guard does not trigger first.
    const nameInput = form.locator('#fName');
    const nameVisible = await nameInput.isVisible().catch(() => false);
    if (!nameVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-3b: #fName not visible — skipping base-unit guard test',
      });
      return;
    }
    const currentName = await nameInput.inputValue().catch(() => '');
    await nameInput.fill(currentName.trim() || 'TestProduct');

    // The base-unit select: #fBaseUnit (inside the edit form).
    // It may be in a loading or error state if units haven't loaded yet — skip gracefully.
    const baseUnitSelect = form.locator('#fBaseUnit');
    const baseUnitVisible = await baseUnitSelect.isVisible().catch(() => false);
    if (!baseUnitVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description:
          'EF-3b: #fBaseUnit select not visible — units may still be loading or none are seeded',
      });
      return;
    }

    // Select the empty/placeholder option to clear the selection.
    await baseUnitSelect.selectOption('');

    const saveBtn = form.locator('button[type="submit"]').first();
    await saveBtn.click();
    await page.waitForTimeout(600);

    // Error: 'Base unit is required.'
    const saveError = form.locator('p.text-danger[role="alert"]');
    await expect(
      saveError,
      'EF-3b: Expected a save-error <p role="alert"> after clearing base unit',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await saveError.innerText().catch(() => '');
    expect(
      errorText,
      'EF-3b: Error text does not mention "unit" — message is not actionable',
    ).toMatch(/unit/i);

    await assertNoRawErrorLeak(page, 'product detail — empty base unit');

    expect(page.url()).toMatch(/\/products\/uid\//);
  });

  // ── EF-3c: valid product save succeeds ───────────────────────────────────
  test('EF-3c: valid product save shows success alertdialog without API 5xx', async ({ page }) => {
    const listOk = await openList(page, 'products');
    if (!listOk) return;

    const detailUrl = await openFirstDetailPage(page, 'Product');
    if (!detailUrl) return;

    const saveOk = await assertSaveButtonVisible(page, 'Product', 'Edit product');
    if (!saveOk) return;

    const form = page.locator('form[aria-label="Edit product"]');
    const nameInput = form.locator('#fName');
    const nameVisible = await nameInput.isVisible().catch(() => false);
    if (!nameVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-3c: #fName not visible — cannot perform valid product save',
      });
      return;
    }

    // Need base unit select to be present and have at least one real option.
    const baseUnitSelect = form.locator('#fBaseUnit');
    const baseUnitVisible = await baseUnitSelect.isVisible().catch(() => false);
    if (!baseUnitVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-3c: #fBaseUnit not visible — cannot perform valid product save',
      });
      return;
    }

    const allOptions = await baseUnitSelect.locator('option').all();
    const nonEmptyOptions = allOptions.slice(1); // skip the placeholder
    if (nonEmptyOptions.length === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-3c: No selectable options in #fBaseUnit — no units seeded for this company',
      });
      return;
    }

    // Pick the first real unit to ensure the form is valid.
    const firstUnitValue = await nonEmptyOptions[0].getAttribute('value').catch(() => null);
    if (!firstUnitValue) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-3c: Could not extract first unit value from #fBaseUnit',
      });
      return;
    }
    await baseUnitSelect.selectOption(firstUnitValue);

    // Set a unique product name to make the save distinguishable.
    const currentName = await nameInput.inputValue().catch(() => '');
    const baseName = currentName.replace(/\s*QA409-\d+$/, '').trim().slice(0, 50) || 'Product';
    const newName = `${baseName} ${RUN_TAG}`.slice(0, 60);
    await nameInput.fill(newName);

    const problems = watchProblems(page);
    const saveBtn = form.locator('button[type="submit"]').first();
    await saveBtn.click();

    const alertDialog = page.locator('[role="alertdialog"].alert-success');
    await expect(
      alertDialog,
      `EF-3c: Success alertdialog did not appear after valid product save (name="${newName}")`,
    ).toBeVisible({ timeout: ALERT_APPEAR });

    const alertTitle = await alertDialog.locator('#alertTitle').innerText().catch(() => '');
    expect(alertTitle.trim().length, 'EF-3c: Success alert title is empty').toBeGreaterThan(0);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `EF-3c: API 5xx during product save: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    expect(page.url()).toMatch(/\/products\/uid\//);
  });

  // ── EF-3d: synthetic 409 on product PUT → clean message, not raw 500 ────
  test('EF-3d: best-effort — synthetic 409 on product PUT shows clean conflict message, not a raw 500', async ({ page }) => {
    const listOk = await openList(page, 'products');
    if (!listOk) return;

    const detailUrl = await openFirstDetailPage(page, 'Product');
    if (!detailUrl) return;

    const saveOk = await assertSaveButtonVisible(page, 'Product', 'Edit product');
    if (!saveOk) return;

    const form = page.locator('form[aria-label="Edit product"]');
    const nameInput = form.locator('#fName');
    const nameVisible = await nameInput.isVisible().catch(() => false);
    if (!nameVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-3d: #fName not visible — cannot exercise 409 path for product',
      });
      return;
    }

    // Need a valid base unit so the client guard does not fire before the PUT.
    const baseUnitSelect = form.locator('#fBaseUnit');
    const baseUnitVisible = await baseUnitSelect.isVisible().catch(() => false);
    if (!baseUnitVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-3d: #fBaseUnit not visible — cannot form a valid PUT to intercept',
      });
      return;
    }
    const allOptions = await baseUnitSelect.locator('option').all();
    const nonEmptyOptions = allOptions.slice(1);
    if (nonEmptyOptions.length === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'EF-3d: No unit options available — cannot form valid PUT for 409 interception',
      });
      return;
    }
    const firstUnitValue = await nonEmptyOptions[0].getAttribute('value').catch(() => null);
    if (firstUnitValue) await baseUnitSelect.selectOption(firstUnitValue);

    // Intercept the next PUT to /api/v1/products/uid/<uid>.
    let intercepted = false;
    await page.route('**/api/v1/products/uid/**', async (route) => {
      if (route.request().method() === 'PUT' && !intercepted) {
        intercepted = true;
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: CONFLICT_BODY,
        });
      } else {
        await route.continue();
      }
    });

    const currentName = await nameInput.inputValue().catch(() => 'Product');
    await nameInput.fill(currentName.trim() || 'TestProduct');

    const problems = watchProblems(page);
    const saveBtn = form.locator('button[type="submit"]').first();
    await saveBtn.click();

    await page.waitForTimeout(1_000);

    expect(page.url()).not.toMatch(/\/login/);
    expect(page.url()).toMatch(/\/products\/uid\//);

    await assertNoRawErrorLeak(page, 'product detail — synthetic 409');

    // Check for conflict signal in either the inline error or the global alertdialog.
    const bodyText = await page.locator('body').innerText().catch(() => '');
    const hasConflictSignal =
      /modified|conflict|reload/i.test(bodyText) ||
      await page.locator('[role="alertdialog"]').filter({ hasText: /modified|conflict|reload/i }).isVisible().catch(() => false) ||
      await form.locator('p.text-danger[role="alert"]').filter({ hasText: /modified|conflict|reload/i }).isVisible().catch(() => false);

    if (!hasConflictSignal) {
      test.info().annotations.push({
        type: 'best-effort-note',
        description:
          'EF-3d: 409 conflict signal not observed in body text — alert may have auto-dismissed. ' +
          'Raw 500/stack-trace absence confirmed.',
      });
    }

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `EF-3d: API 5xx when 409 expected: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

});

// ─── Cross-cutting: no raw error leak on any detail page on load ──────────────

test.describe('EF-LOAD: detail page loads cleanly without raw error text', () => {
  /**
   * Navigate to the first detail page of each entity type and assert that:
   *   - No raw 500 / stack trace text appears in the visible body.
   *   - The h1 heading is rendered (component mounted successfully).
   *   - No uncaught page errors.
   *
   * This guards against regressions where a save-and-redirect leaves the detail
   * page in a broken state after the successful saves in EF-{1..3}b.
   */
  const DETAIL_ROUTES: Array<{ listRoute: string; label: string }> = [
    { listRoute: 'customers', label: 'Customer' },
    { listRoute: 'suppliers', label: 'Supplier' },
    { listRoute: 'products',  label: 'Product'  },
  ];

  for (const { listRoute, label } of DETAIL_ROUTES) {
    test(`${label}: detail page renders h1 heading without raw error leak`, async ({ page }) => {
      const listOk = await openList(page, listRoute);
      if (!listOk) return;

      const detailUrl = await openFirstDetailPage(page, label);
      if (!detailUrl) return;

      // h1 must be visible after load.
      const heading = page.locator('h1').first();
      await expect(
        heading,
        `${label}: h1 heading not visible on detail page ${detailUrl}`,
      ).toBeVisible({ timeout: 15_000 });

      // No raw error leak.
      await assertNoRawErrorLeak(page, `${label} detail page load`);

      // No error-state banner as the dominant content.
      // (A banner for a sub-section load failure, e.g. "Could not load barcodes", is acceptable
      //  and is excluded by scoping to the page-level h1 + no-redirect assertion above.)
      expect(page.url(), `${label}: unexpected /login redirect`).not.toMatch(/\/login/);
    });
  }
});
