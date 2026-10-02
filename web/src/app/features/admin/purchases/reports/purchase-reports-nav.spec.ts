/**
 * Sidebar entries for the four purchase reports: present for a holder of the gate code, absent
 * without it, and gated on exactly the code of the route guard and the endpoint.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthUser } from '../../../../core/auth/auth.model';
import { SessionStore } from '../../../../core/auth/session.store';
import { ShellComponent } from '../../../../layout/shell/shell.component';

const USER: AuthUser = {
  uid: '01HZZZZZZZZZZZZZZZZZZZZZZZ',
  username: 'buyer',
  displayName: 'Buyer',
  isRoot: false,
  activeCompanyUid: '01HAAAAAAAAAAAAAAAAAAAAAAA',
  activeBranchUid: '01HBBBBBBBBBBBBBBBBBBBBBBB',
  hasBranch: true,
};

const GRN = 'PURCHASE.GOODS_RECEIPT.VIEW';
const PO = 'PURCHASE.ORDER.VIEW';

function navFor(permissions: string[]) {
  const session = TestBed.inject(SessionStore);
  session.setSession('access-token', 'refresh-token', USER);
  session.setPermissions(permissions);
  const fixture = TestBed.createComponent(ShellComponent);
  fixture.detectChanges();
  return fixture.componentInstance.nav().flatMap((g) => g.items);
}

describe('Purchase report navigation', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(SessionStore).clear();
  });

  afterEach(() => TestBed.inject(SessionStore).clear());

  it('shows the receipt reports to a holder of the receipt view code, gated on that code', () => {
    const items = navFor([GRN]);
    const register = items.find((i) => i.route === '/admin/reports/purchases/goods-received');
    const bySupplier = items.find((i) => i.route === '/admin/reports/purchases/by-supplier');
    expect(register?.permission).toBe(GRN);
    expect(bySupplier?.permission).toBe(GRN);
    expect(items.find((i) => i.route === '/admin/reports/purchases/open-orders')).toBeUndefined();
    expect(items.find((i) => i.route === '/admin/reports/purchases/price-variance')).toBeUndefined();
  });

  it('shows Open Purchase Orders on the order view code', () => {
    const items = navFor([PO]);
    expect(items.find((i) => i.route === '/admin/reports/purchases/open-orders')?.permission).toBe(PO);
    expect(items.find((i) => i.route === '/admin/reports/purchases/goods-received')).toBeUndefined();
  });

  it('shows Price Variance only to a holder of BOTH codes', () => {
    expect(navFor([PO]).find((i) => i.route === '/admin/reports/purchases/price-variance')).toBeUndefined();
    const both = navFor([PO, GRN]).find((i) => i.route === '/admin/reports/purchases/price-variance');
    expect(both?.allPermissions).toEqual([PO, GRN]);
  });

  it('hides all four from a user with neither code', () => {
    const routes = navFor(['STOCK.VIEW']).map((i) => i.route);
    expect(routes.filter((r) => r.startsWith('/admin/reports/purchases/'))).toEqual([]);
  });
});
