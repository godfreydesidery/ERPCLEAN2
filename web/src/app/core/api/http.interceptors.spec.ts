import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { SessionStore } from '../auth/session.store';
import { apiResponseInterceptor, authHeaderInterceptor } from './http.interceptors';

describe('http interceptors', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let session: SessionStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([authHeaderInterceptor, apiResponseInterceptor]),
        ),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionStore);
    session.clear();
  });

  afterEach(() => httpMock.verify());

  it('unwraps the ApiResponse envelope to the raw payload', () => {
    let result: unknown;
    http.get(`${environment.apiBaseUrl}/health`).subscribe((r) => (result = r));

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/health`);
    req.flush({ data: { status: 'UP' }, errors: [] });

    expect(result).toEqual({ status: 'UP' });
  });

  it('attaches Authorization and X-Branch-Uid when a session exists', () => {
    session.setAccessToken('tok123');
    session.setActiveBranchUid('01BRANCHUID');
    http.get(`${environment.apiBaseUrl}/health`).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/health`);
    expect(req.request.headers.get('Authorization')).toBe('Bearer tok123');
    expect(req.request.headers.get('X-Branch-Uid')).toBe('01BRANCHUID');
    req.flush({ data: {}, errors: [] });
  });

  it('does not attach auth headers when unauthenticated', () => {
    http.get(`${environment.apiBaseUrl}/health`).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/health`);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({ data: {}, errors: [] });
  });
});
