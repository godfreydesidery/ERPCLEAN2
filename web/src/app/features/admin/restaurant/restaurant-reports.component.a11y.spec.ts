/**
 * Accessibility gate — RestaurantReportsComponent: the empty state, then each of the three
 * reports populated.
 */
import { TestBed } from '@angular/core/testing';
import { assertA11y } from '../../../../testing/a11y.helper';
import { RestaurantReportKind } from './models/restaurant-report.model';
import { REPORT_PERMS, reportsBed, stubReportService } from './restaurant-reports.testing';
import { RestaurantReportsComponent } from './restaurant-reports.component';

describe('RestaurantReportsComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations before a report is run', async () => {
    reportsBed(REPORT_PERMS, stubReportService());
    const fixture = TestBed.createComponent(RestaurantReportsComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  for (const kind of ['waiter-sales', 'bills', 'pending-bills'] as RestaurantReportKind[]) {
    it(`has no axe violations with ${kind} populated`, async () => {
      reportsBed(REPORT_PERMS, stubReportService());
      const fixture = TestBed.createComponent(RestaurantReportsComponent);
      fixture.detectChanges();
      const comp = fixture.componentInstance;
      comp.setKind(kind);
      comp.run();
      fixture.detectChanges();
      await assertA11y(fixture);
    }, 20_000);
  }
});
