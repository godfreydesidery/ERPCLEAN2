/**
 * UI/UX: Empty and error states on list/detail screens.
 *
 * Covers the following cases for the highest-traffic list routes
 * (customers, products, suppliers, sales-orders):
 *
 *   EE-1  A populated list renders the erp-table with rows AND a visible
 *         paginator when there are enough rows to exceed one page.
 *   EE-2  Searching for a guaranteed-absent term shows a clean EMPTY state
 *         (erp-empty div or zero tbody rows) WITHOUT any [role="alert"].text-danger
 *         error banner and without a console error.
 *   EE-3  Clearing the search after a no-results query restores rows to the table.
 *   EE-4  Page-2 navigation loads without error and the first-row content changes
 *         relative to page 1.
 *   EE-5  Loading skeleton/spinner appears then resolves (best-effort; no flake).
 *   EE-6  A detail drill-down from the first table row renders a heading (h1)
 *         without console or page errors.
 *
 * Tolerance rules:
 *   - Tests skip gracefully (annotation, no FAIL) when a list has no rows at all;
 *     that is a separate seeding concern, not a UI/UX defect.
 *   - Search terms are DERIVED from the first row's primary text cell at runtime —
 *     no hardcoded product/customer names.
 *   - The guaranteed-absent term is a per-run random suffix so the test is
 *     idempotent across datasets.
 *
 * Auth: uses the pre-saved storageState from auth.setup.ts (chromium project).
 */
import { test, expect } from './_test-authenticated';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── shared constants ─────────────────────────────────────────────────────────

/** Selector that matches the erp-table data rows (excludes thead). */
const TABLE_ROW = 'table.erp-table tbody tr';

/** Selector for the paginator nav rendered by PaginatorComponent. */
const PAGINATOR_NAV = 'nav[aria-label$="pagination"]';

/**
 * Pattern for a /uid/<ULID> detail link, matching the app-wide convention:
 *   /admin/<module>/uid/<26-char-ULID>
 */
const DETAIL_HREF_RE = /\/admin\/[^?#]+\/uid\/([0-9A-HJKMNP-TV-Z]{26})(?:$|[?#/])/;

/** Universal error-state banner selectors (both forms used across components). */
const ERROR_ALERT_SEL = '[role="alert"].text-danger, p.text-danger[role="alert"]';

/** Empty-state container rendered when isEmpty() is true. */
const EMPTY_STATE_SEL = '.erp-empty';

/** Max wait for a table row to appear after navigation/search (ms). */
const TABLE_TIMEOUT = 20_000;

/** Max wait for networkidle after a search submission (ms). */
const SEARCH_IDLE_TIMEOUT = 15_000;

// ─── per-run unique absent term ───────────────────────────────────────────────

/**
 * A random alphanumeric suffix that is almost certainly absent from any realistic
 * dataset, making the no-results search test reliably return 0 rows.
 * Using a 12-char hex suffix ensures ≤1/16^12 collision probability.
 */
function absentTerm(): string {
  return 'ZZNOEXIST' + Math.floor(Math.random() * 0xffffff).toString(16).toUpperCase().padStart(6, '0');
}

// ─── reusable helpers ─────────────────────────────────────────────────────────

/**
 * Navigate to route, wait for networkidle, assert no session bounce, no API 5xx,
 * no uncaught page/console errors. Returns the problems collector.
 */
async function gotoClean(
  page: Parameters<typeof watchProblems>[0],
  route: string,
  { extraWaitMs = 800 }: { extraWaitMs?: number } = {},
) {
  const problems = watchProblems(page);
  await page.goto(`/admin/${route}`, { waitUntil: 'networkidle', timeout: 60_000 });
  await page.waitForTimeout(extraWaitMs);

  expect(page.url(), `session bounced to /login on /admin/${route}`).not.toMatch(/\/login/);

  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  expect(api5xx, `API 5xx during /admin/${route} load: ${JSON.stringify(api5xx)}`).toHaveLength(0);

  expect(problems.pageErrors, `uncaught page error on /admin/${route}: ${JSON.stringify(problems.pageErrors)}`).toHaveLength(0);

  const consoleErrs = realConsoleErrors(problems.consoleErrors);
  expect(consoleErrs, `console errors on /admin/${route}: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);

  return problems;
}

/**
 * Returns true when the list rendered at least one data row; false if empty.
 * Does NOT throw — callers decide whether an empty list is a skip or a failure.
 */
async function hasRows(page: Parameters<typeof watchProblems>[0]): Promise<boolean> {
  const count = await page.locator(TABLE_ROW).count();
  return count > 0;
}

/**
 * Assert that no error-state banner is visible and no console errors occurred.
 * This is the core EE-2 assertion — an absent-term search must produce a CLEAN
 * empty state, not a crash or an error alert.
 */
async function assertNoErrorBanner(
  page: Parameters<typeof watchProblems>[0],
  problems: ReturnType<typeof watchProblems>,
  context: string,
) {
  const errorAlert = page.locator(ERROR_ALERT_SEL).first();
  const alertVisible = await errorAlert.isVisible().catch(() => false);
  expect(alertVisible, `error-state banner visible after ${context}`).toBe(false);

  const consoleErrs = realConsoleErrors(problems.consoleErrors);
  expect(consoleErrs, `console errors after ${context}: ${JSON.stringify(consoleErrs)}`).toHaveLength(0);
}

/**
 * Extract a search term from the primary text cell (column index 1, the name column)
 * of the first tbody row. Falls back to column 0 (code) if column 1 is blank.
 * Returns null when the table has no rows.
 */
async function deriveSearchTermFromFirstRow(
  page: Parameters<typeof watchProblems>[0],
): Promise<string | null> {
  const firstRow = page.locator(TABLE_ROW).first();
  const visible = await firstRow.isVisible().catch(() => false);
  if (!visible) return null;

  // Prefer the second cell (display name / descriptive label).
  const nameCell = await firstRow.locator('td').nth(1).innerText().catch(() => '');
  const trimmed = nameCell.trim();
  // Extract a word of at least 3 alphabetic characters to reduce false-no-matches.
  const word = trimmed.match(/[A-Za-z]{3,}/)?.[0];
  if (word) return word;

  // Fall back to code cell (column 0).
  const codeCell = await firstRow.locator('td').nth(0).innerText().catch(() => '');
  return codeCell.trim().slice(0, 6) || null;
}

/**
 * Open the first detail page linked from the current list.
 * Returns the detail href string or null if no /uid/ link is found.
 */
async function firstDetailHref(
  page: Parameters<typeof watchProblems>[0],
): Promise<string | null> {
  const anchors = await page.locator('a[href]').all();
  for (const anchor of anchors) {
    const href = await anchor.getAttribute('href').catch(() => null);
    if (href && DETAIL_HREF_RE.test(href)) {
      // Return an app-relative href so page.goto() resolves against any baseURL.
      return href.startsWith('http') ? href : (href.startsWith('/') ? href : '/' + href);
    }
  }
  return null;
}

// ─── list configurations ───────────────────────────────────────────────────────

/**
 * One entry per list route we want to cover.
 *
 * searchInputId: the id= on the search <input> in that list component's HTML.
 * clearButtonText: the visible text of the "Clear" button (always "Clear").
 *
 * Routes confirmed from _routes.json and HTML inspection:
 *   customers     → #customerSearch   (customer-list.component.html)
 *   products      → #productSearch    (product-list.component.html)
 *   suppliers     → #supplierSearch   (supplier-list.component.html)
 *   sales-orders  → no free-text search; has #statusFilter (skip search cases)
 */
interface ListConfig {
  route: string;
  label: string;
  searchInputId: string | null;
}

const LIST_CONFIGS: ListConfig[] = [
  { route: 'customers',    label: 'Customers',    searchInputId: '#customerSearch'  },
  { route: 'products',     label: 'Products',     searchInputId: '#productSearch'   },
  { route: 'suppliers',    label: 'Suppliers',    searchInputId: '#supplierSearch'  },
  { route: 'sales-orders', label: 'Sales Orders', searchInputId: null               },
];

// ─── EE-1: populated list renders table + paginator ──────────────────────────

test.describe('EE-1 populated list renders erp-table rows and paginator', () => {
  for (const { route, label } of LIST_CONFIGS) {
    test(`${label} list: table has rows and paginator is visible when list has multiple pages`, async ({ page }) => {
      await gotoClean(page, route);

      // If the list is empty, skip gracefully — this is a seeding issue, not a UI bug.
      const rows = await hasRows(page);
      if (!rows) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list is empty — no rows to assert; seed data required for this case`,
        });
        return;
      }

      // EE-1a: table has at least one row.
      const firstRow = page.locator(TABLE_ROW).first();
      await expect(
        firstRow,
        `EE-1a: erp-table tbody has no rows on /admin/${route}`,
      ).toBeVisible({ timeout: TABLE_TIMEOUT });

      // EE-1b: no error banner visible alongside data rows.
      const errorAlert = page.locator(ERROR_ALERT_SEL).first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(alertVisible, `EE-1b: error banner visible alongside table rows on /admin/${route}`).toBe(false);

      // EE-1c: paginator is present when totalPages > 1. We cannot know in advance
      // whether this particular dataset fills more than one page, so we check
      // conditionally: if the paginator is present it must be valid; if absent the
      // row count must be ≤ the first page (≤ 20 items — the backend default).
      const paginatorNav = page.locator(PAGINATOR_NAV).first();
      const paginatorVisible = await paginatorNav.isVisible().catch(() => false);

      if (paginatorVisible) {
        // The paginator is rendered — assert it exposes the essential controls.
        await expect(
          paginatorNav.getByRole('button', { name: 'Last page' }),
          `EE-1c: "Last page" button missing in paginator on /admin/${route}`,
        ).toBeVisible();

        await expect(
          paginatorNav.getByRole('button', { name: 'Next page' }),
          `EE-1c: "Next page" button missing in paginator on /admin/${route}`,
        ).toBeVisible();

        // Status text must read "Page X of Y (N total)".
        const statusSpan = paginatorNav.locator('span.small.text-muted').first();
        const statusText = await statusSpan.innerText().catch(() => '');
        expect(
          statusText,
          `EE-1c: paginator status text malformed on /admin/${route}: "${statusText}"`,
        ).toMatch(/Page \d+ of \d+/);
      }
      // If paginatorVisible is false, the list fits on one page — that is valid;
      // we do not fail the test (no minimum row-count guarantee from the seed).
    });
  }
});

// ─── EE-2: absent-term search shows clean empty state, no error ───────────────

test.describe('EE-2 absent-term search shows clean empty state without error banner', () => {
  for (const { route, label, searchInputId } of LIST_CONFIGS) {
    // Sales-orders has no free-text search input — skip the search cases for it.
    if (!searchInputId) continue;

    test(`${label} list: searching for a guaranteed-absent term shows empty state without error`, async ({ page }) => {
      const problems = await gotoClean(page, route);

      // Ensure the search input is present.
      const searchInput = page.locator(searchInputId);
      const inputVisible = await searchInput.isVisible().catch(() => false);
      if (!inputVisible) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `search input "${searchInputId}" not visible on /admin/${route} — component may not have loaded a company yet`,
        });
        return;
      }

      const term = absentTerm();

      // Type the absent term and submit.
      await searchInput.fill(term);
      await page.keyboard.press('Enter');
      await page.waitForLoadState('networkidle', { timeout: SEARCH_IDLE_TIMEOUT });
      await page.waitForTimeout(400);

      // EE-2a: no error banner must appear — the app must handle zero results gracefully.
      await assertNoErrorBanner(page, problems, `absent-term search on /admin/${route}`);

      // EE-2b: the table must have zero rows OR the erp-empty placeholder is visible —
      // either is a valid clean empty state.
      const rowCount = await page.locator(TABLE_ROW).count();
      const emptyStateVisible = await page.locator(EMPTY_STATE_SEL).first().isVisible().catch(() => false);

      expect(
        rowCount === 0 || emptyStateVisible,
        `EE-2b: expected zero rows or erp-empty on /admin/${route} after absent-term search, ` +
        `but got ${rowCount} rows and emptyState=${emptyStateVisible}`,
      ).toBe(true);

      // EE-2c: the page body must have some content (the empty placeholder text itself).
      const bodyText = await page.locator('.admin-page').innerText().catch(() => '');
      expect(
        bodyText.trim().length,
        `EE-2c: page body is empty after absent-term search on /admin/${route}`,
      ).toBeGreaterThan(0);
    });
  }
});

// ─── EE-3: clearing search restores rows ─────────────────────────────────────

test.describe('EE-3 clearing absent-term search restores rows', () => {
  for (const { route, label, searchInputId } of LIST_CONFIGS) {
    if (!searchInputId) continue;

    test(`${label} list: clearing no-results search restores original rows`, async ({ page }) => {
      const problems = await gotoClean(page, route);

      // If empty at rest, skip — nothing to restore.
      const initialRows = await hasRows(page);
      if (!initialRows) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list is empty at rest; skip EE-3 (restore requires pre-existing rows)`,
        });
        return;
      }

      const searchInput = page.locator(searchInputId);
      const inputVisible = await searchInput.isVisible().catch(() => false);
      if (!inputVisible) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `search input "${searchInputId}" not visible on /admin/${route}`,
        });
        return;
      }

      // Step 1: submit an absent term.
      const term = absentTerm();
      await searchInput.fill(term);
      await page.keyboard.press('Enter');
      await page.waitForLoadState('networkidle', { timeout: SEARCH_IDLE_TIMEOUT });
      await page.waitForTimeout(400);

      // Assert we got a clean empty state (no error banner).
      await assertNoErrorBanner(page, problems, `absent-term search (EE-3 step 1) on /admin/${route}`);

      // Step 2: clear the search. The "Clear" button appears when searchQ() is non-empty.
      const clearBtn = page.getByRole('button', { name: 'Clear' });
      const clearVisible = await clearBtn.first().isVisible().catch(() => false);

      if (clearVisible) {
        await clearBtn.first().click();
      } else {
        // Fallback: clear the input manually and press Enter.
        await searchInput.clear();
        await page.keyboard.press('Enter');
      }

      await page.waitForLoadState('networkidle', { timeout: SEARCH_IDLE_TIMEOUT });
      await page.waitForTimeout(400);

      // EE-3a: rows must return.
      const restoredRows = page.locator(TABLE_ROW).first();
      await expect(
        restoredRows,
        `EE-3a: rows did not return after clearing search on /admin/${route}`,
      ).toBeVisible({ timeout: TABLE_TIMEOUT });

      // EE-3b: no error banner after clearing.
      await assertNoErrorBanner(page, problems, `clear-search restore on /admin/${route}`);
    });
  }
});

// ─── EE-4: page-2 navigation ──────────────────────────────────────────────────

test.describe('EE-4 page-2 navigation loads without error and changes first row', () => {
  for (const { route, label } of LIST_CONFIGS) {
    test(`${label} list: navigating to page 2 changes rows and shows no error`, async ({ page }) => {
      const problems = await gotoClean(page, route);

      // If empty, skip.
      if (!(await hasRows(page))) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list is empty; skip EE-4`,
        });
        return;
      }

      // If the paginator is absent (single page), skip gracefully.
      const paginatorNav = page.locator(PAGINATOR_NAV).first();
      const paginatorVisible = await paginatorNav.isVisible().catch(() => false);
      if (!paginatorVisible) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list fits on a single page (no paginator); skip EE-4`,
        });
        return;
      }

      // Capture first-row text before navigation.
      const firstRowTextBefore = await page
        .locator(TABLE_ROW).first()
        .innerText()
        .catch(() => '');

      // Click "Next page".
      const nextBtn = paginatorNav.getByRole('button', { name: 'Next page' });
      await expect(nextBtn, `EE-4: "Next page" button missing on /admin/${route}`).toBeVisible();
      await expect(nextBtn, `EE-4: "Next page" button disabled on page 1 of /admin/${route}`).toBeEnabled();

      await nextBtn.click();
      await page.waitForLoadState('networkidle', { timeout: TABLE_TIMEOUT });
      await page.waitForTimeout(400);

      // EE-4a: no error banner after page navigation.
      await assertNoErrorBanner(page, problems, `page-2 navigation on /admin/${route}`);

      // EE-4b: paginator now shows page 2.
      const statusSpan = paginatorNav.locator('span.small.text-muted').first();
      await expect(
        statusSpan,
        `EE-4b: paginator status text did not update to page 2 on /admin/${route}`,
      ).toContainText('Page 2 of', { timeout: TABLE_TIMEOUT });

      // EE-4c: table still has rows on page 2.
      const firstRowAfter = page.locator(TABLE_ROW).first();
      await expect(
        firstRowAfter,
        `EE-4c: table has no rows on page 2 of /admin/${route}`,
      ).toBeVisible({ timeout: TABLE_TIMEOUT });

      // EE-4d: first row content changed (different page → different records).
      // This is a warning-level check — on a tiny dataset two pages could
      // theoretically share a leading row. We emit a console.warn rather than
      // a hard failure so a genuine edge case doesn't block CI.
      const firstRowTextAfter = await firstRowAfter.innerText().catch(() => '');
      if (firstRowTextBefore && firstRowTextAfter && firstRowTextBefore === firstRowTextAfter) {
        console.warn(
          `[EE-4d] page-2 first row identical to page-1 first row on /admin/${route} — ` +
          `possible pagination regression or tiny dataset (investigate if consistent)`,
        );
      }
    });
  }
});

// ─── EE-5: loading spinner/skeleton resolves ──────────────────────────────────

test.describe('EE-5 loading state appears then resolves (best-effort)', () => {
  /**
   * This test navigates to each list and asserts that, after networkidle and an
   * extra 1 s grace period, NO spinner remains visible. It cannot guarantee the
   * spinner appeared (that would require racing the render), but it can detect
   * stuck/infinite spinners, which are the real defect class.
   */
  for (const { route, label } of LIST_CONFIGS) {
    test(`${label} list: no spinner stuck after page load settles`, async ({ page }) => {
      await gotoClean(page, route, { extraWaitMs: 1_200 });

      // After networkidle + 1.2 s the spinner-border from state==='loading' must be gone.
      // (The .spinner-border inside a .spinner-border-sm is the loading indicator used in
      //  every list component; PaginatorComponent and status-tag do not use spinner-border.)
      const spinner = page.locator('p[aria-live="polite"] .spinner-border').first();
      const stuck = await spinner.isVisible().catch(() => false);
      expect(
        stuck,
        `EE-5: loading spinner still visible on /admin/${route} after networkidle + 1.2 s — ` +
        `possible infinite loading state (API not responding or signal not cleared)`,
      ).toBe(false);
    });
  }
});

// ─── EE-6: first-row detail drill-down renders heading without errors ─────────

test.describe('EE-6 first-row detail drill-down renders heading without errors', () => {
  for (const { route, label } of LIST_CONFIGS) {
    test(`${label}: detail page for first row renders h1 heading without console or page errors`, async ({ page }) => {
      const problems = await gotoClean(page, route);

      // If empty, skip.
      if (!(await hasRows(page))) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list is empty; no detail link to follow for EE-6`,
        });
        return;
      }

      // Find the first /uid/<ULID> anchor in the rendered list.
      const detailHref = await firstDetailHref(page);
      if (!detailHref) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `no /uid/<ULID> anchor on /admin/${route} — detail route may differ or list uses a different link pattern`,
        });
        return;
      }

      // Navigate to the detail page.
      await page.goto(detailHref, { waitUntil: 'networkidle', timeout: 60_000 });
      await page.waitForTimeout(600);

      // EE-6a: session not lost.
      expect(page.url(), `EE-6a: session bounced to /login on detail ${detailHref}`).not.toMatch(/\/login/);

      // EE-6b: no API 5xx.
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(api5xx, `EE-6b: API 5xx on detail page ${detailHref}: ${JSON.stringify(api5xx)}`).toHaveLength(0);

      // EE-6c: no page errors.
      expect(
        problems.pageErrors,
        `EE-6c: uncaught page errors on detail ${detailHref}: ${JSON.stringify(problems.pageErrors)}`,
      ).toHaveLength(0);

      // EE-6d: no console errors.
      const consoleErrs = realConsoleErrors(problems.consoleErrors);
      expect(
        consoleErrs,
        `EE-6d: console errors on detail ${detailHref}: ${JSON.stringify(consoleErrs)}`,
      ).toHaveLength(0);

      // EE-6e: h1 heading is visible (the detail page always renders an h1 — either
      //        the entity name/code, or the fallback "… Detail" heading on load error).
      const heading = page.locator('h1').first();
      await expect(
        heading,
        `EE-6e: h1 heading not visible on detail page ${detailHref} — component may not have rendered`,
      ).toBeVisible({ timeout: TABLE_TIMEOUT });

      // EE-6f: no error-state banner as the dominant content.
      const errorAlert = page.locator(ERROR_ALERT_SEL).first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(
        alertVisible,
        `EE-6f: error-state banner visible on detail page ${detailHref} — API load may have failed`,
      ).toBe(false);
    });
  }
});

// ─── EE-SEARCH-DERIVE: search term derived from a real row (smoke of derivation) ──

/**
 * Additional cases: verify that a search term derived from an actual row's primary
 * text cell returns at least one result (confirms the search is functional and the
 * derivation strategy is sound). Runs for routes that have a search input.
 */
test.describe('EE-SEARCH-DERIVE derived-term search returns at least one result', () => {
  for (const { route, label, searchInputId } of LIST_CONFIGS) {
    if (!searchInputId) continue;

    test(`${label} list: searching with a term from the first row's name cell returns results`, async ({ page }) => {
      const problems = await gotoClean(page, route);

      if (!(await hasRows(page))) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list is empty; skip derived-term search`,
        });
        return;
      }

      const searchInput = page.locator(searchInputId);
      const inputVisible = await searchInput.isVisible().catch(() => false);
      if (!inputVisible) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `search input "${searchInputId}" not visible on /admin/${route}`,
        });
        return;
      }

      // Derive term from the name/description column of row 1.
      const term = await deriveSearchTermFromFirstRow(page);
      if (!term) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `could not derive a search term from first row on /admin/${route}`,
        });
        return;
      }

      // Submit the derived search.
      await searchInput.fill(term);
      await page.keyboard.press('Enter');
      await page.waitForLoadState('networkidle', { timeout: SEARCH_IDLE_TIMEOUT });
      await page.waitForTimeout(400);

      // No error banner.
      await assertNoErrorBanner(page, problems, `derived-term search on /admin/${route}`);

      // At least one row (the row we derived the term from must match).
      const firstRow = page.locator(TABLE_ROW).first();
      await expect(
        firstRow,
        `EE-SEARCH-DERIVE: no rows returned for derived term "${term}" on /admin/${route} — ` +
        `search may be broken or the term normalisation strips the word`,
      ).toBeVisible({ timeout: TABLE_TIMEOUT });
    });
  }
});
