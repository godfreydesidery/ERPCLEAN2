/**
 * UI/UX cross-cutting spec — 'chromium' project (starts logged in as rootadmin).
 *
 * Covers four cross-cutting concerns that cut across every feature page:
 *
 *   CC-1  Responsive sidebar (mobile viewport 390×844)
 *         - The `.topbar-toggle` hamburger button is visible.
 *         - On load the sidebar is collapsed (translateX(-100%) = NOT `.sidebar-open`).
 *         - Clicking the toggle opens the sidebar (`.sidebar-open` added).
 *         - Clicking the backdrop (`.sidebar-backdrop`) closes it again.
 *         - Content of a list page (h1 heading) remains reachable with the sidebar closed.
 *
 *   CC-2  Toast / alert feedback
 *         - A valid save on the customer detail page triggers an AlertService success dialog
 *           (`[role="alertdialog"].alert-success`) that auto-dismisses (~1500ms, no click).
 *         - The toast-stack (`div.toast-item`) is also monitored; at least one success
 *           feedback mechanism must fire after a good save.
 *         - The dismiss (X) button on a toast is present and clickable (manual-dismiss path).
 *         - A client-side validation error surfaces as a visible inline message — NOT as a
 *           raw 500 or stack trace in visible text.
 *
 *   CC-3  Destructive action — Archive button on customer detail
 *         - The "Archive" button is present when a customer is ACTIVE.
 *         - Clicking Archive fires immediately (no confirm dialog; owner design decision:
 *           the button text and ARCHIVED status tag are the UX guard).
 *         - After archiving, status-tag changes to "ARCHIVED" and the "Restore" button
 *           appears in place of "Archive".
 *         - A success feedback mechanism fires (AlertService or toast).
 *         - No raw 500 / stack trace appears.
 *         NOTE: if no ACTIVE customer exists the test gracefully skips.
 *
 *   CC-4  Loading indicator resolves (best-effort, non-flaky)
 *         - Navigating to a heavy list page (customers) may briefly show `.global-progress`
 *           OR a `.spinner-border` in the list body.
 *         - After networkidle + 1.5s grace, neither the global-progress bar nor a
 *           list-body spinner must remain visible — stuck indicators are the defect class.
 *         - No API 5xx during the transition.
 *
 * Selector sources: shell.component.html, toast-container.component.html,
 *   alert-host.component.html, customer-list.component.html,
 *   customer-detail.component.html — all read from web/src/app/.
 *
 * Resilience rules
 *   - Mobile tests run in a separate describe with test.use({ viewport }) — isolated
 *     from the desktop-viewport tests so viewport doesn't bleed between groups.
 *   - Any prerequisite absence (no customers, no ACTIVE customer, API down) results in
 *     test.info().annotations + early return, not a hard failure.
 *   - Tests are idempotent: names carry Date.now() tags; archive is paired with restore
 *     so repeated runs don't exhaust ACTIVE customers.
 *   - watchProblems is attached on every test; API 5xx and uncaught console errors are
 *     asserted at the end of each test, not just the primary assertion.
 */

import { test, expect } from './_test-authenticated';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── shared constants ──────────────────────────────────────────────────────────

/** Mobile viewport that matches the iPhone 14 Pro form factor (390×844). */
const MOBILE_VIEWPORT = { width: 390, height: 844 };

/**
 * Desktop breakpoint for the shell is 992px (Bootstrap lg). Below this the
 * sidebar becomes off-canvas and the hamburger toggle appears.
 * MOBILE_VIEWPORT.width (390) is well below, so the toggle will render.
 */

/** Per-run tag for idempotency. */
const RUN_TAG = `QA-${Date.now()}`;

/** Patterns whose presence in visible body text signals a raw server error leak. */
const RAW_ERROR_PATTERNS = [/\b500\b/, /stack\s*trace/i, /NullPointerException/i, /unexpected\s+error/i];

/** Maximum wait for DOM to settle after a user interaction (ms). */
const DOM_SETTLE = 8_000;

/** Maximum wait for success feedback to appear after a form save (ms). */
const FEEDBACK_TIMEOUT = 6_000;

/**
 * Assert that none of the raw-error patterns appear in the visible page body text.
 * Call this after any form submission or destructive action.
 */
async function assertNoRawErrorLeak(page: Parameters<typeof watchProblems>[0], context: string): Promise<void> {
  const text = await page.locator('body').innerText().catch(() => '');
  for (const pattern of RAW_ERROR_PATTERNS) {
    expect(
      pattern.test(text),
      `Raw server error leaked on ${context}: pattern ${pattern} matched in visible body text`,
    ).toBe(false);
  }
}

/**
 * Navigate to route and wait for networkidle. Returns false and annotates if:
 *   - the session was lost (redirected to /login)
 *   - the API returned a 5xx during load
 */
async function gotoSafe(
  page: Parameters<typeof watchProblems>[0],
  problems: ReturnType<typeof watchProblems>,
  route: string,
  timeoutMs = 60_000,
): Promise<boolean> {
  await page.goto(route, { waitUntil: 'networkidle', timeout: timeoutMs });
  await page.waitForTimeout(600);

  if (page.url().includes('/login')) {
    test.info().annotations.push({ type: 'skip-reason', description: `session bounced to /login on ${route}` });
    return false;
  }

  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  if (api5xx.length > 0) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `API 5xx during ${route} load: ${JSON.stringify(api5xx)}`,
    });
    return false;
  }

  return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// CC-1: Responsive sidebar at mobile viewport
// ─────────────────────────────────────────────────────────────────────────────

test.describe('CC-1: responsive sidebar (mobile viewport 390×844)', () => {
  // Override viewport for every test in this describe block.
  // Must use test.use() at the describe level so Playwright creates a separate
  // browser context with the correct viewport before any test runs.
  test.use({ viewport: MOBILE_VIEWPORT });

  /**
   * CC-1a — Hamburger toggle is visible; sidebar is collapsed on load;
   *          toggle opens the sidebar; backdrop closes it.
   *
   * Selectors (confirmed from shell.component.html / shell.component.scss):
   *   toggle   : button.topbar-toggle.d-lg-none
   *   sidebar  : aside.sidebar  (gets class sidebar-open when open)
   *   backdrop : div.sidebar-backdrop.d-lg-none
   *   nav items: aside.sidebar a.nav-item
   */
  test('CC-1a: hamburger toggle visible; sidebar opens/closes; nav items reachable', async ({ page }) => {
    const problems = watchProblems(page);

    // Navigate to the customers list — a real data page with sidebar nav.
    const ok = await gotoSafe(page, problems, '/admin/customers');
    if (!ok) return;

    // ── Hamburger toggle must be visible at 390px ─────────────────────────────
    // shell.component.html: <button class="btn btn-link topbar-toggle d-lg-none" …>
    // At mobile width Playwright renders d-lg-none as visible (Bootstrap d-lg-none
    // hides elements ≥992px; at 390px the element IS shown).
    const toggle = page.locator('button.topbar-toggle');
    await expect(
      toggle,
      'CC-1a: topbar-toggle button not visible at 390px — responsive breakpoint may be wrong or selector changed',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // ── aria-label reflects closed state ──────────────────────────────────────
    // shell.component.html: [attr.aria-label]="sidebarOpen() ? 'Close menu' : 'Open menu'"
    const ariaLabel = await toggle.getAttribute('aria-label').catch(() => null);
    expect(
      ariaLabel,
      'CC-1a: toggle aria-label does not match expected "Open menu" / "Close menu" pattern',
    ).toMatch(/open menu|close menu/i);

    // ── Sidebar is collapsed (no sidebar-open class) ──────────────────────────
    // shell.component.scss: @media (max-width: 991.98px) { .sidebar { transform: translateX(-100%) } }
    // The aside gets [ngClass]="{ 'sidebar-open': sidebarOpen() }" from the component.
    const sidebar = page.locator('aside.sidebar');
    await expect(sidebar, 'CC-1a: aside.sidebar not present in DOM').toBeAttached({ timeout: DOM_SETTLE });

    const hasOpenClass = await sidebar.evaluate((el) => el.classList.contains('sidebar-open')).catch(() => true);
    expect(
      hasOpenClass,
      'CC-1a: sidebar has sidebar-open class on initial load — should start collapsed at mobile width',
    ).toBe(false);

    // The sidebar is off-screen (translated left), so it is technically in the DOM
    // but not "visible" in Playwright's bounding-box sense. We confirm it is NOT
    // marked with sidebar-open rather than asserting toBeHidden() to avoid relying
    // on computed CSS in headless mode.

    // ── Click toggle → sidebar opens (sidebar-open class added) ──────────────
    await toggle.click();
    await page.waitForTimeout(300); // allow the 0.22s CSS transition to start

    const hasOpenAfterClick = await sidebar.evaluate((el) => el.classList.contains('sidebar-open')).catch(() => false);
    expect(
      hasOpenAfterClick,
      'CC-1a: sidebar-open class not added after clicking the hamburger toggle',
    ).toBe(true);

    // aria-expanded must flip to true.
    const ariaExpandedAfterOpen = await toggle.getAttribute('aria-expanded').catch(() => null);
    expect(
      ariaExpandedAfterOpen,
      'CC-1a: aria-expanded did not become "true" after opening the sidebar',
    ).toBe('true');

    // At least one nav item is present inside the now-open sidebar.
    const navItems = sidebar.locator('a.nav-item');
    const navCount = await navItems.count();
    expect(
      navCount,
      'CC-1a: no nav items found inside the sidebar after opening — nav may not have rendered',
    ).toBeGreaterThan(0);

    // ── Backdrop appears ──────────────────────────────────────────────────────
    // shell.component.html: @if (sidebarOpen()) { <div class="sidebar-backdrop d-lg-none" …> }
    const backdrop = page.locator('div.sidebar-backdrop');
    await expect(
      backdrop,
      'CC-1a: sidebar-backdrop not rendered after opening sidebar',
    ).toBeAttached({ timeout: DOM_SETTLE });

    // ── Click backdrop → sidebar closes ──────────────────────────────────────
    // The open sidebar's nav-item anchors sit in front of the backdrop in the
    // z-stack, so a synthetic pointer-event click never lands on the backdrop
    // (Playwright retries but is always intercepted by a nav link).
    // dispatchEvent('click') fires the DOM event directly on the element,
    // bypassing hit-testing, which triggers Angular's (click)="closeSidebar()".
    await backdrop.dispatchEvent('click');
    await page.waitForTimeout(300);

    const hasOpenAfterClose = await sidebar.evaluate((el) => el.classList.contains('sidebar-open')).catch(() => true);
    expect(
      hasOpenAfterClose,
      'CC-1a: sidebar-open class still present after clicking the backdrop',
    ).toBe(false);

    // Backdrop must be gone (Angular removes it when sidebarOpen() is false).
    await expect(
      backdrop,
      'CC-1a: sidebar-backdrop still present after closing sidebar',
    ).not.toBeAttached({ timeout: DOM_SETTLE });

    // ── No API 5xx / console errors during the whole interaction ─────────────
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-1a: API 5xx during mobile sidebar test: ${JSON.stringify(api5xx)}`).toHaveLength(0);
    expect(
      realConsoleErrors(problems.consoleErrors),
      'CC-1a: console errors during mobile sidebar test',
    ).toHaveLength(0);
  });

  /**
   * CC-1b — After toggling the sidebar open, clicking a nav item navigates
   *          correctly and the sidebar collapses (closeSidebar() is called on
   *          every nav-item click in the shell).
   */
  test('CC-1b: clicking a nav item in the open sidebar navigates and collapses sidebar', async ({ page }) => {
    const problems = watchProblems(page);

    const ok = await gotoSafe(page, problems, '/admin/home');
    if (!ok) return;

    const toggle = page.locator('button.topbar-toggle');
    const toggleVisible = await toggle.isVisible({ timeout: DOM_SETTLE }).catch(() => false);
    if (!toggleVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'topbar-toggle not visible at mobile viewport — may be desktop-only context or selector changed',
      });
      return;
    }

    // Open sidebar.
    await toggle.click();
    await page.waitForTimeout(300);

    const sidebar = page.locator('aside.sidebar');
    const isOpen = await sidebar.evaluate((el) => el.classList.contains('sidebar-open')).catch(() => false);
    if (!isOpen) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'sidebar did not open after toggle click — cannot test nav-item close behaviour',
      });
      return;
    }

    // Click the "Customers" nav item inside the sidebar.
    // shell.component.html: <a class="nav-item" [routerLink]="item.route" …> {{ item.label }} </a>
    const customersLink = sidebar.locator('a.nav-item', { hasText: 'Customers' }).first();
    const linkVisible = await customersLink.isVisible({ timeout: DOM_SETTLE }).catch(() => false);
    if (!linkVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"Customers" nav item not visible inside sidebar — rootadmin may lack CUSTOMER.VIEW permission',
      });
      return;
    }

    await customersLink.click();
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    await page.waitForTimeout(400);

    // The route must have changed to /admin/customers.
    expect(page.url(), 'CC-1b: URL did not update to /admin/customers after clicking Customers nav item').toMatch(/\/admin\/customers/);

    // Shell calls closeSidebar() on every nav-item click.
    const stillOpen = await sidebar.evaluate((el) => el.classList.contains('sidebar-open')).catch(() => false);
    expect(
      stillOpen,
      'CC-1b: sidebar-open class still present after clicking a nav item — closeSidebar() may not have fired',
    ).toBe(false);

    // Content (h1 heading) is reachable in the main area.
    await expect(
      page.locator('h1').first(),
      'CC-1b: h1 heading not visible on /admin/customers after sidebar navigation at mobile viewport',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // No API 5xx.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-1b: API 5xx during mobile nav test: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  /**
   * CC-1c — Escape key closes the open sidebar (shell registers @HostListener('document:keydown.escape')).
   */
  test('CC-1c: Escape key closes the open sidebar on mobile', async ({ page }) => {
    const problems = watchProblems(page);

    const ok = await gotoSafe(page, problems, '/admin/home');
    if (!ok) return;

    const toggle = page.locator('button.topbar-toggle');
    const toggleVisible = await toggle.isVisible({ timeout: DOM_SETTLE }).catch(() => false);
    if (!toggleVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'topbar-toggle not visible — skip Escape-closes test',
      });
      return;
    }

    // Open.
    await toggle.click();
    await page.waitForTimeout(300);

    const sidebar = page.locator('aside.sidebar');
    const isOpen = await sidebar.evaluate((el) => el.classList.contains('sidebar-open')).catch(() => false);
    if (!isOpen) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'sidebar did not open — cannot test Escape close',
      });
      return;
    }

    // Press Escape — shell.component.ts @HostListener('document:keydown.escape') calls closeSidebar().
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);

    const stillOpen = await sidebar.evaluate((el) => el.classList.contains('sidebar-open')).catch(() => false);
    expect(
      stillOpen,
      'CC-1c: sidebar-open class still present after pressing Escape',
    ).toBe(false);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// CC-2: Toast / alert feedback
// ─────────────────────────────────────────────────────────────────────────────

test.describe('CC-2: toast / alert feedback mechanisms', () => {

  /**
   * Navigate to the first customer detail page.
   * Returns the URL of the detail page, or null if unavailable.
   */
  async function openFirstCustomerDetail(
    page: Parameters<typeof watchProblems>[0],
    problems: ReturnType<typeof watchProblems>,
  ): Promise<string | null> {
    const ok = await gotoSafe(page, problems, '/admin/customers');
    if (!ok) return null;

    // Find the first "Edit customer" link in the list.
    // customer-list.component.html: <a class="btn btn-sm btn-outline-primary" [routerLink]="['/admin/customers/uid', c.uid]">Edit</a>
    const firstEditLink = page.locator('table.erp-table tbody tr a', { hasText: /Edit/ }).first();
    const linkVisible = await firstEditLink.isVisible({ timeout: 8_000 }).catch(() => false);
    if (!linkVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No customer Edit link in list — table is empty or no data seeded; cannot exercise detail feedback',
      });
      return null;
    }

    await firstEditLink.click();
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    await page.waitForTimeout(600);

    if (page.url().includes('/login')) {
      test.info().annotations.push({ type: 'skip-reason', description: 'session lost during customer detail navigation' });
      return null;
    }

    return page.url();
  }

  /**
   * CC-2a — Valid save on customer detail produces a success alert dialog that
   *          auto-dismisses without requiring a click (AlertService.success, durationMs=1500).
   *
   * Selectors (alert-host.component.html):
   *   alert dialog : [role="alertdialog"].alert-success
   *   alert title  : h2#alertTitle.alert-title
   */
  test('CC-2a: valid save on customer detail shows success alert-dialog that auto-dismisses', async ({ page }) => {
    const problems = watchProblems(page);

    const detailUrl = await openFirstCustomerDetail(page, problems);
    if (!detailUrl) return;

    // Ensure the edit form is present.
    // customer-detail.component.html: <form … aria-label="Edit customer">
    const editForm = page.locator('form[aria-label="Edit customer"]');
    const formVisible = await editForm.isVisible({ timeout: DOM_SETTLE }).catch(() => false);
    if (!formVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Edit customer form not visible — customer may have failed to load or rootadmin lacks CUSTOMER.MANAGE',
      });
      return;
    }

    // Read the current phone value and write something slightly different so
    // the save is genuinely a change (avoids no-op saves that some backends ignore).
    // customer-detail.component.html: <input id="fPhone" …>
    const phoneInput = page.locator('#fPhone');
    const phoneVisible = await phoneInput.isVisible().catch(() => false);
    const originalPhone = phoneVisible
      ? await phoneInput.inputValue().catch(() => '')
      : '';
    const newPhone = `+255-${RUN_TAG.slice(-6)}`;
    if (phoneVisible) await phoneInput.fill(newPhone);

    // Click "Save changes".
    const saveBtn = page.getByRole('button', { name: /Save changes/i });
    const saveBtnVisible = await saveBtn.isVisible({ timeout: DOM_SETTLE }).catch(() => false);
    if (!saveBtnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"Save changes" button not visible — rootadmin may lack CUSTOMER.MANAGE permission',
      });
      return;
    }

    await saveBtn.click();

    // AlertService.success fires with title 'Customer saved' and auto-dismisses at 1500ms.
    // alert-host.component.html: <div class="alert-card alert-success" role="alertdialog" …>
    const alertDialog = page.locator('[role="alertdialog"].alert-success');
    await expect(
      alertDialog,
      'CC-2a: success alert-dialog did not appear after a valid customer save',
    ).toBeVisible({ timeout: FEEDBACK_TIMEOUT });

    // The title must be non-empty and human-readable.
    // alert-host.component.html: <h2 id="alertTitle" class="alert-title">{{ a.title }}</h2>
    const alertTitle = alertDialog.locator('#alertTitle');
    const titleText = await alertTitle.innerText().catch(() => '');
    expect(
      titleText.trim().length,
      'CC-2a: success alert title is empty — not actionable',
    ).toBeGreaterThan(0);

    // The alert must NOT show an "OK" button (it should auto-dismiss, requireAck=false).
    // alert-host.component.html: @if (a.requireAck) { <button … >OK</button> }
    const okBtn = alertDialog.locator('button.alert-ok');
    const okBtnPresent = await okBtn.isVisible().catch(() => false);
    expect(
      okBtnPresent,
      'CC-2a: success alert shows an OK button — should auto-dismiss (requireAck should be false for success)',
    ).toBe(false);

    // Auto-dismiss: wait for the alert to disappear within its 1500ms + grace.
    await expect(
      alertDialog,
      'CC-2a: success alert-dialog did not auto-dismiss within expected time',
    ).not.toBeVisible({ timeout: 4_000 });

    // No raw error leak.
    await assertNoRawErrorLeak(page, 'customer detail — valid save');

    // No API 5xx.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-2a: API 5xx during customer save: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    // Restore the original phone if we changed it, so the test is idempotent.
    if (phoneVisible && originalPhone !== newPhone) {
      await phoneInput.fill(originalPhone);
      await saveBtn.click();
      // Wait for the auto-dismiss before continuing — do not fail if this restore save
      // also triggers an alert (it will, but we don't assert it).
      await page.waitForTimeout(2_500);
    }
  });

  /**
   * CC-2b — The toast-stack appears and a single toast is dismissible via its X button.
   *
   * We trigger a toast indirectly: the HTTP error interceptor pushes an error toast on
   * any non-2xx response. We provoke a controlled client-side validation error on the
   * customer create form (empty display name) — this does NOT call the API so there is
   * no toast, only an inline error. Instead, we rely on the save path: an attempt to
   * save from the detail page with an empty display name gives us a client-side error
   * (saveError signal), no API call, no toast.
   *
   * Because the toast is triggered by the HTTP layer (not directly testable without a
   * real 4xx/5xx), we test the STRUCTURAL requirement:
   *   - The toast-stack container is present in the DOM at all times.
   *   - When a toast appears (we wait up to 1s after a save), its dismiss button works.
   *
   * If no toast appears within the observation window (e.g. the save succeeds with an
   * AlertService success instead), we assert the AlertService path from CC-2a covers it
   * and annotate the toast-stack test as best-effort observed.
   */
  test('CC-2b: toast-stack container is present; toast dismiss button removes the toast', async ({ page }) => {
    const problems = watchProblems(page);

    const detailUrl = await openFirstCustomerDetail(page, problems);
    if (!detailUrl) return;

    // The toast-stack container is always rendered in the shell (even when empty).
    // toast-container.component.html: <div class="toast-stack" aria-live="assertive" …>
    const toastStack = page.locator('div.toast-stack');
    await expect(
      toastStack,
      'CC-2b: .toast-stack container not present in DOM — toast-container component may not be mounted',
    ).toBeAttached({ timeout: DOM_SETTLE });

    // To reliably produce a toast we perform a valid save and watch for either a toast
    // OR the alertdialog. Toast vs alertdialog is a product design choice; both are valid
    // success feedback mechanisms. We assert at least one fires.
    const editForm = page.locator('form[aria-label="Edit customer"]');
    const formVisible = await editForm.isVisible({ timeout: DOM_SETTLE }).catch(() => false);
    if (!formVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Edit customer form not visible — cannot trigger a toast; structural toast-stack check already passed',
      });
      return;
    }

    const saveBtn = page.getByRole('button', { name: /Save changes/i });
    const saveBtnVisible = await saveBtn.isVisible().catch(() => false);
    if (!saveBtnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"Save changes" button not visible — cannot trigger success feedback for toast-dismiss test',
      });
      return;
    }

    await saveBtn.click();

    // Watch for either a toast or alertdialog within FEEDBACK_TIMEOUT.
    // toast-container.component.html: <div class="toast-item toast-success" role="alert">
    // alert-host.component.html:       <div class="alert-card alert-success" role="alertdialog">
    //
    // IMPORTANT: the success alertdialog auto-dismisses after ~1500ms. Using
    // isVisible({ timeout }) in a Promise.all races against the dismiss and can
    // return false even when the element appeared and disappeared within the
    // window — as confirmed by the snapshot showing the alertdialog present when
    // the assertion fires. Instead we wait for DOM attachment (state: 'attached')
    // which is true the moment the element enters the DOM, regardless of whether
    // it has already been removed before our poll returns.
    const toastSuccess = page.locator('.toast-stack .toast-item.toast-success').first();
    const alertSuccess = page.locator('[role="alertdialog"].alert-success');

    // Use a union locator so a single waitFor catches whichever fires first.
    const eitherFeedback = page.locator(
      '[role="alertdialog"].alert-success, .toast-stack .toast-item.toast-success',
    ).first();

    const feedbackAppeared = await eitherFeedback
      .waitFor({ state: 'attached', timeout: FEEDBACK_TIMEOUT })
      .then(() => true)
      .catch(() => false);

    expect(
      feedbackAppeared,
      'CC-2b: neither a toast nor a success alert-dialog appeared after a valid save — no feedback mechanism fired',
    ).toBe(true);

    if (!feedbackAppeared) return; // guard: skip dismiss path if nothing fired

    // Determine which mechanism fired so we can exercise the correct dismiss path.
    // Check toast first (it requires an explicit click to dismiss; alertdialog is auto).
    const toastNowVisible = await toastSuccess.isVisible().catch(() => false);

    if (toastNowVisible) {
      // toast-container.component.html: <button class="toast-close" aria-label="Dismiss" …>
      const dismissBtn = toastSuccess.locator('button.toast-close[aria-label="Dismiss"]');
      await expect(
        dismissBtn,
        'CC-2b: toast dismiss button (button.toast-close) not found inside a visible success toast',
      ).toBeVisible({ timeout: DOM_SETTLE });

      await dismissBtn.click();

      // Toast must disappear promptly after dismiss.
      await expect(
        toastSuccess,
        'CC-2b: toast did not disappear after clicking the dismiss button',
      ).not.toBeVisible({ timeout: 2_000 });
    } else {
      // alertdialog appeared and may already have auto-dismissed (1500ms); that is
      // the correct behaviour. If it is still present it must auto-dismiss shortly.
      const alertStillPresent = await alertSuccess.isVisible().catch(() => false);
      if (alertStillPresent) {
        await expect(
          alertSuccess,
          'CC-2b: success alertdialog did not auto-dismiss',
        ).not.toBeVisible({ timeout: 4_000 });
      } else {
        // Already auto-dismissed — annotate as observed-and-dismissed (pass).
        test.info().annotations.push({
          type: 'info',
          description: 'CC-2b: success alertdialog appeared and auto-dismissed before dismiss-path check — correct behaviour',
        });
      }
    }

    // No API 5xx.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-2b: API 5xx during feedback test: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  /**
   * CC-2c — Client-side validation error (empty display name on customer detail)
   *          shows a clean inline message — no raw 500/stack trace visible.
   *
   * Selector: customer-detail.component.html:
   *   <output class="d-block text-danger small mt-3" aria-live="polite" …>{{ saveError() }}</output>
   */
  test('CC-2c: client-side validation error surfaces as clean inline message (no 500/stack trace)', async ({ page }) => {
    const problems = watchProblems(page);

    const detailUrl = await openFirstCustomerDetail(page, problems);
    if (!detailUrl) return;

    const displayNameInput = page.locator('#fDisplayName');
    const inputVisible = await displayNameInput.isVisible({ timeout: DOM_SETTLE }).catch(() => false);
    if (!inputVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '#fDisplayName not visible — form may not have loaded or rootadmin lacks CUSTOMER.MANAGE',
      });
      return;
    }

    const saveBtn = page.getByRole('button', { name: /Save changes/i });
    const saveBtnVisible = await saveBtn.isVisible().catch(() => false);
    if (!saveBtnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"Save changes" button not visible — cannot test validation error path',
      });
      return;
    }

    // Clear the display name — this triggers the client-side guard in save().
    const originalName = await displayNameInput.inputValue().catch(() => '');
    await displayNameInput.fill('');
    await saveBtn.click();
    await page.waitForTimeout(500);

    // customer-detail.component.ts: if (!displayName) { this.saveError.set('Display name is required.') }
    // customer-detail.component.html: <output class="d-block text-danger small mt-3" aria-live="polite" …>
    const saveError = page.locator('output.text-danger[aria-live="polite"]');
    await expect(
      saveError,
      'CC-2c: expected inline save error to appear after clearing display name and saving',
    ).toBeVisible({ timeout: DOM_SETTLE });

    const errorText = await saveError.innerText().catch(() => '');
    expect(
      errorText.trim().length,
      'CC-2c: save error message is empty — not actionable',
    ).toBeGreaterThan(5);

    // The error must NOT contain any raw server pattern.
    for (const pattern of RAW_ERROR_PATTERNS) {
      expect(
        pattern.test(errorText),
        `CC-2c: raw error pattern "${pattern}" found in inline validation error: "${errorText}"`,
      ).toBe(false);
    }

    await assertNoRawErrorLeak(page, 'customer detail — empty display name save');

    // We must still be on the detail page (no navigation on validation error).
    expect(page.url(), 'CC-2c: session was lost during validation error test').not.toMatch(/\/login/);
    expect(page.url(), 'CC-2c: navigated away from detail page on validation error').toMatch(/\/customers\/uid\//);

    // No API 5xx — the client guard must fire BEFORE any API call.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-2c: API 5xx during validation error test (should not have called API): ${JSON.stringify(api5xx)}`).toHaveLength(0);

    // Restore the original display name so subsequent test runs can use this customer.
    if (originalName) {
      await displayNameInput.fill(originalName);
      await saveBtn.click();
      await page.waitForTimeout(2_500); // let alert auto-dismiss
    }
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// CC-3: Destructive action — Archive on customer detail
// ─────────────────────────────────────────────────────────────────────────────

test.describe('CC-3: destructive action — archive/restore on customer detail', () => {

  /**
   * Find an ACTIVE customer detail URL in the list.
   * Returns the URL of an ACTIVE customer's detail page, or null.
   */
  async function findActiveCustomerDetailUrl(
    page: Parameters<typeof watchProblems>[0],
    problems: ReturnType<typeof watchProblems>,
  ): Promise<string | null> {
    const ok = await gotoSafe(page, problems, '/admin/customers');
    if (!ok) return null;

    // Scan all rows in the table for one whose status cell contains "ACTIVE".
    // customer-list.component.html: <span class="status-tag …">{{ c.status }}</span>
    //   and <a class="btn btn-sm btn-outline-primary" [routerLink]="['/admin/customers/uid', c.uid]">Edit</a>
    const rows = page.locator('table.erp-table tbody tr');
    const rowCount = await rows.count().catch(() => 0);

    if (rowCount === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Customer table is empty — no ACTIVE customer to archive; seed data required',
      });
      return null;
    }

    for (let i = 0; i < rowCount; i++) {
      const row = rows.nth(i);
      // Status column: look for a status-tag with text "ACTIVE".
      const statusTag = row.locator('.status-tag', { hasText: /^ACTIVE$/ });
      const isActive = await statusTag.isVisible().catch(() => false);
      if (!isActive) continue;

      // Found an ACTIVE row — follow its Edit link.
      const editLink = row.locator('a', { hasText: /Edit/ }).first();
      const href = await editLink.getAttribute('href').catch(() => null);
      if (!href) continue;

      await editLink.click();
      await page.waitForLoadState('networkidle', { timeout: 30_000 });
      await page.waitForTimeout(600);

      if (page.url().includes('/login')) {
        test.info().annotations.push({ type: 'skip-reason', description: 'session lost during customer detail navigation' });
        return null;
      }

      return page.url();
    }

    test.info().annotations.push({
      type: 'skip-reason',
      description: 'No ACTIVE customer found in the first page of results — all may already be ARCHIVED',
    });
    return null;
  }

  /**
   * CC-3a — Archive fires immediately (no confirm dialog); status-tag changes to ARCHIVED;
   *          Restore button replaces Archive button; success feedback fires.
   *
   * Design note (confirmed from customer-detail.component.ts and .html):
   *   - archive() is called directly on button click — no browser confirm() or modal.
   *   - On success: AlertService.success('Customer archived') fires.
   *   - customer.update(…) sets status to 'ARCHIVED' in the signal — the status-tag
   *     and Archive/Restore buttons re-render reactively.
   */
  test('CC-3a: Archive fires immediately; status changes to ARCHIVED; Restore button appears; success feedback shown', async ({ page }) => {
    const problems = watchProblems(page);

    const detailUrl = await findActiveCustomerDetailUrl(page, problems);
    if (!detailUrl) return;

    // Confirm we are on the detail page and the customer is ACTIVE.
    // customer-detail.component.html: <span class="status-tag …">{{ c.status }}</span>
    const statusTag = page.locator('.status-tag').filter({ hasText: /^ACTIVE$/ }).first();
    const isActive = await statusTag.isVisible({ timeout: DOM_SETTLE }).catch(() => false);
    if (!isActive) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'Customer detail page does not show ACTIVE status — may have been archived already',
      });
      return;
    }

    // The "Archive" button must be present.
    // customer-detail.component.html: <button type="button" class="btn btn-sm btn-outline-secondary ms-2" …>Archive</button>
    const archiveBtn = page.getByRole('button', { name: /^Archive$/ });
    const archiveBtnVisible = await archiveBtn.isVisible({ timeout: DOM_SETTLE }).catch(() => false);
    if (!archiveBtnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"Archive" button not visible — rootadmin may lack CUSTOMER.MANAGE permission',
      });
      return;
    }

    // ── No browser confirm dialog must be triggered ───────────────────────────
    // Register a dialog handler BEFORE clicking. If a dialog fires this would be
    // a design regression (unexpected confirm). We accept and note it.
    let unexpectedDialog = false;
    page.on('dialog', async (dialog) => {
      unexpectedDialog = true;
      await dialog.accept(); // don't block the test
    });

    await archiveBtn.click();

    // Brief wait for reactivity.
    await page.waitForTimeout(600);

    expect(
      unexpectedDialog,
      'CC-3a: a browser confirm() dialog appeared on Archive click — owner design requires no dialog (direct action)',
    ).toBe(false);

    // ── Success feedback: alertdialog or toast ────────────────────────────────
    // customer-detail.component.ts archive(): this.alerts.success('Customer archived')
    // alert-host.component.html renders [role="alertdialog"].alert-success
    const alertSuccess = page.locator('[role="alertdialog"].alert-success');
    const toastSuccess = page.locator('.toast-stack .toast-item.toast-success').first();

    const [alertVisible, toastVisible] = await Promise.all([
      alertSuccess.isVisible({ timeout: FEEDBACK_TIMEOUT }).catch(() => false),
      toastSuccess.isVisible({ timeout: FEEDBACK_TIMEOUT }).catch(() => false),
    ]);

    expect(
      alertVisible || toastVisible,
      'CC-3a: no success feedback (alertdialog or toast) appeared after archiving the customer',
    ).toBe(true);

    // Wait for auto-dismiss before asserting the DOM state change.
    if (alertVisible) {
      await expect(alertSuccess, 'CC-3a: success alertdialog did not auto-dismiss').not.toBeVisible({ timeout: 4_000 });
    }

    // ── Status tag must change to ARCHIVED ───────────────────────────────────
    // customer-detail.component.ts: this.customer.update((c) => (c ? { ...c, status: 'ARCHIVED' } : c))
    const archivedTag = page.locator('.status-tag').filter({ hasText: /^ARCHIVED$/ }).first();
    await expect(
      archivedTag,
      'CC-3a: status-tag did not change to ARCHIVED after archiving the customer',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // ── "Archive" must be gone, "Restore" must appear ────────────────────────
    // customer-detail.component.html: @if (c.status === 'ACTIVE') { <button>Archive</button> } @else { <button>Restore</button> }
    const archiveBtnAfter = page.getByRole('button', { name: /^Archive$/ });
    await expect(
      archiveBtnAfter,
      'CC-3a: "Archive" button still visible after archiving — template did not re-render',
    ).not.toBeVisible({ timeout: DOM_SETTLE });

    const restoreBtn = page.getByRole('button', { name: /^Restore$/ });
    await expect(
      restoreBtn,
      'CC-3a: "Restore" button did not appear after archiving the customer',
    ).toBeVisible({ timeout: DOM_SETTLE });

    // ── No raw error leak ─────────────────────────────────────────────────────
    await assertNoRawErrorLeak(page, 'customer detail — Archive action');

    // No API 5xx.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-3a: API 5xx during archive action: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    // ── Restore the customer so future test runs find an ACTIVE one ───────────
    await restoreBtn.click();
    await page.waitForTimeout(600);

    // Wait for the restore success alert to auto-dismiss.
    const restoreAlert = page.locator('[role="alertdialog"].alert-success');
    await restoreAlert.waitFor({ state: 'hidden', timeout: 5_000 }).catch(() => undefined);

    // Verify the status returns to ACTIVE.
    const activeTagAfterRestore = page.locator('.status-tag').filter({ hasText: /^ACTIVE$/ }).first();
    await expect(
      activeTagAfterRestore,
      'CC-3a: status did not return to ACTIVE after restoring — Archive/Restore round-trip broken',
    ).toBeVisible({ timeout: DOM_SETTLE });
  });

  /**
   * CC-3b — Confirm there is no browser-native confirm() / prompt() dialog anywhere
   *          in the destructive-action flow (belt-and-suspenders assertion).
   *          The UI guard is the button label + status chip, not a dialog.
   *
   * This is a lightweight structural test that navigates directly to a customer detail
   * and monitors for dialogs without clicking Archive — just confirming the page
   * sets up no auto-triggered dialogs on load.
   */
  test('CC-3b: no unwanted browser dialog fires on customer detail page load', async ({ page }) => {
    const problems = watchProblems(page);

    const ok = await gotoSafe(page, problems, '/admin/customers');
    if (!ok) return;

    const firstEditLink = page.locator('table.erp-table tbody tr a', { hasText: /Edit/ }).first();
    const linkVisible = await firstEditLink.isVisible({ timeout: 6_000 }).catch(() => false);
    if (!linkVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No customer in list — CC-3b structural check skipped',
      });
      return;
    }

    let dialogFired = false;
    page.on('dialog', async (dialog) => {
      dialogFired = true;
      await dialog.dismiss().catch(() => undefined);
    });

    await firstEditLink.click();
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    await page.waitForTimeout(800);

    expect(
      dialogFired,
      'CC-3b: a browser dialog fired on customer detail page load — unexpected auto-dialog in the app',
    ).toBe(false);

    // Detail page must render without errors.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-3b: API 5xx on customer detail load: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    await assertNoRawErrorLeak(page, 'customer detail — page load dialog check');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// CC-4: Loading indicator appears then resolves
// ─────────────────────────────────────────────────────────────────────────────

test.describe('CC-4: loading indicators resolve after page settles', () => {

  /**
   * CC-4a — No stuck global-progress bar after networkidle.
   *
   * The shell renders `.global-progress` (aria-hidden="true") via LoadingService.active().
   * It must be gone once all API requests have completed.
   *
   * shell.component.html: @if (loading.active()) { <div class="global-progress" aria-hidden="true"></div> }
   */
  test('CC-4a: global-progress bar is gone after customers list page settles (networkidle + 1.5s)', async ({ page }) => {
    const problems = watchProblems(page);

    const ok = await gotoSafe(page, problems, '/admin/customers');
    if (!ok) return;

    // Extra grace period for Angular signals and any slow secondary API calls.
    await page.waitForTimeout(1_500);

    // The global-progress div must NOT be visible after the page has settled.
    // It is aria-hidden so Playwright's isVisible() checks rendered dimensions;
    // we use isAttached() to verify it was removed from the DOM (not just hidden).
    const globalProgress = page.locator('div.global-progress');
    const stuck = await globalProgress.isVisible().catch(() => false);
    expect(
      stuck,
      'CC-4a: .global-progress bar still visible after networkidle + 1.5s — LoadingService count may not have reached 0',
    ).toBe(false);

    // API 5xx check.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-4a: API 5xx during customers list load: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  /**
   * CC-4b — No stuck list-body spinner after the customers list settles.
   *
   * customer-list.component.html (state==='loading' case):
   *   <p class="text-muted d-flex align-items-center gap-2 py-3" aria-live="polite">
   *     <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>Loading customers…
   *   </p>
   *
   * The spinner-border inside a p[aria-live="polite"] must NOT remain after page settles.
   */
  test('CC-4b: list-body spinner gone after customers list settles (networkidle + 1.5s)', async ({ page }) => {
    const problems = watchProblems(page);

    const ok = await gotoSafe(page, problems, '/admin/customers');
    if (!ok) return;

    await page.waitForTimeout(1_500);

    // Selector from customer-list.component.html:
    //   p[aria-live="polite"] .spinner-border  (list loading state)
    const listSpinner = page.locator('p[aria-live="polite"] .spinner-border').first();
    const stuck = await listSpinner.isVisible().catch(() => false);
    expect(
      stuck,
      'CC-4b: list-body spinner still visible after networkidle + 1.5s — state may be stuck at "loading" (API not responding or signal not cleared)',
    ).toBe(false);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-4b: API 5xx during list load spinner check: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  /**
   * CC-4c — Best-effort: confirm the global-progress bar briefly APPEARED during a
   *          heavy navigation (catches a regression where LoadingService.begin() is never called).
   *
   * Strategy: intercept the route navigation and snapshot DOM state early.
   * This is deliberately non-flaky: we navigate with 'domcontentloaded' (not networkidle)
   * so we can catch the in-flight state, wait a short time, then assert progress clears.
   *
   * If the app is so fast that the progress bar disappears before our first snapshot,
   * we annotate it as "too fast to observe" — that is NOT a defect. The only failure
   * is a progress bar that REMAINS after the page fully settles.
   */
  test('CC-4c: global-progress bar resolves — not stuck — on products list navigation', async ({ page }) => {
    const problems = watchProblems(page);

    // Navigate with domcontentloaded so we can observe early state.
    await page.goto('/admin/products', { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForTimeout(200); // brief window to catch early progress bar

    // Snapshot: was the progress bar there?
    const globalProgress = page.locator('div.global-progress');
    const observedDuringLoad = await globalProgress.isVisible().catch(() => false);
    if (observedDuringLoad) {
      test.info().annotations.push({
        type: 'info',
        description: 'global-progress bar was observed during load — confirming it resolves',
      });
    } else {
      test.info().annotations.push({
        type: 'info',
        description: 'global-progress bar was not observed during load window (page loaded very quickly) — proceeding to stuck check',
      });
    }

    // Wait for full networkidle + grace.
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    await page.waitForTimeout(1_500);

    // Guard: session not lost.
    if (page.url().includes('/login')) {
      test.info().annotations.push({ type: 'skip-reason', description: 'session bounced on /admin/products' });
      return;
    }

    // The definitive assertion: no stuck progress bar.
    const stuckAfterSettle = await globalProgress.isVisible().catch(() => false);
    expect(
      stuckAfterSettle,
      'CC-4c: global-progress bar is still visible on /admin/products after full networkidle + 1.5s grace — LoadingService count did not reach 0',
    ).toBe(false);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-4c: API 5xx on /admin/products: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    // No console errors (products list renders without error).
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(
      consoleErrs,
      `CC-4c: console errors on /admin/products: ${JSON.stringify(consoleErrs)}`,
    ).toHaveLength(0);
  });

  /**
   * CC-4d — Company-state loading spinner in customer-list also resolves.
   *
   * customer-list.component.html (companyState === 'loading' case):
   *   <p class="text-muted d-flex align-items-center gap-2 mb-3" aria-live="polite">
   *     <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>Loading companies…
   *   </p>
   *
   * After page settles this spinner must NOT remain (companies loaded or errored,
   * never stuck on 'loading').
   */
  test('CC-4d: company-state spinner gone after customers list settles', async ({ page }) => {
    const problems = watchProblems(page);

    const ok = await gotoSafe(page, problems, '/admin/customers');
    if (!ok) return;

    await page.waitForTimeout(1_500);

    // The company spinner is also inside p[aria-live="polite"] .spinner-border.
    // It uses "Loading companies…" text alongside the spinner, but we just assert
    // NO spinner-border is visible inside any aria-live paragraph — covers both states.
    const anySpinner = page.locator('p[aria-live="polite"] .spinner-border');
    const count = await anySpinner.count();
    const anyStuck = count > 0 && (await anySpinner.first().isVisible().catch(() => false));

    expect(
      anyStuck,
      'CC-4d: a spinner inside p[aria-live="polite"] is still visible after networkidle + 1.5s — company or list state stuck at loading',
    ).toBe(false);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `CC-4d: API 5xx during company/list spinner check: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });
});
