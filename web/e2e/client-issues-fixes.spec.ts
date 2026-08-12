/**
 * Regression e2e for the client-reported pending issues of 2026-08-11.
 *
 * These drive the REAL screens the client says are broken, because every one of these defects was
 * invisible to the unit suite: "+ Add item" threw a TypeError only when Angular's NumberValueAccessor
 * fed a real number into a signal typed as string, which no spec that pokes signals with string
 * literals can ever reproduce.
 *
 * Requires the local stack (API on :8081, dev DB) — opt-in, like the rest of e2e/.
 */
import type { Page } from '@playwright/test';
import { test, expect } from './_test-authenticated';

/** Pick the first suggestion out of the screen's hand-rolled typeahead. */
async function pickSuggestion(page: Page, inputId: string, query: string): Promise<string> {
  const input = page.locator(`#${inputId}`);
  await input.fill(query);
  const suggestion = page.locator('ul.list-group li button').first();
  await expect(suggestion).toBeVisible({ timeout: 15_000 });
  const label = (await suggestion.textContent())?.trim() ?? '';
  await suggestion.click();
  return label;
}

test.describe('Receive Goods Without an Order', () => {
  test('adds a line and receives it into stock', async ({ page }) => {
    await page.goto('/admin/goods-receipts/direct');
    await expect(page.getByRole('heading', { name: /Receive Goods Without an Order/i }))
      .toBeVisible({ timeout: 30_000 });

    const supplier = await pickSuggestion(page, 'drSupplierSearch', 'Smoke');
    expect(supplier).not.toEqual('');

    const product = await pickSuggestion(page, 'drProductSearch', 'Smoke');
    expect(product).not.toEqual('');

    // The unit select is populated asynchronously from GET /products/uid/{uid}/units.
    await expect(page.locator('#drUnit')).toBeEnabled({ timeout: 15_000 });

    await page.locator('#drQty').fill('50');
    await page.locator('#drCost').fill('5000');

    // THE REGRESSION: this used to throw `newLineQty(...).trim is not a function` and no-op silently.
    await page.getByRole('button', { name: /Add item/i }).click();

    await expect(page.getByText(/No items added yet/i)).toHaveCount(0);
    await expect(page.locator('table tbody tr')).toHaveCount(1);
    // And it must not have failed quietly instead.
    await expect(page.locator('p[role="alert"]')).toHaveCount(0);

    const receive = page.getByRole('button', { name: /Receive into stock/i });
    await expect(receive).toBeEnabled();
    await receive.click();

    // A successful direct receipt navigates to the new GRN's detail page.
    await expect(page).toHaveURL(/\/admin\/goods-receipts\/uid\//, { timeout: 30_000 });
    // Issue 4c: supplier and grand total must now be visible ON SCREEN, not only in the PDF.
    await expect(page.getByText(/Supplier/i).first()).toBeVisible();
    await expect(page.getByText(/Total Received Value/i)).toBeVisible();
  });

  test('explains itself instead of going dead when nothing has been added', async ({ page }) => {
    await page.goto('/admin/goods-receipts/direct');
    await expect(page.getByRole('heading', { name: /Receive Goods Without an Order/i }))
      .toBeVisible({ timeout: 30_000 });

    const receive = page.getByRole('button', { name: /Receive into stock/i });
    // Previously [disabled] on an empty list: the click never fired and the user got silence.
    await expect(receive).toBeEnabled();
    await receive.click();
    await expect(page.locator('p[role="alert"]')).toContainText(/company|supplier|at least one item/i);
  });
});

test.describe('Stock Valuation report', () => {
  test('offers PDF / Excel / CSV export and downloads a real file', async ({ page }) => {
    await page.goto('/admin/stock/valuation');
    await expect(page.getByRole('heading', { name: /Stock Valuation/i }))
      .toBeVisible({ timeout: 30_000 });

    // The three buttons the client drew red X marks over.
    const pdf = page.getByRole('button', { name: /Export PDF/i });
    await expect(pdf).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: /Export Excel/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Export CSV/i })).toBeVisible();

    const download = page.waitForEvent('download', { timeout: 30_000 });
    await pdf.click();
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/\.pdf$/i);
  });
});
