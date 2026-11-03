/**
 * Accessibility gate — RestaurantDashboardComponent, populated.
 */
import { TestBed } from '@angular/core/testing';
import { assertA11y } from '../../../../testing/a11y.helper';
import { RestaurantDashboardComponent } from './restaurant-dashboard.component';
import {
  DASHBOARD_PERMS,
  dashboardBed,
  stubDashboardService,
} from './restaurant-dashboard.testing';

describe('RestaurantDashboardComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with the dashboard loaded', async () => {
    dashboardBed(DASHBOARD_PERMS, stubDashboardService());
    const fixture = TestBed.createComponent(RestaurantDashboardComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
