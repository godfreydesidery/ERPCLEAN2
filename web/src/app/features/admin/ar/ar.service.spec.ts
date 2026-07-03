/**
 * ArService — unit specs.
 *
 * Covers:
 *  1. getAgeing() GETs /api/v1/ar/ageing/by-customer (not the per-bucket company summary at
 *     /ageing) with companyId, and does NOT send a customerUid param.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ArService } from './ar.service';

describe('ArService', () => {
  let service: ArService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ArService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  it('getAgeing() GETs /ar/ageing/by-customer with companyId', () => {
    service.getAgeing('10').subscribe();

    const req = httpMock.expectOne((r) => r.url.includes('/ar/ageing/by-customer'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('companyId')).toBe('10');
    expect(req.request.params.has('customerUid')).toBe(false);
    req.flush([
      {
        customerId: '5',
        customerCode: 'ACME',
        customerName: 'Acme Traders',
        current: '100.00',
        days1to30: '0.00',
        days31to60: '0.00',
        days61to90: '0.00',
        days91Plus: '0.00',
        total: '100.00',
        currency: 'TZS',
      },
    ]);
  });

  it('getAgeing() never calls the deprecated per-bucket /ageing endpoint', () => {
    service.getAgeing('10').subscribe();

    const req = httpMock.expectOne((r) => r.url.includes('/ar/ageing/by-customer'));
    expect(req.request.url.endsWith('/ageing')).toBe(false);
    req.flush([]);
  });
});
