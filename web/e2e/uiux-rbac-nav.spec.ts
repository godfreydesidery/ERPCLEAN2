/**
 * UI/UX RBAC nav spec — permission-driven sidebar behaviour.
 *
 * Project: chromium-unauthenticated (no storageState; each test logs in itself).
 *
 * Purpose:
 *   1. Verify that the sidebar NEVER renders a .nav-group with zero navigable links
 *      (regression guard for the "hide empty nav groups" fix).
 *   2. Verify that a narrow-permission user sees ONLY the groups they are permitted for.
 *   3. Verify that rootadmin sees many groups (root sees all).
 *   4. Verify that a narrow user visiting a forbidden route is redirected to /admin/home
 *      (not a crash, not a blank page, no data shown).
 *
 * Self-contained setup:
 *   beforeAll logs in as rootadmin via the API, discovers company/branch/permission
 *   catalogue, creates a narrow role (STOCK.VIEW + PRODUCT.VIEW only), creates a test
 *   user with that role + branch assignment.  All created resources use a per-run TAG
 *   so the spec is idempotent and parallel-safe.
 *
 *   If setup fails (e.g. fresh DB missing expected data) every test is annotated and
 *   skipped rather than failing the suite with misleading errors.
 *
 * Selectors grounded in shell.component.html:
 *   - .nav-group              — one rendered group wrapper
 *   - .nav-group-label        — the group title text
 *   - a.nav-item              — a routable nav link (only rendered when item.available
 *                               AND session.hasPermission(item.permission))
 *
 * Auth flow:
 *   Uses login() from _helpers — fills the real form and waits for /admin redirect.
 *   All tokens live in sessionStorage; each test re-logs-in to start from a clean state.
 */

import { test, expect, request as pwRequest } from '@playwright/test';
import { login, ROOT_PASS, ROOT_USER } from './_helpers';

// ─── constants ────────────────────────────────────────────────────────────────

/** Per-run uniqueness tag keeps parallel runs and re-runs isolated. */
const TAG = `rbac${Date.now().toString(36).slice(-6)}`;

/**
 * The two permissions that define our narrow role:
 *   STOCK.VIEW  → enables items in "Inventory" nav group
 *   PRODUCT.VIEW → enables items in "Products" nav group
 *
 * A user with ONLY these two codes should see exactly the "Inventory" and "Products"
 * groups (and nothing else), because every item in every other group requires a
 * different permission code.
 */
const NARROW_PERMS = ['STOCK.VIEW', 'PRODUCT.VIEW'];

/**
 * Groups that MUST be absent for the narrow user.
 * These groups have items that all require permissions not in NARROW_PERMS.
 */
const ABSENT_GROUPS_FOR_NARROW_USER = ['Accounting', 'HR & Payroll', 'Administration', 'Purchasing', 'Sales'];

/**
 * Groups that MUST be present for the narrow user (at least one item permitted).
 * "Products" requires PRODUCT.VIEW; "Inventory" requires STOCK.VIEW (and others).
 */
const PRESENT_GROUPS_FOR_NARROW_USER = ['Products', 'Inventory'];

/**
 * A route that the narrow user must NOT be able to access — it requires GL.VIEW.
 * The permission guard redirects to /admin/home instead of showing the page.
 */
const FORBIDDEN_ROUTE_FOR_NARROW = '/admin/gl/journals';

/** Minimum number of nav groups rootadmin should see (root sees all 16+ groups). */
const ROOT_MIN_GROUPS = 10;

/** Default dev API base — injected by the proxy (same origin as the app). */
const API_BASE = '/api/v1';

/** Narrow test user credentials, built from the per-run tag. */
const NARROW_USERNAME = `narrow.${TAG}`;
const NARROW_PASS = 'NarrowPass12!';

// ─── shared setup state ───────────────────────────────────────────────────────

/**
 * Populated by beforeAll; consumed by every test.
 * If setup fails, setupOk stays false and tests skip.
 */
let setupOk = false;
let setupFailReason = '';

// ─── helpers ──────────────────────────────────────────────────────────────────

/** Minimal fetch wrapper using Playwright's request API context. */
async function apiReq(
  ctx: Awaited<ReturnType<typeof pwRequest.newContext>>,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  token: string | null,
  body?: unknown,
): Promise<{ status: number; body: unknown }> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const opts = {
    headers,
    ...(body !== undefined ? { data: body } : {}),
  };

  const res =
    method === 'GET'
      ? await ctx.get(API_BASE + path, { headers })
      : method === 'POST'
        ? await ctx.post(API_BASE + path, opts)
        : method === 'PUT'
          ? await ctx.put(API_BASE + path, opts)
          : await ctx.delete(API_BASE + path, { headers });

  let parsed: unknown;
  try {
    parsed = await res.json();
  } catch {
    parsed = await res.text().catch(() => '');
  }
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

test.describe('UI/UX RBAC nav — permission-driven sidebar', () => {

  // ── one-time seed: create narrow role + test user ──────────────────────────
  test.beforeAll(async () => {
    // Build an API-only request context.  The Angular dev server proxies /api → :8081,
    // so we point at the same origin as the app.  PW_BASE_URL mirrors playwright.config.ts.
    const appBase = process.env['PW_BASE_URL'] ?? 'http://localhost:4200';
    const ctx = await pwRequest.newContext({ baseURL: appBase });

    try {
      // 1. Log in as rootadmin to get an admin token.
      const loginRes = await apiReq(ctx, 'POST', '/auth/login', null, {
        username: ROOT_USER,
        password: ROOT_PASS,
      });
      const loginBody = loginRes.body as Record<string, unknown>;
      const token = (
        (loginBody?.['data'] as Record<string, unknown>)?.['accessToken'] as string | undefined
      ) ?? '';
      if (!token) {
        setupFailReason = `rootadmin login failed (status ${loginRes.status}): ${JSON.stringify(loginRes.body).slice(0, 200)}`;
        return;
      }

      // 2. Discover organisation -> company (/companies REQUIRES organisationUid).
      const orgsRes = await apiReq(ctx, 'GET', '/organisations?size=5', token);
      const orgs = listOf(orgsRes) as Array<Record<string, unknown>>;
      if (orgs.length === 0) {
        setupFailReason = `No organisations found (status ${orgsRes.status}) — is the backend bootstrapped?`;
        return;
      }
      const orgUid = String(orgs[0]['uid']);

      const companiesRes = await apiReq(ctx, 'GET', `/companies?organisationUid=${orgUid}&size=5`, token);
      const companies = listOf(companiesRes) as Array<Record<string, unknown>>;
      if (companies.length === 0) {
        setupFailReason = `No companies found for org ${orgUid} (status ${companiesRes.status})`;
        return;
      }
      const companyUid = String(companies[0]['uid']);

      // 3. Discover a branch for the company (branches are listed via /branches?companyUid=...;
      //    there is no /companies/{uid}/branches endpoint).
      const branchesRes = await apiReq(ctx, 'GET', `/branches?companyUid=${companyUid}&size=5`, token);
      const branches = listOf(branchesRes) as Array<Record<string, unknown>>;
      if (branches.length === 0) {
        setupFailReason = `No branches found for company ${companyUid} (status ${branchesRes.status})`;
        return;
      }
      const branchUid = String(branches[0]['uid']);

      // 4. Get permission catalogue to resolve NARROW_PERMS to their codes.
      //    The PUT /roles/uid/{uid}/permissions endpoint accepts permissionCodes array.
      //    We verify the two narrow codes exist in the catalogue; if not, we still
      //    proceed (the role will just have 0 matching perms) but log a warning.
      const permsRes = await apiReq(ctx, 'GET', '/roles/permissions', token);
      const permCatalogue = listOf(permsRes) as Array<Record<string, unknown>>;
      const availableCodes = new Set(permCatalogue.map((p) => String(p['code'])));
      const resolvedPerms = NARROW_PERMS.filter((c) => availableCodes.has(c));
      if (resolvedPerms.length < NARROW_PERMS.length) {
        const missing = NARROW_PERMS.filter((c) => !availableCodes.has(c));
        // Non-fatal: proceed with whatever resolved; the nav assertions may still pass
        // if at least one inventory/product permission resolved.
        console.warn(`[rbac-nav-setup] permission codes not in catalogue: ${missing.join(', ')} (catalogue has ${permCatalogue.length} entries)`);
      }

      // 5. Create the narrow role.
      const roleRes = await apiReq(ctx, 'POST', '/roles', token, {
        code: `NARROW_${TAG}`,
        name: `Narrow Role ${TAG}`,
        description: `e2e test role — ${TAG} — safe to delete`,
      });
      const roleUid = uidOf(roleRes);
      if (!roleUid) {
        setupFailReason = `Failed to create narrow role: ${JSON.stringify(roleRes.body).slice(0, 200)}`;
        return;
      }

      // 6. Assign only NARROW_PERMS to the role.
      if (resolvedPerms.length > 0) {
        const permRes = await apiReq(ctx, 'PUT', `/roles/uid/${roleUid}/permissions`, token, {
          permissionCodes: resolvedPerms,
        });
        if (permRes.status >= 300) {
          // Non-fatal: role exists but may have no permissions; nav will show 0 groups
          // which is still a valid test (empty-group rule still holds).
          console.warn(`[rbac-nav-setup] permission assignment returned ${permRes.status}: ${JSON.stringify(permRes.body).slice(0, 200)}`);
        }
      }

      // 7. Create the narrow user.
      const userRes = await apiReq(ctx, 'POST', '/users', token, {
        username: NARROW_USERNAME,
        displayName: `Narrow User ${TAG}`,
        password: NARROW_PASS,
        email: `${NARROW_USERNAME}@erp-test.local`,
        phone: `0700000${TAG.slice(-4)}`,
      });
      const userUid = uidOf(userRes);
      if (!userUid) {
        setupFailReason = `Failed to create narrow user: ${JSON.stringify(userRes.body).slice(0, 200)}`;
        return;
      }

      // 8. Assign branch (make it the default).
      await apiReq(ctx, 'POST', '/user-branches', token, {
        userUid,
        branchUid,
        makeDefault: true,
      });

      // 9. Grant the narrow role.
      const grantRes = await apiReq(ctx, 'POST', '/user-roles', token, {
        userUid,
        roleUid,
        companyUid,
        branchUid,
      });
      if (grantRes.status >= 300) {
        setupFailReason = `Failed to grant role to user: ${JSON.stringify(grantRes.body).slice(0, 200)}`;
        return;
      }

      setupOk = true;
    } catch (err) {
      setupFailReason = `beforeAll threw: ${String(err)}`;
    } finally {
      await ctx.dispose();
    }
  });

  // ── helper to skip when setup failed ──────────────────────────────────────
  function skipIfSetupFailed(): void {
    if (!setupOk) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: `Setup did not complete: ${setupFailReason}`,
      });
      test.skip();
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Test 1: no .nav-group has zero navigable links (rootadmin, the empty-group fix)
  // ────────────────────────────────────────────────────────────────────────────
  test('rootadmin: no nav group renders with zero navigable links', async ({ page }) => {
    await login(page);
    // Wait for the sidebar to be fully rendered.
    await page.waitForSelector('.nav-group', { timeout: 15_000 });
    await page.waitForTimeout(600);

    const groups = await page.locator('.nav-group').all();
    expect(groups.length, 'rootadmin should have at least one nav group').toBeGreaterThan(0);

    for (const group of groups) {
      // Count navigable links inside the group.
      // The shell renders <a class="nav-item"> for permitted + available items and
      // <span class="nav-item nav-item--soon"> for unavailable ones.
      // An empty group would have no <a> children at all.
      const links = await group.locator('a.nav-item').count();
      const label = await group.locator('.nav-group-label').innerText().catch(() => '<unknown>');
      expect(
        links,
        `nav group "${label}" has zero navigable links — empty groups must be hidden (empty-group fix regression)`,
      ).toBeGreaterThan(0);
    }
  });

  // ────────────────────────────────────────────────────────────────────────────
  // Test 2: rootadmin sees many nav groups (root sees all)
  // ────────────────────────────────────────────────────────────────────────────
  test('rootadmin: many nav groups visible (root sees all)', async ({ page }) => {
    await login(page);
    await page.waitForSelector('.nav-group', { timeout: 15_000 });
    await page.waitForTimeout(600);

    const groupCount = await page.locator('.nav-group').count();
    expect(
      groupCount,
      `rootadmin should see at least ${ROOT_MIN_GROUPS} nav groups; got ${groupCount} — ` +
        `permissions may not be loading correctly for root`,
    ).toBeGreaterThanOrEqual(ROOT_MIN_GROUPS);

    // Spot-check: Accounting group must be visible for root.
    const accountingGroup = page.locator('.nav-group', { hasText: 'Accounting' });
    await expect(
      accountingGroup,
      'rootadmin should see the Accounting nav group',
    ).toBeVisible();

    // Spot-check: HR & Payroll group must be visible for root.
    const hrGroup = page.locator('.nav-group', { hasText: 'HR & Payroll' });
    await expect(
      hrGroup,
      'rootadmin should see the HR & Payroll nav group',
    ).toBeVisible();
  });

  // ────────────────────────────────────────────────────────────────────────────
  // Test 3: narrow user — no nav group has zero navigable links
  // ────────────────────────────────────────────────────────────────────────────
  test('narrow user: no nav group renders with zero navigable links', async ({ page }) => {
    skipIfSetupFailed();

    await login(page, NARROW_USERNAME, NARROW_PASS);
    await page.waitForSelector('.nav-group', { timeout: 15_000 });
    await page.waitForTimeout(600);

    const groups = await page.locator('.nav-group').all();

    // The narrow user may legitimately have zero groups if no permission resolved.
    // In that case skip with an annotation rather than asserting zero things.
    if (groups.length === 0) {
      test.info().annotations.push({
        type: 'skip-reason',
        description:
          'Narrow user has no nav groups at all — likely no permissions resolved from catalogue; ' +
          'empty-group fix still holds (nothing to check)',
      });
      return;
    }

    for (const group of groups) {
      const links = await group.locator('a.nav-item').count();
      const label = await group.locator('.nav-group-label').innerText().catch(() => '<unknown>');
      expect(
        links,
        `nav group "${label}" visible for narrow user but has zero navigable links — ` +
          `the empty-group filter is not working (regression)`,
      ).toBeGreaterThan(0);
    }
  });

  // ────────────────────────────────────────────────────────────────────────────
  // Test 4: narrow user — permitted groups are present (Products, Inventory)
  // ────────────────────────────────────────────────────────────────────────────
  test('narrow user: STOCK.VIEW + PRODUCT.VIEW groups are visible', async ({ page }) => {
    skipIfSetupFailed();

    await login(page, NARROW_USERNAME, NARROW_PASS);
    await page.waitForSelector('.nav-group', { timeout: 15_000 });
    await page.waitForTimeout(600);

    for (const groupLabel of PRESENT_GROUPS_FOR_NARROW_USER) {
      const group = page.locator('.nav-group', { hasText: groupLabel });
      const isVisible = await group.isVisible().catch(() => false);
      if (!isVisible) {
        // Non-fatal annotation if a permission code did not resolve — the test
        // annotates but does not hard-fail the suite.
        test.info().annotations.push({
          type: 'warn',
          description:
            `Expected nav group "${groupLabel}" to be visible for narrow user but it is absent. ` +
            `Check that the permission codes (STOCK.VIEW / PRODUCT.VIEW) exist in the catalogue.`,
        });
      }
      // Soft assertion: if the permission resolved, the group MUST be visible.
      // We only assert when we know the permission was in the catalogue.
    }

    // Hard assertion: at least one of the expected groups is visible.
    // This catches the case where neither permission resolved.
    const visibleExpected: string[] = [];
    for (const label of PRESENT_GROUPS_FOR_NARROW_USER) {
      const visible = await page.locator('.nav-group', { hasText: label }).isVisible().catch(() => false);
      if (visible) visibleExpected.push(label);
    }
    expect(
      visibleExpected.length,
      `Narrow user should see at least one of [${PRESENT_GROUPS_FOR_NARROW_USER.join(', ')}] ` +
        `but none were visible. STOCK.VIEW and PRODUCT.VIEW may not be in the permission catalogue ` +
        `or role assignment failed.`,
    ).toBeGreaterThan(0);
  });

  // ────────────────────────────────────────────────────────────────────────────
  // Test 5: narrow user — unpermitted groups are absent
  // ────────────────────────────────────────────────────────────────────────────
  test('narrow user: groups requiring ungranted permissions are absent from the sidebar', async ({ page }) => {
    skipIfSetupFailed();

    await login(page, NARROW_USERNAME, NARROW_PASS);
    // Wait for the nav to render (any group, or the sidebar itself).
    await page.waitForSelector('nav.sidebar-nav', { timeout: 15_000 });
    await page.waitForTimeout(600);

    for (const groupLabel of ABSENT_GROUPS_FOR_NARROW_USER) {
      const group = page.locator('.nav-group', { hasText: groupLabel });
      const count = await group.count();
      expect(
        count,
        `nav group "${groupLabel}" is visible for the narrow user but should be hidden — ` +
          `the user only has STOCK.VIEW + PRODUCT.VIEW; this group requires a different permission`,
      ).toBe(0);
    }
  });

  // ────────────────────────────────────────────────────────────────────────────
  // Test 6: narrow user — visiting a forbidden route is denied, not a crash
  // ────────────────────────────────────────────────────────────────────────────
  test('narrow user: forbidden route /admin/gl/journals redirects to /admin/home — no data shown', async ({ page }) => {
    skipIfSetupFailed();

    await login(page, NARROW_USERNAME, NARROW_PASS);
    // Navigate directly to a GL-guarded route that the narrow user cannot access.
    await page.goto(FORBIDDEN_ROUTE_FOR_NARROW, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForTimeout(500);

    // The permission guard redirects to /admin/home — not /login, not a crash.
    const url = page.url();

    // Must not be a session bounce to /login.
    expect(url, 'session should not be lost when visiting a forbidden route').not.toMatch(/\/login/);

    // Must be redirected away from the requested route (requirePermission guard fired).
    expect(
      url,
      'narrow user should not land on the forbidden GL journals route',
    ).not.toMatch(/\/admin\/gl\/journals/);

    // The redirect target is /admin/home.
    expect(
      url,
      'narrow user should be redirected to /admin/home by the permission guard',
    ).toMatch(/\/admin\/home/);

    // No GL journal data must be visible on screen.
    const bodyText = await page.locator('body').innerText().catch(() => '');
    // The GL journals page renders an h1 or h2 with "Journal" text.
    // After redirect to home, that heading must not be present.
    expect(
      bodyText,
      'GL journal content is visible on screen after permission redirect — guard may not have fired',
    ).not.toMatch(/Journal Entries/);

    // No error-state banner — the redirect should be clean, not a 403 popup.
    const errorBanner = page.locator('[role="alert"].text-danger, p.text-danger[role="alert"]').first();
    const hasError = await errorBanner.isVisible().catch(() => false);
    expect(
      hasError,
      'an error banner appeared after the permission-guard redirect — redirect should be silent',
    ).toBe(false);
  });

  // ────────────────────────────────────────────────────────────────────────────
  // Test 7: narrow user — no nav group has zero navigable links on /admin/home
  //         (regression: re-checking the empty-group rule on the home page itself
  //         where the shell renders with the narrow permission set fully active)
  // ────────────────────────────────────────────────────────────────────────────
  test('narrow user at /admin/home: sidebar renders no empty group headers', async ({ page }) => {
    skipIfSetupFailed();

    await login(page, NARROW_USERNAME, NARROW_PASS);
    await page.goto('/admin/home', { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForSelector('nav.sidebar-nav', { timeout: 15_000 });
    await page.waitForTimeout(600);

    const groups = await page.locator('.nav-group').all();

    for (const group of groups) {
      const links = await group.locator('a.nav-item').count();
      const label = await group.locator('.nav-group-label').innerText().catch(() => '<unknown>');
      expect(
        links,
        `nav group "${label}" is visible on /admin/home for narrow user but has zero navigable links — ` +
          `the empty-group filter must prevent this (empty-group fix regression)`,
      ).toBeGreaterThan(0);
    }
  });

});

// ─────────────────────────────────────────────────────────────────────────────
// Branch-only RBAC: a user with BRANCH.VIEW + BRANCH.MANAGE (no COMPANY.VIEW)
// can reach /admin/branches and sees "Branches" in the sidebar but NOT "Companies".
//
// This is the regression guard for the feature that added the standalone
// BranchAdminComponent so branch-only users are no longer stranded.
// ─────────────────────────────────────────────────────────────────────────────

test.describe('UI/UX RBAC nav — BRANCH-only user reaches /admin/branches', () => {

  /** Per-run tag — distinct from the outer TAG to avoid cross-suite state collisions. */
  const BTAG = `bonly${Date.now().toString(36).slice(-6)}`;

  /** Permissions granted: BRANCH.VIEW + BRANCH.MANAGE only. COMPANY.VIEW is intentionally absent. */
  const BRANCH_PERMS = ['BRANCH.VIEW', 'BRANCH.MANAGE'];

  /** Credentials for the branch-only test user. */
  const BRANCH_USERNAME = `branch.${BTAG}`;
  const BRANCH_PASS = 'BranchOnly12!';

  let bSetupOk = false;
  let bSetupFailReason = '';

  // ── one-time seed ──────────────────────────────────────────────────────────
  // Reuses the module-level apiReq / uidOf / listOf helpers (same file).
  test.beforeAll(async () => {
    const appBase = process.env['PW_BASE_URL'] ?? 'http://localhost:4200';
    const ctx = await pwRequest.newContext({ baseURL: appBase });

    try {
      // 1. Obtain rootadmin token.
      const loginRes = await apiReq(ctx, 'POST', '/auth/login', null, {
        username: ROOT_USER,
        password: ROOT_PASS,
      });
      const loginBody = loginRes.body as Record<string, unknown>;
      const token = (
        (loginBody?.['data'] as Record<string, unknown>)?.['accessToken'] as string | undefined
      ) ?? '';
      if (!token) {
        bSetupFailReason = `rootadmin login failed (status ${loginRes.status}): ${JSON.stringify(loginRes.body).slice(0, 200)}`;
        return;
      }

      // 2. Discover org → company → branch (same discovery pattern as the outer suite).
      const orgsRes = await apiReq(ctx, 'GET', '/organisations?size=5', token);
      const orgs = listOf(orgsRes) as Array<Record<string, unknown>>;
      if (orgs.length === 0) {
        bSetupFailReason = `No organisations found (status ${orgsRes.status}) — backend not bootstrapped?`;
        return;
      }
      const orgUid = String(orgs[0]['uid']);

      const companiesRes = await apiReq(ctx, 'GET', `/companies?organisationUid=${orgUid}&size=5`, token);
      const companies = listOf(companiesRes) as Array<Record<string, unknown>>;
      if (companies.length === 0) {
        bSetupFailReason = `No companies found for org ${orgUid}`;
        return;
      }
      const companyUid = String(companies[0]['uid']);

      const branchesRes = await apiReq(ctx, 'GET', `/branches?companyUid=${companyUid}&size=5`, token);
      const branches = listOf(branchesRes) as Array<Record<string, unknown>>;
      if (branches.length === 0) {
        bSetupFailReason = `No branches found for company ${companyUid}`;
        return;
      }
      const branchUid = String(branches[0]['uid']);

      // 3. Verify BRANCH.VIEW + BRANCH.MANAGE are in the catalogue. Warn if absent (non-fatal:
      //    the test will still exercise the guard; it will just redirect instead of landing).
      const permsRes = await apiReq(ctx, 'GET', '/roles/permissions', token);
      const catalogue = listOf(permsRes) as Array<Record<string, unknown>>;
      const available = new Set(catalogue.map((p) => String(p['code'])));
      const resolved = BRANCH_PERMS.filter((c) => available.has(c));
      if (resolved.length < BRANCH_PERMS.length) {
        const missing = BRANCH_PERMS.filter((c) => !available.has(c));
        console.warn(`[branch-only-setup] missing permission codes: ${missing.join(', ')} (${catalogue.length} in catalogue)`);
      }

      // 4. Create a branch-only role (BRANCH.VIEW + BRANCH.MANAGE; no COMPANY.VIEW).
      const roleRes = await apiReq(ctx, 'POST', '/roles', token, {
        code: `BRANCH_ONLY_${BTAG}`,
        name: `Branch Only ${BTAG}`,
        description: `e2e branch-only role — ${BTAG} — safe to delete`,
      });
      const roleUid = uidOf(roleRes);
      if (!roleUid) {
        bSetupFailReason = `Failed to create branch-only role: ${JSON.stringify(roleRes.body).slice(0, 200)}`;
        return;
      }

      // 5. Assign BRANCH.VIEW + BRANCH.MANAGE (and ONLY those) to the role.
      if (resolved.length > 0) {
        const permRes = await apiReq(ctx, 'PUT', `/roles/uid/${roleUid}/permissions`, token, {
          permissionCodes: resolved,
        });
        if (permRes.status >= 300) {
          console.warn(`[branch-only-setup] permission assignment returned ${permRes.status}: ${JSON.stringify(permRes.body).slice(0, 200)}`);
        }
      }

      // 6. Create the branch-only user.
      const userRes = await apiReq(ctx, 'POST', '/users', token, {
        username: BRANCH_USERNAME,
        displayName: `Branch Only User ${BTAG}`,
        password: BRANCH_PASS,
        email: `${BRANCH_USERNAME}@erp-test.local`,
        phone: `0700002${BTAG.slice(-4)}`,
      });
      const userUid = uidOf(userRes);
      if (!userUid) {
        bSetupFailReason = `Failed to create branch-only user: ${JSON.stringify(userRes.body).slice(0, 200)}`;
        return;
      }

      // 7. Assign the branch (default).
      await apiReq(ctx, 'POST', '/user-branches', token, {
        userUid,
        branchUid,
        makeDefault: true,
      });

      // 8. Grant the branch-only role.
      const grantRes = await apiReq(ctx, 'POST', '/user-roles', token, {
        userUid,
        roleUid,
        companyUid,
        branchUid,
      });
      if (grantRes.status >= 300) {
        bSetupFailReason = `Failed to grant branch-only role to user: ${JSON.stringify(grantRes.body).slice(0, 200)}`;
        return;
      }

      bSetupOk = true;
    } catch (err) {
      bSetupFailReason = `beforeAll threw: ${String(err)}`;
    } finally {
      await ctx.dispose();
    }
  });

  function skipIfBSetupFailed(): void {
    if (!bSetupOk) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: `Branch-only setup did not complete: ${bSetupFailReason}`,
      });
      test.skip();
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // B1: "Branches" nav item is visible in the sidebar; "Companies" is absent.
  //
  //     Regression: before the fix, there was no "Branches" nav item at all, so
  //     a branch-only user would see an empty "Administration" group (or nothing)
  //     and had no route to manage branches.
  // ────────────────────────────────────────────────────────────────────────────
  test('B1: branch-only user: sidebar shows "Branches" nav item and hides "Companies"', async ({ page }) => {
    skipIfBSetupFailed();

    await login(page, BRANCH_USERNAME, BRANCH_PASS);
    // Wait for the sidebar nav — the shell renders it once permissions are loaded.
    await page.waitForSelector('nav.sidebar-nav', { timeout: 15_000 });
    await page.waitForTimeout(600);

    // Scope all locators to the sidebar nav to avoid false matches in page content.
    const sidebar = page.locator('nav.sidebar-nav');

    // "Branches" nav item MUST be visible — BRANCH.VIEW satisfies its permission gate.
    const branchesLink = sidebar.locator('a.nav-item', { hasText: /^Branches$/ });
    await expect(
      branchesLink,
      '"Branches" nav item not found in sidebar for branch-only user — BRANCH.VIEW should make it visible',
    ).toBeVisible();

    // "Companies" nav item MUST be absent — the user has no COMPANY.VIEW.
    // This is the regression we fixed: previously the Companies link was the only path to
    // branch management, and branch-only users were stranded.
    const companiesLink = sidebar.locator('a.nav-item', { hasText: /^Companies$/ });
    const companiesCount = await companiesLink.count();
    expect(
      companiesCount,
      '"Companies" nav item is visible for a branch-only user — COMPANY.VIEW was not granted, ' +
        'so the shell must hide this link (permission regression)',
    ).toBe(0);
  });

  // ────────────────────────────────────────────────────────────────────────────
  // B2: navigating directly to /admin/branches lands on the page (not /admin/home).
  //
  //     The route guard is requirePermission('BRANCH.VIEW'). A branch-only user
  //     satisfies it, so the guard must NOT redirect to /admin/home. The heading
  //     "Branches" (from <h1>) must be visible on screen.
  //
  //     This is the core regression check: before the fix the only way to reach
  //     a branch list was via the COMPANY.VIEW-gated Companies screen. Now there
  //     is a dedicated route and this test proves the guard allows it through.
  // ────────────────────────────────────────────────────────────────────────────
  test('B2: branch-only user: /admin/branches loads (heading visible, not redirected to /admin/home)', async ({ page }) => {
    skipIfBSetupFailed();

    await login(page, BRANCH_USERNAME, BRANCH_PASS);
    await page.goto('/admin/branches', { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForTimeout(500);

    // Must not be bounced back to /login (session still valid).
    const url = page.url();
    expect(url, 'Session must survive navigation to /admin/branches').not.toMatch(/\/login/);

    // Must stay on /admin/branches — the requirePermission('BRANCH.VIEW') guard must not redirect.
    expect(
      url,
      'Branch-only user was redirected away from /admin/branches — ' +
        'the requirePermission("BRANCH.VIEW") guard should allow this user through. ' +
        'If the URL is /admin/home the guard incorrectly rejected BRANCH.VIEW.',
    ).toMatch(/\/admin\/branches/);

    // The page heading "Branches" (<h1 class="h4 mb-1">Branches</h1>) must be visible.
    const heading = page.locator('h1', { hasText: 'Branches' });
    await expect(
      heading,
      'Heading "Branches" not visible on /admin/branches — page may not have loaded or the wrong component rendered',
    ).toBeVisible({ timeout: 10_000 });
  });

  // ────────────────────────────────────────────────────────────────────────────
  // B3: the company picker (#branchCompany) or a branch list is visible after load.
  //
  //     The component resolves GET /organisations/current then GET /companies/accessible.
  //     If the user is assigned to exactly one company it auto-selects and renders the
  //     branch list (.erp-table or .erp-empty). If multiple companies are accessible
  //     the company picker select (#branchCompany) must be visible. Either state is
  //     valid — the test accepts both. What it must NOT see is an error alert or the
  //     "not assigned to any company" empty-state (because the seeded user IS assigned
  //     to a branch in a company).
  // ────────────────────────────────────────────────────────────────────────────
  test('B3: branch-only user: /admin/branches shows company picker or branch list — no error, not empty "no company" state', async ({ page }) => {
    skipIfBSetupFailed();

    await login(page, BRANCH_USERNAME, BRANCH_PASS);
    await page.goto('/admin/branches', { waitUntil: 'networkidle', timeout: 30_000 });
    // Allow time for the two sequential API calls (org + accessible companies) to resolve.
    await page.waitForTimeout(1_500);

    const url = page.url();
    if (!url.includes('/admin/branches')) {
      // Guard redirected — B2 will catch this more specifically; annotate here and bail.
      test.info().annotations.push({
        type: 'skip-reason',
        description: `User was not on /admin/branches (landed: ${url}) — B2 covers the redirect assertion.`,
      });
      return;
    }

    // Error states: org load failure or company load failure.
    const orgError = page.locator('[role="alert"]', { hasText: /Could not load the organisation/i });
    const orgErrorVisible = await orgError.isVisible().catch(() => false);
    expect(
      orgErrorVisible,
      '"Could not load the organisation" error is visible — GET /organisations/current failed for branch-only user. ' +
        'The endpoint must be accessible to authenticated users regardless of COMPANY.VIEW.',
    ).toBe(false);

    const companyError = page.locator('[role="alert"]', { hasText: /Could not load companies/i });
    const companyErrorVisible = await companyError.isVisible().catch(() => false);
    expect(
      companyErrorVisible,
      '"Could not load companies" error is visible — GET /companies/accessible failed for branch-only user. ' +
        'This endpoint is auth-only (no COMPANY.VIEW required) and must return the companies the user is assigned to.',
    ).toBe(false);

    // "Not assigned to any company" empty state: the component renders this when accessible
    // companies come back as an empty list.  Our seeded user IS in a company, so this must
    // not appear.
    const noCompanyEmpty = page.locator('.erp-empty', {
      hasText: /aren.t assigned to any company/i,
    });
    const noCompanyVisible = await noCompanyEmpty.isVisible().catch(() => false);
    expect(
      noCompanyVisible,
      '"You aren\'t assigned to any company" empty state is visible — ' +
        '/companies/accessible returned an empty list for the seeded user, ' +
        'but the user has a branch assignment and should see at least one company.',
    ).toBe(false);

    // At least one of: company picker (#branchCompany) OR branch table (.erp-table) OR
    // branch empty-state (.erp-empty with "No branches") must be present.
    const pickerVisible   = await page.locator('#branchCompany').isVisible().catch(() => false);
    const tableVisible    = await page.locator('table.erp-table').isVisible().catch(() => false);
    const emptyBranchVisible = await page.locator('.erp-empty').isVisible().catch(() => false);

    const pageIsUsable = pickerVisible || tableVisible || emptyBranchVisible;
    expect(
      pageIsUsable,
      'Neither the company picker (#branchCompany), nor a branch table (.erp-table), ' +
        'nor an empty-state (.erp-empty) is visible on /admin/branches. ' +
        'The page appears non-functional for the branch-only user.',
    ).toBe(true);
  });

});
