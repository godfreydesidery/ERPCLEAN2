/**
 * RestaurantSetupComponent specs (ADR-0065).
 *
 * Covers: loads the active branch's setup; routing rows merge product categories with saved routes;
 * saving routing sends only routed rows; table validation; 403 → forbidden; axe.
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
import { RestaurantService } from './restaurant.service';
import { RestaurantSetupComponent } from './restaurant-setup.component';

const kitchen = {
  id: '1', uid: 'S1', branchId: '2', name: 'Kitchen', printerHost: '192.168.1.50', printerPort: 9100,
  defaultStation: true, status: 'ACTIVE' as const,
};
const bar = { ...kitchen, id: '2', uid: 'S2', name: 'Bar', printerHost: null, defaultStation: false };
const table = {
  id: '9', uid: 'T1', branchId: '2', name: 'T1', seats: 4, area: 'Inside', sortOrder: 1,
  status: 'ACTIVE' as const, state: 'OCCUPIED' as const, liveOrderCount: 1,
};

function makeBed(opts: { tablesError?: unknown } = {}) {
  const restaurant = {
    listTables: vi.fn(() => (opts.tablesError ? throwError(() => opts.tablesError) : of([table]))),
    listStations: vi.fn(() => of([kitchen, bar])),
    listCategoryRoutes: vi.fn(() => of([
      { uid: 'R1', category: 'Drinks', prepStationUid: 'S2', prepStationName: 'Bar' },
    ])),
    listProductCategories: vi.fn(() => of(['Food', 'drinks'])),
    replaceCategoryRoutes: vi.fn(() => of([])),
    createTable: vi.fn(() => of(table)),
    updateTable: vi.fn(() => of(table)),
    createStation: vi.fn(() => of(kitchen)),
    updateStation: vi.fn(() => of(kitchen)),
  };
  TestBed.configureTestingModule({
    imports: [RestaurantSetupComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: RestaurantService, useValue: restaurant },
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
