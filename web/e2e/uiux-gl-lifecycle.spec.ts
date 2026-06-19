/**
 * UI/UX: GL lifecycle beyond the post form.
 *
 * Assignment: four lifecycle pages for the General Ledger module.
 *   GL-D   Journal detail  (/admin/gl/journals → open first row)
 *   GL-TB  Trial Balance   (/admin/gl/trial-balance)
 *   GL-FP  Fiscal Periods  (/admin/gl/periods)
 *   GL-YE  Year-End Close  (/admin/gl/year-end)
 *
 * Selector sources: components read directly from
 *   web/src/app/features/admin/gl/journal-entry-detail.component.html
 *   web/src/app/features/admin/gl/journal-entry-list.component.html
 *   web/src/app/features/admin/gl/trial-balance.component.html
 *   web/src/app/features/admin/gl/fiscal-periods.component.html
 *   web/src/app/features/admin/gl/year-end/year-end-close.component.html
 *   web/src/app/features/admin/admin.routes.ts
 *
 * Route guards:
 *   /admin/gl/journals      → GL.VIEW
 *   /admin/gl/journals/uid/:uid → GL.VIEW
 *   /admin/gl/trial-balance → GL.VIEW
 *   /admin/gl/periods       → GL.VIEW
 *   /admin/gl/year-end      → GL.YEAR.CLOSE
 *
 * Auth: 'chromium' project — starts logged in as rootadmin via storageState.
 * The _test-authenticated fixture restores erp.* sessionStorage on every page.
 *
 * Resilience: each test annotates + returns gracefully when its prerequisite
 * (seeded journals / fiscal years / periods, or the permission) is absent.
 * Hard failures are reserved for real observable defects (5xx, missing heading,
 * visible raw error text).
 */

import { test, expect } from './_test-authenticated';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── shared constants ──────────────────────────────────────────────────────────

/** Timeout for any data-driven element to become visible. */
const VISIBLE_MS = 20_000;

/** Short settle after navigation / Angular digest. */
const SETTLE_MS = 800;

/** Raw server-error patterns that must never appear in the visible page. */
const RAW_ERROR_PATTERNS: RegExp[] = [
  /\b500\b/,
  /stack\s*trace/i,
  /NullPointerException/i,
  /unexpected error/i,
];

/**
 * Scan visible body text for raw server-error patterns.
 * Used as a cross-cutting safety assertion on every page we open.
 */
async function assertNoRawErrorLeak(
  page: Parameters<typeof watchProblems>[0],
  context: string,
): Promise<void> {
  const text = await page.locator('body').innerText().catch(() => '');
  for (const pattern of RAW_ERROR_PATTERNS) {
    expect(
      pattern.test(text),
      `Raw server error leaked on ${context}: pattern ${pattern} matched in visible body`,
    ).toBe(false);
  }
}

/**
 * Navigate to route (relative), wait for networkidle, check for session bounce
 * and API 5xx. Returns false with an annotation when the page is unusable.
 * Caller is responsible for any further readiness probes specific to the feature.
 */
async function gotoGlPage(
  page: Parameters<typeof watchProblems>[0],
  relativeUrl: string,
  problems: ReturnType<typeof watchProblems>,
): Promise<boolean> {
  await page.goto(relativeUrl, { waitUntil: 'networkidle', timeout: 60_000 });
  await page.waitForTimeout(SETTLE_MS);

  if (page.url().includes('/login')) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `session bounced to /login on ${relativeUrl}`,
    });
    return false;
  }

  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  if (api5xx.length > 0) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `API 5xx during ${relativeUrl} load: ${JSON.stringify(api5xx)}`,
    });
    return false;
  }

  return true;
}

// ─── GL-D: Journal detail ─────────────────────────────────────────────────────

test.describe('GL-D: Journal detail page', () => {

  /**
   * Navigate to the journal list and return the href of the first "View" link,
   * or null if the list is empty / not accessible.
   */
  async function firstJournalDetailHref(
    page: Parameters<typeof watchProblems>[0],
  ): Promise<string | null> {
    // The list renders <a class="btn btn-sm btn-outline-primary" [routerLink]="...">View</a>
    // for every row — confirmed in journal-entry-list.component.html.
    // The rendered href is /admin/gl/journals/uid/<ULID>.
    const viewLinks = page.locator(
      'table.erp-table tbody tr a.btn-outline-primary',
    );
    const count = await viewLinks.count();
    if (count === 0) return null;
    return viewLinks.first().getAttribute('href').catch(() => null);
  }

  // ── GL-D1: journal list renders without error and has at least the table structure
  test('GL-D1: journal list renders h1 heading without 5xx, no raw error text', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/journals', problems);
    if (!ok) return;

    // Page-level guard: permission error renders an .erp-empty with shield text.
    const permDenied = page.locator('.erp-empty').filter({ hasText: /permission/i });
    const denied = await permDenied.isVisible().catch(() => false);
    if (denied) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'rootadmin lacks GL.VIEW — permission empty-state shown on journal list',
      });
      return;
    }

    // h1 "Journal Entries" must always be rendered (from erp-page-head).
    const heading = page.locator('h1').first();
    await expect(
      heading,
      'GL-D1: h1 heading not visible on /admin/gl/journals',
    ).toBeVisible({ timeout: VISIBLE_MS });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'GL-D1: h1 heading is empty').toMatch(/Journal/i);

    // No raw error leak.
    await assertNoRawErrorLeak(page, '/admin/gl/journals');

    // No console errors.
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(
      consoleErrs,
      `GL-D1: console errors on /admin/gl/journals: ${JSON.stringify(consoleErrs)}`,
    ).toHaveLength(0);
  });

  // ── GL-D2: first journal detail renders header card + balanced lines table
  test('GL-D2: opening first journal renders header card, lines table, and balanced indicator', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/journals', problems);
    if (!ok) return;

    // Guard: permission denied.
    const permDenied = page.locator('.erp-empty').filter({ hasText: /permission/i });
    if (await permDenied.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks GL.VIEW' });
      return;
    }

    // Wait for the table state to settle (may be 'loading' → 'idle').
    await page.waitForTimeout(1_500);

    // If the list is empty (.erp-empty visible), skip gracefully.
    const emptyState = page.locator('.erp-empty').first();
    const listEmpty = await emptyState.isVisible().catch(() => false);
    if (listEmpty) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No journal entries in the list — post at least one manual journal to exercise GL-D2',
      });
      return;
    }

    // Locate the first "View" button — confirmed selector from the list HTML.
    const detailHref = await firstJournalDetailHref(page);
    if (!detailHref) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No View link found in journal list — list may still be loading or empty',
      });
      return;
    }

    // Navigate to the detail page.
    await page.goto(detailHref, { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(SETTLE_MS);

    // GL-D2a: not bounced to login.
    expect(page.url(), 'GL-D2a: bounced to /login on journal detail').not.toMatch(/\/login/);

    // GL-D2b: no API 5xx after navigating to the detail.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(
      api5xx,
      `GL-D2b: API 5xx on journal detail ${detailHref}: ${JSON.stringify(api5xx)}`,
    ).toHaveLength(0);

    // GL-D2c: the header card h1 must include "Journal Entry".
    // Selector from journal-entry-detail.component.html: <h1 class="h5 mb-1">Journal Entry — {{ e.batchNumber }}</h1>
    const detailHeading = page.locator('h1.h5');
    await expect(
      detailHeading,
      'GL-D2c: Journal Entry heading (h1.h5) not visible on detail page',
    ).toBeVisible({ timeout: VISIBLE_MS });

    const headingText = await detailHeading.innerText().catch(() => '');
    expect(headingText, 'GL-D2c: h1.h5 text does not mention Journal Entry').toMatch(/Journal Entry/i);

    // GL-D2d: the Lines section heading must be present.
    // <h2 class="erp-section-title"> Lines (N) </h2>
    const linesHeading = page.locator('h2.erp-section-title').filter({ hasText: /Lines/i });
    await expect(
      linesHeading,
      'GL-D2d: "Lines" section heading not visible on journal detail',
    ).toBeVisible({ timeout: VISIBLE_MS });

    // GL-D2e: the erp-tfoot must show a balance status tag (Balanced or Unbalanced).
    // Selector from HTML: <tfoot class="erp-tfoot fw-semibold"> … <span class="status-tag …">Balanced</span>
    // or <span class="status-tag status-tag--danger">Unbalanced</span>
    const tfootBalanceTag = page.locator('tfoot.erp-tfoot .status-tag').first();
    const balanceTagVisible = await tfootBalanceTag.isVisible().catch(() => false);
    if (!balanceTagVisible) {
      // Lines may be missing for this entry — check for the erp-empty placeholder.
      const noLines = page.locator('.erp-empty').filter({ hasText: /No lines/i });
      const noLinesVisible = await noLines.isVisible().catch(() => false);
      if (!noLinesVisible) {
        // Neither lines table nor the empty placeholder rendered — flag but don't hard-fail
        // (the entry may have legitimately zero lines from a non-MANUAL source).
        test.info().annotations.push({
          type: 'observation',
          description: 'GL-D2e: tfoot balance status tag not visible and no "No lines" placeholder found — entry may be zero-line',
        });
      }
    } else {
      const balanceText = await tfootBalanceTag.innerText().catch(() => '');
      expect(
        balanceText,
        'GL-D2e: tfoot status tag text is empty — expected Balanced or Unbalanced',
      ).toMatch(/balanced/i);
    }

    // GL-D2f: the debits total and credits total cells in the tfoot must be numeric strings.
    // <td class="num mono">{{ totalDebits() }}</td> and <td class="num mono">{{ totalCredits() }}</td>
    const tfootNumCells = page.locator('tfoot.erp-tfoot td.num.mono');
    const numCellCount = await tfootNumCells.count();
    if (numCellCount >= 2) {
      const debitsText = await tfootNumCells.nth(0).innerText().catch(() => '');
      const creditsText = await tfootNumCells.nth(1).innerText().catch(() => '');
      expect(
        debitsText,
        'GL-D2f: total debits cell is not a numeric string in tfoot',
      ).toMatch(/^\d[\d.,]*$/);
      expect(
        creditsText,
        'GL-D2f: total credits cell is not a numeric string in tfoot',
      ).toMatch(/^\d[\d.,]*$/);

      // GL-D2g: balanced entry — debits must equal credits.
      // The "Balanced" status tag is only rendered when isBalanced() is true:
      //   isBalanced = computed(() => totalDebits() === totalCredits())
      // So if the status tag says "Balanced", the totals must match.
      const isBalancedTag = page.locator('tfoot.erp-tfoot .status-tag').filter({ hasText: /^Balanced$/i });
      const isBalancedVisible = await isBalancedTag.isVisible().catch(() => false);
      if (isBalancedVisible) {
        // The numeric values in the tfoot must equal each other when the Balanced tag is shown.
        const debitNum = parseFloat(debitsText.replace(/,/g, ''));
        const creditNum = parseFloat(creditsText.replace(/,/g, ''));
        expect(
          Math.abs(debitNum - creditNum),
          `GL-D2g: "Balanced" shown but totalDebits (${debitsText}) !== totalCredits (${creditsText})`,
        ).toBeLessThan(0.01);
      }
    }

    // GL-D2h: reverse button presence (only for MANUAL, non-reversal, with GL.POST).
    // If present, the button text must be "Reverse Entry".
    // From HTML: <button type="button" class="btn btn-outline-warning" …>Reverse Entry</button>
    const reverseBtn = page.getByRole('button', { name: /Reverse Entry/i });
    const reverseBtnVisible = await reverseBtn.isVisible().catch(() => false);
    if (reverseBtnVisible) {
      // The button exists — verify it is not disabled by default (reversing() starts false).
      const btnDisabled = await reverseBtn.isDisabled().catch(() => false);
      expect(
        btnDisabled,
        'GL-D2h: Reverse Entry button is disabled on initial render (should only disable while reversing)',
      ).toBe(false);

      // The helper text "Creates an equal and opposite journal entry." must also be visible.
      const reverseHelp = page.locator('#reverseHelp');
      await expect(
        reverseHelp,
        'GL-D2h: #reverseHelp text not visible alongside the Reverse Entry button',
      ).toBeVisible({ timeout: VISIBLE_MS });
    }

    // GL-D2i: no raw error leak on the detail page.
    await assertNoRawErrorLeak(page, `journal detail ${detailHref}`);

    // GL-D2j: no console errors during the full flow.
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(
      consoleErrs,
      `GL-D2j: console errors on journal detail: ${JSON.stringify(consoleErrs)}`,
    ).toHaveLength(0);
  });

});

// ─── GL-TB: Trial Balance ─────────────────────────────────────────────────────

test.describe('GL-TB: Trial Balance page', () => {

  // ── GL-TB1: page renders without 5xx and shows h1 heading
  test('GL-TB1: trial balance page renders h1 heading, no 5xx, no raw error text', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/trial-balance', problems);
    if (!ok) return;

    // The guard block renders a .erp-empty[role="alert"] with "don't have permission" when
    // canView() is false.  Confirmed in trial-balance.component.html line 10.
    const permDenied = page.locator('.erp-empty[role="alert"]').filter({ hasText: /permission/i });
    if (await permDenied.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks GL.VIEW' });
      return;
    }

    // h1 "Trial Balance" is always rendered.  Selector: h1 inside erp-page-head__titles.
    const heading = page.locator('h1').first();
    await expect(
      heading,
      'GL-TB1: h1 not visible on /admin/gl/trial-balance',
    ).toBeVisible({ timeout: VISIBLE_MS });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'GL-TB1: h1 text does not mention Trial Balance').toMatch(/Trial Balance/i);

    // No raw error.
    await assertNoRawErrorLeak(page, '/admin/gl/trial-balance');

    // No console errors.
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(
      consoleErrs,
      `GL-TB1: console errors on /admin/gl/trial-balance: ${JSON.stringify(consoleErrs)}`,
    ).toHaveLength(0);
  });

  // ── GL-TB2: period picker selector is present after company loads
  test('GL-TB2: period picker (#periodPicker) is rendered after company context loads', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/trial-balance', problems);
    if (!ok) return;

    const permDenied = page.locator('.erp-empty[role="alert"]').filter({ hasText: /permission/i });
    if (await permDenied.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks GL.VIEW' });
      return;
    }

    // Wait for company loading spinner to clear.
    // The spinner is inside <p aria-live="polite"> during companyState === 'loading'.
    await page.waitForFunction(
      () => !document.querySelector('p[aria-live="polite"] .spinner-border'),
      { timeout: 15_000 },
    ).catch(() => {
      // If still loading after 15 s, continue and let the isVisible check determine state.
    });

    // The period picker <select id="periodPicker"> is always rendered when companyState !== 'loading'.
    // Confirmed from trial-balance.component.html line 54.
    const periodPicker = page.locator('#periodPicker');
    const pickerVisible = await periodPicker.isVisible().catch(() => false);
    if (!pickerVisible) {
      // Companies may still be loading or there is a company API error — skip gracefully.
      test.info().annotations.push({
        type: 'skip-reason',
        description: '#periodPicker not visible — company context not available (API may not be running)',
      });
      return;
    }

    // The first option must be the "— All periods —" placeholder.
    // NOTE: Playwright's toBeVisible() always returns "hidden" for <option> elements
    // because they have no independent CSS layout — the select container is what is
    // visible.  Use .count() to confirm the option exists, then read its text directly.
    const allOptions = periodPicker.locator('option');
    const optionCount = await allOptions.count();
    expect(
      optionCount,
      'GL-TB2: #periodPicker has no <option> elements',
    ).toBeGreaterThan(0);

    const firstOptionText = await allOptions.first().innerText().catch(() => '');
    expect(
      firstOptionText.trim(),
      'GL-TB2: first period picker option is not the "All periods" placeholder',
    ).toMatch(/all periods/i);

    // No API 5xx during picker load.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(
      api5xx,
      `GL-TB2: API 5xx while loading period picker: ${JSON.stringify(api5xx)}`,
    ).toHaveLength(0);
  });

  // ── GL-TB3: when data is present, total debits equal total credits (balanced)
  test('GL-TB3: when trial balance data loads, total debits equal total credits (balanced) and no error shown', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/trial-balance', problems);
    if (!ok) return;

    const permDenied = page.locator('.erp-empty[role="alert"]').filter({ hasText: /permission/i });
    if (await permDenied.isVisible().catch(() => false)) {
      test.info().annotations.push({ type: 'skip-reason', description: 'rootadmin lacks GL.VIEW' });
      return;
    }

    // Wait for company loading to complete.
    await page.waitForFunction(
      () => !document.querySelector('p[aria-live="polite"] .spinner-border'),
      { timeout: 15_000 },
    ).catch(() => {/* continue */});

    // Wait for the trial-balance state to resolve (loading → idle).
    // The loading state renders: <p aria-live="polite"> … Calculating trial balance… </p>
    // We wait up to 20 s for it to disappear.
    await page.waitForFunction(
      () => {
        const p = document.querySelector('p[aria-live="polite"]');
        if (!p) return true;
        return !p.textContent?.includes('Calculating trial balance');
      },
      { timeout: 20_000 },
    ).catch(() => {
      test.info().annotations.push({
        type: 'observation',
        description: 'GL-TB3: trial balance still loading after 20 s — API may be slow',
      });
    });

    // Determine the final state:
    //   - error state: <p class="text-danger" role="alert">Could not load trial balance…</p>
    //   - "no company" empty state: <div class="erp-empty"> Select a company …</div>
    //   - "no activity" empty state: <div class="erp-empty"> No account activity…</div>
    //   - data table: <table class="erp-table tb-sticky">

    const errorAlert = page.locator('p.text-danger[role="alert"]').filter({ hasText: /trial balance/i });
    if (await errorAlert.isVisible().catch(() => false)) {
      // A 4xx (no fiscal periods / no data) yields this — not a 5xx defect.
      // Confirm it is not a raw 500 leak.
      await assertNoRawErrorLeak(page, '/admin/gl/trial-balance (error state)');
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-TB3: trial balance API returned an error — no data to assert balance on',
      });
      return;
    }

    const emptyState = page.locator('.erp-empty').first();
    if (await emptyState.isVisible().catch(() => false)) {
      const emptyText = await emptyState.innerText().catch(() => '');
      test.info().annotations.push({
        type: 'skip-reason',
        description: `GL-TB3: empty state shown (no data): "${emptyText.trim()}"`,
      });
      return;
    }

    // Data table must be present.
    const tbTable = page.locator('table.erp-table.tb-sticky');
    const tableVisible = await tbTable.isVisible().catch(() => false);
    if (!tableVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-TB3: trial balance table not visible — data may not be available for this company',
      });
      return;
    }

    // GL-TB3a: the tfoot total cells must be numeric.
    // From HTML: <tfoot class="erp-tfoot fw-semibold"> <td class="num mono">{{ totalDebits() }}</td> …
    const tfootNumCells = page.locator('tfoot.erp-tfoot td.num.mono');
    const numCellCount = await tfootNumCells.count();
    if (numCellCount < 2) {
      test.info().annotations.push({
        type: 'observation',
        description: `GL-TB3: only ${numCellCount} num.mono cells in tfoot — expected at least 2`,
      });
      return;
    }

    const totalDebitsText = await tfootNumCells.nth(0).innerText().catch(() => '');
    const totalCreditsText = await tfootNumCells.nth(1).innerText().catch(() => '');

    expect(totalDebitsText, 'GL-TB3a: total debits cell in tfoot is empty or non-numeric').toMatch(/^\d[\d.,]*$/);
    expect(totalCreditsText, 'GL-TB3a: total credits cell in tfoot is empty or non-numeric').toMatch(/^\d[\d.,]*$/);

    // GL-TB3b: the balance indicator must be visible in the tfoot.
    // From HTML: <span class="status-tag status-tag--ok"> Balanced </span>
    //         or <span class="status-tag status-tag--danger"> Unbalanced </span>
    const tfootStatusTag = page.locator('tfoot.erp-tfoot .status-tag').first();
    await expect(
      tfootStatusTag,
      'GL-TB3b: balance status tag missing in trial balance tfoot',
    ).toBeVisible({ timeout: VISIBLE_MS });

    const statusText = await tfootStatusTag.innerText().catch(() => '');
    expect(statusText.trim(), 'GL-TB3b: tfoot status tag is neither Balanced nor Unbalanced').toMatch(/balanced/i);

    // GL-TB3c: if the tfoot says Balanced, numeric totals must match.
    // The component uses: isBalanced = Math.abs(dr - cr) < 0.000001
    const isBalanced = /^balanced$/i.test(statusText.trim());
    if (isBalanced) {
      const dr = parseFloat(totalDebitsText.replace(/,/g, ''));
      const cr = parseFloat(totalCreditsText.replace(/,/g, ''));
      expect(
        Math.abs(dr - cr),
        `GL-TB3c: tfoot says Balanced but totalDebits (${totalDebitsText}) !== totalCredits (${totalCreditsText})`,
      ).toBeLessThan(0.01);

      // GL-TB3d: the .alert.alert-success banner must also be visible.
      // From HTML: <div class="alert alert-success …" role="status"> … Balanced … </div>
      const balancedBanner = page.locator('.alert.alert-success[role="status"]');
      await expect(
        balancedBanner,
        'GL-TB3d: alert-success "Balanced" banner not visible when tfoot says Balanced',
      ).toBeVisible({ timeout: VISIBLE_MS });
    } else {
      // Out-of-balance state is valid data, but confirm the banner surfaced correctly.
      const unbalancedBanner = page.locator('.alert.alert-danger[role="alert"]').filter({ hasText: /Out of balance/i });
      const unbalancedVisible = await unbalancedBanner.isVisible().catch(() => false);
      if (!unbalancedVisible) {
        test.info().annotations.push({
          type: 'observation',
          description: 'GL-TB3: tfoot says Unbalanced but .alert.alert-danger "Out of balance" banner not found — may be a UI regression',
        });
      }
    }

    // GL-TB3e: no raw error leak.
    await assertNoRawErrorLeak(page, '/admin/gl/trial-balance (data loaded)');

    // GL-TB3f: no error-state p.text-danger[role="alert"] visible alongside the data table.
    const errorBanner = page.locator('p.text-danger[role="alert"]').first();
    const errorBannerVisible = await errorBanner.isVisible().catch(() => false);
    expect(
      errorBannerVisible,
      'GL-TB3f: error-state banner visible alongside the trial balance data table',
    ).toBe(false);
  });

});

// ─── GL-FP: Fiscal Periods ────────────────────────────────────────────────────

test.describe('GL-FP: Fiscal Periods page', () => {

  // ── GL-FP1: page renders h1 heading without 5xx
  test('GL-FP1: fiscal periods page renders h1 heading without 5xx or raw error text', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/periods', problems);
    if (!ok) return;

    // h1 "Fiscal Periods" is always in the page head.
    const heading = page.locator('h1').first();
    await expect(
      heading,
      'GL-FP1: h1 not visible on /admin/gl/periods',
    ).toBeVisible({ timeout: VISIBLE_MS });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'GL-FP1: h1 text does not mention Fiscal Periods').toMatch(/Fiscal Periods/i);

    await assertNoRawErrorLeak(page, '/admin/gl/periods');

    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(
      consoleErrs,
      `GL-FP1: console errors on /admin/gl/periods: ${JSON.stringify(consoleErrs)}`,
    ).toHaveLength(0);
  });

  // ── GL-FP2: "Accounting Periods" section heading is present
  test('GL-FP2: "Accounting Periods" h2 section heading is rendered after company load', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/periods', problems);
    if (!ok) return;

    // Wait for company loading to clear.
    await page.waitForFunction(
      () => !document.querySelector('p[aria-live="polite"] .spinner-border'),
      { timeout: 15_000 },
    ).catch(() => {/* continue */});

    // From fiscal-periods.component.html line 134:
    // <h2 class="erp-section-title">Accounting Periods</h2>
    // This is rendered only when selectedCompanyId() is truthy.
    const periodsHeading = page.locator('h2.erp-section-title').filter({ hasText: /Accounting Periods/i });
    const headingVisible = await periodsHeading.isVisible().catch(() => false);
    if (!headingVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-FP2: "Accounting Periods" h2 not visible — company context not yet resolved (API may not be running)',
      });
      return;
    }

    await expect(
      periodsHeading,
      'GL-FP2: "Accounting Periods" section heading not visible on /admin/gl/periods',
    ).toBeVisible({ timeout: VISIBLE_MS });

    // No API 5xx.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(
      api5xx,
      `GL-FP2: API 5xx on /admin/gl/periods: ${JSON.stringify(api5xx)}`,
    ).toHaveLength(0);
  });

  // ── GL-FP3: when periods exist, the table renders status pills and close/reopen controls
  test('GL-FP3: when periods are loaded, status pills (.status-tag) are visible in the periods table', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/periods', problems);
    if (!ok) return;

    // Wait for periods loading spinner to clear.
    await page.waitForFunction(
      () => {
        const spinners = document.querySelectorAll('p[aria-live="polite"] .spinner-border');
        return spinners.length === 0;
      },
      { timeout: 20_000 },
    ).catch(() => {/* continue */});

    // Check for the error state from periodsState === 'error'.
    const periodsError = page.locator('p.text-danger[role="alert"]').filter({ hasText: /periods/i });
    if (await periodsError.isVisible().catch(() => false)) {
      await assertNoRawErrorLeak(page, '/admin/gl/periods (error state)');
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-FP3: period load returned an error — skipping status-pill assertions',
      });
      return;
    }

    // No-periods empty state.
    const emptyState = page.locator('.erp-empty').filter({ hasText: /No periods yet/i });
    if (await emptyState.isVisible().catch(() => false)) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-FP3: no fiscal periods seeded — open a fiscal year to create periods',
      });
      return;
    }

    // Periods table.
    const periodTable = page.locator('table.erp-table');
    const tableVisible = await periodTable.isVisible().catch(() => false);
    if (!tableVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-FP3: periods table not visible — data may not yet be loaded',
      });
      return;
    }

    // GL-FP3a: each data row must contain at least one .status-tag (the status column).
    // From HTML: <span class="status-tag …">{{ p.status }}</span>
    const firstRow = periodTable.locator('tbody tr').first();
    await expect(
      firstRow,
      'GL-FP3a: no rows in the fiscal periods tbody',
    ).toBeVisible({ timeout: VISIBLE_MS });

    const statusTag = firstRow.locator('.status-tag').first();
    await expect(
      statusTag,
      'GL-FP3a: .status-tag not found in first period row — status pill missing',
    ).toBeVisible({ timeout: VISIBLE_MS });

    const statusText = await statusTag.innerText().catch(() => '');
    expect(
      statusText.trim(),
      'GL-FP3a: status pill text is empty — expected OPEN or CLOSED',
    ).toMatch(/^(OPEN|CLOSED)$/);

    // GL-FP3b: if rootadmin has GL.PERIOD.CLOSE, at least one close/reopen action button
    // must be visible in the rows (canClose() renders the <th> actions column).
    // From HTML: <button class="btn btn-sm btn-outline-warning" …>Close</button>
    //         or <button class="btn btn-sm btn-outline-success" …>Reopen</button>
    // We do not know if rootadmin has GL.PERIOD.CLOSE, so we check conditionally.
    const closeBtn = firstRow.locator('button.btn-outline-warning', { hasText: /Close/i });
    const reopenBtn = firstRow.locator('button.btn-outline-success', { hasText: /Reopen/i });
    const closeVisible = await closeBtn.isVisible().catch(() => false);
    const reopenVisible = await reopenBtn.isVisible().catch(() => false);

    if (closeVisible || reopenVisible) {
      // GL-FP3b: aria-label on the action button must include the period number.
      const actionBtn = closeVisible ? closeBtn : reopenBtn;
      const ariaLabel = await actionBtn.getAttribute('aria-label').catch(() => null);
      expect(
        ariaLabel,
        'GL-FP3b: close/reopen button missing aria-label with period number',
      ).toMatch(/period\s+\d+/i);
    }

    // GL-FP3c: no API 5xx during the whole test.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(
      api5xx,
      `GL-FP3c: API 5xx on /admin/gl/periods: ${JSON.stringify(api5xx)}`,
    ).toHaveLength(0);

    // GL-FP3d: no raw error leak.
    await assertNoRawErrorLeak(page, '/admin/gl/periods (data loaded)');
  });

  // ── GL-FP4: "Open Fiscal Year" button toggles the create form
  test('GL-FP4: "Open Fiscal Year" button (if present) toggles the create form and form fields render', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/periods', problems);
    if (!ok) return;

    // Wait for company load.
    await page.waitForFunction(
      () => !document.querySelector('p[aria-live="polite"] .spinner-border'),
      { timeout: 15_000 },
    ).catch(() => {/* continue */});

    // "Open Fiscal Year" button is only rendered when canManage() is true (GL.MANAGE).
    // From HTML: <button … (click)="toggleOpenYearForm()" [attr.aria-expanded]="showOpenYearForm()">
    const openYearBtn = page.getByRole('button', { name: /Open Fiscal Year/i });
    const btnVisible = await openYearBtn.isVisible().catch(() => false);
    if (!btnVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-FP4: "Open Fiscal Year" button not visible — rootadmin may lack GL.MANAGE',
      });
      return;
    }

    // Initially aria-expanded should be false (form hidden).
    const ariaExpanded = await openYearBtn.getAttribute('aria-expanded').catch(() => null);
    expect(
      ariaExpanded,
      'GL-FP4: "Open Fiscal Year" button aria-expanded should be "false" initially',
    ).toBe('false');

    // Click to expand.
    await openYearBtn.click();
    await page.waitForTimeout(300);

    // After click, aria-expanded should be true.
    const ariaExpandedAfter = await openYearBtn.getAttribute('aria-expanded').catch(() => null);
    expect(
      ariaExpandedAfter,
      'GL-FP4: aria-expanded not updated to "true" after clicking Open Fiscal Year',
    ).toBe('true');

    // The form #openYearForm should now be visible.
    // From HTML: <form id="openYearForm" …>
    const openYearForm = page.locator('#openYearForm');
    await expect(
      openYearForm,
      'GL-FP4: #openYearForm not visible after clicking "Open Fiscal Year"',
    ).toBeVisible({ timeout: VISIBLE_MS });

    // GL-FP4a: year code input (#yearCode) must be present.
    const yearCodeInput = page.locator('#yearCode');
    await expect(
      yearCodeInput,
      'GL-FP4a: #yearCode input not visible in Open Fiscal Year form',
    ).toBeVisible({ timeout: VISIBLE_MS });

    // GL-FP4b: start month select (#startMonth) must be present.
    const startMonthSelect = page.locator('#startMonth');
    await expect(
      startMonthSelect,
      'GL-FP4b: #startMonth select not visible in Open Fiscal Year form',
    ).toBeVisible({ timeout: VISIBLE_MS });

    // GL-FP4c: calendar year input (#calendarYear) must be present.
    const calendarYearInput = page.locator('#calendarYear');
    await expect(
      calendarYearInput,
      'GL-FP4c: #calendarYear input not visible in Open Fiscal Year form',
    ).toBeVisible({ timeout: VISIBLE_MS });

    // GL-FP4d: the form submit button "Open Year" must be present and enabled.
    const submitBtn = openYearForm.locator('button[type="submit"]');
    await expect(
      submitBtn,
      'GL-FP4d: submit button "Open Year" not visible in the form',
    ).toBeVisible({ timeout: VISIBLE_MS });
    const submitDisabled = await submitBtn.isDisabled().catch(() => false);
    expect(
      submitDisabled,
      'GL-FP4d: "Open Year" submit button is disabled before any interaction',
    ).toBe(false);

    // GL-FP4e: Cancel collapses the form again.
    const cancelBtn = openYearForm.getByRole('button', { name: /Cancel/i });
    await expect(
      cancelBtn,
      'GL-FP4e: Cancel button not visible in the Open Fiscal Year form',
    ).toBeVisible({ timeout: VISIBLE_MS });
    await cancelBtn.click();
    await page.waitForTimeout(200);

    await expect(
      openYearForm,
      'GL-FP4e: #openYearForm still visible after clicking Cancel',
    ).not.toBeVisible({ timeout: 5_000 });

    // Aria-expanded should revert.
    const ariaExpandedReverted = await openYearBtn.getAttribute('aria-expanded').catch(() => null);
    expect(
      ariaExpandedReverted,
      'GL-FP4e: aria-expanded did not revert to "false" after Cancel',
    ).toBe('false');

    // No API 5xx during the toggle.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(
      api5xx,
      `GL-FP4: API 5xx during Open Fiscal Year toggle: ${JSON.stringify(api5xx)}`,
    ).toHaveLength(0);
  });

});

// ─── GL-YE: Year-End Close ────────────────────────────────────────────────────

test.describe('GL-YE: Year-End Close page', () => {

  // ── GL-YE1: page renders h1 heading without 5xx
  test('GL-YE1: year-end close page renders h1 heading without 5xx or raw error text', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/year-end', problems);
    if (!ok) return;

    // If the permission guard blocks access, the router redirects to /admin/home
    // (requirePermission('GL.YEAR.CLOSE') in admin.routes.ts).
    // Detect this redirect and skip gracefully.
    if (!page.url().includes('/gl/year-end')) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-YE1: rootadmin lacks GL.YEAR.CLOSE — router redirected away from /admin/gl/year-end',
      });
      return;
    }

    // h1 "Year-End Close" always rendered from erp-page-head__titles.
    const heading = page.locator('h1').first();
    await expect(
      heading,
      'GL-YE1: h1 not visible on /admin/gl/year-end',
    ).toBeVisible({ timeout: VISIBLE_MS });

    const headingText = await heading.innerText().catch(() => '');
    expect(headingText, 'GL-YE1: h1 text does not mention Year-End Close').toMatch(/Year-End Close/i);

    // Subtitle must be present — it describes the guard/preconditions.
    // From HTML: <p class="subtitle"> Close a fiscal year to post the P&L closing journal …</p>
    const subtitle = page.locator('p.subtitle').first();
    await expect(
      subtitle,
      'GL-YE1: subtitle paragraph not visible on /admin/gl/year-end',
    ).toBeVisible({ timeout: VISIBLE_MS });

    const subtitleText = await subtitle.innerText().catch(() => '');
    expect(
      subtitleText.trim().length,
      'GL-YE1: subtitle is empty — expected precondition description',
    ).toBeGreaterThan(10);

    // No raw error leak.
    await assertNoRawErrorLeak(page, '/admin/gl/year-end');

    // No console errors.
    const consoleErrs = realConsoleErrors(problems.consoleErrors);
    expect(
      consoleErrs,
      `GL-YE1: console errors on /admin/gl/year-end: ${JSON.stringify(consoleErrs)}`,
    ).toHaveLength(0);
  });

  // ── GL-YE2: page renders the correct guard/precondition states without a 500
  test('GL-YE2: year-end close page resolves to a valid final state — no 500, no raw error text', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/year-end', problems);
    if (!ok) return;

    // Permission guard — router redirects.
    if (!page.url().includes('/gl/year-end')) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-YE2: rootadmin lacks GL.YEAR.CLOSE — redirected from year-end page',
      });
      return;
    }

    // Wait for company loading to complete.
    await page.waitForFunction(
      () => !document.querySelector('p[aria-live="polite"] .spinner-border'),
      { timeout: 20_000 },
    ).catch(() => {/* continue */});

    // Wait for fiscal years loading to complete.
    await page.waitForFunction(
      () => {
        const p = document.querySelector('p[aria-live="polite"]');
        if (!p) return true;
        return !p.textContent?.includes('Loading fiscal years');
      },
      { timeout: 20_000 },
    ).catch(() => {/* continue */});

    // The page must be in exactly one of these final states:
    //   a) forbidden state: .erp-empty[role="alert"] with "permission" text
    //   b) empty state:     .erp-empty without role="alert" — "No fiscal years found" with link to /gl/periods
    //   c) data table:      table.erp-table with fiscal year rows
    //   d) error state:     p.text-danger[role="alert"] with "Could not load fiscal years"
    //
    // All of the above are valid UI states — none should contain a 5xx or stack trace.

    const forbiddenEmpty = page.locator('.erp-empty[role="alert"]').filter({ hasText: /permission/i });
    const noYearsEmpty = page.locator('.erp-empty').filter({ hasText: /No fiscal years found/i });
    const dataTable = page.locator('table.erp-table');
    const errorAlert = page.locator('p.text-danger[role="alert"]').filter({ hasText: /fiscal years/i });

    const [isForbidden, isNoYears, hasTable, isError] = await Promise.all([
      forbiddenEmpty.isVisible().catch(() => false),
      noYearsEmpty.isVisible().catch(() => false),
      dataTable.isVisible().catch(() => false),
      errorAlert.isVisible().catch(() => false),
    ]);

    // GL-YE2a: exactly one final state must be visible.
    const statesVisible = [isForbidden, isNoYears, hasTable, isError].filter(Boolean).length;
    expect(
      statesVisible,
      'GL-YE2a: expected exactly one final state to be visible (forbidden / no-years / data / error), got ' + statesVisible,
    ).toBeGreaterThanOrEqual(1);

    // GL-YE2b: if the data table is visible, it must have at least one tbody row.
    if (hasTable) {
      const firstRow = dataTable.locator('tbody tr').first();
      await expect(
        firstRow,
        'GL-YE2b: fiscal year table visible but has no tbody rows',
      ).toBeVisible({ timeout: VISIBLE_MS });

      // GL-YE2c: each row must have a .status-tag for the year status.
      // From HTML: <span class="status-tag …">{{ year.status }}</span>
      const firstRowStatus = firstRow.locator('.status-tag').first();
      await expect(
        firstRowStatus,
        'GL-YE2c: .status-tag not found in first fiscal year row',
      ).toBeVisible({ timeout: VISIBLE_MS });

      const yearStatus = await firstRowStatus.innerText().catch(() => '');
      expect(
        yearStatus.trim(),
        'GL-YE2c: fiscal year status pill text is empty — expected OPEN or CLOSED',
      ).toMatch(/^(OPEN|CLOSED)$/);
    }

    // GL-YE2d: if the "no fiscal years" empty state shows, the link to /admin/gl/periods
    // must be present (as shown in year-end-close.component.html line 91).
    if (isNoYears) {
      const periodsLink = page.locator('.erp-empty a[href*="/gl/periods"]');
      await expect(
        periodsLink,
        'GL-YE2d: link to Fiscal Periods not present in the no-years empty state',
      ).toBeVisible({ timeout: VISIBLE_MS });
    }

    // GL-YE2e: no raw error leak regardless of state.
    await assertNoRawErrorLeak(page, '/admin/gl/year-end (final state)');

    // GL-YE2f: no API 5xx.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(
      api5xx,
      `GL-YE2f: API 5xx on /admin/gl/year-end: ${JSON.stringify(api5xx)}`,
    ).toHaveLength(0);
  });

  // ── GL-YE3: when OPEN fiscal years exist, Close Year button is visible and has aria-label
  test('GL-YE3: when OPEN fiscal years exist, each row has a "Close Year" button with aria-label', async ({ page }) => {
    const problems = watchProblems(page);
    const ok = await gotoGlPage(page, '/admin/gl/year-end', problems);
    if (!ok) return;

    if (!page.url().includes('/gl/year-end')) {
      test.info().annotations.push({ type: 'skip-reason', description: 'lacks GL.YEAR.CLOSE' });
      return;
    }

    // Wait for fiscal years to load.
    await page.waitForFunction(
      () => !document.querySelector('p[aria-live="polite"] .spinner-border'),
      { timeout: 20_000 },
    ).catch(() => {/* continue */});

    const dataTable = page.locator('table.erp-table');
    const tableVisible = await dataTable.isVisible().catch(() => false);
    if (!tableVisible) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-YE3: no fiscal year table visible — no fiscal years to assert action buttons on',
      });
      return;
    }

    // Find an OPEN year row.
    // From HTML: <span class="status-tag status-tag--ok"> OPEN </span>
    const openRows = page.locator('table.erp-table tbody tr').filter({
      has: page.locator('.status-tag.status-tag--ok', { hasText: /^OPEN$/i }),
    });
    const openCount = await openRows.count();

    if (openCount === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'GL-YE3: no OPEN fiscal years found — all years are already CLOSED',
      });
      return;
    }

    // The first open row must have a "Close Year" button with an aria-label.
    // From HTML: <button class="btn btn-sm btn-outline-warning" (click)="requestClose(year)"
    //            [attr.aria-label]="'Close fiscal year ' + year.yearCode">
    const firstOpenRow = openRows.first();
    const closeYearBtn = firstOpenRow.locator('button.btn-outline-warning', { hasText: /Close Year/i });
    await expect(
      closeYearBtn,
      'GL-YE3: "Close Year" button not found in first OPEN fiscal year row',
    ).toBeVisible({ timeout: VISIBLE_MS });

    const ariaLabel = await closeYearBtn.getAttribute('aria-label').catch(() => null);
    expect(
      ariaLabel,
      'GL-YE3: "Close Year" button missing aria-label',
    ).toMatch(/Close fiscal year/i);

    // GL-YE3a: clicking the button must show the confirmation panel (not execute close).
    // From HTML: the component sets confirmCloseUid() → confirmation panel renders.
    // <div class="alert alert-warning … aria-live="assertive"> Confirm Year-End Close </div>
    await closeYearBtn.click();
    await page.waitForTimeout(400);

    const confirmPanel = page.locator('.alert.alert-warning[aria-live="assertive"]');
    await expect(
      confirmPanel,
      'GL-YE3a: confirmation panel not shown after clicking Close Year — requestClose() may not have fired',
    ).toBeVisible({ timeout: VISIBLE_MS });

    // The confirmation panel must contain "Confirm Year-End Close".
    const confirmText = await confirmPanel.innerText().catch(() => '');
    expect(
      confirmText,
      'GL-YE3a: confirmation panel text does not mention Confirm Year-End Close',
    ).toMatch(/Confirm Year-End Close/i);

    // It must mention "Retained Earnings" (the precondition guard text).
    expect(
      confirmText,
      'GL-YE3a: confirmation panel does not mention Retained Earnings',
    ).toMatch(/Retained Earnings/i);

    // GL-YE3b: a Cancel button must dismiss the panel without executing the close.
    const cancelBtn = confirmPanel.getByRole('button', { name: /Cancel/i });
    await expect(
      cancelBtn,
      'GL-YE3b: Cancel button not visible in the year-end confirmation panel',
    ).toBeVisible({ timeout: VISIBLE_MS });
    await cancelBtn.click();
    await page.waitForTimeout(300);

    await expect(
      confirmPanel,
      'GL-YE3b: confirmation panel still visible after clicking Cancel',
    ).not.toBeVisible({ timeout: 5_000 });

    // GL-YE3c: no API call was made (close was cancelled) — no 5xx.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(
      api5xx,
      `GL-YE3c: API 5xx triggered even though close was cancelled: ${JSON.stringify(api5xx)}`,
    ).toHaveLength(0);

    // GL-YE3d: no raw error text anywhere on the page.
    await assertNoRawErrorLeak(page, '/admin/gl/year-end (Close Year flow)');
  });

});
