import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { BranchService } from './branch.service';

describe('BranchService', () => {
  let service: BranchService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiBaseUrl}/branches`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BranchService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists branches scoped by company uid', () => {
    service.list('COMP1').subscribe();
    const req = httpMock.expectOne((r) => r.url === base && r.params.get('companyUid') === 'COMP1');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('setDefault PUTs to the /default sub-resource', () => {
    service.setDefault('BR1').subscribe();
    const req = httpMock.expectOne(`${base}/uid/BR1/default`);
    expect(req.request.method).toBe('PUT');
    req.flush({});
  });

  it('create posts the request body', () => {
    service
      .create({ companyUid: 'COMP1', code: 'BR-01', name: 'Kariakoo', makeDefault: true })
      .subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.makeDefault).toBe(true);
    req.flush({});
  });
});
