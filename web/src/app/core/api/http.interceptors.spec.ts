import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { vi } from 'vitest';
import { environment } from '../../../environments/environment';
import { SessionStore } from '../auth/session.store';
import { AlertService } from '../feedback/alert.service';
import { ToastService } from '../feedback/toast.service';
import { SILENT_ERROR } from './http-context.tokens';
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
  const toastError = vi.fn();
  const alertError = vi.fn();
  /** The page the user is on when a 401 fires — captured into returnUrl by the interceptor. */
  const currentUrl = '/admin/widgets';

  beforeEach(() => {
    navigateByUrl.mockReset();
    navigate.mockReset();
    toastError.mockReset();
    alertError.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([authErrorInterceptor, authHeaderInterceptor, apiResponseInterceptor]),
        ),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigateByUrl, navigate, url: currentUrl } },
        { provide: ToastService, useValue: { error: toastError } },
        { provide: AlertService, useValue: { error: alertError } },
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

  describe('expired access token (refresh)', () => {
    const api = environment.apiBaseUrl;
    const user = {
      uid: 'U1', username: 'amina', displayName: 'Amina', isRoot: false,
      activeCompanyUid: 'C1', activeBranchUid: 'DEFAULT-BRANCH', hasBranch: true,
    };
    const unauthorized = { status: 401, statusText: 'Unauthorized' };
    const tokens = (access: string, refresh: string) => ({
      data: { accessToken: access, accessTokenExpiresAt: 0, refreshToken: refresh, user },
      errors: [],
    });

    beforeEach(() => session.setSession('old-access', 'refresh-1', user));

    it('refreshes once and retries the request with the new token — the user stays signed in', () => {
      let result: unknown;
      http.get(`${api}/companies`).subscribe((r) => (result = r));

      httpMock.expectOne(`${api}/companies`).flush({ data: null, errors: ['expired'] }, unauthorized);
      const refresh = httpMock.expectOne(`${api}/auth/refresh`);
      expect(refresh.request.body).toEqual({ refreshToken: 'refresh-1' });
      refresh.flush(tokens('new-access', 'refresh-2'));

      const retry = httpMock.expectOne(`${api}/companies`);
      expect(retry.request.headers.get('Authorization')).toBe('Bearer new-access');
      retry.flush({ data: ['ok'], errors: [] });

      expect(result).toEqual(['ok']);
      expect(session.accessToken()).toBe('new-access');
      expect(session.refreshToken()).toBe('refresh-2');
      expect(navigate).not.toHaveBeenCalled();
      expect(toastError).not.toHaveBeenCalled();
    });

    it('shares ONE refresh between requests that expire together (a reused token revokes every session)', () => {
      const results: unknown[] = [];
      http.get(`${api}/companies`).subscribe((r) => results.push(r));
      http.get(`${api}/branches`).subscribe((r) => results.push(r));

      httpMock.expectOne(`${api}/companies`).flush({ data: null, errors: [] }, unauthorized);
      httpMock.expectOne(`${api}/branches`).flush({ data: null, errors: [] }, unauthorized);
      httpMock.expectOne(`${api}/auth/refresh`).flush(tokens('new-access', 'refresh-2'));

      httpMock.expectOne(`${api}/companies`).flush({ data: 'c', errors: [] });
      httpMock.expectOne(`${api}/branches`).flush({ data: 'b', errors: [] });
      expect(results.sort()).toEqual(['b', 'c']);
    });

    it('keeps the branch the user switched to — a refresh names only their DEFAULT branch', () => {
      session.setActiveBranchUid('SWITCHED-BRANCH');
      http.get(`${api}/companies`).subscribe();

      httpMock.expectOne(`${api}/companies`).flush({ data: null, errors: [] }, unauthorized);
      httpMock.expectOne(`${api}/auth/refresh`).flush(tokens('new-access', 'refresh-2'));

      const retry = httpMock.expectOne(`${api}/companies`);
      expect(retry.request.headers.get('X-Branch-Uid')).toBe('SWITCHED-BRANCH');
      retry.flush({ data: {}, errors: [] });
      expect(session.activeBranchUid()).toBe('SWITCHED-BRANCH');
    });

    it('when the refresh is refused, ends the session and sends the user to login', () => {
      let failed: unknown;
      http.get(`${api}/companies`).subscribe({ error: (e) => (failed = e) });

      httpMock.expectOne(`${api}/companies`).flush({ data: null, errors: [] }, unauthorized);
      httpMock
        .expectOne(`${api}/auth/refresh`)
        .flush({ data: null, errors: ['Refresh token expired. Please sign in again.'] }, unauthorized);

      expect((failed as { status: number }).status).toBe(401);
      expect(session.isAuthenticated()).toBe(false);
      expect(toastError).toHaveBeenCalledWith('Your session has expired. Please sign in again.');
      expect(navigate).toHaveBeenCalledWith(['/login'], {
        queryParams: { returnUrl: currentUrl },
        replaceUrl: true,
      });
    });

    it('does not loop: a retried request that is still 401 ends the session, with no second refresh', () => {
      http.get(`${api}/companies`).subscribe({ error: () => undefined });

      httpMock.expectOne(`${api}/companies`).flush({ data: null, errors: [] }, unauthorized);
      httpMock.expectOne(`${api}/auth/refresh`).flush(tokens('new-access', 'refresh-2'));
      httpMock.expectOne(`${api}/companies`).flush({ data: null, errors: [] }, unauthorized);

      httpMock.expectNone(`${api}/auth/refresh`);
      expect(session.isAuthenticated()).toBe(false);
      expect(navigate).toHaveBeenCalledTimes(1);
    });

    it('never refreshes a logout — renewing a session only to revoke it is pointless', () => {
      http.post(`${api}/auth/logout`, { refreshToken: 'refresh-1' }).subscribe({ error: () => undefined });

      httpMock.expectOne(`${api}/auth/logout`).flush({ data: null, errors: [] }, unauthorized);

      httpMock.expectNone(`${api}/auth/refresh`);
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

  it('surfaces a 409 business validation as a calm toast, NOT the blocking modal', () => {
    session.setAccessToken('tok');
    http.post(`${environment.apiBaseUrl}/goods-receipts`, {}).subscribe({ error: () => undefined });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/goods-receipts`);
    req.flush(
      { data: null, errors: ['Over-receipt rejected for TEST PRODUCT: reduce the quantity.'] },
      { status: 409, statusText: 'Conflict' },
    );

    expect(toastError).toHaveBeenCalledWith('Over-receipt rejected for TEST PRODUCT: reduce the quantity.');
    expect(alertError).not.toHaveBeenCalled();
  });

  it('surfaces a 422 business rule as a calm toast', () => {
    http.post(`${environment.apiBaseUrl}/currencies/enable`, {}).subscribe({ error: () => undefined });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/currencies/enable`);
    req.flush(
      { data: null, errors: ['That currency is not enabled for this company.'] },
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    expect(toastError).toHaveBeenCalledWith('That currency is not enabled for this company.');
    expect(alertError).not.toHaveBeenCalled();
  });

  it('surfaces a 500 as the blocking "Something went wrong" modal', () => {
    http.get(`${environment.apiBaseUrl}/companies`).subscribe({ error: () => undefined });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/companies`);
    req.flush({ data: null, errors: ['boom'] }, { status: 500, statusText: 'Server Error' });

    expect(alertError).toHaveBeenCalledWith('Something went wrong', 'boom');
    expect(toastError).not.toHaveBeenCalled();
  });

  it('stays silent (no toast, no modal) when SILENT_ERROR is set on the request', () => {
    http
      .post(`${environment.apiBaseUrl}/goods-receipts`, {}, {
        context: new HttpContext().set(SILENT_ERROR, true),
      })
      .subscribe({ error: () => undefined });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/goods-receipts`);
    req.flush(
      { data: null, errors: ['Over-receipt rejected for TEST PRODUCT: reduce the quantity.'] },
      { status: 409, statusText: 'Conflict' },
    );

    expect(toastError).not.toHaveBeenCalled();
    expect(alertError).not.toHaveBeenCalled();
  });
});
