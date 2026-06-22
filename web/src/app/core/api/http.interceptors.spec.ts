import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { vi } from 'vitest';
import { environment } from '../../../environments/environment';
import { SessionStore } from '../auth/session.store';
import {
  apiResponseInterceptor,
  authErrorInterceptor,
  authHeaderInterceptor,
} from './http.interceptors';

describe('http interceptors', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let session: SessionStore;
  const navigateByUrl = vi.fn();
  const navigate = vi.fn();
  /** The page the user is on when a 401 fires — captured into returnUrl by the interceptor. */
  const currentUrl = '/admin/widgets';

  beforeEach(() => {
    navigateByUrl.mockReset();
    navigate.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([authErrorInterceptor, authHeaderInterceptor, apiResponseInterceptor]),
        ),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigateByUrl, navigate, url: currentUrl } },
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

  it('does NOT attach a bearer token to login even when one is stored (stale-token poisoning fix)', () => {
    session.setAccessToken('stale-token');
    http.post(`${environment.apiBaseUrl}/auth/login`, { username: 'x', password: 'y' }).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({ data: {}, errors: [] });
  });

  it('does NOT attach a bearer token to refresh even when one is stored', () => {
    session.setAccessToken('stale-token');
    http.post(`${environment.apiBaseUrl}/auth/refresh`, {}).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/refresh`);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({ data: {}, errors: [] });
  });

  it('on 401 from an authenticated call, clears the session and redirects to login with returnUrl', () => {
    session.setAccessToken('expired-token');
    http.get(`${environment.apiBaseUrl}/companies`).subscribe({ error: () => undefined });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/companies`);
    req.flush({ data: null, errors: ['Unauthorized'] }, { status: 401, statusText: 'Unauthorized' });

    expect(session.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: currentUrl },
      replaceUrl: true,
    });
  });

  it('does NOT redirect on a 401 from the login endpoint (bad credentials is the caller\'s concern)', () => {
    http.post(`${environment.apiBaseUrl}/auth/login`, { username: 'x', password: 'bad' }).subscribe({
      error: () => undefined,
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`);
    req.flush({ data: null, errors: ['Invalid username or password.'] }, { status: 401, statusText: 'Unauthorized' });

    expect(navigate).not.toHaveBeenCalled();
  });
});
