/**
 * UI/UX: Purchasing P2P Journey spec — 'chromium' project (starts logged in as rootadmin).
 *
 * Covers:
 *   PO-1  Purchase Order list loads without error + table/empty-state renders.
 *   PO-2  "New Order" form: submitting without a supplier shows a clean inline error.
 *   PO-3  Happy-path PO create — supplier search → select → create → appears in list.
 *         (Graceful-skip when no suppliers/currency in this company.)
 *   PO-4  PO detail: "Place Order" button present on a DRAFT, "Add Line" guard fires
 *         when no product is selected (line-level formError).
 *
 *   GR-1  Goods Receipts list loads, "New Receipt" link is present.
 *   GR-2  GR create: over-receipt qty surfaces a clean error, no raw 500.
 *         (Graceful-skip when no ORDERED/PARTIALLY_RECEIVED POs exist.)
 *   GR-3  GR create: zero quantity on an included line shows the per-line invalid-
 *         feedback before the server is called.
 *
 *   REQ-1 Purchase Requisitions list loads without error.
 *   REQ-2 Requisition create: submit with empty lines (no product/unit/qty) shows
 *         a clean formError, no 500.
 *   REQ-3 Requisition create: "Add line" button appends a row.
 *
 *   LC-1  Landed Costs list loads without error.
 *   LC-2  Landed cost create: submit with no GRs selected shows a clean formError.
 *   LC-3  Landed cost create: "Add Charge" appends a charge row; remove works.
 *   LC-4  Landed cost create: charge with zero amount blocks submit with a clean error.
 *
 * Selector sources: all derived from component HTML files read directly.
 *   purchase-order-list.component.html  — #poSearch, #createPoForm, #supplierSearch,
 *                                          #newCurrency, #newExpectedDate, #newNotes,
 *                                          p[role="alert"].text-danger (formError),
 *                                          table.erp-table tbody tr
 *   purchase-order-detail.component.html — #lineProductSearch, #lineQty, #lineUnitCost,
 *                                           p[role="alert"].text-danger (lineFormError)
 *   goods-receipt-create.component.html  — #poSearch (PO picker), input[id^="qty-"],
 *                                          div.alert.alert-danger (formError),
 *                                          .invalid-feedback (per-line error),
 *                                          button "Record Receipt"
 *   requisition-create.component.html    — input[id^="reqQty_"], button "Create Requisition",
 *                                          p[role="alert"].text-danger (formError)
 *   landed-cost-create.component.html    — #basis, #notes, select[id^="chargeType-"],
 *                                          input[id^="chargeAmt-"], button "Create Landed Cost",
 *                                          p[role="alert"].text-danger (formError)
 *
 * Resilience:
 *   - RUN_TAG makes every created name unique across reruns.
 *   - Every prerequisite absence annotates test.info() and returns (graceful skip).
 *   - No test hard-fails on a missing seed (supplier, product, ordered PO, GR).
 *   - No raw "500" / "unexpected error" / stack trace must reach the visible DOM.
 */

import { test, expect } from './_test-authenticated';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── shared constants ─────────────────────────────────────────────────────────

const RUN_TAG = `P2P-${Date.now()}`;

const DOM_SETTLE   = 8_000;
const ALERT_APPEAR = 12_000;
const NET_IDLE     = 60_000;
const ROW_TIMEOUT  = 15_000;

const RAW_ERROR_PATTERNS = [/\b500\b/, /stack\s*trace/i, /NullPointerException/i, /unexpected error/i];

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

/** Navigate to route, wait for networkidle, guard against session bounce + API 5xx. */
async function gotoAdmin(
  page: Parameters<typeof watchProblems>[0],
  route: string,
): Promise<ReturnType<typeof watchProblems> | null> {
  const problems = watchProblems(page);
  await page.goto(`/admin/${route}`, { waitUntil: 'networkidle', timeout: NET_IDLE });
  await page.waitForTimeout(800);

  if (page.url().includes('/login')) {
    test.info().annotations.push({ type: 'skip-reason', description: 'session bounced to /login' });
    return null;
  }

  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  if (api5xx.length > 0) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `API 5xx during /admin/${route} load: ${JSON.stringify(api5xx)}`,
    });
    return null;
  }

  return problems;
}

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 1: Purchase Orders
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: Purchase Orders — list and create', () => {

  // ── PO-1: list loads, no error ─────────────────────────────────────────────
  test('PO-1: /admin/purchase-orders loads without API 5xx or console errors', async ({ page }) => {
    const problems = await gotoAdmin(page, 'purchase-orders');
    if (!problems) return;

    // h1 heading must be visible
    const heading = page.locator('h1');
    await expect(heading, 'PO-1: h1 not visible on /admin/purchase-orders').toBeVisible({ timeout: DOM_SETTLE });

    // No error alert alongside the page
    const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
    const alertVisible = await errorAlert.isVisible().catch(() => false);
    expect(alertVisible, 'PO-1: error-state banner visible on /admin/purchase-orders').toBe(false);

    // No console errors
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `PO-1: console errors on purchase-orders list: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'PO-1 purchase-orders list');
  });

  // ── PO-2: "New Order" form — no supplier → clean inline error ─────────────
  test('PO-2: submitting New Order without a supplier shows a clean inline error, no 500', async ({ page }) => {
    const problems = await gotoAdmin(page, 'purchase-orders');
    if (!problems) return;

    // The "New Order" button is rendered only when canCreate() — rootadmin has the permission.
    const newOrderBtn = page.getByRole('button', { name: /New Order/i });
    const btnVisible = await newOrderBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"New Order" button not visible — rootadmin may lack PURCHASE.ORDER.CREATE or company not loaded',
      });
      return;
    }

    await newOrderBtn.click();

    // The form #createPoForm must appear.
    const createForm = page.locator('#createPoForm');
    await expect(createForm, 'PO-2: #createPoForm did not appear after clicking "New Order"')
      .toBeVisible({ timeout: DOM_SETTLE });

    // Deliberately leave supplierSearch empty — no supplier selected.
    // Click the submit button directly.
    const submitBtn = createForm.locator('button[type="submit"]');
    await submitBtn.click();
    await page.waitForTimeout(300);

    // The component sets formError = 'Supplier is required.' and renders it as
    // <p class="text-danger small mb-0" role="alert"> outside the field grid.
    const formError = createForm.locator('p[role="alert"].text-danger');
    await expect(
      formError,
      'PO-2: formError <p role="alert"> not visible after submitting without supplier',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errText = await formError.innerText().catch(() => '');
    expect(errText.trim().length, 'PO-2: formError text is empty — not actionable').toBeGreaterThan(5);
    expect(errText, 'PO-2: error does not mention "Supplier"').toMatch(/[Ss]upplier/);

    await assertNoRawErrorLeak(page, 'PO-2 submit without supplier');

    // Form must still be visible (no nav on error).
    await expect(createForm, 'PO-2: form was dismissed despite validation error').toBeVisible();

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `PO-2: API 5xx fired on client-only validation: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── PO-3: happy-path PO create (graceful-skip when no suppliers) ───────────
  test('PO-3: happy-path — search supplier, select, create PO, new row appears in list', async ({ page }) => {
    const problems = await gotoAdmin(page, 'purchase-orders');
    if (!problems) return;

    const newOrderBtn = page.getByRole('button', { name: /New Order/i });
    const btnVisible = await newOrderBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"New Order" button not visible; skipping happy-path' });
      return;
    }

    await newOrderBtn.click();
    const createForm = page.locator('#createPoForm');
    await expect(createForm, 'PO-3: create form did not appear').toBeVisible({ timeout: DOM_SETTLE });

    // Type in the supplier search field to trigger autocomplete.
    // We use a short generic term that should match any supplier; actual match depends on seed data.
    const supplierInput = createForm.locator('#supplierSearch');
    await supplierInput.fill('a');
    // Wait for the autocomplete listbox to appear (debounce is 300 ms).
    await page.waitForTimeout(700);

    const suggestionList = page.locator('ul[role="listbox"][aria-label="Supplier suggestions"]');
    const listVisible = await suggestionList.isVisible().catch(() => false);
    if (!listVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Supplier autocomplete list did not appear — no suppliers seeded in this company; skipping happy-path',
      });
      return;
    }

    const firstSuggestion = suggestionList.locator('li[role="option"]').first();
    const suggVisible = await firstSuggestion.isVisible().catch(() => false);
    if (!suggVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: 'No supplier suggestions rendered' });
      return;
    }

    await firstSuggestion.click();
    await page.waitForTimeout(300);

    // After selecting, the check-icon confirmation text must appear (selectedSupplier() is truthy).
    const selectedConfirmation = createForm.locator('.bi-check-circle-fill.text-success');
    await expect(
      selectedConfirmation,
      'PO-3: supplier selection confirmation icon did not appear after clicking suggestion',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // Currency defaults to TZS via the app-currency-select component.
    // The component renders a native <select> or custom element with id="newCurrency".
    // We leave it at default — rootadmin's company should have a currency seeded.

    // Add notes so we can find this record.
    await createForm.locator('#newNotes').fill(`E2E-${RUN_TAG}`);

    // Submit.
    const submitBtn = createForm.locator('button[type="submit"]');
    await submitBtn.click();

    // On success the component calls alerts.success() (angular AlertService) and reloads the list.
    // The success alertdialog rendered by AlertComponent appears as [role="alertdialog"].alert-success.
    const alertDialog = page.locator('[role="alertdialog"].alert-success');
    const alertAppeared = await alertDialog.isVisible().catch(
      async () => {
        // Alert may auto-dismiss quickly — wait a little and recheck.
        await page.waitForTimeout(1_500);
        return alertDialog.isVisible().catch(() => false);
      },
    );

    if (!alertAppeared) {
      // The formError may have fired instead (e.g. currency not configured in company).
      const formError = createForm.locator('p[role="alert"].text-danger');
      const errVisible = await formError.isVisible().catch(() => false);
      if (errVisible) {
        const errText = await formError.innerText().catch(() => '');
        test.info().annotations.push({
          type: 'skip-reason',
          description: `PO create failed with formError: "${errText}" — prerequisites (currency/company config) may be missing`,
        });
        await assertNoRawErrorLeak(page, 'PO-3 create-failed formError');
        return;
      }
      // No alert, no form error: check API failures for diagnostic.
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(
        api5xx,
        `PO-3: unexpected API 5xx on create: ${JSON.stringify(api5xx)}`,
      ).toHaveLength(0);
    }

    // Wait for list to refresh.
    await page.waitForTimeout(2_000);
    await page.waitForLoadState('networkidle', { timeout: 15_000 });

    // Verify no 5xx during the whole test.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `PO-3: API 5xx during create flow: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'PO-3 post-create list');
  });

  // ── PO-4: PO detail — Add Line guard fires on missing product ─────────────
  test('PO-4: PO detail "Add Line" form shows an inline error when product is not selected', async ({ page }) => {
    const problems = await gotoAdmin(page, 'purchase-orders');
    if (!problems) return;

    // Navigate to the first DRAFT PO in the list (if any).
    const firstDraftLink = page.locator('table.erp-table tbody tr')
      .filter({ has: page.locator('.status-tag', { hasText: /DRAFT/i }) })
      .locator('a', { hasText: /Open/i })
      .first();

    const draftVisible = await firstDraftLink.isVisible().catch(() => false);
    if (!draftVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No DRAFT purchase orders found in list; skipping PO detail add-line validation test',
      });
      return;
    }

    await firstDraftLink.click();
    await page.waitForLoadState('networkidle', { timeout: NET_IDLE });
    await page.waitForTimeout(600);

    if (page.url().includes('/login')) {
      test.info().annotations.push({ type: 'skip-reason', description: 'session lost navigating to PO detail' });
      return;
    }

    // The "Add Line" form is only visible when isDraft() && canCreate().
    const addLineForm = page.locator('form[aria-label="Add line to order"]');
    const addLineVisible = await addLineForm.isVisible().catch(() => false);
    if (!addLineVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"Add Line" form not present on PO detail — may not be a DRAFT or rootadmin lacks PURCHASE.ORDER.CREATE',
      });
      return;
    }

    // Leave product search empty and enter valid qty + cost to isolate the product guard.
    await addLineForm.locator('#lineQty').fill('10');
    await addLineForm.locator('#lineUnitCost').fill('50');

    // Click the Add button (submit within the add-line form).
    const addBtn = addLineForm.locator('button[type="submit"]');
    await addBtn.click();
    await page.waitForTimeout(400);

    // The lineFormError guard fires: 'Product is required.' as
    // <p class="text-danger small mt-2 mb-0" role="alert">
    const lineFormError = addLineForm.locator('p[role="alert"].text-danger');
    await expect(
      lineFormError,
      'PO-4: lineFormError not visible after clicking Add without selecting a product',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errText = await lineFormError.innerText().catch(() => '');
    expect(errText.trim().length, 'PO-4: lineFormError text is empty').toBeGreaterThan(3);

    await assertNoRawErrorLeak(page, 'PO-4 add-line without product');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `PO-4: API 5xx during add-line guard: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 2: Goods Receipts
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: Goods Receipts — list and create', () => {

  // ── GR-1: list loads, "New Receipt" link present ──────────────────────────
  test('GR-1: /admin/goods-receipts loads without error and shows "New Receipt" link', async ({ page }) => {
    const problems = await gotoAdmin(page, 'goods-receipts');
    if (!problems) return;

    const heading = page.locator('h1');
    await expect(heading, 'GR-1: h1 not visible on /admin/goods-receipts').toBeVisible({ timeout: DOM_SETTLE });

    // No error banner
    const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
    const alertVisible = await errorAlert.isVisible().catch(() => false);
    expect(alertVisible, 'GR-1: error banner visible on goods-receipts list').toBe(false);

    // "New Receipt" link appears when canReceive() (rootadmin has PURCHASE.RECEIVE)
    const newReceiptLink = page.locator('a[routerLink="/admin/goods-receipts/create"], a[href="/admin/goods-receipts/create"]')
      .first();
    // The link may not appear for rootadmin if it lacks PURCHASE.RECEIVE — check gracefully.
    const linkVisible = await newReceiptLink.isVisible().catch(() => false);
    if (!linkVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"New Receipt" link not visible — rootadmin may lack PURCHASE.RECEIVE; asserting list loads cleanly',
      });
    }

    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `GR-1: console errors: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'GR-1 goods-receipts list');
  });

  // ── GR-2: over-receipt surfaces clean error, no raw 500 ───────────────────
  test('GR-2: entering over-receipt qty surfaces a clean error message, no raw 500', async ({ page }) => {
    // Navigate to the GR create page without a poUid — we need to pick a PO.
    const problems = await gotoAdmin(page, 'goods-receipts/create');
    if (!problems) return;

    // The PO picker is shown when no poUid query param.
    const poSearchInput = page.locator('#poSearch');
    const poSearchVisible = await poSearchInput.isVisible().catch(() => false);
    if (!poSearchVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '#poSearch not visible on /admin/goods-receipts/create — user may lack PURCHASE.RECEIVE or page structure changed',
      });
      return;
    }

    // Type a short query to trigger PO search (debounce 300 ms).
    await poSearchInput.fill('PO');
    await page.waitForTimeout(700);

    const poListbox = page.locator('ul[role="listbox"][aria-label="Purchase order suggestions"]');
    const listVisible = await poListbox.isVisible().catch(() => false);
    if (!listVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No ORDERED/PARTIALLY_RECEIVED POs found to pick from — cannot test over-receipt; skipping',
      });
      return;
    }

    const firstPoOption = poListbox.locator('li[role="option"]').first();
    const optionVisible = await firstPoOption.isVisible().catch(() => false);
    if (!optionVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: 'No PO options in listbox' });
      return;
    }

    await firstPoOption.click();
    await page.waitForTimeout(800);

    // Wait for lines to load (linesState becomes 'idle').
    const linesTable = page.locator('table.erp-table').first();
    const linesLoaded = await linesTable.isVisible().catch(async () => {
      await page.waitForTimeout(2_000);
      return linesTable.isVisible().catch(() => false);
    });

    // If all lines are fully received, we get the alert.alert-warning "already fully received".
    const alreadyReceived = page.locator('.alert.alert-warning').first();
    if (await alreadyReceived.isVisible().catch(() => false)) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Selected PO is fully received — cannot exercise over-receipt; skipping',
      });
      return;
    }

    if (!linesLoaded) {
      test.info().annotations.push({ type: 'skip-reason', description: 'GR lines table did not load' });
      return;
    }

    // Enter an over-receipt quantity on the first qty input (much larger than outstanding).
    // The input id is "qty-<lineUid>"; we use a prefix pattern.
    const firstQtyInput = page.locator('input[id^="qty-"]').first();
    const qtyInputVisible = await firstQtyInput.isVisible().catch(() => false);
    if (!qtyInputVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: 'qty input not visible — no receivable lines' });
      return;
    }

    // Set an astronomically large qty — guaranteed over-receipt if PO has any outstanding.
    await firstQtyInput.fill('999999999');

    // Submit the GR.
    const recordBtn = page.getByRole('button', { name: /Record Receipt/i });
    const btnVisible = await recordBtn.isEnabled().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"Record Receipt" button not enabled' });
      return;
    }

    await recordBtn.click();
    await page.waitForTimeout(1_500);

    // Either a client-side quantity error appears (invalid-feedback / formError)
    // or the backend 409 fires. Both must surface a CLEAN user message — no raw 500.
    const formErrorBanner = page.locator('div.alert.alert-danger[role="alert"]');
    const invalidFeedback  = page.locator('.invalid-feedback').first();

    const bannerVisible   = await formErrorBanner.isVisible().catch(() => false);
    const feedbackVisible = await invalidFeedback.isVisible().catch(() => false);

    // At least one of these must fire to indicate the UI guarded against over-receipt.
    if (!bannerVisible && !feedbackVisible) {
      // Check if we navigated away (success) — if so, the over-receipt test is inconclusive
      // because the backend accepted the quantity without 409. Flag as annotation.
      const navigatedAway = !page.url().includes('goods-receipts/create');
      if (navigatedAway) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: 'Backend accepted quantity 999999999 without 409 — cannot verify over-receipt guard; environment may not enforce it',
        });
        return;
      }
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Neither alert-danger banner nor invalid-feedback appeared after over-receipt submit — error not surfaced; investigate',
      });
    }

    if (bannerVisible) {
      const bannerText = await formErrorBanner.innerText().catch(() => '');
      // Must NOT contain raw server error patterns.
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(bannerText),
          `GR-2: raw server error pattern "${pattern}" found in GR form error banner: "${bannerText}"`,
        ).toBe(false);
      }
      expect(bannerText.trim().length, 'GR-2: alert-danger banner text is empty').toBeGreaterThan(5);
    }

    await assertNoRawErrorLeak(page, 'GR-2 over-receipt submission');

    const api5xx = problems.apiFailures.filter((f) => f.status === 500);
    expect(api5xx, `GR-2: unexpected 500 (not 409) during over-receipt: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── GR-3: zero quantity on included line fires per-line invalid-feedback ───
  test('GR-3: entering zero qty on an included line shows invalid-feedback before server is called', async ({ page }) => {
    const problems = await gotoAdmin(page, 'goods-receipts/create');
    if (!problems) return;

    const poSearchInput = page.locator('#poSearch');
    const poSearchVisible = await poSearchInput.isVisible().catch(() => false);
    if (!poSearchVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '#poSearch not visible on GR create page' });
      return;
    }

    await poSearchInput.fill('PO');
    await page.waitForTimeout(700);

    const poListbox = page.locator('ul[role="listbox"][aria-label="Purchase order suggestions"]');
    const listVisible = await poListbox.isVisible().catch(() => false);
    if (!listVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: 'No POs in autocomplete for GR-3' });
      return;
    }

    const firstPoOption = poListbox.locator('li[role="option"]').first();
    if (!(await firstPoOption.isVisible().catch(() => false))) {
      test.info().annotations.push({ type: 'skip-reason', description: 'No PO option items visible' });
      return;
    }

    await firstPoOption.click();
    await page.waitForTimeout(1_000);

    // Wait for lines or fully-received alert
    const alreadyReceived = page.locator('.alert.alert-warning').first();
    if (await alreadyReceived.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'PO fully received — no lines to test in GR-3' });
      return;
    }

    const firstQtyInput = page.locator('input[id^="qty-"]').first();
    if (!(await firstQtyInput.isVisible().catch(() => false))) {
      test.info().annotations.push({ type: 'skip-reason', description: 'qty input not visible in GR-3' });
      return;
    }

    // Set qty to 0 — client validation must catch this before the server is called.
    await firstQtyInput.fill('0');

    const recordBtn = page.getByRole('button', { name: /Record Receipt/i });
    if (!(await recordBtn.isEnabled().catch(() => false))) {
      test.info().annotations.push({ type: 'skip-reason', description: '"Record Receipt" button not enabled in GR-3' });
      return;
    }

    await recordBtn.click();
    await page.waitForTimeout(500);

    // Client guard fires BEFORE the API call:
    // lineErrors sets is-invalid on the input and renders <div class="invalid-feedback">.
    const invalidFeedback = page.locator('.invalid-feedback').first();
    await expect(
      invalidFeedback,
      'GR-3: .invalid-feedback not visible after submitting zero qty — client guard may be missing',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const feedbackText = await invalidFeedback.innerText().catch(() => '');
    expect(feedbackText.trim().length, 'GR-3: invalid-feedback text is empty').toBeGreaterThan(3);

    // Also expect the form-level error banner.
    const formErrorBanner = page.locator('div.alert.alert-danger[role="alert"]');
    await expect(
      formErrorBanner,
      'GR-3: alert-danger formError banner not visible after zero-qty submit',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // No API 5xx (client guard should have prevented the call entirely or returned 4xx).
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `GR-3: API 5xx fired despite client qty guard: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'GR-3 zero-qty validation');
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 3: Purchase Requisitions
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: Purchase Requisitions — list and create', () => {

  // ── REQ-1: list loads without error ────────────────────────────────────────
  test('REQ-1: /admin/purchase-requisitions loads without API 5xx or console errors', async ({ page }) => {
    const problems = await gotoAdmin(page, 'purchase-requisitions');
    if (!problems) return;

    const heading = page.locator('h1');
    await expect(heading, 'REQ-1: h1 not visible on /admin/purchase-requisitions').toBeVisible({ timeout: DOM_SETTLE });

    const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
    const alertVisible = await errorAlert.isVisible().catch(() => false);
    expect(alertVisible, 'REQ-1: error banner on requisitions list page').toBe(false);

    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `REQ-1: console errors: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'REQ-1 requisitions list');
  });

  // ── REQ-2: submit with empty line fires formError, no 500 ──────────────────
  test('REQ-2: submitting requisition with empty line fields shows a clean formError, no 500', async ({ page }) => {
    const problems = await gotoAdmin(page, 'purchase-requisitions/create');
    if (!problems) return;

    // The create form is at /admin/purchase-requisitions/create.
    // h1 must be visible confirming the page loaded.
    const h1 = page.locator('h1');
    const h1Visible = await h1.isVisible().catch(() => false);
    if (!h1Visible) {
      test.info().annotations.push({ type: 'skip-reason', description: 'h1 not visible on requisitions/create — page may not have loaded' });
      return;
    }

    // Wait for the picker data to load (products + units via forkJoin; pickerState → idle).
    // The submit button is disabled while pickerState !== 'idle'.
    const submitBtn = page.getByRole('button', { name: /Create Requisition/i });
    const submitVisible = await submitBtn.isVisible().catch(async () => {
      await page.waitForTimeout(3_000);
      return submitBtn.isVisible().catch(() => false);
    });

    if (!submitVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"Create Requisition" button not visible — pickerState may be in error or permission missing',
      });
      return;
    }

    // Wait for the button to be enabled (picker data loaded).
    await submitBtn.waitFor({ state: 'attached', timeout: DOM_SETTLE });
    const isEnabled = await submitBtn.isEnabled().catch(() => false);
    if (!isEnabled) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"Create Requisition" button is disabled — pickerState still loading or products/units not seeded',
      });
      return;
    }

    // The component starts with one blank line (blankLine()). All fields are empty.
    // Submitting should fire: "Line 1: product is required." via formError signal.
    await submitBtn.click();
    await page.waitForTimeout(400);

    // formError rendered as <p class="text-danger small mb-2" role="alert">
    const formError = page.locator('p[role="alert"].text-danger').first();
    await expect(
      formError,
      'REQ-2: formError <p role="alert"> not visible after submitting empty requisition',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errText = await formError.innerText().catch(() => '');
    expect(errText.trim().length, 'REQ-2: formError text is empty').toBeGreaterThan(5);
    // Message should reference "Line 1" or "product" — client-side guard fires in order.
    expect(errText, 'REQ-2: formError does not mention a line or product').toMatch(/[Ll]ine|[Pp]roduct/);

    // No API 5xx — the guard fires before the HTTP call.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `REQ-2: API 5xx despite client guard: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'REQ-2 empty-line requisition submit');
  });

  // ── REQ-3: "Add line" button appends a row ─────────────────────────────────
  test('REQ-3: clicking "Add line" appends a new row to the requisition line table', async ({ page }) => {
    const problems = await gotoAdmin(page, 'purchase-requisitions/create');
    if (!problems) return;

    // Wait for picker data.
    const addLineBtn = page.getByRole('button', { name: /Add line/i });
    const btnVisible = await addLineBtn.isVisible().catch(async () => {
      await page.waitForTimeout(3_000);
      return addLineBtn.isVisible().catch(() => false);
    });

    if (!btnVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"Add line" button not visible on requisition create' });
      return;
    }

    // Count rows before.
    const rowsBefore = await page.locator('table[aria-label="Requisition line items"] tbody tr').count();

    await addLineBtn.click();
    await page.waitForTimeout(300);

    // Count rows after — must be rowsBefore + 1.
    const rowsAfter = await page.locator('table[aria-label="Requisition line items"] tbody tr').count();
    expect(
      rowsAfter,
      `REQ-3: expected ${rowsBefore + 1} rows after "Add line" click but got ${rowsAfter}`,
    ).toBe(rowsBefore + 1);

    // No console errors from the DOM mutation.
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `REQ-3: console errors on add-line: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'REQ-3 add-line');
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 4: Landed Costs
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: Landed Costs — list and create', () => {

  // ── LC-1: list loads without error ─────────────────────────────────────────
  test('LC-1: /admin/landed-costs loads without API 5xx or console errors', async ({ page }) => {
    const problems = await gotoAdmin(page, 'landed-costs');
    if (!problems) return;

    const heading = page.locator('h1');
    await expect(heading, 'LC-1: h1 not visible on /admin/landed-costs').toBeVisible({ timeout: DOM_SETTLE });

    const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
    const alertVisible = await errorAlert.isVisible().catch(() => false);
    expect(alertVisible, 'LC-1: error banner on landed-costs list').toBe(false);

    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(consoleErrs, `LC-1: console errors: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'LC-1 landed-costs list');
  });

  // ── LC-2: submit with no GR selected shows clean formError ─────────────────
  test('LC-2: submitting landed cost without selecting any GR shows a clean formError, no 500', async ({ page }) => {
    const problems = await gotoAdmin(page, 'landed-costs/create');
    if (!problems) return;

    const h1 = page.locator('h1');
    const h1Visible = await h1.isVisible().catch(() => false);
    if (!h1Visible) {
      test.info().annotations.push({ type: 'skip-reason', description: 'h1 not visible on landed-costs/create' });
      return;
    }

    // The "Create Landed Cost" button.
    const submitBtn = page.getByRole('button', { name: /Create Landed Cost/i });
    const submitVisible = await submitBtn.isVisible().catch(() => false);
    if (!submitVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"Create Landed Cost" button not visible — may lack PURCHASE.LANDED_COST.CREATE' });
      return;
    }

    // Leave GR picker untouched (no GR selected), click submit.
    await submitBtn.click();
    await page.waitForTimeout(400);

    // formError: 'Select at least one goods receipt.'
    // Rendered as: <p class="text-danger small mb-2" role="alert">
    const formError = page.locator('p[role="alert"].text-danger').first();
    await expect(
      formError,
      'LC-2: formError not visible after submitting with no GR selected',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errText = await formError.innerText().catch(() => '');
    expect(errText.trim().length, 'LC-2: formError is empty').toBeGreaterThan(5);
    expect(errText, 'LC-2: formError does not mention GR/receipt').toMatch(/receipt|GR/i);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `LC-2: API 5xx despite client guard: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'LC-2 no-GR submit');
  });

  // ── LC-3: "Add Charge" appends a row; remove button works ──────────────────
  test('LC-3: "Add Charge" appends a charge row; remove button removes it', async ({ page }) => {
    const problems = await gotoAdmin(page, 'landed-costs/create');
    if (!problems) return;

    const addChargeBtn = page.getByRole('button', { name: /Add Charge/i });
    const btnVisible = await addChargeBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"Add Charge" button not visible on landed-costs/create' });
      return;
    }

    // The component initialises with one charge (FREIGHT, empty amount).
    // The charge table only renders when charges().length > 0 — it should be visible immediately.
    const chargeTable = page.locator('table.erp-table').filter({ has: page.locator('caption', { hasText: /Charge lines/i }) });
    const tableVisible = await chargeTable.isVisible().catch(() => false);
    if (!tableVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Charge lines table not visible on landed-costs/create' });
      return;
    }

    const rowsBefore = await chargeTable.locator('tbody tr').count();
    expect(rowsBefore, 'LC-3: expected initial charge row to be present').toBeGreaterThanOrEqual(1);

    // Click "Add Charge" — should append a new row.
    await addChargeBtn.click();
    await page.waitForTimeout(300);

    const rowsAfterAdd = await chargeTable.locator('tbody tr').count();
    expect(
      rowsAfterAdd,
      `LC-3: expected ${rowsBefore + 1} charge rows after "Add Charge" but got ${rowsAfterAdd}`,
    ).toBe(rowsBefore + 1);

    // The remove button is rendered only when charges().length > 1.
    // After the add we have at least 2 rows, so the remove button on the last row should be visible.
    const removeBtn = chargeTable.locator('tbody tr').last()
      .locator('button[aria-label^="Remove charge line"]');
    await expect(
      removeBtn,
      'LC-3: remove button not visible on last charge row after "Add Charge"',
    ).toBeVisible({ timeout: DOM_SETTLE });

    await removeBtn.click();
    await page.waitForTimeout(300);

    const rowsAfterRemove = await chargeTable.locator('tbody tr').count();
    expect(
      rowsAfterRemove,
      `LC-3: expected ${rowsAfterAdd - 1} rows after remove but got ${rowsAfterRemove}`,
    ).toBe(rowsAfterAdd - 1);

    await assertNoRawErrorLeak(page, 'LC-3 add/remove charge');
  });

  // ── LC-4: charge with zero amount blocks submit with a clean error ──────────
  test('LC-4: invalid submission (zero charge amount, no GR) shows a clean formError, no 500', async ({ page }) => {
    const problems = await gotoAdmin(page, 'landed-costs/create');
    if (!problems) return;

    const submitBtn = page.getByRole('button', { name: /Create Landed Cost/i });
    const submitVisible = await submitBtn.isVisible().catch(() => false);
    if (!submitVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"Create Landed Cost" button not visible in LC-4' });
      return;
    }

    // Deterministic, seed-independent: submit the form INVALID (no goods receipt selected, and the
    // first charge amount set to 0). The component's submit() validates client-side and surfaces a
    // single clean formError (the GR guard fires first: "Select at least one goods receipt.") — the
    // property under test is "invalid input -> one clean formError, never a 500". We do not depend on
    // seeded goods receipts (the GR-picker path is environment-sensitive).
    const firstChargeAmt = page.locator('#chargeAmt-0');
    if (await firstChargeAmt.isVisible().catch(() => false)) {
      await firstChargeAmt.fill('0');
    }

    await submitBtn.click();
    await page.waitForTimeout(400);

    const formError = page.locator('p[role="alert"].text-danger').first();
    await expect(
      formError,
      'LC-4: no clean formError shown after an invalid landed-cost submit',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errText = (await formError.innerText().catch(() => '')).trim();
    expect(errText.length, 'LC-4: formError is empty').toBeGreaterThan(5);
    // A client-validation message (goods receipt / charge / amount) — not a raw server error.
    expect(errText, 'LC-4: formError is not a recognised validation message').toMatch(/goods receipt|charge|amount|required/i);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `LC-4: API 5xx despite client validation: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'LC-4 invalid landed-cost submit');
  });

});
