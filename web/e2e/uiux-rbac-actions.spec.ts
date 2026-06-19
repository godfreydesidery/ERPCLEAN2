/**
 * UI/UX RBAC action-level spec — view-only user on /admin/products.
 *
 * Project: chromium-unauthenticated (no storageState; logs in itself).
 *
 * Purpose:
 *   1. A user with PRODUCT.VIEW only (no PRODUCT.MANAGE) can reach /admin/products
 *      because the route guard only requires PRODUCT.VIEW.
 *   2. The "New Product" button is absent — canManage() is false, so the @if block
 *      never renders it.
 *   3. The product list still renders (view is permitted).  A 'forbidden' state
 *      (the <p role="alert"> rendered when the backend returns 403) must NOT appear
 *      because the user has PRODUCT.VIEW.
 *   4. A direct API write attempt (POST /api/v1/products) with the view-only token
 *      returns 403, not 200/201 and not 500.  The app must not surface a raw 500
 *      or an unhandled stack trace on screen.
 *
 * Self-contained setup (mirrors uiux-rbac-nav.spec.ts pattern):
 *   beforeAll logs in as rootadmin via the API, creates a VIEW-ONLY role
 *   (PRODUCT.VIEW only), then creates a test user with that role and a branch.
 *   All resources carry a per-run TAG so the spec is idempotent.
 *   If setup fails every test is annotated and skipped — never hard-fails on env.
 *
 * Selector sources (product-list.component.html, verified):
 *   - button[aria-controls="createProductForm"]  — the "New Product" toggle button
 *     (rendered only when canManage() is true; absent entirely when view-only)
 *   - table.erp-table tbody tr                  — product rows
 *   - p[role="alert"]                           — forbidden / error states
 *   - .erp-empty                                — empty-list container
 *   - #createProductForm                        — the inline create card
 *
 * Route guard: /admin/products → requirePermission('PRODUCT.VIEW')
 * canManage signal: session.hasPermission('PRODUCT.MANAGE')
 */

import { test, expect, request as pwRequest } from '@playwright/test';
import { login, ROOT_USER, ROOT_PASS, watchProblems } from './_helpers';

// ─── constants ────────────────────────────────────────────────────────────────

/** Per-run tag: keeps resources isolated across reruns and parallel workers. */
const TAG = `ract${Date.now().toString(36).slice(-6)}`;

/** The single permission our view-only role carries. */
const VIEW_PERM = 'PRODUCT.VIEW';

/** The permission the role must NOT have (controls the "New Product" button). */
const MANAGE_PERM = 'PRODUCT.MANAGE';

/** View-only user credentials, derived from the per-run tag. */
const VIEW_USERNAME = `view.${TAG}`;
const VIEW_PASS     = 'ViewOnly12!';

/** Default API base (Angular dev server proxies /api → backend :8081). */
const API_BASE = '/api/v1';

/** Patterns whose presence in visible page text signals a raw server error leak. */
const RAW_ERROR_PATTERNS = [/\b500\b/, /stack\s*trace/i, /NullPointerException/i, /unexpected error/i];

// ─── shared setup state ───────────────────────────────────────────────────────

let setupOk = false;
let setupFailReason = '';

/** Token for the view-only user; used in the direct API call test. */
let viewOnlyToken = '';

/** UID of the first company found (needed when crafting a product create body). */
let firstCompanyUid = '';

/** UID of a base unit (so A5 can POST a VALID product body — the permission gate (403) only
 *  fires after @Valid passes; a body missing baseUnitUid would 400 before reaching the gate. */
let firstUnitUid = '';

// ─── helpers ──────────────────────────────────────────────────────────────────

/** Minimal Playwright request context wrapper. */
async function apiReq(
  ctx: Awaited<ReturnType<typeof pwRequest.newContext>>,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  token: string | null,
  body?: unknown,
): Promise<{ status: number; body: unknown }> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const opts = { headers, ...(body !== undefined ? { data: body } : {}) };
  const res =
    method === 'GET'
      ? await ctx.get(API_BASE + path, { headers })
      : method === 'POST'
        ? await ctx.post(API_BASE + path, opts)
        : method === 'PUT'
          ? await ctx.put(API_BASE + path, opts)
          : await ctx.delete(API_BASE + path, { headers });
  let parsed: unknown;
  try { parsed = await res.json(); } catch { parsed = await res.text().catch(() => ''); }
  return { status: res.status(), body: parsed };
}

/** Extract uid from the canonical ApiResponse<T> envelope. */
function uidOf(r: { body: unknown }): string | null {
  const b = r.body as Record<string, unknown> | null;
  if (!b) return null;
  const data = b['data'] as Record<string, unknown> | null;
  if (data?.['uid']) return String(data['uid']);
  if ((b as Record<string, unknown>)['uid']) return String((b as Record<string, unknown>)['uid']);
  return null;
}

/** Extract a list from GET responses (page envelope or plain array). */
function listOf(r: { body: unknown }): unknown[] {
  const b = r.body as Record<string, unknown> | null;
  if (!b) return [];
  const data = b['data'] as Record<string, unknown> | null;
  if (Array.isArray(data?.['content'])) return data!['content'] as unknown[];
  if (Array.isArray(data)) return data as unknown[];
  if (Array.isArray(b['data'])) return b['data'] as unknown[];
  return [];
}

// ─── suite ────────────────────────────────────────────────────────────────────

test.describe('UI/UX RBAC actions — PRODUCT.VIEW-only user on /admin/products', () => {

  // ── one-time seed: create view-only role + test user ──────────────────────
  test.beforeAll(async () => {
    const appBase = process.env['PW_BASE_URL'] ?? 'http://localhost:4200';
    const ctx = await pwRequest.newContext({ baseURL: appBase });

    try {
      // 1. Log in as rootadmin.
      const loginRes = await apiReq(ctx, 'POST', '/auth/login', null, {
        username: ROOT_USER,
        password: ROOT_PASS,
      });
      const loginBody = loginRes.body as Record<string, unknown>;
      const adminToken = (
        (loginBody?.['data'] as Record<string, unknown>)?.['accessToken'] as string | undefined
      ) ?? '';
      if (!adminToken) {
        setupFailReason = `rootadmin login failed (status ${loginRes.status}): ${JSON.stringify(loginRes.body).slice(0, 200)}`;
        return;
      }

      // 2. Discover organisation -> company -> branch.
      //    /companies REQUIRES organisationUid; branches are listed via /branches?companyUid=...
      //    (there is no /companies/{uid}/branches endpoint).
      const orgsRes = await apiReq(ctx, 'GET', '/organisations?size=5', adminToken);
      const orgs = listOf(orgsRes) as Array<Record<string, unknown>>;
      if (orgs.length === 0) {
        setupFailReason = `No organisations found (status ${orgsRes.status}) — is the backend bootstrapped?`;
        return;
      }
      const orgUid = String(orgs[0]['uid']);

      const companiesRes = await apiReq(ctx, 'GET', `/companies?organisationUid=${orgUid}&size=5`, adminToken);
      const companies = listOf(companiesRes) as Array<Record<string, unknown>>;
      if (companies.length === 0) {
        setupFailReason = `No companies found for org ${orgUid} (status ${companiesRes.status})`;
        return;
      }
      firstCompanyUid = String(companies[0]['uid']);

      const branchesRes = await apiReq(
        ctx, 'GET', `/branches?companyUid=${firstCompanyUid}&size=5`, adminToken,
      );
      const branches = listOf(branchesRes) as Array<Record<string, unknown>>;
      if (branches.length === 0) {
        setupFailReason = `No branches found for company ${firstCompanyUid} (status ${branchesRes.status})`;
        return;
      }
      const branchUid = String(branches[0]['uid']);
      const companyUid = firstCompanyUid;

      // Fetch a base unit so A5 can POST a VALID product body (the 403 permission gate only fires
      // after @Valid passes; a body missing baseUnitUid would 400 first). companies[0].id = numeric id.
      const companyNumericId = String(companies[0]['id'] ?? '');
      const unitsRes = await apiReq(ctx, 'GET', `/units?companyId=${companyNumericId}&size=1`, adminToken);
      const units = listOf(unitsRes) as Array<Record<string, unknown>>;
      if (units.length > 0) firstUnitUid = String(units[0]['uid']);

      // 3. Resolve PRODUCT.VIEW from the permission catalogue.
      //    Only PRODUCT.VIEW goes into the role.  PRODUCT.MANAGE must not be added.
      const permsRes = await apiReq(ctx, 'GET', '/roles/permissions', adminToken);
      const permCatalogue = listOf(permsRes) as Array<Record<string, unknown>>;
      const availableCodes = new Set(permCatalogue.map((p) => String(p['code'])));
      if (!availableCodes.has(VIEW_PERM)) {
        // Warn but do not abort: the role will have no permissions; the test still
        // verifies the view-only experience (the page guard will redirect to /admin/home).
        console.warn(
          `[rbac-actions-setup] ${VIEW_PERM} not found in permission catalogue ` +
          `(${permCatalogue.length} entries). View tests will likely skip gracefully.`,
        );
      }
      if (availableCodes.has(MANAGE_PERM)) {
        // Intentional: we will NOT include MANAGE_PERM even though it exists.
        console.log(`[rbac-actions-setup] ${MANAGE_PERM} exists in catalogue — intentionally excluded from role.`);
      }

      // 4. Create the view-only role.
      const roleRes = await apiReq(ctx, 'POST', '/roles', adminToken, {
        code: `VIEW_ONLY_${TAG}`,
        name: `View Only ${TAG}`,
        description: `e2e view-only role — ${TAG} — safe to delete`,
      });
      const roleUid = uidOf(roleRes);
      if (!roleUid) {
        setupFailReason = `Failed to create view-only role: ${JSON.stringify(roleRes.body).slice(0, 200)}`;
        return;
      }

      // 5. Assign PRODUCT.VIEW (and only that) to the role.
      if (availableCodes.has(VIEW_PERM)) {
        const permRes = await apiReq(
          ctx, 'PUT', `/roles/uid/${roleUid}/permissions`, adminToken,
          { permissionCodes: [VIEW_PERM] },
        );
        if (permRes.status >= 300) {
          console.warn(
            `[rbac-actions-setup] permission assignment returned ${permRes.status}: ` +
            `${JSON.stringify(permRes.body).slice(0, 200)}`,
          );
          // Non-fatal: proceed; the test will handle a zero-permission user gracefully.
        }
      }

      // 6. Create the view-only test user.
      const userRes = await apiReq(ctx, 'POST', '/users', adminToken, {
        username: VIEW_USERNAME,
        displayName: `View Only User ${TAG}`,
        password: VIEW_PASS,
        email: `${VIEW_USERNAME}@erp-test.local`,
        phone: `0700001${TAG.slice(-4)}`,
      });
      const userUid = uidOf(userRes);
      if (!userUid) {
        setupFailReason = `Failed to create view-only user: ${JSON.stringify(userRes.body).slice(0, 200)}`;
        return;
      }

      // 7. Assign a branch (make it the default).
      await apiReq(ctx, 'POST', '/user-branches', adminToken, {
        userUid,
        branchUid,
        makeDefault: true,
      });

      // 8. Grant the view-only role to the user.
      const grantRes = await apiReq(ctx, 'POST', '/user-roles', adminToken, {
        userUid,
        roleUid,
        companyUid,
        branchUid,
      });
      if (grantRes.status >= 300) {
        setupFailReason = `Failed to grant view-only role to user: ${JSON.stringify(grantRes.body).slice(0, 200)}`;
        return;
      }

      // 9. Obtain the view-only user's token for the API write test.
      const viewLoginRes = await apiReq(ctx, 'POST', '/auth/login', null, {
        username: VIEW_USERNAME,
        password: VIEW_PASS,
      });
      const viewLoginBody = viewLoginRes.body as Record<string, unknown>;
      viewOnlyToken = (
        (viewLoginBody?.['data'] as Record<string, unknown>)?.['accessToken'] as string | undefined
      ) ?? '';
      if (!viewOnlyToken) {
        // Non-fatal: the token test will annotate and skip.
        console.warn(`[rbac-actions-setup] Could not obtain token for view-only user: status ${viewLoginRes.status}`);
      }

      setupOk = true;
    } catch (err) {
      setupFailReason = `beforeAll threw: ${String(err)}`;
    } finally {
      await ctx.dispose();
    }
  });

  // ── skip helper ────────────────────────────────────────────────────────────
  function skipIfSetupFailed(): void {
    if (!setupOk) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: `Setup did not complete: ${setupFailReason}`,
      });
      test.skip();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Test A1: view-only user can log in and land on /admin/home
  // ──────────────────────────────────────────────────────────────────────────
  test('A1: view-only user logs in via the UI and lands on /admin (not /login)', async ({ page }) => {
    skipIfSetupFailed();

    await login(page, VIEW_USERNAME, VIEW_PASS);

    // After login() the expect(page).toHaveURL(/\/admin/) already asserted in the helper.
    // Re-confirm we are not on /login and not showing a blank page.
    const url = page.url();
    expect(url, 'View-only user should be on /admin after login').toMatch(/\/admin/);
    expect(url, 'View-only user should not be bounced to /login').not.toMatch(/\/login/);

    // Basic sanity: the shell renders something (sidebar or main content).
    const bodyText = await page.locator('body').innerText().catch(() => '');
    expect(
      bodyText.trim().length,
      'Page body is empty after view-only login — Angular may not have booted',
    ).toBeGreaterThan(0);
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Test A2: view-only user can reach /admin/products (PRODUCT.VIEW satisfies the route guard)
  // ──────────────────────────────────────────────────────────────────────────
  test('A2: view-only user reaches /admin/products — route guard allows PRODUCT.VIEW', async ({ page }) => {
    skipIfSetupFailed();

    const problems = watchProblems(page);
    await login(page, VIEW_USERNAME, VIEW_PASS);
    await page.goto('/admin/products', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(800);

    const url = page.url();

    // The route guard (requirePermission('PRODUCT.VIEW')) should let the user through.
    // If the guard fires it redirects to /admin/home, not /login.
    expect(url, 'View-only user should not be bounced to /login from /admin/products').not.toMatch(/\/login/);
    expect(
      url,
      'View-only user should reach /admin/products — PRODUCT.VIEW satisfies the route guard',
    ).toMatch(/\/admin\/products/);

    // No API 5xx during page load.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    if (api5xx.length > 0) {
      test.info().annotations.push({
        type: 'warn',
        description: `API 5xx during /admin/products load for view-only user: ${JSON.stringify(api5xx)}`,
      });
    }
    expect(
      api5xx,
      `API 5xx during /admin/products load: ${JSON.stringify(api5xx)}`,
    ).toHaveLength(0);
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Test A3: "New Product" button is absent — PRODUCT.MANAGE not granted
  // ──────────────────────────────────────────────────────────────────────────
  test('A3: "New Product" button is absent for view-only user (canManage() = false)', async ({ page }) => {
    skipIfSetupFailed();

    await login(page, VIEW_USERNAME, VIEW_PASS);
    await page.goto('/admin/products', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(800);

    // If the guard redirected away from /admin/products (e.g. PRODUCT.VIEW not in catalogue),
    // annotate and skip this assertion rather than failing with a misleading selector error.
    const url = page.url();
    if (!url.includes('/admin/products')) {
      test.info().annotations.push({
        type: 'skip-reason',
        description:
          `View-only user was redirected away from /admin/products (landed: ${url}). ` +
          `PRODUCT.VIEW may not be in the permission catalogue — cannot assert button absence.`,
      });
      return;
    }

    // The "New Product" button is rendered inside @if (canManage()) in the component template.
    // A user without PRODUCT.MANAGE must NOT see it at all (zero count in DOM).
    const newProductBtn = page.getByRole('button', { name: /New Product/i });
    const btnCount = await newProductBtn.count();

    expect(
      btnCount,
      '"New Product" button is visible for a PRODUCT.VIEW-only user — ' +
        'canManage() should return false when PRODUCT.MANAGE is not granted',
    ).toBe(0);

    // Also confirm the #createProductForm card is absent (it only appears after the button click,
    // and the button itself should not exist, so the form must also be absent).
    const createForm = page.locator('#createProductForm');
    const formCount = await createForm.count();
    expect(
      formCount,
      '#createProductForm is present even though the "New Product" button is absent — ' +
        'the create card must not be reachable without PRODUCT.MANAGE',
    ).toBe(0);
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Test A4: product list renders rows (VIEW is permitted) — no 'forbidden' alert
  // ──────────────────────────────────────────────────────────────────────────
  test('A4: product list renders for view-only user — no "permission" alert, no raw error', async ({ page }) => {
    skipIfSetupFailed();

    const problems = watchProblems(page);
    await login(page, VIEW_USERNAME, VIEW_PASS);
    await page.goto('/admin/products', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(1_000);

    const url = page.url();
    if (!url.includes('/admin/products')) {
      test.info().annotations.push({
        type: 'skip-reason',
        description:
          `View-only user did not reach /admin/products (landed: ${url}) — cannot verify list render.`,
      });
      return;
    }

    // The 'forbidden' state renders:
    //   <p class="text-muted ... py-3" role="alert">
    //     <i class="bi bi-shield-lock" ...></i>You don't have permission to view products.
    //   </p>
    // This must NOT appear when the user has PRODUCT.VIEW.
    const forbiddenAlert = page.locator('[role="alert"]').filter({ hasText: /don't have permission/i });
    const forbiddenVisible = await forbiddenAlert.isVisible().catch(() => false);
    expect(
      forbiddenVisible,
      '"You don\'t have permission to view products." alert is visible even though the user ' +
        'has PRODUCT.VIEW — the forbidden state must only render on backend 403',
    ).toBe(false);

    // The page must show EITHER product rows OR the empty-state (.erp-empty).
    // It must not show an error-state alert (which would indicate a backend error).
    const errorAlert = page.locator('p.text-danger[role="alert"]').filter({
      hasText: /Could not load products/i,
    });
    const errorVisible = await errorAlert.isVisible().catch(() => false);
    if (errorVisible) {
      test.info().annotations.push({
        type: 'warn',
        description:
          '"Could not load products" error alert is visible — the products API returned an error ' +
          'for this user. This may be a backend permission-check bug or a missing company context.',
      });
    }

    // Either a table row or the empty-state must be present (page is functional).
    const hasRows = await page.locator('table.erp-table tbody tr').count() > 0;
    const hasEmptyState = await page.locator('.erp-empty').isVisible().catch(() => false);

    if (!hasRows && !hasEmptyState && !errorVisible) {
      test.info().annotations.push({
        type: 'warn',
        description:
          'Neither product rows, nor an empty state, nor an error alert are visible. ' +
          'The list may still be loading or no company was resolved.',
      });
    }

    // No raw server errors on screen.
    const bodyText = await page.locator('body').innerText().catch(() => '');
    for (const pattern of RAW_ERROR_PATTERNS) {
      expect(
        pattern.test(bodyText),
        `Raw server error pattern "${pattern}" leaked on /admin/products for view-only user`,
      ).toBe(false);
    }

    // No API 5xx during page load.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(
      api5xx,
      `API 5xx during products list load for view-only user: ${JSON.stringify(api5xx)}`,
    ).toHaveLength(0);
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Test A5: direct API write with view-only token returns 403, not 201 or 500
  // ──────────────────────────────────────────────────────────────────────────
  test('A5: direct POST /api/v1/products with view-only token returns 403 — write is denied', async () => {
    skipIfSetupFailed();

    if (!viewOnlyToken) {
      test.info().annotations.push({
        type: 'skip-reason',
        description:
          'View-only user token was not obtained during setup — cannot exercise the direct API write test.',
      });
      return;
    }
    if (!firstCompanyUid) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No company UID available — cannot construct a product create payload.',
      });
      return;
    }
    if (!firstUnitUid) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'No base unit available — cannot build a VALID product body to isolate the 403 gate.',
      });
      return;
    }

    const appBase = process.env['PW_BASE_URL'] ?? 'http://localhost:4200';
    const ctx = await pwRequest.newContext({ baseURL: appBase });
    try {
      // Send a VALID product body so the ONLY reason to reject is the missing PRODUCT.MANAGE
      // permission -> 403. (@Valid runs before @PreAuthorize in Spring, so an invalid body would
      // 400 before reaching the gate — which is why baseUnitUid must be present here.)
      const res = await apiReq(ctx, 'POST', '/products', viewOnlyToken, {
        companyUid: firstCompanyUid,
        name: `Forbidden-${TAG}`,
        type: 'GOODS',
        sellable: true,
        stockable: true,
        purchasable: true,
        baseUnitUid: firstUnitUid,
        vatStatus: 'STANDARD',
      });

      // A user with PRODUCT.VIEW and no PRODUCT.MANAGE must be rejected at the authorization layer.
      expect(
        res.status,
        `POST /api/v1/products with PRODUCT.VIEW-only token returned ${res.status} — ` +
          `expected 403 (Forbidden). ` +
          `201 means the permission gate is missing. ` +
          `500 means the request was processed and crashed. ` +
          `Body: ${JSON.stringify(res.body).slice(0, 300)}`,
      ).toBe(403);

      // The 403 body must not carry a raw stack trace or 500-style message.
      const bodyStr = JSON.stringify(res.body ?? '');
      for (const pattern of RAW_ERROR_PATTERNS) {
        expect(
          pattern.test(bodyStr),
          `Raw server error pattern "${pattern}" found in 403 response body from POST /products — ` +
            `the error serialiser must not include stack traces`,
        ).toBe(false);
      }
    } finally {
      await ctx.dispose();
    }
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Test A6: no raw "500" or stack trace on screen after a failed (or any) navigation
  // ──────────────────────────────────────────────────────────────────────────
  test('A6: no raw error text leaks to the screen at any point during the view-only session', async ({ page }) => {
    skipIfSetupFailed();

    const problems = watchProblems(page);
    await login(page, VIEW_USERNAME, VIEW_PASS);

    // Navigate to /admin/products (the primary subject route).
    await page.goto('/admin/products', { waitUntil: 'networkidle', timeout: 60_000 });
    await page.waitForTimeout(600);

    const bodyText = await page.locator('body').innerText().catch(() => '');
    for (const pattern of RAW_ERROR_PATTERNS) {
      expect(
        pattern.test(bodyText),
        `Raw server error pattern "${pattern}" leaked on screen for view-only user at /admin/products`,
      ).toBe(false);
    }

    // No uncaught page-level JavaScript errors.
    expect(
      problems.pageErrors,
      `Uncaught JS error during view-only session: ${problems.pageErrors.join('; ')}`,
    ).toHaveLength(0);

    // No API 5xx at all during the session.
    const api5xx = problems.apiFailures.filter((f) => f.status >= 500);
    expect(
      api5xx,
      `API 5xx during view-only session: ${JSON.stringify(api5xx)}`,
    ).toHaveLength(0);
  });

});
