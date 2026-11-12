/**
 * RestaurantSetupComponent specs (ADR-0065).
 *
 * Covers: loads the active branch's setup; routing rows merge product categories with saved routes;
 * saving routing sends only routed rows; table validation; items and their departments (search,
 * filter, single and bulk department changes, new departments routed first); 403 → forbidden; axe.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { assertA11y } from '../../../../testing/a11y.helper';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { RestaurantService } from './restaurant.service';
import { RestaurantSetupComponent } from './restaurant-setup.component';

const kitchen = {
  id: '1', uid: 'S1', branchId: '2', name: 'Kitchen', printerHost: '192.168.1.50', printerPort: 9100,
  defaultStation: true, status: 'ACTIVE' as const,
};
const bar = { ...kitchen, id: '2', uid: 'S2', name: 'Bar', printerHost: null, defaultStation: false };
const table = {
  id: '9', uid: 'T1', branchId: '2', name: 'T1', seats: 4, area: 'Inside', sortOrder: 1,
  status: 'ACTIVE' as const, state: 'OCCUPIED' as const, liveOrderCount: 1, readyItemCount: 0,
};

function makeBed(opts: { tablesError?: unknown } = {}) {
  const restaurant = {
    listTables: vi.fn(() => (opts.tablesError ? throwError(() => opts.tablesError) : of([table]))),
    listStations: vi.fn(() => of([kitchen, bar])),
    listCategoryRoutes: vi.fn(() => of([
      { uid: 'R1', category: 'Drinks', prepStationUid: 'S2', prepStationName: 'Bar' },
    ])),
    listProductCategories: vi.fn(() => of(['Food', 'drinks'])),
    listModifierGroups: vi.fn(() => of([])),
    replaceCategoryRoutes: vi.fn(() => of([])),
    createTable: vi.fn(() => of(table)),
    updateTable: vi.fn(() => of(table)),
    createStation: vi.fn(() => of(kitchen)),
    updateStation: vi.fn(() => of(kitchen)),
    setCategoryRoute: vi.fn((category: string, uid: string) =>
      of({ uid: 'R9', category, prepStationUid: uid, prepStationName: uid === 'S2' ? 'Bar' : 'Kitchen' })),
  };
  TestBed.configureTestingModule({
    imports: [RestaurantSetupComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: RestaurantService, useValue: restaurant },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({
            rows: [
              { uid: 'P1', code: 'J1', name: 'Passion juice', sellable: true, status: 'ACTIVE', category: null },
              { uid: 'P2', code: 'F1', name: 'Tacos', sellable: true, status: 'ACTIVE', category: 'Food' },
              { uid: 'P3', code: 'D1', name: 'Latte', sellable: true, status: 'ACTIVE', category: 'Drinks' },
              { uid: 'P4', code: 'X1', name: 'Old menu', sellable: true, status: 'INACTIVE', category: 'Food' },
            ],
            meta: { page: 0, size: 200, totalElements: 4, totalPages: 1, hasNext: false },
          })),
          setCategory: vi.fn(() => of({})),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      {
        provide: AuthService,
        useValue: {
          myBranches: vi.fn(() => of([
            { id: '1', uid: 'UB1', userUid: 'U1', branchUid: 'BR1', branchCode: 'B1', branchName: 'Cafe',
              companyUid: 'CO1', isDefault: true, assignedAt: '' },
          ])),
        },
      },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true), isAuthenticated: signal(true), user: signal(null),
          permissions: signal([]), activeBranchUid: signal('BR1'),
        },
      },
    ],
  });
  return restaurant;
}

describe('RestaurantSetupComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('loads the active branch and merges categories with saved routes', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;

    expect(restaurant.listTables).toHaveBeenCalledWith('BR1');
    expect(c.state()).toBe('idle');
    // "drinks" from products and the saved "Drinks" route are the same category.
    expect(c.routeRows()).toEqual([
      { category: 'drinks', stationUid: 'S2' },
      { category: 'Food', stationUid: '' },
    ]);
    expect(c.defaultStation()?.name).toBe('Kitchen');
  });

  it('saves only the routed categories', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    fixture.componentInstance.saveRoutes();
    expect(restaurant.replaceCategoryRoutes).toHaveBeenCalledWith({
      branchUid: 'BR1',
      routes: [{ category: 'drinks', prepStationUid: 'S2' }],
    });
  });

  it('refuses a table with no name and does not call the API', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.tableName.set('   ');
    c.saveTable();
    expect(c.tableError()).toContain('name');
    expect(restaurant.createTable).not.toHaveBeenCalled();
  });

  it('adds a table to the chosen branch', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.tableName.set(' T2 ');
    c.tableSeats.set(2);
    c.saveTable();
    expect(restaurant.createTable).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'T2', seats: 2, branchUid: 'BR1' }));
  });

  it('adds a run of tables, skipping names that already exist', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.bulkPrefix.set('T');
    c.bulkFrom.set(1);
    c.bulkTo.set(3);
    c.addTables();
    const names = restaurant.createTable.mock.calls.map((call: unknown[]) => (call[0] as { name: string }).name);
    expect(names).toEqual(['T2', 'T3']);
  });

  // ── Items and their departments (Tulia 2026-11-12) ──

  function products() {
    return TestBed.inject(ProductService) as unknown as { setCategory: ReturnType<typeof vi.fn> };
  }

  it('lists every active sellable item with where it prints in this branch', () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.items().map((p) => p.name)).toEqual(['Latte', 'Passion juice', 'Tacos']);
    expect(c.uncategorisedCount()).toBe(1);
    expect(c.printsAt('drinks')).toBe('Bar');
    expect(c.printsAt('Food')).toBe('Default (Kitchen)');
    expect(c.printsAt(null)).toBe('Default (Kitchen)');
  });

  it('filters by search and by department, including "no department"', () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.itemSearch.set('f1');
    expect(c.filteredItems().map((p) => p.name)).toEqual(['Tacos']);
    c.itemSearch.set('');
    c.itemDeptFilter.set(c.noDepartment);
    expect(c.filteredItems().map((p) => p.name)).toEqual(['Passion juice']);
    c.itemDeptFilter.set(c.deptKey(' DRINKS '));
    expect(c.filteredItems().map((p) => p.name)).toEqual(['Latte']);
  });

  it("changes an item's existing department; a routed department needs no station", () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    const tacos = c.items().find((p) => p.uid === 'P2')!;
    c.setDraft('P2', ' Drinks ');
    expect(c.needsStation('Drinks')).toBe(false);
    expect(c.canSaveItem(tacos)).toBe(true);
    c.saveCategory(tacos);
    expect(products().setCategory).toHaveBeenCalledWith('P2', 'Drinks');
    expect(restaurant.setCategoryRoute).not.toHaveBeenCalled();
    expect(c.items().find((p) => p.uid === 'P2')?.category).toBe('Drinks');
  });

  it('a new department needs a station, and is routed in this branch before the item moves', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    const juice = c.items().find((p) => p.uid === 'P1')!;
    c.setDraft('P1', 'Juices');
    expect(c.needsStation('Juices')).toBe(true);
    expect(c.canSaveItem(juice)).toBe(false);

    c.setStationDraft('P1', 'S2');
    c.saveCategory(juice);
    expect(restaurant.setCategoryRoute).toHaveBeenCalledWith('Juices', 'S2', 'BR1');
    expect(products().setCategory).toHaveBeenCalledWith('P1', 'Juices');
    expect(restaurant.setCategoryRoute.mock.invocationCallOrder[0])
      .toBeLessThan(products().setCategory.mock.invocationCallOrder[0]);
  });

  it('an unchanged department cannot be saved', () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    const tacos = c.items().find((p) => p.uid === 'P2')!;
    expect(c.canSaveItem(tacos)).toBe(false);
    c.setDraft('P2', ' Food ');
    expect(c.canSaveItem(tacos)).toBe(false);
  });

  it('moves several items after confirmation, and nothing without it', () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.toggleItem('P1', true);
    c.toggleItem('P2', true);
    c.bulkDept.set('Drinks');

    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false);
    c.applyBulk();
    expect(confirm.mock.calls[0][0]).toContain('same in every branch');
    expect(products().setCategory).not.toHaveBeenCalled();

    confirm.mockReturnValueOnce(true);
    c.applyBulk();
    expect(products().setCategory.mock.calls).toEqual([['P1', 'Drinks'], ['P2', 'Drinks']]);
    expect(c.selected().size).toBe(0);
    confirm.mockRestore();
  });

  it('a bulk move into a new department needs a station first', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.toggleAllShown(true);
    c.bulkDept.set('Grill');
    expect(c.canApplyBulk()).toBe(false);
    c.bulkStation.set('S1');
    expect(c.canApplyBulk()).toBe(true);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    c.applyBulk();
    expect(confirm.mock.calls[0][0]).toContain('print at Kitchen');
    expect(restaurant.setCategoryRoute).toHaveBeenCalledWith('Grill', 'S1', 'BR1');
    expect(products().setCategory).toHaveBeenCalledTimes(3);
    confirm.mockRestore();
  });

  it('shows the forbidden state on 403', () => {
    makeBed({ tablesError: new HttpErrorResponse({ status: 403 }) });
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('forbidden');
  });

  it('has no axe violations', async () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantSetupComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
