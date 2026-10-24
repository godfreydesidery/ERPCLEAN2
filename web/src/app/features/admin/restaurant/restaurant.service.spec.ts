/**
 * RestaurantService specs — URLs, verbs and the optional branch parameter (ADR-0065).
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RestaurantService } from './restaurant.service';

const API = '/api/v1/restaurant';

describe('RestaurantService', () => {
  let svc: RestaurantService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), RestaurantService],
    });
    svc = TestBed.inject(RestaurantService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  it('lists tables for the active branch when no branch is given', () => {
    svc.listTables().subscribe();
    const req = http.expectOne((r) => r.url === `${API}/tables`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('branchUid')).toBe(false);
    req.flush([]);
  });

  it('passes the chosen branch', () => {
    svc.listStations('BR1').subscribe();
    const req = http.expectOne((r) => r.url === `${API}/prep-stations`);
    expect(req.request.params.get('branchUid')).toBe('BR1');
    req.flush([]);
  });

  it('PUTs a table update by uid', () => {
    svc.updateTable('T1', { name: 'T1', seats: 6, status: 'ACTIVE' }).subscribe();
    const req = http.expectOne(`${API}/tables/uid/T1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ name: 'T1', seats: 6, status: 'ACTIVE' });
    req.flush({});
  });

  it('replaces the routing in one PUT', () => {
    svc.replaceCategoryRoutes({ branchUid: 'BR1', routes: [{ category: 'Drinks', prepStationUid: 'S2' }] }).subscribe();
    const req = http.expectOne(`${API}/category-routes`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.routes).toEqual([{ category: 'Drinks', prepStationUid: 'S2' }]);
    req.flush([]);
  });

  it('lists live orders, with repeatable status filters', () => {
    svc.listOrders('BR1', ['PAID', 'CANCELLED']).subscribe();
    const req = http.expectOne((r) => r.url === `${API}/orders`);
    expect(req.request.params.getAll('status')).toEqual(['PAID', 'CANCELLED']);
    req.flush([]);
  });

  it('cancels an order', () => {
    svc.cancelOrder('O1', { reason: 'Left' }).subscribe();
    const req = http.expectOne(`${API}/orders/uid/O1/cancel`);
    expect(req.request.method).toBe('POST');
    req.flush({ order: {}, slips: [] });
  });
});
