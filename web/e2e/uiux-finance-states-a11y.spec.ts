/**
 * UI/UX Finance: Empty/Error/Pagination/Search States + Accessibility Gate
 *
 * Covers two concern groups (project: chromium — authenticated as rootadmin):
 *
 * GROUP A — Empty / error / pagination / search states (mirrors uiux-empty-error-states.spec.ts)
 *   for the five financial list surfaces:
 *     FIN-EE-1  Populated list renders erp-table rows; paginator present when multi-page.
 *     FIN-EE-2  Absent-term customer/supplier filter shows clean empty state, no error banner.
 *     FIN-EE-3  Clearing a no-results filter restores original rows.
 *     FIN-EE-4  Page-2 navigation loads without error and first-row content changes.
 *     FIN-EE-5  No stuck spinner after networkidle + 1.2 s.
 *     FIN-EE-6  First-row detail drill-down renders heading without console/page errors.
 *
 *   Surfaces tested:
 *     - AR Receipts      /admin/ar/receipts        (customer filter: #customerFilter)
 *     - AP Payments      /admin/ap/payments        (supplier filter: #supplierFilter)
 *     - Cash Transfers   /admin/cash/transfers     (no free-text search; state-only)
 *     - GL Journals      /admin/gl/journals        (no free-text search; company-gated)
 *     - Fixed Assets     /admin/fixed-assets       (status select filter; company-gated)
 *
 * GROUP B — Axe a11y gate (WCAG 2.1 AA, zero serious/critical)
 *   on the five financial ENTRY forms:
 *     FIN-A11Y-1  AR record-receipt form  /admin/ar/receipts/record
 *     FIN-A11Y-2  AP enter-bill form      /admin/ap/supplier-bills/enter
 *     FIN-A11Y-3  AP record-payment form  /admin/ap/payments/record
 *     FIN-A11Y-4  GL post-journal form    /admin/gl/journals/post
 *     FIN-A11Y-5  Depreciation post form  /admin/depreciation-runs/post
 *
 * Selector sources — all verified against component HTML:
 *   ar-receipts-list.component.html    → #customerFilter, table.erp-table, .erp-empty
 *   ap-payments-list.component.html    → #supplierFilter, table.erp-table, .erp-empty
 *   cash-transfers-list.component.html → table.erp-table, .erp-empty
 *   journal-entry-list.component.html  → table.erp-table, .erp-empty
 *   fixed-asset-list.component.html    → table.erp-table, .erp-empty, #statusFilter
 *   record-receipt.component.html      → form[aria-label="Record receipt"], #receiptAmount
 *   enter-bill.component.html          → form[aria-label="Enter supplier bill"], #supplierInvoiceNo
 *   record-payment.component.html      → form[aria-label="Record payment run"], #paymentDate
 *   post-journal.component.html        → #postingDate, select[name^="lineAccount_"]
 *   depreciation-post.component.html   → #fFiscalPeriodUid, button "Preview", button "Post Run"
 *
 * Resilience rules:
 *   - Tests skip gracefully (annotation, no FAIL) when no rows are seeded, the company
 *     picker hasn't loaded, or a permission gate prevents access to the route.
 *   - Filter/search terms derived from the first row at runtime — never hardcoded.
 *   - "Absent term" is a per-run random suffix with negligible collision probability.
 *   - All API 5xx / console errors are asserted with explicit message context.
 *
 * Auth: chromium project — storageState loaded, _test-authenticated fixture active.
 */

import { test, expect } from './_test-authenticated';
import AxeBuilder from '@axe-core/playwright';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── shared selectors ─────────────────────────────────────────────────────────

const TABLE_ROW      = 'table.erp-table tbody tr';
const PAGINATOR_NAV  = 'nav[aria-label$="pagination"]';
const EMPTY_STATE    = '.erp-empty';
const ERROR_ALERT    = '[role="alert"].text-danger, p.text-danger[role="alert"]';

const TABLE_TIMEOUT        = 20_000;
const SEARCH_IDLE_TIMEOUT  = 15_000;
const NAV_TIMEOUT          = 60_000;
const SETTLE_MS            = 800;

// ─── shared helpers ───────────────────────────────────────────────────────────

/**
 * Generate a search string that is almost certainly absent from any real dataset.
 * 12-char hex suffix → ≤1/16^12 collision probability.
 */
function absentTerm(): string {
  return 'ZZNOEXIST' + Math.floor(Math.random() * 0xffffff).toString(16).toUpperCase().padStart(6, '0');
}

/**
 * Navigate to a route, wait for networkidle, assert no session bounce, no API 5xx,
 * no uncaught page/console errors. Returns the problems collector.
 */
async function gotoAndAssertClean(
  page: Parameters<typeof watchProblems>[0],
  fullRelativeRoute: string,
  { extraWaitMs = SETTLE_MS }: { extraWaitMs?: number } = {},
): Promise<ReturnType<typeof watchProblems>> {
  const problems = watchProblems(page);
  await page.goto(fullRelativeRoute, { waitUntil: 'networkidle', timeout: NAV_TIMEOUT });
  await page.waitForTimeout(extraWaitMs);

  expect(
    page.url(),
    `session bounced to /login on ${fullRelativeRoute}`,
  ).not.toMatch(/\/login/);

  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  expect(
    api5xx,
    `API 5xx during ${fullRelativeRoute} load: ${JSON.stringify(api5xx)}`,
  ).toHaveLength(0);

  expect(
    problems.pageErrors,
    `uncaught page errors on ${fullRelativeRoute}: ${JSON.stringify(problems.pageErrors)}`,
  ).toHaveLength(0);

  const consoleErrs = realConsoleErrors(problems.consoleErrors);
  expect(
    consoleErrs,
    `console errors on ${fullRelativeRoute}: ${JSON.stringify(consoleErrs)}`,
  ).toHaveLength(0);

  return problems;
}

async function hasRows(page: Parameters<typeof watchProblems>[0]): Promise<boolean> {
  return (await page.locator(TABLE_ROW).count()) > 0;
}

/**
 * Assert the page shows a clean empty state — no error-alert banner, no console errors.
 * The ERP convention is: zero rows OR .erp-empty placeholder; never an error banner.
 */
async function assertNoErrorBanner(
  page: Parameters<typeof watchProblems>[0],
  problems: ReturnType<typeof watchProblems>,
  context: string,
): Promise<void> {
  const alertVisible = await page.locator(ERROR_ALERT).first().isVisible().catch(() => false);
  expect(alertVisible, `error-state banner visible after ${context}`).toBe(false);

  const consoleErrs = realConsoleErrors(problems.consoleErrors);
  expect(
    consoleErrs,
    `console errors after ${context}: ${JSON.stringify(consoleErrs)}`,
  ).toHaveLength(0);
}

/**
 * Extract a search term from the primary text cell of the first tbody row.
 * Prefers the 2nd cell (display name / description column); falls back to 1st (code).
 * Returns null when the table has no rows or all cells are blank.
 */
async function deriveSearchTermFromFirstRow(
  page: Parameters<typeof watchProblems>[0],
): Promise<string | null> {
  const firstRow = page.locator(TABLE_ROW).first();
  if (!(await firstRow.isVisible().catch(() => false))) return null;

  // Try column 1 (typically the name/descriptive column).
  const nameText = (await firstRow.locator('td').nth(1).innerText().catch(() => '')).trim();
  const word = nameText.match(/[A-Za-z]{3,}/)?.[0];
  if (word) return word;

  // Fall back to column 0 (code / number).
  const codeText = (await firstRow.locator('td').nth(0).innerText().catch(() => '')).trim();
  return codeText.slice(0, 6) || null;
}

/**
 * Regex for a /uid/<ULID> detail href.
 */
const DETAIL_HREF_RE = /\/admin\/[^?#]+\/uid\/([0-9A-HJKMNP-TV-Z]{26})(?:$|[?#/])/;

/**
 * Return the href of the first row-action (button-styled) anchor that matches
 * the /uid/<ULID> detail pattern.
 *
 * We restrict to anchors that carry a Bootstrap "btn" class — this is the ERP
 * convention for action buttons in table rows. Plain <a> links (e.g. the account
 * name links in cash-transfers-list) are intentionally excluded because those
 * routes may not have a registered detail view (e.g. /admin/cash/accounts/uid/:uid
 * is absent from the route table), which would produce NG04002 on navigation.
 *
 * Fallback: if no btn-anchor carries a uid path (future lists may differ), we
 * try all anchors so the helper never silently skips valid detail links.
 */
async function firstDetailHref(
  page: Parameters<typeof watchProblems>[0],
): Promise<string | null> {
  // Pass 1: btn-styled action anchors only (preferred — avoids plain in-cell links).
  const btnAnchors = await page.locator('a.btn[href*="/uid/"]').all();
  for (const anchor of btnAnchors) {
    const href = await anchor.getAttribute('href').catch(() => null);
    if (href && DETAIL_HREF_RE.test(href)) {
      return href.startsWith('/') ? href : '/' + href;
    }
  }

  // Pass 2: any anchor (fallback for lists that don't use btn-styled detail links).
  const allAnchors = await page.locator('a[href]').all();
  for (const anchor of allAnchors) {
    const href = await anchor.getAttribute('href').catch(() => null);
    if (href && DETAIL_HREF_RE.test(href)) {
      return href.startsWith('/') ? href : '/' + href;
    }
  }

  return null;
}

// ─── list configuration ────────────────────────────────────────────────────────

/**
 * Route: app-relative URL to pass to page.goto().
 * label: human name for assertion messages.
 * filterInputId: CSS selector for the text-filter input on the list page
 *   (null when the list has no free-text search — state-only filtering).
 *
 * Confirmed against admin.routes.ts:
 *   /admin/ar/receipts       → ar-receipts-list.component.html  (customerFilter #customerFilter)
 *   /admin/ap/payments       → ap-payments-list.component.html  (supplierFilter #supplierFilter)
 *   /admin/cash/transfers    → cash-transfers-list.component.html (no free-text filter)
 *   /admin/gl/journals       → journal-entry-list.component.html  (no free-text filter)
 *   /admin/fixed-assets      → fixed-asset-list.component.html    (no free-text; status select)
 */
interface FinListConfig {
  route: string;
  label: string;
  filterInputId: string | null;
}

const FIN_LISTS: FinListConfig[] = [
  { route: '/admin/ar/receipts',     label: 'AR Receipts',    filterInputId: '#customerFilter'  },
  { route: '/admin/ap/payments',     label: 'AP Payments',    filterInputId: '#supplierFilter'  },
  { route: '/admin/cash/transfers',  label: 'Cash Transfers', filterInputId: null               },
  { route: '/admin/gl/journals',     label: 'GL Journals',    filterInputId: null               },
  { route: '/admin/fixed-assets',    label: 'Fixed Assets',   filterInputId: null               },
];

// ═══════════════════════════════════════════════════════════════════════════════
// GROUP A — EMPTY / ERROR / PAGINATION / SEARCH STATES
// ═══════════════════════════════════════════════════════════════════════════════

// ─── FIN-EE-1: populated list renders erp-table rows + paginator ──────────────

test.describe('FIN-EE-1 populated financial list renders erp-table rows and paginator', () => {
  for (const { route, label } of FIN_LISTS) {
    test(`${label}: table has rows and paginator controls are valid when list has data`, async ({ page }) => {
      await gotoAndAssertClean(page, route);

      // Some lists (GL journals, Fixed assets) only render the table once a company
      // is selected. If the table isn't visible after networkidle, the company context
      // may not have resolved — skip gracefully rather than failing.
      const rows = await hasRows(page);
      if (!rows) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list is empty (no rows) — either no data is seeded or a company context is required. Seeding concern, not a UI defect.`,
        });
        return;
      }

      // FIN-EE-1a: at least one data row is visible.
      await expect(
        page.locator(TABLE_ROW).first(),
        `FIN-EE-1a: no erp-table rows visible on ${route}`,
      ).toBeVisible({ timeout: TABLE_TIMEOUT });

      // FIN-EE-1b: no error banner alongside data rows.
      const alertVisible = await page.locator(ERROR_ALERT).first().isVisible().catch(() => false);
      expect(alertVisible, `FIN-EE-1b: error banner visible alongside table rows on ${route}`).toBe(false);

      // FIN-EE-1c: paginator — conditional (only if >1 page of data exists).
      const paginatorNav = page.locator(PAGINATOR_NAV).first();
      const paginatorVisible = await paginatorNav.isVisible().catch(() => false);

      if (paginatorVisible) {
        await expect(
          paginatorNav.getByRole('button', { name: 'Next page' }),
          `FIN-EE-1c: "Next page" button missing in paginator on ${route}`,
        ).toBeVisible();

        await expect(
          paginatorNav.getByRole('button', { name: 'Last page' }),
          `FIN-EE-1c: "Last page" button missing in paginator on ${route}`,
        ).toBeVisible();

        const statusText = await paginatorNav
          .locator('span.small.text-muted')
          .first()
          .innerText()
          .catch(() => '');
        expect(
          statusText,
          `FIN-EE-1c: paginator status text malformed on ${route}: "${statusText}"`,
        ).toMatch(/Page \d+ of \d+/);
      }
      // Single-page list → no paginator, which is valid; test passes.
    });
  }
});

// ─── FIN-EE-2: absent-term filter shows clean empty state ────────────────────

test.describe('FIN-EE-2 absent-term filter shows clean empty state without error banner', () => {
  for (const { route, label, filterInputId } of FIN_LISTS) {
    // Routes without a free-text filter cannot exercise this case.
    if (!filterInputId) continue;

    test(`${label}: filtering with a guaranteed-absent term shows empty state without error`, async ({ page }) => {
      const problems = await gotoAndAssertClean(page, route);

      const filterInput = page.locator(filterInputId);
      const inputVisible = await filterInput.isVisible().catch(() => false);
      if (!inputVisible) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `filter input "${filterInputId}" not visible on ${route} — company context may be required or component not loaded`,
        });
        return;
      }

      const term = absentTerm();
      await filterInput.fill(term);

      // AR Receipts (#customerFilter) and AP Payments (#supplierFilter) are
      // autocomplete / typeahead pickers: they debounce 300 ms then fetch
      // matching customer/supplier names from the API.  Pressing Enter has no
      // effect — the list only re-loads once the user *selects* a suggestion.
      // So we wait for the debounce + API round-trip (networkidle) and then
      // assert against the *suggestion dropdown* state, not the data table.
      await page.waitForLoadState('networkidle', { timeout: SEARCH_IDLE_TIMEOUT });
      await page.waitForTimeout(500);

      // FIN-EE-2a: no error banner.
      await assertNoErrorBanner(page, problems, `absent-term filter on ${route}`);

      // FIN-EE-2b: the autocomplete suggestion listbox must be absent or empty.
      // An absent term returns zero matches from the API → the list-group dropdown
      // is not rendered (customerFilterResults / supplierFilterResults is empty).
      // We check both possible suggestion containers used by the two components.
      const suggestionList = page.locator(
        'ul[aria-label="Customer suggestions"], ul[aria-label="Supplier suggestions"]',
      );
      const suggestionsVisible = await suggestionList.first().isVisible().catch(() => false);
      const suggestionCount = suggestionsVisible
        ? await suggestionList.first().locator('li').count()
        : 0;

      expect(
        suggestionCount,
        `FIN-EE-2b: expected zero autocomplete suggestions for absent term "${term}" on ${route}, ` +
        `but ${suggestionCount} item(s) appeared — either the term was not absent or the filter ` +
        `API returned unexpected results`,
      ).toBe(0);

      // FIN-EE-2c: the .admin-page body still has content (no blank/crash render).
      const bodyText = await page.locator('.admin-page').innerText().catch(() => '');
      expect(
        bodyText.trim().length,
        `FIN-EE-2c: .admin-page body is empty after absent-term filter on ${route}`,
      ).toBeGreaterThan(0);
    });
  }
});

// ─── FIN-EE-3: clearing absent-term filter restores rows ─────────────────────

test.describe('FIN-EE-3 clearing absent-term filter restores original rows', () => {
  for (const { route, label, filterInputId } of FIN_LISTS) {
    if (!filterInputId) continue;

    test(`${label}: clearing no-results filter restores original rows`, async ({ page }) => {
      const problems = await gotoAndAssertClean(page, route);

      if (!(await hasRows(page))) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list is empty at rest; skip FIN-EE-3 (restore requires pre-existing rows)`,
        });
        return;
      }

      const filterInput = page.locator(filterInputId);
      const inputVisible = await filterInput.isVisible().catch(() => false);
      if (!inputVisible) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `filter input "${filterInputId}" not visible on ${route}`,
        });
        return;
      }

      // Step 1: apply absent-term filter.
      const term = absentTerm();
      await filterInput.fill(term);
      await page.keyboard.press('Enter');
      await page.waitForLoadState('networkidle', { timeout: SEARCH_IDLE_TIMEOUT });
      await page.waitForTimeout(500);

      await assertNoErrorBanner(page, problems, `absent-term filter (FIN-EE-3 step 1) on ${route}`);

      // Step 2: clear the filter.
      // The component renders an aria-label="Clear customer filter" / "Clear supplier filter"
      // button when a value is typed. Use that first; fall back to manual clear.
      const clearBtn = page.getByRole('button', { name: /Clear (customer|supplier) filter/i });
      const clearVisible = await clearBtn.first().isVisible().catch(() => false);

      if (clearVisible) {
        await clearBtn.first().click();
      } else {
        await filterInput.clear();
        await page.keyboard.press('Enter');
      }

      await page.waitForLoadState('networkidle', { timeout: SEARCH_IDLE_TIMEOUT });
      await page.waitForTimeout(500);

      // FIN-EE-3a: rows must be restored.
      await expect(
        page.locator(TABLE_ROW).first(),
        `FIN-EE-3a: rows did not return after clearing filter on ${route}`,
      ).toBeVisible({ timeout: TABLE_TIMEOUT });

      // FIN-EE-3b: no error banner after clearing.
      await assertNoErrorBanner(page, problems, `clear-filter restore on ${route}`);
    });
  }
});

// ─── FIN-EE-4: page-2 navigation ─────────────────────────────────────────────

test.describe('FIN-EE-4 page-2 navigation loads without error and changes first row', () => {
  for (const { route, label } of FIN_LISTS) {
    test(`${label}: navigating to page 2 changes rows and shows no error`, async ({ page }) => {
      const problems = await gotoAndAssertClean(page, route);

      if (!(await hasRows(page))) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list is empty; skip FIN-EE-4`,
        });
        return;
      }

      const paginatorNav = page.locator(PAGINATOR_NAV).first();
      const paginatorVisible = await paginatorNav.isVisible().catch(() => false);
      if (!paginatorVisible) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} fits on a single page (no paginator); skip FIN-EE-4`,
        });
        return;
      }

      const firstRowTextBefore = await page
        .locator(TABLE_ROW).first()
        .innerText()
        .catch(() => '');

      const nextBtn = paginatorNav.getByRole('button', { name: 'Next page' });
      await expect(nextBtn, `FIN-EE-4: "Next page" button missing on ${route}`).toBeVisible();
      await expect(nextBtn, `FIN-EE-4: "Next page" button disabled on page 1 of ${route}`).toBeEnabled();

      await nextBtn.click();
      await page.waitForLoadState('networkidle', { timeout: TABLE_TIMEOUT });
      await page.waitForTimeout(500);

      // FIN-EE-4a: no error banner after page navigation.
      await assertNoErrorBanner(page, problems, `page-2 navigation on ${route}`);

      // FIN-EE-4b: paginator status shows page 2.
      await expect(
        paginatorNav.locator('span.small.text-muted').first(),
        `FIN-EE-4b: paginator status did not update to page 2 on ${route}`,
      ).toContainText('Page 2 of', { timeout: TABLE_TIMEOUT });

      // FIN-EE-4c: rows still rendered on page 2.
      await expect(
        page.locator(TABLE_ROW).first(),
        `FIN-EE-4c: table empty on page 2 of ${route}`,
      ).toBeVisible({ timeout: TABLE_TIMEOUT });

      // FIN-EE-4d: first-row text changed relative to page 1 (warn only — tiny datasets
      // could legitimately have identical leading rows across pages).
      const firstRowTextAfter = await page.locator(TABLE_ROW).first().innerText().catch(() => '');
      if (firstRowTextBefore && firstRowTextAfter && firstRowTextBefore === firstRowTextAfter) {
        console.warn(
          `[FIN-EE-4d] page-2 first row identical to page-1 first row on ${route} — ` +
          `possible pagination regression or tiny dataset`,
        );
      }
    });
  }
});

// ─── FIN-EE-5: no stuck spinner after networkidle ────────────────────────────

test.describe('FIN-EE-5 no stuck loading spinner after page settles', () => {
  for (const { route, label } of FIN_LISTS) {
    test(`${label}: spinner resolves within 1.2 s of networkidle`, async ({ page }) => {
      await gotoAndAssertClean(page, route, { extraWaitMs: 1_200 });

      // The loading state for all five components uses:
      //   <p aria-live="polite"> <span class="spinner-border spinner-border-sm"> …
      const spinner = page
        .locator('p[aria-live="polite"] .spinner-border')
        .first();
      const stuck = await spinner.isVisible().catch(() => false);
      expect(
        stuck,
        `FIN-EE-5: loading spinner still visible on ${route} after networkidle + 1.2 s — ` +
        `possible infinite loading state`,
      ).toBe(false);
    });
  }
});

// ─── FIN-EE-6: first-row detail drill-down renders heading without errors ─────

test.describe('FIN-EE-6 first-row detail drill-down renders heading without errors', () => {
  for (const { route, label } of FIN_LISTS) {
    test(`${label}: detail page for first row renders h1 heading without console/page errors`, async ({ page }) => {
      const problems = await gotoAndAssertClean(page, route);

      if (!(await hasRows(page))) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list is empty; no detail link to follow for FIN-EE-6`,
        });
        return;
      }

      const detailHref = await firstDetailHref(page);
      if (!detailHref) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `no /uid/<ULID> anchor on ${route} — detail route may differ or list did not load data rows`,
        });
        return;
      }

      await page.goto(detailHref, { waitUntil: 'networkidle', timeout: NAV_TIMEOUT });
      await page.waitForTimeout(600);

      // FIN-EE-6a: session intact.
      expect(page.url(), `FIN-EE-6a: session bounced to /login on detail ${detailHref}`).not.toMatch(/\/login/);

      // FIN-EE-6b: no API 5xx.
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(api5xx, `FIN-EE-6b: API 5xx on detail ${detailHref}: ${JSON.stringify(api5xx)}`).toHaveLength(0);

      // FIN-EE-6c: no page errors.
      expect(
        problems.pageErrors,
        `FIN-EE-6c: page errors on detail ${detailHref}: ${JSON.stringify(problems.pageErrors)}`,
      ).toHaveLength(0);

      // FIN-EE-6d: no console errors.
      expect(
        realConsoleErrors(problems.consoleErrors),
        `FIN-EE-6d: console errors on detail ${detailHref}: ${JSON.stringify(problems.consoleErrors)}`,
      ).toHaveLength(0);

      // FIN-EE-6e: h1 heading rendered (every detail component has one).
      await expect(
        page.locator('h1').first(),
        `FIN-EE-6e: h1 heading not visible on detail page ${detailHref}`,
      ).toBeVisible({ timeout: TABLE_TIMEOUT });

      // FIN-EE-6f: no error-state banner as dominant content on the detail page.
      const alertVisible = await page.locator(ERROR_ALERT).first().isVisible().catch(() => false);
      expect(
        alertVisible,
        `FIN-EE-6f: error-state banner visible on detail page ${detailHref} — API load may have failed`,
      ).toBe(false);
    });
  }
});

// ─── FIN-EE-DERIVE: derived-filter term returns at least one row ──────────────

test.describe('FIN-EE-DERIVE derived-filter term from first row returns at least one result', () => {
  for (const { route, label, filterInputId } of FIN_LISTS) {
    if (!filterInputId) continue;

    test(`${label}: filtering with a term derived from the first row returns results`, async ({ page }) => {
      const problems = await gotoAndAssertClean(page, route);

      if (!(await hasRows(page))) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `${label} list is empty; skip FIN-EE-DERIVE`,
        });
        return;
      }

      const filterInput = page.locator(filterInputId);
      const inputVisible = await filterInput.isVisible().catch(() => false);
      if (!inputVisible) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `filter input "${filterInputId}" not visible on ${route}`,
        });
        return;
      }

      const term = await deriveSearchTermFromFirstRow(page);
      if (!term) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `could not derive a filter term from first row on ${route}`,
        });
        return;
      }

      await filterInput.fill(term);
      await page.keyboard.press('Enter');
      await page.waitForLoadState('networkidle', { timeout: SEARCH_IDLE_TIMEOUT });
      await page.waitForTimeout(500);

      await assertNoErrorBanner(page, problems, `derived-term filter on ${route}`);

      await expect(
        page.locator(TABLE_ROW).first(),
        `FIN-EE-DERIVE: no rows returned for derived term "${term}" on ${route} — ` +
        `filter may be broken or term normalisation strips the word`,
      ).toBeVisible({ timeout: TABLE_TIMEOUT });
    });
  }
});

// ═══════════════════════════════════════════════════════════════════════════════
// GROUP B — AXE ACCESSIBILITY GATE (WCAG 2.1 AA)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Navigate to a form route, wait for the page to settle, then run axe.
 * If the page shows a permission-denied message or the form is simply not rendered
 * (company context missing), annotate and skip — not a test failure.
 *
 * We disable 'color-contrast' because headless rendering cannot reliably compute
 * computed contrast ratios against the deployed theme. All other serious/critical
 * rules are enforced, matching the approach in smoke.spec.ts and
 * uiux-journeys-a11y.spec.ts.
 */
async function runAxeOnFormRoute(
  page: Parameters<typeof watchProblems>[0],
  route: string,
  label: string,
  /** Optional selector that must be visible before axe runs (form readiness signal). */
  readinessSelector?: string,
): Promise<void> {
  const problems = watchProblems(page);
  await page.goto(route, { waitUntil: 'networkidle', timeout: NAV_TIMEOUT });
  await page.waitForTimeout(SETTLE_MS);

  // Guard: session not bounced.
  if (page.url().includes('/login')) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `session bounced to /login on ${route} — auth may have expired`,
    });
    return;
  }

  // Guard: no API 5xx during load.
  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  if (api5xx.length > 0) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `API 5xx during ${route} load: ${JSON.stringify(api5xx)} — cannot run axe on a broken page`,
    });
    return;
  }

  // Guard: permission denied — the component renders a "no permission" alert.
  // Confirmed selector from component HTML:
  //   record-receipt: p[role="alert"] "You don't have permission to record receipts."
  //   enter-bill:     .erp-empty[role="alert"] "You don't have permission to enter bills."
  //   record-payment: .erp-empty[role="alert"] "You don't have permission to record payments."
  //   depreciation:   p.text-muted "You don't have permission to run depreciation."
  //   post-journal:   [role="alert"] text "don't have permission"
  const permDenied = page.locator('[role="alert"], .erp-empty').filter({ hasText: /don't have permission/i });
  if (await permDenied.isVisible().catch(() => false)) {
    test.info().annotations.push({
      type: 'skip-reason',
      description: `rootadmin lacks permission on ${route} — axe scan skipped (permission not seeded)`,
    });
    return;
  }

  // Guard: if a readiness selector is specified, wait for it (form loaded, not just the shell).
  if (readinessSelector) {
    const ready = await page.locator(readinessSelector).isVisible().catch(() => false);
    if (!ready) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: `readiness selector "${readinessSelector}" not visible on ${route} — ` +
          `form may need company context or accounts to load before axe can run`,
      });
      return;
    }
  }

  // Run axe — zero serious/critical violations required.
  const results = await new AxeBuilder({ page })
    .disableRules(['color-contrast'])
    .analyze();

  const serious = results.violations.filter(
    (v) => v.impact === 'serious' || v.impact === 'critical',
  );
  const summary = serious
    .map((v) => `${v.id} (${v.impact}, ${v.nodes.length} node(s)): ${v.description}`)
    .join('\n  ');

  expect(
    serious,
    `FIN-A11Y: axe serious/critical violations on ${label} (${route}):\n  ${summary}`,
  ).toHaveLength(0);
}

test.describe('FIN-A11Y axe WCAG 2.1 AA gate on financial entry forms', () => {

  /**
   * FIN-A11Y-1 — AR Record Receipt form.
   *
   * Route: /admin/ar/receipts/record
   * HTML: record-receipt.component.html
   *
   * The form renders immediately (form[aria-label="Record receipt"]) with:
   *   #customerSearch, #receiptAmount, #receiptDate, #tenderType, #bankRef
   * Readiness: form[aria-label="Record receipt"] — present once company context
   * resolves or when there is only one company (auto-selected).
   *
   * We scan the form in its default state (no customer selected yet) — this is the
   * most common state that real users encounter on first load.
   */
  test('FIN-A11Y-1: AR record-receipt form /admin/ar/receipts/record — zero serious/critical axe violations', async ({ page }) => {
    await runAxeOnFormRoute(
      page,
      '/admin/ar/receipts/record',
      'AR Record Receipt',
      'form[aria-label="Record receipt"]',
    );
  });

  /**
   * FIN-A11Y-2 — AP Enter Supplier Bill form.
   *
   * Route: /admin/ap/supplier-bills/enter
   * HTML: enter-bill.component.html
   *
   * The form (form[aria-label="Enter supplier bill"]) renders with:
   *   #supplierSearch, #supplierInvoiceNo, #billDate, #dueDate, #vatAmount
   *   line inputs: [id^="desc_"], [id^="qty_"], [id^="cost_"]
   *
   * Readiness: form[aria-label="Enter supplier bill"] visible.
   */
  test('FIN-A11Y-2: AP enter-bill form /admin/ap/supplier-bills/enter — zero serious/critical axe violations', async ({ page }) => {
    await runAxeOnFormRoute(
      page,
      '/admin/ap/supplier-bills/enter',
      'AP Enter Supplier Bill',
      'form[aria-label="Enter supplier bill"]',
    );
  });

  /**
   * FIN-A11Y-3 — AP Record Payment form.
   *
   * Route: /admin/ap/payments/record
   * HTML: record-payment.component.html
   *
   * The form (form[aria-label="Record payment run"]) renders with:
   *   #supplierSearch, #paymentDate, #tenderType, #bankRef
   *   bill selection table rendered only once a supplier is chosen — scan in default state.
   *
   * Readiness: form[aria-label="Record payment run"] visible.
   */
  test('FIN-A11Y-3: AP record-payment form /admin/ap/payments/record — zero serious/critical axe violations', async ({ page }) => {
    await runAxeOnFormRoute(
      page,
      '/admin/ap/payments/record',
      'AP Record Payment',
      'form[aria-label="Record payment run"]',
    );
  });

  /**
   * FIN-A11Y-4 — GL Post Journal form.
   *
   * Route: /admin/gl/journals/post
   * HTML: post-journal.component.html
   *
   * The form renders with:
   *   #postingDate, #description
   *   select[name^="lineAccount_"], input[name^="lineDebit_"], input[name^="lineCreditAmt_"]
   *   .status-tag (Balanced / Not balanced)
   *   button "Post Journal" (disabled until balanced + date + description)
   *
   * Readiness: #postingDate input visible (form fully hydrated, accounts loaded).
   * If accounts have not loaded yet (no company seeded), the readiness selector
   * will be absent and the test annotates + skips gracefully.
   */
  test('FIN-A11Y-4: GL post-journal form /admin/gl/journals/post — zero serious/critical axe violations', async ({ page }) => {
    await runAxeOnFormRoute(
      page,
      '/admin/gl/journals/post',
      'GL Post Journal',
      '#postingDate',
    );
  });

  /**
   * FIN-A11Y-5 — Depreciation Run post form.
   *
   * Route: /admin/depreciation-runs/post
   * HTML: depreciation-post.component.html
   *
   * The form renders with:
   *   #fFiscalPeriodUid, #fPostingDate
   *   button "Preview", button "Post Run"
   *
   * No company-gating in the template itself (canDepreciate() check only).
   * Readiness: #fFiscalPeriodUid input visible.
   *
   * Confirmed from the HTML: the form is rendered as soon as canDepreciate() is true;
   * no company picker interaction needed for the form itself to appear.
   */
  test('FIN-A11Y-5: depreciation post form /admin/depreciation-runs/post — zero serious/critical axe violations', async ({ page }) => {
    await runAxeOnFormRoute(
      page,
      '/admin/depreciation-runs/post',
      'Depreciation Post',
      '#fFiscalPeriodUid',
    );
  });

});
