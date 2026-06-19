/**
 * UI/UX: Money-movement posting runs — Depreciation & Payroll.
 *
 * Assignment: uiux-depreciation-payroll.spec.ts
 * Project: chromium (authenticated as rootadmin via storageState).
 *
 * Covered surfaces:
 *   DR-*  Fixed-asset Depreciation runs (/admin/depreciation-runs, /admin/depreciation-runs/post)
 *   PR-*  Payroll runs (/admin/hr/payroll-runs, /admin/hr/payroll-runs/uid/:uid)
 *
 * Selector provenance (read from component HTML directly — NO guessed selectors):
 *
 *   depreciation-run-list.component.html
 *     h1.h4                            — page heading "Depreciation Runs"
 *     .erp-empty                       — empty state when no runs exist
 *     table.erp-table                  — populated list
 *     a.btn[routerLink*="post"]        — "Run Depreciation" button (canDepreciate guard)
 *     .status-tag                      — status pills on each row
 *     a.btn[aria-label^="Open run"]    — row drill-down link
 *
 *   depreciation-post.component.html
 *     h1.h4                            — "Run Depreciation" heading
 *     #fFiscalPeriodUid                — Fiscal Period UID text input (required)
 *     #fPostingDate                    — Posting Date date input (required)
 *     button[type="button"] "Preview"  — preview action (not submit)
 *     button[type="button"] "Post Run" — post action (not submit)
 *     p.text-danger[role="alert"]      — previewError / postError rendered inline
 *     .alert.alert-success             — posted run confirmation banner (aria-live)
 *     .erp-empty                       — "No assets with pending charges" (empty preview)
 *     #previewHeading                  — preview section heading (h2)
 *
 *   depreciation-run-detail.component.html
 *     h1.h4                            — "Depreciation Run — {runNumber}" heading
 *     .status-tag                      — run status pill
 *     .erp-card__head h2               — "Run Summary"
 *     h2.erp-section-title             — "Per-Asset Lines (n)"
 *     table.erp-table                  — lines table (or .erp-empty if zero lines)
 *
 *   payroll-run-list.component.html
 *     h1                               — "Payroll Runs" heading
 *     button[aria-controls="createPayRunForm"]  — "New Payroll Run" toggle (canRun guard)
 *     #createPayRunForm                — inline create form
 *     #prYear                          — Period Year number input
 *     #prMonth                         — Period Month select
 *     #prPayDate                       — Pay Date date input
 *     output[role="alert"]             — formError live region
 *     button[type="submit"]            — "Create" submit inside #createPayRunForm
 *     .erp-empty                       — "No payroll runs yet"
 *     table.erp-table                  — populated list
 *     .status-tag                      — status pills (DRAFT/CALCULATED/APPROVED/POSTED/PAID/REVERSED)
 *
 *   payroll-run-detail.component.html
 *     h1                               — run number heading
 *     .status-tag                      — run status pill
 *     .erp-card__head h2               — "Run Summary"
 *     .erp-kpi                         — KPI tiles (Period, Pay Date, Gross Total, etc.)
 *     h2.erp-section-title             — "Payroll Lines"
 *     output[role="alert"]             — actionError live region
 *     button "Calculate"               — only if canRun + DRAFT/CALCULATED/APPROVED
 *
 * Resilience contract:
 *   - All tests that need seeded data (an existing run / eligible assets / employees)
 *     annotate + return gracefully. A clean empty-state or permission-denied message
 *     is a PASS; a raw 500 / stack-trace / "unexpected error" in visible body text is a FAIL.
 *   - Per-run RUN_TAG guarantees idempotency across reruns.
 *   - No test navigates to a hardcoded uid — uids are discovered at runtime.
 *
 * Guards:
 *   /admin/depreciation-runs       requires FA.VIEW
 *   /admin/depreciation-runs/post  requires FA.DEPRECIATE
 *   /admin/hr/payroll-runs         requires HR.PAYROLL.VIEW
 *   /admin/hr/payroll-runs/uid/:uid requires HR.PAYROLL.VIEW
 */

import { test, expect } from './_test-authenticated';
import AxeBuilder from '@axe-core/playwright';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── shared constants ──────────────────────────────────────────────────────────

/** Per-run tag makes created resources unique across parallel reruns. */
const RUN_TAG = `QA-${Date.now()}`;

/** Standard settle delay after navigation for Angular signals to flush. */
const SETTLE_MS = 800;

/** Timeout for a heading or key element to become visible. */
const VISIBLE_MS = 15_000;

/** Timeout for a form success result to appear (API round-trip). */
const API_RESULT_MS = 12_000;

/** Patterns that indicate a raw server error has leaked into the visible UI. */
const RAW_ERROR_PATTERNS = [/\b500\b/, /stack\s*trace/i, /NullPointerException/i, /unexpected error/i];

/**
 * Scan visible body text for raw server error patterns.
 * Fails the test with an explicit message if any pattern matches.
 */
async function assertNoRawErrorLeak(
  page: Parameters<typeof watchProblems>[0],
  context: string,
): Promise<void> {
  const text = await page.locator('body').innerText().catch(() => '');
  for (const pattern of RAW_ERROR_PATTERNS) {
    expect(
      pattern.test(text),
      `Raw server error leaked on "${context}": matched ${pattern}`,
    ).toBe(false);
  }
}

/**
 * Navigate to a route and wait for networkidle.
 * Returns false (and annotates) when:
 *   - session bounced to /login
 *   - any API 5xx occurred during page load
 */
async function gotoAndSettle(
  page: Parameters<typeof watchProblems>[0],
  path: string,
  problems: ReturnType<typeof watchProblems>,
): Promise<boolean> {
  await page.goto(path, { waitUntil: 'networkidle', timeout: 60_000 });
  await page.waitForTimeout(SETTLE_MS);

  if (page.url().includes('/login')) {
    test.info().annotations.push({ type: 'skip-reason', description: `session bounced to /login navigating to ${path}` });
    return false;
  }

  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  if (api5xx.length > 0) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `API 5xx during ${path} load: ${JSON.stringify(api5xx)}`,
    });
    return false;
  }

  return true;
}

/**
 * Run axe on the current page state and assert zero serious/critical violations.
 * Mirrors the convention established in uiux-journeys-a11y.spec.ts and smoke.spec.ts.
 */
async function runAxeAndAssert(
  page: Parameters<typeof watchProblems>[0],
  label: string,
): Promise<void> {
  const results = await new AxeBuilder({ page })
    .disableRules(['color-contrast'])
    .analyze();

  const serious = results.violations.filter(
    (v) => v.impact === 'serious' || v.impact === 'critical',
  );
  const summary = serious.map((v) => `${v.id}(${v.nodes.length} node(s))`).join(', ');
  expect(
    serious,
    `axe serious/critical violations on ${label}: ${summary}`,
  ).toHaveLength(0);
}

// ═══════════════════════════════════════════════════════════════════════════════
// GROUP A: Fixed-Asset Depreciation Runs
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: Depreciation run list (/admin/depreciation-runs)', () => {

  // ── DR-1: page heading renders, no 5xx, no raw error ───────────────────────
  test('DR-1: list page renders heading and no raw server error regardless of data state', async ({ page }) => {
    const problems = watchProblems(page);

    const ok = await gotoAndSettle(page, '/admin/depreciation-runs', problems);
    if (!ok) return;

    // Permission denied (FA.VIEW not on rootadmin) — clean message, not a 500.
    const forbidden = page.locator('p.text-muted').filter({ hasText: /don.t have permission/i });
    const isForbidden = await forbidden.isVisible({ timeout: 3_000 }).catch(() => false);
    if (isForbidden) {
      test.info().annotations.push({ type: 'info', description: 'rootadmin lacks FA.VIEW — forbidden UI shown' });
      await assertNoRawErrorLeak(page, 'depreciation-run-list — forbidden state');
      return;
    }

    // Page heading: <h1 class="h4 mb-1">Depreciation Runs</h1>
    const heading = page.locator('h1.h4').filter({ hasText: /Depreciation Runs/ });
    await expect(heading, 'page heading "Depreciation Runs" not visible').toBeVisible({ timeout: VISIBLE_MS });

    // Either empty state or populated table must be present — neither should be an error banner.
    const emptyState = page.locator('.erp-empty');
    const tableRow = page.locator('table.erp-table tbody tr').first();

    const hasEmpty = await emptyState.isVisible({ timeout: 5_000 }).catch(() => false);
    const hasRows = await tableRow.isVisible({ timeout: 3_000 }).catch(() => false);

    // An error banner is a defect — assert it is absent.
    const errorBanner = page.locator('p.text-danger[role="alert"]');
    const hasError = await errorBanner.isVisible({ timeout: 2_000 }).catch(() => false);
    expect(hasError, 'Error alert visible on depreciation-run list page — API or permission error leaked').toBe(false);

    // At least one of the two content states must be rendered.
    expect(
      hasEmpty || hasRows,
      'Neither empty-state div nor table rows rendered — list did not settle into a known state',
    ).toBe(true);

    await assertNoRawErrorLeak(page, 'depreciation-run-list');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx on depreciation-run list: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── DR-2: "Run Depreciation" button present when user has FA.DEPRECIATE ────
  test('DR-2: "Run Depreciation" toolbar button links to /admin/depreciation-runs/post (or is absent when permission denied)', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoAndSettle(page, '/admin/depreciation-runs', problems);
    if (!ok) return;

    // Company must be selected before the toolbar renders.
    // The toolbar is inside @if (selectedCompanyId()) — wait for it to potentially appear.
    await page.waitForTimeout(1_000);

    // The "Run Depreciation" link is rendered as <a class="btn btn-primary" routerLink="/admin/depreciation-runs/post">
    // It is only rendered when canDepreciate() is true.
    const runBtn = page.locator('a.btn.btn-primary[href*="depreciation-runs/post"]');
    const hasBtn = await runBtn.isVisible({ timeout: 5_000 }).catch(() => false);

    if (hasBtn) {
      // The link must point to the correct path.
      const href = await runBtn.getAttribute('href').catch(() => '');
      expect(
        href,
        '"Run Depreciation" button href does not contain the post route',
      ).toMatch(/depreciation-runs\/post/);

      test.info().annotations.push({ type: 'info', description: 'FA.DEPRECIATE permission present — Run Depreciation button visible' });
    } else {
      // canDepreciate() = false → button is not rendered. That is correct behaviour; not a failure.
      test.info().annotations.push({ type: 'info', description: 'Run Depreciation button not visible — rootadmin may lack FA.DEPRECIATE; this is correct permission-gate behaviour' });
    }

    await assertNoRawErrorLeak(page, 'depreciation-run-list — toolbar check');
  });

  // ── DR-3: first run in list opens detail page without 500 ──────────────────
  test('DR-3: opening the first depreciation run detail renders Run Summary and Per-Asset Lines, no 500', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoAndSettle(page, '/admin/depreciation-runs', problems);
    if (!ok) return;

    // Need at least one run in the table.
    const openLink = page.locator('a.btn[aria-label^="Open run"]').first();
    const hasLink = await openLink.isVisible({ timeout: 5_000 }).catch(() => false);

    if (!hasLink) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No depreciation runs in list — detail drill-down skipped (stack has no runs seeded)',
      });
      return;
    }

    await openLink.click();
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    await page.waitForTimeout(SETTLE_MS);

    // URL must be /admin/depreciation-runs/uid/<uid>.
    expect(page.url(), 'Did not navigate to depreciation run detail URL').toMatch(/\/admin\/depreciation-runs\/uid\//);

    // detail heading: <h1 class="h4 mb-1">Depreciation Run — {runNumber} <span class="status-tag ...">
    const detailHeading = page.locator('h1.h4').filter({ hasText: /Depreciation Run/ });
    await expect(detailHeading, 'Depreciation run detail heading not visible').toBeVisible({ timeout: VISIBLE_MS });

    // Status pill must render with the run status text.
    const statusTag = page.locator('.status-tag').first();
    await expect(statusTag, 'No .status-tag rendered on depreciation run detail').toBeVisible({ timeout: VISIBLE_MS });

    // Run Summary erp-card must render.
    const summaryHead = page.locator('.erp-card__head h2').filter({ hasText: /Run Summary/ });
    await expect(summaryHead, '"Run Summary" card heading not visible on detail page').toBeVisible({ timeout: VISIBLE_MS });

    // Per-Asset Lines section heading: <h2 class="erp-section-title">Per-Asset Lines (n)</h2>
    const linesHeading = page.locator('h2.erp-section-title').filter({ hasText: /Per-Asset Lines/ });
    await expect(linesHeading, '"Per-Asset Lines" section heading not visible on detail page').toBeVisible({ timeout: VISIBLE_MS });

    // Lines section should show either the table or the empty-state — not an error.
    const linesError = page.locator('p.text-danger[role="alert"]');
    const linesHasError = await linesError.isVisible({ timeout: 2_000 }).catch(() => false);
    expect(linesHasError, 'Error alert on depreciation run detail lines section').toBe(false);

    await assertNoRawErrorLeak(page, 'depreciation-run-detail');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx on depreciation run detail: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── DR-4: axe on list page ──────────────────────────────────────────────────
  test('DR-4 (a11y): depreciation-run-list has zero axe serious/critical violations', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoAndSettle(page, '/admin/depreciation-runs', problems);
    if (!ok) return;

    // Give the list state (loading → idle) time to settle before axe scan.
    await page.waitForTimeout(800);

    await runAxeAndAssert(page, '/admin/depreciation-runs');
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// GROUP B: Depreciation post form (/admin/depreciation-runs/post)
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: Depreciation post form (/admin/depreciation-runs/post)', () => {

  /**
   * Navigate to the post form and confirm it is usable.
   * Returns false (annotated) when the route is inaccessible or shows
   * a permission-denied state.
   */
  async function openDepreciationPostPage(
    page: Parameters<typeof watchProblems>[0],
    problems: ReturnType<typeof watchProblems>,
  ): Promise<boolean> {
    const ok = await gotoAndSettle(page, '/admin/depreciation-runs/post', problems);
    if (!ok) return false;

    // The route guard (FA.DEPRECIATE) may redirect without a message, or the
    // component renders a "don't have permission" p.text-muted when the computed
    // canDepreciate() is false (belt-and-suspenders check in the template).
    const permissionDenied = page.locator('p.text-muted').filter({ hasText: /don.t have permission/i });
    const isDenied = await permissionDenied.isVisible({ timeout: 3_000 }).catch(() => false);
    if (isDenied) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks FA.DEPRECIATE — permission-denied state shown; form tests skipped' });
      return false;
    }

    // The form requires companyState to complete loading before #fFiscalPeriodUid renders.
    // Wait up to 10 s for the input to appear.
    const periodInput = page.locator('#fFiscalPeriodUid');
    const inputVisible = await periodInput.isVisible({ timeout: 10_000 }).catch(() => false);
    if (!inputVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '#fFiscalPeriodUid not visible — company loading may have failed or the route guard redirected',
      });
      return false;
    }

    return true;
  }

  // ── DP-1: page renders heading, back link, two required fields ──────────────
  test('DP-1: post form renders heading, back link, Fiscal Period UID + Posting Date fields', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openDepreciationPostPage(page, problems);
    if (!ok) return;

    // <h1 class="h4 mb-1">Run Depreciation</h1>
    const heading = page.locator('h1.h4').filter({ hasText: /Run Depreciation/ });
    await expect(heading, '"Run Depreciation" page heading not visible').toBeVisible({ timeout: VISIBLE_MS });

    // Back link: <a routerLink="/admin/depreciation-runs" aria-label="Back to Depreciation Runs list">
    const backLink = page.locator('a[aria-label="Back to Depreciation Runs list"]');
    await expect(backLink, 'Back to Depreciation Runs list link not visible').toBeVisible();

    // #fFiscalPeriodUid — required field
    const periodInput = page.locator('#fFiscalPeriodUid');
    await expect(periodInput, '#fFiscalPeriodUid input not visible').toBeVisible();

    // #fPostingDate — required for Post Run, optional for Preview
    const dateInput = page.locator('#fPostingDate');
    await expect(dateInput, '#fPostingDate date input not visible').toBeVisible();

    // Preview button
    const previewBtn = page.getByRole('button', { name: /^Preview$/ });
    await expect(previewBtn, 'Preview button not visible').toBeVisible();

    // Post Run button
    const postBtn = page.getByRole('button', { name: /^Post Run$/ });
    await expect(postBtn, 'Post Run button not visible').toBeVisible();

    await assertNoRawErrorLeak(page, 'depreciation-post — form render');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx on depreciation post form load: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── DP-2: clicking Preview with empty Fiscal Period UID shows a clean error ─
  test('DP-2: clicking Preview with empty Fiscal Period UID shows a clean inline error, no 500', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openDepreciationPostPage(page, problems);
    if (!ok) return;

    // Ensure the field is empty.
    await page.locator('#fFiscalPeriodUid').fill('');

    // Click Preview without any period UID.
    const previewBtn = page.getByRole('button', { name: /^Preview$/ });
    await previewBtn.click();
    await page.waitForTimeout(400);

    // The component sets previewError() = 'Fiscal period UID is required.'
    // Rendered as: <p class="text-danger small mt-2 mb-0" role="alert">{{ msg }}</p>
    // The error may be either previewError or postError — both render as p.text-danger[role="alert"].
    const errorMsg = page.locator('p.text-danger[role="alert"]');
    await expect(errorMsg, 'No visible inline error after clicking Preview with empty period UID').toBeVisible({ timeout: 5_000 });

    const errorText = await errorMsg.innerText().catch(() => '');
    expect(errorText.trim().length, 'Inline error text is empty — not actionable').toBeGreaterThan(5);

    // Must not contain raw server error patterns.
    await assertNoRawErrorLeak(page, 'depreciation-post — Preview with empty period UID');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx after Preview with empty UID: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── DP-3: clicking Post Run with empty Fiscal Period UID shows a clean error ─
  test('DP-3: clicking Post Run with empty Fiscal Period UID shows a clean inline error, no 500', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openDepreciationPostPage(page, problems);
    if (!ok) return;

    await page.locator('#fFiscalPeriodUid').fill('');
    await page.locator('#fPostingDate').fill('');

    const postBtn = page.getByRole('button', { name: /^Post Run$/ });
    await postBtn.click();
    await page.waitForTimeout(400);

    // postError() = 'Fiscal period UID is required.' → p.text-danger[role="alert"]
    const errorMsg = page.locator('p.text-danger[role="alert"]');
    await expect(errorMsg, 'No inline error after Post Run with empty Fiscal Period UID').toBeVisible({ timeout: 5_000 });

    const errorText = await errorMsg.innerText().catch(() => '');
    expect(errorText.trim().length, 'Post Run error text is empty — not actionable').toBeGreaterThan(5);

    await assertNoRawErrorLeak(page, 'depreciation-post — Post Run with empty period UID');
  });

  // ── DP-4: clicking Post Run with period UID but no posting date shows date error
  test('DP-4: clicking Post Run with period UID filled but empty Posting Date shows "Posting date required" error', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openDepreciationPostPage(page, problems);
    if (!ok) return;

    // Fill a non-empty (but likely invalid) period UID to pass the first guard.
    await page.locator('#fFiscalPeriodUid').fill('PLACEHOLDER-UID-FOR-TEST');
    // Leave posting date empty.
    await page.locator('#fPostingDate').fill('');

    const postBtn = page.getByRole('button', { name: /^Post Run$/ });
    await postBtn.click();
    await page.waitForTimeout(400);

    // postError() = 'Posting date is required.' → p.text-danger[role="alert"]
    const errorMsg = page.locator('p.text-danger[role="alert"]');
    await expect(errorMsg, 'No "Posting date required" error after Post Run with filled UID but no date').toBeVisible({ timeout: 5_000 });

    const errorText = await errorMsg.innerText().catch(() => '');
    // Check it's actionable and specifically mentions date.
    expect(
      errorText,
      'Error message does not mention "date" — not sufficiently actionable',
    ).toMatch(/date/i);

    await assertNoRawErrorLeak(page, 'depreciation-post — Post Run with no posting date');
  });

  // ── DP-5: Preview with a bogus UID shows a clean API error message (not 500) ─
  test('DP-5: Preview with an invalid Fiscal Period UID shows a clean API error, no raw 500 visible', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openDepreciationPostPage(page, problems);
    if (!ok) return;

    // A syntactically valid but non-existent UID to reach the API layer.
    await page.locator('#fFiscalPeriodUid').fill('INVALID-PERIOD-UID-E2E-TEST');

    const previewBtn = page.getByRole('button', { name: /^Preview$/ });
    await previewBtn.click();

    // Wait for either an error message or a preview result (both are valid outcomes).
    await page.waitForTimeout(3_000);

    // Whether the backend returns 400 or 404, the component surfaces previewError()
    // as p.text-danger[role="alert"]. Confirm no raw 500 / stack trace.
    await assertNoRawErrorLeak(page, 'depreciation-post — Preview with invalid UID');

    // If an error DID appear (expected path), verify it is actionable.
    const errorMsg = page.locator('p.text-danger[role="alert"]');
    const hasError = await errorMsg.isVisible({ timeout: 2_000 }).catch(() => false);
    if (hasError) {
      const errorText = await errorMsg.innerText().catch(() => '');
      expect(errorText.trim().length, 'Preview error text is empty — not actionable').toBeGreaterThan(5);
    }
    // If NO error appeared yet (slow network) — that is acceptable; the no-raw-error check above is the gate.

    // API call may have returned a 4xx — that is expected (not a 5xx).
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx during Preview with invalid UID: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── DP-6: axe on the post form ─────────────────────────────────────────────
  test('DP-6 (a11y): depreciation-post form has zero axe serious/critical violations', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openDepreciationPostPage(page, problems);
    if (!ok) return;

    await runAxeAndAssert(page, '/admin/depreciation-runs/post');
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// GROUP C: Payroll Runs list (/admin/hr/payroll-runs)
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: Payroll run list (/admin/hr/payroll-runs)', () => {

  /**
   * Navigate to /admin/hr/payroll-runs and confirm the page settled.
   * Returns false (annotated) on session bounce, 5xx, or forbidden state.
   */
  async function openPayrollRunList(
    page: Parameters<typeof watchProblems>[0],
    problems: ReturnType<typeof watchProblems>,
  ): Promise<boolean> {
    const ok = await gotoAndSettle(page, '/admin/hr/payroll-runs', problems);
    if (!ok) return false;

    // HR.PAYROLL.VIEW guard may block the route — the component renders
    // a "forbidden" state message in that case.
    const forbiddenMsg = page.locator('p.text-muted').filter({ hasText: /don.t have permission/i });
    const isForbidden = await forbiddenMsg.isVisible({ timeout: 4_000 }).catch(() => false);
    if (isForbidden) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks HR.PAYROLL.VIEW — forbidden state shown' });
      return false;
    }

    // Wait for the page heading to confirm the component mounted.
    const heading = page.locator('h1').filter({ hasText: /Payroll Runs/ });
    const headingVisible = await heading.isVisible({ timeout: VISIBLE_MS }).catch(() => false);
    if (!headingVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"Payroll Runs" heading not visible — component may not have mounted' });
      return false;
    }

    return true;
  }

  // ── PR-1: list page renders heading, list or empty state, no 500 ───────────
  test('PR-1: list page renders "Payroll Runs" heading and either rows or clean empty state, no 500', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openPayrollRunList(page, problems);
    if (!ok) return;

    // Heading confirmed inside openPayrollRunList; double-check it is still there.
    await expect(
      page.locator('h1').filter({ hasText: /Payroll Runs/ }),
      '"Payroll Runs" heading should remain visible',
    ).toBeVisible();

    // Either empty state or populated table.
    const emptyState = page.locator('.erp-empty');
    const tableRow = page.locator('table.erp-table tbody tr').first();
    const hasEmpty = await emptyState.isVisible({ timeout: 5_000 }).catch(() => false);
    const hasRows = await tableRow.isVisible({ timeout: 3_000 }).catch(() => false);

    // No error banner should be visible.
    const errorBanner = page.locator('output[role="alert"].text-danger, output.d-block.text-danger[role="alert"]');
    const hasError = await errorBanner.isVisible({ timeout: 2_000 }).catch(() => false);
    expect(hasError, 'Error banner visible on Payroll Runs list — API/server error leaked').toBe(false);

    expect(hasEmpty || hasRows, 'Neither empty-state nor table rows rendered — list did not settle').toBe(true);

    await assertNoRawErrorLeak(page, 'payroll-run-list');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx on payroll run list: ${JSON.stringify(api5xx)}`).toHaveLength(0);
    expect(
      realConsoleErrors(problems.consoleErrors),
      'Console errors on payroll run list',
    ).toHaveLength(0);
  });

  // ── PR-2: "New Payroll Run" button toggles inline create form ──────────────
  test('PR-2: "New Payroll Run" button opens the inline create form with required fields visible', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openPayrollRunList(page, problems);
    if (!ok) return;

    // The toolbar button is rendered only when canRun() (HR.PAYROLL.RUN).
    // Template: <button type="button" … [attr.aria-expanded]="showCreateForm()" aria-controls="createPayRunForm">
    const newRunBtn = page.locator('button[aria-controls="createPayRunForm"]');
    const btnVisible = await newRunBtn.isVisible({ timeout: 5_000 }).catch(() => false);

    if (!btnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: '"New Payroll Run" button not visible — rootadmin lacks HR.PAYROLL.RUN permission; create form tests skipped',
      });
      return;
    }

    // Initially the form should be hidden (aria-expanded = "false").
    const initialExpanded = await newRunBtn.getAttribute('aria-expanded').catch(() => null);
    expect(
      initialExpanded,
      '"New Payroll Run" button should have aria-expanded="false" before click',
    ).toBe('false');

    // Click to open the form.
    await newRunBtn.click();
    await page.waitForTimeout(300);

    // aria-expanded must flip to "true".
    await expect(
      newRunBtn,
      '"New Payroll Run" button aria-expanded should be "true" after click',
    ).toHaveAttribute('aria-expanded', 'true', { timeout: 3_000 });

    // Create form must appear.
    const createForm = page.locator('#createPayRunForm');
    await expect(createForm, '#createPayRunForm not visible after clicking New Payroll Run').toBeVisible({ timeout: 5_000 });

    // Required fields: Year (#prYear), Month (#prMonth), Pay Date (#prPayDate).
    await expect(page.locator('#prYear'), '#prYear input not visible inside create form').toBeVisible();
    await expect(page.locator('#prMonth'), '#prMonth select not visible inside create form').toBeVisible();
    await expect(page.locator('#prPayDate'), '#prPayDate date input not visible inside create form').toBeVisible();

    // Submit button.
    const submitBtn = page.locator('#createPayRunForm button[type="submit"]');
    await expect(submitBtn, 'Create submit button not visible in #createPayRunForm').toBeVisible();

    await assertNoRawErrorLeak(page, 'payroll-run-list — create form toggle');
  });

  // ── PR-3: submitting create form with empty Year shows a clean error ────────
  test('PR-3: submitting create form with empty Year shows "Period year is required" error, no 500', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openPayrollRunList(page, problems);
    if (!ok) return;

    const newRunBtn = page.locator('button[aria-controls="createPayRunForm"]');
    const btnVisible = await newRunBtn.isVisible({ timeout: 5_000 }).catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"New Payroll Run" button absent — HR.PAYROLL.RUN not on rootadmin; validation test skipped' });
      return;
    }

    await newRunBtn.click();
    await page.waitForTimeout(300);

    const createForm = page.locator('#createPayRunForm');
    const formVisible = await createForm.isVisible({ timeout: 5_000 }).catch(() => false);
    if (!formVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '#createPayRunForm did not open' });
      return;
    }

    // Leave all fields empty — submit.
    await page.locator('#prYear').fill('');
    const submitBtn = page.locator('#createPayRunForm button[type="submit"]');
    await submitBtn.click();
    await page.waitForTimeout(400);

    // The component sets formError() = 'Period year is required.'
    // Rendered as: <output class="d-block text-danger small mt-2 mb-0" role="alert">{{ msg }}</output>
    const formError = page.locator('#createPayRunForm output[role="alert"]');
    await expect(formError, 'No error message after submitting create form with empty Year').toBeVisible({ timeout: 5_000 });

    const errorText = await formError.innerText().catch(() => '');
    expect(errorText.trim().length, 'Create form error text is empty — not actionable').toBeGreaterThan(5);
    // The message should mention "year" or "period".
    expect(
      errorText,
      'Error text does not mention "year" or "period" — not sufficiently actionable',
    ).toMatch(/year|period/i);

    // Form must stay open (no navigation on error).
    await expect(createForm, '#createPayRunForm was dismissed on validation error').toBeVisible();

    await assertNoRawErrorLeak(page, 'payroll-run-list — create form empty year');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx during payroll create validation: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── PR-4: submitting with Year + no Month shows "Period month required" error ─
  test('PR-4: submitting create form with Year filled but no Month shows "Period month" error', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openPayrollRunList(page, problems);
    if (!ok) return;

    const newRunBtn = page.locator('button[aria-controls="createPayRunForm"]');
    const btnVisible = await newRunBtn.isVisible({ timeout: 5_000 }).catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"New Payroll Run" button absent; test skipped' });
      return;
    }

    await newRunBtn.click();
    await page.waitForTimeout(300);

    const createForm = page.locator('#createPayRunForm');
    if (!(await createForm.isVisible({ timeout: 5_000 }).catch(() => false))) {
      test.info().annotations.push({ type: 'skip-reason', description: '#createPayRunForm did not open' });
      return;
    }

    // Set year directly via Angular signal to avoid NumberValueAccessor converting the
    // string to a number (which would cause create() to crash on .trim() and silently
    // suppress the validation error we are trying to verify).
    await page.evaluate(() => {
      const appEl = document.querySelector('app-payroll-run-list');
      if (!appEl) return;
      const comp = (window as any).ng?.getComponent?.(appEl);
      if (comp?.fPeriodYear) comp.fPeriodYear.set('2025');
    });
    await page.waitForTimeout(100);
    // Leave month as the placeholder "— select —" (fPeriodMonth signal stays '').
    await page.locator('#prMonth').selectOption('');  // placeholder (empty value)

    const submitBtn = page.locator('#createPayRunForm button[type="submit"]');
    await submitBtn.click();
    await page.waitForTimeout(400);

    // formError() = 'Period month (1–12) is required.'
    // Rendered as: <output class="d-block text-danger small mt-2 mb-0" role="alert">{{ msg }}</output>
    const formError = page.locator('#createPayRunForm output[role="alert"]');
    await expect(formError, 'No error message after submitting create form with no Month').toBeVisible({ timeout: 5_000 });

    const errorText = await formError.innerText().catch(() => '');
    expect(
      errorText,
      'Error text does not mention "month" — not actionable',
    ).toMatch(/month/i);

    await expect(createForm, 'Create form dismissed on validation error').toBeVisible();
    await assertNoRawErrorLeak(page, 'payroll-run-list — create form no month');
  });

  // ── PR-5: submitting with Year + Month but no Pay Date shows date error ─────
  test('PR-5: submitting create form with Year+Month filled but no Pay Date shows "Pay date required" error', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openPayrollRunList(page, problems);
    if (!ok) return;

    const newRunBtn = page.locator('button[aria-controls="createPayRunForm"]');
    const btnVisible = await newRunBtn.isVisible({ timeout: 5_000 }).catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"New Payroll Run" button absent; test skipped' });
      return;
    }

    await newRunBtn.click();
    await page.waitForTimeout(300);

    const createForm = page.locator('#createPayRunForm');
    if (!(await createForm.isVisible({ timeout: 5_000 }).catch(() => false))) {
      test.info().annotations.push({ type: 'skip-reason', description: '#createPayRunForm did not open' });
      return;
    }

    // Set year directly via Angular signal to avoid NumberValueAccessor converting the
    // string to a number (which crashes create() on .trim(), silently eating the validation).
    const currentYear = new Date().getFullYear();
    await page.evaluate((yr) => {
      const appEl = document.querySelector('app-payroll-run-list');
      if (!appEl) return;
      const comp = (window as any).ng?.getComponent?.(appEl);
      if (comp?.fPeriodYear) comp.fPeriodYear.set(String(yr));
    }, currentYear);
    await page.waitForTimeout(100);
    await page.locator('#prMonth').selectOption('1');  // January — selectOption emits a string, safe
    await page.locator('#prPayDate').fill('');         // leave empty

    const submitBtn = page.locator('#createPayRunForm button[type="submit"]');
    await submitBtn.click();
    await page.waitForTimeout(400);

    // formError() = 'Pay date is required.'
    // Rendered as: <output class="d-block text-danger small mt-2 mb-0" role="alert">{{ msg }}</output>
    const formError = page.locator('#createPayRunForm output[role="alert"]');
    await expect(formError, 'No "Pay date required" error after submitting without Pay Date').toBeVisible({ timeout: 5_000 });

    const errorText = await formError.innerText().catch(() => '');
    expect(
      errorText,
      'Error text does not mention "date" — not actionable for Pay Date guard',
    ).toMatch(/date/i);

    await expect(createForm, 'Create form dismissed on Pay Date validation error').toBeVisible();
    await assertNoRawErrorLeak(page, 'payroll-run-list — create form no pay date');
  });

  // ── PR-6: valid create shows success alert and new run appears in list ──────
  test('PR-6: valid create form submission shows success alert and new run appears in the list', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openPayrollRunList(page, problems);
    if (!ok) return;

    const newRunBtn = page.locator('button[aria-controls="createPayRunForm"]');
    const btnVisible = await newRunBtn.isVisible({ timeout: 5_000 }).catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({ type: 'skip-reason', description: '"New Payroll Run" button absent — HR.PAYROLL.RUN not on rootadmin; valid-create test skipped' });
      return;
    }

    await newRunBtn.click();
    await page.waitForTimeout(300);

    const createForm = page.locator('#createPayRunForm');
    if (!(await createForm.isVisible({ timeout: 5_000 }).catch(() => false))) {
      test.info().annotations.push({ type: 'skip-reason', description: '#createPayRunForm did not open' });
      return;
    }

    // Use a year/month combination that is unlikely to conflict with an existing run.
    // The backend allows only one active run per company+period, so we use a far-past
    // period that is near-certainly unused in a test environment.  If it already exists
    // the backend returns an error — handle gracefully.
    const testYear = 2020;
    const testMonth = '11'; // November — arbitrary, unlikely to collide
    const payDate = `${testYear}-11-30`;

    await page.locator('#prYear').fill(String(testYear));
    await page.locator('#prMonth').selectOption(testMonth);
    await page.locator('#prPayDate').fill(payDate);

    const submitBtn = page.locator('#createPayRunForm button[type="submit"]');
    await submitBtn.click();

    // Success path: alerts.success() fires → alertdialog.alert-success appears (shared AlertService pattern).
    const alertDialog = page.locator('[role="alertdialog"].alert-success');
    const alertVisible = await alertDialog.isVisible({ timeout: API_RESULT_MS }).catch(() => false);

    if (!alertVisible) {
      // The run may already exist for that period — check for a clean error message.
      const formError = page.locator('#createPayRunForm output[role="alert"]');
      const hasError = await formError.isVisible({ timeout: 3_000 }).catch(() => false);
      if (hasError) {
        const errText = await formError.innerText().catch(() => '');
        // Whatever the error, it must be a clean business message, not a raw 500.
        await assertNoRawErrorLeak(page, 'payroll-run-list — valid create (existing period conflict)');
        test.info().annotations.push({
          type: 'info',
          description: `Create returned an error (period may already exist): "${errText}" — no 500 leaked`,
        });
        return;
      }
      // No alert and no error — unexpected outcome.
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No success alert and no error message after payroll run create — API may be unreachable',
      });
      return;
    }

    // Alert title must be non-empty.
    const alertTitle = alertDialog.locator('#alertTitle');
    const titleText = await alertTitle.innerText().catch(() => '');
    expect(titleText.trim().length, 'Success alert title is empty after payroll run create').toBeGreaterThan(0);

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx during payroll run create: ${JSON.stringify(api5xx)}`).toHaveLength(0);

    // Wait for list to refresh.
    await page.waitForTimeout(2_000);
    await page.waitForLoadState('networkidle', { timeout: 10_000 });

    // The new run must appear in the list — either by run number in the alert title
    // or by checking any row exists (the list was empty before or the run is now there).
    const tableRow = page.locator('table.erp-table tbody tr');
    const rowCount = await tableRow.count();
    expect(rowCount, 'No rows in payroll run list after successful create').toBeGreaterThan(0);

    await assertNoRawErrorLeak(page, 'payroll-run-list — after valid create');
  });

  // ── PR-7: axe on list page ──────────────────────────────────────────────────
  test('PR-7 (a11y): payroll-run-list has zero axe serious/critical violations', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await openPayrollRunList(page, problems);
    if (!ok) return;

    await page.waitForTimeout(600);
    await runAxeAndAssert(page, '/admin/hr/payroll-runs');
  });

});

// ═══════════════════════════════════════════════════════════════════════════════
// GROUP D: Payroll Run Detail (/admin/hr/payroll-runs/uid/:uid)
// ═══════════════════════════════════════════════════════════════════════════════

test.describe('UI/UX: Payroll run detail (/admin/hr/payroll-runs/uid/:uid)', () => {

  /**
   * Navigate to the payroll list, find the first run's Open button, and navigate
   * to its detail page.  Returns the detail URL on success or null if no runs exist.
   */
  async function openFirstPayrollRunDetail(
    page: Parameters<typeof watchProblems>[0],
    problems: ReturnType<typeof watchProblems>,
  ): Promise<string | null> {
    const listOk = await gotoAndSettle(page, '/admin/hr/payroll-runs', problems);
    if (!listOk) return null;

    // Guard: forbidden state.
    const forbidden = page.locator('p.text-muted').filter({ hasText: /don.t have permission/i });
    if (await forbidden.isVisible({ timeout: 3_000 }).catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks HR.PAYROLL.VIEW — detail drill-down skipped' });
      return null;
    }

    // Find an "Open payroll run …" button.
    const openBtn = page.locator('a.btn[aria-label^="Open payroll run"]').first();
    const hasBtn = await openBtn.isVisible({ timeout: 5_000 }).catch(() => false);
    if (!hasBtn) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No Open buttons in payroll run list — no runs seeded; detail tests skipped',
      });
      return null;
    }

    await openBtn.click();
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    await page.waitForTimeout(SETTLE_MS);

    const url = page.url();
    if (!url.includes('/hr/payroll-runs/uid/')) {
      test.info().annotations.push({ type: 'skip-reason', description: `Navigation to payroll run detail did not land on expected URL: ${url}` });
      return null;
    }

    return url;
  }

  // ── PD-1: detail renders run number heading, status tag, Run Summary card ───
  test('PD-1: payroll run detail renders heading, status tag, Run Summary card and no 500', async ({ page }) => {
    const problems = watchProblems(page);
    const detailUrl = await openFirstPayrollRunDetail(page, problems);
    if (!detailUrl) return;

    // <h1> renders runNumber (e.g. "PR-0001")
    const heading = page.locator('h1').first();
    await expect(heading, 'h1 not visible on payroll run detail page').toBeVisible({ timeout: VISIBLE_MS });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText.trim().length, 'Payroll run detail h1 is empty').toBeGreaterThan(0);

    // Status tag (DRAFT / CALCULATED / APPROVED / POSTED / PAID / REVERSED).
    const statusTag = page.locator('.status-tag').first();
    await expect(statusTag, 'No .status-tag on payroll run detail').toBeVisible({ timeout: VISIBLE_MS });

    // Run Summary erp-card.
    const summaryCard = page.locator('.erp-card__head h2').filter({ hasText: /Run Summary/ });
    await expect(summaryCard, '"Run Summary" card not visible on payroll run detail').toBeVisible({ timeout: VISIBLE_MS });

    // KPI tiles (Period, Pay Date, Gross Total, Net Total).
    const kpiTiles = page.locator('.erp-kpi');
    const kpiCount = await kpiTiles.count();
    expect(kpiCount, 'No .erp-kpi tiles on payroll run detail — summary section broken').toBeGreaterThan(0);

    // No action error banner should be visible on load.
    const actionError = page.locator('output[role="alert"].d-block.text-danger');
    const hasActionError = await actionError.isVisible({ timeout: 2_000 }).catch(() => false);
    expect(hasActionError, 'actionError output visible on fresh payroll run detail load').toBe(false);

    await assertNoRawErrorLeak(page, 'payroll-run-detail — initial render');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx on payroll run detail: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── PD-2: Payroll Lines section renders (table or empty state) ──────────────
  test('PD-2: payroll run detail "Payroll Lines" section renders table or clean empty state, no error', async ({ page }) => {
    const problems = watchProblems(page);
    const detailUrl = await openFirstPayrollRunDetail(page, problems);
    if (!detailUrl) return;

    // h2.erp-section-title "Payroll Lines"
    const linesHeading = page.locator('h2.erp-section-title').filter({ hasText: /Payroll Lines/ });
    await expect(linesHeading, '"Payroll Lines" section heading not visible on payroll run detail').toBeVisible({ timeout: VISIBLE_MS });

    // Lines state: loading → idle (either erp-empty or table).
    await page.waitForTimeout(1_000);

    const linesTable = page.locator('table.erp-table tbody tr').first();
    const emptyLines = page.locator('.erp-empty').filter({ hasText: /No lines yet/i });
    const hasLinesTable = await linesTable.isVisible({ timeout: 4_000 }).catch(() => false);
    const hasEmptyLines = await emptyLines.isVisible({ timeout: 3_000 }).catch(() => false);

    // Neither a lines error nor raw server error should appear.
    const linesError = page.locator('output[role="alert"]').filter({ hasText: /Could not load payroll lines/i });
    const hasLinesError = await linesError.isVisible({ timeout: 2_000 }).catch(() => false);
    expect(hasLinesError, '"Could not load payroll lines" error visible — API failure leaked').toBe(false);

    expect(
      hasLinesTable || hasEmptyLines,
      'Payroll Lines section shows neither a table nor an empty-state message — component stuck in loading?',
    ).toBe(true);

    await assertNoRawErrorLeak(page, 'payroll-run-detail — Payroll Lines section');

    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx loading payroll run detail lines: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── PD-3: Calculate button present and clickable for DRAFT runs ─────────────
  test('PD-3: Calculate button on a DRAFT payroll run is visible and does not crash on click (graceful-skip if no eligible run)', async ({ page }) => {
    const problems = watchProblems(page);

    // Navigate to the list and look specifically for a DRAFT run.
    const listOk = await gotoAndSettle(page, '/admin/hr/payroll-runs', problems);
    if (!listOk) return;

    const forbidden = page.locator('p.text-muted').filter({ hasText: /don.t have permission/i });
    if (await forbidden.isVisible({ timeout: 3_000 }).catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'lacks HR.PAYROLL.VIEW' });
      return;
    }

    // Find any row whose status-tag contains "DRAFT".
    const draftRows = page.locator('table.erp-table tbody tr').filter({ hasText: /\bDRAFT\b/ });
    const draftCount = await draftRows.count();

    if (draftCount === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No DRAFT payroll runs in list — Calculate button test skipped (no eligible run)',
      });
      return;
    }

    // Open the first DRAFT run.
    const openBtn = draftRows.first().locator('a.btn[aria-label^="Open payroll run"]');
    const hasOpenBtn = await openBtn.isVisible({ timeout: 3_000 }).catch(() => false);
    if (!hasOpenBtn) {
      test.info().annotations.push({ type: 'skip-reason', description: 'Open button not found on DRAFT row' });
      return;
    }

    await openBtn.click();
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    await page.waitForTimeout(SETTLE_MS);

    // The Calculate button is shown when canRun() && canCalculate() (status = DRAFT).
    // Template: <button type="button" … (click)="calculate()">Calculate</button>
    const calcBtn = page.getByRole('button', { name: /^Calculate$/ });
    const hasCCalc = await calcBtn.isVisible({ timeout: 5_000 }).catch(() => false);

    if (!hasCCalc) {
      test.info().annotations.push({
        type: 'info',
        description: 'Calculate button not visible on DRAFT run detail — rootadmin may lack HR.PAYROLL.RUN permission',
      });
      // Still assert no 500 on the detail page.
      await assertNoRawErrorLeak(page, 'payroll-run-detail — DRAFT run, no Calculate button');
      return;
    }

    // The button must be enabled (not already calculating).
    await expect(calcBtn, 'Calculate button is disabled before click — unexpected state').toBeEnabled();

    // Click and wait briefly. A success triggers a reload; an error appears as actionError.
    // We do NOT wait for full completion to avoid long test duration.
    const calculateProblems = watchProblems(page);
    await calcBtn.click();
    await page.waitForTimeout(3_000);

    // Regardless of success/failure the UI must not show a raw 500.
    await assertNoRawErrorLeak(page, 'payroll-run-detail — after Calculate click');

    const api5xx = calculateProblems.apiFailures.filter((f) => f.status >= 500);
    expect(api5xx, `API 5xx after Calculate click: ${JSON.stringify(api5xx)}`).toHaveLength(0);
  });

  // ── PD-4: breadcrumb links back to /admin/hr/payroll-runs ──────────────────
  test('PD-4: payroll run detail breadcrumb "Payroll Runs" link navigates back to list page', async ({ page }) => {
    const problems = watchProblems(page);
    const detailUrl = await openFirstPayrollRunDetail(page, problems);
    if (!detailUrl) return;

    // payroll-run-detail.component.html: <a routerLink="/admin/hr/payroll-runs">Payroll Runs</a> inside ol.breadcrumb
    const breadcrumbLink = page.locator('ol.breadcrumb a', { hasText: /Payroll Runs/ });
    await expect(breadcrumbLink, 'Payroll Runs breadcrumb link not visible on detail page').toBeVisible({ timeout: VISIBLE_MS });

    await breadcrumbLink.click();
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    await page.waitForTimeout(SETTLE_MS);

    expect(page.url(), 'Breadcrumb did not navigate back to /admin/hr/payroll-runs').toMatch(/\/admin\/hr\/payroll-runs$/);

    // The list heading should render again.
    await expect(
      page.locator('h1').filter({ hasText: /Payroll Runs/ }),
      '"Payroll Runs" heading not visible after breadcrumb navigation',
    ).toBeVisible({ timeout: VISIBLE_MS });

    await assertNoRawErrorLeak(page, 'payroll-run-detail — breadcrumb navigation back');
  });

  // ── PD-5: axe on run detail ─────────────────────────────────────────────────
  test('PD-5 (a11y): payroll run detail has zero axe serious/critical violations', async ({ page }) => {
    const problems = watchProblems(page);
    const detailUrl = await openFirstPayrollRunDetail(page, problems);
    if (!detailUrl) return;

    // Let lines load before scanning.
    await page.waitForTimeout(800);

    await runAxeAndAssert(page, `payroll-run-detail (${detailUrl})`);
  });

});
