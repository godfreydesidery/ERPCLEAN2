/**
 * RestaurantReportService — the optional item and department filters (2026-11-21): sent when
 * chosen, left out when not (the mobile app and old links send neither), never on payments.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RestaurantReportFilter } from './models/restaurant-report.model';
import { RestaurantReportService } from './restaurant-report.service';

const API = '/api/v1/reports/restaurant';

const BASE: RestaurantReportFilter = {
  fromDate: '2026-11-01',
  toDate: '2026-11-21',
  waiterUid: null,
  branchUid: null,
};

describe('RestaurantReportService', () => {
  let svc: RestaurantReportService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), RestaurantReportService],
    });
    svc = TestBed.inject(RestaurantReportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  it('leaves the item and department out when not chosen', () => {
    svc.bills({ ...BASE, productUid: null, department: '  ' }).subscribe();
    const req = http.expectOne((r) => r.url === `${API}/bills`);
    expect(req.request.params.has('productUid')).toBe(false);
    expect(req.request.params.has('department')).toBe(false);
    req.flush({});
  });

  it('sends the item and the trimmed department with a report and its export', () => {
    const f = { ...BASE, productUid: 'P1', department: ' Food ' };
    svc.waiterItems(f).subscribe();
    const req = http.expectOne((r) => r.url === `${API}/waiter-items`);
    expect(req.request.params.get('productUid')).toBe('P1');
    expect(req.request.params.get('department')).toBe('Food');
    req.flush({});

    svc.export('pending-summary', f, 'PDF').subscribe();
    const exp = http.expectOne((r) => r.url === `${API}/pending-summary/export`);
    expect(exp.request.params.get('productUid')).toBe('P1');
    expect(exp.request.params.has('fromDate')).toBe(false);
    exp.flush(new Blob());
  });

  it('never sends them for payments', () => {
    svc.payments({ ...BASE, productUid: 'P1', department: 'Food' }).subscribe();
    const req = http.expectOne((r) => r.url === `${API}/payments`);
    expect(req.request.params.has('productUid')).toBe(false);
    expect(req.request.params.has('department')).toBe(false);
    req.flush({});
  });

  it('sends them with the dashboard', () => {
    svc
      .dashboard({ fromDate: '2026-11-21', toDate: '2026-11-21', branchUid: null, department: '(no department)' })
      .subscribe();
    const req = http.expectOne((r) => r.url === `${API}/dashboard`);
    expect(req.request.params.get('department')).toBe('(no department)');
    expect(req.request.params.has('productUid')).toBe(false);
    req.flush({});
  });
});
