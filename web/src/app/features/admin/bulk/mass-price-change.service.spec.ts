import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { MassPriceChangeService } from './mass-price-change.service';

describe('MassPriceChangeService', () => {
  let service: MassPriceChangeService;
  let httpMock: HttpTestingController;
  const url = `${environment.apiBaseUrl}/prices/mass-change`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MassPriceChangeService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('posts the request body as-is to /prices/mass-change', () => {
    service
      .apply({ priceListUid: 'PL1', type: 'PERCENT', value: 10, roundToDecimals: 2, dryRun: true })
      .subscribe();
    const req = httpMock.expectOne(url);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      priceListUid: 'PL1', type: 'PERCENT', value: 10, roundToDecimals: 2, dryRun: true,
    });
    req.flush({
      priceListUid: 'PL1', priceListCode: 'RETAIL', priceListName: 'Retail Price',
      totalRows: 1, affected: 1, dryRun: true, samples: [],
    });
  });
});
