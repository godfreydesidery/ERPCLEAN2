/**
 * RestaurantDashboardComponent (2026-11-03): loads on open, KPI tiles with the change against the
 * period before (coloured by whether the move is good), counter sales named, the quick ranges,
 * the flash report download, and the permission gates.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { throwError } from 'rxjs';
import { todayLocal } from '../../../shared/date.util';
import { RestaurantDashboardComponent } from './restaurant-dashboard.component';
import {
  DASHBOARD_PERMS,
  dashboardBed,
  stubDashboardService,
} from './restaurant-dashboard.testing';

function makeBed(
  perms: string[] = DASHBOARD_PERMS,
  overrides: Partial<Record<'dashboard' | 'exportFlashReport', ReturnType<typeof vi.fn>>> = {},
) {
  const stub = stubDashboardService();
  const svc = {
    dashboard: overrides.dashboard ?? vi.fn(stub.dashboard),
    exportFlashReport: overrides.exportFlashReport ?? vi.fn(stub.exportFlashReport),
  };
  dashboardBed(perms, svc);
  return svc;
}

describe('RestaurantDashboardComponent', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('loads today on open and shows the key figures', () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(RestaurantDashboardComponent);
    fixture.detectChanges();

    const today = todayLocal();
    expect(svc.dashboard).toHaveBeenCalledWith({ fromDate: today, toDate: today, branchUid: null });
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Sales');
    expect(text).toContain('120,000.00');
    expect(text).toContain('Spend per guest');
    expect(text).toContain('Tables open');
    expect(text).toContain('41,000.00');
  });

  it('shows the change against the period before; a rise in sales is good, a fall in discounts too', () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantDashboardComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    const sales = c.tiles().find((t) => t.key === 'sales')!;
    const discounts = c.tiles().find((t) => t.key === 'discounts')!;
    const voided = c.tiles().find((t) => t.key === 'voidedItems')!;

    expect(c.fmtChange(sales.change!)).toBe('+20%');
    expect(c.changeClass(sales)).toBe('erp-kpi__delta--up');
    expect(c.fmtChange(discounts.change!)).toBe('−50%');
    expect(c.changeClass(discounts)).toBe('erp-kpi__delta--up');
    // Nothing voided before: no percentage to show.
    expect(voided.change).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('+20%');
  });

  it('names sales made without a table order, lists busy hours that sold, and the complimentary', () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantDashboardComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.waiterRows().map((r) => r.label)).toContain('Counter sales (no table order)');
    expect(c.hourRows().map((r) => r.label)).toEqual(['13:00–14:00', '19:00–20:00']);
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Owner guest');
    expect(text).toContain('Mobile money');
  });

  it('the quick ranges reload with the right dates', () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(RestaurantDashboardComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.setRange('month');
    expect(c.fromDate()).toBe(`${todayLocal().slice(0, 8)}01`);
    expect(c.toDate()).toBe(todayLocal());
    c.setRange('week');
    const from = new Date(c.fromDate());
    const to = new Date(c.toDate());
    expect(Math.round((to.getTime() - from.getTime()) / 86_400_000)).toBe(6);
    expect(svc.dashboard).toHaveBeenCalledTimes(3);
  });

  it('downloads the flash report as PDF or Excel', () => {
    const svc = makeBed();
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const fixture = TestBed.createComponent(RestaurantDashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.exportFlash('XLSX');
    expect(svc.exportFlashReport).toHaveBeenCalledWith(
      expect.objectContaining({ branchUid: null }),
      'XLSX',
    );
  });

  it('offers no download without REPORT.EXPORT', () => {
    makeBed(['SALES.INVOICE.VIEW']);
    const fixture = TestBed.createComponent(RestaurantDashboardComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Flash report PDF');
  });

  it('without SALES.INVOICE.VIEW it loads nothing', () => {
    const svc = makeBed([]);
    const fixture = TestBed.createComponent(RestaurantDashboardComponent);
    fixture.detectChanges();
    expect(svc.dashboard).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain("don't have permission");
  });

  it('a 403 reads as forbidden', () => {
    makeBed(undefined, {
      dashboard: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))),
    });
    const fixture = TestBed.createComponent(RestaurantDashboardComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('forbidden');
  });
});
