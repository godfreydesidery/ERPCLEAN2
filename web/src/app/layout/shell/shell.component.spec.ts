import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ShellComponent } from './shell.component';
import { SessionStore } from '../../core/auth/session.store';
import { AuthUser } from '../../core/auth/auth.model';

/**
 * Sidebar navigation contract.
 *
 * The regression this guards: a screen gets built, its route is registered in admin.routes.ts, and
 * nothing in the nav links to it — so to a user the feature does not exist. That is exactly how the
 * client reported "there is no option to receive without a purchase order" and "there is no stock
 * report with opening/purchases/sales/closing" for two features that were already shipped.
 *
 * The second half of the contract is the permission gate: a nav entry visible to someone the backend
 * will 403 reads as a broken app, so every gated entry is asserted BOTH ways — present for a holder
 * of the code, absent for a user without it.
 */

const NON_ROOT_USER: AuthUser = {
  uid: '01HZZZZZZZZZZZZZZZZZZZZZZZ',
  username: 'storekeeper',
  displayName: 'Sam Keeper',
  isRoot: false,
  activeCompanyUid: '01HAAAAAAAAAAAAAAAAAAAAAAA',
  activeBranchUid: '01HBBBBBBBBBBBBBBBBBBBBBBB',
  hasBranch: true,
};

/** Builds the shell with a session holding exactly `permissions` (never root). */
function shellWithPermissions(permissions: readonly string[]) {
  const session = TestBed.inject(SessionStore);
  session.setSession('access-token', 'refresh-token', NON_ROOT_USER);
  session.setPermissions([...permissions]);
  const fixture = TestBed.createComponent(ShellComponent);
  fixture.detectChanges();
  return fixture;
}

/** Every nav item across every visible group, flattened. */
function navItems(fixture: ReturnType<typeof shellWithPermissions>) {
  return fixture.componentInstance.nav().flatMap((g) => g.items.map((i) => ({ ...i, group: g.label })));
}

function routes(fixture: ReturnType<typeof shellWithPermissions>): string[] {
  return navItems(fixture).map((i) => i.route);
}

/** Route hrefs actually rendered into the sidebar DOM (not just the model). */
function renderedNavHrefs(fixture: ReturnType<typeof shellWithPermissions>): string[] {
  const links: HTMLAnchorElement[] = Array.from(
    fixture.nativeElement.querySelectorAll('.sidebar-nav a.nav-item'),
  );
  return links.map((a) => a.getAttribute('href') ?? '');
}

describe('ShellComponent — sidebar navigation', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(SessionStore).clear();
  });

  afterEach(() => {
    TestBed.inject(SessionStore).clear();
  });

  // ── Stock movement report (K9) — INVENTORY.VALUATION.VIEW ──────────────────

  it('shows the opening/closing stock report to a user with INVENTORY.VALUATION.VIEW', () => {
    const fixture = shellWithPermissions(['INVENTORY.VALUATION.VIEW']);
    expect(routes(fixture)).toContain('/admin/reports/stock-movement');
  });

  it('renders the opening/closing stock report as a real sidebar link', () => {
    const fixture = shellWithPermissions(['INVENTORY.VALUATION.VIEW']);
    expect(renderedNavHrefs(fixture)).toContain('/admin/reports/stock-movement');
  });

  it('labels the opening/closing stock report in the words the client uses', () => {
    const fixture = shellWithPermissions(['INVENTORY.VALUATION.VIEW']);
    const item = navItems(fixture).find((i) => i.route === '/admin/reports/stock-movement');
    expect(item?.label).toBe('Stock Report (Opening & Closing)');
  });

  it('hides the opening/closing stock report from a user without INVENTORY.VALUATION.VIEW', () => {
    const fixture = shellWithPermissions(['STOCK.VIEW']);
    expect(routes(fixture)).not.toContain('/admin/reports/stock-movement');
    expect(renderedNavHrefs(fixture)).not.toContain('/admin/reports/stock-movement');
  });

  it('gates the opening/closing stock report on the same code as its route guard', () => {
    const fixture = shellWithPermissions(['INVENTORY.VALUATION.VIEW']);
    const item = navItems(fixture).find((i) => i.route === '/admin/reports/stock-movement');
    expect(item?.permission).toBe('INVENTORY.VALUATION.VIEW');
  });

  // ── Product List / Stock Value registers — INVENTORY.VALUATION.VIEW ───────

  it('shows the Product List and Stock Value registers to a holder of INVENTORY.VALUATION.VIEW', () => {
    const fixture = shellWithPermissions(['INVENTORY.VALUATION.VIEW']);
    expect(routes(fixture)).toContain('/admin/reports/product-list');
    expect(routes(fixture)).toContain('/admin/reports/stock-value');
    expect(renderedNavHrefs(fixture)).toContain('/admin/reports/product-list');
    expect(renderedNavHrefs(fixture)).toContain('/admin/reports/stock-value');
  });

  it('hides both registers from a user without INVENTORY.VALUATION.VIEW', () => {
    const fixture = shellWithPermissions(['STOCK.VIEW']);
    expect(routes(fixture)).not.toContain('/admin/reports/product-list');
    expect(routes(fixture)).not.toContain('/admin/reports/stock-value');
    expect(renderedNavHrefs(fixture)).not.toContain('/admin/reports/product-list');
    expect(renderedNavHrefs(fixture)).not.toContain('/admin/reports/stock-value');
  });

  it('gates both registers on the same code as their route guards', () => {
    const fixture = shellWithPermissions(['INVENTORY.VALUATION.VIEW']);
    const items = navItems(fixture);
    expect(items.find((i) => i.route === '/admin/reports/product-list')?.permission).toBe(
      'INVENTORY.VALUATION.VIEW',
    );
    expect(items.find((i) => i.route === '/admin/reports/stock-value')?.permission).toBe(
      'INVENTORY.VALUATION.VIEW',
    );
  });

  it('finds the registers in the palette by the words the request arrived in', () => {
    const fixture = shellWithPermissions(['INVENTORY.VALUATION.VIEW']);
    const dests = fixture.componentInstance.searchDestinations();
    expect(dests.find((d) => d.route === '/admin/reports/product-list')?.keywords)
      .toContain('orodha ya bidhaa');
    expect(dests.find((d) => d.route === '/admin/reports/stock-value')?.keywords)
      .toContain('supplier wise');
  });

  // ── Direct goods receipt (K3) — PURCHASE.RECEIVE.DIRECT ───────────────────

  it('shows Receive Without Order to a user with PURCHASE.RECEIVE.DIRECT', () => {
    const fixture = shellWithPermissions(['PURCHASE.RECEIVE.DIRECT']);
    expect(routes(fixture)).toContain('/admin/goods-receipts/direct');
  });

  it('renders Receive Without Order as a real sidebar link in the Purchasing group', () => {
    const fixture = shellWithPermissions(['PURCHASE.RECEIVE.DIRECT']);
    expect(renderedNavHrefs(fixture)).toContain('/admin/goods-receipts/direct');
    const item = navItems(fixture).find((i) => i.route === '/admin/goods-receipts/direct');
    expect(item?.group).toBe('Purchasing');
    // Same wording as the button on the goods-receipt list, so the two read as one action.
    expect(item?.label).toBe('Receive Without Order');
  });

  it('hides Receive Without Order from a user who can only view purchase orders', () => {
    // PURCHASE.ORDER.VIEW alone reaches Goods Receipts but NOT the direct-receipt endpoint.
    const fixture = shellWithPermissions(['PURCHASE.ORDER.VIEW']);
    expect(routes(fixture)).toContain('/admin/goods-receipts');
    expect(routes(fixture)).not.toContain('/admin/goods-receipts/direct');
    expect(renderedNavHrefs(fixture)).not.toContain('/admin/goods-receipts/direct');
  });

  it('gates Receive Without Order on the same code as its route guard', () => {
    const fixture = shellWithPermissions(['PURCHASE.RECEIVE.DIRECT']);
    const item = navItems(fixture).find((i) => i.route === '/admin/goods-receipts/direct');
    expect(item?.permission).toBe('PURCHASE.RECEIVE.DIRECT');
  });

  // ── General nav invariants ────────────────────────────────────────────────

  it('shows nothing permission-gated to a user with no permissions', () => {
    const fixture = shellWithPermissions([]);
    expect(routes(fixture)).toHaveLength(0);
    expect(fixture.componentInstance.nav()).toHaveLength(0);
  });

  it('drops groups whose every item was filtered out', () => {
    const fixture = shellWithPermissions(['PURCHASE.RECEIVE.DIRECT']);
    const groups = fixture.componentInstance.nav();
    expect(groups.every((g) => g.items.length > 0)).toBe(true);
    expect(groups.map((g) => g.label)).toEqual(['Purchasing']);
  });

  it('has no duplicate routes within a group (the @for track key must stay unique)', () => {
    const fixture = shellWithPermissions([]);
    // Root sees everything — the widest possible nav.
    TestBed.inject(SessionStore).setSession('t', 'r', { ...NON_ROOT_USER, isRoot: true });
    fixture.detectChanges();
    for (const group of fixture.componentInstance.nav()) {
      const seen = group.items.map((i) => i.route);
      expect(new Set(seen).size).toBe(seen.length);
    }
  });

  // ── Command palette ───────────────────────────────────────────────────────

  it('exposes hidden search keywords so users find screens by their own words', () => {
    const fixture = shellWithPermissions(['INVENTORY.VALUATION.VIEW', 'PURCHASE.RECEIVE.DIRECT']);
    const dests = fixture.componentInstance.searchDestinations();
    const stock = dests.find((d) => d.route === '/admin/reports/stock-movement');
    const direct = dests.find((d) => d.route === '/admin/goods-receipts/direct');
    expect(stock?.keywords).toContain('opening balance');
    expect(stock?.keywords).toContain('closing balance');
    expect(direct?.keywords).toContain('no lpo');
  });

  it('never leaks a hidden destination into the palette for an unpermitted user', () => {
    const fixture = shellWithPermissions(['PURCHASE.ORDER.VIEW']);
    const paletteRoutes = fixture.componentInstance.searchDestinations().map((d) => d.route);
    expect(paletteRoutes).not.toContain('/admin/goods-receipts/direct');
    expect(paletteRoutes).not.toContain('/admin/reports/stock-movement');
  });
});
