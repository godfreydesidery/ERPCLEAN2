import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { UserCompanyService } from './user-company.service';

describe('UserCompanyService', () => {
  let service: UserCompanyService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiBaseUrl}/user-companies`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UserCompanyService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists memberships for a user via GET /user-companies?userUid=', () => {
    service.listForUser('U1').subscribe();
    const req = httpMock.expectOne(`${base}?userUid=U1`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('assigns a company membership via POST /user-companies', () => {
    service.assign({ userUid: 'U1', companyUid: 'C1' }).subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ userUid: 'U1', companyUid: 'C1' });
    req.flush({}, { status: 201, statusText: 'Created' });
  });

  it('removes a membership via DELETE /user-companies/uid/{uid}', () => {
    service.remove('M1').subscribe();
    const req = httpMock.expectOne(`${base}/uid/M1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});
