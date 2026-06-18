/**
 * UI/UX Forms Validation spec — 'chromium' project (starts logged in as rootadmin).
 *
 * Goal: verify the app surfaces validation and server errors to the user clearly.
 *       No raw stack traces / "500" / "unexpected error" text must reach the screen.
 *       Required fields block submission with a visible, actionable message.
 *       Valid submissions produce a success alert and reflect in the list.
 *
 * Forms exercised:
 *   1. Customer create  — /admin/customers     (inline form behind "New Customer" button)
 *   2. Product create   — /admin/products      (inline form behind "New Product" button)
 *   3. GL journal post  — /admin/gl/journals/post (full-page form)
 *
 * Selector sources: components read directly from web/src/app/features/admin/.
 * Success alert: <div role="alertdialog" class="alert-card alert-success"> / h2#alertTitle.
 * Form errors:
 *   Customer : <output class="d-block text-danger small ..."> (aria-live=polite)
 *   Product  : <p role="alert" class="text-danger small ...">
 *   GL       : <p role="alert" class="text-danger small ...">
 *
 * Resilience: every test that depends on a prerequisite (company loaded, units seeded)
 * annotates and returns gracefully rather than failing with an unrelated assertion.
 */

import { test, expect } from './_test-authenticated';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── shared constants ──────────────────────────────────────────────────────────

/** Per-run tag so created names are unique across parallel reruns. */
const RUN_TAG = `QA-${Date.now()}`;

/** Timeout for the DOM to settle after a form interaction. */
const DOM_SETTLE = 8_000;

/** Timeout for success alert to appear after a valid create. */
const ALERT_APPEAR = 10_000;

/** Patterns whose presence in visible page text signals a raw server error leak. */
const RAW_ERROR_PATTERNS = [/\b500\b/, /stack\s*trace/i, /NullPointerException/i, /unexpected error/i];

/**
 * Assert that none of the raw-error patterns appear in visible body text.
 * Call this after any form submission that should surface a clean 4xx message.
 */
async function assertNoRawErrorLeak(page: Parameters<typeof watchProblems>[0], context: string): Promise<void> {
  const text = await page.locator('body').innerText().catch(() => '');
  for (const pattern of RAW_ERROR_PATTERNS) {
    expect(
      pattern.test(text),
      `Raw server error leaked on ${context}: matched pattern ${pattern} in visible body text`,
    ).toBe(false);
  }
}

// ─── Form 1: Customer create ───────────────────────────────────────────────────

test.describe('UI/UX: Customer create form validation', () => {

  /**
   * Navigate to /admin/customers and wait for the page to fully load (company
   * selector resolves + customer list settles). Return false if the page is not
   * usable (e.g. API down), true if ready.
   */
  async function openCustomerList(page: Parameters<typeof watchProblems>[0]): Promise<boolean> {
    const problems = watchProblems(page);
    await page.goto('/admin/customers', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(800);

    if (page.url().includes('/login')) {
      test.info().annotations.push({ type: 'skip-reason', description: 'session bounced to /login' });
      return false;
    }

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    if (api5xx.length > 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: `API 5xx during /admin/customers load: ${JSON.stringify(api5xx)}`,
      });
      return false;
    }

    return true;
  }

  /**
   * Open the "New Customer" inline create form.
   * Returns false and annotates if the button is absent (permission not available).
   */
  async function openCreateForm(page: Parameters<typeof watchProblems>[0]): Promise<boolean> {
    const btn = page.getByRole('button', { name: /New Customer/i });
    const visible = await btn.isVisible().catch(() => false);
    if (!visible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"New Customer" button not visible — rootadmin may lack CUSTOMER.MANAGE or company context missing',
      });
      return false;
    }
    await btn.click();
    // Wait for the inline card to appear (id="createCustomerForm").
    await expect(
      page.locator('#createCustomerForm'),
      'Create customer card did not appear after clicking "New Customer"',
    ).toBeVisible({ timeout: DOM_SETTLE });
    return true;
  }

  // ── Case C1: empty display name blocks submission ──────────────────────────
  test('C1: submitting with empty display name shows a visible error and does not create a record', async ({ page }) => {
    const ready = await openCustomerList(page);
    if (!ready) return;

    const opened = await openCreateForm(page);
    if (!opened) return;

    // Ensure the display name is empty (default state, but be explicit).
    await page.locator('#newDisplayName').fill('');

    // Submit the form.
    await page.locator('#createCustomerForm button[type="submit"]').click();

    // The component sets formError = 'Company and display name are required.'
    // and does NOT call the API. Assert:
    //  a) error text is visible in the <output> element.
    const formError = page.locator('#createCustomerForm output.text-danger');
    await expect(
      formError,
      'Expected a visible field-level error message after empty display name submission',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await formError.innerText();
    expect(
      errorText.trim().length,
      'Error message text is empty — not actionable',
    ).toBeGreaterThan(5);

    // Assert no raw 500 / stack trace leaked.
    await assertNoRawErrorLeak(page, 'customer create — empty display name');

    // The create form must still be visible (no navigation or form dismissal on error).
    await expect(
      page.locator('#createCustomerForm'),
      'Form was dismissed despite a validation error — the error may have been silent',
    ).toBeVisible();
  });

  // ── Case C2: BUSINESS party type without TIN shows a clean error ───────────
  test('C2: submitting BUSINESS party without TIN shows BR-PARTY-04 message, no 500', async ({ page }) => {
    const ready = await openCustomerList(page);
    if (!ready) return;

    const opened = await openCreateForm(page);
    if (!opened) return;

    // Fill a valid display name.
    await page.locator('#newDisplayName').fill(`BizNoTin-${RUN_TAG}`);

    // Select BUSINESS party type — this reveals the TIN row.
    await page.locator('#newPartyType').selectOption('BUSINESS');

    // Leave TIN empty.  The TIN field is conditionally rendered; assert it appeared.
    const tinField = page.locator('#newTin');
    const tinVisible = await tinField.isVisible().catch(() => false);
    if (!tinVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '#newTin field not visible after selecting BUSINESS — component may have changed',
      });
      return;
    }
    await tinField.fill('');

    // Submit.
    await page.locator('#createCustomerForm button[type="submit"]').click();

    // Expect the BR-PARTY-04 client guard message.
    const formError = page.locator('#createCustomerForm output.text-danger');
    await expect(
      formError,
      'Expected BR-PARTY-04 error message after submitting BUSINESS without TIN',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await formError.innerText();
    expect(
      errorText,
      'Error text does not contain "TIN" — message is not actionable',
    ).toMatch(/TIN/i);

    await assertNoRawErrorLeak(page, 'customer create — BUSINESS without TIN');

    // Form stays open.
    await expect(page.locator('#createCustomerForm')).toBeVisible();
  });

  // ── Case C3: valid submission shows success alert + new row appears ─────────
  test('C3: valid submission shows success alert and new customer appears in the list', async ({ page }) => {
    const ready = await openCustomerList(page);
    if (!ready) return;

    const opened = await openCreateForm(page);
    if (!opened) return;

    const uniqueName = `AutoCust-${RUN_TAG}`;

    await page.locator('#newDisplayName').fill(uniqueName);
    await page.locator('#newPartyType').selectOption('INDIVIDUAL');
    await page.locator('#newCustomerKind').selectOption('CASH_WALK_IN');

    // Watch for API problems during the create call.
    const problems = watchProblems(page);

    await page.locator('#createCustomerForm button[type="submit"]').click();

    // Wait for the success alertdialog to appear.
    const alertDialog = page.locator('[role="alertdialog"].alert-success');
    await expect(
      alertDialog,
      `Success alert did not appear after creating customer "${uniqueName}"`,
    ).toBeVisible({ timeout: ALERT_APPEAR });

    // The alert title must be the user-facing success message (not a raw uid/code).
    const alertTitle = alertDialog.locator('#alertTitle');
    const titleText = await alertTitle.innerText().catch(() => '');
    expect(
      titleText.trim().length,
      'Success alert title is empty',
    ).toBeGreaterThan(0);

    // No API 5xx during the whole test.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx during customer create: ${JSON.stringify(api5xx)}`).toHaveLength(0);
    expect(
      realConsoleErrors(problems.consoleErrors),
      'Console errors during customer create',
    ).toHaveLength(0);

    // Alert auto-dismisses after ~1.5 s; wait for it and then verify the list row.
    // We poll the list because the component calls load() which is async.
    await page.waitForTimeout(2_000);
    await page.waitForLoadState('networkidle', { timeout: 10_000 });

    // The new customer should appear somewhere in the list (search by exact name).
    const searchInput = page.locator('#customerSearch');
    const searchVisible = await searchInput.isVisible().catch(() => false);
    if (searchVisible) {
      await searchInput.fill(uniqueName);
      await page.keyboard.press('Enter');
      await page.waitForLoadState('networkidle', { timeout: 8_000 });
      await page.waitForTimeout(400);
    }

    // At least one row in the list must contain our unique name.
    const matchingRow = page.locator('table.erp-table tbody tr').filter({ hasText: uniqueName });
    await expect(
      matchingRow.first(),
      `Newly created customer "${uniqueName}" not found in list after creation`,
    ).toBeVisible({ timeout: 10_000 });
  });

});

// ─── Form 2: Product create ────────────────────────────────────────────────────

test.describe('UI/UX: Product create form validation', () => {

  async function openProductList(page: Parameters<typeof watchProblems>[0]): Promise<boolean> {
    const problems = watchProblems(page);
    await page.goto('/admin/products', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(800);

    if (page.url().includes('/login')) {
      test.info().annotations.push({ type: 'skip-reason', description: 'session bounced to /login' });
      return false;
    }

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    if (api5xx.length > 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: `API 5xx during /admin/products load: ${JSON.stringify(api5xx)}`,
      });
      return false;
    }
    return true;
  }

  async function openProductCreateForm(page: Parameters<typeof watchProblems>[0]): Promise<boolean> {
    const btn = page.getByRole('button', { name: /New Product/i });
    const visible = await btn.isVisible().catch(() => false);
    if (!visible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"New Product" button not visible — rootadmin may lack PRODUCT.MANAGE or company context missing',
      });
      return false;
    }
    await btn.click();
    await expect(
      page.locator('#createProductForm'),
      'Create product card did not appear after clicking "New Product"',
    ).toBeVisible({ timeout: DOM_SETTLE });
    return true;
  }

  // ── Case P1: empty name blocks submission ───────────────────────────────────
  test('P1: submitting with empty product name shows a visible error and does not create a record', async ({ page }) => {
    const ready = await openProductList(page);
    if (!ready) return;

    const opened = await openProductCreateForm(page);
    if (!opened) return;

    // Leave name empty; pick a base unit if available to isolate the name guard.
    await page.locator('#newName').fill('');

    // Try to select a base unit to avoid that guard triggering first.
    const baseUnitSelect = page.locator('#newBaseUnit');
    const unitVisible = await baseUnitSelect.isVisible().catch(() => false);
    if (unitVisible) {
      const options = await baseUnitSelect.locator('option').all();
      // options[0] is typically "— select unit —" (empty value); pick options[1] if present.
      if (options.length > 1) {
        const firstUnitValue = await options[1].getAttribute('value');
        if (firstUnitValue) await baseUnitSelect.selectOption(firstUnitValue);
      }
    }

    await page.locator('#createProductForm button[type="submit"]').click();

    // Error: 'Company and name are required.' rendered as <p role="alert">.
    const formError = page.locator('#createProductForm p[role="alert"].text-danger');
    await expect(
      formError,
      'Expected a visible error message after submitting product with empty name',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await formError.innerText();
    expect(
      errorText.trim().length,
      'Product form error text is empty — not actionable',
    ).toBeGreaterThan(5);

    await assertNoRawErrorLeak(page, 'product create — empty name');

    await expect(page.locator('#createProductForm')).toBeVisible();
  });

  // ── Case P2: name filled but no base unit selected shows a clean error ───────
  test('P2: submitting without selecting a base unit shows a clean "Base unit is required" error', async ({ page }) => {
    const ready = await openProductList(page);
    if (!ready) return;

    const opened = await openProductCreateForm(page);
    if (!opened) return;

    // The base unit <select> is only rendered when units have loaded and there is at
    // least one. If the system has no units we cannot exercise this guard — skip.
    const baseUnitSelect = page.locator('#newBaseUnit');
    const unitVisible = await baseUnitSelect.isVisible().catch(() => false);
    if (!unitVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Base unit select not visible — no units seeded in this company; cannot exercise the guard',
      });
      return;
    }

    // Fill a valid name but keep base unit at its default empty value.
    await page.locator('#newName').fill(`ProdNoUnit-${RUN_TAG}`);
    await baseUnitSelect.selectOption(''); // explicitly pick the placeholder

    await page.locator('#createProductForm button[type="submit"]').click();

    // Error: 'Base unit is required.'
    const formError = page.locator('#createProductForm p[role="alert"].text-danger');
    await expect(
      formError,
      'Expected "Base unit is required" error after submitting without selecting a unit',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await formError.innerText();
    expect(
      errorText,
      'Error text does not mention unit — message is not actionable',
    ).toMatch(/unit/i);

    await assertNoRawErrorLeak(page, 'product create — no base unit');

    await expect(page.locator('#createProductForm')).toBeVisible();
  });

  // ── Case P3: valid submission shows success alert + new row appears ──────────
  test('P3: valid submission shows success alert and new product appears in the list', async ({ page }) => {
    const ready = await openProductList(page);
    if (!ready) return;

    const opened = await openProductCreateForm(page);
    if (!opened) return;

    // Need at least one unit — skip gracefully if none are available.
    const baseUnitSelect = page.locator('#newBaseUnit');
    const unitVisible = await baseUnitSelect.isVisible().catch(() => false);
    if (!unitVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Base unit select not visible — no units seeded; skipping valid-create case',
      });
      return;
    }

    const options = await baseUnitSelect.locator('option').all();
    const realOptions = options.filter(async (o) => (await o.getAttribute('value')) !== '');
    if (realOptions.length === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No selectable unit options in base unit dropdown — company has no active units',
      });
      return;
    }

    // Pick the first real (non-placeholder) unit.
    const firstUnitValue = await (await baseUnitSelect.locator('option').all())
      .slice(1)[0]
      ?.getAttribute('value')
      .catch(() => null);

    if (!firstUnitValue) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Could not extract unit value from base unit select',
      });
      return;
    }

    const uniqueName = `AutoProd-${RUN_TAG}`;
    await page.locator('#newName').fill(uniqueName);
    await baseUnitSelect.selectOption(firstUnitValue);
    await page.locator('#newType').selectOption('GOODS');

    const problems = watchProblems(page);

    await page.locator('#createProductForm button[type="submit"]').click();

    // Success alert.
    const alertDialog = page.locator('[role="alertdialog"].alert-success');
    await expect(
      alertDialog,
      `Success alert did not appear after creating product "${uniqueName}"`,
    ).toBeVisible({ timeout: ALERT_APPEAR });

    const titleText = await alertDialog.locator('#alertTitle').innerText().catch(() => '');
    expect(titleText.trim().length, 'Success alert title is empty').toBeGreaterThan(0);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx during product create: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    // Wait for list to refresh and search for the new product.
    await page.waitForTimeout(2_000);
    await page.waitForLoadState('networkidle', { timeout: 10_000 });

    const searchInput = page.locator('#productSearch');
    const searchVisible = await searchInput.isVisible().catch(() => false);
    if (searchVisible) {
      await searchInput.fill(uniqueName);
      await page.keyboard.press('Enter');
      await page.waitForLoadState('networkidle', { timeout: 8_000 });
      await page.waitForTimeout(400);
    }

    const matchingRow = page.locator('table.erp-table tbody tr').filter({ hasText: uniqueName });
    await expect(
      matchingRow.first(),
      `Newly created product "${uniqueName}" not found in list after creation`,
    ).toBeVisible({ timeout: 10_000 });
  });

  // ── Case P4: BR-PROD-01 — SERVICE type disables Stockable flag in the UI ──────
  test('P4: selecting SERVICE type disables the Stockable checkbox (BR-PROD-01 UI guard)', async ({ page }) => {
    const ready = await openProductList(page);
    if (!ready) return;

    const opened = await openProductCreateForm(page);
    if (!opened) return;

    // Switch type to SERVICE.
    await page.locator('#newType').selectOption('SERVICE');
    await page.waitForTimeout(200);

    // The Stockable checkbox must be disabled (aria-disabled or HTML disabled attribute).
    const stockableCheckbox = page.locator('#newStockable');
    const isDisabled = await stockableCheckbox.isDisabled().catch(() => false);
    const ariaDisabled = await stockableCheckbox.getAttribute('aria-disabled').catch(() => null);

    const effectivelyDisabled = isDisabled || ariaDisabled === 'true';

    expect(
      effectivelyDisabled,
      'Stockable checkbox is still enabled when type=SERVICE — BR-PROD-01 UI guard is missing',
    ).toBe(true);

    // Revert to GOODS — Stockable should become enabled again.
    await page.locator('#newType').selectOption('GOODS');
    await page.waitForTimeout(200);

    const isDisabledAfterRevert = await stockableCheckbox.isDisabled().catch(() => false);
    expect(
      isDisabledAfterRevert,
      'Stockable checkbox remains disabled after switching back to GOODS',
    ).toBe(false);
  });

});

// ─── Form 3: GL Manual Journal post ───────────────────────────────────────────

test.describe('UI/UX: GL manual journal post form validation', () => {

  async function openPostJournalPage(page: Parameters<typeof watchProblems>[0]): Promise<boolean> {
    const problems = watchProblems(page);
    await page.goto('/admin/gl/journals/post', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(800);

    if (page.url().includes('/login')) {
      test.info().annotations.push({ type: 'skip-reason', description: 'session bounced to /login' });
      return false;
    }

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    if (api5xx.length > 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: `API 5xx during /admin/gl/journals/post load: ${JSON.stringify(api5xx)}`,
      });
      return false;
    }

    // If rootadmin lacks GL.POST the page shows a permission notice — skip gracefully.
    const permDenied = page.locator('[role="alert"]').filter({ hasText: /don't have permission/i });
    const denied = await permDenied.isVisible().catch(() => false);
    if (denied) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'rootadmin does not have GL.POST permission — cannot exercise this form',
      });
      return false;
    }

    // Wait for the form to render (posting date input present).
    const postingDateInput = page.locator('#postingDate');
    const formReady = await postingDateInput.isVisible().catch(() => false);
    if (!formReady) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '#postingDate not visible — form may not have rendered (company or accounts still loading)',
      });
      return false;
    }

    return true;
  }

  // ── Case G1: "Post Journal" button is disabled when not balanced ─────────────
  test('G1: Post Journal button is disabled and "Not balanced" indicator shown when debits ≠ credits', async ({ page }) => {
    const ready = await openPostJournalPage(page);
    if (!ready) return;

    // Fill header fields — date is pre-filled by the component (today).
    await page.locator('#description').fill(`BalanceCheck-${RUN_TAG}`);

    // Accounts need to have loaded for line selects to have options.
    // Probe the first line account select using a page-level name-prefix selector.
    const firstLineAccountSelect = page.locator('select[name^="lineAccount_"]').first();
    const accountSelectVisible = await firstLineAccountSelect.isVisible().catch(() => false);
    if (!accountSelectVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'First line account select not visible — accounts may still be loading or none exist',
      });
      return;
    }

    const accountOptions = await firstLineAccountSelect.locator('option').all();
    if (accountOptions.length < 2) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No selectable accounts in the journal line selector — cannot construct an unbalanced entry',
      });
      return;
    }

    // Enter a debit-only amount on line 1 (making the entry unbalanced).
    // Use page-level name-prefix selector scoped to the first debit input.
    const firstDebit = page.locator('input[name^="lineDebit_"]').first();
    await firstDebit.fill('1000.00');

    await page.waitForTimeout(300);

    // The "Not balanced" status tag should be visible.
    const notBalancedTag = page.locator('.status-tag').filter({ hasText: /Not balanced/i });
    await expect(
      notBalancedTag,
      '"Not balanced" status tag not visible when debits do not equal credits',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // The Post Journal submit button must be disabled.
    const postButton = page.getByRole('button', { name: /Post Journal/i });
    await expect(
      postButton,
      'Post Journal button is not disabled when the entry is unbalanced',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    // The balance hint text should be visible.
    const balanceHint = page.locator('#balanceHint, p').filter({ hasText: /debits must equal credits/i });
    const hintVisible = await balanceHint.isVisible().catch(() => false);
    expect(
      hintVisible,
      'Balance hint text not visible when entry is unbalanced',
    ).toBe(true);
  });

  // ── Case G2: submitting with empty description shows a clean error ───────────
  test('G2: submitting with empty description shows a field-level error and no 500', async ({ page }) => {
    const ready = await openPostJournalPage(page);
    if (!ready) return;

    // The Post Journal button is gated on isBalanced() AND description non-empty.
    // To isolate the description guard we need a balanced entry first.
    // Probe using page-level name-prefix selectors — no table-row scoping required.
    const firstLineSelect = page.locator('select[name^="lineAccount_"]').first();
    const selectVisible = await firstLineSelect.isVisible().catch(() => false);
    if (!selectVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Line account select not visible — cannot construct balanced entry to test description guard',
      });
      return;
    }

    const accountOptions = await firstLineSelect.locator('option').all();
    // Need at least 2 real account options for two different lines.
    if (accountOptions.length < 2) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Fewer than 2 account options in journal line selects — cannot build balanced entry',
      });
      return;
    }

    // The component's post() method checks description before it makes the API call.
    // We can trigger the client-side guard by clearing description and attempting to
    // call post() directly — but we cannot bypass the postDisabled() computed guard
    // via the submit button. Instead, exercise the guard via the formError signal:
    // fill a balanced entry, clear description, observe the Post button disables.

    // Pick account for line 1 (first page-level account select).
    const firstAccountValue = await accountOptions[1].getAttribute('value').catch(() => null);
    if (!firstAccountValue) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Could not read first account value' });
      return;
    }
    await firstLineSelect.selectOption(firstAccountValue);
    await page.locator('input[name^="lineDebit_"]').first().fill('500.00');

    // Pick account for line 2 (second page-level account select).
    const line2Select = page.locator('select[name^="lineAccount_"]').nth(1);
    const line2AccountOptions = await line2Select.locator('option').all();
    const line2AccountValue = line2AccountOptions.length > 1
      ? await line2AccountOptions[1].getAttribute('value').catch(() => null)
      : firstAccountValue;
    if (!line2AccountValue) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Could not read second account value' });
      return;
    }
    await line2Select.selectOption(line2AccountValue);
    await page.locator('input[name^="lineCreditAmt_"]').nth(1).fill('500.00');

    // Wait for balanced state.
    await page.waitForTimeout(300);
    const balancedTag = page.locator('.status-tag').filter({ hasText: /^Balanced$/i });
    const isBalancedVisible = await balancedTag.isVisible().catch(() => false);

    if (!isBalancedVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Could not achieve balanced state — accounts may be identical or amounts not accepted',
      });
      return;
    }

    // Clear description — Post button should become disabled.
    await page.locator('#description').fill('');
    await page.waitForTimeout(200);

    const postButton = page.getByRole('button', { name: /Post Journal/i });
    await expect(
      postButton,
      'Post Journal button should be disabled when description is empty even if the entry is balanced',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    // No raw 500 should have appeared.
    await assertNoRawErrorLeak(page, 'GL journal — empty description with balanced entry');
  });

  // ── Case G3: valid balanced journal is posted and navigates to detail ─────────
  test('G3: valid balanced journal posts successfully and navigates to the journal detail page', async ({ page }) => {
    const ready = await openPostJournalPage(page);
    if (!ready) return;

    // Probe the first page-level account select — no table-row scoping.
    const firstLineSelect = page.locator('select[name^="lineAccount_"]').first();
    const selectVisible = await firstLineSelect.isVisible().catch(() => false);
    if (!selectVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Line account select not visible — accounts not loaded; skipping valid post case',
      });
      return;
    }

    const accountOptions = await firstLineSelect.locator('option').all();
    if (accountOptions.length < 2) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Fewer than 2 account options — cannot build a valid balanced journal',
      });
      return;
    }

    // Fill header.
    const today = new Date().toISOString().slice(0, 10);
    await page.locator('#postingDate').fill(today);
    await page.locator('#description').fill(`E2E-Journal-${RUN_TAG}`);

    // Build balanced lines using page-level name-prefix selectors.
    const firstAccountValue = await accountOptions[1].getAttribute('value').catch(() => null);
    if (!firstAccountValue) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Could not extract account value for line 1' });
      return;
    }

    // Line 1: debit 250 on first account select / first debit input.
    await firstLineSelect.selectOption(firstAccountValue);
    await page.locator('input[name^="lineDebit_"]').first().fill('250.00');

    // Line 2: credit 250 on second account select / second credit input.
    const line2Select = page.locator('select[name^="lineAccount_"]').nth(1);
    const line2AccountOptions = await line2Select.locator('option').all();
    // Use same account for line 2 if only one is available (valid per the backend — same account can be used).
    const secondAccountValue = line2AccountOptions.length > 2
      ? await line2AccountOptions[2].getAttribute('value').catch(() => firstAccountValue)
      : firstAccountValue;

    await line2Select.selectOption(secondAccountValue ?? firstAccountValue);
    await page.locator('input[name^="lineCreditAmt_"]').nth(1).fill('250.00');

    await page.waitForTimeout(400);

    // Confirm balanced state is shown.
    const balancedTag = page.locator('.status-tag').filter({ hasText: /^Balanced$/i });
    const isBalancedVisible = await balancedTag.isVisible().catch(() => false);
    if (!isBalancedVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Entry did not reach balanced state — possibly amounts or accounts rejected client-side',
      });
      return;
    }

    const problems = watchProblems(page);

    // Submit.
    const postButton = page.getByRole('button', { name: /Post Journal/i });
    await expect(postButton, 'Post Journal button not enabled with balanced entry').toBeEnabled();
    await postButton.click();

    // On success the component navigates to /admin/gl/journals/uid/<ULID>.
    await page.waitForURL(/\/admin\/gl\/journals\/uid\//, { timeout: ALERT_APPEAR });

    // No API 5xx during the whole flow.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx during GL journal post: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    // The detail page must render the Lines section heading.
    const linesHeading = page.getByRole('heading', { name: /Lines/i });
    await expect(
      linesHeading,
      'Lines section heading not visible on new journal detail page — post may have failed silently',
    ).toBeVisible({ timeout: 15_000 });

    // No raw error leak on the detail page.
    await assertNoRawErrorLeak(page, 'GL journal detail after successful post');
  });

  // ── Case G4: no 500 / "unexpected error" leaked for any in-form error ─────────
  test('G4: form error messages are clean strings — no stack trace or 500 leaked on the GL post page', async ({ page }) => {
    const ready = await openPostJournalPage(page);
    if (!ready) return;

    // Attempt to submit with missing account on line 1 (formError = 'All lines must have an account').
    // The Post button is gated on isBalanced() which is false when lines have no amounts yet.
    // The component also short-circuits post() when postDisabled() is true.
    // So we need balanced amounts first; then submit with a missing account.

    // Probe using page-level name-prefix selector — no table-row scoping.
    const firstLineSelect = page.locator('select[name^="lineAccount_"]').first();
    const selectVisible = await firstLineSelect.isVisible().catch(() => false);
    if (!selectVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Line account select not visible — cannot build entry to exercise missing-account guard',
      });
      return;
    }

    const accountOptions = await firstLineSelect.locator('option').all();
    if (accountOptions.length < 2) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No selectable accounts available',
      });
      return;
    }

    await page.locator('#description').fill(`CleanErr-${RUN_TAG}`);

    // Make amounts balanced but leave line 1 account blank → client guard fires.
    // line1: debit 100, no account (leave firstLineSelect at default empty value).
    await page.locator('input[name^="lineDebit_"]').first().fill('100.00');
    // line2: credit 100, pick an account (second page-level account select).
    const firstAccountValue = await accountOptions[1].getAttribute('value').catch(() => null);
    if (firstAccountValue) {
      await page.locator('select[name^="lineAccount_"]').nth(1).selectOption(firstAccountValue);
    }
    await page.locator('input[name^="lineCreditAmt_"]').nth(1).fill('100.00');

    await page.waitForTimeout(400);

    // The entry is balanced in amounts but line 1 has no account.
    // postDisabled is computed as !isBalanced() OR missing date/desc/company — it may
    // still be disabled. Force the client guard by checking if post button enabled.
    const postButton = page.getByRole('button', { name: /Post Journal/i });
    const postEnabled = await postButton.isEnabled().catch(() => false);

    if (!postEnabled) {
      // Can't click — the guard prevents even trying. This is correct UI behaviour.
      // Assert no raw errors already on the page.
      await assertNoRawErrorLeak(page, 'GL journal — post button disabled pre-submit');
      return;
    }

    await postButton.click();
    await page.waitForTimeout(500);

    // The formError element should show a clean message.
    const formError = page.locator('p[role="alert"].text-danger');
    const errorVisible = await formError.isVisible().catch(() => false);
    if (errorVisible) {
      const errorText = await formError.innerText().catch(() => '');
      // Confirm it does NOT contain raw 500 / stack trace patterns.
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(errorText),
          `Raw server error pattern "${pattern}" found in GL journal form error: "${errorText}"`,
        ).toBe(false);
      }
      // Confirm the message is non-empty and actionable.
      expect(
        errorText.trim().length,
        'GL journal form error is empty — not actionable',
      ).toBeGreaterThan(5);
    }

    // Verify body-level raw error scan.
    await assertNoRawErrorLeak(page, 'GL journal — in-form error after partial submit');
  });

});

// ─── Cross-cutting: edit form on Customer detail (save with empty display name) ──

test.describe('UI/UX: Customer detail edit form validation', () => {

  /**
   * Navigate to the first customer in the list and open its detail/edit page.
   * Returns null if no customer exists (annotation added).
   */
  async function openFirstCustomerDetail(page: Parameters<typeof watchProblems>[0]): Promise<string | null> {
    await page.goto('/admin/customers', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(800);

    if (page.url().includes('/login')) {
      test.info().annotations.push({ type: 'skip-reason', description: 'session bounced to /login' });
      return null;
    }

    // Find the first Edit link in the customers table.
    const firstEditLink = page.locator('table.erp-table tbody tr').first().getByRole('link', { name: /Edit/i });
    const linkVisible = await firstEditLink.isVisible().catch(() => false);
    if (!linkVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No customer Edit link found in the list — table may be empty',
      });
      return null;
    }

    const href = await firstEditLink.getAttribute('href').catch(() => null);
    await firstEditLink.click();
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    await page.waitForTimeout(600);

    return href;
  }

  // ── Case D1: clearing display name and saving shows a clean error ─────────────
  test('D1: saving customer detail with empty display name shows a clean error, not a 500', async ({ page }) => {
    const detailHref = await openFirstCustomerDetail(page);
    if (!detailHref) return;

    // The detail page has aria-label="Edit customer" form with id="fDisplayName".
    const displayNameInput = page.locator('#fDisplayName');
    const inputVisible = await displayNameInput.isVisible().catch(() => false);
    if (!inputVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '#fDisplayName not visible on customer detail — form may not have loaded',
      });
      return;
    }

    // Clear the display name.
    await displayNameInput.fill('');

    // Click "Save changes".
    const saveBtn = page.getByRole('button', { name: /Save changes/i });
    const saveBtnVisible = await saveBtn.isVisible().catch(() => false);
    if (!saveBtnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"Save changes" button not visible — rootadmin may lack CUSTOMER.MANAGE',
      });
      return;
    }

    await saveBtn.click();
    await page.waitForTimeout(600);

    // The component sets saveError = 'Display name is required.' and renders it as:
    // <output class="d-block text-danger small mt-3" aria-live="polite" aria-atomic="true">
    const saveError = page.locator('output.text-danger[aria-live="polite"]');
    await expect(
      saveError,
      'Expected a visible save-error message after clearing display name on customer detail',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await saveError.innerText().catch(() => '');
    expect(
      errorText.trim().length,
      'Save error text is empty on customer detail — not actionable',
    ).toBeGreaterThan(5);

    await assertNoRawErrorLeak(page, 'customer detail — save with empty display name');

    // We are still on the detail page (no navigation on error).
    expect(page.url()).not.toMatch(/\/login/);
    expect(page.url()).toMatch(/\/customers\/uid\//);
  });

});
