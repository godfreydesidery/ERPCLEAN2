/**
 * StockLocationService — unit specs.
 *
 * Covers:
 *  1. list() makes GET to /api/v1/stock-locations with page/size params.
 *  2. create() makes POST to /api/v1/stock-locations.
 *  3. deactivate() makes DELETE to /api/v1/stock-locations/uid/:uid.
 *  4. setDefault() makes PATCH to /api/v1/stock-locations/uid/:uid/default.
 */
import { HttpClient } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { StockLocationService } from './stock-location.service';

describe('StockLocationService', () => {
  let service: StockLocationService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(StockLocationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => { httpMock.verify(); TestBed.resetTestingModule(); });

  it('list() GETs /api/v1/stock-locations with page/size', () => {
    service.list(0, 20).subscribe();
    const req = httpMock.expectOne((r) => r.url.includes('/stock-locations') && !r.url.includes('/uid'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    req.flush({ data: [], errors: [] });
  });

  it('create() POSTs to /api/v1/stock-locations', () => {
    const payload = { code: 'WH-01', name: 'Warehouse', locationType: 'WAREHOUSE' as const, branchUid: 'B1', makeDefault: false };
    service.create(payload).subscribe();
    const req = httpMock.expectOne((r) => r.url.includes('/stock-locations') && r.method === 'POST');
    expect(req.request.body).toEqual(payload);
    req.flush({ id: '1', uid: 'LOC1', code: 'WH-01', name: 'Warehouse', locationType: 'WAREHOUSE', isDefault: false, status: 'ACTIVE', companyId: '10', branchId: '5' });
  });

  it('deactivate() DELETEs /api/v1/stock-locations/uid/:uid', () => {
    service.deactivate('LOC1').subscribe();
    const req = httpMock.expectOne((r) => r.url.includes('/stock-locations/uid/LOC1') && r.method === 'DELETE');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('setDefault() PATCHes /api/v1/stock-locations/uid/:uid/default', () => {
    service.setDefault('LOC1').subscribe();
    const req = httpMock.expectOne((r) => r.url.includes('/stock-locations/uid/LOC1/default'));
    expect(req.request.method).toBe('PATCH');
    req.flush({ id: '1', uid: 'LOC1', code: 'WH-01', name: 'Warehouse', locationType: 'WAREHOUSE', isDefault: true, status: 'ACTIVE', companyId: '10', branchId: '5' });
  });
});
