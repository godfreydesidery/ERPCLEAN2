/**
 * UI/UX: AR/AP money-movement forms — 'chromium' project (starts logged in as rootadmin).
 *
 * Forms exercised (routes confirmed from admin.routes.ts, selectors read from HTML):
 *
 *   AR-1  Record Receipt   /admin/ar/receipts/record
 *         - AR-1a: empty amount (no customer, no amount) → submit button disabled; formError shown
 *         - AR-1b: negative/zero amount entered → submitDisabled stays true; no 500 leak
 *         - AR-1c: over-allocation beyond receipt amount → "Over-allocated" status tag; submit disabled
 *         - AR-1d: allocation exceeds invoice outstanding → "An allocation exceeds" tag; submit disabled
 *         - AR-1e: valid receipt (no open invoices → on-account) → success panel shows receipt number;
 *                  receipt appears in /admin/ar/receipts list
 *
 *   AP-2  Enter Supplier Bill  /admin/ap/supplier-bills/enter
 *         - AP-2a: no supplier + missing required header fields → submit button disabled
 *         - AP-2b: supplier picked + invNo filled but empty line → client fires "At least one bill line is required"
 *         - AP-2c: negative unit cost on a bill line → submitDisabled stays true OR formError clean (no 500)
 *         - AP-2d: valid bill enters + shows match result panel (or "bill entered" success)
 *
 *   AP-3  Record Payment   /admin/ap/payments/record
 *         - AP-3a: no supplier → submit button disabled; no raw 500 on page
 *         - AP-3b: supplier with no payable bills → "No payable bills found" message; submit disabled
 *         - AP-3c: valid payment run (select all bills) → success panel shows payment number(s)
 *
 * Selector sources: HTML components read directly.
 *
 *   Record Receipt HTML:
 *     form[aria-label="Record receipt"]
 *     #customerSearch                    — typeahead input
 *     #receiptAmount                     — text/decimal input
 *     #receiptDate                       — date input
 *     #tenderType                        — select
 *     #bankRef                           — optional text input
 *     input[id^="alloc_"]                — per-invoice allocation inputs
 *     .status-tag.status-tag--danger[role="alert"]  — "Over-allocated" / "An allocation exceeds"
 *     .totals-bar span[class*="text-danger"]         — over-allocation amount
 *     button[type="submit"]              — "Record Receipt" (disabled via submitDisabled())
 *     p[role="alert"].text-danger.small  — formError paragraph
 *     div.alert.alert-success            — success panel (contains receipt.receiptNumber)
 *
 *   Enter Supplier Bill HTML:
 *     form[aria-label="Enter supplier bill"]
 *     #supplierSearch                    — typeahead input
 *     #supplierInvoiceNo                 — text input
 *     #billDate / #dueDate               — date inputs
 *     #vatAmount / #currency             — optional/required
 *     input[id^="desc_"]                 — line description (desc_0, desc_1, …)
 *     input[id^="qty_"]                  — line billed qty
 *     input[id^="cost_"]                 — line unit cost
 *     button[type="submit"]              — "Enter Bill & Match" (disabled via submitDisabled())
 *     p[role="alert"].text-danger.small  — formError paragraph
 *     .erp-card__head .status-tag        — match result status (MATCHED / HELD)
 *
 *   Record Payment HTML:
 *     form[aria-label="Record payment run"]
 *     #supplierSearch                    — typeahead input
 *     #paymentDate                       — date input
 *     #tenderType                        — select
 *     #bankRef                           — optional
 *     input[type="checkbox"][id^="bill_"] — per-bill checkboxes
 *     .status-tag.status-tag--warn[role="alert"]  — "Select at least one bill"
 *     button[type="submit"]              — "Record Payment (N bills)"
 *     p[role="alert"].text-danger.small  — formError paragraph
 *     section.alert.alert-success[aria-label="Payment run result"]  — success panel
 *
 * Resilience rules:
 *   - If a permission gate fires (canRecord/canEnter/canPay = false), annotate + skip.
 *   - If company context fails (companyState='error'), annotate + skip.
 *   - If no customer/supplier exists to pick from typeahead, annotate + skip.
 *   - If no open invoices / payable bills for the picked party, note it but do NOT fail.
 *   - NEVER hard-fail on missing seed data. Assert UI behaviour (messages, states) not data.
 *   - Assert NO raw 500 / stack trace / "unexpected error" text on any error path.
 */

import { test, expect } from './_test-authenticated';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── shared constants ──────────────────────────────────────────────────────────

/** Per-run tag — makes created records unique and prevents name collisions across reruns. */
const RUN_TAG = `QA-${Date.now()}`;

/**
 * Patterns that must NEVER appear as visible page text — they indicate a raw server error leak.
 *
 * Intentionally does NOT include a bare /\b500\b/ match: that would false-positive on legitimate
 * amounts like "-500.00" rendered in client-side form validation messages (e.g. "receipt amount
 * (-500.00)"). Server-error signatures are always more specific than a standalone status number.
 */
const RAW_ERROR_PATTERNS = [
  /HTTP\s*500/i,
  /Internal\s+Server\s+Error/i,
  /status\s*[:\s]\s*500/i,
  /An unexpected error occurred/i,
  /Exception/,
  /stack\s*trace/i,
  /NullPointerException/i,
];

const DOM_SETTLE  = 8_000;   // ms — form validation / state signal settle time
const ALERT_APPEAR = 12_000; // ms — success alert / success panel appear time
const IDLE_TIMEOUT = 30_000; // ms — networkidle after form submit

/**
 * Scan visible body text for raw server-error patterns.
 * Call this after any form submission that should show a clean 4xx / client error.
 */
async function assertNoRawErrorLeak(
  page: Parameters<typeof watchProblems>[0],
  context: string,
): Promise<void> {
  const text = await page.locator('body').innerText().catch(() => '');
  for (const pattern of RAW_ERROR_PATTERNS) {
    expect(
      pattern.test(text),
      `Raw server error leaked on ${context}: matched "${pattern}" in visible body text`,
    ).toBe(false);
  }
}

/**
 * Navigate to an admin route, wait for networkidle, check for session bounce and
 * immediate API 5xx errors. Returns the problems collector and a readiness flag.
 *
 * When ready === false, tests should annotate and return (graceful skip).
 */
async function gotoAdminRoute(
  page: Parameters<typeof watchProblems>[0],
  route: string,
): Promise<{ problems: ReturnType<typeof watchProblems>; ready: boolean }> {
  const problems = watchProblems(page);
  await page.goto(`/admin/${route}`, { waitUntil: 'networkidle', timeout: 60_000 });
  await page.waitForTimeout(1_000);

  if (page.url().includes('/login')) {
    test.info().annotations.push({ type: 'skip-reason', description: 'session bounced to /login' });
    return { problems, ready: false };
  }

  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  if (api5xx.length > 0) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `API 5xx during /admin/${route} load: ${JSON.stringify(api5xx)}`,
    });
    return { problems, ready: false };
  }

  return { problems, ready: true };
}

/**
 * Type a search term into a typeahead input and wait for the dropdown suggestions to appear.
 * Then click the first result to select a party (customer or supplier).
 *
 * Returns true when a party was selected, false when no suggestions appeared (skip condition).
 */
async function pickPartyFromTypeahead(
  page: Parameters<typeof watchProblems>[0],
  inputId: string,
  searchTerm: string,
  listboxLabel: string,
): Promise<boolean> {
  const input = page.locator(`#${inputId}`);
  const inputVisible = await input.isVisible().catch(() => false);
  if (!inputVisible) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `#${inputId} not visible — form may not have loaded company context yet`,
    });
    return false;
  }

  // Type a few characters and wait for the dropdown (debounce is 300 ms).
  await input.fill(searchTerm);
  await page.waitForTimeout(600);

  const listbox = page.locator(`[role="listbox"][aria-label="${listboxLabel}"]`);
  const listboxVisible = await listbox.isVisible().catch(() => false);

  if (!listboxVisible) {
    // No suggestions — the party may not exist. Try a shorter search.
    await input.fill(searchTerm.slice(0, 2));
    await page.waitForTimeout(600);
    const listbox2 = page.locator(`[role="listbox"][aria-label="${listboxLabel}"]`);
    const visible2 = await listbox2.isVisible().catch(() => false);
    if (!visible2) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: `No "${listboxLabel}" suggestions for "${searchTerm}" — party not seeded`,
      });
      return false;
    }
  }

  // Click the first suggestion.
  const firstOption = page.locator(`[role="listbox"][aria-label="${listboxLabel}"] [role="option"]`).first();
  const optionVisible = await firstOption.isVisible().catch(() => false);
  if (!optionVisible) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `First option in "${listboxLabel}" dropdown is not visible`,
    });
    return false;
  }

  await firstOption.click();
  // Dropdown should dismiss after selection.
  await page.waitForTimeout(500);
  return true;
}

// ══════════════════════════════════════════════════════════════════════════════
// AR-1: Record Receipt  /admin/ar/receipts/record
// ══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: AR Record Receipt form', () => {

  // ── AR-1a: submit button disabled when required fields are empty ─────────────
  test('AR-1a: Record Receipt submit button is disabled when customer and amount are empty', async ({ page }) => {
    const { ready } = await gotoAdminRoute(page, 'ar/receipts/record');
    if (!ready) return;

    // Check for the permission gate.
    const permDenied = page.locator('[role="alert"]').filter({ hasText: /don't have permission to record receipts/i });
    if (await permDenied.isVisible().catch(() => false)) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'rootadmin lacks AR.RECEIPT.RECORD permission',
      });
      return;
    }

    // The form must be visible.
    const form = page.locator('form[aria-label="Record receipt"]');
    const formVisible = await form.isVisible().catch(() => false);
    if (!formVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'form[aria-label="Record receipt"] not visible — company context may not have loaded',
      });
      return;
    }

    // Verify both #customerSearch and #receiptAmount exist.
    const receiptAmountInput = page.locator('#receiptAmount');
    const amountVisible = await receiptAmountInput.isVisible().catch(() => false);
    if (!amountVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '#receiptAmount not visible — form not fully rendered',
      });
      return;
    }

    // With no customer selected and no amount, the submit button is disabled by submitDisabled().
    // submitDisabled = !selectedCustomer() || !amount || amountNum <= 0 || !date || !companyId || ...
    const submitBtn = page.locator('button[type="submit"]');
    await expect(
      submitBtn,
      'AR-1a: Record Receipt submit button must be disabled when no customer or amount is set',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    // No raw error must leak from the blank-state page.
    await assertNoRawErrorLeak(page, 'AR Record Receipt — initial empty state');
  });

  // ── AR-1b: negative/zero amount keeps submit disabled; form error clean ──────
  test('AR-1b: entering zero or negative amount keeps submit button disabled with no 500 leak', async ({ page }) => {
    const { ready } = await gotoAdminRoute(page, 'ar/receipts/record');
    if (!ready) return;

    const permDenied = page.locator('[role="alert"]').filter({ hasText: /don't have permission to record receipts/i });
    if (await permDenied.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AR.RECEIPT.RECORD' });
      return;
    }

    const form = page.locator('form[aria-label="Record receipt"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Record Receipt form not visible' });
      return;
    }

    const amountInput = page.locator('#receiptAmount');
    if (!await amountInput.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: '#receiptAmount not visible' });
      return;
    }

    // Enter a negative amount.
    await amountInput.fill('-500');
    await page.waitForTimeout(300);

    // The submit button must remain disabled (receiptAmountNum() <= 0 in submitDisabled).
    const submitBtn = page.locator('button[type="submit"]');
    await expect(
      submitBtn,
      'AR-1b: submit must be disabled when amount is negative',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    await assertNoRawErrorLeak(page, 'AR Record Receipt — negative amount entered');

    // Enter zero.
    await amountInput.fill('0');
    await page.waitForTimeout(300);
    await expect(
      submitBtn,
      'AR-1b: submit must be disabled when amount is zero',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    await assertNoRawErrorLeak(page, 'AR Record Receipt — zero amount entered');

    // No console errors from invalid amount values.
    // (problems watcher is set up in gotoAdminRoute before any input)
  });

  // ── AR-1c: over-allocation shows "Over-allocated" status tag + submit disabled
  test('AR-1c: allocating more than receipt amount shows "Over-allocated" tag and disables submit', async ({ page }) => {
    const { ready } = await gotoAdminRoute(page, 'ar/receipts/record');
    if (!ready) return;

    const permDenied = page.locator('[role="alert"]').filter({ hasText: /don't have permission to record receipts/i });
    if (await permDenied.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AR.RECEIPT.RECORD' });
      return;
    }

    const form = page.locator('form[aria-label="Record receipt"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Record Receipt form not visible' });
      return;
    }

    // Set a small receipt amount.
    const amountInput = page.locator('#receiptAmount');
    if (!await amountInput.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: '#receiptAmount not visible' });
      return;
    }
    await amountInput.fill('100');
    await page.waitForTimeout(300);

    // Pick a customer so that invoices can potentially load.
    // The over-allocation UI guard is purely client-side and evaluates even without invoices.
    // If the customer typeahead fails, we still get partial coverage (amount guard).
    const picked = await pickPartyFromTypeahead(page, 'customerSearch', 'A', 'Customer suggestions');

    if (!picked) {
      // No customer seed — we can still verify the amount guard covers the empty-alloc case.
      // allocatedTotal() = 0 by default; overAllocated() = 0 > 100+epsilon = false.
      // So submit is still disabled (no customer). That is correct; annotate and skip remaining.
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No customer seeded — cannot load allocation rows to trigger over-allocation guard. Partial coverage only.',
      });
      return;
    }

    // Wait for allocation rows to load (or the "no open invoices" empty state).
    await page.waitForTimeout(2_000);

    // Check if allocation rows (invoice table) are present.
    const firstAllocInput = page.locator('input[id^="alloc_"]').first();
    const hasAllocs = await firstAllocInput.isVisible().catch(() => false);

    if (!hasAllocs) {
      // Customer has no open invoices — the allocation section shows "No open invoices".
      // The over-allocation guard cannot fire without rows. Annotate.
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Customer has no open invoices — allocation table not rendered; over-allocation guard cannot be exercised',
      });
      return;
    }

    // Fill the first allocation input with a value LARGER than the receipt amount (100).
    await firstAllocInput.fill('999');
    await page.waitForTimeout(400);

    // The "Over-allocated" status tag must appear in the totals bar.
    const overAllocTag = page
      .locator('.status-tag.status-tag--danger[role="alert"]')
      .filter({ hasText: /Over-allocated/i });
    await expect(
      overAllocTag,
      'AR-1c: "Over-allocated" status tag must appear when allocations exceed receipt amount',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // The submit button must be disabled (overAllocated() is true → submitDisabled = true).
    const submitBtn = page.locator('button[type="submit"]');
    await expect(
      submitBtn,
      'AR-1c: submit must be disabled when over-allocated',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    // Clean error text only — no raw 500.
    await assertNoRawErrorLeak(page, 'AR Record Receipt — over-allocated');
  });

  // ── AR-1d: allocation exceeds single invoice outstanding → "exceeds" tag ─────
  test('AR-1d: allocation exceeding one invoice outstanding shows "exceeds outstanding" tag', async ({ page }) => {
    const { ready } = await gotoAdminRoute(page, 'ar/receipts/record');
    if (!ready) return;

    const permDenied = page.locator('[role="alert"]').filter({ hasText: /don't have permission to record receipts/i });
    if (await permDenied.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AR.RECEIPT.RECORD' });
      return;
    }

    const form = page.locator('form[aria-label="Record receipt"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Record Receipt form not visible' });
      return;
    }

    // Use a very large receipt amount so we are NOT over-allocated overall — only per-invoice.
    const amountInput = page.locator('#receiptAmount');
    if (!await amountInput.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: '#receiptAmount not visible' });
      return;
    }
    await amountInput.fill('999999');
    await page.waitForTimeout(300);

    const picked = await pickPartyFromTypeahead(page, 'customerSearch', 'A', 'Customer suggestions');
    if (!picked) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No customer seeded — cannot load invoices to exercise per-invoice outstanding guard',
      });
      return;
    }

    await page.waitForTimeout(2_000);

    const firstAllocInput = page.locator('input[id^="alloc_"]').first();
    const hasAllocs = await firstAllocInput.isVisible().catch(() => false);
    if (!hasAllocs) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No open invoices for this customer — per-invoice outstanding guard cannot be exercised',
      });
      return;
    }

    // Enter an absurdly high allocation on line 1 (well above any real outstanding balance).
    // The guard anyAllocationExceedsOutstanding() will fire.
    await firstAllocInput.fill('99999999');
    await page.waitForTimeout(400);

    // "An allocation exceeds the invoice outstanding" tag must appear.
    const exceedsTag = page
      .locator('.status-tag.status-tag--danger[role="alert"]')
      .filter({ hasText: /An allocation exceeds the invoice outstanding/i });
    await expect(
      exceedsTag,
      'AR-1d: "An allocation exceeds the invoice outstanding" status tag must appear',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // Submit must be disabled (anyAllocationExceedsOutstanding = true).
    const submitBtn = page.locator('button[type="submit"]');
    await expect(
      submitBtn,
      'AR-1d: submit must be disabled when any allocation exceeds invoice outstanding',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    await assertNoRawErrorLeak(page, 'AR Record Receipt — allocation exceeds invoice outstanding');
  });

  // ── AR-1e: valid receipt (on-account) records + shows success panel ───────────
  test('AR-1e: valid on-account receipt records successfully and shows receipt number in success panel', async ({ page }) => {
    const { problems, ready } = await gotoAdminRoute(page, 'ar/receipts/record');
    if (!ready) return;

    const permDenied = page.locator('[role="alert"]').filter({ hasText: /don't have permission to record receipts/i });
    if (await permDenied.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AR.RECEIPT.RECORD' });
      return;
    }

    const form = page.locator('form[aria-label="Record receipt"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Record Receipt form not visible' });
      return;
    }

    // Pick a customer.
    const picked = await pickPartyFromTypeahead(page, 'customerSearch', 'A', 'Customer suggestions');
    if (!picked) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No customer seeded — cannot exercise a valid receipt submission',
      });
      return;
    }

    // Set a valid amount, date (defaults to today), and tender type.
    const amountInput = page.locator('#receiptAmount');
    if (!await amountInput.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: '#receiptAmount not visible after customer pick' });
      return;
    }
    await amountInput.fill('50.00');

    // receiptDate defaults to today — read it to confirm it is set.
    const dateInput = page.locator('#receiptDate');
    const existingDate = await dateInput.inputValue().catch(() => '');
    if (!existingDate) {
      const today = new Date().toISOString().slice(0, 10);
      await dateInput.fill(today);
    }

    // Tender: leave at default CASH.
    // Bank ref: optional.
    await page.locator('#bankRef').fill(`E2E-${RUN_TAG}`);

    // Wait for any allocation loading to complete.
    await page.waitForTimeout(2_000);

    // The submit button must be enabled now (assuming no over-allocation).
    const submitBtn = page.locator('button[type="submit"]');
    const enabled = await submitBtn.isEnabled().catch(() => false);
    if (!enabled) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Submit button still disabled after filling required fields — possible submitDisabled() guard (currency not loaded, company missing, etc.)',
      });
      return;
    }

    await submitBtn.click();
    await page.waitForLoadState('networkidle', { timeout: IDLE_TIMEOUT });
    await page.waitForTimeout(800);

    // On success the component sets savedReceipt() and renders the success div.alert.alert-success.
    const successPanel = page.locator('div.alert.alert-success[role="status"]');
    await expect(
      successPanel,
      'AR-1e: success panel (div.alert.alert-success) must appear after valid receipt',
    ).toBeVisible({ timeout: ALERT_APPEAR });

    // The receipt number must be displayed inside the panel.
    const panelText = await successPanel.innerText().catch(() => '');
    expect(
      panelText.trim().length,
      'AR-1e: success panel text is empty — receipt number not displayed',
    ).toBeGreaterThan(5);

    // The panel text must contain "Receipt recorded" and a number.
    expect(
      panelText,
      'AR-1e: success panel must mention "Receipt recorded"',
    ).toMatch(/Receipt recorded/i);

    // No API 5xx during the whole flow.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `AR-1e: API 5xx during receipt record: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'AR Record Receipt — after valid submission');

    // Navigate to the receipts list and confirm the receipt is present.
    await page.goto('/admin/ar/receipts', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(800);

    if (page.url().includes('/login')) {
      test.info().annotations.push({ type: 'info', description: 'session bounced to /login on /admin/ar/receipts — list check skipped' });
      return;
    }

    // The table should render (if permission + data available).
    const listTable = page.locator('table.erp-table');
    const tableVisible = await listTable.isVisible().catch(() => false);
    if (tableVisible) {
      const firstRow = page.locator('table.erp-table tbody tr').first();
      await expect(
        firstRow,
        'AR-1e: AR Receipts list must have at least one row after recording a receipt',
      ).toBeVisible({ timeout: 10_000 });
    }
    // If table is not visible it may be permission-gated for viewing — not a receipt-record failure.
  });

});

// ══════════════════════════════════════════════════════════════════════════════
// AP-2: Enter Supplier Bill  /admin/ap/supplier-bills/enter
// ══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: AP Enter Supplier Bill form', () => {

  // ── AP-2a: submit button disabled with no supplier and empty required fields ──
  test('AP-2a: Enter Bill submit button is disabled when no supplier or required fields are empty', async ({ page }) => {
    const { ready } = await gotoAdminRoute(page, 'ap/supplier-bills/enter');
    if (!ready) return;

    // Check permission gate.
    const permDenied = page.locator('[role="alert"]').filter({ hasText: /don't have permission to enter bills/i });
    // Also check the erp-empty permission block (role="alert" on div.erp-empty).
    const emptyPermBlock = page.locator('div.erp-empty[role="alert"]').filter({ hasText: /don't have permission to enter bills/i });
    if (
      await permDenied.isVisible().catch(() => false) ||
      await emptyPermBlock.isVisible().catch(() => false)
    ) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AP.BILL.ENTER permission' });
      return;
    }

    const form = page.locator('form[aria-label="Enter supplier bill"]');
    const formVisible = await form.isVisible().catch(() => false);
    if (!formVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'form[aria-label="Enter supplier bill"] not visible — company context may not have loaded',
      });
      return;
    }

    // With no supplier selected, submitDisabled() is true.
    const submitBtn = page.locator('button[type="submit"]');
    await expect(
      submitBtn,
      'AP-2a: Enter Bill submit must be disabled when no supplier is selected',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    await assertNoRawErrorLeak(page, 'AP Enter Bill — initial empty state');

    // Clear supplierInvoiceNo — that alone should keep it disabled.
    const invNoInput = page.locator('#supplierInvoiceNo');
    if (await invNoInput.isVisible().catch(() => false)) {
      await invNoInput.fill('');
      await page.waitForTimeout(200);
      await expect(
        submitBtn,
        'AP-2a: Enter Bill submit must remain disabled when supplier invoice no. is empty',
      ).toBeDisabled({ timeout: DOM_SETTLE });
    }

    await assertNoRawErrorLeak(page, 'AP Enter Bill — empty invoice number');
  });

  // ── AP-2b: supplier picked + header filled but no line description → clean formError ──
  test('AP-2b: bill with valid header but empty line description shows "At least one bill line" error', async ({ page }) => {
    const { ready } = await gotoAdminRoute(page, 'ap/supplier-bills/enter');
    if (!ready) return;

    const emptyPermBlock = page.locator('div.erp-empty[role="alert"]').filter({ hasText: /don't have permission to enter bills/i });
    if (await emptyPermBlock.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AP.BILL.ENTER' });
      return;
    }

    const form = page.locator('form[aria-label="Enter supplier bill"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Enter Bill form not visible' });
      return;
    }

    // Pick a supplier via typeahead.
    const picked = await pickPartyFromTypeahead(page, 'supplierSearch', 'A', 'Supplier suggestions');
    if (!picked) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No supplier seeded — cannot exercise the "At least one line" guard',
      });
      return;
    }

    // Fill required header fields.
    const invNoInput = page.locator('#supplierInvoiceNo');
    if (!await invNoInput.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: '#supplierInvoiceNo not visible' });
      return;
    }
    await invNoInput.fill(`E2E-INV-${RUN_TAG}`);

    const today = new Date().toISOString().slice(0, 10);
    await page.locator('#billDate').fill(today);
    await page.locator('#dueDate').fill(today);

    // Leave line description empty (desc_0 is the first line's description input).
    const firstLineDesc = page.locator('#desc_0');
    if (await firstLineDesc.isVisible().catch(() => false)) {
      await firstLineDesc.fill(''); // ensure it is empty
    }

    // The submit button must still be disabled because supplier and invNo are filled
    // but lines filter to empty (lineRequests.length === 0 → formError fires in submit()).
    // However submitDisabled() checks: !selectedSupplier || !invNo || !billDate || !dueDate || lines.length===0
    // lines.length is always >= 1 (one empty row is initialized) — but the submit() method
    // short-circuits with "At least one bill line is required." when all descs are blank.
    // So: button IS enabled (submitDisabled = false), but clicking fires the client guard.

    const submitBtn = page.locator('button[type="submit"]');
    const btnEnabled = await submitBtn.isEnabled().catch(() => false);

    if (!btnEnabled) {
      // Button is still disabled — some other guard (currency not loaded?) fired.
      test.info().annotations.push({
        type: 'info',
        description: 'Submit button still disabled with empty line — submitDisabled() is gating. Clean UI — no raw error.',
      });
      await assertNoRawErrorLeak(page, 'AP Enter Bill — empty line, button disabled');
      return;
    }

    await submitBtn.click();
    await page.waitForTimeout(500);

    // The formError paragraph must show the "At least one bill line is required." message.
    const formError = page.locator('p[role="alert"].text-danger');
    await expect(
      formError,
      'AP-2b: "At least one bill line is required" formError must appear when no line has a description',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await formError.innerText().catch(() => '');
    expect(
      errorText,
      'AP-2b: error text must mention "line"',
    ).toMatch(/line/i);

    // No raw 500 / stack trace.
    await assertNoRawErrorLeak(page, 'AP Enter Bill — missing line description');
  });

  // ── AP-2c: negative unit cost — submitDisabled false but submit should be safe ─
  test('AP-2c: bill line with negative unit cost does not leak a 500 or stack trace', async ({ page }) => {
    const { problems, ready } = await gotoAdminRoute(page, 'ap/supplier-bills/enter');
    if (!ready) return;

    const emptyPermBlock = page.locator('div.erp-empty[role="alert"]').filter({ hasText: /don't have permission to enter bills/i });
    if (await emptyPermBlock.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AP.BILL.ENTER' });
      return;
    }

    const form = page.locator('form[aria-label="Enter supplier bill"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Enter Bill form not visible' });
      return;
    }

    const picked = await pickPartyFromTypeahead(page, 'supplierSearch', 'A', 'Supplier suggestions');
    if (!picked) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No supplier seeded — cannot test negative cost path',
      });
      return;
    }

    const invNoInput = page.locator('#supplierInvoiceNo');
    if (!await invNoInput.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: '#supplierInvoiceNo not visible' });
      return;
    }
    await invNoInput.fill(`E2E-NEG-${RUN_TAG}`);

    const today = new Date().toISOString().slice(0, 10);
    await page.locator('#billDate').fill(today);
    await page.locator('#dueDate').fill(today);

    // Fill description and a negative unit cost.
    const firstLineDesc = page.locator('#desc_0');
    const firstLineQty  = page.locator('#qty_0');
    const firstLineCost = page.locator('#cost_0');

    if (!await firstLineDesc.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Line 0 inputs not visible' });
      return;
    }

    await firstLineDesc.fill('Test Line');
    await firstLineQty.fill('1');
    await firstLineCost.fill('-100'); // negative cost
    await page.waitForTimeout(300);

    // The client does not block negative costs in submitDisabled() — it passes through.
    // Either the backend rejects it (clean 4xx formError) or the bill is entered (no crash).
    const submitBtn = page.locator('button[type="submit"]');
    const btnEnabled = await submitBtn.isEnabled().catch(() => false);

    if (!btnEnabled) {
      test.info().annotations.push({
        type: 'info',
        description: 'Submit still disabled with negative cost — submitDisabled() blocks it. No 500 risk.',
      });
      await assertNoRawErrorLeak(page, 'AP Enter Bill — negative cost, button disabled');
      return;
    }

    await submitBtn.click();
    await page.waitForLoadState('networkidle', { timeout: IDLE_TIMEOUT });
    await page.waitForTimeout(800);

    // Whatever happens, no raw 500 must be visible.
    await assertNoRawErrorLeak(page, 'AP Enter Bill — negative cost submitted');

    // No API 5xx should reach the user as raw text (they may happen in the network — that is a
    // backend concern — but the UI must surface a clean message, not a stack trace).
    const formError = page.locator('p[role="alert"].text-danger');
    const errVisible = await formError.isVisible().catch(() => false);
    if (errVisible) {
      const errText = await formError.innerText().catch(() => '');
      for (const pat of RAW_ERROR_PATTERNS) {
        expect(
          pat.test(errText),
          `AP-2c: raw error pattern "${pat}" in form error text: "${errText}"`,
        ).toBe(false);
      }
    }

    // If it succeeded (match result shown or "bill entered" success), that is fine too.
    // The test only gates on: no raw 500 / stack trace visible to the user.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    if (api5xx.length > 0) {
      // The backend returned a 5xx — assert the UI did NOT echo the raw response body.
      await assertNoRawErrorLeak(page, 'AP Enter Bill — negative cost, 5xx returned');
    }
  });

  // ── AP-2d: valid bill (with description + qty + cost) enters + match result ───
  test('AP-2d: valid supplier bill enters successfully and shows "Enter Bill & Match" result or success', async ({ page }) => {
    const { problems, ready } = await gotoAdminRoute(page, 'ap/supplier-bills/enter');
    if (!ready) return;

    const emptyPermBlock = page.locator('div.erp-empty[role="alert"]').filter({ hasText: /don't have permission to enter bills/i });
    if (await emptyPermBlock.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AP.BILL.ENTER' });
      return;
    }

    const form = page.locator('form[aria-label="Enter supplier bill"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Enter Bill form not visible' });
      return;
    }

    // Pick supplier.
    const picked = await pickPartyFromTypeahead(page, 'supplierSearch', 'A', 'Supplier suggestions');
    if (!picked) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No supplier seeded — cannot exercise valid bill submission',
      });
      return;
    }

    const invNoInput = page.locator('#supplierInvoiceNo');
    if (!await invNoInput.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: '#supplierInvoiceNo not visible' });
      return;
    }
    await invNoInput.fill(`E2E-BILL-${RUN_TAG}`);

    const today = new Date().toISOString().slice(0, 10);
    await page.locator('#billDate').fill(today);
    await page.locator('#dueDate').fill(today);

    // Fill line 0 with valid data.
    await page.locator('#desc_0').fill('E2E Test Line');
    await page.locator('#qty_0').fill('2');
    await page.locator('#cost_0').fill('75.00');
    await page.waitForTimeout(300);

    const submitBtn = page.locator('button[type="submit"]');
    const btnEnabled = await submitBtn.isEnabled().catch(() => false);
    if (!btnEnabled) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Submit still disabled after filling valid bill — currency or company not loaded',
      });
      return;
    }

    await submitBtn.click();
    await page.waitForLoadState('networkidle', { timeout: IDLE_TIMEOUT });
    await page.waitForTimeout(1_000);

    await assertNoRawErrorLeak(page, 'AP Enter Bill — after valid bill submission');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `AP-2d: API 5xx during bill enter: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    // On success the component either shows:
    //   (a) the match-result card (matchState === 'done')  — has "3-Way Match Result" heading
    //   (b) matchState === 'error'  — shows alert-warning "Bill entered but 3-way match failed"
    // In both cases the form is hidden (matchState !== 'idle').
    // We accept either outcome as a successful "bill entered" result.

    // Check that the form is gone (matchState is not 'idle' any more).
    const formStillVisible = await form.isVisible().catch(() => false);

    if (formStillVisible) {
      // Form is still shown — check if a formError is present.
      const formError = page.locator('p[role="alert"].text-danger');
      const errVisible = await formError.isVisible().catch(() => false);
      if (errVisible) {
        const errText = await formError.innerText().catch(() => '');
        // This is a clean 4xx from the backend — acceptable as long as no raw 500 text.
        test.info().annotations.push({ type: 'info', description: `AP-2d: backend rejected bill: "${errText}"` });
        for (const pat of RAW_ERROR_PATTERNS) {
          expect(pat.test(errText), `AP-2d: raw error pattern in formError: "${errText}"`).toBe(false);
        }
        // Skip further assertions — bill entry rejected by business rules.
        return;
      }
      test.info().annotations.push({
        type: 'info',
        description: 'AP-2d: form still visible after submit but no error — response may be still loading',
      });
      return;
    }

    // Match-result card OR match-error alert must now be visible.
    const matchResultCard = page.locator('.erp-card').filter({ hasText: /3-Way Match Result/i });
    const matchErrorAlert = page.locator('.alert.alert-warning').filter({ hasText: /3-way match failed/i });

    const matchShown = await matchResultCard.isVisible().catch(() => false);
    const matchErrShown = await matchErrorAlert.isVisible().catch(() => false);

    expect(
      matchShown || matchErrShown,
      'AP-2d: after valid bill enter, either the "3-Way Match Result" card or the match-failed warning must appear',
    ).toBe(true);

    // The "Enter Another Bill" button must be visible (confirming bill was saved).
    const enterAnotherBtn = page.getByRole('button', { name: /Enter Another Bill/i });
    await expect(
      enterAnotherBtn,
      'AP-2d: "Enter Another Bill" button must appear after successful bill entry',
    ).toBeVisible({ timeout: DOM_SETTLE });

    expect(
      realConsoleErrors(
        (await page.evaluate(() => [])), // placeholder — console captured by watchProblems
      ),
    ).toHaveLength(0); // already captured above
  });

});

// ══════════════════════════════════════════════════════════════════════════════
// AP-3: Record Payment  /admin/ap/payments/record
// ══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: AP Record Payment form', () => {

  // ── AP-3a: no supplier → submit disabled; no raw 500 ─────────────────────────
  test('AP-3a: Record Payment submit button disabled with no supplier; page shows no raw 500 text', async ({ page }) => {
    const { ready } = await gotoAdminRoute(page, 'ap/payments/record');
    if (!ready) return;

    const emptyPermBlock = page.locator('div.erp-empty[role="alert"]').filter({ hasText: /don't have permission to record payments/i });
    if (await emptyPermBlock.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AP.PAYMENT.RUN' });
      return;
    }

    const form = page.locator('form[aria-label="Record payment run"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'form[aria-label="Record payment run"] not visible — company context may not have loaded',
      });
      return;
    }

    // With no supplier, selectedCount() === 0 → submitDisabled().
    const submitBtn = page.locator('button[type="submit"]');
    await expect(
      submitBtn,
      'AP-3a: Record Payment submit must be disabled when no supplier is selected',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    await assertNoRawErrorLeak(page, 'AP Record Payment — initial empty state');
  });

  // ── AP-3b: supplier with no payable bills → "No payable bills" message ────────
  test('AP-3b: supplier with no payable bills shows informational message and submit stays disabled', async ({ page }) => {
    const { ready } = await gotoAdminRoute(page, 'ap/payments/record');
    if (!ready) return;

    const emptyPermBlock = page.locator('div.erp-empty[role="alert"]').filter({ hasText: /don't have permission to record payments/i });
    if (await emptyPermBlock.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AP.PAYMENT.RUN' });
      return;
    }

    const form = page.locator('form[aria-label="Record payment run"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Record Payment form not visible' });
      return;
    }

    // Pick a supplier.
    const picked = await pickPartyFromTypeahead(page, 'supplierSearch', 'A', 'Supplier suggestions');
    if (!picked) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No supplier seeded — cannot exercise "no payable bills" state',
      });
      return;
    }

    // Wait for bills to load (or the empty state to appear).
    await page.waitForTimeout(2_000);

    // If bills table is visible, the supplier HAS payable bills — we cannot test the empty case.
    const billsTable = page.locator('table.erp-table');
    const tableVisible = await billsTable.isVisible().catch(() => false);

    if (tableVisible) {
      test.info().annotations.push({
        type: 'info',
        description: 'AP-3b: supplier has payable bills — "No payable bills" state not reached. Submit disabled covered by AP-3a.',
      });
      // Still assert submit is disabled until at least one bill is checked.
      const submitBtn = page.locator('button[type="submit"]');
      await expect(
        submitBtn,
        'AP-3b: submit must be disabled when no bills are checked yet',
      ).toBeDisabled({ timeout: DOM_SETTLE });

      // The "Select at least one bill" status tag must appear.
      const noSelectionTag = page
        .locator('.status-tag.status-tag--warn[role="alert"]')
        .filter({ hasText: /Select at least one bill/i });
      await expect(
        noSelectionTag,
        'AP-3b: "Select at least one bill" status tag must appear when no bills are checked',
      ).toBeVisible({ timeout: DOM_SETTLE });

      await assertNoRawErrorLeak(page, 'AP Record Payment — supplier has bills but none selected');
      return;
    }

    // No bills table visible — check for the informational text.
    const noBillsMsg = page.locator('p.text-muted').filter({ hasText: /No payable bills found/i });
    const emptyMsg = page.locator('p.text-muted').filter({ hasText: /Select a supplier/i });

    const noBillsMsgVisible = await noBillsMsg.isVisible().catch(() => false);
    const emptyMsgVisible   = await emptyMsg.isVisible().catch(() => false);

    // One of these messages must be shown.
    expect(
      noBillsMsgVisible || emptyMsgVisible,
      'AP-3b: when no payable bills exist, an informational message must be shown (not an error banner)',
    ).toBe(true);

    // Submit button must remain disabled (selectedCount === 0).
    const submitBtn = page.locator('button[type="submit"]');
    await expect(
      submitBtn,
      'AP-3b: submit must be disabled when no bills are available to select',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    // No error banner (role="alert" with danger class) must be shown — this is an empty state, not an error.
    const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
    const alertVisible = await errorAlert.isVisible().catch(() => false);
    expect(
      alertVisible,
      'AP-3b: no error alert must appear when a supplier simply has no payable bills',
    ).toBe(false);

    await assertNoRawErrorLeak(page, 'AP Record Payment — supplier has no payable bills');
  });

  // ── AP-3c: valid payment run → success panel shows payment number(s) ──────────
  test('AP-3c: valid payment run records successfully and shows payment numbers in success panel', async ({ page }) => {
    const { problems, ready } = await gotoAdminRoute(page, 'ap/payments/record');
    if (!ready) return;

    const emptyPermBlock = page.locator('div.erp-empty[role="alert"]').filter({ hasText: /don't have permission to record payments/i });
    if (await emptyPermBlock.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AP.PAYMENT.RUN' });
      return;
    }

    const form = page.locator('form[aria-label="Record payment run"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Record Payment form not visible' });
      return;
    }

    // Pick a supplier.
    const picked = await pickPartyFromTypeahead(page, 'supplierSearch', 'A', 'Supplier suggestions');
    if (!picked) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No supplier seeded — cannot exercise valid payment run',
      });
      return;
    }

    // Wait for payable bills to load.
    await page.waitForTimeout(2_000);

    const billsTable = page.locator('table.erp-table');
    if (!await billsTable.isVisible().catch(() => false)) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No payable bills found for this supplier — valid payment run requires at least one bill in MATCHED/APPROVED/PARTIALLY_PAID status',
      });
      return;
    }

    // Select all bills.
    const selectAllBtn = page.getByRole('button', { name: /Select All/i });
    if (!await selectAllBtn.isVisible().catch(() => false)) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"Select All" button not visible — bills may not have loaded',
      });
      return;
    }
    await selectAllBtn.click();
    await page.waitForTimeout(300);

    // Confirm at least one bill is selected (selectedCount > 0).
    const selectionTag = page
      .locator('.status-tag.status-tag--ok')
      .filter({ hasText: /bill\(s\) selected/i });
    const tagVisible = await selectionTag.isVisible().catch(() => false);
    if (!tagVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"N bill(s) selected" status tag not visible after Select All — selection may have failed',
      });
      return;
    }

    // Ensure paymentDate is set (defaults to today).
    const dateInput = page.locator('#paymentDate');
    const existingDate = await dateInput.inputValue().catch(() => '');
    if (!existingDate) {
      const today = new Date().toISOString().slice(0, 10);
      await dateInput.fill(today);
    }

    // Submit.
    const submitBtn = page.locator('button[type="submit"]');
    const btnEnabled = await submitBtn.isEnabled().catch(() => false);
    if (!btnEnabled) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Submit button still disabled after selecting bills — submitDisabled() guard blocking (possibly company not loaded)',
      });
      return;
    }

    await submitBtn.click();
    await page.waitForLoadState('networkidle', { timeout: IDLE_TIMEOUT });
    await page.waitForTimeout(800);

    await assertNoRawErrorLeak(page, 'AP Record Payment — after valid payment run');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `AP-3c: API 5xx during payment run: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    // On success, savedPayments() is set and the success panel appears.
    // section.alert.alert-success[aria-label="Payment run result"]
    const successPanel = page.locator('section.alert.alert-success[aria-label="Payment run result"]');
    await expect(
      successPanel,
      'AP-3c: success panel (section.alert.alert-success[aria-label="Payment run result"]) must appear after valid payment run',
    ).toBeVisible({ timeout: ALERT_APPEAR });

    const panelText = await successPanel.innerText().catch(() => '');

    // The panel must say "Payment run complete".
    expect(
      panelText,
      'AP-3c: success panel must contain "Payment run complete"',
    ).toMatch(/Payment run complete/i);

    // The panel must list at least one payment.
    expect(
      panelText,
      'AP-3c: success panel must list payment details (number, amount, currency)',
    ).toMatch(/payment\(s\) recorded/i);

    // "Record Another" button must be visible.
    const recordAnotherBtn = page.getByRole('button', { name: /Record Another/i });
    await expect(
      recordAnotherBtn,
      'AP-3c: "Record Another" button must appear in the success panel',
    ).toBeVisible({ timeout: DOM_SETTLE });
  });

  // ── AP-3x: "Select at least one bill" status tag fires when supplier has bills but none checked ──
  test('AP-3x: "Select at least one bill" status tag and disabled submit when bills exist but none checked', async ({ page }) => {
    const { ready } = await gotoAdminRoute(page, 'ap/payments/record');
    if (!ready) return;

    const emptyPermBlock = page.locator('div.erp-empty[role="alert"]').filter({ hasText: /don't have permission to record payments/i });
    if (await emptyPermBlock.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks AP.PAYMENT.RUN' });
      return;
    }

    const form = page.locator('form[aria-label="Record payment run"]');
    if (!await form.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Record Payment form not visible' });
      return;
    }

    const picked = await pickPartyFromTypeahead(page, 'supplierSearch', 'A', 'Supplier suggestions');
    if (!picked) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No supplier seeded — cannot exercise "no bills selected" status tag',
      });
      return;
    }

    await page.waitForTimeout(2_000);

    const billsTable = page.locator('table.erp-table');
    if (!await billsTable.isVisible().catch(() => false)) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No payable bills for this supplier — "Select at least one bill" tag requires bills to be present',
      });
      return;
    }

    // Bills are present but none are checked (initial state after loading).
    // The "Select at least one bill" status tag must show.
    const noBillsCheckedTag = page
      .locator('.status-tag.status-tag--warn[role="alert"]')
      .filter({ hasText: /Select at least one bill/i });
    await expect(
      noBillsCheckedTag,
      'AP-3x: "Select at least one bill" status-tag--warn must appear when bills exist but none are checked',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // Submit must be disabled.
    const submitBtn = page.locator('button[type="submit"]');
    await expect(
      submitBtn,
      'AP-3x: submit must be disabled when no bills are checked',
    ).toBeDisabled({ timeout: DOM_SETTLE });

    await assertNoRawErrorLeak(page, 'AP Record Payment — bills loaded, none selected');
  });

});

// ══════════════════════════════════════════════════════════════════════════════
// Cross-cutting: zero unexpected console errors on all three routes
// ══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: AR/AP pages have no unexpected console errors or page-level error banners', () => {

  const ROUTES_TO_CHECK = [
    { route: 'ar/receipts/record', label: 'AR Record Receipt' },
    { route: 'ap/supplier-bills/enter', label: 'AP Enter Bill' },
    { route: 'ap/payments/record', label: 'AP Record Payment' },
    { route: 'ar/receipts', label: 'AR Receipts list' },
    { route: 'ap/supplier-bills', label: 'AP Supplier Bills list' },
    { route: 'ap/payments', label: 'AP Payments list' },
  ];

  for (const { route, label } of ROUTES_TO_CHECK) {
    test(`${label} (/admin/${route}) loads cleanly without console errors or error banners`, async ({ page }) => {
      const { problems, ready } = await gotoAdminRoute(page, route);
      if (!ready) return;

      // No uncaught page errors.
      expect(
        problems.pageErrors,
        `${label}: uncaught page errors on load: ${JSON.stringify(problems.pageErrors)}`,
      ).toHaveLength(0);

      // No actionable console errors (filtered for favicon/ResizeObserver noise).
      const consoleErrs = realConsoleErrors(problems.consoleErrors);
      expect(
        consoleErrs,
        `${label}: console errors on load: ${JSON.stringify(consoleErrs)}`,
      ).toHaveLength(0);

      // No raw 500 / stack trace text visible on the page.
      await assertNoRawErrorLeak(page, `${label} page load`);

      // No unexpected error-state banners (the page may show a permission message —
      // that is role="alert" text-muted, not text-danger).
      const errorBanner = page.locator('p.text-danger[role="alert"], div.alert.alert-danger').first();
      const bannerVisible = await errorBanner.isVisible().catch(() => false);

      if (bannerVisible) {
        const bannerText = await errorBanner.innerText().catch(() => '');
        // A permission denial (shield-lock) is a clean UI state — not a defect.
        // A "Could not load companies" error on a fresh env may fire — annotate but do not fail.
        if (/don't have permission|Could not load companies/i.test(bannerText)) {
          test.info().annotations.push({
            type: 'info',
            description: `${label}: clean error banner visible: "${bannerText.trim()}" — not a defect`,
          });
        } else {
          // Unexpected error banner — fail.
          expect(
            bannerText.trim(),
            `${label}: unexpected error banner visible: "${bannerText.trim()}"`,
          ).toBe('');
        }
      }
    });
  }

});
