/**
 * ProductService — Prices endpoints (ADR-0048 multi-unit / per-pack pricing).
 *
 * Covers:
 *  1. setPrice() posts the request body as-is — unitUid present when set, absent for base.
 *  2. removePrice() appends ?unitUid=<uid> only when a unit is given; omits it for the
 *     base row (back-compatible with the pre-ADR-0048 endpoint).
 */
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { ProductService } from './product.service';

describe('ProductService — prices', () => {
  let service: ProductService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiBaseUrl}/products`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProductService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('setPrice posts unitUid in the body for a per-unit (pack) price', () => {
    service
      .setPrice('PUID1', { priceListUid: 'PL1', price: { amount: '11000.00', currency: 'TZS' }, unitUid: 'U2' })
      .subscribe();
    const req = httpMock.expectOne(`${base}/uid/PUID1/prices`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.unitUid).toBe('U2');
    req.flush({});
  });

  it('setPrice posts a body with no unitUid for the base price (back-compat)', () => {
    service
      .setPrice('PUID1', { priceListUid: 'PL1', price: { amount: '1000.00', currency: 'TZS' } })
      .subscribe();
    const req = httpMock.expectOne(`${base}/uid/PUID1/prices`);
    expect(req.request.method).toBe('POST');
    expect('unitUid' in req.request.body).toBe(false);
    req.flush({});
  });

  it('removePrice appends ?unitUid= for a per-unit (pack) price row', () => {
    service.removePrice('PUID1', 'PL1', 'U2').subscribe();
    const req = httpMock.expectOne(
      (r) => r.url === `${base}/uid/PUID1/prices/PL1` && r.params.get('unitUid') === 'U2',
    );
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('removePrice omits unitUid for the base price row (back-compatible)', () => {
    service.removePrice('PUID1', 'PL1').subscribe();
    const req = httpMock.expectOne(`${base}/uid/PUID1/prices/PL1`);
    expect(req.request.method).toBe('DELETE');
    expect(req.request.params.has('unitUid')).toBe(false);
    req.flush(null);
  });
});
