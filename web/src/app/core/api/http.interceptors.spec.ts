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
  const toastError = vi.fn();
  const alertError = vi.fn();

  beforeEach(() => {
    navigateByUrl.mockReset();
    toastError.mockReset();
    alertError.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([authErrorInterceptor, authHeaderInterceptor, apiResponseInterceptor]),
        ),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigateByUrl } },
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

  it('on 401 from an authenticated call, clears the session and redirects to login', () => {
    session.setAccessToken('expired-token');
    http.get(`${environment.apiBaseUrl}/companies`).subscribe({ error: () => undefined });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/companies`);
    req.flush({ data: null, errors: ['Unauthorized'] }, { status: 401, statusText: 'Unauthorized' });

    expect(session.isAuthenticated()).toBe(false);
    expect(navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('does NOT redirect on a 401 from the login endpoint (bad credentials is the caller\'s concern)', () => {
    http.post(`${environment.apiBaseUrl}/auth/login`, { username: 'x', password: 'bad' }).subscribe({
      error: () => undefined,
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`);
    req.flush({ data: null, errors: ['Invalid username or password.'] }, { status: 401, statusText: 'Unauthorized' });

    expect(navigateByUrl).not.toHaveBeenCalled();
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
