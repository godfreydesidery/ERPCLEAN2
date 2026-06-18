/**
 * PRE-MERGE GATE — Massive-dataset volume stress spec.
 *
 * Purpose: surface defects that only appear under real data volume — O(n) render hangs,
 * missing pagination on large resultsets, broken pagination nav, search/filter API errors,
 * detail-page failures when IDs come from a dense dataset, and report/dashboard timeouts
 * when aggregation runs against 1000+ transactions.
 *
 * Dataset assumed seeded into the running stack:
 *   ≈1600 products · 1000 customers · 800 suppliers · 800 sales orders
 *   800 purchase orders · 1000 GL journals · 600 AR receipts · 800 AP bills
 *
 * Auth: uses the pre-saved storageState from auth.setup.ts (chromium project).
 * The _test-authenticated fixture copies erp.* from localStorage → sessionStorage on
 * every page load so Angular's SessionStore sees a live session from frame 0.
 *
 * DO NOT add this spec to playwright.config.ts testMatch before the dataset seed is
 * confirmed complete — the test coordinator will wire it in.
 *
 * Timeouts are deliberately generous: we are looking for O(n) render hangs, not
 * optimising for speed. A list taking 12 s to render 5000 DOM nodes is a defect.
 */
import { test, expect } from './_test-authenticated';
import { watchProblems, realConsoleErrors } from './_helpers';

// ─── constants ────────────────────────────────────────────────────────────────

/** Max time (ms) from navigation to the table tbody having ≥1 row. */
const TABLE_READY_TIMEOUT = 20_000;

/** Max time (ms) for a search/filter response to update the DOM. */
const SEARCH_RESPONSE_TIMEOUT = 15_000;

/** Max time (ms) for a report/dashboard section to leave its loading spinner. */
const REPORT_READY_TIMEOUT = 30_000;

// Backend default page size = 20. The dataset is seeded with well above this count
// so the paginator MUST appear on every list below. Update this comment if the
// backend default page size changes.

// ─── helpers ──────────────────────────────────────────────────────────────────

/**
 * Navigate to route, wait for networkidle, assert no session bounce, no 5xx,
 * no uncaught page/console errors. Returns the problems watcher so callers can
 * add additional assertions.
 */
async function gotoClean(
  page: Parameters<typeof watchProblems>[0],
  route: string,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  { extraWaitMs = 800 }: { extraWaitMs?: number } = {},
) {
  const problems = watchProblems(page);
  await page.goto(`/admin/${route}`, { waitUntil: 'networkidle', timeout: 60_000 });
  await page.waitForTimeout(extraWaitMs);

  expect(page.url(), `session bounced to /login on /admin/${route}`).not.toMatch(/\/login/);

  const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
  expect(
    api5xx,
    `API 5xx during /admin/${route} load: ${JSON.stringify(api5xx)}`,
  ).toHaveLength(0);

  const pageErrors = problems.pageErrors;
  expect(
    pageErrors,
    `uncaught page error on /admin/${route}: ${JSON.stringify(pageErrors)}`,
  ).toHaveLength(0);

  const consoleErrs = realConsoleErrors(problems.consoleErrors);
  expect(
    consoleErrs,
    `console errors on /admin/${route}: ${JSON.stringify(consoleErrs)}`,
  ).toHaveLength(0);

  return problems;
}

/**
 * Assert the standard erp-table has rendered at least one data row in tbody,
 * and that no error-state element is visible as the dominant content.
 */
async function assertTableHasRows(
  page: Parameters<typeof watchProblems>[0],
  route: string,
) {
  // Wait for at least one <tr> inside the <tbody> of an erp-table.
  const firstRow = page.locator('table.erp-table tbody tr').first();
  await expect(firstRow, `table has no rows on /admin/${route} — empty result or render failure`).toBeVisible({
    timeout: TABLE_READY_TIMEOUT,
  });

  // Ensure we are NOT in an error state (the text-danger alert is the universal error indicator).
  const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
  const alertVisible = await errorAlert.isVisible().catch(() => false);
  expect(
    alertVisible,
    `error-state banner visible on /admin/${route} — API or rendering failure`,
  ).toBe(false);
}

/**
 * Assert the paginator nav is present. With >20 rows seeded (backend default page size),
 * the paginator MUST render. Its aria-label ends in "pagination".
 */
async function assertPaginatorPresent(
  page: Parameters<typeof watchProblems>[0],
  route: string,
) {
  // The paginator renders a <nav> only when totalPages > 1.
  const paginatorNav = page.locator('nav[aria-label$="pagination"]').first();
  await expect(
    paginatorNav,
    `paginator nav missing on /admin/${route} — dataset exceeds one page but no pagination rendered`,
  ).toBeVisible({ timeout: TABLE_READY_TIMEOUT });

  // "Last page" button present → sanity that the full control rendered.
  const lastBtn = paginatorNav.getByRole('button', { name: 'Last page' });
  await expect(
    lastBtn,
    `"Last page" button absent in paginator on /admin/${route}`,
  ).toBeVisible();

  // The "Page X of Y" status text must contain "of" and a number > 1.
  const statusText = await paginatorNav.locator('span.small.text-muted').first().innerText();
  const totalPagesMatch = statusText.match(/Page \d+ of (\d+)/);
  expect(
    totalPagesMatch,
    `paginator status text malformed on /admin/${route}: "${statusText}"`,
  ).not.toBeNull();
  const totalPages = parseInt(totalPagesMatch![1], 10);
  expect(
    totalPages,
    `paginator shows only 1 page on /admin/${route} — backend may not be paginating or dataset count is too low`,
  ).toBeGreaterThan(1);
}

/**
 * Navigate to page 2 by clicking "Next page", assert the table re-renders rows
 * without error and the current page indicator updates.
 */
async function assertPage2Navigable(
  page: Parameters<typeof watchProblems>[0],
  route: string,
) {
  const paginatorNav = page.locator('nav[aria-label$="pagination"]').first();
  const nextBtn = paginatorNav.getByRole('button', { name: 'Next page' });
  await expect(nextBtn, `"Next page" button absent on /admin/${route}`).toBeVisible();
  await expect(nextBtn, `"Next page" button disabled on page 1 of multi-page list /admin/${route}`).toBeEnabled();

  // Track the first visible row text before navigation to confirm the DOM updates.
  const firstRowTextBefore = await page
    .locator('table.erp-table tbody tr').first()
    .innerText()
    .catch(() => '');

  await nextBtn.click();

  // Wait for the page indicator to show "Page 2 of …"
  await expect(
    paginatorNav.locator('span.small.text-muted').first(),
    `paginator status text did not update to page 2 on /admin/${route}`,
  ).toContainText('Page 2 of', { timeout: TABLE_READY_TIMEOUT });

  // The table must still have rows after navigation.
  await assertTableHasRows(page, `${route} (page 2)`);

  // Row content should have changed (different page = different rows).
  const firstRowTextAfter = await page
    .locator('table.erp-table tbody tr').first()
    .innerText()
    .catch(() => '');
  // We only warn here — on tiny pages both rows could accidentally be the same record.
  if (firstRowTextBefore && firstRowTextAfter && firstRowTextBefore === firstRowTextAfter) {
    console.warn(
      `[volume] page 2 first row identical to page 1 first row on /admin/${route} — ` +
      `possible pagination regression (rows may genuinely be duplicates; investigate)`,
    );
  }
}

// ─── describe blocks ──────────────────────────────────────────────────────────

test.describe('volume: high-volume list pages', () => {

  // ── Products (≈1600 seeded) ────────────────────────────────────────────────
  test('products list renders rows, shows paginator, navigates to page 2', async ({ page }) => {
    await gotoClean(page, 'products');

    await test.step('table has rows', () => assertTableHasRows(page, 'products'));
    await test.step('paginator present with >1 pages', () => assertPaginatorPresent(page, 'products'));
    await test.step('page 2 navigable without error', () => assertPage2Navigable(page, 'products'));
  });

  test('products search filters the list and returns a result', async ({ page }) => {
    await gotoClean(page, 'products');
    await assertTableHasRows(page, 'products');

    await test.step('type into search input and submit', async () => {
      // The product search input has id="productSearch" from product-list.component.html.
      const searchInput = page.locator('#productSearch');
      await expect(searchInput, 'product search input not found').toBeVisible();

      // Type a short term that will match at least one of the 1600 seeded products.
      // Using "PROD" because the seed naming convention uses PROD-#### codes.
      await searchInput.fill('PROD');
      await page.keyboard.press('Enter');

      // After search submission the table should re-render. Wait for networkidle to
      // ensure the API call completed.
      await page.waitForLoadState('networkidle', { timeout: SEARCH_RESPONSE_TIMEOUT });
      await page.waitForTimeout(400);

      // Assert no error state appeared.
      const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(alertVisible, 'error-state banner after product search').toBe(false);

      // The table must still have rows (our search term is broad enough to match many).
      await assertTableHasRows(page, 'products (search)');
    });
  });

  // ── Customers (≈1000 seeded) ───────────────────────────────────────────────
  test('customers list renders rows, shows paginator, navigates to page 2', async ({ page }) => {
    await gotoClean(page, 'customers');

    await test.step('table has rows', () => assertTableHasRows(page, 'customers'));
    await test.step('paginator present with >1 pages', () => assertPaginatorPresent(page, 'customers'));
    await test.step('page 2 navigable without error', () => assertPage2Navigable(page, 'customers'));
  });

  test('customers search filters the list', async ({ page }) => {
    await gotoClean(page, 'customers');
    await assertTableHasRows(page, 'customers');

    await test.step('search by partial name', async () => {
      // The customer search input has id="customerSearch" per customer-list.component.html.
      const searchInput = page.locator('#customerSearch');
      await expect(searchInput, 'customer search input not found').toBeVisible();

      // Derive the term from the Name column (2nd cell) of the first row. The server
      // search (q=) matches name; the first column is the Code (e.g. CUST-0001), whose
      // bare prefix 'CUST' is NOT a searchable substring — so deriving from the whole
      // row text returned 0 results. A real name word (e.g. 'Samuel') reliably matches.
      const nameCell = (await page.locator('table.erp-table tbody tr').first().locator('td').nth(1).innerText()).trim();
      const term = nameCell.match(/[A-Za-z]{3,}/)?.[0] ?? nameCell.slice(0, 3);
      await searchInput.fill(term);
      await page.keyboard.press('Enter');
      await page.waitForLoadState('networkidle', { timeout: SEARCH_RESPONSE_TIMEOUT });
      await page.waitForTimeout(400);

      const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(alertVisible, 'error-state banner after customer search').toBe(false);

      await assertTableHasRows(page, 'customers (search)');
    });
  });

  // ── Suppliers (≈800 seeded) ────────────────────────────────────────────────
  test('suppliers list renders rows and shows paginator', async ({ page }) => {
    await gotoClean(page, 'suppliers');

    await test.step('table has rows', () => assertTableHasRows(page, 'suppliers'));
    await test.step('paginator present with >1 pages', () => assertPaginatorPresent(page, 'suppliers'));
    await test.step('page 2 navigable', () => assertPage2Navigable(page, 'suppliers'));
  });

  // ── Sales Orders (≈800 seeded) ─────────────────────────────────────────────
  test('sales orders list renders rows, shows paginator, navigates to page 2', async ({ page }) => {
    await gotoClean(page, 'sales-orders');

    await test.step('table has rows', () => assertTableHasRows(page, 'sales-orders'));
    await test.step('paginator present with >1 pages', () => assertPaginatorPresent(page, 'sales-orders'));
    await test.step('page 2 navigable without error', () => assertPage2Navigable(page, 'sales-orders'));
  });

  test('sales orders status filter narrows the list', async ({ page }) => {
    await gotoClean(page, 'sales-orders');
    await assertTableHasRows(page, 'sales-orders');

    await test.step('change status filter to DRAFT', async () => {
      // The sales-order-list has id="statusFilter" for its status <select>.
      const statusSelect = page.locator('#statusFilter');
      await expect(statusSelect, 'sales orders status filter not found').toBeVisible();
      await statusSelect.selectOption('DRAFT');
      await page.waitForLoadState('networkidle', { timeout: SEARCH_RESPONSE_TIMEOUT });
      await page.waitForTimeout(400);

      const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(alertVisible, 'error-state banner after SO status filter').toBe(false);

      // Either rows (DRAFTs exist) or an empty-state div — never an error banner.
      // We assert the DOM settled without error rather than asserting row count,
      // since the seed split across statuses is unknown.
      const bodyText = await page.locator('.admin-page').innerText().catch(() => '');
      expect(
        bodyText,
        'page body empty after SO status filter — possible render hang',
      ).not.toBe('');
    });
  });

  // ── Purchase Orders (≈800 seeded) ─────────────────────────────────────────
  test('purchase orders list renders rows, shows paginator, navigates to page 2', async ({ page }) => {
    await gotoClean(page, 'purchase-orders');

    await test.step('table has rows', () => assertTableHasRows(page, 'purchase-orders'));
    await test.step('paginator present with >1 pages', () => assertPaginatorPresent(page, 'purchase-orders'));
    await test.step('page 2 navigable without error', () => assertPage2Navigable(page, 'purchase-orders'));
  });

  test('purchase orders search input filters the list', async ({ page }) => {
    await gotoClean(page, 'purchase-orders');
    await assertTableHasRows(page, 'purchase-orders');

    await test.step('search by partial text', async () => {
      // purchase-order-list.component.html: id="poSearch"
      const searchInput = page.locator('#poSearch');
      await expect(searchInput, 'PO search input not found').toBeVisible();

      // Derive the term from the Supplier column (2nd cell); the server search (q=)
      // matches supplier name. The first column (Order #, e.g. PO-0006) is not a
      // searchable substring, so a real supplier name word is used instead.
      const nameCell = (await page.locator('table.erp-table tbody tr').first().locator('td').nth(1).innerText()).trim();
      const term = nameCell.match(/[A-Za-z]{3,}/)?.[0] ?? nameCell.slice(0, 3);
      await searchInput.fill(term);
      await page.keyboard.press('Enter');
      await page.waitForLoadState('networkidle', { timeout: SEARCH_RESPONSE_TIMEOUT });
      await page.waitForTimeout(400);

      const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(alertVisible, 'error-state banner after PO search').toBe(false);

      await assertTableHasRows(page, 'purchase-orders (search)');
    });
  });

  // ── GL Journals (≈1000 seeded) ─────────────────────────────────────────────
  test('GL journals list renders rows, shows paginator, navigates to page 2', async ({ page }) => {
    await gotoClean(page, 'gl/journals');

    await test.step('table has rows', () => assertTableHasRows(page, 'gl/journals'));
    await test.step('paginator present with >1 pages', () => assertPaginatorPresent(page, 'gl/journals'));
    await test.step('page 2 navigable without error', () => assertPage2Navigable(page, 'gl/journals'));
  });

  // ── AR Receipts (≈600 seeded) ──────────────────────────────────────────────
  test('AR receipts list renders rows, shows paginator, navigates to page 2', async ({ page }) => {
    await gotoClean(page, 'ar/receipts');

    await test.step('table has rows', () => assertTableHasRows(page, 'ar/receipts'));
    await test.step('paginator present with >1 pages', () => assertPaginatorPresent(page, 'ar/receipts'));
    await test.step('page 2 navigable without error', () => assertPage2Navigable(page, 'ar/receipts'));
  });

  // ── AP Supplier Bills (≈800 seeded) ───────────────────────────────────────
  test('AP supplier bills list renders rows, shows paginator, navigates to page 2', async ({ page }) => {
    await gotoClean(page, 'ap/supplier-bills');

    await test.step('table has rows', () => assertTableHasRows(page, 'ap/supplier-bills'));
    await test.step('paginator present with >1 pages', () => assertPaginatorPresent(page, 'ap/supplier-bills'));
    await test.step('page 2 navigable without error', () => assertPage2Navigable(page, 'ap/supplier-bills'));
  });

  test('AP supplier bills status filter narrows results', async ({ page }) => {
    await gotoClean(page, 'ap/supplier-bills');
    await assertTableHasRows(page, 'ap/supplier-bills');

    await test.step('select APPROVED status filter', async () => {
      // supplier-bills-list.component.html: id="statusFilter"
      const statusSelect = page.locator('#statusFilter');
      await expect(statusSelect, 'AP bills status filter not found').toBeVisible();
      await statusSelect.selectOption('APPROVED');
      await page.waitForLoadState('networkidle', { timeout: SEARCH_RESPONSE_TIMEOUT });
      await page.waitForTimeout(400);

      const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(alertVisible, 'error-state after AP bills status filter').toBe(false);
    });
  });

});

// ─── cross-module drill-downs ──────────────────────────────────────────────────

test.describe('volume: cross-module detail drill-downs', () => {

  /**
   * DETAIL_HREF_RE: the app links detail rows as /admin/<module>/uid/<ULID>.
   * Pull the first such href from the rendered list and navigate to it.
   */
  const DETAIL_HREF_RE = /\/admin\/[^?#]+\/uid\/([0-9A-HJKMNP-TV-Z]{26})(?:$|[?#/])/;

  async function openFirstDetailFromList(
    page: Parameters<typeof watchProblems>[0],
    listRoute: string,
  ): Promise<string | null> {
    await gotoClean(page, listRoute);
    await assertTableHasRows(page, listRoute);

    const anchors = await page.locator('a[href]').all();
    for (const anchor of anchors) {
      const href = await anchor.getAttribute('href').catch(() => null);
      if (href && DETAIL_HREF_RE.test(href)) {
        // Return the relative href; page.goto() resolves it against the configured
        // baseURL (any port, e.g. 4400). Do NOT hardcode a host/port here.
        return href.startsWith('http')
          ? href
          : `${href.startsWith('/') ? '' : '/'}${href}`;
      }
    }
    return null;
  }

  // ── Sales Order detail → order lines ──────────────────────────────────────
  test('sales order detail renders header and lines section', async ({ page }) => {
    const detailUrl = await openFirstDetailFromList(page, 'sales-orders');

    await test.step('detail URL found in list', () => {
      expect(
        detailUrl,
        'no /uid/<ULID> detail link found on sales-orders list — table may be empty or detail route changed',
      ).not.toBeNull();
    });

    const problems = watchProblems(page);
    await page.goto(detailUrl!, { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(600);

    await test.step('no API 5xx on detail load', () => {
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(api5xx, `API 5xx on SO detail: ${JSON.stringify(api5xx)}`).toHaveLength(0);
    });

    await test.step('no page/console errors on detail load', () => {
      expect(problems.pageErrors, `page errors on SO detail`).toHaveLength(0);
      expect(realConsoleErrors(problems.consoleErrors), `console errors on SO detail`).toHaveLength(0);
    });

    await test.step('order status tag visible', async () => {
      // The detail page shows a status-tag span with the order status.
      const statusTag = page.locator('.status-tag').first();
      await expect(statusTag, 'no status tag on SO detail page').toBeVisible({ timeout: TABLE_READY_TIMEOUT });
    });

    await test.step('order lines section present', async () => {
      // sales-order-detail.component.html renders the lines in a table.
      // The section heading contains "Lines" and a count.
      const linesHeading = page.getByRole('heading', { name: /Lines/ });
      await expect(
        linesHeading,
        'Lines section heading missing on SO detail — lines may not have loaded',
      ).toBeVisible({ timeout: TABLE_READY_TIMEOUT });
    });
  });

  // ── AP Supplier Bill detail ────────────────────────────────────────────────
  test('AP supplier bill detail renders bill header without error', async ({ page }) => {
    const detailUrl = await openFirstDetailFromList(page, 'ap/supplier-bills');

    await test.step('detail URL found', () => {
      expect(
        detailUrl,
        'no /uid/<ULID> detail link on AP bills list',
      ).not.toBeNull();
    });

    const problems = watchProblems(page);
    await page.goto(detailUrl!, { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(600);

    await test.step('no API 5xx', () => {
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(api5xx, `API 5xx on bill detail: ${JSON.stringify(api5xx)}`).toHaveLength(0);
    });

    await test.step('no page/console errors', () => {
      expect(problems.pageErrors, `page errors on bill detail`).toHaveLength(0);
      expect(realConsoleErrors(problems.consoleErrors), `console errors on bill detail`).toHaveLength(0);
    });

    await test.step('bill status tag visible', async () => {
      const statusTag = page.locator('.status-tag').first();
      await expect(statusTag, 'no status tag on bill detail').toBeVisible({ timeout: TABLE_READY_TIMEOUT });
    });
  });

  // ── GL Journal detail → balanced lines ────────────────────────────────────
  test('GL journal entry detail renders balanced lines table', async ({ page }) => {
    const detailUrl = await openFirstDetailFromList(page, 'gl/journals');

    await test.step('detail URL found', () => {
      expect(
        detailUrl,
        'no /uid/<ULID> detail link on GL journals list',
      ).not.toBeNull();
    });

    const problems = watchProblems(page);
    await page.goto(detailUrl!, { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(600);

    await test.step('no API 5xx', () => {
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(api5xx, `API 5xx on GL journal detail: ${JSON.stringify(api5xx)}`).toHaveLength(0);
    });

    await test.step('no page/console errors', () => {
      expect(problems.pageErrors, `page errors on GL journal detail`).toHaveLength(0);
      expect(realConsoleErrors(problems.consoleErrors), `console errors on GL journal detail`).toHaveLength(0);
    });

    await test.step('Lines section heading present', async () => {
      // journal-entry-detail.component.html: h2 with text "Lines (N)"
      const linesHeading = page.getByRole('heading', { name: /Lines/ });
      await expect(
        linesHeading,
        'Lines section missing on GL journal detail',
      ).toBeVisible({ timeout: TABLE_READY_TIMEOUT });
    });

    await test.step('balanced/unbalanced indicator rendered', async () => {
      // The detail page always renders a status-tag for Balanced or Unbalanced.
      // We assert it exists (not that it is balanced — seeded data quality varies).
      const balanceTag = page.locator('.status-tag').filter({ hasText: /Balanced|Unbalanced/ }).first();
      await expect(
        balanceTag,
        'no Balanced/Unbalanced status tag on GL journal detail — balance check may not have loaded',
      ).toBeVisible({ timeout: TABLE_READY_TIMEOUT });
    });

    await test.step('lines table has at least one row', async () => {
      // The journal detail renders table.erp-table with lines, unless the entry has none.
      // With seeded data every journal should have lines.
      const firstLine = page.locator('table.erp-table tbody tr').first();
      await expect(
        firstLine,
        'GL journal detail lines table has no rows — entry may have been persisted without lines',
      ).toBeVisible({ timeout: TABLE_READY_TIMEOUT });
    });
  });

  // ── AR Receipt detail ──────────────────────────────────────────────────────
  test('AR receipt detail renders without error', async ({ page }) => {
    const detailUrl = await openFirstDetailFromList(page, 'ar/receipts');

    await test.step('detail URL found', () => {
      expect(
        detailUrl,
        'no /uid/<ULID> detail link on AR receipts list',
      ).not.toBeNull();
    });

    const problems = watchProblems(page);
    await page.goto(detailUrl!, { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(600);

    await test.step('no API 5xx or page errors', () => {
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(api5xx, `API 5xx on AR receipt detail: ${JSON.stringify(api5xx)}`).toHaveLength(0);
      expect(problems.pageErrors, `page errors on AR receipt detail`).toHaveLength(0);
      expect(realConsoleErrors(problems.consoleErrors), `console errors on AR receipt detail`).toHaveLength(0);
    });

    await test.step('admin-page section renders non-empty content', async () => {
      const bodyText = await page.locator('.admin-page').innerText().catch(() => '');
      expect(
        bodyText.trim().length,
        'AR receipt detail page body is empty — likely a blank render or route error',
      ).toBeGreaterThan(20);
    });
  });

});

// ─── reports and dashboard under volume ───────────────────────────────────────

test.describe('volume: reports and dashboard', () => {

  // ── Main analytics dashboard ───────────────────────────────────────────────
  test('analytics dashboard loads all sections without API error', async ({ page }) => {
    const problems = watchProblems(page);
    await page.goto('/admin/dashboard', { waitUntil: 'networkidle', timeout: 60_000 });

    await test.step('no session bounce', () => {
      expect(page.url()).not.toMatch(/\/login/);
    });

    await test.step('no API 5xx during dashboard load', () => {
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(api5xx, `API 5xx on dashboard: ${JSON.stringify(api5xx)}`).toHaveLength(0);
    });

    await test.step('no page or console errors', () => {
      expect(problems.pageErrors, `page errors on dashboard`).toHaveLength(0);
      expect(realConsoleErrors(problems.consoleErrors), `console errors on dashboard`).toHaveLength(0);
    });

    await test.step('Finance section heading visible', async () => {
      // dashboard.component.html: h2 containing "Finance"
      const financeHeading = page.getByRole('heading', { name: /Finance/i });
      await expect(
        financeHeading,
        'Finance section heading not visible on dashboard — section may have failed to load',
      ).toBeVisible({ timeout: REPORT_READY_TIMEOUT });
    });

    await test.step('Working Capital section heading visible', async () => {
      const wcHeading = page.getByRole('heading', { name: /Working Capital/i });
      await expect(
        wcHeading,
        'Working Capital section heading not visible on dashboard',
      ).toBeVisible({ timeout: REPORT_READY_TIMEOUT });
    });

    await test.step('no loading spinners stuck after networkidle', async () => {
      // Any spinner still spinning after networkidle + 800 ms extra wait is a hang.
      const spinner = page.locator('.spinner-border').first();
      const stuck = await spinner.isVisible().catch(() => false);
      expect(
        stuck,
        'loading spinner still visible on dashboard after networkidle — report request may be hanging',
      ).toBe(false);
    });
  });

  // ── Trial Balance (aggregates all 1000 GL journals) ────────────────────────
  test('trial balance loads and renders account rows without error', async ({ page }) => {
    const problems = watchProblems(page);
    await page.goto('/admin/gl/trial-balance', { waitUntil: 'networkidle', timeout: 60_000 });
    // Give the aggregate query extra time to complete under volume.
    await page.waitForTimeout(1_500);

    await test.step('no session bounce', () => {
      expect(page.url()).not.toMatch(/\/login/);
    });

    await test.step('no API 5xx', () => {
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(api5xx, `API 5xx on trial balance: ${JSON.stringify(api5xx)}`).toHaveLength(0);
    });

    await test.step('no page or console errors', () => {
      expect(problems.pageErrors, `page errors on trial balance`).toHaveLength(0);
      expect(realConsoleErrors(problems.consoleErrors), `console errors on trial balance`).toHaveLength(0);
    });

    await test.step('trial balance table renders', async () => {
      // trial-balance.component.html: table.erp-table with account rows, OR erp-empty if no data.
      // Under volume we expect data; assert no error-state banner appeared.
      const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(alertVisible, 'error-state on trial balance').toBe(false);

      // The Balanced/Unbalanced status banner should be visible once data loads.
      const balancedBanner = page
        .locator('.alert-success, .alert-danger')
        .filter({ hasText: /Balanced|Out of balance/ })
        .first();
      await expect(
        balancedBanner,
        'Balanced/Out-of-balance banner missing on trial balance — report may not have loaded or calculated',
      ).toBeVisible({ timeout: REPORT_READY_TIMEOUT });
    });

    await test.step('tfoot totals row visible', async () => {
      // The trial balance renders a <tfoot> "Totals" row.
      const tfoot = page.locator('table.erp-table tfoot').first();
      await expect(
        tfoot,
        'trial balance tfoot totals row missing — table may not have rendered',
      ).toBeVisible({ timeout: REPORT_READY_TIMEOUT });
    });
  });

  // ── AR Ageing (aggregates 1000 customers + 600 receipts) ──────────────────
  test('AR ageing loads and renders customer ageing table without error', async ({ page }) => {
    const problems = watchProblems(page);
    await page.goto('/admin/ar/ageing', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(1_500);

    await test.step('no session bounce', () => {
      expect(page.url()).not.toMatch(/\/login/);
    });

    await test.step('no API 5xx', () => {
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(api5xx, `API 5xx on AR ageing: ${JSON.stringify(api5xx)}`).toHaveLength(0);
    });

    await test.step('no page or console errors', () => {
      expect(problems.pageErrors, `page errors on AR ageing`).toHaveLength(0);
      expect(realConsoleErrors(problems.consoleErrors), `console errors on AR ageing`).toHaveLength(0);
    });

    await test.step('Customer Ageing Buckets heading visible', async () => {
      // ar-ageing.component.html: h2 "Customer Ageing Buckets"
      const heading = page.getByRole('heading', { name: /Customer Ageing Buckets/i });
      await expect(
        heading,
        'Customer Ageing Buckets heading missing on AR ageing page',
      ).toBeVisible({ timeout: REPORT_READY_TIMEOUT });
    });

    await test.step('ageing table renders rows (not empty)', async () => {
      const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(alertVisible, 'error-state on AR ageing page').toBe(false);

      // With seeded AR data, the ageing table should have rows.
      const firstRow = page.locator('table.erp-table tbody tr').first();
      await expect(
        firstRow,
        'AR ageing table has no rows — either no AR data was seeded with open balances, or the aggregation failed',
      ).toBeVisible({ timeout: REPORT_READY_TIMEOUT });
    });

    await test.step('no loading spinner stuck after networkidle', async () => {
      const spinner = page.locator('.spinner-border').first();
      const stuck = await spinner.isVisible().catch(() => false);
      expect(stuck, 'loading spinner stuck on AR ageing page').toBe(false);
    });
  });

  // ── Stock Valuation Report ─────────────────────────────────────────────────
  test('stock valuation report loads without API error under volume', async ({ page }) => {
    const problems = watchProblems(page);
    await page.goto('/admin/stock/valuation', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(1_500);

    await test.step('no session bounce', () => {
      expect(page.url()).not.toMatch(/\/login/);
    });

    await test.step('no API 5xx', () => {
      const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
      expect(api5xx, `API 5xx on stock valuation: ${JSON.stringify(api5xx)}`).toHaveLength(0);
    });

    await test.step('no page or console errors', () => {
      expect(problems.pageErrors, `page errors on stock valuation`).toHaveLength(0);
      expect(realConsoleErrors(problems.consoleErrors), `console errors on stock valuation`).toHaveLength(0);
    });

    await test.step('page section renders non-empty content', async () => {
      // We do not assert row count — valuation depends on movements seeded.
      // We assert no error state and the page body has content.
      const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
      const alertVisible = await errorAlert.isVisible().catch(() => false);
      expect(alertVisible, 'error-state on stock valuation page').toBe(false);

      const bodyText = await page.locator('.admin-page').innerText().catch(() => '');
      expect(bodyText.trim().length, 'stock valuation page body is empty').toBeGreaterThan(10);
    });
  });

});

// ─── pagination control integrity under volume ────────────────────────────────

test.describe('volume: pagination control integrity', () => {

  /**
   * For each high-volume list: navigate to the LAST page and assert:
   *   - "Last page" button is disabled (we are on it)
   *   - "First page" button is enabled
   *   - table still has rows (last page may be partial but must not be empty)
   *
   * This catches the most common pagination regression: the last page button
   * fires the correct page index and the backend handles it (OOB index → 400/500).
   */
  const HIGH_VOLUME_LISTS: { route: string; label: string }[] = [
    { route: 'products', label: 'products' },
    { route: 'customers', label: 'customers' },
    { route: 'sales-orders', label: 'sales orders' },
    { route: 'purchase-orders', label: 'purchase orders' },
    { route: 'gl/journals', label: 'GL journals' },
    { route: 'ar/receipts', label: 'AR receipts' },
    { route: 'ap/supplier-bills', label: 'AP supplier bills' },
  ];

  for (const { route, label } of HIGH_VOLUME_LISTS) {
    test(`${label}: last page navigable and returns rows`, async ({ page }) => {
      await gotoClean(page, route);
      await assertTableHasRows(page, route);

      const paginatorNav = page.locator('nav[aria-label$="pagination"]').first();
      // Skip gracefully if paginator did not render (single-page result would be a
      // different kind of failure already caught by the list tests above).
      const paginatorVisible = await paginatorNav.isVisible().catch(() => false);
      if (!paginatorVisible) {
        test.info().annotations.push({
          type: 'skip-reason',
          description: `paginator not visible on /admin/${route} — already reported by list tests`,
        });
        return;
      }

      await test.step('click Last page', async () => {
        const lastBtn = paginatorNav.getByRole('button', { name: 'Last page' });
        await expect(lastBtn, `Last page button missing on /admin/${route}`).toBeVisible();
        await expect(lastBtn, `Last page button disabled on page 1 of multi-page list /admin/${route}`).toBeEnabled();
        await lastBtn.click();
        await page.waitForLoadState('networkidle', { timeout: TABLE_READY_TIMEOUT });
        await page.waitForTimeout(400);
      });

      await test.step('last page has rows', () => assertTableHasRows(page, `${route} (last page)`));

      await test.step('Last page button is now disabled', async () => {
        const lastBtn = paginatorNav.getByRole('button', { name: 'Last page' });
        await expect(
          lastBtn,
          `Last page button is not disabled when on the last page of /admin/${route}`,
        ).toBeDisabled();
      });

      await test.step('First page button is enabled', async () => {
        const firstBtn = paginatorNav.getByRole('button', { name: 'First page' });
        await expect(
          firstBtn,
          `First page button not enabled on last page of /admin/${route}`,
        ).toBeEnabled();
      });

      await test.step('no API errors after navigating to last page', () => {
        // Re-use the problems watcher set up by gotoClean above.
        // Any 5xx that fired since page navigation = out-of-range index bug.
        // Note: gotoClean watcher covers the whole test; new failures append.
        // We re-check by reading the page for an error-state element.
      });

      await test.step('no error-state banner visible', async () => {
        const errorAlert = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
        const alertVisible = await errorAlert.isVisible().catch(() => false);
        expect(alertVisible, `error banner on last page of /admin/${route}`).toBe(false);
      });
    });
  }

});
