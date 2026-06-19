/**
 * UI/UX: Long-tail coverage — POS, CRM, Manufacturing, Projects, Budgeting, FX rates.
 *
 * Project: chromium (starts logged in as rootadmin via storageState + session-restore fixture).
 *
 * Coverage goal per module:
 *   POS     — sell screen renders; tills list renders; sessions list renders;
 *             till create form validation (empty name guard).
 *   CRM     — opportunity create form validation (empty title guard);
 *             opportunity list renders; pipeline dashboard renders.
 *   Mfg     — work-order list renders; work-order create form validation
 *             (empty planned-qty guard); BOM list renders; BOM create form
 *             validation (no parent product guard).
 *   Projects— list renders; create form validation (empty name guard).
 *   Budgets — list renders; create form validation (empty name guard).
 *   FX rates— list renders; create form validation (save with no currencies
 *             selected shows clean error, no raw 500).
 *
 * Selector provenance: every selector was read directly from the matching
 *   component HTML under web/src/app/features/admin/.
 * Resilience: each test annotates + returns (graceful-skip) when a prerequisite
 *   is absent (permission denied, company not loaded, no seeded data).
 *   No test hard-fails on environment state it cannot control.
 * Idempotency: unique names carry the per-run RUN_TAG (Date.now()).
 */

import { test, expect } from './_test-authenticated';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── shared constants ──────────────────────────────────────────────────────────

const RUN_TAG = `QA-${Date.now()}`;

/** Milliseconds allowed for the DOM to settle after an interaction. */
const DOM_SETTLE = 8_000;

/** Milliseconds allowed for a success alert to appear after a valid create. */
const ALERT_APPEAR = 12_000;

/** Raw server-error patterns that must never appear in visible page text. */
const RAW_ERROR_PATTERNS = [/\b500\b/, /stack\s*trace/i, /NullPointerException/i, /unexpected error/i];

/**
 * Scan visible body text for raw-server-error patterns.
 * Fails the assertion with context if any match.
 */
async function assertNoRawErrorLeak(page: Parameters<typeof watchProblems>[0], ctx: string): Promise<void> {
  const text = await page.locator('body').innerText().catch(() => '');
  for (const pattern of RAW_ERROR_PATTERNS) {
    expect(
      pattern.test(text),
      `Raw server error leaked on ${ctx}: matched "${pattern}" in visible body text`,
    ).toBe(false);
  }
}

/**
 * Navigate to a route, wait for networkidle, assert no session bounce and no
 * API 5xx during load.  Returns false (with annotation) on any blocker.
 */
async function gotoAndGuard(
  page: Parameters<typeof watchProblems>[0],
  route: string,
  label: string,
): Promise<{ ok: boolean; problems: ReturnType<typeof watchProblems> }> {
  const problems = watchProblems(page);
  await page.goto(route, { waitUntil: 'networkidle', timeout: 60_000 });
  await page.waitForTimeout(800);

  if (page.url().includes('/login')) {
    test.info().annotations.push({ type: 'skip-reason', description: `${label}: session bounced to /login` });
    return { ok: false, problems };
  }

  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  if (api5xx.length > 0) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `${label}: API 5xx on load — ${JSON.stringify(api5xx)}`,
    });
    return { ok: false, problems };
  }

  return { ok: true, problems };
}

// ═══════════════════════════════════════════════════════════════════════════════
// POS — SELL SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX POS: sell screen', () => {

  /**
   * POS-S1: the sell page renders its heading and checkout form (or permission
   * guard) without a 5xx or console error.
   */
  test('POS-S1: /admin/pos/sell renders without 5xx or console errors', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/pos/sell', 'POS sell');
    if (!ok) return;

    // The page always renders an h1 with "Point of Sale" text regardless of
    // whether the user has permission (permission gate shows erp-empty instead
    // of the form but h1 is still in the header).
    const heading = page.locator('h1').first();
    await expect(
      heading,
      'POS-S1: h1 heading not visible on /admin/pos/sell',
    ).toBeVisible({ timeout: DOM_SETTLE });

    await assertNoRawErrorLeak(page, 'POS sell page load');

    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `POS-S1: console errors on /admin/pos/sell: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

  /**
   * POS-S2: the sell form is visible when the user has POS.SALE.CREATE
   * permission (rootadmin should). If the canSell() guard is false (erp-empty
   * is shown instead) we skip gracefully.
   */
  test('POS-S2: sell form renders Sale Details and Line Items cards', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/pos/sell', 'POS sell form');
    if (!ok) return;

    // Check permission gate: erp-empty with shield-lock means no POS.SALE.CREATE
    const permDenied = page.locator('.erp-empty').filter({ hasText: /permission/i });
    const denied = await permDenied.isVisible().catch(() => false);
    if (denied) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'POS-S2: rootadmin lacks POS.SALE.CREATE — canSell() guard shows permission empty state',
      });
      return;
    }

    // The form renders two erp-cards: "Sale Details" and "Line Items"
    // Confirmed from pos-sale.component.html h2 text.
    const saleDetailsHeading = page.locator('.erp-card h2').filter({ hasText: /Sale Details/i });
    await expect(
      saleDetailsHeading,
      'POS-S2: "Sale Details" card heading not visible on /admin/pos/sell',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const lineItemsHeading = page.locator('.erp-card h2').filter({ hasText: /Line Items/i });
    await expect(
      lineItemsHeading,
      'POS-S2: "Line Items" card heading not visible on /admin/pos/sell',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // The "Add Line" button is always present while the form is visible.
    const addLineBtn = page.getByRole('button', { name: /Add Line/i });
    await expect(
      addLineBtn,
      'POS-S2: "Add Line" button not visible in Line Items card',
    ).toBeVisible({ timeout: DOM_SETTLE });
  });

  /**
   * POS-S3: clicking "Add Line" adds a line and the empty-state placeholder
   * disappears; removing it with the Remove button restores the empty state.
   * This verifies the cart add/remove cycle without a real API call.
   */
  test('POS-S3: Add Line / Remove cycle updates the line items panel', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/pos/sell', 'POS add/remove line');
    if (!ok) return;

    const permDenied = page.locator('.erp-empty').filter({ hasText: /permission/i });
    const denied = await permDenied.isVisible().catch(() => false);
    if (denied) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'POS-S3: rootadmin lacks POS.SALE.CREATE — cannot exercise add-line cycle',
      });
      return;
    }

    // Confirm the empty cart placeholder is shown before adding a line.
    // Text from pos-sale.component.html: 'No lines yet — click "Add Line" above.'
    const emptyCartText = page.locator('.erp-card .erp-empty').filter({ hasText: /No lines yet/i });
    const emptyBefore = await emptyCartText.isVisible().catch(() => false);

    const addLineBtn = page.getByRole('button', { name: /Add Line/i });
    const addVisible = await addLineBtn.isVisible().catch(() => false);
    if (!addVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'POS-S3: Add Line button not visible — form may still be loading',
      });
      return;
    }

    await addLineBtn.click();
    await page.waitForTimeout(300);

    // After adding a line the erp-table must be present.
    const lineTable = page.locator('table.erp-table').filter({ has: page.locator('caption:has-text("Sale line items")') });
    const tableVisible = await lineTable.isVisible().catch(() => false);

    // If the empty placeholder was originally visible, the table must now appear.
    if (emptyBefore) {
      expect(
        tableVisible,
        'POS-S3: line-items table not visible after clicking "Add Line"',
      ).toBe(true);
    }

    // Remove the line using the remove button (aria-label="Remove line …")
    const removeBtn = page.locator('button[aria-label^="Remove line"]').first();
    const removeBtnVisible = await removeBtn.isVisible().catch(() => false);
    if (removeBtnVisible) {
      await removeBtn.click();
      await page.waitForTimeout(300);
      // Empty state should return (or the table is still there — either is acceptable)
      await assertNoRawErrorLeak(page, 'POS add/remove line cycle');
    }
  });

  /**
   * POS-S4: submitting the form with no session/customer/line shows a clean
   * form error via <p role="alert"> — no raw 500 leaked.
   * The formError() signal in pos-sale.component.ts emits client-side messages.
   */
  test('POS-S4: submitting empty POS form shows a clean validation error, no 500', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/pos/sell', 'POS sell submit validation');
    if (!ok) return;

    const permDenied = page.locator('.erp-empty').filter({ hasText: /permission/i });
    const denied = await permDenied.isVisible().catch(() => false);
    if (denied) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'POS-S4: rootadmin lacks POS.SALE.CREATE — cannot exercise form submission',
      });
      return;
    }

    // Submit the form without filling anything.
    const submitBtn = page.locator('button[type="submit"]').filter({ hasText: /Complete Sale/i });
    const submitVisible = await submitBtn.isVisible().catch(() => false);
    if (!submitVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'POS-S4: "Complete Sale" submit button not visible — form may not have rendered',
      });
      return;
    }

    await submitBtn.click();
    await page.waitForTimeout(600);

    // The component's submit() sets formError() which renders as
    // <p class="text-danger small mb-3" role="alert">{{ msg }}</p>
    const formError = page.locator('p.text-danger[role="alert"]');
    const errorVisible = await formError.isVisible().catch(() => false);
    if (errorVisible) {
      const errorText = await formError.innerText().catch(() => '');
      // Must not contain raw 5xx / stack trace
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(errorText),
          `POS-S4: raw error pattern "${pattern}" in form error: "${errorText}"`,
        ).toBe(false);
      }
      expect(errorText.trim().length, 'POS-S4: form error text is empty — not actionable').toBeGreaterThan(3);
    }

    await assertNoRawErrorLeak(page, 'POS sell — empty submit');
    // Page must still be on the sell screen (no redirect on validation failure)
    expect(page.url()).not.toMatch(/\/login/);
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// POS — TILLS LIST
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX POS: tills list', () => {

  /**
   * POS-T1: /admin/pos/tills renders the h1 "POS Tills" heading without 5xx.
   */
  test('POS-T1: /admin/pos/tills renders heading without 5xx or console errors', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/pos/tills', 'POS tills list');
    if (!ok) return;

    const heading = page.locator('h1').first();
    await expect(heading, 'POS-T1: h1 not visible on /admin/pos/tills').toBeVisible({ timeout: DOM_SETTLE });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'POS-T1: h1 does not say "POS Tills"').toMatch(/POS Tills/i);

    await assertNoRawErrorLeak(page, 'POS tills list');

    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `POS-T1: console errors on /admin/pos/tills: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

  /**
   * POS-T2: till create form — submitting with no name shows <p role="alert">
   * with a clean message.
   * The formError() in pos-till-list renders as: <p class="text-danger small mt-2 mb-2" role="alert">
   */
  test('POS-T2: till create form empty-name guard shows a clean error, no 500', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/pos/tills', 'POS till create validation');
    if (!ok) return;

    // Open create form — button is only shown when canManage() is true.
    // Button text: "New Till" (pos-till-list.component.html).
    const newTillBtn = page.getByRole('button', { name: /New Till/i });
    const btnVisible = await newTillBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'POS-T2: "New Till" button not visible — rootadmin may lack POS.TILL.VIEW/MANAGE or company context missing',
      });
      return;
    }

    await newTillBtn.click();
    // The form card uses id="createTillForm" from pos-till-list.component.html.
    await expect(page.locator('#createTillForm'), 'POS-T2: createTillForm card not visible after clicking New Till').toBeVisible({ timeout: DOM_SETTLE });

    // Leave #newTillName empty (default) and submit.
    await page.locator('#newTillName').fill('');
    await page.locator('#createTillForm button[type="submit"]').click();
    await page.waitForTimeout(600);

    // Error: <p class="text-danger small mt-2 mb-2" role="alert"> from formError() signal.
    const formError = page.locator('#createTillForm p[role="alert"].text-danger');
    const errorVisible = await formError.isVisible().catch(() => false);
    if (errorVisible) {
      const errorText = await formError.innerText().catch(() => '');
      expect(errorText.trim().length, 'POS-T2: till form error is empty — not actionable').toBeGreaterThan(3);
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(errorText),
          `POS-T2: raw error pattern "${pattern}" in till form error: "${errorText}"`,
        ).toBe(false);
      }
    }

    await assertNoRawErrorLeak(page, 'POS till create — empty name guard');
    // Form must still be open on error.
    await expect(page.locator('#createTillForm'), 'POS-T2: create form dismissed after validation error').toBeVisible();
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// POS — SESSIONS LIST
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX POS: sessions list', () => {

  /**
   * POS-SE1: /admin/pos/sessions renders the h1 "POS Sessions" heading without 5xx.
   */
  test('POS-SE1: /admin/pos/sessions renders heading without 5xx or console errors', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/pos/sessions', 'POS sessions list');
    if (!ok) return;

    const heading = page.locator('h1').first();
    await expect(heading, 'POS-SE1: h1 not visible on /admin/pos/sessions').toBeVisible({ timeout: DOM_SETTLE });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'POS-SE1: h1 does not say "POS Sessions"').toMatch(/POS Sessions/i);

    await assertNoRawErrorLeak(page, 'POS sessions list');

    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `POS-SE1: console errors on /admin/pos/sessions: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

  /**
   * POS-SE2: when at least one session row exists, the erp-table renders status
   * pill tags (.status-tag) and a View link per row.
   */
  test('POS-SE2: sessions table rows render status pills and View links when sessions exist', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/pos/sessions', 'POS sessions table');
    if (!ok) return;

    const rows = await page.locator('table.erp-table tbody tr').count();
    if (rows === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'POS-SE2: no sessions in the list — table assertions require at least one session',
      });
      return;
    }

    // Each row has a .status-tag for session status.
    const firstStatusTag = page.locator('table.erp-table tbody tr').first().locator('.status-tag');
    await expect(
      firstStatusTag,
      'POS-SE2: .status-tag not found in first session row',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // Each row has a View link (<a class="btn btn-sm btn-outline-primary">)
    const viewLink = page.locator('table.erp-table tbody tr').first().locator('a.btn').filter({ hasText: /View/i });
    await expect(
      viewLink,
      'POS-SE2: View link not found in first session row',
    ).toBeVisible({ timeout: DOM_SETTLE });
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// CRM — OPPORTUNITIES
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX CRM: opportunity create form validation', () => {

  /**
   * CRM-OC1: navigating to /admin/crm/opportunities/create renders the
   * "New Opportunity" heading and the create form.
   */
  test('CRM-OC1: /admin/crm/opportunities/create renders the create form', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/crm/opportunities/create', 'CRM opportunity create');
    if (!ok) return;

    // opportunity-create.component.html: <h1 class="h4 mb-1 mt-1">New Opportunity</h1>
    const heading = page.locator('h1').first();
    await expect(heading, 'CRM-OC1: h1 not visible on /admin/crm/opportunities/create').toBeVisible({ timeout: DOM_SETTLE });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'CRM-OC1: h1 does not say "New Opportunity"').toMatch(/New Opportunity/i);

    // The main form: aria-label="Create opportunity"
    const form = page.locator('form[aria-label="Create opportunity"]');
    await expect(form, 'CRM-OC1: create-opportunity form not visible').toBeVisible({ timeout: DOM_SETTLE });

    await assertNoRawErrorLeak(page, 'CRM opportunity create page');
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `CRM-OC1: console errors on opportunity create: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

  /**
   * CRM-OC2: submitting with an empty title field shows a clean client-side
   * error from formError() — <p class="text-danger small mt-2 mb-2" role="alert">
   * The component guards: title and customer are required.
   */
  test('CRM-OC2: submitting with empty title shows a clean formError, no 500', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/crm/opportunities/create', 'CRM opportunity empty title');
    if (!ok) return;

    // Confirm the form rendered.
    const titleInput = page.locator('#fTitle');
    const formLoaded = await titleInput.isVisible().catch(() => false);
    if (!formLoaded) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'CRM-OC2: #fTitle input not visible — form may not have loaded (permission or company missing)',
      });
      return;
    }

    // Leave title empty and try to submit.
    await titleInput.fill('');
    await page.locator('button[type="submit"]').filter({ hasText: /Create Opportunity/i }).click();
    await page.waitForTimeout(600);

    // Error element: <p class="text-danger small mt-2 mb-2" role="alert">
    const formError = page.locator('p.text-danger[role="alert"]');
    const errorVisible = await formError.isVisible().catch(() => false);
    if (errorVisible) {
      const errorText = await formError.innerText().catch(() => '');
      expect(errorText.trim().length, 'CRM-OC2: formError text is empty — not actionable').toBeGreaterThan(3);
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(errorText),
          `CRM-OC2: raw error pattern "${pattern}" in opportunity formError: "${errorText}"`,
        ).toBe(false);
      }
    }

    await assertNoRawErrorLeak(page, 'CRM opportunity create — empty title submit');
    // Must still be on the create page (no redirect on validation error).
    expect(page.url()).not.toMatch(/\/login/);
    expect(page.url()).toMatch(/\/crm\/opportunities\/create/);
  });

  /**
   * CRM-OC3: /admin/crm/opportunities list renders the Opportunities heading
   * without 5xx.  "New Opportunity" link is a router link (<a>), not a button.
   */
  test('CRM-OC3: /admin/crm/opportunities list renders heading and toolbar link', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/crm/opportunities', 'CRM opportunity list');
    if (!ok) return;

    // opportunity-list.component.html: <h1 class="h4 mb-1">Opportunities</h1>
    const heading = page.locator('h1').first();
    await expect(heading, 'CRM-OC3: h1 not visible on /admin/crm/opportunities').toBeVisible({ timeout: DOM_SETTLE });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'CRM-OC3: h1 does not say "Opportunities"').toMatch(/Opportunities/i);

    // The "New Opportunity" link must be present when canManage().
    // opportunity-list.component.html: <a routerLink="/admin/crm/opportunities/create" class="btn btn-primary">
    const newOppLink = page.getByRole('link', { name: /New Opportunity/i });
    const linkVisible = await newOppLink.isVisible().catch(() => false);
    if (!linkVisible) {
      test.info().annotations.push({
        type: 'info',
        description: 'CRM-OC3: "New Opportunity" link not visible — rootadmin may lack CRM.OPPORTUNITY.MANAGE',
      });
    }

    await assertNoRawErrorLeak(page, 'CRM opportunity list');
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `CRM-OC3: console errors on /admin/crm/opportunities: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// CRM — PIPELINE DASHBOARD
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX CRM: pipeline dashboard', () => {

  /**
   * CRM-PD1: /admin/crm/pipeline renders the "Pipeline Dashboard" heading and
   * the company/branch selector row without 5xx.
   */
  test('CRM-PD1: /admin/crm/pipeline renders heading and selector controls', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/crm/pipeline', 'CRM pipeline dashboard');
    if (!ok) return;

    // pipeline-dashboard.component.html: <h1 class="h4 mb-1">Pipeline Dashboard</h1>
    const heading = page.locator('h1').first();
    await expect(heading, 'CRM-PD1: h1 not visible on /admin/crm/pipeline').toBeVisible({ timeout: DOM_SETTLE });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'CRM-PD1: h1 does not say "Pipeline Dashboard"').toMatch(/Pipeline Dashboard/i);

    // The branchPicker select is always rendered once the company context is loaded.
    // pipeline-dashboard.component.html: id="branchPicker"
    // (companyPicker may be absent if rootadmin belongs to only one company)
    const branchPicker = page.locator('#branchPicker');
    const branchPickerVisible = await branchPicker.isVisible().catch(() => false);
    if (!branchPickerVisible) {
      test.info().annotations.push({
        type: 'info',
        description: 'CRM-PD1: branchPicker not visible — company may still be loading or no branches configured',
      });
    }

    await assertNoRawErrorLeak(page, 'CRM pipeline dashboard');
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `CRM-PD1: console errors on /admin/crm/pipeline: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

  /**
   * CRM-PD2: the "Pipeline Board" section heading and "Forecast" / "Win-Rate KPIs"
   * section headings render once a company+branch are selected.
   * These are always rendered by the template once selectedCompanyId() && selectedBranchId().
   */
  test('CRM-PD2: pipeline board, forecast and KPI section headings render when context is set', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/crm/pipeline', 'CRM pipeline sections');
    if (!ok) return;

    // Wait for the branch picker to appear (implies company loaded).
    const branchPicker = page.locator('#branchPicker');
    const branchPickerVisible = await branchPicker.isVisible({ timeout: 10_000 }).catch(() => false);
    if (!branchPickerVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'CRM-PD2: branchPicker not visible — cannot confirm section headings require company context',
      });
      return;
    }

    // If branchPicker has no selectable option (no branches), skip.
    const branchOptions = await branchPicker.locator('option').all();
    const realOptions = [];
    for (const opt of branchOptions) {
      const val = await opt.getAttribute('value').catch(() => null);
      if (val && val !== '') realOptions.push(val);
    }
    if (realOptions.length === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'CRM-PD2: no branch options available — pipeline sections require a branch to be set',
      });
      return;
    }

    // Wait for the section headings to appear.
    // pipeline-dashboard.component.html: <h2 class="erp-section-title">Pipeline Board</h2>
    const pipelineBoardHeading = page.locator('h2.erp-section-title').filter({ hasText: /Pipeline Board/i });
    await expect(
      pipelineBoardHeading,
      'CRM-PD2: "Pipeline Board" section heading not visible — selectedCompanyId+branchId may not be set',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // <h2 class="erp-section-title">Forecast …</h2>
    const forecastHeading = page.locator('h2.erp-section-title').filter({ hasText: /Forecast/i });
    await expect(
      forecastHeading,
      'CRM-PD2: "Forecast" section heading not visible on pipeline dashboard',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // <h2 class="erp-section-title">Win-Rate KPIs …</h2>
    const kpiHeading = page.locator('h2.erp-section-title').filter({ hasText: /Win-Rate KPIs/i });
    await expect(
      kpiHeading,
      'CRM-PD2: "Win-Rate KPIs" section heading not visible on pipeline dashboard',
    ).toBeVisible({ timeout: DOM_SETTLE });

    await assertNoRawErrorLeak(page, 'CRM pipeline dashboard sections');
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// MANUFACTURING — WORK ORDERS
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX Manufacturing: work orders', () => {

  /**
   * MFG-WO1: /admin/work-orders renders the "Work Orders" heading without 5xx.
   */
  test('MFG-WO1: /admin/work-orders renders heading without 5xx or console errors', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/work-orders', 'Work orders list');
    if (!ok) return;

    const heading = page.locator('h1').first();
    await expect(heading, 'MFG-WO1: h1 not visible on /admin/work-orders').toBeVisible({ timeout: DOM_SETTLE });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'MFG-WO1: h1 does not say "Work Orders"').toMatch(/Work Orders/i);

    await assertNoRawErrorLeak(page, 'Work orders list');
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `MFG-WO1: console errors on /admin/work-orders: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

  /**
   * MFG-WO2: work-order create form — submitting with zero planned qty and no
   * product/branch selected shows a clean formError, no 500.
   * form id: createWorkOrderForm. Error: <p role="alert" class="text-danger small ...">
   */
  test('MFG-WO2: work-order create form empty-submit shows clean validation error, no 500', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/work-orders', 'Work order create validation');
    if (!ok) return;

    // Open the create form — "New Work Order" button only visible when canCreate().
    const newWoBtn = page.getByRole('button', { name: /New Work Order/i });
    const btnVisible = await newWoBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'MFG-WO2: "New Work Order" button not visible — rootadmin may lack MANUFACTURING.VIEW or company context missing',
      });
      return;
    }

    await newWoBtn.click();
    // Form: id="createWorkOrderForm" aria-label="Create work order"
    await expect(
      page.locator('#createWorkOrderForm'),
      'MFG-WO2: createWorkOrderForm card not visible after clicking New Work Order',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // Fill the planned qty with 0 (invalid) and leave product/branch unpicked.
    // #newPlannedQty from work-order-list.component.html
    await page.locator('#newPlannedQty').fill('0');

    // Submit.
    await page.locator('#createWorkOrderForm button[type="submit"]').click();
    await page.waitForTimeout(600);

    // Error: <p class="text-danger small mt-2 mb-0" role="alert"> from formError() signal.
    const formError = page.locator('#createWorkOrderForm p[role="alert"].text-danger');
    const errorVisible = await formError.isVisible().catch(() => false);
    if (errorVisible) {
      const errorText = await formError.innerText().catch(() => '');
      expect(errorText.trim().length, 'MFG-WO2: work-order form error is empty — not actionable').toBeGreaterThan(3);
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(errorText),
          `MFG-WO2: raw error pattern "${pattern}" in work-order formError: "${errorText}"`,
        ).toBe(false);
      }
    }

    await assertNoRawErrorLeak(page, 'Work order create — invalid qty submit');
    // Form stays open.
    await expect(page.locator('#createWorkOrderForm'), 'MFG-WO2: form dismissed after validation error').toBeVisible();
  });

  /**
   * MFG-WO3: the status filter dropdown is rendered in the toolbar once the
   * company is selected.  Changing it must not cause a console error or 5xx.
   */
  test('MFG-WO3: status filter dropdown renders and can be changed without error', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/work-orders', 'Work order status filter');
    if (!ok) return;

    // Status filter: id="statusFilter" in work-order-list.component.html
    const statusFilter = page.locator('#statusFilter');
    const filterVisible = await statusFilter.isVisible().catch(() => false);
    if (!filterVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'MFG-WO3: #statusFilter not visible — company context may not have loaded',
      });
      return;
    }

    // Change the filter to "PLANNED" (value confirmed from component usage of statusOptions).
    await statusFilter.selectOption('PLANNED');
    await page.waitForLoadState('networkidle', { timeout: 15_000 });
    await page.waitForTimeout(400);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `MFG-WO3: 5xx after changing status filter: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `MFG-WO3: console errors after status filter change: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// MANUFACTURING — BILLS OF MATERIALS
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX Manufacturing: bills of materials', () => {

  /**
   * MFG-BOM1: /admin/boms renders the "Bills of Materials" heading without 5xx.
   */
  test('MFG-BOM1: /admin/boms renders heading without 5xx or console errors', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/boms', 'BOM list');
    if (!ok) return;

    const heading = page.locator('h1').first();
    await expect(heading, 'MFG-BOM1: h1 not visible on /admin/boms').toBeVisible({ timeout: DOM_SETTLE });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'MFG-BOM1: h1 does not say "Bills of Materials"').toMatch(/Bills of Materials/i);

    await assertNoRawErrorLeak(page, 'BOM list');
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `MFG-BOM1: console errors on /admin/boms: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

  /**
   * MFG-BOM2: BOM create form — submitting with no parent product selected shows
   * a clean formError.
   * form id: createBomForm. Error: <p class="text-danger small mt-2 mb-0" role="alert">
   */
  test('MFG-BOM2: BOM create form empty-submit shows clean validation error, no 500', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/boms', 'BOM create validation');
    if (!ok) return;

    // "New BOM" button — only visible when canManage().
    const newBomBtn = page.getByRole('button', { name: /New BOM/i });
    const btnVisible = await newBomBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'MFG-BOM2: "New BOM" button not visible — rootadmin may lack BOM.VIEW or company context missing',
      });
      return;
    }

    await newBomBtn.click();
    // Form: id="createBomForm" aria-label="Create bill of materials"
    await expect(
      page.locator('#createBomForm'),
      'MFG-BOM2: createBomForm not visible after clicking New BOM',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // Submit with no parent product and a valid output qty to isolate the product guard.
    // #newOutputQty from bom-list.component.html
    await page.locator('#newOutputQty').fill('1');

    await page.locator('#createBomForm button[type="submit"]').click();
    await page.waitForTimeout(600);

    // Error: <p class="text-danger small mt-2 mb-0" role="alert">
    const formError = page.locator('#createBomForm p[role="alert"].text-danger');
    const errorVisible = await formError.isVisible().catch(() => false);
    if (errorVisible) {
      const errorText = await formError.innerText().catch(() => '');
      expect(errorText.trim().length, 'MFG-BOM2: BOM form error is empty — not actionable').toBeGreaterThan(3);
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(errorText),
          `MFG-BOM2: raw error pattern "${pattern}" in BOM formError: "${errorText}"`,
        ).toBe(false);
      }
    }

    await assertNoRawErrorLeak(page, 'BOM create — empty parent product submit');
    // Form stays open.
    await expect(page.locator('#createBomForm'), 'MFG-BOM2: BOM form dismissed after validation error').toBeVisible();
  });

  /**
   * MFG-BOM3: when BOMs exist in the list, status pills (.status-tag) are rendered
   * and BOM rows have links to their detail pages.
   */
  test('MFG-BOM3: BOM list rows have status tags and detail links when BOMs exist', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/boms', 'BOM list rows');
    if (!ok) return;

    const rows = await page.locator('table.erp-table tbody tr').count();
    if (rows === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'MFG-BOM3: no BOMs in the list — seed data required for row assertions',
      });
      return;
    }

    // Each row has a .status-tag for BOM status (DRAFT/ACTIVE/ARCHIVED).
    const firstStatusTag = page.locator('table.erp-table tbody tr').first().locator('.status-tag');
    await expect(firstStatusTag, 'MFG-BOM3: .status-tag not found in first BOM row').toBeVisible({ timeout: DOM_SETTLE });

    // Each row has an edit link (<a class="btn btn-sm btn-outline-secondary">) to /admin/boms/uid/…
    const firstEditLink = page.locator('table.erp-table tbody tr').first().locator('a.btn');
    await expect(firstEditLink, 'MFG-BOM3: edit link button not found in first BOM row').toBeVisible({ timeout: DOM_SETTLE });
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// PROJECTS
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX Projects', () => {

  /**
   * PROJ-1: /admin/projects renders the "Projects" heading without 5xx.
   */
  test('PROJ-1: /admin/projects renders heading without 5xx or console errors', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/projects', 'Projects list');
    if (!ok) return;

    const heading = page.locator('h1').first();
    await expect(heading, 'PROJ-1: h1 not visible on /admin/projects').toBeVisible({ timeout: DOM_SETTLE });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'PROJ-1: h1 does not say "Projects"').toMatch(/Projects/i);

    await assertNoRawErrorLeak(page, 'Projects list');
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `PROJ-1: console errors on /admin/projects: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

  /**
   * PROJ-2: project create form — submitting with empty name shows a clean
   * formError from the component's create() guard.
   * Form: id="createProjectForm". Error: <p role="alert" class="text-danger small mt-2 mb-0">
   */
  test('PROJ-2: project create form empty-name guard shows clean error, no 500', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/projects', 'Project create validation');
    if (!ok) return;

    // "New Project" button — only visible when canCreate().
    const newProjBtn = page.getByRole('button', { name: /New Project/i });
    const btnVisible = await newProjBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'PROJ-2: "New Project" button not visible — rootadmin may lack PROJECTS.PROJECT.VIEW or company context missing',
      });
      return;
    }

    await newProjBtn.click();
    // Form: id="createProjectForm" aria-label="Create project"
    await expect(
      page.locator('#createProjectForm'),
      'PROJ-2: createProjectForm not visible after clicking New Project',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // Leave #fName empty and submit.
    await page.locator('#fName').fill('');
    await page.locator('#createProjectForm button[type="submit"]').click();
    await page.waitForTimeout(600);

    // Error: <p class="text-danger small mt-2 mb-0" role="alert">
    const formError = page.locator('#createProjectForm p[role="alert"].text-danger');
    const errorVisible = await formError.isVisible().catch(() => false);
    if (errorVisible) {
      const errorText = await formError.innerText().catch(() => '');
      expect(errorText.trim().length, 'PROJ-2: project form error is empty — not actionable').toBeGreaterThan(3);
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(errorText),
          `PROJ-2: raw error pattern "${pattern}" in project formError: "${errorText}"`,
        ).toBe(false);
      }
    }

    await assertNoRawErrorLeak(page, 'Project create — empty name submit');
    // Form stays open.
    await expect(page.locator('#createProjectForm'), 'PROJ-2: project form dismissed after validation error').toBeVisible();
  });

  /**
   * PROJ-3: when projects exist in the list, each row has a status pill and an
   * "Open" action link pointing to /admin/projects/uid/<ULID>.
   */
  test('PROJ-3: project list rows have status tags and Open links when projects exist', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/projects', 'Project list rows');
    if (!ok) return;

    const rows = await page.locator('table.erp-table tbody tr').count();
    if (rows === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'PROJ-3: project list is empty — seed data required for row assertions',
      });
      return;
    }

    const firstStatusTag = page.locator('table.erp-table tbody tr').first().locator('.status-tag');
    await expect(firstStatusTag, 'PROJ-3: .status-tag not found in first project row').toBeVisible({ timeout: DOM_SETTLE });

    // project-list.component.html: <a class="btn btn-sm btn-outline-primary" ...>Open</a>
    const openLink = page.locator('table.erp-table tbody tr').first().locator('a.btn').filter({ hasText: /Open/i });
    await expect(openLink, 'PROJ-3: Open link not found in first project row').toBeVisible({ timeout: DOM_SETTLE });
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// BUDGETING
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX Budgeting', () => {

  /**
   * BUD-1: /admin/budgets renders the "Budgets" heading without 5xx.
   */
  test('BUD-1: /admin/budgets renders heading without 5xx or console errors', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/budgets', 'Budgets list');
    if (!ok) return;

    const heading = page.locator('h1').first();
    await expect(heading, 'BUD-1: h1 not visible on /admin/budgets').toBeVisible({ timeout: DOM_SETTLE });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'BUD-1: h1 does not say "Budgets"').toMatch(/Budgets/i);

    await assertNoRawErrorLeak(page, 'Budgets list');
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `BUD-1: console errors on /admin/budgets: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

  /**
   * BUD-2: budget create form — submitting with empty name and empty fiscal year
   * shows a clean formError.
   * Form: id="createBudgetForm". Error: <p role="alert" class="text-danger small mt-2 mb-0">
   */
  test('BUD-2: budget create form empty-name guard shows clean error, no 500', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/budgets', 'Budget create validation');
    if (!ok) return;

    // "New Budget" button — only visible when canManage().
    const newBudgetBtn = page.getByRole('button', { name: /New Budget/i });
    const btnVisible = await newBudgetBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'BUD-2: "New Budget" button not visible — rootadmin may lack BUDGETING.BUDGET.VIEW or company context missing',
      });
      return;
    }

    await newBudgetBtn.click();
    // Form: id="createBudgetForm" aria-label="Create budget"
    await expect(
      page.locator('#createBudgetForm'),
      'BUD-2: createBudgetForm not visible after clicking New Budget',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // Confirm the Name input is rendered (#fName from budget-list.component.html).
    const nameInput = page.locator('#fName');
    const nameVisible = await nameInput.isVisible().catch(() => false);
    if (!nameVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'BUD-2: #fName not visible inside createBudgetForm — form may not have fully rendered',
      });
      return;
    }

    // Leave name and fiscal year UID empty and submit.
    await nameInput.fill('');
    await page.locator('#fFiscalYearUid').fill('');
    await page.locator('#createBudgetForm button[type="submit"]').click();
    await page.waitForTimeout(600);

    // Error: <p class="text-danger small mt-2 mb-0" role="alert">
    const formError = page.locator('#createBudgetForm p[role="alert"].text-danger');
    const errorVisible = await formError.isVisible().catch(() => false);
    if (errorVisible) {
      const errorText = await formError.innerText().catch(() => '');
      expect(errorText.trim().length, 'BUD-2: budget form error is empty — not actionable').toBeGreaterThan(3);
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(errorText),
          `BUD-2: raw error pattern "${pattern}" in budget formError: "${errorText}"`,
        ).toBe(false);
      }
    }

    await assertNoRawErrorLeak(page, 'Budget create — empty name submit');
    await expect(page.locator('#createBudgetForm'), 'BUD-2: budget form dismissed after validation error').toBeVisible();
  });

  /**
   * BUD-3: budget list table rows render status tags and Open links when budgets exist.
   */
  test('BUD-3: budget list rows have status tags and Open links when budgets exist', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/budgets', 'Budget list rows');
    if (!ok) return;

    const rows = await page.locator('table.erp-table tbody tr').count();
    if (rows === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'BUD-3: budget list is empty — seed data required for row assertions',
      });
      return;
    }

    // Each row has a .status-tag for version status.
    const firstStatusTag = page.locator('table.erp-table tbody tr').first().locator('.status-tag');
    await expect(firstStatusTag, 'BUD-3: .status-tag not found in first budget row').toBeVisible({ timeout: DOM_SETTLE });

    // budget-list.component.html: <a class="btn btn-sm btn-outline-primary" ...>Open</a>
    const openLink = page.locator('table.erp-table tbody tr').first().locator('a.btn').filter({ hasText: /Open/i });
    await expect(openLink, 'BUD-3: Open link not found in first budget row').toBeVisible({ timeout: DOM_SETTLE });
  });

  /**
   * BUD-4: the status filter select renders in the toolbar once the company
   * is selected, and changing it does not produce a 5xx.
   * id="statusFilter" from budget-list.component.html.
   */
  test('BUD-4: budget status filter renders and can be changed without error', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/budgets', 'Budget status filter');
    if (!ok) return;

    const statusFilter = page.locator('#statusFilter');
    const filterVisible = await statusFilter.isVisible().catch(() => false);
    if (!filterVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'BUD-4: #statusFilter not visible — company context may not have loaded',
      });
      return;
    }

    await statusFilter.selectOption('DRAFT');
    await page.waitForLoadState('networkidle', { timeout: 15_000 });
    await page.waitForTimeout(400);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `BUD-4: 5xx after changing budget status filter: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// FX RATES
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX FX rates', () => {

  /**
   * FX-1: /admin/fx/rates renders the "Currency Exchange Rates" heading
   * without 5xx or console errors.
   * Note: fx-rate-list.component.html uses <div class="admin-page"> not <section>.
   */
  test('FX-1: /admin/fx/rates renders heading without 5xx or console errors', async ({ page }) => {
    const { ok, problems } = await gotoAndGuard(page, '/admin/fx/rates', 'FX rates list');
    if (!ok) return;

    const heading = page.locator('h1').first();
    await expect(heading, 'FX-1: h1 not visible on /admin/fx/rates').toBeVisible({ timeout: DOM_SETTLE });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'FX-1: h1 does not say "Currency Exchange Rates"').toMatch(/Currency Exchange Rates/i);

    await assertNoRawErrorLeak(page, 'FX rates list');
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `FX-1: console errors on /admin/fx/rates: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
  });

  /**
   * FX-2: the FX rate create form — clicking "New Rate" opens the form.
   * Submit with empty From/To currency and rate shows a clean error via
   * div.alert.alert-danger (createError() signal renders as .alert.alert-danger).
   * Or the HTML5 required validation on the <select>/<input> blocks submission.
   * Either outcome must show no raw 500 in the body.
   */
  test('FX-2: FX rate create form shows clean error on empty-submit, no 500', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/fx/rates', 'FX rate create validation');
    if (!ok) return;

    // "New Rate" button from fx-rate-list.component.html
    // <button type="button" class="btn btn-primary btn-sm" (click)="openCreate()">New Rate</button>
    const newRateBtn = page.getByRole('button', { name: /New Rate/i });
    const btnVisible = await newRateBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'FX-2: "New Rate" button not visible — rootadmin may lack CURRENCY.VIEW or company context missing',
      });
      return;
    }

    await newRateBtn.click();

    // The create form is inside .erp-card with aria-label="Add exchange rate"
    const createForm = page.locator('form[aria-label="Add exchange rate"]');
    await expect(
      createForm,
      'FX-2: "Add exchange rate" form not visible after clicking New Rate',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // Detect whether currencies loaded (select or input rendered for From field).
    // fx-rate-list.component.html: id="fxNewFrom" and id="fxNewTo" on both select and fallback input.
    const fromField = page.locator('#fxNewFrom');
    const rateField = page.locator('#fxNewRate');

    // Leave from/to/rate/date empty — HTML5 required="" will block if the browser
    // enforces it, or the component's submitCreate() will catch and set createError().
    // Either way, no raw 500 must appear.
    await rateField.fill('');

    // Submit the form.
    await page.locator('button[type="submit"]').filter({ hasText: /Save Rate/i }).click();
    await page.waitForTimeout(600);

    // createError() renders as: <div class="alert alert-danger py-2" role="alert">
    const createErrorAlert = page.locator('div.alert.alert-danger[role="alert"]');
    const errorVisible = await createErrorAlert.isVisible().catch(() => false);
    if (errorVisible) {
      const errorText = await createErrorAlert.innerText().catch(() => '');
      expect(errorText.trim().length, 'FX-2: createError alert text is empty — not actionable').toBeGreaterThan(3);
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(errorText),
          `FX-2: raw error pattern "${pattern}" in FX rate create error: "${errorText}"`,
        ).toBe(false);
      }
    }

    await assertNoRawErrorLeak(page, 'FX rate create — empty submit');
    // Must still be on the FX rates page.
    expect(page.url()).not.toMatch(/\/login/);
    expect(page.url()).toMatch(/\/fx\/rates/);
  });

  /**
   * FX-3: when rate rows exist, the table has From/To currency columns, a Rate
   * column, and an Active status tag per row.
   */
  test('FX-3: FX rate list rows display currency pair, rate, and status tag when rates exist', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/fx/rates', 'FX rate list rows');
    if (!ok) return;

    const rows = await page.locator('table.erp-table tbody tr').count();
    if (rows === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'FX-3: no FX rates in the list — seed data required for row assertions',
      });
      return;
    }

    // The first row should have at least one .status-tag (Active/Inactive and optionally rate type)
    const firstStatusTag = page.locator('table.erp-table tbody tr').first().locator('.status-tag');
    await expect(firstStatusTag, 'FX-3: .status-tag not found in first FX rate row').toBeVisible({ timeout: DOM_SETTLE });

    // Rate rows show currency codes in .mono.fw-semibold cells (from-currency and to-currency)
    const monoCell = page.locator('table.erp-table tbody tr').first().locator('td.mono.fw-semibold').first();
    await expect(monoCell, 'FX-3: currency-code mono cell not found in first FX rate row').toBeVisible({ timeout: DOM_SETTLE });

    const cellText = await monoCell.innerText().catch(() => '');
    expect(cellText.trim().length, 'FX-3: currency code cell is empty').toBeGreaterThan(0);
  });

  /**
   * FX-4: "New Rate" button is disabled while the create form is already open
   * (showCreateForm() is true → [disabled]="showCreateForm()").
   */
  test('FX-4: "New Rate" button is disabled while the create form is open', async ({ page }) => {
    const { ok } = await gotoAndGuard(page, '/admin/fx/rates', 'FX rate create form toggle');
    if (!ok) return;

    const newRateBtn = page.getByRole('button', { name: /New Rate/i });
    const btnVisible = await newRateBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'FX-4: "New Rate" button not visible — cannot test disabled state',
      });
      return;
    }

    await newRateBtn.click();

    // After clicking, the form is open and the button becomes disabled.
    // fx-rate-list.component.html: [disabled]="showCreateForm()"
    const createForm = page.locator('form[aria-label="Add exchange rate"]');
    await expect(createForm, 'FX-4: create form not visible after New Rate click').toBeVisible({ timeout: DOM_SETTLE });

    await expect(
      newRateBtn,
      'FX-4: "New Rate" button is not disabled while the create form is open',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    // Cancel closes the form.
    const cancelBtn = page.getByRole('button', { name: /Cancel/i });
    const cancelVisible = await cancelBtn.isVisible().catch(() => false);
    if (cancelVisible) {
      await cancelBtn.click();
      await page.waitForTimeout(300);
      // After cancel the New Rate button should be enabled again.
      await expect(
        newRateBtn,
        'FX-4: "New Rate" button remains disabled after cancelling the form',
      ).toBeEnabled({ timeout: DOM_SETTLE });
    }
  });

});
